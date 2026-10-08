package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentReader;
import org.cote.accountmanager.io.stream.StreamSegmentSearch;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.io.stream.StreamSegmentWriter;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.junit.Test;

/// Pins the contracts of the three specialized IO classes data.streamSegment declares in its "io" block
/// (StreamSegmentWriter / StreamSegmentReader / StreamSegmentSearch) against a real FILE stream owned by
/// a non-admin user. Until 2026-10-07 most of their ISearch / IReader / IWriter methods were
/// auto-generated stubs returning null / false / 0, which read exactly like "not found".
public class TestStreamSegmentIO extends BaseTest {

	private static final byte[] PAYLOAD = "0123456789 segment payload for TestStreamSegmentIO".getBytes(StandardCharsets.UTF_8);

	/// A FILE stream owned by a plain user, with one segment appended through the normal
	/// RecordUtil.createRecord dispatch (-> StreamSegmentWriter.write).
	private BaseRecord newFileStreamWithPayload(BaseRecord user) throws Exception {
		String dataName = "SegIO stream " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Data/SegIO");
		plist.parameter(FieldNames.FIELD_NAME, dataName);
		BaseRecord stream = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, user, null, plist);
		stream.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
		stream.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
		stream = ioContext.getAccessPoint().create(user, stream);
		assertNotNull("The user must be able to create a FILE stream", stream);

