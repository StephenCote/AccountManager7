package org.cote.accountmanager.olio.llm;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType;
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

	/// serverUrl -> set of model names used since the last flush, for servers that speak the NATIVE
	/// Ollama API (dialect OLLAMA): these are the only entries unloadAll() can act on.
	private static final Map<String, Set<String>> loadedModels = new ConcurrentHashMap<>();

	/// proxyUrl -> set of model names used THROUGH a proxy that fronts an Ollama upstream (dialect
	/// OPENAI_COMPAT, upstream OLLAMA — e.g. LiteLLM). Tracked separately because the native
	/// {proxyUrl}/api/generate keep_alive:0 route does not exist on the proxy (LiteLLM answers 404),
	/// and no per-connection value tells us the Ollama base URL behind it. unloadAll() reports these
	/// as SKIPPED rather than POSTing a request it knows cannot succeed.
	private static final Map<String, Set<String>> proxiedModels = new ConcurrentHashMap<>();

	/// proxyUrl -> direct Ollama base URL behind it, when a deployment has told us. Optional; empty by
	/// default. When present, unloadAll() routes the proxied entries' unload to the direct URL.
	private static final Map<String, String> directUrlForProxy = new ConcurrentHashMap<>();

	/// Record that a model was just used against a NATIVE Ollama server (dialect OLLAMA). Kept for
	/// callers that hold no dialect/upstream; Chat's dispatch path uses the four-arg form.
	public static void recordUsage(String serverUrl, String model) {
		if (serverUrl == null || model == null || model.isEmpty()) return;
		loadedModels.computeIfAbsent(serverUrl, k -> ConcurrentHashMap.newKeySet()).add(model);
	}

	/// Record usage keyed on BOTH axes (2026-10-07). Called unconditionally from Chat's dispatch path
	/// for every request whose MODEL-SERVER FAMILY is Ollama, chat or not — the registry should
	/// always reflect reality regardless of who loaded a given model.
	///  - dialect OLLAMA: a native server; unloadAll() can POST its /api/generate.
	///  - any other dialect with upstream OLLAMA (OPENAI_COMPAT via LiteLLM): a proxied server;
	///    recorded under proxiedModels so the registry is honest about what is resident on the GPU,
	///    and unloadAll() reports it as skipped (or routes to a registered direct URL).
	///  - upstream not OLLAMA: nothing to track (Azure/OpenAI manage their own residency).
	public static void recordUsage(String serverUrl, String model, LLMServiceEnumType dialect, ConnectionUpstreamEnumType upstream) {
		if (serverUrl == null || model == null || model.isEmpty()) return;
		if (dialect == LLMServiceEnumType.OLLAMA) {
			recordUsage(serverUrl, model);
			return;
		}
		if (upstream == ConnectionUpstreamEnumType.OLLAMA) {
			proxiedModels.computeIfAbsent(serverUrl, k -> ConcurrentHashMap.newKeySet()).add(model);
		}
	}

	/// Tell the registry which native Ollama base URL sits behind a proxy URL, so proxied entries can
	/// be unloaded directly. Deployment-global (describes the GPU topology, not an org); nothing in
	/// AM7 populates this today — a host may call it at boot once a per-connection value exists.
	public static void registerDirectUrl(String proxyUrl, String directOllamaUrl) {
		if (proxyUrl == null || proxyUrl.isEmpty()) return;
		if (directOllamaUrl == null || directOllamaUrl.isEmpty()) {
			directUrlForProxy.remove(proxyUrl);
			return;
		}
		directUrlForProxy.put(proxyUrl, directOllamaUrl);
	}

	/// Snapshot of what is tracked, for tests and diagnostics. Native entries only.
	public static Set<String> trackedNativeModels(String serverUrl) {
		Set<String> s = loadedModels.get(serverUrl);
		return (s == null) ? java.util.Collections.emptySet() : new java.util.HashSet<>(s);
	}

	/// Snapshot of what is tracked, for tests and diagnostics. Proxied entries only.
	public static Set<String> trackedProxiedModels(String proxyUrl) {
		Set<String> s = proxiedModels.get(proxyUrl);
		return (s == null) ? java.util.Collections.emptySet() : new java.util.HashSet<>(s);
	}

	/// Honest outcome of an unloadAll() pass. `unloaded` counts models the server ACKNOWLEDGED with
	/// HTTP 200; `failed` counts attempts the server refused or that threw; `skippedProxied` counts
	/// models that were used through a proxy with no known direct URL and were therefore never
	/// attempted. A pass that did nothing (switch off, nothing tracked) is all zeros.
	public static final class UnloadResult {
		public int unloaded;
		public int failed;
		public int skippedProxied;
		public boolean attempted;
		@Override
		public String toString() {
			return "unloaded=" + unloaded + " failed=" + failed + " skippedProxied=" + skippedProxied + " attempted=" + attempted;
		}
	}

	/// OPPORTUNISTIC unload — the automatic "flush VRAM before GPU-heavy work" call made from
	/// PictureBookUtil, ChatUtil and the ISO 42001 TestRunner. Honors the CONFIG_KEY switch, so it is
	/// a no-op by default. This is the one that costs a full model reload on the next LLM call.
	public static UnloadResult unloadAll() {
		if (!unloadEnabled) {
			// Deliberately does NOT clear the registry: the tracked pairs stay accurate so an
			// explicit unloadAll(true) later, or a restart with the switch on, still knows what to
			// unload. The registry is a small bounded set of (server, model) pairs.
			logger.debug("Skipping opportunistic Ollama unload (" + CONFIG_KEY + "=false)");
			return new UnloadResult();
		}
		return unloadAll(true);
	}

	/// Unload every tracked NATIVE (server, model) pair via POST {serverUrl}/api/generate
	/// {"model":name,"keep_alive":0}. Best-effort: one server/model failing to unload must not
	/// block the others, and a failure still clears that entry (don't retry-loop a dead server).
	///
	/// PROXIED entries (used through LiteLLM etc.) are handled per dialect/upstream (2026-10-07):
	/// if a direct Ollama URL was registered for the proxy, the unload is routed there; otherwise
	/// the entry is SKIPPED with one WARN per proxy and left in the registry, so a later pass after
	/// registerDirectUrl() can still act on it. Nothing is ever POSTed to the proxy's /api/generate —
	/// that route 404s on LiteLLM, and the old code then logged "Unloaded" anyway because
	/// ClientUtil.postJSON returns null (not an exception) on a non-200 status.
	///
	/// @param force when true, unload regardless of the CONFIG_KEY switch. This is the path for an
	///        EXPLICITLY INSTRUCTED unload and for shutdown — disabling the automatic flush must not
	///        take away the ability to free the GPU on purpose. When false, behaves exactly like the
	///        no-arg unloadAll().
	public static UnloadResult unloadAll(boolean force) {
		UnloadResult result = new UnloadResult();
		if (!force && !unloadEnabled) {
			logger.debug("Skipping opportunistic Ollama unload (" + CONFIG_KEY + "=false)");
			return result;
		}
		result.attempted = true;
		for (Map.Entry<String, Set<String>> entry : loadedModels.entrySet()) {
			String serverUrl = entry.getKey();
			for (String model : entry.getValue().toArray(new String[0])) {
				if (unloadOne(serverUrl, model)) result.unloaded++; else result.failed++;
				entry.getValue().remove(model);
			}
		}
		for (Map.Entry<String, Set<String>> entry : proxiedModels.entrySet()) {
			String proxyUrl = entry.getKey();
			String direct = directUrlForProxy.get(proxyUrl);
			String[] models = entry.getValue().toArray(new String[0]);
			if (models.length == 0) continue;
			if (direct == null) {
				result.skippedProxied += models.length;
				logger.warn("Skipping Ollama unload of " + models.length + " model(s) used through proxy " + proxyUrl
					+ " (" + String.join(", ", models) + "): the proxy does not serve the native /api/generate keep_alive:0"
					+ " route and no direct Ollama URL is registered for it (OllamaModelUtil.registerDirectUrl)."
					+ " The model(s) stay resident on the upstream GPU.");
				continue;
			}
			for (String model : models) {
				if (unloadOne(direct, model)) result.unloaded++; else result.failed++;
				entry.getValue().remove(model);
			}
		}
		logger.info("Ollama unload pass: " + result);
		return result;
	}

	/// POST the keep_alive:0 unload to a NATIVE Ollama server. Returns true ONLY when the server
	/// answered HTTP 200 — ClientUtil.postJSON returns null (no exception) on any other status, which
	/// the previous version reported as "Unloaded". A null response is logged as a refusal.
	private static boolean unloadOne(String serverUrl, String model) {
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("model", model);
			body.put("keep_alive", 0);
			String json = JSONUtil.exportObject(body);
			String resp = ClientUtil.postJSON(String.class, ClientUtil.getResource(serverUrl + "/api/generate"), json, MediaType.APPLICATION_JSON_TYPE);
			if (resp == null) {
				logger.warn("Ollama did not acknowledge unload of model " + model + " @ " + serverUrl
					+ " (non-200 or empty response) - the model may still be resident");
				return false;
			}
			logger.info("Unloaded Ollama model " + model + " @ " + serverUrl);
			return true;
		} catch (Exception e) {
			logger.warn("Failed to unload Ollama model " + model + " @ " + serverUrl + ": " + e.getMessage());
			return false;
		}
	}
}
