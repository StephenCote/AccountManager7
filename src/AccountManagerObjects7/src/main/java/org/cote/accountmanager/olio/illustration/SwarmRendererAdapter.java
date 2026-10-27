package org.cote.accountmanager.olio.illustration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWImageInfo;
import org.cote.accountmanager.olio.sd.swarm.SWImageResponse;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.cote.accountmanager.util.JSONUtil;

/// SwarmUI adapter (PictureBook V3 design §15, §38.1): one call per stage - txt2img against Swarm's
/// parameter API, or the raw-graph post when the request carries a compiled graph (§38) - bytes back,
/// nothing persisted. The timed window is the generate call only, so the calibration sample (§18)
/// excludes image downloads and decoding. Errors never carry the server URL.
public final class SwarmRendererAdapter implements RendererAdapter {

	private static final Logger logger = LogManager.getLogger(SwarmRendererAdapter.class);

	private final SDAPIEnumType apiType;
	private final String server;
	private final SDUtil sdu;

	public SwarmRendererAdapter(SDAPIEnumType apiType, String server) {
		this(new SDUtil(apiType, server));
	}

	/// The caller's SDUtil is the transport: its apiType/server name the renderer, and a subclass that
	/// overrides txt2img (test capture) sees every stage request this adapter sends.
	public SwarmRendererAdapter(SDUtil sdu) {
		this.apiType = sdu.getApiType();
		this.server = sdu.getServer();
		this.sdu = sdu;
	}

	public String getServer() {
		return server;
	}

	@Override
	public RendererCapabilities capabilities(String model) {
		return RendererCapabilities.forModel(model);
	}

	@Override
	public List<String> listModels() {
		ModelInventory inv = SwarmModelInventory.forServer(apiType, server);
		return (inv.error() == null ? inv.installedModels() : null);
	}

	@Override
	public StageResult execute(StageRequest request, CancelToken cancel) {
		CancelToken token = (cancel == null ? CancelToken.NONE : cancel);
		if(token.isCancelled()) {
			return StageResult.failed(ERROR_CANCELLED, 0L, null);
		}
		if(request.isGraph()) {
			return executeGraph(request, token);
		}
		SWTxt2Img s2i = toSwarm(request);
		/// Exported before txt2img mints the session so the artifact never carries a session id.
		String requestJson = JSONUtil.exportObject(s2i);

		long start = System.nanoTime();
		SWImageResponse resp = null;
		try {
			resp = sdu.txt2img(s2i);
		}
		catch(RuntimeException e) {
			logger.error("txt2img threw for stage " + request.getStage() + ": " + e.getMessage());
		}
		long durationMs = (System.nanoTime() - start) / 1_000_000L;

		if(resp == null) {
			return StageResult.failed(ERROR_UNREACHABLE, durationMs, requestJson);
		}
		if(resp.getError() != null && !resp.getError().isEmpty()) {
			return StageResult.failed(ERROR_PREFIX_BACKEND + resp.getError(), durationMs, requestJson);
		}
		if(resp.getImages() == null || resp.getImages().isEmpty()) {
			return StageResult.failed(ERROR_NO_IMAGES, durationMs, requestJson);
		}

		StageResult.Builder out = StageResult.builder().durationMs(durationMs).requestJson(requestJson);
		int previousSeed = StageImage.SEED_UNKNOWN;
		for(String path : resp.getImages()) {
			byte[] bytes = sdu.fetchImageBytes(path);
			if(bytes == null || bytes.length == 0) {
				logger.warn("Could not retrieve image bytes for stage " + request.getStage() + " (" + path + ")");
				continue;
			}
			int seed = StageImage.SEED_UNKNOWN;
			boolean reported = false;
			try {
				SWImageInfo info = SWUtil.extractInfo(bytes);
				if(info != null && info.getImageParams() != null) {
					seed = info.getImageParams().getSeed();
					reported = true;
				}
			}
			catch(Exception e) {
				logger.warn("Could not read image metadata for seed: " + e.getMessage());
			}
			if(!reported && previousSeed != StageImage.SEED_UNKNOWN) {
				seed = previousSeed + 1;
			}
			int[] dims = ImageDims.decode(bytes);
			out.image(new StageImage(bytes, seed, reported, dims[0], dims[1]));
			previousSeed = seed;
		}
		StageResult result = out.build();
		if(result.isOk()) {
			RendererCapabilities caps = capabilities(request.getModel());
			if(caps != null) {
				RenderCalibration.record(server, caps.getPattern(), durationMs, request.getSteps(), request.getWidth(), request.getHeight());
			}
			logger.info("Stage " + request.getStage() + " rendered " + result.getImages().size() + " image(s) in " + durationMs + " ms");
		}
		return result;
	}

