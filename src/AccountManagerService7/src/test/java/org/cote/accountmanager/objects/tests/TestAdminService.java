package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.AdminMaintenanceUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ThumbnailUtil;
import org.cote.rest.services.AdminService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// Component test for the AdminService REST shim (/rest/admin), exercised through the same hand-rolled
/// HttpServletRequestMock + UserPrincipal pattern as TestFeatureConfigService: the real service methods,
/// the real AdminMaintenanceUtil, and the real (H2) database, without the HTTP transport.
///
/// SCOPE LIMIT, STATED PLAINLY. Calling the service in-process does NOT run the container's
/// @RolesAllowed({"admin"}) - that is a Jersey/JAAS concern verified against the live stack
/// (AccountManagerUx752/e2e/systemAdmin.spec.js). What this file verifies is the status mapping, the
/// body shapes, the parameter validation, and - the point of the second gate - that a plain user who
/// somehow reaches the method is still refused (403) by AdminMaintenanceUtil's own role check.
///
/// USERS. The organization's actors live in a dedicated organization (/Development/AdminMaintenance) so
/// the audit trim/purge that really run here cannot touch another organization's journal. The
/// all-organizations scope admits ONLY an administrator of /System, so one credential-less /System user
/// (amSvcSysOrgAdmin, AccountAdministrators there) exercises that positive path; the test org's own
/// SystemAdministrators member is the NEGATIVE case - a per-organization role reaches no further than its
/// organization. The org admins appear only as setup actors for user creation and role grants, never as
/// assertion subjects.
public class TestAdminService extends BaseTest {

	private static final String ORG = "/Development/AdminMaintenance";
	private static final String ACCT_ADMIN = "amSvcAcctAdmin";
	private static final String SYS_ADMIN = "amSvcSysAdmin";
	private static final String SYS_ORG_ADMIN = "amSvcSysOrgAdmin";
	private static final String PLAIN = "amSvcPlainUser";

	private final AdminService service = new AdminService();

	private OrganizationContext org;
	private OrganizationContext systemOrg;
	private BaseRecord acct;
	private BaseRecord sys;
	private BaseRecord sysOrg;
	private BaseRecord nobody;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		org = getTestOrganization(ORG);
		assertNotNull("Failed to resolve " + ORG, org);
		assertTrue(ORG + " is not initialized", org.isInitialized());

