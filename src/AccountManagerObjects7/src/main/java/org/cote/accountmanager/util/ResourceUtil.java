package org.cote.accountmanager.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ResourceUtil {

	public static final Logger logger = LogManager.getLogger(ResourceUtil.class);
	
	private static Map<String, String> cache = new ConcurrentHashMap<>();
	public static String RESOURCE_PREFIX = "";

	public static String getCategoryResource(String name) {
		return getResource(RESOURCE_PREFIX + "categories/" + name + "Category.json");
	}
	public static String getModelResource(String name) {
		return getResource(RESOURCE_PREFIX + "models/" + name + "Model.json");
	}
	public static String getFunctionResource(String name) {
		return getResource(RESOURCE_PREFIX + "functions/" + name + "Function.js");
	}
	public static String getFactResource(String name) {
		return getResource(RESOURCE_PREFIX + "facts/" + name + "Fact.json");
	}
	public static String getPatternResource(String name) {
		return getResource(RESOURCE_PREFIX + "patterns/" + name + "Pattern.json");
	}
	public static String getRuleResource(String name) {
		return getResource(RESOURCE_PREFIX + "rules/" + name + "Rule.json");
	}
	public static String getValidationRuleResource(String name) {
		return getResource(RESOURCE_PREFIX + "validationRules/" + name + "Rule.json");
	}
	public static String getPolicyResource(String name) {
		return getResource(RESOURCE_PREFIX + "policies/" + name + "Policy.json");
	}
	public static String getResource(String path) {
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
