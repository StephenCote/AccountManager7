package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.schema.ISO42001Provisioning;
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
 * <p><b>Dedicated ISO test database (option #2, in effect 2026-06-22).</b> ISO tests run against their
 * OWN postgres database {@code am7isotestdb} (configured in this module's {@code resource.properties};
 * same host/port/creds as Objects7's {@code am7db} but a separate database). ISO therefore owns <em>every</em>
 * org from a blank DB — there is no shared Objects7 org to borrow — so {@link IOFactory#DEFAULT_FILE_BASE}
 * stays at the module-local {@code "./am7"} and ALL keystores/vaults/streams are generated locally on first
 * run. This eliminates the earlier cross-module keystore log noise entirely (verified: zero
 * {@code Key store does not exist} / "Organization already exists" lines).</p>
 *
 * <p><b>Do NOT point the base at {@code ../AccountManagerObjects7/am7} under this dedicated-DB setup.</b>
 * Agent7 points there because it shares Objects7's {@code am7db} and its default {@code /Development} org,
 * whose keystore already lives there — for a shared DB that base path is correct by design (the org records
 * and their keystores stay consistent as long as the base path is the one in effect when the org was created).
 * ISO no longer shares that DB: its orgs live in {@code am7isotestdb} with keystores generated under this
 * module's {@code ./am7}, so pointing at Objects7's am7 would not find them.</p>
 *
 * <p>The DB is created/blank externally (never dropped or recreated by the tests); committed
 * {@code test.db.reset=false}. The schema and orgs build additively on first IO open
 * ({@code generateNewSchemaOnly}). If the {@code am7isotestdb} container is ever recreated empty, just
 * re-run — the first run rebuilds schema + orgs + a fresh local {@code ./am7}.</p>
 *
 * <p>Registers the {@code iso42001} model namespace and targets the {@code /ISO42001}
 * development org <b>before</b> {@code super.setup()} opens the IO context, so the additive
 * schema scan creates the iso42001 tables.</p>
 *
 * <p>Standing rules baked in: the org Admin user is used ONLY here to create the role users,
 * assign roles, and make the shared (admin-owned) data group. No test assertion runs as Admin;
 * every operation under test runs as a non-admin role user, and the shared group's model-level
 * {@code access.roles} are the only permit path (so the negative-RBAC checks are genuine).</p>
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
		/// Module-local keystore/data dir. With the dedicated am7isotestdb, ISO owns every org and all
		/// keystores/vaults/streams are generated here. Do NOT point at Objects7's am7. See class javadoc.
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

		/// Provision the 6 ISO roles + their PBAC wiring via the production-reusable utility (created at
		/// the org role root "/Name" so the modelAccess pattern resolves them; Certifiers/Administrators
		/// are wired into the system Approvers/RequestUpdaters roles so they may transition the inherited
		/// access.accessRequest approvalStatus field). This is on-demand provisioning with the admin user,
		/// exactly what a Service7 startup hook will call in Phase 7 — not test-only scaffolding.
		ISO42001Provisioning.ensureRoles(adminUser, orgId);

		BaseRecord testersRole    = ensureRole("ISO42001Testers");
		BaseRecord reportersRole  = ensureRole("ISO42001Reporters");
		BaseRecord certifiersRole = ensureRole("ISO42001Certifiers");
		BaseRecord readersRole    = ensureRole("ISO42001Readers");
		BaseRecord adminsRole     = ensureRole("ISO42001Administrators");

		/// Admin creates the non-admin role users (the ONLY admin-user usage).
		isoTester    = getCreateUser("isoTester", isoOrg);
		isoReporter  = getCreateUser("isoReporter", isoOrg);
		isoCertifier = getCreateUser("isoCertifier", isoOrg);
		isoReader    = getCreateUser("isoReader", isoOrg);
		isoAdmin     = getCreateUser("isoAdmin", isoOrg);

		/// Assign each non-admin role user to its ISO role (the operational membership — who is a tester,
		/// reporter, certifier, etc.). The approval-capability wiring lives in ISO42001Provisioning at the
		/// ROLE level, so no per-user system-role grants are needed here.
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

	// ─────────────────────────────────────────────────────────────────────────
	// Deterministic fixtures (constructed directly — no live LLM) for the
	// reporting/PDF tests, which take persisted testRun+testResult records as input.
	// ─────────────────────────────────────────────────────────────────────────

	/** Build an (unpersisted) {@code iso42001.testResult} with the given verdict + statistics. */
	protected BaseRecord fixtureResult(String testId, String module, String protectedClass,
			String verdict, double effectSize, String effectSizeType, double correctedPValue) {
		BaseRecord r = newRec(ISO42001ModelNames.MODEL_TEST_RESULT);
		set(r, FieldNames.FIELD_NAME, testId + "-" + protectedClass);
		set(r, "testId", testId);
		set(r, "testModule", module);
		set(r, "protectedClass", protectedClass);
		set(r, "verdict", verdict);
		set(r, "effectSize", effectSize);
		set(r, "effectSizeType", effectSizeType);
		set(r, "pValue", correctedPValue);
		set(r, "correctedPValue", correctedPValue);
		set(r, "testStatistic", "fixture stat for " + testId);
		return r;
	}

	/**
	 * Create a persisted {@code iso42001.testRun} (COMPLETED) in the shared group owned by {@code creator}
	 * (a Testers-role user), carrying the supplied embedded results. Returns the re-read (planMost) run
	 * so its embedded {@code results} are populated for the report generator.
	 */
	protected BaseRecord createFixtureTestRun(BaseRecord creator, String modelEndpoint, java.util.List<BaseRecord> results) {
		BaseRecord run = newRec(ISO42001ModelNames.MODEL_TEST_RUN);
		set(run, FieldNames.FIELD_NAME, "fixture-run-" + java.util.UUID.randomUUID());
		set(run, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(run, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(run, FieldNames.FIELD_OWNER_ID, (long) creator.get(FieldNames.FIELD_ID));
		set(run, "status", "COMPLETED");
		set(run, "modelEndpoint", modelEndpoint);
		java.util.List<BaseRecord> embedded = run.get("results");
		embedded.addAll(results);
		set(run, "results", embedded);
		BaseRecord created = ioContext.getAccessPoint().create(creator, run);
		assertNotNull("fixture testRun CREATE returned null", created);
		return findByObjectId(creator, ISO42001ModelNames.MODEL_TEST_RUN, created.get(FieldNames.FIELD_OBJECT_ID));
	}

	/** Read a {@code data.data} byte store (blob) as raw bytes, populating the restricted field. */
	protected byte[] readDataBytes(BaseRecord user, String objectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		BaseRecord data = ioContext.getAccessPoint().find(user, q);
		if (data == null) {
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(data, new String[] { FieldNames.FIELD_BYTE_STORE });
		return data.get(FieldNames.FIELD_BYTE_STORE);
	}
}
