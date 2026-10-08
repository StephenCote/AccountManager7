package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.certification.ISO42001CertificationFactory;
import org.cote.accountmanager.iso42001.certification.ISO42001CertificationRequestFactory;
import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Regression for the APPROVE-without-certification strand (found live 2026-10-07 on the Docker stack: a
 * BouncyCastle {@code bcprov 1.76 / bcpkix 1.80} skew made the certifier's first keystore generation throw
 * {@code NoSuchFieldError} inside {@code createCertification}; the request had already been transitioned
 * to APPROVE and stayed there with nothing signed).
 *
 * <p>{@link ISO42001CertificationRequestFactory#approveRequest} must put the request back to REQUEST —
 * with an audit message in the thread — whether signing returns {@code null} or throws, and the request
 * must then be re-approvable through the real signing path. The failing signer is injected through the
 * factory's protected {@code certificationFactory()} seam (a subclass, no mocking framework), so the
 * revert is exercised against the live DB and the real PBAC path for the status transitions.</p>
 */
@Category(IntegrationTest.class)
public class TestISO42001ApproveRevert extends ISO42001BaseTest {

	@Test
	public void testSigningNullRevertsToRequestAndStaysReapprovable() {
		BaseRecord report = fixtureReport("revert-null-report-" + UUID.randomUUID());
		ISO42001CertificationRequestFactory real = new ISO42001CertificationRequestFactory();
		BaseRecord request = real.createRequest(isoReporter, report, isoCertifier,
			"Please certify (signing will fail once).", sharedGroupId, orgId);
		assertNotNull("createRequest returned null (RBAC?)", request);
		String reqOid = request.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals(1, msgCount(freshRequest(reqOid)));

		/// Signer that fails the way RBAC / missing keystore / empty hash failures do: returns null.
		ISO42001CertificationRequestFactory nullSigner = new ISO42001CertificationRequestFactory() {
			@Override
			protected ISO42001CertificationFactory certificationFactory() {
				return new ISO42001CertificationFactory() {
					@Override
					public BaseRecord createCertification(BaseRecord user, BaseRecord rep, BaseRecord certifier,
							String certifierTitle, int validityMonths, String notes) {
						return null;
					}
				};
			}
		};
		assertNull("approve with a failing signer must return null",
			nullSigner.approveRequest(isoCertifier, request, "Approving; signer will return null."));

		BaseRecord rr = freshRequest(reqOid);
		assertEquals("request must be reverted to REQUEST after a null signing result",
			ApprovalResponseEnumType.REQUEST, rr.getEnum(FieldNames.FIELD_APPROVAL_STATUS));
		assertNull("no certification may be linked after a failed signing", rr.get("resultingCertification"));
		assertEquals("thread must carry the approval note AND the revert note", 3, msgCount(rr));
		BaseRecord rep = findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_REPORT, report.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull(rep);
		/// `status` is a plain string on iso42001.report; bind the generic get() to String explicitly
		/// (String.valueOf(rep.get(..)) resolves to the char[] overload and throws ClassCastException).
		String reportStatus = rep.get("status");
		assertEquals("report must not be CERTIFIED after a failed signing", "DRAFT", reportStatus);

		/// The same request is re-approvable through the real signing path.
		BaseRecord cert = real.approveRequest(isoCertifier, request, "Approved after the signer was repaired.");
		assertNotNull("re-approval through the real signer must succeed", cert);
		rr = freshRequest(reqOid);
		assertEquals(ApprovalResponseEnumType.APPROVE, rr.getEnum(FieldNames.FIELD_APPROVAL_STATUS));
		BaseRecord linked = rr.get("resultingCertification");
		assertNotNull("resultingCertification must be linked after the successful re-approval", linked);
		assertEquals((long) cert.get(FieldNames.FIELD_ID), (long) linked.get(FieldNames.FIELD_ID));
	}

	@Test
	public void testSigningErrorRevertsToRequestAndRethrows() {
		BaseRecord report = fixtureReport("revert-throw-report-" + UUID.randomUUID());
		ISO42001CertificationRequestFactory real = new ISO42001CertificationRequestFactory();
		BaseRecord request = real.createRequest(isoReporter, report, isoCertifier,
			"Please certify (signer will throw).", sharedGroupId, orgId);
		assertNotNull("createRequest returned null (RBAC?)", request);
		String reqOid = request.get(FieldNames.FIELD_OBJECT_ID);

		/// Signer that dies the way the live Docker stack did: a LinkageError out of the crypto provider.
		ISO42001CertificationRequestFactory throwingSigner = new ISO42001CertificationRequestFactory() {
			@Override
			protected ISO42001CertificationFactory certificationFactory() {
				return new ISO42001CertificationFactory() {
					@Override
					public BaseRecord createCertification(BaseRecord user, BaseRecord rep, BaseRecord certifier,
							String certifierTitle, int validityMonths, String notes) {
						throw new NoSuchFieldError("simulated bcprov/bcpkix version skew");
					}
				};
			}
		};
		try {
			throwingSigner.approveRequest(isoCertifier, request, "Approving; signer will throw.");
			fail("a LinkageError from the signer must propagate (the caller must see a hard failure, not a quiet null)");
		} catch (NoSuchFieldError expected) {
			assertEquals("simulated bcprov/bcpkix version skew", expected.getMessage());
		}

		BaseRecord rr = freshRequest(reqOid);
		assertEquals("request must be reverted to REQUEST after the signer threw",
			ApprovalResponseEnumType.REQUEST, rr.getEnum(FieldNames.FIELD_APPROVAL_STATUS));
		assertNull(rr.get("resultingCertification"));
		assertEquals("thread must carry the approval note AND the revert note", 3, msgCount(rr));
	}

	// ------------------------------------------------------------------

	/** Fresh (uncached, fully planned) read of a certification request as the certifier. */
	private BaseRecord freshRequest(String oid) {
		Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		q.setCache(false);
		BaseRecord rr = ioContext.getAccessPoint().find(isoCertifier, q);
		assertNotNull("request " + oid + " not re-readable", rr);
		return rr;
	}

	private int msgCount(BaseRecord request) {
		List<?> msgs = request.get(FieldNames.FIELD_MESSAGES);
		return msgs == null ? 0 : msgs.size();
	}

	private BaseRecord fixtureReport(String name) {
		List<BaseRecord> results = new ArrayList<>(Arrays.asList(
			fixtureResult("BIAS-ATTR-002", "BIAS", "Race", "PASS", 0.08, "COHENS_D", 0.42),
			fixtureResult("BIAS-HIRE-001", "BIAS", "Gender", "FLAG", 0.31, "ODDS_RATIO", 0.02)));
		BaseRecord run = createFixtureTestRun(isoTester, "qwen3:8b", results);
		ReportGenerator gen = new ReportGenerator(isoReporter);
		return gen.generate(name, "COMPLIANCE", Arrays.asList(run),
			sharedGroupId, orgId, (long) isoReporter.get(FieldNames.FIELD_ID));
	}
}
