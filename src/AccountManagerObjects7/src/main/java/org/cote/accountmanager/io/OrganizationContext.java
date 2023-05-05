package org.cote.accountmanager.io;

import java.security.KeyStore;

import org.apache.commons.codec.binary.Hex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.CryptoFactory;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.KeyStoreBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.VaultService;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.KeyStoreUtil;

public class OrganizationContext {
	public static final Logger logger = LogManager.getLogger(OrganizationContext.class);
	
	private final IOContext ioContext;
	private String organizationPath = null;
	private long organizationId = 0L;
	private OrganizationEnumType organizationType = null;
	private BaseRecord organization = null;
	private BaseRecord adminUser = null;
	private BaseRecord opsUser = null;
	private BaseRecord vaultUser = null;
	private BaseRecord docControl = null;
	private boolean initialized = false;
	private KeyStoreBean keyStoreBean = null;
	private char[] storePass = null;
	private KeyStore keyStore = null;
	private KeyStore trustStore = null;
	private CryptoBean organizationCipher = null;
	private VaultBean vault = null;
	
	public OrganizationContext(IOContext ctx) {
		this.ioContext = ctx;
	}
	
	public boolean initialize(String organizationPath, OrganizationEnumType orgType) {
		// logger.info("Initialize org from path: " + organizationPath);
		this.organizationPath = organizationPath;
		this.organizationType = orgType;
		BaseRecord rec = null;
		try {
			rec = ioContext.getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, organizationPath, (orgType != null && orgType != OrganizationEnumType.UNKNOWN ? orgType.toString() : null), 0L);
			if(rec == null) {
				logger.warn("Organization " + organizationPath + " not found");
				return false;
			}
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		return initialize(rec);
	}

	public boolean initialize(BaseRecord org) {
		// logger.info("Initialize org from record");
		if(org == null) {
			return false;
		}
		this.organization = org;
		this.organizationPath = org.get(FieldNames.FIELD_PATH);
		this.organizationId = org.get(FieldNames.FIELD_ID);
		this.organizationType = OrganizationEnumType.valueOf(org.get(FieldNames.FIELD_TYPE));
		adminUser =  ioContext.getRecordUtil().getRecord(null, ModelNames.MODEL_USER, Factory.ADMIN_USER_NAME, 0L, 0L, this.organization.get(FieldNames.FIELD_ID));
		opsUser =  ioContext.getRecordUtil().getRecord(null, ModelNames.MODEL_USER, Factory.OPS_USER_NAME, 0L, 0L, this.organization.get(FieldNames.FIELD_ID));
		vaultUser =  ioContext.getRecordUtil().getRecord(null, ModelNames.MODEL_USER, Factory.VAULT_USER_NAME, 0L, 0L, this.organization.get(FieldNames.FIELD_ID));
		docControl =  ioContext.getRecordUtil().getRecord(null, ModelNames.MODEL_USER, Factory.DOCUMENT_CONTROL_USER_NAME, 0L, 0L, this.organization.get(FieldNames.FIELD_ID));

		if(adminUser == null) {
			return false;
		}
		
		if(initializeStores(org) == false) {
			return false;
		}
		initialized = true;
		return true;
	}
	
	
	
