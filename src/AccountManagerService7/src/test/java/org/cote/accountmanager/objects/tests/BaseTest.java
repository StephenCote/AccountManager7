package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.KeyStoreBean;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.CertificateUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.KeyStoreUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Before;

public class BaseTest {
	public static final Logger logger = LogManager.getLogger(BaseTest.class);
	protected IOContext ioContext = null;
	protected OrganizationContext orgContext = null;
	protected String organizationPath = "/Development";
	@Before
	public void setup() {
		/// NOTE: The current setup will throw an error if trying to deserialize a model whose schema has not yet been loaded.  This was done intentionally to only support intentionally loaded schemas
		///
		OlioModelNames.use();
		resetIO("jdbc:h2:./am7/h2", "sa", "1234");
	}
	
   
	protected void resetIO(String dataUrl, String dataUser, String dataPassword) {
		IOProperties props = new IOProperties();
		props.setDataSourceUrl(dataUrl);
		props.setDataSourceUserName(dataUser);
		props.setDataSourcePassword(dataPassword);
		props.setSchemaCheck(false);
		//resetH2DBUtil("./am7/h2", "sa", "1234", false);
		resetIO(RecordIO.DATABASE, props);
	}
	protected void resetIO(RecordIO ioType, IOProperties properties) {
		logger.info("Reset IO");
		clearIO();
		try {
			ioContext = IOSystem.open(ioType, properties);
		} catch (SystemException e) {
			logger.error(e);
		}
		// ioContext = IOSystem.open(RecordIO.FILE, storeName, null);
		OrganizationContext octx = ioContext.getOrganizationContext(organizationPath, OrganizationEnumType.DEVELOPMENT);
		assertNotNull("Context was null", octx);
		if(!octx.isInitialized()) {
			createOrg("/System", OrganizationEnumType.SYSTEM);
			createOrg("/Development", OrganizationEnumType.DEVELOPMENT);
			createOrg("/Public", OrganizationEnumType.PUBLIC);
		}
		else {
			logger.info("Working with existing organization " + organizationPath);
		}
		assertTrue("Expected org to be initialized", octx.isInitialized());
		orgContext = octx;
	}
	protected BaseRecord getCreateCredential(BaseRecord user) {
		return getCreateCredential(user, "password");
	}
	
