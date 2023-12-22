package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.BuilderUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public class GenericItemDataLoadRule implements IOlioContextRule {

	@Override
	public void pregenerate(OlioContext context) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void postgenerate(OlioContext context) {
		ItemUtil.loadItems(context);
		BuilderUtil.loadBuilders(context);
	}

	@Override
	public BaseRecord[] selectLocations(OlioContext context) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void generateRegion(OlioContext context, BaseRecord rootEvent, BaseRecord event) {
		// TODO Auto-generated method stub
		
	}

}
