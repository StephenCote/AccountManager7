package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public interface IOlioContextRule {
	public void pregenerate(OlioContext context);
	public BaseRecord[] selectLocations(OlioContext context);

}
