package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// I-2 (PictureBook V3 Implementation §6.C 226 a, §24): the optional `{ chatConfigName }` body on the three
/// render routes of PictureBookService, exercised through the real service methods -> PbServiceFacade ->
/// PbComposeJobUtil.resolveChatConfig -> real AccessPoint/PBAC -> the real (H2) database, with
/// HttpServletRequestMock + UserPrincipal and the two-SD-init-param ServletContext of
/// TestPictureBookRenderPlanRoute. Nothing is mocked and no actor is ever the admin or the olio principal.
///
///   POST /{book}/scene/{scene}/render        renderScene
///   POST /{book}/scene/{scene}/render-plan   renderPlanScene
///   POST /{book}/render-all                  renderAll
///
/// THE CONTRACT. The body is optional: the no-body callers these routes have always had keep their behaviour
/// (a null or blank body is an empty body and reaches the book gate); text that is not a JSON object is 400
/// "Malformed request body" before any lookup; a `chatConfigName` is resolved AS THE CALLER after the book and
/// scene gates - unknown is 404 "Chat config not found: <name>" (trimmed), blank is the same as absent - and
/// always before the workflow is created, so a refused name leaves no graph behind. The gate ORDER is the point:
/// a stranger's unknown name is still "Book not found" (the name is never consulted), an unknown scene with an
/// unknown name is still the scene's 404, and a scene-less book's render-all is still the 409.
///
/// WHAT THIS CANNOT COVER, AND WHERE IT IS COVERED. A world-less book on this harness cannot get its workflow
/// (TestPictureBookRenderPlanRoute's class comment), so a name that DOES resolve is pinned as the same loud 500
/// naming the workflow the no-body call gets - proof the resolution passed and the gate moved on - never a 200.
/// The run actually carrying `chatConfig {objectId, name}` through `/run/{id}` is the Playwright spec
/// e2e/pictureBookRenderChatConfig.spec.js (Docker stack, .42 + .39).
public class TestPictureBookRenderChatConfigRoutes extends BaseTest {

	private static final String OWNER_NAME = "pbrccowner";
	private static final String STRANGER_NAME = "pbrccstranger";
	private static final String GROUP_PATH = "~/PbRenderChatConfigRoutes";
	private static final String CHAT_PATH = "~/Chat";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String UNREACHABLE_SD = "http://127.0.0.1:1";
	private static final String SETTING = "a tide pool at the foot of a sea stack, gulls overhead";
	private static final String MISSING_CONFIG = "no-such-config-226a";
	private static final String MALFORMED = "Malformed request body";
	private static final String NOT_FOUND = "Book not found";
	private static final String NO_PRINCIPAL = "No authenticated principal";
	private static final String NO_SCENES = "This book has no scenes yet";

	private static final ServletContext SD_CONTEXT = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", UNREACHABLE_SD));

	private final PictureBookService service = new PictureBookService();

