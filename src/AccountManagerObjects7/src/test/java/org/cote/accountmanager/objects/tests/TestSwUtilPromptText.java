package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;
import org.junit.Test;

/**
 * Pure-JUnit coverage of the prompt-text helpers in {@link SWUtil} that the live FLUX.2 request builders
 * call on every scene ({@code stripSDXLWeighting}: {@code SWUtil.newFlux2SceneTxt2Img} and the portrait /
 * landscape builders). No database, no server, no {@code BaseTest}.
 * <p>
 * Relocated here by P5-3 from {@code TestKontext.testStripSDXLWeighting}: that class is Surefire-excluded
 * ({@code pom.xml}), so the only test of a helper on the live path was never run. The assertions are the
 * original ones, unchanged.
 */
public class TestSwUtilPromptText {

	public static final Logger logger = LogManager.getLogger(TestSwUtilPromptText.class);

	@Test
	public void testStripSDXLWeighting() {
		logger.info("testStripSDXLWeighting");

		/// SDXL-style weighted prompt
		String input = "a beautiful athletic ((twenty five:1.5) (25yo:1.5) (white) (woman)) with ((short and curly) (blond hair)) and (electric blue eyes)";
		String cleaned = SWUtil.stripSDXLWeighting(input);
		assertNotNull("Cleaned prompt should not be null", cleaned);
		assertTrue("Should not contain parentheses", !cleaned.contains("(") && !cleaned.contains(")"));
		assertTrue("Should not contain weight numbers", !cleaned.contains(":1.5"));
		assertTrue("Should preserve text content", cleaned.contains("twenty five"));
		assertTrue("Should preserve text content", cleaned.contains("blond hair"));
		logger.info("Input:   " + input);
		logger.info("Cleaned: " + cleaned);

		/// Already clean prompt
		assertEquals("Clean prompt should pass through", "a man with brown hair", SWUtil.stripSDXLWeighting("a man with brown hair"));

		/// Null/empty
		assertNull("Null should return null", SWUtil.stripSDXLWeighting(null));
		assertEquals("Empty should return empty", "", SWUtil.stripSDXLWeighting(""));
	}

	/// The weight syntax must not leak into a FLUX.2 prompt: the strip is what keeps `(word:1.3)` out of
	/// the text the model reads literally. Nested groups and a bare weight both reduce to plain words.
	@Test
	public void testStripSDXLWeightingNestedAndBareWeight() {
		String nested = SWUtil.stripSDXLWeighting("(((a red (silk) dress:1.2)))");
		assertFalse("No parenthesis survives nesting: " + nested, nested.contains("(") || nested.contains(")"));
		assertFalse("No weight survives nesting: " + nested, nested.contains(":1.2"));
		assertTrue("Words survive nesting: " + nested, nested.contains("red") && nested.contains("silk") && nested.contains("dress"));

		String bare = SWUtil.stripSDXLWeighting("portrait, (detailed face:1.4), soft light");
		assertFalse("Bare weight removed: " + bare, bare.contains(":1.4"));
		assertTrue("Surrounding text kept: " + bare, bare.startsWith("portrait") && bare.endsWith("soft light"));
	}
}
