package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// Unit tests for the ARRAY side of PictureBookUtil's LLM-JSON salvage and for the single-shot
/// extraction's corrective retry. Pure functions — no DB, no LLM.
///
/// Motivating failure (2026-10-07, pictureBookWizardUx.spec.js against the Docker stack, qwen3:8b-jos
/// via LiteLLM, 191s generation): the model's 10-scene array carried ONE corrupted token inside
/// scene 4 — `"characters":[{"},{"name":...}]` — so JSONUtil.getList rejected the whole 5.6KB reply:
///   JsonMappingException: Unexpected character ('n' (code 110)): was expecting a colon ...
///   column: 2739 (through reference chain: java.util.ArrayList[4])
/// parseLlmJsonArray returned an empty list, the async job reported COMPLETED, and the wizard showed
/// zero scenes. The chunked path had salvage + a corrective retry; the short-text single-shot path
/// had neither. The real reply is checked in as the fixture so this exact shape stays covered.
///
/// Same-package because the helpers under test are package-private (see TestLlmJsonSalvage).
public class TestLlmJsonArraySalvage {
	public static final Logger logger = LogManager.getLogger(TestLlmJsonArraySalvage.class);

	private static final String CTX = "extract-scenes:test-work";
	private static final String FIXTURE = "/llm-fixtures/malformed-array/wizard-2026-10-07-glitched-element.json";

