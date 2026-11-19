package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.AdminMaintenanceException;
import org.cote.accountmanager.util.AdminMaintenanceUtil;
import org.cote.accountmanager.util.AdminMaintenanceUtil.AuditFilter;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ThumbnailUtil;
import org.junit.Test;

/// AdminMaintenanceUtil - the Objects7 half of the Ux752 Admin panel (/admin/system): thumbnail wipe,
/// audit list/search/view, audit trim/purge, and the two authorization gates in front of them.
///
/// Runs against the database this JVM opened (am7db by default). EVERYTHING DESTRUCTIVE HERE IS CONFINED
/// TO ONE DEDICATED ORGANIZATION (/Development/AdminMaintenance): the audit trim and purge really run,
/// but only with the organization scope, so no other organization's journal or thumbnails are touched.
/// The all-organizations scope is exercised for its GATE (403 for every administrator of the test
/// organization - AccountAdministrators AND SystemAdministrators members alike: a per-organization role
/// never reaches past its organization) and for its READ paths (count, list, detail) as an administrator
/// of /System; the all-organizations DELETE paths are deliberately NOT run against a shared database.
/// State that limit plainly: the cross-org delete fan-out is the same perOrganization loop the single-org
/// path uses, so what is untested is the loop over more than one element, not the delete itself.
///
/// Actors (never the built-in admin user as an assertion subject - llm-conduct rule 3):
///   amAcctAdmin   - AccountAdministrators member of the test org: an administrator of that org only
///   amSysAdmin    - SystemAdministrators member of the test org: STILL an administrator of that org only
///   amSysOrgAdmin - AccountAdministrators member of /System: the one actor allowed all organizations.
///                   Created credential-less by Factory.getCreateUser (it cannot log in); /System is the
///                   deployment-global org, so this is the only way to exercise the positive path without
///                   the built-in admin.
///   amPlainUser   - no administrative role at all
public class TestAdminMaintenanceUtil extends BaseTest {

	private static final String ORG = "/Development/AdminMaintenance";
	private static final String ACCT_ADMIN = "amAcctAdmin";
	private static final String SYS_ADMIN = "amSysAdmin";
	private static final String SYS_ORG_ADMIN = "amSysOrgAdmin";
	private static final String PLAIN = "amPlainUser";
	private static final String GROUP = "~/AdminMaintenance";

	// ─────────────────────────────── fixture ───────────────────────────────

	private OrganizationContext org() {
		OrganizationContext o = getTestOrganization(ORG);
		assertNotNull("Failed to resolve " + ORG, o);
		assertTrue(ORG + " is not initialized", o.isInitialized());
		return o;
	}

	private BaseRecord withRole(OrganizationContext o, String userName, String roleName) {
		BaseRecord u = getCreateUser(userName, o);
		assertNotNull("Failed to resolve " + userName + " - check the $minLen5 rule", u);
		BaseRecord role = AccessSchema.getSystemRole(roleName, RoleEnumType.USER.toString(), o.getOrganizationId());
		assertNotNull(roleName + " role is null", role);
		/// The grant is inherently an admin act, so the org admin is the setup actor; idempotent.
		ioContext.getMemberUtil().member(o.getAdminUser(), role, u, null, true);
		assertTrue(userName + " must be a " + roleName + " member", ioContext.getMemberUtil().isMember(u, role, null));
		return u;
	}

