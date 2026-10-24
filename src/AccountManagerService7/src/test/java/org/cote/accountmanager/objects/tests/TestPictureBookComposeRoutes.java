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
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
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
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// P4-4 (PictureBook V3 §9.1, §21; Implementation §12.4): the three compose / propose routes on PictureBookService
/// exercised through the real service methods -> PbServiceFacade -> PbComposeJobUtil / PbComposeUtil / PbLocationUtil
/// -> the real AsyncJobRegistry -> real AccessPoint/PBAC -> the real (H2) database, with HttpServletRequestMock +
/// UserPrincipal exactly as TestPictureBookLocationRoutes does. Nothing is mocked; no asserting actor is ever the
/// admin or the olio principal.
///
///   POST /{book}/compose                 compose        202 {submitted:true, jobId, ...} | 200 {submitted:false, reason, ...}
///   POST /{book}/scene/{scene}/compose   composeScene   200 composeResultDto (synchronous, one scene)
///   POST /{book}/locations/propose       proposeLocations  202 {submitted:true, jobId, kind:"pb.proposeLocations", ...}
///
/// FIXTURE. The same hand-built H2 world fixture as TestPictureBookLocationRoutes (its doc comment says why a bare book
/// is not enough: composing a scene opens the book's world context) with its own user names, slug prefix and group so
/// the two classes never share rows. The render-path switch is process-global and the Service7 harness defaults
/// PictureBook to v1, so every case pins it (configure) and @After restores what it found, as
/// TestChapBookRestContract does.
///
/// LLM. The harness has no `generalChat` chat config in any group the test users can see (asserted as a precondition
/// in the job case, not assumed): PictureBookUtil.callLlmInternal then raises the hard flag WITHOUT an HTTP call, so
/// the compose job's outcome on this harness is deterministic - a scene with no composition gets a MIGRATED one from its
/// own fields (design §9.1 / §29 fallback), a scene that already has one keeps it and the result says hardFailure, and
/// the location pass that ends the job reports hardFailure for a scene with a setting. The real LLM compose (actors,
/// elements, mood from a qwen reply) is TestPbComposeJob / TestPbComposeScene in Objects7 against the live stack; what
/// is asserted here is the transport contract and the job plumbing, which do not depend on the reply.
///
/// WIRE NOTE (measured, not a defect under test): JSONUtil.exportObject serialises with Include.NON_EMPTY, so a
/// null `failureReason`, an empty `warnings`/`proposals`/`actors`/`elements` are ABSENT from a body rather than
/// present as null/[]; booleans (`submitted:false`, `hardFailure:false`) and ints (`sceneCount:0`) ARE present. A job
/// result is the same serialisation (PbComposeJobUtil.ComposeWork.run), read back through JSONUtil.importObject.
public class TestPictureBookComposeRoutes extends BaseTest {

