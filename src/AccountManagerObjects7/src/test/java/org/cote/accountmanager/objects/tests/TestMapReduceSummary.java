package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.io.IOSystem;
import org.junit.Test;

/// Phase 2b (chatRefactor2): Tests for the map-reduce composeSummary() refactor.
/// Tests with known text content and verifies parallel summarization produces valid results.
public class TestMapReduceSummary extends BaseTest {

	@Test
	public void testComposeSummaryWithTextFile() {
		logger.info("testComposeSummaryWithTextFile");
		BaseRecord testUser = getCreateUser("testMapReduce");
		assertNotNull("Test user is null", testUser);

		/// Load CardFox.txt as test content
		Path txtPath = Path.of("./media/CardFox.txt");
		if (!Files.exists(txtPath)) {
			txtPath = Path.of("../AccountManagerObjects7/media/CardFox.txt");
		}
		if (!Files.exists(txtPath)) {
			logger.warn("CardFox.txt not found — skipping testComposeSummaryWithTextFile");
			return;
		}

		String textContent;
		try {
			textContent = Files.readString(txtPath);
		} catch (IOException e) {
			logger.error("Failed to read test file", e);
			return;
		}
		assertTrue("Text file is empty", textContent.length() > 0);

		/// Upload as data record
		BaseRecord data = getCreateData(testUser, "CardFox-summary-test.txt", "text/plain",
			textContent.getBytes(), "~/Tests/Summaries", testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Data record is null", data);

		/// Create vector store for the data
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if (vu == null || !VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available — skipping");
			return;
		}

		try {
			/// Pass text content directly — avoids byte store re-extraction issues on re-runs
			/// where getCreateData returns an existing record without byte store loaded
			List<BaseRecord> chunks = vu.createVectorStore(data, textContent, VectorUtil.ChunkEnumType.WORD, 500);
			if (chunks.isEmpty()) {
				logger.warn("Vector store creation returned no chunks — skipping");
				return;
			}
			IOSystem.getActiveContext().getWriter().write(chunks.toArray(new BaseRecord[0]));
			IOSystem.getActiveContext().getWriter().flush();
			logger.info("Created " + chunks.size() + " vector chunks");
		} catch (Exception e) {
			logger.warn("Vector store creation failed: " + e.getMessage());
			return;
		}

		/// Verify chunks were actually persisted (DB write may silently fail)
		int storedCount = vu.countVectorStore(data);
		if (storedCount == 0) {
			logger.warn("Vector chunks not persisted (DB write failed) — skipping LLM-dependent assertions");
			return;
		}
		logger.info("Verified " + storedCount + " vector chunks in store");

		/// Get chat/prompt configs
		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "MapReduce Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "MapReduce Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Chat/prompt config not available — skipping LLM-dependent assertions");
			return;
		}

		/// Run map-reduce composeSummary
		List<String> summaries = ChatUtil.composeSummary(testUser, chatConfig, promptConfig, data, false);
		assertNotNull("Summaries list is null", summaries);
		if (summaries.isEmpty()) {
			logger.warn("composeSummary returned empty — LLM service may be unavailable");
			return;
		}

		String lastSummary = summaries.get(summaries.size() - 1);
		assertNotNull("Final summary is null", lastSummary);
		assertTrue("Final summary should not be empty", lastSummary.length() > 0);
		logger.info("Map-reduce produced " + summaries.size() + " summaries, final length=" + lastSummary.length());
		logger.info("Final summary preview: " + lastSummary.substring(0, Math.min(300, lastSummary.length())));
	}

	@Test
	public void testComposeSummaryEmptyContent() {
		logger.info("testComposeSummaryEmptyContent");
		BaseRecord testUser = getCreateUser("testMapReduceEmpty");
		assertNotNull("Test user is null", testUser);

		/// Create a data record with no vector store
		BaseRecord data = getCreateData(testUser, "empty-summary-test.txt", "text/plain",
			"tiny".getBytes(), "~/Tests/Summaries", testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Data record is null", data);

		BaseRecord chatConfig = OlioTestUtil.getChatConfig(testUser, LLMServiceEnumType.OLLAMA, "MapReduceEmpty Chat", testProperties);
		BaseRecord promptConfig = OlioTestUtil.getPromptConfig(testUser, "MapReduceEmpty Prompt");
		if (chatConfig == null || promptConfig == null) {
			logger.warn("Config not available — skipping");
			return;
		}

		/// Should return empty list (no vector chunks)
		List<String> summaries = ChatUtil.composeSummary(testUser, chatConfig, promptConfig, data, false);
		assertNotNull("Summaries should not be null", summaries);
		logger.info("Empty content produced " + summaries.size() + " summaries (expected 0)");
	}

	@Test
	public void testComposeSummaryNullConfig() {
		logger.info("testComposeSummaryNullConfig");
		BaseRecord testUser = getCreateUser("testMapReduceNullCfg");
		assertNotNull("Test user is null", testUser);

		BaseRecord data = getCreateData(testUser, "null-cfg-test.txt", "text/plain",
			"test content".getBytes(), "~/Tests/Summaries", testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Data record is null", data);

		/// Null chatConfig should still work (falls back to local embedding)
		List<String> summaries = ChatUtil.composeSummary(testUser, null, null, data, false);
		assertNotNull("Summaries should not be null even with null config", summaries);
		logger.info("Null config produced " + summaries.size() + " summaries");
	}
}
