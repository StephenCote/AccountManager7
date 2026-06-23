package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Phase 5 (design §11 Phase 4) — report aggregation test.
 *
 * <p>Builds ≥2 fixture {@code iso42001.testRun} records with mixed verdicts (constructed directly,
 * not via a live LLM — engine integration is already proven in Phase 3), aggregates them through
 * {@link ReportGenerator}, and asserts the rollups, overall verdict, ordered sections, control areas,
 * and that the content hash is set; then persists → re-reads (planMost) and confirms the sections and
 * rollups round-trip. Includes the negative-RBAC assertion that a non-Reporter cannot create a report.</p>
 *
 * <p>Standing rules: all CRUD through {@code AccessPoint} against the live {@code am7isotestdb};
 * fixtures created by the Testers-role {@code isoTester}; the report is created by the Reporters-role
 * {@code isoReporter}; no assertion runs as Admin.</p>
 */
@Category(IntegrationTest.class)
public class TestISO42001Report extends ISO42001BaseTest {

	@Test
	public void testReportAggregationAndRoundTrip() {
		/// --- Build two fixture runs with mixed verdicts across two model endpoints ---
		List<BaseRecord> run1Results = new ArrayList<>(Arrays.asList(
			fixtureResult("BIAS-ATTR-002", "BIAS", "Race", "PASS", 0.08, "COHENS_D", 0.42),
			fixtureResult("BIAS-HIRE-001", "BIAS", "Gender", "FLAG", 0.31, "ODDS_RATIO", 0.02)));
		BaseRecord run1 = createFixtureTestRun(isoTester, "qwen3:8b", run1Results);

		List<BaseRecord> run2Results = new ArrayList<>(Arrays.asList(
			fixtureResult("BIAS-REF-001", "BIAS", "Religion", "FAIL", 0.62, "COHENS_D", 0.001),
			fixtureResult("BIAS-ATTR-002", "BIAS", "Age", "PASS", 0.05, "COHENS_D", 0.50)));
		BaseRecord run2 = createFixtureTestRun(isoTester, "llama3.1:8b", run2Results);

		/// Sanity: fixtures persisted with embedded results.
		assertEquals("run1 should have 2 embedded results", 2, ((List<?>) run1.get("results")).size());
		assertEquals("run2 should have 2 embedded results", 2, ((List<?>) run2.get("results")).size());

		/// --- Generate the report as the Reporters-role user ---
		String reportName = "report-" + UUID.randomUUID();
		ReportGenerator gen = new ReportGenerator(isoReporter);
		BaseRecord report = gen.generate(reportName, "COMPLIANCE",
			Arrays.asList(run1, run2), sharedGroupId, orgId, (long) isoReporter.get(FieldNames.FIELD_ID));
		assertNotNull("ReportGenerator.generate returned null (RBAC?)", report);

		/// --- Rollups + overall verdict (FAIL > FLAG > PASS) ---
		assertEquals("passCount", 2, (int) report.get("passCount"));
		assertEquals("flagCount", 1, (int) report.get("flagCount"));
		assertEquals("failCount", 1, (int) report.get("failCount"));
		assertEquals("overallVerdict must be FAIL (a FAIL present)", "FAIL", report.get("overallVerdict"));

		/// --- modelsEvaluated (distinct across runs) ---
		List<String> models = report.get("modelsEvaluated");
		assertNotNull("modelsEvaluated is null", models);
		assertTrue("modelsEvaluated should contain both endpoints, got " + models,
			models.contains("qwen3:8b") && models.contains("llama3.1:8b"));

		/// --- controlAreas A.5.4 / A.5.5 ---
		List<String> controls = report.get("controlAreas");
		assertNotNull("controlAreas is null", controls);
		assertTrue("controlAreas must contain A.5.4 and A.5.5, got " + controls,
			controls.contains("A.5.4") && controls.contains("A.5.5"));

		/// --- Sections present + ordered ---
		List<BaseRecord> sections = report.get("sections");
		assertNotNull("sections is null", sections);
		assertEquals("expected 4 sections", 4, sections.size());
		String[] expectedTypes = { "EXECUTIVE_SUMMARY", "METHODOLOGY", "RESULTS", "MITIGATION" };
		for (int i = 0; i < expectedTypes.length; i++) {
			BaseRecord s = sections.get(i);
			assertEquals("section " + i + " order", i, (int) s.get("sectionOrder"));
			assertEquals("section " + i + " type", expectedTypes[i], s.get("sectionType"));
			assertNotNull("section " + i + " content is null", s.get("content"));
		}
		/// RESULTS section carries chartData JSON for the PDF charts.
		BaseRecord resultsSection = sections.get(2);
		String chartData = resultsSection.get("chartData");
		assertNotNull("RESULTS section chartData is null", chartData);
		assertTrue("chartData should be a JSON object with heatMap+effectBars",
			chartData.contains("heatMap") && chartData.contains("effectBars"));

		/// --- Content hash set (SHA-256 = 32 bytes) so Phase 6 can sign it ---
		Object hashObj = report.get("hash");
		assertTrue("report.hash must be a byte[]", hashObj instanceof byte[]);
		assertEquals("SHA-256 hash must be 32 bytes", 32, ((byte[]) hashObj).length);

		/// --- Persist round-trip: re-read with planMost and confirm sections + rollups survive ---
		String oid = report.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("report objectId not stamped", oid);
		BaseRecord reread = findByObjectId(isoReporter, ISO42001ModelNames.MODEL_REPORT, oid);
		assertNotNull("report not re-readable", reread);
		assertEquals("round-trip passCount", 2, (int) reread.get("passCount"));
		assertEquals("round-trip flagCount", 1, (int) reread.get("flagCount"));
		assertEquals("round-trip failCount", 1, (int) reread.get("failCount"));
		assertEquals("round-trip overallVerdict", "FAIL", reread.get("overallVerdict"));
		List<BaseRecord> rereadSections = reread.get("sections");
		assertNotNull("round-trip sections null", rereadSections);
		assertEquals("round-trip should have 4 sections", 4, rereadSections.size());

		/// --- Negative RBAC: isoTester (no ISO42001Reporters role) cannot create a report ---
		ReportGenerator denied = new ReportGenerator(isoTester);
		BaseRecord shouldBeNull = denied.generate("denied-" + UUID.randomUUID(), "COMPLIANCE",
			Arrays.asList(run1), sharedGroupId, orgId, (long) isoTester.get(FieldNames.FIELD_ID));
		assertNull("report CREATE by non-Reporter (isoTester) MUST be denied", shouldBeNull);
	}
}
