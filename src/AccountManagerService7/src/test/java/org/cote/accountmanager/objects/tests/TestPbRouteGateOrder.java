package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.PictureBookService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// Gate order of the V3 PictureBook routes through the real PictureBookService method -> PbServiceFacade
/// -> the real Objects7 resolver and the real (H2) database, with the same HttpServletRequestMock +
/// UserPrincipal pattern as TestFeatureConfigService. Nothing is mocked.
///
/// FIXTURE. A bare olio.pb.book row that the caller owns is the exact minimum: it is enough to pass
/// requireBook (404 "Book not found"), and the same call then reaches requireWorkflow and 404s there
/// ("no workflow yet") - which is how the test tells "admitted past the book gate" from "unknown book"
/// without an Olio world (a real book needs a seeded world; the gates do not). The full V3 flow behind
/// the gates is covered by the Objects7 suite (TestPbAutoApprove, TestPbRenderJob, ...) against a graph
/// fixture.
///
/// History: this class was TestPbRenderPathRefusal, the proof of the boot-pinned picturebook/chapbook
/// render-path switch (design §28.1 Stages 1-2). The switch and the three PB1 entries it retired went at
/// Phase 10 P10-1 (PictureBook and ChapBook are V3-only); the path-neutral gate-order cases stay here and
/// the route-absence proof is TestPictureBookRestContract.TestPb1RoutesAreGone.
public class TestPbRouteGateOrder extends BaseTest {

	private static final String USER_NAME = "pbrenderpathuser";
	private static final String GROUP_PATH = "~/PbRenderPathRefusal";

	private final PictureBookService service = new PictureBookService();

	private BaseRecord user;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();
		user = getCreateUser(USER_NAME);
		assertNotNull("Test user is null", user);
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

	/// Give the bare book a style with a description through PbServiceFacade.putStyle (creates the book's own style in
	/// the book's group and links book.style); deleteBareBook removes it with the book.
	private void describeStyle(String bookObjectId) {
		Map<String, Object> dto = org.cote.accountmanager.olio.picturebook.PbServiceFacade.putStyle(user, bookObjectId,
			Map.of(FieldNames.FIELD_DESCRIPTION, "Test fixture style: clean ink linework with flat, muted colour."));
		assertNotNull("style PUT", dto);
	}

	private void deleteBareBook(BaseRecord book) {
		if(book != null) {
			/// The book's own style, when describeStyle gave it one: read before the book goes, deleted after it.
			BaseRecord read = PbBookUtil.readBook(user, (String) book.get(FieldNames.FIELD_OBJECT_ID), orgId);
			BaseRecord styleRef = (read != null ? read.get(OlioFieldNames.FIELD_STYLE) : null);
			BaseRecord own = (styleRef != null ? org.cote.accountmanager.olio.picturebook.PbStyleUtil.readStyle(user, styleRef, orgId) : null);
			assertTrue("Failed to delete the bare book fixture",
				IOSystem.getActiveContext().getAccessPoint().delete(user, book));
			if(own != null) {
				assertTrue("cleanup: style", IOSystem.getActiveContext().getAccessPoint().delete(user, own));
			}
		}
	}

	/// ------------------------------------------------------------------------------------------

	/// The run read on a readable book with no workflow is the workflow gate's 404 "no workflow yet" -
	/// proof the request got past the book gate rather than a generic not-found. A CHAPBOOK book takes
	/// the same gates as a STORY book.
	@Test
	public void TestRunRouteReachesTheWorkflowGate() throws Exception {
		BaseRecord story = createBareBook(PbBookTypeEnumType.STORY);
		BaseRecord chap = createBareBook(PbBookTypeEnumType.CHAPBOOK);
		try {
			for(BaseRecord book : new BaseRecord[] { story, chap }) {
				Response r = service.readRun(book.get(FieldNames.FIELD_OBJECT_ID), "no-such-run", requestAs(user));
				assertEquals("The bare fixture has no workflow, so the gate after the book answers 404", 404, r.getStatus());
				String msg = String.valueOf(body(r).get("error"));
				assertTrue("Expected the workflow gate's message, got: " + msg, msg.contains("no workflow"));
			}
		}
		finally {
			deleteBareBook(story);
			deleteBareBook(chap);
		}
	}

