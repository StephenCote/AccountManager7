package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.ColorUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.parsers.data.HSLInterceptor;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Test;

/**
 * Colour library HSB data and the complementary-colour lookup (2026-11-18).
 * <p>
 * Defects covered: (1) {@code HSLInterceptor} skipped HSL for any colour whose blue value is a single digit
 * ({@code length() > 1}), so 89 of the 864 library colours shipped with hue/saturation/lightness 0;
 * (2) {@code ColorUtil.getHSL} returned DEGREES / x100 while every persisted row and the complement SQL use the
 * 0..1 {@code Color.RGBtoHSB} scale; (3) {@code findComplementaryColor} picked {@code ORDER BY abs(hue)} (the
 * lower candidate, not the nearer), did not scope candidates to the library's group, and sent every achromatic
 * colour (and every zeroed one) to the same teal.
 */
public class TestColorComplement extends BaseTest {

	private OlioContext getOlioContext() {
		String dataPath = testProperties.getProperty("test.datagen.path");
		try {
			return OlioTestUtil.getContext(orgContext, dataPath);
		} catch (StackOverflowError | Exception e) {
			logger.error("Failed to get OlioContext: " + e.getMessage());
			return null;
		}
	}

	/// findComplementaryColor returns a reader record that does not carry every field; read hue/saturation explicitly.
	private static double[] hueSat(BaseRecord color) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_COLOR, FieldNames.FIELD_ID, color.get(FieldNames.FIELD_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, "hue", "saturation" });
		q.setCache(false);
		BaseRecord full = IOSystem.getActiveContext().getSearch().findRecord(q);
		return new double[] { ((Number)full.get("hue")).doubleValue(), ((Number)full.get("saturation")).doubleValue() };
	}

	@Test
	public void getHslIsTheZeroToOneHsbScaleOfThePersistedLibrary() {
		/// Air Force Blue (Raf) as stored in olio/colors.json (an entry that was always correct).
		double[] afb = ColorUtil.getHSL(93, 138, 168);
		assertEquals(0.56666666, afb[0], 1e-6);
		assertEquals(0.44642857, afb[1], 1e-6);
		assertEquals(0.65882355, afb[2], 1e-6);
		/// Islamic Green #009000 — one of the 89 that shipped as 0/0/0.
		double[] ig = ColorUtil.getHSL(0, 144, 0);
		assertEquals(1.0 / 3.0, ig[0], 1e-6);
		assertEquals(1.0, ig[1], 1e-6);
		assertEquals(144.0 / 255.0, ig[2], 1e-6);
	}

	@Test
	public void interceptorComputesHslForASingleDigitBlue() throws Exception {
		BaseRecord rec = RecordFactory.newInstance(ModelNames.MODEL_COLOR);
		rec.setValue("red", 255);
		rec.setValue("green", 191);
		new HSLInterceptor().filterField(null, null, null, rec, null, null, "0"); // Amber #ffbf00
		double hue = rec.get("hue");
		double sat = rec.get("saturation");
		assertTrue("hue computed for blue=0 (was skipped): " + hue, hue > 0.12 && hue < 0.13);
		assertEquals(1.0, sat, 1e-6);
	}

	@Test
	public void shippedColorsJsonHasNoZeroedChromaticEntries() {
		/// Hand-entered rows are allowed (Hazel carries true HSL 0.132/0.71/0.32, never zeroed); what must never
		/// recur is a chromatic colour shipped as 0/0/0, or a hue that disagrees with its RGB.
		List<BaseRecord> colors = JSONUtil.getList(ResourceUtil.getInstance().getResource("olio/colors.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertTrue(colors.size() > 800);
		int bad = 0;
		for(BaseRecord c : colors) {
			int r = c.get("red"), g = c.get("green"), b = c.get("blue");
			double[] want = ColorUtil.getHSL(r, g, b);
			double hue = ((Number)c.get("hue")).doubleValue();
			double sat = ((Number)c.get("saturation")).doubleValue();
			double lig = ((Number)c.get("lightness")).doubleValue();
			boolean zeroed = (hue == 0 && sat == 0 && lig == 0 && !(r == g && g == b));
			if(zeroed || Math.abs(hue - want[0]) > 0.01) {
				bad++;
				logger.error("colors.json mismatch: " + c.get("name") + " " + c.get("hex"));
			}
		}
		assertEquals("no zeroed chromatic entry, and every hue matches its RGB", 0, bad);
	}

	@Test
	public void complementIsTheNearestChromaticOppositeAndGreysComplementToThemselves() {
		OlioContext octx = getOlioContext();
		assumeTrue("Context is null - skipping test", octx != null);
		BaseRecord universe = octx.getUniverse();
		ColorUtil.clearCache();

		/// Islamic Green (hue 1/3) → nearest chromatic colour to hue 5/6 (magenta-ish), never the old teal.
		BaseRecord comp = ColorUtil.findComplementaryColor(universe, "#009000");
		assertNotNull("complement of Islamic Green", comp);
		double[] hs = hueSat(comp);
		double h = hs[0];
		double s = hs[1];
		logger.info("Complement of Islamic Green: " + comp.get("name") + " hue=" + h);
		assertTrue("complement hue near 0.8333: " + h, Math.abs(h - (1.0 / 3.0 + 0.5)) < 0.03);
		assertTrue("complement is chromatic", s > 0);
		assertNotEquals("Teal Green", comp.get("name"));

		/// Air Force Blue (Raf) hue 0.5667 → near 0.0667.
		BaseRecord comp2 = ColorUtil.findComplementaryColor(universe, "#5d8aa8");
		assertNotNull(comp2);
		double h2 = hueSat(comp2)[0];
		assertTrue("complement hue near 0.0667: " + h2, Math.abs(h2 - 0.06666666) < 0.03);

		/// Black has no hue to oppose: it complements to itself.
		BaseRecord black = ColorUtil.findComplementaryColor(universe, "#000000");
		assertNotNull("black resolves", black);
		assertEquals("#000000", ((String)black.get("hex")).toLowerCase());
	}
}
