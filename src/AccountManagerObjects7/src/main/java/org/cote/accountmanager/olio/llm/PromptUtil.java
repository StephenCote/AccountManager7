package org.cote.accountmanager.olio.llm;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.AlignmentEnumType;
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
	
	private static Pattern locationName = Pattern.compile("\\$\\{location.name\\}");
	private static Pattern locationTerrain = Pattern.compile("\\$\\{location.terrain\\}");
	private static Pattern locationTerrains = Pattern.compile("\\$\\{location.terrains\\}");
	private static Pattern userFirstName = Pattern.compile("\\$\\{user.firstName\\}");
	private static Pattern systemFirstName = Pattern.compile("\\$\\{system.firstName\\}");
	private static Pattern userFullName = Pattern.compile("\\$\\{user.fullName\\}");
	private static Pattern systemFullName = Pattern.compile("\\$\\{system.fullName\\}");

	private static Pattern userCharDesc = Pattern.compile("\\$\\{user.characterDesc\\}");
	private static Pattern systemCharDesc = Pattern.compile("\\$\\{system.characterDesc\\}");
	private static Pattern userCharDescLight = Pattern.compile("\\$\\{user.characterDescLight\\}");
	private static Pattern systemCharDescLight = Pattern.compile("\\$\\{system.characterDescLight\\}");
	private static Pattern userCharDescPublic = Pattern.compile("\\$\\{user.characterDescPublic\\}");
	private static Pattern systemCharDescPublic = Pattern.compile("\\$\\{system.characterDescPublic\\}");

	private static Pattern profileAgeCompat = Pattern.compile("\\$\\{profile.ageCompat\\}");
	private static Pattern profileRomanceCompat = Pattern.compile("\\$\\{profile.romanceCompat\\}");
	private static Pattern profileRaceCompat = Pattern.compile("\\$\\{profile.raceCompat\\}");
	private static Pattern profileLeader = Pattern.compile("\\$\\{profile.leader\\}");
	private static Pattern eventAlign = Pattern.compile("\\$\\{event.alignment\\}");
	private static Pattern animalPop = Pattern.compile("\\$\\{population.animals\\}");
	private static Pattern peoplePop = Pattern.compile("\\$\\{population.people\\}");
	private static Pattern interactDesc = Pattern.compile("\\$\\{interaction.description\\}");

	private static Pattern sysGen = Pattern.compile("\\$\\{system.gender\\}");
	private static Pattern userGen = Pattern.compile("\\$\\{user.gender\\}");
	private static Pattern userASG = Pattern.compile("\\$\\{user.asg\\}");
	private static Pattern systemASG = Pattern.compile("\\$\\{system.asg\\}");
	private static Pattern userCPPro = Pattern.compile("\\$\\{user.capPPro\\}");
	private static Pattern userCPro = Pattern.compile("\\$\\{user.capPro\\}");
	private static Pattern userPro = Pattern.compile("\\$\\{user.pro\\}");
	private static Pattern userPPro = Pattern.compile("\\$\\{user.ppro\\}");
	private static Pattern systemCPPro = Pattern.compile("\\$\\{system.capPPro\\}");
	private static Pattern systemCPro = Pattern.compile("\\$\\{system.capPro\\}");
	private static Pattern systemPro = Pattern.compile("\\$\\{system.pro\\}");
	private static Pattern systemPPro = Pattern.compile("\\$\\{system.ppro\\}");

	private static Pattern userTradePat = Pattern.compile("\\$\\{user.trade\\}");
	private static Pattern systemTradePat = Pattern.compile("\\$\\{system.trade\\}");
	
	private static Pattern userPrompt = Pattern.compile("\\$\\{userPrompt\\}");
	private static Pattern autoScene = Pattern.compile("\\$\\{scene.auto\\}");
	private static Pattern scene = Pattern.compile("\\$\\{scene\\}"); 
	private static Pattern nlpPat = Pattern.compile("\\$\\{nlp\\}");
	private static Pattern nlpCmdPat = Pattern.compile("\\$\\{nlp.command\\}");
	private static Pattern nlpWarnPat = Pattern.compile("\\$\\{nlpWarn\\}");
	private static Pattern nlpReminderPat = Pattern.compile("\\$\\{nlpReminder}");
	private static Pattern perspective = Pattern.compile("\\$\\{perspective\\}");
	private static Pattern setting = Pattern.compile("\\$\\{setting\\}");
	private static Pattern ratingPat = Pattern.compile("\\$\\{rating\\}");
	private static Pattern ratingName = Pattern.compile("\\$\\{ratingName\\}");
	private static Pattern ratingMpa = Pattern.compile("\\$\\{ratingMpa\\}");
	private static Pattern ratingDesc = Pattern.compile("\\$\\{ratingDesc\\}");
	private static Pattern ratingRestrict = Pattern.compile("\\$\\{ratingRestrict\\}");
	private static Pattern annotateSupplement = Pattern.compile("\\$\\{annotateSupplement\\}");
	private static Pattern userRace = Pattern.compile("\\$\\{user.race\\}");
	private static Pattern systemRace = Pattern.compile("\\$\\{system.race\\}");
	private static Pattern censorWarn = Pattern.compile("\\$\\{censorWarn\\}");
	private static Pattern userConsent = Pattern.compile("\\$\\{user.consent\\}");
	private static Pattern assistCensorWarn = Pattern.compile("\\$\\{assistCensorWarn\\}");
	private static Pattern firstSecondPos = Pattern.compile("\\$\\{firstSecondPos\\}");
	private static Pattern firstSecondWho = Pattern.compile("\\$\\{firstSecondWho\\}");
	private static Pattern firstSecondToBe = Pattern.compile("\\$\\{firstSecondToBe\\}");
	private static Pattern firstSecondName = Pattern.compile("\\$\\{firstSecondName\\}");
	private static Pattern episodic = Pattern.compile("\\$\\{episodic\\}");
	private static Pattern episode = Pattern.compile("\\$\\{episode\\}");
	private static Pattern episodeReminder = Pattern.compile("\\$\\{episodeReminder\\}");
	private static Pattern episodeAssist = Pattern.compile("\\$\\{episodeAssist\\}");
	private static Pattern episodeRule = Pattern.compile("\\$\\{episodeRule\\}");
	
	public static String composeTemplate(List<String> list) {
		return Matcher.quoteReplacement(list.stream().collect(Collectors.joining(" ")));
	}

	public static String getSystemChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("system")).stream().collect(Collectors.joining(System.lineSeparator())));
	}
	public static String getUserChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("user")).stream().collect(Collectors.joining(System.lineSeparator())), true);
	}

	public static String getAssistChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("assistant")).stream().collect(Collectors.joining(System.lineSeparator())), true);
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
		BaseRecord userChar = chatConfig.get("userCharacter");
		BaseRecord systemChar = chatConfig.get("systemCharacter");
		
		PersonalityProfile sysProf = ProfileUtil.getProfile(null, chatConfig.get("systemCharacter"));
		PersonalityProfile usrProf = ProfileUtil.getProfile(null, chatConfig.get("userCharacter"));
		ProfileComparison profComp = new ProfileComparison(null, sysProf, usrProf);
		
		String asupp = "";
		String srace = "";
		String urace = "";
		List<BaseRecord> races = promptConfig.get("races");
		if(sysProf.getRace().contains("L") || sysProf.getRace().contains("S") || sysProf.getRace().contains("V") || sysProf.getRace().contains("R") || sysProf.getRace().contains("W") || sysProf.getRace().contains("X") || sysProf.getRace().contains("Y") || sysProf.getRace().contains("Z")) {
			Optional<BaseRecord> osupp = races.stream().filter(r -> sysProf.getRace().contains(r.get("raceType"))).findFirst();
			if(osupp.isPresent()) {
				srace = composeTemplate(osupp.get().get(OlioFieldNames.FIELD_RACE));
			}
		}

		if(usrProf.getRace().contains("L") || usrProf.getRace().contains("S") || usrProf.getRace().contains("V") || usrProf.getRace().contains("R") || usrProf.getRace().contains("W") || usrProf.getRace().contains("X") || usrProf.getRace().contains("Y") || usrProf.getRace().contains("Z")) {
			Optional<BaseRecord> osupp = races.stream().filter(r -> usrProf.getRace().contains(r.get("raceType"))).findFirst();
			if(osupp.isPresent()) {
				urace = composeTemplate(osupp.get().get(OlioFieldNames.FIELD_RACE));
			}
		}
		templ = userRace.matcher(templ).replaceAll(urace);
		templ = systemRace.matcher(templ).replaceAll(srace);
		templ = annotateSupplement.matcher(templ).replaceAll(Matcher.quoteReplacement(asupp));


		String scenel = "";
		String iscene = chatConfig.get("userNarrative.interactionDescription");
		// (iscene != null ? iscene + System.lineSeparator() : "") + 
		//String cscene = (iscene != null ? iscene  : chatConfig.get("scene"));
		String cscene = chatConfig.get("scene");
		if(cscene == null) {
			cscene = iscene;
		}
		boolean auto = (cscene != null && cscene.length() > 0);
		if((boolean)chatConfig.get("includeScene")) {
			scenel = Matcher.quoteReplacement(((List<String>)promptConfig.get("scene")).stream().collect(Collectors.joining(System.lineSeparator())));
		}
		templ = scene.matcher(templ).replaceAll(scenel);
		
		BaseRecord episodeRec = getNextEpisode(chatConfig);
		String episodicLabel = "";
		String episodeText = "";
		String episodeReminderText = "";
		String episodeRuleText = "1) Always stay in character";
		String episodeAssistText = "";
		boolean isEpisode = false;
		if(episodeRec != null) {
			isEpisode = true;
			episodeAssistText = episodeRec.get("episodeAssist");
			if(episodeAssistText == null) episodeAssistText = "";
			episodicLabel = "episodic";
			episodeRuleText =  Matcher.quoteReplacement(((List<String>)promptConfig.get("episodeRule")).stream().collect(Collectors.joining(System.lineSeparator())));
			StringBuilder elBuff = new StringBuilder();
			StringBuilder epBuff = new StringBuilder(); 
			elBuff.append("EPISODE GUIDANCE:" + System.lineSeparator());
			
			epBuff.append("* Theme: " + episodeRec.get("theme") + System.lineSeparator());
			List<String> stages = episodeRec.get("stages");
			for(String st: stages) {
				epBuff.append("* Stage: " + st + System.lineSeparator());	
			}
			BaseRecord lastEp = getLastEpisode(chatConfig);
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
		
		String whoStart = "I'll start" + (isEpisode ? " the episode" : "") + ":";
		if(!"system".equals(chatConfig.get("startMode"))) {
			whoStart = "You start" + (isEpisode ? " the episode" : "") + ".";
		}
		
		templ = episodic.matcher(templ).replaceAll(episodicLabel);
		templ = episodeAssist.matcher(templ).replaceAll(Matcher.quoteReplacement(episodeAssistText));
		templ = episode.matcher(templ).replaceAll(Matcher.quoteReplacement(episodeText));
		templ = episodeReminder.matcher(templ).replaceAll(Matcher.quoteReplacement(episodeReminderText));
		templ = episodeRule.matcher(templ).replaceAll(episodeRuleText);
		
		String settingStr = chatConfig.get("setting");
		String nlpReminder = "";
		String sysNlp = "";
		String assistNlp = "";
		boolean useNLP = chatConfig.get("useNLP");
		String nlpCommand = null;
		if(useNLP) {
			nlpCommand = chatConfig.get("nlpCommand");
			nlpReminder = "(Reminder: Primary Command: \"${nlp.command}\")";
			sysNlp = composeTemplate(promptConfig.get("systemNlp"));
			assistNlp = composeTemplate(promptConfig.get("assistantNlp"));
		}
		templ = nlpPat.matcher(templ).replaceAll(sysNlp);
		templ = nlpWarnPat.matcher(templ).replaceAll(assistNlp);
		
		String sysCens = "";
		String assistCens = "";
		ESRBEnumType rating = chatConfig.getEnum("rating");
		if(rating == ESRBEnumType.AO || rating == ESRBEnumType.RC) {
			sysCens = composeTemplate(promptConfig.get("systemCensorWarning"));
			assistCens = composeTemplate(promptConfig.get("assistantCensorWarning"));
			
		}

		String uconpref = composeTemplate(promptConfig.get("userConsentPrefix"));
		String ucons = "";
		if(rating == ESRBEnumType.M || rating == ESRBEnumType.AO || rating == ESRBEnumType.RC) {
			ucons = composeTemplate(promptConfig.get("userConsentRating"));
		}
		if(useNLP) {
			if(ucons.length() > 0) ucons += " and ";
			ucons += composeTemplate(promptConfig.get("userConsentNlp"));
		}
		templ = nlpReminderPat.matcher(templ).replaceAll(Matcher.quoteReplacement(nlpReminder));
		templ = userConsent.matcher(templ).replaceAll(ucons.length() > 0 ? uconpref + ucons + ".": "");

		templ = nlpCmdPat.matcher(templ).replaceAll((nlpCommand != null ? Matcher.quoteReplacement(nlpCommand) : ""));
		templ = censorWarn.matcher(templ).replaceAll(sysCens);
		templ = assistCensorWarn.matcher(templ).replaceAll(assistCens);
		
		templ = firstSecondPos.matcher(templ).replaceAll(firstPerson ? "my" : "your");
		templ = firstSecondWho.matcher(templ).replaceAll(whoStart);
		templ = firstSecondToBe.matcher(templ).replaceAll(firstPerson ? "I am" : "You are");
		templ = firstSecondName.matcher(templ).replaceAll((String)(firstPerson ? systemChar.get(FieldNames.FIELD_FIRST_NAME) : userChar.get(FieldNames.FIELD_FIRST_NAME)));
		
		String ugen = userChar.get(FieldNames.FIELD_GENDER);
		templ = userGen.matcher(templ).replaceAll(ugen);
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

		String sgen = systemChar.get(FieldNames.FIELD_GENDER);
		templ = sysGen.matcher(templ).replaceAll(sgen);
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
		List<String> utrades = userChar.get(OlioFieldNames.FIELD_TRADES);
		if(utrades.size() > 0) {
			ujobDesc =" " + utrades.get(0).toLowerCase();
		}
		
		String sjobDesc = "";
		List<String> strades = systemChar.get(OlioFieldNames.FIELD_TRADES);
		if(strades.size() > 0) {
			sjobDesc =" " + strades.get(0).toLowerCase();
		}		
		int sage = systemChar.get(FieldNames.FIELD_AGE);
		int uage = userChar.get(FieldNames.FIELD_AGE);

		String sper = "";
		if(sgen.equals("male") || sgen.equals("female")) {
			List<String> per = promptConfig.get(sgen + "Perspective");
			if(per != null && per.size() > 0) {
				sper = Matcher.quoteReplacement(per.stream().collect(Collectors.joining(System.lineSeparator())));
			}
		}
		templ = perspective.matcher(templ).replaceAll(sper);
		
		templ = systemASG.matcher(templ).replaceAll(sage + " year old " + sgen + sjobDesc);
		templ = userASG.matcher(templ).replaceAll(uage + " year old " + ugen + ujobDesc);
		templ = userCPro.matcher(templ).replaceAll(ucpro);
		templ = userPro.matcher(templ).replaceAll(upro);
		templ = userPPro.matcher(templ).replaceAll(uppro);
		templ = userCPPro.matcher(templ).replaceAll(ucppro);
		templ = systemCPro.matcher(templ).replaceAll(scpro);
		templ = systemPro.matcher(templ).replaceAll(spro);
		templ = systemPPro.matcher(templ).replaceAll(sppro);
		templ = systemCPPro.matcher(templ).replaceAll(scppro);
		
		templ = userTradePat.matcher(templ).replaceAll((ujobDesc.length() > 0 ? ujobDesc : "unemployed"));
		templ = systemTradePat.matcher(templ).replaceAll((sjobDesc.length() > 0 ? sjobDesc : "unemployed"));

		templ = ratingName.matcher(templ).replaceAll(ESRBEnumType.getESRBName(rating));
		templ = ratingPat.matcher(templ).replaceAll(rating.toString());
		templ = ratingDesc.matcher(templ).replaceAll(ESRBEnumType.getESRBShortDescription(rating));
		templ = ratingRestrict.matcher(templ).replaceAll(ESRBEnumType.getESRBRestriction(rating));
		templ = ratingMpa.matcher(templ).replaceAll(ESRBEnumType.getESRBMPA(rating));
		templ = userFirstName.matcher(templ).replaceAll((String)userChar.get(FieldNames.FIELD_FIRST_NAME));
		templ = systemFirstName.matcher(templ).replaceAll((String)systemChar.get(FieldNames.FIELD_FIRST_NAME));
		templ = userFullName.matcher(templ).replaceAll((String)userChar.get(FieldNames.FIELD_NAME));
		templ = systemFullName.matcher(templ).replaceAll((String)systemChar.get(FieldNames.FIELD_NAME));

		templ = userCharDesc.matcher(templ).replaceAll(NarrativeUtil.describe(null, userChar));
		templ = systemCharDesc.matcher(templ).replaceAll(NarrativeUtil.describe(null, systemChar));
		
		templ = userCharDescPublic.matcher(templ).replaceAll(NarrativeUtil.describe(null, userChar, true, false, false));
		templ = systemCharDescPublic.matcher(templ).replaceAll(NarrativeUtil.describe(null, systemChar, true, false, false));
		
		templ = userCharDescLight.matcher(templ).replaceAll(NarrativeUtil.describe(null, userChar, false, true, false));
		templ = systemCharDescLight.matcher(templ).replaceAll(NarrativeUtil.describe(null, systemChar, false, true, false));

		BaseRecord cell = userChar.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION);
		if(settingStr != null && settingStr.length() > 0) {
			if(settingStr.equalsIgnoreCase("random")) {
				settingStr = NarrativeUtil.getRandomSetting();
			}
			templ = setting.matcher(templ).replaceAll("The setting is: " + settingStr);
		}
		else {
			templ = setting.matcher(templ).replaceAll("");
			String tdesc = chatConfig.get(FieldNames.FIELD_TERRAIN);
			if(tdesc == null) tdesc = "";

			templ = locationTerrains.matcher(templ).replaceAll(tdesc);	
			// templ = locationTerrain.matcher(templ).replaceAll(tet.toString().toLowerCase());
		}
		if(isEpisode) {
			templ = autoScene.matcher(templ).replaceAll("");
		}
		else {
			if(!auto) {
				templ = autoScene.matcher(templ).replaceAll("Scene: " + scenel + (settingStr != null ? " " + settingStr : ""));	
			}
			else {
				templ = autoScene.matcher(templ).replaceAll("Scene: " + cscene);
			}
		}

		String pdesc = chatConfig.get("populationDescription");
		String adesc = chatConfig.get("animalDescription");
		templ = peoplePop.matcher(templ).replaceAll(pdesc != null ? pdesc : "");
		templ = animalPop.matcher(templ).replaceAll(adesc != null ? adesc : "");
		
		AlignmentEnumType align = chatConfig.getEnum("alignment");
		templ = eventAlign.matcher(templ).replaceAll(NarrativeUtil.getOthersActLikeSatan(align));

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
		
		String romCompat = null;
		if(uage >= Rules.MINIMUM_ADULT_AGE && sage >= Rules.MINIMUM_ADULT_AGE && (rating == ESRBEnumType.AO || rating == ESRBEnumType.RC || !sgen.equals(ugen))) {
			romCompat = "we'd be doomed to fail";
			if(CompatibilityEnumType.compare(profComp.getRomanticCompatibility(), CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)) {
				romCompat = "there could be something between us";
			}
		}
		templ = profileRomanceCompat.matcher(templ).replaceAll((romCompat != null ? "Romantically, " + romCompat + "." : ""));

		
		String leadDesc = "Neither one of us is in charge.";
		
		PersonalityProfile outLead = PersonalityUtil.identifyLeaderPersonality(Arrays.asList(sysProf, usrProf));
		boolean isLeaderContest = false;
		String contest = "I";
		
		leadDesc = outLead.getRecord().get(FieldNames.FIELD_FIRST_NAME) + " is the leader.";
		if(outLead.getId() == sysProf.getId()) {
			isLeaderContest = GroupDynamicUtil.contestLeadership(null, null, Arrays.asList(usrProf), sysProf).size() > 0;
			contest = usrProf.getRecord().get(FieldNames.FIELD_FIRST_NAME);
		}
		else {
			isLeaderContest = GroupDynamicUtil.contestLeadership(null, null, Arrays.asList(sysProf), usrProf).size() > 0;
		}
		if(isLeaderContest) {
			leadDesc += " " + contest + " may challenge who is leading.";
		}
		
		templ = profileLeader.matcher(templ).replaceAll(leadDesc);
		
		BaseRecord loc = userChar.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION);
		if(loc != null) {
			templ = locationTerrain.matcher(templ).replaceAll(loc.getEnum(FieldNames.FIELD_TERRAIN_TYPE).toString().toLowerCase());	
		}
		BaseRecord interaction = chatConfig.get(OlioFieldNames.FIELD_INTERACTION);
		if(interaction == null) {
			List<BaseRecord> interactions = chatConfig.get(OlioFieldNames.FIELD_INTERACTIONS);
		
			if(interactions.size() > 0) {
				interaction = interactions.get(rand.nextInt(interactions.size()));
				IOSystem.getActiveContext().getReader().populate(interaction);
			}
			if(interaction != null) {
				chatConfig.setValue(OlioFieldNames.FIELD_INTERACTION, interaction);
				Queue.queueUpdate(chatConfig, new String[] {OlioFieldNames.FIELD_INTERACTION});
				Queue.processQueue();
			}
		}
		//logger.info("Inter: " + (interaction != null ? NarrativeUtil.describeInteraction(interaction) : ""));
		templ = interactDesc.matcher(templ).replaceAll((interaction != null ? NarrativeUtil.describeInteraction(interaction) : ""));
		
		String iPrompt = chatConfig.get("userPrompt");
		templ = userPrompt.matcher(templ).replaceAll((iPrompt != null ? iPrompt : ""));
		
		return templ.trim();
	}
	
}