	/// Gate order: an unknown book is 404 "Book not found" before any later gate is consulted.
	@Test
	public void TestUnknownBookIs404() {
		Response r = service.readRun("00000000-0000-0000-0000-000000000000", "no-such-run", requestAs(user));
		assertEquals(404, r.getStatus());
		assertEquals("Book not found", String.valueOf(body(r).get("error")));
	}

	/// No principal is 401 before any book read.
	@Test
	public void TestNoPrincipalIs401() {
		assertEquals(401, service.readRun("x", "y", new HttpServletRequestMock()).getStatus());
	}

	/// Write routes on the bare fixture reach their own next gate: approve fails at the artifact gate
	/// (never a 409), and the auto-approve GET is a plain 200 read defaulting to false.
	@Test
	public void TestWriteRoutesReachTheirNextGate() throws Exception {
		BaseRecord book = createBareBook(PbBookTypeEnumType.STORY);
		String bid = book.get(FieldNames.FIELD_OBJECT_ID);
		try {
			Response a = service.approveArtifact(bid, "no-such-artifact", requestAs(user));
			assertNotEquals("approve on the bare fixture is refused by its own gate, never a 409", 409, a.getStatus());
			assertEquals("approve of an unknown artifact is 404", 404, a.getStatus());
			Response g3 = service.getAutoApprove(bid, requestAs(user));
			assertEquals("auto-approve GET on the bare fixture is a plain 200 read", 200, g3.getStatus());
			assertEquals(Boolean.FALSE, body(g3).get("autoApprove"));
		}
		finally {
			deleteBareBook(book);
		}
	}

	/// P1-10 S0(d): the impact read sits on the listStale pattern, so on the bare fixture it reaches the
	/// workflow gate; and an unregistered refModel is refused 400 by the facade before any book or
	/// workflow read.
	@Test
	public void TestAffectedByRefusesAnUnknownRefModel() throws Exception {
		BaseRecord book = createBareBook(PbBookTypeEnumType.STORY);
		String bid = book.get(FieldNames.FIELD_OBJECT_ID);
		try {
			Response r1 = service.affectedByRecord(bid, OlioModelNames.MODEL_CHAR_PERSON, "no-such-record", requestAs(user));
			assertEquals("the bare fixture has no workflow, so the read 404s at the workflow gate", 404, r1.getStatus());
			assertTrue(String.valueOf(body(r1).get("error")).contains("no workflow"));

			Response bad = service.affectedByRecord(bid, "no.such.model", "no-such-record", requestAs(user));
			assertEquals("an unregistered refModel is 400", 400, bad.getStatus());
			String msg = String.valueOf(body(bad).get("error"));
			assertTrue("400 names the refusal: " + msg, msg.contains("Unknown refModel"));

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
			deleteBareBook(book);
		}
	}

