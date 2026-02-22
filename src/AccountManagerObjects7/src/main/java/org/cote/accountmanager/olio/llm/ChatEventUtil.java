package org.cote.accountmanager.olio.llm;

import java.time.ZonedDateTime;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

/// Manages olio.event lifecycle for chat conversations (Phase 4, MemoryRefactor2).
/// Each chat session gets one event that tracks participants and accumulated interactions.
public class ChatEventUtil {

	private static final Logger logger = LogManager.getLogger(ChatEventUtil.class);
	private static final String EVENT_GROUP_PATH = "~/Events";

	private ChatEventUtil() {}

	/// Get or create the olio.event for a chat conversation.
	/// Idempotent: if chatConfig already has an event bound, returns it.
	/// Otherwise creates a new INTERACT event with systemChar as actor and userChar as participant.
	public static BaseRecord getOrCreateChatEvent(BaseRecord user, BaseRecord chatConfig) {
		if (user == null || chatConfig == null) return null;

		// Check if event already exists on chatConfig
		BaseRecord existing = chatConfig.get("event");
		if (existing != null) {
			long existingId = 0L;
			try { existingId = existing.get(FieldNames.FIELD_ID); } catch (Exception e) { /* stub ref */ }
			if (existingId > 0) {
				return existing;
			}
		}

		BaseRecord systemChar = chatConfig.get("systemCharacter");
		BaseRecord userChar = chatConfig.get("userCharacter");

		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, EVENT_GROUP_PATH);
			String cfgName = chatConfig.get(FieldNames.FIELD_NAME);
			String eventName = "Conversation: " + (cfgName != null ? cfgName : "chat");
			plist.parameter(FieldNames.FIELD_NAME, eventName);

			BaseRecord event = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_EVENT, user, null, plist);

			event.set(FieldNames.FIELD_TYPE, EventEnumType.INTERACT);
			event.set(FieldNames.FIELD_STATE, ActionResultEnumType.IN_PROGRESS);
			event.set(OlioFieldNames.FIELD_EVENT_START, ZonedDateTime.now());
			event.set(OlioFieldNames.FIELD_EVENT_PROGRESS, ZonedDateTime.now());

			// Phase 1: Persist with scalar fields
			event = IOSystem.getActiveContext().getAccessPoint().create(user, event);
			if (event == null) {
				logger.error("ChatEventUtil: failed to create event");
				return null;
			}

			// Phase 2: Set actor/participant lists and update via RecordUtil
			if (systemChar != null) {
				List<BaseRecord> actors = event.get(OlioFieldNames.FIELD_ACTORS);
				actors.add(systemChar);
			}
			if (userChar != null) {
				List<BaseRecord> participants = event.get(OlioFieldNames.FIELD_PARTICIPANTS);
				participants.add(userChar);
			}
			IOSystem.getActiveContext().getRecordUtil().updateRecord(event);

			// Bind event to chatConfig
			chatConfig.set("event", event);
			IOSystem.getActiveContext().getRecordUtil().updateRecord(chatConfig);

			logger.info("ChatEventUtil: created event id=" + event.get(FieldNames.FIELD_OBJECT_ID)
				+ " name=" + eventName);
			return event;

		} catch (Exception e) {
			logger.error("ChatEventUtil: error creating event: " + e.getMessage());
			return null;
		}
	}

	/// Close the chat event — sets state=COMPLETE and eventEnd=now.
	public static void closeChatEvent(BaseRecord user, BaseRecord chatConfig) {
		if (user == null || chatConfig == null) return;

		BaseRecord event = chatConfig.get("event");
		if (event == null) return;

		try {
			IOSystem.getActiveContext().getReader().populate(event);
			event.set(FieldNames.FIELD_STATE, ActionResultEnumType.COMPLETE);
			event.set(OlioFieldNames.FIELD_EVENT_END, ZonedDateTime.now());
			IOSystem.getActiveContext().getRecordUtil().updateRecord(event);
			logger.info("ChatEventUtil: closed event id=" + event.get(FieldNames.FIELD_OBJECT_ID));
		} catch (Exception e) {
			logger.error("ChatEventUtil: error closing event: " + e.getMessage());
		}
	}

	/// Add an interaction to the chat event's interactions list.
	public static void addInteractionToEvent(BaseRecord user, BaseRecord chatConfig, BaseRecord interaction) {
		if (user == null || chatConfig == null || interaction == null) return;

		BaseRecord event = chatConfig.get("event");
		if (event == null) return;

		try {
			IOSystem.getActiveContext().getReader().populate(event);
			List<BaseRecord> interactions = event.get(OlioFieldNames.FIELD_INTERACTIONS);
			interactions.add(interaction);
			IOSystem.getActiveContext().getRecordUtil().updateRecord(event);
			logger.info("ChatEventUtil: added interaction to event, total=" + interactions.size());
		} catch (Exception e) {
			logger.error("ChatEventUtil: error adding interaction to event: " + e.getMessage());
		}
	}
}
