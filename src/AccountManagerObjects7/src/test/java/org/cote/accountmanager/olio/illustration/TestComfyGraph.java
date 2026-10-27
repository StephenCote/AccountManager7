package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/// V3 Phase 5, P5-1: the API-format graph model (design §38.1). Pure in-memory; the fixture cases
/// parse the graphs `.39` produced in the P5-0 spike.
public class TestComfyGraph {

	private static ComfyGraph tiny() {
		ComfyGraph g = ComfyGraph.fromMap(Map.of());
		g.addNode("4", "CheckpointLoaderSimple", Map.of("ckpt_name", "x.safetensors"));
		g.addNode("6", "CLIPTextEncode", Map.of("text", ComfyGraph.tag(ComfyGraph.TYPE_TEXT, "scene"), "clip", List.of("4", 1)));
		g.addNode("5", "EmptyLatentImage", Map.of("width", 1024, "height", 768, "batch_size", 1));
		Map<String, Object> k = new LinkedHashMap<>();
		k.put("noise_seed", ComfyGraph.tag(ComfyGraph.TYPE_SEED, "seed"));
		k.put("steps", 4);
		k.put("model", List.of("4", 0));
		k.put("positive", List.of("6", 0));
		k.put("latent_image", List.of("5", 0));
		k.put("lora_hooks", null);
		g.addNode("10", "SwarmKSampler", k);
		g.addNode("8", "VAEDecode", Map.of("samples", List.of("10", 0), "vae", List.of("4", 2)));
		g.addNode(ComfyGraph.SAVE_FINAL, ComfyGraph.CLASS_SAVE_IMAGE_WS, Map.of("images", List.of("8", 0)));
		return g;
	}

	@Test
	public void buildThenToJsonIsDeterministicAndHashStable() {
		ComfyGraph a = tiny();
		ComfyGraph b = tiny();
		assertEquals(a.toJson(), b.toJson());
		assertEquals(a.graphHash(), b.graphHash());
		assertEquals(64, a.graphHash().length());
		assertTrue("nulls are kept, the graph says what the generator said", a.toJson().contains("\"lora_hooks\":null"));
		assertTrue("compact", !a.toJson().contains("\n"));
		assertTrue(a.toPrettyJson().contains("\n"));
		assertEquals(List.of("4", "6", "5", "10", "8", ComfyGraph.SAVE_FINAL), List.copyOf(a.nodeIds()));
		assertEquals(a.toJson(), ComfyGraph.fromJson(a.toJson()).toJson());
		assertEquals(a.toJson(), a.copy().toJson());
	}

	@Test
	public void copyIsDeep() {
		ComfyGraph a = tiny();
		ComfyGraph b = a.copy();
		b.setInput("10", "steps", 9);
		assertEquals(4, a.inputs("10").get("steps"));
		assertEquals(9, b.inputs("10").get("steps"));
		assertNotEquals(a.graphHash(), b.graphHash());
	}

