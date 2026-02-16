package org.cote.accountmanager.olio.llm.policy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.policy.OperationUtil;
import org.cote.accountmanager.policy.PolicyEvaluator;
import org.cote.accountmanager.policy.operation.IOperation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

/// Evaluates LLM responses through policy template operations.
/// Bypasses the standard PolicyEvaluator pipeline (which requires fully wired AM7 policy records
/// with Rule/Pattern/Fact/Operation linkages) and instead evaluates operations directly from
/// the policy template JSON (policy.rpg.json, policy.bias.json, etc.).
///
/// Resolution order for policy template:
/// 1. chatConfig.policyTemplate field (e.g., "rpg" → loads "olio/llm/policy.rpg.json")
/// 2. chatConfig.policy foreign ref → resolve name → static POLICY_TEMPLATES map
/// 3. If neither found, skip evaluation (PERMIT)
public class ResponsePolicyEvaluator {

	public static final Logger logger = LogManager.getLogger(ResponsePolicyEvaluator.class);

	private static final String TEMPLATE_RESOURCE_PREFIX = "olio/llm/policy.";
	private static final String TEMPLATE_RESOURCE_SUFFIX = ".json";

	/// Evaluate a completed LLM response against the chatConfig's policy template.
	/// Loads the template JSON, instantiates each operation, and evaluates directly.
	/// @param user The context user
	/// @param responseContent The LLM response text (may be null for timeout)
	/// @param chatConfig The chatConfig record with policyTemplate or policy reference
	/// @param promptConfig The promptConfig record
	/// @param requestId The OpenAIRequest objectId for logging context (may be null)
	/// @return PolicyEvaluationResult with PERMIT/DENY status and violation details
	public PolicyEvaluationResult evaluate(BaseRecord user, String responseContent, BaseRecord chatConfig, BaseRecord promptConfig) {
		return evaluate(user, responseContent, chatConfig, promptConfig, null);
	}

	/// Evaluate with request tracking ID for log correlation.
	public PolicyEvaluationResult evaluate(BaseRecord user, String responseContent, BaseRecord chatConfig, BaseRecord promptConfig, String requestId) {
		PolicyEvaluationResult result = new PolicyEvaluationResult();

		if (chatConfig == null) {
			logger.warn("ResponsePolicyEvaluator: chatConfig is null, skipping evaluation");
			result.setPermitted(true);
			return result;
		}

		/// Resolve the policy template JSON
		String templateJson = resolveTemplateJson(chatConfig);
		if (templateJson == null) {
			/// No template found — fall back to standard PolicyEvaluator pipeline
			/// for real AM7 policy records with proper Rule/Pattern/Fact linkages
			return evaluateViaStandardPipeline(user, responseContent, chatConfig, result);
		}

		/// Parse the template
		Map<String, Object> template = JSONUtil.getMap(templateJson.getBytes(), String.class, Object.class);
		if (template == null) {
			logger.warn("ResponsePolicyEvaluator: Failed to parse policy template JSON");
			result.setPermitted(true);
			return result;
		}

		@SuppressWarnings("unchecked")
		List<Map<String, Object>> operations = (List<Map<String, Object>>) template.get("operations");
		if (operations == null || operations.isEmpty()) {
			logger.warn("ResponsePolicyEvaluator: No operations in policy template");
			result.setPermitted(true);
			return result;
		}

		/// Build character context JSON for operations that need it
		String charJson = buildCharacterJson(chatConfig);
		String biasCharJson = buildBiasCharacterJson(chatConfig);

		/// Get reader/search for operation instantiation
		IReader reader = IOSystem.getActiveContext().getReader();
		ISearch search = IOSystem.getActiveContext().getSearch();

		/// Evaluate each operation
		boolean allPassed = true;
		for (Map<String, Object> opDef : operations) {
			String opName = (String) opDef.get("name");
			String opClass = (String) opDef.get("operationClass");

			if (opClass == null || opClass.isEmpty()) {
				logger.warn("ResponsePolicyEvaluator: Operation '" + opName + "' has no operationClass, skipping");
				continue;
			}

			IOperation oper = OperationUtil.getOperationInstance(opClass, reader, search);
			if (oper == null) {
				logger.error("ResponsePolicyEvaluator: Could not instantiate operation: " + opClass);
				result.addViolation(opName != null ? opName : opClass, "Operation class not found: " + opClass);
				allPassed = false;
				continue;
			}

			/// Build sourceFact with response content
			FactType sourceFact = buildSourceFact(user, responseContent);

			/// Build referenceFact with merged parameters + character context
			@SuppressWarnings("unchecked")
			Map<String, Object> params = (Map<String, Object>) opDef.get("parameters");
			FactType referenceFact = buildReferenceFact(user, params, charJson, biasCharJson);

			/// Execute the operation
			try {
				OperationResponseEnumType opResult = oper.operate(null, null, null, sourceFact, referenceFact);
				if (opResult == OperationResponseEnumType.FAILED) {
					allPassed = false;
					result.addViolation(opName != null ? opName : opClass, "Operation detected violation");
					logger.info("ResponsePolicyEvaluator: FAILED — " + opName);
				} else if (opResult == OperationResponseEnumType.ERROR) {
					logger.warn("ResponsePolicyEvaluator: ERROR from operation " + opName);
				}
			} catch (Exception e) {
				logger.error("ResponsePolicyEvaluator: Operation " + opName + " threw: " + e.getMessage());
			}
		}

		result.setPermitted(allPassed);
		String reqCtx = requestId != null ? " [req=" + requestId + "]" : "";
		logger.info("ResponsePolicyEvaluator:" + reqCtx + " " + result.getViolationSummary());
		return result;
	}