	/// §24 I-6: the request's {@code variants} override on the three render routes, through the real service methods.
	/// Two gates, in order. The transport's SHAPE check (PictureBookService.variantsOf: a JSON number that is not an
	/// integer, or a non-numeric value, is 400 "variants must be an integer") sits after the principal check and before
	/// any book read - like the refModel check, it is pure input validation and reveals nothing about any record. The
	/// facade's RANGE check (PbServiceFacade.validateVariants: 1 to the preset's maxVariants, 400 naming the range and
	/// the preset, never a silent clamp) sits after the book / update / scene gates and before the node is touched -
	/// proven on a pinned node: an out-of-range count is the 400 (variants gate first), an in-range one is the pin's 409
	/// (admitted past the variants gate and refused before anything is submitted), and no override at all is the same
	/// 409. Nothing renders here: the SD context's server is unreachable and the pin stops every admitted call.
	@Test
	public void TestVariantsOverrideGates() throws Exception {
		final String NIL = "00000000-0000-0000-0000-000000000000";
		final jakarta.servlet.ServletContext sd = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", "http://127.0.0.1:1"));
		BaseRecord book = createBareBook(PbBookTypeEnumType.STORY);
		String bid = book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord scene = null;
		BaseRecord node = null;
		try {
			/// Shape: 400 before the book read, on every render route, for a non-integral number and a non-numeric string;
			/// a digit string is accepted (it reaches the book gate: 404 on the nil book).
			for(String bad : new String[] { "{\"variants\":\"abc\"}", "{\"variants\":2.5}", "{\"variants\":true}" }) {
				Response r1 = service.renderScene(NIL, "no-such-scene", false, bad, requestAs(user), sd);
				assertEquals("render: shape 400 before the book read for " + bad, 400, r1.getStatus());
				assertTrue(String.valueOf(body(r1).get("error")).startsWith("variants must be an integer"));
				Response r2 = service.renderPlanScene(NIL, "no-such-scene", bad, requestAs(user), sd);
				assertEquals("render-plan: shape 400 for " + bad, 400, r2.getStatus());
				assertTrue(String.valueOf(body(r2).get("error")).startsWith("variants must be an integer"));
				Response r3 = service.renderAll(NIL, bad, requestAs(user), sd);
				assertEquals("render-all: shape 400 for " + bad, 400, r3.getStatus());
				assertTrue(String.valueOf(body(r3).get("error")).startsWith("variants must be an integer"));
			}
			Response digits = service.renderScene(NIL, "no-such-scene", false, "{\"variants\":\"2\"}", requestAs(user), sd);
			assertEquals("a digit string is an accepted shape, so the nil book is the 404", 404, digits.getStatus());
			assertEquals("Book not found", String.valueOf(body(digits).get("error")));
			/// Range needs the book, so an unknown book with an out-of-range count is still the book's 404.
			Response unknownBook = service.renderScene(NIL, "no-such-scene", false, "{\"variants\":99}", requestAs(user), sd);
			assertEquals("the book gate precedes the range check", 404, unknownBook.getStatus());
			/// Principal first: no principal is 401 even with a malformed count (or a malformed body) on all three routes.
			assertEquals(401, service.renderScene(bid, "x", false, "{\"variants\":\"abc\"}", new HttpServletRequestMock(), sd).getStatus());
			assertEquals(401, service.renderPlanScene(bid, "x", "not json", new HttpServletRequestMock(), sd).getStatus());
			assertEquals(401, service.renderAll(bid, "{\"variants\":2.5}", new HttpServletRequestMock(), sd).getStatus());

			/// Range: a scene of the bare book, plus its workflow and RENDER_PLAN node made through PbGraphUtil (the
			/// authorized write path, TestPictureBookCanvasRoute's fixture) in the owner's own group - the facade's
			/// create-on-first-use would put them under the world tree a bare book does not have. The node carries the
			/// scene's deterministic render-plan handle, so the render routes find it rather than create one, and it is
			/// pinned through the real pin route.
			scene = PbBookUtil.createScene(user, book, 0, "Page 0", GROUP_PATH);
			assertNotNull("scene fixture is null", scene);
			String sid = scene.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord read = PbBookUtil.readBook(user, bid, orgId);
			assertNotNull("the bare book must read back for its owner", read);
			/// A scene render requires a described style (PbServiceFacade.STYLE_REQUIRED_MESSAGE, 409), checked after the
			/// variants range and before the pin; given through the production style PUT path as the owner.
			describeStyle(bid);
			BaseRecord wf = org.cote.accountmanager.olio.picturebook.PbGraphUtil.getCreateWorkflow(user, read, GROUP_PATH);
			assertNotNull("workflow fixture is null", wf);
			node = org.cote.accountmanager.olio.picturebook.PbGraphUtil.addNode(user, wf,
				org.cote.accountmanager.olio.picturebook.PbPipelineUtil.renderPlanHandle(sid),
				org.cote.accountmanager.schema.type.PbNodeTypeEnumType.RENDER_PLAN, GROUP_PATH, 0);
			assertNotNull("node fixture is null", node);
			String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
			Response pin = service.pinNode(bid, nodeOid, "{\"pinned\":true}", requestAs(user));
			assertEquals("pin is 200: " + pin.getEntity(), 200, pin.getStatus());
			Response wfView = service.getWorkflow(bid, requestAs(user));
			assertEquals("workflow view is 200: " + wfView.getEntity(), 200, wfView.getStatus());
			@SuppressWarnings("unchecked")
			java.util.List<Map<String, Object>> nodes = (java.util.List<Map<String, Object>>) body(wfView).get("nodes");
			assertNotNull("workflow view carries nodes", nodes);
			assertEquals("the one node", 1, nodes.size());
			assertEquals(nodeOid, String.valueOf(nodes.get(0).get("objectId")));
			assertEquals("the node reads back pinned", Boolean.TRUE, nodes.get(0).get("pinned"));

			/// The preset the range is checked against is the one the plan is built under - the bare book has no style
			/// chain, so it is the resolver's default; the message names that preset and its ceiling.
			org.cote.accountmanager.olio.illustration.QualityPresets.Preset preset =
				org.cote.accountmanager.olio.picturebook.PbRenderPlanUtil.effectivePreset(user,
					PbBookUtil.readBook(user, bid, orgId), null);
			int max = preset.getMaxVariants();
			assertTrue("the preset declares a ceiling of at least 2 (" + max + ")", max >= 2);
			String expected = "variants must be an integer from 1 to " + max + " for preset " + preset.getName().name() + "; got ";
			for(int bad : new int[] { max + 1, 99, 0, -3 }) {
				Response r1 = service.renderScene(bid, sid, false, "{\"variants\":" + bad + "}", requestAs(user), sd);
				assertEquals("render: out-of-range " + bad + " is 400 (before the pin's 409): " + r1.getEntity(), 400, r1.getStatus());
				assertEquals(expected + bad, String.valueOf(body(r1).get("error")));
				Response r2 = service.renderPlanScene(bid, sid, "{\"variants\":" + bad + "}", requestAs(user), sd);
				assertEquals("render-plan: out-of-range " + bad + " is 400: " + r2.getEntity(), 400, r2.getStatus());
				assertEquals(expected + bad, String.valueOf(body(r2).get("error")));
				Response r3 = service.renderAll(bid, "{\"variants\":" + bad + "}", requestAs(user), sd);
				assertEquals("render-all: out-of-range " + bad + " is 400: " + r3.getEntity(), 400, r3.getStatus());
				assertEquals(expected + bad, String.valueOf(body(r3).get("error")));
			}
			/// Admitted past the range gate: an in-range count (as a number and as a digit string), and no override at
			/// all, all stop at the pin - 409, nothing submitted.
			for(String okBody : new String[] { "{\"variants\":2}", "{\"variants\":\"" + max + "\"}", "{\"variants\":1}", "{}", "" }) {
				Response r1 = service.renderScene(bid, sid, false, okBody, requestAs(user), sd);
				assertEquals("render: in-range " + okBody + " reaches the pin gate: " + r1.getEntity(), 409, r1.getStatus());
				assertTrue(String.valueOf(body(r1).get("error")).contains("pinned"));
				Response r2 = service.renderPlanScene(bid, sid, okBody, requestAs(user), sd);
				assertEquals("render-plan: in-range " + okBody + " reaches the pin gate: " + r2.getEntity(), 409, r2.getStatus());
				assertTrue(String.valueOf(body(r2).get("error")).contains("pinned"));
			}
		}
		finally {
			if(node != null) {
				assertTrue("cleanup: node", IOSystem.getActiveContext().getAccessPoint().delete(user, node));
			}
			BaseRecord workflow = org.cote.accountmanager.olio.picturebook.PbGraphUtil.findWorkflow(user, book);
			if(workflow != null) {
				assertTrue("cleanup: workflow", IOSystem.getActiveContext().getAccessPoint().delete(user, workflow));
			}
			if(scene != null) {
				assertTrue("cleanup: scene", IOSystem.getActiveContext().getAccessPoint().delete(user, scene));
			}
			deleteBareBook(book);
		}
	}

