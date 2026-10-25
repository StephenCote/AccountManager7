package org.cote.accountmanager.olio.illustration;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/// Generate-and-patch (PictureBook V3 design §38.2): ask Swarm's generator for the graph it would run
/// for a stage's ordinary T2I / inpaint parameters, with placeholder images and marker strings in the
/// slots that must become tags, then patch those slots into `${comfyrawworkflowinput...}` tags and
/// replace the numeric save node with {@link ComfyGraph#SAVE_FINAL}. The real images, prompts and seed
/// travel in the returned {@link GraphInputs}; the graph itself never carries image data, so it can
/// be persisted as the stage's `renderGraph` artifact (§38.3).
///
/// Literals stay literals: steps, cfg, sampler, scheduler, the model loaders and the mask grow / blur
/// are what the generator wrote for this request. `start_at_step` is Swarm's own
/// `round(steps * (1 - creativity))`; {@link #startAtStep} is the same formula so a caller can predict
/// it, and the compiler warns when the generated graph disagrees.
public final class WorkflowCompiler {

	private static final Logger logger = LogManager.getLogger(WorkflowCompiler.class);

	public static final String TAG_SCENE = "scene";
	public static final String TAG_NEGATIVE = "negative";
	public static final String TAG_SEED = "seed";
	public static final String TAG_INIT = "init";
	public static final String TAG_MASK = "mask";

	/// Alphanumeric so the generator passes them through untouched and no escaping is needed.
	static final String MARKER_SCENE = "V3SCENEMARKER7731";
	static final String MARKER_NEGATIVE = "V3NEGATIVEMARKER7732";
	static final long MARKER_SEED = 1234567L;

	/// Colour distance (sum of channel differences) under which a decoded placeholder still matches.
	static final int COLOUR_TOLERANCE = 60;

	private static final Color[] REFERENCE_COLOURS = {
		new Color(255, 0, 0), new Color(0, 255, 0), new Color(0, 0, 255), new Color(0, 255, 255),
		new Color(255, 128, 0), new Color(128, 0, 255)
	};
	private static final Color INIT_COLOUR = new Color(255, 255, 0);
	private static final Color MASK_COLOUR = new Color(255, 0, 255);

	public static final String CLASS_TEXT_ENCODE = "CLIPTextEncode";
	public static final String CLASS_TEXT_ENCODE_ADVANCED = "SwarmTextEncodeAdvanced";
	public static final String CLASS_KSAMPLER = "SwarmKSampler";
	public static final String INPUT_NOISE_SEED = "noise_seed";
	public static final String INPUT_STEPS = "steps";
	public static final String INPUT_START_AT_STEP = "start_at_step";

	// ─────────────────────────────── inputs ───────────────────────────────

	/// A reference image for a stage: the tag name it fills (`[A-Za-z0-9_]+`; names must stay distinct
	/// once reduced to lowercase letters, {@link ComfyGraph#swarmKey}), the artifact it came from
	/// (provenance), and its bytes.
	public static final class Reference {
		private final String name;
		private final String artifactObjectId;
		private final byte[] png;

		public Reference(String name, String artifactObjectId, byte[] png) {
			if(png == null || png.length == 0) {
				throw new IllegalArgumentException("Reference " + name + " has no bytes");
			}
			ComfyGraph.tag(ComfyGraph.TYPE_IMAGE, name);
			this.name = name;
			this.artifactObjectId = artifactObjectId;
			this.png = png;
		}

		public String getName() { return name; }
		public String getArtifactObjectId() { return artifactObjectId; }
		public byte[] getPng() { return png; }
	}

	/// The actor an ACTOR_REGION stage repaints: the base image it paints over (and that image's
	/// artifact, for provenance) and the boxes the mask covers.
	public static final class ActorTarget {
		private final RenderPlan.Actor actor;
		private final byte[] baseImage;
		private final String baseArtifactObjectId;
		private final List<RenderPlan.Box> boxes;