	/// Fallback: Evaluate via the standard PolicyEvaluator pipeline for real AM7 policy records.
	/// Used when no policy template JSON is found but a policy foreign reference exists.
	private PolicyEvaluationResult evaluateViaStandardPipeline(BaseRecord user, String responseContent,
			BaseRecord chatConfig, PolicyEvaluationResult result) {

		BaseRecord policyRef = chatConfig.get("policy");
		if (policyRef == null) {
			result.setPermitted(true);
			return result;
		}

		PolicyType policy = resolvePolicy(policyRef);
		if (policy == null) {
			logger.warn("ResponsePolicyEvaluator: Could not resolve policy from chatConfig");
			result.setPermitted(true);
			return result;
		}

		PolicyRequestType prt = IOSystem.getActiveContext().getPolicyUtil().getPolicyRequest(policy, user);
		if (prt == null) {
			logger.error("ResponsePolicyEvaluator: Failed to build policy request");
			result.setPermitted(true);
			return result;
		}

		List<FactType> facts = prt.getFacts();
		for (FactType fact : facts) {
			fact.setFactData(responseContent);
		}

		try {
			PolicyEvaluator pe = IOSystem.getActiveContext().getPolicyEvaluator();
			PolicyResponseType prr = pe.evaluatePolicyRequest(prt, policy).toConcrete();
			if (prr == null) {
				logger.error("ResponsePolicyEvaluator: Null policy response");
				result.setPermitted(true);
				return result;
			}

			result.setPermitted(prr.getType() == PolicyResponseEnumType.PERMIT);
			result.setPolicyResponse(prr);

			if (!result.isPermitted()) {
				List<String> messages = prr.getMessages();
				List<String> ruleChain = prr.get("ruleChain");
				String ruleChainStr = ruleChain != null ? String.join(", ", ruleChain) : "";
				String details = "Policy DENY — rules: [" + ruleChainStr + "]";
				if (messages != null && !messages.isEmpty()) {
					details += ", messages: " + String.join("; ", messages);
				}
				result.addViolation("POLICY_DENY", details);
			}

			logger.info("ResponsePolicyEvaluator (standard pipeline): " + result.getViolationSummary());
			return result;

		} catch (Exception e) {
			logger.error("ResponsePolicyEvaluator: Standard pipeline evaluation failed: " + e.getMessage());
			result.setPermitted(true);
			return result;
		}
	}

