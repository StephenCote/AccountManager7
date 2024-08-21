package org.cote.accountmanager.io.db.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.cache.ICache;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.db.DBReader;
import org.cote.accountmanager.io.db.DBSearch;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ErrorUtil;
import org.cote.accountmanager.util.RecordUtil;

public class CacheDBSearch extends DBSearch implements ICache {
	
	/// 2023/06/29 - There is a currency problem with this rather simplistic cache when put under load
	/// For some reason, the mapped values become transposed somehow

	private Map<String, QueryResult> cache = new ConcurrentHashMap<>();
	
	private int maximumCacheSize = 10000;
	private int maximumCacheAgeMS = 360000;
	private long cacheRefreshed = 0L;

	public CacheDBSearch(DBReader reader) {
		super(reader);
		CacheUtil.addProvider(this);
		cacheRefreshed = System.currentTimeMillis();
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
	
	private void checkCache() {
		long now = System.currentTimeMillis();
		if( (now - cacheRefreshed) > maximumCacheAgeMS
			|| cache.size() > maximumCacheSize
		){
			logger.warn("Clearing search cache");
			cacheRefreshed = now;
			cache.clear();
		}
	}
	
	@Override
	public void close() throws ReaderException {
		cleanupCache();
		
	}
	
	@Override
	public QueryResult find(final Query query) throws ReaderException {

			if(query.key() == null) {
				throw new ReaderException("Null query key");
			}
			String hash = query.hash();

			checkCache();
			
			if(query.isCache() && cache.containsKey(hash)) {
				final QueryResult qr = cache.get(hash);
				stats.addCache(query);

				String qt = query.get(FieldNames.FIELD_TYPE);
				String qrt = qr.get(FieldNames.FIELD_TYPE);
				if(!qt.equals(qrt)) {
					logger.error("****** MISMATCHED CACHED RESULT TYPE!!! " + qt + " -> " + qrt);
					logger.error("****** " + query.key() + " -> " + qr.get(FieldNames.FIELD_QUERY_KEY));
					logger.error(query.toFullString());
					logger.error(qr.toFullString());
					logger.error("****** Invalidating cache entries for both");
					cache.remove(query.hash());
					cache.remove(qr.get(FieldNames.FIELD_QUERY_HASH));
				}
				else {
					return qr;
				}
			}

			final QueryResult res = super.find(query);
			
			if(query.isCache() && res != null && res.getCount() > 0) {
				String qt = query.get(FieldNames.FIELD_TYPE);
				String qrt = res.get(FieldNames.FIELD_TYPE);
				if(!qt.equals(qrt)) {
					logger.error("****** MISMATCHED RESULT TYPE ENTERING CACHE!!! " + qt + " -> " + qrt);
					throw new ReaderException("Mismatched result type entering cache: Query for " + qt + " contains " + qrt);
				}
				cache.put(hash, res);
			}
			else {
				/*
				logger.warn("Skip cache: " + query.isCache() + " / " + res.getCount() + " / " + (res == null) + " / " + query.key());
				logger.warn(query.toFullString());
				if(res != null) {
					logger.warn(res.toFullString());
				}
				ErrorUtil.printStackTrace();
				*/
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
