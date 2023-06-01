package org.cote.accountmanager.cache;

import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.record.BaseRecord;

public interface ICache {
	public void clearCache();
	public void clearCache(String key);
	public void clearCacheByModel(String modelName);
	public void clearCache(BaseRecord rec);
	public void clearCacheByIdx(IndexEntry idx);
	public void cleanupCache();
}
