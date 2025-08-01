package org.cote.accountmanager.olio.llm;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileComparison;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.personality.PersonalityUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class PromptUtil {
	public static final Logger logger = LogManager.getLogger(PromptUtil.class);
	
	private static SecureRandom rand = new SecureRandom();
	
	public static String composeTemplate(List<String> list) {
		return list.stream().collect(Collectors.joining(System.lineSeparator()));
	}

	public static String getSystemChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get(Chat.systemRole)).stream().collect(Collectors.joining(System.lineSeparator())));
	}
	public static String getUserChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get(Chat.userRole)).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}

	public static String getAssistChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get(Chat.assistantRole)).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}
	
	public static String getSystemNarrateTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("systemNarrate")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}
	public static String getAssistantNarrateTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("assistantNarrate")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}
	public static String getJailBreakTemplate(BaseRecord promptConfig) {
		return ((List<String>)promptConfig.get("jailBreak")).stream().collect(Collectors.joining(System.lineSeparator()));
	}
	public static String getUserNarrateTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("userNarrate")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}
	public static String getSystemSDTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("systemSDPrompt")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}

	public static String getSystemAnalyzeTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("systemAnalyze")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}
	public static String getAssistantAnalyzeTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("assistantAnalyze")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}
	public static String getUserReduceTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("userReduce")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}

	public static String getUserAnalyzeTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("userAnalyze")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}
	
	public static String getUserCitationTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("userCitation")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}

	public static String getChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig, String templ) {
		return getChatPromptTemplate(promptConfig, chatConfig, templ, false);
	}
	
	public static BaseRecord moveToNextEpisode(BaseRecord chatConfig) {
		BaseRecord nextEp = PromptUtil.getNextEpisode(chatConfig);
		BaseRecord nextEp2 = null;
		if(nextEp != null) {
			// logger.info("Current episode " + nextEp.get("number") + " " + nextEp.get("theme"));
			nextEp2 = PromptUtil.getEpisode(chatConfig, (int)nextEp.get("number") + 1);
			if(nextEp2 != null) {
				// logger.info("Move to episode " + nextEp2.get("number") + " " + nextEp2.get("theme"));
				nextEp.setValue("completed", true);
			}
			else {
				logger.warn("End of episodes");
			}
		}
		else {
			logger.warn("No further episodes");
		}
		return nextEp2;
	}
	
	public static BaseRecord getNextEpisode(BaseRecord chatConfig) {
		BaseRecord lastEp = getLastEpisode(chatConfig);
		if(lastEp != null) {
			BaseRecord afterLast = getEpisode(chatConfig, (int)lastEp.get("number") + 1);
			if(afterLast != null) {
				return afterLast;
			}
		}
		return getEpisode(chatConfig, 1);
	}
	
	public static BaseRecord getEpisode(BaseRecord chatConfig, int number) {
		List<BaseRecord> eps = chatConfig.get("episodes");
		eps.sort((e1, e2) -> ((Integer)e1.get("number")).compareTo(((Integer)e2.get("number"))));
		List<BaseRecord> ceps = eps.stream().filter(e -> (int)e.get("number") == number).collect(Collectors.toList());
		if(ceps.size() > 0) {
			return ceps.get(0);
		}
		return null;
		
	}
	public static BaseRecord getLastEpisode(BaseRecord chatConfig) {
		List<BaseRecord> eps = chatConfig.get("episodes");
		eps.sort((e1, e2) -> ((Integer)e1.get("number")).compareTo(((Integer)e2.get("number"))));
		List<BaseRecord> ceps = eps.stream().filter(e -> (boolean)e.get("completed")).collect(Collectors.toList());
		int size = ceps.size();
		if(size > 0) {
			return ceps.get(size - 1);
		}
		return null;
	}
	
	public static String getChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig, String templ, boolean firstPerson) {
		if(promptConfig == null) {
			logger.error("Prompt configuration is null");
			return null;
		}
		if(chatConfig == null) {
			// logger.info("No chat configuration provided");
			return templ;
		}
		
	    PromptBuilderContext ctx = new PromptBuilderContext(promptConfig, chatConfig, templ, firstPerson);
	    
	    buildRaceReplacements(ctx);
	    buildSceneReplacements(ctx);
	    buildSettingReplacements(ctx);
	    buildEpisodeReplacements(ctx);
	    buildRatingNlpConsentReplacements(ctx);
	    buildPronounAgeTradeReplacements(ctx);
	    buildRatingReplacements(ctx);
	    buildCharacterDescriptionReplacements(ctx);
	    buildProfileReplacements(ctx);
	    buildInteractionReplacements(ctx);
	    return ctx.template.trim();
	
	}
	
	private static void buildInteractionReplacements(PromptBuilderContext ctx) {
		BaseRecord interaction = ctx.chatConfig.get(OlioFieldNames.FIELD_INTERACTION);
		if(interaction == null) {
			List<BaseRecord> interactions = ctx.chatConfig.get(OlioFieldNames.FIELD_INTERACTIONS);
		
			if(interactions.size() > 0) {
				interaction = interactions.get(rand.nextInt(interactions.size()));
				IOSystem.getActiveContext().getReader().populate(interaction);
			}
			if(interaction != null) {
				ctx.chatConfig.setValue(OlioFieldNames.FIELD_INTERACTION, interaction);
				Queue.queueUpdate(ctx.chatConfig, new String[] {OlioFieldNames.FIELD_INTERACTION});
				Queue.processQueue();
			}
		}

		ctx.replace(TemplatePatternEnumType.INTERACTION_DESCRIPTION, (interaction != null ? NarrativeUtil.describeInteraction(interaction) : ""));

	}
	
	private static void buildProfileReplacements(PromptBuilderContext ctx) {
		if(ctx.profComp == null) {
			logger.warn("Profile comparison is null");
			return;
		}
		
		String ageCompat = "about the same age";
		if(ctx.profComp.doesAgeCrossBoundary()) {
			ageCompat = "aware of the difference in our ages";
		}
		ctx.replace(TemplatePatternEnumType.PROFILE_AGE_COMPATIBILITY, "We are " + ageCompat + ".");
		
		String raceCompat = "not compatible";
		if(CompatibilityEnumType.compare(ctx.profComp.getRacialCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
			raceCompat = "compatible";
		}
		ctx.replace(TemplatePatternEnumType.PROFILE_RACE_COMPATIBILITY, "We are racially " + raceCompat + ".");
		
		String romCompat = null;
		int sage = ctx.systemChar.get(FieldNames.FIELD_AGE);
		int uage = ctx.userChar.get(FieldNames.FIELD_AGE);
		String sgen = ctx.systemChar.get(FieldNames.FIELD_GENDER);
		String ugen = ctx.userChar.get(FieldNames.FIELD_GENDER);

		if(uage >= Rules.MINIMUM_ADULT_AGE && sage >= Rules.MINIMUM_ADULT_AGE && (ctx.rating == ESRBEnumType.AO || ctx.rating == ESRBEnumType.RC || !sgen.equals(ugen))) {
			romCompat = "we'd be doomed to fail";
			if(CompatibilityEnumType.compare(ctx.profComp.getRomanticCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
				romCompat = "there could be something between us";
			}
		}
		ctx.replace(TemplatePatternEnumType.PROFILE_ROMANCE_COMPATIBILITY, (romCompat != null ? "Romantically, " + romCompat + "." : ""));
		
		String leadDesc = "Neither one of us is in charge.";
		
		PersonalityProfile outLead = PersonalityUtil.identifyLeaderPersonality(Arrays.asList(ctx.sysProf, ctx.usrProf));
		boolean isLeaderContest = false;
		String contest = "I";
		
		leadDesc = outLead.getRecord().get(FieldNames.FIELD_FIRST_NAME) + " is the leader.";
		if(outLead.getId() == ctx.sysProf.getId()) {
			isLeaderContest = GroupDynamicUtil.contestLeadership(null, null, Arrays.asList(ctx.usrProf), ctx.sysProf).size() > 0;
			contest = ctx.usrProf.getRecord().get(FieldNames.FIELD_FIRST_NAME);
		}
		else {
			isLeaderContest = GroupDynamicUtil.contestLeadership(null, null, Arrays.asList(ctx.sysProf), ctx.usrProf).size() > 0;
		}
		if(isLeaderContest) {
			leadDesc += " " + contest + " may challenge who is leading.";
		}
		ctx.replace(TemplatePatternEnumType.PROFILE_LEADER, leadDesc);
		
	}
	
	private static void buildRaceReplacements(PromptBuilderContext ctx) {
		String asupp = "";
		String srace = "";
		String urace = "";
		List<BaseRecord> races = ctx.promptConfig.get("races");
		if(ctx.sysProf != null) {
			List<String> sysRaces = ctx.sysProf.getRace();
	
			if(sysRaces.contains("L") || sysRaces.contains("S") || sysRaces.contains("V") || sysRaces.contains("R") || sysRaces.contains("W") || sysRaces.contains("X") || sysRaces.contains("Y") || sysRaces.contains("Z")) {
				Optional<BaseRecord> osupp = races.stream().filter(r -> sysRaces.contains(r.get("raceType"))).findFirst();
				if(osupp.isPresent()) {
					srace = composeTemplate(osupp.get().get(OlioFieldNames.FIELD_RACE));
				}
			}
		}
		if(ctx.usrProf != null) {
			List<String> usrRaces = ctx.usrProf.getRace();
			if(usrRaces.contains("L") || usrRaces.contains("S") || usrRaces.contains("V") || usrRaces.contains("R") || usrRaces.contains("W") || usrRaces.contains("X") || usrRaces.contains("Y") || usrRaces.contains("Z")) {
				Optional<BaseRecord> osupp = races.stream().filter(r -> usrRaces.contains(r.get("raceType"))).findFirst();
				if(osupp.isPresent()) {
					urace = composeTemplate(osupp.get().get(OlioFieldNames.FIELD_RACE));
				}
			}
		}
		ctx.replace(TemplatePatternEnumType.USER_RACE, urace);
		ctx.replace(TemplatePatternEnumType.SYSTEM_RACE, srace);
		ctx.replace(TemplatePatternEnumType.ANNOTATE_SUPPLEMENT, asupp);
	}
	
	private static void buildSceneReplacements(PromptBuilderContext ctx) {
		String scenel = "";
		String iscene = ctx.chatConfig.get("userNarrative.interactionDescription");
		String cscene = ctx.chatConfig.get("scene");
		if(cscene == null) {
			cscene = iscene;
		}
		boolean auto = (cscene != null && cscene.length() > 0);
		if((boolean)ctx.chatConfig.get("includeScene")) {
			scenel = ((List<String>)ctx.promptConfig.get("scene")).stream().collect(Collectors.joining(System.lineSeparator()));
		}
		ctx.replace(TemplatePatternEnumType.SCENE, scenel);
        ctx.cscene = cscene;
        ctx.scenel = scenel;
	}
	
	private static void buildSettingReplacements(PromptBuilderContext ctx) {

		String setting = ctx.chatConfig.get("setting");
		if(setting != null && setting.length() > 0) {
			if(setting.equalsIgnoreCase("random")) {
				setting = NarrativeUtil.getRandomSetting();
			}
			ctx.replace(TemplatePatternEnumType.SETTING, "The setting is: " + setting);
			ctx.setting = setting;
		}
		else {
			ctx.replace(TemplatePatternEnumType.SETTING, "");
			String tdesc = ctx.chatConfig.get(FieldNames.FIELD_TERRAIN);
			if(tdesc == null) tdesc = "";
			ctx.replace(TemplatePatternEnumType.LOCATION_TERRAINS, tdesc);
		}
		if(ctx.userChar != null) {
			BaseRecord loc = ctx.userChar.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION);
			if(loc != null) {
				ctx.replace(TemplatePatternEnumType.LOCATION_TERRAIN, loc.getEnum(FieldNames.FIELD_TERRAIN_TYPE).toString().toLowerCase());
			}
		}


	}
	
	private static void buildEpisodeReplacements(PromptBuilderContext ctx) {
		BaseRecord episodeRec = getNextEpisode(ctx.chatConfig);
		String episodicLabel = "";
		String episodeText = "";
		String episodeReminderText = "";
		String episodeRuleText = "1) Always stay in character";
		String episodeAssistText = "";
		// boolean isEpisode = false;
		if(episodeRec != null) {
			// isEpisode = true;
			ctx.episode = true;
			episodeAssistText = episodeRec.get("episodeAssist");
			if(episodeAssistText == null) episodeAssistText = "";
			episodicLabel = "episodic";
			// Matcher.quoteReplacement(
			episodeRuleText =  ((List<String>)ctx.promptConfig.get("episodeRule")).stream().collect(Collectors.joining(System.lineSeparator()));
			StringBuilder elBuff = new StringBuilder();
			StringBuilder epBuff = new StringBuilder(); 
			elBuff.append("EPISODE GUIDANCE:" + System.lineSeparator());
			
			epBuff.append("* Theme: " + episodeRec.get("theme") + System.lineSeparator());
			List<String> stages = episodeRec.get("stages");
			for(String st: stages) {
				epBuff.append("* Stage: " + st + System.lineSeparator());	
			}
			BaseRecord lastEp = getLastEpisode(ctx.chatConfig);
			if(lastEp != null) {
				String sum = lastEp.get("summary");
				if(sum != null) {
					epBuff.append("* Previous Episode: " + sum + System.lineSeparator());
				}
			}
			elBuff.append(epBuff.toString());
			episodeText = elBuff.toString();
			episodeReminderText = "(Reminder - Follow Episode Stages: " + System.lineSeparator() + epBuff.toString() + ")";
		}
		
		
		if(ctx.episode) {
			ctx.replace(TemplatePatternEnumType.AUTO_SCENE, "");
		}
		else {
			boolean auto = (ctx.cscene != null && ctx.cscene.length() > 0);
			if(!auto) {
				ctx.replace(TemplatePatternEnumType.AUTO_SCENE, "Scene: " + ctx.scenel + (ctx.setting != null ? " " + ctx.setting : ""));
	
			}
			else {
				ctx.replace(TemplatePatternEnumType.AUTO_SCENE, "Scene: " + ctx.cscene);
			}
		}
		
		ctx.replace(TemplatePatternEnumType.EPISODIC, episodicLabel);
		ctx.replace(TemplatePatternEnumType.EPISODE_ASSIST, episodeAssistText);
		ctx.replace(TemplatePatternEnumType.EPISODE, episodeText);
		ctx.replace(TemplatePatternEnumType.EPISODE_REMINDER, episodeReminderText);
		ctx.replace(TemplatePatternEnumType.EPISODE_RULE, episodeRuleText);

		
	}

	
	private static void buildRatingNlpConsentReplacements(PromptBuilderContext ctx) {

		String sysNlp = "";
		String assistNlp = "";
		boolean useNLP = ctx.chatConfig.get("useNLP");

		String nlpCommand = null;
		if(useNLP) {
			nlpCommand = ctx.chatConfig.get("nlpCommand");
			sysNlp = composeTemplate(ctx.promptConfig.get("systemNlp"));
			assistNlp = composeTemplate(ctx.promptConfig.get("assistantNlp"));
		}
		
		ctx.replace(TemplatePatternEnumType.NLP, sysNlp);
		ctx.replace(TemplatePatternEnumType.NLP_WARN, assistNlp);
		
		String sysCens = "";
		String assistCens = "";
		ESRBEnumType rating = ctx.chatConfig.getEnum("rating");
		if(rating == ESRBEnumType.AO || rating == ESRBEnumType.RC) {
			sysCens = composeTemplate(ctx.promptConfig.get("systemCensorWarning"));
			assistCens = composeTemplate(ctx.promptConfig.get("assistantCensorWarning"));
			
		}

		String uconpref = composeTemplate(ctx.promptConfig.get("userConsentPrefix"));
		String ucons = "";
		if(rating == ESRBEnumType.M || rating == ESRBEnumType.AO || rating == ESRBEnumType.RC) {
			ucons = composeTemplate(ctx.promptConfig.get("userConsentRating"));
		}
		if(useNLP) {
			if(ucons.length() > 0) ucons += " and ";
			ucons += composeTemplate(ctx.promptConfig.get("userConsentNlp"));
		}
		ctx.replace(TemplatePatternEnumType.NLP_REMINDER, useNLP ? "(Reminder: \"${nlp.command}\")" : "");
		ctx.replace(TemplatePatternEnumType.USER_CONSENT, ucons.length() > 0 ? uconpref + ucons + ".": "");
		ctx.replace(TemplatePatternEnumType.NLP_COMMAND, nlpCommand);
		ctx.replace(TemplatePatternEnumType.CENSOR_WARN, sysCens);
		ctx.replace(TemplatePatternEnumType.ASSISTANT_CENSOR_WARN, assistCens);
	}
	
	private static void buildPronounAgeTradeReplacements(PromptBuilderContext ctx) {
		if(ctx.userChar == null || ctx.systemChar == null) {
			logger.warn("User or system character is null");
			return;
		}
		
		String ugen = ctx.userChar.get(FieldNames.FIELD_GENDER);
		String sgen = ctx.systemChar.get(FieldNames.FIELD_GENDER);
		String ucppro = "His";
		String uppro = "his";
		String ucpro = "He";
		String upro = "he";

		if(ugen.equals("female")) {
			ucppro = "Her";
			ucpro = "She";
			uppro = "her";
			upro = "she";
		}
		
		String scppro = "His";
		String sppro = "his";
		String scpro = "He";
		String spro = "he";

		if(sgen.equals("female")) {
			scppro = "Her";
			scpro = "She";
			sppro = "her";
			spro = "she";
		}

		String ujobDesc = "";
		List<String> utrades = ctx.userChar.get(OlioFieldNames.FIELD_TRADES);
		if(utrades.size() > 0) {
			ujobDesc =" " + utrades.get(0).toLowerCase();
		}
		
		String sjobDesc = "";
		List<String> strades = ctx.systemChar.get(OlioFieldNames.FIELD_TRADES);
		if(strades.size() > 0) {
			sjobDesc =" " + strades.get(0).toLowerCase();
		}		
		int sage = ctx.systemChar.get(FieldNames.FIELD_AGE);
		int uage = ctx.userChar.get(FieldNames.FIELD_AGE);

		String sper = "";
		if(sgen.equals("male") || sgen.equals("female")) {
			List<String> per = ctx.promptConfig.get(sgen + "Perspective");
			if(per != null && per.size() > 0) {
				// Matcher.quoteReplacement(
				sper = per.stream().collect(Collectors.joining(System.lineSeparator()));
			}
		}

		
		String whoStart = "I'll start" + (ctx.episode ? " the episode" : "") + ":";
		if(!"system".equals(ctx.chatConfig.get("startMode"))) {
			whoStart = "You start" + (ctx.episode ? " the episode" : "") + ".";
		}

		ctx.replace(TemplatePatternEnumType.FIRST_SECOND_POSSESSIVE, ctx.firstPerson ? "my" : "your");
		ctx.replace(TemplatePatternEnumType.FIRST_SECOND_WHO, whoStart);
		ctx.replace(TemplatePatternEnumType.FIRST_SECOND_TO_BE, ctx.firstPerson ? "I am" : "You are");
		ctx.replace(TemplatePatternEnumType.FIRST_SECOND_NAME, (String)(ctx.firstPerson ? ctx.systemChar.get(FieldNames.FIELD_FIRST_NAME) : ctx.userChar.get(FieldNames.FIELD_FIRST_NAME)));
		ctx.replace(TemplatePatternEnumType.USER_GENDER, ugen);
		ctx.replace(TemplatePatternEnumType.SYSTEM_GENDER, sgen);

		ctx.replace(TemplatePatternEnumType.PERSPECTIVE, sper);
		ctx.replace(TemplatePatternEnumType.SYSTEM_ASG, sage + " year old " + sgen + sjobDesc);
		ctx.replace(TemplatePatternEnumType.USER_ASG, uage + " year old " + ugen + ujobDesc);

		ctx.replace(TemplatePatternEnumType.USER_CAPITAL_PRONOUN, ucpro);
		ctx.replace(TemplatePatternEnumType.USER_PRONOUN, upro);
		ctx.replace(TemplatePatternEnumType.USER_POSSESSIVE_PRONOUN, uppro);
		ctx.replace(TemplatePatternEnumType.USER_CAPITAL_POSSESSIVE_PRONOUN, ucppro);

		ctx.replace(TemplatePatternEnumType.SYSTEM_CAPITAL_PRONOUN, scpro);
		ctx.replace(TemplatePatternEnumType.SYSTEM_PRONOUN, spro);
		ctx.replace(TemplatePatternEnumType.SYSTEM_POSSESSIVE_PRONOUN, sppro);
		ctx.replace(TemplatePatternEnumType.SYSTEM_CAPITAL_POSSESSIVE_PRONOUN, scppro);
		
		ctx.replace(TemplatePatternEnumType.SYSTEM_TRADE, (sjobDesc.length() > 0 ? sjobDesc : "unemployed"));
		ctx.replace(TemplatePatternEnumType.USER_TRADE, (ujobDesc.length() > 0 ? ujobDesc : "unemployed"));
		
	}
	
	private static void buildRatingReplacements(PromptBuilderContext ctx) {
		ESRBEnumType rating = ctx.chatConfig.getEnum("rating");
		ctx.replace(TemplatePatternEnumType.RATING_NAME, ESRBEnumType.getESRBName(rating));
		ctx.replace(TemplatePatternEnumType.RATING, rating.toString());
		ctx.replace(TemplatePatternEnumType.RATING_DESCRIPTION, ESRBEnumType.getESRBShortDescription(rating));
		ctx.replace(TemplatePatternEnumType.RATING_RESTRICT, ESRBEnumType.getESRBRestriction(rating));
		ctx.replace(TemplatePatternEnumType.RATING_MPA, ESRBEnumType.getESRBMPA(rating));
	}
	
	private static void buildCharacterDescriptionReplacements(PromptBuilderContext ctx) {
		if(ctx.userChar != null) {
			ctx.replace(TemplatePatternEnumType.USER_FIRST_NAME, (String)ctx.userChar.get(FieldNames.FIELD_FIRST_NAME));
			ctx.replace(TemplatePatternEnumType.USER_FULL_NAME, (String)ctx.userChar.get(FieldNames.FIELD_NAME));
			ctx.replace(TemplatePatternEnumType.USER_CHARACTER_DESCRIPTION, NarrativeUtil.describe(null, ctx.userChar));
			ctx.replace(TemplatePatternEnumType.USER_CHARACTER_DESCRIPTION_PUBLIC, NarrativeUtil.describe(null, ctx.userChar, true, false, false));
			ctx.replace(TemplatePatternEnumType.USER_CHARACTER_DESCRIPTION_LIGHT, NarrativeUtil.describe(null, ctx.userChar, false, true, false));			
		}
		if(ctx.systemChar != null) {
			ctx.replace(TemplatePatternEnumType.SYSTEM_FIRST_NAME, (String)ctx.systemChar.get(FieldNames.FIELD_FIRST_NAME));
			ctx.replace(TemplatePatternEnumType.SYSTEM_FULL_NAME, (String)ctx.systemChar.get(FieldNames.FIELD_NAME));
			ctx.replace(TemplatePatternEnumType.SYSTEM_CHARACTER_DESCRIPTION, NarrativeUtil.describe(null, ctx.systemChar));
			ctx.replace(TemplatePatternEnumType.SYSTEM_CHARACTER_DESCRIPTION_PUBLIC, NarrativeUtil.describe(null, ctx.systemChar, true, false, false));
			ctx.replace(TemplatePatternEnumType.SYSTEM_CHARACTER_DESCRIPTION_LIGHT, NarrativeUtil.describe(null, ctx.systemChar, false, true, false));
		}
	}
	
	
}
