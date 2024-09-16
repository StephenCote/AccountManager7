package org.cote.accountmanager.olio.rules;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.BuilderUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.actions.ActionUtil;

public class GenericItemDataLoadRule extends CommonContextRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(GenericItemDataLoadRule.class);
	
	@Override
	public void pregenerate(OlioContext context) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void postgenerate(OlioContext context) {
		ActionUtil.loadActions(context);
		ItemUtil.loadItems(context);
		BuilderUtil.loadBuilders(context);
		AnimalUtil.loadAnimals(context);
	}

}
