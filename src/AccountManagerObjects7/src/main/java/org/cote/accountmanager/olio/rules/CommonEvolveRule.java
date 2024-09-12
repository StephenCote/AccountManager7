package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public abstract class CommonEvolveRule implements IOlioEvolveRule {

	@Override
	public void startEpoch(OlioContext context, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void continueEpoch(OlioContext context, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endEpoch(OlioContext context, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	

	@Override
	public void beginEvolution(OlioContext context) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void evaluateRealmIncrement(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endRealmIncrement(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void startRealmEvent(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void continueRealmEvent(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endRealmEvent(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public BaseRecord startRealmIncrement(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord continueRealmIncrement(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord nextRealmIncrement(OlioContext context, BaseRecord realm) {
		// TODO Auto-generated method stub
		return null;
	}

}
