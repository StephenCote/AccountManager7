package org.cote.accountmanager.olio.illustration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.util.JSONUtil;

/// The immutable render plan of PictureBook V3 design §12: everything a renderer attempt needs,
/// resolved up front, serialised deterministically so it can be shown, hashed and persisted as an
/// artifact. A plan is immutable per attempt; {@link #withStrategy} and {@link #withCompiled} return
/// copies. The seed is deliberately not a plan field (it is an attempt property, recorded on the
/// image). Product-agnostic: no olio.picturebook, no model names, no DB.
///
/// Wire form: {@link #toJson()} serialises {@link #toMap()} through JSONUtil, whose mapper is
/// NON_EMPTY — nulls, empty strings, empty lists and empty maps are absent on the wire, and
/// {@link #fromJson} restores them as null / "" / empty. "Absent = null" for promptOverride.
public final class RenderPlan {

	public static final String PLAN_VERSION = "illustration/1";

	public static final String PURPOSE_PORTRAIT = "PORTRAIT";
	public static final String PURPOSE_LOCATION = "LOCATION";
	public static final String PURPOSE_SCENE = "SCENE";

	public static final String STAGE_BASE = "BASE";
	public static final String STAGE_UPSCALE = "UPSCALE";

	public static final String ROLE_FOREGROUND = "FOREGROUND";
	public static final String ROLE_BACKGROUND = "BACKGROUND";

	public static final String PRIORITY_HIGH = "HIGH";
	public static final String PRIORITY_MEDIUM = "MEDIUM";
	public static final String PRIORITY_LOW = "LOW";

	private final String planVersion;
	private final String purpose;
	private final Canvas canvas;
	private final PresetValues preset;
	private final Style style;
	private final Map<String, Object> config;
	private final Location location;
	private final Camera camera;
	private final List<Actor> actors;
	private final List<Element> elements;
	private final Intent intent;
	private final Strategy strategy;
	private final Map<String, ModelPick> models;
	private final Map<String, CompiledPrompt> compiled;
	private final List<Degradation> degradations;
	private final Map<String, String> inputs;

