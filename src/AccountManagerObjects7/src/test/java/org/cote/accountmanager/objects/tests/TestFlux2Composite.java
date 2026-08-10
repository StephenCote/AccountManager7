package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.Flux2Defaults;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.olio.sd.swarm.SWImageResponse;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.junit.Test;

import jakarta.ws.rs.core.MediaType;

/// FLUX.2 multi-reference scene composite.
///
/// Split into two halves deliberately:
///  - The prompt/request-shape tests are pure and always run. They pin the things that were actually
///    wrong in the Kontext path (SDXL CFG handed to an edit model, square output, center-cropped
///    references, a prompt that let the model draw the reference sheet into the scene).
///  - The live generation test hits the real Swarm server and is gated behind FLUX2_LIVE=1, per the
///    project convention for LLM/SD-touching tests. It writes its output to media/flux/out/ so the
///    result can actually be LOOKED at — for an image pipeline, "an image came back" is not a pass.
///
/// Fixtures are Stephen's staged images in AccountManagerObjects7/media/flux:
///   character1.png  2048x2048  grey-haired bearded man, pink/red jacket, olive boots
///   character2.png  2048x2048  young woman, long strawberry-blonde hair, green/teal outfit
///   landscape1.png  1024x768   desaturated rainy street, clapboard house, power lines
///   bad.composite.png / bad.merge.png  the failures this path replaces
public class TestFlux2Composite {
	public static final Logger logger = LogManager.getLogger(TestFlux2Composite.class);

	private static final String MEDIA = "./media/flux/";
	private static final String OUT = MEDIA + "out/";

	private static final String LEFT_DESC = "a lean man in his fifties with short grey hair and a grey beard, "
		+ "wearing a coral-pink jacket over a patterned shirt, rust-red cargo trousers and olive boots";
	private static final String RIGHT_DESC = "a young woman with long wavy strawberry-blonde hair, "
		+ "wearing a green and white striped satin tunic over lime-green trousers and teal boots";
	private static final String ACTION = "standing together on the wet pavement, talking";
	private static final String SETTING = "a rain-soaked street beside a weathered clapboard house, "
		+ "overhead power lines, bare winter trees, overcast light, puddles reflecting the sky";
	private static final String MOOD = "bleak and quiet, flat grey overcast daylight";

	private static byte[] fixture(String name) throws Exception {
		File f = new File(MEDIA + name);
		assertTrue("Missing staged fixture " + f.getAbsolutePath(), f.exists());
		return Files.readAllBytes(f.toPath());
	}

	private static boolean liveEnabled() {
		return "1".equals(System.getenv("FLUX2_LIVE")) || "1".equals(System.getProperty("flux2.live"));
	}

	private static int envInt(String name, int def) {
		String v = System.getenv(name);
		if (v == null || v.isBlank()) return def;
		try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
	}

	// ── Pure request-shape tests (always run) ────────────────────────────────

	/// The request must be built from the EDITABLE RESOURCE, and must carry the structural properties
	/// that were wrong in the Kontext path.
	///
	/// Values are asserted against Flux2Defaults rather than literals. Hardcoding 2.5/24/1024x768 here
	/// made this test fail the moment flux2Defaults.json was tuned, reporting a regression against
	/// working code - and it defeated the purpose of externalizing the settings by re-embedding them in
	/// a test. Range checks ("CFG within 1.0-3.5") are gone for the same reason: that band is guidance
	/// for a human tuning the file, not an invariant the code must enforce, and asserting it would
	/// block a deliberate experiment outside the band.
	@Test
	public void requestUsesEditModelParameters() {
		SWTxt2Img req = SWUtil.newFlux2SceneTxt2Img(LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, null, 3);
		assertEquals("Must target the installed FLUX.2 checkpoint", SWUtil.defaultFlux2Model(), req.getModel());
		assertEquals("CFG must come from flux2Defaults.json", Flux2Defaults.cfgScale(), req.getCfgScale(), 0.001);
		assertEquals("Steps must come from flux2Defaults.json", Flux2Defaults.steps(), req.getSteps());
		assertEquals("Width must come from flux2Defaults.json", Flux2Defaults.width(), req.getWidth());
		assertEquals("Height must come from flux2Defaults.json", Flux2Defaults.height(), req.getHeight());
		assertEquals("Sampler must come from flux2Defaults.json", Flux2Defaults.sampler(), req.getSampler());
		assertEquals("Scheduler must come from flux2Defaults.json", Flux2Defaults.scheduler(), req.getScheduler());
		/// Structural invariants - true regardless of tuning.
		assertEquals("The SDXL refiner block must stay inert for a FLUX checkpoint",
			0.0, req.getRefinerControlPercentage(), 0.001);
		assertEquals("Negative prompt must be the short targeted one, not the SDXL NEG_PROMPT",
			SWUtil.flux2NegativePrompt(), req.getNegativePrompt());
	}

