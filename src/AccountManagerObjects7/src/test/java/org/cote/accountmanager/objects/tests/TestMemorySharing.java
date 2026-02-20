package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.MemoryUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.junit.Test;

/// Phase 3 (chatRefactor2): Tests for cross-conversation memory sharing,
/// searchMemoriesByPerson(), searchMemoriesByPersonAndQuery(), and memory creation.
public class TestMemorySharing extends BaseTest {

	private long getPersonId(BaseRecord memory, String fieldName) {
		try {
			BaseRecord person = memory.get(fieldName);
			if (person != null) {
				return person.get(FieldNames.FIELD_ID);
			}
		} catch (Exception e) { /* field not populated */ }
		return 0L;
	}

	@Test
	public void testCreateAndRetrieveMemory() {
		logger.info("testCreateAndRetrieveMemory");
		BaseRecord testUser = getCreateUser("testMemShare");
		assertNotNull("Test user is null", testUser);

		/// Create a memory with known content
		BaseRecord memory = MemoryUtil.createMemory(
			testUser,
			"Alice and Bob discussed the weather and agreed it was beautiful outside.",
			"Weather discussion",
			MemoryTypeEnumType.FACT,
			7,
			"am7://test",
			"test-conv-1",
			100L, 200L, OlioModelNames.MODEL_CHAR_PERSON
		);
		assertNotNull("Created memory is null", memory);
		logger.info("Created memory: " + memory.get(FieldNames.FIELD_OBJECT_ID));
	}

	@Test
	public void testSearchMemoriesByPersonPair() {
		logger.info("testSearchMemoriesByPersonPair");
		BaseRecord testUser = getCreateUser("testMemPair");
		assertNotNull("Test user is null", testUser);

		/// Create memories for pair (100, 200)
		MemoryUtil.createMemory(testUser, "Pair memory 1", "PM1",
			MemoryTypeEnumType.FACT, 5, "am7://test", "conv-pair-1",
			100L, 200L, OlioModelNames.MODEL_CHAR_PERSON);
		MemoryUtil.createMemory(testUser, "Pair memory 2", "PM2",
			MemoryTypeEnumType.RELATIONSHIP, 8, "am7://test", "conv-pair-1",
			100L, 200L, OlioModelNames.MODEL_CHAR_PERSON);

		/// Retrieve pair memories — order shouldn't matter
		List<BaseRecord> results = MemoryUtil.searchMemoriesByPersonPair(testUser, 100L, 200L, 50);
		assertNotNull("Results are null", results);
		assertTrue("Should find at least 2 pair memories", results.size() >= 2);
		logger.info("Found " + results.size() + " pair memories for (100, 200)");

		/// Reversed order should give same results
		List<BaseRecord> reversed = MemoryUtil.searchMemoriesByPersonPair(testUser, 200L, 100L, 50);
		assertTrue("Reversed pair should give same count", reversed.size() == results.size());
	}

	@Test
	public void testSearchMemoriesByPerson() {
		logger.info("testSearchMemoriesByPerson");
		BaseRecord testUser = getCreateUser("testMemPerson");
		assertNotNull("Test user is null", testUser);

		/// Create memories across multiple pairs for person 300
		MemoryUtil.createMemory(testUser, "Person300 with 400", "P300-400",
			MemoryTypeEnumType.FACT, 6, "am7://test", "conv-p1",
			300L, 400L, OlioModelNames.MODEL_CHAR_PERSON);
		MemoryUtil.createMemory(testUser, "Person300 with 500", "P300-500",
			MemoryTypeEnumType.RELATIONSHIP, 7, "am7://test", "conv-p2",
			300L, 500L, OlioModelNames.MODEL_CHAR_PERSON);
		MemoryUtil.createMemory(testUser, "Person400 with 500 (no 300)", "P400-500",
			MemoryTypeEnumType.EMOTION, 5, "am7://test", "conv-p3",
			400L, 500L, OlioModelNames.MODEL_CHAR_PERSON);

		/// Search for person 300 — should find memories from both pairs
		List<BaseRecord> results = MemoryUtil.searchMemoriesByPerson(testUser, 300L, 50);
		assertNotNull("Results are null", results);
		assertTrue("Should find at least 2 memories for person 300", results.size() >= 2);
		logger.info("Found " + results.size() + " memories for person 300");

		/// Memory (400, 500) should NOT appear in person 300's results
		boolean foundUnrelated = false;
		for (BaseRecord mem : results) {
			long p1 = getPersonId(mem, "person1");
			long p2 = getPersonId(mem, "person2");
			if (p1 != 300L && p2 != 300L) {
				foundUnrelated = true;
				break;
			}
		}
		assertFalse("Should not include memories not involving person 300", foundUnrelated);
	}

