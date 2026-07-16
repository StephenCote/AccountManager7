package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Real end-to-end test of the PictureBook persistence fixes, calling the ACTUAL shipped
 * {@link PictureBookService} REST methods directly (no HTTP transport — Tomcat is not
 * running in this environment — but the exact same Java methods Jersey would invoke),
 * against the live Postgres DB, the live Ollama LLM at 192.168.1.42, and the live SwarmUI
 * SD backend at 192.168.1.42:7801. This mirrors {@code TestISO42001Service}'s
 * hand-rolled-{@code HttpServletRequestMock} component-test pattern (no mocking framework).
 *
 * This class deliberately does NOT re-implement any of PictureBookService's fixed logic
 * (gender clamp, persisted profile/narrative creation, landscape retention, isBook flag,
 * failedCharacters/failedPortraits reporting). Every assertion below re-queries the live DB
 * with a FRESH {@code Query}/{@code AccessPoint.find()} — never a reused in-memory reference
 * from the call that created the data — per the explicit requirement that prompted this test
 * (a prior attempt was rejected for testing hand-copied fragments and never re-querying the DB).
 *
 * Why this lives in AccountManagerService7's test tree (not Objects7's, as first suggested):
 * PictureBookService — and the fixed orchestration logic under test (createCharPerson(),
 * createPersistedForeignInstance(), patchCharPersonField(), normalizeGender()) — is defined in
 * Service7. Objects7 cannot depend on Service7 (architecture.md: dependencies point downward
 * toward Objects7, never the reverse), so an Objects7-tree test could only call this logic by
 * re-implementing it inline — exactly the fake-test pattern this test exists to avoid. Calling
 * the real class directly, the way Service7's own existing {@code TestISO42001Service} does, is
 * the only way to exercise the actual shipped code without HTTP.
 *
 * GATING: this test hits live LLM + SD backends and must never run by default or in parallel.
 * It follows this repo's existing convention (see {@code TestPageIndex}'s PAGEINDEX_LLM env var):
 * set the {@code PICTUREBOOK_E2E} environment variable to enable, and always run single-threaded:
 *
 *   PICTUREBOOK_E2E=1 mvn -o -pl AccountManagerService7 -Dtest=TestPictureBookServiceE2E -DskipTests=false test
 *
 * Never uses the admin user as the acting caller — admin is used only to provision the
 * non-admin test user, mirroring ensureSharedTestUser()/TestPortraitReuse's convention.
 */
public class TestPictureBookServiceE2E extends BaseTest {

	private static final String EMIT_DIR;
	static {
		// MUST be set before PictureBookService's class-init (PB_EMIT_DIR is `static final`,
		// read once at <clinit>) — this static block runs when THIS test class loads, which is
		// guaranteed to be before any `new PictureBookService()` in this test, and this test
		// is run in isolation via -Dtest=TestPictureBookServiceE2E so no other class touches
		// PictureBookService first in the same JVM fork.
		String dir = new File("./target/test-images/pictureBook-e2e/" + System.currentTimeMillis()).getAbsolutePath();
		new File(dir).mkdirs();
		System.setProperty("test.pictureBook.emitDir", dir);
		EMIT_DIR = dir;
		System.out.println("[TestPictureBookServiceE2E] test.pictureBook.emitDir = " + EMIT_DIR);
	}

	private static Properties liveProps = null;
	private static final String LLM_MODEL = "qwen3-vl:8b-instruct";
	private static final String ORG_SUBPATH = "/Development/PictureBookE2E";

	private OrganizationContext testOrgCtx;
	private BaseRecord testUser;
	private long orgId;
	private final PictureBookService service = new PictureBookService();

	private static boolean llmEnabled() {
		return System.getenv("PICTUREBOOK_E2E") != null;
	}

	/** ServletContext proxy providing only the two init-params generateSceneImage() actually reads. */
	private ServletContext sdServletContext(String sdServer, String sdApiType) {
		return (ServletContext) Proxy.newProxyInstance(
			getClass().getClassLoader(),
			new Class<?>[] { ServletContext.class },
			(proxy, method, args) -> {
				if ("getInitParameter".equals(method.getName()) && args != null && args.length == 1) {
					String key = (String) args[0];
					if ("sd.server".equals(key)) return sdServer;
					if ("sd.server.apiType".equals(key)) return sdApiType;
					return null;
				}
				Class<?> rt = method.getReturnType();
				if (rt == boolean.class) return false;
				if (rt == int.class) return 0;
				if (rt == long.class) return 0L;
				return null;
			}
		);
	}

