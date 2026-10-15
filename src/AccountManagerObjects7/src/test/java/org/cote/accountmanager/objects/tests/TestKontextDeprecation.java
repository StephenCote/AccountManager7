package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/// Kontext deprecation and disablement (PictureBook V3 design §37.3: Phase 0 "Deprecate" row, task
/// P0-11; Phase 2 "Disable" row, task P2-8).
///
/// What Phase 0 changed, and what this test pins:
///   - ChatService.generateScene calls resolveMode(sdConfig, false) like every other caller, so a
///     config carrying NEITHER compositeMode NOR useKontext resolves to CLASSIC, not Kontext. (a)
///   - resolveMode emits ONE WARN whenever it returns kontext, and none when it returns classic or
///     flux2. Captured with a programmatic log4j2 appender on the SceneCompositeUtil logger. (d)
///   - The six legacy fields are marked deprecated in configModel.json descriptions. (e)
///
/// What Phase 2 (Disable) changed, and what this test pins:
///   - Resolution stays PURE: a config that explicitly asks for Kontext — useKontext=true with
///     compositeMode cleared, or compositeMode="kontext" — still RESOLVES to kontext, so a caller can
///     tell "this config asks for Kontext" apart from a typo; but that result is now RETIRED
///     (isRetiredMode true) and every entry point refuses it. Phase 0's "no behaviour change for
///     explicit opt-in" is therefore no longer true. (b)(c)
///   - isSupportedMode no longer accepts "kontext"; SUPPORTED_MODES is "flux2|classic"; the shared
///     refusal text KONTEXT_RETIRED_MESSAGE says "replaced by V3" and cites §37.3. (f)
///
/// The refusal itself (409 from PbRenderPath.requireEnabledCompositeMode, the executor failing the
/// node and run, the ChatService 400/409 envelopes) is exercised against the DB in
/// TestPbKontextDisable (Objects7) and TestChatSceneKontextDisable (Service7), not here.
///
/// Like TestFlux2Composite this does NOT extend BaseTest: resolveMode and the schema read are pure,
/// and RecordFactory falls back to the resource schema when IOSystem is not initialised, so no DB
/// or LLM is involved. The existing resolveMode tests in TestFlux2Composite (compositeMode wins,
/// bare config → flux2, null config → caller default, unrecognized → classic) are not repeated here.
public class TestKontextDeprecation {

	private static final String DEPRECATED_PREFIX = "DEPRECATED (V3 §37.3";
	private static final String[] DEPRECATED_FIELDS = new String[] {
		"useKontext", "kontextModel", "kontextSteps", "kontextCfg", "kontextSampler", "kontextScheduler"
	};

	/// Same shape as TestFlux2Composite.sdConfig(): register the Olio models and build a schema-default
	/// olio.sd.config, which carries compositeMode="flux2" and useKontext=false by default.
	private static BaseRecord sdConfig() throws Exception {
		OlioModelNames.use();
		RecordFactory.model(OlioModelNames.MODEL_SD_CONFIG);
		return RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
	}

	private static final String TARGET_LOGGER = SceneCompositeUtil.class.getName();
	private static final String APPENDER_NAME = "kontextDeprecationCapture";

	/// Collects WARN-and-above events emitted by the SceneCompositeUtil logger.
	///
	/// Attached to the LoggerConfig that governs that logger (cfg.getLoggerConfig(name) — root in
	/// the test config, since no narrower one is declared) and filtered by event logger name, the
	/// way TestPathUtilBehavior.LogCapture does it. NOT via core.Logger.addAppender: that creates a
	/// new LoggerConfig copying root's additivity="false", which silently stops SceneCompositeUtil
	/// lines reaching the console for the rest of the JVM, and removeAppender does not undo it.
	private static final class CapturingAppender extends AbstractAppender {
		final List<String> warnings = Collections.synchronizedList(new ArrayList<String>());

		CapturingAppender(String name) {
			super(name, null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			if (TARGET_LOGGER.equals(event.getLoggerName()) && event.getLevel().isMoreSpecificThan(Level.WARN)) {
				warnings.add(event.getMessage().getFormattedMessage());
			}
		}

		long deprecationCount() {
			synchronized (warnings) {
				return warnings.stream().filter(TestKontextDeprecation::isDeprecationLine).count();
			}
		}
	}

	private static boolean isDeprecationLine(String msg) {
		return msg != null && msg.startsWith("compositeMode=kontext is deprecated");
	}

	private LoggerConfig target;
	private CapturingAppender appender;

	@Before
	public void attachAppender() {
		LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
		Configuration cfg = ctx.getConfiguration();
		target = cfg.getLoggerConfig(TARGET_LOGGER);
		appender = new CapturingAppender(APPENDER_NAME);
		appender.start();
		target.addAppender(appender, Level.WARN, null);
	}

