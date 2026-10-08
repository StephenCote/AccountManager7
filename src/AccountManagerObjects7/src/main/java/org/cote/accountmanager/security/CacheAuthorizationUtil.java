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

/// Entitlement-decision cache over AuthorizationUtil. NOT wired by default: IOFactory.getAuthorizationUtil
/// returns the plain AuthorizationUtil, so this class is only live where it is constructed explicitly.
/// Decisions are keyed by a digest of actor + permission + object urns and evicted per record by urn.
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
		decisionCache.clear();
		keyCache.clear();
	}

	/// Intentional no-op: the keys passed to CacheUtil.clearCache(String) are query hashes and
	/// file-path digests, never an entitlement digest, so there is nothing here to match.
	@Override
	public void clearCache(String key) {

	}

	/// Evicts every entitlement decision in which rec was the actor or the object, by urn.
	@Override
	public void clearCache(BaseRecord rec) {
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

	/// Intentional no-op. An IndexEntry is the file-IO index record and carries no urn, so no
	/// decision can be selected by it; see CachePolicyUtil.clearCacheByIdx for the file-IO gap.
	@Override
	public void clearCacheByIdx(IndexEntry idx) {

	}

	/// Same contract as the other ICache providers: drop everything and leave the provider set, so a
	/// closed instance is not kept alive (and fanned out to) by CacheUtil. Before 2026-10-07 this was
	/// empty and the registration taken in the constructor was never released.
	@Override
	public void cleanupCache() {
		clearCache();
		CacheUtil.removeProvider(this);
	}

	/// Intentional no-op. Decisions are indexed by actor and object urn, not by model; a model-wide
	/// eviction is clearCache(). MemberUtil.clearParticipationQueryCache documents the consequence.
	@Override
	public void clearCacheByModel(String model) {

	}
	
}
