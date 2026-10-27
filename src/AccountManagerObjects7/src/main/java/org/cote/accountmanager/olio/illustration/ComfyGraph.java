package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/// A ComfyUI API-format graph as SwarmUI's `comfyworkflowraw` carries it (PictureBook V3 design §38.1):
/// a map of node id to `{class_type, inputs}` where an input value is a literal, a link `[nodeId, slot]`
/// or a `${comfyrawworkflowinput<type><name>}` tag Swarm fills from the request at send time. The class
/// parses, patches, serialises and hashes; it never talks to a server and never holds image bytes - a
/// SwarmLoadImageB64 node must carry an image tag, not inline base64 ({@link #assertNoInlineImages}).
///
/// Serialisation is deliberately NOT JSONUtil's: that mapper drops nulls and empty strings
/// (Include.NON_EMPTY), and a generated graph carries both (`lora_hooks: null`, an empty negative
/// prompt). Node order is preserved so {@link #graphHash} is stable across a parse/serialise round trip.
public final class ComfyGraph {

	/// Every node id this layer adds carries this prefix; Swarm treats non-numeric ids as real outputs.
	public static final String ID_PREFIX = "v3_";
	/// The single save node a compiled graph ends in (§38.2); Swarm's own numeric save node is removed.
	public static final String SAVE_FINAL = ID_PREFIX + "save_final";
	/// Request-key prefix for a tagged input (§38.1): `comfyrawworkflowinput<type><name>`.
	public static final String INPUT_KEY_PREFIX = "comfyrawworkflowinput";
	/// Swarm's `initImageFixer` uploads an image-typed `comfyrawworkflowinput…` value to ComfyUI and
	/// writes the upload's *filename* into the tag - which SwarmLoadImageB64 cannot decode - unless the
	/// key contains this marker, in which case the tag is filled with the image's base64 (measured on
	/// `.39` 2026-10-24, §38.1). Every image key {@link #inputKey} builds carries it.
	public static final String IMAGE_KEY_MARKER = "swarminputimage";
	/// The request key that carries the graph itself.
	public static final String PARAM_RAW = "comfyworkflowraw";

	public static final String TYPE_TEXT = "text";
	public static final String TYPE_INTEGER = "integer";
	public static final String TYPE_DECIMAL = "decimal";
	public static final String TYPE_BOOLEAN = "boolean";
	public static final String TYPE_IMAGE = "image";
	public static final String TYPE_SEED = "seed";
	public static final String TYPE_MODEL = "model";

	public static final String CLASS_LOAD_IMAGE_B64 = "SwarmLoadImageB64";
	public static final String CLASS_SAVE_IMAGE_WS = "SwarmSaveImageWS";
	public static final String INPUT_IMAGE_BASE64 = "image_base64";
	public static final String FIELD_CLASS_TYPE = "class_type";
	public static final String FIELD_INPUTS = "inputs";

	private static final Pattern TAG = Pattern.compile("\\$\\{(" + INPUT_KEY_PREFIX + "[A-Za-z0-9_]+)\\}");
	private static final ObjectMapper MAPPER = new ObjectMapper().setSerializationInclusion(Include.ALWAYS);
	private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<LinkedHashMap<String, Object>>() { };

	private final LinkedHashMap<String, Map<String, Object>> nodes = new LinkedHashMap<>();

	private ComfyGraph() { }

	// ─────────────────────────────── construction ───────────────────────────────

	/// Parse an API-format graph. Rejects anything that is not a JSON object of `{class_type, inputs}` nodes.
	public static ComfyGraph fromJson(String json) {
		if(json == null || json.isBlank()) {
			throw new IllegalArgumentException("Graph JSON is empty");
		}
		Map<String, Object> parsed;
		try {
			parsed = MAPPER.readValue(json, MAP_TYPE);
		}
		catch(Exception e) {
			throw new IllegalArgumentException("Graph JSON did not parse: " + e.getMessage(), e);
		}
		return fromMap(parsed);
	}

