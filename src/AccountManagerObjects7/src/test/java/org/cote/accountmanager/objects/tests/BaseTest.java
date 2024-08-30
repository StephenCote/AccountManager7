package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.After;
import org.junit.Before;

public class BaseTest {
	public static final Logger logger = LogManager.getLogger(BaseTest.class);
	protected IOContext ioContext = null;
	protected OrganizationContext orgContext = null;
	protected String organizationPath = "/Development";
	protected DBUtil dbUtil = null;
	
	/// Configured via property definition
	protected static boolean resetDataSchema = false;
	private static boolean checkReset = false;
	protected static Properties testProperties = null;
	protected String testDataPath = null;
	
	@Before
	public void setup() {
		
		if(testProperties == null){
			testProperties = new Properties();
			try {
				InputStream fis = ClassLoader.getSystemResourceAsStream("./resource.properties"); 
				testProperties.load(fis);
				fis.close();
			} catch (IOException e) {
				logger.error(e);
				return;
			}
		}
		if(!checkReset) {
			checkReset = true;
			resetDataSchema = Boolean.parseBoolean(testProperties.getProperty("test.db.reset"));
		}
		testDataPath = testProperties.getProperty("test.data.path");
		
		/// NOTE: The current setup will throw an error if trying to deserialize a model whose schema has not yet been loaded.  This was done intentionally to only support intentionally loaded schemas
		
		IOFactory.addPermittedPath("c:\\tmp\\xpic");

		/// USE FILE
		// resetIO(null);
		
		/// USE FILE ARCHIVE (7z)
		/// There are some latent bugs in using the File Archive format, plus it's incredibly slow
		/// resetIO("./test.7z");

		/// USE POSTGRESQL or H2
		resetIO(testProperties.getProperty("test.db.url"), testProperties.getProperty("test.db.user"), testProperties.getProperty("test.db.password"));

	}
	
	@After
	public void tearDown() throws Exception{
		logger.info("Shutting down");
		IOSystem.close();
		ioContext = null;
		orgContext = null;
	}
	
	//protected void resetIO(FileStore store, boolean follow) {
	protected void resetIO(String storeName) {
		IOProperties props = new IOProperties();
		props.setDataSourceName(storeName);

		resetIO(RecordIO.FILE, props);
	}
	protected void resetIO(String dataUrl, String dataUser, String dataPassword) {
		IOProperties props = new IOProperties();
		props.setDataSourceUrl(dataUrl);
		props.setDataSourceUserName(dataUser);
		props.setDataSourcePassword(dataPassword);
		
		
		// Force schema rebuild
		//   - This will destroy all tables and sequences, remove all stream data, and delete all keystores
		props.setReset(resetDataSchema);
		resetDataSchema = false;
		props.setSchemaCheck(false);
		resetIO(RecordIO.DATABASE, props);
	}
	protected void resetIO(RecordIO ioType, IOProperties properties) {
		logger.info("Reset IO");
		clearIO();
		
		boolean error = false;
		OrganizationContext octx = null;
		try {
			ioContext = IOSystem.open(ioType, properties);
			octx = ioContext.getOrganizationContext(organizationPath, OrganizationEnumType.DEVELOPMENT);
			assertNotNull("Context was null", octx);
			if(!octx.isInitialized()) {
				logger.info("Creating organization " + organizationPath);
				octx.createOrganization();
			}
			else {
				logger.debug("Working with existing organization " + organizationPath);
			}
		} catch (StackOverflowError | Exception e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		
		assertFalse("Error encountered", error);

		assertTrue("Expected org to be initialized", octx.isInitialized());
		orgContext = octx;
	}
	
	protected void clearIO() {
		IOSystem.close();
		ioContext = null;
		orgContext = null;
	}
	
	
	protected BaseRecord getCreateData(BaseRecord user, String name, String contentType, byte[] data, String path, long organizationId) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path, "DATA", organizationId);
		BaseRecord dat = ioContext.getRecordUtil().getRecord(user, ModelNames.MODEL_DATA, name, 0L, (long)dir.get(FieldNames.FIELD_ID), organizationId);
		if(dat == null) {
			dat = newData(user, name, contentType, data, path, organizationId);
			ioContext.getRecordUtil().createRecord(dat);
		}
		return dat;
	}
	protected BaseRecord newData(BaseRecord user, String name, String contentType, byte[] data, String path, long organizationId) {
		BaseRecord rec = null;
		boolean error = false;
		try {
			rec = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
			ioContext.getRecordUtil().applyNameGroupOwnership(user, rec, name, path, organizationId);
			rec.set(FieldNames.FIELD_CONTENT_TYPE, contentType);
			rec.set(FieldNames.FIELD_BYTE_STORE, data);
		} catch (Exception e) {
			logger.error(e);
			
			error = true;
		}
		assertNotNull("Record is null", rec);
		assertFalse("Encountered an error", error);
		return rec;
	}

	protected BaseRecord getInferredOwnerPolicyFunction() {
		PolicyType record = JSONUtil.importObject(ResourceUtil.getInstance().getPolicyResource("ownerFunction"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete();
		FactType match = record.getRules().get(0).getPatterns().get(0).getMatch();
		//match.setSourceData(ownerPolicyFunction.getBytes());
		String policyFunction = ResourceUtil.getInstance().getFunctionResource("ownerPolicy");
		if(policyFunction == null) {
			logger.error("Failed to load ownerPolicyFunction.js");
		}
		match.setSourceData(policyFunction.getBytes());
		return record;
	}
	

	protected OrganizationContext getTestOrganization(String path) {
		OrganizationContext orgContext = ioContext.getOrganizationContext(path, OrganizationEnumType.DEVELOPMENT);
		if(!orgContext.isInitialized()) {
			try {
				orgContext.createOrganization();
			} catch (SystemException e) {
				logger.error(e);
			}
		}
		return orgContext;
	}
	protected BaseRecord getCreateUser(String name) {
		return getCreateUser(name, orgContext);
	}
	protected BaseRecord getCreateUser(String name, OrganizationContext org) {
		Factory mf = ioContext.getFactory();
		return mf.getCreateUser(org.getAdminUser(), name, org.getOrganizationId());
	}
	
	protected BaseRecord getCreateData(BaseRecord owner, String name, String path, String textContents) {
		BaseRecord dat = null;
		BaseRecord datT = null;
		// ioContext.getRecordUtil().populate(owner);
		BaseRecord group = ioContext.getPathUtil().findPath(owner, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), owner.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(group != null) {
			dat = ioContext.getRecordUtil().getRecord(owner, ModelNames.MODEL_DATA, name, 0, group.get(FieldNames.FIELD_ID), owner.get(FieldNames.FIELD_ORGANIZATION_ID));

		}
		if(dat == null) {
			try {
				datT = RecordFactory.newInstance(ModelNames.MODEL_DATA);
				datT.set(FieldNames.FIELD_NAME, name);
				datT.set(FieldNames.FIELD_GROUP_PATH, path);
				datT.set(FieldNames.FIELD_BYTE_STORE, textContents.getBytes());
				datT.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
				dat = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, owner, datT, null);
				ioContext.getRecordUtil().createRecord(dat);
	
			} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
				logger.error(e);
			}
		}
		return dat;
	}
	
}


