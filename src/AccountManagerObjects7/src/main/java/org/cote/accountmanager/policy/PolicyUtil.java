package org.cote.accountmanager.policy;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IPath;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.PolicyDefinitionType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelAccess;
import org.cote.accountmanager.schema.ModelAccessRoles;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.SystemPermissionEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class PolicyUtil {
	public static final Logger logger = LogManager.getLogger(PolicyUtil.class);
	
	/*
	 * Resource Tokens:
	 * 	${contextUser} - urn - addressed in FactUtil for Pattern.fact.sourceUrn
	 *  ${actorUrn} - urn
	 *  ${actorType} - modelType
	 *  ${modelRole} - role access defined at the model schema level
	 *  ${modelRoleType} - role type
	 *  ${resourceUrn} - urn
	 *  ${resourceType} - modelType
	 *  ${resourceGroupUrn} - group urn
	 *  ${resourceParentUrn} - parent urn
	 *  ${rule.name} - normalized relative resource reference
	 *  ${pattern.name} - normalized relative resource reference
	 *  ${fact.name} - normalized relative resource reference
	 *  ${error} - marker used to track issues with dynamic policy construction via resource
	 */

	
	private Pattern actorExp = Pattern.compile("\\$\\{actorUrn\\}");
	private Pattern modelRoleExp = Pattern.compile("\\$\\{modelRole\\}");
	private Pattern actorTypeExp = Pattern.compile("\\$\\{actorType\\}");
	private Pattern resourceExp = Pattern.compile("\"\\$\\{resource\\}\"");
	private Pattern resourceParentExp = Pattern.compile("\"\\$\\{resourceParent\\}\"");
	private Pattern resourceGroupExp = Pattern.compile("\"\\$\\{resourceGroup\\}\"");
	private Pattern resourceUrnExp = Pattern.compile("\\$\\{resourceUrn\\}");
	private Pattern resourceTypeExp = Pattern.compile("\\$\\{resourceType\\}");
	private Pattern resourceGroupUrnExp = Pattern.compile("\\$\\{resourceGroupUrn\\}");
	private Pattern resourceParentUrnExp = Pattern.compile("\\$\\{resourceParentUrn\\}");
	private Pattern ruleResourceExp = Pattern.compile("\"\\$\\{rule\\.([A-Za-z0-9]+)\\}\"");
	private Pattern patternResourceExp = Pattern.compile("\"\\$\\{pattern\\.([A-Za-z0-9]+)\\}\"");
	private Pattern factResourceExp = Pattern.compile("\"\\$\\{fact\\.([A-Za-z0-9]+)\\}\"");
	private Pattern errorExp = Pattern.compile("\\$\\{error\\}");
	
	public static final String POLICY_SYSTEM_CREATE_OBJECT = "systemCreateObject";
	public static final String POLICY_SYSTEM_READ_OBJECT = "systemReadObject";
	public static final String POLICY_SYSTEM_UPDATE_OBJECT = "systemUpdateObject";
	public static final String POLICY_SYSTEM_DELETE_OBJECT = "systemDeleteObject";
	public static final String POLICY_SYSTEM_EXECUTE_OBJECT = "systemExecuteObject";
	
	private static Map<String, SystemPermissionEnumType> policyNameMap = new HashMap<>();
	private static Map<SystemPermissionEnumType, String> permissionNameMap = new HashMap<>();
	
	static {
		policyNameMap.put(POLICY_SYSTEM_DELETE_OBJECT, SystemPermissionEnumType.DELETE);
		policyNameMap.put(POLICY_SYSTEM_CREATE_OBJECT, SystemPermissionEnumType.CREATE);
		policyNameMap.put(POLICY_SYSTEM_READ_OBJECT, SystemPermissionEnumType.READ);
		policyNameMap.put(POLICY_SYSTEM_EXECUTE_OBJECT, SystemPermissionEnumType.EXECUTE);
		policyNameMap.put(POLICY_SYSTEM_UPDATE_OBJECT, SystemPermissionEnumType.UPDATE);
		permissionNameMap = policyNameMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
	}
	
	private final IReader reader;
	private final IWriter writer;
	private final ISearch search;
	private final IPath pathUtil;
	
	private enum ResourceType {
		FACT,
		PATTERN,
		RULE
	};
	
	private boolean trace = false;
	
	public PolicyUtil(IReader reader, IWriter writer, ISearch search) {
		this.reader = reader;
		this.writer = writer;
		this.search = search;
		this.pathUtil = IOFactory.getPathUtil(reader, writer, search);
	}
	
	public PolicyUtil(IOContext context) {
		this.reader = context.getReader();
		this.writer = context.getWriter();
		this.search = context.getSearch();
		this.pathUtil = context.getPathUtil();
	}
	
	
	public boolean isTrace() {
		return trace;
	}

	public void setTrace(boolean trace) {
		IOSystem.getActiveContext().getPolicyEvaluator().setTrace(trace);
		IOSystem.getActiveContext().getAuthorizationUtil().setTrace(trace);
		this.trace = trace;
	}
	
	public void close() {
		
	}
	
	public boolean getPolicyResponseExpired(BaseRecord policyResponse) {
		boolean outBool = true;
		if(policyResponse == null) {
			logger.error("Invalid policy response object");
			return outBool;
		}
		long now = System.currentTimeMillis();
		Date expiryDate = policyResponse.get(FieldNames.FIELD_EXPIRY_DATE);
		if(expiryDate == null) {
			logger.error("Invalid expiry date");
			return outBool;
		}
		if(expiryDate.getTime() <= now) {
			logger.warn("Policy response has expired");
		}
		else {
			outBool = false;
		}
		return outBool;
	}
	
	public PolicyRequestType getPolicyRequest(PolicyType policy, BaseRecord contextUser) {
		return getPolicyRequest(policy, contextUser, null);
	}
	public PolicyRequestType getPolicyRequest(PolicyType policy, BaseRecord contextUser, BaseRecord param1) {
		PolicyRequestType preq = null;
		PolicyDefinitionUtil pdu = new PolicyDefinitionUtil(reader, search);
		try {
			PolicyDefinitionType pdef = pdu.generatePolicyDefinition(policy).toConcrete();
			preq = new PolicyRequestType(pdu.generatePolicyRequest(pdef));
			preq.setContextUser(contextUser);
			if(param1 != null) {
				preq.getFacts().get(0).setSourceUrn(param1.get("urn"));
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
			
		}
		return preq;
	}
	
	public boolean evaluateQueryToReadPolicy(BaseRecord contextUser, Query query) {
		boolean outBool = true;
		PolicyResponseType[] prrs = evaluateQueryToReadPolicyResponses(contextUser, query);
		if(prrs.length == 0) {
			logger.warn("No policy responses for " + query.key());
			outBool = false;
		}
		else {
			for(PolicyResponseType prr : prrs) {
				if(prr.getType() != PolicyResponseEnumType.PERMIT) {
					logger.error("Policy response was not permitted for " + query.key());
					logger.error(prr.toString());
					outBool = false;
				}
			}
		}
		return outBool;
	}
	
	public PolicyResponseType[] evaluateQueryToReadPolicyResponses(BaseRecord contextUser, Query query) {
		List<PolicyResponseType> prrs = new ArrayList<>();
		// List<Long> ids = QueryUtil.findFieldValues(query, FieldNames.FIELD_ID, 0L);
		// List<Long> groupIds = QueryUtil.findFieldValues(query, FieldNames.FIELD_GROUP_ID, 0L);
		// List<Long> parentIds = QueryUtil.findFieldValues(query, FieldNames.FIELD_PARENT_ID, 0L);
		//List<Long> urns = QueryUtil.findFieldValues(query, FieldNames.FIELD_URN, null);
		ModelSchema ms = RecordFactory.getSchema(query.get(FieldNames.FIELD_TYPE));
		try {
			/*
			for(Long l : groupIds) {
				BaseRecord group = reader.read(ModelNames.MODEL_GROUP, l);
				prrs.add(evaluateResourcePolicy(contextUser, POLICY_SYSTEM_READ_OBJECT, contextUser, group));
			}
			for(Long l : parentIds) {
				BaseRecord par = reader.read(query.get(FieldNames.FIELD_TYPE), l);
				prrs.add(evaluateResourcePolicy(contextUser, POLICY_SYSTEM_READ_OBJECT, contextUser, par));
			}
			*/
			Set<String> querySet = new HashSet<>();
			for(FieldSchema fs : ms.getFields()) {
				if(fs.isIdentity() || fs.isRecursive()) {
					List<?> vals = QueryUtil.findFieldValues(query, fs.getName(), null);
					String propName = fs.getName();
					String type = query.get(FieldNames.FIELD_TYPE);
					if(fs.getBaseModel() != null && !fs.getBaseModel().equals(ModelNames.MODEL_SELF) && !fs.getBaseModel().equals(ModelNames.MODEL_FLEX)) {
						type = fs.getBaseModel();
						if(fs.getBaseProperty() != null) {
							propName = fs.getBaseProperty();
						}
					}
					
					for(Object x : vals) {
						/// query.get(FieldNames.FIELD_TYPE)
						Query sq = QueryUtil.createQuery(type, propName, x);
						sq.set(FieldNames.FIELD_INSPECT, true);
						if(!querySet.contains(sq.key())) {
							querySet.add(sq.key());
							QueryResult qr = search.find(sq);
							for(BaseRecord cr : qr.getResults()) {
								/// logger.info("Evaluate: " + fs.getName() + " / " + propName + " " + cr.get(FieldNames.FIELD_URN));
								prrs.add(evaluateResourcePolicy(contextUser, POLICY_SYSTEM_READ_OBJECT, contextUser, cr));
							}
						}
					}
				}
			}

		}
		catch(ReaderException | IndexException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return prrs.toArray(new PolicyResponseType[0]);
	}
	
	public boolean executePermitted(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_EXECUTE_OBJECT, actor, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	public boolean deletePermitted(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_DELETE_OBJECT, actor, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	public boolean updatePermitted(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_UPDATE_OBJECT, actor, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	public boolean createPermitted(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_CREATE_OBJECT, actor, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	public boolean readPermitted(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_READ_OBJECT, actor, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	
	public PolicyResponseType evaluateResourcePolicy(BaseRecord contextUser, String policyName, String actorType, String actorUrn, String resourceType, String resourceUrn) {
		BaseRecord actor = null;
		BaseRecord resource = null;
		try {
			actor = reader.readByUrn(actorType, actorUrn);
			resource = reader.readByUrn(resourceType, resourceUrn);
		} catch (ReaderException e) {
			logger.error(e);
			
		}
		return evaluateResourcePolicy(contextUser, policyName, actor, resource);
	}
	
	public PolicyResponseType evaluateResourcePolicy(BaseRecord contextUser, String policyName, BaseRecord actor, BaseRecord resource) {
		PolicyType pol = null;
		PolicyRequestType preq = null;
		PolicyResponseType prr = null;

		PolicyEvaluator pe = IOSystem.getActiveContext().getPolicyEvaluator();

		try {
			pol = getResourcePolicy(policyName, actor, resource).toConcrete();
			preq = getPolicyRequest(pol, contextUser, actor);
			prr = pe.evaluatePolicyRequest(preq, pol).toConcrete();
			// logger.info(JSONUtil.exportObject(preq, RecordSerializerConfig.getUnfilteredModule()));
			// logger.info(JSONUtil.exportObject(prr, RecordSerializerConfig.getUnfilteredModule()));
		}
		catch(ReaderException | FieldException | ModelNotFoundException | ValueException | ScriptException | IndexException | ModelException e) {
			logger.error(e);
			
		}
		return prr;
	}
	public SystemPermissionEnumType getSystemPermissionFromPolicyName(String name) {
		return policyNameMap.get(name);
		/*
		SystemPermissionEnumType spet = SystemPermissionEnumType.UNKNOWN;
		if(POLICY_SYSTEM_CREATE_OBJECT.equals(name)) {
			spet = SystemPermissionEnumType.CREATE;
		}
		else if(POLICY_SYSTEM_UPDATE_OBJECT.equals(name)) {
			spet = SystemPermissionEnumType.UPDATE;
		}
		else if(POLICY_SYSTEM_READ_OBJECT.equals(name)) {
			spet = SystemPermissionEnumType.READ;
		}
		else if(POLICY_SYSTEM_DELETE_OBJECT.equals(name)) {
			spet = SystemPermissionEnumType.DELETE;
		}
		else if(POLICY_SYSTEM_EXECUTE_OBJECT.equals(name)) {
			spet = SystemPermissionEnumType.EXECUTE;
		}
		return spet;
		*/
	}
	
	public String getPolicyName(FieldSchema fs, SystemPermissionEnumType spet) {
		return permissionNameMap.get(spet);
	}
	

	
	public List<BaseRecord> getSchemaRules(BaseRecord actor, SystemPermissionEnumType spet, BaseRecord object){
		List<BaseRecord> rules = new ArrayList<>();
		/// Look through the supplied object schema
		/// If it's foreign, or defines a modelAccess, then create a rule that includes a modelAccess pattern for that specific role or permission
		ModelSchema schema = RecordFactory.getSchema(object.getModel());
		//for(FieldSchema fs : schema.getFields()) {
		for(FieldType f : object.getFields()) {
			List<BaseRecord> patterns = new ArrayList<>(); 
			FieldSchema fs = schema.getFieldSchema(f.getName());
			List<String> roles = getSchemaRoles(fs.getAccess(), spet);
			if(
				(fs.isForeign() || roles.size() > 0)
				&&
				!f.isNullOrEmpty(object.getModel())
			){
				// logger.warn("Add rule for " + fs.getName());
				String patternStr = null;
				BaseRecord linkedObj = null;
				if(fs.isForeign()) {
					// String policyName = getPolicyName(fs, SystemPermissionEnumType.READ);
					// logger.info("Map: " + policyName);
					patternStr = applyResourcePattern(factResourceExp, ResourceType.FACT, ResourceUtil.getPatternResource("resourceReadAccess"));
					linkedObj = object.get(f.getName());
					if(!linkedObj.hasField(FieldNames.FIELD_URN)) {
						reader.populate(linkedObj);
					}
					if(RecordUtil.isIdentityRecord(linkedObj)) {
						// logger.info("Apply resource pattern to " + linkedObj.toString());
						patternStr = applyResourcePattern(patternStr, linkedObj);
						patternStr = applyActorPattern(patternStr, actor);
	
						BaseRecord pattern = JSONUtil.importObject(patternStr, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
						if(pattern == null) {
							logger.error("Invalid pattern");
							logger.error(patternStr);
							continue;
						}
						patterns.add(pattern);
					}
					else {
						logger.warn("Skip " + fs.getName() + " because it does not have an identity value and therefore cannot be checked for system level read access.");
					}
				}
				for(String r : roles) {
					BaseRecord pattern = getModelAccessPattern(actor, r);
					if(pattern != null) {
						patterns.add(pattern);
					}
				}
				if(patterns.size() > 0) {
					String ruleTemplate = ResourceUtil.getRuleResource("genericAll");
					BaseRecord rule = JSONUtil.importObject(ruleTemplate, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
					List<BaseRecord> rpats = rule.get(FieldNames.FIELD_PATTERNS);
					rpats.addAll(patterns);
					rules.add(rule);
				}
			}
			else {
				// logger.warn("Don't add because " + spet.toString() + " " + fs.getName() + " = " + fs.isForeign() + " / " + roles.size() + " / " + f.isNullOrEmpty());
			}
		}
		return rules;
	}
	
	public List<String> getSchemaRoles(ModelAccess access, SystemPermissionEnumType spet){
		List<String> roles = new ArrayList<>();
		if(access != null && access.getRoles() != null) {
			ModelAccessRoles mar = access.getRoles();
			switch(spet) {
				case CREATE:
					roles = mar.getCreate();
					break;
				case READ:
					roles = mar.getRead();
					break;
				case UPDATE:
					roles = mar.getUpdate();
					break;
				case DELETE:
					roles = mar.getDelete();
					break;
				case EXECUTE:
					roles = mar.getExecute();
					break;
				default:
					break;
			}
		}
		return roles;
	}
	public BaseRecord getModelAccessPattern(BaseRecord actor, String roleName) {
		String accessPattern = ResourceUtil.getPatternResource("modelAccess");

		accessPattern = applyResourcePattern(factResourceExp, ResourceType.FACT, accessPattern);
		if(!roleName.startsWith("/")) {
			roleName = "/" + roleName;
		}
		accessPattern = applyActorPattern(accessPattern, actor);
		Matcher	m = modelRoleExp.matcher(accessPattern);
		accessPattern = m.replaceAll(roleName);
		
		BaseRecord rec = JSONUtil.importObject(accessPattern, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if(rec == null) {
			logger.error("Warning: Failed to parse pattern");
			logger.error(accessPattern);
		}
		return rec;
	}
	public List<BaseRecord> getModelAccessPatternList(BaseRecord actor, SystemPermissionEnumType spet, BaseRecord object) {
		List<BaseRecord> patterns = new ArrayList<>();
		ModelSchema schema = RecordFactory.getSchema(object.getModel());
		List<String> roles = getSchemaRoles(schema.getAccess(), spet);
		if(roles.size() > 0) {
			
			// String accessPattern = ResourceUtil.getPatternResource("modelAccess");
			for(String r : roles) {
				BaseRecord pattern = getModelAccessPattern(actor, r);
				if(pattern != null) {
					patterns.add(pattern);
				}
				/*
				String tmpPattern = accessPattern;
				tmpPattern = applyResourcePattern(factResourceExp, ResourceType.FACT, tmpPattern);
				String roleName = r;
				if(!r.startsWith("/")) {
					roleName = "/" + r;
				}
				tmpPattern = applyActorPattern(tmpPattern, actor);

				Matcher 
				m = modelRoleExp.matcher(tmpPattern);
				tmpPattern = m.replaceAll(roleName);
				
				BaseRecord rec = JSONUtil.importObject(tmpPattern, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
				if(rec != null) {
					patterns.add(rec);
				}
				else {
					logger.error("Warning: Failed to parse pattern");
					logger.error(tmpPattern);
				}
				*/
				
			}
		}
		
		
		return patterns;
	}
	
	private String applyResourcePattern(Pattern p, ResourceType recType, String content) {
		String outStr = content;
		Matcher f = p.matcher(outStr);
		while(f.find()) {
			if(f.groupCount() > 0) {
				String recName = f.group(1);
				String str = null;
				if(recType == ResourceType.FACT) {
					str = ResourceUtil.getFactResource(recName);
				}
				else if(recType == ResourceType.PATTERN) {
					str = ResourceUtil.getPatternResource(recName);
				}
				else if(recType == ResourceType.RULE) {
					str = ResourceUtil.getRuleResource(recName);
				}
				if(str == null) {
					str = "${error}";
				}
				outStr = f.replaceFirst(Matcher.quoteReplacement(str));
				f = p.matcher(outStr);
			}
			else {
				logger.error("Error on match: " + f.toMatchResult().toString());
			}
			
		}
		return outStr;

	}
	
	private String applyActorPattern(String contents, BaseRecord actor) {
		Matcher m = actorExp.matcher(contents);
		String actUrn = actor.get(FieldNames.FIELD_URN);
		String outStr = m.replaceAll(actUrn);
		
		m = actorTypeExp.matcher(outStr);
		outStr = m.replaceAll(actor.getModel());

		
		return outStr;
	}
	
	private String applyResourcePattern(String contents, BaseRecord resource) {
		Matcher m = resourceExp.matcher(contents);
		String outStr = m.replaceAll("null");
		
		m = resourceUrnExp.matcher(outStr);
		String recUrn = resource.get(FieldNames.FIELD_URN);
		outStr = m.replaceAll((recUrn != null ? recUrn : ""));
		
		m = resourceTypeExp.matcher(outStr);
		outStr = m.replaceAll(resource.getModel());
		
		return outStr;

	}
	
	/// Note: actor is for object types other than the contextUser, including other users, persons, and accounts.
	///
	public BaseRecord getResourcePolicy(String name, BaseRecord actor, BaseRecord resource) throws ReaderException {

		String policyBase = ResourceUtil.getPolicyResource(name);
		BaseRecord rec = null;
		if(policyBase == null) {
			logger.error("Invalid policy resource name: " + name);
			return rec;
		}
		policyBase = applyResourcePattern(ruleResourceExp, ResourceType.RULE, policyBase);
		policyBase = applyResourcePattern(patternResourceExp, ResourceType.PATTERN, policyBase);
		policyBase = applyResourcePattern(factResourceExp, ResourceType.FACT, policyBase);

		/*
		Matcher m = resourceUrnExp.matcher(policyBase);
		String recUrn = resource.get(FieldNames.FIELD_URN);
		//if(recUrn != null) {
		policyBase = m.replaceAll((recUrn != null ? recUrn : ""));
		//}
		
		m = resourceTypeExp.matcher(policyBase);
		policyBase = m.replaceAll(resource.getModel());
		*/
		policyBase = applyActorPattern(policyBase, actor);
		policyBase = applyResourcePattern(policyBase, resource);
		/*
		m = actorExp.matcher(policyBase);
		String actUrn = actor.get(FieldNames.FIELD_URN);
		policyBase = m.replaceAll(actUrn);
		
		m = resourceExp.matcher(policyBase);
		policyBase = m.replaceAll("null");
		*/
		
		Matcher m = resourceGroupExp.matcher(policyBase);
		policyBase = m.replaceAll("null");
		
		m = resourceParentExp.matcher(policyBase);
		policyBase = m.replaceAll("null");
		/*
		m = actorTypeExp.matcher(policyBase);
		policyBase = m.replaceAll(actor.getModel());
		*/
		Matcher g = resourceGroupUrnExp.matcher(policyBase);
		
		boolean removeGroupUrn = true;
		boolean removeParentUrn = false;
		
		if(g.find()) {
			if(resource.inherits(ModelNames.MODEL_DIRECTORY)) {
				BaseRecord grp = null;
				if(resource.hasField(FieldNames.FIELD_GROUP_PATH) && resource.get(FieldNames.FIELD_GROUP_PATH) != null) {
					// logger.info("Resolve resource by groupPath: " + resource.get(FieldNames.FIELD_GROUP_PATH));
					grp = pathUtil.findPath(null, ModelNames.MODEL_GROUP, resource.get(FieldNames.FIELD_GROUP_PATH), GroupEnumType.DATA.toString(), resource.get(FieldNames.FIELD_ORGANIZATION_ID));
				}
				else if(resource.hasField(FieldNames.FIELD_GROUP_ID)) {
					//logger.info("Resolve resource by groupId: " + resource.get(FieldNames.FIELD_GROUP_ID));
					grp = reader.read(ModelNames.MODEL_GROUP, (long)resource.get(FieldNames.FIELD_GROUP_ID));
				}
				if(grp != null) {
					policyBase = g.replaceAll((String)grp.get(FieldNames.FIELD_URN));
				}
				else {
					logger.error("Group could not be found");
					logger.error(resource.toString());
				}
			}
			else {
				removeGroupUrn = true;
			}
		}
		else {
			removeGroupUrn = true;
		}
		
		Matcher p = resourceParentUrnExp.matcher(policyBase);
		
		if(p.find()) {
			if(resource.inherits(ModelNames.MODEL_PARENT)) {
	
				BaseRecord par = null;
				if(resource.hasField(FieldNames.FIELD_PARENT_ID)) {
					par = reader.read(resource.getModel(), (long)resource.get(FieldNames.FIELD_PARENT_ID));
				}
				else if(resource.inherits(ModelNames.MODEL_PATH) && resource.hasField(FieldNames.FIELD_PATH)) {
					String path = resource.get(FieldNames.FIELD_PATH);
					if(path != null && path.lastIndexOf("/") > 0) {
						path = path.substring(path.lastIndexOf("/"));
						par = pathUtil.findPath(null, resource.getModel(), path, null, resource.get(FieldNames.FIELD_ORGANIZATION_ID));
					}
				}
				if(par != null) {
					policyBase = p.replaceAll((String)par.get(FieldNames.FIELD_URN));
				}
				else {
					removeParentUrn = true;					
				}
			}
			else {
				removeParentUrn = true;
			}
		}
		
		Matcher e = errorExp.matcher(policyBase);
		if(e.find()) {
			logger.error("Policy contains one or more errors and cannot be processed");
			logger.error(policyBase);
			return null;
		}
		
		if(trace) {
			logger.info(policyBase);
		}

		rec = JSONUtil.importObject(policyBase, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if(rec == null) {
			logger.error("Failed to import policy");
			logger.error(policyBase);
			return null;			
		}
		
		SystemPermissionEnumType spet = getSystemPermissionFromPolicyName(name);

		List<BaseRecord> modelPatterns = getModelAccessPatternList(actor, spet, resource);
		if(modelPatterns.size() > 0) {
			List<BaseRecord> rules = rec.get(FieldNames.FIELD_RULES);
			if(rules.size() > 0) {
				List<BaseRecord> patterns = rules.get(0).get(FieldNames.FIELD_PATTERNS);
				// logger.info("Injecting " + modelPatterns.size() + " model access patterns");
				patterns.addAll(modelPatterns);
			}
		}

		List<BaseRecord> rules = rec.get(FieldNames.FIELD_RULES);
		List<BaseRecord> schemaRules = getSchemaRules(actor, spet, resource);
		rules.addAll(schemaRules);
		if(removeGroupUrn || removeParentUrn) {
			removeUnusedRules(rules, removeGroupUrn, removeParentUrn);
		}
		
		return rec;
		
	}
	
	public BaseRecord getInferredOwnerPolicyFunction() {
		PolicyType record = JSONUtil.importObject(ResourceUtil.getPolicyResource("ownerFunction"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete();
		FactType match = record.getRules().get(0).getPatterns().get(0).getMatch();
		String policyFunction = ResourceUtil.getFunctionResource("ownerPolicy");
		if(policyFunction == null) {
			logger.error("Failed to load ownerPolicyFunction.js");
		}
		else {
			match.setSourceData(policyFunction.getBytes());
		}
		return record;
	}

	public BaseRecord getReadPolicy(String urn) {
		PolicyType record = JSONUtil.importObject(ResourceUtil.getPolicyResource("readObject"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete();
		FactType match = record.getRules().get(0).getPatterns().get(0).getMatch();
		match.setSourceUrn(urn);
		return record;
	}

	public BaseRecord getAdminPolicy(String urn) {
		PolicyType record = JSONUtil.importObject(ResourceUtil.getPolicyResource("adminRole"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete();
		FactType match = record.getRules().get(0).getPatterns().get(0).getMatch();
		match.setSourceUrn(urn);
		return record;
	}
	
	
	private void removeUnusedRules(List<BaseRecord> prules, boolean removeGroupUrn, boolean removeParentUrn) {
		for(BaseRecord r : prules) {
			List<BaseRecord> rules = r.get(FieldNames.FIELD_RULES);
			List<BaseRecord> patterns = r.get(FieldNames.FIELD_PATTERNS);
			removeUnusedRules(rules, removeGroupUrn, removeParentUrn);
			List<BaseRecord> npatterns = patterns.stream().filter(o -> filterUnusedPattern(o, removeGroupUrn, removeParentUrn)).collect(Collectors.toList());
			patterns.clear();
			patterns.addAll(npatterns);
		}
	}
	
	private boolean filterUnusedPattern(BaseRecord pattern, boolean removeGroupUrn, boolean removeParentUrn) {
		BaseRecord fact = pattern.get(FieldNames.FIELD_FACT);
		BaseRecord match = pattern.get(FieldNames.FIELD_MATCH);
		return (filterFact(fact, removeGroupUrn, removeParentUrn) && filterFact(match, removeGroupUrn, removeParentUrn));
	}
	
	private boolean filterFact(BaseRecord fact, boolean removeGroupUrn, boolean removeParentUrn) {
		boolean outBool = true;
		if(fact.hasField(FieldNames.FIELD_SOURCE_URN)) {
			String fsurn = fact.get(FieldNames.FIELD_SOURCE_URN);
			if(fsurn != null && (
				(removeGroupUrn && resourceGroupUrnExp.matcher(fsurn).find())
				||
				(removeParentUrn && resourceParentUrnExp.matcher(fsurn).find())
			)) {
				outBool = false;
			}
		}
		return outBool;
	}

	
	
}
