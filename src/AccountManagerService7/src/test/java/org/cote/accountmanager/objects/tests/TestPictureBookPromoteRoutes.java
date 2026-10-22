package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import org.cote.accountmanager.olio.picturebook.BookContext;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbElementTypeEnumType;
import org.cote.accountmanager.schema.type.PbImportanceEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// P4-6 (PictureBook V3 §7.2 rule 4; Implementation §12.4): the promote route on PictureBookService exercised through
/// the real service method -> PbServiceFacade.promoteElement -> PbComposeUtil.promoteElement -> real AccessPoint/PBAC ->
/// the real (H2) database, with HttpServletRequestMock + UserPrincipal exactly as TestPictureBookComposeRoutes does.
/// Nothing is mocked; no asserting actor is ever the admin or the olio principal.
///
///   POST /{book}/composition/{comp}/element/{el}/promote   promoteElement   200 {promoted, created, reused, itemObjectId, ...}
///
/// FIXTURE. The same hand-built H2 world fixture as TestPictureBookComposeRoutes (a bare book is not enough: the item
/// lives in the world's Items group and the location's store in its Stores group) with its own user names, slug prefix
/// and group so the classes never share rows. The location is created through PbLocationUtil.getCreateLocation as the
/// owner: the fixture world sits under /Olio/Universes/Books/Worlds, so §7.2 rule 1 gives it a map-free POI with a
/// store (asserted as a precondition, not assumed). composeMigrated stages no element rows, so each case hand-builds
/// the one unlinked olio.pb.sceneElement the board would promote, as the owner, in the composition's group.
///
/// WIRE NOTE (measured, not a defect under test): JSONUtil.exportObject serialises with Include.NON_EMPTY, so a null
/// `itemObjectId`/`itemName` and an empty `warnings` are ABSENT from a body rather than present as null/[]; booleans
/// (`promoted`, `created`, `reused`) and ints (`staled`) ARE present. There is no render node on this harness, so
/// `staled` is honestly 0 here; the staled=1 path is TestPbPromoteElement in Objects7 against the live stack.
public class TestPictureBookPromoteRoutes extends BaseTest {

