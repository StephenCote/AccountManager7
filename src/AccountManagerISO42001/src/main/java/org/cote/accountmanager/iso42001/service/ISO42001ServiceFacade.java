package org.cote.accountmanager.iso42001.service;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.certification.CertificationVerification;
import org.cote.accountmanager.iso42001.certification.ISO42001CertificationFactory;
import org.cote.accountmanager.iso42001.certification.ISO42001CertificationRequestFactory;
import org.cote.accountmanager.iso42001.engine.BiasModule;
import org.cote.accountmanager.iso42001.engine.BiasModuleRegistry;
import org.cote.accountmanager.iso42001.engine.TestRunner;
import org.cote.accountmanager.iso42001.reporting.PdfExporter;
import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.util.NameBank;
import org.cote.accountmanager.iso42001.util.NameBankLoader;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

/**
 * Shared marshaling facade for the Phase-7 transport shims. Both the REST {@code ISO42001Service} (Service7)
 * and the MCP {@code ISO42001ToolProvider} call these methods so the resolution glue (id → record, endpoint
 * name → chatConfig, moduleId → {@link BiasModule}) lives once, in the ISO module, and the Service7 service
 * class stays a pure transport layer.
 *
 * <p><b>No business logic.</b> Each method only resolves arguments through {@link AccessPoint} as the acting
 * user and delegates to the already-Track-A-tested engine/factory methods ({@link TestRunner},
 * {@link ReportGenerator}, {@link PdfExporter}, {@link ISO42001CertificationFactory},
 * {@link ISO42001CertificationRequestFactory}). RBAC is enforced by those methods + the model access roles;
 * a denied read/create simply returns {@code null} here.</p>
 */
public class ISO42001ServiceFacade {

	private static final Logger logger = LogManager.getLogger(ISO42001ServiceFacade.class);

	private ISO42001ServiceFacade() {}

	private static AccessPoint ap() {
		return IOSystem.getActiveContext().getAccessPoint();
	}

	private static long orgId(BaseRecord user) {
		return user.get(FieldNames.FIELD_ORGANIZATION_ID);
	}

