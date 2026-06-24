package org.cote.accountmanager.iso42001.certification;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.reporting.ReportGenerator;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.model.field.KeyStoreBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.CertificateUtil;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.KeyStoreUtil;

/**
 * Engine/service-callable certification operations for the ISO 42001 subsystem (design §3, §2.7).
 * Pure backend (no REST/MCP/Ux); Phase 7 marshals HTTP/JSON-RPC onto these methods without adding logic.
 *
 * <p>Reuses the existing AM7 crypto infrastructure end-to-end — no new primitives or dependencies:</p>
 * <ul>
 *   <li>{@link KeyStoreUtil#getCreateStore} provisions the certifier's RSA keypair + X.509 cert, chained
 *       (signed) by the self-signed org-root keystore ({@link OrganizationContext#getKeyStoreBean}). [B5:
 *       importing an external signer/root at org setup is backlogged; chaining to the self-signed org root
 *       is the in-scope behavior.]</li>
 *   <li>{@link CryptoUtil#sign}/{@link CryptoUtil#verify} (SHA256withRSA) sign/verify the report content
 *       hash — mirroring {@code AuditUtil}, which signs an audit hash with the org keystore's private key.</li>
 *   <li>{@link CertificateUtil#decodeCertificate} reconstructs the embedded DER cert for verification.</li>
 * </ul>
 *
 * <p>The value signed is the report's canonical content hash ({@link ReportGenerator#computeReportHash},
 * the same SHA-256 stored on {@code report.hash} in Phase 5). Revocation is record-level
 * ({@code status=REVOKED}) honored by {@link #verifyCertification}; X.509 CRLs are backlogged (B6).</p>
 */
public class ISO42001CertificationFactory {

	private static final Logger logger = LogManager.getLogger(ISO42001CertificationFactory.class);

	public static final String HASH_ALGORITHM = "SHA-256";
	public static final String SIGNATURE_ALGORITHM = "SHA256WithRSA";

	public static final String STATUS_VALID = "VALID";
	public static final String STATUS_EXPIRED = "EXPIRED";
	public static final String STATUS_REVOKED = "REVOKED";

	/** Default application-level validity for a new certification, in years. */
	private static final int DEFAULT_VALIDITY_YEARS = 1;

	/**
	 * Create a signed certification for {@code report} (design §3.1 step 4). The certifier's keystore is
	 * obtained/created (chained to the org root), the report's canonical content hash is signed, and the
	 * certification is persisted as {@code certifier}. On success the report is stamped
	 * {@code status=CERTIFIED} with {@code report.certification} linked.
	 *
	 * @param user      the acting (context) user — normally the same as {@code certifier}
	 * @param report    the report to certify (must be persisted; re-read here for its sections)
	 * @param certifier the ISO42001Certifiers-role user whose key signs the report hash
	 * @return the persisted certification record (fully populated in memory), or {@code null} on failure
	 *         (e.g. RBAC denial, missing keystore)
	 */
	public BaseRecord createCertification(BaseRecord user, BaseRecord report, BaseRecord certifier) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		if (report == null || certifier == null) {
			logger.error("createCertification requires a report and a certifier");
			return null;
		}

		String reportOid = report.get(FieldNames.FIELD_OBJECT_ID);
		long orgId = lng(report, FieldNames.FIELD_ORGANIZATION_ID);
		long groupId = lng(report, FieldNames.FIELD_GROUP_ID);

		/// Re-read the report fully so its sections are present for a deterministic content hash. Read as
		/// the acting user (ISO42001Certifiers were granted report read for exactly this).
		BaseRecord fullReport = readReport(user, reportOid, orgId);
		if (fullReport == null) {
			logger.error("createCertification could not read report " + reportOid + " (RBAC?)");
			return null;
		}

		byte[] reportHash = ReportGenerator.computeReportHash(fullReport);
		if (reportHash == null || reportHash.length == 0) {
			logger.error("createCertification computed an empty report hash; aborting");
			return null;
		}

		/// Provision (idempotently) the certifier's keystore, chained to the self-signed org root.
		KeyStoreBean kb = resolveCertifierKeyStore(certifier);
		if (kb == null) {
			logger.error("createCertification could not obtain certifier keystore");
			return null;
		}
		PrivateKey privateKey = kb.getCryptoBean() != null ? kb.getCryptoBean().getPrivateKey() : null;
		byte[] signerCertDer = encodedCert(kb);
		if (privateKey == null || signerCertDer.length == 0) {
			logger.error("createCertification: certifier private key or certificate unavailable");
			return null;
		}

		byte[] signature = CryptoUtil.sign(privateKey, reportHash);
		if (signature == null || signature.length == 0) {
			logger.error("createCertification produced an empty signature");
			return null;
		}

