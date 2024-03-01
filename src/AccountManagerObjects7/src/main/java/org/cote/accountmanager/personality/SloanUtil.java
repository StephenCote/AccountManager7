package org.cote.accountmanager.personality;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class SloanUtil {
	public static final Logger logger = LogManager.getLogger(SloanUtil.class);
	
	private static Map<String, Sloan> sloanDef = new ConcurrentHashMap<>();
	public static Sloan getSloan(String key) {
		Map<String, Sloan> sloanMap = SloanUtil.getSloanDef();
		if(key != null && sloanMap.containsKey(key)) {
			return sloanMap.get(key);
		}
		else {
			logger.warn("Invalid sloan key '" + key + "'");
		}
		return null;
	}
	
	public static Map<String, Sloan> getSloanDef() {
		if(sloanDef.keySet().size() == 0) {
			String[] sloanJson = JSONUtil.importObject(ResourceUtil.getResource("./olio/sloan.json"), String[].class);
			for(String s: sloanJson) {
				String[] pairs = s.split("\\|");
				String dis = "";
				if(pairs.length > 4) dis = pairs[4];
				sloanDef.put(pairs[0], new Sloan(pairs[0], pairs[1], pairs[2], pairs[3], dis));
			}
		}
		return sloanDef;
	}
	
	private static final String SLOAN_SOCIAL_KEY = "social";
	private static final String SLOAN_RESERVED_KEY = "reserved";
	private static final String SLOAN_LIMBIC_KEY = "limbic";
	private static final String SLOAN_CALM_KEY = "calm";
	private static final String SLOAN_ORGANIZED_KEY = "organized";
	private static final String SLOAN_UNSTRUCTURED_KEY = "unstructured";
	private static final String SLOAN_ACCOMMODATING_KEY = "accommodating";
	private static final String SLOAN_EGOCENTRIC_KEY = "egocentric";
	private static final String SLOAN_NONCURIOUS_KEY = "non-curious";
	private static final String SLOAN_INQUISITIVE_KEY = "inquisitive";

	public static String getSloanKey(BaseRecord rec) {
		double ext = rec.get(ProfileUtil.PERSONALITY_FIELDS[2]);
		double neu = rec.get(ProfileUtil.PERSONALITY_FIELDS[4]);
		double con = rec.get(ProfileUtil.PERSONALITY_FIELDS[1]);
		double agr = rec.get(ProfileUtil.PERSONALITY_FIELDS[3]);
		double ope = rec.get(ProfileUtil.PERSONALITY_FIELDS[0]);
		StringBuilder bld = new StringBuilder();
		if(ext > 0.5) {
			bld.append(SLOAN_SOCIAL_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_RESERVED_KEY.substring(0,1));			
		}
		if(neu > 0.5) {
			bld.append(SLOAN_LIMBIC_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_CALM_KEY.substring(0,1));			
		}
		if(con > 0.5) {
			bld.append(SLOAN_ORGANIZED_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_UNSTRUCTURED_KEY.substring(0,1));			
		}
		if(agr > 0.5) {
			bld.append(SLOAN_ACCOMMODATING_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_EGOCENTRIC_KEY.substring(0,1));			
		}
		if(ope > 0.5) {
			bld.append(SLOAN_INQUISITIVE_KEY.substring(0,1));
		}
		else {
			bld.append(SLOAN_NONCURIOUS_KEY.substring(0,1));			
		}
		return bld.toString();
	}
	
	public static String getSloanCardinal(BaseRecord rec) {
		return Collections.max(Arrays.asList(ProfileUtil.PERSONALITY_FIELDS), Comparator.comparing(c -> (double)rec.get(c)));
	}
}
