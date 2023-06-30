package org.cote.accountmanager.cache;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.provider.ProviderUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class CacheUtil {
	public static final Logger logger = LogManager.getLogger(CacheUtil.class);

	private static Set<ICache> cacheProviders = ConcurrentHashMap.newKeySet();
	
	public static void addProvider(ICache cache) {
		cacheProviders.add(cache);
	}
	public static void removeProvider(ICache cache) {
		cacheProviders.remove(cache);
	}
	public static void clearCache() {
		// logger.info("Clear cache: " + cacheProviders.size());
		cacheProviders.forEach(c -> {
			c.clearCache();
		});
		ProviderUtil.clearCache();
	}
	public static void clearCache(String key) {
		// logger.info("Clear cache for key " + key);
		cacheProviders.forEach(c -> {
			c.clearCache(key);
		});
	}
	public static void clearCacheByModel(String model) {
		// logger.info("Clear cache for model " + model);
		cacheProviders.forEach(c -> {
			c.clearCacheByModel(model);
		});
	}
	public static void clearCache(BaseRecord rec) {
		// logger.info("Clear cache for record: " + rec.get(FieldNames.FIELD_URN));
		cacheProviders.forEach(c -> {
			c.clearCache(rec);
		});
	}
	public static void clearCacheByIdx(IndexEntry idx) {
		cacheProviders.forEach(c -> {
			c.clearCacheByIdx(idx);
		});
	}
	public static void clearProviders() {
		cacheProviders.clear();
	}
}
