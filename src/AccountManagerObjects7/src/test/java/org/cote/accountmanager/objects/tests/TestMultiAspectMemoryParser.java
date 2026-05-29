package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.olio.llm.MultiAspectMemoryParser;
import org.cote.accountmanager.olio.llm.MultiAspectMemoryParser.AspectDraft;
import org.junit.Test;

/// Phase 3 (ConversationQualityPlan) unit tests for the multi-aspect
/// memory-extraction parser. Pure-function tests — no DB, no LLM.
public class TestMultiAspectMemoryParser {

	// ── stripCodeFences ─────────────────────────────────────────────

	@Test
	public void stripFences_noFence_passThrough() {
		assertEquals("{\"a\":1}", MultiAspectMemoryParser.stripCodeFences("{\"a\":1}"));
	}

	@Test
	public void stripFences_plainTripleBacktick() {
		String in = "```\n{\"a\":1}\n```";
		assertEquals("{\"a\":1}", MultiAspectMemoryParser.stripCodeFences(in));
	}

	@Test
	public void stripFences_jsonLanguageTag() {
		String in = "```json\n{\"a\":1}\n```";
		assertEquals("{\"a\":1}", MultiAspectMemoryParser.stripCodeFences(in));
	}

	@Test
	public void stripFences_nullSafe() {
		assertEquals("", MultiAspectMemoryParser.stripCodeFences(null));
	}

	@Test
	public void stripFences_emptySafe() {
		assertEquals("", MultiAspectMemoryParser.stripCodeFences(""));
		assertEquals("", MultiAspectMemoryParser.stripCodeFences("   "));
	}

	// ── parse: failure modes return empty ───────────────────────────

	@Test
	public void parse_nullReturnsEmpty() {
		assertTrue(MultiAspectMemoryParser.parse(null).isEmpty());
	}

	@Test
	public void parse_emptyReturnsEmpty() {
		assertTrue(MultiAspectMemoryParser.parse("").isEmpty());
		assertTrue(MultiAspectMemoryParser.parse("   ").isEmpty());
	}

	@Test
	public void parse_malformedJsonReturnsEmpty() {
		assertTrue(MultiAspectMemoryParser.parse("not json at all").isEmpty());
		assertTrue(MultiAspectMemoryParser.parse("{unclosed").isEmpty());
	}

	@Test
	public void parse_jsonArrayReturnsEmpty() {
		/// We expect a JSON object, not array.
		assertTrue(MultiAspectMemoryParser.parse("[{\"a\":1}]").isEmpty());
	}

	@Test
	public void parse_allNullAspectsReturnsEmpty() {
		String in = "{\"new_fact\":null,\"decision\":null,\"tension\":null,\"relationship_change\":null}";
		assertTrue(MultiAspectMemoryParser.parse(in).isEmpty());
	}

	@Test
	public void parse_missingAspectsReturnsEmpty() {
		/// Object with NO recognized aspect keys.
		String in = "{\"foo\":\"bar\",\"baz\":42}";
		assertTrue(MultiAspectMemoryParser.parse(in).isEmpty());
	}

	// ── parse: extraction ───────────────────────────────────────────

	@Test
	public void parse_singleAspectExtracted() {
		String in = "{\"new_fact\":{\"content\":\"Elena from Alder\",\"importance\":5}}";
		List<AspectDraft> r = MultiAspectMemoryParser.parse(in);
		assertEquals(1, r.size());
		assertEquals("new_fact", r.get(0).aspectKey);
		assertEquals("FACT", r.get(0).memoryTypeName);
		assertEquals("Elena from Alder", r.get(0).content);
		assertEquals(5, r.get(0).importance);
	}

	@Test
	public void parse_allFourAspectsExtractedInOrder() {
		String in = "{"
			+ "\"new_fact\":{\"content\":\"f1\",\"importance\":3},"
			+ "\"decision\":{\"content\":\"d1\",\"importance\":7},"
			+ "\"tension\":{\"content\":\"t1\",\"importance\":6},"
			+ "\"relationship_change\":{\"content\":\"r1\",\"importance\":4}"
			+ "}";
		List<AspectDraft> r = MultiAspectMemoryParser.parse(in);
		assertEquals(4, r.size());
		assertEquals("FACT",         r.get(0).memoryTypeName);
		assertEquals("DECISION",     r.get(1).memoryTypeName);
		assertEquals("INSIGHT",      r.get(2).memoryTypeName);
		assertEquals("RELATIONSHIP", r.get(3).memoryTypeName);
	}

