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
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.ChapBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// P1-9 (design §24, §28.1 Stage 1): the two ChapBook render routes - POST /chapbook/scene/{id}/generate
/// and POST /chapbook/render/{book}[?async=true] - keep one request/response contract on both render
/// paths: the v1 default stays on the direct SD path with the legacy bodies, and v3 refuses or skips
/// before any graph work exactly where v1 would.
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
		String slug = "cbrp-" + UUID.randomUUID().toString().substring(0, 12);
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, GROUP_PATH, "DATA", orgId);
		assertNotNull("Fixture group is null", dir);
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(user, book, slug, GROUP_PATH, orgId);
		book.set(FieldNames.FIELD_NAME, slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.CHAPBOOK.toString());
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

	/// On v1 the same route stays on the direct SD path: no job, no run, no node, and the scene row is
	/// never linked to a graph node. The refused SD call is reported as rendered=false with the v1 shape.
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

	/// On v1 the bulk route with ?async=true keeps the legacy cb.render wrapper job and its legacy body
	/// ({jobId, status}); after it settles no page has a graph node.
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
}
