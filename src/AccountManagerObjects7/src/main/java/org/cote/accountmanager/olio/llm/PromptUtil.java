package org.cote.accountmanager.olio.llm;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileComparison;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.RaceEnumType;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.personality.PersonalityUtil;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.record.BaseRecord;
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

	private static Pattern userPrompt = Pattern.compile("\\$\\{userPrompt\\}");
	private static Pattern scene = Pattern.compile("\\$\\{scene\\}"); 
	private static Pattern nlpPat = Pattern.compile("\\$\\{nlp\\}");
	private static Pattern nlpWarnPat = Pattern.compile("\\$\\{nlpWarn\\}");
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
	private static Pattern firstSecondToBe = Pattern.compile("\\$\\{firstSecondToBe\\}");
	
	public static String composeTemplate(List<String> list) {
		return Matcher.quoteReplacement(list.stream().collect(Collectors.joining(" ")));
	}

	public static String getSystemChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("system")).stream().collect(Collectors.joining("\r\n")));
	}
	public static String getUserChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("user")).stream().collect(Collectors.joining("\r\n")), true);
	}

	public static String getAssistChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("assistant")).stream().collect(Collectors.joining("\r\n")), true);
	}

	public static String getSystemAnalyzeTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("systemAnalyze")).stream().collect(Collectors.joining("\r\n")), true);
	}

	public static String getUserAnalyzeTemplate(BaseRecord promptConfig, BaseRecord chatConfig) {
		return getChatPromptTemplate(promptConfig, chatConfig, ((List<String>)promptConfig.get("userAnalyze")).stream().collect(Collectors.joining("\r\n")), true);
	}
	public static String getChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig, String templ) {
		return getChatPromptTemplate(promptConfig, chatConfig, templ, false);
	}
	public static String getChatPromptTemplate(BaseRecord promptConfig, BaseRecord chatConfig, String templ, boolean firstPerson) {

		if(promptConfig == null || chatConfig == null) {
			logger.error("Prompt configuration is null");
			return null;
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
				srace = composeTemplate(osupp.get().get("race"));
			}
		}

		if(usrProf.getRace().contains("L") || usrProf.getRace().contains("S") || usrProf.getRace().contains("V") || usrProf.getRace().contains("R") || usrProf.getRace().contains("W") || usrProf.getRace().contains("X") || usrProf.getRace().contains("Y") || usrProf.getRace().contains("Z")) {
			Optional<BaseRecord> osupp = races.stream().filter(r -> usrProf.getRace().contains(r.get("raceType"))).findFirst();
			if(osupp.isPresent()) {
				urace = composeTemplate(osupp.get().get("race"));
			}
		}
		templ = userRace.matcher(templ).replaceAll(urace);
		templ = systemRace.matcher(templ).replaceAll(srace);
		templ = annotateSupplement.matcher(templ).replaceAll(Matcher.quoteReplacement(asupp));
		templ = firstSecondToBe.matcher(templ).replaceAll(firstPerson ? "I am" : "You are");

		String scenel = "";
		if((boolean)chatConfig.get("includeScene")) {
			scenel = Matcher.quoteReplacement(((List<String>)promptConfig.get("scene")).stream().collect(Collectors.joining("\r\n")));
		}
		templ = scene.matcher(templ).replaceAll(scenel);
		
		String settingl = "";
		String settingStr = chatConfig.get("setting");
		/*
		if(settingStr == null || settingStr.length() == 0) {
			templ = setting.matcher(templ).replaceAll(composeTemplate(promptConfig.get("setting")));
		}
		*/
		String sysNlp = "";
		String assistNlp = "";
		boolean useNLP = chatConfig.get("useNLP");
		if(useNLP) {
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
		templ = censorWarn.matcher(templ).replaceAll(sysCens);
		templ = assistCensorWarn.matcher(templ).replaceAll(assistCens);

		String uconpref = composeTemplate(promptConfig.get("userConsentPrefix"));
		String ucons = "";
		if(rating == ESRBEnumType.M || rating == ESRBEnumType.AO || rating == ESRBEnumType.RC) {
			ucons = composeTemplate(promptConfig.get("userConsentRating"));
		}
		if(useNLP) {
			if(ucons.length() > 0) ucons += " and ";
			ucons += composeTemplate(promptConfig.get("userConsentNlp"));
		}
		templ = userConsent.matcher(templ).replaceAll(ucons.length() > 0 ? uconpref + ucons + ".": "");
		
		String ugen = userChar.get("gender");
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

		String sgen = systemChar.get("gender");
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
		List<String> utrades = userChar.get("trades");
		if(utrades.size() > 0) {
			ujobDesc =" " + utrades.get(0).toLowerCase();
		}
		
		String sjobDesc = "";
		List<String> strades = systemChar.get("trades");
		if(strades.size() > 0) {
			sjobDesc =" " + strades.get(0).toLowerCase();
		}		
		
		templ = systemASG.matcher(templ).replaceAll(systemChar.get("age") + " year old " + sgen + sjobDesc);
		templ = userASG.matcher(templ).replaceAll(userChar.get("age") + " year old " + ugen + ujobDesc);
		templ = userCPro.matcher(templ).replaceAll(ucpro);
		templ = userPro.matcher(templ).replaceAll(upro);
		templ = userPPro.matcher(templ).replaceAll(uppro);
		templ = userCPPro.matcher(templ).replaceAll(ucppro);
		templ = systemCPro.matcher(templ).replaceAll(scpro);
		templ = systemPro.matcher(templ).replaceAll(spro);
		templ = systemPPro.matcher(templ).replaceAll(sppro);
		templ = systemCPPro.matcher(templ).replaceAll(scppro);

		templ = ratingName.matcher(templ).replaceAll(ESRBEnumType.getESRBName(rating));
		templ = ratingPat.matcher(templ).replaceAll(rating.toString());
		templ = ratingDesc.matcher(templ).replaceAll(ESRBEnumType.getESRBShortDescription(rating));
		templ = ratingRestrict.matcher(templ).replaceAll(ESRBEnumType.getESRBRestriction(rating));
		templ = ratingMpa.matcher(templ).replaceAll(ESRBEnumType.getESRBMPA(rating));
		templ = userFirstName.matcher(templ).replaceAll((String)userChar.get("firstName"));
		templ = systemFirstName.matcher(templ).replaceAll((String)systemChar.get("firstName"));
		templ = userFullName.matcher(templ).replaceAll((String)userChar.get("name"));
		templ = systemFullName.matcher(templ).replaceAll((String)systemChar.get("name"));

		templ = userCharDesc.matcher(templ).replaceAll(NarrativeUtil.describe(null, userChar));
		templ = systemCharDesc.matcher(templ).replaceAll(NarrativeUtil.describe(null, systemChar));
		
		templ = userCharDescPublic.matcher(templ).replaceAll(NarrativeUtil.describe(null, userChar, true, false, false));
		templ = systemCharDescPublic.matcher(templ).replaceAll(NarrativeUtil.describe(null, systemChar, true, false, false));
		
		templ = userCharDescLight.matcher(templ).replaceAll(NarrativeUtil.describe(null, userChar, false, false, false));
		templ = systemCharDescLight.matcher(templ).replaceAll(NarrativeUtil.describe(null, systemChar, false, false, false));


		
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
			romCompat = "there could be something between us";
		}
		templ = profileRomanceCompat.matcher(templ).replaceAll("Romantically, " + romCompat + ".");
		
		BaseRecord cell = userChar.get("state.currentLocation");
		if(settingStr != null && settingStr.length() > 0) {
			if(settingStr.equalsIgnoreCase("random")) {
				settingStr = NarrativeUtil.getRandomSetting();
			}
			templ = setting.matcher(templ).replaceAll("The setting is: " + settingStr);
		}
		else {
			templ = setting.matcher(templ).replaceAll("");
			String tdesc = chatConfig.get("terrain");
			if(tdesc == null) tdesc = "";

			templ = locationTerrains.matcher(templ).replaceAll(tdesc);	
			// templ = locationTerrain.matcher(templ).replaceAll(tet.toString().toLowerCase());
		}

		String pdesc = chatConfig.get("populationDescription");
		String adesc = chatConfig.get("animalDescription");
		templ = peoplePop.matcher(templ).replaceAll(pdesc != null ? pdesc : "");
		templ = animalPop.matcher(templ).replaceAll(adesc != null ? adesc : "");
		
		AlignmentEnumType align = chatConfig.getEnum("alignment");
		templ = eventAlign.matcher(templ).replaceAll(NarrativeUtil.getOthersActLikeSatan(align));

		String leadDesc = "Neither one of us is in charge.";
		
		PersonalityProfile outLead = PersonalityUtil.identifyLeaderPersonality(Arrays.asList(sysProf, usrProf));
		boolean isLeaderContest = false;
		String contest = "I";
		
		leadDesc = outLead.getRecord().get("firstName") + " is the leader.";
		if(outLead.getId() == sysProf.getId()) {
			isLeaderContest = GroupDynamicUtil.contestLeadership(null, null, Arrays.asList(usrProf), sysProf).size() > 0;
		}
		else {
			contest = outLead.getRecord().get("firstName");
			isLeaderContest = GroupDynamicUtil.contestLeadership(null, null, Arrays.asList(sysProf), usrProf).size() > 0;
		}
		if(isLeaderContest) {
			leadDesc += " " + contest + " may challenge who is leading.";
		}
		
		templ = profileLeader.matcher(templ).replaceAll(leadDesc);
		
		BaseRecord loc = userChar.get("state.currentLocation");
		if(loc != null) {
			templ = locationTerrain.matcher(templ).replaceAll(loc.getEnum("terrainType").toString().toLowerCase());	
		}
		List<BaseRecord> interactions = chatConfig.get("interactions");
		BaseRecord interaction = null;
		if(interactions.size() > 0) {
			interaction = interactions.get(rand.nextInt(interactions.size()));
			IOSystem.getActiveContext().getReader().populate(interaction);
		}
		templ = interactDesc.matcher(templ).replaceAll((interaction != null ? NarrativeUtil.describeInteraction(interaction) : ""));
		
		String iPrompt = chatConfig.get("userPrompt");
		templ = userPrompt.matcher(templ).replaceAll((iPrompt != null ? iPrompt : ""));
		
		return templ.trim();
	}
	
}
