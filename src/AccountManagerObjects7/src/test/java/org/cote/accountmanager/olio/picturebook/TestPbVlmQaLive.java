package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.QaProvider;
import org.cote.accountmanager.olio.illustration.QaReport;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 8, P8-2: {@link VlmQaProvider} against the live vision model on the test LLM route
 * (ledger §6.C 220 e / 220 h; design §17).
 * <p>
 * The page under inspection is {@code media/flux/qa/p52-t01-direct.png} - the real P5-2 t01 DIRECT_REFERENCE
 * render of book 58's Duña de Rosa and Jideon de Rosa in the wet alley (inspected 2026-10-30: woman with curly
 * dark hair and pink glasses in a pale patterned shirt and shorts on the left, bearded man in a maroon leather
 * jacket and light-blue jeans on the right). The plan is built from book 58's real cast (narrative
 * {@code physicalDescription} / {@code outfitDescription}, portraits as reference images) read through PBAC as
 * {@code pbCustomTestUser}, never admin.
 * <p>
 * Gate (220 h): {@code test.llm.vision.model} blank, the route not answering, or the model not listed there
 * all skip with a printed reason - a skip is NOT a pass. Durations are logged, never asserted.
 */
public class TestPbVlmQaLive extends BaseTest {

	private static final Logger logger = LogManager.getLogger(TestPbVlmQaLive.class);

	static final String PROP_VISION_MODEL = "test.llm.vision.model";
	static final String PROP_SERVER = "test.llm.ollama.server";
	static final File PAGE = new File("media/flux/qa/p52-t01-direct.png");
	static final File OUT = new File("target/p82");
	static final long TIMEOUT_MS = 30L * 60L * 1000L;

	static final String SETTING = TestP51GraphLive.FALLBACK_SETTING;
	static final String CAMERA = "medium wide shot at eye level, both figures standing and facing the viewer";

	private String server;
	private String visionModel;
	private byte[] page;
	private OrganizationContext org;
	private BaseRecord owner;
	private BaseRecord olioUser;
	private long orgId;

	/// book 58 cast, in TestP51GraphLive.CAST_ORDER
	private final Map<String, byte[]> portraitByName = new LinkedHashMap<>();
	private final Map<String, String> physicalByName = new HashMap<>();
	private final Map<String, String> outfitByName = new HashMap<>();

