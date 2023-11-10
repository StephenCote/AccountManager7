package org.cote.accountmanager.factory;

import java.lang.reflect.InvocationTargetException;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.model.field.KeyStoreBean;
import org.cote.accountmanager.provider.IProvider;
import org.cote.accountmanager.provider.ProviderUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.schema.type.UserEnumType;
import org.cote.accountmanager.schema.type.UserStatusEnumType;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.util.CertificateUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.KeyStoreUtil;
import org.cote.accountmanager.util.RecordUtil;

public class Factory {
	public static final Logger logger = LogManager.getLogger(Factory.class);
	
	public static final String ADMIN_USER_NAME = "admin";
	public static final String OPS_USER_NAME = "operations";
	public static final String VAULT_USER_NAME = "vault";
	public static final String DOCUMENT_CONTROL_USER_NAME = "documentControl";
	private Map<String,Class<?>> factories = new ConcurrentHashMap<>();
	private Map<String,IFactory> factoryInst = new ConcurrentHashMap<>();
	private Map<String, List<IFactory>> factoriesMap = new ConcurrentHashMap<>();
	
	private final IOContext context;
	public Factory(IOContext context) {
		this.context = context;
	}
	
	public BaseRecord getCreateDirectoryModel(BaseRecord user, String modelName, String name, String path, long organizationId) {
		if(path.startsWith("~/")) {
			BaseRecord homeDir = user.get(FieldNames.FIELD_HOME_DIRECTORY);
			
			String[] fields = RecordUtil.getPossibleFields(homeDir.getModel(), new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_ID, FieldNames.FIELD_TYPE, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_PARENT_ID});
			context.getRecordUtil().populate(homeDir, fields);
			
			String homePath = homeDir.get(FieldNames.FIELD_PATH);
			if(homePath == null || homePath.length() == 0) {
				logger.warn("Invalid home directory path - constructing from owner");
				homePath = "/home/" + user.get(FieldNames.FIELD_NAME);
			}
			path = homePath + path.substring(1);
		}
		BaseRecord dir = context.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path, "DATA", organizationId);
		BaseRecord per = context.getRecordUtil().getRecord(user, modelName, name, 0L, (long)dir.get(FieldNames.FIELD_ID), organizationId);
		if(per == null) {
			try {
				per = RecordFactory.model(modelName).newInstance();
				context.getRecordUtil().applyNameGroupOwnership(user, per, name, path, organizationId);
				context.getRecordUtil().createRecord(per);
			}
			catch(ModelNotFoundException | FieldException | ValueException e) {
				logger.error(e);
				
			}
		}
		return per;
	}
	
	public BaseRecord getAdminUser(long organizationId) {
		BaseRecord user = null;
		BaseRecord[] recs = new BaseRecord[0];
		try {
			recs = context.getSearch().findByName(ModelNames.MODEL_USER, ADMIN_USER_NAME, organizationId);
		} catch (IndexException | ReaderException e) {
			logger.error(e);
		}
		if(recs.length == 1) {
			user = recs[0];
		}
				
		return user;
	}
	
	/// The initial user create for an organization is out of band due to dependencies on the org, so it's slightly more manual and less recursive here
	///
	public synchronized BaseRecord makeOrganization(String path, OrganizationEnumType orgType, long organizationId) {
		BaseRecord org = context.getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, path, orgType.toString(), 0L);
		if(org != null) {
			return org;
		}

		logger.warn("*** Disable field validation for new organization");
		context.setEnforceValidation(false);
		
		logger.warn("*** Disable path authorization for new organization");
		context.setEnforceAuthorization(false);
		
		org = context.getPathUtil().makePath(null, ModelNames.MODEL_ORGANIZATION, path, orgType.toString(), 0L);
		long orgId = org.get(FieldNames.FIELD_ID);
		logger.info("Created " + org.get(FieldNames.FIELD_NAME) + " from " + path + " with id " + orgId);
		
		BaseRecord admin = getCreateUser(null, ADMIN_USER_NAME, null, orgId, true);
		emitRoles(org, admin);
		emitPermissions(org, admin);
		setupUser(admin, admin, null);
		
		BaseRecord ops = getCreateUser(admin, OPS_USER_NAME, null, orgId);
		BaseRecord vault = getCreateUser(admin, VAULT_USER_NAME, null, orgId);
		BaseRecord docControl = getCreateUser(admin, DOCUMENT_CONTROL_USER_NAME, null, orgId);
	
		configureOrganizationStore(ops, org);
		logger.warn("*** Re-Enable path authorization");
		context.setEnforceAuthorization(true);
		
		logger.warn("*** Re-Enable field validation");
		context.setEnforceValidation(true);
			
		return org;
	}
	
	private void configureOrganizationStore(BaseRecord owner, BaseRecord organization) {
		long orgId = organization.get(FieldNames.FIELD_ID);
		String storePath = IOFactory.DEFAULT_FILE_BASE + "/.jks/" + orgId;
		FileUtil.makePath(storePath);
		String kpath = storePath + "/keystore.jks";
		String tpath = storePath + "/truststore.jks";
		String pwds = UUID.randomUUID().toString();
		char[] pwd = pwds.toCharArray();
		String alias = organization.get(FieldNames.FIELD_NAME) + " Certificate Authority";
		KeyStoreBean ca = KeyStoreUtil.getCreateStore(owner, alias, null);
		KeyStore ks = KeyStoreUtil.getCreateKeyStore(kpath, pwd);
		KeyStore kts = KeyStoreUtil.getCreateKeyStore(tpath, pwd);
		try {
			KeyStoreUtil.importCertificate(ks, ca.getCertificate().getEncoded(), alias);
			KeyStoreUtil.saveKeyStore(ks, kpath, pwd);
			
			byte[] p12 = CertificateUtil.toPKCS12(ca, pwds);

			KeyStoreUtil.importPKCS12(kts, p12, alias, pwd);
			KeyStoreUtil.saveKeyStore(kts, tpath, pwd);
			
			BaseRecord cred = newInstance(ModelNames.MODEL_CREDENTIAL, owner, null, null, ca);
			cred.set(FieldNames.FIELD_TYPE, CredentialEnumType.ENCRYPTED_PASSWORD);
			cred.set(FieldNames.FIELD_CREDENTIAL, pwds.getBytes());
			context.getRecordUtil().createRecord(cred);
			
			
		} catch (CertificateEncodingException | FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
		}
	}
	
	private void setupUser(BaseRecord adminUser, BaseRecord user, BaseRecord personRec) {
		
		if(adminUser != null && user != null) {
			long orgId = adminUser.get(FieldNames.FIELD_ORGANIZATION_ID);
			BaseRecord roleHome = context.getPathUtil().makePath(adminUser, ModelNames.MODEL_ROLE, "/home/" + user.get(FieldNames.FIELD_NAME), "USER", orgId);
			BaseRecord permHome = context.getPathUtil().makePath(adminUser, ModelNames.MODEL_PERMISSION, "/home/" + user.get(FieldNames.FIELD_NAME), "USER", orgId);
			BaseRecord userHome = context.getPathUtil().findPath(adminUser, ModelNames.MODEL_GROUP, "/home/" + user.get(FieldNames.FIELD_NAME), "DATA", orgId);
			
			if(personRec != null) {
				setCRUEntitlement(adminUser, user, personRec, PermissionEnumType.DATA.toString());
			}
			
			setCRUEntitlement(adminUser, user, roleHome, PermissionEnumType.USER.toString());
			setCRUEntitlement(adminUser, user, roleHome, PermissionEnumType.DATA.toString());

			setCRUEntitlement(adminUser, user, permHome, PermissionEnumType.USER.toString());
			setCRUEntitlement(adminUser, user, permHome, PermissionEnumType.DATA.toString());
			
			setCRUEntitlement(adminUser, user, userHome, PermissionEnumType.GROUP.toString());
			setCRUEntitlement(adminUser, user, userHome, PermissionEnumType.DATA.toString());
			setCRUEntitlement(adminUser, user, user, PermissionEnumType.DATA.toString());
			
			if(!adminUser.equals(user)) {
				logger.debug("Assigning default roles");
				BaseRecord usersRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS, RoleEnumType.USER.toString(), orgId);
				BaseRecord requestersRole = AccessSchema.getSystemRole(AccessSchema.ROLE_REQUESTERS, RoleEnumType.USER.toString(), orgId);
				context.getMemberUtil().member(adminUser, usersRole, user, null, true);
				context.getMemberUtil().member(adminUser, requestersRole, user, null, true);
			}

			
			
		}
	}
	private void setCRUEntitlement(BaseRecord adminUser, BaseRecord user, BaseRecord obj, String entType) {
		String[] ptypes = new String[] {"Read", "Update", "Create"};
		for(String p : ptypes) {
			BaseRecord rperm1 = context.getPathUtil().findPath(adminUser, ModelNames.MODEL_PERMISSION, "/" + p, entType, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			if(rperm1 != null) {
				boolean mem = context.getMemberUtil().member(adminUser, obj, user, rperm1, true);
				// logger.info("Granting " + user.get(FieldNames.FIELD_NAME) + "/" + p + " to " + obj.get(FieldNames.FIELD_URN));
				if(!mem) {
					logger.warn("Failed to set member entitlement: " + p + " " + entType);
				}
			}
			else {
				logger.error("Failed to find perm " + p);
			}
		}

	}
	public BaseRecord getCreateUser(BaseRecord adminUser, String name, long organizationId) {
		BaseRecord group = null;
		if(adminUser != null) {
			group = context.getPathUtil().makePath(adminUser, ModelNames.MODEL_GROUP, "/home/" + name, "DATA", organizationId);
		}
		return getCreateUser(adminUser, name, group, organizationId);
	}
	public BaseRecord getCreateUser(BaseRecord adminUser, String name, BaseRecord group, long organizationId) {
		return getCreateUser(adminUser, name, group, organizationId, false);
	}
	private BaseRecord getCreateUser(BaseRecord adminUser, String name, BaseRecord group, long organizationId, boolean skipSetup) {
		BaseRecord rec = context.getRecordUtil().getRecord(null, ModelNames.MODEL_USER, name, 0L, 0L, organizationId);
		if(rec == null) {
			try {
				
				rec = RecordFactory.model(ModelNames.MODEL_USER).newInstance();
				rec.set(FieldNames.FIELD_NAME, name);
				rec.set(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
				if(adminUser != null) {
					rec.set(FieldNames.FIELD_OWNER_ID, adminUser.get(FieldNames.FIELD_ID));
				}
				rec.set(FieldNames.FIELD_STATUS, UserStatusEnumType.NORMAL.toString());
				rec.set(FieldNames.FIELD_TYPE, UserEnumType.NORMAL.toString());
				if(group == null) {
					group = context.getPathUtil().makePath(adminUser, ModelNames.MODEL_GROUP, "/home/" + name, "DATA", organizationId);
				}
				if(group != null) {
					rec.set(FieldNames.FIELD_HOME_DIRECTORY_FIELD_ID, group);
					rec.set(FieldNames.FIELD_HOME_DIRECTORY_FIELD_PATH, group);
				}
				context.getRecordUtil().createRecord(rec, true, true);
				
				BaseRecord pdir = context.getPathUtil().makePath(adminUser, ModelNames.MODEL_GROUP, "/Persons", "DATA", organizationId);
				BaseRecord prec = RecordFactory.model(ModelNames.MODEL_PERSON).newInstance();
				
				prec.set(FieldNames.FIELD_NAME, name);
				prec.set(FieldNames.FIELD_GROUP_ID, pdir.get(FieldNames.FIELD_ID));
				prec.set(FieldNames.FIELD_OWNER_ID, rec.get(FieldNames.FIELD_ID));
				prec.set(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
				List<BaseRecord> users = prec.get(FieldNames.FIELD_USERS);
				users.add(rec);
				context.getRecordUtil().createRecord(prec, true);
				if(!skipSetup) {
					setupUser(adminUser, rec, prec);
				}
			} catch (FieldException | ModelNotFoundException | ValueException  e) {
				logger.error(e.getMessage());
			}
			
		}
		return rec;
	}

	
	private void emitPermissions(BaseRecord org, BaseRecord admin) {
		for(String p :  AccessSchema.SYSTEM_PERMISSION_NAMES) {
			for(String s:  AccessSchema.SYSTEM_PERMISSION_TYPES) {
				context.getPathUtil().makePath(admin, ModelNames.MODEL_PERMISSION, "/" + p, s.toUpperCase(), org.get("id"));
			}
		}
	}
	
	private void emitRoles(BaseRecord org, BaseRecord admin) {
		for(String p : AccessSchema.SYSTEM_ROLE_NAMES) {
			context.getPathUtil().makePath(admin, ModelNames.MODEL_ROLE, "/" + p, "USER", org.get("id"));
		}
		
		BaseRecord admin1 = context.getPathUtil().findPath(null, ModelNames.MODEL_ROLE, "/" + AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR, "USER", org.get("id"));
		BaseRecord admin2 = context.getPathUtil().findPath(null, ModelNames.MODEL_ROLE, "/" + AccessSchema.ROLE_DATA_ADMINISTRATOR, "USER", org.get("id"));
		BaseRecord admin3 = context.getPathUtil().findPath(null, ModelNames.MODEL_ROLE, "/" + AccessSchema.ROLE_MODEL_ADMINISTRATOR, "USER", org.get("id"));
		context.getMemberUtil().member(admin, admin1, admin, null, true);
		context.getMemberUtil().member(admin, admin2, admin, null, true);
		context.getMemberUtil().member(admin, admin3, admin, null, true);
	}
	
	

	
	public void clearCache() {
		factories.clear();
		factoryInst.clear();
		factoriesMap.clear();
	}

	private BaseRecord defaultFactoryInstance(String modelName) {
		BaseRecord rec = null;
		try {
			rec = RecordFactory.newInstance(modelName);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		return rec;
	}
	/*
	public BaseRecord newInstance(String modelName, BaseRecord... arguments) throws FactoryException {
		IFactory fact = getFactory(modelName);
		BaseRecord rec = null;
		if(fact != null) {
			rec = fact.newInstance(arguments); 
		}
		else {
			rec = defaultFactoryInstance(modelName);
		}
		List<IFactory> facts = getFactories(modelName);
		for(int i = 1; i < facts.size(); i++) {
			IFactory ifact = facts.get(i);
			if(ifact != null) {
				ifact.implement(rec, arguments);
			}
		}
		
		/// Call implementation factory implement last to allow to override inherited values (eg: expiration date adjustment defaults)
		if(facts.size() > 0) {
			// logger.info("Invoke primary implement");
			if(facts.get(0) != null) {
				facts.get(0).implement(rec,  arguments);
			}
		}
		return rec;
	}
	
	
	public BaseRecord newInstance(String modelName) throws FactoryException {
		return newInstance(modelName, null);
	}
	*/
	public BaseRecord newInstance(String modelName) throws FactoryException {
		return newInstance(modelName, null, (BaseRecord)null, null);
	}
	public BaseRecord newInstance(String modelName, BaseRecord contextUser) throws FactoryException {
		return newInstance(modelName, contextUser, (BaseRecord)null, null);
	}
	public BaseRecord newTemplateInstance(String modelName, BaseRecord contextUser, String recordTemplate, ParameterList parameterList) throws FactoryException {
		// logger.info("New template instance - no args");
		return newInstance(modelName, contextUser, (recordTemplate != null ? template(modelName, recordTemplate) : null), parameterList, (BaseRecord)null);
	}
	public BaseRecord newTemplateInstance(String modelName, BaseRecord contextUser, String recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		// logger.info("New template instance - with args");
		return newInstance(modelName, contextUser, (recordTemplate != null ? template(modelName, recordTemplate) : null), parameterList, arguments);
	}
	public BaseRecord newInstance(String modelName, BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList) throws FactoryException {
		// logger.info("New instance - no args");
		return newInstance(modelName, contextUser, recordTemplate, parameterList, (BaseRecord)null);
	}
	public BaseRecord newInstance(String modelName, BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		// logger.info("New Instance: " + arguments.length);
		IFactory fact = getFactory(modelName);
		BaseRecord rec = null;
		if(fact != null) {
			rec = fact.newInstance(contextUser, recordTemplate, parameterList, arguments); 
		}
		else {
			rec = defaultFactoryInstance(modelName);
		}
		List<IFactory> facts = getFactories(modelName);
		for(int i = 1; i < facts.size(); i++) {
			IFactory ifact = facts.get(i);
			if(ifact != null) {
				// logger.info("Invoke implement: " + ifact.getClass());
				ifact.implement(contextUser, rec, parameterList, arguments);
			}
		}
		
		/// Call implementation factory implement last to allow to override inherited values (eg: expiration date adjustment defaults)
		if(facts.size() > 0) {
			//logger.info("Invoke primary implement");
			if(facts.get(0) != null) {
				facts.get(0).implement(contextUser, rec, parameterList, arguments);
			}
		}
		ModelSchema lbm = RecordFactory.getSchema(rec.getModel());
		for(int i = 0; i < rec.getFields().size(); i++) {
			FieldType f = rec.getFields().get(i);
			FieldSchema lf = lbm.getFieldSchema(f.getName());
			if(lf.getProvider() != null && lf.getProvider().length() > 0) {
				IProvider prov = ProviderUtil.getProviderInstance(lf.getProvider());
				try {
					prov.provide(contextUser, RecordOperation.NEW, lbm, rec, lf, f);
				} catch (ModelException | FieldException | ValueException | ModelNotFoundException | ReaderException e) {
					logger.error(e);
					
				}
			}
		}
		/*
		try {
			(new MemoryReader()).read(rec);
		}
		catch(ReaderException e) {
			logger.error(e);
		}
		*/
		
		return rec;
	}
	
	
	public VerificationEnumType verify(BaseRecord rec) throws FactoryException {
		return verify(null, rec, null);
	}

	public VerificationEnumType verify(BaseRecord contextUser, BaseRecord rec, ParameterList parameterList) throws FactoryException {
		return verify(contextUser, rec, parameterList, (BaseRecord)null);
	}
	public VerificationEnumType verify(BaseRecord contextUser, BaseRecord rec, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		// logger.info("New Instance: " + arguments.length);
		IFactory fact = getFactory(rec.getModel());
		VerificationEnumType vet = VerificationEnumType.NOT_VERIFIED;
		if(fact != null) {
			vet = fact.verify(contextUser, rec, parameterList, arguments); 
		}
		return vet;
	}
	
	
	public IFactory getFactory(String modelName) throws FactoryException{
		List<IFactory> factories = new ArrayList<>();
		ModelSchema schema = RecordFactory.getSchema(modelName);
		if(schema == null) {
			throw new FactoryException(String.format("Null model for %s", modelName));
		}
		/*
		if(modelName.equals(ModelNames.MODEL_AUDIT)) {
			logger.info("CHECK: " + modelName + " " + schema.getName());
		}
		*/
		return getFactory(schema, "org.cote.accountmanager.factory.GenericFactory");
	}
	
	private List<IFactory> getFactories(String modelName) throws FactoryException{
		if(factoriesMap.containsKey(modelName)) {
			return factoriesMap.get(modelName);
		}
		List<IFactory> factories = new ArrayList<>();
		Set<String> factSet = new HashSet<>();
		ModelSchema schema = RecordFactory.getSchema(modelName);
		if(schema == null) {
			throw new FactoryException(String.format("Null model for %s", modelName));
		}
		
		//IFactory fact = getFactory(schema);
		factSet.add(modelName);
		factories.add(getFactory(schema));
		for(String s : schema.getImplements()){
			ModelSchema ischema = RecordFactory.getSchema(s);
			if(!factSet.contains(ischema.getName())) {
				factories.add(getFactory(ischema));
				factSet.add(ischema.getName());
			}
		};
		
		//logger.info("*** " + modelName);
		/*
		factories.forEach(f -> {
			logger.info("Factory: " + (f != null ? f.getSchema().getName() : "null"));
		});
		*/
		factoriesMap.put(modelName, factories);
		return factories;
	}
	private IFactory getFactory(ModelSchema schema) throws FactoryException {
		return getFactory(schema, null);
	}
	private IFactory getFactory(ModelSchema schema, String defFactCls) throws FactoryException {
		String factCls = schema.getFactory();
		if(factCls == null) {
			if(defFactCls != null) {
				factCls = defFactCls;
			}
			else {
				// 	logger.warn("Model does not define a factory");
				return null;
				// factCls = "org.cote.accountmanager.factory.GenericFactory";
			}
		}
		
		IFactory fact = getFactoryInstance(factCls, schema);
		if(fact == null) {
			throw new FactoryException(String.format("Null factory instance for %s", factCls));
		}
		if(!fact.getSchema().getName().equals(schema.getName())) {
			throw new FactoryException("Incorrect factory (" + fact.getSchema().getName() + ") for " + schema.getName());
		}
		/*
		schema.getImplements().forEach(l -> {
			logger.info(l);
		});
		*/
		return fact;

	}
	
	private IFactory getFactoryInstance(String className, ModelSchema schema){
		Class<?> cls = getFactoryClass(className);
		IFactory oper = null;
		if(cls == null){
			logger.error(className + " is not defined");
			return null;
		}
		
		if(factoryInst.containsKey(schema.getName())) return factoryInst.get(schema.getName());
		try {
			oper = (IFactory)cls.getDeclaredConstructor(Factory.class, ModelSchema.class).newInstance(this, schema);
			factoryInst.put(schema.getName(), oper);

		} catch (InstantiationException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
			logger.error(e);
		}
		return oper;
	}
	
	private Class<?> getFactoryClass(String className){
		// logger.info("Pull " + className);
		if(factories.containsKey(className)) return factories.get(className);
		Class<?> cls = null;
		try {
			if(className.endsWith(".js")) {
				// logger.info("Use ScriptFactory: " + className);
				cls = ScriptFactory.class;
			}
			else {
				cls = Class.forName(className);
			}
			factories.put(className, cls);
		} catch (ClassNotFoundException e) {
			
			logger.error(e);
		}
		return cls;
	}
	
	public BaseRecord template(String modelName, String templateConstruct) {
		String tmp = templateConstruct.trim();
		if(!tmp.startsWith("{")) {
			tmp = "{" + tmp + "}";
		}
		return RecordFactory.importRecord(modelName, tmp);
	}
	


	
}