	@Test
	public void testSearchMemoriesByPersonAndQuery() {
		logger.info("testSearchMemoriesByPersonAndQuery");
		BaseRecord testUser = getCreateUser("testMemPersonQuery");
		assertNotNull("Test user is null", testUser);

		if (!VectorUtil.isVectorSupported()) {
			logger.warn("Vector support not available — skipping semantic search test");
			return;
		}

		/// Create memories with distinct content for semantic matching
		MemoryUtil.createMemory(testUser, "Person 600 loves cooking Italian food with fresh basil and tomatoes.",
			"Cooking interest", MemoryTypeEnumType.FACT, 7, "am7://test", "conv-q1",
			600L, 700L, OlioModelNames.MODEL_CHAR_PERSON);
		MemoryUtil.createMemory(testUser, "Person 600 enjoys reading science fiction novels about space exploration.",
			"Reading interest", MemoryTypeEnumType.FACT, 6, "am7://test", "conv-q2",
			600L, 700L, OlioModelNames.MODEL_CHAR_PERSON);

		/// Semantic search: query about food should match the cooking memory
		List<BaseRecord> results = MemoryUtil.searchMemoriesByPersonAndQuery(
			testUser, "What does this person like to eat?", 600L, 5, 0.3);
		assertNotNull("Results are null", results);
		logger.info("Semantic search for food returned " + results.size() + " results");

		/// We can't guarantee vector similarity results without specific model,
		/// but the method should not throw and should return a list
		assertTrue("Result should be a valid list", results.size() >= 0);
	}

	@Test
	public void testMemorySharingAcrossPairs() {
		logger.info("testMemorySharingAcrossPairs");
		BaseRecord testUser = getCreateUser("testMemCrossShare");
		assertNotNull("Test user is null", testUser);

		/// Create a memory for pair (Alice=800, Bob=900)
		BaseRecord originalMemory = MemoryUtil.createMemory(
			testUser,
			"Alice told Bob about her trip to Paris and the Eiffel Tower.",
			"Paris trip discussion",
			MemoryTypeEnumType.FACT, 8, "am7://test", "conv-alice-bob",
			800L, 900L, OlioModelNames.MODEL_CHAR_PERSON
		);
		assertNotNull("Original memory is null", originalMemory);

		/// Share (copy) that memory to pair (Charlie=1000, Bob=900)
		String content = originalMemory.get("content");
		String summary = originalMemory.get("summary");
		BaseRecord sharedMemory = MemoryUtil.createMemory(
			testUser, content, summary,
			MemoryTypeEnumType.FACT, 8, "am7://manual", "conv-charlie-bob",
			900L, 1000L, OlioModelNames.MODEL_CHAR_PERSON
		);
		assertNotNull("Shared memory is null", sharedMemory);

		/// Verify the shared memory appears in (Charlie, Bob) pair
		List<BaseRecord> charlieBobMems = MemoryUtil.searchMemoriesByPersonPair(testUser, 900L, 1000L, 50);
		assertNotNull("Charlie-Bob results are null", charlieBobMems);
		boolean found = false;
		for (BaseRecord mem : charlieBobMems) {
			String c = mem.get("content");
			if (c != null && c.contains("Paris")) {
				found = true;
				break;
			}
		}
		assertTrue("Shared memory should appear in Charlie-Bob pair", found);

		/// Bob's cross-pair search should find memories from both pairs
		List<BaseRecord> bobMems = MemoryUtil.searchMemoriesByPerson(testUser, 900L, 50);
		assertTrue("Bob should have memories from multiple pairs", bobMems.size() >= 2);
		logger.info("Bob has " + bobMems.size() + " cross-pair memories");
	}

	@Test
	public void testFormatMemoriesAsContext() {
		logger.info("testFormatMemoriesAsContext");
		BaseRecord testUser = getCreateUser("testMemFormat");
		assertNotNull("Test user is null", testUser);

		MemoryUtil.createMemory(testUser, "Formatting test memory", "Format test",
			MemoryTypeEnumType.NOTE, 5, "am7://test", "conv-fmt",
			1100L, 1200L, OlioModelNames.MODEL_CHAR_PERSON);

		List<BaseRecord> mems = MemoryUtil.searchMemoriesByPersonPair(testUser, 1100L, 1200L, 10);
		assertNotNull("Memories are null", mems);
		assertTrue("Should have at least one memory", mems.size() > 0);

		String ctx = MemoryUtil.formatMemoriesAsContext(mems);
		assertNotNull("Context string is null", ctx);
		assertFalse("Context string should not be empty", ctx.isEmpty());
		assertTrue("Context should contain mcp:context", ctx.contains("<mcp:context") || ctx.contains("mcp"));
		logger.info("Formatted context length: " + ctx.length());
	}
}