	/** Find one ISO/system record by objectId in the acting user's org, fully planned, via AccessPoint (RBAC-gated). */
	public static BaseRecord findByObjectId(BaseRecord user, String model, String objectId) {
		if (objectId == null || objectId.isEmpty()) {
			return null;
		}
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId(user));
		q.planMost(true);
		return ap().find(user, q);
	}

	/**
	 * Run a bias test from a persisted {@code iso42001.testConfig}: resolve the config, resolve its LLM
	 * endpoint ({@code olio.llm.chatConfig} by {@code endpointName}), resolve the {@link BiasModule} from
	 * {@code moduleId}/{@code testIds}, then delegate to {@link TestRunner#run}.
	 *
	 * @return the persisted {@code iso42001.testRun}, or {@code null} if any resolution failed / access denied
	 */
	public static BaseRecord runFromConfig(BaseRecord user, String testConfigId) {
		BaseRecord testConfig = findByObjectId(user, ISO42001ModelNames.MODEL_TEST_CONFIG, testConfigId);
		if (testConfig == null) {
			logger.warn("runFromConfig: testConfig not found or access denied: " + testConfigId);
			return null;
		}
		String endpointName = testConfig.get("endpointName");
		BaseRecord chatConfig = resolveChatConfig(user, endpointName);
		if (chatConfig == null) {
			logger.warn("runFromConfig: chatConfig not found for endpointName=" + endpointName);
			return null;
		}
		List<String> testIds = testConfig.get("testIds");
		String moduleId = testConfig.get("moduleId");
		BiasModule module = BiasModuleRegistry.resolve(moduleId, testIds);
		if (module == null) {
			logger.warn("runFromConfig: no bias module resolves moduleId=" + moduleId + " testIds=" + testIds);
			return null;
		}
		NameBank bank = new NameBankLoader().loadDefault();
		return new TestRunner(user, chatConfig).run(testConfig, module, bank);
	}

	/** Resolve a configured LLM endpoint by chatConfig name in the acting user's org (RBAC-gated). */
	public static BaseRecord resolveChatConfig(BaseRecord user, String endpointName) {
		if (endpointName == null || endpointName.isEmpty()) {
			return null;
		}
		Query q = QueryUtil.createQuery(org.cote.accountmanager.olio.schema.OlioModelNames.MODEL_CHAT_CONFIG,
			FieldNames.FIELD_NAME, endpointName);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId(user));
		q.planMost(true);
		return ap().find(user, q);
	}

	/**
	 * Generate and persist a report from the given test-run objectIds (delegates to {@link ReportGenerator}).
	 * Group/org/owner placement mirrors the runs: the report lands in the first run's group, the acting org,
	 * owned by the acting user.
	 */
	public static BaseRecord generateReport(BaseRecord user, String name, String reportType, List<String> testRunIds) {
		List<BaseRecord> runs = new ArrayList<>();
		long groupId = 0L;
		if (testRunIds != null) {
			for (String id : testRunIds) {
				BaseRecord run = findByObjectId(user, ISO42001ModelNames.MODEL_TEST_RUN, id);
				if (run != null) {
					runs.add(run);
					if (groupId == 0L) {
						groupId = nz(run.get(FieldNames.FIELD_GROUP_ID));
					}
				}
			}
		}
		if (runs.isEmpty()) {
			logger.warn("generateReport: no resolvable/authorized test runs in " + testRunIds);
			return null;
		}
		long ownerId = user.get(FieldNames.FIELD_ID);
		return new ReportGenerator(user).generate(name, reportType, runs, groupId, orgId(user), ownerId);
	}

	/** Export a report to PDF (delegates to {@link PdfExporter#export}); returns the created {@code data.data}. */
	public static BaseRecord exportPdf(BaseRecord user, String reportId) {
		BaseRecord report = findByObjectId(user, ISO42001ModelNames.MODEL_REPORT, reportId);
		if (report == null) {
			return null;
		}
		return new PdfExporter(user).export(report);
	}

	/**
	 * Create a certification request for a report (delegates to {@link ISO42001CertificationRequestFactory}).
	 * The request lands in the report's group / acting org.
	 */
	public static BaseRecord requestCertification(BaseRecord user, String reportId, String certifierId,
			String justification) {
		BaseRecord report = findByObjectId(user, ISO42001ModelNames.MODEL_REPORT, reportId);
		if (report == null) {
			logger.warn("requestCertification: report not found or access denied: " + reportId);
			return null;
		}
		BaseRecord certifier = (certifierId != null)
			? findByObjectId(user, ModelNames.MODEL_USER, certifierId) : null;
		long groupId = nz(report.get(FieldNames.FIELD_GROUP_ID));
		return new ISO42001CertificationRequestFactory()
			.createRequest(user, report, certifier, justification, groupId, orgId(user));
	}

	/** Approve + sign a certification request (delegates to {@link ISO42001CertificationRequestFactory#approveRequest}). */
	public static BaseRecord approveRequest(BaseRecord user, String requestId, String note) {
		BaseRecord request = findByObjectId(user, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, requestId);
		if (request == null) {
			return null;
		}
		return new ISO42001CertificationRequestFactory().approveRequest(user, request, note);
	}

	/** Deny a certification request (delegates to {@link ISO42001CertificationRequestFactory#denyRequest}). */
	public static BaseRecord denyRequest(BaseRecord user, String requestId, String reason) {
		BaseRecord request = findByObjectId(user, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, requestId);
		if (request == null) {
			return null;
		}
		return new ISO42001CertificationRequestFactory().denyRequest(user, request, reason);
	}

	/**
	 * Append a message to a certification request's spool thread (delegates to
	 * {@link ISO42001CertificationRequestFactory#appendMessage}). Model RBAC gates the update to
	 * Certifiers/Administrators, so a non-certifier is denied. Returns the updated request, or {@code null}.
	 */
	public static BaseRecord appendRequestMessage(BaseRecord user, String requestId, String text) {
		BaseRecord request = findByObjectId(user, ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, requestId);
		if (request == null) {
			return null;
		}
		return new ISO42001CertificationRequestFactory().appendMessage(user, request, text);
	}

	/**
	 * Revoke a certification (delegates to {@link ISO42001CertificationFactory#revokeCertification}); model
	 * RBAC gates the update to Administrators. Returns the re-read certification on success, else {@code null}.
	 */
	public static BaseRecord revoke(BaseRecord user, String certificationId, String reason) {
		BaseRecord cert = findByObjectId(user, ISO42001ModelNames.MODEL_CERTIFICATION, certificationId);
		if (cert == null) {
			return null;
		}
		boolean ok = new ISO42001CertificationFactory().revokeCertification(user, cert, reason);
		if (!ok) {
			return null;
		}
		return findByObjectId(user, ISO42001ModelNames.MODEL_CERTIFICATION, certificationId);
	}

	/** Verify a certification (delegates to {@link ISO42001CertificationFactory#verifyCertification}). */
	public static CertificationVerification verify(BaseRecord user, String certificationId) {
		BaseRecord cert = findByObjectId(user, ISO42001ModelNames.MODEL_CERTIFICATION, certificationId);
		if (cert == null) {
			return null;
		}
		return new ISO42001CertificationFactory().verifyCertification(user, cert);
	}

	private static long nz(Object v) {
		return (v == null) ? 0L : ((Number) v).longValue();
	}
}
