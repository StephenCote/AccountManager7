package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
import org.cote.accountmanager.olio.picturebook.BookContext;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
import org.cote.accountmanager.olio.picturebook.PbWardrobeUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
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

/// P4-5 (PictureBook V3 §6, §6.1, §6.2): the seven wardrobe routes on PictureBookService exercised through the real
/// service methods -> PbServiceFacade -> PbWardrobeUtil / PbComposeUtil -> real AccessPoint/PBAC -> the real (H2)
/// database, with HttpServletRequestMock + UserPrincipal exactly as TestPictureBookComposeRoutes does. Nothing is
/// mocked; no asserting actor is ever the admin or the olio principal.
///
///   GET  /{book}/character/{char}/wardrobe                       listWardrobe             200 {entries[], entryCount, defaultApparelObjectId, ...}
///   POST /{book}/character/{char}/wardrobe                       createWardrobeAlternate  201 | 200 reused (LLM; only its refusals are driven here)
///   PUT  /{book}/character/{char}/wardrobe/{apparel}/default     setDefaultApparel        200 {changed, previousApparelObjectIds, scenesScanned, staled}
///   POST /{book}/character/{char}/wardrobe/{apparel}/reference   requestApparelReference  202 job (SD; only its refusals are driven here)
///   PUT  /{book}/composition/{comp}/actor/{actor}/apparel        pinActorApparel          200 {pinned, apparelObjectId, affectedSceneIndexes, staled}
///   GET  /{book}/composition/{comp}/outfits                      compositionOutfits       200 {actors[{source, label, fromSceneIndex, ...}]}
///   POST /{book}/wardrobe/migrate-scene-tags                     migrateSceneTags         200 {scanned, pinned, skipped*, ambiguous}
///
/// NO LLM, NO SD. The alternate route is driven only to its 400 (blank description), 403 (reader) and 404 (named
/// chat config) refusals, every one of which the facade/util raises before the LLM is consulted; the reference route
/// only to its 403/404/400/500 refusals, which are raised before a job is claimed - and the registry is checked
/// afterwards for a `pb.apparelReference` job to prove it. The live LLM alternate and the live Swarm reference
/// render are TestPbWardrobe in Objects7 against the live stack.
///
/// FIXTURE. The same hand-built H2 world fixture as TestPictureBookComposeRoutes (own user names, slug prefix) plus:
/// characters created AS THE OWNER (Writer) in the world's Population group, hand-built olio.apparel rows created as
/// the owner in the world's Apparel group and linked into the character's store through MemberUtil (no OlioContext,
/// no catalog, no LLM), scenes with the character on the roster, and a MIGRATED composition per scene from
/// PbComposeUtil.composeMigrated (public; no LLM) so each scene has a real sceneActor row for the character.
/// The H2 scenes have no render node, so every `staled` is 0 on this harness - asserted as such, never skipped.
///
/// WIRE NOTE (measured, not a defect under test): JSONUtil.exportObject serialises with Include.NON_EMPTY, so a null
/// `apparelObjectId` after an unpin and an empty `warnings` are ABSENT from a body; booleans (`pinned:false`,
/// `changed:false`) and ints (`staled:0`) ARE present. Timings are logged, never asserted.
public class TestPictureBookWardrobeRoutes extends BaseTest {

