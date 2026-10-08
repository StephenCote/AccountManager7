package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

/// Adapter base for IOlioContextRule: every hook is an intentional no-op (or null) so a concrete rule
/// overrides only the generation phases it participates in.
public abstract class CommonContextRule implements IOlioContextRule {

	@Override
	public BaseRecord generate(OlioContext context) {
		return null;
	}

	@Override
	public void pregenerate(OlioContext context) {
		
	}

	@Override
	public void generateRegion(OlioContext context, BaseRecord realm) {
		
	}

	@Override
	public void postgenerate(OlioContext context) {
		
	}

	@Override
	public BaseRecord[] selectLocations(OlioContext context) {
		return null;
	}

}