	private static String fixture() {
		try (InputStream is = TestLlmJsonArraySalvage.class.getResourceAsStream(FIXTURE)) {
			assertNotNull("fixture missing: " + FIXTURE, is);
			return new String(is.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	/// Failure records are JSONUtil.exportObject output (pretty-printed), so read them back as a
	/// map instead of substring-matching the serialized form.
	@SuppressWarnings("unchecked")
	private static Map<String, Object> record(String rec) {
		Map<String, Object> m = JSONUtil.getLenientMap(rec.getBytes(StandardCharsets.UTF_8), String.class, Object.class, null);
		assertNotNull("failure record must be JSON: " + rec, m);
		return m;
	}

	private static String cleanArray(int n) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append(',');
			sb.append("{\"index\":").append(i).append(",\"title\":\"Scene ").append(i)
			  .append("\",\"blurb\":\"Blurb ").append(i).append("\",\"characters\":[{\"name\":\"A\",\"role\":\"lead\"}]}");
		}
		return sb.append(']').toString();
	}

	// ── parseLlmJsonArray: the real captured reply ─────────────────────────────

	/// The exact production reply. The whole-array parse MUST fail (that is the fixture's point),
	/// and the scenes before the corrupted element must come back instead of nothing.
	@Test
	public void TestGlitchedElementRealReplySalvagesPrecedingScenes() {
		String raw = fixture();
		assertTrue("fixture must still carry the corrupted token", raw.contains("[{\"},{\"name\""));
		boolean[] ok = new boolean[1];
		List<String> failures = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.parseLlmJsonArray(raw, CTX, failures, ok);
		assertFalse("a reply that only salvages must NOT report ok (the caller uses ok to decide on a retry)", ok[0]);
		assertTrue("expected at least the 4 scenes preceding the corrupted element, got " + scenes.size(), scenes.size() >= 4);
		assertEquals("Valentines Day Singles Event", scenes.get(0).get("title"));
		for (Map<String, Object> s : scenes) {
			assertNotNull("every salvaged scene must carry a title", s.get("title"));
			assertNotNull("every salvaged scene must carry a blurb", s.get("blurb"));
		}
		assertEquals("one failure record describing the salvage", 1, failures.size());
		Map<String, Object> rec = record(failures.get(0));
		assertEquals("the record must be typed parse", "parse", rec.get("kind"));
		assertTrue("the record must say what was salvaged", String.valueOf(rec.get("error")).contains("Salvaged " + scenes.size()));
		assertEquals("the record must keep the raw reply for a redo", raw, rec.get("rawResponse"));
		logger.info("salvaged " + scenes.size() + " of 10 scenes from the real glitched reply");
	}

	/// Regression guard for the old behaviour: a clean array parses as a whole, ok is true, no
	/// failure is recorded, and nothing is lost.
	@Test
	public void TestCleanArrayParsesWhole() {
		boolean[] ok = new boolean[1];
		List<String> failures = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.parseLlmJsonArray(
			"```json\n" + cleanArray(3) + "\n```", CTX, failures, ok);
		assertTrue(ok[0]);
		assertEquals(3, scenes.size());
		assertTrue(failures.isEmpty());
	}

	/// The 3-arg overload (TestLlmEmulator and the chunked path's callers) keeps its contract and
	/// now also salvages.
	@Test
	public void TestThreeArgOverloadSalvages() {
		List<String> failures = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.parseLlmJsonArray(fixture(), CTX, failures);
		assertTrue(scenes.size() >= 4);
		assertEquals(1, failures.size());
	}

	/// Token-ceiling truncation: the reply is cut mid-string inside element 3, with no closing
	/// bracket at all (the real shape). lastIndexOf(']') then lands on element 2's inner
	/// characters array, so the walk meets an element that never closes; that tail is repaired
	/// (repairTruncatedJson) rather than dropped, mirroring the object-side behaviour
	/// TestLlmJsonSalvage covers, and the finished elements survive.
	@Test
	public void TestTruncatedArrayRecoversFinishedElements() {
		String clean = cleanArray(3);
		String truncated = clean.substring(0, clean.indexOf("\"blurb\":\"Blurb 2\"") + 12);
		boolean[] ok = new boolean[1];
		List<String> failures = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.parseLlmJsonArray(truncated, CTX, failures, ok);
		assertFalse(ok[0]);
		assertEquals("the 2 scenes the model finished must be recovered", 2, scenes.size());
		assertEquals("Scene 0", scenes.get(0).get("title"));
		assertEquals("Scene 1", scenes.get(1).get("title"));
		assertEquals(1, failures.size());
		assertEquals("parse", record(failures.get(0)).get("kind"));
	}

	/// A corrupted element in the middle that does NOT break string parity: the elements on both
	/// sides are recovered and only the bad one is dropped.
	@Test
	public void TestBadMiddleElementDropsOnlyThatElement() {
		String arr = "[{\"title\":\"One\"},{\"title\":\"Two\" \"blurb\":\"missing colon and comma\"},{\"title\":\"Three\"}]";
		int[] dropped = new int[1];
		List<Map<String, Object>> scenes = PictureBookUtil.salvageArrayElements(arr, dropped);
		assertEquals(2, scenes.size());
		assertEquals("One", scenes.get(0).get("title"));
		assertEquals("Three", scenes.get(1).get("title"));
		assertEquals(1, dropped[0]);
	}

	/// No JSON at all is still a no-json failure with an empty result (unchanged contract).
	@Test
	public void TestNoArrayIsNoJsonFailure() {
		boolean[] ok = new boolean[1];
		List<String> failures = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.parseLlmJsonArray("I cannot help with that.", CTX, failures, ok);
		assertFalse(ok[0]);
		assertTrue(scenes.isEmpty());
		assertEquals(1, failures.size());
		assertEquals("no-json", record(failures.get(0)).get("kind"));
	}

	// ── extractSingleShot: the corrective retry ────────────────────────────────

	private static Map<String, String> vars() {
		Map<String, String> v = new LinkedHashMap<>();
		v.put("count", "10");
		v.put("text", "The short text of the work.");
		return v;
	}

	/// Attempt 1 is the real glitched reply; attempt 2 answers cleanly. The clean reply wins, and
	/// attempt 2 must have carried the corrective instruction on the text var (a byte-identical
	/// re-issue would only have sampling luck to rely on).
	@Test
	public void TestMalformedFirstReplyIsRetriedWithHintAndCleanRetryWins() {
		AtomicInteger calls = new AtomicInteger();
		List<Map<String, String>> seen = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.extractSingleShot(v -> {
			seen.add(v);
			return calls.incrementAndGet() == 1 ? fixture() : cleanArray(10);
		}, vars(), CTX, failures, null);
		assertEquals("exactly one retry", 2, calls.get());
		assertEquals("the fully parsed retry must win over the salvage", 10, scenes.size());
		assertEquals("Scene 0", scenes.get(0).get("title"));
		assertTrue("attempt 1 is the unmodified prompt", seen.get(0).get("text").equals("The short text of the work."));
		assertTrue("attempt 2 must carry the corrective instruction",
			seen.get(1).get("text").startsWith("The short text of the work.")
			&& seen.get(1).get("text").contains("could not be parsed as JSON"));
		assertTrue("a clean reply leaves no failure record", failures.isEmpty());
	}

	/// A null reply is a timeout / refusal / unreachable server / KI-74 abort: NEVER retried (the
	/// chunked path's rule), and a breadcrumb explains the empty result.
	@Test
	public void TestNullReplyIsNotRetried() {
		AtomicInteger calls = new AtomicInteger();
		List<String> failures = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.extractSingleShot(v -> {
			calls.incrementAndGet();
			return null;
		}, vars(), CTX, failures, null);
		assertEquals(1, calls.get());
		assertTrue(scenes.isEmpty());
		assertEquals(1, failures.size());
		assertEquals("empty", record(failures.get(0)).get("kind"));
	}

	/// A cancel between the attempts stops the retry; the salvage from attempt 1 is still returned
	/// with its failure record so the caller can show what it got.
	@Test
	public void TestCancelBetweenAttemptsSkipsRetryAndKeepsSalvage() {
		SummarizeProgress token = new SummarizeProgress();
		AtomicInteger calls = new AtomicInteger();
		List<String> failures = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.extractSingleShot(v -> {
			calls.incrementAndGet();
			token.cancel();
			return fixture();
		}, vars(), CTX, failures, token);
		assertEquals("no retry after a cancel", 1, calls.get());
		assertTrue("the salvage from attempt 1 is kept", scenes.size() >= 4);
		assertEquals(1, failures.size());
		assertEquals("parse", record(failures.get(0)).get("kind"));
	}

	/// Both attempts malformed: the larger salvage wins and only ITS failure record is kept.
	@Test
	public void TestBothMalformedKeepsLargerSalvage() {
		AtomicInteger calls = new AtomicInteger();
		List<String> failures = new ArrayList<>();
		String smallBroken = "[{\"title\":\"Only\"},{\"title\":\"Broken\" \"x\":1}]";
		List<Map<String, Object>> scenes = PictureBookUtil.extractSingleShot(v ->
			calls.incrementAndGet() == 1 ? smallBroken : fixture(), vars(), CTX, failures, null);
		assertEquals(2, calls.get());
		assertTrue("the fixture salvages more than the 1-element reply", scenes.size() >= 4);
		assertEquals("Valentines Day Singles Event", scenes.get(0).get("title"));
		assertEquals("one failure record, for the attempt whose result is returned", 1, failures.size());
		assertTrue(String.valueOf(record(failures.get(0)).get("rawResponse")).contains("Valentines Day Singles Event"));
	}

	/// Retry returned null (e.g. the retry timed out): fall back to attempt 1's salvage.
	@Test
	public void TestNullRetryFallsBackToFirstSalvage() {
		AtomicInteger calls = new AtomicInteger();
		List<String> failures = new ArrayList<>();
		List<Map<String, Object>> scenes = PictureBookUtil.extractSingleShot(v ->
			calls.incrementAndGet() == 1 ? fixture() : null, vars(), CTX, failures, null);
		assertEquals(2, calls.get());
		assertTrue(scenes.size() >= 4);
		assertEquals(1, failures.size());
	}
}
