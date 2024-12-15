package org.cote.accountmanager.olio.schema;

import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.schema.ModelNames;

public class OlioModelNames extends ModelNames {
	public static final String MODEL_ANIMAL = "olio.animal";
	public static final String MODEL_CHAR_PERSON = "olio.charPerson";
	public static final String MODEL_CHAR_STATISTICS = "olio.statistics";
	public static final String MODEL_CHAR_SKILL = "olio.charSkill";
	public static final String MODEL_INSTINCT = "olio.instinct";
	public static final String MODEL_WORLD = "olio.world";

	public static final String MODEL_QUALITY = "olio.quality";
	public static final String MODEL_ITEM = "olio.item";
	public static final String MODEL_ITEM_STATISTICS = "olio.itemStatistics";
	public static final String MODEL_APPAREL = "olio.apparel";
	public static final String MODEL_WEARABLE = "olio.wearable";
	public static final String MODEL_EVENT = "olio.event";
	public static final String MODEL_CHAR_STATE = "olio.state";
	public static final String MODEL_PLAYER_STATE = "olio.playerState";
	public static final String MODEL_ACTION = "olio.action";
	public static final String MODEL_ACTION_RESULT = "olio.actionResult";
	public static final String MODEL_ACTION_PARAMETERS = "olio.actionParameters";
	public static final String MODEL_SCHEDULE = "olio.schedule";
	public static final String MODEL_STORE = "olio.store";
	public static final String MODEL_INVENTORY_ENTRY = "olio.inventoryEntry";
	public static final String MODEL_BUILDER = "olio.builder";
	public static final String MODEL_REALM = "olio.realm";
	public static final String MODEL_POI = "olio.pointOfInterest";
	public static final String MODEL_INTERACTION = "olio.interaction";
	public static final String MODEL_NARRATIVE = "olio.narrative";
	public static final String MODEL_CHAT_CONFIG = "olio.llm.chatConfig";
	public static final String MODEL_PROMPT_CONFIG = "olio.llm.promptConfig";
	public static final String MODEL_PROMPT_RACE_CONFIG = "olio.llm.promptRaceConfig";
	public static final String MODEL_EPISODE = "olio.llm.episode";
	
	public static final String MODEL_SD_CONFIG = "olio.sd.sdConfig";
	public static final String MODEL_SD_CONFIG_DATA = "olio.sd.sdConfigData";
	
	public static final String MODEL_VECTOR_CHAT_HISTORY = "olio.llm.vectorChatHistory";
	public static final String MODEL_VECTOR_CHAT_HISTORY_LIST = "olio.llm.vectorChatHistoryList";
	
	//static {
	private static boolean prep = false; 

	public static List<String> MODELS = Arrays.asList(
		MODEL_CHAR_PERSON, MODEL_INSTINCT, 
		MODEL_QUALITY, MODEL_ITEM, MODEL_WEARABLE, MODEL_APPAREL, MODEL_EVENT, MODEL_CHAR_STATISTICS, MODEL_CHAR_STATE,
		MODEL_ACTION, MODEL_ACTION_RESULT, MODEL_ACTION_PARAMETERS, MODEL_SCHEDULE, MODEL_INVENTORY_ENTRY, MODEL_STORE, MODEL_BUILDER, MODEL_ITEM_STATISTICS, MODEL_ANIMAL, MODEL_REALM, MODEL_INTERACTION,
		MODEL_NARRATIVE, MODEL_CHAT_CONFIG, MODEL_PROMPT_CONFIG, MODEL_PROMPT_RACE_CONFIG, MODEL_POI, MODEL_WORLD, MODEL_EPISODE, MODEL_SD_CONFIG, MODEL_SD_CONFIG_DATA,
		MODEL_VECTOR_CHAT_HISTORY, MODEL_VECTOR_CHAT_HISTORY_LIST
	);
	
	public static void use() {
		if(!prep) {
			ModelNames.MODELS.addAll(MODELS);
			prep = true;
		}
	}
	
	
	//}
	
}
