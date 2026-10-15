package org.cote.accountmanager.olio.llm;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.CanvasResolver;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.ConfigNotApplied;
import org.cote.accountmanager.olio.illustration.ConfigSnapshot;
import org.cote.accountmanager.olio.illustration.IllustrationModelPolicy;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.SdxlCompiler;
import org.cote.accountmanager.olio.illustration.StageImage;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.illustration.StageResult;
import org.cote.accountmanager.olio.illustration.StrategySelector;
import org.cote.accountmanager.olio.illustration.SwarmRendererAdapter;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbPageFormatEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;

/**
 * The chat scene image through the shared illustration service (PictureBook V3 design §24.1): a
 * transient {@link RenderPlan} built from what {@link Chat#generateScenePrompt} already computed, one
 * BASE stage through the renderer adapter, the bytes persisted as the requesting user under
 * {@code ~/Gallery/Scenes/<label>} exactly where the v1 composite puts them. Chat is a consumer of the
 * illustration service, not of the PictureBook domain: nothing here imports {@code olio.picturebook}.
 * <p>
 * Phase 2 scope: strategy is TEXT_ONLY (the portraits are not attached as references - each actor with
 * a portrait is reported as an {@code ACTOR_TEXT_ONLY} degradation, never silently dropped), there is
 * no landscape stage, one image regardless of the preset, no fallback to the v1 composite on failure.
 * The style comes from the chat's saved SD config: its style clause is the plan's style, so
 * {@link ConfigNotApplied#STYLE_CLAUSE} cannot fire here; what the plan path does not apply are the v1
 * composite knobs ({@code hires}, {@code compositeMode}, {@code skipLandscape}, {@code sceneCreativity})
 * and the v1 hard-coded default negative, each reported under {@link ConfigNotApplied#KEY}.
 */
public final class ChatSceneRenderUtil {

	private static final Logger logger = LogManager.getLogger(ChatSceneRenderUtil.class);

	private static final SecureRandom rand = new SecureRandom();

	/// The additive response key carrying the render description ({@link ChatSceneRender#describe()}).
	public static final String RENDER_KEY = "render";

	public static final String RENDER_PATH_V1 = "v1";
	public static final String RENDER_PATH_V3 = "v3";

	/// Where the v1 composite puts its scenes, kept so the gallery view sees both paths' images.
	public static final String SCENE_GALLERY_PREFIX = "~/Gallery/Scenes/";

	/// The v1 prompt's two-figure cue, carried as a HIGH element so a two-actor text-only render keeps both figures.
	public static final String TWO_PEOPLE_CUE = "two people in scene";

	private ChatSceneRenderUtil() {
	}

	/// A refusal or failure the transport maps to an HTTP status; the message never carries the server URL.
	public static final class RenderFailure extends RuntimeException {
		private static final long serialVersionUID = 1L;
		private final int status;

		public RenderFailure(int status, String message) {
			super(message);
			this.status = status;
		}

		public int getStatus() {
			return status;
		}
	}

	/// What one v3 chat render decided and produced.
	public static final class ChatSceneRender {
		private final RenderPlan plan;
		private final CompiledPrompt compiled;
		private final RenderPlan.ModelPick pick;
		private final BaseRecord data;
		private final Long seed;
		private final String requestJson;
		private final long durationMs;
		private final List<String> degradations;
		private final List<String> configNotApplied;

		ChatSceneRender(RenderPlan plan, CompiledPrompt compiled, RenderPlan.ModelPick pick, BaseRecord data, Long seed,
				String requestJson, long durationMs, List<String> degradations, List<String> configNotApplied) {
			this.plan = plan;
			this.compiled = compiled;
			this.pick = pick;
			this.data = data;
			this.seed = seed;
			this.requestJson = requestJson;
			this.durationMs = durationMs;
			this.degradations = degradations;
			this.configNotApplied = configNotApplied;
		}

		public RenderPlan getPlan() { return plan; }
		public CompiledPrompt getCompiled() { return compiled; }
		public RenderPlan.ModelPick getPick() { return pick; }
		/// The persisted image record, as the requesting user wrote it.
		public BaseRecord getData() { return data; }
		public Long getSeed() { return seed; }
		public String getRequestJson() { return requestJson; }
		public long getDurationMs() { return durationMs; }
		/// Each "CODE: message", the PictureBook vocabulary.
		public List<String> getDegradations() { return degradations; }
		public List<String> getConfigNotApplied() { return configNotApplied; }

