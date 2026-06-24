package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.certification.CertificationVerification;
import org.cote.accountmanager.iso42001.certification.ISO42001CertificationFactory;
import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Phase 6 (design §3.1 step 5) — certification verification failure modes, all non-admin.
 *
 * <p>Each test certifies a fresh fixture report, confirms it verifies, then induces one failure and
 * confirms {@link ISO42001CertificationFactory#verifyCertification} reports invalid:</p>
 * <ul>
 *   <li><b>tamper</b> — a Reporter mutates the report content (overallVerdict) so the recomputed content
 *       hash no longer matches the signed hash (and the signature no longer verifies);</li>
 *   <li><b>expired</b> — an Administrator back-dates the certification {@code expiryDate};</li>
 *   <li><b>revoked</b> — an Administrator revokes the certification ({@code status=REVOKED}).</li>
 * </ul>
 * Also asserts revoke is Administrators-only (a non-admin revoke is denied).
 */
@Category(IntegrationTest.class)
public class TestISO42001CertificationVerify extends ISO42001BaseTest {

	@Test
	public void testTamperFailsVerification() {
		ISO42001CertificationFactory cf = new ISO42001CertificationFactory();
		BaseRecord[] rc = freshCertified(cf, "verify-tamper-");
		BaseRecord report = rc[0];
		BaseRecord cert = rc[1];
		assertTrue("baseline must verify", cf.verifyCertification(isoCertifier, cert).isValid());

		/// Tamper: the Reporter flips the overall verdict (a hashed field) on the persisted report.
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_REPORT, FieldNames.FIELD_OBJECT_ID,
			report.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planCommon(true);
		BaseRecord toTamper = ap.find(isoReporter, q);
		assertNotNull("could not re-read report to tamper", toTamper);
		set(toTamper, "overallVerdict", "PASS");
		assertNotNull("tamper update failed", ap.update(isoReporter, toTamper));

		CertificationVerification v = cf.verifyCertification(isoCertifier, cert);
		assertFalse("verification MUST fail after report content was tampered: " + v, v.isValid());
		assertFalse("hash must no longer match the signed hash", v.isHashMatches());
	}

	@Test
	public void testExpiredFailsVerification() {
		ISO42001CertificationFactory cf = new ISO42001CertificationFactory();
		BaseRecord[] rc = freshCertified(cf, "verify-expired-");
		BaseRecord cert = rc[1];
		assertTrue("baseline must verify", cf.verifyCertification(isoCertifier, cert).isValid());

		/// Back-date the certification expiry (certification update is Administrators-only).
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_CERTIFICATION, FieldNames.FIELD_OBJECT_ID,
			cert.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planCommon(true);
		BaseRecord toExpire = ap.find(isoAdmin, q);
		assertNotNull("could not re-read certification to expire", toExpire);
		set(toExpire, FieldNames.FIELD_EXPIRY_DATE, new Date(System.currentTimeMillis() - 86400000L));
		assertNotNull("expiry update failed", ap.update(isoAdmin, toExpire));

		CertificationVerification v = cf.verifyCertification(isoCertifier, cert);
		assertFalse("verification MUST fail after expiry: " + v, v.isValid());
		assertFalse("notExpired must be false", v.isNotExpired());
	}

	@Test
	public void testRevokeFailsVerificationAndIsAdminOnly() {
		ISO42001CertificationFactory cf = new ISO42001CertificationFactory();
		BaseRecord[] rc = freshCertified(cf, "verify-revoke-");
		BaseRecord cert = rc[1];
		assertTrue("baseline must verify", cf.verifyCertification(isoCertifier, cert).isValid());

		/// Negative RBAC: a non-Administrator (isoReporter) cannot revoke.
		assertFalse("revoke by non-Administrator MUST be denied",
			cf.revokeCertification(isoReporter, cert, "should not work"));
		assertTrue("certification must still verify after a denied revoke",
			cf.verifyCertification(isoCertifier, cert).isValid());

		/// Administrator revokes → subsequent verify fails.
		assertTrue("revoke by Administrator must succeed",
			cf.revokeCertification(isoAdmin, cert, "Superseded by re-test."));
		CertificationVerification v = cf.verifyCertification(isoCertifier, cert);
		assertFalse("verification MUST fail after revocation: " + v, v.isValid());
		assertFalse("statusValid must be false (REVOKED)", v.isStatusValid());
	}

	// ------------------------------------------------------------------

	/** Build a fresh fixture report and certify it; returns [report, certification]. */
	private BaseRecord[] freshCertified(ISO42001CertificationFactory cf, String prefix) {
		List<BaseRecord> results = new ArrayList<>(Arrays.asList(
			fixtureResult("BIAS-ATTR-002", "BIAS", "Race", "PASS", 0.08, "COHENS_D", 0.42),
			fixtureResult("BIAS-REF-001", "BIAS", "Religion", "FAIL", 0.62, "COHENS_D", 0.001)));
		BaseRecord run = createFixtureTestRun(isoTester, "qwen3:8b", results);
		ReportGenerator gen = new ReportGenerator(isoReporter);
		BaseRecord report = gen.generate(prefix + UUID.randomUUID(), "COMPLIANCE", Arrays.asList(run),
			sharedGroupId, orgId, (long) isoReporter.get(FieldNames.FIELD_ID));
		assertNotNull("fixture report null", report);
		BaseRecord cert = cf.createCertification(isoCertifier, report, isoCertifier);
		assertNotNull("createCertification null", cert);
		return new BaseRecord[] { report, cert };
	}
}
