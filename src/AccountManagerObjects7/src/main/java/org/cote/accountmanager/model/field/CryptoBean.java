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
package org.cote.accountmanager.model.field;

import java.security.PrivateKey;
import java.security.PublicKey;

import javax.crypto.SecretKey;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class CryptoBean extends LooseRecord {
	
	@JsonIgnore
	private SecretKey secretKey = null;
	
	@JsonIgnore
	private PublicKey publicKey = null;
	
	@JsonIgnore
	private PrivateKey privateKey = null;

	public CryptoBean() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_KEY_SET, this, null);
			set(ModelNames.MODEL_HASH, RecordFactory.newInstance(ModelNames.MODEL_HASH));
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			/// ignore
		}
	}
	
	public CryptoBean(BaseRecord keySet) {
		this();
		this.setFields(keySet.getFields());
		boolean encryptedCipher = false;
		
		if(hasField(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT)) {
			encryptedCipher = get(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT);
		}
		this.applyKeys(encryptedCipher);
		
		// applyKeys(keySet, false);
	}
	
	public CryptoBean(BaseRecord keySet, boolean encryptedCipher) {
		this();
		this.setFields(keySet.getFields());
		this.applyKeys(encryptedCipher);
	}
	private void applyPublicKey() {
		if(hasField(FieldNames.FIELD_PUBLIC_FIELD_KEY)) {
			

			byte[] keyBytes = get(FieldNames.FIELD_PUBLIC_FIELD_KEY);
			String spec = get(FieldNames.FIELD_PUBLIC_FIELD_KEYSPEC);
			if(spec != null && keyBytes.length > 0) {
				//logger.info("Reconstitute public key");
				//logger.info(this.toString());
				/*
				if(spec.startsWith("EC")) {
					CryptoFactory.getInstance().setECDSAPublicKey(this, keyBytes);
				}
				else {
					CryptoFactory.getInstance().setRSAPublicKey(this, keyBytes);
				}
				*/
				CryptoFactory.getInstance().setPublicKey(this,  keyBytes);
			}
		}		
	}
	private void applyPrivateKey() {
		if(hasField(FieldNames.FIELD_PRIVATE_FIELD_KEY)) {
			// logger.info("Reconstitute private key");
			byte[] keyBytes = get(FieldNames.FIELD_PRIVATE_FIELD_KEY);
			String spec = get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC);
			if(spec != null && keyBytes.length > 0) {
				CryptoFactory.getInstance().setPrivateKey(this,  keyBytes);
				/*
				if(spec.startsWith("EC")) {
					CryptoFactory.getInstance().setECDSAPrivateKey(this, keyBytes);
				}
				else {
					CryptoFactory.getInstance().setRSAPrivateKey(this, keyBytes);
				}
				*/
			}
		}
	}
	private void applyKeys(boolean encryptedCipher) {
		applyPublicKey();
		applyPrivateKey();
		if(hasField(FieldNames.FIELD_CIPHER_FIELD_KEY) && ((byte[])get(FieldNames.FIELD_CIPHER_FIELD_KEY)).length > 0) {
			// logger.info("Reconstitute cipher key");
			byte[] keyBytes = get(FieldNames.FIELD_CIPHER_FIELD_KEY);
			byte[] ivBytes = get(FieldNames.FIELD_CIPHER_FIELD_IV);
			CryptoFactory.getInstance().setSecretKey(this, keyBytes, ivBytes, encryptedCipher);
		}
	}
	/*
	@JsonIgnore
	private void applyKeys(BaseRecord keySet, boolean encryptedCipher) {
		CryptoFactory.getInstance().setMembers(this, keySet, encryptedCipher);
	}
	*/
	
	@JsonIgnore
	public void setPublicKey(BaseRecord rec) {
		try {
			set(FieldNames.FIELD_PUBLIC, rec);
			applyPublicKey();
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}

	@JsonIgnore
	public void setPrivateKey(BaseRecord rec) {
		try {
			set(FieldNames.FIELD_PRIVATE, rec);
			applyPrivateKey();
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}

	
	@JsonIgnore
	public PublicKey getPublicKey() {
		return publicKey;
	}

	@JsonIgnore
	public void setPublicKey(PublicKey publicKey) {
		this.publicKey = publicKey;
	}

	@JsonIgnore
	public PrivateKey getPrivateKey() {
		return privateKey;
	}

	@JsonIgnore
	public void setPrivateKey(PrivateKey privateKey) {
		this.privateKey = privateKey;
	}

	@JsonIgnore
	public SecretKey getSecretKey() {
		return secretKey;
	}

	@JsonIgnore
	public void setSecretKey(SecretKey secretKey) {
		this.secretKey = secretKey;
	}
	
}
