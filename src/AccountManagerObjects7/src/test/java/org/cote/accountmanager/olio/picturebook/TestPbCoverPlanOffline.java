package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.cote.accountmanager.olio.illustration.BandCanvas;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.FixtureGraphClient;
import org.cote.accountmanager.olio.illustration.Flux2Compiler;
import org.cote.accountmanager.olio.illustration.GraphInputs;
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
import org.cote.accountmanager.olio.illustration.WorkflowCompiler;
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
 * <p>
 * §24 I-4 (ledger 236 i): the band is <b>enforced</b>, not asked for. A plan that reserves one renders its figure
 * stages on the figure canvas ({@link BandCanvas}: W x (H - band rows), so no figure can occupy the band), then a
 * TITLE_BAND pass paints the band on the full canvas through the family's masked init-image graph over the padded
 * figure image. So the compiled BASE prompt no longer carries the clause - the TITLE_BAND compile does - and a run
 * makes two renderer requests per variant.
 * <ol>
 * <li>{@link #topBandPlanClipsRowsAndBothCompilersSpeakIt}: TOP -> purpose {@code COVER}, {@code reserved} =
 *     (0,0,1,0.25), the actor box (y 0.05, h 0.4) reads (y 0.25, h 0.4) and the element (y 0.1, h 0.2) reads
 *     (y 0.25, h 0.2) on the plan while the stored rows keep their values; the stages are BASE, TITLE_BAND; the
 *     compiled BASE prompt (FLUX2, the pinned checkpoint) carries the subject and NOT the "top quarter ... negative
 *     space" clause, the compiled TITLE_BAND prompt carries the clause and the setting and none of the actor /
 *     element text; a direct {@link Flux2Compiler} / {@link SdxlCompiler} compile of the banded plan still speaks the
 *     clause (the compilers' own contract); {@code toJson} / {@code fromJson} round-trips the band.</li>
 * <li>{@link #bottomBandSlidesRowsUp}: BOTTOM -> {@code reserved} = (0,0.75,1,0.25); an actor ending at 0.9 is
 *     slid up to end at 0.75 keeping its height; the TITLE_BAND clause says "bottom quarter", BASE says nothing.</li>
 * <li>{@link #noBandReservesNothing}: NONE -> {@code getReserved()} null, rows unclipped, no TITLE_BAND stage, no
 *     clause in either compiler.</li>
 * <li>{@link #figureCanvasArithmetic}: {@code Box.inFigureCanvas} remaps a clipped box onto the figure canvas (top and
 *     bottom bands); {@code BandCanvas.bandPixels} rounds to the 16-grain (768 -> 192, 1024 -> 256); {@code pad}
 *     returns the full canvas with the figure rows intact and the band rows flat.</li>
 * <li>{@link #emptyCoverAndSceneCompositionAreRefused}: a MANUAL cover with no text is a 400 "has no description to
 *     render from"; a SCENE composition handed to {@code buildCompositionPlan} is a 400 "is not a cover-type
 *     composition".</li>
 * <li>{@link #executorRendersTheCoverThroughThePlanPath}: {@code ensureCompositionPlanNode} is idempotent (handle
 *     {@code compositionPlanHandle}, scope {@code composition}); a DRAFT run (the preset's 512x512 canvas, a 128-row
 *     band) makes two renderer requests - BASE at 512x384 (the figure canvas) whose plan is the cover plan (purpose
 *     COVER) and whose prompt carries the subject and no clause, then TITLE_BAND at 512x512, a compiled graph carrying
 *     the padded figure as {@code init} and a mask covering exactly the band's rows, creativity 1.0 (the band is
 *     painted, not a refinement of the padded fill), no grow, a one-grain feather, no references,
 *     the clause in its prompt - records one {@code stage} artifact (the figure image) and one BASE artifact (the band
 *     pass's output) stamped with the run,
 *     completes the node DONE_UNVERIFIED then auto-approves it, the DTO names {@code compositionObjectId} /
 *     {@code purpose} / {@code strategy TEXT_ONLY} / {@code stagesRendered [BASE, TITLE_BAND]}, and nothing composed a
 *     scene (no SCENE composition, the cover's revision unchanged). A second STANDARD run on the same node (1024x1024,
 *     a 256-row band) renders the preset's two variants (four requests) and the QA hook judges variant 0's band
 *     output under the cover plan.</li>
 * <li>{@link #bandPassFailureDegradesAndDeliversThePaddedCanvas}: the renderer refuses the TITLE_BAND pass: the node
 *     still completes, the delivered image is the figure padded to the plan's 512x512 canvas (figure rows intact, the
 *     band flat), the run carries a {@code TITLE_BAND_FAILED} degradation and a TITLE_BAND stage error, auto-approve is
 *     vetoed, and the figure image is still the {@code stage} artifact the final chains to.</li>
 * <li>{@link #foreignCompositionIsNotFoundInThisBook}: a node whose {@code scopeRef} is another book's cover is a
 *     404 "not found in this book" and the run closes FAILED before anything renders.</li>
 * </ol>
 * Timings are logged, never asserted.
 */
public class TestPbCoverPlanOffline extends BaseTest {

	private static final String MODEL = "flux2Klein_9b";
	private static final String OFFLINE_SERVER = "http://127.0.0.1:9";
	/// The images the scripted renderer answers with (decoupled from the request size, as the executor stores
	/// whatever the renderer returned): a W x H "final" and a W x FIGURE_H "figure" (0.25 x 768 = 192 band rows).
	private static final int W = 1024;
	private static final int H = 768;
	private static final int BAND_PX = 192;
	private static final int FIGURE_H = H - BAND_PX;
	/// The plan's canvas is the square default scaled by the preset (CanvasResolver.SQUARE_EDGE x
	/// QualityPresets resolutionScale: DRAFT 0.5, STANDARD 1.0), and the band's rows are a quarter of it on the
	/// 16-grain (§24 I-4, BandCanvas): the figure stages render on the canvas less those rows.
	private static final int DRAFT_EDGE = 512;
	private static final int DRAFT_BAND_PX = 128;
	private static final int STANDARD_EDGE = 1024;
	private static final int STANDARD_BAND_PX = 256;
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
		return ok(png, seed, durationMs, W, H);
	}

	private static StageResult ok(byte[] png, int seed, long durationMs, int width, int height) {
		return StageResult.builder()
			.image(new StageImage(png, seed, true, width, height))
			.durationMs(durationMs)
			.requestJson("{\"offline\":true}")
			.backendGraph("{\"stage\":\"offline\"}")
			.build();
	}

	/// The scripted answer to a figure-canvas BASE request: a solid W x FIGURE_H image.
	private static StageResult figureOk(int r, int g, int b, int seed, long durationMs) {
		return ok(solidFigure(r, g, b), seed, durationMs, W, FIGURE_H);
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

	private static byte[] solidFigure(int r, int g, int b) {
		return MaskImage.solid(W, FIGURE_H, new Color(r, g, b));
	}

	/// PNG bytes of a test image (the production encoder is package-private to the illustration package).
	private static byte[] png(BufferedImage img) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			javax.imageio.ImageIO.write(img, "png", out);
			return out.toByteArray();
		}
		catch(java.io.IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/// A synthetic painted cover on the full canvas: the figure rows colour {@code a}, the band {@code a} less
	/// {@code off}, and a linear ramp between them across the two feathers centred on the seam - what the feathered
	/// composite leaves when the painted band is uniformly darker than the figure (the measured FLUX.2 defect).
	private static byte[] seamedCover(RenderPlan.Box band, Color a, int[] off) {
		int seam = (band.isTop() ? BAND_PX : H - BAND_PX);
		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		for(int y = 0; y < H; y++) {
			int into = (band.isTop() ? seam - y : y - seam + 1);
			double w = (into >= BandCanvas.FEATHER ? 1.0 : (into <= -BandCanvas.FEATHER ? 0.0 : (into + BandCanvas.FEATHER) / (2.0 * BandCanvas.FEATHER)));
			int rgb = new Color((int) Math.round(a.getRed() - off[0] * w), (int) Math.round(a.getGreen() - off[1] * w), (int) Math.round(a.getBlue() - off[2] * w)).getRGB();
			for(int x = 0; x < W; x++) {
				img.setRGB(x, y, rgb);
			}
		}
		return png(img);
	}

	/// The largest per-channel distance of any row's mean from {@code a}, and the largest per-channel step between
	/// adjacent rows, over the whole image: {deviation, step}.
	private static double[] rowProfile(BufferedImage img, Color a) {
		double maxDev = 0.0;
		double maxStep = 0.0;
		double[] prev = null;
		int[] ref = { a.getRed(), a.getGreen(), a.getBlue() };
		for(int y = 0; y < img.getHeight(); y++) {
			double[] row = new double[3];
			for(int x = 0; x < img.getWidth(); x++) {
				int rgb = img.getRGB(x, y);
				row[0] += (rgb >> 16) & 0xFF;
				row[1] += (rgb >> 8) & 0xFF;
				row[2] += rgb & 0xFF;
			}
			for(int c = 0; c < 3; c++) {
				row[c] /= img.getWidth();
				maxDev = Math.max(maxDev, Math.abs(row[c] - ref[c]));
				if(prev != null) {
					maxStep = Math.max(maxStep, Math.abs(row[c] - prev[c]));
				}
			}
			prev = row;
		}
		return new double[] { maxDev, maxStep };
	}

	/// The `stage` chain of the node (the figure image every TITLE_BAND pass painted over), fully read.
	private static List<BaseRecord> stageArtifacts(Fixture f) {
		List<BaseRecord> chain = PbArtifactUtil.listChain(f.owner, f.node, PbPipelineUtil.ROLE_STAGE);
		List<BaseRecord> fresh = new ArrayList<>();
		for(BaseRecord a : chain) {
			BaseRecord full = PbArtifactUtil.readArtifact(f.owner, oidOf(a), orgOf(f.owner));
			assertNotNull("stage artifact " + oidOf(a), full);
			fresh.add(full);
		}
		return fresh;
	}

	@SuppressWarnings("unchecked")
	private static List<String> stages(Map<String, Object> out, String key) {
		Object v = out.get(key);
		assertTrue(key + " is a list: " + v, v instanceof List);
		return (List<String>) v;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> stageErrors(Map<String, Object> out) {
		Object v = out.get(PbNodeExecutor.KEY_STAGE_ERRORS);
		assertTrue("stageErrors is a list: " + v, v instanceof List);
		return (List<Map<String, Object>>) v;
	}

	private static String degradation(Map<String, Object> out, String code) {
		for(String d : PbNodeExecutor.degradationsOf(out)) {
			if(d.startsWith(code + ":")) {
				return d;
			}
		}
		return null;
	}

	private static void assertSize(String what, byte[] png, int width, int height) {
		java.awt.image.BufferedImage img = MaskImage.decode(png);
		assertNotNull(what + " decodes", img);
		assertEquals(what + " width", width, img.getWidth());
		assertEquals(what + " height", height, img.getHeight());
	}

	/// The figure-canvas BASE request (§24 I-4): the plan's canvas is the preset's square ({@code edge} x
	/// {@code edge}) but the request is {@code edge} x ({@code edge} - {@code bandPx}), and its prompt carries the
	/// subject and no band clause (the band is enforced by the canvas, not asked for).
	private static void assertFigurePass(StageRequest req, int edge, int bandPx) {
		assertEquals("BASE stage", RenderPlan.STAGE_BASE, req.getStage());
		assertNotNull("the request carries the plan", req.getPlan());
		assertEquals("the plan is the cover plan", RenderPlan.PURPOSE_COVER, req.getPlan().getPurpose());
		assertEquals("the plan's canvas width is the preset's", edge, req.getPlan().getCanvas().getWidth());
		assertEquals("the plan's canvas height is the preset's", edge, req.getPlan().getCanvas().getHeight());
		assertEquals("the band's rows on that canvas", bandPx, BandCanvas.bandPixels(edge, req.getPlan().getReserved()));
		assertEquals("BASE width", edge, req.getWidth());
		assertEquals("BASE renders on the figure canvas", edge - bandPx, req.getHeight());
		assertEquals("the pinned checkpoint", MODEL, req.getModel());
		assertNotNull("request prompt", req.getPrompt());
		String prompt = req.getPrompt().getPrompt();
		assertTrue("the BASE prompt carries the subject: " + prompt, lower(prompt).contains("basalt headland"));
		assertFalse("the BASE prompt does not ask for the band: " + prompt, lower(prompt).contains(ANY_CLAUSE));
		assertNull("BASE is the plain T2I request (TEXT_ONLY, no references): no graph", req.getGraph());
	}

	/// The TITLE_BAND request (§24 I-4): the full canvas ({@code edge} square), the BASE pick, a compiled graph whose
	/// inputs are the padded figure (the full canvas) as `init` and a mask lit over exactly the band's {@code bandPx}
	/// rows, and a prompt that speaks the clause and the setting but none of the actor / element text. The generator
	/// params the fixture client saw for it carry the inpaint knobs at the family's creativity and no references.
	private static void assertBandPass(StageRequest req, Map<String, Object> params, String clause, int edge, int bandPx) {
		assertEquals("TITLE_BAND stage", RenderPlan.STAGE_TITLE_BAND, req.getStage());
		assertEquals("TITLE_BAND width", edge, req.getWidth());
		assertEquals("TITLE_BAND renders on the full canvas", edge, req.getHeight());
		assertEquals("the BASE pick's model", MODEL, req.getModel());
		assertNotNull("a compiled graph", req.getGraph());
		GraphInputs inputs = req.getInputs();
		assertNotNull("graph inputs", inputs);
		assertNotNull("an init image: " + inputs.names(), inputs.image(WorkflowCompiler.TAG_INIT));
		assertNotNull("a mask image: " + inputs.names(), inputs.image(WorkflowCompiler.TAG_MASK));
		assertEquals("init and mask only (no references)", 2, inputs.imageCount());
		assertSize("the init image is the padded figure", inputs.image(WorkflowCompiler.TAG_INIT), edge, edge);
		assertSize("the mask", inputs.image(WorkflowCompiler.TAG_MASK), edge, edge);
		double coverage = MaskImage.coverage(inputs.image(WorkflowCompiler.TAG_MASK));
		assertEquals("the mask covers exactly the band's rows", bandPx / (double) edge, coverage, 1e-6);
		assertNotNull("TITLE_BAND prompt", req.getPrompt());
		String prompt = req.getPrompt().getPrompt();
		assertTrue("the TITLE_BAND prompt carries the clause: " + prompt, lower(prompt).contains(clause));
		assertTrue("the TITLE_BAND prompt carries the setting: " + prompt, lower(prompt).contains("basalt headland"));
		assertFalse("no actor text in the TITLE_BAND prompt: " + prompt, lower(prompt).contains("hood up"));
		assertFalse("no element text in the TITLE_BAND prompt: " + prompt, lower(prompt).contains("brass lantern"));
		assertNotNull("generator params", params);
		assertTrue("params carry initimage", params.containsKey("initimage"));
		assertTrue("params carry maskimage", params.containsKey("maskimage"));
		assertFalse("params carry no references", params.containsKey("promptimages"));
		RendererCapabilities.InpaintParams family = RendererCapabilities.forModel(MODEL).getInpaint();
		assertEquals("creativity 1.0: the band is painted from the first step", 1.0, ((Number) params.get("initimagecreativity")).doubleValue(), EPS);
		assertEquals("params width", edge, ((Number) params.get("width")).intValue());
		assertEquals("params height", edge, ((Number) params.get("height")).intValue());
		assertEquals("the family's inpaint steps", family.getSteps(), ((Number) params.get("steps")).intValue());
		assertEquals("the band's rows are exact: the mask is not grown", 0, ((Number) params.get("maskgrow")).intValue());
		assertEquals("the mask is feathered one grain", BandCanvas.FEATHER, ((Number) params.get("maskblur")).intValue());
		assertEquals("one grain", BandCanvas.GRAIN, BandCanvas.FEATHER);
		assertEquals("params model", MODEL, params.get("model"));
	}

	/// The generator params of the i-th request the fixture client answered, in request order.
	private static Map<String, Object> generatorParams(QaBackend backend, int index) {
		List<Map<String, Object>> reqs = backend.client.requests();
		assertTrue("generator request " + index + " of " + reqs.size(), index < reqs.size());
		return reqs.get(index);
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

	/// The plan's compiled TITLE_BAND prompt (§24 I-4): the location-only band compile the executor's pass sends.
	private static String bandPrompt(RenderPlan plan) {
		CompiledPrompt band = plan.getCompiled().get(RenderPlan.STAGE_TITLE_BAND);
		assertNotNull("a compiled TITLE_BAND prompt", band);
		assertNotNull("TITLE_BAND prompt text", band.getPrompt());
		return band.getPrompt();
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

			/// §24 I-4: the band is enforced by the canvas, so the strategy plans BASE then TITLE_BAND; the plan's own
			/// BASE compile (FLUX2, the pinned checkpoint) carries the subject and no clause, the TITLE_BAND compile
			/// carries the clause and the setting and none of the actor / element text. Both compiler families still
			/// speak the band when handed the banded plan directly (that is what the TITLE_BAND compile relies on).
			assertEquals("stages", List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_TITLE_BAND), plan.getStrategy().getStages());
			String base = basePrompt(plan);
			logger.info("[p92] BASE prompt: " + base);
			assertFalse("compiled BASE does not ask for the band: " + base, lower(base).contains(ANY_CLAUSE));
			assertTrue("the BASE prompt carries the subject: " + base, lower(base).contains("basalt headland"));
			assertTrue("the BASE prompt carries the actor: " + base, lower(base).contains("hood up"));
			String band = bandPrompt(plan);
			logger.info("[p92] TITLE_BAND prompt: " + band);
			assertTrue("compiled TITLE_BAND carries the clause: " + band, lower(band).contains(TOP_CLAUSE));
			assertTrue("the TITLE_BAND prompt carries the setting: " + band, lower(band).contains("basalt headland"));
			assertFalse("no actor text in TITLE_BAND: " + band, lower(band).contains("hood up"));
			assertFalse("no element text in TITLE_BAND: " + band, lower(band).contains("brass lantern"));
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
			assertEquals("stages", List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_TITLE_BAND), plan.getStrategy().getStages());
			String base = basePrompt(plan);
			assertFalse("compiled BASE does not ask for the band: " + base, lower(base).contains(ANY_CLAUSE));
			String bandText = bandPrompt(plan);
			assertTrue("compiled TITLE_BAND says bottom: " + bandText, lower(bandText).contains(BOTTOM_CLAUSE));
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
			assertEquals("BASE only: no TITLE_BAND stage without a band", List.of(RenderPlan.STAGE_BASE), plan.getStrategy().getStages());
			assertNull("no TITLE_BAND compile without a band", plan.getCompiled().get(RenderPlan.STAGE_TITLE_BAND));
			assertNull("the executor sees no band", PbNodeExecutor.titleBand(plan));
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

			/// DRAFT: one variant = two renderer requests (the BASE figure pass on the figure canvas, then the
			/// TITLE_BAND masked pass on the padded full canvas), QA off, auto-approved.
			byte[] figurePng = solidFigure(90, 120, 60);
			byte[] bandPng = solid(90, 120, 60);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(ok(figurePng, 9201, 800, W, FIGURE_H), ok(bandPng, 9202, 600));
			ScriptedQa qa = new ScriptedQa();
			QaBackend backend = backend(adapter, qa);
			Map<String, Object> out = run(f, backend, PbQualityPresetEnumType.DRAFT);
			String runOid = str(out.get("runObjectId"));

			assertEquals("two renderer requests per variant: BASE then TITLE_BAND", 2, adapter.requests.size());
			StageRequest req = adapter.requests.get(0);
			assertFigurePass(req, DRAFT_EDGE, DRAFT_BAND_PX);
			assertBox("the plan's band", req.getPlan().getReserved(), 0.0, 0.0, 1.0, PbLayoutUtil.TITLE_SAFE_BAND_HEIGHT);
			logger.info("[p92] BASE prompt: " + req.getPrompt().getPrompt());
			StageRequest band = adapter.requests.get(1);
			logger.info("[p92] TITLE_BAND prompt: " + band.getPrompt().getPrompt());
			assertBandPass(band, generatorParams(backend, 0), TOP_CLAUSE, DRAFT_EDGE, DRAFT_BAND_PX);
			assertEquals("the selector is never asked under DRAFT (QA off)", 0, backend.selections);

			/// The DTO: the composition-plan shape plus the plan description and the two stages.
			assertEquals("compositionObjectId", f.coverOid, out.get("compositionObjectId"));
			assertEquals("purpose", PbCompositionPurposeEnumType.COVER.name(), status(out.get("purpose")));
			assertEquals("TEXT_ONLY (a described actor, no references)", PbStrategyEnumType.TEXT_ONLY.name(), out.get("strategy"));
			assertNotNull("planContentHash", out.get("planContentHash"));
			assertNotNull("planArtifactObjectId", out.get("planArtifactObjectId"));
			assertEquals("stagesPlanned", List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_TITLE_BAND), stages(out, "stagesPlanned"));
			assertEquals("stagesRendered", List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_TITLE_BAND), stages(out, "stagesRendered"));
			assertTrue("no stage errors: " + stageErrors(out), stageErrors(out).isEmpty());
			assertNull("no TITLE_BAND_FAILED degradation", degradation(out, RenderPlan.Degradation.TITLE_BAND_FAILED));
			assertEquals("one final rendered", 1, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertNull("no sceneObjectId on a cover DTO", out.get("sceneObjectId"));
			assertNull("no qaMode under DRAFT", out.get(PbNodeExecutor.KEY_QA_MODE));
			List<Map<String, Object>> vs = variants(out, 1);
			assertEquals("auto-approved: " + out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED), Boolean.TRUE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertEquals("the one variant is the approved artifact", out.get("artifactObjectId"), out.get(PbNodeExecutor.KEY_AUTO_APPROVED_ARTIFACT));
			assertEquals(PbReviewStatusEnumType.APPROVED.name(), vs.get(0).get("reviewStatus"));
			assertEquals("headline reviewStatus", PbReviewStatusEnumType.APPROVED.name(), out.get("reviewStatus"));

			/// On the database: one BASE artifact of the run on the composition-plan node, approved and selected,
			/// holding the band pass's full-canvas output; one `stage` artifact holding the figure image it was
			/// painted over; the node DONE_UNVERIFIED before the approval; nothing composed a scene.
			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals("one base artifact", 1, bases.size());
			BaseRecord a0 = bases.get(0);
			assertEquals(oidOf(a0), out.get("artifactObjectId"));
			assertEquals("stamped with the run", runOid, PbArtifactUtil.attemptOf(a0));
			assertEquals(PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(a0));
			assertEquals(PbArtifactUtil.APPROVED_BY_AUTO, a0.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			assertTrue("selected", selected(a0));
			byte[] finalBytes = PbArtifactUtil.readArtifactBytes(f.owner, a0);
			assertSize("the final is the band pass's full canvas", finalBytes, W, H);
			assertTrue("the final is the band answer's bytes", Arrays.equals(bandPng, finalBytes));
			List<BaseRecord> stagesOnDb = stageArtifacts(f);
			assertEquals("one stage artifact (the figure image)", 1, stagesOnDb.size());
			assertEquals("the stage artifact is stamped with the run", runOid, PbArtifactUtil.attemptOf(stagesOnDb.get(0)));
			assertTrue("the stage artifact is the figure image the BASE pass answered",
				Arrays.equals(figurePng, PbArtifactUtil.readArtifactBytes(f.owner, stagesOnDb.get(0))));
			BaseRecord node = PbGraphUtil.readNode(f.owner, oidOf(f.node), orgId);
			String nodeStatus = status(node.get(OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertTrue("node status after the run: " + nodeStatus,
				PbNodeStatusEnumType.DONE_UNVERIFIED.name().equals(nodeStatus) || PbNodeStatusEnumType.DONE.name().equals(nodeStatus));
			assertTrue("still no SCENE composition", PbComposeUtil.listSceneCompositions(f.owner, f.book).isEmpty());
			BaseRecord coverAfter = PbComposeUtil.readComposition(f.owner, f.coverOid, orgId);
			assertEquals("the cover's revision is untouched", revisionBefore, intOf(coverAfter.get(OlioFieldNames.FIELD_PB_REVISION)));
			assertEquals("the cover is still COVER", PbCompositionPurposeEnumType.COVER.name(), status(coverAfter.get(OlioFieldNames.FIELD_PB_PURPOSE)));

			/// STANDARD on the same node: the preset's two variants, each a BASE figure pass then a TITLE_BAND pass;
			/// QA CANONICAL judges variant 0's full-canvas band output under the cover plan.
			byte[] band0 = solid(60, 90, 120);
			ScriptedAdapter adapter2 = new ScriptedAdapter().answer(
				figureOk(60, 90, 120, 9301, 700), ok(band0, 9302, 500),
				figureOk(120, 60, 90, 9303, 700), ok(solid(120, 60, 90), 9304, 500));
			ScriptedQa qa2 = new ScriptedQa().verdicts(passReport());
			QaBackend backend2 = backend(adapter2, qa2);
			Map<String, Object> out2 = run(f, backend2, PbQualityPresetEnumType.STANDARD);
			String runOid2 = str(out2.get("runObjectId"));
			assertEquals("four requests: two variants of BASE + TITLE_BAND", 4, adapter2.requests.size());
			List<String> stagesAsked = new ArrayList<>();
			for(StageRequest r : adapter2.requests) {
				stagesAsked.add(r.getStage());
			}
			assertEquals("stage order across the variants",
				List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_TITLE_BAND, RenderPlan.STAGE_BASE, RenderPlan.STAGE_TITLE_BAND), stagesAsked);
			assertFigurePass(adapter2.requests.get(2), STANDARD_EDGE, STANDARD_BAND_PX);
			assertBandPass(adapter2.requests.get(3), generatorParams(backend2, 1), TOP_CLAUSE, STANDARD_EDGE, STANDARD_BAND_PX);
			assertEquals("two finals rendered", 2, intOf(out2.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertEquals("stagesRendered", List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_TITLE_BAND), stages(out2, "stagesRendered"));
			assertTrue("no stage errors: " + stageErrors(out2), stageErrors(out2).isEmpty());
			assertEquals("selector asked once", 1, backend2.selections);
			assertEquals("handed the BASE checkpoint", MODEL, backend2.checkpoint);
			assertEquals("variant 0 judged", 1, qa2.judged.size());
			assertSize("QA judges the full canvas (the band pass's output)", qa2.judged.get(0), W, H);
			assertTrue("QA judges variant 0's band output", Arrays.equals(band0, qa2.judged.get(0)));
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
			List<BaseRecord> stagesAll = stageArtifacts(f);
			assertEquals("three stage artifacts across the two attempts (one figure image per variant)", 3, stagesAll.size());
			int stagesOfRun2 = 0;
			for(BaseRecord a : stagesAll) {
				if(runOid2.equals(PbArtifactUtil.attemptOf(a))) {
					stagesOfRun2++;
					assertSize("second-run figure image (the answered figure image)", PbArtifactUtil.readArtifactBytes(f.owner, a), W, FIGURE_H);
				}
			}
			assertEquals("two figure images of the second run", 2, stagesOfRun2);
		}
		finally {
			cleanup(f);
		}
	}

	/**
	 * The band pass fails (the renderer refuses it): the run still completes, the figure image is padded to the full
	 * canvas with the band flat-filled and delivered as the final, the stage error and a TITLE_BAND_FAILED degradation
	 * say so on the DTO, and the degradation vetoes auto-approve. The figure image is still persisted as the
	 * {@code stage} artifact the final chains to.
	 */
	@Test
	public void bandPassFailureDegradesAndDeliversThePaddedCanvas() throws Exception {
		logger.info("bandPassFailureDegradesAndDeliversThePaddedCanvas");
		Fixture f = null;
		try {
			f = bookWithCover("p92bf");
			dressCover(f, "top", new double[] { 0.3, 0.05, 0.4, 0.4 }, new double[] { 0.1, 0.1, 0.2, 0.2 });
			Color figureColor = new Color(40, 80, 160);
			byte[] figurePng = solidFigure(40, 80, 160);
			ScriptedAdapter adapter = new ScriptedAdapter().answer(
				ok(figurePng, 9401, 500, W, FIGURE_H),
				StageResult.failed(RendererAdapter.ERROR_PREFIX_BACKEND + "band refused", 50L, "{\"offline\":true}"));
			QaBackend backend = backend(adapter, new ScriptedQa());
			Map<String, Object> out = run(f, backend, PbQualityPresetEnumType.DRAFT);
			String runOid = str(out.get("runObjectId"));

			assertEquals("BASE then the refused TITLE_BAND", 2, adapter.requests.size());
			assertFigurePass(adapter.requests.get(0), DRAFT_EDGE, DRAFT_BAND_PX);
			assertBandPass(adapter.requests.get(1), generatorParams(backend, 0), TOP_CLAUSE, DRAFT_EDGE, DRAFT_BAND_PX);

			String degradation = degradation(out, RenderPlan.Degradation.TITLE_BAND_FAILED);
			assertNotNull("TITLE_BAND_FAILED degradation: " + PbNodeExecutor.degradationsOf(out), degradation);
			assertTrue("the degradation says the band is unpainted: " + degradation, degradation.contains("the band is delivered unpainted"));
			List<Map<String, Object>> errors = stageErrors(out);
			assertEquals("one stage error: " + errors, 1, errors.size());
			assertEquals("the stage error is the band pass", RenderPlan.STAGE_TITLE_BAND, errors.get(0).get("stage"));
			assertEquals("stagesRendered still lists both (the padded canvas stands in)",
				List.of(RenderPlan.STAGE_BASE, RenderPlan.STAGE_TITLE_BAND), stages(out, "stagesRendered"));
			assertEquals("one final rendered", 1, intOf(out.get(PbNodeExecutor.KEY_VARIANTS_RENDERED)));
			assertEquals("not auto-approved", Boolean.FALSE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			String skipped = str(out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			assertNotNull("skip reason", skipped);
			assertTrue("vetoed by the degradation: " + skipped, skipped.startsWith("degraded:") && skipped.contains(RenderPlan.Degradation.TITLE_BAND_FAILED));

			List<BaseRecord> bases = baseArtifacts(f);
			assertEquals("one base artifact", 1, bases.size());
			BaseRecord a0 = bases.get(0);
			assertEquals("stamped with the run", runOid, PbArtifactUtil.attemptOf(a0));
			assertEquals("left for review", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(a0));
			byte[] finalBytes = PbArtifactUtil.readArtifactBytes(f.owner, a0);
			assertSize("the final is the figure padded to the plan's full canvas", finalBytes, DRAFT_EDGE, DRAFT_EDGE);
			BufferedImage padded = MaskImage.decode(finalBytes);
			assertEquals("the band rows are the figure's edge mean (a solid figure: its own colour)", figureColor, new Color(padded.getRGB(0, 0)));
			assertEquals("the band's last row too", figureColor, new Color(padded.getRGB(DRAFT_EDGE - 1, DRAFT_BAND_PX - 1)));
			assertEquals("the figure rows carry the figure", figureColor, new Color(padded.getRGB(0, DRAFT_EDGE - 1)));
			assertEquals("the first figure row", figureColor, new Color(padded.getRGB(DRAFT_EDGE / 2, DRAFT_BAND_PX)));
			List<BaseRecord> stagesOnDb = stageArtifacts(f);
			assertEquals("the figure image is persisted as the stage artifact", 1, stagesOnDb.size());
			assertTrue("the stage artifact is the figure image the BASE pass answered",
				Arrays.equals(figurePng, PbArtifactUtil.readArtifactBytes(f.owner, stagesOnDb.get(0))));
		}
		finally {
			cleanup(f);
		}
	}

	/// The figure-canvas arithmetic the executor and the compiler share: box remapping, band pixel rounding, the
	/// band mask box and the padding - no fixture, no database.
	@Test
	public void figureCanvasArithmetic() {
		logger.info("figureCanvasArithmetic");
		RenderPlan.Box top = new RenderPlan.Box(0.0, 0.0, 1.0, 0.25);
		RenderPlan.Box bottom = new RenderPlan.Box(0.0, 0.75, 1.0, 0.25);

		/// Box.inFigureCanvas: clip to the band first, then stretch the remaining rows to the whole figure canvas.
		assertBox("clipped actor under a top band", new RenderPlan.Box(0.3, 0.25, 0.4, 0.4).inFigureCanvas(top), 0.3, 0.0, 0.4, 0.4 / 0.75);
		assertBox("an actor into the top band is clipped first", new RenderPlan.Box(0.3, 0.05, 0.4, 0.4).inFigureCanvas(top), 0.3, 0.0, 0.4, 0.4 / 0.75);
		assertBox("an actor under a bottom band", new RenderPlan.Box(0.3, 0.35, 0.4, 0.4).inFigureCanvas(bottom), 0.3, 0.35 / 0.75, 0.4, 0.4 / 0.75);
		RenderPlan.Box same = new RenderPlan.Box(0.3, 0.35, 0.4, 0.4);
		assertSame("no band: the box itself", same, same.inFigureCanvas(null));

		/// BandCanvas: the band's pixel height is a multiple of 16 of the canvas height.
		assertEquals("768 x 0.25", 192, BandCanvas.bandPixels(768, top));
		assertEquals("1024 x 0.25", 256, BandCanvas.bandPixels(1024, top));
		assertEquals("no band", 0, BandCanvas.bandPixels(768, null));
		assertEquals("figure height", 576, BandCanvas.figureHeight(768, top));
		assertEquals("H is what the test uses", BAND_PX, BandCanvas.bandPixels(H, top));
		assertArrayEquals("top band pixels", new int[] { 0, 0, 1024, 192 }, BandCanvas.bandPixelsBox(1024, 768, top));
		assertArrayEquals("bottom band pixels", new int[] { 0, 576, 1024, 192 }, BandCanvas.bandPixelsBox(1024, 768, bottom));
		assertArrayEquals("no band pixels", new int[] { 0, 0, 0, 0 }, BandCanvas.bandPixelsBox(1024, 768, null));
		assertBox("top band box", BandCanvas.bandBox(1024, 768, top), 0.0, 0.0, 1.0, 0.25);
		assertBox("bottom band box", BandCanvas.bandBox(1024, 768, bottom), 0.0, 0.75, 1.0, 0.25);
		assertNull("no band box", BandCanvas.bandBox(1024, 768, null));
		byte[] mask = MaskImage.rect(List.of(BandCanvas.bandBox(W, H, top)), W, H);
		assertEquals("the band mask covers exactly the band rows", BAND_PX / (double) H, MaskImage.coverage(mask), 1e-6);

		/// Padding: the figure image lands in the figure rows and the band is the edge mean of a solid figure.
		Color c = new Color(200, 30, 90);
		byte[] figure = solidFigure(200, 30, 90);
		byte[] paddedTop = BandCanvas.pad(figure, W, H, top);
		assertSize("padded to the full canvas", paddedTop, W, H);
		BufferedImage pt = MaskImage.decode(paddedTop);
		assertEquals("top band filled", c, new Color(pt.getRGB(0, 0)));
		assertEquals("figure rows", c, new Color(pt.getRGB(0, H - 1)));
		byte[] paddedBottom = BandCanvas.pad(figure, W, H, bottom);
		assertSize("padded to the full canvas (bottom)", paddedBottom, W, H);
		BufferedImage pb = MaskImage.decode(paddedBottom);
		assertEquals("bottom band filled", c, new Color(pb.getRGB(W - 1, H - 1)));
		assertEquals("figure rows (bottom band)", c, new Color(pb.getRGB(0, 0)));
		assertNull("undecodable figure", BandCanvas.pad(null, W, H, top));
		assertNull("no band", BandCanvas.pad(figure, W, H, null));

		/// The band is each column's own edge extended, not one mean colour: a figure whose left half is one colour
		/// and right half another pads to a band split the same way, continuous with the figure at the seam, and the
		/// edge rows used are the GRAIN rows next to the band (a different colour deeper in the figure does not leak).
		Color left = new Color(220, 40, 40);
		Color right = new Color(40, 40, 220);
		Color deep = new Color(10, 200, 10);
		BufferedImage split = new BufferedImage(W, FIGURE_H, BufferedImage.TYPE_INT_RGB);
		for(int y = 0; y < FIGURE_H; y++) {
			for(int x = 0; x < W; x++) {
				boolean edgeRows = (y < BandCanvas.GRAIN) || (y >= FIGURE_H - BandCanvas.GRAIN);
				split.setRGB(x, y, (edgeRows ? (x < W / 2 ? left : right) : deep).getRGB());
			}
		}
		byte[] splitPng = png(split);
		BufferedImage ps = MaskImage.decode(BandCanvas.pad(splitPng, W, H, top));
		assertEquals("top band, left column: the left edge colour", left, new Color(ps.getRGB(0, 0)));
		assertEquals("top band, right column: the right edge colour", right, new Color(ps.getRGB(W - 1, 0)));
		assertEquals("top band's last row, left", left, new Color(ps.getRGB(W / 4, BAND_PX - 1)));
		assertEquals("top band's last row, right", right, new Color(ps.getRGB(3 * W / 4, BAND_PX - 1)));
		assertEquals("the first figure row below the band is the figure's own edge", left, new Color(ps.getRGB(W / 4, BAND_PX)));
		assertEquals("deeper figure rows untouched", deep, new Color(ps.getRGB(W / 4, BAND_PX + BandCanvas.GRAIN + 8)));
		BufferedImage psb = MaskImage.decode(BandCanvas.pad(splitPng, W, H, bottom));
		assertEquals("bottom band, left column", left, new Color(psb.getRGB(0, H - 1)));
		assertEquals("bottom band, right column", right, new Color(psb.getRGB(W - 1, H - BAND_PX)));
		assertEquals("bottom band's figure edge", right, new Color(psb.getRGB(3 * W / 4, H - BAND_PX - 1)));

		/// Seam match: a painted band uniformly (12, 10, 8) darker than the figure, blended across the feather, is
		/// lifted to the figure's level - every row within a level of it, no step between adjacent rows over a level,
		/// the deep figure rows untouched - and the offset reported is the band's shortfall.
		Color a = new Color(180, 190, 210);
		int[] off = { 12, 10, 8 };
		byte[] seamed = seamedCover(top, a, off);
		BandCanvas.SeamMatch sm = BandCanvas.matchSeam(seamed, W, H, top);
		assertTrue("applied: " + sm, sm.isApplied());
		assertArrayEquals("the offset is the band's shortfall", new double[] { 12.0, 10.0, 8.0 }, sm.getOffset(), 0.01);
		assertTrue("reported as applied with the offset: " + sm, sm.toString().startsWith("SeamMatch{applied r+12.0 g+10.0 b+8.0}"));
		BufferedImage matched = MaskImage.decode(sm.getBytes());
		assertSize("still the full canvas", sm.getBytes(), W, H);
		double[] profile = rowProfile(matched, a);
		assertTrue("every row within a level of the figure's colour (max " + profile[0] + ")", profile[0] <= 1.0);
		assertTrue("no step between adjacent rows over a level (max " + profile[1] + ")", profile[1] <= 1.0);
		assertEquals("the band's deep rows are the figure's colour exactly", a, new Color(matched.getRGB(W / 2, 0)));
		assertEquals("the figure's deep rows are untouched", a, new Color(matched.getRGB(W / 2, H - 1)));
		assertEquals("the unmatched cover shows the shortfall", new Color(168, 180, 202), new Color(MaskImage.decode(seamed).getRGB(W / 2, 0)));
		byte[] seamedBottom = seamedCover(bottom, a, off);
		BandCanvas.SeamMatch smb = BandCanvas.matchSeam(seamedBottom, W, H, bottom);
		assertTrue("applied (bottom): " + smb, smb.isApplied());
		BufferedImage matchedBottom = MaskImage.decode(smb.getBytes());
		double[] profileBottom = rowProfile(matchedBottom, a);
		assertTrue("every row within a level (bottom, max " + profileBottom[0] + ")", profileBottom[0] <= 1.0);
		assertTrue("no step over a level (bottom, max " + profileBottom[1] + ")", profileBottom[1] <= 1.0);
		assertEquals("the bottom band's deep rows are the figure's colour", a, new Color(matchedBottom.getRGB(W / 2, H - 1)));
		assertEquals("the figure's top rows are untouched (bottom band)", a, new Color(matchedBottom.getRGB(W / 2, 0)));
		/// Nothing to do: no band, not the canvas size, or a shortfall under half a level - the same bytes come back.
		assertSame("no band: the bytes untouched", seamed, BandCanvas.matchSeam(seamed, W, H, null).getBytes());
		assertSame("not the canvas size: the bytes untouched", figure, BandCanvas.matchSeam(figure, W, H, top).getBytes());
		byte[] flat = seamedCover(top, a, new int[] { 0, 0, 0 });
		BandCanvas.SeamMatch nil = BandCanvas.matchSeam(flat, W, H, top);
		assertSame("no shortfall: the bytes untouched", flat, nil.getBytes());
		assertFalse("nothing applied: " + nil, nil.isApplied());
		assertNotNull("but measured", nil.getOffset());
		assertEquals("SeamMatch{nil r+0.0 g+0.0 b+0.0}", nil.toString());
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
