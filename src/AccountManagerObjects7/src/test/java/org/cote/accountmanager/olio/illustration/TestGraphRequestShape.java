package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.cote.accountmanager.olio.picturebook.PbArtifactUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// V3 Phase 5, P5-1: the raw-graph request body (design §38.1) has the shape the P5-0 spike sent and
/// `.39` accepted (`raw_flux2.request.json`), and the persisted forms of it (request record, sanitised
/// generator request) carry no image bytes, no graph body and no session.
public class TestGraphRequestShape {

	private static final Set<String> TAG_TYPES = Set.of(ComfyGraph.TYPE_TEXT, ComfyGraph.TYPE_INTEGER, ComfyGraph.TYPE_DECIMAL,
		ComfyGraph.TYPE_BOOLEAN, ComfyGraph.TYPE_IMAGE, ComfyGraph.TYPE_SEED, ComfyGraph.TYPE_MODEL);

	private static WorkflowCompiler.CompiledGraph compiled() {
		return new WorkflowCompiler().compileBase(TestWorkflowCompiler.flux2Request(), TestWorkflowCompiler.references(3), new FixtureGraphClient());
	}

	/// The tag type of a request key, or null when the key is not a tag key.
	private static String tagType(String key) {
		if(!key.startsWith(ComfyGraph.INPUT_KEY_PREFIX)) {
			return null;
		}
		String rest = key.substring(ComfyGraph.INPUT_KEY_PREFIX.length());
		for(String t : TAG_TYPES) {
			if(rest.startsWith(t)) {
				return t;
			}
		}
		return "?";
	}

	private static Set<String> nonTagKeys(Map<String, Object> m) {
		Set<String> out = new TreeSet<>();
		for(String k : m.keySet()) {
			if(tagType(k) == null) out.add(k);
		}
		return out;
	}

	private static Set<String> tagTypes(Map<String, Object> m) {
		Set<String> out = new TreeSet<>();
		for(String k : m.keySet()) {
			String t = tagType(k);
			if(t != null) out.add(t);
		}
		return out;
	}

