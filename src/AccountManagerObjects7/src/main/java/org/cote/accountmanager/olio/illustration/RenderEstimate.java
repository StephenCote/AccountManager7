package org.cote.accountmanager.olio.illustration;

import java.util.LinkedHashMap;
import java.util.Map;

/// Time estimate for one plan (PictureBook V3 design §18): derived from an in-process calibration
/// sample when one exists, otherwise an honest "no measurement" - never a guessed figure. The
/// formula is secondsPerStepPerMegapixel x canvas megapixels x steps x variants x stages; the
/// UPSCALE stage is counted at the BASE stage's cost, which overstates nothing the caller can rely
/// on and is stated in the source.
public final class RenderEstimate {

	public static final String SOURCE_NONE = "NONE: no measurement for this server/family yet";
	public static final String SOURCE_SCOPE = "in-process since JVM start; other JVMs' renders not included";

	private RenderEstimate() { }

	public static Map<String, Object> estimate(RenderPlan plan, RenderCalibration.Sample sample) {
		Map<String, Object> m = new LinkedHashMap<>();
		int stages = (plan != null && plan.getStrategy() != null && plan.getStrategy().getStages() != null && !plan.getStrategy().getStages().isEmpty())
			? plan.getStrategy().getStages().size() : 1;
		int variants = (plan != null && plan.getPreset() != null && plan.getPreset().getVariants() > 0) ? plan.getPreset().getVariants() : 1;
		int steps = (plan != null && plan.getPreset() != null) ? plan.getPreset().getSteps() : 0;
		double megapixels = 0;
		if(plan != null && plan.getCanvas() != null) {
			megapixels = (plan.getCanvas().getWidth() * (double) plan.getCanvas().getHeight()) / 1_000_000.0;
		}
		m.put("scenes", 1);
		m.put("stages", stages);
		m.put("variants", variants);
		m.put("steps", steps);
		if(sample != null && steps > 0 && megapixels > 0) {
			double secondsPerStep = sample.getSecondsPerStepPerMegapixel() * megapixels;
			double seconds = secondsPerStep * steps * variants * stages;
			m.put("secondsPerStep", round(secondsPerStep));
			m.put("seconds", round(seconds));
			m.put("measured", true);
			m.put("source", "MEASURED " + sample.getFamily() + " n=" + sample.getCount() + " at " + sample.getMeasuredAt() + "; " + SOURCE_SCOPE
				+ (stages > 1 ? "; UPSCALE counted at BASE cost" : ""));
		}
		else {
			m.put("secondsPerStep", null);
			m.put("seconds", null);
			m.put("measured", false);
			m.put("source", SOURCE_NONE);
		}
		return m;
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