	@Override
	@Before
	public void setup() {
		// Point at the SAME live dev Postgres DB the other real (non-fake) PictureBook coverage
		// uses (AccountManagerObjects7/src/test/resources/resource.properties) — Service7's own
		// test resource.properties on this module's classpath points at the unrelated ISO
		// 42001 test DB (am7isotestdb), which has nothing to do with this feature.
		if (liveProps == null) {
			liveProps = new Properties();
			File objectsProps = new File("../AccountManagerObjects7/src/test/resources/resource.properties");
			try (InputStream is = objectsProps.exists()
					? new FileInputStream(objectsProps)
					: ClassLoader.getSystemResourceAsStream("./resource.properties")) {
				liveProps.load(is);
			} catch (IOException e) {
				throw new RuntimeException("Failed to load live resource.properties", e);
			}
		}
		OlioModelNames.use();
		organizationPath = "/Development";

		IOProperties props = new IOProperties();
		props.setDataSourceUrl(liveProps.getProperty("test.db.url"));
		props.setDataSourceUserName(liveProps.getProperty("test.db.user"));
		props.setDataSourcePassword(liveProps.getProperty("test.db.password"));
		props.setSchemaCheck(false);
		props.setReset(false); // NEVER reset the schema
		resetIO(RecordIO.DATABASE, props);
		ServiceUtil.clearCache();
	}

	private BaseRecord getOrCreateChatConfig(BaseRecord user, String name) {
		BaseRecord existing = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
		if (existing != null) return existing;
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			cfg.set("serviceType", LLMServiceEnumType.OLLAMA);

			BaseRecord conn = DocumentUtil.getRecord(user, ModelNames.MODEL_CONNECTION, name + " Connection", "~/Chat");
			if (conn == null) {
				ParameterList cplist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
				cplist.parameter(FieldNames.FIELD_NAME, name + " Connection");
				conn = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CONNECTION, user, null, cplist);
				conn.set("serverUrl", liveProps.getProperty("test.llm.ollama.server", "http://192.168.1.42:11434"));
				conn.set("requestTimeout", 180);
				conn = IOSystem.getActiveContext().getAccessPoint().create(user, conn);
			}
			cfg.set("connection", conn);
			cfg.set("model", LLM_MODEL);
			cfg.set("stream", false);

			BaseRecord opts = cfg.get("chatOptions");
			if (opts == null) {
				opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
				cfg.set("chatOptions", opts);
			}
			opts.set("think", false);

