package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.illustration.RenderPlan.Actor;
import org.cote.accountmanager.olio.illustration.RenderPlan.Degradation;
import org.cote.accountmanager.olio.illustration.RenderPlan.Element;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.util.DocumentUtil;

/// The SDXL prompt compiler (PictureBook V3 design §14, §38.1: Swarm parameters, not a Comfy graph).
///
/// Segments, in order, each kept only when meaningful after smart-quote normalisation:
/// MAIN (promptOverride, else the location description; never dropped, hard-cut last),
/// ACTOR_FG (name, description; never dropped), ACTOR_BG (name, description; drops to names),
/// ELEM_HIGH / ELEM_MEDIUM / ELEM_LOW, CAMERA, PALETTE, STYLE (the style description, exactly once,
/// skipped when intent.raw or when MAIN already ends with it), PHOTOREAL (fixed boilerplate when
/// style.photoreal and not raw). Joined with ", ", then LoRA tags appended outside the budget.
///
/// Budget: the family's promptTokenBudget for the stage's model pick. Over budget, groups are
/// dropped in the order ELEM_LOW, ELEM_MEDIUM, PALETTE, CAMERA, ACTOR_BG detail, each recorded as
/// one PROMPT_TRUNCATED degradation; if still over, MAIN is cut at its last ", " boundaries (then
/// words) down to the remaining budget, keeping at least its first segment. ELEM_HIGH, ACTOR_FG,
/// STYLE and PHOTOREAL are never dropped. The compiler emits no (term:weight) syntax of its own.
///
/// Negative: style exclusions then the config negativePrompt, comma-split, de-duplicated
/// case-insensitively, order kept - exclusions are applied raw or not.
public final class SdxlCompiler implements PromptCompiler {

	public static final String PHOTOREAL_BOILERPLATE = "photorealistic, natural skin texture, physically based lighting";

	public static final String GROUP_ACTOR_BG = "ACTOR_BG";
	public static final String GROUP_ELEM_MEDIUM = "ELEM_MEDIUM";
	public static final String GROUP_ELEM_LOW = "ELEM_LOW";
	public static final String GROUP_CAMERA = "CAMERA";
	public static final String GROUP_PALETTE = "PALETTE";

	public static final String MAIN_CUT = "main description cut";
	public static final String OVER_BUDGET_UNCUT = "prompt over budget after all drops; sent as is";

	private static final String SEP = ", ";

	@Override
	public String family() {
		return RendererCapabilities.PATTERN_SDXL;
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
		String main = override != null ? override : clean(plan.getLocation() == null ? null : plan.getLocation().getDescription());

		List<String> fg = new ArrayList<>();
		List<String> bgFull = new ArrayList<>();
		List<String> bgNames = new ArrayList<>();
		for(Actor a : plan.getActors()) {
			String name = clean(a.getName());
			String desc = clean(a.getDescription());
			String full = joinMeaningful(name, desc);
			if(full == null) {
				continue;
			}
			if(a.isForeground()) {
				fg.add(full);
			}
			else {
				bgFull.add(full);
				bgNames.add(name != null ? name : desc);
			}
		}

		List<String> elemHigh = new ArrayList<>();
		List<String> elemMedium = new ArrayList<>();
		List<String> elemLow = new ArrayList<>();
		for(Element e : plan.getElements()) {
			String text = joinMeaningful(clean(e.getName()), clean(e.getDescription()));
			if(text == null) {
				continue;
			}
			if(RenderPlan.PRIORITY_HIGH.equalsIgnoreCase(e.getPriority())) {
				elemHigh.add(text);
			}
			else if(RenderPlan.PRIORITY_LOW.equalsIgnoreCase(e.getPriority())) {
				elemLow.add(text);
			}
			else {
				elemMedium.add(text);
			}
		}

		String camera = clean(plan.getCamera() == null ? null : plan.getCamera().getDescription());
		RenderPlan.Style style = plan.getStyle();
		String palette = clean(style == null ? null : style.getPalette());
		String styleText = raw ? null : clean(style == null ? null : style.getDescription());
		if(styleText != null && main != null && main.toLowerCase().endsWith(styleText.toLowerCase())) {
			styleText = null;
		}
		String photoreal = (!raw && style != null && style.isPhotoreal()) ? PHOTOREAL_BOILERPLATE : null;

		RenderPlan.ModelPick pick = plan.pickForStage(stg);
		RendererCapabilities caps = (pick == null ? null : RendererCapabilities.forModel(pick.getModel()));
		int budget = PromptBudget.budgetFor(caps);

		boolean keepLow = true;
		boolean keepMedium = true;
		boolean keepPalette = true;
		boolean keepCamera = true;
		boolean bgDetail = true;

		String joined = assemble(main, fg, bgDetail ? bgFull : bgNames, elemHigh, keepMedium ? elemMedium : null, keepLow ? elemLow : null,
			keepCamera ? camera : null, keepPalette ? palette : null, styleText, photoreal);
		int tokens = PromptBudget.estimateTokens(joined);

		if(tokens > budget && !elemLow.isEmpty()) {
			record(out, GROUP_ELEM_LOW + " dropped", tokens - budget, budget, stg);
			keepLow = false;
			joined = assemble(main, fg, bgFull, elemHigh, elemMedium, null, camera, palette, styleText, photoreal);
			tokens = PromptBudget.estimateTokens(joined);
		}
		if(tokens > budget && !elemMedium.isEmpty()) {
			record(out, GROUP_ELEM_MEDIUM + " dropped", tokens - budget, budget, stg);
			keepMedium = false;
			joined = assemble(main, fg, bgFull, elemHigh, null, null, camera, palette, styleText, photoreal);
			tokens = PromptBudget.estimateTokens(joined);
		}
		if(tokens > budget && palette != null) {
			record(out, GROUP_PALETTE + " dropped", tokens - budget, budget, stg);
			keepPalette = false;
			joined = assemble(main, fg, bgFull, elemHigh, null, null, camera, null, styleText, photoreal);
			tokens = PromptBudget.estimateTokens(joined);
		}
		if(tokens > budget && camera != null) {
			record(out, GROUP_CAMERA + " dropped", tokens - budget, budget, stg);
			keepCamera = false;
			joined = assemble(main, fg, bgFull, elemHigh, null, null, null, null, styleText, photoreal);
			tokens = PromptBudget.estimateTokens(joined);
		}
		if(tokens > budget && !bgFull.isEmpty() && !bgFull.equals(bgNames)) {
			record(out, GROUP_ACTOR_BG + " detail dropped", tokens - budget, budget, stg);
			bgDetail = false;
			joined = assemble(main, fg, bgNames, elemHigh, null, null, null, null, styleText, photoreal);
			tokens = PromptBudget.estimateTokens(joined);
		}
		if(tokens > budget && main != null) {
			List<String> bg = bgDetail ? bgFull : bgNames;
			String others = assemble(null, fg, bg, elemHigh, keepMedium ? elemMedium : null, keepLow ? elemLow : null,
				keepCamera ? camera : null, keepPalette ? palette : null, styleText, photoreal);
			int remaining = budget - PromptBudget.estimateTokens(others) - (others.isEmpty() ? 0 : 1);
			int over = tokens - budget;
			String cut = main;
			while(PromptBudget.estimateTokens(cut) > remaining) {
				int idx = cut.lastIndexOf(SEP);
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
				record(out, MAIN_CUT, over, budget, stg);
				main = cut;
				joined = assemble(main, fg, bg, elemHigh, keepMedium ? elemMedium : null, keepLow ? elemLow : null,
					keepCamera ? camera : null, keepPalette ? palette : null, styleText, photoreal);
				tokens = PromptBudget.estimateTokens(joined);
			}
		}
		if(tokens > budget) {
			record(out, OVER_BUDGET_UNCUT, tokens - budget, budget, stg);
		}

		String prompt = SDUtil.appendLoraTags(joined, loras(plan.getConfig()));
		String negative = negative(style == null ? null : style.getExclusions(), configString(plan.getConfig(), "negativePrompt"));
		return new CompiledPrompt(prompt, negative, tokens);
	}

