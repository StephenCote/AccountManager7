package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Test;

/// Unit tests for PictureBookUtil's LLM-JSON salvage. Pure functions — no DB, no LLM.
///
/// Lives in the production package (not objects.tests) because the salvage helpers are
/// package-private; same convention as BookContextTestAccess in this package.
///
/// Motivating failure: a 17-chunk extraction logged
/// "Chunk extract-scenes-chunk:10/17 returned unparseable JSON — retrying once", costing a full
/// extra ~90s generation. The chunk prompt carries every previously identified scene forward
/// against a fixed num_ctx of 8192, so the reply budget shrinks as a run proceeds and later chunks
/// are the ones that truncate — which is why it hit chunk 10 and not chunk 1. The old salvage did
/// a `lastIndexOf('}')` slice, which on a truncated reply lands on an INNER brace and produces an
/// unbalanced fragment no parser can read.
public class TestLlmJsonSalvage {
	public static final Logger logger = LogManager.getLogger(TestLlmJsonSalvage.class);

	private static final String CTX = "extract-scenes-chunk:10/17";

	private Map<String, Object> parse(String resp, boolean[] ok) {
		return PictureBookUtil.parseLlmJsonObject(resp, CTX, new ArrayList<String>(), ok);
	}

	// ── truncation: the actual production failure ──────────────────────────────

	/// A reply cut off mid-string inside a nested array, exactly the shape a token-limit
	/// truncation produces. The scenes already emitted must survive.
	@Test
	public void TestTruncatedMidStringIsRecovered() {
		String truncated =
			"{\"additions\":[{\"title\":\"The Bar at Dusk\",\"blurb\":\"Neon bleeds across wet glass.\","
			+ "\"setting\":\"An upscale bar\",\"action\":\"She sets her phone face-up\",\"mood\":\"noir\"},"
			+ "{\"title\":\"The Toast\",\"blurb\":\"He raises a glass to nobody in particu";
		boolean[] ok = new boolean[1];
		Map<String, Object> m = parse(truncated, ok);
		assertTrue("a truncated reply must be repaired and parsed, not discarded", ok[0]);
		assertNotNull(m);
		Object add = m.get("additions");
		assertTrue(add instanceof List);
		List<?> scenes = (List<?>) add;
		assertEquals("both partially-emitted scenes must be recovered", 2, scenes.size());
	}

	/// Truncated between elements, leaving a dangling comma.
	@Test
	public void TestTruncatedAfterCommaIsRecovered() {
		String truncated = "{\"additions\":[{\"title\":\"One\"},";
		boolean[] ok = new boolean[1];
		Map<String, Object> m = parse(truncated, ok);
		assertTrue(ok[0]);
		assertTrue(m.get("additions") instanceof List);
		assertEquals(1, ((List<?>) m.get("additions")).size());
	}

	/// Truncated immediately after a key's colon — no value at all.
	@Test
	public void TestTruncatedAfterColonIsRecovered() {
		boolean[] ok = new boolean[1];
		Map<String, Object> m = parse("{\"additions\":[],\"revisions\":", ok);
		assertTrue(ok[0]);
		assertNotNull(m);
	}

	/// The regression guard for the root cause: lastIndexOf('}') would slice to the INNER brace
	/// and yield "{\"additions\":[{\"title\":\"One\"}" — unbalanced. findBalancedEnd must report
	/// "no balanced end" so the repair path runs instead.
	@Test
	public void TestFindBalancedEndDetectsTruncation() {
		String truncated = "{\"additions\":[{\"title\":\"One\"}";
		assertEquals("truncated object must report no balanced end",
			-1, PictureBookUtil.findBalancedEnd(truncated, 0));
		assertTrue("the misleading inner brace really is the last one",
			truncated.lastIndexOf('}') < truncated.length());
	}

	@Test
	public void TestFindBalancedEndIgnoresBracesInsideStrings() {
		String s = "{\"blurb\":\"a { brace } inside prose\"} trailing junk";
		int end = PictureBookUtil.findBalancedEnd(s, 0);
		assertEquals("must close at the real end, not at a brace inside a string",
			'}', s.charAt(end));
		assertEquals(s.indexOf("} trailing"), end);
	}

	// ── lenient-parse artifacts ────────────────────────────────────────────────

	@Test
	public void TestTrailingCommaParses() {
		boolean[] ok = new boolean[1];
		Map<String, Object> m = parse("{\"additions\":[],\"revisions\":[],\"removals\":[],}", ok);
		assertTrue("a trailing comma must not fail the whole chunk", ok[0]);
		assertEquals(3, m.size());
	}

	/// A raw newline inside a string value — very common in multi-sentence blurbs, and a hard
	/// error under the strict reader.
	@Test
	public void TestRawNewlineInStringParses() {
		boolean[] ok = new boolean[1];
		Map<String, Object> m = parse("{\"blurb\":\"line one\nline two\"}", ok);
		assertTrue("an unescaped newline inside a string must not fail the chunk", ok[0]);
		assertNotNull(m.get("blurb"));
	}