	@After
	public void detachAppender() {
		if (target != null && appender != null) {
			target.removeAppender(APPENDER_NAME);
			appender.stop();
		}
	}

	// ── Behavioural: the chat-path flip ─────────────────────────────────────────

	/// (a) The exact case ChatService.generateScene now presents after the flip to
	/// resolveMode(sdConfig, false): compositeMode genuinely cleared, useKontext left at its
	/// schema default (false). Before Phase 0 chat passed `true` here and ran Kontext.
	@Test
	public void absentFieldsResolveToClassicOnTheChatPath() throws Exception {
		BaseRecord cfg = sdConfig();
		cfg.set("compositeMode", null);
		assertEquals("useKontext schema default must be false", Boolean.FALSE, (Boolean) cfg.get("useKontext"));
		assertEquals("compositeMode cleared + useKontext unset must be classic with legacyKontextDefault=false",
			SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(cfg, false));
		assertEquals("classic must not emit the Kontext deprecation WARN", 0L, appender.deprecationCount());
	}

	/// (a') A null config — chat's other absent case — is also classic with the flipped default.
	@Test
	public void nullConfigResolvesToClassicWithTheFlippedDefault() {
		assertEquals(SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(null, false));
		assertEquals("classic must not emit the Kontext deprecation WARN", 0L, appender.deprecationCount());
	}

	// ── Behavioural: explicit opt-in still resolves to kontext, and that result is retired ──────

	/// (b) useKontext=true with compositeMode cleared — a saved pre-compositeMode config that opted
	/// in — is still IDENTIFIED as Kontext by resolveMode (resolution is pure; the legacy boolean
	/// survives until Phase 10 Remove), and that result is retired: every entry point refuses it.
	@Test
	public void explicitUseKontextResolvesToKontextWhichIsRetired() throws Exception {
		BaseRecord cfg = sdConfig();
		cfg.set("compositeMode", null);
		cfg.set("useKontext", true);
		String mode = SceneCompositeUtil.resolveMode(cfg, false);
		assertEquals(SceneCompositeUtil.MODE_KONTEXT, mode);
		assertTrue("the resolved kontext result must be reported as retired so the caller refuses it",
			SceneCompositeUtil.isRetiredMode(mode));
	}

	/// (c) compositeMode="kontext" is still IDENTIFIED as Kontext regardless of the legacy default —
	/// not downgraded to classic like a typo, so the caller can refuse it with the retirement message
	/// rather than the "unsupported" hint — and that result is retired.
	@Test
	public void explicitCompositeModeKontextResolvesToKontextWhichIsRetired() throws Exception {
		BaseRecord cfg = sdConfig();
		cfg.set("compositeMode", "kontext");
		String mode = SceneCompositeUtil.resolveMode(cfg, false);
		assertEquals(SceneCompositeUtil.MODE_KONTEXT, mode);
		assertTrue(SceneCompositeUtil.isRetiredMode(mode));
		cfg.set("compositeMode", " KONTEXT ");
		assertEquals("case/whitespace handling is unchanged",
			SceneCompositeUtil.MODE_KONTEXT, SceneCompositeUtil.resolveMode(cfg, true));
	}

	// ── Phase 2 Disable: the retirement surface ─────────────────────────────────

	/// (f) The vocabulary a caller may still ask for no longer contains kontext, the retired check
	/// recognises it (raw request value or resolveMode result, case/whitespace-insensitive) and
	/// nothing else, and the shared refusal text is the "replaced by V3" shape citing §37.3.
	@Test
	public void kontextIsRetiredNotSupported() {
		assertFalse("kontext must no longer be a supported mode", SceneCompositeUtil.isSupportedMode("kontext"));
		assertFalse(SceneCompositeUtil.isSupportedMode(" KONTEXT "));
		assertTrue(SceneCompositeUtil.isSupportedMode("flux2"));
		assertTrue(SceneCompositeUtil.isSupportedMode("classic"));
		assertEquals("flux2|classic", SceneCompositeUtil.SUPPORTED_MODES);

		assertTrue(SceneCompositeUtil.isRetiredMode("kontext"));
		assertTrue("retired check must be case/whitespace-insensitive like resolveMode", SceneCompositeUtil.isRetiredMode(" KONTEXT "));
		assertFalse(SceneCompositeUtil.isRetiredMode("flux2"));
		assertFalse(SceneCompositeUtil.isRetiredMode("classic"));
		assertFalse(SceneCompositeUtil.isRetiredMode("bogus"));
		assertFalse(SceneCompositeUtil.isRetiredMode(""));
		assertFalse(SceneCompositeUtil.isRetiredMode(null));

		String msg = SceneCompositeUtil.KONTEXT_RETIRED_MESSAGE;
		assertTrue("the refusal must be the 'replaced by V3' shape: " + msg, msg.contains("replaced by V3"));
		assertTrue("the refusal must cite the design section: " + msg, msg.contains("37.3"));
		assertTrue("the refusal must name the supported path: " + msg, msg.contains("flux2"));
		assertTrue("the refusal travels on the wire as JSON and must be plain ASCII: " + msg,
			msg.chars().allMatch(c -> c < 128));
	}