		/// The {@link #RENDER_KEY} value: one vocabulary with the PictureBook plan-driven nodes.
		public Map<String, Object> describe() {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("renderPath", RENDER_PATH_V3);
			out.put("strategy", plan.getStrategy().getName().name());
			out.put("compiledPrompt", compiled.getPrompt());
			out.put("compiledNegative", compiled.getNegative());
			out.put("model", pick.getModel());
			Map<String, Object> canvas = new LinkedHashMap<>();
			canvas.put("width", plan.getCanvas().getWidth());
			canvas.put("height", plan.getCanvas().getHeight());
			out.put("canvas", canvas);
			out.put("planContentHash", plan.contentHash());
			out.put("seed", seed);
			out.put("durationMs", durationMs);
			out.put("degradations", degradations);
			out.put(ConfigNotApplied.KEY, configNotApplied);
			return out;
		}
	}

	/// The {@link #RENDER_KEY} value for a v1 render, so the client reads one key on either path.
	public static Map<String, Object> describeV1() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("renderPath", RENDER_PATH_V1);
		return out;
	}

	/**
	 * The transient plan for one chat scene. {@code sdConfig} is copied before anything writes to it
	 * (the model policy writes its pick into the copy; the style clause fills its per-style defaults).
	 * The scene description is the intent and the setting rides with it in the main text (the compiler
	 * takes the intent instead of the location text, and trims the main text from the right under budget,
	 * so the setting is the first thing to go); the {@link RenderPlan.Location} records the setting on its own.
	 */
	public static RenderPlan buildPlan(BaseRecord sdConfig, Chat.ScenePromptResult scene, String sysCharOid, String usrCharOid,
			String clientModel, SDAPIEnumType apiType, String sdServer) {
		if(scene == null) {
			throw new RenderFailure(500, "No scene prompt was produced for the chat");
		}
		BaseRecord cfgCopy = (sdConfig != null ? sdConfig.copyRecord() : null);
		Map<String, Object> snapshot = ConfigSnapshot.of(cfgCopy);

		List<RenderPlan.Degradation> degradations = new ArrayList<>();
		ModelResolver.Resolution locRes = IllustrationModelPolicy.apply(PbModelTaskEnumType.LOCATION, cfgCopy, clientModel,
			null, null, null, apiType, sdServer, false);
		RenderPlan.ModelPick locPick = ModelResolver.pickOf(locRes);
		ModelResolver.addDegradation(locRes, degradations);

		QualityPresets.Preset preset = QualityPresets.resolve(PbQualityPresetEnumType.STANDARD);
		RendererCapabilities caps = (locRes.getCapabilities() != null ? locRes.getCapabilities() : RendererCapabilities.forModel(locRes.getModel()));
		int steps = (caps != null ? caps.recommendedSteps(preset.getStepsMode()) : intOf(snapshot.get("steps"), 20));
		RenderPlan.PresetValues presetValues = new RenderPlan.PresetValues(preset.getName().name(), steps, preset.getVariants(),
			preset.isUpscale(), preset.getQa());
		RenderPlan.Canvas canvas = CanvasResolver.resolve(PbPageFormatEnumType.UNKNOWN, 0, 0, preset.getResolutionScale(),
			intOf(snapshot.get("width"), 0), intOf(snapshot.get("height"), 0));

		String styleClause = SDUtil.getSDConfigPrompt(cfgCopy);
		RenderPlan.Style planStyle = new RenderPlan.Style(null, null, styleClause, scene.charNegativePrompt, null, false,
			Collections.emptyList());

		String sceneDesc = (NarrativeUtil.isMeaningful(scene.sceneDesc) ? scene.sceneDesc.trim() : "two people facing each other");
		String settingDesc = (NarrativeUtil.isMeaningful(scene.settingDesc) ? scene.settingDesc.trim() : null);
		String main = (settingDesc != null ? sceneDesc + ", " + settingDesc : sceneDesc);

		RenderPlan.Builder b = RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(canvas)
			.preset(presetValues)
			.style(planStyle)
			.config(snapshot)
			.location(new RenderPlan.Location(null, settingDesc))
			.intent(new RenderPlan.Intent(main, false))
			.element(new RenderPlan.Element(TWO_PEOPLE_CUE, RenderPlan.PRIORITY_HIGH, null))
			.actor(new RenderPlan.Actor(sysCharOid, name(scene.sysFirstName), RenderPlan.ROLE_FOREGROUND,
				positioned(scene.sysCharDesc, "on the left"), scene.sysPortraitBytes != null && scene.sysPortraitBytes.length > 0))
			.actor(new RenderPlan.Actor(usrCharOid, name(scene.usrFirstName), RenderPlan.ROLE_FOREGROUND,
				positioned(scene.usrCharDesc, "on the right"), scene.usrPortraitBytes != null && scene.usrPortraitBytes.length > 0))
			.model(locPick);
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

	/**
	 * Plan, refuse-or-render the BASE stage through {@code sdu}'s renderer, and persist the bytes as
	 * {@code user}. 409/503 when the BASE stage has no resolved model (the resolver's reason), 503 when the
	 * renderer is unreachable, 500 on any other renderer or persistence failure; never a fallback.
	 */
	public static ChatSceneRender render(BaseRecord user, SDUtil sdu, BaseRecord sdConfig, Chat.ScenePromptResult scene,
			String sysCharOid, String usrCharOid, String clientModel) {
		if(user == null) {
			throw new RenderFailure(401, "No authenticated principal");
		}
		if(sdu == null) {
			throw new RenderFailure(500, "No renderer transport");
		}
		RenderPlan plan = buildPlan(sdConfig, scene, sysCharOid, usrCharOid, clientModel, sdu.getApiType(), sdu.getServer());
		String label = (NarrativeUtil.isMeaningful(scene.label) ? scene.label.trim() : "chat scene");

		RenderPlan.ModelPick pick = plan.pickForStage(RenderPlan.STAGE_BASE);
		if(pick == null || !pick.isResolved()) {
			throw new RenderFailure(ModelResolver.unresolvedStatus(pick), ModelResolver.unresolvedMessage(label, pick, plan, null));
		}
		CompiledPrompt compiled = plan.getCompiled().get(RenderPlan.STAGE_BASE);
		if(compiled == null || compiled.getPrompt() == null || compiled.getPrompt().isBlank()) {
			throw new RenderFailure(409, "The chat scene compiled to an empty prompt; nothing to render");
		}

		List<String> degradations = new ArrayList<>();
		for(RenderPlan.Degradation d : plan.getDegradations()) {
			degradations.add(d.getCode() + ": " + d.getMessage());
		}
		if(plan.getStrategy().getStages().contains(RenderPlan.STAGE_UPSCALE)) {
			degradations.add("upscale: UPSCALE stage planned by the preset but not rendered (Phase 2 renders BASE only)");
		}
		Map<String, Object> cfg = plan.getConfig();
		List<String> configNotApplied = configNotApplied(cfg, compiled, plan.getStrategy().getName().name());

		Object cfgVal = cfg.get("cfg");
		double cfgScale = (cfgVal instanceof Number ? ((Number) cfgVal).doubleValue() : 7.0);
		StageRequest req = StageRequest.builder()
			.plan(plan)
			.stage(RenderPlan.STAGE_BASE)
			.prompt(compiled)
			.model(pick.getModel())
			.width(plan.getCanvas().getWidth())
			.height(plan.getCanvas().getHeight())
			.steps(plan.getPreset().getSteps())
			.cfg(cfgScale)
			.sampler((String) cfg.get("sampler"))
			.scheduler((String) cfg.get("scheduler"))
			.seed(StageRequest.SEED_RANDOM)
			.images(1)
			.hires(false)
			.config(cfg)
			.build();

		RendererAdapter adapter = new SwarmRendererAdapter(sdu);
		StageResult result = adapter.execute(req, CancelToken.NONE);
		if(!result.isOk()) {
			String error = (result.getError() != null ? result.getError() : RendererAdapter.ERROR_NO_IMAGES);
			int status = (RendererAdapter.ERROR_UNREACHABLE.equals(error) ? 503 : 500);
			throw new RenderFailure(status, "Render of " + label + " failed: " + error);
		}
		StageImage img = result.getImages().get(0);
		byte[] bytes = img.getBytes();
		if(bytes == null || bytes.length == 0) {
			throw new RenderFailure(500, "Rendered image for " + label + " decoded to empty bytes");
		}
		Long seed = (img.isSeedReported() ? Long.valueOf(img.getSeed()) : null);
		BaseRecord data = persist(user, label, bytes, seed, sysCharOid, usrCharOid, plan, result.getRequestJson());
		return new ChatSceneRender(plan, compiled, pick, data, seed, result.getRequestJson(), result.getDurationMs(),
			degradations, configNotApplied);
	}

	/// The saved config's v1 composite knobs, each reported once; the style clause is applied here, so never STYLE_CLAUSE.
	static List<String> configNotApplied(Map<String, Object> cfg, CompiledPrompt compiled, String strategy) {
		List<String> out = new ArrayList<>();
		if(Boolean.TRUE.equals(cfg.get("hires"))) {
			out.add(ConfigNotApplied.HIRES + ": the saved SD config asks for hires but the plan renders the BASE stage only; no refiner pass was sent");
		}
		if(compiled.getNegative() == null || compiled.getNegative().isBlank()) {
			out.add(ConfigNotApplied.DEFAULT_NEGATIVE + ": no negative prompt compiled (the character names no SD negative and the config carries no negativePrompt); the v1 default negative is not applied on the plan path");
		}
		Object mode = cfg.get("compositeMode");
		if(mode instanceof String && NarrativeUtil.isMeaningful((String) mode)) {
			out.add(ConfigNotApplied.COMPOSITE_MODE + ": the saved SD config's compositeMode '" + mode
				+ "' is a v1 composite setting; the plan path selected strategy " + strategy);
		}
		if(cfg.containsKey("skipLandscape")) {
			out.add(ConfigNotApplied.SKIP_LANDSCAPE + ": the saved SD config's skipLandscape=" + cfg.get("skipLandscape")
				+ " is a v1 composite setting; the plan path renders no landscape stage for a chat scene");
		}
		if(cfg.get("sceneCreativity") instanceof Number) {
			out.add(ConfigNotApplied.SCENE_CREATIVITY + ": the saved SD config's sceneCreativity=" + cfg.get("sceneCreativity")
				+ " is a v1 composite setting; the plan path has no second denoise pass to apply it to");
		}
		return out;
	}

	/// Mirrors SDUtil.createSceneImage: same gallery path, same name shape, same attributes, written as the user through PBAC.
	private static BaseRecord persist(BaseRecord user, String label, byte[] bytes, Long seed, String sysCharOid, String usrCharOid,
			RenderPlan plan, String requestJson) {
		String groupPath = SCENE_GALLERY_PREFIX + label;
		long seedl = (seed != null ? seed.longValue() : -1L);
		String dname = label + " - scene - 1 - " + Math.abs(rand.nextInt()) + " - " + seedl;
		try {
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, "DATA",
				user.get(FieldNames.FIELD_ORGANIZATION_ID));
			if(dir == null) {
				throw new RenderFailure(500, "Could not create the scene gallery folder for " + label);
			}
			ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			clist.parameter(FieldNames.FIELD_NAME, dname);
			BaseRecord data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, clist);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
			ByteModelUtil.setValue(data, bytes);
			AttributeUtil.addAttribute(data, "seed", seedl);
			AttributeUtil.addAttribute(data, "imageType", "scene");
			if(sysCharOid != null) AttributeUtil.addAttribute(data, "systemCharacter", sysCharOid);
			if(usrCharOid != null) AttributeUtil.addAttribute(data, "userCharacter", usrCharOid);
			AttributeUtil.addAttribute(data, "renderPlan", plan.toJson());
			if(requestJson != null) AttributeUtil.addAttribute(data, "stageRequest", requestJson);
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, data);
			if(created == null) {
				throw new RenderFailure(500, "Could not persist the rendered scene for " + label);
			}
			return data;
		}
		catch(RenderFailure e) {
			throw e;
		}
		catch(Exception e) {
			logger.error("Persisting the chat scene " + dname + " failed", e);
			throw new RenderFailure(500, "Could not persist the rendered scene for " + label);
		}
	}

	private static String positioned(String desc, String position) {
		return (NarrativeUtil.isMeaningful(desc) ? desc.trim() + " " + position : position);
	}

	private static String name(String firstName) {
		return (NarrativeUtil.isMeaningful(firstName) ? firstName.trim() : null);
	}

	private static int intOf(Object v, int dflt) {
		return (v instanceof Number ? ((Number) v).intValue() : dflt);
	}
}