	private RenderPlan(Builder b) {
		this.planVersion = PLAN_VERSION;
		this.purpose = b.purpose;
		this.canvas = b.canvas;
		this.preset = b.preset;
		this.style = b.style;
		this.config = Collections.unmodifiableMap(new LinkedHashMap<>(b.config));
		this.location = b.location;
		this.camera = b.camera;
		this.actors = Collections.unmodifiableList(new ArrayList<>(b.actors));
		this.elements = Collections.unmodifiableList(new ArrayList<>(b.elements));
		this.intent = (b.intent == null ? new Intent(null, false) : b.intent);
		this.strategy = b.strategy;
		this.models = Collections.unmodifiableMap(new LinkedHashMap<>(b.models));
		this.compiled = Collections.unmodifiableMap(new LinkedHashMap<>(b.compiled));
		this.degradations = Collections.unmodifiableList(new ArrayList<>(b.degradations));
		this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(b.inputs));
	}

	public String getPlanVersion() {
		return planVersion;
	}

	public String getPurpose() {
		return purpose;
	}

	public Canvas getCanvas() {
		return canvas;
	}

	public PresetValues getPreset() {
		return preset;
	}

	public Style getStyle() {
		return style;
	}

	public Map<String, Object> getConfig() {
		return config;
	}

	public Location getLocation() {
		return location;
	}

	public Camera getCamera() {
		return camera;
	}

	public List<Actor> getActors() {
		return actors;
	}

	public List<Element> getElements() {
		return elements;
	}

	public Intent getIntent() {
		return intent;
	}

	public Strategy getStrategy() {
		return strategy;
	}

	public Map<String, ModelPick> getModels() {
		return models;
	}

	public Map<String, CompiledPrompt> getCompiled() {
		return compiled;
	}

	public List<Degradation> getDegradations() {
		return degradations;
	}

	public Map<String, String> getInputs() {
		return inputs;
	}

	/// The model pick a stage renders with. TEXT_ONLY and the Phase 2 strategies render the BASE
	/// stage with the location pick (the scene stand-in is the landscape path, §6.C 156 e) unless
	/// the purpose is PORTRAIT; DIRECT_REFERENCE renders with the sceneDirect pick; UPSCALE uses
	/// the upscale pick when one was resolved and otherwise the BASE pick. Null when the plan
	/// carries no pick for the task.
	public ModelPick pickForStage(String stage) {
		if(STAGE_UPSCALE.equals(stage)) {
			ModelPick up = models.get(ModelTaskKeys.key(PbModelTaskEnumType.UPSCALE));
			if(up != null) {
				return up;
			}
		}
		PbModelTaskEnumType task;
		if(strategy != null && strategy.getName() == PbStrategyEnumType.DIRECT_REFERENCE) {
			task = PbModelTaskEnumType.SCENE_DIRECT;
		}
		else if(PURPOSE_PORTRAIT.equals(purpose)) {
			task = PbModelTaskEnumType.PORTRAIT;
		}
		else {
			task = PbModelTaskEnumType.LOCATION;
		}
		return models.get(ModelTaskKeys.key(task));
	}

	/// Copy with the strategy set and the selector's degradations appended.
	public RenderPlan withStrategy(Strategy strategy, List<Degradation> added) {
		Builder b = toBuilder();
		b.strategy = strategy;
		if(added != null) {
			b.degradations.addAll(added);
		}
		return b.build();
	}

	/// Copy with the compiled prompts replaced and the compiler's degradations appended.
	public RenderPlan withCompiled(Map<String, CompiledPrompt> compiled, List<Degradation> added) {
		Builder b = toBuilder();
		b.compiled = new LinkedHashMap<>();
		if(compiled != null) {
			b.compiled.putAll(compiled);
		}
		if(added != null) {
			b.degradations.addAll(added);
		}
		return b.build();
	}

	private Builder toBuilder() {
		Builder b = new Builder();
		b.purpose = purpose;
		b.canvas = canvas;
		b.preset = preset;
		b.style = style;
		b.config = new LinkedHashMap<>(config);
		b.location = location;
		b.camera = camera;
		b.actors = new ArrayList<>(actors);
		b.elements = new ArrayList<>(elements);
		b.intent = intent;
		b.strategy = strategy;
		b.models = new LinkedHashMap<>(models);
		b.compiled = new LinkedHashMap<>(compiled);
		b.degradations = new ArrayList<>(degradations);
		b.inputs = new LinkedHashMap<>(inputs);
		return b;
	}

	/// Deterministic map form: fixed insertion order, so equal plans serialise to equal JSON.
	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("planVersion", planVersion);
		m.put("purpose", purpose);
		m.put("canvas", canvas == null ? null : canvas.toMap());
		m.put("preset", preset == null ? null : preset.toMap());
		m.put("style", style == null ? null : style.toMap());
		m.put("config", new LinkedHashMap<>(config));
		m.put("location", location == null ? null : location.toMap());
		m.put("camera", camera == null ? null : camera.toMap());
		List<Object> al = new ArrayList<>();
		for(Actor a : actors) {
			al.add(a.toMap());
		}
		m.put("actors", al);
		List<Object> el = new ArrayList<>();
		for(Element e : elements) {
			el.add(e.toMap());
		}
		m.put("elements", el);
		m.put("intent", intent.toMap());
		m.put("strategy", strategy == null ? null : strategy.toMap());
		Map<String, Object> mm = new LinkedHashMap<>();
		for(Map.Entry<String, ModelPick> e : models.entrySet()) {
			mm.put(e.getKey(), e.getValue().toMap());
		}
		m.put("models", mm);
		Map<String, Object> cm = new LinkedHashMap<>();
		for(Map.Entry<String, CompiledPrompt> e : compiled.entrySet()) {
			cm.put(e.getKey(), e.getValue().toMap());
		}
		m.put("compiled", cm);
		List<Object> dl = new ArrayList<>();
		for(Degradation d : degradations) {
			dl.add(d.toMap());
		}
		m.put("degradations", dl);
		m.put("inputs", new LinkedHashMap<>(inputs));
		return m;
	}

	/// Compact JSON of {@link #toMap()}; the form that is hashed and persisted.
	public String toJson() {
		return JSONUtil.exportObject(toMap(), null, true, false);
	}

	/// SHA-256 hex of {@link #toJson()}.
	public String contentHash() {
		return sha256Hex(toJson());
	}

	public static RenderPlan fromJson(String json) {
		if(json == null || json.isBlank()) {
			return null;
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> m = JSONUtil.importObject(json, LinkedHashMap.class);
		if(m == null) {
			return null;
		}
		return fromMap(m);
	}

	public static RenderPlan fromMap(Map<?, ?> m) {
		Builder b = builder();
		b.purpose = str(m, "purpose");
		b.canvas = Canvas.fromMap(mapOf(m, "canvas"));
		b.preset = PresetValues.fromMap(mapOf(m, "preset"));
		b.style = Style.fromMap(mapOf(m, "style"));
		Map<?, ?> cfg = mapOf(m, "config");
		if(cfg != null) {
			for(Map.Entry<?, ?> e : cfg.entrySet()) {
				b.config.put(String.valueOf(e.getKey()), e.getValue());
			}
		}
		b.location = Location.fromMap(mapOf(m, "location"));
		b.camera = Camera.fromMap(mapOf(m, "camera"));
		for(Map<?, ?> a : listOfMaps(m, "actors")) {
			b.actors.add(Actor.fromMap(a));
		}
		for(Map<?, ?> e : listOfMaps(m, "elements")) {
			b.elements.add(Element.fromMap(e));
		}
		b.intent = Intent.fromMap(mapOf(m, "intent"));
		b.strategy = Strategy.fromMap(mapOf(m, "strategy"));
		Map<?, ?> models = mapOf(m, "models");
		if(models != null) {
			for(Map.Entry<?, ?> e : models.entrySet()) {
				if(e.getValue() instanceof Map) {
					b.models.put(String.valueOf(e.getKey()), ModelPick.fromMap((Map<?, ?>) e.getValue()));
				}
			}
		}
		Map<?, ?> compiled = mapOf(m, "compiled");
		if(compiled != null) {
			for(Map.Entry<?, ?> e : compiled.entrySet()) {
				if(e.getValue() instanceof Map) {
					b.compiled.put(String.valueOf(e.getKey()), CompiledPrompt.fromMap((Map<?, ?>) e.getValue()));
				}
			}
		}
		for(Map<?, ?> d : listOfMaps(m, "degradations")) {
			b.degradations.add(Degradation.fromMap(d));
		}
		Map<?, ?> inputs = mapOf(m, "inputs");
		if(inputs != null) {
			for(Map.Entry<?, ?> e : inputs.entrySet()) {
				b.inputs.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
			}
		}
		return b.build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private String purpose;
		private Canvas canvas;
		private PresetValues preset;
		private Style style;
		private Map<String, Object> config = new LinkedHashMap<>();
		private Location location;
		private Camera camera;
		private List<Actor> actors = new ArrayList<>();
		private List<Element> elements = new ArrayList<>();
		private Intent intent;
		private Strategy strategy;
		private Map<String, ModelPick> models = new LinkedHashMap<>();
		private Map<String, CompiledPrompt> compiled = new LinkedHashMap<>();
		private List<Degradation> degradations = new ArrayList<>();
		private Map<String, String> inputs = new LinkedHashMap<>();

		private Builder() { }

		public Builder purpose(String purpose) {
			this.purpose = purpose;
			return this;
		}

		public Builder canvas(Canvas canvas) {
			this.canvas = canvas;
			return this;
		}

		public Builder preset(PresetValues preset) {
			this.preset = preset;
			return this;
		}

		public Builder style(Style style) {
			this.style = style;
			return this;
		}

		public Builder config(Map<String, Object> config) {
			this.config = new LinkedHashMap<>();
			if(config != null) {
				this.config.putAll(config);
			}
			return this;
		}

		public Builder location(Location location) {
			this.location = location;
			return this;
		}

		public Builder camera(Camera camera) {
			this.camera = camera;
			return this;
		}

		public Builder actor(Actor actor) {
			if(actor != null) {
				this.actors.add(actor);
			}
			return this;
		}

		public Builder actors(List<Actor> actors) {
			this.actors = new ArrayList<>();
			if(actors != null) {
				this.actors.addAll(actors);
			}
			return this;
		}

		public Builder element(Element element) {
			if(element != null) {
				this.elements.add(element);
			}
			return this;
		}

		public Builder elements(List<Element> elements) {
			this.elements = new ArrayList<>();
			if(elements != null) {
				this.elements.addAll(elements);
			}
			return this;
		}

		public Builder intent(Intent intent) {
			this.intent = intent;
			return this;
		}

		public Builder strategy(Strategy strategy) {
			this.strategy = strategy;
			return this;
		}

		public Builder model(ModelPick pick) {
			if(pick != null) {
				String key = ModelTaskKeys.key(pick.getTask());
				this.models.put(key == null ? "unknown" : key, pick);
			}
			return this;
		}

		public Builder compiled(String stage, CompiledPrompt prompt) {
			if(stage != null && prompt != null) {
				this.compiled.put(stage, prompt);
			}
			return this;
		}

		public Builder degradation(Degradation d) {
			if(d != null) {
				this.degradations.add(d);
			}
			return this;
		}

		public Builder input(String role, String hash) {
			if(role != null) {
				this.inputs.put(role, hash);
			}
			return this;
		}

		public RenderPlan build() {
			return new RenderPlan(this);
		}
	}

	// ---- nested value types ------------------------------------------------------------------

	public static final class Canvas {
		private final int width;
		private final int height;

		public Canvas(int width, int height) {
			this.width = width;
			this.height = height;
		}

		public int getWidth() {
			return width;
		}

		public int getHeight() {
			return height;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("width", width);
			m.put("height", height);
			return m;
		}

		static Canvas fromMap(Map<?, ?> m) {
			if(m == null) {
				return null;
			}
			return new Canvas(intOf(m, "width", 0), intOf(m, "height", 0));
		}

		@Override
		public String toString() {
			return width + "x" + height;
		}
	}

	public static final class PresetValues {
		private final String preset;
		private final int steps;
		private final int variants;
		private final boolean upscale;
		private final String qa;

		public PresetValues(String preset, int steps, int variants, boolean upscale, String qa) {
			this.preset = preset;
			this.steps = steps;
			this.variants = variants;
			this.upscale = upscale;
			this.qa = qa;
		}

		public String getPreset() {
			return preset;
		}

		public int getSteps() {
			return steps;
		}

		public int getVariants() {
			return variants;
		}

		public boolean isUpscale() {
			return upscale;
		}

		public String getQa() {
			return qa;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("preset", preset);
			m.put("steps", steps);
			m.put("variants", variants);
			m.put("upscale", upscale);
			m.put("qa", qa);
			return m;
		}

		static PresetValues fromMap(Map<?, ?> m) {
			if(m == null) {
				return null;
			}
			return new PresetValues(str(m, "preset"), intOf(m, "steps", 0), intOf(m, "variants", 0), boolOf(m, "upscale"), str(m, "qa"));
		}
	}

	public static final class Style {
		private final String styleObjectId;
		private final String revisionHash;
		private final String description;
		private final String exclusions;
		private final String palette;
		private final boolean photoreal;
		private final List<String> refs;

		public Style(String styleObjectId, String revisionHash, String description, String exclusions, String palette, boolean photoreal, List<String> refs) {
			this.styleObjectId = styleObjectId;
			this.revisionHash = revisionHash;
			this.description = description;
			this.exclusions = exclusions;
			this.palette = palette;
			this.photoreal = photoreal;
			this.refs = Collections.unmodifiableList(refs == null ? new ArrayList<>() : new ArrayList<>(refs));
		}

		public String getStyleObjectId() {
			return styleObjectId;
		}

		public String getRevisionHash() {
			return revisionHash;
		}

		public String getDescription() {
			return description;
		}

		public String getExclusions() {
			return exclusions;
		}

		public String getPalette() {
			return palette;
		}

		public boolean isPhotoreal() {
			return photoreal;
		}

		public List<String> getRefs() {
			return refs;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("styleObjectId", styleObjectId);
			m.put("revisionHash", revisionHash);
			m.put("description", description);
			m.put("exclusions", exclusions);
			m.put("palette", palette);
			m.put("photoreal", photoreal);
			m.put("refs", new ArrayList<>(refs));
			return m;
		}

		static Style fromMap(Map<?, ?> m) {
			if(m == null) {
				return null;
			}
			return new Style(str(m, "styleObjectId"), str(m, "revisionHash"), str(m, "description"), str(m, "exclusions"), str(m, "palette"), boolOf(m, "photoreal"), strings(m, "refs"));
		}
	}

	public static final class Actor {
		private final String actorId;
		private final String name;
		private final String role;
		private final String description;
		private final boolean referenced;

		public Actor(String actorId, String name, String role, String description, boolean referenced) {
			this.actorId = actorId;
			this.name = name;
			this.role = (role == null ? ROLE_FOREGROUND : role);
			this.description = description;
			this.referenced = referenced;
		}

		public String getActorId() {
			return actorId;
		}

		public String getName() {
			return name;
		}

		public String getRole() {
			return role;
		}

		public boolean isForeground() {
			return !ROLE_BACKGROUND.equals(role);
		}

		public String getDescription() {
			return description;
		}

		public boolean isReferenced() {
			return referenced;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("actorId", actorId);
			m.put("name", name);
			m.put("role", role);
			m.put("description", description);
			m.put("referenced", referenced);
			return m;
		}

		static Actor fromMap(Map<?, ?> m) {
			return new Actor(str(m, "actorId"), str(m, "name"), str(m, "role"), str(m, "description"), boolOf(m, "referenced"));
		}
	}

	public static final class Element {
		private final String name;
		private final String priority;
		private final String description;

		public Element(String name, String priority, String description) {
			this.name = name;
			this.priority = (priority == null ? PRIORITY_MEDIUM : priority);
			this.description = description;
		}

		public String getName() {
			return name;
		}

		public String getPriority() {
			return priority;
		}

		public String getDescription() {
			return description;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", name);
			m.put("priority", priority);
			m.put("description", description);
			return m;
		}

		static Element fromMap(Map<?, ?> m) {
			return new Element(str(m, "name"), str(m, "priority"), str(m, "description"));
		}
	}

	public static final class Location {
		private final String locationId;
		private final String description;

		public Location(String locationId, String description) {
			this.locationId = locationId;
			this.description = description;
		}

		public String getLocationId() {
			return locationId;
		}

		public String getDescription() {
			return description;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("locationId", locationId);
			m.put("description", description);
			return m;
		}

		static Location fromMap(Map<?, ?> m) {
			if(m == null) {
				return null;
			}
			return new Location(str(m, "locationId"), str(m, "description"));
		}
	}

	public static final class Camera {
		private final String description;

		public Camera(String description) {
			this.description = description;
		}

		public String getDescription() {
			return description;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("description", description);
			return m;
		}

		static Camera fromMap(Map<?, ?> m) {
			if(m == null) {
				return null;
			}
			return new Camera(str(m, "description"));
		}
	}

	/// promptOverride null = none ("absent = null" on the wire); raw = no style append.
	public static final class Intent {
		private final String promptOverride;
		private final boolean raw;

		public Intent(String promptOverride, boolean raw) {
			this.promptOverride = promptOverride;
			this.raw = raw;
		}

		public String getPromptOverride() {
			return promptOverride;
		}

		public boolean isRaw() {
			return raw;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("promptOverride", promptOverride);
			m.put("raw", raw);
			return m;
		}

		static Intent fromMap(Map<?, ?> m) {
			if(m == null) {
				return new Intent(null, false);
			}
			return new Intent(str(m, "promptOverride"), boolOf(m, "raw"));
		}
	}

	public static final class Strategy {
		private final PbStrategyEnumType name;
		private final List<String> stages;
		private final String reason;

		public Strategy(PbStrategyEnumType name, List<String> stages, String reason) {
			this.name = (name == null ? PbStrategyEnumType.UNKNOWN : name);
			this.stages = Collections.unmodifiableList(stages == null ? new ArrayList<>() : new ArrayList<>(stages));
			this.reason = reason;
		}

		public PbStrategyEnumType getName() {
			return name;
		}

		public List<String> getStages() {
			return stages;
		}

		public String getReason() {
			return reason;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("name", name.name());
			m.put("stages", new ArrayList<>(stages));
			m.put("reason", reason);
			return m;
		}

		static Strategy fromMap(Map<?, ?> m) {
			if(m == null) {
				return null;
			}
			PbStrategyEnumType n = PbStrategyEnumType.UNKNOWN;
			String s = str(m, "name");
			if(s != null) {
				for(PbStrategyEnumType t : PbStrategyEnumType.values()) {
					if(t.name().equalsIgnoreCase(s)) {
						n = t;
						break;
					}
				}
			}
			return new Strategy(n, strings(m, "stages"), str(m, "reason"));
		}
	}

	/// The resolved checkpoint for one task, folded from a ModelResolver.Resolution: enough to show
	/// which tier won and why, without re-running the resolver.
	public static final class ModelPick {
		private final PbModelTaskEnumType task;
		private final String model;
		private final String family;
		private final String step;
		private final String reason;
		private final boolean degraded;
		private final List<Map<String, String>> candidates;

		public ModelPick(PbModelTaskEnumType task, String model, String family, String step, String reason, boolean degraded, List<Map<String, String>> candidates) {
			this.task = (task == null ? PbModelTaskEnumType.UNKNOWN : task);
			this.model = model;
			this.family = family;
			this.step = step;
			this.reason = reason;
			this.degraded = degraded;
			List<Map<String, String>> cl = new ArrayList<>();
			if(candidates != null) {
				for(Map<String, String> c : candidates) {
					cl.add(Collections.unmodifiableMap(new LinkedHashMap<>(c)));
				}
			}
			this.candidates = Collections.unmodifiableList(cl);
		}

		public PbModelTaskEnumType getTask() {
			return task;
		}

		public String getModel() {
			return model;
		}

		public String getFamily() {
			return family;
		}

		public String getStep() {
			return step;
		}

		public String getReason() {
			return reason;
		}

		public boolean isDegraded() {
			return degraded;
		}

		/// Same test as ModelResolver.Resolution.isResolved, folded through the JSON round trip: a model
		/// was chosen and the reason is OK. False for NO_CANDIDATE / INVENTORY_UNAVAILABLE picks, which
		/// carry no model and must be reported before any renderer call (P2-5).
		public boolean isResolved() {
			return model != null && !model.isBlank() && ModelResolver.Reason.OK.name().equals(reason);
		}

		public List<Map<String, String>> getCandidates() {
			return candidates;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("task", ModelTaskKeys.key(task));
			m.put("model", model);
			m.put("family", family);
			m.put("step", step);
			m.put("reason", reason);
			m.put("degraded", degraded);
			List<Object> cl = new ArrayList<>();
			for(Map<String, String> c : candidates) {
				cl.add(new LinkedHashMap<>(c));
			}
			m.put("candidates", cl);
			return m;
		}

		static ModelPick fromMap(Map<?, ?> m) {
			List<Map<String, String>> cl = new ArrayList<>();
			for(Map<?, ?> c : listOfMaps(m, "candidates")) {
				Map<String, String> cm = new LinkedHashMap<>();
				for(Map.Entry<?, ?> e : c.entrySet()) {
					cm.put(String.valueOf(e.getKey()), e.getValue() == null ? null : String.valueOf(e.getValue()));
				}
				cl.add(cm);
			}
			return new ModelPick(ModelTaskKeys.task(str(m, "task")), str(m, "model"), str(m, "family"), str(m, "step"), str(m, "reason"), boolOf(m, "degraded"), cl);
		}
	}

	/// A §19 degradation: recorded, never thrown. A degradation never stops the render.
	public static final class Degradation {
		public static final String ACTOR_TEXT_ONLY = "ACTOR_TEXT_ONLY";
		public static final String ACTOR_UNRESOLVED = "ACTOR_UNRESOLVED";
		public static final String ACTOR_FAILED = "ACTOR_FAILED";
		public static final String LOCATION_TEXT_ONLY = "LOCATION_TEXT_ONLY";
		public static final String MODEL_SUBSTITUTED = ModelResolver.DEGRADATION_MODEL_SUBSTITUTED;
		public static final String PROMPT_TRUNCATED = "PROMPT_TRUNCATED";
		public static final String QA_WARN = "QA_WARN";

		private final String code;
		private final String message;
		private final String actorId;
		private final String stage;

		public Degradation(String code, String message, String actorId, String stage) {
			this.code = code;
			this.message = message;
			this.actorId = actorId;
			this.stage = stage;
		}

		public String getCode() {
			return code;
		}

		public String getMessage() {
			return message;
		}

		public String getActorId() {
			return actorId;
		}

		public String getStage() {
			return stage;
		}

		Map<String, Object> toMap() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", code);
			m.put("message", message);
			m.put("actorId", actorId);
			m.put("stage", stage);
			return m;
		}

		static Degradation fromMap(Map<?, ?> m) {
			return new Degradation(str(m, "code"), str(m, "message"), str(m, "actorId"), str(m, "stage"));
		}

		@Override
		public String toString() {
			return code + (stage != null ? "@" + stage : "") + (actorId != null ? "[" + actorId + "]" : "") + ": " + message;
		}
	}

	// ---- map helpers (package-private; also used by CompiledPrompt) ------------------------------

	static String str(Map<?, ?> m, String key) {
		if(m == null) {
			return null;
		}
		Object o = m.get(key);
		return (o == null ? null : String.valueOf(o));
	}

	static int intOf(Map<?, ?> m, String key, int def) {
		if(m == null) {
			return def;
		}
		Object o = m.get(key);
		if(o instanceof Number) {
			return ((Number) o).intValue();
		}
		if(o instanceof String) {
			try {
				return Integer.parseInt(((String) o).trim());
			}
			catch(NumberFormatException e) {
				return def;
			}
		}
		return def;
	}

	static boolean boolOf(Map<?, ?> m, String key) {
		if(m == null) {
			return false;
		}
		Object o = m.get(key);
		if(o instanceof Boolean) {
			return (Boolean) o;
		}
		return o != null && Boolean.parseBoolean(String.valueOf(o));
	}

	static Map<?, ?> mapOf(Map<?, ?> m, String key) {
		if(m == null) {
			return null;
		}
		Object o = m.get(key);
		return (o instanceof Map ? (Map<?, ?>) o : null);
	}

	static List<Map<?, ?>> listOfMaps(Map<?, ?> m, String key) {
		List<Map<?, ?>> out = new ArrayList<>();
		if(m == null) {
			return out;
		}
		Object o = m.get(key);
		if(o instanceof List) {
			for(Object e : (List<?>) o) {
				if(e instanceof Map) {
					out.add((Map<?, ?>) e);
				}
			}
		}
		return out;
	}

	static List<String> strings(Map<?, ?> m, String key) {
		List<String> out = new ArrayList<>();
		if(m == null) {
			return out;
		}
		Object o = m.get(key);
		if(o instanceof List) {
			for(Object e : (List<?>) o) {
				if(e != null) {
					out.add(String.valueOf(e));
				}
			}
		}
		return out;
	}

	static String sha256Hex(String s) {
		if(s == null) {
			return null;
		}
		MessageDigest md;
		try {
			md = MessageDigest.getInstance("SHA-256");
		}
		catch(NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable: " + e.getMessage(), e);
		}
		byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder(digest.length * 2);
		for(byte b : digest) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}

	@Override
	public String toString() {
		return toJson();
	}
}
