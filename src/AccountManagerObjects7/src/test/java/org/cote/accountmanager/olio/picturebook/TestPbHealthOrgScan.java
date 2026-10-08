package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Before;
import org.junit.Test;

/// The organization health scan audits every readable book, and its grant audit repeats the universe tier
/// (identical for every book) and the series world tier (identical for every chapter) once per book. Measured
/// 2026-10-07 on the Docker stack: 37 books, 111 s, every thread-dump sample in checkEntitlement. The per-scan
/// memo (PbOlioContextUtil.GrantAuditScan) must (1) return the SAME findings a stand-alone audit does, (2) serve
/// the repeated tiers from the memo, and (3) propagate a universe-tier gap to EVERY book of the scan, then heal
/// it through the normal org repair. Real DB, no LLM, non-admin user.
public class TestPbHealthOrgScan extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> findings(Map<String, Object> report) {
		return (List<Map<String, Object>>) report.get("findings");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> refs(Map<String, Object> entry) {
		return (Map<String, Object>) entry.get("refs");
	}

	private static Set<String> gapKeys(PbOlioContextUtil.GrantAudit audit) {
		Set<String> keys = new HashSet<>();
		for (Map<String, Object> g : audit.missingGrants) {
			keys.add(g.get("tier") + "|" + g.get("role") + "|" + g.get("groupId"));
		}
		return keys;
	}

	@Test
	public void TestScanMemoMatchesStandaloneAndPropagatesUniverseGap() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord owner = getCreateUser("pbHealthScan");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		String tag = shortId();

