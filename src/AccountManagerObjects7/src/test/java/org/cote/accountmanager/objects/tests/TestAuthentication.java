package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.bouncycastle.util.Arrays;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.junit.Test;

public class TestAuthentication extends BaseTest {

	
	@Test
	public void TestCreatePolicy() {
		BaseRecord rec = null;
		ParameterList plist = ParameterUtil.newParameterList("name", "Demo data - " + UUID.randomUUID().toString());
		plist.parameter("path", "~/QA Demo");
		boolean canCreate = false;
		try {
			rec = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, orgContext.getAdminUser(), null, plist);
			logger.info(JSONUtil.exportObject(rec, RecordSerializerConfig.getUnfilteredModule()));
			canCreate = ioContext.getPolicyUtil().createPermitted(orgContext.getAdminUser(), orgContext.getAdminUser(), null, rec);
		} catch (NullPointerException | FactoryException e) {
			logger.error(e);
			
		}
		assertTrue("Expected to be able to create the object", canCreate);
	}
	
	@Test
	public void TestSetCredential() {
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = this.getCreateUser("testUser1");
		
		byte[] useCredBytes = "password".getBytes();
		BaseRecord cred = null;
		
		
		byte[] salt1 = CryptoUtil.getRandomSalt();
		byte[] salt2 = CryptoUtil.getRandomSalt();
		byte[] hash1 = CryptoUtil.getDigest(useCredBytes, salt1);
		byte[] hash2 = CryptoUtil.getDigest(useCredBytes, salt2);
		
		logger.info(BinaryUtil.toBase64Str(hash1));
		logger.info(BinaryUtil.toBase64Str(hash2));
		
		assertTrue("Expected salts to be different", !Arrays.areEqual(salt1,  salt2));
		assertTrue("Expected hashes to be different", !Arrays.areEqual(hash1,  hash2));
		
		try {
			ParameterList plist = ParameterUtil.newParameterList("type", CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
			plist.parameter("password", "password");
			cred = mf.newInstance(ModelNames.MODEL_CREDENTIAL, testUser1, null, plist);
			assertNotNull("Credential is null", cred);
			/*
			cred.set(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD);
			cred.set(FieldNames.FIELD_HASH, mf.newInstance(ModelNames.MODEL_HASH));
			CryptoFactory.getInstance().setSalt(cred);
			cred.set(FieldNames.FIELD_CREDENTIAL, CryptoUtil.getDigest(useCredBytes, cred.get(FieldNames.FIELD_HASH_FIELD_SALT)));
			*/
			
		} catch (FactoryException e) {
			logger.error(e);
		}

		boolean created = ioContext.getRecordUtil().createRecord(cred);
		assertTrue("Credential failed to update", created);
		
		// logger.info(JSONUtil.exportObject(cred, RecordSerializerConfig.getUnfilteredModule()));

		Query query = QueryUtil.createQuery(ModelNames.MODEL_CREDENTIAL, FieldNames.FIELD_REFERENCE_TYPE, testUser1.getModel());
		QueryResult res = null;
		try {
			query.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_CREATED_DATE);
			query.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING);
			query.field(FieldNames.FIELD_REFERENCE_ID, testUser1.get(FieldNames.FIELD_ID));
			query.set(FieldNames.FIELD_RECORD_COUNT, 1);
			res = ioContext.getSearch().find(query);
		} catch (IndexException | ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		assertNotNull("Result is null", res);
		assertTrue("Expected only one result", res.getResults().length == 1);

		byte[] salt =  cred.get(FieldNames.FIELD_HASH_FIELD_SALT);
		assertTrue("Expected a salt", salt.length > 0);
		byte[] credHash = cred.get(FieldNames.FIELD_CREDENTIAL);
		byte[] checkHash = CryptoUtil.getDigest(useCredBytes, cred.get(FieldNames.FIELD_HASH_FIELD_SALT));
		byte[] checkHash2 = CryptoUtil.getDigest(useCredBytes, new byte[0]);
		assertTrue("Expected hashes to be different", !Arrays.areEqual(credHash,  checkHash2));

		assertTrue("Expected hashes to match", Arrays.areEqual(credHash,  checkHash));
		
		VerificationEnumType vet = VerificationEnumType.UNKNOWN;
		try {
			vet = mf.verify(testUser1, cred, ParameterUtil.newParameterList("password", "password"));
		} catch (FactoryException e) {
			logger.error(e);
		}
		logger.info("VET: " + vet);
		
		// logger.info(JSONUtil.exportObject(res, RecordSerializerConfig.getUnfilteredModule()));
	}
	
	/*
	
	@Test
	public void TestCreateKeySet() {
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = this.getCreateUser("testUser1");
		// byte[] useCredBytes = "password".getBytes();
		BaseRecord keySet = null;
		try {
			ParameterList plist = ParameterUtil.newParameterList("path", "~/keys");
			plist.parameter("keyPair", true);
			plist.parameter("secretKey", true);
			keySet = mf.newInstance(ModelNames.MODEL_KEY_SET, testUser1, null, plist);
			assertNotNull("Credential is null", keySet);
		} catch (FactoryException  e) {
			logger.error(e);
		}
		
		assertNotNull("Keyset is null", keySet);
		
		boolean created = ioContext.getRecordUtil().createRecord(keySet);
		assertTrue("Keyset failed to update", created);
		
		long id = keySet.get(FieldNames.FIELD_ID);
		CryptoBean ikey = new CryptoBean(ioContext.getRecordUtil().getRecordById(testUser1, ModelNames.MODEL_KEY_SET, id));
		assertNotNull("Public key is null", ikey.getPublicKey());
		assertNotNull("Private key is null", ikey.getPrivateKey());
		assertNotNull("Secret key is null", ikey.getSecretKey());

	}
	
	@Test
	public void TestX509() {
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = this.getCreateUser("testUser1");
		CryptoBean caKey = null;
		try {
			caKey = new CryptoBean(ioContext.getRecordUtil().getCreateRecord(testUser1, ModelNames.MODEL_KEY_SET, "Test CA Key", "~/keys", orgContext.getOrganizationId()));
			if(!caKey.hasField(FieldNames.FIELD_PUBLIC_FIELD_KEY)) {
				CryptoFactory.getInstance().generateKeyPair(caKey);
				assertTrue("Failed to update record", ioContext.getRecordUtil().updateRecord(caKey));
			}
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		assertNotNull("CA Key is null", caKey);
		assertNotNull("Expected a public key", caKey.getPublicKey());
		
		// generate(CryptoBean crypto, KeyStoreBean signerBean, BaseRecord actor, String domain, int days, String pass)
		ParameterList plist = ParameterUtil.newParameterList("cn", testUser1.get(FieldNames.FIELD_NAME));
		plist.parameter("organization", orgContext.getOrganizationPath());
		KeyStoreBean caStore = null;
		try {
			caStore = CertificateUtil.generate(caKey, null, plist);
		}
		catch(NullPointerException | OperatorCreationException | CertificateException | CertIOException e) {
			logger.error(e);
			
		}
		assertNotNull("CA Store is null", caStore);
		assertNotNull("Cert is null", caStore.getCertificate());
		String ser = JSONUtil.exportObject(caStore, RecordSerializerConfig.getUnfilteredModule());
		// logger.info(ser);
		
		BaseRecord iks = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Import was null", iks);
		
	}
	
	
	@Test
	public void TestKeyStoreBean() {
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = this.getCreateUser("testUser1");
		BaseRecord testUser2 = this.getCreateUser("testUser2");
		String caName = "Personal CA";
		String uName = "testUser2 - Personal";
		// String caName = "Personal CA - " + System.currentTimeMillis();
		// String uName = "testUser2 - " + System.currentTimeMillis();
		KeyStoreBean ca = KeyStoreUtil.getCreateStore(testUser1, caName, null);

		assertNotNull("CA is null", ca);
		// logger.info(JSONUtil.exportObject(ca, RecordSerializerConfig.getForeignUnfilteredModule()));
		assertNotNull("Certificate is null", ca.getCertificate());
	
		KeyStoreBean userCert = KeyStoreUtil.getCreateStore(testUser2, uName, ca);
		assertNotNull("User cert is null", userCert);
		CryptoBean cab = ca.getCryptoBean();
		
		// logger.info(JSONUtil.exportObject(cab, RecordSerializerConfig.getForeignUnfilteredModule()));
		assertNotNull("Expected a user cert", userCert.getCertificate());
		assertNotNull("Expected a public key", ca.getCryptoBean().getPublicKey());
		boolean verified = false;
		try {
			Certificate cert = userCert.getCertificate();
			X509Certificate xcert = CertificateUtil.decodeCertificate(userCert.getCertificate().getEncoded());

			xcert.verify(ca.getCryptoBean().getPublicKey());
			//userCert.getCertificate().verify(userCert.getCryptoBean().getPublicKey());
			verified = true;
		} catch (InvalidKeyException | CertificateException | NoSuchAlgorithmException | NoSuchProviderException | SignatureException e) {
			logger.error(e);
		}
		
		logger.info("Verify Cert: " + verified);
		
		// assertTrue("Expected certificate to be verified", verified);
	
	}

	@Test
	public void TestKeyStore() {
		String caName = "Personal CA";
		String tsName = "Personal Trust Store";
		String uName = "testUser2 - Personal";
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = this.getCreateUser("testUser1");
		KeyStoreBean ca = KeyStoreUtil.getCreateStore(testUser1, caName, null);
		KeyStoreBean ts = KeyStoreUtil.getCreateStore(testUser1, tsName, null);
		assertNotNull("CA is null", ca);
		assertNotNull("CA Cert is null", ca.getCertificate());
		char[] pwd = "password".toCharArray();
		String path = "./am7/keystore.jks";
		String tpath = "./am7/truststore.jks";
		KeyStore ks = KeyStoreUtil.getCreateKeyStore(path, pwd);
		KeyStore kts = KeyStoreUtil.getCreateKeyStore(tpath, pwd);
		assertNotNull("Key store is null", ks);
		String alias = "testUser1 - Personal CA";
		String talias = "testUser1 - Personal Trust";
		if(KeyStoreUtil.getCertificate(ks, alias) == null) {
			boolean imported = false;
			try {
				imported = KeyStoreUtil.importCertificate(ks, ca.getCertificate().getEncoded(), alias);
				KeyStoreUtil.saveKeyStore(ks, path, pwd);
			} catch (CertificateEncodingException e) {
				logger.error(e);
				
			}
			assertTrue("Expected certificate to be imported", imported);
		}
		
		byte[] p12 = CertificateUtil.toPKCS12(ca, "password");
		assertTrue("Expected bytes", p12.length > 0);
		if(KeyStoreUtil.getCertificate(kts, talias) == null) {
			boolean imported = KeyStoreUtil.importPKCS12(kts, p12, talias, pwd);
			assertTrue("Expected certificate to be imported", imported);
			KeyStoreUtil.saveKeyStore(kts, tpath, pwd);
		}
		
		
	}
	*/
	
}