		public ActorTarget(RenderPlan.Actor actor, byte[] baseImage, String baseArtifactObjectId, List<RenderPlan.Box> boxes) {
			if(baseImage == null || baseImage.length == 0) {
				throw new IllegalArgumentException("An actor target needs the base image bytes");
			}
			if(boxes == null || boxes.isEmpty()) {
				throw new IllegalArgumentException("An actor target needs at least one box");
			}
			this.actor = actor;
			this.baseImage = baseImage;
			this.baseArtifactObjectId = baseArtifactObjectId;
			this.boxes = Collections.unmodifiableList(new ArrayList<>(boxes));
		}

		public RenderPlan.Actor getActor() { return actor; }
		public byte[] getBaseImage() { return baseImage; }
		public String getBaseArtifactObjectId() { return baseArtifactObjectId; }
		public List<RenderPlan.Box> getBoxes() { return boxes; }
	}

	/// What {@link #compile} produces: the tagged graph, the inputs that fill it, the sampler's
	/// start step as the generator wrote it, the seed the render will use, and the generator request
	/// (images redacted) for the stage's requestJson.
	public static final class CompiledGraph {
		private final String stage;
		private final ComfyGraph graph;
		private final GraphInputs inputs;
		private final int steps;
		private final int startAtStep;
		private final long seed;
		private final Map<String, Object> generatorParams;
		private final Map<String, String> placeholderMatches;

		CompiledGraph(String stage, ComfyGraph graph, GraphInputs inputs, int steps, int startAtStep, long seed,
				Map<String, Object> generatorParams, Map<String, String> placeholderMatches) {
			this.stage = stage;
			this.graph = graph;
			this.inputs = inputs;
			this.steps = steps;
			this.startAtStep = startAtStep;
			this.seed = seed;
			this.generatorParams = Collections.unmodifiableMap(new LinkedHashMap<>(generatorParams));
			this.placeholderMatches = Collections.unmodifiableMap(new LinkedHashMap<>(placeholderMatches));
		}

		public String getStage() { return stage; }
		public ComfyGraph getGraph() { return graph; }
		public GraphInputs getInputs() { return inputs; }
		/// The sampler's `steps` literal.
		public int getSteps() { return steps; }
		/// The sampler's `start_at_step` literal (0 for BASE).
		public int getStartAtStep() { return startAtStep; }
		/// The concrete seed the inputs carry - chosen here when the request asked for a random one,
		/// because a raw render's PNG metadata does not report the graph's seed (§38.3).
		public long getSeed() { return seed; }
		/// The generator request with every image replaced by a description - safe to persist.
		public Map<String, Object> getGeneratorParams() { return generatorParams; }
		/// How each image slot was located in the generated graph: `base64` or `colour`.
		public Map<String, String> getPlaceholderMatches() { return placeholderMatches; }
	}

	// ─────────────────────────────── formula ───────────────────────────────

	/// Swarm's `start_at_step` for an init-image creativity: `round(steps * (1 - creativity))`, so 4
	/// steps at 0.6 start at 2, 25 at 0.6 start at 10, and creativity 1.0 starts at 0.
	public static int startAtStep(int steps, double creativity) {
		double c = Math.max(0.0, Math.min(1.0, creativity));
		return (int) Math.round(steps * (1.0 - c));
	}

	// ─────────────────────────────── compile ───────────────────────────────

	/// A BASE graph: the request's prompt, seed, size and sampler settings with the references as
	/// `promptimages`. Each reference becomes an image tag of its own name (the request key carries
	/// {@link ComfyGraph#IMAGE_KEY_MARKER}, so Swarm fills the tag with base64 rather than an upload).
	public CompiledGraph compileBase(StageRequest request, List<Reference> references, SwarmGraphClient client) {
		return compile(request, references, null, null, client);
	}

	/// An ACTOR_REGION graph: a masked init-image pass over the target's base image at full canvas
	/// size, with the references (when the family takes any) as `promptimages`. The init image fills
	/// the `init` tag and the mask the `mask` tag.
	public CompiledGraph compileActorRegion(StageRequest request, List<Reference> references, ActorTarget target,
			RendererCapabilities.InpaintParams inpaint, SwarmGraphClient client) {
		if(target == null || inpaint == null) {
			throw new IllegalArgumentException("ACTOR_REGION needs a target and inpaint parameters");
		}
		return compile(request, references, target, inpaint, client);
	}

