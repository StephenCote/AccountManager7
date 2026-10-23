package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-8: the composition board's row-level edits through {@link PbServiceFacade} (the layer REST and
 * MCP share) on live {@code am7db} as {@code testUser1} in {@code /Development/World Building}. Every case writes
 * a throwaway PictureBook, composes one scene with {@code composeMigrated} (a SCENE composition in the book's
 * group) and proves the outcome on the database with uncached re-reads, never from the facade's return alone:
 * a composition patch bumps the revision, flips {@code userEdited}/{@code source=USER} and marks the scene's DONE
 * node STALE; actor and element rows are added, patched and deleted with the exact 400/404/409 refusals the
 * design names; a focal actor of another composition is refused; rows and compositions of another
 * composition/book are 404; a read-only collaborator is 403 on every write with nothing changed; a row handle
 * suffix is never reused after a middle delete; a non-sparse {@code configOverride} is 400.
 */
public class TestPbCompositionRows extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SECOND_USER = "testUser2";

	private static final String REFUSED_EDIT = "Not authorized to edit the composition in this book";
	private static final String SPARSE_OVERRIDE = "{\"schema\":\"olio.sd.config\",\"steps\":20}";
	private static final String NON_SPARSE_OVERRIDE = "{\"schema\":\"data.group\",\"steps\":20}";

	@FunctionalInterface
	private interface Call {
		void run() throws Exception;
	}

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── composition patch ───────────────────────────────

	/// GET returns the MIGRATED composition; a PATCH of editable fields lands on the DB with revision + 1,
	/// userEdited true, source USER, and the scene's DONE page node is STALE afterwards.
	@Test
	public void getAndPatchCompositionBumpsRevisionAndStalesNode() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "patch");
			Stage st = stage(u, bk, 1);
			BaseRecord wf = workflow(u, bk);
			BaseRecord node = ensurePageNode(u, bk, wf, oidOf(st.scene));
			setStatus(u, node, PbNodeStatusEnumType.DONE, orgId);
			int rev0 = intOf(st.comp.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("precondition: a MIGRATED composition is not user-edited", Boolean.FALSE, st.comp.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals("precondition: source MIGRATED", PbCompositionSourceEnumType.MIGRATED.toString(), status(st.comp, OlioFieldNames.FIELD_PB_SOURCE));

			Map<String, Object> got = PbServiceFacade.getSceneComposition(u, oidOf(bk), oidOf(st.scene));
			assertEquals(oidOf(bk), got.get("bookObjectId"));
			assertEquals(oidOf(st.scene), got.get("sceneObjectId"));
			assertEquals(oidOf(st.comp), got.get("compositionObjectId"));
			@SuppressWarnings("unchecked")
			Map<String, Object> dto = (Map<String, Object>) got.get("composition");
			assertNotNull("composition dto", dto);
			assertEquals(oidOf(st.comp), dto.get("objectId"));
			assertEquals("SCENE", String.valueOf(dto.get("purpose")).toUpperCase());
			assertEquals("MIGRATED", String.valueOf(dto.get("source")).toUpperCase());
			assertEquals(Integer.valueOf(rev0), Integer.valueOf(intOf(dto.get("revision"))));
			assertTrue("dto carries the editable keys", dto.containsKey("props") && dto.containsKey("promptOverride")
				&& dto.containsKey("configOverride") && dto.containsKey("focalActorObjectId"));
			assertTrue("actors list", got.get("actors") instanceof List);
			assertTrue("elements list", got.get("elements") instanceof List);

			Map<String, Object> body = new LinkedHashMap<>();
			body.put(OlioFieldNames.FIELD_PB_MOOD, "foreboding");
			body.put(OlioFieldNames.FIELD_PB_SHOT_TYPE, "wide");
			body.put(OlioFieldNames.FIELD_PB_VIEWPOINT, "LOW");
			body.put(OlioFieldNames.FIELD_PB_CAMERA_TEXT, "a low wide shot up the harbour street");
			body.put(OlioFieldNames.FIELD_PB_PROPS, "a wax-sealed letter, a lantern");
			body.put(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, "rain-dark harbour street at dusk, lantern light on wet cobbles");
			body.put(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.TRUE);
			body.put(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, SPARSE_OVERRIDE);
			Map<String, Object> res = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(st.scene), body);
			log("patch result " + res);
			assertEquals(Boolean.TRUE, res.get("updated"));
			assertEquals(oidOf(st.comp), res.get("compositionObjectId"));
			assertEquals("revision + 1 reported", rev0 + 1, intOf(res.get("revision")));
			assertEquals("the DONE node went STALE", Boolean.TRUE, res.get("nodeStale"));
			assertEquals(PbNodeStatusEnumType.STALE.toString(), String.valueOf(res.get("nodeStatus")).toUpperCase());

			BaseRecord fresh = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			assertNotNull(fresh);
			assertEquals("revision + 1 on the DB", rev0 + 1, intOf(fresh.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals(Boolean.TRUE, fresh.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals(PbCompositionSourceEnumType.USER.toString(), status(fresh, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("foreboding", fresh.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("WIDE", status(fresh, OlioFieldNames.FIELD_PB_SHOT_TYPE));
			assertEquals("LOW", status(fresh, OlioFieldNames.FIELD_PB_VIEWPOINT));
			assertEquals("a low wide shot up the harbour street", fresh.get(OlioFieldNames.FIELD_PB_CAMERA_TEXT));
			assertEquals("a wax-sealed letter, a lantern", fresh.get(OlioFieldNames.FIELD_PB_PROPS));
			assertEquals("rain-dark harbour street at dusk, lantern light on wet cobbles", fresh.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals(Boolean.TRUE, fresh.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(SPARSE_OVERRIDE, fresh.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertNodeStatus(u, node, PbNodeStatusEnumType.STALE, orgId);

			/// A second patch bumps again, and the GET reflects it.
			Map<String, Object> body2 = new LinkedHashMap<>();
			body2.put(OlioFieldNames.FIELD_PB_SETTING_TEXT, "the quay in driving rain");
			body2.put(OlioFieldNames.FIELD_PB_ACTION, "hurrying for the tavern door");
			Map<String, Object> res2 = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(st.scene), body2);
			assertEquals(rev0 + 2, intOf(res2.get("revision")));
			Map<String, Object> got2 = PbServiceFacade.getSceneComposition(u, oidOf(bk), oidOf(st.scene));
			@SuppressWarnings("unchecked")
			Map<String, Object> dto2 = (Map<String, Object>) got2.get("composition");
			assertEquals(rev0 + 2, intOf(dto2.get("revision")));
			assertEquals("USER", String.valueOf(dto2.get("source")).toUpperCase());
			assertEquals(Boolean.TRUE, dto2.get("userEdited"));
			assertEquals("the quay in driving rain", dto2.get("settingText"));
			assertEquals("hurrying for the tavern door", dto2.get("action"));

			/// Empty body and refused keys are 400 and leave the revision alone.
			final BaseRecord book = bk;
			refused("empty patch", 400, "No fields to update", () -> PbServiceFacade.patchSceneComposition(u, oidOf(book), oidOf(st.scene), new LinkedHashMap<>()));
			refused("revision is not patchable", 400, "revision cannot be set here; revision is bumped by every edit",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), oidOf(st.scene), map(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(99))));
			refused("location is not patchable here", 400, "location cannot be set here; point the scene at a location with PUT /scene/{id}/location",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), oidOf(st.scene), map(FieldNames.FIELD_LOCATION, "x")));
			refused("unknown field", 400, "Unknown field colourway",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), oidOf(st.scene), map("colourway", "sepia")));
			refused("enum outside its set", 400, "shotType must be one of",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), oidOf(st.scene), map(OlioFieldNames.FIELD_PB_SHOT_TYPE, "DUTCH")));
			assertEquals("refusals do not bump the revision", rev0 + 2,
				intOf(PbComposeUtil.readComposition(u, oidOf(st.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION)));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── actors ───────────────────────────────

	/// An actor backed by a real olio.charPerson of the book's world (Population group) and an actor with only a
	/// description both land as rows of the composition, named actor.<compOid>.<n>, with the documented defaults.
	@Test
	public void addActorWithCharacterAndWithDescription() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "actors");
			Stage st = stage(u, bk, 1);
			String popPath = st.bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("Population group path", popPath);
			BaseRecord mara = createCharPerson(u, popPath, "Mara Quayle", "Mara", "Quayle");
			int rev0 = intOf(st.comp.get(OlioFieldNames.FIELD_PB_REVISION));
			int before = PbComposeUtil.listActors(u, st.comp).size();

			Map<String, Object> withChar = new LinkedHashMap<>();
			withChar.put("characterObjectId", oidOf(mara));
			withChar.put(OlioFieldNames.FIELD_PB_ROLE, "hero");
			withChar.put(OlioFieldNames.FIELD_PB_POSE, "standing at the tavern door");
			withChar.put(FieldNames.FIELD_EXPRESSION, "wary");
			Map<String, Object> r1 = PbServiceFacade.addActor(u, oidOf(bk), oidOf(st.comp), withChar);
			log("add character actor " + r1);
			assertEquals(Boolean.TRUE, r1.get("created"));
			assertEquals(rev0 + 1, intOf(r1.get("revision")));
			String a1 = (String) r1.get("actorObjectId");
			assertNotNull("actorObjectId", a1);
			@SuppressWarnings("unchecked")
			Map<String, Object> a1dto = (Map<String, Object>) r1.get("actor");
			assertEquals(oidOf(mara), a1dto.get("characterObjectId"));
			assertEquals("Mara Quayle", a1dto.get("characterName"));
			assertEquals("HERO", String.valueOf(a1dto.get("role")).toUpperCase());

			BaseRecord row1 = PbComposeUtil.readActor(u, a1, orgId);
			assertNotNull("character actor on the DB", row1);
			assertEquals("the row belongs to the composition", idOf(st.comp), fkId(row1, OlioFieldNames.FIELD_PB_COMPOSITION));
			assertEquals("the row carries the character", idOf(mara), fkId(row1, OlioFieldNames.FIELD_PB_CHARACTER));
			assertEquals("the row lives in the composition's group", (Object) st.comp.get(FieldNames.FIELD_GROUP_ID), (Object) row1.get(FieldNames.FIELD_GROUP_ID));
			assertEquals(PbComposeUtil.ACTOR_NAME_PREFIX + oidOf(st.comp) + "." + (before + 1), row1.get(FieldNames.FIELD_NAME));
			assertEquals("HERO", status(row1, OlioFieldNames.FIELD_PB_ROLE));
			assertEquals("standing at the tavern door", row1.get(OlioFieldNames.FIELD_PB_POSE));
			assertEquals("wary", row1.get(FieldNames.FIELD_EXPRESSION));
			assertEquals("count defaults to 1", 1, intOf(row1.get(FieldNames.FIELD_COUNT)));
			assertEquals("referencePolicy defaults to AUTO", "AUTO", status(row1, OlioFieldNames.FIELD_PB_REFERENCE_POLICY));

			Map<String, Object> descOnly = new LinkedHashMap<>();
			descOnly.put(FieldNames.FIELD_DESCRIPTION, "a hooded dock-hand hauling rope");
			descOnly.put(OlioFieldNames.FIELD_PB_ROLE, "extra");
			descOnly.put(FieldNames.FIELD_COUNT, Integer.valueOf(3));
			descOnly.put(OlioFieldNames.FIELD_PB_X, Double.valueOf(0.1));
			descOnly.put(OlioFieldNames.FIELD_PB_Y, Double.valueOf(0.2));
			descOnly.put(OlioFieldNames.FIELD_PB_W, Double.valueOf(0.3));
			descOnly.put(OlioFieldNames.FIELD_PB_H, Double.valueOf(0.4));
			Map<String, Object> r2 = PbServiceFacade.addActor(u, oidOf(bk), oidOf(st.comp), descOnly);
			log("add description actor " + r2);
			assertEquals(rev0 + 2, intOf(r2.get("revision")));
			String a2 = (String) r2.get("actorObjectId");
			BaseRecord row2 = PbComposeUtil.readActor(u, a2, orgId);
			assertNotNull("description actor on the DB", row2);
			assertEquals(0L, fkId(row2, OlioFieldNames.FIELD_PB_CHARACTER));
			assertEquals("a hooded dock-hand hauling rope", row2.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals("EXTRA", status(row2, OlioFieldNames.FIELD_PB_ROLE));
			assertEquals(3, intOf(row2.get(FieldNames.FIELD_COUNT)));
			assertEquals(0.1, ((Number) row2.get(OlioFieldNames.FIELD_PB_X)).doubleValue(), 0.0001);
			assertEquals(0.4, ((Number) row2.get(OlioFieldNames.FIELD_PB_H)).doubleValue(), 0.0001);
			assertEquals(PbComposeUtil.ACTOR_NAME_PREFIX + oidOf(st.comp) + "." + (before + 2), row2.get(FieldNames.FIELD_NAME));

			List<BaseRecord> rows = PbComposeUtil.listActors(u, st.comp);
			assertEquals("two rows added", before + 2, rows.size());
			Map<String, Object> got = PbServiceFacade.getSceneComposition(u, oidOf(bk), oidOf(st.scene));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> actors = (List<Map<String, Object>>) got.get("actors");
			Set<String> oids = new HashSet<>();
			for(Map<String, Object> a : actors) {
				oids.add(String.valueOf(a.get("objectId")));
			}
			assertTrue("GET lists both rows", oids.contains(a1) && oids.contains(a2));

			/// A character outside the book's world is 404 and writes nothing.
			final BaseRecord book = bk;
			refused("character not of the book", 404, "Character not found: " + oidOf(bk),
				() -> PbServiceFacade.addActor(u, oidOf(book), oidOf(st.comp), map("characterObjectId", oidOf(book))));
			assertEquals(before + 2, PbComposeUtil.listActors(u, st.comp).size());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// Neither a character nor a description is 400 with the exact message, and nothing is written.
	@Test
	public void addActorWithoutEither400() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "noactor");
			Stage st = stage(u, bk, 1);
			int before = PbComposeUtil.listActors(u, st.comp).size();
			int rev0 = intOf(st.comp.get(OlioFieldNames.FIELD_PB_REVISION));
			final BaseRecord book = bk;
			refused("empty body", 400, "An actor without a character needs a description",
				() -> PbServiceFacade.addActor(u, oidOf(book), oidOf(st.comp), new LinkedHashMap<>()));
			refused("null body", 400, "An actor without a character needs a description",
				() -> PbServiceFacade.addActor(u, oidOf(book), oidOf(st.comp), null));
			refused("blank description", 400, "An actor without a character needs a description",
				() -> PbServiceFacade.addActor(u, oidOf(book), oidOf(st.comp), map(FieldNames.FIELD_DESCRIPTION, "   ")));
			refused("role only", 400, "An actor without a character needs a description",
				() -> PbServiceFacade.addActor(u, oidOf(book), oidOf(st.comp), map(OlioFieldNames.FIELD_PB_ROLE, "hero")));
			refused("unknown field", 400, "Unknown field hat",
				() -> PbServiceFacade.addActor(u, oidOf(book), oidOf(st.comp), map("hat", "tricorn")));
			refused("non-string description", 400, "description must be a string",
				() -> PbServiceFacade.addActor(u, oidOf(book), oidOf(st.comp), map(FieldNames.FIELD_DESCRIPTION, Integer.valueOf(7))));
			assertEquals("nothing written", before, PbComposeUtil.listActors(u, st.comp).size());
			assertEquals("revision untouched", rev0, intOf(PbComposeUtil.readComposition(u, oidOf(st.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION)));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// PATCH actor refuses apparel/apparelObjectId and character/characterObjectId with the documented pointers,
	/// applies editable fields, clamps count, and refuses an out-of-range box value.
	@Test
	public void patchActorRefusesApparelAndCharacter400() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "pactor");
			Stage st = stage(u, bk, 1);
			String a = addDescActor(u, bk, st.comp, "a lamplighter on the quay");
			BaseRecord row0 = PbComposeUtil.readActor(u, a, orgId);
			int rev0 = intOf(PbComposeUtil.readComposition(u, oidOf(st.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION));
			final BaseRecord book = bk;

			refused("apparelObjectId", 400, "apparelObjectId cannot be set here; pin an outfit with PUT .../actor/{id}/apparel",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(st.comp), a, map("apparelObjectId", UUID.randomUUID().toString())));
			refused("apparel", 400, "apparel cannot be set here; pin an outfit with PUT .../actor/{id}/apparel",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(st.comp), a, map(OlioFieldNames.FIELD_APPAREL, UUID.randomUUID().toString())));
			refused("characterObjectId", 400, "characterObjectId cannot be set here; an actor's character is fixed; add a new actor instead",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(st.comp), a, map("characterObjectId", UUID.randomUUID().toString())));
			refused("character", 400, "character cannot be set here; an actor's character is fixed; add a new actor instead",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(st.comp), a, map(OlioFieldNames.FIELD_PB_CHARACTER, UUID.randomUUID().toString())));
			refused("name", 400, "name cannot be set here; an actor's name is its handle",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(st.comp), a, map(FieldNames.FIELD_NAME, "actor.x")));
			refused("empty", 400, "No fields to update",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(st.comp), a, new LinkedHashMap<>()));
			refused("box out of range", 400, "x must be between 0 and 1",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(st.comp), a, map(OlioFieldNames.FIELD_PB_X, Double.valueOf(1.5))));
			refused("description cleared on a character-less actor", 400, "An actor without a character needs a description",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(st.comp), a, map(FieldNames.FIELD_DESCRIPTION, "")));
			refused("blank actor id", 400, "An actor objectId is required",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(st.comp), " ", map(OlioFieldNames.FIELD_PB_POSE, "x")));

			BaseRecord unchanged = PbComposeUtil.readActor(u, a, orgId);
			assertEquals("refusals wrote nothing", (Object) row0.get(FieldNames.FIELD_DESCRIPTION), (Object) unchanged.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals("refusals did not bump the revision", rev0,
				intOf(PbComposeUtil.readComposition(u, oidOf(st.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION)));

			Map<String, Object> body = new LinkedHashMap<>();
			body.put(OlioFieldNames.FIELD_PB_ROLE, "named_background");
			body.put(OlioFieldNames.FIELD_PB_POSE, "reaching up with a taper");
			body.put(FieldNames.FIELD_EXPRESSION, "intent");
			body.put(OlioFieldNames.FIELD_PB_ACTION, "lighting the quay lamps");
			body.put(FieldNames.FIELD_COUNT, Integer.valueOf(PbComposeUtil.MAX_CROWD_COUNT + 50));
			body.put(OlioFieldNames.FIELD_PB_X, Double.valueOf(0.6));
			body.put(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(2));
			body.put(OlioFieldNames.FIELD_PB_PRIORITY, Integer.valueOf(9));
			Map<String, Object> res = PbServiceFacade.patchActor(u, oidOf(bk), oidOf(st.comp), a, body);
			assertEquals(Boolean.TRUE, res.get("updated"));
			assertEquals(a, res.get("actorObjectId"));
			assertEquals(rev0 + 1, intOf(res.get("revision")));
			BaseRecord fresh = PbComposeUtil.readActor(u, a, orgId);
			assertEquals("NAMED_BACKGROUND", status(fresh, OlioFieldNames.FIELD_PB_ROLE));
			assertEquals("reaching up with a taper", fresh.get(OlioFieldNames.FIELD_PB_POSE));
			assertEquals("intent", fresh.get(FieldNames.FIELD_EXPRESSION));
			assertEquals("lighting the quay lamps", fresh.get(OlioFieldNames.FIELD_PB_ACTION));
			assertEquals("count is clamped", PbComposeUtil.MAX_CROWD_COUNT, intOf(fresh.get(FieldNames.FIELD_COUNT)));
			assertEquals(0.6, ((Number) fresh.get(OlioFieldNames.FIELD_PB_X)).doubleValue(), 0.0001);
			assertEquals(2, intOf(fresh.get(OlioFieldNames.FIELD_PB_DEPTH)));
			assertEquals(9, intOf(fresh.get(OlioFieldNames.FIELD_PB_PRIORITY)));
			assertEquals("a lamplighter on the quay", fresh.get(FieldNames.FIELD_DESCRIPTION));
			BaseRecord comp = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			assertEquals(rev0 + 1, intOf(comp.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals(PbCompositionSourceEnumType.USER.toString(), status(comp, OlioFieldNames.FIELD_PB_SOURCE));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// The focal actor is set by objectId through the composition patch; deleting that row clears the FK first
	/// (focalActorCleared true) and the row is gone; deleting a non-focal row reports false.
	@Test
	public void deleteFocalActorClearsFocalActor() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "focal");
			Stage st = stage(u, bk, 1);
			String a1 = addDescActor(u, bk, st.comp, "a cloaked traveller");
			String a2 = addDescActor(u, bk, st.comp, "a tavern keeper in the doorway");

			Map<String, Object> res = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(st.scene), map("focalActorObjectId", a1));
			assertEquals(Boolean.TRUE, res.get("updated"));
			BaseRecord comp = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			BaseRecord a1row = PbComposeUtil.readActor(u, a1, orgId);
			assertEquals("focalActor FK on the DB", idOf(a1row), fkId(comp, OlioFieldNames.FIELD_PB_FOCAL_ACTOR));
			Map<String, Object> got = PbServiceFacade.getSceneComposition(u, oidOf(bk), oidOf(st.scene));
			@SuppressWarnings("unchecked")
			Map<String, Object> dto = (Map<String, Object>) got.get("composition");
			assertEquals(a1, dto.get("focalActorObjectId"));
			int rev1 = intOf(comp.get(OlioFieldNames.FIELD_PB_REVISION));

			Map<String, Object> del2 = PbServiceFacade.deleteActor(u, oidOf(bk), oidOf(st.comp), a2);
			assertEquals(Boolean.TRUE, del2.get("deleted"));
			assertEquals("a non-focal row does not clear the focal actor", Boolean.FALSE, del2.get("focalActorCleared"));
			assertEquals(rev1 + 1, intOf(del2.get("revision")));
			assertNull("row 2 is gone", PbComposeUtil.readActor(u, a2, orgId));
			assertEquals("focal actor still set", idOf(a1row), fkId(PbComposeUtil.readComposition(u, oidOf(st.comp), orgId), OlioFieldNames.FIELD_PB_FOCAL_ACTOR));

			Map<String, Object> del1 = PbServiceFacade.deleteActor(u, oidOf(bk), oidOf(st.comp), a1);
			log("delete focal " + del1);
			assertEquals(Boolean.TRUE, del1.get("deleted"));
			assertEquals(a1, del1.get("actorObjectId"));
			assertEquals("the focal actor was cleared", Boolean.TRUE, del1.get("focalActorCleared"));
			assertEquals(rev1 + 2, intOf(del1.get("revision")));
			assertNull("row 1 is gone", PbComposeUtil.readActor(u, a1, orgId));
			BaseRecord after = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			assertEquals("focalActor FK cleared on the DB", 0L, fkId(after, OlioFieldNames.FIELD_PB_FOCAL_ACTOR));
			assertEquals(rev1 + 2, intOf(after.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertTrue("no actor rows remain", PbComposeUtil.listActors(u, after).isEmpty());
			Map<String, Object> got2 = PbServiceFacade.getSceneComposition(u, oidOf(bk), oidOf(st.scene));
			@SuppressWarnings("unchecked")
			Map<String, Object> dto2 = (Map<String, Object>) got2.get("composition");
			assertNull(dto2.get("focalActorObjectId"));

			final BaseRecord book = bk;
			refused("deleting the deleted row", 404, "Actor " + a1 + " not found in this composition",
				() -> PbServiceFacade.deleteActor(u, oidOf(book), oidOf(st.comp), a1));
			refused("empty patch after the delete", 400, "No fields to update",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), oidOf(st.scene), new LinkedHashMap<>()));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── elements ───────────────────────────────

	/// Element add / patch / delete round trip with the 400s for a missing type or description and refused keys.
	@Test
	public void addPatchDeleteElement() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "elem");
			Stage st = stage(u, bk, 1);
			assertTrue("precondition: no element rows", PbComposeUtil.listElements(u, st.comp).isEmpty());
			int rev0 = intOf(st.comp.get(OlioFieldNames.FIELD_PB_REVISION));
			final BaseRecord book = bk;

			refused("no type", 400, "elementType is required",
				() -> PbServiceFacade.addElement(u, oidOf(book), oidOf(st.comp), map(FieldNames.FIELD_DESCRIPTION, "a lantern")));
			refused("no description", 400, "An element needs a description",
				() -> PbServiceFacade.addElement(u, oidOf(book), oidOf(st.comp), map(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop")));
			refused("itemObjectId refused", 400, "itemObjectId cannot be set here; link a world item with POST .../element/{id}/promote",
				() -> PbServiceFacade.addElement(u, oidOf(book), oidOf(st.comp), map("itemObjectId", "x")));
			refused("bad type", 400, "elementType must be one of",
				() -> PbServiceFacade.addElement(u, oidOf(book), oidOf(st.comp),
					map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "gizmo", FieldNames.FIELD_DESCRIPTION, "a lantern")));
			assertTrue("refusals wrote no rows", PbComposeUtil.listElements(u, st.comp).isEmpty());

			Map<String, Object> body = new LinkedHashMap<>();
			body.put(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop");
			body.put(FieldNames.FIELD_DESCRIPTION, "a brass lantern on a hook");
			body.put(OlioFieldNames.FIELD_PB_IMPORTANCE, "high");
			body.put(OlioFieldNames.FIELD_PB_X, Double.valueOf(0.7));
			body.put(OlioFieldNames.FIELD_PB_Y, Double.valueOf(0.1));
			body.put(OlioFieldNames.FIELD_PB_W, Double.valueOf(0.1));
			body.put(OlioFieldNames.FIELD_PB_H, Double.valueOf(0.2));
			body.put(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(1));
			Map<String, Object> add = PbServiceFacade.addElement(u, oidOf(bk), oidOf(st.comp), body);
			log("add element " + add);
			assertEquals(Boolean.TRUE, add.get("created"));
			assertEquals(rev0 + 1, intOf(add.get("revision")));
			String e1 = (String) add.get("elementObjectId");
			assertNotNull(e1);
			BaseRecord row = PbComposeUtil.readElement(u, e1, orgId);
			assertNotNull("element on the DB", row);
			assertEquals(idOf(st.comp), fkId(row, OlioFieldNames.FIELD_PB_COMPOSITION));
			assertEquals(PbComposeUtil.ELEMENT_NAME_PREFIX + oidOf(st.comp) + ".1", row.get(FieldNames.FIELD_NAME));
			assertEquals("PROP", status(row, OlioFieldNames.FIELD_PB_ELEMENT_TYPE));
			assertEquals("HIGH", status(row, OlioFieldNames.FIELD_PB_IMPORTANCE));
			assertEquals("a brass lantern on a hook", row.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals(0.7, ((Number) row.get(OlioFieldNames.FIELD_PB_X)).doubleValue(), 0.0001);
			assertEquals(1, intOf(row.get(OlioFieldNames.FIELD_PB_DEPTH)));
			assertEquals(0L, fkId(row, OlioFieldNames.FIELD_ITEM));

			Map<String, Object> second = map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "structure", FieldNames.FIELD_DESCRIPTION, "a mooring post");
			Map<String, Object> add2 = PbServiceFacade.addElement(u, oidOf(bk), oidOf(st.comp), second);
			String e2 = (String) add2.get("elementObjectId");
			BaseRecord row2 = PbComposeUtil.readElement(u, e2, orgId);
			assertEquals("importance defaults to MEDIUM", "MEDIUM", status(row2, OlioFieldNames.FIELD_PB_IMPORTANCE));
			assertEquals(PbComposeUtil.ELEMENT_NAME_PREFIX + oidOf(st.comp) + ".2", row2.get(FieldNames.FIELD_NAME));

			refused("patch refuses item", 400, "item cannot be set here; link a world item with POST .../element/{id}/promote",
				() -> PbServiceFacade.patchElement(u, oidOf(book), oidOf(st.comp), e1, map(OlioFieldNames.FIELD_ITEM, "x")));
			refused("patch refuses a cleared description", 400, "An element needs a description",
				() -> PbServiceFacade.patchElement(u, oidOf(book), oidOf(st.comp), e1, map(FieldNames.FIELD_DESCRIPTION, " ")));
			refused("patch refuses an empty body", 400, "No fields to update",
				() -> PbServiceFacade.patchElement(u, oidOf(book), oidOf(st.comp), e1, null));
			refused("patch refuses a non-number depth", 400, "depth must be a number",
				() -> PbServiceFacade.patchElement(u, oidOf(book), oidOf(st.comp), e1, map(OlioFieldNames.FIELD_PB_DEPTH, "deep")));

			Map<String, Object> pbody = new LinkedHashMap<>();
			pbody.put(FieldNames.FIELD_DESCRIPTION, "a brass lantern, lit");
			pbody.put(OlioFieldNames.FIELD_PB_IMPORTANCE, "low");
			pbody.put(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "furniture");
			pbody.put(OlioFieldNames.FIELD_PB_W, Double.valueOf(0.25));
			Map<String, Object> patched = PbServiceFacade.patchElement(u, oidOf(bk), oidOf(st.comp), e1, pbody);
			assertEquals(Boolean.TRUE, patched.get("updated"));
			assertEquals(e1, patched.get("elementObjectId"));
			assertEquals(rev0 + 3, intOf(patched.get("revision")));
			BaseRecord fresh = PbComposeUtil.readElement(u, e1, orgId);
			assertEquals("a brass lantern, lit", fresh.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals("LOW", status(fresh, OlioFieldNames.FIELD_PB_IMPORTANCE));
			assertEquals("FURNITURE", status(fresh, OlioFieldNames.FIELD_PB_ELEMENT_TYPE));
			assertEquals(0.25, ((Number) fresh.get(OlioFieldNames.FIELD_PB_W)).doubleValue(), 0.0001);
			assertEquals("untouched fields keep their value", 0.7, ((Number) fresh.get(OlioFieldNames.FIELD_PB_X)).doubleValue(), 0.0001);

			Map<String, Object> del = PbServiceFacade.deleteElement(u, oidOf(bk), oidOf(st.comp), e1);
			assertEquals(Boolean.TRUE, del.get("deleted"));
			assertEquals(e1, del.get("elementObjectId"));
			assertEquals(rev0 + 4, intOf(del.get("revision")));
			assertNull("element gone from the DB", PbComposeUtil.readElement(u, e1, orgId));
			List<BaseRecord> left = PbComposeUtil.listElements(u, st.comp);
			assertEquals(1, left.size());
			assertEquals(e2, oidOf(left.get(0)));
			refused("deleting again", 404, "Element " + e1 + " not found in this composition",
				() -> PbServiceFacade.deleteElement(u, oidOf(book), oidOf(st.comp), e1));
			Map<String, Object> got = PbServiceFacade.getSceneComposition(u, oidOf(bk), oidOf(st.scene));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> elements = (List<Map<String, Object>>) got.get("elements");
			assertEquals(1, elements.size());
			assertEquals(e2, elements.get(0).get("objectId"));
			assertEquals("STRUCTURE", String.valueOf(elements.get(0).get("elementType")).toUpperCase());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── cross-composition / cross-book ───────────────────────────────

	/// focalActorObjectId naming a row of another composition (same book, another scene) is 400 and the FK stays
	/// clear; an unknown id is the same 400.
	@Test
	public void focalActorOfOtherComposition400() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "focalx");
			Stage one = stage(u, bk, 1);
			Stage two = stage(u, bk, 2);
			assertNotEquals("two compositions", idOf(one.comp), idOf(two.comp));
			String other = addDescActor(u, bk, two.comp, "a stranger of scene two");
			int rev0 = intOf(PbComposeUtil.readComposition(u, oidOf(one.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION));
			final BaseRecord book = bk;
			refused("focal actor of another composition", 400, "focalActor must be an actor of this composition",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), oidOf(one.scene), map("focalActorObjectId", other)));
			refused("focal actor unknown", 400, "focalActor must be an actor of this composition",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), oidOf(one.scene), map("focalActorObjectId", UUID.randomUUID().toString())));
			refused("focalActor by FK name is refused", 400, "focalActor cannot be set here; name the actor as focalActorObjectId",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), oidOf(one.scene), map(OlioFieldNames.FIELD_PB_FOCAL_ACTOR, other)));
			BaseRecord fresh = PbComposeUtil.readComposition(u, oidOf(one.comp), orgId);
			assertEquals("FK stays clear", 0L, fkId(fresh, OlioFieldNames.FIELD_PB_FOCAL_ACTOR));
			assertEquals("revision untouched", rev0, intOf(fresh.get(OlioFieldNames.FIELD_PB_REVISION)));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// An actor/element row of another composition of the same book addressed under this composition is 404
	/// (patch and delete), and the row is untouched.
	@Test
	public void rowOfOtherComposition404() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "rowx");
			Stage one = stage(u, bk, 1);
			Stage two = stage(u, bk, 2);
			String a2 = addDescActor(u, bk, two.comp, "a ferryman of scene two");
			Map<String, Object> el = PbServiceFacade.addElement(u, oidOf(bk), oidOf(two.comp),
				map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop", FieldNames.FIELD_DESCRIPTION, "a coil of rope"));
			String e2 = (String) el.get("elementObjectId");
			int rev1 = intOf(PbComposeUtil.readComposition(u, oidOf(one.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION));
			int rev2 = intOf(PbComposeUtil.readComposition(u, oidOf(two.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION));
			final BaseRecord book = bk;
			String bogus = UUID.randomUUID().toString();

			refused("patch actor of another composition", 404, "Actor " + a2 + " not found in this composition",
				() -> PbServiceFacade.patchActor(u, oidOf(book), oidOf(one.comp), a2, map(OlioFieldNames.FIELD_PB_POSE, "x")));
			refused("delete actor of another composition", 404, "Actor " + a2 + " not found in this composition",
				() -> PbServiceFacade.deleteActor(u, oidOf(book), oidOf(one.comp), a2));
			refused("patch element of another composition", 404, "Element " + e2 + " not found in this composition",
				() -> PbServiceFacade.patchElement(u, oidOf(book), oidOf(one.comp), e2, map(FieldNames.FIELD_DESCRIPTION, "x")));
			refused("delete element of another composition", 404, "Element " + e2 + " not found in this composition",
				() -> PbServiceFacade.deleteElement(u, oidOf(book), oidOf(one.comp), e2));
			refused("unknown actor", 404, "Actor " + bogus + " not found in this composition",
				() -> PbServiceFacade.deleteActor(u, oidOf(book), oidOf(one.comp), bogus));
			refused("unknown element", 404, "Element " + bogus + " not found in this composition",
				() -> PbServiceFacade.patchElement(u, oidOf(book), oidOf(one.comp), bogus, map(FieldNames.FIELD_DESCRIPTION, "x")));
			refused("blank element id", 400, "An element objectId is required",
				() -> PbServiceFacade.deleteElement(u, oidOf(book), oidOf(one.comp), ""));

			assertNotNull("actor still there", PbComposeUtil.readActor(u, a2, orgId));
			assertEquals("a ferryman of scene two", PbComposeUtil.readActor(u, a2, orgId).get(FieldNames.FIELD_DESCRIPTION));
			assertNotNull("element still there", PbComposeUtil.readElement(u, e2, orgId));
			assertEquals("a coil of rope", PbComposeUtil.readElement(u, e2, orgId).get(FieldNames.FIELD_DESCRIPTION));
			assertEquals(rev1, intOf(PbComposeUtil.readComposition(u, oidOf(one.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals(rev2, intOf(PbComposeUtil.readComposition(u, oidOf(two.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION)));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// A composition of another book of the same owner addressed under this book is 404 on every row route; a
	/// scene of another book is 404 on GET/PATCH composition; a composition without a book's scene is 404.
	@Test
	public void compositionOfOtherBook404() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bkA = null;
		BaseRecord bkB = null;
		try {
			bkA = newBook(u, "bookA");
			bkB = newBook(u, "bookB");
			Stage a = stage(u, bkA, 1);
			Stage b = stage(u, bkB, 1);
			String bActor = addDescActor(u, bkB, b.comp, "a figure of book B");
			int revB = intOf(PbComposeUtil.readComposition(u, oidOf(b.comp), orgId).get(OlioFieldNames.FIELD_PB_REVISION));
			final BaseRecord bookA = bkA;
			final String compB = oidOf(b.comp);
			final String sceneB = oidOf(b.scene);
			String bogus = UUID.randomUUID().toString();

			refused("add actor under the wrong book", 404, "Composition " + compB + " not found in this book",
				() -> PbServiceFacade.addActor(u, oidOf(bookA), compB, map(FieldNames.FIELD_DESCRIPTION, "x")));
			refused("patch actor under the wrong book", 404, "Composition " + compB + " not found in this book",
				() -> PbServiceFacade.patchActor(u, oidOf(bookA), compB, bActor, map(OlioFieldNames.FIELD_PB_POSE, "x")));
			refused("delete actor under the wrong book", 404, "Composition " + compB + " not found in this book",
				() -> PbServiceFacade.deleteActor(u, oidOf(bookA), compB, bActor));
			refused("add element under the wrong book", 404, "Composition " + compB + " not found in this book",
				() -> PbServiceFacade.addElement(u, oidOf(bookA), compB, map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop", FieldNames.FIELD_DESCRIPTION, "x")));
			refused("unknown composition", 404, "Composition " + bogus + " not found in this book",
				() -> PbServiceFacade.addElement(u, oidOf(bookA), bogus, map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop", FieldNames.FIELD_DESCRIPTION, "x")));
			refused("blank composition", 400, "A composition objectId is required",
				() -> PbServiceFacade.deleteElement(u, oidOf(bookA), "", bogus));
			refused("GET composition of another book's scene", 404, "Scene " + sceneB + " not found in this book",
				() -> PbServiceFacade.getSceneComposition(u, oidOf(bookA), sceneB));
			refused("PATCH composition of another book's scene", 404, "Scene " + sceneB + " not found in this book",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(bookA), sceneB, map(OlioFieldNames.FIELD_PB_MOOD, "x")));
			refused("unknown book", 404, "Book not found",
				() -> PbServiceFacade.getSceneComposition(u, bogus, sceneB));

			/// A scene of book A with no composition yet is 404 with the "compose it first" pointer.
			BaseRecord bare = addScene(u, bkA, 2, "Bare", "a bare quay", "waiting", "still", "Scene 2 without a composition", null, null);
			refused("scene without a composition", 404, "Scene " + oidOf(bare) + " has no composition; compose it first",
				() -> PbServiceFacade.getSceneComposition(u, oidOf(bookA), oidOf(bare)));

			assertNotNull("book B's row untouched", PbComposeUtil.readActor(u, bActor, orgId));
			assertEquals(revB, intOf(PbComposeUtil.readComposition(u, compB, orgId).get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals("book A's composition untouched", idOf(a.comp), idOf(PbComposeUtil.readComposition(u, oidOf(a.comp), orgId)));
		}
		finally {
			deleteBook(u, bkA);
			deleteBook(u, bkB);
		}
	}

	// ─────────────────────────────── authorization ───────────────────────────────

	/// A collaborator with Read only on the book group (and Read on the world record so the book itself reads)
	/// can GET the board but every write is 403 with the exact message and nothing changes on the DB.
	@Test
	public void readOnlyUserIs403AndWritesNothing() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull(org);
		BaseRecord reader = ioContext.getFactory().getCreateUser(org.getAdminUser(), SECOND_USER, org.getOrganizationId());
		assertNotNull("reader " + SECOND_USER, reader);
		assertNotEquals("reader is a different user from the owner", idOf(u), idOf(reader));
		BaseRecord bk = null;
		try {
			bk = newBook(u, "reader");
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			Stage st = stage(u, bk, 1);
			String actor = addDescActor(u, bk, st.comp, "a gull-watcher on the mole");
			Map<String, Object> el = PbServiceFacade.addElement(u, oidOf(bk), oidOf(st.comp),
				map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop", FieldNames.FIELD_DESCRIPTION, "a weathered bollard"));
			String element = (String) el.get("elementObjectId");
			BaseRecord comp0 = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			int rev0 = intOf(comp0.get(OlioFieldNames.FIELD_PB_REVISION));
			String mood0 = comp0.get(OlioFieldNames.FIELD_PB_MOOD);
			String source0 = status(comp0, OlioFieldNames.FIELD_PB_SOURCE);
			assertEquals("the owner's two seed writes already made the composition USER-sourced", PbCompositionSourceEnumType.USER.toString(), source0);

			/// Fixture grants only (the admin of the book's own organization applies them): Read on the book group
			/// and Read on the world record, so readBook succeeds and canUpdate does not.
			BaseRecord olio = olioUser(orgId);
			BaseRecord bookGroup = ioContext.getPathUtil().findPath(olio, ModelNames.MODEL_GROUP, PbBookUtil.bookGroupPath(slug),
				GroupEnumType.DATA.toString(), orgId);
			assertNotNull("book group", bookGroup);
			ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), reader, new BaseRecord[] { bookGroup },
				new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
			BaseRecord world = st.bctx.getWorld();
			assertNotNull("world record", world);
			ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), reader, new BaseRecord[] { world },
				new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString() });

			BaseRecord asReader = PbBookUtil.readBook(reader, oidOf(bk), orgId);
			assertNotNull("precondition: the reader can READ the book (else the refusal would be a 404, not the 403 under test)", asReader);
			PolicyResponseType canUpd = ioContext.getAuthorizationUtil().canUpdate(reader, reader, asReader);
			assertFalse("precondition: the reader must NOT be permitted to update the book",
				canUpd != null && canUpd.getType() == PolicyResponseEnumType.PERMIT);

			Map<String, Object> got = PbServiceFacade.getSceneComposition(reader, oidOf(bk), oidOf(st.scene));
			assertEquals("the reader can GET the board", oidOf(st.comp), got.get("compositionObjectId"));

			final BaseRecord book = bk;
			final String bookOid = oidOf(bk);
			final String compOid = oidOf(st.comp);
			final String sceneOid = oidOf(st.scene);
			refused("reader patch composition", 403, REFUSED_EDIT,
				() -> PbServiceFacade.patchSceneComposition(reader, bookOid, sceneOid, map(OlioFieldNames.FIELD_PB_MOOD, "stolen")));
			refused("reader add actor", 403, REFUSED_EDIT,
				() -> PbServiceFacade.addActor(reader, bookOid, compOid, map(FieldNames.FIELD_DESCRIPTION, "an intruder")));
			refused("reader patch actor", 403, REFUSED_EDIT,
				() -> PbServiceFacade.patchActor(reader, bookOid, compOid, actor, map(OlioFieldNames.FIELD_PB_POSE, "stolen")));
			refused("reader delete actor", 403, REFUSED_EDIT,
				() -> PbServiceFacade.deleteActor(reader, bookOid, compOid, actor));
			refused("reader add element", 403, REFUSED_EDIT,
				() -> PbServiceFacade.addElement(reader, bookOid, compOid, map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop", FieldNames.FIELD_DESCRIPTION, "x")));
			refused("reader patch element", 403, REFUSED_EDIT,
				() -> PbServiceFacade.patchElement(reader, bookOid, compOid, element, map(FieldNames.FIELD_DESCRIPTION, "stolen")));
			refused("reader delete element", 403, REFUSED_EDIT,
				() -> PbServiceFacade.deleteElement(reader, bookOid, compOid, element));
			assertNotNull(book);

			/// P4-10 mirror paths: a composition patch carrying the mirrored fields is refused at the same
			/// requireBookUpdate gate, before the composition write and before any scene mirror - so neither the
			/// composition nor the scene's sdPrompt/promptLocked/configOverride moves.
			BaseRecord scene0 = readScene(u, sceneOid, orgId);
			assertNull("precondition: the scene has no configOverride", scene0.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertNull("precondition: the scene has no sdPrompt", scene0.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			refused("reader patch composition configOverride (mirror path)", 403, REFUSED_EDIT,
				() -> PbServiceFacade.patchSceneComposition(reader, bookOid, sceneOid, map(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, SPARSE_OVERRIDE)));
			refused("reader patch composition promptOverride/promptLocked (mirror path)", 403, REFUSED_EDIT,
				() -> PbServiceFacade.patchSceneComposition(reader, bookOid, sceneOid,
					map2(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, "stolen prompt", OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.TRUE)));
			/// The scene-level configOverride entry point as the reader: it reads the scene (Read on the group
			/// suffices), finds the SCENE composition and tries to patch it as the reader; the authorized update
			/// is refused and patchCompositionFields reports that as 500 "Failed to persist composition <oid>"
			/// (observed live 2026-10-22). Nothing may land - asserted on the DB below.
			try {
				boolean r = PbBookUtil.setSceneConfigOverride(reader, sceneOid, SPARSE_OVERRIDE);
				fail("a reader's configOverride write must be refused, but returned " + r);
			}
			catch(PictureBookException e) {
				log("reader setSceneConfigOverride: " + e.getStatus() + " " + e.getMessage());
				assertEquals("reader setSceneConfigOverride surfaces the refused composition update as 500: " + e.getMessage(), 500, e.getStatus());
				assertTrue("the 500 names the refused composition persist: " + e.getMessage(),
					e.getMessage() != null && e.getMessage().contains("Failed to persist composition " + compOid));
			}
			BaseRecord scene1 = readScene(u, sceneOid, orgId);
			assertNull("scene.configOverride untouched by the reader", scene1.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertNull("scene.sdPrompt untouched by the reader", scene1.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertNotEquals("scene.promptLocked untouched by the reader", Boolean.TRUE, scene1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

			BaseRecord comp1 = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			assertEquals("revision unchanged", rev0, intOf(comp1.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals("mood unchanged", mood0, comp1.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("source unchanged by the refused calls", source0, status(comp1, OlioFieldNames.FIELD_PB_SOURCE));
			assertNull("composition configOverride untouched by the reader", comp1.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertNull("composition promptOverride untouched by the reader", comp1.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			BaseRecord actorRow = PbComposeUtil.readActor(u, actor, orgId);
			assertNotNull("actor row still there", actorRow);
			assertEquals("a gull-watcher on the mole", actorRow.get(FieldNames.FIELD_DESCRIPTION));
			assertNull("actor pose untouched", actorRow.get(OlioFieldNames.FIELD_PB_POSE));
			BaseRecord elRow = PbComposeUtil.readElement(u, element, orgId);
			assertNotNull("element row still there", elRow);
			assertEquals("a weathered bollard", elRow.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals("one actor row", 1, PbComposeUtil.listActors(u, comp1).size());
			assertEquals("one element row", 1, PbComposeUtil.listElements(u, comp1).size());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── P4-10: composition is the authority, scene is the mirror ───────────────────────────────

	private static final String PAGE_PROMPT = "a lone heron on a tidal flat at first light, mist over the water";
	private static final String PAGE_PROMPT_DIRTY = "line one\r\nline\u0000 two\u0007 end";
	private static final String PAGE_PROMPT_CLEAN = "line one\nline two end";
	private static final String STEPS_17 = "{\"schema\":\"olio.sd.config\",\"steps\":17}";
	private static final String STEPS_33 = "{\"schema\":\"olio.sd.config\",\"steps\":33}";
	private static final String STORY_409 = "belongs to a story book; edit its composition board instead";

	/// A ChapBook page's composition patch through the facade mirrors promptOverride/promptLocked/configOverride
	/// onto the scene's sdPrompt/promptLocked/configOverride (re-read from the DB); a promptOverride carrying NUL,
	/// a C0 control and a CRLF is sanitized on the composition AND on the mirrored scene; a blank promptOverride
	/// clears both; promptLocked false unlocks both; sceneMirrored is true and no sceneMirrorError is reported.
	@Test
	public void pagePromptPatchMirrorsToScene() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = makeChapBook(u, newBook(u, "pagemirror"));
			bookContext(u, bk);
			BaseRecord scene = addScene(u, bk, 1, "Heron", "a tidal flat", "a heron stands", "still",
				"Page 1 of the P4-10 page-mirror fixture", null, null);
			assertNull("precondition: no sdPrompt on the page", scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			BaseRecord page = PbComposeUtil.getCreatePageComposition(u, bk, scene, PbCompositionSourceEnumType.USER);
			assertNotNull("page composition", page);
			assertEquals("CHAPBOOK_PAGE purpose", PbCompositionPurposeEnumType.CHAPBOOK_PAGE.toString(), status(page, OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals("the page composition is the scene's", idOf(scene), fkId(page, OlioFieldNames.FIELD_PB_SCENE));
			int rev0 = intOf(PbComposeUtil.readComposition(u, oidOf(page), orgId).get(OlioFieldNames.FIELD_PB_REVISION));

			/// 1. promptOverride + promptLocked -> scene.sdPrompt + scene.promptLocked
			Map<String, Object> res = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(scene),
				map2(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, PAGE_PROMPT, OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.TRUE));
			log("page prompt patch " + res);
			assertEquals(Boolean.TRUE, res.get("updated"));
			assertEquals("sceneMirrored", Boolean.TRUE, res.get("sceneMirrored"));
			assertFalse("no sceneMirrorError: " + res.get("sceneMirrorError"), res.containsKey("sceneMirrorError"));
			assertEquals(oidOf(page), res.get("compositionObjectId"));
			assertEquals("revision + 1", rev0 + 1, intOf(res.get("revision")));
			BaseRecord comp1 = PbComposeUtil.readComposition(u, oidOf(page), orgId);
			assertEquals(PAGE_PROMPT, comp1.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals(Boolean.TRUE, comp1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			BaseRecord s1 = readScene(u, oidOf(scene), orgId);
			assertEquals("scene.sdPrompt mirrors the composition", PAGE_PROMPT, s1.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals("scene.promptLocked mirrors the composition", Boolean.TRUE, s1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

			/// 2. Dirty text: NUL + BEL stripped, CRLF -> LF, on the composition and on the mirrored scene.
			Map<String, Object> res2 = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(scene),
				map(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, PAGE_PROMPT_DIRTY));
			assertEquals(Boolean.TRUE, res2.get("sceneMirrored"));
			BaseRecord comp2 = PbComposeUtil.readComposition(u, oidOf(page), orgId);
			assertEquals("composition promptOverride sanitized", PAGE_PROMPT_CLEAN, comp2.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals("promptLocked kept (not in the body)", Boolean.TRUE, comp2.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			BaseRecord s2 = readScene(u, oidOf(scene), orgId);
			assertEquals("scene.sdPrompt sanitized", PAGE_PROMPT_CLEAN, s2.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertFalse("no raw CR on the scene", String.valueOf((Object) s2.get(OlioFieldNames.FIELD_CB_SD_PROMPT)).contains("\r"));
			assertEquals("scene.promptLocked kept", Boolean.TRUE, s2.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

			/// 3. configOverride mirrors too (either purpose).
			Map<String, Object> res3 = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(scene),
				map(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, SPARSE_OVERRIDE));
			assertEquals(Boolean.TRUE, res3.get("sceneMirrored"));
			assertEquals(SPARSE_OVERRIDE, PbComposeUtil.readComposition(u, oidOf(page), orgId).get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals("scene.configOverride mirrors the composition", SPARSE_OVERRIDE, readScene(u, oidOf(scene), orgId).get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));

			/// 4. Unlock, then clear: promptLocked false -> scene false; promptOverride "" -> both null.
			Map<String, Object> res4 = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(scene),
				map(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.FALSE));
			assertEquals(Boolean.TRUE, res4.get("sceneMirrored"));
			BaseRecord s4 = readScene(u, oidOf(scene), orgId);
			assertEquals("scene unlocked", Boolean.FALSE, s4.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals("scene.sdPrompt kept when only the lock moved", PAGE_PROMPT_CLEAN, s4.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			Map<String, Object> res5 = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(scene),
				map(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, ""));
			assertEquals(Boolean.TRUE, res5.get("sceneMirrored"));
			assertNull("composition promptOverride cleared", PbComposeUtil.readComposition(u, oidOf(page), orgId).get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertNull("scene.sdPrompt cleared with it", readScene(u, oidOf(scene), orgId).get(OlioFieldNames.FIELD_CB_SD_PROMPT));

			/// 5. A patch that carries none of the mirrored keys still reports sceneMirrored true (nothing to mirror).
			Map<String, Object> res6 = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(scene), map(OlioFieldNames.FIELD_PB_MOOD, "hushed"));
			assertEquals(Boolean.TRUE, res6.get("sceneMirrored"));
			assertFalse(res6.containsKey("sceneMirrorError"));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// A STORY scene's SCENE composition promptOverride/promptLocked is a board-level direction, never the scene
	/// text: a patch of them leaves scene.sdPrompt/promptLocked exactly as created (a legacy prompt, locked)
	/// while configOverride in the same body DOES mirror onto the scene. The same holds for the scene-level
	/// configOverride entry point, which writes the SCENE composition first and mirrors it back.
	@Test
	public void storyCompositionPromptPatchDoesNotMirrorToScene() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "storymirror");
			bookContext(u, bk);
			String legacy = "legacy locked prompt of the story scene";
			BaseRecord scene = addScene(u, bk, 1, "Arrival", "a rain-dark harbour street at dusk", "walking toward the tavern door", "wary",
				"Scene 1 of the P4-10 story-mirror fixture", legacy, Boolean.TRUE);
			assertEquals("precondition: legacy sdPrompt on the scene", legacy, scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals("precondition: locked", Boolean.TRUE, scene.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			ComposeResult cr = PbComposeUtil.composeMigrated(u, bk, scene, false);
			assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
			BaseRecord comp = PbComposeUtil.readComposition(u, oidOf(cr.composition), orgId);
			assertEquals("SCENE purpose", "SCENE", status(comp, OlioFieldNames.FIELD_PB_PURPOSE));
			assertFalse("the book is a story book", PbNodeExecutor.isChapBook(PbBookUtil.readBook(u, oidOf(bk), orgId)));

			Map<String, Object> body = new LinkedHashMap<>();
			body.put(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, "board direction: low wide shot, lantern light only");
			body.put(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.FALSE);
			body.put(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, STEPS_17);
			Map<String, Object> res = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(scene), body);
			log("story patch " + res);
			assertEquals(Boolean.TRUE, res.get("updated"));
			assertEquals("sceneMirrored (configOverride mirrored; prompt not a mirror on a story scene)", Boolean.TRUE, res.get("sceneMirrored"));
			assertFalse(res.containsKey("sceneMirrorError"));
			BaseRecord comp1 = PbComposeUtil.readComposition(u, oidOf(comp), orgId);
			assertEquals("board direction: low wide shot, lantern light only", comp1.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals(Boolean.FALSE, comp1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(STEPS_17, comp1.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			BaseRecord s1 = readScene(u, oidOf(scene), orgId);
			assertEquals("story scene.sdPrompt untouched by a composition promptOverride", legacy, s1.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals("story scene.promptLocked untouched by a composition promptLocked", Boolean.TRUE, s1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals("story scene.configOverride IS mirrored", STEPS_17, s1.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));

			/// Scene-level entry point on the story scene: composition first, scene mirrored, revision bumped.
			int rev1 = intOf(comp1.get(OlioFieldNames.FIELD_PB_REVISION));
			assertTrue("setSceneConfigOverride", PbBookUtil.setSceneConfigOverride(u, oidOf(scene), STEPS_33));
			BaseRecord comp2 = PbComposeUtil.readComposition(u, oidOf(comp), orgId);
			assertEquals("the SCENE composition carries the new override", STEPS_33, comp2.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals("touchEdited bumped the revision", rev1 + 1, intOf(comp2.get(OlioFieldNames.FIELD_PB_REVISION)));
			BaseRecord s2 = readScene(u, oidOf(scene), orgId);
			assertEquals("scene.configOverride mirrored from the composition", STEPS_33, s2.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals("scene.sdPrompt still untouched", legacy, s2.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertTrue("clear through the scene entry point", PbBookUtil.setSceneConfigOverride(u, oidOf(scene), ""));
			assertNull("composition override cleared", PbComposeUtil.readComposition(u, oidOf(comp), orgId).get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertNull("scene override cleared with it", readScene(u, oidOf(scene), orgId).get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// The composition's configOverride reaches the effective config as the node tier through the scene->node
	/// mirror: with a composition override of steps 17 and a client overlay of steps 33, the effective config is
	/// 17 (node tier above the overlay); with the composition override cleared and the node re-mirrored, the
	/// overlay's 33 wins over the book tier; a scene-only override (no composition value) also reaches the node.
	@Test
	public void configOverrideTierSitsBetweenOverlayAndNode() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "cfgtier");
			Stage st = stage(u, bk, 1);
			BaseRecord wf = workflow(u, bk);
			BaseRecord node = ensurePageNode(u, bk, wf, oidOf(st.scene));
			assertNull("precondition: node has no override", node.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			BaseRecord book = PbBookUtil.readBook(u, oidOf(bk), orgId);
			BaseRecord overlay = PbConfigUtil.parseOverride(STEPS_33);
			assertNotNull("overlay parses", overlay);
			int bookSteps = intOf(PbConfigUtil.resolveEffectiveConfig(book, node, false).get("steps"));
			log("book-tier steps=" + bookSteps);
			assertNotEquals("fixture: the book tier must not already be 17", 17, bookSteps);
			assertNotEquals("fixture: the book tier must not already be 33", 33, bookSteps);
			assertEquals("overlay beats the book tier when the node has no override", 33,
				intOf(PbConfigUtil.resolveEffectiveConfig(book, node, false, overlay).get("steps")));

			/// Composition override -> scene mirror -> node mirror -> node tier beats the overlay.
			Map<String, Object> res = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(st.scene), map(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, STEPS_17));
			assertEquals(Boolean.TRUE, res.get("sceneMirrored"));
			BaseRecord sceneA = readScene(u, oidOf(st.scene), orgId);
			assertEquals("scene mirror", STEPS_17, sceneA.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertTrue("mirrorSceneOntoNode", PbBookUtil.mirrorSceneOntoNode(u, sceneA, node, book));
			BaseRecord nodeA = PbGraphUtil.readNode(u, oidOf(node), orgId);
			assertEquals("node carries the composition's override", STEPS_17, nodeA.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals("node tier (composition) beats the client overlay", 17,
				intOf(PbConfigUtil.resolveEffectiveConfig(book, nodeA, false, overlay).get("steps")));
			assertEquals("node tier beats the book tier without an overlay", 17,
				intOf(PbConfigUtil.resolveEffectiveConfig(book, nodeA, false).get("steps")));

			/// Composition cleared -> scene cleared -> node cleared -> overlay wins again.
			Map<String, Object> res2 = PbServiceFacade.patchSceneComposition(u, oidOf(bk), oidOf(st.scene), map(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, ""));
			assertEquals(Boolean.TRUE, res2.get("sceneMirrored"));
			BaseRecord sceneB = readScene(u, oidOf(st.scene), orgId);
			assertNull("scene mirror cleared", sceneB.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertTrue("mirrorSceneOntoNode (clear)", PbBookUtil.mirrorSceneOntoNode(u, sceneB, nodeA, book));
			BaseRecord nodeB = PbGraphUtil.readNode(u, oidOf(node), orgId);
			assertNull("node override cleared", nodeB.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals("overlay beats the book tier again", 33,
				intOf(PbConfigUtil.resolveEffectiveConfig(book, nodeB, false, overlay).get("steps")));
			assertEquals("book tier alone", bookSteps,
				intOf(PbConfigUtil.resolveEffectiveConfig(book, nodeB, false).get("steps")));

			/// A scene-only override (patched directly on the scene; the composition stays blank) still reaches
			/// the node - the mirror falls back to the scene's value when the composition carries none.
			BaseRecord patch = PbGraphUtil.patchOf(sceneB, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE);
			patch.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, SPARSE_OVERRIDE);
			assertNotNull("scene-only override patch", ioContext.getAccessPoint().update(u, patch));
			BaseRecord sceneC = readScene(u, oidOf(st.scene), orgId);
			assertEquals(SPARSE_OVERRIDE, sceneC.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertNull("composition still blank", PbComposeUtil.readComposition(u, oidOf(st.comp), orgId).get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertTrue("mirrorSceneOntoNode (scene-only)", PbBookUtil.mirrorSceneOntoNode(u, sceneC, nodeB, book));
			BaseRecord nodeC = PbGraphUtil.readNode(u, oidOf(node), orgId);
			assertEquals("node carries the scene-only override", SPARSE_OVERRIDE, nodeC.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals("scene-only override (steps 20) beats the overlay", 20,
				intOf(PbConfigUtil.resolveEffectiveConfig(book, nodeC, false, overlay).get("steps")));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// The page-prompt entry points refuse a STORY scene with 409 and the exact message, BEFORE any write and
	/// (for the regenerate route) BEFORE the LLM is called: the scene's sdPrompt is unchanged, no CHAPBOOK_PAGE
	/// composition is created, and the SCENE composition's revision is unchanged.
	@Test
	public void storyScenePromptRouteIs409() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "story409");
			Stage st = stage(u, bk, 1);
			assertFalse("the book is a story book", PbNodeExecutor.isChapBook(PbBookUtil.readBook(u, oidOf(bk), orgId)));
			long groupId = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();
			int rev0 = intOf(st.comp.get(OlioFieldNames.FIELD_PB_REVISION));
			assertNull("precondition: no page composition", PbComposeUtil.findSceneComposition(u, st.scene, groupId, orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE));
			assertNull("precondition: no sdPrompt", st.scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			final String sceneOid = oidOf(st.scene);

			refused("set page prompt on a story scene", 409, STORY_409,
				() -> ChapBookUtil.setSceneLandscapePromptResult(u, sceneOid, "a prompt that must not land"));
			refused("setSceneLandscapePrompt (throwing form) on a story scene", 409, STORY_409,
				() -> ChapBookUtil.setSceneLandscapePrompt(u, sceneOid, "a prompt that must not land"));
			/// An unsaved, never-dispatched chatConfig record: non-null gets past the 503 null check, so the 409
			/// proves the story-book gate fires before the LLM step (which would need a connection and a model).
			BaseRecord chatConfig = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG);
			refused("regenerate page prompt on a story scene", 409, STORY_409,
				() -> ChapBookUtil.regenerateSceneLandscapePromptResult(u, sceneOid, chatConfig));

			BaseRecord s1 = readScene(u, sceneOid, orgId);
			assertNull("scene.sdPrompt still null", s1.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertNotEquals("scene not locked", Boolean.TRUE, s1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertNull("no page composition was created", PbComposeUtil.findSceneComposition(u, s1, groupId, orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE));
			BaseRecord comp1 = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			assertEquals("SCENE composition revision unchanged", rev0, intOf(comp1.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertNull("SCENE composition promptOverride unchanged", comp1.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// Turn a freshly created STORY book into a CHAPBOOK as its owner (TestPbComposeJob.makeChapBook's idiom) and
	/// re-read it so the record carries the type.
	private BaseRecord makeChapBook(BaseRecord u, BaseRecord bk) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(bk, OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_BOOK_TYPE);
		patch.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.CHAPBOOK.toString());
		assertNotNull("bookType patch", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readBook(u, oidOf(bk), orgOf(u));
		assertNotNull("re-read book", fresh);
		assertTrue("the book reads back as a ChapBook", PbNodeExecutor.isChapBook(fresh));
		return fresh;
	}

	// ─────────────────────────────── handles ───────────────────────────────

	/// Three actors are .1 .2 .3; deleting .2 and adding a fourth yields .4 (max + 1), never .2 again; the same
	/// holds for elements, and the pure nextHandleIndex agrees.
	@Test
	public void handleSuffixSurvivesMiddleDelete() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "handle");
			Stage st = stage(u, bk, 1);
			assertTrue("precondition: no actor rows (composeMigrated on a cast-less scene)", PbComposeUtil.listActors(u, st.comp).isEmpty());
			String prefix = PbComposeUtil.ACTOR_NAME_PREFIX + oidOf(st.comp) + ".";
			String a1 = addDescActor(u, bk, st.comp, "first");
			String a2 = addDescActor(u, bk, st.comp, "second");
			String a3 = addDescActor(u, bk, st.comp, "third");
			assertEquals(prefix + "1", PbComposeUtil.readActor(u, a1, orgId).get(FieldNames.FIELD_NAME));
			assertEquals(prefix + "2", PbComposeUtil.readActor(u, a2, orgId).get(FieldNames.FIELD_NAME));
			assertEquals(prefix + "3", PbComposeUtil.readActor(u, a3, orgId).get(FieldNames.FIELD_NAME));

			Map<String, Object> del = PbServiceFacade.deleteActor(u, oidOf(bk), oidOf(st.comp), a2);
			assertEquals(Boolean.TRUE, del.get("deleted"));
			assertNull(PbComposeUtil.readActor(u, a2, orgId));
			List<BaseRecord> after = PbComposeUtil.listActors(u, st.comp);
			assertEquals(2, after.size());
			assertEquals("pure nextHandleIndex is max + 1", 4, PbComposeUtil.nextHandleIndex(after));

			String a4 = addDescActor(u, bk, st.comp, "fourth");
			assertEquals("the fourth row is .4, the freed .2 is never reused", prefix + "4", PbComposeUtil.readActor(u, a4, orgId).get(FieldNames.FIELD_NAME));
			Set<String> names = new HashSet<>();
			for(BaseRecord r : PbComposeUtil.listActors(u, st.comp)) {
				names.add(r.get(FieldNames.FIELD_NAME));
			}
			assertEquals(new HashSet<>(Arrays.asList(prefix + "1", prefix + "3", prefix + "4")), names);

			/// Delete the tail too: max + 1 still counts from what remains.
			PbServiceFacade.deleteActor(u, oidOf(bk), oidOf(st.comp), a4);
			String a5 = addDescActor(u, bk, st.comp, "fifth");
			assertEquals(prefix + "4", PbComposeUtil.readActor(u, a5, orgId).get(FieldNames.FIELD_NAME));

			/// Elements count independently of actors.
			String ePrefix = PbComposeUtil.ELEMENT_NAME_PREFIX + oidOf(st.comp) + ".";
			String e1 = (String) PbServiceFacade.addElement(u, oidOf(bk), oidOf(st.comp),
				map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop", FieldNames.FIELD_DESCRIPTION, "e one")).get("elementObjectId");
			String e2 = (String) PbServiceFacade.addElement(u, oidOf(bk), oidOf(st.comp),
				map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop", FieldNames.FIELD_DESCRIPTION, "e two")).get("elementObjectId");
			assertEquals(ePrefix + "1", PbComposeUtil.readElement(u, e1, orgId).get(FieldNames.FIELD_NAME));
			assertEquals(ePrefix + "2", PbComposeUtil.readElement(u, e2, orgId).get(FieldNames.FIELD_NAME));
			PbServiceFacade.deleteElement(u, oidOf(bk), oidOf(st.comp), e1);
			String e3 = (String) PbServiceFacade.addElement(u, oidOf(bk), oidOf(st.comp),
				map2(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop", FieldNames.FIELD_DESCRIPTION, "e three")).get("elementObjectId");
			assertEquals(ePrefix + "3", PbComposeUtil.readElement(u, e3, orgId).get(FieldNames.FIELD_NAME));

			assertEquals("an empty list starts at 1", 1, PbComposeUtil.nextHandleIndex(new ArrayList<>()));
			assertEquals("a null list starts at 1", 1, PbComposeUtil.nextHandleIndex(null));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── configOverride ───────────────────────────────

	/// configOverride must be sparse olio.sd.config JSON carrying its schema: another schema, no schema, or
	/// non-JSON are 400 and the column stays untouched; a sparse olio.sd.config lands; null clears.
	@Test
	public void configOverrideRejectsNonSparse400() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "cfg");
			Stage st = stage(u, bk, 1);
			final BaseRecord book = bk;
			final String sceneOid = oidOf(st.scene);
			int rev0 = intOf(st.comp.get(OlioFieldNames.FIELD_PB_REVISION));
			String expected = "configOverride must be sparse olio.sd.config JSON carrying its schema";
			refused("other schema", 400, expected,
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), sceneOid, map(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, NON_SPARSE_OVERRIDE)));
			refused("no schema", 400, expected,
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), sceneOid, map(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, "{\"steps\":20}")));
			refused("not JSON", 400, expected,
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), sceneOid, map(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, "steps=20")));
			refused("not a string", 400, "configOverride must be a string",
				() -> PbServiceFacade.patchSceneComposition(u, oidOf(book), sceneOid, map(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, Integer.valueOf(20))));
			BaseRecord fresh = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			assertNull("configOverride untouched by refusals", fresh.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals("revision untouched by refusals", rev0, intOf(fresh.get(OlioFieldNames.FIELD_PB_REVISION)));

			Map<String, Object> ok = PbServiceFacade.patchSceneComposition(u, oidOf(bk), sceneOid, map(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, SPARSE_OVERRIDE));
			assertEquals(rev0 + 1, intOf(ok.get("revision")));
			fresh = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			assertEquals(SPARSE_OVERRIDE, fresh.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertNotNull("the stored override parses as a sparse sd.config", PbConfigUtil.parseOverride(fresh.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE)));

			Map<String, Object> clear = new LinkedHashMap<>();
			clear.put(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, null);
			Map<String, Object> cleared = PbServiceFacade.patchSceneComposition(u, oidOf(bk), sceneOid, clear);
			assertEquals(rev0 + 2, intOf(cleared.get("revision")));
			fresh = PbComposeUtil.readComposition(u, oidOf(st.comp), orgId);
			assertNull("null clears the override", fresh.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── stage: scene + composition ───────────────────────────────

	private static final class Stage {
		BookContext bctx;
		BaseRecord scene;
		BaseRecord comp;
	}

	/// A scene of the book and its MIGRATED composition re-read uncached (in the book's group, which is what the
	/// facade's findCompositionOfScene keys on).
	private Stage stage(BaseRecord u, BaseRecord bk, int index) throws Exception {
		long orgId = orgOf(u);
		Stage st = new Stage();
		st.bctx = bookContext(u, bk);
		st.scene = addScene(u, bk, index, "Arrival", "a rain-dark harbour street at dusk", "walking toward the tavern door", "wary",
			"Scene " + index + " of the P4-8 composition-rows fixture", null, null);
		ComposeResult cr = PbComposeUtil.composeMigrated(u, bk, st.scene, false);
		assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
		st.comp = PbComposeUtil.readComposition(u, oidOf(cr.composition), orgId);
		assertNotNull("composition re-read", st.comp);
		assertEquals("the composition belongs to the book", idOf(bk), fkId(st.comp, OlioFieldNames.FIELD_PB_BOOK));
		assertEquals("the composition is in the book's group", (Object) bk.get(FieldNames.FIELD_GROUP_ID), (Object) st.comp.get(FieldNames.FIELD_GROUP_ID));
		assertEquals("the composition is the scene's", idOf(st.scene), fkId(st.comp, OlioFieldNames.FIELD_PB_SCENE));
		assertEquals("SCENE purpose", "SCENE", status(st.comp, OlioFieldNames.FIELD_PB_PURPOSE));
		return st;
	}

	/// A description-only actor row through the facade, returning its objectId.
	private String addDescActor(BaseRecord u, BaseRecord bk, BaseRecord comp, String description) {
		Map<String, Object> res = PbServiceFacade.addActor(u, oidOf(bk), oidOf(comp), map(FieldNames.FIELD_DESCRIPTION, description));
		assertEquals(Boolean.TRUE, res.get("created"));
		String oid = (String) res.get("actorObjectId");
		assertNotNull("actorObjectId for '" + description + "'", oid);
		return oid;
	}

	/// A population member created as the user (the TestPbComposeFallback shape).
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

	private void refused(String what, int status, String contains, Call c) throws Exception {
		try {
			c.run();
		}
		catch(PictureBookException e) {
			log(what + ": " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": status (" + e.getMessage() + ")", status, e.getStatus());
			assertNotNull(what + ": message", e.getMessage());
			assertTrue(what + ": message must contain '" + contains + "' but was: " + e.getMessage(), e.getMessage().contains(contains));
			return;
		}
		fail(what + ": expected PictureBookException " + status + " but nothing was thrown");
	}

	// ─────────────────────────────── graph / nodes (TestPbPromoteElement's shape) ───────────────────────────────

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
		assertNotNull("workflow", wf);
		return wf;
	}

	private BaseRecord ensurePageNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String sceneOid) throws Exception {
		long orgId = orgOf(u);
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = readScene(u, sceneOid, orgId);
		long linked = fkId(scene, OlioFieldNames.FIELD_PB_SCENE_NODE);
		if(linked > 0L) {
			BaseRecord existing = PbGraphUtil.readNodeById(u, linked, orgId);
			assertNotNull("linked page node", existing);
			return existing;
		}
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int idx = (sceneIndex != null ? sceneIndex.intValue() : 0);
		String handle = PbPipelineUtil.landscapeHandle(sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(u, wf, handle);
		if(node == null) {
			node = PbGraphUtil.addNode(u, wf, handle, PbNodeTypeEnumType.LANDSCAPE, PbBookUtil.workflowGroupPath(slug), idx);
			assertNotNull("page node " + handle, node);
			assertTrue("page node scope patch", PbPipelineUtil.patchNodeScope(u, wf, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx));
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
		patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, node);
		assertNotNull("scene.sceneNode patch", IOSystem.getActiveContext().getAccessPoint().update(u, patch));
		BaseRecord back = readScene(u, sceneOid, orgId);
		assertEquals("the page links its node", idOf(node), fkId(back, OlioFieldNames.FIELD_PB_SCENE_NODE));
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgId);
		assertNotNull("page node re-read", fresh);
		return fresh;
	}

	private BaseRecord setStatus(BaseRecord u, BaseRecord node, PbNodeStatusEnumType to, long orgId) {
		assertTrue("persistStatus " + to, PbGraphUtil.persistStatus(u, node, to));
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgId);
		assertNotNull("node re-read", fresh);
		assertEquals("precondition: node is " + to, to.toString(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return fresh;
	}

	private void assertNodeStatus(BaseRecord u, BaseRecord node, PbNodeStatusEnumType expected, long orgId) {
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgId);
		assertNotNull("node re-read", fresh);
		assertEquals("node " + fresh.get(FieldNames.FIELD_NAME) + " status", expected.toString(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
	}

	private static String status(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return (v == null ? null : String.valueOf(v).toUpperCase());
	}

	private BaseRecord readScene(BaseRecord u, String oid, long orgId) {
		BaseRecord s = PbBookUtil.readScene(u, oid, orgId);
		assertNotNull("scene " + oid, s);
		return s;
	}

	// ─────────────────────────────── book / scene (TestPbComposeFallback's shape) ───────────────────────────────

	private BookContext bookContext(BaseRecord u, BaseRecord bk) {
		BookContext bctx = PbBookUtil.openBookContext(u, bk);
		assertNotNull("book context", bctx);
		assertNotNull("book world", bctx.getWorld());
		return bctx;
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

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = ("p48-" + tag + "-" + shortId()).toLowerCase();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-8 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		log("book " + slug + " " + oidOf(bk));
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			log("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup " + bk.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
		}
	}

	// ─────────────────────────────── small helpers ───────────────────────────────

	private static Map<String, Object> map(String k, Object v) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(k, v);
		return m;
	}

	private static Map<String, Object> map2(String k1, Object v1, String k2, Object v2) {
		Map<String, Object> m = map(k1, v1);
		m.put(k2, v2);
		return m;
	}

	private static int intOf(Object o) {
		assertTrue("a number but was " + o, o instanceof Number);
		return ((Number) o).intValue();
	}

	private void log(String msg) {
		logger.info("[p48] " + msg);
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

	private BaseRecord olioUser(long orgId) {
		BaseRecord olio = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal in organization " + orgId, olio);
		return olio;
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
		Object o = (rec != null && rec.hasField(field) ? rec.get(field) : null);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}
}
