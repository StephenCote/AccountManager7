package org.cote.accountmanager.olio.illustration;

/// One finding from {@link NodeCatalogue#validate}: an error makes the graph GRAPH_INVALID before it
/// reaches the GPU; a warning is logged and the graph is sent.
public final class GraphProblem {

	public static final String UNKNOWN_NODE_TYPE = "UNKNOWN_NODE_TYPE";
	public static final String MISSING_REQUIRED_INPUT = "MISSING_REQUIRED_INPUT";
	public static final String UNKNOWN_INPUT = "UNKNOWN_INPUT";
	public static final String DANGLING_LINK = "DANGLING_LINK";
	public static final String UNFILLED_TAG = "UNFILLED_TAG";
	public static final String NO_SAVE_NODE = "NO_SAVE_NODE";
	public static final String INLINE_IMAGE = "INLINE_IMAGE";

	private final String code;
	private final String nodeId;
	private final String message;
	private final boolean warning;

	public GraphProblem(String code, String nodeId, String message, boolean warning) {
		this.code = code;
		this.nodeId = nodeId;
		this.message = message;
		this.warning = warning;
	}

	public static GraphProblem error(String code, String nodeId, String message) {
		return new GraphProblem(code, nodeId, message, false);
	}

	public static GraphProblem warning(String code, String nodeId, String message) {
		return new GraphProblem(code, nodeId, message, true);
	}

	public String getCode() { return code; }
	public String getNodeId() { return nodeId; }
	public String getMessage() { return message; }
	public boolean isWarning() { return warning; }
	public boolean isError() { return !warning; }

	@Override
	public String toString() {
		return code + (nodeId != null ? "[" + nodeId + "]" : "") + ": " + message;
	}
}
