package org.cote.accountmanager.olio.personality;

import java.util.List;
import java.util.stream.Collectors;

import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.Rules;

public class DarkTriadUtil {
	/// This is a somewhat simplistic view on the DarkTriad personality theory, and currently only indicates a presence on the spectrum and not the degree
	///
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
	public static boolean isNarcissist(PersonalityProfile prof) {
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
	public static boolean isMachiavellian(PersonalityProfile prof) {
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
	public static boolean isPsychopath(PersonalityProfile prof) {
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
