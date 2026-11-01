package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
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
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbCoverUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
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
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// P9-3 / P9-4 (PictureBook V3 Implementation §17, design §40): the six cover routes on PictureBookService exercised
/// through the real service methods -> PbServiceFacade -> PbCoverUtil / PbComposeUtil / PbRenderPlanUtil /
/// PbRenderJobUtil -> real AccessPoint/PBAC -> the real (H2) database, with HttpServletRequestMock + UserPrincipal
/// exactly as TestPictureBookCompositionRoutes does. Nothing is mocked; no asserting actor is ever the admin or the
/// olio principal.
///
///   GET    /{book}/covers                     covers       200 {bookObjectId, workflowObjectId, covers:{PURPOSE: entry}}
///   POST   /{book}/covers/{purpose}           createCover  200 {created, existingReturned, source, compositionObjectId, ...}
///   POST   /{book}/covers/{purpose}/render    renderCover  202 {nodeObjectId, nodeMaterialized, jobId, runObjectId, ...}
///   GET    /{book}/covers/{purpose}           cover        200 {bookObjectId, purpose, compositionObjectId, composition, actors, elements, cover}   (P9-4)
///   PATCH  /{book}/covers/{purpose}           patchCover   200 {compositionObjectId, revision, nodeStale, nodeStatus, purpose, updated, composition}   (P9-4)
///   POST   /{book}/covers/{purpose}/plan      planCover    200 the RenderPlan + estimate + contentHash + planSummary + purpose + compositionObjectId   (P9-4)
///
/// P9-4 WHAT THE BOARD / PATCH / PLAN CASES PROVE. The board is a read keyed by purpose (a reader sees it, the render
/// path does not gate it); the patch is the §40 edit path with titleSafeBand among its keys, bumps the revision and
/// marks the cover's own composition_plan@ node STALE once one exists (a FAILED node from the loopback render goes
/// STALE; a book that never rendered reports nodeStale:false); the plan is built from the composition's own text,
/// refuses an empty MANUAL cover with the 400 that names it, carries the title-safe band as the reserved box and
/// never the SD URL. The SD inventory is unreadable here (loopback), which is the MODEL_SUBSTITUTED degradation
/// naming INVENTORY_UNAVAILABLE that TestPictureBookPlanRoute documents - the plan still builds and compiles.
///
/// FIXTURE. TestPictureBookCompositionRoutes' hand-built H2 world fixture (an olio-owned book in a Books-universe
/// world, a Writer role holding the owner with CRUD on the book group and the world tree, an optional Reader role)
/// with its own user names and slug prefix so the classes never share rows - plus the world's Workflow and
/// Artifacts groups, which PbBookUtil.createBook's BookWorldInitializationRule builds and this fixture has to build
/// itself (as the olio principal, before the nested-group grants) so the render route's one write - the workflow,
/// its RENDER_PLAN node and the run row, all in {container}/Workflow - is an authorized write by the Writer.
///
/// WHAT THE RENDER CASE PROVES HERE. The 202 contract: the node is materialized, the run and job exist, the view
/// then shows the node, and a pinned node is a 409 before any job. SD_CONTEXT points at an unreachable loopback
/// server, so the job itself settles FAILED inside the registry (logged, never asserted - the job's failure is not
/// the route's). The rendered cover against Swarm .39 is TestPbCoverRenderLive (Objects7, am7db) and the Phase 9
/// exit criterion TestPbCoverExitCriterionLive (P9-5).
///
/// AUTO (the LLM-staged cover) is not called here with a resolvable config: the gate case asserts the 404 of a named
/// missing config and the idempotency case proves an existing cover short-circuits AUTO without an LLM call. The
/// LLM-bound AUTO path is TestPbCoverUtil (Objects7, .42).
///
/// WIRE NOTE (measured, not a defect under test): JSONUtil.exportObject serialises with Include.NON_EMPTY, so a
/// null entry (a purpose with no composition), an empty `covers` map, a null `nodeObjectId` / `workflowObjectId` and
/// empty `actors` / `elements` lists are ABSENT from a body rather than present as null / {} / []; booleans
/// (`created`, `existingReturned`, `pinned`) and ints (`chainLength`, `plannedNodeCount`) ARE present.
public class TestPictureBookCoverRoutes extends BaseTest {

