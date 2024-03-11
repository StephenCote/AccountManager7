package org.cote.accountmanager.olio;

import java.util.Arrays;
import java.util.List;

import org.cote.accountmanager.olio.personality.PersonalityRules;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

public class ProfileComparison {
	private PersonalityProfile profile1 = null;
	private PersonalityProfile profile2 = null;
	private CompatibilityEnumType compatibility = CompatibilityEnumType.UNKNOWN;

	public ProfileComparison(PersonalityProfile prof1, PersonalityProfile prof2) {
		this.profile1 = prof1;
		this.profile2 = prof2;
		compatibility = MBTIUtil.getCompatibility(prof1.getMbtiKey(), prof2.getMbtiKey());
	}
	
	/// This is a rather lame compatibility determination at the moment
	///
	public CompatibilityEnumType getRomanticCompatibility() {
		List<String> genders = Arrays.asList(new String[] {profile1.getGender(), profile2.getGender()});
		CompatibilityEnumType compat = CompatibilityEnumType.NOT_COMPATIBLE;
		/// Trad. romantic compatibility - personality match, gender match
		if(genders.contains("male") && genders.contains("female") && profile1.getAge() >= Rules.MAXIMUM_CHILD_AGE && profile2.getAge() >= Rules.MAXIMUM_CHILD_AGE) {
			/// Not a large age spread
			if(compatibility != CompatibilityEnumType.NOT_COMPATIBLE) {
				if(!doesAgeCrossBoundary()) {
					if(HighEnumType.compare(getCharismaMargin(), HighEnumType.MODEST, ComparatorEnumType.LESS_THAN_OR_EQUALS)) {
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
	public VeryEnumType getWealthMargin() {
		double wealth1 = ItemUtil.countMoney(profile1.getRecord());
		double wealth2 = ItemUtil.countMoney(profile2.getRecord());
		return VeryEnumType.valueOf(Math.min(wealth1, wealth2) / Math.max(wealth1, wealth2));
	}
	
}