		Date now = new Date();
		GregorianCalendar cal = new GregorianCalendar();
		cal.setTime(now);
		cal.add(GregorianCalendar.YEAR, DEFAULT_VALIDITY_YEARS);

		BaseRecord certification;
		try {
			certification = RecordFactory.model(ISO42001ModelNames.MODEL_CERTIFICATION).newInstance();
			certification.set(FieldNames.FIELD_NAME, "certification-" + UUID.randomUUID());
			certification.set(FieldNames.FIELD_GROUP_ID, groupId);
			certification.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			certification.set(FieldNames.FIELD_OWNER_ID, (long) certifier.get(FieldNames.FIELD_ID));
			certification.set("report", fullReport);
			certification.set("certifier", certifier);
			certification.set("certifierTitle", "ISO 42001 Certifier");
			certification.set("certificationDate", now);
			certification.set(FieldNames.FIELD_EXPIRY_DATE, cal.getTime());
			certification.set("reportHash", reportHash);
			certification.set("reportHashAlgorithm", HASH_ALGORITHM);
			certification.set("signatureAlgorithm", SIGNATURE_ALGORITHM);
			certification.set("signerCertificate", signerCertDer);
			certification.set(FieldNames.FIELD_SIGNATURE, signature);
			certification.set("status", STATUS_VALID);
		} catch (Exception e) {
			logger.error("Failed to build certification record", e);
			return null;
		}

		BaseRecord created = ap.create(certifier, certification);
		if (created == null) {
			logger.error("certification CREATE returned null (RBAC?) for user " + certifier.get(FieldNames.FIELD_NAME));
			return null;
		}
		try {
			certification.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
			certification.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		} catch (Exception e) {
			logger.warn("Could not stamp identity onto certification", e);
		}

		/// Stamp the report: status=CERTIFIED + link the certification. Re-read with a common plan first so
		/// the update carries the group/owner context the update policy needs (see PdfExporter note).
		if (!linkCertificationToReport(user, reportOid, orgId, created)) {
			logger.warn("Certification created but report could not be stamped CERTIFIED for " + reportOid);
		}

