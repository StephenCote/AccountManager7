package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbArtifactUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbConfigUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbWatchedFields;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 2c exit criteria for the graph utilities, against real persisted records on {@code am7db}.
 * <p>
 * <b>The book world is a shared, stable fixture.</b> One slug, reused across runs: creating an Olio world
 * costs a corpus load, and a fresh slug per run would add minutes and leave a world behind every time.
 * Every case that needs isolation gets it from a randomly-suffixed <i>node handle</i> instead, which is
 * free. Where a case makes a NEGATIVE assertion it says explicitly why a stale row from an earlier run
 * cannot satisfy it.
 */
public class TestPbGraph extends BaseTest {

	/** Pre-existing, corpus-loaded organization - see the memory note on org-seed cost. */
	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";

	/** Stable slug: the book world is created once and re-opened on later runs. */
	private static final String SLUG = "pb2c-graph";

	/**
	 * Golden vector for {@link PbConfigUtil#sha256Hex(String)}: the published SHA-256 of the three ASCII
	 * bytes {@code abc}. This pins the algorithm (SHA-256, not {@code CryptoUtil}'s mutable-static
	 * SHA-512) and the lower-case hex encoding.
	 */
	private static final String SHA256_OF_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

	/**
	 * Golden vector for the UTF-8 requirement: SHA-256 of the Turkish dotted/dotless i pair
	 * ({@code I U+0131 space U+0130 i}) encoded as UTF-8, which is 7 bytes.
	 * {@code CryptoUtil.getDigestAsString(String)} uses the <b>platform default charset</b>, so on a
	 * cp1252 host {@code U+0131} is not even encodable and the digest would differ - which is why PB2
	 * does not use it. Written as unicode escapes so the value cannot depend on this file's encoding.
	 */
	private static final String TURKISH_I_PAIR = "I\u0131 \u0130i";
	private static final String SHA256_OF_TURKISH_I = "3de140f8595826857c8ab406130e92c1b27f0bb9fc3abe986fdefdf33e6732bd";

	private ExecutorService bounded = null;

