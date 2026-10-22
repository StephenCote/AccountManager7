package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.olio.illustration.RenderPlan.Actor;
import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.olio.illustration.RenderPlan.Element;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;

/// The FLUX.2 prompt compiler (PictureBook V3 design §14, P4-7): natural-language sentences for an
/// instruction-following edit model, not SDXL tag soup. DB-free: it reads the plan and nothing else.
///
/// Sentences, in order: OPENING (the placement clause - names the reference images positionally when
/// the strategy is DIRECT_REFERENCE and the actor carries a reference, otherwise "Place the people
/// described below in ..." - wrapped around MAIN, the promptOverride else the location description),
/// one sentence per actor (foreground roles as "The first person is ...", named background as "In the
/// background, ...", extras and crowds as "Several ..."), elements grouped by importance as "With a;
/// b. ", STYLE once, then the fixed composition rules ported from SWUtil.buildFlux2ScenePrompt. LoRA
/// tags are appended outside the budget.
///
/// Reference wording is gated twice (architect MUST-FIX 1): on the plan's strategy being
/// DIRECT_REFERENCE and on the actor's own referenced flag. Under TEXT_ONLY every actor is text and no
/// ordinal is spoken, so the model is never told to copy an identity out of an image it was not given.
/// The reference-specific rules (identity fidelity, "do not draw the reference images") are emitted
/// only when at least one reference is spoken of, for the same reason.
///
/// Budget: the family's promptTokenBudget for the stage's model pick (512 for flux2). Over budget,
/// groups drop in the order ELEM_LOW, ELEM_MEDIUM, EXTRAS, ACTOR_BG detail (named background rows keep
/// the name only), then MAIN is cut at its last ". " boundaries (then words); each drop is one
/// PROMPT_TRUNCATED naming what was dropped. Foreground actors, ELEM_HIGH, STYLE and the rules are never
/// dropped. raw intent: the override verbatim plus LoRAs, no style and no rules.
public final class Flux2Compiler implements PromptCompiler {

	public static final String GROUP_ACTOR_BG = "ACTOR_BG";
	public static final String GROUP_EXTRAS = "EXTRAS";
	public static final String GROUP_ELEM_MEDIUM = "ELEM_MEDIUM";
	public static final String GROUP_ELEM_LOW = "ELEM_LOW";

	public static final String MAIN_CUT = SdxlCompiler.MAIN_CUT;
	public static final String OVER_BUDGET_UNCUT = SdxlCompiler.OVER_BUDGET_UNCUT;

	/// The composition rules, verbatim from SWUtil.buildFlux2ScenePrompt. RULE_NO_REFERENCE_OBJECTS keeps
	/// its plain ASCII hyphen on purpose: the string is literal model input and a U+2014 tokenizes as junk
	/// (KI-68). Nothing may be added to that list that also appears in a style clause - see SWUtil.
	public static final String RULE_IDENTITY = "Preserve facial identity, hair, and clothing precisely. ";
	public static final String RULE_COHERENCE = "Matching lighting, scale, and perspective across the whole image. ";
	public static final String RULE_NO_EXTRA_PEOPLE = "No extra people. ";
	public static final String RULE_NO_REFERENCE_OBJECTS = "Do not draw the reference images themselves - no poster, screen, mirror, "
		+ "billboard, framed picture or character sheet anywhere in the scene. ";
	public static final String RULE_SINGLE_SCENE = "A single continuous scene, no panels, no split screen, no collage. ";

	private static final String SENTENCE_END = ". ";
	private static final String SEP = CompilerSupport.SEP;

	@Override
	public String family() {
		return RendererCapabilities.PATTERN_FLUX2;
	}

