package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/// What a Comfy backend's `/ComfyBackendDirect/object_info` says each node type accepts (design §38.1):
/// per class_type, the required and optional input names. {@link #validate} runs a compiled graph
/// against it before the request leaves the JVM, so an unknown node type or a missing required input
/// is GRAPH_INVALID here rather than a GPU-side rejection. An input the catalogue does not list is a
/// warning only - Swarm's own generated graphs carry inputs object_info omits (`lora_hooks`, `images`,
/// `minimax_refs` on SwarmTextEncodeAdvanced), and the backend tolerates them.
///
/// Cached per server for {@link #TTL_MS}; a fetch failure is never cached. {@link #invalidate} is for
/// the one case the cache can be wrong: Swarm rejecting a node type the catalogue listed (a backend
/// restart with a node pack removed), after which the next render refetches.
public final class NodeCatalogue {

	private static final Logger logger = LogManager.getLogger(NodeCatalogue.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() { };

	public static final long TTL_MS = 3_600_000L;

	private static final ConcurrentHashMap<String, NodeCatalogue> CACHE = new ConcurrentHashMap<>();

	/// One node type's input names.
	public static final class NodeType {
		private final String name;
		private final Set<String> required;
		private final Set<String> optional;

		NodeType(String name, Set<String> required, Set<String> optional) {
			this.name = name;
			this.required = Collections.unmodifiableSet(new LinkedHashSet<>(required));
			this.optional = Collections.unmodifiableSet(new LinkedHashSet<>(optional));
		}

		public String getName() { return name; }
		public Set<String> getRequired() { return required; }
		public Set<String> getOptional() { return optional; }
		public boolean accepts(String input) { return required.contains(input) || optional.contains(input); }
	}

	private final String server;
	private final long fetchedAt;
	private final Map<String, NodeType> types;

	private NodeCatalogue(String server, long fetchedAt, Map<String, NodeType> types) {
		this.server = server;
		this.fetchedAt = fetchedAt;
		this.types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
	}

	// ─────────────────────────────── construction ───────────────────────────────

	/// Parse an object_info body: `{<type>: {input: {required: {name: [...]}, optional: {name: [...]}}, ...}}`.
	/// Any top-level entry without an `input` object is skipped.
	public static NodeCatalogue fromJson(String objectInfoJson) {
		return fromJson(null, objectInfoJson);
	}

	public static NodeCatalogue fromJson(String server, String objectInfoJson) {
		if(objectInfoJson == null || objectInfoJson.isBlank()) {
			throw new IllegalArgumentException("object_info JSON is empty");
		}
		Map<String, Object> parsed;
		try {
			parsed = MAPPER.readValue(objectInfoJson, MAP_TYPE);
		}
		catch(Exception e) {
			throw new IllegalArgumentException("object_info JSON did not parse: " + e.getMessage(), e);
		}
		return fromMap(server, parsed);
	}

	@SuppressWarnings("unchecked")
	public static NodeCatalogue fromMap(String server, Map<String, Object> objectInfo) {
		Map<String, NodeType> types = new LinkedHashMap<>();
		for(Map.Entry<String, Object> e : objectInfo.entrySet()) {
			if(!(e.getValue() instanceof Map)) continue;
			Object input = ((Map<String, Object>) e.getValue()).get("input");
			if(!(input instanceof Map)) continue;
			Map<String, Object> in = (Map<String, Object>) input;
			types.put(e.getKey(), new NodeType(e.getKey(), keysOf(in.get("required")), keysOf(in.get("optional"))));
		}
		return new NodeCatalogue(server, System.currentTimeMillis(), types);
	}

	@SuppressWarnings("unchecked")
	private static Set<String> keysOf(Object o) {
		return (o instanceof Map ? ((Map<String, Object>) o).keySet() : Collections.emptySet());
	}

	/// The cached catalogue for a server, fetched through {@code fetcher} (an object_info body) when
	/// absent or older than {@link #TTL_MS}. Null when the fetch returns nothing or does not parse;
	/// that outcome is not cached.
	public static NodeCatalogue forServer(String server, Supplier<String> fetcher) {
		String key = (server == null ? "" : server.trim());
		NodeCatalogue c = CACHE.get(key);
		if(c != null && System.currentTimeMillis() - c.fetchedAt < TTL_MS) {
			return c;
		}
		String json = null;
		try {
			json = fetcher.get();
		}
		catch(RuntimeException e) {
			logger.warn("object_info fetch threw: " + e.getMessage());
		}
		if(json == null || json.isBlank()) {
			logger.warn("No object_info from the renderer; graph validation is skipped for this render");
			return null;
		}
		try {
			c = fromJson(key, json);
		}
		catch(IllegalArgumentException e) {
			logger.warn("object_info did not parse: " + e.getMessage());
			return null;
		}
		CACHE.put(key, c);
		logger.info("Node catalogue for the renderer holds " + c.types.size() + " node types");
		return c;
	}

	public static void invalidate(String server) {
		CACHE.remove(server == null ? "" : server.trim());
	}

	public static void clearCache() {
		CACHE.clear();
	}

	// ─────────────────────────────── inspection ───────────────────────────────

	public String getServer() { return server; }
	public long getFetchedAt() { return fetchedAt; }
	public int size() { return types.size(); }
	public boolean knows(String classType) { return types.containsKey(classType); }
	public NodeType type(String classType) { return types.get(classType); }
	public Set<String> typeNames() { return types.keySet(); }

	/// The subset of this catalogue covering only the given types - what a fixture captures.
	public NodeCatalogue subset(Set<String> classTypes) {
		Map<String, NodeType> out = new LinkedHashMap<>();
		for(String t : classTypes) {
			NodeType nt = types.get(t);
			if(nt != null) out.put(t, nt);
		}
		return new NodeCatalogue(server, fetchedAt, out);
	}

	/// object_info-shaped JSON of this catalogue (input names only; the option arrays are not kept).
	public String toJson() {
		Map<String, Object> out = new LinkedHashMap<>();
		for(NodeType nt : types.values()) {
			Map<String, Object> req = new LinkedHashMap<>();
			for(String r : nt.required) req.put(r, List.of("?"));
			Map<String, Object> opt = new LinkedHashMap<>();
			for(String o : nt.optional) opt.put(o, List.of("?"));
			Map<String, Object> input = new LinkedHashMap<>();
			input.put("required", req);
			input.put("optional", opt);
			Map<String, Object> type = new LinkedHashMap<>();
			type.put("input", input);
			type.put("name", nt.name);
			out.put(nt.name, type);
		}
		try {
			return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
		}
		catch(Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// ─────────────────────────────── validation ───────────────────────────────

	/// Problems with a graph against this catalogue plus the structural checks that need no catalogue
	/// ({@link #validateStructure}). Errors: unknown class_type, missing required input, dangling link,
	/// a tag the inputs do not fill, no non-numeric save node, inline image data. Warning: an input the
	/// catalogue does not list.
	public List<GraphProblem> validate(ComfyGraph graph, Set<String> providedInputKeys) {
		List<GraphProblem> out = validateStructure(graph, providedInputKeys);
		for(String id : graph.nodeIds()) {
			String ct = graph.classType(id);
			NodeType nt = types.get(ct);
			if(nt == null) {
				out.add(GraphProblem.error(GraphProblem.UNKNOWN_NODE_TYPE, id, "node type " + ct + " is not in the renderer's object_info"));
				continue;
			}
			Map<String, Object> in = graph.inputs(id);
			for(String r : nt.required) {
				if(!in.containsKey(r)) {
					out.add(GraphProblem.error(GraphProblem.MISSING_REQUIRED_INPUT, id, ct + " requires input " + r));
				}
			}
			for(String k : in.keySet()) {
				if(!nt.accepts(k)) {
					out.add(GraphProblem.warning(GraphProblem.UNKNOWN_INPUT, id, ct + " does not list input " + k));
				}
			}
		}
		return out;
	}

	/// The checks that need no server: links resolve, every tag has a value, a non-numeric save node
	/// exists, no SwarmLoadImageB64 carries inline data.
	public static List<GraphProblem> validateStructure(ComfyGraph graph, Set<String> providedInputKeys) {
		List<GraphProblem> out = new ArrayList<>();
		for(String d : graph.danglingLinks()) {
			out.add(GraphProblem.error(GraphProblem.DANGLING_LINK, d.substring(0, d.indexOf('.')), "link " + d + " points at no node"));
		}
		Set<String> provided = (providedInputKeys == null ? Collections.emptySet() : providedInputKeys);
		for(String t : graph.tags()) {
			if(!provided.contains(t)) {
				out.add(GraphProblem.error(GraphProblem.UNFILLED_TAG, null, "tag " + t + " has no value in the request"));
			}
		}
		boolean realSave = false;
		for(String s : graph.saveNodeIds()) {
			if(!ComfyGraph.isNumericId(s)) realSave = true;
		}
		if(!realSave) {
			out.add(GraphProblem.error(GraphProblem.NO_SAVE_NODE, null, "no save node with a non-numeric id; Swarm would return no image"));
		}
		for(String id : graph.nodesOf(ComfyGraph.CLASS_LOAD_IMAGE_B64)) {
			Object v = graph.inputs(id).get(ComfyGraph.INPUT_IMAGE_BASE64);
			if(!(v instanceof String) || !ComfyGraph.isTag((String) v)) {
				out.add(GraphProblem.error(GraphProblem.INLINE_IMAGE, id, "SwarmLoadImageB64 carries inline image data instead of an image tag"));
			}
		}
		return out;
	}

	public static List<GraphProblem> errors(List<GraphProblem> problems) {
		List<GraphProblem> out = new ArrayList<>();
		for(GraphProblem p : problems) {
			if(p.isError()) out.add(p);
		}
		return out;
	}

	public static String describe(List<GraphProblem> problems) {
		StringBuilder sb = new StringBuilder();
		for(GraphProblem p : problems) {
			if(sb.length() > 0) sb.append("; ");
			sb.append(p);
		}
		return sb.toString();
	}
}
