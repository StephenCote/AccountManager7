package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.iso42001.certification.ISO42001CertificationRequestFactory;
import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Phase 6 (design §2.8, §3.1) — certification-request lifecycle on {@code iso42001.certificationRequest}
 * (inherits {@code access.accessRequest}), all non-admin.
 *
 * <p>A Reporter creates a request (seeding the {@code message.spool} thread with the justification); the
 * Certifier appends a review note and approves — which fires the signing and links the resulting
 * certification. A second request exercises the deny path. Asserts the spool thread grows and the
 * {@code approvalStatus} transitions REQUEST → APPROVE / DENY. Negative RBAC: a non-Certifier cannot
 * approve.</p>
 */
@Category(IntegrationTest.class)
public class TestISO42001CertificationRequest extends ISO42001BaseTest {

	@Test
	public void testRequestApproveDenyAndRbac() {
		ISO42001CertificationRequestFactory rf = new ISO42001CertificationRequestFactory();

		/// ---------- Approve path ----------
		BaseRecord report = fixtureReport("req-report-" + UUID.randomUUID());
		BaseRecord request = rf.createRequest(isoReporter, report, isoCertifier,
			"Please certify the Q2 bias compliance report.", sharedGroupId, orgId);
		assertNotNull("createRequest returned null (RBAC?)", request);

		String reqOid = request.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord rr = findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, reqOid);
		assertNotNull("request not re-readable", rr);
		assertEquals("initial approvalStatus must be REQUEST",
			ApprovalResponseEnumType.REQUEST, rr.getEnum(FieldNames.FIELD_APPROVAL_STATUS));
		assertEquals("thread should have the reporter's initial message", 1, msgCount(rr));

		/// Certifier appends a review message (requires model update — Certifiers).
		BaseRecord appended = rf.appendMessage(isoCertifier, request, "Reviewed methodology and statistics; proceeding.");
		assertNotNull("appendMessage by certifier returned null", appended);
		rr = findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, reqOid);
		assertEquals("thread should now have 2 messages", 2, msgCount(rr));

		/// Approve → signing fires.
		BaseRecord cert = rf.approveRequest(isoCertifier, request, "Approved for certification.");
		assertNotNull("approveRequest returned null (approval/signing failed)", cert);

		rr = findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, reqOid);
		assertEquals("approvalStatus must transition to APPROVE",
			ApprovalResponseEnumType.APPROVE, rr.getEnum(FieldNames.FIELD_APPROVAL_STATUS));
		assertTrue("thread should have grown past the approval note", msgCount(rr) >= 3);
		BaseRecord resulting = rr.get("resultingCertification");
		assertNotNull("resultingCertification must be linked after approval", resulting);
		assertEquals("resultingCertification must reference the created certification",
			(long) cert.get(FieldNames.FIELD_ID), (long) resulting.get(FieldNames.FIELD_ID));

		/// Approval stamped the report CERTIFIED.
		BaseRecord certifiedReport = findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_REPORT,
			report.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("report must be CERTIFIED after approval", "CERTIFIED", certifiedReport.get("status"));

		/// ---------- Deny path ----------
		BaseRecord report2 = fixtureReport("req-report-deny-" + UUID.randomUUID());
		BaseRecord request2 = rf.createRequest(isoReporter, report2, isoCertifier,
			"Please certify report 2.", sharedGroupId, orgId);
		assertNotNull("createRequest (deny path) null", request2);
		BaseRecord denied = rf.denyRequest(isoCertifier, request2, "Insufficient sample size for FIN module.");
		assertNotNull("denyRequest returned null", denied);
		BaseRecord rr2 = findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST,
			request2.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("approvalStatus must transition to DENY",
			ApprovalResponseEnumType.DENY, rr2.getEnum(FieldNames.FIELD_APPROVAL_STATUS));

		/// ---------- Negative RBAC: non-Certifier cannot approve ----------
		BaseRecord report3 = fixtureReport("req-report-rbac-" + UUID.randomUUID());
		BaseRecord request3 = rf.createRequest(isoReporter, report3, isoCertifier,
			"Please certify report 3.", sharedGroupId, orgId);
		assertNotNull("createRequest (rbac path) null", request3);
		/// isoReader can READ the request (Readers) but is NOT a Certifier, so approve (model update) is denied.
		BaseRecord deniedApprove = rf.approveRequest(isoReader, request3, "I should not be able to do this.");
		assertNull("approveRequest by non-Certifier (isoReader) MUST be denied", deniedApprove);
		BaseRecord rr3 = findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST,
			request3.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("denied approval must leave approvalStatus at REQUEST",
			ApprovalResponseEnumType.REQUEST, rr3.getEnum(FieldNames.FIELD_APPROVAL_STATUS));
	}

	// ------------------------------------------------------------------

	private int msgCount(BaseRecord request) {
		List<?> msgs = request.get(FieldNames.FIELD_MESSAGES);
		return msgs == null ? 0 : msgs.size();
	}

	protected BaseRecord fixtureReport(String name) {
		List<BaseRecord> results = new ArrayList<>(Arrays.asList(
			fixtureResult("BIAS-ATTR-002", "BIAS", "Race", "PASS", 0.08, "COHENS_D", 0.42),
			fixtureResult("BIAS-HIRE-001", "BIAS", "Gender", "FLAG", 0.31, "ODDS_RATIO", 0.02)));
		BaseRecord run = createFixtureTestRun(isoTester, "qwen3:8b", results);
		ReportGenerator gen = new ReportGenerator(isoReporter);
		return gen.generate(name, "COMPLIANCE", Arrays.asList(run),
			sharedGroupId, orgId, (long) isoReporter.get(FieldNames.FIELD_ID));
	}
}
