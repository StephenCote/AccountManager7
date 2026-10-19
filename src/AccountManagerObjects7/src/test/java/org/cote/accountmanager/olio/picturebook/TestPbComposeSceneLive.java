package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.describe;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.enumWord;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.fkId;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.groupOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.idOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.oidOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.orgOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.CastTier;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbActorRoleEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbElementTypeEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-3: the compose step against the real LLM ({@code OlioTestUtil.getOllamaOpenAIConfig}, whichever
 * tier {@code LlmTestGate} resolved). One throwaway book in {@code /Development/World Building} as
 * {@code testUser1}: a known location with one item in its POI store, two population members enrolled in a scene
 * whose text names them plus one figure the cast does not know. The test asserts only the design §9 invariants a
 * sane staging must satisfy - the LLM decides the actual staging - then proves a forced re-compose replaces rows in
 * place and that {@code userEdited} is honoured. Timings are logged, never asserted.
 */
public class TestPbComposeSceneLive extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String CFG_NAME = "P43 Live Cfg";

	private static final String LOCATION_NAME = "The Gilded Tankard";
	private static final String LOCATION_DESC = "A low-beamed tavern by the quay: ale-stained tables, a wide hearth, rain-streaked windows.";
	private static final String ITEM_NAME = "scarred corner table";
	private static final String SETTING = "The Gilded Tankard, a low-beamed tavern by the quay, late on a rainy night";
	private static final String ACTION = "Mira comes in from the rain and finds Tobin waiting at the corner table";
	private static final String MOOD = "tense";
	private static final String SCENE_TEXT = "Mira Vale pushed through the taproom door of the Gilded Tankard, rain dripping from her grey cloak. "
		+ "The fire was low and the room half empty. Tobin Ashgrove was already at the scarred corner table, two cups poured, "
		+ "his back to the wall. Behind the bar Old Marta pretended not to watch them. Mira sat, pushed the wet hair from her "
		+ "eyes, and did not touch her cup.";

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	@Test(timeout = 1_800_000)
	public void composeStagesARealSceneThenRecomposesInPlace() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		try {
			cfg = OlioTestUtil.getOllamaOpenAIConfig(u, CFG_NAME, testProperties);
			assertNotNull("chatConfig " + CFG_NAME, cfg);
			logger.info("[p43-live] route=" + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier")
				+ " server=" + testProperties.getProperty("test.llm.connection.server") + " model=" + (Object) cfg.get("model"));

			bk = newBook(u, "live");
			BookContext bctx = PbBookUtil.openBookContext(u, bk);
			assertNotNull("The book must carry a world", bctx);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("Population group path", popPath);

			/// A known location whose POI store holds one item the scene text names.
			BaseRecord loc = PbLocationUtil.getCreateLocation(u, bk, LOCATION_NAME, LOCATION_DESC, PbLocationKindEnumType.INTERIOR,
				Arrays.asList("the Tankard", "the taproom"));
			assertNotNull("getCreateLocation " + LOCATION_NAME, loc);
			BaseRecord store = storeOf(u, loc, orgId);
			BaseRecord item = newItem(u, bctx, ITEM_NAME, "a heavy oak table scored with knife marks and cup rings");
			assertTrue("enrol item in the location's store", ioContext.getMemberUtil().member(u, store, OlioFieldNames.FIELD_ITEMS, item, null, true));

			BaseRecord scene = addScene(u, bk, 3, "Arrival", SETTING, ACTION, MOOD, SCENE_TEXT, loc);
			BaseRecord mira = createCharPerson(u, popPath, "Mira Vale", "Mira", "Vale");
			BaseRecord tobin = createCharPerson(u, popPath, "Tobin Ashgrove", "Tobin", "Ashgrove");
			enrol(u, scene, mira);
			enrol(u, scene, tobin);
			long gid = idOf(groupOf(scene));

			/// 1. First compose: the LLM stages the scene.
			long t0 = System.currentTimeMillis();
			ComposeResult r1 = PbComposeUtil.composeScene(u, bk, scene, cfg, false);
			long ms1 = System.currentTimeMillis() - t0;
			logger.info("[p43-live] compose 1 (" + ms1 + " ms): " + describe(r1));
			assertFalse("compose must not hard-fail: " + r1.failureReason + " " + r1.warnings, r1.hardFailure);
			assertFalse("the real LLM must not be replaced by the MIGRATED fallback: " + r1.failureReason + " " + r1.warnings, r1.migratedFallback);
			assertFalse(r1.skippedUserEdited);
			assertTrue(r1.isWritten());
			assertEquals(PbCompositionSourceEnumType.LLM, r1.source);
			assertEquals(CastTier.SCENE_MEMBERS, r1.castTier);
			BaseRecord comp = r1.composition;
			assertNotNull("a composition was written", comp);
			String compOid = oidOf(comp);
			assertEquals(PbCompositionSourceEnumType.LLM.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(PbCompositionPurposeEnumType.SCENE.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals(Integer.valueOf(1), comp.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(Boolean.FALSE, comp.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals("the composition lives in the scene's group", gid, idOf(groupOf(comp)));
			assertEquals(idOf(scene), fkId(comp, OlioFieldNames.FIELD_PB_SCENE));
			assertEquals(idOf(bk), fkId(comp, OlioFieldNames.FIELD_PB_BOOK));
			assertEquals("the scene's location is carried on the composition", idOf(loc), fkId(comp, FieldNames.FIELD_LOCATION));
			assertNotUnknown(comp, OlioFieldNames.FIELD_PB_SHOT_TYPE);
			assertNotUnknown(comp, OlioFieldNames.FIELD_PB_VIEWPOINT);
			assertEquals("the scene's own mood wins over the proposal", MOOD, comp.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("the scene's own setting wins", SETTING, comp.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals(ACTION, comp.get(OlioFieldNames.FIELD_PB_ACTION));
			assertNull("no sdPrompt on the scene, no promptOverride", comp.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertFalse("an LLM composition is not flagged for compose", PbComposeUtil.needsCompose(comp));

			List<BaseRecord> actors = r1.actors;
			assertTrue("at least one actor row: " + describe(r1), actors.size() >= 1);
			assertEquals(actors.size(), PbComposeUtil.listActors(u, comp).size());
			int heroes = 0;
			Set<Long> resolved = new HashSet<>();
			Set<String> validRoles = names(PbActorRoleEnumType.values());
			for(int i = 0; i < actors.size(); i++) {
				BaseRecord row = actors.get(i);
				String label = "actor[" + i + "] " + row.get(FieldNames.FIELD_NAME);
				logger.info("[p43-live] " + label + " role=" + enumWord(row, OlioFieldNames.FIELD_PB_ROLE) + " character=" + fkId(row, OlioFieldNames.FIELD_PB_CHARACTER)
					+ " pose=" + (Object) row.get(OlioFieldNames.FIELD_PB_POSE) + " expression=" + (Object) row.get(FieldNames.FIELD_EXPRESSION)
					+ " action=" + (Object) row.get(OlioFieldNames.FIELD_PB_ACTION) + " count=" + (Object) row.get(FieldNames.FIELD_COUNT)
					+ " box=" + box(row) + " depth=" + (Object) row.get(OlioFieldNames.FIELD_PB_DEPTH) + " description=" + (Object) row.get(FieldNames.FIELD_DESCRIPTION));
				assertEquals(label + " priority follows row order", Integer.valueOf(i), row.get(OlioFieldNames.FIELD_PB_PRIORITY));
				assertEquals(label + " group", gid, idOf(groupOf(row)));
				assertEquals(label + " composition", idOf(comp), fkId(row, OlioFieldNames.FIELD_PB_COMPOSITION));
				String role = enumWord(row, OlioFieldNames.FIELD_PB_ROLE);
				assertTrue(label + " role " + role, role != null && validRoles.contains(role) && !"UNKNOWN".equals(role));
				if(PbActorRoleEnumType.HERO.toString().equals(role)) {
					heroes++;
				}
				assertUnitBox(label, row);
				Integer count = row.get(FieldNames.FIELD_COUNT);
				assertTrue(label + " count " + count, count != null && count >= 1);
				long cid = fkId(row, OlioFieldNames.FIELD_PB_CHARACTER);
				if(PbActorRoleEnumType.HERO.toString().equals(role) || PbActorRoleEnumType.SUPPORTING.toString().equals(role)) {
					assertTrue(label + " a hero or supporting role is only given to a resolved cast member", cid > 0L);
				}
				if(cid > 0L) {
					resolved.add(cid);
				}
				else {
					assertTrue(label + " an unresolved figure keeps a description", NarrativeUtilIsMeaningful(row.get(FieldNames.FIELD_DESCRIPTION)));
					assertTrue(label + " an unresolved figure is background, extra or crowd, got " + role,
						role.equals(PbActorRoleEnumType.NAMED_BACKGROUND.toString()) || role.equals(PbActorRoleEnumType.EXTRA.toString())
							|| role.equals(PbActorRoleEnumType.CROWD.toString()));
				}
			}
			// The composer honours every explicit "hero" word the LLM gives (design mandates a single first HERO only for
			// MIGRATED compositions), so the count is recorded, not asserted.
			logger.info("[p43-live] HERO rows=" + heroes + " of " + actors.size() + " actors (recorded, not asserted)");
			assertTrue("at least one cast member resolved to a character FK: " + resolved, resolved.contains(idOf(mira)) || resolved.contains(idOf(tobin)));
			assertTrue("no actor points outside the cast: " + resolved, new HashSet<>(Arrays.asList(idOf(mira), idOf(tobin))).containsAll(resolved));
			long focal = fkId(comp, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			assertTrue("focalActor is set", focal > 0L);
			Set<Long> actorIds = new HashSet<>();
			for(BaseRecord row : actors) {
				actorIds.add(idOf(row));
			}
			assertTrue("focalActor is one of this composition's rows", actorIds.contains(focal));
			for(String unresolved : r1.unresolvedNames) {
				boolean kept = false;
				for(BaseRecord row : actors) {
					String n = row.get(FieldNames.FIELD_NAME);
					String d = row.get(FieldNames.FIELD_DESCRIPTION);
					if(fkId(row, OlioFieldNames.FIELD_PB_CHARACTER) == 0L && ((n != null && n.contains(unresolved)) || (d != null && d.contains(unresolved)))) {
						kept = true;
						assertEquals("an unresolved name is NAMED_BACKGROUND: " + unresolved, PbActorRoleEnumType.NAMED_BACKGROUND.toString(), enumWord(row, OlioFieldNames.FIELD_PB_ROLE));
					}
				}
				assertTrue("unresolved name " + unresolved + " is kept as a row, never dropped", kept);
			}

			List<BaseRecord> elements = r1.elements;
			assertEquals(elements.size(), PbComposeUtil.listElements(u, comp).size());
			Set<String> validTypes = names(PbElementTypeEnumType.values());
			boolean itemLinked = false;
			boolean itemNamed = false;
			for(int i = 0; i < elements.size(); i++) {
				BaseRecord el = elements.get(i);
				String label = "element[" + i + "] " + el.get(FieldNames.FIELD_NAME);
				logger.info("[p43-live] " + label + " type=" + enumWord(el, OlioFieldNames.FIELD_PB_ELEMENT_TYPE) + " item=" + fkId(el, OlioFieldNames.FIELD_ITEM)
					+ " importance=" + enumWord(el, OlioFieldNames.FIELD_PB_IMPORTANCE) + " box=" + box(el) + " description=" + (Object) el.get(FieldNames.FIELD_DESCRIPTION));
				assertEquals(label + " group", gid, idOf(groupOf(el)));
				assertEquals(label + " composition", idOf(comp), fkId(el, OlioFieldNames.FIELD_PB_COMPOSITION));
				String type = enumWord(el, OlioFieldNames.FIELD_PB_ELEMENT_TYPE);
				assertTrue(label + " type " + type, type != null && validTypes.contains(type) && !"UNKNOWN".equals(type));
				assertTrue(label + " description", NarrativeUtilIsMeaningful(el.get(FieldNames.FIELD_DESCRIPTION)));
				assertUnitBox(label, el);
				String d = ((String) el.get(FieldNames.FIELD_DESCRIPTION)).toLowerCase();
				if(d.contains(ITEM_NAME)) {
					itemNamed = true;
					assertEquals(label + " names the store item, so it links it", idOf(item), fkId(el, OlioFieldNames.FIELD_ITEM));
					itemLinked = true;
				}
			}
			logger.info("[p43-live] store item '" + ITEM_NAME + "' named by an element=" + itemNamed + " linked=" + itemLinked
				+ " (the LLM decides whether the table is in frame; only the link is asserted when it is)");
			assertEquals("exactly one SCENE composition", compOid, oidOf(PbComposeUtil.findSceneComposition(u, scene, gid, orgId)));

			/// 2. A forced re-compose replaces the rows in place.
			t0 = System.currentTimeMillis();
			ComposeResult r2 = PbComposeUtil.composeScene(u, bk, scene, cfg, true);
			long ms2 = System.currentTimeMillis() - t0;
			logger.info("[p43-live] compose 2 forced (" + ms2 + " ms): " + describe(r2));
			assertFalse("forced compose must not hard-fail: " + r2.failureReason + " " + r2.warnings, r2.hardFailure);
			assertFalse(r2.migratedFallback);
			assertTrue(r2.isWritten());
			assertEquals("same composition record", compOid, oidOf(r2.composition));
			assertEquals(Integer.valueOf(2), r2.composition.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(PbCompositionSourceEnumType.LLM.toString(), enumWord(r2.composition, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("the stored rows are exactly the fresh rows", r2.actors.size(), PbComposeUtil.listActors(u, r2.composition).size());
			assertEquals(r2.elements.size(), PbComposeUtil.listElements(u, r2.composition).size());
			assertTrue("at least one actor after re-compose", r2.actors.size() >= 1);
			for(BaseRecord row : r2.actors) {
				assertFalse("old rows are gone, not reused: " + row.get(FieldNames.FIELD_NAME), actorIds.contains(idOf(row)));
			}
			long focal2 = fkId(r2.composition, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			assertTrue("focalActor re-pointed at a new row", focal2 > 0L && !actorIds.contains(focal2));
			assertEquals("still exactly one SCENE composition", compOid, oidOf(PbComposeUtil.findSceneComposition(u, scene, gid, orgId)));
			assertEquals("no orphan actor rows in the book group", r2.actors.size(), countRows(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, gid, orgId));
			assertEquals("no orphan element rows in the book group", r2.elements.size(), countRows(u, OlioModelNames.MODEL_PB_SCENE_ELEMENT, gid, orgId));

			/// 3. userEdited is honoured without force and reset with it.
			BaseRecord edit = PbGraphUtil.patchOf(r2.composition, OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_USER_EDITED);
			edit.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
			assertNotNull("mark userEdited", ioContext.getAccessPoint().update(u, edit));
			ComposeResult r3 = PbComposeUtil.composeScene(u, bk, scene, cfg, false);
			logger.info("[p43-live] compose 3 (userEdited): " + describe(r3));
			assertTrue(r3.skippedUserEdited);
			assertFalse(r3.isWritten());
			assertEquals(compOid, oidOf(r3.composition));
			assertEquals(Integer.valueOf(2), PbComposeUtil.readComposition(u, compOid, orgId).get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(r2.actors.size(), r3.actors.size());

			logger.info("[p43-live] timings: compose=" + ms1 + " ms recompose=" + ms2 + " ms (recorded, not asserted)");
		}
		finally {
			deleteBook(u, bk);
			if(cfg != null) {
				BaseRecord conn = cfg.get("connection");
				deleteQuietly(u, cfg);
				deleteQuietly(u, conn);
			}
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static boolean NarrativeUtilIsMeaningful(Object o) {
		return org.cote.accountmanager.olio.NarrativeUtil.isMeaningful(o == null ? null : o.toString());
	}

	private static Set<String> names(Enum<?>[] values) {
		Set<String> s = new HashSet<>();
		for(Enum<?> e : values) {
			s.add(e.name());
		}
		return s;
	}

	private static void assertNotUnknown(BaseRecord rec, String field) {
		String w = enumWord(rec, field);
		assertTrue(field + " is set, got " + w, w != null && !"UNKNOWN".equals(w));
	}

	private static String box(BaseRecord rec) {
		return "[" + rec.get(OlioFieldNames.FIELD_PB_X) + "," + rec.get(OlioFieldNames.FIELD_PB_Y) + " " + rec.get(OlioFieldNames.FIELD_PB_W) + "x" + rec.get(OlioFieldNames.FIELD_PB_H) + "]";
	}

	private static void assertUnitBox(String label, BaseRecord rec) {
		double x = TestPbComposeFallback.dbl(rec, OlioFieldNames.FIELD_PB_X);
		double y = TestPbComposeFallback.dbl(rec, OlioFieldNames.FIELD_PB_Y);
		double w = TestPbComposeFallback.dbl(rec, OlioFieldNames.FIELD_PB_W);
		double h = TestPbComposeFallback.dbl(rec, OlioFieldNames.FIELD_PB_H);
		assertTrue(label + " x=" + x, x >= 0.0 && x <= 1.0);
		assertTrue(label + " y=" + y, y >= 0.0 && y <= 1.0);
		assertTrue(label + " w=" + w, w > 0.0 && w <= 1.0);
		assertTrue(label + " h=" + h, h > 0.0 && h <= 1.0);
		assertTrue(label + " x+w=" + (x + w), x + w <= 1.0 + 1e-9);
		assertTrue(label + " y+h=" + (y + h), y + h <= 1.0 + 1e-9);
		Integer depth = rec.get(OlioFieldNames.FIELD_PB_DEPTH);
		assertTrue(label + " depth " + depth, depth != null && depth >= 0);
	}

	private int countRows(BaseRecord u, String model, long groupId, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID });
		q.setCache(false);
		return ioContext.getAccessPoint().count(u, q);
	}

	/// The location's POI store, read the way PbComposeUtil reads it (location -> poi -> store), as the user.
	private BaseRecord storeOf(BaseRecord u, BaseRecord loc, long orgId) {
		BaseRecord fresh = PbLocationUtil.readLocation(u, oidOf(loc), orgId);
		assertNotNull("readLocation", fresh);
		BaseRecord poiRef = fresh.get(OlioFieldNames.FIELD_PB_POI);
		assertNotNull("the location carries a POI", poiRef);
		Query pq = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_ID, idOf(poiRef));
		pq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_STORE });
		pq.setCache(false);
		BaseRecord poi = ioContext.getAccessPoint().find(u, pq);
		assertNotNull("the POI is readable by the user", poi);
		BaseRecord store = poi.get(FieldNames.FIELD_STORE);
		assertNotNull("the POI carries a store", store);
		assertTrue("the store has an id", idOf(store) > 0L);
		return store;
	}

	/// An olio.item in the world's Items group, the TestPbV4Schema shape.
	private BaseRecord newItem(BaseRecord u, BookContext bc, String name, String description) throws Exception {
		String itemsPath = bc.getGroupPath(OlioFieldNames.FIELD_ITEMS);
		assertNotNull("Items group path", itemsPath);
		BaseRecord item = RecordFactory.newInstance(OlioModelNames.MODEL_ITEM);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, item, name, itemsPath, orgOf(u));
		item.set(FieldNames.FIELD_DESCRIPTION, description);
		List<String> materials = item.get("materials");
		materials.add("oak");
		BaseRecord created = ioContext.getAccessPoint().create(u, item);
		assertNotNull("create item " + name, created);
		item.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		item.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		return item;
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p43" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-3 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			logger.info("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup " + bk.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
		}
	}

	private void deleteQuietly(BaseRecord u, BaseRecord rec) {
		if(rec == null) {
			return;
		}
		try {
			if(!ioContext.getAccessPoint().delete(u, rec)) {
				logger.warn("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + " was not deleted");
			}
		}
		catch(Exception e) {
			logger.error("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
	}

	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting, String action, String mood,
			String description, BaseRecord location) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION,
			OlioFieldNames.FIELD_PB_MOOD, FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_LOCATION);
		patch.set(OlioFieldNames.FIELD_PB_SETTING, setting);
		patch.set(OlioFieldNames.FIELD_PB_ACTION, action);
		patch.set(OlioFieldNames.FIELD_PB_MOOD, mood);
		patch.set(FieldNames.FIELD_DESCRIPTION, description);
		patch.set(FieldNames.FIELD_LOCATION, location);
		assertNotNull("patch scene " + index, ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("scene " + index + " readable", fresh);
		assertEquals("scene.location persisted", idOf(location), fkId(fresh, FieldNames.FIELD_LOCATION));
		return fresh;
	}

	private BaseRecord createCharPerson(BaseRecord u, String groupPath, String name, String firstName, String lastName) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, firstName);
		if(lastName != null) {
			cp.set(FieldNames.FIELD_LAST_NAME, lastName);
		}
		cp.set(FieldNames.FIELD_GENDER, "female");
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("create charPerson " + name, created);
		cp.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		cp.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		return cp;
	}

	/// scene.characters has no production writer yet (P4-4+ owns that); the participation is written directly.
	private void enrol(BaseRecord u, BaseRecord scene, BaseRecord cp) {
		assertTrue("enrol " + cp.get(FieldNames.FIELD_NAME) + " in scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX),
			ioContext.getMemberUtil().member(u, scene, OlioFieldNames.FIELD_PB_CHARACTERS, cp, null, true));
	}
}
