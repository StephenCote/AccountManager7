package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.SceneGenerationParams;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.FileUtil;
import org.junit.Test;

/**
 * Real end-to-end test of {@link PictureBookUtil} — the Objects7 home for the PictureBook
 * business logic that was just extracted out of Service7's {@code PictureBookService}. Calls
 * the ACTUAL shipped static methods directly (no HTTP transport, no
 * {@code HttpServletRequest}/{@code ServletContext}/{@code UserPrincipal}, no
 * {@code java.lang.reflect.Proxy} mocking of any kind — none is possible or necessary since
 * {@code PictureBookUtil} takes a plain {@code BaseRecord user} and plain-string params), against
 * the live Postgres DB, the live Ollama LLM at 192.168.1.42, and the live SwarmUI SD backend.
 *
 * <p>This replaces {@code AccountManagerService7/src/test/java/.../TestPictureBookServiceE2E.java}
 * (deleted alongside this test), which used a {@code Proxy}-faked {@code ServletContext} to call
 * {@code PictureBookService} — an approach Stephen explicitly rejected ("It seems like you're
 * spending a lot of time mocking the rest service, which I'd previously said I prefer not to
 * do... just run the test out of Objects7 or the Ux"). Now that the business logic lives in
 * Objects7's {@code PictureBookUtil} (plain static methods, {@code BaseRecord}/String params
 * only), it can and must be exercised directly from an Objects7 JUnit test with zero mocking.
 *
 * <p>This test deliberately does NOT re-implement any of {@code PictureBookUtil}'s logic (gender
 * clamp, persisted profile/narrative creation, landscape retention, isBook flag,
 * failedCharacters/failedPortraits reporting). Every assertion below re-queries the live DB with a
 * FRESH {@code Query}/{@code AccessPoint.find()} — never a reused in-memory reference from the call
 * that created the data.
 *
 * <p><b>SD-image proof mechanism (see {@link #assertDecodableImage(String, byte[])}):</b> this test
 * proves the portrait/landscape/composite requests actually reached and were fulfilled by the live
 * SwarmUI backend by round-tripping the persisted bytes through {@code javax.imageio.ImageIO.read()}
 * and asserting a real, non-trivial-dimension image decodes. This codebase has no existing wrapper
 * for a SwarmUI generation-history/gallery API (checked {@code SDUtil}, {@code SWUtil},
 * {@code Auto1111Util} — the only SwarmUI endpoints wrapped anywhere are
 * {@code /API/GenerateText2Image}, {@code /API/ListModels}, and {@code /API/GetNewSession}).
 * Calling an unwrapped, unverified SwarmUI endpoint directly from a test would itself be a form of
 * fabrication risk (guessing at a schema/behavior nobody has confirmed). By contrast, nothing in the
 * AM7 pipeline synthesizes image bytes anywhere — {@code SDUtil.createImage}/{@code createSceneImage}
 * only ever populate {@code data.data.byteStore} from SwarmUI's own HTTP response
 * ({@code SWImageResponse} -> base64 decode). A byte array that ImageIO decodes into a real
 * multi-pixel {@code BufferedImage} is therefore direct, checkable proof that SwarmUI generated and
 * returned actual image data for that request; a broken/unreachable SwarmUI would instead show up as
 * either a thrown {@code PictureBookException} (transport failure) or non-decodable bytes here — both
 * of which this test surfaces as failures, not silently swallowed passes.
 *
 * <p>GATING: this test hits live LLM + SD backends and must never run by default or in parallel. It
 * follows this repo's existing convention (see {@code TestPageIndex}'s PAGEINDEX_LLM env var and the
 * retired {@code TestPictureBookServiceE2E}'s PICTUREBOOK_E2E env var): set the
 * {@code PICTUREBOOK_E2E} environment variable to enable, and always run single-threaded:
 *
 * <pre>
 *   PICTUREBOOK_E2E=1 mvn -o -pl AccountManagerObjects7 -Dtest=TestPictureBookUtilE2E -DskipTests=false test
 * </pre>
 *
 * <p>Never uses the admin user as the acting caller — admin is used only to provision the non-admin
 * test user, mirroring {@code ensureSharedTestUser()}/{@code TestPortraitReuse}'s Java convention
 * ({@code Factory.getCreateUser(adminUser, name, orgId)} then acting AS that non-admin user for every
 * subsequent call).
 *
 * <p>Created records (charPersons, portraits, landscapes, composites, book/scene notes) are
 * deliberately NOT deleted — the entire point is verifying persistence survives past the call that
 * created it, and Stephen asked that DB state not be reset/torn down by these tests.
 */
public class TestPictureBookUtilE2E extends BaseTest {