	/// The stores are at the moment superfluous in that the keys and certificates are stored within keyStore and keySet models.  However, depending on how they may be used, it's more convenient to have them in the standard java format
	/// They are intentionally separated between organizations, and certificates won't chain across organizations unless specifically coded that way
	///
	private boolean initializeStores(BaseRecord org) {
		boolean outB = false;
		// logger.info(JSONUtil.exportObject(opsUser, RecordSerializerConfig.getUnfilteredModule()));
		long orgId = org.get(FieldNames.FIELD_ID);
		String storePath = IOFactory.DEFAULT_FILE_BASE + "/jks/" + orgId;
		String kpath = storePath + "/keystore.jks";
		String tpath = storePath + "/truststore.jks";
		
		String alias = org.get(FieldNames.FIELD_NAME) + " Certificate Authority";
		//keyStoreBean = KeyStoreUtil.getCreateStore(opsUser, alias, null);
		try {
			BaseRecord rec = ioContext.getRecordUtil().getRecord(opsUser, ModelNames.MODEL_KEY_STORE, alias + " Certificate", -1L, "~/keyStore");
			if(rec != null) {
				Query query = QueryUtil.createQuery(ModelNames.MODEL_CREDENTIAL, FieldNames.FIELD_REFERENCE_TYPE, ModelNames.MODEL_KEY_STORE);
				query.field(FieldNames.FIELD_REFERENCE_ID, rec.get(FieldNames.FIELD_ID));
				query.field(FieldNames.FIELD_OWNER_ID, opsUser.get(FieldNames.FIELD_ID));
				QueryResult res = ioContext.getSearch().find(query);
				if(res.getResponse() == OperationResponseEnumType.SUCCEEDED && res.getResults().length == 1) {
					storePass = new String((byte[])res.getResults()[0].get(FieldNames.FIELD_CREDENTIAL)).toCharArray();
					keyStore = KeyStoreUtil.getKeyStore(kpath, storePass);
					trustStore = KeyStoreUtil.getKeyStore(tpath, storePass);
					if(keyStore != null && trustStore != null) {
						outB = true;
					}
				}
			}
			if(rec != null) {
				IOSystem.getActiveContext().getRecordUtil().populate(rec, 3);
				// logger.info(rec.toString());
				keyStoreBean = new KeyStoreBean(rec);
			}
			
			BaseRecord crypto = IOSystem.getActiveContext().getRecordUtil().getCreateRecord(vaultUser, ModelNames.MODEL_KEY_SET, "Organization Cipher", "~/keys", vaultUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			IOSystem.getActiveContext().getRecordUtil().populate(crypto, 2);
			organizationCipher = new CryptoBean(crypto);

			if(organizationCipher.getSecretKey() == null) {
				// logger.info("Generating cipher for " + organizationPath);
				CryptoFactory.getInstance().generateSecretKey(organizationCipher);
				BaseRecord cipher = organizationCipher.get(FieldNames.FIELD_CIPHER); 
				ioContext.getRecordUtil().applyOwnership(vaultUser, cipher, orgId);
				IOSystem.getActiveContext().getRecordUtil().createRecord(cipher);
				IOSystem.getActiveContext().getRecordUtil().updateRecord(organizationCipher);
				// logger.info(organizationCipher.toString());
			}
			

			
		}
		catch(NullPointerException | IndexException | ReaderException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			
		}
		return outB;
	}
	
	private boolean initializeVault() {
		boolean outB = false;
		String vaultName = Hex.encodeHexString(CryptoUtil.getDigest(organizationPath.getBytes(), new byte[0]));
		vault = VaultService.getInstance().getCreateVault(vaultUser, vaultName, organizationId);

		if(vault != null && vault.isInitialized()) {
			outB = true;
		}
		return outB;
	}
	
	public VaultBean getVault() {
		if(vault == null) {
			initializeVault();
		}
		return vault;
	}
	
	/*
	 * 		String storePath = IOFactory.DEFAULT_FILE_BASE + "/jks/" + orgId;
		FileUtil.makePath(storePath);
		String kpath = storePath + "/keystore.jks";
		String tpath = storePath + "/truststore.jks";
		char[] pwd = new String((byte[])cred.get(FieldNames.FIELD_CREDENTIAL)).toCharArray();
		String alias = org.get(FieldNames.FIELD_NAME) + " Certificate Authority";

	 */
	
	public String getOrganizationPath() {
		return organizationPath;
	}

	public long getOrganizationId() {
		return organizationId;
	}

	public boolean isInitialized() {
		return initialized;
	}

	public void createOrganization() throws SystemException {
		if(organization != null) {
			throw new SystemException("Organization already exists");
		}
		if(organizationPath == null || organizationType == null) {
			throw new SystemException("Invalid organization path or type");
		}
		BaseRecord rec = ioContext.getFactory().makeOrganization(organizationPath, organizationType, 0L);
		if(rec == null) {
			throw new SystemException("Failed to create organization");
		}
		if(!initialize(rec)) {
			throw new SystemException("Failed to initialize organization");
		}
		
		
	}

	public BaseRecord getAdminUser() {
		return adminUser;
	}

	public BaseRecord getOpsUser() {
		return opsUser;
	}
	

	public BaseRecord getVaultUser() {
		return vaultUser;
	}
	
	public BaseRecord getDocumentControl() {
		return docControl;
	}

	public KeyStoreBean getKeyStoreBean() {
		return keyStoreBean;
	}

	public KeyStore getKeyStore() {
		return keyStore;
	}

	public KeyStore getTrustStore() {
		return trustStore;
	}

	public CryptoBean getOrganizationCipher() {
		return organizationCipher;
	}
	
	public byte[] sign(byte[] bytes) {
		//CryptoFactory.getInstance()
		return new byte[0];
	}
	
	
	
	/*
	 * 		devOrganization = makeOrganization("/Development", OrganizationEnumType.DEVELOPMENT, 0L);
			adminUser =  recordUtil.getRecord(null, ModelNames.MODEL_USER, "admin", 0L, 0L, devOrganization.get(FieldNames.FIELD_ID));
			assertNotNull("Admin user is null", adminUser);
			
			//devOrganization = makePath(null, ModelNames.MODEL_ORGANIZATION, "/Development", null, 0L);
			testGroup1 = pathUtil.makePath(adminUser, ModelNames.MODEL_GROUP, "/home/testUser1", "DATA", devOrganization.get(FieldNames.FIELD_ID));
			testGroup2 = pathUtil.makePath(adminUser, ModelNames.MODEL_GROUP, "/home/testUser2", "DATA", devOrganization.get(FieldNames.FIELD_ID));
			testGroup3 = pathUtil.makePath(adminUser, ModelNames.MODEL_GROUP, "/home/testUser3", "DATA", devOrganization.get(FieldNames.FIELD_ID));
			testUser1 = getCreateUser(adminUser, "testUser1", testGroup1, devOrganization.get(FieldNames.FIELD_ID));
			testUser2 = getCreateUser(adminUser, "testUser2", testGroup2, devOrganization.get(FieldNames.FIELD_ID));
			testUser3 = getCreateUser(adminUser, "testUser3", testGroup3, devOrganization.get(FieldNames.FIELD_ID));
	 */
}
