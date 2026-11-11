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
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil;
import org.cote.accountmanager.olio.picturebook.PbRenderJobUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// P10-B 1 (PictureBook V3 Implementation §6.C 258-259, §20.9): the three render-side routes a NEW book reaches
/// from the reader and the workflow canvas, exercised through the real service methods -> PbServiceFacade ->
/// PbGraphUtil / PbRenderJobUtil -> real AccessPoint/PBAC -> the real (H2) database, with HttpServletRequestMock
/// + UserPrincipal exactly as TestPictureBookCoverRoutes does. Nothing is mocked; no asserting actor is ever the
/// admin or the olio principal.
///
///   POST /{book}/scene/{scene}/render   renderScene   202 {sceneObjectId, sceneIndex, nodeObjectId, nodeMaterialized, jobId, runObjectId, plannedNodeCount, ...}
///   POST /{book}/graph/build            buildGraph    200 {bookObjectId, workflowObjectId, sceneCount, nodesMaterialized}
///   POST /{book}/render-all             renderAll     202 {sceneCount, scenesWithoutNode, nodesMaterialized, jobId, runObjectId, plannedNodeCount, ...}
///   GET  /{book}/workflow               getWorkflow   200 the canvas view (404 "no workflow yet" until something built it)
///
/// THE UAT BLOCKER THIS PINS. A book made by the wizard (scenes and characters extracted, no workflow yet) answered
/// every render from the reader with the 404 "This book has no workflow yet - generate a scene first" while plainly
/// having a scene list. The contract now: the workflow and the scene's RENDER_PLAN node are created on first use,
/// AFTER the gates (404 book / 403 update / 404 scene), as the book's updater - so the first render
/// of a brand-new book is a 202 whose body says `nodeMaterialized:true`, a graph build is a 200 that materializes
/// one node per scene and is idempotent, and render-all plans every scene with `scenesWithoutNode` always empty.
/// A book with no scenes at all is render-all's 409 and the ONE case that leaves no workflow behind: the refusal
/// precedes ensureWorkflow, so GET /workflow is still the 404 afterwards. The same walk through the browser is
/// e2e/pictureBookNewBookRender.spec.js (Docker stack, .42 + .39).
///
/// FIXTURE. TestPictureBookCoverRoutes' hand-built H2 world fixture (an olio-owned book in a Books-universe world
/// with its Workflow and Artifacts groups made as the olio principal before the Writer's nested-group grants, so
/// the routes' first-use writes - workflow, RENDER_PLAN nodes, run rows in {container}/Workflow - are authorized
/// Writer writes; an optional Reader role) with its own user names and slug prefix so the classes never share
/// rows. Scenes are PbBookUtil.createScene as the owner with a `setting` patched in - no LLM is called anywhere
/// in this class. The gate cases that need no world use a bare owner-owned book: it has no Workflow group a
/// plain user may create under /Olio, which is exactly why it is only used where the route must refuse BEFORE
/// ensureWorkflow.
///
/// THE JOBS. SD_CONTEXT is a loopback port nobody listens on, so every accepted job runs to the end of its loop
/// (the registry job is COMPLETED in ~1 s) with its one render stage FAILED - "Renderer inventory unavailable;
/// cannot verify a model for scene N before rendering" - the node FAILED and the run closed FAILED. Each job is
/// awaited to terminal as the submitting owner (so a node is never left in flight when the next route or the
/// cleanup runs), logged, never asserted. No LLM is reached either: compose-scene finds no chat config and the
/// MIGRATED composition written from the scene's own fields stands. The planner then EXECUTES a FAILED node again
/// (a render is the retry of a failure), which is why render-all after a failed single-scene render plans every
/// scene. The rendered image against Swarm .39 is TestPbNewBookRender (Objects7, am7db) and the Playwright spec
/// above.
///
/// WIRE NOTE (measured, not a defect under test): JSONUtil.exportObject serialises with Include.NON_EMPTY, so
/// the empty `scenesWithoutNode` list and an empty `nodes` list are ABSENT from a body rather than present as [];
/// booleans (`nodeMaterialized`) and ints (`sceneCount`, `nodesMaterialized`, `plannedNodeCount`) ARE present.
public class TestPictureBookNewBookRoutes extends BaseTest {

