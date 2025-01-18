package org.cote.accountmanager.console.actions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;

public class ChatAction extends CommonAction implements IAction{
	public static final Logger logger = LogManager.getLogger(ChatAction.class);
	public ChatAction() {
		
	}
	public void addOptions(Options options) {
		options.addOption("episode", true, "Episodic configuration");
		options.addOption("episodeNumber", true, "Episodic configuration");
		options.addOption("reimage", true, "Bit to regenerate SD images");
		options.addOption("refigure", true, "Bit to regenerate SD figurines");
		options.addOption("style", true, "Bit used to adjust style elements, such as image generation style");
		options.addOption("bodyStyle", true, "Bit used to adjust image generation style for body camera");
		options.addOption("hires", false, "Bit to generate a higher resolution SD image");
		options.addOption("seed", true, "Seed to be used for SD images");
		options.addOption("wearable", true, "Wearables");
		options.addOption(OlioFieldNames.FIELD_QUALITIES, true, "Qualities");
		options.addOption(OlioFieldNames.FIELD_STATISTICS, true, "Statistics");
		options.addOption(FieldNames.FIELD_PERSONALITY, true, "Personality");
		options.addOption("person", true, "Person");
		options.addOption("chat", false, "Start chat console");
		options.addOption("chat2", false, "Start chat console");
		options.addOption("session", true, "Name of a session to use with a chat conversation");
		options.addOption("outfit", true, "Create outfit");
		options.addOption("olio", false, "Load the Olio Context");
		options.addOption("party", false, "Generic bit to restrict parties");
		options.addOption("show", false, "Generic bit");
		options.addOption("showSD", false, "Generic bit to show stable-diffusion prompt");
		options.addOption("detailed", false, "Generic bit used to enable pattern and fabric descriptions of clothes.");
		options.addOption("setting", true, "Generic bit to create a random setting instead of the character's context location");
		options.addOption("scene", false, "Generic bit to include a basic scene guidance (including any interaction)");
		options.addOption("prompt", true, "Chat prompt");
		options.addOption("duel", true, "Dualing chat prompts (-dual #)");
		options.addOption("prune", false, "Bit indicating to auto-prune conversation threads.");
		options.addOption("auto", false, "Bit indicating to auto generate (create a brief narrative) of the starting scene");
		//options.addOption("promptConfig", true, "Prompt configuration file");
		options.addOption("chatConfig", true, "Chat configuration");
		options.addOption("promptConfig", true, "Name of user's prompt configuration file - default will be used to create it if it doesn't exist.");
		options.addOption("iprompt", true, "Chat prompt for interactions");
		options.addOption("model", true, "Generic name for a model");
		options.addOption("analyzeModel", true, "Generic name for a model to use for analysis");
		options.addOption("interact", false, "Generic bit to create a random interaction between two characters.  The -scene option must be also enabled.");
		options.addOption("character1", true, "Name of character");
		options.addOption("character2", true, "Name of character");
		//options.addOption("remind", true, "Bit indicating to include instruction reminders every n exchanges");
		options.addOption("rating", true, "ESRB rating guidance for generated content (E, E10, T, M)");
		//options.addOption("rpg", false, "Bit indicating to use the RPG prompt template");
		options.addOption("nlp", true, "Brief command to give via NLP to reinforce immersion");
		options.addOption("jailbreak", false, "Bit indicating to use any JailBreak configuration from a paired prompt configuration");
		options.addOption("assist", false, "Bit indicating to add additional guidance to the assistant");
		options.addOption( "scan", false, "Bit indicating to perform a scan of the gallery");
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
		List<BaseRecord> inters = new ArrayList<>();
		BaseRecord inter = null;
		BaseRecord evt = null;
		BaseRecord cevt = null;
		String genSet = null;
		int seed = 0;
		SDUtil sdu = new SDUtil();
		if(cmd.hasOption("seed")) {
			seed = Integer.parseInt(cmd.getOptionValue("seed"));
		}
		if(cmd.hasOption("setting") && cmd.hasOption("reimage")) {
			genSet = cmd.getOptionValue("setting");
			if(genSet.equals("random")) {
				genSet = NarrativeUtil.getRandomSetting();
			}
		}
		
		if(cmd.hasOption("import") && cmd.hasOption(FieldNames.FIELD_PATH)) {
			if(cmd.hasOption("chatConfig") && cmd.hasOption("promptConfig") && cmd.hasOption("session")) {
				logger.info("Import session " + cmd.getOptionValue(FieldNames.FIELD_PATH));
				BaseRecord cfg = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));
				BaseRecord prompt = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
				OllamaRequest req = JSONUtil.importObject(FileUtil.getFileAsString(cmd.getOptionValue(FieldNames.FIELD_PATH)), OllamaRequest.class);
				ChatUtil.saveSession(user, req, ChatUtil.getSessionName(user, cfg, prompt, cmd.getOptionValue("session")));
			}
			else if(cmd.hasOption("chatConfig")) {
				logger.info("Import chat config " + cmd.getOptionValue(FieldNames.FIELD_PATH));
				BaseRecord cfg = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));
				String patch = FileUtil.getFileAsString(cmd.getOptionValue(FieldNames.FIELD_PATH));
				IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(OlioModelNames.MODEL_CHAT_CONFIG, patch), cfg);
			}
			else if(cmd.hasOption("promptConfig")) {
				logger.info("Import prompt config " + cmd.getOptionValue(FieldNames.FIELD_PATH));
				BaseRecord prompt = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
				String patch = FileUtil.getFileAsString(cmd.getOptionValue(FieldNames.FIELD_PATH));
				BaseRecord opatch = RecordFactory.importRecord(OlioModelNames.MODEL_PROMPT_CONFIG, patch);
				IOSystem.getActiveContext().getRecordUtil().patch(opatch, prompt);
			}
		}
		
		if(cmd.hasOption("delete")) {
			if(cmd.hasOption("chatConfig") && cmd.hasOption("promptConfig") && cmd.hasOption("session")) {
				logger.info("Delete session " + cmd.getOptionValue("session"));
				BaseRecord cfg = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));
				BaseRecord prompt = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
				BaseRecord chat = ChatUtil.getSessionData(user, ChatUtil.getSessionName(user, cfg, prompt, cmd.getOptionValue("session")));
				if(chat != null) {
					IOSystem.getActiveContext().getAccessPoint().delete(user, chat);
				}
			}
			else if(cmd.hasOption("chatConfig")) {
				logger.info("Delete chat config " + cmd.getOptionValue("chatConfig"));
				BaseRecord cfg = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));
				if(cfg != null) {
					IOSystem.getActiveContext().getAccessPoint().delete(user, cfg);
				}
			}
			else if(cmd.hasOption("promptConfig")) {
				logger.info("Delete prompt config " + cmd.getOptionValue("promptConfig"));
				BaseRecord prompt = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
				if(prompt != null) {
					IOSystem.getActiveContext().getAccessPoint().delete(user, prompt);
				}

			}
		}
		
		NarrativeUtil.setDescribeApparelColors(cmd.hasOption("detailed"));
		NarrativeUtil.setDescribePatterns(cmd.hasOption("detailed"));
		NarrativeUtil.setDescribeFabrics(cmd.hasOption("detailed"));
		String universeName = "My Grid Universe";
		String worldName = "My Grid World";
		
		if(cmd.hasOption("chatConfig")) {
			
			BaseRecord cfg = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));
			if(cmd.hasOption("list") && cmd.hasOption("episode")) {
				logger.info("List episodes");	
				List<BaseRecord> eps = cfg.get("episodes");
				eps.forEach(e -> {
					int number = e.get("number");
					String theme = e.get("theme");
					logger.info("Episode #" + number + " - " + theme);
					List<String> stages = e.get("stages");
					
					stages.forEach(s -> {
						logger.info("Stage - " + s);	
					});
					logger.info("JSON: " + e.toString());
				});
			}
			
			if(cmd.hasOption("reset") && cmd.hasOption("episode")) {
				logger.info("Reset episode");
				List<BaseRecord> eps = cfg.get("episodes");
				eps.forEach(e -> {
					e.setValue("completed", false);
					e.setValue("summary", null);
				});
				cfg = IOSystem.getActiveContext().getAccessPoint().update(user, cfg);
			}
			
			if(cmd.hasOption("update") && cmd.hasOption("episode")) {
				BaseRecord ep = RecordFactory.importRecord(OlioModelNames.MODEL_EPISODE, cmd.getOptionValue("episode"));
				int number = ep.get("number");
				BaseRecord mep = null;
				if(number > 0) mep = PromptUtil.getEpisode(cfg, number);
				if(mep != null) {
					logger.info("Patching episode #" + number);
					if(ep.hasField("theme")) mep.setValue("theme", ep.get("theme"));
					if(ep.hasField("summary")) mep.setValue("summary", ep.get("summary"));
					if(ep.hasField("stages")) mep.setValue("stages", ep.get("stages"));
					if(ep.hasField("episodeAssist")) mep.setValue("episodeAssist", ep.get("episodeAssist"));
					
				}
				else {
					logger.info("Create episode");
					List<BaseRecord> eps = cfg.get("episodes");
					//BaseRecord lep = PromptUtil.getLastEpisode(cfg);
					number = eps.size() + 1;
					logger.info("Adding episode #" + number);
					ep.setValue("number", number);
					
					eps.add(ep);
				}
				cfg = IOSystem.getActiveContext().getAccessPoint().update(user, cfg);
			}
			
			if(cmd.hasOption("auto") && cmd.hasOption("update")) {
				String set = cmd.getOptionValue("setting");
				List<BaseRecord> ainters = cfg.get(OlioFieldNames.FIELD_INTERACTIONS);
	
				if(cfg.get("systemCharacter") != null && cfg.get("userCharacter") != null) {
			
					if(ainters.size() > 0) {
						if(set == null || set.equals("random")) {
							set = NarrativeUtil.getRandomSetting();
						}
						logger.info("Generating the chat scene...");
						BaseRecord i2 = ainters.get((new Random()).nextInt(ainters.size()));
						BaseRecord pcfg = null;
						if(cmd.hasOption("promptConfig")) {
							pcfg = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
						}
						ChatUtil.generateAutoScene(octx, cfg, pcfg, i2, set, true);
						logger.info("Scene: " + cfg.get("scene"));
						// cfg.setValue("scene", scene);
						//Queue.queueUpdate(cfg, new String[] {"scene"});
						//Queue.processQueue(user);
						
					}
					else {
						logger.warn("Chat config doesn't define any interactions");
					}
				}
				else {
					logger.warn("Chat config doesn't define both characters");
				}
			}
		}
		if(cmd.hasOption("olio")) {
			octx = OlioContextUtil.getGridContext(user, getProperties().getProperty("test.datagen.path"), universeName, worldName, cmd.hasOption("reset"));
			List<BaseRecord> rlms = octx.getRealms();
			for(BaseRecord r : rlms) {
				if(cmd.hasOption("party")) {
					List<BaseRecord> party1  = OlioUtil.listGroupPopulation(octx, OlioUtil.getCreatePopulationGroup(octx, "Arena Party 1"));
					List<BaseRecord> party2  = OlioUtil.listGroupPopulation(octx, OlioUtil.getCreatePopulationGroup(octx, "Arena Party 2"));
					pop.addAll(party1);
					pop.addAll(party2);
				}
				else {
					pop.addAll(octx.getRealmPopulation(r));
					/// Depending on the staging rule, the population may not yet be dressed or have possessions
					///
					ApparelUtil.outfitAndStage(octx, null, pop);
					ItemUtil.showerWithMoney(octx, pop);
					Queue.processQueue();
				}
			}
			
			BaseRecord olioAdminRole = IOSystem.getActiveContext().getPathUtil().makePath(octx.getOlioUser(), ModelNames.MODEL_ROLE, "~/Roles/Olio Admin", RoleEnumType.USER.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
			if(!IOSystem.getActiveContext().getMemberUtil().isMember(user, olioAdminRole,  null)) {
				boolean enabled = IOSystem.getActiveContext().getMemberUtil().member(octx.getOlioUser(), olioAdminRole, user, null, true);
				logger.info("Configuring user for olio world access: " + enabled);
			}
			if (cmd.hasOption("scan")) {
				octx.scanNestedGroups(octx.getWorld(), OlioFieldNames.FIELD_GALLERY, true);
			}
			if(cmd.hasOption("list")) {
				if(cmd.hasOption("reimage")) {
					BaseRecord sdConfig = SDUtil.randomSDConfig();
					sdu.generateSDImages(octx, pop, sdConfig, genSet, cmd.getOptionValue("style"), cmd.getOptionValue("bodyStyle"), Integer.parseInt(cmd.getOptionValue("reimage")), cmd.hasOption("export"), cmd.hasOption("hires"), seed);
					octx.scanNestedGroups(octx.getWorld(), OlioFieldNames.FIELD_GALLERY, true);
				}
				if(cmd.hasOption("refigure")) {
					sdu.generateSDFigurines(octx, pop, Integer.parseInt(cmd.getOptionValue("refigure")), cmd.hasOption("export"), cmd.hasOption("hires"), seed);
					octx.scanNestedGroups(octx.getWorld(), OlioFieldNames.FIELD_GALLERY, true);
				}
				for(BaseRecord p: pop) {
					logger.info(NarrativeUtil.describe(octx, p));
				}
			}
			
			if(cmd.hasOption("character1")) {
				Optional<BaseRecord> brec = pop.stream().filter(r -> cmd.getOptionValue("character1").equals(r.get(FieldNames.FIELD_FIRST_NAME))).findFirst();
				if(brec.isPresent()) {
					char1 = brec.get();
				}
			}
			if(cmd.hasOption("character2")) {
				Optional<BaseRecord> brec = pop.stream().filter(r -> cmd.getOptionValue("character2").equals(r.get(FieldNames.FIELD_FIRST_NAME))).findFirst();
				if(brec.isPresent()) {
					char2 = brec.get();
				}
			}
			
			if(char1 != null) {

				if(cmd.hasOption("outfit") && !"auto".equals(cmd.getOptionValue("outfit"))) {
					String[] outfit = cmd.getOptionValue("outfit").split(",");
					ApparelUtil.applyAutfit(octx, char1, outfit);
				}
				if(cmd.hasOption("update")) {
					if(cmd.hasOption("wearable") && cmd.hasOption(FieldNames.FIELD_NAME)) {
						BaseRecord item = ItemUtil.findStoredItemByName(char1, cmd.getOptionValue(FieldNames.FIELD_NAME));
						if(item != null) {
							IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(OlioModelNames.MODEL_WEARABLE, cmd.getOptionValue("wearable")), item);		
						}
					}
					if(cmd.hasOption(OlioFieldNames.FIELD_QUALITIES) && cmd.hasOption(FieldNames.FIELD_NAME)) {
						BaseRecord item = ItemUtil.findStoredItemByName(char1, cmd.getOptionValue(FieldNames.FIELD_NAME));
						if(item != null) {
							List<BaseRecord> qs = item.get(OlioFieldNames.FIELD_QUALITIES);
							if(qs.size() > 0) {
								IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(OlioModelNames.MODEL_QUALITY, cmd.getOptionValue(OlioFieldNames.FIELD_QUALITIES)), qs.get(0));		
							}
						}
					}
					if(cmd.hasOption(OlioFieldNames.FIELD_STATISTICS)) {
						/// Patch the full record because some attributes feed into computed values so the computed values won't correctly reflect the dependent update
						IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(OlioModelNames.MODEL_CHAR_STATISTICS, cmd.getOptionValue(OlioFieldNames.FIELD_STATISTICS)), char1.get(OlioFieldNames.FIELD_STATISTICS), true);
					}
					if(cmd.hasOption(FieldNames.FIELD_PERSONALITY)) {
						/// Patch the full record because some attributes feed into computed values so the computed values won't correctly reflect the dependent update
						IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(ModelNames.MODEL_PERSONALITY, cmd.getOptionValue(FieldNames.FIELD_PERSONALITY)), char1.get(FieldNames.FIELD_PERSONALITY), true);
					}
					if(cmd.hasOption("person")) {
						IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(OlioModelNames.MODEL_CHAR_PERSON, cmd.getOptionValue("person")), char1);
					}


				}
				if(cmd.hasOption("reimage") && !cmd.hasOption("chatConfig")) {
					/// Need to overwrite the 'narrative', not just add another one
					//char1.setValue("narrative", null);
					BaseRecord sdConfig = SDUtil.randomSDConfig();
					sdu.generateSDImages(octx, Arrays.asList(char1), sdConfig, genSet, cmd.getOptionValue("style"), cmd.getOptionValue("bodyStyle"), Integer.parseInt(cmd.getOptionValue("reimage")), cmd.hasOption("export"), cmd.hasOption("hires"), seed);
					octx.scanNestedGroups(octx.getWorld(), OlioFieldNames.FIELD_GALLERY, true);
				}
				if(cmd.hasOption("refigure") && !cmd.hasOption("chatConfig")) {
					sdu.generateSDFigurines(octx, Arrays.asList(char1), Integer.parseInt(cmd.getOptionValue("refigure")), cmd.hasOption("export"), cmd.hasOption("hires"), seed);
				}
				if(cmd.hasOption("show")) {
					logger.info("Describe " + char1.get(FieldNames.FIELD_NAME));;
					logger.info(NarrativeUtil.describe(octx, char1));
				}
				if(cmd.hasOption("showSD")) {
					logger.info("Stable Diffusion Prompts for " + char1.get(FieldNames.FIELD_NAME));
					logger.info("Positive:");
					logger.info(NarrativeUtil.getSDPrompt(octx, char1, cmd.getOptionValue("setting")));
					logger.info("Negative");;
					logger.info(NarrativeUtil.getSDNegativePrompt(char1));
					logger.info("Figurine:");;
					logger.info(NarrativeUtil.getSDFigurinePrompt(ProfileUtil.getProfile(octx, char1)));

				}
				if(cmd.hasOption("inspect")) {
					logger.info(char1.toFullString());
				}
			}
			if(char2 != null) {
				if(cmd.hasOption("reimage") && !cmd.hasOption("chatConfig")) {
					/// Need to overwrite the 'narrative', not just add another one
					//char2.setValue("narrative", null);
					sdu.generateSDImages(octx, Arrays.asList(char2), genSet, cmd.getOptionValue("style"), cmd.getOptionValue("bodyStyle"), Integer.parseInt(cmd.getOptionValue("reimage")), cmd.hasOption("export"), cmd.hasOption("hires"), seed);
					octx.scanNestedGroups(octx.getWorld(), OlioFieldNames.FIELD_GALLERY, true);
				}
				if(cmd.hasOption("show")) {
					logger.info(NarrativeUtil.describe(octx, char2));
				}
				if(cmd.hasOption("showSD")) {
					logger.info(NarrativeUtil.getSDPrompt(octx, char2, cmd.getOptionValue("setting")));
					logger.info(NarrativeUtil.getSDNegativePrompt(char2));
				}
				if(cmd.hasOption("inspect")) {
					logger.info(char2.toFullString());
				}

			}
			
			if(char1 != null && char2 != null && cmd.hasOption("promptConfig") && "auto".equals(cmd.getOptionValue("outfit")) && cmd.hasOption("model")) {
				String[] settings = NarrativeUtil.getSettings();
				BaseRecord pcfg = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
				logger.info("Generating outfits for all settings - it's suggested you use one male and one female character");
				List<String> outs = new ArrayList<>();
				for(String set : settings) {
					long start = System.currentTimeMillis();
					String[] aset = set.split("\\|");
					String dset = aset[0] + ", circa " + aset[1] + ".";
					outs.add(set);
					String out1 = char1.get(FieldNames.FIELD_GENDER) + ":" + ChatUtil.getAutoOutfit(pcfg, char1, cmd.getOptionValue("model"), dset);
					outs.add(out1);
					String out2 = char2.get(FieldNames.FIELD_GENDER) + ":" + ChatUtil.getAutoOutfit(pcfg, char2, cmd.getOptionValue("model"), dset);
					outs.add(out2);
					long stop = System.currentTimeMillis();
					logger.info(dset + " (" + (stop - start) + "ms)");
					logger.info(out1);
					logger.info(out2);
				}
				for(String o : outs) {
					System.out.println(o);
				}
				
				
				
				return;
				
			}
			if(char1 != null && char2 != null) {
				if(cmd.hasOption("show")) {	
					CompatibilityEnumType mbtiCompat = MBTIUtil.getCompatibility(char1.get("personality.mbtiKey"), char2.get("personality.mbtiKey"));
					logger.info(char1.get(FieldNames.FIELD_FIRST_NAME) + " has " + mbtiCompat.toString() + " compatability with " + char2.get(FieldNames.FIELD_FIRST_NAME));
				}
				
				if(cmd.hasOption("interact")) {
					for(int i = 0; i < 10; i++) {
						inter = InteractionUtil.randomInteraction(octx, char1, char2);
						if(inter != null) {
							inters.add(inter);
							//break;
						}
					}
					IOSystem.getActiveContext().getRecordUtil().createRecords(inters.toArray(new BaseRecord[0]));
				}
				
				if(cmd.hasOption("show")) {
					logger.info(NarrativeUtil.describeInteraction(inter));
				}
			}
			
			if(cmd.hasOption("chatConfig")) {
				logger.info("Configure chat");
				BaseRecord cfg = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));
				String set = cmd.getOptionValue("setting");
				List<BaseRecord> ainters = cfg.get(OlioFieldNames.FIELD_INTERACTIONS);

				try {
					cfg.set("event", cevt);
					cfg.set("universeName", universeName);
					cfg.set("worldName", worldName);
					cfg.set("startMode", "system");
					cfg.set("assist", cmd.hasOption("assist"));
					cfg.set("useNLP", cmd.hasOption("nlp"));
					cfg.set("nlpCommand", cmd.getOptionValue("nlp"));
					cfg.set("useJailBreak", cmd.hasOption("jailbreak"));
					
					cfg.set("setting", set);
					cfg.set("includeScene", cmd.hasOption("scene"));
					cfg.set("prune", cmd.hasOption("prune"));
					if(cmd.hasOption("rating")) {
						cfg.set("rating", ESRBEnumType.valueOf(cmd.getOptionValue("rating")));
					}
					cfg.set("llmModel", cmd.getOptionValue("model"));
					cfg.set("llmAnalyzeModel", cmd.getOptionValue("analyzeModel"));
					if(char1 != null && char2 != null) {
						cfg.set("systemCharacter", char1);
						cfg.set("userCharacter", char2);
						for(BaseRecord i : inters) {
							if(i != null) {
								IOSystem.getActiveContext().getMemberUtil().member(user, cfg, OlioFieldNames.FIELD_INTERACTIONS, i, null, false);
							}
						}
						cfg.set(OlioFieldNames.FIELD_INTERACTIONS, inters);
						for(BaseRecord i : inters) {
							if(i != null) {
								IOSystem.getActiveContext().getMemberUtil().member(user, cfg, OlioFieldNames.FIELD_INTERACTIONS, i, null, true);
							}
						}
						if(inters.size() > 0 && cmd.hasOption("auto")) {
							if(set != null && set.equals("random")) {
								set = NarrativeUtil.getRandomSetting();
								logger.info("Random setting: " + set);
								//cfg.set("setting", set);
							}
							logger.info("Generating the chat scene...");
							BaseRecord i2 = inters.get((new Random()).nextInt(inters.size()));
							BaseRecord pcfg = null;
							if(cmd.hasOption("promptConfig")) {
								pcfg = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
							}

							ChatUtil.generateAutoScene(octx, cfg, pcfg, i2, set, true);
							logger.info("Scene: " + cfg.get("scene"));
							
						}
						if(cmd.hasOption("reimage")) {
							// char1.setValue("narrative", null);
							// char2.setValue("narrative", null);
							
							sdu.generateSDImages(octx, Arrays.asList(char1, char2), cmd.getOptionValue("setting"), cmd.getOptionValue("style"), cmd.getOptionValue("bodyStyle"), Integer.parseInt(cmd.getOptionValue("reimage")), cmd.hasOption("export"), cmd.hasOption("hires"), seed);
							octx.scanNestedGroups(octx.getWorld(), OlioFieldNames.FIELD_GALLERY, true);

						}
					}
					cfg.set(FieldNames.FIELD_TERRAIN, NarrativeUtil.getTerrain(octx, char2));
					// cfg.set("systemNarrative", NarrativeUtil.getNarrative(octx, char1, cmd.getOptionValue("setting")));
					// cfg.set("userNarrative", NarrativeUtil.getNarrative(octx, char2, cmd.getOptionValue("setting")));
					NarrativeUtil.describePopulation(octx, cfg);
					cfg = IOSystem.getActiveContext().getAccessPoint().update(user, cfg);

				
				}
				catch(ModelNotFoundException | FieldException | ValueException e) {
					logger.error(e);
				}

			}
			

		}
		if(cmd.hasOption("export") && cmd.hasOption(FieldNames.FIELD_PATH)) {
			if(cmd.hasOption("chatConfig") && cmd.hasOption("promptConfig") && cmd.hasOption("session")) {
				logger.info("Export chat request " + cmd.getOptionValue("session") + " to "+ cmd.getOptionValue(FieldNames.FIELD_PATH));
				BaseRecord cfg = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));
				BaseRecord prompt = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
				OllamaRequest req = ChatUtil.getSession(user, ChatUtil.getSessionName(user, cfg, prompt, cmd.getOptionValue("session")));
				FileUtil.emitFile(cmd.getOptionValue(FieldNames.FIELD_PATH), JSONUtil.exportObject(req));
			}
			else if(cmd.hasOption("chatConfig")) {
				logger.info("Export chat config " + cmd.getOptionValue("chatConfig") + " to "+ cmd.getOptionValue(FieldNames.FIELD_PATH));
				BaseRecord cfg = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));
				FileUtil.emitFile(cmd.getOptionValue(FieldNames.FIELD_PATH), cfg.toForeignFilteredString());
			}
			else if(cmd.hasOption("promptConfig")) {
				logger.info("Export prompt config " + cmd.getOptionValue("promptConfig") + " to "+ cmd.getOptionValue(FieldNames.FIELD_PATH));
				BaseRecord prompt = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
				FileUtil.emitFile(cmd.getOptionValue(FieldNames.FIELD_PATH), prompt.copyDeidentifiedRecord().toFullString());
			}
		}
		
		if(cmd.hasOption("chat")) {
			
			BaseRecord promptConfig = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
			BaseRecord chatConfig = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));

			Chat chat = new Chat(user, chatConfig, promptConfig);
			chat.setFormatOutput(true);
			chat.setSessionName(cmd.getOptionValue("session"));

			OllamaRequest req = null;
			if(cmd.hasOption("session")) {
				String sessionName = ChatUtil.getSessionName(user, chatConfig, promptConfig, cmd.getOptionValue("session"));
				OllamaRequest oreq = ChatUtil.getSession(user, sessionName);
				if(oreq != null) {
					req = oreq;
				}
			}
			if(req == null) {
				req = chat.getChatPrompt();
			}
			chat.chatConsole(req);
		}
		
		if(cmd.hasOption("duel")) {
			int iter = Integer.parseInt(cmd.getOptionValue("duel"));
			BaseRecord promptConfig = ChatUtil.getCreatePromptConfig(user, cmd.getOptionValue("promptConfig"));
			BaseRecord chatConfig = ChatUtil.getCreateChatConfig(user, cmd.getOptionValue("chatConfig"));
			BaseRecord chatConfig2 = chatConfig.copyRecord();
			chatConfig2.setValue("systemCharacter", chatConfig.get("userCharacter"));
			//chatConfig2.setValue("systemNarrative", chatConfig.get("userNarrative"));
			//chatConfig2.setValue("userNarrative", chatConfig.get("systemNarrative"));
			chatConfig2.setValue("userCharacter", chatConfig.get("systemCharacter"));
			chatConfig2.setValue("startMode", "user");
			String setting = chatConfig.get("setting");
			if(setting != null && setting.equals("random")) {
				setting = NarrativeUtil.getRandomSetting();
				chatConfig.setValue("setting", setting);
				chatConfig2.setValue("setting", setting);
			}
			chatConfig2.setValue("useNLP", false);
			Chat chat = new Chat(user, chatConfig, promptConfig);
			chat.setFormatOutput(true);
			chat.setSessionName(cmd.getOptionValue("session"));
			Chat chat2 = new Chat(user, chatConfig2, promptConfig);
			chat2.setFormatOutput(true);
			OllamaRequest req1 = chat.getChatPrompt();
			OllamaRequest req2 = chat2.getChatPrompt();
			if(cmd.hasOption("debug")) {
				logger.info(chatConfig.get("systemCharacter.firstName") + ":");
				logger.info(JSONUtil.exportObject(req1));
				logger.info(chatConfig2.get("systemCharacter.firstName") + ":");
				logger.info(JSONUtil.exportObject(req2));
			}
			String message1 = null;
			String message2 = null;
			logger.info("Chat Duel: " + chatConfig.get("systemCharacter.firstName") + " vs " + chatConfig.get("userCharacter.firstName"));
			for(int i = 0; i < iter; i++) {
				chat.continueChat(req1, message1);

				if(cmd.hasOption("debug")) {
					FileUtil.emitFile("./chat1.json", JSONUtil.exportObject(req1));
				}
				
				message2 = req1.getMessages().get(req1.getMessages().size() - 1).getContent();
				System.out.println(chatConfig.get("systemCharacter.firstName") + " - " + message2);
				/*
				if(message1 == null) {
					chat2.continueChat(req2, null);	
				}
				*/
				chat2.continueChat(req2, message2);
				if(cmd.hasOption("debug")) {
					FileUtil.emitFile("./chat2.json", JSONUtil.exportObject(req2));
				}

				message1 = req2.getMessages().get(req2.getMessages().size() - 1).getContent();
				System.out.println(chatConfig.get("userCharacter.firstName") + " - " + message1);
				
			}

		}
		
	}
	

	
	
	
}
