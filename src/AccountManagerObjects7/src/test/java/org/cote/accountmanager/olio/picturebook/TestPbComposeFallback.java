package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ActorSpec;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.Cast;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.CastTier;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.Context;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ElementSpec;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.Proposal;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ResolvedActor;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ResolvedElement;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.Staging;
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
import org.cote.accountmanager.schema.type.PbShotTypeEnumType;
import org.cote.accountmanager.schema.type.PbViewpointEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-3: the compose step without a working LLM. The pure half (reply lifting, role and element-type
 * words, the MIGRATED staging builder, {@code needsCompose}) needs no book; the live half writes a throwaway book
 * in {@code /Development/World Building} as {@code testUser1}, points a real chatConfig at an unreachable host
 * ({@code OlioTestUtil.getUnreachableOllamaConfig}) and proves design §9.1 / §29 on the database: a scene with no
 * composition gets a {@code MIGRATED} one from its own fields and roster (first HERO, rest SUPPORTING, default
 * slots, {@code sdPrompt} and {@code promptLocked} carried over), a scene that already has one keeps it untouched
 * and the result says {@code hardFailure}, a forced re-compose bumps {@code revision} without duplicating rows,
 * and a user-edited composition is skipped unless {@code force}. Nothing is mocked: the LLM step really fails
 * because the host really is unreachable.
 */
