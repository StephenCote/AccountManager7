package org.cote.accountmanager.thread;

/**
 * The unit of work an {@link AsyncJob} runs.
 *
 * <p>Returns the finished payload as a JSON string, which {@link AsyncJobRegistry} retains on the
 * job so a client can collect it after the originating HTTP request is long gone. Returning the
 * already-serialized form keeps this interface free of any knowledge of what the work produces —
 * callers own their own result shapes.
 *
 * <p>Implementations receive the job so they can read its {@link AsyncJob#getProgress()} token:
 * that is both how they report progress (phase/current/total) and how they observe cooperative
 * cancellation, which they must check at natural boundaries — the same contract
 * {@code PictureBookUtil.extractChunkedInternal} already honours at the top of each chunk.
 *
 * <p>Throwing marks the job {@code FAILED} with the exception message; the registry logs it.
 */
public interface AsyncJobWork {
	String run(AsyncJob job) throws Exception;
}
