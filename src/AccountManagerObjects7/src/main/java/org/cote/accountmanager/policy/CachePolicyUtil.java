package org.cote.accountmanager.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.cache.ICache;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.CryptoUtil;

public class CachePolicyUtil extends PolicyUtil implements ICache {
	
	private final Map<String, PolicyResponseType> responseCache;
	private final Map<String, List<String>> actorCache;
	private final Map<String, List<String>> resourceCache;

	public CachePolicyUtil(IReader reader, IWriter writer, ISearch search) {
		super(reader, writer, search);
		responseCache = new HashMap<>();
		actorCache = new HashMap<>();
		resourceCache = new HashMap<>();
		CacheUtil.addProvider(this);

	}
	private String getContextKey(BaseRecord contextUser, String policyName, String actorUrn, String resourceUrn) throws ValueException {

		if(contextUser == null || !contextUser.hasField(FieldNames.FIELD_URN)) {
			throw new ValueException("Expected a context user with a urn");
		}
		if(policyName == null) {
			throw new ValueException("Expected a policy name");
		}
		if(actorUrn == null) {
			throw new ValueException("Expected an actor with a urn");
		}
		if(resourceUrn == null) {
			throw new ValueException("Expected a resource with a urn");
		}
		String urn = contextUser.get(FieldNames.FIELD_URN);
		if(urn == null) {
			throw new ValueException("Context urn was null");
		}
		return policyName + "-" + urn + "-" + actorUrn + "-" + resourceUrn;
	}
	private String getContextHash(BaseRecord contextUser, String policyName, String actorUrn, String resourceUrn) throws ValueException {
		return CryptoUtil.getDigestAsString(getContextKey(contextUser, policyName, actorUrn, resourceUrn));
	}
	
	@Override
	public PolicyResponseType evaluateResourcePolicy(BaseRecord contextUser, String policyName, BaseRecord actor, BaseRecord resource) {
		String hash = null;
		PolicyResponseType prr = null;
		try {
			String recU =  resource.get(FieldNames.FIELD_URN);
			if(recU == null) {
				recU = resource.hash();
			}
			// logger.info("Context Key: " + getContextKey(contextUser, policyName, actor.get(FieldNames.FIELD_URN), recU));
			hash = getContextHash(contextUser, policyName, actor.get(FieldNames.FIELD_URN), recU);
			if(responseCache.containsKey(hash)) {
				logger.debug("Cache hit " + policyName + " " + hash);
				prr = responseCache.get(hash);
				if(getPolicyResponseExpired(prr)) {
					logger.info("Response expired");
					responseCache.remove(hash);
					prr = null;
				}
			}
			if(prr == null) {
				prr = super.evaluateResourcePolicy(contextUser, policyName, actor, resource);
				if(prr != null) {
					cache(hash, prr, actor.get(FieldNames.FIELD_URN), recU);
				}
			}
		} catch (ValueException e) {
			logger.error(e);
			
		}
		
		return prr;
	}
	
	private void cache(String key, PolicyResponseType prr, String actorUrn, String resourceUrn) {
		if(!responseCache.containsKey(key)) {
			// logger.info("Cache " + key);
			responseCache.put(key,  prr);
			if(actorUrn != null) {
				if(!actorCache.containsKey(actorUrn)) {
					actorCache.put(actorUrn, new ArrayList<>());
				}
				actorCache.get(actorUrn).add(key);
			}
			if(resourceUrn != null) {
				if(!resourceCache.containsKey(resourceUrn)) {
					resourceCache.put(resourceUrn, new ArrayList<>());
				}
				resourceCache.get(resourceUrn).add(key);
			}
		}
	}

	@Override
	public void clearCache() {
		// TODO Auto-generated method stub
		responseCache.clear();
		actorCache.clear();
		resourceCache.clear();
	}

	@Override
	public void clearCache(String key) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void clearCache(BaseRecord rec) {
		// TODO Auto-generated method stub
		String urn = rec.get(FieldNames.FIELD_URN);
		if(urn != null) {
			
			List<String> keys = new ArrayList<>();
			if(actorCache.containsKey(urn)) {
				keys.addAll(actorCache.get(urn));
				actorCache.remove(urn);
			}
			if(resourceCache.containsKey(urn)) {
				keys.addAll(resourceCache.get(urn));
				resourceCache.remove(urn);
			}
			for(String key : keys) {
				responseCache.remove(key);
			}
			
		}
	}

	@Override
	public void cleanupCache() {
		clearCache();
		CacheUtil.removeProvider(this);
	}
	
	@Override
	public void close() {
		cleanupCache();
	}

	@Override
	public void clearCacheByIdx(IndexEntry idx) {
		// TODO Auto-generated method stub
		
	}

}
