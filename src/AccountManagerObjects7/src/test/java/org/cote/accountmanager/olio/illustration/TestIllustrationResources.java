package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 2, P2-1: the two illustration resources (design §15 capabilities, §18 quality presets)
 * load from the classpath and resolve the way the design says. Pure - no DB, no SD, no BaseTest.
 * <p>
 * What this does NOT cover: the missing-resource / malformed-resource fallback path itself. Reaching
 * it would mean removing the file from the classpath or substituting {@code ResourceUtil}; neither is
 * done here. Because both loaders fall back silently to a FALLBACK table equal to the shipped resource,
 * the value assertions alone cannot tell "loaded from the file" from "fell back" - the two
 * {@code *ResourceIsOnTheClasspathAndIsWhatTheLoaderServes} cases close that by parsing each file
 * directly and comparing it to what the loader serves.
 */
public class TestIllustrationResources {

	private static final double EPS = 0.0001;

	@Before
	public void reload() {
		QualityPresets.clearCache();
		RendererCapabilities.clearCache();
	}

	// ───────────────────────────── §18 quality presets ─────────────────────────────

	@Test
	public void draftIsTheDefaultPreset() {
		assertEquals(PbQualityPresetEnumType.DRAFT, QualityPresets.defaultPreset());
		assertEquals(PbQualityPresetEnumType.DRAFT, QualityPresets.resolve(null).getName());
		assertEquals(PbQualityPresetEnumType.DRAFT, QualityPresets.resolve(PbQualityPresetEnumType.UNKNOWN).getName());
	}

	@Test
	public void draftPresetMatchesSection18() {
		QualityPresets.Preset p = QualityPresets.resolve(PbQualityPresetEnumType.DRAFT);
		assertEquals(PbQualityPresetEnumType.DRAFT, p.getName());
		assertEquals(0.5, p.getResolutionScale(), EPS);
		assertEquals(QualityPresets.STEPS_LOW, p.getStepsMode());
		assertEquals(1, p.getVariants());
		assertFalse(p.isUpscale());
		assertEquals(QualityPresets.QA_OFF, p.getQa());
	}

	@Test
	public void standardPresetMatchesSection18() {
		QualityPresets.Preset p = QualityPresets.resolve(PbQualityPresetEnumType.STANDARD);
		assertEquals(PbQualityPresetEnumType.STANDARD, p.getName());
		assertEquals(1.0, p.getResolutionScale(), EPS);
		assertEquals(QualityPresets.STEPS_DEFAULT, p.getStepsMode());
		assertEquals(2, p.getVariants());
		assertFalse(p.isUpscale());
		assertEquals(QualityPresets.QA_CANONICAL, p.getQa());
	}

	@Test
	public void finalPresetMatchesSection18() {
		QualityPresets.Preset p = QualityPresets.resolve(PbQualityPresetEnumType.FINAL);
		assertEquals(PbQualityPresetEnumType.FINAL, p.getName());
		assertEquals(1.0, p.getResolutionScale(), EPS);
		assertEquals(QualityPresets.STEPS_HIGH, p.getStepsMode());
		assertEquals(4, p.getVariants());
		assertTrue(p.isUpscale());
		assertEquals(QualityPresets.QA_ALL, p.getQa());
	}

	@Test
	public void everyNamedPresetResolvesNonNull() {
		for(PbQualityPresetEnumType t : PbQualityPresetEnumType.values()) {
			QualityPresets.Preset p = QualityPresets.resolve(t);
			assertNotNull("resolve(" + t + ")", p);
			assertNotNull(p.getName());
			assertTrue(p.getResolutionScale() > 0);
			assertTrue(p.getVariants() >= 1);
		}
	}

	// ───────────────────────────── §15 renderer capabilities ─────────────────────────────

	@Test
	public void familiesAreTheThreeDeclaredInResourceOrder() {
		List<RendererCapabilities> fams = RendererCapabilities.families();
		assertEquals(3, fams.size());
		assertEquals(RendererCapabilities.PATTERN_FLUX2, fams.get(0).getPattern());
		assertEquals(RendererCapabilities.PATTERN_SDXL, fams.get(1).getPattern());
		assertEquals(RendererCapabilities.PATTERN_KONTEXT, fams.get(2).getPattern());
	}

