package org.cote.accountmanager.olio.illustration;

/// A graph could not be produced for a stage: the generator failed, or its graph did not carry the
/// shape the compiler expected to patch (design §38.2). Reported by the adapter as GRAPH_INVALID.
public class GraphCompileException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public GraphCompileException(String message) {
		super(message);
	}

	public GraphCompileException(String message, Throwable cause) {
		super(message, cause);
	}
}
