package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.MemoryUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 1 cleanup verification tests (MemoryRefactor2.md).
 *
 * Verifies that deprecated overloads, dead fields, and text-based fallback
 * parsing have been removed, and that the remaining API surface works correctly.
 */
public class TestPhase1Cleanup extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupPhase1Cleanup() {
		testOrgContext = getTestOrganization("/Development/Phase1Cleanup");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "phase1CleanupUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	/// Verify that non-JSON input to extractMemoriesFromResponse returns empty list.
	/// The text-based fallback parser has been removed — only JSON is supported.
	@Test
	public void testTextParserRemoved() {
		String nonJsonInput = "Memory 1: They talked about philosophy.\nMemory 2: They agreed to meet again.";
		List<BaseRecord> results = MemoryUtil.extractMemoriesFromResponse(
			testUser, nonJsonInput, "am7://test/cleanup-text", "cleanup-text-conv"
		);
		assertNotNull("Results should not be null", results);
		assertEquals("Non-JSON input should return empty list", 0, results.size());
		logger.info("testTextParserRemoved passed: text input returns empty list");
	}

	/// Verify that valid JSON extraction still works.
	@Test
	public void testJsonExtractionStillWorks() {
		String jsonInput = "[{\"content\": \"Test memory\", \"summary\": \"test\", \"memoryType\": \"NOTE\", \"importance\": 5}]";
		List<BaseRecord> results = MemoryUtil.extractMemoriesFromResponse(
			testUser, jsonInput, "am7://test/cleanup-json", "cleanup-json-conv"
		);
		assertNotNull("Results should not be null", results);
		assertEquals("Should extract 1 memory from JSON", 1, results.size());
		logger.info("testJsonExtractionStillWorks passed");
	}

	/// Verify that createMemory with BaseRecord person stubs works correctly.
	@Test
	public void testCreateMemoryWithBaseRecordPersons() {
		try {
			String convId = "cleanup-br-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord p1 = RecordFactory.newInstance("olio.charPerson");
			p1.set(FieldNames.FIELD_ID, 500L);
			BaseRecord p2 = RecordFactory.newInstance("olio.charPerson");
			p2.set(FieldNames.FIELD_ID, 600L);

			BaseRecord memory = MemoryUtil.createMemory(
				testUser, "Phase 1 cleanup test", "cleanup test",
				MemoryTypeEnumType.FACT, 6,
				"am7://test/cleanup-br", convId,
				p1, p2
			);
			assertNotNull("Memory should be created", memory);

			String model1 = memory.get("person1Model");
			String model2 = memory.get("person2Model");
			assertNotNull("person1Model should be set", model1);
			assertNotNull("person2Model should be set", model2);
			assertEquals("person1Model should be olio.charPerson", "olio.charPerson", model1);

			logger.info("testCreateMemoryWithBaseRecordPersons passed");
		} catch (Exception e) {
			logger.error("testCreateMemoryWithBaseRecordPersons failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that the deprecated long-based createMemory overloads no longer exist.
	@Test
	public void testDeprecatedOverloadsRemoved() {
		try {
			// createMemory(user, content, summary, type, importance, sourceUri, convId, long, long)
			Method longOverload = null;
			try {
				longOverload = MemoryUtil.class.getMethod("createMemory",
					BaseRecord.class, String.class, String.class,
					MemoryTypeEnumType.class, int.class, String.class, String.class,
					long.class, long.class);
			} catch (NoSuchMethodException e) {
				// Expected
			}
			assertTrue("createMemory(long,long) overload should be removed", longOverload == null);

			// createMemory(user, content, summary, type, importance, sourceUri, convId, long, long, String)
			Method longModelOverload = null;
			try {
				longModelOverload = MemoryUtil.class.getMethod("createMemory",
					BaseRecord.class, String.class, String.class,
					MemoryTypeEnumType.class, int.class, String.class, String.class,
					long.class, long.class, String.class);
			} catch (NoSuchMethodException e) {
				// Expected
			}
			assertTrue("createMemory(long,long,String) overload should be removed", longModelOverload == null);

			// searchMemoriesByPerson(user, long, int)
			Method searchPersonLong = null;
			try {
				searchPersonLong = MemoryUtil.class.getMethod("searchMemoriesByPerson",
					BaseRecord.class, long.class, int.class);
			} catch (NoSuchMethodException e) {
				// Expected
			}
			assertTrue("searchMemoriesByPerson(long) overload should be removed", searchPersonLong == null);

			// searchMemoriesByPersonPair(user, long, long, int)
			Method searchPairLong = null;
			try {
				searchPairLong = MemoryUtil.class.getMethod("searchMemoriesByPersonPair",
					BaseRecord.class, long.class, long.class, int.class);
			} catch (NoSuchMethodException e) {
				// Expected
			}
			assertTrue("searchMemoriesByPersonPair(long,long) overload should be removed", searchPairLong == null);

			logger.info("testDeprecatedOverloadsRemoved passed: all long overloads removed");
		} catch (Exception e) {
			logger.error("testDeprecatedOverloadsRemoved failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that Chat no longer has formatOutput field or methods.
	@Test
	public void testFormatOutputRemoved() {
		try {
			// Check field removed
			boolean hasField = false;
			try {
				Chat.class.getDeclaredField("formatOutput");
				hasField = true;
			} catch (NoSuchFieldException e) {
				// Expected
			}
			assertFalse("Chat.formatOutput field should be removed", hasField);

			// Check getter removed
			boolean hasGetter = false;
			try {
				Chat.class.getMethod("isFormatOutput");
				hasGetter = true;
			} catch (NoSuchMethodException e) {
				// Expected
			}
			assertFalse("Chat.isFormatOutput() should be removed", hasGetter);

			// Check setter removed
			boolean hasSetter = false;
			try {
				Chat.class.getMethod("setFormatOutput", boolean.class);
				hasSetter = true;
			} catch (NoSuchMethodException e) {
				// Expected
			}
			assertFalse("Chat.setFormatOutput() should be removed", hasSetter);

			logger.info("testFormatOutputRemoved passed");
		} catch (Exception e) {
			logger.error("testFormatOutputRemoved failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that searchMemoriesByPerson with null user returns empty list.
	@Test
	public void testSearchWithNullUserReturnsEmpty() {
		try {
			BaseRecord p = RecordFactory.newInstance("olio.charPerson");
			p.set(FieldNames.FIELD_ID, 1L);
			List<BaseRecord> results = MemoryUtil.searchMemoriesByPerson(null, p, 10);
			assertNotNull("Results should not be null", results);
			assertEquals("Null user should return empty list", 0, results.size());

			logger.info("testSearchWithNullUserReturnsEmpty passed");
		} catch (Exception e) {
			logger.error("testSearchWithNullUserReturnsEmpty failed", e);
			fail("Exception: " + e.getMessage());
		}
	}
}
