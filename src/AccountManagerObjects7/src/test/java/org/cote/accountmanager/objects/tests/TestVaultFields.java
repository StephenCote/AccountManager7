package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.objects.generated.RuleType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
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
		
		//String grpName = "Dataset - " + UUID.randomUUID().toString();
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Vault Field");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, "group", "~/AccessTest", "DATA", testOrgContext.getOrganizationId());
		
		VaultBean vault = testOrgContext.getVault();
		assertNotNull("Vault is null", vault);
		
		VaultService.getInstance().newActiveKey(vault);

		CryptoBean fcipher = getFieldCipher(testOrgContext);
		assertNotNull("Keyset is null", fcipher);
		//VaultService.getInstance().setVaultBytes(vault, vault, null);
		
		String dataName = "Vault Field Test " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/VaultFieldTests");
		plist.parameter("name", dataName);
		BaseRecord data = null;
		BaseRecord ndata = null;
		BaseRecord policy = null;
		BaseRecord journal = null;
		
		String textToEncrypt = "Encrypt this!";
		String bytesToEncrypt = "Now encrypt that!";
		
		try {
			data = ioContext.getFactory().newInstance("vaultField", testUser1, null, plist);
			data.set("vaultText", textToEncrypt);
			data.set("vaultData", bytesToEncrypt.getBytes());
			ndata = ioContext.getAccessPoint().create(testUser1, data);
			//ioContext.getReader().flush();
			//policy = ioContext.getPolicyUtil().getResourcePolicy("systemReadObject", testUser1, null, ndata);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException  e) {
			logger.error(e);
		}
		assertNotNull("Data is null", ndata);
		//assertNotNull("Policy is null", policy);
		
		/*
		PolicyType pol = policy.toConcrete();
		for(BaseRecord rule : pol.getRules()) {
			logger.info("Rule: " + rule.get(FieldNames.FIELD_NAME));
			RuleType irule = rule.toConcrete();
			for(BaseRecord pat : irule.getPatterns()) {
				logger.info("Pattern: " + pat.get(FieldNames.FIELD_NAME) + " " + pat.get(FieldNames.FIELD_URN));
			}
		}
		*/
		//journal = ndata.get(FieldNames.FIELD_JOURNAL);
		//logger.info("Can read journal: " + ioContext.getAuthorizationUtil().canRead(testUser1, testUser1, journal));
		//logger.info(journal.toFullString());
		/*
		PolicyResponseType[] prrs = ioContext.getPolicyUtil().evaluateQueryToReadPolicyResponses(testUser1, QueryUtil.createQuery("vaultField", FieldNames.FIELD_OBJECT_ID, ndata.get(FieldNames.FIELD_OBJECT_ID)));
		logger.info("PRRs: " + prrs.length);
		for(PolicyResponseType prr : prrs) {
			logger.info(prr.toFullString());
		}
		*/

		
		/// ioContext.getPolicyUtil().setTrace(true);
		BaseRecord rdata = ioContext.getAccessPoint().findByObjectId(testUser1, "vaultField", ndata.get(FieldNames.FIELD_OBJECT_ID));
		/// ioContext.getPolicyUtil().setTrace(false);
		assertNotNull("Data is null", rdata);
		//logger.info(rdata.toFullString());
		ioContext.getRecordUtil().populate(rdata);
		
		BaseRecord pdata = rdata.copyRecord(new String[] {"vaultText", "vaultData", FieldNames.FIELD_ID});
		assertTrue("Expected vaultText to match", textToEncrypt.equals(pdata.get("vaultText")));
		assertTrue("Expected vaultData to match", bytesToEncrypt.equals(new String((byte[])pdata.get("vaultData"))));
		
		textToEncrypt = "Even more text to encrypt!";
		try {
			pdata.set("vaultText", textToEncrypt);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		logger.info("**** MARK PATCH");

		BaseRecord udata = ioContext.getAccessPoint().update(testUser1, pdata);
		assertNotNull("Patched data was null", udata);
		
		Query q = QueryUtil.createQuery("vaultField", FieldNames.FIELD_OBJECT_ID, ndata.get(FieldNames.FIELD_OBJECT_ID));
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_ORGANIZATION_ID, "vaultText", "vaultData"});
		BaseRecord udata2 = ioContext.getAccessPoint().find(testUser1, q);
		//BaseRecord udata2 = ioContext.getAccessPoint().findByObjectId(testUser1, "vaultField", ndata.get(FieldNames.FIELD_OBJECT_ID));
		//logger.info(udata2.toFullString());
		assertTrue("Expected vaultText to match", textToEncrypt.equals(udata2.get("vaultText")));
		assertTrue("Expected vaultData to match", bytesToEncrypt.equals(new String((byte[])udata2.get("vaultData"))));
		
		/*
		ModelSchema lbm = RecordFactory.getSchema("vaultField");
		lbm.getFields().stream().sorted(Comparator.comparingInt(FieldSchema::getPriority)).collect(Collectors.toList()).forEach( f -> {
				if(f.getProvider() != null) {
					logger.info("Sorted: " + f.getName());
				}
		});
		*/
		/*
		.sort( (f1, f2) -> {
			Integer pri1 = f1.getPriority();
			Integer pri2 = f2.getPriority();
			return pri1.compareTo(pri2);
		}).forEach((f)-> {
			
		});
		*/

		//BaseRecord rdata2 = ioContext.getAccessPoint().findByObjectId(testUser1, "vaultField", ndata.get(FieldNames.FIELD_OBJECT_ID));
		//logger.info(ndata.toFullString());
		
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