	@SuppressWarnings("unchecked")
	public static ComfyGraph fromMap(Map<String, Object> map) {
		if(map == null) {
			throw new IllegalArgumentException("Graph map is null");
		}
		ComfyGraph g = new ComfyGraph();
		for(Map.Entry<String, Object> e : map.entrySet()) {
			if(!(e.getValue() instanceof Map)) {
				throw new IllegalArgumentException("Node " + e.getKey() + " is not an object");
			}
			Map<String, Object> node = (Map<String, Object>) deepCopy(e.getValue());
			if(!(node.get(FIELD_CLASS_TYPE) instanceof String)) {
				throw new IllegalArgumentException("Node " + e.getKey() + " has no class_type");
			}
			Object in = node.get(FIELD_INPUTS);
			if(in == null) {
				node.put(FIELD_INPUTS, new LinkedHashMap<String, Object>());
			}
			else if(!(in instanceof Map)) {
				throw new IllegalArgumentException("Node " + e.getKey() + " inputs is not an object");
			}
			g.nodes.put(e.getKey(), node);
		}
		return g;
	}

	// ─────────────────────────────── serialisation ───────────────────────────────

	/// Compact JSON, node order preserved - the form sent as `comfyworkflowraw` and the form hashed.
	public String toJson() {
		try {
			return MAPPER.writer().writeValueAsString(nodes);
		}
		catch(Exception e) {
			throw new IllegalStateException("Graph did not serialise: " + e.getMessage(), e);
		}
	}

	public String toPrettyJson() {
		try {
			return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(nodes);
		}
		catch(Exception e) {
			throw new IllegalStateException("Graph did not serialise: " + e.getMessage(), e);
		}
	}

	/// A deep copy of the node map.
	@SuppressWarnings("unchecked")
	public Map<String, Object> toMap() {
		return (Map<String, Object>) deepCopy(nodes);
	}

	public ComfyGraph copy() {
		return fromMap(toMap());
	}

	/// SHA-256 of {@link #toJson()} - the provenance key a `backendGraph` and a `renderGraph` artifact share.
	public String graphHash() {
		return RenderPlan.sha256Hex(toJson());
	}

	// ─────────────────────────────── inspection ───────────────────────────────

	public int size() {
		return nodes.size();
	}

