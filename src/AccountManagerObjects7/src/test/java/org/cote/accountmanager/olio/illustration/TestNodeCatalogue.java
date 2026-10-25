package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

/// V3 Phase 5, P5-1: the node catalogue (design §38.4) - object_info parsing, the per-server cache,
/// and every problem code the graph validator can raise.
public class TestNodeCatalogue {

	static final String OBJECT_INFO_SUBSET = FixtureGraphClient.FIXTURE_DIR + "object_info.subset.json";

	/// A hand-written object_info covering the tiny graph below, in the shape `.39` returns
	/// (`{type: {input: {required: {name: [spec]}, optional: {...}}, ...}}`).
	static final String STUB_OBJECT_INFO = "{"
		+ "\"CheckpointLoaderSimple\":{\"input\":{\"required\":{\"ckpt_name\":[[\"a.safetensors\"]]}},\"name\":\"CheckpointLoaderSimple\"},"
		+ "\"CLIPTextEncode\":{\"input\":{\"required\":{\"text\":[\"STRING\",{\"multiline\":true}],\"clip\":[\"CLIP\"]}}},"
		+ "\"EmptyLatentImage\":{\"input\":{\"required\":{\"width\":[\"INT\"],\"height\":[\"INT\"],\"batch_size\":[\"INT\"]}}},"
		+ "\"SwarmKSampler\":{\"input\":{\"required\":{\"model\":[\"MODEL\"],\"noise_seed\":[\"INT\"],\"steps\":[\"INT\"],\"cfg\":[\"FLOAT\"],"
		+ "\"sampler_name\":[[\"euler\"]],\"scheduler\":[[\"simple\"]],\"positive\":[\"CONDITIONING\"],\"negative\":[\"CONDITIONING\"],\"latent_image\":[\"LATENT\"]},"
		+ "\"optional\":{\"lora_hooks\":[\"HOOKS\"],\"start_at_step\":[\"INT\"]}}},"
		+ "\"VAEDecode\":{\"input\":{\"required\":{\"samples\":[\"LATENT\"],\"vae\":[\"VAE\"]}}},"
		+ "\"SwarmSaveImageWS\":{\"input\":{\"required\":{\"images\":[\"IMAGE\"]},\"optional\":{\"bit_depth\":[[\"8bit\"]]}}},"
		+ "\"SwarmLoadImageB64\":{\"input\":{\"required\":{\"image_base64\":[\"STRING\"]}}},"
		+ "\"NoInput\":{\"name\":\"NoInput\",\"display_name\":\"No input block\"},"
		+ "\"NotAnObject\":7"
		+ "}";

