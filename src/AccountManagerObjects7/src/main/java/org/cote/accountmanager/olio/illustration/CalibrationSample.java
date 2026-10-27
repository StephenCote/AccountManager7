package org.cote.accountmanager.olio.illustration;

import java.time.Instant;

/// One calibration figure the estimate consumes (P5-2 §4.4): seconds per step per megapixel for one
/// stage kind, with where it came from spelled out in `source` so a reader can tell a figure this JVM
/// measured from one read back from a persisted row or taken from the capabilities seed. Built by
/// PbCalibrationUtil.sample; the factories here keep the wording in one place.
public final class CalibrationSample {

	public static final String SOURCE_MEASURED = "measured in-process";
	public static final String SOURCE_PERSISTED_OWN = "persisted from your renders on ";
	public static final String SOURCE_PERSISTED_OTHER = "a persisted record on ";
	public static final String SOURCE_SEED = "seed from capabilities.json - not yet measured on this server";

	private final String stage;
	private final double secondsPerStepPerMegapixel;
	private final int sampleCount;
	private final Instant measuredAt;
	private final String source;
	private final boolean seed;

	private CalibrationSample(String stage, double secondsPerStepPerMegapixel, int sampleCount, Instant measuredAt, String source, boolean seed) {
		this.stage = stage;
		this.secondsPerStepPerMegapixel = secondsPerStepPerMegapixel;
		this.sampleCount = sampleCount;
		this.measuredAt = measuredAt;
		this.source = source;
		this.seed = seed;
	}

	/// "measured in-process (n samples since JVM start)"
	public static CalibrationSample measured(String stage, double secondsPerStepPerMegapixel, int sampleCount, Instant measuredAt) {
		return new CalibrationSample(stage, secondsPerStepPerMegapixel, sampleCount, measuredAt,
			SOURCE_MEASURED + " (" + sampleCount + " samples since JVM start)", false);
	}

	/// "persisted from your renders on <server> (n samples, last <measuredAt>)" when the reader owns the
	/// row; "a persisted record on <server>" otherwise - the figure is used, the provenance is not claimed.
	public static CalibrationSample persisted(String stage, double secondsPerStepPerMegapixel, int sampleCount, Instant measuredAt, String server, boolean own) {
		String src = own
			? SOURCE_PERSISTED_OWN + server + " (" + sampleCount + " samples, last " + measuredAt + ")"
			: SOURCE_PERSISTED_OTHER + server;
		return new CalibrationSample(stage, secondsPerStepPerMegapixel, sampleCount, measuredAt, src, false);
	}

	/// "seed from capabilities.json - not yet measured on this server"
	public static CalibrationSample seed(String stage, double secondsPerStepPerMegapixel) {
		return new CalibrationSample(stage, secondsPerStepPerMegapixel, 0, null, SOURCE_SEED, true);
	}

	/// An in-process entry, by its origin flag.
	public static CalibrationSample of(RenderCalibration.Sample s, String server, boolean own) {
		if(s == null) {
			return null;
		}
		return s.isMeasured()
			? measured(s.getStage(), s.getSecondsPerStepPerMegapixel(), s.getCount(), s.getMeasuredAt())
			: persisted(s.getStage(), s.getSecondsPerStepPerMegapixel(), s.getCount(), s.getMeasuredAt(), server, own);
	}

	public String getStage() { return stage; }
	public double getSecondsPerStepPerMegapixel() { return secondsPerStepPerMegapixel; }
	public int getSampleCount() { return sampleCount; }
	public Instant getMeasuredAt() { return measuredAt; }
	public String getSource() { return source; }
	public boolean isSeed() { return seed; }

	@Override
	public String toString() {
		return "CalibrationSample{" + stage + ", " + secondsPerStepPerMegapixel + " s/step/MP, n=" + sampleCount + ", " + source + "}";
	}
}