		acct = withRole(org, ACCT_ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
		sys = withRole(org, SYS_ADMIN, AccessSchema.ROLE_SYSTEM_ADMINISTRATOR);
		systemOrg = ioContext.getOrganizationContext(OrganizationContext.SYSTEM_ORGANIZATION, OrganizationEnumType.SYSTEM);
		assertNotNull("Failed to resolve /System", systemOrg);
		assertTrue("/System is not initialized", systemOrg.isInitialized());
		sysOrg = withRole(systemOrg, SYS_ORG_ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
		assertTrue(SYS_ORG_ADMIN + " must be an administrator of /System",
			AdminMaintenanceUtil.isSystemOrganizationAdministrator(sysOrg, systemOrg.getOrganizationId()));
		nobody = getCreateUser(PLAIN, org);
		assertNotNull("Failed to resolve " + PLAIN, nobody);
		assertFalse(PLAIN + " must not be an administrator", AdminMaintenanceUtil.isAdministrator(nobody, org.getOrganizationId()));
	}

	private BaseRecord withRole(OrganizationContext o, String userName, String roleName) {
		BaseRecord u = getCreateUser(userName, o);
		assertNotNull("Failed to resolve " + userName + " - check the $minLen5 rule", u);
		BaseRecord role = AccessSchema.getSystemRole(roleName, RoleEnumType.USER.toString(), o.getOrganizationId());
		assertNotNull(roleName + " role is null", role);
		ioContext.getMemberUtil().member(o.getAdminUser(), role, u, null, true);
		assertTrue(userName + " must be a " + roleName + " member", ioContext.getMemberUtil().isMember(u, role, null));
		return u;
	}

	/// The principal's organization path is what ServiceUtil.getPrincipalUser resolves the user in, so a
	/// /System user must be presented as a /System principal.
	private HttpServletRequestMock requestAs(BaseRecord user) {
		String orgPath = (user == sysOrg ? OrganizationContext.SYSTEM_ORGANIZATION : ORG);
		return new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), orgPath));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> body(Response r) {
		assertNotNull("Response is null", r);
		Object entity = r.getEntity();
		assertNotNull("Response entity is null", entity);
		Map<String, Object> m = JSONUtil.importObject(entity.toString(), LinkedHashMap.class);
		assertNotNull("Response body did not parse as JSON: " + entity, m);
		return m;
	}

	private static long total(Map<String, Object> m) {
		return ((Number) m.get("total")).longValue();
	}

	/// An io.queryResult with no hits serializes WITHOUT a `results` key (RecordSerializer drops empty
	/// lists), so "no results" is an absent key, not an empty array. Same rule the panel applies.
	private List<?> results(Response r) {
		Object o = body(r).get("results");
		return (o instanceof List ? (List<?>) o : java.util.Collections.emptyList());
	}

	private static byte[] png() throws Exception {
		BufferedImage img = new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.GREEN);
		g.fillRect(0, 0, 120, 90);
		g.dispose();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(img, "png", baos));
		return baos.toByteArray();
	}

	private void imageWithThumbnail(BaseRecord user, String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/AdminSvc");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		data.set(FieldNames.FIELD_NAME, name);
		data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		data.set(FieldNames.FIELD_BYTE_STORE, png());
		BaseRecord created = ioContext.getAccessPoint().create(user, data);
		assertNotNull("Failed to create image " + name, created);
		BaseRecord full = ioContext.getAccessPoint().findByObjectId(user, ModelNames.MODEL_DATA, created.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull(full);
		assertNotNull("Thumbnail was not created for " + name, ThumbnailUtil.getCreateThumbnail(full, 40, 40));
	}

	private void audit(BaseRecord actor, ActionEnumType action, ResponseEnumType resp, String message, Date created) throws Exception {
		BaseRecord a = AuditUtil.startAudit(actor, action, actor, null);
		assertNotNull(a);
		a.set(FieldNames.FIELD_CREATED_DATE, created);
		AuditUtil.closeAudit(a, resp, message);
		ioContext.getQueue().execute();
	}

	private String searchJson(Map<String, Object> m) {
		return JSONUtil.exportObject(m);
	}

	// ─────────────────────────────── gates / shape ───────────────────────────────

	@Test
	public void TestNoPrincipalIs401Everywhere() {
		HttpServletRequestMock anon = new HttpServletRequestMock();
		assertEquals(401, service.getScope(anon).getStatus());
		assertEquals(401, service.countThumbnails(false, anon).getStatus());
		assertEquals(401, service.cleanupThumbnails(false, anon).getStatus());
		assertEquals(401, service.countAudit(false, anon).getStatus());
		assertEquals(401, service.searchAudit("{}", anon).getStatus());
		assertEquals(401, service.getAudit(UUID.randomUUID().toString(), false, anon).getStatus());
		assertEquals(401, service.deleteAudit(false, 30, false, anon).getStatus());
		assertEquals(401, service.deleteAudit(false, null, true, anon).getStatus());
	}

	/// The second gate: a non-administrator reaching the method is refused by Objects7, with the
	/// documented {"error":"..."} body.
	@Test
	public void TestPlainUserIs403Everywhere() {
		HttpServletRequestMock r = requestAs(nobody);
		Response scope = service.getScope(r);
		assertEquals(403, scope.getStatus());
		assertNotNull(body(scope).get("error"));
		assertEquals(403, service.countThumbnails(false, r).getStatus());
		assertEquals(403, service.cleanupThumbnails(false, r).getStatus());
		assertEquals(403, service.countAudit(false, r).getStatus());
		assertEquals(403, service.searchAudit("{}", r).getStatus());
		assertEquals(403, service.getAudit(UUID.randomUUID().toString(), false, r).getStatus());
		assertEquals(403, service.deleteAudit(false, 30, false, r).getStatus());
		assertEquals(403, service.deleteAudit(false, null, true, r).getStatus());
	}

	@Test
	public void TestAllOrganizationsScopeRequiresSystemOrganizationAdministrator() {
		/// BOTH of the test organization's administrators are refused - the SystemAdministrators member
		/// included. That role is per organization; it is not a cross-tenant grant.
		for(BaseRecord orgAdmin : new BaseRecord[] { acct, sys }) {
			HttpServletRequestMock r = requestAs(orgAdmin);
			Map<String, Object> scope = body(service.getScope(r));
			assertEquals(Boolean.FALSE, scope.get("allOrganizationsAllowed"));
			assertEquals(ORG, scope.get("organizationPath"));
			/// The empty list is the signal the panel keys on; it must be PRESENT, not dropped by a
			/// NON_EMPTY serializer.
			assertTrue("organizations must be an (empty) array for an organization's administrator: " + scope,
				scope.get("organizations") instanceof List && ((List<?>) scope.get("organizations")).isEmpty());
			Response denied = service.countThumbnails(true, r);
			assertEquals(403, denied.getStatus());
			assertTrue("the 403 names /System: " + denied.getEntity(), String.valueOf(body(denied).get("error")).contains("/System"));
			assertEquals(403, service.cleanupThumbnails(true, r).getStatus());
			assertEquals(403, service.countAudit(true, r).getStatus());
			assertEquals(403, service.searchAudit("{\"allOrganizations\":true}", r).getStatus());
			assertEquals(403, service.getAudit(UUID.randomUUID().toString(), true, r).getStatus());
			assertEquals(403, service.deleteAudit(true, 30, false, r).getStatus());
			assertEquals(403, service.deleteAudit(true, null, true, r).getStatus());
		}

		HttpServletRequestMock s = requestAs(sysOrg);
		Map<String, Object> sscope = body(service.getScope(s));
		assertEquals(Boolean.TRUE, sscope.get("allOrganizationsAllowed"));
		assertEquals(OrganizationContext.SYSTEM_ORGANIZATION, sscope.get("organizationPath"));
		List<?> orgs = (List<?>) sscope.get("organizations");
		assertTrue("a /System administrator is told which organizations the all scope covers", orgs.size() > 1);
		/// All-organizations READS as the /System administrator.
		Map<String, Object> allThumbs = body(service.countThumbnails(true, s));
		assertEquals(200, service.countThumbnails(true, s).getStatus());
		assertEquals("all", allThumbs.get("scope"));
		assertEquals(orgs.size(), ((List<?>) allThumbs.get("organizations")).size());
		Response allAudit = service.searchAudit("{\"allOrganizations\":true,\"recordCount\":5}", s);
		assertEquals(200, allAudit.getStatus());
		assertNotNull("the all-organizations search must carry a totalCount", body(allAudit).get("totalCount"));
	}

	@Test
	public void TestDeleteAuditParameterValidation() {
		HttpServletRequestMock r = requestAs(acct);
		Response neither = service.deleteAudit(false, null, false, r);
		assertEquals("neither days nor purge must be 400", 400, neither.getStatus());
		assertNotNull(body(neither).get("error"));
		assertEquals("both days and purge must be 400", 400, service.deleteAudit(false, 30, true, r).getStatus());
		assertEquals("days=0 must be 400", 400, service.deleteAudit(false, 0, false, r).getStatus());
		assertEquals("negative days must be 400", 400, service.deleteAudit(false, -1, false, r).getStatus());

		assertEquals("unparsable search JSON must be 400", 400, service.searchAudit("{not json", r).getStatus());
		assertEquals("a non-numeric date must be 400", 400, service.searchAudit("{\"since\":\"yesterday\"}", r).getStatus());
		assertEquals("an unknown action must be 400", 400, service.searchAudit("{\"action\":\"bogus\"}", r).getStatus());
		assertEquals("an unknown response must be 400", 400, service.searchAudit("{\"response\":\"bogus\"}", r).getStatus());
		assertEquals("an unknown audit id must be 404", 404, service.getAudit(UUID.randomUUID().toString(), false, r).getStatus());
	}

	// ─────────────────────────────── thumbnails ───────────────────────────────

	@Test
	public void TestThumbnailCountAndCleanup() throws Exception {
		HttpServletRequestMock r = requestAs(acct);
		imageWithThumbnail(acct, "svc-thumb-" + UUID.randomUUID().toString().substring(0, 8) + ".png");

		Map<String, Object> before = body(service.countThumbnails(false, r));
		assertEquals("organization", before.get("scope"));
		assertTrue(total(before) >= 1);

		Response del = service.cleanupThumbnails(false, r);
		assertEquals(200, del.getStatus());
		Map<String, Object> wiped = body(del);
		assertEquals(total(before), total(wiped));
		@SuppressWarnings("unchecked")
		Map<String, Object> row = (Map<String, Object>) ((List<?>) wiped.get("organizations")).get(0);
		assertEquals(ORG, row.get("path"));

		assertEquals(0L, total(body(service.countThumbnails(false, r))));
	}

	// ─────────────────────────────── audit ───────────────────────────────

	@Test
	public void TestAuditSearchViewTrimPurge() throws Exception {
		HttpServletRequestMock r = requestAs(acct);

		/// Known-empty journal in THIS organization; the purge's own entry is the one survivor.
		assertEquals(200, service.deleteAudit(false, null, true, r).getStatus());
		ioContext.getQueue().execute();
		assertEquals(1L, total(body(service.countAudit(false, r))));

		String marker = "svc-audit-" + UUID.randomUUID().toString().substring(0, 8);
		Date now = new Date();
		audit(acct, ActionEnumType.READ, ResponseEnumType.PERMIT, marker + " fresh", now);
		audit(acct, ActionEnumType.MODIFY, ResponseEnumType.DENY, marker + " fresh deny", now);
		audit(acct, ActionEnumType.READ, ResponseEnumType.PERMIT, marker + " old", new Date(now.getTime() - 40L * 86400000L));
		assertEquals(4L, total(body(service.countAudit(false, r))));

		/// Search: io.queryResult shape, newest first, totalCount, filters honoured.
		Map<String, Object> q = new LinkedHashMap<>();
		q.put("text", marker);
		q.put("recordCount", 2);
		Response page = service.searchAudit(searchJson(q), r);
		assertEquals(200, page.getStatus());
		Map<String, Object> qr = body(page);
		List<?> results = results(page);
		assertEquals(2, results.size());
		assertEquals(3, ((Number) qr.get("totalCount")).intValue());
		@SuppressWarnings("unchecked")
		Map<String, Object> first = (Map<String, Object>) results.get(0);
		assertEquals(ModelNames.MODEL_AUDIT, first.get("schema"));
		assertTrue("createdDate is epoch millis on the wire", first.get("createdDate") instanceof Number);
		assertNotNull(first.get("objectId"));
		assertTrue(String.valueOf(first.get("message")).contains("fresh"));

		q.put("response", "deny");
		assertEquals(1, results(service.searchAudit(searchJson(q), r)).size());

		Map<String, Object> q2 = new LinkedHashMap<>();
		q2.put("text", marker);
		q2.put("since", now.getTime() - 86400000L);
		assertEquals(2, results(service.searchAudit(searchJson(q2), r)).size());

		/// View.
		String oid = String.valueOf(first.get("objectId"));
		Response view = service.getAudit(oid, false, r);
		assertEquals(200, view.getStatus());
		Map<String, Object> detail = body(view);
		@SuppressWarnings("unchecked")
		Map<String, Object> rec = (Map<String, Object>) detail.get("audit");
		assertEquals(oid, rec.get("objectId"));
		assertEquals(ORG, detail.get("organizationPath"));
		@SuppressWarnings("unchecked")
		Map<String, Object> ctx = (Map<String, Object>) detail.get("contextUser");
		assertEquals(ACCT_ADMIN, ctx.get("name"));
		assertNull(detail.get("resource"));

		/// Trim to 30 days: the old one goes.
		Response trim = service.deleteAudit(false, 30, false, r);
		assertEquals(200, trim.getStatus());
		Map<String, Object> trimmed = body(trim);
		assertEquals(1L, total(trimmed));
		assertEquals(30, ((Number) trimmed.get("days")).intValue());
		ioContext.getQueue().execute();
		assertEquals(2, results(service.searchAudit("{\"text\":\"" + marker + "\"}", r)).size());

		/// Purge: everything, then only the purge's own entry.
		long beforePurge = total(body(service.countAudit(false, r)));
		Response purge = service.deleteAudit(false, null, true, r);
		assertEquals(200, purge.getStatus());
		assertEquals(beforePurge, total(body(purge)));
		ioContext.getQueue().execute();
		assertEquals(1L, total(body(service.countAudit(false, r))));
		assertEquals("a search with no hits has no results (the key is absent, not an empty array)", 0,
			results(service.searchAudit("{\"text\":\"" + marker + "\"}", r)).size());
	}
}
