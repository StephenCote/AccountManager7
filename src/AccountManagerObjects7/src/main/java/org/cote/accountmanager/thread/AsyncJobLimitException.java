package org.cote.accountmanager.thread;

/**
 * Thrown by {@link AsyncJobRegistry#submit} when the principal already has
 * {@link AsyncJobRegistry#MAX_ACTIVE_JOBS_PER_PRINCIPAL} jobs that are queued or running.
 *
 * <p>Unchecked and distinct from the null return on purpose: null means "no usable principal, run
 * synchronously instead", and falling back to a synchronous run here would hand the caller exactly
 * the unbounded work the cap exists to refuse. Transport layers should answer 429.
 */
public class AsyncJobLimitException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final int active;
	private final int limit;

	public AsyncJobLimitException(int active, int limit) {
		super("Too many active async jobs for this principal: " + active + " of " + limit + " already queued or running");
		this.active = active;
		this.limit = limit;
	}

	public int getActive() {
		return active;
	}

	public int getLimit() {
		return limit;
	}
}
