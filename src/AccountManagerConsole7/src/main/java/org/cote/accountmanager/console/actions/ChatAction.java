package org.cote.accountmanager.console.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class ChatAction extends CommonAction implements IAction{
	public static final Logger logger = LogManager.getLogger(ChatAction.class);
	public ChatAction() {
		
	}
	public void addOptions(Options options) {
		options.addOption("wearable", true, "Wearables");
		options.addOption("qualities", true, "Qualities");
		options.addOption("statistics", true, "Statistics");
		options.addOption("personality", true, "Personality");
		options.addOption("person", true, "Person");
		options.addOption("chat", false, "Start chat console");
		options.addOption("chat2", false, "Start chat console");
		options.addOption("outfit", true, "Create outfit");
		options.addOption("olio", false, "Load the Olio Context");
		options.addOption("party", false, "Generic bit to restrict parties");
		options.addOption("show", false, "Generic bit");
		options.addOption("detailed", false, "Generic bit used to enable pattern and fabric descriptions of clothes.");
		options.addOption("setting", false, "Generic bit to create a random setting instead of the character's context location");
		options.addOption("scene", false, "Generic bit to include a basic scene guidance (including any interaction)");
		options.addOption("prompt", true, "Chat prompt");
		options.addOption("iprompt", true, "Chat prompt for interactions");
		options.addOption("model", true, "Generic name for a model");
		options.addOption("interact", false, "Generic bit to create a random interaction between two characters.  The -scene option must be also enabled.");
		options.addOption("character1", true, "Name of character");
		options.addOption("character2", true, "Name of character");
		options.addOption("remind", true, "Bit indicating to include instruction reminders every n exchanges");
		options.addOption("rating", true, "ESRB rating guidance for generated content (E, E10, T, M)");
		options.addOption("rpg", false, "Bit indicating to use the RPG prompt template");
		options.addOption("assist", false, "Bit indicating to add additional guidance to the assistant");
	}
	@Override
	public void handleCommand(CommandLine cmd) {
		// TODO Auto-generated method stub
		
	}
	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		// TODO Auto-generated method stub
		OlioContext octx = null;
		BaseRecord epoch = null;
		List<BaseRecord> pop = new ArrayList<>();
		// List<BaseRecord> party1 = new ArrayList<>();
		// List<BaseRecord> party2 = new ArrayList<>();
		BaseRecord char1 = null;
		BaseRecord char2 = null;
		BaseRecord inter = null;
		BaseRecord evt = null;
		BaseRecord cevt = null;
		if(cmd.hasOption("olio")) {
			// NarrativeUtil.setDescribeApparelColors(cmd.hasOption("detailed"));
			NarrativeUtil.setDescribePatterns(cmd.hasOption("detailed"));
			NarrativeUtil.setDescribeFabrics(cmd.hasOption("detailed"));
			octx = OlioContextUtil.getGridContext(user, getProperties().getProperty("test.datagen.path"), "My Grid Universe", "My Grid World", cmd.hasOption("reset"));
			epoch = octx.startOrContinueEpoch();
			BaseRecord[] locs = octx.getLocations();
			for(BaseRecord lrec : locs) {
				evt = octx.startOrContinueLocationEpoch(lrec);
				cevt = octx.startOrContinueIncrement();
				octx.evaluateIncrement();
				if(cmd.hasOption("party")) {
					List<BaseRecord> party1  = OlioUtil.listGroupPopulation(octx, OlioUtil.getCreatePopulationGroup(octx, "Arena Party 1"));
					List<BaseRecord> party2  = OlioUtil.listGroupPopulation(octx, OlioUtil.getCreatePopulationGroup(octx, "Arena Party 2"));
					pop.addAll(party1);
					pop.addAll(party2);
				}
				else {
					pop.addAll(octx.getPopulation(lrec));
					/// Depending on the staging rule, the population may not yet be dressed or have possessions
					///
					ApparelUtil.outfitAndStage(octx, null, pop);
					ItemUtil.showerWithMoney(octx, pop);
					octx.processQueue();
				}
			}
			
			if(cmd.hasOption("list")) {
				for(BaseRecord p: pop) {
					logger.info(NarrativeUtil.describe(octx, p));
				}
			}
			
			if(cmd.hasOption("character1")) {
				Optional<BaseRecord> brec = pop.stream().filter(r -> cmd.getOptionValue("character1").equals(r.get("firstName"))).findFirst();
				if(brec.isPresent()) {
					char1 = brec.get();
				}
			}
			if(cmd.hasOption("character2")) {
				Optional<BaseRecord> brec = pop.stream().filter(r -> cmd.getOptionValue("character2").equals(r.get("firstName"))).findFirst();
				if(brec.isPresent()) {
					char2 = brec.get();
				}
			}
			
			
			if(char1 != null) {
				if(cmd.hasOption("outfit")) {
					String[] outfit = cmd.getOptionValue("outfit").split(",");
					BaseRecord apparel = ApparelUtil.constructApparel(octx, 0L, char1, outfit);
					IOSystem.getActiveContext().getRecordUtil().createRecord(apparel);
					BaseRecord store = char1.get("store");
					List<BaseRecord> appl = store.get("apparel");
					for(BaseRecord a : appl) {
						IOSystem.getActiveContext().getMemberUtil().member(user, store, "apparel", a, null, false);
					}
					appl.clear();
					appl.add(apparel);
					IOSystem.getActiveContext().getMemberUtil().member(user, store, "apparel", apparel, null, true);
				}
				if(cmd.hasOption("update")) {
					if(cmd.hasOption("wearable") && cmd.hasOption("name")) {
						BaseRecord item = ItemUtil.findStoredItemByName(char1, cmd.getOptionValue("name"));
						if(item != null) {
							ActionUtil.patch(RecordFactory.importRecord(ModelNames.MODEL_WEARABLE, cmd.getOptionValue("wearable")), item);		
						}
					}
					if(cmd.hasOption("qualities") && cmd.hasOption("name")) {
						BaseRecord item = ItemUtil.findStoredItemByName(char1, cmd.getOptionValue("name"));
						if(item != null) {
							List<BaseRecord> qs = item.get("qualities");
							if(qs.size() > 0) {
								ActionUtil.patch(RecordFactory.importRecord(ModelNames.MODEL_QUALITY, cmd.getOptionValue("qualities")), qs.get(0));		
							}
						}
					}
					if(cmd.hasOption("statistics")) {
						/// Patch the full record because some attributes feed into computed values so the computed values won't correctly reflect the dependent update
						ActionUtil.patch(RecordFactory.importRecord(ModelNames.MODEL_CHAR_STATISTICS, cmd.getOptionValue("statistics")), char1.get("statistics"), true);
					}
					if(cmd.hasOption("personality")) {
						/// Patch the full record because some attributes feed into computed values so the computed values won't correctly reflect the dependent update
						ActionUtil.patch(RecordFactory.importRecord(ModelNames.MODEL_PERSONALITY, cmd.getOptionValue("personality")), char1.get("personality"), true);
					}
					if(cmd.hasOption("person")) {
						ActionUtil.patch(RecordFactory.importRecord(ModelNames.MODEL_CHAR_PERSON, cmd.getOptionValue("person")), char1);
					}


				}
				if(cmd.hasOption("show")) {
					logger.info("Describe " + char1.get(FieldNames.FIELD_NAME));;
					logger.info(NarrativeUtil.describe(octx, char1));
				}
				if(cmd.hasOption("inspect")) {
					logger.info(char1.toFullString());
				}
			}
			if(char2 != null) {
				if(cmd.hasOption("show")) {
					logger.info(NarrativeUtil.describe(octx, char2));
				}
				if(cmd.hasOption("inspect")) {
					logger.info(char2.toFullString());
				}

			}
			
			if(char1 != null && char2 != null) {
				if(cmd.hasOption("show")) {	
					CompatibilityEnumType mbtiCompat = MBTIUtil.getCompatibility(char1.get("personality.mbtiKey"), char2.get("personality.mbtiKey"));
					logger.info(char1.get("firstName") + " has " + mbtiCompat.toString() + " compatability with " + char2.get("firstName"));
				}
				if(cmd.hasOption("interact")) {
					for(int i = 0; i < 10; i++) {
						inter = InteractionUtil.randomInteraction(octx, char1, char2);
						if(inter != null) {
							break;
						}
					}
					IOSystem.getActiveContext().getRecordUtil().createRecord(inter);
				}
				
				if(cmd.hasOption("show")) {
					logger.info(NarrativeUtil.describeInteraction(inter));
				}
			}
			

		}
		if(cmd.hasOption("chat2")) {
			Chat chat = new Chat(user);
			chat.setIncludeScene(cmd.hasOption("scene"));
			if(cmd.hasOption("rating")) {
				chat.setRating(ESRBEnumType.valueOf(cmd.getOptionValue("rating")));
			}
			if(cmd.hasOption("setting")) {
				chat.setRandomSetting(true);
			}
			if(cmd.hasOption("rpg")) {
				logger.info(chat.getSystemChatRpgPromptTemplate(octx, evt, cevt, char1, char2, inter, cmd.getOptionValue("iprompt")));
				logger.info(chat.getUserChatRpgPromptTemplate(octx, evt, cevt, char1, char2, inter, cmd.getOptionValue("iprompt")));
				logger.info(chat.getAnnotateChatPromptTemplate(octx, evt, cevt, char1, char2, inter, cmd.getOptionValue("iprompt")));
			}
			else {
				logger.info(chat.getSystemChatPromptTemplate(octx, evt, cevt, char1, char2, inter, cmd.getOptionValue("iprompt")));
				logger.info(chat.getUserChatPromptTemplate(octx, evt, cevt, char1, char2, inter, cmd.getOptionValue("iprompt")));
				logger.info(chat.getAnnotateChatPromptTemplate(octx, evt, cevt, char1, char2, inter, cmd.getOptionValue("iprompt")));
			}
		}
		
		if(cmd.hasOption("chat")) {
			Chat chat = new Chat(user);
			chat.setUseAssist(cmd.hasOption("assist"));
			chat.setIncludeScene(cmd.hasOption("scene"));
			if(cmd.hasOption("remind")) {
				chat.setRemind(Integer.parseInt(cmd.getOptionValue("remind")));
			}
			if(cmd.hasOption("rating")) {
				chat.setRating(ESRBEnumType.valueOf(cmd.getOptionValue("rating")));
			}
			if(cmd.hasOption("setting")) {
				chat.setRandomSetting(true);
			}
			//String model = "llama3:8b-text-q5_1";
			String model = "dolphin-llama3";
			//String model = "llama2-uncensored:7b-chat-q8_0";
			//String model = "zephyr-local";
			//String model = "blue-orchid";
			//String model = "dolphin-mistral";
			if(cmd.hasOption("model")) {
				model = cmd.getOptionValue("model");
			}
			String prompt = "You are assistant, a superhelpful friend to all.";
			String iprompt = null;
			if(cmd.hasOption("prompt")) {
				prompt = cmd.getOptionValue("prompt");
			}
			if(cmd.hasOption("iprompt")) {
				iprompt = cmd.getOptionValue("iprompt");
			}
			chat.setModel(model);
			OllamaRequest req = chat.getChatPrompt(octx, prompt, iprompt, evt, cevt, char1, char2, inter, cmd.hasOption("rpg"));
			// logger.info(char2.toFullString());
			chat.chatConsole(req);
		}
		
	}
	
}
