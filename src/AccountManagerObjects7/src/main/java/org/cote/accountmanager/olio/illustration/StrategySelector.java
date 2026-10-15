package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.olio.illustration.RenderPlan.Strategy;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;

/// Picks the render strategy for a plan (PictureBook V3 design §13). Phase 2 implements TEXT_ONLY
/// only; the other strategies arrive with their phases. Never throws: when a referenced actor
/// cannot be honoured, the actor is rendered from its description and the plan says so with an
/// ACTOR_TEXT_ONLY degradation (§19: a degradation never stops the render).
public final class StrategySelector {

	public static final String REASON_NO_REFERENCED_ACTORS = "TEXT_ONLY: no referenced actors";
	public static final String REASON_FORCED = "TEXT_ONLY: forced";
	public static final String REASON_NO_REFERENCE_SUPPORT_PREFIX = "TEXT_ONLY: renderer family ";
	public static final String REASON_NO_REFERENCE_SUPPORT_SUFFIX = " has no reference support";
	public static final String REASON_UNKNOWN_FAMILY = "TEXT_ONLY: renderer family unknown";
	public static final String REASON_REFERENCES_NOT_IMPLEMENTED = "TEXT_ONLY: REFERENCES_NOT_IMPLEMENTED";

	private StrategySelector() { }

	/// Choose the strategy and stage list for the plan. {@code caps} may be null (unknown family).
	/// Degradations are appended to {@code out} (which may be null).
	public static Strategy select(RenderPlan plan, RendererCapabilities caps, boolean forced, List<Degradation> out) {
		List<String> stages = stagesFor(plan);
		if(forced) {
			return new Strategy(PbStrategyEnumType.TEXT_ONLY, stages, REASON_FORCED);
		}
		List<RenderPlan.Actor> referenced = new ArrayList<>();
		if(plan != null) {
			for(RenderPlan.Actor a : plan.getActors()) {
				if(a.isReferenced()) {
					referenced.add(a);
				}
			}
		}
		if(referenced.isEmpty()) {
			return new Strategy(PbStrategyEnumType.TEXT_ONLY, stages, REASON_NO_REFERENCED_ACTORS);
		}
		String reason;
		if(caps == null) {
			reason = REASON_UNKNOWN_FAMILY;
		}
		else if(!caps.isSupportsReferences()) {
			reason = REASON_NO_REFERENCE_SUPPORT_PREFIX + caps.getPattern() + REASON_NO_REFERENCE_SUPPORT_SUFFIX;
		}
		else {
			reason = REASON_REFERENCES_NOT_IMPLEMENTED;
		}
		if(out != null) {
			for(RenderPlan.Actor a : referenced) {
				String name = (a.getName() != null && !a.getName().isBlank() ? a.getName() : a.getActorId());
				out.add(new Degradation(Degradation.ACTOR_TEXT_ONLY,
					name + " rendered from description (reference rendering not available in this build)", a.getActorId(), RenderPlan.STAGE_BASE));
			}
		}
		return new Strategy(PbStrategyEnumType.TEXT_ONLY, stages, reason);
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
