package org.cote.accountmanager.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.cache.ICache;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.CryptoUtil;

public class CacheAuthorizationUtil extends AuthorizationUtil implements ICache {
	
	private Map<String, Boolean> decisionCache = new ConcurrentHashMap<>(); 
	private Map<String, List<String>> keyCache = new ConcurrentHashMap<>();
	
	public CacheAuthorizationUtil(IReader reader, IWriter writer, ISearch search) {
		super(reader, writer, search);
		CacheUtil.addProvider(this);

	}
	
	private String getCacheKey(BaseRecord actor, BaseRecord permission, BaseRecord object) {
		return CryptoUtil.getDigestAsString(actor.get(FieldNames.FIELD_URN) + "-" + permission.get(FieldNames.FIELD_URN) + "-" + object.get(FieldNames.FIELD_URN));
	}
	
	@Override
	public boolean checkEntitlement(BaseRecord actor, BaseRecord permission, BaseRecord object) {
		String key = getCacheKey(actor, permission, object);
		if(decisionCache.containsKey(key)) {
			logger.info("Cache hit: " + key);
			return decisionCache.get(key);
		}
		boolean check = super.checkEntitlement(actor, permission, object);
		String aurn = actor.get(FieldNames.FIELD_URN);
		String ourn = object.get(FieldNames.FIELD_URN);
		if(!keyCache.containsKey(aurn)) {
			keyCache.put(aurn, new ArrayList<>());
		}
		if(!keyCache.containsKey(ourn)) {
			keyCache.put(ourn, new ArrayList<>());
		}
		logger.info("Cache result: " + key + " = " + check);
		keyCache.get(aurn).add(key);
		keyCache.get(ourn).add(key);
		decisionCache.put(key, check);
		
		return check;
	}

	@Override
	public void clearCache() {
		// TODO Auto-generated method stub
		decisionCache.clear();
		keyCache.clear();
	}

	@Override
	public void clearCache(String key) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void clearCache(BaseRecord rec) {
		// TODO Auto-generated method stub
		if(rec.hasField(FieldNames.FIELD_URN)) {
			String urn = rec.get(FieldNames.FIELD_URN);
			if(keyCache.containsKey(urn)) {
				keyCache.get(urn).forEach((v) -> {
					decisionCache.remove(v);
				});
				keyCache.remove(urn);
			}
		}
	}

	@Override
	public void clearCacheByIdx(IndexEntry idx) {
		// TODO Auto-generated method stub

	}

	@Override
	public void cleanupCache() {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void clearCacheByModel(String model) {
		// TODO Auto-generated method stub

	}
	
}
