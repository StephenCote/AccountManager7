package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.iso42001.certification.CertificationVerification;
import org.cote.accountmanager.iso42001.certification.ISO42001CertificationFactory;
import org.cote.accountmanager.iso42001.reporting.PdfExporter;
import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Phase 6 (design §3, §2.7) — certification create + verify + PDF block, all non-admin.
 *
 * <p>A Reporter generates a deterministic fixture report (Phase-5 style — no live LLM); the
 * {@code isoCertifier} (ISO42001Certifiers) signs it via {@link ISO42001CertificationFactory}. Asserts the
 * certification carries a populated signature/certificate/hash + the spec-default algorithms/dates/status,
 * that the report is stamped {@code CERTIFIED} with the certification linked, that
 * {@link ISO42001CertificationFactory#verifyCertification} returns valid, and that the §4.2 PDF
 * certification block renders the signer detail. Negative RBAC: a non-Certifier cannot create a
 * certification.</p>
 */
@Category(IntegrationTest.class)
public class TestISO42001Certification extends ISO42001BaseTest {

	@Test
	public void testCreateVerifyAndPdfBlock() {
		/// --- Fixture report (isoTester builds the run, isoReporter the report) ---
		BaseRecord report = fixtureReport("cert-report-" + UUID.randomUUID());
		assertNotNull("fixture report null", report);

		/// --- Certify as the Certifiers-role user ---
		ISO42001CertificationFactory cf = new ISO42001CertificationFactory();
		BaseRecord cert = cf.createCertification(isoCertifier, report, isoCertifier);
		assertNotNull("createCertification returned null (RBAC?/keystore?)", cert);

		/// --- Signature material populated ---
		Object sig = cert.get(FieldNames.FIELD_SIGNATURE);
		assertTrue("signature must be a non-empty byte[]", sig instanceof byte[] && ((byte[]) sig).length > 0);
		Object certDer = cert.get("signerCertificate");
		assertTrue("signerCertificate must be a non-empty byte[]", certDer instanceof byte[] && ((byte[]) certDer).length > 0);
		Object rHash = cert.get("reportHash");
		assertTrue("reportHash must be 32 bytes (SHA-256)", rHash instanceof byte[] && ((byte[]) rHash).length == 32);
		assertEquals("reportHashAlgorithm", "SHA-256", cert.get("reportHashAlgorithm"));
		assertEquals("signatureAlgorithm", "SHA256WithRSA", cert.get("signatureAlgorithm"));
		assertEquals("status", "VALID", cert.get("status"));
		assertNotNull("certificationDate set", cert.get("certificationDate"));
		Date expiry = cert.get(FieldNames.FIELD_EXPIRY_DATE);
		assertNotNull("expiryDate set", expiry);
		assertTrue("expiryDate must be in the future", expiry.after(new Date()));

		/// --- Report stamped CERTIFIED + certification linked ---
		String reportOid = report.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord reread = findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_REPORT, reportOid);
		assertNotNull("report not re-readable by certifier", reread);
		assertEquals("report.status must be CERTIFIED", "CERTIFIED", reread.get("status"));
		BaseRecord linked = reread.get("certification");
		assertNotNull("report.certification not linked", linked);
		long linkedId = linked.get(FieldNames.FIELD_ID);
		long certId = cert.get(FieldNames.FIELD_ID);
		assertEquals("report.certification must reference the created certification", certId, linkedId);

		/// --- Verification passes ---
		CertificationVerification v = cf.verifyCertification(isoCertifier, cert);
		assertTrue("freshly created certification must verify: " + v, v.isValid());

		/// --- §4.2 PDF certification block renders the signer detail (assert content directly) ---
		List<String> lines = PdfExporter.certificationBlockLines(report, cert);
		String block = String.join("\n", lines);
		assertTrue("block must show certifier name, got:\n" + block, block.contains("Certified by: isoCertifier"));
		assertTrue("block must show Status: VALID", block.contains("Status: VALID"));
		assertTrue("block must show the SHA-256 report hash hex", block.contains("Report Hash (SHA-256): "));
		assertTrue("block must show a base64 Signature", block.contains("Signature: ") && !block.contains("Signature: (not set)"));
		assertTrue("block must show a base64 Certificate", block.contains("Certificate: ") && !block.contains("Certificate: (not set)"));
		/// And the rendered PDF still produces valid %PDF bytes with the certified block.
		byte[] pdf = new PdfExporter(isoCertifier).renderPdfBytes(reread);
		assertNotNull("certified PDF render null", pdf);
		assertTrue("certified PDF must start with %PDF", pdf.length > 4
			&& pdf[0] == '%' && pdf[1] == 'P' && pdf[2] == 'D' && pdf[3] == 'F');

		/// --- Negative RBAC: a non-Certifier (isoReporter) cannot create a certification ---
		BaseRecord denied = cf.createCertification(isoReporter, report, isoReporter);
		assertNull("certification CREATE by non-Certifier (isoReporter) MUST be denied", denied);
	}

	// ------------------------------------------------------------------

	/** Build a persisted, deterministic fixture report (a Tester run + a Reporter report). */
	protected BaseRecord fixtureReport(String name) {
		List<BaseRecord> results = new ArrayList<>(Arrays.asList(
			fixtureResult("BIAS-ATTR-002", "BIAS", "Race", "PASS", 0.08, "COHENS_D", 0.42),
			fixtureResult("BIAS-HIRE-001", "BIAS", "Gender", "FLAG", 0.31, "ODDS_RATIO", 0.02),
			fixtureResult("BIAS-REF-001", "BIAS", "Religion", "FAIL", 0.62, "COHENS_D", 0.001)));
		BaseRecord run = createFixtureTestRun(isoTester, "qwen3:8b", results);
		ReportGenerator gen = new ReportGenerator(isoReporter);
		return gen.generate(name, "COMPLIANCE", Arrays.asList(run),
			sharedGroupId, orgId, (long) isoReporter.get(FieldNames.FIELD_ID));
	}
}
