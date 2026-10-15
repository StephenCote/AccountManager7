package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.illustration.CanvasResolver;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.SdxlCompiler;
import org.cote.accountmanager.olio.illustration.StrategySelector;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.util.AttributeUtil;

/**
 * Builds the immutable {@link RenderPlan} for one scene (PictureBook V3 design §12-§15, §18): style
 * chain -> effective config snapshot -> model policy -> preset/canvas -> actors -> strategy -> compiled
 * prompt per stage. The plan is a READ: nothing here writes a record. Persisting a plan as a node
 * artifact ({@link #persistPlanArtifact}) is the caller's explicit choice (the live render test and the
 * P2-5 executor); the plan-inspector route never persists.
 * <p>
 * Phase 2 seams, stated per §5 of the tracking doc: {@code olio.pb.scene.characters} has no writer yet,
 * so {@code actors} is empty on every current scene; the location description is the scene's own text
 * ({@code sdPrompt} -> setting/action/mood -> summary -> title), not a location record; a scene with no
 * text at all yields an empty compiled prompt, which the P2-5 executor refuses before rendering.
 */
public final class PbRenderPlanUtil {

	private static final Logger logger = LogManager.getLogger(PbRenderPlanUtil.class);

	private PbRenderPlanUtil() {}

	/// Per-request choices layered over the book's style (design §12 intent, §18 preset).
	public static final class PlanOptions {
		public static final PlanOptions NONE = new PlanOptions(null, false, null, null);

		private final String promptOverride;
		private final boolean raw;
		private final PbQualityPresetEnumType presetOverride;
		private final String modelOverride;

		public PlanOptions(String promptOverride, boolean raw, PbQualityPresetEnumType presetOverride, String modelOverride) {
			this.promptOverride = promptOverride;
			this.raw = raw;
			this.presetOverride = presetOverride;
			this.modelOverride = modelOverride;
		}

		public String getPromptOverride() { return promptOverride; }
		public boolean isRaw() { return raw; }
		public PbQualityPresetEnumType getPresetOverride() { return presetOverride; }
		public String getModelOverride() { return modelOverride; }
	}

	/**
	 * The plan for {@code scene} of {@code book} as {@code user} sees it. 401 without a principal, 400
	 * without a book or scene, 409 when the style chain cannot be read completely (same refusal as the
	 * node executor); every other shortfall is a degradation on the plan, never an exception (§19).
	 */
	public static RenderPlan buildScenePlan(BaseRecord user, BaseRecord book, BaseRecord scene, PlanOptions options,
			SDAPIEnumType apiType, String sdServer) {
		return buildScenePlan(user, book, scene, null, options, apiType, sdServer);
	}

	/**
	 * {@link #buildScenePlan(BaseRecord, BaseRecord, BaseRecord, PlanOptions, SDAPIEnumType, String)} for an
	 * attempt on {@code node} (P2-5): the node's sparse {@code configOverride} is the effective config's node
	 * tier ({@code PbConfigUtil.resolveEffectiveConfig(book, node, ...)}) and, when {@code options} names no
	 * model, its checkpoint is the model override ({@code PbModelPolicy.nodeOverride}) - the same precedence
	 * the LANDSCAPE executor applies, so the plan the executor renders from is the plan the node would get.
	 * A null node is the scene preview: book tiers only, exactly as before.
	 */
	public static RenderPlan buildScenePlan(BaseRecord user, BaseRecord book, BaseRecord scene, BaseRecord node,
			PlanOptions options, SDAPIEnumType apiType, String sdServer) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		if(book == null) throw new PictureBookException(400, "A book is required");
		if(scene == null) throw new PictureBookException(400, "A scene is required");
		PlanOptions opts = (options != null ? options : PlanOptions.NONE);
		String sceneObjectId = scene.get(FieldNames.FIELD_OBJECT_ID);

		PbStyleResolver.EffectiveStyle style = PbStyleResolver.resolve(user, book);
		if(!style.isComplete()) {
			throw new PictureBookException(409, "Style chain of book " + book.get(OlioFieldNames.FIELD_PB_SLUG)
				+ (style.cycleDetected() ? " cycles" : " is not readable at " + style.truncatedModel() + " " + style.truncatedAt())
				+ "; scene " + sceneObjectId + " cannot be planned against a partial style");
		}

		BaseRecord effective = PbConfigUtil.resolveEffectiveConfig(book, node, false, null, style);
		/// Snapshot BEFORE the model policy writes its pick: the snapshot is the config the style chain
		/// produced, the pick is reported separately under models (§12).
		Map<String, Object> snapshot = PbConfigUtil.configSnapshot(effective);
		BaseRecord styleTier = PbConfigUtil.styleTierConfig(book, false, style);
		String modelOverride = (opts.getModelOverride() != null ? opts.getModelOverride()
			: (node != null ? PbModelPolicy.nodeOverride(node, PbModelTaskEnumType.LOCATION) : null));

