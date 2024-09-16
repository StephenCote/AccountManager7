package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.security.VaultService;
import org.junit.Test;

public class TestVault extends BaseTest {
	/*
	 * NOTE: When using a custom vault path, such as /.testvault/ and testing with schemaReset, it's the responsibility of the dev/test to clear out that directory
	 * Otherwise, a vault with a fixed name will fail to be created because the vault path already exists
	 */

	@Test
	public void TestVaultLifecycle() {
		logger.info("Test Vault Lifecycle");
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		// BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Data Dump", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());

		String vaultName = "Vault - " + UUID.randomUUID().toString();
		VaultBean vbean = VaultService.getInstance().getCreateVault(testUser1, vaultName, testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));

		
		String dataName = "Dump data " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/Data/Dump");
		plist.parameter(FieldNames.FIELD_NAME, dataName);
		BaseRecord data = null;
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			assertNotNull("Data is null", data);
			
			VaultService.getInstance().setVaultBytesLegacy(vbean, data, "The super secret sauce".getBytes());

			assertTrue("Failed to create record", ioContext.getRecordUtil().createRecord(data));
			
			String vaultId = data.get(FieldNames.FIELD_VAULT_ID);
			
			VaultBean ibean = VaultService.getInstance().getVaultByObjectId(testUser1, vaultId);
			assertNotNull("Vault bean is null");
			
			byte[] dec = VaultService.getInstance().extractVaultDataLegacy(ibean, data);
			
			logger.info("Dec bytes: " + new String(dec));
			
