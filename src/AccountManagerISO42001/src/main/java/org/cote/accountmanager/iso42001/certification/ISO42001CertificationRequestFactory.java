package org.cote.accountmanager.iso42001.certification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ApprovalResponseEnumType;

/**
 * Engine/service-callable certification-request lifecycle for {@code iso42001.certificationRequest}
 * (design §2.8, §3.1; the model inherits {@code access.accessRequest}). Pure backend — Phase 7 marshals
 * HTTP/MCP onto these methods without adding business logic.
 *
 * <p>The conversation is the inherited embedded {@code messages} list ({@code message.spool} records).
 * The reporter seeds it with the justification at create time; the certifier appends review notes and an
 * approve/deny note. Approval triggers {@link ISO42001CertificationFactory#createCertification} and links
 * the result back onto {@code resultingCertification}.</p>
 *
 * <p><b>RBAC (model {@code access.roles}):</b> create=Reporters; read=Readers/Certifiers;
 * update=Certifiers/Administrators. The {@code approvalStatus} field is NOT redeclared on this model (a
 * child model redeclaring an inherited field would produce duplicate schema fields — {@code RecordFactory}
 * appends inherited fields without de-duplication); it keeps the inherited {@code access.accessRequest}
 * field rule, update = system {@code Approvers}/{@code RequestUpdaters}, which is only evaluated when the
 * update record carries {@code approvalStatus} (approve/deny, not append). Certifiers/Administrators
 * satisfy it because {@code ISO42001Provisioning} enrols them in those system roles. So only a
 * certifier/administrator can append to the thread, approve, or deny — a non-certifier (reporter/tester)
 * is denied at the model-update boundary.</p>
 */
public class ISO42001CertificationRequestFactory {

	private static final Logger logger = LogManager.getLogger(ISO42001CertificationRequestFactory.class);

	/**
	 * Create a certification request (design §3.1 step 2) as {@code reporter} (ISO42001Reporters), seeded
	 * with the justification as the first message in the spool thread and {@code approvalStatus=REQUEST}.
	 *
	 * @return the persisted request (id/objectId stamped), or {@code null} on failure (e.g. RBAC denial)
	 */
	public BaseRecord createRequest(BaseRecord reporter, BaseRecord report, BaseRecord requestedCertifier,
			String justification, long groupId, long orgId) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		BaseRecord request;
		try {
			request = RecordFactory.model(ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST).newInstance();
			request.set(FieldNames.FIELD_NAME, "cert-request-" + UUID.randomUUID());
			request.set(FieldNames.FIELD_GROUP_ID, groupId);
			request.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			request.set(FieldNames.FIELD_OWNER_ID, (long) reporter.get(FieldNames.FIELD_ID));
			request.set("report", report);
			if (requestedCertifier != null) {
				request.set("requestedCertifier", requestedCertifier);
			}
			request.set("justification", justification);
			request.set(FieldNames.FIELD_APPROVAL_STATUS, ApprovalResponseEnumType.REQUEST);

			List<BaseRecord> msgs = request.get(FieldNames.FIELD_MESSAGES);
			if (msgs == null) {
				msgs = new ArrayList<>();
			}
			msgs.add(message(reporter, "Certification requested: " + (justification != null ? justification : ""), orgId));
			request.set(FieldNames.FIELD_MESSAGES, msgs);
		} catch (Exception e) {
			logger.error("Failed to build certification request", e);
			return null;
		}

