package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil;
import org.cote.accountmanager.olio.picturebook.PbRenderJobUtil;
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.ChapBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// P1-9 (design §24, §28.1 Stage 1) and P2-6 (§28.1 Stage 2): the two ChapBook render routes - POST
/// /chapbook/scene/{id}/generate and POST /chapbook/render/{book}[?async=true] - keep one request/response
/// contract on both render paths: v3 (the ChapBook default since P2-6) refuses or skips before any graph
/// work exactly where v1 would, and the explicit v1 cases below are the ROLLBACK proof - with
/// chapbook.renderPath=v1 the routes still answer on the direct SD path with the legacy bodies.
///
/// Exercised through the real ChapBookService methods -> ChapBookUtil -> PbServiceFacade -> SDUtil, the
/// real AsyncJobRegistry and the real (H2) database, with Service7's own HttpServletRequestMock +
/// UserPrincipal (the TestPbRenderPathRefusal pattern) and a ServletContext that answers only the two SD
/// init-params the routes read. Nothing in the application is mocked; the SD server is a real SDUtil
/// pointed at a closed loopback port, so a v1 SD call fails fast with a refused connection and the test
/// reads how the route REPORTS that failure.
///
/// FIXTURE. A bare CHAPBOOK olio.pb.book row the user owns (no Olio world) plus olio.pb.scene rows
/// created through PbBookUtil.createScene as that user; a page gets its prompt through
/// ChapBookUtil.setSceneLandscapePrompt (a genuine, non-fallback prompt - no LLM is involved).
///
/// WHAT THIS CANNOT COVER, AND WHERE IT IS COVERED. A v3 render needs the book's
/// /Olio/Universes/Books/Worlds/<slug>/Workflow group, which only PbBookUtil.createBook's Olio world
/// initialization (seed data, olio principal, world grants) builds - the Service7 H2 harness has no
/// datagen path and has never initialized an Olio context, and a plain user cannot create the top-level
/// /Olio node (measured 2026-10-11: "Not authorized to create auth.group ... node Olio with parent #0").
/// So the v3 job-shaped bodies (jobId / runObjectId / nodeObjectId / nodeStatus, 202 with pending=true
/// for ?async=true, the settled 200 counts) are asserted against a real ChapBook on the Docker stack by
/// the ChapBook Playwright suite on v3, and the v3 node/artifact rows by TestChapBookV3Render (Objects7,
/// am7db, real createChapBook). What this test pins for v3 is the contract BELOW that fixture: a
/// world-less ChapBook is a loud 500 naming the workflow, never a 200 with rendered=false.
///
/// The render-path switch is process-global; every case restores it in finally.
public class TestChapBookServiceRenderPath extends BaseTest {

	private static final String USER_NAME = "cbrenderpathuser";
	private static final String GROUP_PATH = "~/ChapBookServiceRenderPath";
	private static final String SD_API_TYPE = "SWARM";
	/// A closed loopback port: the connect is refused immediately, so an SD stage fails fast and
	/// deterministically. This is never a statement about any real SD server.
	private static final String UNREACHABLE_SD = "http://127.0.0.1:1";
	private static final String GENUINE_PROMPT = "a quiet river at dawn, mist rising over the reeds, soft golden light on the water";
	/// Bound on how long a case waits for a registry job to settle - never a timing assertion.
	private static final long JOB_WAIT_MS = 5L * 60L * 1000L;

	private static final ServletContext SD_CONTEXT = initParams(Map.of("sd.server.apiType", SD_API_TYPE, "sd.server", UNREACHABLE_SD));
	private static final ServletContext NO_SD_CONTEXT = initParams(Collections.emptyMap());

	private final ChapBookService service = new ChapBookService();

