package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.security.VaultService;
import org.junit.Test;

public class TestVaultFields extends BaseTest {

	@Test
	public void TestVaultFields() {
		
		logger.info("Test Vault Fields");
		
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("vaultField", "vaultField");
		assertNotNull("Schema is null", ms);
		ModelNames.loadCustomModels();
		
		String grpName = "Dataset - " + UUID.randomUUID().toString();
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Vault Field");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, "group", "~/" + grpName, "DATA", testOrgContext.getOrganizationId());
		
		VaultBean vault = testOrgContext.getVault();
		assertNotNull("Vault is null", vault);
		
		VaultService.getInstance().newActiveKey(vault);

		CryptoBean fcipher = getFieldCipher(testOrgContext);
		assertNotNull("Keyset is null", fcipher);
		//VaultService.getInstance().setVaultBytes(vault, vault, null);
		
		
		
	}
	
	private CryptoBean getFieldCipher(OrganizationContext ctx) {
		CryptoBean fieldCipher = null;
		try {
			BaseRecord crypto = IOSystem.getActiveContext().getRecordUtil().getCreateRecord(ctx.getVaultUser(), ModelNames.MODEL_KEY_SET, "Organization Field Cipher", "~/keys", ctx.getOrganizationId());
			IOSystem.getActiveContext().getRecordUtil().populate(crypto, 2);
			fieldCipher = new CryptoBean(crypto);
	
			if(fieldCipher.getSecretKey() == null) {
				CryptoFactory.getInstance().generateSecretKey(fieldCipher);
				BaseRecord cipher = fieldCipher.get(FieldNames.FIELD_CIPHER); 
				ioContext.getRecordUtil().applyOwnership(ctx.getVaultUser(), cipher, ctx.getOrganizationId());
				IOSystem.getActiveContext().getRecordUtil().createRecord(cipher);
				IOSystem.getActiveContext().getRecordUtil().updateRecord(fieldCipher);
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return fieldCipher;
	}
	
}
