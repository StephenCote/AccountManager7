package org.cote.accountmanager.console.actions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileComparison;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.OllamaOptions;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.personality.PersonalityUtil;
import org.cote.accountmanager.olio.rules.ArenaEvolveRule;
import org.cote.accountmanager.olio.rules.ArenaInitializationRule;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.SystemPermissionEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ResourceUtil;

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
		options.addOption("list", false, "Generic bit to list values");
		options.addOption("party", false, "Generic bit to restrict parties");
		options.addOption("show", false, "Generic bit");
		options.addOption("prompt", true, "Chat prompt");
		options.addOption("iprompt", true, "Chat prompt for interactions");
		options.addOption("model", true, "Generic name for a model");
		options.addOption("interact", false, "Generic bit");
		options.addOption("character1", true, "Name of character");
		options.addOption("character2", true, "Name of character");
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
		if(cmd.hasOption("olio")) {

			octx = getGridContext(user, getProperties().getProperty("test.datagen.path"), "My Grid Universe", "My Grid World", cmd.hasOption("reset"));
			epoch = octx.startOrContinueEpoch();
			BaseRecord[] locs = octx.getLocations();
			for(BaseRecord lrec : locs) {
				evt = octx.startOrContinueLocationEpoch(lrec);
				BaseRecord cevt = octx.startOrContinueIncrement();
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
					BaseRecord apparel = ApparelUtil.constructApparel(octx, char1, outfit);
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
			logger.info(getSystemChatPromptTemplate(octx, evt, char1, char2, inter, cmd.getOptionValue("iprompt")));
			logger.info(getUserChatPromptTemplate(octx, evt, char1, char2, inter, cmd.getOptionValue("iprompt")));
		}
		
		if(cmd.hasOption("chat")) {
			Chat chat = new Chat(user);
			//String model = "llama2-uncensored:7b-chat-q8_0";
			//String model = "zephyr-local";
			//String model = "blue-orchid";
			String model = "dolphin-mistral";
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
			OllamaRequest req = getChatPrompt(octx, chat, prompt, iprompt, evt, char1, char2, inter);
			// logger.info(char2.toFullString());
			chat.chatConsole(req);
		}
		
	}
	
	private static Pattern locationName = Pattern.compile("\\$\\{location.name\\}");
	private static Pattern locationTerrain = Pattern.compile("\\$\\{location.terrain\\}");
	private static Pattern locationTerrains = Pattern.compile("\\$\\{location.terrains\\}");
	private static Pattern userFirstName = Pattern.compile("\\$\\{user.firstName\\}");
	private static Pattern systemFirstName = Pattern.compile("\\$\\{system.firstName\\}");
	private static Pattern userFullName = Pattern.compile("\\$\\{user.fullName\\}");
	private static Pattern systemFullName = Pattern.compile("\\$\\{system.fullName\\}");

	private static Pattern userCharDesc = Pattern.compile("\\$\\{user.characterDesc\\}");
	private static Pattern systemCharDesc = Pattern.compile("\\$\\{system.characterDesc\\}");
	private static Pattern profileAgeCompat = Pattern.compile("\\$\\{profile.ageCompat\\}");
	private static Pattern profileRomanceCompat = Pattern.compile("\\$\\{profile.romanceCompat\\}");
	private static Pattern profileRaceCompat = Pattern.compile("\\$\\{profile.raceCompat\\}");
	private static Pattern profileLeader = Pattern.compile("\\$\\{profile.leader\\}");
	private static Pattern eventAlign = Pattern.compile("\\$\\{event.alignment\\}");
	private static Pattern animalPop = Pattern.compile("\\$\\{population.animals\\}");
	private static Pattern peoplePop = Pattern.compile("\\$\\{population.people\\}");
	private static Pattern interactDesc = Pattern.compile("\\$\\{interaction.description\\}");

	private static Pattern userPrompt = Pattern.compile("\\$\\{userPrompt\\}");
	
	private static String getSystemChatPromptTemplate(OlioContext ctx, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("chat.system.prompt.txt"), evt, systemChar, userChar, interaction, iPrompt);
	}
	private static String getUserChatPromptTemplate(OlioContext ctx, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		return getChatPromptTemplate(ctx, ResourceUtil.getResource("chat.user.prompt.txt"), evt, systemChar, userChar, interaction, iPrompt);
	}

	private static String getChatPromptTemplate(OlioContext ctx, String templ, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction, String iPrompt) {
		
		PersonalityProfile sysProf = ProfileUtil.getProfile(ctx, systemChar);
		PersonalityProfile usrProf = ProfileUtil.getProfile(ctx, userChar);
		ProfileComparison profComp = new ProfileComparison(ctx, sysProf, usrProf);
		
		templ = userFirstName.matcher(templ).replaceAll((String)userChar.get("firstName"));
		templ = systemFirstName.matcher(templ).replaceAll((String)systemChar.get("firstName"));
		templ = userFullName.matcher(templ).replaceAll((String)userChar.get("name"));
		templ = systemFullName.matcher(templ).replaceAll((String)systemChar.get("name"));

		templ = userCharDesc.matcher(templ).replaceAll(NarrativeUtil.describe(ctx, userChar));
		templ = systemCharDesc.matcher(templ).replaceAll(NarrativeUtil.describe(ctx, systemChar));
		
		String ageCompat = "about the same age";
		if(profComp.doesAgeCrossBoundary()) {
			ageCompat = "aware of the difference in our ages";
		}
		templ = profileAgeCompat.matcher(templ).replaceAll("We are " + ageCompat + ".");
		
		String raceCompat = "not compatible";
		if(CompatibilityEnumType.compare(profComp.getRacialCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
			raceCompat = "compatible";
		}
		templ = profileRaceCompat.matcher(templ).replaceAll("We are racially " + raceCompat + ".");
		
		String romCompat = "we'd be doomed to fail";
		if(CompatibilityEnumType.compare(profComp.getRomanticCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
			romCompat = "there could be something  between us";
		}
		templ = profileRomanceCompat.matcher(templ).replaceAll("Romantically, " + romCompat + ".");
		
		BaseRecord cell = userChar.get("state.currentLocation");
		if(cell != null) {
			List<BaseRecord> acells = GeoLocationUtil.getAdjacentCells(ctx, cell, Rules.MAXIMUM_OBSERVATION_DISTANCE);
			TerrainEnumType tet = TerrainEnumType.valueOf((String)cell.get("terrainType"));
			Set<String> stets = acells.stream().filter(c -> TerrainEnumType.valueOf((String)c.get("terrainType")) != tet).map(c -> ((String)c.get("terrainType")).toLowerCase()).collect(Collectors.toSet());
			String tdesc = "expanse of " + tet.toString().toLowerCase();
			if(stets.size() > 0) {
				tdesc = " a patch of " + tet.toString().toLowerCase() + " near " + stets.stream().collect(Collectors.joining(","));
			}
			templ = locationTerrains.matcher(templ).replaceAll(tdesc);	
			templ = locationTerrain.matcher(templ).replaceAll(tet.toString().toLowerCase());

			
		}
		
		AlignmentEnumType align = AlignmentEnumType.NEUTRAL;
		if(evt != null) {
			align = evt.getEnum("alignment");
		
			BaseRecord realm = ctx.getRealm(evt.get("location"));
			
			List<BaseRecord> apop = GeoLocationUtil.limitToAdjacent(ctx, realm.get("zoo"), cell);
			String anames = apop.stream().map(a -> (String)a.get("name")).collect(Collectors.toSet()).stream().collect(Collectors.joining(", "));
			/*
			List<BaseRecord> fpop = GeoLocationUtil.limitToAdjacent(ctx, ctx.getPopulation(evt.get("location")).stream().filter(r -> !gids.contains(r.get(FieldNames.FIELD_ID))).toList(), cell);
			if(fpop.size() > 0) {
				buff.append(" There are " + fpop.size() +" strangers nearby.");
			}
			else {
				buff.append(" No one else seems to be around.");
			}
			*/
			String adesc = "No animals seem to be nearby.";
			if(anames.length() > 0) {
				adesc ="Some animals are close, including " + anames + ".";
			}
			templ = animalPop.matcher(templ).replaceAll(adesc);
			
			
		}
		templ = eventAlign.matcher(templ).replaceAll(NarrativeUtil.getOthersActLikeSatan(align));

		String leadDesc = "Neither one of us is in charge.";
		
		PersonalityProfile outLead = PersonalityUtil.identifyLeaderPersonality(Arrays.asList(sysProf, usrProf));
		boolean isLeaderContest = false;
		String contest = "I";
		
		if(outLead.getId() == sysProf.getId()) {
			leadDesc = "You are the leader.";
			isLeaderContest = GroupDynamicUtil.contestLeadership(ctx, null, Arrays.asList(usrProf), sysProf).size() > 0;
		}
		else {
			leadDesc = "I am the leader.";
			contest = "You";
			isLeaderContest = GroupDynamicUtil.contestLeadership(ctx, null, Arrays.asList(sysProf), usrProf).size() > 0;
		}
		if(isLeaderContest) {
			leadDesc += " " + contest + " may challenge who is leading.";
		}
		
		templ = profileLeader.matcher(templ).replaceAll(leadDesc);
		
		BaseRecord loc = userChar.get("state.currentLocation");
		if(loc != null) {
			templ = locationTerrain.matcher(templ).replaceAll(loc.getEnum("terrainType").toString().toLowerCase());	
		}
		
		if(interaction != null) {
			templ = interactDesc.matcher(templ).replaceAll(NarrativeUtil.describeInteraction(interaction));
		}
		
		templ = userPrompt.matcher(templ).replaceAll((iPrompt != null ? iPrompt : ""));
		
		return templ.trim();
	}
	
	private static OllamaRequest getChatPrompt(OlioContext octx, Chat chat, String defPrompt, String iPrompt, BaseRecord evt, BaseRecord systemChar, BaseRecord userChar, BaseRecord interaction) {
		
		chat.setLlmSystemPrompt(defPrompt);
		OllamaRequest req = chat.newRequest(chat.getModel());
		
		if(systemChar != null && userChar != null) {
			chat.setLlmSystemPrompt(getSystemChatPromptTemplate(octx, evt, systemChar, userChar, interaction, iPrompt));
			req = chat.newRequest(chat.getModel());
			chat.setPruneSkip(2);
			chat.newMessage(req, getUserChatPromptTemplate(octx, evt, systemChar, userChar, interaction, iPrompt));
		}
		
		OllamaOptions opts = new OllamaOptions();
		opts.setNumGpu(50);
		opts.setNumCtx(4096);
		// req.setOptions(opts);
		
		return req;
	}
	
	
	private static OlioContext getGridContext(BaseRecord user, String dataPath, String universeName, String worldName, boolean resetWorld) {
		AuditUtil.setLogToConsole(false);
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(true);

		OlioContextConfiguration cfg = new OlioContextConfiguration(
				user,
				dataPath,
				"~/Worlds",
				universeName,
				worldName,
				new String[] {},
				2,
				50,
				resetWorld,
				false
			);
		
			/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
			///
			cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
				new GridSquareLocationInitializationRule(),
				new LocationPlannerRule(),
				new GenericItemDataLoadRule()
			}));
			
			// Increment24HourRule incRule = new Increment24HourRule();
			// incRule.setIncrementType(TimeEnumType.HOUR);
			cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
				new Increment24HourRule(),
				new HierarchicalNeedsRule()
			}));
			OlioContext octx = new OlioContext(cfg);

			logger.info("Initialize olio context - Grid");
			octx.initialize();
			
			AuditUtil.setLogToConsole(true);
			IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
			
			return octx;
	}
	private static OlioContext getArenaContext(BaseRecord user, String dataPath, String universeName, String worldName, boolean resetWorld) {
		/// Currently using the 'Arena' setup with minimal locations and small, outfitted squads
		///
		AuditUtil.setLogToConsole(false);
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(true);
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			user,
			dataPath,
			"~/Worlds",
			universeName,
			worldName,
			new String[] {},
			1,
			50,
			resetWorld,
			false
		);
	
		/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
		///
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new ArenaInitializationRule(),
			new GenericItemDataLoadRule()
		}));
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
				new ArenaEvolveRule()
			}));
		OlioContext octx = new OlioContext(cfg);

		logger.info("Initialize olio context - Arena");
		octx.initialize();
		
		AuditUtil.setLogToConsole(true);
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		
		return octx;
	}
	
}
