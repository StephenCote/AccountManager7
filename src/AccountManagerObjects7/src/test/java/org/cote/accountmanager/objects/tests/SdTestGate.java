package org.cote.accountmanager.objects.tests;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.junit.Assume;

import static org.junit.Assert.fail;

/**
 * KI-39 / KI-48 — the one rule for live SD tests: <b>a test that could not reach its backend must
 * SKIP VISIBLY; it must never pass.</b>
 *
 * <p>The pattern this replaces was {@code logger.warn(...); return;}. JUnit reports that as a PASS,
 * so a suite could report all-green while SwarmUI had refused every single request
 * ("Invalid model value for param Model — 'flux1Kontext_flux1KontextDev'", a checkpoint that simply
 * is not installed on that node). Checkpoint availability is per-deployment, so "not installed here"
 * is a legitimate reason not to run — but it is not evidence of anything, and it must not be
 * reported as if it were. {@code Assume} produces a "Skipped" with the reason attached; a genuine
 * empty result from a reachable backend with the checkpoint present is a real failure and fails.
 *
 * <p>Also centralizes stamping the INSTALLED checkpoint onto configs built with
 * {@link SDUtil#randomSDConfig()}, which otherwise carries the model schema default — a
 * per-deployment name that is nobody's guarantee.
 */
public final class SdTestGate {
	public static final Logger logger = LogManager.getLogger(SdTestGate.class);

	private SdTestGate() {}

	/** Skip visibly when no Swarm server is configured at all. */
	public static void requireSwarmConfigured(String swarmServer) {
		Assume.assumeTrue("SKIPPED: test.swarm.server is not configured, so nothing live can be "
			+ "exercised here. This is NOT a pass.", swarmServer != null && !swarmServer.isEmpty());
	}

	private static final Map<String, Boolean> REACHABLE = new ConcurrentHashMap<>();