	private static final String EMIT_DIR;
	static {
		// Test-side-only disk emission for manual visual inspection: PictureBookUtil has zero
		// knowledge of this directory. The test independently re-fetches persisted
		// portraits/landscapes/composites via the normal query/resolution path (the same path the
		// Ux uses to resolve profile.portrait / scene.landscapeObjectId / scene.imageObjectId),
		// reads bytes via ByteModelUtil.getValue(), and writes them here from test code only —
		// never from production code (PictureBookUtil).
		String dir = new File("./target/test-images/pictureBook-e2e/" + System.currentTimeMillis()).getAbsolutePath();
		new File(dir).mkdirs();
		EMIT_DIR = dir;
		System.out.println("[TestPictureBookUtilE2E] EMIT_DIR = " + EMIT_DIR);
	}

	private static final String LLM_MODEL = "qwen3-vl:8b-instruct";
	private static final String ORG_SUBPATH = "/Development/PictureBookUtilE2E";

	/**
	 * Matches the same class of LLM "I don't know" placeholder tokens that
	 * {@code NarrativeUtil.isMeaningful()} (the fix under regression check here) guards against
	 * (case-insensitive, whole-word only — e.g. does NOT match "annulled", "nonexistent", etc.,
	 * because {@code \b} requires a non-word boundary on both sides of the token).
	 */
	private static final Pattern PLACEHOLDER_LEAK_PATTERN =
		Pattern.compile("(?i)\\b(null|n/a|none|unknown|unspecified)\\b");

	/**
	 * Asserts a built SD prompt string does not leak a literal LLM placeholder token (the exact bug
	 * Stephen found by inspecting a real generated prompt: {@code "a null null null woman with ...
	 * null eyes"}). Whole-word match only, so this cannot false-positive on a legitimate substring.
	 */
	private void assertNoPlaceholderLeak(String label, String promptText) {
		assertNotNull(label + ": prompt text must not be null", promptText);
		Matcher m = PLACEHOLDER_LEAK_PATTERN.matcher(promptText);
		StringBuilder found = new StringBuilder();
		while (m.find()) {
			if (found.length() > 0) found.append(", ");
			found.append("'").append(m.group()).append("' at index ").append(m.start());
		}
		assertTrue(label + ": prompt text leaked LLM placeholder token(s) (" + found
			+ ") — full text: \"" + promptText + "\"", found.length() == 0);
	}

	/**
	 * Reconstructs the LITERAL Stage-4 Flux Kontext composite prompt string that
	 * {@code PictureBookUtil.generateSceneImage()} actually sent to the live SwarmUI backend for this
	 * scene, by calling the same REAL, public, pure production method it calls internally —
	 * {@code SWUtil.newKontextSceneTxt2Img(...)} — with the exact same freshly-persisted inputs
	 * generateSceneImage() itself used:
	 * <ul>
	 *   <li>{@code leftDesc}/{@code rightDesc}: the first two scene characters' persisted
	 *       {@code narrative.sdPrompt} (captured fresh from the DB in Step 2 above, via
	 *       {@code narrativeSdPromptByCharOid}), run through the same {@code SWUtil.stripSDXLWeighting()}
	 *       generateSceneImage() applies — this is exactly the string that leaked the literal "null"
	 *       tokens Stephen found before the {@code isMeaningful()} fix.</li>
	 *   <li>{@code action}/{@code setting}/{@code mood}: read from the scene note's own persisted
	 *       "text" JSON blob (the same field/source generateSceneImage() itself parses), via this
	 *       test's existing {@code readTextField()} helper — not reimplemented parsing logic.</li>
	 * </ul>
	 * generateSceneImage() does not return this literal string (its result's "prompt" field is only
	 * the {@code action + " " + setting} summary), so this is the only way to inspect the actual text
	 * without adding test-only mocking. Since {@code SWUtil.newKontextSceneTxt2Img} is a pure function
	 * of its arguments, calling it here with DB-verified real inputs reconstructs it byte-for-byte.
	 */
	private String logAndVerifyCompositePrompt(String label, BaseRecord scene, BaseRecord sceneNote,
			String style, Map<String, String> narrativeSdPromptByCharOid) {
		List<String> charOids = scene.get("characters");
		assertNotNull(label + ": scene.characters must not be null", charOids);
		String leftDesc = charOids.size() > 0
			? SWUtil.stripSDXLWeighting(narrativeSdPromptByCharOid.get(charOids.get(0))) : "";
		String rightDesc = charOids.size() > 1
			? SWUtil.stripSDXLWeighting(narrativeSdPromptByCharOid.get(charOids.get(1))) : "";
		String action = readTextField(sceneNote, "action");
		String setting = readTextField(sceneNote, "setting");
		String mood = readTextField(sceneNote, "mood");
		SWTxt2Img kontextReq = SWUtil.newKontextSceneTxt2Img(leftDesc, rightDesc, action, setting, style, mood, null);
		String compositePrompt = kontextReq.getPrompt();
		logger.info("=== " + label + ": actual Kontext composite prompt sent to SD (Stage 4) ===");
		logger.info(compositePrompt);
		assertNotNull(label + ": reconstructed Kontext composite prompt must not be null", compositePrompt);
		assertFalse(label + ": reconstructed Kontext composite prompt must not be blank", compositePrompt.isBlank());
		assertNoPlaceholderLeak(label + " Kontext composite prompt", compositePrompt);
		return compositePrompt;
	}

