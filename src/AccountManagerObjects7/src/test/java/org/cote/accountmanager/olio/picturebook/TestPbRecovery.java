package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.thread.AsyncJobWork;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * PictureBook V3 Phase 1, P1-7: restart recovery and resume.
 * <p>
 * What is under test: {@link PbRecoveryUtil#sweepInterrupted(BaseRecord)} reconciles the rows a stopped
 * container left behind (a RUNNING run closes INTERRUPTED with "resume to continue" when it carries an
 * execution set, a PENDING run closes INTERRUPTED "before this run was dispatched", a RUNNING node behind
 * an already-closed run is returned to READY without the run being touched, and anything still in flight in
 * this process - a registered cancel token, a held node claim - is skipped and counted);
 * {@link PbRenderJobUtil#resume} refuses anything but an INTERRUPTED run with an execution set and otherwise
 * opens a {@code pb.resume} run that executes only what is not yet current; a previously selected artifact
 * survives a live cancel of a later revision; and on the worker side a job that is waiting for the render
 * lane when {@link AsyncJobRegistry#beginShutdown()} is announced closes its run INTERRUPTED and returns its
 * node to READY, after which {@link AsyncJobRegistry#shutdown()} leaves the registry usable.
 * <p>
 * Live {@code am7db}. The sweep is always scoped to this test's own workflow ({@code pb2c-recovery}) and
 * every run assertion is keyed on the run's own objectId, never on a DB-wide count: the shared
 * {@code pb2c-graph} fixture carries residue from other suites. Where a stage must be observed on demand an
 * {@link SDUtil} subclass is injected through the package-private {@code submit(..., SDUtil, ...)} seam; the
 * resume case (06) and the shutdown case (08) use a FAKE client that persists a real {@code data.data} PNG
 * (64x64, written by ImageIO) so the executor's byte, dimension and seed handling runs for real without a
 * backend round trip; case 07 uses the live {@code .39} backend through {@code super.createImage}. Case 08 is
 * LAST because {@code beginShutdown()} / {@code shutdown()} are process-global. Timings are logged, never
 * asserted.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestPbRecovery extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-recovery";
	private static final String CHARS_PATH = "~/P17Chars";
	/// Render budgets are generous on purpose: a timing is never a pass/fail threshold here.
	private static final long RENDER_TIMEOUT_MS = 20L * 60L * 1000L;
	private static final long LOCAL_TIMEOUT_MS = 5L * 60L * 1000L;
	private static final long POLL_MS = 250L;
	private static final String ABANDONED_MSG = "Execution abandoned before the run could be closed";
	private static final String RUNNING_TEXT = "Interrupted: the server stopped while this run was RUNNING";
	private static final String PENDING_TEXT = "Interrupted: the server stopped before this run was dispatched";
	private static final String RESUME_HINT = "; resume to continue";

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
	 * (i) A RUNNING run with a RUNNING node and nothing in flight in this process: the run closes
	 * INTERRUPTED carrying "RUNNING" and "resume to continue" (it has an execution set), the node is returned
	 * to READY, and the counters say one run and one node.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case01_runningRunAndNodeAreReconciled() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		preSweep(wf, "case01");
		BaseRecord node = plainNode(u, wf, "p17-c01", PbNodeTypeEnumType.PORTRAIT, 0);
		long nodeId = idOf(node);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord run = PbGraphUtil.startRun(u, wf, PbBookUtil.workflowGroupPath(SLUG), Collections.singletonList(Long.valueOf(nodeId)));
		assertNotNull("run", run);
		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
		assertTrue("run must be stamped RUNNING", PbGraphUtil.markRunning(u, run));
		assertTrue("node must be stamped RUNNING", PbGraphUtil.persistStatus(u, node, PbNodeStatusEnumType.RUNNING));
		assertEquals(PbRunStatusEnumType.RUNNING.name(), status(PbGraphUtil.readRun(u, runOid, orgId), OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals(PbNodeStatusEnumType.RUNNING.name(), status(PbGraphUtil.readNode(u, nodeOid, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertNull("precondition: no claim on the node", PbNodeInflight.runOf(nodeId));
		assertNull("precondition: no cancel token for the run", PbRunCancelRegistry.peek(runOid));

		long t0 = System.currentTimeMillis();
		Map<String, Object> r = PbRecoveryUtil.sweepInterrupted(wf);
		logger.info("[P1-7 case01] sweep durationMs=" + (System.currentTimeMillis() - t0) + " result=" + r);
		assertSweep("case01", r, 1, 0, 1, 0, 0);

		BaseRecord fresh = PbGraphUtil.readRun(u, runOid, orgId);
		assertNotNull(fresh);
		assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), status(fresh, OlioFieldNames.FIELD_PB_RUN_STATUS));
		String err = fresh.get(OlioFieldNames.FIELD_PB_ERROR);
		assertEquals("the run says why and how to continue", RUNNING_TEXT + RESUME_HINT, err);
		assertTrue(err.contains("RUNNING"));
		assertTrue(err.contains("resume to continue"));
		assertEquals("the execution set survives the close", Collections.singletonList(Long.valueOf(nodeId)), PbRenderJobUtil.requestedNodeIdsOf(fresh));
		assertEquals(Integer.valueOf(0), fresh.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
		assertEquals(Integer.valueOf(0), fresh.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));
		BaseRecord freshNode = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals("the node is READY for the resume", PbNodeStatusEnumType.READY.name(), status(freshNode, OlioFieldNames.FIELD_PB_NODE_STATUS));

		/// The run DTO the REST route serves (GET /{book}/run/{run}) must survive JSONUtil.exportObject: a raw
		/// ZonedDateTime in startedAt/completedAt made the export null and the route answer 200 with an empty
		/// body (found live by the P1-7 restart spec, 2026-10-11).
		Map<String, Object> dto = PbServiceFacade.getRun(u, bk.get(FieldNames.FIELD_OBJECT_ID), runOid);
		assertNotNull("run DTO", dto);
		assertEquals(runOid, dto.get("runObjectId"));
		assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), dto.get("runStatus"));
		assertEquals(Boolean.TRUE, dto.get("resumable"));
		assertTrue("startedAt must be a string in the DTO, not a java.time value", dto.get("startedAt") == null || dto.get("startedAt") instanceof String);
		assertTrue("completedAt must be a string in the DTO, not a java.time value", dto.get("completedAt") == null || dto.get("completedAt") instanceof String);
		String json = JSONUtil.exportObject(dto);
		assertNotNull("JSONUtil.exportObject(runDto) must not be null (the REST route would answer 200 with an empty body)", json);
		assertTrue("exported DTO carries the run id", json.contains(runOid));
		assertTrue("exported DTO carries startedAt", json.contains("\"startedAt\""));
		logger.info("[P1-7 case01] run DTO json=" + json);
	}

	/**
	 * (ii) A run that was opened (PENDING) but never dispatched: closed INTERRUPTED "before this run was
	 * dispatched", still resumable; its node was never stamped and stays PENDING.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case02_pendingRunIsClosedAsNeverDispatched() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		preSweep(wf, "case02");
		BaseRecord node = plainNode(u, wf, "p17-c02", PbNodeTypeEnumType.PORTRAIT, 0);
		long nodeId = idOf(node);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord run = PbGraphUtil.startRun(u, wf, PbBookUtil.workflowGroupPath(SLUG), Collections.singletonList(Long.valueOf(nodeId)));
		assertNotNull("run", run);
		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
		assertEquals("precondition: PENDING", PbRunStatusEnumType.PENDING.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));

		long t0 = System.currentTimeMillis();
		Map<String, Object> r = PbRecoveryUtil.sweepInterrupted(wf);
		logger.info("[P1-7 case02] sweep durationMs=" + (System.currentTimeMillis() - t0) + " result=" + r);
		assertSweep("case02", r, 0, 1, 0, 0, 0);

		BaseRecord fresh = PbGraphUtil.readRun(u, runOid, orgId);
		assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), status(fresh, OlioFieldNames.FIELD_PB_RUN_STATUS));
		String err = fresh.get(OlioFieldNames.FIELD_PB_ERROR);
		assertEquals(PENDING_TEXT + RESUME_HINT, err);
		assertTrue(err.contains("before this run was dispatched"));
		assertEquals(Collections.singletonList(Long.valueOf(nodeId)), PbRenderJobUtil.requestedNodeIdsOf(fresh));
		assertEquals("a never-dispatched node is left PENDING", PbNodeStatusEnumType.PENDING.name(),
			status(PbGraphUtil.readNode(u, nodeOid, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
	}

	/**
	 * (iii) The {@code TestPbNodeRun.case07} shape: the run was already closed FAILED ("Execution abandoned
	 * before the run could be closed") but its node was left RUNNING. The sweep returns the node to READY and
	 * leaves the closed run exactly as it was.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case03_runningNodeBehindAClosedRunIsResetAndTheRunUntouched() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		preSweep(wf, "case03");
		BaseRecord node = plainNode(u, wf, "p17-c03", PbNodeTypeEnumType.PORTRAIT, 0);
		long nodeId = idOf(node);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord run = PbGraphUtil.startRun(u, wf, PbBookUtil.workflowGroupPath(SLUG), Collections.singletonList(Long.valueOf(nodeId)));
		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
		assertTrue(PbGraphUtil.markRunning(u, run));
		assertTrue(PbGraphUtil.persistStatus(u, node, PbNodeStatusEnumType.RUNNING));
		assertTrue("run must close FAILED", PbGraphUtil.completeRun(u, run, PbRunStatusEnumType.FAILED, 0, 0, ABANDONED_MSG));
		BaseRecord before = PbGraphUtil.readRun(u, runOid, orgId);
		assertEquals(PbRunStatusEnumType.FAILED.name(), status(before, OlioFieldNames.FIELD_PB_RUN_STATUS));
		String completedBefore = String.valueOf((Object) before.get(OlioFieldNames.FIELD_PB_COMPLETED_AT));
		assertEquals(PbNodeStatusEnumType.RUNNING.name(), status(PbGraphUtil.readNode(u, nodeOid, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));

		long t0 = System.currentTimeMillis();
		Map<String, Object> r = PbRecoveryUtil.sweepInterrupted(wf);
		logger.info("[P1-7 case03] sweep durationMs=" + (System.currentTimeMillis() - t0) + " result=" + r);
		assertSweep("case03", r, 0, 0, 1, 0, 0);

		BaseRecord after = PbGraphUtil.readRun(u, runOid, orgId);
		assertEquals("a closed run is not reopened or rewritten", PbRunStatusEnumType.FAILED.name(), status(after, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals(ABANDONED_MSG, after.get(OlioFieldNames.FIELD_PB_ERROR));
		assertEquals(Integer.valueOf(0), after.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
		assertEquals(Integer.valueOf(0), after.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));
		/// completedAt carries a DDL default (now()), so "unchanged" is the only meaningful assertion.
		assertEquals("completedAt is untouched", completedBefore, String.valueOf((Object) after.get(OlioFieldNames.FIELD_PB_COMPLETED_AT)));
		BaseRecord freshNode = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals(PbNodeStatusEnumType.READY.name(), status(freshNode, OlioFieldNames.FIELD_PB_NODE_STATUS));
		/// persistStatus writes only nodeStatus; nothing ever stamped an error on this node.
		assertNull("no error is invented for the reset", freshNode.get(OlioFieldNames.FIELD_PB_LAST_ERROR));
	}

	/**
	 * (iv) In flight in this process is skipped, not reconciled: run A has a registered cancel token and its
	 * node a held claim (what a live job looks like from the sweep's side), run B / node B are orphaned. The
	 * sweep closes B and resets node B, counts A and node A as skipped, and leaves both A rows RUNNING. Once
	 * the token and claim are gone a second sweep reconciles A.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case04_inFlightRunAndNodeAreSkipped() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		preSweep(wf, "case04");
		BaseRecord nodeA = plainNode(u, wf, "p17-c04a", PbNodeTypeEnumType.PORTRAIT, 0);
		BaseRecord nodeB = plainNode(u, wf, "p17-c04b", PbNodeTypeEnumType.PORTRAIT, 1);
		long idA = idOf(nodeA);
		long idB = idOf(nodeB);
		String oidA = nodeA.get(FieldNames.FIELD_OBJECT_ID);
		String oidB = nodeB.get(FieldNames.FIELD_OBJECT_ID);
		String gpath = PbBookUtil.workflowGroupPath(SLUG);

		BaseRecord runA = PbGraphUtil.startRun(u, wf, gpath, Collections.singletonList(Long.valueOf(idA)));
		BaseRecord runB = PbGraphUtil.startRun(u, wf, gpath, Collections.singletonList(Long.valueOf(idB)));
		String runAOid = runA.get(FieldNames.FIELD_OBJECT_ID);
		String runBOid = runB.get(FieldNames.FIELD_OBJECT_ID);
		assertNotEquals(runAOid, runBOid);
		assertTrue(PbGraphUtil.markRunning(u, runA));
		assertTrue(PbGraphUtil.markRunning(u, runB));
		assertTrue(PbGraphUtil.persistStatus(u, nodeA, PbNodeStatusEnumType.RUNNING));
		assertTrue(PbGraphUtil.persistStatus(u, nodeB, PbNodeStatusEnumType.RUNNING));

		SummarizeProgress token = new SummarizeProgress();
		List<Long> claimA = Collections.singletonList(Long.valueOf(idA));
		PbRunCancelRegistry.register(runAOid, token);
		assertNull("precondition: node A is claimable", PbNodeInflight.tryClaim(claimA, runAOid));
		try {
			assertNotNull(PbRunCancelRegistry.peek(runAOid));
			assertEquals(runAOid, PbNodeInflight.runOf(idA));
			assertNull(PbRunCancelRegistry.peek(runBOid));
			assertNull(PbNodeInflight.runOf(idB));

			long t0 = System.currentTimeMillis();
			Map<String, Object> r = PbRecoveryUtil.sweepInterrupted(wf);
			logger.info("[P1-7 case04] sweep with A in flight durationMs=" + (System.currentTimeMillis() - t0) + " result=" + r);
			assertSweep("case04", r, 1, 0, 1, 1, 1);

			BaseRecord freshA = PbGraphUtil.readRun(u, runAOid, orgId);
			assertEquals("the in-flight run is left RUNNING", PbRunStatusEnumType.RUNNING.name(), status(freshA, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertNull("and carries no error", freshA.get(OlioFieldNames.FIELD_PB_ERROR));
			assertEquals("the claimed node is left RUNNING", PbNodeStatusEnumType.RUNNING.name(),
				status(PbGraphUtil.readNode(u, oidA, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
			BaseRecord freshB = PbGraphUtil.readRun(u, runBOid, orgId);
			assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), status(freshB, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals(RUNNING_TEXT + RESUME_HINT, freshB.get(OlioFieldNames.FIELD_PB_ERROR));
			assertEquals(PbNodeStatusEnumType.READY.name(),
				status(PbGraphUtil.readNode(u, oidB, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertNotNull("the sweep does not remove the token", PbRunCancelRegistry.peek(runAOid));
			assertEquals("the sweep does not release the claim", runAOid, PbNodeInflight.runOf(idA));
		}
		finally {
			PbNodeInflight.release(claimA, runAOid);
			PbRunCancelRegistry.unregister(runAOid, token);
		}
		assertNull(PbRunCancelRegistry.peek(runAOid));
		assertNull(PbNodeInflight.runOf(idA));

		/// The "next boot": nothing is in flight any more, so A is reconciled like any other orphan.
		Map<String, Object> r2 = PbRecoveryUtil.sweepInterrupted(wf);
		logger.info("[P1-7 case04] second sweep result=" + r2);
		assertSweep("case04 second sweep", r2, 1, 0, 1, 0, 0);
		assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), status(PbGraphUtil.readRun(u, runAOid, orgId), OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals(PbNodeStatusEnumType.READY.name(), status(PbGraphUtil.readNode(u, oidA, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
	}

	/**
	 * (v) A run opened without an execution set (the pre-P1-6 shape): closed INTERRUPTED with no resume hint,
	 * and {@code resume} refuses it with a 409 that says so. The other refusals are checked on the way: a run
	 * that is not INTERRUPTED (409) and no run at all (400). None of the refusals opens a run.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case05_runWithoutExecutionSetIsNotResumable() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		preSweep(wf, "case05");
		String gpath = PbBookUtil.workflowGroupPath(SLUG);

		BaseRecord run = PbGraphUtil.startRun(u, wf, gpath, Collections.emptyList());
		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
		assertTrue(PbGraphUtil.markRunning(u, run));
		assertTrue("precondition: no execution set", PbRenderJobUtil.requestedNodeIdsOf(PbGraphUtil.readRun(u, runOid, orgId)).isEmpty());

		long t0 = System.currentTimeMillis();
		Map<String, Object> r = PbRecoveryUtil.sweepInterrupted(wf);
		logger.info("[P1-7 case05] sweep durationMs=" + (System.currentTimeMillis() - t0) + " result=" + r);
		assertSweep("case05", r, 1, 0, 0, 0, 0);

		BaseRecord fresh = PbGraphUtil.readRun(u, runOid, orgId);
		assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), status(fresh, OlioFieldNames.FIELD_PB_RUN_STATUS));
		String err = fresh.get(OlioFieldNames.FIELD_PB_ERROR);
		assertEquals("no resume hint without an execution set", RUNNING_TEXT, err);
		assertFalse(err.contains("resume to continue"));
		assertTrue(PbRenderJobUtil.requestedNodeIdsOf(fresh).isEmpty());

		long newestBefore = newestRunId(u, wf);
		String runName = fresh.get(FieldNames.FIELD_NAME);
		try {
			PbRenderJobUtil.resume(u, bk, wf, fresh, (SDUtil) null, swarmServer());
			fail("a run without an execution set must not be resumable");
		}
		catch(PictureBookException e) {
			logger.info("[P1-7 case05] refusal (no execution set): " + e.getStatus() + " " + e.getMessage());
			assertEquals(409, e.getStatus());
			assertEquals("Run " + runName + " has no execution set to resume (opened before P1-6)", e.getMessage());
		}

		/// A run that is not INTERRUPTED: the status is named in the refusal.
		BaseRecord done = PbGraphUtil.startRun(u, wf, gpath, Collections.emptyList());
		String doneOid = done.get(FieldNames.FIELD_OBJECT_ID);
		assertTrue(PbGraphUtil.markRunning(u, done));
		assertTrue(PbGraphUtil.completeRun(u, done, PbRunStatusEnumType.COMPLETED, 0, 0, null));
		BaseRecord doneFresh = PbGraphUtil.readRun(u, doneOid, orgId);
		assertEquals(PbRunStatusEnumType.COMPLETED.name(), status(doneFresh, OlioFieldNames.FIELD_PB_RUN_STATUS));
		long newestAfterDone = newestRunId(u, wf);
		try {
			PbRenderJobUtil.resume(u, bk, wf, doneFresh, (SDUtil) null, swarmServer());
			fail("a COMPLETED run must not be resumable");
		}
		catch(PictureBookException e) {
			logger.info("[P1-7 case05] refusal (not INTERRUPTED): " + e.getStatus() + " " + e.getMessage());
			assertEquals(409, e.getStatus());
			assertEquals("Run " + doneFresh.get(FieldNames.FIELD_NAME) + " is COMPLETED; only an INTERRUPTED run can be resumed", e.getMessage());
		}
		try {
			PbRenderJobUtil.resume(u, bk, wf, (BaseRecord) null, (SDUtil) null, swarmServer());
			fail("no run must be a 400");
		}
		catch(PictureBookException e) {
			assertEquals(400, e.getStatus());
			assertEquals("A run to resume is required", e.getMessage());
		}
		assertEquals("no refusal opened a run", newestAfterDone, newestRunId(u, wf));
		assertTrue("the COMPLETED fixture run is the only row this case added after the sweep", newestAfterDone > newestBefore);
		assertEquals("the refused run is unchanged", PbRunStatusEnumType.INTERRUPTED.name(),
			status(PbGraphUtil.readRun(u, runOid, orgId), OlioFieldNames.FIELD_PB_RUN_STATUS));
	}

	/**
	 * (vi) Resume. Portrait A is rendered DONE through a real job (fake backend persisting a real PNG). Then a
	 * run requesting A and B is left RUNNING with B RUNNING - the "server stopped" shape - and swept. Resuming
	 * the INTERRUPTED run opens a {@code pb.resume} run naming its origin, executes ONLY B (A is current and
	 * reused, never re-rendered), closes COMPLETED, leaves the interrupted run as it was, and moves
	 * {@code workflow.lastRun} to the new run. The backend is called exactly twice over the whole case.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case06_resumeExecutesOnlyTheUnfinishedNode() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String server = swarmServer();
		preSweep(wf, "case06");
		assertEquals("precondition: no active jobs for this principal", 0, AsyncJobRegistry.activeCount(u));
		BaseRecord cpA = createPortraitCharacter(u, "c06a");
		BaseRecord cpB = createPortraitCharacter(u, "c06b");
		BaseRecord nA = portraitNode(u, wf, "p17-c06a", cpA.get(FieldNames.FIELD_OBJECT_ID), null);
		BaseRecord nB = portraitNode(u, wf, "p17-c06b", cpB.get(FieldNames.FIELD_OBJECT_ID), null);
		long idA = idOf(nA);
		long idB = idOf(nB);
		String oidA = nA.get(FieldNames.FIELD_OBJECT_ID);
		String oidB = nB.get(FieldNames.FIELD_OBJECT_ID);
		AtomicInteger calls = new AtomicInteger();
		SDUtil fake = fakePngBackend(server, calls);

		/// Step 1: A becomes DONE through the job (fake backend, real executor path).
		long t0 = System.currentTimeMillis();
		Map<String, Object> d1 = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(Long.valueOf(idA)), fake, server, PbRenderJobUtil.KIND_RENDER);
		logger.info("[P1-7 case06] first render dto=" + d1);
		String firstRunOid = (String) d1.get("runObjectId");
		AsyncJob j1 = awaitTerminal(u, (String) d1.get("jobId"), RENDER_TIMEOUT_MS);
		logger.info("[P1-7 case06] first render durationMs=" + (System.currentTimeMillis() - t0) + " job=" + j1.getStatus() + " result=" + j1.getResult());
		assertEquals(AsyncJob.Status.COMPLETED, j1.getStatus());
		Map<?, ?> r1 = JSONUtil.importObject(j1.getResult(), Map.class);
		assertEquals("A's render closes COMPLETED: " + r1, PbRunStatusEnumType.COMPLETED.name(), r1.get("runStatus"));
		assertEquals(PbRunStatusEnumType.COMPLETED.name(), status(PbGraphUtil.readRun(u, firstRunOid, orgId), OlioFieldNames.FIELD_PB_RUN_STATUS));
		BaseRecord aDone = PbGraphUtil.readNode(u, oidA, orgId);
		assertEquals(PbNodeStatusEnumType.DONE.name(), status(aDone, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertNotNull("A carries an inputHash once DONE", aDone.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
		assertEquals("A has one artifact", 1, PbArtifactUtil.listChain(u, aDone, PbPipelineUtil.ROLE_PORTRAIT).size());
		assertEquals("one backend call so far", 1, calls.get());
		assertNull(PbNodeInflight.runOf(idA));
		assertNull(PbRunCancelRegistry.peek(firstRunOid));

		/// Step 2: the "server stopped" shape - a run requesting A and B, RUNNING, with B RUNNING.
		BaseRecord run = PbGraphUtil.startRun(u, wf, PbBookUtil.workflowGroupPath(SLUG), Arrays.asList(Long.valueOf(idA), Long.valueOf(idB)));
		String runOid = run.get(FieldNames.FIELD_OBJECT_ID);
		assertTrue(PbGraphUtil.markRunning(u, run));
		assertTrue(PbGraphUtil.persistStatus(u, nB, PbNodeStatusEnumType.RUNNING));
		Map<String, Object> sweep = PbRecoveryUtil.sweepInterrupted(wf);
		logger.info("[P1-7 case06] sweep result=" + sweep);
		assertSweep("case06", sweep, 1, 0, 1, 0, 0);
		BaseRecord interrupted = PbGraphUtil.readRun(u, runOid, orgId);
		assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), status(interrupted, OlioFieldNames.FIELD_PB_RUN_STATUS));
		String interruptedError = interrupted.get(OlioFieldNames.FIELD_PB_ERROR);
		assertTrue("resumable: " + interruptedError, interruptedError != null && interruptedError.contains("resume to continue"));
		assertEquals(Arrays.asList(Long.valueOf(idA), Long.valueOf(idB)), PbRenderJobUtil.requestedNodeIdsOf(interrupted));
		assertEquals(PbNodeStatusEnumType.READY.name(), status(PbGraphUtil.readNode(u, oidB, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertEquals("A is still DONE after the sweep", PbNodeStatusEnumType.DONE.name(), status(PbGraphUtil.readNode(u, oidA, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));

		/// Step 3: resume.
		long t1 = System.currentTimeMillis();
		Map<String, Object> d2 = PbRenderJobUtil.resume(u, bk, wf, interrupted, fake, server);
		logger.info("[P1-7 case06] resume dto=" + d2);
		assertEquals(PbRenderJobUtil.KIND_RESUME, d2.get("kind"));
		assertEquals("the new run names its origin", runOid, d2.get("resumedFromRunObjectId"));
		String newRunOid = (String) d2.get("runObjectId");
		assertNotNull(newRunOid);
		assertNotEquals("a NEW run is opened", runOid, newRunOid);
		assertEquals("only B is planned", Integer.valueOf(1), d2.get("plannedNodeCount"));
		assertEquals(Collections.singletonList(handleOf(nB)), d2.get("planned"));
		assertEquals("A is reused", Integer.valueOf(1), d2.get("reusedNodeCount"));
		assertEquals(Integer.valueOf(0), d2.get("pinnedNodeCount"));
		AsyncJob j2 = awaitTerminal(u, (String) d2.get("jobId"), RENDER_TIMEOUT_MS);
		logger.info("[P1-7 case06] resume durationMs=" + (System.currentTimeMillis() - t1) + " job=" + j2.getStatus() + " result=" + j2.getResult());
		assertEquals(AsyncJob.Status.COMPLETED, j2.getStatus());
		Map<?, ?> r2 = JSONUtil.importObject(j2.getResult(), Map.class);
		assertEquals(newRunOid, r2.get("runObjectId"));
		assertEquals("the resume closes COMPLETED: " + r2, PbRunStatusEnumType.COMPLETED.name(), r2.get("runStatus"));
		assertEquals("exactly one node executed", Integer.valueOf(1), r2.get("executedNodeCount"));
		assertEquals(Integer.valueOf(0), r2.get("failedNodeCount"));
		List<?> outcomes = (List<?>) r2.get("nodes");
		assertEquals(1, outcomes.size());
		Map<?, ?> outcome = (Map<?, ?>) outcomes.get(0);
		assertEquals("the executed node is B", handleOf(nB), outcome.get("handle"));
		assertEquals(PbNodeStatusEnumType.DONE.name(), outcome.get("status"));
		assertTrue("durationMs is recorded (never asserted beyond presence)", r2.get("durationMs") instanceof Number);

		BaseRecord newRun = PbGraphUtil.readRun(u, newRunOid, orgId);
		assertEquals(PbRunStatusEnumType.COMPLETED.name(), status(newRun, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals("the resume run's execution set is what it executed", Collections.singletonList(Long.valueOf(idB)), PbRenderJobUtil.requestedNodeIdsOf(newRun));
		assertEquals(Integer.valueOf(1), newRun.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
		assertEquals(Integer.valueOf(0), newRun.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));
		assertNull(newRun.get(OlioFieldNames.FIELD_PB_ERROR));
		BaseRecord stillInterrupted = PbGraphUtil.readRun(u, runOid, orgId);
		assertEquals("the interrupted run is history, not rewritten", PbRunStatusEnumType.INTERRUPTED.name(), status(stillInterrupted, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals(interruptedError, stillInterrupted.get(OlioFieldNames.FIELD_PB_ERROR));
		assertEquals(Arrays.asList(Long.valueOf(idA), Long.valueOf(idB)), PbRenderJobUtil.requestedNodeIdsOf(stillInterrupted));

		BaseRecord bDone = PbGraphUtil.readNode(u, oidB, orgId);
		assertEquals(PbNodeStatusEnumType.DONE.name(), status(bDone, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertNotNull(bDone.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
		assertEquals("B has its one artifact", 1, PbArtifactUtil.listChain(u, bDone, PbPipelineUtil.ROLE_PORTRAIT).size());
		assertNotNull("B's artifact is selected", PbArtifactUtil.findSelected(u, bDone, PbPipelineUtil.ROLE_PORTRAIT));
		BaseRecord aAfter = PbGraphUtil.readNode(u, oidA, orgId);
		assertEquals(PbNodeStatusEnumType.DONE.name(), status(aAfter, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertEquals("A was NOT re-rendered: still one artifact", 1, PbArtifactUtil.listChain(u, aAfter, PbPipelineUtil.ROLE_PORTRAIT).size());
		assertEquals("the backend was called once for A and once for B", 2, calls.get());
		assertEquals("workflow.lastRun moves to the resume run", Long.valueOf(idOf(newRun)), lastRunIdOf(u, wf));
		assertNull(PbNodeInflight.runOf(idA));
		assertNull(PbNodeInflight.runOf(idB));
		assertNull(PbRunCancelRegistry.peek(newRunOid));
	}

	/**
	 * Gap (2) (§6.C item 61): a node that already has a SELECTED artifact is re-rendered and the render is
	 * cancelled mid-stage on the live {@code .39} backend. The bytes are discarded, nothing is recorded, and
	 * the previously selected revision is exactly what it was: same objectId, same revision, same data,
	 * still selected, still GENERATED.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case07_liveCancelLeavesThePreviouslySelectedArtifactUntouched() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String server = swarmServer();
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P1-7 selected artifact survives a live cancel");
		preSweep(wf, "case07");
		BaseRecord cp = createPortraitCharacter(u, "c07");
		BaseRecord node = portraitNode(u, wf, "p17-c07", cp.get(FieldNames.FIELD_OBJECT_ID), fastOverride(renderCfg));
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		long nodeId = idOf(node);

		/// Seed revision 1 as a selected artifact, the way an earlier render would have left it.
		String artifactPath = PbBookUtil.artifactGroupPath(SLUG);
		BaseRecord seedData = persistPng(u, artifactPath, "p17-c07-seed-" + UUID.randomUUID().toString().substring(0, 8), 4242);
		BaseRecord seeded = PbArtifactUtil.persistArtifact(u, node, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE,
			artifactPath, seedData, null, null, null);
		assertNotNull("seed artifact", seeded);
		List<BaseRecord> chainBefore = PbArtifactUtil.listChain(u, node, PbPipelineUtil.ROLE_PORTRAIT);
		assertEquals(1, chainBefore.size());
		BaseRecord selBefore = PbArtifactUtil.findSelected(u, node, PbPipelineUtil.ROLE_PORTRAIT);
		assertNotNull("the seed is selected", selBefore);
		String selOid = selBefore.get(FieldNames.FIELD_OBJECT_ID);
		Integer selRevision = selBefore.get(OlioFieldNames.FIELD_PB_REVISION);
		assertEquals(Integer.valueOf(1), selRevision);
		assertEquals(PbReviewStatusEnumType.GENERATED.name(), status(selBefore, OlioFieldNames.FIELD_PB_REVIEW_STATUS));
		BaseRecord selData = selBefore.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("the seed carries its data", selData);
		long selDataId = ((Number) selData.get(FieldNames.FIELD_ID)).longValue();
		assertEquals(((Number) seedData.get(FieldNames.FIELD_ID)).longValue(), selDataId);

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
		Map<String, Object> dto = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(Long.valueOf(nodeId)), cancelling, server, PbRenderJobUtil.KIND_RENDER);
		String runOid = (String) dto.get("runObjectId");
		runOidRef.set(runOid);
		published.countDown();
		AsyncJob job = awaitTerminal(u, (String) dto.get("jobId"), RENDER_TIMEOUT_MS);
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("[P1-7 case07] live cancel durationMs=" + durationMs + " job=" + job.getStatus() + " observed=" + observed + " result=" + job.getResult());

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
		assertTrue("the outcome says the bytes were discarded: " + outcome.get("error"), String.valueOf(outcome.get("error")).contains("discarded"));

		BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
		assertEquals(PbRunStatusEnumType.CANCELLED.name(), status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals("Cancelled after 0 of 1 stage(s)", run.get(OlioFieldNames.FIELD_PB_ERROR));
		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		String nodeStatus = status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS);
		assertTrue("the node is returned to a pre-run status: " + nodeStatus,
			PbNodeStatusEnumType.READY.name().equals(nodeStatus) || PbNodeStatusEnumType.PENDING.name().equals(nodeStatus));
		assertEquals("revertedTo agrees with the stored status", nodeStatus, outcome.get("revertedTo"));

		/// The point of the case: the chain and its selection are exactly as seeded.
		List<BaseRecord> chainAfter = PbArtifactUtil.listChain(u, fresh, PbPipelineUtil.ROLE_PORTRAIT);
		assertEquals("no revision was added by the cancelled render", 1, chainAfter.size());
		BaseRecord selAfter = PbArtifactUtil.findSelected(u, fresh, PbPipelineUtil.ROLE_PORTRAIT);
		assertNotNull("the seed is still selected", selAfter);
		assertEquals(selOid, selAfter.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals(selRevision, selAfter.get(OlioFieldNames.FIELD_PB_REVISION));
		assertEquals(Boolean.TRUE, selAfter.get(OlioFieldNames.FIELD_PB_SELECTED));
		assertEquals(PbReviewStatusEnumType.GENERATED.name(), status(selAfter, OlioFieldNames.FIELD_PB_REVIEW_STATUS));
		BaseRecord dataAfter = selAfter.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("the seed still points at its data", dataAfter);
		assertEquals("same data row", selDataId, ((Number) dataAfter.get(FieldNames.FIELD_ID)).longValue());
		assertEquals(selOid, chainAfter.get(0).get(FieldNames.FIELD_OBJECT_ID));
		assertNull(PbNodeInflight.runOf(nodeId));
		assertNull(PbRunCancelRegistry.peek(runOid));
	}

	/**
	 * (vii) Worker side of a stop, LAST because {@code beginShutdown()} / {@code shutdown()} are process-global.
	 * Two jobs on one lane: the first holds the lane inside its backend call, the second is stamped RUNNING and
	 * waits for the lane. {@link AsyncJobRegistry#beginShutdown()} is announced: the waiter abandons the wait,
	 * returns its node to READY, closes its run INTERRUPTED "after 0 of 1 stage(s)" and its job completes
	 * carrying that summary while the holder is still inside the backend. When the holder's backend call then
	 * fails (the connection a stopping container closes), its run is INTERRUPTED too, not FAILED, and its node
	 * is READY. {@link AsyncJobRegistry#shutdown()} then retires the pool and the registry is usable again:
	 * a new job runs, the DB reads still work, and a final sweep of this workflow finds nothing to do.
	 */
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case08_shutdownInterruptsTheLaneWaiterAndTheRegistryRecovers() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		preSweep(wf, "case08");
		assertEquals("precondition: no active jobs for this principal", 0, AsyncJobRegistry.activeCount(u));
		assertEquals("precondition: one permit per lane in this JVM", 1, PbRenderLanes.permits());
		assertFalse("precondition: not shutting down", AsyncJobRegistry.isShuttingDown());
		String lane = "http://p17-lane-" + UUID.randomUUID().toString().substring(0, 8) + ":7801/";
		BaseRecord cp1 = createPortraitCharacter(u, "c08a");
		BaseRecord cp2 = createPortraitCharacter(u, "c08b");
		BaseRecord n1 = portraitNode(u, wf, "p17-c08a", cp1.get(FieldNames.FIELD_OBJECT_ID), null);
		BaseRecord n2 = portraitNode(u, wf, "p17-c08b", cp2.get(FieldNames.FIELD_OBJECT_ID), null);

		AtomicInteger entrants = new AtomicInteger();
		CountDownLatch firstEntered = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		String message = "test: backend closed during stop " + UUID.randomUUID();
		SDUtil holding = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				int order = entrants.incrementAndGet();
				if(order != 1) {
					throw new IllegalStateException("test: a second backend call must never happen during a stop (" + name + ")");
				}
				firstEntered.countDown();
				try {
					releaseFirst.await(LOCAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				}
				catch(InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				throw new RuntimeException(message);
			}
		};

		String job1Id = null;
		String job2Id = null;
		String run1 = null;
		String run2 = null;
		try {
			Map<String, Object> d1 = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(Long.valueOf(idOf(n1))), holding, lane, PbRenderJobUtil.KIND_RENDER);
			assertTrue("the first backend call must be reached", firstEntered.await(2, TimeUnit.MINUTES));
			Map<String, Object> d2 = PbRenderJobUtil.submit(u, bk, wf, Collections.singletonList(Long.valueOf(idOf(n2))), holding, lane, PbRenderJobUtil.KIND_RENDER);
			logger.info("[P1-7 case08] dto1=" + d1 + " dto2=" + d2);
			job1Id = (String) d1.get("jobId");
			job2Id = (String) d2.get("jobId");
			run1 = (String) d1.get("runObjectId");
			run2 = (String) d2.get("runObjectId");

			/// The second run is RUNNING (row and node stamped) and waiting on the lane.
			int waiting = -1;
			long deadline = System.currentTimeMillis() + 60_000L;
			while(System.currentTimeMillis() < deadline) {
				waiting = PbRenderLanes.waiting(lane);
				if(waiting == 1) {
					break;
				}
				Thread.sleep(POLL_MS);
			}
			logger.info("[P1-7 case08] while the first holds the lane: available=" + PbRenderLanes.available(lane) + " waiting=" + waiting + " entrants=" + entrants.get());
			assertEquals("exactly one run waits for the lane", 1, waiting);
			assertEquals("no permit is free while the first stage runs", 0, PbRenderLanes.available(lane));
			assertEquals("the second backend call has not started", 1, entrants.get());
			assertEquals(PbRunStatusEnumType.RUNNING.name(), status(PbGraphUtil.readRun(u, run2, orgId), OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals(PbNodeStatusEnumType.RUNNING.name(), status(PbGraphUtil.readNode(u, n2.get(FieldNames.FIELD_OBJECT_ID), orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));

			/// The stop is announced.
			long t0 = System.currentTimeMillis();
			AsyncJobRegistry.beginShutdown();
			assertTrue(AsyncJobRegistry.isShuttingDown());
			AsyncJob j2 = null;
			deadline = System.currentTimeMillis() + LOCAL_TIMEOUT_MS;
			while(System.currentTimeMillis() < deadline) {
				j2 = AsyncJobRegistry.get(u, job2Id);
				assertNotNull("job 2 must stay visible", j2);
				if(j2.isTerminal()) {
					break;
				}
				Thread.sleep(POLL_MS);
			}
			logger.info("[P1-7 case08] waiter after beginShutdown durationMs=" + (System.currentTimeMillis() - t0) + " job2=" + j2.getStatus() + " result=" + j2.getResult());
			assertTrue("the waiter abandons the lane wait once a stop is announced", j2.isTerminal());
			assertEquals("the holder is still inside its backend call", 1L, releaseFirst.getCount());
			AsyncJob j1 = AsyncJobRegistry.get(u, job1Id);
			assertNotNull(j1);
			assertFalse("the holder's job is not terminal yet", j1.isTerminal());

			assertEquals(AsyncJob.Status.COMPLETED, j2.getStatus());
			Map<?, ?> r2 = JSONUtil.importObject(j2.getResult(), Map.class);
			assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), r2.get("runStatus"));
			assertEquals("Interrupted by shutdown after 0 of 1 stage(s)", r2.get("error"));
			assertEquals(Integer.valueOf(0), r2.get("executedNodeCount"));
			Map<?, ?> o2 = (Map<?, ?>) ((List<?>) r2.get("nodes")).get(0);
			assertEquals("INTERRUPTED", o2.get("status"));
			assertEquals(PbNodeStatusEnumType.READY.name(), o2.get("revertedTo"));
			assertEquals("interrupted by shutdown while waiting for the render lane", o2.get("error"));
			BaseRecord run2Rec = PbGraphUtil.readRun(u, run2, orgId);
			assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), status(run2Rec, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals("Interrupted by shutdown after 0 of 1 stage(s)", run2Rec.get(OlioFieldNames.FIELD_PB_ERROR));
			assertEquals("the interrupted run keeps its execution set for the resume", Collections.singletonList(Long.valueOf(idOf(n2))), PbRenderJobUtil.requestedNodeIdsOf(run2Rec));
			BaseRecord n2After = PbGraphUtil.readNode(u, n2.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertEquals(PbNodeStatusEnumType.READY.name(), status(n2After, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertNull("nothing ever ran for the waiter, so no error is stamped", n2After.get(OlioFieldNames.FIELD_PB_LAST_ERROR));
			assertNull(PbNodeInflight.runOf(idOf(n2)));
			assertNull(PbRunCancelRegistry.peek(run2));
			assertEquals("nobody waits any more", 0, PbRenderLanes.waiting(lane));
			assertEquals("the holder still has the permit", 0, PbRenderLanes.available(lane));
			assertEquals(PbRunStatusEnumType.RUNNING.name(), status(PbGraphUtil.readRun(u, run1, orgId), OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals(PbNodeStatusEnumType.RUNNING.name(), status(PbGraphUtil.readNode(u, n1.get(FieldNames.FIELD_OBJECT_ID), orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertNotNull("the holder's cancel token is still registered", PbRunCancelRegistry.peek(run1));

			/// The holder's backend call fails as the container stops.
			long t1 = System.currentTimeMillis();
			releaseFirst.countDown();
			j1 = awaitTerminal(u, job1Id, LOCAL_TIMEOUT_MS);
			logger.info("[P1-7 case08] holder after release durationMs=" + (System.currentTimeMillis() - t1) + " job1=" + j1.getStatus() + " result=" + j1.getResult());
			assertEquals(AsyncJob.Status.COMPLETED, j1.getStatus());
			Map<?, ?> r1 = JSONUtil.importObject(j1.getResult(), Map.class);
			assertEquals("a stage failing during a stop is INTERRUPTED, not FAILED", PbRunStatusEnumType.INTERRUPTED.name(), r1.get("runStatus"));
			assertEquals("Interrupted by shutdown after 0 of 1 stage(s)", r1.get("error"));
			Map<?, ?> o1 = (Map<?, ?>) ((List<?>) r1.get("nodes")).get(0);
			assertEquals("INTERRUPTED", o1.get("status"));
			assertEquals(PbNodeStatusEnumType.READY.name(), o1.get("revertedTo"));
			String o1Err = String.valueOf(o1.get("error"));
			assertTrue("the outcome names the shutdown: " + o1Err, o1Err.startsWith("interrupted by shutdown: "));
			assertTrue("and carries the stage's message: " + o1Err, o1Err.contains(message));
			BaseRecord run1Rec = PbGraphUtil.readRun(u, run1, orgId);
			assertEquals(PbRunStatusEnumType.INTERRUPTED.name(), status(run1Rec, OlioFieldNames.FIELD_PB_RUN_STATUS));
			assertEquals(Integer.valueOf(0), run1Rec.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));
			/// executeStage stamps FAILED (with lastError) before the job reverts the node, so only the status is
			/// asserted here: READY is what the resume needs.
			assertEquals(PbNodeStatusEnumType.READY.name(), status(PbGraphUtil.readNode(u, n1.get(FieldNames.FIELD_OBJECT_ID), orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertNull(PbNodeInflight.runOf(idOf(n1)));
			assertNull(PbRunCancelRegistry.peek(run1));
			assertEquals("the lane is released", 1, PbRenderLanes.available(lane));
			assertEquals(0, PbRenderLanes.waiting(lane));
			assertEquals("the backend was entered exactly once", 1, entrants.get());
			assertEquals("no active jobs remain", 0, AsyncJobRegistry.activeCount(u));
		}
		finally {
			releaseFirst.countDown();
			awaitQuietly(u, job1Id, LOCAL_TIMEOUT_MS);
			awaitQuietly(u, job2Id, LOCAL_TIMEOUT_MS);
			AsyncJobRegistry.shutdown();
		}

		/// After the stop completes the registry is usable again (the next boot in one JVM: the test suite).
		assertFalse("shutdown() clears the flag", AsyncJobRegistry.isShuttingDown());
		assertEquals("shutdown() clears the registry", 0, AsyncJobRegistry.size());
		AsyncJob probe = AsyncJobRegistry.submit(u, "p17.probe", "p17", new AsyncJobWork() {
			@Override
			public String run(AsyncJob job) throws Exception {
				return "{\"ok\":true}";
			}
		});
		assertNotNull("a job can be submitted after shutdown()", probe);
		AsyncJob probeDone = awaitTerminal(u, probe.getJobId(), LOCAL_TIMEOUT_MS);
		assertEquals(AsyncJob.Status.COMPLETED, probeDone.getStatus());
		assertEquals("{\"ok\":true}", probeDone.getResult());
		assertNotNull(run1);
		assertNotNull(run2);
		assertEquals("DB reads still work and run 1 is still INTERRUPTED", PbRunStatusEnumType.INTERRUPTED.name(),
			status(PbGraphUtil.readRun(u, run1, orgId), OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertEquals("run 2 is still INTERRUPTED", PbRunStatusEnumType.INTERRUPTED.name(),
			status(PbGraphUtil.readRun(u, run2, orgId), OlioFieldNames.FIELD_PB_RUN_STATUS));
		Map<String, Object> finalSweep = PbRecoveryUtil.sweepInterrupted(wf);
		logger.info("[P1-7 case08] final sweep result=" + finalSweep);
		assertSweep("case08 final sweep", finalSweep, 0, 0, 0, 0, 0);
	}

	// ─────────────────────────────── sweep helpers ───────────────────────────────

	/**
	 * Reconcile whatever an earlier (possibly failed) run of this suite left RUNNING/PENDING in this test's own
	 * workflow, so the case's counters measure only what the case seeded. Never called while a submit on the
	 * same workflow is mid-flight (the startRun -> tryClaim/register window would be closed by it).
	 */
	private void preSweep(BaseRecord wf, String tag) {
		Map<String, Object> r = PbRecoveryUtil.sweepInterrupted(wf);
		logger.info("[P1-7 " + tag + "] reconciling pre-sweep: " + r);
	}

	private static int count(Map<String, Object> r, String key) {
		Object v = r.get(key);
		assertTrue("sweep result carries an integer " + key + ": " + v, v instanceof Number);
		return ((Number) v).intValue();
	}

	private static void assertSweep(String tag, Map<String, Object> r, int runsInterrupted, int pendingInterrupted,
			int nodesReset, int runsSkippedInFlight, int nodesSkippedInFlight) {
		assertNotNull(tag + ": sweep result", r);
		assertEquals(tag + ": runsInterrupted " + r, runsInterrupted, count(r, "runsInterrupted"));
		assertEquals(tag + ": pendingInterrupted " + r, pendingInterrupted, count(r, "pendingInterrupted"));
		assertEquals(tag + ": nodesReset " + r, nodesReset, count(r, "nodesReset"));
		assertEquals(tag + ": runsSkippedInFlight " + r, runsSkippedInFlight, count(r, "runsSkippedInFlight"));
		assertEquals(tag + ": nodesSkippedInFlight " + r, nodesSkippedInFlight, count(r, "nodesSkippedInFlight"));
		assertEquals(tag + ": ownerUnresolved " + r, 0, count(r, "ownerUnresolved"));
		assertEquals(tag + ": denied " + r, 0, count(r, "denied"));
		assertEquals(tag + ": errored " + r, 0, count(r, "errored"));
		Object problems = r.get("problems");
		assertTrue(tag + ": problems is a list: " + problems, problems instanceof List);
		assertTrue(tag + ": no problems: " + problems, ((List<?>) problems).isEmpty());
		assertTrue(tag + ": durationMs is recorded (never asserted beyond presence)", r.get("durationMs") instanceof Number);
	}

	// ─────────────────────────────── fake PNG persistence ───────────────────────────────

	/** A real 64x64 PNG (ImageIO) in a seed-derived flat colour: enough for decodeDimensions and the byte checks. */
	private static byte[] pngBytes(int seed) throws Exception {
		BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		int rgb = (int) ((seed * 2654435761L) & 0xFFFFFFL);
		if(rgb == 0) {
			rgb = 0x336699;
		}
		for(int y = 0; y < 64; y++) {
			for(int x = 0; x < 64; x++) {
				img.setRGB(x, y, rgb);
			}
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		assertTrue("ImageIO must write png", ImageIO.write(img, "png", baos));
		return baos.toByteArray();
	}

	/**
	 * Persist a {@code data.data} PNG the way {@link SDUtil} does after a backend call (group by path,
	 * content type, bytes through ByteModelUtil, a {@code seed} attribute, AccessPoint.create) and return the
	 * pre-create instance, which still carries the bytes and the attribute the executor reads back in memory.
	 */
	private BaseRecord persistPng(BaseRecord u, String groupPath, String dname, int seed) throws Exception {
		long orgId = orgOf(u);
		BaseRecord grp = ioContext.getPathUtil().makePath(u, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("data group " + groupPath, grp);
		ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		clist.parameter(FieldNames.FIELD_NAME, dname);
		BaseRecord data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, u, null, clist);
		assertNotNull("data instance", data);
		data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(data, pngBytes(seed));
		AttributeUtil.addAttribute(data, "seed", Integer.valueOf(seed));
		BaseRecord created = ioContext.getAccessPoint().create(u, data);
		assertNotNull("data.data " + dname + " must be created", created);
		if(data.get(FieldNames.FIELD_OBJECT_ID) == null) {
			data.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		}
		Object id = data.get(FieldNames.FIELD_ID);
		if(id == null || ((Number) id).longValue() <= 0L) {
			data.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		}
		assertNotNull("the returned data carries its objectId", data.get(FieldNames.FIELD_OBJECT_ID));
		return data;
	}

	/**
	 * The fake backend: every call persists a real PNG under the executor's artifact path and returns it, so
	 * the portrait executor runs its byte/seed/dimension/artifact path for real. {@code calls} counts entries.
	 */
	private SDUtil fakePngBackend(String server, AtomicInteger calls) {
		return new SDUtil(SDAPIEnumType.SWARM, server) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				int n = calls.incrementAndGet();
				try {
					return new ArrayList<>(Collections.singletonList(persistPng(user, groupPath, name + " - 1 - p17 - " + n, 1000 + n)));
				}
				catch(Exception e) {
					throw new RuntimeException("test: fake PNG persist failed: " + e.getMessage(), e);
				}
			}
		};
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
			bk = PbBookUtil.createBook(u, dataPath(), SLUG, "P1-7 restart recovery fixture");
		}
		assertNotNull("book " + SLUG, bk);
		return bk;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("workflow", wf);
		return wf;
	}

	/** A node with a random handle and no scope: enough for the sweep and the plan, never executed. */
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
		String name = "P17-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
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

	/**
	 * The id of {@code workflow.lastRun}, read uncached by value - or null when the pointer is unset.
	 * {@code lastRun} is deliberately absent from {@code PbGraphUtil.workflowRequest()}, so it has to be
	 * projected explicitly; a projected foreign field comes back as a record carrying its {@code id}.
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

	/** Best-effort wait used only in finally blocks: never asserts, never throws. */
	private static void awaitQuietly(BaseRecord u, String jobId, long maxMs) {
		if(jobId == null) {
			return;
		}
		long deadline = System.currentTimeMillis() + maxMs;
		try {
			while(System.currentTimeMillis() < deadline) {
				AsyncJob job = AsyncJobRegistry.get(u, jobId);
				if(job == null || job.isTerminal()) {
					return;
				}
				Thread.sleep(POLL_MS);
			}
		}
		catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		}
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