	// ── Behavioural: the deprecation WARN ───────────────────────────────────────

	/// (d) ONE WARN per resolution that lands on Kontext, from every path that can land there:
	/// explicit compositeMode, useKontext=true, and the caller's legacyKontextDefault on a null config.
	@Test
	public void warnIsEmittedOnceForEveryKontextResolution() throws Exception {
		BaseRecord byMode = sdConfig();
		byMode.set("compositeMode", "kontext");
		assertEquals(SceneCompositeUtil.MODE_KONTEXT, SceneCompositeUtil.resolveMode(byMode, false));
		assertEquals("compositeMode=kontext must WARN exactly once", 1L, appender.deprecationCount());

		BaseRecord byFlag = sdConfig();
		byFlag.set("compositeMode", null);
		byFlag.set("useKontext", true);
		assertEquals(SceneCompositeUtil.MODE_KONTEXT, SceneCompositeUtil.resolveMode(byFlag, false));
		assertEquals("useKontext=true must WARN exactly once more", 2L, appender.deprecationCount());

		assertEquals(SceneCompositeUtil.MODE_KONTEXT, SceneCompositeUtil.resolveMode(null, true));
		assertEquals("legacyKontextDefault=true on a null config must WARN exactly once more",
			3L, appender.deprecationCount());

		synchronized (appender.warnings) {
			String line = appender.warnings.stream().filter(TestKontextDeprecation::isDeprecationLine).findFirst().orElse(null);
			assertNotNull(line);
			assertTrue("the WARN must cite the design section", line.contains("§37.3"));
			assertTrue("the WARN must name the supported path", line.contains("FLUX.2"));
		}
	}

	/// (d') No WARN when the result is classic or flux2 — the deprecation notice must not fire for
	/// the paths people are supposed to be on. The unrelated "Unrecognized compositeMode" WARN for a
	/// typo is still allowed; only the Kontext deprecation line is counted.
	@Test
	public void noWarnForClassicOrFlux2() throws Exception {
		BaseRecord bare = sdConfig();
		assertEquals(SceneCompositeUtil.MODE_FLUX2, SceneCompositeUtil.resolveMode(bare, false));
		assertEquals(SceneCompositeUtil.MODE_FLUX2, SceneCompositeUtil.resolveMode(bare, true));

		BaseRecord classic = sdConfig();
		classic.set("compositeMode", "classic");
		assertEquals(SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(classic, true));

		BaseRecord flagOff = sdConfig();
		flagOff.set("compositeMode", null);
		flagOff.set("useKontext", false);
		assertEquals(SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(flagOff, true));

		BaseRecord typo = sdConfig();
		typo.set("compositeMode", "fluxx2");
		assertEquals(SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(typo, true));

		assertEquals(SceneCompositeUtil.MODE_CLASSIC, SceneCompositeUtil.resolveMode(null, false));

		assertEquals("no Kontext deprecation WARN for classic/flux2 results: " + appender.warnings,
			0L, appender.deprecationCount());
	}

	// ── Documentation check (not behavioural) ───────────────────────────────────

	/// (e) DOC CHECK: the six legacy fields carry the deprecation prefix in configModel.json. This
	/// reads the resource-derived schema (RecordFactory falls back to the resource when IOSystem is
	/// not initialised), so it checks the JSON, not any persisted blob. compositeMode itself is NOT
	/// deprecated and must not carry the prefix.
	@Test
	public void docCheckLegacyKontextFieldsAreMarkedDeprecated() throws Exception {
		OlioModelNames.use();
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_SD_CONFIG);
		assertNotNull("olio.sd.config schema", ms);
		for (String f : DEPRECATED_FIELDS) {
			FieldSchema fs = ms.getFieldSchema(f);
			assertNotNull("field " + f + " must exist on olio.sd.config", fs);
			String desc = fs.getDescription();
			assertNotNull("field " + f + " must have a description", desc);
			assertTrue("field " + f + " description must start with '" + DEPRECATED_PREFIX + "' but was: " + desc,
				desc.startsWith(DEPRECATED_PREFIX));
		}
		FieldSchema mode = ms.getFieldSchema("compositeMode");
		assertNotNull(mode);
		assertTrue("compositeMode is not deprecated and must not carry the prefix",
			mode.getDescription() == null || !mode.getDescription().startsWith(DEPRECATED_PREFIX));
	}
}