	private BaseRecord owner;
	private BaseRecord stranger;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		owner = getCreateUser(OWNER_NAME);
		stranger = getCreateUser(STRANGER_NAME);
		assertNotNull("owner user is null", owner);
		assertNotNull("stranger user is null", stranger);
		assertNotEquals("owner and stranger must be different users", oid(owner), oid(stranger));
	}

	// ─────────────────────────────── request / response helpers ───────────────────────────────

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPictureBookRenderChatConfigRoutes.class.getClassLoader(),
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
		return new HttpServletRequestMock(new UserPrincipal((String) u.get(FieldNames.FIELD_NAME), organizationPath));
	}

	private static String json(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2) {
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return JSONUtil.exportObject(m);
	}

	/// The three routes with a body, as a user.
	private Response renderScene(BaseRecord u, String bookOid, String sceneOid, String body) {
		return service.renderScene(bookOid, sceneOid, false, body, requestAs(u), SD_CONTEXT);
	}

	private Response renderPlan(BaseRecord u, String bookOid, String sceneOid, String body) {
		return service.renderPlanScene(bookOid, sceneOid, body, requestAs(u), SD_CONTEXT);
	}

	private Response renderAll(BaseRecord u, String bookOid, String body) {
		return service.renderAll(bookOid, body, requestAs(u), SD_CONTEXT);
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

	/// The error text of a refusal: the facade's {"error":"msg"} or the route's {"error":true,"message":"msg"}.
	private String errorText(Response r) {
		Map<String, Object> m = body(r);
		Object msg = m.get("message");
		if(msg == null) {
			msg = m.get("error");
		}
		assertNotNull("Refusal body must carry 'error' or 'message': " + r.getEntity(), msg);
		return String.valueOf(msg);
	}

	/// A refusal body never names the SD server (V3 §20: the deployment's URL is not the caller's business).
	private String refusal(Response r, int status, String what) {
		assertEquals(what + " must be " + status + ", body: " + r.getEntity(), status, r.getStatus());
		String text = errorText(r);
		assertFalse(what + " must not leak the SD server: " + text, text.contains("127.0.0.1"));
		assertFalse(what + " must not leak a URL: " + text, text.contains("http://"));
		return text;
	}

	private void refused(Response r, int status, String expectedText, String what) {
		assertEquals(what + ": message", expectedText, refusal(r, status, what));
	}

	/// The world-less harness answer once every gate has passed: a loud 500 naming the workflow (never a 200,
	/// never a leaked URL). Reaching it is the proof that whatever came BEFORE ensureWorkflow accepted the call.
	private void reachedTheWorkflowGate(Response r, String what) {
		String text = refusal(r, 500, what);
		assertTrue(what + ": the 500 names the workflow: " + text, text.toLowerCase().contains("workflow"));
	}

	private String oid(BaseRecord rec) {
		return (rec != null && rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A bare STORY book owned by the owner, in a group the owner owns, created through AccessPoint as that
	/// user and read back with the book projection (TestPictureBookRenderPlanRoute.createBareBook).
	private BaseRecord createBareBook() throws Exception {
		String slug = "rcc-" + UUID.randomUUID().toString().substring(0, 12);
		BaseRecord dir = ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, GROUP_PATH, "DATA", orgId);
		assertNotNull("Fixture group is null", dir);
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(owner, book, slug, GROUP_PATH, orgId);
		book.set(FieldNames.FIELD_NAME, slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, book);
		assertNotNull("Failed to create the bare book fixture", created);
		String bookOid = oid(created);
		assertNotNull("Created book carries no objectId", bookOid);
		BaseRecord read = PbBookUtil.readBook(owner, bookOid, orgId);
		assertNotNull("The created book must read back as its owner", read);
		return read;
	}

	/// A page of the book with a setting, written as a field-list patch (identity + name + setting) through
	/// AccessPoint as the owner, so a refusal is never the empty-text one.
	private BaseRecord addScene(BaseRecord book, int index, String setting) throws Exception {
		BaseRecord created = PbBookUtil.createScene(owner, book, index, "Page " + index, GROUP_PATH);
		assertNotNull("Failed to create page " + index, created);
		String sceneOid = oid(created);
		assertNotNull("Page carries no objectId", sceneOid);
		BaseRecord scene = PbBookUtil.readScene(owner, sceneOid, orgId);
		assertNotNull("Page must read back as its owner", scene);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SETTING });
		patch.set(FieldNames.FIELD_ID, scene.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, sceneOid);
		patch.set(FieldNames.FIELD_NAME, scene.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_SETTING, setting);
		assertNotNull("scene setting patch must persist", IOSystem.getActiveContext().getAccessPoint().update(owner, patch));
		scene = PbBookUtil.readScene(owner, sceneOid, orgId);
		assertNotNull("Patched page must read back", scene);
		assertEquals("the setting must be stored", setting, scene.get(OlioFieldNames.FIELD_PB_SETTING));
		return scene;
	}

	/// An olio.llm.chatConfig the OWNER owns in ~/Chat, created through AccessPoint as the owner, so
	/// ChatUtil.resolveConfig (organizationId + ownerId + name, no group) finds it by name as the owner and
	/// not as the stranger. It needs no connection: nothing dispatches an LLM call before the workflow gate.
	private BaseRecord createOwnerChatConfig(String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, CHAT_PATH);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, owner, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		cfg.set("stream", false);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, cfg);
		assertNotNull("Failed to create the owner's chat config " + name, created);
		BaseRecord resolved = ChatUtil.resolveConfig(owner, OlioModelNames.MODEL_CHAT_CONFIG, name, null);
		assertNotNull("precondition: the owner's chat config " + name + " must resolve by name as the owner", resolved);
		assertEquals("the resolved config is the one just created", oid(created), oid(resolved));
		return resolved;
	}

	/// Delete any workflow a case may have left (none is expected - see assertNoWorkflow), the scenes, the
	/// chat config, then the book - as the owner. Failures are logged, not asserted.
	private void cleanup(BaseRecord book, List<BaseRecord> scenes, BaseRecord chatConfig) {
		if(book != null) {
			try {
				BaseRecord wf = PbGraphUtil.findWorkflow(owner, book);
				if(wf != null) {
					logger.warn("cleanup: a workflow survived for book " + oid(book) + "; deleting it");
					if(!IOSystem.getActiveContext().getAccessPoint().delete(owner, wf)) {
						logger.error("cleanup: workflow " + oid(wf) + " was NOT deleted");
					}
				}
			}
			catch(Exception e) {
				logger.warn("cleanup: workflow lookup threw: " + e.getMessage());
			}
		}
		for(BaseRecord s : scenes) {
			try {
				if(s != null && !IOSystem.getActiveContext().getAccessPoint().delete(owner, s)) {
					logger.warn("cleanup: scene " + oid(s) + " was not deleted");
				}
			}
			catch(Exception e) {
				logger.warn("cleanup: scene delete threw: " + e.getMessage());
			}
		}
		if(chatConfig != null) {
			try {
				if(!IOSystem.getActiveContext().getAccessPoint().delete(owner, chatConfig)) {
					logger.warn("cleanup: chat config " + oid(chatConfig) + " was not deleted");
				}
			}
			catch(Exception e) {
				logger.warn("cleanup: chat config delete threw: " + e.getMessage());
			}
		}
		if(book != null) {
			try {
				if(!IOSystem.getActiveContext().getAccessPoint().delete(owner, book)) {
					logger.error("cleanup: failed to delete book " + oid(book));
				}
			}
			catch(Exception e) {
				logger.error("cleanup: book delete threw: " + e.getMessage());
			}
		}
	}

	/// A refusal before the workflow gate must leave no graph behind: the find-only lookup is null.
	private void assertNoWorkflow(BaseRecord book, String after) {
		assertNull(after + " must not have created a workflow", PbGraphUtil.findWorkflow(owner, book));
	}

	// ─────────────────────────────── the body itself ───────────────────────────────

	/// Text that is not a JSON object is the route's 400 before any lookup (NIL ids suffice); a null or blank
	/// body is an EMPTY body - the no-body callers' shape - and reaches the book gate exactly as before.
	@Test
	public void TestMalformedBodyIs400AndNoBodyIsUnchanged() {
		String bad = "[1,2]";
		refused(renderScene(owner, NIL_UUID, NIL_UUID, bad), 400, MALFORMED, "malformed scene render");
		refused(renderPlan(owner, NIL_UUID, NIL_UUID, bad), 400, MALFORMED, "malformed render-plan");
		refused(renderAll(owner, NIL_UUID, bad), 400, MALFORMED, "malformed render-all");
		refused(renderScene(owner, NIL_UUID, NIL_UUID, "not json"), 400, MALFORMED, "non-JSON scene render");

		refused(renderScene(owner, NIL_UUID, NIL_UUID, null), 404, NOT_FOUND, "scene render with no body");
		refused(renderPlan(owner, NIL_UUID, NIL_UUID, null), 404, NOT_FOUND, "render-plan with no body");
		refused(renderAll(owner, NIL_UUID, null), 404, NOT_FOUND, "render-all with no body");
		refused(renderScene(owner, NIL_UUID, NIL_UUID, ""), 404, NOT_FOUND, "scene render with a blank body");
		refused(renderPlan(owner, NIL_UUID, NIL_UUID, "  "), 404, NOT_FOUND, "render-plan with a whitespace body");
		refused(renderAll(owner, NIL_UUID, "{}"), 404, NOT_FOUND, "render-all with an empty object");
	}

	/// No principal is 401 on all three with a body that names a config: the principal gate precedes the name.
	@Test
	public void TestAnonymousWithAConfigNameIs401() {
		HttpServletRequestMock anon = new HttpServletRequestMock();
		String named = json("chatConfigName", MISSING_CONFIG);
		refused(service.renderScene(NIL_UUID, NIL_UUID, false, named, anon, SD_CONTEXT), 401, NO_PRINCIPAL, "anonymous scene render");
		refused(service.renderPlanScene(NIL_UUID, NIL_UUID, named, anon, SD_CONTEXT), 401, NO_PRINCIPAL, "anonymous render-plan");
		refused(service.renderAll(NIL_UUID, named, anon, SD_CONTEXT), 401, NO_PRINCIPAL, "anonymous render-all");
	}

	// ─────────────────────────────── the name, after the gates ───────────────────────────────

	/// An unknown name is 404 "Chat config not found: <name>" (trimmed) on all three routes, resolved only
	/// AFTER the book and scene gates and BEFORE the workflow: the owner's book gains no graph.
	@Test
	public void TestUnknownConfigIs404AfterTheSceneGatesAndLeavesNoWorkflow() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			scenes.add(addScene(book, 0, SETTING));
			String bookOid = oid(book);
			String sceneOid = oid(scenes.get(0));
			String cnf = "Chat config not found: " + MISSING_CONFIG;
			String named = json("chatConfigName", MISSING_CONFIG);
			String padded = json("chatConfigName", "  " + MISSING_CONFIG + " ");

			refused(renderScene(owner, bookOid, sceneOid, named), 404, cnf, "scene render with an unknown config");
			refused(renderPlan(owner, bookOid, sceneOid, named), 404, cnf, "render-plan with an unknown config");
			refused(renderAll(owner, bookOid, named), 404, cnf, "render-all with an unknown config");
			refused(renderScene(owner, bookOid, sceneOid, padded), 404, cnf, "scene render with a padded unknown config is trimmed");
			refused(renderPlan(owner, bookOid, sceneOid, padded), 404, cnf, "render-plan with a padded unknown config is trimmed");
			assertNoWorkflow(book, "the unknown-config 404s");

			/// the scene gate precedes the name: an unknown scene with an unknown config is the scene's 404
			String sceneMissing = "Scene " + NIL_UUID + " not found in this book";
			refused(renderScene(owner, bookOid, NIL_UUID, named), 404, sceneMissing, "unknown scene with an unknown config");
			refused(renderPlan(owner, bookOid, NIL_UUID, named), 404, sceneMissing, "unknown scene render-plan with an unknown config");
			assertNoWorkflow(book, "the unknown-scene 404s");
		}
		finally {
			cleanup(book, scenes, null);
		}
	}

	/// Render-all on a book with no scenes is the 409 whether or not the body names a config that does not
	/// exist: the scene check precedes the name, and nothing is written either way.
	@Test
	public void TestSceneLessRenderAllIs409BeforeTheConfig() throws Exception {
		BaseRecord book = createBareBook();
		try {
			String bookOid = oid(book);
			String text = refusal(renderAll(owner, bookOid, json("chatConfigName", MISSING_CONFIG)), 409, "scene-less render-all with an unknown config");
			assertTrue("the 409 is the no-scenes one: " + text, text.startsWith(NO_SCENES));
			assertFalse("the config name was never consulted: " + text, text.contains(MISSING_CONFIG));
			assertNoWorkflow(book, "the scene-less 409");
		}
		finally {
			cleanup(book, Collections.emptyList(), null);
		}
	}

	/// Another user in the organization naming a config - unknown or not - learns nothing: the book gate wins
	/// with 404 "Book not found" on all three, the name is never resolved, and the owner's book has no graph.
	@Test
	public void TestStrangerWithAConfigNameIs404BookNotFound() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			scenes.add(addScene(book, 0, SETTING));
			String bookOid = oid(book);
			String sceneOid = oid(scenes.get(0));
			String named = json("chatConfigName", MISSING_CONFIG);
			refused(renderScene(stranger, bookOid, sceneOid, named), 404, NOT_FOUND, "stranger scene render");
			refused(renderPlan(stranger, bookOid, sceneOid, named), 404, NOT_FOUND, "stranger render-plan");
			refused(renderAll(stranger, bookOid, named), 404, NOT_FOUND, "stranger render-all");
			assertNoWorkflow(book, "the stranger's 404s");
		}
		finally {
			cleanup(book, scenes, null);
		}
	}

	/// A name the caller CAN resolve - the owner's own config - passes the gate on all three routes: the call
	/// goes on to the workflow and gets this harness's loud 500 naming it, exactly as the no-body call does.
	/// A blank name is the same as absent (the user's default), not a 404. The stranger cannot resolve the
	/// owner's config by name either (owner-filtered lookup): still "Book not found".
	@Test
	public void TestOwnersConfigPassesTheGateAndBlankIsAbsent() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		BaseRecord cfg = null;
		try {
			scenes.add(addScene(book, 0, SETTING));
			String bookOid = oid(book);
			String sceneOid = oid(scenes.get(0));
			String name = "pbrcc-known-" + UUID.randomUUID().toString().substring(0, 8);
			cfg = createOwnerChatConfig(name);
			assertNull("precondition: the stranger must not resolve the owner's config by name",
				ChatUtil.resolveConfig(stranger, OlioModelNames.MODEL_CHAT_CONFIG, name, null));
			String known = json("chatConfigName", name);

			reachedTheWorkflowGate(renderScene(owner, bookOid, sceneOid, known), "scene render with the owner's config");
			reachedTheWorkflowGate(renderPlan(owner, bookOid, sceneOid, known), "render-plan with the owner's config");
			reachedTheWorkflowGate(renderAll(owner, bookOid, known), "render-all with the owner's config");
			reachedTheWorkflowGate(renderScene(owner, bookOid, sceneOid, json("chatConfigName", "   ")), "scene render with a blank config name");
			reachedTheWorkflowGate(renderPlan(owner, bookOid, sceneOid, null), "render-plan with no body");
			assertNoWorkflow(book, "the workflow 500s");

			refused(renderScene(stranger, bookOid, sceneOid, known), 404, NOT_FOUND, "stranger naming the owner's config");
			assertNoWorkflow(book, "the stranger's 404");
		}
		finally {
			cleanup(book, scenes, cfg);
		}
	}
}
