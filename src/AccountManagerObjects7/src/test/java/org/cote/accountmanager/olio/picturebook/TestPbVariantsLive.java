package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.QaReport;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbRenderPlanUtil.PlanOptions;
import org.cote.accountmanager.olio.picturebook.PbRetentionUtil.Policy;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 6 Scenario F (PictureBook V3 §23, Implementation §13.6 / §6.C 212) against the <b>live</b> Swarm
 * node ({@code test.swarm.server}), through the production run path - {@code PbNodeExecutor.executeNodeRun}
 * with {@code RenderBackend.SWARM}, the same call the facade and the render job make - on {@code am7db}:
 * <ol>
 * <li><b>STANDARD</b> (preset variants 2) as a request override on one RENDER_PLAN node: one run, two
 *     {@code base} artifacts sharing that run as their {@code attempt}, variantIndex 0 / 1, real PNG bytes
 *     that differ, seeds reported by Swarm and distinct (variant 1 = {@code variantSeed(seed0, 1)}), only
 *     variant 0 selected, nothing approved (the book's autoApprove is off).</li>
 * <li><b>FINAL</b> (variants 4) as a second attempt on the same node: four more finals, variantIndex 0..3,
 *     seeds derived from its own variant 0, the new variant 0 displaces the STANDARD selection. The FINAL
 *     preset's UPSCALE stage is reported as a degradation, not rendered (Phase 2 renders BASE only) - that is
 *     asserted, not hidden.</li>
 * <li><b>Approve one</b>: a human approval of FINAL variant 2 (not the headline) selects it, pins the node
 *     and leaves one APPROVED per (node, role).</li>
 * <li><b>Retention</b> with {@code keepAttempts = 1}: the dry run marks the STANDARD attempt's two finals
 *     ARCHIVE and keeps all four of the FINAL attempt (within the last attempt; variant 2 as approved); the
 *     apply archives exactly what the scan said, refuses / denies / fails nothing, and the approved artifact
 *     keeps its bytes and status.</li>
 * </ol>
 * Six real renders on the GPU; the timeout is a hang guard, never a threshold. PNGs are written under
 * {@code target/p65-*.png} for inspection. Skips visibly (NOT a pass) when the node is unreachable or lists
 * no usable checkpoint ({@link SdTestGate#requireRenderConfig}).
 */
public class TestPbVariantsLive extends BaseTest {

	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	private static final String RUN_SETTING = "a basalt headland above a grey sea at dusk";
	private static final String RUN_SD_PROMPT = "two travellers on a basalt headland at dusk, wind in their coats";
	/// Six live renders, FINAL at stepsMode high: a hang guard for a slow node, never a performance bar.
	private static final long SCENARIO_TIMEOUT_MS = 60L * 60L * 1000L;

	private String server;

	@Before
	public void liveSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SwarmModelInventory.invalidateAll();
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private static String oidOf(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static String status(Object v) {
		return (v != null ? v.toString().toUpperCase() : null);
	}

	private static boolean selected(BaseRecord a) {
		Boolean sel = a.get(OlioFieldNames.FIELD_PB_SELECTED);
		return sel != null && sel.booleanValue();
	}

	private static int intOf(Object v) {
		assertTrue("a number, got " + v, v instanceof Number);
		return ((Number) v).intValue();
	}

	private static long seedOf(BaseRecord a) {
		Object s = a.get(OlioFieldNames.FIELD_PB_SEED);
		return (s instanceof Number ? ((Number) s).longValue() : 0L);
	}

	private static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		BaseRecord workflow;
		BaseRecord node;
		String slug;
		String bookOid;
		String sceneOid;
		SDUtil sdu;
	}

	/// A V3 book with one scene carrying its own text, a user-edited MIGRATED composition (so the run never
	/// calls the LLM and never reports COMPOSED_OUT_OF_ORDER), its RENDER_PLAN node, autoApprove off.
	private Fixture fixture(String prefix) throws Exception {
		return fixture(prefix, false);
	}

	/// {@link #fixture(String)} with the book's {@code autoApprove} switch set as asked (§24 I-6: scenario G runs it ON).
	private Fixture fixture(String prefix, boolean autoApprove) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", f.owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		long orgId = orgOf(f.owner);
		f.slug = prefix + shortId();
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "Scenario F " + f.slug);
		assertNotNull("createBook", f.book);
		f.bookOid = oidOf(f.book);
		BookContext bctx = PbBookUtil.openBookContext(f.owner, f.book);
		assertNotNull("book context", bctx);
		assertNotNull("book world", bctx.getWorld());

		BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, 0, "Scene 0", PbBookUtil.bookGroupPath(f.slug));
		assertNotNull("scene", scene);
		f.sceneOid = oidOf(scene);
		BaseRecord sp = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_CB_SD_PROMPT);
		sp.set(OlioFieldNames.FIELD_PB_SETTING, RUN_SETTING);
		sp.set(OlioFieldNames.FIELD_CB_SD_PROMPT, RUN_SD_PROMPT);
		assertNotNull("scene text patch", ioContext.getAccessPoint().update(f.owner, sp));
		scene = PbBookUtil.readScene(f.owner, f.sceneOid, orgId);
		assertEquals("scene sdPrompt", RUN_SD_PROMPT, scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));

		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		f.workflow = PbGraphUtil.getCreateWorkflow(f.owner, f.book, wfPath);
		assertNotNull("workflow", f.workflow);
		BaseRecord n = PbGraphUtil.addNode(f.owner, f.workflow, PbPipelineUtil.renderPlanHandle(f.sceneOid), PbNodeTypeEnumType.RENDER_PLAN, wfPath, 0);
		assertNotNull("render-plan node", n);
		BaseRecord np = PbGraphUtil.patchOf(n, OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF);
		np.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_SCENE);
		np.set(OlioFieldNames.FIELD_PB_SCOPE_REF, f.sceneOid);
		assertNotNull("node scope patch must be written", ioContext.getAccessPoint().update(f.owner, np));

		BaseRecord book = PbBookUtil.readBook(f.owner, f.bookOid, orgId);
		ComposeResult cr = PbComposeUtil.composeMigrated(f.owner, book, scene, false);
		assertNotNull("compose", cr);
		assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
		BaseRecord comp = PbComposeUtil.readComposition(f.owner, oidOf(cr.composition), orgId);
		assertNotNull("composition", comp);
		assertNotNull("composition marked user-edited", PbComposeUtil.touchEdited(f.owner, comp, null));
		assertTrue("autoApprove " + (autoApprove ? "on" : "off"), PbBookUtil.setAutoApprove(f.owner, book, autoApprove));
		CacheUtil.clearCache();
		f.book = PbBookUtil.readBook(f.owner, f.bookOid, orgId);
		assertNotNull("book re-read", f.book);
		if(autoApprove) {
			assertEquals("autoApprove read back on", Boolean.TRUE, f.book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
		}
		else {
			assertNotEquals("autoApprove read back off", Boolean.TRUE, f.book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
		}
		f.node = PbGraphUtil.readNode(f.owner, oidOf(n), orgId);
		assertNotNull("re-read node", f.node);
		f.sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		return f;
	}

	private void cleanup(Fixture f) {
		if(f == null || f.book == null) return;
		try {
			PbDeleteUtil.deleteBookComplete(f.owner, f.bookOid);
		}
		catch(Exception e) {
			logger.warn("Cleanup of " + f.slug + ": " + e.getMessage());
		}
	}

	/// The node's artifacts under {@code role}, re-read uncached and sorted by (attempt order of first sight,
	/// variantIndex); the selection and review stamps are written after the create and listChain is a projection.
	private static List<BaseRecord> artifactsByVariant(Fixture f, String role) {
		List<BaseRecord> fresh = new ArrayList<>();
		for(BaseRecord a : PbArtifactUtil.listChain(f.owner, f.node, role)) {
			BaseRecord r = PbArtifactUtil.readArtifact(f.owner, oidOf(a), orgOf(f.owner));
			assertNotNull("re-read " + oidOf(a), r);
			fresh.add(r);
		}
		fresh.sort((a, b) -> Integer.compare(((Number) a.get(OlioFieldNames.FIELD_PB_REVISION)).intValue(),
			((Number) b.get(OlioFieldNames.FIELD_PB_REVISION)).intValue()));
		return fresh;
	}

	/// The attempt's finals: the {@code base} artifacts whose {@code attempt} is {@code runOid}, in variantIndex order.
	private static List<BaseRecord> attemptFinals(Fixture f, String runOid) {
		List<BaseRecord> out = new ArrayList<>();
		for(BaseRecord a : artifactsByVariant(f, PbPipelineUtil.ROLE_BASE)) {
			if(runOid.equals(PbArtifactUtil.attemptOf(a))) {
				out.add(a);
			}
		}
		out.sort((a, b) -> Integer.compare(PbArtifactUtil.variantIndexOf(a), PbArtifactUtil.variantIndexOf(b)));
		return out;
	}

	private BaseRecord freshReadData(BaseRecord u, String dataOid) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, dataOid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return ioContext.getAccessPoint().find(u, q);
	}

	/// The artifact's bytes, read fresh from its {@code data.data}; asserts a real PNG and writes it under target/.
	private byte[] artifactPng(Fixture f, BaseRecord artifact, String label) throws Exception {
		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull(label + " points at a data.data", dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) {
			ioContext.getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		BaseRecord data = freshReadData(f.owner, dataOid);
		assertNotNull(label + " data.data " + dataOid, data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertTrue(label + " real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals(label + " PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		File out = new File("target", "p65-" + label + "-" + f.slug + ".png");
		Files.write(out.toPath(), bytes);
		logger.info("MEASURED: " + label + " seed=" + seedOf(artifact) + " bytes=" + bytes.length
			+ " width=" + artifact.get(OlioFieldNames.FIELD_PB_IMAGE_WIDTH) + " height=" + artifact.get(OlioFieldNames.FIELD_PB_IMAGE_HEIGHT)
			+ " durationMs=" + artifact.get(OlioFieldNames.FIELD_PB_DURATION_MS) + " png=" + out.getAbsolutePath());
		return bytes;
	}

	/// One production run of the node under {@code preset} against the live node; the node is re-read first
	/// (its status moved on the previous attempt) and the DTO's headline counts are asserted here.
	private Map<String, Object> run(Fixture f, PbQualityPresetEnumType preset, String installed, int expectVariants) {
		return run(f, preset, installed, expectVariants, null, null, false);
	}

	/// {@link #run(Fixture, PbQualityPresetEnumType, String, int)} with a request {@code variants} override (§24 I-6;
	/// null = the preset's own count), the run's chat config (the visual-QA route; null = the user's default) and
	/// whether the book's autoApprove is ON - when it is, the DTO's {@code selected} flags follow the approved variant,
	/// not variant 0, so that check and the "nothing auto-approved" check are the caller's.
	private Map<String, Object> run(Fixture f, PbQualityPresetEnumType preset, String installed, int expectVariants,
			Integer variantsOverride, BaseRecord chatConfig, boolean autoApproveOn) {
		return run(f, new PlanOptions(null, false, preset, installed, chatConfig, variantsOverride), preset.name(), expectVariants, autoApproveOn);
	}

	/// The core of {@link #run}: one production run of the node under {@code opts} (§24 I-8 passes
	/// {@code withBackgroundRegen(true)} here), logged and asserted under {@code label}.
	private Map<String, Object> run(Fixture f, PlanOptions opts, String preset, int expectVariants, boolean autoApproveOn) {
		f.node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgOf(f.owner));
		assertNotNull("node before " + preset, f.node);
		long started = System.currentTimeMillis();
		Map<String, Object> out = PbNodeExecutor.executeNodeRun(f.owner, f.book, f.workflow, f.node, f.sdu, opts, null);
		assertNotNull(preset + " run DTO", out);
		logger.info("MEASURED: " + preset + " run on " + server + " model=" + opts.getModelOverride() + " variantsOverride=" + opts.getVariantsOverride()
			+ " backgroundRegen=" + opts.isBackgroundRegen() + " status=" + out.get("runStatus")
			+ " strategy=" + out.get("strategy") + " planned=" + out.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)
			+ " rendered=" + out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED) + " degradations=" + PbNodeExecutor.degradationsOf(out)
			+ " qaMode=" + out.get(PbNodeExecutor.KEY_QA_MODE) + " autoApproved=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVED)
			+ " wallMs=" + (System.currentTimeMillis() - started) + " (informational, never a threshold)");
		assertEquals(preset + " run status", PbRunStatusEnumType.COMPLETED.toString(), status(out.get("runStatus")));
		assertNotNull(preset + " run objectId", out.get("runObjectId"));
		assertEquals("a scene with no cast and no location plans TEXT_ONLY", PbStrategyEnumType.TEXT_ONLY.name(), out.get("strategy"));
		assertEquals(preset + " variants planned", expectVariants, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)));
		assertEquals(preset + " variants rendered", expectVariants, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
		Object vs = out.get(PbNodeExecutor.KEY_VARIANTS);
		assertTrue(preset + " variants is a list: " + vs, vs instanceof List);
		List<?> variants = (List<?>) vs;
		assertEquals(preset + " DTO variants", expectVariants, variants.size());
		Set<Object> artifactOids = new LinkedHashSet<>();
		for(int i = 0; i < variants.size(); i++) {
			Map<?, ?> d = (Map<?, ?>) variants.get(i);
			assertEquals(preset + " DTO variantIndex", i, intOf(d.get("variantIndex")));
			if(!autoApproveOn) {
				assertEquals(preset + " DTO selected only for variant 0", Boolean.valueOf(i == 0), d.get("selected"));
			}
			artifactOids.add(d.get("artifactObjectId"));
		}
		assertEquals(preset + " distinct artifacts on the DTO", expectVariants, artifactOids.size());
		assertEquals(preset + " variant 0 is the DTO's artifact", out.get("artifactObjectId"), ((Map<?, ?>) variants.get(0)).get("artifactObjectId"));
		if(!autoApproveOn) {
			assertNotEquals(preset + " nothing auto-approved with autoApprove off", Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		}
		return out;
	}

	/// The attempt's finals on the database: {@code n} base artifacts under {@code runOid}, variantIndex 0..n-1,
	/// real distinct PNGs, seeds reported and derived from variant 0, only variant 0 selected, all GENERATED.
	private List<BaseRecord> assertAttempt(Fixture f, String label, String runOid, int n, List<String> degradations) throws Exception {
		return assertAttempt(f, label, runOid, n, degradations, null);
	}

	/// {@link #assertAttempt(Fixture, String, String, int, List)} for an attempt auto-approve acted on: {@code approvedOid}
	/// (null = nothing approved, the plain shape) is the one selected and APPROVED, every sibling is neither selected nor
	/// APPROVED (GENERATED, or IN_REVIEW when visual QA flagged it - §17, 220 d).
	private List<BaseRecord> assertAttempt(Fixture f, String label, String runOid, int n, List<String> degradations, String approvedOid) throws Exception {
		List<BaseRecord> finals = attemptFinals(f, runOid);
		assertEquals(label + " finals of attempt " + runOid, n, finals.size());
		boolean seedUnknown = false;
		for(String d : (degradations != null ? degradations : List.<String>of())) {
			seedUnknown |= d.startsWith(RenderPlan.Degradation.VARIANT_SEED_UNKNOWN);
		}
		assertFalse(label + " Swarm reported variant 0's seed (no VARIANT_SEED_UNKNOWN): " + degradations, seedUnknown);
		long seed0 = seedOf(finals.get(0));
		assertTrue(label + " variant 0 seed is a real seed, got " + seed0, seed0 > 0L);
		Set<Long> seeds = new LinkedHashSet<>();
		List<byte[]> pngs = new ArrayList<>();
		for(int i = 0; i < n; i++) {
			BaseRecord a = finals.get(i);
			assertEquals(label + " variantIndex", i, PbArtifactUtil.variantIndexOf(a));
			assertEquals(label + " attempt", runOid, PbArtifactUtil.attemptOf(a));
			if(approvedOid == null) {
				assertEquals(label + " selected only for variant 0", i == 0, selected(a));
				assertEquals(label + " variant " + i + " GENERATED", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a));
			}
			else if(approvedOid.equals(oidOf(a))) {
				assertTrue(label + " the auto-approved variant " + i + " is selected", selected(a));
				assertEquals(label + " variant " + i + " APPROVED", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a));
				assertEquals(label + " approvedBy is the auto-approver", PbArtifactUtil.APPROVED_BY_AUTO, a.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			}
			else {
				assertFalse(label + " variant " + i + " is not selected (variant " + approvedOid + " was approved)", selected(a));
				PbReviewStatusEnumType rs = PbArtifactUtil.reviewStatusOf(a);
				assertTrue(label + " variant " + i + " is GENERATED or IN_REVIEW, got " + rs,
					rs == PbReviewStatusEnumType.GENERATED || rs == PbReviewStatusEnumType.IN_REVIEW);
			}
			long seed = seedOf(a);
			assertTrue(label + " variant " + i + " seed is a real seed, got " + seed, seed > 0L);
			assertEquals(label + " variant " + i + " seed derives from variant 0's reported seed", (long) PbNodeExecutor.variantSeed(seed0, i), seed);
			seeds.add(Long.valueOf(seed));
			pngs.add(artifactPng(f, a, label + "-v" + i));
		}
		assertEquals(label + " seeds are distinct: " + seeds, n, seeds.size());
		for(int i = 1; i < n; i++) {
			assertFalse(label + " variant " + i + " bytes differ from variant 0", Arrays.equals(pngs.get(0), pngs.get(i)));
		}
		return finals;
	}

	private static Map<?, ?> itemFor(Map<String, Object> sweep, String artifactOid) {
		for(Object o : (List<?>) sweep.get("items")) {
			Map<?, ?> m = (Map<?, ?>) o;
			if(artifactOid.equals(m.get("objectId"))) {
				return m;
			}
		}
		return null;
	}

	@Test(timeout = SCENARIO_TIMEOUT_MS)
	public void scenarioF_standardThenFinalApproveOneRetainOne() throws Exception {
		logger.info("scenarioF_standardThenFinalApproveOneRetainOne");
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "Scenario F variants");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		Fixture f = null;
		try {
			f = fixture("p65f");
			long orgId = orgOf(f.owner);

			/// (1) STANDARD: two finals of one attempt, distinct seeds, variant 0 selected, nothing approved.
			Map<String, Object> std = run(f, PbQualityPresetEnumType.STANDARD, installed, 2);
			String stdRun = (String) std.get("runObjectId");
			List<String> stdDeg = PbNodeExecutor.degradationsOf(std);
			assertTrue("STANDARD run has no degradations: " + stdDeg, stdDeg == null || stdDeg.isEmpty());
			List<BaseRecord> stdFinals = assertAttempt(f, "standard", stdRun, 2, stdDeg);

			/// (2) FINAL: four more on the same node; its UPSCALE stage is a reported degradation (Phase 2
			/// renders BASE only), and its variant 0 displaces the STANDARD selection.
			Map<String, Object> fin = run(f, PbQualityPresetEnumType.FINAL, installed, 4);
			String finRun = (String) fin.get("runObjectId");
			assertNotEquals("two attempts", stdRun, finRun);
			List<String> finDeg = PbNodeExecutor.degradationsOf(fin);
			boolean upscaleReported = false;
			for(String d : (finDeg != null ? finDeg : List.<String>of())) {
				upscaleReported |= d.startsWith(RenderPlan.Degradation.UPSCALE_NOT_RENDERED + ": ");
			}
			assertTrue("FINAL reports its planned UPSCALE stage as a degradation: " + finDeg, upscaleReported);
			assertEquals("FINAL's only degradation is the upscale report: " + finDeg, 1, finDeg.size());
			List<BaseRecord> finFinals = assertAttempt(f, "final", finRun, 4, finDeg);
			List<BaseRecord> stdAfter = attemptFinals(f, stdRun);
			assertFalse("STANDARD variant 0 is no longer selected", selected(stdAfter.get(0)));
			assertFalse("STANDARD variant 1 is still not selected", selected(stdAfter.get(1)));
			assertEquals("six base artifacts on the node", 6, artifactsByVariant(f, PbPipelineUtil.ROLE_BASE).size());

			/// (3) Approve one: FINAL variant 2, not the headline - approve implies select, pins the node.
			BaseRecord pick = finFinals.get(2);
			String pickOid = oidOf(pick);
			assertNotNull("approve", PbArtifactUtil.approve(f.owner, pick, oidOf(f.owner)));
			BaseRecord approved = PbArtifactUtil.readArtifact(f.owner, pickOid, orgId);
			assertEquals("picked variant APPROVED", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(approved));
			assertTrue("picked variant selected", selected(approved));
			assertEquals("approvedBy is the owner", oidOf(f.owner), approved.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			for(BaseRecord a : attemptFinals(f, finRun)) {
				if(!pickOid.equals(oidOf(a))) {
					assertFalse("FINAL variant " + PbArtifactUtil.variantIndexOf(a) + " deselected by the approval", selected(a));
					assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a));
				}
			}
			BaseRecord pinned = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			assertEquals("node pinned by the approval", Boolean.TRUE, pinned.get(OlioFieldNames.FIELD_PB_PINNED));

			/// (4) Retention K=1: dry run marks the STANDARD attempt ARCHIVE and keeps the FINAL attempt whole.
			Policy policy = Policy.of(Integer.valueOf(1), null);
			Map<String, Object> scan = PbRetentionUtil.scanBook(f.owner, f.book, f.workflow, policy);
			assertNotNull("scan", scan);
			assertEquals("scan keepAttempts", 1, intOf(scan.get("keepAttempts")));
			logger.info("MEASURED: retention scan K=1 counts=" + scan.get("counts") + " total=" + scan.get("total") + " warnings=" + scan.get("warnings"));
			for(BaseRecord a : stdFinals) {
				Map<?, ?> item = itemFor(scan, oidOf(a));
				assertNotNull("scan lists STANDARD variant " + PbArtifactUtil.variantIndexOf(a), item);
				assertEquals("STANDARD variant " + PbArtifactUtil.variantIndexOf(a) + " is ARCHIVE: " + item, "ARCHIVE", item.get("action"));
			}
			for(BaseRecord a : finFinals) {
				Map<?, ?> item = itemFor(scan, oidOf(a));
				assertNotNull("scan lists FINAL variant " + PbArtifactUtil.variantIndexOf(a), item);
				assertEquals("FINAL variant " + PbArtifactUtil.variantIndexOf(a) + " is KEEP: " + item, "KEEP", item.get("action"));
			}
			assertEquals("the approved one is kept as approved", "approved", itemFor(scan, pickOid).get("reason"));
			Map<?, ?> scanCounts = (Map<?, ?>) scan.get("counts");
			int toArchive = intOf(scanCounts.get("archive"));
			assertTrue("at least the two STANDARD finals archive, got " + toArchive, toArchive >= 2);
			assertEquals("nothing is byte-deletable yet", 0, intOf(scanCounts.get("deleteBytes")));

			/// The apply archives exactly what the scan said, as the owner, refusing / denying / failing nothing.
			Map<String, Object> apply = PbRetentionUtil.applyBook(f.owner, f.book, f.workflow, policy);
			assertNotNull("apply", apply);
			logger.info("MEASURED: retention apply K=1 archived=" + apply.get("archived") + " bytesDeleted=" + apply.get("bytesDeleted")
				+ " refused=" + apply.get("refused") + " denied=" + apply.get("denied") + " failed=" + apply.get("failed") + " warnings=" + apply.get("warnings"));
			assertEquals("archived matches the scan", toArchive, intOf(apply.get("archived")));
			assertEquals("refused", 0, intOf(apply.get("refused")));
			assertEquals("denied", 0, intOf(apply.get("denied")));
			assertEquals("failed", 0, intOf(apply.get("failed")));
			assertEquals("bytesDeleted", 0, intOf(apply.get("bytesDeleted")));

			for(BaseRecord a : stdFinals) {
				BaseRecord after = PbArtifactUtil.readArtifact(f.owner, oidOf(a), orgId);
				assertEquals("STANDARD variant " + PbArtifactUtil.variantIndexOf(a) + " ARCHIVED", PbReviewStatusEnumType.ARCHIVED, PbArtifactUtil.reviewStatusOf(after));
				assertNotNull("archival stamped", PbArtifactUtil.archivedAtOf(after));
				assertNotNull("bytes kept until D days pass", after.get(OlioFieldNames.FIELD_PB_DATA));
			}
			for(BaseRecord a : finFinals) {
				BaseRecord after = PbArtifactUtil.readArtifact(f.owner, oidOf(a), orgId);
				assertEquals("FINAL variant " + PbArtifactUtil.variantIndexOf(a) + " status unchanged",
					(pickOid.equals(oidOf(a)) ? PbReviewStatusEnumType.APPROVED : PbReviewStatusEnumType.GENERATED), PbArtifactUtil.reviewStatusOf(after));
				assertNull("not archived", PbArtifactUtil.archivedAtOf(after));
			}
			artifactPng(f, PbArtifactUtil.readArtifact(f.owner, pickOid, orgId), "approved-kept");

			/// A second apply is a no-op: the scan has nothing left to archive and nothing is due for deletion.
			Map<String, Object> again = PbRetentionUtil.applyBook(f.owner, f.book, f.workflow, policy);
			assertEquals("second apply archives nothing", 0, intOf(again.get("archived")));
			assertEquals("second apply deletes nothing", 0, intOf(again.get("bytesDeleted")));
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Scenario G (§24 I-6): request variants override + auto-approve ON under FINAL                     */
	/* ------------------------------------------------------------------------------------------------ */

	private static final String G_CHAT_CONFIG_NAME = "p66 vlm qa";
	private static final int G_CHAT_TIMEOUT_S = 300;
	/// Fewer than FINAL's own 4 (and more than 1), so a planned count of 3 can only have come from the override.
	private static final int G_VARIANTS = 3;

	/// The run's chat config for the visual-QA route (220 e): a direct OLLAMA record on {@code test.llm.ollama.server}
	/// as {@link LlmTestGate} resolved it (pin {@code -Dtest.llm.ollama.server=<url>} to a node that lists the
	/// qualityPresets.json {@code qaModel}, else the VLM probe reports it unavailable and the TEXT_ONLY plan has nothing
	/// the Swarm face detector can judge - the run still completes, with no QA). Null when the key is blank.
	private BaseRecord qaChatConfig(BaseRecord owner) {
		String llmServer = testProperties.getProperty("test.llm.ollama.server", "").trim();
		if(llmServer.isEmpty()) {
			logger.warn("[p66] test.llm.ollama.server is blank: the run uses the owner's default analysis config for visual QA");
			return null;
		}
		BaseRecord cfg = OlioTestUtil.getCreateChatConfig(owner, G_CHAT_CONFIG_NAME,
			new OlioTestUtil.ConnectionTarget(llmServer, null, ConnectionDialectEnumType.OLLAMA, ConnectionUpstreamEnumType.OLLAMA, G_CHAT_TIMEOUT_S),
			testProperties.getProperty("test.llm.ollama.model", "qwen3:8b"), G_CHAT_TIMEOUT_S, false);
		assertNotNull("chatConfig " + G_CHAT_CONFIG_NAME, cfg);
		VlmQaProvider probe = new VlmQaProvider(owner, cfg);
		boolean up = probe.isAvailable();
		logger.info("[p66] visual-QA route: direct OLLAMA " + llmServer + " (gate tier=" + testProperties.getProperty("test.llm.resolvedTier", "?")
			+ " route=" + testProperties.getProperty("test.llm.route", "?") + ") qaModel=" + QualityPresets.qaModel()
			+ " vlmAvailable=" + up + (up ? "" : " (" + probe.availabilityNote() + ")"));
		return cfg;
	}

	/// The variant the ALL ordering (220 c) picks from the DTO's own verdicts: PASS before WARN before unchecked /
	/// UNAVAILABLE, FAIL never, ties to the lowest variantIndex; under OFF (no {@code qaMode} on the DTO) variant 0.
	private static String expectedCandidate(Map<String, Object> out) {
		Object mode = out.get(PbNodeExecutor.KEY_QA_MODE);
		List<?> variants = (List<?>) out.get(PbNodeExecutor.KEY_VARIANTS);
		if(mode == null || !QualityPresets.QA_ALL.equals(mode.toString())) {
			return (String) ((Map<?, ?>) variants.get(0)).get("artifactObjectId");
		}
		String best = null;
		int bestRank = Integer.MAX_VALUE;
		for(Object o : variants) {
			Map<?, ?> v = (Map<?, ?>) o;
			Map<?, ?> q = (Map<?, ?>) v.get(PbNodeExecutor.KEY_VARIANT_QA);
			String r = (q != null && q.get("result") != null ? q.get("result").toString() : null);
			int rank;
			if(r == null || QaReport.Result.UNAVAILABLE.name().equals(r)) {
				rank = 2;
			}
			else if(QaReport.Result.PASS.name().equals(r)) {
				rank = 0;
			}
			else if(QaReport.Result.WARN.name().equals(r)) {
				rank = 1;
			}
			else {
				rank = Integer.MAX_VALUE;
			}
			if(rank < bestRank) {
				bestRank = rank;
				best = (String) v.get("artifactObjectId");
			}
		}
		return best;
	}

	/**
	 * §24 I-6, the live leg carried from I-3: FINAL with a request {@code variants} override of {@value #G_VARIANTS}
	 * (the preset says 4) on a book whose {@code autoApprove} is ON. The attempt renders exactly the override, the
	 * FINAL preset's planned UPSCALE stage is reported ({@code UPSCALE_NOT_RENDERED}) and - since 224 e - does NOT veto,
	 * so auto-approve approves exactly the variant the attempt's own QA verdicts order first (220 c; variant 0 when the
	 * route judged nothing), selects it, stamps {@code approvedBy} with the auto-approver and pins the node. Three live
	 * renders on the GPU plus up to three vision-model calls; the timeout is a hang guard, never a threshold. PNGs go to
	 * {@code target/p65-g-*.png}.
	 */
	@Test(timeout = SCENARIO_TIMEOUT_MS)
	public void scenarioG_finalVariantsOverrideAutoApproveOnPicksQaOrderedCandidate() throws Exception {
		logger.info("scenarioG_finalVariantsOverrideAutoApproveOnPicksQaOrderedCandidate");
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "Scenario G variants override + auto-approve");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		Fixture f = null;
		try {
			f = fixture("p66g", true);
			long orgId = orgOf(f.owner);
			BaseRecord cfg = qaChatConfig(f.owner);
			assertNotEquals("the override is a different count from FINAL's own", QualityPresets.resolve(PbQualityPresetEnumType.FINAL).getVariants(), G_VARIANTS);
			assertTrue("the override is within FINAL's maxVariants", G_VARIANTS <= QualityPresets.resolve(PbQualityPresetEnumType.FINAL).getMaxVariants());

			Map<String, Object> out = run(f, PbQualityPresetEnumType.FINAL, installed, G_VARIANTS, Integer.valueOf(G_VARIANTS), cfg, true);
			String runOid = (String) out.get("runObjectId");
			List<String> deg = PbNodeExecutor.degradationsOf(out);
			boolean upscaleReported = false;
			for(String d : (deg != null ? deg : List.<String>of())) {
				upscaleReported |= d.startsWith(RenderPlan.Degradation.UPSCALE_NOT_RENDERED + ": ");
			}
			assertTrue("FINAL reports its planned UPSCALE stage as a degradation: " + deg, upscaleReported);
			assertEquals("nothing on the attempt vetoes auto-approve (UPSCALE_NOT_RENDERED and the QA codes are accepted): " + deg,
				List.of(), PbArtifactUtil.vetoingDegradations(deg));

			/// What the hook judged, in words, before the ordering is asserted.
			List<?> variants = (List<?>) out.get(PbNodeExecutor.KEY_VARIANTS);
			Object qaMode = out.get(PbNodeExecutor.KEY_QA_MODE);
			int judged = 0;
			String provider = null;
			for(Object o : variants) {
				Map<?, ?> v = (Map<?, ?>) o;
				Map<?, ?> q = (Map<?, ?>) v.get(PbNodeExecutor.KEY_VARIANT_QA);
				if(q != null) {
					judged++;
					provider = String.valueOf(q.get("provider"));
				}
				logger.info("MEASURED: scenario G variant " + v.get("variantIndex") + " qa=" + (q != null ? q.get("result") + " via " + q.get("provider") : "not judged")
					+ " reviewStatus=" + v.get("reviewStatus") + " selected=" + v.get("selected"));
			}
			logger.info("MEASURED: scenario G qaMode=" + qaMode + " judged=" + judged + "/" + variants.size() + (provider != null ? " via " + provider : "")
				+ " autoApproved=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVED) + " skipped=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED)
				+ " approved=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
			if(qaMode != null) {
				assertEquals("FINAL judges under ALL", QualityPresets.QA_ALL, qaMode.toString());
				assertEquals("ALL judged every rendered variant", variants.size(), judged);
			}

			/// Auto-approve ON: approved, and exactly the QA-ordered candidate.
			assertEquals("auto-approved (skipped=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED) + ")", Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertNull("no skip reason when approved", out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			String approvedOid = (String) out.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT);
			assertNotNull("the approved variant is named on the DTO", approvedOid);
			String expected = expectedCandidate(out);
			assertNotNull("the verdicts leave a candidate", expected);
			assertEquals("auto-approve picked the QA-ordered candidate", expected, approvedOid);
			int approvedIndex = -1;
			for(Object o : variants) {
				Map<?, ?> v = (Map<?, ?>) o;
				boolean isApproved = approvedOid.equals(v.get("artifactObjectId"));
				assertEquals("DTO selected follows the approval for variant " + v.get("variantIndex"), Boolean.valueOf(isApproved), v.get("selected"));
				if(isApproved) {
					approvedIndex = intOf(v.get("variantIndex"));
					assertEquals("DTO reviewStatus of the approved variant", PbReviewStatusEnumType.APPROVED.name(), v.get("reviewStatus"));
				}
			}
			assertTrue("the approved variant is one of the attempt's", approvedIndex >= 0);
			assertEquals("the DTO's primary artifact is variant 0", ((Map<?, ?>) variants.get(0)).get("artifactObjectId"), out.get("artifactObjectId"));
			if(approvedIndex == 0) {
				assertEquals("top-level reviewStatus follows the primary's approval", PbReviewStatusEnumType.APPROVED.name(), out.get("reviewStatus"));
			}
			else {
				assertNull("top-level reviewStatus is only set when the primary (variant 0) is the one approved", out.get("reviewStatus"));
			}

			/// The database agrees: three finals under the one attempt, the approved one selected + APPROVED by the
			/// auto-approver, the siblings neither; distinct real PNGs; the node pinned by the approval.
			List<BaseRecord> finals = assertAttempt(f, "g-final", runOid, G_VARIANTS, deg, approvedOid);
			artifactPng(f, finals.get(approvedIndex), "g-approved-v" + approvedIndex);
			BaseRecord pinned = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			assertEquals("node pinned by the auto-approval", Boolean.TRUE, pinned.get(OlioFieldNames.FIELD_PB_PINNED));
			if(qaMode != null) {
				Map<String, QaReport> persisted = PbQaUtil.latestReportsOf(f.owner, pinned);
				for(Object o : variants) {
					Map<?, ?> v = (Map<?, ?>) o;
					String oid = (String) v.get("artifactObjectId");
					Map<?, ?> q = (Map<?, ?>) v.get(PbNodeExecutor.KEY_VARIANT_QA);
					QaReport rep = persisted.get(oid);
					assertNotNull("a qa report persisted for variant " + v.get("variantIndex") + ": " + persisted.keySet(), rep);
					assertEquals("persisted verdict matches the DTO for variant " + v.get("variantIndex"), String.valueOf(q.get("result")), rep.getResult().name());
				}
			}
			assertEquals("three base artifacts on the node", G_VARIANTS, artifactsByVariant(f, PbPipelineUtil.ROLE_BASE).size());
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Scenario H (§24 I-8, design §22): background-only regenerate of a text-only scene                 */
	/* ------------------------------------------------------------------------------------------------ */

	/**
	 * Design §22 / §24 I-8 against the live node: a DRAFT render of the text-only scene, then the same node run
	 * again with {@code PlanOptions.withBackgroundRegen(true)} - the production path behind
	 * {@code POST /{book}/scene/{scene}/regenerate-background} and the {@code pb.regenerateBackground} job.
	 * Two GPU renders. Asserted: the regenerate is one attempt of exactly one fresh BASE (the preset's count and
	 * gap-fill are overridden), the DTO names what it regenerated from, and - because a TEXT_ONLY plan has no
	 * ACTOR_REGION pass to replay - it reports {@code BACKGROUND_REGEN_NO_REGIONS} (the actors, had there been any,
	 * were re-rendered with the background) and does not auto-approve. On the database the new final has a real,
	 * different seed, supersedes the first and - nothing being APPROVED - takes the selection from it (V3 §10 rule 1
	 * only protects an APPROVED selection; the job-path case in {@code TestPbRenderStagesOffline} covers that branch).
	 * The ACTOR_REGION seed-reuse branch itself needs a referenced cast on a FLUX.2 node and is asserted offline
	 * ({@code backgroundRegenReusesActorRegionSeed}), not here. Both PNGs are written under {@code target/p65-h-*.png}.
	 */
	@Test(timeout = SCENARIO_TIMEOUT_MS)
	public void scenarioH_regenerateBackgroundOfTextOnlySceneRendersOneFreshBase() throws Exception {
		logger.info("scenarioH_regenerateBackgroundOfTextOnlySceneRendersOneFreshBase");
		BaseRecord renderCfg = SdTestGate.requireRenderConfig(testProperties, server, "Scenario H background regenerate");
		String installed = renderCfg.get(OlioFieldNames.FIELD_SD_MODEL);
		Fixture f = null;
		try {
			f = fixture("p67h");
			long orgId = orgOf(f.owner);

			/// (1) DRAFT: one final, selected, GENERATED, nothing approved (autoApprove off).
			Map<String, Object> first = run(f, PbQualityPresetEnumType.DRAFT, installed, 1);
			String firstRun = (String) first.get("runObjectId");
			assertNull("a plain run is not a regenerate", first.get(PbNodeExecutor.KEY_REGENERATE));
			List<BaseRecord> firstFinals = assertAttempt(f, "h-first", firstRun, 1, PbNodeExecutor.degradationsOf(first));
			BaseRecord firstFinal = firstFinals.get(0);
			String firstOid = oidOf(firstFinal);
			long firstSeed = seedOf(firstFinal);
			byte[] firstPng = artifactPng(f, firstFinal, "h-first");

			/// (2) Regenerate the background: one fresh BASE on a second attempt of the same node.
			Map<String, Object> regen = run(f, new PlanOptions(null, false, PbQualityPresetEnumType.DRAFT, installed, null, null).withBackgroundRegen(true),
				"DRAFT regenerate-background", 1, false);
			String regenRun = (String) regen.get("runObjectId");
			assertNotEquals("the regenerate is its own attempt", firstRun, regenRun);
			assertEquals("the DTO says what it did", PbNodeExecutor.REGENERATE_BACKGROUND, regen.get(PbNodeExecutor.KEY_REGENERATE));
			assertEquals("the DTO names the final it regenerated from", firstOid, regen.get(PbNodeExecutor.KEY_REGENERATED_FROM));
			assertEquals("no actor seed reused on a text-only scene", List.of(), regen.get(PbNodeExecutor.KEY_ACTOR_SEEDS_REUSED));
			assertEquals("no actor seed new on a text-only scene", List.of(), regen.get(PbNodeExecutor.KEY_ACTOR_SEEDS_NEW));
			List<String> deg = PbNodeExecutor.degradationsOf(regen);
			boolean noRegions = false;
			for(String d : (deg != null ? deg : List.<String>of())) {
				noRegions |= d.startsWith(RenderPlan.Degradation.BACKGROUND_REGEN_NO_REGIONS + ": ");
			}
			assertTrue("a TEXT_ONLY plan has no ACTOR_REGION pass, and the result says so: " + deg, noRegions);
			assertTrue("BACKGROUND_REGEN_NO_REGIONS vetoes auto-approve: " + deg,
				PbArtifactUtil.vetoingDegradations(deg).stream().anyMatch(d -> d.startsWith(RenderPlan.Degradation.BACKGROUND_REGEN_NO_REGIONS)));
			assertNotEquals("not auto-approved", Boolean.TRUE, regen.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			logger.info("MEASURED: scenario H regenerate from=" + regen.get(PbNodeExecutor.KEY_REGENERATED_FROM)
				+ " degradations=" + deg + " autoApproveSkipped=" + regen.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));

			/// (3) The database: a second final with a real, different seed, superseding the first and - nothing
			/// APPROVED - now the selection; the first stays GENERATED and loses the selection; the node is not pinned.
			List<BaseRecord> regenFinals = assertAttempt(f, "h-regen", regenRun, 1, deg);
			BaseRecord regenFinal = regenFinals.get(0);
			long regenSeed = seedOf(regenFinal);
			assertTrue("the regenerate's seed is a real seed, got " + regenSeed, regenSeed > 0L);
			assertNotEquals("the BASE re-rolled its seed", firstSeed, regenSeed);
			BaseRecord sup = regenFinal.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
			assertNotNull("the regenerate supersedes the first final", sup);
			assertEquals(firstOid, sup.get(FieldNames.FIELD_OBJECT_ID));
			assertTrue("the regenerate's final is selected (nothing was APPROVED)", selected(regenFinal));
			assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(regenFinal));
			BaseRecord firstAfter = PbArtifactUtil.readArtifact(f.owner, firstOid, orgId);
			assertFalse("the first final lost the selection", selected(firstAfter));
			assertEquals("the first final is still GENERATED", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(firstAfter));
			assertEquals("findSelected answers the regenerate", oidOf(regenFinal), oidOf(PbArtifactUtil.findSelected(f.owner, f.node, PbPipelineUtil.ROLE_BASE)));
			assertEquals("two base artifacts on the node", 2, artifactsByVariant(f, PbPipelineUtil.ROLE_BASE).size());
			BaseRecord node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			assertNotEquals("a regenerate that was not approved does not pin", Boolean.TRUE, node.get(OlioFieldNames.FIELD_PB_PINNED));
			byte[] regenPng = artifactPng(f, regenFinal, "h-regen");
			assertFalse("the regenerate's bytes differ from the first final's", Arrays.equals(firstPng, regenPng));
		}
		finally {
			cleanup(f);
		}
	}
}