	/// Resolve a full policy record from a foreign reference.
	private PolicyType resolvePolicy(BaseRecord policyRef) {
		try {
			String objectId = policyRef.get(FieldNames.FIELD_OBJECT_ID);
			if (objectId == null) {
				long id = policyRef.get(FieldNames.FIELD_ID);
				if (id <= 0L) return null;
				BaseRecord rec = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_POLICY, id);
				if (rec != null) {
					IOSystem.getActiveContext().getReader().populate(rec);
					return new PolicyType(rec);
				}
				return null;
			}
			Query q = QueryUtil.createQuery(ModelNames.MODEL_POLICY, FieldNames.FIELD_OBJECT_ID, objectId);
			q.planMost(true);
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			if (qr != null && qr.getResults().length > 0) {
				return new PolicyType(qr.getResults()[0]);
			}
		} catch (Exception e) {
			logger.error("ResponsePolicyEvaluator: Error resolving policy: " + e.getMessage());
		}
		return null;
	}

	/// Resolve the policy template JSON from chatConfig.
	/// Tries policyTemplate field first, then falls back to policy name lookup.
	private String resolveTemplateJson(BaseRecord chatConfig) {
		/// Try policyTemplate string field first
		String templateName = null;
		if (chatConfig.hasField("policyTemplate")) {
			templateName = chatConfig.get("policyTemplate");
		}

		if (templateName != null && !templateName.isEmpty()) {
			String resource = ResourceUtil.getInstance().getResource(TEMPLATE_RESOURCE_PREFIX + templateName + TEMPLATE_RESOURCE_SUFFIX);
			if (resource != null) {
				return resource;
			}
			logger.warn("ResponsePolicyEvaluator: Policy template not found: " + templateName);
		}

		/// Fall back to policy foreign ref name
		BaseRecord policyRef = chatConfig.get("policy");
		if (policyRef != null) {
			IReader reader = IOSystem.getActiveContext().getReader();
			if (reader != null) {
				reader.populate(policyRef, new String[] { FieldNames.FIELD_NAME });
			}
			String policyName = policyRef.get(FieldNames.FIELD_NAME);
			if (policyName != null) {
				/// Try to derive template name from policy name
				/// e.g., "RPG Response Policy" → "rpg"
				String derived = deriveTemplateName(policyName);
				if (derived != null) {
					String resource = ResourceUtil.getInstance().getResource(TEMPLATE_RESOURCE_PREFIX + derived + TEMPLATE_RESOURCE_SUFFIX);
					if (resource != null) {
						return resource;
					}
				}
			}
		}

		return null;
	}

	/// Derive a template resource name from a policy record name.
	private static String deriveTemplateName(String policyName) {
		if (policyName == null) return null;
		String lower = policyName.toLowerCase();
		if (lower.contains("rpg") && lower.contains("bias")) return "rpg.bias";
		if (lower.contains("rpg")) return "rpg";
		if (lower.contains("bias")) return "bias";
		if (lower.contains("clinical")) return "clinical";
		if (lower.contains("general")) return "general";
		return null;
	}

	/// Build a source fact carrying the response content for operations to evaluate.
	/// Follows the standard AM7 pattern: applyNameGroupOwnership with ~/Facts group path.
	private FactType buildSourceFact(BaseRecord user, String responseContent) {
		try {
			FactType fact = new FactType();
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(
				user, fact, "responseContent", "~/Facts", user.get(FieldNames.FIELD_ORGANIZATION_ID));
			fact.setFactData(responseContent);
			return fact;
		} catch (Exception e) {
			logger.error("ResponsePolicyEvaluator: Failed to build source fact: " + e.getMessage());
			return null;
		}
	}

	/// Build a reference fact carrying merged parameters + character context.
	/// Follows the standard AM7 pattern: applyNameGroupOwnership with ~/Facts group path.
	private FactType buildReferenceFact(BaseRecord user, Map<String, Object> params, String charJson, String biasCharJson) {
		try {
			FactType fact = new FactType();
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(
				user, fact, "operationConfig", "~/Facts", user.get(FieldNames.FIELD_ORGANIZATION_ID));

			/// Merge all context into a single JSON map
			Map<String, String> merged = new HashMap<>();
			if (params != null) {
				for (Map.Entry<String, Object> entry : params.entrySet()) {
					merged.put(entry.getKey(), String.valueOf(entry.getValue()));
				}
			}

			/// Merge character context from charJson
			if (charJson != null) {
				try {
					Map<String, String> charMap = JSONUtil.getMap(charJson.getBytes(), String.class, String.class);
					if (charMap != null) merged.putAll(charMap);
				} catch (Exception e) { /* skip */ }
			}

			/// Merge bias character context
			if (biasCharJson != null) {
				try {
					Map<String, String> biasMap = JSONUtil.getMap(biasCharJson.getBytes(), String.class, String.class);
					if (biasMap != null) merged.putAll(biasMap);
				} catch (Exception e) { /* skip */ }
			}

			fact.setFactData(JSONUtil.exportObject(merged));
			return fact;
		} catch (Exception e) {
			logger.error("ResponsePolicyEvaluator: Failed to build reference fact: " + e.getMessage());
			return null;
		}
	}

	/// Build JSON string with character names from chatConfig for WrongCharacterDetection.
	public static String buildCharacterJson(BaseRecord chatConfig) {
		BaseRecord sysChar = chatConfig.get("systemCharacter");
		BaseRecord userChar = chatConfig.get("userCharacter");
		if (sysChar == null || userChar == null) {
			return null;
		}
		IReader reader = IOSystem.getActiveContext().getReader();
		if (reader != null) {
			reader.populate(sysChar, new String[] { FieldNames.FIELD_FIRST_NAME });
			reader.populate(userChar, new String[] { FieldNames.FIELD_FIRST_NAME });
		}
		String sysName = sysChar.get(FieldNames.FIELD_FIRST_NAME);
		String userName = userChar.get(FieldNames.FIELD_FIRST_NAME);
		if (sysName == null || userName == null) {
			return null;
		}
		return "{\"systemCharName\":\"" + sysName + "\",\"userCharName\":\"" + userName + "\"}";
	}

	/// Build JSON string with character demographics for bias detection operations.
	/// Extracts gender, age, race from systemCharacter for context-dependent bias checks.
	public static String buildBiasCharacterJson(BaseRecord chatConfig) {
		BaseRecord sysChar = chatConfig.get("systemCharacter");
		if (sysChar == null) {
			return null;
		}
		IReader reader = IOSystem.getActiveContext().getReader();
		if (reader != null) {
			reader.populate(sysChar, new String[] { FieldNames.FIELD_GENDER, FieldNames.FIELD_AGE, "race" });
		}

		String gender = sysChar.get(FieldNames.FIELD_GENDER);
		int age = 0;
		try {
			Object ageObj = sysChar.get(FieldNames.FIELD_AGE);
			if (ageObj instanceof Number) {
				age = ((Number) ageObj).intValue();
			}
		} catch (Exception e) { /* use default 0 */ }

		String race = "";
		try {
			Object raceObj = sysChar.get("race");
			if (raceObj instanceof List) {
				@SuppressWarnings("unchecked")
				List<String> raceList = (List<String>) raceObj;
				if (!raceList.isEmpty()) {
					race = raceList.get(0);
				}
			} else if (raceObj instanceof String) {
				race = (String) raceObj;
			}
		} catch (Exception e) { /* use default empty */ }

		/// Get setting from chatConfig if available
		String setting = "";
		if (chatConfig.hasField("setting")) {
			Object settingObj = chatConfig.get("setting");
			if (settingObj instanceof String) {
				setting = (String) settingObj;
			}
		}

		return "{\"systemCharGender\":\"" + (gender != null ? gender : "") + "\""
			+ ",\"systemCharAge\":\"" + age + "\""
			+ ",\"systemCharRace\":\"" + race + "\""
			+ ",\"setting\":\"" + setting + "\"}";
	}

	/// Result container for policy evaluation.
	public static class PolicyEvaluationResult {
		private boolean permitted = false;
		private PolicyResponseType policyResponse = null;
		private List<PolicyViolation> violations = new ArrayList<>();

		public boolean isPermitted() { return permitted; }
		public void setPermitted(boolean permitted) { this.permitted = permitted; }
		public PolicyResponseType getPolicyResponse() { return policyResponse; }
		public void setPolicyResponse(PolicyResponseType policyResponse) { this.policyResponse = policyResponse; }
		public List<PolicyViolation> getViolations() { return violations; }

		public void addViolation(String ruleType, String details) {
			violations.add(new PolicyViolation(ruleType, details));
		}

		public String getViolationSummary() {
			if (violations.isEmpty()) return "PERMIT";
			StringBuilder sb = new StringBuilder("DENY: ");
			for (int i = 0; i < violations.size(); i++) {
				if (i > 0) sb.append(", ");
				sb.append(violations.get(i).getRuleType());
			}
			return sb.toString();
		}
	}

	/// Individual policy violation record.
	public static class PolicyViolation {
		private String ruleType;
		private String details;

		public PolicyViolation(String ruleType, String details) {
			this.ruleType = ruleType;
			this.details = details;
		}

		public String getRuleType() { return ruleType; }
		public String getDetails() { return details; }
	}
}
