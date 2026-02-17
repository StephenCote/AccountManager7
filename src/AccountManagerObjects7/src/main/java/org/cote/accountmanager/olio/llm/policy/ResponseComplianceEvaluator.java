package org.cote.accountmanager.olio.llm.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileComparison;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.llm.policy.ResponsePolicyEvaluator.PolicyViolation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;

/// LLM-based response compliance evaluator.
/// Uses a secondary LLM call to evaluate whether the response follows 6 guidance areas.
/// Prompt text is loaded from olio/llm/prompts/compliance.json.
public class ResponseComplianceEvaluator {

	public static final Logger logger = LogManager.getLogger(ResponseComplianceEvaluator.class);

	private static final String RESOURCE = "compliance";
	private static final String[] CHECK_NAMES = {
		"CHARACTER_IDENTITY", "GENDERED_VOICE", "PROFILE_ADHERENCE",
		"AGE_ADHERENCE", "EQUAL_TREATMENT", "PERSONALITY_CONSISTENCY",
		"USER_AUTONOMY"
	};

	/// Evaluate the LLM response for compliance with character and content guidelines.
	public List<PolicyViolation> evaluate(BaseRecord user, String responseContent, BaseRecord chatConfig) {
		List<PolicyViolation> violations = new ArrayList<>();

		if (responseContent == null || responseContent.trim().isEmpty()) {
			return violations;
		}

		if (chatConfig == null) {
			logger.warn("ResponseComplianceEvaluator: chatConfig is null");
			return violations;
		}

		BaseRecord sysChar = chatConfig.get("systemCharacter");
		BaseRecord usrChar = chatConfig.get("userCharacter");
		if (sysChar == null || usrChar == null) {
			logger.info("ResponseComplianceEvaluator: Both characters required for compliance check");
			return violations;
		}

		IOSystem.getActiveContext().getReader().populate(sysChar);
		IOSystem.getActiveContext().getReader().populate(usrChar);

		String compliancePrompt = buildCompliancePrompt(responseContent, chatConfig, sysChar, usrChar);
		String analysisResponse = callComplianceLLM(user, chatConfig, compliancePrompt);

		if (analysisResponse == null || analysisResponse.trim().isEmpty()) {
			logger.warn("ResponseComplianceEvaluator: Compliance LLM returned empty response");
			return violations;
		}

		return parseComplianceResponse(analysisResponse);
	}

	/// Build the compliance evaluation prompt with full character and profile context.
	/// Instructional text is loaded from compliance.json; dynamic data (stats, response) is assembled here.
	private String buildCompliancePrompt(String responseContent, BaseRecord chatConfig, BaseRecord sysChar, BaseRecord usrChar) {
		StringBuilder sb = new StringBuilder();
		Map<String, Object> res = PromptResourceUtil.load(RESOURCE);

		String sysName = sysChar.get(FieldNames.FIELD_FIRST_NAME);
		String usrName = usrChar.get(FieldNames.FIELD_FIRST_NAME);
		String sysGender = sysChar.get(FieldNames.FIELD_GENDER);
		String usrGender = usrChar.get(FieldNames.FIELD_GENDER);
		int sysAge = sysChar.get(FieldNames.FIELD_AGE);
		int usrAge = usrChar.get(FieldNames.FIELD_AGE);

		/// Header
		sb.append(replaceCharTokens(getStr(res, "promptHeader"), sysName, usrName, sysGender, usrGender, sysAge, usrAge));
		sb.append(System.lineSeparator()).append(System.lineSeparator());

		/// Character context
		appendLines(sb, res, "characterContext", sysName, usrName, sysGender, usrGender, sysAge, usrAge);
		sb.append(System.lineSeparator());

		/// Profile comparison context — dynamic data built in Java
		sb.append(getStr(res, "profileHeader")).append(System.lineSeparator());
		appendProfileStats(sb, sysChar, usrChar);
		sb.append(System.lineSeparator());

		/// Age guidance context
		sb.append(getStr(res, "ageGuidanceHeader")).append(System.lineSeparator());
		sb.append("- ").append(sysName).append(" is ").append(sysAge).append(" years old.");
		String ageSummary = getAgeSummary(res, sysAge);
		if (ageSummary != null) {
			sb.append(" ").append(ageSummary);
		}
		sb.append(System.lineSeparator()).append(System.lineSeparator());

		/// The response to evaluate
		sb.append(getStr(res, "responseHeader")).append(System.lineSeparator());
		String truncated = responseContent.length() > 2000 ? responseContent.substring(0, 2000) + "..." : responseContent;
		sb.append(truncated).append(System.lineSeparator()).append(System.lineSeparator());

		/// Evaluation criteria from resource
		sb.append(getStr(res, "evaluateHeader")).append(System.lineSeparator()).append(System.lineSeparator());
		int checkNum = 1;
		for (String check : CHECK_NAMES) {
			String desc = PromptResourceUtil.getMapValue(RESOURCE, "checks", check);
			if (desc != null) {
				desc = replaceCharTokens(desc, sysName, usrName, sysGender, usrGender, sysAge, usrAge);
				sb.append(checkNum).append(". ").append(check).append(": ").append(desc);
				sb.append(System.lineSeparator()).append(System.lineSeparator());
			}
			checkNum++;
		}

		/// Response format from resource
		appendLines(sb, res, "responseFormat", sysName, usrName, sysGender, usrGender, sysAge, usrAge);

		return sb.toString();
	}

