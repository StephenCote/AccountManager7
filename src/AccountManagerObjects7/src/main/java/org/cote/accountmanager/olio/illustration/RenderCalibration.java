package org.cote.accountmanager.olio.illustration;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// In-process render timing calibration (PictureBook V3 design §18): per server + family + stage, an
/// exponentially weighted moving average of seconds per step per megapixel, fed by every
/// successful stage the adapter completes in this JVM. A Service7 restart forgets it, and another
/// JVM's renders are not included - the estimate's source string says exactly that (see
/// RenderEstimate). Persistence is PbCalibrationUtil's job (as the requesting user, never the org
/// admin); a persisted row may prime a key here with ORIGIN_PERSISTED, and the first in-process
/// measurement folds into that baseline and restarts the in-process count. Samples are immutable and
/// swapped whole under ConcurrentHashMap's per-key atomicity, so a reader never sees a torn pair.
public final class RenderCalibration {

	public static final double ALPHA = 0.3;
	public static final String ORIGIN_MEASURED = "measured";
	public static final String ORIGIN_PERSISTED = "persisted";

	private static final ConcurrentMap<String, Sample> SAMPLES = new ConcurrentHashMap<>();

	private RenderCalibration() { }

	public static final class Sample {
		private final String family;
		private final String stage;
		private final double secondsPerStepPerMegapixel;
		private final int count;
		private final Instant measuredAt;
		private final String origin;

		Sample(String family, String stage, double secondsPerStepPerMegapixel, int count, Instant measuredAt, String origin) {
			this.family = family;
			this.stage = stage;
			this.secondsPerStepPerMegapixel = secondsPerStepPerMegapixel;
			this.count = count;
			this.measuredAt = measuredAt;
			this.origin = origin;
		}

		public String getFamily() { return family; }
		public String getStage() { return stage; }
		public double getSecondsPerStepPerMegapixel() { return secondsPerStepPerMegapixel; }
		public int getCount() { return count; }
		public Instant getMeasuredAt() { return measuredAt; }
		public String getOrigin() { return origin; }
		public boolean isMeasured() { return ORIGIN_MEASURED.equals(origin); }

		Sample fold(double value, Instant at) {
			double next = secondsPerStepPerMegapixel + ALPHA * (value - secondsPerStepPerMegapixel);
			return new Sample(family, stage, next, isMeasured() ? count + 1 : 1, at, ORIGIN_MEASURED);
		}

		@Override
		public String toString() {
			return "Sample{" + family + ", " + stage + ", " + secondsPerStepPerMegapixel + " s/step/MP, n=" + count + ", at=" + measuredAt + ", " + origin + "}";
		}
	}

	static String key(String server, String family, String stage) {
		return (server == null ? "" : server.trim()) + "|" + (family == null ? "" : family.trim()) + "|" + stageOrBase(stage);
	}

	public static String stageOrBase(String stage) {
		return (stage == null || stage.isBlank()) ? RenderPlan.STAGE_BASE : stage.trim();
	}

	/// Fold one completed stage into the average. Ignored when any input is non-positive: a zero
	/// duration or canvas would poison the average, and that is a caller defect, not a sample.
	public static Sample record(String server, String family, String stage, long durationMs, int steps, int width, int height) {
		if(family == null || family.isBlank() || durationMs <= 0 || steps <= 0 || width <= 0 || height <= 0) {
			return null;
		}
		double megapixels = (width * (double) height) / 1_000_000.0;
		double value = (durationMs / 1000.0) / steps / megapixels;
		Instant now = Instant.now();
		String st = stageOrBase(stage);
		return SAMPLES.compute(key(server, family, st), (k, prev) -> prev == null ? new Sample(family, st, value, 1, now, ORIGIN_MEASURED) : prev.fold(value, now));
	}

	/// BASE-stage overload.
	public static Sample record(String server, String family, long durationMs, int steps, int width, int height) {
		return record(server, family, RenderPlan.STAGE_BASE, durationMs, steps, width, height);
	}

	/// Prime a key from a persisted row. Only when nothing is held for the key yet: an in-process
	/// measurement is never replaced by a row it may itself have produced. Returns what the key holds after.
	public static Sample prime(String server, String family, String stage, double secondsPerStepPerMegapixel, int count, Instant measuredAt) {
		if(family == null || family.isBlank() || secondsPerStepPerMegapixel <= 0 || count <= 0) {
			return null;
		}
		String st = stageOrBase(stage);
		return SAMPLES.computeIfAbsent(key(server, family, st), k -> new Sample(family, st, secondsPerStepPerMegapixel, count, measuredAt, ORIGIN_PERSISTED));
	}

	/// The current sample for the server + family + stage, or null when nothing is held in this JVM.
	public static Sample sample(String server, String family, String stage) {
		if(family == null) {
			return null;
		}
		return SAMPLES.get(key(server, family, stage));
	}

	/// BASE-stage overload.
	public static Sample sample(String server, String family) {
		return sample(server, family, RenderPlan.STAGE_BASE);
	}

	/// Test-only: forget every sample.
	public static void clear() {
		SAMPLES.clear();
	}
}
