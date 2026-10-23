package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.illustration.CanvasResolver;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.PromptCompilers;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.StrategySelector;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbReferencePolicyEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.util.AttributeUtil;

/**
 * Builds the immutable {@link RenderPlan} for one scene (PictureBook V3 design §12-§15, §18): style
 * chain -> effective config snapshot -> model policy -> preset/canvas -> actors -> strategy -> compiled
 * prompt per stage. The plan is a READ: nothing here writes a record. Persisting a plan as a node
 * artifact ({@link #persistPlanArtifact}) is the caller's explicit choice (the live render test and the
 * P2-5 executor); the plan-inspector route never persists.
 * <p>
 * Since P4-7 the plan's {@code actors} and {@code elements} come from the scene's SCENE-purpose
 * {@code olio.pb.composition} ({@link #sceneActors}, {@link #sceneElements}): a scene with no composition
 * plans with no actors and no degradation, and the plan never composes one. A scene with no text at all
 * yields an empty compiled prompt, which the P2-5 executor refuses before rendering. Since P3-5 the plan's {@code location} is the
 * scene's {@code olio.pb.location} / {@code locationVariant} when the scene names one
 * ({@link #resolveSceneLocation}): the record's identity, watched-field hash and approved render travel on
 * the plan and its {@code inputs}, and the setting text is the location's subject text followed by the
 * scene's own text ({@code sdPrompt} -> setting/action/mood -> summary -> title). A scene that names no
 * location keeps the Phase 2 shape: scene text only, no identity, no degradation. The executor does not
 * yet consume the carried render (Phase 4 composition); until then the plan reports it and the
 * {@code LOCATION_TEXT_ONLY} degradation marks the scenes that have none.
 */
public final class PbRenderPlanUtil {

	private static final Logger logger = LogManager.getLogger(PbRenderPlanUtil.class);

	/**
	 * Plan {@code inputs} key of the scene's {@code olio.pb.locationVariant} hash. The location itself keys on
	 * {@link PbPipelineUtil#ROLE_LOCATION}; the executor binds the variant under that same role at ordinal 1
	 * ({@code PbNodeExecutor.bindSceneLocation}), so this key is a plan-level name for binding
	 * ({@code location}, 1), not a binding role of its own.
	 */
	public static final String INPUT_LOCATION_VARIANT = "locationVariant";

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
	 * <p>
	 * Projection contract (P3-5, P3-6): the scene's {@code location} and {@code locationVariant} FKs are read
	 * off the record handed in, never re-fetched, so a caller that wants the located plan must project them
	 * ({@code PbBookUtil.sceneRequest()} does, and {@code PbServiceFacade.planScene} reads the scene with it;
	 * {@code PbNodeExecutor.executeRenderPlan} and {@code PbNodeExecutor.executeChapBookPage} both name the
	 * two FKs in their own projections, along with {@code organizationId}, which the resolver unboxes). A
	 * scene read without them plans exactly as one that names no location.
	 */
	public static RenderPlan buildScenePlan(BaseRecord user, BaseRecord book, BaseRecord scene, BaseRecord node,
			PlanOptions options, SDAPIEnumType apiType, String sdServer) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		if(book == null) throw new PictureBookException(400, "A book is required");
		if(scene == null) throw new PictureBookException(400, "A scene is required");
		PlanOptions opts = (options != null ? options : PlanOptions.NONE);
		String sceneObjectId = scene.get(FieldNames.FIELD_OBJECT_ID);

		Scaffold s = scaffold(user, book, node, opts, apiType, sdServer, "scene " + sceneObjectId);
		s.builder.purpose(RenderPlan.PURPOSE_SCENE);
		List<RenderPlan.Degradation> out = new ArrayList<>();
		/// Resolved before the location: a ChapBook page's composition carries the page prompt (P4-8)
		BaseRecord composition = findComposition(user, book, scene, out);
		String renderOid = resolveSceneLocation(user, book, scene, composition, s.builder);

