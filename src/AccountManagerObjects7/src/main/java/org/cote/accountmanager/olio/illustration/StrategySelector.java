package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.olio.illustration.RenderPlan.Strategy;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;

/// Picks the render strategy for a plan (PictureBook V3 design §13). TEXT_ONLY when nothing is
/// referenced or the family cannot honour what is; DIRECT_REFERENCE when every referenced actor rides
/// in BASE (P5-2); SEQUENTIAL_INPAINT when any actor takes an ACTOR_REGION pass (P5-2; HARMONIZE is
/// deferred, unmeasured). Never throws: when a referenced actor cannot be honoured, the actor is
/// rendered from its description and the plan says so with an ACTOR_TEXT_ONLY degradation (§19: a
/// degradation never stops the render).
public final class StrategySelector {

	public static final String REASON_NO_REFERENCED_ACTORS = "TEXT_ONLY: no referenced actors";
	public static final String REASON_FORCED = "TEXT_ONLY: forced";
	public static final String REASON_NO_REFERENCE_SUPPORT_PREFIX = "TEXT_ONLY: renderer family ";
	public static final String REASON_NO_REFERENCE_SUPPORT_SUFFIX = " has no reference support";
	public static final String REASON_NO_MASK_SUPPORT_SUFFIX = " has no mask support";
	public static final String REASON_UNKNOWN_FAMILY = "TEXT_ONLY: renderer family unknown";
	public static final String REASON_NO_SCENE_DIRECT_PICK = "TEXT_ONLY: no resolved SCENE_DIRECT pick";
	/// Appended to a SEQUENTIAL_INPAINT reason on a family that takes no references (SDXL): the passes
	/// are masked inpaints from the compiled region prompt, and no portrait is sent (design §14 L913).
	public static final String REASON_REGION_TEXT_ONLY_SUFFIX = "; region passes carry no portrait (family takes no references)";

	private StrategySelector() { }

	/// Choose the strategy and stage list for the plan. {@code caps} is the family that renders BASE under
	/// a reference strategy (the SCENE_DIRECT pick's when it resolved); it may be null (unknown family).
	/// Degradations are appended to {@code out} (which may be null).
	public static Strategy select(RenderPlan plan, RendererCapabilities caps, boolean forced, List<Degradation> out) {
		List<String> stages = stagesFor(plan);
		if(forced) {
			return new Strategy(PbStrategyEnumType.TEXT_ONLY, stages, REASON_FORCED);
		}
		List<RenderPlan.Actor> referenced = new ArrayList<>();
		List<RenderPlan.Actor> region = new ArrayList<>();
		if(plan != null) {
			for(RenderPlan.Actor a : plan.getActors()) {
				if(a.isReferenced()) {
					referenced.add(a);
					if(RenderPlan.STAGE_ACTOR_REGION.equals(a.getReferenceStage())) {
						region.add(a);
					}
				}
			}
		}
		if(referenced.isEmpty()) {
			return new Strategy(PbStrategyEnumType.TEXT_ONLY, stages, REASON_NO_REFERENCED_ACTORS);
		}
		int baseRefs = referenced.size() - region.size();
		String reason = null;
		String why = null;
		if(caps == null) {
			reason = REASON_UNKNOWN_FAMILY;
			why = "renderer family unknown";
		}
		else if(baseRefs > 0 && !caps.isSupportsReferences()) {
			reason = REASON_NO_REFERENCE_SUPPORT_PREFIX + caps.getPattern() + REASON_NO_REFERENCE_SUPPORT_SUFFIX;
			why = "renderer family " + caps.getPattern() + " has no reference support";
		}
		else if(!region.isEmpty() && (!caps.isSupportsMasks() || caps.getInpaint() == null)) {
			reason = REASON_NO_REFERENCE_SUPPORT_PREFIX + caps.getPattern() + REASON_NO_MASK_SUPPORT_SUFFIX;
			why = "renderer family " + caps.getPattern() + " has no mask support";
		}
		else if(!hasResolvedSceneDirectPick(plan)) {
			/// A reference strategy renders BASE with the SCENE_DIRECT pick (RenderPlan.pickForStage); a plan
			/// that never resolved one (the chat scene path) stays TEXT_ONLY rather than planning a pick it lacks.
			reason = REASON_NO_SCENE_DIRECT_PICK;
			why = "no resolved SCENE_DIRECT pick";
		}
		if(reason != null) {
			if(out != null) {
				for(RenderPlan.Actor a : referenced) {
					String name = (a.getName() != null && !a.getName().isBlank() ? a.getName() : a.getActorId());
					out.add(new Degradation(Degradation.ACTOR_TEXT_ONLY,
						name + " rendered from description (" + why + ")", a.getActorId(), RenderPlan.STAGE_BASE));
				}
			}
			return new Strategy(PbStrategyEnumType.TEXT_ONLY, stages, reason);
		}
		int slots = caps.getMaxReferences();
		if(!region.isEmpty()) {
			stages.add(stages.indexOf(RenderPlan.STAGE_BASE) + 1, RenderPlan.STAGE_ACTOR_REGION);
			return new Strategy(PbStrategyEnumType.SEQUENTIAL_INPAINT, stages,
				"SEQUENTIAL_INPAINT: " + referenced.size() + " referenced actors, " + slots + " slots, " + region.size()
				+ " ACTOR_REGION pass" + (region.size() == 1 ? "" : "es") + "; HARMONIZE deferred (unmeasured)"
				+ (caps.isSupportsReferences() ? "" : REASON_REGION_TEXT_ONLY_SUFFIX));
		}
		return new Strategy(PbStrategyEnumType.DIRECT_REFERENCE, stages,
			"DIRECT_REFERENCE: " + referenced.size() + " referenced actor" + (referenced.size() == 1 ? "" : "s")
			+ " within " + slots + " slot" + (slots == 1 ? "" : "s"));
	}

	private static boolean hasResolvedSceneDirectPick(RenderPlan plan) {
		if(plan == null || plan.getModels() == null) return false;
		RenderPlan.ModelPick pick = plan.getModels().get(ModelTaskKeys.key(PbModelTaskEnumType.SCENE_DIRECT));
		return pick != null && pick.isResolved();
	}

	/// BASE, plus UPSCALE when the preset asks for it.
	static List<String> stagesFor(RenderPlan plan) {
		List<String> stages = new ArrayList<>();
		stages.add(RenderPlan.STAGE_BASE);
		if(plan != null && plan.getPreset() != null && plan.getPreset().isUpscale()) {
			stages.add(RenderPlan.STAGE_UPSCALE);
		}
		return stages;
	}
}
