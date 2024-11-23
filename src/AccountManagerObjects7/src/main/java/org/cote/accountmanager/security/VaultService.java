
package org.cote.accountmanager.security;



import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.codec.binary.Hex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.JsonReader;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.CompressionEnumType;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.FieldUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ZipUtil;


/// The Vault setup in AM7 is similar in concept to the AM5/6 version, but noteably different in the persistence layer.
/// Instead of storing the keys as serialized entities in DataType objects, the keysets, keys, hashes, and vaults all have their own model.
/// There was also a fair amount of duplication in the original object members to differentiate between enciphered/encrypted values vs. temporarily decrypted values
/// In the current version, values are left encrypted, and only temporarily decrypted to reconstitute the key/cipher.
/// TODO: Currently, the private key is left exposed on the VaultBean instance once instantiated, so the public access methods need to only return sanitized versions
/// TODO: Move read/search/delete to use AccessPoint 

public class VaultService
{
	
	
	
	public static final Logger logger = LogManager.getLogger(VaultService.class);
	
	private static Map<String,VaultBean> cacheByObjectId = new ConcurrentHashMap<>();
	
	/// export a version of the vault that does not include exposed (aka unencrypted) information that should be protected
	///
	public String exportVault(VaultBean vault) throws Exception{
		throw new Exception("Not implemented");
	}
	
	private static VaultService instance = null;
	
	private RecordUtil recordUtil = null;
	private Factory factory = null;
	private ISearch search = null;
	
	public static String DEFAULT_VAULT_PATH = "/.vault";
	
	public static VaultService getInstance() {
		if(instance == null) {
			instance = new VaultService();
		}
		return instance;
	}
	
	public VaultService(){
		this.recordUtil = IOSystem.getActiveContext().getRecordUtil();
		this.factory = IOSystem.getActiveContext().getFactory();
		this.search = IOSystem.getActiveContext().getSearch();
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

		if(outCred == null ){
			logger.error("Credential was not successfully restored");
		}

		return outCred;
	}
	
	/// Create an encrypted credential used to protect the private vault key
	/// This credential is enciphered with a discrete secret key, stored in the database
	///
	public boolean createProtectedCredentialFile(BaseRecord vaultOwner, String filePath, byte[] credential) throws FactoryException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		
		recordUtil.populate(vaultOwner);
		File f = new File(filePath);
		if(f.exists()){
			logger.error("File '" + filePath + "' already exists");
			return false;
		}
		
		/// Note: CredentialService is intentionally NOT USED here because this credential SHOULD NOT be stored in the database
		///
		BaseRecord cred = factory.newInstance(ModelNames.MODEL_CREDENTIAL, vaultOwner, null, null);
		cred.set(FieldNames.FIELD_TYPE, CredentialEnumType.ENCRYPTED_PASSWORD);
		cred.set(FieldNames.FIELD_ENCIPHERED, true);
		
		String keyName = filePath.substring(filePath.lastIndexOf("/") + 1);
		