	/// The prompt must carry the doc's multi-reference wording AND the instruction that fixes the
	/// observed failure (the reference sheet rendered as a poster in the scene).
	@Test
	public void promptNamesSourcesAndForbidsDrawingTheReferences() {
		String p = SWUtil.buildFlux2ScenePrompt(LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, null, 3);
		logger.info("FLUX.2 prompt: " + p);
		assertTrue("must name the first reference", p.contains("first reference image"));
		assertTrue("must name the second reference", p.contains("second reference image"));
		assertTrue("must name the setting reference", p.contains("third reference image"));
		assertTrue("must demand identity preservation", p.contains("Preserve facial identity"));
		assertTrue("must demand consistent lighting/scale/perspective", p.contains("Matching lighting, scale, and perspective"));
		assertTrue("must forbid extra people", p.contains("No extra people"));
		// The bad.composite.png failure: the model drew the reference sheet into the scene.
		assertTrue("must forbid depicting the references as objects", p.contains("Do not draw the reference images"));
		assertTrue("must explicitly rule out a poster", p.contains("poster"));
		assertTrue("must rule out a panelled/collage result", p.contains("no panels"));
		assertTrue("must carry the character appearance", p.contains("grey beard"));
	}

	/// The prompt that actually reaches SD must be plain ASCII.
	///
	/// Proven necessary by a received Swarm payload on 2026-08-08 containing "graffiti<U+2011>scarred"
	/// and "gull<U+2011>wing" - non-breaking hyphens the LLM emitted in the scene setting. The ASCII
	/// normalization had been installed at the SDUtil.appendLoras seam on the stated grounds that every
	/// setPrompt call in the SD layer passed through it; the FLUX.2 and Kontext builders set the prompt
	/// directly and did not, so the fix never applied to the composite - the one prompt most likely to
	/// carry LLM-generated text. Asserts on the built REQUEST, not on buildFlux2ScenePrompt, because the
	/// normalization happens at the seam between them.
	@Test
	public void builtRequestPromptIsNormalizedToAscii() {
		/// U+2011 non-breaking hyphens and a U+2014 em dash, exactly as an LLM emits them.
		String setting = "a graffiti‑scarred house on a narrow street — a gull‑wing cab at the curb";
		String action = "sliding her onto the glossy pleather seat — rain‑slicked";
		SWTxt2Img req = SWUtil.newFlux2SceneTxt2Img(LEFT_DESC, RIGHT_DESC, action, setting, MOOD, null, 3);
		String p = req.getPrompt();

		for(int i = 0; i < p.length(); i++) {
			char c = p.charAt(i);
			assertTrue("non-ASCII U+" + String.format("%04X", (int) c) + " reached the SD prompt at index "
				+ i + " - CLIP tokenizes it differently from its ASCII equivalent: " + p, c < 128);
		}
		assertTrue("the compound must survive as an ASCII hyphen", p.contains("graffiti-scarred"));
		assertTrue("the compound must survive as an ASCII hyphen", p.contains("gull-wing"));
		assertTrue("the compound must survive as an ASCII hyphen", p.contains("rain-slicked"));
	}

