package org.cote.accountmanager.io.file.cache;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.file.FileWriter;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.util.CryptoUtil;

public class CacheFileWriter extends FileWriter {

	public CacheFileWriter(String base) {
		super(base);
		// TODO Auto-generated constructor stub
	}
	
	@Override
	protected void prepareWrite(IndexEntry entry, String path, String oldPath) {
		super.prepareWrite(entry, path, oldPath);
		String pathKey = CryptoUtil.getDigestAsString(path);
		String pathKey2 = (oldPath != null ? CryptoUtil.getDigestAsString(path) : null);

		CacheUtil.clearCache(pathKey);
		if(pathKey2 != null) {
			CacheUtil.clearCache(pathKey2);
		}
	}

	@Override
	protected void prepareDelete(IndexEntry entry, String path) {
		super.prepareDelete(entry, path);
		String pathKey = CryptoUtil.getDigestAsString(path);
		CacheUtil.clearCache(pathKey);
		CacheUtil.clearCacheByIdx(entry);
	}
	
}
