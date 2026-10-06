package org.cote.accountmanager.io.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.junit.Test;

/// RecordFactory.cleanupOrphans runs StatementUtil.getDeleteOrphanTemplate as ONE batch, so a single statement that
/// names a column the table does not have ("column vectorreferencetype does not exist", seen in the Docker logs)
/// aborts every delete in it and no orphan is ever removed. The vectorReference/vectorReferenceType columns live only
/// on models that declare them (data.vectorModelStore), not on every common.vectorExt inheritor (data.pageIndexNode).
public class TestCleanupOrphansTemplate extends BaseTest {

	private static final String VECTOR_REF_CLAUSE = " WHERE vectorReferenceType = '";

	@Test
	public void testTemplateOnlyNamesVectorReferenceColumnsOnModelsThatDeclareThem() {
		OlioModelNames.use();
		DBUtil dbUtil = IOSystem.getActiveContext().getDbUtil();
		String sql = StatementUtil.getDeleteOrphanTemplate(null);
		assertNotNull("template", sql);
		assertTrue("template must not be empty", sql.length() > 0);

		String storeTable = dbUtil.getTableName(ModelNames.MODEL_VECTOR_MODEL_STORE);
		String pageIndexTable = dbUtil.getTableName(ModelNames.MODEL_PAGE_INDEX_NODE);
		assertTrue("vectorModelStore (declares both columns) must still be cleaned: " + storeTable,
			sql.contains("DELETE FROM " + storeTable + VECTOR_REF_CLAUSE));
		assertFalse("pageIndexNode (no vectorReferenceType column) must never be targeted by a vectorReferenceType delete",
			sql.contains("DELETE FROM " + pageIndexTable + VECTOR_REF_CLAUSE));

		List<String> offenders = new ArrayList<>();
		int inheritors = 0;
		for (String name : ModelNames.getCustomModelNames()) {
			if (name.equals(ModelNames.MODEL_MODEL) || name.equals(ModelNames.MODEL_PARTICIPATION)) {
				continue;
			}
			ModelSchema ms = RecordFactory.getSchema(name);
			if (ms == null || !ms.inherits(ModelNames.MODEL_VECTOR_EXT)) {
				continue;
			}
			inheritors++;
			boolean declares = ms.hasField(FieldNames.FIELD_VECTOR_REFERENCE) && ms.hasField(FieldNames.FIELD_VECTOR_REFERENCE_TYPE);
			boolean targeted = sql.contains("DELETE FROM " + dbUtil.getTableName(name) + VECTOR_REF_CLAUSE);
			if (targeted && !declares) {
				offenders.add(name);
			}
		}
		assertTrue("expected at least vectorModelStore and pageIndexNode to inherit common.vectorExt", inheritors >= 2);
		assertTrue("every vectorReferenceType delete must target a table that has the column; offenders: " + offenders,
			offenders.isEmpty());
	}

	@Test
	public void testCleanupRemovesDanglingVectorStoreRow() throws Exception {
		OlioModelNames.use();
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord user = getCreateUser("orphanTplUser");
		assertNotNull("user", user);
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
		String groupPath = "~/OrphanTpl";
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, "orphanTpl-" + tag);
		BaseRecord note = ioContext.getFactory().newInstance(ModelNames.MODEL_NOTE, user, null, plist);
		note.set(FieldNames.FIELD_TEXT, "orphan template probe " + tag);
		BaseRecord created = ioContext.getAccessPoint().create(user, note);
		assertNotNull("create note", created);
		long noteId = ((Number) created.get(FieldNames.FIELD_ID)).longValue();
		assertTrue("note id", noteId > 0L);

		// Seed the chunk the way VectorUtil.createVectorStore does, minus the LLM: a real reference to a real row.
		float[] embedding = new float[768];
		BaseRecord chunk = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MODEL_STORE);
		chunk.setValue(FieldNames.FIELD_CHUNK, 1);
		chunk.setValue(FieldNames.FIELD_CHUNK_COUNT, 1);
		chunk.setValue(FieldNames.FIELD_CONTENT, "orphan template probe " + tag);
		chunk.setValue(FieldNames.FIELD_EMBEDDING, embedding);
		chunk.setValue(FieldNames.FIELD_VECTOR_REFERENCE, created.copyRecord(new String[] {FieldNames.FIELD_ID}));
		chunk.setValue(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, ModelNames.MODEL_NOTE);
		chunk.setValue(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		assertEquals("persist chunk", 1, ioContext.getWriter().write(new BaseRecord[] {chunk}));

		Query byRef = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_VECTOR_REFERENCE,
			created.copyRecord(new String[] {FieldNames.FIELD_ID}));
		byRef.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, ModelNames.MODEL_NOTE);
		byRef.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		byRef.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_CHUNK});
		byRef.setCache(false);
		BaseRecord stored = ioContext.getSearch().findRecord(byRef);
		assertNotNull("chunk row must exist while the note exists", stored);
		long chunkId = ((Number) stored.get(FieldNames.FIELD_ID)).longValue();

		// A cleanup while the reference is live must leave the chunk alone.
		assertTrue("cleanup with live reference", RecordFactory.cleanupOrphansExplained(null));
		assertNotNull("chunk must survive while its note exists", rowById(ioContext, chunkId, orgId));

		// Deleting the referenced note strands the chunk; the fixed batch must now remove it.
		assertTrue("delete note", ioContext.getAccessPoint().delete(user, created));
		Query noteQ = QueryUtil.createQuery(ModelNames.MODEL_NOTE, FieldNames.FIELD_ID, noteId);
		noteQ.setCache(false);
		assertNull("note gone", ioContext.getSearch().findRecord(noteQ));
		assertNotNull("chunk is dangling before cleanup", rowById(ioContext, chunkId, orgId));

		assertTrue("cleanup batch must run without a SQLException", RecordFactory.cleanupOrphansExplained(null));
		assertNull("dangling chunk removed by cleanup", rowById(ioContext, chunkId, orgId));
		assertEquals("no chunk left for the deleted note", 0, ioContext.getSearch().count(byRef));
	}

	private static BaseRecord rowById(IOContext ioContext, long id, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_VECTOR_MODEL_STORE, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_CHUNK});
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}
}
