package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.util.ClientUtil;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.core.MediaType;

/// The three Swarm calls a raw graph needs (PictureBook V3 design §38.1-§38.3): the generator
/// (`ComfyGetGeneratedWorkflow`, which turns ordinary T2I params into the graph Swarm would run), the
/// raw post (`GenerateText2Image` with `comfyworkflowraw`), and the backend's `object_info`. The
/// session is {@link SDUtil}'s cached anonymous session, so a graph render and a plain txt2img share
/// one session per server; a session Swarm rejects is dropped and the call retried once.
///
/// Request bodies are serialised here with a mapper that keeps nulls - a generated graph carries
/// `lora_hooks: null`, and the request has to say what the graph says. Responses are read as raw text
/// and parsed as maps, because Swarm reports generation failures as `{"error": "..."}` with HTTP 200.
public class SwarmGraphClient {

	private static final Logger logger = LogManager.getLogger(SwarmGraphClient.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() { };

	public static final String PATH_GENERATED_WORKFLOW = "/API/ComfyGetGeneratedWorkflow";
	public static final String PATH_GENERATE = "/API/GenerateText2Image";
	public static final String PATH_OBJECT_INFO = "/ComfyBackendDirect/object_info";
	public static final String PATH_SYSTEM_STATS = "/ComfyBackendDirect/system_stats";
	public static final String KEY_SESSION = "session_id";
	public static final String KEY_MODEL = "model";
	public static final String KEY_IMAGES = "images";
	/// Swarm's node-failure marker: "(Node 7: KSampler)"; the class may carry spaces.
	static final Pattern NODE_FAILURE = Pattern.compile("\\(Node (\\d+): ([^)]+)\\)");

	/// What the raw post returned: image paths to fetch, or an error string, or neither when the server
	/// gave no usable response at all (unreachable).
	public static final class RawResponse {
		private final List<String> imagePaths;
		private final String error;
		private final long durationMs;
		private final boolean unreachable;

		RawResponse(List<String> imagePaths, String error, long durationMs, boolean unreachable) {
			this.imagePaths = Collections.unmodifiableList(new ArrayList<>(imagePaths == null ? List.of() : imagePaths));
			this.error = error;
			this.durationMs = durationMs;
			this.unreachable = unreachable;
		}

		public List<String> getImagePaths() { return imagePaths; }
		public String getError() { return error; }
		public long getDurationMs() { return durationMs; }
		public boolean isUnreachable() { return unreachable; }
		public boolean failed() { return unreachable || error != null; }
	}

	private final SDUtil sdu;

	public SwarmGraphClient(SDUtil sdu) {
		if(sdu == null) {
			throw new IllegalArgumentException("SDUtil is required");
		}
		this.sdu = sdu;
	}

	public String getServer() {
		return sdu.getServer();
	}

	public String swarmVersion() {
		return sdu.swarmVersion();
	}

	// ─────────────────────────────── generator ───────────────────────────────

	/// The graph Swarm would run for ordinary T2I params (design §38.2 generate-and-patch). The params
	/// are the flat `GenerateText2Image` keys; `session_id` is added here. Throws
	/// {@link GraphCompileException} when the server answers with an error or no workflow.
	public ComfyGraph generatedWorkflow(Map<String, Object> params) {
		Map<String, Object> resp = postWithSession(PATH_GENERATED_WORKFLOW, params);
		if(resp == null) {
			throw new GraphCompileException("Generator returned no response from " + PATH_GENERATED_WORKFLOW);
		}
		Object err = resp.get("error");
		if(err != null) {
			throw new GraphCompileException("Generator error: " + err);
		}
		Object wf = resp.get("workflow");
		if(wf == null) {
			throw new GraphCompileException("Generator response carried no workflow: " + resp.keySet());
		}
		String json;
		if(wf instanceof String) {
			json = (String) wf;
		}
		else {
			try {
				json = MAPPER.writeValueAsString(wf);
			}
			catch(Exception e) {
				throw new GraphCompileException("Generator workflow did not serialise: " + e.getMessage(), e);
			}
		}
		try {
			return ComfyGraph.fromJson(json);
		}
		catch(IllegalArgumentException e) {
			throw new GraphCompileException("Generator workflow did not parse as a graph: " + e.getMessage(), e);
		}
	}

	// ─────────────────────────────── raw post ───────────────────────────────

	/// Post a compiled graph. The body is `model`, `images`, the inputs as request keys (images as data
	/// URIs), `comfyworkflowraw` as the compact graph JSON, and `session_id`. Never throws for a backend
	/// failure - see {@link RawResponse}.
	public RawResponse generateRaw(ComfyGraph graph, GraphInputs inputs, String model, int images, CancelToken cancel) {
		Map<String, Object> body = rawBody(graph, inputs, model, images);
		long start = System.currentTimeMillis();
		if(cancel != null && cancel.isCancelled()) {
			return new RawResponse(null, RendererAdapter.ERROR_CANCELLED, 0L, false);
		}
		Map<String, Object> resp = postWithSession(PATH_GENERATE, body);
		long dur = System.currentTimeMillis() - start;
		if(resp == null) {
			return new RawResponse(null, null, dur, true);
		}
		Object err = resp.get("error");
		if(err != null) {
			return new RawResponse(null, String.valueOf(err), dur, false);
		}
		List<String> paths = new ArrayList<>();
		Object imgs = resp.get(KEY_IMAGES);
		if(imgs instanceof List) {
			for(Object o : (List<?>) imgs) {
				if(o != null) {
					paths.add(String.valueOf(o));
				}
			}
		}
		return new RawResponse(paths, null, dur, false);
	}

	/// The raw post body before `session_id`: `model`, `images` (at least 1), the inputs as request keys
	/// with images as data URIs, and the compact graph under `comfyworkflowraw`.
	static Map<String, Object> rawBody(ComfyGraph graph, GraphInputs inputs, String model, int images) {
		if(graph == null || inputs == null) {
			throw new IllegalArgumentException("Graph and inputs are required");
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put(KEY_MODEL, model);
		body.put(KEY_IMAGES, Integer.valueOf(images < 1 ? 1 : images));
		body.putAll(inputs.toRequestMap());
		body.put(ComfyGraph.PARAM_RAW, graph.toJson());
		return body;
	}

	/// Bytes of an image the raw post named, by the relative path Swarm returned.
	public byte[] fetchImage(String relativePath) {
		return sdu.fetchImageBytes(relativePath);
	}

	// ─────────────────────────────── backend info ───────────────────────────────

	/// The full `object_info` body (every node type), or null when the backend did not answer.
	public String objectInfoJson() {
		return getText(PATH_OBJECT_INFO);
	}

	/// `object_info` for one node type, or null.
	public String objectInfoJson(String classType) {
		if(classType == null || classType.isBlank()) {
			return objectInfoJson();
		}
		return getText(PATH_OBJECT_INFO + "/" + classType);
	}

	/// The backend's `system_stats`, or null.
	public Map<String, Object> systemStats() {
		String text = getText(PATH_SYSTEM_STATS);
		return (text == null ? null : parse(text));
	}

	// ─────────────────────────────── transport ───────────────────────────────

	/// Post a body with the cached session; when Swarm rejects the session, mint a new one and post
	/// once more. Null when the server gave no parseable answer.
	private Map<String, Object> postWithSession(String path, Map<String, Object> body) {
		Map<String, Object> resp = postOnce(path, body);
		if(resp != null && isSessionError(resp.get("error"))) {
			logger.warn("Swarm rejected the session for " + path + "; minting a new one and retrying once");
			sdu.dropSession();
			resp = postOnce(path, body);
		}
		return resp;
	}

	private Map<String, Object> postOnce(String path, Map<String, Object> body) {
		String session = sdu.session();
		if(session == null) {
			logger.error("No Swarm session for " + path);
			return null;
		}
		Map<String, Object> sent = new LinkedHashMap<>(body);
		sent.put(KEY_SESSION, session);
		String json;
		try {
			json = MAPPER.writeValueAsString(sent);
		}
		catch(Exception e) {
			throw new IllegalStateException("Request did not serialise: " + e.getMessage(), e);
		}
		String text = null;
		try {
			text = ClientUtil.postJSON(String.class, ClientUtil.getResource(sdu.getServer() + path), json, MediaType.APPLICATION_JSON_TYPE);
		}
		catch(Exception e) {
			logger.error("POST " + path + " failed: " + e.getMessage());
			return null;
		}
		if(text == null || text.isBlank()) {
			return null;
		}
		return parse(text);
	}

	private String getText(String path) {
		try {
			return ClientUtil.get(String.class, ClientUtil.getResource(sdu.getServer() + path), null, MediaType.APPLICATION_JSON_TYPE);
		}
		catch(Exception e) {
			logger.error("GET " + path + " failed: " + e.getMessage());
			return null;
		}
	}

	private static Map<String, Object> parse(String text) {
		try {
			return MAPPER.readValue(text, MAP_TYPE);
		}
		catch(Exception e) {
			logger.error("Swarm response did not parse as JSON: " + e.getMessage());
			return null;
		}
	}

	static boolean isSessionError(Object err) {
		if(err == null) {
			return false;
		}
		String s = String.valueOf(err).toLowerCase();
		return s.contains("session");
	}

	/// Swarm reports a ComfyUI node failure as "... (Node <id>: <class_type>) ..." (P5-2 §6.2). When the
	/// text matches, the result is "node <id> <class> (<role>): <text>"; the role is the placeholder tag(s)
	/// the failing node carries in the compiled graph (scene, negative, seed, init, mask) and is omitted
	/// when the graph is unknown or the node carries none. No match: the text as given. Never includes a
	/// session id or request JSON - neither is in the text Swarm returns.
	public static String parseNodeFailure(String text, ComfyGraph graph) {
		if(text == null) {
			return "";
		}
		Matcher m = NODE_FAILURE.matcher(text);
		if(!m.find()) {
			return text;
		}
		String id = m.group(1);
		String role = null;
		if(graph != null) {
			Set<String> tags = graph.tagsOf(id);
			if(!tags.isEmpty()) {
				List<String> names = new ArrayList<>();
				for(String t : tags) {
					names.add(ComfyGraph.tagName(t));
				}
				role = String.join(",", names);
			}
		}
		return "node " + id + " " + m.group(2).trim() + (role != null ? " (" + role + ")" : "") + ": " + text;
	}

	public static String parseNodeFailure(String text) {
		return parseNodeFailure(text, null);
	}
}
