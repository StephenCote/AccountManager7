package org.cote.accountmanager.iso42001.schema;

import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.RoleEnumType;

/**
 * On-demand provisioning of the ISO 42001 authorization graph (the 6 ISO roles + their PBAC wiring),
 * created with the organization's admin user. This replaces test-only role scaffolding so the same
 * provisioning is reusable from a Service7 startup hook in Phase 7 — the backend-callable contract.
 *
 * <p><b>Why on-demand in the ISO module (not a hook in {@code OrganizationContext.initialize}).</b> The
 * locked architecture forbids ISO knowledge in Objects7. {@code OrganizationContext.initialize} already
 * shows the pattern AM7 uses to stand up org-scoped identities/roles (the {@code admin}/{@code operations}/
 * {@code vault}/{@code documentControl}/{@code apiUser} system accounts created at org creation, and the
 * system roles at {@code /Name}); this util mirrors that pattern from the ISO side, idempotently, using the
 * org admin user — so nothing ISO-specific leaks into Objects7. A generic provisioning callback on
 * {@code initialize} would be the alternative if other subsystems wanted the same seam.</p>
 *
 * <p><b>Role-to-role entitlement (the PBAC unwind).</b> The {@code iso42001.certificationRequest} model
 * inherits {@code access.accessRequest}, whose {@code approvalStatus} field is gated at the FIELD level to
 * the system roles {@code Approvers}/{@code RequestUpdaters}. Rather than granting those system roles to
 * each certifier <em>user</em> (test-only scaffolding), this util makes the ISO <em>roles</em>
 * {@code ISO42001Certifiers}/{@code ISO42001Administrators} members of those system roles — so any user in
 * an ISO role inherits the approval capability via the actor→role→role→entitlement unwind. A non-certifier
 * is still denied at the {@code certificationRequest} model-update boundary (Certifiers/Administrators
 * only), so the negative-RBAC checks remain genuine.</p>
 */
public class ISO42001Provisioning {

	private static final Logger logger = LogManager.getLogger(ISO42001Provisioning.class);

	public static final String ROLE_TESTERS = "ISO42001Testers";
	public static final String ROLE_REPORTERS = "ISO42001Reporters";
	public static final String ROLE_CERTIFIERS = "ISO42001Certifiers";
	public static final String ROLE_READERS = "ISO42001Readers";
	public static final String ROLE_AUDITORS = "ISO42001Auditors";
	public static final String ROLE_ADMINISTRATORS = "ISO42001Administrators";

	public static final List<String> ROLES = Arrays.asList(
		ROLE_TESTERS, ROLE_REPORTERS, ROLE_CERTIFIERS, ROLE_READERS, ROLE_AUDITORS, ROLE_ADMINISTRATORS);

	private ISO42001Provisioning() {
	}

	/**
	 * Idempotently create the 6 ISO roles at the org role root and wire the certification-approval
	 * entitlement (ISO Certifiers/Administrators ∈ system Approvers + RequestUpdaters). Safe to call on
	 * every startup / test setup.
	 *
	 * @param adminUser the org admin user (the only privileged actor used here)
	 * @param orgId     the organization id
	 */
	public static void ensureRoles(BaseRecord adminUser, long orgId) {
		for (String r : ROLES) {
			ensureRole(adminUser, r, orgId);
		}

		BaseRecord certifiers = role(adminUser, ROLE_CERTIFIERS, orgId);
		BaseRecord admins = role(adminUser, ROLE_ADMINISTRATORS, orgId);
		BaseRecord requestUpdaters = AccessSchema.getSystemRole(AccessSchema.ROLE_REQUEST_UPDATERS, RoleEnumType.USER.toString(), orgId);
		BaseRecord approvers = AccessSchema.getSystemRole(AccessSchema.ROLE_APPROVERS, RoleEnumType.USER.toString(), orgId);
		BaseRecord accountUsersReaders = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS_READERS, RoleEnumType.USER.toString(), orgId);

		grantRoleToRole(adminUser, requestUpdaters, certifiers);
		grantRoleToRole(adminUser, approvers, certifiers);
		grantRoleToRole(adminUser, requestUpdaters, admins);
		grantRoleToRole(adminUser, approvers, admins);

		// A certificationRequest carries a requestedCertifier (system.user) foreign ref. Because system.user is
		// groupless, updating the request (approve/deny/append) makes the dynamic auth checker validate that
		// reference — and system.user's foreign sub-fields (homeDirectory, contactInformation) are read-gated
		// to AccountUsersReaders. Without this, a legitimate certifier's MODIFY is AUDIT-DENIED. Grant the ISO
		// updater roles read access to users via the system AccountUsersReaders role.
		grantRoleToRole(adminUser, accountUsersReaders, certifiers);
		grantRoleToRole(adminUser, accountUsersReaders, admins);
	}

	/** Create (idempotent) and return an ISO role at the org role root ({@code /Name}). */
	public static BaseRecord ensureRole(BaseRecord adminUser, String name, long orgId) {
		return role(adminUser, name, orgId);
	}

	private static BaseRecord role(BaseRecord adminUser, String name, long orgId) {
		BaseRecord r = IOSystem.getActiveContext().getPathUtil()
			.makePath(adminUser, ModelNames.MODEL_ROLE, "/" + name, RoleEnumType.USER.toString(), orgId);
		if (r == null) {
			logger.error("Failed to provision ISO role " + name);
		}
		return r;
	}

	/** Add {@code memberRole} as a member of {@code parentRole} (role-to-role), idempotently. */
	private static void grantRoleToRole(BaseRecord adminUser, BaseRecord parentRole, BaseRecord memberRole) {
		if (parentRole == null || memberRole == null) {
			logger.warn("Cannot wire role entitlement; a role is null (parent=" + parentRole + ", member=" + memberRole + ")");
			return;
		}
		if (!IOSystem.getActiveContext().getMemberUtil().isMember(memberRole, parentRole, null)) {
			boolean ok = IOSystem.getActiveContext().getMemberUtil().member(adminUser, parentRole, memberRole, null, true);
			if (!ok) {
				logger.warn("Failed to add role " + memberRole.get(FieldNames.FIELD_NAME)
					+ " to role " + parentRole.get(FieldNames.FIELD_NAME));
			}
		}
	}
}
