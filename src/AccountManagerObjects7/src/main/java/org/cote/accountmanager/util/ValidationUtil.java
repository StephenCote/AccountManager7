package org.cote.accountmanager.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IExecutable;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.FunctionEnumType;
import org.cote.accountmanager.schema.type.ValidationEnumType;

public class ValidationUtil {
	public static final Logger logger = LogManager.getLogger(ValidationUtil.class);
	
	private static Map<String,Pattern> patterns = new ConcurrentHashMap<>();
	private static Map<String, BaseRecord> rules = new ConcurrentHashMap<>();
	
	public static String[] SYSTEM_RULES = new String[] {
		"email", "minLen5", "notEmpty", "notInParent", "trimBegin", "trimEnd", "trimEnds"
	};
	private static Pattern ruleTokenPattern = Pattern.compile("\"[\\$\\!\\&]{1}\\{([A-Za-z]+)\\}\"", Pattern.MULTILINE);
	
	public static void clearCache() {
		patterns.clear();
		rules.clear();
	}
	
	private static Pattern getPattern(String pat){
		if(patterns.containsKey(pat)) {
			return patterns.get(pat);
		}
		Pattern pattern = Pattern.compile(pat, Pattern.MULTILINE);
		//Pattern pattern = Pattern.compile(pat);
		patterns.put(pat, pattern);
		return pattern;
	}
	public static String getScriptDataObject(String ruleName) {
		String rec = ResourceUtil.getScriptResourceObject(ruleName);
		return getResource(ruleName, rec, ruleTokenPattern);
	}	
	public static String getResourceFunction(String ruleName) {
		String rec = ResourceUtil.getFunctionResourceObject(ruleName);
		return getResource(ruleName, rec, ruleTokenPattern);
	}	
	public static String getResourceRule(String ruleName) {
		String rec = ResourceUtil.getValidationRuleResource(ruleName);
		return getResource(ruleName, rec, ruleTokenPattern);
	}
	private static String getResource(String name, String rec, Pattern pat) {
		if(rec == null) {
			logger.error("Failed to load resource rule: " + name);
			return null;
		}
		Matcher m = pat.matcher(rec);
		int idx = 0;
		StringBuilder rep = new StringBuilder();
		while (m.find()) {
			String match = m.group(0);
			String token = m.group(1);
			String nrec = "";
			if(match.startsWith("\"$")) {
				nrec = getResourceRule(token);
			}
			else if(match.startsWith("\"&")) {
				nrec = getScriptDataObject(token);
			}
			else if(match.startsWith("\"!")) {
				nrec = getResourceFunction(token);
			}
			else {
				logger.error("Failed to handle token: " + match);
			}
		    rep.append(rec, idx, m.start()).append(nrec);
		    idx = m.end();
		}
		if (idx < rec.length()) {
		    rep.append(rec, idx, rec.length());
		}

		return rep.toString();
	}
	