		List<RenderPlan.Degradation> degradations = new ArrayList<>();
		ModelResolver.Resolution locRes = PbModelPolicy.applyForStyle(style, styleTier, PbModelTaskEnumType.LOCATION,
			effective, modelOverride, apiType, sdServer, false);
		ModelResolver.Resolution directRes = PbModelPolicy.applyForStyle(style, styleTier, PbModelTaskEnumType.SCENE_DIRECT,
			null, null, apiType, sdServer, false);
		RenderPlan.ModelPick locPick = ModelResolver.pickOf(locRes);
		RenderPlan.ModelPick directPick = ModelResolver.pickOf(directRes);
		ModelResolver.addDegradation(locRes, degradations);

		QualityPresets.Preset preset = QualityPresets.resolve(
			opts.getPresetOverride() != null && opts.getPresetOverride() != PbQualityPresetEnumType.UNKNOWN
				? opts.getPresetOverride() : style.qualityPreset());
		RendererCapabilities caps = (locRes.getCapabilities() != null ? locRes.getCapabilities() : RendererCapabilities.forModel(locRes.getModel()));
		int steps = (caps != null ? caps.recommendedSteps(preset.getStepsMode()) : intOf(effective, OlioFieldNames.FIELD_SD_STEPS, 20));
		RenderPlan.PresetValues presetValues = new RenderPlan.PresetValues(preset.getName().name(), steps, preset.getVariants(),
			preset.isUpscale(), preset.getQa());

		RenderPlan.Canvas canvas = CanvasResolver.resolve(style.pageFormat(),
			intOf(style.value(OlioFieldNames.FIELD_PB_CUSTOM_WIDTH), 0), intOf(style.value(OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT), 0),
			preset.getResolutionScale(), intOf(effective, "width", 0), intOf(effective, "height", 0));

		RenderPlan.Style planStyle = new RenderPlan.Style(
			style.chain().isEmpty() ? null : (String) style.chain().get(0).get(FieldNames.FIELD_OBJECT_ID),
			PbConfigUtil.sha256Hex(style.canonicalStyle()),
			str(style.value(FieldNames.FIELD_DESCRIPTION)),
			str(style.value(OlioFieldNames.FIELD_PB_EXCLUSIONS)),
			str(style.value(OlioFieldNames.FIELD_PB_PALETTE)),
			Boolean.TRUE.equals(style.value(OlioFieldNames.FIELD_PB_PHOTOREAL)),
			Collections.emptyList());

		RenderPlan.Builder b = RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(canvas)
			.preset(presetValues)
			.style(planStyle)
			.config(snapshot)
			.location(new RenderPlan.Location(sceneObjectId, locationText(scene)))
			.intent(new RenderPlan.Intent(opts.getPromptOverride(), opts.isRaw()))
			.model(locPick)
			.model(directPick)
			.actors(sceneActors(user, scene));
		for(RenderPlan.Degradation d : degradations) {
			b.degradation(d);
		}
		RenderPlan plan = b.build();

		List<RenderPlan.Degradation> out = new ArrayList<>();
		RenderPlan.Strategy strategy = StrategySelector.select(plan, caps, false, out);
		plan = plan.withStrategy(strategy, out);

