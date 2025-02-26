package org.cote.accountmanager.io.file.cache;

import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.cache.ICache;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.file.FileReader;
import org.cote.accountmanager.io.file.FileSearch;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.RecordUtil;

public class CacheFileSearch extends FileSearch implements ICache {

	private Map<String, QueryResult> cache = new HashMap<>();
	public CacheFileSearch(FileReader reader) {
		super(reader);
		CacheUtil.addProvider(this);
	}

	public void clearCache() {
		cache.clear();
	}
	public void clearCache(String key) {
		cache.remove(key);
	}
	public void cleanupCache() {
		clearCache();
		CacheUtil.removeProvider(this);
	}
	@Override
	public void close() throws ReaderException {
		cleanupCache();
		
	}
	
	@Override
	public void clearCacheByModel(String model) {
		cache.values().removeIf(entry -> model.equals((String)entry.get(FieldNames.FIELD_TYPE)));
	}
	
	@Override
	public QueryResult find(Query query) throws ReaderException {
		QueryResult res = null;
		String hash = query.hash();
		if(cache.containsKey(hash)) {
			return cache.get(hash);
		}
		res = super.find(query);
		if(res != null && res.getCount() > 0) {
			cache.put(hash, res);
		}
		return res;
	}

	@Override
	public void clearCache(BaseRecord rec) {
		IndexEntry idx = null;
		try {
			idx = IOSystem.getActiveContext().getIndexManager().getInstance(rec.getAMModel()).findIndexEntry(rec);
		} catch (IndexException e) {
			logger.error(e);
		}
		if(idx != null) {
			clearCacheByIdx(idx);
		}
	}
	
	

	@Override
	public void clearCacheByIdx(IndexEntry idx) {
		// TODO Auto-generated method stub
		String type = idx.get(FieldNames.FIELD_TYPE);
		cache.entrySet().removeIf(entry ->{
			QueryResult mr = entry.getValue();
			boolean match = false;
			for(BaseRecord r : mr.getResults()) {
				if(type.equals(r.getAMModel()) && RecordUtil.matchIdentityRecordsByIdx(idx, r)) {
					// logger.info("Clear record based on query result");
					match = true;
					break;
				}
			}
			return match;
		});
	}

}
