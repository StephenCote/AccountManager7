package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.value.ValueType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.Catalog;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestLooseField {
	public static final Logger logger = LogManager.getLogger(TestLooseField.class);
	
	
	@Test
	public void TestLooseImport() {
		logger.info("Test Loose Imports.");
		logger.warn("Clearing catalog");
		RecordFactory.clearCache();
		logger.info("Attempt to create invalid/unloaded model");

		BaseRecord invmod = null;
		try{
			invmod = RecordFactory.newInstance(ModelNames.MODEL_ORGANIZATION);
		}
		catch(FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		assertNull("Expected model to be null", invmod);
		
		BaseRecord brokenmod = RecordFactory.importSchema("brokenType");
		assertNull("Broken model should be null", brokenmod);
		
		BaseRecord brokenimp = RecordFactory.importSchema("brokenImport");
		if(brokenimp != null) {
			logger.error(brokenimp.toFullString());
		}
		assertNull("Broken import should be null", brokenimp);
		
		BaseRecord generic = RecordFactory.importSchema("genericObject");
		assertNotNull("Model should not be null", generic);
		
		logger.info("Create new instance");
		BaseRecord genericInst = null;
		try{
			genericInst = RecordFactory.newInstance("genericObject");
		}
		catch(FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		assertNotNull("Model instance should not be null", genericInst);
		ValueType dvt = genericInst.getField("createdDate").getFieldValueType();
		// logger.info("Date check: " + dvt.getValue());
		try {
			genericInst.set("name", "Generic Object");
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			// TODO Auto-generated catch block
			
		}
		//genericInst.set("id", 123L);
		//genericInst.set("createdDate", new Date());
		String ser = JSONUtil.exportObject(genericInst, RecordSerializerConfig.getUnfilteredModule());
		assertNotNull("Serialization was null", ser);
		//logger.info(ser);
		
		//SimpleModule module = new SimpleModule().addDeserializer(BaseModel.class, new SchemaDeserializer());
		BaseRecord imp = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Imported object was null", imp);
		//logger.info(JSONUtil.exportObject(imp));
		 
		 
		
	}
	
	@Test
	public void TestErrorHandling() {
		logger.info("Test error handling");

		BaseRecord genObj = null;
		
		String impMod = """
			{
				"model": "genericObject",
				"fields": [
					{"name" : "id", "value": 123 },
					{"name" : "name", "value": "Generic Object"}
				]
			}
		""";
		try {
			genObj = RecordFactory.model("genericObject").newInstance();
			genObj.set("name", "Generic object");
			genObj.set("id", 123L);
			genObj.set("populated", false);
			genObj.set("dataStore", "This is some data".getBytes());
			String ser = JSONUtil.exportObject(genObj, RecordSerializerConfig.getUnfilteredModule());
			
			BaseRecord des = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
			logger.info(JSONUtil.exportObject(des, RecordSerializerConfig.getUnfilteredModule()));
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		
		
		
	}

}
