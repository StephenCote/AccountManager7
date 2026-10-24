package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.junit.Before;
import org.junit.Test;

/// P4-13 (d1): the import dual write enrols a scene's extracted cast as scene.characters through
/// AccessPoint.member, so PBAC decides. Owner: the participation is written and a re-import is idempotent.
/// A reader who can read the book, its world, the scene row and the person but may not update the scene:
/// the same call writes nothing, throws nothing, and the import still returns. The reader's grants are
/// fixture-only (the org admin applies them); positive controls prove the reader reaches the member call
/// (reads succeed) and that it is the update check that refuses. Real DB, no LLM, no SD.
public class TestPbPipelineDualWrite extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String OWNER_NAME = "pbDualWriteOwner";
	private static final String READER_NAME = "pbDualWriteRo";

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private OrganizationContext org() {
		OrganizationContext o = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, o);
		return o;
	}

	private BaseRecord user(String name) {
		OrganizationContext o = org();
		BaseRecord u = ioContext.getFactory().getCreateUser(o.getAdminUser(), name, o.getOrganizationId());
		assertNotNull("Failed to resolve " + name, u);
		return u;
	}

	private static String oid(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	private BaseRecord createCharPerson(BaseRecord u, String groupPath, String name, String firstName, String lastName) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		/// CharPersonFactory pre-builds seven placeholder sub-records path-scoped to the creator's HOME
		/// (~/Statistics, ~/Profiles, ...) and DBWriter auto-creates them on create. The read policy follows
		/// those foreign references, so a reader granted the world groups is still denied on the owner's home.
		/// PictureBookUtil.createCharPerson detaches them and re-creates them in the world groups; this fixture
		/// detaches them and creates none - enrolImportedCast reads only id/objectId/name/groupId.
		for(String f : new String[] { OlioFieldNames.FIELD_STATISTICS, OlioFieldNames.FIELD_INSTINCT, FieldNames.FIELD_BEHAVIOR,
				FieldNames.FIELD_PERSONALITY, FieldNames.FIELD_STATE, FieldNames.FIELD_STORE, FieldNames.FIELD_PROFILE }) {
			cp.set(f, null);
		}
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, firstName);
		cp.set(FieldNames.FIELD_LAST_NAME, lastName);
		cp.set(FieldNames.FIELD_GENDER, "female");
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("create charPerson " + name, created);
		cp.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		cp.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		return cp;
	}

	private BaseRecord readPersonAs(BaseRecord u, String personOid, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, personOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID });
		q.setCache(false);
		return ioContext.getAccessPoint().find(u, q);
	}

	private static List<Map<String, Object>> oneSceneList(String title) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put(OlioFieldNames.FIELD_PB_TITLE, title);
		s.put(OlioFieldNames.FIELD_PB_SETTING, "a rain-dark harbour street at dusk");
		s.put(OlioFieldNames.FIELD_PB_ACTION, "walking toward the tavern door");
		s.put(OlioFieldNames.FIELD_PB_MOOD, "wary");
		s.put(OlioFieldNames.FIELD_PB_BLURB, "Mira crosses the quay as the lamps come on.");
		List<Map<String, Object>> out = new ArrayList<>();
		out.add(s);
		return out;
	}

	private static List<BaseRecord> oneMetaScene(String... personOids) throws Exception {
		BaseRecord entry = RecordFactory.newInstance(OlioModelNames.MODEL_PICTURE_BOOK_SCENE);
		List<String> chars = entry.get(OlioFieldNames.FIELD_PB_CHARACTERS);
		for (String o : personOids) {
			chars.add(o);
		}
		List<BaseRecord> out = new ArrayList<>();
		out.add(entry);
		return out;
	}

	private static boolean permitted(PolicyResponseType prt) {
		return prt != null && prt.getType() == PolicyResponseEnumType.PERMIT;
	}

	@Test
	public void TestOwnerEnrolsCastAndReaderIsRefusedByPbac() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext o = org();
		BaseRecord owner = user(OWNER_NAME);
		BaseRecord reader = user(READER_NAME);
		long orgId = o.getOrganizationId();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		String slug = "dw" + shortId();
		BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "Dual write " + slug);
		assertNotNull("book", book);
		String bookOid = oid(book);
		try {
			BookContext bctx = PbBookUtil.openBookContext(owner, book);
			assertNotNull("book context", bctx);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("population group path", popPath);
			BaseRecord mira = createCharPerson(owner, popPath, "Mira Vale", "Mira", "Vale");
			String miraOid = oid(mira);

			/// The scene row exists before either dual write, so the reader's pass reaches the enrol step
			/// instead of failing at a row create.
			BaseRecord scene = PbBookUtil.createScene(owner, book, 0, "Lamps on the quay", PbBookUtil.bookGroupPath(slug));
			assertNotNull("scene row", scene);
			String sceneOid = oid(scene);
			assertFalse("precondition: no participation yet",
				ioContext.getMemberUtil().isMember(mira, scene, OlioFieldNames.FIELD_PB_CHARACTERS));

			/// ── owner ──
			int wrote = PbPipelineUtil.dualWriteImportedScenes(owner, book, oneSceneList("Lamps on the quay"), oneMetaScene(miraOid));
			assertEquals("the owner's dual write refreshed the one row", 1, wrote);
			BaseRecord fresh = PbBookUtil.readScene(owner, sceneOid, orgId);
			assertNotNull("scene re-read", fresh);
			assertEquals("the text patch landed", "a rain-dark harbour street at dusk", fresh.get(OlioFieldNames.FIELD_PB_SETTING));
			assertTrue("the owner enrolled Mira as scene.characters",
				ioContext.getMemberUtil().isMember(mira, fresh, OlioFieldNames.FIELD_PB_CHARACTERS));

			/// Re-import: idempotent (AccessPoint.member answers false on the existing participation; the util
			/// checks isMember before warning), no second participation, no throw.
			int again = PbPipelineUtil.dualWriteImportedScenes(owner, book, oneSceneList("Lamps on the quay"), oneMetaScene(miraOid));
			assertEquals("the re-import still reports the one row", 1, again);
			List<BaseRecord> members = ioContext.getMemberUtil().findMembers(fresh, OlioFieldNames.FIELD_PB_CHARACTERS, OlioModelNames.MODEL_CHAR_PERSON, 0L);
			assertEquals("exactly one charPerson participation after the re-import: " + members, 1, members.size());

			/// ── reader: fixture grants only, applied by the org admin ──
			BaseRecord olio = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			assertNotNull("olio principal", olio);
			BaseRecord bookGroup = ioContext.getPathUtil().findPath(olio, ModelNames.MODEL_GROUP, PbBookUtil.bookGroupPath(slug),
				GroupEnumType.DATA.toString(), orgId);
			assertNotNull("book group", bookGroup);
			BaseRecord popGroup = ioContext.getPathUtil().findPath(olio, ModelNames.MODEL_GROUP, popPath,
				GroupEnumType.DATA.toString(), orgId);
			assertNotNull("population group", popGroup);
			BaseRecord world = bctx.getWorld();
			assertNotNull("world record", world);
			ioContext.getAuthorizationUtil().setEntitlement(o.getAdminUser(), reader, new BaseRecord[] { bookGroup, popGroup },
				new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
			ioContext.getAuthorizationUtil().setEntitlement(o.getAdminUser(), reader, new BaseRecord[] { world },
				new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString() });
			CacheUtil.clearCache();

			/// Positive controls: the reader reaches the member call - book, scene row and person all readable -
			/// and it is the scene update check that refuses.
			assertNotNull("precondition: the reader can READ the book", PbBookUtil.readBook(reader, bookOid, orgId));
			BaseRecord sceneAsReader = PbBookUtil.readScene(reader, sceneOid, orgId);
			assertNotNull("precondition: the reader can READ the scene row", sceneAsReader);
			assertNotNull("precondition: the reader can READ the person (else enrol skips before the member call)",
				readPersonAs(reader, miraOid, orgId));
			assertFalse("precondition: the reader must NOT be permitted to update the scene row",
				permitted(ioContext.getAuthorizationUtil().canUpdate(reader, reader, sceneAsReader)));

			/// A second person the reader can read but that is not yet enrolled: the thing the reader's pass tries to write.
			BaseRecord tobin = createCharPerson(owner, popPath, "Tobin Ashgrove", "Tobin", "Ashgrove");
			String tobinOid = oid(tobin);
			CacheUtil.clearCache();
			assertNotNull("precondition: the reader can READ Tobin", readPersonAs(reader, tobinOid, orgId));
			assertFalse("precondition: Tobin is not enrolled", ioContext.getMemberUtil().isMember(tobin, fresh, OlioFieldNames.FIELD_PB_CHARACTERS));

			List<Map<String, Object>> stolen = oneSceneList("Stolen title");
			int asReader = PbPipelineUtil.dualWriteImportedScenes(reader, book, stolen, oneMetaScene(tobinOid));
			logger.info("reader dual write returned " + asReader + " (the util never throws; the count is of rows visited)");
			assertEquals("the reader's pass visited the one imported scene, so the member call was reached and the refusal below is PBAC's, not a skip",
				1, asReader);

			CacheUtil.clearCache();
			assertFalse("PBAC refused the reader's enrol: Tobin is still not a scene character",
				ioContext.getMemberUtil().isMember(tobin, fresh, OlioFieldNames.FIELD_PB_CHARACTERS));
			BaseRecord after = PbBookUtil.readScene(owner, sceneOid, orgId);
			assertEquals("the reader's text patch was refused too: title unchanged", "Lamps on the quay", after.get(OlioFieldNames.FIELD_PB_TITLE));
			assertTrue("Mira's participation is untouched", ioContext.getMemberUtil().isMember(mira, after, OlioFieldNames.FIELD_PB_CHARACTERS));
			List<BaseRecord> membersAfter = ioContext.getMemberUtil().findMembers(after, OlioFieldNames.FIELD_PB_CHARACTERS, OlioModelNames.MODEL_CHAR_PERSON, 0L);
			assertEquals("still exactly one charPerson participation", 1, membersAfter.size());
		}
		finally {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bookOid);
			logger.info("cleanup " + slug + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
	}
}
