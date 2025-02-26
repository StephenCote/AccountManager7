package org.cote.accountmanager.io.file.cache;

import java.util.HashMap;
import java.util.Map;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.cache.ICache;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.file.FilePathUtil;
import org.cote.accountmanager.io.file.FileReader;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.RecordUtil;

public class CacheFileReader extends FileReader implements ICache {

	private Map<String, BaseRecord> cache = new HashMap<>();

	public CacheFileReader(String base) {
		super(base);
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
		cache.values().removeIf(entry -> model.equals(entry.getAMModel()));
	}
	
	@Override
	public void clearCache(BaseRecord rec) {
		if(!RecordUtil.isIdentityRecord(rec)) {
			return;
		}
		cache.entrySet().removeIf(entry ->{
			BaseRecord mr = entry.getValue();
			if(RecordUtil.matchIdentityRecords(rec, mr)) {
				// logger.info("Clear record from cache");
				return true;
			}
			return false;
		});
	}
	
	@Override
	protected synchronized BaseRecord read(IndexEntry entry) throws ReaderException {
		BaseRecord rec = null;
		String pathKey = CryptoUtil.getDigestAsString(FilePathUtil.getFilePath(entry));
		if(cache.containsKey(pathKey)) {
			/// Return a copy of the record in order to not inadvertently pass around a reference that unintentionally altered
			return cache.get(pathKey).copyRecord();
		}
		rec = super.read(entry);
		if(rec != null) {
			cache.put(pathKey, rec);
		}
		return rec;
	}

	@Override
	public void clearCacheByIdx(IndexEntry idx) {
		// TODO Auto-generated method stub
		String type = idx.get(FieldNames.FIELD_TYPE);
		cache.entrySet().removeIf(entry ->{
			BaseRecord mr = entry.getValue();
			if(type.equals(mr.getAMModel()) && RecordUtil.matchIdentityRecordsByIdx(idx, mr)) {
				// logger.info("Clear record from cache");
				return true;
			}
			return false;
		});
	}

}
