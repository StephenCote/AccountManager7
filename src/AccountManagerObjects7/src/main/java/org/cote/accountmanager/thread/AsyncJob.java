package org.cote.accountmanager.thread;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.olio.llm.SummarizeProgress;

/**
 * One background job: its identity, its owner, its live progress, and — once finished — its
 * retained result.
 *
 * <p><b>The retained result is the point of this class.</b> A long LLM run used to exist only as an
 * in-flight HTTP request, so anything that broke the connection destroyed the work: nginx's
 * {@code proxy_read_timeout} firing mid-run, the user reloading, the user navigating away. Measured
 * 2026-09-13 on a 17-chunk extraction — nginx returned 504 at exactly 900s while Tomcat carried on
 * to chunk 11/17, and all ~27 minutes of LLM output was discarded because nothing persisted it and
 * the only copy was destined for a socket that had already closed. Holding the finished result here
 * for a TTL means the client can come back and collect it.
 *
 * <p>Progress is tracked by the existing {@link SummarizeProgress} rather than new fields: it
 * already carries phase/current/total/elapsed/cancelled, it is already what
 * {@code PictureBookUtil.extractChunkedInternal} and {@code ChatUtil.mapSummarize} update as they
 * work, and its {@code cancel()} is already the cooperative stop signal those loops check at chunk
 * boundaries.
 *
 * <p>Mutable fields are {@code volatile}: the worker thread writes them and poll requests on
 * unrelated request threads read them.
 */
public class AsyncJob {

	/**
	 * Job lifecycle. {@code QUEUED} is a real, observable state rather than an implementation
	 * detail — the executor bounds concurrency (a GPU-backed LLM server processes requests
	 * sequentially), so a submitted job genuinely may not be running yet, and a client that is told
	 * "running" when nothing is happening cannot show an honest status.
	 */
	public enum Status {
		QUEUED,
		RUNNING,
		COMPLETED,
		FAILED,
		CANCELLED
	}

	private final String jobId;
	private final String kind;
	private final String key;
	private final String ownerObjectId;
	private final SummarizeProgress progress;
	private final long createdAt;

	private volatile Status status = Status.QUEUED;
	private volatile String result;
	private volatile String error;
	private volatile long completedAt;
	/// Guarded by the job instance; only ever replaced wholesale, never mutated in place.
	private volatile List<String> failedExtractions = new ArrayList<>();

	AsyncJob(String jobId, String kind, String key, String ownerObjectId, SummarizeProgress progress) {
		this.jobId = jobId;
		this.kind = kind;
		this.key = key;
		this.ownerObjectId = ownerObjectId;
		this.progress = progress;
		this.createdAt = System.currentTimeMillis();
	}

	public String getJobId() {
		return jobId;
	}

	/** What kind of work this is, e.g. {@code pb.extractScenes} — for diagnostics and client display. */
	public String getKind() {
		return kind;
	}

	/** The caller-supplied work/book objectId this job operates on. */
	public String getKey() {
		return key;
	}

	public String getOwnerObjectId() {
		return ownerObjectId;
	}

	public SummarizeProgress getProgress() {
		return progress;
	}

	public Status getStatus() {
		return status;
	}

	void setStatus(Status status) {
		this.status = status;
	}

	/** The finished payload (serialized JSON), or null while the job is still running. */
	public String getResult() {
		return result;
	}

	void setResult(String result) {
		this.result = result;
	}

	public String getError() {
		return error;
	}

	void setError(String error) {
		this.error = error;
	}

	public long getCreatedAt() {
		return createdAt;
	}

	/** Wall-clock ms at which the job reached a terminal status, or 0 if it has not. */
	public long getCompletedAt() {
		return completedAt;
	}

	void setCompletedAt(long completedAt) {
		this.completedAt = completedAt;
	}

	public List<String> getFailedExtractions() {
		return failedExtractions;
	}

	void setFailedExtractions(List<String> failedExtractions) {
		this.failedExtractions = (failedExtractions == null) ? new ArrayList<String>() : failedExtractions;
	}

	public boolean isTerminal() {
		return status == Status.COMPLETED || status == Status.FAILED || status == Status.CANCELLED;
	}

	/** Elapsed seconds since submission; delegates to the progress token's own clock once running. */
	public long getElapsedSeconds() {
		long end = (completedAt > 0) ? completedAt : System.currentTimeMillis();
		return (end - createdAt) / 1000L;
	}
}