			return IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
		} catch (Exception e) {
			logger.error("Failed to create chat config: " + e.getMessage(), e);
			return null;
		}
	}

	/** Creates the "work" source record (data.data, text/plain) findWork()/extractWorkText() resolve. */
	private BaseRecord createWork(BaseRecord user, String name, String text) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		work.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
		work.set(FieldNames.FIELD_BYTE_STORE, text.getBytes());
		return IOSystem.getActiveContext().getAccessPoint().create(user, work);
	}

	private int countPortraitImages(BaseRecord user, long groupId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, "portrait_%");
		return IOSystem.getActiveContext().getAccessPoint().count(user, q);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parseJsonObject(String json) {
		Map<String, Object> m = JSONUtil.getMap(json.getBytes(), String.class, Object.class);
		return m != null ? m : new LinkedHashMap<>();
	}

	// A short two-scene story with a clearly-female character (Mira) and a deliberately
	// gender-ambiguous character (Ash — no gendered pronouns/cues at all) so createCharPerson()'s
	// gender clamp is exercised for BOTH a normal "female" LLM answer and an off-nominal one
	// (the extract-character prompt asks for "male|female|other" — "other" (or any other
	// free-text the model produces) must clamp to UNKNOWN, never abort character creation).
	private static final String STORY =
		"Chapter One: The Signal\n" +
		"Dr. Mira Kestrel crouched at the edge of the ridge, her short-cropped auburn hair whipping in " +
		"the wind as she adjusted the antenna dish bolted to the old radio tower. Beside her, the " +
		"engineer Ash Larkspur knelt over a tangle of cables, methodical and unhurried, checking each " +
		"connection twice before moving to the next. The valley below glowed faint blue under the " +
		"aurora, and somewhere out there, buried under decades of snow, the signal they had been " +
		"chasing for three years was finally answering back.\n\n" +
		"Chapter Two: The Descent\n" +
		"By dawn, Mira and Ash had rappelled halfway down the ice cliff toward the source of the " +
		"transmission. Mira led, testing each anchor point with the same care Ash used on the cables, " +
		"while Ash followed close behind, watching the rope for fraying. The signal grew louder as they " +
		"descended, a rhythmic pulse that neither of them could yet explain, but both were certain — " +
		"whatever waited at the bottom of the crevasse had been waiting a very long time.\n\n" +
		"Chapter Three: The Chamber\n" +
		"The crevasse opened into a hollow lit by the same pulsing blue glow. Mira steadied the rope while " +
		"Ash swept a headlamp across walls lined with strange metallic ridges. Neither spoke. The pulse " +
		"slowed, then stopped, as if the chamber itself had been waiting for them to arrive.";

	@Test
	public void TestPictureBookPersistenceE2E() throws Exception {
		assumeTrue("PICTUREBOOK_E2E not set — skipping live LLM/SD PictureBook E2E test", llmEnabled());
		logger.info("=== TestPictureBookPersistenceE2E ===");
		logger.info("Disk-emit directory for generated images: " + EMIT_DIR);

		String swarmServer = liveProps.getProperty("test.swarm.server", "http://192.168.1.42:7801");
		String swarmModel = liveProps.getProperty("test.swarm.model", "sdXL_v10VAEFix.safetensors");
		assertNotNull("test.llm.ollama.server must be set", liveProps.getProperty("test.llm.ollama.server"));

		// ---- Setup: non-admin test user (admin only provisions it) ----
		testOrgCtx = getTestOrganization(ORG_SUBPATH);
		orgId = testOrgCtx.getOrganizationId();
		Factory mf = IOSystem.getActiveContext().getFactory();
		testUser = mf.getCreateUser(testOrgCtx.getAdminUser(), "pbE2EUser", orgId);
		assertNotNull("Test user should be created", testUser);
		assertFalse("Actor must not be the admin user", "admin".equals(testUser.get(FieldNames.FIELD_NAME)));
		String testUserName = testUser.get(FieldNames.FIELD_NAME);

		BaseRecord chatConfig = getOrCreateChatConfig(testUser, "pbE2ETestConfig");
		assertNotNull("Chat config should be created", chatConfig);
		String chatConfigName = chatConfig.get(FieldNames.FIELD_NAME);

		String bookName = "PBE2E-" + System.currentTimeMillis();
		BaseRecord work = createWork(testUser, "pbE2EStory-" + System.currentTimeMillis() + ".txt", STORY);
		assertNotNull("Work record should be created", work);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Work objectId should not be null", workObjectId);

		HttpServletRequestMock request = new HttpServletRequestMock(new UserPrincipal(testUserName, "/Development/PictureBookE2E"));
		ServletContext servletContext = sdServletContext(swarmServer, "SWARM");

		// ==================================================================
		// Step 1: extract() — the REAL production entry point. LLM extracts scenes +
		// characters, createCharPerson() runs for real (gender clamp, persisted
		// profile + narrative) for each unique character.
		// ==================================================================
		String extractJson = "{\"count\":3,\"chatConfig\":\"" + chatConfigName + "\",\"bookName\":\"" + bookName + "\"}";
		Response extractResp = service.extract(workObjectId, extractJson, request);
		String extractBody = extractResp.getEntity() != null ? extractResp.getEntity().toString() : null;
		logger.info("extract() status=" + extractResp.getStatus() + " body(trunc)=" +
			(extractBody != null ? extractBody.substring(0, Math.min(600, extractBody.length())) : "null"));
		assertEquals("extract() must return 200 — body: " + extractBody, 200, extractResp.getStatus());
		assertNotNull("extract() response body should not be null", extractBody);

		Map<String, Object> meta = parseJsonObject(extractBody);
		String bookObjectId = (String) meta.get("bookObjectId");
		assertNotNull("meta.bookObjectId should not be null", bookObjectId);
		List<Object> failedCharacters = (List<Object>) meta.getOrDefault("failedCharacters", new ArrayList<>());
		logger.info("failedCharacters after extract(): " + failedCharacters);
		assertTrue("createCharPerson() must not silently fail for any extracted character (failedCharacters="
			+ failedCharacters + ") — this is exactly the regression the gender-clamp/persisted-profile fix addresses",
			failedCharacters.isEmpty());

		List<Map<String, Object>> scenes = (List<Map<String, Object>>) meta.get("scenes");
		assertNotNull("meta.scenes should not be null", scenes);
		assertTrue("extract() should have produced at least 2 scenes to test portrait reuse across scenes; got "
			+ scenes.size(), scenes.size() >= 2);

		// Find two scenes that share at least one character (objectIds, per buildSceneEntry()).
		String sharedCharOid = null;
		Map<String, Object> sceneA = null, sceneB = null;
		outer:
		for (int i = 0; i < scenes.size(); i++) {
			List<String> ci = (List<String>) scenes.get(i).getOrDefault("characters", new ArrayList<>());
			for (int j = i + 1; j < scenes.size(); j++) {
				List<String> cj = (List<String>) scenes.get(j).getOrDefault("characters", new ArrayList<>());
				for (String c : ci) {
					if (cj.contains(c)) { sharedCharOid = c; sceneA = scenes.get(i); sceneB = scenes.get(j); break outer; }
				}
			}
		}
		assertNotNull("Expected at least two scenes sharing a character (story was written so every scene "
			+ "includes both Mira and Ash) — scenes: " + scenes, sharedCharOid);
		logger.info("Shared character objectId across two scenes: " + sharedCharOid);

		// ==================================================================
		// Step 2: FRESH DB verification of EVERY character produced by extract() —
		// new Query + AccessPoint.find(), not the in-memory refs from createCharPerson().
		// ==================================================================
		java.util.Set<String> allCharOids = new java.util.LinkedHashSet<>();
		for (Map<String, Object> sc : scenes) {
			List<String> cs = (List<String>) sc.getOrDefault("characters", new ArrayList<>());
			allCharOids.addAll(cs);
		}
		assertFalse("Expected at least one character objectId across all scenes", allCharOids.isEmpty());
		logger.info("All character objectIds referenced across scenes: " + allCharOids);

		for (String charOid : allCharOids) {
			Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charOid);
			cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			cq.planMost(false);
			BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, cq);
			assertNotNull("charPerson " + charOid + " must exist in a FRESH query", cp);

			String gender = cp.get("gender");
			assertNotNull("gender must not be null for " + charOid, gender);
			assertTrue("gender must be clamped to MALE/FEMALE/UNKNOWN (was: '" + gender + "') for " + charOid,
				gender.equals("MALE") || gender.equals("FEMALE") || gender.equals("UNKNOWN"));
			assertTrue("gender must fit the maxLength:10 field (was: '" + gender + "')", gender.length() <= 10);

			IOSystem.getActiveContext().getReader().populate(cp, new String[] { "narrative" });
			BaseRecord narrative = cp.get("narrative");
			assertNotNull("narrative must be persisted (not an in-memory placeholder) for " + charOid, narrative);
			Long narrId = narrative.get(FieldNames.FIELD_ID);
			assertNotNull("narrative.id must be populated for " + charOid, narrId);
			assertTrue("narrative.id must be a real positive persisted id for " + charOid + " (was " + narrId + ")", narrId > 0L);
			String sdPrompt = narrative.get("sdPrompt");
			assertNotNull("narrative.sdPrompt must not be null for " + charOid, sdPrompt);
			assertFalse("narrative.sdPrompt must not be empty for " + charOid, sdPrompt.isBlank());

			IOSystem.getActiveContext().getReader().populate(cp, new String[] { "profile" });
			BaseRecord profile = cp.get("profile");
			assertNotNull("profile must be persisted (not an in-memory placeholder) for " + charOid, profile);
			Long profId = profile.get(FieldNames.FIELD_ID);
			assertNotNull("profile.id must be populated for " + charOid, profId);
			assertTrue("profile.id must be a real positive persisted id for " + charOid + " (was " + profId + ")", profId > 0L);

			logger.info("Verified charPerson " + charOid + " gender=" + gender + " narrative.id=" + narrId + " profile.id=" + profId);
		}

		// ==================================================================
		// Step 3: generateSceneImage() on sceneA — the REAL 4-stage pipeline
		// (portraits -> landscape -> stitch -> Flux Kontext composite) against the
		// live SwarmUI backend.
		// ==================================================================
		String sceneAOid = (String) sceneA.get("objectId");
		assertNotNull("sceneA objectId should not be null", sceneAOid);
		String genJson = "{\"chatConfig\":\"" + chatConfigName + "\",\"isBook\":true,"
			+ "\"sdConfig\":{\"steps\":14,\"refinerSteps\":10,\"cfg\":5,\"hires\":false,\"style\":\"illustration\",\"model\":\""
			+ swarmModel + "\"}}";

		long t0 = System.currentTimeMillis();
		Response genRespA = service.generateSceneImage(sceneAOid, genJson, request, servletContext);
		logger.info("generateSceneImage(sceneA) took " + (System.currentTimeMillis() - t0) + "ms");
		String genBodyA = genRespA.getEntity() != null ? genRespA.getEntity().toString() : null;
		logger.info("generateSceneImage(sceneA) status=" + genRespA.getStatus() + " body=" + genBodyA);
		assertEquals("generateSceneImage(sceneA) must return 200 — body: " + genBodyA, 200, genRespA.getStatus());

		Map<String, Object> resultA = parseJsonObject(genBodyA);
		List<Object> failedPortraitsA = (List<Object>) resultA.getOrDefault("failedPortraits", new ArrayList<>());
		logger.info("failedPortraits after sceneA generation: " + failedPortraitsA);
		assertTrue("No portrait should fail to link during sceneA generation (failedPortraits=" + failedPortraitsA + ")",
			failedPortraitsA.isEmpty());

		// ---- FRESH verification: sceneA's persisted landscape ----
		BaseRecord sceneANote = fetchSceneNote(sceneAOid);
		String landscapeOidA = readTextField(sceneANote, "landscapeObjectId");
		assertNotNull("sceneA.landscapeObjectId must be persisted on the scene note", landscapeOidA);
		byte[] landscapeBytesA = fetchDataBytes(landscapeOidA);
		assertNotNull("Landscape image for sceneA must still exist in a FRESH query", landscapeBytesA);
		assertTrue("Landscape image bytes for sceneA must be non-empty", landscapeBytesA.length > 0);
		FileUtil.emitFile(EMIT_DIR + "/verified_landscape_sceneA.png", landscapeBytesA);
		logger.info("sceneA landscape objectId=" + landscapeOidA + " bytes=" + landscapeBytesA.length);

		// ---- FRESH verification: shared character's portrait now exists ----
		BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser, ModelNames.MODEL_GROUP,
			"~/Data/PictureBooks/" + bookName + "/Characters", GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Characters group must exist", charsGroup);
		long charsGroupId = ((Number) charsGroup.get(FieldNames.FIELD_ID)).longValue();

		String portraitOidBefore = fetchPortraitObjectId(sharedCharOid);
		assertNotNull("Shared character must have a persisted+linked portrait after sceneA generation", portraitOidBefore);
		byte[] portraitBytesBefore = fetchDataBytes(portraitOidBefore);
		assertNotNull("Shared character's portrait bytes must be readable via ByteModelUtil in a FRESH query", portraitBytesBefore);
		assertTrue("Shared character's portrait bytes must be non-empty", portraitBytesBefore.length > 0);
		FileUtil.emitFile(EMIT_DIR + "/verified_portrait_shared_after_sceneA.png", portraitBytesBefore);
		int portraitCountBefore = countPortraitImages(testUser, charsGroupId, orgId);
		logger.info("Shared character portrait objectId=" + portraitOidBefore + " bytes=" + portraitBytesBefore.length
			+ " ; portrait_* count in Characters group=" + portraitCountBefore);

		// ==================================================================
		// Step 4: generateSceneImage() on sceneB — reuses the SAME shared character.
		// Must NOT regenerate/duplicate that character's portrait.
		// ==================================================================
		String sceneBOid = (String) sceneB.get("objectId");
		assertNotNull("sceneB objectId should not be null", sceneBOid);
		t0 = System.currentTimeMillis();
		Response genRespB = service.generateSceneImage(sceneBOid, genJson, request, servletContext);
		logger.info("generateSceneImage(sceneB) took " + (System.currentTimeMillis() - t0) + "ms");
		String genBodyB = genRespB.getEntity() != null ? genRespB.getEntity().toString() : null;
		logger.info("generateSceneImage(sceneB) status=" + genRespB.getStatus() + " body=" + genBodyB);
		assertEquals("generateSceneImage(sceneB) must return 200 — body: " + genBodyB, 200, genRespB.getStatus());

		Map<String, Object> resultB = parseJsonObject(genBodyB);
		List<Object> failedPortraitsB = (List<Object>) resultB.getOrDefault("failedPortraits", new ArrayList<>());
		logger.info("failedPortraits after sceneB generation: " + failedPortraitsB);
		assertTrue("No portrait should fail to link during sceneB generation (failedPortraits=" + failedPortraitsB + ")",
			failedPortraitsB.isEmpty());

		BaseRecord sceneBNote = fetchSceneNote(sceneBOid);
		String landscapeOidB = readTextField(sceneBNote, "landscapeObjectId");
		assertNotNull("sceneB.landscapeObjectId must be persisted on the scene note", landscapeOidB);
		byte[] landscapeBytesB = fetchDataBytes(landscapeOidB);
		assertNotNull("Landscape image for sceneB must still exist in a FRESH query", landscapeBytesB);
		assertTrue("Landscape image bytes for sceneB must be non-empty", landscapeBytesB.length > 0);
		assertFalse("sceneA and sceneB must have DISTINCT landscape images (each scene renders its own)",
			landscapeOidA.equals(landscapeOidB));
		FileUtil.emitFile(EMIT_DIR + "/verified_landscape_sceneB.png", landscapeBytesB);
		logger.info("sceneB landscape objectId=" + landscapeOidB + " bytes=" + landscapeBytesB.length);

		// ---- THE reuse assertion: same portrait objectId, no duplicate record created ----
		String portraitOidAfter = fetchPortraitObjectId(sharedCharOid);
		assertNotNull("Shared character must still have a linked portrait after sceneB generation", portraitOidAfter);
		assertEquals("Shared character's portrait must be REUSED (same objectId), not regenerated",
			portraitOidBefore, portraitOidAfter);

		int portraitCountAfter = countPortraitImages(testUser, charsGroupId, orgId);
		assertEquals("No duplicate portrait record must be created when the same character reappears in a second scene",
			portraitCountBefore, portraitCountAfter);
		logger.info("Portrait reuse confirmed: objectId unchanged (" + portraitOidAfter + "), portrait_* count unchanged ("
			+ portraitCountAfter + ")");

		// List every emitted file for manual visual inspection.
		File emitDirFile = new File(EMIT_DIR);
		File[] emitted = emitDirFile.listFiles();
		logger.info("=== Emitted image files in " + EMIT_DIR + " ===");
		if (emitted != null) {
			java.util.Arrays.sort(emitted);
			for (File f : emitted) {
				logger.info("  " + f.getAbsolutePath() + " (" + f.length() + " bytes)");
			}
		}

		logger.info("=== TestPictureBookPersistenceE2E PASSED ===");
	}

	/** Fresh query for the scene's data.note by objectId. */
	private BaseRecord fetchSceneNote(String sceneObjectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(false);
		BaseRecord note = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("Scene note " + sceneObjectId + " must exist in a FRESH query", note);
		return note;
	}

	/** Reads a key out of a data.note's JSON "text" blob (the pattern PictureBookService.updateSceneTextField uses). */
	private String readTextField(BaseRecord note, String key) {
		String text = note.get("text");
		if (text == null || text.isEmpty()) return null;
		Map<String, Object> m = parseJsonObject(text);
		Object v = m.get(key);
		return v != null ? v.toString() : null;
	}

	/** Fresh query for a data.data record's byte payload via ByteModelUtil (not a raw .get()). */
	private byte[] fetchDataBytes(String dataObjectId) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, dataObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(false);
		BaseRecord data = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		if (data == null) return null;
		IOSystem.getActiveContext().getReader().populate(data, new String[] { FieldNames.FIELD_BYTE_STORE });
		return ByteModelUtil.getValue(data);
	}

	/** Fresh query for a charPerson's linked portrait objectId (via profile.portrait). */
	private String fetchPortraitObjectId(String charObjectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(false);
		BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("charPerson " + charObjectId + " must exist", cp);
		IOSystem.getActiveContext().getReader().populate(cp, new String[] { "profile" });
		BaseRecord profile = cp.get("profile");
		if (profile == null) return null;
		IOSystem.getActiveContext().getReader().populate(profile, new String[] { "portrait" });
		BaseRecord portrait = profile.get("portrait");
		if (portrait == null) return null;
		return portrait.get(FieldNames.FIELD_OBJECT_ID);
	}
}