	@Before
	public void graphSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		bounded = Executors.newSingleThreadExecutor();
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	/**
	 * Get-or-create the shared book. {@code PbBookUtil.createBook} refuses a duplicate slug with a 409 by
	 * design (the unique index is the serialization point), so a reusable fixture has to probe first.
	 */
	private BaseRecord book(BaseRecord u) {
		long orgId = u.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord existing = PbBookUtil.findBookBySlug(u, SLUG, orgId);
		if(existing != null) {
			return existing;
		}
		BaseRecord created = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 2c graph fixture");
		assertNotNull("Failed to create the fixture book", created);
		return created;
	}

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("Failed to resolve the fixture workflow", wf);
		return wf;
	}

	/** A node with a random handle, so no earlier run's row can satisfy a negative assertion. */
	private BaseRecord node(BaseRecord u, BaseRecord wf, String prefix, PbNodeTypeEnumType type, int ordinal) {
		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, type, PbBookUtil.workflowGroupPath(SLUG), ordinal);
		assertNotNull("Failed to create node " + handle, n);
		return n;
	}

	/** Run {@code c} on another thread with a hard time bound, so a non-terminating plan cannot hang the suite. */
	private <T> T withinSeconds(int seconds, String what, Callable<T> c) {
		Future<T> f = bounded.submit(c);
		try {
			return f.get(seconds, TimeUnit.SECONDS);
		}
		catch(TimeoutException e) {
			f.cancel(true);
			fail(what + " did not complete within " + seconds + "s - it does not terminate");
		}
		catch(Exception e) {
			f.cancel(true);
			/// A StackOverflowError from unbounded plan recursion arrives here wrapped
			fail(what + " failed: " + e.getCause() + " / " + e.getMessage());
		}
		return null;
	}

	// ───────── 1: the workflow <-> run cycle must not make a full plan diverge ─────────

	/**
	 * Ratification 1. Phase 2b asserted the mutual reference {@code workflow.lastRun <-> run.workflow}
	 * <b>exists</b> and that both sides emit a {@code bigint} column. It did NOT assert that a plan over
	 * it terminates, and that is the thing that actually breaks: {@code QueryPlan.checkRecursion} only
	 * catches an immediate parent/child match, {@code pathSet} keys on a {@code planPath()} that grows a
	 * unique string per level, and {@code maximumDepth = 500} only <b>logs</b> - there is no return.
	 * <p>
	 * This matters beyond PB2 because {@code GET /rest/model/{type}/{objectId}/full} is generic and uses
	 * {@code planMost(true)}, so phase 4 would expose it on a public route.
	 * <p>
	 * Every leg is time-bounded on another thread: a case that proves non-termination by hanging the build
	 * is not a usable test.
	 */
	@Test
	public void case01_fullPlanOverTheWorkflowRunCycleTerminates() {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = u.get(FieldNames.FIELD_ORGANIZATION_ID);

		/// The cycle has to be REAL before any of this measures anything: with a null lastRun the two-hop
		/// reference is never traversed and every leg below would pass vacuously.
		BaseRecord run = newRun(u, wf);
		assertNotNull("Failed to create a run for the cycle probe", run);
		assertTrue("The workflow must reference the run, or the cycle is not exercised",
			PbGraphUtil.persistLastRun(u, wf, run));

		/// THE RATIFIED ASSERTION: plan construction terminates. This is what ratification 1 asked for and
		/// what phase 2b did not assert - QueryPlan.checkRecursion only catches an immediate parent/child
		/// match, pathSet keys on a planPath() that grows a unique string per level, and maximumDepth = 500
		/// only LOGS. Each leg runs on another thread under a hard time bound, because a case that proves
		/// non-termination by hanging the build is not a usable test.
		final BaseRecord[] wfFull = new BaseRecord[1];
		withinSeconds(120, "planMost(true) on olio.pb.workflow with lastRun populated", () -> {
			wfFull[0] = fullPlanRead(u, OlioModelNames.MODEL_PB_WORKFLOW, wf.get(FieldNames.FIELD_OBJECT_ID), orgId, null);
			return Boolean.TRUE;
		});
		final BaseRecord[] runFull = new BaseRecord[1];
		withinSeconds(120, "planMost(true) on olio.pb.run", () -> {
			runFull[0] = fullPlanRead(u, OlioModelNames.MODEL_PB_RUN, run.get(FieldNames.FIELD_OBJECT_ID), orgId, null);
			return Boolean.TRUE;
		});
		final BaseRecord[] wfFiltered = new BaseRecord[1];
		withinSeconds(120, "planMost(true, FULL_PLAN_FILTER) on olio.pb.workflow", () -> {
			wfFiltered[0] = fullPlanRead(u, OlioModelNames.MODEL_PB_WORKFLOW, wf.get(FieldNames.FIELD_OBJECT_ID),
				orgId, new ArrayList<>(PbGraphUtil.FULL_PLAN_FILTER));
			return Boolean.TRUE;
		});

		/// THE POSITIVE CONTROL: the explicit projection every PB2 utility actually uses does return the
		/// record. Without this leg, "planMost returns null" below could just mean the row is missing.
		BaseRecord explicit = PbGraphUtil.findWorkflow(u, bk);
		assertNotNull("The explicit projection must return the workflow - otherwise the planMost legs below"
			+ " prove nothing about planMost", explicit);
		assertEquals("...and it must be the same workflow", (String) wf.get(FieldNames.FIELD_OBJECT_ID),
			(String) explicit.get(FieldNames.FIELD_OBJECT_ID));

		logger.info("CASE 1 - planMost(true) over the workflow <-> run cycle TERMINATES."
			+ " Records returned: workflow=" + (wfFull[0] != null) + " run=" + (runFull[0] != null)
			+ " workflow+filter=" + (wfFiltered[0] != null) + "; explicit projection=" + true);

		/// CHARACTERIZATION, measured 2026-08-16 - deliberately pinned rather than worked around.
		/// planMost terminates but the read returns NOTHING, and the reason is not the cycle: the plan
		/// lists the foreign 'book' field while the generated SELECT does not emit that column, so the
		/// reader throws
		///   ReaderException: PSQLException: The column name book was not found in this ResultSet
		/// and AccessPoint.find reports "No results". Consequence to carry into phase 4: the generic
		/// GET /rest/model/{type}/{objectId}/full route would return nothing for these models. That is a
		/// safer failure than unbounded recursion, and it is why every PB2 utility uses an explicit
		/// setRequest. If a future change makes planMost work here, this assertion fails and the
		/// disposition gets revisited on purpose.
		assertNull("planMost(true) on olio.pb.workflow is expected to return null today (the plan names the"
			+ " foreign 'book' column, the SELECT omits it, the reader throws). If this now returns a record,"
			+ " the platform was fixed - update this characterization deliberately.", wfFull[0]);
		assertNull("Same for olio.pb.run", runFull[0]);
	}

	/** A {@code planMost(true)} read, optionally filtered, of one record by objectId. */
	private BaseRecord fullPlanRead(BaseRecord u, String model, String objectId, long orgId, List<String> filter) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		if(filter != null) {
			q.planMost(true, filter);
		}
		else {
			q.planMost(true);
		}
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(u, q);
	}

	private BaseRecord newRun(BaseRecord u, BaseRecord wf) {
		return PbGraphUtil.startRun(u, wf, PbBookUtil.workflowGroupPath(SLUG), new ArrayList<>());
	}

	// ───────── 2: a cycle is refused BEFORE the binding is written ─────────

	/**
	 * {@code HierarchyValidator.checkHierarchy} only covers {@code parentId} chains, so the DAG needs its
	 * own guard. The check runs before the write, which this case proves by counting the bindings after
	 * the refusal: a check that ran afterwards would leave the cyclic edge in the database.
	 */
	@Test
	public void case02_aCycleIsRefusedAndNothingIsPersisted() {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		String grp = PbBookUtil.workflowGroupPath(SLUG);

		BaseRecord a = node(u, wf, "cyc-a", PbNodeTypeEnumType.SCENE_PROMPT, 0);
		BaseRecord b = node(u, wf, "cyc-b", PbNodeTypeEnumType.SCENE, 1);

		/// a -> b is fine
		assertNotNull("A forward edge must be accepted",
			PbGraphUtil.addBinding(u, wf, b, "sceneText", 0, a, null, grp));

		int before = PbGraphUtil.listBindings(u, a).size();

		/// b -> a closes the cycle
		try {
			PbGraphUtil.addBinding(u, wf, a, "sceneText", 0, b, null, grp);
			fail("A binding that closes a cycle must be refused");
		}
		catch(PictureBookException e) {
			assertEquals("A cycle is a client error, not a server error", 400, e.getStatus());
			assertTrue("The message must name the cycle: " + e.getMessage(), e.getMessage().contains("cycle"));
		}
		assertEquals("The refused binding must NOT have been persisted - the check has to run before the write",
			before, PbGraphUtil.listBindings(u, a).size());

		/// A self-edge is a cycle of length one and goes through the same walk
		BaseRecord c = node(u, wf, "cyc-c", PbNodeTypeEnumType.PORTRAIT, 2);
		try {
			PbGraphUtil.addBinding(u, wf, c, "portrait0", 0, c, null, grp);
			fail("A self-edge must be refused");
		}
		catch(PictureBookException e) {
			assertEquals(400, e.getStatus());
		}
		assertEquals("A refused self-edge must persist nothing", 0, PbGraphUtil.listBindings(u, c).size());
	}

	// ───────── 3: propagation marks the expected set and only that set ─────────

	/**
	 * Superseding an artifact must mark exactly the downstream set STALE and leave an unrelated branch
	 * alone. Both halves matter: a propagation that marks everything is as wrong as one that marks
	 * nothing, and only the second half distinguishes them.
	 * <p>
	 * The unrelated branch is set to {@code DONE} first, so "still DONE" is a measurement rather than the
	 * default.
	 */
	@Test
	public void case03_propagationMarksTheDownstreamSetAndLeavesAnUnrelatedBranchAlone() {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		String grp = PbBookUtil.workflowGroupPath(SLUG);
		String artGrp = PbBookUtil.artifactGroupPath(SLUG);

		/// root -> mid -> leaf, plus an unrelated other
		BaseRecord root = node(u, wf, "prop-root", PbNodeTypeEnumType.SOURCE_TEXT, 0);
		BaseRecord mid = node(u, wf, "prop-mid", PbNodeTypeEnumType.SCENE_EXTRACT, 1);
		BaseRecord leaf = node(u, wf, "prop-leaf", PbNodeTypeEnumType.SCENE, 2);
		BaseRecord other = node(u, wf, "prop-other", PbNodeTypeEnumType.LANDSCAPE, 3);

		PbGraphUtil.addBinding(u, wf, mid, "sourceText", 0, root, null, grp);
		PbGraphUtil.addBinding(u, wf, leaf, "sceneText", 0, mid, null, grp);

		assertTrue(PbGraphUtil.persistStatus(u, mid, PbNodeStatusEnumType.DONE));
		assertTrue(PbGraphUtil.persistStatus(u, leaf, PbNodeStatusEnumType.DONE));
		assertTrue(PbGraphUtil.persistStatus(u, other, PbNodeStatusEnumType.DONE));

		BaseRecord artifact = PbArtifactUtil.persistArtifact(u, root, "sourceText", PbArtifactTypeEnumType.TEXT,
			artGrp, null, "revision one", null, null);
		assertNotNull("Failed to persist the root artifact", artifact);

		List<BaseRecord> marked = PbGraphUtil.markStaleDownstream(u, wf, root);
		assertEquals("Exactly the two downstream nodes must be marked, got " + names(marked), 2, marked.size());
		assertTrue("mid must be marked", containsId(marked, mid));
		assertTrue("leaf must be marked - propagation has to recurse, not stop at depth 1", containsId(marked, leaf));
		assertFalse("the unrelated branch must NOT be marked", containsId(marked, other));

		assertEquals("mid must be STALE in the database", PbNodeStatusEnumType.STALE, statusOf(u, mid));
		assertEquals("leaf must be STALE in the database", PbNodeStatusEnumType.STALE, statusOf(u, leaf));
		assertEquals("the unrelated branch must still be DONE - it was set to DONE above, so this is measured",
			PbNodeStatusEnumType.DONE, statusOf(u, other));
		assertEquals("the changed node itself is not marked - its status belongs to whatever produced the revision",
			PbNodeStatusEnumType.PENDING, statusOf(u, root));
	}

	// ───────── 4: a pinned node is MARKED stale but refused without force ─────────

	/**
	 * §2.2 splits {@code pinned} from status deliberately: propagation still marks a pinned node stale,
	 * because knowing an approved output is now inconsistent is worth having, while the executor refuses
	 * to re-run it without {@code force}. Both halves are asserted, because implementing only the second
	 * one (skip pinned nodes in propagation too) is the obvious wrong reading.
	 */
	@Test
	public void case04_aPinnedNodeIsMarkedStaleButNotRunnableWithoutForce() {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		String grp = PbBookUtil.workflowGroupPath(SLUG);

		BaseRecord src = node(u, wf, "pin-src", PbNodeTypeEnumType.SCENE_PROMPT, 0);
		BaseRecord pinned = node(u, wf, "pin-target", PbNodeTypeEnumType.PORTRAIT, 1);
		PbGraphUtil.addBinding(u, wf, pinned, "portrait0", 0, src, null, grp);
		assertTrue(PbGraphUtil.setPinned(u, pinned, true));
		assertTrue(PbGraphUtil.persistStatus(u, pinned, PbNodeStatusEnumType.DONE));
		/// The producer must be DONE. nextRunnable blocks a node whose upstream still has work to do, so
		/// leaving src PENDING would make the pinned node unrunnable for a reason unrelated to the pin -
		/// and the force leg below could never pass.
		assertTrue(PbGraphUtil.persistStatus(u, src, PbNodeStatusEnumType.DONE));

		List<BaseRecord> marked = PbGraphUtil.markStaleDownstream(u, wf, src);
		assertTrue("A pinned node must still be MARKED stale", containsId(marked, pinned));
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, pinned));

		List<BaseRecord> runnable = PbGraphUtil.nextRunnable(u, wf, false);
		assertFalse("A pinned node must not be runnable without force", containsId(runnable, pinned));
		List<BaseRecord> forced = PbGraphUtil.nextRunnable(u, wf, true);
		assertTrue("A pinned node must be runnable WITH force", containsId(forced, pinned));

		assertTrue(PbGraphUtil.setPinned(u, pinned, false));
	}

	// ───────── 5: inputHash reacts to configOverride and not to unrelated work ─────────

	/**
	 * The authoritative staleness value. Two assertions, and the negative one is the load-bearing half: a
	 * hash that changes when anything at all happens would mark the whole book stale on every run.
     */
	@Test
	public void case05_inputHashChangesWithConfigOverrideAndNotWithUnrelatedWork() {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);

		BaseRecord n = node(u, wf, "hash-n", PbNodeTypeEnumType.COMPOSITE, 0);
		BaseRecord other = node(u, wf, "hash-other", PbNodeTypeEnumType.LANDSCAPE, 1);

		String before = PbGraphUtil.computeInputHash(u, n, bk);
		assertNotNull(before);

		/// Unrelated work: another node runs and produces an artifact.
		PbArtifactUtil.persistArtifact(u, other, "landscape", PbArtifactTypeEnumType.TEXT,
			PbBookUtil.artifactGroupPath(SLUG), null, "unrelated", null, null);
		assertTrue(PbGraphUtil.persistStatus(u, other, PbNodeStatusEnumType.DONE));

		BaseRecord reread = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertEquals("An unrelated node running must NOT change this node's inputHash",
			before, PbGraphUtil.computeInputHash(u, reread, bk));

		/// Now change this node's config override.
		assertTrue(PbGraphUtil.persistConfigOverride(u, reread, overrideJson("flux2Steps", Integer.valueOf(7))));
		BaseRecord withOverride = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotEquals("Changing configOverride must change inputHash",
			before, PbGraphUtil.computeInputHash(u, withOverride, bk));

		/// And a prompt change, since promptText is folded in separately from the config.
		String withCfg = PbGraphUtil.computeInputHash(u, withOverride, bk);
		assertTrue(PbGraphUtil.persistPromptText(u, withOverride, "a new resolved prompt"));
		BaseRecord withPrompt = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotEquals("Changing promptText must change inputHash", withCfg,
			PbGraphUtil.computeInputHash(u, withPrompt, bk));
	}

	// ───────── 6: the sparse override really is sparse ─────────

	/**
	 * The dead-resource guard from §9. {@code configOverride} must contain ONLY what was explicitly set;
	 * a full {@code newInstance} graph would make "override" indistinguishable from "default" and would
	 * silently pin every one of the 30 defaulted fields.
	 */
	@Test
	public void case06_configOverrideSerializesOnlyTheSetFields() {
		BaseRecord cfg = null;
		try {
			cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			cfg.set("flux2Steps", Integer.valueOf(9));
			cfg.set("style", "pb2cStyle");
		}
		catch(Exception e) {
			fail("Failed to build a config: " + e.getMessage());
		}
		String sparse = PbConfigUtil.sparseOverride(cfg, Arrays.asList("flux2Steps", "style"));
		assertNotNull("Expected a sparse override", sparse);
		assertTrue("The set fields must be present: " + sparse, sparse.contains("flux2Steps") && sparse.contains("style"));
		for(String dead : new String[] {"flux2Cfg", "kontextModel", "mannequinSteps", "mannequinCreativity",
				"mannequinUseBaseImage", "refinerModel", "negativePrompt"}) {
			assertFalse("An unset field must NOT appear in the override: " + dead + " in " + sparse,
				sparse.contains(dead));
		}
		assertFalse("An unknown field name must be dropped, not serialized",
			PbConfigUtil.sparseOverride(cfg, Arrays.asList("notAFieldOnThisModel")) != null);

		BaseRecord parsed = PbConfigUtil.parseOverride(sparse);
		assertNotNull("The override must round trip", parsed);
		assertEquals("olio.sd.config", parsed.getSchema());
		assertEquals(Integer.valueOf(9), parsed.get("flux2Steps"));
		assertEquals("pb2cStyle", parsed.get("style"));
	}

	// ───────── 7: precedence, and the effective config the hash sees ─────────

	/**
	 * §2.4's chain, measured end to end rather than asserted from the code. The FLUX.2 leg matters most:
	 * those six fields carry no schema default precisely so the resource can govern, and a merge that
	 * left them null would produce a {@code configHash} blind to a resource edit.
	 */
	@Test
	public void case07_effectiveConfigFollowsThePrecedenceChain() {
		BaseRecord book = null;
		BaseRecord node = null;
		try {
			book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
			BaseRecord bookCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			bookCfg.set("style", "bookStyle");
			bookCfg.set("flux2Steps", Integer.valueOf(11));
			book.set(OlioFieldNames.FIELD_PB_SD_CONFIG, bookCfg);

			node = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE);
			BaseRecord nodeCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			nodeCfg.set("flux2Steps", Integer.valueOf(3));
			node.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE,
				PbConfigUtil.sparseOverride(nodeCfg, Arrays.asList("flux2Steps")));
		}
		catch(Exception e) {
			fail("Failed to build the precedence fixture: " + e.getMessage());
		}

		BaseRecord effective = PbConfigUtil.resolveEffectiveConfig(book, node, false);
		assertEquals("The node override must beat the book tier", Integer.valueOf(3), effective.get("flux2Steps"));
		assertEquals("The book tier must survive where the node says nothing", "bookStyle", effective.get("style"));
		assertNotNull("The FLUX.2 resource tier must fill what neither tier set - otherwise configHash is"
			+ " blind to a flux2Defaults.json edit", effective.get("flux2Cfg"));
		assertNotNull(effective.get("flux2ReferenceSize"));
		assertEquals("A schema default must survive where nothing overrides it", Integer.valueOf(20),
			effective.get("steps"));

		/// No book tier at all: the resource defaults still have to land.
		BaseRecord bare = PbConfigUtil.resolveEffectiveConfig(null, null, false);
		assertNotNull("A book-less resolve must still fill the FLUX.2 tier", bare.get("flux2Steps"));

		/// The excluded fields are a policy decision and must be visible as one.
		String h1 = PbConfigUtil.configHash(effective);
		try {
			effective.set("imagePath", "~/Data/Somewhere/Else");
		}
		catch(Exception e) {
			fail(e.getMessage());
		}
		assertEquals("imagePath is an output destination and is deliberately excluded from configHash",
			h1, PbConfigUtil.configHash(effective));
		try {
			effective.set("seed", Integer.valueOf(4242));
		}
		catch(Exception e) {
			fail(e.getMessage());
		}
		assertNotEquals("seed IS an input and must change configHash", h1, PbConfigUtil.configHash(effective));
	}

	// ───────── 8: the hash primitives, pinned ─────────

	/**
	 * Golden vectors plus the Turkish-locale case, per Appendix D.
	 * <p>
	 * The locale leg is not hypothetical: {@code String.format}, {@code toLowerCase} and
	 * {@code toUpperCase} are all locale-sensitive, and a canonical form built with any of them would
	 * produce a different digest on a {@code tr} host - marking every node in every book stale.
	 */
	@Test
	public void case08_hashPrimitivesArePinnedAndLocaleIndependent() {
		assertEquals("SHA-256, not CryptoUtil's mutable-static SHA-512", SHA256_OF_ABC,
			PbConfigUtil.sha256Hex("abc"));
		assertEquals("The canonical string must be encoded as explicit UTF-8", SHA256_OF_TURKISH_I,
			PbConfigUtil.sha256Hex(TURKISH_I_PAIR));

		assertEquals("A null must render as '-', never as \"\" and never as \"null\"",
			PbConfigUtil.NULL_TOKEN, PbConfigUtil.token(null));
		assertNotEquals("The literal text 'null' must not collide with a null",
			PbConfigUtil.token(null), PbConfigUtil.token("null"));
		assertNotEquals("An empty string must not collide with a null",
			PbConfigUtil.token(null), PbConfigUtil.token(""));

		assertEquals("Doubles go through BigDecimal.stripTrailingZeros().toPlainString()", "2",
			PbConfigUtil.doubleToken(2.0d));
		assertEquals("2.5", PbConfigUtil.doubleToken(2.5d));
		assertEquals("No exponent notation may appear", "0.0000001", PbConfigUtil.doubleToken(0.0000001d));

		Locale original = Locale.getDefault();
		try {
			BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			cfg.set("style", "ISTANBUL");
			cfg.set("flux2Cfg", Double.valueOf(2.0d));
			String rootCanonical = PbConfigUtil.canonicalConfig(cfg);
			String rootHash = PbConfigUtil.configHash(cfg);

			Locale.setDefault(Locale.forLanguageTag("tr"));
			assertEquals("The canonical config form must be byte-identical under a Turkish default locale",
				rootCanonical, PbConfigUtil.canonicalConfig(cfg));
			assertEquals("configHash must be identical under a Turkish default locale",
				rootHash, PbConfigUtil.configHash(cfg));
			assertEquals("The double token must be locale-independent", "2.5",
				PbConfigUtil.doubleToken(2.5d));
		}
		catch(Exception e) {
			fail("Locale case failed: " + e.getMessage());
		}
		finally {
			Locale.setDefault(original);
		}
	}

	/**
	 * The {@code computeInputHash} golden vector.
	 * <p>
	 * The node's override sets all six FLUX.2 knobs, so {@code flux2Defaults.json} cannot influence the
	 * result and this vector is stable against a tuning edit to that resource. It <b>will</b> change if a
	 * schema default in {@code configModel.json} changes, if the canonical form changes, or if
	 * {@link PbGraphUtil#PB_PIPELINE_VERSION} is bumped - each of which is a change that should require
	 * someone to look at this test and agree.
	 */
	@Test
	public void case09_computeInputHashGoldenVector() {
		BaseRecord u = user();
		Locale original = Locale.getDefault();
		try {
			BaseRecord node = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE);
			node.set(FieldNames.FIELD_NAME, "Node golden");
			node.set(OlioFieldNames.FIELD_PB_HANDLE, "golden");
			node.set(OlioFieldNames.FIELD_PB_NODE_TYPE, PbNodeTypeEnumType.COMPOSITE.toString());
			node.set(OlioFieldNames.FIELD_PB_PROMPT_TEXT, "a fixed prompt");

			BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			cfg.set("flux2Cfg", Double.valueOf(2.0d));
			cfg.set("flux2Steps", Integer.valueOf(4));
			cfg.set("flux2Width", Integer.valueOf(1024));
			cfg.set("flux2Height", Integer.valueOf(768));
			cfg.set("flux2ReferenceSize", Integer.valueOf(1024));
			cfg.set("flux2IncludeLandscapeRef", Boolean.TRUE);
			node.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, PbConfigUtil.sparseOverride(cfg,
				Arrays.asList("flux2Cfg", "flux2Steps", "flux2Width", "flux2Height", "flux2ReferenceSize",
					"flux2IncludeLandscapeRef")));

			String canonical = PbGraphUtil.canonicalInput(u, node, null);
			String hash = PbConfigUtil.sha256Hex(canonical);
			logger.info("GOLDEN inputHash canonical form:\n" + canonical);
			logger.info("GOLDEN inputHash = " + hash);

			/// The locale leg hashes the ALREADY-BUILT canonical string rather than re-deriving it.
			/// Re-deriving would call RecordFactory.newInstance under the Turkish locale, and RecordFactory
			/// parses a field's declared type with a locale-sensitive toUpperCase() - "string" becomes
			/// STR(dotted-I)NG, the model fails to build, and the broken result is CACHED in
			/// looseBaseModels for the rest of the JVM, breaking unrelated tests. Measured on am7db
			/// 2026-08-15; reported as a platform finding and deliberately not fixed here.
			Locale.setDefault(Locale.forLanguageTag("tr"));
			assertEquals("The inputHash digest must not depend on the default locale", hash,
				PbConfigUtil.sha256Hex(canonical));
			Locale.setDefault(original);

			assertEquals("The inputHash golden vector changed. That is legitimate ONLY if a schema default,"
				+ " the canonical form, or PB_PIPELINE_VERSION changed deliberately - a bump marks every node"
				+ " in every book stale. Canonical form logged above.", GOLDEN_INPUT_HASH, hash);
		}
		catch(Exception e) {
			fail("Golden vector case failed: " + e.getMessage());
		}
		finally {
			Locale.setDefault(original);
		}
	}

	/** Filled from the first run; see {@link #case09_computeInputHashGoldenVector()}. Updated after S6: book.sdConfig/compositeSdConfig promoted to foreign FK, which changed the effective-config canonical form. */
	private static final String GOLDEN_INPUT_HASH =
		"879405447e367aa8235c053aee863cd856ea93ea46e5261b0c6b68dcb33cdef4";

	// ───────── 10: recomputeStatus computes, it does not write ─────────

	/**
	 * Ratification 2. The whole point of splitting {@code recomputeStatus} is that a read path must not
	 * write, so the test that matters is that the stored value is <b>unchanged</b> after a recompute that
	 * returns something different.
	 */
	@Test
	public void case10_recomputeStatusComputesAndDoesNotWrite() {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);

		BaseRecord n = node(u, wf, "recompute-n", PbNodeTypeEnumType.SCENE, 0);
		/// A stored inputHash that cannot match, so the recompute must derive STALE.
		assertTrue(PbGraphUtil.persistInputHash(u, n, "0000000000000000000000000000000000000000000000000000000000000000"));
		assertTrue(PbGraphUtil.persistStatus(u, n, PbNodeStatusEnumType.DONE));

		BaseRecord reread = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertEquals("Precondition: the stored status is DONE", PbNodeStatusEnumType.DONE,
			reread.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));

		PbNodeStatusEnumType derived = PbGraphUtil.recomputeStatus(u, reread, bk);
		assertEquals("The recompute must derive STALE from the hash mismatch", PbNodeStatusEnumType.STALE, derived);
		assertEquals("recomputeStatus must NOT have written - the status is a repairable cache and only an"
			+ " explicitly authorized write may persist it", PbNodeStatusEnumType.DONE, statusOf(u, n));

		/// The explicit write is the one that changes the database.
		assertTrue(PbGraphUtil.persistStatus(u, reread, derived));
		assertEquals(PbNodeStatusEnumType.STALE, statusOf(u, n));

		/// The null-inputHash carve-out: a brand-new node is PENDING/READY, never STALE.
		BaseRecord fresh = node(u, wf, "recompute-fresh", PbNodeTypeEnumType.SCENE, 1);
		PbNodeStatusEnumType freshStatus = PbGraphUtil.recomputeStatus(u, fresh, bk);
		assertNotEquals("A node with a null inputHash must never be STALE", PbNodeStatusEnumType.STALE, freshStatus);
		assertTrue("Expected READY or PENDING, got " + freshStatus,
			freshStatus == PbNodeStatusEnumType.READY || freshStatus == PbNodeStatusEnumType.PENDING);
	}

	// ───────── 11: the watched field set does what it claims ─────────

	/**
	 * §2.3 asks for exactly this test, because the watched set is a policy decision rather than a
	 * derivation: edit a <b>watched</b> field and a <b>non-watched</b> field and assert the different
	 * outcomes. Without the second half, a set that watched every field would pass.
	 */
	@Test
	public void case11_refHashTracksWatchedFieldsAndIgnoresUnwatchedOnes() {
		BaseRecord u = user();
		long orgId = orgOf(u);
		String path = "~/PbGraphRefs";
		assertNotNull(ioContext.getPathUtil().makePath(u, org.cote.accountmanager.schema.ModelNames.MODEL_GROUP,
			path, org.cote.accountmanager.schema.type.GroupEnumType.DATA.toString(), orgId));

		/// data.data is a watched model: name/contentType/size are watched, description is not.
		String tag = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord data = getCreateData(u, "pbref-" + tag, path, "watched-field body");
		assertNotNull("Failed to create the reference record", data);
		String oid = data.get(FieldNames.FIELD_OBJECT_ID);

		String h0 = PbWatchedFields.computeRefHash(u, org.cote.accountmanager.schema.ModelNames.MODEL_DATA, oid);
		assertNotNull("Expected a refHash for a watched model", h0);
		assertEquals("The hash must be stable across calls", h0,
			PbWatchedFields.computeRefHash(u, org.cote.accountmanager.schema.ModelNames.MODEL_DATA, oid));

		/// UNWATCHED: description is not in the declared set.
		BaseRecord descPatch = null;
		try {
			/// ONLY these four fields. newInstance(model) would materialise every field of data.data
			/// and the writer persists everything present on the record, so a full instance blanks
			/// contentType and size - which is exactly the defect this case then mis-reports as a
			/// watched-field change. Measured on am7db 2026-08-15.
			descPatch = RecordFactory.newInstance(org.cote.accountmanager.schema.ModelNames.MODEL_DATA,
				new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
					FieldNames.FIELD_DESCRIPTION});
			descPatch.set(FieldNames.FIELD_ID, data.get(FieldNames.FIELD_ID));
			descPatch.set(FieldNames.FIELD_OBJECT_ID, oid);
			descPatch.set(FieldNames.FIELD_NAME, data.get(FieldNames.FIELD_NAME));
			descPatch.set(FieldNames.FIELD_DESCRIPTION, "changed " + tag);
		}
		catch(Exception e) {
			fail(e.getMessage());
		}
		assertNotNull("Failed to patch an unwatched field", ioContext.getAccessPoint().update(u, descPatch));
		assertEquals("Editing a field OUTSIDE the declared watched set must not change refHash - the set is a"
			+ " policy decision and this is the half that proves it is actually narrow", h0,
			PbWatchedFields.computeRefHash(u, org.cote.accountmanager.schema.ModelNames.MODEL_DATA, oid));

		/// WATCHED: contentType is in the declared set.
		BaseRecord typePatch = null;
		try {
			/// ONLY these four fields. newInstance(model) would materialise every field of data.data
			/// and the writer persists everything present on the record, so a full instance blanks
			/// contentType and size - which is exactly the defect this case then mis-reports as a
			/// watched-field change. Measured on am7db 2026-08-15.
			typePatch = RecordFactory.newInstance(org.cote.accountmanager.schema.ModelNames.MODEL_DATA,
				new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
					FieldNames.FIELD_CONTENT_TYPE});
			typePatch.set(FieldNames.FIELD_ID, data.get(FieldNames.FIELD_ID));
			typePatch.set(FieldNames.FIELD_OBJECT_ID, oid);
			typePatch.set(FieldNames.FIELD_NAME, data.get(FieldNames.FIELD_NAME));
			typePatch.set(FieldNames.FIELD_CONTENT_TYPE, "application/pb2c-" + tag);
		}
		catch(Exception e) {
			fail(e.getMessage());
		}
		assertNotNull("Failed to patch a watched field", ioContext.getAccessPoint().update(u, typePatch));
		assertNotEquals("Editing a WATCHED field must change refHash", h0,
			PbWatchedFields.computeRefHash(u, org.cote.accountmanager.schema.ModelNames.MODEL_DATA, oid));

		/// An unwatched MODEL answers null - "cannot determine", never "unchanged".
		assertNull("An unwatched model must answer null rather than a hash that can never change",
			PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_WORLD, oid));
	}

	/**
	 * The binding-level consequence of case 11: a node whose only input is an edited external record must
	 * come back as drifted. This is the mechanism that makes "edit a character, the portrait goes stale"
	 * possible at all - artifact chaining structurally cannot see a record edit.
	 */
	@Test
	public void case12_anEditedExternalRecordDriftsItsBinding() {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		long orgId = orgOf(u);
		String path = "~/PbGraphRefs";
		assertNotNull(ioContext.getPathUtil().makePath(u, org.cote.accountmanager.schema.ModelNames.MODEL_GROUP,
			path, org.cote.accountmanager.schema.type.GroupEnumType.DATA.toString(), orgId));

		String tag = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord data = getCreateData(u, "pbdrift-" + tag, path, "drift body");
		assertNotNull(data);

		BaseRecord n = node(u, wf, "drift-n", PbNodeTypeEnumType.CHARACTER_DESCRIPTION, 0);
		BaseRecord binding = PbGraphUtil.addRecordBinding(u, wf, n, "chapterSource", 0,
			org.cote.accountmanager.schema.ModelNames.MODEL_DATA, data.get(FieldNames.FIELD_OBJECT_ID),
			PbBookUtil.workflowGroupPath(SLUG));
		assertNotNull("Failed to bind an external record", binding);
		assertNotNull("The bind must capture a refHash at bind time - without it nothing can drift",
			binding.get(OlioFieldNames.FIELD_PB_REF_HASH));

		assertTrue("Nothing has changed, so nothing may be reported as drifted",
			PbGraphUtil.driftedRefBindings(u, n).isEmpty());

		BaseRecord patch = null;
		try {
			patch = RecordFactory.newInstance(org.cote.accountmanager.schema.ModelNames.MODEL_DATA,
				new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
					FieldNames.FIELD_CONTENT_TYPE});
			patch.set(FieldNames.FIELD_ID, data.get(FieldNames.FIELD_ID));
			patch.set(FieldNames.FIELD_OBJECT_ID, data.get(FieldNames.FIELD_OBJECT_ID));
			patch.set(FieldNames.FIELD_NAME, data.get(FieldNames.FIELD_NAME));
			patch.set(FieldNames.FIELD_CONTENT_TYPE, "application/pb2c-drift-" + tag);
		}
		catch(Exception e) {
			fail(e.getMessage());
		}
		assertNotNull(ioContext.getAccessPoint().update(u, patch));

		List<BaseRecord> drifted = PbGraphUtil.driftedRefBindings(u, n);
		assertEquals("The binding must be reported drifted after a watched-field edit", 1, drifted.size());

		assertEquals("Persisting the fresh hash is a separate, explicit write", 1,
			PbGraphUtil.persistRefHashes(u, drifted));
		assertTrue("After persisting, nothing drifts any more", PbGraphUtil.driftedRefBindings(u, n).isEmpty());
	}

	// ───────── 13: artifact versioning, selection and sanitization ─────────

	/**
	 * The artifact chain. The name-carries-the-revision requirement is asserted directly, because it is
	 * the trap: without the revision in the name, the second revision collides on the unique
	 * {@code (name, groupId, organizationId)} index - which is ratification 8's urn-collision guard.
	 */
	@Test
	public void case13_artifactRevisionsSupersedeAndExactlyOneIsSelected() {
		BaseRecord u = user();
		BaseRecord wf = workflow(u, book(u));
		BaseRecord n = node(u, wf, "artifact-n", PbNodeTypeEnumType.PORTRAIT, 0);
		String artGrp = PbBookUtil.artifactGroupPath(SLUG);

		BaseRecord r1 = PbArtifactUtil.persistArtifact(u, n, "portrait0", PbArtifactTypeEnumType.TEXT,
			artGrp, null, "rev one", null, null);
		assertNotNull(r1);
		assertEquals(Integer.valueOf(1), r1.get(OlioFieldNames.FIELD_PB_REVISION));
		assertTrue("The derived name must carry the revision, or revision 2 collides on the unique name index",
			((String) r1.get(FieldNames.FIELD_NAME)).endsWith(" r1"));
		assertEquals(Boolean.TRUE, r1.get(OlioFieldNames.FIELD_PB_SELECTED));

		BaseRecord r2 = PbArtifactUtil.persistArtifact(u, n, "portrait0", PbArtifactTypeEnumType.TEXT,
			artGrp, null, "rev two", null, null);
		assertNotNull("A second revision must be creatable - this is what a revision-free name would break", r2);
		assertEquals(Integer.valueOf(2), r2.get(OlioFieldNames.FIELD_PB_REVISION));
		BaseRecord supersedes = r2.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
		assertNotNull("Revision 2 must supersede revision 1, so the old image stays viewable", supersedes);
		/// Typed locals on purpose: assertEquals(x.get(..), y.get(..)) infers Object[] from the generic
		/// get() and binds the deprecated assertEquals(Object[], Object[]) overload, which then throws
		/// ClassCastException on a Long.
		Long r1Id = r1.get(FieldNames.FIELD_ID);
		Long supersedesId = supersedes.get(FieldNames.FIELD_ID);
		assertEquals(r1Id, supersedesId);

		List<BaseRecord> chain = PbArtifactUtil.listChain(u, n, "portrait0");
		assertEquals("Both revisions must remain in the chain - superseding is not deleting", 2, chain.size());
		int selected = 0;
		for(BaseRecord a : chain) {
			Boolean s = a.get(OlioFieldNames.FIELD_PB_SELECTED);
			if(s != null && s.booleanValue()) {
				selected++;
			}
		}
		assertEquals("Exactly one revision may be selected. This invariant is NOT expressible as a unique"
			+ " index (a boolean is never NULL, so a unique index over 'selected' would forbid a second"
			+ " superseded row), so the post-write re-read in setSelected is its only enforcement", 1, selected);
		Long r2Id = r2.get(FieldNames.FIELD_ID);
		Long selectedId = PbArtifactUtil.findSelected(u, n, "portrait0").get(FieldNames.FIELD_ID);
		assertEquals("The newest revision must be the selected one", r2Id, selectedId);

		/// Re-selecting an older revision must move the flag, not add a second one.
		PbArtifactUtil.setSelected(u, PbArtifactUtil.readArtifact(u, r1.get(FieldNames.FIELD_OBJECT_ID), orgOf(u)));
		Long reselectedId = PbArtifactUtil.findSelected(u, n, "portrait0").get(FieldNames.FIELD_ID);
		assertEquals(r1Id, reselectedId);
		int stillOne = 0;
		for(BaseRecord a : PbArtifactUtil.listChain(u, n, "portrait0")) {
			Boolean s = a.get(OlioFieldNames.FIELD_PB_SELECTED);
			if(s != null && s.booleanValue()) {
				stillOne++;
			}
		}
		assertEquals("Re-selecting must move the flag, not add one", 1, stillOne);
	}

	/**
	 * {@code generatorRequest} sanitization. Structural rather than textual, so an escaped or re-ordered
	 * payload cannot slip through - and an unparseable request is persisted as <b>null</b> rather than
	 * unsanitized, because returning the original would store the base64 this exists to remove.
	 */
	@Test
	public void case14_generatorRequestIsSanitizedStructurally() {
		String raw = "{\"prompt\":\"a scene\",\"session_id\":\"swarm-abc\",\"initImage\":\"iVBORw0KGgoAAAA\","
			+ "\"promptImages\":[\"iVBORw0KGgoAAAA\",\"iVBORw0KGgoBBBB\"],"
			+ "\"nested\":{\"session_id\":\"swarm-def\",\"initImage\":\"iVBORw0KGgoCCCC\",\"steps\":4}}";
		assertFalse("Precondition: the raw request is not sanitized", PbArtifactUtil.isSanitized(raw));

		String clean = PbArtifactUtil.sanitizeGeneratorRequest(raw, Arrays.asList("oid-1", "oid-2"));
		assertNotNull(clean);
		assertTrue("Sanitization must be recognised as complete", PbArtifactUtil.isSanitized(clean));
		assertFalse("No base64 payload may survive: " + clean, clean.contains("iVBORw0KGgo"));
		assertFalse("The Swarm session id must not be persisted: " + clean, clean.contains("swarm-abc"));
		assertFalse("Nested occurrences must be stripped too: " + clean, clean.contains("swarm-def"));
		assertTrue("The prompt must survive", clean.contains("a scene"));
		assertTrue("Nested non-payload fields must survive", clean.contains("steps"));
		assertTrue("The reference artifacts must be recorded by objectId instead",
			clean.contains(PbArtifactUtil.REFERENCE_ARTIFACTS_KEY) && clean.contains("oid-1") && clean.contains("oid-2"));

		assertNull("An unparseable request must be dropped, never persisted unsanitized",
			PbArtifactUtil.sanitizeGeneratorRequest("{not json at all", null));
		assertFalse("Unknown is not clean", PbArtifactUtil.isSanitized("{not json at all"));
	}

	// ───────── 15: the PATCH shape, including name ─────────

	/**
	 * §9's round-trip requirement. The {@code name} is the trap: the writer validates the <b>patch
	 * record</b>, not the merged result, so a patch omitting it fails validation - visibly only in the
	 * log, with the update call returning a value most callers discard.
	 */
	@Test
	public void case15_patchShapedUpdatesRoundTripAndRequireName() {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);

		BaseRecord n = node(u, wf, "patch-n", PbNodeTypeEnumType.SCENE_PROMPT, 0);
		assertTrue(PbGraphUtil.persistPromptText(u, n, "patched prompt"));
		assertEquals("patched prompt",
			PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgId).get(OlioFieldNames.FIELD_PB_PROMPT_TEXT));

		BaseRecord binding = PbGraphUtil.addBinding(u, wf, n, "sourceText", 0, null, null,
			PbBookUtil.workflowGroupPath(SLUG));
		assertTrue(PbGraphUtil.setBindingRequired(u, binding, true));
		assertEquals(Boolean.TRUE, PbGraphUtil.readBinding(u, binding.get(FieldNames.FIELD_OBJECT_ID), orgId)
			.get(OlioFieldNames.FIELD_PB_REQUIRED));

		BaseRecord artifact = PbArtifactUtil.persistArtifact(u, n, "prompt", PbArtifactTypeEnumType.PROMPT,
			PbBookUtil.artifactGroupPath(SLUG), null, "the prompt", null, null);
		assertTrue(PbArtifactUtil.recordImageMetrics(u, artifact, "not really an image".getBytes(), "text/plain",
			0, 0, Long.valueOf(99L)));
		BaseRecord readArtifact = PbArtifactUtil.readArtifact(u, artifact.get(FieldNames.FIELD_OBJECT_ID), orgId);
		assertEquals(Long.valueOf(99L), readArtifact.get(OlioFieldNames.FIELD_PB_SEED));
		assertNotNull(readArtifact.get(OlioFieldNames.FIELD_PB_CONTENT_HASH));

		/// A record read without its name cannot be patched, and must say so rather than silently no-op.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_NODE, FieldNames.FIELD_OBJECT_ID,
			n.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		BaseRecord nameless = ioContext.getAccessPoint().find(u, q);
		assertNotNull(nameless);
		assertNull("Precondition: the projection deliberately omits the name", nameless.get(FieldNames.FIELD_NAME));
		try {
			PbGraphUtil.persistStatus(u, nameless, PbNodeStatusEnumType.DONE);
			fail("Patching without a name must be refused rather than silently failing validation in the writer");
		}
		catch(PictureBookException e) {
			assertEquals(500, e.getStatus());
			assertTrue("The message must explain the name requirement: " + e.getMessage(),
				e.getMessage().contains("without a name"));
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private long orgOf(BaseRecord u) {
		return (long) u.get(FieldNames.FIELD_ORGANIZATION_ID);
	}

	private PbNodeStatusEnumType statusOf(BaseRecord u, BaseRecord node) {
		BaseRecord r = PbGraphUtil.readNode(u, node.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull("Node vanished: " + node.get(FieldNames.FIELD_NAME), r);
		return r.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS);
	}

	private boolean containsId(List<BaseRecord> recs, BaseRecord rec) {
		Long id = rec.get(FieldNames.FIELD_ID);
		for(BaseRecord r : recs) {
			if(id.equals(r.get(FieldNames.FIELD_ID))) {
				return true;
			}
		}
		return false;
	}

	private String names(List<BaseRecord> recs) {
		List<String> out = new ArrayList<>();
		recs.forEach(r -> out.add((String) r.get(FieldNames.FIELD_NAME)));
		return out.toString();
	}

	private String overrideJson(String field, Object value) {
		try {
			BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			cfg.set(field, value);
			return PbConfigUtil.sparseOverride(cfg, Arrays.asList(field));
		}
		catch(Exception e) {
			fail("Failed to build an override: " + e.getMessage());
			return null;
		}
	}
}