	@Override
	public CompiledPrompt compile(RenderPlan plan, String stage, List<Degradation> out) {
		if(plan == null) {
			return new CompiledPrompt("", "", 0);
		}
		String stg = (stage == null ? RenderPlan.STAGE_BASE : stage);
		if(!RenderPlan.STAGE_BASE.equals(stg)) {
			CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
			if(base != null) {
				return base;
			}
		}

		RenderPlan.Intent intent = plan.getIntent();
		boolean raw = intent != null && intent.isRaw();
		String override = clean(intent == null ? null : intent.getPromptOverride());
		List<String> loras = CompilerSupport.loras(plan.getConfig());
		String negative = CompilerSupport.negative(plan.getStyle(), plan.getConfig());

		if(raw && override != null) {
			return new CompiledPrompt(SDUtil.appendLoraTags(override, loras), negative, PromptBudget.estimateTokens(override));
		}

		RenderPlan.Location location = plan.getLocation();
		String main = override != null ? override : clean(location == null ? null : location.getDescription());

		boolean direct = plan.getStrategy() != null && plan.getStrategy().getName() == PbStrategyEnumType.DIRECT_REFERENCE;
		List<Actor> referencedOrdered = new ArrayList<>();
		if(direct) {
			for(Actor a : plan.getActors()) {
				if(a.isReferenced()) {
					referencedOrdered.add(a);
				}
			}
			referencedOrdered.sort((a, b) -> Integer.compare(a.getPriority(), b.getPriority()));
		}
		Map<Actor, Integer> refOrdinal = new LinkedHashMap<>();
		int k = 0;
		for(Actor a : referencedOrdered) {
			refOrdinal.put(a, ++k);
		}
		boolean hasLocationRef = direct && location != null && location.getRenderArtifactId() != null;
		int locationOrdinal = hasLocationRef ? k + 1 : 0;

		List<String> fg = new ArrayList<>();
		List<String> bgFull = new ArrayList<>();
		List<String> bgNames = new ArrayList<>();
		List<String> bgNameList = new ArrayList<>();
		List<String> extras = new ArrayList<>();
		List<String> extraNames = new ArrayList<>();
		int person = 0;
		for(Actor a : plan.getActors()) {
			String name = clean(a.getName());
			String text = clean(a.getDescription());
			String role = a.getRole();
			boolean extra = (RenderPlan.ROLE_EXTRA.equals(role) || RenderPlan.ROLE_CROWD.equals(role)) && a.getCharacterId() == null;
			if(a.isForeground()) {
				String body = foregroundBody(a, name, text);
				if(body == null) {
					continue;
				}
				person++;
				Integer ord = refOrdinal.get(a);
				fg.add("The " + CompilerSupport.ordinal(person) + " person" + (ord != null ? " (the " + CompilerSupport.ordinal(ord) + " reference image)" : "") + " is " + body + SENTENCE_END);
			}
			else if(extra) {
				String body = CompilerSupport.joinMeaningful(name, text);
				if(body == null) {
					continue;
				}
				extras.add((startsWithDigit(body) ? body : "Several " + body) + SENTENCE_END);
				extraNames.add(name != null ? name : text);
			}
			else {
				/// NAMED_BACKGROUND, the legacy BACKGROUND alias, and a named character staged as an extra.
				String subject = (name != null ? name : text);
				if(subject == null) {
					continue;
				}
				String detail = (name != null ? text : null);
				String outfit = clean(a.getOutfit() == null ? null : a.getOutfit().getText());
				String full = CompilerSupport.joinMeaningful(detail, outfit);
				/// A referenced actor in any background role keeps its ordinal, in the detail-dropped sentence too, so every
				/// reference the opening counts is bound to a person.
				int ord = ord(refOrdinal, a);
				String lead = "In the background" + (ord > 0 ? " (the " + CompilerSupport.ordinal(ord) + " reference image)" : "") + ", ";
				bgFull.add(lead + subject + (full != null ? " is " + full : "") + SENTENCE_END);
				bgNames.add(lead + subject + SENTENCE_END);
				bgNameList.add(subject);
			}
		}
		int n = fg.size() + bgFull.size() + extras.size();

		List<String> elemHigh = new ArrayList<>();
		List<String> elemMedium = new ArrayList<>();
		List<String> elemLow = new ArrayList<>();
		List<String> elemMediumNames = new ArrayList<>();
		List<String> elemLowNames = new ArrayList<>();
		for(Element e : plan.getElements()) {
			String name = clean(e.getName());
			String desc = clean(e.getDescription());
			String text = (desc != null ? desc : name);
			if(text == null) {
				continue;
			}
			if(RenderPlan.PRIORITY_HIGH.equalsIgnoreCase(e.getPriority())) {
				elemHigh.add(text);
			}
			else if(RenderPlan.PRIORITY_LOW.equalsIgnoreCase(e.getPriority())) {
				elemLow.add(text);
				elemLowNames.add(name != null ? name : desc);
			}
			else {
				elemMedium.add(text);
				elemMediumNames.add(name != null ? name : desc);
			}
		}

		RenderPlan.Style style = plan.getStyle();
		String styleText = raw ? null : clean(style == null ? null : style.getDescription());
		if(styleText != null && main != null && main.toLowerCase().endsWith(styleText.toLowerCase())) {
			styleText = null;
		}
		String rules = raw ? null : rules(k > 0 || hasLocationRef, extras.isEmpty());

		RenderPlan.ModelPick pick = plan.pickForStage(stg);
		RendererCapabilities caps = (pick == null ? null : RendererCapabilities.forModel(pick.getModel()));
		int budget = PromptBudget.budgetFor(caps);

		boolean keepLow = true;
		boolean keepMedium = true;
		boolean keepExtras = true;
		boolean bgDetail = true;

		String joined = assemble(opening(k, n, main, locationOrdinal), fg, bgFull, extras, elemHigh, elemMedium, elemLow, styleText, rules);
		int tokens = PromptBudget.estimateTokens(joined);

		if(tokens > budget && !elemLow.isEmpty()) {
			CompilerSupport.recordDrop(out, GROUP_ELEM_LOW + " dropped", elemLowNames, tokens - budget, budget, stg);
			keepLow = false;
			joined = assemble(opening(k, n, main, locationOrdinal), fg, bgFull, extras, elemHigh, elemMedium, null, styleText, rules);
			tokens = PromptBudget.estimateTokens(joined);
		}
		if(tokens > budget && !elemMedium.isEmpty()) {
			CompilerSupport.recordDrop(out, GROUP_ELEM_MEDIUM + " dropped", elemMediumNames, tokens - budget, budget, stg);
			keepMedium = false;
			joined = assemble(opening(k, n, main, locationOrdinal), fg, bgFull, extras, elemHigh, null, null, styleText, rules);
			tokens = PromptBudget.estimateTokens(joined);
		}
		if(tokens > budget && !extras.isEmpty()) {
			CompilerSupport.recordDrop(out, GROUP_EXTRAS + " dropped", extraNames, tokens - budget, budget, stg);
			keepExtras = false;
			n = fg.size() + bgFull.size();
			rules = raw ? null : rules(k > 0 || hasLocationRef, true);
			joined = assemble(opening(k, n, main, locationOrdinal), fg, bgFull, null, elemHigh, null, null, styleText, rules);
			tokens = PromptBudget.estimateTokens(joined);
		}
		if(tokens > budget && !bgFull.isEmpty() && !bgFull.equals(bgNames)) {
			CompilerSupport.recordDrop(out, GROUP_ACTOR_BG + " detail dropped", bgNameList, tokens - budget, budget, stg);
			bgDetail = false;
			joined = assemble(opening(k, n, main, locationOrdinal), fg, bgNames, null, elemHigh, null, null, styleText, rules);
			tokens = PromptBudget.estimateTokens(joined);
		}
		if(tokens > budget && main != null) {
			List<String> bg = bgDetail ? bgFull : bgNames;
			String others = assemble(opening(k, n, null, locationOrdinal), fg, bg, keepExtras ? extras : null, elemHigh, keepMedium ? elemMedium : null, keepLow ? elemLow : null, styleText, rules);
			int remaining = budget - PromptBudget.estimateTokens(others);
			int over = tokens - budget;
			String cut = main;
			while(PromptBudget.estimateTokens(cut) > remaining) {
				int idx = cut.lastIndexOf(SENTENCE_END);
				if(idx <= 0) {
					break;
				}
				cut = cut.substring(0, idx).trim();
			}
			if(remaining > 0) {
				while(PromptBudget.estimateTokens(cut) > remaining) {
					int ws = cut.lastIndexOf(' ');
					if(ws <= 0) {
						break;
					}
					cut = cut.substring(0, ws).trim();
				}
			}
			if(!cut.equals(main)) {
				CompilerSupport.recordDrop(out, MAIN_CUT, null, over, budget, stg);
				main = cut;
				joined = assemble(opening(k, n, main, locationOrdinal), fg, bg, keepExtras ? extras : null, elemHigh, keepMedium ? elemMedium : null, keepLow ? elemLow : null, styleText, rules);
				tokens = PromptBudget.estimateTokens(joined);
			}
		}
		if(tokens > budget) {
			CompilerSupport.recordDrop(out, OVER_BUDGET_UNCUT, null, tokens - budget, budget, stg);
		}

		String prompt = SDUtil.appendLoraTags(joined, loras);
		return new CompiledPrompt(prompt, negative, tokens);
	}

