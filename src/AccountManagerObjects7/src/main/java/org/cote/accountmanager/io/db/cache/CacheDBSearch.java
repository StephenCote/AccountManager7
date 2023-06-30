package org.cote.accountmanager.io.db.cache;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.RecordUtil;

public class CacheDBSearch extends DBSearch implements ICache {
	
	/// 2023/06/29 - There is a currency problem with this rather simplistic cache when put under load
	/// For some reason, the mapped values become transposed somehow

	private Map<String, QueryResult> cache = new ConcurrentHashMap<>();
	// Collections.synchronizedMap(new HashMap<>());
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
	public QueryResult find(final Query query) throws IndexException, ReaderException {

			if(query.key() == null) {
				throw new ReaderException("Null query key");
			}
			String hash = query.hash();
			if(cache.containsKey(hash)) {
				// logger.info("Cache hit: " + query.hash() + " " + query.key());
				final QueryResult qr = cache.get(hash);
				String qt = query.get(FieldNames.FIELD_TYPE);
				String qrt = qr.get(FieldNames.FIELD_TYPE);
				if(!qt.equals(qrt)) {
					logger.error("****** MISMATCHED CACHED RESULT TYPE!!! " + qt + " -> " + qrt);
					logger.error("Query hash: " + hash + " / " + query.hash() + " / " + qr.get(FieldNames.FIELD_QUERY_HASH));
					logger.error(query.toFullString());
					logger.error(qr.toFullString());
					throw new ReaderException("Mismatched result type: Cached query for " + qt + " contains " + qrt);
				}
				return qr;
				//return cache.get(hash);
			}
			
			// logger.info(query.key());

				final QueryResult res = super.find(query);
				if(res != null && res.getCount() > 0) {
					String qt = query.get(FieldNames.FIELD_TYPE);
					String qrt = res.get(FieldNames.FIELD_TYPE);
					if(!qt.equals(qrt)) {
						logger.error("****** MISMATCHED RESULT TYPE ENTERING CACHE!!! " + qt + " -> " + qrt);
						logger.error("Query hash: " + hash + " / " + query.hash() + " / " + res.get(FieldNames.FIELD_QUERY_HASH));
						logger.error(query.toFullString());
						logger.error(res.toFullString());
		
					}
					cache.put(hash, res);
				}
				return res;

	}

	@Override
	public synchronized void clearCache(BaseRecord rec) {
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
	public void clearCacheByModel(String model) {
		cache.values().removeIf(entry -> model.equals((String)entry.get(FieldNames.FIELD_TYPE)));
	}

	@Override
	public void clearCacheByIdx(IndexEntry idx) {
		// TODO Auto-generated method stub
		
	}


}
