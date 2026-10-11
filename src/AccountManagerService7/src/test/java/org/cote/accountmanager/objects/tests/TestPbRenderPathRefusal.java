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
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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
/// The switch is process-global; every case restores it in finally.
public class TestPbRenderPathRefusal extends BaseTest {

	private static final String USER_NAME = "pbrenderpathuser";
	private static final String GROUP_PATH = "~/PbRenderPathRefusal";

	private final PictureBookService service = new PictureBookService();

	private BaseRecord user;
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
		assertNotNull("Test user is null", user);
		pb0 = PbRenderPath.pictureBook();
		cb0 = PbRenderPath.chapBook();
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
}
