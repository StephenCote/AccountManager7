package org.cote.accountmanager.factory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.bouncycastle.util.io.pem.PemWriter;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.JsonReader;
import org.cote.accountmanager.io.JsonWriter;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.JSONUtil;


public class CryptoFactory {
	public static final Logger logger = LogManager.getLogger(CryptoFactory.class);

	private static String[] importFields = new String[] {
			FieldNames.FIELD_PUBLIC, FieldNames.FIELD_PRIVATE, FieldNames.FIELD_CIPHER, FieldNames.FIELD_HASH
/*
			FieldNames.FIELD_PUBLIC_FIELD_KEYSIZE,FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC,FieldNames.FIELD_PUBLIC_FIELD_KEYMODE,
			FieldNames.FIELD_PRIVATE_FIELD_KEYSIZE,FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC,FieldNames.FIELD_PRIVATE_FIELD_KEYMODE,FieldNames.FIELD_CIPHER_FIELD_IV,FieldNames.FIELD_CIPHER_FIELD_KEY,
			FieldNames.FIELD_CIPHER_FIELD_ENCRYPT,FieldNames.FIELD_CIPHER_FIELD_KEYSIZE,FieldNames.FIELD_CIPHER_FIELD_KEYSPEC,FieldNames.FIELD_CIPHER_FIELD_KEYMODE,FieldNames.FIELD_HASH_FIELD_KEYFUNCTION,
			FieldNames.FIELD_HASH_FIELD_ALGORITHM, FieldNames.FIELD_HASH_FIELD_PRNG
*/
	};
	
	private static CryptoFactory instance = null;
	private static SecureRandom secureRandom = null;
	
	private final Map<String, CryptoBean> keyRing = new ConcurrentHashMap<>();
	
	public static CryptoFactory getInstance(){
		if(instance != null){
			return instance;
		}
		instance = new CryptoFactory();
		return instance;
	}
	
	public String holdKey(CryptoBean key) {
		String keyId = UUID.randomUUID().toString();
		holdKey(keyId, key);
		return keyId;
	}
	public void holdKey(String keyId, CryptoBean key) {
		keyRing.put(keyId,  key);
	}
	public CryptoBean pullKey(String keyId) {
		CryptoBean bean = null;
		if(keyRing.containsKey(keyId)) {
			bean = keyRing.get(keyId);
			keyRing.remove(keyId);
		}
		return bean;
	}

	public CryptoFactory(){
		Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
		secureRandom = new SecureRandom();
	}
	
	public String randomKey(int length) {
	    int lower = 48;
	    int upper = 122;
	    return secureRandom.ints(lower, upper + 1)
	      .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
	      .limit(length)
	      .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
	      .toString();
	}
	


