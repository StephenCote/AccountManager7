
package org.cote.accountmanager.security;



import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.codec.binary.Hex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.JsonReader;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
public class VaultService
{
	
	
	
	public static final Logger logger = LogManager.getLogger(VaultService.class);
	
	private static Map<String,VaultBean> cacheByUrn = Collections.synchronizedMap(new HashMap<>());
	
	/// export a version of the vault that does not include exposed (aka unencrypted) information that should be protected
	///
	public String exportVault(VaultBean vault) throws Exception{
		throw new Exception("Not implemented");
	}
	
	private static VaultService instance = null;
	public static VaultService getInstance() {
		if(instance == null) {
			instance = new VaultService();
		}
		return instance;
	}
	
	public VaultService(){
		
	}
	
	public BaseRecord loadProtectedCredential(String filePath){
		String fileDat = FileUtil.getFileAsString(filePath);
		if(fileDat == null || fileDat.length() == 0){
			logger.error("File not found: " + filePath);
			return null;
		}
		
		JsonReader reader = new JsonReader();
		BaseRecord outCred = null;
		try {
			outCred = reader.read(ModelNames.MODEL_CREDENTIAL, fileDat);
		} catch (ReaderException e) {
			logger.error(e);
			
		}

		//BaseRecord outCred = JSONUtil.importObject(FileUtil.getFileAsString(filePath), LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(outCred == null ){
			logger.error("Credential was not successfully restored");
		}