		BaseRecord created = ap.create(reporter, request);
		if (created == null) {
			logger.error("certificationRequest CREATE returned null (RBAC?) for " + reporter.get(FieldNames.FIELD_NAME));
			return null;
		}
		try {
			request.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
			request.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		} catch (Exception e) {
			logger.warn("Could not stamp identity onto certification request", e);
			return created;
		}
		return request;
	}

	/**
	 * Append a message to the request's spool thread as {@code user} (requires model update —
	 * Certifiers/Administrators). Returns the updated request (re-read), or {@code null} on failure/denial.
	 */
	public BaseRecord appendMessage(BaseRecord user, BaseRecord request, String text) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		long orgId = lng(request, FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord full = readRequest(user, request.get(FieldNames.FIELD_OBJECT_ID), orgId);
		if (full == null) {
			logger.error("appendMessage could not read request (RBAC?)");
			return null;
		}
		try {
			List<BaseRecord> msgs = full.get(FieldNames.FIELD_MESSAGES);
			if (msgs == null) {
				msgs = new ArrayList<>();
			}
			msgs.add(message(user, text, orgId));
			return ap.update(user, minimalUpdate(full, msgs, null, null));
		} catch (Exception e) {
			logger.error("appendMessage failed", e);
			return null;
		}
	}

	/**
	 * Approve the request (design §3.1 step 4): append an approval note, set
	 * {@code approvalStatus=APPROVE}, then fire {@link ISO42001CertificationFactory#createCertification}
	 * (the certifier signs the report hash) and link the resulting certification onto the request.
	 *
	 * <p>The status transition is deliberately made <em>first</em>: it is the PBAC gate (inherited
	 * {@code approvalStatus} field rule → system Approvers/RequestUpdaters), so an unentitled caller is
	 * refused before anything is signed. The price is that a signing failure would otherwise strand the
	 * request at APPROVE with no certification — so if {@code createCertification} returns null
	 * <em>or throws</em>, the request is reverted to REQUEST with an audit message and the failure is
	 * surfaced (null return, or the original throwable). Measured live 2026-10-07 in the Docker image: a
	 * BouncyCastle {@code bcprov 1.76 / bcpkix 1.80} skew made the certifier's first keystore generation
	 * throw {@code NoSuchFieldError} — an {@code Error}, past every {@code catch (Exception)} — and the
	 * request was left APPROVED with nothing signed.</p>
	 *
	 * @param certifier the ISO42001Certifiers-role approver
	 * @return the created certification, or {@code null} if approval/update was denied or signing failed
	 *         (in which case the request is back at REQUEST)
	 */
	public BaseRecord approveRequest(BaseRecord certifier, BaseRecord request, String note) {
		return approveRequest(certifier, request, note, null, 0, null);
	}

	/**
	 * {@link #approveRequest(BaseRecord, BaseRecord, String)} with explicit certification terms (design
	 * §9A.8 Approve &amp; Sign dialog: title, validity period, notes). The terms are passed straight to
	 * {@link ISO42001CertificationFactory#createCertification(BaseRecord, BaseRecord, BaseRecord, String, int, String)};
	 * an invalid {@code validityMonths} is rejected <em>before</em> the request is transitioned, so a bad
	 * period never touches the request at all.
	 *
	 * @param certifierTitle stamped as {@code certification.certifierTitle} (null → default)
	 * @param validityMonths {@code 0} → default; else {@code 1..MAX_VALIDITY_MONTHS}
	 * @param notes          stamped as {@code certification.notes} (null → unset)
	 */
	public BaseRecord approveRequest(BaseRecord certifier, BaseRecord request, String note,
			String certifierTitle, int validityMonths, String notes) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		if (!ISO42001CertificationFactory.isValidValidityMonths(validityMonths)) {
			logger.error("approveRequest: validityMonths " + validityMonths + " is outside 1.."
				+ ISO42001CertificationFactory.MAX_VALIDITY_MONTHS + "; request left untouched");
			return null;
		}
		long orgId = lng(request, FieldNames.FIELD_ORGANIZATION_ID);
		String oid = request.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord full = readRequest(certifier, oid, orgId);
		if (full == null) {
			logger.error("approveRequest could not read request (RBAC?)");
			return null;
		}

		BaseRecord report = full.get("report");
		if (report == null) {
			logger.error("approveRequest: request has no report reference");
			return null;
		}

		List<BaseRecord> msgs;
		try {
			msgs = full.get(FieldNames.FIELD_MESSAGES);
			if (msgs == null) {
				msgs = new ArrayList<>();
			}
			msgs.add(message(certifier, "Approved: " + (note != null ? note : ""), orgId));
			BaseRecord approved = ap.update(certifier,
				minimalUpdate(full, msgs, ApprovalResponseEnumType.APPROVE, null));
			if (approved == null) {
				logger.error("approveRequest: approvalStatus update denied for " + certifier.get(FieldNames.FIELD_NAME));
				return null;
			}
		} catch (Exception e) {
			logger.error("approveRequest update failed", e);
			return null;
		}

		/// Fire signing as the certifier. From here on the request is APPROVED, so any failure must put it
		/// back to REQUEST (see class/method javadoc). LinkageError is caught alongside RuntimeException on
		/// purpose: a crypto-provider class/field mismatch is exactly the failure that reaches this point as
		/// an Error, and the request state must not depend on which kind of throwable the JVM chose.
		BaseRecord certification;
		try {
			certification = certificationFactory()
				.createCertification(certifier, report, certifier, certifierTitle, validityMonths, notes);
		} catch (RuntimeException | LinkageError e) {
			logger.error("approveRequest: signing threw " + e.getClass().getSimpleName() + "; reverting request to REQUEST", e);
			revertToRequest(certifier, full, msgs, orgId,
				"Signing failed (" + e.getClass().getSimpleName() + "); request returned to REQUEST");
			throw e;
		}
		if (certification == null) {
			logger.error("approveRequest: createCertification failed; reverting request to REQUEST");
			revertToRequest(certifier, full, msgs, orgId, "Signing failed; request returned to REQUEST");
			return null;
		}

		/// Link resultingCertification onto the request (minimal update — identity + the one field).
		try {
			ap.update(certifier, minimalUpdate(full, null, null, certification));
		} catch (Exception e) {
			logger.warn("approveRequest could not link resultingCertification", e);
		}

		return certification;
	}

	/**
	 * Deny the request (design §3.1 step 3): append a denial note and set {@code approvalStatus=DENY}.
	 * Returns the updated request, or {@code null} on failure/denial.
	 */
	public BaseRecord denyRequest(BaseRecord certifier, BaseRecord request, String reason) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		long orgId = lng(request, FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord full = readRequest(certifier, request.get(FieldNames.FIELD_OBJECT_ID), orgId);
		if (full == null) {
			logger.error("denyRequest could not read request (RBAC?)");
			return null;
		}
		try {
			List<BaseRecord> msgs = full.get(FieldNames.FIELD_MESSAGES);
			if (msgs == null) {
				msgs = new ArrayList<>();
			}
			msgs.add(message(certifier, "Denied: " + (reason != null ? reason : ""), orgId));
			return ap.update(certifier, minimalUpdate(full, msgs, ApprovalResponseEnumType.DENY, null));
		} catch (Exception e) {
			logger.error("denyRequest failed", e);
			return null;
		}
	}

	// ------------------------------------------------------------------

	/**
	 * Signing collaborator. Protected so a test can subclass this factory and hand back a
	 * {@link ISO42001CertificationFactory} whose {@code createCertification} fails (null or throws) to
	 * exercise the APPROVE→REQUEST revert without touching the real keystore/crypto path. Production
	 * callers never override it.
	 */
	protected ISO42001CertificationFactory certificationFactory() {
		return new ISO42001CertificationFactory();
	}

	/**
	 * Compensating update after a failed signing: append {@code reason} to the thread and set
	 * {@code approvalStatus=REQUEST} so the request is re-approvable. Uses the same minimal-update shape (and
	 * therefore the same PBAC path) as the APPROVE transition that just succeeded for this certifier. A failure
	 * here is logged at ERROR with the request id — it is the one case that leaves the request APPROVED with
	 * no certification, and it needs an administrator.
	 */
	private void revertToRequest(BaseRecord certifier, BaseRecord full, List<BaseRecord> msgs, long orgId, String reason) {
		AccessPoint ap = IOSystem.getActiveContext().getAccessPoint();
		try {
			List<BaseRecord> thread = (msgs != null) ? msgs : new ArrayList<>();
			thread.add(message(certifier, reason, orgId));
			BaseRecord reverted = ap.update(certifier, minimalUpdate(full, thread, ApprovalResponseEnumType.REQUEST, null));
			if (reverted == null) {
				logger.error("approveRequest: could NOT revert request " + full.get(FieldNames.FIELD_OBJECT_ID)
					+ " to REQUEST after a signing failure; it is APPROVED without a certification");
			}
		} catch (Exception e) {
			logger.error("approveRequest: revert of request " + full.get(FieldNames.FIELD_OBJECT_ID)
				+ " to REQUEST threw; it is APPROVED without a certification", e);
		}
	}

	private BaseRecord readRequest(BaseRecord user, String oid, long orgId) {
		Query q = QueryUtil.createQuery(ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Build a minimal update record: identity ({@code id/objectId/groupId/organizationId}) plus only the
	 * fields being changed. This avoids re-persisting the request's foreign refs ({@code report},
	 * {@code requestedCertifier} → a groupless {@code system.user}, {@code resultingCertification}) — a full
	 * re-update would trigger the dynamic auth check on those referenced objects, which (absent a group on the
	 * referenced record) falls back to role access and denies a legitimate certifier's MODIFY.
	 */
	private BaseRecord minimalUpdate(BaseRecord full, List<BaseRecord> msgs, ApprovalResponseEnumType status,
			BaseRecord resultingCertification) throws Exception {
		/// Materialize ONLY the fields we set. The bare RecordFactory.model(..).newInstance() overload
		/// materializes every model field at its default, and the DB writer persists every field present on
		/// the record, so the "minimal" update silently blanked report, requestedCertifier, justification,
		/// ownerId and approvalStatus on every appendMessage/deny/approve (found live 2026-10-07: approve
		/// after an appended message failed with "request has no report reference").
		Object gid = full.get(FieldNames.FIELD_GROUP_ID);
		List<String> fieldNames = new ArrayList<>(List.of(
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_NAME
		));
		if (gid != null) {
			fieldNames.add(FieldNames.FIELD_GROUP_ID);
		}
		if (msgs != null) {
			fieldNames.add(FieldNames.FIELD_MESSAGES);
		}
		if (status != null) {
			fieldNames.add(FieldNames.FIELD_APPROVAL_STATUS);
		}
		if (resultingCertification != null) {
			fieldNames.add("resultingCertification");
		}
		BaseRecord upd = RecordFactory.newInstance(ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST,
			fieldNames.toArray(new String[0]));
		upd.set(FieldNames.FIELD_ID, full.get(FieldNames.FIELD_ID));
		upd.set(FieldNames.FIELD_OBJECT_ID, full.get(FieldNames.FIELD_OBJECT_ID));
		if (gid != null) {
			upd.set(FieldNames.FIELD_GROUP_ID, gid);
		}
		upd.set(FieldNames.FIELD_ORGANIZATION_ID, full.get(FieldNames.FIELD_ORGANIZATION_ID));
		// name is part of the uniqueness constraint (name, groupId, organizationId); the update path checks it,
		// so a minimal update that omits it hits a NullPointerException (String.equals on a null name).
		upd.set(FieldNames.FIELD_NAME, full.get(FieldNames.FIELD_NAME));
		if (msgs != null) {
			upd.set(FieldNames.FIELD_MESSAGES, msgs);
		}
		if (status != null) {
			upd.set(FieldNames.FIELD_APPROVAL_STATUS, status);
		}
		if (resultingCertification != null) {
			upd.set("resultingCertification", resultingCertification);
		}
		return upd;
	}

	/** Build an (unpersisted) {@code message.spool} thread entry attributed to {@code sender}. */
	private BaseRecord message(BaseRecord sender, String text, long orgId) throws Exception {
		BaseRecord m = RecordFactory.model(ModelNames.MODEL_SPOOL).newInstance();
		m.set(FieldNames.FIELD_NAME, "cert-msg-" + UUID.randomUUID());
		m.set(FieldNames.FIELD_OBJECT_ID, UUID.randomUUID().toString());
		m.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		m.set(FieldNames.FIELD_OWNER_ID, (long) sender.get(FieldNames.FIELD_ID));
		m.set(FieldNames.FIELD_DATA, (text != null ? text : "").getBytes());
		return m;
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