	/// Generate-and-patch. `target` and `inpaint` are both null for BASE and both set for
	/// ACTOR_REGION. Throws {@link GraphCompileException} when the generator fails or its graph does
	/// not carry a slot the compiler must patch.
	public CompiledGraph compile(StageRequest request, List<Reference> references, ActorTarget target,
			RendererCapabilities.InpaintParams inpaint, SwarmGraphClient client) {
		if(request == null || client == null) {
			throw new IllegalArgumentException("A request and a client are required");
		}
		if((target == null) != (inpaint == null)) {
			throw new IllegalArgumentException("A target and inpaint parameters go together");
		}
		List<Reference> refs = (references == null ? List.of() : references);
		if(refs.size() > REFERENCE_COLOURS.length) {
			throw new GraphCompileException("At most " + REFERENCE_COLOURS.length + " references can be placed; " + refs.size() + " given");
		}
		String stage = (target == null ? RenderPlan.STAGE_BASE : RenderPlan.STAGE_ACTOR_REGION);
		int width = request.getWidth();
		int height = request.getHeight();
		int steps = (inpaint != null ? inpaint.getSteps() : request.getSteps());
		String positive = request.getPrompt().getPrompt();
		String negative = request.getPrompt().getNegative();
		boolean tagNegative = (negative != null && !negative.isEmpty());

		/// The inputs that fill the tags - built first so a reference name Swarm would confuse with
		/// another ({@link GraphInputs}) is refused before the generator is asked for anything.
		long seed = (request.getSeed() == StageRequest.SEED_RANDOM ? ThreadLocalRandom.current().nextLong(1L, Integer.MAX_VALUE) : request.getSeed());
		GraphInputs inputs = new GraphInputs();
		inputs.text(TAG_SCENE, positive);
		if(tagNegative) {
			inputs.text(TAG_NEGATIVE, negative);
		}
		inputs.seed(TAG_SEED, seed);
		List<String> artifactIds = new ArrayList<>();
		for(Reference r : refs) {
			inputs.image(r.getName(), r.getPng());
			if(r.getArtifactObjectId() != null) {
				artifactIds.add(r.getArtifactObjectId());
			}
		}
		if(target != null) {
			inputs.image(TAG_INIT, target.getBaseImage());
			inputs.image(TAG_MASK, MaskImage.rect(target.getBoxes(), width, height));
		}
		inputs.referenceArtifacts(artifactIds);

		/// Placeholders: one solid colour per image slot, at canvas size.
		Map<String, Placeholder> placeholders = new LinkedHashMap<>();
		List<String> promptImages = new ArrayList<>();
		for(int i = 0; i < refs.size(); i++) {
			Placeholder p = new Placeholder(refs.get(i).getName(), REFERENCE_COLOURS[i], width, height);
			placeholders.put(p.name, p);
			promptImages.add(p.dataUri);
		}
		Placeholder init = null;
		Placeholder mask = null;
		if(target != null) {
			init = new Placeholder(TAG_INIT, INIT_COLOUR, width, height);
			mask = new Placeholder(TAG_MASK, MASK_COLOUR, width, height);
			placeholders.put(init.name, init);
			placeholders.put(mask.name, mask);
		}

		/// The generator request: the stage's real parameters with markers where tags will go.
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("model", request.getModel());
		params.put("prompt", MARKER_SCENE);
		if(tagNegative) {
			params.put("negativeprompt", MARKER_NEGATIVE);
		}
		if(request.getSampler() != null) {
			params.put("sampler", request.getSampler());
		}
		if(request.getScheduler() != null) {
			params.put("scheduler", request.getScheduler());
		}
		params.put("images", Integer.valueOf(1));
		params.put("steps", Integer.valueOf(steps));
		params.put("seed", Long.valueOf(MARKER_SEED));
		params.put("width", Integer.valueOf(width));
		params.put("height", Integer.valueOf(height));
		params.put("cfgscale", Double.valueOf(request.getCfg()));
		params.put("refinercontrolpercentage", Double.valueOf(0.0));
		if(!promptImages.isEmpty()) {
			params.put("promptimages", promptImages);
		}
		if(target != null) {
			params.put("initimage", init.dataUri);
			params.put("maskimage", mask.dataUri);
			params.put("initimagecreativity", Double.valueOf(inpaint.getCreativity()));
			params.put("maskblur", Integer.valueOf(inpaint.getMaskBlur()));
			params.put("maskgrow", Integer.valueOf(inpaint.getMaskGrow()));
			params.put("initimagerecompositemask", Boolean.TRUE);
		}

		ComfyGraph graph = client.generatedWorkflow(params);
		logger.info("Generator produced " + graph.size() + " nodes for stage " + stage);

		/// Patch 1: image slots.
		Map<String, String> matches = patchImages(graph, placeholders);
		/// Patch 2: the seed.
		patchSeed(graph);
		/// Patch 3: the prompts.
		patchPrompts(graph, tagNegative);
		/// Patch 4: the save node.
		patchSave(graph);

		/// Literals the graph must agree on.
		int graphSteps = samplerInt(graph, INPUT_STEPS, steps);
		if(graphSteps != steps) {
			throw new GraphCompileException("Sampler steps " + graphSteps + " differ from the requested " + steps);
		}
		int startAt = samplerInt(graph, INPUT_START_AT_STEP, 0);
		if(inpaint != null) {
			int predicted = startAtStep(steps, inpaint.getCreativity());
			if(predicted != startAt) {
				logger.warn("Generator wrote start_at_step " + startAt + " where round(" + steps + " * (1 - " + inpaint.getCreativity() + ")) = " + predicted + "; keeping the generator's");
			}
		}

		List<GraphProblem> problems = NodeCatalogue.errors(NodeCatalogue.validateStructure(graph, inputs.names()));
		if(!problems.isEmpty()) {
			throw new GraphCompileException("Patched graph is not well-formed: " + NodeCatalogue.describe(problems));
		}

		Map<String, Object> redactedParams = new LinkedHashMap<>(params);
		if(!promptImages.isEmpty()) {
			List<String> desc = new ArrayList<>();
			for(Reference r : refs) {
				desc.add("<placeholder " + placeholders.get(r.getName()).describe() + ">");
			}
			redactedParams.put("promptimages", desc);
		}
		if(target != null) {
			redactedParams.put("initimage", "<placeholder " + init.describe() + ">");
			redactedParams.put("maskimage", "<placeholder " + mask.describe() + ">");
		}
		return new CompiledGraph(stage, graph, inputs, steps, startAt, seed, redactedParams, matches);
	}