	// ─────────────────────────────── raw graph (§38) ───────────────────────────────

	/// Post a compiled graph. Before the GPU: the graph is checked against the server's node catalogue
	/// (GRAPH_INVALID, node-qualified, no HTTP). After: Swarm's `unsupported node type` style refusals
	/// are RENDERER_REJECTED (node-qualified when the text names one) and drop the catalogue so the next
	/// render refetches it. The seed on each image is the seed the inputs carry, because a raw render's
	/// PNG metadata reflects the request defaults, not the graph (§38.3). Calibration for a graph stage
	/// is not folded here: the executor records it per stage through PbCalibrationUtil.recordSample,
	/// which also persists it as the requesting user (P5-2 §4.4), so one render is one sample.
	private StageResult executeGraph(StageRequest request, CancelToken token) {
		ComfyGraph graph = request.getGraph();
		GraphInputs inputs = request.getInputs();
		SwarmGraphClient client = new SwarmGraphClient(sdu);
		String graphHash = graph.graphHash();
		String requestJson = graphRequestJson(request, graphHash);

		NodeCatalogue catalogue = NodeCatalogue.forServer(server, client::objectInfoJson);
		List<GraphProblem> problems;
		if(catalogue != null) {
			problems = catalogue.validate(graph, inputs.names());
		}
		else {
			logger.warn("No node catalogue for stage " + request.getStage() + "; structural checks only");
			problems = NodeCatalogue.validateStructure(graph, inputs.names());
		}
		List<GraphProblem> errors = NodeCatalogue.errors(problems);
		if(!errors.isEmpty()) {
			return StageResult.builder().error(graphInvalid(graph, errors.get(0)))
				.errorDetail(errors.size() > 1 ? NodeCatalogue.describe(errors.subList(1, errors.size())) : null)
				.durationMs(0L).requestJson(requestJson).build();
		}
		if(problems.size() > errors.size()) {
			logger.info("Graph for stage " + request.getStage() + " has " + (problems.size() - errors.size()) + " warning(s): " + NodeCatalogue.describe(problems));
		}

		SwarmGraphClient.RawResponse resp;
		try {
			resp = client.generateRaw(graph, inputs, request.getModel(), request.getImages(), token);
		}
		catch(RuntimeException e) {
			logger.error("Raw generate threw for stage " + request.getStage() + ": " + e.getMessage());
			return StageResult.failed(ERROR_UNREACHABLE, 0L, requestJson);
		}
		long durationMs = resp.getDurationMs();
		if(resp.isUnreachable()) {
			return StageResult.failed(ERROR_UNREACHABLE, durationMs, requestJson);
		}
		if(resp.getError() != null) {
			if(ERROR_CANCELLED.equals(resp.getError())) {
				return StageResult.failed(ERROR_CANCELLED, durationMs, requestJson);
			}
			if(isGraphRejection(resp.getError())) {
				NodeCatalogue.invalidate(server);
				return StageResult.failed(ERROR_RENDERER_REJECTED + ": " + SwarmGraphClient.parseNodeFailure(resp.getError(), graph), durationMs, requestJson);
			}
			return StageResult.failed(ERROR_PREFIX_BACKEND + resp.getError(), durationMs, requestJson);
		}
		if(resp.getImagePaths().isEmpty()) {
			return StageResult.failed(ERROR_NO_IMAGES, durationMs, requestJson);
		}

		Long seedValue = inputs.seed(WorkflowCompiler.TAG_SEED);
		int seed = (seedValue == null ? StageImage.SEED_UNKNOWN : seedValue.intValue());
		boolean seedReported = (seedValue != null);
		StageResult.Builder out = StageResult.builder().durationMs(durationMs).requestJson(requestJson)
			.backendGraph(backendGraphJson(request.getStage(), graphHash, client.swarmVersion()));
		for(String path : resp.getImagePaths()) {
			byte[] bytes = client.fetchImage(path);
			if(bytes == null || bytes.length == 0) {
				logger.warn("Could not retrieve image bytes for stage " + request.getStage() + " (" + path + ")");
				continue;
			}
			int[] dims = ImageDims.decode(bytes);
			out.image(new StageImage(bytes, seed, seedReported, dims[0], dims[1]));
		}
		StageResult result = out.build();
		if(result.isOk()) {
			logger.info("Stage " + request.getStage() + " (graph " + graphHash.substring(0, 12) + ") rendered " + result.getImages().size() + " image(s) in " + durationMs + " ms");
		}
		return result;
	}

