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

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SdxlCompiler;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbStyleUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// P2-4 (PictureBook V3 §12-§15, §18-§20): the plan-inspector route
/// POST /olio/pictureBook/{book}/scene/{scene}/plan on PictureBookService, exercised through the real
/// service method -> PbServiceFacade.planScene -> PbRenderPlanUtil.buildScenePlan -> real AccessPoint/PBAC ->
/// the real (H2) database, with HttpServletRequestMock + UserPrincipal and a ServletContext that carries
/// exactly the two SD init-params the route reads (TestChapBookServiceRenderPath). Nothing is mocked and no
/// actor is ever the admin or olio principal.
///
/// FIXTURE. A bare STORY olio.pb.book the owner created through AccessPoint in a group it owns
/// (TestPictureBookStyleRoutes.createBareBook) plus pages from PbBookUtil.createScene as that user. The
/// plan is a read: it needs no workflow, node or Olio world, so every refusal and the 200 are reachable here.
/// The 409 "partial style chain" refusal is reached the way TestPbStyleResolver.case07 reaches a truncation:
/// the stranger's style is granted to the owner long enough to become the own style's parent, then revoked
/// (TestPartialStyleChainIs409). No world is needed.
///
/// SD. `sd.server` is pinned to a closed loopback port (127.0.0.1:1) so the installed-model list cannot be
/// read: every plan built here therefore carries a MODEL_SUBSTITUTED degradation whose message names
/// INVENTORY_UNAVAILABLE, the location pick has no model, and the plan still builds and compiles (§19/§20).
/// That is the contract under test, not a statement about any real SD server. A plan against a live Swarm
/// is TestRenderPlanLive (Objects7, .39).
public class TestPictureBookPlanRoute extends BaseTest {

	private static final String OWNER_NAME = "pbplanowner";
	private static final String STRANGER_NAME = "pbplanstranger";
	private static final String GROUP_PATH = "~/PbPlanRoute";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String UNREACHABLE_SD = "http://127.0.0.1:1";
	private static final String SETTING = "a lighthouse on a basalt headland at dusk";
	private static final String OVERRIDE = "a lantern-lit harbour at midnight";
	private static final String STYLE_TEXT = "ink wash on rice paper";
	private static final String STRANGER_STYLE_PATH = "~/PbPlanRouteStranger";
	private static final String STRANGER_STYLE_TEXT = "gouache on grey card";

	private static final ServletContext SD_CONTEXT = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", UNREACHABLE_SD));
	private static final ServletContext BAD_API_CONTEXT = initParams(Map.of("sd.server.apiType", "nonsense", "sd.server", UNREACHABLE_SD));
	private static final ServletContext NO_SD_CONTEXT = initParams(Collections.emptyMap());

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
			TestPictureBookPlanRoute.class.getClassLoader(),
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

