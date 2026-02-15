package org.cote.accountmanager.olio.llm.policy;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/// Note: evaluate() accepts List<OpenAIMessage> directly from Chat's message list.

/// Mid-chat interaction evaluator.
/// Periodically classifies the ongoing interaction type, outcome trajectory,
/// and relationship direction from the recent message history.
/// Lighter-weight than GameUtil.concludeChat() — returns a JSON status string
/// rather than creating a persistent interaction record.
///
/// Output format (JSON string sent as interactionEvent chirp):
/// {
///   "interactionType": "SOCIALIZE|NEGOTIATE|CONFLICT|...",
///   "outcome": "POSITIVE|NEGATIVE|NEUTRAL|MIXED",
///   "actorOutcome": "FAVORABLE|UNFAVORABLE|EQUILIBRIUM",
///   "targetOutcome": "FAVORABLE|UNFAVORABLE|EQUILIBRIUM",
///   "relationshipDirection": "IMPROVING|WORSENING|STABLE",
///   "summary": "Brief status of how the interaction is going"
/// }
public class InteractionEvaluator {

	public static final Logger logger = LogManager.getLogger(InteractionEvaluator.class);

	/// Evaluate the current interaction status from recent messages.
	/// @param user The context user
	/// @param chatConfig The chatConfig with character references and LLM settings
	/// @param messages The message list from the OpenAIRequest (user + assistant roles)
	/// @return JSON string with interaction status, or null on failure
	public String evaluate(BaseRecord user, BaseRecord chatConfig, List<OpenAIMessage> messages) {
		if (user == null || chatConfig == null || messages == null || messages.isEmpty()) {
			return null;
		}

		BaseRecord sysChar = chatConfig.get("systemCharacter");
		BaseRecord usrChar = chatConfig.get("userCharacter");
		if (sysChar == null || usrChar == null) {
			logger.info("InteractionEvaluator: Both characters required");
			return null;
		}

		IOSystem.getActiveContext().getReader().populate(sysChar);
		IOSystem.getActiveContext().getReader().populate(usrChar);

		String sysName = sysChar.get(FieldNames.FIELD_FIRST_NAME);
		String usrName = usrChar.get(FieldNames.FIELD_FIRST_NAME);
		String sysGender = sysChar.get(FieldNames.FIELD_GENDER);
		String usrGender = usrChar.get(FieldNames.FIELD_GENDER);
		int sysAge = sysChar.get(FieldNames.FIELD_AGE);
		int usrAge = usrChar.get(FieldNames.FIELD_AGE);
		String setting = chatConfig.get("setting");

		String evalPrompt = buildEvalPrompt(sysName, sysAge, sysGender, usrName, usrAge, usrGender, setting, messages);
		return callEvalLLM(user, chatConfig, evalPrompt);
	}

	private static final String RESOURCE = "interaction";

	private String buildEvalPrompt(String sysName, int sysAge, String sysGender,
			String usrName, int usrAge, String usrGender, String setting, List<OpenAIMessage> messages) {

		/// Filter to only user/assistant messages
		List<OpenAIMessage> chatMsgs = messages.stream()
			.filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
			.collect(java.util.stream.Collectors.toList());

		StringBuilder sb = new StringBuilder();

		/// Character block from resource
		String charBlock = PromptResourceUtil.getLines(RESOURCE, "characterBlock");
		if (charBlock != null) {
			charBlock = replaceCharTokens(charBlock, sysName, sysAge, sysGender, usrName, usrAge, usrGender);
			sb.append(charBlock).append(System.lineSeparator());
		}

		/// Setting line
		if (setting != null && !setting.isEmpty()) {
			String settingLine = PromptResourceUtil.getString(RESOURCE, "settingLine");
			if (settingLine != null) {
				sb.append(PromptResourceUtil.replaceToken(settingLine, "setting", setting));
			} else {
				sb.append("SETTING: ").append(setting);
			}
			sb.append(System.lineSeparator());
		}
		sb.append(System.lineSeparator());

		/// Conversation header from resource
		String convHeader = PromptResourceUtil.getString(RESOURCE, "conversationHeader");
		if (convHeader != null) {
			sb.append(PromptResourceUtil.replaceToken(convHeader, "msgCount", String.valueOf(chatMsgs.size())));
		} else {
			sb.append("RECENT CONVERSATION (last ").append(chatMsgs.size()).append(" messages):");
		}
		sb.append(System.lineSeparator());

		/// Conversation messages (dynamic)
		int limit = Math.min(chatMsgs.size(), 20);
		int start = chatMsgs.size() - limit;
		for (int i = start; i < chatMsgs.size(); i++) {
			OpenAIMessage msg = chatMsgs.get(i);
			String role = msg.getRole();
			String content = msg.getContent();
			String speaker = "assistant".equals(role) ? sysName : usrName;
			if (content != null && content.length() > 300) {
				content = content.substring(0, 300) + "...";
			}
			sb.append(speaker).append(": ").append(content != null ? content : "").append(System.lineSeparator());
		}
		sb.append(System.lineSeparator());

		/// Classification request + response format from resource
		String classifyReq = PromptResourceUtil.getString(RESOURCE, "classifyRequest");
		if (classifyReq != null) {
			sb.append(classifyReq).append(System.lineSeparator());
		}
		String respFormat = PromptResourceUtil.getLines(RESOURCE, "responseFormat");
		if (respFormat != null) {
			sb.append(respFormat).append(System.lineSeparator());
		}

		return sb.toString();
	}

