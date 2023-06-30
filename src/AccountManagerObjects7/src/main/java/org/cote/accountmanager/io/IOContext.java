package org.cote.accountmanager.io;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.client.AccessPoint;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.io.file.FileIndexManager;
import org.cote.accountmanager.io.file.FileStore;
import org.cote.accountmanager.policy.PolicyDefinitionUtil;
import org.cote.accountmanager.policy.PolicyEvaluator;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.AuthorizationUtil;
import org.cote.accountmanager.util.MemberUtil;
import org.cote.accountmanager.util.RecordUtil;

public class IOContext {
	
	public static final Logger logger = LogManager.getLogger(IOContext.class);
	
	private IReader reader = null;
	private IWriter writer = null;
	private ISearch search = null;
	private FileStore store = null;
	private boolean initialized = false;
	private Factory factory = null;
	private FileIndexManager indexManager = null;
	private RecordIO ioType;
	private IPath pathUtil = null;
	private MemberUtil memberUtil = null;
	private RecordUtil recordUtil = null;
	private PolicyUtil policyUtil = null;
	private AuthorizationUtil authorizationUtil = null;
	private PolicyEvaluator policyEvaluator = null;
	private PolicyDefinitionUtil policyDefinitionUtil = null;
	private String guid = null;
	private DBUtil dbUtil = null;
	private AccessPoint accessPoint = null;
	private boolean enforceAuthorization = true;
	private boolean enforceValidation = true;
	
	private Map<String, OrganizationContext> organizations = new ConcurrentHashMap<>();
	
	public IOContext() {
		this.ioType = RecordIO.UNKNOWN;
		this.guid = UUID.randomUUID().toString();
	}
	
	public IOContext(RecordIO ioType, IReader reader, IWriter writer, ISearch search) {
		this();
		this.reader = reader;
		this.writer = writer;
		this.search = search;
		this.ioType = ioType;
		
		//recordUtil = IOFactory.getRecordUtil(reader, writer, search);

		pathUtil = IOFactory.getPathUtil(this);
		recordUtil = IOFactory.getRecordUtil(this);
		
		memberUtil = IOFactory.getMemberUtil(this);
		authorizationUtil = IOFactory.getAuthorizationUtil(this);
		policyUtil = IOFactory.getPolicyUtil(this);
		
		factory = new Factory(this);
		policyEvaluator = new PolicyEvaluator(this);
		policyDefinitionUtil = new PolicyDefinitionUtil(this);

		this.accessPoint = new AccessPoint(this);

		initialized = true;
	}

	public AccessPoint getAccessPoint() {
		return accessPoint;
	}
	
	public boolean isEnforceValidation() {
		return enforceValidation;
	}

	public void setEnforceValidation(boolean enforceValidation) {
		this.enforceValidation = enforceValidation;
	}

	public void setEnforceAuthorization(boolean enforce) {
		enforceAuthorization = enforce;
	}
	
	public boolean isEnforceAuthorization() {
		return enforceAuthorization;
	}
	
	public DBUtil getDbUtil() {
		return dbUtil;
	}

	public void setDbUtil(DBUtil dbUtil) {
		this.dbUtil = dbUtil;
	}

	public String getGuid() {
		return guid;
	}

	public void close() {
		IOSystem.close(this);
		if(indexManager != null) {
			indexManager.clearCache();
		}
		initialized = false;
	}
	
	public BaseRecord loadModel(String modelName) {
		BaseRecord rec = RecordFactory.model(modelName);
		if(rec != null && indexManager != null && ioType == RecordIO.FILE) {
			/// Invoke getInstance to make sure the indexer is loaded for the model type
			///
			indexManager.getInstance(modelName);
		}
		return rec;
	}
	
	
	public PolicyDefinitionUtil getPolicyDefinitionUtil() {
		return policyDefinitionUtil;
	}

	public PolicyEvaluator getPolicyEvaluator() {
		return policyEvaluator;
	}
	public OrganizationContext findOrganizationContext(BaseRecord rec) {
		String orgPath = rec.get(FieldNames.FIELD_ORGANIZATION_PATH);
		
		OrganizationContext ctx = null;
		if(orgPath != null) {
			ctx = getOrganizationContext(orgPath, null);
		}
		else {
			long orgId = rec.get(FieldNames.FIELD_ORGANIZATION_ID);
			BaseRecord org = recordUtil.getRecordById(null, ModelNames.MODEL_ORGANIZATION, orgId);
			if(org != null) {
				ctx = getOrganizationContext(org.get(FieldNames.FIELD_PATH), null);
			}
		}
		return ctx;
	}
	public OrganizationContext getOrganizationContext(String orgPath, OrganizationEnumType orgType) {
		if(organizations.containsKey(orgPath)) {
			return organizations.get(orgPath);
		}
		OrganizationContext octx = new OrganizationContext(this);
		octx.initialize(orgPath, orgType);
		organizations.put(orgPath, octx);
		return octx;
	}
	
	

	public FileIndexManager getIndexManager() {
		return indexManager;
	}

	public void setIndexManager(FileIndexManager indexManager) {
		this.indexManager = indexManager;
	}

	public void setStore(FileStore store) {
		this.store = store;
	}

	public Factory getFactory() {
		return factory;
	}

	public boolean isInitialized() {
		return initialized;
	}

	public RecordIO getIoType() {
		return ioType;
	}

	public PolicyUtil getPolicyUtil() {
		return policyUtil;
	}

	public IReader getReader() {
		return reader;
	}

	public IWriter getWriter() {
		return writer;
	}

	public ISearch getSearch() {
		return search;
	}

	public FileStore getStore() {
		return store;
	}

	public IPath getPathUtil() {
		return pathUtil;
	}

	public MemberUtil getMemberUtil() {
		return memberUtil;
	}

	public RecordUtil getRecordUtil() {
		return recordUtil;
	}

	public AuthorizationUtil getAuthorizationUtil() {
		return authorizationUtil;
	}
	
	

	

}