	private static final String OWNER_NAME = "pbpromoteowner";
	private static final String READER_NAME = "pbpromotereader";
	private static final String STRANGER_NAME = "pbpromotestranger";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String[] DATA_AND_GROUP = new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() };
	private static final String[] CRUD = new String[] { "Read", "Update", "Create", "Delete" };
	private static final String[] READ = new String[] { "Read" };
	private static final String SCENE_SETTING = "Rain hammers the shutters while the fiddler tunes up";
	private static final String CUP_DESC = "a dented pewter cup";

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
		BookContext bctx;
		BaseRecord location;
		BaseRecord store;
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

	// ─────────────────────────────── fixture (TestPictureBookComposeRoutes' shape) ───────────────────────────────

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
		f.slug = "p46r-" + UUID.randomUUID().toString().substring(0, 12);
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

	/// The world's groups as the owner opens the book context (the resolution promoteElement makes for the Items group).
	private BookContext openContext(Fixture f) {
		if(f.bctx == null) {
			f.bctx = PbBookUtil.openBookContext(owner, bookAsOwner(f));
			assertNotNull("book context", f.bctx);
			assertNotNull("book world", f.bctx.getWorld());
			assertNotNull("world Items group", f.bctx.getGroup(OlioFieldNames.FIELD_ITEMS));
		}
		return f.bctx;
	}

	/// A location of the book's world created AS THE OWNER through the real library path (TestPictureBookLocationRoutes
	/// creates the same thing through POST /locations); its map-free POI and store are the precondition every happy
	/// path here depends on, so both are asserted rather than assumed.
	private BaseRecord addLocation(Fixture f) throws Exception {
		if(f.location != null) {
			return f.location;
		}
		f.location = PbLocationUtil.getCreateLocation(owner, bookAsOwner(f), "The Drowned Anchor", "A low-ceilinged harbour tavern",
			PbLocationKindEnumType.INTERIOR, List.of("the Anchor"));
		assertNotNull("location in the book's world", f.location);
		BaseRecord fresh = PbLocationUtil.readLocation(owner, oid(f.location), orgId);
		assertNotNull("the location reads back as the owner", fresh);
		BaseRecord poiRef = fresh.get(OlioFieldNames.FIELD_PB_POI);
		assertNotNull("§7.2 rule 1: a location in a book world carries a map-free POI", poiRef);
		Query pq = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_ID, poiRef.get(FieldNames.FIELD_ID));
		pq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_STORE });
		pq.setCache(false);
		BaseRecord poi = IOSystem.getActiveContext().getAccessPoint().find(owner, pq);
		assertNotNull("the POI is readable by the owner", poi);
		f.store = poi.get(FieldNames.FIELD_STORE);
		assertNotNull("the POI carries a store", f.store);
		assertTrue("the store has an id", idOf(f.store) > 0L);
		return f.location;
	}

	/// A page of the world book created as the owner (Writer) with its setting patched in, optionally pointed at a
	/// location (the pointer promoteElement follows to the store).
	private BaseRecord addScene(Fixture f, int index, BaseRecord location) throws Exception {
		BaseRecord book = bookAsOwner(f);
		BaseRecord created = PbBookUtil.createScene(owner, book, index, "Page " + index, PbBookUtil.bookGroupPath(f.slug));
		assertNotNull("Failed to create page " + index, created);
		String sceneOid = oid(created);
		assertNotNull("Page carries no objectId", sceneOid);
		BaseRecord scene = PbBookUtil.readScene(owner, sceneOid, orgId);
		assertNotNull("Page must read back as its owner", scene);
		String[] fields = (location != null
			? new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SETTING, FieldNames.FIELD_LOCATION }
			: new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_SETTING });
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE, fields);
		patch.set(FieldNames.FIELD_ID, scene.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, sceneOid);
		patch.set(FieldNames.FIELD_NAME, scene.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_PB_SETTING, SCENE_SETTING);
		if(location != null) {
			patch.set(FieldNames.FIELD_LOCATION, location);
		}
		assertNotNull("scene patch must persist", IOSystem.getActiveContext().getAccessPoint().update(owner, patch));
		scene = PbBookUtil.readScene(owner, sceneOid, orgId);
		assertNotNull("Patched page must read back", scene);
		assertEquals("the setting must be stored", SCENE_SETTING, scene.get(OlioFieldNames.FIELD_PB_SETTING));
		assertEquals("the location pointer must be stored", (location != null ? idOf(location) : 0L), fkId(scene, FieldNames.FIELD_LOCATION));
		return scene;
	}

	/// A MIGRATED composition of the scene (no LLM), written as the owner and re-read uncached so it carries the
	/// scene's location pointer the way PbComposeUtil.persist copies it.
	private BaseRecord compose(Fixture f, BaseRecord scene) {
		long t0 = System.currentTimeMillis();
		ComposeResult r = PbComposeUtil.composeMigrated(owner, bookAsOwner(f), scene, false);
		logger.info("composeMigrated scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX) + ": " + (System.currentTimeMillis() - t0)
			+ " ms written=" + r.isWritten() + " reason=" + r.failureReason);
		assertTrue("the MIGRATED composition is written: " + r.failureReason, r.isWritten());
		assertNotNull("composition", r.composition);
		BaseRecord comp = PbComposeUtil.readComposition(owner, oid(r.composition), orgId);
		assertNotNull("composition re-read as the owner", comp);
		assertEquals("the composition carries the scene's location pointer", fkId(scene, FieldNames.FIELD_LOCATION), fkId(comp, FieldNames.FIELD_LOCATION));
		assertTrue("composeMigrated stages no element rows", PbComposeUtil.listElements(owner, comp).isEmpty());
		return comp;
	}

	/// The one unlinked olio.pb.sceneElement the board would promote, created AS THE OWNER in the composition's group
	/// exactly as PbComposeUtil.createElement builds one.
	private String addElement(BaseRecord comp, String description) throws Exception {
		Long gid = comp.get(FieldNames.FIELD_GROUP_ID);
		assertNotNull("composition groupId", gid);
		BaseRecord row = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE_ELEMENT);
		ioContext.getRecordUtil().applyOwnership(owner, row, orgId);
		row.set(FieldNames.FIELD_GROUP_ID, gid);
		row.set(FieldNames.FIELD_NAME, PbComposeUtil.ELEMENT_NAME_PREFIX + oid(comp) + "." + UUID.randomUUID().toString().substring(0, 8));
		row.set(OlioFieldNames.FIELD_PB_COMPOSITION, comp);
		row.set(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, PbElementTypeEnumType.PROP.toString());
		row.set(FieldNames.FIELD_DESCRIPTION, description);
		row.set(OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.MEDIUM.toString());
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, row);
		assertNotNull("create element '" + description + "' as the owner", created);
		String elOid = oid(created);
		assertNotNull("element carries no objectId", elOid);
		BaseRecord fresh = element(elOid);
		assertEquals("the element belongs to the composition", idOf(comp), fkId(fresh, OlioFieldNames.FIELD_PB_COMPOSITION));
		assertEquals("precondition: the element is not linked", 0L, fkId(fresh, OlioFieldNames.FIELD_ITEM));
		return elOid;
	}

	private BaseRecord element(String elOid) {
		BaseRecord el = PbComposeUtil.readElement(owner, elOid, orgId);
		assertNotNull("element " + elOid + " reads back as the owner", el);
		return el;
	}

	/// Rows of the world Items group, counted as the owner with an explicit groupId + organizationId (never unconstrained).
	private int countItems(Fixture f) {
		BaseRecord itemsGroup = openContext(f).getGroup(OlioFieldNames.FIELD_ITEMS);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_ITEM, FieldNames.FIELD_GROUP_ID, idOf(itemsGroup));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().count(owner, q);
	}

	/// The item by objectId with the fields the assertions need (ownerId, type, groupId), read as the owner uncached.
	private BaseRecord item(String itemOid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_ITEM, FieldNames.FIELD_OBJECT_ID, itemOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_TYPE, FieldNames.FIELD_DESCRIPTION });
		q.setCache(false);
		BaseRecord it = IOSystem.getActiveContext().getAccessPoint().find(owner, q);
		assertNotNull("item " + itemOid + " reads back as the owner", it);
		return it;
	}

	/// Whether the store holds the item (the participation read PbComposeUtil.storeItems makes).
	private boolean storeHolds(Fixture f, long itemId) throws Exception {
		List<BaseRecord> parts = ioContext.getMemberUtil().findMembers(f.store, OlioFieldNames.FIELD_ITEMS, OlioModelNames.MODEL_ITEM, 0L);
		if(parts == null) {
			return false;
		}
		for(BaseRecord p : parts) {
			Object pid = p.get(FieldNames.FIELD_PARTICIPANT_ID);
			if(pid instanceof Number && ((Number) pid).longValue() == itemId) {
				return true;
			}
		}
		return false;
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

	private int num(Map<String, Object> m, String key) {
		Object o = m.get(key);
		assertNotNull("'" + key + "' must be present: " + m, o);
		assertTrue("'" + key + "' must be a number, got " + o.getClass().getSimpleName(), o instanceof Number);
		return ((Number) o).intValue();
	}

	@FunctionalInterface
	private interface Call {
		void run() throws Exception;
	}

	/// A direct facade call that must raise PictureBookException(status) carrying the text.
	private void facadeRefused(String what, int status, String contains, Call c) throws Exception {
		try {
			c.run();
		}
		catch(PictureBookException e) {
			assertEquals(what + ": status (" + e.getMessage() + ")", status, e.getStatus());
			assertNotNull(what + ": message", e.getMessage());
			assertTrue(what + ": message must contain '" + contains + "' but was: " + e.getMessage(), e.getMessage().contains(contains));
			return;
		}
		fail(what + ": expected PictureBookException " + status + " but nothing was thrown");
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/// Without a principal the route is 401 before the book is even looked up.
	@Test
	public void TestNoPrincipalIs401() {
		HttpServletRequestMock anon = new HttpServletRequestMock();
		refused(service.promoteElement(NIL_UUID, NIL_UUID, NIL_UUID, anon), 401, "No authenticated principal", "anonymous promote");
	}

	/// A user with no grant on the book cannot tell it exists: 404 "Book not found", the composition and element ids
	/// never being consulted.
	@Test
	public void TestStrangerIs404() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			refused(service.promoteElement(f.bookOid, NIL_UUID, NIL_UUID, requestAs(stranger)), 404, "Book not found", "stranger promote");
		}
		finally {
			cleanup(f);
		}
	}

	/// A Reader can see the book but not update it: 403 from the facade's canUpdate gate before any id is read, and
	/// nothing is written - the element stays unlinked, the Items group and the store are untouched.
	@Test
	public void TestReaderIs403AndWritesNothing() throws Exception {
		Fixture f = createWorldBook(true);
		try {
			v3();
			BaseRecord loc = addLocation(f);
			BaseRecord scene = addScene(f, 1, loc);
			BaseRecord comp = compose(f, scene);
			String elOid = addElement(comp, CUP_DESC);
			int before = countItems(f);
			assertNotNull("precondition: the reader can READ the book (else this would be a 404, not the 403 under test)",
				PbBookUtil.readBook(reader, f.bookOid, orgId));

			refused(service.promoteElement(f.bookOid, oid(comp), elOid, requestAs(reader)), 403, "Not authorized to promote an element", "reader promote");
			refused(service.promoteElement(f.bookOid, NIL_UUID, NIL_UUID, requestAs(reader)), 403, "Not authorized to promote an element", "reader promote with nil ids");

			assertEquals("no Items row was written", before, countItems(f));
			assertEquals("the element is still unlinked", 0L, fkId(element(elOid), OlioFieldNames.FIELD_ITEM));
			List<BaseRecord> parts = ioContext.getMemberUtil().findMembers(f.store, OlioFieldNames.FIELD_ITEMS, OlioModelNames.MODEL_ITEM, 0L);
			assertTrue("the store is untouched", parts == null || parts.isEmpty());
		}
		finally {
			cleanup(f);
		}
	}

	/// The owner's happy path: 200 with promoted/created, an itemObjectId that resolves to an olio-owned type=prop row
	/// in the world Items group held by the location's store and linked from the element; the same call again is
	/// idempotent (reused, nothing created, no new row, same item).
	@Test
	public void TestOwnerPromotesThenReusesOnRepeat() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			HttpServletRequestMock o = requestAs(owner);
			BaseRecord loc = addLocation(f);
			BaseRecord scene = addScene(f, 1, loc);
			BaseRecord comp = compose(f, scene);
			String compOid = oid(comp);
			String elOid = addElement(comp, CUP_DESC);
			int before = countItems(f);

			long t0 = System.currentTimeMillis();
			Map<String, Object> d = ok(service.promoteElement(f.bookOid, compOid, elOid, o), "owner promote");
			logger.info("promote: " + (System.currentTimeMillis() - t0) + " ms " + d);
			assertEquals(f.bookOid, d.get("bookObjectId"));
			assertEquals(compOid, d.get("compositionObjectId"));
			assertEquals(elOid, d.get("elementObjectId"));
			assertEquals(Boolean.TRUE, d.get("promoted"));
			assertEquals(Boolean.TRUE, d.get("created"));
			assertEquals(Boolean.FALSE, d.get("reused"));
			assertEquals("no render node on this harness, so nothing to stale", 0, num(d, "staled"));
			assertTrue("no warnings: " + d.get("warnings"), list(d, "warnings").isEmpty());
			String itemOid = String.valueOf(d.get("itemObjectId"));
			assertNotEquals("itemObjectId is reported: " + d, "null", itemOid);
			assertEquals("the item is named from the description", "dented pewter cup", d.get("itemName"));
			String text = String.valueOf(d.get("elementText"));
			assertEquals("elementText is the item once, not name plus the description it was named from", "dented pewter cup", text);
			assertFalse("elementText never says null: " + text, text.contains("null"));

			BaseRecord it = item(itemOid);
			assertEquals("the item is owned by the olio principal", idOf(olioUser), ((Number) it.get(FieldNames.FIELD_OWNER_ID)).longValue());
			assertNotEquals("and not by the owner", idOf(owner), ((Number) it.get(FieldNames.FIELD_OWNER_ID)).longValue());
			assertEquals("item.type", PbComposeUtil.ITEM_TYPE_PROP, it.get(FieldNames.FIELD_TYPE));
			assertEquals("the item sits in the world Items group", idOf(openContext(f).getGroup(OlioFieldNames.FIELD_ITEMS)),
				((Number) it.get(FieldNames.FIELD_GROUP_ID)).longValue());
			assertEquals("exactly one Items row was written", before + 1, countItems(f));
			assertTrue("the location's store holds the item", storeHolds(f, idOf(it)));
			assertEquals("the element links the item", idOf(it), fkId(element(elOid), OlioFieldNames.FIELD_ITEM));

			Map<String, Object> d2 = ok(service.promoteElement(f.bookOid, compOid, elOid, o), "owner promote again");
			assertEquals(Boolean.TRUE, d2.get("promoted"));
			assertEquals("nothing created the second time", Boolean.FALSE, d2.get("created"));
			assertEquals(Boolean.TRUE, d2.get("reused"));
			assertEquals("the same item", itemOid, d2.get("itemObjectId"));
			assertEquals("elementText is stable", d.get("elementText"), d2.get("elementText"));
			assertEquals(0, num(d2, "staled"));
			assertEquals("no second row", before + 1, countItems(f));
		}
		finally {
			cleanup(f);
		}
	}

	/// An element that is not of the composition, or a composition that is not of the book, is 404 - and a
	/// composition with no location is 409 - each leaving the element unlinked and the Items group untouched.
	@Test
	public void TestUnknownIdsAre404AndNoLocationIs409() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			HttpServletRequestMock o = requestAs(owner);
			BaseRecord loc = addLocation(f);
			BaseRecord located = compose(f, addScene(f, 1, loc));
			BaseRecord unlocated = compose(f, addScene(f, 2, null));
			assertEquals("precondition: page 2's composition has no location", 0L, fkId(unlocated, FieldNames.FIELD_LOCATION));
			String elLocated = addElement(located, "a brass lantern");
			String elUnlocated = addElement(unlocated, "a milestone");
			int before = countItems(f);

			refused(service.promoteElement(f.bookOid, oid(located), NIL_UUID, o), 404, "not found in this composition", "unknown element");
			refused(service.promoteElement(f.bookOid, oid(located), elUnlocated, o), 404, "not found in this composition", "element of another composition");
			refused(service.promoteElement(f.bookOid, NIL_UUID, elLocated, o), 404, "not found in this book", "unknown composition");
			refused(service.promoteElement(f.bookOid, oid(unlocated), elUnlocated, o), 409, "has no location to hold the item", "composition without a location");
			refused(service.promoteElement(NIL_UUID, oid(located), elLocated, o), 404, "Book not found", "unknown book");

			assertEquals("nothing written", before, countItems(f));
			assertEquals("the located element is still unlinked", 0L, fkId(element(elLocated), OlioFieldNames.FIELD_ITEM));
			assertEquals("the unlocated element is still unlinked", 0L, fkId(element(elUnlocated), OlioFieldNames.FIELD_ITEM));
		}
		finally {
			cleanup(f);
		}
	}

	/// Blank ids. The route's path template cannot carry a blank segment, so the 400s are exercised on the facade the
	/// route delegates to (PbServiceFacade.promoteElement is the method the route calls, with the same user), after the
	/// book gates and before anything is read. A blank book id is the facade's own 400.
	@Test
	public void TestBlankIdsAre400() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			String some = UUID.randomUUID().toString();
			facadeRefused("blank composition id", 400, "A composition objectId is required", () -> PbServiceFacade.promoteElement(owner, f.bookOid, " ", some));
			facadeRefused("null composition id", 400, "A composition objectId is required", () -> PbServiceFacade.promoteElement(owner, f.bookOid, null, some));
			facadeRefused("blank element id", 400, "An element objectId is required", () -> PbServiceFacade.promoteElement(owner, f.bookOid, some, ""));
			facadeRefused("null element id", 400, "An element objectId is required", () -> PbServiceFacade.promoteElement(owner, f.bookOid, some, null));
			facadeRefused("blank book id", 400, "A book objectId is required", () -> PbServiceFacade.promoteElement(owner, " ", some, some));
			facadeRefused("no user", 401, "No authenticated principal", () -> PbServiceFacade.promoteElement(null, f.bookOid, some, some));
			/// the stranger is refused at the book before the blank ids are seen
			facadeRefused("stranger with blank ids", 404, "Book not found", () -> PbServiceFacade.promoteElement(stranger, f.bookOid, "", ""));
			assertNull("no composition was ever created on this book", PbComposeUtil.readComposition(owner, some, orgId));
		}
		finally {
			cleanup(f);
		}
	}
}
