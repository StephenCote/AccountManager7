package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// What one stage execution produced (design §15): image bytes (never persisted here - persistence
/// goes through PbPipelineUtil.persistBytes), the wall time of the backend call only, the request as
/// sent (sanitised: no session id), the backend graph when the adapter has one, and an error when the
/// stage failed. A failed stage has no images and a non-null error.
public final class StageResult {

	private final List<StageImage> images;
	private final long durationMs;
	private final String requestJson;
	private final String backendGraph;
	private final String error;
	private final String errorDetail;

	private StageResult(Builder b) {
		this.images = Collections.unmodifiableList(new ArrayList<>(b.images));
		this.durationMs = b.durationMs;
		this.requestJson = b.requestJson;
		this.backendGraph = b.backendGraph;
		this.error = b.error;
		this.errorDetail = b.errorDetail;
	}

	public List<StageImage> getImages() { return images; }
	public long getDurationMs() { return durationMs; }
	public String getRequestJson() { return requestJson; }
	public String getBackendGraph() { return backendGraph; }
	public String getError() { return error; }
	/// The findings beyond the one the error names (GRAPH_INVALID with several problems), or null.
	public String getErrorDetail() { return errorDetail; }
	public boolean isOk() { return error == null && !images.isEmpty(); }

	/// The error's code (P5-2 §6.1): {@link RendererAdapter#errorCode}.
	public String getErrorCode() {
		return RendererAdapter.errorCode(error);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static StageResult failed(String error, long durationMs, String requestJson) {
		return builder().error(error).durationMs(durationMs).requestJson(requestJson).build();
	}

	public static final class Builder {
		private final List<StageImage> images = new ArrayList<>();
		private long durationMs;
		private String requestJson;
		private String backendGraph;
		private String error;
		private String errorDetail;

		private Builder() { }

		public Builder image(StageImage image) { if(image != null) images.add(image); return this; }
		public Builder durationMs(long durationMs) { this.durationMs = durationMs; return this; }
		public Builder requestJson(String requestJson) { this.requestJson = requestJson; return this; }
		public Builder backendGraph(String backendGraph) { this.backendGraph = backendGraph; return this; }
		public Builder error(String error) { this.error = error; return this; }
		public Builder errorDetail(String errorDetail) { this.errorDetail = errorDetail; return this; }

		public StageResult build() {
			if(error == null && images.isEmpty()) {
				error = RendererAdapter.ERROR_NO_IMAGES;
			}
			return new StageResult(this);
		}
	}
}
