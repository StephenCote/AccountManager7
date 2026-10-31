package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.FixtureGraphClient;
import org.cote.accountmanager.olio.illustration.MaskImage;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.QaProvider;
import org.cote.accountmanager.olio.illustration.QaReport;
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
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.RenderBackend;
import org.cote.accountmanager.olio.picturebook.PbRenderPlanUtil.PlanOptions;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P8-4 (V3 §17; ledger §6.C 220 g): the visual-QA hook in {@code PbNodeExecutor} - when it runs, what it persists,
 * what it flags and what auto-approve then does - driven through {@code executeNodeRun} against {@code am7db} with
 * <b>no Swarm server and no vision LLM</b>. The {@link RenderBackend} seam supplies the scripted renderer of
 * {@code TestPbRenderStagesOffline} and, new here, a scripted {@link QaProvider} whose verdicts are queued per
 * variant; the {@link SDUtil} points at a closed port and nothing opens a connection. Each case is a TEXT_ONLY
 * scene (no cast, no location) so the only degradations are the hook's.
 * <ol>
 * <li>{@link #canonicalFailFlagsVariantZeroAndSkipsAutoApprove}: STANDARD (variants 2, qa CANONICAL), variant 0
 *     FAILs -> the provider saw variant 0's bytes once; {@code qaMode=CANONICAL}; {@code variants[0].qa} is the
 *     persisted {@code qa} artifact's verdict, {@code variants[0].reviewStatus=IN_REVIEW}, variant 1 unjudged;
 *     {@code QA_FAIL} degradation; auto-approve skipped by the ordering; on the DB variant 0 IN_REVIEW and still
 *     selected, variant 1 GENERATED, one report bound to variant 0 and none to variant 1.</li>
 * <li>{@link #canonicalWarnFlagsThenAutoApproves}: STANDARD, variant 0 WARNs -> flagged IN_REVIEW by the hook,
 *     then auto-approved ({@code QA_WARN} is accepted, 220 c): APPROVED by auto, selected, the report WARN.</li>
 * <li>{@link #allModeJudgesEveryVariantAndOrdersThem}: FINAL (variants 4, qa ALL) scripted WARN, PASS, FAIL, PASS
 *     -> four checks in variant order, {@code qaMode=ALL}, variants 0 and 2 IN_REVIEW, 1 and 3 GENERATED, four
 *     reports; auto-approve is vetoed by FINAL's unrendered UPSCALE pseudo-degradation (not by QA - the ordering
 *     produced a candidate), so the ordering is then shown directly: {@code autoApprove(..., QA_ALL)} over the
 *     persisted reports approves variant 1 (first PASS) and moves the selection off variant 0.</li>
 * <li>{@link #offModeNeverResolvesAProvider}: DRAFT (qa OFF) -> the backend's selector is never asked, no
 *     {@code qaMode} / {@code qa} keys, no {@code qa} artifact, the one variant auto-approved as before Phase 8.</li>
 * <li>{@link #providerErrorIsUnavailableAndTheAttemptStands}: the provider throws -> {@code QA_UNAVAILABLE}
 *     degradation naming the error, an UNAVAILABLE report persisted, variant 0 not flagged and auto-approved.</li>
 * <li>{@link #noProviderIsUnavailableWithTheProbeNote}: the selector answers none -> one {@code QA_UNAVAILABLE}
 *     degradation quoting the probe note, {@code qaMode} present, no {@code qa} keys or artifacts, auto-approved.</li>
 * <li>{@link #chapBookPageIsJudgedAsOneVariant}: a ChapBook page (LANDSCAPE node) on STANDARD, FAIL -> the page DTO
 *     carries a one-entry {@code variants[]} with {@code qa}, the page image is IN_REVIEW and its report is on the
 *     page node.</li>
 * </ol>
 * Timings are logged by the hook, never asserted.
 */
public class TestPbQaHookOffline extends BaseTest {

	private static final String MODEL = "flux2Klein_9b";
	private static final String OFFLINE_SERVER = "http://127.0.0.1:9";
	private static final int W = 1024;
	private static final int H = 768;
	private static final String RUN_SETTING = "a basalt headland above a grey sea at dusk";
	private static final String RUN_SD_PROMPT = "two travellers on a basalt headland at dusk, wind in their coats";
	private static final String PAGE_PROMPT = "a serene alpine lake at dawn, wide natural vista, soft diffuse light, snow on distant peaks";
	private static final String STYLE_PATH = "~/p84styles";
	private static final String POEM_TEXT =
		"Outside, all is pristine,\n" +
		"From cobalt skies of charcoal unity\n" +
		"Descending upon snow canvassed green\n" +
		"To silver veins of icy sheens,\n" +
		"Born of spells and sorcery.\n" +
		"\n" +
		"Inside hearts and hearths and homes,\n" +
		"Ochre embers and ebon cinders,\n" +
		"Faded life stirred by motherly crones,\n" +
		"Dry damp clothes and warm cold bones\n" +
		"And illuminate the age-old spellbound tomes.";

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

	private static String str(Object v) {
		return (v != null ? v.toString() : null);
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

	// ─────────────────────────────── scripted QA provider ───────────────────────────────

	/// Answers the queued reports in order and keeps every image and plan it was asked to judge; a check past
	/// the script is a test defect. {@code boom} set makes every check throw it (the hook must catch it).
	private static final class ScriptedQa implements QaProvider {
		static final String NAME = "scripted";
		final Deque<QaReport> script = new ArrayDeque<>();
		final List<byte[]> judged = new ArrayList<>();
		final List<RenderPlan> plans = new ArrayList<>();
		RuntimeException boom;
		int probes;

		ScriptedQa verdicts(QaReport... reports) {
			for(QaReport r : reports) {
				script.add(r);
			}
			return this;
		}

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public boolean isAvailable() {
			probes++;
			return true;
		}

		@Override
		public QaReport check(RenderPlan plan, byte[] imageBytes) {
			judged.add(imageBytes);
			plans.add(plan);
			if(boom != null) {
				throw boom;
			}
			QaReport r = script.poll();
			if(r == null) {
				throw new IllegalStateException("The QA script has no verdict for image " + judged.size());
			}
			return r;
		}
	}

	/// PASS: both planned figures seen.
	private static QaReport passReport() {
		return QaReport.builder(ScriptedQa.NAME).model("scripted-v1").durationMs(5L)
			.personCount(2, Integer.valueOf(2), "two figures").build();
	}

	/// WARN: an extra figure (220 b: more people than planned is a warning, not a failure).
	private static QaReport warnReport() {
		return QaReport.builder(ScriptedQa.NAME).model("scripted-v1").durationMs(5L)
			.personCount(2, Integer.valueOf(3), "an extra figure").build();
	}

	/// FAIL: one figure fewer than planned and a referenced actor absent.
	private static QaReport failReport() {
		return QaReport.builder(ScriptedQa.NAME).model("scripted-v1").durationMs(5L)
			.personCount(2, Integer.valueOf(1), "one figure")
			.actorPresent("actorB", "Tobin", Boolean.FALSE, "no weathered sailor in the frame").build();
	}

	/// The backend the run uses: the scripted renderer, the fixture graph client, and {@code selection} as the
	/// attempt's QA choice (null provider = none answered). Counts the selector's calls and keeps the checkpoint
	/// it was handed, so the OFF case can show the selector was never asked.
	private static final class QaBackend implements RenderBackend {
		final ScriptedAdapter adapter;
		final FixtureGraphClient client;
		final PbQaUtil.Selection selection;
		int selections;
		String checkpoint;
		/// the run's chatConfig the hook handed the selector (224 a) - null here: these runs name none
		BaseRecord runChatConfig;

		QaBackend(ScriptedAdapter adapter, FixtureGraphClient client, PbQaUtil.Selection selection) {
			this.adapter = adapter;
			this.client = client;
			this.selection = selection;
		}

		@Override
		public RendererAdapter adapter(SDUtil sdu) {
			return adapter;
		}

		@Override
		public SwarmGraphClient client(SDUtil sdu) {
			return client;
		}

		@Override
		public PbQaUtil.Selection qaProvider(BaseRecord user, BaseRecord chatConfig, SDUtil sdu, String ckpt) {
			selections++;
			checkpoint = ckpt;
			runChatConfig = chatConfig;
			return selection;
		}
	}

	private static QaBackend backend(ScriptedAdapter adapter, ScriptedQa qa) {
		return new QaBackend(adapter, new FixtureGraphClient().pruneUnusedLoaders(), new PbQaUtil.Selection(qa, "scripted"));
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A V3 book with one scene and its RENDER_PLAN node (or a ChapBook with its first page's LANDSCAPE node),
	/// owned by a non-admin user, with the book style pinned to {@link #MODEL} and auto-approve on.
	private static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		BaseRecord workflow;
		BaseRecord node;
		String slug;
		String bookOid;
		String sceneOid;
		SDUtil sdu;
		BaseRecord style;
		BaseRecord poem;
	}

	private Fixture fixture(String prefix) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", f.owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		f.slug = prefix + shortId();
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "Offline QA " + f.slug);
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
		f.sdu = new SDUtil(SDAPIEnumType.SWARM, OFFLINE_SERVER);
		return f;
	}

	/**
	 * Everything a real run needs that {@link #fixture} does not give (the {@code TestPbRenderStagesOffline}
	 * recipe): the book context (a world, or compose 409s), scene text so the plan has a subject, a user-edited
	 * MIGRATED composition so {@code ensureComposition} neither calls the LLM nor reports COMPOSED_OUT_OF_ORDER,
	 * the book style pinned to {@link #MODEL} with the inventory seeded so nothing is MODEL_SUBSTITUTED, and
	 * auto-approve on. Leaves {@code f.book} as the fresh {@code readBook} projection.
	 */
	private void prepareRun(Fixture f, String tag) throws Exception {
		long orgId = orgOf(f.owner);
		BookContext bctx = PbBookUtil.openBookContext(f.owner, f.book);
		assertNotNull("book context", bctx);
		assertNotNull("book world", bctx.getWorld());

		BaseRecord scene = PbBookUtil.readScene(f.owner, f.sceneOid, orgId);
		assertNotNull("scene", scene);
		BaseRecord sp = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_CB_SD_PROMPT);
		sp.set(OlioFieldNames.FIELD_PB_SETTING, RUN_SETTING);
		sp.set(OlioFieldNames.FIELD_CB_SD_PROMPT, RUN_SD_PROMPT);
		assertNotNull("scene text patch", IOSystem.getActiveContext().getAccessPoint().update(f.owner, sp));
		scene = PbBookUtil.readScene(f.owner, f.sceneOid, orgId);

		BaseRecord book = PbBookUtil.readBook(f.owner, f.bookOid, orgId);
		ComposeResult cr = PbComposeUtil.composeMigrated(f.owner, book, scene, false);
		assertNotNull("compose", cr);
		assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
		BaseRecord comp = PbComposeUtil.readComposition(f.owner, oidOf(cr.composition), orgId);
		assertNotNull("composition", comp);
		assertNotNull("composition marked user-edited", PbComposeUtil.touchEdited(f.owner, comp, null));
		finishPrepare(f, book, tag);
	}

	/// Style pin + inventory seed + auto-approve on, then re-read the book; shared by both fixtures.
	private void finishPrepare(Fixture f, BaseRecord book, String tag) throws Exception {
		f.style = pinBookStyle(f.owner, book, tag, MODEL);
		seedInventory(OFFLINE_SERVER, List.of(MODEL));
		assertTrue("autoApprove on", PbBookUtil.setAutoApprove(f.owner, book, true));
		CacheUtil.clearCache();
		f.book = PbBookUtil.readBook(f.owner, f.bookOid, orgOf(f.owner));
		assertNotNull("book re-read", f.book);
		assertEquals("autoApprove read back", Boolean.TRUE, f.book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
	}

	/**
	 * Case 7's fixture: a ChapBook from Stephen's poem ({@code ChapBookUtil.createChapBook}, no chatConfig), its
	 * first page given a real landscape prompt and a LANDSCAPE page node linked through {@code sceneNode} - the
	 * {@code TestChapBookPageComposition} recipe - then the same style pin / inventory / auto-approve finish.
	 */
	private Fixture chapBookFixture(String prefix) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", f.owner);
		long orgId = orgOf(f.owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		f.slug = prefix + shortId();

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/P84-" + f.slug);
		plist.parameter(FieldNames.FIELD_NAME, "Poem " + f.slug);
		BaseRecord poem = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_POEM, f.owner, null, plist);
		poem.set("text", POEM_TEXT);
		poem.set("title", "Poem " + f.slug);
		f.poem = IOSystem.getActiveContext().getAccessPoint().create(f.owner, poem);
		assertNotNull("poem", f.poem);

		f.book = ChapBookUtil.createChapBook(f.owner, dataPath, f.slug, "Offline QA page " + f.slug, List.of(oidOf(f.poem)), 5, null);
		assertNotNull("createChapBook", f.book);
		f.bookOid = oidOf(f.book);
		BaseRecord book = PbBookUtil.readBook(f.owner, f.bookOid, orgId);
		assertNotNull("book readable", book);
		assertTrue("the fixture is a ChapBook", PbNodeExecutor.isChapBook(book));
		List<BaseRecord> scenes = PbBookUtil.listScenes(f.owner, book);
		assertTrue("at least one page", scenes != null && !scenes.isEmpty());
		f.sceneOid = oidOf(scenes.get(0));
		assertTrue("page prompt set", ChapBookUtil.setSceneLandscapePrompt(f.owner, f.sceneOid, PAGE_PROMPT));

		String wfPath = PbBookUtil.workflowGroupPath(f.slug);
		f.workflow = PbGraphUtil.getCreateWorkflow(f.owner, book, wfPath);
		assertNotNull("workflow", f.workflow);
		BaseRecord scene = PbBookUtil.readScene(f.owner, f.sceneOid, orgId);
		assertNotNull("page", scene);
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int idx = (sceneIndex != null ? sceneIndex.intValue() : 0);
		String handle = PbPipelineUtil.landscapeHandle(f.sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(f.owner, f.workflow, handle);
		if(node == null) {
			node = PbGraphUtil.addNode(f.owner, f.workflow, handle, PbNodeTypeEnumType.LANDSCAPE, wfPath, idx);
			assertNotNull("page node " + handle, node);
			assertTrue("page node scope patch", PbPipelineUtil.patchNodeScope(f.owner, f.workflow, node, PbPipelineUtil.SCOPE_SCENE, f.sceneOid, idx));
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
		patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, node);
		assertNotNull("scene.sceneNode patch", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
		f.node = PbGraphUtil.readNode(f.owner, oidOf(node), orgId);
		assertNotNull("page node re-read", f.node);
		f.sdu = new SDUtil(SDAPIEnumType.SWARM, OFFLINE_SERVER);
		finishPrepare(f, book, prefix);
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
		if(f.style != null) {
			SwarmModelInventory.invalidate(OFFLINE_SERVER);
			try {
				ioContext.getAccessPoint().delete(f.owner, f.style);
			}
			catch(Exception e) {
				logger.warn("Cleanup of style for " + f.slug + ": " + e.getMessage());
			}
		}
		if(f.poem != null) {
			try {
				ioContext.getAccessPoint().delete(f.owner, f.poem);
			}
			catch(Exception e) {
				logger.warn("Cleanup of poem for " + f.slug + ": " + e.getMessage());
			}
		}
	}

	/// Put {@code installed} into SwarmModelInventory's cache for {@code server} so the resolver never asks the
	/// (closed) port and never substitutes the pinned model. Same seam TestPbRenderStagesOffline uses.
	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void seedInventory(String server, List<String> installed) throws Exception {
		java.lang.reflect.Field cacheField = SwarmModelInventory.class.getDeclaredField("CACHE");
		cacheField.setAccessible(true);
		Map cache = (Map) cacheField.get(null);
		java.lang.reflect.Constructor<SwarmModelInventory> ctor = SwarmModelInventory.class.getDeclaredConstructor(String.class, List.class, long.class);
		ctor.setAccessible(true);
		cache.put(server.trim(), ctor.newInstance(server.trim(), new ArrayList<>(installed), System.currentTimeMillis()));
	}

	/// A BOOK-scope style pinning PORTRAIT / LOCATION / SCENE_DIRECT / INPAINT to {@code model} (no preset: each
	/// case passes its preset as the request override), linked to the book; returns the style ref for cleanup.
	private BaseRecord pinBookStyle(BaseRecord u, BaseRecord bk, String tag, String model) throws Exception {
		long orgId = orgOf(u);
		Map<String, String> pins = new LinkedHashMap<>();
		for(PbModelTaskEnumType t : new PbModelTaskEnumType[] { PbModelTaskEnumType.PORTRAIT, PbModelTaskEnumType.LOCATION,
				PbModelTaskEnumType.SCENE_DIRECT, PbModelTaskEnumType.INPAINT }) {
			pins.put(ModelTaskKeys.key(t), model);
		}
		BaseRecord style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, style, "p84-" + tag + "-" + shortId(), STYLE_PATH, orgId);
		style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.BOOK.toString());
		style.set(OlioFieldNames.FIELD_PB_TASK_MODELS, JSONUtil.exportObject(pins));
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

	/// The node's artifacts under {@code role}, re-read uncached and sorted by variantIndex (the selection and
	/// review stamps are written after the create, and listChain is a projection).
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

	private static List<BaseRecord> baseArtifacts(Fixture f) {
		return artifactsByVariant(f, PbPipelineUtil.ROLE_BASE);
	}

	private static List<BaseRecord> qaArtifacts(Fixture f) {
		return PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_QA);
	}

	/// One {@code executeNodeRun} of the fixture node under {@code preset} as the request override.
	private static Map<String, Object> run(Fixture f, QaBackend backend, PbQualityPresetEnumType preset) {
		Map<String, Object> out = PbNodeExecutor.executeNodeRun(f.owner, f.book, f.workflow, f.node, f.sdu,
			new PlanOptions(null, false, preset, null), null, backend);
		assertNotNull("run DTO", out);
		assertEquals("run status", PbRunStatusEnumType.COMPLETED.toString(), status(out.get("runStatus")));
		assertNotNull("run objectId", out.get("runObjectId"));
		return out;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> variants(Map<String, Object> out, int expected) {
		Object vs = out.get(PbNodeExecutor.KEY_VARIANTS);
		assertTrue("variants is a list: " + vs, vs instanceof List);
		List<Map<String, Object>> list = (List<Map<String, Object>>) vs;
		assertEquals("variants", expected, list.size());
		return list;
	}

	private static Map<?, ?> qaOf(Map<String, Object> variant) {
		Object q = variant.get(PbNodeExecutor.KEY_VARIANT_QA);
		assertTrue("variant " + variant.get("variantIndex") + " carries qa: " + variant, q instanceof Map);
		return (Map<?, ?>) q;
	}

	/// The one degradation starting with {@code code + ": "}, or a failed assertion naming what was there.
	private static String degradation(Map<String, Object> out, String code) {
		List<String> all = PbNodeExecutor.degradationsOf(out);
		assertNotNull("degradations", all);
		String found = null;
		for(String d : all) {
			if(d.startsWith(code + ": ")) {
				assertNull("one " + code + " entry, found another: " + all, found);
				found = d;
			}
		}
		assertNotNull("a " + code + " degradation in " + all, found);
		return found;
	}

	private static void assertNoDegradation(Map<String, Object> out, String code) {
		List<String> all = PbNodeExecutor.degradationsOf(out);
		if(all == null) return;
		for(String d : all) {
			assertFalse("no " + code + " degradation, found: " + d, d.startsWith(code + ": "));
		}
	}

	private static byte[] solid(int r, int g, int b) {
		return MaskImage.solid(W, H, new Color(r, g, b));
	}

	// ─────────────────────────────── cases ───────────────────────────────

	@Test
	public void canonicalFailFlagsVariantZeroAndSkipsAutoApprove() throws Exception {
		logger.info("canonicalFailFlagsVariantZeroAndSkipsAutoApprove");
		Fixture f = null;
		try {
			f = fixture("p84cf");
			prepareRun(f, "cf");
			byte[] png0 = solid(90, 120, 60);
			byte[] png1 = solid(60, 90, 120);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(png0, 8101, 800), ok(png1, 8102, 700));
			ScriptedQa qa = new ScriptedQa().verdicts(failReport());
			QaBackend backend = backend(adapter, qa);

			Map<String, Object> out = run(f, backend, PbQualityPresetEnumType.STANDARD);
			String runOid = str(out.get("runObjectId"));
			assertEquals("TEXT_ONLY scene", PbStrategyEnumType.TEXT_ONLY.name(), out.get("strategy"));
			assertEquals("two finals rendered", 2, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));

			/// The hook: one selection, variant 0 judged once with the bytes the renderer returned, under the plan.
			assertEquals("selector asked once per attempt", 1, backend.selections);
			assertEquals("selector handed the BASE checkpoint", MODEL, backend.checkpoint);
			assertNull("a run opened without a chatConfig hands the selector null (224 a; default route)", backend.runChatConfig);
			assertEquals("CANONICAL judges variant 0 only", 1, qa.judged.size());
			assertArrayEquals("variant 0's bytes", png0, qa.judged.get(0));
			assertNotNull("the plan was handed to the provider", qa.plans.get(0));
			assertEquals("the plan's preset asks for CANONICAL", QualityPresets.QA_CANONICAL,
				PbQaUtil.qaModeOf(qa.plans.get(0)));
			assertEquals("qaMode on the DTO", QualityPresets.QA_CANONICAL, out.get(PbNodeExecutor.KEY_QA_MODE));

			/// variants[]: variant 0 carries the verdict and the review flag; variant 1 was never judged.
			List<Map<String, Object>> vs = variants(out, 2);
			Map<String, Object> d0 = vs.get(0);
			Map<String, Object> d1 = vs.get(1);
			Map<?, ?> q0 = qaOf(d0);
			assertEquals("variant 0 FAIL", QaReport.Result.FAIL.name(), q0.get("result"));
			assertEquals("report provider", ScriptedQa.NAME, q0.get("provider"));
			String qaOid = str(q0.get("artifactObjectId"));
			assertNotNull("the qa artifact's objectId", qaOid);
			assertEquals("variant 0 flagged", PbReviewStatusEnumType.IN_REVIEW.name(), d0.get("reviewStatus"));
			assertNull("variant 1 unjudged", d1.get(PbNodeExecutor.KEY_VARIANT_QA));
			assertEquals("variant 1 untouched", PbReviewStatusEnumType.GENERATED.name(), d1.get("reviewStatus"));
			assertEquals(Boolean.TRUE, d0.get("selected"));
			assertEquals(Boolean.FALSE, d1.get("selected"));

			/// Degradation + auto-approve: QA_FAIL is reported, and the ordering (not a veto) skips the attempt.
			String deg = degradation(out, RenderPlan.Degradation.QA_FAIL);
			assertTrue("names variant 0: " + deg, deg.startsWith(RenderPlan.Degradation.QA_FAIL + ": variant 0 ("));
			assertTrue("carries the failed checks: " + deg, deg.contains(QaReport.KIND_PERSON_COUNT) && deg.contains("Tobin"));
			assertEquals(Boolean.FALSE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			String skipped = str(out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			assertNotNull("skip reason", skipped);
			assertTrue("skipped by the QA ordering: " + skipped, skipped.startsWith("degraded: " + RenderPlan.Degradation.QA_FAIL + ": variant 0 ("));
			assertNull("no top-level approval", out.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
			assertNotEquals("headline reviewStatus is not APPROVED", PbReviewStatusEnumType.APPROVED.name(), out.get("reviewStatus"));

			/// On the database: two bases of the run, variant 0 IN_REVIEW and still selected, one report bound to it.
			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals("base artifacts", 2, bases.size());
			BaseRecord a0 = bases.get(0);
			BaseRecord a1 = bases.get(1);
			assertEquals(runOid, PbArtifactUtil.attemptOf(a0));
			assertEquals(runOid, PbArtifactUtil.attemptOf(a1));
			assertEquals(oidOf(a0), d0.get("artifactObjectId"));
			assertEquals(PbReviewStatusEnumType.IN_REVIEW, PbArtifactUtil.reviewStatusOf(a0));
			assertTrue("variant 0 stays selected", selected(a0));
			assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a1));
			assertFalse(selected(a1));
			List<BaseRecord> reports = qaArtifacts(f);
			assertEquals("one qa artifact", 1, reports.size());
			assertEquals("the DTO names it", qaOid, oidOf(reports.get(0)));
			assertEquals("report attempt is the run", runOid, PbArtifactUtil.attemptOf(reports.get(0)));
			assertEquals("report variantIndex", 0, PbArtifactUtil.variantIndexOf(reports.get(0)));
			QaReport r0 = PbQaUtil.reportFor(f.owner, f.node, oidOf(a0));
			assertNotNull("report for variant 0", r0);
			assertEquals(QaReport.Result.FAIL, r0.getResult());
			assertEquals(oidOf(a0), r0.getImageArtifactObjectId());
			assertEquals(ScriptedQa.NAME, r0.getProvider());
			assertEquals("scripted-v1", r0.getModel());
			assertEquals("two checks round-trip", 2, r0.getChecks().size());
			assertNull("no report for variant 1", PbQaUtil.reportFor(f.owner, f.node, oidOf(a1)));
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void canonicalWarnFlagsThenAutoApproves() throws Exception {
		logger.info("canonicalWarnFlagsThenAutoApproves");
		Fixture f = null;
		try {
			f = fixture("p84cw");
			prepareRun(f, "cw");
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(solid(120, 90, 60), 8201, 800), ok(solid(90, 60, 120), 8202, 700));
			ScriptedQa qa = new ScriptedQa().verdicts(warnReport());

			Map<String, Object> out = run(f, backend(adapter, qa), PbQualityPresetEnumType.STANDARD);
			assertEquals(1, qa.judged.size());
			List<Map<String, Object>> vs = variants(out, 2);
			Map<String, Object> d0 = vs.get(0);
			assertEquals("variant 0 WARN", QaReport.Result.WARN.name(), qaOf(d0).get("result"));
			String deg = degradation(out, RenderPlan.Degradation.QA_WARN);
			assertTrue(deg, deg.startsWith(RenderPlan.Degradation.QA_WARN + ": variant 0 ("));
			assertNoDegradation(out, RenderPlan.Degradation.QA_FAIL);

			/// A WARN is accepted (220 c): the hook flagged variant 0, then auto-approve approved it over the flag.
			assertEquals("auto-approved: " + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED), Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertEquals(d0.get("artifactObjectId"), out.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
			assertEquals("variants[0] reports the approval", PbReviewStatusEnumType.APPROVED.name(), d0.get("reviewStatus"));
			assertEquals("headline reviewStatus", PbReviewStatusEnumType.APPROVED.name(), out.get("reviewStatus"));

			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals(2, bases.size());
			BaseRecord a0 = bases.get(0);
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a0));
			assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, a0.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			assertTrue(selected(a0));
			assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(bases.get(1)));
			QaReport r0 = PbQaUtil.reportFor(f.owner, f.node, oidOf(a0));
			assertNotNull(r0);
			assertEquals("the report keeps the WARN the approval overrode", QaReport.Result.WARN, r0.getResult());
			assertEquals(1, qaArtifacts(f).size());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void allModeJudgesEveryVariantAndOrdersThem() throws Exception {
		logger.info("allModeJudgesEveryVariantAndOrdersThem");
		Fixture f = null;
		try {
			f = fixture("p84al");
			prepareRun(f, "al");
			byte[][] pngs = { solid(10, 20, 30), solid(40, 50, 60), solid(70, 80, 90), solid(100, 110, 120) };
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(pngs[0], 8301, 800), ok(pngs[1], 8302, 700),
				ok(pngs[2], 8303, 700), ok(pngs[3], 8304, 700));
			ScriptedQa qa = new ScriptedQa().verdicts(warnReport(), passReport(), failReport(), passReport());

			Map<String, Object> out = run(f, backend(adapter, qa), PbQualityPresetEnumType.FINAL);
			assertEquals("four finals rendered", 4, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertEquals("qaMode ALL", QualityPresets.QA_ALL, out.get(PbNodeExecutor.KEY_QA_MODE));
			assertEquals("every variant judged", 4, qa.judged.size());
			for(int i = 0; i < 4; i++) {
				assertArrayEquals("variant " + i + " bytes in variant order", pngs[i], qa.judged.get(i));
			}

			List<Map<String, Object>> vs = variants(out, 4);
			String[] expected = { QaReport.Result.WARN.name(), QaReport.Result.PASS.name(), QaReport.Result.FAIL.name(), QaReport.Result.PASS.name() };
			String[] flags = { PbReviewStatusEnumType.IN_REVIEW.name(), PbReviewStatusEnumType.GENERATED.name(),
				PbReviewStatusEnumType.IN_REVIEW.name(), PbReviewStatusEnumType.GENERATED.name() };
			for(int i = 0; i < 4; i++) {
				assertEquals("variantIndex", i, intOf(vs.get(i).get("variantIndex")));
				assertEquals("variant " + i + " verdict", expected[i], qaOf(vs.get(i)).get("result"));
				assertEquals("variant " + i + " reviewStatus", flags[i], vs.get(i).get("reviewStatus"));
			}
			degradation(out, RenderPlan.Degradation.QA_WARN);
			String fail = degradation(out, RenderPlan.Degradation.QA_FAIL);
			assertTrue(fail, fail.startsWith(RenderPlan.Degradation.QA_FAIL + ": variant 2 ("));

			/// FINAL plans UPSCALE, which Phase 2 does not render; that pseudo-entry vetoes auto-approve for every
			/// FINAL attempt today (PbArtifactUtil.autoApprove javadoc). The ordering still ran and found a
			/// candidate - the skip is the veto, not "all variants failed".
			assertEquals(Boolean.FALSE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			String skipped = str(out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			assertNotNull(skipped);
			assertTrue("vetoed by the unrendered UPSCALE stage: " + skipped, skipped.startsWith("degraded: upscale: "));
			assertFalse("not the all-failed skip: " + skipped, skipped.contains(RenderPlan.Degradation.QA_FAIL));

			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals(4, bases.size());
			PbReviewStatusEnumType[] db = { PbReviewStatusEnumType.IN_REVIEW, PbReviewStatusEnumType.GENERATED,
				PbReviewStatusEnumType.IN_REVIEW, PbReviewStatusEnumType.GENERATED };
			for(int i = 0; i < 4; i++) {
				assertEquals("variant " + i, i, PbArtifactUtil.variantIndexOf(bases.get(i)));
				assertEquals("variant " + i + " on the DB", db[i], PbArtifactUtil.reviewStatusOf(bases.get(i)));
			}
			assertTrue("variant 0 still selected (nothing approved)", selected(bases.get(0)));
			assertEquals("four reports", 4, qaArtifacts(f).size());
			Map<String, QaReport> latest = PbQaUtil.latestReportsOf(f.owner, f.node);
			assertEquals(4, latest.size());
			Map<String, QaReport.Result> verdicts = new LinkedHashMap<>();
			for(BaseRecord a : bases) {
				QaReport r = latest.get(oidOf(a));
				assertNotNull("report for " + oidOf(a), r);
				verdicts.put(oidOf(a), r.getResult());
			}
			assertEquals(QaReport.Result.WARN, verdicts.get(oidOf(bases.get(0))));
			assertEquals(QaReport.Result.PASS, verdicts.get(oidOf(bases.get(1))));
			assertEquals(QaReport.Result.FAIL, verdicts.get(oidOf(bases.get(2))));
			assertEquals(QaReport.Result.PASS, verdicts.get(oidOf(bases.get(3))));

			/// The ALL ordering over the persisted verdicts, without the veto: the first PASS (variant 1) wins over
			/// the WARN on variant 0 and the approval moves the selection.
			PbArtifactUtil.AutoApproveOutcome oc = PbArtifactUtil.autoApprove(f.owner, bases, verdicts, List.of(), QualityPresets.QA_ALL);
			assertTrue("approved: " + oc.getSkipped(), oc.isApproved());
			assertEquals("variant 1 approved", oidOf(bases.get(1)), oidOf(oc.getApproved()));
			List<BaseRecord> after = baseArtifacts(f);
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(after.get(1)));
			assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, after.get(1).get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			assertTrue("variant 1 selected", selected(after.get(1)));
			assertFalse("variant 0 deselected", selected(after.get(0)));
			assertEquals("variant 0 keeps its flag", PbReviewStatusEnumType.IN_REVIEW, PbArtifactUtil.reviewStatusOf(after.get(0)));
			assertEquals("variant 2 keeps its flag", PbReviewStatusEnumType.IN_REVIEW, PbArtifactUtil.reviewStatusOf(after.get(2)));
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void offModeNeverResolvesAProvider() throws Exception {
		logger.info("offModeNeverResolvesAProvider");
		Fixture f = null;
		try {
			f = fixture("p84of");
			prepareRun(f, "of");
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(solid(30, 30, 30), 8401, 800));
			ScriptedQa qa = new ScriptedQa().verdicts(failReport());
			QaBackend backend = backend(adapter, qa);

			Map<String, Object> out = run(f, backend, PbQualityPresetEnumType.DRAFT);
			assertEquals("one final", 1, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertEquals("the selector is never asked under OFF", 0, backend.selections);
			assertEquals("the provider is never probed", 0, qa.probes);
			assertTrue("nothing judged", qa.judged.isEmpty());
			assertNull("no qaMode", out.get(PbNodeExecutor.KEY_QA_MODE));
			List<Map<String, Object>> vs = variants(out, 1);
			assertNull("no qa on the variant", vs.get(0).get(PbNodeExecutor.KEY_VARIANT_QA));
			assertNoDegradation(out, RenderPlan.Degradation.QA_FAIL);
			assertNoDegradation(out, RenderPlan.Degradation.QA_WARN);
			assertNoDegradation(out, RenderPlan.Degradation.QA_UNAVAILABLE);
			assertEquals("auto-approved as before Phase 8: " + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED), Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), vs.get(0).get("reviewStatus"));
			assertTrue("no qa artifacts", qaArtifacts(f).isEmpty());
			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals(1, bases.size());
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(bases.get(0)));
			assertTrue(PbQaUtil.latestReportsOf(f.owner, f.node).isEmpty());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void providerErrorIsUnavailableAndTheAttemptStands() throws Exception {
		logger.info("providerErrorIsUnavailableAndTheAttemptStands");
		Fixture f = null;
		try {
			f = fixture("p84pe");
			prepareRun(f, "pe");
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(solid(200, 200, 10), 8501, 800), ok(solid(10, 200, 200), 8502, 700));
			ScriptedQa qa = new ScriptedQa();
			qa.boom = new IllegalStateException("boom");

			Map<String, Object> out = run(f, backend(adapter, qa), PbQualityPresetEnumType.STANDARD);
			assertEquals("the provider was asked once and threw", 1, qa.judged.size());
			assertEquals(QualityPresets.QA_CANONICAL, out.get(PbNodeExecutor.KEY_QA_MODE));
			List<Map<String, Object>> vs = variants(out, 2);
			Map<?, ?> q0 = qaOf(vs.get(0));
			assertEquals(QaReport.Result.UNAVAILABLE.name(), q0.get("result"));
			assertEquals("provider error: boom", q0.get("note"));
			assertNotNull("an UNAVAILABLE report is persisted too", q0.get("artifactObjectId"));
			assertNull(vs.get(1).get(PbNodeExecutor.KEY_VARIANT_QA));
			String deg = degradation(out, RenderPlan.Degradation.QA_UNAVAILABLE);
			assertTrue(deg, deg.startsWith(RenderPlan.Degradation.QA_UNAVAILABLE + ": variant 0 ("));
			assertTrue(deg, deg.endsWith("provider error: boom"));
			assertNoDegradation(out, RenderPlan.Degradation.QA_FAIL);

			/// Not flagged, and QA_UNAVAILABLE is accepted: the attempt auto-approves.
			assertEquals("auto-approved: " + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED), Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), vs.get(0).get("reviewStatus"));
			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals(2, bases.size());
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(bases.get(0)));
			assertEquals(1, qaArtifacts(f).size());
			QaReport r0 = PbQaUtil.reportFor(f.owner, f.node, oidOf(bases.get(0)));
			assertNotNull(r0);
			assertEquals(QaReport.Result.UNAVAILABLE, r0.getResult());
			assertEquals(ScriptedQa.NAME, r0.getProvider());
			assertTrue(r0.getChecks().isEmpty());
			assertEquals("provider error: boom", r0.getNote());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void noProviderIsUnavailableWithTheProbeNote() throws Exception {
		logger.info("noProviderIsUnavailableWithTheProbeNote");
		Fixture f = null;
		try {
			f = fixture("p84np");
			prepareRun(f, "np");
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(solid(5, 5, 5), 8601, 800), ok(solid(250, 250, 250), 8602, 700));
			String note = "vlm: route does not list the QA model; swarmYolo: detector not installed (probes 3 + 4 ms)";
			QaBackend backend = new QaBackend(adapter, new FixtureGraphClient().pruneUnusedLoaders(), new PbQaUtil.Selection(null, note));

			Map<String, Object> out = run(f, backend, PbQualityPresetEnumType.STANDARD);
			assertEquals(1, backend.selections);
			assertEquals("qaMode is reported even when nothing could judge", QualityPresets.QA_CANONICAL, out.get(PbNodeExecutor.KEY_QA_MODE));
			List<Map<String, Object>> vs = variants(out, 2);
			assertNull("no qa on variant 0", vs.get(0).get(PbNodeExecutor.KEY_VARIANT_QA));
			assertNull("no qa on variant 1", vs.get(1).get(PbNodeExecutor.KEY_VARIANT_QA));
			String deg = degradation(out, RenderPlan.Degradation.QA_UNAVAILABLE);
			assertEquals(RenderPlan.Degradation.QA_UNAVAILABLE + ": no visual-QA provider is available (" + note + ")", deg);
			assertEquals("auto-approved: " + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED), Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertTrue("no qa artifacts", qaArtifacts(f).isEmpty());
			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals(2, bases.size());
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(bases.get(0)));
			assertEquals(PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(bases.get(1)));
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void chapBookPageIsJudgedAsOneVariant() throws Exception {
		logger.info("chapBookPageIsJudgedAsOneVariant");
		Fixture f = null;
		try {
			f = chapBookFixture("p84cb");
			byte[] png = solid(180, 200, 230);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(png, 8701, 800));
			ScriptedQa qa = new ScriptedQa().verdicts(failReport());
			QaBackend backend = backend(adapter, qa);

			Map<String, Object> out = run(f, backend, PbQualityPresetEnumType.STANDARD);
			String runOid = str(out.get("runObjectId"));
			assertEquals("a page renders one final whatever the preset says", 1, adapter.requests.size());
			assertEquals("page DTO names its scene", f.sceneOid, out.get("sceneObjectId"));
			assertEquals(1, backend.selections);
			assertEquals(MODEL, backend.checkpoint);
			assertEquals("the page judged once", 1, qa.judged.size());
			assertArrayEquals(png, qa.judged.get(0));
			assertEquals(QualityPresets.QA_CANONICAL, out.get(PbNodeExecutor.KEY_QA_MODE));

			List<Map<String, Object>> vs = variants(out, 1);
			Map<String, Object> d0 = vs.get(0);
			assertEquals(0, intOf(d0.get("variantIndex")));
			assertEquals("the page image is the one variant", out.get("artifactObjectId"), d0.get("artifactObjectId"));
			assertEquals(QaReport.Result.FAIL.name(), qaOf(d0).get("result"));
			assertEquals(PbReviewStatusEnumType.IN_REVIEW.name(), d0.get("reviewStatus"));
			String deg = degradation(out, RenderPlan.Degradation.QA_FAIL);
			assertTrue(deg, deg.startsWith(RenderPlan.Degradation.QA_FAIL + ": variant 0 ("));
			assertEquals(Boolean.FALSE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			String skipped = str(out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			assertNotNull(skipped);
			assertTrue("skipped by the QA ordering: " + skipped, skipped.startsWith("degraded: " + RenderPlan.Degradation.QA_FAIL + ": variant 0 ("));

			/// On the database: the page's landscape artifact IN_REVIEW and selected, its report on the page node.
			List<BaseRecord> pages = artifactsByVariant(f, PbPipelineUtil.ROLE_LANDSCAPE);
			assertEquals("one page image", 1, pages.size());
			BaseRecord a0 = pages.get(0);
			assertEquals(oidOf(a0), d0.get("artifactObjectId"));
			assertEquals(runOid, PbArtifactUtil.attemptOf(a0));
			assertEquals(PbReviewStatusEnumType.IN_REVIEW, PbArtifactUtil.reviewStatusOf(a0));
			assertTrue(selected(a0));
			assertEquals(1, qaArtifacts(f).size());
			QaReport r0 = PbQaUtil.reportFor(f.owner, f.node, oidOf(a0));
			assertNotNull(r0);
			assertEquals(QaReport.Result.FAIL, r0.getResult());
			assertEquals(oidOf(a0), r0.getImageArtifactObjectId());
		}
		finally {
			cleanup(f);
		}
	}
}
