package org.cote.accountmanager.olio.picturebook;

/**
 * Carries an HTTP-style status code + message out of {@link PictureBookUtil} so the thin
 * REST transport layer ({@code PictureBookService}) can build the exact same
 * {@code Response.status(status).entity("{\"error\":\"" + message + "\"}")} shape it built
 * inline before this logic moved to Objects7. Objects7 has no JAX-RS dependency, so the
 * business logic signals failures via this unchecked exception rather than a jakarta.ws.rs.core.Response.
 */
public class PictureBookException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int status;

	public PictureBookException(int status, String message) {
		super(message);
		this.status = status;
	}

	public PictureBookException(int status, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	public int getStatus() {
		return status;
	}
}
