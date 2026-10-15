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
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// P2-5 (PictureBook V3 §12, §16, §20): the plan-executor route
/// POST /olio/pictureBook/{book}/scene/{scene}/render-plan on PictureBookService, exercised through the real
/// service method -> PbServiceFacade.requestRenderPlanScene -> real AccessPoint/PBAC -> the real (H2) database,
/// with HttpServletRequestMock + UserPrincipal and a ServletContext that carries exactly the two SD init-params
/// the route reads (TestPictureBookPlanRoute). Nothing is mocked and no actor is ever the admin or olio
/// principal.
///
/// FIXTURE. A bare STORY olio.pb.book the owner created through AccessPoint in a group it owns, plus a page
/// from PbBookUtil.createScene as that user (TestPictureBookPlanRoute.createBareBook / addScene).
///
/// WHAT THIS CANNOT COVER, AND WHERE IT IS COVERED. Unlike the plan inspector, the executor WRITES: it needs
/// the book's /Olio/Universes/Books/Worlds/<slug>/Workflow group, which only PbBookUtil.createBook's Olio
/// world initialization builds, and a plain user cannot create the top-level /Olio node on this harness
/// (TestChapBookServiceRenderPath, measured 2026-10-11). So every refusal that precedes the workflow - 401,
/// the three 404s, the v1 409 and the unrecognised-API-type 500 - is pinned here against the real route, and
/// the world-less SD-backed call is pinned as a loud 500 naming the workflow, never a 200 and never a graph
/// row. The contracts BEYOND the workflow - 503 INVENTORY_UNAVAILABLE, 409 NO_CANDIDATE, 409 empty text,
/// MODEL_SUBSTITUTED still rendering, and the 200 with its base artifact - are TestPbRenderPlanExecutor
/// (Objects7, am7db, real createBook, Swarm .39), and the 200 on the Docker stack is the Playwright spec
/// e2e/pictureBookV3RenderPlan.spec.js.
///
/// The render-path switch is process-global; the one case that flips it restores it in finally.
public class TestPictureBookRenderPlanRoute extends BaseTest {

	private static final String OWNER_NAME = "pbrenderplanowner";
	private static final String STRANGER_NAME = "pbrenderplanstranger";
	private static final String GROUP_PATH = "~/PbRenderPlanRoute";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String UNREACHABLE_SD = "http://127.0.0.1:1";
	private static final String SETTING = "a lighthouse on a basalt headland at dusk";

	private static final ServletContext SD_CONTEXT = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", UNREACHABLE_SD));
	private static final ServletContext BAD_API_CONTEXT = initParams(Map.of("sd.server.apiType", "nonsense", "sd.server", UNREACHABLE_SD));

	private final PictureBookService service = new PictureBookService();

