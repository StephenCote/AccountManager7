package org.cote.accountmanager.olio.llm;

/// Tracks progress of an in-flight summarization task.
/// Shared between ChatService (which starts async summarization and reports status)
/// and ChatUtil (which updates progress as map/reduce phases execute).
public class SummarizeProgress {
	private volatile String phase = "pending";
	private volatile int current = 0;
	private volatile int total = 0;
	private final long startTime;
	private volatile boolean cancelled = false;

	public SummarizeProgress() {
		this.startTime = System.currentTimeMillis();
	}

	public String getPhase() { return phase; }
	public void setPhase(String phase) { this.phase = phase; }

	public int getCurrent() { return current; }
	public void setCurrent(int current) { this.current = current; }
	public void incrementCurrent() { this.current++; }

	public int getTotal() { return total; }
	public void setTotal(int total) { this.total = total; }

	public long getStartTime() { return startTime; }
	public long getElapsedSeconds() { return (System.currentTimeMillis() - startTime) / 1000; }

	public boolean isCancelled() { return cancelled; }
	public void cancel() { this.cancelled = true; }
}