	@Test
	public void rawRequestKeysMatchTheSpikeFixture() {
		Map<String, Object> fixture = JSONUtil.getMap(FixtureGraphClient.fixtureText("raw_flux2.request.json").getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertNotNull(fixture);
		WorkflowCompiler.CompiledGraph c = compiled();
		Map<String, Object> body = new LinkedHashMap<>(SwarmGraphClient.rawBody(c.getGraph(), c.getInputs(), TestWorkflowCompiler.FLUX2, 1));
		body.put(SwarmGraphClient.KEY_SESSION, "<redacted>");

		/// Non-tag keys are exactly the fixture's. Tag NAMES differ by design (the spike used `v3seed`,
		/// the compiler uses `seed`, and the compiler tags a negative), so the comparison is on the set
		/// of tag TYPES used, which the fixture proves Swarm accepts.
		assertEquals(Set.of("model", "images", ComfyGraph.PARAM_RAW, SwarmGraphClient.KEY_SESSION), nonTagKeys(fixture));
		assertEquals(nonTagKeys(fixture), nonTagKeys(body));
		assertEquals(Set.of(ComfyGraph.TYPE_SEED, ComfyGraph.TYPE_TEXT, ComfyGraph.TYPE_IMAGE), tagTypes(fixture));
		assertEquals(tagTypes(fixture), tagTypes(body));

		assertEquals(fixture.get("model"), body.get("model"));
		assertEquals(Integer.valueOf(1), body.get("images"));
		assertTrue("the graph travels as a JSON string, as in the fixture", fixture.get(ComfyGraph.PARAM_RAW) instanceof String);
		assertTrue(body.get(ComfyGraph.PARAM_RAW) instanceof String);
		ComfyGraph sent = ComfyGraph.fromJson((String) body.get(ComfyGraph.PARAM_RAW));
		assertEquals(c.getGraph().graphHash(), sent.graphHash());
		assertEquals(ComfyGraph.CLASS_SAVE_IMAGE_WS, sent.classType(ComfyGraph.SAVE_FINAL));

		/// Seeds are numbers, images are data URIs, text is text.
		assertTrue(body.get(ComfyGraph.inputKey(ComfyGraph.TYPE_SEED, WorkflowCompiler.TAG_SEED)) instanceof Number);
		assertEquals(424242L, ((Number) body.get(ComfyGraph.inputKey(ComfyGraph.TYPE_SEED, WorkflowCompiler.TAG_SEED))).longValue());
		assertEquals(TestWorkflowCompiler.SCENE, body.get(ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE)));
		String actorA = (String) body.get(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "actorA"));
		assertTrue(actorA.startsWith("data:image/png;base64,"));
		/// Every graph tag has a body key and every body tag key is a graph tag.
		Set<String> bodyTags = new LinkedHashSet<>();
		for(String k : body.keySet()) {
			if(tagType(k) != null) bodyTags.add(k);
		}
		assertEquals(sent.tags(), bodyTags);
		assertEquals(TestWorkflowCompiler.FLUX2, fixture.get("model"));
	}

	@Test
	public void rawBodyRequiresGraphAndInputsAndAtLeastOneImage() {
		WorkflowCompiler.CompiledGraph c = compiled();
		assertEquals(Integer.valueOf(1), SwarmGraphClient.rawBody(c.getGraph(), c.getInputs(), "m", 0).get("images"));
		assertEquals(Integer.valueOf(2), SwarmGraphClient.rawBody(c.getGraph(), c.getInputs(), "m", 2).get("images"));
		try {
			SwarmGraphClient.rawBody(null, c.getInputs(), "m", 1);
			org.junit.Assert.fail();
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
		try {
			SwarmGraphClient.rawBody(c.getGraph(), null, "m", 1);
			org.junit.Assert.fail();
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
	}

	@Test
	public void redactedInputsCarryNoBytes() {
		WorkflowCompiler.CompiledGraph c = compiled();
		Map<String, Object> red = c.getInputs().redacted();
		assertEquals(c.getInputs().names(), red.keySet());
		String json = JSONUtil.exportObject(red);
		assertFalse(json.contains("data:image"));
		assertFalse(json.contains("base64,"));
		assertTrue(json, json.contains("bytes png>"));
		assertEquals(TestWorkflowCompiler.SCENE, red.get(ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE)));
		assertEquals(424242L, ((Number) red.get(ComfyGraph.inputKey(ComfyGraph.TYPE_SEED, WorkflowCompiler.TAG_SEED))).longValue());
		assertTrue(String.valueOf(c.getInputs()).contains("bytes png>"));
		assertFalse(String.valueOf(c.getInputs()).contains("base64,"));

		/// The stage's request record, as the adapter persists it.
		StageRequest req = StageRequest.builder().stage(c.getStage()).model(TestWorkflowCompiler.FLUX2)
			.graph(c.getGraph()).inputs(c.getInputs()).seed((int) c.getSeed()).build();
		String record = SwarmRendererAdapter.graphRequestJson(req, c.getGraph().graphHash());
		assertFalse(record.contains("data:image"));
		assertFalse(record.contains("base64,"));
		assertFalse("the graph body is its own artifact", record.contains("class_type"));
		assertFalse(record.contains(SwarmGraphClient.KEY_SESSION));
		Map<String, Object> rec = JSONUtil.getMap(record.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertEquals(RenderPlan.STAGE_BASE, rec.get("stage"));
		assertEquals(c.getGraph().graphHash(), rec.get("graphHash"));
		assertEquals(List.of("art-actorA", "art-actorB", "art-actorC"), rec.get("referenceArtifactIds"));
		assertTrue(rec.get("inputs") instanceof Map);
		/// Bytes-free is not the same as structurally clean: the redacted inputs keep the image TAG KEYS
		/// (`comfyrawworkflowinputimage<name>` -> "<N bytes png>"), and the structural check is on key
		/// names. The pipeline's `recordImage` runs every request record through the sanitiser before it
		/// is persisted, which drops those keys and keeps the rest; the graph artifact still names the
		/// image tags, so nothing of the provenance is lost.
		assertFalse(PbArtifactUtil.isSanitized(record));
		String persisted = PbArtifactUtil.sanitizeGeneratorRequest(record, c.getInputs().getReferenceArtifactIds());
		assertNotNull(persisted);
		assertTrue(PbArtifactUtil.isSanitized(persisted));
		Map<String, Object> pm = JSONUtil.getMap(persisted.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertEquals(c.getGraph().graphHash(), pm.get("graphHash"));
		assertEquals(RenderPlan.STAGE_BASE, pm.get("stage"));
		assertEquals(List.of("art-actorA", "art-actorB", "art-actorC"), pm.get("referenceArtifactIds"));
		assertEquals(List.of("art-actorA", "art-actorB", "art-actorC"), pm.get(PbArtifactUtil.REFERENCE_ARTIFACTS_KEY));
		@SuppressWarnings("unchecked")
		Map<String, Object> pin = (Map<String, Object>) pm.get("inputs");
		assertEquals(TestWorkflowCompiler.SCENE, pin.get(ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE)));
		assertEquals(424242L, ((Number) pin.get(ComfyGraph.inputKey(ComfyGraph.TYPE_SEED, WorkflowCompiler.TAG_SEED))).longValue());
		for(String k : pin.keySet()) {
			assertFalse(k, k.toLowerCase().startsWith(PbArtifactUtil.SANITIZE_IMAGE_KEY_PREFIX));
		}
		String backend = SwarmRendererAdapter.backendGraphJson(c.getStage(), c.getGraph().graphHash(), "0.9.7");
		Map<String, Object> bg = JSONUtil.getMap(backend.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertEquals(c.getGraph().graphHash(), bg.get("graphHash"));
		assertEquals(ComfyGraph.SAVE_FINAL, bg.get("saveNodeId"));
		assertEquals("0.9.7", bg.get("swarmVersion"));
	}

	@Test
	public void sanitizeStripsImageInputsAndRawGraph() {
		WorkflowCompiler.CompiledGraph c = compiled();
		Map<String, Object> body = new LinkedHashMap<>(SwarmGraphClient.rawBody(c.getGraph(), c.getInputs(), TestWorkflowCompiler.FLUX2, 1));
		body.put(SwarmGraphClient.KEY_SESSION, "abc-session");
		String wire = JSONUtil.exportObject(body);
		assertTrue(wire.contains("data:image"));
		assertTrue(wire.contains("class_type"));
		assertFalse(PbArtifactUtil.isSanitized(wire));

		String clean = PbArtifactUtil.sanitizeGeneratorRequest(wire, c.getInputs().getReferenceArtifactIds());
		assertNotNull(clean);
		assertTrue(PbArtifactUtil.isSanitized(clean));
		assertFalse(clean.contains("data:image"));
		assertFalse(clean.contains("base64,"));
		assertFalse(clean.contains("class_type"));
		assertFalse(clean.contains("abc-session"));
		Map<String, Object> m = JSONUtil.getMap(clean.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertNull(m.get(ComfyGraph.PARAM_RAW));
		assertNull(m.get(SwarmGraphClient.KEY_SESSION));
		for(String k : m.keySet()) {
			assertFalse(k, k.toLowerCase().startsWith(PbArtifactUtil.SANITIZE_IMAGE_KEY_PREFIX));
		}
		assertEquals(TestWorkflowCompiler.FLUX2, m.get("model"));
		assertEquals(TestWorkflowCompiler.SCENE, m.get(ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, WorkflowCompiler.TAG_SCENE)));
		assertEquals(List.of("art-actorA", "art-actorB", "art-actorC"), m.get(PbArtifactUtil.REFERENCE_ARTIFACTS_KEY));

		/// The compiler's record of what it asked the generator carries descriptions, not bytes - but it
		/// keeps the `promptimages` key, so the structural check (key names, not content) still calls it
		/// unsanitised and sanitising it drops the key. It is not what the adapter persists.
		String gen = JSONUtil.exportObject(c.getGeneratorParams());
		assertFalse(gen.contains("data:image"));
		assertTrue(gen.contains("<placeholder actorA rgb(255,0,0) 1024x768>"));
		assertFalse(PbArtifactUtil.isSanitized(gen));
		String genClean = PbArtifactUtil.sanitizeGeneratorRequest(gen, List.of());
		assertTrue(PbArtifactUtil.isSanitized(genClean));
		assertFalse(genClean.contains("promptimages"));
		assertTrue(genClean.contains(WorkflowCompiler.MARKER_SCENE));
		assertNull(PbArtifactUtil.sanitizeGeneratorRequest("not json", List.of()));
		assertFalse(PbArtifactUtil.isSanitized("not json"));
		assertTrue(PbArtifactUtil.isSanitized(null));
	}
}
