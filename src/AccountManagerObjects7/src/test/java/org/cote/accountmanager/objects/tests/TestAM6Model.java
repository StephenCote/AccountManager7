package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.MemoryWriter;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ScriptUtil;
import org.junit.Test;

public class TestAM6Model extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestAM6Model.class);
	private String storageBase = "./am6";
	
	@Test
	public void TestOwnerPolicy() {
		logger.info("Test owner policy");
		//BaseModel model = getOwnerPolicy();
	}
	
	
	@Test
	public void TestOrganizationCRUD() {
		logger.info("Test Org CRUD");
		OrganizationContext org = this.getTestOrganization("/Public");
		assertNotNull("Org model is null", org);
	}

	@Test
	public void TestRecordPath() {
		OrganizationContext org = this.getTestOrganization("/Path/Test");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = null;
		try 
		{
			testUser1 = mf.getCreateUser(org.getAdminUser(), "testUser1", org.getOrganizationId());
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		
		logger.info("Test Record Path");
		BaseRecord rec = null;
		try {
			rec = ioContext.getPathUtil().makePath(testUser1, "group",  "~/Demo Path", "DATA", org.getOrganizationId());
		} catch (ClassCastException | NullPointerException e) {
			logger.error(e);
			
		}
		assertNotNull("Record is null", rec);
	}
	
	@Test
	public void TestByteAccess() {
		logger.info("Test Byte Access");
		BaseRecord model = null;
		try {
			model = RecordFactory.model("data").newInstance(new String[] {"name", "dataBytesStore"});
			assertNotNull("Model was null", model);
			
			model.set("name", "Demo Data");
			model.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			String demoData = "This is the demo data that we're going to be working with for now";
			model.set("dataBytesStore", demoData.getBytes());

			byte[] data = model.get("dataBytesStore");
			assertTrue("Expected a value", data != null && data.length > 0);
			byte[] data2 = model.get("dataBytesStore");
			assertTrue("Expected a value", data2 != null && data2.length > 0);
			
			//logger.info(JSONUtil.exportObject(model, RecordSerializerConfig.getUnfilteredModule()));
			
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		assertNotNull("Model was null", model);
	}
	
	@Test
	public void TestDataConstruct() {
		logger.info("Test Data Construct");
		BaseRecord model = null;
		BaseRecord proto = null;
		try {
			proto = RecordFactory.model("data");
			assertNotNull("Protoype was null", proto);
			model = RecordFactory.model("data").newInstance(new String[] {"name", "dataBytesStore"});
			assertNotNull("Model was null", model);
			
			model.set("name", "Demo Data");
			String demoData = "This is the demo data that we're going to be working with for now";
			model.set("dataBytesStore", demoData.getBytes());
			
			//logger.info(JSONUtil.exportObject(model));

			
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		assertNotNull("Model was null", model);
	}
	
	@Test
	public void TestScript1() {
		logger.info("Script Test");
		BaseRecord model = null;
		MemoryWriter writer = new MemoryWriter();
		try {
			model = RecordFactory.model("data").newInstance(new String[] {"name", "dataBytesStore"});
			model.set("name", "Demo Data");
			model.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			String demoData = "This is the demo data that we're going to be working with for now";
			model.set("dataBytesStore", demoData.getBytes());
			writer.write(model);
		} catch (FieldException | ModelNotFoundException | ValueException | WriterException e) {
			logger.error(e);
			
		}
		
		String script = """
				console.log("Test");
				console.log("Test Obj:", test);
		""";
		try {
			Map<String, Object> params = new HashMap<>();
			params.put("test", model);
			String header = ScriptUtil.mapAndConvertParameters(new BaseRecord[] {model});
			logger.info(header);
			ScriptUtil.run(header + script, params);
		}
		catch(ScriptException e) {
			logger.error(e);
			
		}
	}
	
	/*
	LooseModel mod = ModelFactory.getLooseModel("data");
	List<String> inh = mod.getImplements();
	inh.forEach(k -> {
		logger.info("Implements: " + k);
	});
	
	LooseModel bmod = ModelFactory.getLooseModel("cryptoByteStore");
	bmod.getFields().forEach(s -> {
		logger.info("Field " + s.getName());
	});
	*/
}
