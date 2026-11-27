package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.AdminMaintenanceUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.SystemCheckUtil;
import org.cote.rest.services.SystemCheckService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// Component test for the SystemCheckService REST shim (/rest/system/check): the real service methods, the
/// real SystemCheckUtil, the real (H2) database, through the HttpServletRequestMock + UserPrincipal pattern
/// of TestAdminService. Calling the service in-process does NOT run the container's @RolesAllowed - that is
/// verified against the live stack - so what this file pins is the status mapping (200/400/403/404/409), the
/// names-only summary, and URL redaction for a non-/System administrator.
///
/// Actors: ssSvcAdmin (AccountAdministrators of /Development/ServiceStatus), ssSvcPlain (no admin role),
/// ssSvcSysAdmin (AccountAdministrators of /System, credential-less). Never the built-in admin as a subject.
public class TestSystemCheckService extends BaseTest {

	private static final String ORG = "/Development/ServiceStatus";
	private static final String ADMIN = "ssSvcAdmin";
	private static final String PLAIN = "ssSvcPlain";
	private static final String SYS_ADMIN = "ssSvcSysAdmin";
	private static final String CLOSED_URL = "http://127.0.0.1:9";
	private static final String BLACKHOLE_URL = "http://192.0.2.1:11434";

	private final SystemCheckService service = new SystemCheckService();
	private final ServletContext context = initParams(Map.of("sd.server", CLOSED_URL));

	private OrganizationContext org;
	private BaseRecord admin;
	private BaseRecord plain;
	private BaseRecord sysAdmin;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		SystemCheckUtil.invalidate();
		org = getTestOrganization(ORG);
		assertNotNull(org);
		assertTrue(org.isInitialized());
		admin = withRole(org, ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
		assertFalse(AdminMaintenanceUtil.isSystemOrganizationAdministrator(admin, org.getOrganizationId()));
		plain = getCreateUser(PLAIN, org);
		assertNotNull(plain);
		assertFalse(AdminMaintenanceUtil.isAdministrator(plain, org.getOrganizationId()));
		OrganizationContext sys = ioContext.getOrganizationContext(OrganizationContext.SYSTEM_ORGANIZATION, OrganizationEnumType.SYSTEM);
		assertNotNull(sys);
		assertTrue(sys.isInitialized());
		sysAdmin = withRole(sys, SYS_ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
		assertTrue(AdminMaintenanceUtil.isSystemOrganizationAdministrator(sysAdmin, sys.getOrganizationId()));
	}

	private BaseRecord withRole(OrganizationContext o, String userName, String roleName) {
		BaseRecord u = getCreateUser(userName, o);
		assertNotNull("Failed to resolve " + userName, u);
		BaseRecord role = AccessSchema.getSystemRole(roleName, RoleEnumType.USER.toString(), o.getOrganizationId());
		assertNotNull(role);
		ioContext.getMemberUtil().member(o.getAdminUser(), role, u, null, true);
		assertTrue(ioContext.getMemberUtil().isMember(u, role, null));
		return u;
	}

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(TestSystemCheckService.class.getClassLoader(), new Class<?>[] { ServletContext.class },
			(proxy, method, args) -> {
				if("getInitParameter".equals(method.getName()) && args != null && args.length == 1) {
					return params.get(String.valueOf(args[0]));
				}
				Class<?> rt = method.getReturnType();
				if(rt == boolean.class) return false;
				if(rt == int.class) return 0;
				if(rt == long.class) return 0L;
				if(rt.equals(java.util.Enumeration.class)) return Collections.emptyEnumeration();
				return null;
			});
	}

