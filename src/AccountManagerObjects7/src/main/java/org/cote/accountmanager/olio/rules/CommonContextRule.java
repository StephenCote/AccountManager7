package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public abstract class CommonContextRule implements IOlioContextRule {

	@Override
	public BaseRecord generate(OlioContext context) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void pregenerate(OlioContext context) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void generateRegion(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void postgenerate(OlioContext context) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public BaseRecord[] selectLocations(OlioContext context) {
		// TODO Auto-generated method stub
		return null;
	}

}