		out = new ArrayList<>();
		SdxlCompiler compiler = new SdxlCompiler();
		Map<String, CompiledPrompt> compiled = new LinkedHashMap<>();
		for(String stage : strategy.getStages()) {
			compiled.put(stage, compiler.compile(plan, stage, out));
		}
		return plan.withCompiled(compiled, out);
	}

	/// Persist {@code plan} as the node's {@code renderPlan} JSON artifact, as {@code user} through PBAC.
	public static BaseRecord persistPlanArtifact(BaseRecord user, BaseRecord node, String groupPath, RenderPlan plan) {
		if(plan == null) throw new PictureBookException(400, "A plan is required");
		return PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_RENDER_PLAN, PbArtifactTypeEnumType.JSON,
			groupPath, null, plan.toJson(), null, null);
	}

	/// The plan carried by a {@code renderPlan} artifact, or null when the artifact carries none.
	public static RenderPlan readPlanArtifact(BaseRecord artifact) {
		if(artifact == null || !artifact.hasField(OlioFieldNames.FIELD_PB_ARTIFACT_TEXT)) return null;
		String text = artifact.get(OlioFieldNames.FIELD_PB_ARTIFACT_TEXT);
		if(text == null || text.isBlank()) return null;
		return RenderPlan.fromJson(text);
	}

	// ─── helpers ─────────────────────────────────────────────────────────────────

	/// The scene's own text, nearest-to-a-prompt first. Empty is allowed: the plan is a read.
	static String locationText(BaseRecord scene) {
		String sdPrompt = strField(scene, OlioFieldNames.FIELD_CB_SD_PROMPT);
		if(NarrativeUtil.isMeaningful(sdPrompt)) return sdPrompt.trim();
		List<String> parts = new ArrayList<>();
		for(String f : new String[] { OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD }) {
			String v = strField(scene, f);
			if(NarrativeUtil.isMeaningful(v)) parts.add(v.trim());
		}
		if(!parts.isEmpty()) return joinSentences(parts);
		String summary = strField(scene, "summary");
		if(NarrativeUtil.isMeaningful(summary)) return summary.trim();
		String title = strField(scene, OlioFieldNames.FIELD_PB_TITLE);
		return (NarrativeUtil.isMeaningful(title) ? title.trim() : "");
	}

	/// Scene text fields are free prose that may or may not already end in punctuation; a trailing
	/// clause separator (, :) is kept as the join rather than doubled with a period.
	static String joinSentences(List<String> parts) {
		StringBuilder sb = new StringBuilder();
		for(String p : parts) {
			if(sb.length() > 0) {
				char last = sb.charAt(sb.length() - 1);
				sb.append(last == '.' || last == '!' || last == '?' || last == ';' || last == ',' || last == ':' ? " " : ". ");
			}
			sb.append(p);
		}
		return sb.toString();
	}

	/// The scene's cast as FOREGROUND, text-only actors. Mirrors PbCastUtil.listCastMembers: findMembers
	/// resolves the participantModel override, the ids come from a scene the caller already read, and
	/// the IN query runs as the caller. Empty on any error - a roster read never aborts a plan.
	private static List<RenderPlan.Actor> sceneActors(BaseRecord user, BaseRecord scene) {
		try {
			List<BaseRecord> parts = IOSystem.getActiveContext().getMemberUtil().findMembers(scene,
				OlioFieldNames.FIELD_PB_CHARACTERS, OlioModelNames.MODEL_CHAR_PERSON, 0L);
			if(parts == null || parts.isEmpty()) return Collections.emptyList();
			List<String> ids = new ArrayList<>();
			for(BaseRecord part : parts) {
				Object pid = part.get(FieldNames.FIELD_PARTICIPANT_ID);
				if(pid instanceof Number) ids.add(Long.toString(((Number) pid).longValue()));
			}
			if(ids.isEmpty()) return Collections.emptyList();
			long organizationId = ((Number) scene.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
			q.field(FieldNames.FIELD_ID, ComparatorEnumType.IN, String.join(",", ids));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
				FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, OlioFieldNames.FIELD_NARRATIVE, FieldNames.FIELD_ATTRIBUTES });
			q.setCache(false);
			BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
			if(recs == null) return Collections.emptyList();
			List<RenderPlan.Actor> actors = new ArrayList<>();
			for(BaseRecord cp : recs) {
				actors.add(new RenderPlan.Actor(cp.get(FieldNames.FIELD_OBJECT_ID), cp.get(FieldNames.FIELD_NAME),
					RenderPlan.ROLE_FOREGROUND, actorDescription(cp), false));
			}
			return actors;
		}
		catch(Exception e) {
			logger.warn("Failed to list characters of scene " + scene.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
			return Collections.emptyList();
		}
	}

	/// pbDescription attribute first, then the narrative's physical (+ outfit) description. The
	/// narrative's own sdPrompt is deliberately NOT used: it is a portrait prompt, not a scene actor line.
	private static String actorDescription(BaseRecord cp) {
		try {
			String d = AttributeUtil.getAttributeValue(cp, PictureBookUtil.ATTR_DESCRIPTION, (String) null);
			if(NarrativeUtil.isMeaningful(d)) return d.trim();
		}
		catch(Exception e) {
			logger.warn("Could not read " + PictureBookUtil.ATTR_DESCRIPTION + " of " + cp.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
		BaseRecord narrative = (cp.hasField(OlioFieldNames.FIELD_NARRATIVE) ? cp.get(OlioFieldNames.FIELD_NARRATIVE) : null);
		if(narrative == null) return null;
		try {
			IOSystem.getActiveContext().getReader().populate(narrative, new String[] { "physicalDescription", "outfitDescription" });
		}
		catch(Exception e) {
			logger.warn("Could not populate narrative of " + cp.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
		List<String> parts = new ArrayList<>();
		for(String f : new String[] { "physicalDescription", "outfitDescription" }) {
			String v = strField(narrative, f);
			if(NarrativeUtil.isMeaningful(v)) parts.add(v.trim());
		}
		return (parts.isEmpty() ? null : String.join(". ", parts));
	}

	private static String strField(BaseRecord rec, String field) {
		if(rec == null || !rec.hasField(field)) return null;
		Object v = rec.get(field);
		return (v == null ? null : v.toString());
	}

	private static String str(Object v) {
		return (v == null ? null : v.toString());
	}

	private static int intOf(Object v, int def) {
		return (v instanceof Number ? ((Number) v).intValue() : def);
	}

	private static int intOf(BaseRecord rec, String field, int def) {
		if(rec == null || !rec.hasField(field)) return def;
		return intOf(rec.get(field), def);
	}
}
