package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 2, P2-8: Kontext Disable (design §37.3, "Disable" row). A composite node whose
 * effective config resolves to the retired Kontext pipeline is refused by
 * {@link PbRenderPath#requireEnabledCompositeMode} inside {@link PbNodeExecutor#executeComposite} - a
 * {@link PictureBookException} 409 whose message starts with
 * {@link SceneCompositeUtil#KONTEXT_RETIRED_MESSAGE} - before the model policy and before any SD contact.
 * <p>
 * Because the refusal is thrown inside {@code dispatch}, {@code executeNodeRun} has already opened the
 * {@code olio.pb.run} row and stamped it RUNNING; the catch closes it FAILED with the refusal and stamps the
 * node FAILED with {@code lastError} (the P0-8 visibility pattern) - that is what the canvas shows, and this
 * test pins it rather than hiding it. The control case proves the gate is specific: a {@code flux2} override
 * on the same fixture is NOT refused with the retirement message (it proceeds to the model policy and the
 * SD stage, where the subclass sentinel - or, on a Swarm outage, an inventory failure - ends the run).
 * <p>
 * Live {@code am7db}; the SD backend is never mocked. The {@link SDUtil} <i>subclass</i> throws a sentinel from
 * the real {@code createImage}/{@code createSceneImage} entry points so any SD contact on the refusal path is a
 * test failure, not a network call. Shared {@code pb2c-graph} fixture book, random node handles, every run
 * assertion scoped by "newer than the newest run before the call" ({@code TestPbNodeRun}'s conventions).
 * <p>
 * The legacy {@code useKontext=true}-with-no-{@code compositeMode} path cannot be built through a node's
 * sparse {@code configOverride} (schema default {@code compositeMode=flux2} wins and
 * {@link SDUtil#applyOverrides} skips nulls), so that path is exercised at the transport in
 * {@code TestChatSceneKontextDisable} (Service7) and pinned at the resolver in {@code TestKontextDeprecation}.
 * <p>
 * Lives in {@code org.cote.accountmanager.olio.picturebook} for the package-private
 * {@code executeNodeRun(user, book, workflow, node, SDUtil)} seam.
 */
public class TestPbKontextDisable extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";
	/// Generous on purpose: a timing is never a pass/fail threshold here.
	private static final long CASE_TIMEOUT_MS = 10L * 60L * 1000L;

	@Before
	public void runSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
	}

	// ─────────────────────────────── cases ───────────────────────────────

	/**
	 * {@code compositeMode=kontext} on the node's override: executeNodeRun throws the 409 whose message starts
	 * with KONTEXT_RETIRED_MESSAGE and names the scene; SD is never contacted; the node reads FAILED with the
	 * refusal as lastError; the run this call opened reads FAILED with the refusal as its error.
	 */
	@Test(timeout = CASE_TIMEOUT_MS)
	public void case01_kontextOverrideIsRefusedBeforeSdAndFailsNodeAndRun() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord node = compositeNode(u, bk, wf, "p28-c01", modeOverride(SceneCompositeUtil.MODE_KONTEXT));
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		String sceneOid = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		long newestBefore = newestRunId(u, wf);

		List<String> sdCalls = new ArrayList<>();
		SDUtil sdu = sentinelSdUtil(sdCalls);

		PictureBookException thrown = null;
		try {
			PbNodeExecutor.executeNodeRun(u, bk, wf, node, sdu);
			fail("a kontext override must be refused");
		}
		catch(PictureBookException e) {
			thrown = e;
		}
		logger.info("P2-8 case01 refusal: status=" + thrown.getStatus() + " message=" + thrown.getMessage() + " sdCalls=" + sdCalls);

		assertEquals("the refusal is a 409 (the config's state, not the request's syntax)", 409, thrown.getStatus());
		assertTrue("the refusal starts with the shared retirement message: " + thrown.getMessage(),
			String.valueOf(thrown.getMessage()).startsWith(SceneCompositeUtil.KONTEXT_RETIRED_MESSAGE));
		assertTrue("the refusal names the scene: " + thrown.getMessage(),
			thrown.getMessage().contains("(scene " + sceneOid + ")"));
		assertTrue("SD must not be contacted for a retired compositeMode: " + sdCalls, sdCalls.isEmpty());

		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertNotNull(fresh);
		assertEquals("the node is FAILED after the refusal", PbNodeStatusEnumType.FAILED.name(),
			status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String lastError = fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertTrue("lastError carries the refusal: " + lastError, lastError != null && lastError.contains("replaced by V3"));
		assertNotNull("lastRunAt is stamped on failure", fresh.get(OlioFieldNames.FIELD_PB_LAST_RUN_AT));

		BaseRecord run = latestRun(u, wf);
		assertNotNull("the call opened a run before the refusal (P0-8 visibility)", run);
		assertTrue("the run opened by this call is newer than every run before it",
			((Number) run.get(FieldNames.FIELD_ID)).longValue() > newestBefore);
		assertEquals("the run closes FAILED on the refusal", PbRunStatusEnumType.FAILED.name(),
			status(run, OlioFieldNames.FIELD_PB_RUN_STATUS));
		String runError = run.get(OlioFieldNames.FIELD_PB_ERROR);
		assertTrue("the run error carries the refusal: " + runError, runError != null && runError.contains("replaced by V3"));
		assertNotNull("completedAt is stamped on close", run.get(OlioFieldNames.FIELD_PB_COMPLETED_AT));
	}

	/** The retired check is case/whitespace-insensitive at the executor too: {@code " KONTEXT "} is refused the same way. */
	@Test(timeout = CASE_TIMEOUT_MS)
	public void case02_kontextOverrideIsRefusedCaseInsensitively() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		BaseRecord node = compositeNode(u, bk, wf, "p28-c02", modeOverride(" KONTEXT "));
		List<String> sdCalls = new ArrayList<>();

		try {
			PbNodeExecutor.executeNodeRun(u, bk, wf, node, sentinelSdUtil(sdCalls));
			fail("a ' KONTEXT ' override must be refused");
		}
		catch(PictureBookException e) {
			assertEquals(409, e.getStatus());
			assertTrue(String.valueOf(e.getMessage()).startsWith(SceneCompositeUtil.KONTEXT_RETIRED_MESSAGE));
		}
		assertTrue("SD must not be contacted: " + sdCalls, sdCalls.isEmpty());
	}

	/**
	 * Control: the same fixture with a {@code flux2} override is NOT refused with the retirement message. The
	 * gate passes and execution continues to the model policy and the SD stage; whatever ends the run there
	 * (the sentinel from the subclass, or - with the Swarm node down - an inventory/model failure) is logged
	 * and must not be the retirement 409. Not an SD render; proves only that the gate is specific to kontext.
	 */
	@Test(timeout = CASE_TIMEOUT_MS)
	public void case03_flux2OverrideIsNotRefusedByTheRetirementGate() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BaseRecord wf = workflow(u, bk);
		long orgId = orgOf(u);
		BaseRecord node = compositeNode(u, bk, wf, "p28-c03", modeOverride(SceneCompositeUtil.MODE_FLUX2));
		String nodeOid = node.get(FieldNames.FIELD_OBJECT_ID);
		List<String> sdCalls = new ArrayList<>();

		Exception thrown = null;
		try {
			PbNodeExecutor.executeNodeRun(u, bk, wf, node, sentinelSdUtil(sdCalls));
		}
		catch(Exception e) {
			thrown = e;
		}
		logger.info("P2-8 case03 control: thrown=" + (thrown != null ? thrown.getClass().getSimpleName() + ": " + thrown.getMessage() : "none")
			+ " sdCalls=" + sdCalls);

		if(thrown instanceof PictureBookException) {
			PictureBookException pbe = (PictureBookException) thrown;
			assertFalse("a flux2 override must not be refused as retired: " + pbe.getMessage(),
				pbe.getStatus() == 409 && String.valueOf(pbe.getMessage()).startsWith(SceneCompositeUtil.KONTEXT_RETIRED_MESSAGE));
		}
		if(thrown != null) {
			assertFalse("no retirement text anywhere in the control's failure: " + thrown.getMessage(),
				String.valueOf(thrown.getMessage()).contains("replaced by V3"));
		}
		BaseRecord fresh = PbGraphUtil.readNode(u, nodeOid, orgId);
		assertNotNull(fresh);
		String lastError = fresh.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertFalse("the control node's lastError must not be the retirement: " + lastError,
			lastError != null && lastError.contains("replaced by V3"));
	}

	// ─────────────────────────────── fixtures ───────────────────────────────

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

	/** A sparse {@code olio.sd.config} override carrying only {@code compositeMode}. */
	private static String modeOverride(String mode) throws Exception {
		BaseRecord override = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG, new String[] { "compositeMode" });
		override.set("compositeMode", mode);
		String s = PbConfigUtil.sparseOverride(override, Arrays.asList("compositeMode"));
		assertNotNull("sparse override", s);
		return s;
	}

	/**
	 * A fresh scene in the fixture book plus a COMPOSITE node scoped to it, with the given sparse override; no
	 * bindings (an unbound composite is a degradation, not a refusal, so the mode gate is reached). Copies
	 * {@code TestPbRenderJob.landscapeNode} with the node type changed.
	 */
	private BaseRecord compositeNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String prefix, String configOverride) throws Exception {
		int sceneIndex = 100000 + Math.abs(UUID.randomUUID().hashCode() % 800000);
		BaseRecord scene = PbBookUtil.createScene(u, bk, sceneIndex, prefix + " scene", PbBookUtil.bookGroupPath(SLUG));
		assertNotNull("scene " + sceneIndex, scene);
		String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);

		String handle = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord n = PbGraphUtil.addNode(u, wf, handle, PbNodeTypeEnumType.COMPOSITE, PbBookUtil.workflowGroupPath(SLUG), 0);
		assertNotNull("node " + handle, n);
		BaseRecord patch = PbGraphUtil.patchOf(n, OlioModelNames.MODEL_PB_NODE, new String[] {
			OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF, OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE });
		patch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_SCENE);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, sceneOid);
		patch.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, configOverride);
		assertNotNull("scope patch must be written", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbGraphUtil.readNode(u, n.get(FieldNames.FIELD_OBJECT_ID), orgOf(u));
		assertNotNull(fresh);
		assertEquals("precondition: scopeRef", sceneOid, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		assertEquals("precondition: configOverride", configOverride, fresh.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
		assertEquals("precondition: PENDING", PbNodeStatusEnumType.PENDING.name(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return fresh;
	}

	/** The real SDUtil against the configured Swarm server, with every image entry point replaced by a sentinel throw. */
	private SDUtil sentinelSdUtil(List<String> sdCalls) {
		return new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name,
					int batch, boolean hires, int seed) {
				sdCalls.add("createImage:" + name);
				throw new RuntimeException("P2-8 sentinel: SD createImage reached");
			}
			@Override
			public List<BaseRecord> createSceneImage(BaseRecord user, String groupPath, String name, SWTxt2Img s2i,
					String sysCharOid, String usrCharOid) {
				sdCalls.add("createSceneImage:" + name);
				throw new RuntimeException("P2-8 sentinel: SD createSceneImage reached");
			}
		};
	}

	private Query runsQuery(BaseRecord u, BaseRecord wf) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, wf);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(PbGraphUtil.runRequest());
		q.setCache(false);
		return q;
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
}
