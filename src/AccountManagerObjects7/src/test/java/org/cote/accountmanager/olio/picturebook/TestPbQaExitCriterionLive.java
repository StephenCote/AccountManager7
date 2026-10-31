package org.cote.accountmanager.olio.picturebook;

import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.fkId;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.idOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.oidOf;
import static org.cote.accountmanager.olio.picturebook.TestPbComposeFallback.orgOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.CancelToken;
import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.Flux2Compiler;
import org.cote.accountmanager.olio.illustration.ImageDims;
import org.cote.accountmanager.olio.illustration.ModelTaskKeys;
import org.cote.accountmanager.olio.illustration.QaProvider;
import org.cote.accountmanager.olio.illustration.QaReport;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.RendererAdapter;
import org.cote.accountmanager.olio.illustration.RendererCapabilities;
import org.cote.accountmanager.olio.illustration.StageRequest;
import org.cote.accountmanager.olio.illustration.StageResult;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.illustration.SwarmQaProvider;
import org.cote.accountmanager.olio.illustration.SwarmRendererAdapter;
import org.cote.accountmanager.olio.illustration.WorkflowCompiler;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.schema.type.PbStyleScopeEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * P8-6 exit criterion (PictureBookV3Implementation.md :1061): missing-character detection on a deliberately
 * broken render through each visual-QA provider, live against SD {@code .39} and the {@code .42} VLM.
 *
 * A two-actor plan is rendered with the second actor's text removed from the prompt, so the page carries one
 * person where the plan expects two. Both providers must FAIL it, and the executor must flag the variant for
 * review and keep auto-approve off it.
 *
 * <ul>
 * <li>t01 — the broken page (plain T2I, FLUX.2 on .39) checked directly by {@link VlmQaProvider} (.42 route) and
 * {@link SwarmQaProvider} (.39 YOLO): FAIL from both.</li>
 * <li>t02 — the executor path with the production provider selection ({@code PbQaUtil.providerFor} ->
 * {@code new VlmQaProvider(user, chatConfig)}, the run's chatConfig = the .42 record): FINAL / qa ALL, variant 0
 * stripped -> FAIL -> IN_REVIEW, the ALL ordering skips it, the review surfaces show it.</li>
 * <li>t03 — the executor path with the Swarm YOLO provider forced: STANDARD / qa CANONICAL, variant 0 stripped
 * -> FAIL -> IN_REVIEW, auto-approve skipped with the CANONICAL reason.</li>
 * </ul>
 *
 * The strip is done by a test-only {@link RendererAdapter} wrapper on the first BASE execute of a run (graph path:
 * the compiled graph's {@code scene} text input; T2I path: the prompt), so the planner, compiler and QA hook run
 * their production code unchanged. Fixtures are fresh books under {@code pbCustomTestUser}, deleted in
 * {@code finally}; the second actor is narrative-only (no approved portrait) so the removed sentence is the only
 * thing placing them.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestPbQaExitCriterionLive extends BaseTest {

	private static final Logger logger = LogManager.getLogger(TestPbQaExitCriterionLive.class);

	private static final File OUT = new File("target/p86");
	private static final String STYLE_PATH = "~/p86styles";
	private static final String LANDSCAPE_FALLBACK = "./media/flux/landscape1.png";
	private static final byte[] PNG_MAGIC = new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47 };
	private static final String[] CAST_GENDER = new String[] { "female", "male" };
	private static final String SCENE_TITLE = "Wet alley";
	private static final String CAMERA = "medium wide shot at eye level, both figures standing and facing the viewer";
	private static final String PROP_VISION_MODEL = "test.llm.vision.model";
	private static final String PROP_SERVER = "test.llm.ollama.server";
	private static final String CHAT_CONFIG_NAME = "p86 vlm qa";
	private static final int CHAT_TIMEOUT_S = 300;
	/// Observation ceilings only (never pass/fail thresholds): FINAL on flux2 is 4 variants x 24 steps + 4 VLM calls.
	private static final long T02_TIMEOUT_MS = 120L * 60L * 1000L;
	private static final long T03_TIMEOUT_MS = 60L * 60L * 1000L;

	/// The foreground sentence the compiler emits for the second actor (Flux2Compiler :131, no reference ordinal
	/// because the fixture's second actor is text-only).
	static final String MARKER = "The second person is ";
	/// Where the second actor's sentence ends: the start of the next compiler sentence (Flux2Compiler assemble order).
	static final String[] STOPS = new String[] { "The third person", "With ", "In the background", "Several ",
		Flux2Compiler.RULE_IDENTITY, Flux2Compiler.RULE_COHERENCE };

	private String server;
	private String flux2Model;
	private String llmServer;
	private String visionModel;
	private SDUtil sdu;
	private SwarmGraphClient client;
	private OrganizationContext org;
	private BaseRecord owner;
	private BaseRecord olioUser;
	private long orgId;
	private BaseRecord cfg42;

	private final String[] castNames = new String[2];
	private final byte[][] castPng = new byte[2][];
	private final int[][] castDims = new int[2][];
	private final String[] castPhysical = new String[2];
	private final String[] castOutfit = new String[2];
	private final Map<String, byte[]> portraitByName = new LinkedHashMap<>();
	private byte[] landscapePng;
	private int[] landscapeDims;
	private String landscapeSource;
	private String setting;

	@Before
	public void liveSetup() throws Exception {
		Assume.assumeTrue(TestP51GraphLive.SKIP, TestP51GraphLive.gated());
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
		SdTestGate.resolveInstalledCheckpoints(testProperties);
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SdTestGate.requireSwarmReachable(server);
		SwarmModelInventory.invalidateAll();
		flux2Model = prop("test.swarm.flux2Model", "flux2Klein_9b");
		sdu = new SDUtil(SDAPIEnumType.SWARM, server);
		SdTestGate.requireModelInstalled(sdu, server, flux2Model, "the FLUX.2 plan-driven BASE render");
		client = new SwarmGraphClient(sdu);
		visionModel = prop(PROP_VISION_MODEL, "").trim();
		llmServer = prop(PROP_SERVER, "").trim();
		Assume.assumeTrue("SKIPPED (not a pass): " + PROP_VISION_MODEL + " is blank in resource.properties", !visionModel.isEmpty());
		Assume.assumeTrue("SKIPPED (not a pass): " + PROP_SERVER + " is blank in resource.properties", !llmServer.isEmpty());
		OUT.mkdirs();
		log("SD: Swarm " + server + " (" + client.swarmVersion() + ") model " + flux2Model
			+ "; LLM: direct Ollama " + llmServer + " (gate tier=" + prop("test.llm.resolvedTier", "?") + " route=" + prop("test.llm.route", "?")
			+ ") vision model " + visionModel + "; production qaModel=" + QualityPresets.qaModel());
		org = getTestOrganization(TestP51GraphLive.CUSTOM_ORG_PATH);
		assertNotNull("custom org", org);
		owner = getCreateUser(TestP51GraphLive.CUSTOM_OWNER, org);
		assertNotNull("custom owner", owner);
		orgId = orgOf(owner);
		olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		readCast();
		cfg42 = OlioTestUtil.getCreateChatConfig(owner, CHAT_CONFIG_NAME,
			new OlioTestUtil.ConnectionTarget(llmServer, null, ConnectionDialectEnumType.OLLAMA, ConnectionUpstreamEnumType.OLLAMA, CHAT_TIMEOUT_S),
			prop("test.llm.ollama.model", "qwen3:8b"), CHAT_TIMEOUT_S, false);
		assertNotNull("chatConfig " + CHAT_CONFIG_NAME, cfg42);
		log("chatConfig " + CHAT_CONFIG_NAME + " = " + oidOf(cfg42) + " (direct OLLAMA target " + llmServer + "; the VLM route takes its model from qualityPresets.json)");
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* t01 - the broken page through both providers directly                                            */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = T03_TIMEOUT_MS)
	public void t01_brokenPageFailsBothProviders() throws Exception {
		VlmQaProvider vlm = requireVlm();
		SwarmQaProvider yolo = requireYolo();
		RenderPlan plan = twoActorPlan();
		CompiledPrompt compiled = new Flux2Compiler().compile(plan, RenderPlan.STAGE_BASE, new ArrayList<>());
		assertNotNull("compiled", compiled);
		Strip s = strip(compiled.getPrompt());
		assertNull("strip: " + s.failure, s.failure);
		assertTrue("removed names " + castNames[1] + ": " + s.removed, s.removed.contains(castNames[1]));
		assertFalse("removed must not name " + castNames[0] + ": " + s.removed, s.removed.contains(castNames[0]));
		assertTrue("stripped keeps " + castNames[0], s.after.contains(castNames[0]));
		assertFalse("stripped drops " + castNames[1], s.after.contains(castNames[1]));
		assertTrue("stripped keeps the rules", s.after.contains(Flux2Compiler.RULE_COHERENCE));
		assertTrue("opening rewritten to one person: " + s.after, s.after.startsWith("Place the person described below in "));
		writeText("t01-prompt-before.txt", s.before);
		writeText("t01-prompt-removed.txt", s.removed);
		writeText("t01-prompt-after.txt", s.after);

		int seed = (int) (System.nanoTime() & 0x7fffffff);
		StageRequest req = StageRequest.builder().plan(plan).stage(RenderPlan.STAGE_BASE)
			.prompt(new CompiledPrompt(s.after, compiled.getNegative(), 0)).model(flux2Model).width(1024).height(1024)
			.steps(4).cfg(1.0).sampler("euler").scheduler("simple").seed(seed).images(1).hires(false).config(new LinkedHashMap<>()).build();
		long t0 = System.currentTimeMillis();
		StageResult res = new SwarmRendererAdapter(sdu).execute(req, CancelToken.NONE);
		assertNotNull("render result", res);
		assertTrue("render ok: " + res.getError() + " / " + res.getErrorDetail(), res.isOk());
		byte[] page = res.getImages().get(0).getBytes();
		assertPng("broken page", page);
		int[] dims = ImageDims.decode(page);
		log("t01 broken page " + dims[0] + "x" + dims[1] + " (" + page.length + " bytes, seed " + seed + ") rendered by " + server + " in " + (System.currentTimeMillis() - t0) + " ms");
		Files.write(new File(OUT, "t01-broken.png").toPath(), page);

		QaReport vr = runCheck("t01-vlm", vlm, plan, page);
		assertEquals("VLM verdict on a one-person page for a two-actor plan", QaReport.Result.FAIL, vr.getResult());
		assertTrue("VLM failed a count or presence check", failedKind(vr, QaReport.KIND_PERSON_COUNT) || failedKind(vr, QaReport.KIND_ACTOR_PRESENT));

		QaReport yr = runCheck("t01-yolo", yolo, plan, page);
		assertEquals("YOLO verdict on a one-person page for a two-actor plan", QaReport.Result.FAIL, yr.getResult());
		List<QaReport.Check> counts = yr.checksOf(QaReport.KIND_PERSON_COUNT);
		assertEquals("one PERSON_COUNT check", 1, counts.size());
		assertEquals("expected two foreground actors", "2", counts.get(0).getExpected());
		assertNotEquals("observed faces != 2", "2", counts.get(0).getObserved());
		assertEquals("PERSON_COUNT failed", QaReport.Result.FAIL, counts.get(0).getResult());
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* t02 - executor path, production provider selection (VLM over the run's .42 chatConfig)           */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = T02_TIMEOUT_MS)
	public void t02_executorVlmFlagsStrippedVariant() throws Exception {
		requireVlm();
		Fixture f = fixture("vlm");
		try {
			StrippingBackend backend = new StrippingBackend(false);
			Map<String, Object> out = runScene(f, PbQualityPresetEnumType.FINAL, cfg42, backend);
			assertNull("strip: " + backend.failure, backend.failure);
			assertEquals("stripped exactly the first BASE execute", 1, backend.strippedCount);
			assertTrue("removed names " + castNames[1], backend.removed.contains(castNames[1]));
			writeText("t02-prompt-before.txt", backend.before);
			writeText("t02-prompt-after.txt", backend.after);
			assertEquals("qa mode", QualityPresets.QA_ALL, out.get("qaMode"));
			List<Map<String, Object>> variants = variants(out);
			assertEquals("4 FINAL variants", 4, variants.size());
			savePages("t02", variants);
			Map<String, Object> v0 = variantAt(variants, 0);
			Map<String, Object> q0 = qaOf(v0);
			assertEquals("variant 0 provider", "vlm", q0.get("provider"));
			assertEquals("variant 0 QA", "FAIL", String.valueOf(q0.get("result")));
			assertEquals("variant 0 flagged", PbReviewStatusEnumType.IN_REVIEW.name(), String.valueOf(v0.get("reviewStatus")));
			List<String> degradations = strings(out, "degradations");
			assertTrue("QA_FAIL degradation for variant 0: " + degradations, degradations.stream().anyMatch(d -> d.startsWith(RenderPlan.Degradation.QA_FAIL + ": variant 0 (")));
			for(Map<String, Object> v : variants) {
				Map<String, Object> q = qaOf(v);
				log("t02 variant " + v.get("variantIndex") + " qa=" + (q != null ? q.get("result") : "none") + " review=" + v.get("reviewStatus") + " selected=" + v.get("selected"));
			}
			assertEquals("auto-approve did not approve", Boolean.FALSE, out.get("autoApproved"));
			String skipped = String.valueOf(out.get("autoApproveSkipped"));
			log("t02 autoApproveSkipped=" + skipped);
			assertTrue("skipped for a degradation: " + skipped, skipped.startsWith("degraded: "));
			assertFalse("the ALL ordering never names variant 0 as the reason: " + skipped, skipped.startsWith("degraded: " + RenderPlan.Degradation.QA_FAIL + ": variant 0"));

			BaseRecord wf = PbGraphUtil.findWorkflow(owner, PbBookUtil.readBook(owner, f.bookOid, orgId));
			BaseRecord run = latestRun(owner, wf);
			assertNotNull("run row", run);
			assertEquals("run chatConfig is the .42 record", idOf(cfg42), fkId(run, OlioFieldNames.FIELD_PB_CHAT_CONFIG));

			String nodeOid = String.valueOf(out.get("nodeObjectId"));
			BaseRecord node = PbGraphUtil.readNode(owner, nodeOid, orgId);
			Map<String, QaReport> latest = PbQaUtil.latestReportsOf(owner, node);
			QaReport persisted = latest.get(String.valueOf(v0.get("artifactObjectId")));
			assertNotNull("persisted QA report for variant 0: " + latest.keySet(), persisted);
			assertEquals("persisted verdict", QaReport.Result.FAIL, persisted.getResult());

			Map<String, Object> view = PbServiceFacade.nodeView(owner, f.bookOid, nodeOid);
			Map<String, Object> a0 = artifactIn(view, String.valueOf(v0.get("artifactObjectId")));
			assertNotNull("nodeView carries variant 0", a0);
			assertEquals("nodeView reviewStatus", PbReviewStatusEnumType.IN_REVIEW.name(), String.valueOf(a0.get("reviewStatus")));
			assertEquals("nodeView qa.result", "FAIL", String.valueOf(qaOf(a0).get("result")));
			Map<String, Object> queue = PbServiceFacade.listReviewQueue(owner, f.bookOid, PbServiceFacade.REVIEW_FILTER_ALL);
			Map<String, Object> qa0 = queued(queue, nodeOid, String.valueOf(v0.get("artifactObjectId")));
			assertNotNull("review queue carries variant 0", qa0);
			assertEquals("queue reviewStatus", PbReviewStatusEnumType.IN_REVIEW.name(), String.valueOf(qa0.get("reviewStatus")));
			assertEquals("queue qa.result", "FAIL", String.valueOf(qaOf(qa0).get("result")));

			// 220 c ordering, proven on the run's own verdicts: a real approve write in the fixture book.
			List<BaseRecord> arts = new ArrayList<>();
			Map<String, QaReport.Result> verdicts = new LinkedHashMap<>();
			boolean anyNonFail = false;
			for(Map<String, Object> v : variants) {
				String oid = String.valueOf(v.get("artifactObjectId"));
				arts.add(PbArtifactUtil.readArtifact(owner, oid, orgId));
				QaReport r = latest.get(oid);
				QaReport.Result res = (r != null ? r.getResult() : null);
				verdicts.put(oid, res);
				anyNonFail |= (res != QaReport.Result.FAIL);
			}
			PbArtifactUtil.AutoApproveOutcome outcome = PbArtifactUtil.autoApprove(owner, arts, verdicts, List.of(), QualityPresets.QA_ALL);
			log("t02 ordering: approved=" + (outcome.getApproved() != null ? oidOf(outcome.getApproved()) : null) + " skipped=" + outcome.getSkipped() + " (anyNonFail=" + anyNonFail + ")");
			assertEquals("ALL approves iff a non-FAIL variant exists", anyNonFail, outcome.isApproved());
			if(anyNonFail) {
				assertNotEquals("the approved variant is not the stripped one", String.valueOf(v0.get("artifactObjectId")), oidOf(outcome.getApproved()));
			}
			else {
				assertTrue("all-FAIL reason: " + outcome.getSkipped(), outcome.getSkipped().startsWith("degraded: " + RenderPlan.Degradation.QA_FAIL + ": all "));
			}
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* t03 - executor path, Swarm YOLO forced                                                           */
	/* ------------------------------------------------------------------------------------------------ */

	@Test(timeout = T03_TIMEOUT_MS)
	public void t03_executorSwarmYoloFlagsStrippedVariant() throws Exception {
		requireYolo();
		Fixture f = fixture("yolo");
		try {
			StrippingBackend backend = new StrippingBackend(true);
			Map<String, Object> out = runScene(f, PbQualityPresetEnumType.STANDARD, null, backend);
			assertNull("strip: " + backend.failure, backend.failure);
			assertEquals("stripped exactly the first BASE execute", 1, backend.strippedCount);
			writeText("t03-prompt-before.txt", backend.before);
			writeText("t03-prompt-after.txt", backend.after);
			assertEquals("qa mode", QualityPresets.QA_CANONICAL, out.get("qaMode"));
			List<Map<String, Object>> variants = variants(out);
			assertEquals("2 STANDARD variants", 2, variants.size());
			savePages("t03", variants);
			Map<String, Object> v0 = variantAt(variants, 0);
			Map<String, Object> q0 = qaOf(v0);
			assertEquals("variant 0 provider", "swarmYolo", q0.get("provider"));
			assertEquals("variant 0 QA", "FAIL", String.valueOf(q0.get("result")));
			assertEquals("variant 0 flagged", PbReviewStatusEnumType.IN_REVIEW.name(), String.valueOf(v0.get("reviewStatus")));
			assertNull("CANONICAL checks variant 0 only", qaOf(variantAt(variants, 1)));
			List<String> degradations = strings(out, "degradations");
			assertTrue("QA_FAIL degradation for variant 0: " + degradations, degradations.stream().anyMatch(d -> d.startsWith(RenderPlan.Degradation.QA_FAIL + ": variant 0 (")));
			assertEquals("auto-approve did not approve", Boolean.FALSE, out.get("autoApproved"));
			String skipped = String.valueOf(out.get("autoApproveSkipped"));
			log("t03 autoApproveSkipped=" + skipped);
			assertTrue("CANONICAL reason: " + skipped, skipped.startsWith("degraded: " + RenderPlan.Degradation.QA_FAIL + ": variant 0 (") && skipped.endsWith(") failed visual QA"));
			BaseRecord wf = PbGraphUtil.findWorkflow(owner, PbBookUtil.readBook(owner, f.bookOid, orgId));
			BaseRecord run = latestRun(owner, wf);
			assertNotNull("run row", run);
			assertNull("run names no chatConfig", run.get(OlioFieldNames.FIELD_PB_CHAT_CONFIG));
		}
		finally {
			cleanup(f);
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Providers                                                                                        */
	/* ------------------------------------------------------------------------------------------------ */

	/// The production route (Chat over the .42 record) with a name-keyed portrait resolver, because the book-58 plan
	/// of t01 carries cast names as portrait ids. t02 goes through the public ctor via the executor's default seam.
	private VlmQaProvider requireVlm() {
		VlmQaProvider.Route route = VlmQaProvider.routeFor(owner, cfg42);
		VlmQaProvider p = new VlmQaProvider(route, null, oid -> portraitByName.get(oid));
		boolean up = p.isAvailable();
		log("VLM route " + route + " available=" + up + (up ? "" : " (" + p.availabilityNote() + ")"));
		Assume.assumeTrue("SKIPPED (not a pass): " + p.availabilityNote(), up);
		return p;
	}

	private SwarmQaProvider requireYolo() {
		SwarmQaProvider p = new SwarmQaProvider(client, flux2Model);
		boolean up = p.isAvailable();
		log("YOLO on " + p.getServer() + " available=" + up);
		Assume.assumeTrue("SKIPPED (not a pass): " + SwarmQaProvider.CLASS_YOLO + "/" + SwarmQaProvider.DETECTOR_MODEL + " not available on " + server, up);
		return p;
	}

	private QaReport runCheck(String tag, QaProvider p, RenderPlan plan, byte[] page) throws Exception {
		long t0 = System.currentTimeMillis();
		QaReport rep = p.check(plan, page);
		long ms = System.currentTimeMillis() - t0;
		assertNotNull(tag + " report", rep);
		log(tag + " " + p.name() + " -> " + rep.getResult() + " in " + ms + " ms (model " + rep.getModel() + ")" + (rep.getNote() != null ? " note=" + rep.getNote() : ""));
		for(QaReport.Check c : rep.getChecks()) {
			log(tag + "   " + c.getKind() + " " + c.getResult() + " expected=" + c.getExpected() + " observed=" + c.getObserved() + (c.getDetail() != null ? " - " + c.getDetail() : ""));
		}
		writeText(tag + "-report.json", rep.toJson());
		return rep;
	}

	private static boolean failedKind(QaReport rep, String kind) {
		for(QaReport.Check c : rep.checksOf(kind)) {
			if(c.getResult() == QaReport.Result.FAIL) return true;
		}
		return false;
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* The strip                                                                                        */
	/* ------------------------------------------------------------------------------------------------ */

	static final class Strip {
		String before;
		String removed;
		String after;
		String failure;
	}

	/// Removes the second actor's foreground sentence and rewrites the opening to one person. Marker-bounded:
	/// the sentence ends at the first ". " followed by another compiler sentence (STOPS), so sentence breaks inside
	/// the actor's own description are kept with it.
	static Strip strip(String prompt) {
		Strip s = new Strip();
		s.before = prompt;
		s.after = prompt;
		if(prompt == null) {
			s.failure = "no prompt";
			return s;
		}
		int start = prompt.indexOf(MARKER);
		if(start < 0) {
			s.failure = "marker '" + MARKER + "' not in prompt: " + prompt;
			return s;
		}
		int from = start + MARKER.length();
		int end = -1;
		int dot = prompt.indexOf(". ", from);
		while(dot >= 0) {
			String rest = prompt.substring(dot + 2);
			for(String stop : STOPS) {
				if(rest.startsWith(stop)) {
					end = dot + 2;
					break;
				}
			}
			if(end >= 0) break;
			dot = prompt.indexOf(". ", dot + 2);
		}
		if(end < 0) {
			int i = prompt.indexOf(Flux2Compiler.RULE_IDENTITY, from);
			if(i < 0) i = prompt.indexOf(Flux2Compiler.RULE_COHERENCE, from);
			end = i;
		}
		if(end < 0) {
			s.failure = "no sentence end after the marker: " + prompt;
			return s;
		}
		s.removed = prompt.substring(start, end);
		s.after = (prompt.substring(0, start) + prompt.substring(end))
			.replace("them, with the other people described below, in ", "them in ")
			.replace("Place the people described below in ", "Place the person described below in ");
		return s;
	}

	/// Test-only backend: the production Swarm adapter and client, with the first BASE execute of a run stripped.
	class StrippingBackend implements PbNodeExecutor.RenderBackend {
		final boolean forceSwarm;
		int strippedCount = 0;
		String before;
		String removed;
		String after;
		String failure;

		StrippingBackend(boolean forceSwarm) {
			this.forceSwarm = forceSwarm;
		}

		@Override
		public RendererAdapter adapter(SDUtil s) {
			return new StrippingAdapter(new SwarmRendererAdapter(s));
		}

		@Override
		public SwarmGraphClient client(SDUtil s) {
			return new SwarmGraphClient(s);
		}

		@Override
		public PbQaUtil.Selection qaProvider(BaseRecord user, BaseRecord chatConfig, SDUtil s, String checkpoint) {
			if(!forceSwarm) {
				return PbNodeExecutor.RenderBackend.super.qaProvider(user, chatConfig, s, checkpoint);
			}
			return new PbQaUtil.Selection(new SwarmQaProvider(client(s), checkpoint), "p86: swarm forced");
		}

		final class StrippingAdapter implements RendererAdapter {
			private final RendererAdapter inner;

			StrippingAdapter(RendererAdapter inner) {
				this.inner = inner;
			}

			@Override
			public RendererCapabilities capabilities(String model) {
				return inner.capabilities(model);
			}

			@Override
			public List<String> listModels() {
				return inner.listModels();
			}

			@Override
			public StageResult execute(StageRequest request, CancelToken cancel) {
				StageRequest req = request;
				if(strippedCount == 0 && RenderPlan.STAGE_BASE.equals(request.getStage())) {
					strippedCount++;
					if(request.isGraph()) {
						String scene = (request.getInputs() != null ? request.getInputs().text(WorkflowCompiler.TAG_SCENE) : null);
						Strip s = strip(scene);
						record(s);
						if(s.failure == null) {
							request.getInputs().text(WorkflowCompiler.TAG_SCENE, s.after);
						}
					}
					else {
						String scene = (request.getPrompt() != null ? request.getPrompt().getPrompt() : null);
						Strip s = strip(scene);
						record(s);
						if(s.failure == null) {
							req = request.toBuilder().prompt(new CompiledPrompt(s.after, request.getPrompt().getNegative(), 0)).build();
						}
					}
					log("strip (" + (request.isGraph() ? "graph scene input" : "T2I prompt") + "): " + (failure != null ? "FAILED " + failure : "removed '" + removed + "'"));
				}
				return inner.execute(req, cancel);
			}

			private void record(Strip s) {
				before = s.before;
				removed = s.removed;
				after = s.after;
				failure = s.failure;
			}
		}
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Running a scene through the executor                                                             */
	/* ------------------------------------------------------------------------------------------------ */

	/// Mirrors PbServiceFacade.ensureRenderPlanNode (private): find-or-create the scene's RENDER_PLAN node, then the
	/// 8-arg executeNodeRun with the test backend.
	private Map<String, Object> runScene(Fixture f, PbQualityPresetEnumType preset, BaseRecord chatConfig, PbNodeExecutor.RenderBackend backend) throws Exception {
		BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(owner, book, PbBookUtil.workflowGroupPath(f.slug));
		assertNotNull("workflow", wf);
		String handle = PbPipelineUtil.renderPlanHandle(f.sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(owner, wf, handle);
		if(node == null) {
			node = PbGraphUtil.addNode(owner, wf, handle, PbNodeTypeEnumType.RENDER_PLAN, PbBookUtil.workflowGroupPath(f.slug), 0);
			assertNotNull("render-plan node", node);
			assertTrue("scope render-plan node", PbPipelineUtil.patchNodeScope(owner, wf, node, PbPipelineUtil.SCOPE_SCENE, f.sceneOid, 0));
		}
		node = PbGraphUtil.readNode(owner, oidOf(node), orgId);
		assertNotNull("render-plan node re-read", node);
		PbRenderPlanUtil.PlanOptions opts = new PbRenderPlanUtil.PlanOptions(null, false, preset, null, chatConfig);
		long t0 = System.currentTimeMillis();
		Map<String, Object> out = PbNodeExecutor.executeNodeRun(owner, book, wf, node, sdu, opts, null, backend);
		assertNotNull("executor result", out);
		log(f.slug + " " + preset + " run in " + (System.currentTimeMillis() - t0) + " ms: strategy=" + out.get("strategy")
			+ " stagesRendered=" + out.get("stagesRendered") + " stageErrors=" + out.get("stageErrors") + " runStatus=" + out.get("runStatus")
			+ " nodeStatus=" + out.get("nodeStatus") + " qaMode=" + out.get("qaMode") + " degradations=" + out.get("degradations"));
		assertEquals("run completed", "COMPLETED", String.valueOf(out.get("runStatus")).toUpperCase());
		/// "executed" is a facade key (PbServiceFacade puts it on the REST DTO); the executor DTO carries the counts.
		assertEquals("one node executed", Integer.valueOf(1), out.get("executedNodeCount"));
		assertEquals("no node failed", Integer.valueOf(0), out.get("failedNodeCount"));
		return out;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> variants(Map<String, Object> out) {
		Object v = out.get("variants");
		assertNotNull("variants", v);
		return (List<Map<String, Object>>) v;
	}

	private static Map<String, Object> variantAt(List<Map<String, Object>> variants, int index) {
		for(Map<String, Object> v : variants) {
			if(v.get("variantIndex") != null && ((Number) v.get("variantIndex")).intValue() == index) return v;
		}
		fail("no variant " + index + " in " + variants);
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> qaOf(Map<String, Object> entry) {
		return (entry != null ? (Map<String, Object>) entry.get("qa") : null);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> artifactIn(Map<String, Object> view, String artifactOid) {
		for(Object o : flatten(view)) {
			if(o instanceof Map && artifactOid.equals(((Map<String, Object>) o).get("objectId")) && ((Map<String, Object>) o).containsKey("reviewStatus")) {
				return (Map<String, Object>) o;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> queued(Map<String, Object> queue, String nodeOid, String artifactOid) {
		for(Map<String, Object> n : (List<Map<String, Object>>) queue.get("nodes")) {
			if(!nodeOid.equals(n.get("nodeObjectId"))) continue;
			for(Map<String, Object> a : (List<Map<String, Object>>) n.get("artifacts")) {
				if(artifactOid.equals(a.get("objectId"))) return a;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> flatten(Object v) {
		List<Object> out = new ArrayList<>();
		if(v instanceof Map) {
			out.add(v);
			for(Object c : ((Map<String, Object>) v).values()) out.addAll(flatten(c));
		}
		else if(v instanceof List) {
			for(Object c : (List<Object>) v) out.addAll(flatten(c));
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static List<String> strings(Map<String, Object> out, String key) {
		Object v = out.get(key);
		List<String> res = new ArrayList<>();
		if(v instanceof List) {
			for(Object o : (List<Object>) v) res.add(String.valueOf(o));
		}
		return res;
	}

	private void savePages(String tag, List<Map<String, Object>> variants) throws Exception {
		for(Map<String, Object> v : variants) {
			BaseRecord a = PbArtifactUtil.readArtifact(owner, String.valueOf(v.get("artifactObjectId")), orgId);
			assertNotNull("variant artifact " + v.get("artifactObjectId"), a);
			byte[] png = PbArtifactUtil.readArtifactBytes(owner, a);
			assertPng(tag + " variant " + v.get("variantIndex"), png);
			File out = new File(OUT, tag + "-variant" + v.get("variantIndex") + ".png");
			Files.write(out.toPath(), png);
			log(tag + " variant " + v.get("variantIndex") + " -> " + out.getPath() + " (" + png.length + " bytes)");
		}
	}

	private Query runsQuery(BaseRecord u, BaseRecord wf) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_RUN, OlioFieldNames.FIELD_PB_WORKFLOW, wf);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(PbGraphUtil.runRequest());
		q.setCache(false);
		return q;
	}

	private BaseRecord latestRun(BaseRecord u, BaseRecord wf) {
		BaseRecord[] recs = ioContext.getAccessPoint().list(u, runsQuery(u, wf)).getResults();
		if(recs == null || recs.length == 0) return null;
		BaseRecord best = null;
		for(BaseRecord r : recs) {
			if(r.getSchema() == null) r.setSchema(OlioModelNames.MODEL_PB_RUN);
			if(best == null || idOf(r) > idOf(best)) best = r;
		}
		return best;
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Fixture (trimmed p52 recipe: two actors, the second narrative-only)                              */
	/* ------------------------------------------------------------------------------------------------ */

	static final class Fixture {
		String slug;
		String bookOid;
		String sceneOid;
		String locationOid;
		String landscapeOid;
		String[] charOids;
		BaseRecord style;
	}

	private Fixture fixture(String tag) throws Exception {
		Fixture f = new Fixture();
		f.slug = "p86" + tag + TestP51GraphLive.shortId();
		BaseRecord bk = PbBookUtil.createBook(owner, dataPath(), f.slug, "P86 " + tag + " " + f.slug);
		assertNotNull("fixture book", bk);
		f.bookOid = oidOf(bk);
		try {
			BookContext bctx = PbBookUtil.openBookContext(owner, bk);
			assertNotNull("book context", bctx);
			assertNotNull("book world", bctx.getWorld());
			BaseRecord popGroup = bctx.getGroup(OlioFieldNames.FIELD_POPULATION);
			String popPath = bctx.getGroupPath(OlioFieldNames.FIELD_POPULATION);
			assertNotNull("population group", popGroup);
			BaseRecord location = PbLocationUtil.getCreateLocation(owner, PbBookUtil.readBook(owner, f.bookOid, orgId), "Wet alley", setting, PbLocationKindEnumType.EXTERIOR, List.of("the alley"));
			assertNotNull("location", location);
			f.locationOid = oidOf(location);
			BaseRecord scene = addScene(owner, bk, 0, SCENE_TITLE, setting, location);
			f.sceneOid = oidOf(scene);
			f.landscapeOid = approveLandscape(owner, bk, f.locationOid);
			f.charOids = new String[2];
			long[] charIds = new long[2];
			for(int i = 0; i < 2; i++) {
				f.charOids[i] = createCharPersonIn(owner, popGroup, popPath, castNames[i], CAST_GENDER[i]);
				BaseRecord cp = readChar(owner, f.charOids[i]);
				charIds[i] = idOf(cp);
				giveNarrative(owner, cp, castPhysical[i], castOutfit[i].isEmpty() ? null : castOutfit[i]);
				enrol(owner, scene, cp);
			}
			BaseRecord book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			// Only the first actor gets an approved portrait: the second is placed by prompt text alone.
			BaseRecord art = approvedPortraitFor(owner, book, f.slug, f.charOids[0], castPng[0], castDims[0][0], castDims[0][1]);
			ComposeResult cr = PbComposeUtil.composeMigrated(owner, book, scene, false);
			assertNotNull("compose", cr);
			assertTrue("compose written: " + cr.failureReason, cr.isWritten());
			BaseRecord comp = PbComposeUtil.readComposition(owner, oidOf(cr.composition), orgId);
			List<BaseRecord> rows = PbComposeUtil.listActors(owner, comp);
			assertEquals("actor rows", 2, rows.size());
			for(int i = 0; i < 2; i++) {
				placeActor(owner, rowByCharacter(rows, charIds[i]), i, 0.04 + i * 0.48, 0.10, 0.44, 0.85);
			}
			assertNotNull("touchEdited", PbComposeUtil.touchEdited(owner, comp, null));
			assertTrue("setAutoApprove", PbBookUtil.setAutoApprove(owner, book, true));
			CacheUtil.clearCache();
			book = PbBookUtil.readBook(owner, f.bookOid, orgId);
			assertEquals("autoApprove on", Boolean.TRUE, book.get(OlioFieldNames.FIELD_PB_AUTO_APPROVE));
			f.style = pinBookStyle(owner, book, tag, flux2Model);
			log(tag + " fixture book " + f.slug + " scene " + f.sceneOid + " location " + f.locationOid + " landscape " + f.landscapeOid
				+ " (" + landscapeSource + ") actors " + Arrays.toString(f.charOids) + " portrait(" + castNames[0] + ")=" + oidOf(art) + " " + castNames[1] + "=text-only");
			return f;
		}
		catch(Exception | AssertionError e) {
			cleanup(f);
			throw e;
		}
	}

	private void cleanup(Fixture f) {
		if(f == null) return;
		if(f.bookOid != null) {
			try {
				DeleteResult res = PbDeleteUtil.deleteBookComplete(owner, f.bookOid);
				log("cleanup " + f.slug + " deleted=" + res.deleted + " reason=" + res.reason);
			}
			catch(Exception e) {
				logger.error("[p86] cleanup " + f.slug + " failed: " + e.getMessage(), e);
			}
		}
		if(f.style != null) {
			try {
				ioContext.getAccessPoint().delete(owner, f.style);
			}
			catch(Exception e) {
				logger.error("[p86] style cleanup failed: " + e.getMessage());
			}
		}
	}

	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String sceneSetting, BaseRecord location) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("scene", scene);
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SETTING, FieldNames.FIELD_LOCATION);
		patch.set(OlioFieldNames.FIELD_PB_SETTING, sceneSetting);
		patch.set(FieldNames.FIELD_LOCATION, location);
		assertNotNull("scene patch", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertEquals("scene location", idOf(location), fkId(fresh, FieldNames.FIELD_LOCATION));
		return fresh;
	}

	private String approveLandscape(BaseRecord u, BaseRecord bk, String locOid) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
		assertNotNull("workflow", wf);
		BaseRecord n = PbGraphUtil.addNode(u, wf, PbPipelineUtil.locationHandle(locOid), PbNodeTypeEnumType.LOCATION, PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull("location node", n);
		assertTrue("scope location node", PbPipelineUtil.patchNodeScope(u, wf, n, PbPipelineUtil.SCOPE_LOCATION, locOid, 0));
		BaseRecord data = persistPng(u, PbBookUtil.artifactGroupPath(slug), "loc-" + TestP51GraphLive.shortId() + ".png", landscapePng);
		BaseRecord a = PbArtifactUtil.persistArtifact(u, n, PbPipelineUtil.ROLE_LANDSCAPE, PbArtifactTypeEnumType.IMAGE, PbBookUtil.artifactGroupPath(slug), data, null, null, null);
		assertNotNull("landscape artifact", a);
		assertTrue("landscape metrics", PbArtifactUtil.recordImageMetrics(u, a, landscapePng, "image/png", landscapeDims[0], landscapeDims[1], null));
		Map<String, Object> approved = PbServiceFacade.approveArtifact(u, oidOf(bk), oidOf(a));
		assertEquals("approve names the location node", oidOf(n), approved.get("nodeObjectId"));
		BaseRecord loc = PbLocationUtil.readLocation(u, locOid, orgOf(u));
		assertNotNull("location re-read", loc);
		assertEquals("approvedRender", idOf(a), fkId(loc, OlioFieldNames.FIELD_PB_APPROVED_RENDER));
		return oidOf(a);
	}

	private BaseRecord approvedPortraitFor(BaseRecord u, BaseRecord book, String slug, String charOid, byte[] bytes, int w, int h) throws Exception {
		long oid = orgOf(u);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, book, PbBookUtil.workflowGroupPath(slug));
		BaseRecord pNode = PbGraphUtil.addNode(u, wf, PbPipelineUtil.portraitHandle(charOid), PbNodeTypeEnumType.PORTRAIT, PbBookUtil.workflowGroupPath(slug), 0);
		assertNotNull("portrait node", pNode);
		String groupPath = PbBookUtil.artifactGroupPath(slug);
		BaseRecord data = persistPng(u, groupPath, "p86-portrait-" + TestP51GraphLive.shortId() + ".png", bytes);
		BaseRecord art = PbArtifactUtil.persistArtifact(u, pNode, PbPipelineUtil.ROLE_PORTRAIT, PbArtifactTypeEnumType.IMAGE, groupPath, data, null, null, null);
		assertNotNull("portrait artifact", art);
		assertTrue("portrait metrics", PbArtifactUtil.recordImageMetrics(u, art, bytes, "image/png", w, h, null));
		assertNotNull("approve", PbArtifactUtil.approve(u, PbArtifactUtil.readArtifact(u, oidOf(art), oid), "p86"));
		assertEquals("portrait approved", PbReviewStatusEnumType.APPROVED, PbArtifactUtil.reviewStatusOf(PbArtifactUtil.readArtifact(u, oidOf(art), oid)));
		return art;
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

	private BaseRecord pinBookStyle(BaseRecord u, BaseRecord bk, String tag, String model) throws Exception {
		long oid = orgOf(u);
		Map<String, String> pins = new LinkedHashMap<>();
		for(PbModelTaskEnumType t : new PbModelTaskEnumType[] { PbModelTaskEnumType.PORTRAIT, PbModelTaskEnumType.LOCATION, PbModelTaskEnumType.SCENE_DIRECT, PbModelTaskEnumType.INPAINT }) {
			pins.put(ModelTaskKeys.key(t), model);
		}
		BaseRecord style = RecordFactory.newInstance(OlioModelNames.MODEL_PB_STYLE);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, style, "p86-" + tag + "-" + TestP51GraphLive.shortId(), STYLE_PATH, oid);
		style.set(OlioFieldNames.FIELD_PB_SCOPE, PbStyleScopeEnumType.BOOK.toString());
		style.set(OlioFieldNames.FIELD_PB_TASK_MODELS, JSONUtil.exportObject(pins));
		BaseRecord created = ioContext.getAccessPoint().create(u, style);
		assertNotNull("style " + tag, created);
		BaseRecord styleRef = PbStyleUtil.readStyle(u, oidOf(created), oid);
		assertNotNull("style re-read", styleRef);
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_STYLE });
		patch.set(FieldNames.FIELD_ID, bk.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, bk.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, bk.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_STYLE, styleRef);
		assertNotNull("book style patch " + tag, ioContext.getAccessPoint().update(u, patch));
		CacheUtil.clearCache();
		BaseRecord after = PbBookUtil.readBook(u, oidOf(bk), oid);
		BaseRecord linked = after.get(OlioFieldNames.FIELD_STYLE);
		assertNotNull("book style linked", linked);
		assertEquals("book style is " + tag, oidOf(styleRef), oidOf(linked));
		return styleRef;
	}

	private void placeActor(BaseRecord u, BaseRecord row, int priority, double x, double y, double w, double h) throws Exception {
		BaseRecord patch = PbGraphUtil.patchOf(row, OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioFieldNames.FIELD_PB_PRIORITY,
			OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y, OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H);
		patch.set(OlioFieldNames.FIELD_PB_PRIORITY, priority);
		patch.set(OlioFieldNames.FIELD_PB_X, x);
		patch.set(OlioFieldNames.FIELD_PB_Y, y);
		patch.set(OlioFieldNames.FIELD_PB_W, w);
		patch.set(OlioFieldNames.FIELD_PB_H, h);
		assertNotNull("place actor row " + row.get(FieldNames.FIELD_NAME), ioContext.getAccessPoint().update(u, patch));
	}

	private void enrol(BaseRecord u, BaseRecord scene, BaseRecord cp) {
		assertTrue("enrol " + cp.get(FieldNames.FIELD_NAME), ioContext.getMemberUtil().member(u, scene, OlioFieldNames.FIELD_PB_CHARACTERS, cp, null, true));
	}

	private BaseRecord readChar(BaseRecord u, String oid) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, oid);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID });
		q.setCache(false);
		BaseRecord cp = ioContext.getAccessPoint().find(u, q);
		assertNotNull("character " + oid, cp);
		return cp;
	}

	private void giveNarrative(BaseRecord u, BaseRecord cp, String physical, String outfit) throws Exception {
		BaseRecord narrative = PbSubRecordUtil.createSubRecord(u, null, OlioModelNames.MODEL_NARRATIVE);
		assertNotNull("narrative", narrative);
		List<String> fields = new ArrayList<>(Arrays.asList(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "physicalDescription"));
		if(outfit != null) fields.add("outfitDescription");
		BaseRecord np = RecordFactory.newInstance(OlioModelNames.MODEL_NARRATIVE, fields.toArray(new String[0]));
		np.set(FieldNames.FIELD_ID, narrative.get(FieldNames.FIELD_ID));
		np.set(FieldNames.FIELD_OBJECT_ID, narrative.get(FieldNames.FIELD_OBJECT_ID));
		np.set("physicalDescription", physical);
		if(outfit != null) np.set("outfitDescription", outfit);
		assertNotNull("narrative patch", ioContext.getAccessPoint().update(u, np));
		BaseRecord lp = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON, new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "narrative" });
		lp.set(FieldNames.FIELD_ID, cp.get(FieldNames.FIELD_ID));
		lp.set(FieldNames.FIELD_OBJECT_ID, cp.get(FieldNames.FIELD_OBJECT_ID));
		lp.set(FieldNames.FIELD_NAME, cp.get(FieldNames.FIELD_NAME));
		lp.set("narrative", narrative);
		assertNotNull("narrative link patch", ioContext.getAccessPoint().update(u, lp));
	}

	private static final String[] CHAR_SUB_RECORD_FIELDS = new String[] {
		FieldNames.FIELD_PROFILE, OlioFieldNames.FIELD_STATISTICS, FieldNames.FIELD_STORE, OlioFieldNames.FIELD_INSTINCT,
		FieldNames.FIELD_BEHAVIOR, FieldNames.FIELD_PERSONALITY, FieldNames.FIELD_STATE
	};

	private static String createCharPersonIn(BaseRecord user, BaseRecord group, String groupPath, String name, String gender) throws Exception {
		long groupId = ((Number) group.get(FieldNames.FIELD_ID)).longValue();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cp = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, user, null, plist);
		cp.set(FieldNames.FIELD_NAME, name);
		cp.set(FieldNames.FIELD_FIRST_NAME, name.split(" ")[0]);
		cp.set(FieldNames.FIELD_GENDER, gender);
		for(String f : CHAR_SUB_RECORD_FIELDS) {
			BaseRecord sub = cp.get(f);
			if(sub != null) sub.set(FieldNames.FIELD_GROUP_ID, groupId);
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("create character " + name, created);
		return created.get(FieldNames.FIELD_OBJECT_ID);
	}

	private static BaseRecord rowByCharacter(List<BaseRecord> rows, long charId) {
		for(BaseRecord r : rows) {
			if(fkId(r, OlioFieldNames.FIELD_PB_CHARACTER) == charId) return r;
		}
		fail("no actor row for character id " + charId);
		return null;
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Book-58 cast (the real portraits, narratives and landscape) and the t01 plan                      */
	/* ------------------------------------------------------------------------------------------------ */

	private RenderPlan twoActorPlan() {
		return RenderPlan.builder().purpose("scene").canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 20, 1, false, QualityPresets.QA_CANONICAL))
			.location(new RenderPlan.Location("loc-" + TestP51GraphLive.shortId(), setting))
			.camera(new RenderPlan.Camera(CAMERA))
			.actor(planActor(0, new RenderPlan.Box(0.05, 0.10, 0.45, 0.85)))
			.actor(planActor(1, new RenderPlan.Box(0.50, 0.05, 0.45, 0.90))).build();
	}

	/// The portrait id is the cast name so the t01 resolver can hand the provider the real book-58 portrait bytes.
	private RenderPlan.Actor planActor(int i, RenderPlan.Box box) {
		return new RenderPlan.Actor.Builder("actor-" + TestP51GraphLive.shortId()).characterId("char-" + TestP51GraphLive.shortId())
			.name(castNames[i]).role(RenderPlan.ROLE_FOREGROUND).box(box)
			.outfit(new RenderPlan.Outfit(null, castOutfit[i].isEmpty() ? null : castOutfit[i], null))
			.identity(new RenderPlan.Identity(castNames[i], null, castPhysical[i]))
			.referenced(true).referenceStage(RenderPlan.STAGE_BASE).priority(i).build();
	}

	private void readCast() throws Exception {
		BaseRecord book58 = PbBookUtil.findBookBySlug(owner, TestP51GraphLive.CUSTOM_BOOK_SLUG, orgId);
		assertNotNull("book " + TestP51GraphLive.CUSTOM_BOOK_SLUG, book58);
		List<BaseRecord> scenes = PbBookUtil.listScenes(owner, book58);
		assertFalse("book 58 scenes", scenes.isEmpty());
		BaseRecord s0 = scenes.get(0);
		setting = TestP51GraphLive.text(s0.get(OlioFieldNames.FIELD_PB_SETTING), TestP51GraphLive.FALLBACK_SETTING);
		Map<String, String> oidByName = new LinkedHashMap<>();
		for(Map<String, Object> c : PictureBookUtil.listCharacters(owner, oidOf(book58))) {
			oidByName.put(String.valueOf(c.get("name")), String.valueOf(c.get("objectId")));
		}
		for(int i = 0; i < 2; i++) {
			String name = TestP51GraphLive.CAST_ORDER[i];
			String oid = oidByName.get(name);
			assertNotNull("book-58 cast member " + name + " in " + oidByName.keySet(), oid);
			BaseRecord person = TestP51GraphLive.readPerson(owner, oid, orgId);
			assertNotNull("person " + name, person);
			BaseRecord profile = person.get(FieldNames.FIELD_PROFILE);
			assertNotNull("profile " + name, profile);
			BaseRecord portrait = profile.get("portrait");
			assertNotNull("portrait ref " + name, portrait);
			String portraitOid = portrait.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord data = TestP51GraphLive.freshReadData(owner, portraitOid, orgId);
			if(data == null && olioUser != null) data = TestP51GraphLive.freshReadData(olioUser, portraitOid, orgId);
			assertNotNull("portrait data " + name, data);
			byte[] png = ByteModelUtil.getValue(data);
			assertTrue("portrait bytes " + name, png != null && png.length > 1024);
			assertPng("portrait " + name, png);
			castNames[i] = name;
			castPng[i] = png;
			castDims[i] = ImageDims.decode(png);
			portraitByName.put(name, png);
			BaseRecord narrative = person.get(OlioFieldNames.FIELD_NARRATIVE);
			String physical = (narrative != null ? TestP51GraphLive.text(narrative.get("physicalDescription"), "") : "");
			String outfit = (narrative != null ? TestP51GraphLive.text(narrative.get("outfitDescription"), "") : "");
			castPhysical[i] = physical.isEmpty() ? name + ", as drawn in the portrait" : physical;
			castOutfit[i] = outfit;
			log("cast " + i + " " + name + " portrait " + castDims[i][0] + "x" + castDims[i][1] + " physical=" + castPhysical[i].length() + " chars outfit=" + outfit.length() + " chars");
		}
		readLandscape(book58, s0);
	}

	private void readLandscape(BaseRecord book58, BaseRecord s0) throws Exception {
		landscapePng = null;
		BaseRecord wf = PbGraphUtil.findWorkflow(owner, book58);
		if(wf != null) {
			BaseRecord node = PbPipelineUtil.findNodeByHandle(owner, wf, PbPipelineUtil.landscapeHandle(oidOf(s0)));
			if(node != null) {
				List<BaseRecord> chain = PbArtifactUtil.listChain(owner, node, PbPipelineUtil.ROLE_LANDSCAPE);
				if((chain == null || chain.isEmpty()) && olioUser != null) chain = PbArtifactUtil.listChain(olioUser, node, PbPipelineUtil.ROLE_LANDSCAPE);
				if(chain != null && !chain.isEmpty()) {
					BaseRecord art = chain.get(chain.size() - 1);
					BaseRecord data = art.get(OlioFieldNames.FIELD_PB_DATA);
					String dataOid = (data != null ? data.get(FieldNames.FIELD_OBJECT_ID) : null);
					if(data != null && dataOid == null) {
						ioContext.getReader().populate(data, new String[] { FieldNames.FIELD_OBJECT_ID });
						dataOid = data.get(FieldNames.FIELD_OBJECT_ID);
					}
					BaseRecord fresh = (dataOid != null ? TestP51GraphLive.freshReadData(owner, dataOid, orgId) : null);
					if(fresh == null && dataOid != null && olioUser != null) fresh = TestP51GraphLive.freshReadData(olioUser, dataOid, orgId);
					byte[] png = (fresh != null ? ByteModelUtil.getValue(fresh) : null);
					if(png != null && png.length > 1024 && isPng(png)) {
						landscapePng = png;
						landscapeSource = "book-58 scene-0 landscape " + oidOf(art);
					}
				}
			}
		}
		if(landscapePng == null) {
			landscapePng = Files.readAllBytes(new File(LANDSCAPE_FALLBACK).toPath());
			assertPng("landscape fallback", landscapePng);
			landscapeSource = LANDSCAPE_FALLBACK;
		}
		landscapeDims = ImageDims.decode(landscapePng);
		log("landscape " + landscapeDims[0] + "x" + landscapeDims[1] + " from " + landscapeSource);
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* Small helpers                                                                                     */
	/* ------------------------------------------------------------------------------------------------ */

	private static void assertPng(String label, byte[] bytes) {
		assertTrue(label + " is a PNG", isPng(bytes));
	}

	private static boolean isPng(byte[] bytes) {
		if(bytes == null || bytes.length < PNG_MAGIC.length) return false;
		for(int i = 0; i < PNG_MAGIC.length; i++) {
			if(bytes[i] != PNG_MAGIC[i]) return false;
		}
		return true;
	}

	private static void writeText(String name, String text) throws Exception {
		Files.write(new File(OUT, name).toPath(), (text != null ? text : "").getBytes(StandardCharsets.UTF_8));
	}

	private String prop(String key, String def) {
		String v = testProperties.getProperty(key);
		return (v != null && !v.trim().isEmpty() ? v.trim() : def);
	}

	private static void log(String msg) {
		logger.info("[p86] " + msg);
	}
}
