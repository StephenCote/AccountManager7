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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Enumeration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.operator.OperatorCreationException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.KeyStoreBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;


public class KeyStoreUtil {
	
	private KeyStoreUtil(){
		
	}
	
	public static final Logger logger = LogManager.getLogger(KeyStoreUtil.class);

	public static final String KEYSTORE_PROVIDER_JKS = "JKS";
	public static final String KEYSTORE_PROVIDER_P12 = "PKCS12";
	public static final String PREFERRED_KEYSTORE_PROVIDER = KEYSTORE_PROVIDER_P12;

	public static KeyStore getCreateKeyStore(String path, char[] password){
		File check = new File(path);
		if(!check.exists()){
			KeyStore newStore=null;
			try {
				newStore = KeyStore.getInstance(PREFERRED_KEYSTORE_PROVIDER);
				newStore.load(null, password);
				FileOutputStream fos = new FileOutputStream(path);
				newStore.store(fos, password);
				fos.close();
			} catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		return getKeyStore(path,password);
	}
	
	public static boolean saveKeyStore(KeyStore store, String path, char[] password){
		boolean saved = false;
		try{
			FileOutputStream fos = new FileOutputStream(path);
			store.store(fos, password);
			fos.close();
			saved = true;
		}
		 catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return saved;
	}
	
	public static KeyStore getKeyStore(String path, char[] password){
		File check = new File(path);
		if(!check.exists()){
			logger.error("Key store does not exist at " + path);
			return null;
		}
		KeyStore keystore = null;
		FileInputStream is = null;
		try{
			
		  is = new FileInputStream(path);
		  keystore = KeyStore.getInstance(PREFERRED_KEYSTORE_PROVIDER);
		  keystore.load(is, password);
		}
		catch(KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e){
			logger.error(e);
			logger.error("Path:" + path);
			logger.error("Pwd len: " + password.length);
			e.printStackTrace();
		}
		finally{
			try {
				if(is != null) is.close();
			}
			catch (IOException e) {
				logger.error(e);
			}
		}
		return keystore;
	}

	public static byte[] getKeyStoreBytes(KeyStore store, char[] password) {
	   byte[] outByte = new byte[0];
	   ByteArrayOutputStream baos = new ByteArrayOutputStream();
	   try {
		store.store(baos,password);
		outByte = baos.toByteArray();
		} catch (KeyStoreException | NoSuchAlgorithmException | CertificateException | IOException e) {
			logger.error(e);
			e.printStackTrace();
		}

	   return outByte;
	}


	public static KeyStore getKeyStore(byte[] keystoreBytes, char[] password) {
		return getKeyStore(keystoreBytes, password, PREFERRED_KEYSTORE_PROVIDER);
	}
	public static KeyStore getKeyStore(byte[] keystoreBytes, char[] password, String provider) {

	      KeyStore store = null;
	      try {
	    	store =  KeyStore.getInstance(provider);
			store.load(new ByteArrayInputStream(keystoreBytes), password);
		} catch (NoSuchAlgorithmException | CertificateException | IOException | KeyStoreException e) {
			logger.error(e);
			e.printStackTrace();
			store = null;
		} 
	      return store;
  
	}
	public static Certificate decodeCertificate(byte[] certificate){
		Certificate cert = null;
		try{
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			ByteArrayInputStream bais = new ByteArrayInputStream(certificate);
			cert =  cf.generateCertificate(bais);
		}
		catch(CertificateException e){
			logger.error(e);
			e.printStackTrace();
		}
		return cert;
	}
	public static boolean importCertificate(KeyStore store, byte[] certificate, String alias){
		boolean outBool = false;
		logger.info("Import certificate: " + alias);
		try{
			Certificate cert =  decodeCertificate(certificate);
			store.setCertificateEntry(alias, cert);
			outBool = true;
		}
		catch(KeyStoreException e){
			logger.error(e);
			e.printStackTrace();
		}
		return outBool;
	}
	public static boolean importPKCS12(KeyStore store,  byte[] p12cert, String alias, char[] password){
		KeyStore pkstore = getKeyStore(p12cert,password);
		logger.info("Import PKCS12 Store: " + alias);
		if(pkstore == null){
			logger.error("PKCS12 store is null");
			return false;
		}
		boolean outBool = false;
		boolean useAlias = false;
		try{
			Enumeration<String> aliases = pkstore.aliases();
		    while (aliases.hasMoreElements()) {
		      String storeAlias = aliases.nextElement();
		         if (pkstore.isKeyEntry(storeAlias)) {

		            Key key = pkstore.getKey(storeAlias, password);
	
		            Certificate[] chain = pkstore.getCertificateChain(storeAlias);
		            if(!useAlias){
		            	useAlias = true;
		            	storeAlias = alias;
		            }
		            logger.debug("Adding key for alias " + storeAlias);
		            store.setKeyEntry(storeAlias, key, password, chain);

		            outBool = true;
		         }
		    }
		}
		catch(KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException e){
			logger.error(e.getMessage());
			logger.error(e);
			e.printStackTrace();
		}
	    return outBool;

	}


	public static Certificate getCertificate(String path, char[] password, String keyAlias){
			KeyStore store = getKeyStore(path,password);
			if(store == null) return null;
			return getCertificate(store, keyAlias);
	}

	public static Certificate getCertificate(KeyStore store, String keyAlias){
			Certificate cert = null;

		    try {
				cert = store.getCertificate(keyAlias);
			} catch (KeyStoreException e) {
				logger.error(e);
			}

		    return cert;
	}
	public static Key getKey(String path, char[] password, String keyAlias, char[] keyPassword){
		KeyStore store = getKeyStore(path,password);
		return getKey(store, password, keyAlias, keyPassword);
	}

	public static Key getKey(KeyStore store, char[] password, String keyAlias, char[] keyPassword){
		Key key = null;

	    
			try {
				key = store.getKey(keyAlias, (keyPassword == null ? password : keyPassword));

				if(key != null){
					Certificate[] certs = store.getCertificateChain(keyAlias);
					logger.info("Alias " + keyAlias + " chain: " + certs.length);
				}
			} catch (UnrecoverableKeyException | KeyStoreException | NoSuchAlgorithmException e) {
				logger.error(e);
			}
		    return key;
	}
	
	public static KeyStoreBean getCreateStore(BaseRecord owner, String name, KeyStoreBean signer) {
		
		BaseRecord rec = null;
		BaseRecord crypto = null;
		try {
			/// The keyset will be applied to the store after the certificate is generated
			///
			rec = IOSystem.getActiveContext().getRecordUtil().getCreateRecord(owner, ModelNames.MODEL_KEY_STORE, name + " Certificate", "~/keyStore", owner.get(FieldNames.FIELD_ORGANIZATION_ID));

			crypto = IOSystem.getActiveContext().getRecordUtil().getCreateRecord(owner, ModelNames.MODEL_KEY_SET, name + " Keys", "~/keys", owner.get(FieldNames.FIELD_ORGANIZATION_ID));
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		if(rec == null || crypto == null) {
			return null;
		}

		IOSystem.getActiveContext().getRecordUtil().populate(crypto);
		CryptoBean cb = new CryptoBean(crypto);
		
		if(!cb.hasField(FieldNames.FIELD_PUBLIC_FIELD_KEY)) {
			try {
				CryptoFactory.getInstance().generateKeyPair(cb);
				IOSystem.getActiveContext().getRecordUtil().applyOwnership(owner, cb.get(FieldNames.FIELD_PUBLIC), owner.get(FieldNames.FIELD_ORGANIZATION_ID));
				IOSystem.getActiveContext().getRecordUtil().createRecord(cb.get(FieldNames.FIELD_PUBLIC));
				IOSystem.getActiveContext().getRecordUtil().applyOwnership(owner, cb.get(FieldNames.FIELD_PRIVATE), owner.get(FieldNames.FIELD_ORGANIZATION_ID));
				IOSystem.getActiveContext().getRecordUtil().createRecord(cb.get(FieldNames.FIELD_PRIVATE));
				IOSystem.getActiveContext().getRecordUtil().updateRecord(cb);
			}
			catch(ModelNotFoundException | FieldException | ValueException e) {
				logger.error(e);
			}
		}
		if(rec.hasField(FieldNames.FIELD_KEY_SET)) {
			BaseRecord kset = rec.get(FieldNames.FIELD_KEY_SET);
			if(kset != null && !(boolean)kset.get(FieldNames.FIELD_POPULATED)) {
				IOSystem.getActiveContext().getRecordUtil().populate(kset);
			}
		}
		KeyStoreBean kb = new KeyStoreBean(rec);

		if(!kb.hasField(FieldNames.FIELD_STORE) || ((byte[])kb.get(FieldNames.FIELD_STORE)).length == 0) {
			ParameterList plist = ParameterUtil.newParameterList("cn", name);
			try {
				CertificateUtil.generate(kb, cb, signer, plist);
				IOSystem.getActiveContext().getRecordUtil().updateRecord(kb);
			}
			catch(NullPointerException | OperatorCreationException | CertificateException | CertIOException e) {
				logger.error(e);
				
			}
		}
		return kb;
	}


}
