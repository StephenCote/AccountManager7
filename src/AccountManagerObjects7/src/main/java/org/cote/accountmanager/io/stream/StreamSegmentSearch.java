package org.cote.accountmanager.io.stream;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;

public class StreamSegmentSearch implements ISearch {
	
	public static final Logger logger = LogManager.getLogger(StreamSegmentSearch.class);
	StreamSegmentUtil ssUtil = null;
	
	public StreamSegmentSearch() {
		ssUtil = new StreamSegmentUtil();
	}
	@Override
	public void close() throws ReaderException {
		// TODO Auto-generated method stub

	}

	@Override
	public BaseRecord findRecord(Query query) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findRecords(Query query) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public int count(Query query) {
		// TODO Auto-generated method stub
		return 0;
	}

	
	/// NOTE: No attempt is made here to make this smart or return multiple segments.
	/// Therefore, the assumption is the query is one level deep and only looking for 3 things:
	/// 1) The streamId to obtain the stream object
	/// 2) The startPosition (long)
	/// 3) The length
	///
	
	@Override
	public QueryResult find(Query query) throws IndexException, ReaderException {
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
		}
		
		
		return res;
	}

	@Override
	public BaseRecord[] findByName(String model, String name) throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findByName(String model, String name, long organizationId)
			throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findByUrn(String model, String urn) throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, long organizationId)
			throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, String type,
			long organizationId) throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findByObjectId(String model, String objectId) throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findById(String model, long id) throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findByNameInParent(String model, long parentId, String name)
			throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, long organizationId)
			throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type)
			throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type, long organizationId)
			throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name)
			throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name, long organizationId)
			throws IndexException, ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

}