	private static final String OWNER_NAME = "pbcovrtowner";
	private static final String READER_NAME = "pbcovrtreader";
	private static final String STRANGER_NAME = "pbcovrtstranger";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String[] DATA_AND_GROUP = new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() };
	private static final String[] CRUD = new String[] { "Read", "Update", "Create", "Delete" };
	private static final String[] READ = new String[] { "Read" };
	private static final String SCENE_SETTING = "The lighthouse keeper counts the ships that never came back";
	private static final String UNREACHABLE_SD = "http://127.0.0.1:1";
	private static final String NOT_FOUND = "Book not found";
	private static final String NO_PRINCIPAL = "No authenticated principal";
	private static final String MALFORMED = "Malformed request body";
	private static final String REFUSED_CREATE = "Not authorized to create cover art in this book";
	private static final String REFUSED_RENDER = "Not authorized to render cover art in this book";
	private static final String REFUSED_EDIT = "Not authorized to edit cover art in this book";
	private static final String NO_COVER = "has no COVER composition";
	private static final String NO_DESCRIPTION = "has no description to render from";
	private static final String COVER_SETTING = "A lighthouse at the end of a drowned causeway, its lamp lit against a copper dusk";
	private static final String BAD_PURPOSE = "Purpose must be one of COVER, TITLE_PAGE, CHAPTER_OPENER, SERIES_ART";
	private static final String BAD_MODE = "Mode must be one of";
	private static final String MISSING_CONFIG = "p93r-no-such-chat-config";
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
	private String pb0;
	private String cb0;

	private static final class Fixture {
		String slug;
		BaseRecord world;
		BaseRecord book;
		String bookOid;
	}

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		pb0 = PbRenderPath.pictureBook();
		cb0 = PbRenderPath.chapBook();
		orgId = orgContext.getOrganizationId();
		admin = orgContext.getAdminUser();
		assertNotNull("Admin is null", admin);
		owner = getCreateUser(OWNER_NAME);
		reader = getCreateUser(READER_NAME);
		stranger = getCreateUser(STRANGER_NAME);
		assertNotNull("Owner is null", owner);
		assertNotNull("Reader is null", reader);
		assertNotNull("Stranger is null", stranger);
		olioUser = ioContext.getFactory().getCreateUser(admin, OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("Olio principal is null", olioUser);
		bootstrapOlioRoot();
		ensureBooksUniverse();
	}

	/// The switch is process-global and other classes in this JVM depend on it: always restored.
	@After
	public void restoreRenderPath() {
		PbRenderPath.configure(pb0, cb0);
	}

	private void v3() {
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
	}

	private void v1() {
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
	}

	// ─────────────────────────────── fixture (TestPictureBookCompositionRoutes' shape + the Workflow / Artifacts groups) ───────────────────────────────

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

	private Fixture createWorldBook(boolean withReader) throws Exception {
		Fixture f = new Fixture();
		f.slug = "p93r-" + UUID.randomUUID().toString().substring(0, 12);
		String worldsPath = booksWorldsPath();

		BaseRecord created = WorldUtil.getCreateWorld(olioUser, worldsPath, f.slug, new String[0]);
		assertNotNull("Book world is null", created);
		f.world = WorldUtil.findWorld(olioUser, worldsPath, f.slug);
		assertNotNull("Book world could not be re-found", f.world);
		ioContext.getReader().populate(f.world, 2);

		String bookGroupPath = PbBookUtil.bookGroupPath(f.slug);
		BaseRecord bookDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, bookGroupPath, "DATA", orgId);
		assertNotNull("Book group is null", bookDir);
		/// What BookWorldInitializationRule builds for a real createBook: the render route writes the workflow, its
		/// node and the run row into {container}/Workflow, so the group must exist before the Writer is granted on
		/// the world tree (a plain user cannot create a sibling of the Book group under the olio-owned container).
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

	private BaseRecord bookAsOwner(Fixture f) {
		BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
		assertNotNull("the world book must read back as the owner", book);
		return book;
	}

	/// A page of the world book created as the owner (Writer) with its setting patched in.
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
		return scene;
	}

	/// A MIGRATED composition of the scene (no LLM), written as the owner in the book group and re-read uncached.
	private BaseRecord compose(Fixture f, BaseRecord scene) {
		long t0 = System.currentTimeMillis();
		ComposeResult r = PbComposeUtil.composeMigrated(owner, bookAsOwner(f), scene, false);
		logger.info("composeMigrated scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX) + ": " + (System.currentTimeMillis() - t0)
			+ " ms written=" + r.isWritten() + " reason=" + r.failureReason);
		assertTrue("the MIGRATED composition is written: " + r.failureReason, r.isWritten());
		assertNotNull("composition", r.composition);
		BaseRecord comp = PbComposeUtil.readComposition(owner, oid(r.composition), orgId);
		assertNotNull("composition re-read as the owner", comp);
		return comp;
	}

	/// The book's cover-type composition of {@code purpose} as the owner reads it, uncached; null when there is none.
	private BaseRecord coverOf(Fixture f, PbCompositionPurposeEnumType purpose) {
		return PbCoverUtil.findCover(owner, bookAsOwner(f), purpose);
	}

	/// Nothing the refused calls may have written: no cover of any purpose and no workflow.
	private void assertNothingWritten(Fixture f, String what) {
		BaseRecord book = bookAsOwner(f);
		for(PbCompositionPurposeEnumType p : PbCompositionPurposeEnumType.values()) {
			if(PbCoverUtil.isCoverPurpose(p) && p != PbCompositionPurposeEnumType.SERIES_ART) {
				assertNull(what + ": no " + p + " composition may exist", PbCoverUtil.findCover(owner, book, p));
			}
		}
		assertNull(what + ": no workflow may exist", PbGraphUtil.findWorkflow(owner, book));
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPictureBookCoverRoutes.class.getClassLoader(),
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
		return service.covers(bookOid, requestAs(u));
	}

	private Response create(BaseRecord u, String bookOid, String purpose, String body) {
		return service.createCover(bookOid, purpose, body, requestAs(u), SD_CONTEXT);
	}

	private Response render(BaseRecord u, String bookOid, String purpose) {
		return service.renderCover(bookOid, purpose, false, requestAs(u), SD_CONTEXT);
	}

	/// P9-4: the board of one cover (GET /{book}/covers/{purpose}).
	private Response board(BaseRecord u, String bookOid, String purpose) {
		return service.cover(bookOid, purpose, requestAs(u));
	}

	/// P9-4: the row-level edit (PATCH /{book}/covers/{purpose}).
	private Response patch(BaseRecord u, String bookOid, String purpose, String body) {
		return service.patchCover(bookOid, purpose, body, requestAs(u));
	}

	/// P9-4: the plan (POST /{book}/covers/{purpose}/plan) against the loopback SD context.
	private Response plan(BaseRecord u, String bookOid, String purpose, String body) {
		return service.planCover(bookOid, purpose, body, requestAs(u), SD_CONTEXT);
	}

	private static String manual() {
		return json("mode", "manual");
	}

	/// The one patch body most P9-4 cases send: the cover's own setting text plus a title-safe band word.
	private static String settingAndBand(String band) {
		return json(OlioFieldNames.FIELD_PB_SETTING_TEXT, COVER_SETTING, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, band);
	}

	/// The compiled BASE prompt of a plan body.
	private String basePrompt(Map<String, Object> dto) {
		Map<String, Object> base = map(map(dto, "compiled"), RenderPlan.STAGE_BASE);
		assertTrue("compiled must carry a BASE entry: " + dto.get("compiled"), !base.isEmpty());
		return String.valueOf(base.get("prompt"));
	}

	/// The plan's reserved band, or null when the wire dropped it (NON_EMPTY: no band reserved).
	@SuppressWarnings("unchecked")
	private Map<String, Object> reservedOf(Map<String, Object> dto) {
		Object o = dto.get("reserved");
		if(o == null) {
			return null;
		}
		assertTrue("'reserved' must be a JSON object, got " + o.getClass().getSimpleName(), o instanceof Map);
		return (Map<String, Object>) o;
	}

	private static double dbl(Map<String, Object> m, String key) {
		Object o = m.get(key);
		assertNotNull("'" + key + "' must be present: " + m, o);
		assertTrue("'" + key + "' must be a number, got " + o.getClass().getSimpleName(), o instanceof Number);
		return ((Number) o).doubleValue();
	}

	private String oid(BaseRecord rec) {
		return (rec != null && rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	private static String json(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2) {
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return JSONUtil.exportObject(m);
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

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Map<String, Object> m, String key) {
		Object o = m.get(key);
		assertNotNull("'" + key + "' must be present: " + m, o);
		assertTrue("'" + key + "' must be a JSON object, got " + o.getClass().getSimpleName(), o instanceof Map);
		return (Map<String, Object>) o;
	}

	/// The `covers` map of a view body; empty when the wire dropped it (NON_EMPTY: a book with no covers).
	@SuppressWarnings("unchecked")
	private Map<String, Object> coversOf(Map<String, Object> view) {
		Object o = view.get("covers");
		if(o == null) {
			return Collections.emptyMap();
		}
		assertTrue("'covers' must be a JSON object, got " + o.getClass().getSimpleName(), o instanceof Map);
		return (Map<String, Object>) o;
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

	/// An enum field's stored word, upper-cased (lowercase on the wire, UPPERCASE in Java - compare case-insensitively).
	/// Bound to Object first: `String.valueOf(rec.get(...))` picks the char[] overload and throws ClassCastException.
	private static String enumWord(BaseRecord rec, String field) {
		Object val = rec.get(field);
		return (val != null ? val.toString().toUpperCase() : null);
	}

	private static void assertFlag(Map<String, Object> m, String key, boolean expected) {
		assertEquals("'" + key + "' in " + m, Boolean.valueOf(expected), m.get(key));
	}

	/// The 202 body's job, awaited through the registry AS THE SUBMITTING USER until it is terminal. Its terminal
	/// status is logged, never asserted: SD_CONTEXT is a loopback port nobody listens on, so the job fails inside
	/// the registry by design, and how long that takes is an observation (small devices), not a threshold.
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
		refused(service.covers(NIL_UUID, anon), 401, NO_PRINCIPAL, "anonymous GET covers");
		refused(service.createCover(NIL_UUID, "cover", manual(), anon, SD_CONTEXT), 401, NO_PRINCIPAL, "anonymous POST cover");
		refused(service.renderCover(NIL_UUID, "cover", false, anon, SD_CONTEXT), 401, NO_PRINCIPAL, "anonymous POST render");
		refused(service.cover(NIL_UUID, "cover", anon), 401, NO_PRINCIPAL, "anonymous GET cover board");
		refused(service.patchCover(NIL_UUID, "cover", settingAndBand("top"), anon), 401, NO_PRINCIPAL, "anonymous PATCH cover");
		refused(service.planCover(NIL_UUID, "cover", "{}", anon, SD_CONTEXT), 401, NO_PRINCIPAL, "anonymous POST plan");
	}

	/// A body that is not JSON is the transport's 400, before the facade sees the book.
	@Test
	public void TestMalformedBodyIs400() {
		v3();
		refused(create(owner, NIL_UUID, "cover", "{not json"), 400, MALFORMED, "malformed create");
		refused(create(owner, NIL_UUID, "cover", "[1,2]"), 400, MALFORMED, "array body create");
		refused(patch(owner, NIL_UUID, "cover", "{not json"), 400, MALFORMED, "malformed patch");
		refused(patch(owner, NIL_UUID, "cover", "[1,2]"), 400, MALFORMED, "array body patch");
		refused(plan(owner, NIL_UUID, "cover", "{not json"), 400, MALFORMED, "malformed plan");
		refused(plan(owner, NIL_UUID, "cover", "[1,2]"), 400, MALFORMED, "array body plan");
	}

	/// An unknown book is 404 on all six routes, write or read.
	@Test
	public void TestUnknownBookIs404() {
		v3();
		refused(view(owner, NIL_UUID), 404, NOT_FOUND, "GET covers of an unknown book");
		refused(create(owner, NIL_UUID, "cover", manual()), 404, NOT_FOUND, "create on an unknown book");
		refused(render(owner, NIL_UUID, "cover"), 404, NOT_FOUND, "render on an unknown book");
		refused(create(owner, NIL_UUID, "cover", null), 404, NOT_FOUND, "create with no body on an unknown book");
		refused(board(owner, NIL_UUID, "cover"), 404, NOT_FOUND, "GET cover board of an unknown book");
		refused(patch(owner, NIL_UUID, "cover", settingAndBand("top")), 404, NOT_FOUND, "patch on an unknown book");
		refused(patch(owner, NIL_UUID, "cover", null), 404, NOT_FOUND, "patch with no body on an unknown book is the 404, not the 400 body");
		refused(plan(owner, NIL_UUID, "cover", "{}"), 404, NOT_FOUND, "plan on an unknown book");
	}

	/// A user with no grant on the book cannot tell it exists: 404 (not 403) on every route, and nothing is written.
	@Test
	public void TestStrangerIs404AndWritesNothing() throws Exception {
		v3();
		Fixture f = createWorldBook(false);
		try {
			assertNull("precondition: the stranger cannot read the book", PbBookUtil.readBook(stranger, f.bookOid, orgId));
			refused(view(stranger, f.bookOid), 404, NOT_FOUND, "stranger GET covers");
			refused(create(stranger, f.bookOid, "cover", manual()), 404, NOT_FOUND, "stranger create");
			refused(render(stranger, f.bookOid, "cover"), 404, NOT_FOUND, "stranger render");
			refused(board(stranger, f.bookOid, "cover"), 404, NOT_FOUND, "stranger GET cover board");
			refused(patch(stranger, f.bookOid, "cover", settingAndBand("top")), 404, NOT_FOUND, "stranger patch");
			refused(plan(stranger, f.bookOid, "cover", "{}"), 404, NOT_FOUND, "stranger plan");
			assertNothingWritten(f, "after the stranger's calls");
			/// With a cover in place the stranger still cannot tell: the board, patch and plan stay the book's 404.
			String compOid = str(ok(create(owner, f.bookOid, "cover", manual()), "owner manual create"), "compositionObjectId");
			assertNotNull("composition objectId", compOid);
			refused(board(stranger, f.bookOid, "cover"), 404, NOT_FOUND, "stranger GET cover board of an existing cover");
			refused(patch(stranger, f.bookOid, "cover", settingAndBand("top")), 404, NOT_FOUND, "stranger patch of an existing cover");
			refused(plan(stranger, f.bookOid, "cover", "{}"), 404, NOT_FOUND, "stranger plan of an existing cover");
			BaseRecord stored = coverOf(f, PbCompositionPurposeEnumType.COVER);
			assertNotNull("the owner's cover is still there", stored);
			assertNull("the stranger's patch did not land", stored.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
		}
		finally {
			cleanup(f);
		}
	}

	/// On the v1 render path the three writes are the 409 refusal naming the path, before anything is written;
	/// the reads (view, board, plan) are not gated - they report what exists (nothing: a 404 naming the purpose).
	@Test
	public void TestV1BookRefusesWritesButViewReads() throws Exception {
		v1();
		Fixture f = createWorldBook(false);
		try {
			refused(create(owner, f.bookOid, "cover", manual()), 409, PbRenderPath.REFUSAL_PREFIX, "v1 create");
			refused(create(owner, f.bookOid, "title-page", manual()), 409, PbRenderPath.REFUSAL_PREFIX, "v1 create title page");
			refused(render(owner, f.bookOid, "cover"), 409, PbRenderPath.REFUSAL_PREFIX, "v1 render");
			refused(patch(owner, f.bookOid, "cover", settingAndBand("top")), 409, PbRenderPath.REFUSAL_PREFIX, "v1 patch");
			refused(patch(owner, f.bookOid, "poster", settingAndBand("top")), 409, PbRenderPath.REFUSAL_PREFIX, "v1 patch is the 409, not the 400 purpose");
			assertNothingWritten(f, "after the v1 refusals");
			Map<String, Object> v = ok(view(owner, f.bookOid), "v1 GET covers");
			assertEquals("the view names the book", f.bookOid, str(v, "bookObjectId"));
			assertNull("no workflow on a book that never rendered", str(v, "workflowObjectId"));
			assertTrue("no cover entries on a fresh book: " + v, coversOf(v).isEmpty());
			refused(board(owner, f.bookOid, "cover"), 404, NO_COVER, "v1 GET cover board is the read's 404, not the path's 409");
			refused(plan(owner, f.bookOid, "cover", "{}"), 404, NO_COVER, "v1 plan is the read's 404, not the path's 409");
		}
		finally {
			cleanup(f);
		}
	}

	/// A Reader can view the covers but neither create, render nor edit one: 403 naming the refused act, before the
	/// purpose is even parsed (so a reader cannot probe purposes either), and nothing is written. Once the owner has
	/// made a cover the reader's board and plan are 200 (reads), the patch is still the 403.
	@Test
	public void TestReaderViewsButCannotWrite() throws Exception {
		v3();
		Fixture f = createWorldBook(true);
		try {
			assertNotNull("precondition: the reader can read the book", PbBookUtil.readBook(reader, f.bookOid, orgId));
			Map<String, Object> v = ok(view(reader, f.bookOid), "reader GET covers");
			assertEquals("the view names the book", f.bookOid, str(v, "bookObjectId"));
			assertTrue("no cover entries on a fresh book: " + v, coversOf(v).isEmpty());
			refused(create(reader, f.bookOid, "cover", manual()), 403, REFUSED_CREATE, "reader create");
			refused(create(reader, f.bookOid, "poster", manual()), 403, REFUSED_CREATE, "reader create is the 403, not the 400 purpose");
			refused(render(reader, f.bookOid, "cover"), 403, REFUSED_RENDER, "reader render");
			refused(render(reader, f.bookOid, "poster"), 403, REFUSED_RENDER, "reader render is the 403, not the 400 purpose");
			refused(patch(reader, f.bookOid, "cover", settingAndBand("top")), 403, REFUSED_EDIT, "reader patch");
			refused(patch(reader, f.bookOid, "poster", settingAndBand("top")), 403, REFUSED_EDIT, "reader patch is the 403, not the 400 purpose");
			refused(board(reader, f.bookOid, "cover"), 404, NO_COVER, "reader GET cover board of no cover");
			assertNothingWritten(f, "after the reader's calls");

			String compOid = str(ok(create(owner, f.bookOid, "cover", manual()), "owner manual create"), "compositionObjectId");
			assertNotNull("composition objectId", compOid);
			ok(patch(owner, f.bookOid, "cover", settingAndBand("top")), "owner patch");
			Map<String, Object> b = ok(board(reader, f.bookOid, "cover"), "reader GET cover board");
			assertEquals("the reader's board names the composition", compOid, str(b, "compositionObjectId"));
			assertEquals("the reader sees the owner's text", COVER_SETTING, str(map(b, "composition"), OlioFieldNames.FIELD_PB_SETTING_TEXT));
			Map<String, Object> p = ok(plan(reader, f.bookOid, "cover", "{}"), "reader plan");
			assertEquals("the reader's plan names the composition", compOid, str(p, "compositionObjectId"));
			refused(patch(reader, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_SETTING_TEXT, "overwritten")), 403, REFUSED_EDIT, "reader patch of an existing cover");
			BaseRecord stored = coverOf(f, PbCompositionPurposeEnumType.COVER);
			assertEquals("the reader's patch did not land", COVER_SETTING, stored.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
		}
		finally {
			cleanup(f);
		}
	}

	/// The owner's bad words: a purpose that is not a cover purpose (unknown, or SCENE) is 400 on every purpose-keyed
	/// route, a bad mode is 400, AUTO with a named config that does not exist is 404 - and none writes a row.
	@Test
	public void TestBadPurposeModeAndConfigWriteNothing() throws Exception {
		v3();
		Fixture f = createWorldBook(false);
		try {
			refused(create(owner, f.bookOid, "poster", manual()), 400, BAD_PURPOSE, "create of an unknown purpose");
			refused(create(owner, f.bookOid, "scene", manual()), 400, BAD_PURPOSE, "create of the SCENE purpose");
			refused(render(owner, f.bookOid, "poster"), 400, BAD_PURPOSE, "render of an unknown purpose");
			refused(render(owner, f.bookOid, "scene"), 400, BAD_PURPOSE, "render of the SCENE purpose");
			refused(board(owner, f.bookOid, "poster"), 400, BAD_PURPOSE, "board of an unknown purpose");
			refused(board(owner, f.bookOid, "scene"), 400, BAD_PURPOSE, "board of the SCENE purpose");
			refused(patch(owner, f.bookOid, "poster", settingAndBand("top")), 400, BAD_PURPOSE, "patch of an unknown purpose");
			refused(patch(owner, f.bookOid, "scene", settingAndBand("top")), 400, BAD_PURPOSE, "patch of the SCENE purpose");
			refused(plan(owner, f.bookOid, "poster", "{}"), 400, BAD_PURPOSE, "plan of an unknown purpose");
			refused(plan(owner, f.bookOid, "scene", "{}"), 400, BAD_PURPOSE, "plan of the SCENE purpose");
			refused(create(owner, f.bookOid, "cover", json("mode", "sideways")), 400, BAD_MODE, "create with a bad mode");
			refused(create(owner, f.bookOid, "cover", json("chatConfigName", MISSING_CONFIG)), 404, "Chat config not found: " + MISSING_CONFIG, "AUTO with a missing named config");
			refused(create(owner, f.bookOid, "cover", json("mode", "auto", "chatConfigName", MISSING_CONFIG)), 404, "Chat config not found: " + MISSING_CONFIG, "explicit AUTO with a missing named config");
			assertNothingWritten(f, "after the bad words");
		}
		finally {
			cleanup(f);
		}
	}

	/// MANUAL: the first call creates an empty USER composition, every later call of any mode returns it untouched
	/// (existingReturned - so an existing cover short-circuits AUTO with no LLM call), and the view lists it with
	/// no node. The purpose words are route words: `cover`, `title-page`, `TITLE_PAGE` all resolve.
	@Test
	public void TestManualCoverIsIdempotentAndViewed() throws Exception {
		v3();
		Fixture f = createWorldBook(false);
		try {
			Map<String, Object> c = ok(create(owner, f.bookOid, "cover", manual()), "manual create");
			assertFlag(c, "created", true);
			assertFlag(c, "existingReturned", false);
			assertFlag(c, "written", true);
			assertFlag(c, "llmFailure", false);
			assertFlag(c, "hardFailure", false);
			assertEquals("purpose", "COVER", str(c, "purpose"));
			assertEquals("mode", "MANUAL", str(c, "mode"));
			assertEquals("source", "USER", str(c, "source"));
			String compOid = str(c, "compositionObjectId");
			assertNotNull("the created composition's objectId: " + c, compOid);
			Map<String, Object> comp = map(c, "composition");
			assertEquals("the composition dto carries the same objectId", compOid, str(comp, "objectId"));
			assertTrue("a manual cover has no actors", list(c, "actors").isEmpty());
			assertTrue("a manual cover has no elements", list(c, "elements").isEmpty());

			BaseRecord stored = coverOf(f, PbCompositionPurposeEnumType.COVER);
			assertNotNull("the COVER composition is persisted", stored);
			assertEquals("persisted objectId", compOid, oid(stored));
			assertNull("a cover composition has no scene", stored.get(OlioFieldNames.FIELD_PB_SCENE));
			assertEquals("persisted purpose", "COVER", enumWord(stored, OlioFieldNames.FIELD_PB_PURPOSE));

			Map<String, Object> again = ok(create(owner, f.bookOid, "cover", manual()), "manual create again");
			assertFlag(again, "created", false);
			assertFlag(again, "existingReturned", true);
			assertEquals("the same composition is returned", compOid, str(again, "compositionObjectId"));
			Map<String, Object> auto = ok(create(owner, f.bookOid, "COVER", null), "AUTO (no body) on an existing cover");
			assertFlag(auto, "created", false);
			assertFlag(auto, "existingReturned", true);
			assertFlag(auto, "llmFailure", false);
			assertEquals("AUTO returns the existing composition without composing", compOid, str(auto, "compositionObjectId"));
			/// The inputs are validated before the short-circuit (a bad scene is the 404 whether or not a cover exists) -
			/// then an existing cover is returned without cloning: the scene here has no composition, so reaching the
			/// clone path would have been the 404 "has no composition to copy".
			refused(create(owner, f.bookOid, "cover", json("mode", "from_scene", "sceneObjectId", NIL_UUID)), 404, "not found in this book", "FROM_SCENE of an unknown scene on an existing cover");
			BaseRecord bare = addScene(f, 0);
			Map<String, Object> scene = ok(create(owner, f.bookOid, "cover", json("mode", "from_scene", "sceneObjectId", oid(bare))), "FROM_SCENE on an existing cover");
			assertFlag(scene, "existingReturned", true);
			assertFlag(scene, "created", false);
			assertEquals("FROM_SCENE returns the existing composition without cloning", compOid, str(scene, "compositionObjectId"));

			Map<String, Object> t = ok(create(owner, f.bookOid, "title-page", manual()), "manual title page");
			assertFlag(t, "created", true);
			assertEquals("purpose", "TITLE_PAGE", str(t, "purpose"));
			String titleOid = str(t, "compositionObjectId");
			assertNotEquals("the title page is its own composition", compOid, titleOid);
			Map<String, Object> t2 = ok(create(owner, f.bookOid, "TITLE_PAGE", manual()), "title page by its enum word");
			assertFlag(t2, "existingReturned", true);
			assertEquals("the enum word resolves to the same composition", titleOid, str(t2, "compositionObjectId"));

			Map<String, Object> v = ok(view(owner, f.bookOid), "GET covers");
			Map<String, Object> covers = coversOf(v);
			assertEquals("two purposes are listed: " + covers.keySet(), 2, covers.size());
			Map<String, Object> cover = map(covers, "COVER");
			assertEquals("COVER entry", compOid, str(cover, "compositionObjectId"));
			assertEquals("COVER entry purpose", "COVER", str(cover, "purpose"));
			assertEquals("COVER entry source", "USER", str(cover, "source"));
			assertNull("no node before a render", str(cover, "nodeObjectId"));
			assertNull("no node status before a render", str(cover, "nodeStatus"));
			assertFlag(cover, "pinned", false);
			assertEquals("no artifacts before a render", 0, num(cover, "chainLength"));
			assertNull("no approved image before a render", cover.get("approved"));
			assertNull("no latest image before a render", cover.get("latest"));
			assertEquals("TITLE_PAGE entry", titleOid, str(map(covers, "TITLE_PAGE"), "compositionObjectId"));
			assertNull("no workflow before a render", str(v, "workflowObjectId"));
			assertNull("no CHAPTER_OPENER", covers.get("CHAPTER_OPENER"));
			assertNull("no SERIES_ART", covers.get("SERIES_ART"));
		}
		finally {
			cleanup(f);
		}
	}

	/// FROM_SCENE: the scene must be given, must be a scene of this book, must have a composition; then the cover is
	/// a CLONE of that scene's SCENE composition, a cover-type row of its own (no scene FK) that names its source.
	@Test
	public void TestFromSceneClonesTheSceneComposition() throws Exception {
		v3();
		Fixture f = createWorldBook(false);
		try {
			BaseRecord bare = addScene(f, 0);
			BaseRecord composed = addScene(f, 1);
			BaseRecord src = compose(f, composed);
			refused(create(owner, f.bookOid, "cover", json("mode", "from_scene")), 400, "A scene objectId is required", "FROM_SCENE without a scene");
			refused(create(owner, f.bookOid, "cover", json("mode", "from_scene", "sceneObjectId", NIL_UUID)), 404, "not found in this book", "FROM_SCENE of an unknown scene");
			refused(create(owner, f.bookOid, "cover", json("mode", "from_scene", "sceneObjectId", oid(bare))), 404, "has no composition to copy", "FROM_SCENE of a scene with no composition");
			assertNothingWritten(f, "after the FROM_SCENE refusals");

			Map<String, Object> c = ok(create(owner, f.bookOid, "cover", json("mode", "scene", "sceneObjectId", oid(composed))), "FROM_SCENE create");
			assertFlag(c, "created", true);
			assertFlag(c, "existingReturned", false);
			assertEquals("mode", "FROM_SCENE", str(c, "mode"));
			assertEquals("source", "CLONE", str(c, "source"));
			assertEquals("the source scene is named", oid(composed), str(c, "sourceSceneObjectId"));
			String compOid = str(c, "compositionObjectId");
			assertNotNull("the clone's objectId", compOid);
			assertNotEquals("the clone is not the scene's own composition", oid(src), compOid);

			BaseRecord stored = coverOf(f, PbCompositionPurposeEnumType.COVER);
			assertNotNull("the COVER clone is persisted", stored);
			assertEquals("persisted objectId", compOid, oid(stored));
			assertNull("the clone has no scene FK", stored.get(OlioFieldNames.FIELD_PB_SCENE));
			assertEquals("the clone's purpose", "COVER", enumWord(stored, OlioFieldNames.FIELD_PB_PURPOSE));
			BaseRecord sceneComp = PbComposeUtil.readComposition(owner, oid(src), orgId);
			assertNotNull("the scene's own composition is untouched", sceneComp);
			assertEquals("the scene's composition keeps its scene", oid(composed), oid(sceneComp.get(OlioFieldNames.FIELD_PB_SCENE)));
		}
		finally {
			cleanup(f);
		}
	}

	/// Render: no composition is a 404 that names the purpose (the render path never creates one); with a MANUAL
	/// cover the 202 materializes the RENDER_PLAN node, opens a run and queues the job; the view then shows the
	/// node; a pinned node is the 409 before any further job. The job's own outcome against the loopback SD is
	/// logged, not asserted.
	@Test
	public void TestRenderMaterializesNodeAndRefusesPinned() throws Exception {
		v3();
		Fixture f = createWorldBook(false);
		String jobId = null;
		AsyncJob job = null;
		try {
			refused(render(owner, f.bookOid, "cover"), 404, "has no COVER composition", "render with no cover");
			refused(render(owner, f.bookOid, "chapter-opener"), 404, "has no CHAPTER_OPENER composition", "render with no chapter opener");
			assertNull("a refused render materializes no workflow", PbGraphUtil.findWorkflow(owner, bookAsOwner(f)));

			String compOid = str(ok(create(owner, f.bookOid, "cover", manual()), "manual create"), "compositionObjectId");
			assertNotNull("composition objectId", compOid);
			Map<String, Object> r = accepted(render(owner, f.bookOid, "cover"), "render");
			assertEquals("the render names the book", f.bookOid, str(r, "bookObjectId"));
			assertEquals("the render names the purpose", "COVER", str(r, "purpose"));
			assertEquals("the render names the composition", compOid, str(r, "compositionObjectId"));
			String nodeOid = str(r, "nodeObjectId");
			assertNotNull("the RENDER_PLAN node is materialized: " + r, nodeOid);
			assertFlag(r, "nodeMaterialized", true);
			assertNotNull("a run is opened: " + r, str(r, "runObjectId"));
			assertEquals("the one node is planned", 1, num(r, "plannedNodeCount"));
			assertEquals("kind", PbRenderJobUtil.KIND_RENDER, str(r, "kind"));
			jobId = str(r, "jobId");
			job = awaitRenderJob(r, "cover render job");

			BaseRecord node = PbGraphUtil.readNode(owner, nodeOid, orgId);
			assertNotNull("the node reads back as the owner", node);
			assertEquals("the node is scoped to the composition", compOid, node.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
			BaseRecord workflow = PbGraphUtil.findWorkflow(owner, bookAsOwner(f));
			assertNotNull("the workflow now exists", workflow);

			Map<String, Object> v = ok(view(owner, f.bookOid), "GET covers after the render");
			assertEquals("the view names the workflow", oid(workflow), str(v, "workflowObjectId"));
			Map<String, Object> cover = map(coversOf(v), "COVER");
			assertEquals("the view shows the node", nodeOid, str(cover, "nodeObjectId"));
			assertNotNull("the view shows the node status: " + cover, str(cover, "nodeStatus"));
			assertFlag(cover, "pinned", false);
			logger.info("cover entry after the render: " + cover);

			BaseRecord pin = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE,
				new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_PINNED });
			pin.set(FieldNames.FIELD_ID, node.get(FieldNames.FIELD_ID));
			pin.set(FieldNames.FIELD_OBJECT_ID, nodeOid);
			pin.set(OlioFieldNames.FIELD_PB_PINNED, Boolean.TRUE);
			assertNotNull("the owner can pin the node", IOSystem.getActiveContext().getAccessPoint().update(owner, pin));
			refused(render(owner, f.bookOid, "cover"), 409, "is pinned", "render of a pinned cover");
			refused(service.renderCover(f.bookOid, "cover", true, requestAs(owner), SD_CONTEXT), 409, "is pinned", "forced render of a pinned cover");
			Map<String, Object> pinned = map(coversOf(ok(view(owner, f.bookOid), "GET covers after the pin")), "COVER");
			assertFlag(pinned, "pinned", true);
			assertEquals("still the same node", nodeOid, str(pinned, "nodeObjectId"));
		}
		finally {
			if(jobId != null && job == null) {
				/// A job that was queued but never awaited (an assertion fired first) must settle before the rows go.
				PbRenderJobUtil.awaitTerminal(owner, jobId, JOB_CEILING_MS);
			}
			cleanup(f);
		}
	}

	/// SERIES_ART on a book that is not a chapter of a series: create is the 409 that says so, the view lists no
	/// SERIES_ART, and render is the 404 (there is no composition to render - the render path never creates one).
	@Test
	public void TestSeriesArtOnANonChapterIs409() throws Exception {
		v3();
		Fixture f = createWorldBook(false);
		try {
			refused(create(owner, f.bookOid, "series-art", manual()), 409, "is not a chapter of a series", "SERIES_ART create on a non-chapter");
			refused(create(owner, f.bookOid, "series_art", null), 409, "is not a chapter of a series", "SERIES_ART AUTO create on a non-chapter");
			refused(render(owner, f.bookOid, "series-art"), 404, "has no SERIES_ART composition", "SERIES_ART render on a non-chapter");
			refused(board(owner, f.bookOid, "series-art"), 404, "has no SERIES_ART composition", "SERIES_ART board on a non-chapter");
			refused(patch(owner, f.bookOid, "series-art", settingAndBand("top")), 404, "has no SERIES_ART composition", "SERIES_ART patch on a non-chapter");
			refused(plan(owner, f.bookOid, "series-art", "{}"), 404, "has no SERIES_ART composition", "SERIES_ART plan on a non-chapter");
			assertNothingWritten(f, "after the SERIES_ART refusals");
			Map<String, Object> v = ok(view(owner, f.bookOid), "GET covers");
			assertNull("no SERIES_ART entry", coversOf(v).get("SERIES_ART"));
		}
		finally {
			cleanup(f);
		}
	}

	/// P9-4 board + patch. The board of a missing cover is the 404 naming the purpose; of a MANUAL cover it is the
	/// composition keyed by purpose with no rows and the view's entry (no node). The patch refuses an empty body, an
	/// unknown key, a refused key and a bad band word with a 400 that names it, and writes nothing; a good patch
	/// lands settingText + titleSafeBand, bumps the revision, flags userEdited / USER, reports nodeStale:false on a
	/// book that never rendered, and the board then reads the edit back. Each later patch bumps the revision again.
	@Test
	public void TestCoverBoardReadsAndPatchBumpsRevision() throws Exception {
		v3();
		Fixture f = createWorldBook(false);
		try {
			refused(board(owner, f.bookOid, "cover"), 404, NO_COVER, "board with no cover");
			refused(patch(owner, f.bookOid, "cover", settingAndBand("top")), 404, NO_COVER, "patch with no cover");
			refused(board(owner, f.bookOid, "chapter-opener"), 404, "has no CHAPTER_OPENER composition", "board with no chapter opener");
			assertNothingWritten(f, "after the refused board / patch");

			Map<String, Object> c = ok(create(owner, f.bookOid, "cover", manual()), "manual create");
			String compOid = str(c, "compositionObjectId");
			assertNotNull("composition objectId", compOid);
			int rev0 = num(map(c, "composition"), OlioFieldNames.FIELD_PB_REVISION);

			Map<String, Object> b = ok(board(owner, f.bookOid, "cover"), "board of the manual cover");
			assertEquals("the board names the book", f.bookOid, str(b, "bookObjectId"));
			assertEquals("the board names the purpose", "COVER", str(b, "purpose"));
			assertEquals("the board names the composition", compOid, str(b, "compositionObjectId"));
			Map<String, Object> comp = map(b, "composition");
			assertEquals("the composition dto is the same row", compOid, str(comp, "objectId"));
			assertEquals("a cover-type composition's dto purpose", "COVER", str(comp, "purpose").toUpperCase());
			assertNull("a manual cover carries no setting yet", str(comp, OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertNull("a cover has no scene", str(comp, "sceneObjectId"));
			assertTrue("a manual cover has no actors", list(b, "actors").isEmpty());
			assertTrue("a manual cover has no elements", list(b, "elements").isEmpty());
			Map<String, Object> entry = map(b, "cover");
			assertEquals("the entry names the composition", compOid, str(entry, "compositionObjectId"));
			assertEquals("the entry names the purpose", "COVER", str(entry, "purpose"));
			assertNull("no node before a render", str(entry, "nodeObjectId"));
			assertFlag(entry, "pinned", false);
			assertEquals("no artifacts before a render", 0, num(entry, "chainLength"));

			refused(patch(owner, f.bookOid, "cover", null), 400, "No fields to update", "patch with no body");
			refused(patch(owner, f.bookOid, "cover", "{}"), 400, "No fields to update", "patch with an empty body");
			refused(patch(owner, f.bookOid, "cover", json("colour", "teal")), 400, "Unknown field colour", "patch of an unknown key");
			refused(patch(owner, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_PURPOSE, "scene")), 400, "cannot be set here", "patch of the purpose");
			refused(patch(owner, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_REVISION, 9)), 400, "cannot be set here", "patch of the revision");
			refused(patch(owner, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, "sideways")), 400, "titleSafeBand must be one of", "patch of a bad band word");
			refused(patch(owner, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_SETTING_TEXT, COVER_SETTING, "colour", "teal")), 400, "Unknown field colour", "a bad key refuses the whole patch");
			BaseRecord untouched = coverOf(f, PbCompositionPurposeEnumType.COVER);
			assertNotNull("the cover is still there", untouched);
			assertNull("none of the refused patches landed", untouched.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("the revision is untouched", rev0, ((Number) untouched.get(OlioFieldNames.FIELD_PB_REVISION)).intValue());

			Map<String, Object> p = ok(patch(owner, f.bookOid, "cover", settingAndBand("top")), "patch setting + band");
			assertEquals("the patch names the book", f.bookOid, str(p, "bookObjectId"));
			assertEquals("the patch names the purpose", "COVER", str(p, "purpose"));
			assertEquals("the patch names the composition", compOid, str(p, "compositionObjectId"));
			assertNull("a cover patch has no scene", str(p, "sceneObjectId"));
			assertFlag(p, "updated", true);
			assertEquals("the revision is bumped", rev0 + 1, num(p, "revision"));
			assertFlag(p, "nodeStale", false);
			assertNull("no node status on a book that never rendered", str(p, "nodeStatus"));
			Map<String, Object> pc = map(p, "composition");
			assertEquals("the re-read composition carries the setting", COVER_SETTING, str(pc, OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("the re-read composition carries the band", "TOP", str(pc, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND).toUpperCase());
			assertEquals("the re-read composition's revision", rev0 + 1, num(pc, OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("the re-read composition is USER", "USER", str(pc, "source").toUpperCase());

			BaseRecord stored = coverOf(f, PbCompositionPurposeEnumType.COVER);
			assertNotNull("the cover is persisted", stored);
			assertEquals("persisted setting", COVER_SETTING, stored.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("persisted band", "TOP", enumWord(stored, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND));
			assertEquals("persisted source", "USER", enumWord(stored, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("persisted userEdited", Boolean.TRUE, stored.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals("persisted revision", rev0 + 1, ((Number) stored.get(OlioFieldNames.FIELD_PB_REVISION)).intValue());
			assertNull("still no scene", stored.get(OlioFieldNames.FIELD_PB_SCENE));

			Map<String, Object> b2 = map(ok(board(owner, f.bookOid, "cover"), "board after the patch"), "composition");
			assertEquals("the board reads the setting back", COVER_SETTING, str(b2, OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("the board reads the band back", "TOP", str(b2, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND).toUpperCase());

			Map<String, Object> p2 = ok(patch(owner, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, "BOTTOM", OlioFieldNames.FIELD_PB_MOOD, "elegiac")), "second patch");
			assertEquals("every edit bumps the revision", rev0 + 2, num(p2, "revision"));
			Map<String, Object> pc2 = map(p2, "composition");
			assertEquals("the band is now BOTTOM", "BOTTOM", str(pc2, OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND).toUpperCase());
			assertEquals("the mood landed", "elegiac", str(pc2, OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("the setting is untouched by a patch that did not name it", COVER_SETTING, str(pc2, OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("the view's entry is the same composition", compOid, str(map(coversOf(ok(view(owner, f.bookOid), "GET covers")), "COVER"), "compositionObjectId"));
		}
		finally {
			cleanup(f);
		}
	}

	/// P9-4 plan. An empty MANUAL cover is the 400 that says there is nothing to render from; a bad preset is its
	/// 400; a meaningful promptOverride in the body is enough on its own. With a setting patched in the plan builds:
	/// purpose COVER, the composition named, the BASE prompt carrying the cover's own text, the title-safe band as
	/// the reserved box (top quarter for TOP, bottom quarter for BOTTOM, none for NONE), the unreadable inventory
	/// reported as a degradation, an estimate, a contentHash that round-trips, and never the SD URL. Nothing is
	/// written by a plan: the revision and the workflow are what they were.
	@Test
	public void TestPlanCoverNeedsDescriptionAndCarriesTheBand() throws Exception {
		v3();
		Fixture f = createWorldBook(false);
		try {
			refused(plan(owner, f.bookOid, "cover", "{}"), 404, NO_COVER, "plan with no cover");
			Map<String, Object> c = ok(create(owner, f.bookOid, "cover", manual()), "manual create");
			String compOid = str(c, "compositionObjectId");
			assertNotNull("composition objectId", compOid);
			refused(plan(owner, f.bookOid, "cover", "{}"), 400, NO_DESCRIPTION, "plan of an empty manual cover");
			refused(plan(owner, f.bookOid, "cover", null), 400, NO_DESCRIPTION, "plan of an empty manual cover with no body");
			refused(plan(owner, f.bookOid, "cover", json("preset", "ultra")), 400, "Unknown quality preset 'ultra'", "plan with a bad preset");
			refused(plan(owner, f.bookOid, "cover", json("promptOverride", "null")), 400, NO_DESCRIPTION, "the literal 'null' is not a description");

			Map<String, Object> over = ok(plan(owner, f.bookOid, "cover", json("promptOverride", "A single lit window in a dark tower")), "plan from a body override alone");
			assertEquals("purpose", RenderPlan.PURPOSE_COVER, str(over, "purpose"));
			assertEquals("the composition is named", compOid, str(over, "compositionObjectId"));
			assertTrue("the override reaches the BASE prompt: " + basePrompt(over), basePrompt(over).contains("single lit window"));
			assertNull("no band on a fresh manual cover reserves nothing", reservedOf(over));

			int rev = num(map(ok(patch(owner, f.bookOid, "cover", settingAndBand("top")), "patch setting + TOP"), "composition"), OlioFieldNames.FIELD_PB_REVISION);
			Response r = plan(owner, f.bookOid, "cover", "{}");
			String raw = String.valueOf(r.getEntity());
			Map<String, Object> dto = ok(r, "plan of the patched cover");
			assertEquals("planVersion", RenderPlan.PLAN_VERSION, dto.get("planVersion"));
			assertEquals("purpose", RenderPlan.PURPOSE_COVER, str(dto, "purpose"));
			assertEquals("the composition is named", compOid, str(dto, "compositionObjectId"));
			Map<String, Object> canvas = map(dto, "canvas");
			assertTrue("canvas width is explicit: " + canvas, num(canvas, "width") > 0);
			assertTrue("canvas height is explicit: " + canvas, num(canvas, "height") > 0);
			assertTrue("the BASE prompt carries the cover's own text: " + basePrompt(dto), basePrompt(dto).contains("drowned causeway"));
			assertEquals("no actors on a manual cover", 0, list(dto, "actors").size());
			assertEquals("no elements on a manual cover", 0, list(dto, "elements").size());
			Map<String, Object> top = reservedOf(dto);
			assertNotNull("TOP reserves a band: " + dto.keySet(), top);
			assertEquals("the TOP band hugs the top edge", 0.0, dbl(top, "y"), 0.0001);
			assertEquals("the band is full width", 1.0, dbl(top, "w"), 0.0001);
			assertEquals("the band is a quarter of the page", 0.25, dbl(top, "h"), 0.0001);
			boolean reported = false;
			for(Object o : list(dto, "degradations")) {
				@SuppressWarnings("unchecked")
				Map<String, Object> d = (Map<String, Object>) o;
				if(RenderPlan.Degradation.MODEL_SUBSTITUTED.equals(d.get("code")) && String.valueOf(d.get("message")).contains("INVENTORY_UNAVAILABLE")) {
					reported = true;
				}
			}
			assertTrue("the unreadable loopback inventory is a MODEL_SUBSTITUTED degradation naming INVENTORY_UNAVAILABLE: " + dto.get("degradations"), reported);
			Map<String, Object> estimate = map(dto, "estimate");
			assertNotNull("the estimate names its source", estimate.get("source"));
			assertEquals("contentHash is the hash of the plan on the wire", RenderPlan.fromMap(dto).contentHash(), str(dto, "contentHash"));
			assertNotNull("planSummary is present", dto.get("planSummary"));
			assertTrue("the plan never carries the SD server URL", !raw.contains("127.0.0.1"));

			BaseRecord stored = coverOf(f, PbCompositionPurposeEnumType.COVER);
			assertEquals("a plan writes nothing: the revision is what the patch left", rev, ((Number) stored.get(OlioFieldNames.FIELD_PB_REVISION)).intValue());
			assertNull("a plan materializes no workflow", PbGraphUtil.findWorkflow(owner, bookAsOwner(f)));

			ok(patch(owner, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, "bottom")), "patch BOTTOM");
			Map<String, Object> bottom = reservedOf(ok(plan(owner, f.bookOid, "cover", "{}"), "plan with BOTTOM"));
			assertNotNull("BOTTOM reserves a band", bottom);
			assertEquals("the BOTTOM band starts three quarters down", 0.75, dbl(bottom, "y"), 0.0001);
			assertEquals("the BOTTOM band is a quarter of the page", 0.25, dbl(bottom, "h"), 0.0001);
			ok(patch(owner, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, "none")), "patch NONE");
			assertNull("NONE reserves nothing", reservedOf(ok(plan(owner, f.bookOid, "cover", "{}"), "plan with NONE")));
		}
		finally {
			cleanup(f);
		}
	}

	/// P9-4 patch after a render. Once the cover has a composition_plan@ node (the loopback render settles it
	/// FAILED, or whatever terminal word the executor wrote - never RUNNING once the job is terminal), a patch marks
	/// it STALE: nodeStale:true and nodeStatus STALE on the patch body, STALE on the node itself, STALE on the
	/// board's entry; a second patch on the already-STALE node reports nodeStale:true again without rewriting it.
	/// A render after the patch is then admitted without force (the 202 - the node is no longer pinned / fresh).
	@Test
	public void TestPatchAfterRenderMarksNodeStale() throws Exception {
		v3();
		Fixture f = createWorldBook(false);
		String jobId = null;
		AsyncJob job = null;
		try {
			String compOid = str(ok(create(owner, f.bookOid, "cover", manual()), "manual create"), "compositionObjectId");
			assertNotNull("composition objectId", compOid);
			ok(patch(owner, f.bookOid, "cover", settingAndBand("top")), "patch before the render");
			Map<String, Object> r = accepted(render(owner, f.bookOid, "cover"), "render");
			String nodeOid = str(r, "nodeObjectId");
			assertNotNull("the node is materialized: " + r, nodeOid);
			jobId = str(r, "jobId");
			job = awaitRenderJob(r, "cover render job");

			BaseRecord before = PbGraphUtil.readNode(owner, nodeOid, orgId);
			assertNotNull("the node reads back", before);
			String statusBefore = enumWord(before, OlioFieldNames.FIELD_PB_NODE_STATUS);
			logger.info("node " + nodeOid + " after the loopback render: " + statusBefore);
			assertNotEquals("a terminal job leaves no RUNNING node", "RUNNING", statusBefore);

			Map<String, Object> p = ok(patch(owner, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_MOOD, "stormlit")), "patch after the render");
			assertFlag(p, "nodeStale", true);
			assertEquals("the patch reports the node STALE", "STALE", str(p, "nodeStatus").toUpperCase());
			BaseRecord after = PbGraphUtil.readNode(owner, nodeOid, orgId);
			assertEquals("the node itself is STALE", "STALE", enumWord(after, OlioFieldNames.FIELD_PB_NODE_STATUS));
			Map<String, Object> entry = map(ok(board(owner, f.bookOid, "cover"), "board after the patch"), "cover");
			assertEquals("the board's entry shows the node", nodeOid, str(entry, "nodeObjectId"));
			assertEquals("the board's entry shows STALE", "STALE", str(entry, "nodeStatus").toUpperCase());

			Map<String, Object> p2 = ok(patch(owner, f.bookOid, "cover", json(OlioFieldNames.FIELD_PB_MOOD, "stormlit, late")), "second patch");
			assertFlag(p2, "nodeStale", true);
			assertEquals("still STALE", "STALE", str(p2, "nodeStatus").toUpperCase());
			assertEquals("still STALE on the node", "STALE", enumWord(PbGraphUtil.readNode(owner, nodeOid, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));

			Map<String, Object> again = accepted(render(owner, f.bookOid, "cover"), "render after the edit, no force");
			jobId = str(again, "jobId");
			job = null;
			assertEquals("the same node is re-run", nodeOid, str(again, "nodeObjectId"));
			assertFlag(again, "nodeMaterialized", false);
			job = awaitRenderJob(again, "second cover render job");
			assertNotNull("the second job settles", job);
		}
		finally {
			if(jobId != null && job == null) {
				/// A job that was queued but never awaited (an assertion fired first) must settle before the rows go.
				PbRenderJobUtil.awaitTerminal(owner, jobId, JOB_CEILING_MS);
			}
			cleanup(f);
		}
	}
}
