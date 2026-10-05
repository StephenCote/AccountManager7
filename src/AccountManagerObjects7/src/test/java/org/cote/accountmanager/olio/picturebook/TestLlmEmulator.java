package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.LlmEmulator;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * EMULATOR dialect acceptance. DETERMINISTIC: no GPU, no live LLM, no network. Talks to the test
 * database only (a persisted {@code system.connection} is required because {@code Chat.configureChat}
 * re-queries the connection by FK id and resolves the dialect from THAT row).
 *
 * <p>Lives in {@code org.cote.accountmanager.olio.picturebook} on purpose: the synthesizer's output is
 * fed through the REAL package-private PictureBook parsers ({@code parseLlmJsonObject},
 * {@code parseLlmJsonArray}) rather than a test-side copy, so a drift between what the emulator emits
 * and what production accepts fails here, not in a Playwright run.</p>
 *
 * <p>Every dispatching case drives {@code Chat.chat(req)} in buffer mode — the exact call
 * {@code PictureBookUtil.callLlmInternal} makes — against a persisted EMULATOR connection whose
 * {@code serverUrl} names a set under a throw-away fixture root. The root is configured via
 * {@code LlmEmulator.configure(...)} and cleared in {@code @After}, because the emulator config is
 * process-global and surefire runs the Objects7 suite in one JVM.</p>
 */
public class TestLlmEmulator extends BaseTest {

	private static final String EXTRACT_CHUNK = "pictureBook.extract-chunk";
	private static final String EXTRACT_SCENES = "pictureBook.extract-scenes";
	private static final String REDUCE_CHARACTER = "pictureBook.reduce-character";
	private static final String MODEL = "qwen3:8b";
	private static final int REQUEST_TIMEOUT_SEC = 20;

	/// A chunk whose first non-blank line is distinctive: the synthesizer's FIRST scene title must
	/// begin with it. This is the property the chapter-parity Playwright test keys on.
	private static final String CHUNK_FIRST_LINE = "Chapter 7: The Lantern Under The Stairs";
	private static final String CHUNK = CHUNK_FIRST_LINE + "\n\n"
		+ "Darby Wren carried the lantern down the cellar steps while Old Marsh waited at the top, one hand on the rail. "
		+ "The flame guttered when the draft found it, and the shadows of the shelves leaned in across the packed-earth floor. "
		+ "Darby set the lantern on the barrel, crouched, and pulled the loose board aside; underneath lay a tin box wrapped in oilcloth. "
		+ "Marsh called down that the rain had started again. Darby did not answer. She lifted the box, and the lid came away in her hands.";

	private Path tmpRoot;

	@Before
	public void setUpEmulator() throws Exception {
		tmpRoot = Files.createTempDirectory("am7-llm-emulator-test-");
		LlmEmulator.configure(tmpRoot.toString(), null);
		LlmEmulator.resetCounters();
		Chat.clearLastCallError();
	}

