package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 1, P1-3: {@link PbNodeExecutor#executeNodeRun} - the single-node run path behind
 * {@code POST .../node/{id}/regenerate}.
 * <p>
 * What is under test: the run is stamped RUNNING (run row AND node) while the SD call is in flight; a
 * throw closes the run FAILED with the error and the node FAILED with {@code lastError}; a pinned node is
 * refused with 409 and opens no run; a missing SD server is refused with 500 and opens no run; and a live
 * PORTRAIT render on the Swarm node from {@code test.swarm.server} binds the character (refHash), links the
 * portrait to the profile, closes DONE with an inputHash, and then reads as refDrift after the character is
 * edited.
 * <p>
 * Live {@code am7db}; the SD backend is never mocked. Where the SD stage must fail on demand an {@link SDUtil}
 * <i>subclass</i> throws from the real {@code createImage} entry point while the rest of the executor runs
 * for real. The shared {@code pb2c-graph} fixture book is used (as {@code TestPbGraph}/{@code TestPbStale}
 * do); every node here has a random handle and every run assertion is scoped by the run's own objectId or
 * by "newer than the newest run before the call", never by status alone.
 * <p>
 * Lives in {@code org.cote.accountmanager.olio.picturebook} for the package-private
 * {@code executeNodeRun(user, book, workflow, node, SDUtil)} seam.
 */
public class TestPbNodeRun extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	/// Never enrolled in any per-book role by any test (the book Writer role is r/c/u/d), so the read-only
	/// measurement in case06 stays honest across runs (role membership persists in the database).
	private static final String READER_USER = "p13ReadOnly";
	private static final String SLUG = "pb2c-graph";
	private static final String CHARS_PATH = "~/P13Chars";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	/// Render budgets are generous on purpose: a timing is never a pass/fail threshold here.
	private static final long RENDER_TIMEOUT_MS = 20L * 60L * 1000L;

	/// Hand-written (does not start with PictureBookUtil's generated-prompt prefix) and CLOTHED, so the
	/// render never falls into the bare-record nude-render trap (PictureBookV3Implementation.md §6.L).
	private static final String PORTRAIT_PROMPT = "a tall woman in her thirties with short dark hair and sea-grey eyes,"
		+ " wearing a weathered oilskin coat over a thick wool sweater, head and shoulders portrait, soft overcast light";

	@Before
	public void runSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/**
	 * While the SD call is in flight the run row reads RUNNING and the node reads RUNNING; after the SD
	 * stage throws, the run is FAILED carrying the message, {@code completedAt} is set, and the node is
	 * FAILED with {@code lastError}. The run is the one the call opened (it names exactly this node).
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case01_runIsRunningDuringExecutionAndFailedAfterThrow() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord cp = createPortraitCharacter(u, "c01");
		BaseRecord node = portraitNode(u, wf, "p13-c01", cp.get(FieldNames.FIELD_OBJECT_ID), null);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		long newestBefore = newestRunId(u, wf);

		Map<String, Object> observed = new LinkedHashMap<>();
		String message = "test: SD disabled mid-run " + UUID.randomUUID();
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				BaseRecord run = latestRun(u, wf);
				observed.put("runObjectId", (run != null ? run.get(FieldNames.FIELD_OBJECT_ID) : null));
				observed.put("runStatus", (run != null ? status(run, OlioFieldNames.FIELD_PB_RUN_STATUS) : null));
				BaseRecord n = PbGraphUtil.readNode(u, nodeOid, orgId);
				observed.put("nodeStatus", (n != null ? status(n, OlioFieldNames.FIELD_PB_NODE_STATUS) : null));
				observed.put("imageName", name);
				throw new RuntimeException(message);
			}
		};

		long t0 = System.currentTimeMillis();
		Exception thrown = null;
		try {
			PbNodeExecutor.executeNodeRun(u, bk, wf, node, sdu);
			fail("executeNodeRun must rethrow the SD failure");
		}
		catch(Exception e) {
			thrown = e;
		}
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P1-3 case01 executeNodeRun (SD throw) durationMs=" + durationMs + " observed=" + observed);

		assertNotNull("the SD failure must propagate", thrown);
		assertTrue("the propagated error carries the SD message: " + thrown.getMessage(),
			String.valueOf(thrown.getMessage()).contains(message));
		assertTrue("the SD stage must actually have been reached", observed.containsKey("runStatus"));
		assertEquals("the run reads RUNNING while the SD call is in flight", PbRunStatusEnumType.RUNNING.name(), observed.get("runStatus"));
		assertEquals("the node reads RUNNING while the SD call is in flight", PbNodeStatusEnumType.RUNNING.name(), observed.get("nodeStatus"));
		String imageName = (String) observed.get("imageName");
		assertTrue("the portrait is named after the character (v1 convention): " + imageName,
			imageName != null && imageName.startsWith("portrait_"));

		String runOid = (String) observed.get("runObjectId");
		assertNotNull("the in-flight run must be readable", runOid);
		BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
		assertNotNull("the run must still exist after the failure", run);
		assertTrue("the run opened by this call is newer than every run before it",
			((Number) run.get(FieldNames.FIELD_ID)).longValue() > newestBefore);
		assertEquals("the run closes FAILED when its one node throws", PbRunStatusEnumType.FAILED.name(),
			status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
		String runError = run.get(OlioFieldNames.FIELD_PB_ERROR);
		assertTrue("the run error carries the SD message: " + runError, runError != null && runError.contains(message));
		assertNotNull("completedAt is stamped on close", run.get(OlioFieldNames.FIELD_PB_COMPLETED_AT));
		/// requestedNodeIds is a list/long in the model but reads back as a List of Integer for small ids.
		List<? extends Number> requested = run.get(OlioFieldNames.FIELD_PB_REQUESTED_NODE_IDS);
		assertNotNull("requestedNodeIds", requested);
		assertTrue("the run names exactly this node: " + requested, requested.size() == 1
			&& requested.get(0).longValue() == ((Number) node.get(FieldNames.FIELD_ID)).longValue());
		assertEquals("one failed node", Integer.valueOf(1), run.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));

		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals("the node is FAILED after the throw", PbNodeStatusEnumType.FAILED.name(),
			status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String lastError = fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertTrue("lastError carries the SD message: " + lastError, lastError != null && lastError.contains(message));
		assertNotNull("lastRunAt is stamped on failure", fresh.get(OlioFieldNames.FIELD_PB_LAST_RUN_AT));
	}

	/**
	 * The public entry point with a real but unreachable SD server: the real {@link SDUtil} fails, the run
	 * closes FAILED with a non-blank error, the node is FAILED with {@code lastError}. No subclass here - this
	 * is the exact path {@code PbServiceFacade.requestRegenerate} takes when the configured server is down.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case02_unreachableSdServerClosesRunFailed() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord cp = createPortraitCharacter(u, "c02");
		BaseRecord node = portraitNode(u, wf, "p13-c02", cp.get(FieldNames.FIELD_OBJECT_ID), null);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		long newestBefore = newestRunId(u, wf);

		long t0 = System.currentTimeMillis();
		Exception thrown = null;
		try {
			PbNodeExecutor.executeNodeRun(u, bk, wf, node, SDAPIEnumType.SWARM.name(), "http://127.0.0.1:1");
			fail("an unreachable SD server must fail the run");
		}
		catch(Exception e) {
			thrown = e;
		}
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P1-3 case02 executeNodeRun (unreachable SD) durationMs=" + durationMs + " error=" + thrown.getMessage());

		BaseRecord run = latestRun(u, wf);
		assertNotNull("a run must have been opened", run);
		assertTrue("the run is the one this call opened", ((Number) run.get(FieldNames.FIELD_ID)).longValue() > newestBefore);
		List<? extends Number> requested = run.get(OlioFieldNames.FIELD_PB_REQUESTED_NODE_IDS);
		assertTrue("the run names exactly this node: " + requested, requested != null && requested.size() == 1
			&& requested.get(0).longValue() == ((Number) node.get(FieldNames.FIELD_ID)).longValue());
		assertEquals("the run closes FAILED", PbRunStatusEnumType.FAILED.name(),
			status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
		String runError = run.get(OlioFieldNames.FIELD_PB_ERROR);
		assertTrue("the run carries a non-blank error", runError != null && !runError.isBlank());
		assertNotNull("completedAt is stamped on close", run.get(OlioFieldNames.FIELD_PB_COMPLETED_AT));

		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals("the node is FAILED", PbNodeStatusEnumType.FAILED.name(),
			status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String lastError = fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertTrue("lastError is recorded", lastError != null && !lastError.isBlank());
		assertEquals("run.error and node.lastError agree", lastError, runError);
	}

	/** A pinned node is refused by {@code requestRegenerate} with 409 BEFORE any run is opened. */
	@Test
	public void case03_pinnedNodeIs409AndOpensNoRun() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord cp = createPortraitCharacter(u, "c03");
		BaseRecord node = portraitNode(u, wf, "p13-c03", cp.get(FieldNames.FIELD_OBJECT_ID), null);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		assertTrue("pin the node", PbGraphUtil.setPinned(u, node, true));
		assertEquals("precondition: pinned", Boolean.TRUE, PbGraphUtil.readNode(u, nodeOid, orgId).get(OlioFieldNames.FIELD_PB_PINNED));

		long newestBefore = newestRunId(u, wf);
		long countBefore = runCount(u, wf);
		try {
			PbServiceFacade.requestRegenerate(u, bookOid, nodeOid, SDAPIEnumType.SWARM.name(), swarmServer());
			fail("a pinned node must be refused");
		}
		catch(PictureBookException pbe) {
			assertEquals("pinned → 409", 409, pbe.getStatus());
			assertTrue("the 409 says the node is pinned: " + pbe.getMessage(), pbe.getMessage().contains("pinned"));
		}
		assertEquals("no run is opened for a refused regenerate", countBefore, runCount(u, wf));
		assertEquals("the newest run is unchanged", newestBefore, newestRunId(u, wf));
		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals("the node status is untouched", PbNodeStatusEnumType.PENDING.name(),
			status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertNull("no lastError is written for a refused regenerate", fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR));
	}

	/**
	 * No SD server (null and blank, through the facade and the executor) is the 500 "SD server not
	 * configured" gate, raised before a run row is opened - a configuration error must not leave PENDING
	 * rows behind.
	 */
	@Test
	public void case04_missingSdServerIs500AndOpensNoRun() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord cp = createPortraitCharacter(u, "c04");
		BaseRecord node = portraitNode(u, wf, "p13-c04", cp.get(FieldNames.FIELD_OBJECT_ID), null);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);

		long newestBefore = newestRunId(u, wf);
		long countBefore = runCount(u, wf);
		for(String[] args : new String[][] { { null, null }, { "SWARM", null }, { "SWARM", "" }, { null, swarmServer() } }) {
			try {
				PbServiceFacade.requestRegenerate(u, bookOid, nodeOid, args[0], args[1]);
				fail("requestRegenerate without an SD server must be refused: " + Arrays.toString(args));
			}
			catch(PictureBookException pbe) {
				assertEquals("no SD server → 500 for " + Arrays.toString(args), 500, pbe.getStatus());
				assertTrue(pbe.getMessage(), pbe.getMessage().contains("SD server not configured"));
			}
		}
		try {
			PbNodeExecutor.executeNodeRun(u, bk, wf, node, (SDUtil) null);
			fail("executeNodeRun with no SDUtil must be refused");
		}
		catch(PictureBookException pbe) {
			assertEquals(500, pbe.getStatus());
			assertTrue(pbe.getMessage(), pbe.getMessage().contains("SD server not configured"));
		}
		assertEquals("no run is opened when the SD gate refuses", countBefore, runCount(u, wf));
		assertEquals("the newest run is unchanged", newestBefore, newestRunId(u, wf));
		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals("the node status is untouched", PbNodeStatusEnumType.PENDING.name(),
			status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
	}

	/**
	 * Security-reviewer finding (P1-3): a user who can READ the book but not UPDATE it is refused by both
	 * {@code requestRegenerate} and {@code testNode} with 403, before any run row is opened and before the
	 * executor's olio-principal narrative refresh can touch the character. The reader is built from direct
	 * READ grants (book group + each foreign record the book projects), never from the per-book Writer role,
	 * which is read/create/update/delete and would make the measurement meaningless.
	 */
	@Test
	public void case06_readerIs403AndOpensNoRun() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord cp = createPortraitCharacter(u, "c06");
		String cpOid = cp.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord node = portraitNode(u, wf, "p13-c06", cpOid, null);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord reader = readOnlyReader(u, bk);
		BaseRecord bookAsReader = PbBookUtil.readBook(reader, bookOid, orgId);
		assertNotNull("precondition: the reader must be able to read the book, or the 403 proves nothing", bookAsReader);
		PolicyResponseType upd = ioContext.getAuthorizationUtil().canUpdate(reader, reader, bookAsReader);
		assertFalse("precondition: the reader must NOT be permitted to update the book (got " + (upd != null ? upd.getType() : null) + ")",
			upd != null && upd.getType() == PolicyResponseEnumType.PERMIT);

		long newestBefore = newestRunId(u, wf);
		long countBefore = runCount(u, wf);
		try {
			PbServiceFacade.requestRegenerate(reader, bookOid, nodeOid, SDAPIEnumType.SWARM.name(), swarmServer());
			fail("a read-only user must not be able to regenerate a node");
		}
		catch(PictureBookException pbe) {
			assertEquals("read-only regenerate → 403", 403, pbe.getStatus());
			assertTrue(pbe.getMessage(), pbe.getMessage().contains("Not authorized"));
		}
		try {
			PbServiceFacade.testNode(reader, bookOid, nodeOid, SDAPIEnumType.SWARM.name(), swarmServer());
			fail("a read-only user must not be able to test a node");
		}
		catch(PictureBookException pbe) {
			assertEquals("read-only testNode → 403", 403, pbe.getStatus());
			assertTrue(pbe.getMessage(), pbe.getMessage().contains("Not authorized"));
		}

		assertEquals("no run is opened for a refused regenerate", countBefore, runCount(u, wf));
		assertEquals("the newest run is unchanged", newestBefore, newestRunId(u, wf));
		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals("the node status is untouched", PbNodeStatusEnumType.PENDING.name(),
			status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertNull("no lastError is written for a refused regenerate", fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR));

		/// The olio-principal narrative refresh never ran: the hand-written prompt is exactly as created.
		Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cpOid);
		cq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative" });
		cq.setCache(false);
		BaseRecord cpAfter = ioContext.getAccessPoint().find(u, cq);
		assertNotNull(cpAfter);
		BaseRecord nar = cpAfter.get("narrative");
		assertNotNull("the narrative is still linked", nar);
		ioContext.getReader().populate(nar, new String[] { "sdPrompt" });
		assertEquals("the character's narrative.sdPrompt is unchanged by a refused request", PORTRAIT_PROMPT, nar.get("sdPrompt"));
	}

	/** A non-Exception Throwable thrown from the SD stage; distinct from JUnit's AssertionError so {@code fail()} is never swallowed. */
	private static final class SdStageError extends Error {
		private static final long serialVersionUID = 1L;
		SdStageError(String message) {
			super(message);
		}
	}

	/**
	 * The {@code finally} abandonment branch of {@code executeNodeRun}. Its catch is {@code catch(Exception)},
	 * so every Exception from the SD stage (case01/case02) closes the run FAILED <i>before</i> the finally runs
	 * and the branch is dead for them; the one path that reaches it is a Throwable that is not an Exception.
	 * Thrown from the real {@code createImage} entry point (nothing between it and {@code executeNodeRun}
	 * catches Throwable or Error), an {@link Error} bypasses the catch: the run closes FAILED carrying the
	 * fixed "Execution abandoned" message with {@code failedNodeCount} 0 (failNode was never called), and the
	 * node is left RUNNING with no {@code lastError} - exactly the shape the restart sweep (P1-7) is specified
	 * to pick up. The Error itself propagates unchanged. The node is stamped FAILED at the end so the shared
	 * fixture workflow carries no RUNNING node out of this test.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case07_errorBypassesCatchAndFinallyClosesRunAbandoned() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord cp = createPortraitCharacter(u, "c07");
		BaseRecord node = portraitNode(u, wf, "p13-c07", cp.get(FieldNames.FIELD_OBJECT_ID), null);
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		long newestBefore = newestRunId(u, wf);

		Map<String, Object> observed = new LinkedHashMap<>();
		String message = "test: Error from the SD stage " + UUID.randomUUID();
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				BaseRecord run = latestRun(u, wf);
				observed.put("runObjectId", (run != null ? run.get(FieldNames.FIELD_OBJECT_ID) : null));
				observed.put("runStatus", (run != null ? status(run, OlioFieldNames.FIELD_PB_RUN_STATUS) : null));
				BaseRecord n = PbGraphUtil.readNode(u, nodeOid, orgId);
				observed.put("nodeStatus", (n != null ? status(n, OlioFieldNames.FIELD_PB_NODE_STATUS) : null));
				throw new SdStageError(message);
			}
		};

		long t0 = System.currentTimeMillis();
		SdStageError thrown = null;
		try {
			PbNodeExecutor.executeNodeRun(u, bk, wf, node, sdu);
			fail("executeNodeRun must let the Error propagate");
		}
		catch(SdStageError e) {
			thrown = e;
		}
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P1-3 case07 executeNodeRun (SD Error) durationMs=" + durationMs + " observed=" + observed);

		assertNotNull("the Error must propagate unchanged (not wrapped, not swallowed)", thrown);
		assertEquals("the propagated Error is the one the SD stage threw", message, thrown.getMessage());
		assertTrue("the SD stage must actually have been reached", observed.containsKey("runStatus"));
		assertEquals("the run reads RUNNING while the SD call is in flight", PbRunStatusEnumType.RUNNING.name(), observed.get("runStatus"));
		assertEquals("the node reads RUNNING while the SD call is in flight", PbNodeStatusEnumType.RUNNING.name(), observed.get("nodeStatus"));

		String runOid = (String) observed.get("runObjectId");
		assertNotNull("the in-flight run must be readable", runOid);
		BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
		assertNotNull("the run must still exist after the Error", run);
		assertTrue("the run opened by this call is newer than every run before it",
			((Number) run.get(FieldNames.FIELD_ID)).longValue() > newestBefore);
		assertEquals("the finally closes the run FAILED", PbRunStatusEnumType.FAILED.name(),
			status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
		String runError = run.get(OlioFieldNames.FIELD_PB_ERROR);
		assertTrue("the run error is the fixed abandonment message, not the Error's: " + runError,
			runError != null && runError.contains("Execution abandoned before the run could be closed"));
		assertFalse("the Error's own message never reaches the run row: " + runError, runError.contains(message));
		assertNotNull("completedAt is stamped on close", run.get(OlioFieldNames.FIELD_PB_COMPLETED_AT));
		List<? extends Number> requested = run.get(OlioFieldNames.FIELD_PB_REQUESTED_NODE_IDS);
		assertTrue("the run names exactly this node: " + requested, requested != null && requested.size() == 1
			&& requested.get(0).longValue() == ((Number) node.get(FieldNames.FIELD_ID)).longValue());
		assertEquals("failNode was bypassed with the catch: no failed node is counted", Integer.valueOf(0),
			run.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));

		/// The node is what the restart sweep looks for: RUNNING, no lastError, behind a FAILED run.
		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertNotNull(fresh);
		assertEquals("the node is left RUNNING (failNode never ran)", PbNodeStatusEnumType.RUNNING.name(),
			status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		assertNull("no lastError is written on the abandonment path", fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR));

		/// Leave the shared fixture clean: stamp the stranded node FAILED the way the sweep will.
		assertTrue("cleanup: the stranded node must be stampable FAILED", PbGraphUtil.persistStatus(u, fresh, PbNodeStatusEnumType.FAILED));
		assertEquals("cleanup: the node reads FAILED", PbNodeStatusEnumType.FAILED.name(),
			status(PbGraphUtil.readNode(u, nodeOid, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));
	}

	/**
	 * GPU, live Swarm: a real PORTRAIT render through the run path on the installed checkpoint. Asserts the
	 * three G-30 convergences (profile-driven prompt via {@code resolveSceneCharacter}, portrait linked to the
	 * profile, SD server resolved from the request) plus the Phase 1 run bookkeeping: run COMPLETED, node
	 * DONE with inputHash, a ROLE_CHARACTER binding with refHash, an IMAGE artifact whose bytes are a real
	 * PNG (written to {@code target/} for inspection). Then the exit criterion: editing the character makes
	 * the node read refDrift and STALE on the next scan. Duration is logged, never asserted.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case05_livePortraitRunThenCharacterEditDrifts() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		String server = swarmServer();
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "P1-3 live portrait run");
		String model = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);

		BaseRecord cp = createPortraitCharacter(u, "c05");
		String cpOid = cp.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord override = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG,
			new String[] { OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_STEPS, "hires" });
		override.set(OlioFieldNames.FIELD_SD_MODEL, model);
		override.set(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(12));
		override.set("hires", Boolean.FALSE);
		BaseRecord node = portraitNode(u, wf, "p13-c05", cpOid,
			PbConfigUtil.sparseOverride(override, Arrays.asList(OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_STEPS, "hires")));
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		assertNull("precondition: no inputHash before the render", node.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
		assertTrue("precondition: no bindings before the render", PbGraphUtil.listBindings(u, node).isEmpty());
		long newestBefore = newestRunId(u, wf);

		long t0 = System.currentTimeMillis();
		Map<String, Object> out = PbNodeExecutor.executeNodeRun(u, bk, wf, node, SDAPIEnumType.SWARM.name(), server);
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P1-3 case05 live portrait executeNodeRun durationMs=" + durationMs + " dto=" + out);

		assertNotNull("DTO", out);
		assertEquals("DTO nodeStatus", "DONE", out.get("nodeStatus"));
		assertEquals("DTO characterObjectId", cpOid, out.get("characterObjectId"));
		assertEquals("DTO portraitLinked (G-30 divergence 2: the portrait is linked to the profile)", Boolean.TRUE, out.get("portraitLinked"));
		assertEquals("DTO runStatus", PbRunStatusEnumType.COMPLETED.name(), String.valueOf((Object) out.get("runStatus")));
		assertEquals("DTO executedNodeCount", Integer.valueOf(1), out.get("executedNodeCount"));
		assertEquals("DTO failedNodeCount", Integer.valueOf(0), out.get("failedNodeCount"));
		assertTrue("DTO durationMs is recorded (never asserted beyond presence)", out.get("durationMs") instanceof Number);

		String runOid = (String) out.get("runObjectId");
		assertNotNull("DTO runObjectId", runOid);
		BaseRecord run = PbGraphUtil.readRun(u, runOid, orgId);
		assertNotNull("the run must be readable", run);
		assertTrue("the run is the one this call opened", ((Number) run.get(FieldNames.FIELD_ID)).longValue() > newestBefore);
		assertEquals("the run closes COMPLETED", PbRunStatusEnumType.COMPLETED.name(),
			status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
		assertNotNull("startedAt", run.get(OlioFieldNames.FIELD_PB_STARTED_AT));
		assertNotNull("completedAt", run.get(OlioFieldNames.FIELD_PB_COMPLETED_AT));
		assertEquals("executedNodeCount", Integer.valueOf(1), run.get(OlioFieldNames.FIELD_PB_EXECUTED_NODE_COUNT));
		assertEquals("failedNodeCount", Integer.valueOf(0), run.get(OlioFieldNames.FIELD_PB_FAILED_NODE_COUNT));
		assertNull("no error on a completed run", run.get(OlioFieldNames.FIELD_PB_ERROR));

		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertEquals("the node is DONE", PbNodeStatusEnumType.DONE.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String inputHash = fresh.get(OlioFieldNames.FIELD_PB_INPUT_HASH);
		assertTrue("inputHash is written on DONE", inputHash != null && !inputHash.isBlank());
		assertNull("no lastError on success", fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR));
		String promptText = fresh.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT);
		assertTrue("the resolved prompt is persisted on the node", promptText != null && !promptText.isBlank());
		logger.info("P1-3 case05 node.promptText=" + promptText);
		assertTrue("G-30 divergence 1: the prompt comes from the character's profile, not a bare name: " + promptText,
			promptText.contains("oilskin"));

		/// The character binding carries a refHash (the drift baseline) - written only by bindRecord.
		BaseRecord charBinding = null;
		for(BaseRecord b : PbGraphUtil.listBindings(u, fresh)) {
			if(PbPipelineUtil.ROLE_CHARACTER.equals(b.get(OlioFieldNames.FIELD_PB_ROLE))) {
				charBinding = b;
			}
		}
		assertNotNull("a ROLE_CHARACTER binding is written by the render", charBinding);
		assertEquals(OlioModelNames.MODEL_CHAR_PERSON, charBinding.get(OlioFieldNames.FIELD_PB_REF_MODEL));
		assertEquals(cpOid, charBinding.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
		String refHash = charBinding.get(OlioFieldNames.FIELD_PB_REF_HASH);
		assertTrue("refHash is written at bind time", refHash != null && !refHash.isBlank());

		/// The artifact: an IMAGE whose data.data bytes are a real PNG.
		String artifactOid = (String) out.get("artifactObjectId");
		assertNotNull("DTO artifactObjectId", artifactOid);
		BaseRecord artifact = PbArtifactUtil.readArtifact(u, artifactOid, orgId);
		assertNotNull("the artifact must be readable", artifact);
		assertEquals("selected", Boolean.TRUE, artifact.get(OlioFieldNames.FIELD_PB_SELECTED));
		assertEquals("role", PbPipelineUtil.ROLE_PORTRAIT, artifact.get(OlioFieldNames.FIELD_PB_ROLE));
		Object artifactDurationMs = artifact.get(OlioFieldNames.FIELD_PB_DURATION_MS);
		logger.info("P1-3 case05 artifact.durationMs=" + artifactDurationMs + " seed=" + artifact.get(OlioFieldNames.FIELD_PB_SEED)
			+ " byteLength=" + artifact.get(OlioFieldNames.FIELD_PB_BYTE_LENGTH));
		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("the artifact points at a data.data record", dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			IOSystem.getActiveContext().getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		assertNotNull("data objectId", dataOid);
		BaseRecord data = freshReadData(u, dataOid);
		assertNotNull("the rendered image record must be readable", data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertNotNull("the rendered image must have bytes", bytes);
		assertTrue("the rendered image must be larger than a PNG header (was " + bytes.length + ")", bytes.length > 1024);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		File png = new File("target/p13-portrait-" + System.currentTimeMillis() + ".png");
		FileUtil.emitFile(png.getPath(), bytes);
		assertTrue("the PNG must have been written to " + png.getAbsolutePath(), png.exists() && png.length() == bytes.length);
		logger.info("P1-3 case05 PNG written: " + png.getAbsolutePath() + " (" + bytes.length + " bytes)");

		/// The portrait is linked to the character's profile (G-30 divergence 2), through the user's own read.
		Query pq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, cpOid);
		pq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "profile" });
		pq.setCache(false);
		BaseRecord cpAfter = IOSystem.getActiveContext().getAccessPoint().find(u, pq);
		assertNotNull(cpAfter);
		BaseRecord profile = cpAfter.get("profile");
		assertNotNull("profile", profile);
		IOSystem.getActiveContext().getReader().populate(profile, new String[] { "portrait" });
		BaseRecord portrait = profile.get("portrait");
		assertNotNull("profile.portrait is linked after the render", portrait);
		assertEquals("profile.portrait is the rendered image", dataOid, portrait.get(FieldNames.FIELD_OBJECT_ID));

		/// Freshly rendered: not stale. The refHash written during the render must agree with the
		/// record as the scan sees it, or the executor would be creating self-drift.
		PbStaleUtil.clear();
		PbStaleUtil.Scan pre = PbStaleUtil.scan(u, bk, wf, true);
		Long nodeId = node.get(FieldNames.FIELD_ID);
		assertFalse("a freshly rendered node must not be stale before any edit: " + describe(pre.entries.get(nodeId)),
			pre.entries.containsKey(nodeId));

		/// Exit criterion: a charPerson edit shows the affected node.
		patchCharPerson(u, cp, "firstName", "After");
		PbStaleUtil.clear();
		PbStaleUtil.Scan post = PbStaleUtil.scan(u, bk, wf, true);
		PbStaleUtil.Entry entry = post.entries.get(nodeId);
		assertNotNull("the rendered node is stale after its character was edited", entry);
		assertEquals("reason", PbStaleUtil.REASON_REF_DRIFT, entry.reason);
		assertEquals("one drifted binding", 1, entry.driftedBindings.size());
		assertEquals(PbPipelineUtil.ROLE_CHARACTER, entry.driftedBindings.get(0).role);
		assertEquals(cpOid, entry.driftedBindings.get(0).refObjectId);
		assertFalse("driftedEntriesFor finds the node", post.driftedEntriesFor(OlioModelNames.MODEL_CHAR_PERSON, cpOid).isEmpty());
		assertEquals("the node reads STALE after the scan persisted it", PbNodeStatusEnumType.STALE.name(),
			status(PbGraphUtil.readNode(u, nodeOid, orgId), OlioFieldNames.FIELD_PB_NODE_STATUS));

		/// §10 rule 4 seam for P1-4: the STALE node keeps its selected artifact.
		BaseRecord artifactAfter = PbArtifactUtil.readArtifact(u, artifactOid, orgId);
		assertNotNull(artifactAfter);
		assertEquals("the STALE node keeps its selected artifact", Boolean.TRUE, artifactAfter.get(OlioFieldNames.FIELD_PB_SELECTED));
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

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("workflow", wf);
		return wf;
	}

	/**
	 * A second non-admin user in the owner's organization who can READ {@code bk} and nothing more: Read
	 * (GROUP + DATA) on the book's group, plus Read (DATA) directly on every foreign record the book's
	 * projection carries ({@code world} lives under the universe container, not in a book group, and
	 * {@code PolicyUtil} turns each populated foreign field into a "reading the parent requires reading the
	 * child" rule - the record-level grant is what {@code TestPbSharedBookRead} proved satisfies it). Grants
	 * are made by the organization admin purely as the fixture grantor; {@code MemberUtil.member} is
	 * idempotent and returns false when the entry already exists, so the grant is verified by the reader's
	 * own read in the caller, not by the return value.
	 */
	private BaseRecord readOnlyReader(BaseRecord owner, BaseRecord bk) {
		OrganizationContext org = getTestOrganization(ORG_A);
		long orgId = orgOf(owner);
		BaseRecord admin = org.getAdminUser();
		BaseRecord reader = ioContext.getFactory().getCreateUser(admin, READER_USER, orgId);
		assertNotNull("reader " + READER_USER, reader);
		assertFalse("precondition: the reader must not be a group administrator, or the 403 proves nothing",
			ioContext.getAuthorizationUtil().isModelAdministrator(ModelNames.MODEL_GROUP, reader));

		long gid = ((Number) bk.get(FieldNames.FIELD_GROUP_ID)).longValue();
		BaseRecord dir = ioContext.getAccessPoint().findById(owner, ModelNames.MODEL_GROUP, gid);
		assertNotNull("the book's group must be readable by its owner", dir);
		BaseRecord readGroup = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.GROUP.toString(), orgId);
		BaseRecord readData = AccessSchema.getSystemPermission(AccessSchema.SYSTEM_PERMISSION_READ, PermissionEnumType.DATA.toString(), orgId);
		assertNotNull("Read/GROUP permission", readGroup);
		assertNotNull("Read/DATA permission", readData);
		ioContext.getMemberUtil().member(admin, dir, reader, readGroup, true);
		ioContext.getMemberUtil().member(admin, dir, reader, readData, true);
		for(String f : new String[] { OlioFieldNames.FIELD_PB_WORLD, OlioFieldNames.FIELD_PB_SERIES, OlioFieldNames.FIELD_PB_CHAPTER,
				OlioFieldNames.FIELD_PB_SOURCE_DATA, OlioFieldNames.FIELD_PB_SD_CONFIG, OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG }) {
			Object v = bk.get(f);
			if(v instanceof BaseRecord) {
				ioContext.getMemberUtil().member(admin, (BaseRecord) v, reader, readData, true);
			}
		}
		CacheUtil.clearCache();
		return reader;
	}

	/**
	 * A PORTRAIT node scoped to {@code cpOid} (identity + name + scope + scopeRef [+ configOverride], the
	 * patch shape {@code TestPbGraph} uses), re-read uncached so the executor sees the persisted fields.
	 */
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
		if(configOverride != null) {
			assertEquals("precondition: configOverride", configOverride, fresh.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
		}
		return fresh;
	}

	/**
	 * A charPerson the user owns, describable for a portrait: a hand-written, clothed {@code narrative.sdPrompt}
	 * (wins in {@code resolveSceneCharacter} when the record-driven refresh succeeds) and the same text as the
	 * {@code pbDescription} attribute (the fallback when the sparse record cannot be profiled). The factory
	 * builds every sub-record except the narrative, so one is created and linked here the way
	 * {@code PictureBookUtil.ensureNarrative} does it.
	 */
	private BaseRecord createPortraitCharacter(BaseRecord u, String tag) throws Exception {
		long orgId = orgOf(u);
		BaseRecord grp = ioContext.getPathUtil().makePath(u, ModelNames.MODEL_GROUP, CHARS_PATH, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("characters group", grp);
		String gpath = grp.get(FieldNames.FIELD_PATH);
		String name = "P13-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, gpath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, u, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set("firstName", "Before");
		cp.set("lastName", "Drift");
		cp.set("gender", "female");
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

	/** Identity + name + one changed field, the {@code TestPbStale} idiom. */
	private void patchCharPerson(BaseRecord u, BaseRecord cp, String field, Object value) throws Exception {
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON,
			new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, field });
		patch.set(FieldNames.FIELD_ID, cp.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, cp.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, cp.get(FieldNames.FIELD_NAME));
		patch.set(field, value);
		assertNotNull("charPerson patch of " + field + " must persist", ioContext.getAccessPoint().update(u, patch));
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

	private Query runsQuery(BaseRecord u, BaseRecord wf) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, wf);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(PbGraphUtil.runRequest());
		q.setCache(false);
		return q;
	}

	private long runCount(BaseRecord u, BaseRecord wf) {
		return ioContext.getAccessPoint().count(u, runsQuery(u, wf));
	}

	/** The newest olio.pb.run of the workflow (highest id), read uncached; null when there is none. */
	private BaseRecord latestRun(BaseRecord u, BaseRecord wf) {
		BaseRecord[] recs = ioContext.getAccessPoint().list(u, runsQuery(u, wf)).getResults();
		if(recs == null || recs.length == 0) {
			return null;
		}
		BaseRecord best = null;
		long bestId = -1L;
		for(BaseRecord r : recs) {
			if(r.getSchema() == null) {
				r.setSchema(OlioModelNames.MODEL_PB_RUN);
			}
			long id = ((Number) r.get(FieldNames.FIELD_ID)).longValue();
			if(id > bestId) {
				bestId = id;
				best = r;
			}
		}
		return best;
	}

	private long newestRunId(BaseRecord u, BaseRecord wf) {
		BaseRecord r = latestRun(u, wf);
		return (r != null ? ((Number) r.get(FieldNames.FIELD_ID)).longValue() : -1L);
	}

	/// Enum fields read back as the enum (or, on list projections, the raw string); never hand a generic
	/// get() to String.valueOf - type inference picks the char[] overload and throws ClassCastException.
	private static String status(BaseRecord rec, String field) {
		Object v = (rec != null ? rec.get(field) : null);
		return (v != null ? v.toString().toUpperCase() : null);
	}

	private static String describe(PbStaleUtil.Entry e) {
		return (e == null ? "null" : e.handle + " reason=" + e.reason + " drifted=" + e.driftedBindings.size());
	}
}
