package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.FixtureGraphClient;
import org.cote.accountmanager.olio.illustration.Flux2Compiler;
import org.cote.accountmanager.olio.illustration.MaskImage;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.QaProvider;
import org.cote.accountmanager.olio.illustration.QaReport;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.SdxlCompiler;
import org.cote.accountmanager.olio.illustration.StageImage;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.illustration.StageResult;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbCoverUtil.CoverResult;
import org.cote.accountmanager.olio.picturebook.PbNodeExecutor.RenderBackend;
import org.cote.accountmanager.olio.picturebook.PbRenderPlanUtil.PlanOptions;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
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
 * P9-2 (V3 §40; ledger §17): the cover render plan and its executor branch, against {@code am7db} with <b>no
 * Swarm server and no LLM</b>. A MANUAL {@code COVER} composition ({@code PbCoverUtil.getCreate}, no rows, no
 * text) is given its setting / action / mood / title-safe band and one description-only actor row plus one
 * element row placed <i>inside</i> the band; the plan is built through {@code PbRenderPlanUtil.buildCompositionPlan}
 * and rendered through {@code executeNodeRun} on the node {@code PbServiceFacade.ensureCompositionPlanNode}
 * materializes, with the scripted {@link RendererAdapter} / {@link QaProvider} of {@code TestPbQaHookOffline}
 * behind the {@link RenderBackend} seam.
 * <ol>
 * <li>{@link #topBandPlanClipsRowsAndBothCompilersSpeakIt}: TOP -> purpose {@code COVER}, {@code reserved} =
 *     (0,0,1,0.25), the actor box (y 0.05, h 0.4) reads (y 0.25, h 0.4) and the element (y 0.1, h 0.2) reads
 *     (y 0.25, h 0.2) on the plan while the stored rows keep their values; the compiled BASE prompt (FLUX2, the
 *     pinned checkpoint) and a direct {@link SdxlCompiler} compile both carry the "top quarter ... negative space"
 *     clause; {@code toJson} / {@code fromJson} round-trips the band.</li>
 * <li>{@link #bottomBandSlidesRowsUp}: BOTTOM -> {@code reserved} = (0,0.75,1,0.25); an actor ending at 0.9 is
 *     slid up to end at 0.75 keeping its height; the clause says "bottom quarter".</li>
 * <li>{@link #noBandReservesNothing}: NONE -> {@code getReserved()} null, rows unclipped, no clause in either
 *     compiler.</li>
 * <li>{@link #emptyCoverAndSceneCompositionAreRefused}: a MANUAL cover with no text is a 400 "has no description to
 *     render from"; a SCENE composition handed to {@code buildCompositionPlan} is a 400 "is not a cover-type
 *     composition".</li>
 * <li>{@link #executorRendersTheCoverThroughThePlanPath}: {@code ensureCompositionPlanNode} is idempotent (handle
 *     {@code compositionPlanHandle}, scope {@code composition}); a DRAFT run makes exactly one renderer request
 *     whose plan is the cover plan (purpose COVER, the clause in the prompt), records one BASE artifact stamped
 *     with the run, completes the node DONE_UNVERIFIED then auto-approves it, the DTO names
 *     {@code compositionObjectId} / {@code purpose} / {@code strategy TEXT_ONLY}, and nothing composed a scene
 *     (no SCENE composition, the cover's revision unchanged). A second STANDARD run on the same node renders the
 *     preset's two variants and the QA hook judges variant 0 under the cover plan.</li>
 * <li>{@link #foreignCompositionIsNotFoundInThisBook}: a node whose {@code scopeRef} is another book's cover is a
 *     404 "not found in this book" and the run closes FAILED before anything renders.</li>
 * </ol>
 * Timings are logged, never asserted.
 */
public class TestPbCoverPlanOffline extends BaseTest {

	private static final String MODEL = "flux2Klein_9b";
	private static final String OFFLINE_SERVER = "http://127.0.0.1:9";
	private static final int W = 1024;
	private static final int H = 768;
	private static final String STYLE_PATH = "~/p92styles";
	private static final String COVER_SETTING = "a basalt headland above a grey sea at dusk, gulls wheeling";
	private static final String COVER_ACTION = "a lone figure in a storm coat faces the sea";
	private static final String COVER_MOOD = "wind-scoured and expectant";
	private static final String ACTOR_TEXT = "a tall woman in a grey storm coat, hood up";
	private static final String ELEMENT_TEXT = "a brass lantern set on the rocks";
	private static final String SCENE_SETTING = "a basalt headland above a grey sea at dusk";
	private static final String SCENE_SD_PROMPT = "two travellers on a basalt headland at dusk, wind in their coats";
	private static final String TOP_CLAUSE = "top quarter of the frame is calm, uncluttered negative space";
	private static final String BOTTOM_CLAUSE = "bottom quarter of the frame is calm, uncluttered negative space";
	private static final String ANY_CLAUSE = "of the frame is calm, uncluttered negative space";
	private static final double EPS = 1e-9;

	private interface Call {
		void run() throws Exception;
	}

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
		return (v != null ? v.toString().toUpperCase(Locale.ROOT) : null);
	}

	private static String str(Object v) {
		return (v != null ? v.toString() : null);
	}

	private static int intOf(Object v) {
		assertTrue("a number, got " + v, v instanceof Number);
		return ((Number) v).intValue();
	}

	private static double dbl(BaseRecord rec, String field) {
		Object v = (rec != null && rec.hasField(field) ? rec.get(field) : null);
		return (v instanceof Number ? ((Number) v).doubleValue() : 0.0);
	}

	private static boolean selected(BaseRecord a) {
		Boolean sel = a.get(OlioFieldNames.FIELD_PB_SELECTED);
		return sel != null && sel.booleanValue();
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for(int i = 0; i + 1 < kv.length; i += 2) {
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	private static void assertBox(String what, RenderPlan.Box box, double x, double y, double w, double h) {
		assertNotNull(what + " box", box);
		assertEquals(what + " x", x, box.getX(), EPS);
		assertEquals(what + " y", y, box.getY(), EPS);
		assertEquals(what + " w", w, box.getW(), EPS);
		assertEquals(what + " h", h, box.getH(), EPS);
	}

	private static void assertRow(String what, BaseRecord row, double x, double y, double w, double h) {
		assertEquals(what + " stored x", x, dbl(row, OlioFieldNames.FIELD_PB_X), EPS);
		assertEquals(what + " stored y", y, dbl(row, OlioFieldNames.FIELD_PB_Y), EPS);
		assertEquals(what + " stored w", w, dbl(row, OlioFieldNames.FIELD_PB_W), EPS);
		assertEquals(what + " stored h", h, dbl(row, OlioFieldNames.FIELD_PB_H), EPS);
	}

	private static String lower(String s) {
		return (s != null ? s.toLowerCase(Locale.ROOT) : "");
	}

	private void refused(String what, int status, String contains, Call c) throws Exception {
		try {
			c.run();
		}
		catch(PictureBookException e) {
			logger.info("[p92] " + what + ": " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": status (" + e.getMessage() + ")", status, e.getStatus());
			assertNotNull(what + ": message", e.getMessage());
			assertTrue(what + ": message must contain '" + contains + "' but was: " + e.getMessage(), e.getMessage().contains(contains));
			return;
		}
		fail(what + ": expected PictureBookException " + status + " but nothing was thrown");
	}

	// ─────────────────────────────── scripted renderer / QA / backend (TestPbQaHookOffline) ───────────────────────────────

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

	private static final class ScriptedQa implements QaProvider {
		static final String NAME = "scripted";
		final Deque<QaReport> script = new ArrayDeque<>();
		final List<byte[]> judged = new ArrayList<>();
		final List<RenderPlan> plans = new ArrayList<>();

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
			return true;
		}

		@Override
		public QaReport check(RenderPlan plan, byte[] imageBytes) {
			judged.add(imageBytes);
			plans.add(plan);
			QaReport r = script.poll();
			if(r == null) {
				throw new IllegalStateException("The QA script has no verdict for image " + judged.size());
			}
			return r;
		}
	}

	/// PASS: the one planned figure seen.
	private static QaReport passReport() {
		return QaReport.builder(ScriptedQa.NAME).model("scripted-v1").durationMs(5L)
			.personCount(1, Integer.valueOf(1), "one figure").build();
	}

	private static final class QaBackend implements RenderBackend {
		final ScriptedAdapter adapter;
		final FixtureGraphClient client;
		final PbQaUtil.Selection selection;
		int selections;
		String checkpoint;

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
			return selection;
		}
	}

	private static QaBackend backend(ScriptedAdapter adapter, ScriptedQa qa) {
		return new QaBackend(adapter, new FixtureGraphClient().pruneUnusedLoaders(), new PbQaUtil.Selection(qa, "scripted"));
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A V3 book with its world, a MANUAL COVER composition (text + band + one actor row + one element row unless
	/// the case says otherwise), its composition-plan node, the book style pinned to {@link #MODEL} with the
	/// inventory seeded, and auto-approve on; owned by a non-admin user.
	private static final class Fixture {
		BaseRecord owner;
		BaseRecord book;
		BaseRecord workflow;
		BaseRecord node;
		BaseRecord cover;
		BaseRecord actorRow;
		BaseRecord elementRow;
		String slug;
		String bookOid;
		String coverOid;
		SDUtil sdu;
		BaseRecord style;
	}

	/// The book, its world and its MANUAL cover; no text, no rows, no node yet.
	private Fixture bookWithCover(String prefix) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser("pbPlanOwner");
		assertNotNull("owner", f.owner);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		long orgId = orgOf(f.owner);
		f.slug = prefix + shortId();
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "Offline cover " + f.slug);
		assertNotNull("createBook", f.book);
		f.bookOid = oidOf(f.book);
		BookContext bctx = PbBookUtil.openBookContext(f.owner, f.book);
		assertNotNull("book context", bctx);
		assertNotNull("book world", bctx.getWorld());
		f.book = PbBookUtil.readBook(f.owner, f.bookOid, orgId);
		assertNotNull("book re-read", f.book);

		CoverResult cr = PbCoverUtil.getCreate(f.owner, f.book, PbCompositionPurposeEnumType.COVER, PbCoverUtil.Mode.MANUAL, null);
		assertNotNull("getCreate", cr);
		assertTrue("MANUAL cover written: " + cr.failureReason, cr.isWritten());
		assertTrue("created now", cr.created);
		f.coverOid = oidOf(cr.composition);
		f.cover = PbComposeUtil.readComposition(f.owner, f.coverOid, orgId);
		assertNotNull("cover re-read", f.cover);
		assertEquals("purpose", PbCompositionPurposeEnumType.COVER.name(), status(f.cover.get(OlioFieldNames.FIELD_PB_PURPOSE)));
		f.sdu = new SDUtil(SDAPIEnumType.SWARM, OFFLINE_SERVER);
		return f;
	}

	/// Text + band on the cover, one actor row and one element row at the given boxes, then the node, the style
	/// pin, the inventory seed and auto-approve. {@code band} null leaves the composition's band untouched (UNKNOWN).
	private void dressCover(Fixture f, String band, double[] actorBox, double[] elementBox) throws Exception {
		long orgId = orgOf(f.owner);
		Map<String, Object> fields = map(
			OlioFieldNames.FIELD_PB_SETTING_TEXT, COVER_SETTING,
			OlioFieldNames.FIELD_PB_ACTION, COVER_ACTION,
			OlioFieldNames.FIELD_PB_MOOD, COVER_MOOD);
		if(band != null) {
			fields.put(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND, band);
		}
		PbComposeUtil.patchCompositionFields(f.owner, f.cover, fields);
		f.cover = PbComposeUtil.readComposition(f.owner, f.coverOid, orgId);
		assertNotNull("cover after patch", f.cover);
		assertEquals("settingText persisted", COVER_SETTING, f.cover.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
		if(band != null) {
			assertEquals("titleSafeBand persisted", band.toUpperCase(Locale.ROOT), status(f.cover.get(OlioFieldNames.FIELD_PB_TITLE_SAFE_BAND)));
		}
		if(actorBox != null) {
			f.actorRow = PbComposeUtil.createActorRow(f.owner, f.book, f.cover, map(
				FieldNames.FIELD_DESCRIPTION, ACTOR_TEXT,
				OlioFieldNames.FIELD_PB_ROLE, "hero",
				OlioFieldNames.FIELD_PB_X, Double.valueOf(actorBox[0]), OlioFieldNames.FIELD_PB_Y, Double.valueOf(actorBox[1]),
				OlioFieldNames.FIELD_PB_W, Double.valueOf(actorBox[2]), OlioFieldNames.FIELD_PB_H, Double.valueOf(actorBox[3])));
			assertNotNull("actor row", f.actorRow);
			assertRow("actor", f.actorRow, actorBox[0], actorBox[1], actorBox[2], actorBox[3]);
		}
		if(elementBox != null) {
			f.elementRow = PbComposeUtil.createElementRow(f.owner, f.cover, map(
				OlioFieldNames.FIELD_PB_ELEMENT_TYPE, "prop",
				FieldNames.FIELD_DESCRIPTION, ELEMENT_TEXT,
				OlioFieldNames.FIELD_PB_X, Double.valueOf(elementBox[0]), OlioFieldNames.FIELD_PB_Y, Double.valueOf(elementBox[1]),
				OlioFieldNames.FIELD_PB_W, Double.valueOf(elementBox[2]), OlioFieldNames.FIELD_PB_H, Double.valueOf(elementBox[3])));
			assertNotNull("element row", f.elementRow);
			assertRow("element", f.elementRow, elementBox[0], elementBox[1], elementBox[2], elementBox[3]);
		}
		ensureNode(f);
		finishPrepare(f);
	}

	/// The composition-plan node through the facade helper the P9-3 render route will call.
	private void ensureNode(Fixture f) {
		f.workflow = PbGraphUtil.getCreateWorkflow(f.owner, f.book, PbBookUtil.workflowGroupPath(f.slug));
		assertNotNull("workflow", f.workflow);
		Map<String, Object> out = new LinkedHashMap<>();
		f.node = PbServiceFacade.ensureCompositionPlanNode(f.owner, f.book, f.workflow, f.cover, out);
		assertNotNull("composition-plan node", f.node);
		assertEquals("node handle", PbPipelineUtil.compositionPlanHandle(f.coverOid), f.node.get(OlioFieldNames.FIELD_PB_HANDLE));
		assertEquals("node scope", PbPipelineUtil.SCOPE_COMPOSITION, f.node.get(OlioFieldNames.FIELD_PB_SCOPE));
		assertEquals("node scopeRef", f.coverOid, f.node.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		assertEquals("node type", PbNodeTypeEnumType.RENDER_PLAN.name(), status(f.node.get(OlioFieldNames.FIELD_PB_NODE_TYPE)));
		assertEquals("nodeObjectId", oidOf(f.node), out.get("nodeObjectId"));
		assertEquals("materialized now", Boolean.TRUE, out.get("nodeMaterialized"));
	}

	/// Style pin + inventory seed + auto-approve on, then re-read the book (the TestPbQaHookOffline finish).
	private void finishPrepare(Fixture f) throws Exception {
		f.style = pinBookStyle(f.owner, f.book, f.slug, MODEL);
		seedInventory(OFFLINE_SERVER, List.of(MODEL));
		assertTrue("autoApprove on", PbBookUtil.setAutoApprove(f.owner, f.book, true));
		CacheUtil.clearCache();
		f.book = PbBookUtil.readBook(f.owner, f.bookOid, orgOf(f.owner));
		assertNotNull("book re-read", f.book);
		assertEquals("autoApprove read back", Boolean.TRUE, f.book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
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

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void seedInventory(String server, List<String> installed) throws Exception {
		java.lang.reflect.Field cacheField = SwarmModelInventory.class.getDeclaredField("CACHE");
		cacheField.setAccessible(true);
		Map cache = (Map) cacheField.get(null);
		java.lang.reflect.Constructor<SwarmModelInventory> ctor = SwarmModelInventory.class.getDeclaredConstructor(String.class, List.class, long.class);
		ctor.setAccessible(true);
		cache.put(server.trim(), ctor.newInstance(server.trim(), new ArrayList<>(installed), System.currentTimeMillis()));
	}

	private BaseRecord pinBookStyle(BaseRecord u, BaseRecord bk, String tag, String model) throws Exception {
		long orgId = orgOf(u);
		Map<String, String> pins = new LinkedHashMap<>();
		for(PbModelTaskEnumType t : new PbModelTaskEnumType[] { PbModelTaskEnumType.PORTRAIT, PbModelTaskEnumType.LOCATION,
				PbModelTaskEnumType.SCENE_DIRECT, PbModelTaskEnumType.INPAINT }) {
			pins.put(ModelTaskKeys.key(t), model);
		}
		BaseRecord style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, style, "p92-" + tag + "-" + shortId(), STYLE_PATH, orgId);
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

	private static List<BaseRecord> baseArtifacts(Fixture f) {
		List<BaseRecord> chain = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_BASE);
		List<BaseRecord> fresh = new ArrayList<>();
		for(BaseRecord a : chain) {
			BaseRecord full = PbArtifactUtil.readArtifact(f.owner, oidOf(a), orgOf(f.owner));
			assertNotNull("artifact " + oidOf(a), full);
			fresh.add(full);
		}
		return fresh;
	}

	private static Map<String, Object> run(Fixture f, QaBackend backend, PbQualityPresetEnumType preset) {
		long t0 = System.currentTimeMillis();
		Map<String, Object> out = PbNodeExecutor.executeNodeRun(f.owner, f.book, f.workflow, f.node, f.sdu,
			new PlanOptions(null, false, preset, null), null, backend);
		assertNotNull("run DTO", out);
		assertEquals("run status", PbRunStatusEnumType.COMPLETED.toString(), status(out.get("runStatus")));
		assertNotNull("run objectId", out.get("runObjectId"));
		LogUtil.info("[p92] " + preset + " run of " + f.slug + " in " + (System.currentTimeMillis() - t0) + " ms: strategy="
			+ out.get("strategy") + " autoApproved=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVED) + " skipped=" + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED)
			+ " degradations=" + PbNodeExecutor.degradationsOf(out));
		return out;
	}

	/// Quiet alias so the static {@link #run} can log through the test logger.
	private static final class LogUtil {
		static void info(String msg) {
			org.apache.logging.log4j.LogManager.getLogger(TestPbCoverPlanOffline.class).info(msg);
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> variants(Map<String, Object> out, int expected) {
		Object vs = out.get(PbNodeExecutor.KEY_VARIANTS);
		assertTrue("variants is a list: " + vs, vs instanceof List);
		List<Map<String, Object>> list = (List<Map<String, Object>>) vs;
		assertEquals("variants", expected, list.size());
		return list;
	}

	private static byte[] solid(int r, int g, int b) {
		return MaskImage.solid(W, H, new Color(r, g, b));
	}

	private RenderPlan plan(Fixture f) {
		RenderPlan plan = PbRenderPlanUtil.buildCompositionPlan(f.owner, f.book, f.cover, f.node, PlanOptions.NONE,
			f.sdu.getApiType(), f.sdu.getServer());
		assertNotNull("plan", plan);
		logger.info("[p92] plan of " + f.slug + ": purpose=" + plan.getPurpose() + " reserved=" + boxText(plan.getReserved())
			+ " actors=" + plan.getActors().size() + " elements=" + plan.getElements().size() + " strategy=" + (plan.getStrategy() != null ? plan.getStrategy().getName() : null)
			+ " degradations=" + plan.getDegradations().size());
		return plan;
	}

	private static String boxText(RenderPlan.Box b) {
		return (b == null ? "null" : "(" + b.getX() + "," + b.getY() + "," + b.getW() + "," + b.getH() + ")");
	}

	private static String basePrompt(RenderPlan plan) {
		CompiledPrompt base = plan.getCompiled().get(RenderPlan.STAGE_BASE);
		assertNotNull("a compiled BASE prompt", base);
		assertNotNull("BASE prompt text", base.getPrompt());
		return base.getPrompt();
	}

	private static String sdxlPrompt(RenderPlan plan) {
		List<RenderPlan.Degradation> out = new ArrayList<>();
		CompiledPrompt p = new SdxlCompiler().compile(plan, RenderPlan.STAGE_BASE, out);
		assertNotNull("SDXL compile", p);
		return p.getPrompt();
	}

	private static String flux2Prompt(RenderPlan plan) {
		List<RenderPlan.Degradation> out = new ArrayList<>();
		CompiledPrompt p = new Flux2Compiler().compile(plan, RenderPlan.STAGE_BASE, out);
		assertNotNull("FLUX2 compile", p);
		return p.getPrompt();
	}

	// ─────────────────────────────── cases ───────────────────────────────

	@Test
	public void topBandPlanClipsRowsAndBothCompilersSpeakIt() throws Exception {
		logger.info("topBandPlanClipsRowsAndBothCompilersSpeakIt");
		Fixture f = null;
		try {
			f = bookWithCover("p92top");
			dressCover(f, "top", new double[] { 0.3, 0.05, 0.4, 0.4 }, new double[] { 0.1, 0.1, 0.2, 0.2 });
			RenderPlan plan = plan(f);

			assertEquals("purpose", RenderPlan.PURPOSE_COVER, plan.getPurpose());
			assertBox("reserved", plan.getReserved(), 0.0, 0.0, 1.0, PbLayoutUtil.TITLE_SAFE_BAND_HEIGHT);
			assertTrue("reserved is top", plan.getReserved().isTop());
			assertNotNull("location text", plan.getLocation());
			assertTrue("the cover's own text is the subject: " + plan.getLocation().getDescription(),
				lower(plan.getLocation().getDescription()).contains("basalt headland"));

			assertEquals("one actor", 1, plan.getActors().size());
			RenderPlan.Actor actor = plan.getActors().get(0);
			assertEquals("the text row is its own actorId", oidOf(f.actorRow), actor.getActorId());
			assertBox("actor (pushed below the band, height kept)", actor.getBox(), 0.3, 0.25, 0.4, 0.4);
			assertEquals("one element", 1, plan.getElements().size());
			assertBox("element (pushed below the band)", plan.getElements().get(0).getBox(), 0.1, 0.25, 0.2, 0.2);

			/// The stored rows are not touched by the plan-time clip.
			long orgId = orgOf(f.owner);
			assertRow("actor after plan", PbComposeUtil.readActor(f.owner, oidOf(f.actorRow), orgId), 0.3, 0.05, 0.4, 0.4);
			assertRow("element after plan", PbComposeUtil.readElement(f.owner, oidOf(f.elementRow), orgId), 0.1, 0.1, 0.2, 0.2);

			/// Both compiler families speak the band; the plan's own BASE compile is FLUX2 (the pinned checkpoint).
			String base = basePrompt(plan);
			logger.info("[p92] BASE prompt: " + base);
			assertTrue("compiled BASE carries the clause: " + base, lower(base).contains(TOP_CLAUSE));
			assertTrue("the BASE prompt also carries the subject: " + base, lower(base).contains("basalt headland"));
			String flux = flux2Prompt(plan);
			assertTrue("FLUX2 carries the clause: " + flux, lower(flux).contains(TOP_CLAUSE));
			String sdxl = sdxlPrompt(plan);
			logger.info("[p92] SDXL prompt: " + sdxl);
			assertTrue("SDXL carries the clause: " + sdxl, lower(sdxl).contains(TOP_CLAUSE));

			/// The band travels with the plan.
			RenderPlan back = RenderPlan.fromJson(plan.toJson());
			assertNotNull("fromJson", back);
			assertBox("reserved after round-trip", back.getReserved(), 0.0, 0.0, 1.0, PbLayoutUtil.TITLE_SAFE_BAND_HEIGHT);
			assertEquals("purpose after round-trip", RenderPlan.PURPOSE_COVER, back.getPurpose());
			assertBox("actor after round-trip", back.getActors().get(0).getBox(), 0.3, 0.25, 0.4, 0.4);
			assertEquals("content hash is stable across the round-trip", plan.contentHash(), back.contentHash());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void bottomBandSlidesRowsUp() throws Exception {
		logger.info("bottomBandSlidesRowsUp");
		Fixture f = null;
		try {
			f = bookWithCover("p92bot");
			dressCover(f, "bottom", new double[] { 0.3, 0.5, 0.4, 0.4 }, new double[] { 0.1, 0.7, 0.2, 0.2 });
			RenderPlan plan = plan(f);

			double band = PbLayoutUtil.TITLE_SAFE_BAND_HEIGHT;
			assertBox("reserved", plan.getReserved(), 0.0, 1.0 - band, 1.0, band);
			assertFalse("reserved is bottom", plan.getReserved().isTop());
			assertBox("actor (slid up to end at the band)", plan.getActors().get(0).getBox(), 0.3, 0.35, 0.4, 0.4);
			assertBox("element (slid up to end at the band)", plan.getElements().get(0).getBox(), 0.1, 0.55, 0.2, 0.2);
			String base = basePrompt(plan);
			assertTrue("compiled BASE says bottom: " + base, lower(base).contains(BOTTOM_CLAUSE));
			String sdxl = sdxlPrompt(plan);
			assertTrue("SDXL says bottom: " + sdxl, lower(sdxl).contains(BOTTOM_CLAUSE));
			assertBox("reserved after round-trip", RenderPlan.fromJson(plan.toJson()).getReserved(), 0.0, 1.0 - band, 1.0, band);
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void noBandReservesNothing() throws Exception {
		logger.info("noBandReservesNothing");
		Fixture f = null;
		try {
			f = bookWithCover("p92non");
			dressCover(f, "none", new double[] { 0.3, 0.05, 0.4, 0.4 }, new double[] { 0.1, 0.1, 0.2, 0.2 });
			RenderPlan plan = plan(f);

			assertEquals("purpose", RenderPlan.PURPOSE_COVER, plan.getPurpose());
			assertNull("NONE reserves nothing", plan.getReserved());
			assertBox("actor unclipped", plan.getActors().get(0).getBox(), 0.3, 0.05, 0.4, 0.4);
			assertBox("element unclipped", plan.getElements().get(0).getBox(), 0.1, 0.1, 0.2, 0.2);
			String base = basePrompt(plan);
			assertFalse("no clause in BASE: " + base, lower(base).contains(ANY_CLAUSE));
			assertFalse("no clause in FLUX2: " + base, lower(flux2Prompt(plan)).contains(ANY_CLAUSE));
			assertFalse("no clause in SDXL: " + base, lower(sdxlPrompt(plan)).contains(ANY_CLAUSE));
			assertNull("null after round-trip", RenderPlan.fromJson(plan.toJson()).getReserved());
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void emptyCoverAndSceneCompositionAreRefused() throws Exception {
		logger.info("emptyCoverAndSceneCompositionAreRefused");
		Fixture f = null;
		try {
			f = bookWithCover("p92ref");
			ensureNode(f);
			finishPrepare(f);
			final Fixture fx = f;
			long orgId = orgOf(f.owner);

			/// A MANUAL cover carries no text: nothing to render from, and the prompt override is the only way in.
			refused("empty cover", 400, "has no description to render from",
				() -> PbRenderPlanUtil.buildCompositionPlan(fx.owner, fx.book, fx.cover, fx.node, PlanOptions.NONE, fx.sdu.getApiType(), fx.sdu.getServer()));
			RenderPlan overridden = PbRenderPlanUtil.buildCompositionPlan(f.owner, f.book, f.cover, f.node,
				new PlanOptions("a lighthouse on a black rock, storm light", false, null, null), f.sdu.getApiType(), f.sdu.getServer());
			assertNotNull("a prompt override is enough", overridden);
			assertEquals(RenderPlan.PURPOSE_COVER, overridden.getPurpose());
			assertTrue("the override is the prompt: " + basePrompt(overridden), lower(basePrompt(overridden)).contains("lighthouse"));

			/// A SCENE composition (composeMigrated, no LLM) is not a cover and is refused by purpose.
			BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, 0, "Scene 0", PbBookUtil.bookGroupPath(f.slug));
			assertNotNull("scene", scene);
			BaseRecord sp = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_CB_SD_PROMPT);
			sp.set(OlioFieldNames.FIELD_PB_SETTING, SCENE_SETTING);
			sp.set(OlioFieldNames.FIELD_CB_SD_PROMPT, SCENE_SD_PROMPT);
			assertNotNull("scene text patch", IOSystem.getActiveContext().getAccessPoint().update(f.owner, sp));
			scene = PbBookUtil.readScene(f.owner, oidOf(scene), orgId);
			ComposeResult cr = PbComposeUtil.composeMigrated(f.owner, f.book, scene, false);
			assertTrue("SCENE composition written: " + cr.failureReason, cr.isWritten());
			BaseRecord sceneComp = PbComposeUtil.readComposition(f.owner, oidOf(cr.composition), orgId);
			assertNotNull("scene composition", sceneComp);
			refused("scene composition", 400, "is not a cover-type composition",
				() -> PbRenderPlanUtil.buildCompositionPlan(fx.owner, fx.book, sceneComp, fx.node, PlanOptions.NONE, fx.sdu.getApiType(), fx.sdu.getServer()));
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void executorRendersTheCoverThroughThePlanPath() throws Exception {
		logger.info("executorRendersTheCoverThroughThePlanPath");
		Fixture f = null;
		try {
			f = bookWithCover("p92run");
			dressCover(f, "top", new double[] { 0.3, 0.05, 0.4, 0.4 }, new double[] { 0.1, 0.1, 0.2, 0.2 });
			long orgId = orgOf(f.owner);

			/// ensureCompositionPlanNode is idempotent: the same node by handle, not materialized again.
			Map<String, Object> again = new LinkedHashMap<>();
			BaseRecord same = PbServiceFacade.ensureCompositionPlanNode(f.owner, f.book, f.workflow, f.cover, again);
			assertEquals("same node", oidOf(f.node), oidOf(same));
			assertEquals("not materialized again", Boolean.FALSE, again.get("nodeMaterialized"));

			int revisionBefore = intOf(f.cover.get(OlioFieldNames.FIELD_PB_REVISION));
			assertTrue("no SCENE composition before", PbComposeUtil.listSceneCompositions(f.owner, f.book).isEmpty());

			/// DRAFT: one final, QA off, auto-approved.
			byte[] png = solid(90, 120, 60);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(png, 9201, 800));
			ScriptedQa qa = new ScriptedQa();
			QaBackend backend = backend(adapter, qa);
			Map<String, Object> out = run(f, backend, PbQualityPresetEnumType.DRAFT);
			String runOid = str(out.get("runObjectId"));

			assertEquals("one renderer request", 1, adapter.requests.size());
			StageRequest req = adapter.requests.get(0);
			assertEquals("BASE stage", RenderPlan.STAGE_BASE, req.getStage());
			assertNotNull("the request carries the plan", req.getPlan());
			assertEquals("the plan is the cover plan", RenderPlan.PURPOSE_COVER, req.getPlan().getPurpose());
			assertBox("the plan's band", req.getPlan().getReserved(), 0.0, 0.0, 1.0, PbLayoutUtil.TITLE_SAFE_BAND_HEIGHT);
			assertEquals("the pinned checkpoint", MODEL, req.getModel());
			assertNotNull("request prompt", req.getPrompt());
			String prompt = req.getPrompt().getPrompt();
			logger.info("[p92] rendered prompt: " + prompt);
			assertTrue("the rendered prompt carries the clause: " + prompt, lower(prompt).contains(TOP_CLAUSE));
			assertTrue("the rendered prompt carries the subject: " + prompt, lower(prompt).contains("basalt headland"));
			assertEquals("the selector is never asked under DRAFT (QA off)", 0, backend.selections);

			/// The DTO: the composition-plan shape plus the plan description.
			assertEquals("compositionObjectId", f.coverOid, out.get("compositionObjectId"));
			assertEquals("purpose", PbCompositionPurposeEnumType.COVER.name(), status(out.get("purpose")));
			assertEquals("TEXT_ONLY (a described actor, no references)", PbStrategyEnumType.TEXT_ONLY.name(), out.get("strategy"));
			assertNotNull("planContentHash", out.get("planContentHash"));
			assertNotNull("planArtifactObjectId", out.get("planArtifactObjectId"));
			assertEquals("one final rendered", 1, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertNull("no sceneObjectId on a cover DTO", out.get("sceneObjectId"));
			assertNull("no qaMode under DRAFT", out.get(PbNodeExecutor.KEY_QA_MODE));
			List<Map<String, Object>> vs = variants(out, 1);
			assertEquals("auto-approved: " + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED), Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertEquals("the one variant is the approved artifact", out.get("artifactObjectId"), out.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), vs.get(0).get("reviewStatus"));
			assertEquals("headline reviewStatus", PbReviewStatusEnumType.APPROVED.name(), out.get("reviewStatus"));

			/// On the database: one BASE artifact of the run on the composition-plan node, approved and selected;
			/// the node DONE_UNVERIFIED before the approval; nothing composed a scene.
			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals("one base artifact", 1, bases.size());
			BaseRecord a0 = bases.get(0);
			assertEquals(oidOf(a0), out.get("artifactObjectId"));
			assertEquals("stamped with the run", runOid, PbArtifactUtil.attemptOf(a0));
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a0));
			assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, a0.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			assertTrue("selected", selected(a0));
			BaseRecord node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			String nodeStatus = status(node.get(OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertTrue("node status after the run: " + nodeStatus,
				PbNodeStatusEnumType.DONE_UNVERIFIED.name().equals(nodeStatus) || PbNodeStatusEnumType.DONE.name().equals(nodeStatus));
			assertTrue("still no SCENE composition", PbComposeUtil.listSceneCompositions(f.owner, f.book).isEmpty());
			BaseRecord coverAfter = PbComposeUtil.readComposition(f.owner, f.coverOid, orgId);
			assertEquals("the cover's revision is untouched", revisionBefore, intOf(coverAfter.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals("the cover is still COVER", PbCompositionPurposeEnumType.COVER.name(), status(coverAfter.get(OlioFieldNames.FIELD_PB_PURPOSE)));

			/// STANDARD on the same node: the preset's two variants, QA CANONICAL judges variant 0 under the cover plan.
			ScriptedAdapter adapter2 = new ScriptedAdapter().answer(ok(solid(60, 90, 120), 9202, 700), ok(solid(120, 60, 90), 9203, 700));
			ScriptedQa qa2 = new ScriptedQa().verdicts(passReport());
			QaBackend backend2 = backend(adapter2, qa2);
			Map<String, Object> out2 = run(f, backend2, PbQualityPresetEnumType.STANDARD);
			String runOid2 = str(out2.get("runObjectId"));
			assertEquals("two finals", 2, adapter2.requests.size());
			assertEquals("two finals rendered", 2, intOf(out2.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertEquals("selector asked once", 1, backend2.selections);
			assertEquals("handed the BASE checkpoint", MODEL, backend2.checkpoint);
			assertEquals("variant 0 judged", 1, qa2.judged.size());
			assertEquals("judged under the cover plan", RenderPlan.PURPOSE_COVER, qa2.plans.get(0).getPurpose());
			assertEquals("qaMode", QualityPresets.QA_CANONICAL, out2.get(PbNodeExecutor.KEY_QA_MODE));
			List<Map<String, Object>> vs2 = variants(out2, 2);
			assertEquals("variant 0 PASS", QaReport.Result.PASS.name(), ((Map<?, ?>) vs2.get(0).get(PbNodeExecutor.KEY_VARIANT_QA)).get("result"));
			/// The first run's approval stands: the existing-approved guard (PbArtifactUtil.autoApprove) refuses to
			/// approve over an APPROVED sibling on the same (node, role), so the second run is rendered, judged and
			/// left for review.
			assertEquals("not auto-approved over the standing approval", Boolean.FALSE, out2.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			String skipped = str(out2.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			assertNotNull("skip reason", skipped);
			assertTrue("existing-approved: " + skipped, skipped.startsWith("existing-approved"));
			List<BaseRecord> all = baseArtifacts(f);
			assertEquals("three base artifacts across the two attempts", 3, all.size());
			int ofRun2 = 0;
			int approved = 0;
			for(BaseRecord a : all) {
				if(runOid2.equals(PbArtifactUtil.attemptOf(a))) {
					ofRun2++;
					assertEquals("second-run artifact left unapproved", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a));
				}
				if(PbArtifactUtil.reviewStatusOf(a) == PbReviewStatusEnumType.APPROVED) {
					approved++;
					assertEquals("the standing approval is the first run's", oidOf(a0), oidOf(a));
				}
			}
			assertEquals("two of the second run", 2, ofRun2);
			assertEquals("exactly one approved base", 1, approved);
		}
		finally {
			cleanup(f);
		}
	}

	@Test
	public void foreignCompositionIsNotFoundInThisBook() throws Exception {
		logger.info("foreignCompositionIsNotFoundInThisBook");
		Fixture a = null;
		Fixture b = null;
		try {
			a = bookWithCover("p92fa");
			dressCover(a, "top", new double[] { 0.3, 0.05, 0.4, 0.4 }, null);
			b = bookWithCover("p92fb");
			assertFalse("compositionOfBook refuses the other book's cover", PbNodeExecutor.compositionOfBook(a.book, b.cover));
			assertTrue("compositionOfBook accepts its own", PbNodeExecutor.compositionOfBook(a.book, a.cover));

			/// A node in A's workflow whose scopeRef is B's cover: readable by the (same) owner, not A's.
			String wfPath = PbBookUtil.workflowGroupPath(a.slug);
			String handle = PbPipelineUtil.compositionPlanHandle(b.coverOid);
			BaseRecord n = PbGraphUtil.addNode(a.owner, a.workflow, handle, PbNodeTypeEnumType.RENDER_PLAN, wfPath, 1);
			assertNotNull("foreign node", n);
			assertTrue("scope patch", PbPipelineUtil.patchNodeScope(a.owner, a.workflow, n, PbPipelineUtil.SCOPE_COMPOSITION, b.coverOid, 1));
			BaseRecord foreign = PbGraphUtil.readNode(a.owner, oidOf(n), orgOf(a.owner));
			assertNotNull("foreign node re-read", foreign);

			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(solid(1, 2, 3), 9301, 100));
			QaBackend backend = backend(adapter, new ScriptedQa());
			final Fixture fa = a;
			refused("foreign composition", 404, "not found in this book",
				() -> PbNodeExecutor.executeNodeRun(fa.owner, fa.book, fa.workflow, foreign, fa.sdu,
					new PlanOptions(null, false, PbQualityPresetEnumType.DRAFT, null), null, backend));
			assertTrue("nothing rendered", adapter.requests.isEmpty());
			assertTrue("no artifact on the foreign node", PbArtifactUtil.listChain(a.owner, foreign, PbPipelineUtil.ROLE_BASE).isEmpty());
			BaseRecord after = PbGraphUtil.readNode(a.owner, oidOf(foreign), orgOf(a.owner));
			assertEquals("the node is FAILED", PbNodeStatusEnumType.FAILED.name(), status(after.get(OlioFieldNames.FIELD_PB_NODE_STATUS)));
		}
		finally {
			cleanup(a);
			cleanup(b);
		}
	}
}
