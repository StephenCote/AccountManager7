package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.StoreException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IPath;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.OperationType;
import org.cote.accountmanager.objects.generated.PatternType;
import org.cote.accountmanager.objects.generated.PolicyDefinitionType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.objects.generated.RuleType;
import org.cote.accountmanager.policy.CachePolicyUtil;
import org.cote.accountmanager.policy.PolicyDefinitionUtil;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.OperationEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.PatternEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.MemberUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Before;

public class BaseTest {
	public static final Logger logger = LogManager.getLogger(BaseTest.class);
	protected IOContext ioContext = null;
	protected OrganizationContext orgContext = null;
	protected String organizationPath = "/Development";
	@Before
	public void setup() {
		/// NOTE: The current setup will throw an error if trying to deserialize a model whose schema has not yet been loaded.  This was done intentionally to only support intentionally loaded schemas
		/// 
		resetIO("jdbc:h2:./am7/h2", "sa", "1234");
	}
	
   
	protected void resetIO(String dataUrl, String dataUser, String dataPassword) {
		IOProperties props = new IOProperties();
		props.setDataSourceUrl(dataUrl);
		props.setDataSourceUserName(dataUser);
		props.setDataSourcePassword(dataPassword);
		props.setSchemaCheck(false);
		//resetH2DBUtil("./am7/h2", "sa", "1234", false);
		resetIO(RecordIO.DATABASE, props);
	}
	protected void resetIO(RecordIO ioType, IOProperties properties) {
		logger.info("Reset IO");
		clearIO();
		try {
			ioContext = IOSystem.open(ioType, properties);
		} catch (SystemException e) {
			logger.error(e);
		}
		// ioContext = IOSystem.open(RecordIO.FILE, storeName, null);
		OrganizationContext octx = ioContext.getOrganizationContext(organizationPath, OrganizationEnumType.DEVELOPMENT);
		assertNotNull("Context was null", octx);
		if(!octx.isInitialized()) {
			createOrg("/System", OrganizationEnumType.SYSTEM);
			createOrg("/Development", OrganizationEnumType.DEVELOPMENT);
			createOrg("/Public", OrganizationEnumType.PUBLIC);
		}
		else {
			logger.info("Working with existing organization " + organizationPath);
		}
		assertTrue("Expected org to be initialized", octx.isInitialized());
		orgContext = octx;
	}
	protected BaseRecord getCreateCredential(BaseRecord user) {
		return getCreateCredential(user, "password");
	}
	
	protected BaseRecord getCreateCredential(BaseRecord user, String pwd) {
		BaseRecord cred = CredentialUtil.getLatestCredential(user);
		if(cred == null) {
			ParameterList plist = ParameterUtil.newParameterList("password", pwd);
			plist.parameter("type", CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
			try {
				cred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, user, null, plist);
				ioContext.getRecordUtil().createRecord(cred);
				cred = CredentialUtil.getLatestCredential(user);
			}
			catch(FactoryException e) {
				logger.error(e);
			}
		}
		return cred;
	}
	protected void createOrg(String path, OrganizationEnumType type) {
		logger.info("Creating organization " + path);
		OrganizationContext octx = ioContext.getOrganizationContext(path, type);
		try {
			octx.createOrganization();
			BaseRecord admin = octx.getAdminUser();
			ParameterList plist = ParameterUtil.newParameterList("password", "password");
			plist.parameter("type", CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
			BaseRecord cred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, admin, null, plist);
			ioContext.getRecordUtil().createRecord(cred);
			logger.info("Created credential");
			logger.info(cred.toFullString());

		} catch (NullPointerException | SystemException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	protected void clearIO() {
		IOSystem.close();
		ioContext = null;
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
		BaseRecord record = null;
		boolean error = false;
		try {
			record = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
			ioContext.getRecordUtil().applyNameGroupOwnership(user, record, name, path, organizationId);
			record.set(FieldNames.FIELD_CONTENT_TYPE, contentType);
			record.set(FieldNames.FIELD_BYTE_STORE, data);
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		assertNotNull("Record is null", record);
		assertFalse("Encountered an error", error);
		return record;
	}

	protected BaseRecord getInferredOwnerPolicyFunction() {
		PolicyType record = JSONUtil.importObject(ResourceUtil.getPolicyResource("ownerFunction"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete();
		FactType match = record.getRules().get(0).getPatterns().get(0).getMatch();
		//match.setSourceData(ownerPolicyFunction.getBytes());
		String policyFunction = ResourceUtil.getFunctionResource("ownerPolicy");
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
	
	
}