	/// "GRAPH_INVALID: node <id> <class> <code>: <message>" for a node-bound problem; "GRAPH_INVALID: <code>: <message>" otherwise.
	static String graphInvalid(ComfyGraph graph, GraphProblem p) {
		StringBuilder sb = new StringBuilder(ERROR_GRAPH_INVALID).append(": ");
		if(p.getNodeId() != null) {
			sb.append("node ").append(p.getNodeId());
			String ct = (graph == null ? null : graph.classType(p.getNodeId()));
			if(ct != null && !ct.isBlank()) {
				sb.append(' ').append(ct);
			}
			sb.append(' ');
		}
		return sb.append(p.getCode()).append(": ").append(p.getMessage()).toString();
	}

	/// The stage's request record: what was sent minus the graph body and the images (both are
	/// artifacts of their own), so it is safe to persist as is.
	static String graphRequestJson(StageRequest request, String graphHash) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("stage", request.getStage());
		m.put("model", request.getModel());
		m.put("images", Integer.valueOf(request.getImages()));
		m.put("graphHash", graphHash);
		m.put("inputs", request.getInputs().redacted());
		m.put("referenceArtifactIds", request.getInputs().getReferenceArtifactIds());
		return JSONUtil.exportObject(m);
	}

	static String backendGraphJson(String stage, String graphHash, String swarmVersion) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("stage", stage);
		m.put("graphHash", graphHash);
		m.put("saveNodeId", ComfyGraph.SAVE_FINAL);
		m.put("swarmVersion", swarmVersion);
		return JSONUtil.exportObject(m);
	}

	/// Swarm refusing the graph itself (an unknown node type, a malformed workflow) rather than a
	/// generation failure. A session rejection that survived the client's one retry is not one.
	static boolean isGraphRejection(String error) {
		if(error == null || SwarmGraphClient.isSessionError(error)) {
			return false;
		}
		String s = error.toLowerCase();
		return s.contains("unsupported node") || s.contains("node type") || s.contains("workflow") || s.contains("invalid") || s.contains("comfy");
	}

	/// The Swarm request for a stage. BASE never runs the refiner; UPSCALE runs the config's refiner
	/// block only when the config names a refinerModel (mirrors SDUtil.createImage's hires handling).
	static SWTxt2Img toSwarm(StageRequest request) {
		SWTxt2Img s2i = new SWTxt2Img();
		CompiledPrompt cp = request.getPrompt();
		s2i.setPrompt(cp.getPrompt());
		if(!cp.getNegative().isEmpty()) {
			s2i.setNegativePrompt(cp.getNegative());
		}
		s2i.setSteps(request.getSteps());
		s2i.setModel(request.getModel());
		if(request.getScheduler() != null) {
			s2i.setScheduler(request.getScheduler());
		}
		if(request.getSampler() != null) {
			s2i.setSampler(request.getSampler());
		}
		s2i.setCfgScale(request.getCfg());
		s2i.setSeed(request.getSeed());
		s2i.setImages(request.getImages());
		s2i.setWidth(request.getWidth());
		s2i.setHeight(request.getHeight());

		Map<String, Object> cfg = request.getConfig();
		String refinerModel = str(cfg, "refinerModel");
		boolean useRefiner = request.isHires() && refinerModel != null && !refinerModel.isBlank();
		if(request.isHires() && !useRefiner) {
			logger.warn("Stage " + request.getStage() + " asked for hires but the config names no refinerModel; skipping refiner");
		}
		if(useRefiner) {
			s2i.setRefinerModel(refinerModel);
			s2i.setRefinerScheduler(str(cfg, "refinerScheduler"));
			s2i.setRefinerSampler(str(cfg, "refinerSampler"));
			s2i.setRefinerMethod(str(cfg, "refinerMethod"));
			s2i.setRefinerSteps(intOrNull(cfg, "refinerSteps"));
			s2i.setRefinerUpscale(intOrNull(cfg, "refinerUpscale"));
			s2i.setRefinerUpscaleMethod(str(cfg, "refinerUpscaleMethod"));
			s2i.setRefinerCfgScale(intOrNull(cfg, "refinerCfg"));
			s2i.setRefinerControlPercentage(dbl(cfg, "refinerControlPercentage", 0.2));
		}
		else {
			s2i.setRefinerControlPercentage(0.0);
		}
		return s2i;
	}

	private static String str(Map<String, Object> m, String key) {
		Object v = (m == null ? null : m.get(key));
		return (v == null ? null : v.toString());
	}

	private static Integer intOrNull(Map<String, Object> m, String key) {
		Object v = (m == null ? null : m.get(key));
		return (v instanceof Number ? ((Number) v).intValue() : null);
	}

	private static double dbl(Map<String, Object> m, String key, double def) {
		Object v = (m == null ? null : m.get(key));
		return (v instanceof Number ? ((Number) v).doubleValue() : def);
	}
}