	/// "<name>, <identity.text>[, <outfit.text>][, <pose>][, <expression>][, <action>][, facing <facing>]", or null
	/// when the actor carries no meaningful text at all.
	private static String foregroundBody(Actor a, String name, String text) {
		String body = CompilerSupport.joinMeaningful(name, text);
		if(body == null) {
			return null;
		}
		body = CompilerSupport.joinMeaningful(body, clean(a.getOutfit() == null ? null : a.getOutfit().getText()));
		body = CompilerSupport.joinMeaningful(body, clean(a.getPose()));
		body = CompilerSupport.joinMeaningful(body, clean(a.getExpression()));
		body = CompilerSupport.joinMeaningful(body, clean(a.getAction()));
		String facing = clean(a.getFacing());
		if(facing != null) {
			body = body + SEP + "facing " + facing;
		}
		return body;
	}

	/// The placement clause wrapped around MAIN. k = spoken actor references, n = actors listed below,
	/// locationOrdinal = the location reference's ordinal (0 when none). With no actors, MAIN alone is the
	/// scene sentence (or the environment reference when one is spoken of).
	static String opening(int k, int n, String main, int locationOrdinal) {
		StringBuilder p = new StringBuilder();
		if(n == 0) {
			if(locationOrdinal > 0) {
				p.append("The environment shown in the ").append(CompilerSupport.ordinal(locationOrdinal)).append(" reference image");
				if(main != null) {
					p.append(" (").append(main).append(")");
				}
				p.append(SENTENCE_END);
			}
			else if(main != null) {
				p.append(main).append(SENTENCE_END);
			}
			return p.toString();
		}
		boolean others = n > k;
		if(k == 0) {
			p.append("Place the ").append(n > 1 ? "people" : "person").append(" described below in ");
		}
		else if(k == 1) {
			p.append("Take the exact person and face from the ").append(CompilerSupport.ordinal(1)).append(" reference image and place ")
				.append(others ? "them, with the other people described below, in " : "them in ");
		}
		else {
			p.append("Combine the exact person and face from the ").append(CompilerSupport.ordinal(1))
				.append(" reference image with the exact person and face from the ").append(CompilerSupport.ordinal(2)).append(" reference image");
			for(int i = 3; i <= k; i++) {
				p.append(" and the ").append(CompilerSupport.ordinal(i)).append(" reference image");
			}
			p.append(". Place ").append(others ? "them all, with the other people described below, in " : "them together in ");
		}
		if(locationOrdinal > 0) {
			p.append("the environment shown in the ").append(CompilerSupport.ordinal(locationOrdinal)).append(" reference image");
			if(main != null) {
				p.append(" (").append(main).append(")");
			}
			p.append(SENTENCE_END);
		}
		else if(main != null) {
			p.append(main).append(SENTENCE_END);
		}
		else {
			p.append("a coherent shared setting").append(SENTENCE_END);
		}
		return p.toString();
	}

