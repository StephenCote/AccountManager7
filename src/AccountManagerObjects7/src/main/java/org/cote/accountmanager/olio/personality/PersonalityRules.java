package org.cote.accountmanager.olio.personality;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ReasonEnumType;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.record.BaseRecord;

public class PersonalityRules {
	public static final Logger logger = LogManager.getLogger(PersonalityRules.class);
	
	public static boolean ruleCrossesAgeBoundary(BaseRecord rec1, BaseRecord rec2) {
		int minAge = Math.min(rec1.get("age"), rec2.get("age"));
		int maxAge = Math.max(rec1.get("age"), rec2.get("age"));
		return (
				(
					minAge < Rules.MAXIMUM_CHILD_AGE
					&&
					maxAge >= Rules.MAXIMUM_CHILD_AGE
				)
				||
				(
					minAge < Rules.MINIMUM_ADULT_AGE
					&&
					maxAge >= Rules.MINIMUM_ADULT_AGE
				)
				||
				(
					minAge < Rules.SENIOR_AGE
					&&
					maxAge >= Rules.SENIOR_AGE
				)
		);
	}
}