	static String assemble(String main, List<String> fg, List<String> bg, List<String> elemHigh, List<String> elemMedium, List<String> elemLow,
			String camera, String palette, String style, String photoreal) {
		List<String> parts = new ArrayList<>();
		if(main != null) {
			parts.add(main);
		}
		parts.addAll(fg);
		parts.addAll(bg);
		parts.addAll(elemHigh);
		if(elemMedium != null) {
			parts.addAll(elemMedium);
		}
		if(elemLow != null) {
			parts.addAll(elemLow);
		}
		if(camera != null) {
			parts.add(camera);
		}
		if(palette != null) {
			parts.add(palette);
		}
		if(style != null) {
			parts.add(style);
		}
		if(photoreal != null) {
			parts.add(photoreal);
		}
		return String.join(SEP, parts);
	}

	static String negative(String exclusions, String configNegative) {
		Map<String, String> seen = new LinkedHashMap<>();
		for(String src : new String[] {exclusions, configNegative}) {
			if(src == null) {
				continue;
			}
			for(String term : src.split(",")) {
				String t = clean(term);
				if(t == null) {
					continue;
				}
				seen.putIfAbsent(t.toLowerCase(), t);
			}
		}
		return String.join(SEP, seen.values());
	}

	/// Smart-quote normalised and trimmed, or null when not meaningful (null, blank, "null", "n/a", ...).
	static String clean(String s) {
		if(s == null) {
			return null;
		}
		String t = DocumentUtil.replaceSmartQuotes(s).trim();
		return NarrativeUtil.isMeaningful(t) ? t : null;
	}

	private static String joinMeaningful(String a, String b) {
		if(a == null) {
			return b;
		}
		if(b == null) {
			return a;
		}
		return a + SEP + b;
	}

	private static void record(List<Degradation> out, String what, int over, int budget, String stage) {
		if(out == null) {
			return;
		}
		out.add(new Degradation(Degradation.PROMPT_TRUNCATED, what + " (" + over + " tokens over budget " + budget + ")", null, stage));
	}

	private static List<String> loras(Map<String, Object> config) {
		List<String> out = new ArrayList<>();
		if(config == null) {
			return out;
		}
		Object o = config.get("loras");
		if(o instanceof List) {
			for(Object e : (List<?>) o) {
				if(e != null && !String.valueOf(e).isBlank()) {
					out.add(String.valueOf(e));
				}
			}
		}
		return out;
	}

	private static String configString(Map<String, Object> config, String key) {
		if(config == null) {
			return null;
		}
		Object o = config.get(key);
		return (o == null ? null : String.valueOf(o));
	}
}