	// ─────────────────────────────── patches ───────────────────────────────

	private static final class Placeholder {
		final String name;
		final Color colour;
		final byte[] png;
		final String base64;
		final String dataUri;
		final int width;
		final int height;

		Placeholder(String name, Color colour, int width, int height) {
			this.name = name;
			this.colour = colour;
			this.width = width;
			this.height = height;
			this.png = MaskImage.solid(width, height, colour);
			this.base64 = Base64.getEncoder().encodeToString(png);
			this.dataUri = "data:image/png;base64," + base64;
		}

		String describe() {
			return name + " rgb(" + colour.getRed() + "," + colour.getGreen() + "," + colour.getBlue() + ") " + width + "x" + height;
		}
	}

	/// Every SwarmLoadImageB64 node must be one of the placeholders; each placeholder must be found.
	private static Map<String, String> patchImages(ComfyGraph graph, Map<String, Placeholder> placeholders) {
		Map<String, String> matches = new LinkedHashMap<>();
		for(String id : graph.nodesOf(ComfyGraph.CLASS_LOAD_IMAGE_B64)) {
			Object v = graph.inputs(id).get(ComfyGraph.INPUT_IMAGE_BASE64);
			if(!(v instanceof String)) {
				throw new GraphCompileException("Node " + id + " SwarmLoadImageB64 carries no image_base64 string");
			}
			String b64 = stripDataUri((String) v);
			Placeholder hit = null;
			String how = null;
			for(Placeholder p : placeholders.values()) {
				if(p.base64.equals(b64)) {
					hit = p;
					how = "base64";
					break;
				}
			}
			if(hit == null) {
				Color c = decodeColour(b64);
				if(c != null) {
					int best = Integer.MAX_VALUE;
					for(Placeholder p : placeholders.values()) {
						int d = Math.abs(c.getRed() - p.colour.getRed()) + Math.abs(c.getGreen() - p.colour.getGreen()) + Math.abs(c.getBlue() - p.colour.getBlue());
						if(d < best) {
							best = d;
							hit = p;
						}
					}
					if(best > COLOUR_TOLERANCE) {
						hit = null;
					}
					how = "colour";
				}
			}
			if(hit == null) {
				throw new GraphCompileException("Node " + id + " SwarmLoadImageB64 matches no placeholder image");
			}
			if(matches.containsKey(hit.name)) {
				throw new GraphCompileException("Placeholder " + hit.name + " appears in more than one node (" + matches.get(hit.name) + ", " + id + ")");
			}
			graph.setInput(id, ComfyGraph.INPUT_IMAGE_BASE64, ComfyGraph.tag(ComfyGraph.TYPE_IMAGE, hit.name));
			matches.put(hit.name, how + ":" + id);
		}
		for(Placeholder p : placeholders.values()) {
			if(!matches.containsKey(p.name)) {
				throw new GraphCompileException("Generated graph carries no node for image " + p.name + " - the family may not take it");
			}
		}
		return matches;
	}