		s.builder
			.actors(actorsOf(user, book, composition, s.caps, renderOid != null, out))
			.elements(elementsOf(user, composition, out));
		/// A SCENE composition's shot type / viewpoint / cameraText is the plan's camera (P4-10 A2); the compiler
		/// (SdxlCompiler) already consumes plan.camera, so nothing else changes when there is none.
		String camera = cameraText(composition);
		if(camera != null) {
			s.builder.camera(new RenderPlan.Camera(camera));
		}
		/// A SCENE composition's promptOverride is the intent; a CHAPBOOK_PAGE's is the page's own text and
		/// already went into the location clause above (P2-6), so it is not repeated as an intent.
		if(!NarrativeUtil.isMeaningful(opts.getPromptOverride()) && !isPageComposition(composition)) {
			String compOverride = strField(composition, OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE);
			if(NarrativeUtil.isMeaningful(compOverride)) {
				s.builder.intent(new RenderPlan.Intent(compOverride.trim(), opts.isRaw()));
			}
		}
		for(RenderPlan.Degradation d : out) {
			s.builder.degradation(d);
		}
		return finish(s);
	}

	/**
	 * The scene's setting as the plan carries it (V3 §12, §29; P3-5). A scene that names no location gets
	 * {@code Location(null, null, null, null, sceneText)}: no identity, no {@code inputs}, no degradation -
	 * plain text-only is not a shortfall. A scene that names one reads the location (and the variant when
	 * named) once, as {@code user}, and then:
	 * <ul>
	 * <li>{@code inputs.location} = the location's watched-field hash as read ({@code PbWatchedFields.refHashOf}
	 *     on the same record - never a second read); {@code inputs.locationVariant} likewise when a variant is
	 *     named. A record this user cannot read contributes a null hash, as the executor's binding does.</li>
	 * <li>{@code renderArtifactId} = the variant's {@code approvedRender} when a variant is named, else the
	 *     location's. The location's render is never a substitute for a variant's (D3): a scene at "the tavern
	 *     at night" with only the daytime tavern approved is text-only, and the message names the variant.
	 *     The pointer is taken as truth; the artifact's own review status is not re-read (§6.C 175 e).</li>
	 * <li>{@code description} = the location's subject text ({@link #locationSubjectText}: description, then
	 *     the variant's qualifiers) followed by the scene's own text ({@link #locationText}) when the subject
	 *     text is non-empty, else the scene's text alone. Composed, not replaced: the setting text is the
	 *     compiled prompt's MAIN clause, and dropping the scene's action/mood for a located scene would be a
	 *     silent loss (§30 row 4) until the Phase 4 composition model arrives.</li>
	 * <li>no render (none approved, or the record unreadable) → {@code LOCATION_TEXT_ONLY} on the BASE
	 *     stage; never an exception.</li>
	 * <li>the book's scope rule (P3-4, {@link PbLocationUtil#requireLocationOfBook}) is applied here as on
	 *     every pointing route: a location whose {@code world} is not the book's, or a variant whose
	 *     {@code location} is not the scene's location, is refused. Generic {@code POST /rest/model} can still
	 *     persist such a pointer (the FK is gated on READ only), so the plan treats the refused record as if
	 *     it were unreadable - no text composed, a null hash, no render carried - and the degradation message
	 *     says why instead of "no approved render". A refused location takes the named variant down with it;
	 *     a refused variant leaves the location's text and hash in place but carries no render (D3: the
	 *     location's render is never the variant's). {@code book} must carry its {@code world} FK
	 *     ({@code PbBookUtil.bookRequest()} does); a book that carries none has no locations, so every named
	 *     location is refused.</li>
	 * </ul>
	 * Returns the objectId of the render the plan carries, or null when the scene names no location or no
	 * render is carried - the actor resolver counts it against the renderer's reference slots (P4-7).
	 */
	static String resolveSceneLocation(BaseRecord user, BaseRecord book, BaseRecord scene, BaseRecord composition, RenderPlan.Builder builder) {
		String sceneText = locationText(scene, composition);
		String locOid = fkObjectId(scene, FieldNames.FIELD_LOCATION);
		if(locOid == null) {
			builder.location(new RenderPlan.Location(null, null, null, null, sceneText));
			return null;
		}
		String varOid = fkObjectId(scene, OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
		long orgId = ((Number) scene.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		BaseRecord loc = PbLocationUtil.readLocation(user, locOid, orgId);
		BaseRecord var = (varOid != null ? PbLocationUtil.readVariant(user, varOid, orgId) : null);

		String refusal = null;
		if(loc != null && !PbLocationUtil.sameWorld(book, loc)) {
			refusal = "Location " + nameOr(loc, locOid) + " is not in this book's world";
			loc = null;
			var = null;
		}
		else if(var != null && !locOid.equals(fkObjectId(var, FieldNames.FIELD_LOCATION))) {
			refusal = "Variant " + nameOr(var, varOid) + " is not a variant of location " + nameOr(loc, locOid);
			var = null;
		}

		String revisionHash = PbWatchedFields.refHashOf(loc);
		builder.input(PbPipelineUtil.ROLE_LOCATION, revisionHash);
		if(varOid != null) {
			builder.input(INPUT_LOCATION_VARIANT, PbWatchedFields.refHashOf(var));
		}

		BaseRecord renderHolder = (varOid != null ? var : loc);
		String renderOid = (renderHolder != null ? fkObjectId(renderHolder, OlioFieldNames.FIELD_PB_APPROVED_RENDER) : null);

		String subject = (loc != null ? locationSubjectText(loc, var) : "");
		String text = sceneText;
		if(!subject.isEmpty()) {
			List<String> parts = new ArrayList<>();
			parts.add(subject);
			if(!sceneText.isEmpty()) parts.add(sceneText);
			text = joinSentences(parts);
		}
		builder.location(new RenderPlan.Location(locOid, varOid, revisionHash, renderOid, text));

		if(renderOid == null) {
			String why;
			if(refusal != null) {
				why = refusal;
			}
			else if(varOid != null) {
				why = "No approved render for variant " + nameOr(var, varOid) + (var == null ? " (not readable)" : "");
			}
			else {
				why = "No approved render for location " + nameOr(loc, locOid) + (loc == null ? " (not readable)" : "");
			}
			/// Stage BASE, like MODEL_SUBSTITUTED: that is where the render would have been used (§12 init
			/// image / reference), even though the shortfall is known at plan time.
			builder.degradation(new RenderPlan.Degradation(RenderPlan.Degradation.LOCATION_TEXT_ONLY,
				why + "; setting described in text", null, RenderPlan.STAGE_BASE));
		}
		return renderOid;
	}

	/// The objectId of a projected FK, or null when the field is absent, null, or carries no objectId.
	private static String fkObjectId(BaseRecord rec, String field) {
		if(rec == null || !rec.hasField(field)) return null;
		BaseRecord fk = rec.get(field);
		return (fk != null ? fk.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	/// The record's name for a message, or its objectId when the name is not meaningful or the record is null.
	private static String nameOr(BaseRecord rec, String objectId) {
		String n = strField(rec, FieldNames.FIELD_NAME);
		return (NarrativeUtil.isMeaningful(n) ? n : objectId);
	}

	/**
	 * The plan for one {@code olio.pb.location} (optionally one of its {@code olio.pb.locationVariant}s) of
	 * {@code book} as {@code user} sees it (V3 §7, §7.1, P3-4). Same style chain, config tiers, model policy,
	 * preset and canvas as a scene plan - the location pick is the one a LOCATION node renders with - but
	 * purpose {@code location}, no actors (so the selector lands on TEXT_ONLY) and the subject text is the
	 * location's description followed by the variant's time-of-day / weather / season / viewpoint / extra.
	 * The {@code Location.locationId} is the location record's objectId, never a scene's. 400 when the
	 * location carries no description at all: a location is rendered from its identity, not from a scene.
	 */
	public static RenderPlan buildLocationPlan(BaseRecord user, BaseRecord book, BaseRecord location, BaseRecord variant,
			BaseRecord node, PlanOptions options, SDAPIEnumType apiType, String sdServer) {
		if(user == null) throw new PictureBookException(401, "No authenticated principal");
		if(book == null) throw new PictureBookException(400, "A book is required");
		if(location == null) throw new PictureBookException(400, "A location is required");
		PlanOptions opts = (options != null ? options : PlanOptions.NONE);
		String locationObjectId = location.get(FieldNames.FIELD_OBJECT_ID);
		String text = locationSubjectText(location, variant);
		if(text.isEmpty() && !NarrativeUtil.isMeaningful(opts.getPromptOverride())) {
			throw new PictureBookException(400, "Location " + locationObjectId + " has no description to render from");
		}
		Scaffold s = scaffold(user, book, node, opts, apiType, sdServer, "location " + locationObjectId);
		s.builder
			.purpose(RenderPlan.PURPOSE_LOCATION)
			.location(new RenderPlan.Location(locationObjectId, text));
		return finish(s);
	}

	/// The builder every plan shares before its subject is set, plus the capabilities the strategy needs.
	private static final class Scaffold {
		final RenderPlan.Builder builder;
		final RendererCapabilities caps;
		Scaffold(RenderPlan.Builder builder, RendererCapabilities caps) {
			this.builder = builder;
			this.caps = caps;
		}
	}

	/// Style chain -> effective config snapshot -> model policy -> preset -> canvas -> style, with the
	/// resolver's degradations already on the builder. {@code subject} names the plan in the 409 message.
	private static Scaffold scaffold(BaseRecord user, BaseRecord book, BaseRecord node, PlanOptions opts,
			SDAPIEnumType apiType, String sdServer, String subject) {
		PbStyleResolver.EffectiveStyle style = PbStyleResolver.resolve(user, book);
		if(!style.isComplete()) {
			throw new PictureBookException(409, "Style chain of book " + book.get(OlioFieldNames.FIELD_PB_SLUG)
				+ (style.cycleDetected() ? " cycles" : " is not readable at " + style.truncatedModel() + " " + style.truncatedAt())
				+ "; " + subject + " cannot be planned against a partial style");
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
			.canvas(canvas)
			.preset(presetValues)
			.style(planStyle)
			.config(snapshot)
			.intent(new RenderPlan.Intent(opts.getPromptOverride(), opts.isRaw()))
			.model(locPick)
			.model(directPick);
		for(RenderPlan.Degradation d : degradations) {
			b.degradation(d);
		}
		return new Scaffold(b, caps);
	}

	/// Strategy selection and per-stage compilation, identical for every purpose. The compiler is the
	/// stage pick's family ({@link PromptCompilers#forPick}): FLUX2 picks compile as prose, everything else as SDXL.
	private static RenderPlan finish(Scaffold s) {
		RenderPlan plan = s.builder.build();
		List<RenderPlan.Degradation> out = new ArrayList<>();
		RenderPlan.Strategy strategy = StrategySelector.select(plan, s.caps, false, out);
		plan = plan.withStrategy(strategy, out);

		out = new ArrayList<>();
		Map<String, CompiledPrompt> compiled = new LinkedHashMap<>();
		for(String stage : strategy.getStages()) {
			compiled.put(stage, PromptCompilers.forPick(plan.pickForStage(stage)).compile(plan, stage, out));
		}
		return plan.withCompiled(compiled, out);
	}

	/**
	 * The location's own description, then the variant's qualifiers in a fixed order (time of day, weather,
	 * season, viewpoint, extra), each only when meaningful. Empty when the location carries no description;
	 * the caller decides whether that is a refusal.
	 */
	static String locationSubjectText(BaseRecord location, BaseRecord variant) {
		List<String> parts = new ArrayList<>();
		String desc = strField(location, FieldNames.FIELD_DESCRIPTION);
		if(NarrativeUtil.isMeaningful(desc)) parts.add(desc.trim());
		if(variant != null) {
			for(String f : new String[] { OlioFieldNames.FIELD_PB_TIME_OF_DAY, OlioFieldNames.FIELD_PB_WEATHER,
					OlioFieldNames.FIELD_PB_SEASON, OlioFieldNames.FIELD_PB_VIEWPOINT, OlioFieldNames.FIELD_PB_EXTRA }) {
				String v = strField(variant, f);
				if(NarrativeUtil.isMeaningful(v)) parts.add(v.trim());
			}
		}
		return (parts.isEmpty() ? "" : joinSentences(parts));
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

	/**
	 * The scene's setting text as the plan carries it (P4-10 A2: the composition is the authority, the scene
	 * is the fallback).
	 * <ul>
	 * <li>A {@code CHAPBOOK_PAGE} composition with a meaningful {@code promptOverride} supplies the page's
	 *     prompt verbatim (P4-8).</li>
	 * <li>A {@code SCENE} composition supplies its board text, per field with the scene's own field as the
	 *     fallback: {@code settingText} else {@code scene.setting}, {@code action} else {@code scene.action},
	 *     {@code mood} else {@code scene.mood}, plus the composition's {@code props} when meaningful. When none
	 *     of those is meaningful the scene's text is used <b>without</b> its legacy {@code sdPrompt} - once a
	 *     composition exists the legacy prompt has already been carried onto it as {@code promptOverride}
	 *     (the intent clause, {@link #buildScenePlan}) and must not be repeated as the setting.</li>
	 * <li>No composition: the scene's own text, legacy {@code sdPrompt} first, as before.</li>
	 * </ul>
	 */
	static String locationText(BaseRecord scene, BaseRecord composition) {
		if(isPageComposition(composition)) {
			String pagePrompt = strField(composition, OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE);
			if(NarrativeUtil.isMeaningful(pagePrompt)) return pagePrompt.trim();
			return locationText(scene);
		}
		if(composition == null) {
			return locationText(scene);
		}
		List<String> parts = new ArrayList<>();
		String setting = firstMeaningful(strField(composition, OlioFieldNames.FIELD_PB_SETTING_TEXT), strField(scene, OlioFieldNames.FIELD_PB_SETTING));
		if(setting != null) parts.add(setting);
		String action = firstMeaningful(strField(composition, OlioFieldNames.FIELD_PB_ACTION), strField(scene, OlioFieldNames.FIELD_PB_ACTION));
		if(action != null) parts.add(action);
		String mood = firstMeaningful(strField(composition, OlioFieldNames.FIELD_PB_MOOD), strField(scene, OlioFieldNames.FIELD_PB_MOOD));
		if(mood != null) parts.add(mood);
		String props = strField(composition, OlioFieldNames.FIELD_PB_PROPS);
		if(NarrativeUtil.isMeaningful(props)) parts.add(props.trim());
		if(!parts.isEmpty()) return joinSentences(parts);
		return locationText(scene, false);
	}

	/// The first meaningful value, trimmed, or null.
	private static String firstMeaningful(String... values) {
		for(String v : values) {
			if(NarrativeUtil.isMeaningful(v)) return v.trim();
		}
		return null;
	}

	static boolean isPageComposition(BaseRecord composition) {
		if(composition == null || !composition.hasField(OlioFieldNames.FIELD_PB_PURPOSE)) return false;
		return PbCompositionPurposeEnumType.CHAPBOOK_PAGE.toString().equalsIgnoreCase(strField(composition, OlioFieldNames.FIELD_PB_PURPOSE));
	}

	/**
	 * The camera clause a {@code SCENE} composition contributes (P4-10 A2): the shot type and viewpoint as words
	 * ({@code CLOSE_UP} → "close up", {@code OVER_SHOULDER} → "over shoulder"; {@code UNKNOWN}/unset contribute
	 * nothing) followed by the free-text {@code cameraText}, joined with ", ". Null when the composition is null,
	 * a page composition, or carries nothing meaningful - the plan then has no camera, as before.
	 * <p>
	 * The composer writes the shot and viewpoint into {@code cameraText} as well ({@code PbComposeUtil.cameraText},
	 * "medium, eye_level"), so any comma-separated piece of the free text that is just one of those two words is
	 * dropped here rather than repeated ("medium, eye level, medium, eye_level" in every LLM-composed scene's
	 * compiled prompt, found by the P4-10 live proof).
	 */
	static String cameraText(BaseRecord composition) {
		if(composition == null || isPageComposition(composition)) return null;
		List<String> parts = new ArrayList<>();
		String shot = words(strField(composition, OlioFieldNames.FIELD_PB_SHOT_TYPE));
		if(shot != null) parts.add(shot);
		String view = words(strField(composition, OlioFieldNames.FIELD_PB_VIEWPOINT));
		if(view != null) parts.add(view);
		String text = strField(composition, OlioFieldNames.FIELD_PB_CAMERA_TEXT);
		if(NarrativeUtil.isMeaningful(text)) {
			List<String> kept = new ArrayList<>();
			boolean dropped = false;
			for(String piece : text.split(",")) {
				String p = piece.trim();
				if(p.isEmpty()) continue;
				String norm = p.toLowerCase().replace('_', ' ');
				if(norm.equals(shot) || norm.equals(view)) {
					dropped = true;
					continue;
				}
				kept.add(p);
			}
			if(!dropped) parts.add(text.trim());
			else parts.addAll(kept);
		}
		return (parts.isEmpty() ? null : String.join(", ", parts));
	}

	/// An enum name as prompt words: lower-cased, underscores to spaces; null for UNKNOWN or unset
	/// ({@link #enumStr} does the UNKNOWN/blank filtering).
	static String words(String enumName) {
		String n = enumStr(enumName);
		return (n == null ? null : n.toLowerCase().replace('_', ' '));
	}

	/// The scene's own text, nearest-to-a-prompt first. Empty is allowed: the plan is a read.
	static String locationText(BaseRecord scene) {
		return locationText(scene, true);
	}

	/// The scene's own text; {@code includeSdPrompt} false skips the legacy prompt (it is the composition's
	/// promptOverride once a composition exists, and goes into the intent clause instead).
	static String locationText(BaseRecord scene, boolean includeSdPrompt) {
		if(includeSdPrompt) {
			String sdPrompt = strField(scene, OlioFieldNames.FIELD_CB_SD_PROMPT);
			if(NarrativeUtil.isMeaningful(sdPrompt)) return sdPrompt.trim();
		}
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

	// ─── composition -> actors / elements (P4-7) ─────────────────────────────────

	/**
	 * The scene's actors from its SCENE-purpose composition (V3 §12, §29; P4-7). No composition (none
	 * exists in the book's group as {@code user} lists it) means no actors and no degradation - the plan
	 * never composes one.
	 * Each {@code olio.pb.sceneActor} row becomes one {@link RenderPlan.Actor}:
	 * <ul>
	 * <li>a row with no {@code character} is a text actor (EXTRA / CROWD style): the row's name and
	 *     description, the count folded in front of the description, never referenced, no degradation;</li>
	 * <li>a row whose character cannot be read as {@code user} (the outfit resolver substituted the row's FK
	 *     stub, or the stub carries no name) is
	 *     {@code ACTOR_UNRESOLVED} on BASE - actorId is the character's objectId when the stub carries one,
	 *     else the row's - and is planned from the row's text;</li>
	 * <li>a resolved character carries its name, its outfit ({@link PbWardrobeUtil#resolveOutfits} for the
	 *     apparel, {@link PbWardrobeUtil#describeApparel} for the text), its identity text (the narrative's
	 *     physical description alone when an outfit text resolved, else {@link #actorDescription}) and its
	 *     approved PORTRAIT artifact when the book's workflow has one.</li>
	 * </ul>
	 * {@code referenced} is decided here, not by the compiler: a HERO / SUPPORTING (or FOREGROUND) actor whose
	 * policy is not TEXT_ONLY and who has an approved portrait is a candidate (NAMED_BACKGROUND only under
	 * REQUIRE_REFERENCE); candidates fill {@code caps.maxReferences} slots less one when the scene carries a
	 * location render, REQUIRE_REFERENCE first, then by priority. Every other candidate is
	 * {@code ACTOR_TEXT_ONLY} with the reason (no approved portrait, reference limit, REQUIRE_REFERENCE not
	 * met); a renderer with no reference slots at all makes every actor text with no per-actor degradation.
	 * A composition or actor-row read that throws never aborts a plan: the actors are empty and one
	 * {@code ACTOR_UNRESOLVED} (no actorId) says the rows were not read, so the render cannot auto-approve.
	 */
	static List<RenderPlan.Actor> sceneActors(BaseRecord user, BaseRecord book, BaseRecord scene, RendererCapabilities caps,
			boolean hasLocationRef, List<RenderPlan.Degradation> out) {
		return actorsOf(user, book, findComposition(user, book, scene, out), caps, hasLocationRef, out);
	}

	/// The scene's elements from its SCENE-purpose composition; empty without one. No degradation is raised.
	static List<RenderPlan.Element> sceneElements(BaseRecord user, BaseRecord book, BaseRecord scene, List<RenderPlan.Degradation> out) {
		return elementsOf(user, findComposition(user, book, scene, out), out);
	}

	/// The scene's SCENE-purpose composition as {@code user} reads it, or null (none, no book group). A read that
	/// throws is null too, with an ACTOR_UNRESOLVED on {@code out} so the plan says the rows were not read.
	private static BaseRecord findComposition(BaseRecord user, BaseRecord book, BaseRecord scene, List<RenderPlan.Degradation> out) {
		if(book == null || scene == null) return null;
		long groupId = (book.hasField(FieldNames.FIELD_GROUP_ID) ? longOf(book.get(FieldNames.FIELD_GROUP_ID)) : 0L);
		if(groupId <= 0L) {
			logger.warn("Book " + book.get(FieldNames.FIELD_OBJECT_ID) + " carries no groupId; scene planned without a composition");
			return null;
		}
		/// A ChapBook page plans from its CHAPBOOK_PAGE composition; a scene from its SCENE composition (P4-8)
		PbCompositionPurposeEnumType purpose = (PbNodeExecutor.isChapBook(book) ? PbCompositionPurposeEnumType.CHAPBOOK_PAGE : PbCompositionPurposeEnumType.SCENE);
		try {
			return PbComposeUtil.findSceneComposition(user, scene, groupId, PbGraphUtil.orgId(book), purpose);
		}
		catch(Exception e) {
			logger.warn("Failed to read the composition of scene " + scene.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
			unreadRows(out, "composition of scene " + scene.get(FieldNames.FIELD_OBJECT_ID));
			return null;
		}
	}

	/// Records that a composition's rows could not be read: the actors are unresolved, so auto-approve is vetoed.
	private static void unreadRows(List<RenderPlan.Degradation> out, String what) {
		if(out == null) return;
		out.add(new RenderPlan.Degradation(RenderPlan.Degradation.ACTOR_UNRESOLVED,
			"The " + what + " could not be read; the scene is planned without its actors", null, RenderPlan.STAGE_BASE));
	}

	/// One actor row on its way to an immutable Actor: the builder plus what the reference decision needs.
	private static final class ActorEntry {
		final RenderPlan.Actor.Builder builder;
		final String name;
		final String actorId;
		final int priority;
		final int order;
		final boolean requireReference;
		boolean candidate;
		boolean referenced;

		ActorEntry(RenderPlan.Actor.Builder builder, String name, String actorId, int priority, int order, boolean requireReference) {
			this.builder = builder;
			this.name = name;
			this.actorId = actorId;
			this.priority = priority;
			this.order = order;
			this.requireReference = requireReference;
		}
	}

	private static List<RenderPlan.Actor> actorsOf(BaseRecord user, BaseRecord book, BaseRecord composition,
			RendererCapabilities caps, boolean hasLocationRef, List<RenderPlan.Degradation> out) {
		if(composition == null) return Collections.emptyList();
		List<BaseRecord> rows;
		Map<Long, PbWardrobeUtil.ResolvedOutfit> outfits = new HashMap<>();
		try {
			rows = PbComposeUtil.listActors(user, composition);
			for(PbWardrobeUtil.ResolvedOutfit ro : PbWardrobeUtil.resolveOutfits(user, book, composition)) {
				if(ro.actor != null) outfits.put(PbWardrobeUtil.idOf(ro.actor), ro);
			}
		}
		catch(Exception e) {
			logger.warn("Failed to read the actors of composition " + composition.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
			unreadRows(out, "actor rows of composition " + composition.get(FieldNames.FIELD_OBJECT_ID));
			return Collections.emptyList();
		}
		if(rows == null || rows.isEmpty()) return Collections.emptyList();

		long orgId = PbGraphUtil.orgId(book);
		int slots = (caps == null ? 0 : Math.max(0, caps.getMaxReferences() - (hasLocationRef ? 1 : 0)));
		BaseRecord[] workflow = new BaseRecord[] { null };
		boolean[] workflowRead = new boolean[] { false };

		List<ActorEntry> entries = new ArrayList<>();
		int order = 0;
		for(BaseRecord row : rows) {
			String rowOid = strField(row, FieldNames.FIELD_OBJECT_ID);
			String rowName = rowDisplayName(row);
			String rowText = meaningful(strField(row, FieldNames.FIELD_DESCRIPTION));
			String role = enumStr(strField(row, OlioFieldNames.FIELD_PB_ROLE));
			if(role == null) role = RenderPlan.ROLE_FOREGROUND;
			String policy = enumStr(strField(row, OlioFieldNames.FIELD_PB_REFERENCE_POLICY));
			String facing = enumStr(strField(row, OlioFieldNames.FIELD_PB_FACING));
			int priority = intOf(row, OlioFieldNames.FIELD_PB_PRIORITY, 0);
			boolean textOnly = PbReferencePolicyEnumType.TEXT_ONLY.name().equals(policy);
			boolean requireRef = PbReferencePolicyEnumType.REQUIRE_REFERENCE.name().equals(policy);

			String charOid = fkObjectId(row, OlioFieldNames.FIELD_PB_CHARACTER);
			long charId = PbHealthUtil.fkId(row, OlioFieldNames.FIELD_PB_CHARACTER);

			RenderPlan.Actor.Builder b;
			String name;
			String actorId;
			ActorEntry entry;
			if(charId <= 0L) {
				/// A text row: EXTRA / CROWD style, named or not, never a reference. The count is spoken only when it
				/// is a crowd (the compose step stores at least 1 on every row).
				int count = intOf(row, FieldNames.FIELD_COUNT, 0);
				String text = (count > 1 && rowText != null ? count + " " + rowText : rowText);
				actorId = rowOid;
				name = rowName;
				b = new RenderPlan.Actor.Builder(actorId).name(name).identity(new RenderPlan.Identity(null, null, text));
				entry = new ActorEntry(b, name, actorId, priority, order, false);
			}
			else {
				PbWardrobeUtil.ResolvedOutfit ro = outfits.get(PbWardrobeUtil.idOf(row));
				BaseRecord cp = (ro != null ? ro.character : PbWardrobeUtil.readCharacterById(user, charId, orgId));
				if(cp == null || !cp.hasField(FieldNames.FIELD_NAME) || (ro != null && ro.characterUnread)) {
					actorId = (charOid != null ? charOid : rowOid);
					name = rowName;
					String label = (rowName != null ? rowName : (rowText != null ? rowText : actorId));
					out.add(new RenderPlan.Degradation(RenderPlan.Degradation.ACTOR_UNRESOLVED,
						label + " rendered from the row's text (character " + (charOid != null ? charOid : "#" + charId) + " could not be read)",
						actorId, RenderPlan.STAGE_BASE));
					b = new RenderPlan.Actor.Builder(actorId).characterId(charOid).name(name)
						.identity(new RenderPlan.Identity(null, null, rowText));
					entry = new ActorEntry(b, name, actorId, priority, order, false);
				}
				else {
					String cpOid = strField(cp, FieldNames.FIELD_OBJECT_ID);
					actorId = (cpOid != null ? cpOid : rowOid);
					name = meaningful(strField(cp, FieldNames.FIELD_NAME));
					RenderPlan.Outfit outfit = null;
					String outfitText = null;
					if(ro != null && ro.apparel != null) {
						try {
							outfitText = PbWardrobeUtil.describeApparel(user, ro.apparel, orgId);
						}
						catch(Exception e) {
							logger.warn("Could not describe the apparel of " + name + ": " + e.getMessage());
						}
						outfit = new RenderPlan.Outfit(strField(ro.apparel, FieldNames.FIELD_OBJECT_ID), outfitText, null);
					}
					String identityText = (NarrativeUtil.isMeaningful(outfitText) ? physicalDescription(cp) : actorDescription(cp));
					String portraitOid = null;
					if(!textOnly) {
						if(!workflowRead[0]) {
							workflowRead[0] = true;
							try {
								workflow[0] = PbGraphUtil.findWorkflow(user, book);
							}
							catch(Exception e) {
								logger.warn("Could not read the workflow of book " + book.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
							}
						}
						portraitOid = approvedPortrait(user, workflow[0], cpOid);
					}
					b = new RenderPlan.Actor.Builder(actorId).characterId(cpOid).name(name).outfit(outfit)
						.identity(new RenderPlan.Identity(portraitOid, null, identityText));
					entry = new ActorEntry(b, name, actorId, priority, order, requireRef);
					boolean eligibleRole = RenderPlan.ROLE_HERO.equals(role) || RenderPlan.ROLE_SUPPORTING.equals(role)
						|| RenderPlan.ROLE_FOREGROUND.equals(role) || (RenderPlan.ROLE_NAMED_BACKGROUND.equals(role) && requireRef);
					if(!textOnly && eligibleRole) {
						if(portraitOid != null) {
							entry.candidate = true;
						}
						else {
							out.add(new RenderPlan.Degradation(RenderPlan.Degradation.ACTOR_TEXT_ONLY,
								label(name, actorId) + " rendered from description (" + (requireRef ? "REQUIRE_REFERENCE not met" : "no approved portrait") + ")",
								actorId, RenderPlan.STAGE_BASE));
						}
					}
				}
			}
			b.role(role).priority(priority).box(boxOf(row)).depth(intOf(row, OlioFieldNames.FIELD_PB_DEPTH, 0))
				.pose(meaningful(strField(row, OlioFieldNames.FIELD_PB_POSE)))
				.expression(meaningful(strField(row, FieldNames.FIELD_EXPRESSION)))
				.action(meaningful(strField(row, OlioFieldNames.FIELD_PB_ACTION)))
				.facing(facing != null ? facing.toLowerCase() : null)
				.policy(policy);
			entries.add(entry);
			order++;
		}

		/// Slot assignment: REQUIRE_REFERENCE first, then priority, then row order (listActors is already by priority).
		List<ActorEntry> candidates = new ArrayList<>();
		for(ActorEntry e : entries) {
			if(e.candidate) candidates.add(e);
		}
		candidates.sort(Comparator.<ActorEntry>comparingInt(e -> e.requireReference ? 0 : 1)
			.thenComparingInt(e -> e.priority).thenComparingInt(e -> e.order));
		int used = 0;
		for(ActorEntry e : candidates) {
			if(used < slots) {
				e.referenced = true;
				used++;
			}
			else if(e.requireReference) {
				out.add(new RenderPlan.Degradation(RenderPlan.Degradation.ACTOR_TEXT_ONLY,
					label(e.name, e.actorId) + " rendered from description (REQUIRE_REFERENCE not met)", e.actorId, RenderPlan.STAGE_BASE));
			}
			else if(slots > 0) {
				out.add(new RenderPlan.Degradation(RenderPlan.Degradation.ACTOR_TEXT_ONLY,
					label(e.name, e.actorId) + " rendered from description (reference limit " + slots + ")", e.actorId, RenderPlan.STAGE_BASE));
			}
		}

		List<RenderPlan.Actor> actors = new ArrayList<>();
		for(ActorEntry e : entries) {
			actors.add(e.builder.referenced(e.referenced).build());
		}
		return actors;
	}

	private static List<RenderPlan.Element> elementsOf(BaseRecord user, BaseRecord composition, List<RenderPlan.Degradation> out) {
		if(composition == null) return Collections.emptyList();
		try {
			List<BaseRecord> rows = PbComposeUtil.listElements(user, composition);
			if(rows == null || rows.isEmpty()) return Collections.emptyList();
			List<RenderPlan.Element> elements = new ArrayList<>();
			for(BaseRecord row : rows) {
				String text = PbComposeUtil.elementText(user, row);
				elements.add(new RenderPlan.Element(
					enumStr(strField(row, OlioFieldNames.FIELD_PB_ELEMENT_TYPE)),
					rowDisplayName(row),
					(NarrativeUtil.isMeaningful(text) ? text.trim() : null),
					boxOf(row),
					intOf(row, OlioFieldNames.FIELD_PB_DEPTH, 0),
					enumStr(strField(row, OlioFieldNames.FIELD_PB_IMPORTANCE)),
					fkObjectId(row, OlioFieldNames.FIELD_ITEM)));
			}
			return elements;
		}
		catch(Exception e) {
			logger.warn("Failed to read the elements of composition " + composition.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
			return Collections.emptyList();
		}
	}

	/// The objectId of the first APPROVED artifact in the character's PORTRAIT chain, or null.
	private static String approvedPortrait(BaseRecord user, BaseRecord workflow, String charOid) {
		if(workflow == null || charOid == null) return null;
		try {
			BaseRecord node = PbPipelineUtil.findNodeByHandle(user, workflow, PbPipelineUtil.portraitHandle(charOid));
			if(node == null) return null;
			for(BaseRecord a : PbArtifactUtil.listChain(user, node, PbPipelineUtil.ROLE_PORTRAIT)) {
				if(PbArtifactUtil.reviewStatusOf(a) == PbReviewStatusEnumType.APPROVED) {
					return a.get(FieldNames.FIELD_OBJECT_ID);
				}
			}
		}
		catch(Exception e) {
			logger.warn("Could not read the portrait chain of " + charOid + ": " + e.getMessage());
		}
		return null;
	}

	/// The narrative's physical description alone (the outfit is spoken by the plan's Outfit), else actorDescription.
	private static String physicalDescription(BaseRecord cp) {
		BaseRecord narrative = (cp.hasField(OlioFieldNames.FIELD_NARRATIVE) ? cp.get(OlioFieldNames.FIELD_NARRATIVE) : null);
		if(narrative != null) {
			try {
				IOSystem.getActiveContext().getReader().populate(narrative, new String[] { "physicalDescription" });
			}
			catch(Exception e) {
				logger.warn("Could not populate narrative of " + cp.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			}
			String v = strField(narrative, "physicalDescription");
			if(NarrativeUtil.isMeaningful(v)) return v.trim();
		}
		return actorDescription(cp);
	}

	/// The row's box when it has any extent, else null (an unset box reads back as four zeros).
	private static RenderPlan.Box boxOf(BaseRecord row) {
		double w = doubleOf(row, OlioFieldNames.FIELD_PB_W);
		double h = doubleOf(row, OlioFieldNames.FIELD_PB_H);
		if(w <= 0 && h <= 0) return null;
		return new RenderPlan.Box(doubleOf(row, OlioFieldNames.FIELD_PB_X), doubleOf(row, OlioFieldNames.FIELD_PB_Y), w, h);
	}

	private static String label(String name, String actorId) {
		return (name != null ? name : actorId);
	}

	/// The row's display name, or null when its name is the compose step's handle (actor.<composition>.<n> or
	/// element.<composition>.<n>), which identifies the row and must never be spoken in a prompt.
	private static String rowDisplayName(BaseRecord row) {
		String n = meaningful(strField(row, FieldNames.FIELD_NAME));
		if(n == null) return null;
		return (n.startsWith(PbComposeUtil.ACTOR_NAME_PREFIX) || n.startsWith(PbComposeUtil.ELEMENT_NAME_PREFIX) ? null : n);
	}

	/// Trimmed when meaningful, else null.
	private static String meaningful(String s) {
		return (NarrativeUtil.isMeaningful(s) ? s.trim() : null);
	}

	/// An enum field's name, upper-cased, or null when unset / UNKNOWN.
	private static String enumStr(String s) {
		if(s == null) return null;
		String u = s.trim().toUpperCase();
		return (u.isEmpty() || "UNKNOWN".equals(u) ? null : u);
	}

	private static double doubleOf(BaseRecord rec, String field) {
		if(rec == null || !rec.hasField(field)) return 0.0;
		Object v = rec.get(field);
		return (v instanceof Number ? ((Number) v).doubleValue() : 0.0);
	}

	private static long longOf(Object v) {
		return (v instanceof Number ? ((Number) v).longValue() : 0L);
	}

	/// pbDescription attribute first, then the narrative's physical (+ outfit) description. The
	/// narrative's own sdPrompt is deliberately NOT used: it is a portrait prompt, not a scene actor line.
	static String actorDescription(BaseRecord cp) {
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
