package org.cote.accountmanager.io.file;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.SearchBase;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;

public class FileSearch extends SearchBase {
	public static final Logger logger = LogManager.getLogger(FileSearch.class);
	private final FileReader reader;

	public FileSearch(FileReader reader) {
		this.reader = reader;
	}

	public int count(Query query) {
		return findRecords(query).length;
	}
	
	public QueryResult find(Query query) throws ReaderException {
		
		if(useAlternateIO(query)) {
			return findAlternate(query);
		}
		
		BaseRecord[] recs = new BaseRecord[0];

		try {
			recs = findByIndex(IOSystem.getActiveContext().getIndexManager().getInstance(query.get(FieldNames.FIELD_TYPE)).findIndexEntries(query));
			query.set(FieldNames.FIELD_EXECUTED, true);
		} catch (FieldException | ValueException | ModelNotFoundException | IndexException e) {
			logger.error(e);
		}

		if(query.hasField(FieldNames.FIELD_SORT_FIELD)) {
			String sortField = query.get(FieldNames.FIELD_SORT_FIELD);
			if(sortField != null) {
				List<BaseRecord> qrecs = Arrays.asList(recs);
				qrecs.sort((f1, f2) -> {
					int comp = 0;
					if(f1.hasField(sortField) && f2.hasField(sortField)) {
						FieldType ft1 = f1.getField(sortField);
						FieldType ft2 = f2.getField(sortField);
						comp = ft1.compareTo(ft2);
					}

					return comp;
				});
				recs = qrecs.toArray(new BaseRecord[0]);
			}
		}
		
		long startRecord = query.get(FieldNames.FIELD_START_RECORD);
		int istart = Math.toIntExact(startRecord);
		int recordCount = query.get(FieldNames.FIELD_RECORD_COUNT);
		long totalCount = recs.length;
		if(recordCount > 0) {
			int limit = Math.min(istart + recordCount, recs.length);
			int start = Math.min(Math.max(istart, 0), recs.length);
			recs = Arrays.copyOfRange(recs, start, limit);
		}
		BaseRecord[] urecs = recs;
		List<String> requestFields = query.get(FieldNames.FIELD_REQUEST);
		if(requestFields.size() > 0) {
			logger.debug("Restricting response field count to " + requestFields.size() + " - " + String.join(", ",  requestFields));
			urecs = new BaseRecord[recs.length];
			for(int i = 0; i < recs.length; i++) {
				try {
					urecs[i] = RecordFactory.newInstance(recs[i].getAMModel(), requestFields.toArray(new String[0]));
					List<FieldType> ufields = recs[i].getFields().stream().filter(o -> {
						return requestFields.contains(o.getName());
					}).collect(Collectors.toList());
					urecs[i].setFields(ufields);
				}
				catch(ModelNotFoundException | FieldException e) {
					logger.error(e);
					
				}
			}
		}
		QueryResult res = new QueryResult(query, urecs);
		res.setTotalCount(totalCount);
		return res;
	}
	
	protected BaseRecord[] findByIndex(IndexEntry[] entries) throws ReaderException {
		List<BaseRecord> records = new ArrayList<>();
		if(entries.length == 0) {
			return records.toArray(new BaseRecord[0]);
		}
		for(IndexEntry entry : entries) {
			BaseRecord record = reader.read(entry);
			if(record != null) {
				records.add(record);
			}
			else {
				logger.error("Error loading " + entry.getObjectId());
			}
		}
		return records.toArray(new BaseRecord[0]);
	}
	

}
