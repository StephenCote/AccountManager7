package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.junit.Before;
import org.junit.Test;

/// P5-2 §6: the node-level error contract, offline. parseNodeFailure over the three §6.2 shapes
/// (class with spaces included), the role from the failing node's own placeholder tags, and
/// GRAPH_INVALID raised from the catalogue before any post - node-qualified, durationMs 0, the
/// remaining problems on errorDetail. No server: the catalogue is pre-seeded for a closed loopback
/// port, so a generate attempt would come back UNREACHABLE, never GRAPH_INVALID.
public class TestSwarmRendererAdapterErrors {

	private static final String SERVER = "http://127.0.0.1:9";
	private static final String LOADER_FAILURE = "ComfyUI execution error (Node 4: CheckpointLoaderSimple) (Model=flux2Klein_9b.safetensors): value not in list: ckpt_name";
	private static final String SPACED_CLASS_FAILURE = "ComfyUI execution error (Node 12: Swarm Load Image B64): Invalid base64-encoded string";
	private static final String NO_NODE_FAILURE = "unsupported node type SwarmKSampler on this backend";

	@Before
	public void dropCatalogues() {
		NodeCatalogue.clearCache();
	}

	@Test
	public void parseNodeFailureQualifiesTheNodeAndKeepsTheText() {
		assertEquals("node 4 CheckpointLoaderSimple: " + LOADER_FAILURE, SwarmGraphClient.parseNodeFailure(LOADER_FAILURE));
		assertEquals("node 12 Swarm Load Image B64: " + SPACED_CLASS_FAILURE, SwarmGraphClient.parseNodeFailure(SPACED_CLASS_FAILURE));
		assertEquals("no match: the text as given", NO_NODE_FAILURE, SwarmGraphClient.parseNodeFailure(NO_NODE_FAILURE));
		assertEquals("", SwarmGraphClient.parseNodeFailure(null));

		/// the adapter's composition: code, ": ", the qualified text; every one of the three is a graph rejection
		for(String text : new String[] {LOADER_FAILURE, SPACED_CLASS_FAILURE, NO_NODE_FAILURE}) {
			assertTrue(text, SwarmRendererAdapter.isGraphRejection(text));
			String err = RendererAdapter.ERROR_RENDERER_REJECTED + ": " + SwarmGraphClient.parseNodeFailure(text);
			assertEquals(RendererAdapter.ERROR_RENDERER_REJECTED, err.substring(0, err.indexOf(": ")));
			assertFalse(err.contains("session_id"));
		}
	}