	@Test
	public void parse_someNullSomeValid() {
		String in = "{"
			+ "\"new_fact\":null,"
			+ "\"decision\":{\"content\":\"keep walking\",\"importance\":4},"
			+ "\"tension\":null,"
			+ "\"relationship_change\":{\"content\":\"warmer\",\"importance\":5}"
			+ "}";
		List<AspectDraft> r = MultiAspectMemoryParser.parse(in);
		assertEquals(2, r.size());
		assertEquals("DECISION", r.get(0).memoryTypeName);
		assertEquals("RELATIONSHIP", r.get(1).memoryTypeName);
	}

	@Test
	public void parse_strippedCodeFences() {
		String in = "```json\n{\"new_fact\":{\"content\":\"x\",\"importance\":5}}\n```";
		List<AspectDraft> r = MultiAspectMemoryParser.parse(in);
		assertEquals(1, r.size());
		assertEquals("x", r.get(0).content);
	}

	// ── importance handling ─────────────────────────────────────────

	@Test
	public void parse_missingImportanceDefaultsTo5() {
		String in = "{\"new_fact\":{\"content\":\"hello\"}}";
		List<AspectDraft> r = MultiAspectMemoryParser.parse(in);
		assertEquals(1, r.size());
		assertEquals(MultiAspectMemoryParser.DEFAULT_IMPORTANCE, r.get(0).importance);
	}

	@Test
	public void parse_importanceClampedHigh() {
		String in = "{\"new_fact\":{\"content\":\"hello\",\"importance\":99}}";
		List<AspectDraft> r = MultiAspectMemoryParser.parse(in);
		assertEquals(1, r.size());
		assertEquals(10, r.get(0).importance);
	}

	@Test
	public void parse_importanceClampedLow() {
		String in = "{\"new_fact\":{\"content\":\"hello\",\"importance\":-3}}";
		List<AspectDraft> r = MultiAspectMemoryParser.parse(in);
		assertEquals(1, r.size());
		assertEquals(1, r.get(0).importance);
	}

	@Test
	public void parse_importanceNonNumericIgnored() {
		String in = "{\"new_fact\":{\"content\":\"hello\",\"importance\":\"high\"}}";
		List<AspectDraft> r = MultiAspectMemoryParser.parse(in);
		assertEquals(1, r.size());
		assertEquals(MultiAspectMemoryParser.DEFAULT_IMPORTANCE, r.get(0).importance);
	}

	// ── content edge cases ─────────────────────────────────────────

	@Test
	public void parse_emptyContentSkipped() {
		String in = "{\"new_fact\":{\"content\":\"\",\"importance\":5}}";
		assertTrue(MultiAspectMemoryParser.parse(in).isEmpty());
	}

	@Test
	public void parse_whitespaceOnlyContentSkipped() {
		String in = "{\"new_fact\":{\"content\":\"   \",\"importance\":5}}";
		assertTrue(MultiAspectMemoryParser.parse(in).isEmpty());
	}

	@Test
	public void parse_missingContentSkipped() {
		String in = "{\"new_fact\":{\"importance\":5}}";
		assertTrue(MultiAspectMemoryParser.parse(in).isEmpty());
	}

	@Test
	public void parse_aspectAsScalarSkipped() {
		/// aspect should be object or null; a string at that key is rejected
		String in = "{\"new_fact\":\"this is wrong format\"}";
		assertTrue(MultiAspectMemoryParser.parse(in).isEmpty());
	}

	@Test
	public void parse_partialOneValidOneMalformedKeepsValid() {
		String in = "{"
			+ "\"new_fact\":\"malformed\","
			+ "\"decision\":{\"content\":\"good one\",\"importance\":7}"
			+ "}";
		List<AspectDraft> r = MultiAspectMemoryParser.parse(in);
		assertEquals(1, r.size());
		assertEquals("DECISION", r.get(0).memoryTypeName);
		assertEquals("good one", r.get(0).content);
	}
}
