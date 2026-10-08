package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CivilUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.junit.Before;
import org.junit.Test;

/// Audit 2026-10-07 item 15: PictureBookUtil.tagApparelSceneIndex (PUT /character/{oid}/apparel/{a}/scene-tag).
/// The 2026-08-17 book check authorizes the CHARACTER's book, but its PB1 guard only recognised a character in a
/// legacy <book>/Characters group. Since the per-book-world change every PB2 character is created in the book
/// world's Population group (series chapters: the "Chapter Population <slug>" shadow group), so the guard returned
/// false - a silent no-op - for every current-pipeline book. Real DB, no LLM, no SD, non-admin users throughout.
public class TestPbApparelSceneTag extends BaseTest {

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	/// The seven sub-records CharPersonFactory pre-builds, path-scoped to the acting user's home (~/Profiles,
	/// ~/Stores, ...). The real pipeline never leaves them there: createCharPerson detaches them and recreates
	/// them in the world's groups (PbSubRecordUtil), and a chapter shadow is re-homed wholesale into its shadow
	/// group (PbSharingUtil.rehomeSubRecords). Either way the book teardown - which runs as the olio principal -
	/// can reach them. A fixture that left them in the user's home would be deleting a layout the product never
	/// produces, and the olio-principal teardown is (correctly) DENIED on another user's home records.
	private static final String[] CHAR_SUB_RECORD_FIELDS = new String[] {
		FieldNames.FIELD_PROFILE, OlioFieldNames.FIELD_STATISTICS, FieldNames.FIELD_STORE,
		OlioFieldNames.FIELD_INSTINCT, FieldNames.FIELD_BEHAVIOR, FieldNames.FIELD_PERSONALITY, FieldNames.FIELD_STATE
	};

	/// Create a character in a PB2 character group the way the pipeline lays one out: the record AND its
	/// factory-built sub-records all in that one group (groupId is the persisted column; groupPath is virtual).
	private static String createCharPersonIn(BaseRecord user, BaseRecord group, String groupPath, String name) throws Exception {
		assertNotNull("target group", group);
		assertNotNull("target group path", groupPath);
		long groupId = ((Number) group.get(FieldNames.FIELD_ID)).longValue();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_GENDER, "female");
		for(String f : CHAR_SUB_RECORD_FIELDS) {
			BaseRecord sub = cp.get(f);
			if(sub != null) {
				sub.set(FieldNames.FIELD_GROUP_ID, groupId);
			}
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("create charPerson " + name + " in " + groupPath, created);
		return created.get(FieldNames.FIELD_OBJECT_ID);
	}

