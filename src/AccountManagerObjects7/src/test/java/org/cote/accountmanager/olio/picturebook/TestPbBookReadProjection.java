package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Test;

/// §6.A remedy option 2 - the read-path projection. {@code PbBookUtil.readBook} / {@code findBookBySlug} /
/// {@code requireBookUpdate} now AUTHORIZE the book on {@code bookAuthorizingRequest()} (the book row and its
/// own-group config, NO linkage FKs) and then populate the four linkage FKs ({@code world}, {@code series},
/// {@code sourceData}, {@code sourceRange}) raw, so the record every caller receives still has today's
/// {@code bookRequest()} shape. Two things are proven here, on a real series chapter over the real manuscript:
///
/// 1. SHAPE: the record {@code readBook} returns is field-for-field the record a direct
///    {@code AccessPoint.find} with {@code bookRequest()} returns (as the owner, who can read both) -
///    same field set, same scalar values, same nested identity/common fields, same
///    {@code sourceRange.startOffset/endOffset}. Compared programmatically, not by eye.
/// 2. AUTHORIZATION: a series Writer who is NOT the owner can read the chapter, passes the Update gate
///    ({@code requireBookUpdate} PERMIT, and gets the full shape back), while the SAME Writer is denied Read
///    on the fully-populated record (the pre-change projection) - the narrowing is load-bearing. A stranger
///    still reads nothing. The three bracketed PBAC traces ({@code setTrace(true)} around ONE call each) print
///    the "Add rule for <field>" lines that show which foreign rules each evaluation carries.
///
/// Real DB, no LLM, no SD. Never runs as admin as an acting user: the org admin only applies the Writer
/// enrolment, exactly as production does.
public class TestPbBookReadProjection extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private File locateFixture(String fileName) {
		File[] candidates = new File[] {
			new File("media", fileName),
			new File("AccountManagerObjects7/media", fileName),
			new File("src/AccountManagerObjects7/media", fileName)
		};
		for (File f : candidates) {
			if (f.exists()) {
				return f;
			}
		}
		return null;
	}

	/// The PRE-CHANGE read: a direct AccessPoint.find with bookRequest(), exactly what readBook did before §6.A.
	private BaseRecord legacyReadBook(BaseRecord user, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.bookRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private static Set<String> fieldNames(BaseRecord rec) {
		return rec.getFields().stream().map(FieldType::getName).collect(Collectors.toCollection(HashSet::new));
	}

	private static boolean permits(PolicyResponseType prr) {
		return prr != null && prr.getType() == PolicyResponseEnumType.PERMIT;
	}

	/// Field-by-field equality of two book projections, one level deep into every nested MODEL field.
	private static void assertSameShape(String label, BaseRecord expected, BaseRecord actual) {
		Set<String> expNames = fieldNames(expected);
		Set<String> actNames = fieldNames(actual);
		assertEquals(label + ": field set must be identical to bookRequest()'s", expNames, actNames);
		for (FieldType f : expected.getFields()) {
			String name = f.getName();
			Object ev = expected.get(name);
			Object av = actual.get(name);
			if (f.getValueType() == FieldEnumType.MODEL) {
				if (ev == null) {
					assertNull(label + "." + name + " must be null as in bookRequest()", av);
					continue;
				}
				assertNotNull(label + "." + name + " must be populated as in bookRequest()", av);
				BaseRecord er = (BaseRecord) ev;
				BaseRecord ar = (BaseRecord) av;
				assertEquals(label + "." + name + ".schema", er.getSchema(), ar.getSchema());
				assertEquals(label + "." + name + " nested field set", fieldNames(er), fieldNames(ar));
				for (FieldType nf : er.getFields()) {
					if (nf.getValueType() == FieldEnumType.MODEL || nf.getValueType() == FieldEnumType.LIST) {
						continue;
					}
					assertEquals(label + "." + name + "." + nf.getName(), (Object) er.get(nf.getName()), (Object) ar.get(nf.getName()));
				}
			}
			else if (f.getValueType() == FieldEnumType.LIST) {
				List<?> el = (List<?>) ev;
				List<?> al = (List<?>) av;
				assertEquals(label + "." + name + " list size", (el == null ? 0 : el.size()), (al == null ? 0 : al.size()));
			}
			else {
				assertEquals(label + "." + name, ev, av);
			}
		}
	}

	@Test
	public void testReadBookShapeAndNarrowedAuthorization() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbReadProjOwner");
		assertNotNull("owner user", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		File docx = locateFixture("HarlotsEight_Vol1_SM.docx");
		assertNotNull("Real manuscript fixture must be locatable (media/HarlotsEight_Vol1_SM.docx)", docx);
		BaseRecord sourceData = getCreateFileData(owner, "~/PbReadProjData", docx.getAbsolutePath());
		assertNotNull("sourceData must be created", sourceData);
		String sourceDataOid = sourceData.get(FieldNames.FIELD_OBJECT_ID);

		String tag = shortId();
		String seriesSlug = "rps" + tag;
		BaseRecord series = PbSeriesUtil.getCreateSeries(owner, dataPath, seriesSlug, "Read Proj Series " + tag);
		assertNotNull("series must be created", series);
		String seriesOid = series.get(FieldNames.FIELD_OBJECT_ID);

		String slug = "rpc" + tag;
		Map<String, Object> range = new LinkedHashMap<>();
		range.put(OlioFieldNames.FIELD_PB_START_OFFSET, Integer.valueOf(300));
		range.put(OlioFieldNames.FIELD_PB_END_OFFSET, Integer.valueOf(1700));
		range.put(OlioFieldNames.FIELD_PB_TITLE, "Projection Chapter");
		Map<String, Object> r = PbServiceFacade.createChapter(owner, dataPath, seriesOid, null, slug,
			"Projection Chapter Title", Integer.valueOf(1), sourceDataOid, range, null, null);
		assertNotNull("createChapter", r);
		String bookOid = (String) r.get("bookObjectId");
		assertNotNull("chapter bookObjectId", bookOid);

		// ── 1. SHAPE: readBook == the pre-change bookRequest() read, field by field (as the owner) ──
		BaseRecord legacy = legacyReadBook(owner, bookOid, orgId);
		assertNotNull("owner must read the chapter under the legacy full projection", legacy);
		BaseRecord viaRead = PbBookUtil.readBook(owner, bookOid, orgId);
		assertNotNull("owner must read the chapter via readBook", viaRead);
		assertSameShape("readBook", legacy, viaRead);
		BaseRecord viaSlug = PbBookUtil.findBookBySlug(owner, slug, orgId);
		assertNotNull("owner must read the chapter via findBookBySlug", viaSlug);
		assertSameShape("findBookBySlug", legacy, viaSlug);
		BaseRecord viaGate = PbBookUtil.requireBookUpdate(owner, viaRead, "shape proof");
		assertSameShape("requireBookUpdate", legacy, viaGate);

		// The linkage the 37 callers read, present and correct on the narrowed read.
		// sourceRange: the book read carries the FK with the range's query fields only (its model's
		// query list is id/objectId/ownerId/organizationId/urn - offsets were never on the book read);
		// callers populate the offsets on demand through readChapterSourceRange, so prove that path.
		BaseRecord sr = viaRead.get(OlioFieldNames.FIELD_PB_SOURCE_RANGE);
		assertNotNull("sourceRange must be populated", sr);
		assertTrue("sourceRange.id", ((Number) sr.get(FieldNames.FIELD_ID)).longValue() > 0L);
		assertNotNull("sourceRange.objectId", sr.get(FieldNames.FIELD_OBJECT_ID));
		Integer[] offsets = PictureBookUtil.readChapterSourceRange(viaRead);
		assertNotNull("readChapterSourceRange must resolve the chapter's range from the narrowed read", offsets);
		assertEquals("sourceRange.startOffset", Integer.valueOf(300), offsets[0]);
		assertEquals("sourceRange.endOffset", Integer.valueOf(1700), offsets[1]);
		BaseRecord sd = viaRead.get(OlioFieldNames.FIELD_PB_SOURCE_DATA);
		assertNotNull("sourceData must be populated", sd);
		assertEquals("sourceData identity", sourceDataOid, (String) sd.get(FieldNames.FIELD_OBJECT_ID));
		assertFalse("sourceData must NOT carry the manuscript bytes", sd.hasField(FieldNames.FIELD_BYTE_STORE));
		BaseRecord se = viaRead.get(OlioFieldNames.FIELD_PB_SERIES);
		assertNotNull("series must be populated", se);
		assertEquals("series identity", seriesOid, (String) se.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("series.name", se.get(FieldNames.FIELD_NAME));
		BaseRecord wd = viaRead.get(OlioFieldNames.FIELD_PB_WORLD);
		assertNotNull("world must be populated", wd);
		assertNotNull("world.name", wd.get(FieldNames.FIELD_NAME));
		assertNotNull("world.id", wd.get(FieldNames.FIELD_ID));
		// The authorizing projection is bookRequest() minus exactly the four linkage fields.
		Set<String> authorizing = new HashSet<>(Arrays.asList(PbBookUtil.bookAuthorizingRequest()));
		Set<String> full = new HashSet<>(Arrays.asList(PbBookUtil.bookRequest()));
		for (String f : PbBookUtil.BOOK_LINKAGE_FIELDS) {
			assertTrue("bookRequest() projects " + f, full.contains(f));
			assertFalse("bookAuthorizingRequest() must NOT project " + f, authorizing.contains(f));
		}
		full.removeAll(Arrays.asList(PbBookUtil.BOOK_LINKAGE_FIELDS));
		assertEquals("bookAuthorizingRequest() == bookRequest() - linkage", full, authorizing);
		assertTrue("sdConfig stays in the authorizing read", authorizing.contains(OlioFieldNames.FIELD_PB_SD_CONFIG));
		assertTrue("style stays in the authorizing read", authorizing.contains(OlioFieldNames.FIELD_STYLE));

		// ── 2. AUTHORIZATION: a non-owner series Writer ──
		BaseRecord writer = getCreateUser("pbReadProjWriter");
		assertNotNull("writer user", writer);
		assertFalse("writer must be a different user from the owner",
			((Long) owner.get(FieldNames.FIELD_ID)).equals((Long) writer.get(FieldNames.FIELD_ID)));
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", olioUser);
		BaseRecord writerRole = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.seriesWriterRolePath(seriesSlug), RoleEnumType.USER.toString(), orgId);
		assertNotNull("series Writer role", writerRole);
		assertTrue("enrol writer", ioContext.getMemberUtil().member(orgContext.getAdminUser(), writerRole, writer, null, true));

		// TRACE 1 - the narrowed authorizing read as the Writer (ONE call bracketed).
		System.out.println("=== TRACE 1: readBook(writer) - authorizing projection ===");
		ioContext.getPolicyUtil().setTrace(true);
		BaseRecord asWriter;
		try {
			asWriter = PbBookUtil.readBook(writer, bookOid, orgId);
		}
		finally {
			ioContext.getPolicyUtil().setTrace(false);
		}
		System.out.println("=== TRACE 1 end ===");
		assertNotNull("a non-owner series Writer MUST be able to read the chapter", asWriter);
		assertSameShape("readBook(writer)", legacy, asWriter);

		// TRACE 2 - the SAME Writer evaluated on the fully-populated record (the pre-change projection):
		// the four foreign rules deny. This is why the narrowing exists.
		System.out.println("=== TRACE 2: canRead(writer, populated record) - legacy projection ===");
		ioContext.getPolicyUtil().setTrace(true);
		PolicyResponseType legacyRead;
		try {
			legacyRead = ioContext.getAuthorizationUtil().canRead(writer, writer, asWriter);
		}
		finally {
			ioContext.getPolicyUtil().setTrace(false);
		}
		System.out.println("=== TRACE 2 end: " + (legacyRead == null ? "null" : legacyRead.getType()) + " ===");
		assertFalse("the Writer is DENIED Read on the populated record - the narrowing is load-bearing", permits(legacyRead));
		assertNull("and the legacy full-projection read returns nothing for the Writer", legacyReadBook(writer, bookOid, orgId));

		// TRACE 3 - the Update gate as the Writer: narrow record, PERMIT, full shape returned (ONE call bracketed).
		System.out.println("=== TRACE 3: requireBookUpdate(writer) ===");
		ioContext.getPolicyUtil().setTrace(true);
		BaseRecord gated;
		try {
			gated = PbBookUtil.requireBookUpdate(writer, asWriter, "trace the gate");
		}
		finally {
			ioContext.getPolicyUtil().setTrace(false);
		}
		System.out.println("=== TRACE 3 end ===");
		assertNotNull("requireBookUpdate must PERMIT a series Writer", gated);
		assertSameShape("requireBookUpdate(writer)", legacy, gated);
		assertTrue("canUpdate on the authorizing view PERMITs the Writer",
			permits(ioContext.getAuthorizationUtil().canUpdate(writer, writer, PbBookUtil.authorizingView(asWriter))));
		assertFalse("canUpdate on the populated record would DENY the Writer (the gates must use the view)",
			permits(ioContext.getAuthorizationUtil().canUpdate(writer, writer, asWriter)));
		// authorizingView never mutates its input.
		assertNotNull("the populated record keeps its series after authorizingView", asWriter.get(OlioFieldNames.FIELD_PB_SERIES));
		BaseRecord view = PbBookUtil.authorizingView(asWriter);
		for (String f : PbBookUtil.BOOK_LINKAGE_FIELDS) {
			assertFalse("authorizingView must not carry " + f, view.hasField(f));
		}

		// TRACE 4 - the config FKs STAY in the authorizing read: give the chapter its own sdConfig (created in
		// the book's own group by setBookSdConfig) and re-trace the Writer's read. The trace must now show
		// "Add rule for sdConfig" and still PERMIT - Read on the chapter group is Read on its config.
		BaseRecord sparse = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG, new String[] { OlioFieldNames.FIELD_SD_STEPS });
		sparse.setValue(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(12));
		BaseRecord storedCfg = PbBookUtil.setBookSdConfig(owner, viaRead, sparse);
		assertNotNull("owner must be able to set the chapter's sdConfig", storedCfg);
		System.out.println("=== TRACE 4: readBook(writer) with the book's own sdConfig populated ===");
		ioContext.getPolicyUtil().setTrace(true);
		BaseRecord asWriterCfg;
		try {
			asWriterCfg = PbBookUtil.readBook(writer, bookOid, orgId);
		}
		finally {
			ioContext.getPolicyUtil().setTrace(false);
		}
		System.out.println("=== TRACE 4 end ===");
		assertNotNull("a series Writer MUST still read the chapter once it carries its own sdConfig", asWriterCfg);
		BaseRecord cfgOnRead = asWriterCfg.get(OlioFieldNames.FIELD_PB_SD_CONFIG);
		assertNotNull("the authorizing read carries the populated sdConfig (so a foreign rule WAS evaluated for it)", cfgOnRead);
		assertEquals("sdConfig identity", (String) storedCfg.get(FieldNames.FIELD_OBJECT_ID), (String) cfgOnRead.get(FieldNames.FIELD_OBJECT_ID));
		assertTrue("canUpdate on the authorizing view still PERMITs the Writer with sdConfig populated",
			permits(ioContext.getAuthorizationUtil().canUpdate(writer, writer, PbBookUtil.authorizingView(asWriterCfg))));

		// ── A stranger still reads nothing (no leak through the raw linkage step) ──
		BaseRecord stranger = getCreateUser("pbReadProjStranger");
		assertNotNull("stranger user", stranger);
		assertNull("a stranger must NOT read the chapter", PbBookUtil.readBook(stranger, bookOid, orgId));
		assertNull("a stranger must NOT read the chapter by slug", PbBookUtil.findBookBySlug(stranger, slug, orgId));
		int status = -1;
		try {
			PbBookUtil.requireBookUpdate(stranger, viaRead, "stranger");
		}
		catch (PictureBookException e) {
			status = e.getStatus();
		}
		assertEquals("a stranger's Update gate is a 404 (cannot read), never a leak", 404, status);

		System.out.println("=== §6.A PROOF: writer read=" + (asWriter != null) + " gate=PERMIT legacyRead="
			+ (legacyRead == null ? "null" : legacyRead.getType()) + " stranger=null (book=" + bookOid + ")");
	}
}
