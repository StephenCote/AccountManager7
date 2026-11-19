package org.cote.accountmanager.util;

/// Raised by {@link AdminMaintenanceUtil} when a maintenance request cannot be honoured. Carries the
/// HTTP-style status the transport layer should map it to (401 unauthenticated, 403 not an
/// administrator / scope not permitted, 400 bad argument, 404 not found, 500 internal), mirroring
/// PictureBookException so Service7 stays a thin mapper.
public class AdminMaintenanceException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final int status;

	public AdminMaintenanceException(int status, String message) {
		super(message);
		this.status = status;
	}

	public AdminMaintenanceException(int status, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
	}

	public int getStatus() {
		return status;
	}
}
