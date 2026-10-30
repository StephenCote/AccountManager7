package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 1, P1-8 (design §28.1 Stage 1), Phase 2, P2-6 (§28.1 Stage 2 for ChapBook) and Phase 7,
 * P7-1 (§28.1 Stage 2 for PictureBook): the boot-pinned {@link PbRenderPath} switch - one default per product,
 * both {@code v3} with {@code v1} as the rollback value - the {@link PbServiceFacade} gate it drives, and the
 * PB1 entry-point refusal ({@code requirePb1Entry}) that retires PB1 under the PictureBook default.
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

	/**
	 * Absent (null / blank) pins the product default - v3 for both book products since §28.1 Stage 2 (ChapBook
	 * P2-6, PictureBook P7-1); the call never throws.
	 */
	@Test
	public void case01_absentIsTheProductDefault() {
		assertEquals(PbRenderPath.V3, PbRenderPath.PICTUREBOOK_DEFAULT);
		assertEquals(PbRenderPath.V3, PbRenderPath.CHAPBOOK_DEFAULT);
		PbRenderPath.configure(null, null);
		assertEquals(PbRenderPath.V3, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V3, PbRenderPath.chapBook());
		PbRenderPath.configure("", "   ");
		assertEquals(PbRenderPath.V3, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V3, PbRenderPath.chapBook());
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

	/**
	 * An unrecognised value pins the product default (a typo must never silently move a product off its
	 * default: since P7-1 neither product can be rolled back to v1 by a misspelling) and never throws.
	 */
	@Test
	public void case03_unrecognisedIsTheProductDefault() {
		PbRenderPath.configure("v2", "x");
		assertEquals(PbRenderPath.V3, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V3, PbRenderPath.chapBook());
		PbRenderPath.configure("v1", "graph");
		assertEquals(PbRenderPath.V1, PbRenderPath.pictureBook());
		assertEquals("the bad chapbook value pins the chapbook default", PbRenderPath.V3, PbRenderPath.chapBook());
		PbRenderPath.configure("graph", "v1");
		assertEquals("the bad picturebook value must not borrow the chapbook value", PbRenderPath.V3, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V1, PbRenderPath.chapBook());
	}

	/**
	 * P2-6 (§28.1 Stage 2): {@code chapbook.renderPath=v1} is the rollback value. It is honoured - the facade
	 * refuses a CHAPBOOK book with the 409 that names the key and the env var - and it is independent of the
	 * PictureBook value in both directions; absent restores the v3 default.
	 */
	@Test
	public void case10_chapBookV1IsRollback() throws Exception {
		BaseRecord chap = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		chap.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.CHAPBOOK.toString());
		assertEquals(PbRenderPath.V3, PbRenderPath.chapBook());
		assertTrue("the default admits ChapBook on v3", PbRenderPath.isV3(chap));
		PbRenderPath.requireV3(chap);

		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V1);
		assertEquals(PbRenderPath.V1, PbRenderPath.chapBook());
		assertEquals("rollback leaves PictureBook alone", PbRenderPath.V3, PbRenderPath.pictureBook());
		assertFalse(PbRenderPath.isV3(chap));
		try {
			PbRenderPath.requireV3(chap);
			fail("CHAPBOOK under the rollback value must be refused");
		}
		catch(PictureBookException e) {
			assertEquals(409, e.getStatus());
			assertTrue(e.getMessage(), e.getMessage().startsWith(PbRenderPath.REFUSAL_PREFIX));
			assertTrue(e.getMessage(), e.getMessage().contains(PbRenderPath.CHAPBOOK_CONFIG_KEY + "=" + PbRenderPath.V3));
			assertTrue(e.getMessage(), e.getMessage().contains("CHAPBOOK_RENDER_PATH"));
		}

		PbRenderPath.configure(PbRenderPath.V1, null);
		assertEquals("absent after a rollback restores the chapbook default", PbRenderPath.V3, PbRenderPath.chapBook());
		assertEquals("an explicit picturebook v1 is honoured (rollback)", PbRenderPath.V1, PbRenderPath.pictureBook());
		assertTrue(PbRenderPath.isV3(chap));
	}

	/**
	 * P7-1 (§28.1 Stage 2 for PictureBook): the default retires PB1 - {@code isPb1Retired()} is true and
	 * {@code requirePb1Entry} refuses with a 409 whose message carries {@code PB1_REPLACED_PREFIX}, the entry
	 * label, the replacement, the key=v3 and the env var; {@code picturebook.renderPath=v1} is the rollback and
	 * re-admits the entry point without touching ChapBook; absent restores the retirement.
	 */
	@Test
	public void case11_pictureBookV1IsRollbackAndPb1EntryRefuses() {
		assertEquals(PbRenderPath.V3, PbRenderPath.pictureBook());
		assertTrue("the default retires PB1", PbRenderPath.isPb1Retired());
		try {
			PbRenderPath.requirePb1Entry("POST /scene/{id}/generate", "POST /{book}/run");
			fail("a PB1 entry point under v3 must be refused");
		}
		catch(PictureBookException e) {
			assertEquals(409, e.getStatus());
			assertTrue(e.getMessage(), e.getMessage().startsWith(PbRenderPath.PB1_REPLACED_PREFIX));
			assertTrue(e.getMessage(), e.getMessage().contains("POST /scene/{id}/generate"));
			assertTrue(e.getMessage(), e.getMessage().contains("Use POST /{book}/run instead"));
			assertTrue(e.getMessage(), e.getMessage().contains(PbRenderPath.PICTUREBOOK_CONFIG_KEY + "=" + PbRenderPath.V3));
			assertTrue(e.getMessage(), e.getMessage().contains(PbRenderPath.PICTUREBOOK_CONFIG_KEY + "=" + PbRenderPath.V1));
			assertTrue(e.getMessage(), e.getMessage().contains("PICTUREBOOK_RENDER_PATH"));
			assertFalse("a PB1 refusal is not a requireV3 refusal", e.getMessage().startsWith(PbRenderPath.REFUSAL_PREFIX));
		}

		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3);
		assertEquals(PbRenderPath.V1, PbRenderPath.pictureBook());
		assertEquals("rollback leaves ChapBook alone", PbRenderPath.V3, PbRenderPath.chapBook());
		assertFalse("rollback re-enables PB1", PbRenderPath.isPb1Retired());
		PbRenderPath.requirePb1Entry("POST /scene/{id}/generate", "POST /{book}/run");

		PbRenderPath.configure(null, PbRenderPath.V3);
		assertEquals("absent after a rollback restores the picturebook default", PbRenderPath.V3, PbRenderPath.pictureBook());
		assertTrue(PbRenderPath.isPb1Retired());
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
	 * product the switch was introduced for at Stage 1).
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

	// ───────────────────── PB1 entry refusal, live (P7-2, §28.1 Stage 2) ─────────────────────

	/** A syntactically valid SD host nothing listens on: reaching the SD stage would surface as a connection error. */
	private static final String DEAD_SD = "http://127.0.0.1:1";
	private static final String STRANGER = "testUser2";

	/**
	 * Owner under the default {@code v3}: each of the three retired PB1 entries refuses with 409 "replaced by V3",
	 * naming the entry and its V3 replacement, before any SD / LLM / persist work. The fixture is the PB1 storage
	 * shape ({@code ~/Data/PictureBooks/<book>/Scenes/<note>}), not the pb2c-graph pb.book, whose oid does not
	 * resolve through resolveBookGroupEither. A dead SD host proves generate never reached SD; a still-null
	 * getBookSdConfig proves settings never persisted. An unknown oid is still 404 (existence sits before the gate),
	 * and prepare's unchanged tolerance of an unknown id (skip, not refuse) means an empty-in-effect batch returns.
	 * Under the {@code v1} rollback the settings entry is admitted: it returns and, with no config, persists nothing.
	 */
	@Test
	public void case12_ownerPb1EntriesRefuseUnderV3BeforeAnyWork() throws Exception {
		BaseRecord u = user();
		Pb1Book b = pb1Book(u, "p72-owner-" + UUID.randomUUID().toString().substring(0, 8));
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
		assertTrue("precondition: PB1 retired under v3", PbRenderPath.isPb1Retired());

		expectPb1Replaced("generateSceneImage(owner)", PictureBookUtil.PB1_ENTRY_GENERATE, PictureBookUtil.PB1_REPLACEMENT_GENERATE,
			() -> PictureBookUtil.generateSceneImage(u, b.sceneOid, new PictureBookUtil.SceneGenerationParams(), "SWARM", DEAD_SD));
		expectPb1Replaced("prepareSceneImagePrompts(owner)", PictureBookUtil.PB1_ENTRY_PREPARE, PictureBookUtil.PB1_REPLACEMENT_PREPARE,
			() -> PictureBookUtil.prepareSceneImagePrompts(u, Arrays.asList(b.sceneOid), null, null, null));
		expectPb1Replaced("setBookSdConfig(owner)", PictureBookUtil.PB1_ENTRY_SETTINGS, PictureBookUtil.PB1_REPLACEMENT_SETTINGS,
			() -> PictureBookUtil.setBookSdConfig(u, b.bookGroupOid, cfg, null));
		assertNull("the refused settings write must not have persisted a config",
			PictureBookUtil.getBookSdConfig(u, b.bookGroupOid));

		/// Existence before the gate: an unknown id is 404 from the lookup (generate, settings) or skipped (prepare).
		String none = "no-such-" + System.nanoTime();
		assertEquals("generate(unknown scene) under v3", 404, expectNotPb1Replaced("generate(unknown)",
			() -> PictureBookUtil.generateSceneImage(u, none, new PictureBookUtil.SceneGenerationParams(), "SWARM", DEAD_SD)));
		assertEquals("settings(unknown book) under v3", 404, expectNotPb1Replaced("settings(unknown)",
			() -> PictureBookUtil.setBookSdConfig(u, none, cfg, null)));
		PictureBookUtil.prepareSceneImagePrompts(u, Arrays.asList(none), null, null, null);

		/// Rollback admits the entry (to whatever comes next - here nothing, since no config is supplied).
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3);
		try {
			assertFalse("precondition: PB1 admitted under v1", PbRenderPath.isPb1Retired());
			PictureBookUtil.setBookSdConfig(u, b.bookGroupOid, null, null);
			assertNull("v1 settings call with no config persists nothing", PictureBookUtil.getBookSdConfig(u, b.bookGroupOid));
		}
		finally {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
		}
	}

	/**
	 * Stranger (a second user of the org): the PB1 refusal sits AFTER authorization and existence (ledger §6.C 215
	 * a). With no grant the scene note and the book group are unreadable to B, so generate and settings answer 404
	 * (absence, not a product switch) and prepare skips the unreadable id. With Read+Update on the Scenes group only
	 * (the TestPictureBookSceneAuthz shape) B reaches the book-level check and gets 403 from generate and prepare,
	 * while settings - keyed on the book group B still cannot read - stays 404. None of them ever carries the 409.
	 */
	@Test
	public void case13_strangerIsDeniedBeforeThePb1Refusal() throws Exception {
		BaseRecord u = user();
		BaseRecord b2 = stranger();
		Pb1Book b = pb1Book(u, "p72-stranger-" + UUID.randomUUID().toString().substring(0, 8));
		BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);

		assertEquals("generate(stranger, no grant)", 404, expectNotPb1Replaced("generate(stranger)",
			() -> PictureBookUtil.generateSceneImage(b2, b.sceneOid, new PictureBookUtil.SceneGenerationParams(), "SWARM", DEAD_SD)));
		assertEquals("settings(stranger, no grant)", 404, expectNotPb1Replaced("settings(stranger)",
			() -> PictureBookUtil.setBookSdConfig(b2, b.bookGroupOid, cfg, null)));
		PictureBookUtil.prepareSceneImagePrompts(b2, Arrays.asList(b.sceneOid), null, null, null);

		OrganizationContext org = getTestOrganization(ORG_A);
		IOSystem.getActiveContext().getAuthorizationUtil().setEntitlement(org.getAdminUser(), b2,
			new BaseRecord[] { b.scenesGroup }, new String[] { "Read", "Update" },
			new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
		CacheUtil.clearCache();

		assertEquals("generate(stranger, Scenes grant) must be the book-level 403, not the 409", 403,
			expectNotPb1Replaced("generate(stranger+grant)", () -> PictureBookUtil.generateSceneImage(b2, b.sceneOid,
				new PictureBookUtil.SceneGenerationParams(), "SWARM", DEAD_SD)));
		assertEquals("prepare(stranger, Scenes grant) must be the book-level 403, not the 409", 403,
			expectNotPb1Replaced("prepare(stranger+grant)", () -> PictureBookUtil.prepareSceneImagePrompts(b2,
				Arrays.asList(b.sceneOid), null, null, null)));
		assertEquals("settings(stranger, Scenes grant) stays 404 on the unreadable book group", 404,
			expectNotPb1Replaced("settings(stranger+grant)", () -> PictureBookUtil.setBookSdConfig(b2, b.bookGroupOid, cfg, null)));
		assertNull("nothing the stranger did may have persisted a config", PictureBookUtil.getBookSdConfig(u, b.bookGroupOid));
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private interface Call {
		void run();
	}

	/** The PB1 storage shape createFromScenes produces: book group, Scenes sub-group, one scene note. No LLM. */
	private static final class Pb1Book {
		BaseRecord scenesGroup;
		String bookGroupOid;
		String sceneOid;
	}

	private Pb1Book pb1Book(BaseRecord owner, String name) {
		Pb1Book b = new Pb1Book();
		long oid = orgOf(owner);
		String path = "~/Data/PictureBooks/" + name;
		BaseRecord bookGroup = IOSystem.getActiveContext().getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), oid);
		assertNotNull("book group " + path, bookGroup);
		b.bookGroupOid = bookGroup.get(FieldNames.FIELD_OBJECT_ID);
		b.scenesGroup = IOSystem.getActiveContext().getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, path + "/Scenes",
			GroupEnumType.DATA.toString(), oid);
		assertNotNull("Scenes group", b.scenesGroup);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path + "/Scenes");
		plist.parameter(FieldNames.FIELD_NAME, "Scene 1");
		BaseRecord note = null;
		try {
			note = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_NOTE, owner, null, plist);
			note.set("text", "{\"title\":\"Scene 1\",\"setting\":\"a quiet room\",\"action\":\"nothing happens\",\"mood\":\"calm\"}");
		}
		catch(Exception e) {
			fail("scene note: " + e.getMessage());
		}
		BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().create(owner, note);
		assertNotNull("scene note", scene);
		b.sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("scene objectId", b.sceneOid);
		return b;
	}

	/** 409 carrying {@link PbRenderPath#PB1_REPLACED_PREFIX}, the retired entry and the V3 replacement it names. */
	private static void expectPb1Replaced(String what, String entry, String replacement, Call call) {
		try {
			call.run();
			fail(what + " under v3 must throw the PB1 409");
		}
		catch(PictureBookException e) {
			assertEquals(what + " status", 409, e.getStatus());
			assertNotNull(what + " message", e.getMessage());
			assertTrue(what + " must start with '" + PbRenderPath.PB1_REPLACED_PREFIX + "': " + e.getMessage(),
				e.getMessage().startsWith(PbRenderPath.PB1_REPLACED_PREFIX));
			assertTrue(what + " must name the retired entry: " + e.getMessage(), e.getMessage().contains(entry));
			assertTrue(what + " must name the V3 replacement: " + e.getMessage(), e.getMessage().contains(replacement));
		}
	}

	/** The status of the PictureBookException {@code call} throws, which must not be the PB1 409. */
	private static int expectNotPb1Replaced(String what, Call call) {
		try {
			call.run();
		}
		catch(PictureBookException e) {
			assertFalse(what + " must not carry the PB1 refusal: " + e.getMessage(),
				e.getMessage() != null && e.getMessage().startsWith(PbRenderPath.PB1_REPLACED_PREFIX));
			assertFalse(what + " must not be the PB1 409", e.getStatus() == 409);
			return e.getStatus();
		}
		fail(what + " completed without throwing");
		return -1;
	}

	private BaseRecord stranger() {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord s = getCreateUser(STRANGER, org);
		assertNotNull("user " + STRANGER, s);
		return s;
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