	@Test
	public void flux2FamilyMatchesTheInstalledKleinCheckpoint() {
		RendererCapabilities rc = RendererCapabilities.forModel("flux2Klein_9b");
		assertNotNull(rc);
		assertEquals(RendererCapabilities.PATTERN_FLUX2, rc.getPattern());
		assertTrue(rc.isEnabled());
		assertEquals(3, rc.getMaxReferences());
		assertTrue(rc.isSupportsReferences());
		assertTrue(rc.isSupportsStyleReferences());
		assertTrue(rc.isSupportsInitImage());
		assertFalse(rc.isSupportsMasks());
		assertFalse(rc.isSupportsRegionalPrompt());
		assertTrue(rc.isSupportsSeedReplay());
		assertTrue(rc.isSupportsCancel());
		assertTrue(rc.isSupportsBatch());
		assertEquals(4, rc.getRecommendedStepsLow());
		assertEquals(4, rc.getRecommendedStepsDefault());
		assertEquals(24, rc.getRecommendedStepsHigh());
		assertEquals(1.0, rc.getCfgMin(), EPS);
		assertEquals(3.5, rc.getCfgMax(), EPS);
		assertResolutions(rc, new int[][] {{1024, 768}, {768, 1024}, {1024, 1024}});
	}

	@Test
	public void sdxlFamilyMatchesBothInstalledSdxlNames() {
		RendererCapabilities jug = RendererCapabilities.forModel("juggernautXL_ragnarokBy");
		assertNotNull(jug);
		assertEquals(RendererCapabilities.PATTERN_SDXL, jug.getPattern());
		RendererCapabilities base = RendererCapabilities.forModel("OfficialStableDiffusion/sd_xl_base_1.0.safetensors");
		assertNotNull(base);
		assertSame("one family instance per pattern", jug, base);
		assertTrue(jug.isEnabled());
		assertEquals(0, jug.getMaxReferences());
		assertFalse(jug.isSupportsReferences());
		assertFalse(jug.isSupportsStyleReferences());
		assertTrue(jug.isSupportsInitImage());
		assertTrue(jug.isSupportsMasks());
		assertFalse(jug.isSupportsRegionalPrompt());
		assertTrue(jug.isSupportsSeedReplay());
		assertTrue(jug.isSupportsCancel());
		assertTrue(jug.isSupportsBatch());
		assertEquals(12, jug.getRecommendedStepsLow());
		assertEquals(20, jug.getRecommendedStepsDefault());
		assertEquals(30, jug.getRecommendedStepsHigh());
		assertEquals(5.0, jug.getCfgMin(), EPS);
		assertEquals(7.0, jug.getCfgMax(), EPS);
		assertResolutions(jug, new int[][] {{1024, 1024}, {1024, 768}, {768, 1024}});
	}

	@Test
	public void globsTryTheBasenameAfterTheFullName() {
		RendererCapabilities rc = RendererCapabilities.forModel("Flux/flux2Klein_9b.safetensors");
		assertNotNull("'Flux/flux2Klein_9b.safetensors' does not match 'flux2*' as a whole; its basename does", rc);
		assertEquals(RendererCapabilities.PATTERN_FLUX2, rc.getPattern());
		assertTrue(rc.matches("  FLUX2KLEIN_9B  "));
		assertFalse(rc.matches("Flux/"));
	}

	@Test
	public void globsAreCaseInsensitive() {
		assertEquals(RendererCapabilities.PATTERN_SDXL, RendererCapabilities.forModel("JUGGERNAUTxl_RAGNAROKBY").getPattern());
		assertEquals(RendererCapabilities.PATTERN_FLUX2, RendererCapabilities.forModel("FLUX2klein_9b").getPattern());
	}