		BaseRecord bookA = PbBookUtil.createBook(owner, dataPath, "hsa" + tag, "Scan A " + tag);
		BaseRecord bookB = PbBookUtil.createBook(owner, dataPath, "hsb" + tag, "Scan B " + tag);
		assertNotNull("book A", bookA);
		assertNotNull("book B", bookB);
		String seriesSlug = "hss" + tag;
		BaseRecord series = PbSeriesUtil.getCreateSeries(owner, dataPath, seriesSlug, "Scan Series " + tag);
		assertNotNull("series", series);
		BaseRecord ch1 = PbBookUtil.createBook(owner, dataPath, "hsc1" + tag, "Scan Ch 1 " + tag, series, 0);
		BaseRecord ch2 = PbBookUtil.createBook(owner, dataPath, "hsc2" + tag, "Scan Ch 2 " + tag, series, 1);
		assertNotNull("chapter 1", ch1);
		assertNotNull("chapter 2", ch2);
		List<String> bookOids = new ArrayList<>();
		for (BaseRecord b : new BaseRecord[] {bookA, bookB, ch1, ch2}) {
			bookOids.add(b.get(FieldNames.FIELD_OBJECT_ID));
		}

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);
		OrganizationContext octx = ioContext.findOrganizationContext(owner);
		assertNotNull("org context", octx);

		try {
			/// 1. Memoized results equal the stand-alone ones, and the repeated tiers are served from the memo.
			PbOlioContextUtil.GrantAudit aloneA = PbOlioContextUtil.checkGrants(owner, "hsa" + tag, false);
			PbOlioContextUtil.GrantAudit aloneB = PbOlioContextUtil.checkGrants(owner, "hsb" + tag, false);
			PbOlioContextUtil.GrantAudit aloneS = PbOlioContextUtil.checkGrants(owner, seriesSlug, true);

			PbOlioContextUtil.GrantAuditScan scan = new PbOlioContextUtil.GrantAuditScan();
			long t0 = System.currentTimeMillis();
			PbOlioContextUtil.GrantAudit memoA = PbOlioContextUtil.checkGrants(owner, "hsa" + tag, false, null, scan);
			long tFirst = System.currentTimeMillis() - t0;
			t0 = System.currentTimeMillis();
			PbOlioContextUtil.GrantAudit memoB = PbOlioContextUtil.checkGrants(owner, "hsb" + tag, false, null, scan);
			long tSecond = System.currentTimeMillis() - t0;
			PbOlioContextUtil.GrantAudit memoS1 = PbOlioContextUtil.checkGrants(owner, seriesSlug, true, null, scan);
			t0 = System.currentTimeMillis();
			PbOlioContextUtil.GrantAudit memoS2 = PbOlioContextUtil.checkGrants(owner, seriesSlug, true, null, scan);
			long tRepeat = System.currentTimeMillis() - t0;
			System.out.println("[TestPbHealthOrgScan] checkGrants ms: first(book A, both tiers)=" + tFirst
				+ " second(book B, universe memoized)=" + tSecond + " repeat(series, fully memoized)=" + tRepeat);

			assertEquals("A: memo == standalone", gapKeys(aloneA), gapKeys(memoA));
			assertEquals("B: memo == standalone", gapKeys(aloneB), gapKeys(memoB));
			assertEquals("S: memo == standalone", gapKeys(aloneS), gapKeys(memoS1));
			assertEquals("A/B/S: standalone audits were clean", 0, aloneA.missingGrants.size() + aloneB.missingGrants.size() + aloneS.missingGrants.size());
			assertEquals("universe tier audited once, served twice (B, S1)", 2, scan.universeHits);
			assertEquals("series world served from memo on the repeat", 1, scan.worldHits);
			assertTrue("the repeat is the same audit object", memoS1 == memoS2);

			/// 2. The org scan uses the memo and matches the per-book reports for the owner's books.
			long tOrg0 = System.currentTimeMillis();
			Map<String, Object> org = PbHealthUtil.checkOrg(owner);
			long tOrg = System.currentTimeMillis() - tOrg0;
			System.out.println("[TestPbHealthOrgScan] checkOrg(" + ((List<?>) org.get("findings")).size() + " findings) ms=" + tOrg);
			for (Map<String, Object> f : findings(org)) {
				assertFalse("org scan clean of GRANTS_MISSING before revoke: " + f, PbHealthUtil.GRANTS_MISSING.equals(f.get("code")) && bookOids.contains(refs(f).get("bookObjectId")));
			}

			/// 3. Revoke one universe-tier Read grant and the gap shows on EVERY book of the scan - same groupId.
			BaseRecord readerRole = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE, PbOlioContextUtil.universeReaderRolePath(), RoleEnumType.USER.toString(), orgId);
			assertNotNull("universe Reader role", readerRole);
			BaseRecord readPerm = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_PERMISSION, "/Read", PermissionEnumType.DATA.toString(), orgId);
			assertNotNull("Read permission", readPerm);
			BaseRecord worldA = WorldUtil.findWorld(olioUser, PbOlioContextUtil.bookWorldPath(), "hsa" + tag);
			assertNotNull("world A", worldA);
			BookContext bctx = PbOlioContextUtil.assembleBookContext(worldA);
			assertNotNull("book context", bctx);
			List<BaseRecord> universeGroups = OlioContext.findAuthorizationGroups(olioUser, bctx.getUniverse(), new OlioContextConfiguration().getUniversePath(), octx);
			assertFalse("universe groups enumerated", universeGroups.isEmpty());
			/// Revoke on the LAST own group of the universe (not a shared /Library corpus) so no other org data is touched.
			BaseRecord target = universeGroups.get(universeGroups.size() - 1);
			long targetId = ((Number) target.get(FieldNames.FIELD_ID)).longValue();
			assertTrue("Reader has Read on universe group before revoke", ioContext.getAuthorizationUtil().checkEntitlement(readerRole, readPerm, target));
			assertTrue("revoke", ioContext.getMemberUtil().member(olioUser, target, readerRole, readPerm, false));
			CacheUtil.clearCache();
			OlioContextUtil.clearCache();
			assertFalse("revoked (precondition)", ioContext.getAuthorizationUtil().checkEntitlement(readerRole, readPerm, target));

			try {
				Map<String, Object> broken = PbHealthUtil.checkOrg(owner);
				Set<String> booksWithGap = new HashSet<>();
				for (Map<String, Object> f : findings(broken)) {
					if (PbHealthUtil.GRANTS_MISSING.equals(f.get("code")) && bookOids.contains(refs(f).get("bookObjectId"))
						&& "universe".equals(refs(f).get("tier")) && refs(f).get("groupId") instanceof Number
						&& ((Number) refs(f).get("groupId")).longValue() == targetId) {
						booksWithGap.add((String) refs(f).get("bookObjectId"));
					}
				}
				assertEquals("the memoized universe gap is reported on all four books: " + booksWithGap, new HashSet<>(bookOids), booksWithGap);

				/// 4. The org repair restores it and the post-heal audit (a fresh scan) is clean.
				Map<String, Object> healed = PbHealthUtil.healOrg(owner, dataPath, null, false);
				CacheUtil.clearCache();
				assertTrue("Reader has Read on universe group again", ioContext.getAuthorizationUtil().checkEntitlement(readerRole, readPerm, target));
				for (Map<String, Object> f : findings(healed)) {
					assertFalse("post-heal org scan clean of GRANTS_MISSING: " + f, PbHealthUtil.GRANTS_MISSING.equals(f.get("code")) && bookOids.contains(refs(f).get("bookObjectId")));
				}
			}
			finally {
				/// Belt and braces: never leave the shared universe grant revoked for the rest of the suite.
				ioContext.getMemberUtil().member(olioUser, target, readerRole, readPerm, true);
				CacheUtil.clearCache();
			}
		}
		finally {
			for (BaseRecord b : new BaseRecord[] {bookA, bookB}) {
				DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, b.get(FieldNames.FIELD_OBJECT_ID));
				assertTrue("cleanup book: " + res.reason, res.deleted);
			}
			assertEquals("cleanup series tears down its two chapters", 2, PbSeriesUtil.deleteSeries(owner, series.get(FieldNames.FIELD_OBJECT_ID)));
		}
	}
}