	@Before
	public void vlmSetup() throws Exception {
		visionModel = testProperties.getProperty(PROP_VISION_MODEL, "").trim();
		Assume.assumeTrue("SKIPPED (not a pass): " + PROP_VISION_MODEL + " is blank in resource.properties", !visionModel.isEmpty());
		server = testProperties.getProperty(PROP_SERVER, "").trim();
		Assume.assumeTrue("SKIPPED (not a pass): " + PROP_SERVER + " is blank", !server.isEmpty());
		Assume.assumeTrue("SKIPPED (not a pass): page " + PAGE.getAbsolutePath() + " is missing", PAGE.isFile());
		page = Files.readAllBytes(PAGE.toPath());
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		OUT.mkdirs();
		logger.info("[p82] route=" + server + " vision model=" + visionModel + " (" + PROP_VISION_MODEL + "; production reads qualityPresets.json qaModel="
			+ QualityPresets.qaModel() + ") page=" + PAGE + " (" + page.length + " bytes)");

		org = getTestOrganization(TestP51GraphLive.CUSTOM_ORG_PATH);
		assertNotNull("custom test organization", org);
		owner = getCreateUser(TestP51GraphLive.CUSTOM_OWNER, org);
		assertNotNull("owner " + TestP51GraphLive.CUSTOM_OWNER, owner);
		orgId = TestP51GraphLive.orgOf(owner);
		olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		readCast();
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* offline                                                                                           */
	/* ------------------------------------------------------------------------------------------------ */

	@Test
	public void t00_qaModelReadsResource() {
		QualityPresets.clearCache();
		String m = QualityPresets.qaModel();
		assertNotNull(m);
		assertFalse("qaModel never blank", m.isBlank());
		assertEquals("qualityPresets.json qaModel", "valkyriesys/eudaimonia-dryad3-vision", m);
	}

	/** Prompt shape and answer mapping with a canned Ollama answer - no network. */
	@Test
	public void t01_offlinePromptAndMapping() throws Exception {
		CannedTransport t = new CannedTransport();
		t.tags = "{\"models\":[{\"name\":\"" + visionModel + ":latest\"},{\"name\":\"qwen3:8b\"}]}";
		t.answer = ollamaEnvelope("{\"person_count\": 2, \"actors\": [{\"id\": \"A1\", \"present\": true, \"identity_match\": true, \"outfit_match\": false, \"note\": \"wears shorts instead of jeans\"},"
			+ " {\"id\": \"a2\", \"present\": \"yes\", \"identity_match\": \"null\", \"outfit_match\": true, \"note\": null}], \"location_match\": true, \"composition_match\": false, \"notes\": \"A1 lacks jeans.\"}", "stop");
		VlmQaProvider.Route route = new VlmQaProvider.Route(server + "/", LLMServiceEnumType.OLLAMA, null, visionModel);
		VlmQaProvider p = new VlmQaProvider(route, t, oid -> portraitByName.get(oid));
		assertEquals("vlm", p.name());
		assertTrue("route supported", route.isSupported());
		assertEquals("trailing slash trimmed", server, route.getServerUrl());
		RenderPlan plan = twoActorPlan();

		VlmQaProvider.Prompt prompt = p.buildPrompt(plan);
		assertEquals(2, prompt.expectedForeground);
		assertEquals(2, prompt.referenceImages.size());
		assertTrue("A1 labelled", prompt.text.contains("A1: \"" + TestP51GraphLive.CAST_ORDER[0] + "\""));
		assertTrue("A2 labelled", prompt.text.contains("A2: \"" + TestP51GraphLive.CAST_ORDER[1] + "\""));
		assertTrue("portrait image index", prompt.text.contains("reference portrait of this character: image 2") && prompt.text.contains("image 3"));
		assertTrue("setting", prompt.text.contains(SETTING));
		assertTrue("camera", prompt.text.contains(CAMERA));
		assertTrue("expected count", prompt.text.contains("foreground: 2"));
		for(RenderPlan.Actor a : plan.getActors()) {
			assertFalse("actorId never leaves the process: " + a.getActorId(), prompt.text.contains(a.getActorId()));
		}
		Files.write(new File(OUT, "t01-prompt.txt").toPath(), prompt.text.getBytes(StandardCharsets.UTF_8));

		QaReport rep = p.check(plan, page);
		logger.info("[p82] t01 canned: " + rep.toJson());
		assertEquals("one tags probe", 1, t.gets);
		assertEquals("one chat call", 1, t.posts);
		assertTrue("posted to /api/chat", t.lastPostUrl.endsWith("/api/chat"));
		assertTrue("format json", t.lastPostBody.contains("\"format\":\"json\""));
		assertTrue("num_predict guard", t.lastPostBody.contains("\"num_predict\":" + VlmQaProvider.MAX_OUTPUT_TOKENS));
		assertTrue("3 images on the message", t.lastPostBody.contains("\"images\":[") && countOf(t.lastPostBody, "\"images\":[\"") == 1);
		assertEquals(QaReport.Result.WARN, rep.getResult());
		assertEquals(visionModel, rep.getModel());
		assertEquals("A1 lacks jeans.", rep.getNote());
		assertEquals(QaReport.Result.PASS, rep.checksOf(QaReport.KIND_PERSON_COUNT).get(0).getResult());
		List<QaReport.Check> present = rep.checksOf(QaReport.KIND_ACTOR_PRESENT);
		assertEquals(2, present.size());
		assertEquals(QaReport.Result.PASS, present.get(0).getResult());
		assertEquals("\"yes\" reads as present", QaReport.Result.PASS, present.get(1).getResult());
		assertEquals("actorId mapped back from A1", plan.getActors().get(0).getActorId(), present.get(0).getActorId());
		List<QaReport.Check> identity = rep.checksOf(QaReport.KIND_IDENTITY);
		assertEquals(2, identity.size());
		assertEquals(QaReport.Result.PASS, identity.get(0).getResult());
		assertEquals("reference portrait (image 2)", identity.get(0).getExpected());
		assertEquals("literal \"null\" is no observation", QaReport.Result.UNAVAILABLE, identity.get(1).getResult());
		List<QaReport.Check> outfit = rep.checksOf(QaReport.KIND_OUTFIT);
		assertEquals(2, outfit.size());
		assertEquals(QaReport.Result.WARN, outfit.get(0).getResult());
		assertEquals("wears shorts instead of jeans", outfit.get(0).getDetail());
		assertEquals(QaReport.Result.PASS, outfit.get(1).getResult());
		assertEquals(QaReport.Result.PASS, rep.checksOf(QaReport.KIND_LOCATION).get(0).getResult());
		assertEquals(QaReport.Result.WARN, rep.checksOf(QaReport.KIND_COMPOSITION).get(0).getResult());
		assertEquals("round trip", rep.getResult(), QaReport.fromJson(rep.toJson()).getResult());

		/// stop=length with empty content (the dryad3 1024-token trap) is UNAVAILABLE with the reason
		CannedTransport t2 = new CannedTransport();
		t2.tags = t.tags;
		t2.answer = ollamaEnvelope("", "length");
		QaReport r2 = new VlmQaProvider(route, t2, oid -> null).check(plan, page);
		assertEquals(QaReport.Result.UNAVAILABLE, r2.getResult());
		assertTrue(r2.getNote(), r2.getNote().contains("token budget"));

		/// model not listed: UNAVAILABLE without a chat call
		CannedTransport t3 = new CannedTransport();
		t3.tags = "{\"models\":[{\"name\":\"qwen3:8b\"}]}";
		VlmQaProvider p3 = new VlmQaProvider(route, t3, oid -> null);
		assertFalse(p3.isAvailable());
		QaReport r3 = p3.check(plan, page);
		assertEquals(QaReport.Result.UNAVAILABLE, r3.getResult());
		assertTrue(r3.getNote(), r3.getNote().contains("not listed"));
		assertEquals("no chat call", 0, t3.posts);
		assertEquals("probed once", 1, t3.gets);

		/// unsupported dialect (Azure OPENAI) / absent route: UNAVAILABLE, no network at all
		CannedTransport t4 = new CannedTransport();
		QaReport r4 = new VlmQaProvider(new VlmQaProvider.Route(server, LLMServiceEnumType.OPENAI, "k", visionModel), t4, oid -> null).check(plan, page);
		assertEquals(QaReport.Result.UNAVAILABLE, r4.getResult());
		assertEquals(0, t4.gets + t4.posts);
		QaReport r5 = new VlmQaProvider(null, t4, null).check(plan, page);
		assertEquals(QaReport.Result.UNAVAILABLE, r5.getResult());
		assertEquals(0, t4.gets + t4.posts);

		/// prose instead of JSON: UNAVAILABLE
		CannedTransport t6 = new CannedTransport();
		t6.tags = t.tags;
		t6.answer = ollamaEnvelope("I see two people in an alley.", "stop");
		QaReport r6 = new VlmQaProvider(route, t6, oid -> null).check(plan, page);
		assertEquals(QaReport.Result.UNAVAILABLE, r6.getResult());

		/// OpenAI-compatible shape: /v1/models probe, /v1/chat/completions body, choices[0] answer
		CannedTransport t7 = new CannedTransport();
		t7.tags = "{\"data\":[{\"id\":\"" + visionModel + "\"}]}";
		t7.answer = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"person_count\\\": 1, \\\"actors\\\": [{\\\"id\\\": \\\"A1\\\", \\\"present\\\": true}, {\\\"id\\\": \\\"A2\\\", \\\"present\\\": false, \\\"note\\\": \\\"not visible\\\"}], \\\"location_match\\\": null, \\\"composition_match\\\": null, \\\"notes\\\": \\\"only one figure\\\"}\"}}]}";
		VlmQaProvider p7 = new VlmQaProvider(new VlmQaProvider.Route(server, LLMServiceEnumType.OPENAI_COMPAT, "sk-test", visionModel), t7, oid -> null);
		QaReport r7 = p7.check(plan, page);
		assertTrue(t7.lastGetUrl.endsWith("/v1/models"));
		assertTrue(t7.lastPostUrl.endsWith("/v1/chat/completions"));
		assertEquals("Bearer sk-test", t7.lastPostHeaders.get("Authorization"));
		assertTrue("max_tokens guard", t7.lastPostBody.contains("\"max_tokens\":" + VlmQaProvider.MAX_OUTPUT_TOKENS));
		assertTrue("json_object", t7.lastPostBody.contains("\"response_format\":{\"type\":\"json_object\"}"));
		assertTrue("data url", t7.lastPostBody.contains("\"url\":\"data:image/png;base64,"));
		assertEquals(QaReport.Result.FAIL, r7.getResult());
		assertEquals(QaReport.Result.FAIL, r7.checksOf(QaReport.KIND_PERSON_COUNT).get(0).getResult());
		assertEquals(QaReport.Result.FAIL, r7.checksOf(QaReport.KIND_ACTOR_PRESENT).get(1).getResult());
		assertEquals("absent actor gets no identity/outfit noise", 1, r7.checksOf(QaReport.KIND_IDENTITY).size());
		assertEquals(QaReport.Result.UNAVAILABLE, r7.checksOf(QaReport.KIND_LOCATION).get(0).getResult());
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* live                                                                                              */
	/* ------------------------------------------------------------------------------------------------ */

	/** The matching two-actor page through Ollama native: no FAIL, both referenced actors seen. */
	@Test(timeout = TIMEOUT_MS)
	public void t02_liveTwoActorsMatch() throws Exception {
		RecordingTransport t = new RecordingTransport();
		VlmQaProvider p = live(LLMServiceEnumType.OLLAMA, t);
		RenderPlan plan = twoActorPlan();
		QaReport rep = run("t02", p, plan, t);
		assertNotEquals("live answer, not UNAVAILABLE: " + rep.getNote(), QaReport.Result.UNAVAILABLE, rep.getResult());
		assertNotEquals("matching page must not FAIL: " + rep.toJson(), QaReport.Result.FAIL, rep.getResult());
		QaReport.Check count = rep.checksOf(QaReport.KIND_PERSON_COUNT).get(0);
		assertEquals("2 people counted (" + count.getDetail() + ")", "2", count.getObserved());
		for(QaReport.Check c : rep.checksOf(QaReport.KIND_ACTOR_PRESENT)) {
			assertEquals(c.getExpected() + " present (" + c.getDetail() + ")", QaReport.Result.PASS, c.getResult());
		}
	}

	/** A third referenced actor who is not on the page: PERSON_COUNT and ACTOR_PRESENT FAIL (the P8-6 criterion). */
	@Test(timeout = TIMEOUT_MS)
	public void t03_liveThirdActorAbsent() throws Exception {
		RecordingTransport t = new RecordingTransport();
		VlmQaProvider p = live(LLMServiceEnumType.OLLAMA, t);
		RenderPlan plan = threeActorPlan();
		QaReport rep = run("t03", p, plan, t);
		assertNotEquals("live answer, not UNAVAILABLE: " + rep.getNote(), QaReport.Result.UNAVAILABLE, rep.getResult());
		assertEquals("missing actor is a FAIL: " + rep.toJson(), QaReport.Result.FAIL, rep.getResult());
		QaReport.Check count = rep.checksOf(QaReport.KIND_PERSON_COUNT).get(0);
		assertEquals("3", count.getExpected());
		assertEquals("fewer people than planned (" + count.getObserved() + ")", QaReport.Result.FAIL, count.getResult());
		QaReport.Check touvier = null;
		for(QaReport.Check c : rep.checksOf(QaReport.KIND_ACTOR_PRESENT)) {
			if(TestP51GraphLive.CAST_ORDER[2].equals(c.getExpected())) {
				touvier = c;
			}
		}
		assertNotNull("Touvier presence check", touvier);
		assertEquals("Touvier absent (" + touvier.getDetail() + ")", QaReport.Result.FAIL, touvier.getResult());
	}

	/** The same page through the OpenAI-compatible dialect of the same server (what a LiteLLM route speaks). */
	@Test(timeout = TIMEOUT_MS)
	public void t04_liveOpenAiCompat() throws Exception {
		RecordingTransport t = new RecordingTransport();
		VlmQaProvider p = live(LLMServiceEnumType.OPENAI_COMPAT, t);
		RenderPlan plan = twoActorPlan();
		QaReport rep = run("t04", p, plan, t);
		assertTrue(t.lastPostUrl, t.lastPostUrl.endsWith("/v1/chat/completions"));
		assertNotEquals("live answer, not UNAVAILABLE: " + rep.getNote(), QaReport.Result.UNAVAILABLE, rep.getResult());
		assertNotEquals("matching page must not FAIL: " + rep.toJson(), QaReport.Result.FAIL, rep.getResult());
		assertEquals("2", rep.checksOf(QaReport.KIND_PERSON_COUNT).get(0).getObserved());
	}

	/** 220 e: the production route from the owner's resolved chat config; never throws, UNAVAILABLE when it cannot serve. */
	@Test(timeout = TIMEOUT_MS)
	public void t05_routeForOwnerDefaultConfig() throws Exception {
		VlmQaProvider.Route route = VlmQaProvider.routeFor(owner, null);
		assertNotNull(route);
		assertEquals("model from qualityPresets.json, never the chat config's", QualityPresets.qaModel(), route.getModel());
		logger.info("[p82] t05 owner route: " + route);
		VlmQaProvider p = new VlmQaProvider(owner, null);
		RenderPlan plan = twoActorPlan();
		long t0 = System.currentTimeMillis();
		QaReport rep = p.check(plan, page);
		logger.info("[p82] t05 " + rep + " in " + (System.currentTimeMillis() - t0) + " ms; available=" + p.isAvailable());
		Files.write(new File(OUT, "t05-report.json").toPath(), rep.toJson().getBytes(StandardCharsets.UTF_8));
		if(p.isAvailable()) {
			assertNotEquals(QaReport.Result.UNAVAILABLE, rep.getResult());
		}
		else {
			assertEquals(QaReport.Result.UNAVAILABLE, rep.getResult());
			assertNotNull("reason on the note", rep.getNote());
		}
	}

	/** A route nothing answers on: UNAVAILABLE, no throw. */
	@Test(timeout = TIMEOUT_MS)
	public void t06_unreachableRoute() {
		VlmQaProvider p = new VlmQaProvider(new VlmQaProvider.Route("http://127.0.0.1:9", LLMServiceEnumType.OLLAMA, null, visionModel), null, null);
		long t0 = System.currentTimeMillis();
		QaReport rep = p.check(twoActorPlan(), page);
		logger.info("[p82] t06 " + rep + " in " + (System.currentTimeMillis() - t0) + " ms");
		assertEquals(QaReport.Result.UNAVAILABLE, rep.getResult());
		assertTrue(rep.getNote(), rep.getNote().contains("model list unavailable"));
		assertEquals(0, rep.getChecks().size());
	}

	/* ------------------------------------------------------------------------------------------------ */
	/* helpers                                                                                           */
	/* ------------------------------------------------------------------------------------------------ */

	private VlmQaProvider live(LLMServiceEnumType dialect, RecordingTransport t) {
		VlmQaProvider.Route route = new VlmQaProvider.Route(server, dialect, null, visionModel);
		VlmQaProvider p = new VlmQaProvider(route, t, oid -> portraitByName.get(oid));
		boolean avail = p.isAvailable();
		Assume.assumeTrue("SKIPPED (not a pass): " + visionModel + " is not listed by " + server + " (" + dialect + ")", avail);
		return p;
	}

	private QaReport run(String tag, VlmQaProvider p, RenderPlan plan, RecordingTransport t) throws Exception {
		VlmQaProvider.Prompt prompt = p.buildPrompt(plan);
		Files.write(new File(OUT, tag + "-prompt.txt").toPath(), prompt.text.getBytes(StandardCharsets.UTF_8));
		long t0 = System.currentTimeMillis();
		QaReport rep = p.check(plan, page);
		long ms = System.currentTimeMillis() - t0;
		logger.info("[p82] " + tag + " " + p.getRoute() + " -> " + rep + " in " + ms + " ms (" + prompt.referenceImages.size() + " reference images)");
		if(t.lastResponse != null) {
			Files.write(new File(OUT, tag + "-response.json").toPath(), t.lastResponse.getBytes(StandardCharsets.UTF_8));
			logger.info("[p82] " + tag + " raw answer: " + VlmQaProvider.abbreviate(t.lastResponse, 1200));
		}
		Files.write(new File(OUT, tag + "-report.json").toPath(), rep.toJson().getBytes(StandardCharsets.UTF_8));
		for(QaReport.Check c : rep.getChecks()) {
			logger.info("[p82] " + tag + "   " + c.getKind() + " " + c.getResult() + " expected=" + c.getExpected() + " observed=" + c.getObserved() + (c.getDetail() != null ? " - " + c.getDetail() : ""));
		}
		return rep;
	}

	private RenderPlan twoActorPlan() {
		return RenderPlan.builder()
			.purpose("scene")
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 20, 1, false, QualityPresets.QA_CANONICAL))
			.location(new RenderPlan.Location("loc-" + TestP51GraphLive.shortId(), SETTING))
			.camera(new RenderPlan.Camera(CAMERA))
			.actor(actor(TestP51GraphLive.CAST_ORDER[0], new RenderPlan.Box(0.05, 0.10, 0.45, 0.85)))
			.actor(actor(TestP51GraphLive.CAST_ORDER[1], new RenderPlan.Box(0.50, 0.05, 0.45, 0.90)))
			.build();
	}