	@Test
	public void roleComesFromTheFailingNodesOwnTags() {
		ComfyGraph g = TestNodeCatalogue.tiny();
		/// node 6 carries the scene tag, node 10 the seed tag, node 4 none
		assertEquals("node 6 CLIPTextEncode (" + WorkflowCompiler.TAG_SCENE + "): boom (Node 6: CLIPTextEncode)",
			SwarmGraphClient.parseNodeFailure("boom (Node 6: CLIPTextEncode)", g));
		assertEquals("node 10 SwarmKSampler (" + WorkflowCompiler.TAG_SEED + "): x (Node 10: SwarmKSampler) y",
			SwarmGraphClient.parseNodeFailure("x (Node 10: SwarmKSampler) y", g));
		assertEquals("node 4 CheckpointLoaderSimple: " + LOADER_FAILURE, SwarmGraphClient.parseNodeFailure(LOADER_FAILURE, g));
		/// a node the graph does not know: no role, still qualified
		assertEquals("node 99 Foo: (Node 99: Foo)", SwarmGraphClient.parseNodeFailure("(Node 99: Foo)", g));
		assertEquals(java.util.Set.of(), g.tagsOf("nope"));
		assertEquals(java.util.Set.of(TestNodeCatalogue.SCENE_KEY), g.tagsOf("6"));
		assertEquals(WorkflowCompiler.TAG_SCENE, ComfyGraph.tagName(TestNodeCatalogue.SCENE_KEY));
		assertEquals(WorkflowCompiler.TAG_SEED, ComfyGraph.tagName(TestNodeCatalogue.SEED_KEY));
		assertEquals("ref0", ComfyGraph.tagName(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, "ref0")));
		assertEquals(WorkflowCompiler.TAG_INIT, ComfyGraph.tagName(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, WorkflowCompiler.TAG_INIT)));
		assertEquals("steps", ComfyGraph.tagName(ComfyGraph.inputKey(ComfyGraph.TYPE_INTEGER, "steps")));
		assertEquals("plain", ComfyGraph.tagName("plain"));
		assertNull(ComfyGraph.tagName(null));
	}

	@Test
	public void graphInvalidIsNodeQualifiedAndNeverPosted() {
		AtomicInteger fetches = new AtomicInteger();
		assertTrue(NodeCatalogue.forServer(SERVER, () -> { fetches.incrementAndGet(); return TestNodeCatalogue.STUB_OBJECT_INFO; }) != null);
		assertEquals(1, fetches.get());

		ComfyGraph g = TestNodeCatalogue.tiny();
		g.addNode("x", "Bogus", Map.of("a", 1));
		GraphInputs inputs = new GraphInputs();
		inputs.text(WorkflowCompiler.TAG_SCENE, "a scene");
		inputs.seed(WorkflowCompiler.TAG_SEED, 7L);
		StageRequest req = StageRequest.builder().stage(RenderPlan.STAGE_BASE).model("a.safetensors").width(1024).height(768)
			.steps(4).graph(g).inputs(inputs).build();

		StageResult res = new SwarmRendererAdapter(SDAPIEnumType.SWARM, SERVER).execute(req, null);
		assertFalse(res.isOk());
		assertEquals(RendererAdapter.ERROR_GRAPH_INVALID, res.getErrorCode());
		assertEquals(RendererAdapter.ERROR_GRAPH_INVALID + ": node x Bogus " + GraphProblem.UNKNOWN_NODE_TYPE + ": node type Bogus is not in the renderer's object_info", res.getError());
		assertNull("one problem: nothing left for the detail", res.getErrorDetail());
		assertEquals("rejected before any post", 0L, res.getDurationMs());
		assertNull(res.getBackendGraph());
		assertTrue(res.getImages().isEmpty());
		assertEquals("the cached catalogue answered; nothing was fetched", 1, fetches.get());
		assertFalse(res.getError().contains("session"));
		assertFalse(res.getError().contains("127.0.0.1"));

		/// two problems: the first names the error, the rest ride on errorDetail
		g.inputs("10").remove("cfg");
		StageResult two = new SwarmRendererAdapter(SDAPIEnumType.SWARM, SERVER).execute(req.toBuilder().graph(g).build(), null);
		assertEquals(RendererAdapter.ERROR_GRAPH_INVALID, two.getErrorCode());
		assertTrue(two.getError(), two.getError().startsWith(RendererAdapter.ERROR_GRAPH_INVALID + ": node "));
		assertTrue(two.getErrorDetail(), two.getErrorDetail() != null && !two.getErrorDetail().isBlank());
		String both = two.getError() + " | " + two.getErrorDetail();
		assertTrue(both, both.contains(GraphProblem.UNKNOWN_NODE_TYPE) && both.contains(GraphProblem.MISSING_REQUIRED_INPUT));
		assertEquals(0L, two.getDurationMs());
		assertEquals(1, fetches.get());
	}

	@Test
	public void errorCodeIsTheFirstToken() {
		assertEquals(RendererAdapter.CODE_UNREACHABLE, StageResult.failed(RendererAdapter.ERROR_UNREACHABLE, 5L, null).getErrorCode());
		assertEquals(RendererAdapter.CODE_CANCELLED, StageResult.failed(RendererAdapter.ERROR_CANCELLED, 5L, null).getErrorCode());
		assertEquals(RendererAdapter.CODE_RENDERER_ERROR, StageResult.failed(RendererAdapter.ERROR_PREFIX_BACKEND + "boom: detail", 5L, null).getErrorCode());
		assertEquals(RendererAdapter.ERROR_RENDERER_REJECTED, StageResult.failed(RendererAdapter.ERROR_RENDERER_REJECTED + ": node 4 X: a: b", 5L, null).getErrorCode());
		assertEquals(RendererAdapter.ERROR_GRAPH_INVALID, StageResult.failed(RendererAdapter.ERROR_GRAPH_INVALID + ": node 3 Foo: missing", 0L, null).getErrorCode());
		assertEquals("free text", StageResult.failed("free text", 5L, null).getErrorCode());
		assertNull(StageResult.builder().image(new StageImage(new byte[] {1}, 1, true, 1, 1)).build().getErrorCode());
		assertEquals(RendererAdapter.CODE_NO_IMAGES, StageResult.builder().build().getErrorCode());
		assertNull(RendererAdapter.errorCode(null));
	}
}
