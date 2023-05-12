package org.cote.accountmanager.io.db.cache;

import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.cache.ICache;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.db.DBReader;
import org.cote.accountmanager.io.db.DBSearch;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.RecordUtil;

public class CacheDBSearch extends DBSearch implements ICache {

	private Map<String, QueryResult> cache = new HashMap<>();
	public CacheDBSearch(DBReader reader) {
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
	public QueryResult find(Query query) throws IndexException, ReaderException {
		QueryResult res = null;
		String hash = query.hash();
		if(cache.containsKey(hash)) {
			// logger.info("Cache hit: " + query.key());
			return cache.get(hash);
		}
		
		// logger.info(query.key());
		
		res = super.find(query);
		if(res != null && res.getCount() > 0) {
			cache.put(hash, res);
		}
		return res;
	}

	@Override
	public void clearCache(BaseRecord rec) {
		// logger.info("TODO: Clear cache by record");
		cache.entrySet().removeIf(entry ->{
			QueryResult mr = entry.getValue();
			boolean match = false;
			for(BaseRecord r : mr.getResults()) {
				if(rec.getModel().equals(r.getModel()) && RecordUtil.matchIdentityRecords(rec, r)) {
					// logger.info("Clear record based on query result");
					match = true;
					break;
				}
				
			}
			return match;
		});
	}

	@Override
	public void clearCacheByIdx(IndexEntry idx) {
		// TODO Auto-generated method stub
		
	}


}
