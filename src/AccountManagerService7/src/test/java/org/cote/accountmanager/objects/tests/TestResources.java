package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.StreamUtil;
import org.junit.Test;

public class TestResources extends BaseTest {
	/*
	@Test
	public void TestLiveData() {
		String oldBase = IOFactory.DEFAULT_FILE_BASE;
		IOFactory.DEFAULT_FILE_BASE = "c:/projects/data/am7";
		resetIO(null);
		
		VerificationEnumType vet = VerificationEnumType.UNKNOWN;
		String pwd = BinaryUtil.fromBase64Str("cGFzc3dvcmQ=");
		BaseRecord user = orgContext.getAdminUser();
		assertNotNull("User is null", user);
		
		BaseRecord cred = getLatestCredential(user);
		assertNotNull("Cred is null", cred);
		try {
			vet = ioContext.getFactory().verify(user, cred, ParameterUtil.newParameterList("password", pwd));
		} catch (FactoryException e) {
			logger.error(e);
		}
		logger.info("VET: " + vet);
		IOFactory.DEFAULT_FILE_BASE = oldBase;
	
	}
	*/
	
	@Test
	public void TestAdminAuth() {
		
		String pwd = BinaryUtil.fromBase64Str("cGFzc3dvcmQ=");
		logger.info("Password: \"" + pwd + "\"");
		
		BaseRecord user = orgContext.getAdminUser();
		assertNotNull("User is null", user);
		
		BaseRecord cred = CredentialUtil.getLatestCredential(user);
		assertNotNull("Cred is null", cred);
		
		VerificationEnumType vet = VerificationEnumType.UNKNOWN;
		try {
			vet = ioContext.getFactory().verify(user, cred, ParameterUtil.newParameterList("password", "password"));
		} catch (FactoryException e) {
			logger.error(e);
		}
		logger.info("VET: " + vet);
	}
	
	@Test
	public void TestResources() {
		logger.info("Test resources");
		String contents = null;
		BufferedInputStream is = new BufferedInputStream(ClassLoader.getSystemResourceAsStream("models/userModel.json"));
		try {
			contents = StreamUtil.streamToString(is);
		} catch (IOException e) {
			logger.error(e);
			e.printStackTrace();
		}
		//logger.info("Contents: " + contents);
		assertNotNull("Contents are null", contents);
		ModelSchema schema = RecordFactory.getSchema("user");
		assertNotNull("Schema is null", schema);
		
	}
	

	
	@Test
	public void TestModels() {
		BaseRecord rec1 = null;
		BaseRecord rec2 = null;
		try {
			rec1 = ioContext.getFactory().newInstance(ModelNames.MODEL_AUTHENTICATION_REQUEST);
			rec2 = ioContext.getFactory().newInstance(ModelNames.MODEL_AUTHENTICATION_RESPONSE);
		} catch (FactoryException e) {
			logger.error(e);
		}
		assertNotNull("Null rec", rec1);
		assertNotNull("Null rec", rec2);
	}
}
