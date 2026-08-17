package org.cote.accountmanager.olio.picturebook;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@code picturebook.v2} switch: does the scene pipeline record itself into the
 * {@code olio.pb.*} workflow graph, or run exactly as PictureBook 1 did?
 * <p>
 * <b>Default OFF, and that default is the non-regression gate.</b> With the flag off,
 * {@code PictureBookUtil.generateSceneImage} must behave byte-for-byte as before - which is what
 * {@code TestPictureBookCustom#TestPictureBookCustomPipeline} asserts by passing <i>unchanged</i>.
 * Every v2 call site is therefore guarded by {@link #isV2Enabled()} and every v2 failure is
 * <b>swallowed with a log line</b> (see {@link PbPipelineUtil}): the graph is provenance, and losing
 * provenance must never lose an image the GPU already spent ten minutes producing.
 * <p>
 * <b>Deployment-global and boot-pinned, deliberately - not per-organization.</b> It selects which
 * code path the process runs, not a tenant's entitlement, so exactly one copy is correct here
 * (contrast the per-org rule in {@code .claude/rules/architecture.md}, which forbids resolving a
 * per-org value into process-global state). Set once at startup from Service7's {@code web.xml}
 * init-param, Console7's {@code resource.properties}, or Objects7's test {@code resource.properties} -
 * the same three-host shape {@code OllamaModelUtil.CONFIG_KEY} already uses.
 * <p>
 * <b>It is not an authorization boundary.</b> {@code aiDocs/UxFeatureFlagDesign.md} §5 says this of
 * the Ux feature manifest and it is equally true here: turning v2 on grants nobody anything. Every
 * write the v2 path makes goes through {@code AccessPoint}, so PBAC decides, not this boolean. The Ux
 * manifest mechanism was <b>not</b> reused: it is per-user rather than per-org today (§3.1, "the
 * toggle is inert"), it lives in Service7, and Objects7 cannot read it without either a layering
 * violation or a read-path-that-creates.
 * <p>
 * {@code volatile} because the setter runs on the startup thread while the getter is read from
 * request and pipeline threads.
 */
public class PbFeatureFlag {
	public static final Logger logger = LogManager.getLogger(PbFeatureFlag.class);

	/** Config key used by every host: Service7 web.xml init-param, Console7/Objects7 resource.properties. */
	public static final String CONFIG_KEY = "picturebook.v2";

	private static volatile boolean v2Enabled = false;

	private PbFeatureFlag() {
		/// static utility
	}

	public static void setV2Enabled(boolean enabled) {
		/// Log only on CHANGE. Every BaseTest setUp re-applies this from test properties, and an
		/// unconditional log emits one line per test class restating a value that never moved - the
		/// same reason OllamaModelUtil.setUnloadEnabled gates its own log.
		if(enabled != v2Enabled) {
			logger.info("PictureBook 2 graph recording (" + CONFIG_KEY + ") is now "
				+ (enabled ? "ENABLED - scene generation records nodes, bindings and artifacts"
					: "DISABLED - scene generation runs the PictureBook 1 path only"));
		}
		v2Enabled = enabled;
	}

	public static boolean isV2Enabled() {
		return v2Enabled;
	}
}
