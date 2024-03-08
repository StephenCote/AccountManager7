package org.cote.accountmanager.olio;

import org.cote.accountmanager.olio.personality.PersonalityRules;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;

public class ProfileComparison {
	private PersonalityProfile profile1 = null;
	private PersonalityProfile profile2 = null;
	private CompatibilityEnumType compatibility = CompatibilityEnumType.UNKNOWN;

	public ProfileComparison(PersonalityProfile prof1, PersonalityProfile prof2) {
		this.profile1 = prof1;
		this.profile2 = prof2;
		compatibility = MBTIUtil.getCompatibility(prof1.getMbtiKey(), prof2.getMbtiKey());
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
}
