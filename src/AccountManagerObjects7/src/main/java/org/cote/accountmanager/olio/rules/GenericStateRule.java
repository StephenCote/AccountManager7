package org.cote.accountmanager.olio.rules;

import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioPolicyUtil;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;

public class GenericStateRule  implements IOlioStateRule {
	public static final Logger logger = LogManager.getLogger(GenericStateRule.class);

	@Override
	public boolean canMove(OlioContext context, BaseRecord animal, BaseRecord location) {
		List<BaseRecord> params = Arrays.asList(new BaseRecord[] {
			OlioPolicyUtil.createFactParameter("character", FieldEnumType.MODEL, animal),
			OlioPolicyUtil.createFactParameter("animalActor", FieldEnumType.MODEL, animal),
			OlioPolicyUtil.createFactParameter("targetLocation", FieldEnumType.MODEL, location),
		});
		return PolicyUtil.isPermit(OlioPolicyUtil.evalPolicy(context, params, "canMove"));
	}
	

}
