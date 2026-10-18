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
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil;
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
import jakarta.ws.rs.core.Response;

/// P3-4 (PictureBook V3 §7, §26, §27; Implementation §11.4): the thirteen location routes on PictureBookService
/// exercised through the real service methods -> PbServiceFacade -> PbLocationUtil -> real AccessPoint/PBAC ->
/// the real (H2) database, with HttpServletRequestMock + UserPrincipal exactly as TestPictureBookStyleRoutes
/// does. Nothing is mocked; no asserting actor is ever the admin or the olio principal.
///
///   GET    /{book}/locations                                   listLocations
///   POST   /{book}/locations                                   createLocation
///   GET    /{book}/location/{id}                               getLocation
///   PATCH  /{book}/location/{id}                               updateLocation
///   DELETE /{book}/location/{id}                               deleteLocation
///   POST   /{book}/location/{id}/variants                      createLocationVariant
///   POST   /{book}/location/{id}/render?variant=&force=        renderLocation (gates only - see below)
///   POST   /{book}/location/{id}/lock                          lockLocation
///   POST   /{book}/locations/propose                           proposeLocations
///   GET    /{book}/locations/proposed?status=                  listLocationProposals
///   POST   /{book}/locations/proposed/{id}/accept              acceptLocationProposal
///   POST   /{book}/locations/proposed/{id}/reject              rejectLocationProposal
///   POST   /{book}/locations/merge                             mergeLocations
///   POST   /{book}/scene/{scene}/plan                          planScene, for a scene that NAMES a location
///                                                              (P3-5; the no-location shape is TestPictureBookPlanRoute)
///   PUT    /{book}/scene/{scene}/location                      setSceneLocation (P3-7; the Ux pointing path)
///
/// FIXTURE. Locations live in the book's Olio world (design §7 decided default), so unlike the style routes a
/// bare owner-owned book is not enough: requireBookContext needs a world under /Olio/Universes/Books/Worlds
/// and a Books universe record to assemble a BookContext. The Service7 harness is H2 and never ran
/// OlioContext.initialize, so this test hand-mirrors the production shape the way TestOlioServiceDeleteWorld
/// does: admin bootstraps /Olio and grants the olio principal on it; the olio principal creates /Olio/Universes,
/// the "Books" universe world, the book world (WorldFactory gives it its Locations / Stores / Points of Interest
/// groups), the Book group and an olio-owned olio.pb.book whose `world` FK points at that world; a Writer role
/// (owner enrolled) gets CRUD on the Book group and on EVERY group beneath the world container (the recursive
/// grant scanNestedWorldGroups performs in production) plus Read on the world record (configureWorldAuthorization);
/// an optional Reader role (reader enrolled) gets Read only on the same set; the stranger gets nothing. The grant
/// must cover the whole world tree, not just Locations: reading an olio.pb.location adds dynamic foreign-access
/// rules for its populated `poi` (an olio.pointOfInterest in the world's Points of Interest group) and `world`
/// FKs, so a Locations-only grant is PERMIT on the row and DENY on the read (measured with PolicyUtil trace,
/// first run of this test: "...points.of.interest = false" -> 403 "Location is not readable"). Every write the
/// routes perform on location / variant / store / POI rows is done by PbLocationUtil as the olio principal, so
/// the request user needs exactly the book-update grant plus Read on the world tree - which is what makes the
/// 403 cases below meaningful. Recorded as ⚠ §6.C 174 (hand-built H2 world fixture).
///
/// RENDER. renderLocation evaluates resolveSdApiType/resolveSdServer from the ServletContext BEFORE the facade
/// runs, so a context is required even for the gate cases; SD_CONTEXT points at an unreachable server. Only the
/// gate order is asserted here (404 book, 403 not writer, 404 location, 409 locked) - every one is thrown before
/// ensureWorkflow / PbRenderJobUtil.submit. The owner's 202 path materializes a LOCATION node and spawns a render
/// job against a real SD server and is proven live against the Docker stack (Implementation §11.4), not on H2.
///
/// WIRE NOTE (measured, not a defect under test): JSONUtil.exportObject serialises with Include.NON_EMPTY, so a
/// null `approvedRenderObjectId`/`node`, an empty `aliases`/`variants`/`usedByScenes`/`proposals`/`warnings`
/// are ABSENT from the response rather than present as null/[]; `locked:false` (a boolean) and
/// `scenesConsidered:0` (an int) ARE present. Enum fields arrive as the Java name ("INTERIOR") and are compared
/// case-insensitively.
public class TestPictureBookLocationRoutes extends BaseTest {

