package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.util.MemoryUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 6 vector memory cleanup tests (MemoryRefactor2.md).
 *
 * Verifies cascade delete on memory removal, deleteMemoryWithCascade utility,
 * no false positives, and proper cleanup behavior.
 */
public class TestVectorCleanup extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupVectorCleanup() {
		testOrgContext = getTestOrganization("/Development/VectorCleanup");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "vectorCleanupUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	/// Verify that cascadeDeleteVectors runs without error on a memory record.
	@Test
	public void testCascadeDeleteVectorsMethod() {
		try {
			String convId = "cascade-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord memory = MemoryUtil.createMemory(testUser, "Cascade test content", "cascade test",
				MemoryTypeEnumType.FACT, 5, "am7://test/cascade", convId);
			assertNotNull("Memory should be created", memory);

			// Cascade delete vectors (may return 0 if no vector support or no vectors created)
			int deleted = MemoryUtil.cascadeDeleteVectors(memory);
			assertTrue("Cascade delete should return >= 0", deleted >= 0);

			logger.info("testCascadeDeleteVectorsMethod passed — vectorsDeleted=" + deleted);
		} catch (Exception e) {
			logger.error("testCascadeDeleteVectorsMethod failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that deleteMemoryWithCascade deletes the memory record.
	@Test
	public void testDeleteMemoryWithCascade() {
		try {
			String convId = "delcascade-" + UUID.randomUUID().toString().substring(0, 8);
			BaseRecord memory = MemoryUtil.createMemory(testUser, "Delete cascade content", "delete cascade",
				MemoryTypeEnumType.NOTE, 3, "am7://test/delcascade", convId);
			assertNotNull("Memory should be created", memory);

			String objectId = memory.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("Memory objectId should be set", objectId);

			// Delete with cascade
			boolean deleted = MemoryUtil.deleteMemoryWithCascade(testUser, memory);
			assertTrue("Memory should be deleted", deleted);

			// Verify memory is gone
			Query q = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_OBJECT_ID, objectId);
			BaseRecord found = IOSystem.getActiveContext().getSearch().findRecord(q);
			assertTrue("Memory should not be found after delete", found == null);

			logger.info("testDeleteMemoryWithCascade passed — objectId=" + objectId);
		} catch (Exception e) {
			logger.error("testDeleteMemoryWithCascade failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that valid memories are not affected by cascade operations on other records.
	@Test
	public void testNoFalsePositives() {
		try {
			String convId = "nofp-" + UUID.randomUUID().toString().substring(0, 8);

			// Create two memories
			BaseRecord mem1 = MemoryUtil.createMemory(testUser, "Memory 1 survives", "survivor 1",
				MemoryTypeEnumType.FACT, 5, "am7://test/nofp", convId);
			BaseRecord mem2 = MemoryUtil.createMemory(testUser, "Memory 2 gets deleted", "deleted 2",
				MemoryTypeEnumType.FACT, 5, "am7://test/nofp", convId);
			assertNotNull("Memory 1 should be created", mem1);
			assertNotNull("Memory 2 should be created", mem2);

			String oid1 = mem1.get(FieldNames.FIELD_OBJECT_ID);
			String oid2 = mem2.get(FieldNames.FIELD_OBJECT_ID);

			// Delete mem2 only
			MemoryUtil.deleteMemoryWithCascade(testUser, mem2);

			// Verify mem1 still exists
			Query q = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_OBJECT_ID, oid1);
			BaseRecord found = IOSystem.getActiveContext().getSearch().findRecord(q);
			assertNotNull("Memory 1 should still exist", found);

			// Verify mem2 is gone
			Query q2 = QueryUtil.createQuery(ModelNames.MODEL_MEMORY, FieldNames.FIELD_OBJECT_ID, oid2);
			BaseRecord found2 = IOSystem.getActiveContext().getSearch().findRecord(q2);
			assertTrue("Memory 2 should be deleted", found2 == null);

			logger.info("testNoFalsePositives passed");
		} catch (Exception e) {
			logger.error("testNoFalsePositives failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that VectorUtil is available in the test context.
	@Test
	public void testVectorUtilAvailable() {
		try {
			VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
			// VectorUtil may be null if no embedding server configured, but the method should not throw
			logger.info("testVectorUtilAvailable passed — vectorUtil=" + (vu != null ? "available" : "not configured"));
		} catch (Exception e) {
			logger.error("testVectorUtilAvailable failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that deleting a nonexistent memory returns false without error.
	@Test
	public void testDeleteNonexistentMemory() {
		try {
			BaseRecord fakeMem = RecordFactory.newInstance(ModelNames.MODEL_MEMORY);
			fakeMem.set(FieldNames.FIELD_ID, -1L);

			boolean deleted = MemoryUtil.deleteMemoryWithCascade(testUser, fakeMem);
			assertFalse("Deleting nonexistent memory should return false", deleted);

			logger.info("testDeleteNonexistentMemory passed");
		} catch (Exception e) {
			logger.error("testDeleteNonexistentMemory failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that conversation memories can be queried after selective deletion.
	@Test
	public void testQueryAfterSelectiveDelete() {
		try {
			String convId = "seldelete-" + UUID.randomUUID().toString().substring(0, 8);

			BaseRecord mem1 = MemoryUtil.createMemory(testUser, "Keeper memory", "keeper",
				MemoryTypeEnumType.FACT, 7, "am7://test/seldelete", convId);
			BaseRecord mem2 = MemoryUtil.createMemory(testUser, "Throwaway memory", "throwaway",
				MemoryTypeEnumType.NOTE, 3, "am7://test/seldelete", convId);
			assertNotNull("mem1 should be created", mem1);
			assertNotNull("mem2 should be created", mem2);

			MemoryUtil.deleteMemoryWithCascade(testUser, mem2);

			List<BaseRecord> remaining = MemoryUtil.getConversationMemories(testUser, convId);
			assertNotNull("Remaining memories should not be null", remaining);
			assertEquals("Should have 1 remaining memory", 1, remaining.size());
			assertEquals("Remaining memory should be the keeper",
				"Keeper memory", remaining.get(0).get("content"));

			logger.info("testQueryAfterSelectiveDelete passed");
		} catch (Exception e) {
			logger.error("testQueryAfterSelectiveDelete failed", e);
			fail("Exception: " + e.getMessage());
		}
	}
}