	private Response plan(BaseRecord u, String bookOid, String sceneOid, String json) {
		return service.planScene(bookOid, sceneOid, json, requestAs(u), SD_CONTEXT);
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

	private Map<String, Object> ok(Response r, String what) {
		assertEquals(what + " must be 200, body: " + r.getEntity(), 200, r.getStatus());
		return body(r);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Map<String, Object> m, String key) {
		Object o = (m != null ? m.get(key) : null);
		if(o == null) {
			return Collections.emptyMap();
		}
		assertTrue("'" + key + "' must be a JSON object, got " + o.getClass().getSimpleName(), o instanceof Map);
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private List<Object> list(Map<String, Object> m, String key) {
		Object o = (m != null ? m.get(key) : null);
		if(o == null) {
			return Collections.emptyList();
		}
		assertTrue("'" + key + "' must be a JSON array, got " + o.getClass().getSimpleName(), o instanceof List);
		return (List<Object>) o;
	}

	private int num(Map<String, Object> m, String key) {
		Object v = m.get(key);
		assertTrue("'" + key + "' must be a number in " + m, v instanceof Number);
		return ((Number) v).intValue();
	}

	private String basePrompt(Map<String, Object> dto) {
		Map<String, Object> base = map(map(dto, "compiled"), RenderPlan.STAGE_BASE);
		assertFalse("compiled must carry a BASE entry: " + dto.get("compiled"), base.isEmpty());
		return String.valueOf(base.get("prompt"));
	}

	private String oid(BaseRecord rec) {
		return (rec != null && rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A bare STORY book owned by the owner, in a group the owner owns, created through AccessPoint as that
	/// user and read back with the book projection (TestChapBookServiceRenderPath.createChapBook).
	private BaseRecord createBareBook() throws Exception {
		String slug = "pln-" + UUID.randomUUID().toString().substring(0, 12);
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

	/// A page of the book with a setting, so the plan's location text is the scene's own text and not the
	/// title fallback. The setting is written as a field-list patch (identity + name + setting) through
	/// AccessPoint as the owner.
	private BaseRecord addScene(BaseRecord book, int index, String setting) throws Exception {
		BaseRecord created = PbBookUtil.createScene(owner, book, index, "Page " + index, GROUP_PATH);
		assertNotNull("Failed to create page " + index, created);
		String sceneOid = oid(created);
		assertNotNull("Page carries no objectId", sceneOid);
		BaseRecord scene = PbBookUtil.readScene(owner, sceneOid, orgId);
		assertNotNull("Page must read back as its owner", scene);
		if(setting != null) {
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
		}
		return scene;
	}

	/// Delete the scenes, the book, then every style row named - as the owner. Failures are logged, not
	/// asserted, so a teardown problem never masks the case.
	private void cleanup(BaseRecord book, List<BaseRecord> scenes, List<String> styleOids) {
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
		for(String so : styleOids) {
			if(so == null) {
				continue;
			}
			try {
				BaseRecord row = PbStyleUtil.readStyle(owner, so, orgId);
				if(row == null || !IOSystem.getActiveContext().getAccessPoint().delete(owner, row)) {
					logger.error("cleanup: style row " + so + " was NOT deleted");
				}
			}
			catch(Exception e) {
				logger.error("cleanup: style delete threw: " + e.getMessage());
			}
		}
	}

	/// A SERIES-scope style the stranger owns in its own home group, created through AccessPoint as the
	/// stranger and read back with the style projection (TestPbStyleResolver.newStyle/createStyle).
	private BaseRecord createStrangerStyle(String name) throws Exception {
		BaseRecord style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
		ioContext.getRecordUtil().applyNameGroupOwnership(stranger, style, name, STRANGER_STYLE_PATH, orgId);
		style.set(FieldNames.FIELD_NAME, name);
		style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.SERIES.toString());
		style.set(FieldNames.FIELD_DESCRIPTION, STRANGER_STYLE_TEXT);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(stranger, style);
		assertNotNull("Failed to create the stranger's style", created);
		BaseRecord read = PbStyleUtil.readStyle(stranger, oid(created), orgId);
		assertNotNull("The stranger must read its own style back", read);
		return read;
	}

	/// Grant or revoke Read (GROUP + DATA) on the stranger's style group for the owner, as the stranger -
	/// TestPbStyleResolver.grant. member() returns false when the entry already exists (re-runs share users),
	/// so the grant is proven by the owner's subsequent read, not by the return value. The record cache is
	/// cleared so a revoke is observed by the next read.
	private void grantStrangerGroupRead(BaseRecord strangerStyle, boolean enable) {
		long gid = ((Number) strangerStyle.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord dir = IOSystem.getActiveContext().getAccessPoint().findById(stranger, ModelNames.MODEL_GROUP, gid);
		assertNotNull("The stranger must read its own style group " + gid, dir);
		for(String type : new String[] { PermissionEnumType.GROUP.toString(), PermissionEnumType.DATA.toString() }) {
			BaseRecord perm = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, type, orgId);
			assertNotNull("Read/" + type + " permission is null", perm);
			ioContext.getMemberUtil().member(stranger, dir, owner, perm, enable);
		}
		CacheUtil.clearCache();
		ServiceUtil.clearCache();
	}

	// ─────────────────────────────── refusals ───────────────────────────────

	/// No principal is 401 before any lookup, on the real route with a well-formed body.
	@Test
	public void TestAnonymousIs401() {
		Response r = service.planScene(NIL_UUID, NIL_UUID, "{}", new HttpServletRequestMock(), SD_CONTEXT);
		assertEquals("anonymous must be 401, body: " + r.getEntity(), 401, r.getStatus());
	}

	/// A book objectId that does not exist is 404 "Book not found" (requireBook).
	@Test
	public void TestUnknownBookIs404() {
		Response r = plan(owner, NIL_UUID, NIL_UUID, "{}");
		assertEquals("unknown book must be 404, body: " + r.getEntity(), 404, r.getStatus());
		assertEquals("Book not found", errorText(r));
	}

	/// A scene objectId that does not exist is 404 naming the scene (requireSceneOfBook).
	@Test
	public void TestUnknownSceneIs404() throws Exception {
		BaseRecord book = createBareBook();
		try {
			Response r = plan(owner, oid(book), NIL_UUID, "{}");
			assertEquals("unknown scene must be 404, body: " + r.getEntity(), 404, r.getStatus());
			assertEquals("Scene " + NIL_UUID + " not found in this book", errorText(r));
		}
		finally {
			cleanup(book, Collections.emptyList(), Collections.emptyList());
		}
	}

	/// A real scene that belongs to a different book of the same owner is 404 against this book - the
	/// path pair must agree, a readable scene elsewhere is not enough.
	@Test
	public void TestSceneOfOtherBookIs404() throws Exception {
		BaseRecord bookA = createBareBook();
		BaseRecord bookB = createBareBook();
		List<BaseRecord> scenesB = new ArrayList<>();
		try {
			BaseRecord sceneB = addScene(bookB, 0, SETTING);
			scenesB.add(sceneB);
			Response r = plan(owner, oid(bookA), oid(sceneB), "{}");
			assertEquals("scene of another book must be 404, body: " + r.getEntity(), 404, r.getStatus());
			assertEquals("Scene " + oid(sceneB) + " not found in this book", errorText(r));
			/// and the same scene against its own book is a plan
			ok(plan(owner, oid(bookB), oid(sceneB), "{}"), "the same scene against its own book");
		}
		finally {
			cleanup(bookB, scenesB, Collections.emptyList());
			cleanup(bookA, Collections.emptyList(), Collections.emptyList());
		}
	}

	/// Another user in the same organization cannot plan the owner's scene: the book is not readable to
	/// them, so it is 404 "Book not found" - indistinguishable from a book that does not exist.
	@Test
	public void TestStrangerIs404() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			BaseRecord scene = addScene(book, 0, SETTING);
			scenes.add(scene);
			Response r = plan(stranger, oid(book), oid(scene), "{}");
			assertEquals("stranger must be 404, body: " + r.getEntity(), 404, r.getStatus());
			assertEquals("Book not found", errorText(r));
		}
		finally {
			cleanup(book, scenes, Collections.emptyList());
		}
	}

	/// A style chain the owner cannot read completely is 409 (PbRenderPlanUtil.buildScenePlan, the same
	/// refusal PbNodeExecutor raises) before any inventory read. The stranger's SERIES style is granted to the
	/// owner long enough to become the own style's parent (requireValidParent needs a complete parent chain)
	/// and the plan is 200 with the parent's description compiled; after the revoke the chain is truncated at
	/// olio.pb.style - at the parent, or at the own style itself when PBAC denies the whole child read once its
	/// parent is unreadable (TestPbStyleResolver.case07 records both) - and the plan is 409 naming the hop.
	@Test
	public void TestPartialStyleChainIs409() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		List<String> styles = new ArrayList<>();
		BaseRecord parent = null;
		boolean granted = false;
		try {
			BaseRecord scene = addScene(book, 0, SETTING);
			scenes.add(scene);
			parent = createStrangerStyle("pln-parent-" + UUID.randomUUID().toString().substring(0, 8));
			/// The stranger's group is shared across runs; clear any grant an aborted run left behind first.
			grantStrangerGroupRead(parent, false);
			assertNull("Control: before the grant the owner cannot read the stranger's style", PbStyleUtil.readStyle(owner, oid(parent), orgId));
			grantStrangerGroupRead(parent, true);
			granted = true;
			assertNotNull("Control: with the grant the owner reads the stranger's style", PbStyleUtil.readStyle(owner, oid(parent), orgId));

			Map<String, Object> styled = ok(service.putStyle(oid(book), "{\"parent\":\"" + oid(parent) + "\"}", requestAs(owner)), "PUT style with the stranger's parent");
			Object so = map(styled, "own").get("objectId");
			assertNotNull("PUT must create an own style: " + styled, so);
			styles.add(String.valueOf(so));
			assertEquals("the chain is complete while the parent is readable: " + styled, Boolean.TRUE, styled.get("complete"));
			assertEquals(oid(parent), map(styled, "own").get("parent"));

			Map<String, Object> before = ok(plan(owner, oid(book), oid(scene), "{}"), "plan while the parent is readable");
			String beforePrompt = basePrompt(before);
			assertEquals("the parent's description is in effect", STRANGER_STYLE_TEXT, map(before, "style").get("description"));
			assertTrue("the parent's description is compiled: " + beforePrompt, beforePrompt.contains(STRANGER_STYLE_TEXT));
			assertEquals("the own style heads the plan's chain", so, map(before, "style").get("styleObjectId"));

			grantStrangerGroupRead(parent, false);
			granted = false;
			assertNull("Control: after the revoke the parent is unreadable", PbStyleUtil.readStyle(owner, oid(parent), orgId));
			BaseRecord ownAfter = PbStyleUtil.readStyle(owner, String.valueOf(so), orgId);
			String hop = (ownAfter != null ? oid(parent) : String.valueOf(so));
			logger.info("MEASURED: after the revoke the own style is " + (ownAfter != null ? "still readable; truncation at the parent " : "unreadable too; truncation at the own style ") + hop);

			Response r = plan(owner, oid(book), oid(scene), "{}");
			assertEquals("partial chain must be 409, body: " + r.getEntity(), 409, r.getStatus());
			String msg = errorText(r);
			assertEquals("Style chain of book " + book.get(OlioFieldNames.FIELD_PB_SLUG) + " is not readable at "
				+ OlioModelNames.MODEL_PB_STYLE + " " + hop + "; scene " + oid(scene) + " cannot be planned against a partial style", msg);
			assertFalse("a refusal carries no plan: " + r.getEntity(), body(r).containsKey("compiled"));
		}
		finally {
			/// Re-grant so the owner can read and delete its own style (PBAC may hide it while the parent is
			/// unreadable), tear down as the owner, then revoke and remove the stranger's style as the stranger.
			if(parent != null && !granted) {
				grantStrangerGroupRead(parent, true);
			}
			cleanup(book, scenes, styles);
			if(parent != null) {
				grantStrangerGroupRead(parent, false);
				try {
					if(!IOSystem.getActiveContext().getAccessPoint().delete(stranger, parent)) {
						logger.error("cleanup: stranger style " + oid(parent) + " was NOT deleted");
					}
				}
				catch(Exception e) {
					logger.error("cleanup: stranger style delete threw: " + e.getMessage());
				}
			}
		}
	}

	/// No SD backend configured (no DB connection, no init-param) is a loud 500 after the book and scene
	/// were found - never a plan with a null server.
	@Test
	public void TestNoSdServerIs500() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			BaseRecord scene = addScene(book, 0, SETTING);
			scenes.add(scene);
			Response r = service.planScene(oid(book), oid(scene), "{}", requestAs(owner), NO_SD_CONTEXT);
			assertEquals("no SD server must be 500, body: " + r.getEntity(), 500, r.getStatus());
			assertEquals("SD server is not configured", errorText(r));

			Response r2 = service.planScene(oid(book), oid(scene), "{}", requestAs(owner), BAD_API_CONTEXT);
			assertEquals("unrecognised SD API type must be 500, body: " + r2.getEntity(), 500, r2.getStatus());
			assertEquals("SD server API type is not recognised", errorText(r2));
			assertFalse("a refusal never carries the server URL", String.valueOf(r2.getEntity()).contains("127.0.0.1"));
		}
		finally {
			cleanup(book, scenes, Collections.emptyList());
		}
	}

	/// An unknown quality preset is 400 naming the value; a malformed body is 400 from the route itself.
	@Test
	public void TestBadPresetAndMalformedBodyAre400() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			BaseRecord scene = addScene(book, 0, SETTING);
			scenes.add(scene);
			Response r = plan(owner, oid(book), oid(scene), "{\"preset\":\"ULTRA\"}");
			assertEquals("unknown preset must be 400, body: " + r.getEntity(), 400, r.getStatus());
			assertEquals("Unknown quality preset 'ULTRA'", errorText(r));

			Response m = plan(owner, oid(book), oid(scene), "{not json");
			assertEquals("malformed JSON must be 400, body: " + m.getEntity(), 400, m.getStatus());
			assertEquals("Malformed request body", errorText(m));
		}
		finally {
			cleanup(book, scenes, Collections.emptyList());
		}
	}

	// ─────────────────────────────── plans ───────────────────────────────

	/// The default plan of a style-less book: TEXT_ONLY, an explicit canvas, the scene's own text as the
	/// location, both model tasks present, the unreadable inventory reported as a degradation (not a
	/// failure), a compiled BASE prompt carrying the scene text, an estimate, a content hash that
	/// round-trips, no server URL anywhere in the body, and a config snapshot free of ownership fields.
	@Test
	public void TestOkPlanIsTextOnlyWithExplicitCanvas() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			BaseRecord scene = addScene(book, 0, SETTING);
			scenes.add(scene);
			Response r = plan(owner, oid(book), oid(scene), "{}");
			String raw = String.valueOf(r.getEntity());
			Map<String, Object> dto = ok(r, "default plan");

			assertEquals(RenderPlan.PLAN_VERSION, dto.get("planVersion"));
			assertEquals(RenderPlan.PURPOSE_SCENE, dto.get("purpose"));
			Map<String, Object> canvas = map(dto, "canvas");
			assertTrue("canvas width must be explicit and positive: " + canvas, num(canvas, "width") > 0);
			assertTrue("canvas height must be explicit and positive: " + canvas, num(canvas, "height") > 0);

			Map<String, Object> strategy = map(dto, "strategy");
			assertEquals("TEXT_ONLY", strategy.get("name"));
			List<Object> stages = list(strategy, "stages");
			assertEquals("a non-upscaling preset has exactly the BASE stage: " + stages, List.of(RenderPlan.STAGE_BASE), stages);
			assertEquals("no referenced actors on a bare scene", 0, list(dto, "actors").size());
			assertEquals(Boolean.FALSE, map(dto, "preset").get("upscale"));

			Map<String, Object> location = map(dto, "location");
			assertNull("a scene that names no olio.pb.location carries no locationId (P3-5; it used to be the scene objectId): " + location,
				location.get("locationId"));
			assertNull(location.get("variantId"));
			assertNull(location.get("revisionHash"));
			assertNull(location.get("renderArtifactId"));
			assertTrue("location text is the scene's setting: " + location, String.valueOf(location.get("description")).contains(SETTING));

			Map<String, Object> models = map(dto, "models");
			assertTrue("models carries the location task: " + models.keySet(), models.containsKey("location"));
			assertTrue("models carries the sceneDirect task: " + models.keySet(), models.containsKey("sceneDirect"));
			Map<String, Object> locPick = map(models, "location");
			assertNull("no model can be picked when the inventory is unreadable: " + locPick, locPick.get("model"));
			assertEquals(ModelResolver.Reason.INVENTORY_UNAVAILABLE.name(), locPick.get("reason"));

			List<Object> degradations = list(dto, "degradations");
			boolean reported = false;
			for(Object o : degradations) {
				@SuppressWarnings("unchecked")
				Map<String, Object> d = (Map<String, Object>) o;
				if(RenderPlan.Degradation.MODEL_SUBSTITUTED.equals(d.get("code"))
						&& String.valueOf(d.get("message")).contains(ModelResolver.Reason.INVENTORY_UNAVAILABLE.name())) {
					reported = true;
					assertEquals(RenderPlan.STAGE_BASE, d.get("stage"));
				}
			}
			assertTrue("the unreadable inventory is a MODEL_SUBSTITUTED degradation naming INVENTORY_UNAVAILABLE: " + degradations, reported);

			assertTrue("BASE prompt carries the scene text", basePrompt(dto).contains(SETTING));

			Map<String, Object> estimate = map(dto, "estimate");
			assertEquals(1, num(estimate, "scenes"));
			assertEquals(stages.size(), num(estimate, "stages"));
			assertNotNull("estimate names its source", estimate.get("source"));
			assertEquals("nothing has rendered against this server in this JVM", Boolean.FALSE, estimate.get("measured"));

			String hash = String.valueOf(dto.get("contentHash"));
			assertEquals("contentHash is the hash of the plan on the wire", RenderPlan.fromMap(dto).contentHash(), hash);

			assertFalse("the plan never carries the SD server URL", raw.contains("127.0.0.1"));
			Map<String, Object> config = map(dto, "config");
			assertFalse("config snapshot is not empty", config.isEmpty());
			for(String k : new String[] { FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID,
					FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID }) {
				assertFalse("config snapshot must not carry " + k + ": " + config.keySet(), config.containsKey(k));
			}
			Map<String, Object> style = map(dto, "style");
			assertNotNull("style.revisionHash is always present", style.get("revisionHash"));
			assertNull("a style-less book names no style", style.get("styleObjectId"));
		}
		finally {
			cleanup(book, scenes, Collections.emptyList());
		}
	}

	/// A promptOverride replaces the location text as the main prompt: the override is compiled, the
	/// scene's setting is not, the intent echoes the override, and the hash differs from the default plan.
	@Test
	public void TestPromptOverrideBecomesMain() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			BaseRecord scene = addScene(book, 0, SETTING);
			scenes.add(scene);
			Map<String, Object> plain = ok(plan(owner, oid(book), oid(scene), "{}"), "default plan");
			Map<String, Object> dto = ok(plan(owner, oid(book), oid(scene), "{\"promptOverride\":\"" + OVERRIDE + "\"}"), "override plan");
			String prompt = basePrompt(dto);
			assertTrue("override is the main prompt: " + prompt, prompt.startsWith(OVERRIDE));
			assertFalse("the scene's setting is replaced, not appended: " + prompt, prompt.contains(SETTING));
			assertEquals(OVERRIDE, map(dto, "intent").get("promptOverride"));
			assertTrue("location text is still reported on the plan", String.valueOf(map(dto, "location").get("description")).contains(SETTING));
			assertNotEquals("an override changes the content hash", plain.get("contentHash"), dto.get("contentHash"));
		}
		finally {
			cleanup(book, scenes, Collections.emptyList());
		}
	}

	/// raw:true compiles without the style's description and photoreal boilerplate; the style is still
	/// reported on the plan. Accepted as a JSON boolean and as the string "true".
	@Test
	public void TestRawSkipsStyle() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		List<String> styles = new ArrayList<>();
		try {
			BaseRecord scene = addScene(book, 0, SETTING);
			scenes.add(scene);
			Response ps = service.putStyle(oid(book), "{\"description\":\"" + STYLE_TEXT + "\",\"photoreal\":true}", requestAs(owner));
			Map<String, Object> styled = ok(ps, "PUT style");
			Object so = map(styled, "own").get("objectId");
			assertNotNull("PUT must create an own style: " + styled, so);
			styles.add(String.valueOf(so));

			Map<String, Object> dressed = ok(plan(owner, oid(book), oid(scene), "{}"), "styled plan");
			String dressedPrompt = basePrompt(dressed);
			assertTrue("style description is compiled: " + dressedPrompt, dressedPrompt.contains(STYLE_TEXT));
			assertTrue("photoreal boilerplate is compiled: " + dressedPrompt, dressedPrompt.contains(SdxlCompiler.PHOTOREAL_BOILERPLATE));
			assertTrue("scene text is compiled: " + dressedPrompt, dressedPrompt.contains(SETTING));
			assertEquals(STYLE_TEXT, map(dressed, "style").get("description"));
			assertEquals(Boolean.TRUE, map(dressed, "style").get("photoreal"));
			assertEquals(so, map(dressed, "style").get("styleObjectId"));

			for(String body : new String[] { "{\"raw\":true}", "{\"raw\":\"true\"}" }) {
				Map<String, Object> bare = ok(plan(owner, oid(book), oid(scene), body), "raw plan " + body);
				String barePrompt = basePrompt(bare);
				assertFalse("raw omits the style description (" + body + "): " + barePrompt, barePrompt.contains(STYLE_TEXT));
				assertFalse("raw omits the photoreal boilerplate (" + body + "): " + barePrompt, barePrompt.contains(SdxlCompiler.PHOTOREAL_BOILERPLATE));
				assertTrue("raw still compiles the scene text (" + body + "): " + barePrompt, barePrompt.contains(SETTING));
				assertEquals(Boolean.TRUE, map(bare, "intent").get("raw"));
				assertEquals("the style is still on the plan under raw", STYLE_TEXT, map(bare, "style").get("description"));
				assertEquals("the style hash is the same plan input under raw", map(dressed, "style").get("revisionHash"), map(bare, "style").get("revisionHash"));
			}
		}
		finally {
			cleanup(book, scenes, styles);
		}
	}

	/// preset "final" (case-insensitive) adds the UPSCALE stage and the estimate counts both stages.
	@Test
	public void TestFinalPresetAddsUpscale() throws Exception {
		BaseRecord book = createBareBook();
		List<BaseRecord> scenes = new ArrayList<>();
		try {
			BaseRecord scene = addScene(book, 0, SETTING);
			scenes.add(scene);
			Map<String, Object> dto = ok(plan(owner, oid(book), oid(scene), "{\"preset\":\"final\"}"), "final plan");
			Map<String, Object> preset = map(dto, "preset");
			assertEquals("FINAL", preset.get("preset"));
			assertEquals(Boolean.TRUE, preset.get("upscale"));
			assertEquals(List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_UPSCALE), list(map(dto, "strategy"), "stages"));
			assertEquals(2, num(map(dto, "estimate"), "stages"));
			assertEquals(1, num(map(dto, "estimate"), "scenes"));
			Map<String, Object> compiled = map(dto, "compiled");
			assertTrue("both stages are compiled: " + compiled.keySet(), compiled.containsKey(RenderPlan.STAGE_BASE) && compiled.containsKey(RenderPlan.STAGE_UPSCALE));
		}
		finally {
			cleanup(book, scenes, Collections.emptyList());
		}
	}
}
