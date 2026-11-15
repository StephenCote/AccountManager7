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
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
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
 * <p>
 * §24 I-6 (212 e): the {@code variants} override and the gap fill - {@link #runHonoursVariantsOverride},
 * {@link #variantsOverrideOutOfRangeIs400}, {@link #runFillsVariantFailedGapOnSameAttempt},
 * {@link #differingVariantsOverrideStartsFreshAttemptNotFill}.
 * <p>
 * §24 I-8 (design §22, 219 e): the background-only regenerate - {@link #backgroundRegenReusesActorRegionSeed}
 * (SEQUENTIAL_INPAINT: fresh BASE, actor pass re-run on the recorded seed, new final supersedes the old),
 * {@link #backgroundRegenWithoutSelectedRenderIs409}, {@link #backgroundRegenPreI8FinalReportsActorSeedNew}
 * (no label on record -> {@code ACTOR_SEED_NEW}, accepted) and {@link #jobRegeneratesBackgroundOfTextOnlyScene}
 * (the {@code pb.regenerateBackground} job on a plain T2I node -> {@code BACKGROUND_REGEN_NO_REGIONS}, auto-approve vetoed).
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

	/// A list that is empty on the wire: JSONUtil.exportObject (Include.NON_EMPTY) drops empty lists, so a
	/// wire-read map has the key absent (null) or, when a serializer kept it, an empty list.
	private static boolean emptyOnTheWire(Object v) {
		return v == null || (v instanceof List && ((List<?>) v).isEmpty());
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

	// ─────────────────────────────── §24 I-6: variants override + gap resume ───────────────────────────────

	/// §24 I-6 (212 c): a request `variants` of 3 over STANDARD (whose own count is 2) renders three finals of one
	/// attempt - the override replaces the preset's count on the plan, and the later variants still derive their
	/// seeds from variant 0's reported seed.
	@Test
	public void runHonoursVariantsOverride() throws Exception {
		logger.info("runHonoursVariantsOverride");
		Fixture f = null;
		try {
			f = fixture("p66ov");
			prepareRun(f, "ovr", null);
			byte[] png0 = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] png1 = MaskImage.solid(W, H, new Color(60, 90, 120));
			byte[] png2 = MaskImage.solid(W, H, new Color(120, 60, 90));
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(png0, 7101, 800), ok(png1, 7102, 700), ok(png2, 7103, 650));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();

			Map<String, Object> out = PbNodeExecutor.executeNodeRun(f.owner, f.book, f.workflow, f.node, f.sdu,
				new PlanOptions(null, false, PbQualityPresetEnumType.STANDARD, null, null, Integer.valueOf(3)), null, backend(adapter, client));
			assertNotNull("run DTO", out);
			assertEquals("run status", PbRunStatusEnumType.COMPLETED.toString(), status(out.get("runStatus")));
			String runOid = (String) out.get("runObjectId");
			assertNotNull("run objectId", runOid);
			assertEquals("the override replaces STANDARD's 2", 3, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)));
			assertEquals(3, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertNull("an ordinary attempt resumes nothing", out.get(PbNodeExecutor.KEY_RESUMED_ATTEMPT));
			assertEquals("requests sent", 3, adapter.requests.size());
			assertEquals(StageRequest.SEED_RANDOM, adapter.requests.get(0).getSeed());
			assertEquals(PbNodeExecutor.variantSeed(7101, 1), adapter.requests.get(1).getSeed());
			assertEquals(PbNodeExecutor.variantSeed(7101, 2), adapter.requests.get(2).getSeed());

			/// The persisted plan carries the overridden count, so a later gap fill knows what this attempt planned.
			List<BaseRecord> plans = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN);
			assertEquals("plan artifacts", 1, plans.size());
			RenderPlan persisted = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, oidOf(plans.get(0)), orgOf(f.owner)));
			assertNotNull("persisted plan", persisted);
			assertEquals("plan preset variants", 3, persisted.getPreset().getVariants());

			List<?> variants = (List<?>) out.get(PbNodeExecutor.KEY_VARIANTS);
			assertEquals("variants", 3, variants.size());
			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals("base artifacts", 3, bases.size());
			for(int i = 0; i < 3; i++) {
				assertEquals(i, PbArtifactUtil.variantIndexOf(bases.get(i)));
				assertEquals("variant " + i + " attempt is the run", runOid, PbArtifactUtil.attemptOf(bases.get(i)));
				assertEquals("only variant 0 selected", i == 0, selected(bases.get(i)));
			}
			assertEquals(Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(bases.get(0)));
		}
		finally {
			cleanup(f);
		}
	}

	/// §24 I-6 (212 c): the facade's range check - 1..maxVariants of the preset the attempt renders with, else a
	/// 400 naming the range, the preset and the value; never clamped. The ceilings are qualityPresets.json's
	/// (DRAFT 4, STANDARD 6, FINAL 8); null is "no override".
	@Test
	public void variantsOverrideOutOfRangeIs400() throws Exception {
		logger.info("variantsOverrideOutOfRangeIs400");
		Fixture f = null;
		try {
			f = fixture("p66rg");
			BaseRecord book = PbBookUtil.readBook(f.owner, f.bookOid, orgOf(f.owner));
			assertNotNull("book", book);
			assertNull("null is no override", PbServiceFacade.validateVariants(f.owner, book, PbQualityPresetEnumType.STANDARD, null));
			assertEquals(Integer.valueOf(1), PbServiceFacade.validateVariants(f.owner, book, PbQualityPresetEnumType.STANDARD, Integer.valueOf(1)));
			assertEquals(Integer.valueOf(6), PbServiceFacade.validateVariants(f.owner, book, PbQualityPresetEnumType.STANDARD, Integer.valueOf(6)));
			assertEquals(Integer.valueOf(8), PbServiceFacade.validateVariants(f.owner, book, PbQualityPresetEnumType.FINAL, Integer.valueOf(8)));
			assertEquals(Integer.valueOf(4), PbServiceFacade.validateVariants(f.owner, book, PbQualityPresetEnumType.DRAFT, Integer.valueOf(4)));
			assertVariants400(book, f.owner, PbQualityPresetEnumType.STANDARD, 7, "variants must be an integer from 1 to 6 for preset STANDARD; got 7");
			assertVariants400(book, f.owner, PbQualityPresetEnumType.STANDARD, 0, "variants must be an integer from 1 to 6 for preset STANDARD; got 0");
			assertVariants400(book, f.owner, PbQualityPresetEnumType.STANDARD, -2, "variants must be an integer from 1 to 6 for preset STANDARD; got -2");
			assertVariants400(book, f.owner, PbQualityPresetEnumType.FINAL, 9, "variants must be an integer from 1 to 8 for preset FINAL; got 9");
			assertVariants400(book, f.owner, PbQualityPresetEnumType.DRAFT, 5, "variants must be an integer from 1 to 4 for preset DRAFT; got 5");
			/// No preset override: the book's own effective preset (no style here, so the default) still bounds it.
			QualityPresets.Preset effective = PbRenderPlanUtil.effectivePreset(f.owner, book, PlanOptions.NONE);
			assertNotNull("effective preset", effective);
			assertVariants400(book, f.owner, null, effective.getMaxVariants() + 1, "variants must be an integer from 1 to "
				+ effective.getMaxVariants() + " for preset " + effective.getName().name() + "; got " + (effective.getMaxVariants() + 1));
		}
		finally {
			cleanup(f);
		}
	}

	private static void assertVariants400(BaseRecord book, BaseRecord owner, PbQualityPresetEnumType preset, int variants, String message) {
		try {
			Integer v = PbServiceFacade.validateVariants(owner, book, preset, Integer.valueOf(variants));
			fail("variants " + variants + " over " + preset + " must be refused, got " + v);
		}
		catch(PictureBookException e) {
			assertEquals("status for variants " + variants, 400, e.getStatus());
			assertEquals("message for variants " + variants, message, e.getMessage());
		}
	}

	/// §24 I-6 (212 e): an attempt of 3 whose middle variant failed leaves variantIndex 1 empty and VARIANT_FAILED
	/// vetoing auto-approve. The next run of the same (still current, unpinned) node fills exactly that index under
	/// the SAME attempt with the seed the attempt would have used, keeps the two surviving finals, reports the whole
	/// attempt (kept + filled) and - the fill's plan carrying no VARIANT_FAILED - auto-approves variant 0. A third
	/// run finds no gap and starts a fresh attempt.
	@Test
	public void runFillsVariantFailedGapOnSameAttempt() throws Exception {
		logger.info("runFillsVariantFailedGapOnSameAttempt");
		Fixture f = null;
		try {
			f = fixture("p66gf");
			prepareRun(f, "gap", null);
			long orgId = orgOf(f.owner);
			PlanOptions three = new PlanOptions(null, false, PbQualityPresetEnumType.STANDARD, null, null, Integer.valueOf(3));
			byte[] png0 = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] png2 = MaskImage.solid(W, H, new Color(120, 60, 90));
			ScriptedAdapter first = new ScriptedAdapter().answer(ok(png0, 7201, 800),
				StageResult.failed(RendererAdapter.ERROR_UNREACHABLE, 5, "{\"offline\":true}"), ok(png2, 7203, 650));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();

			/// Attempt 1: 3 planned, variant 1 fails, 2 kept, auto-approve vetoed, node current (DONE_UNVERIFIED).
			Map<String, Object> out1 = PbNodeExecutor.executeNodeRun(f.owner, f.book, f.workflow, f.node, f.sdu, three, null, backend(first, client));
			assertEquals("run 1 status", PbRunStatusEnumType.COMPLETED.toString(), status(out1.get("runStatus")));
			String attempt1 = (String) out1.get("runObjectId");
			assertNotNull("run 1 objectId", attempt1);
			assertEquals("requests sent by run 1", 3, first.requests.size());
			assertEquals(3, intOf(out1.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)));
			assertEquals(2, intOf(out1.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertNull("run 1 is not a fill", out1.get(PbNodeExecutor.KEY_RESUMED_ATTEMPT));
			assertFalse("VARIANT_FAILED vetoes auto-approve on run 1",
				PbArtifactUtil.vetoingDegradations(PbNodeExecutor.degradationsOf(out1)).isEmpty());
			assertNotEquals("run 1 is not auto-approved", Boolean.TRUE, out1.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			List<BaseRecord> bases1 = baseArtifacts(f);
			assertEquals("base artifacts after run 1", 2, bases1.size());
			assertEquals(0, PbArtifactUtil.variantIndexOf(bases1.get(0)));
			assertEquals("the gap is kept, not renumbered", 2, PbArtifactUtil.variantIndexOf(bases1.get(1)));
			assertEquals(Long.valueOf(7201), bases1.get(0).get(OlioFieldNames.FIELD_PB_SEED));
			assertTrue("variant 0 selected", selected(bases1.get(0)));
			assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(bases1.get(0)));
			String v0Oid = oidOf(bases1.get(0));
			String v2Oid = oidOf(bases1.get(1));
			BaseRecord node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			assertEquals("node current after run 1", PbNodeStatusEnumType.DONE_UNVERIFIED, node.getEnum(OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertNotEquals("run 1 did not pin the node", Boolean.TRUE, node.get(OlioFieldNames.FIELD_PB_PINNED));

			/// Attempt 1 resumed: the same options on the re-read node render exactly variant 1 with attempt 1's seed.
			byte[] png1 = MaskImage.solid(W, H, new Color(60, 90, 120));
			ScriptedAdapter second = new ScriptedAdapter().answer(ok(png1, 7202, 700));
			Map<String, Object> out2 = PbNodeExecutor.executeNodeRun(f.owner, f.book, f.workflow, node, f.sdu, three, null, backend(second, client));
			assertEquals("run 2 status", PbRunStatusEnumType.COMPLETED.toString(), status(out2.get("runStatus")));
			String run2 = (String) out2.get("runObjectId");
			assertNotNull("run 2 objectId", run2);
			assertNotEquals("run 2 is its own run", attempt1, run2);
			assertEquals("exactly the gap is rendered", 1, second.requests.size());
			assertEquals("the fill takes attempt 1's derived seed", PbNodeExecutor.variantSeed(7201, 1), second.requests.get(0).getSeed());
			assertEquals("the fill resumes attempt 1", attempt1, out2.get(PbNodeExecutor.KEY_RESUMED_ATTEMPT));
			assertEquals("filled indexes", List.of(Integer.valueOf(1)), out2.get(PbNodeExecutor.KEY_FILLED_VARIANT_INDEXES));
			assertEquals("planned is what attempt 1 planned", 3, intOf(out2.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)));
			assertEquals("rendered is the whole attempt", 3, intOf(out2.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			List<String> deg2 = PbNodeExecutor.degradationsOf(out2);
			assertTrue("the fill's plan carries no VARIANT_FAILED: " + deg2, PbArtifactUtil.vetoingDegradations(deg2).isEmpty());
			assertFalse("CANONICAL judges nothing on a fill, so no QA_UNAVAILABLE either: " + deg2,
				String.join("|", deg2).contains(RenderPlan.Degradation.QA_UNAVAILABLE));
			assertEquals("the mode still orders the kept verdicts", QualityPresets.QA_CANONICAL, out2.get(PbNodeExecutor.KEY_QA_MODE));

			/// The DTO describes attempt 1 as a whole: kept 0 and 2 around the filled 1, headline = kept variant 0.
			List<?> variants = (List<?>) out2.get(PbNodeExecutor.KEY_VARIANTS);
			assertEquals("variants", 3, variants.size());
			Map<?, ?> d0 = (Map<?, ?>) variants.get(0);
			Map<?, ?> d1 = (Map<?, ?>) variants.get(1);
			Map<?, ?> d2 = (Map<?, ?>) variants.get(2);
			assertEquals(0, intOf(d0.get("variantIndex")));
			assertEquals(1, intOf(d1.get("variantIndex")));
			assertEquals(2, intOf(d2.get("variantIndex")));
			assertEquals(Boolean.TRUE, d0.get("kept"));
			assertEquals(Boolean.FALSE, d1.get("kept"));
			assertEquals(Boolean.TRUE, d2.get("kept"));
			assertEquals("kept variant 0 is attempt 1's", v0Oid, d0.get("artifactObjectId"));
			assertEquals("kept variant 2 is attempt 1's", v2Oid, d2.get("artifactObjectId"));
			assertEquals("the DTO's artifact is the kept variant 0", v0Oid, out2.get("artifactObjectId"));
			assertEquals(Boolean.TRUE, d0.get("selected"));
			assertEquals(Boolean.FALSE, d1.get("selected"));
			assertEquals(Boolean.FALSE, d2.get("selected"));

			/// On the database: three finals of attempt 1, the fill at index 1 under attempt 1 (not run 2), unselected,
			/// and variant 0 now auto-approved by the fill's clean attempt.
			List<BaseRecord> bases2 = baseArtifacts(f);
			assertEquals("base artifacts after the fill", 3, bases2.size());
			for(int i = 0; i < 3; i++) {
				assertEquals(i, PbArtifactUtil.variantIndexOf(bases2.get(i)));
				assertEquals("variant " + i + " belongs to attempt 1", attempt1, PbArtifactUtil.attemptOf(bases2.get(i)));
				assertEquals("only variant 0 selected", i == 0, selected(bases2.get(i)));
			}
			assertEquals("kept variant 0 is the same row", v0Oid, oidOf(bases2.get(0)));
			assertEquals("kept variant 2 is the same row", v2Oid, oidOf(bases2.get(2)));
			assertEquals("the fill's artifact is what the DTO names", oidOf(bases2.get(1)), d1.get("artifactObjectId"));
			assertEquals(Long.valueOf(7202), bases2.get(1).get(OlioFieldNames.FIELD_PB_SEED));
			assertNull("the fill supersedes what attempt 1 superseded (nothing), not its own variant 0",
				bases2.get(1).get(OlioFieldNames.FIELD_PB_SUPERSEDES));
			assertEquals(Boolean.TRUE, out2.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertEquals("auto-approve picks the kept variant 0", v0Oid, out2.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(bases2.get(0)));
			assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, bases2.get(0).get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(bases2.get(1)));
			assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(bases2.get(2)));
			List<BaseRecord> plans = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN);
			assertEquals("the fill persists its own plan artifact", 2, plans.size());
			RenderPlan fillPlan = PbRenderPlanUtil.readPlanArtifact(PbArtifactUtil.readArtifact(f.owner, oidOf(plans.get(1)), orgId));
			assertEquals("the fill's plan still says 3", 3, fillPlan.getPreset().getVariants());
			for(RenderPlan.Degradation d : fillPlan.getDegradations()) {
				assertNotEquals("the fill's plan records no VARIANT_FAILED", RenderPlan.Degradation.VARIANT_FAILED, d.getCode());
			}

			/// Attempt 2: the node is now pinned by auto-approve (a facade 409); the executor itself, handed the
			/// re-read node, finds no gap (attempt 1 is complete) and starts a fresh attempt of 3.
			node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			assertEquals("auto-approve pinned the node", Boolean.TRUE, node.get(OlioFieldNames.FIELD_PB_PINNED));
			assertNull("no gap left on attempt 1", PbNodeExecutor.findGapFill(
				PbPipelineUtil.openNodeGraph(f.owner, f.book, f.workflow, node, true, null), node));
		}
		finally {
			cleanup(f);
		}
	}

	/// §24 I-6 (212 e): a `variants` override that differs from what the gapped attempt planned is a different
	/// attempt - the executor starts fresh (4 renders, new attempt) instead of filling the hole.
	@Test
	public void differingVariantsOverrideStartsFreshAttemptNotFill() throws Exception {
		logger.info("differingVariantsOverrideStartsFreshAttemptNotFill");
		Fixture f = null;
		try {
			f = fixture("p66df");
			prepareRun(f, "dif", null);
			long orgId = orgOf(f.owner);
			byte[] png0 = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] png2 = MaskImage.solid(W, H, new Color(120, 60, 90));
			ScriptedAdapter first = new ScriptedAdapter().answer(ok(png0, 7301, 800),
				StageResult.failed(RendererAdapter.ERROR_UNREACHABLE, 5, "{\"offline\":true}"), ok(png2, 7303, 650));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			Map<String, Object> out1 = PbNodeExecutor.executeNodeRun(f.owner, f.book, f.workflow, f.node, f.sdu,
				new PlanOptions(null, false, PbQualityPresetEnumType.STANDARD, null, null, Integer.valueOf(3)), null, backend(first, client));
			String attempt1 = (String) out1.get("runObjectId");
			assertEquals("base artifacts after run 1", 2, baseArtifacts(f).size());
			BaseRecord node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);

			ScriptedAdapter second = new ScriptedAdapter().answer(ok(png0, 7401, 800), ok(png2, 7402, 700),
				ok(png0, 7403, 650), ok(png2, 7404, 600));
			Map<String, Object> out2 = PbNodeExecutor.executeNodeRun(f.owner, f.book, f.workflow, node, f.sdu,
				new PlanOptions(null, false, PbQualityPresetEnumType.STANDARD, null, null, Integer.valueOf(4)), null, backend(second, client));
			String run2 = (String) out2.get("runObjectId");
			assertEquals("run 2 status", PbRunStatusEnumType.COMPLETED.toString(), status(out2.get("runStatus")));
			assertEquals("a fresh attempt renders all four", 4, second.requests.size());
			assertEquals(StageRequest.SEED_RANDOM, second.requests.get(0).getSeed());
			assertNull("not a fill", out2.get(PbNodeExecutor.KEY_RESUMED_ATTEMPT));
			assertEquals(4, intOf(out2.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)));
			assertEquals(4, intOf(out2.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals("attempt 1's two plus attempt 2's four", 6, bases.size());
			int ofRun2 = 0;
			for(BaseRecord a : bases) {
				if(run2.equals(PbArtifactUtil.attemptOf(a))) {
					ofRun2++;
				}
				else {
					assertEquals("every other final is attempt 1's", attempt1, PbArtifactUtil.attemptOf(a));
				}
			}
			assertEquals("four finals of attempt 2", 4, ofRun2);
		}
		finally {
			cleanup(f);
		}
	}

	/// §24 I-8: record {@code pr}'s final the way {@code executeRenderPlan} does on the non-run path (single variant,
	/// no attempt; it supersedes the role's current selection and takes it), re-read in full.
	private static BaseRecord recordFinal(Fixture f, PlannedRender pr, String requestJson) {
		BaseRecord a = PbPipelineUtil.recordImage(f.graph, f.node, PbPipelineUtil.ROLE_BASE, PbArtifactTypeEnumType.IMAGE,
			pr.data, pr.bytes, "image/png", pr.seed, pr.frozen, requestJson, pr.referenceArtifactObjectIds, f.sdu.getApiType(),
			pr.durationMs, pr.backendGraph, (String) null);
		assertNotNull("final recorded", a);
		BaseRecord full = PbArtifactUtil.readArtifact(f.owner, oidOf(a), orgOf(f.owner));
		assertNotNull("final re-read", full);
		return full;
	}

	/// §24 I-8 (design §22): SEQUENTIAL_INPAINT rendered once and recorded; then the same plan under
	/// {@code PlanOptions.backgroundRegen} -> a fresh BASE draw, actor B's ACTOR_REGION pass re-run with the seed the
	/// recorded final carries (found through its {@code stageLabel}), one variant, the DTO naming the final it
	/// regenerated from and the actor that kept its seed, and the new final superseding the old selection.
	@Test
	public void backgroundRegenReusesActorRegionSeed() throws Exception {
		logger.info("backgroundRegenReusesActorRegionSeed");
		Fixture f = null;
		try {
			f = fixture("p68bg");
			long orgId = orgOf(f.owner);
			String passLabel = RenderPlan.actorStageKey(RenderPlan.STAGE_ACTOR_REGION, ACTOR_B);
			byte[] basePng = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] passPng = MaskImage.solid(W, H, new Color(160, 100, 30));
			ScriptedAdapter first = new ScriptedAdapter().answer(ok(basePng, 11, 1200), ok(passPng, 12, 900));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.SEQUENTIAL_INPAINT, RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION);

			/// Render 1, the ordinary path; the final recorded the way executeRenderPlan records it.
			PlannedRender pr1 = render(f, plan, backend(first, client));
			assertNull("an ordinary render is no regenerate", pr1.bg);
			BaseRecord final1 = recordFinal(f, pr1, pr1.requestJson);
			String final1Oid = oidOf(final1);
			assertTrue("final 1 selected", selected(final1));
			assertEquals("the final carries its last stage's label", passLabel, PbArtifactUtil.stageLabelOf(final1));
			assertEquals(Long.valueOf(12), final1.get(OlioFieldNames.FIELD_PB_SEED));
			List<BaseRecord> stages1 = artifactsByVariant(f, PbPipelineUtil.ROLE_STAGE);
			assertEquals("stage artifacts after render 1", 1, stages1.size());
			assertEquals("the stage artifact carries BASE", RenderPlan.STAGE_BASE, PbArtifactUtil.stageLabelOf(stages1.get(0)));

			/// Render 2, the regenerate: BASE is a fresh draw, actor B's pass is re-run on the seed final 1 carries.
			byte[] base2Png = MaskImage.solid(W, H, new Color(40, 70, 140));
			byte[] pass2Png = MaskImage.solid(W, H, new Color(200, 140, 40));
			ScriptedAdapter second = new ScriptedAdapter().answer(ok(base2Png, 31, 1000), ok(pass2Png, 12, 800));
			f.graph.setPlanOptions(PlanOptions.NONE.withBackgroundRegen(true));
			PlannedRender pr2 = render(f, plan, backend(second, client));

			assertEquals("requests sent by the regenerate", 2, second.requests.size());
			StageRequest base2 = second.requests.get(0);
			StageRequest pass2 = second.requests.get(1);
			assertEquals(RenderPlan.STAGE_BASE, base2.getStage());
			assertNotEquals("BASE is a fresh draw, not the actor seed", 12, base2.getSeed());
			assertEquals(RenderPlan.STAGE_ACTOR_REGION, pass2.getStage());
			assertEquals("the pass reuses actor B's recorded seed", 12, pass2.getSeed());
			assertArrayEquals("the pass paints over the NEW base", base2Png, pass2.getInputs().image("init"));
			assertNotNull("regenerate", pr2.bg);
			assertEquals(final1Oid, pr2.bg.fromArtifactObjectId);
			assertEquals(List.of(ACTOR_B), pr2.bg.reused);
			assertTrue("no fresh actor seed: " + pr2.bg.fresh, pr2.bg.fresh.isEmpty());
			assertEquals(1, pr2.variantsPlanned);
			assertEquals(1, pr2.variants.size());
			assertEquals(List.of(RenderPlan.STAGE_BASE, passLabel), pr2.stagesRendered);
			String joined = String.join("|", pr2.degradations);
			assertFalse("no ACTOR_SEED_NEW: " + joined, joined.contains(RenderPlan.Degradation.ACTOR_SEED_NEW));
			assertFalse("no BACKGROUND_REGEN_NO_REGIONS: " + joined, joined.contains(RenderPlan.Degradation.BACKGROUND_REGEN_NO_REGIONS));
			Map<String, Object> out = new LinkedHashMap<>();
			pr2.describe(out);
			assertEquals(PbNodeExecutor.REGENERATE_BACKGROUND, out.get(PbNodeExecutor.KEY_REGENERATE));
			assertEquals(final1Oid, out.get(PbNodeExecutor.KEY_REGENERATED_FROM));
			assertEquals(List.of(ACTOR_B), out.get(PbNodeExecutor.KEY_ACTOR_SEEDS_REUSED));
			assertEquals(List.of(), out.get(PbNodeExecutor.KEY_ACTOR_SEEDS_NEW));
			assertEquals(1, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)));

			/// Persisted: a second plan artifact, a second BASE stage artifact, and the regenerated final takes the
			/// selection from final 1 (superseding it; final 1 stays).
			assertEquals("plan artifacts", 2, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN).size());
			assertEquals("stage artifacts after the regenerate", 2, PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE).size());
			BaseRecord final2 = recordFinal(f, pr2, pr2.requestJson);
			assertTrue("final 2 selected", selected(final2));
			assertEquals(passLabel, PbArtifactUtil.stageLabelOf(final2));
			BaseRecord sup = final2.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
			assertNotNull("final 2 supersedes final 1", sup);
			assertEquals(final1Oid, sup.get(FieldNames.FIELD_OBJECT_ID));
			final1 = PbArtifactUtil.readArtifact(f.owner, final1Oid, orgId);
			assertNotNull("final 1 is kept", final1);
			assertFalse("final 1 no longer selected", selected(final1));
		}
		finally {
			cleanup(f);
		}
	}

	/// §24 I-8: a regenerate on a node with no selected {@code base} final is a 409 before anything is persisted -
	/// no plan artifact, no request to the renderer.
	@Test
	public void backgroundRegenWithoutSelectedRenderIs409() throws Exception {
		logger.info("backgroundRegenWithoutSelectedRenderIs409");
		Fixture f = null;
		try {
			f = fixture("p68nr");
			ScriptedAdapter adapter = new ScriptedAdapter();
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.SEQUENTIAL_INPAINT, RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION);
			f.graph.setPlanOptions(PlanOptions.NONE.withBackgroundRegen(true));
			try {
				render(f, plan, backend(adapter, client));
				fail("a regenerate with nothing to regenerate from must be refused");
			}
			catch(PictureBookException e) {
				assertEquals("status (" + e.getMessage() + ")", 409, e.getStatus());
				assertEquals("Scene has no selected render to regenerate the background of; render it first", e.getMessage());
			}
			assertEquals("no request reached the renderer", 0, adapter.requests.size());
			assertTrue("no plan artifact persisted", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_RENDER_PLAN).isEmpty());
			assertTrue("no stage artifact persisted", PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE).isEmpty());
		}
		finally {
			cleanup(f);
		}
	}

	/// §24 I-8: a selected final recorded before I-8 carries no stage label, so no ACTOR_REGION seed is on record for
	/// actor B - the regenerate still runs, the pass draws a fresh seed, and the result says so ({@code ACTOR_SEED_NEW},
	/// accepted: same character, not the same pixels) without claiming the actors were re-rendered with the background.
	@Test
	public void backgroundRegenPreI8FinalReportsActorSeedNew() throws Exception {
		logger.info("backgroundRegenPreI8FinalReportsActorSeedNew");
		Fixture f = null;
		try {
			f = fixture("p68pi");
			byte[] basePng = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] passPng = MaskImage.solid(W, H, new Color(160, 100, 30));
			ScriptedAdapter first = new ScriptedAdapter().answer(ok(basePng, 11, 1200), ok(passPng, 12, 900));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();
			RenderPlan plan = plan(f, PbStrategyEnumType.SEQUENTIAL_INPAINT, RenderPlan.STAGE_BASE, RenderPlan.STAGE_ACTOR_REGION);
			PlannedRender pr1 = render(f, plan, backend(first, client));
			/// recorded with the raw adapter request: no stageLabel, as every final written before I-8
			BaseRecord final1 = recordFinal(f, pr1, "{\"offline\":true}");
			String final1Oid = oidOf(final1);
			assertNull("a pre-I-8 final carries no label", PbArtifactUtil.stageLabelOf(final1));

			ScriptedAdapter second = new ScriptedAdapter().answer(ok(basePng, 31, 1000), ok(passPng, 32, 800));
			f.graph.setPlanOptions(PlanOptions.NONE.withBackgroundRegen(true));
			PlannedRender pr2 = render(f, plan, backend(second, client));

			assertEquals("requests sent by the regenerate", 2, second.requests.size());
			StageRequest pass2 = second.requests.get(1);
			assertEquals(RenderPlan.STAGE_ACTOR_REGION, pass2.getStage());
			assertNotEquals("no seed on record: the pass is a fresh draw", 12, pass2.getSeed());
			assertNotNull("regenerate", pr2.bg);
			assertEquals(final1Oid, pr2.bg.fromArtifactObjectId);
			assertTrue("no actor kept its seed: " + pr2.bg.reused, pr2.bg.reused.isEmpty());
			assertEquals(List.of(ACTOR_B), pr2.bg.fresh);
			String joined = String.join("|", pr2.degradations);
			assertTrue("ACTOR_SEED_NEW for actor B: " + joined,
				joined.contains(RenderPlan.Degradation.ACTOR_SEED_NEW + ": actor " + ACTOR_B + " re-rendered with a new seed"));
			assertFalse("the pass still ran, so no BACKGROUND_REGEN_NO_REGIONS: " + joined, joined.contains(RenderPlan.Degradation.BACKGROUND_REGEN_NO_REGIONS));
			assertTrue("ACTOR_SEED_NEW is accepted by auto-approve: " + joined, PbArtifactUtil.vetoingDegradations(pr2.degradations).isEmpty());
			Map<String, Object> out = new LinkedHashMap<>();
			pr2.describe(out);
			assertEquals(List.of(), out.get(PbNodeExecutor.KEY_ACTOR_SEEDS_REUSED));
			assertEquals(List.of(ACTOR_B), out.get(PbNodeExecutor.KEY_ACTOR_SEEDS_NEW));
		}
		finally {
			cleanup(f);
		}
	}

	/// §24 I-8 through the job: a text-only scene rendered by a STANDARD job (two variants, auto-approved, which pins
	/// the node) is unpinned and regenerated by a {@code pb.regenerateBackground} job -> one fresh BASE, one final that
	/// supersedes the approved one, {@code regenerate=background} on the node outcome naming the final it came from, and
	/// - the plan having no ACTOR_REGION pass - {@code BACKGROUND_REGEN_NO_REGIONS}, which vetoes auto-approve.
	@Test
	public void jobRegeneratesBackgroundOfTextOnlyScene() throws Exception {
		logger.info("jobRegeneratesBackgroundOfTextOnlyScene");
		Fixture f = null;
		try {
			f = fixture("p68jb");
			prepareRun(f, "bgjob", PbQualityPresetEnumType.STANDARD);
			long orgId = orgOf(f.owner);
			long nodeId = Long.valueOf(idOf(f.node));
			byte[] png0 = MaskImage.solid(W, H, new Color(90, 120, 60));
			byte[] png1 = MaskImage.solid(W, H, new Color(60, 90, 120));
			ScriptedAdapter first = new ScriptedAdapter().answer(ok(png0, 9101, 800), ok(png1, 9102, 700));
			FixtureGraphClient client = new FixtureGraphClient().pruneUnusedLoaders();

			Map<String, Object> dto1 = PbRenderJobUtil.submit(f.owner, f.book, f.workflow, List.of(Long.valueOf(nodeId)), f.sdu, OFFLINE_SERVER,
				PbRenderJobUtil.KIND_RENDER, true, backend(first, client));
			AsyncJob job1 = awaitTerminal(f.owner, (String) dto1.get("jobId"), JOB_TIMEOUT_MS);
			assertEquals("job 1 status (" + job1.getResult() + ")", AsyncJob.Status.COMPLETED, job1.getStatus());
			String run1 = (String) dto1.get("runObjectId");
			List<BaseRecord> bases1 = baseArtifacts(f);
			assertEquals("base artifacts after job 1", 2, bases1.size());
			assertTrue("variant 0 selected", selected(bases1.get(0)));
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(bases1.get(0)));
			String approvedOid = oidOf(bases1.get(0));
			BaseRecord node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			assertEquals("auto-approve pinned the node", Boolean.TRUE, node.get(OlioFieldNames.FIELD_PB_PINNED));

			/// A pinned node is never re-rendered (the facade 409s before submit); the user unpins it first.
			assertTrue("unpin", PbGraphUtil.setPinned(f.owner, node, false));
			CacheUtil.clearCache();
			node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			assertNotEquals("unpinned", Boolean.TRUE, node.get(OlioFieldNames.FIELD_PB_PINNED));

			byte[] png2 = MaskImage.solid(W, H, new Color(140, 40, 70));
			ScriptedAdapter second = new ScriptedAdapter().answer(ok(png2, 9201, 650));
			Map<String, Object> dto2 = PbRenderJobUtil.submit(f.owner, f.book, f.workflow, List.of(Long.valueOf(nodeId)), f.sdu, OFFLINE_SERVER,
				PbRenderJobUtil.KIND_REGENERATE_BACKGROUND, true, backend(second, client), PlanOptions.NONE.withBackgroundRegen(true));
			assertEquals(PbRenderJobUtil.KIND_REGENERATE_BACKGROUND, dto2.get("kind"));
			assertEquals("one node planned", 1, intOf(dto2.get("plannedNodeCount")));
			String run2 = (String) dto2.get("runObjectId");
			assertNotNull("run 2", run2);
			assertNotEquals("run 2 is its own run", run1, run2);
			AsyncJob job2 = awaitTerminal(f.owner, (String) dto2.get("jobId"), JOB_TIMEOUT_MS);
			assertEquals("job 2 status (" + job2.getResult() + ")", AsyncJob.Status.COMPLETED, job2.getStatus());

			/// The preset says 2; the regenerate renders exactly one fresh BASE.
			assertEquals("requests sent by the regenerate", 1, second.requests.size());
			assertEquals(RenderPlan.STAGE_BASE, second.requests.get(0).getStage());
			assertEquals("BASE is a fresh draw", StageRequest.SEED_RANDOM, second.requests.get(0).getSeed());

			Map<?, ?> result = JSONUtil.importObject(job2.getResult(), Map.class);
			assertNotNull("job 2 result", result);
			List<?> nodes = (List<?>) result.get("nodes");
			assertEquals("one node outcome", 1, nodes.size());
			Map<?, ?> outcome = (Map<?, ?>) nodes.get(0);
			assertEquals(PbNodeExecutor.REGENERATE_BACKGROUND, outcome.get(PbNodeExecutor.KEY_REGENERATE));
			assertEquals("regenerated from the approved final", approvedOid, outcome.get(PbNodeExecutor.KEY_REGENERATED_FROM));
			assertEquals(1, intOf(outcome.get(PbNodeExecutor.KEY_VARIANTS_PLANNED)));
			assertEquals(1, intOf(outcome.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertEquals("one variant on the outcome", 1, ((List<?>) outcome.get(PbNodeExecutor.KEY_VARIANTS)).size());
			/// The job result is the wire (JSONUtil.exportObject, Include.NON_EMPTY): an empty list is dropped, so "no actor
			/// kept or lost its seed" reads as the key absent or empty - never a non-empty list.
			assertTrue("no actor seed reused on the wire: " + outcome.get(PbNodeExecutor.KEY_ACTOR_SEEDS_REUSED),
				emptyOnTheWire(outcome.get(PbNodeExecutor.KEY_ACTOR_SEEDS_REUSED)));
			assertTrue("no actor seed new on the wire: " + outcome.get(PbNodeExecutor.KEY_ACTOR_SEEDS_NEW),
				emptyOnTheWire(outcome.get(PbNodeExecutor.KEY_ACTOR_SEEDS_NEW)));
			String joined = String.valueOf(outcome.get("degradations"));
			assertTrue("no region pass, so the result says so: " + joined, joined.contains(RenderPlan.Degradation.BACKGROUND_REGEN_NO_REGIONS));
			assertNotEquals("BACKGROUND_REGEN_NO_REGIONS vetoes auto-approve", Boolean.TRUE, outcome.get(PbNodeExecutor.KEY_AUTO_APPROVED));

			/// On the database: the regenerated final supersedes the approved one but arrives UNSELECTED - V3 §10 rule 1
			/// (an attempt never displaces an APPROVED selection) and rule 5 ("a stale approved node is re-rendered only on
			/// request, and the new result goes to review"); the approved final stays selected and approved, as it was.
			List<BaseRecord> bases2 = baseArtifacts(f);
			assertEquals("base artifacts after the regenerate", 3, bases2.size());
			BaseRecord fresh = null;
			for(BaseRecord a : bases2) {
				if(run2.equals(PbArtifactUtil.attemptOf(a))) {
					assertNull("one final of the regenerate", fresh);
					fresh = a;
				}
				else {
					assertEquals("every other final is job 1's", run1, PbArtifactUtil.attemptOf(a));
				}
			}
			assertNotNull("the regenerate's final", fresh);
			assertEquals(0, PbArtifactUtil.variantIndexOf(fresh));
			assertEquals(Long.valueOf(9201), fresh.get(OlioFieldNames.FIELD_PB_SEED));
			assertFalse("rule 1: the regenerate's final does not displace the APPROVED selection", selected(fresh));
			assertEquals("it goes to review", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(fresh));
			BaseRecord sup = fresh.get(OlioFieldNames.FIELD_PB_SUPERSEDES);
			assertNotNull("it supersedes the approved final", sup);
			assertEquals(approvedOid, sup.get(FieldNames.FIELD_OBJECT_ID));
			BaseRecord approved = PbArtifactUtil.readArtifact(f.owner, approvedOid, orgId);
			assertTrue("the approved final stays selected", selected(approved));
			assertEquals("the approved final keeps its review", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(approved));
			assertEquals("findSelected keeps answering the approved final", approvedOid,
				oidOf(PbArtifactUtil.findSelected(f.owner, node, PbPipelineUtil.ROLE_BASE)));
			node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			assertNotEquals("a vetoed regenerate does not pin", Boolean.TRUE, node.get(OlioFieldNames.FIELD_PB_PINNED));
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
