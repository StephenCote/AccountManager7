package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 1 foundation test for the ISO 42001 subsystem.
 *
 * Standing rules honored:
 *  - All services live; CRUD exercised through {@code AccessPoint} against the real DB.
 *  - Test organization: {@code /ISO42001} (DEVELOPMENT), created if absent.
 *  - The org Admin user is used ONLY to create role users + assign roles + create the
 *    shared group. NO assertion runs as the Admin user.
 *  - Every operation under test runs as a non-admin role user.
 *  - Each first-class model gets create/read/patch/list/delete plus a negative RBAC
 *    assertion (an unauthorized role user is denied create).
 *  - The two embedded models (testResult, reportSection) are validated through their
 *    parents (testRun.results, report.sections round-trip).
 *
 * NOTE: the iso42001 tables are auto-created on IO open. {@code IOSystem.open()} scans
 * {@code ModelNames.MODELS} and creates any missing tables via {@code generateNewSchemaOnly}
 * (no reset, no drops). {@link ISO42001ModelNames#use()} is therefore called BEFORE
 * {@code super.setup()} opens the IO context.
 */
public class TestISO42001Models extends BaseTest {

	private OrganizationContext isoOrg;
	private long orgId;
	private BaseRecord adminUser;

	private BaseRecord isoTester;
	private BaseRecord isoReporter;
	private BaseRecord isoCertifier;
	private BaseRecord isoReader;
	private BaseRecord isoAdmin;

	private long sharedGroupId;

	@Override
	@Before
	public void setup() {
		/// Register ISO 42001 model names BEFORE the IO context opens so the schema scan
		/// creates the iso42001 tables (additive — never resets/drops).
		ISO42001ModelNames.use();
		/// Run against the /ISO42001 development org. BaseTest.resetIO initializes this org
		/// (created fresh here, with its keystore under this module's working dir). The default
		/// System/Development orgs' keystores live under another module's CWD; their init misses
		/// inside IOSystem.open are caught there and are non-fatal to this run.
		organizationPath = "/ISO42001";
		super.setup();
		setupIso();
	}

	private void setupIso() {
		isoOrg = getTestOrganization("/ISO42001");
		orgId = isoOrg.getOrganizationId();
		adminUser = isoOrg.getAdminUser();
		assertNotNull("ISO org admin is null", adminUser);

		/// The 6 ISO 42001 auth.role names referenced by model access.roles (CamelCase),
		/// created at the org role root ("/Name") so the modelAccess pattern resolves them.
		BaseRecord testersRole     = ensureRole("ISO42001Testers");
		BaseRecord reportersRole   = ensureRole("ISO42001Reporters");
		BaseRecord certifiersRole  = ensureRole("ISO42001Certifiers");
		BaseRecord readersRole     = ensureRole("ISO42001Readers");
		ensureRole("ISO42001Auditors");
		BaseRecord adminsRole      = ensureRole("ISO42001Administrators");

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
		/// access.roles are the only permit path — making the negative RBAC check genuine.
		BaseRecord g = ioContext.getPathUtil().makePath(adminUser, ModelNames.MODEL_GROUP, "~/ISO42001Shared", "DATA", orgId);
		assertNotNull("Shared group is null", g);
		sharedGroupId = g.get(FieldNames.FIELD_ID);
	}

	private BaseRecord ensureRole(String name) {
		BaseRecord role = ioContext.getPathUtil().makePath(adminUser, ModelNames.MODEL_ROLE, "/" + name, RoleEnumType.USER.toString(), orgId);
		assertNotNull("Role " + name + " is null", role);
		return role;
	}

	private void ensureMember(BaseRecord role, BaseRecord user) {
		if (!ioContext.getMemberUtil().isMember(user, role, null)) {
			boolean ok = ioContext.getMemberUtil().member(adminUser, role, user, null, true);
			assertTrue("Failed to assign " + user.get(FieldNames.FIELD_NAME) + " to role", ok);
		}
	}

	/**
	 * Full create/read(as owner)/read(as reader role)/patch/list/negative-create/delete
	 * for a group-backed first-class model. Returns the planMost read record so callers
	 * can assert on embedded lists.
	 *
	 * @param model        the iso42001 model name
	 * @param creator      a non-admin user that holds the model's create role
	 * @param canReaderRead whether isoReader (ISO42001Readers) is in the model's read role
	 * @param fieldSetter  optional model-specific field population
	 */
	private BaseRecord crudAndRbac(String model, BaseRecord creator, boolean canReaderRead, String patchField, Object patchValue, Consumer<BaseRecord> fieldSetter) {
		String name = model.substring(model.indexOf('.') + 1) + "-" + UUID.randomUUID();

		/// CREATE as the authorized role user (not owner of the shared group).
		BaseRecord rec = newRec(model);
		set(rec, FieldNames.FIELD_NAME, name);
		set(rec, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(rec, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(rec, FieldNames.FIELD_OWNER_ID, (long) creator.get(FieldNames.FIELD_ID));
		if (fieldSetter != null) {
			fieldSetter.accept(rec);
		}
		BaseRecord created = ioContext.getAccessPoint().create(creator, rec);
		assertNotNull(model + " CREATE as authorized role user returned null", created);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull(model + " created objectId is null", oid);

		/// READ as the creator (owner-based access).
		BaseRecord readByOwner = findByObjectId(creator, model, oid);
		assertNotNull(model + " READ as creator failed", readByOwner);

		/// READ as isoReader purely via the model-level read role (isoReader owns nothing here).
		if (canReaderRead) {
			BaseRecord readByReader = findByObjectId(isoReader, model, oid);
			assertNotNull(model + " READ via ISO42001Readers role failed (model-level RBAC)", readByReader);
		}

		/// PATCH (update of a model-specific field) as the authorized role user. Re-read with a
		/// common plan so the record carries the group/owner context the update policy needs
		/// (a bare sparse patch lacks groupId and NPEs in policy resolution).
		Query pq = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, oid);
		pq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq.planCommon(true);
		BaseRecord toPatch = ioContext.getAccessPoint().find(creator, pq);
		assertNotNull(model + " re-read for PATCH failed", toPatch);
		set(toPatch, patchField, patchValue);
		BaseRecord updated = ioContext.getAccessPoint().update(creator, toPatch);
		assertNotNull(model + " PATCH as authorized role user failed", updated);

		/// LIST as a user holding the model read role. Query-level authorization here goes
		/// through the coarse model-level read (PolicyUtil) — so constrain by name (not groupId;
		/// a groupId constraint would instead force a group-read check on the admin-owned group
		/// that the model read role does not grant). isoReader (ISO42001Readers) reads all five.
		BaseRecord lister = canReaderRead ? isoReader : creator;
		Query lq = QueryUtil.createQuery(model, FieldNames.FIELD_NAME, name);
		lq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		QueryResult qr = ioContext.getAccessPoint().list(lister, lq);
		assertTrue(model + " LIST returned no records", qr != null && qr.getResults().length > 0);

		/// NEGATIVE RBAC: isoReader (no create role for any of these models) is denied create.
		BaseRecord bad = newRec(model);
		set(bad, FieldNames.FIELD_NAME, name + "-denied");
		set(bad, FieldNames.FIELD_GROUP_ID, sharedGroupId);
		set(bad, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		set(bad, FieldNames.FIELD_OWNER_ID, (long) isoReader.get(FieldNames.FIELD_ID));
		if (fieldSetter != null) {
			fieldSetter.accept(bad);
		}
		BaseRecord badCreated = ioContext.getAccessPoint().create(isoReader, bad);
		assertNull(model + " CREATE by unauthorized role user (isoReader) MUST be denied", badCreated);

		/// Re-read with full plan so callers can inspect embedded lists.
		BaseRecord full = findByObjectId(creator, model, oid);

		/// DELETE as the creator (owner-based; a non-admin role user).
		boolean deleted = ioContext.getAccessPoint().delete(creator, readByOwner);
		assertTrue(model + " DELETE as authorized role user failed", deleted);

		return full;
	}

	private BaseRecord findByObjectId(BaseRecord user, String model, String objectId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.planMost(true);
		return ioContext.getAccessPoint().find(user, q);
	}

	private void set(BaseRecord rec, String field, Object value) {
		try {
			rec.set(field, value);
		} catch (Exception e) {
			throw new RuntimeException("Failed to set " + field + " on " + rec.getSchema(), e);
		}
	}

	private BaseRecord newRec(String model) {
		try {
			return RecordFactory.model(model).newInstance();
		} catch (Exception e) {
			throw new RuntimeException("newInstance failed for " + model, e);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// First-class model CRUD + RBAC
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void testTestConfigCrud() {
		crudAndRbac(ISO42001ModelNames.MODEL_TEST_CONFIG, isoTester, true, "moduleId", "DATA", rec -> {
			set(rec, "moduleId", "BIAS");
			set(rec, "endpointName", "spark-ollama");
			set(rec, "endpointType", "ollama");
			set(rec, "samplesPerGroup", 30);
		});
	}

	@Test
	public void testTestRunCrudAndEmbeddedResults() {
		BaseRecord full = crudAndRbac(ISO42001ModelNames.MODEL_TEST_RUN, isoTester, true, "status", "FAILED", rec -> {
			set(rec, "status", "COMPLETED");
			set(rec, "modelEndpoint", "qwen3:8b");
			List<BaseRecord> results = rec.get("results");
			results.add(newTestResult("BIAS-ATTR-001", "PASS"));
			results.add(newTestResult("BIAS-ATTR-002", "FLAG"));
		});
		assertNotNull("testRun full re-read is null", full);
		List<BaseRecord> results = full.get("results");
		assertNotNull("testRun.results is null after round-trip", results);
		assertTrue("Expected 2 embedded testResult rows, got " + (results == null ? 0 : results.size()),
			results != null && results.size() == 2);
	}

	@Test
	public void testReportCrudAndEmbeddedSections() {
		BaseRecord full = crudAndRbac(ISO42001ModelNames.MODEL_REPORT, isoReporter, true, "status", "REVIEW", rec -> {
			set(rec, "reportType", "COMPLIANCE");
			set(rec, "status", "DRAFT");
			set(rec, "overallVerdict", "PASS");
			List<BaseRecord> sections = rec.get("sections");
			sections.add(newReportSection("EXECUTIVE_SUMMARY", 0));
			sections.add(newReportSection("METHODOLOGY", 1));
		});
		assertNotNull("report full re-read is null", full);
		List<BaseRecord> sections = full.get("sections");
		assertNotNull("report.sections is null after round-trip", sections);
		assertTrue("Expected 2 embedded reportSection rows, got " + (sections == null ? 0 : sections.size()),
			sections != null && sections.size() == 2);
	}

	@Test
	public void testCertificationCrud() {
		crudAndRbac(ISO42001ModelNames.MODEL_CERTIFICATION, isoCertifier, true, "notes", "patched note", rec -> {
			set(rec, "certifier", isoCertifier);
			set(rec, "certifierTitle", "Compliance Officer");
			set(rec, "status", "VALID");
		});
	}

	@Test
	public void testCertificationRequestCrud() {
		crudAndRbac(ISO42001ModelNames.MODEL_CERTIFICATION_REQUEST, isoReporter, true, "justification", "Updated justification after review.", rec -> {
			set(rec, "requestedCertifier", isoCertifier);
			set(rec, "justification", "February compliance cycle complete; ready for sign-off.");
		});
	}

	private BaseRecord newTestResult(String testId, String verdict) {
		BaseRecord r = newRec(ISO42001ModelNames.MODEL_TEST_RESULT);
		set(r, FieldNames.FIELD_NAME, testId);
		set(r, "testId", testId);
		set(r, "testModule", "BIAS");
		set(r, "verdict", verdict);
		return r;
	}

	private BaseRecord newReportSection(String sectionType, int order) {
		BaseRecord s = newRec(ISO42001ModelNames.MODEL_REPORT_SECTION);
		set(s, FieldNames.FIELD_NAME, sectionType);
		set(s, "sectionType", sectionType);
		set(s, "sectionOrder", order);
		set(s, "content", "Section content for " + sectionType);
		return s;
	}
}
