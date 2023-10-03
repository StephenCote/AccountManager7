package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.junit.Test;

public class TestAudit extends BaseTest {
	

	/*
	@Test
	public void TestKeyStore() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		// ioContext.getRecordUtil().populate(testOrgContext.getKeyStoreBean(), 3);
		assertNotNull("Public key is null", testOrgContext.getKeyStoreBean().getCryptoBean().getPublicKey());
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser1);
		
		String name = testUser1.get(FieldNames.FIELD_NAME) + " Certificate Test " + UUID.randomUUID().toString();
		String storeName = name + " Certificate";
		String keyName = name + " Keys";
		try {

			KeyStoreBean ca = KeyStoreUtil.getCreateStore(testUser1, name, null);
			assertNotNull("CA is null", ca);

			BaseRecord crypto = IOSystem.getActiveContext().getRecordUtil().getCreateRecord(testUser1, ModelNames.MODEL_KEY_STORE, storeName, "~/keyStore", testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));
			assertNotNull("Keys were null", crypto);
			ioContext.getRecordUtil().populate(crypto, 3);
			KeyStoreBean ica = new KeyStoreBean(crypto);
			assertNotNull("Expected a public key", ica.getCryptoBean().getPublicKey());
			
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
		}

	}
	*/
	
	@Test
	public void TestAuditSignaturce() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser1);

		String dataName = "Example Data";
		BaseRecord data = getCreateData(testUser1, dataName, "~/QA Data", "This is the example data");

		BaseRecord audit = AuditUtil.startAudit(testUser1, ActionEnumType.READ, testUser1, data);
		PolicyResponseType prr = IOSystem.getActiveContext().getPolicyUtil().evaluateResourcePolicy(testUser1, PolicyUtil.POLICY_SYSTEM_UPDATE_OBJECT, testUser1, data);
		AuditUtil.closeAudit(audit, prr, null);
		//logger.info(audit.toString());

		logger.info("Can update: " + ioContext.getAuthorizationUtil().canUpdate(testUser1, testUser1, data));
		
		//logger.info(testOrgContext.getKeyStoreBean().toString());
		// ioContext.getRecordUtil().populate(testOrgContext.getKeyStoreBean().getCryptoBean());
		// logger.info(testOrgContext.getKeyStoreBean().getCryptoBean().toString());
		//CryptoBean crypto = testOrgContext.getKeyStoreBean().getCryptoBean();
	}
	

	@Test
	public void TestCreateAudit() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		assertNotNull("User is null", testUser1);
		
		String dataName = "Example Data";
		BaseRecord data = getCreateData(testUser1, dataName, "~/QA Data", "This is the example data");
		BaseRecord audit = null;
		
		try {
				audit = ioContext.getFactory().newInstance(ModelNames.MODEL_AUDIT, testUser1, null, ParameterUtil.newParameterList(FieldNames.FIELD_ACTION, ActionEnumType.ADD), testUser1, data);
				ioContext.getRecordUtil().createRecord(audit);
		} catch (FactoryException e) {
			logger.error(e);
			if(audit != null) {
				logger.error(audit.toString());
			}
		}
		assertNotNull("Audit is null", audit);
		logger.info(audit.toString());

		
	}

}