	@After
	public void tearDownEmulator() throws Exception {
		/// Leave the process exactly as we found it: unconfigured, counters zeroed.
		LlmEmulator.configure(null, null);
		LlmEmulator.resetCounters();
		if (tmpRoot != null) {
			deleteTree(tmpRoot);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Fixture-root helpers
	// ------------------------------------------------------------------------------------------------

	private String newSet(String manifestJson) throws Exception {
		String set = "emu-" + UUID.randomUUID().toString().substring(0, 8);
		Path dir = tmpRoot.resolve(set);
		Files.createDirectories(dir);
		if (manifestJson != null) {
			Files.write(dir.resolve(LlmEmulator.MANIFEST_FILE), manifestJson.getBytes(StandardCharsets.UTF_8));
		}
		return set;
	}

	/// Minimal JSON string quoting for hand-built fixture files (the emulator's own quoter is package-private).
	private static String jsonQuote(String s) {
		StringBuilder b = new StringBuilder("\"");
		for (char c : s.toCharArray()) {
			switch (c) {
				case '"': b.append("\\\""); break;
				case '\\': b.append("\\\\"); break;
				case '\n': b.append("\\n"); break;
				case '\r': b.append("\\r"); break;
				case '\t': b.append("\\t"); break;
				default:
					if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
					else b.append(c);
			}
		}
		return b.append('"').toString();
	}

	private static void deleteTree(Path p) throws Exception {
		if (p == null || !Files.exists(p)) return;
		try (java.util.stream.Stream<Path> s = Files.walk(p)) {
			s.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
				try { Files.deleteIfExists(q); } catch (Exception ignore) { /* best effort */ }
			});
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Prompt construction — the REAL classpath templates, substituted the way callLlmInternal does
	// ------------------------------------------------------------------------------------------------

	private static String template(String promptName, String field) {
		String t = PromptResourceUtil.getString(promptName, field);
		assertNotNull("classpath prompt resource " + promptName + "." + field + " is missing", t);
		return t;
	}

	private static String extractChunkUser(String chunk) {
		String u = template(EXTRACT_CHUNK, "user");
		u = u.replace("{previousScenes}", "[]");
		u = u.replace("{knownCharacters}", "(none yet)");
		u = u.replace("{maxNew}", "4");
		u = u.replace("{chunk}", chunk);
		return u + "\n/no_think";
	}

	private static String extractScenesUser(String text, int count) {
		String u = template(EXTRACT_SCENES, "user");
		u = u.replace("{count}", String.valueOf(count));
		u = u.replace("{text}", text);
		return u + "\n/no_think";
	}

	private static String reduceCharacterUser(String name, String passages) {
		String u = template(REDUCE_CHARACTER, "user");
		u = u.replace("{name}", name);
		u = u.replace("{raceOptions}", "Asian, Black, White, Hispanic, Middle Eastern, Indigenous, Mixed");
		u = u.replace("{ethnicityOptions}", "Irish, Italian, Japanese, Nigerian, Mexican");
		u = u.replace("{passages}", passages);
		return u + "\n/no_think";
	}

	private static OpenAIRequest bareRequest(String model, String system, String user) {
		OpenAIRequest req = new OpenAIRequest();
		req.setModel(model);
		if (system != null) {
			OpenAIMessage s = new OpenAIMessage();
			s.setRole("system");
			s.setContent(system);
			req.addMessage(s);
		}
		OpenAIMessage u = new OpenAIMessage();
		u.setRole("user");
		u.setContent(user);
		req.addMessage(u);
		return req;
	}

	// ------------------------------------------------------------------------------------------------
	// Persistence helpers — modeled on TestUpstreamWireEmission.persistConnection / chatConfig
	// ------------------------------------------------------------------------------------------------

	private BaseRecord persistConnection(BaseRecord user, String name, String serverUrl, ConnectionDialectEnumType dialect) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord c = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
		assertNotNull("connection factory newInstance returned null", c);
		c.set("serverUrl", serverUrl);
		c.set("requestTimeout", REQUEST_TIMEOUT_SEC);
		c.set(FieldNames.FIELD_DIALECT, dialect);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, c);
		assertNotNull("AccessPoint.create returned null for system.connection '" + name + "'", created);

		long connId = created.get(FieldNames.FIELD_ID);
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, connId);
		cq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, "serverUrl", "requestTimeout", "apiKey",
			FieldNames.FIELD_DIALECT, FieldNames.FIELD_UPSTREAM });
		cq.setCache(false);
		BaseRecord back = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
		assertNotNull("persisted connection id=" + connId + " could not be read back", back);
		assertEquals("system.connection.dialect did not persist", dialect, back.getEnum(FieldNames.FIELD_DIALECT));
		return back;
	}

	/// In-memory chatConfig (Chat reads only its fields) bound to the given connection. serviceType is
	/// left at its schema default so the connection's dialect is the only thing selecting the transport.
	private BaseRecord chatConfigFor(BaseRecord user, String name, BaseRecord conn) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		cfg.set("model", MODEL);
		cfg.set("stream", false);
		cfg.set("connection", conn);
		return cfg;
	}

	private BaseRecord emulatorConfig(BaseRecord user, String set) throws Exception {
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord conn = persistConnection(user, "Emu Conn " + nonce, LlmEmulator.SCHEME + set, ConnectionDialectEnumType.EMULATOR);
		return chatConfigFor(user, "Emu Cfg " + nonce, conn);
	}

	/// The exact call shape PictureBookUtil.callLlmInternal makes: system prompt on the Chat, one user
	/// turn, buffer mode. Returns the stripped content or null.
	private String dispatch(BaseRecord user, BaseRecord cfg, String system, String userPrompt, OpenAIRequest[] reqOut) {
		Chat chat = new Chat(user, cfg, null);
		assertEquals("Chat resolved the wrong wire dialect", LLMServiceEnumType.EMULATOR, chat.getServiceType());
		chat.setLlmSystemPrompt(system);
		OpenAIRequest req = chat.newRequest(chat.getModel());
		req.setStream(false);
		chat.newMessage(req, userPrompt);
		if (reqOut != null && reqOut.length > 0) reqOut[0] = req;
		Chat.clearLastCallError();
		OpenAIResponse resp = chat.chat(req);
		if (resp == null || resp.getMessage() == null) return null;
		return PictureBookUtil.stripThink(resp.getMessage().getContent());
	}

	// ------------------------------------------------------------------------------------------------
	// 1. Key stability
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testRequestKeyIgnoresEverythingButModelAndMessages() throws Exception {
		String sys = template(EXTRACT_CHUNK, "system");
		String usr = extractChunkUser(CHUNK);
		OpenAIRequest a = bareRequest(MODEL, sys, usr);
		OpenAIRequest b = bareRequest(MODEL, sys, usr);
		b.setStream(true);
		b.set("temperature", 0.9);
		b.set("max_tokens", 4096);
		b.set("user", "trace-nonce-" + UUID.randomUUID());
		assertEquals("stream/options must not participate in the key", LlmEmulator.requestKey(a), LlmEmulator.requestKey(b));
		assertEquals(64, LlmEmulator.requestKey(a).length());

		OpenAIRequest c = bareRequest(MODEL, sys, usr + " ");
		assertNotEquals("a one-character content change must change the key", LlmEmulator.requestKey(a), LlmEmulator.requestKey(c));
		OpenAIRequest d = bareRequest("other-model", sys, usr);
		assertNotEquals("the model participates in the key", LlmEmulator.requestKey(a), LlmEmulator.requestKey(d));
	}

	/// The property that lets a recording made against a real OPENAI_COMPAT server replay under the
	/// emulator: the same chatConfig content bound to two different dialects builds requests with the
	/// same key. Uses Chat.newRequest for both so applyChatOptions' per-dialect differences are in play.
	@Test
	public void testRequestKeyStableAcrossOpenAiCompatAndEmulatorDialects() throws Exception {
		BaseRecord user = getCreateUser("emuKeyUser");
		assertNotNull(user);
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		String set = newSet(null);

		BaseRecord compatConn = persistConnection(user, "Emu Key Compat " + nonce, "http://127.0.0.1:1", ConnectionDialectEnumType.OPENAI_COMPAT);
		BaseRecord emuConn = persistConnection(user, "Emu Key Emu " + nonce, LlmEmulator.SCHEME + set, ConnectionDialectEnumType.EMULATOR);
		BaseRecord compatCfg = chatConfigFor(user, "Emu Key Compat Cfg " + nonce, compatConn);
		BaseRecord emuCfg = chatConfigFor(user, "Emu Key Emu Cfg " + nonce, emuConn);

		String sys = template(EXTRACT_CHUNK, "system");
		String usr = extractChunkUser(CHUNK);

		Chat compat = new Chat(user, compatCfg, null);
		assertEquals(LLMServiceEnumType.OPENAI_COMPAT, compat.getServiceType());
		compat.setLlmSystemPrompt(sys);
		OpenAIRequest r1 = compat.newRequest(compat.getModel());
		r1.setStream(false);
		compat.newMessage(r1, usr);

		Chat emu = new Chat(user, emuCfg, null);
		assertEquals(LLMServiceEnumType.EMULATOR, emu.getServiceType());
		emu.setLlmSystemPrompt(sys);
		OpenAIRequest r2 = emu.newRequest(emu.getModel());
		r2.setStream(false);
		emu.newMessage(r2, usr);

		assertEquals("the same prompt bound to OPENAI_COMPAT and EMULATOR must key identically, or a"
			+ " recording made against the real server can never replay", LlmEmulator.requestKey(r1), LlmEmulator.requestKey(r2));
		/// Nothing was dispatched.
		assertEquals(0L, (long) LlmEmulator.stats().get("hit") + LlmEmulator.stats().get("miss") + LlmEmulator.stats().get("synth"));
	}

	// ------------------------------------------------------------------------------------------------
	// 2. Set-name validation
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testParseSetNameRejectsPathTricks() {
		assertEquals("harlots-eight", LlmEmulator.parseSetName("emulator://harlots-eight"));
		assertEquals("harlots-eight", LlmEmulator.parseSetName("emulator://harlots-eight/"));
		assertEquals("harlots-eight-faults", LlmEmulator.parseSetName("  EMULATOR://harlots-eight-faults  "));
		assertNull("parent traversal must be rejected", LlmEmulator.parseSetName("emulator://../x"));
		assertNull("nested paths must be rejected", LlmEmulator.parseSetName("emulator://a/b"));
		assertNull("empty set must be rejected", LlmEmulator.parseSetName("emulator://"));
		assertNull("a dot-only set must be rejected", LlmEmulator.parseSetName("emulator://.."));
		assertNull("backslash traversal must be rejected", LlmEmulator.parseSetName("emulator://..\\x"));
		assertNull("a real URL is not an emulator set", LlmEmulator.parseSetName("http://192.168.1.42:11434"));
		assertNull(LlmEmulator.parseSetName(null));
	}

	// ------------------------------------------------------------------------------------------------
	// 3. Synthesizer output goes through the PRODUCTION parsers
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testSynthesizedExtractChunkParsesAndTitlesFirstSceneFromFirstLine() {
		OpenAIRequest req = bareRequest(MODEL, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK));
		assertEquals(LlmEmulator.KIND_EXTRACT_CHUNK, LlmEmulator.detectKind(req));
		String out = LlmEmulator.synthesize(LlmEmulator.KIND_EXTRACT_CHUNK, req, LlmEmulator.requestKey(req));

		List<String> failed = new ArrayList<>();
		boolean[] ok = new boolean[1];
		Map<String, Object> parsed = PictureBookUtil.parseLlmJsonObject(out, "test:extract-chunk", failed, ok);
		assertTrue("production parseLlmJsonObject rejected the synthesized extract-chunk output: " + failed, ok[0]);
		assertTrue(failed.isEmpty());
		Object additions = parsed.get("additions");
		assertTrue("additions must be a list", additions instanceof List);
		List<?> adds = (List<?>) additions;
		assertTrue("expected at least 2 synthesized scenes, got " + adds.size(), adds.size() >= 2);
		assertTrue(parsed.get("revisions") instanceof List);
		assertTrue(parsed.get("removals") instanceof List);

		Map<?, ?> first = (Map<?, ?>) adds.get(0);
		String title = String.valueOf(first.get("title"));
		assertTrue("first scene title '" + title + "' must begin with the chunk's first line '" + CHUNK_FIRST_LINE + "'",
			title.startsWith(CHUNK_FIRST_LINE));
		for (Object o : adds) {
			Map<?, ?> s = (Map<?, ?>) o;
			for (String f : new String[] { "title", "blurb", "setting", "action", "mood", "characters", "diffusionPrompt" }) {
				assertNotNull("scene is missing '" + f + "'", s.get(f));
			}
			assertTrue(s.get("characters") instanceof List);
			assertFalse(((List<?>) s.get("characters")).isEmpty());
		}
	}

	@Test
	public void testSynthesizedExtractScenesParsesAsArray() {
		OpenAIRequest req = bareRequest(MODEL, template(EXTRACT_SCENES, "system"), extractScenesUser(CHUNK, 3));
		assertEquals(LlmEmulator.KIND_EXTRACT_SCENES, LlmEmulator.detectKind(req));
		String out = LlmEmulator.synthesize(LlmEmulator.KIND_EXTRACT_SCENES, req, LlmEmulator.requestKey(req));

		List<String> failed = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.parseLlmJsonArray(out, "test:extract-scenes", failed);
		assertTrue("production parseLlmJsonArray rejected the synthesized extract-scenes output: " + failed, failed.isEmpty());
		assertTrue("expected 2..3 scenes, got " + scenes.size(), scenes.size() >= 2 && scenes.size() <= 3);
		assertEquals(0, ((Number) scenes.get(0).get("index")).intValue());
		assertTrue(String.valueOf(scenes.get(0).get("title")).startsWith(CHUNK_FIRST_LINE));
		for (int i = 0; i < scenes.size(); i++) {
			assertEquals(i, ((Number) scenes.get(i).get("index")).intValue());
		}
	}

	@Test
	public void testSynthesizedReduceCharacterParsesWithNameAndListedRace() {
		String passages = "Darby Wren, a slender woman with dark brown hair, wore a wool coat.\n---\nDarby lifted the lantern.";
		OpenAIRequest req = bareRequest(MODEL, template(REDUCE_CHARACTER, "system"), reduceCharacterUser("Darby Wren", passages));
		assertEquals(LlmEmulator.KIND_REDUCE_CHARACTER, LlmEmulator.detectKind(req));
		String out = LlmEmulator.synthesize(LlmEmulator.KIND_REDUCE_CHARACTER, req, LlmEmulator.requestKey(req));

		List<String> failed = new ArrayList<>();
		boolean[] ok = new boolean[1];
		Map<String, Object> parsed = PictureBookUtil.parseLlmJsonObject(out, "test:reduce-character", failed, ok);
		assertTrue("production parseLlmJsonObject rejected the synthesized reduce-character output: " + failed, ok[0]);
		assertEquals("Darby Wren", parsed.get("name"));
		String race = String.valueOf(parsed.get("race"));
		List<String> allowed = List.of("Asian", "Black", "White", "Hispanic", "Middle Eastern", "Indigenous", "Mixed");
		assertTrue("race '" + race + "' must be copied from the prompt's own option list", allowed.contains(race));
		assertTrue(parsed.get("physical") instanceof Map);
		assertNotNull(parsed.get("description"));
		assertNotNull(parsed.get("gender"));
		assertTrue(parsed.get("skills") instanceof List);
	}

	// ------------------------------------------------------------------------------------------------
	// 4. End-to-end through Chat.chatInternal (buffer path) against a persisted EMULATOR connection
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testChatDispatchSynthesizesThroughBufferPath() throws Exception {
		BaseRecord user = getCreateUser("emuChatUser");
		String set = newSet(null); /// no manifest → non-strict, no faults
		BaseRecord cfg = emulatorConfig(user, set);

		String out = dispatch(user, cfg, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK), null);
		assertNotNull("chat() returned null; lastCallError=" + Chat.getLastCallError(), out);
		assertFalse(Chat.isLastCallUnreachable());

		boolean[] ok = new boolean[1];
		List<String> failed = new ArrayList<>();
		Map<String, Object> parsed = PictureBookUtil.parseLlmJsonObject(out, "test:chat", failed, ok);
		assertTrue("content returned through Chat did not parse: " + failed + " content=" + out, ok[0]);
		List<?> adds = (List<?>) parsed.get("additions");
		assertTrue(adds.size() >= 2);
		assertTrue(String.valueOf(((Map<?, ?>) adds.get(0)).get("title")).startsWith(CHUNK_FIRST_LINE));

		Map<String, Long> st = LlmEmulator.stats();
		assertEquals("exactly one synthesized answer expected", 1L, (long) st.get("synth"));
		assertEquals(0L, (long) st.get("hit"));
		assertEquals(0L, (long) st.get("miss"));
		assertEquals(0L, (long) st.get("fault"));
	}

	/// A recorded fixture at <set>/<sha256>.json must win over synthesis, keyed on the request Chat
	/// actually sends. This is the replay path the recorder exists for.
	@Test
	public void testExactFixtureHitBeatsSynthesis() throws Exception {
		BaseRecord user = getCreateUser("emuChatUser");
		String set = newSet(null);
		BaseRecord cfg = emulatorConfig(user, set);

		String sys = template(EXTRACT_SCENES, "system");
		String usr = extractScenesUser(CHUNK, 2);
		/// Compute the key the same way the recorder does, from a request built by the same Chat.
		Chat probe = new Chat(user, cfg, null);
		probe.setLlmSystemPrompt(sys);
		OpenAIRequest probeReq = probe.newRequest(probe.getModel());
		probeReq.setStream(false);
		probe.newMessage(probeReq, usr);
		String key = LlmEmulator.requestKey(probeReq);

		String fixtureContent = "[{\"index\":0,\"title\":\"FIXTURE HIT\",\"blurb\":\"b\",\"setting\":\"s\",\"action\":\"a\",\"mood\":\"m\",\"characters\":[{\"name\":\"X\",\"role\":\"r\"}],\"diffusionPrompt\":\"d\"}]";
		String fixture = "{\"key\":\"" + key + "\",\"kind\":\"extract-scenes\",\"model\":\"" + MODEL + "\",\"response\":{\"content\":"
			+ jsonQuote(fixtureContent) + "}}";
		Files.write(tmpRoot.resolve(set).resolve(key + ".json"), fixture.getBytes(StandardCharsets.UTF_8));

		String out = dispatch(user, cfg, sys, usr, null);
		assertNotNull("chat() returned null; lastCallError=" + Chat.getLastCallError(), out);
		List<Map<String, Object>> scenes = PictureBookUtil.parseLlmJsonArray(out, "test:hit", new ArrayList<>());
		assertEquals(1, scenes.size());
		assertEquals("FIXTURE HIT", scenes.get(0).get("title"));

		Map<String, Long> st = LlmEmulator.stats();
		assertEquals("the fixture must be served as a HIT", 1L, (long) st.get("hit"));
		assertEquals("no synthesis when a fixture matches", 0L, (long) st.get("synth"));
	}

	@Test
	public void testStrictMissReturnsNullAndIsNotUnreachable() throws Exception {
		BaseRecord user = getCreateUser("emuChatUser");
		String set = newSet("{\"strict\": true, \"kinds\": {}, \"faults\": []}");
		BaseRecord cfg = emulatorConfig(user, set);

		String out = dispatch(user, cfg, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK), null);
		assertNull("a strict-mode miss must surface as a null completion", out);
		String err = Chat.getLastCallError();
		assertNotNull("the miss must be reported through Chat.getLastCallError()", err);
		assertTrue("unexpected error text: " + err, err.contains("fixture miss (strict)"));
		assertTrue("the error must name the set so a Playwright author can find it: " + err, err.contains("set=" + set));
		assertFalse("a fixture miss is NOT a connectivity failure — the chunk loop must not treat it as server-down",
			Chat.isLastCallUnreachable());
		assertEquals(1L, (long) LlmEmulator.stats().get("miss"));
		assertEquals(0L, (long) LlmEmulator.stats().get("synth"));
	}

	@Test
	public void testUnreachableFaultSetsLastCallUnreachable() throws Exception {
		BaseRecord user = getCreateUser("emuChatUser");
		String set = newSet("{\"strict\": false, \"faults\": [{\"kind\": \"extract-chunk\", \"occurrence\": 1, \"mode\": \"unreachable\"}]}");
		BaseRecord cfg = emulatorConfig(user, set);

		String out = dispatch(user, cfg, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK), null);
		assertNull(out);
		assertTrue("an 'unreachable' fault must look like a ConnectException to the consumer; lastCallError="
			+ Chat.getLastCallError(), Chat.isLastCallUnreachable());
		assertEquals(1L, (long) LlmEmulator.stats().get("fault"));

		/// occurrence:1 fires exactly once — the second call of the same kind must go through.
		String again = dispatch(user, cfg, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK), null);
		assertNotNull("the fault must fire only on its configured occurrence; lastCallError=" + Chat.getLastCallError(), again);
		assertFalse(Chat.isLastCallUnreachable());
		assertEquals(1L, (long) LlmEmulator.stats().get("fault"));
		assertEquals(1L, (long) LlmEmulator.stats().get("synth"));
	}

	@Test
	public void testHttp500FaultReturnsNullThenRetrySucceeds() throws Exception {
		BaseRecord user = getCreateUser("emuChatUser");
		String set = newSet("{\"faults\": [{\"kind\": \"extract-chunk\", \"occurrence\": 2, \"mode\": \"http500\"}]}");
		BaseRecord cfg = emulatorConfig(user, set);
		String sys = template(EXTRACT_CHUNK, "system");
		String usr = extractChunkUser(CHUNK);

		assertNotNull("occurrence 1 must succeed", dispatch(user, cfg, sys, usr, null));
		String second = dispatch(user, cfg, sys, usr, null);
		assertNull("occurrence 2 must be the emulated HTTP 500", second);
		String err = Chat.getLastCallError();
		assertNotNull(err);
		assertTrue("the provider error text must reach the caller: " + err, err.contains("emulated failure"));
		assertFalse("an HTTP 500 is not a connectivity failure", Chat.isLastCallUnreachable());
		assertNotNull("occurrence 3 (the retry) must succeed", dispatch(user, cfg, sys, usr, null));

		Map<String, Long> st = LlmEmulator.stats();
		assertEquals(1L, (long) st.get("fault"));
		assertEquals(2L, (long) st.get("synth"));

		/// resetCounters() must also clear the per-set fault occurrence counters, or a second run in the
		/// same JVM (the Tomcat case) can never see the fault again.
		LlmEmulator.resetCounters();
		assertNotNull(dispatch(user, cfg, sys, usr, null));
		assertNull("after resetCounters() the occurrence-2 fault must fire again", dispatch(user, cfg, sys, usr, null));
		assertEquals(1L, (long) LlmEmulator.stats().get("fault"));
	}

	@Test
	public void testEmptyFaultYieldsEmptyArrayContent() throws Exception {
		BaseRecord user = getCreateUser("emuChatUser");
		String set = newSet("{\"faults\": [{\"kind\": \"extract-scenes\", \"occurrence\": 1, \"mode\": \"empty\"}]}");
		BaseRecord cfg = emulatorConfig(user, set);

		String out = dispatch(user, cfg, template(EXTRACT_SCENES, "system"), extractScenesUser(CHUNK, 3), null);
		assertEquals("[]", out == null ? null : out.trim());
		assertTrue(PictureBookUtil.parseLlmJsonArray(out, "test:empty", new ArrayList<>()).isEmpty());
		assertEquals(1L, (long) LlmEmulator.stats().get("fault"));
	}

	@Test
	public void testUnconfiguredEmulatorFailsFastWithExactMessage() throws Exception {
		BaseRecord user = getCreateUser("emuChatUser");
		String set = newSet(null);
		BaseRecord cfg = emulatorConfig(user, set);

		LlmEmulator.configure(null, null);
		assertFalse(LlmEmulator.isConfigured());

		String out = dispatch(user, cfg, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK), null);
		assertNull(out);
		assertEquals("LLM emulator not configured on this deployment", Chat.getLastCallError());
		assertFalse("a configuration miss must not read as server-down", Chat.isLastCallUnreachable());
		Map<String, Long> st = LlmEmulator.stats();
		assertEquals("nothing may be counted when the emulator never ran", 0L,
			(long) st.get("hit") + st.get("miss") + st.get("synth") + st.get("fault"));
	}

	@Test
	public void testInvalidSetInServerUrlIsAMissNotAnEscape() throws Exception {
		BaseRecord user = getCreateUser("emuChatUser");
		String nonce = UUID.randomUUID().toString().substring(0, 8);
		BaseRecord conn = persistConnection(user, "Emu Bad Conn " + nonce, "emulator://../" + nonce, ConnectionDialectEnumType.EMULATOR);
		BaseRecord cfg = chatConfigFor(user, "Emu Bad Cfg " + nonce, conn);

		String out = dispatch(user, cfg, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK), null);
		assertNull(out);
		String err = Chat.getLastCallError();
		assertNotNull(err);
		assertTrue("unexpected error: " + err, err.contains("invalid emulator set"));
		assertEquals(1L, (long) LlmEmulator.stats().get("miss"));
		assertFalse(Chat.isLastCallUnreachable());
	}

	// ------------------------------------------------------------------------------------------------
	// 5. Recorder writes a replayable fixture and nothing sensitive
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testRecorderWritesReplayableFixtureWithoutSecrets() throws Exception {
		Path rec = Files.createTempDirectory("am7-llm-emulator-rec-");
		try {
			LlmEmulator.configure(tmpRoot.toString(), rec.toString());
			assertEquals(rec.toAbsolutePath().normalize(), LlmEmulator.recordDir());

			OpenAIRequest req = bareRequest(MODEL, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK));
			/// A real, non-message request field. The recorder must persist model + messages only.
			req.set("user", "sk-SECRET-SHOULD-NOT-APPEAR");
			req.set("session_id", "sess-SECRET-SHOULD-NOT-APPEAR");
			String content = "{\"additions\":[],\"revisions\":[],\"removals\":[]}";
			assertTrue(LlmEmulator.record(req, content));
			assertEquals(1L, (long) LlmEmulator.stats().get("recorded"));

			String key = LlmEmulator.requestKey(req);
			Path f = rec.resolve("qwen3_8b").resolve(key + ".json");
			if (!Files.isRegularFile(f)) {
				/// Model-name sanitization is an implementation detail; locate the file by key instead.
				try (java.util.stream.Stream<Path> s = Files.walk(rec)) {
					f = s.filter(p -> p.getFileName().toString().equals(key + ".json")).findFirst().orElse(null);
				}
			}
			assertNotNull("recorder did not write <recordDir>/<model>/<key>.json", f);
			String json = Files.readString(f, StandardCharsets.UTF_8);
			assertTrue(json.contains("\"key\" : \"" + key + "\"") || json.contains("\"key\":\"" + key + "\""));
			assertTrue(json.contains("extract-chunk"));
			assertTrue(json.contains(CHUNK_FIRST_LINE));
			assertFalse("the recorder must never persist non-message request fields", json.contains("sk-SECRET"));
			assertFalse(json.toLowerCase().contains("authorization"));

			/// Round trip: drop the recording into a set and it must be served as a HIT with identical content.
			String set = newSet(null);
			Files.copy(f, tmpRoot.resolve(set).resolve(key + ".json"));
			LlmEmulator.configure(tmpRoot.toString(), null);
			LlmEmulator.resetCounters();
			java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<java.util.stream.Stream<String>>> fut =
				LlmEmulator.respond(LlmEmulator.SCHEME + set, req, null);
			java.net.http.HttpResponse<java.util.stream.Stream<String>> resp = fut.get();
			assertEquals(200, resp.statusCode());
			assertEquals(1L, (long) LlmEmulator.stats().get("hit"));
			StringBuilder body = new StringBuilder();
			resp.body().forEach(l -> body.append(l).append('\n'));
			assertTrue(body.toString().contains("data: [DONE]"));
			assertTrue("replayed SSE must carry the recorded content", body.toString().contains("additions"));
		} finally {
			deleteTree(rec);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 6. Hardening: recorder refused when it overlaps the fixture root; oversized exchanges skipped
	// ------------------------------------------------------------------------------------------------

	private static boolean fileNamedExistsUnder(Path root, String fileName) throws Exception {
		if (root == null || !Files.isDirectory(root)) return false;
		try (Stream<Path> s = Files.walk(root)) {
			return s.anyMatch(p -> p.getFileName() != null && p.getFileName().toString().equals(fileName));
		}
	}

	private static long directChildCount(Path dir) throws Exception {
		if (dir == null || !Files.isDirectory(dir)) return 0L;
		try (Stream<Path> s = Files.list(dir)) {
			return s.count();
		}
	}

	/// Drop a hand-written fixture for req into root/set and prove respond() serves it as a HIT whose
	/// SSE body carries the marker. Proves replay is intact independent of the recorder state.
	private void assertReplaysFixture(Path root, String set, OpenAIRequest req, String marker) throws Exception {
		Files.createDirectories(root.resolve(set));
		String key = LlmEmulator.requestKey(req);
		String fixture = "{\"key\":\"" + key + "\",\"kind\":\"extract-chunk\",\"model\":\"" + MODEL + "\",\"response\":{\"content\":"
			+ jsonQuote(marker) + "}}";
		Files.write(root.resolve(set).resolve(key + ".json"), fixture.getBytes(StandardCharsets.UTF_8));
		long hitsBefore = LlmEmulator.stats().get("hit");
		HttpResponse<Stream<String>> resp = LlmEmulator.respond(LlmEmulator.SCHEME + set, req, null).get();
		assertEquals(200, resp.statusCode());
		StringBuilder body = new StringBuilder();
		resp.body().forEach(l -> body.append(l).append('\n'));
		assertTrue("replay must carry the fixture content for set " + set, body.toString().contains(marker));
		assertEquals("the fixture must be served as a HIT", hitsBefore + 1, (long) LlmEmulator.stats().get("hit"));
	}

	/// Security review: with recordDir inside/at/above the fixture root, a user-chosen chatConfig.model
	/// equal to an existing set name would write that user's upstream responses INTO a shared fixture
	/// set and replay them to other tenants. configure() must refuse the recorder (recordDir → null)
	/// while leaving fixture replay enabled, for all three overlap directions.
	@Test
	public void testConfigureRefusesRecordDirOverlappingFixtureRoot() throws Exception {
		String content = "{\"additions\":[],\"revisions\":[],\"removals\":[]}";
		String sys = template(EXTRACT_CHUNK, "system");
		Path nestedRoot = tmpRoot.resolve("x");

		String[][] overlapping = {
			{ tmpRoot.toString(), tmpRoot.toString() },                 /// recordDir == fixtureRoot
			{ tmpRoot.toString(), tmpRoot.resolve("sub").toString() },  /// recordDir under fixtureRoot
			{ nestedRoot.toString(), tmpRoot.toString() },              /// recordDir contains fixtureRoot
		};
		for (int i = 0; i < overlapping.length; i++) {
			String fixtureRoot = overlapping[i][0];
			String recordDir = overlapping[i][1];
			/// A distinct request per case so the replay fixture written by the previous case cannot
			/// satisfy this case's "nothing was recorded" walk.
			OpenAIRequest req = bareRequest(MODEL, sys, extractChunkUser(CHUNK + "\n(overlap case " + i + ")"));
			String keyFile = LlmEmulator.requestKey(req) + ".json";

			LlmEmulator.resetCounters();
			LlmEmulator.configure(fixtureRoot, recordDir);
			assertTrue("fixture replay must stay enabled (case " + i + ", fixtureRoot=" + fixtureRoot + ")", LlmEmulator.isConfigured());
			assertEquals(Paths.get(fixtureRoot).toAbsolutePath().normalize(), LlmEmulator.fixtureRoot());
			assertNull("recording must be DISABLED when recordDir=" + recordDir + " overlaps fixtureRoot=" + fixtureRoot
				+ " (case " + i + ")", LlmEmulator.recordDir());

			assertFalse("record() must report nothing written while the recorder is refused (case " + i + ")",
				LlmEmulator.record(req, content));
			assertEquals("recorded must stay 0 (case " + i + ")", 0L, (long) LlmEmulator.stats().get("recorded"));
			assertFalse("no fixture file may appear anywhere under " + tmpRoot + " (case " + i + ")",
				fileNamedExistsUnder(tmpRoot, keyFile));

			/// Replay still works against the configured root.
			assertReplaysFixture(LlmEmulator.fixtureRoot(), "emu-" + UUID.randomUUID().toString().substring(0, 8), req,
				"OVERLAP-REPLAY-CASE-" + i);
		}

		/// Negative control: disjoint directories must NOT be refused — including a shared NAME prefix
		/// that is not a path-component prefix ("fix" vs "fixtures"). Recording must actually work there.
		Path fix = tmpRoot.resolve("fix");
		Path fixtures = tmpRoot.resolve("fixtures");
		LlmEmulator.resetCounters();
		LlmEmulator.configure(fix.toString(), fixtures.toString());
		assertTrue(LlmEmulator.isConfigured());
		assertEquals("a name-prefix overlap is not a directory overlap and must be allowed",
			fixtures.toAbsolutePath().normalize(), LlmEmulator.recordDir());
		OpenAIRequest ctl = bareRequest(MODEL, sys, extractChunkUser(CHUNK + "\n(negative control)"));
		assertTrue(LlmEmulator.record(ctl, content));
		assertEquals(1L, (long) LlmEmulator.stats().get("recorded"));
		assertTrue(fileNamedExistsUnder(fixtures, LlmEmulator.requestKey(ctl) + ".json"));
		assertFalse("the control recording must land under recordDir, never under the fixture root",
			fileNamedExistsUnder(fix, LlmEmulator.requestKey(ctl) + ".json"));
	}

	/// Security review: a hostile upstream must not be able to fill the disk through the recorder. Any
	/// exchange whose request-message content + response content exceeds MAX_RECORD_CHARS is skipped —
	/// no file, no directory, recorded not incremented — while a normal-size exchange still records.
	@Test
	public void testRecorderSkipsOversizedExchangeButStillWritesNormalOne() throws Exception {
		Path rec = Files.createTempDirectory("am7-llm-emulator-rec-cap-");
		try {
			LlmEmulator.configure(tmpRoot.toString(), rec.toString());
			assertEquals(rec.toAbsolutePath().normalize(), LlmEmulator.recordDir());
			assertEquals(0L, directChildCount(rec));

			OpenAIRequest req = bareRequest(MODEL, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK));
			long msgChars = 0L;
			for (OpenAIMessage m : req.getMessages()) msgChars += m.getContent().length();
			assertTrue("test premise: prompt alone must be well under the cap", msgChars < LlmEmulator.MAX_RECORD_CHARS / 2);

			/// Response pushes the total exactly one char over the cap → skipped.
			String oneOver = "x".repeat((int) (LlmEmulator.MAX_RECORD_CHARS - msgChars + 1));
			assertFalse("an exchange over MAX_RECORD_CHARS must not be written", LlmEmulator.record(req, oneOver));
			assertEquals(0L, (long) LlmEmulator.stats().get("recorded"));
			assertFalse(fileNamedExistsUnder(rec, LlmEmulator.requestKey(req) + ".json"));

			/// Request alone over the cap (tiny response) → skipped too; the cap covers both sides.
			OpenAIRequest hugeReq = bareRequest(MODEL, null, "y".repeat(LlmEmulator.MAX_RECORD_CHARS + 1));
			assertFalse(LlmEmulator.record(hugeReq, "{}"));
			assertEquals(0L, (long) LlmEmulator.stats().get("recorded"));
			assertFalse(fileNamedExistsUnder(rec, LlmEmulator.requestKey(hugeReq) + ".json"));

			/// The size gate runs before any filesystem work: not even the model directory was created.
			assertEquals("a skipped exchange must leave the record dir untouched", 0L, directChildCount(rec));

			/// Response bringing the total to EXACTLY the cap → still written (the rule is "exceeds").
			String atCap = "z".repeat((int) (LlmEmulator.MAX_RECORD_CHARS - msgChars));
			assertTrue("an exchange of exactly MAX_RECORD_CHARS must still be written", LlmEmulator.record(req, atCap));
			assertEquals(1L, (long) LlmEmulator.stats().get("recorded"));
			assertTrue(fileNamedExistsUnder(rec, LlmEmulator.requestKey(req) + ".json"));

			/// A normal-size exchange records as before.
			OpenAIRequest normalReq = bareRequest(MODEL, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK + "\n(normal)"));
			assertTrue(LlmEmulator.record(normalReq, "{\"additions\":[],\"revisions\":[],\"removals\":[]}"));
			assertEquals(2L, (long) LlmEmulator.stats().get("recorded"));
			assertTrue(fileNamedExistsUnder(rec, LlmEmulator.requestKey(normalReq) + ".json"));
		} finally {
			deleteTree(rec);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// 7. Zip-bundled fixtures: <set>/fixtures.zip with <sha256>.json entries at the zip ROOT
	// ------------------------------------------------------------------------------------------------

	/// A recorded-fixture JSON document for req whose assistant content is marker.
	private static byte[] fixtureJson(OpenAIRequest req, String marker) {
		String key = LlmEmulator.requestKey(req);
		return ("{\"key\":\"" + key + "\",\"kind\":\"extract-chunk\",\"model\":\"" + MODEL + "\",\"response\":{\"content\":"
			+ jsonQuote(marker) + "}}").getBytes(StandardCharsets.UTF_8);
	}

	/// Build a zip in memory from (entryName → bytes) pairs, in order. A name ending in '/' is a
	/// directory entry. Uses java.util.zip only — the same reader the emulator uses.
	private static byte[] zipOf(Object... nameBytesPairs) throws Exception {
		return zipOf(java.util.zip.Deflater.DEFAULT_COMPRESSION, nameBytesPairs);
	}

	private static byte[] zipOf(int deflateLevel, Object[] nameBytesPairs) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (ZipOutputStream zout = new ZipOutputStream(bos)) {
			zout.setLevel(deflateLevel);
			for (int i = 0; i < nameBytesPairs.length; i += 2) {
				String name = (String) nameBytesPairs[i];
				byte[] data = (byte[]) nameBytesPairs[i + 1];
				zout.putNextEntry(new ZipEntry(name));
				if (data != null && data.length > 0) zout.write(data);
				zout.closeEntry();
			}
		}
		return bos.toByteArray();
	}

	private Path writeZip(String set, byte[] zipBytes) throws Exception {
		Path zip = tmpRoot.resolve(set).resolve(LlmEmulator.FIXTURES_ZIP);
		Files.write(zip, zipBytes);
		return zip;
	}

	private static String sseBody(HttpResponse<Stream<String>> resp) {
		StringBuilder body = new StringBuilder();
		resp.body().forEach(l -> body.append(l).append('\n'));
		return body.toString();
	}

	/// (i) A fixture that exists ONLY inside fixtures.zip is served as a HIT with the recorded content.
	/// The manifest stays loose and strict: a zip fixture must satisfy strict mode exactly like a loose
	/// one, so a set shipped as manifest.json + fixtures.zip is fully usable with no loose fixtures.
	@Test
	public void testZipOnlyFixtureIsServedAsHit() throws Exception {
		String set = newSet("{\"strict\": true, \"kinds\": {}, \"faults\": []}");
		OpenAIRequest req = bareRequest(MODEL, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK + "\n(zip only)"));
		String key = LlmEmulator.requestKey(req);
		writeZip(set, zipOf(key + ".json", fixtureJson(req, "ZIP-ONLY-MARKER-" + key.substring(0, 8))));
		assertFalse("test premise: no loose fixture for this key", Files.exists(tmpRoot.resolve(set).resolve(key + ".json")));

		HttpResponse<Stream<String>> resp = LlmEmulator.respond(LlmEmulator.SCHEME + set, req, null).get();
		assertEquals("zip fixture must be served, not a strict miss", 200, resp.statusCode());
		String body = sseBody(resp);
		assertTrue("replayed SSE must carry the ZIP fixture's recorded content: " + body, body.contains("ZIP-ONLY-MARKER-" + key.substring(0, 8)));
		assertTrue(body.contains("data: [DONE]"));

		Map<String, Long> st = LlmEmulator.stats();
		assertEquals("a zip fixture is a HIT", 1L, (long) st.get("hit"));
		assertEquals(0L, (long) st.get("miss"));
		assertEquals(0L, (long) st.get("synth"));
		assertEquals("stats().fixtures counts the loaded zip entries", 1L, (long) st.get("fixtures"));

		/// Second call for the same key: still a HIT, and the zip was loaded once (count unchanged).
		assertEquals(200, LlmEmulator.respond(LlmEmulator.SCHEME + set, req, null).get().statusCode());
		assertEquals(2L, (long) LlmEmulator.stats().get("hit"));
		assertEquals(1L, (long) LlmEmulator.stats().get("fixtures"));
	}

	/// (ii) A loose <key>.json beside fixtures.zip wins over the zip entry with the same name.
	@Test
	public void testLooseFixtureOverridesZipEntryWithSameKey() throws Exception {
		String set = newSet(null);
		OpenAIRequest req = bareRequest(MODEL, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK + "\n(loose beats zip)"));
		String key = LlmEmulator.requestKey(req);
		writeZip(set, zipOf(key + ".json", fixtureJson(req, "FROM-ZIP")));

		/// Zip alone → the zip content. Nothing is shadowed yet.
		HttpResponse<Stream<String>> r1 = LlmEmulator.respond(LlmEmulator.SCHEME + set, req, null).get();
		assertEquals(200, r1.statusCode());
		assertTrue(sseBody(r1).contains("FROM-ZIP"));
		assertEquals(1L, (long) LlmEmulator.stats().get("hit"));
		assertEquals("a zip-served hit shadows nothing", 0L, (long) LlmEmulator.stats().get("shadowed"));

		/// Now drop a loose fixture for the SAME key with different content: it must win on the next call
		/// even though the zip is already loaded and cached for this set — and the shadowing is counted.
		Files.write(tmpRoot.resolve(set).resolve(key + ".json"), fixtureJson(req, "FROM-LOOSE"));
		HttpResponse<Stream<String>> r2 = LlmEmulator.respond(LlmEmulator.SCHEME + set, req, null).get();
		assertEquals(200, r2.statusCode());
		String body = sseBody(r2);
		assertTrue("the loose fixture must override the zip entry: " + body, body.contains("FROM-LOOSE"));
		assertFalse("the zip content must not leak through once a loose fixture exists: " + body, body.contains("FROM-ZIP"));
		assertEquals(2L, (long) LlmEmulator.stats().get("hit"));
		assertEquals(0L, (long) LlmEmulator.stats().get("synth"));
		assertEquals("a loose hit over a same-named zip entry is counted as shadowed", 1L, (long) LlmEmulator.stats().get("shadowed"));

		/// A loose hit in a set whose zip does NOT hold that key is not a shadow.
		OpenAIRequest looseOnly = bareRequest(MODEL, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK + "\n(loose only)"));
		Files.write(tmpRoot.resolve(set).resolve(LlmEmulator.requestKey(looseOnly) + ".json"), fixtureJson(looseOnly, "LOOSE-ONLY"));
		HttpResponse<Stream<String>> r3 = LlmEmulator.respond(LlmEmulator.SCHEME + set, looseOnly, null).get();
		assertEquals(200, r3.statusCode());
		assertTrue(sseBody(r3).contains("LOOSE-ONLY"));
		assertEquals(3L, (long) LlmEmulator.stats().get("hit"));
		assertEquals("shadowed counts only loose hits the zip could also have served", 1L, (long) LlmEmulator.stats().get("shadowed"));

		/// It is a counter: resetCounters() zeroes it (the zip cache itself is untouched).
		LlmEmulator.resetCounters();
		assertEquals(0L, (long) LlmEmulator.stats().get("shadowed"));
		assertEquals("resetCounters must not drop the zip cache", 1L, (long) LlmEmulator.stats().get("fixtures"));
	}

	/// (iii) Only entries named EXACTLY <64 hex>.json at the zip root are fixtures. Traversal names,
	/// nested directories, manifest.json, directory entries and other files are ignored — never matched,
	/// never resolved against the filesystem, never written anywhere.
	@Test
	public void testZipIgnoresNonConformingEntryNames() throws Exception {
		String set = newSet(null);
		String sys = template(EXTRACT_CHUNK, "system");
		OpenAIRequest target = bareRequest(MODEL, sys, extractChunkUser(CHUNK + "\n(nonconforming)"));
		String key = LlmEmulator.requestKey(target);
		OpenAIRequest other = bareRequest(MODEL, sys, extractChunkUser(CHUNK + "\n(the one valid entry)"));
		String otherKey = LlmEmulator.requestKey(other);

		byte[] evil = fixtureJson(target, "EVIL-MUST-NOT-REPLAY");
		writeZip(set, zipOf(
			"../evil.json", evil,                     /// traversal name
			"../" + key + ".json", evil,              /// traversal-prefixed REAL key: must not match the key either
			"sub/" + key + ".json", evil,             /// nested path holding the real key
			"dir/", new byte[0],                      /// directory entry
			"notes.txt", "not a fixture".getBytes(StandardCharsets.UTF_8),
			LlmEmulator.MANIFEST_FILE, "{\"strict\": true}".getBytes(StandardCharsets.UTF_8), /// manifest inside the zip is ignored (it stays loose)
			key.toUpperCase() + ".json", evil,        /// wrong case: FIXTURE_FILE is lowercase hex
			otherKey + ".json", fixtureJson(other, "VALID-ZIP-ENTRY")));

		/// The target key has NO conforming entry → not a hit. The manifest inside the zip did not make
		/// the set strict (the loose set has no manifest) → synthesis, not a 500.
		HttpResponse<Stream<String>> resp = LlmEmulator.respond(LlmEmulator.SCHEME + set, target, null).get();
		assertEquals(200, resp.statusCode());
		String body = sseBody(resp);
		assertFalse("a non-conforming zip entry must never be replayed: " + body, body.contains("EVIL-MUST-NOT-REPLAY"));
		assertEquals("no hit for the target key", 0L, (long) LlmEmulator.stats().get("hit"));
		assertEquals("non-strict miss → synthesized", 1L, (long) LlmEmulator.stats().get("synth"));
		assertEquals("exactly one conforming entry was loaded", 1L, (long) LlmEmulator.stats().get("fixtures"));

		/// The one conforming entry is served normally from the same zip.
		HttpResponse<Stream<String>> ok = LlmEmulator.respond(LlmEmulator.SCHEME + set, other, null).get();
		assertEquals(200, ok.statusCode());
		assertTrue(sseBody(ok).contains("VALID-ZIP-ENTRY"));
		assertEquals(1L, (long) LlmEmulator.stats().get("hit"));

		/// Nothing from the zip was materialized on disk, anywhere near the fixture root.
		assertFalse(Files.exists(tmpRoot.resolve("evil.json")));
		assertFalse(Files.exists(tmpRoot.resolve(set).resolve("evil.json")));
		assertFalse(fileNamedExistsUnder(tmpRoot, "evil.json"));
		assertFalse(fileNamedExistsUnder(tmpRoot, "notes.txt"));
		assertFalse("zip entries are map keys, never paths", Files.exists(tmpRoot.resolve(set).resolve("sub")));
		assertFalse(Files.exists(tmpRoot.resolve(set).resolve("dir")));
		assertFalse("the in-zip manifest must not be extracted", Files.exists(tmpRoot.resolve(set).resolve(LlmEmulator.MANIFEST_FILE)));
	}

	/// (iv) A corrupt fixtures.zip (garbage bytes, or a truncated real archive) and a MISSING zip are
	/// all "no zip fixtures": no exception escapes respond(); non-strict sets synthesize and strict sets
	/// report an ordinary miss.
	@Test
	public void testCorruptOrMissingZipIsAMissNotAnException() throws Exception {
		String sys = template(EXTRACT_CHUNK, "system");
		OpenAIRequest req = bareRequest(MODEL, sys, extractChunkUser(CHUNK + "\n(corrupt zip)"));

		/// (a) Garbage bytes where the zip should be, non-strict → synth.
		String garbageSet = newSet(null);
		writeZip(garbageSet, "this is not a zip archive at all \u0000\u0001\u0002 PK-but-not-really".getBytes(StandardCharsets.UTF_8));
		HttpResponse<Stream<String>> r1 = LlmEmulator.respond(LlmEmulator.SCHEME + garbageSet, req, null).get();
		assertEquals("a corrupt zip must degrade to 'no zip fixtures', not fail the call", 200, r1.statusCode());
		assertTrue(sseBody(r1).contains("data: [DONE]"));
		assertEquals(0L, (long) LlmEmulator.stats().get("hit"));
		assertEquals(1L, (long) LlmEmulator.stats().get("synth"));
		assertEquals(0L, (long) LlmEmulator.stats().get("fixtures"));

		/// (b) A real archive truncated mid-entry (valid local header, torn data), non-strict → synth.
		/// Stored (uncompressed) so the entry body is ~4 KB and cutting the file in half is guaranteed to
		/// tear the entry data itself, not merely the central directory (which ZipInputStream never reads).
		String truncatedSet = newSet(null);
		byte[] good = zipOf(java.util.zip.Deflater.NO_COMPRESSION,
			new Object[] { LlmEmulator.requestKey(req) + ".json", fixtureJson(req, "x".repeat(4096)) });
		assertTrue("test premise: the archive must be dominated by the entry body", good.length > 4096);
		writeZip(truncatedSet, java.util.Arrays.copyOf(good, good.length / 2));
		HttpResponse<Stream<String>> r2 = LlmEmulator.respond(LlmEmulator.SCHEME + truncatedSet, req, null).get();
		assertEquals("a truncated zip must degrade to 'no zip fixtures', not fail the call", 200, r2.statusCode());
		String b2 = sseBody(r2);
		assertFalse("a torn entry must never be served as a fixture", b2.contains("xxxxxxxx"));
		assertEquals(0L, (long) LlmEmulator.stats().get("hit"));
		assertEquals(2L, (long) LlmEmulator.stats().get("synth"));
		assertEquals(0L, (long) LlmEmulator.stats().get("fixtures"));

		/// (c) No zip at all, non-strict → synth (the pre-zip behaviour is unchanged).
		String missingSet = newSet(null);
		assertFalse(Files.exists(tmpRoot.resolve(missingSet).resolve(LlmEmulator.FIXTURES_ZIP)));
		HttpResponse<Stream<String>> r3 = LlmEmulator.respond(LlmEmulator.SCHEME + missingSet, req, null).get();
		assertEquals(200, r3.statusCode());
		assertEquals(3L, (long) LlmEmulator.stats().get("synth"));

		/// (d) Corrupt zip in a STRICT set → an ordinary strict miss (500 + "fixture miss (strict)"), not an exception.
		String strictSet = newSet("{\"strict\": true, \"kinds\": {}, \"faults\": []}");
		writeZip(strictSet, "garbage".getBytes(StandardCharsets.UTF_8));
		HttpResponse<Stream<String>> r4 = LlmEmulator.respond(LlmEmulator.SCHEME + strictSet, req, null).get();
		assertEquals(500, r4.statusCode());
		String b4 = sseBody(r4);
		assertTrue("strict + corrupt zip is a plain fixture miss: " + b4, b4.contains("fixture miss (strict)"));
		assertEquals(1L, (long) LlmEmulator.stats().get("miss"));
		assertEquals(0L, (long) LlmEmulator.stats().get("hit"));
		assertEquals(0L, (long) LlmEmulator.stats().get("fixtures"));
	}

	// ------------------------------------------------------------------------------------------------
	// 8. Zip loader bounds: cache growth, per-set byte cap, declared-size rejection, recorder parity
	// ------------------------------------------------------------------------------------------------

	/// A conforming fixture entry name for a small integer: 64 lowercase hex digits + ".json".
	private static String hexName(int i) {
		return String.format("%064x", i) + ".json";
	}

	/// A fixture document for an arbitrary key (no request needed) whose assistant content is content.
	private static byte[] fixtureBytesForKey(String key64, String content) {
		return ("{\"key\":\"" + key64 + "\",\"kind\":\"extract-chunk\",\"model\":\"" + MODEL + "\",\"response\":{\"content\":"
			+ jsonQuote(content) + "}}").getBytes(StandardCharsets.UTF_8);
	}

	private static int le16(byte[] b, int off) {
		return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
	}

	private static int le32(byte[] b, int off) {
		return le16(b, off) | (le16(b, off + 2) << 16);
	}

	private static void putLe32(byte[] b, int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >>> 8);
		b[off + 2] = (byte) (v >>> 16);
		b[off + 3] = (byte) (v >>> 24);
	}

	/// Overwrite the FIRST byte of the FIRST entry's compressed data with a deflate block header whose
	/// BTYPE is 11 (reserved): any attempt to inflate the entry throws on its very first byte.
	private static void corruptFirstEntryData(byte[] zip) {
		assertEquals("test premise: local file header signature", 0x04034b50, le32(zip, 0));
		int nameLen = le16(zip, 26);
		int extraLen = le16(zip, 28);
		zip[30 + nameLen + extraLen] = 0x07;
	}

	/// Rewrite the central-directory UNCOMPRESSED size of the named entry (what ZipEntry.getSize()
	/// reports) without touching its data. Walks the central directory from the end-of-central-directory
	/// record so entry data can never be mistaken for a header. Returns true when the entry was found.
	private static boolean patchCentralDirectorySize(byte[] zip, String entryName, int newUncompressedSize) {
		int eocd = -1;
		for (int i = zip.length - 22; i >= 0; i--) {
			if (le32(zip, i) == 0x06054b50) { eocd = i; break; }
		}
		assertTrue("test premise: end-of-central-directory record", eocd >= 0);
		int entries = le16(zip, eocd + 10);
		int cen = le32(zip, eocd + 16);
		for (int n = 0; n < entries; n++) {
			assertEquals("test premise: central directory header signature", 0x02014b50, le32(zip, cen));
			int nameLen = le16(zip, cen + 28);
			int extraLen = le16(zip, cen + 30);
			int commentLen = le16(zip, cen + 32);
			String name = new String(zip, cen + 46, nameLen, StandardCharsets.UTF_8);
			if (name.equals(entryName)) {
				putLe32(zip, cen + 24, newUncompressedSize);
				return true;
			}
			cen += 46 + nameLen + extraLen + commentLen;
		}
		return false;
	}

	/// (a) A serverUrl naming a set whose directory does not exist synthesizes (non-strict) but must NOT
	/// add a ZIP_FIXTURES cache key — otherwise any EMULATOR connection could grow the cache without bound
	/// by rotating invented names. A set whose directory exists is cached exactly once, zip or no zip.
	@Test
	public void testNonExistentSetDoesNotGrowTheZipCache() throws Exception {
		OpenAIRequest req = bareRequest(MODEL, template(EXTRACT_CHUNK, "system"), extractChunkUser(CHUNK + "\n(ghost set)"));
		assertEquals("test premise: a fresh configuration caches no zip sets", 0, LlmEmulator.cachedZipSets());

		for (int i = 0; i < 5; i++) {
			String ghost = "ghost-" + UUID.randomUUID().toString().substring(0, 8);
			assertFalse(Files.exists(tmpRoot.resolve(ghost)));
			HttpResponse<Stream<String>> r = LlmEmulator.respond(LlmEmulator.SCHEME + ghost, req, null).get();
			assertEquals("a non-existent, non-strict set synthesizes", 200, r.statusCode());
			assertTrue(sseBody(r).contains("data: [DONE]"));
		}
		assertEquals("invented set names must not add ZIP_FIXTURES keys", 0, LlmEmulator.cachedZipSets());
		assertEquals(0L, (long) LlmEmulator.stats().get("fixtures"));
		assertEquals(5L, (long) LlmEmulator.stats().get("synth"));
		assertEquals(0L, (long) LlmEmulator.stats().get("hit"));

		/// The direct accessor follows the same rule.
		assertTrue(LlmEmulator.zipFixtures(tmpRoot.resolve("ghost-direct")).isEmpty());
		assertTrue(LlmEmulator.zipFixtures(null).isEmpty());
		assertEquals(0, LlmEmulator.cachedZipSets());

		/// A set whose directory EXISTS is cached (once), even with no fixtures.zip in it.
		String real = newSet(null);
		assertEquals(200, LlmEmulator.respond(LlmEmulator.SCHEME + real, req, null).get().statusCode());
		assertEquals("an existing set directory is cached on first lookup", 1, LlmEmulator.cachedZipSets());
		assertEquals(200, LlmEmulator.respond(LlmEmulator.SCHEME + real, req, null).get().statusCode());
		assertEquals("...and only once", 1, LlmEmulator.cachedZipSets());
		assertEquals(0L, (long) LlmEmulator.stats().get("fixtures"));

		/// Reconfiguring drops the cache.
		LlmEmulator.configure(tmpRoot.toString(), null);
		assertEquals(0, LlmEmulator.cachedZipSets());
	}

	/// (b) The per-set BYTE bound: loading stops at the first conforming entry (in central-directory
	/// order) that would cross the cap, and every later entry is ignored. The production cap admits the
	/// whole set. Exercised through the package's cap-taking overload; zipFixtures() always passes
	/// MAX_ZIP_BYTES.
	@Test
	public void testPerSetByteCapStopsLoadingAtTheBound() throws Exception {
		String set = newSet(null);
		Path setDir = tmpRoot.resolve(set);
		int n = 6;
		byte[][] payloads = new byte[n][];
		Object[] pairs = new Object[n * 2];
		for (int i = 0; i < n; i++) {
			payloads[i] = fixtureBytesForKey(hexName(i).substring(0, 64), "P" + i + "-" + "p".repeat(10_000));
			pairs[i * 2] = hexName(i);
			pairs[i * 2 + 1] = payloads[i];
		}
		writeZip(set, zipOf(pairs));
		assertEquals(0, LlmEmulator.cachedZipSets());

		long capForThree = (long) payloads[0].length + payloads[1].length + payloads[2].length;
		Map<String, byte[]> three = LlmEmulator.loadZipFixtures(setDir, capForThree);
		assertEquals("exactly the first three entries fit the cap", java.util.Set.of(hexName(0), hexName(1), hexName(2)), three.keySet());
		assertArrayEquals("loaded bytes are the entry's inflated bytes", payloads[2], three.get(hexName(2)));

		/// One byte less and the third entry would cross the bound: it and everything after it are ignored.
		Map<String, byte[]> two = LlmEmulator.loadZipFixtures(setDir, capForThree - 1);
		assertEquals(java.util.Set.of(hexName(0), hexName(1)), two.keySet());

		/// A cap below the first entry loads nothing (and does not throw).
		assertTrue(LlmEmulator.loadZipFixtures(setDir, 10).isEmpty());

		/// Direct loader calls bypass the cache; the production path caches with MAX_ZIP_BYTES, which
		/// admits everything here (and a recorded chapter set of a few hundred entries / ~1 MB with room to spare).
		assertEquals(0, LlmEmulator.cachedZipSets());
		assertEquals(64L * 1024 * 1024, LlmEmulator.MAX_ZIP_BYTES);
		assertEquals(n, LlmEmulator.zipFixtures(setDir).size());
		assertEquals(1, LlmEmulator.cachedZipSets());
		assertEquals((long) n, (long) LlmEmulator.stats().get("fixtures"));
	}

	/// (c) An entry whose DECLARED uncompressed size exceeds MAX_ZIP_ENTRY_BYTES is skipped without being
	/// opened. Proof: its compressed data is corrupted so that inflating even its first byte throws, and the
	/// loader treats any exception as "whole archive unreadable → empty set". The good entry beside it
	/// surviving therefore proves the oversized entry was never inflated. Then the opposite lie — a header
	/// declaring 100 bytes for an entry that really inflates past the cap — trips readCapped and drops only
	/// that entry.
	@Test
	public void testOversizedDeclaredEntryIsSkippedWithoutBeingRead() throws Exception {
		String sys = template(EXTRACT_CHUNK, "system");
		OpenAIRequest good = bareRequest(MODEL, sys, extractChunkUser(CHUNK + "\n(good beside bomb)"));
		String goodName = LlmEmulator.requestKey(good) + ".json";
		String bombName = hexName(0xB0B);
		byte[] bombBody = new byte[LlmEmulator.MAX_ZIP_ENTRY_BYTES + 1];
		java.util.Arrays.fill(bombBody, (byte) 'x');

		/// (i) Declared oversize + corrupted deflate stream, listed FIRST so the loader meets it first.
		String set1 = newSet(null);
		byte[] zip1 = zipOf(bombName, bombBody, goodName, fixtureJson(good, "GOOD-BESIDE-BOMB"));
		corruptFirstEntryData(zip1);
		Path z1 = writeZip(set1, zip1);
		try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(z1.toFile())) {
			ZipEntry bomb = zf.getEntry(bombName);
			assertEquals("test premise: the central directory declares the real size", (long) bombBody.length, bomb.getSize());
			assertTrue(bomb.getSize() > LlmEmulator.MAX_ZIP_ENTRY_BYTES);
			boolean threw = false;
			try (java.io.InputStream in = zf.getInputStream(bomb)) {
				in.read(new byte[8192]);
			} catch (java.io.IOException ex) {
				threw = true;
			}
			assertTrue("test premise: inflating the corrupted entry must throw", threw);
		}
		Map<String, byte[]> loaded1 = LlmEmulator.zipFixtures(tmpRoot.resolve(set1));
		assertEquals("only the good entry loads: the oversized one was rejected on its declared size, never opened",
			java.util.Set.of(goodName), loaded1.keySet());
		HttpResponse<Stream<String>> r1 = LlmEmulator.respond(LlmEmulator.SCHEME + set1, good, null).get();
		assertEquals(200, r1.statusCode());
		assertTrue(sseBody(r1).contains("GOOD-BESIDE-BOMB"));
		assertEquals(1L, (long) LlmEmulator.stats().get("hit"));
		assertEquals(1L, (long) LlmEmulator.stats().get("fixtures"));

		/// (ii) Lying header: a VALID deflate stream of MAX_ZIP_ENTRY_BYTES + 1 bytes whose central-directory
		/// size claims 100. The declared-size check admits it; readCapped closes it at the cap; only it is dropped.
		String set2 = newSet(null);
		byte[] zip2 = zipOf(bombName, bombBody, goodName, fixtureJson(good, "GOOD-BESIDE-LIAR"));
		assertTrue("test premise: central-directory entry patched", patchCentralDirectorySize(zip2, bombName, 100));
		Path z2 = writeZip(set2, zip2);
		try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(z2.toFile())) {
			assertEquals("test premise: the header now lies", 100L, zf.getEntry(bombName).getSize());
		}
		Map<String, byte[]> loaded2 = LlmEmulator.zipFixtures(tmpRoot.resolve(set2));
		assertEquals("the liar is dropped at the read cap; the good entry is unaffected", java.util.Set.of(goodName), loaded2.keySet());
		HttpResponse<Stream<String>> r2 = LlmEmulator.respond(LlmEmulator.SCHEME + set2, good, null).get();
		assertEquals(200, r2.statusCode());
		assertTrue(sseBody(r2).contains("GOOD-BESIDE-LIAR"));
		assertEquals(2L, (long) LlmEmulator.stats().get("hit"));
		assertEquals(2L, (long) LlmEmulator.stats().get("fixtures"));
	}

	/// (d) Units parity between recorder and loader. The recorder gates an exchange in CHARS
	/// (MAX_RECORD_CHARS over request+response content); the loader gates an entry in BYTES
	/// (MAX_ZIP_ENTRY_BYTES = 4 × MAX_RECORD_CHARS) and then re-applies the recorder's char rule. So:
	/// a fixture the recorder ACTUALLY WROTE for 1.9M three-byte chars (5.7 MB — over MAX_RECORD_CHARS
	/// bytes, under it in chars) is admitted from the zip and replayed as a HIT; and a fixture over the
	/// char cap but under every byte cap is refused by the loader exactly as record() refuses the exchange.
	/// U+4E2D: one Java char, three UTF-8 bytes.
	private static final String CJK = "\u4e2d";

	@Test
	public void testZipEntryByteCapAdmitsEverythingTheRecorderWouldWrite() throws Exception {
		assertEquals(4L * LlmEmulator.MAX_RECORD_CHARS, (long) LlmEmulator.MAX_ZIP_ENTRY_BYTES);
		assertEquals(3, CJK.getBytes(StandardCharsets.UTF_8).length);
		String sys = template(EXTRACT_CHUNK, "system");
		OpenAIRequest wide = bareRequest(MODEL, sys, extractChunkUser(CHUNK + "\n(multibyte)"));
		String wideKey = LlmEmulator.requestKey(wide);
		String marker = "MULTIBYTE-MARKER-" + wideKey.substring(0, 8);
		String wideContent = marker + CJK.repeat(1_900_000);
		OpenAIRequest narrow = bareRequest(MODEL, sys, extractChunkUser(CHUNK + "\n(too many chars)"));
		String narrowKey = LlmEmulator.requestKey(narrow);
		String narrowContent = "y".repeat(LlmEmulator.MAX_RECORD_CHARS + 1);

		/// 1. What the recorder does with each exchange.
		Path rec = Files.createTempDirectory("am7-llm-emulator-rec-parity-");
		byte[] recordedWide;
		try {
			LlmEmulator.configure(tmpRoot.toString(), rec.toString());
			assertTrue("the recorder admits 1.9M chars of 3-byte text (under its CHAR cap)", LlmEmulator.record(wide, wideContent));
			assertFalse("the recorder refuses " + narrowContent.length() + " chars (over its CHAR cap)", LlmEmulator.record(narrow, narrowContent));
			assertEquals(1L, (long) LlmEmulator.stats().get("recorded"));
			Path written;
			try (Stream<Path> s = Files.walk(rec)) {
				written = s.filter(p -> p.getFileName().toString().equals(wideKey + ".json")).findFirst().orElse(null);
			}
			assertNotNull("recorded fixture file", written);
			recordedWide = Files.readAllBytes(written);
		} finally {
			LlmEmulator.configure(tmpRoot.toString(), null);
			LlmEmulator.resetCounters();
			deleteTree(rec);
		}
		assertTrue("test premise: the recorded file is over MAX_RECORD_CHARS BYTES (" + recordedWide.length + ")",
			recordedWide.length > LlmEmulator.MAX_RECORD_CHARS);
		assertTrue("test premise: ...and under MAX_ZIP_ENTRY_BYTES", recordedWide.length <= LlmEmulator.MAX_ZIP_ENTRY_BYTES);
		byte[] narrowBytes = fixtureJson(narrow, narrowContent);
		assertTrue("test premise: the over-char fixture is under the per-entry BYTE cap", narrowBytes.length <= LlmEmulator.MAX_ZIP_ENTRY_BYTES);

		/// 2. What the loader does with the same two documents, bundled in one strict set.
		String set = newSet("{\"strict\": true, \"kinds\": {}, \"faults\": []}");
		writeZip(set, zipOf(wideKey + ".json", recordedWide, narrowKey + ".json", narrowBytes));
		Map<String, byte[]> loaded = LlmEmulator.zipFixtures(tmpRoot.resolve(set));
		assertEquals("the loader admits exactly what the recorder wrote and refuses what it refused",
			java.util.Set.of(wideKey + ".json"), loaded.keySet());
		assertEquals(1L, (long) LlmEmulator.stats().get("fixtures"));

		HttpResponse<Stream<String>> hit = LlmEmulator.respond(LlmEmulator.SCHEME + set, wide, null).get();
		assertEquals("the recorded multibyte fixture replays from the zip", 200, hit.statusCode());
		String body = sseBody(hit);
		assertTrue(body.contains(marker));
		assertTrue("the multibyte content itself is replayed, not mangled", body.contains(CJK.repeat(200)));
		assertTrue(body.contains("data: [DONE]"));
		assertEquals(1L, (long) LlmEmulator.stats().get("hit"));

		HttpResponse<Stream<String>> miss = LlmEmulator.respond(LlmEmulator.SCHEME + set, narrow, null).get();
		assertEquals("the over-char entry was never loaded, so strict mode reports a plain miss", 500, miss.statusCode());
		assertTrue(sseBody(miss).contains("fixture miss (strict)"));
		assertEquals(1L, (long) LlmEmulator.stats().get("miss"));
	}
}
