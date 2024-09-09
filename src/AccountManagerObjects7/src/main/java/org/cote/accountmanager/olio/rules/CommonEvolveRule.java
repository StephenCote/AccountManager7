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
	public void startRealmEpoch(OlioContext context, BaseRecord realm, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void continueRealmEpoch(OlioContext context, BaseRecord realm, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endRealmEpoch(OlioContext context, BaseRecord realm, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void startLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void continueLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public BaseRecord startIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord continueIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void evaluateIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord increment) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord increment) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent) {
		// TODO Auto-generated method stub
		return null;
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

}
