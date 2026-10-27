package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.junit.Before;
import org.junit.Test;

/// V3 Phase 5, P5-1: the raw-graph adapter path with no server behind it (design §38.4). A closed
/// port on the loopback answers immediately, so these run with the rest of the offline suite.
public class TestSwarmGraphClientOffline {

	@Before
	public void dropCatalogues() {
		NodeCatalogue.clearCache();
	}

	private static WorkflowCompiler.CompiledGraph compiled() {
		return new WorkflowCompiler().compileBase(TestWorkflowCompiler.flux2Request(), TestWorkflowCompiler.references(3), new FixtureGraphClient());
	}

	private static StageRequest graphRequest(ComfyGraph graph, GraphInputs inputs) {
		return StageRequest.builder().stage(RenderPlan.STAGE_BASE).model(TestWorkflowCompiler.FLUX2)
			.width(TestWorkflowCompiler.W).height(TestWorkflowCompiler.H).graph(graph).inputs(inputs).build();
	}

	@Test
	public void unreachableServerFailsFastWithoutTheUrlInTheError() {
		WorkflowCompiler.CompiledGraph c = compiled();
		SwarmRendererAdapter adapter = new SwarmRendererAdapter(SDAPIEnumType.SWARM, FixtureGraphClient.OFFLINE_SERVER);
		StageRequest req = graphRequest(c.getGraph(), c.getInputs());
		assertTrue(req.isGraph());

		StageResult res = adapter.execute(req, null);
		assertFalse(res.isOk());
		assertEquals(RendererAdapter.ERROR_UNREACHABLE, res.getError());
		assertFalse("the server address is never part of the error", res.getError().contains("127.0.0.1"));
		assertTrue(res.getImages().isEmpty());
		assertNull(res.getBackendGraph());
		/// The request record is written before the post, and carries no image bytes.
		assertNotNull(res.getRequestJson());
		assertTrue(res.getRequestJson().contains(c.getGraph().graphHash()));
		assertFalse(res.getRequestJson().contains("data:image"));
		assertFalse(res.getRequestJson().contains("127.0.0.1"));
		/// The failed catalogue fetch was not cached as "known".
		assertNull(NodeCatalogue.forServer(FixtureGraphClient.OFFLINE_SERVER, () -> null));
	}

	@Test
	public void unfilledTagIsGraphInvalidAndNeverSent() {
		WorkflowCompiler.CompiledGraph c = compiled();
		ComfyGraph g = c.getGraph().copy();
		g.setInput(ComfyGraph.SAVE_FINAL, "filename_prefix", ComfyGraph.tag(ComfyGraph.TYPE_TEXT, "nowhere"));
		SwarmRendererAdapter adapter = new SwarmRendererAdapter(SDAPIEnumType.SWARM, FixtureGraphClient.OFFLINE_SERVER);

		StageResult res = adapter.execute(graphRequest(g, c.getInputs()), null);
		assertFalse(res.isOk());
		assertTrue(res.getError(), res.getError().startsWith(RendererAdapter.ERROR_GRAPH_INVALID + ": "));
		assertTrue(res.getError(), res.getError().contains(ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, "nowhere")));
		assertEquals("rejected before any post", 0L, res.getDurationMs());
		assertTrue(res.getRequestJson().contains(g.graphHash()));
	}

	@Test
	public void danglingLinkIsGraphInvalidAndNamesTheNode() {
		WorkflowCompiler.CompiledGraph c = compiled();
		ComfyGraph g = c.getGraph().copy();
		assertTrue(g.removeNode("8"));
		List<String> dangling = g.danglingLinks();
		assertTrue(dangling.toString(), dangling.contains(ComfyGraph.SAVE_FINAL + ".images -> 8"));
		SwarmRendererAdapter adapter = new SwarmRendererAdapter(SDAPIEnumType.SWARM, FixtureGraphClient.OFFLINE_SERVER);

		StageResult res = adapter.execute(graphRequest(g, c.getInputs()), null);
		assertFalse(res.isOk());
		assertTrue(res.getError(), res.getError().startsWith(RendererAdapter.ERROR_GRAPH_INVALID + ": "));
		/// P5-2 §6.2: "GRAPH_INVALID: node <id> <class> <code>: <message>"
		assertTrue(res.getError(), res.getError().startsWith(RendererAdapter.ERROR_GRAPH_INVALID + ": node " + ComfyGraph.SAVE_FINAL + " "
			+ g.classType(ComfyGraph.SAVE_FINAL) + " " + GraphProblem.DANGLING_LINK + ": "));
		assertEquals(RendererAdapter.ERROR_GRAPH_INVALID, res.getErrorCode());
		assertEquals(0L, res.getDurationMs());
	}

	@Test
	public void cancelledBeforeSendIsReportedAsCancelled() {
		WorkflowCompiler.CompiledGraph c = compiled();
		SwarmRendererAdapter adapter = new SwarmRendererAdapter(SDAPIEnumType.SWARM, FixtureGraphClient.OFFLINE_SERVER);
		StageResult res = adapter.execute(graphRequest(c.getGraph(), c.getInputs()), () -> true);
		assertEquals(RendererAdapter.ERROR_CANCELLED, res.getError());
		assertEquals(0L, res.getDurationMs());
	}

	@Test
	public void rawResponseClassifiesSwarmAnswers() {
		SwarmGraphClient.RawResponse ok = new SwarmGraphClient.RawResponse(List.of("View/local/raw/a.png"), null, 12L, false);
		assertFalse(ok.isUnreachable());
		assertNull(ok.getError());
		assertEquals(1, ok.getImagePaths().size());
		SwarmGraphClient.RawResponse err = new SwarmGraphClient.RawResponse(null, "Unsupported node type 'Nope'", 3L, false);
		assertTrue(err.getImagePaths().isEmpty());
		assertEquals("Unsupported node type 'Nope'", err.getError());
		SwarmGraphClient.RawResponse down = new SwarmGraphClient.RawResponse(null, null, 0L, true);
		assertTrue(down.isUnreachable());
		assertTrue(down.getImagePaths().isEmpty());
	}

	@Test
	public void graphRejectionPhrasesAreRecognised() {
		assertTrue(SwarmRendererAdapter.isGraphRejection("Unsupported node type 'Nope'"));
		assertTrue(SwarmRendererAdapter.isGraphRejection("Invalid workflow: node 10 has no input 'cfg'"));
		assertTrue(SwarmRendererAdapter.isGraphRejection("ComfyUI execution error: Prompt outputs failed validation"));
		assertFalse(SwarmRendererAdapter.isGraphRejection("Invalid session"));
		assertFalse(SwarmRendererAdapter.isGraphRejection("Model flux2Klein_9b not found"));
		assertFalse(SwarmRendererAdapter.isGraphRejection(null));
	}
}