	/// A one-character scene must not claim a "second reference image" that was never attached.
	@Test
	public void promptAdaptsToReferenceCount() {
		String one = SWUtil.buildFlux2ScenePrompt(LEFT_DESC, null, ACTION, SETTING, MOOD, null, 2);
		assertFalse("a single-person scene must not reference a second person", one.contains("second person"));
		assertTrue("the setting ref is second when there is only one person", one.contains("second reference image"));
		assertFalse("must not claim a third reference that isn't attached", one.contains("third reference image"));
	}

	/// SDXL-tuned parameters on the shared config MUST NOT reach the FLUX.2 composite.
	///
	/// This is a real regression, caught while wiring TestPictureBookCustom: the builder originally
	/// read the shared steps/width/height, and the model schema gives those non-null defaults
	/// (steps 20, width 1024, height 1024). "Fall back when unset" therefore never fired, so any real
	/// book config silently forced the composite to 1024x1024 square at the SDXL step count — exactly
	/// the Kontext defect this path was written to fix. The first live run only escaped it by passing
	/// a null config.
	/// Asserts against Flux2Defaults, NOT against literal numbers.
	///
	/// This test hardcoded 2.5/24/1024x768 and broke the first time Stephen tuned
	/// olio/sd/flux2Defaults.json to steps=4 - reporting a "regression" when the code was fine and the
	/// resource had simply been used as designed. Hardcoding the values duplicated the resource the
	/// whole change exists to decouple from. The contract here is "the SDXL config's values do not
	/// reach the FLUX request, the resource's do" - which is exactly what this now says, and it holds
	/// at any tuning.
	@Test
	public void sdxlTunedConfigValuesDoNotLeakIntoTheFlux2Request() throws Exception {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_SD_CONFIG);
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		// Exactly what TestPictureBookCustom's buildCommonSdConfig pins for the SDXL stages.
		cfg.set("cfg", 5);
		cfg.set("steps", 40);
		cfg.set("width", 1024);
		cfg.set("height", 1024);

		SWTxt2Img req = SWUtil.newFlux2SceneTxt2Img(LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, cfg, 3);

		/// The leak assertions: the SDXL values must not appear.
		assertNotEquals("the SDXL cfg (5) must not reach a FLUX edit model", 5.0, req.getCfgScale(), 0.001);
		assertNotEquals("the SDXL step count (40) must not reach the composite", 40, req.getSteps());
		assertFalse("an SDXL square size must not force the composite square",
			req.getWidth() == 1024 && req.getHeight() == 1024);

