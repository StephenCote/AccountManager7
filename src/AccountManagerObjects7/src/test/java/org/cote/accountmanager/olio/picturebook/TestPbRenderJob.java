package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.thread.AsyncJobWork;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 1, P1-6: {@link PbRenderJobUtil} - the async render job behind
 * {@code POST .../scene/{id}/render}, {@code .../render-all} and {@code .../run/{runId}/cancel}.
 * <p>
 * What is under test: the plan (upstream closure, pinned/reused/non-executable left out, producers before
 * consumers, a stored-RUNNING node refused); "nothing to render" is a 409 that opens no run; a cancel that
 * lands while the job is still QUEUED closes the run CANCELLED through {@code onCancelledBeforeStart} and
 * releases the claim; two runs on one SD lane serialise (the second waits while the first holds the lane)
 * and a throwing stage closes its run FAILED; a consumer whose producer failed in the same run is FAILED
 * "blocked by" without being executed; a cancel that lands while the live {@code .39} backend is rendering
 * discards the bytes, reverts the node and closes the run CANCELLED; and a live render through the job
 * closes COMPLETED with a selected artifact stamped with its backend and duration.
 * <p>
 * Live {@code am7db}; the SD backend is never mocked. Where a stage must fail or be observed on demand an
 * {@link SDUtil} <i>subclass</i> is injected through the package-private {@code submit(..., SDUtil, ...)}
 * seam (the {@code executeNodeRun(..., SDUtil)} idiom of P1-3) and the rest of the job runs for real on the
 * registry's worker threads. The shared {@code pb2c-graph} fixture book is used; every node has a random
 * handle and every run assertion is scoped by the run's own objectId, never by status alone. Timings are
 * logged, never asserted.
 */
