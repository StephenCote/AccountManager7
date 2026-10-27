package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * V3 Phase 5, P5-1: generate-and-patch (design §38.2) against the generator graphs {@code .39} produced
 * in the P5-0 spike. {@link FixtureGraphClient} answers the generator call from the captured fixtures with
 * the request's images / prompts / seed / steps substituted the way the real generator writes them; the
 * patch step, the inputs, the literals check and the structural validation are the production code. No
 * server, no GPU, no DB.
 * <p>
 * What this does NOT cover: whether {@code .39}'s generator still produces these node shapes today (the
 * gated {@code TestP51GraphLive.t00} drift probe), and whether Swarm accepts the patched graph (gated
 * {@code t01}-{@code t03}).
 */
public class TestWorkflowCompiler {

	static final String FLUX2 = "flux2Klein_9b";
	static final String SDXL = "juggernautXL_ragnarokBy";
	static final String SCENE = "Touvier, a lean man in his fifties wearing a bright yellow scarf, stands at the rail of a river barge at dusk";
	static final String NEGATIVE = "blurry faces, deformed, extra people, missing limbs";
	static final int W = 1024;
	static final int H = 768;

	static StageRequest request(String model, int steps, double cfg, String negative, int seed) {
		return StageRequest.builder()
			.stage(RenderPlan.STAGE_BASE)
			.prompt(new CompiledPrompt(SCENE, negative, 0))
			.model(model)
			.width(W).height(H)
			.steps(steps).cfg(cfg)
			.sampler("euler").scheduler("simple")
			.seed(seed)
			.build();
	}

	static StageRequest flux2Request() {
		return request(FLUX2, 4, 2.0, NEGATIVE, 424242);
	}

	static List<WorkflowCompiler.Reference> references(int n) {
		List<WorkflowCompiler.Reference> refs = new ArrayList<>();
		String[] names = {"actorA", "actorB", "actorC", "actorD", "actorE", "actorF", "actorG"};
		for(int i = 0; i < n; i++) {
			/// Distinct greys so each reference's bytes are its own.
			refs.add(new WorkflowCompiler.Reference(names[i], "art-" + names[i], MaskImage.solid(96, 128, new Color(40 + i * 20, 40 + i * 20, 40 + i * 20))));
		}
		return refs;
	}

	static WorkflowCompiler.ActorTarget target(byte[] base, RenderPlan.Box... boxes) {
		RenderPlan.Actor actor = new RenderPlan.Actor("A", "Touvier", RenderPlan.ROLE_FOREGROUND, "a lean man in his fifties", true);
		return new WorkflowCompiler.ActorTarget(actor, base, "art-base", List.of(boxes));
	}

	static String tagOf(String type, String name) {
		return ComfyGraph.tag(type, name);
	}

	// ─────────────────────────────── BASE / FLUX.2 ───────────────────────────────