		return certification;
	}

	/**
	 * Verify a certification (design §3.1 step 5): recompute the report's content hash, confirm it still
	 * matches the signed hash (tamper check), verify the signature against the embedded certificate's
	 * public key, and confirm the X.509 cert validity, the application-level expiry, and the
	 * {@code status==VALID}. Returns a structured per-check result; {@link CertificationVerification#isValid()}
	 * is the overall verdict.
	 *
	 * <p>Takes the acting {@code user} (the AM7 idiom — every {@code AccessPoint} read needs a context
	 * user; the spec's {@code verifyCertification(certification)} is realized as this two-arg form). The
	 * certification and its report are re-read fresh from the DB as {@code user}, so a report tampered with
	 * after signing is detected.</p>
	 */
	public CertificationVerification verifyCertification(BaseRecord user, BaseRecord certification) {
		CertificationVerification v = new CertificationVerification();
		if (certification == null) {
			v.setReason("certification is null");
			return v;
		}

		/// Re-read + populate the restricted/blob fields (signature, reportHash, signerCertificate) and the
		/// report reference, so verification does not depend on how the caller fetched the record.
		BaseRecord cert = populateForVerify(user, certification);
		if (cert == null) {
			v.setReason("certification not readable");
			return v;
		}

		String status = cert.get("status");
		v.setStatus(status);
		v.setStatusValid(STATUS_VALID.equals(status));
		if (!v.isStatusValid()) {
			v.setReason("certification status is " + status);
		}

		/// X.509 certificate validity.
		byte[] certDer = cert.get("signerCertificate");
		X509Certificate x509 = null;
		if (certDer != null && certDer.length > 0) {
			x509 = CertificateUtil.decodeCertificate(certDer);
		}
		if (x509 == null) {
			v.setReason("signer certificate missing/undecodable");
			return v;
		}
		try {
			x509.checkValidity(new Date());
			v.setCertificateValid(true);
		} catch (Exception e) {
			v.setCertificateValid(false);
			v.setReason("signer certificate not within validity period: " + e.getMessage());
		}

		/// Application-level certification expiry (record expiryDate).
		Date expiry = cert.get(FieldNames.FIELD_EXPIRY_DATE);
		v.setNotExpired(expiry == null || expiry.after(new Date()));
		if (!v.isNotExpired()) {
			v.setReason("certification expired on " + expiry);
		}

		/// Recompute the report content hash and compare to the signed hash (tamper detection).
		byte[] signedHash = cert.get("reportHash");
		BaseRecord report = cert.get("report");
		byte[] recomputed = null;
		if (report != null) {
			BaseRecord fullReport = readReport(user, report.get(FieldNames.FIELD_OBJECT_ID), lng(report, FieldNames.FIELD_ORGANIZATION_ID));
			if (fullReport != null) {
				recomputed = ReportGenerator.computeReportHash(fullReport);
			}
		}
		v.setHashMatches(recomputed != null && signedHash != null && Arrays.equals(recomputed, signedHash));
		if (!v.isHashMatches()) {
			v.setReason("report content hash does not match the signed hash (tampered or unreadable report)");
		}

		/// Verify the signature over the RECOMPUTED hash, so a tampered report fails here too.
		byte[] signature = cert.get(FieldNames.FIELD_SIGNATURE);
		byte[] verifyAgainst = recomputed != null ? recomputed : signedHash;
		v.setSignatureValid(signature != null && verifyAgainst != null
			&& CryptoUtil.verify(x509.getPublicKey(), verifyAgainst, signature));
		if (!v.isSignatureValid() && v.getReason() == null) {
			v.setReason("signature did not verify against the signer certificate");
		}

		if (v.isValid()) {
			v.setReason("valid");
		}
		return v;
	}

	/**
	 * Revoke a certification (application-level; design §3.1 / B6). Sets {@code status=REVOKED} so a
	 * subsequent {@link #verifyCertification} fails. Update of the certification is gated to
	 * {@code ISO42001Administrators} by the model, so {@code user} must be an administrator.
	 *
	 * @return {@code true} if the revocation persisted
	 */
	public boolean revokeCertification(BaseRecord user, BaseRecord certification, String reason) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		if (certification == null) {
			return false;
		}
		String oid = certification.get(FieldNames.FIELD_OBJECT_ID);
		long orgId = lng(certification, FieldNames.FIELD_ORGANIZATION_ID);
		try {
			Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_CERTIFICATION, FieldNames.FIELD_OBJECT_ID, oid);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.planCommon(true);
			BaseRecord toUpdate = ap.find(user, q);
			if (toUpdate == null) {
				logger.error("revokeCertification could not read certification " + oid + " (RBAC?)");
				return false;
			}
			toUpdate.set("status", STATUS_REVOKED);
			if (reason != null) {
				toUpdate.set("notes", reason);
			}
			BaseRecord updated = ap.update(user, toUpdate);
			return updated != null;
		} catch (Exception e) {
			logger.error("revokeCertification failed for " + oid, e);
			return false;
		}
	}

	// ------------------------------------------------------------------

	/** Obtain (idempotently) the certifier's keystore, with its cert signed by the self-signed org root. */
	private KeyStoreBean resolveCertifierKeyStore(BaseRecord certifier) {
		OrganizationContext octx = IOSystem.getActiveContext().findOrganizationContext(certifier);
		KeyStoreBean signer = octx != null ? octx.getKeyStoreBean() : null;
		if (signer == null) {
			logger.warn("Org root keystore unavailable; certifier cert will be self-signed");
		}
		String name = "ISO42001 Certifier " + certifier.get(FieldNames.FIELD_NAME);
		return KeyStoreUtil.getCreateStore(certifier, name, signer);
	}

	private static byte[] encodedCert(KeyStoreBean kb) {
		try {
			if (kb.getCertificate() != null) {
				return kb.getCertificate().getEncoded();
			}
		} catch (Exception e) {
			logger.error("Failed to encode certifier certificate", e);
		}
		return new byte[0];
	}

	private BaseRecord readReport(BaseRecord user, String reportOid, long orgId) {
		if (reportOid == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_REPORT, FieldNames.FIELD_OBJECT_ID, reportOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	private boolean linkCertificationToReport(BaseRecord user, String reportOid, long orgId, BaseRecord certification) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		try {
			Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_REPORT, FieldNames.FIELD_OBJECT_ID, reportOid);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.planCommon(true);
			BaseRecord report = ap.find(user, q);
			if (report == null) {
				return false;
			}
			report.set("status", "CERTIFIED");
			report.set("certification", certification);
			return ap.update(user, report) != null;
		} catch (Exception e) {
			logger.error("Failed to link certification to report " + reportOid, e);
			return false;
		}
	}

	/**
	 * Re-read the certification by objectId as {@code user} (planMost) and populate the restricted
	 * {@code signature} plus the {@code reportHash}/{@code signerCertificate} blobs, which default queries
	 * omit.
	 */
	private BaseRecord populateForVerify(BaseRecord user, BaseRecord certification) {
		String oid = certification.get(FieldNames.FIELD_OBJECT_ID);
		long orgId = lng(certification, FieldNames.FIELD_ORGANIZATION_ID);
		Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_CERTIFICATION, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		BaseRecord cert = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if (cert == null) {
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(cert,
			new String[] { FieldNames.FIELD_SIGNATURE, "reportHash", "signerCertificate" });
		return cert;
	}

	private static long lng(BaseRecord r, String field) {
		try {
			Object v = r.get(field);
			if (v instanceof Number) {
				return ((Number) v).longValue();
			}
		} catch (Exception e) {
			/* default */
		}
		return 0L;
	}
}
