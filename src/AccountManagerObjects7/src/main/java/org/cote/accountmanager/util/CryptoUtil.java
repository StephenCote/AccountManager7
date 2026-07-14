/*******************************************************************************
 * Copyright (C) 2002, 2020 Stephen Cote Enterprises, LLC. All rights reserved.
 * Redistribution without modification is permitted provided the following conditions are met:
 *
 *    1. Redistribution may not deviate from the original distribution,
 *        and must reproduce the above copyright notice, this list of conditions
 *        and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *    2. Products may be derived from this software.
 *    3. Redistributions of any form whatsoever must retain the following acknowledgment:
 *        "This product includes software developed by Stephen Cote Enterprises, LLC"
 *
 * THIS SOFTWARE IS PROVIDED BY STEPHEN COTE ENTERPRISES, LLC ``AS IS''
 * AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THIS PROJECT OR ITS CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY 
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/
package org.cote.accountmanager.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.spec.IESParameterSpec;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.GeneralException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class CryptoUtil {

	public static final Logger logger = LogManager.getLogger(CryptoUtil.class);

	private static int saltLength = 16;
	private static SecureRandom secureRandom = null;
	private static Random random = null;
	public static final boolean USESECURERANDOM = true;
	/// MessageDigest is stateful and not thread-safe: sharing one instance across concurrent Tomcat
	/// request threads (via getMessageDigest(alg, true)) let one thread's reset()/update() interleave
	/// with another's digest(), corrupting the internal buffer and throwing
	/// "ArrayIndexOutOfBoundsException: arraycopy: length -103 is negative" from engineDigest/engineUpdate.
	/// A ThreadLocal per algorithm keeps the "avoid repeated getInstance() lookups" benefit without sharing
	/// mutable state across threads.
	private static Map<String, ThreadLocal<MessageDigest>> digestInstance = new ConcurrentHashMap<>();
	
	private static String defaultHashAlgorithm = "SHA-512";
	private static String defaultPRNG = "SHA1PRNG";
	
	public static byte[] setRandomSalt(BaseRecord bean) {
		byte[] salt = new byte[16];
		try {
			salt = getRandomSalt(bean.get(FieldNames.FIELD_HASH_FIELD_PRNG));
			bean.set(FieldNames.FIELD_HASH_FIELD_SALT, salt);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return salt;
	}
	
	public static byte[] nextRandom(String prng, int length){
		byte[] outByte = new byte[length];
		if(USESECURERANDOM){
			if(secureRandom == null){
				try{
					secureRandom = SecureRandom.getInstance(prng);
				}
				catch(NoSuchAlgorithmException e){
					logger.error(e.getMessage());
				}
			}
			if(secureRandom == null) return new byte[0];
			else secureRandom.nextBytes(outByte);
		}
		else{
			if(random == null){
				random = new Random();
			}
			random.nextBytes(outByte);
		}
		return outByte;
	}
	
	public static byte[] getRandomSalt(){
		return getRandomSalt(defaultPRNG);
	}
	public static byte[] getRandomSalt(String prng){
		return nextRandom(prng, saltLength);
	}

	

	public static int getSaltLength() {
		return saltLength;
	}
	public static void setSaltLength(int saltLength) {
		CryptoUtil.saltLength = saltLength;
	}
	
	
	private static MessageDigest getMessageDigest(){
		return getMessageDigest(defaultHashAlgorithm, false);
	}
	private static MessageDigest getMessageDigest(String hashAlgorithm, boolean useSingleton){
		if(useSingleton){
			ThreadLocal<MessageDigest> tl = digestInstance.computeIfAbsent(hashAlgorithm, alg -> ThreadLocal.withInitial(() -> {
				try{
					return MessageDigest.getInstance(alg);
				}
				catch(NoSuchAlgorithmException e){
					logger.error(GeneralException.TRACE_EXCEPTION,e);
					return null;
				}
			}));
			return tl.get();
		}
		MessageDigest digest = null;
		try{
			digest = MessageDigest.getInstance(hashAlgorithm);
		}
		catch(NoSuchAlgorithmException e){
			logger.error(GeneralException.TRACE_EXCEPTION,e);
		}
		return digest;
	}
	public static String getDigestAsString(String inStr) {
		return getDigestAsString(inStr.getBytes(), new byte[0]);
	}
	public static String getDigestAsString(byte[] inBytes, byte[] salt){
		return new String(BinaryUtil.toBase64(getDigest(defaultHashAlgorithm, inBytes,salt)));
	}

	public static byte[] getDigest(byte[] inBytes, byte[] salt){
		return getDigest(defaultHashAlgorithm, inBytes,salt);
	}
	public static String getDigestAsString(CryptoBean bean, String inStr) {
		return getDigestAsString(bean, inStr.getBytes(StandardCharsets.UTF_8));
	}
	public static String getDigestAsString(CryptoBean bean, byte[] bytes) {
		return new String(BinaryUtil.toBase64(getDigest(bean, bytes)), StandardCharsets.UTF_8);
	}

	public static byte[] getDigest(CryptoBean bean, byte[] inBytes){
		String hashAlgo = defaultHashAlgorithm;
		byte[] salt = new byte[0];
		if(bean.hasField(FieldNames.FIELD_HASH_FIELD_ALGORITHM)) {
			hashAlgo = bean.get(FieldNames.FIELD_HASH_FIELD_ALGORITHM);
		}
		if(bean.hasField(FieldNames.FIELD_HASH_FIELD_SALT)) {
			salt = bean.get(FieldNames.FIELD_HASH_FIELD_SALT);
		}
		logger.info(hashAlgo + " :: " + salt);
		return getDigest(hashAlgo, inBytes, salt);
		
	}
	public static byte[] getDigest(String hashAlgorithm, byte[] inBytes, byte[] salt){
		MessageDigest digest = getMessageDigest(hashAlgorithm, true);
		if(digest == null) {
			logger.error("Null digest");
			return new byte[0];
		}

		digest.reset();
		digest.update(salt);
		return digest.digest(inBytes);
	}

	public static CryptoBean getPasswordBean(String password, byte[] salt){
		CryptoBean bean = new CryptoBean();
		try {
			bean.set(FieldNames.FIELD_CIPHER, IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CIPHER_KEY));
		} catch (FieldException | ValueException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
			
		}
		CryptoFactory.getInstance().setPassKey(bean, password, salt,false);
		return bean;
	}
	public static byte[] encipher(byte[] data, String password, byte[] salt){
		return encipher(getPasswordBean(password, salt),data);
	}
	public static byte[] decipher(byte[] data, String password, byte[] salt){
		return decipher(getPasswordBean(password, salt),data);
	}
	public static byte[] decipher(CryptoBean bean, byte[] data){

		byte[] ret = new byte[0];
		boolean bECD = ((String)bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC)).startsWith("EC");

		Cipher cipher = CryptoFactory.getInstance().getDecryptCipherKey(bean);

		if(cipher == null || ((!bECD && bean.getSecretKey() == null) && (bean.getPrivateKey() == null))) {
			logger.error("Expected keys not present");
			if(cipher == null) logger.error("Null cipher");
			return ret;
		}
		try {
			ret = cipher.doFinal(data);
		}
		catch (IllegalBlockSizeException | BadPaddingException e) {
			logger.error(GeneralException.TRACE_EXCEPTION,e);
			logger.error(e.getMessage());
		}

		return ret;
	}
	public static byte[] encipher(CryptoBean bean, byte[] data){
		byte[] ret = new byte[0];
		boolean bECD = ((String)bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC)).startsWith("EC");
		Cipher cipher = CryptoFactory.getInstance().getEncryptCipherKey(bean);
		if(cipher == null || ((!bECD && bean.getSecretKey() == null) && (bean.getPublicKey() == null))) {
			logger.error("Expected keys not present");
			if(cipher == null) logger.error("Null cipher");
			return ret;
		}
		try {
			ret = cipher.doFinal(data);
		}
		catch (IllegalBlockSizeException | BadPaddingException e) {
			logger.error(e.getMessage());
			logger.error(GeneralException.TRACE_EXCEPTION,e);

		}
		return ret;
	}

	/// Streaming counterpart to decipher(CryptoBean, byte[]) - added to fix StreamUtil.rebox()
	/// buffering an entire boxed stream file into one byte[] to decrypt it (KI-23, the same
	/// buffer-everything OOM shape as KI-17/KI-22, one layer deeper). CipherInputStream is the JDK's own
	/// streaming wrapper around a Cipher - this wraps the SAME already-initialized Cipher
	/// CryptoFactory.getDecryptCipherKey() produces for decipher(), so it is not a new crypto
	/// implementation, just a different (streaming) way of driving the existing one. Caller owns
	/// closing `in`/`out`; closing the returned success path also closes both (CipherInputStream/`in`
	/// via transferTo, `out` via its own lifecycle) - safe to close again.
	public static boolean decipherStream(CryptoBean bean, InputStream in, OutputStream out) {
		boolean bECD = ((String)bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC)).startsWith("EC");
		Cipher cipher = CryptoFactory.getInstance().getDecryptCipherKey(bean);
		if(cipher == null || ((!bECD && bean.getSecretKey() == null) && (bean.getPrivateKey() == null))) {
			logger.error("Expected keys not present");
			if(cipher == null) logger.error("Null cipher");
			return false;
		}
		try(CipherInputStream cis = new CipherInputStream(in, cipher)) {
			cis.transferTo(out);
		}
		catch (IOException e) {
			logger.error(GeneralException.TRACE_EXCEPTION, e);
			logger.error(e.getMessage());
			return false;
		}
		return true;
	}

	/// Streaming counterpart to encipher(CryptoBean, byte[]) - see decipherStream() for rationale.
	/// CipherOutputStream.close() is what triggers the cipher's doFinal() (final block/padding), so the
	/// transfer must complete before this method returns, not merely be flushed.
	public static boolean encipherStream(CryptoBean bean, InputStream in, OutputStream out) {
		boolean bECD = ((String)bean.get(FieldNames.FIELD_CIPHER_FIELD_KEYSPEC)).startsWith("EC");
		Cipher cipher = CryptoFactory.getInstance().getEncryptCipherKey(bean);
		if(cipher == null || ((!bECD && bean.getSecretKey() == null) && (bean.getPublicKey() == null))) {
			logger.error("Expected keys not present");
			if(cipher == null) logger.error("Null cipher");
			return false;
		}
		try(CipherOutputStream cos = new CipherOutputStream(out, cipher)) {
			in.transferTo(cos);
		}
		catch (IOException e) {
			logger.error(GeneralException.TRACE_EXCEPTION, e);
			logger.error(e.getMessage());
			return false;
		}
		return true;
	}
	public static byte[] encrypt(CryptoBean bean, byte[] data){
		PublicKey key = bean.getPublicKey();

		byte[] ret = new byte[0];
		if(key == null || data.length == 0){
			String reason = (key == null ? " Null key" : "Null data");
			logger.error(String.format("Invalid parameter: %s",reason));
			return ret;
		}
		try{
			Cipher cipher = Cipher.getInstance(bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC));
			if(cipher == null){
				logger.error("Null Cipher");
				return ret;
			}

			boolean bECD = ((String)bean.get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC)).startsWith("EC");
			if(bECD) {
				IESParameterSpec params = new IESParameterSpec(null, null, 256, 256, null);
				cipher.init(Cipher.ENCRYPT_MODE, key, params);
			}
			else {
				cipher.init(Cipher.ENCRYPT_MODE, key);
			}
			ret = cipher.doFinal(data);
		}
		catch(Exception e){
			logger.error(e.getMessage());
			logger.error(GeneralException.TRACE_EXCEPTION,e);
		}

		return ret;

	}

	public static byte[] decrypt(CryptoBean bean, byte[] data){
		PrivateKey key = bean.getPrivateKey();
		byte[] ret = new byte[0];
		if(key == null || data.length == 0){
			logger.error("Private key " + (key == null ? "is null" : "exists") + " and data " + (data.length == 0 ? "not provided" : "provided"));
			return ret;
		}
		try{
			Cipher cipher = Cipher.getInstance(bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC));
			boolean bECD = ((String)bean.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC)).startsWith("EC");
			if(bECD) {
				IESParameterSpec params = new IESParameterSpec(null, null, 256, 256, null);
				cipher.init(Cipher.DECRYPT_MODE, key, params);
			}
			else {
				cipher.init(Cipher.DECRYPT_MODE, key);
			}
			ret = cipher.doFinal(data);
		}
		catch(Exception e){
			logger.error(e.getMessage());
			logger.error(GeneralException.TRACE_EXCEPTION,e);
		}
		
		return ret;
	}
	public static boolean verify(PublicKey key, byte[] data, byte[] signature) {
		boolean ver = false;
		try {
	     Signature publicSignature = Signature.getInstance("SHA256withRSA");
	     publicSignature.initVerify(key);
	     publicSignature.update(data);
	     ver = publicSignature.verify(signature);
		
		}
		catch(SignatureException | InvalidKeyException | NoSuchAlgorithmException e) {
			logger.error(e);
		}
		return ver;
	}
	public static byte[] sign(PrivateKey key, byte[] data) {
		byte[] sig = new byte[0];
		try {
			Signature privateSignature = Signature.getInstance("SHA256withRSA");
		    privateSignature.initSign(key);
		    privateSignature.update(data);
		    sig = privateSignature.sign();
		}
		catch(SignatureException | InvalidKeyException | NoSuchAlgorithmException e) {
			logger.error(e);
		}
		return sig;
	}
	



}