	@Test
	public void kontextIsKnownButDisabled() {
		RendererCapabilities rc = RendererCapabilities.forModel("flux1-kontext-dev");
		assertNotNull("the family is declared so the refusal can name it", rc);
		assertEquals(RendererCapabilities.PATTERN_KONTEXT, rc.getPattern());
		assertFalse("Kontext is retired (§37.3)", rc.isEnabled());
		assertEquals(1, rc.getMaxReferences());
		assertTrue(rc.isSupportsReferences());
		assertFalse(rc.isSupportsStyleReferences());
		assertFalse(rc.isSupportsBatch());
		assertEquals(1.0, rc.getCfgMin(), EPS);
		assertEquals(1.0, rc.getCfgMax(), EPS);
	}

	@Test
	public void unknownNamesResolveToNullNotAGuess() {
		assertNull(RendererCapabilities.forModel("nope"));
		assertNull(RendererCapabilities.forModel("sd15_v15pruned"));
		assertNull(RendererCapabilities.forModel(null));
		assertNull(RendererCapabilities.forModel(""));
		assertNull(RendererCapabilities.forModel("   "));
	}

	@Test
	public void flux2WinsOverSdxlWhenBothCouldMatch() {
		/// "flux2*" is first in resource order; a name matching both globs lands on flux2.
		RendererCapabilities rc = RendererCapabilities.forModel("flux2_xl_experimental");
		assertNotNull(rc);
		assertEquals(RendererCapabilities.PATTERN_FLUX2, rc.getPattern());
	}

	@Test
	public void noFamilyCarriesATimingUntilMeasuredOnDotThirtyNine() {
		for(RendererCapabilities rc : RendererCapabilities.families()) {
			assertNull(rc.getPattern() + " secondsPerStepPerReference must stay null until measured (§15/§18)",
				rc.getSecondsPerStepPerReference());
		}
	}

	@Test
	public void recommendedStepsFollowsThePresetStepsMode() {
		RendererCapabilities flux2 = RendererCapabilities.forModel("flux2Klein_9b");
		assertEquals(4, flux2.recommendedSteps(QualityPresets.STEPS_LOW));
		assertEquals(4, flux2.recommendedSteps(QualityPresets.STEPS_DEFAULT));
		assertEquals(24, flux2.recommendedSteps(QualityPresets.STEPS_HIGH));
		assertEquals("stepsMode is case-insensitive", 24, flux2.recommendedSteps("HIGH"));
		assertEquals("anything else is default", 4, flux2.recommendedSteps("bogus"));
		assertEquals("null is default", 4, flux2.recommendedSteps(null));

		RendererCapabilities sdxl = RendererCapabilities.forModel("juggernautXL_ragnarokBy");
		assertEquals(12, sdxl.recommendedSteps(QualityPresets.resolve(PbQualityPresetEnumType.DRAFT).getStepsMode()));
		assertEquals(20, sdxl.recommendedSteps(QualityPresets.resolve(PbQualityPresetEnumType.STANDARD).getStepsMode()));
		assertEquals(30, sdxl.recommendedSteps(QualityPresets.resolve(PbQualityPresetEnumType.FINAL).getStepsMode()));
	}

	@Test
	public void nativeResolutionsAreUnmodifiable() {
		RendererCapabilities rc = RendererCapabilities.forModel("flux2Klein_9b");
		try {
			rc.getNativeResolutions().add(new int[] {1, 1});
			assertTrue("getNativeResolutions() must be unmodifiable", false);
		}
		catch(UnsupportedOperationException expected) {
			/// the list refused the write
		}
		try {
			RendererCapabilities.families().clear();
			assertTrue("families() must be unmodifiable", false);
		}
		catch(UnsupportedOperationException expected) {
			/// the list refused the write
		}
	}

	@Test
	public void clearCacheReloadsTheSameValues() {
		RendererCapabilities before = RendererCapabilities.forModel("flux2Klein_9b");
		QualityPresets.Preset pBefore = QualityPresets.resolve(PbQualityPresetEnumType.FINAL);
		RendererCapabilities.clearCache();
		QualityPresets.clearCache();
		RendererCapabilities after = RendererCapabilities.forModel("flux2Klein_9b");
		QualityPresets.Preset pAfter = QualityPresets.resolve(PbQualityPresetEnumType.FINAL);
		assertNotNull(after);
		assertTrue("a reload builds a new instance from the resource", before != after);
		assertEquals(before.toString(), after.toString());
		assertEquals(pBefore.toString(), pAfter.toString());
	}

