package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.FixtureGraphClient;
import org.cote.accountmanager.olio.illustration.MaskImage;
import org.cote.accountmanager.olio.illustration.ModelResolver;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.StageImage;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.illustration.StageResult;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.PbStageFailedException;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.PlannedRender;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.RenderBackend;
import org.cote.accountmanager.olio.picturebook.PbPipelineUtil.SceneGraph;
import org.cote.accountmanager.olio.picturebook.PbRenderPlanUtil.PlanOptions;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.PbCalibrationUtil;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P5-3 (§6.C 209 b): {@code PbNodeExecutor.renderPlanned} / {@code renderStages} run against {@code am7db}
 * with <b>no Swarm server</b>, through the package-private {@link RenderBackend} seam - a scripted
 * {@link RendererAdapter} answers each stage and a {@link FixtureGraphClient} answers the compiler's
 * generated-workflow and version calls from the captured P5-0 fixtures. What the live path persists
 * (plan artifact, renderGraph envelopes, stage images, calibration rows) is asserted on the database;
 * what it sends is asserted on the requests the fake adapter received. The {@link SDUtil} points at a
 * closed port and nothing opens a connection.
 * <ol>
 * <li>{@link #directReferenceBaseCarriesBothPortraits}: DIRECT_REFERENCE, two actors referenced on BASE
 *     -> one graph request carrying {@code actora} / {@code actorb} with the portraits' bytes, one
 *     renderGraph envelope stamped {@link FixtureGraphClient#FIXTURE_VERSION}, no stage image, a persisted
 *     calibration row for (offline server, flux2 family, BASE).</li>
 * <li>{@link #sequentialInpaintChainsAPassOverTheBase}: SEQUENTIAL_INPAINT, one actor on BASE and one on
 *     ACTOR_REGION -> two requests (the second a masked init-image graph), the BASE image persisted as the
 *     one {@code stage} artifact, two renderGraph envelopes, the final references = the pass's portrait plus
 *     the stage artifact it painted over, calibration rows for both stages.</li>
 * <li>{@link #failedPassIsADegradationAndTheBaseStands}: the ACTOR_REGION pass answers an error -> one
 *     {@code ACTOR_FAILED} degradation (also recorded on the persisted plan artifact), one stage error
 *     labelled {@code ACTOR_REGION#<actor>}, the final bytes are the BASE bytes, no stage image.</li>
 * <li>{@link #baseFailureIs503WithNothingButThePlanPersisted}: the BASE stage answers UNREACHABLE ->
 *     {@link PbStageFailedException} 503 carrying the {@code UNREACHABLE} stage error; the plan artifact is
 *     the only artifact on the node and no calibration row was written.</li>
 * </ol>
 * <p>
 * P6-2 (V3 §23, §6.C 212): the same seam, a preset that plans more than one final per attempt.
 * <ol start="5">
 * <li>{@link #variantsRenderTwoFinalsOfOneAttempt}: DIRECT_REFERENCE, preset variants 2 -> BASE rendered
 *     twice, the second with the seed derived from the first's reported seed, two finals with distinct
 *     {@code data} rows, two envelopes, no degradation; {@code describe} says planned 2 / rendered 2.</li>
 * <li>{@link #sequentialInpaintVariantsEachChainTheirOwnPass}: SEQUENTIAL_INPAINT, variants 2 -> four
 *     requests BASE, pass, BASE, pass; each pass paints over its own variant's BASE; two {@code stage}
 *     artifacts stamped variantIndex 0 and 1, only variant 0's selected; each final references its own
 *     stage.</li>
 * <li>{@link #laterVariantBaseFailureKeepsWhatRendered}: variants 3, variant 1's BASE answers UNREACHABLE ->
 *     no throw; two finals keeping their real variant indexes (0 and 2, the gap is not renumbered), one
 *     stage error tagged {@code variant 1}, a {@code VARIANT_FAILED} degradation (persisted on the plan
 *     artifact, and a veto for auto-approve); planned 3 / rendered 2.</li>
 * <li>{@link #runRecordsVariantsAsOneAttempt}: {@code executeNodeRun} with a STANDARD override on a
 *     text-only scene -> two {@code base} artifacts sharing the run's objectId as {@code attempt},
 *     variantIndex 0/1, only variant 0 selected and auto-approved, {@code variants[]} on the DTO.</li>
 * <li>{@link #jobRecordsVariantsAsOneAttempt}: the same through {@code PbRenderJobUtil.submit} with the
 *     book's style on STANDARD -> the job's node outcome carries {@code variants[]} and both artifacts
 *     share the job's run as their attempt.</li>
 * </ol>
 */
public class TestPbRenderStagesOffline extends BaseTest {

	private static final String MODEL = "flux2Klein_9b";
	private static final String OFFLINE_SERVER = "http://127.0.0.1:9";
	private static final String PROMPT = "two travellers on a basalt headland at dusk";
	private static final String PASS_PROMPT = "Tobin, a weathered sailor in an oilskin coat";
	private static final String NEGATIVE = "blurry, text";
	private static final int W = 1024;
	private static final int H = 768;
	private static final int PORTRAIT_W = 96;
	private static final int PORTRAIT_H = 128;
	private static final String ACTOR_A = "actorA";
	private static final String ACTOR_B = "actorB";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	/// P6-2 run cases: the scene's own text, so the plan is TEXT_ONLY (no actors, no location) and the plain
	/// T2I path carries the derived seeds straight to the adapter.
	private static final String RUN_SETTING = "a basalt headland above a grey sea at dusk";
	private static final String RUN_SD_PROMPT = "two travellers on a basalt headland at dusk, wind in their coats";
	private static final String STYLE_PATH = "~/p62styles";
	/// A hang guard on the job poll, never a performance threshold.
	private static final long JOB_TIMEOUT_MS = 10L * 60L * 1000L;
	private static final long POLL_MS = 250L;

	@Before
	public void offlineSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
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

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	/// Enum values come back as the enum or, through JSON, the raw string; never hand a generic get() to
	/// String.valueOf - inference picks the char[] overload and throws ClassCastException.
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

	// ─────────────────────────────── scripted renderer ───────────────────────────────

	/// Answers the queued results in order and keeps every request it was handed; a request past the
	/// script is a test defect, not a silent miss.
	private static final class ScriptedAdapter implements RendererAdapter {
		final Deque<StageResult> script = new ArrayDeque<>();
		final List<StageRequest> requests = new ArrayList<>();

		ScriptedAdapter answer(StageResult... results) {
			for(StageResult r : results) {
				script.add(r);
			}
			return this;
		}

		@Override
		public RendererCapabilities capabilities(String model) {
			return RendererCapabilities.forModel(model);
		}

		@Override
		public List<String> listModels() {
			return List.of(MODEL);
		}

		@Override
		public StageResult execute(StageRequest request, CancelToken cancel) {
			requests.add(request);
			StageResult r = script.poll();
			if(r == null) {
				throw new IllegalStateException("The script has no answer for request " + requests.size() + " (" + request.getStage() + ")");
			}
			return r;
		}
	}

	private static StageResult ok(byte[] png, int seed, long durationMs) {
		return StageResult.builder()
			.image(new StageImage(png, seed, true, W, H))
			.durationMs(durationMs)
			.requestJson("{\"offline\":true}")
			.backendGraph("{\"stage\":\"offline\"}")
			.build();
	}

	/// The note the offline backend answers the visual-QA selector with (P8-4): this suite never probes the
	/// owner's chat route or a Swarm port, so a STANDARD / FINAL attempt here carries exactly one waived
	/// QA_UNAVAILABLE degradation quoting it. The hook itself is covered by TestPbQaHookOffline.
	private static final String NO_QA_NOTE = "offline test: no QA provider";

	private static RenderBackend backend(ScriptedAdapter adapter, FixtureGraphClient client) {
		return new RenderBackend() {
			@Override
			public RendererAdapter adapter(SDUtil sdu) {
				return adapter;
			}
			@Override
			public SwarmGraphClient client(SDUtil sdu) {
				return client;
			}
			@Override
			public PbQaUtil.Selection qaProvider(BaseRecord user, BaseRecord chatConfig, SDUtil sdu, String checkpoint) {
				return new PbQaUtil.Selection(null, NO_QA_NOTE);
			}
		};
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A V3 book with one scene, its RENDER_PLAN node, and two portrait artifacts with distinct solid-colour
	/// PNG bytes, owned by a non-admin user.
	private static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		BaseRecord workflow;
		BaseRecord node;
		String slug;
		String bookOid;
		String sceneOid;
		String portraitA;
		String portraitB;
		byte[] pngA;
		byte[] pngB;
		SceneGraph graph;
		SDUtil sdu;
		/// P6-2 run cases only: the BOOK-scope style pinning every task to {@link #MODEL}.
		BaseRecord style;
	}

	private Fixture fixture(String prefix) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", f.owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		f.slug = prefix + shortId();
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "Offline stages " + f.slug);
		assertNotNull("createBook", f.book);
		f.bookOid = oidOf(f.book);
		BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, 0, "Scene 0", PbBookUtil.bookGroupPath(f.slug));
		assertNotNull("scene", scene);
		f.sceneOid = oidOf(scene);

		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		f.workflow = PbGraphUtil.getCreateWorkflow(f.owner, f.book, wfPath);
		assertNotNull("workflow", f.workflow);
		BaseRecord n = PbGraphUtil.addNode(f.owner, f.workflow, PbPipelineUtil.renderPlanHandle(f.sceneOid), PbNodeTypeEnumType.RENDER_PLAN, wfPath, 0);
		assertNotNull("render-plan node", n);
		BaseRecord patch = PbGraphUtil.patchOf(n, OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_SCOPE, OlioFieldNames.FIELD_PB_SCOPE_REF);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE, PbPipelineUtil.SCOPE_SCENE);
		patch.set(OlioFieldNames.FIELD_PB_SCOPE_REF, f.sceneOid);
		assertNotNull("node scope patch must be written", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
		f.node = PbGraphUtil.readNode(f.owner, oidOf(n), orgOf(f.owner));
		assertNotNull("re-read node", f.node);

		f.pngA = MaskImage.solid(PORTRAIT_W, PORTRAIT_H, new Color(200, 40, 40));
		f.pngB = MaskImage.solid(PORTRAIT_W, PORTRAIT_H, new Color(40, 40, 200));
		f.portraitA = portraitArtifact(f, "a", f.pngA);
		f.portraitB = portraitArtifact(f, "b", f.pngB);

		f.graph = PbPipelineUtil.openNodeGraph(f.owner, f.book, f.workflow, f.node, null, null);
		assertNotNull("scene graph", f.graph);
		f.sdu = new SDUtil(SDAPIEnumType.SWARM, OFFLINE_SERVER);
		return f;
	}

	private String portraitArtifact(Fixture f, String tag, byte[] png) throws Exception {
		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		BaseRecord pNode = PbGraphUtil.addNode(f.owner, f.workflow, PbPipelineUtil.portraitHandle(UUID.randomUUID().toString()),
			PbNodeTypeEnumType.PORTRAIT, wfPath, 0);
		assertNotNull("portrait node " + tag, pNode);
		String groupPath = PbBookUtil.artifactGroupPath(f.slug);
		BaseRecord data = persistPng(f.owner, groupPath, "p53-portrait-" + tag + "-" + shortId() + ".png", png);
		BaseRecord art = PbArtifactUtil.persistArtifact(f.owner, pNode, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE,
			groupPath, data, null, null, null);
		assertNotNull("portrait artifact " + tag, art);
		assertTrue("portrait metrics " + tag, PbArtifactUtil.recordImageMetrics(f.owner, art, png, "image/png", PORTRAIT_W, PORTRAIT_H, null));
		return oidOf(art);
	}

	private BaseRecord persistPng(BaseRecord u, String groupPath, String name, byte[] bytes) throws Exception {
		BaseRecord image = RecordFactory.newInstance(ModelNames.MODEL_DATA);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, image, name, groupPath, orgOf(u));
		image.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		ByteModelUtil.setValue(image, bytes);
		BaseRecord created = ioContext.getAccessPoint().create(u, image);
		assertNotNull("data " + name, created);
		return created;
	}

	private void cleanup(Fixture f) {
		if(f == null || f.book == null) return;
		try {
			PbDeleteUtil.deleteBookComplete(f.owner, f.bookOid);
		}
		catch(Exception e) {
			logger.warn("Cleanup of " + f.slug + ": " + e.getMessage());
		}
		if(f.style != null) {
			SwarmModelInventory.invalidate(OFFLINE_SERVER);
			try {
				ioContext.getAccessPoint().delete(f.owner, f.style);
			}
			catch(Exception e) {
				logger.warn("Cleanup of style for " + f.slug + ": " + e.getMessage());
			}
		}
	}

	// ─────────────────────────────── P6-2 run fixture ───────────────────────────────

	/// Put {@code installed} into SwarmModelInventory's cache for {@code server} so the resolver never asks the
	/// (closed) port and never substitutes the pinned model. Same seam TestPbRenderPlanActors uses.
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void seedInventory(String server, List<String> installed) throws Exception {
		java.lang.reflect.Field cacheField = SwarmModelInventory.class.getDeclaredField("CACHE");
		cacheField.setAccessible(true);
		Map cache = (Map) cacheField.get(null);
		java.lang.reflect.Constructor<SwarmModelInventory> ctor = SwarmModelInventory.class.getDeclaredConstructor(String.class, List.class, long.class);
		ctor.setAccessible(true);
		cache.put(server.trim(), ctor.newInstance(server.trim(), new ArrayList<>(installed), System.currentTimeMillis()));
	}

	/// A BOOK-scope style pinning PORTRAIT / LOCATION / SCENE_DIRECT / INPAINT to {@code model} (and carrying
	/// {@code preset} when given), linked to the book; returns the style ref for cleanup.
	private BaseRecord pinBookStyle(BaseRecord u, BaseRecord bk, String tag, String model, PbQualityPresetEnumType preset) throws Exception {
		long orgId = orgOf(u);
		Map<String, String> pins = new LinkedHashMap<>();
		for(PbModelTaskEnumType t : new PbModelTaskEnumType[] { PbModelTaskEnumType.PORTRAIT, PbModelTaskEnumType.LOCATION,
				PbModelTaskEnumType.SCENE_DIRECT, PbModelTaskEnumType.INPAINT }) {
			pins.put(ModelTaskKeys.key(t), model);
		}
		BaseRecord style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, style, "p62-" + tag + "-" + shortId(), STYLE_PATH, orgId);
		style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.BOOK.toString());
		style.set(OlioFieldNames.FIELD_PB_TASK_MODELS, JSONUtil.exportObject(pins));
		if(preset != null) {
			style.set(OlioFieldNames.FIELD_PB_QUALITY_PRESET, preset.toString());
		}
		BaseRecord created = ioContext.getAccessPoint().create(u, style);
		assertNotNull("style create", created);
		BaseRecord styleRef = PbStyleUtil.readStyle(u, oidOf(created), orgId);
		assertNotNull("style read", styleRef);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_STYLE });
		patch.set(FieldNames.FIELD_ID, bk.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, bk.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, bk.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_STYLE, styleRef);
		assertNotNull("book style patch", ioContext.getAccessPoint().update(u, patch));
		CacheUtil.clearCache();
		BaseRecord after = PbBookUtil.readBook(u, oidOf(bk), orgId);
		BaseRecord linked = after.get(OlioFieldNames.FIELD_STYLE);
		assertNotNull("book carries its style", linked);
		assertEquals("book style", oidOf(styleRef), oidOf(linked));
		return styleRef;
	}

	/**
	 * Everything a real run (Cases 8/9) needs that {@link #fixture} does not give: the book context (a world,
	 * or compose 409s), scene text so the plan has a subject, a user-edited MIGRATED composition so
	 * {@code ensureComposition} neither calls the LLM nor reports COMPOSED_OUT_OF_ORDER, the book style
	 * pinned to {@link #MODEL} with the inventory seeded so nothing is MODEL_SUBSTITUTED, and auto-approve on.
	 * Leaves {@code f.book} as the fresh {@code readBook} projection (the one {@code applyAutoApprove} reads).
	 */
	private void prepareRun(Fixture f, String tag, PbQualityPresetEnumType stylePreset) throws Exception {
		long orgId = orgOf(f.owner);
		BookContext bctx = PbBookUtil.openBookContext(f.owner, f.book);
		assertNotNull("book context", bctx);
		assertNotNull("book world", bctx.getWorld());

		BaseRecord scene = PbBookUtil.readScene(f.owner, f.sceneOid, orgId);
		assertNotNull("scene", scene);
		BaseRecord sp = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_CB_SD_PROMPT);
		sp.set(OlioFieldNames.FIELD_PB_SETTING, RUN_SETTING);
		sp.set(OlioFieldNames.FIELD_CB_SD_PROMPT, RUN_SD_PROMPT);
		assertNotNull("scene text patch", ioContext.getAccessPoint().update(f.owner, sp));
		scene = PbBookUtil.readScene(f.owner, f.sceneOid, orgId);
		assertEquals("scene sdPrompt", RUN_SD_PROMPT, scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT));

		BaseRecord book = PbBookUtil.readBook(f.owner, f.bookOid, orgId);
		ComposeResult cr = PbComposeUtil.composeMigrated(f.owner, book, scene, false);
		assertNotNull("compose", cr);
		assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
		BaseRecord comp = PbComposeUtil.readComposition(f.owner, oidOf(cr.composition), orgId);
		assertNotNull("composition", comp);
		assertNotNull("composition marked user-edited", PbComposeUtil.touchEdited(f.owner, comp, null));

		f.style = pinBookStyle(f.owner, book, tag, MODEL, stylePreset);
		seedInventory(OFFLINE_SERVER, List.of(MODEL));
		assertTrue("autoApprove on", PbBookUtil.setAutoApprove(f.owner, book, true));
		CacheUtil.clearCache();
		f.book = PbBookUtil.readBook(f.owner, f.bookOid, orgId);
		assertNotNull("book re-read", f.book);
		assertEquals("autoApprove read back", Boolean.TRUE, f.book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
	}

	/// Poll the registry (the way a client polls {@code GET /rest/job/{id}}) until the job is terminal.
	private static AsyncJob awaitTerminal(BaseRecord u, String jobId, long maxMs) throws InterruptedException {
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
		fail("job " + jobId + " did not reach a terminal state within " + maxMs + "ms (last: " + (job != null ? job.getStatus() : "null") + ")");
		return null;
	}

	/// The attempt's {@code base} artifacts in variant order, each re-read uncached (the selection and review
	/// stamps are written after the create, and listChain is a projection).
	private static List<BaseRecord> baseArtifacts(Fixture f) {
		return artifactsByVariant(f, PbPipelineUtil.ROLE_BASE);
	}

	/// The node's artifacts under {@code role}, re-read and sorted by variantIndex (the variants of one attempt
	/// are written against the same pre-batch selection, so listChain's revision order is not their order).
	private static List<BaseRecord> artifactsByVariant(Fixture f, String role) {
		List<BaseRecord> chain = PbArtifactUtil.listChain(f.owner, f.node, role);
		List<BaseRecord> fresh = new ArrayList<>();
		for(BaseRecord a : chain) {
			BaseRecord full = PbArtifactUtil.readArtifact(f.owner, oidOf(a), orgOf(f.owner));
			assertNotNull("artifact " + oidOf(a), full);
			fresh.add(full);
		}
		fresh.sort((a, b) -> Integer.compare(PbArtifactUtil.variantIndexOf(a), PbArtifactUtil.variantIndexOf(b)));
		return fresh;
	}

	/// A resolved plan for the two fixture actors: both referenced, on the stages given, under
	/// {@code strategy}; the INPAINT pick and the per-actor pass prompt only for SEQUENTIAL_INPAINT.
	private static RenderPlan plan(Fixture f, PbStrategyEnumType strategy, String stageA, String stageB) {
		return plan(f, strategy, stageA, stageB, 1);
	}

	/// P6-2: the same plan with a preset that asks for {@code variants} finals per attempt.
	private static RenderPlan plan(Fixture f, PbStrategyEnumType strategy, String stageA, String stageB, int variants) {
		String family = RendererCapabilities.forModel(MODEL).getPattern();
		assertNotNull("family of " + MODEL, family);
		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("cfg", 2.0);
		cfg.put("sampler", "euler");
		cfg.put("scheduler", "simple");
		cfg.put("steps", 4);
		boolean sequential = (strategy == PbStrategyEnumType.SEQUENTIAL_INPAINT);
		List<String> stages = (sequential ? List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION) : List.of(RenderPlan.STAGE_BASE));
		RenderPlan.Builder b = RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(W, H))
			.preset(new RenderPlan.PresetValues("p53", 4, variants, false, null))
			.config(cfg)
			.actor(actor(ACTOR_A, "Mara", 0, f.portraitA, stageA, new RenderPlan.Box(0.05, 0.1, 0.4, 0.8)))
			.actor(actor(ACTOR_B, "Tobin", 1, f.portraitB, stageB, new RenderPlan.Box(0.55, 0.1, 0.4, 0.8)))
			.strategy(new RenderPlan.Strategy(strategy, stages, "p53 offline"))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.SCENE_DIRECT, MODEL, family, "DEPLOYMENT", ModelResolver.Reason.OK.name(), false, null))
			.compiled(RenderPlan.STAGE_BASE, new CompiledPrompt(PROMPT, NEGATIVE, 0));
		if(sequential) {
			b.model(new RenderPlan.ModelPick(PbModelTaskEnumType.INPAINT, MODEL, family, "DEPLOYMENT", ModelResolver.Reason.OK.name(), false, null));
			b.compiled(RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, ACTOR_B), new CompiledPrompt(PASS_PROMPT, NEGATIVE, 0));
		}
		return b.build();
	}

	private static RenderPlan.Actor actor(String id, String name, int priority, String portraitOid, String stage, RenderPlan.Box box) {
		return new RenderPlan.Actor.Builder(id)
			.name(name)
			.role(RenderPlan.ROLE_FOREGROUND)
			.priority(priority)
			.box(box)
			.identity(new RenderPlan.Identity(portraitOid, null, name))
			.referenced(true)
			.referenceStage(stage)
			.build();
	}

	private static PlannedRender render(Fixture f, RenderPlan plan, RenderBackend backend) {
		return render(f, plan, backend, 1);
	}

	/// P6-2: the backend travels on the graph now (SceneGraph.setRenderBackend), and the render asks for
	/// {@code variants} finals of one attempt.
	private static PlannedRender render(Fixture f, RenderPlan plan, RenderBackend backend, int variants) {
		f.graph.setRenderBackend(backend);
		return PbNodeExecutor.renderPlanned(f.graph, f.node, plan, "scene 0 of " + f.slug, f.sdu, "p53_" + f.slug, "Scene has no text to render", variants);
	}

	private static void assertPng(String label, byte[] bytes) {
		assertNotNull(label + " bytes", bytes);
		assertTrue(label + " is a PNG", bytes.length > PNG_MAGIC.length);
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals(label + " PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
	}

	private static Map<String, Object> envelope(BaseRecord graphArtifact) {
		Map<String, Object> env = PbRenderPlanUtil.readGraphEnvelope(graphArtifact);
		assertNotNull("renderGraph envelope", env);
		return env;
	}

	private static void assertCalibrationRow(BaseRecord owner, String family, String stage, int references) {
		BaseRecord row = PbCalibrationUtil.findRow(owner, OFFLINE_SERVER, family, stage);
		assertNotNull("calibration row for " + stage + " on " + OFFLINE_SERVER, row);
		Number count = row.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT);
		assertTrue("sampleCount >= 1 for " + stage + ": " + count, count != null && count.intValue() >= 1);
		Number refs = row.get(OlioFieldNames.FIELD_SD_CAL_LAST_REFERENCES);
		assertEquals("lastReferences for " + stage, references, refs == null ? -1 : refs.intValue());
	}

	// ─────────────────────────────── cases ───────────────────────────────

	@Test
	public void directReferenceBaseCarriesBothPortraits() throws Exception {
		logger.info("directReferenceBaseCarriesBothPortraits");
		Fixture f = null;
		try {
			f = fixture("p53dr");
			byte[] basePng = MaskImage.solid(W, H, new Color(90, 120, 60));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(basePng, 4242, 1500));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.DIRECT_REFERENCE, RenderPlan.STAGE_BASE, RenderPlan.STAGE_BASE);

			PlannedRender pr = render(f, plan, backend(adapter, client));

			/// One graph request, both portraits in it under the letter-suffixed tags, their bytes intact.
			assertEquals("requests sent", 1, adapter.requests.size());
			StageRequest sent = adapter.requests.get(0);
			assertEquals(RenderPlan.STAGE_BASE, sent.getStage());
			assertTrue("BASE went through the graph path", sent.isGraph());
			assertNotNull("graph inputs", sent.getInputs());
			assertArrayEquals("actora carries portrait A", f.pngA, sent.getInputs().image("actora"));
			assertArrayEquals("actorb carries portrait B", f.pngB, sent.getInputs().image("actorb"));
			assertEquals("reference provenance", List.of(f.portraitA, f.portraitB), sent.getInputs().getReferenceArtifactIds());
			assertEquals("the client was asked for one generated workflow", 1, client.requests().size());

			/// The result is the scripted image, with the BASE stage as the only one rendered.
			assertArrayEquals("final bytes are the scripted BASE image", basePng, pr.bytes);
			assertEquals(Long.valueOf(4242), pr.seed);
			assertEquals(List.of(RenderPlan.STAGE_BASE), pr.stagesRendered);
			assertTrue("no stage errors: " + pr.stageErrors, pr.stageErrors.isEmpty());
			assertEquals("degradations: " + pr.degradations, 0, pr.degradations.size());
			assertEquals("final references", List.of(f.portraitA, f.portraitB), pr.referenceArtifactObjectIds);
			assertTrue("calibration persisted", pr.calibrationPersisted);
			assertNotNull("final data.data", pr.data);
			assertPng("final", pr.bytes);

			/// Persisted: the plan, one renderGraph envelope stamped with the fixture client's version, no stage image.
			assertEquals("plan artifacts", 1, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN).size());
			List<BaseRecord> graphs = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH);
			assertEquals("renderGraph artifacts", 1, graphs.size());
			Map<String, Object> env = envelope(graphs.get(0));
			assertEquals(RenderPlan.STAGE_BASE, env.get(PbRenderPlanUtil.GRAPH_KEY_STAGE));
			assertEquals(FixtureGraphClient.FIXTURE_VERSION, env.get(PbRenderPlanUtil.GRAPH_KEY_SWARM_VERSION));
			assertEquals("graph hash matches the request graph", sent.getGraph().graphHash(), env.get(PbRenderPlanUtil.GRAPH_KEY_HASH));
			assertTrue("no stage image on a one-stage render", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE).isEmpty());
			assertCalibrationRow(f.owner, plan.pickForStage(RenderPlan.STAGE_BASE).getFamily(), RenderPlan.STAGE_BASE, 2);
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void sequentialInpaintChainsAPassOverTheBase() throws Exception {
		logger.info("sequentialInpaintChainsAPassOverTheBase");
		Fixture f = null;
		try {
			f = fixture("p53si");
			byte[] basePng = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] passPng = MaskImage.solid(W, H, new Color(160, 100, 30));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(basePng, 11, 1200), ok(passPng, 12, 900));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.SEQUENTIAL_INPAINT, RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION);

			PlannedRender pr = render(f, plan, backend(adapter, client));

			/// BASE carried only actor A; the pass carried actor B's portrait over the BASE image with a mask.
			assertEquals("requests sent", 2, adapter.requests.size());
			StageRequest base = adapter.requests.get(0);
			StageRequest pass = adapter.requests.get(1);
			assertEquals(RenderPlan.STAGE_BASE, base.getStage());
			assertTrue("BASE is a graph", base.isGraph());
			assertArrayEquals("BASE actora is portrait A", f.pngA, base.getInputs().image("actora"));
			assertNull("BASE carries no actorb", base.getInputs().image("actorb"));
			assertEquals(RenderPlan.STAGE_ACTOR_REGION, pass.getStage());
			assertTrue("pass is a graph", pass.isGraph());
			assertArrayEquals("pass actora is portrait B", f.pngB, pass.getInputs().image("actora"));
			assertArrayEquals("pass init image is the BASE output", basePng, pass.getInputs().image("init"));
			assertNotNull("pass carries a mask", pass.getInputs().image("mask"));
			assertEquals("pass prompt", PASS_PROMPT, pass.getPrompt().getPrompt());
			assertEquals("the client was asked for two generated workflows", 2, client.requests().size());
			assertTrue("second workflow request was an inpaint", client.requests().get(1).containsKey("initimage"));

			String passLabel = RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, ACTOR_B);
			assertArrayEquals("final bytes are the pass output", passPng, pr.bytes);
			assertEquals(Long.valueOf(12), pr.seed);
			assertEquals(List.of(RenderPlan.STAGE_BASE, passLabel), pr.stagesRendered);
			assertTrue("no stage errors: " + pr.stageErrors, pr.stageErrors.isEmpty());
			assertEquals("degradations: " + pr.degradations, 0, pr.degradations.size());
			assertTrue("calibration persisted", pr.calibrationPersisted);

			/// Persisted: the BASE image as the one stage artifact, two envelopes, the final chaining to the stage.
			List<BaseRecord> stageArts = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE);
			assertEquals("stage artifacts", 1, stageArts.size());
			String stageOid = oidOf(stageArts.get(0));
			assertEquals("final references: the pass's portrait and the stage it painted over", List.of(f.portraitB, stageOid), pr.referenceArtifactObjectIds);
			List<BaseRecord> graphs = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH);
			assertEquals("renderGraph artifacts", 2, graphs.size());
			assertEquals(RenderPlan.STAGE_BASE, envelope(graphs.get(0)).get(PbRenderPlanUtil.GRAPH_KEY_STAGE));
			assertEquals(passLabel, envelope(graphs.get(1)).get(PbRenderPlanUtil.GRAPH_KEY_STAGE));
			assertEquals(FixtureGraphClient.FIXTURE_VERSION, envelope(graphs.get(1)).get(PbRenderPlanUtil.GRAPH_KEY_SWARM_VERSION));
			String family = plan.pickForStage(RenderPlan.STAGE_BASE).getFamily();
			assertCalibrationRow(f.owner, family, RenderPlan.STAGE_BASE, 1);
			assertCalibrationRow(f.owner, family, RenderPlan.STAGE_ACTOR_REGION, 1);
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void failedPassIsADegradationAndTheBaseStands() throws Exception {
		logger.info("failedPassIsADegradationAndTheBaseStands");
		Fixture f = null;
		try {
			f = fixture("p53pf");
			byte[] basePng = MaskImage.solid(W, H, new Color(90, 120, 60));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(basePng, 21, 1100),
				StageResult.failed(RendererAdapter.ERROR_NO_IMAGES, 300, "{\"offline\":true}"));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.SEQUENTIAL_INPAINT, RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION);

			PlannedRender pr = render(f, plan, backend(adapter, client));

			String passLabel = RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, ACTOR_B);
			assertEquals("requests sent", 2, adapter.requests.size());
			assertArrayEquals("final bytes are the BASE image", basePng, pr.bytes);
			assertEquals(List.of(RenderPlan.STAGE_BASE), pr.stagesRendered);
			assertEquals("one stage error", 1, pr.stageErrors.size());
			assertEquals(passLabel, pr.stageErrors.get(0).get("stage"));
			assertEquals(RendererAdapter.errorCode(RendererAdapter.ERROR_NO_IMAGES), pr.stageErrors.get(0).get("code"));
			boolean actorFailed = false;
			for(String d : pr.degradations) {
				actorFailed |= d.startsWith(RenderPlan.Degradation.ACTOR_FAILED + ": ");
			}
			assertTrue("ACTOR_FAILED degradation reported: " + pr.degradations, actorFailed);
			assertEquals("final references are the BASE references", List.of(f.portraitA), pr.referenceArtifactObjectIds);
			assertTrue("the BASE stage still calibrated", pr.calibrationPersisted);

			/// The persisted plan artifact says what the render did; nothing was persisted for the failed pass.
			List<BaseRecord> plans = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN);
			assertEquals("plan artifacts", 1, plans.size());
			RenderPlan persisted = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, oidOf(plans.get(0)), orgOf(f.owner)));
			assertNotNull("persisted plan", persisted);
			boolean recorded = false;
			for(RenderPlan.Degradation d : persisted.getDegradations()) {
				recorded |= RenderPlan.Degradation.ACTOR_FAILED.equals(d.getCode()) && ACTOR_B.equals(d.getActorId());
			}
			assertTrue("ACTOR_FAILED recorded on the plan artifact for " + ACTOR_B, recorded);
			assertTrue("no stage image", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE).isEmpty());
			assertEquals("one renderGraph envelope (BASE only)", 1, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH).size());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void baseFailureIs503WithNothingButThePlanPersisted() throws Exception {
		logger.info("baseFailureIs503WithNothingButThePlanPersisted");
		Fixture f = null;
		try {
			f = fixture("p53bf");
			ScriptedAdapter adapter = new ScriptedAdapter().answer(StageResult.failed(RendererAdapter.ERROR_UNREACHABLE, 5, "{\"offline\":true}"));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.DIRECT_REFERENCE, RenderPlan.STAGE_BASE, RenderPlan.STAGE_BASE);
			/// The owner's calibration rows are shared across the class, so the check is before/after on the
			/// BASE row's sample count (absent counts as 0).
			String family = plan.pickForStage(RenderPlan.STAGE_BASE).getFamily();
			int samplesBefore = calibrationSamples(f.owner, family, RenderPlan.STAGE_BASE);

			try {
				render(f, plan, backend(adapter, client));
				fail("A BASE failure must throw PbStageFailedException");
			}
			catch(PbStageFailedException e) {
				assertEquals("UNREACHABLE is a 503", 503, e.getStatus());
				assertTrue("message names the scene: " + e.getMessage(), e.getMessage().startsWith("Render of scene 0 of " + f.slug + " failed: "));
				assertTrue("message carries the plan artifact: " + e.getMessage(), e.getMessage().contains("(plan artifact "));
				assertEquals("one stage error", 1, e.getStageErrors().size());
				assertEquals(RenderPlan.STAGE_BASE, e.getStageErrors().get(0).get("stage"));
				assertEquals(RendererAdapter.CODE_UNREACHABLE, e.getStageErrors().get(0).get("code"));
			}
			assertEquals("exactly one request was sent", 1, adapter.requests.size());

			/// The plan is the only artifact; no image, no envelope, no calibration sample.
			assertEquals("plan artifacts", 1, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN).size());
			assertTrue("no renderGraph", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH).isEmpty());
			assertTrue("no stage image", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE).isEmpty());
			assertTrue("no base image", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_BASE).isEmpty());
			assertEquals("a failed BASE records no calibration sample", samplesBefore, calibrationSamples(f.owner, family, RenderPlan.STAGE_BASE));
		}
		finally {
			cleanup(f);
		}
	}

	// ─────────────────────────────── P6-2 variants ───────────────────────────────

	@Test
	public void variantsRenderTwoFinalsOfOneAttempt() throws Exception {
		logger.info("variantsRenderTwoFinalsOfOneAttempt");
		Fixture f = null;
		try {
			f = fixture("p62v2");
			byte[] png0 = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] png1 = MaskImage.solid(W, H, new Color(60, 90, 120));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(png0, 4242, 1500), ok(png1, 4243, 1400));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.DIRECT_REFERENCE, RenderPlan.STAGE_BASE, RenderPlan.STAGE_BASE, 2);

			PlannedRender pr = render(f, plan, backend(adapter, client), 2);

			/// BASE went out twice through the graph path; the second carries the seed derived from the first's
			/// reported seed and the same two portraits.
			assertEquals("requests sent", 2, adapter.requests.size());
			StageRequest first = adapter.requests.get(0);
			StageRequest second = adapter.requests.get(1);
			assertEquals(RenderPlan.STAGE_BASE, first.getStage());
			assertEquals(RenderPlan.STAGE_BASE, second.getStage());
			assertTrue("variant 0 is a graph", first.isGraph());
			assertTrue("variant 1 is a graph", second.isGraph());
			assertEquals("variant 1 seed derives from variant 0's reported seed", PbNodeExecutor.variantSeed(4242, 1), second.getSeed());
			assertArrayEquals("variant 1 actora is portrait A", f.pngA, second.getInputs().image("actora"));
			assertArrayEquals("variant 1 actorb is portrait B", f.pngB, second.getInputs().image("actorb"));
			assertEquals("the client was asked for two generated workflows", 2, client.requests().size());

			/// Two finals, each with its own data row; variant 0 is the PlannedRender's headline final.
			assertEquals("variants", 2, pr.variants.size());
			assertArrayEquals("headline bytes are variant 0", png0, pr.bytes);
			assertEquals(Long.valueOf(4242), pr.seed);
			PlannedRender.Variant v0 = pr.variants.get(0);
			PlannedRender.Variant v1 = pr.variants.get(1);
			assertEquals(0, v0.variantIndex);
			assertEquals(1, v1.variantIndex);
			assertArrayEquals("variant 0 bytes", png0, v0.bytes);
			assertArrayEquals("variant 1 bytes", png1, v1.bytes);
			assertEquals(Long.valueOf(4242), v0.seed);
			assertEquals(Long.valueOf(4243), v1.seed);
			assertNotNull("variant 0 data", v0.data);
			assertNotNull("variant 1 data", v1.data);
			assertNotEquals("distinct data rows", oidOf(v0.data), oidOf(v1.data));
			assertEquals("variant 1 references", List.of(f.portraitA, f.portraitB), v1.referenceArtifactObjectIds);
			assertEquals("variant 1 stages", List.of(RenderPlan.STAGE_BASE), v1.stagesRendered);
			assertTrue("no stage errors: " + pr.stageErrors, pr.stageErrors.isEmpty());
			assertEquals("degradations: " + pr.degradations, 0, pr.degradations.size());
			assertTrue("calibration persisted", pr.calibrationPersisted);
			assertPng("variant 1", v1.bytes);

			/// Persisted: one plan, one envelope per variant, no stage image; renderPlanned records no finals.
			assertEquals("plan artifacts", 1, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN).size());
			List<BaseRecord> graphs = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH);
			assertEquals("renderGraph artifacts", 2, graphs.size());
			assertEquals(RenderPlan.STAGE_BASE, envelope(graphs.get(0)).get(PbRenderPlanUtil.GRAPH_KEY_STAGE));
			assertEquals(RenderPlan.STAGE_BASE, envelope(graphs.get(1)).get(PbRenderPlanUtil.GRAPH_KEY_STAGE));
			assertTrue("no stage image", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE).isEmpty());
			assertTrue("renderPlanned records no base artifact", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_BASE).isEmpty());

			Map<String, Object> described = new LinkedHashMap<>();
			pr.describe(described);
			assertEquals(Integer.valueOf(2), described.get(PbNodeExecutor.KEY_VARIANTS_PLANNED));
			assertEquals(Integer.valueOf(2), described.get(PbNodeExecutor.KEY_VARIANTS_RENDERED));
			assertCalibrationRow(f.owner, plan.pickForStage(RenderPlan.STAGE_BASE).getFamily(), RenderPlan.STAGE_BASE, 2);
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void sequentialInpaintVariantsEachChainTheirOwnPass() throws Exception {
		logger.info("sequentialInpaintVariantsEachChainTheirOwnPass");
		Fixture f = null;
		try {
			f = fixture("p62si");
			byte[] base0 = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] pass0 = MaskImage.solid(W, H, new Color(160, 100, 30));
			byte[] base1 = MaskImage.solid(W, H, new Color(60, 90, 120));
			byte[] pass1 = MaskImage.solid(W, H, new Color(30, 100, 160));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(base0, 11, 1200), ok(pass0, 12, 900), ok(base1, 13, 1100), ok(pass1, 14, 800));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.SEQUENTIAL_INPAINT, RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION, 2);

			PlannedRender pr = render(f, plan, backend(adapter, client), 2);

			/// BASE, pass, BASE, pass - each pass paints over its own variant's BASE, never the other's.
			assertEquals("requests sent", 4, adapter.requests.size());
			assertEquals(RenderPlan.STAGE_BASE, adapter.requests.get(0).getStage());
			assertEquals(RenderPlan.STAGE_ACTOR_REGION, adapter.requests.get(1).getStage());
			assertEquals(RenderPlan.STAGE_BASE, adapter.requests.get(2).getStage());
			assertEquals(RenderPlan.STAGE_ACTOR_REGION, adapter.requests.get(3).getStage());
			assertEquals("variant 1 BASE seed derives from variant 0's BASE seed", PbNodeExecutor.variantSeed(11, 1), adapter.requests.get(2).getSeed());
			assertArrayEquals("variant 0 pass paints over variant 0's BASE", base0, adapter.requests.get(1).getInputs().image("init"));
			assertArrayEquals("variant 1 pass paints over variant 1's BASE", base1, adapter.requests.get(3).getInputs().image("init"));
			assertEquals("the client was asked for four generated workflows", 4, client.requests().size());

			String passLabel = RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, ACTOR_B);
			assertEquals("variants", 2, pr.variants.size());
			assertArrayEquals("headline bytes are variant 0's pass", pass0, pr.bytes);
			assertEquals(Long.valueOf(12), pr.seed);
			PlannedRender.Variant v1 = pr.variants.get(1);
			assertEquals(1, v1.variantIndex);
			assertArrayEquals("variant 1 bytes are its pass", pass1, v1.bytes);
			assertEquals(Long.valueOf(14), v1.seed);
			assertEquals(List.of(RenderPlan.STAGE_BASE, passLabel), v1.stagesRendered);
			assertTrue("no stage errors: " + pr.stageErrors, pr.stageErrors.isEmpty());
			assertEquals("degradations: " + pr.degradations, 0, pr.degradations.size());
			assertTrue("calibration persisted", pr.calibrationPersisted);

			/// Two stage artifacts stamped with their variant, written against the same (absent) pre-batch
			/// selection so only variant 0's is selected; each final references its own stage.
			List<BaseRecord> stages = artifactsByVariant(f, PbPipelineUtil.ROLE_STAGE);
			assertEquals("stage artifacts", 2, stages.size());
			BaseRecord stage0 = stages.get(0);
			BaseRecord stage1 = stages.get(1);
			assertEquals(0, PbArtifactUtil.variantIndexOf(stage0));
			assertEquals(1, PbArtifactUtil.variantIndexOf(stage1));
			assertNull("no run, no attempt on stage 0", PbArtifactUtil.attemptOf(stage0));
			assertNull("no run, no attempt on stage 1", PbArtifactUtil.attemptOf(stage1));
			assertTrue("variant 0's stage is selected", selected(stage0));
			assertFalse("variant 1's stage is not selected", selected(stage1));
			assertEquals("variant 0 references its own stage", List.of(f.portraitB, oidOf(stage0)), pr.referenceArtifactObjectIds);
			assertEquals("variant 1 references its own stage", List.of(f.portraitB, oidOf(stage1)), v1.referenceArtifactObjectIds);
			assertEquals("renderGraph artifacts", 4, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH).size());
			String family = plan.pickForStage(RenderPlan.STAGE_BASE).getFamily();
			assertCalibrationRow(f.owner, family, RenderPlan.STAGE_BASE, 1);
			assertCalibrationRow(f.owner, family, RenderPlan.STAGE_ACTOR_REGION, 1);
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void laterVariantBaseFailureKeepsWhatRendered() throws Exception {
		logger.info("laterVariantBaseFailureKeepsWhatRendered");
		Fixture f = null;
		try {
			f = fixture("p62vf");
			byte[] base0 = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] base2 = MaskImage.solid(W, H, new Color(120, 60, 90));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(base0, 31, 1300),
				StageResult.failed(RendererAdapter.ERROR_UNREACHABLE, 5, "{\"offline\":true}"), ok(base2, 33, 1250));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.DIRECT_REFERENCE, RenderPlan.STAGE_BASE, RenderPlan.STAGE_BASE, 3);

			PlannedRender pr = render(f, plan, backend(adapter, client), 3);

			/// All three were attempted; variant 2's seed is still derived from variant 0, not renumbered.
			assertEquals("requests sent", 3, adapter.requests.size());
			assertEquals(PbNodeExecutor.variantSeed(31, 1), adapter.requests.get(1).getSeed());
			assertEquals(PbNodeExecutor.variantSeed(31, 2), adapter.requests.get(2).getSeed());

			assertEquals("finals", 2, pr.variants.size());
			assertEquals(0, pr.variants.get(0).variantIndex);
			assertEquals("the gap is kept", 2, pr.variants.get(1).variantIndex);
			assertArrayEquals("variant 2 bytes", base2, pr.variants.get(1).bytes);
			assertEquals(Long.valueOf(33), pr.variants.get(1).seed);
			assertArrayEquals("headline bytes are variant 0", base0, pr.bytes);

			assertEquals("one stage error", 1, pr.stageErrors.size());
			assertEquals(RenderPlan.STAGE_BASE, pr.stageErrors.get(0).get("stage"));
			assertEquals(RendererAdapter.CODE_UNREACHABLE, pr.stageErrors.get(0).get("code"));
			assertEquals("the stage error names its variant", Integer.valueOf(1), pr.stageErrors.get(0).get("variant"));
			boolean variantFailed = false;
			for(String d : pr.degradations) {
				variantFailed |= d.startsWith(RenderPlan.Degradation.VARIANT_FAILED + ": ");
			}
			assertTrue("VARIANT_FAILED degradation reported: " + pr.degradations, variantFailed);
			assertFalse("VARIANT_FAILED vetoes auto-approve", PbArtifactUtil.vetoingDegradations(pr.degradations).isEmpty());
			assertTrue("the rendered variants still calibrated", pr.calibrationPersisted);

			/// The persisted plan carries the degradation; only the two rendered variants left envelopes.
			List<BaseRecord> plans = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN);
			assertEquals("plan artifacts", 1, plans.size());
			RenderPlan persisted = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, oidOf(plans.get(0)), orgOf(f.owner)));
			assertNotNull("persisted plan", persisted);
			boolean recorded = false;
			for(RenderPlan.Degradation d : persisted.getDegradations()) {
				recorded |= RenderPlan.Degradation.VARIANT_FAILED.equals(d.getCode());
			}
			assertTrue("VARIANT_FAILED recorded on the plan artifact", recorded);
			assertEquals("renderGraph artifacts", 2, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_GRAPH).size());

			Map<String, Object> described = new LinkedHashMap<>();
			pr.describe(described);
			assertEquals(Integer.valueOf(3), described.get(PbNodeExecutor.KEY_VARIANTS_PLANNED));
			assertEquals(Integer.valueOf(2), described.get(PbNodeExecutor.KEY_VARIANTS_RENDERED));
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void runRecordsVariantsAsOneAttempt() throws Exception {
		logger.info("runRecordsVariantsAsOneAttempt");
		Fixture f = null;
		try {
			f = fixture("p62rn");
			prepareRun(f, "run", null);
			byte[] png0 = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] png1 = MaskImage.solid(W, H, new Color(60, 90, 120));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(png0, 7001, 800), ok(png1, 7002, 700));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();

			/// STANDARD (variants 2) as a request override over a style with no preset: the override wins.
			Map<String, Object> out = PbNodeExecutor.executeNodeRun(f.owner, f.book, f.workflow, f.node, f.sdu,
				new PlanOptions(null, false, PbQualityPresetEnumType.STANDARD, null), null, backend(adapter, client));
			assertNotNull("run DTO", out);
			assertEquals("run status", PbRunStatusEnumType.COMPLETED.toString(), status(out.get("runStatus")));
			String runOid = (String) out.get("runObjectId");
			assertNotNull("run objectId", runOid);
			assertEquals("a scene with no cast and no location plans TEXT_ONLY", PbStrategyEnumType.TEXT_ONLY.name(), out.get("strategy"));
			assertEquals(2, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)));
			assertEquals(2, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			/// STANDARD asks for CANONICAL visual QA (P8-4); with no provider the attempt carries exactly that one
			/// waived degradation and nothing else, and still auto-approves below.
			List<String> degradations = PbNodeExecutor.degradationsOf(out);
			assertEquals("only the waived QA_UNAVAILABLE: " + degradations,
				List.of(RenderPlan.Degradation.QA_UNAVAILABLE + ": no visual-QA provider is available (" + NO_QA_NOTE + ")"), degradations);
			assertEquals("qaMode is reported", QualityPresets.QA_CANONICAL, out.get(PbNodeExecutor.KEY_QA_MODE));

			/// The plain T2I path: variant 0 random, variant 1 derived from variant 0's reported seed.
			assertEquals("requests sent", 2, adapter.requests.size());
			assertFalse("TEXT_ONLY is not a graph", adapter.requests.get(0).isGraph());
			assertFalse("TEXT_ONLY variant 1 is not a graph", adapter.requests.get(1).isGraph());
			assertEquals(StageRequest.SEED_RANDOM, adapter.requests.get(0).getSeed());
			assertEquals(PbNodeExecutor.variantSeed(7001, 1), adapter.requests.get(1).getSeed());

			/// variants[] on the DTO: variant 0 is the headline artifact and the only selected one.
			Object vs = out.get(PbNodeExecutor.KEY_VARIANTS);
			assertTrue("variants is a list: " + vs, vs instanceof List);
			List<?> variants = (List<?>) vs;
			assertEquals("variants", 2, variants.size());
			Map<?, ?> d0 = (Map<?, ?>) variants.get(0);
			Map<?, ?> d1 = (Map<?, ?>) variants.get(1);
			assertEquals(0, intOf(d0.get("variantIndex")));
			assertEquals(1, intOf(d1.get("variantIndex")));
			assertEquals(Boolean.TRUE, d0.get("selected"));
			assertEquals(Boolean.FALSE, d1.get("selected"));
			assertEquals("variant 0 is the DTO's artifact", out.get("artifactObjectId"), d0.get("artifactObjectId"));
			assertNotEquals("distinct artifacts", d0.get("artifactObjectId"), d1.get("artifactObjectId"));

			/// On the database: two base artifacts sharing the run as their attempt, only variant 0 selected and
			/// auto-approved (§10 rule 5 fires once per attempt, on the selected final).
			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals("base artifacts", 2, bases.size());
			BaseRecord a0 = bases.get(0);
			BaseRecord a1 = bases.get(1);
			assertEquals(0, PbArtifactUtil.variantIndexOf(a0));
			assertEquals(1, PbArtifactUtil.variantIndexOf(a1));
			assertEquals("variant 0 attempt is the run", runOid, PbArtifactUtil.attemptOf(a0));
			assertEquals("variant 1 attempt is the run", runOid, PbArtifactUtil.attemptOf(a1));
			assertTrue("variant 0 selected", selected(a0));
			assertFalse("variant 1 not selected", selected(a1));
			assertEquals(Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), out.get("reviewStatus"));
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a0));
			assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, a0.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a1));
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void jobRecordsVariantsAsOneAttempt() throws Exception {
		logger.info("jobRecordsVariantsAsOneAttempt");
		Fixture f = null;
		try {
			f = fixture("p62jb");
			prepareRun(f, "job", PbQualityPresetEnumType.STANDARD);
			byte[] png0 = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] png1 = MaskImage.solid(W, H, new Color(60, 90, 120));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(png0, 9001, 800), ok(png1, 9002, 700));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();

			/// The book's style says STANDARD (variants 2); the job renders the node with default options.
			Map<String, Object> dto = PbRenderJobUtil.submit(f.owner, f.book, f.workflow, List.of(Long.valueOf(idOf(f.node))), f.sdu, OFFLINE_SERVER,
				PbRenderJobUtil.KIND_RENDER, true, backend(adapter, client));
			assertNotNull("submit DTO", dto);
			String jobId = (String) dto.get("jobId");
			String runOid = (String) dto.get("runObjectId");
			assertNotNull("jobId", jobId);
			assertNotNull("runObjectId", runOid);
			AsyncJob job = awaitTerminal(f.owner, jobId, JOB_TIMEOUT_MS);
			assertEquals("job status (" + job.getResult() + ")", AsyncJob.Status.COMPLETED, job.getStatus());

			Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
			assertNotNull("job result", result);
			List<?> nodes = (List<?>) result.get("nodes");
			assertNotNull("nodes", nodes);
			assertEquals("one node outcome", 1, nodes.size());
			Map<?, ?> outcome = (Map<?, ?>) nodes.get(0);
			assertEquals(Boolean.TRUE, outcome.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			Object vs = outcome.get(PbNodeExecutor.KEY_VARIANTS);
			assertTrue("outcome variants is a list: " + outcome, vs instanceof List);
			List<?> variants = (List<?>) vs;
			assertEquals("outcome variants", 2, variants.size());
			Map<?, ?> d0 = (Map<?, ?>) variants.get(0);
			Map<?, ?> d1 = (Map<?, ?>) variants.get(1);
			assertEquals(0, intOf(d0.get("variantIndex")));
			assertEquals(1, intOf(d1.get("variantIndex")));
			assertEquals(Boolean.TRUE, d0.get("selected"));
			assertEquals(Boolean.FALSE, d1.get("selected"));
			assertEquals("variant 0 is the outcome's artifact", outcome.get("artifactObjectId"), d0.get("artifactObjectId"));

			assertEquals("requests sent", 2, adapter.requests.size());
			assertEquals(PbNodeExecutor.variantSeed(9001, 1), adapter.requests.get(1).getSeed());

			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals("base artifacts", 2, bases.size());
			assertEquals("variant 0 attempt is the job's run", runOid, PbArtifactUtil.attemptOf(bases.get(0)));
			assertEquals("variant 1 attempt is the job's run", runOid, PbArtifactUtil.attemptOf(bases.get(1)));
			assertEquals(0, PbArtifactUtil.variantIndexOf(bases.get(0)));
			assertEquals(1, PbArtifactUtil.variantIndexOf(bases.get(1)));
			assertTrue("variant 0 selected", selected(bases.get(0)));
			assertFalse("variant 1 not selected", selected(bases.get(1)));
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(bases.get(0)));
			assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(bases.get(1)));
		}
		finally {
			cleanup(f);
		}
	}

	/// The owner's persisted sample count for (offline server, family, stage); 0 when there is no row.
	private static int calibrationSamples(BaseRecord owner, String family, String stage) {
		BaseRecord row = PbCalibrationUtil.findRow(owner, OFFLINE_SERVER, family, stage);
		if(row == null) {
			return 0;
		}
		Number count = row.get(OlioFieldNames.FIELD_SD_CAL_SAMPLE_COUNT);
		return (count == null ? 0 : count.intValue());
	}
}