		BaseRecord crypto = recordUtil.getCreateRecord(vaultOwner, ModelNames.MODEL_KEY_SET, "Vault Key - " + keyName, "~/keys", vaultOwner.get(FieldNames.FIELD_ORGANIZATION_ID));
		recordUtil.populate(crypto);
		CryptoBean cb = new CryptoBean(crypto);
		if(cb.getSecretKey() == null) {
			CryptoFactory.getInstance().generateSecretKey(cb);
			BaseRecord ciph = cb.get(FieldNames.FIELD_CIPHER);
			recordUtil.applyOwnership(vaultOwner, ciph, vaultOwner.get(FieldNames.FIELD_ORGANIZATION_ID));
			recordUtil.createRecord(ciph);
			recordUtil.updateRecord(cb);
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
			
			Query keyQuery = QueryUtil.createQuery(ModelNames.MODEL_KEY_SET, FieldNames.FIELD_OBJECT_ID, credential.get(FieldNames.FIELD_KEY_ID));
			keyQuery.planMost(true);
			BaseRecord keySet = IOSystem.getActiveContext().getSearch().findRecord(keyQuery);
			if(keySet == null) {
				logger.warn("Failed to retrieve keySet");
			}
			CryptoBean crypto = new CryptoBean(keySet);
			if(crypto.getSecretKey() == null){
				logger.warn("Secret key is null");
				return new byte[0];
			}
			return CryptoUtil.decipher(crypto, credential.get(FieldNames.FIELD_CREDENTIAL));
		}
		return credential.get(FieldNames.FIELD_CREDENTIAL);
	}
	
	private CryptoBean getPrimarySymmetricKey(VaultBean bean) {
		IOSystem.getActiveContext().getRecordUtil().populate(bean.getServiceUser());
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
		vault.setKeyPath(CryptoUtil.encipher(orgSKey, path.getBytes()));
	}
	
	private String getKeyPath(VaultBean vault){
		
		if(vault.getServiceUser() == null){
			logger.error("Vault is not properly initialized");
			return null;
		}
		IOSystem.getActiveContext().getRecordUtil().populate(vault.getServiceUser());
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
				String credPath = IOFactory.DEFAULT_FILE_BASE + DEFAULT_VAULT_PATH + "/" + organizationId + "/credential/" + vaultName + ".json";
				String vaultPath = IOFactory.DEFAULT_FILE_BASE + DEFAULT_VAULT_PATH + "/" + organizationId + "/vault";
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
		catch(Exception e) {
			logger.error(e);
		}
		return vault;
	}
	
	public VaultBean newVault(BaseRecord serviceUser, String vaultBasePath, String vaultName){
		VaultBean vault = null;
		try {
			vault = new VaultBean(factory.newInstance(ModelNames.MODEL_VAULT));
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
			IOSystem.getActiveContext().getRecordUtil().populate(vault.getServiceUser());
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

			String ipath = "~/" + vault.getGroupName() + "/" + vault.get(FieldNames.FIELD_NAME);
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(vault.getServiceUser(), ModelNames.MODEL_GROUP, ipath, "DATA", orgId);
			recordUtil.populate(dir);
			
			ParameterList kslist = ParameterUtil.newParameterList(FieldNames.FIELD_PATH, ipath);
			kslist.parameter("salt", true);
			BaseRecord saltSet = factory.newInstance(ModelNames.MODEL_KEY_SET, vault.getServiceUser(), null, kslist);
			saltSet.set(FieldNames.FIELD_NAME, "Salt");

			recordUtil.applyNameGroupOwnership(vault.getServiceUser(), saltSet, "Salt", ipath, orgId);
			
			recordUtil.createRecord(saltSet);
			
			CryptoBean sm = new CryptoBean();
			sm.set(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT, true);
			CryptoFactory.getInstance().generateKeyPair(sm);
			CryptoFactory.getInstance().generateSecretKey(sm);

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
			BaseRecord publicKeyCfg = sm.copyRecord();
			publicKeyCfg.set(FieldNames.FIELD_PRIVATE, null);
			publicKeyCfg.set(FieldNames.FIELD_CIPHER, null);
			
			BaseRecord publicKey = factory.newInstance(ModelNames.MODEL_KEY_SET, vault.getServiceUser(), publicKeyCfg, null);
			
			recordUtil.applyNameGroupOwnership(vault.getServiceUser(), publicKey, "Public Key", dir.get(FieldNames.FIELD_PATH), orgId);
			recordUtil.applyOwnership(vault.getServiceUser(), publicKey.get(FieldNames.FIELD_PUBLIC), orgId);
			recordUtil.applyOwnership(vault.getServiceUser(), publicKey.get(FieldNames.FIELD_HASH), orgId);

			recordUtil.createRecord(publicKey);
			
			BaseRecord pdat =  factory.getCreateDirectoryModel(vault.getServiceUser(), ModelNames.MODEL_VAULT, vault.get(FieldNames.FIELD_NAME), "~/" + vault.getGroupName(), orgId);
			pdat.set(FieldNames.FIELD_HAVE_VAULT_KEY, true);
			pdat.set(FieldNames.FIELD_PROTECTED_CREDENTIAL_PATH, vault.getProtectedCredentialPath());
			pdat.set(FieldNames.FIELD_PUBLIC, publicKey);
			pdat.set(FieldNames.FIELD_PROTECTED, true);
			pdat.set(FieldNames.FIELD_HAVE_CREDENTIAL, true);
			pdat.set(FieldNames.FIELD_SALT, saltSet);
			pdat.set(FieldNames.FIELD_SERVICE_USER, vault.getServiceUser());
			pdat.set(FieldNames.FIELD_VAULT_PATH, vault.get(FieldNames.FIELD_VAULT_PATH));
			pdat.set(FieldNames.FIELD_PROTECTED_CREDENTIAL_PATH, vault.get(FieldNames.FIELD_PROTECTED_CREDENTIAL_PATH));
			recordUtil.applyOwnership(vault.getServiceUser(), pdat, orgId);
			recordUtil.createRecord(pdat);
			
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
		catch(IllegalArgumentException | NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException e){
			logger.error(e);
		}
		return true;
	}
	public CryptoBean getActiveKey(VaultBean vault) {
		if(vault.getActiveKey() != null) {
			return vault.getActiveKey();
		}
		BaseRecord key = null;
		if(!vault.hasField(FieldNames.FIELD_ACTIVE_KEY) || vault.get(FieldNames.FIELD_ACTIVE_KEY) == null) {
			if(!newActiveKey(vault)) {
				logger.error("Failed to create a new active key");
				return null;
			}
		}
		key = vault.get(FieldNames.FIELD_ACTIVE_KEY);
		if(key == null) {
			logger.error("Failed to retrieve key");
			return null;
		}
		
		if(vault.getVaultKey() == null && getVaultKey(vault) == null) {
			logger.error("Vault key is null");
			return null;
		}
		CryptoBean vaultKey = vault.getVaultKey();
		CryptoBean activeBean = null;
		activeBean = new CryptoBean(key, vaultKey.getPrivateKey(), vaultKey.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC));
		vault.setActiveKey(activeBean);
		return activeBean;
	}
	
	public VaultBean getPublicVault(BaseRecord user, String name) {
		VaultBean dvault = new VaultBean();
		VaultBean pvault = null;
		
		try {
			BaseRecord group = search.findByPath(user, ModelNames.MODEL_GROUP, "~/" + dvault.get(FieldNames.FIELD_GROUP_NAME), user.get(FieldNames.FIELD_ORGANIZATION_ID));
			if(group == null) {
				logger.warn("Vault group does not exist: " + "~/" + dvault.get(FieldNames.FIELD_GROUP_NAME));
				return null;
			}

			Query vaultQuery = QueryUtil.createQuery(ModelNames.MODEL_VAULT, FieldNames.FIELD_NAME, name);
			vaultQuery.field(FieldNames.FIELD_GROUP_ID, group.get(FieldNames.FIELD_ID));
			vaultQuery.planMost(true);
			BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(vaultQuery);
			if(rec == null) {
				logger.error("Failed to retrieve vault " + name);
				return null;
			}
			pvault = new VaultBean(rec);
			if(!initialize(pvault, null)) {
				logger.error("Failed to initialize public vault");
				return null;
			}
		}
		catch(ReaderException e) {
			logger.error(e);
		}
		return pvault;
	}
	public VaultBean getVault(BaseRecord user, String name) {
		
		VaultBean pvault = getPublicVault(user, name);
		if(pvault == null) {
			logger.warn("Failed to find public vault " + name);
			return null;
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
			
			recordUtil.populate(vault.getServiceUser());
			recordUtil.populate(vault.get(FieldNames.FIELD_PUBLIC));
			recordUtil.populate(vault.get(FieldNames.FIELD_SALT));
	
			String vaultPath = getVaultPath(vault);
			logger.debug("Initializing Vault '" + vault.get(FieldNames.FIELD_NAME) + "' In " + vaultPath);
			if (FileUtil.makePath(vaultPath) == false)
			{
				throw new FieldException("Unable to create path to " + vaultPath);
			}
	
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
		IOSystem.getActiveContext().getRecordUtil().populate(vault.getServiceUser());
		return search.findByPath(vault.getServiceUser(), ModelNames.MODEL_GROUP, "~/" + vault.get(FieldNames.FIELD_GROUP_NAME), vault.get(FieldNames.FIELD_ORGANIZATION_ID));
	}

	private BaseRecord getVaultInstanceGroup(VaultBean vault) throws IndexException, ReaderException {
		BaseRecord grp = getVaultGroup(vault);
		if(grp == null) {
			logger.error("Failed to find vault group");
			return null;
		}

		Query groupQuery = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_NAME, vault.get(FieldNames.FIELD_NAME));
		groupQuery.field(FieldNames.FIELD_PARENT_ID, grp.get(FieldNames.FIELD_ID));
		groupQuery.planMost(true);
		BaseRecord ogrp = IOSystem.getActiveContext().getSearch().findRecord(groupQuery);

		if(ogrp == null) {
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

		Query keyQuery = QueryUtil.createQuery(ModelNames.MODEL_KEY_SET, FieldNames.FIELD_NAME, "Salt");
		keyQuery.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		keyQuery.planMost(true);
		return IOSystem.getActiveContext().getSearch().findRecord(keyQuery);
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
				
				recordUtil.populate(credSalt.get(FieldNames.FIELD_HASH));
				if (vault.isProtected()){
					decConfig = CryptoUtil.decipher(decConfig, new String(getProtectedCredentialValue(vault.getProtectedCredential())), credSalt.get(FieldNames.FIELD_HASH_FIELD_SALT));
				}

				if (decConfig.length == 0) return null;
				CryptoBean crypto = new CryptoBean();
				CryptoFactory.getInstance().importCryptoBean(crypto, decConfig, false);
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
			BaseRecord grp = getVaultGroup(vault);
			Query vaultQuery = QueryUtil.createQuery(ModelNames.MODEL_VAULT, FieldNames.FIELD_NAME, vault.get(FieldNames.FIELD_NAME));
			vaultQuery.field(FieldNames.FIELD_GROUP_ID, grp.get(FieldNames.FIELD_ID));
			vaultQuery.planMost(true);
			BaseRecord rec = IOSystem.getActiveContext().getSearch().findRecord(vaultQuery);
			if(rec != null) {
				BaseRecord key = rec.get(FieldNames.FIELD_PUBLIC);
				crypto = new CryptoBean(key);
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
		IOSystem.getActiveContext().getRecordUtil().populate(vault.getServiceUser());
		logger.info("**** NEW ACTIVE KEY");
		if(pubKey == null) {
			logger.error("Public key could not be found");
			return false;
		}
		try {
			recordUtil.populate(vault.getServiceUser());
			CryptoBean key = new CryptoBean(factory.newInstance(ModelNames.MODEL_KEY_SET, vault.getServiceUser(), null, null));
			key.setPublicKey((BaseRecord)pubKey.get(FieldNames.FIELD_PUBLIC));
			key.set(FieldNames.FIELD_CIPHER_FIELD_ENCRYPT, true);
			if(!CryptoFactory.getInstance().generateSecretKey(key)) {
				logger.error(key.toString());
				return false;
			}
	
			BaseRecord expKey = key.copyRecord();
			expKey.set(FieldNames.FIELD_PUBLIC, null);
			expKey.set(FieldNames.FIELD_CIPHER_FIELD_KEY_ID, vault.get(FieldNames.FIELD_VAULT_LINK));
			BaseRecord impDir = getVaultInstanceGroup(vault);
			if(impDir == null) {
				logger.error("Invalid vault directory");
				return false;
			}
			BaseRecord cipher = expKey.get(FieldNames.FIELD_CIPHER);
			recordUtil.applyNameGroupOwnership(vault.getServiceUser(), expKey, "Key - " + UUID.randomUUID().toString(), impDir.get(FieldNames.FIELD_PATH), vault.get(FieldNames.FIELD_ORGANIZATION_ID));
			recordUtil.applyOwnership(vault.getServiceUser(), cipher, vault.get(FieldNames.FIELD_ORGANIZATION_ID));
			
			recordUtil.createRecord(expKey);
			vault.set(FieldNames.FIELD_ACTIVE_KEY, expKey);
			vault.setActiveKey(null);
		}
		catch(ClassCastException | NullPointerException | ReaderException | FieldException | ValueException | ModelNotFoundException | IndexException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
			
		}
		return true;
	}
	
	public List<VaultBean> listVaultsByOwner(BaseRecord owner) {
		
		VaultBean tmp = new VaultBean();
		recordUtil.populate(owner);
		List<VaultBean> vaults = new ArrayList<>();
		try {
			
			tmp.set(FieldNames.FIELD_SERVICE_USER, owner);
			tmp.set(FieldNames.FIELD_ORGANIZATION_ID, owner.get(FieldNames.FIELD_ORGANIZATION_ID));
			
			BaseRecord vgroup = getVaultGroup(tmp);
			if(vgroup == null) {
				logger.error("Failed to find vault group");
				return vaults;
			}
			Query q = QueryUtil.createQuery(ModelNames.MODEL_VAULT, FieldNames.FIELD_OWNER_ID, owner.get(FieldNames.FIELD_ID));
			QueryResult qr = search.find(q);
			
			if(qr != null) {
				for(BaseRecord r: qr.getResults()) {
					vaults.add(new VaultBean(r));
				}
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException | IndexException | ReaderException e) {
			logger.error(e);
		}
		return vaults;
		
	}
	

	
	/// TODO - change back to private
	public CryptoBean getVaultCipher(VaultBean vault, String keyId) {
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_KEY_SET, FieldNames.FIELD_OBJECT_ID, keyId);
		q.planMost(true);
		BaseRecord key = search.findRecord(q);
		if(key == null) {
			logger.error("Failed to find key: " + keyId);
			return null;
		}

		/// TODO: Is this needed?
		this.getVaultKey(vault);
		CryptoBean vaultKey = getVaultKey(vault);
		CryptoBean crypto = new CryptoBean(key, vaultKey.getPrivateKey(), vaultKey.get(FieldNames.FIELD_PRIVATE_FIELD_KEYSPEC));
		return crypto;

	}
	
	public VaultBean getVaultByObjectId(BaseRecord user, String objectId){
		BaseRecord data = recordUtil.getRecordByObjectId(user, ModelNames.MODEL_VAULT, objectId);
		if(data == null){
			logger.error("Data is null for object id '" + objectId + "'");
			return null;
		}
		return getVault(new VaultBean(data));
	}
	
	private VaultBean getVault(VaultBean pubVault){
		if(pubVault == null){
			logger.error("Vault reference could not be restored");
			return null;
		}

		initialize(pubVault, null);
		
		String vaultPath = getVaultPath(pubVault);
		String credPath = getProtectedCredentialPath(pubVault);
		BaseRecord cred = loadProtectedCredential(credPath);

		VaultBean vault = loadVault(vaultPath, pubVault.get(FieldNames.FIELD_NAME), pubVault.get(FieldNames.FIELD_PROTECTED));
		if(vault == null){
			logger.error("Failed to restore vault");
			return null;
		}
		initialize(vault, cred);

		return vault;
	}

	private void deleteHash(BaseRecord rec) {
		BaseRecord hash = rec.get(FieldNames.FIELD_HASH);
		if(hash != null && RecordUtil.isIdentityRecord(hash)) {
			recordUtil.deleteRecord(hash);
		}
	}
	private void deleteCipher(BaseRecord rec) {
		BaseRecord cip = rec.get(FieldNames.FIELD_CIPHER);
		if(cip != null && RecordUtil.isIdentityRecord(cip)) {
			recordUtil.deleteRecord(cip);
		}
	}
	private void deleteKeyPair(BaseRecord rec) {
		BaseRecord priv = rec.get(FieldNames.FIELD_PRIVATE);
		BaseRecord pub = rec.get(FieldNames.FIELD_PUBLIC);
		if(priv != null && RecordUtil.isIdentityRecord(priv)) {
			recordUtil.deleteRecord(priv);
		}
		if(pub != null && RecordUtil.isIdentityRecord(pub)) {
			recordUtil.deleteRecord(pub);
		}
		
	}
	private void deleteKeySet(BaseRecord rec) {
		if(rec != null) {
			deleteCipher(rec);
			deleteHash(rec);
			deleteKeyPair(rec);
			if(RecordUtil.isIdentityRecord(rec)) {
				recordUtil.deleteRecord(rec);
			}
		}
	}
	private void deleteSalt(BaseRecord rec) {
		BaseRecord salt = rec.get(FieldNames.FIELD_SALT);
		if(salt != null && RecordUtil.isIdentityRecord(salt)) {
			deleteKeySet(salt);
		}
	}
	private void deleteVaultRecord(BaseRecord rec) {
		if(rec != null) {
			deleteSalt(rec);
			if(RecordUtil.isIdentityRecord(rec)) {
				recordUtil.deleteRecord(rec);
			}
		}
	}
	
	public boolean deleteVault(VaultBean vault) throws IndexException, ReaderException
	{
		logger.info("Cleaning up vault instance");
		IOSystem.getActiveContext().getRecordUtil().populate(vault.getServiceUser());
		
		String vaultLink = vault.get(FieldNames.FIELD_VAULT_LINK);
		BaseRecord pvault = null;
		if(vaultLink != null) {
			pvault = recordUtil.getRecordByObjectId(vault.getServiceUser(), ModelNames.MODEL_VAULT, vaultLink);
		}

		if (!(boolean)vault.get(FieldNames.FIELD_HAVE_VAULT_KEY)){
			logger.warn("No key detected, so nothing is deleted");
		}
		if (vault.getKeyPath() == null){
			logger.warn("Key path is null");
		}
		else{
			String keyPath = getKeyPath(vault);
			File vaultKeyFile = new File(keyPath);
			if(vaultKeyFile.exists()){
				if(!vaultKeyFile.delete()) {
					logger.error("Unable to delete vault key file " + keyPath);
				}
			}
			else{
				logger.warn("Vault file " + keyPath + " does not exist");
			}
		}
		
		String credPath = getProtectedCredentialPath(vault);
		if(credPath == null || credPath.length() == 0) {
			logger.warn("Credential path is null");
		}
		else {
			File credFile = new File(credPath);
			if(credFile.exists()) {
				if(!credFile.delete()) {
					logger.error("Unable to delete vault credential file " + credPath);
				}
			}
			else {
				logger.warn("Vault credential file " + credPath + " does not exist");
			}
		}

		BaseRecord localImpDir = getVaultInstanceGroup(vault);
		logger.info("Removing implementation group: " + (localImpDir == null ? "[null]" : localImpDir.get(FieldNames.FIELD_URN)));
		if (localImpDir != null )
		{
			Query keySetsQ = QueryUtil.createQuery(ModelNames.MODEL_KEY_SET, FieldNames.FIELD_GROUP_ID, localImpDir.get(FieldNames.FIELD_ID));
			QueryResult keySetQR = search.find(keySetsQ);
			logger.info("Delete " + keySetQR.getResults().length + " ciphers from " + localImpDir.get(FieldNames.FIELD_URN));
			for(BaseRecord r : keySetQR.getResults()) {
				deleteKeySet(r);
			}
		}

		BaseRecord vaultGroup = getVaultGroup(vault);
		if(vaultGroup != null){
			logger.info("Removing implementation data");
			deleteVaultRecord(pvault);
			deleteVaultRecord(vault);
			recordUtil.deleteRecord(vaultGroup);
		}
		else{
			logger.warn("Vault group is null");
		}

		return true;
	}
	

	
	public void vaultField(VaultBean vault, BaseRecord obj, FieldType field) throws ValueException, ModelException
	{
		if(!obj.inherits(ModelNames.MODEL_VAULT_EXT)) {
			throw new ModelException("Model does not inherit from " + ModelNames.MODEL_VAULT_EXT);
		}
		ModelSchema ms = RecordFactory.getSchema(obj.getModel());
		FieldSchema fs = ms.getFieldSchema(field.getName());
		
		if(!fs.isEncrypt()) {
			throw new ModelException("Model " + obj.getModel() + " field " + field.getName() + " is not configured to be encrypted");
		}
		if(FieldUtil.isNullOrEmpty(obj.getModel(), field)) {
			logger.warn("Do not vault null or empty value");
			return;
		}
		
		String vaultId = obj.get(FieldNames.FIELD_VAULT_ID);
		String vlink = vault.get(FieldNames.FIELD_VAULT_LINK);
		
		/// Because multiple fields may be encrypted, they'll need to use the same vault and key associated with the record
		///
		if(vaultId != null && !vaultId.equals(vlink)) {
			throw new ValueException("Specified vault does not match the recorded vault identifier");
		}
		String keyId = obj.get(FieldNames.FIELD_KEY_ID);
		CryptoBean key = null;
		if(keyId != null) {
			key = getVaultCipher(vault, keyId);
			if(key == null) {
				throw new ValueException("Failed to find key " + keyId);
			}
		}
		else {
			if(vault.getActiveKey() == null && getActiveKey(vault) == null) {
				throw new ValueException("Failed to establish active key");
			}
			key = vault.getActiveKey();
			keyId = key.get(FieldNames.FIELD_OBJECT_ID);
		}
		recordUtil.populate(key);
		
		List<String> vaulted = obj.get(FieldNames.FIELD_VAULTED_FIELDS);
		List<String> unvaulted = obj.get(FieldNames.FIELD_UNVAULTED_FIELDS);
		if(vaulted.contains(field.getName())) {
			logger.error("Field " + field.getName() + " is already vaulted");
			return;
		}
		
		try {
			obj.set(FieldNames.FIELD_KEY_ID, keyId);
			obj.set(FieldNames.FIELD_VAULT_ID, vlink);
			obj.set(FieldNames.FIELD_VAULTED, true);

			switch(field.getValueType()) {
				case STRING:
					String sval = field.getValue();
					if(sval != null && sval.length() > 0) {
						field.setValue(
							BinaryUtil.toBase64Str(
								CryptoUtil.encipher(key, sval.getBytes(StandardCharsets.UTF_8))
							)
						);
					}
					break;
				case BLOB:
					byte[] bval = field.getValue();
					if(bval != null && bval.length > 0) {
						field.setValue(CryptoUtil.encipher(key,  field.getValue()));
					}
					break;
				default:
					throw new ValueException("Unhandled field type: " + field.getValueType().toString());
			}
			
		}
		catch(ClassCastException | ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		unvaulted.remove(field.getName());
		vaulted.add(field.getName());
	}
	
	public void unvaultField(VaultBean vault, BaseRecord obj, FieldType field) throws ModelException, ValueException, FieldException
	{
		if(vault == null){
			logger.error("Vault reference is null");
			return;
		}

		if (!(boolean)vault.get(FieldNames.FIELD_HAVE_VAULT_KEY)){
			logger.warn("Vault key is not specified");
			return;
		}
		
		if(obj == null){
			logger.error("Object reference is null");
			return;
		}
		
		if(field == null) {
			logger.error("Field reference is null");
			return;
		}
		
		List<String> vaulted = obj.get(FieldNames.FIELD_VAULTED_FIELDS);
		List<String> unvaulted = obj.get(FieldNames.FIELD_UNVAULTED_FIELDS);
		
		if(FieldUtil.isNullOrEmpty(obj.getModel(), field)) {
			logger.warn("Marking null or empty field as being decrypted");
			vaulted.remove(field.getName());
			unvaulted.add(field.getName());
			return;
		}
		
		if(!obj.inherits(ModelNames.MODEL_VAULT_EXT)) {
			throw new ModelException("Model does not inherit from " + ModelNames.MODEL_VAULT_EXT);
		}
		ModelSchema ms = RecordFactory.getSchema(obj.getModel());
		FieldSchema fs = ms.getFieldSchema(field.getName());
		
		if(!fs.isEncrypt()) {
			throw new ModelException("Model " + obj.getModel() + " field " + field.getName() + " is not configured to be encrypted");
		}
		
		boolean isVaulted = obj.get(FieldNames.FIELD_VAULTED);
		String vaultId = obj.get(FieldNames.FIELD_VAULT_ID);
		String keyId = obj.get(FieldNames.FIELD_KEY_ID);
		
		if(!isVaulted || vaultId == null || keyId == null) {
			logger.error("Object is not vaulted");
			return;
		}
		
		String vaultLinkId = vault.get(FieldNames.FIELD_VAULT_LINK);
		if (vaultLinkId.equals(vaultId) == false){
			logger.error("Object vault id '" + vaultId + "' does not match the specified vault link id '" + vaultLinkId + "'.  This may result from changing the persisted vault configuration name.");
			return;
		}
		
		CryptoBean key = getVaultCipher(vault, keyId);
		if(key == null) {
			throw new ValueException("Vault cipher is null");
		}
		
		if(unvaulted.contains(field.getName())) {
			logger.info("Field " + field.getName() + " was already unvaulted");
		}

		switch(field.getValueType()) {
			case STRING:
				String sval = field.getValue();
				if(sval != null && sval.length() > 0) {
					// logger.warn("Decrypt: '" + sval + "'");
					field.setValue(
						new String(
							CryptoUtil.decipher(key, BinaryUtil.fromBase64(
								sval.getBytes(StandardCharsets.UTF_8)
							)),
							StandardCharsets.UTF_8)
					);
				}
				break;
			case BLOB:
				byte[] bval = field.getValue();
				if(bval != null && bval.length > 0) {
					field.setValue(CryptoUtil.decipher(key,  field.getValue()));
				}
				break;
			default:
				throw new ValueException("Unhandled field type: " + field.getValueType().toString());
		}
		vaulted.remove(field.getName());
		unvaulted.add(field.getName());

	}
	
	public byte[] extractVaultDataLegacy(VaultBean vault, BaseRecord obj) throws ModelException, ValueException, FieldException
	{
		byte[] outBytes = new byte[0];
		if(vault == null){
			logger.error("Vault reference is null");
			return outBytes;
		}
		if (!(boolean)vault.get(FieldNames.FIELD_HAVE_VAULT_KEY)){
			logger.warn("Vault key is not specified");
			return outBytes;
		}
		
		if(!obj.inherits(ModelNames.MODEL_CRYPTOBYTESTORE) && !obj.inherits(ModelNames.MODEL_VAULT_EXT)) {
			throw new ModelException("Model does not inherit from " + ModelNames.MODEL_CRYPTOBYTESTORE + " or " + ModelNames.MODEL_VAULT_EXT);
		}
		
		boolean isVaulted = obj.get(FieldNames.FIELD_VAULTED);
		String vaultId = obj.get(FieldNames.FIELD_VAULT_ID);
		String keyId = obj.get(FieldNames.FIELD_KEY_ID);
		
		if(!isVaulted || vaultId == null || keyId == null) {
			logger.error("Object is not vaulted");
			return outBytes;
		}
		
		String vaultLinkId = vault.get(FieldNames.FIELD_VAULT_LINK);
		// If the data vault id isn't the same as this vault name, then it can't be decrypted.
		//
		if (vaultLinkId.equals(vaultId) == false){
			logger.error("Object vault id '" + vaultId + "' does not match the specified vault link id '" + vaultLinkId + "'.  This is a precautionary/secondary check, probably due to changing the persisted vault configuration name");
			return outBytes;
		}

		return getVaultBytesLegacy(vault,obj, getVaultCipher(vault,keyId));

	}
	
	public static byte[] getVaultBytesLegacy(VaultBean vault, BaseRecord obj, CryptoBean bean) throws ModelException, ValueException, FieldException
	{
		
		if(!obj.inherits(ModelNames.MODEL_CRYPTOBYTESTORE) && !obj.inherits(ModelNames.MODEL_VAULT_EXT)) {
			throw new ModelException("Model does not inherit from " + ModelNames.MODEL_CRYPTOBYTESTORE + " or " + ModelNames.MODEL_VAULT_EXT);
		}
		
		if(bean == null){
			logger.error("Vault cipher for " + obj.get(FieldNames.FIELD_OBJECT_ID) + " is null");
			return new byte[0];
		}
		
		byte[] ret = CryptoUtil.decipher(bean, ByteModelUtil.getValue(obj));
		CompressionEnumType cet = CompressionEnumType.valueOf(obj.get(FieldNames.FIELD_COMPRESSION_TYPE));
		if (cet == CompressionEnumType.GZIP && ret.length > 0)
		{
			ret = ZipUtil.gunzipBytes(ret);
		}


		return ret;
	}
	
	public void setVaultBytesLegacy(VaultBean vault, BaseRecord obj, byte[] inData) throws ValueException, ModelException
	{
		if (vault.getActiveKey() == null){
			if(getActiveKey(vault) == null){
				throw new ValueException("Failed to establish active key");
			}
			if (vault.getActiveKey() == null)
			{
				throw new ValueException("Active key is null");
			}
		}
		
		if(!obj.inherits(ModelNames.MODEL_CRYPTOBYTESTORE) && !obj.inherits(ModelNames.MODEL_VAULT_EXT)) {
			throw new ModelException("Model does not inherit from " + ModelNames.MODEL_CRYPTOBYTESTORE + " or " + ModelNames.MODEL_VAULT_EXT);
		}
		recordUtil.populate(vault.getActiveKey());
		logger.info("Get active key");
		CryptoBean key = vault.getActiveKey();
		
		try {
			obj.set(FieldNames.FIELD_KEY_ID, key.get(FieldNames.FIELD_OBJECT_ID));
			obj.set(FieldNames.FIELD_VAULT_ID, vault.get(FieldNames.FIELD_VAULT_LINK));
			obj.set(FieldNames.FIELD_VAULTED, true);
			obj.set(FieldNames.FIELD_COMPRESSION_TYPE, CompressionEnumType.UNKNOWN);
			obj.set(FieldNames.FIELD_DATA_HASH, CryptoUtil.getDigestAsString(inData, new byte[0]));
			if(inData.length > ByteModelUtil.MINIMUM_COMPRESSION_SIZE && ByteModelUtil.tryCompress(obj)) {
				inData = ZipUtil.gzipBytes(inData);
				obj.set(FieldNames.FIELD_COMPRESSION_TYPE, CompressionEnumType.GZIP);
			}
			logger.info("Encipher data");
			ByteModelUtil.setValue(obj, CryptoUtil.encipher(key, inData));
			
		}
		catch(ClassCastException | ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		
		logger.info("Exit from set vault bytes");

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
	*/
	
}