	/// The character read back the way the editor reads it, with its store planned, plus one outfit from the
	/// same apparel wizard createCharPerson uses - built against the book's OlioContext so the apparel, its
	/// wearables and qualities land in the WORLD's Apparel/Wearables/Qualities groups (olio principal), exactly
	/// where the pipeline puts them - then linked into the store as TestPictureBookFull does.
	private static String giveApparel(BaseRecord user, OlioContext octx, String charOid) throws Exception {
		Query cpQ = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charOid);
		cpQ.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		cpQ.planMost(true);
		cpQ.setCache(false);
		BaseRecord person = IOSystem.getActiveContext().getAccessPoint().find(user, cpQ);
		assertNotNull("character readable by its creator", person);
		BaseRecord store = person.get(FieldNames.FIELD_STORE);
		assertNotNull("charPerson factory gave the character a store", store);
		BaseRecord apparel = ApparelUtil.contextApparel(octx, person, 2, CivilUtil.ClimateType.TEMPERATE);
		assertNotNull("apparel wizard produced an outfit", apparel);
		apparel.setValue(OlioFieldNames.FIELD_IN_USE, true);
		List<BaseRecord> wearables = apparel.get(OlioFieldNames.FIELD_WEARABLES);
		for(BaseRecord w : wearables) {
			w.setValue(OlioFieldNames.FIELD_IN_USE, true);
		}
		assertTrue("apparel persisted", IOSystem.getActiveContext().getRecordUtil().createRecord(apparel));
		assertTrue("apparel linked into the character's store",
			IOSystem.getActiveContext().getMemberUtil().member(user, store, OlioFieldNames.FIELD_APPAREL, apparel, null, true));
		return apparel.get(FieldNames.FIELD_OBJECT_ID);
	}

	/// The tag as the character editor's list view reads it: a FRESH uncached read of the apparel's referenced
	/// attributes (a cached parent would hide a just-written attribute row - see model-api.md).
	private static Integer readSceneIndex(BaseRecord user, String apparelOid) throws Exception {
		Query aq = QueryUtil.createQuery(OlioModelNames.MODEL_APPAREL, FieldNames.FIELD_OBJECT_ID, apparelOid);
		aq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		aq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ATTRIBUTES});
		aq.setCache(false);
		BaseRecord fresh = IOSystem.getActiveContext().getAccessPoint().find(user, aq);
		assertNotNull("apparel readable by its owner", fresh);
		return AttributeUtil.getAttributeValue(fresh, "sceneIndex", null);
	}

	private static void assertStrangerDenied(BaseRecord stranger, String charOid, String apparelOid) {
		try {
			PictureBookUtil.tagApparelSceneIndex(stranger, charOid, apparelOid, 7);
			fail("a user with no grant on the book must not tag its character's apparel");
		}
		catch(PictureBookException pbe) {
			/// The character read through AccessPoint is denied, which the method reports as absent (404) -
			/// the no-disclosure convention throughout PictureBookUtil. A 403 would also be a refusal.
			assertTrue("refused with 404/403, got " + pbe.getStatus() + ": " + pbe.getMessage(),
				pbe.getStatus() == 404 || pbe.getStatus() == 403);
		}
	}

	@Test
	public void TestPb2PopulationCharacterIsTaggedAndStrangerIsRefused() throws Exception {
		OlioModelNames.use();
		BaseRecord owner = getCreateUser("pbTagOwner");
		BaseRecord stranger = getCreateUser("pbTagStranger");
		assertNotNull("owner", owner);
		assertNotNull("stranger", stranger);
		assertFalse("owner and stranger must differ", ((Long) owner.get(FieldNames.FIELD_ID)).equals((Long) stranger.get(FieldNames.FIELD_ID)));
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String tag = shortId();
		String slug = "tag" + tag;
		BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "Scene Tag " + tag);
		assertNotNull("createBook", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		try {
			BaseRecord readBook = PbBookUtil.readBook(owner, bookOid, orgId);
			assertNotNull("book readable by its creator", readBook);
			BookContext bctx = PbBookUtil.openBookContext(owner, readBook);
			assertNotNull("book world context", bctx);
			BaseRecord populationGroup = bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
			String populationPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("the book world's Population group", populationGroup);
			assertNotNull("the book world's Population group path", populationPath);
			assertTrue("population path ends in the Population group: " + populationPath,
				populationPath.endsWith("/" + PictureBookUtil.POPULATION_DIR));
			assertTrue(PictureBookUtil.isPb2CharacterGroupName(PictureBookUtil.POPULATION_DIR));
			assertFalse(PictureBookUtil.isPb2CharacterGroupName(PictureBookUtil.CHARACTERS_DIR));
			assertFalse(PictureBookUtil.isPb2CharacterGroupName("Pb1Sim"));
			assertFalse(PictureBookUtil.isPb2CharacterGroupName(null));
			/// The same memoized book OlioContext the pipeline threads into createCharPerson (octxHint).
			OlioContext octx = PbOlioContextUtil.getCreateBookContext(owner, dataPath, slug);
			assertNotNull("book OlioContext", octx);

			/// Exactly where createFromScenes puts a PB2 book's characters.
			String charOid = createCharPersonIn(owner, populationGroup, populationPath, "Elara " + tag);
			String apparelOid = giveApparel(owner, octx, charOid);
			assertNull("fresh apparel carries no scene tag", readSceneIndex(owner, apparelOid));

			/// Before the fix this returned false and wrote nothing.
			assertTrue("book writer tags a Population-group character's apparel",
				PictureBookUtil.tagApparelSceneIndex(owner, charOid, apparelOid, 2));
			assertEquals("tag persisted as a referenced attribute", Integer.valueOf(2), readSceneIndex(owner, apparelOid));

			/// Re-tag updates the SAME attribute row rather than adding a second one.
			assertTrue("re-tag", PictureBookUtil.tagApparelSceneIndex(owner, charOid, apparelOid, 5));
			assertEquals("re-tag updated in place", Integer.valueOf(5), readSceneIndex(owner, apparelOid));

			/// An apparel that is not THIS character's is still refused (400), grant or no grant.
			String otherCharOid = createCharPersonIn(owner, populationGroup, populationPath, "Other " + tag);
			try {
				PictureBookUtil.tagApparelSceneIndex(owner, otherCharOid, apparelOid, 1);
				fail("an apparel outside the named character's store must be refused");
			}
			catch(PictureBookException pbe) {
				assertEquals("foreign apparel is a 400", 400, pbe.getStatus());
			}
			assertEquals("foreign-apparel refusal changed nothing", Integer.valueOf(5), readSceneIndex(owner, apparelOid));

			/// A stranger in the organization holds no book role; the authorization must refuse, not skip.
			assertStrangerDenied(stranger, charOid, apparelOid);
			assertEquals("stranger refusal changed nothing", Integer.valueOf(5), readSceneIndex(owner, apparelOid));
		}
		finally {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, bookOid);
			assertTrue("cleanup book: " + res.reason, res.deleted);
		}
	}

	@Test
	public void TestSeriesChapterShadowCharacterIsTagged() throws Exception {
		OlioModelNames.use();
		BaseRecord owner = getCreateUser("pbTagOwner");
		BaseRecord stranger = getCreateUser("pbTagStranger");
		assertNotNull("owner", owner);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String tag = shortId();
		String seriesSlug = "tgs" + tag;
		String chapterSlug = "tgc" + tag;
		BaseRecord series = PbSeriesUtil.getCreateSeries(owner, dataPath, seriesSlug, "Scene Tag Series " + tag);
		assertNotNull("series", series);
		try {
			BaseRecord chapter = PbBookUtil.createBook(owner, dataPath, chapterSlug, "Scene Tag Ch 1 " + tag, series, 0);
			assertNotNull("chapter", chapter);
			BaseRecord readChapter = PbBookUtil.readBook(owner, chapter.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull("chapter readable by its creator", readChapter);
			BookContext bctx = PbBookUtil.openBookContext(owner, readChapter);
			assertNotNull("series world context via the chapter", bctx);
			String populationPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("series world Population path", populationPath);

			/// The chapter's shadow character home: a sibling of Population named "Chapter Population <slug>".
			BaseRecord shadowGroup = PbBookUtil.getCreateChapterShadowCharGroup(owner, seriesSlug, populationPath, chapterSlug, orgId);
			assertNotNull("shadow character group", shadowGroup);
			String shadowName = shadowGroup.get(FieldNames.FIELD_NAME);
			assertEquals(PbBookUtil.chapterShadowCharGroupName(chapterSlug), shadowName);
			assertTrue("shadow group name is recognised as a PB2 character home", PictureBookUtil.isPb2CharacterGroupName(shadowName));
			String shadowPath = PbBookUtil.chapterShadowCharGroupPath(populationPath, chapterSlug);
			assertNotNull("shadow path derives from the Population path", shadowPath);
			/// The series OlioContext (same memo copyToChapterShadow / createCharPerson use for a chapter).
			OlioContext octx = PbOlioContextUtil.getCreateSeriesContext(owner, dataPath, seriesSlug);
			assertNotNull("series OlioContext", octx);

			String charOid = createCharPersonIn(owner, shadowGroup, shadowPath, "Shadow Elara " + tag);
			String apparelOid = giveApparel(owner, octx, charOid);
			assertNull("fresh apparel carries no scene tag", readSceneIndex(owner, apparelOid));
			assertTrue("series writer tags a chapter shadow character's apparel",
				PictureBookUtil.tagApparelSceneIndex(owner, charOid, apparelOid, 3));
			assertEquals(Integer.valueOf(3), readSceneIndex(owner, apparelOid));

			assertStrangerDenied(stranger, charOid, apparelOid);
			assertEquals("stranger refusal changed nothing", Integer.valueOf(3), readSceneIndex(owner, apparelOid));
		}
		finally {
			assertEquals("cleanup series tears down its chapter", 1, PbSeriesUtil.deleteSeries(owner, series.get(FieldNames.FIELD_OBJECT_ID)));
		}
	}
}