	@Test
	public void globToRegexEscapesEverythingButWildcards() {
		assertTrue(RendererCapabilities.globToRegex("a.b*").matcher("a.bXYZ").matches());
		assertFalse("'.' is literal, not any-char", RendererCapabilities.globToRegex("a.b*").matcher("aXb").matches());
		assertTrue(RendererCapabilities.globToRegex("sd?l").matcher("sdxl").matches());
		assertFalse(RendererCapabilities.globToRegex("sd?l").matcher("sdxxl").matches());
		assertTrue(RendererCapabilities.globToRegex("*XL*").matcher("juggernautxl_v9").matches());
	}

	// ───────────────────── the resources themselves, not just the loaders' answers ─────────────────────

	/**
	 * Every case above would still pass if {@code qualityPresets.json} were deleted or malformed, because
	 * {@code QualityPresets.load()} falls back to a FALLBACK table that equals the shipped resource and the
	 * only tell is an ERROR log line. This case reads the resource off the classpath itself, parses it, and
	 * asserts that what the loader serves is what the file says - so a missing or unparseable file, or a
	 * file that drifted from the fallback, fails here rather than being silently papered over.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void qualityPresetsResourceIsOnTheClasspathAndIsWhatTheLoaderServes() {
		String json = ResourceUtil.getInstance().getResource("olio/illustration/qualityPresets.json");
		assertNotNull("olio/illustration/qualityPresets.json must be on the classpath - the loader falls back silently without it", json);
		Map<String, Object> parsed = JSONUtil.getMap(json.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertNotNull("qualityPresets.json must parse as a JSON object", parsed);

		assertEquals("defaultPreset in the file is what defaultPreset() serves",
			QualityPresets.defaultPreset().name(), String.valueOf(parsed.get("defaultPreset")).trim().toUpperCase());

		Object presets = parsed.get("presets");
		assertTrue("'presets' must be an object", presets instanceof Map);
		Map<String, Object> presetMap = (Map<String, Object>) presets;
		assertEquals("the file names exactly the non-UNKNOWN enum values", PbQualityPresetEnumType.values().length - 1, presetMap.size());
		for(Map.Entry<String, Object> e : presetMap.entrySet()) {
			PbQualityPresetEnumType key = PbQualityPresetEnumType.valueOf(e.getKey());
			assertTrue("'presets." + e.getKey() + "' must be an object", e.getValue() instanceof Map);
			Map<String, Object> f = (Map<String, Object>) e.getValue();
			QualityPresets.Preset p = QualityPresets.resolve(key);
			assertEquals(key + ".resolutionScale", ((Number) f.get("resolutionScale")).doubleValue(), p.getResolutionScale(), EPS);
			assertEquals(key + ".stepsMode", f.get("stepsMode"), p.getStepsMode());
			assertEquals(key + ".variants", ((Number) f.get("variants")).intValue(), p.getVariants());
			assertEquals(key + ".upscale", f.get("upscale"), Boolean.valueOf(p.isUpscale()));
			assertEquals(key + ".qa", String.valueOf(f.get("qa")).toUpperCase(), p.getQa());
		}
	}

	/** Same proof for {@code capabilities.json}: see {@link #qualityPresetsResourceIsOnTheClasspathAndIsWhatTheLoaderServes}. */
	@Test
	@SuppressWarnings("unchecked")
	public void capabilitiesResourceIsOnTheClasspathAndIsWhatTheLoaderServes() {
		String json = ResourceUtil.getInstance().getResource("olio/illustration/capabilities.json");
		assertNotNull("olio/illustration/capabilities.json must be on the classpath - the loader falls back silently without it", json);
		Map<String, Object> parsed = JSONUtil.getMap(json.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		assertNotNull("capabilities.json must parse as a JSON object", parsed);
		Object fams = parsed.get("families");
		assertTrue("'families' must be a non-empty array", fams instanceof List && !((List<Object>) fams).isEmpty());
		List<Object> fileFamilies = (List<Object>) fams;
		List<RendererCapabilities> served = RendererCapabilities.families();
		assertEquals("one served family per declared family, in file order", fileFamilies.size(), served.size());

		for(int i = 0; i < fileFamilies.size(); i++) {
			assertTrue("families[" + i + "] must be an object", fileFamilies.get(i) instanceof Map);
			Map<String, Object> f = (Map<String, Object>) fileFamilies.get(i);
			RendererCapabilities rc = served.get(i);
			String p = String.valueOf(f.get("pattern"));
			assertEquals("families[" + i + "].pattern", p, rc.getPattern());
			assertEquals(p + ".enabled", f.get("enabled"), Boolean.valueOf(rc.isEnabled()));
			assertEquals(p + ".maxReferences", ((Number) f.get("maxReferences")).intValue(), rc.getMaxReferences());
			assertEquals(p + ".supportsReferences", f.get("supportsReferences"), Boolean.valueOf(rc.isSupportsReferences()));
			assertEquals(p + ".supportsStyleReferences", f.get("supportsStyleReferences"), Boolean.valueOf(rc.isSupportsStyleReferences()));
			assertEquals(p + ".supportsInitImage", f.get("supportsInitImage"), Boolean.valueOf(rc.isSupportsInitImage()));
			assertEquals(p + ".supportsMasks", f.get("supportsMasks"), Boolean.valueOf(rc.isSupportsMasks()));
			assertEquals(p + ".supportsRegionalPrompt", f.get("supportsRegionalPrompt"), Boolean.valueOf(rc.isSupportsRegionalPrompt()));
			assertEquals(p + ".supportsSeedReplay", f.get("supportsSeedReplay"), Boolean.valueOf(rc.isSupportsSeedReplay()));
			assertEquals(p + ".supportsCancel", f.get("supportsCancel"), Boolean.valueOf(rc.isSupportsCancel()));
			assertEquals(p + ".supportsBatch", f.get("supportsBatch"), Boolean.valueOf(rc.isSupportsBatch()));
			Map<String, Object> steps = (Map<String, Object>) f.get("recommendedSteps");
			assertEquals(p + ".recommendedSteps.low", ((Number) steps.get("low")).intValue(), rc.getRecommendedStepsLow());
			assertEquals(p + ".recommendedSteps.default", ((Number) steps.get("default")).intValue(), rc.getRecommendedStepsDefault());
			assertEquals(p + ".recommendedSteps.high", ((Number) steps.get("high")).intValue(), rc.getRecommendedStepsHigh());
			List<Object> cfg = (List<Object>) f.get("cfgRange");
			assertEquals(p + ".cfgRange[0]", ((Number) cfg.get(0)).doubleValue(), rc.getCfgMin(), EPS);
			assertEquals(p + ".cfgRange[1]", ((Number) cfg.get(1)).doubleValue(), rc.getCfgMax(), EPS);
			List<Object> res = (List<Object>) f.get("nativeResolutions");
			int[][] expected = new int[res.size()][];
			for(int r = 0; r < res.size(); r++) {
				List<Object> wh = (List<Object>) res.get(r);
				expected[r] = new int[] {((Number) wh.get(0)).intValue(), ((Number) wh.get(1)).intValue()};
			}
			assertResolutions(rc, expected);
			assertTrue(p + ".secondsPerStepPerReference must be declared (null) in the file, not omitted", f.containsKey("secondsPerStepPerReference"));
			assertEquals(p + ".secondsPerStepPerReference", f.get("secondsPerStepPerReference"), rc.getSecondsPerStepPerReference());
		}
	}

	private static void assertResolutions(RendererCapabilities rc, int[][] expected) {
		List<int[]> actual = rc.getNativeResolutions();
		assertEquals(rc.getPattern() + " nativeResolutions count", expected.length, actual.size());
		for(int i = 0; i < expected.length; i++) {
			assertEquals(rc.getPattern() + " [" + i + "].w", expected[i][0], actual.get(i)[0]);
			assertEquals(rc.getPattern() + " [" + i + "].h", expected[i][1], actual.get(i)[1]);
		}
	}
}
