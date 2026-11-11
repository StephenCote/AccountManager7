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

	private void deleteBareBook(BaseRecord book) {
		if(book != null) {
			assertTrue("Failed to delete the bare book fixture",
				IOSystem.getActiveContext().getAccessPoint().delete(user, book));
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
}
