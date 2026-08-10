package org.cote.accountmanager.objects.tests;

import java.util.List;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
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

	/**
	 * Stamp the installed checkpoint (and refiner) from {@code test.swarm.*} onto a config, so a
	 * live call actually generates instead of being refused for a model that only exists in the
	 * schema default. Leaves an explicitly-set model alone.
	 */
	public static BaseRecord stampInstalledModel(BaseRecord sdConfig, Properties testProperties) {
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
			if (installed == null) return false;
			for (String m : installed) {
				if (m == null) continue;
				String bare = m.endsWith(".safetensors") ? m.substring(0, m.length() - 12) : m;
				if (m.equalsIgnoreCase(model) || bare.equalsIgnoreCase(model)) return true;
			}
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
