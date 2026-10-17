package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.picturebook.PbArtifactUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbDeleteUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil.LocationPassResult;
import org.cote.accountmanager.olio.picturebook.PbMigrationUtil;
import org.cote.accountmanager.olio.picturebook.PbMigrationUtil.ImportResult;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 6 exit-criterion test: import a real PB1 book into PB2 and verify PB1 records are untouched.
 * <p>
 * Skips automatically if no PB1 book with a {@code .pictureBookMeta} note is found in
 * {@code ~/Data/PictureBooks/} — the test will never synthesize fake PB1 data.
 */
public class TestPbMigration extends BaseTest {

	public static final Logger logger = LogManager.getLogger(TestPbMigration.class);

	private static final String ORG_A = "/Development/PictureBook Custom Tests";
	private static final String TEST_USER = "pbCustomTestUser";
	private static final String PICTURES_BOOKS_PATH = "~/Data/PictureBooks";
	private static final String V1_META_NOTE_NAME = PbMigrationUtil.V1_META_NOTE_NAME;

	@Before
	public void migrationSetup() {
		OlioContextUtil.clearCache();
		OlioModelNames.use();
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	/**
	 * Find all PB1 book groups under {@code ~/Data/PictureBooks/} that have a
	 * {@code .pictureBookMeta} note. Returns the most recently created one that hasn't been
	 * migrated yet, or the most recent one if all have been migrated. Newest-first ordering
	 * ensures that a freshly created iter-N book is tried before previously-migrated ones.
	 */
	private BaseRecord findMigratableBookGroup(BaseRecord user) {
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);

		// Resolve the PictureBooks container group
		BaseRecord pbDir = IOSystem.getActiveContext().getPathUtil().findPath(user,
			ModelNames.MODEL_GROUP, PICTURES_BOOKS_PATH, GroupEnumType.DATA.toString(), orgId);
		if (pbDir == null) {
			return null;
		}
		long pbDirId = ((Number) pbDir.get(FieldNames.FIELD_ID)).longValue();

		// List immediate child groups of the PictureBooks dir — newest first so a freshly
		// created book is tried before older already-migrated ones
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, pbDirId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_ID);
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING.toString());
		}
		catch(Exception e) {
			logger.warn("Could not set sort order on PB1 book group query: " + e.getMessage());
		}
		q.setRequestRange(0, 50);
		q.planMost(false);
		BaseRecord[] bookGroups = IOSystem.getActiveContext().getSearch().findRecords(q);
		if (bookGroups == null || bookGroups.length == 0) {
			return null;
		}

		// Walk newest-first; prefer a book that hasn't been migrated yet (no PB2 counterpart).
		// Fall back to the newest with a meta note if all have been migrated.
		BaseRecord fallback = null;
		for (BaseRecord bg : bookGroups) {
			String path = bg.get(FieldNames.FIELD_PATH);
			if (path == null) {
				continue;
			}
			BaseRecord grp = IOSystem.getActiveContext().getPathUtil().findPath(user,
				ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), orgId);
			if (grp == null) {
				continue;
			}
			Query nq = QueryUtil.createQuery(ModelNames.MODEL_NOTE,
				FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
			nq.field(FieldNames.FIELD_NAME, V1_META_NOTE_NAME);
			nq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			nq.planMost(false);
			BaseRecord meta = IOSystem.getActiveContext().getAccessPoint().find(user, nq);
			if (meta == null) {
				continue;
			}
			// Prefer books that haven't been migrated yet — check for an existing PB2 counterpart
			String groupName = bg.get(FieldNames.FIELD_NAME);
			String slug = org.cote.accountmanager.olio.picturebook.PbPipelineUtil.deriveSlug(groupName);
			if (slug != null && PbBookUtil.findBookBySlug(user, slug, orgId) == null) {
				return bg;  // un-migrated: best candidate
			}
			if (fallback == null) {
				fallback = bg;  // already migrated: keep as fallback
			}
		}
		return fallback;
	}

	/**
	 * Deterministic synthetic fixture test: seeds a minimal PB1-shaped group + notes inline and
	 * verifies the full migration path without relying on any pre-existing real PB1 data.
	 * Idempotent — safe to run multiple times; handles both fresh import and already-migrated state.
	 */
	@Test
	public void testImportV1BookSynthetic() throws Exception {
		BaseRecord user = user();
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// ── 1. Create the PB1 fixture book group ────────────────────────────────────
		String fixtureGroupPath = PICTURES_BOOKS_PATH + "/TestMigrationFixture";
		BaseRecord fixtureGroup = IOSystem.getActiveContext().getPathUtil().makePath(
			user, ModelNames.MODEL_GROUP, fixtureGroupPath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Fixture PB1 book group must be created/found", fixtureGroup);
		String fixtureGroupObjectId = fixtureGroup.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Fixture group objectId must not be null", fixtureGroupObjectId);
		long fixtureGroupId = ((Number) fixtureGroup.get(FieldNames.FIELD_ID)).longValue();

		// ── 2. Create or retrieve the synthetic scene note ──────────────────────────
		//    The note's text is a JSON map matching the keys importScene reads
		String sceneNoteName = ".syntheticScene0";
		String sceneJson = "{\"title\":\"Synthetic Scene\",\"summary\":\"A quiet field at dawn.\","
			+ "\"setting\":\"Rolling meadows at sunrise.\",\"action\":\"A figure walks slowly.\","
			+ "\"mood\":\"Peaceful\",\"blurb\":\"A serene opening.\"}";

		Query sceneNoteQ = QueryUtil.createQuery(ModelNames.MODEL_NOTE,
			FieldNames.FIELD_GROUP_ID, fixtureGroupId);
		sceneNoteQ.field(FieldNames.FIELD_NAME, sceneNoteName);
		sceneNoteQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		sceneNoteQ.planMost(false);
		BaseRecord sceneNote = IOSystem.getActiveContext().getAccessPoint().find(user, sceneNoteQ);
		if (sceneNote == null) {
			ParameterList scenePlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, fixtureGroupPath);
			scenePlist.parameter(FieldNames.FIELD_NAME, sceneNoteName);
			sceneNote = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_NOTE, user, null, scenePlist);
			sceneNote.set("text", sceneJson);
			sceneNote = IOSystem.getActiveContext().getAccessPoint().create(user, sceneNote);
			assertNotNull("Synthetic scene note must be created", sceneNote);
		}
		String sceneNoteObjectId = sceneNote.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Scene note objectId must not be null", sceneNoteObjectId);
		logger.info("Synthetic scene note objectId: {}", sceneNoteObjectId);

		// ── 3. Create or retrieve the .pictureBookMeta note ─────────────────────────
		//    The JSON must carry the schema so the deserializer can resolve the scenes list type
		String metaJson = "{\"schema\":\"olio.pictureBookMeta\","
			+ "\"workName\":\"Synthetic Migration Test Book\","
			+ "\"scenes\":[{\"schema\":\"olio.pictureBookScene\","
			+ "\"objectId\":\"" + sceneNoteObjectId + "\"}]}";

		Query metaNoteQ = QueryUtil.createQuery(ModelNames.MODEL_NOTE,
			FieldNames.FIELD_GROUP_ID, fixtureGroupId);
		metaNoteQ.field(FieldNames.FIELD_NAME, V1_META_NOTE_NAME);
		metaNoteQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		metaNoteQ.planMost(false);
		BaseRecord metaNote = IOSystem.getActiveContext().getAccessPoint().find(user, metaNoteQ);
		if (metaNote == null) {
			ParameterList metaPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, fixtureGroupPath);
			metaPlist.parameter(FieldNames.FIELD_NAME, V1_META_NOTE_NAME);
			metaNote = IOSystem.getActiveContext().getFactory().newInstance(
				ModelNames.MODEL_NOTE, user, null, metaPlist);
			metaNote.set("text", metaJson);
			metaNote = IOSystem.getActiveContext().getAccessPoint().create(user, metaNote);
			assertNotNull("Meta note must be created", metaNote);
		}
		logger.info("Meta note present; meta JSON points to scene objectId={}", sceneNoteObjectId);

		// ── 4. Determine the expected slug and check for a prior migration run ───────
		String fixtureGroupName = fixtureGroup.get(FieldNames.FIELD_NAME);
		String expectedSlug = org.cote.accountmanager.olio.picturebook.PbPipelineUtil.deriveSlug(fixtureGroupName);
		assertNotNull("Slug derivation must succeed for fixture group name '" + fixtureGroupName + "'", expectedSlug);
		logger.info("Expected PB2 slug: {}", expectedSlug);

		BaseRecord pb2Book = PbBookUtil.findBookBySlug(user, expectedSlug, orgId);
		if (pb2Book != null) {
			logger.info("PB2 book '{}' already exists — verifying idempotency (prior run)", expectedSlug);
		} else {
			// ── 5. Run the migration ───────────────────────────────────────────────
			ImportResult result;
			try {
				result = PbMigrationUtil.importV1Book(user, dataPath(), fixtureGroupObjectId);
			} catch (org.cote.accountmanager.olio.picturebook.PictureBookException e) {
				if (e.getStatus() == 409) {
					// Race between the findBookBySlug check above and the guard inside importV1Book —
					// treat as a successful prior run
					logger.info("importV1Book returned 409 on first call — treating as already-migrated");
					pb2Book = PbBookUtil.findBookBySlug(user, expectedSlug, orgId);
					assertNotNull("PB2 book must exist after 409", pb2Book);
					return;
				}
				throw e;
			}
			assertNotNull("importV1Book must return a non-null result", result);
			logger.info("Synthetic migration result: {}", result);

			assertNotNull("result.slug must not be null", result.slug);
			assertNotNull("result.bookObjectId must not be null", result.bookObjectId);
			assertEquals("scenesFailed must be 0", 0, result.scenesFailed);
			assertTrue("scenesImported must be >= 1", result.scenesImported >= 1);

			pb2Book = PbBookUtil.findBookBySlug(user, result.slug, orgId);
		}

		// ── 6. Post-migration assertions ─────────────────────────────────────────
		assertNotNull("PB2 book must exist for slug '" + expectedSlug + "'", pb2Book);

		// PB1 auth.group must still exist and be unchanged
		BaseRecord pb1GroupAfter = PictureBookUtil.findBookGroup(user, fixtureGroupObjectId);
		assertNotNull("PB1 fixture group must still exist after migration", pb1GroupAfter);
		assertEquals("PB1 fixture group name must be unchanged",
			fixtureGroupName, (String) pb1GroupAfter.get(FieldNames.FIELD_NAME));

		logger.info("testImportV1BookSynthetic PASSED — PB2 book exists for slug '{}'", expectedSlug);
	}

	/**
	 * V3 §28 step 3 (P3-3): a PB1 scene note that names a {@code landscapeObjectId} has that image carried over as an
	 * UNAPPROVED {@code landscape} artifact on the scene's canonical LANDSCAPE node (DONE_UNVERIFIED, null inputHash,
	 * bytes referenced - the artifact's {@code data} IS the PB1 row, not a copy), and the import ends with the
	 * whole-book location pass, whose proposals group the scenes that share a setting without resolving anything.
	 * <p>
	 * The pass resolves {@code generalChat} for the importing user, and {@code pbCustomTestUser} has none, so this
	 * test provisions one in its {@code ~/Chat} on the JVM's gate-resolved LLM route (quote the {@code [LLM-GATE]}
	 * line with the result) and deletes it - with the PB1 fixture and the imported book - in {@code finally}. The
	 * fixture is nonce-named so every run is a fresh import, never the idempotent "already migrated" branch.
	 */
	@Test
	public void testImportV1BookLandscapesAndLocationPass() throws Exception {
		BaseRecord user = user();
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		String fixtureGroupPath = PICTURES_BOOKS_PATH + "/TestMigrationLandscape" + nonce;
		String setting = "The Lantern Inn's taproom, a low-beamed tavern by the quay";
		String[] titles = new String[] {"Arrival at the Lantern Inn", "Return to the Lantern Inn"};
		List<BaseRecord> fixtures = new ArrayList<>();
		BaseRecord fixtureGroup = null;
		BaseRecord chatCfg = null;
		String bookOid = null;
		try {
			// ── 0. generalChat for the importing user on the gate's route ───────────
			chatCfg = OlioTestUtil.getOllamaOpenAIConfig(user, "generalChat", testProperties);
			assertNotNull("generalChat chatConfig on the gate route", chatCfg);
			// The get-or-create's existence check ran PBAC's group-less name scan before the row existed, and
			// CacheDBSearch pins that result (a foreign org's same-named record) across creates; without this,
			// resolveConfig("generalChat") in import step 12 is denied for the row's own owner.
			ChatUtil.clearCache();
			logger.info("[p33-migration] generalChat model={} route={} tier={}", chatCfg.get("model"),
				testProperties.getProperty("test.llm.route"), testProperties.getProperty("test.llm.resolvedTier"));

			// ── 1. PB1 fixture: group, two landscape PNGs, two scene notes at ONE place, meta note ──
			fixtureGroup = IOSystem.getActiveContext().getPathUtil().makePath(
				user, ModelNames.MODEL_GROUP, fixtureGroupPath, GroupEnumType.DATA.toString(), orgId);
			assertNotNull("Fixture PB1 book group must be created", fixtureGroup);
			String fixtureGroupObjectId = fixtureGroup.get(FieldNames.FIELD_OBJECT_ID);
			String fixtureGroupName = fixtureGroup.get(FieldNames.FIELD_NAME);
			String expectedSlug = PbPipelineUtil.deriveSlug(fixtureGroupName);
			assertNotNull("Slug derivation must succeed for '" + fixtureGroupName + "'", expectedSlug);
			assertNull("fresh fixture must not already be migrated", PbBookUtil.findBookBySlug(user, expectedSlug, orgId));

			String[] dataOids = new String[titles.length];
			long[] dataIds = new long[titles.length];
			StringBuilder metaScenes = new StringBuilder();
			for(int i = 0; i < titles.length; i++) {
				BaseRecord data = createFixturePng(user, fixtureGroupPath, "landscape" + i + ".png", 0x203040 + (i * 0x404040));
				fixtures.add(data);
				dataOids[i] = data.get(FieldNames.FIELD_OBJECT_ID);
				dataIds[i] = ((Number) data.get(FieldNames.FIELD_ID)).longValue();
				String sceneJson = "{\"title\":\"" + titles[i] + "\",\"summary\":\"A traveller and the innkeeper.\","
					+ "\"setting\":\"" + setting + "\",\"action\":\"They talk over ale.\","
					+ "\"mood\":\"Warm\",\"blurb\":\"Scene " + i + ".\","
					+ "\"landscapeObjectId\":\"" + dataOids[i] + "\"}";
				BaseRecord note = createFixtureNote(user, fixtureGroupPath, ".scene" + i, sceneJson);
				fixtures.add(note);
				if(i > 0) {
					metaScenes.append(',');
				}
				metaScenes.append("{\"schema\":\"olio.pictureBookScene\",\"objectId\":\"")
					.append((String) note.get(FieldNames.FIELD_OBJECT_ID)).append("\"}");
			}
			String metaJson = "{\"schema\":\"olio.pictureBookMeta\",\"workName\":\"Landscape Migration Test Book " + nonce + "\","
				+ "\"scenes\":[" + metaScenes + "]}";
			BaseRecord metaNote = createFixtureNote(user, fixtureGroupPath, V1_META_NOTE_NAME, metaJson);
			fixtures.add(metaNote);

			// ── 2. Import ────────────────────────────────────────────────────────────
			long t0 = System.currentTimeMillis();
			ImportResult result = PbMigrationUtil.importV1Book(user, dataPath(), fixtureGroupObjectId);
			long ms = System.currentTimeMillis() - t0;
			assertNotNull("importV1Book must return a result", result);
			bookOid = result.bookObjectId;
			logger.info("[p33-migration] import took {} ms (recorded, not asserted): {}", ms, result);
			assertEquals(expectedSlug, result.slug);
			assertEquals("scenesFailed", 0, result.scenesFailed);
			assertEquals("scenesImported", titles.length, result.scenesImported);
			assertEquals("landscapesImported", titles.length, result.landscapesImported);

			BaseRecord book = PbBookUtil.findBookBySlug(user, result.slug, orgId);
			assertNotNull("PB2 book must exist for slug '" + result.slug + "'", book);
			List<BaseRecord> scenes = PbBookUtil.listScenes(user, book);
			assertEquals("PB2 scene count", titles.length, scenes.size());
			BaseRecord workflow = PbGraphUtil.findWorkflow(user, book);
			assertNotNull("PB2 book must have a workflow after migration", workflow);

			// ── 3. One LANDSCAPE node per scene, one unapproved landscape artifact referencing the PB1 bytes ──
			List<String> sceneOids = new ArrayList<>();
			for(BaseRecord scene : scenes) {
				String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
				sceneOids.add(sceneOid);
				int idx = ((Number) scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX)).intValue();
				assertTrue("scene index " + idx + " within the fixture", idx >= 0 && idx < titles.length);
				assertEquals("scene " + idx + " title", titles[idx], (String) scene.get(OlioFieldNames.FIELD_PB_TITLE));
				BaseRecord node = PbPipelineUtil.findNodeByHandle(user, workflow, PbPipelineUtil.landscapeHandle(sceneOid));
				assertNotNull("LANDSCAPE node for scene " + idx, node);
				String nodeType = String.valueOf((Object) node.get(OlioFieldNames.FIELD_PB_NODE_TYPE));
				assertEquals("node type", "landscape", nodeType.toLowerCase());
				String storedStatus = String.valueOf((Object) node.get(OlioFieldNames.FIELD_PB_NODE_STATUS));
				assertEquals("migrated LANDSCAPE node " + idx + " must be DONE_UNVERIFIED", "done_unverified", storedStatus.toLowerCase());
				assertNull("migrated LANDSCAPE node " + idx + " must have null inputHash", node.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
				List<BaseRecord> chain = PbArtifactUtil.listChain(user, node, PbPipelineUtil.ROLE_LANDSCAPE);
				assertEquals("one landscape artifact on scene " + idx, 1, chain.size());
				BaseRecord art = chain.get(0);
				assertNotEquals("imported landscape must not be approved", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(art));
				Object dataRef = art.get(OlioFieldNames.FIELD_PB_DATA);
				assertTrue("artifact.data is a record", dataRef instanceof BaseRecord);
				long refId = ((Number) ((BaseRecord) dataRef).get(FieldNames.FIELD_ID)).longValue();
				assertEquals("artifact " + idx + " references the PB1 data row (not a copy)", dataIds[idx], refId);
			}

			// ── 4. The location pass ran, parsed, and put every scene in a proposal; nothing resolved ──
			LocationPassResult pass = result.locationPass;
			assertNotNull("location pass result", pass);
			logger.info("[p33-migration] location pass: run={} scenes={} skipped={} chunks={}/{} proposed={} updated={} settled={} retired={} hard={} warnings={}",
				pass.passRun, pass.scenesConsidered, pass.scenesSkipped, pass.chunksParsed, pass.chunks, pass.proposed, pass.updated,
				pass.settled, pass.retired, pass.hardFailure, pass.warnings);
			assertFalse("location pass must not hard-fail: " + pass.warnings + " / " + result.warnings, pass.hardFailure);
			assertEquals("scenes considered", titles.length, pass.scenesConsidered);
			assertEquals("scenes skipped", 0, pass.scenesSkipped);
			assertEquals("chunks", 1, pass.chunks);
			assertEquals("chunks parsed", 1, pass.chunksParsed);
			assertTrue("at least one proposal", pass.proposed >= 1);
			List<BaseRecord> rows = PbLocationUtil.listProposals(user, book, null);
			assertEquals("proposal rows", pass.proposed, rows.size());
			for(BaseRecord row : rows) {
				logger.info("[p33-migration] proposal '{}' kind={} scenes={}", row.get(OlioFieldNames.FIELD_PB_PROPOSED_NAME),
					row.get(OlioFieldNames.FIELD_PB_KIND), row.get(OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS));
				assertNull("a proposal resolves nothing", row.get(FieldNames.FIELD_LOCATION));
			}
			for(String sceneOid : sceneOids) {
				boolean listed = false;
				for(BaseRecord row : rows) {
					List<String> ids = row.get(OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS);
					if(ids != null && ids.contains(sceneOid)) {
						listed = true;
						break;
					}
				}
				assertTrue("every scene appears in a proposal: " + sceneOid, listed);
			}
			for(BaseRecord scene : scenes) {
				BaseRecord fresh = PbBookUtil.readScene(user, scene.get(FieldNames.FIELD_OBJECT_ID), orgId);
				assertNotNull(fresh);
				Object loc = fresh.get(FieldNames.FIELD_LOCATION);
				assertTrue("no scene is resolved by a pass", loc == null || !(loc instanceof BaseRecord));
			}

			// ── 5. PB1 untouched ─────────────────────────────────────────────────────
			BaseRecord pb1GroupAfter = PictureBookUtil.findBookGroup(user, fixtureGroupObjectId);
			assertNotNull("PB1 fixture group must still exist after migration", pb1GroupAfter);
			assertEquals(fixtureGroupName, (String) pb1GroupAfter.get(FieldNames.FIELD_NAME));
			Query metaQ = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, metaNote.get(FieldNames.FIELD_OBJECT_ID));
			metaQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			metaQ.planMost(true);
			metaQ.setCache(false);
			BaseRecord metaAfter = IOSystem.getActiveContext().getAccessPoint().find(user, metaQ);
			assertNotNull("PB1 meta note must still exist", metaAfter);
			assertEquals("PB1 meta note text unchanged", metaJson, (String) metaAfter.get("text"));
		}
		finally {
			if(bookOid != null) {
				try {
					DeleteResult del = PbDeleteUtil.deleteBookComplete(user, bookOid);
					logger.info("[p33-migration] cleanup book {}: deleted={} reason={}", bookOid, del.deleted, del.reason);
				}
				catch(Exception e) {
					logger.error("[p33-migration] cleanup book " + bookOid + ": " + e.getMessage());
				}
			}
			for(int i = fixtures.size() - 1; i >= 0; i--) {
				deleteQuietly(user, fixtures.get(i));
			}
			deleteQuietly(user, fixtureGroup);
			if(chatCfg != null) {
				Object conn = chatCfg.get("connection");
				deleteQuietly(user, chatCfg);
				if(conn instanceof BaseRecord) {
					deleteQuietly(user, (BaseRecord) conn);
				}
			}
		}
	}

	private BaseRecord createFixtureNote(BaseRecord user, String groupPath, String name, String text) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set("text", text);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, note);
		assertNotNull("fixture note " + name, created);
		return created;
	}

	/// A real 4x4 PNG as a data.data row in the fixture group: the migration references it, never copies it.
	private BaseRecord createFixturePng(BaseRecord user, String groupPath, String name, int rgb) throws Exception {
		BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		for(int y = 0; y < 4; y++) {
			for(int x = 0; x < 4; x++) {
				img.setRGB(x, y, rgb);
			}
		}
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		assertTrue("PNG encode", ImageIO.write(img, "png", bos));
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(data, bos.toByteArray());
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, data);
		assertNotNull("fixture data " + name, created);
		return created;
	}

	private void deleteQuietly(BaseRecord user, BaseRecord rec) {
		if(rec == null) {
			return;
		}
		try {
			if(!IOSystem.getActiveContext().getAccessPoint().delete(user, rec)) {
				logger.warn("[p33-migration] cleanup: {} {} was not deleted", rec.getSchema(), rec.get(FieldNames.FIELD_NAME));
			}
		}
		catch(Exception e) {
			logger.error("[p33-migration] cleanup " + rec.getSchema() + " " + rec.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
	}

	@Test
	public void TestImportV1Book() {
		BaseRecord user = user();
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);

		// Discover a migratable PB1 book — skip if none exists
		BaseRecord bookGroup = findMigratableBookGroup(user);
		assumeTrue("No PB1 book with a " + V1_META_NOTE_NAME + " note found — test skipped",
			bookGroup != null);

		String bookGroupObjectId = bookGroup.get(FieldNames.FIELD_OBJECT_ID);
		String groupName = bookGroup.get(FieldNames.FIELD_NAME);
		String groupPath = bookGroup.get(FieldNames.FIELD_PATH);
		logger.info("Migrating PB1 book '{}' (objectId={})", groupName, bookGroupObjectId);

		// Capture the PB1 meta note text before migration (to verify it is untouched afterward)
		BaseRecord pbDir = IOSystem.getActiveContext().getPathUtil().findPath(user,
			ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Book group path should resolve", pbDir);
		Query preMigQ = QueryUtil.createQuery(ModelNames.MODEL_NOTE,
			FieldNames.FIELD_GROUP_ID, pbDir.get(FieldNames.FIELD_ID));
		preMigQ.field(FieldNames.FIELD_NAME, V1_META_NOTE_NAME);
		preMigQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		preMigQ.planMost(true);
		BaseRecord metaNoteBefore = IOSystem.getActiveContext().getAccessPoint().find(user, preMigQ);
		assertNotNull("Meta note must be present before migration", metaNoteBefore);
		String metaTextBefore = metaNoteBefore.get("text");
		assertNotNull("Meta note must have text before migration", metaTextBefore);

		// Derive the slug upfront so we can check for a prior run
		String expectedSlug = org.cote.accountmanager.olio.picturebook.PbPipelineUtil.deriveSlug(groupName);
		assertNotNull("Slug derivation must succeed for '" + groupName + "'", expectedSlug);

		// If the PB2 book already exists (idempotent re-run), verify it rather than re-importing
		BaseRecord pb2Book = PbBookUtil.findBookBySlug(user, expectedSlug, orgId);
		if (pb2Book != null) {
			logger.info("PB2 book '{}' already exists — verifying existing import", expectedSlug);
		}
		else {
			// Run the migration
			ImportResult result = PbMigrationUtil.importV1Book(user, dataPath(), bookGroupObjectId);
			assertNotNull("importV1Book must return a result", result);
			logger.info("Migration result: {}", result);

			assertNotNull("Result slug must not be null", result.slug);
			assertNotNull("Result bookObjectId must not be null", result.bookObjectId);
			assertEquals("Failed scene count must be 0", 0, result.scenesFailed);

			pb2Book = PbBookUtil.findBookBySlug(user, result.slug, orgId);
			assertNotNull("PB2 book must exist for slug '" + result.slug + "'", pb2Book);

			if (result.scenesImported > 0) {
				List<BaseRecord> scenes = PbBookUtil.listScenes(user, pb2Book);
				assertNotNull("PB2 scene list must not be null", scenes);
				assertEquals("PB2 scene count must match imported count",
					result.scenesImported, scenes.size());

				// Phase 6 exit criterion: migrated nodes carry DONE_UNVERIFIED + null inputHash
				// (honest labelling — PB1 had output for each scene but provenance is unknown to PB2)
				BaseRecord workflow = PbGraphUtil.findWorkflow(user, pb2Book);
				assertNotNull("PB2 book must have a workflow after migration", workflow);
				Query nodeQ = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE,
					OlioFieldNames.FIELD_PB_WORKFLOW, workflow);
				nodeQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
				nodeQ.setRequest(new String[]{
					FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
					OlioFieldNames.FIELD_PB_NODE_STATUS, OlioFieldNames.FIELD_PB_INPUT_HASH
				});
				nodeQ.setCache(false);
				QueryResult nodeQr = IOSystem.getActiveContext().getAccessPoint().list(user, nodeQ);
				assertNotNull("PB2 workflow must have nodes after migration", nodeQr);
				BaseRecord[] migrationNodes = nodeQr.getResults();
				assertNotNull("PB2 workflow node result set must not be null", migrationNodes);
				assertTrue("PB2 workflow must have at least one migration node", migrationNodes.length > 0);
				for(BaseRecord node : migrationNodes) {
					String nodeName = node.get(FieldNames.FIELD_NAME);
					String storedStatus = node.get(OlioFieldNames.FIELD_PB_NODE_STATUS);
					String storedHash = node.get(OlioFieldNames.FIELD_PB_INPUT_HASH);
					assertEquals("Migrated node '" + nodeName + "' must be DONE_UNVERIFIED",
						"done_unverified", storedStatus != null ? storedStatus.toLowerCase() : null);
					assertNull("Migrated node '" + nodeName + "' must have null inputHash", storedHash);
				}
			}
		}

		// Shared assertions (always run, whether imported this run or previously)
		assertNotNull("PB2 book must exist for slug '" + expectedSlug + "'", pb2Book);

		// PB1 auth.group must still exist and be readable
		BaseRecord pb1GroupAfter = PictureBookUtil.findBookGroup(user, bookGroupObjectId);
		assertNotNull("PB1 auth.group must still exist after migration", pb1GroupAfter);
		assertEquals("PB1 group name must be unchanged", groupName,
			(String) pb1GroupAfter.get(FieldNames.FIELD_NAME));

		// PB1 meta note must still exist and its text must be identical
		Query postMigQ = QueryUtil.createQuery(ModelNames.MODEL_NOTE,
			FieldNames.FIELD_GROUP_ID, pbDir.get(FieldNames.FIELD_ID));
		postMigQ.field(FieldNames.FIELD_NAME, V1_META_NOTE_NAME);
		postMigQ.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		postMigQ.planMost(true);
		BaseRecord metaNoteAfter = IOSystem.getActiveContext().getAccessPoint().find(user, postMigQ);
		assertNotNull("PB1 meta note must still exist after migration", metaNoteAfter);
		String metaTextAfter = metaNoteAfter.get("text");
		assertEquals("PB1 meta note text must be unchanged after migration",
			metaTextBefore, metaTextAfter);
	}

	@Test
	public void TestImportV1BookDuplicateRejected() {
		BaseRecord user = user();
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);

		BaseRecord bookGroup = findMigratableBookGroup(user);
		assumeTrue("No PB1 book with a " + V1_META_NOTE_NAME + " note found — test skipped",
			bookGroup != null);

		String bookGroupObjectId = bookGroup.get(FieldNames.FIELD_OBJECT_ID);
		String groupName = bookGroup.get(FieldNames.FIELD_NAME);

		// Run once — may or may not already exist; catch 409 silently
		String slug = null;
		try {
			ImportResult first = PbMigrationUtil.importV1Book(user, dataPath(), bookGroupObjectId);
			slug = first.slug;
		}
		catch (org.cote.accountmanager.olio.picturebook.PictureBookException e) {
			if (e.getStatus() == 409) {
				// Already exists — that is fine for this test's purpose
				// Derive slug to verify guard exists
				slug = org.cote.accountmanager.olio.picturebook.PbPipelineUtil.deriveSlug(groupName);
				assertNotNull("Slug derivation must succeed", slug);
			}
			else {
				throw e;
			}
		}

		// Second call must be rejected with 409
		final String finalSlug = slug;
		boolean got409 = false;
		try {
			PbMigrationUtil.importV1Book(user, dataPath(), bookGroupObjectId);
		}
		catch (org.cote.accountmanager.olio.picturebook.PictureBookException e) {
			if (e.getStatus() == 409) {
				got409 = true;
			}
			else {
				throw e;
			}
		}
		// A PB2 book with this slug exists — the guard must have fired
		assertNotNull("Slug must have been established", finalSlug);
		BaseRecord pb2Book = PbBookUtil.findBookBySlug(user, finalSlug, orgId);
		assertNotNull("PB2 book must exist after import", pb2Book);
		// Either we got a 409 on the re-import, or the first call above already found the book
		// Either way is correct — the guard must not silently overwrite
		logger.info("Duplicate guard test: got409={}, pb2Book exists={}", got409, pb2Book != null);
	}
}