	private String replaceCharTokens(String template, String sysName, int sysAge, String sysGender,
			String usrName, int usrAge, String usrGender) {
		template = PromptResourceUtil.replaceToken(template, "sysName", sysName);
		template = PromptResourceUtil.replaceToken(template, "sysAge", String.valueOf(sysAge));
		template = PromptResourceUtil.replaceToken(template, "sysGender", sysGender);
		template = PromptResourceUtil.replaceToken(template, "usrName", usrName);
		template = PromptResourceUtil.replaceToken(template, "usrAge", String.valueOf(usrAge));
		template = PromptResourceUtil.replaceToken(template, "usrGender", usrGender);
		return template;
	}

	private String callEvalLLM(BaseRecord user, BaseRecord chatConfig, String evalPrompt) {
		try {
			BaseRecord resolvedConfig = OlioUtil.getFullRecord(chatConfig);
			if (resolvedConfig == null) resolvedConfig = chatConfig;

			String model = resolvedConfig.get("analyzeModel");
			if (model == null || model.isEmpty()) model = resolvedConfig.get("model");

			Chat chat = new Chat(user, resolvedConfig, null);
			chat.setPersistSession(false);
			OpenAIRequest areq = new OpenAIRequest();
			areq.setModel(model);
			areq.setStream(false);

			try {
				areq.set("temperature", 0.3);
				areq.set("top_p", 0.5);
				String tokField = ChatUtil.getMaxTokenField(resolvedConfig);
				if (tokField != null && !tokField.isEmpty()) {
					areq.set(tokField, 1024);
				}
			} catch (Exception e) {
				logger.warn("InteractionEvaluator: Error setting options: " + e.getMessage());
			}

			OpenAIMessage sysMsg = new OpenAIMessage();
			sysMsg.setRole("system");
			String sysContent = PromptResourceUtil.getString(RESOURCE, "system");
			sysMsg.setContent(sysContent != null ? sysContent : "You are a conversation analyst. Classify the current state of an ongoing interaction between two characters. Respond only in JSON format.");
			areq.addMessage(sysMsg);

			OpenAIMessage userMsg = new OpenAIMessage();
			userMsg.setRole("user");
			userMsg.setContent(evalPrompt);
			areq.addMessage(userMsg);

			OpenAIResponse resp = chat.chat(areq);
			if (resp == null) return null;

			BaseRecord msg = resp.get("message");
			if (msg != null) {
				String content = msg.get("content");
				return sanitizeJson(content);
			}
			List<BaseRecord> choices = resp.get("choices");
			if (choices != null && !choices.isEmpty()) {
				BaseRecord choice = choices.get(0);
				BaseRecord message = choice.get("message");
				if (message != null) {
					return sanitizeJson(message.get("content"));
				}
			}
			return null;
		} catch (Exception e) {
			logger.error("InteractionEvaluator: LLM call failed: " + e.getMessage());
			return null;
		}
	}

	/// Extract clean JSON from LLM response (strip code blocks if present).
	private String sanitizeJson(String response) {
		if (response == null) return null;
		String json = response.trim();
		if (json.startsWith("```")) {
			int start = json.indexOf('{');
			int end = json.lastIndexOf('}');
			if (start >= 0 && end > start) {
				json = json.substring(start, end + 1);
			}
		}
		return json;
	}
}