	private static void patchSeed(ComfyGraph graph) {
		int hits = 0;
		for(String id : graph.nodesOf(CLASS_KSAMPLER)) {
			Object v = graph.inputs(id).get(INPUT_NOISE_SEED);
			if(v instanceof Number && ((Number) v).longValue() == MARKER_SEED) {
				graph.setInput(id, INPUT_NOISE_SEED, ComfyGraph.tag(ComfyGraph.TYPE_SEED, TAG_SEED));
				hits++;
			}
		}
		if(hits == 0) {
			throw new GraphCompileException("No " + CLASS_KSAMPLER + " carries the seed marker " + MARKER_SEED);
		}
	}

	private static void patchPrompts(ComfyGraph graph, boolean tagNegative) {
		int scene = 0;
		int neg = 0;
		for(String id : graph.nodeIds()) {
			Map<String, Object> in = graph.inputs(id);
			for(Map.Entry<String, Object> e : new ArrayList<>(in.entrySet())) {
				if(!(e.getValue() instanceof String)) {
					continue;
				}
				String s = (String) e.getValue();
				if(s.contains(MARKER_SCENE)) {
					in.put(e.getKey(), s.replace(MARKER_SCENE, ComfyGraph.tag(ComfyGraph.TYPE_TEXT, TAG_SCENE)));
					scene++;
				}
				else if(s.contains(MARKER_NEGATIVE)) {
					in.put(e.getKey(), s.replace(MARKER_NEGATIVE, ComfyGraph.tag(ComfyGraph.TYPE_TEXT, TAG_NEGATIVE)));
					neg++;
				}
			}
		}
		if(scene == 0) {
			throw new GraphCompileException("No node carries the scene prompt marker");
		}
		if(tagNegative && neg == 0) {
			throw new GraphCompileException("No node carries the negative prompt marker");
		}
	}

	/// Replace every save node with one non-numeric {@link ComfyGraph#SAVE_FINAL} wired like the
	/// first (Swarm returns images only for non-numeric save ids, §38.1).
	private static void patchSave(ComfyGraph graph) {
		List<String> saves = graph.saveNodeIds();
		if(saves.isEmpty()) {
			throw new GraphCompileException("Generated graph has no save node");
		}
		Map<String, Object> wiring = new LinkedHashMap<>(graph.inputs(saves.get(0)));
		String classType = graph.classType(saves.get(0));
		for(String id : saves) {
			graph.removeNode(id);
		}
		graph.addNode(ComfyGraph.SAVE_FINAL, classType, wiring);
	}

	private static int samplerInt(ComfyGraph graph, String input, int fallback) {
		for(String id : graph.nodesOf(CLASS_KSAMPLER)) {
			Object v = graph.inputs(id).get(input);
			if(v instanceof Number) {
				return ((Number) v).intValue();
			}
		}
		return fallback;
	}

	static String stripDataUri(String s) {
		if(s != null && s.startsWith("data:")) {
			int comma = s.indexOf(',');
			if(comma >= 0) {
				return s.substring(comma + 1);
			}
		}
		return s;
	}

	private static Color decodeColour(String base64) {
		try {
			return MaskImage.cornerColor(Base64.getDecoder().decode(base64));
		}
		catch(IllegalArgumentException e) {
			return null;
		}
	}
}
