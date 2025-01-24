package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.security.VaultService;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestCipherData extends BaseTest {

	@Test
	public void TestSetKeys() {
		
		/// NOTE: The preferred method of encrypting fields is to set the field.encrypt attribute to true and specify the EncryptionFieldProvider in the field.provider attribute.
		///
		logger.warn("Test byte store encryption.");
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Cryptography");
		Factory mf = ioContext.getFactory();
		
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord dat = null;
		try {
			dat = RecordFactory.newInstance(ModelNames.MODEL_DATA);
			dat.set(FieldNames.FIELD_ENCIPHERED, true);
		}
		catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		assertNotNull("Data is null", dat);

		String testStr = "This is the test string";
		boolean error = false;
		try {
			ByteModelUtil.setValue(dat, testStr.getBytes());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			error = true;
		}
		assertFalse("Error setting value", error);
		
		String ser = dat.toFullString();
		
		BaseRecord dat2 = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		
		logger.info(ser);
		
		logger.info(dat2.toFullString());
		
		String sval = null;
		try {
			sval = new String(ByteModelUtil.getValue(dat2));
		} catch (ValueException | FieldException e) {
			logger.error(sval);
		}
		assertNotNull("Value is null", sval);
		logger.info(sval);
		
	}
}
