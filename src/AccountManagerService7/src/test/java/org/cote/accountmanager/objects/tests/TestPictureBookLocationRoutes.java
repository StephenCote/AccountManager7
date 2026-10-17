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
	}

	/// A user with no grant on the book cannot tell it exists: 404 "Book not found" on all thirteen routes, the
	/// location / proposal ids never being consulted.
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

			/// and nothing changed
			Map<String, Object> after = ok(service.getLocation(f.bookOid, locOid, o), "owner get after reader attempts");
			assertEquals("The spiral stair of the lighthouse", after.get("description"));
			assertEquals(Boolean.FALSE, after.get("locked"));
			assertTrue("no variant was added", list(after, "variants").isEmpty());
			assertEquals("still exactly one location", 1, list(ok(service.listLocations(f.bookOid, o), "owner list"), "locations").size());
		}
		finally {
			cleanup(f);
		}
	}
}
