package org.cote.accountmanager.olio.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;

/// Tracks which Ollama (server, model) pairs have been used since the last flush, and provides
/// an explicit utility to unload them all via Ollama's documented keep_alive:0 trick. Ollama
/// keeps a model resident in VRAM for a while after each request; non-chat LLM callers (Picture
/// Book image generation, auto-scene/auto-outfit narration, chunk summarization, ISO 42001 bias
/// trials) call unloadAll() before doing GPU-heavy work (SD image generation) so the model isn't
/// fighting for GPU memory. Live/interactive chat deliberately never calls this — it wants the
/// model to stay warm for the user's next message.
public class OllamaModelUtil {

	private static final Logger logger = LogManager.getLogger(OllamaModelUtil.class);

	/// serverUrl -> set of model names used since the last flush.
	private static final Map<String, Set<String>> loadedModels = new ConcurrentHashMap<>();

	/// Record that a model was just used against an Ollama server. Called unconditionally from
	/// Chat's dispatch path for every OLLAMA-serviced request, chat or not — the registry should
	/// always reflect reality regardless of who loaded a given model.
	public static void recordUsage(String serverUrl, String model) {
		if (serverUrl == null || model == null || model.isEmpty()) return;
		loadedModels.computeIfAbsent(serverUrl, k -> ConcurrentHashMap.newKeySet()).add(model);
	}

	/// Unload every tracked (server, model) pair via POST {serverUrl}/api/generate
	/// {"model":name,"keep_alive":0}. Best-effort: one server/model failing to unload must not
	/// block the others, and a failure still clears that entry (don't retry-loop a dead server).
	public static void unloadAll() {
		for (Map.Entry<String, Set<String>> entry : loadedModels.entrySet()) {
			String serverUrl = entry.getKey();
			for (String model : entry.getValue().toArray(new String[0])) {
				unloadOne(serverUrl, model);
				entry.getValue().remove(model);
			}
		}
	}

	private static void unloadOne(String serverUrl, String model) {
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", model);
			body.put("keep_alive", 0);
			String json = JSONUtil.exportObject(body);
			ClientUtil.postJSON(String.class, ClientUtil.getResource(serverUrl + "/api/generate"), json, MediaType.APPLICATION_JSON_TYPE);
			logger.info("Unloaded Ollama model " + model + " @ " + serverUrl);
		} catch (Exception e) {
			logger.warn("Failed to unload Ollama model " + model + " @ " + serverUrl + ": " + e.getMessage());
		}
	}
}