	private BaseRecord owner;
	private BaseRecord stranger;
	private long orgId;
	/// This harness boots on the v1 default (Objects7's BaseTest pins v3; Service7's does not), so every case
	/// pins v3 itself and the switch is restored to what the harness had (TestChapBookServiceRenderPath).
	private String pb0;
	private String cb0;

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
		pb0 = PbRenderPath.pictureBook();
		cb0 = PbRenderPath.chapBook();
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
	}

	@After
	public void restoreSwitch() {
		PbRenderPath.configure(pb0, cb0);
	}

	// ─────────────────────────────── request / response helpers ───────────────────────────────

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPictureBookRenderPlanRoute.class.getClassLoader(),
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

	private Response render(BaseRecord u, String bookOid, String sceneOid) {
		return service.renderPlanScene(bookOid, sceneOid, requestAs(u), SD_CONTEXT);
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

	private String oid(BaseRecord rec) {
		return (rec != null && rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A bare STORY book owned by the owner, in a group the owner owns, created through AccessPoint as that
	/// user and read back with the book projection.
	private BaseRecord createBareBook() throws Exception {
		String slug = "rpl-" + UUID.randomUUID().toString().substring(0, 12);
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

	/// Delete any workflow a case may have left (none is expected - see assertNoWorkflow), the scenes, then
	/// the book - as the owner. Failures are logged, not asserted, so a teardown problem never masks the case.
	private void cleanup(BaseRecord book, List<BaseRecord> scenes) {
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

	// ─────────────────────────────── refusals before the workflow ───────────────────────────────

	/// No principal is 401 before any lookup, on the real route.
	@Test
	public void TestAnonymousIs401() {
		Response r = service.renderPlanScene(NIL_UUID, NIL_UUID, new HttpServletRequestMock(), SD_CONTEXT);
		assertEquals("anonymous must be 401, body: " + r.getEntity(), 401, r.getStatus());
	}

	/// A book objectId that does not exist is 404 "Book not found" (requireBook).
	@Test
	public void TestUnknownBookIs404() {
		Response r = render(owner, NIL_UUID, NIL_UUID);
		assertEquals("Book not found", refusal(r, 404, "unknown book"));
	}

	/// A scene objectId that does not exist is 404 naming the scene (requireSceneOfBook), and nothing was
	/// written for the book.
	@Test
	public void TestUnknownSceneIs404() throws Exception {
		BaseRecord book = createBareBook();
		try {
			Response r = render(owner, oid(book), NIL_UUID);
			assertEquals("Scene " + NIL_UUID + " not found in this book", refusal(r, 404, "unknown scene"));
			assertNoWorkflow(book, "the unknown-scene 404");
		}
		finally {
			cleanup(book, Collections.emptyList());
		}
	}

	/// Another user in the same organization cannot render the owner's scene: the book is not readable to
	/// them, so it is 404 "Book not found" - indistinguishable from a book that does not exist - and the
	/// owner's book gains no workflow.
	@Test
	public void TestStrangerIs404() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			scenes.add(addScene(book, 0, SETTING));
			Response r = render(stranger, oid(book), oid(scenes.get(0)));
			assertEquals("Book not found", refusal(r, 404, "stranger"));
			assertNoWorkflow(book, "the stranger's 404");
		}
		finally {
			cleanup(book, scenes);
		}
	}

	/// With the PictureBook render path pinned to v1 the executor is refused with the v1 409 before the
	/// workflow is touched (PbRenderPath.requireV3). Process-global switch, re-pinned to v3 in finally.
	@Test
	public void TestV1IsRefusedBeforeAnyGraphWork() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			scenes.add(addScene(book, 0, SETTING));
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3);
			Response r;
			try {
				r = render(owner, oid(book), oid(scenes.get(0)));
			}
			finally {
				PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
			}
			String text = refusal(r, 409, "v1 render path");
			assertTrue("the v1 refusal names the render path: " + text, text.startsWith(PbRenderPath.REFUSAL_PREFIX));
			assertNoWorkflow(book, "the v1 409");
		}
		finally {
			cleanup(book, scenes);
		}
	}

	/// An sd.server.apiType the deployment misspelled is the same 500 planScene gives - reported before any
	/// book lookup and never as a raw IllegalArgumentException - and writes nothing.
	@Test
	public void TestUnrecognisedApiTypeIs500() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			scenes.add(addScene(book, 0, SETTING));
			Response r = service.renderPlanScene(oid(book), oid(scenes.get(0)), requestAs(owner), BAD_API_CONTEXT);
			assertEquals("SD server API type is not recognised", refusal(r, 500, "unrecognised API type"));
			assertNoWorkflow(book, "the API-type 500");
		}
		finally {
			cleanup(book, scenes);
		}
	}

	// ─────────────────────────────── the workflow gate on this harness ───────────────────────────────

	/// The first write the executor needs is the book's workflow under /Olio/Universes/Books/Worlds/<slug>,
	/// which a world-less book on this harness cannot get (see the class comment). The contract pinned is
	/// that the route is then a loud 500 naming the workflow - never a 200, never a half-created graph,
	/// never a leaked server URL. The same call on a real book is TestPbRenderPlanExecutor.
	@Test
	public void TestWorldLessBookIsALoud500NamingTheWorkflow() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			scenes.add(addScene(book, 0, SETTING));
			Response r = render(owner, oid(book), oid(scenes.get(0)));
			String text = refusal(r, 500, "world-less book");
			logger.info("MEASURED: world-less render-plan refusal: " + r.getStatus() + " " + text);
			assertTrue("the 500 names the workflow: " + text, text.toLowerCase().contains("workflow"));
			assertNoWorkflow(book, "the workflow 500");
		}
		finally {
			cleanup(book, scenes);
		}
	}
}