	@Test
	public void TestSingleQuotedStringsParse() {
		boolean[] ok = new boolean[1];
		Map<String, Object> m = parse("{'additions':[],'revisions':[]}", ok);
		assertTrue(ok[0]);
		assertEquals(2, m.size());
	}

	// ── fences and think blocks ────────────────────────────────────────────────

	/// The old code only stripped a fence when the reply STARTED with one, so a preamble defeated
	/// it and the brace slice had to rescue the result.
	@Test
	public void TestFencedJsonWithPreambleParses() {
		String resp = "Here is the JSON you asked for:\n```json\n{\"additions\":[],\"revisions\":[]}\n```";
		boolean[] ok = new boolean[1];
		Map<String, Object> m = parse(resp, ok);
		assertTrue("a preamble before the fence must not defeat parsing", ok[0]);
		assertEquals(2, m.size());
	}

	@Test
	public void TestStripCodeFencesRemovesFencesAnywhere() {
		assertEquals("{\"a\":1}", PictureBookUtil.stripCodeFences("```json\n{\"a\":1}\n```"));
		assertEquals("{\"a\":1}", PictureBookUtil.stripCodeFences("{\"a\":1}"));
	}

	/// A preamble containing a brace: the brace slice alone cannot rescue this, so fence
	/// stripping has to happen first.
	@Test
	public void TestPreambleContainingBraceStillParses() {
		String resp = "I will return a {json} object now:\n```\n{\"additions\":[]}\n```";
		boolean[] ok = new boolean[1];
		Map<String, Object> m = parse(resp, ok);
		assertTrue(ok[0]);
		assertTrue(m.containsKey("additions"));
	}

	// ── the empty-vs-failed distinction (saves a whole LLM round) ───────────────

	/// "No new scenes in this chunk" is a CORRECT answer. It used to be treated as unparseable,
	/// burning a second ~90s generation and then recording a bogus failedExtractions entry.
	@Test
	public void TestLegitimateEmptyObjectIsSuccessNotFailure() {
		boolean[] ok = new boolean[1];
		List<String> failures = new ArrayList<>();
		Map<String, Object> m = PictureBookUtil.parseLlmJsonObject("{}", CTX, failures, ok);
		assertTrue("an empty object parsed fine — it must report success", ok[0]);
		assertTrue(m.isEmpty());
		assertTrue("a valid empty object must NOT be recorded as a failed extraction",
			failures.isEmpty());
	}

	@Test
	public void TestEmptyArraysObjectIsSuccess() {
		boolean[] ok = new boolean[1];
		List<String> failures = new ArrayList<>();
		PictureBookUtil.parseLlmJsonObject("{\"additions\":[],\"revisions\":[],\"removals\":[]}",
			CTX, failures, ok);
		assertTrue(ok[0]);
		assertTrue(failures.isEmpty());
	}

	// ── genuine failures still fail, and are reported usefully ─────────────────

	@Test
	public void TestProseWithNoJsonIsAFailure() {
		boolean[] ok = new boolean[1];
		List<String> failures = new ArrayList<>();
		Map<String, Object> m = PictureBookUtil.parseLlmJsonObject(
			"I need the actual story text before I can identify scenes.", CTX, failures, ok);
		assertFalse("prose with no JSON object must report failure", ok[0]);
		assertTrue(m.isEmpty());
		assertEquals("the failure must be recorded for investigation", 1, failures.size());
		assertTrue("the raw response must be captured", failures.get(0).contains("story text"));
	}

	/// A null/empty response previously produced NO failedExtractions entry at all — the chunk
	/// vanished behind a single WARN.
	@Test
	public void TestNullAndEmptyResponsesAreRecorded() {
		boolean[] ok = new boolean[1];
		List<String> failures = new ArrayList<>();
		PictureBookUtil.parseLlmJsonObject(null, CTX, failures, ok);
		assertFalse(ok[0]);
		PictureBookUtil.parseLlmJsonObject("", CTX, failures, ok);
		assertFalse(ok[0]);
		assertEquals("both a null and an empty response must be recorded", 2, failures.size());
	}

	// ── repair helper directly ─────────────────────────────────────────────────

	@Test
	public void TestRepairClosesNestedStructures() {
		assertEquals("{\"a\":[{\"b\":1}]}", PictureBookUtil.repairTruncatedJson("{\"a\":[{\"b\":1}]}"));
		assertEquals("{\"a\":[{\"b\":1}]}", PictureBookUtil.repairTruncatedJson("{\"a\":[{\"b\":1"));
		assertEquals("{\"a\":\"x\"}", PictureBookUtil.repairTruncatedJson("{\"a\":\"x"));
	}

	/// A dangling backslash would otherwise escape the quote the repair appends.
	@Test
	public void TestRepairDropsDanglingEscape() {
		String out = PictureBookUtil.repairTruncatedJson("{\"a\":\"x\\");
		assertEquals("{\"a\":\"x\"}", out);
	}

	@Test
	public void TestRepairIsIdempotentOnValidJson() {
		String valid = "{\"additions\":[],\"revisions\":[]}";
		assertEquals(valid, PictureBookUtil.repairTruncatedJson(valid));
	}
}