			VaultService.getInstance().deleteVault(ibean);
			

		} catch (IllegalArgumentException | NullPointerException | FactoryException | ValueException | ModelException | FieldException | IndexException | ReaderException  e) {
			logger.error(e);
			e.printStackTrace();
		}

		
		
	}

	@Test
	public void TestVaultedBytes() {
		logger.info("Test vaulted data");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Vaulted Data", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());

		VaultBean orgVault = orgContext.getVault();
		assertNotNull("Vault is null", orgVault);
		
		String dataName = "Demo data " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/Authorize/Create");
		plist.parameter(FieldNames.FIELD_NAME, dataName);
		BaseRecord data = null;
		
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			assertNotNull("Data is null", data);
			
			CryptoBean bean = VaultService.getInstance().getVaultKey(orgVault);
			assertNotNull("Private key is null", bean.getPrivateKey());
			
			CryptoBean akey = VaultService.getInstance().getActiveKey(orgVault);
			assertNotNull("Active key is null", akey);
			
			VaultService.getInstance().setVaultBytesLegacy(orgVault, data, "The super secret sauce".getBytes());
			VaultService.getInstance().setVaultBytesLegacy(orgVault, data, "And even more super secret sauce".getBytes());
			
			/// rotate the active key before extracting
			VaultService.getInstance().newActiveKey(orgVault);
			
			byte[] decBytes = VaultService.getInstance().extractVaultDataLegacy(orgVault, data);
			logger.info("Extracted: " + new String(decBytes));

		} catch (IllegalArgumentException | NullPointerException | FactoryException | ValueException | ModelException | FieldException    e) {
			logger.error(e);
			e.printStackTrace();
		}

		
	}
	
	
	
	@Test
	public void TestCryptoPersist() {
		logger.info("Test Crypto Bean Persistence");
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		assertNotNull("Test org is null", testOrgContext);
		assertTrue("Org isn't initialized", testOrgContext.isInitialized());
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Keys", GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Group is null", group);
		
		CryptoBean bean = new CryptoBean();
		String keyName = "Key Pair - " + UUID.randomUUID().toString();
		CryptoFactory.getInstance().generateSecretKey(bean);
		CryptoFactory.getInstance().generateKeyPair(bean);
		
		try {
			ioContext.getRecordUtil().applyNameGroupOwnership(testUser1, bean, keyName, "~/Keys", testOrgContext.getOrganizationId());
			ioContext.getRecordUtil().createRecord(bean);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.info(e);
		}
		
		
		CryptoBean ibean = new CryptoBean(ioContext.getRecordUtil().getRecordById(testUser1, ModelNames.MODEL_KEY_SET, bean.get(FieldNames.FIELD_ID)));
		assertNotNull("Crypto bean is null", ibean);
		BaseRecord erec = ibean.copyRecord();
		
		BaseRecord publicKey = null;
		try {
			erec.set(FieldNames.FIELD_PRIVATE, null);
			erec.set(FieldNames.FIELD_HASH, null);
			publicKey = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_KEY_SET, testUser1, erec, null);
			
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		assertNotNull("Public key is null", publicKey);
		String vaultName = "Vault - " + UUID.randomUUID().toString();
		VaultBean vbean = VaultService.getInstance().getCreateVault(testUser1, vaultName, testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("Vault is null", vbean);
		
		boolean key = VaultService.getInstance().newActiveKey(vbean);
		assertTrue("Key was not set", key);
		
		List<VaultBean> vaults = VaultService.getInstance().listVaultsByOwner(testUser1);
		logger.info("Vaults: " + vaults.size());
		assertTrue("Expected at least one vault", vaults.size() > 0);
		
		/// should be able to create a new key off the last entry
		key = VaultService.getInstance().newActiveKey(vaults.get(vaults.size() - 1));
		assertTrue("Key was not set", key);
	}


	@Test
	public void TestOrgVaultGetActiveKey() {
		logger.info("Test loading organization vault");
		VaultBean orgVault = orgContext.getVault();
		assertNotNull("Vault is null", orgVault);
		logger.info("Creating new active key");
		boolean newKey = VaultService.getInstance().newActiveKey(orgVault);
		assertTrue("Expected to be assigned a new active key", newKey);

	}

	@Test
	public void TestRandomVault() {
		
		logger.info("Testing random vault location");
		
		String vaultPath = "./am7/.vault/";
		BaseRecord testUser1 = this.getCreateUser("testUser1");
		VaultService vs = new VaultService();
		String vaultName = "testVault-" + System.currentTimeMillis();
		boolean created = false;
		try {
			created = vs.createProtectedCredentialFile(testUser1, vaultPath + vaultName, "password".getBytes());
		} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException e) {
			logger.error(e);
			
		}
		assertTrue("Vault was not created", created);
		
		BaseRecord cred = vs.loadProtectedCredential(vaultPath + vaultName);
		assertNotNull("Protected credential is null", cred);
		
		String vaultName2 = "Test Vault - " + UUID.randomUUID().toString();
		VaultBean newVault = vs.newVault(testUser1, vaultPath + "vault", vaultName2);
		vs.setProtectedCredentialPath(newVault, vaultPath + vaultName);
		assertNotNull("Vault is null", newVault);
		
		created = vs.createVault(newVault, cred);
		logger.info("Created: " + created);
		
		VaultBean chkVault = vs.loadVault(vaultPath + "vault", vaultName2, true);
		assertNotNull("Failed to load vault", chkVault);
		ioContext.getRecordUtil().populate(chkVault);

		BaseRecord icred = vs.loadProtectedCredential(vaultPath + vaultName);
		assertNotNull("Credential is null", icred);
		boolean init = vs.initialize(chkVault, icred);
		assertTrue("Failed to initialize vault", init);
		
		CryptoBean key = vs.getVaultKey(chkVault);
		assertNotNull("Key is null", key);
		
		boolean newKey = vs.newActiveKey(chkVault);
		assertTrue("Expected to be assigned a new active key", newKey);
		
		VaultBean rvault = vs.getVault(testUser1, vaultName2);
		assertNotNull("Vault is null", rvault);
		
		VaultBean nbean = vs.getVault(testUser1, "Test bad name");
		assertNull("Expected the vault to be null", nbean);
	}

	@Test
	public void TestFixedVaultSetup() {
		String vaultPath = "./am7/.vault/";
		String vaultName2 = "Test Vault 123";
		String vaultName = "testVault-123";
		BaseRecord testUser1 = this.getCreateUser("testUser1");
		boolean error = false;
		try {
			VaultService vs = new VaultService();
			VaultBean vault = vs.getVault(testUser1, vaultName2);
			if(vault == null) {
				boolean created = false;
				try {
					created = vs.createProtectedCredentialFile(testUser1, vaultPath + vaultName, "password".getBytes());
				} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException e) {
					logger.error(e);
					
				}
				assertTrue("Vault was not created", created);
				BaseRecord cred = vs.loadProtectedCredential(vaultPath + vaultName);
				assertNotNull("Protected credential is null", cred);
				VaultBean newVault = vs.newVault(testUser1, vaultPath + "vault", vaultName2);
				vs.setProtectedCredentialPath(newVault, vaultPath + vaultName);
				assertNotNull("Vault is null", newVault);
				created = vs.createVault(newVault, cred);
				vault = vs.getVault(testUser1, vaultName2);
			}
			assertNotNull("Vault is null", vault);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		assertFalse("Error encountered", error);
	}
}
