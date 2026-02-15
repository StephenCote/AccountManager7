package org.cote.accountmanager.olio.llm;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum TemplatePatternEnumType {

	ANIMAL_POPULATION("population.animals"),
	ANNOTATE_SUPPLEMENT("annotateSupplement"),
	ASSISTANT_CENSOR_WARN("assistCensorWarn"),
	AUTO_SCENE("scene.auto"),
	CENSOR_WARN("censorWarn"),
	DYNAMIC_RULES("dynamicRules"),
	EPISODE("episode"),
	EPISODE_ASSIST("episodeAssist"),

	EPISODE_REMINDER("episodeReminder"),
	EPISODE_RULE("episodeRule"),
	EPISODIC("episodic"),
	EVENT_ALIGNMENT("event.alignment"),
	FIRST_SECOND_NAME("firstSecondName"),
	FIRST_SECOND_POSSESSIVE("firstSecondPos"),

	FIRST_SECOND_TO_BE("firstSecondToBe"),
	FIRST_SECOND_WHO("firstSecondWho"),
	INTERACTION_DESCRIPTION("interaction.description"),
	LOCATION_NAME("location.name"),
	LOCATION_TERRAIN("location.terrain"),
	LOCATION_TERRAINS("location.terrains"),
	MEMORY_CONTEXT("memory.context"),
	MEMORY_COUNT("memory.count"),
	MEMORY_FACTS("memory.facts"),
	MEMORY_LAST_SESSION("memory.lastSession"),
	MEMORY_RELATIONSHIP("memory.relationship"),
	NLP_COMMAND("nlp.command"),
	NLP("nlp"),

	NLP_REMINDER("nlpReminder"),
	NLP_WARN("nlpWarn"),
	POPULATION_PEOPLE("population.people"),
	PERSPECTIVE("perspective"),
	PROFILE_AGE_COMPATIBILITY("profile.ageCompat"),
	PROFILE_COMPARISON("profile.comparison"),
	PROFILE_LEADER("profile.leader"),
	PROFILE_RACE_COMPATIBILITY("profile.raceCompat"),
	PROFILE_ROMANCE_COMPATIBILITY("profile.romanceCompat"),
	AGE_GUIDANCE("ageGuidance"),
	RATING_DESCRIPTION("ratingDesc"),
	RATING_MPA("ratingMpa"),
	RATING_NAME("ratingName"),
	RATING("rating"),

	RATING_RESTRICT("ratingRestrict"),
	SCENE("scene"),
	
	SETTING("setting"),
	SYSTEM_GENDER("system.gender"),
	SYSTEM_ASG("system.asg"), 
	SYSTEM_CHARACTER_DESCRIPTION("system.characterDesc"),
	SYSTEM_CHARACTER_DESCRIPTION_LIGHT("system.characterDescLight"),
	SYSTEM_CHARACTER_DESCRIPTION_PUBLIC("system.characterDescPublic"),
	SYSTEM_CAPITAL_POSSESSIVE_PRONOUN("system.capPPro"),
	SYSTEM_CAPITAL_PRONOUN("system.capPro"),
	SYSTEM_FIRST_NAME("system.firstName"),
	SYSTEM_FULL_NAME("system.fullName"),
	SYSTEM_POSSESSIVE_PRONOUN("system.ppro"),
	SYSTEM_PRONOUN("system.pro"),
	SYSTEM_RACE("system.race"),
	SYSTEM_TRADE("system.trade"),
	USER_ASG("user.asg"),
	USER_CHARACTER_DESCRIPTION("user.characterDesc"),
	USER_CHARACTER_DESCRIPTION_LIGHT("user.characterDescLight"),
	USER_CHARACTER_DESCRIPTION_PUBLIC("user.characterDescPublic"),
	USER_CONSENT("user.consent"),
	USER_CAPITAL_POSSESSIVE_PRONOUN("user.capPPro"),
	USER_CAPITAL_PRONOUN("user.capPro"),
	USER_FIRST_NAME("user.firstName"),
	USER_FULL_NAME("user.fullName"),
	USER_GENDER("user.gender"),
	USER_POSSESSIVE_PRONOUN("user.ppro"),
	USER_PRONOUN("user.pro"),
	USER_PROMPT("userPrompt"),
	USER_RACE("user.race"),
	USER_TRADE("user.trade"),
	USER_CITATION("user.citations"),
	USER_QUESTION("user.question")
    ;

    private final String key;
    private final Pattern pattern;

    TemplatePatternEnumType(String key) {
        this.key = key;
        this.pattern = Pattern.compile(Pattern.quote("${" + key + "}"));
    }

    public String getKey() {
        return this.key;
    }
    
    public Pattern getPattern() {
		return this.pattern;
	}

    public String replace(String template, String replacement) {
        if (template == null) {
            return null;
        }
        if (replacement == null) {
            replacement = "";
        }
        return this.pattern.matcher(template).replaceAll(Matcher.quoteReplacement(replacement));
    }
}