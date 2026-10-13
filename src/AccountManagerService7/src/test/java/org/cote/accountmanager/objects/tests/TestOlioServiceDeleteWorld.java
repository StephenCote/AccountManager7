package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import org.cote.rest.services.OlioService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// PF-1 (§9.1 / §6.C 143a): DELETE /rest/olio/world/{id} on a PictureBook world must answer deleted:true
/// and leave no world row behind. The old route ran PictureBookUtil.reset (which tears the book world
/// down itself) and then handed the already-deleted, stale world record to WorldUtil.deleteWorld, whose
/// delete of a gone row returned false - so the UI reported "Failed to delete world" for a world that
/// was in fact gone.
///
/// The fix re-finds the world after reset and answers deleted:true only when it is gone for BOTH the
/// olio principal and the request user. The user-only re-find is not enough: reset's step 2 removes
/// the user's per-book Writer role, so the user goes blind to an olio-owned world whether or not step 3
/// actually removed it. That is why this fixture is shaped like production - the world and the book are
/// OWNED BY THE OLIO PRINCIPAL and the test user reaches them only through the Writer role. A world the
/// user owned would make the misplaced case pass against a user-only re-find and prove nothing.
///
/// Exercised through the real OlioService method -> PictureBookUtil.reset -> PbDeleteUtil ->
/// WorldUtil, against the real (H2) database. Nothing is mocked; assertions on "gone" read the raw rows
/// through ISearch with the cache off, so a cached or PBAC-hidden row cannot pass as deleted.
public class TestOlioServiceDeleteWorld extends BaseTest {

