package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.core.Response;

/// P1-8 (design §28.1 Stage 1): a V3 route is refused with a clear 409 "render path is v1" body when the
/// product's boot-pinned switch is v1, and admitted (here: falls through to the next gate) when it is v3.
///
/// Exercised through the real PictureBookService method -> PbServiceFacade -> PbRenderPath, the real
/// Objects7 resolver and the real (H2) database, with the same HttpServletRequestMock + UserPrincipal
/// pattern as TestFeatureConfigService. Nothing is mocked.
///
/// FIXTURE. The gate sits AFTER requireBook (404) and BEFORE requireWorkflow (404 "no workflow yet"),
/// so a bare olio.pb.book row that the caller owns is the exact minimum: it is enough to pass
/// requireBook, and under v3 the same call reaches requireWorkflow and 404s there - which is how the
/// test tells "admitted past the switch" from "refused by the switch" without an Olio world (a real
/// book needs a seeded world; the switch does not). The full V3 flow behind the gate is covered by the
/// Objects7 suite (TestPbRenderPath, TestPbAutoApprove, TestPbRenderJob, ...) against a graph fixture.
///
/// P7-2 (§28.1 Stage 2 for PictureBook): the three retired PB1 entries - POST /scene/{id}/generate, POST
/// /{book}/prepare-images, PUT /{book}/settings - refuse with 409 "replaced by V3" under the picturebook v3
/// default, after authorization / existence and before any SD or persist work (TestPb1EntriesRefusedUnderV3,
/// on the PB1 storage shape: a data.group book, its Scenes sub-group and one data.note scene).
///
/// The switch is process-global; every case restores it in finally.
public class TestPbRenderPathRefusal extends BaseTest {

	private static final String USER_NAME = "pbrenderpathuser";
	private static final String STRANGER_NAME = "pbrenderpathstranger";
	private static final String GROUP_PATH = "~/PbRenderPathRefusal";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	/// A syntactically valid SD host nothing listens on: reaching the SD stage would surface as a connection error.
	private static final ServletContext SD_CONTEXT = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", "http://127.0.0.1:1"));

	private final PictureBookService service = new PictureBookService();

