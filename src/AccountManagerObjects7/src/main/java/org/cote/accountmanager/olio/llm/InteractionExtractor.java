package org.cote.accountmanager.olio.llm;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.MemoryUtil;

/// Extracts and persists olio.interaction records from conversation segments.
/// Runs in the keyframe pipeline after memory extraction (Phase 3, MemoryRefactor2).
///
/// Unlike InteractionEvaluator (which returns a transient JSON chirp),
/// this class creates durable interaction records and links them to
/// extracted memories via temporal pinning (interactionId field).
public class InteractionExtractor {

	private static final Logger logger = LogManager.getLogger(InteractionExtractor.class);
	private static final String INTERACTION_GROUP_PATH = "~/Interactions";
	private static final String PROMPT_NAME = "interactionExtraction";

	private InteractionExtractor() {}

	/// Extract and persist an interaction from a conversation segment.
	/// Called from the keyframe pipeline AFTER memory extraction.
	///
	/// @param user The context user
	/// @param chatConfig The chatConfig with character references and LLM settings
	/// @param systemChar The system character record
	/// @param userChar The user character record
	/// @param messages The message list from the snapshot request
	/// @param conversationId The chat config objectId used as conversation identifier
	/// @return persisted olio.interaction record, or null if type=NONE or on failure
	public static BaseRecord extractInteraction(
			BaseRecord user, BaseRecord chatConfig,
			BaseRecord systemChar, BaseRecord userChar,
			List<OpenAIMessage> messages, String conversationId) {

		if (user == null || chatConfig == null || systemChar == null || userChar == null) {
			logger.warn("InteractionExtractor: null parameter(s)");
			return null;
		}
		if (messages == null || messages.isEmpty()) {
			return null;
		}

		IOSystem.getActiveContext().getReader().populate(systemChar);
		IOSystem.getActiveContext().getReader().populate(userChar);

		String sysName = systemChar.get(FieldNames.FIELD_FIRST_NAME);
		String usrName = userChar.get(FieldNames.FIELD_FIRST_NAME);
		String setting = chatConfig.get("setting");

		// Build the prompt
		String systemPrompt = buildSystemPrompt(sysName, usrName, setting);
		if (systemPrompt == null) {
			logger.warn("InteractionExtractor: failed to build system prompt");
			return null;
		}

		// Build user message with conversation segment
		String userContent = buildConversationSegment(sysName, usrName, messages);

		// Call LLM
		String jsonResponse = callLLM(user, chatConfig, systemPrompt, userContent);
		if (jsonResponse == null || jsonResponse.isBlank()) {
			logger.info("InteractionExtractor: LLM returned empty response");
			return null;
		}

		// Parse response
		return parseAndPersist(user, chatConfig, systemChar, userChar, jsonResponse, conversationId);
	}

	/// Build the system prompt from the promptTemplate resource.
	private static String buildSystemPrompt(String sysName, String usrName, String setting) {
		BaseRecord templateRec = PromptResourceUtil.loadAsRecord(PROMPT_NAME);
		String prompt;
		if (templateRec != null) {
			prompt = PromptTemplateComposer.composeSystem(templateRec, null, null);
		} else {
			prompt = PromptResourceUtil.getLines(PROMPT_NAME, "system");
		}
		if (prompt == null) {
			return null;
		}
		prompt = PromptResourceUtil.replaceToken(prompt, "systemCharName", sysName);
		prompt = PromptResourceUtil.replaceToken(prompt, "userCharName", usrName);
		prompt = PromptResourceUtil.replaceToken(prompt, "setting", setting != null ? setting : "unspecified");
		return prompt;
	}

	/// Build the conversation segment from recent messages (last 20).
	private static String buildConversationSegment(String sysName, String usrName, List<OpenAIMessage> messages) {
		List<OpenAIMessage> chatMsgs = messages.stream()
			.filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
			.collect(Collectors.toList());

		StringBuilder sb = new StringBuilder();
		sb.append("CONVERSATION:").append(System.lineSeparator());

		int limit = Math.min(chatMsgs.size(), 20);
		int start = chatMsgs.size() - limit;
		for (int i = start; i < chatMsgs.size(); i++) {
			OpenAIMessage msg = chatMsgs.get(i);
			String speaker = "assistant".equals(msg.getRole()) ? sysName : usrName;
			String content = msg.getContent();
			if (content != null && content.length() > 300) {
				content = content.substring(0, 300) + "...";
			}
			sb.append(speaker).append(": ").append(content != null ? content : "").append(System.lineSeparator());
		}
		return sb.toString();
	}