	private static final String OWNER_NAME = "pblocowner";
	private static final String READER_NAME = "pblocreader";
	private static final String STRANGER_NAME = "pblocstranger";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String BARE_GROUP_PATH = "~/PictureBookLocationRoutes";
	private static final String[] DATA_AND_GROUP = new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() };
	private static final String[] CRUD = new String[] { "Read", "Update", "Create", "Delete" };
	private static final String[] READ = new String[] { "Read" };
	private static final ServletContext SD_CONTEXT = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", "http://127.0.0.1:1"));

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
		BaseRecord locGroup;
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
		olioUser = ioContext.getFactory().getCreateUser(admin, OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("Olio principal is null", olioUser);
		bootstrapOlioRoot();
		ensureBooksUniverse();
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// /Olio belongs to the admin in a fresh org; the olio principal must be able to build beneath it.
	private void bootstrapOlioRoot() {
		BaseRecord rootDir = ioContext.getPathUtil().makePath(admin, ModelNames.MODEL_GROUP, olioCfg.getBasePath(), "DATA", orgId);
		assertNotNull("Olio root group is null", rootDir);
		ioContext.getAuthorizationUtil().setEntitlement(admin, olioUser, new BaseRecord[] { rootDir }, new String[] { "Read", "Update", "Create" }, DATA_AND_GROUP);
		BaseRecord uDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, olioCfg.getUniversePath(), "DATA", orgId);
		assertNotNull("Universes group is null", uDir);
	}

	/// PbOlioContextUtil.assembleBookContext needs a world record named "Books" in /Olio/Universes; get-or-create,
	/// never deleted (it is shared by every case and by TestOlioServiceDeleteWorld's shape).
	private void ensureBooksUniverse() {
		BaseRecord universe = WorldUtil.getCreateWorld(olioUser, olioCfg.getUniversePath(), PbOlioContextUtil.BOOKS_UNIVERSE, new String[0]);
		assertNotNull("Books universe world is null", universe);
	}

	private String booksWorldsPath() {
		return olioCfg.getUniversePath() + "/" + PbOlioContextUtil.BOOKS_UNIVERSE + "/Worlds";
	}

	private Fixture createWorldBook(boolean withReader) throws Exception {
		Fixture f = new Fixture();
		f.slug = "p34r-" + UUID.randomUUID().toString().substring(0, 12);
		String worldsPath = booksWorldsPath();

		BaseRecord created = WorldUtil.getCreateWorld(olioUser, worldsPath, f.slug, new String[0]);
		assertNotNull("Book world is null", created);
		/// getCreateWorld returns a minimal findByNameInGroup record; re-find and populate before reading its groups.
		f.world = WorldUtil.findWorld(olioUser, worldsPath, f.slug);
		assertNotNull("Book world could not be re-found", f.world);
		ioContext.getReader().populate(f.world, 2);
		f.locGroup = f.world.get(FieldNames.FIELD_LOCATIONS);
		assertNotNull("World carries no Locations group", f.locGroup);

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

	/// Mirrors OlioContext.scanNestedGroups: group entitlements do not inherit down the tree, so every group
	/// beneath the world container (Locations, Stores, Points of Interest, ...) is granted explicitly. Returns
	/// the number of groups granted.
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

	/// The style-routes shape: an owner-owned book in an owner-owned group with no world at all.
	private BaseRecord createBareBook() throws Exception {
		String slug = "p34b-" + UUID.randomUUID().toString().substring(0, 12);
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

	/// Book first, then the world (WorldUtil.deleteWorld sweeps locations, variants, stores and POIs). Logs, never asserts.
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

	// ─────────────────────────────── helpers ───────────────────────────────

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPictureBookLocationRoutes.class.getClassLoader(),
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

	private static boolean containsIgnoreCase(List<String> in, String s) {
		for(String i : in) {
			if(i != null && i.equalsIgnoreCase(s)) {
				return true;
			}
		}
		return false;
	}

	private Map<String, Object> createLocation(BaseRecord as, String bookOid, String name, String description, String kind, List<String> aliases) {
		Map<String, Object> req = new LinkedHashMap<>();
		req.put("name", name);
		if(description != null) req.put("description", description);
		if(kind != null) req.put("kind", kind);
		if(aliases != null) req.put("aliases", aliases);
		return ok(service.createLocation(bookOid, JSONUtil.exportObject(req), requestAs(as)), "createLocation " + name);
	}

	private Map<String, Object> locationNamed(Map<String, Object> listBody, String name) {
		for(Object o : list(listBody, "locations")) {
			assertTrue("locations[] entries must be objects", o instanceof Map);
			@SuppressWarnings("unchecked")
			Map<String, Object> l = (Map<String, Object>) o;
			if(name.equals(l.get("name"))) {
				return l;
			}
		}
		return null;
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/// Without a principal every route is 401 before the book is even looked up - a read and a write route.
	@Test
	public void TestNoPrincipalIs401() {
		HttpServletRequestMock anon = new HttpServletRequestMock();
		refused(service.listLocations(NIL_UUID, anon), 401, "No authenticated principal", "anonymous list");
		refused(service.createLocation(NIL_UUID, json("name", "Anywhere"), anon), 401, "No authenticated principal", "anonymous create");
		refused(service.lockLocation(NIL_UUID, NIL_UUID, json("locked", Boolean.TRUE), anon), 401, "No authenticated principal", "anonymous lock");
		refused(service.listLocationProposals(NIL_UUID, null, anon), 401, "No authenticated principal", "anonymous proposals");
		/// the body is read before the principal is required, so a well-formed body is what reaches the 401
		refused(service.setSceneLocation(NIL_UUID, NIL_UUID, "{}", anon), 401, "No authenticated principal", "anonymous scene location");
		refused(service.setSceneLocation(NIL_UUID, NIL_UUID, json("locationObjectId", NIL_UUID), anon), 401, "No authenticated principal", "anonymous scene location with an id");
	}

	/// A user with no grant on the book cannot tell it exists: 404 "Book not found" on all fourteen routes, the
	/// location / proposal / scene ids never being consulted.
	@Test
	public void TestStrangerIs404OnEveryRoute() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock s = requestAs(stranger);
			String nf = "Book not found";
			refused(service.listLocations(f.bookOid, s), 404, nf, "stranger list");
			refused(service.createLocation(f.bookOid, json("name", "Tavern"), s), 404, nf, "stranger create");
			refused(service.getLocation(f.bookOid, NIL_UUID, s), 404, nf, "stranger get");
			refused(service.updateLocation(f.bookOid, NIL_UUID, json("description", "x"), s), 404, nf, "stranger patch");
			refused(service.deleteLocation(f.bookOid, NIL_UUID, s), 404, nf, "stranger delete");
			refused(service.createLocationVariant(f.bookOid, NIL_UUID, json("timeOfDay", "dusk"), s), 404, nf, "stranger variant");
			refused(service.renderLocation(f.bookOid, NIL_UUID, null, false, s, SD_CONTEXT), 404, nf, "stranger render");
			refused(service.lockLocation(f.bookOid, NIL_UUID, json("locked", Boolean.TRUE), s), 404, nf, "stranger lock");
			refused(service.proposeLocations(f.bookOid, "{}", s), 404, nf, "stranger propose");
			refused(service.listLocationProposals(f.bookOid, null, s), 404, nf, "stranger proposals");
			refused(service.acceptLocationProposal(f.bookOid, NIL_UUID, "{}", s), 404, nf, "stranger accept");
			refused(service.rejectLocationProposal(f.bookOid, NIL_UUID, "{}", s), 404, nf, "stranger reject");
			refused(service.mergeLocations(f.bookOid, json("duplicateObjectId", NIL_UUID, "survivorObjectId", NIL_UUID), s), 404, nf, "stranger merge");
			/// a real page of the book, so the 404 is provably the book gate and not a scene miss
			BaseRecord page = addScene(f, 1);
			refused(service.setSceneLocation(f.bookOid, oid(page), json("locationObjectId", NIL_UUID), s), 404, nf, "stranger scene location");
			refused(service.setSceneLocation(f.bookOid, oid(page), "{}", s), 404, nf, "stranger scene location clear");
			assertPointer(oid(page), null, null, "the stranger wrote nothing");
		}
		finally {
			cleanup(f);
		}
	}

	/// A book with no world reference (PB1 shape) is readable but has nowhere to keep locations: 409.
	@Test
	public void TestBookWithoutWorldIs409() throws Exception {
		BaseRecord bare = createBareBook();
		try {
			HttpServletRequestMock o = requestAs(owner);
			refused(service.listLocations(oid(bare), o), 409, "has no resolvable world", "bare list");
			refused(service.createLocation(oid(bare), json("name", "Tavern"), o), 409, "has no resolvable world", "bare create");
		}
		finally {
			cleanupBare(bare);
		}
	}

	/// Route-level body checks fire before requireBook (so NIL ids suffice), facade-level value checks after it.
	@Test
	public void TestBodyAndValueValidation() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock o = requestAs(owner);
			String bad = "[1,2]";
			String mb = "Malformed request body";
			refused(service.createLocation(NIL_UUID, bad, o), 400, mb, "malformed create");
			refused(service.updateLocation(NIL_UUID, NIL_UUID, bad, o), 400, mb, "malformed patch");
			refused(service.createLocationVariant(NIL_UUID, NIL_UUID, bad, o), 400, mb, "malformed variant");
			refused(service.lockLocation(NIL_UUID, NIL_UUID, bad, o), 400, mb, "malformed lock");
			refused(service.proposeLocations(NIL_UUID, bad, o), 400, mb, "malformed propose");
			refused(service.acceptLocationProposal(NIL_UUID, NIL_UUID, bad, o), 400, mb, "malformed accept");
			refused(service.rejectLocationProposal(NIL_UUID, NIL_UUID, bad, o), 400, mb, "malformed reject");
			refused(service.mergeLocations(NIL_UUID, bad, o), 400, mb, "malformed merge");

			refused(service.createLocation(NIL_UUID, "{}", o), 400, "name is required", "create without name");
			refused(service.createLocation(NIL_UUID, json("name", "   "), o), 400, "name is required", "create with blank name");
			refused(service.lockLocation(NIL_UUID, NIL_UUID, json("locked", "yes"), o), 400, "locked must be a JSON boolean", "lock with string");
			refused(service.lockLocation(NIL_UUID, NIL_UUID, "{}", o), 400, "locked must be a JSON boolean", "lock without flag");
			refused(service.mergeLocations(NIL_UUID, json("duplicateObjectId", NIL_UUID), o), 400, "duplicateObjectId and survivorObjectId are required", "merge missing survivor");
			refused(service.mergeLocations(NIL_UUID, "{}", o), 400, "duplicateObjectId and survivorObjectId are required", "merge missing both");

			/// facade-level: these need a real, writable book
			refused(service.createLocation(f.bookOid, json("name", "Tavern", "kind", "underwater"), o), 400, "Unknown location kind", "create with unknown kind");
			refused(service.listLocationProposals(f.bookOid, "bogus", o), 400, "Unknown proposal status", "proposed list with unknown status");
			refused(service.getLocation(f.bookOid, NIL_UUID, o), 404, "Location not found in this book's world", "get of unknown location");
			refused(service.renderLocation(f.bookOid, NIL_UUID, null, false, o, SD_CONTEXT), 404, "Location not found in this book's world", "render of unknown location");
			refused(service.mergeLocations(f.bookOid, json("duplicateObjectId", NIL_UUID, "survivorObjectId", NIL_UUID), o), 404, "Location not found in this book's world", "merge of unknown locations");
		}
		finally {
			cleanup(f);
		}
	}

	/// The owner's whole library lifecycle on one location: create (idempotent on name), list, get, PATCH, variants, delete.
	@Test
	public void TestOwnerLocationLifecycle() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock o = requestAs(owner);
			String worldOid = oid(f.world);

			Map<String, Object> loc = createLocation(owner, f.bookOid, "The Drowned Anchor", "A low-ceilinged harbour tavern", "interior", List.of("the Anchor", "Drowned Anchor"));
			String locOid = String.valueOf(loc.get("objectId"));
			assertNotEquals("created location carries an objectId", "null", locOid);
			assertEquals("The Drowned Anchor", loc.get("name"));
			assertEquals("A low-ceilinged harbour tavern", loc.get("description"));
			assertTrue("kind arrives as INTERIOR: " + loc.get("kind"), "INTERIOR".equalsIgnoreCase(String.valueOf(loc.get("kind"))));
			assertEquals("a new location is unlocked", Boolean.FALSE, loc.get("locked"));
			assertEquals("the location lives in the book's world", worldOid, loc.get("worldObjectId"));
			assertNotNull("§7.2 rule 1: a resolved location in a book world has a map-free POI", loc.get("poiObjectId"));
			assertNull("no render is approved yet", loc.get("approvedRenderObjectId"));
			assertNull("no workflow, so no node", loc.get("node"));
			List<String> aliases = strings(list(loc, "aliases"));
			assertTrue("aliases kept: " + aliases, containsIgnoreCase(aliases, "the Anchor") && containsIgnoreCase(aliases, "Drowned Anchor"));
			assertFalse("the location's own name is never an alias", containsIgnoreCase(aliases, "The Drowned Anchor"));

			/// idempotent on name (case-insensitive, alias-aware): same objectId, no second row
			Map<String, Object> again = createLocation(owner, f.bookOid, "the drowned anchor", null, null, null);
			assertEquals("re-creating by name returns the same location", locOid, again.get("objectId"));
			Map<String, Object> viaAlias = createLocation(owner, f.bookOid, "Drowned Anchor", null, null, null);
			assertEquals("creating by an alias returns the existing location", locOid, viaAlias.get("objectId"));

			Map<String, Object> listed = ok(service.listLocations(f.bookOid, o), "list");
			assertEquals(f.bookOid, listed.get("bookObjectId"));
			assertEquals(worldOid, listed.get("worldObjectId"));
			assertEquals("exactly one location in a fresh world", 1, list(listed, "locations").size());
			Map<String, Object> inList = locationNamed(listed, "The Drowned Anchor");
			assertNotNull("list carries the created location", inList);
			assertEquals(locOid, inList.get("objectId"));

			Map<String, Object> got = ok(service.getLocation(f.bookOid, locOid, o), "get");
			assertEquals(locOid, got.get("objectId"));
			assertEquals("The Drowned Anchor", got.get("name"));

			/// PATCH: description + kind + aliases are the editable set; anything else is a 400 naming the set
			Map<String, Object> patched = ok(service.updateLocation(f.bookOid, locOid, json("description", "A low-ceilinged harbour tavern, lamps swinging", "kind", "MIXED", "aliases", List.of("the Anchor", "Anchor tavern")), o), "patch");
			assertEquals("A low-ceilinged harbour tavern, lamps swinging", patched.get("description"));
			assertTrue("kind patched to MIXED: " + patched.get("kind"), "MIXED".equalsIgnoreCase(String.valueOf(patched.get("kind"))));
			List<String> patchedAliases = strings(list(patched, "aliases"));
			assertTrue("aliases replaced: " + patchedAliases, containsIgnoreCase(patchedAliases, "Anchor tavern"));
			assertFalse("aliases replaced, not merged: " + patchedAliases, containsIgnoreCase(patchedAliases, "Drowned Anchor"));
			refused(service.updateLocation(f.bookOid, locOid, json("name", "Renamed"), o), 400, "is not editable", "patch of name");
			refused(service.updateLocation(f.bookOid, locOid, "{}", o), 400, "Nothing to change", "empty patch");
			refused(service.updateLocation(f.bookOid, locOid, json("kind", "underwater"), o), 400, "Unknown location kind", "patch with unknown kind");
			refused(service.updateLocation(f.bookOid, locOid, json("aliases", "not-a-list"), o), 400, "aliases must be a list of strings", "patch with scalar aliases");

			/// variants: default name is "<location> - <deltas>", at least one delta, names unique per location
			Map<String, Object> v1 = ok(service.createLocationVariant(f.bookOid, locOid, json("timeOfDay", "dusk", "weather", "rain"), o), "variant dusk/rain");
			String v1Oid = String.valueOf(v1.get("objectId"));
			assertNotEquals("variant carries an objectId", "null", v1Oid);
			assertEquals("default variant name joins the deltas", "The Drowned Anchor - dusk, rain", v1.get("name"));
			assertEquals(locOid, v1.get("locationObjectId"));
			assertEquals("dusk", v1.get("timeOfDay"));
			assertEquals("rain", v1.get("weather"));
			refused(service.createLocationVariant(f.bookOid, locOid, "{}", o), 400, "at least one of timeOfDay, weather, season, viewpoint or extra", "variant with no delta");
			refused(service.createLocationVariant(f.bookOid, locOid, json("name", "the drowned anchor - DUSK, RAIN", "season", "winter"), o), 409, "already has a variant named", "variant with duplicate name");
			Map<String, Object> v2 = ok(service.createLocationVariant(f.bookOid, locOid, json("name", "Anchor at dawn", "timeOfDay", "dawn", "extra", "empty, chairs on tables"), o), "variant dawn");
			assertEquals("explicit variant name kept", "Anchor at dawn", v2.get("name"));

			Map<String, Object> withVariants = ok(service.getLocation(f.bookOid, locOid, o), "get with variants");
			List<Object> variants = list(withVariants, "variants");
			assertEquals("both variants are listed under the location", 2, variants.size());

			/// delete sweeps the variants and the POI/store with it, reporting each step
			Map<String, Object> del = ok(service.deleteLocation(f.bookOid, locOid, o), "delete");
			assertEquals("delete reports success", Boolean.TRUE, del.get("deleted"));
			assertEquals(locOid, del.get("locationObjectId"));
			List<Object> steps = list(del, "steps");
			assertFalse("delete reports its steps", steps.isEmpty());
			List<String> stepNames = new ArrayList<>();
			for(Object s : steps) {
				assertTrue("steps[] entries must be objects", s instanceof Map);
				stepNames.add(String.valueOf(((Map<?, ?>) s).get("step")));
				assertEquals("every step succeeded: " + s, Boolean.TRUE, ((Map<?, ?>) s).get("ok"));
			}
			assertTrue("variants are a delete step: " + stepNames, stepNames.contains("variants"));
			refused(service.getLocation(f.bookOid, locOid, o), 404, "Location not found in this book's world", "get after delete");
			refused(service.getLocation(f.bookOid, v1Oid, o), 404, "Location not found in this book's world", "variant id is not a location");
			Map<String, Object> emptyList = ok(service.listLocations(f.bookOid, o), "list after delete");
			assertTrue("world has no locations after the delete", list(emptyList, "locations").isEmpty());
		}
		finally {
			cleanup(f);
		}
	}

	/// Lock is whole-asset (§6.C 174): a locked location refuses edit, variant, delete, render and merge in either
	/// direction with one 409 shape; locking is idempotent; unlock restores everything; merge folds the duplicate's
	/// name and aliases into the survivor and removes the duplicate.
	@Test
	public void TestOwnerLockAndMerge() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock o = requestAs(owner);
			Map<String, Object> a = createLocation(owner, f.bookOid, "Windswept Hill", "A bald hill above the town", "exterior", List.of("the Hill"));
			Map<String, Object> b = createLocation(owner, f.bookOid, "The Hill Above Town", "Same hill, as the narrator first calls it", "exterior", List.of("the bald hill"));
			String aOid = String.valueOf(a.get("objectId"));
			String bOid = String.valueOf(b.get("objectId"));
			assertNotEquals("two distinct locations", aOid, bOid);

			Map<String, Object> locked = ok(service.lockLocation(f.bookOid, aOid, json("locked", Boolean.TRUE), o), "lock");
			assertEquals(aOid, locked.get("locationObjectId"));
			assertEquals(Boolean.TRUE, locked.get("locked"));
			Map<String, Object> lockedAgain = ok(service.lockLocation(f.bookOid, aOid, json("locked", Boolean.TRUE), o), "lock again");
			assertEquals("locking an already-locked location is a no-op, never refused", Boolean.TRUE, lockedAgain.get("locked"));
			assertEquals("get reflects the lock", Boolean.TRUE, ok(service.getLocation(f.bookOid, aOid, o), "get locked").get("locked"));

			String lockedMsg = "Location Windswept Hill is locked; unlock it to ";
			refused(service.updateLocation(f.bookOid, aOid, json("description", "changed"), o), 409, lockedMsg + "edit it", "patch while locked");
			refused(service.createLocationVariant(f.bookOid, aOid, json("timeOfDay", "night"), o), 409, lockedMsg + "add a variant", "variant while locked");
			refused(service.deleteLocation(f.bookOid, aOid, o), 409, lockedMsg + "delete it", "delete while locked");
			refused(service.renderLocation(f.bookOid, aOid, null, false, o, SD_CONTEXT), 409, lockedMsg + "render it", "render while locked");
			refused(service.mergeLocations(f.bookOid, json("duplicateObjectId", aOid, "survivorObjectId", bOid), o), 409, lockedMsg + "merge it into another location", "merge locked duplicate");
			refused(service.mergeLocations(f.bookOid, json("duplicateObjectId", bOid, "survivorObjectId", aOid), o), 409, lockedMsg + "merge another location into it", "merge into locked survivor");
			refused(service.mergeLocations(f.bookOid, json("duplicateObjectId", bOid, "survivorObjectId", bOid), o), 400, "cannot be merged into itself", "self merge");

			Map<String, Object> unlocked = ok(service.lockLocation(f.bookOid, aOid, json("locked", Boolean.FALSE), o), "unlock");
			assertEquals(Boolean.FALSE, unlocked.get("locked"));
			Map<String, Object> patched = ok(service.updateLocation(f.bookOid, aOid, json("description", "A bald hill above the town, gorse to the knee"), o), "patch after unlock");
			assertEquals("A bald hill above the town, gorse to the knee", patched.get("description"));

			Map<String, Object> merged = ok(service.mergeLocations(f.bookOid, json("duplicateObjectId", bOid, "survivorObjectId", aOid), o), "merge");
			assertEquals(Boolean.TRUE, merged.get("merged"));
			assertEquals(bOid, merged.get("duplicateObjectId"));
			assertEquals(aOid, merged.get("survivorObjectId"));
			Map<String, Object> survivor = map(merged, "survivor");
			assertEquals(aOid, survivor.get("objectId"));
			List<String> aliases = strings(list(survivor, "aliases"));
			assertTrue("survivor keeps its own aliases: " + aliases, containsIgnoreCase(aliases, "the Hill"));
			assertTrue("duplicate's name becomes an alias: " + aliases, containsIgnoreCase(aliases, "The Hill Above Town"));
			assertTrue("duplicate's aliases fold in: " + aliases, containsIgnoreCase(aliases, "the bald hill"));
			refused(service.getLocation(f.bookOid, bOid, o), 404, "Location not found in this book's world", "duplicate after merge");
			Map<String, Object> listed = ok(service.listLocations(f.bookOid, o), "list after merge");
			assertEquals("one location survives the merge", 1, list(listed, "locations").size());
			Map<String, Object> viaFoldedName = createLocation(owner, f.bookOid, "The Hill Above Town", null, null, null);
			assertEquals("the folded name now resolves to the survivor", aOid, viaFoldedName.get("objectId"));
		}
		finally {
			cleanup(f);
		}
	}

	/// The proposal routes on a book with no scenes: the pass returns without touching the LLM, the proposed list is
	/// empty and accept / reject of an unknown proposal is a 404 after the book gate.
	@Test
	public void TestOwnerProposalsOnSceneLessBook() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock o = requestAs(owner);
			Map<String, Object> pass = ok(service.proposeLocations(f.bookOid, "{}", o), "propose");
			assertEquals(f.bookOid, pass.get("bookObjectId"));
			assertNotNull("passRun is reported", pass.get("passRun"));
			assertEquals("no scene has a setting, so none were considered", 0, ((Number) pass.get("scenesConsidered")).intValue());
			assertEquals("no LLM chunk was built", 0, ((Number) pass.get("chunks")).intValue());
			assertNotEquals("the pass is not a hard failure", Boolean.TRUE, pass.get("hardFailure"));
			List<String> warnings = strings(list(pass, "warnings"));
			boolean saidWhy = false;
			for(String w : warnings) {
				if(w.contains("No scene of " + f.slug + " has a setting to cluster")) {
					saidWhy = true;
				}
			}
			assertTrue("the pass says why it proposed nothing: " + warnings, saidWhy);
			assertTrue("nothing proposed", list(pass, "proposals").isEmpty());
			Map<String, Object> alsoNull = ok(service.proposeLocations(f.bookOid, null, o), "propose with no body");
			assertEquals(0, ((Number) alsoNull.get("scenesConsidered")).intValue());

			Map<String, Object> proposed = ok(service.listLocationProposals(f.bookOid, null, o), "proposed list");
			assertEquals(f.bookOid, proposed.get("bookObjectId"));
			assertTrue("no proposals on a scene-less book", list(proposed, "proposals").isEmpty());
			Map<String, Object> proposedFiltered = ok(service.listLocationProposals(f.bookOid, "proposed", o), "proposed list filtered");
			assertTrue("status filter is case-insensitive and still empty", list(proposedFiltered, "proposals").isEmpty());

			refused(service.acceptLocationProposal(f.bookOid, NIL_UUID, "{}", o), 404, "Proposal not found in this book", "accept unknown");
			refused(service.acceptLocationProposal(f.bookOid, NIL_UUID, json("overwrite", Boolean.TRUE), o), 404, "Proposal not found in this book", "accept unknown with overwrite");
			refused(service.rejectLocationProposal(f.bookOid, NIL_UUID, json("reason", "nope"), o), 404, "Proposal not found in this book", "reject unknown");
		}
		finally {
			cleanup(f);
		}
	}

	/// A Read-only grantee sees the library but may not change it: list / get / proposed list 200; every write 403
	/// naming the refused action, before any body or lock check.
	@Test
	public void TestReaderReadsButCannotWrite() throws Exception {
		Fixture f = createWorldBook(true);
		try {
			HttpServletRequestMock o = requestAs(owner);
			HttpServletRequestMock r = requestAs(reader);
			Map<String, Object> loc = createLocation(owner, f.bookOid, "Lighthouse Stair", "The spiral stair of the lighthouse", "interior", null);
			String locOid = String.valueOf(loc.get("objectId"));

			Map<String, Object> listed = ok(service.listLocations(f.bookOid, r), "reader list");
			assertNotNull("reader sees the location", locationNamed(listed, "Lighthouse Stair"));
			Map<String, Object> got = ok(service.getLocation(f.bookOid, locOid, r), "reader get");
			assertEquals(locOid, got.get("objectId"));
			ok(service.listLocationProposals(f.bookOid, null, r), "reader proposed list");

			String na = "Not authorized to ";
			refused(service.createLocation(f.bookOid, json("name", "Another"), r), 403, na + "add a location in this book", "reader create");
			refused(service.updateLocation(f.bookOid, locOid, json("description", "x"), r), 403, na + "edit a location in this book", "reader patch");
			refused(service.updateLocation(f.bookOid, locOid, json("name", "x"), r), 403, na + "edit a location in this book", "reader patch of name is still the 403, not the 400");
			refused(service.deleteLocation(f.bookOid, locOid, r), 403, na + "delete a location in this book", "reader delete");
			refused(service.createLocationVariant(f.bookOid, locOid, json("timeOfDay", "dusk"), r), 403, na + "add a location variant in this book", "reader variant");
			refused(service.renderLocation(f.bookOid, locOid, null, false, r, SD_CONTEXT), 403, na + "render a location in this book", "reader render");
			refused(service.lockLocation(f.bookOid, locOid, json("locked", Boolean.TRUE), r), 403, na + "lock a location in this book", "reader lock");
			refused(service.lockLocation(f.bookOid, locOid, json("locked", Boolean.FALSE), r), 403, na + "unlock a location in this book", "reader unlock");
			refused(service.proposeLocations(f.bookOid, "{}", r), 403, na + "propose locations in this book", "reader propose");
			refused(service.acceptLocationProposal(f.bookOid, NIL_UUID, "{}", r), 403, na + "accept a location proposal in this book", "reader accept");
			refused(service.rejectLocationProposal(f.bookOid, NIL_UUID, "{}", r), 403, na + "reject a location proposal in this book", "reader reject");
			refused(service.mergeLocations(f.bookOid, json("duplicateObjectId", locOid, "survivorObjectId", NIL_UUID), r), 403, na + "merge locations in this book", "reader merge");
			/// a real page of the book pointed at a real location: the 403 fires before the scene or the location is consulted
			BaseRecord page = addScene(f, 1);
			refused(service.setSceneLocation(f.bookOid, oid(page), json("locationObjectId", locOid), r), 403, na + "point a scene at a location in this book", "reader scene location");
			refused(service.setSceneLocation(f.bookOid, oid(page), "{}", r), 403, na + "point a scene at a location in this book", "reader scene location clear");

			/// and nothing changed
			Map<String, Object> after = ok(service.getLocation(f.bookOid, locOid, o), "owner get after reader attempts");
			assertEquals("The spiral stair of the lighthouse", after.get("description"));
			assertEquals(Boolean.FALSE, after.get("locked"));
			assertTrue("no variant was added", list(after, "variants").isEmpty());
			assertEquals("still exactly one location", 1, list(ok(service.listLocations(f.bookOid, o), "owner list"), "locations").size());
			assertPointer(oid(page), null, null, "the reader pointed nothing");
		}
		finally {
			cleanup(f);
		}
	}

	// ─────────────────────────────── P3-5: the plan of a scene that names a location ───────────────────────────────

	private static final String SCENE_SETTING = "Rain hammers the shutters while the fiddler tunes up";
	private static final String TAVERN_DESCRIPTION = "A low-ceilinged harbour tavern";

	/// A page of the world book created as the owner (Writer) with its setting patched in, read back with the
	/// scene projection (TestPictureBookPlanRoute.addScene).
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

	/// Points the scene at a location and/or a variant the way the Ux does it: a field-list patch of the scene's
	/// own FK fields through AccessPoint as the owner. The FK targets are olio-owned world rows the owner can
	/// READ, which is all the writer gates on - so a location of ANOTHER world persists too (the resolver, not
	/// the writer, is what refuses it; TestLocatedScenePlanRefusesForeignReferences).
	private void pointScene(BaseRecord scene, BaseRecord location, BaseRecord variant) throws Exception {
		List<String> fields = new ArrayList<>(List.of(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_LOCATION));
		if(variant != null) {
			fields.add(OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
		}
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE, fields.toArray(new String[0]));
		patch.set(FieldNames.FIELD_ID, scene.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, oid(scene));
		patch.set(FieldNames.FIELD_NAME, scene.get(FieldNames.FIELD_NAME));
		patch.set(FieldNames.FIELD_LOCATION, location);
		if(variant != null) {
			patch.set(OlioFieldNames.FIELD_PB_LOCATION_VARIANT, variant);
		}
		assertNotNull("scene location patch must persist", IOSystem.getActiveContext().getAccessPoint().update(owner, patch));
	}

	/// The page as the owner reads it now (PbBookUtil.readScene is uncached, so this is the persisted state).
	private BaseRecord freshPage(String sceneOid) {
		BaseRecord s = PbBookUtil.readScene(owner, sceneOid, orgId);
		assertNotNull("page " + sceneOid + " must read back as the owner", s);
		return s;
	}

	/// Both pointer FKs of the page, re-read fresh; a cleared / never-set FK reads back as a null nested record.
	private void assertPointer(String sceneOid, String expectLocOid, String expectVarOid, String what) {
		BaseRecord s = freshPage(sceneOid);
		BaseRecord loc = s.get(FieldNames.FIELD_LOCATION);
		BaseRecord var = s.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
		assertEquals(what + ": persisted location pointer", expectLocOid, oid(loc));
		assertEquals(what + ": persisted variant pointer", expectVarOid, oid(var));
	}

	private BaseRecord locationRecord(String locOid) {
		BaseRecord loc = PbLocationUtil.readLocation(owner, locOid, orgId);
		assertNotNull("location " + locOid + " must read back as the owner", loc);
		return loc;
	}

	private BaseRecord variantRecord(String varOid) {
		BaseRecord var = PbLocationUtil.readVariant(owner, varOid, orgId);
		assertNotNull("variant " + varOid + " must read back as the owner", var);
		return var;
	}

	private Map<String, Object> planOf(Fixture f, BaseRecord scene) {
		return ok(service.planScene(f.bookOid, oid(scene), "{}", requestAs(owner), SD_CONTEXT), "plan of " + scene.get(FieldNames.FIELD_NAME));
	}

	/// The LOCATION_TEXT_ONLY degradations of a plan DTO, in order.
	private List<Map<String, Object>> locationDegradations(Map<String, Object> dto) {
		List<Map<String, Object>> out = new ArrayList<>();
		for(Object o : list(dto, "degradations")) {
			assertTrue("degradations[] entries must be objects", o instanceof Map);
			@SuppressWarnings("unchecked")
			Map<String, Object> d = (Map<String, Object>) o;
			if(RenderPlan.Degradation.LOCATION_TEXT_ONLY.equals(d.get("code"))) {
				out.add(d);
			}
		}
		return out;
	}

	private void assertTheOneLocationDegradation(Map<String, Object> dto, String message) {
		List<Map<String, Object>> ds = locationDegradations(dto);
		assertEquals("exactly one LOCATION_TEXT_ONLY: " + dto.get("degradations"), 1, ds.size());
		assertEquals(message, ds.get(0).get("message"));
		assertEquals(RenderPlan.STAGE_BASE, ds.get(0).get("stage"));
		assertNull("not an actor degradation: " + ds.get(0), ds.get(0).get("actorId"));
	}

	private String basePrompt(Map<String, Object> dto) {
		Map<String, Object> base = map(map(dto, "compiled"), RenderPlan.STAGE_BASE);
		assertFalse("compiled must carry a BASE entry: " + dto.get("compiled"), base.isEmpty());
		return String.valueOf(base.get("prompt"));
	}

	/// A scene that names an (unrendered) location: the plan carries the location's identity and revision hash,
	/// composes its description ahead of the scene setting, carries no render, and says so with one
	/// LOCATION_TEXT_ONLY on BASE. A second scene at the location's dusk/rain variant names the variant, carries the
	/// variant's own input beside the location's, composes the qualifiers, and the degradation names the variant.
	/// Nothing here needs an SD server: the pinned loopback SD only adds the MODEL_SUBSTITUTED line every plan in
	/// this harness carries. The rendered half (the approved render riding the plan) is the Playwright proof
	/// (pictureBookLocations.spec.js, PB_SD_TESTS=1) and TestPbScenePlanLocation (Objects7, SdTestGate).
	@Test
	public void TestLocatedScenePlanCarriesTheLocation() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock o = requestAs(owner);
			Map<String, Object> locDto = createLocation(owner, f.bookOid, "The Drowned Anchor", TAVERN_DESCRIPTION, "interior", null);
			String locOid = String.valueOf(locDto.get("objectId"));
			BaseRecord loc = locationRecord(locOid);
			Map<String, Object> varDto = ok(service.createLocationVariant(f.bookOid, locOid, json("timeOfDay", "dusk", "weather", "rain"), o), "variant dusk/rain");
			String varOid = String.valueOf(varDto.get("objectId"));
			assertEquals("The Drowned Anchor - dusk, rain", varDto.get("name"));
			BaseRecord var = variantRecord(varOid);

			BaseRecord located = addScene(f, 1);
			pointScene(located, loc, null);
			BaseRecord atVariant = addScene(f, 2);
			pointScene(atVariant, loc, var);
			BaseRecord plain = addScene(f, 3);

			/// the located scene
			Map<String, Object> dto = planOf(f, located);
			Map<String, Object> location = map(dto, "location");
			assertEquals(locOid, location.get("locationId"));
			assertNull("no variant named: " + location, location.get("variantId"));
			String revisionHash = String.valueOf(location.get("revisionHash"));
			assertTrue("revisionHash is the location's watched-field hash: " + location, location.get("revisionHash") != null && !revisionHash.isEmpty());
			assertNull("no approved render, so none is carried: " + location, location.get("renderArtifactId"));
			assertEquals("location description, then the scene setting", TAVERN_DESCRIPTION + ". " + SCENE_SETTING, location.get("description"));
			Map<String, Object> inputs = map(dto, "inputs");
			assertEquals("inputs.location is the revision hash: " + inputs, revisionHash, inputs.get("location"));
			assertFalse("no locationVariant input on a plain located scene: " + inputs, inputs.containsKey("locationVariant"));
			assertTheOneLocationDegradation(dto, "No approved render for location The Drowned Anchor; setting described in text");
			assertTrue("the carried text is what is compiled: " + basePrompt(dto), basePrompt(dto).contains(TAVERN_DESCRIPTION + ". " + SCENE_SETTING));
			assertEquals("strategy unchanged by the location", "TEXT_ONLY", map(dto, "strategy").get("name"));
			String hash = String.valueOf(dto.get("contentHash"));
			assertEquals("contentHash is the hash of the plan on the wire", RenderPlan.fromMap(dto).contentHash(), hash);
			assertEquals("the same scene plans identically twice", hash, planOf(f, located).get("contentHash"));

			/// the scene at the variant
			Map<String, Object> vdto = planOf(f, atVariant);
			Map<String, Object> vloc = map(vdto, "location");
			assertEquals(locOid, vloc.get("locationId"));
			assertEquals(varOid, vloc.get("variantId"));
			assertEquals("the revision hash is the location's", revisionHash, vloc.get("revisionHash"));
			assertNull("the location's missing render is not the variant's either: " + vloc, vloc.get("renderArtifactId"));
			assertEquals("location, dusk, rain, then the scene setting", TAVERN_DESCRIPTION + ". dusk. rain. " + SCENE_SETTING, vloc.get("description"));
			Map<String, Object> vinputs = map(vdto, "inputs");
			assertEquals(revisionHash, vinputs.get("location"));
			Object variantInput = vinputs.get("locationVariant");
			assertTrue("inputs.locationVariant is the variant's own hash: " + vinputs, variantInput instanceof String && !((String) variantInput).isEmpty());
			assertNotEquals("the variant's hash is not the location's", revisionHash, variantInput);
			assertTheOneLocationDegradation(vdto, "No approved render for variant The Drowned Anchor - dusk, rain; setting described in text");
			assertTrue("compiled prompt carries the qualifiers: " + basePrompt(vdto), basePrompt(vdto).contains("dusk. rain"));
			assertNotEquals("the two plans differ", hash, vdto.get("contentHash"));

			/// the plain scene is unchanged by its siblings
			Map<String, Object> pdto = planOf(f, plain);
			Map<String, Object> ploc = map(pdto, "location");
			assertNull(ploc.get("locationId"));
			assertNull(ploc.get("revisionHash"));
			assertEquals(SCENE_SETTING, ploc.get("description"));
			assertFalse("no location input without a location: " + pdto.get("inputs"), map(pdto, "inputs").containsKey("location"));
			assertTrue("no LOCATION_TEXT_ONLY without a location", locationDegradations(pdto).isEmpty());

			/// editing the location moves its hash, and the plan with it, for every scene that names it
			ok(service.updateLocation(f.bookOid, locOid, json("description", TAVERN_DESCRIPTION + ", lamps swinging"), o), "patch description");
			Map<String, Object> edited = planOf(f, located);
			String editedHash = String.valueOf(map(edited, "location").get("revisionHash"));
			assertNotEquals("the revision hash moved with the description", revisionHash, editedHash);
			assertEquals(editedHash, map(edited, "inputs").get("location"));
			assertEquals(TAVERN_DESCRIPTION + ", lamps swinging. " + SCENE_SETTING, map(edited, "location").get("description"));
			assertNotEquals("the content hash moved", hash, edited.get("contentHash"));
			Map<String, Object> vedited = planOf(f, atVariant);
			assertEquals("the variant scene sees the new location hash", editedHash, map(vedited, "inputs").get("location"));
			assertEquals("the variant's own input did not move", variantInput, map(vedited, "inputs").get("locationVariant"));
		}
		finally {
			cleanup(f);
		}
	}

	/// The resolver refuses what the writer let through (security review of P3-5). A location of ANOTHER world
	/// (the owner is Writer of both books, so the FK persists) is treated as unreadable: null hash, no composed text,
	/// no render, the named variant falls with it, and the message says why. A variant of ANOTHER location of the
	/// same world keeps the location's hash and text, carries a null locationVariant input and no render, and the
	/// message says why. Both are the one LOCATION_TEXT_ONLY on BASE - no new code.
	@Test
	public void TestLocatedScenePlanRefusesForeignReferences() throws Exception {
		Fixture f = createWorldBook(false);
		Fixture other = null;
		try {
			other = createWorldBook(false);
			HttpServletRequestMock o = requestAs(owner);
			assertNotEquals("two distinct worlds", oid(f.world), oid(other.world));

			Map<String, Object> foreignDto = createLocation(owner, other.bookOid, "The Salt Chapel", "A chapel of white salt blocks", "exterior", null);
			String foreignOid = String.valueOf(foreignDto.get("objectId"));
			BaseRecord foreign = locationRecord(foreignOid);
			Map<String, Object> foreignVarDto = ok(service.createLocationVariant(other.bookOid, foreignOid, json("timeOfDay", "night"), o), "foreign variant");
			BaseRecord foreignVar = variantRecord(String.valueOf(foreignVarDto.get("objectId")));
			assertTrue("the owner can read the other world's location, so the writer lets the FK through", PbLocationUtil.readLocation(owner, foreignOid, orgId) != null);

			BaseRecord crossWorld = addScene(f, 1);
			pointScene(crossWorld, foreign, null);
			BaseRecord crossWorldVariant = addScene(f, 2);
			pointScene(crossWorldVariant, foreign, foreignVar);

			String refused = "Location The Salt Chapel is not in this book's world; setting described in text";
			Map<String, Object> dto = planOf(f, crossWorld);
			Map<String, Object> location = map(dto, "location");
			assertEquals("the pointer is still reported", foreignOid, location.get("locationId"));
			assertNull("no hash for a refused location: " + location, location.get("revisionHash"));
			assertNull("no render for a refused location: " + location, location.get("renderArtifactId"));
			assertEquals("no text composed from a refused location", SCENE_SETTING, location.get("description"));
			assertFalse("a null location input is absent on the wire: " + dto.get("inputs"), map(dto, "inputs").containsKey("location"));
			assertTheOneLocationDegradation(dto, refused);
			assertFalse("the refused description is not compiled", basePrompt(dto).contains("white salt"));

			Map<String, Object> vdto = planOf(f, crossWorldVariant);
			Map<String, Object> vloc = map(vdto, "location");
			assertEquals(foreignOid, vloc.get("locationId"));
			assertEquals("the variant pointer is still reported", oid(foreignVar), vloc.get("variantId"));
			assertNull(vloc.get("revisionHash"));
			assertNull(vloc.get("renderArtifactId"));
			assertEquals(SCENE_SETTING, vloc.get("description"));
			assertTrue("both inputs null, so absent on the wire: " + vdto.get("inputs"), map(vdto, "inputs").isEmpty());
			assertTheOneLocationDegradation(vdto, refused);

			/// a variant of another location of the SAME world
			Map<String, Object> aDto = createLocation(owner, f.bookOid, "The Drowned Anchor", TAVERN_DESCRIPTION, "interior", null);
			BaseRecord locA = locationRecord(String.valueOf(aDto.get("objectId")));
			Map<String, Object> bDto = createLocation(owner, f.bookOid, "The Rope Walk", "A long shed where rope is laid", "exterior", null);
			String locBOid = String.valueOf(bDto.get("objectId"));
			Map<String, Object> vBDto = ok(service.createLocationVariant(f.bookOid, locBOid, json("timeOfDay", "night"), o), "variant of B");
			BaseRecord vB = variantRecord(String.valueOf(vBDto.get("objectId")));
			assertEquals("The Rope Walk - night", vBDto.get("name"));

			BaseRecord mismatched = addScene(f, 3);
			pointScene(mismatched, locA, vB);
			Map<String, Object> mdto = planOf(f, mismatched);
			Map<String, Object> mloc = map(mdto, "location");
			assertEquals(oid(locA), mloc.get("locationId"));
			assertEquals(oid(vB), mloc.get("variantId"));
			String aHash = String.valueOf(mloc.get("revisionHash"));
			assertTrue("the location keeps its hash: " + mloc, mloc.get("revisionHash") != null && !aHash.isEmpty());
			assertNull("no render is carried for a refused variant: " + mloc, mloc.get("renderArtifactId"));
			assertEquals("the location's text without the foreign variant's qualifiers", TAVERN_DESCRIPTION + ". " + SCENE_SETTING, mloc.get("description"));
			Map<String, Object> minputs = map(mdto, "inputs");
			assertEquals(aHash, minputs.get("location"));
			assertFalse("the null locationVariant input is absent on the wire: " + minputs, minputs.containsKey("locationVariant"));
			assertTheOneLocationDegradation(mdto, "Variant The Rope Walk - night is not a variant of location The Drowned Anchor; setting described in text");
			assertFalse("night is not compiled", basePrompt(mdto).contains("night"));
		}
		finally {
			cleanup(f);
			cleanup(other);
		}
	}

	// ─────────────────────────────── P3-7: PUT /{book}/scene/{scene}/location ───────────────────────────────

	/// The one pointing path the Ux uses (design §24 P3-6, §26), on a page of the world book that has never been
	/// rendered (no sceneNode, so nodeStale:false / pinned:false are on the wire and nodeStatus is absent). The
	/// route's own 400s come before the facade (body shape, id types); the facade's 404s cover the scene outside
	/// the book, a location of ANOTHER world (D4 - the owner can read it, only the same-world check refuses it),
	/// an unknown location / variant, and a variant under a different location than the one named. Every success
	/// is re-read fresh as the owner through PbBookUtil.readScene; every refusal is proven to leave the page as
	/// it was. Staleness on a node-bearing page is Objects7's TestPbSceneLocationPointer (case 9), not H2's.
	@Test
	public void TestSetSceneLocationRoute() throws Exception {
		Fixture f = createWorldBook(false);
		Fixture other = null;
		try {
			other = createWorldBook(false);
			HttpServletRequestMock o = requestAs(owner);
			assertNotEquals("two distinct worlds", oid(f.world), oid(other.world));
			BaseRecord page = addScene(f, 1);
			String pageOid = oid(page);
			BaseRecord foreignPage = addScene(other, 1);
			String foreignPageOid = oid(foreignPage);
			assertNotEquals(pageOid, foreignPageOid);

			Map<String, Object> aDto = createLocation(owner, f.bookOid, "The Drowned Anchor", TAVERN_DESCRIPTION, "interior", null);
			String locA = String.valueOf(aDto.get("objectId"));
			Map<String, Object> bDto = createLocation(owner, f.bookOid, "The Rope Walk", "A long shed where rope is laid", "exterior", null);
			String locB = String.valueOf(bDto.get("objectId"));
			assertNotEquals(locA, locB);
			Map<String, Object> vBDto = ok(service.createLocationVariant(f.bookOid, locB, json("timeOfDay", "night"), o), "variant of B");
			String varB = String.valueOf(vBDto.get("objectId"));
			assertEquals("The Rope Walk - night", vBDto.get("name"));
			Map<String, Object> foreignDto = createLocation(owner, other.bookOid, "The Salt Chapel", "A chapel of white salt blocks", "exterior", null);
			String foreignOid = String.valueOf(foreignDto.get("objectId"));
			assertNotNull("the owner can read the other world's location; only the facade's same-world check refuses it",
				PbLocationUtil.readLocation(owner, foreignOid, orgId));
			assertPointer(pageOid, null, null, "a fresh page");

			/// route-level 400s: the body is checked before the facade is reached
			refused(service.setSceneLocation(f.bookOid, pageOid, "not json", o), 400, "Malformed request body", "malformed body");
			refused(service.setSceneLocation(f.bookOid, pageOid, "[1,2]", o), 400, "Malformed request body", "array body");
			refused(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", Integer.valueOf(5)), o), 400, "locationObjectId and variantObjectId must be strings when present", "numeric locationObjectId");
			refused(service.setSceneLocation(f.bookOid, pageOid, json("variantObjectId", Boolean.TRUE), o), 400, "locationObjectId and variantObjectId must be strings when present", "boolean variantObjectId");
			refused(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", locA, "variantObjectId", Map.of("objectId", varB)), o), 400, "locationObjectId and variantObjectId must be strings when present", "object variantObjectId");

			/// facade 404s: the scene, then the location / variant, must be the book's
			refused(service.setSceneLocation(f.bookOid, NIL_UUID, json("locationObjectId", locA), o), 404, "Scene " + NIL_UUID + " not found in this book", "unknown scene");
			refused(service.setSceneLocation(f.bookOid, foreignPageOid, json("locationObjectId", locA), o), 404, "Scene " + foreignPageOid + " not found in this book", "page of another book");
			refused(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", foreignOid), o), 404, "Location not found in this book's world", "location of another world");
			refused(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", NIL_UUID), o), 404, "Location not found in this book's world", "unknown location");
			refused(service.setSceneLocation(f.bookOid, pageOid, json("variantObjectId", NIL_UUID), o), 404, "Location variant not found in this book's world", "unknown variant");
			refused(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", locA, "variantObjectId", varB), o), 404, "Location variant not found under that location", "variant under the wrong location");
			assertPointer(pageOid, null, null, "after every refusal");
			assertPointer(foreignPageOid, null, null, "the other book's page");

			/// 200: point at a location
			Map<String, Object> pointed = ok(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", locA), o), "point at A");
			assertEquals(Boolean.TRUE, pointed.get("updated"));
			assertEquals(pageOid, pointed.get("sceneObjectId"));
			assertEquals(locA, pointed.get("locationObjectId"));
			assertEquals("The Drowned Anchor", pointed.get("locationName"));
			assertNull("no variant named: " + pointed, pointed.get("variantObjectId"));
			assertNull("no variant name: " + pointed, pointed.get("variantName"));
			assertEquals("a page with no node is not stale", Boolean.FALSE, pointed.get("nodeStale"));
			assertEquals(Boolean.FALSE, pointed.get("pinned"));
			assertNull("no node, no status (absent under NON_EMPTY): " + pointed, pointed.get("nodeStatus"));
			assertPointer(pageOid, locA, null, "after the point at A");
			/// and the library sees the page under A (usedByScenes is computed from the scenes' location FK)
			Map<String, Object> aListed = locationNamed(ok(service.listLocations(f.bookOid, o), "owner list after the point"), "The Drowned Anchor");
			assertNotNull("The Drowned Anchor is in the library", aListed);
			List<Object> usedA = list(aListed, "usedByScenes");
			assertEquals("exactly the one page uses A: " + usedA, 1, usedA.size());
			assertTrue(usedA.get(0) instanceof Map);
			@SuppressWarnings("unchecked")
			Map<String, Object> usedA0 = (Map<String, Object>) usedA.get(0);
			assertEquals(pageOid, usedA0.get("sceneObjectId"));
			assertNull("no variant on that use: " + usedA0, usedA0.get("variantObjectId"));

			/// 200: the variant alone resolves its parent
			Map<String, Object> viaVariant = ok(service.setSceneLocation(f.bookOid, pageOid, json("variantObjectId", varB), o), "point at B's variant");
			assertEquals(Boolean.TRUE, viaVariant.get("updated"));
			assertEquals("the parent location is resolved from the variant", locB, viaVariant.get("locationObjectId"));
			assertEquals("The Rope Walk", viaVariant.get("locationName"));
			assertEquals(varB, viaVariant.get("variantObjectId"));
			assertEquals("The Rope Walk - night", viaVariant.get("variantName"));
			assertPointer(pageOid, locB, varB, "after the point at B's variant");

			/// naming the matching pair is the same result
			Map<String, Object> both = ok(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", locB, "variantObjectId", varB), o), "point at B + variant");
			assertEquals(locB, both.get("locationObjectId"));
			assertEquals(varB, both.get("variantObjectId"));
			assertPointer(pageOid, locB, varB, "after the point at B + variant");

			/// a refusal on a pointed page leaves the pointer alone
			refused(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", locA, "variantObjectId", varB), o), 404, "Location variant not found under that location", "mismatch on a pointed page");
			refused(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", foreignOid), o), 404, "Location not found in this book's world", "foreign location on a pointed page");
			assertPointer(pageOid, locB, varB, "after the refusals on a pointed page");

			/// 200: "{}" clears both pointers
			Map<String, Object> cleared = ok(service.setSceneLocation(f.bookOid, pageOid, "{}", o), "clear");
			assertEquals(Boolean.TRUE, cleared.get("updated"));
			assertEquals(pageOid, cleared.get("sceneObjectId"));
			assertNull("cleared location: " + cleared, cleared.get("locationObjectId"));
			assertNull("cleared location name: " + cleared, cleared.get("locationName"));
			assertNull("cleared variant: " + cleared, cleared.get("variantObjectId"));
			assertNull("cleared variant name: " + cleared, cleared.get("variantName"));
			assertEquals(Boolean.FALSE, cleared.get("nodeStale"));
			assertPointer(pageOid, null, null, "after the clear");

			/// a blank body and explicit nulls clear the same way
			ok(service.setSceneLocation(f.bookOid, pageOid, json("locationObjectId", locA), o), "re-point at A");
			assertPointer(pageOid, locA, null, "re-pointed at A");
			Map<String, Object> blankCleared = ok(service.setSceneLocation(f.bookOid, pageOid, "", o), "clear with a blank body");
			assertNull("blank body clears: " + blankCleared, blankCleared.get("locationObjectId"));
			assertPointer(pageOid, null, null, "after the blank-body clear");
			ok(service.setSceneLocation(f.bookOid, pageOid, json("variantObjectId", varB), o), "re-point at B's variant");
			assertPointer(pageOid, locB, varB, "re-pointed at B's variant");
			Map<String, Object> nullCleared = ok(service.setSceneLocation(f.bookOid, pageOid, "{\"locationObjectId\":null,\"variantObjectId\":null}", o), "clear with explicit nulls");
			assertNull("explicit nulls clear: " + nullCleared, nullCleared.get("locationObjectId"));
			assertNull("explicit nulls clear the variant: " + nullCleared, nullCleared.get("variantObjectId"));
			assertPointer(pageOid, null, null, "after the explicit-null clear");

			/// and the library lists the page under nothing now
			Map<String, Object> listed = ok(service.listLocations(f.bookOid, o), "owner list after the clears");
			Map<String, Object> aAfter = locationNamed(listed, "The Drowned Anchor");
			Map<String, Object> bAfter = locationNamed(listed, "The Rope Walk");
			assertNotNull("The Drowned Anchor is still in the library", aAfter);
			assertNotNull("The Rope Walk is still in the library", bAfter);
			assertTrue("no scene uses A after the clear: " + aAfter, list(aAfter, "usedByScenes").isEmpty());
			assertTrue("no scene uses B after the clear: " + bAfter, list(bAfter, "usedByScenes").isEmpty());
		}
		finally {
			cleanup(f);
			cleanup(other);
		}
	}
}
