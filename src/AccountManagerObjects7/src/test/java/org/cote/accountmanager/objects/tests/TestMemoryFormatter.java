package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.olio.llm.MemoryFormatter;
import org.cote.accountmanager.olio.llm.MemoryFormatter.MemoryDraft;
import org.junit.Test;

/// Phase 4 (ConversationQualityPlan) unit tests for the memory
/// system-section formatter. Pure-function tests — no DB / no LLM.
public class TestMemoryFormatter {

	private static MemoryDraft d(String type, String summary, String content) {
		return new MemoryDraft(type, summary, content, 5);
	}

	// ── empty / null inputs ─────────────────────────────────────────────

	@Test
	public void nullInputReturnsEmpty() {
		assertEquals("", MemoryFormatter.asSystemSection(null));
	}

	@Test
	public void emptyInputReturnsEmpty() {
		assertEquals("", MemoryFormatter.asSystemSection(new ArrayList<>()));
	}

	@Test
	public void allEmptyDisplayTextReturnsEmpty() {
		MemoryDraft nullSummary = new MemoryDraft("FACT", null, "", 5);
		MemoryDraft whitespace = new MemoryDraft("FACT", "   ", null, 5);
		assertEquals("", MemoryFormatter.asSystemSection(Arrays.asList(nullSummary, whitespace)));
	}

	// ── MemoryDraft.displayText ────────────────────────────────────────

	@Test
	public void draft_preferSummaryOverContent() {
		assertEquals("the summary",
			new MemoryDraft("FACT", "the summary", "the content", 5).displayText());
	}

	@Test
	public void draft_fallbackToContent() {
		assertEquals("the content",
			new MemoryDraft("FACT", null, "the content", 5).displayText());
		assertEquals("the content",
			new MemoryDraft("FACT", "", "the content", 5).displayText());
		assertEquals("the content",
			new MemoryDraft("FACT", "  ", "the content", 5).displayText());
	}

	@Test
	public void draft_emptyDisplayWhenBothAbsent() {
		assertEquals("", new MemoryDraft("FACT", null, null, 5).displayText());
		assertEquals("", new MemoryDraft("FACT", "", "", 5).displayText());
	}

	@Test
	public void draft_nullMemoryTypeDefaultsToNote() {
		assertEquals("NOTE", new MemoryDraft(null, "x", null, 5).memoryType);
	}

	// ── single-type output ──────────────────────────────────────────────

