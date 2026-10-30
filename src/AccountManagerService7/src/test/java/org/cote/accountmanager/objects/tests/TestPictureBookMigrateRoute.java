package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// P7-4 (design §30 row 7): the transport contract of POST /picture-book/migrate-v1, the one call the
/// pre-V3 notice's Import button makes.
///
/// The body is {groupObjectId}. Until P7-4 the route parsed it through parseParams -> olio.pictureBookRequest,
/// which has no groupObjectId field, so the typed deserializer dropped the value and the documented body was
/// ALWAYS a 400 "groupObjectId is required" (first seen in the P7-4 Playwright spec, which sent exactly the
/// documented body). The route now reads the body as a plain map. These cases pin that: a parsed groupObjectId
/// must reach PbMigrationUtil.importV1Book, which is proven by its own 404s coming back through the route.
///
/// Exercised through the real PictureBookService method, the real Objects7 utilities and the real test
/// database, with the HttpServletRequestMock + UserPrincipal pattern of TestPbRenderPathRefusal. Nothing is
/// mocked. The 200 path (PbBookUtil.createBook needs an Olio datagen path this harness does not have, per
/// TestChapBookServiceRenderPath) is covered by Objects7 TestPbMigration (direct) and the Ux752 Playwright
/// spec e2e/pictureBookV3Import.spec.js (real Tomcat).
public class TestPictureBookMigrateRoute extends BaseTest {

	private static final String USER_NAME = "pbmigrateuser";
	private static final String STRANGER_NAME = "pbmigratestranger";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	/// No datagen.path: every case here fails before importV1Book reaches createBook.
	private static final ServletContext NO_DATAGEN = initParams(Collections.emptyMap());

	private final PictureBookService service = new PictureBookService();