		return outCred;
	}
	
	/// Create an encrypted credential used to protect the private vault key
	/// This credential is enciphered with a discrete secret key, stored in the database
	///
	public boolean createProtectedCredentialFile(BaseRecord vaultOwner, String filePath, byte[] credential) throws FactoryException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		

		IOSystem.getActiveContext().getRecordUtil().populate(vaultOwner);
		File f = new File(filePath);
		if(f.exists()){
			logger.error("File '" + filePath + "' already exists");
			return false;
		}
		
		/// Note: CredentialService is intentionally NOT USED here because this credential SHOULD NOT be stored in the database
		///
		BaseRecord cred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, vaultOwner, null, null);
		cred.set(FieldNames.FIELD_TYPE, CredentialEnumType.ENCRYPTED_PASSWORD);
		cred.set(FieldNames.FIELD_ENCIPHERED, true);
		
		String keyName = filePath.substring(filePath.lastIndexOf("/") + 1);
		

		BaseRecord crypto = IOSystem.getActiveContext().getRecordUtil().getCreateRecord(vaultOwner, ModelNames.MODEL_KEY_SET, "Vault Key - " + keyName, "~/keys", vaultOwner.get(FieldNames.FIELD_ORGANIZATION_ID));
		IOSystem.getActiveContext().getRecordUtil().populate(crypto);
		CryptoBean cb = new CryptoBean(crypto);
		if(cb.getSecretKey() == null) {
			CryptoFactory.getInstance().generateSecretKey(cb);
			IOSystem.getActiveContext().getRecordUtil().updateRecord(cb);
		}
		
		cred.set(FieldNames.FIELD_CREDENTIAL, CryptoUtil.encipher(cb, credential));
		cred.set(FieldNames.FIELD_KEY_ID, cb.get(FieldNames.FIELD_OBJECT_ID));
		
		
		if(!FileUtil.emitFile(filePath, cred.toString())){
			logger.error("Failed to create credential file at '" + filePath + "'");
			return false;
		}

		/// Test decipher
		BaseRecord icred = JSONUtil.importObject(FileUtil.getFileAsString(filePath), LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		// Invoke a memory read to activate providers
		
		new MemoryReader().read(icred);
		if(icred == null){
			logger.error("Failed check to read in credential file");
			return false;
		}

		byte[] icredb = getProtectedCredentialValue(icred);
		if(icredb.length == 0) {
			logger.error("Failed to decipher credential");
			return false;	
		}
		
		if(Arrays.equals(icredb,credential) == false){
			logger.error("Restored credential does not match the submitted credential.");
			return false;
		}

		return true;
	}
	
	private byte[] getProtectedCredentialValue(BaseRecord credential){
		OrganizationContext org = IOSystem.getActiveContext().getOrganizationContext(credential.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if(CredentialEnumType.ENCRYPTED_PASSWORD.toString().equals(credential.get(FieldNames.FIELD_TYPE))){
			CryptoBean crypto = new CryptoBean(IOSystem.getActiveContext().getRecordUtil().getRecordByObjectId(org.getAdminUser(), ModelNames.MODEL_KEY_SET, credential.get(FieldNames.FIELD_KEY_ID)));
			if(crypto.getSecretKey() == null){
				return new byte[0];
			}
			return CryptoUtil.decipher(crypto, credential.get(FieldNames.FIELD_CREDENTIAL));
		}
		return credential.get(FieldNames.FIELD_CREDENTIAL);
	}
	private CryptoBean getPrimarySymmetricKey(VaultBean bean) {
		OrganizationContext org = IOSystem.getActiveContext().getOrganizationContext(bean.getServiceUser().get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if(org != null) {
			return org.getOrganizationCipher();
		}
		return null;
	}
	private void setVaultPath(VaultBean vault, String path){

		CryptoBean orgSKey = getPrimarySymmetricKey(vault);
		vault.setVaultPath(CryptoUtil.encipher(orgSKey, path.getBytes()));
	}
	
	private String getVaultPath(VaultBean vault){
		CryptoBean orgSKey = getPrimarySymmetricKey(vault);
		return new String(CryptoUtil.decipher(orgSKey, vault.getVaultPath()));
	}
	
	private void setKeyPath(VaultBean vault){
		CryptoBean orgSKey = getPrimarySymmetricKey(vault);
		String path = getVaultPath(vault) + File.separator + vault.getNameHash() + "-" + vault.getKeyPrefix() + (vault.isProtected() ? vault.getKeyProtectedPrefix() : "") + vault.getKeyExtension();
		// logger.info("Setting key path: " + path);
		vault.setKeyPath(CryptoUtil.encipher(orgSKey, path.getBytes()));
	}
	
	private String getKeyPath(VaultBean vault){
		if(vault.getServiceUser() == null){
			logger.error("Vault is not properly initialized");
			return null;
		}
		CryptoBean orgSKey = getPrimarySymmetricKey(vault);
		return new String(CryptoUtil.decipher(orgSKey, vault.getKeyPath()));

	}
	
	public void setProtectedCredentialPath(VaultBean vault, String path){
		CryptoBean orgSKey = getPrimarySymmetricKey(vault);
		vault.setProtectedCredentialPath(CryptoUtil.encipher(orgSKey, path.getBytes()));
	}
	
	private String getProtectedCredentialPath(VaultBean vault){
		CryptoBean orgSKey = getPrimarySymmetricKey(vault);
		return new String(CryptoUtil.decipher(orgSKey, vault.getProtectedCredentialPath()));

	}
	
	private void setProtected(VaultBean vault, BaseRecord credential){
		boolean prot = (credential != null);
		vault.setProtected(prot);
		vault.setProtectedCredential(credential);
	}
	
	public VaultBean getCreateVault(BaseRecord vaultUser, String vaultName, long organizationId) {
		VaultBean vault = null;
		try {
			vault = getVault(vaultUser, vaultName);
			
			if(vault == null) {
				String credPath = IOFactory.DEFAULT_FILE_BASE + "/.vault/" + organizationId + "/credential/" + vaultName + ".json";
				String vaultPath = IOFactory.DEFAULT_FILE_BASE + "/.vault/" + organizationId + "/vault";
				if(!createProtectedCredentialFile(vaultUser, credPath, UUID.randomUUID().toString().getBytes())) {
					logger.error("Failed to create protected credential");
					return null;
				}
				BaseRecord cred = loadProtectedCredential(credPath);
				if(cred == null) {
					logger.error("Failed to restore protected credential");
					return null;
				}
				VaultBean nvault = newVault(vaultUser, vaultPath, vaultName);
				setProtectedCredentialPath(nvault, credPath);
				if(!createVault(nvault, cred)) {
					logger.error("Failed to create new vault");
					return null;
				}
				vault = getVault(vaultUser, vaultName);
			}
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException | IndexException | ReaderException e) {
			logger.error(e);
			
		}
		return vault;
	}
	
	public VaultBean newVault(BaseRecord serviceUser, String vaultBasePath, String vaultName){
		VaultBean vault = null;
		try {
			vault = new VaultBean(IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_VAULT));
			vault.set(FieldNames.FIELD_SERVICE_USER, serviceUser);
	
			vault.set(FieldNames.FIELD_ORGANIZATION_ID, serviceUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			setVaultPath(vault,vaultBasePath);
			vault.set(FieldNames.FIELD_NAME, vaultName);
	
			vault.set(FieldNames.FIELD_ALIAS, vaultName.replaceAll("\\s", "").toLowerCase());
			vault.set(FieldNames.FIELD_NAME_HASH, Hex.encodeHexString(CryptoUtil.getDigest(vaultName.getBytes(),new byte[0])));
			setKeyPath(vault);
		}
		catch(ClassCastException | NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
			vault = null;
		}
		return vault;
	}
	
	public boolean createVault(VaultBean vault, BaseRecord credential){
		try{
			if ((boolean)vault.get(FieldNames.FIELD_HAVE_VAULT_KEY) == true){
				logger.info("Vault key already exists for " + vault.get(FieldNames.FIELD_NAME));
				return true;
			}
			
			setProtected(vault, credential);
			setKeyPath(vault);
			String vaultKeyPath = getKeyPath(vault);
			File vaultFile = new File(vaultKeyPath);
			if (vaultFile.exists()){
				logger.error("Vault Key Path already exists: " + vaultKeyPath);
				return false;
			}
			long orgId = vault.getServiceUser().get(FieldNames.FIELD_ORGANIZATION_ID);
			/*
			BaseRecord vdat = IOSystem.getActiveContext().getFactory().getCreateDirectoryModel(vault.getServiceUser(), ModelNames.MODEL_VAULT, vault.get(FieldNames.FIELD_NAME), "~/" + vault.getGroupName(), orgId);
	
			if (vdat.get(FieldNames.FIELD_CREDENTIAL) != null)
			{
				logger.error("Vault for '" + vault.get(FieldNames.FIELD_NAME) + "' could not be made.  Existing vault must first be unimproved.");
				return false;
			}
			*/
			String ipath = "~/" + vault.getGroupName() + "/" + vault.get(FieldNames.FIELD_NAME);
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(vault.getServiceUser(), ModelNames.MODEL_GROUP, ipath, "DATA", orgId);
			IOSystem.getActiveContext().getRecordUtil().populate(dir);
			
			ParameterList kslist = ParameterUtil.newParameterList("path", ipath);
			kslist.parameter("salt", true);
			BaseRecord saltSet = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_KEY_SET, vault.getServiceUser(), null, kslist);
			saltSet.set(FieldNames.FIELD_NAME, "Salt");
			IOSystem.getActiveContext().getRecordUtil().createRecord(saltSet);
			
			CryptoBean sm = new CryptoBean();
			sm.set(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT, true);
			CryptoFactory.getInstance().generateKeyPair(sm);
			CryptoFactory.getInstance().generateSecretKey(sm);
			//IOSystem.getActiveContext().getRecordUtil().createRecord(sm);
			
			byte[] privateKeyConfig = CryptoFactory.getInstance().serialize(sm, true, false, false, false, true).getBytes(StandardCharsets.UTF_8);
	
			String inPassword = null;
			if(vault.getProtectedCredential() != null && (CredentialEnumType.ENCRYPTED_PASSWORD.toString().equals(vault.getProtectedCredential().get(FieldNames.FIELD_TYPE)) || CredentialEnumType.HASHED_PASSWORD.toString().equals(vault.getProtectedCredential().get(FieldNames.FIELD_TYPE)))){
				inPassword = new String(getProtectedCredentialValue(vault.getProtectedCredential()));
			}

			// If a password was specified, encrypt with password
			//
			if (vault.isProtected() && inPassword != null && inPassword.length() > 0)
			{
				privateKeyConfig = CryptoUtil.encipher(privateKeyConfig, inPassword, saltSet.get(FieldNames.FIELD_HASH_FIELD_SALT)); 
			}
	
			// Encipher the private key with the org key
			//
			CryptoBean orgSKey = getPrimarySymmetricKey(vault); 
			byte[] encPrivateKey = CryptoUtil.encipher(orgSKey, privateKeyConfig);
			
			/// Set aside the public key in the vault key directory 
			/// The enciphered private key and passphrase are stored outside of the system, while the cipher key is stored inside the system 
			BaseRecord publicKeyCfg = CryptoFactory.getInstance().export(sm, false, true, false, false, true);
			BaseRecord publicKey = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_KEY_SET, vault.getServiceUser(), publicKeyCfg, null);
			publicKey.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			publicKey.set(FieldNames.FIELD_NAME, "Public Key");
			IOSystem.getActiveContext().getRecordUtil().createRecord(publicKey);
			
			//BaseRecord pdat = IOSystem.getActiveContext().getFactory().getCreateDirectoryModel(vault.getServiceUser(), ModelNames.MODEL_VAULT, "Public Vault", "~/" + vault.getGroupName() + "/" + vault.get(FieldNames.FIELD_NAME), orgId);
			BaseRecord pdat =  IOSystem.getActiveContext().getFactory().getCreateDirectoryModel(vault.getServiceUser(), ModelNames.MODEL_VAULT, vault.get(FieldNames.FIELD_NAME), "~/" + vault.getGroupName(), orgId);
			pdat.set(FieldNames.FIELD_HAVE_VAULT_KEY, true);
			pdat.set(FieldNames.FIELD_PROTECTED_CREDENTIAL_PATH, vault.getProtectedCredentialPath());
			pdat.set(FieldNames.FIELD_PUBLIC, publicKey);
			pdat.set(FieldNames.FIELD_PROTECTED, true);
			pdat.set(FieldNames.FIELD_HAVE_CREDENTIAL, true);
			pdat.set(FieldNames.FIELD_SALT, saltSet);
			pdat.set(FieldNames.FIELD_SERVICE_USER, vault.getServiceUser());
			pdat.set(FieldNames.FIELD_VAULT_PATH, vault.get(FieldNames.FIELD_VAULT_PATH));
			pdat.set(FieldNames.FIELD_PROTECTED_CREDENTIAL_PATH, vault.get(FieldNames.FIELD_PROTECTED_CREDENTIAL_PATH));
			IOSystem.getActiveContext().getRecordUtil().createRecord(pdat);
			
			vault.set(FieldNames.FIELD_VAULT_LINK, pdat.get(FieldNames.FIELD_OBJECT_ID));
			vault.set(FieldNames.FIELD_HAVE_VAULT_KEY, true);
			
			BaseRecord cred = RecordFactory.newInstance(ModelNames.MODEL_CREDENTIAL);
			cred.set(FieldNames.FIELD_TYPE, CredentialEnumType.KEY);
			cred.set(FieldNames.FIELD_ENCIPHERED, true);
			cred.set(FieldNames.FIELD_CREDENTIAL, encPrivateKey);
			vault.set(FieldNames.FIELD_CREDENTIAL, cred);
			vault.set(FieldNames.FIELD_HAVE_VAULT_KEY, true);
			vault.set(FieldNames.FIELD_HAVE_CREDENTIAL, true);
			
			FileUtil.emitFile(vaultKeyPath, JSONUtil.exportObject(vault, RecordSerializerConfig.getForeignFilteredModule()));

		}
		catch(NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException e){
			logger.error(e);
			
		}
		return true;
	}
	
	
	public VaultBean getVault(BaseRecord user, String name) throws IndexException, ReaderException{
		VaultBean dvault = new VaultBean();
		
		BaseRecord group = IOSystem.getActiveContext().getSearch().findByPath(user, ModelNames.MODEL_GROUP, "~/" + dvault.get(FieldNames.FIELD_GROUP_NAME), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(group == null) {
			logger.warn("Vault group does not exist");
			return null;
		}
		//IOSystem.getActiveContext().getSearch().findByPath(vault.getServiceUser(), ModelNames.MODEL_GROUP, "~/" + dvault.get(FieldNames.FIELD_GROUP_NAME), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_VAULT, group.get(FieldNames.FIELD_ID), name);
		VaultBean pvault = null;
		if(recs.length == 0) {
			logger.error("Failed to find vault " + name);
			return null;
		}
		pvault = new VaultBean(recs[0]);
		IOSystem.getActiveContext().getRecordUtil().populate(pvault.getServiceUser());
		//logger.info(JSONUtil.exportObject(pvault, RecordSerializerConfig.getUnfilteredModule()));
		if(!initialize(pvault, null)) {
			logger.error("Failed to initialize public vault");
		}

		String vaultPath = getVaultPath(pvault);
		String credPath = getProtectedCredentialPath(pvault);

		BaseRecord cred = loadProtectedCredential(credPath);

		VaultBean vault = loadVault(vaultPath, pvault.get(FieldNames.FIELD_NAME), pvault.isProtected());
		if(vault == null){
			logger.error("Failed to restore vault " + vaultPath);
			return null;
		}
		if(!initialize(vault, cred)) {
			logger.error("Failed to initialize vault " + vaultPath);
			return null;
		}
		//cacheByUrn.put(data.getUrn(), vault);
		return vault;
	}
	
	public VaultBean loadVault(String vaultBasePath, String vaultName, boolean isProtected){
		VaultBean chkV = new VaultBean();
		String path = vaultBasePath + File.separator + Hex.encodeHexString(CryptoUtil.getDigest(vaultName.getBytes(),new byte[0])) + "-" + chkV.getKeyPrefix() + (isProtected ? chkV.getKeyProtectedPrefix() : "") + chkV.getKeyExtension();
		File f = new File(path);
		if(!f.exists()){
			logger.warn("Vault file is not accessible: '" + path + "'");
			return null;
		}
		String content = FileUtil.getFileAsString(f);
		return new VaultBean(JSONUtil.importObject(content, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()));

	}
	
	/// When loading a vault via urn, this method will be invoked twice: Once for the meta data reference in the database which is used to find the vault, and Two for the vault itself
	/// Therefore, log statements are dialed down to debug, otherwise it looks like the call to initialize twice is in error
	///
	public boolean initialize(VaultBean vault, BaseRecord credential) {
		boolean init = false;
		try {
			if(vault.getServiceUser() == null){
				throw new FieldException("Unable to locate service user");
			}
			if(vault.getVaultPath().length == 0) {
				throw new FieldException("Invalid base path");
			}
	
			String vaultPath = getVaultPath(vault);
			logger.debug("Initializing Vault '" + vault.get(FieldNames.FIELD_NAME) + "' In " + vaultPath);
			if (FileUtil.makePath(vaultPath) == false)
			{
				throw new FieldException("Unable to create path to " + vaultPath);
			}
	
			IOSystem.getActiveContext().getRecordUtil().populate(vault.getServiceUser());
	
			// Check for non-password protected file
			//
			File vaultKeyFile = new File(getKeyPath(vault));
			if (vaultKeyFile.exists())
			{
				vault.setHaveVaultKey(true);
				setProtected(vault, credential);
			}
			init = true;
			vault.setInitialized(true);
		}
		catch(FieldException e) {
			logger.error(e);
			
		}
		return init;
	
	}
	
	private BaseRecord getVaultGroup(VaultBean vault) throws IndexException, ReaderException {
		return IOSystem.getActiveContext().getSearch().findByPath(vault.getServiceUser(), ModelNames.MODEL_GROUP, "~/" + vault.get(FieldNames.FIELD_GROUP_NAME), vault.get(FieldNames.FIELD_ORGANIZATION_ID));
		//return BaseService.readByNameInParent(audit, AuditEnumType.GROUP, vault.getServiceUser(), vault.getServiceUser().getHomeDirectory(), vault.getVaultGroupName(), "DATA");
	}
	private BaseRecord getVaultInstanceGroup(VaultBean vault) throws IndexException, ReaderException {
		BaseRecord grp = getVaultGroup(vault);
		if(grp == null) {
			logger.error("Failed to find vault group");
			return null;
		}
		BaseRecord ogrp = null;
		BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findByNameInParent(ModelNames.MODEL_GROUP, grp.get(FieldNames.FIELD_ID), vault.get(FieldNames.FIELD_NAME));
		if(recs.length > 0) {
			ogrp = recs[0];
		}
		else {
			logger.error("Failed to find vault group " + vault.get(FieldNames.FIELD_NAME) + " in group " + grp.get(FieldNames.FIELD_NAME));
		}
		return ogrp;
	}
	
	private BaseRecord getSalt(VaultBean vault) throws IndexException, ReaderException{
		BaseRecord dir = getVaultInstanceGroup(vault);
		if(dir == null) {
			logger.error("Null vault instance group");
			return null;
		}
		BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_KEY_SET, dir.get(FieldNames.FIELD_ID), "Salt");
		BaseRecord kset = null;
		if(recs.length > 0) {
			kset = recs[0];
		}
		return kset;
	}
	
	public CryptoBean getVaultKey(VaultBean vault)
	{
		try {
			if(vault.isInitialized() == false){
				throw new FieldException("Vault was not initialized");
			}
			if (!vault.isHaveVaultKey() || (vault.isProtected() && !vault.isHaveCredential())){
				if(vault.isProtected()) {
					logger.error("Vault password was not specified");
				}
				else if(!vault.isHaveVaultKey()) {
					logger.error("Vault configuration does not indicate a key is defined.");
				}
				return null;
			}
			if (vault.getVaultKey() == null)
			{

				BaseRecord cred = vault.get(FieldNames.FIELD_CREDENTIAL);
				byte[] keyBytes = new byte[0];
				if(cred != null) {
					keyBytes = cred.get(FieldNames.FIELD_CREDENTIAL);
					if (keyBytes.length == 0){
						logger.error("Vault key credential is null");
						return null;
					}
				}
				CryptoBean orgSKey = getPrimarySymmetricKey(vault); 
				byte[] decConfig = CryptoUtil.decipher(orgSKey, keyBytes);
				BaseRecord credSalt = getSalt(vault);
				if(credSalt == null){
					logger.info("Salt is null");
					return null;
				}
				if (vault.isProtected()){
					decConfig = CryptoUtil.decipher(decConfig, new String(getProtectedCredentialValue(vault.getProtectedCredential())), credSalt.get(FieldNames.FIELD_HASH_FIELD_SALT));
				}
				if (decConfig.length == 0) return null;
				CryptoBean crypto = CryptoFactory.getInstance().createCryptoBean(decConfig, true);
				vault.setVaultKey(crypto);

			}
		}
		catch(FieldException | IndexException | ReaderException e) {
			logger.error(e);
			
		}
			return vault.getVaultKey();
	}

	public CryptoBean getPublicKey(VaultBean vault) {
		CryptoBean crypto = null;
		try {
			//BaseRecord grp = getVaultInstanceGroup(vault);
			BaseRecord grp = getVaultGroup(vault);
			BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findByNameInGroup(ModelNames.MODEL_VAULT, grp.get(FieldNames.FIELD_ID), vault.get(FieldNames.FIELD_NAME));
			if(recs.length > 0) {
				BaseRecord key = recs[0].get(FieldNames.FIELD_PUBLIC);
				IOSystem.getActiveContext().getRecordUtil().populate(key);
				crypto = new CryptoBean(key, true);
			}
			else {
				logger.error("Public key " + vault.get(FieldNames.FIELD_NAME) + " was not found in group " + grp.get(FieldNames.FIELD_ID));
			}
		}
		catch(ReaderException | IndexException e) {
			logger.error(e);
			
		}
		return crypto;
	}
	
	/// Creates a new symmetric key within the vault group/data structure 
	/// This is currently using the older key storage style than the symmetrickeys table because (a) the keys don't hold any relationship reference other than vault ids, and (b) that makes it harder to determine if the key is still used without scanning every other table that includes a cipherkey reference
	/// By keeping it in the vault meta data (where it's effectively the same level of protection (or lack of), the keys can more easily be cleaned up by simply deleting the vault
	/// This could be refactored by defining groups of symmetric keys vs. groups of data items containing the keys and associating that group relative to the vault group
	///
	public boolean newActiveKey(VaultBean vault)  {
		
		
		CryptoBean pubKey = getPublicKey(vault);
		if(pubKey == null) {
			logger.error("Public key could not be found");
			return false;
		}
		try {
			IOSystem.getActiveContext().getRecordUtil().populate(vault.getServiceUser());
			CryptoBean key = new CryptoBean(IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_KEY_SET, vault.getServiceUser(), null, null));
			key.setPublicKey((BaseRecord)pubKey.get(FieldNames.FIELD_PUBLIC));
			key.set(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT, true);
			if(!CryptoFactory.getInstance().generateSecretKey(key)) {
				logger.error(key.toString());
				return false;
			}
	
			BaseRecord expKey = CryptoFactory.getInstance().export(key, false, false, true, false, true);
			BaseRecord impDir = getVaultInstanceGroup(vault);
			if(impDir == null) {
				logger.error("Invalid vault directory");
				return false;
			}
			expKey.set(FieldNames.FIELD_ORGANIZATION_ID, impDir.get(FieldNames.FIELD_ORGANIZATION_ID));
			expKey.set(FieldNames.FIELD_GROUP_ID, impDir.get(FieldNames.FIELD_ID));
			expKey.set(FieldNames.FIELD_NAME, "Key - " + UUID.randomUUID().toString());
			IOSystem.getActiveContext().getRecordUtil().createRecord(expKey);
			// vault.set(FieldNames.FIELD_ACTIVE_KEY_ID, expKey.get(FieldNames.FIELD_ID));
			vault.set(FieldNames.FIELD_ACTIVE_KEY, expKey);
		}
		catch(ClassCastException | NullPointerException | ReaderException | FieldException | ValueException | ModelNotFoundException | IndexException | FactoryException e) {
			logger.error(e);
			
		}
		return true;
	}
	
	/*
	
	public boolean changeVaultPassword(VaultBean vault, BaseRecord currentCred, BaseRecord newCred) throws ArgumentException
	{

		if(vault.isProtected().booleanValue() && currentCred == null) throw new ArgumentException("Credential required to decipher vault key");
		
		SecurityBean orgSKey = getPrimarySymmetricKey(vault);
		
		byte[] decConfig = CryptoUtil.decipher(orgSKey,  vault.getCredential().getCredential());
		BaseRecord credSalt = getSalt(vault);
		if(credSalt == null){
			logger.error("Salt is null");
			return false;
		}
		if(vault.isProtected()) decConfig = CryptoUtil.decipher(decConfig, new String(isProtectedCredentialValue(currentCred)),credSalt.getSalt());
		if (decConfig.length == 0) throw new ArgumentException("Failed to decipher config");

		if(newCred != null){
			decConfig = CryptoUtil.encipher(decConfig, new String(isProtectedCredentialValue(newCred)),credSalt.getSalt());
		}

		// Encipher with product key
		//
		byte[] encPrivateKey = CryptoUtil.encipher(orgSKey, decConfig);
		vault.getCredential().setCredential(encPrivateKey);
		logger.info("Saving vault to '" + vault.getKeyPath() + "'");
		FileUtil.emitFile(new String(CryptoUtil.decipher(orgSKey,vault.getKeyPath())), exportVault(vault));

		vault.setVaultKey(null);
		setProtected(vault, newCred);

		
		if (getVaultKey(vault) == null){
			logger.error("Failed to restore key with reset password");
			return false;
		}
		return true;
	}
	

	

	
	public boolean deleteVault(VaultType vault) throws ArgumentException, FactoryException
	{
		AuditType audit = beginAudit(vault,ActionEnumType.DELETE, "Delete vault",true);

		logger.info("Cleaning up vault instance");
		if (!vault.getHaveVaultKey().booleanValue()){
			logger.warn("No key detected, so nothing is deleted");
		}
		if (vault.getKeyPath() == null){
			logger.warn("Path is null");
		}
		else{
			File vaultKeyFile = new File(getKeyPath(vault));
			if(vaultKeyFile.exists()){
				if(!vaultKeyFile.delete()) logger.error("Unable to delete vault key file " + vault.getKeyPath());
			}
			else{
				logger.warn("Vault file " + vault.getKeyPath() + " does not exist");
			}
		}

		DirectoryGroupType localImpDir = getVaultInstanceGroup(vault);
		logger.info("Removing implementation group: " + (localImpDir == null ? "[null]" : localImpDir.getUrn()));
		if (localImpDir != null && !((GroupFactory)Factories.getFactory(FactoryEnumType.GROUP)).deleteDirectoryGroup(localImpDir))
		{
			logger.warn("Unable to delete keys from vault directory");
		}
		DirectoryGroupType vaultGroup = getVaultGroup(vault);
		if(vaultGroup != null){
			DataType impData = ((DataFactory)Factories.getFactory(FactoryEnumType.DATA)).getDataByName(vault.getVaultName(), true,vaultGroup);
			logger.info("Removing implementation data: " + (impData == null ? "[null]" : impData.getUrn()));
			if(impData != null && !((DataFactory)Factories.getFactory(FactoryEnumType.DATA)).delete(impData)){
				logger.warn("Unable to delete improvement key");
			}
			else if(impData == null){
				logger.warn("Implementation data '" + vault.getVaultName() + "' in group " + vaultGroup.getUrn() + " could not be removed");
			}
		}
		else{
			logger.warn("Vault group is null");
		}
		cacheByUrn.remove(vault.getVaultDataUrn());
		vault.setVaultDataUrn(null);
		vault.setKeyPath(null);
		vault.setHaveCredential(false);
		vault.setProtectedCredential(null);
		vault.setActiveKey(null);
		vault.setVaultKey(null);
		vault.setCredential(null);
		
		AuditService.permitResult(audit, "TODO: Add authZ check");
		return true;
	}
	private DataType getVaultMetaData(VaultBean vault) throws FactoryException, ArgumentException{
		AuditType audit = beginAudit(vault,ActionEnumType.READ, "getVaultMetaData",true);
		return BaseService.readByName(audit, AuditEnumType.DATA, vault.getServiceUser(),  getVaultGroup(vault), vault.getVaultName());
	}
	


	
	
	
	
	/// NOTE:
	///		The Volatile Key includes the deciphered/exposed private key from the vaultKey
	///		The private key should be immediately null'd after decrypting the secret key
	///		
	private SecurityBean getVolatileVaultKey(VaultBean vault) throws ArgumentException
	{
		if (!vault.getHaveVaultKey().booleanValue() || getVaultKey(vault) == null){
			logger.error("Vault is not initialized correctly.  The vault key is not present.");
			return null;
		}

		SecurityBean outBean = new SecurityBean();
		
		SecurityBean inBean = vault.getVaultKeyBean();
		
		
		outBean.setEncryptCipherKey(inBean.getEncryptCipherKey());
		outBean.setCipherIV(inBean.getCipherIV());
		outBean.setCipherKey(inBean.getCipherKey());
		outBean.setPrivateKeyBytes(inBean.getPrivateKeyBytes());
		outBean.setPrivateKey(inBean.getPrivateKey());
		outBean.setPublicKeyBytes(inBean.getPublicKeyBytes());
		outBean.setPublicKey(inBean.getPublicKey());
		outBean.setSecretKey(inBean.getSecretKey());

		return outBean;
	
	}
	
	private SecurityBean getCipherFromData(VaultBean vault, DataType data) throws DataException, ArgumentException{
		// Get a mutable security manager to swap out the keys
		// The Volatile Key includes the exposed private key, so it's immediately wiped from the object after decrypting the cipher key
		//
		SecurityBean vSm = getVolatileVaultKey(vault);
		if (vSm == null){
			logger.error("Volatile key copy is null");
			return null;
		}
		byte[] dataBytes = DataUtil.getValue(data);
		if(dataBytes.length == 0){
			logger.error("Key data was empty");
			return null;
		}
		SecurityFactory.getSecurityFactory().importSecurityBean(vSm, dataBytes, true);
		vSm.setPrivateKey(null);
		vSm.setPrivateKeyBytes(new byte[0]);
		return vSm;
	}
	
	private SecurityBean getVaultCipher(VaultBean vault, String keyId) throws FactoryException, ArgumentException, DataException{
		if(vault.getSymmetricKeyMap().containsKey(keyId)){
			return vault.getSymmetricKeyMap().get(keyId);
		}

		// Get the encrypted keys for this data object.
		//
		DataType key = ((DataFactory)Factories.getFactory(FactoryEnumType.DATA)).getDataByName(keyId, getVaultInstanceGroup(vault));
		if (key == null){
			logger.error("Vault key " + keyId + " does not exist");
			return null;
		}

		SecurityBean vSm = getCipherFromData(vault, key);
		if(vSm == null){
			logger.error("Failed to restore cipher from data");
			return null;
		}
		vault.getSymmetricKeyMap().put(keyId, vSm);
		return vSm;
	}
	
	public static boolean canVault(NameIdType obj) throws FactoryException {
		INameIdFactory fact = Factories.getFactory(FactoryEnumType.valueOf(obj.getNameType().toString()));
		return fact.isVaulted();
	}
	public String[] extractVaultAttributeValues(VaultBean vault, AttributeType attr) throws UnsupportedEncodingException, ArgumentException, FactoryException, DataException {
		String[] outVals = new String[0];
		AttributeFactory af = Factories.getAttributeFactory();
		if(vault == null){
			logger.error("Vault reference is null");
			return outVals;
		}
		if (!vault.getHaveVaultKey()){
			logger.warn("Vault key is not specified");
			return outVals;
		}
		if (!vault.getVaultDataUrn().equals(attr.getVaultId())){
			logger.error("Attribute vault id '" + attr.getVaultId() + "' does not match the specified vault name '" + vault.getVaultDataUrn() + "'.  This is a precautionary/secondary check, probably due to changing the persisted vault configuration name");
			return outVals;
		}
		return af.getEncipheredValues(attr, getVaultCipher(vault,attr.getKeyId()));
	}
	public void setVaultAttributeValues(VaultBean vault, AttributeType attr) throws DataException, FactoryException, UnsupportedEncodingException, ArgumentException
	{
		if(attr.getVaulted().booleanValue()) {
			logger.warn("Vaulting existing attribute values runs the risk of accidentally enciphering multiple times");
		}
		setVaultAttributeValues(vault,attr,attr.getValues().toArray(new String[0]));
	}
	public void setVaultAttributeValues(VaultBean vault, AttributeType attr, String[] values) throws DataException, FactoryException, UnsupportedEncodingException, ArgumentException
	{
		if (vault.getActiveKey() == null || vault.getActiveKeyId() == null){
			if(!newActiveKey(vault)){
				throw new FactoryException("Failed to establish active key");
			}
			if (vault.getActiveKey() == null)
			{
				throw new FactoryException("Active key is null");
			}
		}
		
		if(attr.getEnciphered().booleanValue()) throw new ArgumentException("Cannot vault an enciphered attribute");
		AttributeFactory af = Factories.getAttributeFactory();
		attr.setVaulted(true);
		attr.setKeyId(vault.getActiveKeyId());
		attr.setVaultId(vault.getVaultDataUrn());
		attr.getValues().clear();
		af.setEncipheredAttributeValues(attr, vault.getActiveKeyBean(), values);
	}
	public void setVaultBytes(VaultBean vault, NameIdType obj, byte[] inData) throws DataException, FactoryException, UnsupportedEncodingException, ArgumentException
	{
		if (vault.getActiveKey() == null || vault.getActiveKeyId() == null){
			if(!newActiveKey(vault)){
				throw new FactoryException("Failed to establish active key");
			}
			if (vault.getActiveKey() == null)
			{
				throw new FactoryException("Active key is null");
			}
		}
		INameIdFactory fact = Factories.getFactory(FactoryEnumType.valueOf(obj.getNameType().toString()));
		if(fact.isVaulted() == false) throw new ArgumentException("Object factory does not support vaulted protection");
		
		obj.setKeyId(vault.getActiveKeyId());
		obj.setVaultId(vault.getVaultDataUrn());
		obj.setVaulted(true);
		
		if(obj.getNameType() == NameEnumType.CREDENTIAL) {
			BaseRecord cred = (BaseRecord)obj;
			cred.setCredential(CryptoUtil.encipher(vault.getActiveKeyBean(), inData));

		}
		else if(obj.getNameType() == NameEnumType.DATA) {
			DataType data = (DataType)obj;
			data.setCompressed(false);
			data.setDataHash(CryptoUtil.getDigestAsString(inData,new byte[0]));
	
			if (inData.length > 512 && DataUtil.tryCompress(data))
			{
				inData = ZipUtil.gzipBytes(inData);
				data.setCompressed(true);
				data.setCompressionType(CompressionEnumType.GZIP);
			}
			DataUtil.setValue(data,CryptoUtil.encipher(vault.getActiveKeyBean(), inData));
		}

	}
	public byte[] extractVaultData(VaultBean vault, NameIdType obj) throws FactoryException, ArgumentException, DataException
	{
		byte[] outBytes = new byte[0];
		if(vault == null){
			logger.error("Vault reference is null");
			return outBytes;
		}
		if (!vault.getHaveVaultKey().booleanValue()){
			logger.warn("Vault key is not specified");
			return outBytes;
		}
		INameIdFactory fact = Factories.getFactory(FactoryEnumType.valueOf(obj.getNameType().toString()));
		if(fact.isVaulted() == false) throw new ArgumentException("Object factory does not support vaulted protection");

		boolean isVaulted = obj.getVaulted();
		String vaultId = obj.getVaultId();
		String keyId = obj.getKeyId();
		
		if(!isVaulted || vaultId == null || keyId == null) {
			logger.error("Object is not vaulted");
			return outBytes;
		}
		
		// If the data vault id isn't the same as this vault name, then it can't be decrypted.
		//
		if (vault.getVaultDataUrn().equals(vaultId) == false){
			logger.error("Object vault id '" + vaultId + "' does not match the specified vault name '" + vault.getVaultDataUrn() + "'.  This is a precautionary/secondary check, probably due to changing the persisted vault configuration name");
			return outBytes;
		}

		return getVaultBytes(vault,obj, getVaultCipher(vault,keyId));

	}
	public static byte[] getVaultBytes(VaultBean vault, NameIdType obj, SecurityBean bean) throws DataException, FactoryException, ArgumentException
	{
		
		if(bean == null){
			logger.error("Vault cipher for " + obj.getUrn() + " is null");
			return new byte[0];
		}
		
		INameIdFactory fact = Factories.getFactory(FactoryEnumType.valueOf(obj.getNameType().toString()));
		if(fact.isVaulted() == false) throw new ArgumentException("Object factory does not support vaulted protection");
		
		byte[] ret = new byte[0];
		switch(obj.getNameType()) {
			case DATA:
				DataType data = (DataType)obj;
				ret = CryptoUtil.decipher(bean,DataUtil.getValue(data));
				if (data.getCompressed().booleanValue() && ret.length > 0)
				{
					ret = ZipUtil.gunzipBytes(ret);
				}
				if (data.getPointer().booleanValue())
				{
					ret = FileUtil.getFile(new String(ret));
				}
				break;
			case CREDENTIAL:
				ret = CryptoUtil.decipher(bean,((BaseRecord)obj).getCredential());
				break;
			default:
				logger.error("Unhandled object type: " + obj.getNameType());
				break;
		}

		return ret;
	}
	/// Use this method with BaseService.add
	/// The TypeSanitizer will take care of setVaultBytes
	/// Otherwise, for direct factory adds (such as with bulk inserts), use setVaultBytes and invoke the add method directly on the factory
	public DataType newVaultData(VaultBean vault, UserType dataOwner, String name, DirectoryGroupType group, String mimeType, byte[] inData, byte[] clientCipher) throws FactoryException, ArgumentException, DataException, UnsupportedEncodingException
	{
		
		boolean encipher = (clientCipher != null && clientCipher.length > 0);
		if (inData == null || inData.length == 0) return null;

		DataType outData = ((DataFactory)Factories.getFactory(FactoryEnumType.DATA)).newData(dataOwner, group.getId());
		outData.setName(name);
		outData.setMimeType(mimeType);
		outData.setGroupPath(group.getPath());
		if (encipher && clientCipher.length > 0)
		{
			outData.setCipherKey(clientCipher);
			outData.setEncipher(true);
		}

		DataUtil.setValue(outData, inData);
		outData.setVaulted(true);
		outData.setVaultId(vault.getVaultDataUrn());
		return outData;
	}
	
	/// return a list of the PUBLIC vault configurations
	/// These have the same data as the PRIVATE configuration, with the exception of which key is held
	/// 

	
	public List<VaultType> listVaultsByOwner(UserType owner) throws FactoryException, ArgumentException, DataException{
		VaultType vault = new VaultType();
		((NameIdFactory)Factories.getFactory(FactoryEnumType.USER)).populate(owner);
		vault.setServiceUser(owner);
		vault.setServiceUserUrn(owner.getUrn());

		/// Using the default group location ("~/.vault)
		///
		DirectoryGroupType dir = getVaultGroup(vault);
		if(dir == null){
			return new ArrayList<>();
		}

		List<DataType> dataList = BaseService.listByGroup(AuditEnumType.DATA, "DATA", dir.getObjectId(), 0L, 0, owner);
		
		List<VaultType> vaults = new ArrayList<>();
		for(DataType data : dataList){
			VaultBean vaultb = getVaultByUrn(owner, data.getUrn());
			if(vaultb != null) vaults.add(vaultb);
		}

		return vaults;
	}
	
	/// User provided for context authorization
	///
	public VaultBean getVaultByUrn(UserType user, String urn){
		if(cacheByUrn.containsKey(urn)) return cacheByUrn.get(urn);
		
		DataType data = BaseService.readByUrn(AuditEnumType.DATA, urn, user);
		if(data == null){
			logger.error("Data is null for urn '" + urn + "'");
			return null;
		}
		return getVault(data);
	}
	/// User provided for context authorization
	///
	public VaultBean getVaultByObjectId(UserType user, String objectId){
		if(cacheByUrn.containsKey(objectId)) return cacheByUrn.get(objectId);
		
		DataType data = BaseService.readByObjectId(AuditEnumType.DATA, objectId, user);
		if(data == null){
			logger.error("Data is null for object id '" + objectId + "'");
			return null;
		}
		return getVault(data);
	}
	
	private VaultBean getVault(DataType data){
		VaultBean vault = null;
		VaultType pubVault = null;


		try {
			pubVault = JSONUtil.importObject(new String(DataUtil.getValue(data)), VaultType.class);
			initialize(pubVault, null);
		} catch ( DataException | ArgumentException | FactoryException e) {
			logger.error(e);
		}

		
		if(pubVault == null){
			logger.error("Vault reference could not be restored");
			return null;
		}
		
		String vaultPath = getVaultPath(pubVault);
		String credPath = getProtectedCredentialPath(pubVault);

		BaseRecord cred = loadProtectedCredential(credPath);

		vault = loadVault(vaultPath, pubVault.getVaultName(), pubVault.isProtected());
		if(vault == null){
			logger.error("Failed to restore vault");
			return null;
		}
		try {
			initialize(vault, cred);
		} catch (ArgumentException | FactoryException e) {
			logger.error(e);
			vault = null;
		}
		cacheByUrn.put(data.getUrn(), vault);
		return vault;
	}
	public static void clearCache() {
		cacheByUrn.clear();
	}
	public static String reportCacheSize(){
		return "VaultService Cache Report\ncacheByUrn\t" + cacheByUrn.keySet().size() + "\n";
	}
	*/
}