	@Test
	public void singleFactRenders() {
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(d("FACT", "Alder is from the Northwood", null)));
		assertTrue("expected '# What you know' header, got:\n" + out,
			out.contains("# What you know"));
		assertTrue("expected '## Facts' header, got:\n" + out,
			out.contains("## Facts"));
		assertTrue("expected fact text, got:\n" + out,
			out.contains("- Alder is from the Northwood"));
		assertTrue("expected default guidance, got:\n" + out,
			out.contains("# How to respond"));
	}

	@Test
	public void singleRelationshipRenders() {
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(d("RELATIONSHIP", "Tension over the upcoming visit", null)));
		assertTrue(out.contains("## Relationships"));
		assertTrue(out.contains("- Tension over the upcoming visit"));
	}

	@Test
	public void singleInsightLabelledAsUnresolved() {
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(d("INSIGHT", "Whose family will attend remains unsettled", null)));
		assertTrue("INSIGHT must surface under '## Unresolved', got:\n" + out,
			out.contains("## Unresolved"));
	}

	@Test
	public void unknownTypeBucketsUnderOther() {
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(d("PROPHECY", "the lantern will go dark at dawn", null)));
		assertTrue("expected '## Other' header, got:\n" + out,
			out.contains("## Other"));
		assertTrue(out.contains("- the lantern will go dark at dawn"));
	}

	// ── grouping + ordering ─────────────────────────────────────────────

	@Test
	public void multipleTypesGroupedAndOrdered() {
		String out = MemoryFormatter.asSystemSection(Arrays.asList(
			d("DECISION",     "agreed to bring photos", null),
			d("FACT",         "venue is the stone church", null),
			d("RELATIONSHIP", "warmer since last call", null)
		));
		int facts = out.indexOf("## Facts");
		int relationships = out.indexOf("## Relationships");
		int decisions = out.indexOf("## Recent decisions");
		assertTrue("all three headers must be present", facts >= 0 && relationships >= 0 && decisions >= 0);
		/// Headers ordered per TYPE_HEADERS map insertion: FACT, RELATIONSHIP, DECISION
		assertTrue("Facts must come before Relationships", facts < relationships);
		assertTrue("Relationships must come before Decisions", relationships < decisions);
	}

	@Test
	public void inputOrderPreservedWithinBucket() {
		String out = MemoryFormatter.asSystemSection(Arrays.asList(
			d("FACT", "first fact", null),
			d("FACT", "second fact", null),
			d("FACT", "third fact", null)
		));
		int first = out.indexOf("- first fact");
		int second = out.indexOf("- second fact");
		int third = out.indexOf("- third fact");
		assertTrue(first >= 0 && second > first && third > second);
	}

	@Test
	public void emptyBucketsNotEmitted() {
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(d("FACT", "alone in its bucket", null)));
		assertFalse("no Relationships header when no RELATIONSHIP drafts, got:\n" + out,
			out.contains("## Relationships"));
		assertFalse("no Other header when no unknown-type drafts, got:\n" + out,
			out.contains("## Other"));
	}

	// ── edge cases ──────────────────────────────────────────────────────

	@Test
	public void draftsWithEmptyDisplaySkippedNotEmittedBlank() {
		String out = MemoryFormatter.asSystemSection(Arrays.asList(
			d("FACT", "real fact", null),
			d("FACT", "   ", "  "),       // empty display — skipped
			d("FACT", null, "real content fallback")
		));
		assertTrue(out.contains("- real fact"));
		assertTrue(out.contains("- real content fallback"));
		assertFalse("must not emit blank bullets, got:\n" + out, out.contains("- \n"));
		assertFalse("must not emit space-only bullets, got:\n" + out, out.contains("-  \n"));
	}

	@Test
	public void nullDraftsInListSkipped() {
		List<MemoryDraft> ms = new ArrayList<>();
		ms.add(d("FACT", "kept", null));
		ms.add(null);
		ms.add(d("FACT", "also kept", null));
		String out = MemoryFormatter.asSystemSection(ms);
		assertTrue(out.contains("- kept"));
		assertTrue(out.contains("- also kept"));
	}

	@Test
	public void summaryTrimmedOnDisplay() {
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(d("FACT", "   leading and trailing spaces   ", null)));
		assertTrue(out.contains("- leading and trailing spaces\n"));
	}

	@Test
	public void customGuidanceRespected() {
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(d("FACT", "x", null)),
			"# Style\nbe terse");
		assertTrue("custom guidance, got:\n" + out, out.contains("# Style"));
		assertTrue(out.contains("be terse"));
		assertFalse("default guidance should be replaced, got:\n" + out,
			out.contains("# How to respond"));
	}

	@Test
	public void nullGuidanceOmitsClosingSection() {
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(d("FACT", "x", null)), null);
		assertTrue(out.contains("- x"));
		assertFalse(out.contains("# How to respond"));
	}

	@Test
	public void emptyGuidanceOmitsClosingSection() {
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(d("FACT", "x", null)), "");
		assertFalse(out.contains("# How to respond"));
	}

	@Test
	public void allEmptyDraftsButTypePresentReturnsEmpty() {
		/// Even though there's a FACT in the list, both summary & content are blank,
		/// so displayText() returns "" and nothing makes it into the buckets.
		String out = MemoryFormatter.asSystemSection(
			Arrays.asList(new MemoryDraft("FACT", "", "", 5)));
		assertEquals("", out);
	}

	@Test
	public void eventTypeAliasesOutcome() {
		/// Both OUTCOME and EVENT should land under "## Recent events"
		String out = MemoryFormatter.asSystemSection(Arrays.asList(
			d("OUTCOME", "they reached the river", null),
			d("EVENT", "the storm broke before dawn", null)
		));
		assertTrue(out.contains("## Recent events"));
		assertTrue(out.contains("- they reached the river"));
		assertTrue(out.contains("- the storm broke before dawn"));
		/// Exactly one occurrence of "## Recent events" header
		assertEquals(1, out.split("## Recent events", -1).length - 1);
	}
}