	/// Append profile comparison stats (dynamic data — stays in Java).
	private void appendProfileStats(StringBuilder sb, BaseRecord sysChar, BaseRecord usrChar) {
		try {
			PersonalityProfile sysProf = ProfileUtil.getProfile(null, sysChar);
			PersonalityProfile usrProf = ProfileUtil.getProfile(null, usrChar);
			if (sysProf != null && usrProf != null) {
				ProfileComparison pc = new ProfileComparison(null, sysProf, usrProf);
				sb.append("- Strength diff (sys - usr): ").append(pc.getPhysicalStrengthDiff()).append(System.lineSeparator());
				sb.append("- Intelligence diff: ").append(pc.getIntelligenceDiff()).append(System.lineSeparator());
				sb.append("- Charisma diff: ").append(pc.getCharismaDiff()).append(System.lineSeparator());
				sb.append("- Wisdom diff: ").append(pc.getWisdomDiff()).append(System.lineSeparator());
				sb.append("- Racial compatibility: ").append(pc.getRacialCompatibility()).append(System.lineSeparator());
				sb.append("- Romantic compatibility: ").append(pc.getRomanticCompatibility()).append(System.lineSeparator());
				sb.append("- Age crosses boundary: ").append(pc.doesAgeCrossBoundary()).append(System.lineSeparator());
				double sysWealth = ItemUtil.countMoney(sysProf.getRecord());
				double usrWealth = ItemUtil.countMoney(usrProf.getRecord());
				sb.append("- Wealth: system=").append(sysWealth).append(", user=").append(usrWealth).append(System.lineSeparator());
				sb.append("- System character race codes: ").append(sysProf.getRace()).append(System.lineSeparator());
				sb.append("- User character race codes: ").append(usrProf.getRace()).append(System.lineSeparator());
				sb.append("- System character dark triad (machiavellianism): ").append(sysProf.getMachiavellian()).append(System.lineSeparator());
				sb.append("- System character dark triad (narcissism): ").append(sysProf.getNarcissist()).append(System.lineSeparator());
				sb.append("- System character dark triad (psychopathy): ").append(sysProf.getPsychopath()).append(System.lineSeparator());
			}
		} catch (Exception e) {
			sb.append("- (Profile comparison unavailable: ").append(e.getMessage()).append(")").append(System.lineSeparator());
		}
	}

	/// Look up the age summary for the compliance evaluator's abbreviated age guidance.
	@SuppressWarnings("unchecked")
	private String getAgeSummary(Map<String, Object> res, int age) {
		Object summaryObj = res != null ? res.get("ageGuidanceSummary") : null;
		if (!(summaryObj instanceof Map)) return null;
		Map<String, String> summaries = (Map<String, String>) summaryObj;
		String bracket;
		if (age <= 5) bracket = "child_0_5";
		else if (age <= 9) bracket = "child_6_9";
		else if (age <= 12) bracket = "preteen_10_12";
		else if (age <= 15) bracket = "teen_13_15";
		else if (age <= 17) bracket = "teen_16_17";
		else if (age <= 25) bracket = "youngAdult_18_25";
		else if (age >= 70) bracket = "elderly_70_plus";
		else if (age >= 55) bracket = "middleAged_55_69";
		else return null;
		return summaries.get(bracket);
	}