	private BaseRecord acctAdmin(OrganizationContext o) {
		return withRole(o, ACCT_ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
	}

	private BaseRecord sysAdmin(OrganizationContext o) {
		return withRole(o, SYS_ADMIN, AccessSchema.ROLE_SYSTEM_ADMINISTRATOR);
	}

	/// An administrator OF /System - the only principal the all-organizations scope admits.
	private BaseRecord systemOrgAdmin() {
		OrganizationContext sys = ioContext.getOrganizationContext(OrganizationContext.SYSTEM_ORGANIZATION, OrganizationEnumType.SYSTEM);
		assertNotNull("Failed to resolve " + OrganizationContext.SYSTEM_ORGANIZATION, sys);
		assertTrue(OrganizationContext.SYSTEM_ORGANIZATION + " is not initialized", sys.isInitialized());
		BaseRecord u = withRole(sys, SYS_ORG_ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
		assertTrue(SYS_ORG_ADMIN + " must be an administrator of /System", AdminMaintenanceUtil.isSystemOrganizationAdministrator(u, sys.getOrganizationId()));
		return u;
	}

	private BaseRecord plain(OrganizationContext o) {
		BaseRecord u = getCreateUser(PLAIN, o);
		assertNotNull("Failed to resolve " + PLAIN, u);
		assertFalse(PLAIN + " must not be an administrator", AdminMaintenanceUtil.isAdministrator(u, o.getOrganizationId()));
		return u;
	}

	private static byte[] png(int w, int h) throws Exception {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, w, h);
		g.setColor(Color.BLUE);
		g.fillOval(w / 4, h / 4, w / 2, h / 2);
		g.dispose();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		assertTrue(ImageIO.write(img, "png", baos));
		return baos.toByteArray();
	}

	/// An image data.data in the user's own group, created through AccessPoint as that user, plus its
	/// 50x50 thumbnail through the production ThumbnailUtil path (what MediaUtil does on a thumbnail GET).
	private BaseRecord imageWithThumbnail(BaseRecord user, String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, GROUP);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		data.set(FieldNames.FIELD_NAME, name);
		data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		data.set(FieldNames.FIELD_BYTE_STORE, png(200, 160));
		BaseRecord created = ioContext.getAccessPoint().create(user, data);
		assertNotNull("Failed to create image " + name, created);
		BaseRecord full = ioContext.getAccessPoint().findByObjectId(user, ModelNames.MODEL_DATA, created.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("Failed to re-read image " + name, full);
		BaseRecord thumb = ThumbnailUtil.getCreateThumbnail(full, 50, 50);
		assertNotNull("Thumbnail was not created for " + name, thumb);
		return full;
	}

	/// A closed audit in `o`, written through the real AuditUtil path (hash, signature, queue), with an
	/// explicit createdDate so trim has something older than today to find. Flushed synchronously.
	private BaseRecord audit(OrganizationContext o, BaseRecord actor, ActionEnumType action, ResponseEnumType resp, String message, Date created) throws Exception {
		BaseRecord a = AuditUtil.startAudit(actor, action, actor, null);
		assertNotNull("startAudit returned null", a);
		a.set(FieldNames.FIELD_CREATED_DATE, created);
		AuditUtil.closeAudit(a, resp, message);
		ioContext.getQueue().execute();
		return a;
	}

	/// BaseRecord.get is generic, so String.valueOf(rec.get(f)) resolves to the char[] overload at compile
	/// time and throws ClassCastException at run time; force the Object overload.
	private static String str(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return String.valueOf(v);
	}

	private static long days(int n) {
		return n * 86400000L;
	}

	private static long count(Map<String, Object> result) {
		return ((Number) result.get("total")).longValue();
	}

	private static int status(Runnable r) {
		try {
			r.run();
			return 200;
		}
		catch(AdminMaintenanceException e) {
			return e.getStatus();
		}
	}

	// ─────────────────────────────── gates ───────────────────────────────

	@Test
	public void gatesRefuseAnonymousPlainAndNonSystemAdministrators() {
		OrganizationContext o = org();
		BaseRecord acct = acctAdmin(o);
		BaseRecord sys = sysAdmin(o);
		BaseRecord sysOrg = systemOrgAdmin();
		BaseRecord nobody = plain(o);

		assertEquals("null principal must be 401", 401, status(() -> AdminMaintenanceUtil.requireAdministrator(null)));
		assertEquals("a plain user must be 403", 403, status(() -> AdminMaintenanceUtil.requireAdministrator(nobody)));
		assertEquals("an AccountAdministrators member is an administrator", o.getOrganizationId(), AdminMaintenanceUtil.requireAdministrator(acct));
		assertEquals("a SystemAdministrators member is an administrator", o.getOrganizationId(), AdminMaintenanceUtil.requireAdministrator(sys));

		assertFalse("an AccountAdministrators member of " + ORG + " is not a /System administrator", AdminMaintenanceUtil.isSystemOrganizationAdministrator(acct, o.getOrganizationId()));
		assertFalse("a SystemAdministrators member of " + ORG + " is STILL not a /System administrator", AdminMaintenanceUtil.isSystemOrganizationAdministrator(sys, o.getOrganizationId()));
		assertTrue(AdminMaintenanceUtil.isSystemOrganizationAdministrator(sysOrg, ((Number) sysOrg.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue()));

		/// Every all-organizations entry point refuses BOTH of the test org's administrators with 403 -
		/// the SystemAdministrators member included: that role is per organization ...
		for(BaseRecord orgAdmin : new BaseRecord[] { acct, sys }) {
			assertEquals(403, status(() -> AdminMaintenanceUtil.countThumbnails(orgAdmin, true)));
			assertEquals(403, status(() -> AdminMaintenanceUtil.cleanupThumbnails(orgAdmin, true)));
			assertEquals(403, status(() -> AdminMaintenanceUtil.countAudit(orgAdmin, true)));
			assertEquals(403, status(() -> AdminMaintenanceUtil.listAudit(orgAdmin, true, null)));
			assertEquals(403, status(() -> AdminMaintenanceUtil.getAuditDetail(orgAdmin, true, UUID.randomUUID().toString())));
			assertEquals(403, status(() -> AdminMaintenanceUtil.trimAudit(orgAdmin, true, 30)));
			assertEquals(403, status(() -> AdminMaintenanceUtil.purgeAudit(orgAdmin, true)));
		}
		/// ... and every entry point refuses the plain user, in either scope.
		assertEquals(403, status(() -> AdminMaintenanceUtil.countThumbnails(nobody, false)));
		assertEquals(403, status(() -> AdminMaintenanceUtil.cleanupThumbnails(nobody, false)));
		assertEquals(403, status(() -> AdminMaintenanceUtil.listAudit(nobody, false, null)));
		assertEquals(403, status(() -> AdminMaintenanceUtil.getAuditDetail(nobody, false, UUID.randomUUID().toString())));
		assertEquals(403, status(() -> AdminMaintenanceUtil.trimAudit(nobody, false, 1)));
		assertEquals(403, status(() -> AdminMaintenanceUtil.purgeAudit(nobody, false)));
		assertEquals(403, status(() -> AdminMaintenanceUtil.purgeAudit(nobody, true)));

		/// Scope resolution: each of the test org's administrators sees exactly its own organization; the
		/// /System administrator's all-organizations scope covers the test org, its parent and /System.
		for(BaseRecord orgAdmin : new BaseRecord[] { acct, sys }) {
			List<BaseRecord> own = AdminMaintenanceUtil.resolveScope(orgAdmin, false);
			assertEquals(1, own.size());
			assertEquals(o.getOrganizationId(), ((Number) own.get(0).get(FieldNames.FIELD_ID)).longValue());
		}
		List<BaseRecord> all = AdminMaintenanceUtil.resolveScope(sysOrg, true);
		assertTrue("all-organizations scope must list more than one organization, got " + all.size(), all.size() > 1);
		boolean sawSelf = false, sawDev = false, sawSystem = false;
		for(BaseRecord r : all) {
			String p = r.get(FieldNames.FIELD_PATH);
			if(ORG.equals(p)) sawSelf = true;
			if("/Development".equals(p)) sawDev = true;
			if(OrganizationContext.SYSTEM_ORGANIZATION.equals(p)) sawSystem = true;
		}
		assertTrue("scope must include " + ORG, sawSelf);
		assertTrue("scope must include /Development", sawDev);
		assertTrue("scope must include /System", sawSystem);

		/// describeScope: what the panel keys its scope control on.
		for(BaseRecord orgAdmin : new BaseRecord[] { acct, sys }) {
			Map<String, Object> d = AdminMaintenanceUtil.describeScope(orgAdmin);
			assertEquals(Boolean.FALSE, d.get("allOrganizationsAllowed"));
			assertEquals(ORG, d.get("organizationPath"));
			assertTrue("an organization's administrators get no organization list", ((List<?>) d.get("organizations")).isEmpty());
		}
		Map<String, Object> dSys = AdminMaintenanceUtil.describeScope(sysOrg);
		assertEquals(Boolean.TRUE, dSys.get("allOrganizationsAllowed"));
		assertEquals(OrganizationContext.SYSTEM_ORGANIZATION, dSys.get("organizationPath"));
		assertEquals(all.size(), ((List<?>) dSys.get("organizations")).size());
	}

	// ─────────────────────────────── thumbnails ───────────────────────────────

	@Test
	public void thumbnailWipeRemovesEveryThumbnailInTheOrganizationAndTheyRegenerate() throws Exception {
		OrganizationContext o = org();
		BaseRecord acct = acctAdmin(o);
		BaseRecord sys = systemOrgAdmin();

		String n1 = "am-thumb-" + UUID.randomUUID().toString().substring(0, 8) + ".png";
		String n2 = "am-thumb-" + UUID.randomUUID().toString().substring(0, 8) + ".png";
		BaseRecord img1 = imageWithThumbnail(acct, n1);
		imageWithThumbnail(acct, n2);

		long before = count(AdminMaintenanceUtil.countThumbnails(acct, false));
		assertTrue("expected at least the two thumbnails just created, got " + before, before >= 2);

		/// The /System administrator's all-organizations COUNT must be at least the single-org count.
		Map<String, Object> allCount = AdminMaintenanceUtil.countThumbnails(sys, true);
		assertEquals(AdminMaintenanceUtil.SCOPE_ALL, allCount.get("scope"));
		assertTrue(count(allCount) >= before);
		assertTrue(((List<?>) allCount.get("organizations")).size() > 1);

		Map<String, Object> wiped = AdminMaintenanceUtil.cleanupThumbnails(acct, false);
		assertEquals(AdminMaintenanceUtil.SCOPE_ORGANIZATION, wiped.get("scope"));
		assertEquals("wipe must report exactly what count reported", before, count(wiped));
		List<?> rows = (List<?>) wiped.get("organizations");
		assertEquals(1, rows.size());
		@SuppressWarnings("unchecked")
		Map<String, Object> row = (Map<String, Object>) rows.get(0);
		assertEquals(ORG, row.get("path"));
		assertEquals(before, ((Number) row.get("count")).longValue());

		assertEquals("nothing may remain after the wipe", 0L, count(AdminMaintenanceUtil.countThumbnails(acct, false)));

		/// Thumbnails are a derived cache: the next production read re-creates one.
		BaseRecord again = ThumbnailUtil.getCreateThumbnail(img1, 50, 50);
		assertNotNull("thumbnail must regenerate after the wipe", again);
		assertEquals((String) img1.get(FieldNames.FIELD_OBJECT_ID), (String) again.get(FieldNames.FIELD_REFERENCE_ID));
		assertEquals(1L, count(AdminMaintenanceUtil.countThumbnails(acct, false)));

		/// The wipe itself was journaled, as the caller, as a PERMIT.
		ioContext.getQueue().execute();
		AuditFilter f = new AuditFilter();
		f.text = "Wiped " + before + " thumbnail(s)";
		QueryResult qr = AdminMaintenanceUtil.listAudit(acct, false, f);
		assertTrue("the wipe must leave a maintenance audit", qr.getResults().length >= 1);
		assertEquals("PERMIT", str(qr.getResults()[0], FieldNames.FIELD_RESPONSE));
	}

	// ─────────────────────────────── audit ───────────────────────────────

	@Test
	public void auditListSearchViewTrimAndPurge() throws Exception {
		OrganizationContext o = org();
		BaseRecord acct = acctAdmin(o);
		BaseRecord sys = systemOrgAdmin();

		/// Start from a known-empty journal in THIS organization only.
		AdminMaintenanceUtil.purgeAudit(acct, false);
		ioContext.getQueue().execute();
		/// The purge leaves exactly its own journal entry behind - by design (see the util header).
		Map<String, Object> afterPurge = AdminMaintenanceUtil.countAudit(acct, false);
		assertEquals("a purge leaves only the record of the purge", 1L, count(afterPurge));

		String marker = "am-audit-" + UUID.randomUUID().toString().substring(0, 8);
		Date now = new Date();
		audit(o, acct, ActionEnumType.READ, ResponseEnumType.PERMIT, marker + " fresh read", now);
		audit(o, acct, ActionEnumType.MODIFY, ResponseEnumType.DENY, marker + " fresh deny", now);
		audit(o, acct, ActionEnumType.READ, ResponseEnumType.PERMIT, marker + " ten days old", new Date(now.getTime() - days(10)));
		audit(o, acct, ActionEnumType.DELETE, ResponseEnumType.DENY, marker + " forty days old", new Date(now.getTime() - days(40)));

		assertEquals(5L, count(AdminMaintenanceUtil.countAudit(acct, false)));

		/// List: newest first, paged, totalCount carried.
		AuditFilter all = new AuditFilter();
		all.recordCount = 2;
		QueryResult page1 = AdminMaintenanceUtil.listAudit(acct, false, all);
		assertEquals(2, page1.getResults().length);
		assertEquals("totalCount must reflect the whole journal, not the page", 5L, page1.getTotalCount());
		assertEquals(ModelNames.MODEL_AUDIT, page1.getResults()[0].getSchema());
		long t0 = ((Date) page1.getResults()[0].get(FieldNames.FIELD_CREATED_DATE)).getTime();
		long t1 = ((Date) page1.getResults()[1].get(FieldNames.FIELD_CREATED_DATE)).getTime();
		assertTrue("newest first", t0 >= t1);
		all.startRecord = 4;
		QueryResult page3 = AdminMaintenanceUtil.listAudit(acct, false, all);
		assertEquals(1, page3.getResults().length);
		assertTrue("the oldest record is last", str(page3.getResults()[0], FieldNames.FIELD_MESSAGE).contains("forty days old"));

		/// Search: by text, by response, by action, by date, combined.
		AuditFilter byText = new AuditFilter();
		byText.text = marker;
		assertEquals(4, AdminMaintenanceUtil.listAudit(acct, false, byText).getResults().length);
		AuditFilter deny = new AuditFilter();
		deny.text = marker;
		deny.response = "deny";
		QueryResult denies = AdminMaintenanceUtil.listAudit(acct, false, deny);
		assertEquals(2, denies.getResults().length);
		for(BaseRecord r : denies.getResults()) {
			assertEquals("DENY", str(r, FieldNames.FIELD_RESPONSE));
		}
		AuditFilter del = new AuditFilter();
		del.text = marker;
		del.action = "DELETE";
		assertEquals(1, AdminMaintenanceUtil.listAudit(acct, false, del).getResults().length);
		AuditFilter recent = new AuditFilter();
		recent.text = marker;
		recent.since = new Date(now.getTime() - days(1));
		assertEquals(2, AdminMaintenanceUtil.listAudit(acct, false, recent).getResults().length);
		AuditFilter window = new AuditFilter();
		window.text = marker;
		window.since = new Date(now.getTime() - days(20));
		window.until = new Date(now.getTime() - days(5));
		QueryResult inWindow = AdminMaintenanceUtil.listAudit(acct, false, window);
		assertEquals(1, inWindow.getResults().length);
		assertTrue(str(inWindow.getResults()[0], FieldNames.FIELD_MESSAGE).contains("ten days old"));
		AuditFilter bogus = new AuditFilter();
		bogus.action = "not-an-action";
		assertEquals("an unknown enum value is a 400, not an empty page", 400, status(() -> AdminMaintenanceUtil.listAudit(acct, false, bogus)));

		/// View: the detail resolves its references and is confined to the caller's organization.
		String oid = page3.getResults()[0].get(FieldNames.FIELD_OBJECT_ID);
		Map<String, Object> detail = AdminMaintenanceUtil.getAuditDetail(acct, false, oid);
		@SuppressWarnings("unchecked")
		Map<String, Object> rec = (Map<String, Object>) detail.get("audit");
		assertEquals(oid, rec.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("DELETE", rec.get(FieldNames.FIELD_ACTION));
		assertEquals("DENY", rec.get(FieldNames.FIELD_RESPONSE));
		assertTrue(rec.get(FieldNames.FIELD_CREATED_DATE) instanceof Number);
		assertEquals(ORG, detail.get("organizationPath"));
		@SuppressWarnings("unchecked")
		Map<String, Object> ctxUser = (Map<String, Object>) detail.get("contextUser");
		assertNotNull("contextUser must be resolved", ctxUser);
		assertEquals(Boolean.TRUE, ctxUser.get("found"));
		assertEquals(ACCT_ADMIN, ctxUser.get("name"));
		@SuppressWarnings("unchecked")
		Map<String, Object> subject = (Map<String, Object>) detail.get("subject");
		assertNotNull("subject (the actor) must be resolved", subject);
		assertEquals(ModelNames.MODEL_USER, subject.get("schema"));
		assertEquals(ACCT_ADMIN, subject.get("name"));
		assertNull("no resource was audited", detail.get("resource"));
		assertEquals(404, status(() -> AdminMaintenanceUtil.getAuditDetail(acct, false, UUID.randomUUID().toString())));

		/// A record in another organization is 404 to this org's administrator, but visible to the
		/// /System administrator in the all-organizations scope.
		OrganizationContext dev = getTestOrganization("/Development");
		BaseRecord devUser = getCreateUser(PLAIN, dev);
		assertNotNull(devUser);
		String devMarker = marker + "-dev";
		BaseRecord devAudit = audit(dev, devUser, ActionEnumType.READ, ResponseEnumType.PERMIT, devMarker, now);
		String devOid = devAudit.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("another org's record is not reachable by id in the org scope", 404, status(() -> AdminMaintenanceUtil.getAuditDetail(acct, false, devOid)));
		Map<String, Object> crossDetail = AdminMaintenanceUtil.getAuditDetail(sys, true, devOid);
		assertEquals("/Development", crossDetail.get("organizationPath"));
		AuditFilter crossList = new AuditFilter();
		crossList.text = devMarker;
		assertEquals("the org scope must not list another org's record", 0, AdminMaintenanceUtil.listAudit(acct, false, crossList).getResults().length);
		assertEquals("the all-organizations scope must", 1, AdminMaintenanceUtil.listAudit(sys, true, crossList).getResults().length);
		assertTrue(count(AdminMaintenanceUtil.countAudit(sys, true)) >= 6);

		/// Trim to the last 30 days: only the forty-day-old record goes; the trim is journaled.
		Map<String, Object> trimmed = AdminMaintenanceUtil.trimAudit(acct, false, 30);
		assertEquals(1L, count(trimmed));
		assertEquals(30, trimmed.get("days"));
		ioContext.getQueue().execute();
		AuditFilter stillThere = new AuditFilter();
		stillThere.text = marker;
		QueryResult remaining = AdminMaintenanceUtil.listAudit(acct, false, stillThere);
		assertEquals(3, remaining.getResults().length);
		for(BaseRecord r : remaining.getResults()) {
			assertFalse("the forty-day-old record must be gone", str(r, FieldNames.FIELD_MESSAGE).contains("forty days old"));
		}
		assertEquals(400, status(() -> AdminMaintenanceUtil.trimAudit(acct, false, 0)));
		assertEquals(400, status(() -> AdminMaintenanceUtil.trimAudit(acct, false, -5)));
		/// Trim to 5 days: the ten-day-old one goes too. Flush its journal entry before counting - the
		/// queue is asynchronous, so a maintenance entry enqueued by one command and flushed after the
		/// next would otherwise land beside the purge's own entry.
		assertEquals(1L, count(AdminMaintenanceUtil.trimAudit(acct, false, 5)));
		ioContext.getQueue().execute();

		/// Purge: everything in this organization, then only the purge's own entry remains; the other
		/// organization's record is untouched.
		long beforePurge = count(AdminMaintenanceUtil.countAudit(acct, false));
		assertTrue(beforePurge >= 3);
		Map<String, Object> purged = AdminMaintenanceUtil.purgeAudit(acct, false);
		assertEquals(beforePurge, count(purged));
		ioContext.getQueue().execute();
		assertEquals(1L, count(AdminMaintenanceUtil.countAudit(acct, false)));
		QueryResult last = AdminMaintenanceUtil.listAudit(acct, false, null);
		assertEquals(1, last.getResults().length);
		assertTrue(str(last.getResults()[0], FieldNames.FIELD_MESSAGE).contains("Purged " + beforePurge + " audit record(s)"));
		assertEquals("the purge must not reach the other organization", 1, AdminMaintenanceUtil.listAudit(sys, true, crossList).getResults().length);
	}

	@Test
	public void auditDetailRejectsABlankId() {
		OrganizationContext o = org();
		BaseRecord acct = acctAdmin(o);
		try {
			AdminMaintenanceUtil.getAuditDetail(acct, false, " ");
			fail("blank objectId must be refused");
		}
		catch(AdminMaintenanceException e) {
			assertEquals(400, e.getStatus());
		}
	}
}