	/**
	 * True when {@code url} answered with any HTTP status (a 302 from SwarmUI or "Ollama is running"
	 * both count). Probed once per URL per JVM, with one retry.
	 *
	 * <p>The timeouts are generous on purpose: a 3-second connect timeout misreported the Spark as
	 * "off the LAN" twice on 2026-09-28 while curl and a standalone probe reached it in under a second
	 * — a GPU box mid-generation can be slow to accept, and that is not the same as unreachable.
	 */
	public static boolean isReachable(String url) {
		if (url == null || url.isBlank()) return false;
		return REACHABLE.computeIfAbsent(url, u -> {
			Exception last = null;
			for (int attempt = 1; attempt <= 2; attempt++) {
				try {
					HttpClient client = HttpClient.newBuilder()
						.connectTimeout(Duration.ofSeconds(10))
						.followRedirects(HttpClient.Redirect.NEVER)
						.build();
					HttpRequest req = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(15)).GET().build();
					int status = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
					logger.info(u + " answered HTTP " + status + (attempt > 1 ? " (attempt " + attempt + ")" : ""));
					return true;
				} catch (Exception e) {
					last = e;
					logger.warn(u + " did not answer on attempt " + attempt + ": " + e.getMessage());
				}
			}
			logger.warn(u + " is not reachable: " + (last != null ? last.getMessage() : "unknown"));
			return false;
		});
	}

	/**
	 * Skip visibly when Swarm is unconfigured, or configured but not answering — the checked-in
	 * property names a LAN host that is unavailable when working off the LAN.
	 */
	public static void requireSwarmReachable(String swarmServer) {
		requireSwarmConfigured(swarmServer);
		Assume.assumeTrue("SKIPPED: test.swarm.server=" + swarmServer + " did not answer, so nothing live "
			+ "can be exercised here (off the LAN?). This is NOT a pass.", isReachable(swarmServer));
	}

	/** Same rule for the LLM: unconfigured or unreachable {@code test.llm.ollama.server} skips visibly. */
	public static void requireLlmReachable(String llmServer) {
		Assume.assumeTrue("SKIPPED: test.llm.ollama.server is not configured, so nothing live can be "
			+ "exercised here. This is NOT a pass.", llmServer != null && !llmServer.isBlank());
		Assume.assumeTrue("SKIPPED: test.llm.ollama.server=" + llmServer + " did not answer, so nothing live "
			+ "can be exercised here (off the LAN?). This is NOT a pass.", isReachable(llmServer));
	}

	public static final String PROP_SWARM_SERVER = "test.swarm.server";
	public static final String PROP_SWARM_MODEL = "test.swarm.model";
	public static final String PROP_SWARM_REFINER = "test.swarm.refinerModel";

	/** Server URL the checkpoints in {@code testProperties} were last resolved against; null = not yet. */
	private static String resolvedFor = null;

	/**
	 * Resolve the checkpoints the live tests send FROM THE SERVER'S OWN LIST rather than from names
	 * pinned in {@code resource.properties}. Stephen, 2026-09-28: "the models are hard coded. Just read
	 * the model list from server (code is there) and use the sdXL default." Checkpoint availability is
	 * per-node and he roams between two Swarm boxes, so a pinned name is wrong on one of them.
	 *
	 * <p>Base model, first match wins:
	 * <ol>
	 * <li>{@code test.swarm.model} when set AND installed - an explicit, verified override;</li>
	 * <li>the {@code olio.sd.config} schema default (SDXL base) when installed - what production
	 *     sends for a config that names no model, so tests exercise the real default where they can;</li>
	 * <li>the first installed SDXL-family checkpoint (see {@link #isSdxlFamily}) - so a node without
	 *     the official base still runs the tests instead of skipping nine of them.</li>
	 * </ol>
	 * Refiner: {@code test.swarm.refinerModel} is kept only when the node lists it; otherwise it is
	 * removed so no request names a refiner the node would refuse.
	 *
	 * <p>The result is written back into {@code testProperties} under the same keys, so every existing
	 * consumer of {@code test.swarm.model} / {@code test.swarm.refinerModel} sees what this node
	 * actually has without being edited. Resolved once per JVM per server URL; when the server does not
	 * answer, the properties are left as configured and the reachability gates skip as before.
	 */
	public static synchronized void resolveInstalledCheckpoints(Properties testProperties) {
		if (testProperties == null) return;
		String server = testProperties.getProperty(PROP_SWARM_SERVER);
		if (server == null || server.isBlank()) return;
		if (server.equals(resolvedFor)) return;
		if (!isReachable(server)) {
			logger.warn("Swarm " + server + " not reachable - leaving test.swarm.* checkpoints as configured");
			return;
		}
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		List<String> installed = sdu.listModels();
		if (installed == null || installed.isEmpty()) {
			logger.warn("Swarm " + server + " listed no checkpoints - leaving test.swarm.* as configured");
			return;
		}
		resolvedFor = server;

		String configured = testProperties.getProperty(PROP_SWARM_MODEL);
		String schemaDefault = SDUtil.schemaDefault(OlioFieldNames.FIELD_SD_MODEL);
		String model = null;
		String how = null;
		if (configured != null && !configured.isBlank()) {
			if (isInstalled(installed, configured)) {
				model = configured;
				how = "test.swarm.model (verified installed)";
			} else {
				logger.warn("test.swarm.model='" + configured + "' is NOT installed on " + server + " - resolving from the server's list instead");
			}
		}
		if (model == null && schemaDefault != null && isInstalled(installed, schemaDefault)) {
			model = schemaDefault;
			how = "olio.sd.config schema default";
		}
		if (model == null) {
			for (String m : installed) {
				if (isSdxlFamily(m)) {
					model = m;
					how = "first installed SDXL-family checkpoint";
					break;
				}
			}
		}
		if (model != null) {
			testProperties.setProperty(PROP_SWARM_MODEL, model);
			logger.info("Live SD checkpoint for " + server + " = '" + model + "' [" + how + "]");
		} else {
			testProperties.remove(PROP_SWARM_MODEL);
			logger.warn("Swarm " + server + " has no SDXL-family checkpoint among " + installed.size()
				+ " listed; live SD tests will skip. Listed: " + installed);
		}

		String refiner = testProperties.getProperty(PROP_SWARM_REFINER);
		if (refiner != null && !refiner.isBlank() && !isInstalled(installed, refiner)) {
			testProperties.remove(PROP_SWARM_REFINER);
			logger.warn("test.swarm.refinerModel='" + refiner + "' is NOT installed on " + server + " - rendering without a refiner");
		}
	}

	/** Server-listed names carry the folder and extension; match either bare or as listed. */
	private static boolean isInstalled(List<String> installed, String model) {
		if (model == null || model.isBlank()) return false;
		for (String m : installed) {
			if (m == null) continue;
			String bare = m.endsWith(".safetensors") ? m.substring(0, m.length() - 12) : m;
			if (m.equalsIgnoreCase(model) || bare.equalsIgnoreCase(model)) return true;
		}
		return false;
	}

	/**
	 * An SDXL-class base checkpoint the default SDXL settings (1024px, ~20 steps, cfg ~7) are sane
	 * for. Excludes distilled variants that need their own step/cfg regime and non-base families.
	 */
	static boolean isSdxlFamily(String listedName) {
		if (listedName == null) return false;
		String n = listedName.toLowerCase();
		if (!(n.contains("sd_xl") || n.contains("sdxl") || n.contains("xl"))) return false;
		for (String bad : new String[] { "hyper", "lightning", "turbo", "lcm", "refiner", "pony", "flux", "inpaint" }) {
			if (n.contains(bad)) return false;
		}
		return true;
	}

	/**
	 * The client-side config a live render should carry so it hits the checkpoint this node has -
	 * a sparse {@code olio.sd.config} carrying ONLY {@code model}, so nothing else about the render
	 * (refiner, hires, steps) differs from what production sends. Renders that build their effective
	 * config from {@code RecordFactory.newInstance(olio.sd.config)} (ChapBook via {@code PbConfigUtil
	 * .resolveEffectiveConfig}) materialise the schema default {@code model} as a real value, so
	 * {@code SDUtil.resolveModel}/{@code sd.default.model} never get a say; the only production seam
	 * that changes the checkpoint there is the {@code clientSdConfig} the Ux sends, applied by
	 * {@code SDUtil.applyOverrides}. Skips visibly when nothing usable is installed.
	 */
	public static BaseRecord requireRenderConfig(Properties testProperties, String swarmServer, String what) {
		requireSwarmReachable(swarmServer);
		resolveInstalledCheckpoints(testProperties);
		String model = testProperties.getProperty(PROP_SWARM_MODEL);
		Assume.assumeTrue("SKIPPED before generating anything: " + what + " needs an SDXL-family checkpoint and "
			+ swarmServer + " lists none (availability is per-node). This is NOT a pass.",
			model != null && !model.isBlank());
		try {
			BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG, new String[] { OlioFieldNames.FIELD_SD_MODEL });
			cfg.setValue(OlioFieldNames.FIELD_SD_MODEL, model);
			logger.info(what + " will render with checkpoint '" + model + "' on " + swarmServer);
			return cfg;
		} catch (Exception e) {
			fail("Could not build the render config for " + what + ": " + e.getMessage());
			return null;
		}
	}

	/**
	 * Stamp the installed checkpoint (and refiner) from {@code test.swarm.*} onto a config, so a
	 * live call actually generates instead of being refused for a model that only exists in the
	 * schema default. Leaves an explicitly-set model alone. Resolves the properties against the
	 * server first ({@link #resolveInstalledCheckpoints}).
	 */
	public static BaseRecord stampInstalledModel(BaseRecord sdConfig, Properties testProperties) {
		resolveInstalledCheckpoints(testProperties);
		if (sdConfig == null || testProperties == null) return sdConfig;
		try {
			String model = testProperties.getProperty("test.swarm.model");
			if (model != null && !model.isBlank()) sdConfig.setValue("model", model);
			String refiner = testProperties.getProperty("test.swarm.refinerModel");
			if (refiner != null && !refiner.isBlank() && sdConfig.hasField("refinerModel")) {
				sdConfig.setValue("refinerModel", refiner);
			}
		} catch (Exception e) {
			logger.warn("Could not stamp the installed checkpoint onto the config: " + e.getMessage());
		}
		return sdConfig;
	}

	/** True when {@code model} appears in the server's checkpoint list (with or without .safetensors). */
	public static boolean isModelInstalled(SDUtil sdu, String model) {
		if (sdu == null || model == null || model.isBlank()) return false;
		try {
			List<String> installed = sdu.listModels();
			return installed != null && isInstalled(installed, model);
		} catch (Exception e) {
			logger.warn("Could not list Swarm checkpoints: " + e.getMessage());
		}
		return false;
	}

	/**
	 * Gate a live test on a checkpoint BEFORE it does any work.
	 *
	 * <p>Classifying only after the fact still sends a request the server refuses — visible in the
	 * Swarm log as "Refused to generate image … Invalid model value for param Model" — and pays for
	 * whatever staging (portraits, landscape) ran first. Skip visibly instead.
	 */
	public static void requireModelInstalled(SDUtil sdu, String swarmServer, String model, String what) {
		boolean installed = isModelInstalled(sdu, model);
		logger.info("Checkpoint '" + model + "' installed on " + swarmServer + ": " + installed);
		Assume.assumeTrue("SKIPPED before generating anything: " + what + " needs checkpoint '" + model
			+ "', which is not installed on " + swarmServer + " (availability is per-node). This is NOT "
			+ "a pass — install it, or point the config at a checkpoint this node has.", installed);
	}

	/**
	 * Call this at every point a live generation came back empty. It classifies the empty result and
	 * NEVER returns normally: either a visible Skip (checkpoint genuinely absent on this node) or a
	 * hard failure (the checkpoint is there, so the generation really did fail).
	 *
	 * @param what human-readable name of the stage, e.g. "portrait" / "landscape" / "Kontext scene"
	 */
	public static void emptyResultIsSkipOrFailure(SDUtil sdu, String swarmServer, String model, String what) {
		boolean installed = isModelInstalled(sdu, model);
		logger.info("Checkpoint '" + model + "' installed on " + swarmServer + ": " + installed);
		Assume.assumeTrue("SKIPPED: " + what + " produced no image because the checkpoint '" + model
			+ "' is not installed on " + swarmServer + " (checkpoint availability is per-node). This is "
			+ "NOT a pass — point the config at an installed checkpoint to exercise this path here.",
			installed);
		fail(what + " returned no image even though '" + model + "' IS installed on " + swarmServer
			+ " — a real generation failure, not a missing checkpoint");
	}

	/**
	 * Preparation (apparel/portrait staging etc.) that produced too little to run the real assertion.
	 * Same rule: visible skip when the backend could not deliver, never a silent pass.
	 */
	public static void insufficientPreparation(String what, int got, int needed) {
		Assume.assumeTrue("SKIPPED: only " + got + " of the " + needed + " required " + what
			+ " could be prepared, so the real assertion never ran. This is NOT a pass.", got >= needed);
	}
}
