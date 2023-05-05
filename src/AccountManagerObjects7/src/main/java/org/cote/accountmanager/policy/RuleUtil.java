
package org.cote.accountmanager.policy;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.schema.type.ComparatorEnumType;


public class RuleUtil {
	private static final Pattern intPattern = Pattern.compile("^\\d+$");
	public static final Logger logger = LogManager.getLogger(RuleUtil.class);
	private static final Map<String,Pattern> patterns = new HashMap<String,Pattern>();
	
	private static Pattern getPattern(String pattern){
		if(patterns.containsKey(pattern)) return patterns.get(pattern);
		Pattern p = Pattern.compile(pattern);
		patterns.put(pattern, p);
		return p;
	}
	public static boolean compareValue(String chkData, ComparatorEnumType comparator, String compData){
		boolean outBool = false;
		
		if(chkData != null && compData != null && intPattern.matcher(chkData).matches() && intPattern.matcher(compData).matches()){
			long lChk = Long.parseLong(chkData);
			long lComp = Long.parseLong(compData);
			switch(comparator){

				case EQUALS:
					outBool = (lChk == lComp);
					break;
				case GREATER_THAN:
					outBool = (lChk > lComp);
					break;
				case GREATER_THAN_OR_EQUALS:
					outBool = (lChk >= lComp);
					break;
				case LESS_THAN:
					outBool = (lChk < lComp);
					break;
				case LESS_THAN_OR_EQUALS:
					outBool = (lChk <= lComp);
					break;
				case NOT_EQUALS:
					outBool = (lChk != lComp);
					break;
				default:
					logger.error("Unhandled comparator: " + comparator);
					break;

			}
			logger.info("Comparing as long " + chkData + " is " + comparator + " " + compData + " == " + outBool);
		}
		else{

			switch(comparator){
				case LIKE:
					if(compData != null){
						String likePat = "^" + compData.replaceAll("%", ".*") + "$";
						Pattern pat = getPattern(likePat);
						outBool = pat.matcher(chkData).find();
					}
					break;
				case EQUALS:
					if(chkData != null && compData != null) outBool = chkData.equals(compData);
					else if(chkData == null && compData == null){
						logger.info("Both values are null with an equals comparator.  Returning true");
						outBool = true;
					}
					break;
				case IS_NULL:
					outBool = (chkData == null);
					break;
				default:
					logger.error("Unhandled comparator: " + comparator);
					break;

			}
			logger.info("Comparing as string " + chkData + " is " + comparator + " " + compData + " == " + outBool);
		}
		return outBool;
	}
	
}
