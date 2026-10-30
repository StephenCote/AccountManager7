package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.SceneGenerationParams;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 0, slice B: the scene-generation guards in {@link PictureBookUtil}.
 * <p>
 * Every test here runs against the live {@code am7db} database; the LLM-touching ones call the live
 * LLM route {@code LlmTestGate} resolved (read the {@code [LLM-GATE]} line in the log), and the one
 * GPU test renders a real scene on the live Swarm node from {@code test.swarm.server}. The SD backend
 * is never mocked: where a stage must fail on demand, an {@link SDUtil} <i>subclass</i> throws from
 * the real entry point ({@code createSceneImage} / {@code createImage}) while everything else in the
 * pipeline - prompt resolution, meta persistence, the PB2 graph, run/node bookkeeping - executes for
 * real.
 * <p>
 * Lives in {@code org.cote.accountmanager.olio.picturebook} for the package-private seams:
 * {@code generateSceneImage(user, sceneOid, params, SDUtil)} and {@code resolvePrompt}.
 */
public class TestPbSceneGenerationGuards extends BaseTest {

	private static final String WORK_PATH = "~/PbGuardWork";
	private static final String OWN_SCENES_PATH = "~/PbGuardScenes";
	private static final String CHARS_PATH = "~/PbGuardChars";
	private static final String LLM_CHAT_CONFIG = "pbGuardLlmConfig";
	/// The solid colour of the fixture portrait - nothing else on a black, landscape-less canvas is it.
	private static final int PORTRAIT_FILL_RGB = 0x3C8ED0;
	private static final String TEST_USER = "pbGuardUser";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	/// Render budgets are generous on purpose: a timing is never a pass/fail threshold here.
	private static final long RENDER_TIMEOUT_MS = 20L * 60L * 1000L;
	private static final long LLM_TIMEOUT_MS = 20L * 60L * 1000L;

	/// The literal {@code PictureBookUtil.BLANK_LANDSCAPE_FALLBACK} (private there).
	private static final String BLANK_LANDSCAPE_FALLBACK = "A detailed environment";

