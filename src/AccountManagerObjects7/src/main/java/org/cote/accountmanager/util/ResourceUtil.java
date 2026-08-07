package org.cote.accountmanager.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ResourceUtil {

	public static final Logger logger = LogManager.getLogger(ResourceUtil.class);
	
	private static Map<String, String> cache = new ConcurrentHashMap<>();
	private static Map<String, byte[]> binaryCache = new ConcurrentHashMap<>();
	public String resourcePrefix = "";
	
	private static ResourceUtil _instance = null;
	public ResourceUtil() {
		
	}
	public void setResourcePrefix(String pref) {
		resourcePrefix = pref;
	}
	public static ResourceUtil getInstance() {
		if(_instance == null) {
			_instance = new ResourceUtil();
		}
		return _instance;
	}
	
	public static void clearCache() {
		cache.clear();
		binaryCache.clear();
	}
	
	public String getCategoryResource(String name) {
		return getResource(resourcePrefix + "categories/" + name + "Category.json");
	}

	/// Feature manifest resources: resources/features/<name>Manifest.json
	/// Same shape as getCategoryResource - honors resourcePrefix and reuses the caching getResource.
	public String getFeatureManifestResource(String name) {
		return getResource(resourcePrefix + "features/" + name + "Manifest.json");
	}
	
	public void releaseModelResource(String name) {
		cache.remove(name.replaceAll("\\.", "/"));
	}
	
	public String getModelResource(String name) {
		String namePath = name.replaceAll("\\.", "/");
		return getResource(resourcePrefix + "models/" + namePath + "Model.json");
	}
	public String getFunctionResource(String name) {
		return getResource(resourcePrefix + "functions/" + name + "Function.js");
	}
	public String getFunctionResourceObject(String name) {
		return getResource(resourcePrefix + "functions/" + name + "Function.json");
	}
	public String getScriptResourceObject(String name) {
		String rec = BinaryUtil.toBase64Str(getResource(resourcePrefix + "functions/javascript/" + name + "Function.js"));
		return "{\"dataBytesStore\": \"" + rec + "\"}";
	}
	
	public String getFactResource(String name) {
		return getResource(resourcePrefix + "facts/" + name + "Fact.json");
	}
	public String getPatternResource(String name) {
		return getResource(resourcePrefix + "patterns/" + name + "Pattern.json");
	}
	public String getRuleResource(String name) {
		return getResource(resourcePrefix + "rules/" + name + "Rule.json");
	}
	public String getValidationRuleResource(String name) {
		return getResource(resourcePrefix + "validationRules/" + name + "Rule.json");
	}
	public String getPolicyResource(String name) {
		return getResource(resourcePrefix + "policies/" + name + "Policy.json");
	}
	/// Load a BINARY classpath resource (images, etc.). getResource() decodes as UTF-8 and would
	/// corrupt anything that isn't text, so binary callers must come through here. Cached separately
	/// from the text cache; the resources this is used for are small and immutable at runtime (the
	/// mannequin base images are ~30KB each and are read once per wear level during SD generation).
	/// Returns null when the resource is absent, matching getResource()'s contract.
	public byte[] getBinaryResource(String path) {
		byte[] cached = binaryCache.get(path);
		if(cached != null) {
			return cached;
		}
		InputStream srs = ResourceUtil.class.getClassLoader().getResourceAsStream(path);
		if(srs == null) {
			logger.warn("Failed to load binary resource " + path);
			return null;
		}
		byte[] data = null;
		try(BufferedInputStream is = new BufferedInputStream(srs)) {
			data = StreamUtil.getStreamBytes(is);
		} catch (IOException e) {
			logger.error("IOException reading binary resource " + path + ": " + e.getMessage());
			return null;
		}
		if(data != null && data.length > 0) {
			binaryCache.put(path, data);
		}
		return data;
	}

	public String getResource(String path) {
		if(cache.containsKey(path)) {
			return cache.get(path);
		}
		//InputStream srs = ClassLoader.getSystemResourceAsStream(path);
		//InputStream srs = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
		InputStream srs = ResourceUtil.class.getClassLoader().getResourceAsStream(path);
		if(srs == null) {
			logger.warn("Failed to load " + path);
			ErrorUtil.printStackTrace();
			return null;
		}
		BufferedInputStream is = new BufferedInputStream(srs);
		String file = null;
		try {
			file = StreamUtil.streamToString(is);
		} catch (IOException e) {
			logger.error("IOException: " + e.getMessage());
			
		}
		finally {
			try {
				is.close();
			} catch (IOException e) {
				//logger.error(e);
			}
		}
		if(file != null && file.length() > 0) {
			cache.put(path, file);
		}
		return file;
	}
	
}