	private HttpServletRequestMock requestAs(BaseRecord user) {
		String orgPath = (user == sysAdmin ? OrganizationContext.SYSTEM_ORGANIZATION : ORG);
		return new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), orgPath));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> body(Response r) {
		assertNotNull(r);
		assertNotNull(r.getEntity());
		Map<String, Object> m = JSONUtil.importObject(r.getEntity().toString(), LinkedHashMap.class);
		assertNotNull("not JSON: " + r.getEntity(), m);
		return m;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> check(Map<String, Object> rep, String name) {
		for(Object o : (List<Object>) rep.get("checks")) {
			Map<String, Object> c = (Map<String, Object>) o;
			if(name.equals(c.get("name"))) {
				return c;
			}
		}
		return null;
	}

	private BaseRecord connection(BaseRecord user, String name, String url) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord existing = ioContext.getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_CONNECTION, ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Chat", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID)).get(FieldNames.FIELD_OBJECT_ID), name);
		if(existing != null) {
			return existing;
		}
		BaseRecord c = ioContext.getFactory().newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
		c.set("serverUrl", url);
		c.set(FieldNames.FIELD_DIALECT, ConnectionDialectEnumType.OLLAMA);
		BaseRecord created = ioContext.getAccessPoint().create(user, c);
		assertNotNull("create connection " + name, created);
		return created;
	}

	@Test
	public void TestSummaryIsOpenAndNamesOnly() {
		Response r = service.summary(requestAs(plain), context);
		assertEquals(200, r.getStatus());
		String raw = r.getEntity().toString();
		Map<String, Object> m = body(r);
		assertEquals("exactly status, degraded, checkedAt: " + raw, 3, m.size());
		for(Object n : (List<?>) m.get("degraded")) {
			assertTrue("names come only from the fixed set: " + n, SystemCheckUtil.SUMMARY_NAMES.contains(n));
		}
		assertTrue("sd on a closed port is degraded: " + raw, ((List<?>) m.get("degraded")).contains("sd"));
		assertFalse("no '://' in the summary: " + raw, raw.contains("://"));
	}

	@Test
	public void TestPlainUserGets403() {
		assertEquals(403, service.last(requestAs(plain), context).getStatus());
		assertEquals(403, service.runCheck(false, requestAs(plain), context).getStatus());
		assertEquals(403, service.connections(requestAs(plain)).getStatus());
		assertEquals(403, service.testConnection(UUID.randomUUID().toString(), false, requestAs(plain)).getStatus());
		assertEquals(403, service.testServer("db", false, requestAs(plain), context).getStatus());
		assertEquals(401, service.last(new HttpServletRequestMock(), context).getStatus());
	}

	@Test
	public void TestStatusMapping() throws Exception {
		Response r = service.last(requestAs(admin), context);
		assertEquals(200, r.getStatus());
		Map<String, Object> rep = body(r);
		assertNotNull(rep.get("checks"));
		assertNotNull(rep.get("connections"));
		assertEquals("ok", check(rep, "db").get("status"));

		assertEquals(200, service.runCheck(false, requestAs(admin), context).getStatus());
		assertEquals(200, service.connections(requestAs(admin)).getStatus());
		assertEquals(400, service.testServer("not-a-check", false, requestAs(admin), context).getStatus());
		assertEquals(403, service.testServer("vectors", false, requestAs(admin), context).getStatus());
		Response db = service.testServer("db", false, requestAs(admin), context);
		assertEquals(200, db.getStatus());
		assertEquals("db", body(db).get("name"));
		assertEquals(404, service.testConnection(UUID.randomUUID().toString(), false, requestAs(admin)).getStatus());

		BaseRecord closed = connection(admin, "ss-svc-closed", CLOSED_URL);
		Response cr = service.testConnection(closed.get(FieldNames.FIELD_OBJECT_ID), false, requestAs(admin));
		assertEquals(200, cr.getStatus());
		Map<String, Object> row = body(cr);
		assertEquals("fail", row.get("status"));
		assertEquals("unreachable", row.get("reason"));
		assertFalse(cr.getEntity().toString().contains("://"));
	}

	@Test
	public void TestConcurrentDeepIs409() throws Exception {
		BaseRecord hole = connection(admin, "ss-svc-black-hole", BLACKHOLE_URL);
		BaseRecord closed = connection(admin, "ss-svc-closed", CLOSED_URL);
		AtomicReference<Response> first = new AtomicReference<>();
		Thread t = new Thread(() -> first.set(service.testConnection(hole.get(FieldNames.FIELD_OBJECT_ID), true, requestAs(admin))));
		t.start();
		long wait = System.currentTimeMillis() + 10000L;
		while(!SystemCheckUtil.isDeepChatBusy() && System.currentTimeMillis() < wait) {
			Thread.sleep(20L);
		}
		assertTrue("the first deep test must be running", SystemCheckUtil.isDeepChatBusy());
		assertEquals(409, service.testConnection(closed.get(FieldNames.FIELD_OBJECT_ID), true, requestAs(admin)).getStatus());
		assertEquals(409, service.runCheck(true, requestAs(admin), context).getStatus());
		t.join(60000L);
		assertNotNull(first.get());
		assertEquals(200, first.get().getStatus());
		long until = System.currentTimeMillis() + 40000L;
		while(SystemCheckUtil.isDeepChatBusy() && System.currentTimeMillis() < until) {
			Thread.sleep(100L);
		}
		assertFalse(SystemCheckUtil.isDeepChatBusy());
	}

	@Test
	public void TestUrlRedactedForNonSystemAdmin() {
		Map<String, Object> rep = body(service.runCheck(false, requestAs(admin), context));
		Map<String, Object> sd = check(rep, "sd");
		assertNotNull(sd);
		assertEquals(Boolean.TRUE, sd.get("urlRedacted"));
		assertNull(((Map<?, ?>) sd.get("target")).get("url"));
		assertNull("N1: no vectors row for a non-/System admin", check(rep, "vectors"));
		Response one = service.testServer("sd", false, requestAs(admin), context);
		assertEquals(Boolean.TRUE, body(one).get("urlRedacted"));
		assertFalse(one.getEntity().toString().contains("://"));

		Map<String, Object> srep = body(service.last(requestAs(sysAdmin), context));
		Map<String, Object> ssd = check(srep, "sd");
		assertEquals(Boolean.FALSE, ssd.get("urlRedacted"));
		assertEquals(CLOSED_URL, ((Map<?, ?>) ssd.get("target")).get("url"));
		assertNotNull("a /System admin gets the vectors row", check(srep, "vectors"));
	}
}
