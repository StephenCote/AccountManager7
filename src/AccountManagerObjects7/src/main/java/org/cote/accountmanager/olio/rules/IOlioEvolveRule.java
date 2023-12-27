package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public interface IOlioEvolveRule {

	public void startEpoch(OlioContext context, BaseRecord epoch);
	public void continueEpoch(OlioContext context, BaseRecord epoch);
	public void endEpoch(OlioContext context, BaseRecord epoch);
	public void startLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch);
	public void continueLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch);
	public void endLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch);
	public void startIncrement(OlioContext context, BaseRecord locationEpoch);
	public void continueIncrement(OlioContext context, BaseRecord locationEpoch);
	public void endIncrement(OlioContext context, BaseRecord locationEpoch);
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent);
	public void beginEvolution(OlioContext context);

}