public class TestPbComposeFallback extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String CFG_NAME = "P43 Dead Cfg";
	private static final String DEAD_URL = "http://127.0.0.1:1";

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── pure: applyReply ───────────────────────────────

	@Test
	public void applyReplyLiftsTheNestedShape() {
		Map<String, Object> reply = new LinkedHashMap<>();
		reply.put("location", map("name", "The Gilded Tankard taproom", "sameAs", "The Gilded Tankard", "variant", "rainy night"));
		reply.put("camera", map("shot", "medium", "viewpoint", "eye_level", "focal", "Mira Vale"));
		reply.put("actors", Arrays.asList(
			map("character", "Mira Vale", "role", "hero", "pose", "standing in the doorway", "expression", "wary",
				"action", "shaking rain from her cloak", "placement", "left foreground", "description", "a tall woman in a grey cloak"),
			map("character", null, "role", "crowd", "description", "fishermen at the bar", "count", Integer.valueOf(4), "placement", "background")));
		reply.put("elements", Arrays.asList(map("type", "furniture", "description", "a scarred corner table", "placement", "right")));
		reply.put("mood", "tense, damp");

		Proposal p = PbComposeUtil.applyReply(reply);
		assertEquals("The Gilded Tankard taproom", p.locationName);
		assertEquals("The Gilded Tankard", p.sameAs);
		assertEquals("rainy night", p.variant);
		assertEquals("medium", p.shotWord);
		assertEquals("eye_level", p.viewpointWord);
		assertEquals("Mira Vale", p.focalName);
		assertEquals("tense, damp", p.mood);
		assertEquals(2, p.actors.size());
		ActorSpec a0 = p.actors.get(0);
		assertEquals("Mira Vale", a0.name);
		assertEquals("hero", a0.roleWord);
		assertEquals("standing in the doorway", a0.pose);
		assertEquals("wary", a0.expression);
		assertEquals("shaking rain from her cloak", a0.action);
		assertEquals("left foreground", a0.placement);
		assertEquals("a tall woman in a grey cloak", a0.description);
		assertNull(a0.count);
		ActorSpec a1 = p.actors.get(1);
		assertNull("a null character stays null", a1.name);
		assertEquals("crowd", a1.roleWord);
		assertEquals("fishermen at the bar", a1.description);
		assertEquals(Integer.valueOf(4), a1.count);
		assertEquals(1, p.elements.size());
		ElementSpec e0 = p.elements.get(0);
		assertEquals("furniture", e0.typeWord);
		assertEquals("a scarred corner table", e0.description);
		assertEquals("right", e0.placement);
		assertFalse(p.isEmpty());
	}

	@Test
	public void applyReplyToleratesFlatKeysNullWordsAndJunkRows() {
		Map<String, Object> reply = new LinkedHashMap<>();
		reply.put("Location", "the quay");
		reply.put("SHOT", "wide");
		reply.put("viewpoint", "null");
		reply.put("focal", "n/a");
		reply.put("actors", Arrays.asList(
			map("name", "Tobin Ashgrove", "role", "Supporting", "count", "3"),
			map("character", "null", "role", "extra"),
			"not an object",
			map("description", "none", "pose", "null", "action", "unknown")));
		reply.put("elements", Arrays.asList(
			map("type", "prop", "name", "a brass tankard"),
			map("type", "prop", "description", "null"),
			map("type", "prop")));
		reply.put("mood", "unspecified");

		Proposal p = PbComposeUtil.applyReply(reply);
		assertEquals("a bare location string is the name", "the quay", p.locationName);
		assertNull(p.sameAs);
		assertEquals("flat camera keys are read case-insensitively", "wide", p.shotWord);
		assertNull("the literal word null is no viewpoint", p.viewpointWord);
		assertNull("n/a is no focal name", p.focalName);
		assertNull("unspecified is no mood", p.mood);
		assertEquals("one usable actor; the all-null row, the string row and the null-named extra are dropped", 1, p.actors.size());
		ActorSpec a0 = p.actors.get(0);
		assertEquals("name is accepted as an alias of character", "Tobin Ashgrove", a0.name);
		assertEquals("Supporting", a0.roleWord);
		assertEquals("a numeric string count is parsed", Integer.valueOf(3), a0.count);
		assertEquals("one usable element; a null description and a missing one are dropped", 1, p.elements.size());
		assertEquals("name is accepted as an alias of description", "a brass tankard", p.elements.get(0).description);
	}

	@Test
	public void applyReplyOfNothingIsEmpty() {
		assertTrue(PbComposeUtil.applyReply(null).isEmpty());
		assertTrue(PbComposeUtil.applyReply(new LinkedHashMap<>()).isEmpty());
		Map<String, Object> onlyCamera = new LinkedHashMap<>();
		onlyCamera.put("camera", map("shot", "close_up"));
		Proposal p = PbComposeUtil.applyReply(onlyCamera);
		assertEquals("close_up", p.shotWord);
		assertTrue("no actors and no elements is empty whatever the camera says", p.isEmpty());
	}

	@Test
	public void applyReplyCapsTheRowCountsAndCountsTheDrops() {
		List<Object> actors = new ArrayList<>();
		for(int i = 0; i < PbComposeUtil.MAX_ACTORS + 6; i++) {
			actors.add(map("character", "Figure " + i, "role", "extra", "pose", "standing"));
		}
		actors.add("junk between the rows");
		actors.add(map("character", "null", "role", "extra"));
		List<Object> elements = new ArrayList<>();
		for(int i = 0; i < PbComposeUtil.MAX_ELEMENTS + 8; i++) {
			elements.add(map("type", "prop", "description", "prop " + i));
		}
		Map<String, Object> reply = new LinkedHashMap<>();
		reply.put("actors", actors);
		reply.put("elements", elements);

		Proposal p = PbComposeUtil.applyReply(reply);
		assertEquals("actors are capped", PbComposeUtil.MAX_ACTORS, p.actors.size());
		assertEquals("the first rows are the ones kept", "Figure 0", p.actors.get(0).name);
		assertEquals("Figure " + (PbComposeUtil.MAX_ACTORS - 1), p.actors.get(PbComposeUtil.MAX_ACTORS - 1).name);
		assertEquals("only usable rows beyond the cap are counted as dropped (junk rows are not)", 6, p.droppedActors);
		assertEquals("elements are capped", PbComposeUtil.MAX_ELEMENTS, p.elements.size());
		assertEquals("prop 0", p.elements.get(0).description);
		assertEquals(8, p.droppedElements);

		Map<String, Object> small = new LinkedHashMap<>();
		small.put("actors", Arrays.asList(map("character", "Mira Vale", "role", "hero")));
		Proposal q = PbComposeUtil.applyReply(small);
		assertEquals(0, q.droppedActors);
		assertEquals(0, q.droppedElements);
	}

	@Test
	public void applyReplyCountTakesTheFirstRunOfDigitsAndDropsNonCounts() {
		Map<String, Object> reply = new LinkedHashMap<>();
		reply.put("actors", Arrays.asList(
			map("character", null, "role", "crowd", "description", "bar patrons", "count", "3 or 4"),
			map("character", null, "role", "crowd", "description", "dockhands", "count", "none"),
			map("character", null, "role", "crowd", "description", "children", "count", Integer.valueOf(0)),
			map("character", null, "role", "crowd", "description", "guards", "count", Integer.valueOf(5)),
			map("character", null, "role", "crowd", "description", "fishwives", "count", Integer.valueOf(-2)),
			map("character", null, "role", "crowd", "description", "sailors", "count", "about 12 men"),
			map("character", "Mira Vale", "role", "hero", "pose", "standing", "description", "n/a"),
			map("character", "null", "role", "crowd", "description", "stable hands", "count", "none"),
			map("character", null, "role", "crowd", "description", "longshoremen", "count", "a dozen or so, 12 figures")));

		Proposal p = PbComposeUtil.applyReply(reply);
		assertEquals("every row carries a description, so none is dropped", 9, p.actors.size());
		assertEquals("the whole phrase is scanned, not a 16-char prefix", Integer.valueOf(12), p.actors.get(8).count);
		assertEquals("\"3 or 4\" is 3, never 34", Integer.valueOf(3), p.actors.get(0).count);
		assertNull("\"none\" is no count", p.actors.get(1).count);
		assertNull("zero is no count", p.actors.get(2).count);
		assertEquals(Integer.valueOf(5), p.actors.get(3).count);
		assertNull("a negative number is no count", p.actors.get(4).count);
		assertEquals("the first run of digits in a phrase", Integer.valueOf(12), p.actors.get(5).count);
		ActorSpec mira = p.actors.get(6);
		assertEquals("Mira Vale", mira.name);
		assertNull("n/a is not a meaningful description", mira.description);
		assertEquals("standing", mira.pose);
		ActorSpec unnamed = p.actors.get(7);
		assertNull("the literal word null is no character name", unnamed.name);
		assertEquals("stable hands", unnamed.description);
		assertNull(unnamed.count);
	}

	// ─────────────────────────────── pure: the compose-scene prompt resources ───────────────────────────────

	@Test
	public void composeScenePromptSystemTextsAgreeAndEndWithNoThink() throws Exception {
		String promptJson = resource("/olio/llm/prompts/pictureBook.compose-scene.json");
		String templateJson = resource("/olio/llm/templates/promptTemplate.pictureBook.compose-scene.json");

		@SuppressWarnings("unchecked")
		Map<String, Object> prompt = JSONUtil.importObject(promptJson, Map.class);
		assertNotNull("prompts/pictureBook.compose-scene.json parses", prompt);
		assertTrue("the prompt has a user half", prompt.get("user") instanceof String && !((String) prompt.get("user")).isBlank());
		String promptSystem = joinLines(prompt.get("system"));
		assertNotNull("the prompt has a system half", promptSystem);

		@SuppressWarnings("unchecked")
		Map<String, Object> template = JSONUtil.importObject(templateJson, Map.class);
		assertNotNull("templates/promptTemplate.pictureBook.compose-scene.json parses", template);
		assertEquals(PbComposeUtil.PROMPT_COMPOSE_SCENE, template.get("name"));
		Object sections = template.get("sections");
		assertTrue("the template has sections", sections instanceof List);
		String templateSystem = null;
		for(Object o : (List<?>) sections) {
			if(o instanceof Map && "system".equals(((Map<?, ?>) o).get("sectionName"))) {
				templateSystem = joinLines(((Map<?, ?>) o).get("lines"));
			}
		}
		assertNotNull("the template has a system section with lines", templateSystem);

		assertTrue("the prompt system text ends with /no_think, got ...'" + tail(promptSystem) + "'", promptSystem.trim().endsWith("/no_think"));
		assertTrue("the template system text ends with /no_think, got ...'" + tail(templateSystem) + "'", templateSystem.trim().endsWith("/no_think"));
		assertEquals("the two system texts are one text", promptSystem.trim(), templateSystem.trim());
	}

	// ─────────────────────────────── pure: words → enums ───────────────────────────────

	@Test
	public void roleWordsAreTolerant() {
		assertEquals(PbActorRoleEnumType.HERO, PbComposeUtil.roleFor("hero"));
		assertEquals(PbActorRoleEnumType.HERO, PbComposeUtil.roleFor(" Protagonist "));
		assertEquals(PbActorRoleEnumType.SUPPORTING, PbComposeUtil.roleFor("companion"));
		assertEquals(PbActorRoleEnumType.NAMED_BACKGROUND, PbComposeUtil.roleFor("named-background"));
		assertEquals(PbActorRoleEnumType.NAMED_BACKGROUND, PbComposeUtil.roleFor("background"));
		assertEquals(PbActorRoleEnumType.EXTRA, PbComposeUtil.roleFor("bystander"));
		assertEquals(PbActorRoleEnumType.CROWD, PbComposeUtil.roleFor("patrons"));
		assertNull("an unknown word is null so the caller applies its own rule", PbComposeUtil.roleFor("villain"));
		assertNull(PbComposeUtil.roleFor("null"));
		assertNull(PbComposeUtil.roleFor(null));
		assertNull(PbComposeUtil.roleFor("  "));
	}

	@Test
	public void elementTypeWordsAreTolerantAndNeverNull() {
		assertEquals(PbElementTypeEnumType.PROP, PbComposeUtil.elementTypeFor("prop"));
		assertEquals(PbElementTypeEnumType.FURNITURE, PbComposeUtil.elementTypeFor("Table"));
		assertEquals(PbElementTypeEnumType.ANIMAL, PbComposeUtil.elementTypeFor("horse"));
		assertEquals(PbElementTypeEnumType.VEHICLE, PbComposeUtil.elementTypeFor("wagon"));
		assertEquals(PbElementTypeEnumType.STRUCTURE, PbComposeUtil.elementTypeFor("door"));
		assertEquals(PbElementTypeEnumType.NATURE, PbComposeUtil.elementTypeFor("sky"));
		assertEquals(PbElementTypeEnumType.OTHER, PbComposeUtil.elementTypeFor("gizmo"));
		assertEquals(PbElementTypeEnumType.OTHER, PbComposeUtil.elementTypeFor("none"));
		assertEquals(PbElementTypeEnumType.OTHER, PbComposeUtil.elementTypeFor(null));
	}

	// ─────────────────────────────── pure: MIGRATED staging ───────────────────────────────

	@Test
	public void migratedStagingFollowsTheRosterOrderAndTheSceneFields() throws Exception {
		BaseRecord scene = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE);
		scene.set(OlioFieldNames.FIELD_PB_MOOD, "  hushed  ");
		scene.set(OlioFieldNames.FIELD_PB_SETTING, "The abbey scriptorium after midnight");
		List<BaseRecord> roster = Arrays.asList(bareCharPerson("Mira Vale"), bareCharPerson("Tobin Ashgrove"));

		Staging s = PbComposeUtil.migratedStaging(scene, roster);
		assertEquals(PbCompositionSourceEnumType.MIGRATED, s.source);
		assertEquals(PbShotTypeEnumType.MEDIUM, s.shot);
		assertEquals(PbViewpointEnumType.EYE_LEVEL, s.viewpoint);
		assertEquals("hushed", s.mood);
		assertEquals("The abbey scriptorium after midnight", s.settingText);
		assertNull(s.cameraText);
		assertEquals(2, s.actors.size());
		assertEquals(0, s.focalIndex);
		assertTrue(s.elements.isEmpty());
		ResolvedActor first = s.actors.get(0);
		assertEquals(PbActorRoleEnumType.HERO, first.role);
		assertEquals("Mira Vale", first.displayName);
		assertTrue(first.character == roster.get(0));
		ResolvedActor second = s.actors.get(1);
		assertEquals(PbActorRoleEnumType.SUPPORTING, second.role);
		assertEquals("Tobin Ashgrove", second.displayName);
		for(ResolvedActor ra : s.actors) {
			assertNotNull(ra.placement);
			assertFalse("a default slot is never reported as a recognised word", ra.placementRecognized);
			assertUnit(ra.displayName, ra.placement.x(), ra.placement.y(), ra.placement.w(), ra.placement.h());
		}
		assertTrue("two figures do not share one slot", first.placement.x() != second.placement.x());
	}

	@Test
	public void migratedStagingGoesWideForABigRosterAndEmptyForNone() throws Exception {
		BaseRecord scene = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE);
		scene.set(OlioFieldNames.FIELD_PB_MOOD, "null");
		List<BaseRecord> five = new ArrayList<>();
		for(int i = 1; i <= 5; i++) {
			five.add(bareCharPerson("Person " + i));
		}
		Staging wide = PbComposeUtil.migratedStaging(scene, five);
		assertEquals("more than three figures is a WIDE shot", PbShotTypeEnumType.WIDE, wide.shot);
		assertEquals(5, wide.actors.size());
		assertEquals(PbActorRoleEnumType.HERO, wide.actors.get(0).role);
		for(int i = 1; i < 5; i++) {
			assertEquals(PbActorRoleEnumType.SUPPORTING, wide.actors.get(i).role);
		}
		assertNull("the literal word null is no mood", wide.mood);
		assertNull("no setting on the scene, no settingText", wide.settingText);

		Staging none = PbComposeUtil.migratedStaging(scene, new ArrayList<>());
		assertEquals(PbShotTypeEnumType.MEDIUM, none.shot);
		assertTrue(none.actors.isEmpty());
		assertEquals(-1, none.focalIndex);
		Staging nul = PbComposeUtil.migratedStaging(scene, null);
		assertTrue(nul.actors.isEmpty());
		assertEquals(-1, nul.focalIndex);
	}

	// ─────────────────────────────── pure: needsCompose ───────────────────────────────

	@Test
	public void needsComposeFlagsOnlyAnUneditedMigratedSceneComposition() throws Exception {
		assertTrue("no composition yet", PbComposeUtil.needsCompose(null));
		assertTrue(PbComposeUtil.needsCompose(composition(PbCompositionSourceEnumType.MIGRATED, false, PbCompositionPurposeEnumType.SCENE)));
		assertFalse("an LLM composition is done", PbComposeUtil.needsCompose(composition(PbCompositionSourceEnumType.LLM, false, PbCompositionPurposeEnumType.SCENE)));
		assertFalse("a USER composition is done", PbComposeUtil.needsCompose(composition(PbCompositionSourceEnumType.USER, false, PbCompositionPurposeEnumType.SCENE)));
		assertFalse("a user-edited MIGRATED composition is left alone", PbComposeUtil.needsCompose(composition(PbCompositionSourceEnumType.MIGRATED, true, PbCompositionPurposeEnumType.SCENE)));
		assertFalse("only SCENE-purpose compositions are composed", PbComposeUtil.needsCompose(composition(PbCompositionSourceEnumType.MIGRATED, false, PbCompositionPurposeEnumType.COVER)));
		BaseRecord noPurpose = composition(PbCompositionSourceEnumType.MIGRATED, false, null);
		String materialisedPurpose = noPurpose.get(OlioFieldNames.FIELD_PB_PURPOSE);
		assertEquals("an unset enum materialises as UNKNOWN, never null", PbCompositionPurposeEnumType.UNKNOWN.toString(), materialisedPurpose);
		assertFalse("an UNKNOWN purpose is not SCENE, so it is not composed", PbComposeUtil.needsCompose(noPurpose));
	}

	// ─────────────────────────────── live DB, dead LLM ───────────────────────────────

	@Test
	public void deadLlmWritesMigratedThenLeavesItAloneUntilForced() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		try {
			cfg = OlioTestUtil.getUnreachableOllamaConfig(u, CFG_NAME, DEAD_URL, "qwen3:8b", 5);
			assertNotNull("chatConfig " + CFG_NAME, cfg);
			logger.info("[p43-dead] chatConfig " + CFG_NAME + " -> " + DEAD_URL + " (no LLM can answer here by construction)");

			bk = newBook(u, "dead");
			BookContext bctx = PbBookUtil.openBookContext(u, bk);
			assertNotNull("The book must carry a world", bctx);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("The book world must expose its Population group", popPath);

			BaseRecord scene = addScene(u, bk, 3, "Arrival",
				"The Gilded Tankard, a low-beamed tavern by the quay", "Mira shakes the rain from her cloak and finds Tobin at the corner table", "tense",
				"Mira Vale pushes through the taproom door, rain dripping from her cloak. Tobin Ashgrove is already at the corner table.",
				"landscape, a low-beamed tavern taproom at night, rain on the windows", Boolean.TRUE);
			BaseRecord mira = createCharPerson(u, popPath, "Mira Vale", "Mira", "Vale");
			BaseRecord tobin = createCharPerson(u, popPath, "Tobin Ashgrove", "Tobin", "Ashgrove");
			enrol(u, scene, mira);
			enrol(u, scene, tobin);

			/// 1. No composition yet, LLM unreachable -> MIGRATED from the scene's own fields and roster.
			long t0 = System.currentTimeMillis();
			ComposeResult r1 = PbComposeUtil.composeScene(u, bk, scene, cfg, false);
			logger.info("[p43-dead] compose 1 (" + (System.currentTimeMillis() - t0) + " ms): " + describe(r1));
			assertTrue("the LLM path must fall back: " + r1.failureReason, r1.migratedFallback);
			assertFalse("a fallback is not a hard failure", r1.hardFailure);
			assertFalse(r1.skippedUserEdited);
			assertTrue(r1.isWritten());
			assertEquals(PbCompositionSourceEnumType.MIGRATED, r1.source);
			assertEquals("the roster came from scene.characters", CastTier.SCENE_MEMBERS, r1.castTier);
			assertNotNull("a composition was written", r1.composition);
			BaseRecord comp = r1.composition;
			String compOid = oidOf(comp);
			assertEquals(PbCompositionSourceEnumType.MIGRATED.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(PbCompositionPurposeEnumType.SCENE.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals(Integer.valueOf(1), comp.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(Boolean.FALSE, comp.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals("the composition lives in the scene's group", idOf(groupOf(scene)), idOf(groupOf(comp)));
			assertEquals("the composition points at the scene", idOf(scene), fkId(comp, OlioFieldNames.FIELD_PB_SCENE));
			assertEquals("the composition points at the book", idOf(bk), fkId(comp, OlioFieldNames.FIELD_PB_BOOK));
			assertEquals("sdPrompt migrates to promptOverride", "landscape, a low-beamed tavern taproom at night, rain on the windows",
				comp.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals("promptLocked migrates", Boolean.TRUE, comp.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(PbShotTypeEnumType.MEDIUM.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_SHOT_TYPE));
			assertEquals(PbViewpointEnumType.EYE_LEVEL.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_VIEWPOINT));
			assertEquals("tense", comp.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("The Gilded Tankard, a low-beamed tavern by the quay", comp.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("Mira shakes the rain from her cloak and finds Tobin at the corner table", comp.get(OlioFieldNames.FIELD_PB_ACTION));
			assertTrue("a MIGRATED, unedited composition is flagged for a real compose", PbComposeUtil.needsCompose(comp));

			assertEquals("two actor rows, no elements", 2, r1.actors.size());
			assertEquals(0, r1.elements.size());
			BaseRecord hero = r1.actors.get(0);
			BaseRecord support = r1.actors.get(1);
			assertEquals(Integer.valueOf(0), hero.get(OlioFieldNames.FIELD_PB_PRIORITY));
			assertEquals(Integer.valueOf(1), support.get(OlioFieldNames.FIELD_PB_PRIORITY));
			assertEquals(PbActorRoleEnumType.HERO.toString(), enumWord(hero, OlioFieldNames.FIELD_PB_ROLE));
			assertEquals(PbActorRoleEnumType.SUPPORTING.toString(), enumWord(support, OlioFieldNames.FIELD_PB_ROLE));
			List<Long> actorCharIds = Arrays.asList(fkId(hero, OlioFieldNames.FIELD_PB_CHARACTER), fkId(support, OlioFieldNames.FIELD_PB_CHARACTER));
			assertTrue("both roster members are actors: " + actorCharIds, actorCharIds.contains(idOf(mira)) && actorCharIds.contains(idOf(tobin)));
			assertEquals("the focal actor is the HERO row", idOf(hero), fkId(comp, OlioFieldNames.FIELD_PB_FOCAL_ACTOR));
			for(BaseRecord row : r1.actors) {
				assertEquals("the row lives in the scene's group", idOf(groupOf(scene)), idOf(groupOf(row)));
				assertEquals(idOf(comp), fkId(row, OlioFieldNames.FIELD_PB_COMPOSITION));
				assertUnit((String) row.get(FieldNames.FIELD_NAME), dbl(row, OlioFieldNames.FIELD_PB_X), dbl(row, OlioFieldNames.FIELD_PB_Y),
					dbl(row, OlioFieldNames.FIELD_PB_W), dbl(row, OlioFieldNames.FIELD_PB_H));
				assertEquals(Integer.valueOf(1), row.get(FieldNames.FIELD_COUNT));
			}
			long gid = idOf(groupOf(scene));
			assertEquals("findSceneComposition sees the one composition", compOid, oidOf(PbComposeUtil.findSceneComposition(u, scene, gid, orgId)));

			/// 2. The LLM is still unreachable and a composition exists: hard failure, nothing touched.
			t0 = System.currentTimeMillis();
			ComposeResult r2 = PbComposeUtil.composeScene(u, bk, scene, cfg, false);
			logger.info("[p43-dead] compose 2 (" + (System.currentTimeMillis() - t0) + " ms): " + describe(r2));
			assertTrue("an existing composition is never replaced by a fallback", r2.hardFailure);
			assertFalse(r2.migratedFallback);
			assertFalse(r2.isWritten());
			assertEquals(compOid, oidOf(r2.composition));
			assertEquals(PbCompositionSourceEnumType.MIGRATED, r2.source);
			assertEquals(2, r2.actors.size());
			BaseRecord after2 = PbComposeUtil.readComposition(u, compOid, orgId);
			assertEquals("revision untouched", Integer.valueOf(1), after2.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("focalActor untouched", idOf(hero), fkId(after2, OlioFieldNames.FIELD_PB_FOCAL_ACTOR));
			assertEquals("rows untouched", oidOf(hero), oidOf(PbComposeUtil.listActors(u, after2).get(0)));

			/// 3. A forced MIGRATED refresh replaces the rows in place: same composition, revision 2, still two rows.
			t0 = System.currentTimeMillis();
			ComposeResult r3 = PbComposeUtil.composeMigrated(u, bk, scene, true);
			logger.info("[p43-dead] migrated refresh (" + (System.currentTimeMillis() - t0) + " ms): " + describe(r3));
			assertTrue(r3.isWritten());
			assertFalse(r3.hardFailure);
			assertEquals(compOid, oidOf(r3.composition));
			assertEquals(Integer.valueOf(2), r3.composition.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("rows are replaced, never accumulated", 2, r3.actors.size());
			assertEquals(2, PbComposeUtil.listActors(u, r3.composition).size());
			assertFalse("the old HERO row is gone", oidOf(hero).equals(oidOf(r3.actors.get(0))));
			assertEquals("the new HERO row is the focal actor", idOf(r3.actors.get(0)), fkId(r3.composition, OlioFieldNames.FIELD_PB_FOCAL_ACTOR));
			assertEquals("still exactly one SCENE composition", compOid, oidOf(PbComposeUtil.findSceneComposition(u, scene, gid, orgId)));
			assertEquals("still MIGRATED", PbCompositionSourceEnumType.MIGRATED.toString(), enumWord(r3.composition, OlioFieldNames.FIELD_PB_SOURCE));

			/// 4. userEdited guards both entry points unless forced.
			BaseRecord edit = PbGraphUtil.patchOf(r3.composition, OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_USER_EDITED);
			edit.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
			assertNotNull("mark userEdited", ioContext.getAccessPoint().update(u, edit));
			ComposeResult r4 = PbComposeUtil.composeScene(u, bk, scene, cfg, false);
			logger.info("[p43-dead] compose 4: " + describe(r4));
			assertTrue("a user-edited composition is skipped", r4.skippedUserEdited);
			assertFalse(r4.hardFailure);
			assertFalse(r4.migratedFallback);
			assertFalse(r4.isWritten());
			assertEquals(compOid, oidOf(r4.composition));
			assertEquals(2, r4.actors.size());
			ComposeResult r5 = PbComposeUtil.composeMigrated(u, bk, scene, false);
			assertTrue("the MIGRATED builder honours userEdited too", r5.skippedUserEdited);
			assertEquals(Integer.valueOf(2), PbComposeUtil.readComposition(u, compOid, orgId).get(OlioFieldNames.FIELD_PB_REVISION));
			assertFalse("a user-edited composition is not flagged for compose", PbComposeUtil.needsCompose(r4.composition));

			ComposeResult r6 = PbComposeUtil.composeMigrated(u, bk, scene, true);
			logger.info("[p43-dead] forced refresh over userEdited: " + describe(r6));
			assertTrue(r6.isWritten());
			assertEquals(compOid, oidOf(r6.composition));
			assertEquals(Integer.valueOf(3), r6.composition.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("force resets userEdited", Boolean.FALSE, r6.composition.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals(2, r6.actors.size());

			/// 5. A big roster goes WIDE; the first member is still the HERO.
			BaseRecord crowdScene = addScene(u, bk, 4, "Festival Night", "The Gilded Tankard, the taproom crowded for the festival", null, "crowded", null, null, null);
			List<BaseRecord> five = new ArrayList<>();
			five.add(mira);
			five.add(tobin);
			for(String n : new String[] {"Old Marta", "Pell Dunmore", "Isolde Rook"}) {
				five.add(createCharPerson(u, popPath, n, n.substring(0, n.indexOf(' ')), n.substring(n.indexOf(' ') + 1)));
			}
			for(BaseRecord cp : five) {
				enrol(u, crowdScene, cp);
			}
			ComposeResult r7 = PbComposeUtil.composeScene(u, bk, crowdScene, cfg, false);
			logger.info("[p43-dead] compose crowd scene: " + describe(r7));
			assertTrue(r7.migratedFallback);
			assertTrue(r7.isWritten());
			assertEquals(5, r7.actors.size());
			assertEquals(PbShotTypeEnumType.WIDE.toString(), enumWord(r7.composition, OlioFieldNames.FIELD_PB_SHOT_TYPE));
			assertEquals(PbActorRoleEnumType.HERO.toString(), enumWord(r7.actors.get(0), OlioFieldNames.FIELD_PB_ROLE));
			for(int i = 1; i < 5; i++) {
				assertEquals(PbActorRoleEnumType.SUPPORTING.toString(), enumWord(r7.actors.get(i), OlioFieldNames.FIELD_PB_ROLE));
			}
			assertNull("no sdPrompt on this scene, no promptOverride", r7.composition.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals(Boolean.FALSE, r7.composition.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertFalse("the two scenes have distinct compositions", compOid.equals(oidOf(r7.composition)));
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

	// ─────────────────────────────── live DB, hand-built proposal (no LLM) ───────────────────────────────

	/**
	 * The seams {@code openContext -> collectCast -> applyReply -> stage -> persist} against a real book: a
	 * proposal written by hand stands in for the LLM reply, so every rule of §9.1 is exercised on the database
	 * without a model in the loop. Names resolve against the scene roster (full name, then first name for the focal
	 * word), an unknown name is kept as NAMED_BACKGROUND with its detail folded into the description, an unknown
	 * placement word is surfaced and defaulted, a crowd keeps the first integer of "3 or 4", element descriptions
	 * link the location's store items by whole word (so "cupboard" never links "cup"), and the rows written carry
	 * the character / item FKs and the focal actor.
	 */
	@Test
	public void stageResolvesNamesLinksItemsAndSurfacesUnknownPlacements() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "stage");
			BookContext bctx = PbBookUtil.openBookContext(u, bk);
			assertNotNull("The book must carry a world", bctx);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("The book world must expose its Population group", popPath);

			BaseRecord loc = PbLocationUtil.getCreateLocation(u, bk, "The Gilded Tankard", "A low-beamed tavern by the quay",
				PbLocationKindEnumType.INTERIOR, Arrays.asList("the Tankard", "the taproom"));
			assertNotNull("getCreateLocation", loc);
			BaseRecord store = storeOf(u, loc, orgId);
			BaseRecord cup = newItem(u, bctx, "cup", "a dented pewter cup");
			BaseRecord table = newItem(u, bctx, "scarred corner table", "the corner table, scarred by knives");
			assertTrue("enrol cup in the store", ioContext.getMemberUtil().member(u, store, OlioFieldNames.FIELD_ITEMS, cup, null, true));
			assertTrue("enrol table in the store", ioContext.getMemberUtil().member(u, store, OlioFieldNames.FIELD_ITEMS, table, null, true));

			BaseRecord scene = addSceneAt(u, bk, 5, "Arrival",
				"The Gilded Tankard, a low-beamed tavern by the quay", "Mira shakes the rain from her cloak and finds Tobin at the corner table", "tense",
				"Mira Vale pushes through the taproom door, rain dripping from her cloak. Tobin Ashgrove is already at the corner table.", loc);
			BaseRecord mira = createCharPerson(u, popPath, "Mira Vale", "Mira", "Vale");
			BaseRecord tobin = createCharPerson(u, popPath, "Tobin Ashgrove", "Tobin", "Ashgrove");
			enrol(u, scene, mira);
			enrol(u, scene, tobin);

			/// 1. The context and the cast come from the real scene.
			ComposeResult result = new ComposeResult();
			Context ctx = PbComposeUtil.openContext(u, bk, scene, "compose scene");
			assertEquals(idOf(bk), idOf(ctx.book));
			assertEquals(oidOf(scene), oidOf(ctx.scene));
			assertEquals(5, ctx.sceneIndex);
			assertEquals("the scene carries its location", idOf(loc), fkId(ctx.scene, FieldNames.FIELD_LOCATION));
			Cast cast = PbComposeUtil.collectCast(u, ctx, result);
			assertEquals(CastTier.SCENE_MEMBERS, cast.tier);
			assertEquals("the two enrolled characters", 2, cast.members.size());

			/// 2. A hand-written reply through the real parser.
			Proposal p = PbComposeUtil.applyReply(map(
				"location", map("name", "The Gilded Tankard", "sameAs", "the taproom", "variant", "night"),
				"camera", map("shot", "medium", "viewpoint", "eye_level", "focal", "Mira"),
				"actors", Arrays.asList(
					map("character", "Mira Vale", "role", "hero", "pose", "shaking rain from her cloak", "expression", "wary",
						"placement", "left foreground", "description", "n/a"),
					map("character", "Old Gaffer Pim", "role", "supporting", "pose", "leaning on the bar", "placement", "upstairs"),
					map("character", null, "role", "crowd", "description", "bar patrons", "count", "3 or 4", "placement", "background")),
				"elements", Arrays.asList(
					map("type", "furniture", "description", "a tall cupboard", "placement", "right"),
					map("type", "prop", "description", "two cups poured", "placement", "centre"),
					map("type", "furniture", "description", "the scarred corner table", "placement", "right foreground")),
				"mood", "tense"));
			assertEquals(3, p.actors.size());
			assertEquals(3, p.elements.size());
			assertEquals("Mira", p.focalName);
			assertEquals(Integer.valueOf(3), p.actors.get(2).count);

			/// 3. Staging: names, roles, placements, camera, items.
			Staging s = PbComposeUtil.stage(u, ctx, cast, p, result);
			assertEquals(PbCompositionSourceEnumType.LLM, s.source);
			assertEquals(3, s.actors.size());

			ResolvedActor a0 = s.actors.get(0);
			assertNotNull("Mira Vale resolves against the roster", a0.character);
			assertEquals(idOf(mira), idOf(a0.character));
			assertEquals("Mira Vale", a0.displayName);
			assertEquals(PbActorRoleEnumType.HERO, a0.role);
			assertNull("n/a is not a description", a0.description);
			assertEquals("shaking rain from her cloak", a0.pose);
			assertEquals("wary", a0.expression);
			assertEquals(1, a0.count);
			assertTrue("'left foreground' is a known placement", a0.placementRecognized);
			assertNotNull(a0.placement);
			assertUnit("Mira", a0.placement.x(), a0.placement.y(), a0.placement.w(), a0.placement.h());

			ResolvedActor a1 = s.actors.get(1);
			assertNull("Old Gaffer Pim is nobody in this book", a1.character);
			assertEquals(PbActorRoleEnumType.NAMED_BACKGROUND, a1.role);
			assertEquals("Old Gaffer Pim", a1.displayName);
			assertNotNull(a1.description);
			assertTrue("the unresolved name and its detail are folded into the description: " + a1.description,
				a1.description.startsWith("Old Gaffer Pim - leaning"));
			assertEquals(1, a1.count);
			assertFalse("'upstairs' is not a placement word", a1.placementRecognized);
			assertNotNull("an unknown placement still gets a default slot", a1.placement);
			assertUnit("Old Gaffer Pim", a1.placement.x(), a1.placement.y(), a1.placement.w(), a1.placement.h());
			assertEquals(Arrays.asList("Old Gaffer Pim"), result.unresolvedNames);
			assertTrue("the unknown placement word is surfaced: " + result.unrecognizedPlacements, result.unrecognizedPlacements.contains("upstairs"));
			assertFalse("known placement words are not reported", result.unrecognizedPlacements.contains("left foreground"));

			ResolvedActor a2 = s.actors.get(2);
			assertNull(a2.character);
			assertEquals(PbActorRoleEnumType.CROWD, a2.role);
			assertEquals("bar patrons", a2.displayName);
			assertEquals("\"3 or 4\" is three", 3, a2.count);
			assertTrue("'background' is a known placement", a2.placementRecognized);

			assertEquals("the focal word 'Mira' finds Mira Vale by first name", 0, s.focalIndex);
			assertEquals(PbShotTypeEnumType.MEDIUM, s.shot);
			assertEquals(PbViewpointEnumType.EYE_LEVEL, s.viewpoint);
			assertEquals("medium, eye_level", s.cameraText);
			assertEquals("the scene's own mood wins", "tense", s.mood);
			assertEquals("the scene's own setting wins", "The Gilded Tankard, a low-beamed tavern by the quay", s.settingText);
			assertEquals("The Gilded Tankard; sameAs=the taproom; variant=night", result.locationHint);

			assertEquals(3, s.elements.size());
			ResolvedElement e0 = s.elements.get(0);
			ResolvedElement e1 = s.elements.get(1);
			ResolvedElement e2 = s.elements.get(2);
			assertEquals(PbElementTypeEnumType.FURNITURE, e0.type);
			assertNull("'cupboard' must not link the item 'cup'", e0.item);
			assertEquals(PbElementTypeEnumType.PROP, e1.type);
			assertNotNull("'two cups poured' links the item 'cup' by plural whole word", e1.item);
			assertEquals(idOf(cup), idOf(e1.item));
			assertEquals(PbElementTypeEnumType.FURNITURE, e2.type);
			assertNotNull("'the scarred corner table' links the longer item name", e2.item);
			assertEquals(idOf(table), idOf(e2.item));
			for(ResolvedElement re : s.elements) {
				assertNotNull(re.placement);
				assertUnit(re.description, re.placement.x(), re.placement.y(), re.placement.w(), re.placement.h());
			}

			/// 4. Role rules on resolved names: no role words -> first HERO, rest SUPPORTING; explicit words honoured;
			///    a resolved person called a 'crowd' of five is still one HERO.
			Proposal plain = PbComposeUtil.applyReply(map("actors", Arrays.asList(
				map("character", "Mira Vale", "pose", "standing"),
				map("character", "Tobin Ashgrove", "pose", "seated"))));
			Staging s2 = PbComposeUtil.stage(u, ctx, cast, plain, new ComposeResult());
			assertEquals(2, s2.actors.size());
			assertEquals(idOf(mira), idOf(s2.actors.get(0).character));
			assertEquals(PbActorRoleEnumType.HERO, s2.actors.get(0).role);
			assertEquals(idOf(tobin), idOf(s2.actors.get(1).character));
			assertEquals(PbActorRoleEnumType.SUPPORTING, s2.actors.get(1).role);
			assertEquals("no focal word: the first HERO", 0, s2.focalIndex);

			Proposal swapped = PbComposeUtil.applyReply(map("actors", Arrays.asList(
				map("character", "Mira Vale", "role", "supporting", "pose", "standing"),
				map("character", "Tobin Ashgrove", "role", "hero", "pose", "seated"))));
			Staging s3 = PbComposeUtil.stage(u, ctx, cast, swapped, new ComposeResult());
			assertEquals(2, s3.actors.size());
			assertEquals(PbActorRoleEnumType.SUPPORTING, s3.actors.get(0).role);
			assertEquals(PbActorRoleEnumType.HERO, s3.actors.get(1).role);
			assertEquals("no focal word: the first HERO, wherever it sits", 1, s3.focalIndex);

			Proposal crowdWord = PbComposeUtil.applyReply(map("actors", Arrays.asList(
				map("character", "Tobin", "role", "crowd", "count", Integer.valueOf(5), "pose", "seated"))));
			Staging s4 = PbComposeUtil.stage(u, ctx, cast, crowdWord, new ComposeResult());
			assertEquals(1, s4.actors.size());
			assertEquals("'Tobin' resolves by unique first name", idOf(tobin), idOf(s4.actors.get(0).character));
			assertEquals("a resolved person is never a CROWD", PbActorRoleEnumType.HERO, s4.actors.get(0).role);
			assertEquals("a resolved person is one figure", 1, s4.actors.get(0).count);

			/// 5. Persist the first staging and read the rows back as the user.
			PbComposeUtil.persist(u, ctx, null, s, result);
			logger.info("[p43-stage] persist: " + describe(result));
			assertFalse("persist must not fail: " + result.failureReason, result.hardFailure);
			assertTrue(result.isWritten());
			assertEquals(PbCompositionSourceEnumType.LLM, result.source);
			BaseRecord comp = result.composition;
			assertNotNull(comp);
			String compOid = oidOf(comp);
			assertEquals(PbCompositionSourceEnumType.LLM.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(PbCompositionPurposeEnumType.SCENE.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals(Integer.valueOf(1), comp.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(Boolean.FALSE, comp.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals(idOf(scene), fkId(comp, OlioFieldNames.FIELD_PB_SCENE));
			assertEquals(idOf(bk), fkId(comp, OlioFieldNames.FIELD_PB_BOOK));
			assertEquals("the composition carries the scene's location", idOf(loc), fkId(comp, FieldNames.FIELD_LOCATION));
			assertEquals(PbShotTypeEnumType.MEDIUM.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_SHOT_TYPE));
			assertEquals(PbViewpointEnumType.EYE_LEVEL.toString(), enumWord(comp, OlioFieldNames.FIELD_PB_VIEWPOINT));
			assertEquals("medium, eye_level", comp.get(OlioFieldNames.FIELD_PB_CAMERA_TEXT));
			assertEquals("tense", comp.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("The Gilded Tankard, a low-beamed tavern by the quay", comp.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("Mira shakes the rain from her cloak and finds Tobin at the corner table", comp.get(OlioFieldNames.FIELD_PB_ACTION));
			assertFalse("an LLM composition is not flagged for compose", PbComposeUtil.needsCompose(comp));

			List<BaseRecord> rows = PbComposeUtil.listActors(u, comp);
			assertEquals(3, rows.size());
			assertEquals(3, result.actors.size());
			for(int i = 0; i < 3; i++) {
				BaseRecord row = rows.get(i);
				assertEquals(Integer.valueOf(i), row.get(OlioFieldNames.FIELD_PB_PRIORITY));
				assertEquals(idOf(comp), fkId(row, OlioFieldNames.FIELD_PB_COMPOSITION));
				assertEquals(idOf(groupOf(scene)), idOf(groupOf(row)));
				assertUnit((String) row.get(FieldNames.FIELD_NAME), dbl(row, OlioFieldNames.FIELD_PB_X), dbl(row, OlioFieldNames.FIELD_PB_Y),
					dbl(row, OlioFieldNames.FIELD_PB_W), dbl(row, OlioFieldNames.FIELD_PB_H));
			}
			BaseRecord r0 = rows.get(0);
			assertEquals(PbActorRoleEnumType.HERO.toString(), enumWord(r0, OlioFieldNames.FIELD_PB_ROLE));
			assertEquals(idOf(mira), fkId(r0, OlioFieldNames.FIELD_PB_CHARACTER));
			assertEquals("shaking rain from her cloak", r0.get(OlioFieldNames.FIELD_PB_POSE));
			assertEquals("wary", r0.get(FieldNames.FIELD_EXPRESSION));
			assertEquals(Integer.valueOf(1), r0.get(FieldNames.FIELD_COUNT));
			BaseRecord r1 = rows.get(1);
			assertEquals(PbActorRoleEnumType.NAMED_BACKGROUND.toString(), enumWord(r1, OlioFieldNames.FIELD_PB_ROLE));
			assertEquals("an unresolved name has no character FK", 0L, fkId(r1, OlioFieldNames.FIELD_PB_CHARACTER));
			assertEquals("the unresolved name survives in the description", a1.description, r1.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals("leaning on the bar", r1.get(OlioFieldNames.FIELD_PB_POSE));
			assertEquals(Integer.valueOf(1), r1.get(FieldNames.FIELD_COUNT));
			BaseRecord r2 = rows.get(2);
			assertEquals(PbActorRoleEnumType.CROWD.toString(), enumWord(r2, OlioFieldNames.FIELD_PB_ROLE));
			assertEquals(0L, fkId(r2, OlioFieldNames.FIELD_PB_CHARACTER));
			assertEquals("bar patrons", r2.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals(Integer.valueOf(3), r2.get(FieldNames.FIELD_COUNT));
			assertEquals("the focal actor is Mira's row", idOf(r0), fkId(comp, OlioFieldNames.FIELD_PB_FOCAL_ACTOR));

			List<BaseRecord> els = PbComposeUtil.listElements(u, comp);
			assertEquals(3, els.size());
			assertEquals(3, result.elements.size());
			for(BaseRecord el : els) {
				assertEquals(idOf(comp), fkId(el, OlioFieldNames.FIELD_PB_COMPOSITION));
				assertEquals(idOf(groupOf(scene)), idOf(groupOf(el)));
				assertUnit((String) el.get(FieldNames.FIELD_NAME), dbl(el, OlioFieldNames.FIELD_PB_X), dbl(el, OlioFieldNames.FIELD_PB_Y),
					dbl(el, OlioFieldNames.FIELD_PB_W), dbl(el, OlioFieldNames.FIELD_PB_H));
			}
			assertEquals(PbElementTypeEnumType.FURNITURE.toString(), enumWord(els.get(0), OlioFieldNames.FIELD_PB_ELEMENT_TYPE));
			assertEquals("a tall cupboard", els.get(0).get(FieldNames.FIELD_DESCRIPTION));
			assertEquals("no item on the cupboard row", 0L, fkId(els.get(0), OlioFieldNames.FIELD_ITEM));
			assertEquals(PbElementTypeEnumType.PROP.toString(), enumWord(els.get(1), OlioFieldNames.FIELD_PB_ELEMENT_TYPE));
			assertEquals("the cup row links the cup", idOf(cup), fkId(els.get(1), OlioFieldNames.FIELD_ITEM));
			assertEquals(PbElementTypeEnumType.FURNITURE.toString(), enumWord(els.get(2), OlioFieldNames.FIELD_PB_ELEMENT_TYPE));
			assertEquals("the table row links the table", idOf(table), fkId(els.get(2), OlioFieldNames.FIELD_ITEM));
			long gid = idOf(groupOf(scene));
			assertEquals("findSceneComposition sees the one composition", compOid, oidOf(PbComposeUtil.findSceneComposition(u, scene, gid, orgId)));

			/// 6. A forced MIGRATED refresh on top replaces the rows: same composition, revision 2, roster only, no elements.
			ComposeResult r = PbComposeUtil.composeMigrated(u, bk, scene, true);
			logger.info("[p43-stage] migrated refresh: " + describe(r));
			assertTrue(r.isWritten());
			assertFalse(r.hardFailure);
			assertEquals(compOid, oidOf(r.composition));
			assertEquals(PbCompositionSourceEnumType.MIGRATED, r.source);
			assertEquals(Integer.valueOf(2), r.composition.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("the roster replaces the LLM rows", 2, r.actors.size());
			assertEquals(2, PbComposeUtil.listActors(u, r.composition).size());
			assertEquals("the element rows are gone", 0, r.elements.size());
			assertEquals(0, PbComposeUtil.listElements(u, r.composition).size());
			List<Long> charIds = Arrays.asList(fkId(r.actors.get(0), OlioFieldNames.FIELD_PB_CHARACTER), fkId(r.actors.get(1), OlioFieldNames.FIELD_PB_CHARACTER));
			assertTrue("both roster members are actors: " + charIds, charIds.contains(idOf(mira)) && charIds.contains(idOf(tobin)));
			assertEquals(PbActorRoleEnumType.HERO.toString(), enumWord(r.actors.get(0), OlioFieldNames.FIELD_PB_ROLE));
			assertEquals(idOf(r.actors.get(0)), fkId(r.composition, OlioFieldNames.FIELD_PB_FOCAL_ACTOR));
			assertTrue("a MIGRATED, unedited composition is flagged for compose again", PbComposeUtil.needsCompose(r.composition));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static String resource(String path) throws Exception {
		try(InputStream in = TestPbComposeFallback.class.getResourceAsStream(path)) {
			assertNotNull("resource " + path + " is on the class path", in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/// A prompt section is a string or a list of lines; either way one text.
	private static String joinLines(Object o) {
		if(o instanceof String) {
			return (String) o;
		}
		if(o instanceof List) {
			StringBuilder b = new StringBuilder();
			for(Object line : (List<?>) o) {
				if(line != null) {
					b.append(line.toString());
				}
			}
			return b.toString();
		}
		return null;
	}

	private static String tail(String s) {
		return (s == null ? null : s.substring(Math.max(0, s.length() - 40)));
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	private static BaseRecord bareCharPerson(String name) throws Exception {
		BaseRecord cp = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
		cp.set(FieldNames.FIELD_NAME, name);
		return cp;
	}

	private static BaseRecord composition(PbCompositionSourceEnumType source, boolean userEdited, PbCompositionPurposeEnumType purpose) throws Exception {
		BaseRecord c = RecordFactory.newInstance(OlioModelNames.MODEL_PB_COMPOSITION);
		c.set(OlioFieldNames.FIELD_PB_SOURCE, source.toString());
		c.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.valueOf(userEdited));
		if(purpose != null) {
			c.set(OlioFieldNames.FIELD_PB_PURPOSE, purpose.toString());
		}
		return c;
	}

	private static void assertUnit(String label, double x, double y, double w, double h) {
		assertTrue(label + " x=" + x, x >= 0.0 && x <= 1.0);
		assertTrue(label + " y=" + y, y >= 0.0 && y <= 1.0);
		assertTrue(label + " w=" + w, w > 0.0 && w <= 1.0);
		assertTrue(label + " h=" + h, h > 0.0 && h <= 1.0);
		assertTrue(label + " x+w=" + (x + w), x + w <= 1.0 + 1e-9);
		assertTrue(label + " y+h=" + (y + h), y + h <= 1.0 + 1e-9);
	}

	static String describe(ComposeResult r) {
		return "written=" + r.isWritten() + " source=" + r.source + " tier=" + r.castTier + " skipped=" + r.skippedUserEdited
			+ " fallback=" + r.migratedFallback + " hard=" + r.hardFailure + " actors=" + r.actors.size() + " elements=" + r.elements.size()
			+ " unresolved=" + r.unresolvedNames + " unrecognized=" + r.unrecognizedPlacements + " location=" + r.locationHint
			+ " reason=" + r.failureReason + " warnings=" + r.warnings + " ms=" + r.elapsedMs;
	}

	static String enumWord(BaseRecord rec, String field) {
		Object o = rec.get(field);
		return (o == null ? null : o.toString().toUpperCase());
	}

	static double dbl(BaseRecord rec, String field) {
		Object o = rec.get(field);
		assertNotNull(field + " on " + rec.get(FieldNames.FIELD_NAME), o);
		return ((Number) o).doubleValue();
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

	static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	static String oidOf(BaseRecord rec) {
		assertNotNull("record", rec);
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	static long fkId(BaseRecord rec, String field) {
		Object o = rec.get(field);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}

	/// A record carrying groupId as a bare long, lifted so idOf(groupOf(x)) reads the same for scene and rows.
	static BaseRecord groupOf(BaseRecord rec) throws Exception {
		BaseRecord g = RecordFactory.newInstance(org.cote.accountmanager.schema.ModelNames.MODEL_GROUP);
		Long gid = rec.get(FieldNames.FIELD_GROUP_ID);
		assertNotNull("groupId on " + rec.getSchema() + " " + rec.get(FieldNames.FIELD_NAME), gid);
		g.set(FieldNames.FIELD_ID, gid);
		return g;
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

	/// A scene with the fields the compose step reads; nulls are left unset. The scene is re-read as the user.
	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting, String action, String mood,
			String description, String sdPrompt, Boolean promptLocked) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		List<String> fields = new ArrayList<>();
		Map<String, Object> values = new LinkedHashMap<>();
		put(fields, values, OlioFieldNames.FIELD_PB_SETTING, setting);
		put(fields, values, OlioFieldNames.FIELD_PB_ACTION, action);
		put(fields, values, OlioFieldNames.FIELD_PB_MOOD, mood);
		put(fields, values, FieldNames.FIELD_DESCRIPTION, description);
		put(fields, values, OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
		put(fields, values, OlioFieldNames.FIELD_PB_PROMPT_LOCKED, promptLocked);
		if(!fields.isEmpty()) {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, fields.toArray(new String[0]));
			for(Map.Entry<String, Object> e : values.entrySet()) {
				patch.set(e.getKey(), e.getValue());
			}
			assertNotNull("patch scene " + index, ioContext.getAccessPoint().update(u, patch));
		}
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("scene " + index + " readable by " + u.get(FieldNames.FIELD_NAME), fresh);
		return fresh;
	}

	private static void put(List<String> fields, Map<String, Object> values, String field, Object value) {
		if(value != null) {
			fields.add(field);
			values.put(field, value);
		}
	}

	/// addScene plus a location FK, re-read as the user and checked to carry it (the item link depends on it).
	private BaseRecord addSceneAt(BaseRecord u, BaseRecord bk, int index, String title, String setting, String action, String mood,
			String description, BaseRecord location) throws Exception {
		BaseRecord scene = addScene(u, bk, index, title, setting, action, mood, description, null, null);
		assertNotNull("location for scene " + index, location);
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION);
		patch.set(FieldNames.FIELD_LOCATION, location);
		assertNotNull("patch scene " + index + " location", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("scene " + index + " readable by " + u.get(FieldNames.FIELD_NAME), fresh);
		assertEquals("scene " + index + " carries its location", idOf(location), fkId(fresh, FieldNames.FIELD_LOCATION));
		return fresh;
	}

	/// The store behind the location's POI, read as the user (the compose step reads items from it the same way).
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

	/// An olio.item in the book world's Items group, created as the user.
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

	/// A population member created as the user (the TestPbSceneCharacterRename shape), re-read with its name.
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