	private RenderPlan threeActorPlan() {
		return RenderPlan.builder()
			.purpose("scene")
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("STANDARD", 20, 1, false, QualityPresets.QA_CANONICAL))
			.location(new RenderPlan.Location("loc-" + TestP51GraphLive.shortId(), SETTING))
			.camera(new RenderPlan.Camera(CAMERA))
			.actor(actor(TestP51GraphLive.CAST_ORDER[0], new RenderPlan.Box(0.02, 0.10, 0.32, 0.85)))
			.actor(actor(TestP51GraphLive.CAST_ORDER[1], new RenderPlan.Box(0.34, 0.05, 0.32, 0.90)))
			.actor(actor(TestP51GraphLive.CAST_ORDER[2], new RenderPlan.Box(0.66, 0.10, 0.32, 0.85)))
			.build();
	}

	/// A referenced foreground actor from book 58: identity text + portrait (resolver key = the cast name).
	private RenderPlan.Actor actor(String name, RenderPlan.Box box) {
		return new RenderPlan.Actor.Builder("actor-" + TestP51GraphLive.shortId())
			.characterId("char-" + TestP51GraphLive.shortId())
			.name(name)
			.role(RenderPlan.ROLE_FOREGROUND)
			.box(box)
			.outfit(new RenderPlan.Outfit(null, outfitByName.get(name), null))
			.identity(new RenderPlan.Identity(name, null, physicalByName.get(name)))
			.referenced(true)
			.referenceStage(RenderPlan.STAGE_BASE)
			.build();
	}

	private void readCast() throws Exception {
		BaseRecord book = PbBookUtil.findBookBySlug(owner, TestP51GraphLive.CUSTOM_BOOK_SLUG, orgId);
		assertNotNull("book " + TestP51GraphLive.CUSTOM_BOOK_SLUG + " as " + TestP51GraphLive.CUSTOM_OWNER, book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		List<Map<String, Object>> chars = PictureBookUtil.listCharacters(owner, bookOid);
		assertTrue("book 58 has characters", chars != null && !chars.isEmpty());
		for(String name : TestP51GraphLive.CAST_ORDER) {
			String oid = null;
			for(Map<String, Object> c : chars) {
				if(name.equals(c.get("name"))) {
					oid = (String) c.get("objectId");
					break;
				}
			}
			assertNotNull("book 58 character " + name, oid);
			BaseRecord person = TestP51GraphLive.readPerson(owner, oid, orgId);
			BaseRecord profile = person.get(FieldNames.FIELD_PROFILE);
			assertNotNull(name + " profile", profile);
			BaseRecord portrait = profile.get("portrait");
			assertNotNull(name + " portrait", portrait);
			String portraitOid = portrait.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord data = TestP51GraphLive.freshReadData(owner, portraitOid, orgId);
			if(data == null && olioUser != null) {
				data = TestP51GraphLive.freshReadData(olioUser, portraitOid, orgId);
			}
			assertNotNull(name + " portrait data", data);
			byte[] png = ByteModelUtil.getValue(data);
			assertTrue(name + " portrait bytes", png != null && png.length > 1024);
			portraitByName.put(name, png);
			/// the inputs the model sees, saved for visual inspection next to the outputs
			Files.write(new File(OUT, "portrait-" + name.replaceAll("[^A-Za-z0-9]+", "_") + ".png").toPath(), png);
			BaseRecord narrative = person.get(OlioFieldNames.FIELD_NARRATIVE);
			String physical = (narrative != null ? TestP51GraphLive.text(narrative.get("physicalDescription"), "") : "");
			String outfit = (narrative != null ? TestP51GraphLive.text(narrative.get("outfitDescription"), "") : "");
			physicalByName.put(name, physical.isEmpty() ? name + ", as drawn in the portrait" : physical);
			outfitByName.put(name, outfit);
			logger.info("[p82] cast " + name + " portrait " + png.length + " bytes physical=" + physical.length() + " chars outfit=" + outfit.length() + " chars");
		}
	}

	private static String ollamaEnvelope(String content, String doneReason) {
		Map<String, Object> msg = new LinkedHashMap<>();
		msg.put("role", "assistant");
		msg.put("content", content);
		Map<String, Object> env = new LinkedHashMap<>();
		env.put("model", "x");
		env.put("message", msg);
		env.put("done", Boolean.TRUE);
		env.put("done_reason", doneReason);
		env.put("eval_count", Integer.valueOf(content.isEmpty() ? 1024 : 120));
		return JSONUtil.exportObject(env, null, true, false);
	}

	private static int countOf(String s, String sub) {
		int n = 0;
		for(int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) {
			n++;
		}
		return n;
	}

	/// Offline replay: counts calls, returns the canned bodies.
	static final class CannedTransport implements VlmQaProvider.Transport {
		String tags;
		String answer;
		int gets;
		int posts;
		String lastGetUrl;
		String lastPostUrl;
		String lastPostBody;
		Map<String, String> lastPostHeaders = new HashMap<>();

		@Override
		public String post(String url, Map<String, String> headers, String json) {
			posts++;
			lastPostUrl = url;
			lastPostBody = json;
			lastPostHeaders = (headers != null ? headers : new HashMap<>());
			return answer;
		}

		@Override
		public String get(String url, Map<String, String> headers) {
			gets++;
			lastGetUrl = url;
			return tags;
		}
	}

	/// Live, through the production ClientTransport, keeping the raw answer for the saved evidence.
	static final class RecordingTransport implements VlmQaProvider.Transport {
		private final VlmQaProvider.Transport inner = new VlmQaProvider.ClientTransport();
		String lastPostUrl;
		String lastResponse;

		@Override
		public String post(String url, Map<String, String> headers, String json) {
			lastPostUrl = url;
			lastResponse = inner.post(url, headers, json);
			return lastResponse;
		}

		@Override
		public String get(String url, Map<String, String> headers) {
			return inner.get(url, headers);
		}
	}

	/// Compile-time check that the provider is usable through the interface the executor will hold.
	@SuppressWarnings("unused")
	private static QaProvider asProvider(VlmQaProvider p) {
		return p;
	}

	@SuppressWarnings("unused")
	private static void unused(List<String> l, ArrayList<String> a) {
		assertNull(null);
	}
}