	private static final String OWNER_NAME = "pbcomposeowner";
	private static final String READER_NAME = "pbcomposereader";
	private static final String STRANGER_NAME = "pbcomposestranger";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String BARE_GROUP_PATH = "~/PictureBookComposeRoutes";
	private static final String[] DATA_AND_GROUP = new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() };
	private static final String[] CRUD = new String[] { "Read", "Update", "Create", "Delete" };
	private static final String[] READ = new String[] { "Read" };
	/// An SD context that resolves (Quick start is accepted) but whose server is unreachable - nothing here renders.
	private static final ServletContext SD_CONTEXT = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", "http://127.0.0.1:1"));
	/// No SD init-params at all: Quick start must be refused before anything is queued.
	private static final ServletContext NO_SD_CONTEXT = initParams(Map.of());
	private static final String SCENE_SETTING = "Rain hammers the shutters while the fiddler tunes up";
	private static final String MISSING_CONFIG = "no-such-config-p44";
	/// Job ceiling. No LLM is reachable on this harness so a job is milliseconds of DB work; the ceiling is only
	/// the point at which a hung registry is reported rather than waited on forever.
	private static final long JOB_CEILING_MS = 5L * 60L * 1000L;

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

	// ─────────────────────────────── fixture (TestPictureBookLocationRoutes' shape) ───────────────────────────────

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
		f.slug = "p44r-" + UUID.randomUUID().toString().substring(0, 12);
		String worldsPath = booksWorldsPath();

		BaseRecord created = WorldUtil.getCreateWorld(olioUser, worldsPath, f.slug, new String[0]);
		assertNotNull("Book world is null", created);
		f.world = WorldUtil.findWorld(olioUser, worldsPath, f.slug);
		assertNotNull("Book world could not be re-found", f.world);
		ioContext.getReader().populate(f.world, 2);

		String bookGroupPath = PbBookUtil.bookGroupPath(f.slug);
		BaseRecord bookDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, bookGroupPath, "DATA", orgId);
		assertNotNull("Book group is null", bookDir);
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

	/// An owner-owned book in an owner-owned group with no world at all (the PB1 shape).
	private BaseRecord createBareBook() throws Exception {
		String slug = "p44b-" + UUID.randomUUID().toString().substring(0, 12);
		BaseRecord dir = ioContext.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, BARE_GROUP_PATH, "DATA", orgId);
		assertNotNull("Bare fixture group is null", dir);
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(owner, book, slug, BARE_GROUP_PATH, orgId);
		book.set(FieldNames.FIELD_NAME, slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, book);
		assertNotNull("Failed to create the bare book", created);
		assertNotNull("Bare book carries no objectId", oid(created));
		return created;
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

	private void cleanupBare(BaseRecord book) {
		if(book == null) {
			return;
		}
		try {
			if(!IOSystem.getActiveContext().getAccessPoint().delete(owner, book)) {
				logger.error("Cleanup: could not delete bare book " + oid(book));
			}
		}
		catch(Exception e) {
			logger.error("Cleanup: bare book: " + e.getMessage());
		}
	}

	/// A page of the world book created as the owner (Writer) with its setting patched in (TestPictureBookLocationRoutes.addScene).
	private BaseRecord addScene(Fixture f, int index) throws Exception {
		BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
		assertNotNull("the world book must read back as the owner", book);
		BaseRecord created = PbBookUtil.createScene(owner, book, index, "Page " + index, PbBookUtil.bookGroupPath(f.slug));
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
		patch.set(OlioFieldNames.FIELD_PB_SETTING, SCENE_SETTING);
		assertNotNull("scene setting patch must persist", IOSystem.getActiveContext().getAccessPoint().update(owner, patch));
		scene = PbBookUtil.readScene(owner, sceneOid, orgId);
		assertNotNull("Patched page must read back", scene);
		assertEquals("the setting must be stored", SCENE_SETTING, scene.get(OlioFieldNames.FIELD_PB_SETTING));
		return scene;
	}

	/// Marks the scene's composition user-edited the way the P4-9 board will (a field-list patch of the row as the
	/// owner, who created it), so needsCompose() answers false for it.
	private void markUserEdited(String compositionOid) throws Exception {
		BaseRecord comp = PbComposeUtil.readComposition(owner, compositionOid, orgId);
		assertNotNull("composition " + compositionOid + " must read back as the owner", comp);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_COMPOSITION, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_USER_EDITED });
		patch.set(FieldNames.FIELD_ID, comp.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, compositionOid);
		patch.set(FieldNames.FIELD_NAME, comp.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
		assertNotNull("userEdited patch must persist", IOSystem.getActiveContext().getAccessPoint().update(owner, patch));
		BaseRecord again = PbComposeUtil.readComposition(owner, compositionOid, orgId);
		assertEquals("userEdited must read back true", Boolean.TRUE, again.get(OlioFieldNames.FIELD_PB_USER_EDITED));
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPictureBookComposeRoutes.class.getClassLoader(),
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

	private Map<String, Object> ok(Response r, String what) {
		assertEquals(what + " must be 200, body: " + r.getEntity(), 200, r.getStatus());
		return body(r);
	}

	private Map<String, Object> accepted(Response r, String what) {
		assertEquals(what + " must be 202, body: " + r.getEntity(), 202, r.getStatus());
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
		Object o = m.get(key);
		assertNotNull("'" + key + "' must be present: " + m, o);
		assertTrue("'" + key + "' must be a number, got " + o.getClass().getSimpleName(), o instanceof Number);
		return ((Number) o).intValue();
	}

	private static String json(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2) {
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return JSONUtil.exportObject(m);
	}

	private static List<String> strings(List<Object> in) {
		List<String> out = new ArrayList<>();
		for(Object o : in) {
			out.add(String.valueOf(o));
		}
		return out;
	}

	/// The 202 body's job, polled through the registry AS THE SUBMITTING USER (the composite owner+jobId key is what
	/// GET /rest/job/{id} resolves) until it is terminal. Fails on the ceiling or a FAILED job; never on how long it took.
	@SuppressWarnings("unchecked")
	private Map<String, Object> awaitJob(BaseRecord as, Map<String, Object> submit, String what) throws Exception {
		assertEquals(what + ": submitted", Boolean.TRUE, submit.get("submitted"));
		String jobId = String.valueOf(submit.get("jobId"));
		assertNotEquals(what + ": jobId is reported", "null", jobId);
		String st = String.valueOf(submit.get("jobStatus"));
		assertTrue(what + ": jobStatus is the registry's lowercase status, got " + st,
			"queued".equals(st) || "running".equals(st) || "completed".equals(st));
		long started = System.currentTimeMillis();
		AsyncJob job = AsyncJobRegistry.get(as, jobId);
		assertNotNull(what + ": the submitting user must see job " + jobId, job);
		while(!job.isTerminal() && System.currentTimeMillis() - started < JOB_CEILING_MS) {
			Thread.sleep(100L);
		}
		long elapsed = System.currentTimeMillis() - started;
		assertTrue(what + ": job " + jobId + " did not finish within the ceiling (" + elapsed + " ms)", job.isTerminal());
		logger.info(what + ": job " + jobId + " " + job.getStatus() + " after " + elapsed + " ms");
		assertNotEquals(what + ": job failed: " + job.getError(), AsyncJob.Status.FAILED, job.getStatus());
		assertEquals(what + ": job completed", AsyncJob.Status.COMPLETED, job.getStatus());
		assertNotNull(what + ": a completed job carries a result", job.getResult());
		Map<String, Object> result = JSONUtil.importObject(job.getResult(), LinkedHashMap.class);
		assertNotNull(what + ": job result did not parse as JSON: " + job.getResult(), result);
		return result;
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/// Without a principal every route is 401 before the book is even looked up.
	@Test
	public void TestNoPrincipalIs401() {
		HttpServletRequestMock anon = new HttpServletRequestMock();
		refused(service.compose(NIL_UUID, "{}", anon, SD_CONTEXT), 401, "No authenticated principal", "anonymous compose");
		refused(service.composeScene(NIL_UUID, NIL_UUID, "{}", anon, NO_SD_CONTEXT), 401, "No authenticated principal", "anonymous scene compose");
		refused(service.proposeLocations(NIL_UUID, "{}", anon), 401, "No authenticated principal", "anonymous propose");
	}

	/// Route-level body checks fire before requireBook (so NIL ids suffice): a JSON array is not a body.
	@Test
	public void TestMalformedBodyIs400() {
		HttpServletRequestMock o = requestAs(owner);
		String bad = "[1,2]";
		String mb = "Malformed request body";
		refused(service.compose(NIL_UUID, bad, o, SD_CONTEXT), 400, mb, "malformed compose");
		refused(service.composeScene(NIL_UUID, NIL_UUID, bad, o, NO_SD_CONTEXT), 400, mb, "malformed scene compose");
		refused(service.proposeLocations(NIL_UUID, bad, o), 400, mb, "malformed propose");
		/// a missing / blank body is an empty body, not a malformed one: it reaches the book gate
		refused(service.compose(NIL_UUID, null, o, SD_CONTEXT), 404, "Book not found", "compose with no body");
		refused(service.proposeLocations(NIL_UUID, "", o), 404, "Book not found", "propose with a blank body");
	}

	/// A user with no grant on the book cannot tell it exists: 404 "Book not found" on all three, on either render path,
	/// the scene id never being consulted.
	@Test
	public void TestStrangerIs404OnEveryRoute() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock s = requestAs(stranger);
			String nf = "Book not found";
			v3();
			refused(service.compose(f.bookOid, "{}", s, SD_CONTEXT), 404, nf, "stranger compose (v3)");
			refused(service.composeScene(f.bookOid, NIL_UUID, "{}", s, NO_SD_CONTEXT), 404, nf, "stranger scene compose (v3)");
			refused(service.proposeLocations(f.bookOid, "{}", s), 404, nf, "stranger propose (v3)");
			v1();
			refused(service.compose(f.bookOid, "{}", s, SD_CONTEXT), 404, nf, "stranger compose (v1)");
			refused(service.composeScene(f.bookOid, NIL_UUID, "{}", s, NO_SD_CONTEXT), 404, nf, "stranger scene compose (v1)");
			refused(service.proposeLocations(f.bookOid, "{}", s), 404, nf, "stranger propose (v1)");
		}
		finally {
			cleanup(f);
		}
	}

	/// A book on the v1 path has no compositions to make: both compose routes are 409 with the render-path refusal,
	/// before the scene is looked up. The location pass is not a V3 feature (P3-4 shipped it on both paths), so the
	/// propose job is accepted for the same book - and on a scene-less book it reports nothing to cluster.
	@Test
	public void TestV1BookRefusesComposeButNotPropose() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock o = requestAs(owner);
			v1();
			refused(service.compose(f.bookOid, "{}", o, SD_CONTEXT), 409, PbRenderPath.REFUSAL_PREFIX, "v1 compose");
			refused(service.compose(f.bookOid, json("quickStart", Boolean.TRUE, "force", Boolean.TRUE), o, SD_CONTEXT), 409, PbRenderPath.REFUSAL_PREFIX, "v1 compose with options");
			refused(service.composeScene(f.bookOid, NIL_UUID, "{}", o, NO_SD_CONTEXT), 409, PbRenderPath.REFUSAL_PREFIX, "v1 scene compose");

			Map<String, Object> submit = accepted(service.proposeLocations(f.bookOid, "{}", o), "v1 propose");
			assertEquals(f.bookOid, submit.get("bookObjectId"));
			assertEquals("pb.proposeLocations", submit.get("kind"));
			Map<String, Object> pass = awaitJob(owner, submit, "v1 propose job");
			assertEquals(f.bookOid, pass.get("bookObjectId"));
			assertEquals("pb.proposeLocations", pass.get("kind"));
			assertEquals("no scene has a setting, so none were considered", 0, num(pass, "scenesConsidered"));
			assertEquals("no LLM chunk was built", 0, num(pass, "chunks"));
			assertNotEquals("the pass is not a hard failure", Boolean.TRUE, pass.get("hardFailure"));
			assertTrue("nothing proposed", list(pass, "proposals").isEmpty());
		}
		finally {
			cleanup(f);
		}
	}

	/// A Read-only grantee may not compose or propose: 403 naming the refused action, before any body value, chat
	/// config or scene is consulted. The facade orders the render-path check before the grant check, so under v1
	/// the same reader sees the 409 (measured order, PbServiceFacade.requestCompose / composeScene).
	@Test
	public void TestReaderIs403OnEveryRoute() throws Exception {
		Fixture f = createWorldBook(true);
		try {
			HttpServletRequestMock r = requestAs(reader);
			String na = "Not authorized to ";
			v3();
			refused(service.compose(f.bookOid, "{}", r, SD_CONTEXT), 403, na + "compose scenes in this book", "reader compose");
			refused(service.compose(f.bookOid, json("chatConfigName", MISSING_CONFIG, "quickStart", Boolean.TRUE), r, NO_SD_CONTEXT), 403, na + "compose scenes in this book", "reader compose is the 403, not the 404 config or the 500 SD");
			refused(service.composeScene(f.bookOid, NIL_UUID, "{}", r, NO_SD_CONTEXT), 403, na + "compose scenes in this book", "reader scene compose");
			refused(service.composeScene(f.bookOid, NIL_UUID, json("chatConfigName", MISSING_CONFIG, "force", Boolean.TRUE), r, NO_SD_CONTEXT), 403, na + "compose scenes in this book", "reader scene compose is the 403, not the 404");
			refused(service.proposeLocations(f.bookOid, "{}", r), 403, na + "propose locations in this book", "reader propose");
			refused(service.proposeLocations(f.bookOid, json("chatConfigName", MISSING_CONFIG), r), 403, na + "propose locations in this book", "reader propose is the 403, not the 404 config");
			v1();
			refused(service.compose(f.bookOid, "{}", r, SD_CONTEXT), 409, PbRenderPath.REFUSAL_PREFIX, "reader compose under v1");
			refused(service.composeScene(f.bookOid, NIL_UUID, "{}", r, NO_SD_CONTEXT), 409, PbRenderPath.REFUSAL_PREFIX, "reader scene compose under v1");
			refused(service.proposeLocations(f.bookOid, "{}", r), 403, na + "propose locations in this book", "reader propose under v1");
			/// and nothing was queued for the book
			assertEquals("no job runs for the book", 0, AsyncJobRegistry.list(reader).stream().filter(j -> !j.isTerminal()).count());
		}
		finally {
			cleanup(f);
		}
	}

	/// A book with no world reference (PB1 shape) is the owner's and on the V3 path, but compositions and locations live
	/// in the book's world: the whole-book compose and the location pass are refused 409 at the route before anything is
	/// queued (the contract the synchronous location pass always had), and the per-scene route reaches the scene gate
	/// first (no scenes exist).
	@Test
	public void TestBareBookIs409BeforeAnythingIsQueued() throws Exception {
		BaseRecord bare = createBareBook();
		try {
			HttpServletRequestMock o = requestAs(owner);
			v3();
			String noWorld = "has no resolvable world";
			refused(service.compose(oid(bare), "{}", o, SD_CONTEXT), 409, noWorld, "bare compose");
			refused(service.compose(oid(bare), json("force", Boolean.TRUE), o, SD_CONTEXT), 409, noWorld, "bare forced compose");
			refused(service.proposeLocations(oid(bare), "{}", o), 409, noWorld, "bare propose");
			refused(service.composeScene(oid(bare), NIL_UUID, "{}", o, NO_SD_CONTEXT), 404, "not found in this book", "bare scene compose");
			assertEquals("nothing queued", 0, AsyncJobRegistry.list(owner).stream().filter(j -> !j.isTerminal()).count());
		}
		finally {
			cleanupBare(bare);
		}
	}

	/// Facade-level value checks on a real, writable V3 book: a named chat config that does not exist is a 404 on all
	/// three routes before anything is queued; Quick start without an SD backend is a 500 before anything is queued; and
	/// a scene-less book answers 200 submitted:false "The book has no scenes" with the tally - the idempotent
	/// compose-on-open a client may issue on every V3 open.
	@Test
	public void TestOwnerValueChecksAndSceneLessNoOp() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock o = requestAs(owner);
			v3();
			String cnf = "Chat config not found: " + MISSING_CONFIG;
			refused(service.compose(f.bookOid, json("chatConfigName", MISSING_CONFIG), o, SD_CONTEXT), 404, cnf, "compose with unknown config");
			refused(service.compose(f.bookOid, json("chatConfigName", "  " + MISSING_CONFIG + " "), o, SD_CONTEXT), 404, cnf, "compose with unknown config is trimmed");
			refused(service.proposeLocations(f.bookOid, json("chatConfigName", MISSING_CONFIG), o), 404, cnf, "propose with unknown config");
			refused(service.compose(f.bookOid, json("quickStart", Boolean.TRUE), o, NO_SD_CONTEXT), 500, "SD server not configured", "quick start without SD");
			refused(service.compose(f.bookOid, json("quickStart", "true"), o, NO_SD_CONTEXT), 500, "SD server not configured", "quick start as a string without SD");
			assertEquals("no job runs for the owner after the refusals", 0, AsyncJobRegistry.list(owner).stream().filter(j -> !j.isTerminal()).count());

			Map<String, Object> out = ok(service.compose(f.bookOid, "{}", o, SD_CONTEXT), "scene-less compose");
			assertEquals(f.bookOid, out.get("bookObjectId"));
			assertEquals("pb.compose", out.get("kind"));
			assertEquals(Boolean.FALSE, out.get("submitted"));
			assertEquals(Boolean.FALSE, out.get("reused"));
			assertEquals("The book has no scenes", out.get("reason"));
			assertEquals(0, num(out, "sceneCount"));
			assertEquals(0, num(out, "scenesToCompose"));
			assertEquals(0, num(out, "scenesCurrent"));
			assertEquals(0, num(out, "pagesSkipped"));
			assertEquals(Boolean.FALSE, out.get("quickStart"));
			assertEquals(Boolean.FALSE, out.get("force"));
			assertNull("nothing queued, no jobId", out.get("jobId"));
			assertNull("nothing queued, no jobStatus", out.get("jobStatus"));
			/// the options are echoed even when nothing is queued (quickStart with a resolvable SD context, force as a string)
			Map<String, Object> echoed = ok(service.compose(f.bookOid, json("quickStart", Boolean.TRUE, "force", "true"), o, SD_CONTEXT), "scene-less compose with options");
			assertEquals(Boolean.FALSE, echoed.get("submitted"));
			assertEquals(Boolean.TRUE, echoed.get("quickStart"));
			assertEquals(Boolean.TRUE, echoed.get("force"));
			assertEquals("The book has no scenes", echoed.get("reason"));
			/// per-scene: an unknown scene is a 404 after the book and grant gates
			refused(service.composeScene(f.bookOid, NIL_UUID, "{}", o, NO_SD_CONTEXT), 404, "not found in this book", "scene compose of unknown scene");
		}
		finally {
			cleanup(f);
		}
	}

	/// The whole lifecycle of one scene through the three routes, with the LLM unreachable (no chat config resolvable -
	/// asserted, see the class comment):
	///   1. compose           -> 202; the job's migration pass (P4-10) writes a MIGRATED composition from the scene's own
	///                           fields BEFORE the loop, so the loop finds an existing row and the LLM miss is a hardFailure
	///                           that leaves it untouched; then the location pass runs and reports its hard failure (the
	///                           scene has a setting)
	///   2. compose again     -> 202 again: a MIGRATED, unedited composition still needs a real compose (needsCompose); the
	///                           migration now creates nothing and the LLM miss is the same hardFailure on the same row
	///   3. scene compose     -> 200 hardFailure "existing composition left untouched"; 404 for a named missing config
	///   4. user-edited       -> scene compose is skippedUserEdited (nothing written); force is the LLM miss again, the
	///                           edited row untouched; whole-book compose is now 200 "Every scene is composed"
	///   5. propose           -> 202, its own job: the pass on the one setting is the same hard failure, nothing proposed
	@Test
	public void TestOwnerComposeJobLifecycle() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock o = requestAs(owner);
			v3();
			assertNull("precondition: this case relies on no 'generalChat' chat config being resolvable as the owner on this harness",
				ChatUtil.resolveConfig(owner, OlioModelNames.MODEL_CHAT_CONFIG, "generalChat", null));
			BaseRecord scene = addScene(f, 1);
			String sceneOid = oid(scene);

			/// 1. first compose: 202; the migration pass writes the MIGRATED row and the loop's LLM miss leaves it untouched
			Map<String, Object> submit = accepted(service.compose(f.bookOid, "{}", o, SD_CONTEXT), "compose");
			assertEquals(f.bookOid, submit.get("bookObjectId"));
			assertEquals("pb.compose", submit.get("kind"));
			assertEquals(Boolean.FALSE, submit.get("reused"));
			assertEquals(1, num(submit, "sceneCount"));
			assertEquals(1, num(submit, "scenesToCompose"));
			assertEquals(0, num(submit, "scenesCurrent"));
			assertEquals(0, num(submit, "pagesSkipped"));
			Map<String, Object> result = awaitJob(owner, submit, "compose job");
			assertEquals(f.bookOid, result.get("bookObjectId"));
			assertEquals(f.slug, result.get("slug"));
			assertEquals(1, num(result, "sceneCount"));
			assertEquals(1, num(result, "visited"));
			assertEquals(0, num(result, "composed"));
			assertEquals(0, num(result, "current"));
			/// P4-10: the migration pass, not the loop, writes the MIGRATED row, so the loop's own "migrated" fallback count is
			/// 0 and the LLM miss over that existing row is the one hard failure (mirror of TestPbComposeJob case06).
			assertEquals(0, num(result, "migrated"));
			assertEquals(1, num(result, "hardFailures"));
			Map<String, Object> migration = map(result, "migration");
			assertNull("the migration did not throw: " + migration, migration.get("error"));
			assertEquals("the migration scanned the one scene", 1, num(migration, "scanned"));
			assertEquals("a MIGRATED row was written before the loop", 1, num(migration, "created"));
			assertEquals(0, num(migration, "failed"));
			assertEquals(0, num(migration, "skippedExisting"));
			assertEquals(0, num(result, "skippedUserEdited"));
			assertEquals(0, num(result, "quickStartRendered"));
			assertEquals(1, num(result, "lastSceneIndex"));
			assertEquals(Boolean.FALSE, result.get("cancelled"));
			assertEquals(Boolean.FALSE, result.get("interrupted"));
			assertNull("no resume note on a finished job", result.get("note"));
			List<Object> scenes = list(result, "scenes");
			assertEquals("one scene outcome", 1, scenes.size());
			@SuppressWarnings("unchecked")
			Map<String, Object> outcome = (Map<String, Object>) scenes.get(0);
			assertEquals(sceneOid, outcome.get("sceneObjectId"));
			assertEquals(1, num(outcome, "sceneIndex"));
			assertEquals("hardFailure", outcome.get("status"));
			assertEquals("MIGRATED", outcome.get("source"));
			assertEquals("NONE", outcome.get("castTier"));
			assertEquals(0, num(outcome, "actorCount"));
			assertEquals(0, num(outcome, "elementCount"));
			String why = String.valueOf(outcome.get("error"));
			assertTrue("the outcome says why the migration's row was left alone: " + why, why.contains("LLM unavailable") && why.contains("existing composition left untouched"));
			String compOid = String.valueOf(outcome.get("compositionObjectId"));
			assertNotEquals("the migration's MIGRATED composition is reported", "null", compOid);
			assertNull("no draft render without quickStart", outcome.get("draftRender"));
			BaseRecord comp = PbComposeUtil.readComposition(owner, compOid, orgId);
			assertNotNull("the composition reads back as the owner", comp);
			assertEquals("MIGRATED", String.valueOf((Object) comp.get(OlioFieldNames.FIELD_PB_SOURCE)).toUpperCase());
			assertEquals("SCENE", String.valueOf((Object) comp.get(OlioFieldNames.FIELD_PB_PURPOSE)).toUpperCase());
			assertEquals(SCENE_SETTING, comp.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertTrue("a MIGRATED, unedited scene composition still needs a real compose", PbComposeUtil.needsCompose(comp));
			/// the job ends with the whole-book location pass, which on this harness is the LLM miss
			assertEquals(Boolean.TRUE, result.get("locationPassRan"));
			Map<String, Object> pass = map(result, "locationPass");
			assertEquals("the one setting was considered", 1, num(pass, "scenesConsidered"));
			assertEquals(1, num(pass, "chunks"));
			assertEquals(0, num(pass, "chunksParsed"));
			assertEquals(Boolean.TRUE, pass.get("hardFailure"));
			assertNotEquals(Boolean.TRUE, pass.get("cancelled"));
			assertTrue("nothing proposed", list(pass, "proposals").isEmpty());
			List<String> pw = strings(list(pass, "warnings"));
			assertTrue("the pass says the LLM could not be used: " + pw, pw.stream().anyMatch(w -> w.contains("the LLM could not be used (hard failure)")));

			/// 2. compose again: still to-compose (MIGRATED + unedited), and the miss now leaves the row alone
			Map<String, Object> again = accepted(service.compose(f.bookOid, "{}", o, SD_CONTEXT), "second compose");
			assertEquals(1, num(again, "scenesToCompose"));
			assertEquals(0, num(again, "scenesCurrent"));
			assertNotEquals("a finished job is never reused", submit.get("jobId"), again.get("jobId"));
			Map<String, Object> result2 = awaitJob(owner, again, "second compose job");
			assertEquals(1, num(result2, "visited"));
			assertEquals(0, num(result2, "migrated"));
			assertEquals(0, num(result2, "composed"));
			assertEquals(1, num(result2, "hardFailures"));
			assertEquals("the migration creates nothing once the scene has a row", 0, num(map(result2, "migration"), "created"));
			assertEquals(1, num(map(result2, "migration"), "skippedExisting"));
			@SuppressWarnings("unchecked")
			Map<String, Object> outcome2 = (Map<String, Object>) list(result2, "scenes").get(0);
			assertEquals("hardFailure", outcome2.get("status"));
			assertEquals(compOid, outcome2.get("compositionObjectId"));
			assertTrue("the existing composition is left untouched: " + outcome2.get("error"), String.valueOf(outcome2.get("error")).contains("existing composition left untouched"));
			assertEquals(Boolean.TRUE, result2.get("locationPassRan"));

			/// 3. the synchronous per-scene route on the same scene
			refused(service.composeScene(f.bookOid, sceneOid, json("chatConfigName", MISSING_CONFIG), o, NO_SD_CONTEXT), 404, "Chat config not found: " + MISSING_CONFIG, "scene compose with unknown config");
			Map<String, Object> one = ok(service.composeScene(f.bookOid, sceneOid, "{}", o, NO_SD_CONTEXT), "scene compose");
			assertEquals(f.bookOid, one.get("bookObjectId"));
			assertEquals(sceneOid, one.get("sceneObjectId"));
			assertEquals(1, num(one, "sceneIndex"));
			assertEquals(Boolean.FALSE, one.get("written"));
			assertEquals(Boolean.TRUE, one.get("hardFailure"));
			assertEquals(Boolean.FALSE, one.get("migratedFallback"));
			assertEquals(Boolean.FALSE, one.get("skippedUserEdited"));
			assertEquals(Boolean.FALSE, one.get("cancelled"));
			assertTrue("failureReason: " + one.get("failureReason"), String.valueOf(one.get("failureReason")).contains("existing composition left untouched"));
			assertEquals(compOid, one.get("compositionObjectId"));
			assertEquals("MIGRATED", one.get("source"));
			assertEquals(compOid, map(one, "composition").get("objectId"));
			assertTrue("no actors", list(one, "actors").isEmpty());
			assertTrue("no elements", list(one, "elements").isEmpty());

			/// 4. the user edits the composition: the per-scene route steps aside, force overrides, the book is "composed"
			markUserEdited(compOid);
			Map<String, Object> skipped = ok(service.composeScene(f.bookOid, sceneOid, "{}", o, NO_SD_CONTEXT), "scene compose of a user-edited composition");
			assertEquals(Boolean.TRUE, skipped.get("skippedUserEdited"));
			assertEquals(Boolean.FALSE, skipped.get("written"));
			assertEquals(Boolean.FALSE, skipped.get("hardFailure"));
			assertEquals(compOid, skipped.get("compositionObjectId"));
			assertNull("nothing failed, so no reason", skipped.get("failureReason"));
			Map<String, Object> forced = ok(service.composeScene(f.bookOid, sceneOid, json("force", Boolean.TRUE), o, NO_SD_CONTEXT), "forced scene compose of a user-edited composition");
			assertEquals(Boolean.FALSE, forced.get("skippedUserEdited"));
			assertEquals(Boolean.TRUE, forced.get("hardFailure"));
			assertEquals(compOid, forced.get("compositionObjectId"));
			BaseRecord stillEdited = PbComposeUtil.readComposition(owner, compOid, orgId);
			assertEquals("the edited row survived the forced miss", Boolean.TRUE, stillEdited.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertFalse("a user-edited composition is current", PbComposeUtil.needsCompose(stillEdited));
			Map<String, Object> composedBook = ok(service.compose(f.bookOid, "{}", o, SD_CONTEXT), "compose of a composed book");
			assertEquals(Boolean.FALSE, composedBook.get("submitted"));
			assertEquals("Every scene is composed", composedBook.get("reason"));
			assertEquals(1, num(composedBook, "sceneCount"));
			assertEquals(0, num(composedBook, "scenesToCompose"));
			assertEquals(1, num(composedBook, "scenesCurrent"));
			assertNull("nothing queued", composedBook.get("jobId"));
			/// force on the book counts the edited scene again - and is 202, so it is drained before the fixture goes
			Map<String, Object> forcedBook = accepted(service.compose(f.bookOid, json("force", Boolean.TRUE), o, SD_CONTEXT), "forced compose of a composed book");
			assertEquals(1, num(forcedBook, "scenesToCompose"));
			assertEquals(Boolean.TRUE, forcedBook.get("force"));
			Map<String, Object> result3 = awaitJob(owner, forcedBook, "forced compose job");
			assertEquals(1, num(result3, "hardFailures"));
			assertEquals(0, num(result3, "skippedUserEdited"));
			assertEquals(Boolean.TRUE, result3.get("force"));

			/// 5. the location pass alone, as its own job
			Map<String, Object> psubmit = accepted(service.proposeLocations(f.bookOid, "{}", o), "propose");
			assertEquals("pb.proposeLocations", psubmit.get("kind"));
			assertEquals(f.bookOid, psubmit.get("bookObjectId"));
			assertEquals(Boolean.FALSE, psubmit.get("reused"));
			Map<String, Object> ppass = awaitJob(owner, psubmit, "propose job");
			assertEquals(f.bookOid, ppass.get("bookObjectId"));
			assertEquals("pb.proposeLocations", ppass.get("kind"));
			assertEquals(1, num(ppass, "scenesConsidered"));
			assertEquals(1, num(ppass, "chunks"));
			assertEquals(Boolean.TRUE, ppass.get("hardFailure"));
			assertTrue("nothing proposed", list(ppass, "proposals").isEmpty());
			assertNotNull("durationMs is reported", ppass.get("durationMs"));
			Map<String, Object> proposed = ok(service.listLocationProposals(f.bookOid, null, o), "proposed list");
			assertTrue("no proposals were written by the failed pass", list(proposed, "proposals").isEmpty());
		}
		finally {
			cleanup(f);
		}
	}

	/// P4-12: the synchronous per-scene route on a NEVER-composed scene whose legacy sdPrompt is locked, with the LLM
	/// unreachable (no `generalChat` resolvable - asserted). The facade composes migrate-first: the MIGRATED row is
	/// written from the scene's own fields BEFORE the LLM attempt (the only path that copies sdPrompt -> promptOverride
	/// and keeps promptLocked), and the LLM miss over it is NOT the "existing composition left untouched" hardFailure
	/// the raw compose reports - the MIGRATED row stands and the body says so: written, migratedFallback, migratedFirst,
	/// source MIGRATED, failureReason "...stands". This fixture scene has no render node, so nodeStale is false (present:
	/// booleans survive NON_EMPTY) and nodeStatus is null (ABSENT on the wire, see the class WIRE NOTE). A second POST
	/// finds the row: the plain hardFailure, nothing re-migrated (migratedFirst false), revision still 1, nothing staled.
	@Test
	public void TestOwnerComposeSceneMigratesLockedPromptFirst() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock o = requestAs(owner);
			v3();
			assertNull("precondition: this case relies on no 'generalChat' chat config being resolvable as the owner on this harness",
				ChatUtil.resolveConfig(owner, OlioModelNames.MODEL_CHAT_CONFIG, "generalChat", null));
			BaseRecord scene = addScene(f, 1);
			String sceneOid = oid(scene);
			String token = "PBTOKEN-" + UUID.randomUUID().toString().substring(0, 8);
			String lockedPrompt = "rain on the shutters, a fiddler tuning up, lantern light, " + token;
			BaseRecord lock = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE, new String[] {
				FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_CB_SD_PROMPT, OlioFieldNames.FIELD_PB_PROMPT_LOCKED });
			lock.set(FieldNames.FIELD_ID, scene.get(FieldNames.FIELD_ID));
			lock.set(FieldNames.FIELD_OBJECT_ID, sceneOid);
			lock.set(FieldNames.FIELD_NAME, scene.get(FieldNames.FIELD_NAME));
			lock.set(OlioFieldNames.FIELD_CB_SD_PROMPT, lockedPrompt);
			lock.set(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.TRUE);
			assertNotNull("locked legacy prompt patch must persist", IOSystem.getActiveContext().getAccessPoint().update(owner, lock));
			BaseRecord locked = PbBookUtil.readScene(owner, sceneOid, orgId);
			assertNotNull("locked page must read back", locked);
			assertEquals("precondition: sdPrompt stored", lockedPrompt, locked.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
			assertEquals("precondition: promptLocked stored", Boolean.TRUE, locked.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			assertNotNull("the world book must read back as the owner", book);
			Long bookGroupId = book.get(FieldNames.FIELD_GROUP_ID);
			assertNotNull("book group", bookGroupId);
			assertNull("precondition: the scene has never been composed", PbComposeUtil.findSceneComposition(owner, locked, bookGroupId.longValue(), orgId));

			/// 1. migrate-first: the MIGRATED row stands over the LLM miss
			long t0 = System.currentTimeMillis();
			Map<String, Object> one = ok(service.composeScene(f.bookOid, sceneOid, "{}", o, NO_SD_CONTEXT), "migrate-first scene compose");
			logger.info("[p412-route] compose 1 (" + (System.currentTimeMillis() - t0) + " ms, recorded not asserted): " + one);
			assertEquals(f.bookOid, one.get("bookObjectId"));
			assertEquals(sceneOid, one.get("sceneObjectId"));
			assertEquals(1, num(one, "sceneIndex"));
			assertEquals("P4-12: the MIGRATED row written first is the outcome, so it IS written", Boolean.TRUE, one.get("written"));
			assertEquals("P4-12: reported as the MIGRATED fallback over the LLM miss", Boolean.TRUE, one.get("migratedFallback"));
			assertEquals("P4-12: the body says the migration ran first", Boolean.TRUE, one.get("migratedFirst"));
			assertEquals("P4-12: not the raw 'existing composition left untouched' hard failure", Boolean.FALSE, one.get("hardFailure"));
			assertEquals(Boolean.FALSE, one.get("skippedUserEdited"));
			assertEquals(Boolean.FALSE, one.get("cancelled"));
			assertEquals("MIGRATED", one.get("source"));
			String reason = String.valueOf(one.get("failureReason"));
			assertTrue("the reason names the LLM miss and says the MIGRATED row stands: " + reason, reason.contains("LLM unavailable") && reason.endsWith("stands"));
			assertTrue("the reason is also a warning: " + one.get("warnings"), strings(list(one, "warnings")).contains(reason));
			assertTrue("nodeStale is on the wire even when false", one.containsKey("nodeStale"));
			assertEquals("no render node on this fixture scene, so nothing was staled", Boolean.FALSE, one.get("nodeStale"));
			assertNull("no render node: nodeStatus is null, which NON_EMPTY leaves off the wire", one.get("nodeStatus"));
			String compOid = String.valueOf(one.get("compositionObjectId"));
			assertNotEquals("the MIGRATED composition is reported", "null", compOid);
			Map<String, Object> comp = map(one, "composition");
			assertEquals(compOid, comp.get("objectId"));
			assertEquals("MIGRATED", comp.get("source"));
			assertEquals("SCENE", comp.get("purpose"));
			assertEquals("a fresh MIGRATED row is revision 1", 1, num(comp, "revision"));
			assertEquals("P4-12: the lock rode onto the composition", Boolean.TRUE, comp.get("promptLocked"));
			String override = String.valueOf(comp.get("promptOverride"));
			assertTrue("P4-12: promptOverride carries the token: " + override, override.contains(token));
			assertEquals("promptOverride is the scene's sdPrompt verbatim", lockedPrompt, override);
			assertEquals(SCENE_SETTING, comp.get("settingText"));
			assertEquals(Boolean.FALSE, comp.get("userEdited"));
			BaseRecord stored = PbComposeUtil.readComposition(owner, compOid, orgId);
			assertNotNull("the composition reads back as the owner", stored);
			assertEquals("MIGRATED", String.valueOf((Object) stored.get(OlioFieldNames.FIELD_PB_SOURCE)).toUpperCase());
			assertEquals(Integer.valueOf(1), stored.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("stored promptOverride", lockedPrompt, stored.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals("stored promptLocked", Boolean.TRUE, stored.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertTrue("a MIGRATED, unedited scene composition still needs a real compose", PbComposeUtil.needsCompose(stored));

			/// 2. the second POST finds the row: the plain hard failure, nothing re-migrated, nothing staled
			t0 = System.currentTimeMillis();
			Map<String, Object> two = ok(service.composeScene(f.bookOid, sceneOid, "{}", o, NO_SD_CONTEXT), "second scene compose");
			logger.info("[p412-route] compose 2 (" + (System.currentTimeMillis() - t0) + " ms, recorded not asserted): " + two);
			assertEquals(Boolean.FALSE, two.get("written"));
			assertEquals(Boolean.TRUE, two.get("hardFailure"));
			assertEquals("an existing row is never re-migrated", Boolean.FALSE, two.get("migratedFirst"));
			assertEquals(Boolean.FALSE, two.get("migratedFallback"));
			assertEquals(Boolean.FALSE, two.get("skippedUserEdited"));
			assertEquals(Boolean.FALSE, two.get("nodeStale"));
			assertNull("nothing written, no node: no status", two.get("nodeStatus"));
			assertTrue("failureReason: " + two.get("failureReason"), String.valueOf(two.get("failureReason")).contains("existing composition left untouched"));
			assertEquals(compOid, two.get("compositionObjectId"));
			assertEquals("MIGRATED", two.get("source"));
			Map<String, Object> comp2 = map(two, "composition");
			assertEquals(compOid, comp2.get("objectId"));
			assertEquals("the untouched row is still revision 1", 1, num(comp2, "revision"));
			assertEquals(Boolean.TRUE, comp2.get("promptLocked"));
			assertEquals(lockedPrompt, comp2.get("promptOverride"));
			BaseRecord still = PbComposeUtil.readComposition(owner, compOid, orgId);
			assertEquals(Integer.valueOf(1), still.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals(lockedPrompt, still.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals(Boolean.TRUE, still.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		}
		finally {
			cleanup(f);
		}
	}
}
