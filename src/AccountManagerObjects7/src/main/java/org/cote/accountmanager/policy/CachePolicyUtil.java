package org.cote.accountmanager.policy;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.cache.ICache;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.file.IndexEntry;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.CryptoUtil;

public class CachePolicyUtil extends PolicyUtil implements ICache {
	
	private Map<String, PolicyType> policyCache;
	private Map<String, PolicyResponseType> responseCache;
	private Map<String, List<String>> actorCache;
	private Map<String, List<String>> resourceCache;
	
	private int maximumCacheSize = 10000;
	private int maximumCacheAgeMS = 360000;
	private long cacheRefreshed = 0L;

	public CachePolicyUtil(IReader reader, IWriter writer, ISearch search) {
		super(reader, writer, search);
		init();

	}
	public CachePolicyUtil(IOContext context) {
		super(context);
		init();
	}
	private void init() {
		cacheRefreshed = System.currentTimeMillis();
		policyCache = new ConcurrentHashMap<>();
		responseCache = new ConcurrentHashMap<>();
		actorCache = new ConcurrentHashMap<>();
		resourceCache = new ConcurrentHashMap<>();
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
	public BaseRecord getResourcePolicy(String name, BaseRecord actor, String token, BaseRecord resource) throws ReaderException {
		String recId = null;
		if(resource.hasField(FieldNames.FIELD_URN)) {
			recId = resource.get(FieldNames.FIELD_URN);
		}
		else {
			recId = resource.hash();
		}
		String key = name + "-" + actor.get(FieldNames.FIELD_URN)+ "-" + token + "-" + recId;
		String hash = CryptoUtil.getDigestAsString(key);
		if(!policyCache.containsKey(hash)) {
			PolicyType pol = super.getResourcePolicy(name, actor, token, resource).toConcrete();
			policyCache.put(hash, pol);
			if(isTrace()) {
				logger.info("Cache resource policy for " + key);
			}
		}
		else {
			if(isTrace()) {
				logger.info("Using cached resource policy for " + key);
			}
		}
		return policyCache.get(hash);
	}
	
	@Override
	public PolicyResponseType evaluateResourcePolicy(BaseRecord contextUser, String policyName, BaseRecord actor, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, policyName, actor, null, resource);
	}
	
	@Override
	public PolicyResponseType evaluateResourcePolicy(BaseRecord contextUser, String policyName, BaseRecord actor, String accessToken, BaseRecord resource) {
		String hash = null;
		PolicyResponseType prr = null;
		try {
			String recU =  null;
			if(resource.hasField(FieldNames.FIELD_URN)) {
				resource.get(FieldNames.FIELD_URN);
			}
			if(recU == null) {
				recU = resource.hash();
			}
			//logger.info("Context Key: " + getContextKey(contextUser, policyName, actor.get(FieldNames.FIELD_URN), recU));
			hash = getContextHash(contextUser, policyName, actor.get(FieldNames.FIELD_URN), recU);
			if(responseCache.containsKey(hash)) {
				if(isTrace()) {
					logger.info("Cache hit " + policyName + " " + hash);
				}
				prr = responseCache.get(hash);
				if(getPolicyResponseExpired(prr)) {
					if(isTrace()) {
						logger.info("Response expired");
					}
					responseCache.remove(hash);
					prr = null;
				}
			}
			if(prr == null) {
				prr = super.evaluateResourcePolicy(contextUser, policyName, actor, accessToken, resource);
				if(prr != null) {
					cache(hash, prr, actor.get(FieldNames.FIELD_URN), recU);
				}
			}
		} catch (ValueException e) {
			logger.error(e);
			
		}
		
		return prr;
	}
	
	private synchronized void checkCache() {
		long now = System.currentTimeMillis();
		if( (now - cacheRefreshed) > maximumCacheAgeMS
			|| responseCache.size() > maximumCacheSize
			|| resourceCache.size() > maximumCacheSize
			|| actorCache.size() > maximumCacheSize
		){
			logger.info("Clearing policy cache");
			cacheRefreshed = now;
			responseCache.clear();
			actorCache.clear();
			resourceCache.clear();
		}
	}
	
	private synchronized void cache(String key, PolicyResponseType prr, String actorUrn, String resourceUrn) {
		checkCache();
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
	public void clearCacheByModel(String model) {
		// TODO Auto-generated method stub

	}

	@Override
	public void clearCache(BaseRecord rec) {
		// TODO Auto-generated method stub
		String urn = null;
		if(rec.hasField(FieldNames.FIELD_URN)) {
			urn = rec.get(FieldNames.FIELD_URN);
		}
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
