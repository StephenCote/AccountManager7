package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import org.cote.accountmanager.olio.picturebook.PbComposeUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
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
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// P4-8 (PictureBook V3 Implementation §12.4): the eight composition-row routes on PictureBookService exercised through
/// the real service methods -> PbServiceFacade -> PbComposeUtil -> real AccessPoint/PBAC -> the real (H2) database, with
/// HttpServletRequestMock + UserPrincipal exactly as TestPictureBookPromoteRoutes does. Nothing is mocked; no asserting
/// actor is ever the admin or the olio principal.
///
///   GET    /{book}/scene/{scene}/composition                      getSceneComposition     200 {composition, actors, elements}
///   PATCH  /{book}/scene/{scene}/composition                      patchSceneComposition   200 {revision, nodeStale, updated}
///   POST   /{book}/composition/{comp}/actor                       addCompositionActor     200 {actorObjectId, actor, created}
///   PATCH  /{book}/composition/{comp}/actor/{actor}               patchCompositionActor   200 {actor, updated}
///   DELETE /{book}/composition/{comp}/actor/{actor}               deleteCompositionActor  200 {deleted, focalActorCleared}
///   POST   /{book}/composition/{comp}/element                     addCompositionElement   200 {elementObjectId, element, created}
///   PATCH  /{book}/composition/{comp}/element/{element}           patchCompositionElement 200 {element, updated}
///   DELETE /{book}/composition/{comp}/element/{element}           deleteCompositionElement 200 {deleted}
///
/// FIXTURE. The same hand-built H2 world fixture as TestPictureBookPromoteRoutes (an olio-owned book in a Books-universe
/// world, a Writer role holding the owner with CRUD on the book group and the world tree, an optional Reader role) with
/// its own user names and slug prefix so the classes never share rows. composeMigrated stages the one SCENE composition
/// each case edits; actors here are description-only (a character-backed actor needs a world Population charPerson and
/// is covered by TestPbCompositionRows in Objects7 against the live stack).
///
/// WIRE NOTE (measured, not a defect under test): JSONUtil.exportObject serialises with Include.NON_EMPTY, so a null
/// `nodeStatus` and null text fields are ABSENT from a body rather than present as null; booleans (`nodeStale`,
/// `updated`, `created`, `deleted`) and ints (`revision`) ARE present. There is no render node on this harness, so
/// `nodeStale` is honestly false here; the nodeStale=true path is TestPbCompositionRows in Objects7.
public class TestPictureBookCompositionRoutes extends BaseTest {

