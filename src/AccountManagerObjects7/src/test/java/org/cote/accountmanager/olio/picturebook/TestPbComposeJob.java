package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.enumWord;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.fkId;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.idOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.oidOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.orgOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.thread.AsyncJobWork;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-4: {@link PbComposeJobUtil} - the whole-book {@code pb.compose} job and the
 * {@code pb.proposeLocations} job behind {@code POST .../book/{id}/compose} and {@code .../propose-locations}.
 * <p>
 * Live {@code am7db} as {@code testUser1} in {@code /Development/World Building}; every case creates its own
 * throwaway book and deletes it. Cases 01-05 need no LLM (the submit path refuses, tallies, dedupes, claims
 * and cancels before any worker runs); 06-09 drive the job against the real LLM the gate resolved
 * ({@code OlioTestUtil.getOllamaOpenAIConfig}); 10 runs the job with no chat config named, so it composes
 * through the organization's {@code generalChat} when one resolves and otherwise writes the MIGRATED fallback
 * without an LLM call; none touches a live SD server (09 uses a dead one on purpose). Timings are logged as
 * MEASURED, never asserted.
 * <ol>
 * <li>A book with no scenes is not submitted: {@code submitted:false}, "The book has no scenes".</li>
 * <li>A CHAPBOOK book is not composed: {@code submitted:false}, the ChapBook reason, {@code pagesSkipped} = pages.</li>
 * <li>An unknown chat config name is a 404 from both submit entries before anything is queued.</li>
 * <li>Quick start without an SD backend is a 500 (blank server; unknown API type) before anything is queued.</li>
 * <li>One live job per book: a saturated pool holds the compose QUEUED; a resubmit is {@code reused:true};
 * the owner's propose submit is a 409 that names the job; a stranger's is a 409 that names nothing;
 * {@code requireNotComposing} is a 409; a cancel while queued closes CANCELLED "Cancelled before starting",
 * releases the claim and writes no composition; a propose job then holds the claim the same way, the owner's
 * compose submit is a 409 naming it, and the registry's per-principal cap answers 429 on a second book.</li>
 * <li>Live: two scenes visited in story order and every one written (LLM, or MIGRATED when the live model
 * returned no parseable staging - the §29 fallback, MEASURED not asserted), each outcome's composition is the
 * scene's row, the location pass ran; a plain resubmit picks up exactly the MIGRATED rows until every scene is
 * LLM-composed, then is "Every scene is composed"; a forced resubmit re-composes each row (revision bumps) or
 * reports it as a hardFailure left untouched.</li>
 * <li>Live: a cancel that lands mid-compose closes CANCELLED with the partial result, {@code NOTE_RESUME},
 * the interrupted scene "cancelled" without a composition, the claim released; a fresh submit resumes.</li>
 * <li>Live: the {@code pb.proposeLocations} job alone completes with the location-pass DTO, and while it is
 * queued the owner's compose submit is a 409 naming it.</li>
 * <li>Live LLM, no SD: {@code quickStart} against an SD server that accepts no connections composes every
 * scene, refuses every DRAFT render before rendering (503 "Renderer inventory unavailable", never the URL),
 * tallies {@code quickStartFailed} per written scene with {@code quickStartRendered} 0, keeps going, runs
 * the location pass and completes - a failed draft never fails the job (§9.1 "Quick start").</li>
 * <li>A scene is current only for a composition in its own group (the book group) - the shape the job loop
 * resolves ({@code findSceneComposition}: scene + the scene's group). A SCENE-purpose row in another group of
 * the same organization that points at the scene and the book is excluded by the group-constrained
 * whole-book list, so the tally still submits; the job then writes the scene's own row and the list resolves
 * the scene to it rather than to the older foreign row.</li>
 * </ol>
 */
public class TestPbComposeJob extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String STRANGER_USER = "pbComposeStranger";
	private static final String CFG_NAME = "P44 Compose Job Cfg";
	/// An SD server that accepts no connections, so the Quick-start DRAFT render is refused before rendering.
	private static final String DEAD_SD_SERVER = "http://127.0.0.1:1";

	/// Poll budgets are generous on purpose: a timing is never a pass/fail threshold here.
	private static final long JOB_TIMEOUT_MS = 20L * 60L * 1000L;
	private static final long LOCAL_TIMEOUT_MS = 5L * 60L * 1000L;
	private static final long POLL_MS = 250L;
	private static final long PHASE_POLL_MS = 2_000L;

	private static final String SETTING_1 = "the spiral stair of the Varrow Point lighthouse, salt-damp stone, a storm rising outside";
	private static final String ACTION_1 = "Ilse climbs with the lamp oil while Corin follows two steps behind";
	private static final String TEXT_1 = "Ilse Brandt took the stair two at a time, the oil can knocking against her hip. "
		+ "Corin Hale followed with the lantern, its light swinging across the salt-damp stone. Below them the door "
		+ "banged in the wind and the first thunder rolled in off the water. 'Keep up,' she said, 'the lamp has been "
		+ "dark an hour already.'";
	private static final String SETTING_2 = "the lamp room at the top of the Varrow Point lighthouse, brass and glass, lightning beyond the panes";
	private static final String ACTION_2 = "Ilse trims the wick and lights the great lamp while Corin watches the ship on the rocks";
	private static final String TEXT_2 = "The lamp room was all brass and glass and the smell of cold oil. Ilse Brandt knelt at the "
		+ "burner, trimming the wick with a steady hand, while Corin Hale pressed his face to the pane. Lightning showed "
		+ "him the schooner on the Varrow rocks, her foremast gone. 'Now,' Ilse said, and struck the match, and the "
		+ "great lens turned and threw its beam out over the wreck.";

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── 01-05: no LLM ───────────────────────────────

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case01_noScenesIsNotSubmitted() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		try {
			bk = newBook(u, "c01");
			Map<String, Object> out = PbComposeJobUtil.submitCompose(u, bk, false, false, (String) null, null, null);
			logger.info("P4-4 case01 submit: " + out);
			assertEquals(Boolean.FALSE, out.get("submitted"));
			assertEquals(Boolean.FALSE, out.get("reused"));
			assertEquals("The book has no scenes", out.get("reason"));
			assertEquals(Integer.valueOf(0), out.get("sceneCount"));
			assertEquals(Integer.valueOf(0), out.get("scenesToCompose"));
			assertEquals(Integer.valueOf(0), out.get("scenesCurrent"));
			assertEquals(Integer.valueOf(0), out.get("pagesSkipped"));
			assertEquals(PbRenderJobUtil.KIND_COMPOSE, out.get("kind"));
			assertEquals(oidOf(bk), out.get("bookObjectId"));
			assertNull("nothing was queued", out.get("jobId"));
			assertNull("no claim is held", PbComposeJobUtil.inflightJobId(oidOf(bk)));
			assertFalse(PbComposeJobUtil.isComposing(oidOf(bk)));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case02_chapBookIsNotComposed() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		try {
			bk = newBook(u, "c02");
			bk = makeChapBook(u, bk);
			addScene(u, bk, 0, "First page", null, null, null, "A first page of verse.");
			addScene(u, bk, 1, "Second page", null, null, null, "A second page of verse.");
			addScene(u, bk, 2, "Third page", null, null, null, "A third page of verse.");
			Map<String, Object> out = PbComposeJobUtil.submitCompose(u, bk, false, false, (String) null, null, null);
			logger.info("P4-4 case02 submit: " + out);
			assertEquals(Boolean.FALSE, out.get("submitted"));
			assertEquals(Boolean.FALSE, out.get("reused"));
			assertEquals("ChapBook pages are not composed; run the location pass to cluster their places", out.get("reason"));
			assertEquals(Integer.valueOf(3), out.get("sceneCount"));
			assertEquals("pagesSkipped is the page count", Integer.valueOf(3), out.get("pagesSkipped"));
			assertEquals(Integer.valueOf(0), out.get("scenesToCompose"));
			assertEquals(Integer.valueOf(0), out.get("scenesCurrent"));
			assertNull("nothing was queued", out.get("jobId"));
			assertNull("no claim is held", PbComposeJobUtil.inflightJobId(oidOf(bk)));
			/// force changes nothing for a ChapBook: pages are never composed.
			Map<String, Object> forced = PbComposeJobUtil.submitCompose(u, bk, false, true, (String) null, null, null);
			assertEquals(Boolean.FALSE, forced.get("submitted"));
			assertEquals(Integer.valueOf(3), forced.get("pagesSkipped"));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case03_unknownChatConfigIs404() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		try {
			bk = newBook(u, "c03");
			addScene(u, bk, 1, "The Climb", SETTING_1, ACTION_1, "urgent", TEXT_1);
			final BaseRecord book = bk;
			String bogus = "p44-no-such-config-" + shortId();
			PictureBookException e1 = expect(404, () -> PbComposeJobUtil.submitCompose(u, book, false, false, bogus, null, null));
			assertEquals("Chat config not found: " + bogus, e1.getMessage());
			PictureBookException e2 = expect(404, () -> PbComposeJobUtil.submitProposeLocations(u, book, bogus));
			assertEquals("Chat config not found: " + bogus, e2.getMessage());
			assertNull("the 404 queued nothing and holds no claim", PbComposeJobUtil.inflightJobId(oidOf(bk)));
			assertEquals("no job of the user was registered by the refusals", 0, countNonTerminal(u));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case04_quickStartWithoutSdIs500() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		try {
			bk = newBook(u, "c04");
			addScene(u, bk, 1, "The Climb", SETTING_1, ACTION_1, "urgent", TEXT_1);
			final BaseRecord book = bk;
			PictureBookException e1 = expect(500, () -> PbComposeJobUtil.submitCompose(u, book, true, false, null, "SWARM", "   "));
			assertEquals("SD server not configured; Quick start renders a draft of every composed scene", e1.getMessage());
			PictureBookException e2 = expect(500, () -> PbComposeJobUtil.submitCompose(u, book, true, false, (String) null, null, null));
			assertEquals("SD server not configured; Quick start renders a draft of every composed scene", e2.getMessage());
			PictureBookException e3 = expect(500, () -> PbComposeJobUtil.submitCompose(u, book, true, false, null, "NOT_AN_API", "http://127.0.0.1:1"));
			assertEquals("SD server API type is not recognised", e3.getMessage());
			assertNull("the 500s queued nothing and hold no claim", PbComposeJobUtil.inflightJobId(oidOf(bk)));
			assertEquals(0, countNonTerminal(u));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case05_oneLiveJobPerBook() throws Exception {
		BaseRecord u = user();
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord stranger = getCreateUser(STRANGER_USER, org);
		assertNotNull("stranger " + STRANGER_USER, stranger);
		assertFalse("the stranger is a different principal", oidOf(stranger).equals(oidOf(u)));
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord bk2 = null;
		List<Filler> fillers = new ArrayList<>();
		String composeJobId = null;
		String proposeJobId = null;
		try {
			bk = newBook(u, "c05");
			bk2 = newBook(u, "c05b");
			BaseRecord scene = addScene(u, bk, 1, "The Climb", SETTING_1, ACTION_1, "urgent", TEXT_1);
			long gid = ((Number) scene.get(FieldNames.FIELD_GROUP_ID)).longValue();
			String bkOid = oidOf(bk);
			String bk2Oid = oidOf(bk2);
			assertEquals("no job of the user is live before the case", 0, countNonTerminal(u));

			/// Saturate the pool so the compose job stays QUEUED.
			for(int i = 0; i < AsyncJobRegistry.MAX_CONCURRENT_JOBS; i++) {
				fillers.add(filler(u, "p44-c05-" + i));
			}
			for(Filler f : fillers) {
				assertTrue("filler " + f.job.getJobId() + " must be running", f.started.await(30, TimeUnit.SECONDS));
			}

			Map<String, Object> dto = PbComposeJobUtil.submitCompose(u, bk, false, false, (String) null, null, null);
			logger.info("P4-4 case05 compose submit: " + dto);
			assertEquals(Boolean.TRUE, dto.get("submitted"));
			assertEquals(Boolean.FALSE, dto.get("reused"));
			assertEquals(PbRenderJobUtil.KIND_COMPOSE, dto.get("kind"));
			assertEquals("queued", dto.get("jobStatus"));
			assertEquals(Integer.valueOf(1), dto.get("scenesToCompose"));
			composeJobId = (String) dto.get("jobId");
			assertNotNull(composeJobId);
			assertEquals("the claim carries the job id", composeJobId, PbComposeJobUtil.inflightJobId(bkOid));
			assertTrue(PbComposeJobUtil.isComposing(bkOid));

			/// Resubmit by the owner: the live job is the answer.
			Map<String, Object> again = PbComposeJobUtil.submitCompose(u, bk, false, false, (String) null, null, null);
			logger.info("P4-4 case05 compose resubmit: " + again);
			assertEquals(Boolean.TRUE, again.get("submitted"));
			assertEquals(Boolean.TRUE, again.get("reused"));
			assertEquals(composeJobId, again.get("jobId"));
			assertEquals("queued", again.get("jobStatus"));
			assertEquals("still one job of the user plus the fillers", fillers.size() + 1, countNonTerminal(u));

			/// The owner's other kind is a 409 that names the live job.
			final BaseRecord book = bk;
			PictureBookException other = expect(409, () -> PbComposeJobUtil.submitProposeLocations(u, book, null));
			assertEquals("Your " + PbRenderJobUtil.KIND_COMPOSE + " job " + composeJobId
				+ " is still running for this book; wait for it to finish or cancel it first", other.getMessage());

			/// A stranger's submit is a 409 that names nothing (architect S3).
			PictureBookException strangerPropose = expect(409, () -> PbComposeJobUtil.submitProposeLocations(stranger, book, null));
			assertEquals("A job is already running for this book", strangerPropose.getMessage());
			assertFalse("no job id in the refusal", strangerPropose.getMessage().contains(composeJobId));
			assertFalse("no owner id in the refusal", strangerPropose.getMessage().contains(oidOf(u)));
			assertFalse("no stranger id in the refusal", strangerPropose.getMessage().contains(oidOf(stranger)));
			assertFalse("no book id in the refusal", strangerPropose.getMessage().contains(bkOid));
			try {
				Map<String, Object> strangerCompose = PbComposeJobUtil.submitCompose(stranger, bk, false, false, (String) null, null, null);
				logger.info("P4-4 case05 stranger compose submit returned (the tally saw no scenes it may read): " + strangerCompose);
				assertEquals(Boolean.FALSE, strangerCompose.get("submitted"));
				assertEquals("The book has no scenes", strangerCompose.get("reason"));
			}
			catch(PictureBookException e) {
				logger.info("P4-4 case05 stranger compose submit refused: " + e.getStatus() + " " + e.getMessage());
				assertEquals(409, e.getStatus());
				assertEquals("A job is already running for this book", e.getMessage());
			}
			assertEquals("the stranger's attempts left the owner's claim alone", composeJobId, PbComposeJobUtil.inflightJobId(bkOid));

			/// The synchronous per-scene compose guard.
			PictureBookException guard = expect(409, () -> PbComposeJobUtil.requireNotComposing(bkOid));
			assertEquals("A compose job is running for this book; wait for it to finish or cancel it first", guard.getMessage());

			/// Cancel while QUEUED, then free a worker: the job closes through onCancelledBeforeStart.
			AsyncJob queued = AsyncJobRegistry.get(u, composeJobId);
			assertNotNull(queued);
			assertEquals(AsyncJob.Status.QUEUED, queued.getStatus());
			assertTrue("the cancel is accepted", AsyncJobRegistry.cancel(u, composeJobId));
			assertFalse("a second cancel is a no-op", AsyncJobRegistry.cancel(u, composeJobId));
			fillers.get(0).release.countDown();
			AsyncJob done = awaitTerminal(u, composeJobId, LOCAL_TIMEOUT_MS);
			logger.info("P4-4 case05 compose job " + done.getStatus() + " error=" + done.getError() + " result=" + done.getResult());
			assertEquals(AsyncJob.Status.CANCELLED, done.getStatus());
			assertEquals("Cancelled before starting", done.getError());
			assertNull("no result: the work never ran", done.getResult());
			assertNull("the claim is released", PbComposeJobUtil.inflightJobId(bkOid));
			assertFalse(PbComposeJobUtil.isComposing(bkOid));
			PbComposeJobUtil.requireNotComposing(bkOid);
			assertNull("no composition was written", PbComposeUtil.findSceneComposition(u, scene, gid, orgId));
			awaitTerminal(u, fillers.get(0).job.getJobId(), 30_000L);

			/// Re-saturate the pool, then the propose job holds the claim the same way.
			fillers.add(filler(u, "p44-c05-2"));
			assertTrue(fillers.get(2).started.await(30, TimeUnit.SECONDS));
			Map<String, Object> pdto = PbComposeJobUtil.submitProposeLocations(u, bk, null);
			logger.info("P4-4 case05 propose submit: " + pdto);
			assertEquals(Boolean.TRUE, pdto.get("submitted"));
			assertEquals(Boolean.FALSE, pdto.get("reused"));
			assertEquals(PbRenderJobUtil.KIND_PROPOSE_LOCATIONS, pdto.get("kind"));
			assertEquals("queued", pdto.get("jobStatus"));
			proposeJobId = (String) pdto.get("jobId");
			assertNotNull(proposeJobId);
			assertEquals(proposeJobId, PbComposeJobUtil.inflightJobId(bkOid));
			assertFalse("a propose claim is not a compose claim", PbComposeJobUtil.isComposing(bkOid));
			PbComposeJobUtil.requireNotComposing(bkOid);
			Map<String, Object> pagain = PbComposeJobUtil.submitProposeLocations(u, bk, null);
			assertEquals(Boolean.TRUE, pagain.get("reused"));
			assertEquals(proposeJobId, pagain.get("jobId"));
			final String pj = proposeJobId;
			PictureBookException composeDuringPropose = expect(409, () -> PbComposeJobUtil.submitCompose(u, book, false, false, (String) null, null, null));
			assertEquals("Your " + PbRenderJobUtil.KIND_PROPOSE_LOCATIONS + " job " + pj
				+ " is still running for this book; wait for it to finish or cancel it first", composeDuringPropose.getMessage());

			/// The registry's per-principal cap: 2 running fillers + the queued propose + a queued filler = 4.
			fillers.add(filler(u, "p44-c05-3"));
			int active = countNonTerminal(u);
			logger.info("P4-4 case05 active jobs of the user before the cap check: " + active);
			assertEquals(AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL, active);
			final BaseRecord book2 = bk2;
			PictureBookException cap = expect(429, () -> PbComposeJobUtil.submitProposeLocations(u, book2, null));
			assertEquals("Principal already has " + AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL + " of "
				+ AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL + " jobs queued or running", cap.getMessage());
			assertNull("the 429 left no claim on the second book", PbComposeJobUtil.inflightJobId(bk2Oid));

			/// Cancel the queued propose job and drain.
			assertTrue(AsyncJobRegistry.cancel(u, proposeJobId));
			for(Filler f : fillers) {
				f.release.countDown();
			}
			AsyncJob pdone = awaitTerminal(u, proposeJobId, LOCAL_TIMEOUT_MS);
			logger.info("P4-4 case05 propose job " + pdone.getStatus() + " error=" + pdone.getError());
			assertEquals(AsyncJob.Status.CANCELLED, pdone.getStatus());
			assertEquals("Cancelled before starting", pdone.getError());
			assertNull("the propose claim is released", PbComposeJobUtil.inflightJobId(bkOid));
		}
		finally {
			for(Filler f : fillers) {
				f.release.countDown();
			}
			for(Filler f : fillers) {
				awaitTerminal(u, f.job.getJobId(), 30_000L);
			}
			if(composeJobId != null) {
				AsyncJobRegistry.cancel(u, composeJobId);
			}
			if(proposeJobId != null) {
				AsyncJobRegistry.cancel(u, proposeJobId);
			}
			deleteBook(u, bk);
			deleteBook(u, bk2);
		}
	}

	// ─────────────────────────────── 06-08: live LLM ───────────────────────────────

	@Test(timeout = 1_800_000)
	public void case06_liveComposeTwoScenesThenLocationPass() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		String jobId = null;
		try {
			cfg = liveConfig(u);
			bk = newBook(u, "c06");
			String bkOid = oidOf(bk);
			List<BaseRecord> scenes = twoScenesWithCast(u, bk);
			BaseRecord s1 = scenes.get(0);
			BaseRecord s2 = scenes.get(1);
			long gid = ((Number) s1.get(FieldNames.FIELD_GROUP_ID)).longValue();
			assertNull(PbComposeUtil.findSceneComposition(u, s1, gid, orgId));
			assertNull(PbComposeUtil.findSceneComposition(u, s2, gid, orgId));

			long t0 = System.currentTimeMillis();
			Map<String, Object> dto = PbComposeJobUtil.submitCompose(u, bk, false, false, CFG_NAME, null, null);
			logger.info("P4-4 case06 submit: " + dto);
			assertEquals(Boolean.TRUE, dto.get("submitted"));
			assertEquals(Boolean.FALSE, dto.get("reused"));
			assertEquals(Integer.valueOf(2), dto.get("sceneCount"));
			assertEquals(Integer.valueOf(2), dto.get("scenesToCompose"));
			assertEquals(Integer.valueOf(0), dto.get("scenesCurrent"));
			jobId = (String) dto.get("jobId");
			assertNotNull(jobId);
			AsyncJob job = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "case06");
			long ms = System.currentTimeMillis() - t0;
			logger.info("MEASURED: P4-4 case06 compose job wall " + ms + " ms -> " + job.getStatus() + " error=" + job.getError());
			logger.info("P4-4 case06 result: " + job.getResult());
			assertEquals("job error: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
			assertNull("the claim is released on completion", PbComposeJobUtil.inflightJobId(bkOid));
			assertFalse(PbComposeJobUtil.isComposing(bkOid));

			Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
			assertNotNull("the result parses: " + job.getResult(), result);
			assertEquals(bkOid, result.get("bookObjectId"));
			assertEquals(Boolean.FALSE, result.get("quickStart"));
			assertEquals(Boolean.FALSE, result.get("force"));
			assertEquals(2, num(result, "sceneCount"));
			assertEquals(2, num(result, "visited"));
			/// P4-10: for a STORY book the job runs PbMigrationUtil.migrateCompositions BEFORE the loop, so every scene
			/// already holds a MIGRATED row (revision 1) when the loop reaches it and the loop ALWAYS composes over an
			/// EXISTING row. The live LLM (qwen3:8b at the chatConfig's temperature) does not always return a parseable
			/// staging: over an existing row a malformed reply is a hardFailure that leaves the MIGRATED row untouched
			/// (PbComposeUtil.composeScene), never a second MIGRATED write - so "migrated" is 0 on every pass and
			/// "hardFailures" may be non-zero. What the job must guarantee is that every scene was visited, in story
			/// order, and is either LLM-composed (revision 2) or reported untouched (still revision 1); how many came
			/// back parseable is MEASURED, never asserted.
			/// (Re-baselined from the pre-P4-10 contract, where the loop itself wrote the MIGRATED fallback: then
			/// composed + migrated == 2, hardFailures == 0 and every row was revision 1.)
			Map<?, ?> migration = (Map<?, ?>) result.get("migration");
			assertNotNull("the job reports its migration pass: " + job.getResult(), migration);
			assertNull("the migration did not throw: " + migration, migration.get("error"));
			assertEquals("both scenes scanned by the migration", 2, num(migration, "scanned"));
			assertEquals("a MIGRATED row was written for every scene before the loop", 2, num(migration, "created"));
			assertEquals(0, num(migration, "failed"));
			assertEquals(0, num(migration, "skippedExisting"));
			/// JSONUtil's NON_EMPTY mapper drops an empty list from the job result, so absent-or-empty is "no failures".
			List<?> failedOnWire = (List<?>) migration.get("failedSceneObjectIds");
			assertTrue("no migration failures: " + migration, failedOnWire == null || failedOnWire.isEmpty());
			int composed0 = num(result, "composed");
			int hard0 = num(result, "hardFailures");
			logger.info("MEASURED: P4-4 case06 first pass composed=" + composed0 + " hardFailures=" + hard0);
			assertEquals("the loop never writes a MIGRATED fallback over the migration's row: " + job.getResult(), 0, num(result, "migrated"));
			assertEquals("every scene is LLM-composed or reported untouched on the first pass: " + job.getResult(), 2, composed0 + hard0);
			assertEquals(0, num(result, "current"));
			assertEquals(0, num(result, "skippedUserEdited"));
			assertEquals(0, num(result, "pagesSkipped"));
			assertEquals(0, num(result, "quickStartRendered"));
			assertEquals(0, num(result, "quickStartFailed"));
			assertEquals(Boolean.FALSE, result.get("cancelled"));
			assertEquals(Boolean.FALSE, result.get("interrupted"));
			assertEquals(Boolean.TRUE, result.get("locationPassRan"));
			assertNull("no note on a complete run", result.get("note"));
			assertTrue(lng(result, "durationMs") >= 0L);
			Map<?, ?> lp = (Map<?, ?>) result.get("locationPass");
			assertNotNull("locationPass DTO", lp);
			logger.info("P4-4 case06 location pass: " + lp);
			assertEquals("both scenes carry a setting", 2, num(lp, "scenesConsidered"));
			assertEquals(Boolean.FALSE, lp.get("cancelled"));
			assertNotNull("passRun", lp.get("passRun"));

			List<?> outcomes = (List<?>) result.get("scenes");
			assertEquals(2, outcomes.size());
			BaseRecord[] bySlot = { s1, s2 };
			for(int i = 0; i < 2; i++) {
				Map<?, ?> o = (Map<?, ?>) outcomes.get(i);
				BaseRecord scene = bySlot[i];
				String label = "outcome[" + i + "] " + o;
				assertEquals(label + " story order", oidOf(scene), o.get("sceneObjectId"));
				assertEquals(label, scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX), o.get("sceneIndex"));
				String status = (String) o.get("status");
				/// Pre-P4-10 this accepted "composed" or "migrated" with every row at revision 1; now the loop composes
				/// over the migration's MIGRATED row, so a success is LLM at revision 2 and the only other outcome is a
				/// hardFailure that leaves the MIGRATED row at revision 1.
				assertTrue(label + " status is composed or hardFailure", "composed".equals(status) || "hardFailure".equals(status));
				boolean composedNow = "composed".equals(status);
				String expectedSource = (composedNow ? PbCompositionSourceEnumType.LLM : PbCompositionSourceEnumType.MIGRATED).name();
				assertEquals(label, expectedSource, o.get("source"));
				if(!composedNow) {
					assertTrue(label + " the hard failure says the existing row was left untouched",
						String.valueOf(o.get("error")).contains("existing composition left untouched"));
				}
				assertTrue(label + " actorCount", num(o, "actorCount") >= 1);
				assertTrue(label + " elapsedMs", lng(o, "elapsedMs") >= 0L);
				BaseRecord comp = PbComposeUtil.findSceneComposition(u, scene, gid, orgId);
				assertNotNull(label + " composition row", comp);
				assertEquals(label + " compositionObjectId is the scene's row", oidOf(comp), o.get("compositionObjectId"));
				assertEquals(label + " a MIGRATED row is flagged for re-compose, an LLM row is not",
					!composedNow, PbComposeUtil.needsCompose(comp));
				assertEquals(label, expectedSource, enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
				assertEquals(label + " LLM over the MIGRATED row is revision 2; an untouched MIGRATED row stays at 1",
					Integer.valueOf(composedNow ? 2 : 1), comp.get(OlioFieldNames.FIELD_PB_REVISION));
				assertEquals(label, Boolean.FALSE, comp.get(OlioFieldNames.FIELD_PB_USER_EDITED));
				assertEquals(label, idOf(scene), fkId(comp, OlioFieldNames.FIELD_PB_SCENE));
				assertEquals(label + " actors persisted", num(o, "actorCount"), PbComposeUtil.listActors(u, comp).size());
			}

			/// A MIGRATED row needsCompose, so a plain (non-forced) resubmit must pick up exactly those scenes and
			/// leave the LLM rows alone ("current"). Over an EXISTING row a malformed reply is a hardFailure that
			/// leaves the row untouched (PbComposeUtil.composeScene) - so the row stays MIGRATED and pending.
			/// Repeat until every scene is LLM-composed; the pass count is MEASURED. Bounded so a systematically
			/// broken prompt/LLM fails instead of looping.
			/// (Pre-P4-10: revision started at {1,1} and pending was the loop's own "migrated" count; now a composed
			/// row is already at 2 and the pending rows are the first pass's hard failures.)
			int[] revision = new int[2];
			for(int i = 0; i < 2; i++) {
				revision[i] = ("composed".equals(((Map<?, ?>) outcomes.get(i)).get("status")) ? 2 : 1);
			}
			int passes = 1;
			int pending = hard0;
			while(pending > 0) {
				assertTrue("the LLM returned no parseable staging for the same scene(s) in " + passes + " consecutive passes; giving up", passes < 5);
				Map<String, Object> more = PbComposeJobUtil.submitCompose(u, bk, false, false, CFG_NAME, null, null);
				logger.info("P4-4 case06 pass " + (passes + 1) + " submit: " + more);
				assertEquals(Boolean.TRUE, more.get("submitted"));
				assertEquals("exactly the MIGRATED rows are to compose", Integer.valueOf(pending), more.get("scenesToCompose"));
				assertEquals("the LLM rows are current", Integer.valueOf(2 - pending), more.get("scenesCurrent"));
				jobId = (String) more.get("jobId");
				t0 = System.currentTimeMillis();
				AsyncJob jobN = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "case06-pass" + (passes + 1));
				logger.info("MEASURED: P4-4 case06 pass " + (passes + 1) + " wall " + (System.currentTimeMillis() - t0) + " ms -> " + jobN.getStatus() + " error=" + jobN.getError());
				logger.info("P4-4 case06 pass " + (passes + 1) + " result: " + jobN.getResult());
				assertEquals("job error: " + jobN.getError(), AsyncJob.Status.COMPLETED, jobN.getStatus());
				Map<?, ?> rN = JSONUtil.importObject(jobN.getResult(), Map.class);
				assertEquals(2, num(rN, "visited"));
				assertEquals(2 - pending, num(rN, "current"));
				Map<?, ?> migN = (Map<?, ?>) rN.get("migration");
				assertNotNull("later passes still report the migration: " + jobN.getResult(), migN);
				assertEquals("the migration creates nothing once every scene has a row", 0, num(migN, "created"));
				assertEquals(2, num(migN, "skippedExisting"));
				assertEquals(0, num(migN, "failed"));
				assertEquals("a MIGRATED row is never re-migrated over itself", 0, num(rN, "migrated"));
				assertEquals("every pending scene is either re-composed or reported untouched", pending, num(rN, "composed") + num(rN, "hardFailures"));
				List<?> oN = (List<?>) rN.get("scenes");
				int stillPending = 0;
				for(int i = 0; i < 2; i++) {
					Map<?, ?> o = (Map<?, ?>) oN.get(i);
					String status = (String) o.get("status");
					BaseRecord comp = PbComposeUtil.findSceneComposition(u, bySlot[i], gid, orgId);
					assertEquals("same composition row: " + o, oidOf(comp), o.get("compositionObjectId"));
					if("composed".equals(status)) {
						revision[i]++;
						assertEquals(PbCompositionSourceEnumType.LLM.name(), o.get("source"));
						assertEquals(PbCompositionSourceEnumType.LLM.name(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
						assertFalse(PbComposeUtil.needsCompose(comp));
					}
					else if("hardFailure".equals(status)) {
						assertTrue("the hard failure says the row was left untouched: " + o, String.valueOf(o.get("error")).contains("existing composition left untouched"));
						assertEquals(PbCompositionSourceEnumType.MIGRATED.name(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
						assertTrue(PbComposeUtil.needsCompose(comp));
						stillPending++;
					}
					else {
						assertEquals("an LLM row is left alone: " + o, "current", status);
					}
					assertEquals("revision bumps only on a write: " + o, Integer.valueOf(revision[i]), comp.get(OlioFieldNames.FIELD_PB_REVISION));
				}
				pending = stillPending;
				passes++;
			}
			logger.info("MEASURED: P4-4 case06 every scene LLM-composed after " + passes + " pass(es); revisions=" + revision[0] + "," + revision[1]);
			for(int i = 0; i < 2; i++) {
				BaseRecord comp = PbComposeUtil.findSceneComposition(u, bySlot[i], gid, orgId);
				assertEquals(PbCompositionSourceEnumType.LLM.name(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
				assertFalse(PbComposeUtil.needsCompose(comp));
			}

			/// Resubmit: nothing to do.
			Map<String, Object> again = PbComposeJobUtil.submitCompose(u, bk, false, false, CFG_NAME, null, null);
			logger.info("P4-4 case06 resubmit: " + again);
			assertEquals(Boolean.FALSE, again.get("submitted"));
			assertEquals("Every scene is composed", again.get("reason"));
			assertEquals(Integer.valueOf(2), again.get("scenesCurrent"));
			assertEquals(Integer.valueOf(0), again.get("scenesToCompose"));
			assertNull(again.get("jobId"));

			/// Forced resubmit: every scene is re-composed in place. Over an EXISTING composition a malformed reply
			/// is a hardFailure that leaves the row untouched (PbComposeUtil.composeScene) - reported, never a
			/// silent overwrite - so the forced pass is checked per outcome against the row's revision.
			t0 = System.currentTimeMillis();
			Map<String, Object> forced = PbComposeJobUtil.submitCompose(u, bk, false, true, CFG_NAME, null, null);
			logger.info("P4-4 case06 forced submit: " + forced);
			assertEquals(Boolean.TRUE, forced.get("submitted"));
			assertEquals(Integer.valueOf(2), forced.get("scenesToCompose"));
			assertEquals(Integer.valueOf(0), forced.get("scenesCurrent"));
			jobId = (String) forced.get("jobId");
			AsyncJob job2 = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "case06-forced");
			ms = System.currentTimeMillis() - t0;
			logger.info("MEASURED: P4-4 case06 forced compose job wall " + ms + " ms -> " + job2.getStatus() + " error=" + job2.getError());
			logger.info("P4-4 case06 forced result: " + job2.getResult());
			assertEquals("job error: " + job2.getError(), AsyncJob.Status.COMPLETED, job2.getStatus());
			Map<?, ?> r2 = JSONUtil.importObject(job2.getResult(), Map.class);
			assertEquals(Boolean.TRUE, r2.get("force"));
			assertEquals(2, num(r2, "visited"));
			assertEquals(0, num(r2, "current"));
			assertEquals(0, num(r2, "migrated"));
			assertEquals("forced: the migration still creates nothing", 0, num((Map<?, ?>) r2.get("migration"), "created"));
			assertEquals("forced: every scene is re-composed or reported as a hard failure: " + job2.getResult(),
				2, num(r2, "composed") + num(r2, "hardFailures"));
			assertEquals(Boolean.TRUE, r2.get("locationPassRan"));
			logger.info("MEASURED: P4-4 case06 forced pass composed=" + num(r2, "composed") + " hardFailures=" + num(r2, "hardFailures"));
			List<?> o2 = (List<?>) r2.get("scenes");
			for(int i = 0; i < 2; i++) {
				Map<?, ?> o = (Map<?, ?>) o2.get(i);
				BaseRecord comp = PbComposeUtil.findSceneComposition(u, bySlot[i], gid, orgId);
				assertNotNull(comp);
				assertEquals("same row: " + o, oidOf(comp), o.get("compositionObjectId"));
				if("composed".equals(o.get("status"))) {
					assertEquals("revision bumped by the forced re-compose: " + o, Integer.valueOf(revision[i] + 1), comp.get(OlioFieldNames.FIELD_PB_REVISION));
					assertEquals(PbCompositionSourceEnumType.LLM.name(), o.get("source"));
				}
				else {
					assertEquals("the only other outcome over an existing row: " + o, "hardFailure", o.get("status"));
					assertTrue("the hard failure says the row was left untouched: " + o, String.valueOf(o.get("error")).contains("existing composition left untouched"));
					assertEquals("revision unchanged: " + o, Integer.valueOf(revision[i]), comp.get(OlioFieldNames.FIELD_PB_REVISION));
				}
				assertEquals("the row stays LLM-sourced either way", PbCompositionSourceEnumType.LLM.name(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
			}
			assertNull(PbComposeJobUtil.inflightJobId(bkOid));
		}
		finally {
			if(jobId != null) {
				AsyncJobRegistry.cancel(u, jobId);
				awaitTerminalQuietly(u, jobId, LOCAL_TIMEOUT_MS);
			}
			deleteBook(u, bk);
			deleteConfig(u, cfg);
		}
	}

	@Test(timeout = 1_800_000)
	public void case07_liveCancelMidCompose() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		String jobId = null;
		try {
			cfg = liveConfig(u);
			bk = newBook(u, "c07");
			String bkOid = oidOf(bk);
			List<BaseRecord> scenes = twoScenesWithCast(u, bk);
			long gid = ((Number) scenes.get(0).get(FieldNames.FIELD_GROUP_ID)).longValue();

			long t0 = System.currentTimeMillis();
			Map<String, Object> dto = PbComposeJobUtil.submitCompose(u, bk, false, false, CFG_NAME, null, null);
			logger.info("P4-4 case07 submit: " + dto);
			assertEquals(Boolean.TRUE, dto.get("submitted"));
			jobId = (String) dto.get("jobId");
			assertNotNull(jobId);

			/// Wait for the worker to be inside a scene's compose, then cancel.
			long deadline = System.currentTimeMillis() + JOB_TIMEOUT_MS;
			AsyncJob job = null;
			String phaseSeen = null;
			while(System.currentTimeMillis() < deadline) {
				job = AsyncJobRegistry.get(u, jobId);
				assertNotNull(job);
				String phase = job.getProgress().getPhase();
				if(job.isTerminal()) {
					fail("the job finished before a cancel could land: " + job.getStatus() + " " + job.getResult());
				}
				if(job.getStatus() == AsyncJob.Status.RUNNING && phase != null && phase.startsWith("compose ")) {
					phaseSeen = phase;
					break;
				}
				Thread.sleep(100L);
			}
			assertNotNull("the job reached a compose phase", phaseSeen);
			long tCancel = System.currentTimeMillis();
			assertTrue("the cancel is accepted during '" + phaseSeen + "'", AsyncJobRegistry.cancel(u, jobId));
			AsyncJob done = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "case07");
			logger.info("MEASURED: P4-4 case07 cancel landed during '" + phaseSeen + "' at +" + (tCancel - t0) + " ms; terminal after "
				+ (System.currentTimeMillis() - tCancel) + " ms -> " + done.getStatus() + " error=" + done.getError());
			logger.info("P4-4 case07 result: " + done.getResult());
			assertEquals(AsyncJob.Status.CANCELLED, done.getStatus());
			assertNotNull("the partial result is kept", done.getResult());
			Map<?, ?> result = JSONUtil.importObject(done.getResult(), Map.class);
			assertNotNull(result);
			assertEquals(Boolean.TRUE, result.get("cancelled"));
			assertEquals(Boolean.FALSE, result.get("interrupted"));
			assertEquals(Boolean.FALSE, result.get("locationPassRan"));
			assertNull(result.get("locationPass"));
			assertEquals(PbComposeJobUtil.NOTE_RESUME, result.get("note"));
			List<?> outcomes = (List<?>) result.get("scenes");
			assertTrue("at least the interrupted scene is reported", outcomes.size() >= 1);
			Map<?, ?> last = (Map<?, ?>) outcomes.get(outcomes.size() - 1);
			assertEquals("the interrupted scene: " + last, "cancelled", last.get("status"));
			assertNull("nothing was written for the interrupted scene", last.get("compositionObjectId"));
			/// P4-10: a scene visited before the cancel composed over its MIGRATED row - LLM-composed, or a hardFailure
			/// when the live LLM's reply was unparseable (the row is left untouched). Either is a settled outcome; how
			/// many parsed is MEASURED. (Pre-P4-10: composed + 1 == visited and hardFailures == 0.)
			logger.info("MEASURED: P4-4 case07 before the cancel composed=" + num(result, "composed") + " hardFailures=" + num(result, "hardFailures"));
			assertEquals("every visited scene but the interrupted one settled (composed or untouched)",
				num(result, "composed") + num(result, "hardFailures") + 1, num(result, "visited"));
			assertEquals(outcomes.size(), num(result, "visited"));
			assertEquals("the loop never writes a MIGRATED fallback", 0, num(result, "migrated"));
			assertNull("the claim is released", PbComposeJobUtil.inflightJobId(bkOid));
			assertFalse(PbComposeJobUtil.isComposing(bkOid));
			BaseRecord interruptedScene = PbBookUtil.readScene(u, (String) last.get("sceneObjectId"), orgId);
			assertNotNull(interruptedScene);
			/// P4-10: the job migrated every scene into a MIGRATED row BEFORE the loop (the cancel landed in a
			/// "compose " phase, after "migrate compositions"), so the interrupted scene does hold a row - the
			/// migration's, untouched by the cancelled compose: MIGRATED, revision 1, still flagged for compose.
			/// (Pre-P4-10 there was no row at all for the interrupted scene.)
			Map<?, ?> migration = (Map<?, ?>) result.get("migration");
			assertNotNull("the partial result carries the migration pass: " + done.getResult(), migration);
			assertEquals("every scene was migrated before the loop", scenes.size(), num(migration, "created"));
			assertEquals(0, num(migration, "failed"));
			BaseRecord interruptedComp = PbComposeUtil.findSceneComposition(u, interruptedScene, gid, orgId);
			assertNotNull("the interrupted scene keeps the migration's row", interruptedComp);
			interruptedComp = PbComposeUtil.readComposition(u, oidOf(interruptedComp), orgId);
			assertEquals("the cancelled compose never touched it", PbCompositionSourceEnumType.MIGRATED.name(), enumWord(interruptedComp, OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(Integer.valueOf(1), interruptedComp.get(OlioFieldNames.FIELD_PB_REVISION));
			assertTrue("still pending", PbComposeUtil.needsCompose(interruptedComp));

			/// A fresh submit resumes (the tally still finds something to compose).
			Map<String, Object> resumed = PbComposeJobUtil.submitCompose(u, bk, false, false, CFG_NAME, null, null);
			logger.info("P4-4 case07 resubmit: " + resumed);
			assertEquals(Boolean.TRUE, resumed.get("submitted"));
			assertEquals(Boolean.FALSE, resumed.get("reused"));
			assertTrue(((Integer) resumed.get("scenesToCompose")).intValue() >= 1);
			assertEquals(num(result, "composed"), ((Integer) resumed.get("scenesCurrent")).intValue());
			jobId = (String) resumed.get("jobId");
			assertTrue(AsyncJobRegistry.cancel(u, jobId));
			AsyncJob done2 = awaitTerminal(u, jobId, JOB_TIMEOUT_MS);
			logger.info("P4-4 case07 resumed job after cancel: " + done2.getStatus() + " error=" + done2.getError());
			assertEquals(AsyncJob.Status.CANCELLED, done2.getStatus());
			assertNull(PbComposeJobUtil.inflightJobId(bkOid));
		}
		finally {
			if(jobId != null) {
				AsyncJobRegistry.cancel(u, jobId);
				awaitTerminalQuietly(u, jobId, LOCAL_TIMEOUT_MS);
			}
			deleteBook(u, bk);
			deleteConfig(u, cfg);
		}
	}

	@Test(timeout = 1_800_000)
	public void case08_proposeLocationsJob() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		BaseRecord cfg = null;
		String jobId = null;
		List<Filler> fillers = new ArrayList<>();
		try {
			cfg = liveConfig(u);
			bk = newBook(u, "c08");
			String bkOid = oidOf(bk);
			twoScenesWithCast(u, bk);

			/// Hold the pool so the propose job is observably live while the compose submit is refused.
			for(int i = 0; i < AsyncJobRegistry.MAX_CONCURRENT_JOBS; i++) {
				fillers.add(filler(u, "p44-c08-" + i));
			}
			for(Filler f : fillers) {
				assertTrue(f.started.await(30, TimeUnit.SECONDS));
			}
			Map<String, Object> dto = PbComposeJobUtil.submitProposeLocations(u, bk, CFG_NAME);
			logger.info("P4-4 case08 submit: " + dto);
			assertEquals(Boolean.TRUE, dto.get("submitted"));
			assertEquals(Boolean.FALSE, dto.get("reused"));
			assertEquals(PbRenderJobUtil.KIND_PROPOSE_LOCATIONS, dto.get("kind"));
			assertEquals("queued", dto.get("jobStatus"));
			jobId = (String) dto.get("jobId");
			assertNotNull(jobId);
			final BaseRecord book = bk;
			final String pj = jobId;
			PictureBookException e = expect(409, () -> PbComposeJobUtil.submitCompose(u, book, false, false, CFG_NAME, null, null));
			assertEquals("Your " + PbRenderJobUtil.KIND_PROPOSE_LOCATIONS + " job " + pj
				+ " is still running for this book; wait for it to finish or cancel it first", e.getMessage());

			long t0 = System.currentTimeMillis();
			for(Filler f : fillers) {
				f.release.countDown();
			}
			AsyncJob job = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "case08");
			logger.info("MEASURED: P4-4 case08 propose job wall " + (System.currentTimeMillis() - t0) + " ms (from pool release) -> "
				+ job.getStatus() + " error=" + job.getError());
			logger.info("P4-4 case08 result: " + job.getResult());
			assertEquals("job error: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
			assertEquals(PbRenderJobUtil.KIND_PROPOSE_LOCATIONS, job.getKind());
			Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
			assertNotNull(result);
			assertEquals(bkOid, result.get("bookObjectId"));
			assertEquals(PbRenderJobUtil.KIND_PROPOSE_LOCATIONS, result.get("kind"));
			assertNotNull("passRun", result.get("passRun"));
			assertEquals(2, num(result, "scenesConsidered"));
			assertEquals(0, num(result, "scenesSkipped"));
			assertEquals(Boolean.FALSE, result.get("cancelled"));
			assertEquals(Boolean.FALSE, result.get("hardFailure"));
			assertTrue(lng(result, "durationMs") >= 0L);
			assertTrue("proposals: " + result.get("proposals"), ((List<?>) result.get("proposals")).size() >= 1);
			assertNull("the claim is released", PbComposeJobUtil.inflightJobId(bkOid));
		}
		finally {
			for(Filler f : fillers) {
				f.release.countDown();
			}
			for(Filler f : fillers) {
				awaitTerminal(u, f.job.getJobId(), 30_000L);
			}
			if(jobId != null) {
				AsyncJobRegistry.cancel(u, jobId);
				awaitTerminalQuietly(u, jobId, LOCAL_TIMEOUT_MS);
			}
			deleteBook(u, bk);
			deleteConfig(u, cfg);
		}
	}

	@Test(timeout = 1_800_000)
	public void case09_quickStartWithUnreachableSdTalliesEveryDraftFailureAndCompletes() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord cfg = null;
		String jobId = null;
		try {
			cfg = liveConfig(u);
			bk = newBook(u, "c09");
			String bkOid = oidOf(bk);
			List<BaseRecord> scenes = twoScenesWithCast(u, bk);
			long gid = ((Number) scenes.get(0).get(FieldNames.FIELD_GROUP_ID)).longValue();

			long t0 = System.currentTimeMillis();
			Map<String, Object> dto = PbComposeJobUtil.submitCompose(u, bk, true, false, CFG_NAME, "SWARM", DEAD_SD_SERVER);
			logger.info("P4-4 case09 submit: " + dto);
			assertEquals(Boolean.TRUE, dto.get("submitted"));
			assertEquals(Boolean.FALSE, dto.get("reused"));
			assertEquals(Boolean.TRUE, dto.get("quickStart"));
			assertEquals(Integer.valueOf(2), dto.get("scenesToCompose"));
			jobId = (String) dto.get("jobId");
			assertNotNull(jobId);
			AsyncJob job = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "case09");
			logger.info("MEASURED: P4-4 case09 quick-start compose job wall " + (System.currentTimeMillis() - t0) + " ms -> "
				+ job.getStatus() + " error=" + job.getError());
			logger.info("P4-4 case09 result: " + job.getResult());
			assertEquals("a refused draft never fails the job: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
			assertNull("the claim is released on completion", PbComposeJobUtil.inflightJobId(bkOid));

			Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
			assertNotNull("the result parses: " + job.getResult(), result);
			assertEquals(Boolean.TRUE, result.get("quickStart"));
			assertEquals(2, num(result, "visited"));
			/// P4-10: the migration wrote a MIGRATED row for every scene before the loop, so the loop composes over an
			/// existing row - a parseable reply is "composed" (written; its draft is attempted), an unparseable one is a
			/// "hardFailure" that leaves the MIGRATED row untouched (not written; no draft attempted). "migrated" is 0.
			/// (Pre-P4-10: written = composed + migrated, hardFailures == 0.)
			Map<?, ?> migration = (Map<?, ?>) result.get("migration");
			assertNotNull("migration pass: " + job.getResult(), migration);
			assertEquals(2, num(migration, "created"));
			assertEquals(0, num(migration, "failed"));
			int written = num(result, "composed");
			int hard = num(result, "hardFailures");
			logger.info("MEASURED: P4-4 case09 composed=" + written + " hardFailures=" + hard);
			assertEquals("the loop never writes a MIGRATED fallback over the migration's row", 0, num(result, "migrated"));
			assertEquals("every scene is composed or reported untouched: " + job.getResult(), 2, written + hard);
			assertEquals("nothing rendered against a dead renderer", 0, num(result, "quickStartRendered"));
			assertEquals("one draft failure per written scene", written, num(result, "quickStartFailed"));
			assertEquals(Boolean.FALSE, result.get("cancelled"));
			assertEquals(Boolean.FALSE, result.get("interrupted"));
			assertEquals("the location pass still runs after the draft failures", Boolean.TRUE, result.get("locationPassRan"));
			assertNotNull(result.get("locationPass"));
			assertNull("no note on a complete run", result.get("note"));

			List<?> outcomes = (List<?>) result.get("scenes");
			assertEquals(2, outcomes.size());
			for(int i = 0; i < 2; i++) {
				Map<?, ?> o = (Map<?, ?>) outcomes.get(i);
				String label = "outcome[" + i + "] " + o;
				assertEquals(label + " story order", oidOf(scenes.get(i)), o.get("sceneObjectId"));
				String status = (String) o.get("status");
				assertTrue(label + " status is composed or hardFailure", "composed".equals(status) || "hardFailure".equals(status));
				BaseRecord comp = PbComposeUtil.findSceneComposition(u, scenes.get(i), gid, orgId);
				assertNotNull(label + " composition row", comp);
				assertEquals(label + " the composition is the scene's row", oidOf(comp), o.get("compositionObjectId"));
				assertNull(label + " no draft render DTO", o.get("draftRender"));
				if("hardFailure".equals(status)) {
					assertTrue(label, String.valueOf(o.get("error")).contains("existing composition left untouched"));
					assertEquals(label + " the untouched row is the migration's", PbCompositionSourceEnumType.MIGRATED.name(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
					assertNull(label + " no draft is attempted for an unwritten scene", o.get("draftRenderError"));
					continue;
				}
				assertEquals(label, PbCompositionSourceEnumType.LLM.name(), enumWord(comp, OlioFieldNames.FIELD_PB_SOURCE));
				String err = (String) o.get("draftRenderError");
				assertNotNull(label + " draftRenderError", err);
				assertTrue(label + " the refusal is the executor's pre-render 503", err.contains("Renderer inventory unavailable"));
				assertFalse(label + " the refusal never carries the server URL", err.contains(DEAD_SD_SERVER));
				assertTrue(label + " draftRenderMs", lng(o, "draftRenderMs") >= 0L);
				logger.info("MEASURED: P4-4 case09 " + label.substring(0, 10) + " draft refused in " + lng(o, "draftRenderMs") + " ms: " + err);
			}
		}
		finally {
			if(jobId != null) {
				AsyncJobRegistry.cancel(u, jobId);
				awaitTerminalQuietly(u, jobId, LOCAL_TIMEOUT_MS);
			}
			deleteBook(u, bk);
			deleteConfig(u, cfg);
		}
	}

	// ─────────────────────────────── 10: no LLM ───────────────────────────────

	/// A scene's composition is the row in the scene's own group (the book group, where compose writes every row)
	/// - the shape the job loop resolves through findSceneComposition. AccessPoint.list is not a per-record
	/// boundary, so the whole-book list behind the submit tally has to carry the same group condition: a SCENE row
	/// written in another group of the same organization that points at the scene and the book must be invisible
	/// to it, or the tally would answer "Every scene is composed" where the loop would compose. The foreign row
	/// is created first so that, were it listed, it would also win the lowest-id resolution over the real one.
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case10_tallyIgnoresACompositionFromAnotherGroup() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		BaseRecord foreign = null;
		String jobId = null;
		try {
			bk = newBook(u, "c10");
			String bkOid = oidOf(bk);
			BaseRecord scene = addScene(u, bk, 0, "Only scene", SETTING_1, ACTION_1, null, TEXT_1);
			long sceneGid = ((Number) scene.get(FieldNames.FIELD_GROUP_ID)).longValue();
			assertEquals("the book row and its scene share the book group", sceneGid, ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue());

			PbComposeJobUtil.Tally before = PbComposeJobUtil.tally(u, bk, false);
			assertEquals(1, before.sceneCount);
			assertEquals("a never-composed scene is to-compose", 1, before.toCompose);
			assertEquals(0, before.current);

			/// An LLM-sourced, not user-edited SCENE composition (needsCompose false) in another group of the same
			/// organization - the user's own ~/Data, the one place outside the olio-owned book container where
			/// testUser1 can create - pointing at the scene and the book.
			String foreignPath = "~/Data/P44 Foreign " + shortId();
			foreign = RecordFactory.newInstance(OlioModelNames.MODEL_PB_COMPOSITION);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, foreign, "foreign composition", foreignPath, orgId);
			foreign.set(FieldNames.FIELD_NAME, "foreign composition");
			foreign.set(OlioFieldNames.FIELD_PB_BOOK, bk);
			foreign.set(OlioFieldNames.FIELD_PB_SCENE, scene);
			foreign.set(OlioFieldNames.FIELD_PB_PURPOSE, PbCompositionPurposeEnumType.SCENE.toString());
			foreign.set(OlioFieldNames.FIELD_PB_SOURCE, PbCompositionSourceEnumType.LLM.toString());
			foreign.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.FALSE);
			BaseRecord created = ioContext.getAccessPoint().create(u, foreign);
			assertNotNull("the foreign-group composition is created", created);
			foreign.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
			foreign.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
			long foreignGid = ((Number) foreign.get(FieldNames.FIELD_GROUP_ID)).longValue();
			assertTrue("the row really is in another group", foreignGid != sceneGid);
			BaseRecord foreignBack = PbComposeUtil.readComposition(u, oidOf(foreign), orgId);
			assertNotNull("the foreign-group row is the user's to read", foreignBack);
			assertFalse("as a row it would read as current", PbComposeUtil.needsCompose(foreignBack));
			assertEquals("it points at the scene", idOf(scene), fkId(foreignBack, OlioFieldNames.FIELD_PB_SCENE));

			Map<Long, BaseRecord> listed = PbComposeUtil.listSceneCompositions(u, bk);
			assertTrue("the book-group list does not carry the foreign-group row: " + listed.keySet(), listed.isEmpty());
			assertNull("the loop's resolution does not see it either", PbComposeUtil.findSceneComposition(u, scene, sceneGid, orgId));
			PbComposeJobUtil.Tally after = PbComposeJobUtil.tally(u, bk, false);
			assertEquals(1, after.sceneCount);
			assertEquals("the foreign-group row does not make the scene current", 1, after.toCompose);
			assertEquals(0, after.current);

			/// The public submit sees the same tally and queues the job; the worker writes the scene's own row
			/// (LLM-sourced, or MIGRATED where no chat config resolves for this organization - either is a row in
			/// the book group) and the book-group list then resolves the scene to that row, not the older foreign row.
			long t0 = System.currentTimeMillis();
			Map<String, Object> out = PbComposeJobUtil.submitCompose(u, bk, false, false, (String) null, null, null);
			logger.info("P4-4 case10 submit: " + out);
			assertEquals(Boolean.TRUE, out.get("submitted"));
			assertEquals(Integer.valueOf(1), out.get("scenesToCompose"));
			assertEquals(Integer.valueOf(0), out.get("scenesCurrent"));
			jobId = (String) out.get("jobId");
			assertNotNull(jobId);
			AsyncJob job = awaitTerminalLoggingPhases(u, jobId, JOB_TIMEOUT_MS, "case10");
			logger.info("MEASURED: P4-4 case10 compose job wall " + (System.currentTimeMillis() - t0) + " ms -> " + job.getStatus()
				+ " error=" + job.getError() + " result=" + job.getResult());
			assertEquals(AsyncJob.Status.COMPLETED, job.getStatus());
			assertNull(PbComposeJobUtil.inflightJobId(bkOid));

			BaseRecord own = PbComposeUtil.findSceneComposition(u, scene, sceneGid, orgId);
			assertNotNull("the job wrote the scene's own composition", own);
			assertEquals("in the book group", sceneGid, ((Number) own.get(FieldNames.FIELD_GROUP_ID)).longValue());
			assertTrue("the foreign row is older than the scene's own", idOf(foreign) < idOf(own));
			Map<Long, BaseRecord> listedAfter = PbComposeUtil.listSceneCompositions(u, bk);
			assertEquals("one scene with a composition: " + listedAfter.keySet(), 1, listedAfter.size());
			assertEquals("the scene resolves to its own row, not the older foreign row", oidOf(own), oidOf(listedAfter.get(idOf(scene))));
			assertNotNull("the foreign row is untouched", PbComposeUtil.readComposition(u, oidOf(foreign), orgId));
		}
		finally {
			if(jobId != null) {
				AsyncJobRegistry.cancel(u, jobId);
				awaitTerminalQuietly(u, jobId, LOCAL_TIMEOUT_MS);
			}
			deleteQuietly(u, foreign);
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	/** A registry job that occupies a worker until the test counts {@code release} down. */
	private static final class Filler {
		final AsyncJob job;
		final CountDownLatch started;
		final CountDownLatch release;

		Filler(AsyncJob job, CountDownLatch started, CountDownLatch release) {
			this.job = job;
			this.started = started;
			this.release = release;
		}
	}

	private Filler filler(BaseRecord u, String key) {
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		AsyncJob job = AsyncJobRegistry.submit(u, "test.filler", key, new AsyncJobWork() {
			@Override
			public String run(AsyncJob j) throws Exception {
				started.countDown();
				release.await(LOCAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				return "{}";
			}
		});
		assertNotNull("filler " + key, job);
		return new Filler(job, started, release);
	}

	private interface Thrower {
		void run();
	}

	private static PictureBookException expect(int status, Thrower t) {
		try {
			t.run();
		}
		catch(PictureBookException e) {
			assertEquals("status of '" + e.getMessage() + "'", status, e.getStatus());
			return e;
		}
		fail("expected a " + status + " PictureBookException");
		return null;
	}

	private static int num(Map<?, ?> m, String key) {
		Object o = m.get(key);
		assertNotNull(key + " in " + m, o);
		return ((Number) o).intValue();
	}

	private static long lng(Map<?, ?> m, String key) {
		Object o = m.get(key);
		assertNotNull(key + " in " + m, o);
		return ((Number) o).longValue();
	}

	private int countNonTerminal(BaseRecord u) {
		return AsyncJobRegistry.activeCount(u);
	}

	/** Poll the registry (the way a client polls {@code GET /rest/job/{id}}) until the job is terminal. */
	private AsyncJob awaitTerminal(BaseRecord u, String jobId, long maxMs) throws InterruptedException {
		long deadline = System.currentTimeMillis() + maxMs;
		AsyncJob job = null;
		while(System.currentTimeMillis() < deadline) {
			job = AsyncJobRegistry.get(u, jobId);
			assertNotNull("job " + jobId + " must stay visible to its owner", job);
			if(job.isTerminal()) {
				return job;
			}
			Thread.sleep(POLL_MS);
		}
		fail("job " + jobId + " did not reach a terminal state within " + maxMs + "ms (last: "
			+ (job != null ? job.getStatus() + " " + job.getProgress().getPhase() : "null") + ")");
		return null;
	}

	private void awaitTerminalQuietly(BaseRecord u, String jobId, long maxMs) {
		try {
			long deadline = System.currentTimeMillis() + maxMs;
			while(System.currentTimeMillis() < deadline) {
				AsyncJob job = AsyncJobRegistry.get(u, jobId);
				if(job == null || job.isTerminal()) {
					return;
				}
				Thread.sleep(POLL_MS);
			}
			logger.warn("cleanup: job " + jobId + " still live after " + maxMs + " ms");
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Poll every ~2 s, logging each phase change, until terminal; fails only on timeout (no progress), never on time taken. */
	private AsyncJob awaitTerminalLoggingPhases(BaseRecord u, String jobId, long maxMs, String tag) throws InterruptedException {
		long start = System.currentTimeMillis();
		long deadline = start + maxMs;
		String lastPhase = null;
		AsyncJob job = null;
		while(System.currentTimeMillis() < deadline) {
			job = AsyncJobRegistry.get(u, jobId);
			assertNotNull("job " + jobId + " must stay visible to its owner", job);
			String phase = job.getProgress().getPhase();
			String key = job.getStatus() + " " + phase + " " + job.getProgress().getCurrent() + "/" + job.getProgress().getTotal();
			if(!key.equals(lastPhase)) {
				logger.info("P4-4 " + tag + " +" + (System.currentTimeMillis() - start) + " ms: " + key);
				lastPhase = key;
			}
			if(job.isTerminal()) {
				return job;
			}
			Thread.sleep(PHASE_POLL_MS);
		}
		fail("job " + jobId + " did not reach a terminal state within " + maxMs + "ms (last: " + lastPhase + ")");
		return null;
	}

	private BaseRecord liveConfig(BaseRecord u) {
		BaseRecord cfg = OlioTestUtil.getOllamaOpenAIConfig(u, CFG_NAME, testProperties);
		assertNotNull("chatConfig " + CFG_NAME, cfg);
		logger.info("[p44-live] route=" + testProperties.getProperty("test.llm.route") + " tier=" + testProperties.getProperty("test.llm.resolvedTier")
			+ " server=" + testProperties.getProperty("test.llm.connection.server") + " model=" + (Object) cfg.get("model"));
		return cfg;
	}

	/** Two scenes in story order with Ilse and Corin enrolled in both, so the LLM has a cast to resolve. */
	private List<BaseRecord> twoScenesWithCast(BaseRecord u, BaseRecord bk) throws Exception {
		BookContext bctx = PbBookUtil.openBookContext(u, bk);
		assertNotNull("The book must carry a world", bctx);
		String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Population group path", popPath);
		BaseRecord s1 = addScene(u, bk, 1, "The Climb", SETTING_1, ACTION_1, "urgent", TEXT_1);
		BaseRecord s2 = addScene(u, bk, 2, "The Lamp Room", SETTING_2, ACTION_2, "resolute", TEXT_2);
		BaseRecord ilse = createCharPerson(u, popPath, "Ilse Brandt", "Ilse", "Brandt", "female");
		BaseRecord corin = createCharPerson(u, popPath, "Corin Hale", "Corin", "Hale", "male");
		enrol(u, s1, ilse);
		enrol(u, s1, corin);
		enrol(u, s2, ilse);
		enrol(u, s2, corin);
		List<BaseRecord> out = new ArrayList<>();
		out.add(s1);
		out.add(s2);
		return out;
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p44" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-4 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		return bk;
	}

	/// Turn a freshly created STORY book into a CHAPBOOK as its owner (the one-field PATCH idiom of
	/// PbBookUtil.setAutoApprove) and re-read it so the record handed to the tally carries the type.
	private BaseRecord makeChapBook(BaseRecord u, BaseRecord bk) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(bk, OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_BOOK_TYPE);
		patch.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, PbBookTypeEnumType.CHAPBOOK.toString());
		assertNotNull("bookType patch", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readBook(u, oidOf(bk), orgOf(u));
		assertNotNull("re-read book", fresh);
		assertTrue("the book reads back as a ChapBook: " + enumWord(fresh, OlioFieldNames.FIELD_PB_BOOK_TYPE), PbNodeExecutor.isChapBook(fresh));
		return fresh;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			logger.info("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup " + bk.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
		}
	}

	private void deleteConfig(BaseRecord u, BaseRecord cfg) {
		if(cfg == null) {
			return;
		}
		BaseRecord conn = cfg.get("connection");
		deleteQuietly(u, cfg);
		deleteQuietly(u, conn);
	}

	private void deleteQuietly(BaseRecord u, BaseRecord rec) {
		if(rec == null) {
			return;
		}
		try {
			if(!ioContext.getAccessPoint().delete(u, rec)) {
				logger.warn("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + " was not deleted");
			}
		}
		catch(Exception e) {
			logger.error("cleanup: " + rec.getSchema() + " " + (Object) rec.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
	}

	/** A scene with optional setting/action/mood and its text; nulls are left unset. */
	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting, String action, String mood,
			String description) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		List<String> fields = new ArrayList<>();
		if(setting != null) {
			fields.add(OlioFieldNames.FIELD_PB_SETTING);
		}
		if(action != null) {
			fields.add(OlioFieldNames.FIELD_PB_ACTION);
		}
		if(mood != null) {
			fields.add(OlioFieldNames.FIELD_PB_MOOD);
		}
		if(description != null) {
			fields.add(FieldNames.FIELD_DESCRIPTION);
		}
		if(!fields.isEmpty()) {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, fields.toArray(new String[0]));
			if(setting != null) {
				patch.set(OlioFieldNames.FIELD_PB_SETTING, setting);
			}
			if(action != null) {
				patch.set(OlioFieldNames.FIELD_PB_ACTION, action);
			}
			if(mood != null) {
				patch.set(OlioFieldNames.FIELD_PB_MOOD, mood);
			}
			if(description != null) {
				patch.set(FieldNames.FIELD_DESCRIPTION, description);
			}
			assertNotNull("patch scene " + index, ioContext.getAccessPoint().update(u, patch));
		}
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("scene " + index + " readable", fresh);
		return fresh;
	}

	private BaseRecord createCharPerson(BaseRecord u, String groupPath, String name, String firstName, String lastName, String gender) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, firstName);
		if(lastName != null) {
			cp.set(FieldNames.FIELD_LAST_NAME, lastName);
		}
		cp.set(FieldNames.FIELD_GENDER, gender);
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("create charPerson " + name, created);
		cp.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		cp.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		return cp;
	}

	private void enrol(BaseRecord u, BaseRecord scene, BaseRecord cp) {
		assertTrue("enrol " + cp.get(FieldNames.FIELD_NAME) + " in scene " + scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX),
			ioContext.getMemberUtil().member(u, scene, OlioFieldNames.FIELD_PB_CHARACTERS, cp, null, true));
	}
}
