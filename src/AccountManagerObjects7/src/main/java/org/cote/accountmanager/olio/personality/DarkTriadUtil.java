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
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.record.BaseRecord;

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
		double mach = per.get("machiavellianism");
		double narc = per.get("narcissism");
		double psych = per.get("psychopathy");
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
		double agreeLimit = 1.0 - ((double)per.get("agreeableness"));
		double avgLimit = ((double)per.get("openness") + (double)per.get("extraversion")) / 2;
		double prettyPsycho = Math.min(agreeLimit, avgLimit);
		
		try {
			per.set("machiavellianism", Double.parseDouble(df.format(rand.nextDouble(agreeLimit))));
			per.set("narcissism", Double.parseDouble(df.format(rand.nextDouble(prettyPsycho))));
			per.set("psychopathy", Double.parseDouble(df.format(rand.nextDouble(prettyPsycho))));
		} catch (NumberFormatException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		
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