	private BaseRecord user;
	private BaseRecord stranger;
	private long orgId;
	private String pb0;
	private String cb0;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		user = getCreateUser(USER_NAME);
		stranger = getCreateUser(STRANGER_NAME);
		assertNotNull("Test user is null", user);
		assertNotNull("Stranger user is null", stranger);
		assertNotEquals("owner and stranger must be different users", user.get(FieldNames.FIELD_ID), stranger.get(FieldNames.FIELD_ID));
		pb0 = PbRenderPath.pictureBook();
		cb0 = PbRenderPath.chapBook();
	}

	/// The same @Context ServletContext stand-in TestPictureBookRenderPlanRoute uses: only the two SD init-params.
	private static ServletContext initParams(Map<String, String> params) {
		return (ServletContext) Proxy.newProxyInstance(
			TestPbRenderPathRefusal.class.getClassLoader(),
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

	@After
	public void restoreSwitch() {
		PbRenderPath.configure(pb0, cb0);
	}

	private HttpServletRequestMock requestAs(BaseRecord u) {
		return new HttpServletRequestMock(new UserPrincipal((String)u.get(FieldNames.FIELD_NAME), organizationPath));
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

	/// A bare book row owned by the test user, in a group the test user owns. Created through
	/// AccessPoint as that user - no admin, no RecordUtil bypass - and deleted the same way.
	private BaseRecord createBareBook(PbBookTypeEnumType type) throws Exception {
		String slug = "rp-" + UUID.randomUUID().toString().substring(0, 12);
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, GROUP_PATH, "DATA", orgId);
		assertNotNull("Fixture group is null", dir);
		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		ioContext.getRecordUtil().applyNameGroupOwnership(user, book, slug, GROUP_PATH, orgId);
		book.set(FieldNames.FIELD_NAME, slug);
		book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
		book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
		book.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, type.toString());
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, book);
		assertNotNull("Failed to create the bare book fixture", created);
		String oid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Created book carries no objectId", oid);
		return created;
	}

	private void deleteBareBook(BaseRecord book) {
		if(book != null) {
			assertTrue("Failed to delete the bare book fixture",
				IOSystem.getActiveContext().getAccessPoint().delete(user, book));
		}
	}

	/// The PB1 storage shape createFromScenes produces - ~/Data/PictureBooks/<book>/Scenes/<scene note> - owned
	/// by the test user, created through AccessPoint as that user. No LLM: the note text is the extraction's JSON shape.
	private static final class Pb1Book {
		BaseRecord bookGroup;
		BaseRecord scenesGroup;
		BaseRecord scene;
		String bookGroupOid;
		String sceneOid;
	}

	private Pb1Book createPb1Book() throws Exception {
		Pb1Book b = new Pb1Book();
		String path = "~/Data/PictureBooks/p72-" + UUID.randomUUID().toString().substring(0, 8);
		b.bookGroup = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("book group " + path, b.bookGroup);
		b.bookGroupOid = b.bookGroup.get(FieldNames.FIELD_OBJECT_ID);
		b.scenesGroup = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path + "/Scenes", GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Scenes group", b.scenesGroup);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path + "/Scenes");
		plist.parameter(FieldNames.FIELD_NAME, "Scene 1");
		BaseRecord note = ioContext.getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set("text", "{\"title\":\"Scene 1\",\"setting\":\"a quiet room\",\"action\":\"nothing happens\",\"mood\":\"calm\"}");
		b.scene = IOSystem.getActiveContext().getAccessPoint().create(user, note);
		assertNotNull("scene note", b.scene);
		b.sceneOid = b.scene.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("scene objectId", b.sceneOid);
		return b;
	}

	private void deletePb1Book(Pb1Book b) {
		if(b == null) return;
		IOSystem.getActiveContext().getAccessPoint().delete(user, b.scene);
		IOSystem.getActiveContext().getAccessPoint().delete(user, b.scenesGroup);
		IOSystem.getActiveContext().getAccessPoint().delete(user, b.bookGroup);
	}

	/// 409 whose body starts with PB1_REPLACED_PREFIX and names the retired entry and its V3 replacement.
	private void assertPb1Replaced(String what, Response r, String entry, String replacement) {
		assertEquals(what + " under v3 must be 409", 409, r.getStatus());
		String msg = String.valueOf(body(r).get("error"));
		assertTrue(what + " must start with '" + PbRenderPath.PB1_REPLACED_PREFIX + "': " + msg, msg.startsWith(PbRenderPath.PB1_REPLACED_PREFIX));
		assertTrue(what + " must name the retired entry: " + msg, msg.contains(entry));
		assertTrue(what + " must name the V3 replacement: " + msg, msg.contains(replacement));
		assertTrue(what + " must name the rollback env var: " + msg, msg.contains("PICTUREBOOK_RENDER_PATH"));
	}

	/// A non-409 answer that carries none of the PB1 refusal text.
	private int assertNotPb1Replaced(String what, Response r) {
		assertNotEquals(what + " must not be the PB1 409", 409, r.getStatus());
		String msg = String.valueOf(body(r).get("error"));
		assertTrue(what + " must not carry the PB1 refusal: " + msg, !msg.startsWith(PbRenderPath.PB1_REPLACED_PREFIX));
		return r.getStatus();
	}

	/// ------------------------------------------------------------------------------------------

	/// Under v1 the V3 run route is refused with 409 and the documented body prefix; the refusal names
	/// the product key and every config surface, and never leaks past the gate (no "no workflow"
	/// text, which is what the NEXT gate would say).
	@Test
	public void TestRunRouteRefusedUnderV1() throws Exception {
		BaseRecord book = createBareBook(PbBookTypeEnumType.STORY);
		try {
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
			Response r = service.readRun(book.get(FieldNames.FIELD_OBJECT_ID), "no-such-run", requestAs(user));
			assertEquals("A V3 route under v1 must be 409", 409, r.getStatus());
			Map<String, Object> b = body(r);
			Object err = b.get("error");
			assertNotNull("409 body must carry 'error'", err);
			String msg = err.toString();
			assertTrue("error must start with '" + PbRenderPath.REFUSAL_PREFIX + "': " + msg,
				msg.startsWith(PbRenderPath.REFUSAL_PREFIX));
			assertTrue("error must name the product key: " + msg, msg.contains(PbRenderPath.PICTUREBOOK_CONFIG_KEY));
			assertTrue("error must name the Docker env var: " + msg, msg.contains("PICTUREBOOK_RENDER_PATH"));
			assertTrue("error must name web.xml: " + msg, msg.contains("web.xml"));
			assertTrue("error must name resource.properties: " + msg, msg.contains("resource.properties"));
			assertTrue("the refusal must stop at the switch, not reach the workflow gate: " + msg,
				!msg.contains("no workflow"));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteBareBook(book);
		}
	}

	/// Under v3 the same call passes the switch: the bare fixture has no workflow, so the next gate
	/// answers 404 "no workflow yet" - proof the request got past the switch rather than a generic
	/// not-found.
	@Test
	public void TestRunRouteAdmittedUnderV3() throws Exception {
		BaseRecord book = createBareBook(PbBookTypeEnumType.STORY);
		try {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
			Response r = service.readRun(book.get(FieldNames.FIELD_OBJECT_ID), "no-such-run", requestAs(user));
			assertNotEquals("A V3 route under v3 must not be refused by the switch", 409, r.getStatus());
			assertEquals("The bare fixture has no workflow, so the gate after the switch answers 404", 404, r.getStatus());
			String msg = String.valueOf(body(r).get("error"));
			assertTrue("Expected the workflow gate's message, got: " + msg, msg.contains("no workflow"));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteBareBook(book);
		}
	}

	/// Gate order: an unknown book is 404 BEFORE the switch is consulted, so the refusal never
	/// discloses that a book the caller cannot see exists.
	@Test
	public void TestUnknownBookIs404BeforeTheSwitch() {
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
		Response r = service.readRun("00000000-0000-0000-0000-000000000000", "no-such-run", requestAs(user));
		assertEquals(404, r.getStatus());
		assertEquals("Book not found", String.valueOf(body(r).get("error")));
	}

	/// No principal is 401 regardless of the switch.
	@Test
	public void TestNoPrincipalIs401() {
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
		assertEquals(401, service.readRun("x", "y", new HttpServletRequestMock()).getStatus());
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
		assertEquals(401, service.readRun("x", "y", new HttpServletRequestMock()).getStatus());
	}

	/// Product selection: a CHAPBOOK book is governed by chapbook.renderPath and a STORY book by
	/// picturebook.renderPath - each is admitted or refused by its own key, independently of the other.
	@Test
	public void TestProductSelectionByBookType() throws Exception {
		BaseRecord story = createBareBook(PbBookTypeEnumType.STORY);
		BaseRecord chap = createBareBook(PbBookTypeEnumType.CHAPBOOK);
		try {
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3);
			assertEquals("STORY under pb=v1 must be refused", 409,
				service.readRun(story.get(FieldNames.FIELD_OBJECT_ID), "r", requestAs(user)).getStatus());
			Response rc = service.readRun(chap.get(FieldNames.FIELD_OBJECT_ID), "r", requestAs(user));
			assertEquals("CHAPBOOK under cb=v3 must pass the switch (404 at the workflow gate)", 404, rc.getStatus());
			assertTrue(String.valueOf(body(rc).get("error")).contains("no workflow"));

			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V1);
			Response rs = service.readRun(story.get(FieldNames.FIELD_OBJECT_ID), "r", requestAs(user));
			assertEquals("STORY under pb=v3 must pass the switch", 404, rs.getStatus());
			Response rc2 = service.readRun(chap.get(FieldNames.FIELD_OBJECT_ID), "r", requestAs(user));
			assertEquals("CHAPBOOK under cb=v1 must be refused", 409, rc2.getStatus());
			String msg = String.valueOf(body(rc2).get("error"));
			assertTrue("ChapBook refusal must name the chapbook key: " + msg, msg.contains(PbRenderPath.CHAPBOOK_CONFIG_KEY));
			assertTrue("ChapBook refusal must name the chapbook env var: " + msg, msg.contains("CHAPBOOK_RENDER_PATH"));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteBareBook(story);
			deleteBareBook(chap);
		}
	}

	/// Write routes are gated the same way: the review POSTs and the auto-approve PUT under v1 are 409
	/// before any write (and before the 403 update check, which this owner would pass anyway); under
	/// v3 they pass the switch and fail at the next gate (404 workflow / artifact) instead.
	@Test
	public void TestWriteRoutesRefusedUnderV1() throws Exception {
		BaseRecord book = createBareBook(PbBookTypeEnumType.STORY);
		String bid = book.get(FieldNames.FIELD_OBJECT_ID);
		try {
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
			Response a = service.approveArtifact(bid, "no-such-artifact", requestAs(user));
			assertEquals("approve under v1 must be 409", 409, a.getStatus());
			assertTrue(String.valueOf(body(a).get("error")).startsWith(PbRenderPath.REFUSAL_PREFIX));
			Response s = service.setAutoApprove(bid, "{\"autoApprove\":true}", requestAs(user));
			assertEquals("auto-approve PUT under v1 must be 409", 409, s.getStatus());
			assertTrue(String.valueOf(body(s).get("error")).startsWith(PbRenderPath.REFUSAL_PREFIX));
			Response g = service.getAutoApprove(bid, requestAs(user));
			assertEquals("auto-approve GET under v1 must be 409", 409, g.getStatus());
			Response c = service.cancelRun(bid, "no-such-run", requestAs(user));
			assertEquals("cancel under v1 must be 409", 409, c.getStatus());

			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
			assertNotEquals("approve under v3 must pass the switch", 409,
				service.approveArtifact(bid, "no-such-artifact", requestAs(user)).getStatus());
			Response g3 = service.getAutoApprove(bid, requestAs(user));
			assertEquals("auto-approve GET under v3 on the bare fixture is a plain 200 read", 200, g3.getStatus());
			assertEquals(Boolean.FALSE, body(g3).get("autoApprove"));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteBareBook(book);
		}
	}

	/// P1-10 S0(d): the impact read is NOT behind the render-path switch (it sits on the listStale pattern),
	/// so under v1 it still reaches the workflow gate on the bare fixture; and an unregistered refModel is
	/// refused 400 by the facade before any book or workflow read, under either switch setting.
	@Test
	public void TestAffectedByIsUngatedAndRefusesAnUnknownRefModel() throws Exception {
		BaseRecord book = createBareBook(PbBookTypeEnumType.STORY);
		String bid = book.get(FieldNames.FIELD_OBJECT_ID);
		try {
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
			Response r1 = service.affectedByRecord(bid, OlioModelNames.MODEL_CHAR_PERSON, "no-such-record", requestAs(user));
			assertNotEquals("affected-by under v1 must not be refused by the switch", 409, r1.getStatus());
			assertEquals("the bare fixture has no workflow, so the read 404s at the workflow gate", 404, r1.getStatus());
			assertTrue(String.valueOf(body(r1).get("error")).contains("no workflow"));

			Response bad = service.affectedByRecord(bid, "no.such.model", "no-such-record", requestAs(user));
			assertEquals("an unregistered refModel is 400", 400, bad.getStatus());
			String msg = String.valueOf(body(bad).get("error"));
			assertTrue("400 names the refusal: " + msg, msg.contains("Unknown refModel"));

			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
			assertEquals(404, service.affectedByRecord(bid, OlioModelNames.MODEL_CHAR_PERSON, "no-such-record", requestAs(user)).getStatus());
			assertEquals(400, service.affectedByRecord(bid, "no.such.model", "no-such-record", requestAs(user)).getStatus());
			assertEquals("no principal is 401", 401,
				service.affectedByRecord(bid, OlioModelNames.MODEL_CHAR_PERSON, "x", new HttpServletRequestMock()).getStatus());

			/// Gate order. The model check precedes the book read (an unknown book with an unknown model is
			/// 400, not 404), and the principal check precedes both: no principal is 401 even with an unknown
			/// model, so an unauthenticated caller cannot use the route as a model-registration oracle.
			Response unknownBoth = service.affectedByRecord("00000000-0000-0000-0000-000000000000", "no.such.model", "x", requestAs(user));
			assertEquals("unknown book + unknown model is 400 (model check before requireBook)", 400, unknownBoth.getStatus());
			assertTrue(String.valueOf(body(unknownBoth).get("error")).contains("Unknown refModel"));
			Response anon = service.affectedByRecord(bid, "no.such.model", "x", new HttpServletRequestMock());
			assertEquals("no principal + unknown model is 401 (principal check first)", 401, anon.getStatus());
			assertTrue("401 body must not name the model check: " + body(anon),
				!String.valueOf(body(anon).get("error")).contains("refModel"));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deleteBareBook(book);
		}
	}

	/// P7-2 (§28.1 Stage 2): the three retired PB1 entries through the real REST methods. Owner under v3: 409
	/// "replaced by V3" naming the entry and its replacement, with a dead SD host (generate never reached SD) and
	/// GET /settings still {} afterwards (the settings PUT persisted nothing). Gate order (ledger §6.C 215 a): a
	/// stranger is 404 on the unreadable scene / book and an unknown id is 404 - never the 409, which would confirm
	/// the record exists. Under the v1 rollback the settings PUT is admitted (200 {"updated":true}, empty body).
	@Test
	public void TestPb1EntriesRefusedUnderV3() throws Exception {
		Pb1Book b = createPb1Book();
		/// A real field, not "{}": ensureSchema turns an empty object into "{\"schema\":...,}" which parseParams logs and drops.
		String generateBody = "{\"isBook\":true}";
		String prepareBody = "{\"sceneObjectIds\":[\"" + b.sceneOid + "\"]}";
		String settingsBody = "{\"sdConfig\":{\"schema\":\"olio.sd.config\",\"steps\":7}}";
		try {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
			assertPb1Replaced("POST /scene/{id}/generate", service.generateSceneImage(b.sceneOid, generateBody, requestAs(user), SD_CONTEXT),
				PictureBookUtil.PB1_ENTRY_GENERATE, PictureBookUtil.PB1_REPLACEMENT_GENERATE);
			assertPb1Replaced("POST /{book}/prepare-images", service.prepareSceneImagePrompts(b.bookGroupOid, prepareBody, requestAs(user)),
				PictureBookUtil.PB1_ENTRY_PREPARE, PictureBookUtil.PB1_REPLACEMENT_PREPARE);
			assertPb1Replaced("PUT /{book}/settings", service.setBookSdConfig(b.bookGroupOid, settingsBody, requestAs(user)),
				PictureBookUtil.PB1_ENTRY_SETTINGS, PictureBookUtil.PB1_REPLACEMENT_SETTINGS);
			Response get = service.getBookSdConfig(b.bookGroupOid, requestAs(user));
			assertEquals("GET /{book}/settings stays readable under v3", 200, get.getStatus());
			assertEquals("the refused PUT must not have persisted a config", "{}", String.valueOf(get.getEntity()));

			assertEquals("stranger generate is 404 on the unreadable scene", 404,
				assertNotPb1Replaced("generate(stranger)", service.generateSceneImage(b.sceneOid, generateBody, requestAs(stranger), SD_CONTEXT)));
			assertEquals("stranger settings PUT is 404 on the unreadable book", 404,
				assertNotPb1Replaced("settings(stranger)", service.setBookSdConfig(b.bookGroupOid, settingsBody, requestAs(stranger))));
			assertEquals("unknown scene is 404 before the gate", 404,
				assertNotPb1Replaced("generate(unknown)", service.generateSceneImage(NIL_UUID, generateBody, requestAs(user), SD_CONTEXT)));
			assertEquals("unknown book is 404 before the gate", 404,
				assertNotPb1Replaced("settings(unknown)", service.setBookSdConfig(NIL_UUID, settingsBody, requestAs(user))));

			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3);
			Response admitted = service.setBookSdConfig(b.bookGroupOid, "", requestAs(user));
			assertEquals("settings PUT under the v1 rollback is admitted", 200, admitted.getStatus());
			assertEquals(Boolean.TRUE, body(admitted).get("updated"));
		}
		finally {
			PbRenderPath.configure(pb0, cb0);
			deletePb1Book(b);
		}
	}
}