	private BaseRecord user;
	private long orgId;
	private String pb0;
	private String cb0;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		user = getCreateUser(USER_NAME);
		assertNotNull("Test user is null", user);
		pb0 = PbRenderPath.pictureBook();
		cb0 = PbRenderPath.chapBook();
	}

	@After
	public void restoreSwitch() {
		PbRenderPath.configure(pb0, cb0);
	}

	/// The routes read exactly two init-params and nothing else off the ServletContext
	/// (HttpServletRequestMock's own getServletContext() is null, which would NPE before the 401 gate).
	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestChapBookServiceRenderPath.class.getClassLoader(),
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

	private static String str(Map<String, Object> b, String key) {
		Object v = b.get(key);
		return (v != null ? v.toString() : null);
	}

	private static int num(Map<String, Object> b, String key) {
		Object v = b.get(key);
		assertTrue("'" + key + "' must be a number, got " + v, v instanceof Number);
		return ((Number) v).intValue();
	}

	private static void assertFlag(Map<String, Object> b, String key, boolean expected) {
		assertEquals("'" + key + "' in " + b, Boolean.valueOf(expected), b.get(key));
	}

	/// A bare CHAPBOOK book owned by the test user in a group the test user owns - created through
	/// AccessPoint as that user, no admin, no RecordUtil bypass - read back with the book projection so
	/// the slug (which names the scenes and the workflow group) is present.
	private BaseRecord createChapBook() throws Exception {
		return createBook(PbBookTypeEnumType.CHAPBOOK);
	}

	/// The same bare book of the given type (STORY for the 409 case of the prompt route).
	private BaseRecord createBook(PbBookTypeEnumType bookType) throws Exception {
		String slug = "cbrp-" + UUID.randomUUID().toString().substring(0, 12);
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, GROUP_PATH, "DATA", orgId);
		assertNotNull("Fixture group is null", dir);
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(user, book, slug, GROUP_PATH, orgId);
		book.set(FieldNames.FIELD_NAME, slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, bookType.toString());
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, book);
		assertNotNull("Failed to create the ChapBook fixture", created);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Created book carries no objectId", oid);
		BaseRecord read = PbBookUtil.readBook(user, oid, orgId);
		assertNotNull("The created book must read back as its owner", read);
		return read;
	}

	/// A page of the book; with a prompt it is a genuine stored landscape prompt (the shape the render
	/// pre-step treats as authoritative, so no LLM recovery is attempted), without one it is un-prompted.
	private BaseRecord addPage(BaseRecord book, int index, String prompt) {
		BaseRecord scene = PbBookUtil.createScene(user, book, index, "Page " + index, GROUP_PATH);
		assertNotNull("Failed to create page " + index, scene);
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Page carries no objectId", sceneOid);
		if (prompt != null) {
			assertTrue("Failed to set the page prompt", ChapBookUtil.setSceneLandscapePrompt(user, sceneOid, prompt));
			scene = PbBookUtil.readScene(user, sceneOid, orgId);
			assertNotNull("Prompted page must read back", scene);
			String stored = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			assertTrue("Stored prompt must be a genuine prompt, got: " + stored, ChapBookUtil.isGenuineStoredPrompt(stored));
			assertTrue("Stored prompt must be the one set: " + stored, stored.contains("quiet river"));
		}
		return scene;
	}

	private String oid(BaseRecord r) {
		return r.get(FieldNames.FIELD_OBJECT_ID);
	}

	/// The scene's page node link, read fresh (uncached projection) - null when no graph node was made.
	private BaseRecord sceneNodeOf(String sceneOid) {
		BaseRecord scene = PbBookUtil.readScene(user, sceneOid, orgId);
		assertNotNull("Scene must still read back: " + sceneOid, scene);
		return scene.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
	}

	private AsyncJob awaitJob(String jobId) {
		assertNotNull("jobId is null", jobId);
		AsyncJob job = PbRenderJobUtil.awaitTerminal(user, jobId, JOB_WAIT_MS);
		assertNotNull("Job " + jobId + " did not settle within the wait bound", job);
		return job;
	}

	/// Best-effort cleanup. Never asserted, so a failed delete cannot mask the case's own failure;
	/// every fixture carries a unique slug so residue (workflow group, node, run rows) cannot collide.
	private void deleteFixture(BaseRecord book, BaseRecord... scenes) {
		for (BaseRecord s : scenes) {
			if (s != null && !IOSystem.getActiveContext().getAccessPoint().delete(user, s)) {
				logger.warn("Fixture scene was not deleted: " + oid(s));
			}
		}
		if (book != null && !IOSystem.getActiveContext().getAccessPoint().delete(user, book)) {
			logger.warn("Fixture book was not deleted: " + oid(book));
		}
	}

	/// ------------------------------------------------------------------------------------------

	/// On v3 a prompted page of a ChapBook whose world was never built (so the workflow group cannot be
	/// created as the user) is a loud 500 naming the workflow on both routes - never a 200 with
	/// rendered=false, never a skip - and nothing is left behind: no node on the scene, no job submitted.
	/// The same book on v1 renders (case below); the difference is the v3 graph requirement, not the book.
	@Test
	public void TestV3OnAWorldlessChapBookIs500NamingTheWorkflow() throws Exception {
		BaseRecord book = createChapBook();
		BaseRecord page = addPage(book, 0, GENUINE_PROMPT);
		try {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
			Response rs = service.renderChapBookScene(oid(page), "{}", requestAs(user), SD_CONTEXT);
			Map<String, Object> bs = body(rs);
			assertEquals("page render on a world-less v3 ChapBook: " + bs, 500, rs.getStatus());
			assertTrue("the error names the workflow: " + bs, String.valueOf(bs.get("error")).startsWith("Failed to create a workflow for book"));
			assertFalse("an error body carries no render outcome: " + bs, bs.containsKey("rendered"));
			assertFalse("an error body carries no job: " + bs, bs.containsKey("jobId"));

			for (boolean async : new boolean[] { true, false }) {
				Response rb = service.renderChapBook(oid(book), async, "{}", requestAs(user), SD_CONTEXT);
				Map<String, Object> bb = body(rb);
				assertEquals("bulk render (async=" + async + ") on a world-less v3 ChapBook: " + bb, 500, rb.getStatus());
				assertTrue("the error names the workflow: " + bb, String.valueOf(bb.get("error")).startsWith("Failed to create a workflow for book"));
				assertFalse("an error body carries no job: " + bb, bb.containsKey("jobId"));
			}
			assertNull("No node may have been linked to the page", sceneNodeOf(oid(page)));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteFixture(book, page);
		}
	}

	/// Rollback (chapbook.renderPath=v1, §28.1 Stage 2): the same route stays on the direct SD path: no job,
	/// no run, no node, and the scene row is never linked to a graph node. The refused SD call is reported
	/// as rendered=false with the v1 shape.
	@Test
	public void TestPageRenderOnV1StaysOnTheDirectPath() throws Exception {
		BaseRecord book = createChapBook();
		BaseRecord page = addPage(book, 0, GENUINE_PROMPT);
		try {
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
			Response r = service.renderChapBookScene(oid(page), "{}", requestAs(user), SD_CONTEXT);
			Map<String, Object> b = body(r);
			assertEquals("A v1 page render answers 200: " + b, 200, r.getStatus());
			assertEquals(PbRenderPath.V1, str(b, "renderPath"));
			assertNull("v1 has no job: " + b, str(b, "jobId"));
			assertNull("v1 has no run: " + b, str(b, "runObjectId"));
			assertNull("v1 has no node: " + b, str(b, "nodeObjectId"));
			assertNull("v1 has no artifact: " + b, str(b, "artifactObjectId"));
			assertNull("v1 has no node status: " + b, str(b, "nodeStatus"));
			assertFlag(b, "rendered", false);
			assertFlag(b, "skipped", false);
			assertFlag(b, "pending", false);
			assertNull("No image when the SD call was refused: " + b, str(b, "imageObjectId"));
			assertNull("v1 must not create a graph node for the page", sceneNodeOf(oid(page)));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteFixture(book, page);
		}
	}

	/// An un-prompted page on v3 is skipped before any node or job exists - the v1 meaning of
	/// SKIPPED_NO_PROMPT, with the v3 renderPath so the client knows which path declined.
	@Test
	public void TestUnPromptedPageOnV3IsSkippedWithoutANode() throws Exception {
		BaseRecord book = createChapBook();
		BaseRecord page = addPage(book, 0, null);
		try {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
			Response r = service.renderChapBookScene(oid(page), "{}", requestAs(user), SD_CONTEXT);
			Map<String, Object> b = body(r);
			assertEquals("A skipped page is 200: " + b, 200, r.getStatus());
			assertEquals(PbRenderPath.V3, str(b, "renderPath"));
			assertFlag(b, "skipped", true);
			assertFlag(b, "rendered", false);
			assertFlag(b, "pending", false);
			assertNull("No job for a skipped page: " + b, str(b, "jobId"));
			assertNull("No node for a skipped page: " + b, str(b, "nodeObjectId"));
			assertNull("No image for a skipped page: " + b, str(b, "imageObjectId"));
			assertTrue("llmUnavailable must be reported as a boolean: " + b, b.get("llmUnavailable") instanceof Boolean);
			assertNull("A skipped page must not have been given a graph node", sceneNodeOf(oid(page)));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteFixture(book, page);
		}
	}

	/// Rollback (chapbook.renderPath=v1): the bulk route with ?async=true keeps the legacy cb.render wrapper
	/// job and its legacy body ({jobId, status}); after it settles no page has a graph node.
	@Test
	public void TestBulkAsyncOnV1IsTheLegacyWrapperJob() throws Exception {
		BaseRecord book = createChapBook();
		BaseRecord prompted = addPage(book, 0, GENUINE_PROMPT);
		try {
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
			Response r = service.renderChapBook(oid(book), true, "{}", requestAs(user), SD_CONTEXT);
			Map<String, Object> b = body(r);
			assertEquals("The legacy wrapper is submitted as 202: " + b, 202, r.getStatus());
			assertNotNull("The wrapper job id: " + b, str(b, "jobId"));
			assertNotNull("The wrapper job status: " + b, str(b, "status"));
			assertFalse("The legacy body carries no renderPath: " + b, b.containsKey("renderPath"));
			assertFalse("The legacy body carries no run: " + b, b.containsKey("runObjectId"));
			AsyncJob job = awaitJob(str(b, "jobId"));
			assertTrue("The wrapper job must settle", job.isTerminal());
			assertNull("v1 must not create a graph node for the page", sceneNodeOf(oid(prompted)));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteFixture(book, prompted);
		}
	}

	/// No principal is 401 on both routes regardless of the switch - and before the SD init-params are
	/// consulted, so a missing SD configuration never changes the answer to an unauthenticated caller.
	@Test
	public void TestNoPrincipalIs401() {
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
		assertEquals(401, service.renderChapBookScene("x", "{}", new HttpServletRequestMock(), NO_SD_CONTEXT).getStatus());
		assertEquals(401, service.renderChapBook("x", true, "{}", new HttpServletRequestMock(), NO_SD_CONTEXT).getStatus());
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
		assertEquals(401, service.renderChapBookScene("x", "{}", new HttpServletRequestMock(), NO_SD_CONTEXT).getStatus());
		assertEquals(401, service.renderChapBook("x", false, "{}", new HttpServletRequestMock(), NO_SD_CONTEXT).getStatus());
	}

	/// Missing SD init-params are a 500 naming them, on both routes and both paths, before any book or
	/// scene is looked up - so the fixture is never touched and no job is submitted.
	@Test
	public void TestMissingSdInitParamsAre500BeforeAnyRender() throws Exception {
		BaseRecord book = createChapBook();
		BaseRecord page = addPage(book, 0, GENUINE_PROMPT);
		try {
			for (String path : new String[] { PbRenderPath.V1, PbRenderPath.V3 }) {
				PbRenderPath.configure(path, path);
				Response rs = service.renderChapBookScene(oid(page), "{}", requestAs(user), NO_SD_CONTEXT);
				assertEquals("page render under " + path, 500, rs.getStatus());
				assertTrue(String.valueOf(body(rs).get("error")).startsWith("SD server not configured"));
				Response rb = service.renderChapBook(oid(book), true, "{}", requestAs(user), NO_SD_CONTEXT);
				assertEquals("bulk render under " + path, 500, rb.getStatus());
				assertTrue(String.valueOf(body(rb).get("error")).startsWith("SD server not configured"));
			}
			assertNull("No render ran, so no node was made", sceneNodeOf(oid(page)));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteFixture(book, page);
		}
	}

	/// PUT /chapbook/scene/{id}/prompt answers 200 with a body that says what landed (P4-8 §6.C 194 ee, re-baselined
	/// for P4-10, where the page's CHAPBOOK_PAGE composition is the authority and is written FIRST, the scene
	/// sdPrompt/promptLocked being its mirror): on a page of a ChapBook the first set creates the page
	/// composition and names it (updated, compositionUpdated, sceneUpdated, compositionObjectId, promptLocked
	/// true, no error) and the composition AND the scene mirror both carry the prompt; a clear ({} body) is the
	/// same complete shape unlocked on the same composition. Failures BEFORE any write map to their status and
	/// write nothing: a scene the user owns that belongs to no book is a 500 ("has no readable book") - under
	/// P4-8 that was a 200 partial with the scene written first; since P4-10 the composition is written first
	/// and a bookless scene has no composition to write, so the scene is NOT touched either - a scene of a
	/// STORY book is a 409 (its prompt is edited on the composition board) with no CHAPBOOK_PAGE composition
	/// created and the scene untouched, and a missing scene is still a 404.
	///
	/// A true partial (composition written, scene mirror refused) is not constructible here: the H2 harness
	/// has one user who owns the book, the scene and the composition group, so nothing refuses the mirror.
	/// That branch is pinned in Objects7 by TestChapBookPageComposition against am7db with a second,
	/// entitlement-limited user.
	@Test
	public void TestSetPromptBodyReportsSceneAndCompositionWrites() throws Exception {
		BaseRecord book = createChapBook();
		BaseRecord page = addPage(book, 0, null);
		BaseRecord bookless = createBooklessScene();
		BaseRecord story = createBook(PbBookTypeEnumType.STORY);
		BaseRecord storyScene = PbBookUtil.createScene(user, story, 0, "Story scene 0", GROUP_PATH);
		assertNotNull("Failed to create the story scene", storyScene);
		long storyGid = ((Number) story.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord comp = null;
		try {
			Response rs = service.setSceneLandscapePrompt(oid(page), "{\"sdPrompt\":\"" + GENUINE_PROMPT + "\"}", requestAs(user));
			assertEquals("set on a page of a book", 200, rs.getStatus());
			Map<String, Object> b = body(rs);
			logger.info("[p410] set body: " + b);
			assertFlag(b, "updated", true);
			assertFlag(b, "compositionUpdated", true);
			assertFlag(b, "sceneUpdated", true);
			assertFlag(b, "promptLocked", true);
			String compOid = str(b, "compositionObjectId");
			assertNotNull("a complete write names the composition: " + b, compOid);
			assertFalse("a complete write carries no error: " + b, b.containsKey("error"));
			comp = PbComposeUtil.readComposition(user, compOid, orgId);
			assertNotNull("the named composition reads back as the user", comp);
			assertEquals("the composition carries the prompt", GENUINE_PROMPT, comp.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals("the composition is locked", Boolean.TRUE, comp.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals("a human edit marks the composition userEdited", Boolean.TRUE, comp.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			BaseRecord pageAfterSet = PbBookUtil.readScene(user, oid(page), orgId);
			assertEquals("the scene mirrors the prompt", GENUINE_PROMPT, pageAfterSet.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals("the scene mirrors the lock", Boolean.TRUE, pageAfterSet.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

			Response rc = service.setSceneLandscapePrompt(oid(page), "{}", requestAs(user));
			assertEquals("clear", 200, rc.getStatus());
			Map<String, Object> c = body(rc);
			logger.info("[p410] clear body: " + c);
			assertFlag(c, "updated", true);
			assertFlag(c, "compositionUpdated", true);
			assertFlag(c, "sceneUpdated", true);
			assertFlag(c, "promptLocked", false);
			assertEquals("a clear reuses the same composition", compOid, str(c, "compositionObjectId"));
			assertFalse("a clear carries no error: " + c, c.containsKey("error"));
			BaseRecord compAfterClear = PbComposeUtil.readComposition(user, compOid, orgId);
			assertNull("the composition prompt is cleared", compAfterClear.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals("the composition is unlocked", Boolean.FALSE, compAfterClear.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			BaseRecord pageAfterClear = PbBookUtil.readScene(user, oid(page), orgId);
			assertNull("the page prompt is cleared", pageAfterClear.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals("the page is unlocked", Boolean.FALSE, pageAfterClear.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

			/// A bookless scene: the composition-first write has no book to put a composition in, so the
			/// request is refused BEFORE any write (P4-8 wrote the scene first and answered a 200 partial).
			Response rp = service.setSceneLandscapePrompt(oid(bookless), "{\"sdPrompt\":\"" + GENUINE_PROMPT + "\"}", requestAs(user));
			Map<String, Object> p = body(rp);
			logger.info("[p410] bookless status=" + rp.getStatus() + " body: " + p);
			assertEquals("a bookless scene is refused before any write", 500, rp.getStatus());
			String err = str(p, "error");
			assertNotNull("the refusal is in the body: " + p, err);
			assertEquals("the error names the scene and the reason", "Scene " + oid(bookless) + " has no readable book", err);
			assertFalse("no write flags on a refusal: " + p, p.containsKey("updated"));
			assertFalse("no composition to name: " + p, p.containsKey("compositionObjectId"));
			BaseRecord booklessAfter = PbBookUtil.readScene(user, oid(bookless), orgId);
			assertNull("the bookless scene's sdPrompt was NOT written", booklessAfter.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals("the bookless scene was not locked", Boolean.FALSE, booklessAfter.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

			/// A scene of a STORY book: its prompt lives on the composition board, so the page-prompt route is a
			/// 409 before any write - no CHAPBOOK_PAGE composition, no scene change.
			Response ro = service.setSceneLandscapePrompt(oid(storyScene), "{\"sdPrompt\":\"" + GENUINE_PROMPT + "\"}", requestAs(user));
			Map<String, Object> o = body(ro);
			logger.info("[p410] story status=" + ro.getStatus() + " body: " + o);
			assertEquals("a story-book scene is a 409", 409, ro.getStatus());
			String storyErr = str(o, "error");
			assertNotNull("the refusal is in the body: " + o, storyErr);
			assertEquals("Scene " + oid(storyScene) + " belongs to a story book; edit its composition board instead", storyErr);
			assertFalse("no write flags on a refusal: " + o, o.containsKey("updated"));
			assertNull("no CHAPBOOK_PAGE composition was created for the story scene",
				PbComposeUtil.findSceneComposition(user, storyScene, storyGid, orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE));
			assertNull("no SCENE composition was created for the story scene either",
				PbComposeUtil.findSceneComposition(user, storyScene, storyGid, orgId));
			BaseRecord storyAfter = PbBookUtil.readScene(user, oid(storyScene), orgId);
			assertNull("the story scene's sdPrompt was NOT written", storyAfter.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals("the story scene was not locked", Boolean.FALSE, storyAfter.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));

			Response rm = service.setSceneLandscapePrompt("no-such-scene", "{\"sdPrompt\":\"x\"}", requestAs(user));
			assertEquals("a missing scene is still a 404", 404, rm.getStatus());
			assertTrue(String.valueOf(body(rm).get("error")).startsWith("Scene not found"));
		}
		finally {
			if (comp != null && !IOSystem.getActiveContext().getAccessPoint().delete(user, comp)) {
				logger.warn("Fixture composition was not deleted: " + oid(comp));
			}
			deleteFixture(book, page, bookless);
			deleteFixture(story, storyScene);
		}
	}

	/// An olio.pb.scene the user owns with no book: PbBookUtil.createScene refuses that (400), so it is
	/// assembled the way that method does and created through AccessPoint as the user. No page composition
	/// can be created for it, which is the real shape of a partial prompt write.
	private BaseRecord createBooklessScene() throws Exception {
		String name = "bookless-" + UUID.randomUUID().toString().substring(0, 12);
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, GROUP_PATH, "DATA", orgId);
		assertNotNull("Fixture group is null", dir);
		BaseRecord scene = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE);
		ioContext.getRecordUtil().applyNameGroupOwnership(user, scene, name, GROUP_PATH, orgId);
		scene.set(FieldNames.FIELD_NAME, name);
		scene.set(OlioFieldNames.FIELD_PB_SCENE_INDEX, Integer.valueOf(0));
		scene.set(OlioFieldNames.FIELD_PB_TITLE, "Bookless");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, scene);
		assertNotNull("Failed to create the bookless scene", created);
		BaseRecord read = PbBookUtil.readScene(user, oid(created), orgId);
		assertNotNull("The bookless scene must read back", read);
		assertNull("precondition: no book", read.get(OlioFieldNames.FIELD_PB_BOOK));
		return read;
	}
}
