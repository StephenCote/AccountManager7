package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.BatchQueue;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// BatchQueue.processQueue spills a batch to DEFAULT_FILE_BASE/.queue/<schema>/<uuid>.json when it
/// runs without an active context. Until 2026-10-07 scanQueue() was an empty TODO, so a spilled audit
/// stayed on disk forever. This writes a file in exactly the format processQueue emits and proves the
/// next execute() restores it, persists it, and removes the file.
public class TestBatchQueueSpill extends BaseTest {

	private BaseRecord findAuditByMessage(String marker) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_AUDIT, FieldNames.FIELD_MESSAGE, marker);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_MESSAGE, FieldNames.FIELD_RESPONSE });
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	@Test
	public void TestSpilledBatchIsRestoredAndPersisted() throws Exception {
		OrganizationContext oc = getTestOrganization("/Development/BatchQueue");
		Factory mf = ioContext.getFactory();
		BaseRecord user = mf.getCreateUser(oc.getAdminUser(), "bqUser1", oc.getOrganizationId());
		assertNotNull(user);

		/// Build the audit the way AuditUtil does, but do not close it: closeAudit would enqueue it
		/// live. Instead serialize it the way processQueue does when the context is gone.
		String marker = "batch-queue-spill-" + UUID.randomUUID().toString();
		BaseRecord audit = AuditUtil.startAudit(user, ActionEnumType.READ, user, null);
		assertNotNull(audit);
		audit.set(FieldNames.FIELD_RESPONSE, ResponseEnumType.INVALID);
		audit.set(FieldNames.FIELD_MESSAGE, marker);
		assertTrue("Precondition: the marker is not already persisted", findAuditByMessage(marker) == null);

		File dir = new File(BatchQueue.getQueuePath() + File.separator + ModelNames.MODEL_AUDIT);
		File spill = new File(dir, UUID.randomUUID().toString() + ".json");
		List<BaseRecord> batch = Arrays.asList(audit);
		assertTrue(FileUtil.emitFile(spill.getPath(), JSONUtil.exportObject(batch, RecordSerializerConfig.getUnfilteredModule())));
		assertTrue("Precondition: the spill file exists", spill.exists());

		/// One flush: scanQueue claims and re-enqueues the file, then processQueue writes the batch.
		ioContext.getQueue().execute();

		assertFalse("The spill file must be consumed", spill.exists());
		File[] leftovers = dir.listFiles((d, n) -> n.startsWith(spill.getName()));
		assertTrue("No claim or failed marker may be left behind for a good file: " + Arrays.toString(leftovers),
			leftovers == null || leftovers.length == 0);

		BaseRecord persisted = findAuditByMessage(marker);
		assertNotNull("The restored audit must have been written", persisted);
		assertEquals(marker, persisted.get(FieldNames.FIELD_MESSAGE));
	}

	@Test
	public void TestUnparseableSpillIsRetainedNotRetried() throws Exception {
		File dir = new File(BatchQueue.getQueuePath() + File.separator + ModelNames.MODEL_AUDIT);
		File spill = new File(dir, UUID.randomUUID().toString() + ".json");
		assertTrue(FileUtil.emitFile(spill.getPath(), "this is not json"));

		ioContext.getQueue().execute();

		assertFalse("A bad file must not stay in the scan set", spill.exists());
		File failed = new File(spill.getPath() + ".failed");
		assertTrue("A bad file is retained as .failed for inspection", failed.exists());
		assertTrue(failed.delete());
	}
}
