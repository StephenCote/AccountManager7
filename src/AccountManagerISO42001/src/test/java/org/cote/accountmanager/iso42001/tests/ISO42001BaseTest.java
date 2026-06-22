package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Before;

/**
 * Shared base for ISO 42001 <b>integration</b> tests (those that open a live IO context).
 * Centralizes the per-test scaffolding so each test class stops repeating it: file-base
 * pointing, model registration, org/role/user/shared-group creation, and the small CRUD
 * helpers.
 *
 * <p><b>File base.</b> {@link IOFactory#DEFAULT_FILE_BASE} stays at the module-local {@code "./am7"}.
 * This module owns a <em>dedicated</em> org ({@code /ISO42001}); its vault/keystore was generated under
 * the ISO module's local {@code am7/.jks/<orgId>} on first run and lives there. Agent7 points its base
 * at {@code ../AccountManagerObjects7/am7} because Agent7 reuses the default {@code /Development} org,
 * whose keystore Objects7 already created there. We can <b>not</b> do that: Objects7's {@code am7/.jks}
 * holds only the default orgs (1–13); the {@code /ISO42001} org is a new org (14) whose keystore is local —
 * pointing at Objects7's am7 finds 1–13 but not 14, so that org fails to initialize and every ISO test
 * fails (verified 2026-06-22).</p>
 *
 * <p><b>Residual non-fatal log noise.</b> {@code BaseTest} always initializes the shared {@code am7db}'s
 * default System/Development orgs (1/2/3), whose keystores are not local to this module — so the run logs
 * {@code Key store does not exist at ./am7/.jks/{1,2,3}/...} and "Organization already exists" for them.
 * ISO never asserts against those orgs, so it is non-fatal; the {@code /ISO42001} org (local keystore)
 * initializes fine. <b>The clean elimination is a dedicated ISO test database (option #2 below)</b>, where
 * ISO would own every org from a one-shot reset and no foreign keystore would be missing.</p>
 *
 * <p>Registers the {@code iso42001} model namespace and targets the {@code /ISO42001}
 * development org <b>before</b> {@code super.setup()} opens the IO context, so the additive
 * schema scan ({@code generateNewSchemaOnly}) creates the iso42001 tables — no reset/drop.</p>
 *
 * <p>Standing rules baked in: the org Admin user is used ONLY here to create the role users,
 * assign roles, and make the shared (admin-owned) data group. No test assertion runs as Admin;
 * every operation under test runs as a non-admin role user, and the shared group's model-level
 * {@code access.roles} are the only permit path (so the negative-RBAC checks are genuine).</p>
 *
 * <p><b>Implementation note / TODO (Stephen, 2026-06-22): dedicated iso42001 test DB (option #2).</b>
 * The ISO tests currently share the {@code am7db} unit-test database with Objects7 (its default orgs live
 * there). A dedicated ISO postgres database + keystore would let ISO own every org from a one-shot reset,
 * isolate it from Objects7's dev keys/data, reset independently, and remove the residual default-org
 * keystore log noise above. Deferred until someone provisions the db. When it exists, point ISO
 * {@code resource.properties} {@code test.db.url} (and, if the keystore moves with it, {@code DEFAULT_FILE_BASE})
 * at the dedicated db/keystore.</p>
 */
public abstract class ISO42001BaseTest extends BaseTest {

	protected OrganizationContext isoOrg;
	protected long orgId;
	protected BaseRecord adminUser;

	protected BaseRecord isoTester;
	protected BaseRecord isoReporter;
	protected BaseRecord isoCertifier;
	protected BaseRecord isoReader;
	protected BaseRecord isoAdmin;

	protected long sharedGroupId;

	@Override
	@Before
	public void setup() {
		/// Module-local keystore/data dir: the /ISO42001 org's keystore lives here (am7/.jks/<orgId>).
		/// Do NOT point at Objects7's am7 (it holds only the default orgs 1–13, not the ISO org). See class javadoc.
		IOFactory.DEFAULT_FILE_BASE = "./am7";
		/// Register ISO model names BEFORE IO opens so the additive schema scan creates the tables.
		ISO42001ModelNames.use();
		organizationPath = "/ISO42001";
		super.setup();
		setupIso();
	}

	protected void setupIso() {
		isoOrg = getTestOrganization("/ISO42001");
		orgId = isoOrg.getOrganizationId();
		adminUser = isoOrg.getAdminUser();
		assertNotNull("ISO org admin is null", adminUser);

		/// The 6 ISO 42001 auth.role names referenced by model access.roles (CamelCase),
		/// created at the org role root ("/Name") so the modelAccess pattern resolves them.
		BaseRecord testersRole    = ensureRole("ISO42001Testers");
		BaseRecord reportersRole  = ensureRole("ISO42001Reporters");
		BaseRecord certifiersRole = ensureRole("ISO42001Certifiers");
		BaseRecord readersRole    = ensureRole("ISO42001Readers");
		ensureRole("ISO42001Auditors");
		BaseRecord adminsRole     = ensureRole("ISO42001Administrators");

		/// Admin creates the non-admin role users (the ONLY admin-user usage).
		isoTester    = getCreateUser("isoTester", isoOrg);
		isoReporter  = getCreateUser("isoReporter", isoOrg);
		isoCertifier = getCreateUser("isoCertifier", isoOrg);
		isoReader    = getCreateUser("isoReader", isoOrg);
		isoAdmin     = getCreateUser("isoAdmin", isoOrg);

		ensureMember(testersRole, isoTester);
		ensureMember(reportersRole, isoReporter);
		ensureMember(certifiersRole, isoCertifier);
		ensureMember(readersRole, isoReader);
		ensureMember(adminsRole, isoAdmin);

		/// A shared, ADMIN-owned data group. Role users are NOT owners, so the model-level
		/// access.roles are the only permit path — making the negative RBAC checks genuine.
		BaseRecord g = ioContext.getPathUtil().makePath(adminUser, ModelNames.MODEL_GROUP, "~/ISO42001Shared", "DATA", orgId);
		assertNotNull("Shared group is null", g);
		sharedGroupId = g.get(FieldNames.FIELD_ID);
	}

	protected BaseRecord ensureRole(String name) {
		BaseRecord role = ioContext.getPathUtil().makePath(adminUser, ModelNames.MODEL_ROLE, "/" + name, RoleEnumType.USER.toString(), orgId);
		assertNotNull("Role " + name + " is null", role);
		return role;
	}

	protected void ensureMember(BaseRecord role, BaseRecord user) {
		if (!ioContext.getMemberUtil().isMember(user, role, null)) {
			boolean ok = ioContext.getMemberUtil().member(adminUser, role, user, null, true);
			assertTrue("Failed to assign " + user.get(FieldNames.FIELD_NAME) + " to role", ok);
		}
	}

	/** Set a field, rethrowing the checked schema exceptions as unchecked for test brevity. */
	protected void set(BaseRecord rec, String field, Object value) {
		try {
			rec.set(field, value);
		} catch (Exception e) {
			throw new RuntimeException("Failed to set " + field + " on " + rec.getSchema(), e);
		}
	}

	/** New (unpersisted) instance of an iso42001 model. */
	protected BaseRecord newRec(String model) {
		try {
			return RecordFactory.model(model).newInstance();
		} catch (Exception e) {
			throw new RuntimeException("newInstance failed for " + model, e);
		}
	}

	/** Find a single record by objectId in the ISO org, fully planned (planMost recursion). */
	protected BaseRecord findByObjectId(BaseRecord user, String model, String objectId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		return ioContext.getAccessPoint().find(user, q);
	}
}