	private static final String OWNER_NAME = "pbwardrobeowner";
	private static final String READER_NAME = "pbwardrobereader";
	private static final String STRANGER_NAME = "pbwardrobestranger";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String[] DATA_AND_GROUP = new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() };
	private static final String[] CRUD = new String[] { "Read", "Update", "Create", "Delete" };
	private static final String[] READ = new String[] { "Read" };
	/// An SD context that resolves but whose server is unreachable - nothing here renders (the reference route is
	/// refused before a job is claimed in every case below).
	private static final ServletContext SD_CONTEXT = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", "http://127.0.0.1:1"));
	/// No SD init-params at all: the reference route must be refused 500 before anything is queued.
	private static final ServletContext NO_SD_CONTEXT = initParams(Map.of());
	private static final String SCENE_SETTING = "Lanterns swing over the quay as the tide turns";
	private static final String MISSING_CONFIG = "no-such-config-p45";
	private static final String REFERENCE_KIND = "pb.apparelReference";

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
		BaseRecord popGroup;
		String popPath;
		BaseRecord apparelGroup;
	}

	/// One character of the fixture book as the owner reads it through the wardrobe gate (with its store).
	private static final class Cast {
		String name;
		String oid;
		long id;
		BaseRecord cp;
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
		long t0 = System.currentTimeMillis();
		Fixture f = new Fixture();
		f.slug = "p45r-" + UUID.randomUUID().toString().substring(0, 12);
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
		logger.info("fixture " + f.slug + " built in " + (System.currentTimeMillis() - t0) + " ms");
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

	/// The world's Population and Apparel groups as the owner opens the book context (the same resolution the
	/// wardrobe gate uses to decide a character belongs to the book).
	private void openGroups(Fixture f) {
		if(f.bctx != null) {
			return;
		}
		BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
		assertNotNull("the world book must read back as the owner", book);
		f.bctx = PbBookUtil.openBookContext(owner, book);
		assertNotNull("book context", f.bctx);
		f.popGroup = f.bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
		f.popPath = f.bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
		f.apparelGroup = f.bctx.getGroup(OlioFieldNames.FIELD_APPAREL);
		assertNotNull("population group", f.popGroup);
		assertNotNull("population path", f.popPath);
		assertNotNull("apparel group", f.apparelGroup);
	}

	private static final String[] CHAR_SUB_RECORD_FIELDS = new String[] {
		FieldNames.FIELD_PROFILE, OlioFieldNames.FIELD_STATISTICS, FieldNames.FIELD_STORE, OlioFieldNames.FIELD_INSTINCT,
		FieldNames.FIELD_BEHAVIOR, FieldNames.FIELD_PERSONALITY, FieldNames.FIELD_STATE
	};

	/// A character of the book, created AS THE OWNER in the world's Population group (TestPbWardrobe's shape), then
	/// read back through the real wardrobe gate so the Cast carries the store the routes will consult.
	private Cast addCharacter(Fixture f, String label) throws Exception {
		openGroups(f);
		long t0 = System.currentTimeMillis();
		Cast c = new Cast();
		c.name = label + " " + UUID.randomUUID().toString().substring(0, 8);
		long groupId = ((Number) f.popGroup.get(FieldNames.FIELD_ID)).longValue();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, f.popPath);
		plist.parameter(FieldNames.FIELD_NAME, c.name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, owner, null, plist);
		cp.set(FieldNames.FIELD_NAME, c.name);
		cp.set(FieldNames.FIELD_FIRST_NAME, label);
		cp.set(FieldNames.FIELD_GENDER, "female");
		for(String sf : CHAR_SUB_RECORD_FIELDS) {
			BaseRecord sub = cp.get(sf);
			if(sub != null) {
				sub.set(FieldNames.FIELD_GROUP_ID, groupId);
			}
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, cp);
		assertNotNull("create character " + c.name + " as the owner", created);
		c.oid = oid(created);
		assertNotNull("character carries no objectId", c.oid);
		BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
		c.cp = PbWardrobeUtil.requireCharacterOfBook(owner, book, c.oid);
		assertNotNull("the character passes the wardrobe gate as the owner", c.cp);
		c.id = ((Number) c.cp.get(FieldNames.FIELD_ID)).longValue();
		c.store = c.cp.get(FieldNames.FIELD_STORE);
		assertNotNull("character " + c.name + " has a store", c.store);
		logger.info("character " + c.name + " #" + c.id + " created in " + (System.currentTimeMillis() - t0) + " ms");
		return c;
	}

	/// A hand-built apparel row created AS THE OWNER in the world's Apparel group and linked into the character's
	/// store (the membership write is the same MemberUtil call PbWardrobeUtil.createAlternate makes as the caller).
	private String addApparel(Fixture f, Cast c, String name, boolean inUse) throws Exception {
		openGroups(f);
		BaseRecord apparel = RecordFactory.newInstance(OlioModelNames.MODEL_APPAREL);
		long apparelGroupId = ((Number) f.apparelGroup.get(FieldNames.FIELD_ID)).longValue();
		apparel.set(FieldNames.FIELD_GROUP_ID, apparelGroupId);
		ioContext.getRecordUtil().applyOwnership(owner, apparel, orgId);
		apparel.set(FieldNames.FIELD_NAME, name + " " + UUID.randomUUID().toString().substring(0, 8));
		apparel.set(FieldNames.FIELD_DESCRIPTION, name + " of " + c.name);
		apparel.set(OlioFieldNames.FIELD_IN_USE, inUse);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(owner, apparel);
		assertNotNull("create apparel " + name + " as the owner", created);
		apparel.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		apparel.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		assertTrue("apparel " + name + " linked into the store of " + c.name,
			ioContext.getMemberUtil().member(owner, c.store, OlioFieldNames.FIELD_APPAREL, apparel, null, true));
		return oid(created);
	}

	/// A page of the world book created as the owner (Writer) with its setting patched in (TestPictureBookComposeRoutes.addScene).
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

	/// Puts the character on the scene's roster (scene.characters) as the owner.
	private void enroll(BaseRecord scene, Cast c) {
		assertTrue("character " + c.name + " on the roster of scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX),
			ioContext.getMemberUtil().member(owner, scene, OlioFieldNames.FIELD_PB_CHARACTERS, c.cp, null, true));
	}

	/// A MIGRATED composition of the scene from its own fields and roster (no LLM), written as the owner.
	private BaseRecord compose(Fixture f, BaseRecord scene) {
		long t0 = System.currentTimeMillis();
		BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
		ComposeResult r = PbComposeUtil.composeMigrated(owner, book, scene, false);
		logger.info("composeMigrated scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX) + ": " + (System.currentTimeMillis() - t0)
			+ " ms written=" + r.isWritten() + " actors=" + r.actors.size() + " tier=" + r.castTier);
		assertTrue("the MIGRATED composition is written", r.isWritten());
		assertNotNull("composition", r.composition);
		assertNotNull("composition objectId", oid(r.composition));
		return r.composition;
	}

	/// The composition's sceneActor row for the character, by the row's character FK.
	private String actorOf(BaseRecord comp, Cast c) {
		for(BaseRecord row : PbComposeUtil.listActors(owner, comp)) {
			if(fkId(row, OlioFieldNames.FIELD_PB_CHARACTER) == c.id) {
				String aoid = oid(row);
				assertNotNull("actor row carries no objectId", aoid);
				return aoid;
			}
		}
		fail("no actor for character " + c.name + " #" + c.id + " in composition " + oid(comp));
		return null;
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPictureBookWardrobeRoutes.class.getClassLoader(),
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

	private static long fkId(BaseRecord rec, String field) {
		if(rec == null || !rec.hasField(field)) {
			return 0L;
		}
		Object o = rec.get(field);
		if(o instanceof BaseRecord) {
			Object id = ((BaseRecord) o).get(FieldNames.FIELD_ID);
			return (id instanceof Number ? ((Number) id).longValue() : 0L);
		}
		return (o instanceof Number ? ((Number) o).longValue() : 0L);
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

	private static List<Integer> ints(List<Object> in) {
		List<Integer> out = new ArrayList<>();
		for(Object o : in) {
			out.add(((Number) o).intValue());
		}
		return out;
	}

	/// The wardrobe entry for an apparel objectId in a GET wardrobe body.
	@SuppressWarnings("unchecked")
	private Map<String, Object> entryOf(Map<String, Object> wardrobe, String apparelOid) {
		for(Object o : list(wardrobe, "entries")) {
			Map<String, Object> e = (Map<String, Object>) o;
			if(apparelOid.equals(e.get("objectId"))) {
				return e;
			}
		}
		fail("no wardrobe entry for apparel " + apparelOid + " in " + wardrobe);
		return null;
	}

	/// The resolved-outfit row for an actor objectId in a GET outfits body.
	@SuppressWarnings("unchecked")
	private Map<String, Object> outfitOf(Map<String, Object> outfits, String actorOid) {
		for(Object o : list(outfits, "actors")) {
			Map<String, Object> a = (Map<String, Object>) o;
			if(actorOid.equals(a.get("actorObjectId"))) {
				return a;
			}
		}
		fail("no resolved outfit for actor " + actorOid + " in " + outfits);
		return null;
	}

	private Map<String, Object> outfits(Fixture f, String compOid, String what) {
		long t0 = System.currentTimeMillis();
		Map<String, Object> out = ok(service.compositionOutfits(f.bookOid, compOid, requestAs(owner)), what);
		logger.info(what + ": " + (System.currentTimeMillis() - t0) + " ms " + out);
		assertEquals(f.bookOid, out.get("bookObjectId"));
		assertEquals(compOid, out.get("compositionObjectId"));
		return out;
	}

	private void expectOutfit(Map<String, Object> outfits, String actorOid, Cast c, String source, String apparelOid, int fromIdx, String label) {
		Map<String, Object> a = outfitOf(outfits, actorOid);
		assertEquals("characterObjectId of " + c.name, c.oid, a.get("characterObjectId"));
		assertEquals("source for " + c.name, source, a.get("source"));
		assertEquals("apparelObjectId for " + c.name, apparelOid, a.get("apparelObjectId"));
		assertEquals("fromSceneIndex for " + c.name, fromIdx, num(a, "fromSceneIndex"));
		assertEquals("label for " + c.name, label, a.get("label"));
		assertNull("no warning for " + c.name, a.get("warning"));
	}

	private long referenceJobs(BaseRecord as) {
		List<AsyncJob> jobs = AsyncJobRegistry.list(as);
		return jobs.stream().filter(j -> REFERENCE_KIND.equals(j.getKind())).count();
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/// Without a principal every route is 401 before the book is even looked up.
	@Test
	public void TestNoPrincipalIs401() {
		HttpServletRequestMock anon = new HttpServletRequestMock();
		String np = "No authenticated principal";
		refused(service.listWardrobe(NIL_UUID, NIL_UUID, anon), 401, np, "anonymous wardrobe list");
		refused(service.createWardrobeAlternate(NIL_UUID, NIL_UUID, "{}", anon, SD_CONTEXT), 401, np, "anonymous alternate");
		refused(service.setDefaultApparel(NIL_UUID, NIL_UUID, NIL_UUID, anon), 401, np, "anonymous default");
		refused(service.requestApparelReference(NIL_UUID, NIL_UUID, NIL_UUID, anon, SD_CONTEXT), 401, np, "anonymous reference");
		refused(service.pinActorApparel(NIL_UUID, NIL_UUID, NIL_UUID, "{}", anon), 401, np, "anonymous pin");
		refused(service.compositionOutfits(NIL_UUID, NIL_UUID, anon), 401, np, "anonymous outfits");
		refused(service.migrateSceneTags(NIL_UUID, anon), 401, np, "anonymous migrate");
		assertEquals("nothing queued for nobody", 0, referenceJobs(owner));
	}

	/// Route-level body checks fire before requireBook (so NIL ids suffice): a JSON array is not a body; a missing
	/// body is an empty one and reaches the book gate.
	@Test
	public void TestMalformedBodyIs400() {
		HttpServletRequestMock o = requestAs(owner);
		String bad = "[1,2]";
		String mb = "Malformed request body";
		refused(service.createWardrobeAlternate(NIL_UUID, NIL_UUID, bad, o, SD_CONTEXT), 400, mb, "malformed alternate");
		refused(service.pinActorApparel(NIL_UUID, NIL_UUID, NIL_UUID, bad, o), 400, mb, "malformed pin");
		refused(service.createWardrobeAlternate(NIL_UUID, NIL_UUID, null, o, SD_CONTEXT), 404, "Book not found", "alternate with no body");
		refused(service.pinActorApparel(NIL_UUID, NIL_UUID, NIL_UUID, null, o), 404, "Book not found", "pin with no body");
		refused(service.pinActorApparel(NIL_UUID, NIL_UUID, NIL_UUID, "   ", o), 404, "Book not found", "pin with a blank body");
		/// a blank book id is the 400 of the book gate on every route
		refused(service.listWardrobe("", NIL_UUID, o), 400, "A book objectId is required", "wardrobe list with a blank book");
		refused(service.migrateSceneTags(" ", o), 400, "A book objectId is required", "migrate with a blank book");
	}

	/// A user with no grant on the book cannot tell it exists: 404 "Book not found" on all seven routes, no character,
	/// apparel, composition or actor id ever being consulted.
	@Test
	public void TestStrangerIs404OnEveryRoute() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			HttpServletRequestMock s = requestAs(stranger);
			String nf = "Book not found";
			v3();
			refused(service.listWardrobe(f.bookOid, NIL_UUID, s), 404, nf, "stranger wardrobe list");
			refused(service.createWardrobeAlternate(f.bookOid, NIL_UUID, json("description", "a red dress"), s, SD_CONTEXT), 404, nf, "stranger alternate");
			refused(service.setDefaultApparel(f.bookOid, NIL_UUID, NIL_UUID, s), 404, nf, "stranger default");
			refused(service.requestApparelReference(f.bookOid, NIL_UUID, NIL_UUID, s, SD_CONTEXT), 404, nf, "stranger reference");
			refused(service.pinActorApparel(f.bookOid, NIL_UUID, NIL_UUID, "{}", s), 404, nf, "stranger pin");
			refused(service.compositionOutfits(f.bookOid, NIL_UUID, s), 404, nf, "stranger outfits");
			refused(service.migrateSceneTags(f.bookOid, s), 404, nf, "stranger migrate");
			assertEquals("nothing queued for the stranger", 0, referenceJobs(stranger));
		}
		finally {
			cleanup(f);
		}
	}

	/// A Read-only grantee reads the wardrobe and the resolved outfits (the two read routes have no update gate) but
	/// every write is 403 naming the refused action - before any body value, character, apparel, chat config or SD
	/// backend is consulted - and nothing is queued.
	@Test
	public void TestReaderReadsButEveryWriteIs403() throws Exception {
		Fixture f = createWorldBook(true);
		try {
			v3();
			Cast a = addCharacter(f, "Reader Case");
			String a1 = addApparel(f, a, "Default outfit", true);
			BaseRecord scene = addScene(f, 1);
			enroll(scene, a);
			BaseRecord comp = compose(f, scene);
			String compOid = oid(comp);
			String actorOid = actorOf(comp, a);

			HttpServletRequestMock r = requestAs(reader);
			long t0 = System.currentTimeMillis();
			Map<String, Object> w = ok(service.listWardrobe(f.bookOid, a.oid, r), "reader wardrobe list");
			logger.info("reader wardrobe list: " + (System.currentTimeMillis() - t0) + " ms " + w);
			assertEquals(f.bookOid, w.get("bookObjectId"));
			assertEquals(a.oid, w.get("characterObjectId"));
			assertEquals(a.name, w.get("characterName"));
			assertEquals(1, num(w, "entryCount"));
			assertEquals(a1, w.get("defaultApparelObjectId"));
			assertEquals(Boolean.TRUE, entryOf(w, a1).get("isDefault"));

			t0 = System.currentTimeMillis();
			Map<String, Object> out = ok(service.compositionOutfits(f.bookOid, compOid, r), "reader outfits");
			logger.info("reader outfits: " + (System.currentTimeMillis() - t0) + " ms " + out);
			assertEquals(compOid, out.get("compositionObjectId"));
			assertEquals(oid(scene), out.get("sceneObjectId"));
			expectOutfit(out, actorOid, a, "default", a1, -1, "(default)");

			String na = "Not authorized to ";
			refused(service.createWardrobeAlternate(f.bookOid, a.oid, json("description", "a long red dress"), r, SD_CONTEXT), 403, na + "modify the wardrobe in this book", "reader alternate");
			refused(service.createWardrobeAlternate(f.bookOid, NIL_UUID, json("description", "   ", "chatConfigName", MISSING_CONFIG), r, SD_CONTEXT), 403, na + "modify the wardrobe in this book", "reader alternate is the 403, not the 404 character / 400 description / 404 config");
			refused(service.setDefaultApparel(f.bookOid, a.oid, a1, r), 403, na + "modify the wardrobe in this book", "reader default");
			refused(service.setDefaultApparel(f.bookOid, NIL_UUID, NIL_UUID, r), 403, na + "modify the wardrobe in this book", "reader default is the 403, not the 404");
			refused(service.requestApparelReference(f.bookOid, a.oid, a1, r, SD_CONTEXT), 403, na + "render a wardrobe reference in this book", "reader reference");
			refused(service.requestApparelReference(f.bookOid, a.oid, a1, r, NO_SD_CONTEXT), 403, na + "render a wardrobe reference in this book", "reader reference is the 403, not the 500 SD");
			refused(service.pinActorApparel(f.bookOid, compOid, actorOid, json("apparelObjectId", a1), r), 403, na + "pin an outfit in this book", "reader pin");
			refused(service.pinActorApparel(f.bookOid, compOid, actorOid, "{}", r), 403, na + "pin an outfit in this book", "reader unpin");
			refused(service.pinActorApparel(f.bookOid, "", "", "{}", r), 403, na + "pin an outfit in this book", "reader pin is the 403, not the 400 ids");
			refused(service.migrateSceneTags(f.bookOid, r), 403, na + "migrate wardrobe tags in this book", "reader migrate");

			/// nothing changed and nothing was queued
			Map<String, Object> again = ok(service.compositionOutfits(f.bookOid, compOid, r), "reader outfits after the refusals");
			expectOutfit(again, actorOid, a, "default", a1, -1, "(default)");
			assertEquals("no reference job for the reader", 0, referenceJobs(reader));
			assertEquals("no job runs for the reader", 0, AsyncJobRegistry.list(reader).stream().filter(j -> !j.isTerminal()).count());
		}
		finally {
			cleanup(f);
		}
	}

	/// The wardrobe list, the default flip and the gates of the alternate / reference routes on a real, writable V3
	/// book as the owner: two characters with hand-built outfits, one scene wearing the default. Every alternate and
	/// reference call here is refused before the LLM / SD backend is reached, and the registry proves no reference
	/// job was claimed.
	@Test
	public void TestOwnerWardrobeDefaultAndRefusals() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			HttpServletRequestMock o = requestAs(owner);
			Cast a = addCharacter(f, "Mira");
			Cast b = addCharacter(f, "Tomas");
			String a1 = addApparel(f, a, "Travelling cloak", true);
			String a2 = addApparel(f, a, "Court gown", false);
			String b1 = addApparel(f, b, "Fisherman's oilskin", true);
			BaseRecord scene = addScene(f, 1);
			enroll(scene, a);
			enroll(scene, b);
			BaseRecord comp = compose(f, scene);
			String compOid = oid(comp);
			String actorA = actorOf(comp, a);
			String actorB = actorOf(comp, b);

			/// GET wardrobe
			long t0 = System.currentTimeMillis();
			Map<String, Object> w = ok(service.listWardrobe(f.bookOid, a.oid, o), "wardrobe list");
			logger.info("wardrobe list: " + (System.currentTimeMillis() - t0) + " ms " + w);
			assertEquals(f.bookOid, w.get("bookObjectId"));
			assertEquals(a.oid, w.get("characterObjectId"));
			assertEquals(a.name, w.get("characterName"));
			assertEquals(oid(a.store), w.get("storeObjectId"));
			assertEquals(a1, w.get("defaultApparelObjectId"));
			assertEquals(2, num(w, "entryCount"));
			assertEquals(2, list(w, "entries").size());
			Map<String, Object> e1 = entryOf(w, a1);
			Map<String, Object> e2 = entryOf(w, a2);
			assertEquals(Boolean.TRUE, e1.get("isDefault"));
			assertEquals(Boolean.FALSE, e2.get("isDefault"));
			assertTrue("entry name: " + e1.get("name"), String.valueOf(e1.get("name")).startsWith("Travelling cloak"));
			assertTrue("entry description: " + e2.get("description"), String.valueOf(e2.get("description")).startsWith("Court gown of "));
			assertNull("no legacy scene tag", e1.get("sceneIndex"));
			assertTrue("hand-built apparel has no wearables", list(e1, "wearables").isEmpty());
			assertTrue("no reference gallery yet", list(e1, "gallery").isEmpty());
			assertNull("no reference yet", e1.get("referenceObjectId"));
			assertTrue("no warnings: " + w.get("warnings"), list(w, "warnings").isEmpty());
			/// the other character's wardrobe is its own
			Map<String, Object> wb = ok(service.listWardrobe(f.bookOid, b.oid, o), "wardrobe list of the second character");
			assertEquals(1, num(wb, "entryCount"));
			assertEquals(b1, wb.get("defaultApparelObjectId"));
			/// unknown character
			refused(service.listWardrobe(f.bookOid, NIL_UUID, o), 404, "Character not found", "wardrobe list of an unknown character");

			/// POST wardrobe: refused before the LLM
			refused(service.createWardrobeAlternate(f.bookOid, a.oid, "{}", o, SD_CONTEXT), 400, "No outfit description", "alternate with no description");
			refused(service.createWardrobeAlternate(f.bookOid, a.oid, json("description", "   "), o, SD_CONTEXT), 400, "No outfit description", "alternate with a blank description");
			refused(service.createWardrobeAlternate(f.bookOid, a.oid, json("description", "a long red dress", "chatConfigName", MISSING_CONFIG), o, SD_CONTEXT), 404, "Chat config not found: " + MISSING_CONFIG, "alternate with an unknown chat config");
			refused(service.createWardrobeAlternate(f.bookOid, NIL_UUID, json("description", "a long red dress"), o, SD_CONTEXT), 404, "Character not found", "alternate for an unknown character");

			/// PUT default: the court gown becomes the default, the cloak is the previous one; the one scene wears the
			/// default so it is scanned, and on this harness it has no node to stale
			t0 = System.currentTimeMillis();
			Map<String, Object> d = ok(service.setDefaultApparel(f.bookOid, a.oid, a2, o), "set default");
			logger.info("set default: " + (System.currentTimeMillis() - t0) + " ms " + d);
			assertEquals(f.bookOid, d.get("bookObjectId"));
			assertEquals(a.oid, d.get("characterObjectId"));
			assertEquals(a2, d.get("apparelObjectId"));
			assertEquals(Boolean.TRUE, d.get("changed"));
			assertEquals("the cloak was the default", List.of(a1), strings(list(d, "previousApparelObjectIds")));
			assertEquals("the one scene was scanned", 1, num(d, "scenesScanned"));
			assertEquals("no render node on this harness, so nothing to stale", 0, num(d, "staled"));
			assertTrue("no warnings: " + d.get("warnings"), list(d, "warnings").isEmpty());
			Map<String, Object> w2 = ok(service.listWardrobe(f.bookOid, a.oid, o), "wardrobe list after the flip");
			assertEquals(a2, w2.get("defaultApparelObjectId"));
			assertEquals(Boolean.FALSE, entryOf(w2, a1).get("isDefault"));
			assertEquals(Boolean.TRUE, entryOf(w2, a2).get("isDefault"));
			Map<String, Object> out = outfits(f, compOid, "outfits after the flip");
			expectOutfit(out, actorA, a, "default", a2, -1, "(default)");
			expectOutfit(out, actorB, b, "default", b1, -1, "(default)");
			/// idempotent
			Map<String, Object> d2 = ok(service.setDefaultApparel(f.bookOid, a.oid, a2, o), "set default again");
			assertEquals(Boolean.FALSE, d2.get("changed"));
			assertTrue("nothing was previous", list(d2, "previousApparelObjectIds").isEmpty());
			assertEquals(0, num(d2, "scenesScanned"));
			assertEquals(0, num(d2, "staled"));
			/// refusals
			refused(service.setDefaultApparel(f.bookOid, a.oid, b1, o), 400, "is not in the wardrobe of " + a.name, "default to another character's outfit");
			refused(service.setDefaultApparel(f.bookOid, a.oid, NIL_UUID, o), 404, "Apparel not found", "default to an unknown outfit");
			refused(service.setDefaultApparel(f.bookOid, NIL_UUID, a1, o), 404, "Character not found", "default for an unknown character");

			/// POST reference: every refusal lands before a job is claimed
			refused(service.requestApparelReference(f.bookOid, a.oid, NIL_UUID, o, SD_CONTEXT), 404, "Apparel not found", "reference of an unknown outfit");
			refused(service.requestApparelReference(f.bookOid, a.oid, b1, o, SD_CONTEXT), 400, "is not in the wardrobe of " + a.name, "reference of another character's outfit");
			refused(service.requestApparelReference(f.bookOid, NIL_UUID, a1, o, SD_CONTEXT), 404, "Character not found", "reference for an unknown character");
			refused(service.requestApparelReference(f.bookOid, a.oid, a1, o, NO_SD_CONTEXT), 500, "SD server not configured", "reference without an SD backend");
			refused(service.requestApparelReference(f.bookOid, a.oid, a1, o, initParams(Map.of("sd.server.apiType", "NOPE", "sd.server", "http://127.0.0.1:1"))), 500, "SD server API type is not recognised", "reference with a bad SD api type");
			assertEquals("no reference job was claimed for the owner", 0, referenceJobs(owner));
			assertEquals("no job runs for the owner", 0, AsyncJobRegistry.list(owner).stream().filter(j -> !j.isTerminal()).count());
		}
		finally {
			cleanup(f);
		}
	}

	/// Pins, carry-forward and the tag migration through the routes on a three-scene book: Mira is in every scene,
	/// Tomas only in scene 2. A pin on scene 1 is carried into scenes 2 and 3 (affected [1,2,3]); an unpin returns
	/// them to the default; a legacy sceneIndex tag is migrated into a pin exactly once.
	@Test
	public void TestOwnerPinCarryForwardOutfitsAndMigrate() throws Exception {
		Fixture f = createWorldBook(false);
		try {
			v3();
			HttpServletRequestMock o = requestAs(owner);
			Cast a = addCharacter(f, "Mira");
			Cast b = addCharacter(f, "Tomas");
			String a1 = addApparel(f, a, "Travelling cloak", true);
			String a2 = addApparel(f, a, "Court gown", false);
			String a3 = addApparel(f, a, "Mourning black", false);
			String b1 = addApparel(f, b, "Fisherman's oilskin", true);
			BaseRecord s1 = addScene(f, 1);
			BaseRecord s2 = addScene(f, 2);
			BaseRecord s3 = addScene(f, 3);
			enroll(s1, a);
			enroll(s2, a);
			enroll(s2, b);
			enroll(s3, a);
			BaseRecord c1 = compose(f, s1);
			BaseRecord c2 = compose(f, s2);
			BaseRecord c3 = compose(f, s3);
			String c1Oid = oid(c1);
			String c2Oid = oid(c2);
			String c3Oid = oid(c3);
			String a1Actor = actorOf(c1, a);
			String a2Actor = actorOf(c2, a);
			String a3Actor = actorOf(c3, a);
			String b2Actor = actorOf(c2, b);

			/// GET outfits: everyone wears the default
			Map<String, Object> o1 = outfits(f, c1Oid, "outfits of scene 1");
			assertEquals(oid(s1), o1.get("sceneObjectId"));
			assertEquals("one actor in scene 1", 1, list(o1, "actors").size());
			expectOutfit(o1, a1Actor, a, "default", a1, -1, "(default)");
			Map<String, Object> o2 = outfits(f, c2Oid, "outfits of scene 2");
			assertEquals("two actors in scene 2", 2, list(o2, "actors").size());
			expectOutfit(o2, a2Actor, a, "default", a1, -1, "(default)");
			expectOutfit(o2, b2Actor, b, "default", b1, -1, "(default)");
			/// refusals of the read route
			refused(service.compositionOutfits(f.bookOid, NIL_UUID, o), 404, "Composition " + NIL_UUID + " not found in this book", "outfits of an unknown composition");
			refused(service.compositionOutfits(f.bookOid, " ", o), 400, "A composition objectId is required", "outfits with a blank composition");

			/// PUT actor apparel with an empty body: an unpin of an unpinned actor - pinned:false, only its own scene affected
			long t0 = System.currentTimeMillis();
			Map<String, Object> noop = ok(service.pinActorApparel(f.bookOid, c1Oid, a1Actor, "{}", o), "unpin of an unpinned actor");
			logger.info("unpin (no-op): " + (System.currentTimeMillis() - t0) + " ms " + noop);
			assertEquals(f.bookOid, noop.get("bookObjectId"));
			assertEquals(c1Oid, noop.get("compositionObjectId"));
			assertEquals(a1Actor, noop.get("actorObjectId"));
			assertEquals(Boolean.FALSE, noop.get("pinned"));
			assertNull("no apparel after an unpin", noop.get("apparelObjectId"));
			assertEquals(List.of(1), ints(list(noop, "affectedSceneIndexes")));
			assertEquals(0, num(noop, "staled"));

			/// pin the court gown on scene 1: scenes 2 and 3 now carry it
			t0 = System.currentTimeMillis();
			Map<String, Object> pin = ok(service.pinActorApparel(f.bookOid, c1Oid, a1Actor, json("apparelObjectId", a2), o), "pin scene 1");
			logger.info("pin scene 1: " + (System.currentTimeMillis() - t0) + " ms " + pin);
			assertEquals(Boolean.TRUE, pin.get("pinned"));
			assertEquals(a2, pin.get("apparelObjectId"));
			assertEquals(a1Actor, pin.get("actorObjectId"));
			assertEquals("the pinned scene and the two that carry from it", List.of(1, 2, 3), ints(list(pin, "affectedSceneIndexes")));
			assertEquals(0, num(pin, "staled"));
			expectOutfit(outfits(f, c1Oid, "scene 1 after the pin"), a1Actor, a, "pinned", a2, -1, "(pinned)");
			Map<String, Object> o2b = outfits(f, c2Oid, "scene 2 after the pin");
			expectOutfit(o2b, a2Actor, a, "carried", a2, 1, "(from scene 1)");
			expectOutfit(o2b, b2Actor, b, "default", b1, -1, "(default)");
			expectOutfit(outfits(f, c3Oid, "scene 3 after the pin"), a3Actor, a, "carried", a2, 1, "(from scene 1)");
			/// a pin is not a user edit
			BaseRecord c1Again = PbComposeUtil.readComposition(owner, c1Oid, orgId);
			assertNotNull("composition 1 reads back after the pin", c1Again);
			assertEquals("a pin does not mark the composition user-edited", Boolean.FALSE, c1Again.get(OlioFieldNames.FIELD_PB_USER_EDITED));

			/// a later pin breaks the chain for the scenes after it only
			Map<String, Object> pin3 = ok(service.pinActorApparel(f.bookOid, c3Oid, a3Actor, json("apparelObjectId", a3), o), "pin scene 3");
			logger.info("pin scene 3: " + pin3);
			assertEquals(List.of(3), ints(list(pin3, "affectedSceneIndexes")));
			expectOutfit(outfits(f, c3Oid, "scene 3 after its own pin"), a3Actor, a, "pinned", a3, -1, "(pinned)");
			expectOutfit(outfits(f, c2Oid, "scene 2 still carries from 1"), a2Actor, a, "carried", a2, 1, "(from scene 1)");

			/// refusals of the write route
			refused(service.pinActorApparel(f.bookOid, c2Oid, b2Actor, json("apparelObjectId", a1), o), 400, "is not in the wardrobe of " + b.name, "pin another character's outfit");
			refused(service.pinActorApparel(f.bookOid, c2Oid, a2Actor, json("apparelObjectId", NIL_UUID), o), 404, "Apparel not found", "pin an unknown outfit");
			refused(service.pinActorApparel(f.bookOid, c1Oid, b2Actor, json("apparelObjectId", b1), o), 400, "does not belong to composition " + c1Oid, "pin an actor of another composition");
			refused(service.pinActorApparel(f.bookOid, c1Oid, NIL_UUID, json("apparelObjectId", a2), o), 404, "Actor not found", "pin an unknown actor");
			refused(service.pinActorApparel(f.bookOid, NIL_UUID, a1Actor, json("apparelObjectId", a2), o), 404, "Composition not found", "pin in an unknown composition");
			refused(service.pinActorApparel(f.bookOid, "", a1Actor, "{}", o), 400, "A composition objectId is required", "pin with a blank composition");
			refused(service.pinActorApparel(f.bookOid, c1Oid, " ", "{}", o), 400, "An actor objectId is required", "pin with a blank actor");
			/// and none of them moved anything
			expectOutfit(outfits(f, c2Oid, "scene 2 after the refusals"), a2Actor, a, "carried", a2, 1, "(from scene 1)");

			/// unpin scene 1 with a blank apparelObjectId: scene 2 falls back to the default, scene 3 keeps its own pin
			t0 = System.currentTimeMillis();
			Map<String, Object> unpin = ok(service.pinActorApparel(f.bookOid, c1Oid, a1Actor, json("apparelObjectId", ""), o), "unpin scene 1");
			logger.info("unpin scene 1: " + (System.currentTimeMillis() - t0) + " ms " + unpin);
			assertEquals(Boolean.FALSE, unpin.get("pinned"));
			assertNull("no apparel after an unpin", unpin.get("apparelObjectId"));
			assertEquals("the unpinned scene and the one that carried from it", List.of(1, 2), ints(list(unpin, "affectedSceneIndexes")));
			expectOutfit(outfits(f, c1Oid, "scene 1 after the unpin"), a1Actor, a, "default", a1, -1, "(default)");
			expectOutfit(outfits(f, c2Oid, "scene 2 after the unpin"), a2Actor, a, "default", a1, -1, "(default)");
			expectOutfit(outfits(f, c3Oid, "scene 3 after the unpin"), a3Actor, a, "pinned", a3, -1, "(pinned)");

			/// POST migrate: a legacy sceneIndex tag on the court gown for scene 2 becomes the pin of Mira's actor there
			assertTrue("legacy tag of the court gown for scene 2", PictureBookUtil.tagApparelSceneIndex(owner, a.oid, a2, 2));
			Map<String, Object> w = ok(service.listWardrobe(f.bookOid, a.oid, o), "wardrobe list carries the legacy tag");
			assertEquals("the tag is reported on the entry", 2, num(entryOf(w, a2), "sceneIndex"));
			t0 = System.currentTimeMillis();
			Map<String, Object> m1 = ok(service.migrateSceneTags(f.bookOid, o), "migrate");
			logger.info("migrate #1: " + (System.currentTimeMillis() - t0) + " ms " + m1);
			assertEquals(f.bookOid, m1.get("bookObjectId"));
			assertEquals(1, num(m1, "scanned"));
			assertEquals(1, num(m1, "pinned"));
			assertEquals(0, num(m1, "skippedPinned"));
			assertEquals(0, num(m1, "skippedNoActor"));
			assertEquals(0, num(m1, "skippedNoScene"));
			assertEquals(0, num(m1, "skippedNoComposition"));
			assertEquals(0, num(m1, "ambiguous"));
			assertTrue("no warnings: " + m1.get("warnings"), list(m1, "warnings").isEmpty());
			expectOutfit(outfits(f, c2Oid, "scene 2 after the migration"), a2Actor, a, "pinned", a2, -1, "(pinned)");
			expectOutfit(outfits(f, c3Oid, "scene 3 keeps its own pin"), a3Actor, a, "pinned", a3, -1, "(pinned)");
			/// idempotent: the pin is never overwritten; a tag for a scene that does not exist is counted, not applied
			assertTrue("legacy tag of the oilskin for a scene that does not exist", PictureBookUtil.tagApparelSceneIndex(owner, b.oid, b1, 9));
			Map<String, Object> m2 = ok(service.migrateSceneTags(f.bookOid, o), "migrate again");
			logger.info("migrate #2: " + m2);
			assertEquals(2, num(m2, "scanned"));
			assertEquals(0, num(m2, "pinned"));
			assertEquals(1, num(m2, "skippedPinned"));
			assertEquals(1, num(m2, "skippedNoScene"));
			assertEquals(0, num(m2, "skippedNoActor"));
			assertEquals(0, num(m2, "skippedNoComposition"));
			expectOutfit(outfits(f, c2Oid, "scene 2 after the second migration"), a2Actor, a, "pinned", a2, -1, "(pinned)");
			assertEquals("nothing queued by any of this", 0, referenceJobs(owner));
		}
		finally {
			cleanup(f);
		}
	}
}
