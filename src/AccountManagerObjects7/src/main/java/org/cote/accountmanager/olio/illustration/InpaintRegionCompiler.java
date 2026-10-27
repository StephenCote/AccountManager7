package org.cote.accountmanager.olio.illustration;

import java.util.List;

import org.cote.accountmanager.olio.illustration.RenderPlan.Actor;
import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.olio.sd.SDUtil;

/// The ACTOR_REGION prompt compiler (PictureBook V3 design §14, P5-2): one actor, region-local. The
/// pass sends exactly one reference (the actor's own portrait) and a mask over the actor's box, so the
/// prompt speaks of "the reference image" in the singular and says nothing about the rest of the cast
/// or the setting - the surrounding scene is already painted and is only to be matched. Shape after
/// the P5-1 hand-written prompt (TestP51GraphLive.inpaintPrompt), the one that measured 54.2 s.
///
/// Sentences, in order: "The person in the reference image is <name>, <identity>, <outfit>. ",
/// "<name> is <pose>, <expression>, <action>, facing <facing>. " when any of those is meaningful,
/// STYLE once, then the fixed rules: identity fidelity (Flux2Compiler.RULE_IDENTITY), region coherence,
/// no extra people, no reference object, single scene. LoRA tags are appended outside the budget. The
/// intent's raw/override is a scene-level instruction and is not consulted here.
///
/// Budget: the family's promptTokenBudget for the plan's ACTOR_REGION pick. Nothing is droppable in a
/// one-actor prompt, so over budget is recorded as OVER_BUDGET_UNCUT against the actor and sent uncut.
public final class InpaintRegionCompiler {

	public static final String RULE_REGION_COHERENCE = "Matching lighting, scale, and perspective with the surrounding scene. ";
	public static final String RULE_NO_REFERENCE_OBJECT = "Do not draw the reference image itself - no poster, screen, mirror or framed picture. ";
	public static final String OVER_BUDGET_UNCUT = SdxlCompiler.OVER_BUDGET_UNCUT;

	private static final String SENTENCE_END = ". ";
	private static final String SEP = CompilerSupport.SEP;

	/// Compile the region pass for one actor. Degradations carry the actor's id and the ACTOR_REGION stage.
	public CompiledPrompt compile(RenderPlan plan, Actor actor, List<Degradation> out) {
		if(plan == null || actor == null) {
			return new CompiledPrompt("", "", 0);
		}
		List<String> loras = CompilerSupport.loras(plan.getConfig());
		String negative = CompilerSupport.negative(plan.getStyle(), plan.getConfig());

		String name = clean(actor.getName());
		String identity = clean(actor.getIdentity() == null ? null : actor.getIdentity().getText());
		String outfit = clean(actor.getOutfit() == null ? null : actor.getOutfit().getText());
		String who = CompilerSupport.joinMeaningful(CompilerSupport.joinMeaningful(name, identity), outfit);

		StringBuilder p = new StringBuilder();
		p.append("The person in the reference image");
		if(who != null) {
			p.append(" is ").append(who);
		}
		p.append(SENTENCE_END);

		String doing = CompilerSupport.joinMeaningful(clean(actor.getPose()), clean(actor.getExpression()));
		doing = CompilerSupport.joinMeaningful(doing, clean(actor.getAction()));
		String facing = clean(actor.getFacing());
		if(facing != null) {
			doing = (doing == null ? "" : doing + SEP) + "facing " + facing;
		}
		if(doing != null) {
			p.append(name != null ? name : "The same person").append(" is ").append(doing).append(SENTENCE_END);
		}

		RenderPlan.Style style = plan.getStyle();
		String styleText = clean(style == null ? null : style.getDescription());
		if(styleText != null) {
			p.append(styleText);
			if(!styleText.endsWith(".")) {
				p.append(".");
			}
			p.append(" ");
		}
		p.append(Flux2Compiler.RULE_IDENTITY).append(RULE_REGION_COHERENCE).append(Flux2Compiler.RULE_NO_EXTRA_PEOPLE)
			.append(RULE_NO_REFERENCE_OBJECT).append(Flux2Compiler.RULE_SINGLE_SCENE);

		String joined = p.toString().trim();
		int tokens = PromptBudget.estimateTokens(joined);
		RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_ACTOR_REGION);
		int budget = PromptBudget.budgetFor(pick == null ? null : RendererCapabilities.forModel(pick.getModel()));
		if(tokens > budget && out != null) {
			out.add(new Degradation(Degradation.PROMPT_TRUNCATED, OVER_BUDGET_UNCUT + " (" + (tokens - budget) + " tokens over budget " + budget + ")",
				actor.getActorId(), RenderPlan.STAGE_ACTOR_REGION));
		}
		return new CompiledPrompt(SDUtil.appendLoraTags(joined, loras), negative, tokens);
	}

	private static String clean(String s) {
		return CompilerSupport.clean(s);
	}
}
