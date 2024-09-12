package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public interface IOlioEvolveRule {

	public void startEpoch(OlioContext context, BaseRecord epoch);
	public void continueEpoch(OlioContext context, BaseRecord epoch);
	public void endEpoch(OlioContext context, BaseRecord epoch);

	public void startRealmEvent(OlioContext context, BaseRecord realm);
	public void continueRealmEvent(OlioContext context, BaseRecord realm);
	public void endRealmEvent(OlioContext context, BaseRecord realm);

	/*
	public void startLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch);
	public void continueLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch);
	public void endLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch);
	*/
	public BaseRecord startRealmIncrement(OlioContext context, BaseRecord realm);
	public BaseRecord continueRealmIncrement(OlioContext context, BaseRecord realm);
	public void evaluateRealmIncrement(OlioContext context, BaseRecord realm);
	public void endRealmIncrement(OlioContext context, BaseRecord realm);
	
	public BaseRecord nextRealmIncrement(OlioContext context, BaseRecord realm);
	public void beginEvolution(OlioContext context);

}
