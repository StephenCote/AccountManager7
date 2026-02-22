package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.MemoryUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 2 extraction tests (MemoryRefactor2.md).
 *
 * Verifies max-per-segment enforcement, type filtering, V2 prompt loading,
 * and new chatConfig field defaults.
 */
public class TestMemExtract extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupMemExtract() {
		testOrgContext = getTestOrganization("/Development/MemExtract");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "memExtractUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	/// Pass 5-memory JSON to 7-arg overload with maxPerSegment=1, verify only 1 returned.
	@Test
	public void testMaxPerSegmentEnforced() {
		try {
			String json = "["
				+ "{\"content\": \"Memory one\", \"summary\": \"m1\", \"memoryType\": \"FACT\", \"importance\": 5},"
				+ "{\"content\": \"Memory two\", \"summary\": \"m2\", \"memoryType\": \"FACT\", \"importance\": 6},"
				+ "{\"content\": \"Memory three\", \"summary\": \"m3\", \"memoryType\": \"DECISION\", \"importance\": 7},"
				+ "{\"content\": \"Memory four\", \"summary\": \"m4\", \"memoryType\": \"RELATIONSHIP\", \"importance\": 4},"
				+ "{\"content\": \"Memory five\", \"summary\": \"m5\", \"memoryType\": \"DISCOVERY\", \"importance\": 8}"
				+ "]";

			String convId = "max-seg-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord p1 = RecordFactory.newInstance("olio.charPerson");
			p1.set(FieldNames.FIELD_ID, 700L);
			BaseRecord p2 = RecordFactory.newInstance("olio.charPerson");
			p2.set(FieldNames.FIELD_ID, 800L);

			List<BaseRecord> results = MemoryUtil.extractMemoriesFromResponse(
				testUser, json, "am7://test/max-seg", convId, p1, p2, 1
			);
			assertNotNull("Results should not be null", results);
			assertEquals("Should return only 1 memory when maxPerSegment=1", 1, results.size());
			logger.info("testMaxPerSegmentEnforced passed");
		} catch (Exception e) {
			logger.error("testMaxPerSegmentEnforced failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Pass 5-memory JSON with maxPerSegment=0 (unlimited), verify all 5 returned.
	@Test
	public void testMaxPerSegmentUnlimited() {
		try {
			String json = "["
				+ "{\"content\": \"Unlim one\", \"summary\": \"u1\", \"memoryType\": \"FACT\", \"importance\": 5},"
				+ "{\"content\": \"Unlim two\", \"summary\": \"u2\", \"memoryType\": \"FACT\", \"importance\": 6},"
				+ "{\"content\": \"Unlim three\", \"summary\": \"u3\", \"memoryType\": \"DECISION\", \"importance\": 7},"
				+ "{\"content\": \"Unlim four\", \"summary\": \"u4\", \"memoryType\": \"RELATIONSHIP\", \"importance\": 4},"
				+ "{\"content\": \"Unlim five\", \"summary\": \"u5\", \"memoryType\": \"DISCOVERY\", \"importance\": 8}"
				+ "]";

			String convId = "max-unlim-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord p1 = RecordFactory.newInstance("olio.charPerson");
			p1.set(FieldNames.FIELD_ID, 701L);
			BaseRecord p2 = RecordFactory.newInstance("olio.charPerson");
			p2.set(FieldNames.FIELD_ID, 801L);

			List<BaseRecord> results = MemoryUtil.extractMemoriesFromResponse(
				testUser, json, "am7://test/max-unlim", convId, p1, p2, 0
			);
			assertNotNull("Results should not be null", results);
			assertEquals("Should return all 5 memories when maxPerSegment=0", 5, results.size());
			logger.info("testMaxPerSegmentUnlimited passed");
		} catch (Exception e) {
			logger.error("testMaxPerSegmentUnlimited failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Create memories with various types, filter by "FACT,DECISION", verify only those types remain.
	@Test
	public void testFilterByTypes() {
		try {
			String convId = "filter-" + UUID.randomUUID().toString().substring(0, 8);
			List<BaseRecord> memories = new ArrayList<>();

			memories.add(MemoryUtil.createMemory(testUser, "A fact", "fact", MemoryTypeEnumType.FACT, 5,
				"am7://test/filter", convId));
			memories.add(MemoryUtil.createMemory(testUser, "A decision", "decision", MemoryTypeEnumType.DECISION, 6,
				"am7://test/filter", convId));
			memories.add(MemoryUtil.createMemory(testUser, "An emotion", "emotion", MemoryTypeEnumType.EMOTION, 7,
				"am7://test/filter", convId));
			memories.add(MemoryUtil.createMemory(testUser, "A relationship", "relationship", MemoryTypeEnumType.RELATIONSHIP, 4,
				"am7://test/filter", convId));

			List<BaseRecord> filtered = MemoryUtil.filterByTypes(memories, "FACT,DECISION");
			assertNotNull("Filtered should not be null", filtered);
			assertEquals("Should have 2 memories after filtering to FACT,DECISION", 2, filtered.size());
			for (BaseRecord mem : filtered) {
				String mt = mem.get("memoryType").toString();
				assertTrue("Type should be FACT or DECISION, was: " + mt,
					"FACT".equals(mt) || "DECISION".equals(mt));
			}
			logger.info("testFilterByTypes passed");
		} catch (Exception e) {
			logger.error("testFilterByTypes failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Empty type string returns all memories (no filtering).
	@Test
	public void testFilterByTypesEmpty() {
		try {
			String convId = "filter-empty-" + UUID.randomUUID().toString().substring(0, 8);
			List<BaseRecord> memories = new ArrayList<>();
			memories.add(MemoryUtil.createMemory(testUser, "Keep all one", "ka1", MemoryTypeEnumType.FACT, 5,
				"am7://test/filter-empty", convId));
			memories.add(MemoryUtil.createMemory(testUser, "Keep all two", "ka2", MemoryTypeEnumType.EMOTION, 6,
				"am7://test/filter-empty", convId));

			List<BaseRecord> filtered = MemoryUtil.filterByTypes(memories, "");
			assertNotNull("Filtered should not be null", filtered);
			assertEquals("Empty types should return all memories", 2, filtered.size());

			List<BaseRecord> filteredNull = MemoryUtil.filterByTypes(memories, null);
			assertNotNull("Filtered null should not be null", filteredNull);
			assertEquals("Null types should return all memories", 2, filteredNull.size());

			logger.info("testFilterByTypesEmpty passed");
		} catch (Exception e) {
			logger.error("testFilterByTypesEmpty failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify memoryExtractionV2.json loads as a promptTemplate record.
	@Test
	public void testV2PromptLoads() {
		BaseRecord templateRec = PromptResourceUtil.loadAsRecord("memoryExtractionV2");
		assertNotNull("V2 prompt should load from resources as a record", templateRec);
		assertEquals("V2 prompt should be a promptTemplate",
			"olio.llm.promptTemplate", templateRec.getSchema());

		/// Compose the template to verify all sections join correctly
		String composed = org.cote.accountmanager.olio.llm.PromptTemplateComposer.composeSystem(
			templateRec, null, null);
		assertNotNull("Composed prompt should not be null", composed);
		assertTrue("Composed prompt should contain single memory instruction",
			composed.contains("SINGLE MOST IMPORTANT"));
		assertTrue("Composed prompt should contain memoryExtractionTypes token",
			composed.contains("${memoryExtractionTypes}"));
		logger.info("testV2PromptLoads passed");
	}

	/// Create chatConfig record, verify new field defaults.
	@Test
	public void testChatConfigNewFields() {
		try {
			BaseRecord cfg = RecordFactory.newInstance("olio.llm.chatConfig");
			assertNotNull("chatConfig should be created", cfg);

			int maxPerSeg = cfg.get("memoryExtractionMaxPerSegment");
			assertEquals("memoryExtractionMaxPerSegment should default to 1", 1, maxPerSeg);

			String types = cfg.get("memoryExtractionTypes");
			assertNotNull("memoryExtractionTypes should have a default", types);
			assertEquals("memoryExtractionTypes default",
				"FACT,RELATIONSHIP,DISCOVERY,DECISION,INSIGHT", types);

			logger.info("testChatConfigNewFields passed");
		} catch (Exception e) {
			logger.error("testChatConfigNewFields failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// V2 extraction with empty/trivial input returns empty list.
	@Test
	public void testEmptyExtractionReturnsEmptyList() {
		String emptyJson = "[]";
		String convId = "empty-" + UUID.randomUUID().toString().substring(0, 8);
		List<BaseRecord> results = MemoryUtil.extractMemoriesFromResponse(
			testUser, emptyJson, "am7://test/empty", convId, null, null, 1
		);
		assertNotNull("Results should not be null", results);
		assertEquals("Empty JSON array should return 0 memories", 0, results.size());

		List<BaseRecord> results2 = MemoryUtil.extractMemoriesFromResponse(
			testUser, "no json here at all", "am7://test/empty2", convId, null, null, 1
		);
		assertNotNull("Results2 should not be null", results2);
		assertEquals("Non-JSON input should return 0 memories", 0, results2.size());

		logger.info("testEmptyExtractionReturnsEmptyList passed");
	}

	/// Verify maxPerSegment=2 truncates correctly.
	@Test
	public void testMaxPerSegmentTwo() {
		try {
			String json = "["
				+ "{\"content\": \"M2 one\", \"summary\": \"m2-1\", \"memoryType\": \"FACT\", \"importance\": 9},"
				+ "{\"content\": \"M2 two\", \"summary\": \"m2-2\", \"memoryType\": \"DECISION\", \"importance\": 8},"
				+ "{\"content\": \"M2 three\", \"summary\": \"m2-3\", \"memoryType\": \"INSIGHT\", \"importance\": 7}"
				+ "]";

			String convId = "max-two-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord p1 = RecordFactory.newInstance("olio.charPerson");
			p1.set(FieldNames.FIELD_ID, 702L);
			BaseRecord p2 = RecordFactory.newInstance("olio.charPerson");
			p2.set(FieldNames.FIELD_ID, 802L);

			List<BaseRecord> results = MemoryUtil.extractMemoriesFromResponse(
				testUser, json, "am7://test/max-two", convId, p1, p2, 2
			);
			assertNotNull("Results should not be null", results);
			assertEquals("Should return 2 memories when maxPerSegment=2", 2, results.size());
			logger.info("testMaxPerSegmentTwo passed");
		} catch (Exception e) {
			logger.error("testMaxPerSegmentTwo failed", e);
			fail("Exception: " + e.getMessage());
		}
	}
}