public class TestPbRenderJob extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";
	/// A second book of the same owner (TestPbSecurity's / TestPbReview's fixture), for the cross-book 404.
	private static final String OTHER_SLUG = "pb2c-sec-a";
	private static final String CHARS_PATH = "~/P16Chars";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	/// Render budgets are generous on purpose: a timing is never a pass/fail threshold here.
	private static final long RENDER_TIMEOUT_MS = 20L * 60L * 1000L;
	private static final long LOCAL_TIMEOUT_MS = 5L * 60L * 1000L;
	private static final long POLL_MS = 250L;

	/// Hand-written (does not start with PictureBookUtil's generated-prompt prefix) and CLOTHED, so the
	/// render never falls into the bare-record nude-render trap (PictureBookV3Implementation.md §6.L).
	private static final String PORTRAIT_PROMPT = "a stocky man in his fifties with a close-cropped grey beard and deep-set brown eyes,"
		+ " wearing a patched canvas work jacket over a high-collared linen shirt, head and shoulders portrait, warm lantern light";

	@Before
	public void runSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/**
	 * A terminal whose whole upstream closure is pinned has nothing to execute: 409 before any run row is
	 * written and before the SD client is even required (a null client is handed in).
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case01_nothingToRenderIs409AndOpensNoRun() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		BaseRecord pinned = plainNode(u, wf, "p16-c01-pinned", PbNodeTypeEnumType.PORTRAIT, 0);
		assertTrue("precondition: pin", PbGraphUtil.setPinned(u, pinned, true));
		long newestBefore = newestRunId(u, wf);
		long inflightBefore = PbNodeInflight.size();

		try {
			PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(idOf(pinned)), (SDUtil) null, swarmServer(), PbRenderJobUtil.KIND_RENDER);
			fail("a fully pinned plan must be refused");
		}
		catch(PictureBookException e) {
			logger.info("P1-6 case01 refusal: " + e.getStatus() + " " + e.getMessage());
			assertEquals("409", 409, e.getStatus());
			assertTrue("the message says nothing is to render: " + e.getMessage(), e.getMessage().startsWith("Nothing to render"));
			assertTrue("the message counts the pinned node: " + e.getMessage(), e.getMessage().contains("1 pinned"));
		}
		assertEquals("no run row is written", newestBefore, newestRunId(u, wf));
		assertEquals("no claim is left behind", inflightBefore, PbNodeInflight.size());
	}

	/**
	 * The plan from one terminal: its producers are included, a pinned producer and a SKIPPED (reused)
	 * producer are counted and left out, a non-executable producer (SOURCE_TEXT) is ignored, and the
	 * execution order is topological - the producer runs first even though its ordinal is higher than the
	 * consumer's. A node whose stored status is RUNNING with no in-flight claim (a JVM died with it) is
	 * refused with 409 naming restart recovery, not silently re-planned.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case02_planOrdersProducersFirstAndRefusesStoredRunning() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		String gp = PbBookUtil.workflowGroupPath(SLUG);
		BaseRecord consumer = plainNode(u, wf, "p16-c02-C", PbNodeTypeEnumType.COMPOSITE, 0);
		BaseRecord pending = plainNode(u, wf, "p16-c02-A", PbNodeTypeEnumType.PORTRAIT, 5);
		BaseRecord pinned = plainNode(u, wf, "p16-c02-P", PbNodeTypeEnumType.PORTRAIT, 1);
		BaseRecord skipped = plainNode(u, wf, "p16-c02-K", PbNodeTypeEnumType.PORTRAIT, 2);
		BaseRecord source = plainNode(u, wf, "p16-c02-S", PbNodeTypeEnumType.SOURCE_TEXT, 3);
		assertTrue(PbGraphUtil.setPinned(u, pinned, true));
		assertTrue(PbGraphUtil.persistStatus(u, skipped, PbNodeStatusEnumType.SKIPPED));
		assertNotNull(PbGraphUtil.addBinding(u, wf, consumer, PbPipelineUtil.ROLE_PORTRAIT, 0, pending, null, gp));
		assertNotNull(PbGraphUtil.addBinding(u, wf, consumer, PbPipelineUtil.ROLE_PORTRAIT, 1, pinned, null, gp));
		assertNotNull(PbGraphUtil.addBinding(u, wf, consumer, PbPipelineUtil.ROLE_PORTRAIT, 2, skipped, null, gp));
		assertNotNull(PbGraphUtil.addBinding(u, wf, consumer, "source", 0, source, null, gp));
		PbStaleUtil.clear();

		PbRenderJobUtil.Plan plan = PbRenderJobUtil.plan(u, bk, wf, Collections.singletonList(idOf(consumer)));
		logger.info("P1-6 case02 plan execute=" + plan.handles() + " pinned=" + plan.pinned + " reused=" + plan.reused + " needsSd=" + plan.needsSd);
		assertEquals("producer first, consumer second, nothing else",
			Arrays.asList(handleOf(pending), handleOf(consumer)), plan.handles());
		assertEquals("the pinned producer is counted", 1, plan.pinned);
		assertEquals("the SKIPPED producer is reused", 1, plan.reused);
		assertTrue("a PORTRAIT/COMPOSITE plan needs the SD backend", plan.needsSd);
		assertEquals("scope label for one terminal", "this scene", plan.scopeLabel);

		/// Stored RUNNING, no claim in this JVM: the recompute would read READY/PENDING (no inputHash), the
		/// plan must not.
		BaseRecord orphan = plainNode(u, wf, "p16-c02-R", PbNodeTypeEnumType.PORTRAIT, 0);
		assertTrue(PbGraphUtil.persistStatus(u, orphan, PbNodeStatusEnumType.RUNNING));
		assertNull("precondition: no in-flight claim", PbNodeInflight.runOf(idOf(orphan)));
		try {
			PbStaleUtil.clear();
			BaseRecord stored = PbGraphUtil.readNode(u, orphan.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
			assertEquals("precondition: the stamp is persisted", PbNodeStatusEnumType.RUNNING.name(), status(stored, OlioFieldNames.FIELD_PB_NODE_STATUS));
			PbRenderJobUtil.plan(u, bk, wf, Collections.singletonList(idOf(orphan)));
			fail("a stored-RUNNING node must be refused");
		}
		catch(PictureBookException e) {
			logger.info("P1-6 case02 orphan refusal: " + e.getStatus() + " " + e.getMessage());
			assertEquals(409, e.getStatus());
			assertTrue(e.getMessage(), e.getMessage().contains(handleOf(orphan) + " is RUNNING"));
			assertTrue("names restart recovery, not a run: " + e.getMessage(), e.getMessage().contains("restart recovery"));
		}
		finally {
			assertTrue("fixture reset", PbGraphUtil.persistStatus(u, orphan, PbNodeStatusEnumType.PENDING));
		}
	}

	/**
	 * Cancel while QUEUED. Both worker threads are held by filler jobs so the render job never starts;
	 * the cancel lands through {@link PbRunCancelRegistry}; when the pool frees the registry calls
	 * {@code onCancelledBeforeStart} instead of {@code run}: the run row closes CANCELLED "Cancelled before
	 * starting", the node claim is released, the cancel entry is gone and the node was never touched.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case03_cancelWhileQueuedClosesRunThroughOnCancelledBeforeStart() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord node = plainNode(u, wf, "p16-c03", PbNodeTypeEnumType.PORTRAIT, 0);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		long nodeId = idOf(node);

		CountDownLatch fillersStarted = new CountDownLatch(AsyncJobRegistry.MAX_CONCURRENT_JOBS);
		CountDownLatch releaseFillers = new CountDownLatch(1);
		List<AsyncJob> fillers = new ArrayList<>();
		try {
			for(int i = 0; i < AsyncJobRegistry.MAX_CONCURRENT_JOBS; i++) {
				AsyncJob f = AsyncJobRegistry.submit(u, "test.filler", "p16-c03-" + i, new AsyncJobWork() {
					@Override
					public String run(AsyncJob job) throws Exception {
						fillersStarted.countDown();
						releaseFillers.await(LOCAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
						return "{}";
					}
				});
				assertNotNull("filler " + i, f);
				fillers.add(f);
			}
			assertTrue("both worker threads must be occupied", fillersStarted.await(30, TimeUnit.SECONDS));

			SDUtil never = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
				@Override
				public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
						int batch, boolean hires, int seed) {
					throw new IllegalStateException("test: a QUEUED job that was cancelled must never reach the backend");
				}
			};
			Map<String, Object> dto = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(nodeId), never, swarmServer(), PbRenderJobUtil.KIND_RENDER);
			logger.info("P1-6 case03 submit dto=" + dto);
			String jobId = (String) dto.get("jobId");
			String runOid = (String) dto.get("runObjectId");
			assertNotNull(jobId);
			assertNotNull(runOid);
			assertEquals("queued", dto.get("jobStatus"));
			assertEquals(Integer.valueOf(1), dto.get("plannedNodeCount"));
			assertEquals("the claim is held while queued", runOid, PbNodeInflight.runOf(nodeId));
			assertNotNull("the cancel token is published while queued", PbRunCancelRegistry.peek(runOid));

			assertTrue("the cancel is accepted", PbRunCancelRegistry.cancel(runOid));
			assertFalse("a second cancel is a no-op", PbRunCancelRegistry.cancel(runOid));
			AsyncJob queued = AsyncJobRegistry.get(u, jobId);
			assertNotNull(queued);
			assertEquals("still QUEUED until a worker frees", AsyncJob.Status.QUEUED, queued.getStatus());

			releaseFillers.countDown();
			AsyncJob done = awaitTerminal(u, jobId, LOCAL_TIMEOUT_MS);
			logger.info("P1-6 case03 job " + done.getStatus() + " error=" + done.getError() + " elapsed=" + done.getElapsedSeconds() + "s");
			assertEquals(AsyncJob.Status.CANCELLED, done.getStatus());
			assertEquals("Cancelled before starting", done.getError());

			BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
			assertNotNull(run);
			assertEquals(PbRunStatusEnumType.CANCELLED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals("Cancelled before starting", run.get(OlioFieldNames.FIELD_PB_ERROR));
			assertNotNull("completedAt", run.get(OlioFieldNames.FIELD_PB_COMPLETED_AT));
			assertNull("the claim is released", PbNodeInflight.runOf(nodeId));
			assertNull("the cancel entry is gone", PbRunCancelRegistry.peek(runOid));
			BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
			assertEquals("the node was never stamped", PbNodeStatusEnumType.PENDING.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertNull("no lastError", fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR));
		}
		finally {
			releaseFillers.countDown();
			for(AsyncJob f : fillers) {
				awaitTerminal(u, f.getJobId(), 30_000L);
			}
		}
	}

	/**
	 * One lane per SD server, one permit: two single-node runs submitted back to back on the same lane key
	 * run on two worker threads, but the second's backend call starts only after the first's returns, and
	 * while the first holds the lane the second is observed waiting. Both stages throw, so both runs close
	 * FAILED carrying the stage message and both nodes are FAILED with {@code lastError}.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case04_sameServerRunsSerialiseOnTheLaneAndAThrowingStageFailsTheRun() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		assertEquals("precondition: one permit per lane in this JVM", 1, PbRenderLanes.permits());
		/// A lane key of this test's own, so another lane's traffic cannot confuse the measurement; the
		/// injected client decides what the "backend" does.
		String lane = "http://p16-lane-" + UUID.randomUUID().toString().substring(0, 8) + ":7801/";
		BaseRecord cp1 = createPortraitCharacter(u, "c04a");
		BaseRecord cp2 = createPortraitCharacter(u, "c04b");
		BaseRecord n1 = portraitNode(u, wf, "p16-c04a", cp1.get(FieldNames.FIELD_OBJECT_ID), null);
		BaseRecord n2 = portraitNode(u, wf, "p16-c04b", cp2.get(FieldNames.FIELD_OBJECT_ID), null);

		Map<String, Long> enter = new ConcurrentHashMap<>();
		Map<String, Long> exit = new ConcurrentHashMap<>();
		Map<String, Integer> availableOnEntry = new ConcurrentHashMap<>();
		AtomicInteger entrants = new AtomicInteger();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		String message = "test: lane probe " + UUID.randomUUID();
		SDUtil probe = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				int order = entrants.incrementAndGet();
				enter.put(name, Long.valueOf(System.nanoTime()));
				availableOnEntry.put(name, Integer.valueOf(PbRenderLanes.available(lane)));
				if(order == 1) {
					firstEntered.countDown();
					try {
						releaseFirst.await(LOCAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
					}
					catch(InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
				exit.put(name, Long.valueOf(System.nanoTime()));
				throw new RuntimeException(message + " " + name);
			}
		};

		Map<String, Object> d1 = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(idOf(n1)), probe, lane, PbRenderJobUtil.KIND_RENDER);
		Map<String, Object> d2 = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(idOf(n2)), probe, lane, PbRenderJobUtil.KIND_RENDER);
		logger.info("P1-6 case04 dto1=" + d1 + " dto2=" + d2);
		String run1 = (String) d1.get("runObjectId");
		String run2 = (String) d2.get("runObjectId");
		assertTrue("the first backend call must be reached", firstEntered.await(2, TimeUnit.MINUTES));

		/// While the first holds the lane: the second run is RUNNING (its row and node stamped) but waiting
		/// on the semaphore, so exactly one waiter and no permits.
		int waiting = -1;
		long deadline = System.currentTimeMillis() + 60_000L;
		while(System.currentTimeMillis() < deadline) {
			waiting = PbRenderLanes.waiting(lane);
			if(waiting == 1) {
				break;
			}
			Thread.sleep(POLL_MS);
		}
		logger.info("P1-6 case04 while first holds the lane: available=" + PbRenderLanes.available(lane) + " waiting=" + waiting + " entrants=" + entrants.get());
		assertEquals("exactly one run waits for the lane", 1, waiting);
		assertEquals("no permit is free while the first stage runs", 0, PbRenderLanes.available(lane));
		assertEquals("the second backend call has not started", 1, entrants.get());
		String firstName = enter.keySet().iterator().next();
		assertEquals("the lane was taken before the first backend call", Integer.valueOf(0), availableOnEntry.get(firstName));

		releaseFirst.countDown();
		AsyncJob j1 = awaitTerminal(u, (String) d1.get("jobId"), LOCAL_TIMEOUT_MS);
		AsyncJob j2 = awaitTerminal(u, (String) d2.get("jobId"), LOCAL_TIMEOUT_MS);
		logger.info("P1-6 case04 job1=" + j1.getStatus() + " job2=" + j2.getStatus() + " enter=" + enter + " exit=" + exit);
		assertEquals("both backend calls were made", 2, entrants.get());
		String secondName = null;
		for(String k : enter.keySet()) {
			if(!k.equals(firstName)) {
				secondName = k;
			}
		}
		assertNotNull(secondName);
		assertTrue("the second backend call starts after the first returns",
			enter.get(secondName).longValue() >= exit.get(firstName).longValue());
		assertEquals("the lane was free again when the second entered... and then taken by it", Integer.valueOf(0), availableOnEntry.get(secondName));
		assertEquals("the lane is released afterwards", 1, PbRenderLanes.available(lane));
		assertEquals(0, PbRenderLanes.waiting(lane));

		/// A throwing stage: job COMPLETED (the work returns its summary; the run carries the failure).
		for(String runOid : Arrays.asList(run1, run2)) {
			BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
			assertNotNull(run);
			assertEquals("run " + runOid + " closes FAILED", PbRunStatusEnumType.FAILED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals(Integer.valueOf(1), run.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));
			assertEquals(Integer.valueOf(0), run.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
			String err = run.get(OlioFieldNames.FIELD_PB_ERROR);
			assertTrue("run error names the failed node: " + err, err != null && err.startsWith("1 node(s) failed: "));
			assertNull("claims are released", PbRunCancelRegistry.peek(runOid));
		}
		for(BaseRecord n : Arrays.asList(n1, n2)) {
			BaseRecord fresh = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertEquals(PbNodeStatusEnumType.FAILED.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
			String last = fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
			assertTrue("lastError carries the stage message: " + last, last != null && last.contains(message));
			assertNull(PbNodeInflight.runOf(idOf(n)));
		}
		assertEquals(AsyncJob.Status.COMPLETED, j1.getStatus());
		assertEquals(AsyncJob.Status.COMPLETED, j2.getStatus());
		Map<?, ?> r1 = JSONUtil.importObject(j1.getResult(), Map.class);
		assertEquals("the job result carries the run status", PbRunStatusEnumType.FAILED.name(), r1.get("runStatus"));
		assertTrue("durationMs is recorded on the result (never asserted beyond presence)", r1.get("durationMs") instanceof Number);
	}

	/**
	 * A failed producer blocks its consumer in the same run: A (PORTRAIT) throws, C (COMPOSITE, bound to A)
	 * is FAILED "blocked by failed producer A" without the backend being asked for it, and the run closes
	 * FAILED counting both.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case05_failedProducerBlocksItsConsumer() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String gp = PbBookUtil.workflowGroupPath(SLUG);
		BaseRecord cp = createPortraitCharacter(u, "c05");
		BaseRecord producer = portraitNode(u, wf, "p16-c05-A", cp.get(FieldNames.FIELD_OBJECT_ID), null);
		BaseRecord consumer = plainNode(u, wf, "p16-c05-C", PbNodeTypeEnumType.COMPOSITE, 0);
		assertNotNull(PbGraphUtil.addBinding(u, wf, consumer, PbPipelineUtil.ROLE_PORTRAIT, 0, producer, null, gp));

		List<String> calls = Collections.synchronizedList(new ArrayList<>());
		String message = "test: producer down " + UUID.randomUUID();
		SDUtil failing = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				calls.add(name);
				throw new RuntimeException(message);
			}
		};
		Map<String, Object> dto = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(idOf(consumer)), failing, swarmServer(), PbRenderJobUtil.KIND_RENDER);
		logger.info("P1-6 case05 dto=" + dto);
		assertEquals(Arrays.asList(handleOf(producer), handleOf(consumer)), dto.get("planned"));
		AsyncJob job = awaitTerminal(u, (String) dto.get("jobId"), LOCAL_TIMEOUT_MS);
		logger.info("P1-6 case05 job=" + job.getStatus() + " result=" + job.getResult() + " calls=" + calls);

		assertEquals("only the producer reached the backend", 1, calls.size());
		BaseRecord run = PbGraphUtil.readRun(u, (String) dto.get("runObjectId"), orgId);
		assertEquals(PbRunStatusEnumType.FAILED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals(Integer.valueOf(2), run.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));
		assertEquals("2 node(s) failed: " + handleOf(producer) + ", " + handleOf(consumer), run.get(OlioFieldNames.FIELD_PB_ERROR));

		BaseRecord a = PbGraphUtil.readNode(u, producer.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertEquals(PbNodeStatusEnumType.FAILED.name(), status(a, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertTrue(String.valueOf((Object) a.get(OlioFieldNames.FIELD_PB_LAST_ERROR)).contains(message));
		BaseRecord c = PbGraphUtil.readNode(u, consumer.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertEquals(PbNodeStatusEnumType.FAILED.name(), status(c, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertEquals("blocked by failed producer " + handleOf(producer), c.get(OlioFieldNames.FIELD_PB_LAST_ERROR));
		assertNull(PbNodeInflight.runOf(idOf(producer)));
		assertNull(PbNodeInflight.runOf(idOf(consumer)));

		Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
		assertEquals(Integer.valueOf(2), result.get("failedNodeCount"));
		List<?> nodes = (List<?>) result.get("nodes");
		assertEquals(2, nodes.size());
		Map<?, ?> cOutcome = (Map<?, ?>) nodes.get(1);
		assertEquals(handleOf(consumer), cOutcome.get("handle"));
		assertEquals("FAILED", cOutcome.get("status"));
		assertNull("a blocked node has no stage duration (it never ran)", cOutcome.get("durationMs"));
	}

	/**
	 * Cancel mid-stage on the live {@code .39} backend. The injected client flips the run's cancel flag the
	 * moment the executor calls it and then lets the REAL render proceed: {@code discardIfCancelled} throws
	 * after the backend returns, the bytes are discarded, no artifact exists, the node is returned to its
	 * pre-stamp status, the run closes CANCELLED "after 0 of 1 stage(s)", and the job is CANCELLED carrying
	 * the summary. Claims and the cancel entry are gone.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case06_liveCancelMidStageDiscardsTheRenderAndRevertsTheNode() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String server = swarmServer();
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P1-6 live cancel mid-stage");
		BaseRecord cp = createPortraitCharacter(u, "c06");
		BaseRecord node = portraitNode(u, wf, "p16-c06", cp.get(FieldNames.FIELD_OBJECT_ID), fastOverride(renderCfg));
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		long nodeId = idOf(node);

		AtomicReference<String> runOidRef = new AtomicReference<>();
		CountDownLatch published = new CountDownLatch(1);
		Map<String, Object> observed = new ConcurrentHashMap<>();
		SDUtil cancelling = new SDUtil(SDAPIEnumType.SWARM, server) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				try {
					published.await(2, TimeUnit.MINUTES);
				}
				catch(InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				String runOid = runOidRef.get();
				BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
				observed.put("runStatus", String.valueOf((Object) status(run, OlioFieldNames.FIELD_PB_RUN_STATUS)));
				observed.put("nodeStatus", String.valueOf((Object) status(PbGraphUtil.readNode(u, nodeOid, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS)));
				observed.put("cancelAccepted", Boolean.valueOf(PbRunCancelRegistry.cancel(runOid)));
				long t0 = System.currentTimeMillis();
				List<BaseRecord> images;
				try {
					images = super.createImage(user, groupPath, sdConfig, name, batch, hires, seed);
				}
				catch(RuntimeException e) {
					observed.put("backendError", String.valueOf(e));
					throw e;
				}
				observed.put("backendDurationMs", Long.valueOf(System.currentTimeMillis() - t0));
				observed.put("imagesReturned", Integer.valueOf(images != null ? images.size() : -1));
				return images;
			}
		};

		long t0 = System.currentTimeMillis();
		Map<String, Object> dto = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(nodeId), cancelling, server, PbRenderJobUtil.KIND_RENDER);
		String runOid = (String) dto.get("runObjectId");
		runOidRef.set(runOid);
		published.countDown();
		AsyncJob job = awaitTerminal(u, (String) dto.get("jobId"), RENDER_TIMEOUT_MS);
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P1-6 case06 live cancel durationMs=" + durationMs + " job=" + job.getStatus() + " observed=" + observed + " result=" + job.getResult());

		assertEquals("the backend was reached", Boolean.TRUE, observed.get("cancelAccepted"));
		assertEquals(PbRunStatusEnumType.RUNNING.name(), observed.get("runStatus"));
		assertEquals(PbNodeStatusEnumType.RUNNING.name(), observed.get("nodeStatus"));
		Object imagesReturned = observed.get("imagesReturned");
		assertNotNull("createImage never returned - backendError=" + observed.get("backendError") + " job=" + job.getStatus()
			+ " error=" + job.getError() + " result=" + job.getResult(), imagesReturned);
		assertTrue("the real render returned image(s): " + imagesReturned, ((Integer) imagesReturned).intValue() > 0);

		assertEquals(AsyncJob.Status.CANCELLED, job.getStatus());
		Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
		assertNotNull("the cancelled job keeps its summary", result);
		assertEquals(PbRunStatusEnumType.CANCELLED.name(), result.get("runStatus"));
		Map<?, ?> outcome = (Map<?, ?>) ((List<?>) result.get("nodes")).get(0);
		assertEquals("CANCELLED", outcome.get("status"));
		assertTrue("the outcome says the bytes were discarded: " + outcome.get("error"),
			String.valueOf(outcome.get("error")).contains("discarded"));

		BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
		assertEquals(PbRunStatusEnumType.CANCELLED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals("Cancelled after 0 of 1 stage(s)", run.get(OlioFieldNames.FIELD_PB_ERROR));
		assertEquals(Integer.valueOf(0), run.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
		assertNotNull(run.get(OlioFieldNames.FIELD_PB_COMPLETED_AT));

		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		String nodeStatus = status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS);
		assertTrue("the node is returned to a pre-run status, not left RUNNING/DONE: " + nodeStatus,
			PbNodeStatusEnumType.READY.name().equals(nodeStatus) || PbNodeStatusEnumType.PENDING.name().equals(nodeStatus));
		assertEquals("revertedTo agrees with the stored status", nodeStatus, outcome.get("revertedTo"));
		assertNull("no inputHash: nothing was recorded", fresh.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
		assertTrue("no artifact exists for the cancelled stage", PbArtifactUtil.listChain(u, fresh, PbPipelineUtil.ROLE_PORTRAIT).isEmpty());
		assertNull(PbNodeInflight.runOf(nodeId));
		assertNull(PbRunCancelRegistry.peek(runOid));
	}

	/**
	 * The happy path through the job on the live {@code .39} backend with the real client the transport
	 * would build: COMPLETED run, DONE node with an inputHash, a selected artifact stamped with its backend
	 * and a recorded duration, and real PNG bytes written to {@code target/} for inspection.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case07_liveRenderThroughTheJobCompletes() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String server = swarmServer();
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P1-6 live render through the job");
		BaseRecord cp = createPortraitCharacter(u, "c07");
		String cpOid = cp.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord node = portraitNode(u, wf, "p16-c07", cpOid, fastOverride(renderCfg));
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		long nodeId = idOf(node);

		long t0 = System.currentTimeMillis();
		Map<String, Object> dto = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(nodeId), SDAPIEnumType.SWARM.name(), server, PbRenderJobUtil.KIND_RENDER);
		logger.info("P1-6 case07 dto=" + dto);
		/// With a free pool the worker may already have started by the time the DTO is built, so the
		/// accepted states are queued|running; only the filler-held cases (03, 05) can assert queued.
		assertTrue("accepted job at submit: " + dto.get("jobStatus"), Arrays.asList("queued", "running").contains(dto.get("jobStatus")));
		/// The DTO reads runStatus off the run handle the worker stamps RUNNING in place (PbGraphUtil.markRunning)
		/// as it dispatches, so the same race applies: a queued job's run must still read PENDING, a job
		/// already running may have reached the stamp (RUNNING) or not yet (PENDING).
		String submitRunStatus = String.valueOf((Object) dto.get("runStatus")).toUpperCase();
		if("queued".equals(dto.get("jobStatus"))) {
			assertEquals("a queued job's run is still PENDING", PbRunStatusEnumType.PENDING.name(), submitRunStatus);
		}
		else {
			assertTrue("a running job's run is PENDING or RUNNING at submit: " + submitRunStatus,
				Arrays.asList(PbRunStatusEnumType.PENDING.name(), PbRunStatusEnumType.RUNNING.name()).contains(submitRunStatus));
		}
		String runOid = (String) dto.get("runObjectId");
		AsyncJob job = awaitTerminal(u, (String) dto.get("jobId"), RENDER_TIMEOUT_MS);
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P1-6 case07 live render durationMs=" + durationMs + " job=" + job.getStatus() + " result=" + job.getResult());

		assertEquals("job error: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
		Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
		assertEquals(PbRunStatusEnumType.COMPLETED.name(), result.get("runStatus"));
		assertEquals(Integer.valueOf(1), result.get("executedNodeCount"));
		Map<?, ?> outcome = (Map<?, ?>) ((List<?>) result.get("nodes")).get(0);
		assertEquals("DONE", outcome.get("status"));
		Boolean bookAuto = (bk.hasField(OlioFieldNames.FIELD_PB_AUTO_APPROVE) ? bk.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE) : null);
		boolean expectAuto = (bookAuto != null && bookAuto.booleanValue());
		logger.info("P1-6 case07 fixture book autoApprove=" + bookAuto + " outcome.autoApproved=" + outcome.get(PbNodeExecutor.KEY_AUTO_APPROVED)
			+ " skipped=" + outcome.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
		assertEquals("the outcome reports the book's switch", Boolean.valueOf(expectAuto), outcome.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		String artifactOid = (String) outcome.get("artifactObjectId");
		assertNotNull("the outcome names the artifact", artifactOid);

		BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
		assertEquals(PbRunStatusEnumType.COMPLETED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals(Integer.valueOf(1), run.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
		assertEquals(Integer.valueOf(0), run.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));
		assertNull(run.get(OlioFieldNames.FIELD_PB_ERROR));
		assertNull(PbNodeInflight.runOf(nodeId));
		assertNull(PbRunCancelRegistry.peek(runOid));

		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals(PbNodeStatusEnumType.DONE.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String inputHash = fresh.get(OlioFieldNames.FIELD_PB_INPUT_HASH);
		assertTrue(inputHash != null && !inputHash.isBlank());
		String promptText = fresh.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT);
		assertTrue("the prompt comes from the character's profile: " + promptText, promptText != null && promptText.contains("canvas work jacket"));

		BaseRecord artifact = PbArtifactUtil.readArtifact(u, artifactOid, orgId);
		assertNotNull(artifact);
		assertEquals(Boolean.TRUE, artifact.get(OlioFieldNames.FIELD_PB_SELECTED));
		assertEquals("backend is stamped", SDAPIEnumType.SWARM.name(), status(artifact, OlioFieldNames.FIELD_PB_BACKEND));
		Object artifactDurationMs = artifact.get(OlioFieldNames.FIELD_PB_DURATION_MS);
		logger.info("P1-6 case07 artifact.durationMs=" + artifactDurationMs + " seed=" + artifact.get(OlioFieldNames.FIELD_PB_SEED)
			+ " byteLength=" + artifact.get(OlioFieldNames.FIELD_PB_BYTE_LENGTH) + " reviewStatus=" + status(artifact, OlioFieldNames.FIELD_PB_REVIEW_STATUS));
		assertTrue("durationMs is recorded (presence only)", artifactDurationMs instanceof Number && ((Number) artifactDurationMs).longValue() > 0L);

		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull(dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			IOSystem.getActiveContext().getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		BaseRecord data = freshReadData(u, dataOid);
		assertNotNull(data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertTrue("real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		File png = new File("target/p16-portrait-" + System.currentTimeMillis() + ".png");
		FileUtil.emitFile(png.getPath(), bytes);
		assertTrue(png.exists() && png.length() == bytes.length);
		logger.info("P1-6 case07 PNG written: " + png.getAbsolutePath() + " (" + bytes.length + " bytes)");
	}

	/**
	 * The submit-time claim and the facade's cancel gates; no backend is needed. While run A is QUEUED (both
	 * worker threads held by fillers) its node is claimed, so a second submit for the same node is refused
	 * 409 "is being rendered by run A" by the pre-check, before any run row is written. Through
	 * {@link PbServiceFacade#cancelRun}: A addressed through another book of the same owner is 404 (and the
	 * token is untouched), an unknown run is 404, the first cancel through its own book is accepted and
	 * reports the status it read, a second is 409 "already being cancelled", and once A has closed CANCELLED
	 * a third is 409 naming the terminal status. Finally a PENDING run row with no job behind it in this JVM
	 * is 409 "no job for it is in flight".
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case08_inFlightClaimRefusesASecondSubmitAndCancelRunGatesByBookAndStatus() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord other = otherBook(u);
		String otherOid = other.get(FieldNames.FIELD_OBJECT_ID);
		assertFalse("precondition: two distinct books", bookOid.equals(otherOid));
		/// The other book must have a workflow, or the 404 below would be requireWorkflow's, not requireRunOfBook's.
		BaseRecord otherWf = PbGraphUtil.getCreateWorkflow(u, other, PbBookUtil.workflowGroupPath(OTHER_SLUG));
		assertNotNull("the other book's workflow", otherWf);
		assertTrue("precondition: two distinct workflows", idOf(wf) != idOf(otherWf));
		BaseRecord node = plainNode(u, wf, "p16-c08", PbNodeTypeEnumType.PORTRAIT, 0);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		long nodeId = idOf(node);

		CountDownLatch fillersStarted = new CountDownLatch(AsyncJobRegistry.MAX_CONCURRENT_JOBS);
		CountDownLatch releaseFillers = new CountDownLatch(1);
		List<AsyncJob> fillers = new ArrayList<>();
		try {
			for(int i = 0; i < AsyncJobRegistry.MAX_CONCURRENT_JOBS; i++) {
				AsyncJob f = AsyncJobRegistry.submit(u, "test.filler", "p16-c08-" + i, new AsyncJobWork() {
					@Override
					public String run(AsyncJob job) throws Exception {
						fillersStarted.countDown();
						releaseFillers.await(LOCAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
						return "{}";
					}
				});
				assertNotNull("filler " + i, f);
				fillers.add(f);
			}
			assertTrue("both worker threads must be occupied", fillersStarted.await(30, TimeUnit.SECONDS));

			SDUtil never = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
				@Override
				public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
						int batch, boolean hires, int seed) {
					throw new IllegalStateException("test: no stage of case08 may reach the backend");
				}
			};
			Map<String, Object> dtoA = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(nodeId), never, swarmServer(), PbRenderJobUtil.KIND_RENDER);
			logger.info("P1-6 case08 run A dto=" + dtoA);
			String jobA = (String) dtoA.get("jobId");
			String runA = (String) dtoA.get("runObjectId");
			assertNotNull(jobA);
			assertNotNull(runA);
			assertEquals("queued", dtoA.get("jobStatus"));
			assertEquals("A holds the claim while queued", runA, PbNodeInflight.runOf(nodeId));
			long newestAfterA = newestRunId(u, wf);

			/// P1-10 S0(b): the workflow view names the submitted run (lastRun is re-read through a
			/// projection that carries it; workflowRequest() omits it), and the run DTO carries the job id
			/// for as long as the token is registered - QUEUED counts, the token is published at submit.
			Map<String, Object> wv = PbServiceFacade.workflowView(u, bookOid);
			assertNotNull("workflowView.lastRunObjectId must be set after a submit: " + wv.get("lastRunObjectId"), wv.get("lastRunObjectId"));
			assertEquals("workflowView.lastRunObjectId must be the submitted run", runA, wv.get("lastRunObjectId"));
			Map<String, Object> inFlight = PbServiceFacade.getRun(u, bookOid, runA);
			logger.info("P1-10 case08 in-flight run dto=" + inFlight);
			assertEquals(Boolean.TRUE, inFlight.get("inFlightInThisProcess"));
			assertNotNull("runDto.jobId must be present while in flight", inFlight.get("jobId"));
			assertEquals("runDto.jobId must be the submitted job", jobA, inFlight.get("jobId"));

			/// A second submit for the claimed node: refused by the pre-check, so no run row is written.
			try {
				PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(nodeId), never, swarmServer(), PbRenderJobUtil.KIND_RENDER);
				fail("a node claimed by a queued run must be refused");
			}
			catch(PictureBookException e) {
				logger.info("P1-6 case08 second submit refusal: " + e.getStatus() + " " + e.getMessage());
				assertEquals(409, e.getStatus());
				assertEquals("Node " + handleOf(node) + " is being rendered by run " + runA, e.getMessage());
			}
			assertEquals("the refusal wrote no run row", newestAfterA, newestRunId(u, wf));
			assertEquals("A still holds the claim", runA, PbNodeInflight.runOf(nodeId));

			/// Facade gates while A is QUEUED.
			try {
				PbServiceFacade.cancelRun(u, otherOid, runA);
				fail("a run addressed through another book must be 404");
			}
			catch(PictureBookException e) {
				logger.info("P1-6 case08 cross-book cancel: " + e.getStatus() + " " + e.getMessage());
				assertEquals("cross-book addressing is 404, not 403 and not a cancel", 404, e.getStatus());
				assertTrue(e.getMessage(), e.getMessage().contains("not found in this book"));
			}
			assertNotNull("the token is still published", PbRunCancelRegistry.peek(runA));
			assertFalse("the cross-book attempt did not flip the token", PbRunCancelRegistry.peek(runA).isCancelled());
			try {
				PbServiceFacade.cancelRun(u, bookOid, UUID.randomUUID().toString());
				fail("an unknown run must be 404");
			}
			catch(PictureBookException e) {
				assertEquals(404, e.getStatus());
			}

			Map<String, Object> cancelled = PbServiceFacade.cancelRun(u, bookOid, runA);
			logger.info("P1-6 case08 cancel through the book: " + cancelled);
			assertEquals(runA, cancelled.get("runObjectId"));
			assertEquals("the status as read before the cancel", PbRunStatusEnumType.PENDING.name(), cancelled.get("runStatus"));
			assertEquals(Boolean.TRUE, cancelled.get("cancelRequested"));
			assertTrue("the token is flipped", PbRunCancelRegistry.peek(runA).isCancelled());
			try {
				PbServiceFacade.cancelRun(u, bookOid, runA);
				fail("a second cancel of a run still in flight must be 409");
			}
			catch(PictureBookException e) {
				logger.info("P1-6 case08 repeat cancel: " + e.getStatus() + " " + e.getMessage());
				assertEquals(409, e.getStatus());
				assertTrue(e.getMessage(), e.getMessage().contains("already being cancelled"));
			}
			AsyncJob queued = AsyncJobRegistry.get(u, jobA);
			assertNotNull(queued);
			assertEquals("still QUEUED until a worker frees", AsyncJob.Status.QUEUED, queued.getStatus());

			releaseFillers.countDown();
			AsyncJob done = awaitTerminal(u, jobA, LOCAL_TIMEOUT_MS);
			logger.info("P1-6 case08 job " + done.getStatus() + " error=" + done.getError());
			assertEquals(AsyncJob.Status.CANCELLED, done.getStatus());
			assertEquals("Cancelled before starting", done.getError());
			BaseRecord run = PbGraphUtil.readRun(u, runA, orgId);
			assertNotNull(run);
			assertEquals(PbRunStatusEnumType.CANCELLED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertNull("the claim is released", PbNodeInflight.runOf(nodeId));
			assertNull("the cancel entry is gone", PbRunCancelRegistry.peek(runA));
			Map<String, Object> terminal = PbServiceFacade.getRun(u, bookOid, runA);
			assertEquals(Boolean.FALSE, terminal.get("inFlightInThisProcess"));
			assertNull("runDto.jobId is null once the run is terminal", terminal.get("jobId"));

			/// Terminal: 409 naming the status.
			try {
				PbServiceFacade.cancelRun(u, bookOid, runA);
				fail("a terminal run must be 409");
			}
			catch(PictureBookException e) {
				logger.info("P1-6 case08 terminal cancel: " + e.getStatus() + " " + e.getMessage());
				assertEquals(409, e.getStatus());
				assertTrue(e.getMessage(), e.getMessage().contains("is CANCELLED and cannot be cancelled"));
			}
			BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
			assertEquals("the node was never stamped", PbNodeStatusEnumType.PENDING.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		}
		finally {
			releaseFillers.countDown();
			for(AsyncJob f : fillers) {
				awaitTerminal(u, f.getJobId(), 30_000L);
			}
		}

		/// A PENDING run row nobody in this JVM is executing (another JVM's, or one orphaned by a restart).
		BaseRecord orphan = PbGraphUtil.startRun(u, wf, PbBookUtil.workflowGroupPath(SLUG), Collections.singletonList(Long.valueOf(nodeId)));
		assertNotNull("orphan run row", orphan);
		String orphanOid = orphan.get(FieldNames.FIELD_OBJECT_ID);
		try {
			assertNull("precondition: no token for the orphan", PbRunCancelRegistry.peek(orphanOid));
			PbServiceFacade.cancelRun(u, bookOid, orphanOid);
			fail("a run with no job in this process must be 409");
		}
		catch(PictureBookException e) {
			logger.info("P1-6 case08 orphan cancel: " + e.getStatus() + " " + e.getMessage());
			assertEquals(409, e.getStatus());
			assertTrue(e.getMessage(), e.getMessage().contains("no job for it is in flight in this process"));
		}
		finally {
			assertTrue("fixture: close the orphan run", PbGraphUtil.completeRun(u, orphan, PbRunStatusEnumType.FAILED, 0, 0, "test fixture: orphan run of case08 closed"));
		}
	}

	/**
	 * Lanes are per SD server, keyed by the normalised URL, and a run waiting for a lane honours a cancel
	 * without waiting for the lane to free. Run A holds lane L1 (its injected client blocks inside the
	 * backend call); run C on lane L2 reaches its backend call while A still holds L1; run B, submitted
	 * with a differently-cased, slash-less spelling of L1, waits on A's lane; a cancel through
	 * {@link PbServiceFacade#cancelRun} while B waits closes B CANCELLED "cancelled while waiting for the
	 * render lane" with its node reverted and its backend never called - while A is still blocked. A then
	 * closes FAILED like any throwing stage and L1 is free again.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case09_lanesAreKeyedPerServerAndALaneWaitObservesCancel() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("precondition: one permit per lane in this JVM", 1, PbRenderLanes.permits());
		String l1 = "http://p16-lane-" + UUID.randomUUID().toString().substring(0, 8) + ":7801/";
		String l1Alias = l1.substring(0, l1.length() - 1).toUpperCase();
		String l2 = "http://p16-lane-" + UUID.randomUUID().toString().substring(0, 8) + ":7801/";
		assertEquals("the alias normalises to the same lane", PbRenderLanes.laneKey(l1), PbRenderLanes.laneKey(l1Alias));
		assertFalse("two distinct lanes", PbRenderLanes.laneKey(l1).equals(PbRenderLanes.laneKey(l2)));

		BaseRecord cpA = createPortraitCharacter(u, "c09a");
		BaseRecord cpB = createPortraitCharacter(u, "c09b");
		BaseRecord cpC = createPortraitCharacter(u, "c09c");
		BaseRecord nA = portraitNode(u, wf, "p16-c09a", cpA.get(FieldNames.FIELD_OBJECT_ID), null);
		BaseRecord nB = portraitNode(u, wf, "p16-c09b", cpB.get(FieldNames.FIELD_OBJECT_ID), null);
		BaseRecord nC = portraitNode(u, wf, "p16-c09c", cpC.get(FieldNames.FIELD_OBJECT_ID), null);
		String nBOid = nB.get(FieldNames.FIELD_OBJECT_ID);

		CountDownLatch aEntered = new CountDownLatch(1);
		CountDownLatch releaseA = new CountDownLatch(1);
		AtomicInteger aCalls = new AtomicInteger();
		String messageA = "test: lane holder " + UUID.randomUUID();
		SDUtil blocker = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				aCalls.incrementAndGet();
				aEntered.countDown();
				try {
					releaseA.await(LOCAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				}
				catch(InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				throw new RuntimeException(messageA);
			}
		};
		Map<String, Object> observedC = new ConcurrentHashMap<>();
		String messageC = "test: other lane " + UUID.randomUUID();
		SDUtil passer = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				observedC.put("l1AvailableOnEntry", Integer.valueOf(PbRenderLanes.available(l1)));
				observedC.put("l2AvailableOnEntry", Integer.valueOf(PbRenderLanes.available(l2)));
				observedC.put("aStillBlocked", Boolean.valueOf(releaseA.getCount() == 1L));
				throw new RuntimeException(messageC);
			}
		};
		AtomicInteger bCalls = new AtomicInteger();
		SDUtil never = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				bCalls.incrementAndGet();
				throw new IllegalStateException("test: a run cancelled while waiting for the lane must never reach the backend");
			}
		};

		Map<String, Object> dA = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(idOf(nA)), blocker, l1, PbRenderJobUtil.KIND_RENDER);
		String runA = (String) dA.get("runObjectId");
		logger.info("P1-6 case09 dtoA=" + dA);
		try {
			assertTrue("A must reach its backend call", aEntered.await(2, TimeUnit.MINUTES));
			assertEquals("A holds L1", 0, PbRenderLanes.available(l1));

			/// C on another lane is not held up by A.
			Map<String, Object> dC = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(idOf(nC)), passer, l2, PbRenderJobUtil.KIND_RENDER);
			AsyncJob jC = awaitTerminal(u, (String) dC.get("jobId"), LOCAL_TIMEOUT_MS);
			logger.info("P1-6 case09 C on L2 while A holds L1: job=" + jC.getStatus() + " observed=" + observedC);
			assertEquals("C's backend call was made while A still held L1", Boolean.TRUE, observedC.get("aStillBlocked"));
			assertEquals("L1 was A's when C entered", Integer.valueOf(0), observedC.get("l1AvailableOnEntry"));
			assertEquals("L2 was taken by C itself when it entered", Integer.valueOf(0), observedC.get("l2AvailableOnEntry"));
			assertEquals("L2 is free again", 1, PbRenderLanes.available(l2));
			assertEquals(AsyncJob.Status.COMPLETED, jC.getStatus());
			BaseRecord runCRow = PbGraphUtil.readRun(u, (String) dC.get("runObjectId"), orgId);
			assertEquals(PbRunStatusEnumType.FAILED.name(), status(runCRow, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals("1 node(s) failed: " + handleOf(nC), runCRow.get(OlioFieldNames.FIELD_PB_ERROR));

			/// B on the alias spelling of L1 waits behind A.
			Map<String, Object> dB = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(idOf(nB)), never, l1Alias, PbRenderJobUtil.KIND_RENDER);
			String runB = (String) dB.get("runObjectId");
			logger.info("P1-6 case09 dtoB=" + dB);
			int waiting = -1;
			long deadline = System.currentTimeMillis() + 60_000L;
			while(System.currentTimeMillis() < deadline) {
				waiting = PbRenderLanes.waiting(l1);
				if(waiting == 1) {
					break;
				}
				Thread.sleep(POLL_MS);
			}
			assertEquals("B waits on A's lane under the alias spelling", 1, waiting);
			assertEquals("B's run is RUNNING while it waits", PbRunStatusEnumType.RUNNING.name(),
				status(PbGraphUtil.readRun(u, runB, orgId), OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals("B's node is stamped RUNNING while it waits", PbNodeStatusEnumType.RUNNING.name(),
				status(PbGraphUtil.readNode(u, nBOid, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));

			Map<String, Object> cancelled = PbServiceFacade.cancelRun(u, bookOid, runB);
			logger.info("P1-6 case09 cancel B while it waits: " + cancelled);
			assertEquals(runB, cancelled.get("runObjectId"));
			assertEquals(PbRunStatusEnumType.RUNNING.name(), cancelled.get("runStatus"));
			assertEquals(Boolean.TRUE, cancelled.get("cancelRequested"));
			AsyncJob jB = awaitTerminal(u, (String) dB.get("jobId"), LOCAL_TIMEOUT_MS);
			logger.info("P1-6 case09 B job=" + jB.getStatus() + " result=" + jB.getResult() + " aStillBlocked=" + (releaseA.getCount() == 1L));
			assertEquals("A is still blocked: B did not wait for the lane to free", 1L, releaseA.getCount());
			assertEquals("B never reached the backend", 0, bCalls.get());
			assertEquals(AsyncJob.Status.CANCELLED, jB.getStatus());
			Map<?, ?> resultB = JSONUtil.importObject(jB.getResult(), Map.class);
			assertNotNull("the cancelled job keeps its summary", resultB);
			assertEquals(PbRunStatusEnumType.CANCELLED.name(), resultB.get("runStatus"));
			Map<?, ?> outcomeB = (Map<?, ?>) ((List<?>) resultB.get("nodes")).get(0);
			assertEquals("CANCELLED", outcomeB.get("status"));
			assertEquals("cancelled while waiting for the render lane", outcomeB.get("error"));
			BaseRecord runBRow = PbGraphUtil.readRun(u, runB, orgId);
			assertEquals(PbRunStatusEnumType.CANCELLED.name(), status(runBRow, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals("Cancelled after 0 of 1 stage(s)", runBRow.get(OlioFieldNames.FIELD_PB_ERROR));
			assertNotNull(runBRow.get(OlioFieldNames.FIELD_PB_COMPLETED_AT));
			BaseRecord freshB = PbGraphUtil.readNode(u, nBOid, orgId);
			String bStatus = status(freshB, OlioFieldNames.FIELD_PB_NODE_STATUS);
			assertTrue("B's node is returned to a pre-run status, not left RUNNING: " + bStatus,
				PbNodeStatusEnumType.READY.name().equals(bStatus) || PbNodeStatusEnumType.PENDING.name().equals(bStatus));
			assertEquals("revertedTo agrees with the stored status", bStatus, outcomeB.get("revertedTo"));
			assertNull("no inputHash: nothing was recorded", freshB.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
			assertNull(PbNodeInflight.runOf(idOf(nB)));
			assertNull(PbRunCancelRegistry.peek(runB));
			assertEquals("nobody waits on L1 any more", 0, PbRenderLanes.waiting(l1));
			assertEquals("A still holds L1", 0, PbRenderLanes.available(l1));
		}
		finally {
			releaseA.countDown();
		}

		AsyncJob jA = awaitTerminal(u, (String) dA.get("jobId"), LOCAL_TIMEOUT_MS);
		logger.info("P1-6 case09 A job=" + jA.getStatus() + " result=" + jA.getResult());
		assertEquals(AsyncJob.Status.COMPLETED, jA.getStatus());
		assertEquals("A's backend call was made exactly once", 1, aCalls.get());
		BaseRecord runARow = PbGraphUtil.readRun(u, runA, orgId);
		assertEquals(PbRunStatusEnumType.FAILED.name(), status(runARow, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals("1 node(s) failed: " + handleOf(nA), runARow.get(OlioFieldNames.FIELD_PB_ERROR));
		assertEquals("L1 is free again", 1, PbRenderLanes.available(l1));
		assertNull(PbNodeInflight.runOf(idOf(nA)));
		assertNull(PbRunCancelRegistry.peek(runA));
	}

	/**
	 * M1: a refused submit never displaces {@code workflow.lastRun}. With the principal's cap
	 * ({@link AsyncJobRegistry#MAX_ACTIVE_JOBS_PER_PRINCIPAL}) of jobs held non-terminal - the first blocked
	 * inside its backend call on this test's own lane, the second RUNNING but waiting for that lane, the rest
	 * QUEUED behind {@link AsyncJobRegistry#MAX_CONCURRENT_JOBS} workers; QUEUED counts as active because
	 * {@code activeCount} counts every {@code !isTerminal()} job - each accepted submit reported
	 * {@code lastRunUpdated} and {@code lastRun} is the last accepted run. The (N+1)-th submit on a fresh node
	 * is refused 429 by the pre-check (before the drift scan, before any run row, before any claim), and
	 * afterwards {@code lastRun} still names run N, no run row was written and the fresh node is unclaimed.
	 * <p>
	 * Not covered here, and said so: the {@code AsyncJobLimitException} branch that writes a FAILED
	 * "Refused: ..." run row is reachable only in the race between the pre-check and the registry's own lock;
	 * there is no seam to force it deterministically, so this case asserts the pre-check outcome (no run row)
	 * rather than reading a refused run.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case10_refusedSubmitNeverDisplacesLastRun() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		assertEquals("precondition: one permit per lane in this JVM", 1, PbRenderLanes.permits());
		assertEquals("precondition: no active jobs for this principal", 0, AsyncJobRegistry.activeCount(u));
		final int n = AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL;
		assertTrue("the cap must exceed the worker count for some job to sit QUEUED", n > AsyncJobRegistry.MAX_CONCURRENT_JOBS);
		String lane = "http://p16-lane-" + UUID.randomUUID().toString().substring(0, 8) + ":7801/";

		List<BaseRecord> nodes = new ArrayList<>();
		for(int i = 0; i < n; i++) {
			BaseRecord cp = createPortraitCharacter(u, "c10-" + i);
			nodes.add(portraitNode(u, wf, "p16-c10-" + i, cp.get(FieldNames.FIELD_OBJECT_ID), null));
		}
		BaseRecord cpX = createPortraitCharacter(u, "c10-x");
		BaseRecord fresh = portraitNode(u, wf, "p16-c10-x", cpX.get(FieldNames.FIELD_OBJECT_ID), null);

		/// Every backend call parks on the gate; the test opens it in its finally and never leaves it held.
		AtomicInteger entrants = new AtomicInteger();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch gate = new CountDownLatch(1);
		String message = "test: cap probe " + UUID.randomUUID();
		SDUtil blocker = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				entrants.incrementAndGet();
				firstEntered.countDown();
				try {
					gate.await(RENDER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				}
				catch(InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				throw new RuntimeException(message + " " + name);
			}
		};

		List<String> jobIds = new ArrayList<>();
		List<String> runOids = new ArrayList<>();
		long submittedAt = System.currentTimeMillis();
		long runNId = -1L;
		try {
			for(int i = 0; i < n; i++) {
				Map<String, Object> dto = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(idOf(nodes.get(i))), blocker, lane, PbRenderJobUtil.KIND_RENDER);
				logger.info("P1-6 case10 submit " + (i + 1) + "/" + n + " dto=" + dto);
				String jobId = (String) dto.get("jobId");
				String runOid = (String) dto.get("runObjectId");
				assertNotNull("jobId on submit " + (i + 1), jobId);
				assertNotNull("runObjectId on submit " + (i + 1), runOid);
				assertEquals("lastRunUpdated on submit " + (i + 1), Boolean.TRUE, dto.get("lastRunUpdated"));
				assertTrue("an accepted job is queued or already running at submit " + (i + 1) + ": " + dto.get("jobStatus"), Arrays.asList("queued", "running").contains(dto.get("jobStatus")));
				jobIds.add(jobId);
				runOids.add(runOid);
			}
			assertTrue("the first backend call must be reached", firstEntered.await(2, TimeUnit.MINUTES));

			/// Settle: MAX_CONCURRENT_JOBS workers RUNNING (one on the backend, one waiting for the lane), the
			/// rest QUEUED. The deadline is a hang guard, never a timing assertion.
			int running = -1;
			int queued = -1;
			long deadline = System.currentTimeMillis() + 60_000L;
			while(System.currentTimeMillis() < deadline) {
				running = 0;
				queued = 0;
				for(String jobId : jobIds) {
					AsyncJob j = AsyncJobRegistry.get(u, jobId);
					assertNotNull("job " + jobId + " must stay visible to its owner", j);
					if(j.getStatus() == AsyncJob.Status.RUNNING) {
						running++;
					}
					else if(j.getStatus() == AsyncJob.Status.QUEUED) {
						queued++;
					}
				}
				if(running == AsyncJobRegistry.MAX_CONCURRENT_JOBS && queued == n - AsyncJobRegistry.MAX_CONCURRENT_JOBS && PbRenderLanes.waiting(lane) == 1) {
					break;
				}
				Thread.sleep(POLL_MS);
			}
			logger.info("P1-6 case10 settled after " + (System.currentTimeMillis() - submittedAt) + "ms: running=" + running + " queued=" + queued
				+ " activeCount=" + AsyncJobRegistry.activeCount(u) + " laneAvailable=" + PbRenderLanes.available(lane)
				+ " laneWaiting=" + PbRenderLanes.waiting(lane) + " entrants=" + entrants.get());
			assertEquals("MAX_CONCURRENT_JOBS jobs are RUNNING", AsyncJobRegistry.MAX_CONCURRENT_JOBS, running);
			assertEquals("the rest are QUEUED", n - AsyncJobRegistry.MAX_CONCURRENT_JOBS, queued);
			assertEquals("QUEUED and RUNNING both count as active", n, AsyncJobRegistry.activeCount(u));
			assertEquals("only the first backend call was made", 1, entrants.get());
			assertEquals("the lane is held", 0, PbRenderLanes.available(lane));
			assertEquals("the second RUNNING job waits for the lane", 1, PbRenderLanes.waiting(lane));
			for(int i = 0; i < n; i++) {
				assertEquals("node " + (i + 1) + " is claimed by its run", runOids.get(i), PbNodeInflight.runOf(idOf(nodes.get(i))));
			}

			/// lastRun is the last accepted run (N), read by value through an explicit projection.
			BaseRecord runN = PbGraphUtil.readRun(u, runOids.get(n - 1), orgId);
			assertNotNull("run N is readable by its objectId", runN);
			runNId = ((Number) runN.get(FieldNames.FIELD_ID)).longValue();
			Long lastRunBefore = lastRunIdOf(u, wf);
			logger.info("P1-6 case10 before the refusal: lastRun=" + lastRunBefore + " runN=" + runNId + " (" + runOids.get(n - 1) + ")");
			assertEquals("workflow.lastRun is the last accepted run", Long.valueOf(runNId), lastRunBefore);

			/// The (N+1)-th submit on a fresh node: 429 from the pre-check, nothing written, nothing claimed.
			long newestBefore = newestRunId(u, wf);
			int inflightBefore = PbNodeInflight.size();
			assertNull("precondition: the fresh node is unclaimed", PbNodeInflight.runOf(idOf(fresh)));
			try {
				Map<String, Object> dX = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(idOf(fresh)), blocker, lane, PbRenderJobUtil.KIND_RENDER);
				fail("the (N+1)-th submit must be refused 429, got " + dX);
			}
			catch(PictureBookException e) {
				logger.info("P1-6 case10 refusal: " + e.getStatus() + " " + e.getMessage());
				assertEquals("the principal's cap is a 429", 429, e.getStatus());
				assertTrue("the refusal names the cap: " + e.getMessage(),
					e.getMessage() != null && e.getMessage().contains(n + " of " + AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL + " jobs queued or running"));
			}
			/// Skipped, and said so: the refused run cannot be read because the pre-check fires before startRun,
			/// so there is no run objectId to read; the assertable consequence is that no run row appeared.
			assertEquals("the refusal wrote no run row (the pre-check fires before startRun)", newestBefore, newestRunId(u, wf));
			assertEquals("the refusal claimed nothing", inflightBefore, PbNodeInflight.size());
			assertNull("the fresh node is still unclaimed", PbNodeInflight.runOf(idOf(fresh)));
			assertEquals("the refusal left the active count alone", n, AsyncJobRegistry.activeCount(u));
			Long lastRunAfter = lastRunIdOf(u, wf);
			logger.info("P1-6 case10 after the refusal: lastRun=" + lastRunAfter + " runN=" + runNId);
			assertEquals("M1: a refused submit never displaces workflow.lastRun", Long.valueOf(runNId), lastRunAfter);
			assertEquals("the fresh node is still PENDING", PbNodeStatusEnumType.PENDING.name(),
				status(PbGraphUtil.readNode(u, fresh.get(FieldNames.FIELD_OBJECT_ID), orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
		}
		finally {
			gate.countDown();
			for(String jobId : jobIds) {
				AsyncJob j = awaitTerminal(u, jobId, RENDER_TIMEOUT_MS);
				logger.info("P1-6 case10 job " + jobId + " " + j.getStatus() + " after " + (System.currentTimeMillis() - submittedAt) + "ms");
			}
		}

		/// After the gate opens every held job runs its throwing stage to the end: job COMPLETED, run FAILED,
		/// every claim released, and lastRun is still run N (nothing accepted since).
		assertEquals("every backend call was eventually made", n, entrants.get());
		for(int i = 0; i < n; i++) {
			AsyncJob j = AsyncJobRegistry.get(u, jobIds.get(i));
			assertNotNull(j);
			assertEquals("job " + (i + 1) + " completes (the run carries the failure)", AsyncJob.Status.COMPLETED, j.getStatus());
			BaseRecord run = PbGraphUtil.readRun(u, runOids.get(i), orgId);
			assertNotNull(run);
			assertEquals("run " + runOids.get(i) + " closes FAILED", PbRunStatusEnumType.FAILED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertNull("claim released for node " + (i + 1), PbNodeInflight.runOf(idOf(nodes.get(i))));
			assertNull("cancel token released for run " + runOids.get(i), PbRunCancelRegistry.peek(runOids.get(i)));
		}
		assertEquals("the lane is free again", 1, PbRenderLanes.available(lane));
		assertEquals(0, PbRenderLanes.waiting(lane));
		assertEquals("no active jobs remain for this principal", 0, AsyncJobRegistry.activeCount(u));
		assertEquals("lastRun is still run N once everything has drained", Long.valueOf(runNId), lastRunIdOf(u, wf));
	}

	/**
	 * PF-9 (PictureBookV3Implementation §9): a plain-PB node closed by one of the four executors that used a
	 * bare {@code persistStatus} (LANDSCAPE here) must complete the way the portrait and ChapBook-page closings
	 * do - {@code inputHash} and {@code lastRunAt} stamped, counted on the run - so that the planner sees it as
	 * current. Live {@code .39}: render 1 through the job → DONE_UNVERIFIED with an inputHash and
	 * {@code executedNodeCount} 1 (was 0); an un-forced re-submit is the 409 "(1 reused, 0 pinned)" and opens
	 * no run (was accepted and re-rendered); a forced re-submit renders revision 2, selected, superseding 1.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case11_plainPbNodeIsCurrentAfterRenderAndOnlyForceRerolls() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String server = swarmServer();
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "PF-9 plain-PB landscape through the job");
		Boolean bookAuto = (bk.hasField(OlioFieldNames.FIELD_PB_AUTO_APPROVE) ? bk.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE) : null);
		assertFalse("precondition: the shared fixture book's autoApprove switch is off (TestPbAutoApprove.resetAutoApprove), else the render would pin",
			bookAuto != null && bookAuto.booleanValue());

		BaseRecord node = landscapeNode(u, bk, wf, "pf9-c11", fastOverride(renderCfg));
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		long nodeId = idOf(node);
		String handle = handleOf(node);
		assertNull("precondition: no inputHash before the first render", node.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
		ZonedDateTime createdAt = node.get(OlioFieldNames.FIELD_PB_LAST_RUN_AT);
		assertNotNull("precondition: lastRunAt is a zonetime column with a DDL default (objects7-reference), so it is never null", createdAt);

		/// 1. Render 1 through the job: DONE_UNVERIFIED, hashed, stamped, counted.
		long t0 = System.currentTimeMillis();
		Map<String, Object> dto1 = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(nodeId), SDAPIEnumType.SWARM.name(), server, PbRenderJobUtil.KIND_RENDER);
		logger.info("PF-9 case11 render 1 dto=" + dto1);
		assertEquals(Arrays.asList(handle), dto1.get("planned"));
		assertEquals(Integer.valueOf(0), dto1.get("reusedNodeCount"));
		String run1Oid = (String) dto1.get("runObjectId");
		AsyncJob job1 = awaitTerminal(u, (String) dto1.get("jobId"), RENDER_TIMEOUT_MS);
		logger.info("PF-9 case11 render 1 durationMs=" + (System.currentTimeMillis() - t0) + " job=" + job1.getStatus() + " result=" + job1.getResult());
		assertEquals("job error: " + job1.getError(), AsyncJob.Status.COMPLETED, job1.getStatus());
		Map<?, ?> result1 = JSONUtil.importObject(job1.getResult(), Map.class);
		assertEquals(PbRunStatusEnumType.COMPLETED.name(), result1.get("runStatus"));
		assertEquals("the landscape counts on its run (was 0 with the bare persistStatus)", Integer.valueOf(1), result1.get("executedNodeCount"));
		Map<?, ?> outcome1 = (Map<?, ?>) ((List<?>) result1.get("nodes")).get(0);
		assertEquals(handle, outcome1.get("handle"));
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), outcome1.get("status"));
		String artifact1Oid = (String) outcome1.get("artifactObjectId");
		assertNotNull(artifact1Oid);

		BaseRecord run1 = PbGraphUtil.readRun(u, run1Oid, orgId);
		assertEquals(PbRunStatusEnumType.COMPLETED.name(), status(run1, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals(Integer.valueOf(1), run1.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
		assertEquals(Integer.valueOf(0), run1.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));

		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String inputHash = fresh.get(OlioFieldNames.FIELD_PB_INPUT_HASH);
		assertTrue("the landscape carries its inputHash after completion (was null)", inputHash != null && !inputHash.isBlank());
		ZonedDateTime lastRunAt = fresh.get(OlioFieldNames.FIELD_PB_LAST_RUN_AT);
		assertNotNull(lastRunAt);
		assertTrue("lastRunAt was stamped by the render (" + lastRunAt + " after the creation default " + createdAt + ")", lastRunAt.isAfter(createdAt));
		assertFalse("not pinned (autoApprove off)", Boolean.TRUE.equals(fresh.get(OlioFieldNames.FIELD_PB_PINNED)));
		BaseRecord revision1 = PbArtifactUtil.readArtifact(u, artifact1Oid, orgId);
		assertNotNull(revision1);
		assertEquals(Integer.valueOf(1), revision1.get(OlioFieldNames.FIELD_PB_REVISION));
		assertEquals(Boolean.TRUE, revision1.get(OlioFieldNames.FIELD_PB_SELECTED));

		/// 2. Un-forced: the node is current, so the submit is refused and opens no run.
		long runsBefore = newestRunId(u, wf);
		try {
			PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(nodeId), SDAPIEnumType.SWARM.name(), server, PbRenderJobUtil.KIND_RENDER);
			fail("an un-forced render of a current landscape must be refused");
		}
		catch(PictureBookException e) {
			logger.info("PF-9 case11 un-forced refusal: " + e.getStatus() + " " + e.getMessage());
			assertEquals(409, e.getStatus());
			assertEquals("Nothing to render: every node of this scene is current (1 reused, 0 pinned)", e.getMessage());
		}
		assertEquals("the refusal opens no run", runsBefore, newestRunId(u, wf));
		assertNull(PbNodeInflight.runOf(nodeId));

		/// 3. Forced: a new run re-rolls the current landscape into revision 2.
		t0 = System.currentTimeMillis();
		Map<String, Object> dto2 = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(nodeId), SDAPIEnumType.SWARM.name(), server, PbRenderJobUtil.KIND_RENDER, true);
		logger.info("PF-9 case11 forced render 2 dto=" + dto2);
		assertEquals(Arrays.asList(handle), dto2.get("planned"));
		assertEquals(Integer.valueOf(1), dto2.get("plannedNodeCount"));
		assertEquals(Integer.valueOf(0), dto2.get("reusedNodeCount"));
		assertEquals(Integer.valueOf(0), dto2.get("pinnedNodeCount"));
		String run2Oid = (String) dto2.get("runObjectId");
		assertNotEquals("the forced render is a new run", run1Oid, run2Oid);
		AsyncJob job2 = awaitTerminal(u, (String) dto2.get("jobId"), RENDER_TIMEOUT_MS);
		logger.info("PF-9 case11 forced render 2 durationMs=" + (System.currentTimeMillis() - t0) + " job=" + job2.getStatus() + " result=" + job2.getResult());
		assertEquals("job error: " + job2.getError(), AsyncJob.Status.COMPLETED, job2.getStatus());
		Map<?, ?> result2 = JSONUtil.importObject(job2.getResult(), Map.class);
		assertEquals(PbRunStatusEnumType.COMPLETED.name(), result2.get("runStatus"));
		assertEquals(Integer.valueOf(1), result2.get("executedNodeCount"));
		Map<?, ?> outcome2 = (Map<?, ?>) ((List<?>) result2.get("nodes")).get(0);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), outcome2.get("status"));
		String artifact2Oid = (String) outcome2.get("artifactObjectId");
		assertNotNull(artifact2Oid);
		assertNotEquals("the forced render produces a new artifact", artifact1Oid, artifact2Oid);

		BaseRecord revision2 = PbArtifactUtil.readArtifact(u, artifact2Oid, orgId);
		assertNotNull(revision2);
		assertEquals(Integer.valueOf(2), revision2.get(OlioFieldNames.FIELD_PB_REVISION));
		assertEquals(Boolean.TRUE, revision2.get(OlioFieldNames.FIELD_PB_SELECTED));
		assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(revision2));
		BaseRecord supersedes = revision2.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
		assertNotNull("revision 2 supersedes revision 1", supersedes);
		assertEquals(artifact1Oid, supersedes.get(FieldNames.FIELD_OBJECT_ID));
		revision1 = PbArtifactUtil.readArtifact(u, artifact1Oid, orgId);
		assertNotNull("revision 1 is kept", revision1);
		assertFalse("revision 1 is no longer selected", Boolean.TRUE.equals(revision1.get(OlioFieldNames.FIELD_PB_SELECTED)));

		fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals(PbNodeStatusEnumType.DONE_UNVERIFIED.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertEquals("two renders, two artifacts", 2, PbArtifactUtil.listChain(u, fresh, PbPipelineUtil.ROLE_LANDSCAPE).size());
		assertEquals("the newest artifact is selected", artifact2Oid,
			PbArtifactUtil.findSelected(u, fresh, PbPipelineUtil.ROLE_LANDSCAPE).get(FieldNames.FIELD_OBJECT_ID));
		assertNull(PbNodeInflight.runOf(nodeId));
		assertNull(PbRunCancelRegistry.peek(run2Oid));

		BaseRecord dataRef = revision2.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull(dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			IOSystem.getActiveContext().getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		byte[] bytes = ByteModelUtil.getValue(freshReadData(u, dataOid));
		assertTrue("real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		File png = new File("target/pf9-landscape-rev2-" + System.currentTimeMillis() + ".png");
		FileUtil.emitFile(png.getPath(), bytes);
		assertTrue(png.exists() && png.length() == bytes.length);
		logger.info("PF-9 case11 PNG written: " + png.getAbsolutePath() + " (" + bytes.length + " bytes)");
	}

	/**
	 * P7-5 Scenario D (PictureBookV3Implementation §6.C 219 o): a pinned scene whose upstream portrait drifted.
	 * The planner skips the pinned terminal but still executes the STALE producer, so {@code submit} alone would
	 * accept the request (202, portrait re-rendered, scene left pinned and stale) - which is what the Manage
	 * Characters "Regenerate" hit on Docker. {@link PbServiceFacade#requestSceneRender} must refuse the pinned
	 * scene itself, forced or not, with the same 409 {@code requestRegenerate} gives, before anything is planned:
	 * no run row, no in-flight claim, the producer untouched. The precondition asserts the planner's own view
	 * (execute = [producer], 1 pinned) so the refusal is provably the facade's. Offline - no SD call is made.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case12_facadeRefusesPinnedSceneWithStaleUpstreamBeforePlanning() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		String gp = PbBookUtil.workflowGroupPath(SLUG);
		int sceneIndex = 100000 + Math.abs(UUID.randomUUID().hashCode() % 800000);
		BaseRecord scene = PbBookUtil.createScene(u, bk, sceneIndex, "p75-c12 scene", PbBookUtil.bookGroupPath(SLUG));
		assertNotNull("scene " + sceneIndex, scene);
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord terminal = plainNode(u, wf, "p75-c12-S", PbNodeTypeEnumType.COMPOSITE, 0);
		BaseRecord producer = plainNode(u, wf, "p75-c12-P", PbNodeTypeEnumType.PORTRAIT, 1);
		assertNotNull(PbGraphUtil.addBinding(u, wf, terminal, PbPipelineUtil.ROLE_PORTRAIT, 0, producer, null, gp));
		BaseRecord link = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
		link.set(OlioFieldNames.FIELD_PB_SCENE_NODE, terminal);
		assertNotNull("scene.sceneNode link", ioContext.getAccessPoint().update(u, link));
		/// Scenario D's shape: the scene was approved (pinned), then its character changed (producer STALE,
		/// propagation marked the scene STALE too).
		assertTrue(PbGraphUtil.persistStatus(u, producer, PbNodeStatusEnumType.STALE));
		assertTrue(PbGraphUtil.persistStatus(u, terminal, PbNodeStatusEnumType.STALE));
		assertTrue(PbGraphUtil.setPinned(u, terminal, true));
		PbStaleUtil.clear();

		PbRenderJobUtil.Plan plan = PbRenderJobUtil.plan(u, bk, wf, Collections.singletonList(idOf(terminal)), true);
		logger.info("P7-5 case12 planner view execute=" + plan.handles() + " pinned=" + plan.pinned + " reused=" + plan.reused);
		assertEquals("precondition: the planner alone would execute the stale producer", Arrays.asList(handleOf(producer)), plan.handles());
		assertEquals("precondition: the planner only counts the pinned terminal", 1, plan.pinned);

		long newestBefore = newestRunId(u, wf);
		long inflightBefore = PbNodeInflight.size();
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		String expected = "Node " + handleOf(terminal) + " is pinned. Unpin it first - pinning means do not replace this artifact.";
		for(boolean force : new boolean[] { true, false }) {
			try {
				PbServiceFacade.requestSceneRender(u, bookOid, sceneOid, SDAPIEnumType.SWARM.name(), swarmServer(), force);
				fail("a render of a pinned scene must be refused (force=" + force + ")");
			}
			catch(PictureBookException e) {
				logger.info("P7-5 case12 refusal force=" + force + ": " + e.getStatus() + " " + e.getMessage());
				assertEquals(409, e.getStatus());
				assertEquals(expected, e.getMessage());
			}
		}
		assertEquals("no run row is written", newestBefore, newestRunId(u, wf));
		assertEquals("no claim is left behind", inflightBefore, PbNodeInflight.size());
		assertNull(PbNodeInflight.runOf(idOf(producer)));
		BaseRecord freshP = PbGraphUtil.readNode(u, producer.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertEquals("the stale producer was not rendered", PbNodeStatusEnumType.STALE.name(), status(freshP, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertNull("no artifact for the producer", PbArtifactUtil.findSelected(u, freshP, PbPipelineUtil.ROLE_PORTRAIT));
		BaseRecord freshS = PbGraphUtil.readNode(u, terminal.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertTrue("the scene node stays pinned", Boolean.TRUE.equals(freshS.get(OlioFieldNames.FIELD_PB_PINNED)));

		/// Unpinned, the same request reaches the planner: the SD-less seam is not available through the facade,
		/// so prove the hand-off by the planner's own 500 "SD server not configured" when no server is given -
		/// which is after the pin gate and before any run row.
		assertTrue(PbGraphUtil.setPinned(u, terminal, false));
		PbStaleUtil.clear();
		try {
			PbServiceFacade.requestSceneRender(u, bookOid, sceneOid, SDAPIEnumType.SWARM.name(), null, true);
			fail("an unpinned scene with no SD server reaches the planner's 500");
		}
		catch(PictureBookException e) {
			logger.info("P7-5 case12 unpinned hand-off: " + e.getStatus() + " " + e.getMessage());
			assertEquals(500, e.getStatus());
			assertEquals("SD server not configured", e.getMessage());
		}
		assertEquals("still no run row", newestBefore, newestRunId(u, wf));
	}

	// ─────────────────────────────── fixture ───────────────────────────────

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

	private String swarmServer() {
		String s = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		assertNotNull(SdTestGate.PROP_SWARM_SERVER + " must be set", s);
		return s;
	}

	private BaseRecord book(BaseRecord u) {
		BaseRecord bk = PbBookUtil.findBookBySlug(u, SLUG, orgOf(u));
		if(bk == null) {
			bk = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 2c graph fixture");
		}
		assertNotNull("book " + SLUG, bk);
		return bk;
	}

	/** The same owner's second book (the TestPbSecurity / TestPbReview fixture), for the cross-book 404. */
	private BaseRecord otherBook(BaseRecord u) {
		BaseRecord bk = PbBookUtil.findBookBySlug(u, OTHER_SLUG, orgOf(u));
		if(bk == null) {
			bk = PbBookUtil.createBook(u, dataPath(), OTHER_SLUG, "Phase 2c security fixture " + OTHER_SLUG);
		}
		assertNotNull("book " + OTHER_SLUG, bk);
		return bk;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("workflow", wf);
		return wf;
	}

	/** A node with a random handle and no scope: enough for planning, never executed. */
	private BaseRecord plainNode(BaseRecord u, BaseRecord wf, String prefix, PbNodeTypeEnumType type, int ordinal) {
		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, type, PbBookUtil.workflowGroupPath(SLUG), ordinal);
		assertNotNull("node " + handle, n);
		assertEquals("precondition: PENDING", PbNodeStatusEnumType.PENDING.name(), status(n, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return n;
	}

	/** A PORTRAIT node scoped to {@code cpOid} (the {@code TestPbNodeRun} patch shape), re-read uncached. */
	private BaseRecord portraitNode(BaseRecord u, BaseRecord wf, String prefix, String cpOid, String configOverride) throws Exception {
		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, PbNodeTypeEnumType.PORTRAIT, PbBookUtil.workflowGroupPath(SLUG), 0);
		assertNotNull("node " + handle, n);
		List<String> changed = new ArrayList<>(Arrays.asList(OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF));
		if(configOverride != null) {
			changed.add(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE);
		}
		BaseRecord patch = PbGraphUtil.patchOf(n, OlioModelNames.MODEL_PB_NODE, changed.toArray(new String[0]));
		patch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_CHARACTER);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, cpOid);
		if(configOverride != null) {
			patch.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, configOverride);
		}
		assertNotNull("scope patch must be written", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull(fresh);
		assertEquals("precondition: scopeRef", cpOid, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		assertEquals("precondition: PENDING", PbNodeStatusEnumType.PENDING.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return fresh;
	}

	/**
	 * A LANDSCAPE node scoped to a fresh {@code olio.pb.scene} of the fixture book that carries a hand-written
	 * {@code sdPrompt} (no LLM in the loop), so {@code executeLandscape} renders the scene prompt itself rather
	 * than the no-prompt fallback. Scene creation copies TestPbAutoApprove.promptNode; the prompt patch copies
	 * TestChapBookRenderStyle.patchScenePrompt.
	 */
	private BaseRecord landscapeNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String prefix, String configOverride) throws Exception {
		int sceneIndex = 100000 + Math.abs(UUID.randomUUID().hashCode() % 800000);
		BaseRecord scene = PbBookUtil.createScene(u, bk, sceneIndex, prefix + " scene", PbBookUtil.bookGroupPath(SLUG));
		assertNotNull("scene " + sceneIndex, scene);
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord scenePatch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_CB_SD_PROMPT });
		scenePatch.set(FieldNames.FIELD_ID, scene.get(FieldNames.FIELD_ID));
		scenePatch.set(FieldNames.FIELD_OBJECT_ID, sceneOid);
		scenePatch.set(FieldNames.FIELD_NAME, scene.get(FieldNames.FIELD_NAME));
		scenePatch.set(OlioFieldNames.FIELD_CB_SD_PROMPT,
			"a small stone harbour at dusk, fishing boats moored along the quay, lanterns reflected in still water, gulls, distant headland, painterly, soft light");
		assertNotNull("scene sdPrompt patch must be written", ioContext.getAccessPoint().update(u, scenePatch));

		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, PbNodeTypeEnumType.LANDSCAPE, PbBookUtil.workflowGroupPath(SLUG), 0);
		assertNotNull("node " + handle, n);
		List<String> changed = new ArrayList<>(Arrays.asList(OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF));
		if(configOverride != null) {
			changed.add(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE);
		}
		BaseRecord patch = PbGraphUtil.patchOf(n, OlioModelNames.MODEL_PB_NODE, changed.toArray(new String[0]));
		patch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_SCENE);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, sceneOid);
		if(configOverride != null) {
			patch.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, configOverride);
		}
		assertNotNull("scope patch must be written", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull(fresh);
		assertEquals("precondition: scopeRef", sceneOid, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		assertEquals("precondition: PENDING", PbNodeStatusEnumType.PENDING.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return fresh;
	}

	/** The installed checkpoint at 12 steps, no hires: the cheapest real render the gate allows. */
	private String fastOverride(BaseRecord renderCfg) throws Exception {
		String model = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		assertNotNull("the render gate must name an installed checkpoint", model);
		BaseRecord override = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG,
			new String[] { OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_STEPS, "hires" });
		override.set(OlioFieldNames.FIELD_SD_MODEL, model);
		override.set(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(12));
		override.set("hires", Boolean.FALSE);
		return PbConfigUtil.sparseOverride(override, Arrays.asList(OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_STEPS, "hires"));
	}

	/**
	 * A charPerson the user owns, describable for a portrait: a hand-written, clothed {@code narrative.sdPrompt}
	 * plus the same text as the {@code pbDescription} attribute ({@code TestPbNodeRun}'s fixture).
	 */
	private BaseRecord createPortraitCharacter(BaseRecord u, String tag) throws Exception {
		long orgId = orgOf(u);
		BaseRecord grp = ioContext.getPathUtil().makePath(u, ModelNames.MODEL_GROUP, CHARS_PATH, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("characters group", grp);
		String gpath = grp.get(FieldNames.FIELD_PATH);
		String name = "P16-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, gpath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set("firstName", "Harbor");
		cp.set("lastName", "Warden");
		cp.set("gender", "male");
		BaseRecord created = ioContext.getAccessPoint().create(u, cp);
		assertNotNull("charPerson must be created", created);
		String cpOid = created.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull(cpOid);

		BaseRecord narrative = PbSubRecordUtil.createSubRecord(u, null, OlioModelNames.MODEL_NARRATIVE);
		assertNotNull("narrative must be created", narrative);
		BaseRecord narrativePatch = RecordFactory.newInstance(OlioModelNames.MODEL_NARRATIVE,
			new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "sdPrompt", "physicalDescription" });
		narrativePatch.set(FieldNames.FIELD_ID, narrative.get(FieldNames.FIELD_ID));
		narrativePatch.set(FieldNames.FIELD_OBJECT_ID, narrative.get(FieldNames.FIELD_OBJECT_ID));
		narrativePatch.set("sdPrompt", PORTRAIT_PROMPT);
		narrativePatch.set("physicalDescription", PORTRAIT_PROMPT);
		assertNotNull("narrative prompt patch must persist", ioContext.getAccessPoint().update(u, narrativePatch));
		assertTrue("precondition: the prompt is hand-written by PictureBookUtil's rule", PictureBookUtil.isHandWrittenPrompt(PORTRAIT_PROMPT));

		Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cpOid);
		cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID, "firstName", "lastName", "narrative", "profile", FieldNames.FIELD_ATTRIBUTES });
		cq.setCache(false);
		BaseRecord cpRead = ioContext.getAccessPoint().find(u, cq);
		assertNotNull("charPerson must be readable", cpRead);
		assertNotNull("the factory must have persisted a profile (portrait link target)", cpRead.get("profile"));

		BaseRecord linkPatch = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON,
			new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative" });
		linkPatch.set(FieldNames.FIELD_ID, cpRead.get(FieldNames.FIELD_ID));
		linkPatch.set(FieldNames.FIELD_OBJECT_ID, cpOid);
		linkPatch.set(FieldNames.FIELD_NAME, name);
		linkPatch.set("narrative", narrative);
		assertNotNull("narrative link patch must persist", ioContext.getAccessPoint().update(u, linkPatch));

		/// Persisted as the attribute record itself, never through a parent patch (model-api.md, "referenced fields").
		BaseRecord attr = AttributeUtil.addAttribute(cpRead, PictureBookUtil.ATTR_DESCRIPTION, PORTRAIT_PROMPT);
		assertTrue("pbDescription attribute must persist", ioContext.getRecordUtil().createRecord(attr));

		BaseRecord full = ioContext.getAccessPoint().find(u, cq);
		assertNotNull(full);
		BaseRecord nar = full.get("narrative");
		assertNotNull("precondition: narrative is linked", nar);
		ioContext.getReader().populate(nar, new String[] { "sdPrompt" });
		assertEquals("precondition: the hand-written prompt is stored", PORTRAIT_PROMPT, nar.get("sdPrompt"));
		return full;
	}

	// ─────────────────────────────── reads (all uncached) ───────────────────────────────

	private BaseRecord freshReadData(BaseRecord u, String objectId) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return ioContext.getAccessPoint().find(u, q);
	}

	/**
	 * The id of {@code workflow.lastRun}, read uncached by value - or null when the pointer is unset.
	 * {@code lastRun} is deliberately absent from {@code PbGraphUtil.workflowRequest()} and filtered out of
	 * {@code FULL_PLAN_FILTER} (one half of the workflow <-> run cycle), so it has to be projected explicitly;
	 * a projected foreign field comes back as a record carrying its {@code id}.
	 */
	private Long lastRunIdOf(BaseRecord u, BaseRecord wf) {
		String wfOid = wf.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("workflow objectId", wfOid);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_WORKFLOW, FieldNames.FIELD_OBJECT_ID, wfOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_LAST_RUN });
		q.setCache(false);
		BaseRecord freshWf = ioContext.getAccessPoint().find(u, q);
		assertNotNull("workflow " + wfOid + " must be readable with lastRun projected", freshWf);
		if(!freshWf.hasField(OlioFieldNames.FIELD_PB_LAST_RUN)) {
			return null;
		}
		BaseRecord lr = freshWf.get(OlioFieldNames.FIELD_PB_LAST_RUN);
		if(lr == null) {
			return null;
		}
		Object id = lr.get(FieldNames.FIELD_ID);
		return (id instanceof Number ? Long.valueOf(((Number) id).longValue()) : null);
	}

	private Query runsQuery(BaseRecord u, BaseRecord wf) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, wf);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(PbGraphUtil.runRequest());
		q.setCache(false);
		return q;
	}

	/** The newest olio.pb.run of the workflow (highest id), read uncached; -1 when there is none. */
	private long newestRunId(BaseRecord u, BaseRecord wf) {
		BaseRecord[] recs = ioContext.getAccessPoint().list(u, runsQuery(u, wf)).getResults();
		long bestId = -1L;
		if(recs != null) {
			for(BaseRecord r : recs) {
				long id = ((Number) r.get(FieldNames.FIELD_ID)).longValue();
				if(id > bestId) {
					bestId = id;
				}
			}
		}
		return bestId;
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
		fail("job " + jobId + " did not reach a terminal state within " + maxMs + "ms (last: " + (job != null ? job.getStatus() + " " + job.getProgress().getPhase() : "null") + ")");
		return null;
	}

	private static long idOf(BaseRecord node) {
		return ((Number) node.get(FieldNames.FIELD_ID)).longValue();
	}

	private static String handleOf(BaseRecord node) {
		return node.get(OlioFieldNames.FIELD_PB_HANDLE);
	}

	/// Enum fields read back as the enum (or, on list projections, the raw string); never hand a generic
	/// get() to String.valueOf - type inference picks the char[] overload and throws ClassCastException.
	private static String status(BaseRecord rec, String field) {
		Object v = (rec != null ? rec.get(field) : null);
		return (v != null ? v.toString().toUpperCase() : null);
	}
}