	static final String SEED_KEY = ComfyGraph.inputKey(ComfyGraph.TYPE_SEED, WorkflowCompiler.TAG_SEED);
	static final String SCENE_KEY = ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE);
	static final Set<String> PROVIDED = Set.of(SEED_KEY, SCENE_KEY);

	@Before
	public void dropCatalogues() {
		NodeCatalogue.clearCache();
	}

	static ComfyGraph tiny() {
		ComfyGraph g = ComfyGraph.fromMap(Map.of());
		g.addNode("4", "CheckpointLoaderSimple", Map.of("ckpt_name", "a.safetensors"));
		g.addNode("6", "CLIPTextEncode", Map.of("text", ComfyGraph.tag(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE), "clip", List.of("4", 1)));
		g.addNode("7", "CLIPTextEncode", Map.of("text", "", "clip", List.of("4", 1)));
		g.addNode("5", "EmptyLatentImage", Map.of("width", 1024, "height", 768, "batch_size", 1));
		Map<String, Object> ks = new LinkedHashMap<>();
		ks.put("model", List.of("4", 0));
		ks.put("noise_seed", ComfyGraph.tag(ComfyGraph.TYPE_SEED, WorkflowCompiler.TAG_SEED));
		ks.put("steps", 4);
		ks.put("cfg", 2.0);
		ks.put("sampler_name", "euler");
		ks.put("scheduler", "simple");
		ks.put("positive", List.of("6", 0));
		ks.put("negative", List.of("7", 0));
		ks.put("latent_image", List.of("5", 0));
		ks.put("lora_hooks", null);
		g.addNode("10", "SwarmKSampler", ks);
		g.addNode("8", "VAEDecode", Map.of("samples", List.of("10", 0), "vae", List.of("4", 2)));
		g.addNode(ComfyGraph.SAVE_FINAL, ComfyGraph.CLASS_SAVE_IMAGE_WS, Map.of("images", List.of("8", 0)));
		return g;
	}

	static List<GraphProblem> withCode(List<GraphProblem> problems, String code) {
		List<GraphProblem> out = new ArrayList<>();
		for(GraphProblem p : problems) {
			if(code.equals(p.getCode())) out.add(p);
		}
		return out;
	}

	@Test
	public void fromJsonSkipsEntriesWithoutInput() {
		NodeCatalogue c = NodeCatalogue.fromJson(STUB_OBJECT_INFO);
		assertEquals("seven entries carry an input block; NoInput and NotAnObject are skipped", 7, c.size());
		assertFalse(c.knows("NoInput"));
		assertFalse(c.knows("NotAnObject"));
		assertTrue(c.knows("SwarmKSampler"));
		NodeCatalogue.NodeType ks = c.type("SwarmKSampler");
		assertEquals(Set.of("model", "noise_seed", "steps", "cfg", "sampler_name", "scheduler", "positive", "negative", "latent_image"), ks.getRequired());
		assertEquals(Set.of("lora_hooks", "start_at_step"), ks.getOptional());
		assertTrue(ks.accepts("cfg"));
		assertTrue(ks.accepts("lora_hooks"));
		assertFalse(ks.accepts("denoise"));
		assertNull(c.type("Bogus"));
		assertNull(c.getServer());
		assertTrue(c.getFetchedAt() > 0);
		for(String bad : new String[] {null, "", "  ", "not json", "[1,2]"}) {
			try {
				NodeCatalogue.fromJson(bad);
				fail("accepted " + bad);
			}
			catch(IllegalArgumentException e) {
				/// expected
			}
		}
	}

	@Test
	public void cleanGraphHasNoProblems() {
		NodeCatalogue c = NodeCatalogue.fromJson(STUB_OBJECT_INFO);
		List<GraphProblem> problems = c.validate(tiny(), PROVIDED);
		assertEquals(NodeCatalogue.describe(problems), 0, problems.size());
		assertEquals("", NodeCatalogue.describe(problems));
	}

	@Test
	public void unknownClassTypeIsReported() {
		ComfyGraph g = tiny();
		g.addNode("x", "Bogus", Map.of("a", 1));
		List<GraphProblem> errs = NodeCatalogue.errors(NodeCatalogue.fromJson(STUB_OBJECT_INFO).validate(g, PROVIDED));
		assertEquals(1, errs.size());
		assertEquals(GraphProblem.UNKNOWN_NODE_TYPE, errs.get(0).getCode());
		assertEquals("x", errs.get(0).getNodeId());
		assertEquals("node type Bogus is not in the renderer's object_info", errs.get(0).getMessage());
		assertEquals("UNKNOWN_NODE_TYPE[x]: node type Bogus is not in the renderer's object_info", errs.get(0).toString());
	}

	@Test
	public void missingRequiredInputIsReported() {
		ComfyGraph g = tiny();
		g.inputs("10").remove("cfg");
		List<GraphProblem> errs = NodeCatalogue.errors(NodeCatalogue.fromJson(STUB_OBJECT_INFO).validate(g, PROVIDED));
		assertEquals(NodeCatalogue.describe(errs), 1, errs.size());
		assertEquals(GraphProblem.MISSING_REQUIRED_INPUT, errs.get(0).getCode());
		assertEquals("10", errs.get(0).getNodeId());
		assertEquals("SwarmKSampler requires input cfg", errs.get(0).getMessage());
		/// An absent OPTIONAL input is nothing.
		ComfyGraph h = tiny();
		h.inputs("10").remove("lora_hooks");
		assertTrue(NodeCatalogue.fromJson(STUB_OBJECT_INFO).validate(h, PROVIDED).isEmpty());
	}

	@Test
	public void linkToMissingNodeIsReported() {
		ComfyGraph g = tiny();
		assertTrue(g.removeNode("8"));
		List<GraphProblem> errs = NodeCatalogue.errors(NodeCatalogue.fromJson(STUB_OBJECT_INFO).validate(g, PROVIDED));
		assertEquals(NodeCatalogue.describe(errs), 1, errs.size());
		assertEquals(GraphProblem.DANGLING_LINK, errs.get(0).getCode());
		assertEquals("the node id is the prefix before the input name", ComfyGraph.SAVE_FINAL, errs.get(0).getNodeId());
		assertEquals("link " + ComfyGraph.SAVE_FINAL + ".images -> 8 points at no node", errs.get(0).getMessage());
		/// Structural only, no catalogue needed.
		assertEquals(1, NodeCatalogue.validateStructure(g, PROVIDED).size());
	}

	@Test
	public void missingProvidedTagIsReported() {
		ComfyGraph g = tiny();
		List<GraphProblem> errs = NodeCatalogue.errors(NodeCatalogue.validateStructure(g, Set.of(SCENE_KEY)));
		assertEquals(NodeCatalogue.describe(errs), 1, errs.size());
		assertEquals(GraphProblem.UNFILLED_TAG, errs.get(0).getCode());
		assertNull("a tag belongs to the request, not a node", errs.get(0).getNodeId());
		assertEquals("tag " + SEED_KEY + " has no value in the request", errs.get(0).getMessage());
		assertEquals("UNFILLED_TAG: tag " + SEED_KEY + " has no value in the request", errs.get(0).toString());
		/// Null provided set: every tag is unfilled.
		assertEquals(2, withCode(NodeCatalogue.validateStructure(g, null), GraphProblem.UNFILLED_TAG).size());
		/// Extra provided keys are not a problem.
		assertTrue(NodeCatalogue.validateStructure(g, Set.of(SEED_KEY, SCENE_KEY, "comfyrawworkflowinputtextunused")).isEmpty());
	}

	@Test
	public void noSaveNodeAndInlineImageAreErrors() {
		ComfyGraph g = tiny();
		g.rename(ComfyGraph.SAVE_FINAL, "9");
		List<GraphProblem> errs = NodeCatalogue.errors(NodeCatalogue.validateStructure(g, PROVIDED));
		assertEquals(NodeCatalogue.describe(errs), 1, errs.size());
		assertEquals(GraphProblem.NO_SAVE_NODE, errs.get(0).getCode());
		assertNull(errs.get(0).getNodeId());

		ComfyGraph h = tiny();
		h.addNode("102", ComfyGraph.CLASS_LOAD_IMAGE_B64, Map.of(ComfyGraph.INPUT_IMAGE_BASE64, "iVBORw0KGgo="));
		errs = NodeCatalogue.errors(NodeCatalogue.validateStructure(h, PROVIDED));
		assertEquals(NodeCatalogue.describe(errs), 1, errs.size());
		assertEquals(GraphProblem.INLINE_IMAGE, errs.get(0).getCode());
		assertEquals("102", errs.get(0).getNodeId());
		h.setInput("102", ComfyGraph.INPUT_IMAGE_BASE64, ComfyGraph.tag(ComfyGraph.TYPE_IMAGE, "actorA"));
		Set<String> provided = Set.of(SEED_KEY, SCENE_KEY, ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "actorA"));
		assertTrue(NodeCatalogue.describe(NodeCatalogue.fromJson(STUB_OBJECT_INFO).validate(h, provided)),
			NodeCatalogue.fromJson(STUB_OBJECT_INFO).validate(h, provided).isEmpty());
	}

	@Test
	public void unknownInputIsAWarningOnly() {
		ComfyGraph g = tiny();
		g.setInput("10", "denoise", 0.6);
		List<GraphProblem> problems = NodeCatalogue.fromJson(STUB_OBJECT_INFO).validate(g, PROVIDED);
		assertEquals(NodeCatalogue.describe(problems), 1, problems.size());
		GraphProblem p = problems.get(0);
		assertTrue(p.isWarning());
		assertFalse(p.isError());
		assertEquals(GraphProblem.UNKNOWN_INPUT, p.getCode());
		assertEquals("10", p.getNodeId());
		assertEquals("SwarmKSampler does not list input denoise", p.getMessage());
		assertTrue("a warning does not block the render", NodeCatalogue.errors(problems).isEmpty());
	}

	@Test
	public void ttlAndInvalidate() {
		AtomicInteger fetches = new AtomicInteger();
		NodeCatalogue a = NodeCatalogue.forServer("http://127.0.0.1:9", () -> { fetches.incrementAndGet(); return STUB_OBJECT_INFO; });
		assertNotNull(a);
		assertEquals(1, fetches.get());
		assertEquals("http://127.0.0.1:9", a.getServer());
		/// Cached within the TTL; the key is the trimmed server.
		assertSame(a, NodeCatalogue.forServer("http://127.0.0.1:9", () -> { fetches.incrementAndGet(); return STUB_OBJECT_INFO; }));
		assertSame(a, NodeCatalogue.forServer("  http://127.0.0.1:9 ", () -> { fetches.incrementAndGet(); return STUB_OBJECT_INFO; }));
		assertEquals(1, fetches.get());
		assertTrue(NodeCatalogue.TTL_MS >= 60_000L);
		assertTrue(System.currentTimeMillis() - a.getFetchedAt() < NodeCatalogue.TTL_MS);
		/// Another server is another entry.
		NodeCatalogue b = NodeCatalogue.forServer("http://127.0.0.1:10", () -> { fetches.incrementAndGet(); return STUB_OBJECT_INFO; });
		assertEquals(2, fetches.get());
		assertNotNull(b);
		/// Invalidate drops one server only.
		NodeCatalogue.invalidate("http://127.0.0.1:9");
		NodeCatalogue a2 = NodeCatalogue.forServer("http://127.0.0.1:9", () -> { fetches.incrementAndGet(); return STUB_OBJECT_INFO; });
		assertEquals(3, fetches.get());
		assertNotNull(a2);
		assertFalse(a == a2);
		assertSame(b, NodeCatalogue.forServer("http://127.0.0.1:10", () -> { fetches.incrementAndGet(); return STUB_OBJECT_INFO; }));
		assertEquals(3, fetches.get());
		/// clearCache drops everything.
		NodeCatalogue.clearCache();
		NodeCatalogue.forServer("http://127.0.0.1:10", () -> { fetches.incrementAndGet(); return STUB_OBJECT_INFO; });
		assertEquals(4, fetches.get());
	}

	@Test
	public void failedFetchIsNullAndNeverCached() {
		AtomicInteger fetches = new AtomicInteger();
		assertNull(NodeCatalogue.forServer("s1", () -> { fetches.incrementAndGet(); return null; }));
		assertNull(NodeCatalogue.forServer("s1", () -> { fetches.incrementAndGet(); return "   "; }));
		assertNull(NodeCatalogue.forServer("s1", () -> { fetches.incrementAndGet(); throw new IllegalStateException("boom"); }));
		assertNull(NodeCatalogue.forServer("s1", () -> { fetches.incrementAndGet(); return "not json"; }));
		assertNull(NodeCatalogue.forServer("s1", () -> { fetches.incrementAndGet(); return "[1]"; }));
		assertEquals("every failure re-fetches", 5, fetches.get());
		/// The next good answer is cached.
		NodeCatalogue c = NodeCatalogue.forServer("s1", () -> { fetches.incrementAndGet(); return STUB_OBJECT_INFO; });
		assertNotNull(c);
		assertSame(c, NodeCatalogue.forServer("s1", () -> { fetches.incrementAndGet(); return STUB_OBJECT_INFO; }));
		assertEquals(6, fetches.get());
		/// A null server keys as the empty string.
		assertNotNull(NodeCatalogue.forServer(null, () -> STUB_OBJECT_INFO));
		assertEquals("", NodeCatalogue.forServer(null, () -> STUB_OBJECT_INFO).getServer());
	}

	@Test
	public void problemsNeverContainServerUrl() {
		String server = "http://192.168.1.39:7801";
		NodeCatalogue c = NodeCatalogue.forServer(server, () -> STUB_OBJECT_INFO);
		assertNotNull(c);
		ComfyGraph g = tiny();
		g.addNode("x", "Bogus", Map.of());
		g.inputs("10").remove("cfg");
		g.setInput("10", "denoise", 0.6);
		g.removeNode("8");
		g.addNode("102", ComfyGraph.CLASS_LOAD_IMAGE_B64, Map.of(ComfyGraph.INPUT_IMAGE_BASE64, "iVBORw0KGgo="));
		List<GraphProblem> problems = c.validate(g, Set.of());
		assertTrue(problems.size() >= 6);
		String all = NodeCatalogue.describe(problems);
		assertFalse(all, all.contains("192.168"));
		assertFalse(all, all.contains("7801"));
		assertFalse(all, all.contains("http"));
		for(GraphProblem p : problems) {
			assertFalse(p.toString(), p.toString().contains(server));
		}
		/// toJson carries no server either.
		assertFalse(c.toJson().contains(server));
	}

	@Test
	public void subsetAndToJsonRoundTrip() {
		NodeCatalogue full = NodeCatalogue.fromJson("http://127.0.0.1:9", STUB_OBJECT_INFO);
		NodeCatalogue sub = full.subset(Set.of("CLIPTextEncode", "SwarmKSampler", "NotThere"));
		assertEquals(2, sub.size());
		assertEquals(Set.of("CLIPTextEncode", "SwarmKSampler"), sub.typeNames());
		assertEquals(full.getServer(), sub.getServer());
		assertEquals(full.getFetchedAt(), sub.getFetchedAt());
		String json = sub.toJson();
		assertTrue(json.contains("\"?\""));
		assertFalse(json.contains("127.0.0.1"));
		NodeCatalogue back = NodeCatalogue.fromJson(json);
		assertEquals(sub.typeNames(), back.typeNames());
		for(String t : sub.typeNames()) {
			assertEquals(t, sub.type(t).getRequired(), back.type(t).getRequired());
			assertEquals(t, sub.type(t).getOptional(), back.type(t).getOptional());
		}
		/// The subset of the tiny graph's own types validates it cleanly; the rest were not needed.
		Set<String> used = new java.util.LinkedHashSet<>();
		for(String id : tiny().nodeIds()) used.add(tiny().classType(id));
		assertTrue(full.subset(used).validate(tiny(), PROVIDED).isEmpty());
		/// ...and the subset without one of them reports it.
		used.remove("VAEDecode");
		List<GraphProblem> errs = NodeCatalogue.errors(full.subset(used).validate(tiny(), PROVIDED));
		assertEquals(1, errs.size());
		assertEquals("8", errs.get(0).getNodeId());
	}

	/// The three compiled stage graphs against the node catalogue captured from `.39` by the gated
	/// `TestP51GraphLive.t00_catalogueAndDriftProbe` (`PB_SD_TESTS=1`), which writes
	/// `target/p51/object_info.subset.json`; that file is copied by hand into
	/// `src/test/resources/olio/illustration/p50/` after review. Until it is there this test FAILS
	/// (it does not skip): a graph nobody has checked against a real catalogue is not a tested graph.
	@Test
	public void compiledGraphsValidateClean() {
		String json;
		try(InputStream in = TestNodeCatalogue.class.getResourceAsStream(OBJECT_INFO_SUBSET)) {
			if(in == null) {
				fail("Fixture " + OBJECT_INFO_SUBSET + " is missing: run the gated TestP51GraphLive (PB_SD_TESTS=1) against .39, "
					+ "review target/p51/object_info.subset.json and copy it into src/test/resources" + FixtureGraphClient.FIXTURE_DIR + " (LF).");
			}
			json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch(java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		NodeCatalogue c = NodeCatalogue.fromJson(json);
		assertTrue(c.size() > 0);
		WorkflowCompiler wc = new WorkflowCompiler();

		WorkflowCompiler.CompiledGraph base = wc.compileBase(TestWorkflowCompiler.flux2Request(), TestWorkflowCompiler.references(3), new FixtureGraphClient());
		List<GraphProblem> p1 = c.validate(base.getGraph(), base.getInputs().names());
		assertEquals("base FLUX.2: " + NodeCatalogue.describe(p1), 0, NodeCatalogue.errors(p1).size());

		byte[] baseImage = MaskImage.solid(TestWorkflowCompiler.W, TestWorkflowCompiler.H, new java.awt.Color(30, 60, 90));
		RenderPlan.Box box = new RenderPlan.Box(0.55, 0.2, 0.4, 0.8);
		WorkflowCompiler.CompiledGraph inpaintFlux2 = wc.compileActorRegion(TestWorkflowCompiler.flux2Request(), TestWorkflowCompiler.references(3),
			TestWorkflowCompiler.target(baseImage, box), new RendererCapabilities.InpaintParams(4, 1.0, 16, 8), new FixtureGraphClient());
		List<GraphProblem> p2 = c.validate(inpaintFlux2.getGraph(), inpaintFlux2.getInputs().names());
		assertEquals("inpaint FLUX.2: " + NodeCatalogue.describe(p2), 0, NodeCatalogue.errors(p2).size());

		WorkflowCompiler.CompiledGraph inpaintSdxl = wc.compileActorRegion(
			TestWorkflowCompiler.request(TestWorkflowCompiler.SDXL, 25, 5.0, TestWorkflowCompiler.NEGATIVE, 424242), List.of(),
			TestWorkflowCompiler.target(baseImage, box), new RendererCapabilities.InpaintParams(25, 0.6, 16, 8), new FixtureGraphClient());
		List<GraphProblem> p3 = c.validate(inpaintSdxl.getGraph(), inpaintSdxl.getInputs().names());
		assertEquals("inpaint SDXL: " + NodeCatalogue.describe(p3), 0, NodeCatalogue.errors(p3).size());
	}
}
