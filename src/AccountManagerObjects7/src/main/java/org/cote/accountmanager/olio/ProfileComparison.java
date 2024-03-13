package org.cote.accountmanager.olio;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.personality.PersonalityRules;
import org.cote.accountmanager.olio.personality.PersonalityUtil;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class ProfileComparison {
	private OlioContext context = null;
	private PersonalityProfile profile1 = null;
	private PersonalityProfile profile2 = null;
	private CompatibilityEnumType compatibility = CompatibilityEnumType.UNKNOWN;

	public ProfileComparison(OlioContext ctx, PersonalityProfile prof1, PersonalityProfile prof2) {
		this.profile1 = prof1;
		this.profile2 = prof2;
		this.context = ctx;
		compatibility = MBTIUtil.getCompatibility(prof1.getMbtiKey(), prof2.getMbtiKey());
	}
	
	public CompatibilityEnumType getRomanticCompatibility() {
		List<String> genders = Arrays.asList(new String[] {profile1.getGender(), profile2.getGender()});
		CompatibilityEnumType compat = CompatibilityEnumType.NOT_COMPATIBLE;
		/// Trad. romantic compatibility - personality match, gender match
		if(genders.contains("male") && genders.contains("female") && profile1.getAge() >= Rules.MAXIMUM_CHILD_AGE && profile2.getAge() >= Rules.MAXIMUM_CHILD_AGE) { 
			/// personality is at least a little compatible
			if(compatibility != CompatibilityEnumType.NOT_COMPATIBLE) {
				if(!profile1.isMarried() && !profile2.isMarried()) {
					/// TODO: Include X range of prior positive interactions, common interests, and ideologies
					///
					/// Not a large age spread, and not a huge gap in charisma (being used as a relative and general measure of physical and personality attractiveness) 
					if(
						getRacialCompatibility() != CompatibilityEnumType.NOT_COMPATIBLE
						&& !doesAgeCrossBoundary()
						&& HighEnumType.compare(getCharismaMargin(), HighEnumType.MODEST, ComparatorEnumType.LESS_THAN_OR_EQUALS)
					) {
						compat = CompatibilityEnumType.IDEAL;	
					}
					else {
						compat = CompatibilityEnumType.COMPATIBLE;
					}
				}
				else {
					compat = CompatibilityEnumType.PARTIAL;
				}
			}
			else {
				compat = CompatibilityEnumType.NOT_IDEAL;
			}
		}
		return compat;
	}
	public CompatibilityEnumType getRacialCompatibility() {
		CompatibilityEnumType cet = CompatibilityEnumType.NOT_COMPATIBLE;
		List<String> race1 = profile1.getRace();
		List<String> race2 = profile2.getRace();
		if(race1.size() > 0 && race2.size() > 0) {
			List<String> differences = race1.stream().filter(r -> !race2.contains(r)).collect(Collectors.toList());
			if(differences.size() == 0) {
				cet = CompatibilityEnumType.IDEAL;
			}
			/// TODO: Need to encapsulate this type of rule into something more data-driven and configurable
			///
			else if(
				(race1.size() == 1 && race1.contains("alien")) || (race2.size() == 1 && race2.contains("alien"))
				||
				(race1.size() == 1 && race1.contains("vampire")) || (race2.size() == 1 && race2.contains("vampire"))
				||
				(race1.size() == 1 && race1.contains("robot")) || (race2.size() == 1 && race2.contains("robot"))
			){
				cet = CompatibilityEnumType.NOT_IDEAL;
			}
			else if(race1.contains(race2) || race2.contains(race1)){
				cet = CompatibilityEnumType.COMPATIBLE;
			}
			else {
				cet = CompatibilityEnumType.PARTIAL;
			}
		}
		return cet;
	}
	public CompatibilityEnumType getCompatibility() {
		return compatibility;
	}

	public HighEnumType getCharismaMargin() {
		return HighEnumType.margin(profile1.getCharisma(), profile2.getCharisma());
	}
	public int getCharismaDiff() {
		return getStatDiff("charisma");
	}
	public HighEnumType getIntelligenceMargin() {
		return HighEnumType.margin(profile1.getIntelligence(), profile2.getIntelligence());
	}	
	public int getIntelligenceDiff() {
		return getStatDiff("intelligence");
	}
	public HighEnumType getPhysicalStrengthMargin() {
		return HighEnumType.margin(profile1.getPhysicalStrength(), profile2.getPhysicalStrength());
	}
	public int getPhysicalStrengthDiff() {
		return getStatDiff("physicalStrength");
	}
	public HighEnumType getWisdomMargin() {
		return HighEnumType.margin(profile1.getWisdom(), profile2.getWisdom());
	}
	public int getWisdomDiff() {
		return getStatDiff("wisdom");
	}
	private int getStatDiff(String fieldName) {
		return (((int)profile1.getRecord().get("statistics." + fieldName)) - ((int)profile2.getRecord().get("statistics." + fieldName)));
	}
	private double getPersonalityDiff(String fieldName) {
		return (((double)profile1.getRecord().get("personality." + fieldName)) - ((double)profile2.getRecord().get("personality." + fieldName)));
	}
	public boolean doesAgeCrossBoundary() {
		return PersonalityRules.ruleCrossesAgeBoundary(profile1.getRecord(), profile2.getRecord());
	}
	public double getMachiavellianDiff() {
		return getPersonalityDiff("machiavellianism");
	}
	public double getPsychopathyDiff() {
		return getPersonalityDiff("psychopathy");
	}
	public double getNarcissismDiff() {
		return getPersonalityDiff("narcissism");
	}
	public double getWealthGap() {
		double wealth1 = ItemUtil.countMoney(profile1.getRecord());
		double wealth2 = ItemUtil.countMoney(profile2.getRecord());
		return (1 - (Math.min(wealth1, wealth2) / Math.max(wealth1, wealth2)));
	}
	public VeryEnumType getWealthMargin() {
		double wealth1 = ItemUtil.countMoney(profile1.getRecord());
		double wealth2 = ItemUtil.countMoney(profile2.getRecord());
		return VeryEnumType.valueOf(Math.min(wealth1, wealth2) / Math.max(wealth1, wealth2));
	}
	
	public String compare() {
		CompatibilityEnumType cet = getCompatibility();
		CompatibilityEnumType rcet = getRomanticCompatibility();
		CompatibilityEnumType racet = getRacialCompatibility();
		boolean ageIssue = doesAgeCrossBoundary();
		double machDiff = getMachiavellianDiff();
		double psychDiff = getPsychopathyDiff();
		double narcDiff = getNarcissismDiff();
		int prettyDiff = getCharismaDiff();
		int smartDiff = getIntelligenceDiff();
		int strongDiff = getPhysicalStrengthDiff();
		int wisDiff = getWisdomDiff();
		
		PersonalityProfile outLead = PersonalityUtil.identifyLeaderPersonality(Arrays.asList(profile1, profile2));
		boolean isLeader = false;
		boolean isLeaderContest = false;
		if(outLead.getId() == profile1.getId()) {
			isLeader = true;
		}
		else {
			isLeaderContest = GroupDynamicUtil.contestLeadership(context, null, Arrays.asList(profile1), profile2).size() > 0;
		}

		double wealth1 = ItemUtil.countMoney(profile1.getRecord());
		double wealth2 = ItemUtil.countMoney(profile2.getRecord());
		double wealthGap = getWealthGap();
		StringBuilder buff = new StringBuilder();
		buff.append(profile1.getName() + " (" + profile1.getGender() + ", " + profile1.getAge() + ") --- " + profile2.getName() + " (" + profile2.getGender() + ", " + profile2.getAge() + ") ");
		buff.append("\nHow is #1 aligning? " + profile1.getAlignment().toString());
		buff.append("\nHow is #2 aligning? " + profile2.getAlignment().toString());
		buff.append("\nAre their personalities compatible? " + cet.toString());
		buff.append("\nAre they romantically compatibile? " + rcet.toString());
		buff.append("\nAre they racially compatibile? " + racet.toString());
		buff.append("\nWhat is the wealth gap? " + wealthGap);
		buff.append("\nWho is richer, #1 or #2? " + wealth1 + " " + wealth2);
		buff.append("\nIs there an age disparity? " + ageIssue);
		buff.append("\nHow much prettier is #1 from #2? " + prettyDiff);
		buff.append("\nHow much smarter is #1 from #2? " + smartDiff);
		buff.append("\nHow much stronger is #1 from #2? " + strongDiff);
		buff.append("\nHow much wiser is #1 from #2? " + wisDiff);
		buff.append("\nHow much more of machiavellian is #1 from #2? " + machDiff);
		buff.append("\nHow much more of a psychopath is #1 from #2? " + psychDiff);
		buff.append("\nHow much more narcissistic is #1 from #2? " + narcDiff);
		buff.append("\nIs #1 the leader? " + isLeader);
		buff.append("\nIs #1 contesting #2's leadership? " + isLeaderContest);
		
		return buff.toString();
	}
	
}
