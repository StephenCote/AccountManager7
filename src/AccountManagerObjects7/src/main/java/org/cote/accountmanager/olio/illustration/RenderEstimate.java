package org.cote.accountmanager.olio.illustration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Time estimate for one plan (PictureBook V3 design §18, P5-2 §4.4): per stage kind, from the
/// calibration sample the caller resolved for it (in-process, persisted, or the capabilities seed -
/// each says which in its source), otherwise an honest "no figure" - never a guessed number.
///
/// BASE = s_base x steps x MP + secondsPerStepPerReference x steps x references (the references the
/// base prompt speaks: BASE-stage actors plus the location when the strategy sends references);
/// each ACTOR_REGION pass = s_region x effectiveSteps x MP (the family's inpaint steps minus Swarm's
/// start_at_step); UPSCALE is counted at BASE cost, as before. The whole is multiplied by variants.
public final class RenderEstimate {

	public static final String SOURCE_NONE = "NONE: no measurement for this server/family yet";
	public static final String NOTE_UPSCALE = "UPSCALE counted at BASE cost";

	private RenderEstimate() { }

	/// Samples keyed by stage kind (RenderPlan.STAGE_BASE, STAGE_ACTOR_REGION); a missing key is "no figure".
	public static Map<String, Object> estimate(RenderPlan plan, Map<String, CalibrationSample> samples) {
		Map<String, Object> m = new LinkedHashMap<>();
		List<String> stageList = (plan != null && plan.getStrategy() != null && plan.getStrategy().getStages() != null && !plan.getStrategy().getStages().isEmpty())
			? plan.getStrategy().getStages() : List.of(RenderPlan.STAGE_BASE);
		int variants = (plan != null && plan.getPreset() != null && plan.getPreset().getVariants() > 0) ? plan.getPreset().getVariants() : 1;
		int steps = (plan != null && plan.getPreset() != null) ? plan.getPreset().getSteps() : 0;
		double megapixels = 0;
		if(plan != null && plan.getCanvas() != null) {
			megapixels = (plan.getCanvas().getWidth() * (double) plan.getCanvas().getHeight()) / 1_000_000.0;
		}
		int references = baseReferences(plan);
		List<RenderPlan.Actor> regionActors = regionActors(plan);
		boolean upscale = stageList.contains(RenderPlan.STAGE_UPSCALE);

		CalibrationSample base = (samples == null ? null : samples.get(RenderPlan.STAGE_BASE));
		CalibrationSample region = (samples == null ? null : samples.get(RenderPlan.STAGE_ACTOR_REGION));

		m.put("scenes", 1);
		m.put("stages", stageList.size());
		m.put("variants", variants);
		m.put("steps", steps);
		m.put("references", references);
		m.put("regionPasses", regionActors.size());

		Map<String, Object> perStage = new LinkedHashMap<>();
		Map<String, Object> sources = new LinkedHashMap<>();
		boolean complete = true;
		double total = 0;
		Double secondsPerStep = null;

		if(base != null && steps > 0 && megapixels > 0) {
			double sps = base.getSecondsPerStepPerMegapixel() * megapixels;
			double baseSeconds = sps * steps;
			Double perRef = perReference(plan);
			if(references > 0) {
				if(perRef != null) {
					baseSeconds += perRef * steps * references;
				}
				else {
					complete = false;
				}
			}
			secondsPerStep = round(sps);
			perStage.put(RenderPlan.STAGE_BASE, round(baseSeconds));
			sources.put(RenderPlan.STAGE_BASE, base.getSource());
			total += baseSeconds;
			if(upscale) {
				perStage.put(RenderPlan.STAGE_UPSCALE, round(sps * steps));
				sources.put(RenderPlan.STAGE_UPSCALE, NOTE_UPSCALE);
				total += sps * steps;
			}
		}
		else {
			complete = false;
			sources.put(RenderPlan.STAGE_BASE, SOURCE_NONE);
		}

		if(!regionActors.isEmpty()) {
			int effective = effectiveRegionSteps(plan);
			if(region != null && effective > 0 && megapixels > 0) {
				double pass = region.getSecondsPerStepPerMegapixel() * effective * megapixels;
				double all = pass * regionActors.size();
				perStage.put(RenderPlan.STAGE_ACTOR_REGION, round(all));
				sources.put(RenderPlan.STAGE_ACTOR_REGION, region.getSource());
				m.put("regionEffectiveSteps", effective);
				total += all;
			}
			else {
				complete = false;
				sources.put(RenderPlan.STAGE_ACTOR_REGION, SOURCE_NONE);
			}
		}

		m.put("secondsPerStep", secondsPerStep);
		m.put("perStage", perStage);
		m.put("sources", sources);
		if(base != null && steps > 0 && megapixels > 0) {
			m.put("seconds", round(total * variants));
			m.put("measured", !base.isSeed());
			m.put("complete", complete);
			StringBuilder src = new StringBuilder(base.getSource());
			if(!regionActors.isEmpty()) {
				src.append("; ACTOR_REGION: ").append(sources.get(RenderPlan.STAGE_ACTOR_REGION));
			}
			if(upscale) {
				src.append("; ").append(NOTE_UPSCALE);
			}
			m.put("source", src.toString());
		}
		else {
			m.put("seconds", null);
			m.put("measured", false);
			m.put("complete", false);
			m.put("source", SOURCE_NONE);
		}
		return m;
	}

