package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.MemoryWriter;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

/// Pins the MemoryWriter / MemoryReader contracts that every other writer and reader inherits.
/// MemoryWriter is the base of DBWriter, FileWriter and JsonWriter; FileWriter and JsonWriter do NOT
/// override write(BaseRecord[]), so the batch loop here IS their batch write.
public class TestMemoryIO extends BaseTest {

	/// Before 2026-10-07 the batch loop wrote recs[0] on every iteration: the first record was translated
	/// N times and the rest were never touched, while the method still reported N writes. Translation on
	/// CREATE assigns a UUID to every null string identity field (RecordTranslator.translateField), so
	/// "was this record translated" is observable as "does it now carry an objectId".
	@Test
	public void TestBatchWriteTranslatesEveryRecordNotJustTheFirst() throws Exception {
		BaseRecord[] recs = new BaseRecord[3];
		for(int i = 0; i < recs.length; i++) {
			recs[i] = RecordFactory.newInstance(ModelNames.MODEL_GROUP, new String[] { FieldNames.FIELD_NAME });
			recs[i].set(FieldNames.FIELD_NAME, "memio-" + i);
			assertFalse("Precondition: a name-only instance carries no objectId before it is written",
				recs[i].hasField(FieldNames.FIELD_OBJECT_ID) && recs[i].get(FieldNames.FIELD_OBJECT_ID) != null);
		}

		MemoryWriter writer = new MemoryWriter();
		assertEquals(RecordIO.MEMORY, writer.getRecordIo());
		int written = writer.write(recs);
		assertEquals("Every record in the batch must be written", recs.length, written);

		Set<String> objectIds = new HashSet<>();
		for(int i = 0; i < recs.length; i++) {
			assertTrue("Record " + i + " must have been translated (identity fields auto-inserted)",
				recs[i].hasField(FieldNames.FIELD_OBJECT_ID));
			String oid = recs[i].get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("Record " + i + " must have been assigned an objectId by translation", oid);
			objectIds.add(oid);
		}
		assertEquals("Each record must have been translated on its own, not the first one three times",
			recs.length, objectIds.size());
		/// The writer holds nothing, so close is a safe no-op at any point.
		writer.close();
		writer.flush();
	}

	@Test
	public void TestMemoryWriterUnsupportedOperationsSayWhy() throws Exception {
		MemoryWriter writer = new MemoryWriter();
		BaseRecord rec = RecordFactory.newInstance(ModelNames.MODEL_GROUP, new String[] { FieldNames.FIELD_NAME });
		rec.set(FieldNames.FIELD_NAME, "memio-unsupported");
		assertTrue("A single in-memory write translates and succeeds", writer.write(rec));
		assertTrue("An in-memory delete translates and succeeds", writer.delete(rec));

		boolean threw = false;
		try {
			writer.write(rec, new java.io.ByteArrayOutputStream());
		} catch (WriterException e) {
			threw = true;
		}
		assertTrue("write(rec, OutputStream) must be an explicit not-implemented, not a silent false", threw);

		threw = false;
		try {
			writer.delete(rec, new java.io.ByteArrayOutputStream());
		} catch (WriterException e) {
			threw = true;
		}
		assertTrue("delete(rec, OutputStream) must be an explicit not-implemented, not a silent false", threw);
	}

	@Test
	public void TestMemoryReaderContract() throws Exception {
		MemoryReader reader = new MemoryReader();
		assertEquals(RecordIO.MEMORY, reader.getRecordIo());
		BaseRecord rec = RecordFactory.newInstance(ModelNames.MODEL_GROUP, new String[] { FieldNames.FIELD_NAME });
		rec.set(FieldNames.FIELD_NAME, "memio-read");
		assertTrue("inspect returns the record it was handed", reader.inspect(rec) == rec);
		assertTrue("read returns the record it was handed (translation only)", reader.read(rec) == rec);

		boolean threw = false;
		try {
			reader.read(ModelNames.MODEL_GROUP, "no-such-object-id");
		} catch (ReaderException e) {
			threw = true;
		}
		assertTrue("read(model, objectId) has no store to read from and must say so", threw);

		threw = false;
		try {
			reader.readByUrn(ModelNames.MODEL_GROUP, "no-such-urn");
		} catch (ReaderException e) {
			threw = true;
		}
		assertTrue("readByUrn has no store to read from and must say so", threw);

		/// Nothing is held, so close and flush are safe no-ops.
		reader.flush();
		reader.close();
	}
}
