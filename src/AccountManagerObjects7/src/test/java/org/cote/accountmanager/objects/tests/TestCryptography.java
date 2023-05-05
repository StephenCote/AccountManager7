package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.CryptoUtil;
import org.junit.Test;

public class TestCryptography extends BaseTest {

	@Test
	public void TestCipher() {
		logger.info("Test AES");
		CryptoBean bean = new CryptoBean();
		
		logger.info("Test default values");
		
		String hashAlgo = bean.get("hash.algorithm");

		assertNotNull("Hash Algorithm was null", hashAlgo);
		
		try {
			bean.set("cipher", RecordFactory.newInstance("cipherKey"));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
		
		BaseRecord rec = bean.get("cipher");
		assertNotNull("Cipher object was null", rec);
		boolean encrypt = rec.get("encrypt");
		logger.info("Encrypt: " + encrypt);
		
		boolean cenc = bean.get("cipher.encrypt");
		
		assertTrue("Expected values to match", cenc == encrypt);
		
		/// Try to generate a secret key
		CryptoFactory.getInstance().generateSecretKey(bean);
		
		assertTrue("Expected record to have a cipher", bean.hasField("cipher"));
		
		String rawText = "Example text to encipher";
		byte[] enc = CryptoUtil.encipher(bean, rawText.getBytes());
		
		//logger.info(JSONUtil.exportObject(bean, RecordSerializerConfig.getUnfilteredModule()));
		
		logger.info("Serialize bean");
		String erec = CryptoFactory.getInstance().serialize(bean, false, false, true, false, false);
		//logger.info(erec);
		
		CryptoBean ibean = new CryptoBean();

		try {
			logger.info("Import bean");
			CryptoFactory.getInstance().importCryptoBean(ibean, erec.getBytes(StandardCharsets.UTF_8), false);
		}
		catch(Exception e) {
			
		}
		logger.info("Decipher");
		//logger.info(JSONUtil.exportObject(ibean, RecordSerializerConfig.getUnfilteredModule()));
		byte[] dec = CryptoUtil.decipher(ibean, enc);
		assertTrue("Expected decrypted value", dec.length > 0);
		String decText = new String(dec);
		assertTrue("Expected text to match", decText.equals(rawText));
		//logger.info("Deciphered: " + decText);
	}
	
	@Test
	public void TestRSA() {
		logger.info("Test RSA");
		CryptoBean bean = new CryptoBean();
		
		
		CryptoFactory.getInstance().generateKeyPair(bean);
		String rawText = "Example text to encipher";
		byte[] enc = CryptoUtil.encrypt(bean,  rawText.getBytes(StandardCharsets.UTF_8));
		String erec = CryptoFactory.getInstance().serialize(bean, true, true, false, false, false);
		
		CryptoBean ibean = new CryptoBean();
		try {
			CryptoFactory.getInstance().importCryptoBean(ibean, erec.getBytes(StandardCharsets.UTF_8), false);
		}
		catch(NullPointerException e) {
			logger.error(e);
			
		}
		byte[] dec = CryptoUtil.decrypt(ibean, enc);
		String decText = new String(dec);
		assertTrue("Expected text to match", decText.equals(rawText));
	}
	
	@Test
	public void TestEC() {
		logger.info("Test RSA");
		CryptoBean bean = new CryptoBean();
		
		
		CryptoFactory.getInstance().generateECKeySet(bean);
		//CryptoFactory.getInstance().generateSecretKey(bean);
		
		String rawText = "Example text to encipher";
		byte[] enc = CryptoUtil.encrypt(bean,  rawText.getBytes(StandardCharsets.UTF_8));
		String erec = CryptoFactory.getInstance().serialize(bean, true, true, true, false, false);
		
		//logger.info(JSONUtil.exportObject((BaseRecord)bean, RecordSerializerConfig.getUnfilteredModule()));
		//logger.info(erec);
		
		CryptoBean ibean = new CryptoBean();
		CryptoFactory.getInstance().configureECBean(ibean);
		
		
		try {
			CryptoFactory.getInstance().importCryptoBean(ibean, erec.getBytes(StandardCharsets.UTF_8), true);
		}
		catch(NullPointerException e) {
			logger.error(e);
			
		}
		
		byte[] dec = CryptoUtil.decrypt(ibean, enc);
		String decText = new String(dec);
		assertTrue("Expected text (" + decText + ") to match (" + rawText + ")", decText.equals(rawText));
		
		String raw2 = "More example text";
		byte[] enc2 = CryptoUtil.encrypt(bean,  raw2.getBytes());
		byte[] dec2 = CryptoUtil.decrypt(bean, enc2);
		String decText2 = new String(dec2);
		
		assertTrue("Expected text to match", decText2.equals(raw2));
		
		String raw3 = "More and more example";
		//logger.info(JSONUtil.exportObject(bean, RecordSerializerConfig.getUnfilteredModule()));
		byte[] enc3 = CryptoUtil.encipher(bean, raw3.getBytes());
		byte[] dec3 = CryptoUtil.decipher(bean, enc3);
		String decText3 = new String(dec3);
		assertTrue("Expected text to match", decText3.equals(raw3));
		
		//logger.info(JSONUtil.exportObject(ibean, RecordSerializerConfig.getUnfilteredModule()));
		//logger.info(CryptoUtil.serialize(ibean, true, true, true));
	
	}

	
	@Test
	public void TestDigest() {
		String demoText = "This is the text to hash";
		logger.info("Testing digest with default algorithm");
		CryptoBean bean = new CryptoBean();

		logger.info("Testing digest with alternate algorithm");
		CryptoBean bean2 = new CryptoBean();
		CryptoBean bean3 = new CryptoBean();
		try {
			bean.set(FieldNames.FIELD_HASH_FIELD_ALGORITHM, "SHA-512");
			bean2.set(FieldNames.FIELD_HASH_FIELD_ALGORITHM, "SHA3-512");
			bean3.set(FieldNames.FIELD_HASH_FIELD_ALGORITHM, "SHA3-224");
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		CryptoFactory cf = CryptoFactory.getInstance();

		String hash1 = CryptoUtil.getDigestAsString(bean, demoText);
		String hash2 = CryptoUtil.getDigestAsString(bean2, demoText);
		String hash3 = CryptoUtil.getDigestAsString(bean3, demoText);
		logger.info(hash1);
		logger.info(hash2);
		logger.info(hash3);
		assertFalse("Didn't expect hashes to match", hash1.equals(hash2));
		assertFalse("Didn't expect hashes to match", hash1.equals(hash3));
		assertFalse("Didn't expect hashes to match", hash2.equals(hash3));
	}
	
	@Test
	public void TestPassKey() {
		logger.info("Test RSA");
		CryptoBean bean = new CryptoBean();
		CryptoFactory.getInstance().configurePassBean(bean);
		
	    CryptoUtil.setRandomSalt(bean);
		CryptoFactory.getInstance().setPassKey(bean, "Demo Password", false);
		
		String raw1 = "Example text to encipher";
		byte[] enc = CryptoUtil.encipher(bean, raw1.getBytes());
		
		String saltOnly = CryptoFactory.getInstance().serialize(bean, false, false, false, true, false);
		
		String ser = CryptoFactory.getInstance().serialize(bean, false, false, true, false, false);
		
		CryptoBean ibean = new CryptoBean();
		//logger.info(saltOnly);
		//logger.info(JSONUtil.exportObject(isalt, RecordSerializerConfig.getUnfilteredModule()));
		

		logger.info("Testing if a straight import of a pass key can be used to decrypt (salt not reused; note: salt is only used for hash and pass key calculation)");
		CryptoFactory.getInstance().importCryptoBean(ibean, ser.getBytes(), false);
		byte[] dec = CryptoUtil.decipher(ibean, enc);
		
		String decText = new String(dec);
		assertTrue("Text does not match", decText.equals(raw1));
		
		logger.info("Testing if a pass key can be recreated with the passphrase and the salt");
		CryptoBean isalt = new CryptoBean();
		CryptoFactory.getInstance().importCryptoBean(isalt, saltOnly.getBytes(), false);
		
		try {
			/// didn't import anything about the cipher itself, so config for a pass key with defaults
			CryptoFactory.getInstance().configurePassBean(isalt);
			CryptoFactory.getInstance().setPassKey(isalt, "Demo Password", false);
			//logger.info(JSONUtil.exportObject(isalt, RecordSerializerConfig.getUnfilteredModule()));
			byte[] dec2 = CryptoUtil.decipher(isalt, enc);
			
			String decText2 = new String(dec2);
			assertTrue("Text does not match", decText2.equals(raw1));
		}
		catch(Exception e) {
			
		}
		//logger.info(ser);
	}
	
	/*
	@Test
	public void TestListAlgorithms() {

		logger.info("Secure Random Algorithms");
        Set<String> secureRandom = Security.getAlgorithms("SecureRandom");
        secureRandom.forEach(x -> System.out.println(x));
		
        logger.info("Message Digest Algorithms");
        Set<String> messageDigest = Security.getAlgorithms("MessageDigest");
        messageDigest.forEach(x -> System.out.println(x));
	}
	*/
}
