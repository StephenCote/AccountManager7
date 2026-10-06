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
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Before;
import org.junit.Test;

/// Grants are health-checked and self-healed through code, never by hand. A fresh book audits clean; a
/// revoked Read entitlement (Writer role on the world's Workflow group - the Book group is left alone so the
/// owner can still read the book row) is reported as GRANTS_MISSING by the read-only check, which grants
/// nothing itself; healBook re-applies the grants through getCreateBookContext's add-only pass; the
/// entitlement is back and the re-check is clean. Finally a second user creates a series and its grant audit
/// is clean - the regression guard for the cached group-children read that produced "Missing Read grant for
/// Reader on universe group Book" on the first series in an organization.
/// Real DB, no LLM, non-admin users throughout.
public class TestPbHealthGrants extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private static BaseRecord findGroup(BaseRecord actor, String path, long orgId) {
		return IOSystem.getActiveContext().getPathUtil().findPath(actor, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), orgId);
	}

	private static BaseRecord findRole(BaseRecord actor, String path, long orgId) {
		return IOSystem.getActiveContext().getPathUtil().findPath(actor, ModelNames.MODEL_ROLE, path,
			RoleEnumType.USER.toString(), orgId);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> findings(Map<String, Object> report) {
		return (List<Map<String, Object>>) report.get("findings");
	}

	private static List<Map<String, Object>> findingsOf(Map<String, Object> report, String code) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> f : findings(report)) {
			if (code.equals(f.get("code"))) {
				out.add(f);
			}
		}
		return out;
	}

	private static Set<String> findingCodes(Map<String, Object> report) {
		Set<String> codes = new HashSet<>();
		for (Map<String, Object> f : findings(report)) {
			codes.add((String) f.get("code"));
		}
		return codes;
	}

	@SuppressWarnings("unchecked")
	private static Set<String> healedCodes(Map<String, Object> report) {
		Set<String> codes = new HashSet<>();
		for (Map<String, Object> h : (List<Map<String, Object>>) report.get("healed")) {
			codes.add((String) h.get("code"));
		}
		return codes;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> refs(Map<String, Object> entry) {
		return (Map<String, Object>) entry.get("refs");
	}

	@Test
	public void TestRevokedGrantIsReportedAndHealed() {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord owner = getCreateUser("pbHealthOwner");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		String slug = "hgr" + shortId();

		BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "Grants " + slug);
		assertNotNull("book", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);

		Map<String, Object> clean = PbHealthUtil.checkBook(owner, bookOid);
		assertFalse("fresh book has no GRANTS_MISSING: " + findingCodes(clean), findingCodes(clean).contains(PbHealthUtil.GRANTS_MISSING));
		assertFalse("fresh book has no ROLES_MISSING: " + findingCodes(clean), findingCodes(clean).contains(PbHealthUtil.ROLES_MISSING));

		/// Revoke one entitlement the way a broken environment would present it.
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);
		BaseRecord writerRole = findRole(olioUser, PbOlioContextUtil.writerRolePath(slug), orgId);
		assertNotNull("writer role", writerRole);
		BaseRecord readPerm = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_PERMISSION, "/Read", PermissionEnumType.DATA.toString(), orgId);
		assertNotNull("Read permission", readPerm);
		String wfPath = PbBookUtil.workflowGroupPath(slug);
		BaseRecord wfGroup = findGroup(olioUser, wfPath, orgId);
		assertNotNull("Workflow group", wfGroup);
		String wfGroupName = wfPath.substring(wfPath.lastIndexOf('/') + 1);
		assertTrue("writer has Read on Workflow before revoke", ioContext.getAuthorizationUtil().checkEntitlement(writerRole, readPerm, wfGroup));
		assertTrue("revoke Writer Read on Workflow", ioContext.getMemberUtil().member(olioUser, wfGroup, writerRole, readPerm, false));
		CacheUtil.clearCache();
		assertFalse("writer lacks Read on Workflow after revoke (precondition)", ioContext.getAuthorizationUtil().checkEntitlement(writerRole, readPerm, wfGroup));

		/// The read-only check names the exact gap and does not repair it.
		Map<String, Object> before = PbHealthUtil.checkBook(owner, bookOid);
		List<Map<String, Object>> gaps = findingsOf(before, PbHealthUtil.GRANTS_MISSING);
		assertFalse("GRANTS_MISSING reported: " + findingCodes(before), gaps.isEmpty());
		Map<String, Object> wfGap = null;
		for (Map<String, Object> g : gaps) {
			if (wfGroupName.equals(refs(g).get("group"))) {
				wfGap = g;
			}
		}
		assertNotNull("the gap is on the Workflow group: " + gaps, wfGap);
		assertEquals("world", refs(wfGap).get("tier"));
		assertEquals(writerRole.get(FieldNames.FIELD_NAME), refs(wfGap).get("role"));
		assertEquals(((Number) wfGroup.get(FieldNames.FIELD_ID)).longValue(), ((Number) refs(wfGap).get("groupId")).longValue());
		assertEquals("ERROR", wfGap.get("severity"));
		assertEquals(Boolean.TRUE, wfGap.get("healable"));
		CacheUtil.clearCache();
		assertFalse("checkBook granted nothing", ioContext.getAuthorizationUtil().checkEntitlement(writerRole, readPerm, wfGroup));

		/// Repair.
		Map<String, Object> healReport = PbHealthUtil.healBook(owner, dataPath, bookOid, null, false);
		assertTrue("GRANTS_MISSING healed: " + healReport.get("healed") + " skipped=" + healReport.get("skipped"),
			healedCodes(healReport).contains(PbHealthUtil.GRANTS_MISSING));
		assertTrue("post-heal audit clear of GRANTS_MISSING: " + findingsOf(healReport, PbHealthUtil.GRANTS_MISSING),
			findingsOf(healReport, PbHealthUtil.GRANTS_MISSING).isEmpty());
		CacheUtil.clearCache();
		assertTrue("writer has Read on Workflow again", ioContext.getAuthorizationUtil().checkEntitlement(writerRole, readPerm, wfGroup));

		Map<String, Object> again = PbHealthUtil.checkBook(owner, bookOid);
		assertFalse("re-check clean: " + findingCodes(again), findingCodes(again).contains(PbHealthUtil.GRANTS_MISSING));

		/// Idempotent: a healthy book heals nothing.
		Map<String, Object> idempotent = PbHealthUtil.healBook(owner, dataPath, bookOid, null, false);
		assertFalse("nothing healed the second time: " + healedCodes(idempotent), healedCodes(idempotent).contains(PbHealthUtil.GRANTS_MISSING));

		DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bookOid);
		assertTrue("cleanup: " + res.reason, res.deleted);
	}

	@Test
	public void TestSecondUserSeriesAuditsClean() {
		OlioModelNames.use();
		BaseRecord user2 = getCreateUser("pbHealthUser2");
		assertNotNull("user2", user2);
		String dataPath = testProperties.getProperty("test.datagen.path");
		String seriesSlug = "hgs" + shortId();

		/// getCreateSeries throws on "Missing Read grant ..." from verifyGrants; reaching the assertion is the test.
		BaseRecord series = PbSeriesUtil.getCreateSeries(user2, dataPath, seriesSlug, "Grants Series " + seriesSlug);
		assertNotNull("series", series);
		assertEquals(seriesSlug, PbSeriesUtil.seriesSlug(series));

		PbOlioContextUtil.GrantAudit audit = PbOlioContextUtil.checkGrants(user2, seriesSlug, true);
		assertTrue("series grant audit clean; error=" + audit.error + " roles=" + audit.missingRoles + " grants=" + audit.missingGrants, audit.clean());

		/// deleteSeries returns the chapter count (0 here) and throws on any failure; the row being gone is the check.
		long orgId = ((Number) user2.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("no chapters torn down", 0, PbSeriesUtil.deleteSeries(user2, seriesOid));
		BaseRecord olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertTrue("series row removed", PbSeriesUtil.readSeries(olioUser, seriesOid, orgId) == null);
	}
}
