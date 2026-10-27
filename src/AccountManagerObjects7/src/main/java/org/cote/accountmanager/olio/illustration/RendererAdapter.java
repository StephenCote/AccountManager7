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

	/// Stage error codes (P5-2 §6.1) for the prose errors above, so a consumer classifies every failed
	/// stage by one token: the executor's stageErrors[].code and the ACTOR_FAILED message prefix.
	String CODE_UNREACHABLE = "UNREACHABLE";
	String CODE_NO_IMAGES = "NO_IMAGES";
	String CODE_CANCELLED = "CANCELLED";
	String CODE_RENDERER_ERROR = "RENDERER_ERROR";

	/// The code of a stage error: the prose constants map to their CODE_*, GRAPH_INVALID and
	/// RENDERER_REJECTED are their own first token, anything else is the text before the first ": "
	/// (or the whole text). Null for null.
	static String errorCode(String error) {
		if(error == null) {
			return null;
		}
		if(ERROR_UNREACHABLE.equals(error)) {
			return CODE_UNREACHABLE;
		}
		if(ERROR_NO_IMAGES.equals(error)) {
			return CODE_NO_IMAGES;
		}
		if(ERROR_CANCELLED.equals(error)) {
			return CODE_CANCELLED;
		}
		if(error.startsWith(ERROR_PREFIX_BACKEND)) {
			return CODE_RENDERER_ERROR;
		}
		int i = error.indexOf(": ");
		return (i < 0 ? error : error.substring(0, i));
	}

	/// Capabilities of the named model's family, or null when the family is unknown.
	RendererCapabilities capabilities(String model);

	/// Installed checkpoints as the backend lists them, or null when the list could not be read.
	List<String> listModels();

	/// Execute one stage. Never throws for backend failures - a failed stage is a result with
	/// {@link StageResult#getError()} set.
	StageResult execute(StageRequest request, CancelToken cancel);
}
