package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.cote.accountmanager.olio.picturebook.PbConfigUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.junit.Test;

/**
 * GAP 1 (PictureBook2ChapBookGapAnalysis-2026-08-31, §config precedence): proves that a per-node
 * {@code configOverride} WINS a conflicting book-tier {@code sdConfig} value in
 * {@link PbConfigUtil#resolveEffectiveConfig}.
 * <p>
 * {@code resolveEffectiveConfig} implements a four-tier merge (node {@code configOverride} sparse JSON
 * &rarr; book {@code sdConfig}/{@code compositeSdConfig} &rarr; {@code olio/sd/flux2Defaults.json} &rarr;
 * {@code Flux2Defaults} constants). Nothing else in the suite proved the top tier actually beats the book
 * tier for a field they both set — this does.
 * <p>
 * <b>Pure in-memory, no LAN.</b> The records are built with {@code RecordFactory.newInstance} and merged
 * directly; no DB write, no LLM, no SD server. {@code bookConfig}&rarr;{@code ensureFullSdConfig} only
 * re-fetches from the DB when the config lacks a {@code style} field, and a
 * {@code newInstance(olio.sd.config)} materialises {@code style}, so no query is issued.
 * <p>
 * <b>Field chosen: {@code steps}</b> — a real {@code olio.sd.config int} (schema default 20) that
 * {@code applyFlux2Defaults} does NOT touch (that method fills only the six {@code flux2*} knobs), so it
 * cleanly isolates node-vs-book precedence. Both test values (book 11, node 42) differ from the schema
 * default 20 on purpose: {@code RecordSerializer} omits a numeric value equal to its schema default, so a
 * value of 20 in the sparse override would serialise identically to "unset" and prove nothing.
 */
public class TestPbConfigPrecedence extends BaseTest {

	/** Distinctive, non-default int values so serialization cannot elide them. */
	private static final int BOOK_STEPS = 11;
	private static final int NODE_STEPS = 42;
	private static final int SCHEMA_DEFAULT_STEPS = 20;

	@Test
	public void testNodeConfigOverrideWinsBookSdConfig() throws Exception {
		// ── Book tier: a full olio.sd.config carrying steps=11, hung on the book's sdConfig FK ──
		BaseRecord bookCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		bookCfg.set("steps", BOOK_STEPS);
		assertEquals("sanity: book config carries steps=11", Integer.valueOf(BOOK_STEPS), bookCfg.get("steps"));

		BaseRecord book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
		book.set(OlioFieldNames.FIELD_PB_SD_CONFIG, bookCfg);

		// ── Node tier: a SPARSE configOverride setting the SAME field to a DIFFERENT value (42) ──
		BaseRecord overrideCfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		overrideCfg.set("steps", NODE_STEPS);
		String sparse = PbConfigUtil.sparseOverride(overrideCfg, Arrays.asList("steps"));
		assertNotNull("sparseOverride must produce a non-null JSON string for a non-default value", sparse);
		assertTrue("sparse override JSON must contain the overridden value 42, got: " + sparse,
			sparse.contains("42"));
		// It must be a sparse string carrying ONLY steps — never a full materialized record (that would
		// make "override" indistinguishable from "default"). A materialized record would also carry the
		// book value's neighbour fields; the sparse form must not.
		assertFalse("sparse override must NOT carry the book value 11", sparse.contains("11"));

		BaseRecord node = RecordFactory.newInstance(OlioModelNames.MODEL_PB_NODE);
		node.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, sparse);

		// ── Merge: node override must WIN ─────────────────────────────────────────────────────────
		BaseRecord effective = PbConfigUtil.resolveEffectiveConfig(book, node, false);
		assertNotNull("resolveEffectiveConfig must never return null", effective);
		int mergedSteps = ((Number) effective.get("steps")).intValue();
		logger.info("Precedence merge: book steps={}, node override steps={}, effective steps={}",
			BOOK_STEPS, NODE_STEPS, mergedSteps);
		assertEquals("Node configOverride (steps=42) MUST win the merge over the book sdConfig (steps=11)",
			NODE_STEPS, mergedSteps);
		assertFalse("The merged value must NOT be the book-tier value 11 (override was ignored)",
			mergedSteps == BOOK_STEPS);
		assertFalse("The merged value must NOT be the schema default 20 (nothing applied)",
			mergedSteps == SCHEMA_DEFAULT_STEPS);

		// ── Control: with NO node override, the book tier value must show through (11, not 42) ──────
		// This proves the book value was genuinely present, so the 42 above is the override winning —
		// not a coincidence where 42 was already the effective value.
		BaseRecord effectiveNoNode = PbConfigUtil.resolveEffectiveConfig(book, null, false);
		int bookOnlySteps = ((Number) effectiveNoNode.get("steps")).intValue();
		assertEquals("With no node override, the book sdConfig value (steps=11) must be effective",
			BOOK_STEPS, bookOnlySteps);
	}
}
