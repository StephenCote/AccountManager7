package org.cote.accountmanager.olio.illustration;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// In-process render timing calibration (PictureBook V3 design §18): per server + family, an
/// exponentially weighted moving average of seconds per step per megapixel, fed by every
/// successful stage the adapter completes in this JVM. Never persisted (and never as the org
/// admin): a Service7 restart forgets it, and another JVM's renders are not included - the
/// estimate's source string says exactly that (see RenderEstimate). Samples are immutable and
/// swapped whole under ConcurrentHashMap's per-key atomicity, so a reader never sees a torn pair.
public final class RenderCalibration {

	public static final double ALPHA = 0.3;

	private static final ConcurrentMap<String, Sample> SAMPLES = new ConcurrentHashMap<>();

	private RenderCalibration() { }

	public static final class Sample {
		private final String family;
		private final double secondsPerStepPerMegapixel;
		private final int count;
		private final Instant measuredAt;

		Sample(String family, double secondsPerStepPerMegapixel, int count, Instant measuredAt) {
			this.family = family;
			this.secondsPerStepPerMegapixel = secondsPerStepPerMegapixel;
			this.count = count;
			this.measuredAt = measuredAt;
		}

		public String getFamily() { return family; }
		public double getSecondsPerStepPerMegapixel() { return secondsPerStepPerMegapixel; }
		public int getCount() { return count; }
		public Instant getMeasuredAt() { return measuredAt; }

		Sample fold(double value, Instant at) {
			double next = secondsPerStepPerMegapixel + ALPHA * (value - secondsPerStepPerMegapixel);
			return new Sample(family, next, count + 1, at);
		}

		@Override
		public String toString() {
			return "Sample{" + family + ", " + secondsPerStepPerMegapixel + " s/step/MP, n=" + count + ", at=" + measuredAt + "}";
		}
	}

	static String key(String server, String family) {
		return (server == null ? "" : server.trim()) + "|" + (family == null ? "" : family.trim());
	}

	/// Fold one completed stage into the average. Ignored when any input is non-positive: a zero
	/// duration or canvas would poison the average, and that is a caller defect, not a sample.
	public static Sample record(String server, String family, long durationMs, int steps, int width, int height) {
		if(family == null || family.isBlank() || durationMs <= 0 || steps <= 0 || width <= 0 || height <= 0) {
			return null;
		}
		double megapixels = (width * (double) height) / 1_000_000.0;
		double value = (durationMs / 1000.0) / steps / megapixels;
		Instant now = Instant.now();
		return SAMPLES.compute(key(server, family), (k, prev) -> prev == null ? new Sample(family, value, 1, now) : prev.fold(value, now));
	}

	/// The current sample for the server + family, or null when nothing has been measured in this JVM.
	public static Sample sample(String server, String family) {
		if(family == null) {
			return null;
		}
		return SAMPLES.get(key(server, family));
	}

	/// Test-only: forget every sample.
	public static void clear() {
		SAMPLES.clear();
	}
}