	@Test
	public void tagRendersAsSwarmPlaceholder() {
		/// Image keys carry Swarm's base64 marker (measured on .39 2026-10-24: without it Swarm uploads the
		/// image to ComfyUI and writes the upload's filename into the tag - `init_image_sui_backend_0_1.png`
		/// in `image_base64` - and SwarmLoadImageB64 fails with "cannot identify image file"). The spike's
		/// `actorA` only worked because Swarm's case-sensitive lookup of the tag missed the capital A.
		assertEquals("${comfyrawworkflowinputimageswarminputimageactorA}", ComfyGraph.tag(ComfyGraph.TYPE_IMAGE, "actorA"));
		assertEquals("comfyrawworkflowinputimageswarminputimageinit", ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "init"));
		assertEquals("comfyrawworkflowinputseedseed", ComfyGraph.inputKey(ComfyGraph.TYPE_SEED, "seed"));
		assertEquals("comfyrawworkflowinputtextscene", ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, "scene"));
		for(String t : List.of(ComfyGraph.TYPE_TEXT, ComfyGraph.TYPE_INTEGER, ComfyGraph.TYPE_DECIMAL, ComfyGraph.TYPE_BOOLEAN, ComfyGraph.TYPE_SEED, ComfyGraph.TYPE_MODEL)) {
			assertFalse(t, ComfyGraph.inputKey(t, "x").contains(ComfyGraph.IMAGE_KEY_MARKER));
		}
		assertTrue(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "x").startsWith(ComfyGraph.INPUT_KEY_PREFIX + ComfyGraph.TYPE_IMAGE));
		assertTrue(ComfyGraph.isTag(ComfyGraph.tag(ComfyGraph.TYPE_IMAGE, "duna_de_rosa")));
		assertTrue(ComfyGraph.isTag("${comfyrawworkflowinputtextscene}"));
		assertFalse(ComfyGraph.isTag("a ${comfyrawworkflowinputtextscene} b"));
		assertFalse(ComfyGraph.isTag("comfyrawworkflowinputtextscene"));
		assertFalse(ComfyGraph.isTag(null));
		try {
			ComfyGraph.tag("float", "x");
			fail("unknown type");
		}
		catch(IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("Unknown tag type"));
		}
		try {
			ComfyGraph.tag(ComfyGraph.TYPE_TEXT, "actor A");
			fail("space in name");
		}
		catch(IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("[A-Za-z0-9_]+"));
		}
		try {
			ComfyGraph.tag(ComfyGraph.TYPE_TEXT, "");
			fail("empty name");
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
	}

	@Test
	public void fromJsonRoundTripsBaseFlux2Fixture() {
		ComfyGraph g = FixtureGraphClient.fixtureGraph(FixtureGraphClient.BASE_FLUX2);
		assertEquals(18, g.size());
		assertEquals(List.of("102", "103", "104"), g.nodesOf(ComfyGraph.CLASS_LOAD_IMAGE_B64));
		assertEquals(List.of("9"), g.saveNodeIds());
		assertEquals("SwarmKSampler", g.classType("10"));
		assertNull(g.classType("nope"));
		assertNull(g.inputs("nope"));
		/// A null input the generator wrote (model_negative on the 2026-10-26 sampler) is kept as null.
		assertTrue(g.inputs("10").containsKey("model_negative"));
		assertNull(g.inputs("10").get("model_negative"));
		assertEquals("8", ComfyGraph.linkTarget(g.inputs("9").get("images")));
		assertTrue(g.danglingLinks().isEmpty());
		assertTrue("the captured graph is untagged", g.tags().isEmpty());
		ComfyGraph again = ComfyGraph.fromJson(g.toJson());
		assertEquals(g.toJson(), again.toJson());
		assertEquals(g.graphHash(), again.graphHash());
		/// The captured inpaint graphs parse too (2026-10-26 generator: SDXL gained ModelSamplingDiscrete, 17 nodes).
		assertEquals(27, FixtureGraphClient.fixtureGraph(FixtureGraphClient.INPAINT_FLUX2).size());
		assertEquals(17, FixtureGraphClient.fixtureGraph(FixtureGraphClient.INPAINT_SDXL).size());
	}

	@Test
	public void fromJsonRejectsNonGraphs() {
		String[] bad = {null, "", "   ", "[]", "{\"1\": 5}", "{\"1\": {\"inputs\": {}}}", "{\"1\": {\"class_type\": \"X\", \"inputs\": 3}}", "not json"};
		for(String s : bad) {
			try {
				ComfyGraph.fromJson(s);
				fail("expected rejection of " + s);
			}
			catch(IllegalArgumentException e) {
				/// expected
			}
		}
		/// Missing inputs is tolerated as an empty map.
		ComfyGraph g = ComfyGraph.fromJson("{\"1\": {\"class_type\": \"X\"}}");
		assertTrue(g.inputs("1").isEmpty());
	}

	@Test
	public void renameRewritesEveryLink() {
		ComfyGraph g = FixtureGraphClient.fixtureGraph(FixtureGraphClient.BASE_FLUX2);
		g.rename("8", "v3_decode");
		assertFalse(g.hasNode("8"));
		assertTrue(g.hasNode("v3_decode"));
		assertEquals("VAEDecode", g.classType("v3_decode"));
		assertEquals("v3_decode", ComfyGraph.linkTarget(g.inputs("9").get("images")));
		assertEquals(Integer.valueOf(0), ((List<?>) g.inputs("9").get("images")).get(1));
		assertTrue(g.danglingLinks().isEmpty());
		assertEquals("order preserved", 18, g.size());
		try {
			g.rename("nope", "x");
			fail();
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
		try {
			g.rename("9", "v3_decode");
			fail();
		}
		catch(IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("already exists"));
		}
	}

	@Test
	public void danglingLinksReportedAfterRemove() {
		ComfyGraph g = tiny();
		assertTrue(g.removeNode("6"));
		assertFalse(g.removeNode("6"));
		assertEquals(List.of("10.positive -> 6"), g.danglingLinks());
	}

	@Test
	public void addNodeRejectsDuplicatesAndCopiesInputs() {
		ComfyGraph g = tiny();
		Map<String, Object> in = new LinkedHashMap<>();
		in.put("images", List.of("8", 0));
		g.addNode("v3_save_2", ComfyGraph.CLASS_SAVE_IMAGE_WS, in);
		in.put("images", List.of("99", 0));
		assertEquals("8", ComfyGraph.linkTarget(g.inputs("v3_save_2").get("images")));
		assertEquals(List.of(ComfyGraph.SAVE_FINAL, "v3_save_2"), g.saveNodeIds());
		try {
			g.addNode("4", "X", null);
			fail();
		}
		catch(IllegalArgumentException e) {
			assertTrue(e.getMessage().contains("already exists"));
		}
		try {
			g.addNode("", "X", null);
			fail();
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
		try {
			g.setInput("nope", "x", 1);
			fail();
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
	}

	@Test
	public void assertNoInlineImagesRejectsPlaceholderLiteral() {
		ComfyGraph g = FixtureGraphClient.fixtureGraph(FixtureGraphClient.BASE_FLUX2);
		try {
			g.assertNoInlineImages();
			fail("fixture loaders carry literal image data");
		}
		catch(IllegalStateException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("102"));
		}
		for(String id : g.nodesOf(ComfyGraph.CLASS_LOAD_IMAGE_B64)) {
			g.setInput(id, ComfyGraph.INPUT_IMAGE_BASE64, ComfyGraph.tag(ComfyGraph.TYPE_IMAGE, "ref" + id));
		}
		g.assertNoInlineImages();
		assertEquals(Set.of("comfyrawworkflowinputimageswarminputimageref102", "comfyrawworkflowinputimageswarminputimageref103", "comfyrawworkflowinputimageswarminputimageref104"), g.tags());
	}

	@Test
	public void swarmKeyIsLowercaseLettersOnly() {
		assertEquals("comfyrawworkflowinputimageswarminputimagedunaderosa", ComfyGraph.swarmKey(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "duna_de_rosa")));
		assertEquals("comfyrawworkflowinputimageswarminputimageactora", ComfyGraph.swarmKey(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "actorA")));
		/// Digits and underscores vanish, case folds: these are one key to Swarm.
		assertEquals(ComfyGraph.swarmKey(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "ref0")), ComfyGraph.swarmKey(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "ref1")));
		assertEquals(ComfyGraph.swarmKey(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "REF")), ComfyGraph.swarmKey(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "r_e_f")));
		assertNotEquals(ComfyGraph.swarmKey(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "init")), ComfyGraph.swarmKey(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "mask")));
		assertEquals("", ComfyGraph.swarmKey("123_"));
	}

	@Test
	public void graphInputsRefuseKeysSwarmWouldMerge() {
		byte[] png = MaskImage.solid(8, 8, java.awt.Color.RED);
		GraphInputs in = new GraphInputs().image("ref0", png);
		try {
			in.image("ref1", png);
			fail("ref0 and ref1 are one key to Swarm");
		}
		catch(IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("collides"));
		}
		in.text("scene", "a");
		try {
			in.text("Scene", "b");
			fail("case-folded duplicate");
		}
		catch(IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("collides"));
		}
		/// The same key replaces its value; distinct-after-reduction names coexist.
		in.text("scene", "c").image("init", png).image("mask", png).image("duna_de_rosa", png).image("jideon_de_rosa", png);
		assertEquals("c", in.text("scene"));
		assertEquals(5, in.imageCount());
	}

	@Test
	public void graphHashChangesWithStructureNotWithTagValues() {
		ComfyGraph a = tiny();
		String h = a.graphHash();
		/// A tag is part of the graph; what fills it is not.
		GraphInputs x = new GraphInputs().text("scene", "a").seed("seed", 1L);
		GraphInputs y = new GraphInputs().text("scene", "b").seed("seed", 2L);
		assertNotEquals(x.toRequestMap(), y.toRequestMap());
		assertEquals(h, a.graphHash());
		ComfyGraph b = tiny();
		b.setInput("10", "steps", 8);
		assertNotEquals(h, b.graphHash());
		ComfyGraph c = tiny();
		c.setInput("6", "text", ComfyGraph.tag(ComfyGraph.TYPE_TEXT, "other"));
		assertNotEquals("a different tag is a different graph", h, c.graphHash());
	}

	@Test
	public void tagsCollectsEveryTagIncludingNested() {
		ComfyGraph g = tiny();
		g.setInput("6", "text", "prefix ${comfyrawworkflowinputtextscene} ${comfyrawworkflowinputtextstyle}");
		g.setInput("5", "extra", List.of("${comfyrawworkflowinputintegerwidth}", Map.of("k", "${comfyrawworkflowinputdecimalcfg}")));
		assertEquals(Set.of("comfyrawworkflowinputtextscene", "comfyrawworkflowinputtextstyle",
			"comfyrawworkflowinputintegerwidth", "comfyrawworkflowinputdecimalcfg", "comfyrawworkflowinputseedseed"), g.tags());
	}

	@Test
	public void numericIds() {
		assertTrue(ComfyGraph.isNumericId("9"));
		assertTrue(ComfyGraph.isNumericId("118"));
		assertFalse(ComfyGraph.isNumericId(ComfyGraph.SAVE_FINAL));
		assertFalse(ComfyGraph.isNumericId(""));
		assertFalse(ComfyGraph.isNumericId(null));
		assertFalse(ComfyGraph.isNumericId("9a"));
		assertTrue(ComfyGraph.SAVE_FINAL.startsWith(ComfyGraph.ID_PREFIX));
	}

	@Test
	public void linkTargetOnlyMatchesLinks() {
		assertEquals("8", ComfyGraph.linkTarget(List.of("8", 0)));
		assertEquals("8", ComfyGraph.linkTarget(List.of(8, 1)));
		assertNull(ComfyGraph.linkTarget(List.of("8")));
		assertNull(ComfyGraph.linkTarget(List.of("8", "0")));
		assertNull(ComfyGraph.linkTarget("8"));
		assertNull(ComfyGraph.linkTarget(null));
		assertNull(ComfyGraph.linkTarget(List.of(1, 2, 3)));
	}
}