		BaseRecord seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
		seg.set(FieldNames.FIELD_STREAM, PAYLOAD);
		seg.set(FieldNames.FIELD_STREAM_ID, stream.get(FieldNames.FIELD_OBJECT_ID));
		assertTrue("The segment must be appended through the specialized writer", ioContext.getRecordUtil().createRecord(seg));
		return stream;
	}

	private static Query segmentQuery(String streamId, long start, long length) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_STREAM_SEGMENT, FieldNames.FIELD_STREAM_ID, streamId);
		q.field(FieldNames.FIELD_START_POSITION, start);
		q.field(FieldNames.FIELD_LENGTH, length);
		return q;
	}

	@Test
	public void TestSearchViewsAgreeWithFindAndDispatch() throws Exception {
		OrganizationContext oc = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord user = mf.getCreateUser(oc.getAdminUser(), "testUser5", oc.getOrganizationId());
		BaseRecord stream = newFileStreamWithPayload(user);
		String streamId = stream.get(FieldNames.FIELD_OBJECT_ID);

		StreamSegmentSearch search = new StreamSegmentSearch();

		/// find() is the one real operation; the first 10 bytes of the stream.
		QueryResult qr = search.find(segmentQuery(streamId, 0L, 10L));
		assertNotNull(qr);
		assertEquals("find must report the segment it read in count, not only totalCount", 1, qr.getCount());
		assertEquals(1L, qr.getTotalCount());
		assertArrayEquals(Arrays.copyOfRange(PAYLOAD, 0, 10), (byte[]) qr.getResults()[0].get(FieldNames.FIELD_STREAM));

		/// findRecords / findRecord / count are views over find(), not stubs.
		BaseRecord[] recs = search.findRecords(segmentQuery(streamId, 0L, 10L));
		assertEquals(1, recs.length);
		assertArrayEquals(Arrays.copyOfRange(PAYLOAD, 0, 10), (byte[]) recs[0].get(FieldNames.FIELD_STREAM));
		BaseRecord rec = search.findRecord(segmentQuery(streamId, 10L, 5L));
		assertNotNull("findRecord must return the segment find() read", rec);
		assertArrayEquals(Arrays.copyOfRange(PAYLOAD, 10, 15), (byte[]) rec.get(FieldNames.FIELD_STREAM));
		assertEquals(1, search.count(segmentQuery(streamId, 0L, 10L)));

		/// A query that names no stream cannot be answered: find() returns null, the views report empty.
		Query noStream = QueryUtil.createQuery(ModelNames.MODEL_STREAM_SEGMENT, FieldNames.FIELD_START_POSITION, 0L);
		assertNull(search.find(noStream));
		assertEquals(0, search.findRecords(noStream).length);
		assertNull(search.findRecord(noStream));
		assertEquals(0, search.count(noStream));

		/// The same query through the context search dispatches to this class (SearchBase.findAlternate);
		/// before the count fix, findRecord here returned null while find() carried the segment.
		BaseRecord viaDispatch = ioContext.getSearch().findRecord(segmentQuery(streamId, 0L, 10L));
		assertNotNull("The dispatched findRecord must return the segment", viaDispatch);
		assertArrayEquals(Arrays.copyOfRange(PAYLOAD, 0, 10), (byte[]) viaDispatch.get(FieldNames.FIELD_STREAM));
		BaseRecord[] viaDispatchRecs = ioContext.getSearch().findRecords(segmentQuery(streamId, 0L, 10L));
		assertEquals("The dispatched findRecords must return the segment", 1, viaDispatchRecs.length);
	}

	@Test
	public void TestSearchLookupsThatHaveNoMeaningForASegmentThrow() throws Exception {
		StreamSegmentSearch search = new StreamSegmentSearch();
		String m = ModelNames.MODEL_STREAM_SEGMENT;
		assertThrowsReader(() -> search.findByName(m, "x"));
		assertThrowsReader(() -> search.findByName(m, "x", 1L));
		assertThrowsReader(() -> search.findByUrn(m, "x"));
		assertThrowsReader(() -> search.findByObjectId(m, "x"));
		assertThrowsReader(() -> search.findById(m, 1L));
		assertThrowsReader(() -> search.findByPath(null, m, "/x", 1L));
		assertThrowsReader(() -> search.findByPath(null, m, "/x", "FILE", 1L));
		assertThrowsReader(() -> search.findByNameInParent(m, 1L, "x"));
		assertThrowsReader(() -> search.findByNameInParent(m, 1L, "x", 1L));
		assertThrowsReader(() -> search.findByNameInParent(m, 1L, "x", "FILE"));
		assertThrowsReader(() -> search.findByNameInParent(m, 1L, "x", "FILE", 1L));
		assertThrowsReader(() -> search.findByNameInGroup(m, 1L, "x"));
		assertThrowsReader(() -> search.findByNameInGroup(m, 1L, "x", 1L));
		assertNull("No statistics are kept on the segment path", search.getStatistics());
		search.enableStatistics(true);
		search.close();
	}

	@Test
	public void TestReaderContract() throws Exception {
		OrganizationContext oc = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord user = mf.getCreateUser(oc.getAdminUser(), "testUser5", oc.getOrganizationId());
		BaseRecord stream = newFileStreamWithPayload(user);
		String streamId = stream.get(FieldNames.FIELD_OBJECT_ID);

		StreamSegmentReader reader = new StreamSegmentReader();
		assertEquals(RecordIO.FILE, reader.getRecordIo());

		BaseRecord seg = new StreamSegmentUtil().newSegment(streamId, 0L, 10L);
		assertTrue("inspect returns the record it was handed", reader.inspect(seg) == seg);
		assertThrowsReader(() -> reader.inspect(null));

		/// read(BaseRecord) is the real operation: a copy of the request carrying the bytes.
		BaseRecord read = reader.read(seg);
		assertNotNull(read);
		assertTrue("read must not mutate the request record", read != seg);
		assertTrue((boolean) read.get(FieldNames.FIELD_READ));
		assertEquals(10L, ((Number) read.get(FieldNames.FIELD_SIZE)).longValue());
		assertArrayEquals(Arrays.copyOfRange(PAYLOAD, 0, 10), (byte[]) read.get(FieldNames.FIELD_STREAM));

		/// Identity-keyed reads have no meaning for a byte range.
		assertThrowsReader(() -> reader.read(ModelNames.MODEL_STREAM_SEGMENT, "x"));
		assertThrowsReader(() -> reader.readByUrn(ModelNames.MODEL_STREAM_SEGMENT, "x"));
		assertThrowsReader(() -> reader.read(ModelNames.MODEL_STREAM_SEGMENT, 1L));

		/// Nothing to populate, nothing held: all safe no-ops.
		reader.populate(read);
		reader.populate(read, 1);
		reader.populate(read, new String[0]);
		reader.populate(read, new String[0], 1);
		reader.repopulate(read, 1);
		reader.conditionalPopulate(read, new String[0]);
		reader.flush();
		reader.close();
	}

	@Test
	public void TestWriterContract() throws Exception {
		OrganizationContext oc = getTestOrganization("/Development/Stream");
		Factory mf = ioContext.getFactory();
		BaseRecord user = mf.getCreateUser(oc.getAdminUser(), "testUser5", oc.getOrganizationId());
		BaseRecord stream = newFileStreamWithPayload(user);
		String streamId = stream.get(FieldNames.FIELD_OBJECT_ID);

		StreamSegmentWriter writer = new StreamSegmentWriter();
		assertEquals(RecordIO.FILE, writer.getRecordIo());

		/// write(BaseRecord) is the real operation: a second segment appends to the stream file.
		BaseRecord seg2 = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
		seg2.set(FieldNames.FIELD_STREAM, "-tail".getBytes(StandardCharsets.UTF_8));
		seg2.set(FieldNames.FIELD_STREAM_ID, streamId);
		assertTrue(writer.write(seg2));
		byte[] all = new StreamSegmentUtil().streamToEnd(streamId, 0L, 0L);
		assertEquals(PAYLOAD.length + 5, all.length);
		assertEquals("-tail", new String(Arrays.copyOfRange(all, PAYLOAD.length, all.length), StandardCharsets.UTF_8));

		/// Everything that assumes a record store is an explicit refusal, never a silent false / 0.
		assertThrowsWriter(() -> writer.write(new BaseRecord[] { seg2 }));
		assertThrowsWriter(() -> writer.write(seg2, new ByteArrayOutputStream()));
		assertThrowsWriter(() -> writer.delete(seg2));
		assertThrowsWriter(() -> writer.delete(seg2, new ByteArrayOutputStream()));
		assertThrowsWriter(() -> writer.delete(segmentQuery(streamId, 0L, 10L)));

		/// Nothing buffered, nothing held.
		writer.flush();
		writer.close();
	}

	@FunctionalInterface
	private interface ReaderCall {
		void call() throws ReaderException;
	}

	@FunctionalInterface
	private interface WriterCall {
		void call() throws WriterException;
	}

	private static void assertThrowsReader(ReaderCall call) {
		try {
			call.call();
		} catch (ReaderException e) {
			assertNotNull("The refusal must say why", e.getMessage());
			return;
		}
		throw new AssertionError("Expected a ReaderException naming why the operation is not supported");
	}

	private static void assertThrowsWriter(WriterCall call) {
		try {
			call.call();
		} catch (WriterException e) {
			assertNotNull("The refusal must say why", e.getMessage());
			return;
		}
		throw new AssertionError("Expected a WriterException naming why the operation is not supported");
	}
}
