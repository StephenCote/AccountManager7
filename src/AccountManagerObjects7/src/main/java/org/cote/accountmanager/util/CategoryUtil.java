package org.cote.accountmanager.util;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;

public class CategoryUtil {
public static final Logger logger = LogManager.getLogger(CategoryUtil.class);
	
	private static Map<String, BaseRecord> categories = new ConcurrentHashMap<>();
	
	private static Pattern ruleTokenPattern = Pattern.compile("\"\\$\\{([A-Za-z]+)\\}\"", Pattern.MULTILINE);
	
	public static String[] CATEGORY_NAMES = new String[] {"identity", "asset", "business", "process", "event", "project", "policy", "resource", "form"};
	
	public static void clearCache() {
		categories.clear();
	}
	
	public static String getResourceCategory(String ruleName) {
		String rec = ResourceUtil.getCategoryResource(ruleName);
		if(rec == null) {
			logger.error("Failed to load category: " + ruleName);
			return null;
		}
		
		Matcher m = ruleTokenPattern.matcher(rec);
		int idx = 0;
		StringBuilder rep = new StringBuilder();
		while (m.find()) {
			String token = m.group(1);
			String nrec = getResourceCategory(token);
		    rep.append(rec, idx, m.start()).append(nrec);
		    idx = m.end();
		}
		if (idx < rec.length()) {
		    rep.append(rec, idx, rec.length());
		}

		return rep.toString();
	}
	
	public static BaseRecord getCategory(String cat) {
		BaseRecord outCat = null;
		if(cat == null || cat.length() == 0) {
			logger.error("Invalid category name");
			return outCat;
		}
		if(!categories.containsKey(cat)) {
			if(cat.startsWith("$")) {
				String resource = getResourceCategory(cat.substring(1));
				if(resource != null) {
					outCat = RecordFactory.importRecord(resource);
					if(outCat != null) {
						categories.put(cat, outCat);
					}
					else {
						logger.error("Failed to import rule from " + resource);
					}
				}
				else {
					/// logger.error("Failed to load rule");
				}
			}
			else {
				logger.warn("TODO: Load rule via urn");
			}
		}
		else {
			outCat = categories.get(cat);
		}
		return outCat;
	}
}
