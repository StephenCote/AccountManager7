package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/// Merge / delete of an extracted character must also move or detach it in the V3 book's own cast records:
/// the scene's `characters` participations and every composition actor's `character` FK. Before 2026-11-18
/// they were never touched, so a merge left the deleted duplicate as the hero of its scene compositions and
/// every such render silently had no portrait reference (alchemy.doc: 10 scenes). Also covers the fuzzy
/// actor-name fallback used by the composer. Real DB, no LLM, non-admin user.
public class TestPbCharacterV3Cast extends BaseTest {

	private static final String PB1_BOOKS_ROOT = "~/Data/PictureBooks/";

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	/// The fixture: a PB2 book, its PB1 group + meta (how a book objectId resolves for merge/delete), people in
	/// the world population, and one V3 scene with the given cast and a MIGRATED composition (HERO = cast[0]).
	private static final class Fx {
		String slug, bookOid, bookGroupPath;
		BaseRecord book, scene, comp, bookGroup, scenesGroup, metaNote;
		Map<String, String> oidByName = new LinkedHashMap<>();
	}

	private Fx build(BaseRecord owner, String slug, List<String> people, List<String> cast) throws Exception {
		IOContext io = IOSystem.getActiveContext();
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		Fx fx = new Fx();
		fx.slug = slug;
		fx.book = PbBookUtil.createBook(owner, dataPath, slug, "V3Cast " + slug);
		assertNotNull("createBook", fx.book);
		fx.bookOid = fx.book.get(FieldNames.FIELD_OBJECT_ID);
		OlioContext ctx = PbOlioContextUtil.getCreateBookContext(owner, dataPath, slug);
		assertNotNull("book olio context", ctx);
		String popPath = populationPath(ctx, orgId);
		for(String n : people) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, popPath);
			plist.parameter(FieldNames.FIELD_NAME, n);
			BaseRecord cp = io.getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, owner, null, plist);
			cp.set(FieldNames.FIELD_NAME, n);
			cp.set("gender", "male");
			BaseRecord created = io.getAccessPoint().create(owner, cp);
			assertNotNull("create " + n, created);
			fx.oidByName.put(n, created.get(FieldNames.FIELD_OBJECT_ID));
		}
		fx.bookGroup = io.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, PB1_BOOKS_ROOT + slug, GroupEnumType.DATA.toString(), orgId);
		fx.bookGroupPath = fx.bookGroup.get(FieldNames.FIELD_PATH);
		fx.scenesGroup = io.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, fx.bookGroupPath + "/Scenes", GroupEnumType.DATA.toString(), orgId);
		Map<String, Object> meta = new LinkedHashMap<>();
		meta.put("pb2BookObjectId", fx.bookOid);
		meta.put("scenes", new ArrayList<>());
		ParameterList mpl = ParameterList.newParameterList(FieldNames.FIELD_PATH, fx.bookGroupPath);
		mpl.parameter(FieldNames.FIELD_NAME, PictureBookUtil.META_NOTE_NAME);
		BaseRecord note = io.getFactory().newInstance(ModelNames.MODEL_NOTE, owner, null, mpl);
		note.set("text", JSONUtil.exportObject(meta));
		fx.metaNote = io.getAccessPoint().create(owner, note);
		assertNotNull("meta note", fx.metaNote);

		fx.scene = PbBookUtil.createScene(owner, fx.book, 0, "Scene " + slug, PbBookUtil.bookGroupPath(slug));
		assertNotNull("scene", fx.scene);
		for(String n : cast) {
			BaseRecord person = readPerson(fx.oidByName.get(n), orgId);
			assertTrue("enrol " + n, io.getAccessPoint().member(owner, fx.scene, OlioFieldNames.FIELD_PB_CHARACTERS, person, null, true));
		}
		PbComposeUtil.ComposeResult cr = PbComposeUtil.composeMigrated(owner, fx.book, fx.scene, false);
		assertNotNull("composeMigrated", cr);
		fx.comp = composition(owner, fx);
		assertNotNull("MIGRATED composition", fx.comp);
		assertEquals("one actor per cast member", cast.size(), PbComposeUtil.listActors(owner, fx.comp).size());
		return fx;
	}

	private static BaseRecord composition(BaseRecord owner, Fx fx) {
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord fresh = PbBookUtil.readBook(owner, fx.bookOid, orgId);
		return PbComposeUtil.findSceneComposition(owner, fx.scene, ((Number) fresh.get(FieldNames.FIELD_GROUP_ID)).longValue(), orgId);
	}

	private static BaseRecord readPerson(String oid, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private static String populationPath(OlioContext ctx, long orgId) {
		BaseRecord world = ctx.getWorld();
		String popPath = world.get("population.path");
		if(popPath == null || popPath.isBlank()) {
			Query wq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_OBJECT_ID, world.get(FieldNames.FIELD_OBJECT_ID));
			wq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			wq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "population.path" });
			wq.setCache(false);
			popPath = IOSystem.getActiveContext().getSearch().findRecord(wq).get("population.path");
		}
		return popPath;
	}

	private static boolean isMember(String personOid, BaseRecord scene, long orgId) {
		BaseRecord p = readPerson(personOid, orgId);
		return p != null && IOSystem.getActiveContext().getMemberUtil().isMember(p, scene, OlioFieldNames.FIELD_PB_CHARACTERS);
	}

	/// Actor character id -> (role, description, apparel id) as re-read now.
	private static List<Object[]> actorRows(BaseRecord owner, Fx fx) {
		List<Object[]> out = new ArrayList<>();
		for(BaseRecord a : PbComposeUtil.listActors(owner, composition(owner, fx))) {
			BaseRecord ch = a.get(OlioFieldNames.FIELD_PB_CHARACTER);
			BaseRecord ap = a.get(OlioFieldNames.FIELD_APPAREL);
			Object cid = (ch != null ? ch.get(FieldNames.FIELD_ID) : null);
			out.add(new Object[] { (cid instanceof Number && ((Number) cid).longValue() > 0 ? ((Number) cid).longValue() : null),
				String.valueOf((Object) a.get(OlioFieldNames.FIELD_PB_ROLE)), a.get(FieldNames.FIELD_DESCRIPTION),
				(ap != null ? ap.get(FieldNames.FIELD_ID) : null) });
		}
		return out;
	}

	private static long idOf(String oid, long orgId) {
		return ((Number) readPerson(oid, orgId).get(FieldNames.FIELD_ID)).longValue();
	}

	private void teardown(BaseRecord owner, Fx fx) {
		if(fx == null) return;
		IOContext io = IOSystem.getActiveContext();
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		try { if(fx.metaNote != null) io.getAccessPoint().delete(owner, fx.metaNote); } catch(Exception e) { logger.warn("cleanup meta: " + e.getMessage()); }
		try { if(fx.scenesGroup != null) io.getAccessPoint().delete(owner, fx.scenesGroup); } catch(Exception e) { logger.warn("cleanup scenes: " + e.getMessage()); }
		try { if(fx.bookGroup != null) io.getAccessPoint().delete(owner, fx.bookGroup); } catch(Exception e) { logger.warn("cleanup group: " + e.getMessage()); }
		BaseRecord olioUser = io.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SLUG, fx.slug);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID });
		q.setCache(false);
		BaseRecord row = io.getSearch().findRecord(q);
		if(row != null && olioUser != null) {
			PictureBookUtil.DeleteResult del = PictureBookUtil.teardownBookFootprintAsOlio(olioUser, row, orgId);
			if(!del.deleted) logger.warn("cleanup teardown of " + fx.slug + ": " + del.reason);
		}
	}

	@Test
	public void mergeRepointsSceneCastAndCompositionActorsToTheKeeper() throws Exception {
		OlioModelNames.use();
		BaseRecord owner = getCreateUser("pbV3CastOwner");
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String id = shortId();
		String keeper = "Manuel deFol " + id, dupA = "General deFol " + id, dupB = "Doughty " + id;
		Fx fx = null;
		try {
			fx = build(owner, "v3m" + id, Arrays.asList(keeper, dupA, dupB), Arrays.asList(dupA, dupB));
			long dupAId = idOf(fx.oidByName.get(dupA), orgId), keepId = idOf(fx.oidByName.get(keeper), orgId);
			assertEquals("fixture: dupA is the HERO actor", Long.valueOf(dupAId), actorRows(owner, fx).get(0)[0]);

			PictureBookUtil.MergeResult r = PictureBookUtil.mergeCharacters(owner, fx.bookOid, fx.oidByName.get(keeper),
				Arrays.asList(fx.oidByName.get(dupA)));
			logger.info("merge result V3 cast: " + r.v3);
			assertTrue("no V3 failures: " + r.v3.failures, r.v3.failures.isEmpty());
			assertEquals("one scene membership moved", 1, r.v3.sceneMembershipsChanged);
			assertEquals("one actor repointed", 1, r.v3.actorsChanged);

			assertTrue("keeper is now in the scene cast", isMember(fx.oidByName.get(keeper), fx.scene, orgId));
			assertNull("the merged duplicate's record is gone", readPerson(fx.oidByName.get(dupA), orgId));
			List<Object[]> rows = actorRows(owner, fx);
			assertEquals("HERO actor now points at the keeper", Long.valueOf(keepId), rows.get(0)[0]);
			assertNull("its stale outfit was cleared", rows.get(0)[3]);
			assertEquals("the other actor is untouched", Long.valueOf(idOf(fx.oidByName.get(dupB), orgId)), rows.get(1)[0]);
		}
		finally {
			teardown(owner, fx);
		}
	}

	@Test
	public void deleteDetachesSceneCastAndUnlinksActorsKeepingTheName() throws Exception {
		OlioModelNames.use();
		BaseRecord owner = getCreateUser("pbV3CastOwner");
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String id = shortId();
		String ship = "The Golden Sunset " + id, hero = "Argentice " + id;
		Fx fx = null;
		try {
			fx = build(owner, "v3d" + id, Arrays.asList(ship, hero), Arrays.asList(ship, hero));
			PictureBookUtil.DeleteCharacterResult r = PictureBookUtil.deleteCharacter(owner, fx.bookOid, fx.oidByName.get(ship));
			logger.info("delete result V3 cast: " + r.v3);
			assertTrue("deleted", r.deleted);
			assertTrue("no V3 failures: " + r.v3.failures, r.v3.failures.isEmpty());
			assertEquals("one scene membership removed", 1, r.v3.sceneMembershipsChanged);
			assertEquals("one actor unlinked", 1, r.v3.actorsChanged);
			assertTrue("the other cast member stays", isMember(fx.oidByName.get(hero), fx.scene, orgId));

			List<Object[]> rows = actorRows(owner, fx);
			assertEquals("both actor rows remain", 2, rows.size());
			assertNull("the deleted character's actor has no character", rows.get(0)[0]);
			assertEquals("and keeps its name as the description", ship, rows.get(0)[2]);
			assertEquals("the other actor still points at its character", Long.valueOf(idOf(fx.oidByName.get(hero), orgId)), rows.get(1)[0]);
		}
		finally {
			teardown(owner, fx);
		}
	}

	private static BaseRecord cand(long id, String name) {
		try {
			BaseRecord r = org.cote.accountmanager.record.RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON,
				new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME });
			r.set(FieldNames.FIELD_ID, id);
			r.set(FieldNames.FIELD_NAME, name);
			return r;
		}
		catch(Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	public void fuzzyActorNameResolvesTheAlchemyMisses() {
		List<BaseRecord> book = Arrays.asList(cand(1, "Argentice"), cand(2, "Major Gallant"), cand(3, "Reverend Doughty"),
			cand(4, "Sergeant Major at Arms Victual"), cand(5, "Staff Sergeant Aufait"), cand(6, "Nightshade"),
			cand(7, "Marcus Finley"), cand(8, "Manuel deFól"));
		/// The names the composer left unlinked on alchemy.doc (2026-11-18).
		assertEquals(2L, (long) PictureBookUtil.fuzzyCharacterMatch("Gallant", book).get(FieldNames.FIELD_ID));
		assertEquals(3L, (long) PictureBookUtil.fuzzyCharacterMatch("Doughty", book).get(FieldNames.FIELD_ID));
		assertEquals(5L, (long) PictureBookUtil.fuzzyCharacterMatch("Aufait", book).get(FieldNames.FIELD_ID));
		assertEquals(8L, (long) PictureBookUtil.fuzzyCharacterMatch("deFöl", book).get(FieldNames.FIELD_ID));
		assertEquals(8L, (long) PictureBookUtil.fuzzyCharacterMatch("General deFäl", book).get(FieldNames.FIELD_ID));
		assertEquals(8L, (long) PictureBookUtil.fuzzyCharacterMatch("General deFål", book).get(FieldNames.FIELD_ID));
		assertEquals(7L, (long) PictureBookUtil.fuzzyCharacterMatch("Marcus Finely", book).get(FieldNames.FIELD_ID));
		/// Non-names and titles alone stay unlinked.
		assertNull(PictureBookUtil.fuzzyCharacterMatch("officers", book));
		assertNull(PictureBookUtil.fuzzyCharacterMatch("a window nearby with soft lighting", book));
		assertNull(PictureBookUtil.fuzzyCharacterMatch("Reverend", book));
		assertNull(PictureBookUtil.fuzzyCharacterMatch("Major", book));
	}

	@Test
	public void fuzzyActorNameRefusesAmbiguityAndPrefersTheExactPerson() {
		List<BaseRecord> fam = Arrays.asList(cand(1, "Darby"), cand(2, "Darby's dad"), cand(3, "Mia's dad"));
		assertEquals("Darby, not her father", 1L, (long) PictureBookUtil.fuzzyCharacterMatch("Darby", fam).get(FieldNames.FIELD_ID));
		assertNull("two dads: ambiguous", PictureBookUtil.fuzzyCharacterMatch("dad", fam));
		List<BaseRecord> twins = Arrays.asList(cand(1, "John Smith"), cand(2, "Jane Smith"));
		assertNull("surname shared by two people: ambiguous", PictureBookUtil.fuzzyCharacterMatch("Smith", twins));
		assertFalse("short tokens need an exact match", PictureBookUtil.fuzzyCharacterMatch("Jon", Arrays.asList(cand(1, "Joan"))) != null);
	}
}