	/// The fixed rules. The identity-fidelity and no-reference-objects rules only make sense when a
	/// reference image is actually spoken of; "No extra people." only when no extras or crowds were asked for.
	static String rules(boolean anyReference, boolean noExtras) {
		StringBuilder r = new StringBuilder();
		if(anyReference) {
			r.append(RULE_IDENTITY);
		}
		r.append(RULE_COHERENCE);
		if(noExtras) {
			r.append(RULE_NO_EXTRA_PEOPLE);
		}
		if(anyReference) {
			r.append(RULE_NO_REFERENCE_OBJECTS);
		}
		r.append(RULE_SINGLE_SCENE);
		return r.toString();
	}

	static String assemble(String opening, List<String> fg, List<String> bg, List<String> extras, List<String> elemHigh, List<String> elemMedium, List<String> elemLow,
			String style, String rules) {
		StringBuilder p = new StringBuilder();
		if(opening != null) {
			p.append(opening);
		}
		for(String s : fg) {
			p.append(s);
		}
		for(String s : bg) {
			p.append(s);
		}
		if(extras != null) {
			for(String s : extras) {
				p.append(s);
			}
		}
		appendElements(p, elemHigh);
		appendElements(p, elemMedium);
		appendElements(p, elemLow);
		if(style != null) {
			p.append(style);
			if(!style.endsWith(".")) {
				p.append(".");
			}
			p.append(" ");
		}
		if(rules != null) {
			p.append(rules);
		}
		return p.toString().trim();
	}

	private static void appendElements(StringBuilder p, List<String> items) {
		if(items == null || items.isEmpty()) {
			return;
		}
		p.append("With ").append(String.join("; ", items)).append(SENTENCE_END);
	}

	private static int ord(Map<Actor, Integer> refOrdinal, Actor a) {
		Integer o = refOrdinal.get(a);
		return (o == null ? 0 : o);
	}

	private static boolean startsWithDigit(String s) {
		return s != null && !s.isEmpty() && Character.isDigit(s.charAt(0));
	}

	private static String clean(String s) {
		return CompilerSupport.clean(s);
	}
}