		/// ...and the resource's values must, whatever they currently are.
		assertEquals("cfg must come from flux2Defaults.json", Flux2Defaults.cfgScale(), req.getCfgScale(), 0.001);
		assertEquals("steps must come from flux2Defaults.json", Flux2Defaults.steps(), req.getSteps());
		assertEquals("width must come from flux2Defaults.json", Flux2Defaults.width(), req.getWidth());
		assertEquals("height must come from flux2Defaults.json", Flux2Defaults.height(), req.getHeight());
	}

	/// compositeMode must actually exist on olio.sd.config and round-trip.
	///
	/// Worth its own test because TestPictureBookCustom.buildCommonSdConfig sets it with setValue(),
	/// which swallows FieldException/ModelNotFoundException and logs — so a missing or misspelled
	/// field would silently leave compositeMode null, PictureBookUtil would fall back to the legacy
	/// useKontext boolean (default false), and the whole book would quietly render on the classic
	/// pipeline while the test claimed to be exercising FLUX.2.
	@Test
	public void compositeModeIsARealFieldOnTheConfig() throws Exception {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_SD_CONFIG);
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg.set("compositeMode", "flux2");
		assertEquals("compositeMode must round-trip, or the picture-book test silently runs classic",
			"flux2", (String) cfg.get("compositeMode"));
	}

	/// ...but the flux2-prefixed fields MUST be honored, or the config is decorative.
	@Test
	public void flux2PrefixedConfigValuesAreHonored() throws Exception {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_SD_CONFIG);
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg.set("flux2Model", "someOtherFlux2");
		cfg.set("flux2Cfg", 3.0);
		cfg.set("flux2Steps", 28);
		cfg.set("flux2Width", 1280);
		cfg.set("flux2Height", 800);

		SWTxt2Img req = SWUtil.newFlux2SceneTxt2Img(LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, cfg, 3);
		assertEquals("someOtherFlux2", req.getModel());
		assertEquals(3.0, req.getCfgScale(), 0.001);
		assertEquals(28, req.getSteps());
		assertEquals(1280, req.getWidth());
		assertEquals(800, req.getHeight());
	}

	/// References must be letterboxed, never center-cropped. This is the concrete regression guard on
	/// the stitchSceneImages behavior that discarded 44% of a 1024x768 landscape's width.
	@Test
	public void referencesArePreparedWithoutCropping() throws Exception {
		byte[] land = fixture("landscape1.png");
		BufferedImage srcLand = ImageIO.read(new ByteArrayInputStream(land));
		assertEquals("fixture landscape width", 1024, srcLand.getWidth());
		assertEquals("fixture landscape height", 768, srcLand.getHeight());

		List<String> refs = SDUtil.buildFlux2References(1024, fixture("character1.png"), fixture("character2.png"), land);
		assertEquals("all three references must be prepared", 3, refs.size());
		for (String ref : refs) {
			assertTrue("each reference must be a PNG data URL", ref.startsWith("data:image/png;base64,"));
			byte[] raw = BinaryUtil.fromBase64(ref.substring("data:image/png;base64,".length()).getBytes());
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
			assertEquals("reference width normalized", 1024, img.getWidth());
			assertEquals("reference height normalized", 1024, img.getHeight());
		}

		// The landscape is 4:3, so fitting into a square must letterbox top and bottom (1024x768
		// scales to 1024x768 centered => 128px bars), NOT crop the sides away. Sample the top bar.
		byte[] landRef = BinaryUtil.fromBase64(refs.get(2).substring("data:image/png;base64,".length()).getBytes());
		BufferedImage landImg = ImageIO.read(new ByteArrayInputStream(landRef));
		assertEquals("top letterbox must be the pad colour, proving nothing was cropped",
			0xFFFFFF, landImg.getRGB(512, 5) & 0xFFFFFF);
		assertEquals("bottom letterbox must be the pad colour", 0xFFFFFF, landImg.getRGB(512, 1018) & 0xFFFFFF);
		// ...and the full original width must still be present at the vertical centre.
		assertTrue("the far-left column of the landscape must survive",
			(landImg.getRGB(2, 512) & 0xFFFFFF) != 0xFFFFFF);
		assertTrue("the far-right column of the landscape must survive",
			(landImg.getRGB(1021, 512) & 0xFFFFFF) != 0xFFFFFF);
	}

	// ── Shared pipeline resolution (SceneCompositeUtil) ──────────────────────

	private static BaseRecord sdConfig() throws Exception {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_SD_CONFIG);
		return RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
	}

	/// compositeMode wins over the legacy boolean, in both directions.
	@Test
	public void compositeModeOverridesTheLegacyBoolean() throws Exception {
		BaseRecord cfg = sdConfig();
		cfg.set("useKontext", true);
		cfg.set("compositeMode", "flux2");
		assertEquals("compositeMode must win over useKontext=true",
			SceneCompositeUtil.MODE_FLUX2, SceneCompositeUtil.resolveMode(cfg, true));

		cfg.set("compositeMode", "classic");
		assertEquals("compositeMode must win in the other direction too",
			SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(cfg, true));
	}

	/// A bare, schema-built config must resolve to FLUX.2 — the mode whose checkpoint actually ships.
	///
	/// History, because the trap is worth keeping: useKontext was declared `"default": true`, and a
	/// schema default is never null, so "never assigned" was indistinguishable from "explicitly true"
	/// and every book config that omitted the field silently ran Kontext. Kontext then requested
	/// kontextModel's own default flux1Kontext_flux1KontextDev, which is not installed on the local
	/// Swarm, so it was refused with "Invalid model value for param Model" and the composite produced
	/// nothing at all.
	///
	/// Fixed 2026-08-10 by making the DEFAULTS honest: compositeMode defaults to "flux2",
	/// useKontext defaults to false, and kontextModel has no default (Kontext is now an explicit
	/// opt-in that must name a checkpoint the target node carries).
	@Test
	public void bareConfigResolvesToFlux2ByDefault() throws Exception {
		BaseRecord bare = sdConfig();
		assertEquals("useKontext must no longer default to true",
			Boolean.FALSE, (Boolean) bare.get("useKontext"));
		assertEquals("compositeMode must default to flux2", "flux2", (String) bare.get("compositeMode"));
		assertEquals(SceneCompositeUtil.MODE_FLUX2, SceneCompositeUtil.resolveMode(bare, true));
		assertEquals("the compositeMode default wins over any caller-supplied legacy default",
			SceneCompositeUtil.MODE_FLUX2, SceneCompositeUtil.resolveMode(bare, false));
		assertTrue("kontextModel must have no default — the old one named an uninstalled checkpoint",
			bare.get("kontextModel") == null || ((String) bare.get("kontextModel")).isEmpty());
	}

	/// legacyKontextDefault only actually applies where the field is genuinely absent.
	@Test
	public void legacyDefaultAppliesOnlyWithNoConfig() {
		assertEquals("a null config takes the caller's default",
			SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(null, false));
		assertEquals("a null config takes the caller's default, other direction",
			SceneCompositeUtil.MODE_KONTEXT, SceneCompositeUtil.resolveMode(null, true));
	}

	/// An explicit useKontext must still be honored when compositeMode is absent, so existing saved
	/// configs keep working.
	@Test
	public void legacyBooleanStillHonoredWhenModeAbsent() throws Exception {
		BaseRecord cfg = sdConfig();
		/// compositeMode now carries a "flux2" default and compositeMode WINS, so the legacy boolean
		/// is only reachable once the mode is genuinely cleared — which is exactly the situation a
		/// saved pre-compositeMode config presents.
		cfg.set("compositeMode", "");
		cfg.set("useKontext", false);
		assertEquals(SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(cfg, true));
		cfg.set("useKontext", true);
		assertEquals(SceneCompositeUtil.MODE_KONTEXT, SceneCompositeUtil.resolveMode(cfg, false));
	}

	/// A typo must degrade to classic with a warning, not silently pick a reference pipeline.
	@Test
	public void unrecognizedModeFallsBackToClassic() throws Exception {
		BaseRecord cfg = sdConfig();
		cfg.set("compositeMode", "fluxx2");
		assertEquals(SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(cfg, true));
	}

	/// The landscape reference is optional and ON by default. Every reference is encoded into FLUX.2's
	/// context and attention cost grows faster than linearly in it, so the third reference is real GPU
	/// time: 10.64 min on the local Beelink GTR9 (Strix Halo iGPU) versus ~3.3 min for the same
	/// three-reference request on the DGX Spark. Memory is not the constraint on either box. Dropping
	/// the reference must be a config flip, not a code change, and must be trivially reversible.
	@Test
	public void landscapeReferenceIsOptionalAndOnByDefault() throws Exception {
		byte[] c1 = fixture("character1.png");
		byte[] c2 = fixture("character2.png");
		byte[] land = fixture("landscape1.png");

		SWTxt2Img on = SceneCompositeUtil.buildSceneRequest(SceneCompositeUtil.MODE_FLUX2,
			LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, "p", "n", c1, c2, land, 0.85, null);
		assertEquals("a null config must keep the landscape reference (default on)",
			3, on.getPromptImages().size());

		BaseRecord cfg = sdConfig();
		cfg.set("flux2IncludeLandscapeRef", false);
		SWTxt2Img off = SceneCompositeUtil.buildSceneRequest(SceneCompositeUtil.MODE_FLUX2,
			LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, "p", "n", c1, c2, land, 0.85, cfg);
		assertEquals("flux2IncludeLandscapeRef=false must drop the setting reference only",
			2, off.getPromptImages().size());
		assertTrue("the setting must still reach the model as prompt text",
			off.getPrompt().contains("rain-soaked street"));

		cfg.set("flux2IncludeLandscapeRef", true);
		SWTxt2Img back = SceneCompositeUtil.buildSceneRequest(SceneCompositeUtil.MODE_FLUX2,
			LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, "p", "n", c1, c2, land, 0.85, cfg);
		assertEquals("re-enabling must restore the third reference with no code change",
			3, back.getPromptImages().size());
	}

	/// Every flux2 config field must be honored by the SHARED builder, because both the picture-book
	/// and chat paths now go through it.
	///
	/// This exists because they did NOT both go through it: flux2IncludeLandscapeRef was implemented in
	/// SceneCompositeUtil while PictureBookUtil still assembled its own request inline, so the field was
	/// silently ignored for every picture-book scene. A toggle was added to TestPictureBookCustom that
	/// did nothing. Asserting the fields against the shared builder is what makes that class of drift
	/// impossible rather than merely unlikely.
	@Test
	public void sharedBuilderHonorsEveryFlux2ConfigField() throws Exception {
		BaseRecord cfg = sdConfig();
		cfg.set("flux2Cfg", 3.0);
		cfg.set("flux2Steps", 28);
		cfg.set("flux2Width", 1280);
		cfg.set("flux2Height", 800);
		cfg.set("flux2ReferenceSize", 768);
		cfg.set("flux2IncludeLandscapeRef", false);

		SWTxt2Img req = SceneCompositeUtil.buildSceneRequest(SceneCompositeUtil.MODE_FLUX2,
			LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, "classic prompt", "classic negative",
			fixture("character1.png"), fixture("character2.png"), fixture("landscape1.png"),
			0.85, cfg);
		assertNotNull(req);
		assertEquals("flux2Cfg", 3.0, req.getCfgScale(), 0.001);
		assertEquals("flux2Steps", 28, req.getSteps());
		assertEquals("flux2Width", 1280, req.getWidth());
		assertEquals("flux2Height", 800, req.getHeight());
		assertEquals("flux2IncludeLandscapeRef=false must drop the landscape reference",
			2, req.getPromptImages().size());

		// flux2ReferenceSize must reach the reference preparation, not just be accepted and ignored.
		byte[] raw = BinaryUtil.fromBase64(
			req.getPromptImages().get(0).substring("data:image/png;base64,".length()).getBytes());
		BufferedImage refImg = ImageIO.read(new ByteArrayInputStream(raw));
		assertEquals("flux2ReferenceSize must size the prepared references", 768, refImg.getWidth());
		assertEquals("flux2ReferenceSize must size the prepared references", 768, refImg.getHeight());
	}

	/// The shared builder must produce a real FLUX.2 request with its references attached — this is
	/// what the chat endpoint now depends on.
	@Test
	public void sharedBuilderProducesTheFlux2Request() throws Exception {
		SWTxt2Img req = SceneCompositeUtil.buildSceneRequest(SceneCompositeUtil.MODE_FLUX2,
			LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, "classic prompt", "classic negative",
			fixture("character1.png"), fixture("character2.png"), fixture("landscape1.png"),
			0.85, null);
		assertNotNull(req);
		assertEquals(SWUtil.defaultFlux2Model(), req.getModel());
		/// From the resource, not a literal - and specifically NOT the 0.85 classic creativity passed in
		/// as an argument, which is what this guards against.
		assertEquals("CFG must come from flux2Defaults.json, not the classic creativity argument",
			Flux2Defaults.cfgScale(), req.getCfgScale(), 0.001);
		assertNotNull("references must be attached", req.getPromptImages());
		assertEquals("all three references must be attached", 3, req.getPromptImages().size());
		assertTrue("FLUX.2 must not use an init image", req.getInitImage() == null);
	}

	// ── Live generation (gated) ──────────────────────────────────────────────

	/// Real generation against the live Swarm server using the staged fixtures. Writes the result to
	/// media/flux/out/ for visual inspection — the only way to judge an image pipeline.
	/// Run with:  FLUX2_LIVE=1 mvn -o -pl AccountManagerObjects7 -Dtest=TestFlux2Composite test
	@Test
	public void liveFlux2CompositeAgainstStagedFixtures() throws Exception {
		if (!liveEnabled()) {
			logger.info("Skipping live FLUX.2 generation (set FLUX2_LIVE=1 to run)");
			return;
		}
		String server = System.getenv("FLUX2_SERVER");
		if (server == null || server.isEmpty()) server = "http://192.168.1.42:7801";

		/// Test-only knobs for measuring generation cost. FLUX.2 encodes every reference image into
		/// its context, so reference SIZE and COUNT are the two levers that move wall-clock time;
		/// FLUX2_REF_SIZE / FLUX2_REF_COUNT exist so that can be measured instead of guessed.
		int refSize = envInt("FLUX2_REF_SIZE", 1024);
		int refCount = envInt("FLUX2_REF_COUNT", 3);
		byte[][] all = new byte[][] { fixture("character1.png"), fixture("character2.png"), fixture("landscape1.png") };
		byte[][] use = new byte[Math.min(refCount, all.length)][];
		System.arraycopy(all, 0, use, 0, use.length);

		List<String> refs = SDUtil.buildFlux2References(refSize, use);
		assertEquals(use.length + " references expected", use.length, refs.size());

		SWTxt2Img req = SWUtil.newFlux2SceneTxt2Img(LEFT_DESC, RIGHT_DESC, ACTION, SETTING, MOOD, null, refs.size());
		req.setPromptImages(refs);
		int steps = envInt("FLUX2_STEPS", req.getSteps());
		req.setSteps(steps);
		long t0 = System.currentTimeMillis();
		logger.info("FLUX.2 live request: model=" + req.getModel() + " cfg=" + req.getCfgScale()
			+ " steps=" + req.getSteps() + " " + req.getWidth() + "x" + req.getHeight() + " refs=" + refs.size());
		logger.info("FLUX.2 live prompt: " + req.getPrompt());

		logger.info("FLUX.2 TIMING run: server=" + server + " refs=" + refs.size() + "@" + refSize
			+ "px steps=" + steps + " " + req.getWidth() + "x" + req.getHeight());
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		SWImageResponse rep = sdu.txt2img(req);
		logger.info("FLUX.2 TIMING result: server=" + server + " refs=" + refs.size() + "@" + refSize
			+ "px steps=" + steps + " elapsed=" + ((System.currentTimeMillis() - t0) / 1000) + "s");
		assertNotNull("Swarm returned no response — is " + server + " reachable and flux2Klein_9b installed?", rep);
		assertNotNull("Swarm returned an error instead of images: " + rep.getError(), rep.getImages());
		assertFalse("Swarm returned an empty images list", rep.getImages().isEmpty());

		new File(OUT).mkdirs();
		int i = 0;
		for (String path : rep.getImages()) {
			byte[] data = ClientUtil.get(byte[].class, ClientUtil.getResource(server + "/" + path), null,
				MediaType.APPLICATION_OCTET_STREAM_TYPE);
			assertNotNull("Could not fetch generated image " + path, data);
			assertTrue("Generated image is empty", data.length > 0);
			File outFile = new File(OUT + "flux2_composite_" + (++i) + ".png");
			Files.write(outFile.toPath(), data);
			BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
			assertNotNull("Generated bytes must decode as an image", img);
			assertEquals("Generated width must match the request", req.getWidth(), img.getWidth());
			assertEquals("Generated height must match the request", req.getHeight(), img.getHeight());
			logger.info("FLUX.2 composite written for INSPECTION: " + outFile.getAbsolutePath()
				+ " (" + img.getWidth() + "x" + img.getHeight() + ", " + data.length + " bytes)");
		}
	}
}