	private static final String OWNER_NAME = "pbnbrtowner";
	private static final String READER_NAME = "pbnbrtreader";
	private static final String STRANGER_NAME = "pbnbrtstranger";
	private static final String BARE_GROUP_PATH = "~/PbNewBookRoutes";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String[] DATA_AND_GROUP = new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() };
	private static final String[] CRUD = new String[] { "Read", "Update", "Create", "Delete" };
	private static final String[] READ = new String[] { "Read" };
	private static final String SCENE_SETTING = "A rowboat tied to a drowned pier, the lantern still burning under the water";
	private static final String UNREACHABLE_SD = "http://127.0.0.1:1";
	private static final String NOT_FOUND = "Book not found";
	private static final String NO_PRINCIPAL = "No authenticated principal";
	private static final String NO_WORKFLOW = "no workflow";
	private static final String NO_SCENES = "This book has no scenes yet";
	private static final String SCENE_NOT_FOUND = "not found in this book";
	private static final String REFUSED_RENDER = "Not authorized to render a scene in this book";
	private static final String REFUSED_BUILD = "Not authorized to build the book's graph in this book";
	private static final String REFUSED_RENDER_ALL = "Not authorized to render the book in this book";
	private static final long JOB_CEILING_MS = 5L * 60L * 1000L;

	private static final ServletContext SD_CONTEXT = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", UNREACHABLE_SD));

	private final PictureBookService service = new PictureBookService();
	private final OlioContextConfiguration olioCfg = new OlioContextConfiguration();

	private BaseRecord admin;
	private BaseRecord olioUser;
	private BaseRecord owner;
	private BaseRecord reader;
	private BaseRecord stranger;
	private long orgId;

	private static final class Fixture {
		String slug;
		BaseRecord world;
		BaseRecord book;
		String bookOid;
		/// The scenes' objectIds in index order.
		final List<String> sceneOids = new ArrayList<>();
	}

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		admin = orgContext.getAdminUser();
		assertNotNull("Admin is null", admin);
		owner = getCreateUser(OWNER_NAME);
		reader = getCreateUser(READER_NAME);
		stranger = getCreateUser(STRANGER_NAME);
		assertNotNull("Owner is null", owner);
		assertNotNull("Reader is null", reader);
		assertNotNull("Stranger is null", stranger);
		assertNotEquals("owner and stranger must be different users", owner.get(FieldNames.FIELD_ID), stranger.get(FieldNames.FIELD_ID));
		olioUser = ioContext.getFactory().getCreateUser(admin, OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("Olio principal is null", olioUser);
		bootstrapOlioRoot();
		ensureBooksUniverse();
	}

	// ─────────────────────────────── fixture (TestPictureBookCoverRoutes' shape) ───────────────────────────────

	private void bootstrapOlioRoot() {
		BaseRecord rootDir = ioContext.getPathUtil().makePath(admin, ModelNames.MODEL_GROUP, olioCfg.getBasePath(), "DATA", orgId);
		assertNotNull("Olio root group is null", rootDir);
		ioContext.getAuthorizationUtil().setEntitlement(admin, olioUser, new BaseRecord[] { rootDir }, new String[] { "Read", "Update", "Create" }, DATA_AND_GROUP);
		BaseRecord uDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, olioCfg.getUniversePath(), "DATA", orgId);
		assertNotNull("Universes group is null", uDir);
	}

	private void ensureBooksUniverse() {
		BaseRecord universe = WorldUtil.getCreateWorld(olioUser, olioCfg.getUniversePath(), PbOlioContextUtil.BOOKS_UNIVERSE, new String[0]);
		assertNotNull("Books universe world is null", universe);
	}

	private String booksWorldsPath() {
		return olioCfg.getUniversePath() + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/Worlds";
	}

	/// An olio-owned book in its own Books-universe world with the Workflow and Artifacts groups a real createBook's
	/// BookWorldInitializationRule builds, the owner enrolled as Writer with CRUD on the book group and the whole
	/// world tree, and - with {@code withReader} - the reader enrolled as Reader with Read on the same.
	private Fixture createWorldBook(boolean withReader) throws Exception {
		Fixture f = new Fixture();
		f.slug = "p10br-" + UUID.randomUUID().toString().substring(0, 12);
		String worldsPath = booksWorldsPath();

		BaseRecord created = WorldUtil.getCreateWorld(olioUser, worldsPath, f.slug, new String[0]);
		assertNotNull("Book world is null", created);
		f.world = WorldUtil.findWorld(olioUser, worldsPath, f.slug);
		assertNotNull("Book world could not be re-found", f.world);
		ioContext.getReader().populate(f.world, 2);

		String bookGroupPath = PbBookUtil.bookGroupPath(f.slug);
		BaseRecord bookDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, bookGroupPath, "DATA", orgId);
		assertNotNull("Book group is null", bookDir);
		/// The routes under test write the workflow, its RENDER_PLAN nodes and the run rows into {container}/Workflow,
		/// so the group must exist before the Writer is granted on the world tree (a plain user cannot create a
		/// sibling of the Book group under the olio-owned container - which is also why the bare book below cannot
		/// reach ensureWorkflow).
		assertNotNull("Workflow group is null", ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, PbBookUtil.workflowGroupPath(f.slug), "DATA", orgId));
		assertNotNull("Artifacts group is null", ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, PbBookUtil.artifactGroupPath(f.slug), "DATA", orgId));
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(olioUser, book, f.slug, bookGroupPath, orgId);
		book.set(FieldNames.FIELD_NAME, f.slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, f.slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
		book.set(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, owner.get(FieldNames.FIELD_OBJECT_ID));
		book.set(OlioFieldNames.FIELD_PB_WORLD, f.world);
		f.book = IOSystem.getActiveContext().getAccessPoint().create(olioUser, book);
		assertNotNull("Failed to create the olio-owned book", f.book);
		f.bookOid = oid(f.book);
		assertNotNull("Created book carries no objectId", f.bookOid);

		BaseRecord worldContainer = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, worldsPath + "/" + f.slug, "DATA", orgId);
		assertNotNull("World container group is null", worldContainer);

		BaseRecord writerRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, PbOlioContextUtil.writerRolePath(f.slug), RoleEnumType.USER.toString(), orgId);
		assertNotNull("Writer role is null", writerRole);
		assertTrue("Owner could not be enrolled as Writer", ioContext.getMemberUtil().member(olioUser, writerRole, owner, null, true));
		ioContext.getAuthorizationUtil().setEntitlement(admin, writerRole, new BaseRecord[] { bookDir }, CRUD, DATA_AND_GROUP);
		assertTrue("World tree carries no child groups", grantNestedGroups(writerRole, worldContainer, CRUD) > 0);
		ioContext.getAuthorizationUtil().setEntitlement(admin, writerRole, f.world, READ, PermissionEnumType.DATA.toString());

		if(withReader) {
			BaseRecord readerRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, PbOlioContextUtil.BOOK_ROLE_BASE + "/" + f.slug + "/Reader", RoleEnumType.USER.toString(), orgId);
			assertNotNull("Reader role is null", readerRole);
			assertTrue("Reader could not be enrolled", ioContext.getMemberUtil().member(olioUser, readerRole, reader, null, true));
			ioContext.getAuthorizationUtil().setEntitlement(admin, readerRole, new BaseRecord[] { bookDir }, READ, DATA_AND_GROUP);
			grantNestedGroups(readerRole, worldContainer, READ);
			ioContext.getAuthorizationUtil().setEntitlement(admin, readerRole, f.world, READ, PermissionEnumType.DATA.toString());
		}
		ServiceUtil.clearCache();
		return f;
	}

	private int grantNestedGroups(BaseRecord role, BaseRecord dir, String[] perms) {
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, dir.get(FieldNames.FIELD_ID), orgId);
		pq.setCache(false);
		BaseRecord[] dirs = ioContext.getSearch().findRecords(pq);
		int count = 0;
		for(BaseRecord g : dirs) {
			ioContext.getAuthorizationUtil().setEntitlement(admin, role, new BaseRecord[] { g }, perms, DATA_AND_GROUP);
			count += 1 + grantNestedGroups(role, g, perms);
		}
		return count;
	}

	private void cleanup(Fixture f) {
		if(f == null) {
			return;
		}
		try {
			if(f.book != null && !IOSystem.getActiveContext().getAccessPoint().delete(olioUser, f.book)) {
				logger.error("Cleanup: could not delete book " + f.slug);
			}
		}
		catch(Exception e) {
			logger.error("Cleanup: book " + f.slug + ": " + e.getMessage());
		}
		try {
			BaseRecord world = WorldUtil.findWorld(olioUser, booksWorldsPath(), f.slug);
			if(world != null) {
				ioContext.getReader().populate(world, 2);
				if(!WorldUtil.deleteWorld(olioUser, world)) {
					logger.error("Cleanup: could not delete world " + f.slug);
				}
			}
		}
		catch(Exception e) {
			logger.error("Cleanup: world " + f.slug + ": " + e.getMessage());
		}
	}

	/// A bare book row owned by the owner in a group the owner owns (TestPbRouteGateOrder's fixture): no world,
	/// no Workflow group, so only the refusals that precede ensureWorkflow are exercised on it.
	private BaseRecord createBareBook() throws Exception {
		String slug = "p10bb-" + UUID.randomUUID().toString().substring(0, 12);
		BaseRecord dir = ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, BARE_GROUP_PATH, "DATA", orgId);
		assertNotNull("Bare fixture group is null", dir);
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(owner, book, slug, BARE_GROUP_PATH, orgId);
		book.set(FieldNames.FIELD_NAME, slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, book);
		assertNotNull("Failed to create the bare book fixture", created);
		assertNotNull("Created bare book carries no objectId", oid(created));
		return created;
	}

	private void deleteBareBook(BaseRecord book) {
		if(book != null) {
			assertTrue("Failed to delete the bare book fixture", IOSystem.getActiveContext().getAccessPoint().delete(owner, book));
		}
	}

	private BaseRecord bookAsOwner(Fixture f) {
		BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
		assertNotNull("the world book must read back as the owner", book);
		return book;
	}

	/// A page of the world book created as the owner (Writer) with its setting patched in; its objectId is recorded
	/// on the fixture in index order.
	private BaseRecord addScene(Fixture f, int index) throws Exception {
		BaseRecord book = bookAsOwner(f);
		BaseRecord created = PbBookUtil.createScene(owner, book, index, "Page " + index, PbBookUtil.bookGroupPath(f.slug));
		assertNotNull("Failed to create page " + index, created);
		String sceneOid = oid(created);
		assertNotNull("Page carries no objectId", sceneOid);
		BaseRecord scene = PbBookUtil.readScene(owner, sceneOid, orgId);
		assertNotNull("Page must read back as its owner", scene);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SETTING });
		patch.set(FieldNames.FIELD_ID, scene.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, sceneOid);
		patch.set(FieldNames.FIELD_NAME, scene.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_SETTING, SCENE_SETTING);
		assertNotNull("scene patch must persist", IOSystem.getActiveContext().getAccessPoint().update(owner, patch));
		scene = PbBookUtil.readScene(owner, sceneOid, orgId);
		assertNotNull("Patched page must read back", scene);
		assertEquals("the setting must be stored", SCENE_SETTING, scene.get(OlioFieldNames.FIELD_PB_SETTING));
		f.sceneOids.add(sceneOid);
		return scene;
	}

	/// Nothing the refused calls may have written: no workflow as the owner sees it, and GET /workflow as the owner
	/// is still the 404 "no workflow yet".
	private void assertNoWorkflow(Fixture f, String what) {
		assertNull(what + ": no workflow may exist", PbGraphUtil.findWorkflow(owner, bookAsOwner(f)));
		refused(view(owner, f.bookOid), 404, NO_WORKFLOW, what + ": owner GET /workflow");
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPictureBookNewBookRoutes.class.getClassLoader(),
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

	private Response view(BaseRecord u, String bookOid) {
		return service.getWorkflow(bookOid, requestAs(u));
	}

	private Response render(BaseRecord u, String bookOid, String sceneOid) {
		return service.renderScene(bookOid, sceneOid, false, requestAs(u), SD_CONTEXT);
	}

	private Response build(BaseRecord u, String bookOid) {
		return service.buildGraph(bookOid, requestAs(u));
	}

	private Response renderAll(BaseRecord u, String bookOid) {
		return service.renderAll(bookOid, requestAs(u), SD_CONTEXT);
	}

	private String oid(BaseRecord rec) {
		return (rec != null && rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
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

	private String errorText(Response r) {
		Map<String, Object> m = body(r);
		Object msg = m.get("message");
		if(msg == null) {
			msg = m.get("error");
		}
		assertNotNull("Refusal body must carry 'error' or 'message': " + r.getEntity(), msg);
		return String.valueOf(msg);
	}

	private void refused(Response r, int status, String contains, String what) {
		assertEquals(what + " status, body: " + r.getEntity(), status, r.getStatus());
		String msg = errorText(r);
		assertTrue(what + " message must contain '" + contains + "' but was: " + msg, msg.contains(contains));
	}

	/// A refusal that must NOT be the workflow gate's: the UAT blocker was precisely this text on a book with scenes.
	private void refusedNotByTheWorkflowGate(Response r, int status, String contains, String what) {
		refused(r, status, contains, what);
		String msg = errorText(r);
		assertFalse(what + " must not be the retired workflow gate's refusal: " + msg, msg.contains(NO_WORKFLOW));
	}

	private Map<String, Object> ok(Response r, String what) {
		assertEquals(what + " must be 200, body: " + r.getEntity(), 200, r.getStatus());
		return body(r);
	}

	private Map<String, Object> accepted(Response r, String what) {
		assertEquals(what + " must be 202, body: " + r.getEntity(), 202, r.getStatus());
		return body(r);
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
		Object o = m.get(key);
		assertNotNull("'" + key + "' must be present: " + m, o);
		assertTrue("'" + key + "' must be a number, got " + o.getClass().getSimpleName(), o instanceof Number);
		return ((Number) o).intValue();
	}

	private static String str(Map<String, Object> m, String key) {
		Object o = (m != null ? m.get(key) : null);
		return (o != null ? String.valueOf(o) : null);
	}

	private static void assertFlag(Map<String, Object> m, String key, boolean expected) {
		assertEquals("'" + key + "' in " + m, Boolean.valueOf(expected), m.get(key));
	}

	/// The handles of the nodes a GET /workflow body carries (absent entirely when the graph has no nodes).
	@SuppressWarnings("unchecked")
	private List<String> handlesOf(Map<String, Object> view) {
		List<String> out = new ArrayList<>();
		for(Object o : list(view, "nodes")) {
			assertTrue("a node entry must be a JSON object, got " + o.getClass().getSimpleName(), o instanceof Map);
			out.add(str((Map<String, Object>) o, "handle"));
		}
		return out;
	}

	/// The 202 body's submit DTO, asserted the way the reader reads it: a job and a run exist, the kind is the one the
	/// route promises, and the plan counts are ints. Returns the jobId.
	private String assertSubmitted(Map<String, Object> dto, String kind, String what) {
		String jobId = str(dto, "jobId");
		assertNotNull(what + ": jobId is reported: " + dto, jobId);
		assertNotNull(what + ": runObjectId is reported: " + dto, str(dto, "runObjectId"));
		assertEquals(what + ": kind", kind, str(dto, "kind"));
		num(dto, "plannedNodeCount");
		num(dto, "reusedNodeCount");
		num(dto, "pinnedNodeCount");
		return jobId;
	}

	/// The 202 body's job, awaited through the registry AS THE SUBMITTING USER until it is terminal. Its terminal
	/// status is logged, never asserted: SD_CONTEXT is a loopback port nobody listens on, so the job's one stage
	/// fails by design (the job itself completes), and how long that takes is an observation (small devices),
	/// not a threshold.
	private AsyncJob awaitRenderJob(Map<String, Object> submit, String what) {
		String jobId = str(submit, "jobId");
		assertNotNull(what + ": jobId is reported: " + submit, jobId);
		String st = str(submit, "jobStatus");
		assertTrue(what + ": jobStatus is the registry's lowercase status, got " + st,
			"queued".equals(st) || "running".equals(st) || "completed".equals(st) || "failed".equals(st));
		long started = System.currentTimeMillis();
		AsyncJob job = PbRenderJobUtil.awaitTerminal(owner, jobId, JOB_CEILING_MS);
		long elapsed = System.currentTimeMillis() - started;
		assertNotNull(what + ": the submitting user must see job " + jobId + " settle within the ceiling (" + elapsed + " ms)", job);
		logger.info(what + ": job " + jobId + " " + job.getStatus() + " after " + elapsed + " ms"
			+ (job.getError() != null ? " error=" + job.getError() : ""));
		return job;
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/// Without a principal every route is 401 before the book is even looked up - and before the SD init-params
	/// are consulted.
	@Test
	public void TestNoPrincipalIs401() {
		HttpServletRequestMock anon = new HttpServletRequestMock();
		refused(service.renderScene(NIL_UUID, NIL_UUID, false, anon, SD_CONTEXT), 401, NO_PRINCIPAL, "anonymous POST scene render");
		refused(service.buildGraph(NIL_UUID, anon), 401, NO_PRINCIPAL, "anonymous POST graph build");
		refused(service.renderAll(NIL_UUID, anon, SD_CONTEXT), 401, NO_PRINCIPAL, "anonymous POST render-all");
		refused(service.getWorkflow(NIL_UUID, anon), 401, NO_PRINCIPAL, "anonymous GET workflow");
	}

	/// Gate order: an unknown book is 404 before any later gate is consulted, so a refusal never discloses whether a
	/// book the caller cannot see exists.
	@Test
	public void TestUnknownBookIs404() {
		refused(render(owner, NIL_UUID, NIL_UUID), 404, NOT_FOUND, "scene render on an unknown book");
		refused(build(owner, NIL_UUID), 404, NOT_FOUND, "graph build on an unknown book");
		refused(renderAll(owner, NIL_UUID), 404, NOT_FOUND, "render-all on an unknown book");
	}

	/// A user with no grant on the book cannot tell it exists: 404 (not 403) on every route, and nothing is written.
	@Test
	public void TestStrangerIs404AndWritesNothing() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			String s0 = oid(addScene(f, 0));
			assertNull("precondition: the stranger cannot read the book", PbBookUtil.readBook(stranger, f.bookOid, orgId));
			refused(render(stranger, f.bookOid, s0), 404, NOT_FOUND, "stranger scene render");
			refused(build(stranger, f.bookOid), 404, NOT_FOUND, "stranger graph build");
			refused(renderAll(stranger, f.bookOid), 404, NOT_FOUND, "stranger render-all");
			refused(view(stranger, f.bookOid), 404, NOT_FOUND, "stranger GET workflow");
			assertNoWorkflow(f, "after the stranger's refusals");
		}
		finally {
			cleanup(f);
		}
	}

	/// A Reader (Read on the book group and the world tree, nothing more) may look but not build or render: 403
	/// naming the action, nothing written - and the gate answers before the scene is even resolved, so a reader
	/// learns nothing about the scene list from a render.
	@Test
	public void TestReaderIs403AndWritesNothing() throws Exception {
		Fixture f = createWorldBook(true);
		try {
			String s0 = oid(addScene(f, 0));
			assertNotNull("precondition: the reader reads the book", PbBookUtil.readBook(reader, f.bookOid, orgId));
			refusedNotByTheWorkflowGate(render(reader, f.bookOid, s0), 403, REFUSED_RENDER, "reader scene render");
			refusedNotByTheWorkflowGate(render(reader, f.bookOid, NIL_UUID), 403, REFUSED_RENDER, "reader scene render of an unknown scene is the 403, not the scene's 404");
			refusedNotByTheWorkflowGate(build(reader, f.bookOid), 403, REFUSED_BUILD, "reader graph build");
			refusedNotByTheWorkflowGate(renderAll(reader, f.bookOid), 403, REFUSED_RENDER_ALL, "reader render-all");
			refused(view(reader, f.bookOid), 404, NO_WORKFLOW, "reader GET workflow of a never-built book");
			assertNoWorkflow(f, "after the reader's refusals");
		}
		finally {
			cleanup(f);
		}
	}

	/// An unknown scene, or a scene of another book, is the scene's 404 after the book gates and BEFORE the workflow
	/// is created: the owner's refused render leaves no workflow behind.
	@Test
	public void TestUnknownSceneIs404AndWritesNothing() throws Exception {
		Fixture f = createWorldBook(false);
		Fixture other = null;
		try {
			addScene(f, 0);
			other = createWorldBook(false);
			String foreign = oid(addScene(other, 0));
			refusedNotByTheWorkflowGate(render(owner, f.bookOid, NIL_UUID), 404, SCENE_NOT_FOUND, "render of an unknown scene");
			refusedNotByTheWorkflowGate(render(owner, f.bookOid, foreign), 404, SCENE_NOT_FOUND, "render of another book's scene");
			assertNoWorkflow(f, "after the scene 404s");
			assertNoWorkflow(other, "the other book, whose scene was addressed through the wrong book");
		}
		finally {
			cleanup(other);
			cleanup(f);
		}
	}

	/// A book with no scenes at all: render-all is the 409 that names the fix, and it is the one refusal that comes
	/// BEFORE ensureWorkflow, so a scene-less book stays workflow-less (GET /workflow still 404). The bare fixture has
	/// no Workflow group the owner could write into, which proves the order the hard way: had the route created the
	/// workflow first, this would be a 500, not the 409.
	@Test
	public void TestRenderAllOfASceneLessBookIs409AndWritesNothing() throws Exception {
		BaseRecord bare = createBareBook();
		try {
			String bookOid = oid(bare);
			refusedNotByTheWorkflowGate(renderAll(owner, bookOid), 409, NO_SCENES, "render-all of a scene-less bare book");
			refused(view(owner, bookOid), 404, NO_WORKFLOW, "GET workflow of the bare book after the 409");
			assertNull("no workflow may exist on the bare book", PbGraphUtil.findWorkflow(owner, PbBookUtil.readBook(owner, bookOid, orgId)));
		}
		finally {
			deleteBareBook(bare);
		}
	}

	/// The canvas's "Build graph" on a book that has no scenes yet (a chapter made through POST /chapter before any
	/// extraction): 200 with sceneCount 0 and nodesMaterialized 0, the workflow created, GET /workflow then 200
	/// with no nodes, a repeat build idempotent on the same workflow - and render-all still the 409 "no scenes".
	@Test
	public void TestBuildGraphOnASceneLessBookIs200() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			refused(view(owner, f.bookOid), 404, NO_WORKFLOW, "precondition: GET workflow before any build");
			Map<String, Object> b = ok(build(owner, f.bookOid), "graph build of a scene-less book");
			assertEquals("bookObjectId", f.bookOid, str(b, "bookObjectId"));
			String wfOid = str(b, "workflowObjectId");
			assertNotNull("workflowObjectId is reported: " + b, wfOid);
			assertEquals("sceneCount", 0, num(b, "sceneCount"));
			assertEquals("nodesMaterialized", 0, num(b, "nodesMaterialized"));

			Map<String, Object> v = ok(view(owner, f.bookOid), "GET workflow after the build");
			assertEquals("the view is of the workflow the build created", wfOid, str(v, "workflowObjectId"));
			assertTrue("the view carries no nodes: " + v.get("nodes"), handlesOf(v).isEmpty());

			Map<String, Object> again = ok(build(owner, f.bookOid), "repeat graph build");
			assertEquals("a repeat build finds the same workflow", wfOid, str(again, "workflowObjectId"));
			assertEquals("a repeat build materializes nothing", 0, num(again, "nodesMaterialized"));

			refusedNotByTheWorkflowGate(renderAll(owner, f.bookOid), 409, NO_SCENES, "render-all of a scene-less book that has a workflow");
		}
		finally {
			cleanup(f);
		}
	}

	/// THE UAT BLOCKER AT THE REST LAYER. A new book with scenes and NO workflow (GET /workflow 404, as the reader
	/// finds it right after the wizard): the first scene render is a 202 that materializes the workflow and the
	/// scene's RENDER_PLAN node (`nodeMaterialized:true`, one node planned) - never the 404 "no workflow yet";
	/// the view then shows exactly that node; Build graph materializes the remaining scenes' nodes and is a no-op
	/// on repeat; render-all plans every scene with `scenesWithoutNode` empty and materializes nothing more; and a
	/// second render of a scene whose node exists reports `nodeMaterialized:false` with the same node.
	@Test
	public void TestNewBookRendersWithoutAGraphBuild() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			final int n = 3;
			for(int i = 0; i < n; i++) {
				addScene(f, i);
			}
			String s0 = f.sceneOids.get(0);
			refused(view(owner, f.bookOid), 404, NO_WORKFLOW, "precondition: a new book has no workflow");

			/// 1. First render from the reader, straight after the wizard.
			Map<String, Object> r0 = accepted(render(owner, f.bookOid, s0), "first scene render of a new book");
			assertEquals("sceneObjectId", s0, str(r0, "sceneObjectId"));
			assertEquals("sceneIndex", 0, num(r0, "sceneIndex"));
			assertFlag(r0, "nodeMaterialized", true);
			String node0 = str(r0, "nodeObjectId");
			assertNotNull("nodeObjectId is reported: " + r0, node0);
			assertSubmitted(r0, PbRenderJobUtil.KIND_RENDER, "first scene render");
			assertEquals("one node planned: the scene's own RENDER_PLAN node", 1, num(r0, "plannedNodeCount"));
			assertEquals("nothing reused on a first render", 0, num(r0, "reusedNodeCount"));
			assertEquals("nothing pinned on a new book", 0, num(r0, "pinnedNodeCount"));
			assertFalse("the 202 body must not carry the retired workflow gate's text: " + r0, r0.toString().contains(NO_WORKFLOW));

			Map<String, Object> v1 = ok(view(owner, f.bookOid), "GET workflow after the first render");
			List<String> h1 = handlesOf(v1);
			assertEquals("the view shows exactly the one node the render materialized: " + h1, 1, h1.size());
			assertEquals("that node is scene 0's RENDER_PLAN node", PbPipelineUtil.renderPlanHandle(s0), h1.get(0));
			String wfOid = str(v1, "workflowObjectId");
			assertNotNull("the view names the workflow", wfOid);
			awaitRenderJob(r0, "first scene render");

			/// 2. Build graph: the remaining scenes' nodes, then idempotent.
			Map<String, Object> b1 = ok(build(owner, f.bookOid), "graph build after one render");
			assertEquals("bookObjectId", f.bookOid, str(b1, "bookObjectId"));
			assertEquals("the build finds the workflow the render created", wfOid, str(b1, "workflowObjectId"));
			assertEquals("sceneCount", n, num(b1, "sceneCount"));
			assertEquals("the build materializes the nodes the render did not", n - 1, num(b1, "nodesMaterialized"));
			Map<String, Object> b2 = ok(build(owner, f.bookOid), "repeat graph build");
			assertEquals("a repeat build materializes nothing", 0, num(b2, "nodesMaterialized"));
			assertEquals("a repeat build still counts every scene", n, num(b2, "sceneCount"));

			Map<String, Object> v2 = ok(view(owner, f.bookOid), "GET workflow after the build");
			List<String> h2 = handlesOf(v2);
			assertEquals("one node per scene: " + h2, n, h2.size());
			for(String sceneOid : f.sceneOids) {
				assertTrue("scene " + sceneOid + " has its RENDER_PLAN node in the view: " + h2, h2.contains(PbPipelineUtil.renderPlanHandle(sceneOid)));
			}

			/// 3. Render all: every scene planned, none without a node, nothing more materialized. The first job
			/// settled FAILED (loopback SD), and a FAILED node is executed again, so every terminal is planned.
			Map<String, Object> ra = accepted(renderAll(owner, f.bookOid), "render-all");
			assertEquals("sceneCount", n, num(ra, "sceneCount"));
			assertTrue("scenesWithoutNode is empty (absent on the wire): " + ra.get("scenesWithoutNode"), list(ra, "scenesWithoutNode").isEmpty());
			assertEquals("render-all materializes nothing the build did not", 0, num(ra, "nodesMaterialized"));
			assertSubmitted(ra, PbRenderJobUtil.KIND_RENDER_ALL, "render-all");
			assertEquals("every scene's node is planned or reused, nothing else is in a scene-only graph",
				n, num(ra, "plannedNodeCount") + num(ra, "reusedNodeCount"));
			assertTrue("the never-rendered scenes are planned: " + ra, num(ra, "plannedNodeCount") >= n - 1);
			assertEquals("nothing pinned", 0, num(ra, "pinnedNodeCount"));
			awaitRenderJob(ra, "render-all");

			/// 4. A second single-scene render: the node exists, so nothing is materialized and it is the same node.
			String s1 = f.sceneOids.get(1);
			Map<String, Object> r1 = accepted(render(owner, f.bookOid, s1), "render of a scene whose node exists");
			assertEquals("sceneObjectId", s1, str(r1, "sceneObjectId"));
			assertFlag(r1, "nodeMaterialized", false);
			assertNotNull("nodeObjectId is reported: " + r1, str(r1, "nodeObjectId"));
			assertSubmitted(r1, PbRenderJobUtil.KIND_RENDER, "second scene render");
			awaitRenderJob(r1, "second scene render");

			Map<String, Object> v3 = ok(view(owner, f.bookOid), "GET workflow at the end");
			assertEquals("still one node per scene", n, handlesOf(v3).size());
			assertEquals("still the one workflow", wfOid, str(v3, "workflowObjectId"));
		}
		finally {
			cleanup(f);
		}
	}
}
