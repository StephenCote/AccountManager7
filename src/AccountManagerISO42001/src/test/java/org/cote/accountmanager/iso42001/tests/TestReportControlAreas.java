package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.iso42001.reporting.ReportData;
import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * P2-4c: pure-logic verification of {@link ReportGenerator#deriveControlAreas} — no DB, no LLM, no
 * AccessPoint (built entirely from a constructed {@link ReportData}, {@code testRuns == null}).
 *
 * <p>Regression target: previously an unknown, non-empty module id fell through the
 * {@code controls.isEmpty()} guard to {@code BIAS_CONTROL_AREAS}, mislabeling any non-BIAS module as
 * a bias control (A.5.4/A.5.5). The fix falls back to bias ONLY when no module is derivable at all,
 * and records a present-but-unmapped module as {@code UNMAPPED:<moduleId>}. BIAS behavior is
 * unchanged.</p>
 */
@Category(UnitTest.class)
public class TestReportControlAreas {

	private static ReportData withModules(String... modules) {
		ReportData data = new ReportData();
		for (String m : modules) {
			ReportData.Row row = new ReportData.Row();
			row.testModule = m;
			row.verdict = "PASS";
			data.addRow(row);
		}
		return data;
	}

	/** BIAS module → A.5.4/A.5.5 exactly (behavior must be identical after P2-4c). */
	@Test
	public void biasModuleMapsToBiasControls() {
		List<String> c = ReportGenerator.deriveControlAreas(withModules("BIAS"), null);
		assertTrue("BIAS must map to A.5.4", c.contains("A.5.4"));
		assertTrue("BIAS must map to A.5.5", c.contains("A.5.5"));
		assertEquals("BIAS must map to exactly the two bias controls", 2, c.size());
	}

	/** No module derivable at all → documented bias-suite fallback (unchanged). */
	@Test
	public void noModuleFallsBackToBias() {
		List<String> c = ReportGenerator.deriveControlAreas(new ReportData(), null);
		assertTrue("empty-module report keeps the bias default", c.contains("A.5.4") && c.contains("A.5.5"));
		assertEquals(2, c.size());
	}

	/**
	 * The regression: an unknown, non-empty, NON-BIAS module must NOT default to the bias controls.
	 * It yields an explicit UNMAPPED marker and never A.5.4/A.5.5.
	 */
	@Test
	public void unknownNonBiasModuleIsNotLabeledBias() {
		List<String> c = ReportGenerator.deriveControlAreas(withModules("TRANS"), null);
		assertFalse("unknown module must NOT be labeled A.5.4 (bias)", c.contains("A.5.4"));
		assertFalse("unknown module must NOT be labeled A.5.5 (bias)", c.contains("A.5.5"));
		assertTrue("unknown module must be recorded as UNMAPPED",
			c.contains(ReportGenerator.UNMAPPED_PREFIX + "TRANS"));
	}

	/** Mixed BIAS + unknown: bias controls preserved AND the unknown recorded honestly — no cross-labeling. */
	@Test
	public void mixedBiasAndUnknownKeepsBothHonest() {
		List<String> c = ReportGenerator.deriveControlAreas(withModules("BIAS", "TRANS"), null);
		assertTrue("BIAS controls preserved in a mixed report", c.contains("A.5.4") && c.contains("A.5.5"));
		assertTrue("unknown module still recorded as UNMAPPED in a mixed report",
			c.contains(ReportGenerator.UNMAPPED_PREFIX + "TRANS"));
	}
}
