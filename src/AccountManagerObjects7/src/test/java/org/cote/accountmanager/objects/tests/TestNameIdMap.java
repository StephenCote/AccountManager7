package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.NameIdExporter;
import org.junit.Test;

public class TestNameIdMap {
	public static final Logger logger = LogManager.getLogger(TestNameIdMap.class);
	public static List<Field> getAllFields(List<Field> fields, Class<?> cls) {
	    fields.addAll(Arrays.asList(cls.getDeclaredFields()));

	    if (cls.getSuperclass() != null) {
	        getAllFields(fields, cls.getSuperclass());
	    }

	    return fields;
	}
	
	private Class<?> getClass(String name){
		Class<?> enumClass = null;
		try {
			enumClass = Class.forName(name);
		} catch (ClassNotFoundException e) {
			logger.error(e);
		}
		return enumClass;
	}
	
	@Test
	public void TestLooseModelMap() {
		logger.info("Test LooseModel map");

		ModelSchema lm = RecordFactory.getSchema("genericObject");
		Optional<FieldSchema> olft = lm.getFields().stream().filter(o -> o.getName().equals(FieldNames.FIELD_TYPE)).findFirst();
		assertTrue("Failed to find matching object", olft.isPresent());
		FieldSchema lft = olft.get();
		String clsName = lft.getBaseClass();
		logger.info("Evaluate " + clsName);
		Class<?> enumClass = getClass(clsName);
		assertNotNull("Enum Class is null", enumClass);
		Object enumValue = Enum.valueOf((Class)enumClass, "SYSTEM");
		assertNotNull("Enum value is null", enumValue);
		//logger.info(enumValue);
		//logger.info(JSONUtil.exportObject(lm));
		
	}
	
	@Test
	public void TestComplexObject() {
		logger.info("Test Complex Object");
		BaseRecord com = RecordFactory.model("complexObject");
		assertNotNull("Base model is null", com);
		BaseRecord genObj = null;
		BaseRecord linkObj = null;
		
		try {
			genObj = com.newInstance(new String[] {FieldNames.FIELD_NAME, "link", FieldNames.FIELD_TYPE});
			assertNotNull("Expected complex instance to exist", genObj);
			linkObj = com.newInstance(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_TYPE});
			genObj.set(FieldNames.FIELD_NAME, "Parent Object");
			linkObj.set(FieldNames.FIELD_NAME, "Child Object");
			linkObj.set(FieldNames.FIELD_TYPE, "SYSTEM");
			genObj.set("link", linkObj);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		String ser = JSONUtil.exportObject(genObj, RecordSerializerConfig.getUnfilteredModule());
		//logger.info(ser);
		BaseRecord impObj = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Import was null", impObj);
		String ser2 = JSONUtil.exportObject(impObj, RecordSerializerConfig.getUnfilteredModule());
		//logger.info(ser2);
		
		assertTrue("Expected serials to match", ser2.equals(ser));
		BaseRecord impLink = impObj.get("link");
		assertNotNull("Expected a link object", impLink);
		assertTrue("Expected child link types to match", "SYSTEM".equals(impLink.get(FieldNames.FIELD_TYPE)));
		logger.info(ser2);
	}

	@Test
	public void TestComplexList() {
		logger.info("Test complex list");
		BaseRecord obj = null;
		BaseRecord cobj1 = null;
		BaseRecord cobj2 = null;
		try {
			obj = RecordFactory.model("complexObject").newInstance(new String[] {"fancyStuff"});
			cobj1 = RecordFactory.model("genericObject").newInstance(new String[] {FieldNames.FIELD_NAME});
			cobj2 = RecordFactory.model("genericObject").newInstance(new String[] {FieldNames.FIELD_NAME});

			cobj1.set(FieldNames.FIELD_NAME, "Example 1");
			cobj2.set(FieldNames.FIELD_NAME, "Example 2");
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		List<BaseRecord> list = obj.get("fancyStuff");
		list.add(cobj1);
		list.add(cobj2);
		String ser = JSONUtil.exportObject(obj, RecordSerializerConfig.getUnfilteredModule());
		logger.info(ser);
		
		BaseRecord impObj = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Object is null", impObj);
		List<BaseRecord> list2 = impObj.get("fancyStuff");
		BaseRecord icobj1 = (BaseRecord)list2.get(0);
		//logger.info((String)icobj1.get(FieldNames.FIELD_NAME));
		String ser2 = JSONUtil.exportObject(impObj, RecordSerializerConfig.getUnfilteredModule());
		
		logger.info(ser2);
	}
	
	@Test
	public void TestSimpleList() {
		logger.info("Test simple list");
		BaseRecord obj = null;
		try{
			obj = RecordFactory.model("complexObject").newInstance(new String[] {"stuff"});
		}
		catch(FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		assertNotNull("Object was null", obj);
		List<String> list = obj.get("stuff");
		assertNotNull("List was null", list);
		list.add("Example 1");
		list.add("Example 2");
		list.add("Example 3");
		String ser = JSONUtil.exportObject(obj, RecordSerializerConfig.getUnfilteredModule());
		BaseRecord impObj = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		List<String> list2 = impObj.get("stuff");
		logger.info(list2.get(0));
		String ser2 = JSONUtil.exportObject(impObj, RecordSerializerConfig.getUnfilteredModule());
		//logger.info(ser2);
		assertTrue("Serials don't match", ser2.equals(ser));
		
	}
	/*
	@Test
	public void TestClassMap() {
		logger.info("Test map class to LooseBaseModel");
		BaseRecord genObj = null;
		
		try {
			genObj = RecordFactory.model("genericObject").newInstance();
			genObj.set(FieldNames.FIELD_TYPE, "SYSTEM");
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		List<Field> fields = getAllFields(new ArrayList<>(), UserType.class);

		Map<String, String> exp = NameIdExporter.instance().export(UserType.class);
		RecordFactory.addRawModels(exp);
		BaseRecord userType = RecordFactory.model("system.user");
		assertNotNull("User type was null", userType);
		BaseRecord userInst = null;
		try{
			userInst = userType.newInstance();
		}
		catch(FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		assertNotNull("User instance was null", userInst);

		
	}
	*/
	/*
	private StringBuilder emitModel(String name, List<Field> fields) {
		//Map<String, List<Field>> inheritMap = new HashMap<>();
		return emitModel(name, fields, 0);
	}
	*/

}