	protected BaseRecord getCreateCredential(BaseRecord user, String pwd) {
		BaseRecord cred = CredentialUtil.getLatestCredential(user);
		if(cred == null) {
			ParameterList plist = ParameterUtil.newParameterList("password", pwd);
			plist.parameter(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
			try {
				cred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, user, null, plist);
				ioContext.getRecordUtil().createRecord(cred);
				cred = CredentialUtil.getLatestCredential(user);
			}
			catch(FactoryException e) {
				logger.error(e);
			}
		}
		return cred;
	}
	protected void createOrg(String path, OrganizationEnumType type) {
		logger.info("Creating organization " + path);
		OrganizationContext octx = ioContext.getOrganizationContext(path, type);
		try {
			octx.createOrganization();
			BaseRecord admin = octx.getAdminUser();
			ParameterList plist = ParameterUtil.newParameterList("password", "password");
			plist.parameter(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
			BaseRecord cred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, admin, null, plist);
			ioContext.getRecordUtil().createRecord(cred);
			logger.info("Created credential");
			logger.info(cred.toFullString());

		} catch (NullPointerException | SystemException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	protected void clearIO() {
		IOSystem.close();
		ioContext = null;
	}
	
	
	protected BaseRecord getCreateData(BaseRecord user, String name, String contentType, byte[] data, String path, long organizationId) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path, "DATA", organizationId);
		BaseRecord dat = ioContext.getRecordUtil().getRecord(user, ModelNames.MODEL_DATA, name, 0L, (long)dir.get(FieldNames.FIELD_ID), organizationId);
		if(dat == null) {
			dat = newData(user, name, contentType, data, path, organizationId);
			ioContext.getRecordUtil().createRecord(dat);
		}
		return dat;
	}
	protected BaseRecord newData(BaseRecord user, String name, String contentType, byte[] data, String path, long organizationId) {
		BaseRecord record = null;
		boolean error = false;
		try {
			record = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
			ioContext.getRecordUtil().applyNameGroupOwnership(user, record, name, path, organizationId);
			record.set(FieldNames.FIELD_CONTENT_TYPE, contentType);
			record.set(FieldNames.FIELD_BYTE_STORE, data);
		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		assertNotNull("Record is null", record);
		assertFalse("Encountered an error", error);
		return record;
	}

	protected BaseRecord getInferredOwnerPolicyFunction() {
		PolicyType record = JSONUtil.importObject(ResourceUtil.getInstance().getPolicyResource("ownerFunction"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete();
		FactType match = record.getRules().get(0).getPatterns().get(0).getMatch();
		//match.setSourceData(ownerPolicyFunction.getBytes());
		String policyFunction = ResourceUtil.getInstance().getFunctionResource("ownerPolicy");
		if(policyFunction == null) {
			logger.error("Failed to load ownerPolicyFunction.js");
		}
		match.setSourceData(policyFunction.getBytes());
		return record;
	}
	

	protected OrganizationContext getTestOrganization(String path) {
		OrganizationContext orgContext = ioContext.getOrganizationContext(path, OrganizationEnumType.DEVELOPMENT);
		if(!orgContext.isInitialized()) {
			/// The H2 fixture (am7/h2.mv.db) and the per-org Java key stores (am7/.jks/<orgId>/) are two
			/// gitignored files that only Factory.configureOrganizationStore ever writes, and only once, at
			/// organization creation. When they drift apart - the org row survives in H2 but its .jks dir is
			/// gone - OrganizationContext.initialize fails ("Key store does not exist"), createOrganization
			/// then throws "Organization already exists", and every vault-touching write in that org NPEs
			/// deep inside VaultService (seen 2026-10-12 for org 4, /Development/MediaUtilStreamTest).
			/// The files are fully derivable from persisted truth (the ops user's CA keyStore record and the
			/// credential holding the store password), so rebuild them from the DB instead of dropping the
			/// fixture.
			if(regenerateOrganizationStores(path, OrganizationEnumType.DEVELOPMENT)) {
				assertTrue("Regenerated key stores for " + path + " but the organization still failed to initialize",
					orgContext.initialize(path, OrganizationEnumType.DEVELOPMENT));
				return orgContext;
			}
			try {
				orgContext.createOrganization();
			} catch (SystemException e) {
				logger.error(e);
			}
		}
		return orgContext;
	}

	/// Rebuild am7/.jks/<orgId>/{keystore,truststore}.jks for an organization that already exists in the
	/// DB, from the same persisted state Factory.configureOrganizationStore wrote them from: the ops user's
	/// "<org> Certificate Authority Certificate" keyStore record (certificate + key set) and the
	/// ENCRYPTED_PASSWORD credential referencing it (the store password), read exactly the way
	/// OrganizationContext.initializeStores reads it at runtime. Returns false, without touching the
	/// filesystem, when the org does not exist (the caller should create it) or when any piece of the DB
	/// state is missing (then nothing can be derived and the caller's createOrganization will report the
	/// real problem). Never overwrites a store file that already exists.
	protected boolean regenerateOrganizationStores(String path, OrganizationEnumType type) {
		BaseRecord org = ioContext.getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, path, type.toString(), 0L);
		if(org == null) {
			return false;
		}
		long orgId = org.get(FieldNames.FIELD_ID);
		String storePath = IOFactory.DEFAULT_FILE_BASE + "/.jks/" + orgId;
		String kpath = storePath + "/keystore.jks";
		String tpath = storePath + "/truststore.jks";
		File kfile = new File(kpath);
		File tfile = new File(tpath);
		if(kfile.exists() && tfile.exists()) {
			logger.warn("Key stores for " + path + " (org " + orgId + ") already exist at " + storePath + "; not regenerating");
			return false;
		}

		BaseRecord ops = ioContext.getRecordUtil().getRecord(null, ModelNames.MODEL_USER, Factory.OPS_USER_NAME, 0L, 0L, orgId);
		if(ops == null) {
			logger.error("Cannot regenerate key stores for " + path + ": no " + Factory.OPS_USER_NAME + " user in org " + orgId);
			return false;
		}
		String alias = org.get(FieldNames.FIELD_NAME) + " Certificate Authority";
		BaseRecord ksRec = ioContext.getRecordUtil().getRecord(ops, ModelNames.MODEL_KEY_STORE, alias + " Certificate", -1L, "~/keyStore");
		if(ksRec == null) {
			logger.error("Cannot regenerate key stores for " + path + ": keyStore record '" + alias + " Certificate' not found for " + Factory.OPS_USER_NAME);
			return false;
		}

		Query query = QueryUtil.createQuery(ModelNames.MODEL_CREDENTIAL, FieldNames.FIELD_REFERENCE_TYPE, ModelNames.MODEL_KEY_STORE);
		query.field(FieldNames.FIELD_REFERENCE_ID, ksRec.get(FieldNames.FIELD_ID));
		query.field(FieldNames.FIELD_OWNER_ID, ops.get(FieldNames.FIELD_ID));
		query.requestMostFields();
		QueryResult res = null;
		try {
			res = ioContext.getSearch().find(query);
		} catch (ReaderException e) {
			logger.error(e);
			return false;
		}
		if(res == null || res.getResponse() != OperationResponseEnumType.SUCCEEDED || res.getResults().length != 1) {
			logger.error("Cannot regenerate key stores for " + path + ": expected exactly one store-password credential for keyStore id "
				+ ksRec.get(FieldNames.FIELD_ID) + ", found " + (res == null ? "null result" : res.getResults().length));
			return false;
		}
		byte[] pwdBytes = res.getResults()[0].get(FieldNames.FIELD_CREDENTIAL);
		if(pwdBytes == null || pwdBytes.length == 0) {
			logger.error("Cannot regenerate key stores for " + path + ": store-password credential is empty");
			return false;
		}
		String pwds = new String(pwdBytes);
		char[] pwd = pwds.toCharArray();

		ioContext.getRecordUtil().populate(ksRec, 3);
		KeyStoreBean ca = new KeyStoreBean(ksRec);
		CryptoBean cb = ca.getCryptoBean();
		if(cb == null || cb.getPublicKey() == null || cb.getPrivateKey() == null) {
			logger.error("Cannot regenerate key stores for " + path + ": CA key pair not loadable from keyStore id " + ksRec.get(FieldNames.FIELD_ID)
				+ " (keySet " + (cb == null ? "null" : cb.get(FieldNames.FIELD_ID)) + "); refusing to mint a new key pair");
			return false;
		}
		/// The certificate itself is NOT in the DB. crypto.keyStore.store is a readOnly blob, and
		/// StatementUtil.getUpdateTemplate skips readOnly fields on UPDATE, while KeyStoreUtil.getCreateStore
		/// creates the keyStore row (empty store) BEFORE generating the certificate and then updateRecord()s
		/// it - so the bytes are dropped and every keyStore row carries an empty store (measured 2026-10-12:
		/// 5 of 5 rows in this H2 fixture, 90 of 90 in Postgres am7db). The .jks files were the only copy.
		/// Re-derive it the same way getCreateStore does - a self-signed CA certificate over the PERSISTED
		/// key pair (CertificateUtil.generate with no signer) - without calling updateRecord, so the DB is
		/// not touched. Only the serial/validity differ from the lost original; nothing in this fixture
		/// chains off the org CA.
		if(ca.getCertificate() == null) {
			try {
				CertificateUtil.generate(ca, cb, null, ParameterUtil.newParameterList("cn", alias));
			} catch (Exception e) {
				logger.error(e);
				return false;
			}
			if(ca.getCertificate() == null) {
				logger.error("Cannot regenerate key stores for " + path + ": certificate generation over the persisted key pair returned nothing");
				return false;
			}
			logger.warn("Re-derived the CA certificate for " + path + " from the persisted key pair (the DB never held the original)");
		}

		logger.warn("Regenerating missing key stores for " + path + " (org " + orgId + ") at " + storePath
			+ " from keyStore id " + ksRec.get(FieldNames.FIELD_ID) + " (password length " + pwds.length() + ")");
		FileUtil.makePath(storePath);
		boolean ok = false;
		try {
			if(!kfile.exists()) {
				KeyStore ks = KeyStoreUtil.getCreateKeyStore(kpath, pwd);
				ok = ks != null && KeyStoreUtil.importCertificate(ks, ca.getCertificate().getEncoded(), alias) && KeyStoreUtil.saveKeyStore(ks, kpath, pwd);
				if(!ok) {
					logger.error("Failed to write " + kpath);
					return false;
				}
			}
			if(!tfile.exists()) {
				KeyStore kts = KeyStoreUtil.getCreateKeyStore(tpath, pwd);
				byte[] p12 = CertificateUtil.toPKCS12(ca, pwds);
				ok = kts != null && p12.length > 0 && KeyStoreUtil.importPKCS12(kts, p12, alias, pwd) && KeyStoreUtil.saveKeyStore(kts, tpath, pwd);
				if(!ok) {
					logger.error("Failed to write " + tpath);
					return false;
				}
			}
		} catch (CertificateEncodingException e) {
			logger.error(e);
			return false;
		}
		/// Prove the files load with the persisted password the same way initializeStores will.
		boolean loads = KeyStoreUtil.getKeyStore(kpath, pwd) != null && KeyStoreUtil.getKeyStore(tpath, pwd) != null;
		logger.warn("Regenerated key stores for " + path + ": keystore=" + kfile.length() + " bytes, truststore=" + tfile.length() + " bytes, load with persisted password=" + loads);
		return loads;
	}
	protected BaseRecord getCreateUser(String name) {
		return getCreateUser(name, orgContext);
	}
	protected BaseRecord getCreateUser(String name, OrganizationContext org) {
		Factory mf = ioContext.getFactory();
		return mf.getCreateUser(org.getAdminUser(), name, org.getOrganizationId());
	}
	
	
}