	/**
	 * Reconstructs and emits the LITERAL Stage-3 stitched reference image that
	 * {@code PictureBookUtil.generateSceneImage()} builds internally and sends as the single Kontext
	 * {@code promptImage} — {@code [leftPortrait | centerPortrait | landscape]} — by calling the same
	 * REAL, public, pure production method it calls internally, {@code SDUtil.stitchSceneImages()},
	 * with the exact same freshly-persisted inputs generateSceneImage() itself used: the scene's
	 * first two characters' own persisted portrait bytes (resolved via the same
	 * {@code profile.portrait} path as {@link #fetchPortraitObjectId(String)}/
	 * {@link #fetchDataBytes(String)}) and the scene's own persisted landscape bytes.
	 *
	 * <p>{@code generateSceneImage()} never exposes, persists, or returns this intermediate image —
	 * it only exists inside the local {@code refComposite} variable of the Stage 3/4 method body —
	 * so this reconstruction (mirroring how {@link #logAndVerifyCompositePrompt} reconstructs the
	 * prompt text) is the only way to inspect it without adding test-only instrumentation to
	 * production code. This directly answers Stephen's "I can't tell if what you're sending for the
	 * final image is even valid" concern: if the stitched reference itself is malformed/blank/
	 * wrong-order, that — not Kontext itself — would explain a bad final composite.
	 */
	private byte[] reconstructAndEmitStitchedReference(String label, BaseRecord scene, byte[] landscapeBytes) throws Exception {
		List<String> charOids = scene.get("characters");
		assertNotNull(label + ": scene.characters must not be null", charOids);
		byte[] leftBytes = null;
		byte[] centerBytes = null;
		if (charOids.size() > 0) {
			String leftOid = fetchPortraitObjectId(charOids.get(0));
			if (leftOid != null) leftBytes = fetchDataBytes(leftOid);
		}
		if (charOids.size() > 1) {
			String centerOid = fetchPortraitObjectId(charOids.get(1));
			if (centerOid != null) centerBytes = fetchDataBytes(centerOid);
		}
		logger.info(label + ": stitching reference from leftBytes=" + (leftBytes != null ? leftBytes.length : 0)
			+ " centerBytes=" + (centerBytes != null ? centerBytes.length : 0)
			+ " landscapeBytes=" + (landscapeBytes != null ? landscapeBytes.length : 0));
		byte[] stitched = SDUtil.stitchSceneImages(
			leftBytes != null ? leftBytes : landscapeBytes,
			centerBytes != null ? centerBytes : landscapeBytes,
			landscapeBytes, 1024);
		assertDecodableImage(label + " stitched Kontext reference [left|center|landscape]", stitched);
		FileUtil.emitFile(EMIT_DIR + "/verified_stitchedReference_" + label + ".png", stitched);
		logger.info(label + ": emitted stitched reference (" + (stitched != null ? stitched.length : 0) + " bytes)");
		return stitched;
	}

	private OrganizationContext testOrgCtx;
	private BaseRecord testUser;
	private long orgId;

	private static boolean llmEnabled() {
		return System.getenv("PICTUREBOOK_E2E") != null;
	}

	// A short three-chapter story with a clearly-female character (Mira) and a deliberately
	// gender-ambiguous character (Ash — no gendered pronouns/cues at all) so createCharPerson()'s
	// gender clamp (normalizeGender(): raw LLM value -> exactly MALE/FEMALE/UNKNOWN, never any
	// other string) is incidentally re-exercised for both a normal "female" LLM answer and an
	// off-nominal one. Every scene includes both characters so at least two scenes should share a
	// character (needed for the portrait-reuse check).
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

	private BaseRecord getOrCreateChatConfig(BaseRecord user, String name) {
		BaseRecord existing = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
		if (existing != null) return existing;
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
			cfg.set("connection", OlioTestUtil.getCreateConnection(user, name + " Connection",
				testProperties.getProperty("test.llm.ollama.server", "http://192.168.1.42:11434"), null, 180));
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

	private int countPortraitImages(BaseRecord user, long groupId, long organizationId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, "portrait_%");
		return IOSystem.getActiveContext().getAccessPoint().count(user, q);
	}

	/** Fresh query for the scene's data.note by objectId. */
	private BaseRecord fetchSceneNote(String sceneObjectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(false);
		// cache:false — see fetchPortraitObjectId()'s javadoc: CacheDBSearch.clearCache(BaseRecord)
		// only invalidates cache entries whose TOP-LEVEL cached result matches the updated record's
		// own schema+identity; it does NOT reach into other models' cached results that merely
		// embed that record as a nested foreign field. Without cache:false here, a query shape
		// already cached earlier in this same test run (before PictureBookUtil's own writes) would
		// be returned verbatim, defeating the "FRESH query" requirement.
		q.setCache(false);
		BaseRecord note = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		assertNotNull("Scene note " + sceneObjectId + " must exist in a FRESH query", note);
		return note;
	}