	/// Parse the LLM compliance response JSON into violations.
	private List<PolicyViolation> parseComplianceResponse(String response) {
		List<PolicyViolation> violations = new ArrayList<>();

		String json = response.trim();
		if (json.startsWith("```")) {
			int start = json.indexOf('{');
			int end = json.lastIndexOf('}');
			if (start >= 0 && end > start) {
				json = json.substring(start, end + 1);
			}
		}

		try {
			Map<String, Object> result = JSONUtil.getMap(json.getBytes(), String.class, Object.class);
			if (result == null) {
				logger.warn("ResponseComplianceEvaluator: Failed to parse compliance JSON");
				return violations;
			}

			for (String check : CHECK_NAMES) {
				Object entry = result.get(check);
				if (entry instanceof Map) {
					Map<?, ?> checkResult = (Map<?, ?>) entry;
					Object pass = checkResult.get("pass");
					boolean passed = Boolean.TRUE.equals(pass) || "true".equals(String.valueOf(pass));
					if (!passed) {
						String note = checkResult.get("note") != null ? String.valueOf(checkResult.get("note")) : "Compliance check failed";
						violations.add(new PolicyViolation("COMPLIANCE_" + check, note));
					}
				}
			}
		} catch (Exception e) {
			logger.warn("ResponseComplianceEvaluator: Error parsing compliance response: " + e.getMessage());
		}

		if (!violations.isEmpty()) {
			logger.info("ResponseComplianceEvaluator: " + violations.size() + " compliance violation(s) detected");
		}
		return violations;
	}

	/// Make an LLM call for compliance evaluation.
	private String callComplianceLLM(BaseRecord user, BaseRecord chatConfig, String compliancePrompt) {
		try {
			BaseRecord resolvedConfig = OlioUtil.getFullRecord(chatConfig);
			if (resolvedConfig == null) {
				resolvedConfig = chatConfig;
			}

			String model = resolvedConfig.get("analyzeModel");
			if (model == null || model.isEmpty()) {
				model = resolvedConfig.get("model");
			}

			Chat chat = new Chat(user, resolvedConfig, null);
			chat.setPersistSession(false);
			OpenAIRequest areq = new OpenAIRequest();
			areq.setModel(model);
			areq.setStream(false);

			try {
				areq.set("temperature", 0.2);
				areq.set("top_p", 0.5);
				String tokField = ChatUtil.getMaxTokenField(resolvedConfig);
				if (tokField != null && !tokField.isEmpty()) {
					areq.set(tokField, 2048);
				}
			} catch (Exception e) {
				logger.warn("ResponseComplianceEvaluator: Error setting analysis options: " + e.getMessage());
			}

			OpenAIMessage sysMsg = new OpenAIMessage();
			sysMsg.setRole("system");
			String sysContent = PromptResourceUtil.getString(RESOURCE, "system");
			sysMsg.setContent(sysContent != null ? sysContent : "Evaluate the response for compliance. Respond only in JSON format.");
			areq.addMessage(sysMsg);

			OpenAIMessage userMsg = new OpenAIMessage();
			userMsg.setRole("user");
			userMsg.setContent(compliancePrompt);
			areq.addMessage(userMsg);

			OpenAIResponse resp = chat.chat(areq);
			if (resp == null) {
				logger.warn("ResponseComplianceEvaluator: chat.chat() returned null");
				return null;
			}

			BaseRecord msg = resp.get("message");
			if (msg != null) {
				return msg.get("content");
			}
			List<BaseRecord> choices = resp.get("choices");
			if (choices != null && !choices.isEmpty()) {
				BaseRecord choice = choices.get(0);
				BaseRecord message = choice.get("message");
				if (message != null) {
					return message.get("content");
				}
			}
			logger.warn("ResponseComplianceEvaluator: No content in LLM response");
			return null;
		} catch (Exception e) {
			logger.error("ResponseComplianceEvaluator: LLM call failed: " + e.getMessage());
			return null;
		}
	}

	/// Replace character-specific tokens in a template string.
	private String replaceCharTokens(String template, String sysName, String usrName,
			String sysGender, String usrGender, int sysAge, int usrAge) {
		if (template == null) return "";
		template = PromptResourceUtil.replaceToken(template, "sysName", sysName);
		template = PromptResourceUtil.replaceToken(template, "usrName", usrName);
		template = PromptResourceUtil.replaceToken(template, "sysGender", sysGender);
		template = PromptResourceUtil.replaceToken(template, "usrGender", usrGender);
		template = PromptResourceUtil.replaceToken(template, "sysAge", String.valueOf(sysAge));
		template = PromptResourceUtil.replaceToken(template, "usrAge", String.valueOf(usrAge));
		return template;
	}

	/// Get a string value from a resource map, or empty string.
	private String getStr(Map<String, Object> res, String key) {
		if (res == null) return "";
		Object val = res.get(key);
		return val instanceof String ? (String) val : "";
	}

	/// Append a lines array from a resource map, applying character token replacement.
	@SuppressWarnings("unchecked")
	private void appendLines(StringBuilder sb, Map<String, Object> res, String key,
			String sysName, String usrName, String sysGender, String usrGender, int sysAge, int usrAge) {
		if (res == null) return;
		Object val = res.get(key);
		if (val instanceof List) {
			for (String line : (List<String>) val) {
				sb.append(replaceCharTokens(line, sysName, usrName, sysGender, usrGender, sysAge, usrAge));
				sb.append(System.lineSeparator());
			}
		}
	}
}
