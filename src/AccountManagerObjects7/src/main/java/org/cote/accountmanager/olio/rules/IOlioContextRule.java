package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public interface IOlioContextRule {
	
	public BaseRecord generate(OlioContext context);
	public void pregenerate(OlioContext context);
	public void generateRegion(OlioContext context, BaseRecord realm);
	public void postgenerate(OlioContext context);
	public BaseRecord[] selectLocations(OlioContext context);

}
