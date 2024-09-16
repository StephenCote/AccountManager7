package org.cote.accountmanager.olio.personality;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class PersonalityRules {
	public static final Logger logger = LogManager.getLogger(PersonalityRules.class);
	
	public static boolean ruleCrossesAgeBoundary(BaseRecord rec1, BaseRecord rec2) {
		int minAge = Math.min(rec1.get(FieldNames.FIELD_AGE), rec2.get(FieldNames.FIELD_AGE));
		int maxAge = Math.max(rec1.get(FieldNames.FIELD_AGE), rec2.get(FieldNames.FIELD_AGE));
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
