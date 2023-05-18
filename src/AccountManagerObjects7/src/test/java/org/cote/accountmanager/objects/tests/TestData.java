package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;

import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.schema.type.ValidationEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ContentTypeUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.GraphicsUtil;
import org.cote.accountmanager.util.ThumbnailUtil;
import org.junit.Test;

public class TestData extends BaseTest {
	/*
	ValidationRuleType rule1 = getCreateRule(testUser, groupV, "Not Empty", null);

	ValidationRuleType trimBegin = getCreateRule(testUser, groupV, "Trim Begin", "^\\s+");
	trimBegin.setReplacementValue("");
	trimBegin.setIsReplacementRule(true);
	trimBegin.setValidationType(ValidationEnumType.REPLACEMENT);
	((ValidationRuleFactory)Factories.getFactory(FactoryEnumType.VALIDATIONRULE)).updateValidationRule(trimBegin);
	
	ValidationRuleType trimEnd = getCreateRule(testUser, groupV, "Trim End", "\\s+$");
	trimEnd.setReplacementValue("");
	trimEnd.setValidationType(ValidationEnumType.REPLACEMENT);
	trimEnd.setIsReplacementRule(true);
	((ValidationRuleFactory)Factories.getFactory(FactoryEnumType.VALIDATIONRULE)).updateValidationRule(trimEnd);
	
	rule1.setIsRuleSet(true);
	rule1.getRules().add(trimBegin);
	rule1.getRules().add(trimEnd);
	((ValidationRuleFactory)Factories.getFactory(FactoryEnumType.VALIDATIONRULE)).updateValidationRule(rule1);
	*/
	
	private String trimBeginRule = """
		{
			"model": "validationRule",
			"name": "trimBegin",
			"expression": "^\s+",
			"type": "replacement",
			"replacementValue": "",
			"replacementValueType": "string"
		}			
	""";
	
	private String trimEndRule = """
		{
			"model": "validationRule",
			"name": "trimEnd",
			"expression": "\s+$",
			"type": "replacement",
			"replacementValue": "",
			"replacementValueType": "string"
		}			
	""";
	private String trimEndsRule = """
		{
			"model": "validationRule",
			"name": "trimEnds",
			"type": "none",
			"rules": [""" + trimBeginRule + ", " + trimEndRule + """
			]
		}			
	""";
	private String notEmptyRule = """
		{
			"model": "validationRule",
			"name": "notEmpty",
			"type": "boolean",
			"expression": "\\\\S",
			"rules": [""" + trimEndsRule + """
			]
		}			
	""";
	private String emailRule = """
		{
			"model": "validationRule",
			"name": "emailRule",
			"expression": "^[a-zA-Z0-9_!#$%&’*+/=?`{|}~^.-]+@[a-zA-Z0-9.-]+$",
			"type": "boolean"
		}
	""";
	
	@Test
	public void TestFieldValidation() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord rule = RecordFactory.importRecord(notEmptyRule);
		logger.info(rule.toFullString());
		
		Pattern p = getPattern("^\s+");
		String test = "    TT";
		Matcher m = p.matcher(test);
		logger.info("Test: " + m.matches());
		
