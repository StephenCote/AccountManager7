package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 1, P1-8 (design §28.1 Stage 1): the boot-pinned {@link PbRenderPath} switch and the
 * {@link PbServiceFacade} gate it drives.
 * <p>
 * The resolver cases are pure. The facade cases run against the shared {@code pb2c-graph} fixture book in
 * {@code am7db} (the same book TestPbAutoApprove / TestPbReview / TestPbRenderJob use): with both products
 * pinned to {@code v1} every V3 action and every "P1-3 and later" read throws 409 with the documented prefix
 * <b>before</b> any job, write or SD call, while the Phase-0 read views stay readable; with {@code v3} the same
 * calls pass the switch. The switch is process-global: every case restores {@code v3/v3} (what the test
 * properties pin and what {@code BaseTest.setup()} re-applies on every {@code @Before}) in {@code finally}.
 * <p>
 * Product selection by {@code bookType} is exercised at the resolver level on in-memory records here and
 * end-to-end (real STORY and CHAPBOOK rows through the REST method) in Service7's TestPbRenderPathRefusal; a
 * throwaway CHAPBOOK book was deliberately not created in {@code am7db}, since PbBookUtil.createBook seeds an
 * Olio world the switch does not need.
 */
public class TestPbRenderPath extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";

	@Before
	public void renderPathSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	@After
	public void restoreV3() {
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
	}

	// ─────────────────────────────── resolver ───────────────────────────────

	/** Absent (null / blank) pins v1 for that product; the call never throws. */
	@Test
	public void case01_absentIsV1() {
		PbRenderPath.configure(null, null);
		assertEquals(PbRenderPath.V1, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V1, PbRenderPath.chapBook());
		PbRenderPath.configure("", "   ");
		assertEquals(PbRenderPath.V1, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V1, PbRenderPath.chapBook());
	}

	/** Each product is independent, and the value is trimmed and case-insensitive. */
	@Test
	public void case02_eachValueIndependentlyAndNormalised() {
		PbRenderPath.configure("v3", " V3 ");
		assertEquals(PbRenderPath.V3, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V3, PbRenderPath.chapBook());
		PbRenderPath.configure("v1", "v3");
		assertEquals(PbRenderPath.V1, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V3, PbRenderPath.chapBook());
		PbRenderPath.configure("V3", "v1");
		assertEquals(PbRenderPath.V3, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V1, PbRenderPath.chapBook());
	}

	/** An unrecognised value pins v1 (a typo must never silently enable V3) and never throws. */
	@Test
	public void case03_unrecognisedIsV1() {
		PbRenderPath.configure("v2", "x");
		assertEquals(PbRenderPath.V1, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V1, PbRenderPath.chapBook());
		PbRenderPath.configure("v3", "graph");
		assertEquals(PbRenderPath.V3, PbRenderPath.pictureBook());
		assertEquals("the bad chapbook value must not borrow the picturebook value", PbRenderPath.V1, PbRenderPath.chapBook());
	}

	/** The test properties pin v3/v3 and BaseTest re-applies them, so after setup both products read v3. */
	@Test
	public void case04_testPropertiesPinV3() {
		assertEquals(PbRenderPath.V3, testProperties.getProperty(PbRenderPath.PICTUREBOOK_CONFIG_KEY));
		assertEquals(PbRenderPath.V3, testProperties.getProperty(PbRenderPath.CHAPBOOK_CONFIG_KEY));
		assertEquals(PbRenderPath.V3, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V3, PbRenderPath.chapBook());
	}

	/**
	 * Product selection: CHAPBOOK (any case) reads the chapbook key; STORY, UNKNOWN, a null record and a record
	 * with no bookType field all read the picturebook key (the conservative default: a PictureBook is the
	 * product whose switch Stage 1 is about).
	 */
	@Test
	public void case05_keyForByBookType() throws Exception {
		BaseRecord story = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		story.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.STORY.toString());
		BaseRecord chap = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		chap.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.CHAPBOOK.toString());
		BaseRecord chapLower = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		chapLower.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, "chapbook");
		BaseRecord unknown = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		unknown.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.UNKNOWN.toString());
		BaseRecord notABook = RecordFactory.newInstance(ModelNames.MODEL_DATA);

		assertEquals(PbRenderPath.PICTUREBOOK_CONFIG_KEY, PbRenderPath.keyFor(story));
		assertEquals(PbRenderPath.CHAPBOOK_CONFIG_KEY, PbRenderPath.keyFor(chap));
		assertEquals(PbRenderPath.CHAPBOOK_CONFIG_KEY, PbRenderPath.keyFor(chapLower));
		assertEquals(PbRenderPath.PICTUREBOOK_CONFIG_KEY, PbRenderPath.keyFor(unknown));
		assertEquals(PbRenderPath.PICTUREBOOK_CONFIG_KEY, PbRenderPath.keyFor(null));
		assertEquals(PbRenderPath.PICTUREBOOK_CONFIG_KEY, PbRenderPath.keyFor(notABook));

		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3);
		assertFalse(PbRenderPath.isV3(story));
		assertTrue(PbRenderPath.isV3(chap));
		assertFalse(PbRenderPath.isV3(unknown));
		assertFalse(PbRenderPath.isV3(null));
		try {
			PbRenderPath.requireV3(story);
			fail("STORY under picturebook=v1 must be refused");
		}
		catch(PictureBookException e) {
			assertEquals(409, e.getStatus());
			assertTrue(e.getMessage(), e.getMessage().startsWith(PbRenderPath.REFUSAL_PREFIX));
			assertTrue(e.getMessage(), e.getMessage().contains(PbRenderPath.PICTUREBOOK_CONFIG_KEY));
			assertTrue(e.getMessage(), e.getMessage().contains("PICTUREBOOK_RENDER_PATH"));
		}
		PbRenderPath.requireV3(chap);

		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V1);
		assertTrue(PbRenderPath.isV3(story));
		assertFalse(PbRenderPath.isV3(chap));
		PbRenderPath.requireV3(story);
		try {
			PbRenderPath.requireV3(chap);
			fail("CHAPBOOK under chapbook=v1 must be refused");
		}
		catch(PictureBookException e) {
			assertEquals(409, e.getStatus());
			assertTrue(e.getMessage(), e.getMessage().contains(PbRenderPath.CHAPBOOK_CONFIG_KEY));
			assertTrue(e.getMessage(), e.getMessage().contains("CHAPBOOK_RENDER_PATH"));
		}
	}

	// ─────────────────────────────── facade gate, live am7db ───────────────────────────────

	/**
	 * Under v1 every V3 action AND every "P1-3 and later" read is refused with 409 + the prefix, before
	 * the next gate (so an unknown node/run/artifact id still gets the 409, never a 404 - the switch is
	 * consulted right after the book is read).
	 */
	@Test
	public void case06_facadeRefusesEveryV3EntryUnderV1() {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		String bid = bk.get(FieldNames.FIELD_OBJECT_ID);
		String none = "no-such-" + System.nanoTime();
		try {
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
			expect409("requestRegenerate", () -> PbServiceFacade.requestRegenerate(u, bid, none, null, null));
			expect409("requestSceneRender", () -> PbServiceFacade.requestSceneRender(u, bid, none, null, null));
			expect409("requestRenderAll", () -> PbServiceFacade.requestRenderAll(u, bid, null, null));
			expect409("cancelRun", () -> PbServiceFacade.cancelRun(u, bid, none));
			expect409("getRun", () -> PbServiceFacade.getRun(u, bid, none));
			expect409("resumeRun", () -> PbServiceFacade.resumeRun(u, bid, none, null, null));
			expect409("selectArtifact", () -> PbServiceFacade.selectArtifact(u, bid, none));
			expect409("approveArtifact", () -> PbServiceFacade.approveArtifact(u, bid, none));
			expect409("rejectArtifact", () -> PbServiceFacade.rejectArtifact(u, bid, none));
			expect409("listReviewQueue", () -> PbServiceFacade.listReviewQueue(u, bid, null));
			expect409("getAutoApprove", () -> PbServiceFacade.getAutoApprove(u, bid));
			expect409("setAutoApprove", () -> PbServiceFacade.setAutoApprove(u, bid, true));
			expect409("testNode", () -> PbServiceFacade.testNode(u, bid, none, null, null));

			/// A missing book is still 404 first: the switch never discloses a book the caller cannot see.
			try {
				PbServiceFacade.getRun(u, none, none);
				fail("unknown book must be 404");
			}
			catch(PictureBookException e) {
				assertEquals(404, e.getStatus());
			}
		}
		finally {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
		}
	}

	/** The Phase-0 read views are NOT gated: a v1 deployment can still inspect the graph it recorded. */
	@Test
	public void case07_phase0ReadsStayReadableUnderV1() {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		String bid = bk.get(FieldNames.FIELD_OBJECT_ID);
		try {
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1);
			Map<String, Object> wf = PbServiceFacade.workflowView(u, bid);
			assertNotNull("workflowView under v1", wf);
			assertNotNull("workflowView names the workflow", wf.get("workflowObjectId"));
			List<Map<String, Object>> stale = PbServiceFacade.listStale(u, bid);
			assertNotNull("listStale under v1", stale);
			Map<String, Object> aff = PbServiceFacade.affectedByRecord(u, bid, OlioModelNames.MODEL_CHAR_PERSON, "no-such-" + System.nanoTime());
			assertNotNull("affectedByRecord under v1", aff);
			/// nodeView / artifactView with an unknown id answer 404 (the gate after requireBook), not 409.
			for(String[] call : new String[][] { { "nodeView" }, { "artifactView" } }) {
				try {
					if("nodeView".equals(call[0])) {
						PbServiceFacade.nodeView(u, bid, "no-such-node");
					}
					else {
						PbServiceFacade.artifactView(u, bid, "no-such-artifact");
					}
					fail(call[0] + " with an unknown id must be 404");
				}
				catch(PictureBookException e) {
					assertEquals(call[0] + " must not be refused by the switch", 404, e.getStatus());
				}
			}
		}
		finally {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
		}
	}

	/** Under v3 the same entries pass the switch: reads answer, and an unknown id reaches the 404 gate behind it. */
	@Test
	public void case08_facadeAdmitsUnderV3() {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		String bid = bk.get(FieldNames.FIELD_OBJECT_ID);
		String none = "no-such-" + System.nanoTime();
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);

		Map<String, Object> aa = PbServiceFacade.getAutoApprove(u, bid);
		assertNotNull(aa);
		assertEquals(bid, aa.get("bookObjectId"));
		Map<String, Object> q = PbServiceFacade.listReviewQueue(u, bid, null);
		assertNotNull("listReviewQueue under v3", q);
		for(String[] call : new String[][] { { "getRun" }, { "approveArtifact" }, { "cancelRun" } }) {
			try {
				switch(call[0]) {
					case "getRun": PbServiceFacade.getRun(u, bid, none); break;
					case "approveArtifact": PbServiceFacade.approveArtifact(u, bid, none); break;
					default: PbServiceFacade.cancelRun(u, bid, none); break;
				}
				fail(call[0] + " with an unknown id must be 404 under v3");
			}
			catch(PictureBookException e) {
				assertEquals(call[0] + " under v3 must pass the switch and 404 at the next gate", 404, e.getStatus());
				assertFalse(call[0] + " must not carry the refusal text under v3",
					e.getMessage() != null && e.getMessage().startsWith(PbRenderPath.REFUSAL_PREFIX));
			}
		}
	}

	/** ChapBook product under a split switch: the STORY fixture follows picturebook.renderPath only. */
	@Test
	public void case09_storyBookFollowsPictureBookKeyOnly() {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		String bid = bk.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("fixture precondition", PbRenderPath.PICTUREBOOK_CONFIG_KEY, PbRenderPath.keyFor(bk));
		try {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V1);
			assertNotNull("pb=v3/cb=v1 admits the STORY book", PbServiceFacade.getAutoApprove(u, bid));
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3);
			expect409("pb=v1/cb=v3 refuses the STORY book", () -> PbServiceFacade.getAutoApprove(u, bid));
		}
		finally {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private interface Call {
		void run();
	}

	private static void expect409(String what, Call call) {
		try {
			call.run();
			fail(what + " under v1 must throw 409");
		}
		catch(PictureBookException e) {
			assertEquals(what + " status", 409, e.getStatus());
			assertNotNull(what + " message", e.getMessage());
			assertTrue(what + " message must start with '" + PbRenderPath.REFUSAL_PREFIX + "': " + e.getMessage(),
				e.getMessage().startsWith(PbRenderPath.REFUSAL_PREFIX));
		}
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("organization " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("user " + TEST_USER, u);
		return u;
	}

	private long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", p);
		return p;
	}

	private BaseRecord book(BaseRecord u) {
		BaseRecord bk = PbBookUtil.findBookBySlug(u, SLUG, orgOf(u));
		if(bk == null) {
			bk = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 2c graph fixture");
		}
		assertNotNull("book " + SLUG, bk);
		/// The facade's workflow gate needs the graph to exist; the sibling tests create it the same way.
		assertNotNull("workflow", PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG)));
		return bk;
	}
}