	private BaseRecord user;
	private BaseRecord stranger;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		user = getCreateUser(USER_NAME);
		stranger = getCreateUser(STRANGER_NAME);
		assertNotNull("Test user is null", user);
		assertNotNull("Stranger user is null", stranger);
		assertNotEquals("owner and stranger must be different users", user.get(FieldNames.FIELD_ID), stranger.get(FieldNames.FIELD_ID));
	}

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPictureBookMigrateRoute.class.getClassLoader(),
			new Class<?>[] { ServletContext.class },
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
			}
		);
	}

	private HttpServletRequestMock requestAs(BaseRecord u) {
		return new HttpServletRequestMock(new UserPrincipal((String)u.get(FieldNames.FIELD_NAME), organizationPath));
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

	private Response migrate(BaseRecord asUser, String json) {
		return service.migrateV1Book(json, requestAs(asUser), NO_DATAGEN);
	}

	private String error(Response r) {
		return String.valueOf(body(r).get("error"));
	}

	private void assert400(String what, Response r, String fragment) {
		assertEquals(what + " must be 400", 400, r.getStatus());
		String msg = error(r);
		assertTrue(what + " must say '" + fragment + "': " + msg, msg.contains(fragment));
	}

	/// The PB1 storage shape without its .pictureBookMeta note: ~/Data/PictureBooks/<book>/Scenes/<scene note>,
	/// owned by the test user, created through AccessPoint as that user.
	private static final class Pb1Book {
		BaseRecord bookGroup;
		BaseRecord scenesGroup;
		BaseRecord scene;
		String bookGroupOid;
		String name;
	}

	private Pb1Book createPb1BookWithoutMeta() throws Exception {
		Pb1Book b = new Pb1Book();
		b.name = "p74-" + UUID.randomUUID().toString().substring(0, 8);
		String path = "~/Data/PictureBooks/" + b.name;
		b.bookGroup = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("book group " + path, b.bookGroup);
		b.bookGroupOid = b.bookGroup.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("book group objectId", b.bookGroupOid);
		b.scenesGroup = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path + "/Scenes", GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Scenes group", b.scenesGroup);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path + "/Scenes");
		plist.parameter(FieldNames.FIELD_NAME, "Scene 1");
		BaseRecord note = ioContext.getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set("text", "{\"index\":0,\"title\":\"Scene 1\",\"setting\":\"a quiet room\",\"action\":\"nothing happens\",\"mood\":\"calm\"}");
		b.scene = IOSystem.getActiveContext().getAccessPoint().create(user, note);
		assertNotNull("scene note", b.scene);
		return b;
	}

	private void deletePb1Book(Pb1Book b) {
		if(b == null) return;
		IOSystem.getActiveContext().getAccessPoint().delete(user, b.scene);
		IOSystem.getActiveContext().getAccessPoint().delete(user, b.scenesGroup);
		IOSystem.getActiveContext().getAccessPoint().delete(user, b.bookGroup);
	}

	/// ------------------------------------------------------------------------------------------

	/// No principal: 401 before the body is looked at.
	@Test
	public void TestAnonymousIs401() {
		Response r = service.migrateV1Book("{\"groupObjectId\":\"" + NIL_UUID + "\"}", new HttpServletRequestMock(), NO_DATAGEN);
		assertEquals("anonymous must be 401", 401, r.getStatus());
	}

	/// A missing or blank body is refused with the documented shape, before any lookup.
	@Test
	public void TestMissingBodyIs400() {
		assert400("null body", migrate(user, null), "Request body required");
		assert400("blank body", migrate(user, "   "), "Request body required");
	}

	/// A body that is not a JSON object is refused as malformed, not reported as a missing field.
	@Test
	public void TestMalformedBodyIs400() {
		assert400("non-JSON body", migrate(user, "not json"), "Malformed request body");
		assert400("JSON array body", migrate(user, "[\"" + NIL_UUID + "\"]"), "Malformed request body");
	}

	/// An object body without a usable groupObjectId is the field-level 400.
	@Test
	public void TestMissingGroupObjectIdIs400() {
		assert400("other keys only", migrate(user, "{\"foo\":\"bar\"}"), "groupObjectId is required");
		assert400("empty groupObjectId", migrate(user, "{\"groupObjectId\":\"\"}"), "groupObjectId is required");
		assert400("blank groupObjectId", migrate(user, "{\"groupObjectId\":\"   \"}"), "groupObjectId is required");
		assert400("non-string groupObjectId", migrate(user, "{\"groupObjectId\":42}"), "groupObjectId is required");
	}

	/// The documented body reaches importV1Book: an unknown group is ITS 404, not the route's 400. This is the
	/// case that failed before P7-4 (the value never survived the typed parse).
	@Test
	public void TestUnknownGroupIs404FromImport() {
		Response r = migrate(user, "{\"groupObjectId\":\"" + NIL_UUID + "\"}");
		assertEquals("unknown group must be 404", 404, r.getStatus());
		String msg = error(r);
		assertTrue("404 must come from importV1Book and name the group: " + msg,
			msg.contains("PB1 book group '" + NIL_UUID + "' not found or not accessible"));
	}

	/// A real PB1 book group the caller owns, with a scene but no .pictureBookMeta: the lookup succeeds and the
	/// next step's 404 comes back - the full PB1-side path up to the point where a meta note would be read.
	/// The same group is invisible to a stranger (AccessPoint.find denies), so the stranger gets the group 404
	/// and PB1 is never read on their behalf.
	@Test
	public void TestOwnedGroupWithoutMetaIs404AndStrangerCannotSeeIt() throws Exception {
		Pb1Book b = createPb1BookWithoutMeta();
		try {
			String json = "{\"groupObjectId\":\"" + b.bookGroupOid + "\"}";

			Response owner = migrate(user, json);
			assertEquals("owner without meta must be 404", 404, owner.getStatus());
			String msg = error(owner);
			assertTrue("owner 404 must be the meta-note miss: " + msg, msg.contains(".pictureBookMeta"));
			assertTrue("owner 404 must name the PB1 book: " + msg, msg.contains(b.name));

			Response other = migrate(stranger, json);
			assertEquals("stranger must be 404", 404, other.getStatus());
			String omsg = error(other);
			assertTrue("stranger 404 must be the group miss, not the meta miss: " + omsg,
				omsg.contains("not found or not accessible") && !omsg.contains(".pictureBookMeta"));
		}
		finally {
			deletePb1Book(b);
		}
	}
}
