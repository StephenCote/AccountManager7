package org.cote.accountmanager.io.stream;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOStatistics;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;

/// The specialized search declared by data.streamSegment's "io" block. The only query shape a segment
/// supports is streamId (+ startPosition, length) - see find(). findRecord / findRecords / count are
/// thin views over that one operation; the name/urn/path/id lookups of ISearch have no meaning for a
/// byte range and say so instead of returning null.
public class StreamSegmentSearch implements ISearch {

	public static final Logger logger = LogManager.getLogger(StreamSegmentSearch.class);
	StreamSegmentUtil ssUtil = null;

	public StreamSegmentSearch() {
		ssUtil = new StreamSegmentUtil();
	}

	/// No resources are held between calls.
	@Override
	public void close() throws ReaderException {

	}

	@Override
	public BaseRecord findRecord(Query query) {
		BaseRecord[] recs = findRecords(query);
		return (recs.length > 0 ? recs[0] : null);
	}

	@Override
	public BaseRecord[] findRecords(Query query) {
		QueryResult qr = null;
		try {
			qr = find(query);
		} catch (ReaderException e) {
			logger.error(e);
		}
		return (qr == null ? new BaseRecord[0] : qr.getResults());
	}

	/// A segment query addresses one range, so the count is the number of segments find() actually read (0 or 1).
	@Override
	public int count(Query query) {
		return findRecords(query).length;
	}


	/// NOTE: No attempt is made here to make this smart or return multiple segments.
	/// Therefore, the assumption is the query is one level deep and only looking for 3 things:
	/// 1) The streamId to obtain the stream object
	/// 2) The startPosition (long)
	/// 3) The length
	///
	
	@Override
	public QueryResult find(Query query) throws ReaderException {
		logger.info("Use Segment Search!");
		List<BaseRecord> segments = new ArrayList<>();
		QueryResult res = new QueryResult(query, segments.toArray(new BaseRecord[0]));
		List<BaseRecord> queries = query.get(FieldNames.FIELD_FIELDS);
		
		String streamId = null;
		long startPosition = 0L;
		long length = 0L;
		
		for(BaseRecord r : queries) {
			QueryField qf = new QueryField(r);
			String name = qf.get(FieldNames.FIELD_NAME);
			if(name == null) {
				continue;
			}
			if(name.equals(FieldNames.FIELD_START_POSITION)) {
				startPosition = qf.get(FieldNames.FIELD_VALUE);
			}
			else if(name.equals(FieldNames.FIELD_LENGTH)) {
				length = qf.get(FieldNames.FIELD_VALUE);
			}
			else if(name.equals(FieldNames.FIELD_STREAM_ID)) {
				streamId = qf.get(FieldNames.FIELD_VALUE);
			}
		}
		
		if(streamId == null) {
			logger.error("Null stream id");
			return null;
		}
		logger.info(streamId + " " + startPosition + " " + length);
		BaseRecord segment = ssUtil.newSegment(streamId, startPosition, length);
		StreamSegmentReader sreader = RecordFactory.getClassInstance("org.cote.accountmanager.io.stream.StreamSegmentReader");
		BaseRecord rseg = sreader.read(segment);
		if((boolean)rseg.get(FieldNames.FIELD_READ)) {
			res.setTotalCount(1);
			List<BaseRecord> results = res.get(FieldNames.FIELD_RESULTS);
			results.add(rseg);
			/// The result was constructed over an empty array, so its "count" is 0 unless it is set here
			/// alongside totalCount. SearchBase.findRecord / findRecords (the dispatch path every caller
			/// except a direct find() takes) gate on getCount() > 0, so without this a dispatched segment
			/// query returned null / empty while the same find() carried the segment (TestStreamSegmentIO).
			try {
				res.set(FieldNames.FIELD_COUNT, results.size());
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				throw new ReaderException(e);
			}
		}


		return res;
	}

	private static final String NOT_ADDRESSABLE = "Segments are not addressable by name, urn, path, id or container; query data.streamSegment by streamId, startPosition and length";

	/// Every lookup below assumes a record store keyed by name/urn/path/id/container. A segment has none of
	/// those - it is a byte range of one stream - so each one is an explicit error rather than a null that
	/// reads like "not found".
	@Override
	public BaseRecord[] findByName(String model, String name) throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findByName(String model, String name, long organizationId)
			throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findByUrn(String model, String urn) throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, long organizationId)
			throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, String type,
			long organizationId) throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findByObjectId(String model, String objectId) throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findById(String model, long id) throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findByNameInParent(String model, long parentId, String name)
			throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, long organizationId)
			throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type)
			throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type, long organizationId)
			throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name)
			throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	@Override
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name, long organizationId)
			throws ReaderException {
		throw new ReaderException(NOT_ADDRESSABLE);
	}

	/// Segment reads are not counted: there is no statistics collector on this path, and callers that ask
	/// (IOContext reports the primary search's statistics) must treat null as "none kept".
	@Override
	public IOStatistics getStatistics() {
		return null;
	}

	@Override
	public void enableStatistics(boolean enabled) {

	}

}
