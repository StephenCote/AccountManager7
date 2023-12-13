package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public interface IOlioEvolveRule {
	public void generateRegion(OlioContext context, BaseRecord rootEvent, BaseRecord event);
	public void beginEvolution(OlioContext context);

}
