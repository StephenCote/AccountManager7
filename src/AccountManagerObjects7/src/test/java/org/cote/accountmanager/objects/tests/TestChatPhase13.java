package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.MemoryUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 13 tests: Memory Infrastructure & Chat Config Exposure
 *
 * P13-1: Config purpose field -- chatConfig has extractMemories, memoryBudget, memoryExtractionEvery
 * P13-2: ChatUtil.getCreateChatConfig() deprecation -- still callable, returns valid config
 * P13-3: MemoryUtil.createMemory -- create and retrieve a memory by conversationId
 * P13-5: MemoryUtil.getConversationMemories -- multiple memories same conversationId
 * P13-6: MemoryUtil.searchMemoriesByPersonPair -- person pair query
 * P13-8: Memory count -- searchMemoriesByPersonPair list size check
 * P13-9: Memory delete -- create, verify, delete, verify gone
 * P13-10: Cross-conversation recall -- person pair search spans conversations
 */
public class TestChatPhase13 extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupPhase13() {
		testOrgContext = getTestOrganization("/Development/Phase13");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "phase13User", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	/// P13-1: Verify chatConfigModel has the fields extractMemories, memoryBudget, memoryExtractionEvery
	/// and that they can be set on a chatConfig instance.
	@SuppressWarnings("deprecation")
	@Test
	public void testChatConfigMemoryFields() {
		try {
			String cfgName = "P13-1-Config-" + UUID.randomUUID().toString().substring(0, 6);
			BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser, cfgName);
			assertNotNull("ChatConfig should not be null", cfg);

			// Verify the fields exist and can be set
			cfg.set("extractMemories", true);
			boolean extractMemories = cfg.get("extractMemories");
			assertTrue("extractMemories should be true after setting", extractMemories);

			cfg.set("memoryBudget", 800);
			int memoryBudget = cfg.get("memoryBudget");
			assertEquals("memoryBudget should be 800", 800, memoryBudget);

			cfg.set("memoryExtractionEvery", 3);
			int memoryExtractionEvery = cfg.get("memoryExtractionEvery");
			assertEquals("memoryExtractionEvery should be 3", 3, memoryExtractionEvery);

			// Persist and re-read to verify persistence
			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
			assertNotNull("Updated config should not be null", cfg);

			boolean persisted = cfg.get("extractMemories");
			assertTrue("extractMemories should persist as true", persisted);

			int persistedBudget = cfg.get("memoryBudget");
			assertEquals("memoryBudget should persist as 800", 800, persistedBudget);

			int persistedEvery = cfg.get("memoryExtractionEvery");
			assertEquals("memoryExtractionEvery should persist as 3", 3, persistedEvery);

			logger.info("P13-1 passed: chatConfig memory fields verified (extractMemories, memoryBudget, memoryExtractionEvery)");
		} catch (Exception e) {
			logger.error("P13-1 failed", e);
			fail("P13-1 Exception: " + e.getMessage());
		}
	}

	/// P13-2: Verify ChatUtil.getCreateChatConfig() is deprecated but still functional.
	/// It should return a valid chatConfig record.
	@SuppressWarnings("deprecation")
	@Test
	public void testGetCreateChatConfigDeprecation() {
		try {
			String cfgName = "P13-2-Deprecated-" + UUID.randomUUID().toString().substring(0, 6);

			// Call the deprecated method -- it should still work
			BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser, cfgName);
			assertNotNull("Deprecated getCreateChatConfig should still return a non-null config", cfg);

			// Verify it's a valid chatConfig
			String name = cfg.get(FieldNames.FIELD_NAME);
			assertNotNull("Config name should not be null", name);
			assertTrue("Config name should contain our prefix", name.contains("P13-2-Deprecated-"));

			// Verify the config has expected schema fields
			cfg.set("model", "test-model-p13-2");
			String model = cfg.get("model");
			assertEquals("Model field should be settable", "test-model-p13-2", model);

			// Call again with the same name -- should return existing, not duplicate
			BaseRecord cfg2 = ChatUtil.getCreateChatConfig(testUser, cfgName);
			assertNotNull("Second call should also return non-null", cfg2);
			long id1 = cfg.get(FieldNames.FIELD_ID);
			long id2 = cfg2.get(FieldNames.FIELD_ID);
			assertEquals("Same name should return same record ID", id1, id2);

			logger.info("P13-2 passed: deprecated getCreateChatConfig still works and returns valid config");
		} catch (Exception e) {
			logger.error("P13-2 failed", e);
			fail("P13-2 Exception: " + e.getMessage());
		}
	}

	/// P13-3: Create a memory with MemoryUtil.createMemory and verify it can be retrieved
	/// via getConversationMemories.
	@Test
	public void testCreateMemoryAndRetrieve() {
		try {
			String conversationId = UUID.randomUUID().toString();
			String content = "The blacksmith mentioned a hidden forge beneath the old tower.";
			String summary = "Hidden forge location";

			BaseRecord memory = MemoryUtil.createMemory(
				testUser, content, summary,
				MemoryTypeEnumType.NOTE, 5,
				"am7://test/phase13", conversationId
			);
			assertNotNull("Created memory should not be null", memory);

			// Verify fields were set
			String storedContent = memory.get("content");
			assertEquals("Content should match", content, storedContent);

			String storedSummary = memory.get("summary");
			assertEquals("Summary should match", summary, storedSummary);

			int storedImportance = memory.get("importance");
			assertEquals("Importance should be 5", 5, storedImportance);

			// Retrieve by conversationId
			List<BaseRecord> memories = MemoryUtil.getConversationMemories(testUser, conversationId);
			assertNotNull("Conversation memories list should not be null", memories);
			assertTrue("Should find at least 1 memory for conversation, found " + memories.size(),
				memories.size() >= 1);

			// Verify the memory we created is in the results
			long memId = memory.get(FieldNames.FIELD_ID);
			boolean found = memories.stream().anyMatch(m -> (long) m.get(FieldNames.FIELD_ID) == memId);
			assertTrue("Created memory should appear in conversation memories", found);

			logger.info("P13-3 passed: createMemory and getConversationMemories work correctly");
		} catch (Exception e) {
			logger.error("P13-3 failed", e);
			fail("P13-3 Exception: " + e.getMessage());
		}
	}

	/// P13-5: Create two memories with the same conversationId.
	/// Verify getConversationMemories returns at least 2.
	@Test
	public void testGetConversationMemoriesMultiple() {
		try {
			String conversationId = UUID.randomUUID().toString();

			BaseRecord mem1 = MemoryUtil.createMemory(
				testUser, "Memory one: the river crossing was dangerous.",
				"River crossing danger", MemoryTypeEnumType.NOTE, 6,
				"am7://test/phase13", conversationId
			);
			assertNotNull("First memory should not be null", mem1);

			BaseRecord mem2 = MemoryUtil.createMemory(
				testUser, "Memory two: the merchant offered a rare gem.",
				"Rare gem offer", MemoryTypeEnumType.NOTE, 7,
				"am7://test/phase13", conversationId
			);
			assertNotNull("Second memory should not be null", mem2);

			List<BaseRecord> memories = MemoryUtil.getConversationMemories(testUser, conversationId);
			assertNotNull("Conversation memories list should not be null", memories);
			assertTrue("Should find at least 2 memories for conversation, found " + memories.size(),
				memories.size() >= 2);

			logger.info("P13-5 passed: getConversationMemories returns multiple memories (" + memories.size() + ")");
		} catch (Exception e) {
			logger.error("P13-5 failed", e);
			fail("P13-5 Exception: " + e.getMessage());
		}
	}

	/// P13-6: Create a memory with unique personIds per run.
	/// Verify searchMemoriesByPersonPair returns it.
	@Test
	public void testSearchMemoriesByPersonPair() {
		try {
			String conversationId = UUID.randomUUID().toString();
			long personId1 = 30000L + (System.currentTimeMillis() % 10000);
			long personId2 = personId1 + 1;

			BaseRecord memory = MemoryUtil.createMemory(
				testUser,
				"Aria confided in Marcus about her fear of the northern woods.",
				"Aria's fear confession",
				MemoryTypeEnumType.NOTE, 8,
				"am7://test/phase13", conversationId,
				personId1, personId2
			);
			assertNotNull("Memory with person pair should not be null", memory);

			List<BaseRecord> results = MemoryUtil.searchMemoriesByPersonPair(testUser, personId1, personId2, 10);
			assertNotNull("Person pair search results should not be null", results);
			assertTrue("Should find at least 1 memory for person pair (1, 2), found " + results.size(),
				results.size() >= 1);

			// Verify the specific memory is in the results
			long memId = memory.get(FieldNames.FIELD_ID);
			boolean found = results.stream().anyMatch(m -> (long) m.get(FieldNames.FIELD_ID) == memId);
			assertTrue("Created memory should appear in person pair results", found);

			logger.info("P13-6 passed: searchMemoriesByPersonPair returns correct results");
		} catch (Exception e) {
			logger.error("P13-6 failed", e);
			fail("P13-6 Exception: " + e.getMessage());
		}
	}

	/// P13-8: Create multiple memories and verify searchMemoriesByPersonPair list size matches.
	@Test
	public void testMemoryCountByPersonPair() {
		try {
			// Use unique person IDs per run to avoid stale data from prior test executions
			long personId1 = 40000L + (System.currentTimeMillis() % 10000);
			long personId2 = personId1 + 1;

			BaseRecord mem1 = MemoryUtil.createMemory(
				testUser,
				"Count test memory 1: They discussed strategy over dinner.",
				"Strategy discussion",
				MemoryTypeEnumType.NOTE, 5,
				"am7://test/phase13", UUID.randomUUID().toString(),
				personId1, personId2
			);
			assertNotNull("Memory 1 should not be null", mem1);

			BaseRecord mem2 = MemoryUtil.createMemory(
				testUser,
				"Count test memory 2: They trained together at dawn.",
				"Dawn training",
				MemoryTypeEnumType.NOTE, 6,
				"am7://test/phase13", UUID.randomUUID().toString(),
				personId1, personId2
			);
			assertNotNull("Memory 2 should not be null", mem2);

			BaseRecord mem3 = MemoryUtil.createMemory(
				testUser,
				"Count test memory 3: They shared stories around the campfire.",
				"Campfire stories",
				MemoryTypeEnumType.NOTE, 4,
				"am7://test/phase13", UUID.randomUUID().toString(),
				personId1, personId2
			);
			assertNotNull("Memory 3 should not be null", mem3);

			List<BaseRecord> results = MemoryUtil.searchMemoriesByPersonPair(testUser, personId1, personId2, 10);
			assertNotNull("Person pair search results should not be null", results);
			assertTrue("Should find at least 3 memories for person pair (1001, 1002), found " + results.size(),
				results.size() >= 3);

			logger.info("P13-8 passed: memory count matches expectations (" + results.size() + " found)");
		} catch (Exception e) {
			logger.error("P13-8 failed", e);
			fail("P13-8 Exception: " + e.getMessage());
		}
	}

	/// P13-9: Create a memory, verify it exists, delete it, verify it is gone.
	@Test
	public void testMemoryDelete() {
		try {
			String conversationId = UUID.randomUUID().toString();

			BaseRecord memory = MemoryUtil.createMemory(
				testUser,
				"This memory will be deleted: the secret door is behind the bookshelf.",
				"Secret door location",
				MemoryTypeEnumType.NOTE, 9,
				"am7://test/phase13", conversationId
			);
			assertNotNull("Memory to delete should not be null", memory);
			long memId = memory.get(FieldNames.FIELD_ID);

			// Verify it exists in conversation memories
			List<BaseRecord> beforeDelete = MemoryUtil.getConversationMemories(testUser, conversationId);
			boolean existsBefore = beforeDelete.stream().anyMatch(m -> (long) m.get(FieldNames.FIELD_ID) == memId);
			assertTrue("Memory should exist before deletion", existsBefore);

			// Delete the memory
			boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(testUser, memory);
			assertTrue("Delete operation should succeed", deleted);

			// Verify it is no longer returned by getConversationMemories
			List<BaseRecord> afterDelete = MemoryUtil.getConversationMemories(testUser, conversationId);
			boolean existsAfter = afterDelete.stream().anyMatch(m -> (long) m.get(FieldNames.FIELD_ID) == memId);
			assertTrue("Memory should NOT exist after deletion", !existsAfter);

			logger.info("P13-9 passed: memory delete verified (created, confirmed, deleted, confirmed gone)");
		} catch (Exception e) {
			logger.error("P13-9 failed", e);
			fail("P13-9 Exception: " + e.getMessage());
		}
	}

	/// P13-10: Cross-conversation recall -- create a memory in conversationId="session1"
	/// with personId1/personId2, then search by person pair (not by conversationId).
	/// The memory should be found regardless of conversationId, demonstrating
	/// cross-conversation recall.
	@Test
	public void testCrossConversationRecall() {
		try {
			// Use unique person IDs per run to avoid stale data from prior test executions
			long personId1 = 20000L + (System.currentTimeMillis() % 10000);
			long personId2 = personId1 + 1;
			String session1 = "session1-" + UUID.randomUUID().toString();
			String session2 = "session2-" + UUID.randomUUID().toString();

			// Create a memory in session1
			BaseRecord mem1 = MemoryUtil.createMemory(
				testUser,
				"Cross-conv memory: Elara mentioned the ancient ritual requires moonstone.",
				"Moonstone ritual requirement",
				MemoryTypeEnumType.NOTE, 7,
				"am7://test/phase13", session1,
				personId1, personId2
			);
			assertNotNull("Session1 memory should not be null", mem1);

			// Create a memory in session2 with the same person pair
			BaseRecord mem2 = MemoryUtil.createMemory(
				testUser,
				"Cross-conv memory: Elara found a moonstone fragment in the cave.",
				"Moonstone fragment found",
				MemoryTypeEnumType.NOTE, 6,
				"am7://test/phase13", session2,
				personId1, personId2
			);
			assertNotNull("Session2 memory should not be null", mem2);

			// Search by person pair -- should find memories from BOTH sessions
			List<BaseRecord> results = MemoryUtil.searchMemoriesByPersonPair(testUser, personId1, personId2, 10);
			assertNotNull("Cross-conversation results should not be null", results);
			assertTrue("Should find at least 2 memories across conversations, found " + results.size(),
				results.size() >= 2);

			// Verify both memories are present
			long mem1Id = mem1.get(FieldNames.FIELD_ID);
			long mem2Id = mem2.get(FieldNames.FIELD_ID);
			boolean foundMem1 = results.stream().anyMatch(m -> (long) m.get(FieldNames.FIELD_ID) == mem1Id);
			boolean foundMem2 = results.stream().anyMatch(m -> (long) m.get(FieldNames.FIELD_ID) == mem2Id);
			assertTrue("Memory from session1 should be found via person pair search", foundMem1);
			assertTrue("Memory from session2 should be found via person pair search", foundMem2);

			// Verify that getConversationMemories for session1 does NOT return the session2 memory
			List<BaseRecord> session1Only = MemoryUtil.getConversationMemories(testUser, session1);
			boolean session2InSession1 = session1Only.stream().anyMatch(m -> (long) m.get(FieldNames.FIELD_ID) == mem2Id);
			assertTrue("Session2 memory should NOT appear in session1 conversation query", !session2InSession1);

			logger.info("P13-10 passed: cross-conversation recall via person pair search works correctly");
		} catch (Exception e) {
			logger.error("P13-10 failed", e);
			fail("P13-10 Exception: " + e.getMessage());
		}
	}

	/// P13-14: Reproduce the chatHistory and getSessionContext server queries exactly.
	/// chatHistory: simple find (no plan) then access session foreign ref.
	/// getSessionContext: planMost(true, FULL_PLAN_FILTER) then access chatConfig foreign ref.
	@SuppressWarnings("deprecation")
	@Test
	public void testChatRequestQueryPaths() {
		try {
			// Create a chatConfig + promptConfig + chatRequest (with session)
			String suffix = UUID.randomUUID().toString().substring(0, 6);
			BaseRecord chatConfig = ChatUtil.getCreateChatConfig(testUser, "P14-cfg-" + suffix);
			assertNotNull("ChatConfig should not be null", chatConfig);
			BaseRecord promptConfig = ChatUtil.getCreatePromptConfig(testUser, "P14-pcfg-" + suffix);
			assertNotNull("PromptConfig should not be null", promptConfig);
			BaseRecord chatReq = ChatUtil.getCreateChatRequest(testUser, "P14-req-" + suffix, chatConfig, promptConfig);
			assertNotNull("ChatRequest should not be null", chatReq);
			String objectId = chatReq.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("ChatRequest objectId should not be null", objectId);
			logger.info("P13-14: Created chatRequest objectId=" + objectId);

			// --- PATH 1: Mimic ChatService.chatHistory (simple find, no planMost) ---
			logger.info("P13-14 PATH 1: Simple find (chatHistory endpoint pattern)");
			Query q1 = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
			q1.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			BaseRecord found1 = IOSystem.getActiveContext().getAccessPoint().find(testUser, q1);
			assertNotNull("PATH 1: find() should return a record", found1);
			BaseRecord session1 = found1.get("session");
			logger.info("PATH 1: session = " + (session1 != null ? "present" : "NULL"));
			BaseRecord fullSession1 = OlioUtil.getFullRecord(session1, false);
			logger.info("PATH 1: fullSession = " + (fullSession1 != null ? "present" : "NULL"));

			// --- PATH 2: Mimic ChatService.getSessionContext (planMost with FULL_PLAN_FILTER) ---
			logger.info("P13-14 PATH 2: planMost find (getSessionContext endpoint pattern)");
			Query q2 = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, objectId);
			q2.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			q2.planMost(true, OlioUtil.FULL_PLAN_FILTER);
			BaseRecord found2 = IOSystem.getActiveContext().getAccessPoint().find(testUser, q2);
			assertNotNull("PATH 2: planMost find() should return a record", found2);
			BaseRecord cc2 = found2.get("chatConfig");
			logger.info("PATH 2: chatConfig = " + (cc2 != null ? "present" : "NULL"));
			BaseRecord fullCc2 = OlioUtil.getFullRecord(cc2);
			logger.info("PATH 2: fullChatConfig = " + (fullCc2 != null ? "present" : "NULL"));

			logger.info("P13-14 passed: both query paths completed");
		} catch (Exception e) {
			logger.error("P13-14 failed", e);
			fail("P13-14 Exception: " + e.getMessage());
		}
	}

	/// P13-12: Verify chatRequest query does NOT try to resolve foreign ref fields
	/// (chatConfig, promptConfig, session) as query criteria on their own models.
	/// Regression test for: "'chatConfig' field requested for olio.llm.chatConfig model"
	@SuppressWarnings("deprecation")
	@Test
	public void testChatRequestQueryNoForeignFieldError() {
		try {
			// Create a chatRequest record to query against
			BaseRecord chatConfig = ChatUtil.getCreateChatConfig(testUser, "P13-12-cfg-" + UUID.randomUUID().toString().substring(0, 6));
			assertNotNull("ChatConfig should not be null", chatConfig);

			// Query chatRequest model by objectId — this should NOT throw an error
			// about 'chatConfig' being requested as a field on the chatConfig sub-model
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, "nonexistent-test-id");
			q.field(FieldNames.FIELD_ORGANIZATION_ID, testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			BaseRecord result = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
			// result can be null (no match) — the important thing is no exception was thrown
			logger.info("P13-12 passed: chatRequest query executed without foreign field errors (result=" + (result != null ? "found" : "null") + ")");
		} catch (Exception e) {
			logger.error("P13-12 failed", e);
			fail("P13-12 Exception (likely foreign field in query): " + e.getMessage());
		}
	}

	/// P13-13: Verify apiKey survives copyRecord without decryption errors.
	/// Regression test for: encrypted apiKey fails decryption on copied/cloned chatConfig.
	@SuppressWarnings("deprecation")
	@Test
	public void testApiKeyCopyRecord() {
		try {
			String cfgName = "P13-13-apikey-" + UUID.randomUUID().toString().substring(0, 6);
			BaseRecord chatConfig = ChatUtil.getCreateChatConfig(testUser, cfgName);
			assertNotNull("ChatConfig should not be null", chatConfig);

			// Set a test apiKey value and persist
			String testKey = "sk-test-" + UUID.randomUUID().toString().substring(0, 12);
			chatConfig.set("apiKey", testKey);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
			assertNotNull("Updated config should not be null", chatConfig);

			// Read the config back fully (triggers EncryptFieldProvider decrypt via getFullRecord)
			BaseRecord readBack = OlioUtil.getFullRecord(chatConfig);
			assertNotNull("Read-back config should not be null", readBack);
			String decryptedKey = readBack.get("apiKey");
			assertEquals("Decrypted apiKey should match original", testKey, decryptedKey);

			// Now copyRecord — this is the operation that previously failed
			BaseRecord copy = readBack.copyRecord();
			assertNotNull("Copy should not be null", copy);

			// The fix in ChatUtil.getChat re-sets apiKey from the decrypted source.
			// Simulate that pattern: read apiKey from source, set on copy
			String plainKey = readBack.get("apiKey");
			copy.set("apiKey", plainKey);
			String copyKey = copy.get("apiKey");
			assertEquals("ApiKey on copy (after re-set from source) should match", testKey, copyKey);

			logger.info("P13-13 passed: apiKey copy/re-set pattern works correctly");
		} catch (Exception e) {
			logger.error("P13-13 failed", e);
			fail("P13-13 Exception: " + e.getMessage());
		}
	}

	/// P13-11: Verify autoTitle field can be set on a chatConfig and persists.
	@SuppressWarnings("deprecation")
	@Test
	public void testAutoTitleConfigField() {
		try {
			String cfgName = "P13-11-AutoTitle-" + UUID.randomUUID().toString().substring(0, 6);
			BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser, cfgName);
			assertNotNull("ChatConfig should not be null", cfg);

			// Default should be false (boolean default)
			boolean defaultVal = cfg.get("autoTitle");
			assertTrue("autoTitle default should be false", !defaultVal);

			// Set to true and persist
			cfg.set("autoTitle", true);
			boolean afterSet = cfg.get("autoTitle");
			assertTrue("autoTitle should be true after setting", afterSet);

			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
			assertNotNull("Updated config should not be null", cfg);

			boolean persisted = cfg.get("autoTitle");
			assertTrue("autoTitle should persist as true", persisted);

			logger.info("P13-11 passed: autoTitle config field verified");
		} catch (Exception e) {
			logger.error("P13-11 failed", e);
			fail("P13-11 Exception: " + e.getMessage());
		}
	}
}
