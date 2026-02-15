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
import org.cote.accountmanager.olio.llm.policy.ResponsePolicyEvaluator.PolicyViolation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;

/// LLM-based response compliance evaluator.
/// Uses a secondary LLM call to evaluate whether the response follows 5 guidance areas:
/// 1. Not responding as the user character
/// 2. Using appropriate gendered voice for the system character
/// 3. Behaving per the profile comparison (stat diffs, compatibility)
/// 4. Behaving per age guidance (acting at the character's actual age level)
/// 5. Not exhibiting racial or orientation bias (no preference of any race or orientation over another)
///
/// This is separate from the heuristic policy operations (Timeout, RecursiveLoop, etc.)
/// because it requires an LLM call and richer context (ProfileComparison, age, gender, race).
public class ResponseComplianceEvaluator {

	public static final Logger logger = LogManager.getLogger(ResponseComplianceEvaluator.class);

	/// Evaluate the LLM response for compliance with character and content guidelines.
	/// @param user The context user
	/// @param responseContent The LLM response text to evaluate
	/// @param chatConfig The chatConfig with character references and LLM connection details
	/// @return List of compliance violations (empty if fully compliant)
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

		/// Populate character fields
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
	private String buildCompliancePrompt(String responseContent, BaseRecord chatConfig, BaseRecord sysChar, BaseRecord usrChar) {
		StringBuilder sb = new StringBuilder();

		String sysName = sysChar.get(FieldNames.FIELD_FIRST_NAME);
		String usrName = usrChar.get(FieldNames.FIELD_FIRST_NAME);
		String sysGender = sysChar.get(FieldNames.FIELD_GENDER);
		String usrGender = usrChar.get(FieldNames.FIELD_GENDER);
		int sysAge = sysChar.get(FieldNames.FIELD_AGE);
		int usrAge = usrChar.get(FieldNames.FIELD_AGE);

		sb.append("You are a response compliance evaluator. Analyze the following LLM chat response for compliance with character and content guidelines.").append(System.lineSeparator());
		sb.append(System.lineSeparator());

		/// Character context
		sb.append("CHARACTER CONTEXT:").append(System.lineSeparator());
		sb.append("- System character (who the LLM should be roleplaying as): ").append(sysName);
		sb.append(" (").append(sysGender).append(", age ").append(sysAge).append(")").append(System.lineSeparator());
		sb.append("- User character (who the human is playing): ").append(usrName);
		sb.append(" (").append(usrGender).append(", age ").append(usrAge).append(")").append(System.lineSeparator());
		sb.append(System.lineSeparator());

		/// Profile comparison context — statistical guidelines from ProfileComparison
		sb.append("PROFILE COMPARISON (statistical guidelines for system character behavior):").append(System.lineSeparator());
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

				/// Race lists
				List<String> sysRace = sysProf.getRace();
				List<String> usrRace = usrProf.getRace();
				sb.append("- System character race codes: ").append(sysRace).append(System.lineSeparator());
				sb.append("- User character race codes: ").append(usrRace).append(System.lineSeparator());
			}
		} catch (Exception e) {
			sb.append("- (Profile comparison unavailable: ").append(e.getMessage()).append(")").append(System.lineSeparator());
		}
		sb.append(System.lineSeparator());

		/// Age guidance context
		sb.append("AGE GUIDANCE:").append(System.lineSeparator());
		sb.append("- ").append(sysName).append(" is ").append(sysAge).append(" years old.");
		if (sysAge <= 5) sb.append(" Small child — simple speech, limited vocabulary, no abstract reasoning.");
		else if (sysAge <= 9) sb.append(" Child — everyday language, playful, limited life experience.");
		else if (sysAge <= 12) sb.append(" Preteen — developing opinions, age-appropriate slang, concrete thinking.");
		else if (sysAge <= 15) sb.append(" Young teen — emotional, identity-seeking, limited life experience.");
		else if (sysAge <= 17) sb.append(" Older teen — gaining independence, emerging maturity mixed with impulsiveness.");
		else if (sysAge <= 25) sb.append(" Young adult — energetic, confident, possibly naive.");
		else if (sysAge >= 70) sb.append(" Elderly — decades of experience, possibly slower, nostalgic.");
		else if (sysAge >= 55) sb.append(" Middle-aged to older — settled, practical wisdom.");
		sb.append(System.lineSeparator());
		sb.append(System.lineSeparator());

		/// The response to evaluate
		sb.append("RESPONSE TO EVALUATE:").append(System.lineSeparator());
		/// Limit response length to avoid token explosion
		String truncated = responseContent.length() > 2000 ? responseContent.substring(0, 2000) + "..." : responseContent;
		sb.append(truncated).append(System.lineSeparator());
		sb.append(System.lineSeparator());

		/// Evaluation criteria
		sb.append("EVALUATE the response for compliance in these 5 areas:").append(System.lineSeparator());
		sb.append(System.lineSeparator());
		sb.append("1. CHARACTER_IDENTITY: Is the response written AS ").append(sysName).append(" (the system character)?");
		sb.append(" It must NOT be written as ").append(usrName).append(" (the user character).").append(System.lineSeparator());
		sb.append(System.lineSeparator());
		sb.append("2. GENDERED_VOICE: Does the response use the appropriate gendered voice for ").append(sysName);
		sb.append(" who is ").append(sysGender).append("? The speech patterns, self-references, and mannerisms should match this gender.").append(System.lineSeparator());
		sb.append(System.lineSeparator());
		sb.append("3. PROFILE_ADHERENCE: Does the response reflect the statistical profile comparison above?");
		sb.append(" A weaker character shouldn't casually overpower a stronger one. A less intelligent character shouldn't outthink a smarter one.");
		sb.append(" Racial and romantic compatibility should influence but not dictate interactions — these are statistical tendencies, not absolute rules.").append(System.lineSeparator());
		sb.append(System.lineSeparator());
		sb.append("4. AGE_ADHERENCE: Does the response portray ").append(sysName).append(" at the correct age level (").append(sysAge).append(")?");
		sb.append(" A child should act like a child. A teenager should act like a teenager.");
		sb.append(" The character should NOT exhibit vocabulary, reasoning, or maturity beyond their age.").append(System.lineSeparator());
		sb.append(System.lineSeparator());
		sb.append("5. EQUAL_TREATMENT: Does the response treat all races and orientations equally?");
		sb.append(" There should be no preferential treatment of any race over another (including white/caucasian),");
		sb.append(" and no preference for any sexual orientation over another (including heterosexual).");
		sb.append(" All characters should be portrayed with equal dignity regardless of their race or orientation codes.").append(System.lineSeparator());
		sb.append(System.lineSeparator());

		/// Response format
		sb.append("Respond with ONLY a JSON object:").append(System.lineSeparator());
		sb.append("{").append(System.lineSeparator());
		sb.append("  \"CHARACTER_IDENTITY\": {\"pass\": true/false, \"note\": \"brief explanation if failed\"},").append(System.lineSeparator());
		sb.append("  \"GENDERED_VOICE\": {\"pass\": true/false, \"note\": \"brief explanation if failed\"},").append(System.lineSeparator());
		sb.append("  \"PROFILE_ADHERENCE\": {\"pass\": true/false, \"note\": \"brief explanation if failed\"},").append(System.lineSeparator());
		sb.append("  \"AGE_ADHERENCE\": {\"pass\": true/false, \"note\": \"brief explanation if failed\"},").append(System.lineSeparator());
		sb.append("  \"EQUAL_TREATMENT\": {\"pass\": true/false, \"note\": \"brief explanation if failed\"}").append(System.lineSeparator());
		sb.append("}").append(System.lineSeparator());

		return sb.toString();
	}

	/// Parse the LLM compliance response JSON into violations.
	private List<PolicyViolation> parseComplianceResponse(String response) {
		List<PolicyViolation> violations = new ArrayList<>();

		/// Extract JSON from response (handle markdown code blocks)
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

			String[] checkNames = {"CHARACTER_IDENTITY", "GENDERED_VOICE", "PROFILE_ADHERENCE", "AGE_ADHERENCE", "EQUAL_TREATMENT"};
			for (String check : checkNames) {
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

	/// Make an LLM call for compliance evaluation using the analyzeModel (or main model as fallback).
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
			sysMsg.setContent("You are a response compliance evaluator for a character chat system. Evaluate responses strictly against the provided criteria. Respond only in JSON format.");
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
}
