package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.iso42001.certification.CertificationVerification;
import org.cote.accountmanager.iso42001.certification.ISO42001CertificationFactory;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.service.ISO42001ServiceFacade;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Phase 10 — the full ISO 42001 lifecycle in ONE test, driven entirely through
 * {@link ISO42001ServiceFacade} (the same resolution layer REST and MCP marshal onto), against the live
 * DB and the live LLM selected by the {@code [LLM-GATE]}:
 *
 * <ol>
 *   <li><b>Campaign</b> — isoTester creates an {@code iso42001.testConfig} whose {@code endpointName} is
 *       the name of a real {@code olio.llm.chatConfig} (Phase 7 contract: the facade resolves the endpoint
 *       by chatConfig name in the acting user's org).</li>
 *   <li><b>Run</b> — {@code runFromConfig} executes BIAS-HIRE-001 (tier 1, 2 per group) and persists the
 *       {@code iso42001.testRun} with an embedded result and a raw-log capture.</li>
 *   <li><b>Report</b> — isoReporter generates a COMPLIANCE report from that run.</li>
 *   <li><b>Certify</b> — isoReporter requests certification; isoCertifier appends a review note (which must
 *       NOT blank the request's other fields — the 2026-10-07 regression), then approves with explicit
 *       terms (title, 24-month validity, notes — an out-of-range period is rejected without touching the
 *       request), which signs the report hash and links the certification; {@code verify} is valid.</li>
 *   <li><b>Export</b> — isoReporter exports the certified report to PDF.</li>
 *   <li><b>Revoke</b> — isoAdmin revokes; {@code verify} is no longer valid; a non-admin cannot revoke.</li>
 * </ol>
 *
 * Every step runs as a non-admin role user; the only admin usage is the role/user provisioning in
 * {@link ISO42001BaseTest}. The statistical verdict is not asserted (LLM output is nondeterministic); the
 * run must have reached the endpoint (verdict != ERROR) for the lifecycle to count as exercised.
 */
@Category(LiveTest.class)
public class TestISO42001Lifecycle extends ISO42001BaseTest {

	private static final Logger log = LogManager.getLogger(TestISO42001Lifecycle.class);

	private static final int PER_GROUP = 2;
	private static final int TIER = 1;
	private static final long SEED = 20261007L;

	@Test
	public void testFullLifecycleThroughFacade() {
		/// ---------- 1. Campaign (isoTester) ----------
		/// A real chatConfig in isoTester's ~/Chat, pointed at whatever the LLM gate resolved.
		String endpointName = "ISO42001 Lifecycle Chat";
		BaseRecord chatConfig = OlioTestUtil.getOllamaOpenAIConfig(isoTester, endpointName, testProperties);
		assertNotNull("chat config is null", chatConfig);
		assertNotNull("facade must resolve the chatConfig by endpointName as isoTester",
			ISO42001ServiceFacade.resolveChatConfig(isoTester, endpointName));

		BaseRecord tc = newRec(ISO42001ModelNames.MODEL_TEST_CONFIG);
		set(tc, FieldNames.FIELD_NAME, "lifecycle-campaign-" + UUID.randomUUID());
		set(tc, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(tc, "moduleId", "BIAS");
		List<String> testIds = tc.get("testIds");
		testIds.add("BIAS-HIRE-001");
		set(tc, "testIds", testIds);
		set(tc, "endpointName", endpointName);
		set(tc, "endpointType", "ollama");
		set(tc, "samplesPerGroup", PER_GROUP);
		set(tc, "tier", TIER);
		set(tc, "randomSeed", SEED);
		BaseRecord campaign = ISO42001ServiceFacade.createConfig(isoTester, tc);
		assertNotNull("createConfig as isoTester returned null (RBAC?)", campaign);
		String campaignOid = campaign.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord campaignRead = ISO42001ServiceFacade.findByObjectId(isoTester,
			ISO42001ModelNames.MODEL_TEST_CONFIG, campaignOid);
		assertNotNull("campaign not re-readable as isoTester", campaignRead);
		assertEquals("bindOwnership must stamp ownerId from the acting user",
			(long) isoTester.get(FieldNames.FIELD_ID), (long) campaignRead.get(FieldNames.FIELD_OWNER_ID));

		/// ---------- 2. Run (isoTester, live LLM) ----------
		long t0 = System.currentTimeMillis();
		BaseRecord run = ISO42001ServiceFacade.runFromConfig(isoTester, campaignOid);
		assertNotNull("runFromConfig returned null", run);
		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord runRead = ISO42001ServiceFacade.findByObjectId(isoTester, ISO42001ModelNames.MODEL_TEST_RUN, runOid);
		assertNotNull("testRun not re-readable", runRead);
		assertEquals("run must land in the campaign's group", sharedGroupId, (long) runRead.get(FieldNames.FIELD_GROUP_ID));
		assertEquals("run status", "COMPLETED", runRead.get("status"));
		List<BaseRecord> results = runRead.get("results");
		assertNotNull("run.results null", results);
		assertEquals("one embedded result for the single module", 1, results.size());
		String verdict = results.get(0).get("verdict");
		log.info("[lifecycle] run {} verdict={} totalTrials={} in {} ms", runOid, verdict,
			runRead.get("totalTrials"), System.currentTimeMillis() - t0);
		assertTrue("verdict must be a known value, got " + verdict,
			Arrays.asList("PASS", "FLAG", "FAIL", "ERROR").contains(verdict));
		assertFalse("LLM endpoint must have been reached (verdict ERROR means it was not): "
			+ results.get(0).get("notes"), "ERROR".equals(verdict));
		assertTrue("run must record trials", ((Number) runRead.get("totalTrials")).intValue() > 0);
		assertNotNull("rawLogRef must be set", runRead.get("rawLogRef"));

		/// ---------- 3. Report (isoReporter) ----------
		String reportName = "lifecycle-report-" + UUID.randomUUID();
		BaseRecord report = ISO42001ServiceFacade.generateReport(isoReporter, reportName, "COMPLIANCE",
			Arrays.asList(runOid));
		assertNotNull("generateReport as isoReporter returned null", report);
		String reportOid = report.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord reportRead = ISO42001ServiceFacade.findByObjectId(isoReporter, ISO42001ModelNames.MODEL_REPORT, reportOid);
		assertNotNull("report not re-readable by its owner", reportRead);
		assertEquals("report status before certification", "DRAFT", reportRead.get("status"));
		assertEquals("report must land in the run's group", sharedGroupId, (long) reportRead.get(FieldNames.FIELD_GROUP_ID));
		List<BaseRecord> sections = reportRead.get("sections");
		assertTrue("report must have sections", sections != null && !sections.isEmpty());

		/// ---------- 4. Certify (isoReporter requests; isoCertifier reviews + approves) ----------
		BaseRecord request = ISO42001ServiceFacade.requestCertification(isoReporter, reportOid,
			isoCertifier.get(FieldNames.FIELD_OBJECT_ID), "Lifecycle: please certify.");
		assertNotNull("requestCertification as isoReporter returned null", request);
		String reqOid = request.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord reqRead = ISO42001ServiceFacade.findByObjectId(isoCertifier,
			ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, reqOid);
		assertNotNull("request not readable by certifier", reqRead);
		assertEquals(ApprovalResponseEnumType.REQUEST, reqRead.getEnum(FieldNames.FIELD_APPROVAL_STATUS));
		assertNotNull("request.report must be set", reqRead.get("report"));
		assertEquals("Lifecycle: please certify.", reqRead.get("justification"));
		long reqOwner = reqRead.get(FieldNames.FIELD_OWNER_ID);
		boolean hadRequestedCertifier = reqRead.get("requestedCertifier") != null;
		int msgsBefore = msgCount(reqRead);

		/// Append a review note — regression guard: the update must touch ONLY the thread.
		BaseRecord appended = ISO42001ServiceFacade.appendRequestMessage(isoCertifier, reqOid, "Reviewed; proceeding.");
		assertNotNull("appendRequestMessage as isoCertifier returned null", appended);
		reqRead = ISO42001ServiceFacade.findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, reqOid);
		assertEquals("thread must grow by one", msgsBefore + 1, msgCount(reqRead));
		assertNotNull("appendMessage must NOT blank request.report", reqRead.get("report"));
		assertEquals("appendMessage must NOT blank justification", "Lifecycle: please certify.", reqRead.get("justification"));
		assertEquals("appendMessage must NOT reset approvalStatus",
			ApprovalResponseEnumType.REQUEST, reqRead.getEnum(FieldNames.FIELD_APPROVAL_STATUS));
		assertEquals("appendMessage must NOT change ownerId", reqOwner, (long) reqRead.get(FieldNames.FIELD_OWNER_ID));
		if (hadRequestedCertifier) {
			assertNotNull("appendMessage must NOT blank requestedCertifier", reqRead.get("requestedCertifier"));
		}

		/// Non-certifier cannot approve.
		assertNull("approve by isoReader MUST be denied",
			ISO42001ServiceFacade.approveRequest(isoReader, reqOid, "nope"));

		/// An out-of-range validity period is rejected BEFORE the request is transitioned (no half-approved
		/// request without a certification).
		assertNull("approve with validityMonths > MAX must be rejected",
			ISO42001ServiceFacade.approveRequest(isoCertifier, reqOid, "bad period", "Compliance Officer",
				ISO42001CertificationFactory.MAX_VALIDITY_MONTHS + 1, null));
		reqRead = ISO42001ServiceFacade.findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, reqOid);
		assertEquals("rejected validity must leave the request at REQUEST",
			ApprovalResponseEnumType.REQUEST, reqRead.getEnum(FieldNames.FIELD_APPROVAL_STATUS));

		/// Approve & Sign with explicit terms (design §9A.8 dialog: title, validity period, notes).
		Date approveStart = new Date();
		BaseRecord cert = ISO42001ServiceFacade.approveRequest(isoCertifier, reqOid, "Approved.",
			"Compliance Officer", 24, "Scope: hiring-decision prompts only.");
		assertNotNull("approveRequest as isoCertifier returned null", cert);
		String certOid = cert.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord certRead = ISO42001ServiceFacade.findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_CERTIFICATION, certOid);
		assertNotNull("certification not readable by certifier", certRead);
		assertEquals("certifierTitle must be the supplied title", "Compliance Officer", certRead.get("certifierTitle"));
		assertEquals("notes must be the supplied notes", "Scope: hiring-decision prompts only.", certRead.get("notes"));
		Date expiry = certRead.get(FieldNames.FIELD_EXPIRY_DATE);
		assertNotNull("expiryDate must be set", expiry);
		GregorianCalendar lo = new GregorianCalendar();
		lo.setTime(approveStart);
		lo.add(GregorianCalendar.MONTH, 24);
		lo.add(GregorianCalendar.MINUTE, -5);
		GregorianCalendar hi = new GregorianCalendar();
		hi.setTime(new Date());
		hi.add(GregorianCalendar.MONTH, 24);
		hi.add(GregorianCalendar.MINUTE, 5);
		assertTrue("expiryDate " + expiry + " must be ~24 months out (between " + lo.getTime() + " and " + hi.getTime() + ")",
			expiry.after(lo.getTime()) && expiry.before(hi.getTime()));

		reqRead = ISO42001ServiceFacade.findByObjectId(isoCertifier, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, reqOid);
		assertEquals(ApprovalResponseEnumType.APPROVE, reqRead.getEnum(FieldNames.FIELD_APPROVAL_STATUS));
		BaseRecord resulting = reqRead.get("resultingCertification");
		assertNotNull("resultingCertification must be linked", resulting);
		assertEquals((long) cert.get(FieldNames.FIELD_ID), (long) resulting.get(FieldNames.FIELD_ID));

		reportRead = ISO42001ServiceFacade.findByObjectId(isoReporter, ISO42001ModelNames.MODEL_REPORT, reportOid);
		assertEquals("report must be CERTIFIED", "CERTIFIED", reportRead.get("status"));
		assertNotNull("report.certification must be linked", reportRead.get("certification"));

		CertificationVerification ver = ISO42001ServiceFacade.verify(isoCertifier, certOid);
		assertNotNull("verify returned null", ver);
		assertTrue("certification must verify VALID: " + ver, ver.isValid());

		/// ---------- 5. Export (isoReporter) ----------
		BaseRecord pdf = ISO42001ServiceFacade.exportPdf(isoReporter, reportOid);
		assertNotNull("exportPdf as isoReporter returned null", pdf);
		byte[] pdfBytes = readDataBytes(isoReporter, pdf.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("exported PDF bytes not readable", pdfBytes);
		assertTrue("exported PDF must start with %PDF, got "
			+ new String(pdfBytes, 0, Math.min(8, pdfBytes.length), StandardCharsets.ISO_8859_1),
			pdfBytes.length > 4 && new String(pdfBytes, 0, 4, StandardCharsets.ISO_8859_1).equals("%PDF"));
		reportRead = ISO42001ServiceFacade.findByObjectId(isoReporter, ISO42001ModelNames.MODEL_REPORT, reportOid);
		assertNotNull("report.exportedPdf must be linked", reportRead.get("exportedPdf"));
		log.info("[lifecycle] exported PDF {} bytes", pdfBytes.length);

		/// ---------- 6. Revoke (isoAdmin); non-admin denied ----------
		assertNull("revoke by isoCertifier (not an Administrator) MUST be denied",
			ISO42001ServiceFacade.revoke(isoCertifier, certOid, "should not work"));
		CertificationVerification stillValid = ISO42001ServiceFacade.verify(isoCertifier, certOid);
		assertTrue("denied revoke must leave the certification valid", stillValid.isValid());

		BaseRecord revoked = ISO42001ServiceFacade.revoke(isoAdmin, certOid, "Lifecycle test revocation.");
		assertNotNull("revoke as isoAdmin returned null", revoked);
		assertEquals("REVOKED", revoked.get("status"));
		CertificationVerification afterRevoke = ISO42001ServiceFacade.verify(isoAdmin, certOid);
		assertNotNull(afterRevoke);
		assertFalse("revoked certification must not verify", afterRevoke.isValid());
		assertFalse("status check must be the failing component", afterRevoke.isStatusValid());
		log.info("[lifecycle] campaign={} run={} report={} request={} cert={} -> REVOKED",
			campaignOid, runOid, reportOid, reqOid, certOid);
	}

	private int msgCount(BaseRecord request) {
		List<?> msgs = request.get(FieldNames.FIELD_MESSAGES);
		return msgs == null ? 0 : msgs.size();
	}
}
