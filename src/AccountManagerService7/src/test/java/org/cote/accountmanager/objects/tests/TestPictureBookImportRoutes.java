package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
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
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
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
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/// §24 I-1 / KI-89 (b) (PictureBook V3 Implementation, 2026-11-13): the wizard's import route after the bookless
/// (PB1) import was removed. Exercised through the real service method -> PbServiceFacade.createFromScenesAndCompose
/// -> real AccessPoint/PBAC -> the real (H2) database, with HttpServletRequestMock + UserPrincipal exactly as
/// TestPictureBookNewBookRoutes does. Nothing is mocked; no asserting actor is ever the admin or the olio principal.
///
///   POST /{work}/create-from-scenes   createFromScenes   400 blank pb2BookObjectId / 404 unknown or unreadable book /
///                                                        403 a reader of the book - every one BEFORE anything is written
///   POST /{work}/extract              (removed)          no PictureBookService method carries that @Path any more
///   GET  /books                       listBooks          200, still lists the owner's book
///   GET  /{book}/pages                bookPages          200, still answers for the owner (and 404 for a stranger)
///
/// THE CONTRACT THIS PINS. Before I-1 a create-from-scenes body WITHOUT pb2BookObjectId was accepted and wrote a
/// `~/Data/PictureBooks/<bookName>` group + scene notes + a .pictureBookMeta that no V3 surface (/books, /pages,
/// /workflow, the /v2 reader) could list - the PB1 reader that could was removed at Phase 10 (KI-89). Now the id
/// is REQUIRED and the three gates (400 blank -> 404 unknown/unreadable -> 403 not the book's updater) run in the
/// facade before PictureBookUtil.createFromScenes is entered, so a refused import leaves NOTHING behind: the proof
/// here is that the caller's `~/Data/PictureBooks/<name>` group - the first thing the import used to create - does
/// not exist afterwards, and that the work note is never even looked up (the gate cases use a work the caller
/// cannot read or that does not exist, and the refusal is still the book's, never "Work not found").
///
/// FIXTURE. TestPictureBookNewBookRoutes' hand-built H2 world fixture (an olio-owned book in a Books-universe world,
/// the owner enrolled as Writer with CRUD on the book group and the world tree, an optional Reader role with Read)
/// with its own user names and slug prefix so the classes never share rows. The positive import (an owner on their
/// own book, scenes + characters created, the compose job queued) needs the book's Olio world context and an LLM
/// and is pinned by the Objects7 TestPictureBookKnownIssues KI-42 test (am7db) and the Docker Playwright specs
/// (pictureBookCompose / pictureBookV3Handoff); it is deliberately NOT repeated here.
public class TestPictureBookImportRoutes extends BaseTest {

