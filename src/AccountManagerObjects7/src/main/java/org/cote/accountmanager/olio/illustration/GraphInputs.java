package org.cote.accountmanager.olio.illustration;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// The per-request values that fill a {@link ComfyGraph}'s tags (design §38.1): keyed by the
/// `comfyrawworkflowinput<type><name>` request key, typed by the tag type. Image bytes live here and
/// only here - {@link #toRequestMap()} turns them into data URIs at send time and {@link #redacted()}
/// replaces them with their size for anything that is logged or persisted.
///
/// Swarm matches request keys by {@link ComfyGraph#swarmKey} (lowercase letters only), so two keys that
/// reduce to the same value would silently share one value on the server; the second is refused here.
public final class GraphInputs {

	private final Map<String, Object> values = new LinkedHashMap<>();
	private final Map<String, byte[]> images = new LinkedHashMap<>();
	private final Map<String, String> swarmKeys = new LinkedHashMap<>();
	private final List<String> referenceArtifactIds = new ArrayList<>();

	public GraphInputs text(String name, String value) {
		put(ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, name), (value == null ? "" : value));
		return this;
	}

	public GraphInputs seed(String name, long value) {
		put(ComfyGraph.inputKey(ComfyGraph.TYPE_SEED, name), Long.valueOf(value));
		return this;
	}

	public GraphInputs integer(String name, long value) {
		put(ComfyGraph.inputKey(ComfyGraph.TYPE_INTEGER, name), Long.valueOf(value));
		return this;
	}

	public GraphInputs decimal(String name, double value) {
		put(ComfyGraph.inputKey(ComfyGraph.TYPE_DECIMAL, name), Double.valueOf(value));
		return this;
	}

	public GraphInputs bool(String name, boolean value) {
		put(ComfyGraph.inputKey(ComfyGraph.TYPE_BOOLEAN, name), Boolean.valueOf(value));
		return this;
	}

	public GraphInputs model(String name, String value) {
		put(ComfyGraph.inputKey(ComfyGraph.TYPE_MODEL, name), value);
		return this;
	}

	/// PNG bytes for an image tag. Sent as a `data:image/png;base64,...` URI.
	public GraphInputs image(String name, byte[] png) {
		if(png == null || png.length == 0) {
			throw new IllegalArgumentException("Image input " + name + " has no bytes");
		}
		String key = ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, name);
		put(key, png);
		images.put(key, png);
		return this;
	}

	/// Same key replaces; a different key that Swarm would read as the same one is refused.
	private void put(String key, Object value) {
		String sk = ComfyGraph.swarmKey(key);
		String prior = swarmKeys.get(sk);
		if(prior != null && !prior.equals(key)) {
			throw new IllegalArgumentException("Input " + key + " collides with " + prior + ": Swarm reduces both to " + sk);
		}
		swarmKeys.put(sk, key);
		values.put(key, value);
	}

	/// Artifact objectIds of the references supplied as images, in request order (provenance, §21).
	public GraphInputs referenceArtifacts(List<String> objectIds) {
		referenceArtifactIds.clear();
		if(objectIds != null) {
			referenceArtifactIds.addAll(objectIds);
		}
		return this;
	}

	public List<String> getReferenceArtifactIds() {
		return Collections.unmodifiableList(referenceArtifactIds);
	}

	/// The request keys provided, in insertion order.
	public Set<String> names() {
		return Collections.unmodifiableSet(new LinkedHashSet<>(values.keySet()));
	}

	public boolean has(String inputKey) {
		return values.containsKey(inputKey);
	}

	public Object get(String inputKey) {
		return values.get(inputKey);
	}

	/// The seed value for a seed tag name, or null when none was set.
	public Long seed(String name) {
		Object v = values.get(ComfyGraph.inputKey(ComfyGraph.TYPE_SEED, name));
		return (v instanceof Number ? Long.valueOf(((Number) v).longValue()) : null);
	}

	public String text(String name) {
		Object v = values.get(ComfyGraph.inputKey(ComfyGraph.TYPE_TEXT, name));
		return (v instanceof String ? (String) v : null);
	}

	public byte[] image(String name) {
		return images.get(ComfyGraph.inputKey(ComfyGraph.TYPE_IMAGE, name));
	}

	public int imageCount() {
		return images.size();
	}

	/// The values as the request carries them: images as data URIs, everything else as is.
	public Map<String, Object> toRequestMap() {
		Map<String, Object> out = new LinkedHashMap<>();
		for(Map.Entry<String, Object> e : values.entrySet()) {
			byte[] png = images.get(e.getKey());
			out.put(e.getKey(), (png != null ? dataUri(png) : e.getValue()));
		}
		return out;
	}

	/// The values with every image replaced by `<N bytes png>` - safe to log and to persist.
	public Map<String, Object> redacted() {
		Map<String, Object> out = new LinkedHashMap<>();
		for(Map.Entry<String, Object> e : values.entrySet()) {
			byte[] png = images.get(e.getKey());
			out.put(e.getKey(), (png != null ? "<" + png.length + " bytes png>" : e.getValue()));
		}
		return out;
	}

	public static String dataUri(byte[] png) {
		return "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
	}

	@Override
	public String toString() {
		return "GraphInputs" + redacted();
	}
}