	/** Reads a key out of a data.note's JSON "text" blob (PictureBookUtil.updateSceneTextField's format). */
	@SuppressWarnings("unchecked")
	private String readTextField(BaseRecord note, String key) {
		String text = note.get("text");
		if (text == null || text.isEmpty()) return null;
		java.util.Map<String, Object> m = org.cote.accountmanager.util.JSONUtil.getMap(text.getBytes(), String.class, Object.class);
		if (m == null) return null;
		Object v = m.get(key);
		return v != null ? v.toString() : null;
	}

	/** Fresh query for a data.data record's byte payload via ByteModelUtil (not a raw .get()). */
	private byte[] fetchDataBytes(String dataObjectId) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, dataObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(false);
		q.setCache(false); // see fetchPortraitObjectId() javadoc — force a genuinely fresh read
		BaseRecord data = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		if (data == null) return null;
		IOSystem.getActiveContext().getReader().populate(data, new String[] { FieldNames.FIELD_BYTE_STORE });
		return ByteModelUtil.getValue(data);
	}

	/**
	 * Fresh query for a charPerson's linked portrait objectId (via profile.portrait).
	 *
	 * <p><b>cache:false is required here, not optional.</b> {@code CacheDBSearch.clearCache(BaseRecord)}
	 * (invoked after {@code AccessPoint.update()}) only invalidates cache entries whose TOP-LEVEL
	 * cached result matches the updated record's own schema+identity — it never walks into a
	 * DIFFERENT model's already-cached result to invalidate that record where it appears merely as
	 * a nested foreign field. Concretely: an earlier {@code olio.charPerson} query in this same test
	 * run (same objectId/orgId/{@code planMost(false)} shape) gets cached with the charPerson's
	 * nested {@code profile} sub-record in its PRE-portrait state; PictureBookUtil's later
	 * {@code identity.profile} PATCH only invalidates {@code identity.profile}-typed cache entries,
	 * so re-running the exact same {@code olio.charPerson} query later returns the STALE cached
	 * instance (and {@code RecordReader.populate()}'s own per-instance "already populated"
	 * memoization then skips re-fetching profile/portrait on that same stale instance). Confirmed by
	 * running this test without {@code cache:false}: the assertion below failed even though
	 * PictureBookUtil's own log line ("Persisted+linked portrait for ...") proved the portrait WAS
	 * correctly linked in the DB at that moment — the staleness was in this test's own repeated
	 * query, not in PictureBookUtil.
	 */
	private String fetchPortraitObjectId(String charObjectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(false);
		q.setCache(false);
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

	/**
	 * SD-image proof: asserts the given bytes decode into a real, non-trivial image via
	 * {@code javax.imageio.ImageIO}. See the class javadoc for why this is the chosen proof
	 * mechanism (no wrapped SwarmUI history/gallery API exists anywhere in this codebase).
	 */
	private BufferedImage assertDecodableImage(String label, byte[] bytes) throws Exception {
		assertNotNull(label + ": bytes must not be null", bytes);
		assertTrue(label + ": bytes must be non-empty", bytes.length > 0);
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
		assertNotNull(label + ": ImageIO must decode a real image from the persisted bytes "
			+ "(non-decodable bytes would mean SwarmUI never actually returned image data)", img);
		assertTrue(label + ": decoded image width must be > 0 (was " + img.getWidth() + ")", img.getWidth() > 0);
		assertTrue(label + ": decoded image height must be > 0 (was " + img.getHeight() + ")", img.getHeight() > 0);
		logger.info(label + ": decoded " + img.getWidth() + "x" + img.getHeight() + " image from " + bytes.length + " persisted bytes");
		return img;
	}

	@Test
	@SuppressWarnings("unchecked")
	public void TestPictureBookUtilPersistenceE2E() throws Exception {
		assumeTrue("PICTUREBOOK_E2E not set — skipping live LLM/SD PictureBook E2E test", llmEnabled());
		logger.info("=== TestPictureBookUtilPersistenceE2E ===");
		logger.info("Disk-emit directory for generated images: " + EMIT_DIR);

		// System property (-Dtest.swarm.server=...) takes precedence over the checked-in
		// resource.properties value so this test can be pointed at a reachable SwarmUI instance
		// without editing shared test config (resource.properties already carries an unrelated
		// local WIP edit — see git status — pointing test.swarm.server at an address that is not
		// reachable from this environment).
		String swarmServer = System.getProperty("test.swarm.server",
			testProperties.getProperty("test.swarm.server", "http://192.168.1.42:7801"));
		String swarmModel = testProperties.getProperty("test.swarm.model", "sdXL_v10VAEFix.safetensors");
		assertNotNull("test.llm.ollama.server must be set", testProperties.getProperty("test.llm.ollama.server"));

		// ---- Setup: non-admin test user (admin only provisions it) ----
		testOrgCtx = getTestOrganization(ORG_SUBPATH);
		orgId = testOrgCtx.getOrganizationId();
		Factory mf = IOSystem.getActiveContext().getFactory();
		testUser = mf.getCreateUser(testOrgCtx.getAdminUser(), "pbUtilE2EUser", orgId);
		assertNotNull("Test user should be created", testUser);
		assertFalse("Actor must not be the admin user", "admin".equals(testUser.get(FieldNames.FIELD_NAME)));

		BaseRecord chatConfig = getOrCreateChatConfig(testUser, "pbUtilE2ETestConfig");
		assertNotNull("Chat config should be created", chatConfig);
		String chatConfigName = chatConfig.get(FieldNames.FIELD_NAME);

		String bookName = "PBUtilE2E-" + System.currentTimeMillis();
		BaseRecord work = createWork(testUser, "pbUtilE2EStory-" + System.currentTimeMillis() + ".txt", STORY);
		assertNotNull("Work record should be created", work);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Work objectId should not be null", workObjectId);

		// ==================================================================
		// Step 1: PictureBookUtil.extract() — the REAL production entry point, called directly.
		// LLM extracts scenes + characters; createCharPerson() runs for real (gender clamp,
		// persisted profile + narrative) for each unique character.
		// ==================================================================
		BaseRecord meta;
		try {
			meta = PictureBookUtil.extract(testUser, workObjectId, 3, chatConfigName, "sci-fi", bookName);
		} catch (PictureBookException pbe) {
			fail("PictureBookUtil.extract() threw PictureBookException(status=" + pbe.getStatus()
				+ ", message=" + pbe.getMessage() + ")");
			return;
		}
		assertNotNull("extract() must return a non-null pictureBookMeta", meta);
		String bookObjectId = meta.get("bookObjectId");
		assertNotNull("meta.bookObjectId should not be null", bookObjectId);

		List<String> failedCharacters = meta.get("failedCharacters");
		logger.info("failedCharacters after extract(): " + failedCharacters);
		assertTrue("createCharPerson() must not silently fail for any extracted character (failedCharacters="
			+ failedCharacters + ")", failedCharacters == null || failedCharacters.isEmpty());

		List<BaseRecord> scenes = meta.get("scenes");
		assertNotNull("meta.scenes should not be null", scenes);
		assertTrue("extract() should have produced at least 2 scenes to test portrait reuse across scenes; got "
			+ scenes.size(), scenes.size() >= 2);

		// Find two scenes that share at least one character (objectIds, per buildSceneEntry()).
		String sharedCharOid = null;
		BaseRecord sceneA = null, sceneB = null;
		outer:
		for (int i = 0; i < scenes.size(); i++) {
			List<String> ci = scenes.get(i).get("characters");
			if (ci == null) continue;
			for (int j = i + 1; j < scenes.size(); j++) {
				List<String> cj = scenes.get(j).get("characters");
				if (cj == null) continue;
				for (String c : ci) {
					if (cj.contains(c)) { sharedCharOid = c; sceneA = scenes.get(i); sceneB = scenes.get(j); break outer; }
				}
			}
		}
		assertNotNull("Expected at least two scenes sharing a character (story was written so every scene "
			+ "includes both Mira and Ash)", sharedCharOid);
		logger.info("Shared character objectId across two scenes: " + sharedCharOid);

		// ==================================================================
		// Step 2: FRESH DB verification of EVERY character produced by extract() — a new
		// Query + AccessPoint.find() per character, never the in-memory refs from createCharPerson().
		// ==================================================================
		Set<String> allCharOids = new LinkedHashSet<>();
		for (BaseRecord sc : scenes) {
			List<String> cs = sc.get("characters");
			if (cs != null) allCharOids.addAll(cs);
		}
		assertFalse("Expected at least one character objectId across all scenes", allCharOids.isEmpty());
		logger.info("All character objectIds referenced across scenes: " + allCharOids);

		// Per-character sdPrompt (the literal string built by NarrativeUtil.buildPortraitPromptFromExtractedData()
		// from the LLM's raw extraction) — captured here from FRESH DB reads so it can be (a) inspected/logged
		// per character below, and (b) reused later to reconstruct the actual Kontext composite prompt text
		// (Stage 4) via the REAL production SWUtil.newKontextSceneTxt2Img() method, not reimplemented logic.
		Map<String, String> narrativeSdPromptByCharOid = new LinkedHashMap<>();
		// charOid -> character name, so later artifacts (portrait PNGs, prompt txt files) can be
		// named unambiguously per character rather than by objectId.
		Map<String, String> charNameByCharOid = new LinkedHashMap<>();

		// FINDING (Stephen's "char stats" ask): investigated whether the raw LLM-extracted character
		// JSON (gender, age_approx, physical.{height,build,hair,eyes,skin,distinguishing},
		// clothing_style, outfit_notes, personality_hint — the `charData` map parsed from the
		// "pictureBook.extract-character" LLM call) is persisted or exposed ANYWHERE queryable after
		// extract() returns. It is NOT: PictureBookUtil.createCharPerson(user, charData, ...) takes
		// `charData` as a local method parameter, uses it exactly once to build a single flattened
		// prompt string via NarrativeUtil.buildPortraitPromptFromExtractedData(name, charData), and
		// then charData goes out of scope — it is never written to charPerson, never attached to
		// narrative (only narrative.sdPrompt/physicalDescription, the LOSSY rendered string, is
		// persisted), never stored in the scene note's JSON blob, and never returned in `meta`. So
		// there is no verified_charData_<name>.json to emit here — the raw structured extraction is
		// genuinely unrecoverable independent of its lossy text rendering. Reporting this as a real
		// gap per instruction, not working around it with new production persistence.
		logger.info("FINDING: raw LLM-extracted charData (gender/age/build/hair/eyes/skin/outfit_notes) is "
			+ "NOT persisted or exposed anywhere after PictureBookUtil.extract() returns — only its lossy "
			+ "narrative.sdPrompt text rendering survives. See createCharPerson()'s local `charData` param.");

		for (String charOid : allCharOids) {
			Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, charOid);
			cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			cq.planMost(false);
			// cache:false — this exact query shape (objectId/orgId/planMost(false)) is re-run later
			// by fetchPortraitObjectId() for the shared character; without disabling cache here too,
			// THIS read would seed the stale cache entry that later staleness note documents.
			cq.setCache(false);
			BaseRecord cp = IOSystem.getActiveContext().getAccessPoint().find(testUser, cq);
			assertNotNull("charPerson " + charOid + " must exist in a FRESH query", cp);

			// Gender-clamp re-confirmation (incidental to this test's persistence focus, but this
			// story deliberately includes a clearly-female character (Mira) and a gender-ambiguous
			// one (Ash) to exercise normalizeGender()'s clamp to MALE/FEMALE/UNKNOWN for both a
			// normal and an off-nominal LLM answer).
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
			// Log the ACTUAL LLM-derived prompt text so a human can visually double-check it, and
			// regression-check the specific bug Stephen found (literal "null" tokens leaking into the
			// prompt when buildPortraitPromptFromExtractedData()'s isMeaningful() guard fails to catch
			// an LLM placeholder). This is real LLM extraction output, not the unit test's synthetic
			// literal-"null" input, so it also tells us whether this run organically produced any
			// placeholder-style field values at all.
			logger.info("narrative.sdPrompt for '" + cp.get(FieldNames.FIELD_NAME) + "' (" + charOid + "): " + sdPrompt);
			assertNoPlaceholderLeak("narrative.sdPrompt for " + charOid, sdPrompt);
			narrativeSdPromptByCharOid.put(charOid, sdPrompt);
			String charNameForFile = cp.get(FieldNames.FIELD_NAME);
			charNameByCharOid.put(charOid, charNameForFile);
			// Write the actual prompt text to a file next to the images — log lines require digging
			// through Maven output; a .txt file sits right next to the emitted PNGs for direct review.
			FileUtil.emitFile(EMIT_DIR + "/verified_narrativeSdPrompt_" + charNameForFile + ".txt", sdPrompt);

			IOSystem.getActiveContext().getReader().populate(cp, new String[] { "profile" });
			BaseRecord profile = cp.get("profile");
			assertNotNull("profile must be persisted (not an in-memory placeholder) for " + charOid, profile);
			Long profId = profile.get(FieldNames.FIELD_ID);
			assertNotNull("profile.id must be populated for " + charOid, profId);
			assertTrue("profile.id must be a real positive persisted id for " + charOid + " (was " + profId + ")", profId > 0L);

			String name = cp.get(FieldNames.FIELD_NAME);
			logger.info("Verified charPerson '" + name + "' (" + charOid + ") gender=" + gender
				+ " narrative.id=" + narrId + " profile.id=" + profId);
		}

		// ==================================================================
		// Step 3: PictureBookUtil.generateSceneImage() on sceneA — the REAL 4-stage pipeline
		// (portraits -> landscape -> stitch -> Flux Kontext composite) against the live SwarmUI
		// backend, called directly with plain sdApiType/sdServer strings (no ServletContext).
		// ==================================================================
		String sceneAOid = sceneA.get("objectId");
		assertNotNull("sceneA objectId should not be null", sceneAOid);

		SceneGenerationParams paramsA = new SceneGenerationParams();
		paramsA.chatConfigName = chatConfigName;
		paramsA.steps = 14;
		paramsA.refinerSteps = 10;
		paramsA.cfg = 5;
		paramsA.hires = false;
		paramsA.style = "illustration";
		paramsA.sdModelName = swarmModel;
		paramsA.isBookOverride = true;

		long t0 = System.currentTimeMillis();
		BaseRecord resultA;
		try {
			resultA = PictureBookUtil.generateSceneImage(testUser, sceneAOid, paramsA, "SWARM", swarmServer);
		} catch (PictureBookException pbe) {
			fail("generateSceneImage(sceneA) threw PictureBookException(status=" + pbe.getStatus()
				+ ", message=" + pbe.getMessage() + ")");
			return;
		}
		logger.info("generateSceneImage(sceneA) took " + (System.currentTimeMillis() - t0) + "ms");
		assertNotNull("generateSceneImage(sceneA) must return a non-null pictureBookResult", resultA);

		List<String> failedPortraitsA = resultA.get("failedPortraits");
		logger.info("failedPortraits after sceneA generation: " + failedPortraitsA);
		assertTrue("No portrait should fail to link during sceneA generation (failedPortraits=" + failedPortraitsA + ")",
			failedPortraitsA == null || failedPortraitsA.isEmpty());

		String compositeOidA = resultA.get("imageObjectId");
		assertNotNull("sceneA composite imageObjectId should not be null", compositeOidA);
		byte[] compositeBytesA = fetchDataBytes(compositeOidA);
		assertDecodableImage("sceneA composite (final, Kontext-or-classic per params.useKontext)", compositeBytesA);
		FileUtil.emitFile(EMIT_DIR + "/verified_composite_sceneA.png", compositeBytesA);

		// ---- FRESH verification: sceneA's persisted landscape ----
		BaseRecord sceneANote = fetchSceneNote(sceneAOid);

		// ---- NEW: capture + inspect the actual Stage-4 Kontext composite prompt text for sceneA ----
		// (see logAndVerifyCompositePrompt() javadoc for exactly how this is reconstructed/verified)
		String compositePromptTextA = logAndVerifyCompositePrompt("sceneA", sceneA, sceneANote, paramsA.style,
			narrativeSdPromptByCharOid);
		// Write the actual reconstructed prompt text to a file next to the images (log lines require
		// digging through Maven output; a .txt file sits right next to the emitted PNGs).
		FileUtil.emitFile(EMIT_DIR + "/verified_kontextCompositePrompt_sceneA.txt", compositePromptTextA);

		String landscapeOidA = readTextField(sceneANote, "landscapeObjectId");
		assertNotNull("sceneA.landscapeObjectId must be persisted on the scene note", landscapeOidA);
		byte[] landscapeBytesA = fetchDataBytes(landscapeOidA);
		assertDecodableImage("sceneA landscape", landscapeBytesA);
		FileUtil.emitFile(EMIT_DIR + "/verified_landscape_sceneA.png", landscapeBytesA);
		logger.info("sceneA landscape objectId=" + landscapeOidA + " bytes=" + landscapeBytesA.length);

		// ---- NEW: reconstruct + emit the actual Stage-3 stitched Kontext reference image for sceneA ----
		// (see reconstructAndEmitStitchedReference() javadoc — this is the literal [left|center|landscape]
		// image generateSceneImage() sent as the promptImage; it was never exposed/persisted anywhere)
		reconstructAndEmitStitchedReference("sceneA", sceneA, landscapeBytesA);

		// ---- FRESH verification: shared character's portrait now exists ----
		BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().findPath(testUser, ModelNames.MODEL_GROUP,
			"~/Data/PictureBooks/" + bookName + "/Characters", GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Characters group must exist", charsGroup);
		long charsGroupId = ((Number) charsGroup.get(FieldNames.FIELD_ID)).longValue();

		String portraitOidBefore = fetchPortraitObjectId(sharedCharOid);
		assertNotNull("Shared character must have a persisted+linked portrait after sceneA generation", portraitOidBefore);
		byte[] portraitBytesBefore = fetchDataBytes(portraitOidBefore);
		assertDecodableImage("shared character portrait (after sceneA)", portraitBytesBefore);
		FileUtil.emitFile(EMIT_DIR + "/verified_portrait_shared_after_sceneA.png", portraitBytesBefore);
		int portraitCountBefore = countPortraitImages(testUser, charsGroupId, orgId);
		logger.info("Shared character portrait objectId=" + portraitOidBefore + " bytes=" + portraitBytesBefore.length
			+ " ; portrait_* count in Characters group=" + portraitCountBefore);

		// ==================================================================
		// Step 4: generateSceneImage() on sceneB — reuses the SAME shared character. Must NOT
		// regenerate/duplicate that character's portrait record.
		// ==================================================================
		String sceneBOid = sceneB.get("objectId");
		assertNotNull("sceneB objectId should not be null", sceneBOid);

		SceneGenerationParams paramsB = new SceneGenerationParams();
		paramsB.chatConfigName = chatConfigName;
		paramsB.steps = 14;
		paramsB.refinerSteps = 10;
		paramsB.cfg = 5;
		paramsB.hires = false;
		paramsB.style = "illustration";
		paramsB.sdModelName = swarmModel;
		paramsB.isBookOverride = true;

		t0 = System.currentTimeMillis();
		BaseRecord resultB;
		try {
			resultB = PictureBookUtil.generateSceneImage(testUser, sceneBOid, paramsB, "SWARM", swarmServer);
		} catch (PictureBookException pbe) {
			fail("generateSceneImage(sceneB) threw PictureBookException(status=" + pbe.getStatus()
				+ ", message=" + pbe.getMessage() + ")");
			return;
		}
		logger.info("generateSceneImage(sceneB) took " + (System.currentTimeMillis() - t0) + "ms");
		assertNotNull("generateSceneImage(sceneB) must return a non-null pictureBookResult", resultB);

		List<String> failedPortraitsB = resultB.get("failedPortraits");
		logger.info("failedPortraits after sceneB generation: " + failedPortraitsB);
		assertTrue("No portrait should fail to link during sceneB generation (failedPortraits=" + failedPortraitsB + ")",
			failedPortraitsB == null || failedPortraitsB.isEmpty());

		String compositeOidB = resultB.get("imageObjectId");
		assertNotNull("sceneB composite imageObjectId should not be null", compositeOidB);
		byte[] compositeBytesB = fetchDataBytes(compositeOidB);
		assertDecodableImage("sceneB composite (final, Kontext-or-classic per params.useKontext)", compositeBytesB);
		FileUtil.emitFile(EMIT_DIR + "/verified_composite_sceneB.png", compositeBytesB);

		BaseRecord sceneBNote = fetchSceneNote(sceneBOid);

		// ---- NEW: capture + inspect the actual Stage-4 Kontext composite prompt text for sceneB ----
		String compositePromptTextB = logAndVerifyCompositePrompt("sceneB", sceneB, sceneBNote, paramsB.style,
			narrativeSdPromptByCharOid);
		FileUtil.emitFile(EMIT_DIR + "/verified_kontextCompositePrompt_sceneB.txt", compositePromptTextB);

		String landscapeOidB = readTextField(sceneBNote, "landscapeObjectId");
		assertNotNull("sceneB.landscapeObjectId must be persisted on the scene note", landscapeOidB);
		byte[] landscapeBytesB = fetchDataBytes(landscapeOidB);
		assertDecodableImage("sceneB landscape", landscapeBytesB);
		assertFalse("sceneA and sceneB must have DISTINCT landscape images (each scene renders its own)",
			landscapeOidA.equals(landscapeOidB));
		FileUtil.emitFile(EMIT_DIR + "/verified_landscape_sceneB.png", landscapeBytesB);
		logger.info("sceneB landscape objectId=" + landscapeOidB + " bytes=" + landscapeBytesB.length);

		// ---- NEW: reconstruct + emit the actual Stage-3 stitched Kontext reference image for sceneB ----
		reconstructAndEmitStitchedReference("sceneB", sceneB, landscapeBytesB);

		// ---- NEW: emit EACH unique character's own portrait (not just "shared") so both faces used
		// in the stitched reference / composites can be individually inspected side-by-side with the
		// prompts above, unambiguously named by character name.
		for (String charOid : allCharOids) {
			String charName = charNameByCharOid.get(charOid);
			String pOid = fetchPortraitObjectId(charOid);
			if (pOid == null) {
				logger.warn("No persisted portrait found for character '" + charName + "' (" + charOid + ") — skipping emit");
				continue;
			}
			byte[] pBytes = fetchDataBytes(pOid);
			assertDecodableImage("character portrait for '" + charName + "'", pBytes);
			FileUtil.emitFile(EMIT_DIR + "/verified_portrait_" + charName + ".png", pBytes);
			logger.info("Emitted portrait for '" + charName + "' (" + charOid + "): objectId=" + pOid + " bytes=" + pBytes.length);
		}

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

		// No cleanup — records are deliberately left in the DB so persistence can be inspected
		// after the test run (Stephen's explicit ask: prove persistence survives).
		File emitDirFile = new File(EMIT_DIR);
		File[] emitted = emitDirFile.listFiles();
		logger.info("=== Emitted image files in " + EMIT_DIR + " ===");
		if (emitted != null) {
			java.util.Arrays.sort(emitted);
			for (File f : emitted) {
				logger.info("  " + f.getAbsolutePath() + " (" + f.length() + " bytes)");
			}
		}

		logger.info("=== Kontext composite prompt summary (for visual double-check) ===");
		logger.info("sceneA composite prompt: " + compositePromptTextA);
		logger.info("sceneB composite prompt: " + compositePromptTextB);
		logger.info("Captured character narrative.sdPrompt values: " + narrativeSdPromptByCharOid);

		logger.info("=== TestPictureBookUtilPersistenceE2E PASSED ===");
	}
}