	public byte[] serializeCipher(CryptoBean bean){
		
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		JsonWriter writer = new JsonWriter();
		try {
			writer.write(bean.get(FieldNames.FIELD_CIPHER), baos);
		} catch (WriterException e) {
			logger.error(e);
			
		}
		return baos.toByteArray();

	}

	
	public byte[] serializeRSAPrivateKey(CryptoBean bean){
		
		try {
			bean.set(FieldNames.FIELD_PRIVATE_FIELD_KEY, null);
			BaseRecord rec = RecordFactory.newInstance(ModelNames.MODEL_RSA_KEY);
			RSAPrivateKey keySpec = (RSAPrivateKey) bean.getPrivateKey();
			rec.set(FieldNames.FIELD_RSA_MODULUS, keySpec.getModulus().toByteArray());
			rec.set(FieldNames.FIELD_RSA_IEXPONENT, keySpec.getPrivateExponent().toByteArray());
			JsonWriter writer = new JsonWriter();
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			writer.write(rec, baos);
			bean.set(FieldNames.FIELD_PRIVATE_FIELD_KEY, baos.toByteArray());
		}
		catch(FieldException | ModelNotFoundException | ValueException | WriterException e) {
			logger.error(e);
			
		}
		return bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEY);
		
	}
	public byte[] serializeRSAPublicKey(CryptoBean bean){
		
		try {
			bean.set(FieldNames.FIELD_PUBLIC_FIELD_KEY, null);
			BaseRecord rec = RecordFactory.newInstance(ModelNames.MODEL_RSA_KEY);
			KeyFactory keyFactory = KeyFactory.getInstance(bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC));
			RSAPublicKeySpec keySpec = keyFactory.getKeySpec(bean.getPublicKey(), RSAPublicKeySpec.class);
			rec.set(FieldNames.FIELD_RSA_MODULUS, keySpec.getModulus().toByteArray());
			rec.set(FieldNames.FIELD_RSA_EXPONENT, keySpec.getPublicExponent().toByteArray());
			JsonWriter writer = new JsonWriter();
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			writer.write(rec, baos);
			bean.set(FieldNames.FIELD_PUBLIC_FIELD_KEY, baos.toByteArray());
		}
		catch(FieldException | ModelNotFoundException | ValueException | WriterException | NoSuchAlgorithmException | InvalidKeySpecException e) {
			logger.error(e);
			logger.error(JSONUtil.exportObject(bean, RecordSerializerConfig.getForeignUnfilteredModule()));
			e.printStackTrace();
			
		}
		return bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEY);
		
	}
	
	public byte[] serializeECPublicKey(CryptoBean bean) {
		try {
			bean.set(FieldNames.FIELD_PUBLIC_FIELD_KEY, null);
			
			StringWriter swriter = new StringWriter();
			PemWriter privateKeyWriter = new PemWriter(swriter);
			privateKeyWriter.writeObject(new PemObject("PUBLIC KEY", bean.getPublicKey().getEncoded()));
			privateKeyWriter.close();
			swriter.close();
			bean.set(FieldNames.FIELD_PUBLIC_FIELD_KEY, swriter.toString().getBytes(StandardCharsets.UTF_8));
		}
		catch(FieldException | ModelNotFoundException | ValueException | IOException e) {
			logger.error(e);
			
		}
		return bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEY);
	}
	
	public byte[] serializeECPrivateKey(CryptoBean bean) {
		try {
			bean.set(FieldNames.FIELD_PRIVATE_FIELD_KEY, null);
			
			StringWriter swriter = new StringWriter();
			PemWriter privateKeyWriter = new PemWriter(swriter);
			privateKeyWriter.writeObject(new PemObject("PRIVATE KEY", bean.getPrivateKey().getEncoded()));
			privateKeyWriter.close();
			swriter.close();
			bean.set(FieldNames.FIELD_PRIVATE_FIELD_KEY, swriter.toString().getBytes(StandardCharsets.UTF_8));
		}
		catch(FieldException | ModelNotFoundException | ValueException | IOException e) {
			logger.error(e);
			
		}
		return bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEY);
	}
	

	public void configurePassBean(CryptoBean bean) {
		try {

			if(!bean.hasField(FieldNames.FIELD_CIPHER) || bean.get(FieldNames.FIELD_CIPHER) == null) {
				bean.set(FieldNames.FIELD_CIPHER, RecordFactory.newInstance(ModelNames.MODEL_CIPHER_KEY));
				bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC, "AES");
				bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYMODE, "AES/CBC/PKCS5Padding");
			}
			//bean.set(FieldNames.FIELD_HASH_PROVIDER, "PBKDF2WithHmacSHA512");
			//bean.set(FieldNames.FIELD_HASH_PROVIDER, null)
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	public void setSalt(BaseRecord rec) {
		if(rec == null) {
			return;
		}
		if(rec.get(FieldNames.FIELD_HASH) == null) {
			try {
				BaseRecord salt = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_HASH);
				salt.set(FieldNames.FIELD_OWNER_ID, rec.get(FieldNames.FIELD_OWNER_ID));
				salt.set(FieldNames.FIELD_ORGANIZATION_ID, rec.get(FieldNames.FIELD_ORGANIZATION_ID));
				rec.set(FieldNames.FIELD_HASH, salt);
			} catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
				logger.error(e);
				
			}
		}
		byte[] salt = rec.get(FieldNames.FIELD_HASH_FIELD_SALT);
		if(salt == null || salt.length == 0) {
			CryptoUtil.setRandomSalt(rec);
		}
	}
	
	public void setPassKey(CryptoBean bean, String passKey, boolean encryptedPassKey){
		//if(!bean.hasField(FieldNames.FIELD_HASH_FIELD_SALT)) CryptoUtil.setRandomSalt(bean);
		setSalt(bean);
		setPassKey(bean, passKey, bean.get(FieldNames.FIELD_HASH_FIELD_SALT), encryptedPassKey);
	}

	public void setPassKey(CryptoBean bean, String passKey, byte[] salt, boolean encryptedPassKey){

		try{
			SecretKeyFactory factory = SecretKeyFactory.getInstance(bean.get(FieldNames.FIELD_HASH_FIELD_KEYFUNCTION));

			KeySpec spec = new javax.crypto.spec.PBEKeySpec(passKey.toCharArray(), salt, 65536, bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSIZE));
			SecretKey tmp = factory.generateSecret(spec);
			SecretKey secret = new SecretKeySpec(tmp.getEncoded(), bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC));
			byte[] iv = new byte[0];
			String keyMode = bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYMODE);
			if(keyMode.startsWith("AES")) {
				Cipher cipher = Cipher.getInstance(bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYMODE));
				cipher.init(Cipher.ENCRYPT_MODE, secret, new IvParameterSpec(salt, 0, 16));
				AlgorithmParameters params = cipher.getParameters();
				iv = params.getParameterSpec(IvParameterSpec.class).getIV();
			}
			setSecretKey(bean, secret.getEncoded(), iv, encryptedPassKey);
		} catch (NoSuchAlgorithmException | InvalidKeySpecException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | InvalidParameterSpecException e) {
			logger.error(e);
			e.printStackTrace();
			
		}  
	}
	public void setSecretKey(CryptoBean bean, byte[] key, byte[] iv, boolean encryptedCipher){
		byte[] decKey = key;
		byte[] decIv = iv;
		try {
			if(encryptedCipher){
				decKey = CryptoUtil.decrypt(bean, key);
				if(iv.length > 0) {
					decIv = CryptoUtil.decrypt(bean, iv);
				}
				// logger.info("Dec: " + decKey.length);
				bean.set(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT, true);
			}
			String spec = bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC);
			// logger.info("Dec Key: " + decKey.length + " / " + spec);
			bean.setSecretKey(new SecretKeySpec(decKey, spec));
			
			bean.set(FieldNames.FIELD_CIPHER_FIELD_IV, iv);
			bean.set(FieldNames.FIELD_CIPHER_FIELD_KEY, key);
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
			
		}
	}
	
	public void setPublicKey(CryptoBean bean, byte[] publicKey){
		PublicKey pubKey = null;
		try {
			KeyFactory factory = KeyFactory.getInstance(bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC));
    	   	X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(publicKey);
			pubKey = factory.generatePublic(x509KeySpec);
		} catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
			logger.error(e);
			
		}
		bean.setPublicKey(pubKey);
		
	}
	
	public void setPrivateKey(CryptoBean bean, byte[] privateKey){
		PrivateKey privKey = null;
		try{
			// logger.info("**** Set Private Key: " + privateKey.length + " / " + bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC));
	        KeyFactory kFact = KeyFactory.getInstance(bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC));
			PKCS8EncodedKeySpec privKeySpec = new PKCS8EncodedKeySpec(privateKey);
			privKey = kFact.generatePrivate(privKeySpec);		
		}
		catch(Exception e){
			logger.error(e);
		}
		bean.setPrivateKey(privKey);
	}
	
	public void setPublicKey(CryptoBean bean, byte[] modBytes, byte[] expBytes){

		BigInteger modules = new BigInteger(1, modBytes);
		BigInteger exponent = new BigInteger(1, expBytes);

		PublicKey pubKey = null;
		try {
			KeyFactory factory = KeyFactory.getInstance(bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC));
			
			RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(modules, exponent);
			pubKey = factory.generatePublic(pubSpec);
		} catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
			logger.error(e);
			
		}
		bean.setPublicKey(pubKey);
	}
	public void setECDSAPublicKey(CryptoBean bean, byte[] ecdsaKey) {
		try {
			String key = new String(ecdsaKey,StandardCharsets.UTF_8);
			PemObject spki = new PemReader(new StringReader(key)).readPemObject();
			if(spki == null) {
				logger.error("Null Pem object");
				return;
			}
			/// KeyFactory keyGen = KeyFactory.getInstance(bean.get("asymmetricCipherKeySpec"));
			KeyFactory keyGen = KeyFactory.getInstance(bean.get(FieldNames.FIELD_AGREEMENTSPEC));
			PublicKey pubK = keyGen.generatePublic(new X509EncodedKeySpec(spki.getContent()));
        	bean.setPublicKey(pubK);
        	//bean.set("publicKeybytes", pubK.getEncoded());
		}
		catch(NoSuchAlgorithmException | InvalidKeySpecException | IOException  e) {
			logger.error(e);
			
		}
	}
	public void setECDSAPrivateKey(CryptoBean bean, byte[] ecdsaKey) {
		
		try {
			String key = new String(ecdsaKey,StandardCharsets.UTF_8);
			PemObject spki = new PemReader(new StringReader(key)).readPemObject();
			if(spki == null) {
				logger.error("Null Pem object");
				return;
			}
			/// KeyFactory keyGen = KeyFactory.getInstance(bean.get("asymmetricCipherKeySpec"));
			KeyFactory keyGen = KeyFactory.getInstance(bean.get(FieldNames.FIELD_AGREEMENTSPEC));
			PrivateKey privK = keyGen.generatePrivate(new PKCS8EncodedKeySpec(spki.getContent()));
        	bean.setPrivateKey(privK);
        	//bean.set("privateKeybytes", privK.getEncoded());

		}
		catch(NoSuchAlgorithmException | InvalidKeySpecException | IOException e) {
			logger.error(e);
			
		}
	}
	public void setRSAPublicKey(CryptoBean bean, byte[] rsaKey){
		
		String keyStr = new String(rsaKey, StandardCharsets.UTF_8);
		JsonReader reader = new JsonReader();
		try {
			if(!bean.hasField(FieldNames.FIELD_PUBLIC) || bean.get(FieldNames.FIELD_PUBLIC) == null) {
				bean.set(FieldNames.FIELD_PUBLIC, RecordFactory.newInstance(ModelNames.MODEL_KEY));
			}
			//bean.set(FieldNames.FIELD_PUBLIC_FIELD_KEY, rsaKey);
			// logger.info("Set RSA Public Key: " + keyStr);
			BaseRecord key = reader.read(ModelNames.MODEL_KEY_SET, keyStr);
			byte[] modBytes = key.get(FieldNames.FIELD_RSA_MODULUS);
			byte[] expBytes = key.get(FieldNames.FIELD_RSA_EXPONENT);
			setPublicKey(bean, modBytes, expBytes);
		}
		catch(ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}

	}
	public void setRSAPrivateKey(CryptoBean bean, byte[] rsaKey){
		JsonReader reader = new JsonReader();
		try {
			if(!bean.hasField(FieldNames.FIELD_PRIVATE) || bean.get(FieldNames.FIELD_PRIVATE) == null) {
				bean.set(FieldNames.FIELD_PRIVATE, RecordFactory.newInstance(ModelNames.MODEL_KEY));
			}
			//bean.set("privateKeybytes", rsaKey);
			String keyStr = new String(rsaKey, StandardCharsets.UTF_8);
			// logger.info("Set RSA Private Key: " + keyStr);
			BaseRecord key = reader.read(ModelNames.MODEL_KEY_SET, keyStr);
			byte[] modBytes = key.get(FieldNames.FIELD_RSA_MODULUS);
			byte[] dBytes = key.get(FieldNames.FIELD_RSA_IEXPONENT);
			setPrivateKey(bean, modBytes, dBytes);
		}
		catch(ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	public void setPrivateKey(CryptoBean bean, byte[] modBytes, byte[] dBytes){
		BigInteger modulus = new BigInteger(1, modBytes);
		BigInteger d = new BigInteger(1, dBytes);

		PrivateKey priKey = null;
		try {
			KeyFactory factory = KeyFactory.getInstance(bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC));
			RSAPrivateKeySpec privSpec = new RSAPrivateKeySpec(modulus, d);
			priKey = factory.generatePrivate(privSpec);
		} catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
			logger.error(e.getMessage());
			logger.error(e);
		}
		bean.setPrivateKey(priKey);

	}
	public CryptoBean createCryptoBean(byte[] keys, boolean encryptedCipher){
		CryptoBean bean = new CryptoBean();
		importCryptoBean(bean, keys, encryptedCipher);
		return bean;
	}
	public BaseRecord export(CryptoBean bean, boolean includePrivateKey, boolean includePublicKey, boolean includeCipher, boolean includeSalt, boolean includeAlgorithms){
		BaseRecord rec = null;
		CryptoFactory sf = getInstance();

		try {
			
			rec = RecordFactory.newInstance(ModelNames.MODEL_KEY_SET);
			
			boolean includeECMeta = false;

			if(includeSalt || includeAlgorithms) {
				logger.info("Serialize salt and algos");
				rec.set(FieldNames.FIELD_HASH_FIELD_SALT, (includeSalt ? bean.get(FieldNames.FIELD_HASH_FIELD_SALT) : null));
				rec.set(FieldNames.FIELD_HASH_FIELD_KEYFUNCTION, (includeAlgorithms ? bean.get(FieldNames.FIELD_HASH_FIELD_KEYFUNCTION) : null));
				rec.set(FieldNames.FIELD_HASH_FIELD_ALGORITHM, (includeAlgorithms ? bean.get(FieldNames.FIELD_HASH_FIELD_ALGORITHM) : null));
				rec.set(FieldNames.FIELD_HASH_FIELD_PRNG, (includeAlgorithms ? bean.get(FieldNames.FIELD_HASH_FIELD_PRNG) : null));
			}
			
			if(bean.hasField(FieldNames.FIELD_PUBLIC) && includePublicKey) {
				logger.info("Serialize public key");
				String spec = bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC);
				rec.set(FieldNames.FIELD_PUBLIC_FIELD_KEYSIZE, (includeAlgorithms ? bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSIZE) : 0));
				rec.set(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC, (includeAlgorithms ? bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC) : null));
				rec.set(FieldNames.FIELD_PUBLIC_FIELD_KEYMODE, (includeAlgorithms ? bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYMODE) : null));
				if(spec.matches("RSA")) {
					sf.serializeRSAPublicKey(bean);
				}
				else if(spec.startsWith("EC")) {
					sf.serializeECPublicKey(bean);
					includeECMeta = true;
				}
				else {
					logger.error("Unhandled spec: " + spec);
				}
				// rec.set(FieldNames.FIELD_PUBLIC, bean.get(FieldNames.FIELD_PUBLIC));
				rec.set(FieldNames.FIELD_PUBLIC_FIELD_KEY, bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEY));
			}
			else {
				// logger.info("Skip public key");
			}
			if(bean.hasField(FieldNames.FIELD_PRIVATE) && includePrivateKey) {
				logger.info("Serialize private key");
				String spec = bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC);
				rec.set(FieldNames.FIELD_PRIVATE_FIELD_KEYSIZE, (includeAlgorithms ? bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSIZE) : 0));
				rec.set(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC, (includeAlgorithms ? bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC) : null));
				rec.set(FieldNames.FIELD_PRIVATE_FIELD_KEYMODE, (includeAlgorithms ? bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYMODE) : null));
				if(spec.matches("RSA")) {
					sf.serializeRSAPrivateKey(bean);
				}
				else if(spec.startsWith("EC")) {
					sf.serializeECPrivateKey(bean);
					includeECMeta = true;
				}
				else {
					logger.error("Unhandled spec: " + spec);
				}
				rec.set(FieldNames.FIELD_PRIVATE_FIELD_KEY, bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEY));
			}
			else {
				// logger.info("Skip private key");
			}

			if(bean.hasField(FieldNames.FIELD_CIPHER) && includeCipher) {
				logger.info("Serialize cipher");
				// sf.serializeCipher(bean);
				rec.set(FieldNames.FIELD_CIPHER_FIELD_IV, bean.get(FieldNames.FIELD_CIPHER_FIELD_IV));
				rec.set(FieldNames.FIELD_CIPHER_FIELD_KEY, bean.get(FieldNames.FIELD_CIPHER_FIELD_KEY));
				rec.set(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT, bean.get(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT));
				rec.set(FieldNames.FIELD_CIPHER_FIELD_KEYSIZE, (includeAlgorithms ? bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSIZE) : 0));
				rec.set(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC, (includeAlgorithms ? bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC) : null));
				rec.set(FieldNames.FIELD_CIPHER_FIELD_KEYMODE, (includeAlgorithms ? bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYMODE) : null));
			}
			else {
				//logger.info("Skip cipher key");
			}

			if(includeECMeta) {
				rec.set(FieldNames.FIELD_AGREEMENTSPEC, (includeAlgorithms ? bean.get(FieldNames.FIELD_AGREEMENTSPEC) : null));
				rec.set(FieldNames.FIELD_CURVE_NAME, (includeAlgorithms ? bean.get(FieldNames.FIELD_CURVE_NAME) : null));
			}

		} catch (FieldException | ModelNotFoundException | ValueException  e1) {
			logger.error(e1);
		}
		return rec;
	}	
	public String serialize(CryptoBean bean, boolean includePrivateKey, boolean includePublicKey, boolean includeCipher, boolean includeSalt, boolean includeAlgorithms){
		
		BaseRecord rec = export(bean, includePrivateKey, includePublicKey, includeCipher, includeSalt, includeAlgorithms);
		JsonWriter writer = new JsonWriter();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		try {
			writer.write(rec, baos);
		} catch (WriterException e1) {
			logger.error(e1);
		}
		return new String(baos.toByteArray());
		
	}
	
	public void importCryptoBean(CryptoBean bean, byte[] keySet, boolean encryptedCipher){
		JsonReader reader = new JsonReader();
		BaseRecord record = null;

		try {
			record = reader.read(ModelNames.MODEL_KEY_SET, new String(keySet));
			setMembers(bean, record, encryptedCipher);
		}
		catch(ReaderException e) {
			logger.error(e);
			
		}
		
	}

	public void setMembers(CryptoBean bean, BaseRecord rec, boolean encryptedCipher) {
		try {

			for(String field : importFields) {
				copyField(rec, bean, field);
			}

			if(rec.hasField(FieldNames.FIELD_HASH_FIELD_SALT)) {
				bean.set(FieldNames.FIELD_HASH_FIELD_SALT, rec.get(FieldNames.FIELD_HASH_FIELD_SALT));
			}
			
			if(rec.hasField(FieldNames.FIELD_PUBLIC_FIELD_KEY) && rec.get(FieldNames.FIELD_PUBLIC_FIELD_KEY) != null) {
				if(!bean.hasField(FieldNames.FIELD_PUBLIC) || bean.get(FieldNames.FIELD_PUBLIC) == null) {
					bean.set(FieldNames.FIELD_PUBLIC, RecordFactory.newInstance(ModelNames.MODEL_KEY));
				}
				if(((String)bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC)).startsWith("EC")) {
					setECDSAPublicKey(bean, rec.get(FieldNames.FIELD_PUBLIC_FIELD_KEY));	
				}
				else {
					setRSAPublicKey(bean, rec.get(FieldNames.FIELD_PUBLIC_FIELD_KEY));
				}
			}
			if(rec.hasField(FieldNames.FIELD_PRIVATE_FIELD_KEY) && rec.get(FieldNames.FIELD_PRIVATE_FIELD_KEY) != null) {
				if(!bean.hasField(FieldNames.FIELD_PRIVATE) || bean.get(FieldNames.FIELD_PRIVATE) == null) {
					bean.set(FieldNames.FIELD_PRIVATE, RecordFactory.newInstance(ModelNames.MODEL_KEY));
				}
				if(((String)bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC)).startsWith("EC")) {
					setECDSAPrivateKey(bean, rec.get(FieldNames.FIELD_PRIVATE_FIELD_KEY));	
				}
				else {
					setRSAPrivateKey(bean, rec.get(FieldNames.FIELD_PRIVATE_FIELD_KEY));
				}
			}
			
			if(rec.hasField(FieldNames.FIELD_CIPHER_FIELD_KEY) && rec.hasField(FieldNames.FIELD_CIPHER_FIELD_IV) && rec.get(FieldNames.FIELD_CIPHER_FIELD_KEY) != null) {
				if(!bean.hasField(FieldNames.FIELD_CIPHER) || bean.get(FieldNames.FIELD_CIPHER) == null) {
					bean.set(FieldNames.FIELD_CIPHER, RecordFactory.newInstance(ModelNames.MODEL_CIPHER_KEY));
				}
				setSecretKey(bean, rec.get(FieldNames.FIELD_CIPHER_FIELD_KEY), rec.get(FieldNames.FIELD_CIPHER_FIELD_IV), encryptedCipher);
			}
			
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	private void copyField(BaseRecord source, BaseRecord target, String fieldName) throws FieldException, ValueException, ModelNotFoundException {
		//logger.info(source.toFullString());
		if(source.hasField(fieldName) && source.getField(fieldName) != null) {
			target.set(fieldName, source.get(fieldName));
		}
	}
	public Cipher getEncryptCipherKey(CryptoBean bean){
		return getCipherKey(bean, false);
	}
	public Cipher getDecryptCipherKey(CryptoBean bean){
		return getCipherKey(bean, true);
	}
	private Cipher getCipherKey(CryptoBean bean,  boolean decrypt){
		/*
		if(decrypt && bean.getDecryptCipherKey() != null) {
			// logger.warn("**** " + bean.getDecryptCipherKey());
			return bean.getDecryptCipherKey();
		}
		else if(bean.getEncryptCipherKey() != null) {
			return bean.getEncryptCipherKey();
		}
		*/

		boolean bECD = ((String)bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC)).startsWith("EC");
		if(
			bean.getSecretKey() == null
			&&
			(bECD
			&&
			( decrypt && bean.getPrivateKey() == null)
			|| ( bean.getPublicKey() == null )
			)
		){
			return null;
		}

		Cipher cipherKey = null;
       try {
    	   /// "symmetricCipherKeySpec"
    	cipherKey = Cipher.getInstance((bECD ? bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC) : bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYMODE)));
    	//cipherKey = Cipher.getInstance(bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC));
		int mode = Cipher.ENCRYPT_MODE;
		if(decrypt) mode = Cipher.DECRYPT_MODE;

		if(bECD) {
			cipherKey.init(mode,  (decrypt ? bean.getPrivateKey() : bean.getPublicKey()));
		}
		else if(bean.get(FieldNames.FIELD_CIPHER_FIELD_IV) != null && ((byte[])bean.get(FieldNames.FIELD_CIPHER_FIELD_IV)).length > 0){
			boolean encrypted = bean.get(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT);
			byte[] biv = bean.get(FieldNames.FIELD_CIPHER_FIELD_IV);
			if(encrypted) {
				biv = CryptoUtil.decrypt(bean, biv);
			}
			IvParameterSpec iv = new IvParameterSpec(biv);
			cipherKey.init(mode, bean.getSecretKey(), iv);

		}
		else{
			cipherKey.init(mode,  bean.getSecretKey());
			bean.set(FieldNames.FIELD_CIPHER_FIELD_IV, cipherKey.getIV());
		}

		if(decrypt) {
			bean.setDecryptCipherKey(cipherKey);
		}
		else {
			bean.setEncryptCipherKey(cipherKey);
		}
		

       }
       catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException | InvalidAlgorithmParameterException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e.getMessage());
			logger.error(e);
			
		}
       return cipherKey;
	}
	public boolean generateSecretKey(CryptoBean bean){
		boolean ret = false;
		if(bean == null) {
			logger.error("CryptoBean is null");
			return ret;
		}
		SecretKey secretKey = null;
		try {
			if(!bean.hasField(FieldNames.FIELD_CIPHER) || bean.get(FieldNames.FIELD_CIPHER) == null) {
				bean.set(FieldNames.FIELD_CIPHER, RecordFactory.newInstance(ModelNames.MODEL_CIPHER_KEY));
			}
			if((boolean)bean.get(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT) && bean.getPublicKey() == null){
				logger.error("Cannot encrypt secret key with missing PKI data.  Verify PKI is initialized.");
				return false;
			}

			boolean bECD = (bean.get(FieldNames.FIELD_AGREEMENTSPEC) != null);

			if(bECD) {
				KeyAgreement keyAgreement = KeyAgreement.getInstance(bean.get(FieldNames.FIELD_AGREEMENTSPEC));
				keyAgreement.init(bean.getPrivateKey());
				keyAgreement.doPhase(bean.getPublicKey(), true);
				secretKey = keyAgreement.generateSecret(bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC));
			}
			else {
				KeyGenerator kgen = KeyGenerator.getInstance(bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC));
				kgen.init((int)bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSIZE));
				secretKey = kgen.generateKey();
			}
			bean.setSecretKey(secretKey);
			bean.set(FieldNames.FIELD_CIPHER_FIELD_KEY, secretKey.getEncoded());
			Cipher cipher = getCipherKey(bean, false);
			if(cipher == null) {
				logger.error("Cipher is null");
				return ret;
			}
			bean.set(FieldNames.FIELD_CIPHER_FIELD_IV, cipher.getIV());
			
			if((boolean)bean.get(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT)){
				bean.set(FieldNames.FIELD_CIPHER_FIELD_KEY, CryptoUtil.encrypt(bean, bean.get(FieldNames.FIELD_CIPHER_FIELD_KEY)));
				if(bean.get(FieldNames.FIELD_CIPHER_FIELD_IV) != null) bean.set(FieldNames.FIELD_CIPHER_FIELD_IV, CryptoUtil.encrypt(bean, bean.get(FieldNames.FIELD_CIPHER_FIELD_IV)));
			}
			
			ret = true;
		} catch (NullPointerException | NoSuchAlgorithmException | InvalidKeyException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e.getMessage());
			logger.error(e);
			
			
		}
		return ret;
	}
	/*
	public void configureECBean(CryptoBean bean) {
		try {
			if(!bean.hasField(FieldNames.FIELD_PUBLIC) || bean.get(FieldNames.FIELD_PUBLIC) == null) {
				bean.set(FieldNames.FIELD_PUBLIC, RecordFactory.newInstance(ModelNames.MODEL_KEY));
			}
			if(!bean.hasField(FieldNames.FIELD_PRIVATE) || bean.get(FieldNames.FIELD_PRIVATE) == null) {
				bean.set(FieldNames.FIELD_PRIVATE, RecordFactory.newInstance(ModelNames.MODEL_KEY));
			}
			if(!bean.hasField(FieldNames.FIELD_CIPHER) || bean.get(FieldNames.FIELD_CIPHER) == null) {
				bean.set(FieldNames.FIELD_CIPHER, RecordFactory.newInstance(ModelNames.MODEL_CIPHER_FIELD_KEY));
			}
	
			bean.set(FieldNames.FIELD_AGREEMENTSPEC, "ECDH");
			bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC, "AES/GCM/NoPadding");
			//bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYMODE, null);
			bean.set(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC, "ECIES");
			bean.set(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC, "ECIES");
			bean.set(FieldNames.FIELD_HASH_PROVIDER, "SHA256withECDSA");
			bean.set(FieldNames.FIELD_CURVE_NAME, "secp256k1");
			bean.set(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT, true);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	*/
	public void configureECBean(CryptoBean bean) {
		try {
			if(!bean.hasField(FieldNames.FIELD_PUBLIC) || bean.get(FieldNames.FIELD_PUBLIC) == null) {
				bean.set(FieldNames.FIELD_PUBLIC, RecordFactory.newInstance(ModelNames.MODEL_KEY));
				bean.set(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC, "ECIES");

			}
			if(!bean.hasField(FieldNames.FIELD_PRIVATE) || bean.get(FieldNames.FIELD_PRIVATE) == null) {
				bean.set(FieldNames.FIELD_PRIVATE, RecordFactory.newInstance(ModelNames.MODEL_KEY));
				bean.set(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC, "ECIES");
			}
			if(!bean.hasField(FieldNames.FIELD_CIPHER) || bean.get(FieldNames.FIELD_CIPHER) == null) {
				bean.set(FieldNames.FIELD_CIPHER, RecordFactory.newInstance(ModelNames.MODEL_CIPHER_KEY));
				//bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC, "AES/GCM/NoPadding");
				
				bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC, "ECIES");
				
				//bean.set(FieldNames.FIELD_CIPHER_FIELD_KEYMODE, "ECIES");
				bean.set(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT, true);
			}
	
			bean.set(FieldNames.FIELD_AGREEMENTSPEC, "ECDH");
			bean.set(FieldNames.FIELD_HASH_FIELD_KEYFUNCTION, "SHA256withECDSA");
			bean.set(FieldNames.FIELD_CURVE_NAME, "secp256k1");
			
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}

	public boolean generateECKeySet(CryptoBean bean) {
		configureECBean(bean);
		return (generateKeyPair(bean) && generateSecretKey(bean));
	}
	
	
	public boolean generateKeyPair(CryptoBean bean){

		boolean ret = false;
		try{
			if(!bean.hasField(FieldNames.FIELD_PUBLIC) || bean.get(FieldNames.FIELD_PUBLIC) == null) {
				bean.set(FieldNames.FIELD_PUBLIC, RecordFactory.newInstance(ModelNames.MODEL_KEY));
			}
			if(!bean.hasField(FieldNames.FIELD_PRIVATE) || bean.get(FieldNames.FIELD_PRIVATE) == null) {
				bean.set(FieldNames.FIELD_PRIVATE, RecordFactory.newInstance(ModelNames.MODEL_KEY));
			}

	        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC));
	        
	        boolean bECD = ((String)bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC)).startsWith("EC");
	        int keySize = bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSIZE);
	        if(bECD) {
	        	ECParameterSpec ecSpec = ECNamedCurveTable.getParameterSpec(bean.get(FieldNames.FIELD_CURVE_NAME));
	        	keyGen.initialize(ecSpec, secureRandom);
	        }
	       
	        else{
	        	keyGen.initialize(keySize, secureRandom);
	        }
        	KeyPair keyPair = keyGen.generateKeyPair();
        	bean.setPublicKey(keyPair.getPublic());
        	bean.setPrivateKey(keyPair.getPrivate());
        	
			/* the public key */
			bean.set(FieldNames.FIELD_PUBLIC_FIELD_KEY, keyPair.getPublic().getEncoded());
			bean.set(FieldNames.FIELD_PRIVATE_FIELD_KEY, keyPair.getPrivate().getEncoded());
        	
        	/*
        	bean.set(FieldNames.FIELD_PUBLIC_FIELD_KEY, this.serialize(bean, false, true, true, false, true).getBytes(StandardCharsets.UTF_8));
        	byte[] privKey = this.serialize(bean, true, false, true, false, true).getBytes(StandardCharsets.UTF_8);
        	// logger.info("Serialize private key spec: " + new String(privKey));
        	bean.set(FieldNames.FIELD_PRIVATE_FIELD_KEY, privKey);
        	*/
			ret = true;
		}
		catch(Exception e){
			logger.error(e);
			
		}
		return ret;
	}

}
