package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWImageResponse;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbRunStatusEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * P3-6 (PictureBook V3 design §24, §30 row 3 "ChapBook optional locations"): a ChapBook page is an
 * {@code olio.pb.scene} and may name an {@code olio.pb.location} exactly as a PictureBook scene does (P3-5).
 * When it does, the page's TEXT_ONLY plan composes the location's subject text ahead of the page's own
 * {@code sdPrompt}, carries the location's identity / watched-field hash / approved render, binds the location
 * record onto the page node after the image is recorded (so a later edit to the location is a drift the scan
 * reports as {@code refDrift}), and degrades to {@code LOCATION_TEXT_ONLY} - which vetoes auto-approve (D5) -
 * when no render is approved. An unlocated page plans exactly as before P3-6; the v1 path is untouched.
 * Pages are pointed through a generic record patch (the pointing UI is P3-7, §26). Non-admin owner against
 * {@code am7db}; the live case renders on the Swarm node named by {@code test.swarm.server} and is gated by
 * {@link SdTestGate} (a gated skip is a skip, never a pass); the rest plan against a Swarm whose {@code txt2img}
 * is never reached, so the page node FAILS after the plan artifact is persisted.
 * <ol>
 * <li>{@link #case01LocatedPageRendersWithTheLocationAndDriftsWhenItChanges}: page 0 at an approved location -
 *     the plan carries identity, hash and render; the prompt sent starts with the location's description and
 *     carries the page prompt; the page node binds the location; auto-approve approves and pins. Page 1 at an
 *     unrendered location - {@code LOCATION_TEXT_ONLY}, auto-approve vetoed, artifact left GENERATED. Then a
 *     description edit on the first location is reported as {@code refDrift} on page 0's node only. SD-gated.</li>
 * <li>{@link #case02LocatedPageFailureKeepsThePlanAndBindsNothing}: the renderer cannot be reached - the
 *     composed prompt was still what was sent, the persisted plan carries the location, and no location binding
 *     is written (binding follows the image, never precedes it).</li>
 * <li>{@link #case03UnlocatedPagePlansAsBeforeP36}: no location named - no identity, no inputs, no binding; the
 *     plan's description is the page prompt verbatim (the P1-9 pins hold).</li>
 * <li>{@link #case04LocationOfAnotherBookIsRefused}: a location of the owner's OTHER ChapBook (its own world)
 *     is refused: pointer kept, no hash, no render, no subject text, one degradation naming it - first against the
 *     unreachable renderer, then live: the page renders from its own prompt alone, the refusal vetoes auto-approve,
 *     and the node binds NOTHING - the binder follows the plan's verdict, not the raw pointer. SD-gated.</li>
 * <li>{@link #case05VariantOnAPageBindsRepointsAndUnpoints}: one page rendered three times. At a rendered
 *     location's unrendered variant: both qualifiers in the prompt, both inputs on the plan, no render rides (D3),
 *     ordinals 0 and 1 bound, auto-approve vetoed. Re-pointed at another unrendered location: ordinal 0 re-bound,
 *     ordinal 1 dropped, the input hash moves, still vetoed. Un-pointed: both bindings dropped, the hash moves
 *     again, nothing degrades, auto-approve approves and pins. SD-gated.</li>
 * </ol>
 */
public class TestChapBookPageLocation extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	private static final long RENDER_TIMEOUT_MS = 20L * 60L * 1000L;
	private static final long JOB_WAIT_MS = 15L * 60L * 1000L;
	private static final String PLAN_MARKER = "(plan artifact ";
	private static final String POEM_TEXT = "Outside, all is pristine,\n"
		+ "From cobalt skies of charcoal unity\n"
		+ "Descending upon snow canvassed green\n"
		+ "To silver veins of icy sheens,\n"
		+ "Born of spells and sorcery.\n"
		+ "\n"
		+ "Inside hearts and hearths and homes,\n"
		+ "Ochre embers and ebon cinders,\n"
		+ "Faded life stirred by motherly crones,\n"
		+ "Dry damp clothes and warm cold bones\n"
		+ "And illuminate the age-old spellbound tomes.";
	private static final String GENUINE_PROMPT = "a serene alpine lake at dawn, wide natural vista, soft diffuse light, snow on distant peaks";
	private static final String SECOND_PROMPT = "a warm cottage hearth at dusk, ochre embers, wide natural vista through a frosted window";
	private static final String LOC_DESCRIPTION = "A low-beamed tavern by the quay, lanterns in the windows, barrels stacked by the door";

	private BaseRecord testUser;
	private String server;

	@Before
	public void setUpChapBookPageLocation() {
		assertTrue("resource.properties must put ChapBooks on v3 for this class (BaseTest re-pins it)",
			PbRenderPath.V3.equals(PbRenderPath.valueFor(chapBookStub())));
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookTestUser", ctx.getOrganizationId());
		assertNotNull("chapbookTestUser must be created", testUser);
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		assertNotNull(SdTestGate.PROP_SWARM_SERVER + " must be set", server);
		SwarmModelInventory.invalidateAll();
		PbStaleUtil.clear();
	}

	// ───────────────────────────── helpers (TestChapBookV3Render + TestPbScenePlanLocation idioms) ─────────────────────────────

	private static final class CapturingSdu extends SDUtil {
		final List<SWTxt2Img> captured = new ArrayList<>();
		private final boolean delegate;
		CapturingSdu(String server, boolean delegate) {
			super(SDAPIEnumType.SWARM, server);
			this.delegate = delegate;
		}
		@Override
		public SWImageResponse txt2img(SWTxt2Img req) {
			captured.add(req);
			return (delegate ? super.txt2img(req) : null);
		}
	}

	private static final class Fixture {
		String bookOid;
		String slug;
		long orgId;
		List<BaseRecord> scenes;
		List<String> sceneOids = new ArrayList<>();
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private static String oidOf(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static long fkId(BaseRecord rec, String field) {
		Object o = rec.get(field);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}

	private static String status(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return (v != null ? String.valueOf(v).toUpperCase() : null);
	}

	private static BaseRecord chapBookStub() {
		try {
			BaseRecord stub = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] { OlioFieldNames.FIELD_PB_BOOK_TYPE });
			stub.setValue(OlioFieldNames.FIELD_PB_BOOK_TYPE, "CHAPBOOK");
			return stub;
		}
		catch (Exception e) {
			throw new AssertionError("Cannot build a chapbook stub: " + e.getMessage(), e);
		}
	}

	private static String planOidOf(String message) {
		if (message == null) {
			return null;
		}
		int at = message.indexOf(PLAN_MARKER);
		if (at < 0) {
			return null;
		}
		int start = at + PLAN_MARKER.length();
		int end = message.indexOf(')', start);
		return (end > start ? message.substring(start, end) : null);
	}

	private BaseRecord createPoem(BaseRecord user, String groupPath, String name, String text) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord poem = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			poem.set("text", text);
			poem.set("title", name);
			return IOSystem.getActiveContext().getAccessPoint().create(user, poem);
		}
		catch (Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}

	/// Two pages from the two-stanza poem at 5 lines a page, in a ChapBook with its own world.
	private Fixture createFixture(String tag) {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		Fixture fx = new Fixture();
		fx.orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();
		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookP36-" + tag + "-" + ts, "Poem P3-6 " + tag + " " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));
		fx.slug = "cb-p36-" + tag + "-" + ts;
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, fx.slug, "ChapBook P3-6 " + tag + " " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		fx.bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord bookCheck = readBook(fx);
		fx.scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertEquals("two stanzas at 5 lines a page give two pages", 2, fx.scenes.size());
		for (BaseRecord s : fx.scenes) {
			fx.sceneOids.add(s.get(FieldNames.FIELD_OBJECT_ID));
		}
		return fx;
	}

	private BaseRecord readBook(Fixture fx) {
		BaseRecord b = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		assertNotNull("readBook " + fx.slug, b);
		return b;
	}

	private BaseRecord readScene(Fixture fx, String sceneOid) {
		BaseRecord s = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertNotNull("page " + sceneOid, s);
		return s;
	}

	private void deleteBook(Fixture fx) {
		if (fx == null || fx.bookOid == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(testUser, fx.bookOid);
			logger.info("cleanup " + fx.slug + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch (Exception e) {
			logger.error("cleanup " + fx.slug + ": " + e.getMessage());
		}
	}

	private void resetAutoApprove(Fixture fx) {
		try {
			PbServiceFacade.setAutoApprove(testUser, fx.bookOid, false);
		}
		catch (Exception e) {
			logger.warn("autoApprove reset " + fx.slug + ": " + e.getMessage());
		}
	}

	private String storeFastBookConfig(Fixture fx, BaseRecord renderConfig) throws Exception {
		String model = renderConfig.get(OlioFieldNames.FIELD_SD_MODEL);
		assertNotNull("The render gate must name an installed checkpoint", model);
		BaseRecord sparse = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG,
			new String[] { OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_STEPS, "hires" });
		sparse.setValue(OlioFieldNames.FIELD_SD_MODEL, model);
		sparse.setValue(OlioFieldNames.FIELD_SD_STEPS, Integer.valueOf(12));
		sparse.setValue("hires", Boolean.FALSE);
		BaseRecord stored = ChapBookUtil.setBookSdConfig(testUser, fx.bookOid, sparse);
		assertNotNull("The book sdConfig must be stored", stored);
		assertEquals("the stored book config names the installed checkpoint", model, stored.get(OlioFieldNames.FIELD_SD_MODEL));
		return model;
	}

	/// The generic record patch is the only way to point a page until P3-7's UI (§26); it is what the UI's
	/// facade route will do underneath, minus the staleness mark (§6.C 179).
	private void pointPageAt(Fixture fx, String sceneOid, BaseRecord loc) throws Exception {
		pointPageAt(fx, sceneOid, loc, null);
	}

	/// Point (or, with both null, un-point) a page: both FKs are written on every call, as a UI unlink would
	/// (a present-but-null FK on a field-name patch writes NULL, §6.C 170 f).
	private void pointPageAt(Fixture fx, String sceneOid, BaseRecord loc, BaseRecord variant) throws Exception {
		BaseRecord scene = readScene(fx, sceneOid);
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
		patch.set(FieldNames.FIELD_LOCATION, loc);
		patch.set(OlioFieldNames.FIELD_PB_LOCATION_VARIANT, variant);
		assertNotNull("page.location/locationVariant patch", IOSystem.getActiveContext().getAccessPoint().update(testUser, patch));
		BaseRecord back = readScene(fx, sceneOid);
		BaseRecord backLoc = back.get(FieldNames.FIELD_LOCATION);
		BaseRecord backVar = back.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
		assertEquals("the page persists the location pointer", (loc != null ? oidOf(loc) : null), (backLoc != null ? oidOf(backLoc) : null));
		assertEquals("the page persists the variant pointer", (variant != null ? oidOf(variant) : null), (backVar != null ? oidOf(backVar) : null));
	}

	private BaseRecord newVariant(Fixture fx, String locOid, String name, String timeOfDay, String weather) {
		BaseRecord v = PbLocationUtil.createVariant(testUser, readBook(fx), locOid, name, timeOfDay, weather, null, null, null);
		assertNotNull("createVariant " + name, v);
		assertEquals("the variant hangs off its location", locOid, oidOf(v.get(FieldNames.FIELD_LOCATION)));
		assertNull("a fresh variant has no approvedRender", v.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		return v;
	}

	private BaseRecord mustReadVariant(Fixture fx, String varOid) {
		BaseRecord v = PbLocationUtil.readVariant(testUser, varOid, fx.orgId);
		assertNotNull("variant " + varOid, v);
		return v;
	}

	private BaseRecord newLocation(Fixture fx, String name, String description) {
		BaseRecord loc = PbLocationUtil.getCreateLocation(testUser, readBook(fx), name, description, PbLocationKindEnumType.INTERIOR, List.of("the taproom"));
		assertNotNull("getCreateLocation " + name, loc);
		assertNull("a fresh location has no approvedRender", loc.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		return loc;
	}

	private BaseRecord mustReadLocation(Fixture fx, String locOid) {
		BaseRecord loc = PbLocationUtil.readLocation(testUser, locOid, fx.orgId);
		assertNotNull("location " + locOid, loc);
		return loc;
	}

	private BaseRecord workflow(Fixture fx) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(testUser, readBook(fx), PbBookUtil.workflowGroupPath(fx.slug));
		assertNotNull("workflow", wf);
		return wf;
	}

	private BaseRecord locationNode(Fixture fx, BaseRecord wf, String locOid) {
		BaseRecord n = PbGraphUtil.addNode(testUser, wf, PbPipelineUtil.locationHandle(locOid), PbNodeTypeEnumType.LOCATION, PbBookUtil.workflowGroupPath(fx.slug), 0);
		assertNotNull("location node " + locOid, n);
		assertTrue("scope patch", PbPipelineUtil.patchNodeScope(testUser, wf, n, PbPipelineUtil.SCOPE_LOCATION, locOid, 0));
		BaseRecord fresh = PbGraphUtil.readNode(testUser, oidOf(n), fx.orgId);
		assertNotNull("the location node reads back", fresh);
		assertEquals("the location node is scoped to its location", locOid, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		return fresh;
	}

	private static byte[] png(int w, int h, Color c) throws Exception {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(c);
		g.fillRect(0, 0, w, h);
		g.setColor(Color.WHITE);
		g.drawLine(0, 0, w - 1, h - 1);
		g.dispose();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		assertTrue("ImageIO must have a PNG writer", ImageIO.write(img, "png", baos));
		return baos.toByteArray();
	}

	private BaseRecord persistPng(Fixture fx, String name, byte[] bytes) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, PbBookUtil.artifactGroupPath(fx.slug));
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, plist);
		ByteModelUtil.setValue(data, bytes);
		data.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(testUser, data);
		assertNotNull("data.data " + name, created);
		return created;
	}

	/// Approve a synthetic landscape of the location's LOCATION node through the facade so {@code approvedRender}
	/// is pinned the way the product pins it. Once per location: a second LOCATION node with the same handle
	/// is a duplicate-handle 500.
	private BaseRecord approveLandscape(Fixture fx, String locOid, Color c) throws Exception {
		BaseRecord wf = workflow(fx);
		BaseRecord n = locationNode(fx, wf, locOid);
		byte[] bytes = png(64, 64, c);
		BaseRecord data = persistPng(fx, "loc-" + shortId() + ".png", bytes);
		BaseRecord artifact = PbArtifactUtil.persistArtifact(testUser, n, PbPipelineUtil.ROLE_LANDSCAPE, PbArtifactTypeEnumType.IMAGE,
			PbBookUtil.artifactGroupPath(fx.slug), data, null, null, null);
		assertNotNull("landscape artifact", artifact);
		assertTrue("metrics", PbArtifactUtil.recordImageMetrics(testUser, artifact, bytes, "image/png", 64, 64, null));
		Map<String, Object> approved = PbServiceFacade.approveArtifact(testUser, fx.bookOid, oidOf(artifact));
		assertEquals("approve names the location node", oidOf(n), approved.get("nodeObjectId"));
		assertEquals("approve promotes the landscape to the location's approvedRender", idOf(artifact),
			fkId(mustReadLocation(fx, locOid), OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		return PbArtifactUtil.readArtifact(testUser, oidOf(artifact), fx.orgId);
	}

	private AsyncJob awaitJob(String jobId) {
		AsyncJob job = PbRenderJobUtil.awaitTerminal(testUser, jobId, JOB_WAIT_MS);
		assertNotNull("Job " + jobId + " must reach a terminal state within the wait budget", job);
		return job;
	}

	private Map<?, ?> soleOutcome(AsyncJob job, String nodeOid) {
		assertEquals("Job error: " + job.getError(), AsyncJob.Status.COMPLETED, job.getStatus());
		Map<?, ?> result = JSONUtil.importObject(job.getResult(), Map.class);
		assertNotNull("The job result parses: " + job.getResult(), result);
		assertEquals("the run completed: " + job.getResult(), PbRunStatusEnumType.COMPLETED.name(), result.get("runStatus"));
		assertEquals("one node executed: " + job.getResult(), Integer.valueOf(1), result.get("executedNodeCount"));
		List<?> outcomes = (List<?>) result.get("nodes");
		assertNotNull("The job result lists its node outcomes", outcomes);
		assertEquals("one outcome: " + outcomes, 1, outcomes.size());
		Map<?, ?> outcome = (Map<?, ?>) outcomes.get(0);
		assertEquals("the outcome is the page node's", nodeOid, outcome.get("nodeObjectId"));
		assertEquals("a rendered page is DONE_UNVERIFIED: " + outcome, PbNodeStatusEnumType.DONE_UNVERIFIED.name(), outcome.get("status"));
		return outcome;
	}

	/// Submit one page through the facade's package seam, wait for the job, return the outcome and the node.
	private Map<?, ?> renderPage(Fixture fx, String sceneOid, CapturingSdu sdu, String label, BaseRecord[] nodeOut) {
		long t0 = System.currentTimeMillis();
		Map<String, Object> submit = PbServiceFacade.requestChapBookPageRender(testUser, fx.bookOid, sceneOid, sdu, server);
		String nodeOid = (String) submit.get("nodeObjectId");
		assertNotNull("submit returns the page node: " + submit, nodeOid);
		AsyncJob job = awaitJob((String) submit.get("jobId"));
		long ms = System.currentTimeMillis() - t0;
		logger.info("MEASURED: P3-6 " + label + " on " + server + " wallMs=" + ms + " job=" + job.getStatus() + " result=" + job.getResult());
		Map<?, ?> outcome = soleOutcome(job, nodeOid);
		nodeOut[0] = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertNotNull("page node " + nodeOid, nodeOut[0]);
		return outcome;
	}

	/// A page whose renderer is never reached: the node FAILS after the plan artifact is persisted.
	private RenderPlan failedPagePlan(Fixture fx, String sceneOid, CapturingSdu sdu, BaseRecord[] nodeOut) {
		Map<String, Object> submit = PbServiceFacade.requestChapBookPageRender(testUser, fx.bookOid, sceneOid, sdu, server);
		String nodeOid = (String) submit.get("nodeObjectId");
		assertNotNull("submit returns the page node: " + submit, nodeOid);
		AsyncJob job = awaitJob((String) submit.get("jobId"));
		logger.info("P3-6 no-SD page job: " + job.getStatus() + " " + job.getResult());
		BaseRecord node = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertNotNull("page node " + nodeOid, node);
		assertEquals("an unreachable renderer FAILS the page node", PbNodeStatusEnumType.FAILED.name(), status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));
		String lastError = node.get(OlioFieldNames.FIELD_PB_LAST_ERROR);
		assertNotNull("the failure is recorded on the node", lastError);
		assertTrue("the failure is the unreachable renderer: " + lastError, lastError.contains(RendererAdapter.ERROR_UNREACHABLE));
		assertFalse("the server URL never leaks: " + lastError, lastError.contains(server));
		String planOid = planOidOf(lastError);
		assertNotNull("the failure names the persisted plan artifact: " + lastError, planOid);
		assertEquals("txt2img reached once", 1, sdu.captured.size());
		BaseRecord planArtifact = PbArtifactUtil.readArtifact(testUser, planOid, fx.orgId);
		assertNotNull("plan artifact " + planOid, planArtifact);
		assertEquals("the named artifact is the renderPlan", PbPipelineUtil.ROLE_RENDER_PLAN, planArtifact.get(OlioFieldNames.FIELD_PB_ROLE));
		assertNull("no landscape on a failed page", PbArtifactUtil.findSelected(testUser, node, PbPipelineUtil.ROLE_LANDSCAPE));
		RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(planArtifact);
		assertNotNull("the plan artifact decodes", plan);
		assertEquals("a page plans as a scene", RenderPlan.PURPOSE_SCENE, plan.getPurpose());
		assertEquals("a ChapBook page is TEXT_ONLY", PbStrategyEnumType.TEXT_ONLY, plan.getStrategy().getName());
		assertEquals("the request is the compiled BASE prompt", plan.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt(), sdu.captured.get(0).getPrompt());
		nodeOut[0] = node;
		return plan;
	}

	private RenderPlan selectedPlanOf(BaseRecord node) {
		BaseRecord planArtifact = PbArtifactUtil.findSelected(testUser, node, PbPipelineUtil.ROLE_RENDER_PLAN);
		assertNotNull("The page node must carry a selected renderPlan artifact", planArtifact);
		RenderPlan plan = PbRenderPlanUtil.readPlanArtifact(planArtifact);
		assertNotNull("The renderPlan artifact must decode", plan);
		return plan;
	}

	private static List<RenderPlan.Degradation> locationDegradations(RenderPlan p) {
		List<RenderPlan.Degradation> out = new ArrayList<>();
		for (RenderPlan.Degradation d : p.getDegradations()) {
			if (RenderPlan.Degradation.LOCATION_TEXT_ONLY.equals(d.getCode())) {
				out.add(d);
			}
		}
		return out;
	}

	private static RenderPlan.Degradation theOneLocationDegradation(RenderPlan p, String expectedMessage) {
		List<RenderPlan.Degradation> ds = locationDegradations(p);
		assertEquals("exactly one LOCATION_TEXT_ONLY: " + p.getDegradations(), 1, ds.size());
		RenderPlan.Degradation d = ds.get(0);
		assertEquals("the degradation is at the BASE stage", RenderPlan.STAGE_BASE, d.getStage());
		assertNull("not an actor degradation", d.getActorId());
		assertEquals("the degradation says why", expectedMessage, d.getMessage());
		return d;
	}

	private static String noRenderForLocation(String name) {
		return "No approved render for location " + name + "; setting described in text";
	}

	@SuppressWarnings("unchecked")
	private static List<String> strings(Map<?, ?> out, String key) {
		Object v = out.get(key);
		return (v instanceof List ? (List<String>) v : List.of());
	}

	private BaseRecord freshReadData(String objectId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
	}

	private byte[] artifactBytes(Fixture fx, BaseRecord artifact) throws Exception {
		BaseRecord dataRef = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		assertNotNull("artifact points at a data.data", dataRef);
		String dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		if (dataOid == null) {
			IOSystem.getActiveContext().getReader().populate(dataRef, new String[] { FieldNames.FIELD_OBJECT_ID });
			dataOid = dataRef.get(FieldNames.FIELD_OBJECT_ID);
		}
		BaseRecord data = freshReadData(dataOid, fx.orgId);
		assertNotNull("data.data " + dataOid, data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertTrue("real image bytes (was " + (bytes != null ? bytes.length : -1) + ")", bytes != null && bytes.length > 1024);
		for (int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		return bytes;
	}

	private void assertLocationBinding(BaseRecord node, String locOid, String expectedHash) {
		assertRefBinding(node, 0, OlioModelNames.MODEL_PB_LOCATION, locOid, expectedHash, "the page node binds its location after the render");
		assertNull("no variant binding when none is named", PbPipelineUtil.findBinding(testUser, node, PbPipelineUtil.ROLE_LOCATION, 1));
	}

	private void assertVariantBinding(BaseRecord node, String varOid, String expectedHash) {
		assertRefBinding(node, 1, OlioModelNames.MODEL_PB_LOCATION_VARIANT, varOid, expectedHash, "the page node binds its variant (ordinal 1) after the render");
	}

	private void assertRefBinding(BaseRecord node, int ordinal, String refModel, String refOid, String expectedHash, String why) {
		BaseRecord binding = PbPipelineUtil.findBinding(testUser, node, PbPipelineUtil.ROLE_LOCATION, ordinal);
		assertNotNull(why, binding);
		assertEquals("ordinal " + ordinal + " refModel", refModel, binding.get(OlioFieldNames.FIELD_PB_REF_MODEL));
		assertEquals("ordinal " + ordinal + " refObjectId", refOid, binding.get(OlioFieldNames.FIELD_PB_REF_OBJECT_ID));
		assertEquals("ordinal " + ordinal + "'s refHash is the plan's input for it", expectedHash, binding.get(OlioFieldNames.FIELD_PB_REF_HASH));
	}

	private void assertNoLocationBindings(BaseRecord node, String why) {
		assertNull(why + " (ordinal 0)", PbPipelineUtil.findBinding(testUser, node, PbPipelineUtil.ROLE_LOCATION, 0));
		assertNull(why + " (ordinal 1)", PbPipelineUtil.findBinding(testUser, node, PbPipelineUtil.ROLE_LOCATION, 1));
	}

	/// The stored inputHash is exactly the recompute over the node's bindings (what the drift scan compares
	/// against), and the canonical form names - or does not name - the given objectIds.
	/// Only meaningful on a book whose autoApprove is ON: with it off, applyAutoApprove writes FALSE + "off" for every
	/// page, degraded or not (PbNodeExecutor.java:384-388), and the FALSE proves nothing about D5.
	private static void assertAutoApproveVetoed(Map<?, ?> out, BaseRecord node, String why) {
		assertEquals("D5 vetoes auto-approve " + why, Boolean.FALSE, out.get(PbNodeExecutor.KEY_AUTO_APPROVED));
		String skipped = (String) out.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED);
		assertNotNull("the veto is reported on the outcome: " + out, skipped);
		assertTrue("the veto names the degradation, not the switch: " + skipped,
			skipped.startsWith("degraded: ") && skipped.contains(RenderPlan.Degradation.LOCATION_TEXT_ONLY));
		assertNull("no review status on a vetoed page", out.get("reviewStatus"));
		assertFalse("a vetoed page is not pinned", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));
	}

	private String assertInputHashOverBindings(Fixture fx, BaseRecord node, List<String> named, List<String> notNamed) {
		String canonical = PbGraphUtil.canonicalInput(testUser, node, readBook(fx));
		for (String oid : named) {
			assertTrue("the canonical input names " + oid + ": " + canonical, canonical.contains(oid));
		}
		for (String oid : notNamed) {
			assertFalse("the canonical input does not name " + oid + ": " + canonical, canonical.contains(oid));
		}
		String stored = node.get(OlioFieldNames.FIELD_PB_INPUT_HASH);
		assertNotNull("completeNode stamps the inputHash after the binding", stored);
		assertEquals("the stored inputHash is the recompute over the bindings", PbGraphUtil.computeInputHash(testUser, node, readBook(fx)), stored);
		return stored;
	}

	private void savePng(Fixture fx, BaseRecord artifact, String label) throws Exception {
		byte[] bytes = artifactBytes(fx, artifact);
		File png = new File("target", "p36-" + label + "-" + fx.slug + ".png");
		Files.write(png.toPath(), bytes);
		logger.info("MEASURED: P3-6 " + label + " bytes=" + bytes.length + " png=" + png.getAbsolutePath());
	}

	// ───────────────────────────── cases ─────────────────────────────

	/**
	 * Live. Page 0 names a location with an approved render: the persisted plan carries its identity, hash and
	 * render; the prompt sent is the location's description then the page prompt, and is the compiled BASE
	 * prompt and the artifact's config snapshot description ("prompt shown is the prompt sent" holds on the
	 * review screen); the page node binds the location with the plan's hash; auto-approve approves and pins.
	 * Page 1 names a location with no render: {@code LOCATION_TEXT_ONLY}, auto-approve vetoed with the
	 * degradation named, artifact GENERATED, the location still bound. Then the first location's description
	 * changes: a forced drift scan reports page 0's node as {@code refDrift} on its location binding (and marks
	 * it STALE), page 1's node is untouched. SD-gated; a gated skip is a skip.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case01LocatedPageRendersWithTheLocationAndDriftsWhenItChanges() throws Exception {
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "P3-6 located page render");
		Fixture fx = createFixture("live");
		try {
			String page0 = fx.sceneOids.get(0);
			String page1 = fx.sceneOids.get(1);
			assertTrue("page 0 prompt", ChapBookUtil.setSceneLandscapePrompt(testUser, page0, GENUINE_PROMPT));
			assertTrue("page 1 prompt", ChapBookUtil.setSceneLandscapePrompt(testUser, page1, SECOND_PROMPT));
			String expectedModel = storeFastBookConfig(fx, renderConfig);

			String locAName = "The Gilded Tankard " + shortId();
			BaseRecord locA = newLocation(fx, locAName, LOC_DESCRIPTION);
			String locAOid = oidOf(locA);
			BaseRecord approved = approveLandscape(fx, locAOid, Color.ORANGE);
			String locBName = "The Unpainted Chapel " + shortId();
			BaseRecord locB = newLocation(fx, locBName, "A whitewashed chapel on a headland, one bell, gulls on the roof");
			String locBOid = oidOf(locB);

			pointPageAt(fx, page0, locA);
			pointPageAt(fx, page1, locB);
			assertTrue("auto-approve is on for the book", Boolean.TRUE.equals(PbServiceFacade.setAutoApprove(testUser, fx.bookOid, true).get("autoApprove")));

			String expectedHashA = PbWatchedFields.refHashOf(mustReadLocation(fx, locAOid));
			String expectedText = PbRenderPlanUtil.joinSentences(List.of(LOC_DESCRIPTION, GENUINE_PROMPT));

			// ── page 0: located, approved render ──
			CapturingSdu sduA = new CapturingSdu(server, true);
			BaseRecord[] nodeOut = new BaseRecord[1];
			Map<?, ?> outA = renderPage(fx, page0, sduA, "located page render", nodeOut);
			BaseRecord nodeA = nodeOut[0];
			String nodeAOid = oidOf(nodeA);

			assertEquals("txt2img must be reached once", 1, sduA.captured.size());
			SWTxt2Img reqA = sduA.captured.get(0);
			assertEquals("the book-tier installed checkpoint is what was sent", expectedModel, reqA.getModel());
			String sentA = reqA.getPrompt();
			assertNotNull("a prompt was sent", sentA);
			assertTrue("the prompt sent starts with the composed text (location description, then the page prompt): " + sentA,
				sentA.startsWith(expectedText));

			RenderPlan planA = selectedPlanOf(nodeA);
			assertEquals("a page plans as a scene", RenderPlan.PURPOSE_SCENE, planA.getPurpose());
			assertEquals("a ChapBook page is TEXT_ONLY", PbStrategyEnumType.TEXT_ONLY, planA.getStrategy().getName());
			RenderPlan.Location plA = planA.getLocation();
			assertEquals("the persisted plan carries the location's identity", locAOid, plA.getLocationId());
			assertNull("no variant named", plA.getVariantId());
			assertEquals("the persisted plan carries the location's watched-field hash", expectedHashA, plA.getRevisionHash());
			assertEquals("the approved render rides on the persisted plan", oidOf(approved), plA.getRenderArtifactId());
			assertEquals("location text first, then the page prompt", expectedText, plA.getDescription());
			assertEquals("inputs.location is the location's hash", expectedHashA, planA.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertFalse("no variant input when none is named: " + planA.getInputs(), planA.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertTrue("no LOCATION_TEXT_ONLY for an approved location: " + planA.getDegradations(), locationDegradations(planA).isEmpty());
			String compiledA = planA.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt();
			assertTrue("the compiled BASE prompt leads with the location's description: " + compiledA, compiledA.startsWith(LOC_DESCRIPTION));
			assertEquals("prompt shown = prompt sent", compiledA, sentA);

			List<String> degA = strings(outA, "degradations");
			assertTrue("a fresh book with no style resolves with no substitution: " + degA, degA.isEmpty());
			assertEquals("a located page with an approved render auto-approves: " + outA.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED),
				Boolean.TRUE, outA.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertNull("nothing vetoed the approval", outA.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			assertEquals("the outcome reports APPROVED", PbReviewStatusEnumType.APPROVED.name(), outA.get("reviewStatus"));

			assertLocationBinding(nodeA, locAOid, expectedHashA);
			assertTrue("auto-approve pins the page node", Boolean.TRUE.equals(nodeA.get(OlioFieldNames.FIELD_PB_PINNED)));
			assertInputHashOverBindings(fx, nodeA, List.of(locAOid, expectedHashA), List.of());

			String artifactAOid = (String) outA.get("artifactObjectId");
			BaseRecord artifactA = PbArtifactUtil.readArtifact(testUser, artifactAOid, fx.orgId);
			assertNotNull("landscape artifact " + artifactAOid, artifactA);
			assertEquals("the page's artifact is its landscape", PbPipelineUtil.ROLE_LANDSCAPE, artifactA.get(OlioFieldNames.FIELD_PB_ROLE));
			assertEquals("the artifact is APPROVED", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(artifactA));
			assertEquals("...by auto-approve", PbArtifactUtil.APPROVED_BY_AUTO, artifactA.get(OlioFieldNames.FIELD_PB_APPROVED_BY));
			BaseRecord snapA = artifactA.get(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT);
			assertNotNull("frozen config on the artifact", snapA);
			assertEquals("the snapshot's description is the prompt sent", compiledA, snapA.get(FieldNames.FIELD_DESCRIPTION));
			byte[] bytesA = artifactBytes(fx, artifactA);
			File pngA = new File("target", "p36-located-" + fx.slug + ".png");
			Files.write(pngA.toPath(), bytesA);
			logger.info("MEASURED: P3-6 page 0 model=" + snapA.get(OlioFieldNames.FIELD_SD_MODEL) + " bytes=" + bytesA.length + " png=" + pngA.getAbsolutePath());

			BaseRecord scene0 = readScene(fx, page0);
			assertNotNull("the page points at its image", scene0.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
			assertFalse("the page is current", Boolean.TRUE.equals(scene0.get(OlioFieldNames.FIELD_PB_IMAGE_STALE)));
			assertEquals("the page still points at its location", locAOid, oidOf(scene0.get(FieldNames.FIELD_LOCATION)));

			// ── page 1: located, no render ──
			CapturingSdu sduB = new CapturingSdu(server, true);
			Map<?, ?> outB = renderPage(fx, page1, sduB, "unrendered-location page render", nodeOut);
			BaseRecord nodeB = nodeOut[0];
			assertNotEquals("each page has its own node", nodeAOid, oidOf(nodeB));
			assertEquals("txt2img must be reached once for page 1", 1, sduB.captured.size());
			String sentB = sduB.captured.get(0).getPrompt();
			assertTrue("the unrendered location's description still leads the prompt: " + sentB, sentB.startsWith("A whitewashed chapel"));
			assertTrue("...and the page prompt follows: " + sentB, sentB.contains(SECOND_PROMPT));

			RenderPlan planB = selectedPlanOf(nodeB);
			assertEquals("page 1's plan carries its location's identity", locBOid, planB.getLocation().getLocationId());
			assertNull("an unrendered location carries no render", planB.getLocation().getRenderArtifactId());
			assertEquals("...but still its hash", PbWatchedFields.refHashOf(mustReadLocation(fx, locBOid)), planB.getLocation().getRevisionHash());
			theOneLocationDegradation(planB, noRenderForLocation(locBName));
			assertEquals("prompt shown = prompt sent (page 1)", planB.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt(), sentB);

			List<String> degB = strings(outB, "degradations");
			boolean textOnly = false;
			for (String d : degB) {
				if (d.startsWith(RenderPlan.Degradation.LOCATION_TEXT_ONLY + ": ")) {
					textOnly = true;
				}
			}
			assertTrue("the outcome lists LOCATION_TEXT_ONLY: " + degB, textOnly);
			assertEquals("D5 vetoes auto-approve for a degraded page", Boolean.FALSE, outB.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			String skipped = (String) outB.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED);
			assertNotNull("the veto is reported on the outcome: " + outB, skipped);
			assertTrue("the veto names the degradation: " + skipped, skipped.startsWith("degraded: ") && skipped.contains(RenderPlan.Degradation.LOCATION_TEXT_ONLY));
			assertNull("no review status on a vetoed page", outB.get("reviewStatus"));
			BaseRecord artifactB = PbArtifactUtil.readArtifact(testUser, (String) outB.get("artifactObjectId"), fx.orgId);
			assertEquals("the vetoed artifact stays GENERATED for human review", PbReviewStatusEnumType.GENERATED, PbArtifactUtil.reviewStatusOf(artifactB));
			assertFalse("a vetoed page is not pinned", Boolean.TRUE.equals(nodeB.get(OlioFieldNames.FIELD_PB_PINNED)));
			assertLocationBinding(nodeB, locBOid, (String) planB.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			byte[] bytesB = artifactBytes(fx, artifactB);
			File pngB = new File("target", "p36-unrendered-" + fx.slug + ".png");
			Files.write(pngB.toPath(), bytesB);
			logger.info("MEASURED: P3-6 page 1 bytes=" + bytesB.length + " png=" + pngB.getAbsolutePath());

			// ── drift: the first location's description changes; page 0's node is refDrift, page 1's is not ──
			BaseRecord wf = workflow(fx);
			long nodeAId = idOf(nodeA);
			long nodeBId = idOf(nodeB);
			PbStaleUtil.clear();
			PbStaleUtil.Scan pre = PbStaleUtil.scan(testUser, readBook(fx), wf, true);
			assertTrue("the scan is complete for the owner: " + pre.undeterminedRefs, pre.complete);
			assertFalse("nothing drifted before the edit: " + pre.entries.keySet(), pre.entries.containsKey(nodeAId));
			assertFalse("page 1's node did not drift before the edit either: " + pre.entries.keySet(), pre.entries.containsKey(nodeBId));

			Map<String, Object> edited = PbServiceFacade.updateLocation(testUser, fx.bookOid, locAOid,
				Map.of(FieldNames.FIELD_DESCRIPTION, LOC_DESCRIPTION + ", now with a new sign over the door"));
			assertNotNull("updateLocation returns the location", edited);
			String hashAfter = PbWatchedFields.refHashOf(mustReadLocation(fx, locAOid));
			assertNotEquals("the description is in the watched set", expectedHashA, hashAfter);

			PbStaleUtil.clear();
			PbStaleUtil.Scan post = PbStaleUtil.scan(testUser, readBook(fx), wf, true);
			assertTrue("the post-edit scan is complete for the owner: " + post.undeterminedRefs, post.complete);
			PbStaleUtil.Entry entryA = post.entries.get(nodeAId);
			assertNotNull("page 0's node drifted: " + post.entries.keySet(), entryA);
			assertEquals("a location edit is a reference drift, not a hash change", PbStaleUtil.REASON_REF_DRIFT, entryA.reason);
			assertEquals("exactly one binding drifted: " + entryA.driftedBindings, 1, entryA.driftedBindings.size());
			PbStaleUtil.DriftedRef drifted = entryA.driftedBindings.get(0);
			assertEquals("the drifted binding is the location role", PbPipelineUtil.ROLE_LOCATION, drifted.role);
			assertEquals("...of a location", OlioModelNames.MODEL_PB_LOCATION, drifted.refModel);
			assertEquals("...the edited one", locAOid, drifted.refObjectId);
			assertTrue("the scan persisted the mark", entryA.persisted);
			assertFalse("the location's drifted entries name page 0's node", post.driftedEntriesFor(OlioModelNames.MODEL_PB_LOCATION, locAOid).isEmpty());
			assertFalse("page 1's location did not change", post.entries.containsKey(nodeBId));
			BaseRecord nodeAAfter = PbGraphUtil.readNode(testUser, nodeAOid, fx.orgId);
			assertEquals("the scan marked page 0's node STALE despite the pin", PbNodeStatusEnumType.STALE.name(), status(nodeAAfter, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertTrue("the pin is not a drift matter", Boolean.TRUE.equals(nodeAAfter.get(OlioFieldNames.FIELD_PB_PINNED)));
			assertEquals("the persisted plan, re-read after the edit and the scan, keeps the hash it rendered with", expectedHashA,
				selectedPlanOf(nodeAAfter).getInputs().get(PbPipelineUtil.ROLE_LOCATION));
		}
		finally {
			resetAutoApprove(fx);
			deleteBook(fx);
		}
	}

	/**
	 * The renderer cannot be reached: the page node FAILS, the failure names the persisted plan artifact, the
	 * plan carries the location (identity, hash, approved render, composed text), the prompt sent was the
	 * composed prompt, and no location binding is written - the binding follows the recorded image, never
	 * precedes it, so a failed page leaves nothing for the drift scan to watch.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case02LocatedPageFailureKeepsThePlanAndBindsNothing() throws Exception {
		SdTestGate.requireSwarmReachable(server);
		Fixture fx = createFixture("fail");
		try {
			String page0 = fx.sceneOids.get(0);
			assertTrue("page 0 prompt", ChapBookUtil.setSceneLandscapePrompt(testUser, page0, GENUINE_PROMPT));
			BaseRecord loc = newLocation(fx, "The Gilded Tankard " + shortId(), LOC_DESCRIPTION);
			String locOid = oidOf(loc);
			BaseRecord approved = approveLandscape(fx, locOid, Color.ORANGE);
			pointPageAt(fx, page0, loc);
			String expectedHash = PbWatchedFields.refHashOf(mustReadLocation(fx, locOid));

			CapturingSdu sdu = new CapturingSdu(server, false);
			BaseRecord[] nodeOut = new BaseRecord[1];
			RenderPlan plan = failedPagePlan(fx, page0, sdu, nodeOut);
			BaseRecord node = nodeOut[0];

			RenderPlan.Location pl = plan.getLocation();
			assertEquals("the persisted plan carries the location's identity", locOid, pl.getLocationId());
			assertEquals("...its hash", expectedHash, pl.getRevisionHash());
			assertEquals("...and its approved render", oidOf(approved), pl.getRenderArtifactId());
			assertEquals("location text first, then the page prompt", PbRenderPlanUtil.joinSentences(List.of(LOC_DESCRIPTION, GENUINE_PROMPT)), pl.getDescription());
			assertEquals("inputs.location is the location's hash", expectedHash, plan.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertTrue("no LOCATION_TEXT_ONLY for an approved location: " + plan.getDegradations(), locationDegradations(plan).isEmpty());
			String sent = sdu.captured.get(0).getPrompt();
			assertTrue("the composed prompt was sent: " + sent, sent.startsWith(LOC_DESCRIPTION) && sent.contains(GENUINE_PROMPT));
			assertFalse("the plan never carries the server", plan.toJson().contains(server));

			assertNull("no location binding on a failed page", PbPipelineUtil.findBinding(testUser, node, PbPipelineUtil.ROLE_LOCATION, 0));
			assertNull("a failed page never ran completeNode", node.get(OlioFieldNames.FIELD_PB_INPUT_HASH));
			BaseRecord scene = readScene(fx, page0);
			assertNull("no image on a failed page", scene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
			assertEquals("the pointer is untouched by the failure", locOid, oidOf(scene.get(FieldNames.FIELD_LOCATION)));
		}
		finally {
			deleteBook(fx);
		}
	}

	/**
	 * Regression for the P1-9 pins: a page that names no location plans with no identity, no inputs, no
	 * degradation and no binding, and its description is the page prompt verbatim.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case03UnlocatedPagePlansAsBeforeP36() throws Exception {
		SdTestGate.requireSwarmReachable(server);
		Fixture fx = createFixture("plain");
		try {
			String page0 = fx.sceneOids.get(0);
			assertTrue("page 0 prompt", ChapBookUtil.setSceneLandscapePrompt(testUser, page0, GENUINE_PROMPT));
			BaseRecord scene = readScene(fx, page0);
			assertNull("precondition: the page names no location", scene.get(FieldNames.FIELD_LOCATION));

			CapturingSdu sdu = new CapturingSdu(server, false);
			BaseRecord[] nodeOut = new BaseRecord[1];
			RenderPlan plan = failedPagePlan(fx, page0, sdu, nodeOut);
			RenderPlan.Location pl = plan.getLocation();
			assertNull("no location identity", pl.getLocationId());
			assertNull("no variant identity", pl.getVariantId());
			assertNull("no hash", pl.getRevisionHash());
			assertNull("no render", pl.getRenderArtifactId());
			assertEquals("the locked prompt is the plan's location text, verbatim", GENUINE_PROMPT, pl.getDescription());
			assertFalse("no location input: " + plan.getInputs(), plan.getInputs().containsKey(PbPipelineUtil.ROLE_LOCATION));
			assertFalse("no variant input: " + plan.getInputs(), plan.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertTrue("no LOCATION_TEXT_ONLY for an unlocated page: " + plan.getDegradations(), locationDegradations(plan).isEmpty());
			String sent = sdu.captured.get(0).getPrompt();
			assertTrue("the page prompt alone was sent: " + sent, sent.startsWith(GENUINE_PROMPT));
			assertNoLocationBindings(nodeOut[0], "an unlocated page binds nothing");
		}
		finally {
			deleteBook(fx);
		}
	}

	/**
	 * A location of the owner's OTHER ChapBook - readable, approved, but of another world (each ChapBook has
	 * its own) - is refused the way P3-5 refuses it for a PictureBook scene: the pointer is kept, no hash, no
	 * render, no subject text, {@code inputs.location} null (and so absent from the persisted plan), one
	 * degradation naming the location. First on a page whose renderer is never reached (the plan alone), then
	 * live: the page renders from its own prompt and binds NOTHING - the binder follows the plan's verdict, not
	 * the raw pointer, so the other world's edits and approvals can never stale this page. SD-gated.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case04LocationOfAnotherBookIsRefused() throws Exception {
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "P3-6 refused-location page render");
		Fixture fx1 = createFixture("xw1");
		Fixture fx2 = null;
		try {
			fx2 = createFixture("xw2");
			storeFastBookConfig(fx1, renderConfig);
			long world1 = fkId(readBook(fx1), OlioFieldNames.FIELD_PB_WORLD);
			long world2 = fkId(readBook(fx2), OlioFieldNames.FIELD_PB_WORLD);
			assertTrue("both ChapBooks project a world", world1 > 0 && world2 > 0);
			assertNotEquals("precondition: two ChapBooks of one owner live in different worlds", world1, world2);

			String loc2Name = "The Other Book's Tankard " + shortId();
			BaseRecord loc2 = newLocation(fx2, loc2Name, LOC_DESCRIPTION);
			String loc2Oid = oidOf(loc2);
			BaseRecord loc2Render = approveLandscape(fx2, loc2Oid, Color.ORANGE);
			BaseRecord loc2Read = PbLocationUtil.readLocation(testUser, loc2Oid, fx1.orgId);
			assertNotNull("precondition: the owner CAN read the other book's location", loc2Read);
			assertTrue("precondition: it is in its own book's world", PbLocationUtil.sameWorld(readBook(fx2), loc2Read));
			assertFalse("precondition: it is not in this book's world", PbLocationUtil.sameWorld(readBook(fx1), loc2Read));

			String page0 = fx1.sceneOids.get(0);
			assertTrue("page 0 prompt", ChapBookUtil.setSceneLandscapePrompt(testUser, page0, GENUINE_PROMPT));
			pointPageAt(fx1, page0, loc2);
			String refusal = "Location " + loc2Name + " is not in this book's world; setting described in text";

			// ── the plan alone: the renderer is never reached ──
			CapturingSdu sdu = new CapturingSdu(server, false);
			BaseRecord[] nodeOut = new BaseRecord[1];
			RenderPlan plan = failedPagePlan(fx1, page0, sdu, nodeOut);
			RenderPlan.Location pl = plan.getLocation();
			assertEquals("the identity is still the pointer the page carries", loc2Oid, pl.getLocationId());
			assertNull("no variant named", pl.getVariantId());
			assertNull("refused is treated as unreadable: no hash", pl.getRevisionHash());
			assertNull("the other world's render (" + oidOf(loc2Render) + ") never rides", pl.getRenderArtifactId());
			assertEquals("no subject text from a refused record", GENUINE_PROMPT, pl.getDescription());
			/// In memory the resolver puts inputs.location = null (TestPbScenePlanLocation case12); this plan was
			/// read back from the artifact, and toJson (NON_EMPTY) drops a null entry - absent on the wire.
			assertNull("a refused location has no revision hash to input", plan.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertFalse("null inputs are absent after the artifact round-trip: " + plan.getInputs(),
				plan.getInputs().containsKey(PbPipelineUtil.ROLE_LOCATION));
			assertFalse("no variant input: " + plan.getInputs(), plan.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			theOneLocationDegradation(plan, refusal);
			String sentFailed = sdu.captured.get(0).getPrompt();
			assertTrue("the page prompt alone was sent: " + sentFailed, sentFailed.startsWith(GENUINE_PROMPT));
			assertNoLocationBindings(nodeOut[0], "a failed page binds nothing");

			// ── live: the same page renders (the FAILED node is re-run) and the refused pointer is NOT bound ──
			assertTrue("auto-approve is on for the book", Boolean.TRUE.equals(PbServiceFacade.setAutoApprove(testUser, fx1.bookOid, true).get("autoApprove")));
			CapturingSdu sdu2 = new CapturingSdu(server, true);
			Map<?, ?> out = renderPage(fx1, page0, sdu2, "refused-location page render", nodeOut);
			BaseRecord node = nodeOut[0];
			assertEquals("txt2img must be reached once", 1, sdu2.captured.size());
			String sent = sdu2.captured.get(0).getPrompt();
			assertTrue("the page prompt alone was sent: " + sent, sent.startsWith(GENUINE_PROMPT));
			assertFalse("no text from the refused record: " + sent, sent.contains(LOC_DESCRIPTION));
			RenderPlan livePlan = selectedPlanOf(node);
			RenderPlan.Location lpl = livePlan.getLocation();
			assertEquals("the selected plan still carries the pointer", loc2Oid, lpl.getLocationId());
			assertNull("...still no hash", lpl.getRevisionHash());
			assertNull("...still no render", lpl.getRenderArtifactId());
			assertFalse("...still no location input: " + livePlan.getInputs(), livePlan.getInputs().containsKey(PbPipelineUtil.ROLE_LOCATION));
			theOneLocationDegradation(livePlan, refusal);
			assertAutoApproveVetoed(out, node, "for the refusal too");
			assertNoLocationBindings(node, "a refused pointer is not bound by a successful render");
			assertInputHashOverBindings(fx1, node, List.of(), List.of(loc2Oid));
			BaseRecord scene = readScene(fx1, page0);
			assertNotNull("the page points at its image", scene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
			assertEquals("the pointer is untouched by the render", loc2Oid, oidOf(scene.get(FieldNames.FIELD_LOCATION)));
			BaseRecord artifact = PbArtifactUtil.readArtifact(testUser, (String) out.get("artifactObjectId"), fx1.orgId);
			assertNotNull("landscape artifact", artifact);
			savePng(fx1, artifact, "refused");
		}
		finally {
			resetAutoApprove(fx1);
			deleteBook(fx1);
			deleteBook(fx2);
		}
	}

	/**
	 * Live. A page names a location AND one of its variants. Leg 1: the plan carries both identities and both
	 * hashes, no render (D3: the location's render never stands in for the variant's; the one
	 * {@code LOCATION_TEXT_ONLY} names the variant), the prompt sent starts with the location's description
	 * then the variant's qualifiers then the page prompt; the page node binds the location at ordinal 0 and the
	 * variant at ordinal 1 and the stored inputHash is the recompute over both; auto-approve (on) is vetoed by the
	 * degradation. Leg 2: the page is re-pointed at another, unrendered location with no variant and re-rendered on
	 * the same node - ordinal 0 is re-bound to the new location, ordinal 1 is dropped, the inputHash moves, still
	 * vetoed. Leg 3: the page is un-pointed and re-rendered - both bindings are dropped, the plan is unlocated, the
	 * inputHash names neither location, and with nothing degraded auto-approve approves and pins (last leg: a
	 * pinned page would 409 on the next submit). SD-gated.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void case05VariantOnAPageBindsRepointsAndUnpoints() throws Exception {
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, server, "P3-6 variant page render");
		Fixture fx = createFixture("var");
		try {
			String page0 = fx.sceneOids.get(0);
			assertTrue("page 0 prompt", ChapBookUtil.setSceneLandscapePrompt(testUser, page0, GENUINE_PROMPT));
			storeFastBookConfig(fx, renderConfig);

			BaseRecord locA = newLocation(fx, "The Gilded Tankard " + shortId(), LOC_DESCRIPTION);
			String locAOid = oidOf(locA);
			approveLandscape(fx, locAOid, Color.ORANGE);
			String varName = "Dusk " + shortId();
			BaseRecord varA = newVariant(fx, locAOid, varName, "dusk", "light rain");
			String varAOid = oidOf(varA);
			BaseRecord locB = newLocation(fx, "The Unpainted Chapel " + shortId(), "A whitewashed chapel on a headland, one bell, gulls on the roof");
			String locBOid = oidOf(locB);

			assertTrue("auto-approve is on for the book", Boolean.TRUE.equals(PbServiceFacade.setAutoApprove(testUser, fx.bookOid, true).get("autoApprove")));

			// ── leg 1: location + variant ──
			pointPageAt(fx, page0, locA, varA);
			String expectedHashA = PbWatchedFields.refHashOf(mustReadLocation(fx, locAOid));
			String expectedHashVar = PbWatchedFields.refHashOf(mustReadVariant(fx, varAOid));
			String expectedText = PbRenderPlanUtil.joinSentences(List.of(
				PbRenderPlanUtil.locationSubjectText(mustReadLocation(fx, locAOid), mustReadVariant(fx, varAOid)), GENUINE_PROMPT));
			assertTrue("the variant's qualifiers are in the subject text: " + expectedText, expectedText.contains("dusk") && expectedText.contains("light rain"));

			CapturingSdu sdu1 = new CapturingSdu(server, true);
			BaseRecord[] nodeOut = new BaseRecord[1];
			Map<?, ?> out1 = renderPage(fx, page0, sdu1, "variant page render", nodeOut);
			BaseRecord node = nodeOut[0];
			String nodeOid = oidOf(node);
			assertEquals("txt2img must be reached once", 1, sdu1.captured.size());
			String sent1 = sdu1.captured.get(0).getPrompt();
			assertTrue("the prompt sent starts with the composed text (location, variant qualifiers, page prompt): " + sent1, sent1.startsWith(expectedText));

			RenderPlan plan1 = selectedPlanOf(node);
			RenderPlan.Location pl1 = plan1.getLocation();
			assertEquals("the plan carries the location's identity", locAOid, pl1.getLocationId());
			assertEquals("...and the variant's", varAOid, pl1.getVariantId());
			assertEquals("...and the location's hash", expectedHashA, pl1.getRevisionHash());
			assertEquals("inputs.location is the location's hash", expectedHashA, plan1.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertEquals("inputs.locationVariant is the variant's hash", expectedHashVar, plan1.getInputs().get(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			assertNull("D3: the location's render never stands in for the variant's", pl1.getRenderArtifactId());
			assertEquals("the composed text is the plan's description", expectedText, pl1.getDescription());
			theOneLocationDegradation(plan1, "No approved render for variant " + varName + "; setting described in text");
			assertEquals("prompt shown = prompt sent", plan1.getCompiled().get(RenderPlan.STAGE_BASE).getPrompt(), sent1);
			assertAutoApproveVetoed(out1, node, "for the unrendered variant");

			assertRefBinding(node, 0, OlioModelNames.MODEL_PB_LOCATION, locAOid, expectedHashA, "the page node binds its location at ordinal 0");
			assertVariantBinding(node, varAOid, expectedHashVar);
			String hash1 = assertInputHashOverBindings(fx, node, List.of(locAOid, varAOid), List.of());
			BaseRecord artifact1 = PbArtifactUtil.readArtifact(testUser, (String) out1.get("artifactObjectId"), fx.orgId);
			assertNotNull("leg 1 landscape artifact", artifact1);
			savePng(fx, artifact1, "variant");

			// ── leg 2: re-point at another location, no variant; same node ──
			pointPageAt(fx, page0, locB, null);
			String expectedHashB = PbWatchedFields.refHashOf(mustReadLocation(fx, locBOid));
			CapturingSdu sdu2 = new CapturingSdu(server, true);
			Map<?, ?> out2 = renderPage(fx, page0, sdu2, "re-pointed page render", nodeOut);
			node = nodeOut[0];
			assertEquals("the page re-renders on its own node", nodeOid, oidOf(node));
			String sent2 = sdu2.captured.get(0).getPrompt();
			assertTrue("the new location leads the prompt: " + sent2, sent2.startsWith("A whitewashed chapel"));
			assertFalse("nothing of the old location or variant remains: " + sent2, sent2.contains(LOC_DESCRIPTION) || sent2.contains("light rain"));
			RenderPlan plan2 = selectedPlanOf(node);
			assertEquals("the selected plan is the new location's", locBOid, plan2.getLocation().getLocationId());
			assertNull("no variant named now", plan2.getLocation().getVariantId());
			assertEquals("inputs.location moved to the new location's hash", expectedHashB, plan2.getInputs().get(PbPipelineUtil.ROLE_LOCATION));
			assertFalse("no variant input now: " + plan2.getInputs(), plan2.getInputs().containsKey(PbRenderPlanUtil.INPUT_LOCATION_VARIANT));
			theOneLocationDegradation(plan2, noRenderForLocation(locB.get(FieldNames.FIELD_NAME)));
			assertAutoApproveVetoed(out2, node, "for the unrendered location");
			assertRefBinding(node, 0, OlioModelNames.MODEL_PB_LOCATION, locBOid, expectedHashB, "ordinal 0 was re-bound to the new location");
			assertNull("ordinal 1 was dropped with the variant", PbPipelineUtil.findBinding(testUser, node, PbPipelineUtil.ROLE_LOCATION, 1));
			String hash2 = assertInputHashOverBindings(fx, node, List.of(locBOid), List.of(locAOid, varAOid));
			assertNotEquals("the inputHash moved with the bindings", hash1, hash2);

			// ── leg 3: un-point; same node ──
			pointPageAt(fx, page0, null, null);
			CapturingSdu sdu3 = new CapturingSdu(server, true);
			Map<?, ?> out3 = renderPage(fx, page0, sdu3, "un-pointed page render", nodeOut);
			node = nodeOut[0];
			assertEquals("the page re-renders on its own node", nodeOid, oidOf(node));
			String sent3 = sdu3.captured.get(0).getPrompt();
			assertTrue("the page prompt alone was sent: " + sent3, sent3.startsWith(GENUINE_PROMPT));
			RenderPlan plan3 = selectedPlanOf(node);
			assertNull("the selected plan is unlocated", plan3.getLocation().getLocationId());
			assertNull("...no variant", plan3.getLocation().getVariantId());
			assertEquals("...its description is the page prompt verbatim", GENUINE_PROMPT, plan3.getLocation().getDescription());
			assertFalse("...no location input: " + plan3.getInputs(), plan3.getInputs().containsKey(PbPipelineUtil.ROLE_LOCATION));
			assertTrue("...no LOCATION_TEXT_ONLY: " + plan3.getDegradations(), locationDegradations(plan3).isEmpty());
			assertEquals("nothing degraded, so auto-approve approves: " + out3.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED),
				Boolean.TRUE, out3.get(PbNodeExecutor.KEY_AUTO_APPROVED));
			assertNull("nothing vetoed the approval", out3.get(PbNodeExecutor.KEY_AUTO_APPROVE_SKIPPED));
			assertEquals("the outcome reports APPROVED", PbReviewStatusEnumType.APPROVED.name(), out3.get("reviewStatus"));
			assertTrue("auto-approve pins the page node", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));
			assertNoLocationBindings(node, "un-pointing drops both bindings on the next render");
			String hash3 = assertInputHashOverBindings(fx, node, List.of(), List.of(locAOid, varAOid, locBOid));
			assertNotEquals("the inputHash moved again", hash2, hash3);
			BaseRecord scene = readScene(fx, page0);
			assertNull("the page names no location", scene.get(FieldNames.FIELD_LOCATION));
			assertNull("...and no variant", scene.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT));
			assertNotNull("the page points at its latest image", scene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID));
		}
		finally {
			resetAutoApprove(fx);
			deleteBook(fx);
		}
	}
}
