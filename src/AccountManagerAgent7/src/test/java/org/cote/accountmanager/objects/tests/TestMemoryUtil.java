package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.agent.MemoryUtil;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.junit.Before;
import org.junit.Test;

public class TestMemoryUtil extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupMemory() {
		testOrgContext = getTestOrganization("/Development/Memory");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "memoryTestUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	// --- Group A: Model & Schema Tests ---

	@Test
	public void testMemoryModelSchema() {
		try {
			BaseRecord mem = RecordFactory.newInstance(ModelNames.MODEL_MEMORY);
			assertNotNull("Memory model instance should not be null", mem);

			assertTrue("Memory should have 'content' field", mem.hasField("content"));
			assertTrue("Memory should have 'summary' field", mem.hasField("summary"));
			assertTrue("Memory should have 'memoryType' field", mem.hasField("memoryType"));
			assertTrue("Memory should have 'importance' field", mem.hasField("importance"));
			assertTrue("Memory should have 'sourceUri' field", mem.hasField("sourceUri"));
			assertTrue("Memory should have 'sourceContext' field", mem.hasField("sourceContext"));
			assertTrue("Memory should have 'annotations' field", mem.hasField("annotations"));
			assertTrue("Memory should have 'conversationId' field", mem.hasField("conversationId"));

			// Check inherited fields from data.directory
			assertTrue("Memory should have inherited 'name' field", mem.hasField(FieldNames.FIELD_NAME));
			assertTrue("Memory should have inherited 'groupPath' field", mem.hasField(FieldNames.FIELD_GROUP_PATH));

			// Check default importance
			int importance = mem.get("importance");
			assertEquals("Default importance should be 5", 5, importance);

			logger.info("Memory model schema validation passed");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testVectorMemoryModelSchema() {
		try {
			BaseRecord vmem = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MEMORY);
			assertNotNull("VectorMemory model instance should not be null", vmem);

			assertTrue("VectorMemory should have 'memoryType' field", vmem.hasField("memoryType"));
			assertTrue("VectorMemory should have 'conversationId' field", vmem.hasField("conversationId"));

			// Check inherited vector fields from data.vectorModelStore
			assertTrue("VectorMemory should have 'vectorReference' field", vmem.hasField(FieldNames.FIELD_VECTOR_REFERENCE));
			assertTrue("VectorMemory should have 'content' field", vmem.hasField("content"));

			logger.info("VectorMemory model schema validation passed");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testMemoryTypeEnum() {
		for (MemoryTypeEnumType type : MemoryTypeEnumType.values()) {
			assertNotNull("Enum value should not be null", type);
			assertNotNull("Enum value() should not be null", type.value());
			assertEquals("fromValue should roundtrip", type, MemoryTypeEnumType.fromValue(type.value()));
		}

		// Verify expected values exist
		assertNotNull(MemoryTypeEnumType.UNKNOWN);
		assertNotNull(MemoryTypeEnumType.DISCOVERY);
		assertNotNull(MemoryTypeEnumType.BEHAVIOR);
		assertNotNull(MemoryTypeEnumType.OUTCOME);
		assertNotNull(MemoryTypeEnumType.NOTE);
		assertNotNull(MemoryTypeEnumType.INSIGHT);
		assertNotNull(MemoryTypeEnumType.DECISION);
		assertNotNull(MemoryTypeEnumType.ERROR_LESSON);

		logger.info("MemoryTypeEnumType validation passed");
	}

	// --- Group B: Memory Creation Tests ---

	@Test
	public void testCreateMemory() {
		try {
			String content = "The AM7 system uses a multi-inheritance model schema with JSON definitions.";
			String summary = "AM7 uses multi-inheritance model schema";
			String conversationId = UUID.randomUUID().toString();

			BaseRecord memory = MemoryUtil.createMemory(
				testUser, content, summary,
				MemoryTypeEnumType.DISCOVERY, 7,
				"am7://test/source", conversationId
			);
			assertNotNull("Created memory should not be null", memory);

			assertEquals("Content should match", content, memory.get("content"));
			assertEquals("Summary should match", summary, memory.get("summary"));
			assertEquals("Importance should match", 7, (int) memory.get("importance"));
			assertEquals("ConversationId should match", conversationId, memory.get("conversationId"));

			logger.info("Memory creation test passed");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testCreateMemoryWithNullOptionalFields() {
		try {
			BaseRecord memory = MemoryUtil.createMemory(
				testUser, "Simple test note", "Simple note",
				MemoryTypeEnumType.NOTE, 3,
				null, null
			);
			assertNotNull("Memory with null optional fields should still be created", memory);

			logger.info("Memory with null optionals test passed");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group C: Conversation Query Tests ---

	@Test
	public void testGetConversationMemories() {
		try {
			String conversationId = "conv-" + UUID.randomUUID().toString();

			// Create multiple memories for the same conversation
			MemoryUtil.createMemory(testUser, "Memory A for conversation", "Memory A",
				MemoryTypeEnumType.NOTE, 5, null, conversationId);
			MemoryUtil.createMemory(testUser, "Memory B for conversation", "Memory B",
				MemoryTypeEnumType.INSIGHT, 7, null, conversationId);

			// Create a memory for a different conversation
			MemoryUtil.createMemory(testUser, "Different conversation", "Other",
				MemoryTypeEnumType.NOTE, 3, null, "other-conv-" + UUID.randomUUID().toString());

			List<BaseRecord> convMemories = MemoryUtil.getConversationMemories(testUser, conversationId);
			assertNotNull("Conversation memories should not be null", convMemories);
			assertEquals("Should find exactly 2 memories for this conversation", 2, convMemories.size());

			logger.info("Conversation memory query test passed: found " + convMemories.size() + " memories");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group D: MCP Context Formatting Tests ---

	@Test
	public void testFormatMemoriesAsContext() {
		try {
			String conversationId = "fmt-" + UUID.randomUUID().toString();

			MemoryUtil.createMemory(testUser, "Formatting test content", "Format test",
				MemoryTypeEnumType.DISCOVERY, 8, "am7://test/format", conversationId);

			List<BaseRecord> memories = MemoryUtil.getConversationMemories(testUser, conversationId);
			assertFalse("Should have at least one memory", memories.isEmpty());

			String context = MemoryUtil.formatMemoriesAsContext(memories);
			assertNotNull("Formatted context should not be null", context);
			assertFalse("Formatted context should not be empty", context.isEmpty());

			// MCP context should contain the memory content
			assertTrue("Context should contain memory content", context.contains("Formatting test content"));

			logger.info("MCP context formatting test passed:\n" + context);
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testFormatEmptyMemories() {
		String context = MemoryUtil.formatMemoriesAsContext(null);
		assertEquals("Null memories should return empty string", "", context);

		context = MemoryUtil.formatMemoriesAsContext(java.util.Collections.emptyList());
		assertEquals("Empty list should return empty string", "", context);
	}

	// --- Group E: Extraction from LLM Response Tests ---

	@Test
	public void testExtractMemoriesFromResponse() {
		try {
			String conversationId = "ext-" + UUID.randomUUID().toString();
			String llmResponse = "Here are the extracted memories:\n" +
				"[\n" +
				"  {\"content\": \"The user prefers dark mode interfaces.\", \"summary\": \"User prefers dark mode\", \"memoryType\": \"BEHAVIOR\", \"importance\": 6},\n" +
				"  {\"content\": \"The API rate limit is 100 requests per minute.\", \"summary\": \"API rate limit 100/min\", \"memoryType\": \"DISCOVERY\", \"importance\": 8}\n" +
				"]";

			List<BaseRecord> extracted = MemoryUtil.extractMemoriesFromResponse(
				testUser, llmResponse, "am7://test/extraction", conversationId
			);
			assertNotNull("Extracted memories should not be null", extracted);
			assertEquals("Should extract 2 memories", 2, extracted.size());

			// Verify first memory
			BaseRecord mem0 = extracted.get(0);
			assertEquals("First memory content", "The user prefers dark mode interfaces.", mem0.get("content"));
			assertEquals("First memory importance", 6, (int) mem0.get("importance"));

			// Verify second memory
			BaseRecord mem1 = extracted.get(1);
			assertTrue("Second memory content should contain rate limit",
				((String) mem1.get("content")).contains("rate limit"));

			logger.info("Memory extraction test passed: extracted " + extracted.size() + " memories");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	@Test
	public void testExtractMemoriesFromInvalidResponse() {
		List<BaseRecord> extracted = MemoryUtil.extractMemoriesFromResponse(
			testUser, "This is not JSON at all", null, null
		);
		assertNotNull("Should return empty list, not null", extracted);
		assertTrue("Should extract 0 memories from invalid response", extracted.isEmpty());
	}

	@Test
	public void testExtractMemoriesWithUnknownType() {
		try {
			String llmResponse = "[{\"content\": \"Some memory\", \"summary\": \"Test\", \"memoryType\": \"INVALID_TYPE\", \"importance\": 5}]";

			List<BaseRecord> extracted = MemoryUtil.extractMemoriesFromResponse(
				testUser, llmResponse, null, null
			);
			assertNotNull("Extracted memories should not be null", extracted);
			assertEquals("Should extract 1 memory with fallback type", 1, extracted.size());

			logger.info("Unknown memory type fallback test passed");
		} catch (Exception e) {
			logger.error(e);
			fail("Exception: " + e.getMessage());
		}
	}

	// --- Group F: Vector Search Tests (require vector support) ---

	@Test
	public void testSearchMemoriesWithoutVectorSupport() {
		// This test verifies graceful degradation when vectors aren't available
		// If vector support IS available, it will test actual search
		String conversationId = "search-" + UUID.randomUUID().toString();

		MemoryUtil.createMemory(testUser, "Vector search test memory about machine learning algorithms",
			"ML algorithms memory", MemoryTypeEnumType.DISCOVERY, 7, null, conversationId);

		List<BaseRecord> results = MemoryUtil.searchMemories(testUser, "machine learning", 5, 0.5);
		assertNotNull("Search results should not be null even without vector support", results);

		logger.info("Memory search test completed: " + results.size() + " results");
	}
}
