package org.cote.accountmanager.olio.personality;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.AnimalProfile;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.record.BaseRecord;

public class PersonalityUtil {
	public static final Logger logger = LogManager.getLogger(PersonalityUtil.class);
	
	public static List<PersonalityProfile> filterProfileByMBTI(List<PersonalityProfile> map, String mbtiKey){
		return map.stream()
			.filter(pp -> pp.getMbtiKey() != null && pp.getMbtiKey().equals(mbtiKey))
			.collect(Collectors.toList())
		;
	}
	public static List<BaseRecord> filterByMBTI(List<PersonalityProfile> map, String mbtiKey){
		return filterProfileByMBTI(map, mbtiKey).stream()
			.map(pp -> pp.getRecord())
			.collect(Collectors.toList())
		;
	}

	public static PersonalityProfile identifyLeader(List<PersonalityProfile> profs) {
		profs.sort(PersonalityUtil.leaderComparator());
		return profs.get(profs.size() - 1);
	}
	
	protected static Comparator<PersonalityProfile> leaderComparator() {
	    return
	    	Comparator.comparing(PersonalityProfile::getExtraverted)
	    	.thenComparing(PersonalityProfile::getCharisma)
	    	.thenComparing(PersonalityProfile::getPhysicalStrength)
	    ;
	}
	
	public static List<PersonalityProfile> filterCommanders(List<PersonalityProfile> map) {
		return filterProfileByMBTI(map, "entj");
	}

	public static List<PersonalityProfile> filterDirectors(List<PersonalityProfile> map) {
		return filterProfileByMBTI(map, "estj");
	}
	
	public static List<PersonalityProfile> filterNarcissists(List<PersonalityProfile> map){
		return map.stream()
			.filter(p ->
				Rules.ruleMostly(p.getExtraverted())
				&&
				Rules.ruleNotUsually(p.getAgreeable())
			)
			.collect(Collectors.toList())
		;
	}

	public static List<PersonalityProfile> filterPretty(List<PersonalityProfile> map){
		return map.stream()
			.filter(p ->
				Rules.rulePrettyGood(p.getCharisma())
			)
			.collect(Collectors.toList())
		;
	}

	public static boolean sameProfile(AnimalProfile ap1, AnimalProfile ap2) {
		return ap1.getId() == ap2.getId();
	}
	public static List<PersonalityProfile> filterBetterLooking(List<PersonalityProfile> map, PersonalityProfile ref){
		return map.stream()
			.filter(p ->
				!sameProfile(ref, p)
				&&
				Rules.ruleBetterThan(p.getCharisma(), ref.getCharisma())
			)
			.collect(Collectors.toList())
		;
	}
	
	public static List<PersonalityProfile> filterBetterLookingPrettyNarcissists(List<PersonalityProfile> map, PersonalityProfile ref){
		return filterBetterLooking(filterPretty(filterNarcissists(map)), ref);
	}
}