	private static final String OWNER_NAME = "pbimprtowner";
	private static final String READER_NAME = "pbimprtreader";
	private static final String STRANGER_NAME = "pbimprtstranger";
	private static final String WORK_GROUP_PATH = "~/PbImportRoutes";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String[] DATA_AND_GROUP = new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() };
	private static final String[] CRUD = new String[] { "Read", "Update", "Create", "Delete" };
	private static final String[] READ = new String[] { "Read" };
	private static final String NOT_FOUND = "Book not found";
	private static final String ID_REQUIRED = "pb2BookObjectId is required";
	private static final String KI_89 = "KI-89";
	private static final String REFUSED_IMPORT = "Not authorized to import scenes in this book";
	/// The group the bookless import used to create first (PictureBookUtil.ensureBookGroup): `~/Data/PictureBooks/<name>`.
	private static final String PB1_GROUP_BASE = "~/Data/PictureBooks/";

	private static final ServletContext NO_DATAGEN = initParams(Map.of());

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

	// ─────────────────────────────── fixture (TestPictureBookNewBookRoutes' shape) ───────────────────────────────

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

	/// An olio-owned book in its own Books-universe world, the owner enrolled as Writer with CRUD on the book group
	/// and the whole world tree, and - with {@code withReader} - the reader enrolled as Reader with Read on the same.
	private Fixture createWorldBook(boolean withReader) throws Exception {
		Fixture f = new Fixture();
		f.slug = "p24i1-" + UUID.randomUUID().toString().substring(0, 12);
		String worldsPath = booksWorldsPath();

		BaseRecord created = WorldUtil.getCreateWorld(olioUser, worldsPath, f.slug, new String[0]);
		assertNotNull("Book world is null", created);
		f.world = WorldUtil.findWorld(olioUser, worldsPath, f.slug);
		assertNotNull("Book world could not be re-found", f.world);
		ioContext.getReader().populate(f.world, 2);

		String bookGroupPath = PbBookUtil.bookGroupPath(f.slug);
		BaseRecord bookDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, bookGroupPath, "DATA", orgId);
		assertNotNull("Book group is null", bookDir);
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

	/// The wizard's "work": a data.note the owner wrote, in a group the owner owns (the shape the Objects7 KI-42 test
	/// and TestPbGenderUtil use). The reader and the stranger cannot read it.
	private BaseRecord createWork(String tag) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, WORK_GROUP_PATH);
		plist.parameter(FieldNames.FIELD_NAME, "import-src-" + tag);
		BaseRecord work = ioContext.getFactory().newInstance(ModelNames.MODEL_NOTE, owner, null, plist);
		work.set("text", "A short manuscript. Nobody in it is described.");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, work);
		assertNotNull("Failed to create the work note", created);
		assertNotNull("Work note carries no objectId", oid(created));
		return created;
	}

	private void deleteWork(BaseRecord work) {
		if(work != null) {
			assertTrue("Failed to delete the work note", IOSystem.getActiveContext().getAccessPoint().delete(owner, work));
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPictureBookImportRoutes.class.getClassLoader(),
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

	/// The wizard's body (sceneExtractor.js): one curated scene, no characters, the book id as given (absent when null).
	/// Hand-built, NOT JSONUtil.exportObject(map): that mapper is Include.NON_EMPTY and would silently drop the
	/// empty-string id and the empty characters list, so the "empty" case would never reach the wire (model-api.md).
	private static String importBody(String pb2BookObjectId, String bookName) {
		StringBuilder b = new StringBuilder("{");
		if(pb2BookObjectId != null) {
			b.append("\"pb2BookObjectId\":").append(jsonString(pb2BookObjectId)).append(',');
		}
		b.append("\"bookName\":").append(jsonString(bookName)).append(',');
		b.append("\"genre\":\"mystery\",");
		b.append("\"sceneList\":[{\"index\":0,\"title\":\"The Cellar\",")
			.append("\"description\":\"Gwendolyn Marsh came down the cellar steps first and did not look back.\",")
			.append("\"setting\":\"A stone cellar lit by one lamp\"}],");
		b.append("\"characters\":[]}");
		return b.toString();
	}

	private static String jsonString(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private Response importScenes(BaseRecord u, String workOid, String pb2BookObjectId, String bookName) {
		return service.createFromScenes(workOid, importBody(pb2BookObjectId, bookName), requestAs(u), NO_DATAGEN);
	}

	private Response books(BaseRecord u) {
		return service.listBooks(requestAs(u));
	}

	private Response pages(BaseRecord u, String bookOid) {
		return service.bookPages(bookOid, requestAs(u));
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

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> listBody(Response r, String what) {
		assertEquals(what + " must be 200, body: " + r.getEntity(), 200, r.getStatus());
		Object entity = r.getEntity();
		assertNotNull(what + ": response entity is null", entity);
		List<Map<String, Object>> l = JSONUtil.importObject(entity.toString(), List.class);
		assertNotNull(what + ": body did not parse as a JSON array: " + entity, l);
		return l;
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

	/// A refusal that is the BOOK's, not the work's: the gates run before the work is ever looked up.
	private void refusedBeforeTheWork(Response r, int status, String contains, String what) {
		refused(r, status, contains, what);
		String msg = errorText(r);
		assertFalse(what + " must be refused before the work is looked up, but was: " + msg, msg.contains("Work not found"));
	}

	/// Nothing the refused import may have written: the caller's `~/Data/PictureBooks/<name>` group - the first thing
	/// the import creates (PictureBookUtil.ensureBookGroup) - does not exist.
	private void assertNoPb1Group(BaseRecord u, String name, String what) {
		assertNull(what + ": no " + PB1_GROUP_BASE + name + " group may exist under " + u.get(FieldNames.FIELD_NAME) + "'s home",
			ioContext.getPathUtil().findPath(u, ModelNames.MODEL_GROUP, PB1_GROUP_BASE + name, "DATA", orgId));
	}

	private static String uniqueName(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String pathOf(Method m) {
		Path p = m.getAnnotation(Path.class);
		return (p != null ? p.value() : null);
	}

	// ─────────────────────────────── the gates: 400 blank -> 404 unknown/unreadable -> 403 reader ───────────────────────────────

	@Test
	public void TestBlankBookIdIs400AndWritesNothing() throws Exception {
		BaseRecord work = createWork("blank");
		String absent = uniqueName("I1 Absent");
		String empty = uniqueName("I1 Empty");
		try {
			/// Absent entirely (the pre-I-1 bookless body) and present-but-empty are the same refusal.
			refusedBeforeTheWork(importScenes(owner, oid(work), null, absent), 400, ID_REQUIRED, "owner, no pb2BookObjectId");
			assertTrue("the 400 must name KI-89", errorText(importScenes(owner, oid(work), null, absent)).contains(KI_89));
			refusedBeforeTheWork(importScenes(owner, oid(work), "", empty), 400, ID_REQUIRED, "owner, empty pb2BookObjectId");
			refusedBeforeTheWork(importScenes(owner, oid(work), "   ", empty), 400, ID_REQUIRED, "owner, blank pb2BookObjectId");
			assertNoPb1Group(owner, absent, "absent id");
			assertNoPb1Group(owner, empty, "empty id");
			/// And the work is still exactly as the owner left it (one note, nothing attached).
			assertNotNull("the work must still read back as the owner", IOSystem.getActiveContext().getAccessPoint().findByObjectId(owner, ModelNames.MODEL_NOTE, oid(work)));
		}
		finally {
			deleteWork(work);
		}
	}

	@Test
	public void TestUnknownBookIs404AndWritesNothing() throws Exception {
		BaseRecord work = createWork("unknown");
		String name = uniqueName("I1 Unknown");
		try {
			refusedBeforeTheWork(importScenes(owner, oid(work), NIL_UUID, name), 404, NOT_FOUND, "owner, nil book id");
			refusedBeforeTheWork(importScenes(owner, oid(work), UUID.randomUUID().toString(), name), 404, NOT_FOUND, "owner, random book id");
			assertNoPb1Group(owner, name, "unknown book");
		}
		finally {
			deleteWork(work);
		}
	}

	@Test
	public void TestStrangerIs404ReaderIs403AndNothingIsWritten() throws Exception {
		Fixture f = createWorldBook(true);
		BaseRecord work = createWork("roles");
		String name = uniqueName("I1 Roles");
		try {
			/// The stranger cannot read the book at all: requireBook's 404, indistinguishable from a book that does not exist.
			refusedBeforeTheWork(importScenes(stranger, UUID.randomUUID().toString(), f.bookOid, name), 404, NOT_FOUND, "stranger import");
			refused(pages(stranger, f.bookOid), 404, NOT_FOUND, "stranger GET /pages");
			/// The reader can read the book (GET /pages answers) but is not its updater: requireBookUpdate's 403.
			listBody(pages(reader, f.bookOid), "reader GET /pages");
			refusedBeforeTheWork(importScenes(reader, UUID.randomUUID().toString(), f.bookOid, name), 403, REFUSED_IMPORT, "reader import");
			/// Even a work the reader could never read is not what refused them: the book gate came first.
			refusedBeforeTheWork(importScenes(reader, oid(work), f.bookOid, name), 403, REFUSED_IMPORT, "reader import of the owner's work");
			/// Nothing written: no PB1 group under either caller's home for the body's name OR for the book's slug (the
			/// name the bound import would use), and the book has no scenes.
			assertNoPb1Group(stranger, name, "stranger");
			assertNoPb1Group(stranger, f.slug, "stranger (slug)");
			assertNoPb1Group(reader, name, "reader");
			assertNoPb1Group(reader, f.slug, "reader (slug)");
			BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			assertNotNull("the world book must read back as the owner", book);
			assertTrue("a refused import must leave the book without scenes", PbBookUtil.listScenes(owner, book).isEmpty());
		}
		finally {
			deleteWork(work);
			cleanup(f);
		}
	}

	// ─────────────────────────────── the removed route, and the surfaces that must be unaffected ───────────────────────────────

	@Test
	public void TestExtractRouteIsGoneAndCreateFromScenesRemains() {
		List<String> paths = new ArrayList<>();
		boolean createFromScenes = false;
		for(Method m : PictureBookService.class.getDeclaredMethods()) {
			String p = pathOf(m);
			if(p == null) {
				continue;
			}
			paths.add(p);
			if(p.endsWith("/extract")) {
				assertTrue("POST /{work}/extract was removed with the bookless import (KI-89 (b)) but " + m.getName() + " still carries @Path(" + p + ")", false);
			}
			if(p.endsWith("/create-from-scenes")) {
				createFromScenes = true;
			}
		}
		assertTrue("PictureBookService must still declare routes (reflection looked at the wrong class?)", paths.size() > 10);
		assertTrue("POST /{work}/create-from-scenes must remain the one import route: " + paths, createFromScenes);
	}

	@Test
	public void TestBooksAndPagesStillAnswerForTheOwner() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			List<Map<String, Object>> list = listBody(books(owner), "owner GET /books");
			boolean listed = false;
			for(Map<String, Object> dto : list) {
				if(f.bookOid.equals(String.valueOf(dto.get("objectId")))) {
					listed = true;
					assertEquals("the listed slug", f.slug, String.valueOf(dto.get("slug")));
				}
			}
			assertTrue("GET /books as the owner must list the owner's book " + f.slug + " in " + list, listed);
			assertTrue("GET /pages on a book with no scenes is an empty array", listBody(pages(owner, f.bookOid), "owner GET /pages").isEmpty());
			/// A stranger still sees neither: the removal changed nothing about the read surfaces.
			boolean strangerSees = false;
			for(Map<String, Object> dto : listBody(books(stranger), "stranger GET /books")) {
				if(f.bookOid.equals(String.valueOf(dto.get("objectId")))) {
					strangerSees = true;
				}
			}
			assertFalse("GET /books as a stranger must not list " + f.slug, strangerSees);
			refused(pages(stranger, f.bookOid), 404, NOT_FOUND, "stranger GET /pages");
		}
		finally {
			cleanup(f);
		}
	}
}
