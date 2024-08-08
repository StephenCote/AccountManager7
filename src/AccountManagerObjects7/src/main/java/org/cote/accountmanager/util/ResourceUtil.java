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
	}
	
	public String getCategoryResource(String name) {
		return getResource(resourcePrefix + "categories/" + name + "Category.json");
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
	public String getResource(String path) {
		if(cache.containsKey(path)) {
			return cache.get(path);
		}
		//InputStream srs = ClassLoader.getSystemResourceAsStream(path);
		//InputStream srs = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
		InputStream srs = ResourceUtil.class.getClassLoader().getResourceAsStream(path);
		if(srs == null) {
			logger.warn("Failed to load " + path);
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
