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
import org.cote.accountmanager.util.RecordUtil;

public class CacheDBSearch extends DBSearch implements ICache {
	
	/// 2023/06/29 - There is a currency problem with this rather simplistic cache when put under load
	/// For some reason, the mapped values become transposed somehow

	//private Map<String, QueryResult> cache = new ConcurrentHashMap<>();
	private Map<String,  Map<String, QueryResult>> cacheMap = new ConcurrentHashMap<>();
	private int maximumCacheSize = 10000;
	private int maximumCacheAgeMS = 360000;
	private long cacheRefreshed = 0L;

	public CacheDBSearch(DBReader reader) {
		super(reader);
		CacheUtil.addProvider(this);
		cacheRefreshed = System.currentTimeMillis();
	}

	public synchronized void clearCache() {
		cacheMap.clear();
	}
	public synchronized void clearCache(String key) {
		cacheMap.values().forEach(m -> {
			m.remove(key);
		});
	}
	
	public void cleanupCache() {
		clearCache();
		CacheUtil.removeProvider(this);
	}
	
	private synchronized void checkCache() {
		long now = System.currentTimeMillis();
		if( (now - cacheRefreshed) > maximumCacheAgeMS
		//	|| cache.size() > maximumCacheSize
		){
			cacheRefreshed = now;
			cacheMap.clear();
		}
	}
	
	@Override
	public void close() throws ReaderException {
		cleanupCache();
		
	}
	
	private synchronized Map<String, QueryResult> getCacheMap(String model) {
		if(!cacheMap.containsKey(model)) {
			cacheMap.put(model, new ConcurrentHashMap<>());
		}
		return cacheMap.get(model);
	}
	
	private synchronized QueryResult getCache(Map<String, QueryResult> cache, final Query query, String hash) {
		
		QueryResult qr = null;
		if(query.isCache() && cache.containsKey(hash)) {
			qr = cache.get(hash);
			stats.addCache(query);

			String qt = query.get(FieldNames.FIELD_TYPE);
			String qrt = qr.get(FieldNames.FIELD_TYPE);
			if(!qt.equals(qrt)) {
				logger.error("****** MISMATCHED CACHED RESULT TYPE!!! " + qt + " -> " + qrt);
				logger.error("****** " + query.key() + " -> " + qr.get(FieldNames.FIELD_QUERY_KEY));
				logger.error(query.toFullString());
				logger.error(qr.toFullString());
				logger.error("****** Invalidating cache entries for both");
				cache.remove(hash);
				cache.remove(qr.get(FieldNames.FIELD_QUERY_HASH));
				qr = null;
			}
		}
		return qr;
	}
	
	private synchronized void addToCache(Map<String, QueryResult> cache, final Query query, QueryResult qr, String hash) throws ReaderException {
		if(query.isCache() && qr != null && qr.getCount() > 0) {
			String qt = query.get(FieldNames.FIELD_TYPE);
			String qrt = qr.get(FieldNames.FIELD_TYPE);
			if(!qt.equals(qrt)) {
				logger.error("****** MISMATCHED RESULT TYPE ENTERING CACHE!!! " + qt + " -> " + qrt);
				throw new ReaderException("Mismatched result type entering cache: Query for " + qt + " contains " + qrt);
			}
			cache.put(hash, qr);
		}
	}
	
	@Override
	public QueryResult find(final Query query) throws ReaderException {

		if(query.key() == null) {
			throw new ReaderException("Null query key");
		}
		
		checkCache();
		
		Map<String, QueryResult> cache = getCacheMap(query.get(FieldNames.FIELD_TYPE));
		final String hash = query.hash();
		if(hash == null) {
			throw new ReaderException("Null query hash");
		}
		QueryResult qr = getCache(cache, query, hash);
		if(qr == null) {
			qr = super.find(query);
			addToCache(cache, query, qr, hash);
		}

		return qr;

	}

	@Override
	public synchronized void clearCache(BaseRecord rec) {
		// logger.info("TODO: Clear cache by record");
		cacheMap.values().forEach(m ->{
			m.entrySet().removeIf(entry ->{
		
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
		});
	}
	
	@Override
	public void clearCacheByModel(String model) {
		cacheMap.remove(model);
	}

	@Override
	public void clearCacheByIdx(IndexEntry idx) {
		// TODO Auto-generated method stub
		
	}


}
