package org.cote.accountmanager.olio.llm.policy;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.policy.PolicyEvaluator;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;

/// Phase 9: Evaluates LLM responses through the existing policy evaluation pipeline.
/// Reads the policy from chatConfig.policy, builds a PolicyRequestType with response data
/// as fact parameters, and delegates to the standard PolicyEvaluator infrastructure.
/// The detection operations (Timeout, RecursiveLoop, WrongCharacter, Refusal) are wired as
/// IOperation implementations referenced by the policy's patterns.
public class ResponsePolicyEvaluator {

	public static final Logger logger = LogManager.getLogger(ResponsePolicyEvaluator.class);

	/// Evaluate a completed LLM response against the chatConfig's policy.
	/// Uses the existing PolicyEvaluator pipeline — operations are defined in the policy record.
	/// @param user The context user for policy evaluation
	/// @param responseContent The LLM response text (may be null for timeout)
	/// @param chatConfig The chatConfig record with policy reference
	/// @param promptConfig The promptConfig record
	/// @return PolicyEvaluationResult with PERMIT/DENY status and violation details
	public PolicyEvaluationResult evaluate(BaseRecord user, String responseContent, BaseRecord chatConfig, BaseRecord promptConfig) {
		PolicyEvaluationResult result = new PolicyEvaluationResult();

		if (chatConfig == null) {
			logger.warn("ResponsePolicyEvaluator: chatConfig is null, skipping evaluation");
			result.setPermitted(true);
			return result;
		}

		/// Resolve the policy from chatConfig.policy foreign reference
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

		/// Build PolicyRequestType through the standard PolicyUtil pipeline
		PolicyRequestType prt = IOSystem.getActiveContext().getPolicyUtil().getPolicyRequest(policy, user);
		if (prt == null) {
			logger.error("ResponsePolicyEvaluator: Failed to build policy request");
			result.setPermitted(true);
			return result;
		}

		/// Populate fact data: set response content on parameter facts, and chatConfig/promptConfig on match facts
		List<FactType> facts = prt.getFacts();
		for (FactType fact : facts) {
			/// Parameter facts carry the response content for operations to evaluate
			fact.setFactData(responseContent);

			/// Populate chatConfig/promptConfig on facts that support them
			if (fact.hasField("chatConfig")) {
				fact.setValue("chatConfig", chatConfig);
			}
			if (fact.hasField("promptConfig")) {
				fact.setValue("promptConfig", promptConfig);
			}
		}

		/// Evaluate through the standard PolicyEvaluator
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

			/// Extract violation details from the rule/pattern chain and messages
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

			logger.info("ResponsePolicyEvaluator: " + result.getViolationSummary());
			return result;

		} catch (Exception e) {
			logger.error("ResponsePolicyEvaluator: Policy evaluation failed: " + e.getMessage());
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
