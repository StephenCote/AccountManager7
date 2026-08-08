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

	/// Master switch for unloadAll(), OFF by default.
	///
	/// The unload is a GPU-contention optimization, not a correctness requirement: it frees VRAM
	/// before SD work. But it is only a win when the model is cheap to bring back. With a large model
	/// (gpt-oss:120b) the cost inverts — every unload forces a full reload on the next LLM call, and a
	/// picture-book run alternates LLM and SD work repeatedly (unloadAll() is called from eight places
	/// in PictureBookUtil alone), so the pipeline spends more time cycling the model in and out of
	/// VRAM than it saves. Default OFF so nobody pays that by accident; turn it on deliberately on a
	/// GPU-constrained box running a small model.
	///
	/// Deployment-global, not per-org: it describes the GPU the process talks to, so one copy is
	/// correct here (contrast the per-org rule in .claude/rules/architecture.md). Boot-pinned —
	/// set once at startup from Service7's init-param / Console7's resource.properties. volatile
	/// because the setter runs on the startup thread while unloadAll() is called from request and
	/// pipeline threads.
	private static volatile boolean unloadEnabled = false;

	/// Config key used by every host: Service7 web.xml init-param, Console7 resource.properties,
	/// Objects7 test resource.properties.
	public static final String CONFIG_KEY = "llm.ollama.unload";

	public static void setUnloadEnabled(boolean enabled) {
		/// Log only on CHANGE. Every BaseTest setUp re-applies this from test properties, so an
		/// unconditional log emitted one line per test class restating a value that never moved.
		if(enabled != unloadEnabled) {
			logger.info("Ollama model unload (" + CONFIG_KEY + ") is now " + (enabled ? "ENABLED" : "DISABLED")
				+ (enabled ? "" : " - models stay resident between LLM and SD work"));
		}
		unloadEnabled = enabled;
	}

	public static boolean isUnloadEnabled() {
		return unloadEnabled;
	}

	/// serverUrl -> set of model names used since the last flush.
	private static final Map<String, Set<String>> loadedModels = new ConcurrentHashMap<>();

	/// Record that a model was just used against an Ollama server. Called unconditionally from
	/// Chat's dispatch path for every OLLAMA-serviced request, chat or not — the registry should
	/// always reflect reality regardless of who loaded a given model.
	public static void recordUsage(String serverUrl, String model) {
		if (serverUrl == null || model == null || model.isEmpty()) return;
		loadedModels.computeIfAbsent(serverUrl, k -> ConcurrentHashMap.newKeySet()).add(model);
	}

	/// OPPORTUNISTIC unload — the automatic "flush VRAM before GPU-heavy work" call made from
	/// PictureBookUtil, ChatUtil and the ISO 42001 TestRunner. Honors the CONFIG_KEY switch, so it is
	/// a no-op by default. This is the one that costs a full model reload on the next LLM call.
	public static void unloadAll() {
		if (!unloadEnabled) {
			// Deliberately does NOT clear the registry: the tracked pairs stay accurate so an
			// explicit unloadAll(true) later, or a restart with the switch on, still knows what to
			// unload. The registry is a small bounded set of (server, model) pairs.
			logger.debug("Skipping opportunistic Ollama unload (" + CONFIG_KEY + "=false)");
			return;
		}
		unloadAll(true);
	}

	/// Unload every tracked (server, model) pair via POST {serverUrl}/api/generate
	/// {"model":name,"keep_alive":0}. Best-effort: one server/model failing to unload must not
	/// block the others, and a failure still clears that entry (don't retry-loop a dead server).
	///
	/// @param force when true, unload regardless of the CONFIG_KEY switch. This is the path for an
	///        EXPLICITLY INSTRUCTED unload and for shutdown — disabling the automatic flush must not
	///        take away the ability to free the GPU on purpose. When false, behaves exactly like the
	///        no-arg unloadAll().
	public static void unloadAll(boolean force) {
		if (!force && !unloadEnabled) {
			logger.debug("Skipping opportunistic Ollama unload (" + CONFIG_KEY + "=false)");
			return;
		}
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
