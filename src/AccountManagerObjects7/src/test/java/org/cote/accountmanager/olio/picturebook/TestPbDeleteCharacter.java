package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/// Character extraction sometimes yields an animal or an expression as a "character". The manage-characters
/// view needs a way to delete such an extract AND detach it from every scene that references it, in all four
/// places a scene<->character link is persisted: the scene note's text JSON (by name), the .pictureBookMeta
/// scenes[].characters objectId lists, the PB2 graph (ref bindings to the charPerson, character-scoped nodes
/// with their edges and produced artifacts), and finally the charPerson record itself with its sub-records.
/// Real DB, no LLM, non-admin users throughout.
public class TestPbDeleteCharacter extends BaseTest {

	private static final String PB1_BOOKS_ROOT = "~/Data/PictureBooks/";

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	@Before
	public void setupEach() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private static BaseRecord rawRowBySlug(IOContext ioContext, String slug, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SLUG, slug);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID});
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	private static String populationPath(OlioContext ctx, long orgId) {
		BaseRecord world = ctx.getWorld();
		String popPath = world.get("population.path");
		if(popPath == null || popPath.isBlank()) {
			String worldOid = world.get(FieldNames.FIELD_OBJECT_ID);
			Query wq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_OBJECT_ID, worldOid);
			wq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			wq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "population.path"});
			wq.setCache(false);
			BaseRecord full = IOSystem.getActiveContext().getSearch().findRecord(wq);
			assertNotNull("world re-read for population path", full);
			popPath = full.get("population.path");
		}
		return popPath;
	}

	private static String createCharPersonIn(BaseRecord user, String groupPath, String name, String gender) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set("gender", gender);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("create charPerson " + name, created);
		return created.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static BaseRecord createNote(BaseRecord user, String groupPath, String name, String text) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set("text", text);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, note);
		assertNotNull("create note " + name, created);
		return created;
	}

	private static BaseRecord readNote(BaseRecord user, String groupPath, String name, long orgId) {
		BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
			ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), orgId);
		if(grp == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private static Map<String, Object> noteJson(BaseRecord note) {
		String text = note.get("text");
		assertNotNull("note text", text);
		return JSONUtil.getMap(text.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
	}

	private static Set<String> characterOids(List<Map<String, Object>> chars) {
		Set<String> out = new HashSet<>();
		for(Map<String, Object> c : chars) {
			out.add((String) c.get("objectId"));
		}
		return out;
	}

	private static Set<String> objectIds(List<BaseRecord> recs) {
		Set<String> out = new HashSet<>();
		for(BaseRecord r : recs) {
			out.add((String) r.get(FieldNames.FIELD_OBJECT_ID));
		}
		return out;
	}

	private static void deleteQuietly(BaseRecord user, BaseRecord rec, String label) {
		if(rec == null) {
			return;
		}
		try {
			if(!IOSystem.getActiveContext().getAccessPoint().delete(user, rec)) {
				logger.warn("cleanup: could not delete " + label);
			}
		}
		catch(Exception e) {
			logger.warn("cleanup: could not delete " + label + ": " + e.getMessage());
		}
	}

	@Test
	public void testDeleteCharacterDetachesScenesMetaAndGraph() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord owner = getCreateUser("pbDelCharOwner");
		BaseRecord stranger = getCreateUser("pbDelCharStranger");
		assertNotNull("owner", owner);
		assertNotNull("stranger", stranger);
		assertFalse("owner and stranger must be different users",
			((Long) owner.get(FieldNames.FIELD_ID)).equals((Long) stranger.get(FieldNames.FIELD_ID)));
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);

		String id = shortId();
		String slug = "dc" + id;
		String sighName = "Sigh " + id;
		String annaName = "Anna " + id;
		BaseRecord olioUser = null;
		BaseRecord bookGroup = null;
		BaseRecord scenesGroup = null;
		BaseRecord sceneNote = null;
		BaseRecord metaNote = null;
		try {
			// --- Fixture: a PB2 book with two characters in its world Population group.
			BaseRecord book = PbBookUtil.createBook(owner, dataPath, slug, "DelChar " + slug);
			assertNotNull("createBook", book);
			String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
			olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			assertNotNull("olio principal", olioUser);

			OlioContext ctx = PbOlioContextUtil.getCreateBookContext(owner, dataPath, slug);
			assertNotNull("book olio context", ctx);
			String popPath = populationPath(ctx, orgId);
			assertNotNull("population path", popPath);

			String sighOid = createCharPersonIn(owner, popPath, sighName, "FEMALE");
			String annaOid = createCharPersonIn(owner, popPath, annaName, "FEMALE");

			// The legacy (PB1) book group is how a pb.book objectId resolves to a group the caller owns;
			// scene notes and the meta note live under it exactly as the extractor writes them.
			bookGroup = ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP,
				PB1_BOOKS_ROOT + slug, GroupEnumType.DATA.toString(), orgId);
			assertNotNull("PB1 book group", bookGroup);
			String bookGroupPath = bookGroup.get(FieldNames.FIELD_PATH);
			scenesGroup = ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP,
				bookGroupPath + "/Scenes", GroupEnumType.DATA.toString(), orgId);
			assertNotNull("Scenes group", scenesGroup);

			Map<String, Object> sceneText = new LinkedHashMap<>();
			sceneText.put("title", "S1");
			List<Object> sceneChars = new ArrayList<>();
			Map<String, Object> c1 = new LinkedHashMap<>();
			c1.put("name", sighName);
			c1.put("role", "extract");
			Map<String, Object> c2 = new LinkedHashMap<>();
			c2.put("name", annaName);
			c2.put("role", "lead");
			sceneChars.add(c1);
			sceneChars.add(c2);
			sceneText.put("characters", sceneChars);
			sceneNote = createNote(owner, bookGroupPath + "/Scenes", "Scene 1", JSONUtil.exportObject(sceneText));

			Map<String, Object> meta = new LinkedHashMap<>();
			meta.put("pb2BookObjectId", bookOid);
			Map<String, Object> ms = new LinkedHashMap<>();
			ms.put("index", 0);
			List<String> metaChars = new ArrayList<>();
			metaChars.add(sighOid);
			metaChars.add(annaOid);
			ms.put("characters", metaChars);
			List<Object> scenes = new ArrayList<>();
			scenes.add(ms);
			meta.put("scenes", scenes);
			metaNote = createNote(owner, bookGroupPath, PictureBookUtil.META_NOTE_NAME, JSONUtil.exportObject(meta));

			// --- PB2 graph: a scene node bound to both characters, plus a character-scoped portrait node for
			//     the target with an edge into the scene node and one produced artifact.
			String wfPath = PbBookUtil.workflowGroupPath(slug);
			BaseRecord wf = PbGraphUtil.getCreateWorkflow(owner, book, wfPath);
			assertNotNull("workflow", wf);
			BaseRecord sceneNode = PbGraphUtil.addNode(owner, wf, "scene-0-" + id, PbNodeTypeEnumType.SCENE_PROMPT, wfPath, 0);
			assertNotNull("scene node", sceneNode);
			assertNotNull("ref binding (target)", PbGraphUtil.addRecordBinding(owner, wf, sceneNode, "character", 0,
				OlioModelNames.MODEL_CHAR_PERSON, sighOid, wfPath));
			assertNotNull("ref binding (keeper)", PbGraphUtil.addRecordBinding(owner, wf, sceneNode, "character", 1,
				OlioModelNames.MODEL_CHAR_PERSON, annaOid, wfPath));

			BaseRecord portraitNode = PbGraphUtil.addNode(owner, wf, "portrait-sigh-" + id, PbNodeTypeEnumType.PORTRAIT, wfPath, 1);
			assertNotNull("portrait node", portraitNode);
			BaseRecord scopePatch = PbGraphUtil.patchOf(portraitNode, OlioModelNames.MODEL_PB_NODE,
				OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF);
			scopePatch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_CHARACTER);
			scopePatch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, sighOid);
			assertNotNull("scope patch", ioContext.getAccessPoint().update(owner, scopePatch));
			assertNotNull("edge portrait -> scene", PbGraphUtil.addBinding(owner, wf, sceneNode, "portrait", 2, portraitNode, null, wfPath));
			assertNotNull("portrait artifact", PbArtifactUtil.persistArtifact(owner, portraitNode, "portrait",
				PbArtifactTypeEnumType.PROMPT, PbBookUtil.artifactGroupPath(slug), null, "test prompt " + id, null, null));

			String portraitNodeOid = portraitNode.get(FieldNames.FIELD_OBJECT_ID);
			String sceneNodeOid = sceneNode.get(FieldNames.FIELD_OBJECT_ID);
			assertEquals("scene node starts with three bindings", 3, PbGraphUtil.listBindings(owner, sceneNode).size());
			assertTrue("listCharacters(owner) shows the target before delete",
				characterOids(PictureBookUtil.listCharacters(owner, bookOid)).contains(sighOid));

			// --- Negatives first, so they run against the intact fixture.
			try {
				PictureBookUtil.deleteCharacter(stranger, bookOid, sighOid);
				fail("a stranger must not be able to delete another user's character");
			}
			catch(PictureBookException e) {
				assertTrue("stranger is refused with 403 or 404, got " + e.getStatus(),
					e.getStatus() == 403 || e.getStatus() == 404);
			}
			try {
				PictureBookUtil.deleteCharacter(owner, bookOid, UUID.randomUUID().toString());
				fail("an unknown character objectId must 404");
			}
			catch(PictureBookException e) {
				assertEquals("unknown character", 404, e.getStatus());
			}
			try {
				PictureBookUtil.deleteCharacter(owner, bookOid, "");
				fail("an empty character objectId must 400");
			}
			catch(PictureBookException e) {
				assertEquals("empty character objectId", 400, e.getStatus());
			}
			assertTrue("negatives must not have deleted the target",
				characterOids(PictureBookUtil.listCharacters(owner, bookOid)).contains(sighOid));

			// --- The delete.
			PictureBookUtil.DeleteCharacterResult r = PictureBookUtil.deleteCharacter(owner, bookOid, sighOid);
			assertNotNull("result", r);
			assertTrue("charPerson deleted", r.deleted);
			assertEquals("deleted name", sighName, r.deletedName);
			assertEquals("one scene note detached", 1, r.scenesDetached);
			assertTrue("meta updated", r.metaUpdated);
			assertEquals("ref binding + edge removed", 2, r.bindingsRemoved);
			assertEquals("character-scoped node removed", 1, r.nodesRemoved);
			assertEquals("its artifact removed", 1, r.artifactsRemoved);

			// --- Verify every representation, re-read fresh.
			BaseRecord sceneAfter = readNote(owner, bookGroupPath + "/Scenes", "Scene 1", orgId);
			assertNotNull("scene note still exists", sceneAfter);
			String sceneTextAfter = sceneAfter.get("text");
			assertFalse("scene text no longer names the deleted character: " + sceneTextAfter, sceneTextAfter.contains(sighName));
			assertTrue("scene text still names the keeper: " + sceneTextAfter, sceneTextAfter.contains(annaName));

			BaseRecord metaAfter = readNote(owner, bookGroupPath, PictureBookUtil.META_NOTE_NAME, orgId);
			assertNotNull("meta note still exists", metaAfter);
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> metaScenes = (List<Map<String, Object>>) noteJson(metaAfter).get("scenes");
			assertEquals("one meta scene", 1, metaScenes.size());
			@SuppressWarnings("unchecked")
			List<String> metaCharsAfter = (List<String>) metaScenes.get(0).get("characters");
			assertEquals("meta scene keeps only the keeper", List.of(annaOid), metaCharsAfter);

			Set<String> listed = characterOids(PictureBookUtil.listCharacters(owner, bookOid));
			assertFalse("listCharacters no longer shows the deleted character", listed.contains(sighOid));
			assertTrue("listCharacters still shows the keeper", listed.contains(annaOid));

			Set<String> nodeOids = objectIds(PbGraphUtil.listNodes(owner, wf));
			assertFalse("portrait node gone", nodeOids.contains(portraitNodeOid));
			assertTrue("scene node kept", nodeOids.contains(sceneNodeOid));
			List<BaseRecord> bindingsAfter = PbGraphUtil.listBindings(owner, sceneNode);
			assertEquals("only the keeper's ref binding remains", 1, bindingsAfter.size());
			assertEquals("remaining binding references the keeper", annaOid,
				bindingsAfter.get(0).get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));

			Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, sighOid);
			cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			cq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
			cq.setCache(false);
			assertNull("charPerson row is gone", ioContext.getSearch().findRecord(cq));

			// Deleting the same character again must 404, not silently succeed.
			try {
				PictureBookUtil.deleteCharacter(owner, bookOid, sighOid);
				fail("second delete of the same character must 404");
			}
			catch(PictureBookException e) {
				assertEquals("already deleted", 404, e.getStatus());
			}
		}
		finally {
			if(sceneNote != null) {
				deleteQuietly(owner, readNote(owner, PB1_BOOKS_ROOT + slug + "/Scenes", "Scene 1", orgId), "scene note");
			}
			if(metaNote != null) {
				deleteQuietly(owner, readNote(owner, PB1_BOOKS_ROOT + slug, PictureBookUtil.META_NOTE_NAME, orgId), "meta note");
			}
			deleteQuietly(owner, scenesGroup, "Scenes group");
			deleteQuietly(owner, bookGroup, "PB1 book group");
			if(olioUser == null) {
				olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
			}
			if(olioUser != null) {
				BaseRecord row = rawRowBySlug(ioContext, slug, orgId);
				if(row == null) {
					PbBookUtil.rollbackCreate(ioContext, slug, orgId, false, new PictureBookException(500, "test cleanup"));
				}
				else {
					PictureBookUtil.DeleteResult del = PictureBookUtil.teardownBookFootprintAsOlio(olioUser, row, orgId);
					if(!del.deleted) {
						logger.warn("cleanup teardown of " + slug + ": " + del.reason);
					}
				}
			}
		}
	}
}