	/// Call the LLM for interaction extraction.
	private static String callLLM(BaseRecord user, BaseRecord chatConfig, String systemPrompt, String userContent) {
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
					areq.set(tokField, 256);
				}
			} catch (Exception e) {
				logger.warn("InteractionExtractor: Error setting LLM options: " + e.getMessage());
			}

			OpenAIMessage sysMsg = new OpenAIMessage();
			sysMsg.setRole("system");
			sysMsg.setContent(systemPrompt);
			areq.addMessage(sysMsg);

			OpenAIMessage userMsg = new OpenAIMessage();
			userMsg.setRole("user");
			userMsg.setContent(userContent);
			areq.addMessage(userMsg);

			OpenAIResponse resp = chat.chat(areq);
			if (resp == null) return null;

			BaseRecord msg = resp.get("message");
			if (msg != null) {
				return sanitizeJson(msg.get("content"));
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
			logger.error("InteractionExtractor: LLM call failed: " + e.getMessage());
			return null;
		}
	}

	/// Parse the LLM JSON response and persist an olio.interaction record.
	/// Returns null if type is NONE or on parse/persistence failure.
	@SuppressWarnings("unchecked")
	public static BaseRecord parseAndPersist(BaseRecord user, BaseRecord chatConfig,
			BaseRecord systemChar, BaseRecord userChar, String jsonResponse, String conversationId) {
		try {
			Map<String, Object> parsed = JSONUtil.getMap(jsonResponse.getBytes(), String.class, Object.class);
			if (parsed == null) {
				logger.warn("InteractionExtractor: failed to parse JSON: " + jsonResponse);
				return null;
			}

			String typeStr = (String) parsed.get("type");
			if (typeStr == null || typeStr.isEmpty() || "NONE".equalsIgnoreCase(typeStr)) {
				logger.info("InteractionExtractor: type=NONE, no interaction record created");
				return null;
			}

			InteractionEnumType interType;
			try {
				interType = InteractionEnumType.valueOf(typeStr.toUpperCase());
			} catch (IllegalArgumentException e) {
				logger.warn("InteractionExtractor: unknown interaction type: " + typeStr);
				return null;
			}

			String description = (String) parsed.get("description");
			if (description != null && description.length() > 128) {
				description = description.substring(0, 128);
			}

			OutcomeEnumType actorOutcome = parseOutcome((String) parsed.get("actorOutcome"));
			OutcomeEnumType interactorOutcome = parseOutcome((String) parsed.get("interactorOutcome"));

			// Use canonical person ordering (lower ID = actor)
			BaseRecord[] canon = MemoryUtil.canonicalPersonOrder(systemChar, userChar);

			return createInteraction(user, canon[0], canon[1], interType, description,
				actorOutcome, interactorOutcome);

		} catch (Exception e) {
			logger.error("InteractionExtractor: parse/persist failed: " + e.getMessage());
			return null;
		}
	}

	/// Parse an outcome string to OutcomeEnumType, defaulting to EQUILIBRIUM.
	private static OutcomeEnumType parseOutcome(String outcomeStr) {
		if (outcomeStr == null || outcomeStr.isEmpty()) {
			return OutcomeEnumType.EQUILIBRIUM;
		}
		try {
			return OutcomeEnumType.valueOf(outcomeStr.toUpperCase());
		} catch (IllegalArgumentException e) {
			return OutcomeEnumType.EQUILIBRIUM;
		}
	}

	/// Create and persist an olio.interaction record using the two-phase PBAC bypass pattern.
	/// Phase 1: Create with scalar fields via AccessPoint.create (PBAC-safe).
	/// Phase 2: Set $flex foreign refs (actor/interactor) via RecordUtil.updateRecord (bypasses PBAC).
	private static BaseRecord createInteraction(BaseRecord user, BaseRecord actor, BaseRecord interactor,
			InteractionEnumType type, String description,
			OutcomeEnumType actorOutcome, OutcomeEnumType interactorOutcome) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, INTERACTION_GROUP_PATH);
			String name = type.name() + " " + java.util.UUID.randomUUID().toString().substring(0, 8);
			plist.parameter(FieldNames.FIELD_NAME, name);

			BaseRecord inter = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_INTERACTION, user, null, plist);

			inter.set(FieldNames.FIELD_TYPE, type);
			inter.set("state", ActionResultEnumType.COMPLETE);
			if (description != null) {
				inter.set(FieldNames.FIELD_DESCRIPTION, description);
			}
			inter.set(OlioFieldNames.FIELD_ACTOR_TYPE, actor.getSchema());
			inter.set("interactorType", interactor.getSchema());
			inter.set("actorOutcome", actorOutcome);
			inter.set("interactorOutcome", interactorOutcome);

			// Phase 1: Persist without foreign refs
			inter = IOSystem.getActiveContext().getAccessPoint().create(user, inter);
			if (inter == null) {
				logger.error("InteractionExtractor: failed to persist interaction record");
				return null;
			}

			// Phase 2: Set actor/interactor foreign refs and update via RecordUtil
			inter.set(OlioFieldNames.FIELD_ACTOR, actor);
			inter.set(OlioFieldNames.FIELD_INTERACTOR, interactor);
			IOSystem.getActiveContext().getRecordUtil().updateRecord(inter);

			logger.info("InteractionExtractor: created interaction type=" + type + " id=" + inter.get(FieldNames.FIELD_OBJECT_ID));
			return inter;

		} catch (Exception e) {
			logger.error("InteractionExtractor: error creating interaction: " + e.getMessage());
			return null;
		}
	}

	/// Link extracted memories to the interaction via $flex foreign reference.
	/// Sets interactionModel + interaction fields on each memory and persists via RecordUtil.
	public static void linkMemoriesToInteraction(List<BaseRecord> memories, BaseRecord interaction) {
		if (memories == null || memories.isEmpty() || interaction == null) return;

		for (BaseRecord memory : memories) {
			try {
				memory.set("interactionModel", OlioModelNames.MODEL_INTERACTION);
				memory.set("interaction", interaction);
				IOSystem.getActiveContext().getRecordUtil().updateRecord(memory);
			} catch (Exception e) {
				logger.warn("InteractionExtractor: failed to link memory to interaction: " + e.getMessage());
			}
		}
		logger.info("InteractionExtractor: linked " + memories.size() + " memories to interaction " + interaction.get(FieldNames.FIELD_OBJECT_ID));
	}

	/// Build a backward-compatible chirp JSON string for the listener.
	/// Matches the format expected by IChatListener.onInteractionEvent().
	public static String buildInteractionChirp(BaseRecord interaction) {
		if (interaction == null) return null;
		try {
			String type = interaction.get(FieldNames.FIELD_TYPE) != null
				? interaction.get(FieldNames.FIELD_TYPE).toString() : "UNKNOWN";
			String actorOutcome = interaction.get("actorOutcome") != null
				? interaction.get("actorOutcome").toString() : "EQUILIBRIUM";
			String interactorOutcome = interaction.get("interactorOutcome") != null
				? interaction.get("interactorOutcome").toString() : "EQUILIBRIUM";
			String description = interaction.get(FieldNames.FIELD_DESCRIPTION);
			if (description == null) description = "";

			String direction = InteractionEnumType.isPositive(InteractionEnumType.valueOf(type))
				? "IMPROVING" : InteractionEnumType.isNegative(InteractionEnumType.valueOf(type))
				? "WORSENING" : "STABLE";

			return "{\"interactionType\": \"" + type + "\""
				+ ", \"outcome\": \"" + (InteractionEnumType.isPositive(InteractionEnumType.valueOf(type)) ? "POSITIVE" : InteractionEnumType.isNegative(InteractionEnumType.valueOf(type)) ? "NEGATIVE" : "NEUTRAL") + "\""
				+ ", \"actorOutcome\": \"" + actorOutcome + "\""
				+ ", \"targetOutcome\": \"" + interactorOutcome + "\""
				+ ", \"relationshipDirection\": \"" + direction + "\""
				+ ", \"summary\": \"" + description.replace("\"", "\\\"") + "\""
				+ "}";
		} catch (Exception e) {
			logger.warn("InteractionExtractor: failed to build chirp: " + e.getMessage());
			return null;
		}
	}

	/// Extract clean JSON from LLM response (strip code blocks if present).
	public static String sanitizeJson(String response) {
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