	@Test
	public void baseFlux2ThreeReferencesPatchesEverySlot() {
		FixtureGraphClient client = new FixtureGraphClient();
		List<WorkflowCompiler.Reference> refs = references(3);
		WorkflowCompiler.CompiledGraph c = new WorkflowCompiler().compileBase(flux2Request(), refs, client);

		assertEquals(RenderPlan.STAGE_BASE, c.getStage());
		ComfyGraph g = c.getGraph();
		assertEquals("one save node removed, one added", 18, g.size());
		assertFalse("the numeric save node is gone", g.hasNode("9"));
		assertTrue(g.hasNode(ComfyGraph.SAVE_FINAL));
		assertEquals(ComfyGraph.CLASS_SAVE_IMAGE_WS, g.classType(ComfyGraph.SAVE_FINAL));
		assertEquals("save wiring copied from the generator's save node", "8", ComfyGraph.linkTarget(g.inputs(ComfyGraph.SAVE_FINAL).get("images")));
		assertEquals(List.of(ComfyGraph.SAVE_FINAL), g.saveNodeIds());

		/// Image slots: every SwarmLoadImageB64 now carries a tag and each reference was located.
		for(String id : g.nodesOf(ComfyGraph.CLASS_LOAD_IMAGE_B64)) {
			assertTrue("node " + id + " is tagged", ComfyGraph.isTag((String) g.inputs(id).get(ComfyGraph.INPUT_IMAGE_BASE64)));
		}
		assertEquals(Set.of("actorA", "actorB", "actorC"), c.getPlaceholderMatches().keySet());
		for(String how : c.getPlaceholderMatches().values()) {
			assertTrue("exact base64 match when the generator echoes the placeholder: " + how, how.startsWith("base64:"));
		}
		assertEquals(tagOf(ComfyGraph.TYPE_IMAGE, "actorA"), g.inputs("102").get(ComfyGraph.INPUT_IMAGE_BASE64));
		assertEquals(tagOf(ComfyGraph.TYPE_IMAGE, "actorB"), g.inputs("103").get(ComfyGraph.INPUT_IMAGE_BASE64));
		assertEquals(tagOf(ComfyGraph.TYPE_IMAGE, "actorC"), g.inputs("104").get(ComfyGraph.INPUT_IMAGE_BASE64));
		g.assertNoInlineImages();

		/// Prompts and seed. The 2026-10-26 generator encodes flux2 prompts with a plain CLIPTextEncode (the
		/// P5-0 capture had SwarmTextEncodeAdvanced.prompt); the compiler's patch is class-agnostic.
		assertEquals(WorkflowCompiler.CLASS_TEXT_ENCODE, g.classType("6"));
		assertEquals(tagOf(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE), g.inputs("6").get("text"));
		assertEquals(tagOf(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_NEGATIVE), g.inputs("7").get("text"));
		assertEquals(tagOf(ComfyGraph.TYPE_SEED, WorkflowCompiler.TAG_SEED), g.inputs("10").get(WorkflowCompiler.INPUT_NOISE_SEED));

		/// Literals stay literals.
		assertEquals(4, ((Number) g.inputs("10").get(WorkflowCompiler.INPUT_STEPS)).intValue());
		assertEquals(0, ((Number) g.inputs("10").get(WorkflowCompiler.INPUT_START_AT_STEP)).intValue());
		assertEquals(2.0, ((Number) g.inputs("10").get("cfg")).doubleValue(), 0.0001);
		assertEquals("euler", g.inputs("10").get("sampler_name"));
		assertEquals(4, c.getSteps());
		assertEquals(0, c.getStartAtStep());
		assertEquals(424242L, c.getSeed());
		assertTrue("null inputs survive serialisation (model_negative)", g.toJson().contains("\"model_negative\":null"));

		/// Inputs fill exactly the tags.
		GraphInputs in = c.getInputs();
		Set<String> expected = new LinkedHashSet<>(List.of(
			ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE),
			ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_NEGATIVE),
			ComfyGraph.inputKey(ComfyGraph.TYPE_SEED, WorkflowCompiler.TAG_SEED),
			ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "actorA"),
			ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "actorB"),
			ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "actorC")));
		assertEquals(expected, in.names());
		assertEquals(expected, g.tags());
		assertEquals(SCENE, in.text(WorkflowCompiler.TAG_SCENE));
		assertEquals(NEGATIVE, in.text(WorkflowCompiler.TAG_NEGATIVE));
		assertEquals(Long.valueOf(424242L), in.seed(WorkflowCompiler.TAG_SEED));
		assertArrayEquals("the real reference bytes travel in the inputs, not the placeholder", refs.get(0).getPng(), in.image("actorA"));
		assertArrayEquals(refs.get(2).getPng(), in.image("actorC"));
		assertEquals(3, in.imageCount());
		assertEquals(List.of("art-actorA", "art-actorB", "art-actorC"), in.getReferenceArtifactIds());
		assertTrue(NodeCatalogue.validateStructure(g, in.names()).isEmpty());

		/// What the generator was asked for: markers and placeholders, never the real images.
		assertEquals(1, client.requests.size());
		Map<String, Object> asked = client.requests.get(0);
		assertEquals(WorkflowCompiler.MARKER_SCENE, asked.get("prompt"));
		assertEquals(WorkflowCompiler.MARKER_NEGATIVE, asked.get("negativeprompt"));
		assertEquals(Long.valueOf(WorkflowCompiler.MARKER_SEED), asked.get("seed"));
		assertEquals(FLUX2, asked.get("model"));
		assertEquals(Integer.valueOf(1), asked.get("images"));
		assertEquals(Integer.valueOf(4), asked.get("steps"));
		assertEquals(Integer.valueOf(W), asked.get("width"));
		assertEquals(Integer.valueOf(H), asked.get("height"));
		assertEquals(Double.valueOf(2.0), asked.get("cfgscale"));
		assertFalse(asked.containsKey("initimage"));
		List<?> promptImages = (List<?>) asked.get("promptimages");
		assertEquals(3, promptImages.size());
		for(Object o : promptImages) {
			assertTrue(String.valueOf(o).startsWith("data:image/png;base64,"));
		}

		/// The redacted generator params carry descriptions, no bytes.
		String redacted = String.valueOf(c.getGeneratorParams());
		assertFalse(redacted.contains("data:image"));
		assertTrue(redacted, redacted.contains("<placeholder actorA rgb(255,0,0) 1024x768>"));
		assertEquals(WorkflowCompiler.MARKER_SCENE, c.getGeneratorParams().get("prompt"));
	}

	@Test
	public void colourFallbackWhenTheGeneratorReencodesImages() {
		FixtureGraphClient client = new FixtureGraphClient().reencodeImages();
		WorkflowCompiler.CompiledGraph c = new WorkflowCompiler().compileBase(flux2Request(), references(3), client);
		assertEquals(Set.of("actorA", "actorB", "actorC"), c.getPlaceholderMatches().keySet());
		for(String how : c.getPlaceholderMatches().values()) {
			assertTrue("re-encoded placeholder located by its colour: " + how, how.startsWith("colour:"));
		}
		c.getGraph().assertNoInlineImages();
	}

	@Test
	public void noReferencesSendsNoPromptImagesAndKeepsNoLoader() {
		FixtureGraphClient client = new FixtureGraphClient();
		/// The captured base fixture carries three loaders; with no placeholders every one is an unmatched
		/// node, which must be rejected rather than left holding the fixture's bytes.
		try {
			new WorkflowCompiler().compileBase(flux2Request(), List.of(), client);
			fail("a loader node with no placeholder must be rejected, not silently kept");
		}
		catch(GraphCompileException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("matches no placeholder"));
		}
		assertFalse(client.requests.get(0).containsKey("promptimages"));
	}

	@Test
	public void emptyNegativeIsNotTagged() {
		FixtureGraphClient client = new FixtureGraphClient();
		WorkflowCompiler.CompiledGraph c = new WorkflowCompiler().compileBase(request(FLUX2, 4, 2.0, "", 7), references(3), client);
		assertFalse(client.requests.get(0).containsKey("negativeprompt"));
		assertFalse(c.getGraph().tags().contains(ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_NEGATIVE)));
		assertNull(c.getInputs().text(WorkflowCompiler.TAG_NEGATIVE));
		assertFalse(ComfyGraph.isTag((String) c.getGraph().inputs("7").get("text")));
	}

	@Test
	public void randomSeedIsChosenHereAndFitsAnInt() {
		FixtureGraphClient client = new FixtureGraphClient();
		WorkflowCompiler.CompiledGraph c = new WorkflowCompiler().compileBase(request(FLUX2, 4, 2.0, NEGATIVE, StageRequest.SEED_RANDOM), references(3), client);
		assertTrue(c.getSeed() >= 1L);
		assertTrue(c.getSeed() < Integer.MAX_VALUE);
		assertEquals(Long.valueOf(c.getSeed()), c.getInputs().seed(WorkflowCompiler.TAG_SEED));
		assertNotEquals(WorkflowCompiler.MARKER_SEED, c.getSeed());
		/// The generator never sees the real seed, only the marker.
		assertEquals(Long.valueOf(WorkflowCompiler.MARKER_SEED), client.requests.get(0).get("seed"));
	}

	@Test
	public void graphHashIsStableAcrossCompilesAndChangesWithTheStage() {
		WorkflowCompiler wc = new WorkflowCompiler();
		String h1 = wc.compileBase(flux2Request(), references(3), new FixtureGraphClient()).getGraph().graphHash();
		String h2 = wc.compileBase(request(FLUX2, 4, 2.0, NEGATIVE, 99), references(3), new FixtureGraphClient()).getGraph().graphHash();
		assertEquals("the seed is an input, not part of the graph", h1, h2);
		assertEquals(64, h1.length());
		String h3 = wc.compileActorRegion(flux2Request(), references(3), target(MaskImage.solid(W, H, Color.GRAY), new RenderPlan.Box(0.55, 0.2, 0.4, 0.8)),
			new RendererCapabilities.InpaintParams(4, 1.0, 16, 8), new FixtureGraphClient()).getGraph().graphHash();
		assertNotEquals(h1, h3);
	}

	// ─────────────────────────────── ACTOR_REGION ───────────────────────────────

	@Test
	public void actorRegionFlux2CarriesInitAndMaskAtFullCreativity() {
		FixtureGraphClient client = new FixtureGraphClient();
		byte[] base = MaskImage.solid(W, H, new Color(90, 120, 150));
		RenderPlan.Box box = new RenderPlan.Box(0.55, 0.2, 0.4, 0.8);
		WorkflowCompiler.CompiledGraph c = new WorkflowCompiler().compileActorRegion(flux2Request(), references(3), target(base, box),
			new RendererCapabilities.InpaintParams(4, 1.0, 16, 8), client);

		assertEquals(RenderPlan.STAGE_ACTOR_REGION, c.getStage());
		ComfyGraph g = c.getGraph();
		assertEquals(27, g.size());
		assertEquals(5, g.nodesOf(ComfyGraph.CLASS_LOAD_IMAGE_B64).size());
		g.assertNoInlineImages();
		assertEquals(Set.of("actorA", "actorB", "actorC", WorkflowCompiler.TAG_INIT, WorkflowCompiler.TAG_MASK), c.getPlaceholderMatches().keySet());
		assertEquals(tagOf(ComfyGraph.TYPE_IMAGE, WorkflowCompiler.TAG_MASK), g.inputs("102").get(ComfyGraph.INPUT_IMAGE_BASE64));
		assertEquals(tagOf(ComfyGraph.TYPE_IMAGE, WorkflowCompiler.TAG_INIT), g.inputs("15").get(ComfyGraph.INPUT_IMAGE_BASE64));
		assertEquals(tagOf(ComfyGraph.TYPE_IMAGE, "actorA"), g.inputs("108").get(ComfyGraph.INPUT_IMAGE_BASE64));

		assertEquals(4, c.getSteps());
		assertEquals("creativity 1.0 starts at step 0", 0, c.getStartAtStep());
		assertEquals(0, ((Number) g.inputs("10").get(WorkflowCompiler.INPUT_START_AT_STEP)).intValue());
		assertEquals("mask grow stays a literal", 16, ((Number) g.inputs(g.nodesOf("SwarmMaskGrow").get(0)).get("grow")).intValue());
		assertEquals("mask blur stays a literal", 8, ((Number) g.inputs(g.nodesOf("SwarmMaskBlur").get(0)).get("blur_radius")).intValue());

		GraphInputs in = c.getInputs();
		assertEquals(5, in.imageCount());
		assertArrayEquals("the init tag carries the base image", base, in.image(WorkflowCompiler.TAG_INIT));
		byte[] mask = in.image(WorkflowCompiler.TAG_MASK);
		assertNotNull(mask);
		assertEquals("mask covers the box (0.4 x 0.8)", 0.32, MaskImage.coverage(mask), 0.002);
		assertEquals(Color.BLACK, MaskImage.cornerColor(mask));
		assertEquals(List.of("art-actorA", "art-actorB", "art-actorC"), in.getReferenceArtifactIds());
		assertTrue(NodeCatalogue.validateStructure(g, in.names()).isEmpty());

		Map<String, Object> asked = client.requests.get(0);
		assertEquals(Double.valueOf(1.0), asked.get("initimagecreativity"));
		assertEquals(Integer.valueOf(16), asked.get("maskgrow"));
		assertEquals(Integer.valueOf(8), asked.get("maskblur"));
		assertEquals(Boolean.TRUE, asked.get("initimagerecompositemask"));
		assertTrue(String.valueOf(asked.get("initimage")).startsWith("data:image/png;base64,"));
		assertTrue(String.valueOf(asked.get("maskimage")).startsWith("data:image/png;base64,"));
		String redacted = String.valueOf(c.getGeneratorParams());
		assertFalse(redacted.contains("data:image"));
		assertTrue(redacted, redacted.contains("<placeholder init rgb(255,255,0) 1024x768>"));
		assertTrue(redacted, redacted.contains("<placeholder mask rgb(255,0,255) 1024x768>"));
	}

	@Test
	public void actorRegionFlux2AtPartialCreativityStartsMidway() {
		WorkflowCompiler.CompiledGraph c = new WorkflowCompiler().compileActorRegion(flux2Request(), references(3),
			target(MaskImage.solid(W, H, Color.GRAY), new RenderPlan.Box(0.1, 0.1, 0.5, 0.5)),
			new RendererCapabilities.InpaintParams(4, 0.6, 16, 8), new FixtureGraphClient());
		assertEquals(4, c.getSteps());
		assertEquals(2, c.getStartAtStep());
	}

	@Test
	public void actorRegionSdxlTakesNoReferences() {
		FixtureGraphClient client = new FixtureGraphClient();
		StageRequest req = request(SDXL, 25, 5.0, NEGATIVE, 424242);
		WorkflowCompiler.CompiledGraph c = new WorkflowCompiler().compileActorRegion(req, List.of(),
			target(MaskImage.solid(W, H, Color.GRAY), new RenderPlan.Box(0.55, 0.2, 0.4, 0.8)),
			new RendererCapabilities.InpaintParams(25, 0.6, 16, 8), client);
		ComfyGraph g = c.getGraph();
		/// 2026-10-26 generator: ModelSamplingDiscrete added (16 -> 17 nodes), the mask loader is "101", and
		/// the prompts ride CLIPTextEncodeSDXL with the marker in both text_g and text_l - the patch tags both.
		assertEquals(17, g.size());
		assertEquals(2, g.nodesOf(ComfyGraph.CLASS_LOAD_IMAGE_B64).size());
		assertEquals(Set.of(WorkflowCompiler.TAG_INIT, WorkflowCompiler.TAG_MASK), c.getPlaceholderMatches().keySet());
		assertEquals(tagOf(ComfyGraph.TYPE_IMAGE, WorkflowCompiler.TAG_MASK), g.inputs("101").get(ComfyGraph.INPUT_IMAGE_BASE64));
		assertEquals(tagOf(ComfyGraph.TYPE_IMAGE, WorkflowCompiler.TAG_INIT), g.inputs("15").get(ComfyGraph.INPUT_IMAGE_BASE64));
		assertEquals("CLIPTextEncodeSDXL", g.classType("6"));
		assertEquals(tagOf(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE), g.inputs("6").get("text_g"));
		assertEquals(tagOf(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE), g.inputs("6").get("text_l"));
		assertEquals(tagOf(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_NEGATIVE), g.inputs("7").get("text_g"));
		assertEquals(tagOf(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_NEGATIVE), g.inputs("7").get("text_l"));
		assertEquals(25, c.getSteps());
		assertEquals(10, c.getStartAtStep());
		assertEquals(5.0, ((Number) g.inputs("10").get("cfg")).doubleValue(), 0.0001);
		assertEquals(2, c.getInputs().imageCount());
		assertTrue(c.getInputs().getReferenceArtifactIds().isEmpty());
		assertFalse(client.requests.get(0).containsKey("promptimages"));
		assertTrue(NodeCatalogue.validateStructure(g, c.getInputs().names()).isEmpty());
	}

	// ─────────────────────────────── formula ───────────────────────────────

	@Test
	public void startAtStepFollowsCreativity() {
		assertEquals(2, WorkflowCompiler.startAtStep(4, 0.6));
		assertEquals(10, WorkflowCompiler.startAtStep(25, 0.6));
		assertEquals(0, WorkflowCompiler.startAtStep(4, 1.0));
		assertEquals(3, WorkflowCompiler.startAtStep(8, 0.6));
		assertEquals(5, WorkflowCompiler.startAtStep(12, 0.6));
		assertEquals("creativity above 1 clamps to 1", 0, WorkflowCompiler.startAtStep(4, 1.5));
		assertEquals("creativity below 0 clamps to 0", 25, WorkflowCompiler.startAtStep(25, -0.2));
	}

	// ─────────────────────────────── rejections ───────────────────────────────

	@Test
	public void moreThanSixReferencesIsRejectedBeforeTheGeneratorIsCalled() {
		FixtureGraphClient client = new FixtureGraphClient();
		try {
			new WorkflowCompiler().compileBase(flux2Request(), references(7), client);
			fail("expected GraphCompileException");
		}
		catch(GraphCompileException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("At most 6"));
		}
		assertTrue("never reached the generator", client.requests.isEmpty());
	}

	/// Swarm reduces request keys to lowercase letters, so `ref0` / `ref1` would share one image server-side.
	@Test
	public void referencesSwarmWouldMergeAreRejectedBeforeTheGeneratorIsCalled() {
		FixtureGraphClient client = new FixtureGraphClient();
		List<WorkflowCompiler.Reference> refs = List.of(
			new WorkflowCompiler.Reference("ref0", null, MaskImage.solid(8, 8, Color.RED)),
			new WorkflowCompiler.Reference("ref1", null, MaskImage.solid(8, 8, Color.BLUE)));
		try {
			new WorkflowCompiler().compileBase(flux2Request(), refs, client);
			fail("expected IllegalArgumentException");
		}
		catch(IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("collides"));
		}
		assertTrue("never reached the generator", client.requests.isEmpty());
	}

	/// Every image the request carries is keyed so Swarm fills its tag with base64 instead of uploading
	/// it to ComfyUI and writing the upload's filename into SwarmLoadImageB64 (run #2 of the live test,
	/// 2026-10-24: `init`, `mask` and `touvier` all failed that way; see ComfyGraph.IMAGE_KEY_MARKER).
	@Test
	public void everyImageRequestKeyCarriesTheSwarmBase64Marker() {
		WorkflowCompiler.CompiledGraph base = new WorkflowCompiler().compileBase(flux2Request(), references(3), new FixtureGraphClient());
		byte[] canvas = MaskImage.solid(W, H, new Color(120, 120, 120));
		WorkflowCompiler.CompiledGraph region = new WorkflowCompiler().compileActorRegion(flux2Request(), references(3),
			target(canvas, new RenderPlan.Box(0.1, 0.1, 0.3, 0.5)), new RendererCapabilities.InpaintParams(4, 1.0, 16, 8), new FixtureGraphClient());
		for(WorkflowCompiler.CompiledGraph c : List.of(base, region)) {
			Map<String, Object> body = c.getInputs().toRequestMap();
			Set<String> reduced = new LinkedHashSet<>();
			int images = 0;
			for(Map.Entry<String, Object> e : body.entrySet()) {
				assertTrue("distinct to Swarm: " + e.getKey(), reduced.add(ComfyGraph.swarmKey(e.getKey())));
				if(e.getValue() instanceof String && ((String) e.getValue()).startsWith("data:image/png;base64,")) {
					images++;
					assertTrue(e.getKey(), e.getKey().contains(ComfyGraph.IMAGE_KEY_MARKER));
				}
				else {
					assertFalse(e.getKey(), e.getKey().contains(ComfyGraph.IMAGE_KEY_MARKER));
				}
			}
			assertEquals(c.getInputs().imageCount(), images);
			/// And the graph's tags are the same keys.
			for(String t : c.getGraph().tags()) {
				if(t.startsWith(ComfyGraph.INPUT_KEY_PREFIX + ComfyGraph.TYPE_IMAGE)) {
					assertTrue(t, t.contains(ComfyGraph.IMAGE_KEY_MARKER));
				}
				assertTrue(t, body.containsKey(t));
			}
		}
		assertEquals(3, base.getInputs().imageCount());
		assertEquals("mask, init and three references", 5, region.getInputs().imageCount());
	}

	@Test
	public void loaderNodeWithNoPlaceholderIsRejected() {
		try {
			new WorkflowCompiler().compileBase(flux2Request(), references(2), new FixtureGraphClient());
			fail("expected GraphCompileException");
		}
		catch(GraphCompileException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("104") && e.getMessage().contains("matches no placeholder"));
		}
	}

	@Test
	public void placeholderWithNoLoaderNodeIsRejected() {
		/// Three references against the SDXL inpaint fixture, which has no reference loader at all.
		try {
			new WorkflowCompiler().compileActorRegion(request(SDXL, 25, 5.0, NEGATIVE, 1), references(3),
				target(MaskImage.solid(W, H, Color.GRAY), new RenderPlan.Box(0.1, 0.1, 0.5, 0.5)),
				new RendererCapabilities.InpaintParams(25, 0.6, 16, 8), new FixtureGraphClient());
			fail("expected GraphCompileException");
		}
		catch(GraphCompileException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("carries no node for image actorA"));
		}
	}

	@Test
	public void missingSeedMarkerIsRejected() {
		try {
			new WorkflowCompiler().compileBase(flux2Request(), references(3), new FixtureGraphClient().dropSeed());
			fail("expected GraphCompileException");
		}
		catch(GraphCompileException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("seed marker"));
		}
	}

	@Test
	public void missingPromptMarkerIsRejected() {
		try {
			new WorkflowCompiler().compileBase(flux2Request(), references(3), new FixtureGraphClient().dropPrompt());
			fail("expected GraphCompileException");
		}
		catch(GraphCompileException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("scene prompt marker"));
		}
	}

	@Test
	public void missingSaveNodeIsRejected() {
		try {
			new WorkflowCompiler().compileBase(flux2Request(), references(3), new FixtureGraphClient().dropSave());
			fail("expected GraphCompileException");
		}
		catch(GraphCompileException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("no save node"));
		}
	}

	@Test
	public void samplerStepsMustMatchTheRequest() {
		try {
			new WorkflowCompiler().compileBase(request(FLUX2, 8, 2.0, NEGATIVE, 1), references(3), new FixtureGraphClient().keepFixtureSteps());
			fail("expected GraphCompileException");
		}
		catch(GraphCompileException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("Sampler steps 4 differ from the requested 8"));
		}
	}

	@Test
	public void generatorErrorSurfacesAsCompileException() {
		FixtureGraphClient client = new FixtureGraphClient() {
			@Override
			public ComfyGraph generatedWorkflow(Map<String, Object> params) {
				throw new GraphCompileException("Generator error: Model not found");
			}
		};
		try {
			new WorkflowCompiler().compileBase(flux2Request(), references(3), client);
			fail("expected GraphCompileException");
		}
		catch(GraphCompileException e) {
			assertEquals("Generator error: Model not found", e.getMessage());
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void referenceNameMustBeATagName() {
		new WorkflowCompiler.Reference("actor A", null, MaskImage.solid(4, 4, Color.RED));
	}

	@Test(expected = IllegalArgumentException.class)
	public void referenceNeedsBytes() {
		new WorkflowCompiler.Reference("actorA", null, new byte[0]);
	}

	@Test(expected = IllegalArgumentException.class)
	public void actorTargetNeedsABox() {
		new WorkflowCompiler.ActorTarget(new RenderPlan.Actor("A", "x", null, null, true), MaskImage.solid(4, 4, Color.RED), null, List.of());
	}

	@Test(expected = IllegalArgumentException.class)
	public void actorRegionNeedsInpaintParams() {
		new WorkflowCompiler().compileActorRegion(flux2Request(), references(3), target(MaskImage.solid(W, H, Color.GRAY), new RenderPlan.Box(0, 0, 1, 1)), null, new FixtureGraphClient());
	}
}
