package org.cote.accountmanager.olio.illustration;

import java.util.List;

/// Renderer backend seam (PictureBook V3 design §15). Adapters return bytes; persistence is the
/// caller's (PbPipelineUtil.persistBytes). Error strings never carry the server URL - a plan
/// artifact is user-visible and the URL is deployment config. cancelActive(stage) from §15 is
/// deliberately not here in Phase 2: Swarm's interrupt endpoint is not wired, so a token is checked
/// before each call instead ({@link CancelToken}).
public interface RendererAdapter {

	String ERROR_UNREACHABLE = "Renderer unreachable or returned no response";
	String ERROR_NO_IMAGES = "Renderer returned no images";
	String ERROR_CANCELLED = "Render cancelled before the stage was sent";
	String ERROR_PREFIX_BACKEND = "Renderer error: ";
	/// A raw graph failed validation before it was sent (design §38.4) - the detail follows ": ".
	String ERROR_GRAPH_INVALID = "GRAPH_INVALID";
	/// Swarm refused a raw graph it was sent (an unsupported node type, a malformed workflow) - the
	/// server's text follows ": ". Distinct from a generation failure, which stays a backend error.
	String ERROR_RENDERER_REJECTED = "RENDERER_REJECTED";

	/// Capabilities of the named model's family, or null when the family is unknown.
	RendererCapabilities capabilities(String model);

	/// Installed checkpoints as the backend lists them, or null when the list could not be read.
	List<String> listModels();

	/// Execute one stage. Never throws for backend failures - a failed stage is a result with
	/// {@link StageResult#getError()} set.
	StageResult execute(StageRequest request, CancelToken cancel);
}