	private static final String USER_NAME = "deleteworlduser";
	private static final String[] DATA_AND_GROUP = new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() };

	private final OlioService service = new OlioService();
	private final OlioContextConfiguration olioCfg = new OlioContextConfiguration();

	private BaseRecord user;
	private BaseRecord olioUser;
	private BaseRecord admin;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		admin = orgContext.getAdminUser();
		user = getCreateUser(USER_NAME);
		assertNotNull("Test user is null", user);
		/// Same call PbOlioContextUtil.newBookConfiguration makes; admin is used for the fixture only.
		olioUser = ioContext.getFactory().getCreateUser(admin, OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("Olio principal is null", olioUser);
		bootstrapOlioRoot();
		injectContext(service, emptyContext());
	}

	/// The once-per-organization part of OlioContext.configureEnvironment (:798-810): the org admin
	/// creates /Olio and grants the olio principal Read/Update/Create on it; only then can the olio
	/// principal create /Olio/Universes and everything below. Every world in the product is created
	/// by the olio principal under that tree (OlioContext.initialize, WorldUtil), never by a user.
	private void bootstrapOlioRoot() {
		BaseRecord rootDir = ioContext.getPathUtil().makePath(admin, ModelNames.MODEL_GROUP, olioCfg.getBasePath(), "DATA", orgId);
		assertNotNull("Olio root group is null: " + olioCfg.getBasePath(), rootDir);
		ioContext.getAuthorizationUtil().setEntitlement(admin, olioUser, new BaseRecord[] { rootDir },
			new String[] { "Read", "Update", "Create" }, DATA_AND_GROUP);
		BaseRecord uDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, olioCfg.getUniversePath(), "DATA", orgId);
		assertNotNull("Universe group is null: " + olioCfg.getUniversePath(), uDir);
	}

	/// Deliberately NOT the Books universe: teardownBookFootprintAsOlio only resolves the world under
	/// PbOlioContextUtil.bookWorldPath(), so a world here survives reset and the route must wipe it itself.
	private String misplacedWorldsPath() {
		return olioCfg.getUniversePath() + "/PfOneMisplaced/Worlds";
	}

	private String plainWorldsPath() {
		return olioCfg.getUniversePath() + "/PfOnePlain/Worlds";
	}

	private static void injectContext(OlioService svc, ServletContext ctx) {
		try {
			Field f = OlioService.class.getDeclaredField("context");
			f.setAccessible(true);
			f.set(svc, ctx);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("OlioService.context field not injectable", e);
		}
	}

	private static ServletContext emptyContext() {
		return (ServletContext) Proxy.newProxyInstance(
			TestOlioServiceDeleteWorld.class.getClassLoader(),
			new Class<?>[] { ServletContext.class },
			(proxy, method, args) -> {
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

	/// {@code /Olio/Universes/Books/Worlds} - derived from the public container path because
	/// PbOlioContextUtil.bookWorldPath() is package-private.
	private static String booksWorldsPath() {
		String probe = "probe";
		String container = PbBookUtil.bookContainerPath(probe);
		assertTrue("bookContainerPath must end with the slug: " + container, container.endsWith("/" + probe));
		return container.substring(0, container.length() - probe.length() - 1);
	}

	private static String newSlug() {
		return "pf1-" + UUID.randomUUID().toString().substring(0, 12);
	}

	/// A world owned by the olio principal at {@code worldsPath/slug}, built through the production
	/// WorldUtil.getCreateWorld path (container group + the WorldFactory sub-groups).
	private BaseRecord createOlioWorld(String worldsPath, String slug) {
		BaseRecord world = WorldUtil.getCreateWorld(olioUser, worldsPath, slug, new String[0]);
		assertNotNull("Failed to create the olio-owned world fixture at " + worldsPath + "/" + slug, world);
		assertNotNull("World fixture carries no objectId", world.get(FieldNames.FIELD_OBJECT_ID));
		return world;
	}

	/// The PictureBook half of the fixture, shaped like PbOlioContextUtil.newBookConfiguration +
	/// OlioContext.configureWorldAuthorization: an olio-owned olio.pb.book whose slug is the world name,
	/// in {container}/Book; a Writer role at PbOlioContextUtil.writerRolePath(slug) with the test user
	/// enrolled; CRUD on the Book group and Read on the world record granted to that role by the org admin.
	private BaseRecord createOlioBookForWorld(BaseRecord world, String slug) throws Exception {
		String bookGroupPath = PbBookUtil.bookGroupPath(slug);
		BaseRecord bookDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, bookGroupPath, "DATA", orgId);
		assertNotNull("Book group is null: " + bookGroupPath, bookDir);

		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(olioUser, book, slug, bookGroupPath, orgId);
		book.set(FieldNames.FIELD_NAME, slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
		book.set(OlioFieldNames.FIELD_PB_CREATED_BY_OBJECT_ID, user.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(olioUser, book);
		assertNotNull("Failed to create the olio-owned book fixture", created);

		BaseRecord writerRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.writerRolePath(slug), RoleEnumType.USER.toString(), orgId);
		assertNotNull("Writer role is null", writerRole);
		assertTrue("Failed to enrol the test user in the Writer role",
			ioContext.getMemberUtil().member(olioUser, writerRole, user, null, true));

		String[] crud = new String[] { "Read", "Update", "Create", "Delete" };
		ioContext.getAuthorizationUtil().setEntitlement(admin, writerRole, new BaseRecord[] { bookDir }, crud, DATA_AND_GROUP);
		ioContext.getAuthorizationUtil().setEntitlement(admin, writerRole, world, new String[] { "Read" }, PermissionEnumType.DATA.toString());
		return created;
	}

	/// A non-book world is read by users through a role carrying Read on the world record (what
	/// OlioContext.configureWorldAuthorization grants the Olio User role); this is that grant for the
	/// test user alone, on a role that is not a book role and so is untouched by reset.
	private void grantReadThroughRole(BaseRecord world, String slug) {
		BaseRecord readerRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE,
			"~/Roles/Olio/PfOne/" + slug + "/Reader", RoleEnumType.USER.toString(), orgId);
		assertNotNull("Reader role is null", readerRole);
		assertTrue("Failed to enrol the test user in the Reader role",
			ioContext.getMemberUtil().member(olioUser, readerRole, user, null, true));
		ioContext.getAuthorizationUtil().setEntitlement(admin, readerRole, world, new String[] { "Read" }, PermissionEnumType.DATA.toString());
	}

	/// Raw row read: ISearch, cache off, no PBAC - the only read that can tell "deleted" from "hidden".
	private BaseRecord rawRow(String model, String objectId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private BaseRecord findAs(BaseRecord principal, String model, String objectId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(principal, q);
	}

	/// ------------------------------------------------------------------------------------------

	/// The PF-1 symptom. Book world in the Books universe: reset's step 3 removes the world itself, so
	/// the route must recognise "already gone" as success. Before the fix this answered deleted:false
	/// while the row was gone.
	@Test
	public void TestBookWorldInBooksUniverseIsReportedDeleted() throws Exception {
		String slug = newSlug();
		BaseRecord world = createOlioWorld(booksWorldsPath(), slug);
		String worldOid = world.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord book = createOlioBookForWorld(world, slug);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);

		/// Premise: the user reaches the olio-owned world only through the Writer role.
		assertNotNull("Premise: the enrolled user must be able to read the world", findAs(user, OlioModelNames.MODEL_WORLD, worldOid));
		assertNotNull("Premise: the enrolled user must be able to read the book", findAs(user, OlioModelNames.MODEL_PB_BOOK, bookOid));

		Response r = service.deleteWorld(worldOid, requestAs(user));
		assertEquals("deleteWorld must answer 200: " + r.getEntity(), 200, r.getStatus());
		Map<String, Object> b = body(r);
		assertEquals("deleteWorld must report deleted:true for a book world (PF-1): " + b, Boolean.TRUE, b.get("deleted"));

		assertNull("World row must be gone from the database", rawRow(OlioModelNames.MODEL_WORLD, worldOid));
		assertNull("Book row must be gone from the database", rawRow(OlioModelNames.MODEL_PB_BOOK, bookOid));
		assertNull("Olio principal must no longer see the world", findAs(olioUser, OlioModelNames.MODEL_WORLD, worldOid));
	}

	/// The discriminating case for the both-principals re-find. The world backs a book but sits outside
	/// the Books universe, so reset deletes the book, its groups and the Writer role but NOT the world.
	/// After reset the request user is blind to the world (role gone) while the olio principal still sees
	/// it - a user-only re-find would answer deleted:true and leave the row. The route must fall through
	/// to WorldUtil.deleteWorld as the olio principal and actually remove it.
	@Test
	public void TestBookWorldOutsideBooksUniverseIsActuallyDeleted() throws Exception {
		String slug = newSlug();
		BaseRecord world = createOlioWorld(misplacedWorldsPath(), slug);
		String worldOid = world.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord book = createOlioBookForWorld(world, slug);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);

		assertNotNull("Premise: the enrolled user must be able to read the world", findAs(user, OlioModelNames.MODEL_WORLD, worldOid));

		Response r = service.deleteWorld(worldOid, requestAs(user));
		assertEquals("deleteWorld must answer 200: " + r.getEntity(), 200, r.getStatus());
		Map<String, Object> b = body(r);
		assertEquals("deleteWorld must report deleted:true: " + b, Boolean.TRUE, b.get("deleted"));

		assertNull("World row must be gone from the database - a user-only re-find would have left it", rawRow(OlioModelNames.MODEL_WORLD, worldOid));
		assertNull("Book row must be gone from the database", rawRow(OlioModelNames.MODEL_PB_BOOK, bookOid));
		assertNull("Olio principal must no longer see the world", findAs(olioUser, OlioModelNames.MODEL_WORLD, worldOid));
	}

	/// Regression guard for the non-book path: an olio-owned world with no olio.pb.book (a grid/arena
	/// universe world) takes the route straight to WorldUtil.deleteWorld as the olio principal and must
	/// still answer deleted:true with the row gone.
	@Test
	public void TestPlainOlioWorldIsDeleted() throws Exception {
		String slug = newSlug();
		BaseRecord world = createOlioWorld(plainWorldsPath(), slug);
		String worldOid = world.get(FieldNames.FIELD_OBJECT_ID);
		grantReadThroughRole(world, slug);
		assertNotNull("Premise: the enrolled user must be able to read the world", findAs(user, OlioModelNames.MODEL_WORLD, worldOid));

		Response r = service.deleteWorld(worldOid, requestAs(user));
		assertEquals("deleteWorld must answer 200: " + r.getEntity(), 200, r.getStatus());
		Map<String, Object> b = body(r);
		assertEquals("deleteWorld must report deleted:true: " + b, Boolean.TRUE, b.get("deleted"));
		assertNull("World row must be gone from the database", rawRow(OlioModelNames.MODEL_WORLD, worldOid));
	}

	/// A world that does not exist is 404, not a false success.
	@Test
	public void TestUnknownWorldIs404() throws Exception {
		Response r = service.deleteWorld("00000000-0000-0000-0000-000000000000", requestAs(user));
		assertEquals("Unknown world must be 404", 404, r.getStatus());
	}
}
