package org.cote.accountmanager.cache;

import java.util.HashSet;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.provider.ProviderUtil;
import org.cote.accountmanager.record.BaseRecord;

public class CacheUtil {
	public static final Logger logger = LogManager.getLogger(CacheUtil.class);

	private static Set<ICache> cacheProviders = new HashSet<>();
	
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
		cacheProviders.forEach(c -> {
			c.clearCache(key);
		});
	}
	public static void clearCache(BaseRecord rec) {
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
