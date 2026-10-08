package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.ModelService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// PATCH /rest/model status contract (Core REST audit, 2026-10-07). The Objects7 side
/// (TestPatchIdentityOutcome) proves AccessPoint.update exposes PERMIT/DENY/INVALID through
/// AuditUtil; this file proves ModelService.patchModel maps the body and that outcome to
/// 400 / 404 / 403 / 422 / 200 instead of the old `200 false`.
///
/// Same in-process pattern as TestAccessRequestService: real service method, real AccessPoint/PBAC,
/// real database, HttpServletRequestMock + UserPrincipal. All actors are non-admin users.
public class TestModelServicePatch extends BaseTest {

	private final ModelService service = new ModelService();

	private BaseRecord owner;
	private BaseRecord reader;
	private BaseRecord stranger;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		owner = getCreateUser("mspOwner");
		reader = getCreateUser("mspReader");
		stranger = getCreateUser("mspStranger");
		assertNotNull(owner);
		assertNotNull(reader);
		assertNotNull(stranger);
	}

	private HttpServletRequestMock requestAs(BaseRecord user) {
		return new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), organizationPath));
	}

	private Response patch(BaseRecord user, Map<String, Object> body) {
		return service.patchModel(JSONUtil.exportObject(body), requestAs(user), new HttpServletResponseMock());
	}

	@SuppressWarnings("unchecked")
	private String errorOf(Response r) {
		assertNotNull("Response entity is null", r.getEntity());
		Map<String, Object> m = JSONUtil.importObject(r.getEntity().toString(), LinkedHashMap.class);
		assertNotNull("Error body did not parse as JSON: " + r.getEntity(), m);
		Object err = m.get("error");
		assertNotNull("Error body must carry 'error': " + r.getEntity(), err);
		return err.toString();
	}

	private BaseRecord createNote(BaseRecord user, String name, String text) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/ModelServicePatch");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord n = ioContext.getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		n.set("text", text);
		BaseRecord created = ioContext.getAccessPoint().create(user, n);
		assertNotNull("note CREATE returned null", created);
		return created;
	}

	private BaseRecord reread(BaseRecord user, String objectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_OBJECT_ID, objectId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, "text" });
		q.setCache(false);
		return ioContext.getAccessPoint().find(user, q);
	}

	private Map<String, Object> noteBody(BaseRecord created, String name, String text) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("schema", ModelNames.MODEL_NOTE);
		m.put(FieldNames.FIELD_ID, (long) created.get(FieldNames.FIELD_ID));
		m.put(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		m.put(FieldNames.FIELD_NAME, name);
		m.put("text", text);
		return m;
	}

	/// Grant `grantee` READ (group + data) on the directory holding `note`, as the note's owner.
	/// member(..., true) returns false when the entry already exists (re-runs share users and
	/// directory), so the grant is verified by the caller's subsequent read, not by the return value.
	private void grantRead(BaseRecord noteOwner, BaseRecord note, BaseRecord grantee) {
		long gid = note.get(FieldNames.FIELD_GROUP_ID);
		BaseRecord dir = ioContext.getAccessPoint().findById(noteOwner, ModelNames.MODEL_GROUP, gid);
		assertNotNull("note directory must be readable by its owner", dir);
		for(String type : new String[] { PermissionEnumType.GROUP.toString(), PermissionEnumType.DATA.toString() }) {
			BaseRecord perm = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, type, orgId);
			assertNotNull("Read/" + type + " permission is null", perm);
			ioContext.getMemberUtil().member(noteOwner, dir, grantee, perm, true);
		}
	}

	/// ------------------------------------------------------------------------------------------

	@Test
	public void TestHappyPathIs200AndPersists() throws Exception {
		String name = "msp-" + UUID.randomUUID();
		BaseRecord created = createNote(owner, name, "before");
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		Response r = patch(owner, noteBody(created, name, "after"));
		assertEquals("owner patch must be 200, body: " + r.getEntity(), 200, r.getStatus());
		assertEquals(Boolean.TRUE, r.getEntity());
		assertEquals("after", reread(owner, oid).get("text"));
	}

	@Test
	public void TestBodyWithoutSchemaIs400() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(FieldNames.FIELD_OBJECT_ID, UUID.randomUUID().toString());
		m.put("text", "x");
		Response r = patch(owner, m);
		assertEquals("no schema must be 400, body: " + r.getEntity(), 400, r.getStatus());
		errorOf(r);
	}

	@Test
	public void TestUnknownModelIs400() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("schema", "nope.doesNotExist");
		m.put(FieldNames.FIELD_OBJECT_ID, UUID.randomUUID().toString());
		Response r = patch(owner, m);
		assertEquals("unknown model must be 400, body: " + r.getEntity(), 400, r.getStatus());
		errorOf(r);
	}

	@Test
	public void TestMissingIdentityIs400NotCreate() throws Exception {
		String name = "msp-noid-" + UUID.randomUUID();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("schema", ModelNames.MODEL_NOTE);
		m.put(FieldNames.FIELD_NAME, name);
		m.put("text", "should not be created");
		Response r = patch(owner, m);
		assertEquals("identity-less patch must be 400, body: " + r.getEntity(), 400, r.getStatus());
		assertTrue(errorOf(r).toLowerCase().contains("identity"));

		/// The old behaviour was to fall into AccessPoint.update's create branch; nothing may exist now.
		Query q = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		assertEquals("a 400 patch must not have created a record", 0, ioContext.getAccessPoint().count(owner, q));
	}

	@Test
	public void TestUnknownRecordIs404() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("schema", ModelNames.MODEL_NOTE);
		m.put(FieldNames.FIELD_OBJECT_ID, UUID.randomUUID().toString());
		m.put(FieldNames.FIELD_NAME, "ghost");
		m.put("text", "x");
		Response r = patch(owner, m);
		assertEquals("unknown objectId must be 404, body: " + r.getEntity(), 404, r.getStatus());
		errorOf(r);
	}

	/// A user who cannot even read the target gets the same 404 as a missing record (no existence
	/// oracle), and the record is untouched.
	@Test
	public void TestUnreadableRecordIs404AndUnchanged() throws Exception {
		String name = "msp-" + UUID.randomUUID();
		BaseRecord created = createNote(owner, name, "owned");
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		Response r = patch(stranger, noteBody(created, name, "hijacked"));
		assertEquals("stranger patch must be 404, body: " + r.getEntity(), 404, r.getStatus());
		errorOf(r);
		assertEquals("owned", reread(owner, oid).get("text"));
	}

	/// Read-but-not-update: the pre-flight find succeeds, AccessPoint.update is DENIED, and the
	/// service answers 403 with the audit reason. Record untouched.
	@Test
	public void TestReadOnlyUserIs403AndUnchanged() throws Exception {
		String name = "msp-" + UUID.randomUUID();
		BaseRecord created = createNote(owner, name, "owned");
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord full = reread(owner, oid);
		grantRead(owner, full, reader);
		assertNotNull("precondition: reader must be able to read the note after the grant", reread(reader, oid));

		Response r = patch(reader, noteBody(created, name, "hijacked"));
		assertEquals("read-only patch must be 403, body: " + r.getEntity(), 403, r.getStatus());
		assertTrue("403 body should carry the audit outcome: " + r.getEntity(), errorOf(r).contains("DENY"));
		assertEquals("owned", reread(owner, oid).get("text"));
	}

	/// Owner, but the patch itself fails writer validation (blank name): 422, not 403, record untouched.
	/// The body is built by hand: JSONUtil.exportObject uses Include.NON_EMPTY and would silently drop
	/// `"name":""`, turning this into a valid text-only patch.
	@Test
	public void TestValidationFailureIs422AndUnchanged() throws Exception {
		String name = "msp-" + UUID.randomUUID();
		BaseRecord created = createNote(owner, name, "valid");
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);

		String json = "{\"schema\":\"" + ModelNames.MODEL_NOTE + "\",\"id\":" + (long) created.get(FieldNames.FIELD_ID)
			+ ",\"objectId\":\"" + oid + "\",\"name\":\"\",\"text\":\"should-not-land\"}";
		Response r = service.patchModel(json, requestAs(owner), new HttpServletResponseMock());
		assertEquals("blank-name patch must be 422, body: " + r.getEntity(), 422, r.getStatus());
		assertTrue("422 body should carry the audit outcome: " + r.getEntity(), errorOf(r).contains("INVALID"));
		BaseRecord fresh = reread(owner, oid);
		assertEquals("valid", fresh.get("text"));
		assertEquals(name, fresh.get(FieldNames.FIELD_NAME));
	}

	@Test
	public void TestNoPrincipalIs401() throws Exception {
		String name = "msp-" + UUID.randomUUID();
		BaseRecord created = createNote(owner, name, "owned");
		Response r = service.patchModel(JSONUtil.exportObject(noteBody(created, name, "anon")), new HttpServletRequestMock(), new HttpServletResponseMock());
		assertEquals("no principal must be 401, body: " + r.getEntity(), 401, r.getStatus());
		errorOf(r);
		assertEquals("owned", reread(owner, created.get(FieldNames.FIELD_OBJECT_ID)).get("text"));
	}
}
