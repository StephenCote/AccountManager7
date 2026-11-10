package org.cote.accountmanager.olio.picturebook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.PromptBudget;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.util.JSONUtil;

/// ChapBook pages are straight image generation (Stephen, 2026-11-10): on the V3 plan path a ChapBook render must
/// honour its effective olio.sd.config - book config, then the page's configOverride (mirrored onto the page node,
/// PbBookUtil.mirrorSceneOntoNode) - the way the v1 page path (ChapBookUtil.renderResolvedSceneV1) always did,
/// instead of the PictureBook plan rules (preset-governed steps D6, BASE only, look from olio.pb.style only):
/// <ol>
/// <li><b>steps</b> - the config's steps when set (> 0), else the preset's;</li>
/// <li><b>hires / refiner</b> - the BASE request carries {@code hires} from the config, so
///     SwarmRendererAdapter.toSwarm sends the config's refiner block (model, steps, cfg, method, upscale...);</li>
/// <li><b>style</b> - the config's style clause (SDUtil.applyConfigStyle after fillStyleDefaults, exactly v1) is
///     appended to the BASE prompt, unless the book's olio.pb.style carries a description (that is then the
///     style, compiled already - never two styles);</li>
/// <li><b>seed / negative</b> - the config's seed when >= 0 (v1: SDUtil.createImage), else random; the default
///     negative prompt when nothing compiled one (v1: NarrativeUtil.getDefaultNegativePrompt).</li>
/// </ol>
/// Scope: a ChapBook book AND a TEXT_ONLY plan. PictureBook is untouched, and so is any ChapBook plan that
/// references characters (a cover with a cast renders BASE on the FLUX.2 SCENE_DIRECT pick, where the config's
/// SDXL step count and refiner would be wrong - the 20-minute timeout of 2026-11-05).
public final class ChapBookStraightGen {

	public static final Logger logger = LogManager.getLogger(ChapBookStraightGen.class);

	private ChapBookStraightGen() { }

	/// A ChapBook book rendering a TEXT_ONLY plan.
	public static boolean applies(BaseRecord book, RenderPlan plan) {
		return book != null && PbNodeExecutor.isChapBook(book) && isTextOnly(plan);
	}

	static boolean isTextOnly(RenderPlan plan) {
		return plan != null && plan.getStrategy() != null && plan.getStrategy().getName() == PbStrategyEnumType.TEXT_ONLY;
	}

	/// The plan with steps 1 and the BASE prompt's style 3 / negative 4 taken from the effective config. Applied
	/// before the plan artifact is persisted, so the artifact records what is actually sent. Returns the plan
	/// unchanged when it does not apply.
	public static RenderPlan apply(BaseRecord book, RenderPlan plan) {
		if(!applies(book, plan)) {
			return plan;
		}
		return applyToTextOnly(plan);
	}

	/// The transform itself, for a TEXT_ONLY plan (package-visible for the offline test).
	static RenderPlan applyToTextOnly(RenderPlan plan) {
		if(!isTextOnly(plan)) {
			return plan;
		}
		Map<String, Object> cfg = plan.getConfig();
		RenderPlan out = plan;

		int steps = configSteps(cfg);
		RenderPlan.PresetValues p = out.getPreset();
		if(steps > 0 && p != null && p.getSteps() != steps) {
			out = out.withPreset(new RenderPlan.PresetValues(p.getPreset(), steps, p.getVariants(), p.isUpscale(), p.getQa()));
		}

		CompiledPrompt base = (out.getCompiled() != null ? out.getCompiled().get(RenderPlan.STAGE_BASE) : null);
		if(base != null) {
			String prompt = base.getPrompt();
			if(!bookStyleDescribed(out)) {
				String styled = styledPrompt(prompt, cfg);
				if(styled != null) {
					prompt = styled;
				}
			}
			String negative = base.getNegative();
			if(negative == null || negative.isBlank()) {
				negative = NarrativeUtil.getDefaultNegativePrompt();
			}
			if(!prompt.equals(base.getPrompt()) || !negative.equals(base.getNegative())) {
				Map<String, CompiledPrompt> compiled = new LinkedHashMap<>(out.getCompiled());
				compiled.put(RenderPlan.STAGE_BASE, new CompiledPrompt(prompt, negative, PromptBudget.estimateTokens(prompt)));
				out = out.withCompiled(compiled, null);
			}
		}
		return out;
	}

	/// Whether the book's style chain supplies the style text (then the config's clause is not added).
	static boolean bookStyleDescribed(RenderPlan plan) {
		RenderPlan.Style s = plan.getStyle();
		return s != null && NarrativeUtil.isMeaningful(s.getDescription());
	}

	/// The prompt with the config's style clause appended (v1 order: fillStyleDefaults, then applyConfigStyle), or
	/// null when the config names no style.
	static String styledPrompt(String prompt, Map<String, Object> cfg) {
		Object style = (cfg != null ? cfg.get("style") : null);
		if(!(style instanceof String) || !NarrativeUtil.isMeaningful((String) style)) {
			return null;
		}
		BaseRecord rec = configRecord(cfg);
		if(rec == null) {
			return null;
		}
		SDUtil.fillStyleDefaults(rec);
		return SDUtil.applyConfigStyle(prompt, rec);
	}

	/// 2: hires as the config says (the adapter skips the refiner, with a warning, when no refinerModel is named).
	public static boolean hires(Map<String, Object> cfg) {
		return cfg != null && Boolean.TRUE.equals(cfg.get("hires"));
	}

	/// 4: the config's seed when >= 0, else StageRequest.SEED_RANDOM (v1 createImage's rule).
	public static int seed(Map<String, Object> cfg) {
		Object v = (cfg != null ? cfg.get("seed") : null);
		if(v instanceof Number) {
			long s = ((Number) v).longValue();
			if(s >= 0 && s <= Integer.MAX_VALUE) {
				return (int) s;
			}
		}
		return StageRequest.SEED_RANDOM;
	}

	static int configSteps(Map<String, Object> cfg) {
		Object v = (cfg != null ? cfg.get("steps") : null);
		return (v instanceof Number ? ((Number) v).intValue() : 0);
	}

	/// The snapshot map as an olio.sd.config record (the shape getSDConfigPrompt / fillStyleDefaults read).
	private static BaseRecord configRecord(Map<String, Object> cfg) {
		try {
			Map<String, Object> m = new LinkedHashMap<>(cfg);
			/// the deserializer's schema key ("schema"; FieldNames.FIELD_SCHEMA is "schemaData", a different field)
			m.put("schema", OlioModelNames.MODEL_SD_CONFIG);
			return PbArtifactUtil.parseSnapshot(JSONUtil.exportObject(m));
		}
		catch(Exception e) {
			logger.warn("ChapBook style clause skipped: the effective config could not be read as " + OlioModelNames.MODEL_SD_CONFIG + ": " + e.getMessage());
			return null;
		}
	}

	/// The configNotApplied entries a straight-gen render no longer earns: hires, the style clause (when it was
	/// applied) and the default negative are all honoured. Returns the list filtered.
	static List<String> dropHonoured(List<String> notApplied, RenderPlan plan) {
		boolean styleHonoured = !bookStyleDescribed(plan);
		notApplied.removeIf(e -> e.startsWith(PbNodeExecutor.NOT_APPLIED_HIRES)
			|| e.startsWith(PbNodeExecutor.NOT_APPLIED_DEFAULT_NEGATIVE)
			|| (styleHonoured && e.startsWith(PbNodeExecutor.NOT_APPLIED_STYLE_CLAUSE)));
		return notApplied;
	}
}