	/// P7-2: these guards drive the PB1 entries generateSceneImage / prepareSceneImagePrompts, which
	/// refuse with 409 for book scenes under the default picturebook.renderPath=v3 (design §28.1
	/// Stage 2). Pin the rollback value for this product only (ChapBook stays v3); BaseTest re-pins
	/// v3 on every @Before and restorePb1Pin restores it after each case.
	@Before
	public void pinPb1Rollback() {
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3);
	}

	@After
	public void restorePb1Pin() {
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
	}

	// ─────────────────────────────── P0-1 ───────────────────────────────

	/**
	 * A sparse per-scene override must render THIS scene with its values and must NOT be promoted to
	 * the book-wide config in {@code .pictureBookMeta.sdConfig}. The SD subclass throws from the first
	 * real SD call, so the only persisted config is the one {@code persistBookSdConfig} wrote BEFORE
	 * the SD stages - which is exactly the write the override used to leak into.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void testSceneOverrideDoesNotLeakIntoBookConfig() throws Exception {
		BaseRecord user = user();
		String tag = shortId();
		Fixture fx = createPb2Fixture(user, "p01-" + tag, Arrays.asList(preSeededScene("Override scene " + tag)));
		String sceneOid = fx.sceneOids.get(0);

		SceneGenerationParams params = new SceneGenerationParams();
		params.bookSlug = fx.slug;
		params.isBookOverride = Boolean.TRUE;
		params.sdConfig = classicConfig(20);
		assertEquals("precondition: the book-level config carries steps=20", Integer.valueOf(20), params.sdConfig.get("steps"));
		params.sdConfigOverride = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG, new String[] { "steps" });
		params.sdConfigOverride.set("steps", 7);

		PictureBookException caught = null;
		List<SWTxt2Img> sent = new ArrayList<>();
		try {
			PictureBookUtil.generateSceneImage(user, sceneOid, params, capturingThrowingSdu("test: SD disabled", sent));
			fail("The throwing SDUtil must abort the scene");
		}
		catch(PictureBookException e) {
			caught = e;
		}
		finally {
			cleanupStrayEmits(sceneOid);
		}

		/// The override must have been APPLIED to this scene: every SD request the pipeline built
		/// (the landscape, then the composite after the landscape failure degraded non-fatally)
		/// carries the override's steps, not the book's.
		assertFalse("At least one SD request must have reached the SDUtil", sent.isEmpty());
		for(SWTxt2Img s2i : sent) {
			assertEquals("P0-1: the per-scene steps=7 override must reach the SD request", 7, s2i.getSteps());
		}

		/// The stored BOOK config: typed read through the public API...
		BaseRecord stored = PictureBookUtil.getBookSdConfig(user, fx.bookGroupOid);
		assertNotNull("The book must have a stored sdConfig after generateSceneImage persisted it", stored);
		assertEquals("P0-1: the per-scene steps=7 override must not be promoted to the book config",
			Integer.valueOf(20), stored.get("steps"));
		/// ...and the raw meta JSON, read uncached, must not carry the override value anywhere.
		String metaText = readMetaText(user, fx.bookGroupOid);
		assertNotNull("The .pictureBookMeta note must exist", metaText);
		assertTrue("The meta must carry an sdConfig block", metaText.contains("\"sdConfig\""));
		assertFalse("P0-1: the raw meta JSON must not contain the override's steps=7: " + metaText,
			Pattern.compile("\"steps\"\\s*:\\s*7\\b").matcher(metaText).find());

		/// The abort must have come from the SD subclass, not from the override handling.
		assertNotNull(caught);
		assertEquals("The exception must be the SD subclass's, not an override-handling failure",
			"test: SD disabled", caught.getMessage());
		assertFalse("The failure must not be about the override", caught.getMessage().toLowerCase().contains("override"));
	}

	/**
	 * The no-graph classic branch (a PB1-only book: no {@code olio.pb.book}, so no graph) reached WITH a
	 * portrait and WITHOUT a landscape. That is the one combination that executes the branch's debug
	 * dump, {@code FileUtil.emitFile("./land-<oid>.png", landscapeBytes)}, with a null payload - which
	 * NPE'd and so turned a non-fatal landscape failure back into a fatal one carrying the NPE's message.
	 * <p>
	 * Fixture: one charPerson whose persisted PNG portrait is REUSED (no portrait render), listed on the
	 * scene by objectId; an SDUtil that fails the landscape with one message and the composite with
	 * another. The composite's message can only be observed by passing through the guarded dump, and
	 * the {@code ./comp-<oid>.png} the same branch emits is decoded to prove the portrait pixels were
	 * drawn onto a black (landscape-less) canvas - i.e. the branch really ran with portrait=yes,
	 * landscape=no.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void testNoGraphClassicLandscapeDumpGuardWithPortrait() throws Exception {
		BaseRecord user = user();
		String tag = shortId();
		Fixture fx = createPb1OnlyFixture(user, "p01ng-" + tag, Arrays.asList(preSeededScene("No-graph scene " + tag)));
		String sceneOid = fx.sceneOids.get(0);

		/// A character with a persisted, reusable portrait - added to the scene AFTER createFromScenes
		/// so the fixture does not try to extract a character out of the objectId string.
		String cpOid = createCharPersonWithPortrait(user, tag);
		Map<String, Object> sceneJson = readSceneJson(user, sceneOid);
		sceneJson.put("characters", new ArrayList<>(Arrays.asList(cpOid)));
		writeSceneJson(user, sceneOid, sceneJson);
		assertEquals("precondition: the scene must list the character by objectId",
			Arrays.asList(cpOid), readSceneJson(user, sceneOid).get("characters"));

		SceneGenerationParams params = new SceneGenerationParams();
		params.isBookOverride = Boolean.TRUE;
		params.sdConfig = classicConfig(20);

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord u, String groupPath, BaseRecord cfg, String name,
					int batch, boolean hires, int seed) {
				throw new RuntimeException("test: portrait SD disabled");
			}
			@Override
			public List<BaseRecord> createSceneImage(BaseRecord u, String groupPath, String name, SWTxt2Img s2i,
					String sysCharOid, String usrCharOid) {
				if(name != null && name.startsWith("landscape_")) {
					throw new RuntimeException("test: landscape SD failure");
				}
				throw new RuntimeException("test: composite SD disabled");
			}
		};

		CountingAppender app = new CountingAppender("p01ng-" + tag);
		File comp = new File("./comp-" + sceneOid + ".png");
		File land = new File("./land-" + sceneOid + ".png");
		try {
			runWithAppenderOn(PictureBookUtil.class, app, () -> {
				try {
					PictureBookUtil.generateSceneImage(user, sceneOid, params, sdu);
					fail("The throwing SDUtil must abort the scene at the composite");
				}
				catch(PictureBookException e) {
					assertEquals("No-graph classic path with a portrait and no landscape: the abort must be the"
						+ " COMPOSITE call's message - a NullPointerException from"
						+ " FileUtil.emitFile(\"./land-<oid>.png\", null) would surface here instead",
						"test: composite SD disabled", e.getMessage());
				}
				return null;
			});

			/// The branch really ran with portrait=yes, landscape=no.
			boolean reused = false;
			boolean landscapeFailed = false;
			for(String m : app.messages) {
				if(m.contains("Reusing persisted portrait for")) reused = true;
				if(m.contains("landscape generation FAILED (test: landscape SD failure)")) landscapeFailed = true;
				assertFalse("The portrait must not have been re-rendered: " + m, m.contains("Portrait generation error"));
			}
			assertTrue("The persisted portrait must have been reused (no SD call)", reused);
			assertTrue("The landscape must have failed non-fatally", landscapeFailed);
			assertFalse("No landscape was produced, so no land-<oid>.png may be dumped", land.exists());
			assertTrue("The composite canvas dump must exist: the branch under test emits it", comp.exists());

			/// The dumped canvas: the portrait's pixels on an otherwise black canvas.
			BufferedImage canvas = ImageIO.read(comp);
			assertNotNull("comp-<oid>.png must decode", canvas);
			int w = canvas.getWidth(), h = canvas.getHeight();
			int portraitW = w / 3;
			int portraitH = (int) (portraitW * 1.33);
			if(portraitH > h * 3 / 4) {
				portraitH = h * 3 / 4;
				portraitW = (int) (portraitH / 1.33);
			}
			int yOffset = h - portraitH - (h / 20);
			int xLeft = w / 12;
			int inPortrait = canvas.getRGB(xLeft + portraitW / 2, yOffset + portraitH / 2);
			int outside = canvas.getRGB(w - 8, 8);
			logger.info("P0-6 no-graph canvas " + w + "x" + h + " portrait-centre rgb=" + Integer.toHexString(inPortrait & 0xFFFFFF)
				+ " top-right rgb=" + Integer.toHexString(outside & 0xFFFFFF));
			assertTrue("The portrait's fill colour must be present at the left portrait slot (was "
				+ Integer.toHexString(inPortrait & 0xFFFFFF) + ")", isPortraitFill(inPortrait));
			assertEquals("With no landscape the canvas outside the portrait must be black", 0, outside & 0xFFFFFF);
		}
		finally {
			cleanupStrayEmits(sceneOid);
		}
	}

	// ─────────────────────────────── P0-3 (LLM) ───────────────────────────────

	/**
	 * {@code promptTemplateOverride} names the LANDSCAPE template. It must reach only the landscape
	 * prompt call; the scene-image prompt must still be produced from its own template, so the
	 * landscape template's {@code {time}} / {@code {style}} placeholders never trip the
	 * unsubstituted-placeholder guard and the scene prompt never degrades to the raw concatenation.
	 */
	@Test(timeout = LLM_TIMEOUT_MS)
	public void testLandscapeTemplateOverrideDoesNotPoisonScenePrompt() throws Exception {
		BaseRecord user = user();
		String tag = shortId();
		BaseRecord chatConfig = OlioTestUtil.getPbChatConfig(user, LLM_CHAT_CONFIG, testProperties);
		assertNotNull("LLM chat config must resolve (live LLM route required)", chatConfig);
		assertNotNull("ChatUtil.resolveConfig must find the chat config by name",
			ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, LLM_CHAT_CONFIG, null));

		/// The override template really is the landscape one, placeholders and all.
		PictureBookUtil.ResolvedPrompt landscapeTpl = PictureBookUtil.resolvePrompt(user, chatConfig, "pictureBook.landscape-prompt");
		assertTrue("pictureBook.landscape-prompt must resolve", landscapeTpl.isUsable());
		assertTrue("The landscape template must carry {time} or {style}: " + landscapeTpl.userTpl,
			landscapeTpl.userTpl.contains("{time}") || landscapeTpl.userTpl.contains("{style}"));

		String settingA = "a lamplit observatory dome above a sleeping town";
		String settingB = "a flooded cathedral nave at low tide";
		Fixture fx = createPb2Fixture(user, "p03-" + tag, Arrays.asList(
			rawScene("Observatory " + tag, settingA, "an astronomer adjusts the great brass telescope", "quiet, reverent"),
			rawScene("Cathedral " + tag, settingB, "a diver surfaces between the pews", "eerie, still")));
		String sceneA = fx.sceneOids.get(0);
		String sceneB = fx.sceneOids.get(1);

		CountingAppender app = new CountingAppender("p03-" + tag);
		runWithAppenderOn(PictureBookUtil.class, app, () -> {
			/// Scene A through the full pipeline (prompts resolve live, SD aborts at the first call).
			SceneGenerationParams params = new SceneGenerationParams();
			params.bookSlug = fx.slug;
			params.isBookOverride = Boolean.TRUE;
			params.chatConfigName = LLM_CHAT_CONFIG;
			params.promptTemplateOverride = "pictureBook.landscape-prompt";
			params.sdConfig = classicConfig(8);
			try {
				PictureBookUtil.generateSceneImage(user, sceneA, params, throwingSdu("test: SD disabled"));
				fail("The throwing SDUtil must abort the scene");
			}
			catch(PictureBookException e) {
				assertEquals("test: SD disabled", e.getMessage());
			}
			finally {
				cleanupStrayEmits(sceneA);
			}
			/// Scene B through the batch pre-resolver with the same override.
			PictureBookUtil.prepareSceneImagePrompts(user, Arrays.asList(sceneB), LLM_CHAT_CONFIG,
				classicConfig(8), "pictureBook.landscape-prompt");
			return null;
		});

		for(String m : app.messages) {
			assertFalse("P0-3: the placeholder guard fired - the landscape template reached a non-landscape call: " + m,
				m.contains("Refusing to call LLM for prompt"));
			assertFalse("P0-3: the scene-image prompt fell back to raw concatenation: " + m,
				m.contains("Scene-image prompt LLM call failed"));
			assertFalse("P0-3: the landscape prompt fell back: " + m, m.contains("Landscape prompt failed"));
		}

		assertScenePromptClean(user, sceneA, settingA);
		assertScenePromptClean(user, sceneB, settingB);
	}

	private void assertScenePromptClean(BaseRecord user, String sceneOid, String setting) throws Exception {
		Map<String, Object> json = readSceneJson(user, sceneOid);
		String scenePrompt = (String) json.get("scenePrompt");
		String landscapePrompt = (String) json.get("landscapePrompt");
		logger.info("scene " + sceneOid + " scenePrompt=" + scenePrompt);
		logger.info("scene " + sceneOid + " landscapePrompt=" + landscapePrompt);
		assertNotNull("The scene prompt must be cached on the scene note", scenePrompt);
		assertFalse("The scene prompt must not be empty", scenePrompt.isBlank());
		assertFalse("The scene prompt must carry no unsubstituted placeholder: " + scenePrompt, scenePrompt.contains("{"));
		assertFalse("The scene prompt must not be the raw-concatenation fallback: " + scenePrompt,
			scenePrompt.contains("Setting: " + setting + ". Mood:"));
		assertNotNull("The landscape prompt must be cached on the scene note", landscapePrompt);
		assertFalse("The landscape prompt must not be empty", landscapePrompt.isBlank());
		assertFalse("The landscape prompt must carry no unsubstituted placeholder: " + landscapePrompt, landscapePrompt.contains("{"));
	}

	// ─────────────────────────────── P0-4 (LLM) ───────────────────────────────

	/**
	 * LLM-emitted JSON nulls and the literal placeholders ({@code "null"}, {@code "n/a"}) in a scene's
	 * setting/action/mood must neither NPE nor leak into the cached prompts. Scene 1 has a real mood
	 * only (so the LLM IS called, with a blank setting); scene 2 has nothing real at all (so the
	 * deterministic landscape fallback must be used and no LLM call made for it).
	 */
	@Test(timeout = LLM_TIMEOUT_MS)
	public void testNullSceneFieldsDoNotNpeAndFallBack() throws Exception {
		BaseRecord user = user();
		String tag = shortId();
		assertNotNull("LLM chat config must resolve (live LLM route required)",
			OlioTestUtil.getPbChatConfig(user, LLM_CHAT_CONFIG, testProperties));

		String scene1Oid = createRawNote(user, OWN_SCENES_PATH, "p04-null-" + tag,
			"{\"title\":\"T\",\"setting\":null,\"action\":\"null\",\"mood\":\"tense\",\"characters\":[]}");
		String scene2Oid = createRawNote(user, OWN_SCENES_PATH, "p04-na-" + tag,
			"{\"title\":\"T2\",\"setting\":\"n/a\",\"action\":\"\",\"mood\":\"\",\"characters\":[]}");
		/// Precondition: the raw payloads really carry the JSON null / literal placeholders.
		assertTrue(readSceneJson(user, scene1Oid).containsKey("setting"));
		assertNull(readSceneJson(user, scene1Oid).get("setting"));
		assertEquals("null", readSceneJson(user, scene1Oid).get("action"));
		assertEquals("n/a", readSceneJson(user, scene2Oid).get("setting"));

		BaseRecord sdConfig = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		SdTestGate.stampInstalledModel(sdConfig, testProperties);
		assertTrue("precondition: landscape prompts must be enabled for this config", PictureBookUtil.landscapeEnabled(sdConfig));

		CountingAppender pbApp = new CountingAppender("p04-pb-" + tag);
		CountingAppender rootApp = new CountingAppender("p04-root-" + tag);
		org.apache.logging.log4j.core.Logger root = (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
		rootApp.start();
		root.addAppender(rootApp);
		try {
			runWithAppenderOn(PictureBookUtil.class, pbApp, () -> {
				PictureBookUtil.prepareSceneImagePrompts(user, Arrays.asList(scene1Oid, scene2Oid), LLM_CHAT_CONFIG, sdConfig, null);
				return null;
			});
		}
		finally {
			root.removeAppender(rootApp);
			rootApp.stop();
		}

		for(String m : pbApp.messages) {
			assertFalse("P0-4: prepareSceneImagePrompts swallowed a per-scene failure: " + m,
				m.contains("prepareSceneImagePrompts: failed for scene"));
		}
		for(Throwable t : rootApp.thrown) {
			assertFalse("P0-4: a NullPointerException was logged during prompt preparation: " + t,
				t instanceof NullPointerException);
		}
		for(String m : rootApp.messages) {
			assertFalse("P0-4: a NullPointerException surfaced in the log: " + m, m.contains("NullPointerException"));
		}

		Pattern nullWord = Pattern.compile("(?i)\\bnull\\b");
		for(String oid : new String[] { scene1Oid, scene2Oid }) {
			Map<String, Object> json = readSceneJson(user, oid);
			String scenePrompt = (String) json.get("scenePrompt");
			String landscapePrompt = (String) json.get("landscapePrompt");
			logger.info("P0-4 scene " + oid + " scenePrompt=" + scenePrompt);
			logger.info("P0-4 scene " + oid + " landscapePrompt=" + landscapePrompt);
			assertNotNull("scenePrompt must be cached for " + oid, scenePrompt);
			assertFalse("scenePrompt must not be blank for " + oid, scenePrompt.isBlank());
			assertNotNull("landscapePrompt must be cached for " + oid, landscapePrompt);
			assertFalse("landscapePrompt must not be blank for " + oid, landscapePrompt.isBlank());
			for(String p : new String[] { scenePrompt, landscapePrompt }) {
				assertFalse("P0-4: the literal 'null' leaked into a prompt for " + oid + ": " + p, nullWord.matcher(p).find());
				assertFalse("P0-4: the literal 'n/a' leaked into a prompt for " + oid + ": " + p, p.toLowerCase().contains("n/a"));
			}
		}
		/// Scene 2 has no real setting/mood at all: the only legitimate landscape output is the
		/// deterministic fallback (plus the code-owned style suffix), never an invented landscape.
		String landscape2 = (String) readSceneJson(user, scene2Oid).get("landscapePrompt");
		assertTrue("P0-4: with no real setting/mood the landscape prompt must be the deterministic fallback: " + landscape2,
			landscape2.contains(BLANK_LANDSCAPE_FALLBACK));
		boolean sawBlankSkip = false;
		for(String m : pbApp.messages) {
			if(m.contains("setting and mood are both blank")) sawBlankSkip = true;
		}
		assertTrue("P0-4: the blank-input landscape skip must have been taken for scene 2", sawBlankSkip);
	}

	// ─────────────────────────────── P0-6 (GPU, live Swarm) ───────────────────────────────

	/**
	 * A landscape-stage failure is non-fatal: the scene still renders (for real, on the live Swarm
	 * node) without a landscape reference, the result says so, the LANDSCAPE node is FAILED with the
	 * error, and the run closes COMPLETED.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void testLandscapeFailureIsNonFatal() throws Exception {
		BaseRecord user = user();
		String tag = shortId();
		String server = swarmServer();
		assertTrue("Swarm must be reachable at " + server, SdTestGate.isReachable(server));

		Fixture fx = createPb2Fixture(user, "p06-" + tag, Arrays.asList(preSeededScene("Landscape-fail scene " + tag)));
		String sceneOid = fx.sceneOids.get(0);

		SceneGenerationParams params = new SceneGenerationParams();
		params.bookSlug = fx.slug;
		params.isBookOverride = Boolean.TRUE;
		params.sdConfig = classicConfig(8);

		SDUtil sdu = landscapeOnlyThrowingSdu(server, "test: landscape SD failure");
		long t0 = System.currentTimeMillis();
		BaseRecord result;
		try {
			result = PictureBookUtil.generateSceneImage(user, sceneOid, params, sdu);
		}
		finally {
			cleanupStrayEmits(sceneOid);
		}
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P0-6 generateSceneImage durationMs=" + durationMs);

		assertNotNull("The scene must still render when the landscape fails", result);
		String imageOid = result.get("imageObjectId");
		assertNotNull("The result must carry the rendered image's objectId", imageOid);
		assertEquals("landscapeFailed must be reported", Boolean.TRUE, result.get("landscapeFailed"));
		String landscapeError = result.get("landscapeError");
		assertNotNull("landscapeError must be reported", landscapeError);
		assertTrue("landscapeError must carry the SD failure message: " + landscapeError,
			landscapeError.contains("test: landscape SD failure"));

		/// The image: real PNG bytes, read back uncached through ByteModelUtil.
		BaseRecord data = freshReadData(user, imageOid);
		assertNotNull("The rendered image record must be readable", data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertNotNull("The rendered image must have bytes", bytes);
		assertTrue("The rendered image must be larger than a PNG header (was " + bytes.length + ")", bytes.length > 1024);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		File out = new File("target/pb-v3-p0-6-landscape-failed.png");
		FileUtil.emitFile(out.getPath(), bytes);
		assertTrue("The PNG must have been written to " + out.getAbsolutePath(), out.exists() && out.length() == bytes.length);
		logger.info("P0-6 PNG written: " + out.getAbsolutePath() + " (" + bytes.length + " bytes)");
		/// Measured, not asserted: with no landscape and no portraits the classic init canvas is a
		/// black TYPE_INT_RGB image, so the img2img result's brightness says how much the render
		/// actually recovered from it. Logged so the number is in the record next to the PNG.
		java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
		assertNotNull("The PNG must decode", img);
		long lum = 0L;
		int w = img.getWidth(), h = img.getHeight();
		for(int y = 0; y < h; y += 4) {
			for(int x = 0; x < w; x += 4) {
				int rgb = img.getRGB(x, y);
				lum += (((rgb >> 16) & 0xFF) * 299L + ((rgb >> 8) & 0xFF) * 587L + (rgb & 0xFF) * 114L) / 1000L;
			}
		}
		long samples = ((long) ((h + 3) / 4)) * ((w + 3) / 4);
		logger.info("P0-6 PNG " + w + "x" + h + " mean luminance (0-255, sampled every 4px) = " + (lum / Math.max(1L, samples)));

		/// Graph: LANDSCAPE node FAILED with the error, run COMPLETED.
		BaseRecord workflow = workflowOf(user, fx.slug);
		assertNotNull("The book must have a workflow after generation", workflow);
		BaseRecord landscapeNode = PbPipelineUtil.findNodeByHandle(user, workflow, PbPipelineUtil.landscapeHandle(sceneOid));
		assertNotNull("The LANDSCAPE node must exist for the scene", landscapeNode);
		BaseRecord landscapeFresh = PbGraphUtil.readNode(user, landscapeNode.get(FieldNames.FIELD_OBJECT_ID), orgOf(user));
		assertNotNull(landscapeFresh);
		assertEquals("The LANDSCAPE node must be FAILED", PbNodeStatusEnumType.FAILED,
			landscapeFresh.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));
		String nodeErr = landscapeFresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertNotNull("The LANDSCAPE node must carry lastError", nodeErr);
		assertTrue("The LANDSCAPE node's lastError must carry the SD failure message: " + nodeErr,
			nodeErr.contains("test: landscape SD failure"));

		BaseRecord run = latestRun(user, workflow);
		assertNotNull("A run must have been recorded", run);
		assertEquals("The run must close COMPLETED when the composite succeeds despite the landscape failure",
			PbRunStatusEnumType.COMPLETED, run.getEnum(OlioFieldNames.FIELD_PB_RUN_STATUS));
		logger.info("P0-6 run " + run.get(FieldNames.FIELD_OBJECT_ID) + " status=" + run.getEnum(OlioFieldNames.FIELD_PB_RUN_STATUS)
			+ " failedNodeCount=" + run.get("failedNodeCount") + " durationMs=" + durationMs);
	}

	// ─────────────────────────────── P0-8 ───────────────────────────────

	/**
	 * When the scene pipeline throws, the run must close FAILED with the error (never left PENDING)
	 * and the in-flight COMPOSITE node must be FAILED.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void testRunClosedFailedWhenSceneThrows() throws Exception {
		BaseRecord user = user();
		String tag = shortId();
		Fixture fx = createPb2Fixture(user, "p08-" + tag, Arrays.asList(preSeededScene("Run-fail scene " + tag)));
		String sceneOid = fx.sceneOids.get(0);

		SceneGenerationParams params = new SceneGenerationParams();
		params.bookSlug = fx.slug;
		params.isBookOverride = Boolean.TRUE;
		params.sdConfig = classicConfig(8);

		try {
			PictureBookUtil.generateSceneImage(user, sceneOid, params, throwingSdu("test: SD disabled"));
			fail("The throwing SDUtil must abort the scene");
		}
		catch(PictureBookException e) {
			assertEquals("test: SD disabled", e.getMessage());
		}
		finally {
			cleanupStrayEmits(sceneOid);
		}

		BaseRecord workflow = workflowOf(user, fx.slug);
		assertNotNull("The book must have a workflow after generation", workflow);
		BaseRecord run = latestRun(user, workflow);
		assertNotNull("A run must have been recorded", run);
		PbRunStatusEnumType status = run.getEnum(OlioFieldNames.FIELD_PB_RUN_STATUS);
		assertFalse("P0-8: the run must not be left PENDING", PbRunStatusEnumType.PENDING.equals(status));
		assertEquals("P0-8: the run must close FAILED", PbRunStatusEnumType.FAILED, status);
		String runErr = run.get(OlioFieldNames.FIELD_PB_ERROR);
		assertNotNull("P0-8: the FAILED run must carry the error", runErr);
		assertTrue("P0-8: the run error must carry the SD failure message: " + runErr, runErr.contains("test: SD disabled"));

		BaseRecord compositeNode = PbPipelineUtil.findNodeByHandle(user, workflow, PbPipelineUtil.compositeHandle(sceneOid));
		assertNotNull("The COMPOSITE node must exist for the scene", compositeNode);
		BaseRecord compositeFresh = PbGraphUtil.readNode(user, compositeNode.get(FieldNames.FIELD_OBJECT_ID), orgOf(user));
		assertNotNull(compositeFresh);
		assertEquals("P0-8: the in-flight COMPOSITE node must be FAILED", PbNodeStatusEnumType.FAILED,
			compositeFresh.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));
		String nodeErr = compositeFresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertNotNull("P0-8: the FAILED COMPOSITE node must carry lastError", nodeErr);
		assertTrue("P0-8: the node error must carry the SD failure message: " + nodeErr, nodeErr.contains("test: SD disabled"));
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	private static final class Fixture {
		String slug;
		String bookGroupOid;
		List<String> sceneOids = new ArrayList<>();
	}

	private BaseRecord user() {
		OlioModelNames.use();
		BaseRecord u = getCreateUser(TEST_USER);
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", p);
		return p;
	}

	private String swarmServer() {
		String s = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		assertNotNull(SdTestGate.PROP_SWARM_SERVER + " must be set", s);
		return s;
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static Map<String, Object> rawScene(String title, String setting, String action, String mood) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("title", title);
		s.put("summary", title + " - " + action);
		s.put("setting", setting);
		s.put("action", action);
		s.put("mood", mood);
		s.put("characters", new ArrayList<String>());
		return s;
	}

	/** A scene whose prompts are already cached as plain prose, so no LLM is needed to render it. */
	private static Map<String, Object> preSeededScene(String title) {
		Map<String, Object> s = rawScene(title, "a rain-dark harbour at dusk",
			"lanterns are lit one by one along the quay", "hushed, expectant");
		s.put("scenePrompt", "a rain-dark harbour at dusk, lanterns lit one by one along the quay, hushed expectant mood,"
			+ " wet cobblestones reflecting warm light, moored fishing boats, painterly");
		s.put("landscapePrompt", "a rain-dark harbour at dusk, wet cobblestones, moored fishing boats, lantern light,"
			+ " low grey clouds, painterly");
		return s;
	}

	private BaseRecord createWork(BaseRecord user, String tag) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, WORK_PATH);
		plist.parameter(FieldNames.FIELD_NAME, "guard-src-" + tag);
		BaseRecord work = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		work.set("text", "Guard fixture source text for " + tag + ". The harbour slept under the rain.");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, work);
		assertNotNull("work note must be created", created);
		return created;
	}

	/** A note with hand-written JSON text, so JSON nulls / literal placeholders survive verbatim. */
	private String createRawNote(BaseRecord user, String path, String name, String json) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set("text", json);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, note);
		assertNotNull("note " + name + " must be created", created);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull(oid);
		return oid;
	}

	/** PB2 book (world + graph capable) plus its PB1 scenes group, via createFromScenes with the PB2 book id. */
	private Fixture createPb2Fixture(BaseRecord user, String tag, List<Map<String, Object>> scenes) throws Exception {
		Fixture fx = new Fixture();
		fx.slug = "pbguard-" + tag.toLowerCase();
		BaseRecord work = createWork(user, tag);
		BaseRecord pb2Book = PbBookUtil.createBook(user, dataPath(), fx.slug, "Guard " + tag);
		assertNotNull("PB2 book must be created", pb2Book);
		String pb2BookOid = pb2Book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord meta = PictureBookUtil.createFromScenes(user, work.get(FieldNames.FIELD_OBJECT_ID), null, "fiction",
			"Guard " + tag, new ArrayList<>(scenes), new ArrayList<>(), dataPath(), pb2BookOid);
		finishFixture(fx, meta, scenes.size());
		return fx;
	}

	/** Legacy PB1-only book: no olio.pb.book, so generateSceneImage records no graph. */
	private Fixture createPb1OnlyFixture(BaseRecord user, String tag, List<Map<String, Object>> scenes) throws Exception {
		Fixture fx = new Fixture();
		fx.slug = null;
		BaseRecord work = createWork(user, tag);
		BaseRecord meta = PictureBookUtil.createFromScenes(user, work.get(FieldNames.FIELD_OBJECT_ID), null, "fiction",
			"Guard " + tag, new ArrayList<>(scenes), new ArrayList<>(), dataPath());
		finishFixture(fx, meta, scenes.size());
		return fx;
	}

	private void finishFixture(Fixture fx, BaseRecord meta, int expectedScenes) {
		assertNotNull("createFromScenes must return meta", meta);
		fx.bookGroupOid = meta.get("bookObjectId");
		assertNotNull("meta.bookObjectId must be set", fx.bookGroupOid);
		List<BaseRecord> sceneRecs = meta.get("scenes");
		assertNotNull("meta.scenes must be set", sceneRecs);
		assertEquals("every scene must be persisted", expectedScenes, sceneRecs.size());
		for(BaseRecord s : sceneRecs) {
			String oid = s.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("scene objectId", oid);
			fx.sceneOids.add(oid);
		}
	}

	/**
	 * A charPerson that Stage 1 will REUSE rather than render: a {@code pbDescription} attribute so
	 * {@code resolveSceneCharacter} has something to describe it with, and a persisted solid-colour PNG
	 * attached as {@code profile.portrait} exactly the way generateSceneImage attaches one (profile patch
	 * of {@code id + objectId + portrait}). Returns the charPerson objectId; the reuse read is re-run
	 * here through the pipeline's own populate chain so a fixture problem fails as a precondition, not
	 * as the guard under test.
	 */
	private String createCharPersonWithPortrait(BaseRecord user, String tag) throws Exception {
		long orgId = orgOf(user);
		BaseRecord charsGroup = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP,
			CHARS_PATH, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("characters group must resolve", charsGroup);
		String groupPath = charsGroup.get(FieldNames.FIELD_PATH);

		String cpName = "GuardChar-" + tag;
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, cpName);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		cp.set(FieldNames.FIELD_NAME, cpName);
		cp.set("gender", "FEMALE");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("charPerson must be created", created);
		String cpOid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull(cpOid);

		/// The frozen description resolveSceneCharacter falls back to when the record is too sparse to
		/// describe on its own. Persisted the way PictureBookUtil persists it: the attribute record
		/// itself, never a parent patch (.claude/rules/model-api.md, "referenced fields").
		Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cpOid);
		cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID, "profile", FieldNames.FIELD_ATTRIBUTES });
		cq.setCache(false);
		BaseRecord cpRead = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
		assertNotNull("charPerson must be readable", cpRead);
		BaseRecord attr = AttributeUtil.addAttribute(cpRead, PictureBookUtil.ATTR_DESCRIPTION,
			"a tall woman in her thirties with short dark hair, a weathered oilskin coat and sea-grey eyes");
		assertTrue("pbDescription attribute must persist", IOSystem.getActiveContext().getRecordUtil().createRecord(attr));

		BaseRecord profile = cpRead.get("profile");
		if(profile == null) {
			IOSystem.getActiveContext().getReader().populate(cpRead, new String[] { "profile" });
			profile = cpRead.get("profile");
		}
		assertNotNull("the factory must have persisted a profile for the charPerson", profile);
		Long profId = profile.get(FieldNames.FIELD_ID);
		assertTrue("profile.id must be > 0 for the attach patch", profId != null && profId.longValue() > 0L);
		String profOid = profile.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, image, "portrait_" + cpName, groupPath, orgId);
		image.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(image, solidPng(384, 512, PORTRAIT_FILL_RGB));
		BaseRecord imageCreated = IOSystem.getActiveContext().getAccessPoint().create(user, image);
		assertNotNull("portrait image must be created", imageCreated);

		String[] patchFields = (profOid != null)
			? new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "portrait" }
			: new String[] { FieldNames.FIELD_ID, "portrait" };
		BaseRecord profilePatch = RecordFactory.newInstance(ModelNames.MODEL_PROFILE, patchFields);
		profilePatch.set(FieldNames.FIELD_ID, profId);
		if(profOid != null) {
			profilePatch.set(FieldNames.FIELD_OBJECT_ID, profOid);
		}
		profilePatch.set("portrait", imageCreated);
		assertNotNull("profile.portrait attach patch must persist",
			IOSystem.getActiveContext().getAccessPoint().update(user, profilePatch));

		/// Precondition: the pipeline's own reuse read (scene-fetch projection, then the two populates,
		/// then ByteModelUtil) must see a real PNG, or Stage 1 would render instead of reuse.
		Query rq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cpOid);
		rq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		rq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			"narrative", "gender", "profile", FieldNames.FIELD_STORE, FieldNames.FIELD_ATTRIBUTES });
		rq.setCache(false);
		BaseRecord cp2 = IOSystem.getActiveContext().getAccessPoint().find(user, rq);
		assertNotNull("charPerson re-read must succeed", cp2);
		assertEquals("precondition: the pbDescription attribute must be readable on the scene-fetch projection",
			"a tall woman in her thirties with short dark hair, a weathered oilskin coat and sea-grey eyes",
			AttributeUtil.getAttributeValue(cp2, PictureBookUtil.ATTR_DESCRIPTION, (String) null));
		BaseRecord profile2 = cp2.get("profile");
		if(profile2 == null) {
			IOSystem.getActiveContext().getReader().populate(cp2, new String[] { "profile" });
			profile2 = cp2.get("profile");
		}
		assertNotNull("precondition: profile on re-read", profile2);
		IOSystem.getActiveContext().getReader().populate(profile2, new String[] { "portrait" });
		BaseRecord portrait2 = profile2.get("portrait");
		assertNotNull("precondition: profile.portrait must be linked after the attach patch", portrait2);
		IOSystem.getActiveContext().getReader().populate(portrait2, new String[] { FieldNames.FIELD_BYTE_STORE });
		byte[] reusable = ByteModelUtil.getValue(portrait2);
		assertTrue("precondition: the reusable portrait must be a real PNG",
			reusable != null && reusable.length > PNG_MAGIC.length
			&& Arrays.equals(PNG_MAGIC, Arrays.copyOf(reusable, PNG_MAGIC.length)));
		return cpOid;
	}

	private static byte[] solidPng(int w, int h, int rgb) throws Exception {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(rgb));
		g.fillRect(0, 0, w, h);
		g.dispose();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		assertTrue("PNG encoder must be available", ImageIO.write(img, "png", baos));
		return baos.toByteArray();
	}

	/** True when a canvas pixel is (within an interpolation tolerance) the fixture portrait's fill colour. */
	private static boolean isPortraitFill(int argb) {
		int dr = Math.abs(((argb >> 16) & 0xFF) - ((PORTRAIT_FILL_RGB >> 16) & 0xFF));
		int dg = Math.abs(((argb >> 8) & 0xFF) - ((PORTRAIT_FILL_RGB >> 8) & 0xFF));
		int db = Math.abs((argb & 0xFF) - (PORTRAIT_FILL_RGB & 0xFF));
		return dr <= 8 && dg <= 8 && db <= 8;
	}

	/** A schema-built config pinned to the CLASSIC composite on the installed checkpoint, no hires. */
	private BaseRecord classicConfig(int steps) throws Exception {
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		SdTestGate.stampInstalledModel(cfg, testProperties);
		cfg.set("steps", steps);
		cfg.set("refinerSteps", steps);
		cfg.set("cfg", 5);
		cfg.set("hires", Boolean.FALSE);
		cfg.set("compositeMode", "classic");
		cfg.set("negativePrompt", "text, watermark, blurry");
		return cfg;
	}

	/** Real SDUtil whose two image entry points throw on every call. */
	private SDUtil throwingSdu(String message) {
		return new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				throw new RuntimeException(message);
			}
			@Override
			public List<BaseRecord> createSceneImage(BaseRecord user, String groupPath, String name, SWTxt2Img s2i,
					String sysCharOid, String usrCharOid) {
				throw new RuntimeException(message);
			}
		};
	}

	/** Real SDUtil that records every scene request it is handed and then throws on every call. */
	private SDUtil capturingThrowingSdu(String message, List<SWTxt2Img> sent) {
		return new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				throw new RuntimeException(message);
			}
			@Override
			public List<BaseRecord> createSceneImage(BaseRecord user, String groupPath, String name, SWTxt2Img s2i,
					String sysCharOid, String usrCharOid) {
				sent.add(s2i);
				throw new RuntimeException(message);
			}
		};
	}

	/** Real SDUtil against the live server that fails ONLY the landscape render. */
	private SDUtil landscapeOnlyThrowingSdu(String server, String message) {
		return new SDUtil(SDAPIEnumType.SWARM, server) {
			@Override
			public List<BaseRecord> createSceneImage(BaseRecord user, String groupPath, String name, SWTxt2Img s2i,
					String sysCharOid, String usrCharOid) {
				if(name != null && name.startsWith("landscape_")) {
					throw new RuntimeException(message);
				}
				return super.createSceneImage(user, groupPath, name, s2i, sysCharOid, usrCharOid);
			}
		};
	}

	// ─────────────────────────────── reads (all uncached) ───────────────────────────────

	private Map<String, Object> readSceneJson(BaseRecord user, String sceneOid) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(user));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
		q.setCache(false);
		BaseRecord note = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		assertNotNull("scene note " + sceneOid + " must be readable", note);
		String text = note.get("text");
		assertNotNull("scene note " + sceneOid + " must have text", text);
		Map<String, Object> map = JSONUtil.getMap(text.getBytes(), String.class, Object.class);
		assertNotNull("scene note " + sceneOid + " text must be JSON: " + text, map);
		return map;
	}

	/** Rewrites the scene note's JSON text in place (identity + name + text, the fields that were read). */
	private void writeSceneJson(BaseRecord user, String sceneOid, Map<String, Object> json) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, sceneOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(user));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
		q.setCache(false);
		BaseRecord note = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		assertNotNull("scene note " + sceneOid + " must be readable for rewrite", note);
		note.set("text", JSONUtil.exportObject(json));
		assertNotNull("scene note " + sceneOid + " rewrite must persist",
			IOSystem.getActiveContext().getAccessPoint().update(user, note));
	}

	private String readMetaText(BaseRecord user, String bookGroupOid) throws Exception {
		BaseRecord bookGroup = PictureBookUtil.findBookGroup(user, bookGroupOid);
		assertNotNull("book group " + bookGroupOid + " must resolve", bookGroup);
		Query mq = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_GROUP_ID, bookGroup.get(FieldNames.FIELD_ID));
		mq.field(FieldNames.FIELD_NAME, PictureBookUtil.META_NOTE_NAME);
		mq.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(user));
		mq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "text" });
		mq.setCache(false);
		BaseRecord meta = IOSystem.getActiveContext().getAccessPoint().find(user, mq);
		return (meta != null ? meta.get("text") : null);
	}

	private BaseRecord freshReadData(BaseRecord user, String objectId) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(user));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return ioContext.getAccessPoint().find(user, q);
	}

	private BaseRecord workflowOf(BaseRecord user, String slug) {
		BaseRecord book = PbBookUtil.findBookBySlug(user, slug, orgOf(user));
		assertNotNull("book " + slug + " must resolve", book);
		return PbGraphUtil.findWorkflow(user, book);
	}

	/** The newest olio.pb.run of the workflow (highest id), read uncached. */
	private BaseRecord latestRun(BaseRecord user, BaseRecord workflow) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, workflow);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(user));
		q.setRequest(PbGraphUtil.runRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		if(recs == null || recs.length == 0) {
			return null;
		}
		BaseRecord best = null;
		long bestId = -1L;
		for(BaseRecord r : recs) {
			if(r.getSchema() == null) r.setSchema(OlioModelNames.MODEL_PB_RUN);
			long id = ((Number) r.get(FieldNames.FIELD_ID)).longValue();
			if(id > bestId) {
				bestId = id;
				best = r;
			}
		}
		return best;
	}

	/** The no-graph classic branch dumps ./comp-<oid>.png and ./land-<oid>.png into the process cwd. */
	private static void cleanupStrayEmits(String sceneOid) {
		for(String n : new String[] { "./comp-" + sceneOid + ".png", "./land-" + sceneOid + ".png" }) {
			File f = new File(n);
			if(f.exists() && !f.delete()) {
				logger.warn("Could not delete stray emit " + f.getAbsolutePath());
			}
		}
	}

	// ─────────────────────────────── log capture ───────────────────────────────

	private static final class CountingAppender extends AbstractAppender {
		final List<String> messages = Collections.synchronizedList(new ArrayList<String>());
		final List<Throwable> thrown = Collections.synchronizedList(new ArrayList<Throwable>());

		CountingAppender(String name) {
			super(name, null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
			if(event.getThrown() != null) {
				thrown.add(event.getThrown());
			}
		}
	}

	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	private static <T> T runWithAppenderOn(Class<?> loggerOwner, CountingAppender app, ThrowingSupplier<T> body)
			throws Exception {
		org.apache.logging.log4j.core.Logger target =
			(org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerOwner);
		app.start();
		target.addAppender(app);
		try {
			return body.get();
		}
		finally {
			target.removeAppender(app);
			app.stop();
		}
	}
}