	/// §24 I-8: the background-only regenerate route (POST /{book}/scene/{scene}/regenerate-background), through the
	/// real service method. Gate order: the principal check precedes the body parse (no principal + malformed body is
	/// 401, so an unauthenticated caller learns nothing); a malformed body is 400 "Malformed request body" before any
	/// book read (nil book); an unknown book is 404 "Book not found" for an empty object, an empty body and no body;
	/// an unknown scene of a readable book is the scene gate's 404. Then on the bare fixture, with the scene's
	/// RENDER_PLAN node made through PbGraphUtil as TestVariantsOverrideGates does: pinned is the render routes' 409
	/// "pinned" (the same gate, in the same place); unpinned with no selected final is this route's own 409 "render it
	/// first" (the executor repeats that check at run time); and a second scene linked through sceneNode to a LANDSCAPE
	/// node is 409 "not a RENDER_PLAN node" - a regenerate keeps actors, and a page node has no actor passes to keep.
	/// Nothing is submitted: every admitted call stops at a gate, and the SD context's server is unreachable anyway.
	@Test
	public void TestRegenerateBackgroundGates() throws Exception {
		final String NIL = "00000000-0000-0000-0000-000000000000";
		final jakarta.servlet.ServletContext sd = initParams(Map.of("sd.server.apiType", "SWARM", "sd.server", "http://127.0.0.1:1"));
		BaseRecord book = createBareBook(PbBookTypeEnumType.STORY);
		String bid = book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord scene = null;
		BaseRecord scene2 = null;
		BaseRecord node = null;
		BaseRecord pageNode = null;
		try {
			/// Principal first, before the body is parsed.
			assertEquals("no principal + malformed body is 401", 401,
				service.regenerateSceneBackground(bid, "x", "not json", new HttpServletRequestMock(), sd).getStatus());
			assertEquals("no principal + nil book is 401", 401,
				service.regenerateSceneBackground(NIL, "x", "{}", new HttpServletRequestMock(), sd).getStatus());
			/// Body shape before any book read.
			Response bad = service.regenerateSceneBackground(NIL, "no-such-scene", "not json", requestAs(user), sd);
			assertEquals("malformed body is 400 before the book read: " + bad.getEntity(), 400, bad.getStatus());
			assertEquals("Malformed request body", String.valueOf(body(bad).get("message")));
			/// Book gate: an empty object, an empty body and no body are all accepted shapes, so the nil book is the 404.
			for(String okBody : new String[] { "{}", "", null }) {
				Response r = service.regenerateSceneBackground(NIL, "no-such-scene", okBody, requestAs(user), sd);
				assertEquals("unknown book is 404 for body " + okBody + ": " + r.getEntity(), 404, r.getStatus());
				assertEquals("Book not found", String.valueOf(body(r).get("error")));
			}
			/// Scene gate on a readable book.
			Response noScene = service.regenerateSceneBackground(bid, NIL, "{}", requestAs(user), sd);
			assertEquals("unknown scene of a readable book is 404: " + noScene.getEntity(), 404, noScene.getStatus());
			assertTrue(String.valueOf(body(noScene).get("error")).contains("not found in this book"));

			/// The scene, its workflow and its RENDER_PLAN node in the owner's own group (the facade's create-on-first-use
			/// would put them under the world tree a bare book does not have); the node carries the scene's deterministic
			/// render-plan handle so the route finds it rather than creates one.
			scene = PbBookUtil.createScene(user, book, 0, "Page 0", GROUP_PATH);
			assertNotNull("scene fixture is null", scene);
			String sid = scene.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord read = PbBookUtil.readBook(user, bid, orgId);
			assertNotNull("the bare book must read back for its owner", read);
			/// A scene render requires a described style (PbServiceFacade.STYLE_REQUIRED_MESSAGE, 409), checked after the
			/// variants range and before the pin; given through the production style PUT path as the owner.
			describeStyle(bid);
			BaseRecord wf = org.cote.accountmanager.olio.picturebook.PbGraphUtil.getCreateWorkflow(user, read, GROUP_PATH);
			assertNotNull("workflow fixture is null", wf);
			node = org.cote.accountmanager.olio.picturebook.PbGraphUtil.addNode(user, wf,
				org.cote.accountmanager.olio.picturebook.PbPipelineUtil.renderPlanHandle(sid),
				org.cote.accountmanager.schema.type.PbNodeTypeEnumType.RENDER_PLAN, GROUP_PATH, 0);
			assertNotNull("node fixture is null", node);
			String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);

			/// Pinned: the render routes' gate, in the same place - before this route's own refusals.
			Response pin = service.pinNode(bid, nodeOid, "{\"pinned\":true}", requestAs(user));
			assertEquals("pin is 200: " + pin.getEntity(), 200, pin.getStatus());
			Response pinned = service.regenerateSceneBackground(bid, sid, "{}", requestAs(user), sd);
			assertEquals("a pinned node is 409 before the no-render check: " + pinned.getEntity(), 409, pinned.getStatus());
			assertTrue(String.valueOf(body(pinned).get("error")).contains("pinned"));
			Response unpin = service.pinNode(bid, nodeOid, "{\"pinned\":false}", requestAs(user));
			assertEquals("unpin is 200: " + unpin.getEntity(), 200, unpin.getStatus());

			/// Unpinned, never rendered: this route's own 409 - the message the executor repeats at run time.
			for(String okBody : new String[] { "{}", "", null }) {
				Response noRender = service.regenerateSceneBackground(bid, sid, okBody, requestAs(user), sd);
				assertEquals("no selected render is 409 for body " + okBody + ": " + noRender.getEntity(), 409, noRender.getStatus());
				assertEquals("Scene has no selected render to regenerate the background of; render it first",
					String.valueOf(body(noRender).get("error")));
			}

			/// A scene whose linked node is not a RENDER_PLAN node (a ChapBook page's LANDSCAPE node, linked through
			/// sceneNode the way the page path does): 409 naming the type, before the no-render check.
			scene2 = PbBookUtil.createScene(user, book, 1, "Page 1", GROUP_PATH);
			assertNotNull("scene 2 fixture is null", scene2);
			String sid2 = scene2.get(FieldNames.FIELD_OBJECT_ID);
			pageNode = org.cote.accountmanager.olio.picturebook.PbGraphUtil.addNode(user, wf,
				org.cote.accountmanager.olio.picturebook.PbPipelineUtil.landscapeHandle(sid2),
				org.cote.accountmanager.schema.type.PbNodeTypeEnumType.LANDSCAPE, GROUP_PATH, 1);
			assertNotNull("page node fixture is null", pageNode);
			BaseRecord link = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
				new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_PB_SCENE_NODE });
			link.set(FieldNames.FIELD_ID, scene2.get(FieldNames.FIELD_ID));
			link.set(FieldNames.FIELD_OBJECT_ID, sid2);
			link.set(OlioFieldNames.FIELD_PB_SCENE_NODE, pageNode);
			assertNotNull("scene 2 links its page node", IOSystem.getActiveContext().getAccessPoint().update(user, link));
			BaseRecord back = PbBookUtil.readScene(user, sid2, orgId);
			assertNotNull("scene 2 re-read", back);
			BaseRecord linked = back.get(OlioFieldNames.FIELD_PB_SCENE_NODE);
			assertNotNull("scene 2 reads back linked", linked);
			Long pageNodeId = pageNode.get(FieldNames.FIELD_ID);
			Long linkedId = linked.get(FieldNames.FIELD_ID);
			assertEquals("scene 2 links the page node", pageNodeId, linkedId);
			Response notPlan = service.regenerateSceneBackground(bid, sid2, "{}", requestAs(user), sd);
			assertEquals("a LANDSCAPE scene node is 409: " + notPlan.getEntity(), 409, notPlan.getStatus());
			String msg = String.valueOf(body(notPlan).get("error"));
			assertTrue("409 names the type: " + msg, msg.contains("is a LANDSCAPE, not a RENDER_PLAN node"));
			/// Gate order held: the RENDER_PLAN node is still unpinned and untouched - no run, no attempt.
			BaseRecord after = org.cote.accountmanager.olio.picturebook.PbGraphUtil.readNode(user, nodeOid, orgId);
			assertNotNull("node re-read", after);
			assertNotEquals("the node was never pinned by the route", Boolean.TRUE, after.get(OlioFieldNames.FIELD_PB_PINNED));
		}
		finally {
			if(pageNode != null) {
				assertTrue("cleanup: page node", IOSystem.getActiveContext().getAccessPoint().delete(user, pageNode));
			}
			if(node != null) {
				assertTrue("cleanup: node", IOSystem.getActiveContext().getAccessPoint().delete(user, node));
			}
			BaseRecord workflow = org.cote.accountmanager.olio.picturebook.PbGraphUtil.findWorkflow(user, book);
			if(workflow != null) {
				assertTrue("cleanup: workflow", IOSystem.getActiveContext().getAccessPoint().delete(user, workflow));
			}
			if(scene2 != null) {
				assertTrue("cleanup: scene 2", IOSystem.getActiveContext().getAccessPoint().delete(user, scene2));
			}
			if(scene != null) {
				assertTrue("cleanup: scene", IOSystem.getActiveContext().getAccessPoint().delete(user, scene));
			}
			deleteBareBook(book);
		}
	}

	private static jakarta.servlet.ServletContext initParams(Map<String, String> params) {
		return (jakarta.servlet.ServletContext) java.lang.reflect.Proxy.newProxyInstance(
			TestPbRouteGateOrder.class.getClassLoader(),
			new Class<?>[] { jakarta.servlet.ServletContext.class },
			(proxy, method, args) -> {
				if("getInitParameter".equals(method.getName()) && args != null && args.length == 1) {
					return params.get(String.valueOf(args[0]));
				}
				Class<?> rt = method.getReturnType();
				if(rt == boolean.class) return false;
				if(rt == int.class) return 0;
				if(rt == long.class) return 0L;
				if(rt.equals(java.util.Enumeration.class)) return java.util.Collections.emptyEnumeration();
				return null;
			}
		);
	}
}
