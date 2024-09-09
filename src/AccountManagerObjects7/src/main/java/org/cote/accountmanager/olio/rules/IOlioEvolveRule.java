package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public interface IOlioEvolveRule {

	public void startEpoch(OlioContext context, BaseRecord epoch);
	public void continueEpoch(OlioContext context, BaseRecord epoch);
	public void endEpoch(OlioContext context, BaseRecord epoch);

	public void startRealmEpoch(OlioContext context, BaseRecord realm, BaseRecord epoch);
	public void continueRealmEpoch(OlioContext context, BaseRecord realm, BaseRecord epoch);
	public void endRealmEpoch(OlioContext context, BaseRecord realm, BaseRecord epoch);

	public void startLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch);
	public void continueLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch);
	public void endLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch);
	public BaseRecord startIncrement(OlioContext context, BaseRecord locationEpoch);
	public BaseRecord continueIncrement(OlioContext context, BaseRecord locationEpoch);
	public void evaluateIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord increment);
	public void endIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord increment);
	
	public void evaluateRealmIncrement(OlioContext context, BaseRecord realm);
	public void endRealmIncrement(OlioContext context, BaseRecord realm);

	
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent);
	public void beginEvolution(OlioContext context);

}