	private static final String OWNER_NAME = "pbcomprowowner";
	private static final String READER_NAME = "pbcomprowreader";
	private static final String STRANGER_NAME = "pbcomprowstranger";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String[] DATA_AND_GROUP = new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() };
	private static final String[] CRUD = new String[] { "Read", "Update", "Create", "Delete" };
	private static final String[] READ = new String[] { "Read" };
	private static final String SCENE_SETTING = "The lighthouse keeper counts the ships that never came back";
	private static final String REFUSED_EDIT = "Not authorized to edit the composition in this book";
	private static final String MALFORMED = "Malformed request body";
	private static final String ACTOR_DESC = "a drenched harbour pilot in a yellow oilskin";
	private static final String ELEMENT_DESC = "a brass ship's bell on a rotting post";

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

	// ─────────────────────────────── fixture (TestPictureBookPromoteRoutes' shape) ───────────────────────────────

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
		f.slug = "p48r-" + UUID.randomUUID().toString().substring(0, 12);
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
		assertEquals("the composition belongs to the book", idOf(bookAsOwner(f)), fkId(comp, OlioFieldNames.FIELD_PB_BOOK));
		assertEquals("the composition sits in the book group", ((Number) bookAsOwner(f).get(FieldNames.FIELD_GROUP_ID)).longValue(),
			((Number) comp.get(FieldNames.FIELD_GROUP_ID)).longValue());
		assertTrue("composeMigrated stages no actor rows", PbComposeUtil.listActors(owner, comp).isEmpty());
		assertTrue("composeMigrated stages no element rows", PbComposeUtil.listElements(owner, comp).isEmpty());
		return comp;
	}

	private BaseRecord freshComp(String compOid) {
		BaseRecord c = PbComposeUtil.readComposition(owner, compOid, orgId);
		assertNotNull("composition " + compOid + " reads back as the owner", c);
		return c;
	}

	private int revisionOf(String compOid) {
		Object o = freshComp(compOid).get(OlioFieldNames.FIELD_PB_REVISION);
		return (o instanceof Number ? ((Number) o).intValue() : 0);
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private HttpServletRequestMock requestAs(BaseRecord u) {
		return new HttpServletRequestMock(new UserPrincipal((String) u.get(FieldNames.FIELD_NAME), organizationPath));
	}

	private String oid(BaseRecord rec) {
		return (rec != null && rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private static long fkId(BaseRecord rec, String field) {
		Object o = (rec != null && rec.hasField(field) ? rec.get(field) : null);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
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

	private int num(Map<String, Object> m, String key) {
		Object o = m.get(key);
		assertNotNull("'" + key + "' must be present: " + m, o);
		assertTrue("'" + key + "' must be a number, got " + o.getClass().getSimpleName(), o instanceof Number);
		return ((Number) o).intValue();
	}

	private double dbl(Map<String, Object> m, String key) {
		Object o = m.get(key);
		assertNotNull("'" + key + "' must be present: " + m, o);
		assertTrue("'" + key + "' must be a number, got " + o.getClass().getSimpleName(), o instanceof Number);
		return ((Number) o).doubleValue();
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/// Without a principal every route is 401 before the book is even looked up.
	@Test
	public void TestNoPrincipalIs401() {
		HttpServletRequestMock anon = new HttpServletRequestMock();
		String b = json("mood", "x");
		refused(service.getSceneComposition(NIL_UUID, NIL_UUID, anon), 401, "No authenticated principal", "anonymous GET composition");
		refused(service.patchSceneComposition(NIL_UUID, NIL_UUID, b, anon), 401, "No authenticated principal", "anonymous PATCH composition");
		refused(service.addCompositionActor(NIL_UUID, NIL_UUID, b, anon), 401, "No authenticated principal", "anonymous POST actor");
		refused(service.patchCompositionActor(NIL_UUID, NIL_UUID, NIL_UUID, b, anon), 401, "No authenticated principal", "anonymous PATCH actor");
		refused(service.deleteCompositionActor(NIL_UUID, NIL_UUID, NIL_UUID, anon), 401, "No authenticated principal", "anonymous DELETE actor");
		refused(service.addCompositionElement(NIL_UUID, NIL_UUID, b, anon), 401, "No authenticated principal", "anonymous POST element");
		refused(service.patchCompositionElement(NIL_UUID, NIL_UUID, NIL_UUID, b, anon), 401, "No authenticated principal", "anonymous PATCH element");
		refused(service.deleteCompositionElement(NIL_UUID, NIL_UUID, NIL_UUID, anon), 401, "No authenticated principal", "anonymous DELETE element");
	}

	/// A user with no grant on the book cannot tell it exists: 404 "Book not found" on every route, the real scene,
	/// composition and row ids never being consulted.
	@Test
	public void TestStrangerIs404() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			BaseRecord scene = addScene(f, 1);
			BaseRecord comp = compose(f, scene);
			String sOid = oid(scene);
			String cOid = oid(comp);
			HttpServletRequestMock s = requestAs(stranger);
			String b = json("mood", "x");
			int rev0 = revisionOf(cOid);
			assertNull("precondition: the stranger cannot read the book", PbBookUtil.readBook(stranger, f.bookOid, orgId));
			refused(service.getSceneComposition(f.bookOid, sOid, s), 404, "Book not found", "stranger GET composition");
			refused(service.patchSceneComposition(f.bookOid, sOid, b, s), 404, "Book not found", "stranger PATCH composition");
			refused(service.addCompositionActor(f.bookOid, cOid, b, s), 404, "Book not found", "stranger POST actor");
			refused(service.patchCompositionActor(f.bookOid, cOid, NIL_UUID, b, s), 404, "Book not found", "stranger PATCH actor");
			refused(service.deleteCompositionActor(f.bookOid, cOid, NIL_UUID, s), 404, "Book not found", "stranger DELETE actor");
			refused(service.addCompositionElement(f.bookOid, cOid, b, s), 404, "Book not found", "stranger POST element");
			refused(service.patchCompositionElement(f.bookOid, cOid, NIL_UUID, b, s), 404, "Book not found", "stranger PATCH element");
			refused(service.deleteCompositionElement(f.bookOid, cOid, NIL_UUID, s), 404, "Book not found", "stranger DELETE element");
			BaseRecord after = freshComp(cOid);
			assertEquals("nothing was written: revision unchanged", rev0, ((Number) after.get(OlioFieldNames.FIELD_PB_REVISION)).intValue());
			assertTrue("no actor row was written", PbComposeUtil.listActors(owner, after).isEmpty());
			assertTrue("no element row was written", PbComposeUtil.listElements(owner, after).isEmpty());
		}
		finally {
			cleanup(f);
		}
	}

	/// A Reader can see the composition (GET 200) but not edit it: 403 from the facade's canUpdate gate on every write,
	/// before any row id is read, and nothing is written - the revision is unchanged and no row appears.
	@Test
	public void TestReaderIs403OnEveryWrite() throws Exception {
		Fixture f = createWorldBook(true);
		try {
			v3();
			BaseRecord scene = addScene(f, 1);
			BaseRecord comp = compose(f, scene);
			String sOid = oid(scene);
			String cOid = oid(comp);
			int rev0 = revisionOf(cOid);
			assertNotNull("precondition: the reader can READ the book (else this would be a 404, not the 403 under test)",
				PbBookUtil.readBook(reader, f.bookOid, orgId));
			HttpServletRequestMock r = requestAs(reader);

			Map<String, Object> got = ok(service.getSceneComposition(f.bookOid, sOid, r), "reader GET composition");
			assertEquals(cOid, got.get("compositionObjectId"));
			assertEquals(sOid, got.get("sceneObjectId"));
			assertEquals("MIGRATED", map(got, "composition").get("source"));
			assertTrue("no actors", list(got, "actors").isEmpty());
			assertTrue("no elements", list(got, "elements").isEmpty());

			refused(service.patchSceneComposition(f.bookOid, sOid, json("mood", "bleak"), r), 403, REFUSED_EDIT, "reader PATCH composition");
			refused(service.addCompositionActor(f.bookOid, cOid, json("description", ACTOR_DESC), r), 403, REFUSED_EDIT, "reader POST actor");
			refused(service.patchCompositionActor(f.bookOid, cOid, NIL_UUID, json("pose", "x"), r), 403, REFUSED_EDIT, "reader PATCH actor");
			refused(service.deleteCompositionActor(f.bookOid, cOid, NIL_UUID, r), 403, REFUSED_EDIT, "reader DELETE actor");
			refused(service.addCompositionElement(f.bookOid, cOid, json("elementType", "prop", "description", ELEMENT_DESC), r), 403, REFUSED_EDIT, "reader POST element");
			refused(service.patchCompositionElement(f.bookOid, cOid, NIL_UUID, json("description", "x"), r), 403, REFUSED_EDIT, "reader PATCH element");
			refused(service.deleteCompositionElement(f.bookOid, cOid, NIL_UUID, r), 403, REFUSED_EDIT, "reader DELETE element");

			BaseRecord after = freshComp(cOid);
			assertEquals("the revision is untouched", rev0, ((Number) after.get(OlioFieldNames.FIELD_PB_REVISION)).intValue());
			assertNotEquals("mood was never written", "bleak", after.get(OlioFieldNames.FIELD_PB_MOOD));
			assertTrue("no actor row was written", PbComposeUtil.listActors(owner, after).isEmpty());
			assertTrue("no element row was written", PbComposeUtil.listElements(owner, after).isEmpty());
		}
		finally {
			cleanup(f);
		}
	}

	/// The owner's happy path across all eight routes: every write returns 200 with a strictly increasing revision,
	/// nodeStale=false (no node on this harness), and the rows read back from the DB and from the GET.
	@Test
	public void TestOwnerCrudRoundTrip() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			BaseRecord scene = addScene(f, 1);
			BaseRecord comp = compose(f, scene);
			String sOid = oid(scene);
			String cOid = oid(comp);
			HttpServletRequestMock o = requestAs(owner);
			int rev = revisionOf(cOid);

			Map<String, Object> got = ok(service.getSceneComposition(f.bookOid, sOid, o), "GET composition (empty)");
			assertEquals(f.bookOid, got.get("bookObjectId"));
			assertEquals(cOid, got.get("compositionObjectId"));
			assertEquals(rev, num(map(got, "composition"), "revision"));
			assertTrue(list(got, "actors").isEmpty());
			assertTrue(list(got, "elements").isEmpty());

			Map<String, Object> d = ok(service.patchSceneComposition(f.bookOid, sOid,
				json("mood", "bleak", "shotType", "wide", "viewpoint", "low", "cameraText", "35mm, rain on the lens", "promptLocked", Boolean.TRUE), o), "PATCH composition");
			assertEquals(Boolean.TRUE, d.get("updated"));
			assertEquals(cOid, d.get("compositionObjectId"));
			assertEquals(sOid, d.get("sceneObjectId"));
			assertEquals("revision bumps by one", rev + 1, num(d, "revision"));
			assertEquals("no node on this harness", Boolean.FALSE, d.get("nodeStale"));
			rev = num(d, "revision");
			BaseRecord c1 = freshComp(cOid);
			assertEquals("bleak", c1.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("WIDE", String.valueOf((Object) c1.get(OlioFieldNames.FIELD_PB_SHOT_TYPE)));
			assertEquals("LOW", String.valueOf((Object) c1.get(OlioFieldNames.FIELD_PB_VIEWPOINT)));
			assertEquals(Boolean.TRUE, c1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(Boolean.TRUE, c1.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals("USER", String.valueOf((Object) c1.get(OlioFieldNames.FIELD_PB_SOURCE)));

			d = ok(service.addCompositionActor(f.bookOid, cOid, json("description", ACTOR_DESC, "role", "supporting", "x", 0.2, "y", 0.3, "w", 0.4, "h", 0.6), o), "POST actor");
			assertEquals(Boolean.TRUE, d.get("created"));
			assertEquals("revision bumps by one", rev + 1, num(d, "revision"));
			rev = num(d, "revision");
			String aOid = String.valueOf(d.get("actorObjectId"));
			Map<String, Object> actor = map(d, "actor");
			assertEquals(aOid, actor.get("objectId"));
			assertEquals(ACTOR_DESC, actor.get("description"));
			assertEquals("SUPPORTING", actor.get("role"));
			assertEquals(1, num(actor, "count"));
			assertEquals("AUTO", actor.get("referencePolicy"));
			assertEquals(0.2, dbl(actor, "x"), 1e-9);
			assertEquals(0.6, dbl(actor, "h"), 1e-9);
			assertEquals("the handle is actor.<comp>.<n>", PbComposeUtil.ACTOR_NAME_PREFIX + cOid + ".1", actor.get("name"));
			assertNull("no character on a description-only actor", actor.get("characterObjectId"));
			BaseRecord aRow = PbComposeUtil.readActor(owner, aOid, orgId);
			assertNotNull("the actor row reads back as the owner", aRow);
			assertEquals(idOf(comp), fkId(aRow, OlioFieldNames.FIELD_PB_COMPOSITION));

			d = ok(service.patchCompositionActor(f.bookOid, cOid, aOid, json("pose", "braced against the wind", "expression", "squinting", "count", 2, "x", 0.5), o), "PATCH actor");
			assertEquals(Boolean.TRUE, d.get("updated"));
			assertEquals("revision bumps by one", rev + 1, num(d, "revision"));
			rev = num(d, "revision");
			actor = map(d, "actor");
			assertEquals("braced against the wind", actor.get("pose"));
			assertEquals("squinting", actor.get("expression"));
			assertEquals(2, num(actor, "count"));
			assertEquals(0.5, dbl(actor, "x"), 1e-9);
			assertEquals("untouched fields survive", ACTOR_DESC, actor.get("description"));
			aRow = PbComposeUtil.readActor(owner, aOid, orgId);
			assertEquals("braced against the wind", aRow.get(OlioFieldNames.FIELD_PB_POSE));
			assertEquals(2, ((Number) aRow.get(FieldNames.FIELD_COUNT)).intValue());

			got = ok(service.getSceneComposition(f.bookOid, sOid, o), "GET composition (one actor)");
			assertEquals(1, list(got, "actors").size());
			assertEquals(rev, num(map(got, "composition"), "revision"));
			assertEquals("WIDE", map(got, "composition").get("shotType"));

			d = ok(service.deleteCompositionActor(f.bookOid, cOid, aOid, o), "DELETE actor");
			assertEquals(Boolean.TRUE, d.get("deleted"));
			assertEquals(Boolean.FALSE, d.get("focalActorCleared"));
			assertEquals("revision bumps by one", rev + 1, num(d, "revision"));
			rev = num(d, "revision");
			assertNull("the actor row is gone", PbComposeUtil.readActor(owner, aOid, orgId));
			refused(service.patchCompositionActor(f.bookOid, cOid, aOid, json("pose", "x"), o), 404, "Actor " + aOid + " not found in this composition", "PATCH deleted actor");

			d = ok(service.addCompositionElement(f.bookOid, cOid, json("elementType", "prop", "description", ELEMENT_DESC, "importance", "high"), o), "POST element");
			assertEquals(Boolean.TRUE, d.get("created"));
			assertEquals("revision bumps by one", rev + 1, num(d, "revision"));
			rev = num(d, "revision");
			String eOid = String.valueOf(d.get("elementObjectId"));
			Map<String, Object> el = map(d, "element");
			assertEquals(eOid, el.get("objectId"));
			assertEquals("PROP", el.get("elementType"));
			assertEquals("HIGH", el.get("importance"));
			assertEquals(ELEMENT_DESC, el.get("description"));
			assertNull("no item on a fresh element", el.get("itemObjectId"));
			BaseRecord eRow = PbComposeUtil.readElement(owner, eOid, orgId);
			assertNotNull("the element row reads back as the owner", eRow);
			assertEquals(idOf(comp), fkId(eRow, OlioFieldNames.FIELD_PB_COMPOSITION));

			d = ok(service.patchCompositionElement(f.bookOid, cOid, eOid, json("elementType", "furniture", "depth", 3, "w", 0.25), o), "PATCH element");
			assertEquals(Boolean.TRUE, d.get("updated"));
			assertEquals("revision bumps by one", rev + 1, num(d, "revision"));
			rev = num(d, "revision");
			el = map(d, "element");
			assertEquals("FURNITURE", el.get("elementType"));
			assertEquals(3, num(el, "depth"));
			assertEquals(0.25, dbl(el, "w"), 1e-9);
			assertEquals("untouched fields survive", ELEMENT_DESC, el.get("description"));
			eRow = PbComposeUtil.readElement(owner, eOid, orgId);
			assertEquals("FURNITURE", String.valueOf((Object) eRow.get(OlioFieldNames.FIELD_PB_ELEMENT_TYPE)));
			assertEquals(3, ((Number) eRow.get(OlioFieldNames.FIELD_PB_DEPTH)).intValue());

			got = ok(service.getSceneComposition(f.bookOid, sOid, o), "GET composition (one element)");
			assertTrue(list(got, "actors").isEmpty());
			assertEquals(1, list(got, "elements").size());

			d = ok(service.deleteCompositionElement(f.bookOid, cOid, eOid, o), "DELETE element");
			assertEquals(Boolean.TRUE, d.get("deleted"));
			assertEquals("revision bumps by one", rev + 1, num(d, "revision"));
			rev = num(d, "revision");
			assertNull("the element row is gone", PbComposeUtil.readElement(owner, eOid, orgId));
			refused(service.deleteCompositionElement(f.bookOid, cOid, eOid, o), 404, "Element " + eOid + " not found in this composition", "DELETE deleted element");

			got = ok(service.getSceneComposition(f.bookOid, sOid, o), "GET composition (final)");
			assertTrue(list(got, "actors").isEmpty());
			assertTrue(list(got, "elements").isEmpty());
			Map<String, Object> cd = map(got, "composition");
			assertEquals("the final revision is what the last write reported", rev, num(cd, "revision"));
			assertEquals("bleak", cd.get("mood"));
			assertEquals("USER", cd.get("source"));
			assertEquals(Boolean.TRUE, cd.get("userEdited"));
			assertEquals(Boolean.TRUE, cd.get("promptLocked"));
			assertEquals("eight writes, eight bumps", revisionOf(cOid), rev);
		}
		finally {
			cleanup(f);
		}
	}

	/// Unknown scene, composition, actor and element ids are 404 with the id in the message; the book being readable
	/// and editable by the owner, the ids themselves are what fail.
	@Test
	public void TestUnknownIdsAre404() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			BaseRecord scene = addScene(f, 1);
			BaseRecord comp = compose(f, scene);
			String sOid = oid(scene);
			String cOid = oid(comp);
			HttpServletRequestMock o = requestAs(owner);
			int rev0 = revisionOf(cOid);

			refused(service.getSceneComposition(f.bookOid, NIL_UUID, o), 404, "Scene " + NIL_UUID + " not found in this book", "GET unknown scene");
			refused(service.patchSceneComposition(f.bookOid, NIL_UUID, json("mood", "x"), o), 404, "Scene " + NIL_UUID + " not found in this book", "PATCH unknown scene");
			refused(service.addCompositionActor(f.bookOid, NIL_UUID, json("description", ACTOR_DESC), o), 404, "Composition " + NIL_UUID + " not found in this book", "POST actor on unknown composition");
			refused(service.addCompositionElement(f.bookOid, NIL_UUID, json("elementType", "prop", "description", ELEMENT_DESC), o), 404, "Composition " + NIL_UUID + " not found in this book", "POST element on unknown composition");
			refused(service.patchCompositionActor(f.bookOid, cOid, NIL_UUID, json("pose", "x"), o), 404, "Actor " + NIL_UUID + " not found in this composition", "PATCH unknown actor");
			refused(service.deleteCompositionActor(f.bookOid, cOid, NIL_UUID, o), 404, "Actor " + NIL_UUID + " not found in this composition", "DELETE unknown actor");
			refused(service.patchCompositionElement(f.bookOid, cOid, NIL_UUID, json("description", "x"), o), 404, "Element " + NIL_UUID + " not found in this composition", "PATCH unknown element");
			refused(service.deleteCompositionElement(f.bookOid, cOid, NIL_UUID, o), 404, "Element " + NIL_UUID + " not found in this composition", "DELETE unknown element");
			refused(service.getSceneComposition(NIL_UUID, sOid, o), 404, "Book not found", "GET unknown book");

			// A scene with no composition yet is a 404 that says so.
			BaseRecord bare = addScene(f, 2);
			refused(service.getSceneComposition(f.bookOid, oid(bare), o), 404, "Scene " + oid(bare) + " has no composition; compose it first", "GET uncomposed scene");
			refused(service.patchSceneComposition(f.bookOid, oid(bare), json("mood", "x"), o), 404, "Scene " + oid(bare) + " has no composition; compose it first", "PATCH uncomposed scene");

			assertEquals("none of the misses wrote anything", rev0, revisionOf(cOid));
		}
		finally {
			cleanup(f);
		}
	}

	/// Malformed JSON is a 400 from the service before the facade; unknown and refused fields are 400 from the row
	/// API with the field named; none of them writes.
	@Test
	public void TestBadBodyIs400() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			BaseRecord scene = addScene(f, 1);
			BaseRecord comp = compose(f, scene);
			String sOid = oid(scene);
			String cOid = oid(comp);
			HttpServletRequestMock o = requestAs(owner);
			Map<String, Object> d = ok(service.addCompositionActor(f.bookOid, cOid, json("description", ACTOR_DESC), o), "seed actor");
			String aOid = String.valueOf(d.get("actorObjectId"));
			d = ok(service.addCompositionElement(f.bookOid, cOid, json("elementType", "prop", "description", ELEMENT_DESC), o), "seed element");
			String eOid = String.valueOf(d.get("elementObjectId"));
			int rev0 = revisionOf(cOid);
			double x0 = ((Number) PbComposeUtil.readActor(owner, aOid, orgId).get(OlioFieldNames.FIELD_PB_X)).doubleValue();
			String bad = "{not json";

			refused(service.patchSceneComposition(f.bookOid, sOid, bad, o), 400, MALFORMED, "malformed PATCH composition");
			refused(service.addCompositionActor(f.bookOid, cOid, bad, o), 400, MALFORMED, "malformed POST actor");
			refused(service.patchCompositionActor(f.bookOid, cOid, aOid, bad, o), 400, MALFORMED, "malformed PATCH actor");
			refused(service.addCompositionElement(f.bookOid, cOid, bad, o), 400, MALFORMED, "malformed POST element");
			refused(service.patchCompositionElement(f.bookOid, cOid, eOid, bad, o), 400, MALFORMED, "malformed PATCH element");

			refused(service.patchSceneComposition(f.bookOid, sOid, "{}", o), 400, "No fields to update", "empty PATCH composition");
			refused(service.patchSceneComposition(f.bookOid, sOid, json("colourway", "x"), o), 400, "Unknown field colourway", "unknown composition field");
			refused(service.patchSceneComposition(f.bookOid, sOid, json("revision", 9), o), 400, "revision cannot be set here; revision is bumped by every edit", "refused revision");
			refused(service.patchSceneComposition(f.bookOid, sOid, json("name", "x"), o), 400, "name cannot be set here; a composition's name is its handle", "refused composition name");
			refused(service.patchSceneComposition(f.bookOid, sOid, json("location", "x"), o), 400, "location cannot be set here; point the scene at a location with PUT /scene/{id}/location", "refused location");
			refused(service.patchSceneComposition(f.bookOid, sOid, json("shotType", "sideways"), o), 400, "shotType must be one of", "bad shotType");
			refused(service.patchSceneComposition(f.bookOid, sOid, json("promptLocked", "yes"), o), 400, "promptLocked must be a boolean", "bad promptLocked");
			refused(service.patchSceneComposition(f.bookOid, sOid, json("configOverride", "{\"schema\":\"data.group\",\"steps\":20}"), o), 400,
				"configOverride must be sparse olio.sd.config JSON carrying its schema", "non-sparse configOverride");
			refused(service.patchSceneComposition(f.bookOid, sOid, json("focalActorObjectId", NIL_UUID), o), 400, "focalActor must be an actor of this composition", "focal actor not of this composition");

			refused(service.addCompositionActor(f.bookOid, cOid, "{}", o), 400, "An actor without a character needs a description", "actor with neither");
			refused(service.addCompositionActor(f.bookOid, cOid, json("hat", "x"), o), 400, "Unknown field hat", "unknown actor field");
			refused(service.addCompositionActor(f.bookOid, cOid, json("description", 7), o), 400, "description must be a string", "non-string description");
			refused(service.patchCompositionActor(f.bookOid, cOid, aOid, json("characterObjectId", NIL_UUID), o), 400,
				"characterObjectId cannot be set here; an actor's character is fixed; add a new actor instead", "refused characterObjectId");
			refused(service.patchCompositionActor(f.bookOid, cOid, aOid, json("apparelObjectId", NIL_UUID), o), 400,
				"apparelObjectId cannot be set here; pin an outfit with PUT .../actor/{id}/apparel", "refused apparelObjectId");
			refused(service.patchCompositionActor(f.bookOid, cOid, aOid, json("name", "x"), o), 400, "name cannot be set here; an actor's name is its handle", "refused actor name");
			refused(service.patchCompositionActor(f.bookOid, cOid, aOid, json("x", 1.5), o), 400, "x must be between 0 and 1", "actor x out of range");
			refused(service.patchCompositionActor(f.bookOid, cOid, aOid, json("x", "far"), o), 400, "x must be a number", "actor x not a number");

			refused(service.addCompositionElement(f.bookOid, cOid, json("description", ELEMENT_DESC), o), 400, "elementType is required", "element without type");
			refused(service.addCompositionElement(f.bookOid, cOid, json("elementType", "prop"), o), 400, "An element needs a description", "element without description");
			refused(service.addCompositionElement(f.bookOid, cOid, json("elementType", "lamp", "description", "x"), o), 400, "elementType must be one of", "bad elementType");
			refused(service.patchCompositionElement(f.bookOid, cOid, eOid, json("itemObjectId", NIL_UUID), o), 400,
				"itemObjectId cannot be set here; link a world item with POST .../element/{id}/promote", "refused itemObjectId");
			refused(service.patchCompositionElement(f.bookOid, cOid, eOid, json("depth", "deep"), o), 400, "depth must be a number", "bad element depth");

			BaseRecord after = freshComp(cOid);
			assertEquals("no refused call bumped the revision", rev0, ((Number) after.get(OlioFieldNames.FIELD_PB_REVISION)).intValue());
			assertEquals("still one actor", 1, PbComposeUtil.listActors(owner, after).size());
			assertEquals("still one element", 1, PbComposeUtil.listElements(owner, after).size());
			BaseRecord aRow = PbComposeUtil.readActor(owner, aOid, orgId);
			assertNotNull("the seeded actor still reads back", aRow);
			double x1 = ((Number) aRow.get(OlioFieldNames.FIELD_PB_X)).doubleValue();
			assertEquals("the actor's x is untouched by the refused patches", x0, x1, 1e-9);
			assertFalse("the actor's x is a unit value", x1 > 1.0);
			assertNull("pose was never written by a refused call", aRow.get(OlioFieldNames.FIELD_PB_POSE));
		}
		finally {
			cleanup(f);
		}
	}

	/// A scene or composition of ANOTHER book of the same owner is 404 under this book: the ids are real and readable
	/// by the owner, the cross-book check is what refuses them, and nothing in either book is written.
	@Test
	public void TestForeignGroupSceneIs404() throws Exception {
		Fixture a = createWorldBook(false);
		Fixture b = createWorldBook(false);
		try {
			v3();
			BaseRecord sceneA = addScene(a, 1);
			BaseRecord compA = compose(a, sceneA);
			BaseRecord sceneB = addScene(b, 1);
			BaseRecord compB = compose(b, sceneB);
			String sB = oid(sceneB);
			String cB = oid(compB);
			HttpServletRequestMock o = requestAs(owner);
			int revA = revisionOf(oid(compA));
			int revB = revisionOf(cB);
			assertNotNull("precondition: the owner reads book B's scene", PbBookUtil.readScene(owner, sB, orgId));
			assertNotNull("precondition: the owner reads book B's composition", PbComposeUtil.readComposition(owner, cB, orgId));

			refused(service.getSceneComposition(a.bookOid, sB, o), 404, "Scene " + sB + " not found in this book", "GET foreign scene");
			refused(service.patchSceneComposition(a.bookOid, sB, json("mood", "x"), o), 404, "Scene " + sB + " not found in this book", "PATCH foreign scene");
			refused(service.addCompositionActor(a.bookOid, cB, json("description", ACTOR_DESC), o), 404, "Composition " + cB + " not found in this book", "POST actor on foreign composition");
			refused(service.patchCompositionActor(a.bookOid, cB, NIL_UUID, json("pose", "x"), o), 404, "Composition " + cB + " not found in this book", "PATCH actor on foreign composition");
			refused(service.deleteCompositionActor(a.bookOid, cB, NIL_UUID, o), 404, "Composition " + cB + " not found in this book", "DELETE actor on foreign composition");
			refused(service.addCompositionElement(a.bookOid, cB, json("elementType", "prop", "description", ELEMENT_DESC), o), 404, "Composition " + cB + " not found in this book", "POST element on foreign composition");
			refused(service.patchCompositionElement(a.bookOid, cB, NIL_UUID, json("description", "x"), o), 404, "Composition " + cB + " not found in this book", "PATCH element on foreign composition");
			refused(service.deleteCompositionElement(a.bookOid, cB, NIL_UUID, o), 404, "Composition " + cB + " not found in this book", "DELETE element on foreign composition");

			// The same ids under their own book work, proving the ids were real and the refusal was the book check.
			ok(service.getSceneComposition(b.bookOid, sB, o), "GET own scene");

			assertEquals("book A's composition is untouched", revA, revisionOf(oid(compA)));
			assertEquals("book B's composition is untouched", revB, revisionOf(cB));
			assertTrue("no actor landed on B", PbComposeUtil.listActors(owner, freshComp(cB)).isEmpty());
			assertTrue("no element landed on B", PbComposeUtil.listElements(owner, freshComp(cB)).isEmpty());
		}
		finally {
			cleanup(a);
			cleanup(b);
		}
	}
}
