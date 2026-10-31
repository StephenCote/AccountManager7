package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.illustration.GraphInputs;
import org.cote.accountmanager.olio.illustration.QaReport;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SwarmGraphClient;
import org.cote.accountmanager.olio.illustration.SwarmModelInventory;
import org.cote.accountmanager.olio.illustration.SwarmQaProvider;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.junit.Assume;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * V3 Phase 8, P8-3: {@link SwarmQaProvider} against the live renderer (design §17 fallback, ledger §6.C 220 f).
 * The page under inspection is {@code media/flux/qa/p52-t01-direct.png} - the real P5-2 t01 two-actor render
 * (Duña de Rosa left, Jideon de Rosa right); the detector is {@code face_yolov8m.pt} through the raw graph
 * {@link SwarmQaProvider#qaGraph()}. Gated like the other live Swarm tests: {@code PB_SD_TESTS=1} or
 * {@code -Dpb.sd.tests=1}; a skip is NOT a pass. The raw post names the checkpoint
 * {@code SdTestGate.resolveInstalledCheckpoints} picked (the graph has no sampler, but Swarm's model check
 * still runs on a raw graph, design §38). The mask the renderer returns is written to {@code target/p83/}
 * for inspection; timings are logged as observations, never asserted.
 *
 * <p>The three-actor layout here differs from P8-2's: the detector attributes faces by box geometry only, so
 * the third actor is planned on the right 28% of the page, where the render has no face, making the absence
 * attributable to that actor rather than to a box that merely missed the face.
 *
 * Run: {@code mvn -o -pl AccountManagerObjects7 -Dtest=TestSwarmQaLive -DskipTests=false -Dpb.sd.tests=1 test}
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TestSwarmQaLive extends BaseTest {

	private static final Logger logger = LogManager.getLogger(TestSwarmQaLive.class);

	static final File PAGE = new File("media/flux/qa/p52-t01-direct.png");
	static final File OUT = new File("target/p83");
	static final long TIMEOUT_MS = 10L * 60L * 1000L;

	private String server;
	private String model;
	private SwarmGraphClient client;
	private SwarmQaProvider provider;
	private byte[] page;

	@Before
	public void liveSetup() throws Exception {
		Assume.assumeTrue(TestP51GraphLive.SKIP, TestP51GraphLive.gated());
		Assume.assumeTrue("SKIPPED (not a pass): page " + PAGE.getAbsolutePath() + " is missing", PAGE.isFile());
		page = Files.readAllBytes(PAGE.toPath());
		SdTestGate.resolveInstalledCheckpoints(testProperties);
		server = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		SdTestGate.requireSwarmReachable(server);
		model = testProperties.getProperty(SdTestGate.PROP_SWARM_MODEL);
		Assume.assumeTrue("SKIPPED (not a pass): no installed checkpoint resolved on " + server, model != null && !model.isBlank());
		SwarmModelInventory.invalidateAll();
		client = new SwarmGraphClient(new SDUtil(SDAPIEnumType.SWARM, server));
		provider = new SwarmQaProvider(client, model);
		OUT.mkdirs();
		logger.info("[p83] server=" + server + " (" + client.swarmVersion() + ") checkpoint=" + model + " page=" + PAGE + " (" + page.length + " bytes)");
	}

	@Test(timeout = TIMEOUT_MS)
	public void t01_detectorIsListedByTheRenderer() {
		long t0 = System.currentTimeMillis();
		boolean avail = provider.isAvailable();
		logger.info("[p83] t01 object_info probe for " + SwarmQaProvider.CLASS_YOLO + " on " + server + ": available=" + avail + " in " + (System.currentTimeMillis() - t0) + " ms");
		assertTrue(SwarmQaProvider.DETECTOR_MODEL + " is installed on " + server + " (measured 2026-10-30); if this fails the renderer changed", avail);
		List<String> models = SwarmQaProvider.detectorModels(client.objectInfoJson(SwarmQaProvider.CLASS_YOLO));
		assertNotNull(models);
		assertTrue(models.toString(), models.contains(SwarmQaProvider.DETECTOR_MODEL));
	}

	@Test(timeout = TIMEOUT_MS)
	public void t02_twoActorPagePasses() throws Exception {
		/// Test-side instrumentation: run the same graph once more to keep the mask for inspection.
		GraphInputs inputs = new GraphInputs().image(SwarmQaProvider.INPUT_PAGE, page);
		long t0 = System.currentTimeMillis();
		SwarmGraphClient.RawResponse raw = client.generateRaw(SwarmQaProvider.qaGraph(), inputs, model, 1, null);
		long genMs = System.currentTimeMillis() - t0;
		assertNotNull(raw);
		assertNull("raw graph accepted: " + raw.getError(), raw.getError());
		assertEquals(1, raw.getImagePaths().size());
		byte[] mask = client.fetchImage(raw.getImagePaths().get(0));
		assertNotNull(mask);
		File maskFile = new File(OUT, "t02-mask.png");
		Files.write(maskFile.toPath(), mask);
		List<SwarmQaProvider.Face> faces = SwarmQaProvider.faces(mask);
		assertNotNull("mask decodes", faces);
		logger.info("[p83] t02 raw graph on " + server + ": " + raw.getImagePaths().get(0) + " in " + genMs + " ms (renderer " + raw.getDurationMs()
			+ " ms); mask " + mask.length + " bytes -> " + maskFile + "; faces=" + faces + " (" + faces.size() + ")");

		t0 = System.currentTimeMillis();
		QaReport rep = provider.check(twoActorPlan(), page);
		long ms = System.currentTimeMillis() - t0;
		write("t02", rep, ms);
		assertEquals("two actors, two faces: " + rep.toJson(), QaReport.Result.PASS, rep.getResult());
		assertEquals(2, faces.size());
		QaReport.Check count = rep.checksOf(QaReport.KIND_PERSON_COUNT).get(0);
		assertEquals("2", count.getExpected());
		assertEquals("2", count.getObserved());
		List<QaReport.Check> present = rep.checksOf(QaReport.KIND_ACTOR_PRESENT);
		assertEquals(2, present.size());
		assertEquals(TestP51GraphLive.CAST_ORDER[0], present.get(0).getExpected());
		assertEquals(QaReport.Result.PASS, present.get(0).getResult());
		assertEquals(TestP51GraphLive.CAST_ORDER[1], present.get(1).getExpected());
		assertEquals(QaReport.Result.PASS, present.get(1).getResult());
		for(QaReport.Check c : rep.checksOf(QaReport.KIND_IDENTITY)) {
			assertEquals("identity is never judged by a detector", QaReport.Result.UNAVAILABLE, c.getResult());
		}
	}

	@Test(timeout = TIMEOUT_MS)
	public void t03_thirdActorPlannedOnTheRightIsAbsent() throws Exception {
		long t0 = System.currentTimeMillis();
		QaReport rep = provider.check(threeActorPlan(), page);
		long ms = System.currentTimeMillis() - t0;
		write("t03", rep, ms);
		assertEquals("three planned, two drawn: " + rep.toJson(), QaReport.Result.FAIL, rep.getResult());
		QaReport.Check count = rep.checksOf(QaReport.KIND_PERSON_COUNT).get(0);
		assertEquals(QaReport.Result.FAIL, count.getResult());
		assertEquals("3", count.getExpected());
		assertEquals("2", count.getObserved());
		List<QaReport.Check> present = rep.checksOf(QaReport.KIND_ACTOR_PRESENT);
		assertEquals(3, present.size());
		assertEquals(QaReport.Result.PASS, present.get(0).getResult());
		assertEquals(QaReport.Result.PASS, present.get(1).getResult());
		assertEquals(TestP51GraphLive.CAST_ORDER[2], present.get(2).getExpected());
		assertEquals("Touvier was planned where no face is", QaReport.Result.FAIL, present.get(2).getResult());
	}

	private void write(String tag, QaReport rep, long ms) throws Exception {
		Files.write(new File(OUT, tag + "-report.json").toPath(), rep.toJson().getBytes(StandardCharsets.UTF_8));
		logger.info("[p83] " + tag + " " + rep + " in " + ms + " ms (provider " + rep.getDurationMs() + " ms) on " + server);
		for(QaReport.Check c : rep.getChecks()) {
			logger.info("[p83] " + tag + "   " + c.getKind() + " " + c.getResult() + " expected=" + c.getExpected() + " observed=" + c.getObserved()
				+ (c.getDetail() != null ? " - " + c.getDetail() : ""));
		}
	}

	/// The P8-2 two-actor layout (TestPbVlmQaLive): left 45% and right 45%.
	private RenderPlan twoActorPlan() {
		return plan(
			actor(TestP51GraphLive.CAST_ORDER[0], new RenderPlan.Box(0.05, 0.10, 0.45, 0.85)),
			actor(TestP51GraphLive.CAST_ORDER[1], new RenderPlan.Box(0.50, 0.05, 0.45, 0.90)));
	}

	/// The same two plus Touvier on the right 28%, where the P5-2 render has no face.
	private RenderPlan threeActorPlan() {
		return plan(
			actor(TestP51GraphLive.CAST_ORDER[0], new RenderPlan.Box(0.05, 0.10, 0.45, 0.85)),
			actor(TestP51GraphLive.CAST_ORDER[1], new RenderPlan.Box(0.50, 0.05, 0.45, 0.90)),
			actor(TestP51GraphLive.CAST_ORDER[2], new RenderPlan.Box(0.70, 0.10, 0.28, 0.85)));
	}

	private RenderPlan plan(RenderPlan.Actor... actors) {
		RenderPlan.Builder b = RenderPlan.builder()
			.purpose("scene")
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 20, 1, false, QualityPresets.QA_CANONICAL))
			.location(new RenderPlan.Location("loc-" + TestP51GraphLive.shortId(), TestP51GraphLive.FALLBACK_SETTING))
			.camera(new RenderPlan.Camera("medium wide shot at eye level, both figures standing and facing the viewer"));
		for(RenderPlan.Actor a : actors) {
			b.actor(a);
		}
		return b.build();
	}

	/// A referenced foreground actor from book 58's cast; no outfit text, since a detector never judges it.
	private RenderPlan.Actor actor(String name, RenderPlan.Box box) {
		return new RenderPlan.Actor.Builder("actor-" + TestP51GraphLive.shortId())
			.characterId("char-" + TestP51GraphLive.shortId())
			.name(name)
			.role(RenderPlan.ROLE_FOREGROUND)
			.box(box)
			.referenced(true)
			.referenceStage(RenderPlan.STAGE_BASE)
			.build();
	}
}
