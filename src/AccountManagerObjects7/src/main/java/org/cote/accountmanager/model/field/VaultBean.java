package org.cote.accountmanager.model.field;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.security.VaultService;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class VaultBean extends LooseRecord {
	
	@JsonIgnore
	private CryptoBean vaultKey = null;
	
	@JsonIgnore
	private CryptoBean publicKey = null;
	
	public VaultBean() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_VAULT, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			/// ignore
		}
	}
	
	public VaultBean(BaseRecord vault) {
		this();
		this.setFields(vault.getFields());
	}
	
	@JsonIgnore
	public CryptoBean getVaultKey() {
		if(vaultKey == null) {
			BaseRecord rec = get(FieldNames.FIELD_VAULT_KEY);
			if(rec != null) {
				vaultKey = new CryptoBean(rec);
			}
		}
		return vaultKey;
	}
	
	@JsonIgnore
	public BaseRecord getServiceUser() {
		return get(FieldNames.FIELD_SERVICE_USER);
	}
	
	@JsonIgnore
	public BaseRecord getProtectedCredential() {
		return get(FieldNames.FIELD_PROTECTED_CREDENTIAL);
	}
	
	@JsonIgnore
	public String getNameHash() {
		return get(FieldNames.FIELD_NAME_HASH);
	}
	
	@JsonIgnore
	public String getKeyPrefix() {
		return get(FieldNames.FIELD_KEY_PREFIX);
	}
	
	@JsonIgnore
	public String getKeyProtectedPrefix() {
		return get(FieldNames.FIELD_KEY_PROTECTED_PREFIX);
	}
	
	@JsonIgnore
	public String getKeyExtension() {
		return get(FieldNames.FIELD_KEY_EXTENSION);
	}

	@JsonIgnore
	public String getGroupName() {
		return get(FieldNames.FIELD_GROUP_NAME);
	}
	
	@JsonIgnore
	public boolean isProtected() {
		return get(FieldNames.FIELD_PROTECTED);
	}
	
	@JsonIgnore
	public boolean isProtectedCredential() {
		return get(FieldNames.FIELD_PROTECTED_CREDENTIAL);
	}
	
	@JsonIgnore
	public void setProtected(boolean prot) {
		try {
			set(FieldNames.FIELD_PROTECTED, prot);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	@JsonIgnore
	public boolean isHaveVaultKey() {
		return get(FieldNames.FIELD_HAVE_VAULT_KEY);
	}
	
	@JsonIgnore
	public void setHaveVaultKey(boolean prot) {
		try {
			set(FieldNames.FIELD_HAVE_VAULT_KEY, prot);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	@JsonIgnore
	public boolean isHaveCredential() {
		return get(FieldNames.FIELD_HAVE_CREDENTIAL);
	}
	
	/*
	@JsonIgnore
	public BaseRecord getVaultKey() {
		return get(FieldNames.FIELD_VAULT_KEY);
	}
	*/
	
	@JsonIgnore
	public void setVaultKey(CryptoBean prot) {
		try {
			set(FieldNames.FIELD_VAULT_KEY, prot);
			vaultKey = prot;
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	@JsonIgnore
	public boolean isInitialized() {
		return get(FieldNames.FIELD_INITIALIZED);
	}
	
	@JsonIgnore
	public void setInitialized(boolean prot) {
		try {
			set(FieldNames.FIELD_INITIALIZED, prot);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	@JsonIgnore
	public void setProtectedCredential(BaseRecord cred) {
		try {
			set(FieldNames.FIELD_PROTECTED_CREDENTIAL, cred);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	@JsonIgnore
	public byte[] getVaultPath() {
		return get(FieldNames.FIELD_VAULT_PATH);
	}
	@JsonIgnore
	public void setVaultPath(byte[] path) {
		try {
			set(FieldNames.FIELD_VAULT_PATH, path);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	@JsonIgnore
	public byte[] getKeyPath() {
		return get(FieldNames.FIELD_KEY_PATH);
	}
	@JsonIgnore
	public void setKeyPath(byte[] path) {
		try {
			set(FieldNames.FIELD_KEY_PATH, path);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	@JsonIgnore
	public byte[] getProtectedCredentialPath() {
		return get(FieldNames.FIELD_PROTECTED_CREDENTIAL_PATH);
	}
	@JsonIgnore
	public void setProtectedCredentialPath(byte[] path) {
		try {
			set(FieldNames.FIELD_PROTECTED_CREDENTIAL_PATH, path);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	@JsonIgnore
	public CryptoBean getPublicKey() {
		return VaultService.getInstance().getPublicKey(this);
	}
	
	@JsonIgnore
	public CryptoBean getActiveKey() {
		return get(FieldNames.FIELD_ACTIVE_KEY);
	}
	
	public boolean newActiveKey() {
		return VaultService.getInstance().newActiveKey(this);
	}
	

}
