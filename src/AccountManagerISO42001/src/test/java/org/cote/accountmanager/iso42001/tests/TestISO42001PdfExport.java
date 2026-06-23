package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.iso42001.reporting.PdfExporter;
import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Phase 5 (design §11 Phase 4) — signed/exported PDF test.
 *
 * <p>Generates a report from fixture runs, exports it to PDF via {@link PdfExporter}, and asserts the
 * stored {@code data.data}: it exists, {@code contentType=application/pdf}, the bytes start with the
 * {@code %PDF} magic, the size is non-trivial, and {@code report.exportedPdf} resolves on re-read.
 * The certification block renders "NOT CERTIFIED" (Phase 6 fills it).</p>
 *
 * <p>All operations run as the Reporters-role {@code isoReporter}; fixtures are created by the
 * Testers-role {@code isoTester}; no assertion runs as Admin. CRUD goes through {@code AccessPoint}
 * against the live {@code am7isotestdb}.</p>
 */
@Category(IntegrationTest.class)
public class TestISO42001PdfExport extends ISO42001BaseTest {

	@Test
	public void testPdfExportAndReference() {
		/// --- Fixture run with mixed verdicts so the report has content + charts ---
		List<BaseRecord> results = new ArrayList<>(Arrays.asList(
			fixtureResult("BIAS-ATTR-002", "BIAS", "Race", "PASS", 0.08, "COHENS_D", 0.42),
			fixtureResult("BIAS-HIRE-001", "BIAS", "Gender", "FLAG", 0.31, "ODDS_RATIO", 0.02),
			fixtureResult("BIAS-REF-001", "BIAS", "Religion", "FAIL", 0.62, "COHENS_D", 0.001)));
		BaseRecord run = createFixtureTestRun(isoTester, "qwen3:8b", results);

		/// --- Generate the report as the Reporters-role user ---
		ReportGenerator gen = new ReportGenerator(isoReporter);
		BaseRecord report = gen.generate("pdf-report-" + UUID.randomUUID(), "COMPLIANCE",
			Arrays.asList(run), sharedGroupId, orgId, (long) isoReporter.get(FieldNames.FIELD_ID));
		assertNotNull("ReportGenerator.generate returned null", report);
		String reportOid = report.get(FieldNames.FIELD_OBJECT_ID);

		/// --- Export to PDF ---
		PdfExporter exporter = new PdfExporter(isoReporter);
		BaseRecord pdfData = exporter.export(report);
		assertNotNull("PdfExporter.export returned null", pdfData);

		/// data.data contentType = application/pdf
		assertEquals("PDF data.data contentType", "application/pdf", pdfData.get(FieldNames.FIELD_CONTENT_TYPE));

		/// Bytes: %PDF magic + non-trivial size (sections + two embedded chart PNGs).
		String pdfOid = pdfData.get(FieldNames.FIELD_OBJECT_ID);
		byte[] bytes = readDataBytes(isoReporter, pdfOid);
		assertNotNull("PDF bytes not re-readable", bytes);
		assertTrue("PDF should be non-trivial in size, got " + bytes.length + " bytes", bytes.length > 1000);
		String header = new String(bytes, 0, Math.min(5, bytes.length), StandardCharsets.ISO_8859_1);
		assertTrue("PDF must start with %PDF magic, got '" + header + "'", header.startsWith("%PDF"));

		/// --- report.exportedPdf resolves on re-read (planMost) to the stored PDF ---
		BaseRecord reread = findByObjectId(isoReporter, ISO42001ModelNames.MODEL_REPORT, reportOid);
		assertNotNull("report not re-readable", reread);
		BaseRecord exportedPdf = reread.get("exportedPdf");
		assertNotNull("report.exportedPdf is null after export", exportedPdf);
		assertEquals("report.exportedPdf must reference the stored PDF data.data",
			pdfOid, exportedPdf.get(FieldNames.FIELD_OBJECT_ID));
	}
}
