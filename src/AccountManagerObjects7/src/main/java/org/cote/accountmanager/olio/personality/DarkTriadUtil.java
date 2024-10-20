package org.cote.accountmanager.olio.personality;

import java.math.RoundingMode;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ReasonEnumType;
import org.cote.accountmanager.olio.RollEnumType;
import org.cote.accountmanager.olio.RollUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ComputeUtil;

public class DarkTriadUtil {
	public static final Logger logger = LogManager.getLogger(DarkTriadUtil.class);
	/// Dark Cube
	/// https://www.ncbi.nlm.nih.gov/pmc/articles/PMC4783766/
	/// Adapted from https://pubmed.ncbi.nlm.nih.gov/28951816/#:~:text=Background%3A%20The%20Big%20Five%20traits,Machiavellianism%2C%20narcissism%2C%20and%20psychopathy.
	/* 
	 * M, high Machiavellianism; m, low Machiavellianism; N, high narcissism; n, low narcissism; P, high psychopathy; p, low psychopathy): MNP "maleficent", MNp "manipulative narcissistic", MnP "anti-social", Mnp "Machiavellian", mNP "psychopathic narcissistic", mNp "narcissistic", mnP "psychopathic", and mnp "benevolent".
	 */
	private static SecureRandom rand = new SecureRandom();
	private static Map<String, String> triadKeyTitles = new HashMap<>();
	static {
		triadKeyTitles.put("MNP", "maleficent");
		triadKeyTitles.put("MNp", "manipulative narcissistic");
		triadKeyTitles.put("MnP", "anti-social");
		triadKeyTitles.put("Mnp", "machiavellian");
		triadKeyTitles.put("mNP", "psychopathic narcissistic");
		triadKeyTitles.put("mNp", "narcissistic");
		triadKeyTitles.put("mnP", "psychopathic");
		triadKeyTitles.put("mnp", "benevolent");
	}
	/// This is a somewhat simplistic view on the DarkTriad personality theory, and currently only indicates a presence on the spectrum and not the degree
	///
	public static String getDarkTriadKey(BaseRecord per) {
		double mach = per.get(OlioFieldNames.FIELD_MACHIAVELLIANISM);
		double narc = per.get(OlioFieldNames.FIELD_NARCISSISM);
		double psych = per.get(OlioFieldNames.FIELD_PSYCHOPATHY);
		StringBuilder buff = new StringBuilder();
		if(mach > 0.5) buff.append("M");
		else buff.append("m");
		if(narc > 0.5) buff.append("N");
		else buff.append("n");
		if(psych > 0.5) buff.append("P");
		else buff.append("p");

		return buff.toString();
	}
	public static String getDarkTriadName(String key) {
		if(key != null && triadKeyTitles.containsKey(key)) {
			return triadKeyTitles.get(key);
		}
		return null;
	}
	