	public Set<String> nodeIds() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(nodes.keySet()));
	}

	public boolean hasNode(String id) {
		return nodes.containsKey(id);
	}

	public String classType(String id) {
		Map<String, Object> n = nodes.get(id);
		return (n == null ? null : (String) n.get(FIELD_CLASS_TYPE));
	}

	/// The live, mutable inputs map of a node (null when the node does not exist).
	@SuppressWarnings("unchecked")
	public Map<String, Object> inputs(String id) {
		Map<String, Object> n = nodes.get(id);
		return (n == null ? null : (Map<String, Object>) n.get(FIELD_INPUTS));
	}

	/// Node ids of the given class_type, in graph order.
	public List<String> nodesOf(String classType) {
		List<String> out = new ArrayList<>();
		for(Map.Entry<String, Map<String, Object>> e : nodes.entrySet()) {
			if(classType.equals(e.getValue().get(FIELD_CLASS_TYPE))) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	/// Ids of save nodes (SwarmSaveImageWS / SaveImage), in graph order. Swarm returns an image for a
	/// save node only when its id is non-numeric (§38.1), so a graph whose save nodes are all numeric
	/// produces nothing.
	public List<String> saveNodeIds() {
		List<String> out = new ArrayList<>();
		for(Map.Entry<String, Map<String, Object>> e : nodes.entrySet()) {
			String ct = (String) e.getValue().get(FIELD_CLASS_TYPE);
			if(CLASS_SAVE_IMAGE_WS.equals(ct) || "SaveImage".equals(ct)) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	public static boolean isNumericId(String id) {
		if(id == null || id.isEmpty()) {
			return false;
		}
		for(int i = 0; i < id.length(); i++) {
			if(!Character.isDigit(id.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	/// Every `${comfyrawworkflowinput...}` tag present in any input value, as request keys (without
	/// the `${}`), in graph order.
	public Set<String> tags() {
		Set<String> out = new LinkedHashSet<>();
		for(Map<String, Object> node : nodes.values()) {
			collectTags(node.get(FIELD_INPUTS), out);
		}
		return out;
	}

	/// The tags present in one node's own input values (empty when the node does not exist or carries none).
	public Set<String> tagsOf(String id) {
		Set<String> out = new LinkedHashSet<>();
		Map<String, Object> n = nodes.get(id);
		if(n != null) {
			collectTags(n.get(FIELD_INPUTS), out);
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static void collectTags(Object v, Set<String> out) {
		if(v instanceof String) {
			Matcher m = TAG.matcher((String) v);
			while(m.find()) {
				out.add(m.group(1));
			}
		}
		else if(v instanceof Map) {
			for(Object o : ((Map<String, Object>) v).values()) {
				collectTags(o, out);
			}
		}
		else if(v instanceof List) {
			for(Object o : (List<Object>) v) {
				collectTags(o, out);
			}
		}
	}

	/// Links `[nodeId, slot]` whose target node does not exist.
	public List<String> danglingLinks() {
		List<String> out = new ArrayList<>();
		for(Map.Entry<String, Map<String, Object>> e : nodes.entrySet()) {
			Map<String, Object> in = inputs(e.getKey());
			for(Map.Entry<String, Object> ie : in.entrySet()) {
				String target = linkTarget(ie.getValue());
				if(target != null && !nodes.containsKey(target)) {
					out.add(e.getKey() + "." + ie.getKey() + " -> " + target);
				}
			}
		}
		return out;
	}

	/// The node id a link value points at, or null when the value is not a link.
	public static String linkTarget(Object v) {
		if(v instanceof List && ((List<?>) v).size() == 2 && ((List<?>) v).get(1) instanceof Number) {
			Object id = ((List<?>) v).get(0);
			return (id instanceof String || id instanceof Number ? String.valueOf(id) : null);
		}
		return null;
	}

	/// Fails when any SwarmLoadImageB64 node still carries inline image data rather than an image tag -
	/// the guard that keeps base64 out of a persisted `renderGraph` artifact (§38.3).
	public void assertNoInlineImages() {
		for(String id : nodesOf(CLASS_LOAD_IMAGE_B64)) {
			Object v = inputs(id).get(INPUT_IMAGE_BASE64);
			if(!(v instanceof String) || !isTag((String) v)) {
				throw new IllegalStateException("Node " + id + " carries inline image data instead of an image tag");
			}
		}
	}

	public static boolean isTag(String value) {
		return value != null && TAG.matcher(value).matches();
	}

	// ─────────────────────────────── mutation ───────────────────────────────

	/// Replace one node's input value.
	public void setInput(String nodeId, String input, Object value) {
		Map<String, Object> in = inputs(nodeId);
		if(in == null) {
			throw new IllegalArgumentException("No node " + nodeId);
		}
		in.put(input, value);
	}

	/// Add a node; the id must be new. Inputs are copied.
	public void addNode(String id, String classType, Map<String, Object> inputs) {
		if(id == null || id.isBlank() || classType == null || classType.isBlank()) {
			throw new IllegalArgumentException("A node needs an id and a class_type");
		}
		if(nodes.containsKey(id)) {
			throw new IllegalArgumentException("Node " + id + " already exists");
		}
		Map<String, Object> node = new LinkedHashMap<>();
		node.put(FIELD_CLASS_TYPE, classType);
		node.put(FIELD_INPUTS, (inputs == null ? new LinkedHashMap<String, Object>() : deepCopyMap(inputs)));
		nodes.put(id, node);
	}

	/// Remove a node. Links into it are left dangling on purpose; {@link #danglingLinks} reports them.
	public boolean removeNode(String id) {
		return nodes.remove(id) != null;
	}

	/// Rename a node and rewrite every link that pointed at it.
	public void rename(String oldId, String newId) {
		if(!nodes.containsKey(oldId)) {
			throw new IllegalArgumentException("No node " + oldId);
		}
		if(nodes.containsKey(newId)) {
			throw new IllegalArgumentException("Node " + newId + " already exists");
		}
		LinkedHashMap<String, Map<String, Object>> renamed = new LinkedHashMap<>();
		for(Map.Entry<String, Map<String, Object>> e : nodes.entrySet()) {
			renamed.put(oldId.equals(e.getKey()) ? newId : e.getKey(), e.getValue());
		}
		nodes.clear();
		nodes.putAll(renamed);
		for(String id : nodes.keySet()) {
			Map<String, Object> in = inputs(id);
			for(Map.Entry<String, Object> ie : in.entrySet()) {
				if(oldId.equals(linkTarget(ie.getValue()))) {
					List<Object> link = new ArrayList<>();
					link.add(newId);
					link.add(((List<?>) ie.getValue()).get(1));
					ie.setValue(link);
				}
			}
		}
	}

	// ─────────────────────────────── tags ───────────────────────────────

	/// `comfyrawworkflowinput<type><name>` - the request key that fills the tag of the same name. An
	/// image key is `comfyrawworkflowinputimage` + {@link #IMAGE_KEY_MARKER} + `<name>`.
	public static String inputKey(String type, String name) {
		String t = requireType(type);
		return INPUT_KEY_PREFIX + t + (TYPE_IMAGE.equals(t) ? IMAGE_KEY_MARKER : "") + requireName(name);
	}

	/// `${comfyrawworkflowinput<type><name>}` - the value placed in the graph.
	public static String tag(String type, String name) {
		return "${" + inputKey(type, name) + "}";
	}

	/// The `<name>` of an input key (`comfyrawworkflowinputtextscene` -> `scene`); the key as given when
	/// it is not one. The type is matched longest-first so `image` + marker strips before `integer`'s prefix could.
	public static String tagName(String inputKey) {
		if(inputKey == null || !inputKey.startsWith(INPUT_KEY_PREFIX)) {
			return inputKey;
		}
		String rest = inputKey.substring(INPUT_KEY_PREFIX.length());
		for(String t : new String[] {TYPE_IMAGE + IMAGE_KEY_MARKER, TYPE_INTEGER, TYPE_DECIMAL, TYPE_BOOLEAN, TYPE_MODEL, TYPE_TEXT, TYPE_SEED}) {
			if(rest.startsWith(t) && rest.length() > t.length()) {
				return rest.substring(t.length());
			}
		}
		return inputKey;
	}

	/// The key as Swarm matches it (`T2IParamTypes.CleanTypeName`): ASCII-lowercased, letters only. So
	/// `ref0`, `ref1` and `REF` are one key to Swarm and the last value sent wins silently; two request
	/// keys must never reduce to the same value ({@link GraphInputs} refuses the second).
	public static String swarmKey(String key) {
		StringBuilder sb = new StringBuilder(key.length());
		for(int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if(c >= 'A' && c <= 'Z') {
				c = (char) (c + ('a' - 'A'));
			}
			if(c >= 'a' && c <= 'z') {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static String requireType(String type) {
		switch(type) {
			case TYPE_TEXT: case TYPE_INTEGER: case TYPE_DECIMAL: case TYPE_BOOLEAN:
			case TYPE_IMAGE: case TYPE_SEED: case TYPE_MODEL:
				return type;
			default:
				throw new IllegalArgumentException("Unknown tag type " + type);
		}
	}

	private static String requireName(String name) {
		if(name == null || name.isEmpty() || !name.matches("[A-Za-z0-9_]+")) {
			throw new IllegalArgumentException("A tag name must be [A-Za-z0-9_]+: " + name);
		}
		return name;
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	@SuppressWarnings("unchecked")
	static Object deepCopy(Object o) {
		if(o instanceof Map) {
			return deepCopyMap((Map<String, Object>) o);
		}
		if(o instanceof List) {
			List<Object> out = new ArrayList<>();
			for(Object v : (List<Object>) o) {
				out.add(deepCopy(v));
			}
			return out;
		}
		return o;
	}

	private static Map<String, Object> deepCopyMap(Map<String, Object> m) {
		Map<String, Object> out = new LinkedHashMap<>();
		for(Map.Entry<String, Object> e : m.entrySet()) {
			out.put(e.getKey(), deepCopy(e.getValue()));
		}
		return out;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("ComfyGraph[");
		for(Map.Entry<String, Map<String, Object>> e : nodes.entrySet()) {
			if(sb.length() > 11) sb.append(", ");
			sb.append(e.getKey()).append('=').append(e.getValue().get(FIELD_CLASS_TYPE));
		}
		return sb.append(']').toString();
	}
}
