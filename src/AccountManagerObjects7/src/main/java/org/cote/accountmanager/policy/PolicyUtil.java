package org.cote.accountmanager.policy;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
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
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.OperationType;
import org.cote.accountmanager.objects.generated.PatternType;
import org.cote.accountmanager.objects.generated.PolicyDefinitionType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.objects.generated.RuleType;
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
import org.cote.accountmanager.schema.type.FactEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.SystemPermissionEnumType;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.FieldUtil;
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

	private Pattern permissionExp = Pattern.compile("\"\\$\\{permission\\}\"");
	private Pattern tokenExp = Pattern.compile("\"\\$\\{token\\}\"");
	private Pattern binaryTokenExp = Pattern.compile("\"\\$\\{binaryToken\\}\"");
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
	
	
	private static Map<String, SystemPermissionEnumType> policyNameMap = new ConcurrentHashMap<>();
	private static Map<SystemPermissionEnumType, String> permissionNameMap = new ConcurrentHashMap<>();
	
	static {
		policyNameMap.put(POLICY_SYSTEM_DELETE_OBJECT, SystemPermissionEnumType.DELETE);
		policyNameMap.put(POLICY_SYSTEM_CREATE_OBJECT, SystemPermissionEnumType.CREATE);
		policyNameMap.put(POLICY_SYSTEM_READ_OBJECT, SystemPermissionEnumType.READ);
		policyNameMap.put(POLICY_SYSTEM_EXECUTE_OBJECT, SystemPermissionEnumType.EXECUTE);
		policyNameMap.put(POLICY_SYSTEM_UPDATE_OBJECT, SystemPermissionEnumType.UPDATE);
		permissionNameMap = policyNameMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
	}
	
	private Map<String, String> policyBaseMap = new ConcurrentHashMap<>();
	
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
	
	public static boolean isPermit(BaseRecord prt) {
		return (prt != null && prt.getEnum(FieldNames.FIELD_TYPE) == PolicyResponseEnumType.PERMIT);
	}
	
	public static void addResponseMessage(BaseRecord prt, String msg) {
		List<String> messages = prt.get("messages");
		messages.add(msg);
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
	
	/// TODO: Need to check that the query plan associated with a query matches the query fields
	///
	public PolicyResponseType[] evaluateQueryToReadPolicyResponses(BaseRecord contextUser, Query query) {
		List<PolicyResponseType> prrs = new ArrayList<>();
		ModelSchema ms = RecordFactory.getSchema(query.get(FieldNames.FIELD_TYPE));
		try {
			Set<String> querySet = new HashSet<>();
			/// Look through fields with an access model or identity fields and compare against the values being searched for
			/// 
			for(FieldSchema fs : ms.getFields()) {
				if((fs.isIndex() && RecordUtil.isConstrainedByField(query, fs.getName()) && fs.isDynamicPolicy()) || (fs.getAccess() != null && fs.getAccess().getRoles() != null) || fs.isIdentity() || fs.isRecursive()) {
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
						Query sq = QueryUtil.createQuery(type, propName, x);
						sq.setRequest(RecordUtil.getPossibleFields(type, PolicyEvaluator.FIELD_POPULATION));
						if(fs.isIndex()) {
							List<String> constraints = RecordUtil.getConstraints(query, propName);
							if(constraints.size() > 0) {
								if(trace) {
									logger.info("Checking constrained indexed field " + fs.getName() + " / " + propName + " / " + constraints.stream().collect(Collectors.joining(",")));
								}
								for(String c : constraints) {
									if(trace) {
										logger.info("Constraining: " + c + " = " + QueryUtil.findFieldValue(query, c, null));
									}
									sq.field(c, QueryUtil.findFieldValue(query, c, null));
								}
							}
							else {
								if(trace) {
									logger.info("Skipping unconstrained check on indexed field " + fs.getName());
								}
							}
						}
						sq.set(FieldNames.FIELD_INSPECT, true);
						if(!querySet.contains(sq.key())) {
							querySet.add(sq.key());
							if(trace) {
								logger.info("Scanning (" + fs.getName() + ") " + query.key() + " --> " + sq.key() + " from " + type + "." + propName + "=" + x);
							}
							QueryResult qr = search.find(sq);
							for(BaseRecord cr : qr.getResults()) {
								prrs.add(evaluateResourcePolicy(contextUser, POLICY_SYSTEM_READ_OBJECT, contextUser, query.get(FieldNames.FIELD_TOKEN), cr));
							}
						}
					}
				}

			}
			
			if(prrs.size() == 0 && RecordUtil.isConstrained(query)) {
				logger.warn("**** Try direct testing for a constrained query");
			}
			
			if(prrs.size() == 0) {
				if(getSchemaRoles(ms.getAccess(), SystemPermissionEnumType.READ).size() > 0) {
					if(trace) {
						logger.warn("*** Evaluate system policy for coarse model level read");
					}
					BaseRecord tmpRec = IOSystem.getActiveContext().getFactory().newInstance(query.get(FieldNames.FIELD_TYPE));
					prrs.add(evaluateResourcePolicy(contextUser, POLICY_SYSTEM_READ_OBJECT, contextUser, null, tmpRec));
				}
				else {
					if(trace) {
						logger.info("Zero PRRs found");
						logger.info(query.toFullString());
					}
				}
			}
			else {
				if(trace) {
					logger.info("PRR Count: " + prrs.size());
				}
			}

		}
		catch(ReaderException | FieldException | ValueException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		return prrs.toArray(new PolicyResponseType[0]);
	}
	
	public boolean executePermitted(BaseRecord contextUser, BaseRecord actor, String token, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_EXECUTE_OBJECT, actor, token, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	public boolean deletePermitted(BaseRecord contextUser, BaseRecord actor, String token, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_DELETE_OBJECT, actor, token, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	public boolean updatePermitted(BaseRecord contextUser, BaseRecord actor, String token, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_UPDATE_OBJECT, actor, token, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	public boolean createPermitted(BaseRecord contextUser, BaseRecord actor, String token, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_CREATE_OBJECT, actor, token, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	public boolean readPermitted(BaseRecord contextUser, BaseRecord actor, String token, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, POLICY_SYSTEM_READ_OBJECT, actor, token, resource).getType() == PolicyResponseEnumType.PERMIT;
	}
	
	public PolicyResponseType evaluateResourcePolicy(BaseRecord contextUser, String policyName, String actorType, String actorUrn, String token, String resourceType, String resourceUrn) {
		BaseRecord actor = null;
		BaseRecord resource = null;
		try {
			actor = reader.readByUrn(actorType, actorUrn);
			resource = reader.readByUrn(resourceType, resourceUrn);
		} catch (ReaderException e) {
			logger.error(e);
			
		}
		return evaluateResourcePolicy(contextUser, policyName, actor, token, resource);
	}
	public PolicyResponseType evaluateResourcePolicy(BaseRecord contextUser, String policyName, BaseRecord actor, BaseRecord resource) {
		return evaluateResourcePolicy(contextUser, policyName, actor, null, resource);
	
	}
	public PolicyResponseType evaluateResourcePolicy(BaseRecord contextUser, String policyName, BaseRecord actor, String accessToken, BaseRecord resource) {
		PolicyType pol = null;
		PolicyRequestType preq = null;
		PolicyResponseType prr = null;
		PolicyEvaluator pe = IOSystem.getActiveContext().getPolicyEvaluator();

		try {
			pol = getResourcePolicy(policyName, actor, accessToken, resource).toConcrete();
			preq = getPolicyRequest(pol, contextUser, actor);
			prr = pe.evaluatePolicyRequest(preq, pol).toConcrete();
		}
		catch(ReaderException | FieldException | ModelNotFoundException | ValueException | ScriptException | IndexException | ModelException e) {
			logger.error(e);
			
		}
		return prr;
	}
	public SystemPermissionEnumType getSystemPermissionFromPolicyName(String name) {
		if(policyNameMap.containsKey(name)) {
			return policyNameMap.get(name);
		}
		return SystemPermissionEnumType.CREATE;
	}
	
	public String getPolicyName(FieldSchema fs, SystemPermissionEnumType spet) {
		return permissionNameMap.get(spet);
	}
	private List<PatternType> getForeignPatterns(BaseRecord actor, SystemPermissionEnumType spet, BaseRecord object, FieldType f, FieldSchema fs){
		List<PatternType> patterns = new ArrayList<>();
		if(spet == SystemPermissionEnumType.DELETE) {
			logger.debug("Skip policy check for deleting object with foreign reference");
		}
		else {
			List<BaseRecord> objects = new ArrayList<>();
			if(fs.getFieldType() == FieldEnumType.LIST) {
				objects = object.get(f.getName());
			}
			else if(fs.getFieldType() == FieldEnumType.MODEL) {
				objects.add(object.get(f.getName()));
			}
			else {
				logger.error("Unhandled field type: " + fs.getFieldType().toString());
			}
			for(BaseRecord linkedObj : objects) {
				if(!linkedObj.hasField(FieldNames.FIELD_URN) || linkedObj.get(FieldNames.FIELD_URN) == null) {
					reader.populate(linkedObj, RecordUtil.getPossibleFields(linkedObj.getSchema(), PolicyEvaluator.FIELD_POPULATION));
				}

				if(RecordUtil.isIdentityRecord(linkedObj)) {
					try {
						PolicyType recPolicy = this.getResourcePolicy(POLICY_SYSTEM_READ_OBJECT, actor, null, linkedObj).toConcrete();
						patterns.addAll(recPolicy.getRules().get(0).getPatterns());
					}
					catch(ReaderException e) {
						logger.error(e);
					}
				}
				else {
					logger.debug("Skip " + fs.getName() + " because it does not have an identity value and therefore cannot be checked for system level read access.");
				}
			}

		}
		return patterns;
	}
	
	public List<BaseRecord> getSchemaRules(BaseRecord actor, SystemPermissionEnumType spet, BaseRecord object){
		List<BaseRecord> rules = new ArrayList<>();
		if(object == null) {
			return rules;
		}
		
		/// Look through the supplied object schema
		/// If it's foreign, or defines a modelAccess, then create a rule that includes a modelAccess pattern for that specific role or permission
		ModelSchema schema = RecordFactory.getSchema(object.getSchema());
		for(int i = 0; i < object.getFields().size(); i++) {
			FieldType f = object.getFields().get(i);
			List<BaseRecord> patterns = new ArrayList<>(); 
			FieldSchema fs = schema.getFieldSchema(f.getName());
			List<String> roles = getSchemaRoles(fs.getAccess(), spet);
			if(
				((fs.isForeign() && fs.isFollowReference()) || roles.size() > 0)
				&&
				!f.isNullOrEmpty(object.getSchema())
			){
				if(trace) {
					logger.info("Add rule for " + fs.getName());
				}
				if(fs.isForeign()) {
					if(spet == SystemPermissionEnumType.DELETE) {
						logger.debug("Skip policy check for deleting object with foreign reference");
					}
					else {
						if(trace) {
							logger.info("Add " + spet.toString() + " foreign access pattern for " + object.getSchema() + "." + f.getName());
						}
						patterns.addAll(getForeignPatterns(actor, spet, object, f, fs));
					}
				}
				if(trace) {
					logger.info("Add " + spet.toString() + " " + roles.size() + " roles to access pattern for " + object.getSchema() + "." + f.getName());
				}
				for(String r : roles) {
					if(trace) {
						logger.info("Add " + spet.toString() + " role " + r + " access pattern for " + object.getSchema() + "." + f.getName());
					}
					BaseRecord pattern = getModelAccessPattern(actor, r);
					if(pattern != null) {
						patterns.add(pattern);
					}
				}
				if(patterns.size() > 0) {
					String ruleTemplate = ResourceUtil.getInstance().getRuleResource("genericOr");
					BaseRecord rule = JSONUtil.importObject(ruleTemplate, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
					List<BaseRecord> rpats = rule.get(FieldNames.FIELD_PATTERNS);
					rpats.addAll(patterns);
					rules.add(rule);
				}
			}
			else {
				if(trace) {
					// logger.warn("Ignore sub-schema rule because " + spet.toString() + " " + fs.getName() + " = " + fs.isForeign() + " / " + roles.size() + " / " + f.isNullOrEmpty(object.getModel()));
				}
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
	private BaseRecord getModelAccessPattern(BaseRecord actor, String roleName) {
		String accessPattern = ResourceUtil.getInstance().getPatternResource("modelAccess");

		accessPattern = applyResourcePattern(ResourceUtil.getInstance(), factResourceExp, ResourceType.FACT, accessPattern);
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
		if(object == null) {
			return patterns;
		}
		ModelSchema schema = RecordFactory.getSchema(object.getSchema());
		List<String> roles = getSchemaRoles(schema.getAccess(), spet);
		if(roles.size() > 0) {
			for(String r : roles) {
				BaseRecord pattern = getModelAccessPattern(actor, r);
				if(pattern != null) {
					patterns.add(pattern);
				}
			}
		}
		
		
		return patterns;
	}
	
	private String applyResourcePattern(ResourceUtil resourceUtil, Pattern p, ResourceType recType, String content) {
		String outStr = content;
		Matcher f = p.matcher(outStr);
		while(f.find()) {
			if(f.groupCount() > 0) {
				String recName = f.group(1);
				String str = null;
				if(recType == ResourceType.FACT) {
					str = resourceUtil.getFactResource(recName);
				}
				else if(recType == ResourceType.PATTERN) {
					str = resourceUtil.getPatternResource(recName);
				}
				else if(recType == ResourceType.RULE) {
					str = resourceUtil.getRuleResource(recName);
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

	private String applyPermissionPattern(String contents, String name) {
		
		SystemPermissionEnumType spet = getSystemPermissionFromPolicyName(name);

		Matcher m = permissionExp.matcher(contents);
		String outStr = null;
		if(spet != SystemPermissionEnumType.UNKNOWN) {
			String perm1 = spet.toString();
			outStr = m.replaceAll("\"/" + perm1.substring(0,1) + perm1.toLowerCase().substring(1) + "\"");
		}
		else {
			outStr = m.replaceAll("null");
		}
		return outStr;
	}
	
	private String applyTokenPattern(String contents, String token) {
		Matcher m = tokenExp.matcher(contents);
		String outStr = null;
		if(token != null && token.length() > 0) {
			outStr = m.replaceAll("\"" + token + "\"");
		}
		else {
			outStr = m.replaceAll("null");
		}
		
		m = binaryTokenExp.matcher(outStr);
		if(token != null && token.length() > 0) {
			outStr = m.replaceAll("\"" + BinaryUtil.toBase64Str(token) + "\"");
		}
		else {
			outStr = m.replaceAll("\"\"");
		}
		return outStr;
	}
	
	private String applyActorPattern(String contents, BaseRecord actor) {
		String outStr = contents;
		if(actor.hasField(FieldNames.FIELD_URN)) {
			Matcher m1 = actorExp.matcher(contents);
			String actUrn = actor.get(FieldNames.FIELD_URN);
			outStr = m1.replaceAll(actUrn);
		}
		Matcher m2 = actorTypeExp.matcher(outStr);
		outStr = m2.replaceAll(actor.getSchema());
		
		return outStr;
	}
	
	private String applyResourcePattern(String contents, BaseRecord resource) {
		Matcher m = resourceExp.matcher(contents);
		String outStr = m.replaceAll("null");
		
		m = resourceUrnExp.matcher(outStr);
		String recUrn = null;
		if(resource != null) {
			if(resource.hasField(FieldNames.FIELD_URN)) {
				recUrn = resource.get(FieldNames.FIELD_URN);
			}
			else if(resource.hasField(FieldNames.FIELD_ID) && ((long)resource.get(FieldNames.FIELD_ID)) > 0L) {
				recUrn = Long.toString(resource.get(FieldNames.FIELD_ID));
			}
		}
		outStr = m.replaceAll((recUrn != null ? recUrn : ""));
		
		m = resourceTypeExp.matcher(outStr);
		outStr = m.replaceAll((resource != null ? resource.getSchema() : ""));
		
		return outStr;

	}
	public String getPolicyBase(String resourceName) {
		return getPolicyBase(ResourceUtil.getInstance(), resourceName);
	}
	public String getPolicyBase(ResourceUtil resourceUtil, String resourceName) {
		if(policyBaseMap.containsKey(resourceName)) {
			return policyBaseMap.get(resourceName);
		}
		
		String policyBase = resourceUtil.getPolicyResource(resourceName);
		if(policyBase == null) {
			return null;
		}
		policyBase = applyResourcePattern(resourceUtil, ruleResourceExp, ResourceType.RULE, policyBase);
		policyBase = applyResourcePattern(resourceUtil, patternResourceExp, ResourceType.PATTERN, policyBase);
		policyBase = applyResourcePattern(resourceUtil, factResourceExp, ResourceType.FACT, policyBase);
		
		policyBaseMap.put(resourceName, policyBase);
		
		return policyBase;
	}
	
	/// Note: actor is for object types other than the contextUser, including other users, persons, and accounts.
	///
	public BaseRecord getResourcePolicy(String name, BaseRecord actor, String token, BaseRecord resource) throws ReaderException {
		return getResourcePolicy(ResourceUtil.getInstance(), name, actor, token, resource);
	}
	
	public BaseRecord getResourcePolicy(ResourceUtil resourceUtil, String name, BaseRecord actor, String token, BaseRecord resource) throws ReaderException {
		String policyBase = getPolicyBase(resourceUtil, name);
		BaseRecord rec = null;
		if(policyBase == null) {
			logger.error("Invalid policy resource name: " + name);
			return rec;
		}

		policyBase = applyActorPattern(policyBase, actor);
		policyBase = applyResourcePattern(policyBase, resource);
		policyBase = applyTokenPattern(policyBase, token);
		policyBase = applyPermissionPattern(policyBase, name);
		
		Matcher m = resourceGroupExp.matcher(policyBase);
		policyBase = m.replaceAll("null");
		
		m = resourceParentExp.matcher(policyBase);
		policyBase = m.replaceAll("null");

		Matcher g = resourceGroupUrnExp.matcher(policyBase);
		
		boolean removeGroupUrn = true;
		boolean removeParentUrn = false;
		
		if(resource != null && g.find()) {
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
		
		if(resource != null && p.find()) {
			if(resource.inherits(ModelNames.MODEL_PARENT)) {
	
				BaseRecord par = null;
				if(resource.hasField(FieldNames.FIELD_PARENT_ID)) {
					par = reader.read(resource.getSchema(), (long)resource.get(FieldNames.FIELD_PARENT_ID));
				}
				else if(resource.inherits(ModelNames.MODEL_PATH) && resource.hasField(FieldNames.FIELD_PATH)) {
					String path = resource.get(FieldNames.FIELD_PATH);
					if(path != null && path.lastIndexOf("/") > 0) {
						path = path.substring(path.lastIndexOf("/"));
						par = pathUtil.findPath(null, resource.getSchema(), path, null, resource.get(FieldNames.FIELD_ORGANIZATION_ID));
					}
				}
				if(par != null && resource.hasField(FieldNames.FIELD_URN) && !FieldUtil.isNullOrEmpty(par.getSchema(), par.getField(FieldNames.FIELD_URN))) {
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
				patterns.addAll(modelPatterns);
			}
		}

		List<BaseRecord> rules = rec.get(FieldNames.FIELD_RULES);
		
		List<BaseRecord> schemaRules = getSchemaRules(actor, spet, resource);
		if(trace) {
			logger.info("Adding " + schemaRules.size() + " dynamic rules");
		}
		rules.addAll(schemaRules);
		
		if(removeGroupUrn || removeParentUrn || token == null) {
			removeUnusedRules(rules, removeGroupUrn, removeParentUrn, (token == null));
		}

		return rec;
		
	}
	
	public BaseRecord getInferredOwnerPolicyFunction() {
		PolicyType record = JSONUtil.importObject(ResourceUtil.getInstance().getPolicyResource("ownerFunction"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete();
		FactType match = record.getRules().get(0).getPatterns().get(0).getMatch();
		String policyFunction = ResourceUtil.getInstance().getFunctionResource("ownerPolicy");
		if(policyFunction == null) {
			logger.error("Failed to load ownerPolicyFunction.js");
		}
		else {
			match.setSourceData(policyFunction.getBytes());
		}
		return record;
	}

	public BaseRecord getReadPolicy(String urn) {
		PolicyType record = JSONUtil.importObject(ResourceUtil.getInstance().getPolicyResource("readObject"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete();
		FactType match = record.getRules().get(0).getPatterns().get(0).getMatch();
		match.setSourceUrn(urn);
		return record;
	}

	public BaseRecord getAdminPolicy(String urn) {
		PolicyType record = JSONUtil.importObject(ResourceUtil.getInstance().getPolicyResource("adminRole"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete();
		FactType match = record.getRules().get(0).getPatterns().get(0).getMatch();
		match.setSourceUrn(urn);
		return record;
	}
	
	
	private void removeUnusedRules(List<BaseRecord> prules, boolean removeGroupUrn, boolean removeParentUrn, boolean removeToken) {
		for(BaseRecord r : prules) {
			List<BaseRecord> rules = r.get(FieldNames.FIELD_RULES);
			List<BaseRecord> patterns = r.get(FieldNames.FIELD_PATTERNS);
			removeUnusedRules(rules, removeGroupUrn, removeParentUrn, removeToken);
			List<BaseRecord> npatterns = patterns.stream().filter(o -> filterUnusedPattern(o, removeGroupUrn, removeParentUrn, removeToken)).collect(Collectors.toList());
			patterns.clear();
			patterns.addAll(npatterns);
		}
	}
	
	private boolean filterUnusedPattern(BaseRecord pattern, boolean removeGroupUrn, boolean removeParentUrn, boolean removeToken) {
		BaseRecord fact = pattern.get(FieldNames.FIELD_FACT);
		BaseRecord match = pattern.get(FieldNames.FIELD_MATCH);
		return (filterFact(fact, removeGroupUrn, removeParentUrn, removeToken) && filterFact(match, removeGroupUrn, removeParentUrn, removeToken));
	}
	
	private boolean filterFact(BaseRecord fact, boolean removeGroupUrn, boolean removeParentUrn, boolean removeToken) {
		boolean outBool = true;
		if(fact == null) {
			return outBool;
		}
		if(fact.hasField(FieldNames.FIELD_SOURCE_URN)) {
			String fsurn = fact.get(FieldNames.FIELD_SOURCE_URN);
			if(
				fsurn != null && (
						(removeGroupUrn && resourceGroupUrnExp.matcher(fsurn).find())
						||
						(removeParentUrn && resourceParentUrnExp.matcher(fsurn).find())
				)
			) {
				outBool = false;
			}
		}
		if(fact.hasField(FieldNames.FIELD_FACT_DATA_TYPE)) {
			String fdt = fact.get(FieldNames.FIELD_FACT_DATA_TYPE);
			if(removeToken && fdt != null && fdt.equals("token")) {
				outBool = false;
			}
			
		}
		return outBool;
	}
	
	public String printPattern(PatternType pattern, int depth) {
		StringBuilder buff = new StringBuilder();
		StringBuilder baseTabBuff = new StringBuilder();
		for(int i = 0; i < depth; i++) baseTabBuff.append("\t");
		String baseTab = baseTabBuff.toString();
		String tab = baseTab.toString() + "\t";
		String subTab = tab + "\t";
		reader.populate(pattern);
		buff.append(baseTab + "PATTERN " + pattern.getName()+ "\n");
		buff.append(tab + "urn\t" + pattern.getUrn()+ "\n");
		buff.append(tab + "type\t" + pattern.getType()+ "\n");
		buff.append(tab + "order\t" + pattern.get(FieldNames.FIELD_ORDER) + "\n");
		if(pattern.getOperationUrn() != null) buff.append(tab + "operation\t" + pattern.getOperationUrn()+ "\n");
		FactType srcFact = pattern.getFact();
		FactType mFact = pattern.getMatch();
		buff.append(tab + "SOURCE FACT " + (srcFact != null ? srcFact.getName() : "IS NULL")+ "\n");
		if(srcFact != null){
			buff.append(subTab + "urn\t" + srcFact.getUrn()+ "\n");
			buff.append(subTab + "type\t" + srcFact.getFactType()+ "\n");
			buff.append(subTab + "factoryType\t" + srcFact.getModelType() + "\n");
			buff.append(subTab + "sourceUrl\t" + srcFact.getSourceUrl()+ "\n");
			buff.append(subTab + "sourceUrn\t" + srcFact.getSourceUrn()+ "\n");
			buff.append(subTab + "sourceType\t" + srcFact.getSourceType()+ "\n");
			buff.append(subTab + "sourceDataType\t" + srcFact.getSourceDataType().toString()+ "\n");
			buff.append(subTab + "factData\t" + srcFact.getFactData()+ "\n");
		}
		buff.append(tab + "COMPARATOR " + pattern.getComparator()+ "\n");
		buff.append(tab + "MATCH FACT " + (mFact != null ? mFact.getName() : "IS NULL")+ "\n");
		if(mFact != null){
			buff.append(subTab + "urn\t" + mFact.getUrn()+ "\n");
			buff.append(subTab + "type\t" + mFact.getFactType()+ "\n");
			buff.append(subTab + "factoryType\t" + mFact.getModelType()+ "\n");
			buff.append(subTab + "sourceUrl\t" + mFact.getSourceUrl()+ "\n");
			buff.append(subTab + "sourceUrn\t" + mFact.getSourceUrn()+ "\n");
			buff.append(subTab + "sourceType\t" + mFact.getSourceType()+ "\n");
			buff.append(subTab + "sourceDataType\t" + mFact.getSourceDataType().toString()+ "\n");
			buff.append(subTab + "factData\t" + mFact.getFactData()+ "\n");
			if(mFact.getType() == FactEnumType.OPERATION){
				buff.append(subTab + "OPERATION\t" + (mFact.getSourceUrl() != null ? mFact.getSourceUrl() : "IS NULL")+ "\n");
				if(mFact.getSourceUrl() != null){
					try {
						OperationType op = new OperationType(reader.readByUrn(ModelNames.MODEL_OPERATION, mFact.getSourceUrl()));
						buff.append(subTab + "urn\t" + op.getUrn()+ "\n");
						buff.append(subTab + "operationType\t" + op.getType()+ "\n");
						buff.append(subTab + "operation\t" + op.getOperation()+ "\n");
					}
					catch(ReaderException e) {
						logger.error(e);
					}
				}
				
			}
		}
		return buff.toString();
	}
	public String printRule(RuleType rule, int depth) {
		
		reader.populate(rule);
		StringBuilder buff = new StringBuilder();
		StringBuilder baseTabBuff = new StringBuilder();
		for(int i = 0; i < depth; i++) baseTabBuff.append("\t");
		String baseTab = baseTabBuff.toString();
		String tab = baseTab.toString() + "\t";

		buff.append(baseTab + "RULE " + rule.getName()+ "\n");
		buff.append(tab + "urn\t" + rule.getUrn()+ "\n");
		buff.append(tab + "type\t" + rule.getType()+ "\n");
		buff.append(tab + "condition\t" + rule.getCondition()+ "\n");
		buff.append(tab + "order\t" + rule.get(FieldNames.FIELD_ORDER) + "\n");
		
		List<RuleType> rules = rule.getRules();
		for(int p = 0; p < rules.size();p++){
			RuleType crule = rules.get(p);
			buff.append(printRule(crule,depth+1));
		}
		
		List<PatternType> patterns = rule.getPatterns();
		for(int p = 0; p < patterns.size();p++){
			PatternType pattern = patterns.get(p);
			buff.append(printPattern(pattern,depth+1));
		}
		return buff.toString();
	}
	public String printPolicy(PolicyType pol) {
		StringBuilder buff = new StringBuilder();
		reader.populate(pol);
		buff.append("\nPOLICY " + pol.getName()+ "\n");
		buff.append("\turn\t" + pol.getUrn()+ "\n");
		buff.append("\tenabled\t" + pol.getEnabled()+ "\n");
		buff.append("\tcondition\t" + pol.getCondition()+ "\n");
		buff.append("\tcreated\t" + pol.getCreatedDate().toString()+ "\n");
		buff.append("\texpires\t" + pol.getExpiryDate().toString()+ "\n");
		List<RuleType> rules = pol.getRules();
		for(int i = 0; i < rules.size();i++){
			RuleType rule = rules.get(i);
			buff.append(printRule(rule, 1));
		}
		return buff.toString();
	}
	

	
	
}
