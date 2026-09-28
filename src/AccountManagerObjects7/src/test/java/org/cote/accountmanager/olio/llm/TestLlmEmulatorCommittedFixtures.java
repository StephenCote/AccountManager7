package org.cote.accountmanager.olio.llm;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.net.URL;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.RecordFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The COMMITTED LLM fixture set {@code src/test/resources/llm-fixtures/harlots-eight/fixtures.zip} must be
 * consumed by the production {@link LlmEmulator} replay path and replay each recorded completion exactly.
 *
 * <p>DETERMINISTIC: no database, no GPU, no network. Lives in this package to reach the package-private
 * {@link LlmEmulator#zipFixtures}, {@link LlmEmulator#resolveUnder} and {@link LlmEmulator#loadManifest}.
 *
 * <p>Nothing here hardcodes a fixture key, kind count or recorded content: the zip is rebuilt from each new
 * recording run, so every test discovers its entries dynamically with {@link ZipFile} (a different reader from
 * the emulator's own {@code ZipInputStream}), groups them by {@code kind}, and picks the first entry of each
 * kind in sorted-name order.
 *
 * <p>Two fixture roots are exercised:
 * <ul>
 * <li>the REAL on-disk root ({@code src/test/resources/llm-fixtures}) — but a developer machine that produced
 *     the zip still holds the git-ignored loose {@code <sha256>.json} recorder drops beside it, and a loose file
 *     always wins over the zip entry of the same key, so per-kind replay there is only attempted for keys with
 *     no loose file, and the loose/zip copies are cross-checked for agreement;</li>
 * <li>a CLEAN-CLONE layout: {@code manifest.json} + {@code fixtures.zip} copied byte-for-byte into a temp root
 *     with no loose files — exactly what a fresh checkout has — so the "served from the zip through
 *     {@code respond()}" proof is never vacuous.</li>
 * </ul>
 */
public class TestLlmEmulatorCommittedFixtures {

	private static final Logger logger = LogManager.getLogger(TestLlmEmulatorCommittedFixtures.class);

	private static final String SET = "harlots-eight";
	private static final Pattern FIXTURE_ENTRY = Pattern.compile("^[0-9a-f]{64}\\.json$");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/// Absolute, normalized real fixture root and the set directory beneath it.
	private static Path realRoot;
	private static Path realSetDir;
	/// Every conforming zip entry, parsed, in sorted entry-name order.
	private static final TreeMap<String, Map<String, Object>> ENTRIES = new TreeMap<>();
	/// kind → sorted entry names of that kind.
	private static final TreeMap<String, List<String>> BY_KIND = new TreeMap<>();
	/// Entry names that the zip holds but the emulator's own FIXTURE_FILE filter would drop (should be 0).
	private static int nonConformingEntries = 0;

	/// Clean-clone layout: <tmp>/<SET>/{manifest.json, fixtures.zip}, no loose fixtures.
	private static Path cleanRoot;
	private static Path cleanSetDir;

	// ------------------------------------------------------------------------------------------------
	// Discovery
	// ------------------------------------------------------------------------------------------------

	private static Path resolveRealRoot() throws Exception {
		/// Surefire sets basedir to the module directory; a plain JUnit launch has user.dir there.
		for (String prop : new String[] { "basedir", "user.dir" }) {
			String v = System.getProperty(prop);
			if (v == null || v.isBlank()) continue;
			Path p = Paths.get(v).resolve("src").resolve("test").resolve("resources").resolve("llm-fixtures");
			if (Files.isDirectory(p.resolve(SET))) return p.toAbsolutePath().normalize();
		}
		/// Fall back to the classpath copy (target/test-classes/llm-fixtures) made by process-test-resources.
		URL u = TestLlmEmulatorCommittedFixtures.class.getClassLoader().getResource("llm-fixtures/" + SET + "/" + LlmEmulator.MANIFEST_FILE);
		if (u != null) {
			return Paths.get(u.toURI()).getParent().getParent().toAbsolutePath().normalize();
		}
		fail("could not locate llm-fixtures/" + SET + " on disk or on the classpath");
		return null;
	}

	@BeforeClass
	public static void discoverCommittedZip() throws Exception {
		/// No IOSystem/DB here: register the Olio model names and import the two wire-record schemas from
		/// resources so OpenAIRequest/OpenAIMessage can be instantiated (RecordFactory.getSchema falls back to
		/// importSchemaFromResource when IOSystem is not initialized).
		OlioModelNames.use();
		assertNotNull("olio openaiRequest schema must import from resources", RecordFactory.model(OlioModelNames.MODEL_OPENAI_REQUEST));
		assertNotNull("olio openaiMessage schema must import from resources", RecordFactory.model(OlioModelNames.MODEL_OPENAI_MESSAGE));
		realRoot = resolveRealRoot();
		realSetDir = realRoot.resolve(SET).normalize();
		Path zip = realSetDir.resolve(LlmEmulator.FIXTURES_ZIP);
		assertTrue("committed " + zip + " is missing", Files.isRegularFile(zip));
		assertTrue("committed " + realSetDir.resolve(LlmEmulator.MANIFEST_FILE) + " is missing",
			Files.isRegularFile(realSetDir.resolve(LlmEmulator.MANIFEST_FILE)));

		try (ZipFile zf = new ZipFile(zip.toFile())) {
			Enumeration<? extends ZipEntry> en = zf.entries();
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				String name = e.getName();
				if (e.isDirectory() || !FIXTURE_ENTRY.matcher(name).matches()) {
					nonConformingEntries++;
					continue;
				}
				byte[] bytes;
				try (java.io.InputStream in = zf.getInputStream(e)) {
					bytes = in.readAllBytes();
				}
				Map<String, Object> fx = MAPPER.readValue(bytes, new TypeReference<Map<String, Object>>() { });
				assertNotNull("zip entry " + name + " did not parse as a JSON object", fx);
				ENTRIES.put(name, fx);
				String kind = fx.get("kind") instanceof String ? (String) fx.get("kind") : "(no kind)";
				BY_KIND.computeIfAbsent(kind, k -> new ArrayList<>()).add(name);
			}
		}
		for (List<String> names : BY_KIND.values()) Collections.sort(names);
		assertFalse("the committed zip holds no <sha256>.json fixture entries", ENTRIES.isEmpty());
		StringBuilder summary = new StringBuilder();
		for (Map.Entry<String, List<String>> e : BY_KIND.entrySet()) summary.append(' ').append(e.getKey()).append('=').append(e.getValue().size());
		logger.info("Committed zip " + zip + ": " + ENTRIES.size() + " fixture entries, " + nonConformingEntries
			+ " non-conforming; kinds:" + summary);

		/// Clean-clone layout — the two committed files only.
		cleanRoot = Files.createTempDirectory("am7-llm-committed-fixtures-");
		cleanSetDir = cleanRoot.resolve(SET);
		Files.createDirectories(cleanSetDir);
		Files.copy(zip, cleanSetDir.resolve(LlmEmulator.FIXTURES_ZIP), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(realSetDir.resolve(LlmEmulator.MANIFEST_FILE), cleanSetDir.resolve(LlmEmulator.MANIFEST_FILE), StandardCopyOption.REPLACE_EXISTING);
		assertArrayEquals("the clean-clone zip must be a byte-for-byte copy of the committed zip",
			Files.readAllBytes(zip), Files.readAllBytes(cleanSetDir.resolve(LlmEmulator.FIXTURES_ZIP)));
	}

	@AfterClass
	public static void removeCleanClone() throws Exception {
		if (cleanRoot != null && Files.exists(cleanRoot)) {
			try (Stream<Path> s = Files.walk(cleanRoot)) {
				s.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
					try { Files.deleteIfExists(q); } catch (Exception ignore) { /* best effort */ }
				});
			}
		}
	}

	@Before
	public void configureOnRealRoot() {
		/// Recording OFF, deployment strict OFF (the set manifest decides); configure() also clears the zip cache.
		LlmEmulator.configure(realRoot.toString(), null, false);
		LlmEmulator.resetCounters();
		assertTrue(LlmEmulator.isConfigured());
		assertNull("recording must be OFF for a replay test", LlmEmulator.recordDir());
	}

	@After
	public void unconfigure() {
		LlmEmulator.configure(null, null);
		LlmEmulator.resetCounters();
	}

	// ------------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------------

	/// Rebuild the wire request exactly as the fixture recorded it (model + ordered role/content messages).
	@SuppressWarnings("unchecked")
	private static OpenAIRequest requestFrom(Map<String, Object> fixture) {
		Object ro = fixture.get("request");
		assertTrue("fixture has no request object", ro instanceof Map);
		Map<String, Object> request = (Map<String, Object>) ro;
		OpenAIRequest req = new OpenAIRequest();
		req.setModel((String) request.get("model"));
		Object mo = request.get("messages");
		assertTrue("fixture request has no messages list", mo instanceof List);
		for (Object o : (List<Object>) mo) {
			Map<String, Object> mm = (Map<String, Object>) o;
			OpenAIMessage m = new OpenAIMessage();
			m.setRole((String) mm.get("role"));
			m.setContent((String) mm.get("content"));
			req.addMessage(m);
		}
		return req;
	}

	@SuppressWarnings("unchecked")
	private static String recordedContent(Map<String, Object> fixture) {
		Object resp = fixture.get("response");
		assertTrue("fixture has no response object", resp instanceof Map);
		Object c = ((Map<String, Object>) resp).get("content");
		assertTrue("fixture response.content is not a string", c instanceof String);
		return (String) c;
	}

	private static String keyOf(String entryName) {
		return entryName.substring(0, entryName.length() - ".json".length());
	}

	/// Reassemble the assistant content from the emulated SSE stream the way Chat.processStreamChunk does:
	/// each "data: " line is a chat.completion.chunk whose choices[i].delta.content is appended; [DONE] ends it.
	private static String contentFromSse(HttpResponse<Stream<String>> resp) throws Exception {
		StringBuilder out = new StringBuilder();
		List<String> lines = new ArrayList<>();
		resp.body().forEach(lines::add);
		boolean done = false;
		for (String line : lines) {
			if (line == null || line.isEmpty()) continue;
			assertTrue("every SSE line must be 'data: ' framed: " + line, line.startsWith("data: "));
			String json = line.substring(6);
			if ("[DONE]".equals(json)) {
				done = true;
				break;
			}
			JsonNode node = MAPPER.readTree(json);
			assertNull("no error object may appear in a replayed stream: " + json, node.get("error"));
			JsonNode choices = node.get("choices");
			assertNotNull("chunk without choices: " + json, choices);
			for (JsonNode choice : choices) {
				JsonNode delta = choice.get("delta");
				if (delta != null && delta.hasNonNull("content")) out.append(delta.get("content").asText());
			}
		}
		assertTrue("the stream must terminate with data: [DONE]", done);
		return out.toString();
	}

	private static String rawBody(HttpResponse<Stream<String>> resp) {
		StringBuilder b = new StringBuilder();
		resp.body().forEach(l -> b.append(l).append('\n'));
		return b.toString();
	}

	/// respond() for the fixture and assert the reassembled content is byte-for-byte the recorded one and
	/// that exactly one HIT (no miss/synth/fault) was counted.
	private static void assertReplaysExactly(String setUrl, String entryName, Map<String, Object> fixture, String where) throws Exception {
		OpenAIRequest req = requestFrom(fixture);
		assertEquals("reconstructed request must key to the fixture's own entry name (" + where + ")",
			keyOf(entryName), LlmEmulator.requestKey(req));
		Map<String, Long> before = LlmEmulator.stats();
		HttpResponse<Stream<String>> resp = LlmEmulator.respond(setUrl, req, null).get();
		assertEquals("respond() must answer 200 for a recorded key (" + where + ", " + entryName + ")", 200, resp.statusCode());
		assertEquals("text/event-stream", resp.headers().firstValue("content-type").orElse(null));
		String got = contentFromSse(resp);
		String expected = recordedContent(fixture);
		assertEquals("replayed content differs from response.content (" + where + ", " + entryName + ")", expected, got);
		assertArrayEquals("replayed content differs byte-for-byte (UTF-8) (" + where + ", " + entryName + ")",
			expected.getBytes(StandardCharsets.UTF_8), got.getBytes(StandardCharsets.UTF_8));
		Map<String, Long> after = LlmEmulator.stats();
		assertEquals("hit must increment by exactly 1 (" + where + ")", before.get("hit") + 1, (long) after.get("hit"));
		assertEquals("miss must not change (" + where + ")", before.get("miss"), after.get("miss"));
		assertEquals("synth must not change — a recorded key is never synthesized (" + where + ")", before.get("synth"), after.get("synth"));
		assertEquals("fault must not change (" + where + ")", before.get("fault"), after.get("fault"));
	}

	// ------------------------------------------------------------------------------------------------
	// 1. Every committed entry is internally consistent with the production key + kind derivation
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testEveryZipEntryKeysAndKindsExactlyAsTheProductionCodeDerivesThem() {
		assertEquals("every entry in the committed zip must be a conforming <sha256>.json (the emulator silently drops the rest)",
			0, nonConformingEntries);
		int checked = 0;
		for (Map.Entry<String, Map<String, Object>> e : ENTRIES.entrySet()) {
			String name = e.getKey();
			Map<String, Object> fx = e.getValue();
			assertEquals("entry name and embedded key disagree: " + name, keyOf(name), fx.get("key"));
			OpenAIRequest req = requestFrom(fx);
			assertEquals("LlmEmulator.requestKey over the recorded request must reproduce the recorded key: " + name,
				keyOf(name), LlmEmulator.requestKey(req));
			assertEquals("the fixture's request.model must be what the key was derived from: " + name, fx.get("model"), req.getModel());
			assertEquals("LlmEmulator.detectKind must classify the recorded prompt as the recorded kind: " + name,
				fx.get("kind"), LlmEmulator.detectKind(req));
			assertFalse("recorded content must not be empty: " + name, recordedContent(fx).isEmpty());
			checked++;
		}
		assertEquals(ENTRIES.size(), checked);
		assertTrue("expected more than one prompt kind in the committed set, found " + BY_KIND.keySet(), BY_KIND.size() > 1);
		logger.info("Verified key+kind derivation on " + checked + " committed fixtures across kinds " + BY_KIND.keySet());
	}

	// ------------------------------------------------------------------------------------------------
	// 2. Per kind, the first zip-only entry replays byte-for-byte from the REAL fixture root
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testFirstZipOnlyEntryOfEachKindReplaysFromRealRoot() throws Exception {
		Path setDir = LlmEmulator.resolveUnder(LlmEmulator.fixtureRoot(), SET);
		assertEquals(realSetDir, setDir);
		String setUrl = LlmEmulator.SCHEME + SET;
		int replayed = 0;
		List<String> shadowedKinds = new ArrayList<>();
		for (Map.Entry<String, List<String>> kind : BY_KIND.entrySet()) {
			String picked = null;
			int skipped = 0;
			for (String name : kind.getValue()) {
				if (Files.exists(setDir.resolve(name))) {
					skipped++;
					continue; /// a loose recorder drop shadows the zip entry — pick the next one
				}
				picked = name;
				break;
			}
			if (picked == null) {
				/// Every entry of this kind is shadowed by a loose drop on this machine (the machine that
				/// produced the zip). The clean-clone test below covers the zip path for it.
				shadowedKinds.add(kind.getKey() + "(" + skipped + " loose)");
				continue;
			}
			assertTrue("premise: no loose file may shadow the picked key", Files.notExists(setDir.resolve(picked)));
			assertReplaysExactly(setUrl, picked, ENTRIES.get(picked), "real root, kind=" + kind.getKey());
			replayed++;
		}
		logger.info("Real-root replay: " + replayed + " kind(s) replayed from zip-only keys; kinds fully shadowed by loose drops: " + shadowedKinds);
		if (replayed > 0) {
			assertEquals("stats().fixtures must report the zip once the first zip-only lookup loaded it",
				(long) ENTRIES.size(), (long) LlmEmulator.stats().get("fixtures"));
		}
		assertEquals("every kind was either replayed from the zip or accounted for as loose-shadowed",
			BY_KIND.size(), replayed + shadowedKinds.size());
	}

	// ------------------------------------------------------------------------------------------------
	// 3. Clean-clone layout (manifest.json + fixtures.zip only): every kind replays byte-for-byte
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testFirstEntryOfEachKindReplaysByteForByteFromZipInCleanCloneLayout() throws Exception {
		LlmEmulator.configure(cleanRoot.toString(), null, false);
		LlmEmulator.resetCounters();
		Path setDir = LlmEmulator.resolveUnder(LlmEmulator.fixtureRoot(), SET);
		assertNotNull(setDir);
		try (Stream<Path> s = Files.list(setDir)) {
			List<String> files = s.map(p -> p.getFileName().toString()).sorted().collect(java.util.stream.Collectors.toList());
			assertEquals("clean-clone set must hold exactly the two committed files",
				List.of(LlmEmulator.FIXTURES_ZIP, LlmEmulator.MANIFEST_FILE), files);
		}
		assertEquals("nothing loaded before the first lookup", 0L, (long) LlmEmulator.stats().get("fixtures"));

		String setUrl = LlmEmulator.SCHEME + SET;
		int replayed = 0;
		for (Map.Entry<String, List<String>> kind : BY_KIND.entrySet()) {
			String picked = kind.getValue().get(0);
			assertTrue("clean clone has no loose files by construction", Files.notExists(setDir.resolve(picked)));
			assertReplaysExactly(setUrl, picked, ENTRIES.get(picked), "clean clone, kind=" + kind.getKey());
			replayed++;
			assertEquals("the production zip loader must hold every conforming entry (" + kind.getKey() + ")",
				(long) ENTRIES.size(), (long) LlmEmulator.stats().get("fixtures"));
		}
		assertEquals("one replay per kind", BY_KIND.size(), replayed);
		Map<String, Long> st = LlmEmulator.stats();
		assertEquals((long) BY_KIND.size(), (long) st.get("hit"));
		assertEquals(0L, (long) st.get("miss"));
		assertEquals(0L, (long) st.get("synth"));
		assertEquals(0L, (long) st.get("fault"));
		logger.info("Clean-clone replay: " + replayed + " kind(s) served from fixtures.zip through respond(): " + BY_KIND.keySet());
	}

	// ------------------------------------------------------------------------------------------------
	// 4. Zip count and loose/zip agreement
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testProductionZipLoaderCountMatchesIndependentZipFileCountAndAgreesWithLooseCopies() throws Exception {
		Path setDir = LlmEmulator.resolveUnder(LlmEmulator.fixtureRoot(), SET);
		Map<String, byte[]> loaded = LlmEmulator.zipFixtures(setDir);
		assertEquals("LlmEmulator.zipFixtures must load exactly the ^[0-9a-f]{64}\\.json$ entries ZipFile counted",
			ENTRIES.size(), loaded.size());
		assertEquals("stats().fixtures mirrors the loaded zip", (long) ENTRIES.size(), (long) LlmEmulator.stats().get("fixtures"));
		assertEquals("same entry names", ENTRIES.keySet(), new java.util.TreeSet<>(loaded.keySet()));

		int compared = 0;
		for (Map.Entry<String, Map<String, Object>> e : ENTRIES.entrySet()) {
			String name = e.getKey();
			/// The bytes the production loader holds must parse to the same content ZipFile gave us.
			Map<String, Object> viaLoader = MAPPER.readValue(loaded.get(name), new TypeReference<Map<String, Object>>() { });
			assertEquals("production loader bytes and ZipFile bytes disagree on response.content: " + name,
				recordedContent(e.getValue()), recordedContent(viaLoader));

			/// When a git-ignored loose recorder drop of the same key is present, it must agree with the zip.
			Path loose = setDir.resolve(name);
			if (Files.isRegularFile(loose)) {
				Map<String, Object> looseFx = MAPPER.readValue(Files.readAllBytes(loose), new TypeReference<Map<String, Object>>() { });
				assertEquals("loose " + name + " and its zip entry disagree on response.content",
					recordedContent(looseFx), recordedContent(e.getValue()));
				assertEquals("loose " + name + " and its zip entry disagree on request", looseFx.get("request"), e.getValue().get("request"));
				compared++;
			}
		}
		logger.info("Loose/zip agreement checked on " + compared + " of " + ENTRIES.size() + " entries (loose drops present on this machine: " + compared + ")");
	}

	// ------------------------------------------------------------------------------------------------
	// 5. Keying is exact: a one-character change in the last user message is a strict MISS
	// ------------------------------------------------------------------------------------------------

	@Test
	public void testOneCharacterMutationOfLastUserMessageIsAStrictMissNotAZipHit() throws Exception {
		/// Deployment-wide strict so a miss cannot fall through to the synthesizer.
		LlmEmulator.configure(realRoot.toString(), null, true);
		LlmEmulator.resetCounters();
		Path setDir = LlmEmulator.resolveUnder(LlmEmulator.fixtureRoot(), SET);
		String setUrl = LlmEmulator.SCHEME + SET;

		String entryName = BY_KIND.firstEntry().getValue().get(0);
		Map<String, Object> fx = ENTRIES.get(entryName);

		/// Positive control: the exact recorded request is served under strict mode.
		assertReplaysExactly(setUrl, entryName, fx, "strict positive control");

		/// Mutate exactly one character of the LAST user message (flip the case of the first ASCII letter).
		OpenAIRequest mutated = requestFrom(fx);
		List<OpenAIMessage> msgs = mutated.getMessages();
		int lastUser = -1;
		for (int i = msgs.size() - 1; i >= 0; i--) {
			if ("user".equalsIgnoreCase(msgs.get(i).getRole())) { lastUser = i; break; }
		}
		assertTrue("fixture must carry a user message", lastUser >= 0);
		String original = msgs.get(lastUser).getContent();
		int idx = -1;
		for (int i = 0; i < original.length(); i++) {
			char c = original.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) { idx = i; break; }
		}
		assertTrue("user message must contain an ASCII letter to flip", idx >= 0);
		char c = original.charAt(idx);
		char flipped = Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c);
		String changed = original.substring(0, idx) + flipped + original.substring(idx + 1);
		assertEquals("exactly one character differs", original.length(), changed.length());
		assertNotEquals(original, changed);
		/// OpenAIMessage wraps the underlying record by shared field refs, so this mutates the request.
		msgs.get(lastUser).setContent(changed);
		assertEquals("mutation must have reached the request", changed, LlmEmulator.lastMessageContent(mutated, "user"));

		String origKey = keyOf(entryName);
		String mutKey = LlmEmulator.requestKey(mutated);
		assertNotEquals("a one-character change must change the key", origKey, mutKey);
		assertFalse("premise: no loose fixture may exist for the mutated key", Files.exists(setDir.resolve(mutKey + ".json")));
		assertNull("premise: no zip entry may exist for the mutated key", LlmEmulator.zipFixtures(setDir).get(mutKey + ".json"));

		Map<String, Long> before = LlmEmulator.stats();
		HttpResponse<Stream<String>> resp = LlmEmulator.respond(setUrl, mutated, null).get();
		assertEquals("a strict miss is an emulated HTTP 500", 500, resp.statusCode());
		String body = rawBody(resp);
		assertTrue("strict miss body must say so: " + body, body.contains("fixture miss (strict)"));
		assertTrue("strict miss must name the mutated key: " + body, body.contains("key=" + mutKey));
		assertFalse("the recorded content must not leak into a miss", body.contains(recordedContent(fx).substring(0, Math.min(40, recordedContent(fx).length()))));
		Map<String, Long> after = LlmEmulator.stats();
		assertEquals("miss must increment by exactly 1", before.get("miss") + 1, (long) after.get("miss"));
		assertEquals("hit must not change on a miss", before.get("hit"), after.get("hit"));
		assertEquals("strict never synthesizes", before.get("synth"), after.get("synth"));
		assertEquals(before.get("fault"), after.get("fault"));
	}

	// ------------------------------------------------------------------------------------------------
	// 6. Manifest parses (independently and through the production loader) and recordingGaps are well-formed
	// ------------------------------------------------------------------------------------------------

	@Test
	@SuppressWarnings("unchecked")
	public void testManifestParsesAndRecordingGapsAreWellFormed() throws Exception {
		Path mf = realSetDir.resolve(LlmEmulator.MANIFEST_FILE);
		Map<String, Object> m = MAPPER.readValue(Files.readAllBytes(mf), new TypeReference<Map<String, Object>>() { });
		assertNotNull(m);
		assertTrue("manifest.strict must be a boolean", m.get("strict") instanceof Boolean);
		assertTrue("manifest.kinds must be an object", m.get("kinds") instanceof Map);
		assertTrue("manifest.faults must be a list", m.get("faults") instanceof List);
		assertTrue("manifest.recordingGaps must be a list", m.get("recordingGaps") instanceof List);

		List<Object> gaps = (List<Object>) m.get("recordingGaps");
		for (int i = 0; i < gaps.size(); i++) {
			assertTrue("recordingGaps[" + i + "] must be an object", gaps.get(i) instanceof Map);
			Map<String, Object> g = (Map<String, Object>) gaps.get(i);
			assertTrue("recordingGaps[" + i + "].chapter must be an integer", g.get("chapter") instanceof Integer);
			assertTrue("recordingGaps[" + i + "].chapter must be >= 1 (1-based)", ((Integer) g.get("chapter")) >= 1);
			assertTrue("recordingGaps[" + i + "].fromChunk must be an integer", g.get("fromChunk") instanceof Integer);
			assertTrue("recordingGaps[" + i + "].fromChunk must be >= 0 (0-based)", ((Integer) g.get("fromChunk")) >= 0);
			assertTrue("recordingGaps[" + i + "].reason must be a string", g.get("reason") instanceof String);
			assertFalse("recordingGaps[" + i + "].reason must not be blank", ((String) g.get("reason")).isBlank());
		}

		/// The production loader must read the same file to the same strict flag with no fault entries
		/// (a fault in the committed set would break every replay run) and the default kind detectors intact.
		LlmEmulator.Manifest loaded = LlmEmulator.loadManifest(realSetDir);
		assertEquals("production manifest loader disagrees with the file on strict", m.get("strict"), loaded.strict);
		assertEquals("committed set must declare no faults", ((List<Object>) m.get("faults")).size(), loaded.faults.size());
		for (String kind : BY_KIND.keySet()) {
			assertTrue("every recorded kind must have a detector in the loaded manifest: " + kind, loaded.kinds.containsKey(kind));
		}
		logger.info("Manifest OK: strict=" + loaded.strict + " recordingGaps=" + gaps.size() + " faults=" + loaded.faults.size());
	}
}