	public static int getDeceptionCounterStatistic(BaseRecord record) {
		BaseRecord stat = record.get(OlioFieldNames.FIELD_STATISTICS);
		int cs = ComputeUtil.getAverage(stat, new String[] {OlioFieldNames.FIELD_PERCEPTION, OlioFieldNames.FIELD_WISDOM, OlioFieldNames.FIELD_MENTAL_ENDURANCE});
		return cs;
		//if(cs > 0) return cs / 2;
		//return 0;
	}
	public static int getDeceptionStatistic(BaseRecord record) {
		BaseRecord stat = record.get(OlioFieldNames.FIELD_STATISTICS);
		int ci = ComputeUtil.getMaximumInt(stat, new String[] {OlioFieldNames.FIELD_CHARISMA, OlioFieldNames.FIELD_INTELLIGENCE});
		int cp = ComputeUtil.getMinimumInt(stat, new String[] {OlioFieldNames.FIELD_CREATIVITY, OlioFieldNames.FIELD_PERCEPTION});
		int cv = ci + cp;
		if(cv > 0) {
			return cv / 2;
		}
		return 0;
	}
	public static RollEnumType rollDeception(BaseRecord rec) {
		int decStat = DarkTriadUtil.getDeceptionStatistic(rec);
		return RollUtil.rollStat20(decStat);
	}
	public static RollEnumType rollCounterDeception(BaseRecord rec) {
		int cntStat = DarkTriadUtil.getDeceptionCounterStatistic(rec);
		return RollUtil.rollStat20(cntStat);
	}
	public static RollEnumType rollNarcissism(BaseRecord rec) {
		return RollUtil.rollStat1(rec.get("personality.narcissism"));
	}
	public static RollEnumType rollCounterNarcissism(BaseRecord rec) {
		return RollUtil.rollStat20(ComputeUtil.getAverage(rec.get(OlioFieldNames.FIELD_STATISTICS), new String[] {OlioFieldNames.FIELD_SPIRITUALITY, "willpower"}));
	}
	public static RollEnumType rollPsychopathy(BaseRecord rec) {
		return RollUtil.rollStat1(rec.get("personality.psychopathy"));
	}
	public static RollEnumType rollCounterPsychopathy(BaseRecord rec) {
		return RollUtil.rollStat1(ComputeUtil.getDblAverage(rec.get(FieldNames.FIELD_PERSONALITY), new String[] {OlioFieldNames.FIELD_CONSCIENTIOUSNESS, OlioFieldNames.FIELD_AGREEABLENESS}));
	}
	public static OutcomeEnumType ruleDarkTriad(BaseRecord interaction, PersonalityProfile actor, PersonalityProfile interactor) {
		OutcomeEnumType actorOutcome = OutcomeEnumType.EQUILIBRIUM;
		OutcomeEnumType interactorOutcome = OutcomeEnumType.EQUILIBRIUM;
		ReasonEnumType ret = interaction.getEnum(OlioFieldNames.FIELD_ACTOR_REASON);
		RollEnumType roll = RollEnumType.UNKNOWN;
		RollEnumType iroll = RollEnumType.UNKNOWN;
		switch(ret) {
			case ATTRACTIVE_NARCISSISM:
				roll = RollUtil.rollCharisma(actor.getRecord());
				iroll = RollUtil.rollCounterCharisma(interactor.getRecord());				
				break;
			case NARCISSISM:
				roll = rollNarcissism(actor.getRecord());
				iroll = rollCounterNarcissism(interactor.getRecord());
				break;
			case MACHIAVELLIANISM:
				roll = rollDeception(actor.getRecord());
				iroll = rollCounterDeception(interactor.getRecord());
				break;
			case PSYCHOPATHY:
				roll = rollPsychopathy(actor.getRecord());
				iroll = rollCounterPsychopathy(interactor.getRecord());
				break;
			default:
				logger.error("Unhandled reason type: " + ret.toString());
				break;
		}
		/// This is a fairly simple rule at the moment, meant to test tendencies vs. counter tendencies
		/// eg: A psycopath acts up, is the other able to counteract that instance of the person being a psycho
		/// Follow on actions then may dictate any ramifications, ie: if a psycho actor loses here, does that escalate into a different type of challenge?
		/// That determination should be made by the parent rule, and not within the utility
		///
		actorOutcome = RollUtil.rollToOutcome(roll);
		interactorOutcome = RollUtil.rollToOutcome(iroll);

		if(roll == RollEnumType.SUCCESS || roll == RollEnumType.NATURAL_SUCCESS) {
			if(iroll == RollEnumType.SUCCESS || iroll == RollEnumType.NATURAL_SUCCESS) {
				actorOutcome = OutcomeEnumType.UNFAVORABLE;
			}
		}
		else if(roll == RollEnumType.FAILURE || roll == RollEnumType.CATASTROPHIC_FAILURE) {
			if(iroll != RollEnumType.SUCCESS && iroll != RollEnumType.NATURAL_SUCCESS) {
				interactorOutcome = OutcomeEnumType.FAVORABLE;
			}
		}
		try {
			interaction.set("actorOutcome", actorOutcome);
			interaction.set("interactorOutcome", interactorOutcome);
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return actorOutcome;
	}
	
	public static List<PersonalityProfile> filterNarcissism(List<PersonalityProfile> map){
		return map.stream()
			.filter(p ->
				p.isNarcissist()
			)
			.collect(Collectors.toList())
		;
	}
	
	public static List<PersonalityProfile> filterMachiavellianism(List<PersonalityProfile> map){
		return map.stream()
			.filter(p ->
				p.isMachiavellian()
			)
			.collect(Collectors.toList())
		;
	}

	public static List<PersonalityProfile> filterMachiavellianism(List<PersonalityProfile> map, PersonalityProfile ignore){
		return map.stream()
			.filter(p ->
				p.getId() != ignore.getId()
				&&
				p.isMachiavellian()
			)
			.collect(Collectors.toList())
		;
	}
	
	
	public static List<PersonalityProfile> filterPsychopath(List<PersonalityProfile> map){
		return map.stream()
			.filter(p ->
				p.isPsychopath()
			)
			.collect(Collectors.toList())
		;
	}
	public static List<PersonalityProfile> filterPsychopath(List<PersonalityProfile> map, PersonalityProfile ignore){
		return map.stream()
			.filter(p ->
				p.getId() != ignore.getId()
				&&
				p.isPsychopath()
			)
			.collect(Collectors.toList())
		;
	}
	
	public static void rollDarkPersonality(BaseRecord per) {
		/// Based on published research (that I read), the only reliable correlation between personality metrics and dark cube traits are very low aggreeableness. 
		/// psychopaths and narcissists have higher values in openness and extraversion
		/// I based the utility calculations on a few other OCEAN to DARK CUBE studies, but they're fairly fudgy.
		/// Also, given the ranges, it's not possible to have more than one trait, which isn't really accurate
		/// Therefore, the roll will be based on the inverse of agreeableness, and the min of that number and the average of openness and extraversion for psychos and narcissists

		DecimalFormat df = new DecimalFormat("#.##");
		df.setRoundingMode(RoundingMode.HALF_EVEN);
		/// Allow a minimum of ten percent to dark personality traits
		///
		double agreeLimit = Math.max(1.0 - ((double)per.get(OlioFieldNames.FIELD_AGREEABLENESS)), 0.10);
		double avgLimit = ((double)per.get(OlioFieldNames.FIELD_OPENNESS) + (double)per.get(OlioFieldNames.FIELD_EXTRAVERSION)) / 2;
		double prettyPsycho = Math.min(agreeLimit, avgLimit);
		
		try {
			per.set(OlioFieldNames.FIELD_MACHIAVELLIANISM, Double.parseDouble(df.format(rand.nextDouble(agreeLimit))));
			per.set(OlioFieldNames.FIELD_NARCISSISM, Double.parseDouble(df.format(rand.nextDouble(prettyPsycho))));
			per.set(OlioFieldNames.FIELD_PSYCHOPATHY, Double.parseDouble(df.format(rand.nextDouble(prettyPsycho))));
		} catch (NumberFormatException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		
	}
	
	public static double getAggressiveness(BaseRecord per) {
		double ne = per.get("neuroticism");
		double na = per.get("narcissism");
		double op = per.get("openness");
		double ag = per.get("agreeableness");
		double co = per.get("conscientiousness");
		return ((ne + (1 - op) + na - (1 - ag) + (1 - co)) / 5);
	}
	
	private static boolean isNarcissist(BaseRecord per) {
		return (
			((double)per.get("personality.extraversion")) >= 0.8
			&&
			((double)per.get("personality.agreeableness")) <= 0.3
			&&
			((double)per.get("personality.conscientiousness")) <= 0.5
			&&
			((double)per.get("personality.neuroticism")) >= 0.5
			&&
			((double)per.get("personality.openness")) >= 0.7
			&&
			((int)per.get("statistics.intelligence")) >= 9
		);
	}
	private static boolean isMachiavellian(BaseRecord per) {
		
		return (
				((double)per.get("personality.extraversion")) <= 0.5
				&&
				((double)per.get("personality.agreeableness")) <= 0.2
				&&
				((double)per.get("personality.conscientiousness")) <= 0.3
				&&
				((double)per.get("personality.neuroticism")) >= 0.6
				&&
				((double)per.get("personality.openness")) <= 0.5
				&&
				((int)per.get("statistics.intelligence")) >= 9
			);
	}
	
	private static boolean isPsychopath(BaseRecord per) {
		
		return (
				((double)per.get("personality.extraversion")) >= 0.7
				&&
				((double)per.get("personality.agreeableness")) <= 0.4
				&&
				((double)per.get("personality.conscientiousness")) <= 0.3
				&&
				((double)per.get("personality.neuroticism")) <= 0.3
				&&
				((double)per.get("personality.openness")) >= 0.6
				&&
				((int)per.get("statistics.intelligence")) >= 9
			);

	}
	
	
	private static boolean isNarcissist(PersonalityProfile prof) {
		return (
			Rules.ruleMostly(prof.getExtraverted())
			&&
			Rules.ruleNotUsually(prof.getAgreeable())
			&&
			Rules.ruleLessSomewhat(prof.getConscientious())
			&&
			Rules.ruleSomewhat(prof.getNeurotic())
			&&
			Rules.ruleUsually(prof.getOpen())
			&&
			Rules.ruleFair(prof.getIntelligence())
		);
	}
	private static boolean isMachiavellian(PersonalityProfile prof) {
		return (
			Rules.ruleLessSomewhat(prof.getExtraverted())
			&&
			Rules.ruleSlightly(prof.getAgreeable())
			&&
			Rules.ruleNotUsually(prof.getConscientious())
			&&
			Rules.ruleFrequently(prof.getNeurotic())
			&&
			Rules.ruleLessSomewhat(prof.getOpen())
			&&
			Rules.ruleFair(prof.getIntelligence())
		);
	}
	private static boolean isPsychopath(PersonalityProfile prof) {
		return (
			Rules.ruleUsually(prof.getExtraverted())
			&&
			Rules.ruleLessFrequently(prof.getAgreeable())
			&&
			Rules.ruleNotUsually(prof.getConscientious())
			&&
			Rules.ruleNotUsually(prof.getNeurotic())
			&&
			Rules.ruleFrequently(prof.getOpen())
			&&
			Rules.ruleFair(prof.getIntelligence())
		);
	}
}
