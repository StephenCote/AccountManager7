package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.security.VaultService;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestVault extends BaseTest {
	
	
	/*
	@Test
	public void TestKeyExport() {
		CryptoBean crypto = new CryptoBean();
		CryptoFactory.getInstance().generateKeyPair(crypto);
		
		BaseRecord ekey = CryptoFactory.getInstance().export(crypto, false, true, false, false, true);
		
		logger.info(JSONUtil.exportObject(crypto, RecordSerializerConfig.getUnfilteredModule()));
		logger.info(JSONUtil.exportObject(ekey, RecordSerializerConfig.getUnfilteredModule()));
		
		CryptoBean icrypto = new CryptoBean();
		//CryptoFactory.getInstance().importCryptoBean(icrypto, ekey, true);
		CryptoFactory.getInstance().setMembers(icrypto, ekey, true);
		CryptoBean icrypto2 = new CryptoBean(ekey);
	}
	*/
	
	@Test
	public void TestCryptoPersist() {
		logger.info("Test Crypto Bean Persistence");
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		/*
		CryptoBean bean = new CryptoBean();
		CryptoFactory.getInstance().generateSecretKey(bean);
		CryptoFactory.getInstance().generateKeyPair(bean);
		try {
			ioContext.getRecordUtil().applyOwnership(testUser1, bean, testOrgContext.getOrganizationId());
			ioContext.getRecordUtil().createRecord(bean);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.info(e);
		}
		
		
		CryptoBean ibean = new CryptoBean(ioContext.getRecordUtil().getRecordById(testUser1, ModelNames.MODEL_KEY_SET, bean.get(FieldNames.FIELD_ID)));
		logger.info(bean.getPublicKey() != null);
		*/
		String vaultName = "Vault - " + UUID.randomUUID().toString();
		VaultBean vbean = VaultService.getInstance().getCreateVault(testUser1, vaultName, testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));
		
	}
	
	/*
	@Test
	public void TestOrgVault() {
		logger.info("Test loading organization vault");
		VaultBean orgVault = orgContext.getVault();
		assertNotNull("Vault is null", orgVault);
		//getPublicKey(orgVault);
		// logger.info(JSONUtil.exportObject(orgVault, RecordSerializerConfig.getUnfilteredModule()));
		logger.info("Creating new active key");
		boolean newKey = VaultService.getInstance().newActiveKey(orgVault);
		assertTrue("Expected to be assigned a new active key", newKey);
		
	}
	*/
	/*
	@Test
	public void TestRandomVault() {
		String vaultPath = "./am7/.testvault/";
		BaseRecord testUser1 = this.getCreateUser("testUser1");
		VaultService vs = new VaultService();
		String vaultName = "testVault-" + System.currentTimeMillis();
		boolean created = false;
		try {
			created = vs.createProtectedCredentialFile(testUser1, vaultPath + vaultName, "password".getBytes());
		} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException e) {
			logger.error(e);
			
		}
		assertNotNull("Vault was not created", created);
		
		BaseRecord cred = vs.loadProtectedCredential(vaultPath + vaultName);
		assertNotNull("Protected credential is null", cred);
		
		String vaultName2 = "Test Vault - " + UUID.randomUUID().toString();
		VaultBean newVault = vs.newVault(testUser1, vaultPath + "vault", vaultName2);
		vs.setProtectedCredentialPath(newVault, vaultPath + vaultName);
		assertNotNull("Vault is null", newVault);
		// logger.info(JSONUtil.exportObject(cred, RecordSerializerConfig.getUnfilteredModule()));
		// logger.info(JSONUtil.exportObject(newVault, RecordSerializerConfig.getUnfilteredModule()));
		
		logger.info("*** Create Vault");
		created = vs.createVault(newVault, cred);
		logger.info("Created: " + created);
		
		logger.info("*** Load Vault");
		VaultBean chkVault = vs.loadVault(vaultPath + "vault", vaultName2, true);
		assertNotNull("Failed to load vault", chkVault);
		logger.info("*** Load Credential");
		BaseRecord icred = vs.loadProtectedCredential(vaultPath + vaultName);
		assertNotNull("Credential is null", icred);
		logger.info("*** Initialize Vault");
		boolean init = vs.initialize(chkVault, icred);
		assertTrue("Failed to initialize vault", init);
		
		logger.info("*** Get Vault Key");
		CryptoBean key = vs.getVaultKey(chkVault);
		assertNotNull("Key is null", key);
		
		logger.info("*** New Cipher Key");
		boolean newKey = vs.newActiveKey(chkVault);
		assertTrue("Expected to be assigned a new active key", newKey);
		
		logger.info("**** Read vault by vault name");
		VaultBean rvault = null;
		try {
			rvault = vs.getVault(testUser1, vaultName2);
		} catch (IndexException | ReaderException e) {
			logger.error(e);
			
		}
		assertNotNull("Vault is null", rvault);
		
		
		VaultBean nbean = null;
		try {
			nbean = vs.getVault(testUser1, "Test it");
		} catch (IndexException | ReaderException e) {
			logger.error(e);
			
		}
		assertNull("Expected the vault to be null", nbean);
	}
	*/
	/*
	@Test
	public void TestFixedVaultSetup() {
		String vaultPath = "./am7/.testvault/";
		String vaultName2 = "Test Vault 123";
		String vaultName = "testVault-123";
		BaseRecord testUser1 = this.getCreateUser("testUser1");
		VaultService vs = new VaultService();
		VaultBean vault = null;
		try {
			vault = vs.getVault(testUser1, vaultName2);
		} catch (IndexException | ReaderException e1) {
			logger.error(e1);
			e1.printStackTrace();
		}
		if(vault == null) {
			boolean created = false;
			try {
				created = vs.createProtectedCredentialFile(testUser1, vaultPath + vaultName, "password".getBytes());
			} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException e) {
				logger.error(e);
				
			}
			assertNotNull("Vault was not created", created);
			
			BaseRecord cred = vs.loadProtectedCredential(vaultPath + vaultName);
			assertNotNull("Protected credential is null", cred);
			
	
			VaultBean newVault = vs.newVault(testUser1, vaultPath + "vault", vaultName2);
			vs.setProtectedCredentialPath(newVault, vaultPath + vaultName);
			assertNotNull("Vault is null", newVault);
			// logger.info(JSONUtil.exportObject(cred, RecordSerializerConfig.getUnfilteredModule()));
			// logger.info(JSONUtil.exportObject(newVault, RecordSerializerConfig.getUnfilteredModule()));
			
			logger.info("*** Create Vault");
			created = vs.createVault(newVault, cred);
			logger.info("Created: " + created);
			
			try {
				vault = vs.getVault(testUser1, vaultName2);
			} catch (IndexException | ReaderException e) {
				logger.error(e);
				
			}
		}
		assertNotNull("Vault is null", vault);

	}
	*/
}