	public static BaseRecord getRule(String rule) {
		BaseRecord outRule = null;
		if(rule == null || rule.length() == 0) {
			logger.error("Invalid rule name");
			return outRule;
		}
		if(!rules.containsKey(rule)) {
			if(rule.startsWith("$")) {
				String resource = getResourceRule(rule.substring(1));
				// logger.info("Resource: " + resource);
				if(resource != null) {
					outRule = RecordFactory.importRecord(resource);
					if(outRule != null) {
						rules.put(rule, outRule);
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
			outRule = rules.get(rule);
		}
		return outRule;
	}
	
	
	public static boolean validateFieldWithRule(BaseRecord record, FieldType field, BaseRecord rule) {
		boolean outBool = false;
		boolean out_return_bool = true;
		boolean child_return = false;
		if(IOSystem.getActiveContext() != null) {
			IOSystem.getActiveContext().getRecordUtil().populate(rule);
		}
		
		ValidationEnumType ruleType = ValidationEnumType.valueOf(rule.get(FieldNames.FIELD_TYPE));
		boolean compare = rule.get(FieldNames.FIELD_COMPARISON);
		boolean allowNull = rule.get(FieldNames.FIELD_ALLOW_NULL);
		List<BaseRecord> rules = rule.get(FieldNames.FIELD_RULES);

		for(BaseRecord r : rules) {
			child_return = validateFieldWithRule(record, field, r);
			if(!child_return && out_return_bool) {
				out_return_bool = false;
			}
		}
		FieldEnumType fet = field.getValueType();
		if(fet == FieldEnumType.BLOB || fet == FieldEnumType.MODEL || fet == FieldEnumType.BYTE || fet == FieldEnumType.LIST){
			logger.info("Binary or foreign field type " + fet.toString() + " not currently supported.");
			return true;
		}
		
		if(fet != FieldEnumType.LONG && fet != FieldEnumType.INT && fet != FieldEnumType.STRING && fet != FieldEnumType.ENUM) {
			logger.info("Field type " + fet.toString() + " not currently supported.");
			return true;
			
		}
		
		String expression = rule.get(FieldNames.FIELD_EXPRESSION);

		if(expression != null && expression.length() > 0){
			Pattern exp = getPattern(expression);
			String val = field.getValue();
			Matcher m = null;
			if(val != null) {
				m = exp.matcher(val);
			}
			switch(ruleType){
				case REPLACEMENT:
					String repVal = rule.get(FieldNames.FIELD_REPLACEMENT_VALUE);
					outBool = true;
					if(repVal != null && m != null && m.find()){
						val = m.replaceAll(repVal);
						logger.debug("Rule " + rule.get(FieldNames.FIELD_NAME) + " replaced value with '" + val + "'");
						try {
							field.setValue(val);
						} catch (ValueException e) {
							logger.error(e);
						}
					}
					else{
						logger.debug("Rule " + rule.get(FieldNames.FIELD_NAME) + " did not match " + expression + " with value " + val + ".  Marking validation as true because it's a replacement rule, not a match rule.");
					}
					break;
				case BOOLEAN:
					if(
						(allowNull && (val == null || val.length() == 0))
						||
						(m != null && m.find() == compare)
					){
						logger.debug("Rule " + rule.get(FieldNames.FIELD_NAME) + " matched value '" + val + "'");
						outBool = true;
					}
					else{
						logger.warn("Validation of " + record.getModel() + "." + field.getName() + " (" + val + ") failed pattern " + expression + " because " + (m != null && m.matches()) + " was false or " + allowNull + " is true and " +(val == null || val.length() == 0));
					}
					break;
				default:
					logger.warn("Rule " + rule.get(FieldNames.FIELD_NAME) + " with type " + ruleType + " was not handled");
					break;
			}
		}
		else if(ruleType == ValidationEnumType.FUNCTION){
			BaseRecord func = rule.get(FieldNames.FIELD_FUNCTION);
			if(func == null) {
				logger.error("Function is null");
			}
			else {
				FunctionEnumType ftype = FunctionEnumType.valueOf(func.get(FieldNames.FIELD_TYPE));
				switch(ftype) {
					case JAVASCRIPT:
						BaseRecord scriptData = func.get(FieldNames.FIELD_DATA);
						if(scriptData == null) {
							logger.error("Script data is null");
						}
						else {
							//logger.info(scriptData.toFullString());
							
							Map<String,Object> params = ScriptUtil.getCommonParameterMap(null);
							params.put("field", field);
							params.put("record", record);
							params.put("ioContext", IOSystem.getActiveContext());

							try {
								boolean valid = ScriptUtil.run(Boolean.class, params, scriptData);
								outBool = valid;
							} catch (ScriptException e) {
								logger.error(e);
							}
						}
						break;
					case JAVA:
						String className = func.get(FieldNames.FIELD_CLASS);
						IExecutable exec = RecordFactory.getClassInstance(className);
						if(exec != null) {
							outBool = exec.execute(rule, record, field);
						}
						break;
					default:
						logger.error("Unhandled type: " + fet.toString());
						break;
				}
				
			}
		}
		else if(ruleType == ValidationEnumType.NONE){
			logger.debug("Rule " + rule.get(FieldNames.FIELD_NAME) + " does not contain an expression and is set to validation type " + ruleType + ".  Marking validation as true.");
			outBool = true;
		}
		else{
			logger.debug("Rule " + rule.get(FieldNames.FIELD_NAME) + " does not define a pattern.  Marking validation as true.");
			outBool = true;
		}
		logger.debug("Rule " + rule.get(FieldNames.FIELD_NAME) + " returns " + (outBool && out_return_bool));
		return (outBool && out_return_bool);
	}
}