		BaseRecord data = null;
		try {
			data = RecordFactory.newInstance(ModelNames.MODEL_DATA);
			data.set(FieldNames.FIELD_NAME, "   This   ");
			boolean valid = validateFieldWithRule(data, data.getField(FieldNames.FIELD_NAME), rule);
			logger.info("Valid: " + valid);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	
	public void evaluateRule(BaseRecord record, FieldType field, BaseRecord rule) {
		
	}
	
	private static Map<String,Pattern> patterns = new HashMap<String,Pattern>();
	
	private static Pattern getPattern(String pat){
		if(patterns.containsKey(pat)) {
			return patterns.get(pat);
		}
		Pattern pattern = Pattern.compile(pat, Pattern.MULTILINE);
		//Pattern pattern = Pattern.compile(pat);
		patterns.put(pat, pattern);
		return pattern;
	}
	public static boolean validateFieldWithRule(BaseRecord record, FieldType field, BaseRecord rule) {
		boolean outBool = false;
		boolean out_return_bool = true;
		boolean child_return = false;
		IOSystem.getActiveContext().getRecordUtil().populate(rule);
		
		ValidationEnumType ruleType = ValidationEnumType.valueOf(rule.get(FieldNames.FIELD_TYPE));
		boolean compare = rule.get("comparison");
		boolean allowNull = rule.get("allowNull");
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
		
		if(fet != FieldEnumType.STRING && fet != FieldEnumType.ENUM) {
			logger.info("Field type " + fet.toString() + " not currently supported.");
			return true;
			
		}
		
		String expression = rule.get("expression");

		if(expression != null && expression.length() > 0){
			Pattern exp = getPattern(expression);
			String val = field.getValue();
			Matcher m = null;
			if(val != null) {
				m = exp.matcher(val);
			}
			switch(ruleType){
				case REPLACEMENT:
					String repVal = rule.get("replacementValue");
					outBool = true;
					if(repVal != null && m != null && m.matches()){
						val = m.replaceAll(repVal);
						logger.info("Rule " + rule.get(FieldNames.FIELD_NAME) + " replaced value with '" + val + "'");
						try {
							field.setValue(val);
						} catch (ValueException e) {
							logger.error(e);
						}
					}
					else{
						logger.info("Rule " + rule.get(FieldNames.FIELD_NAME) + " did not match " + expression + " with value " + val + ".  Marking validation as true because it's a replacement rule, not a match rule.");
					}
					break;
				case BOOLEAN:
					if(
						(allowNull && (val == null || val.length() == 0))
						||
						(m != null && m.matches() == compare)
					){
						logger.info("Rule " + rule.get(FieldNames.FIELD_NAME) + " matched value '" + val + "'");
						outBool = true;
					}
					else{
						logger.warn("Validation of " + field.getName() + " failed pattern " + expression + " because " + m.matches() + " was false or " + allowNull + " is true and " +(val == null || val.length() == 0));
					}
					break;
				default:
					logger.warn("Rule " + rule.get(FieldNames.FIELD_NAME) + " with type " + ruleType + " was not handled");
					break;
			}
		}
		else if(ruleType == ValidationEnumType.NONE){
			logger.info("Rule " + rule.get(FieldNames.FIELD_NAME) + " does not contain an expression and is set to validation type " + ruleType + ".  Marking validation as true.");
			outBool = true;
		}
		else{
			logger.info("Rule " + rule.get(FieldNames.FIELD_NAME) + " does not define a pattern.  Marking validation as true.");
			outBool = true;
		}
		logger.info("Rule " + rule.get(FieldNames.FIELD_NAME) + " returns " + (outBool && out_return_bool));
		return (outBool && out_return_bool);
	}
	
	/*
	@Test
	public void TestDataConstruct() {
		logger.info("Test Data Construct");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String[] sampleData = new String[] {"IMG_20220827_184359053.jpg", "IMG_20221230_142909291_HDR.jpg", "shutterstock-1940302522___08094459781.png", "The_George_Bennie_Railplane_System_of_Transport_poster,_1929.png"};
		
		try {
			BaseRecord data = getCreateData(testUser1, "~/Data/Pictures", "c:\\tmp\\xpic\\" + sampleData[0]);
			assertNotNull("Data is null", data);
			//logger.info(data.toFullString());
		}
		catch(ValueException | FieldException | ModelNotFoundException | FactoryException | IndexException | ReaderException | IOException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	*/
	private BaseRecord getCreateData(BaseRecord user, String groupPath, String filePath) throws FieldException, ValueException, ModelNotFoundException, FactoryException, IndexException, ReaderException, IOException {
		//BaseRecord data = null;
		String dataName = filePath.replaceAll("\\\\", "/");
		dataName = dataName.substring(dataName.lastIndexOf("/") + 1);
		// dataName = "Float " + UUID.randomUUID().toString() + "-" + dataName;
		BaseRecord data = null;
		BaseRecord dir = ioContext.getPathUtil().findPath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dir != null) {
			data = ioContext.getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_DATA, dir.get(FieldNames.FIELD_OBJECT_ID), dataName);
			if(data != null) {
				return data;
			}
		}
		
		byte[] fdata = FileUtil.getFile(filePath);

		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		plist.parameter("name", dataName);

		data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		data.set(FieldNames.FIELD_NAME, dataName);
		data.set(FieldNames.FIELD_CONTENT_TYPE, ContentTypeUtil.getTypeFromExtension(dataName));
		
		if(fdata.length > ByteModelUtil.MAXIMUM_BYTE_LENGTH) {
			/// NOTE: When creating a stream, and creating segments separately, and then attaching that stream to another object
			/// The size re-calculation done in the segment writer will affect the persistence layer, not the instance of the stream object
			/// If the size is needed in the current context, such as below where a data object is stored as a stream, and then a thumbnail created, then:
			///		a) set the size on the stream, if known
			///		b) or, if all segments are present, write the segments with the stream
			///		c) or, re-read the stream after writing the segment
			///	Both (a) and (c) are done below
			BaseRecord stream = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, user, null, plist);
			stream.set(FieldNames.FIELD_CONTENT_TYPE, data.get(FieldNames.FIELD_CONTENT_TYPE));
			stream.set(FieldNames.FIELD_SIZE, (long)fdata.length);
			stream.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			//List<BaseRecord> segs = stream.get(FieldNames.FIELD_SEGMENTS);
			logger.info("Invoke create on stream");
			stream = ioContext.getAccessPoint().create(user, stream);
			
			//logger.info(stream.toFullString());
			
			BaseRecord seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
			seg.set(FieldNames.FIELD_STREAM, fdata);
			seg.set(FieldNames.FIELD_STREAM_ID, stream.get(FieldNames.FIELD_OBJECT_ID));
			logger.info("Invoke create on segment");
			ioContext.getRecordUtil().createRecord(seg);
			
			stream = ioContext.getAccessPoint().findByObjectId(user, ModelNames.MODEL_STREAM, stream.get(FieldNames.FIELD_OBJECT_ID));
			data.set(FieldNames.FIELD_STREAM, stream);
			data.set(FieldNames.FIELD_SIZE, stream.get(FieldNames.FIELD_SIZE));
		}
		else {
			data.set(FieldNames.FIELD_BYTE_STORE, fdata);
		}
		logger.info("Invoke create on data");
		data = ioContext.getAccessPoint().create(user, data);
		
		if(ThumbnailUtil.canCreateThumbnail(data)) {
			ThumbnailUtil.getCreateThumbnail(data, 50, 50);
		}
		return data;
	}
	
}