	/// One in-process BASE sample (the pre-P5-2 shape); null is "no figure".
	public static Map<String, Object> estimate(RenderPlan plan, RenderCalibration.Sample sample) {
		Map<String, CalibrationSample> samples = new LinkedHashMap<>();
		if(sample != null) {
			samples.put(RenderPlan.STAGE_BASE, CalibrationSample.of(sample, null, false));
		}
		return estimate(plan, samples);
	}

	/// The references the base prompt sends: BASE-stage referenced actors plus the location render when
	/// the strategy sends references at all (Flux2Compiler.usesReferences).
	static int baseReferences(RenderPlan plan) {
		if(plan == null || !Flux2Compiler.usesReferences(plan.getStrategy())) {
			return 0;
		}
		int n = 0;
		for(RenderPlan.Actor a : plan.getActors()) {
			if(a.isReferenced() && RenderPlan.STAGE_BASE.equals(a.getReferenceStage())) {
				n++;
			}
		}
		if(plan.getLocation() != null && plan.getLocation().getRenderArtifactId() != null) {
			n++;
		}
		return n;
	}

	static List<RenderPlan.Actor> regionActors(RenderPlan plan) {
		List<RenderPlan.Actor> out = new java.util.ArrayList<>();
		if(plan == null || plan.getStrategy() == null || plan.getStrategy().getStages() == null
			|| !plan.getStrategy().getStages().contains(RenderPlan.STAGE_ACTOR_REGION)) {
			return out;
		}
		for(RenderPlan.Actor a : plan.getActors()) {
			if(a.isReferenced() && RenderPlan.STAGE_ACTOR_REGION.equals(a.getReferenceStage())) {
				out.add(a);
			}
		}
		return out;
	}

	static Double perReference(RenderPlan plan) {
		RendererCapabilities caps = capsFor(plan, RenderPlan.STAGE_BASE);
		return (caps == null ? null : caps.getSecondsPerStepPerReference());
	}

	/// The family's inpaint steps minus Swarm's start_at_step; 0 when the family declares no inpaint.
	static int effectiveRegionSteps(RenderPlan plan) {
		RendererCapabilities caps = capsFor(plan, RenderPlan.STAGE_ACTOR_REGION);
		if(caps == null || caps.getInpaint() == null) {
			return 0;
		}
		RendererCapabilities.InpaintParams ip = caps.getInpaint();
		return ip.getSteps() - WorkflowCompiler.startAtStep(ip.getSteps(), ip.getCreativity());
	}

	private static RendererCapabilities capsFor(RenderPlan plan, String stage) {
		if(plan == null) {
			return null;
		}
		RenderPlan.ModelPick pick = plan.pickForStage(stage);
		return (pick == null ? null : RendererCapabilities.forModel(pick.getModel()));
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
