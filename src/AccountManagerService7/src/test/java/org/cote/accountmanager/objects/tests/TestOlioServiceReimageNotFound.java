package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.OlioService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// PF-8 (PictureBookV3Implementation §9): POST /olio/{type}/{objectId}/reimage for a charPerson the
/// caller cannot read (or that does not exist) is a 404 with a JSON error body. Before the fix the
/// route dereferenced the null find result (a1.get("profile.portrait") / generateSDImages on a null
/// list element) and the client saw a 500 with a stack trace.
///
/// Exercised through the real OlioService method with Service7's own HttpServletRequestMock +
/// UserPrincipal and a ServletContext answering only the SD init-params the route reads before the
/// find; the SD server is a real SDUtil pointed at a closed loopback port, which must never be
/// contacted on this path. The find is the real AccessPoint.find as the test user on the real (H2)
/// database. The positive path (an existing character, a live SD render) is bugfix3-live.spec.js:211
/// against the Docker stack.
public class TestOlioServiceReimageNotFound extends BaseTest {

	private static final String USER_NAME = "reimagenotfounduser";
	private static final String NO_SUCH_OBJECT_ID = "00000000-0000-0000-0000-000000000000";
	private static final ServletContext SD_CONTEXT = initParams(Map.of(
		"sd.server.apiType", "SWARM",
		"sd.server", "http://127.0.0.1:1",
		"sd.model", "no-such-model.safetensors",
		"sd.refinerModel", "no-such-refiner.safetensors"
	));

	private final OlioService service = new OlioService();
	private BaseRecord user;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		user = getCreateUser(USER_NAME);
		assertNotNull("Test user is null", user);
		injectContext(service, SD_CONTEXT);
	}

	/// OlioService receives its ServletContext by @Context field injection (package-private field
	/// `context`); outside Jersey the test sets it the same way the container would.
	private static void injectContext(OlioService svc, ServletContext ctx) {
		try {
			Field f = OlioService.class.getDeclaredField("context");
			f.setAccessible(true);
			f.set(svc, ctx);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("OlioService.context field not injectable", e);
		}
	}

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestOlioServiceReimageNotFound.class.getClassLoader(),
			new Class<?>[] { ServletContext.class },
			(proxy, method, args) -> {
				if ("getInitParameter".equals(method.getName()) && args != null && args.length == 1) {
					return params.get(String.valueOf(args[0]));
				}
				Class<?> rt = method.getReturnType();
				if (rt == boolean.class) return false;
				if (rt == int.class) return 0;
				if (rt == long.class) return 0L;
				if (rt.equals(java.util.Enumeration.class)) return Collections.emptyEnumeration();
				return null;
			}
		);
	}

	private HttpServletRequestMock requestAs(BaseRecord u) {
		return new HttpServletRequestMock(new UserPrincipal((String) u.get(FieldNames.FIELD_NAME), organizationPath));
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

	/// ------------------------------------------------------------------------------------------

	/// A reimage of a charPerson objectId that does not exist is 404 with the documented error body -
	/// no exception, no SD call, no Olio context initialization (the harness has no datagen path, so
	/// reaching resolveOlioContext would itself fail rather than 404).
	@Test
	public void TestReimageOfUnknownCharPersonIs404() {
		String json = "{\"schema\":\"olio.sd.config\",\"imageAction\":\"portrait\"}";
		Response r = service.reimageWithConfig(json, OlioModelNames.MODEL_CHAR_PERSON, NO_SUCH_OBJECT_ID, requestAs(user), new HttpServletResponseMock());
		assertEquals("reimage of an unknown character must be 404, body=" + r.getEntity(), 404, r.getStatus());
		assertEquals("Character not found or not readable", String.valueOf(body(r).get("error")));
	}
}
