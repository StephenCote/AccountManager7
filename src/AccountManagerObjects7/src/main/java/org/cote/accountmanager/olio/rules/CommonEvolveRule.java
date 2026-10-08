package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

/// Adapter base for IOlioEvolveRule: every hook is an intentional no-op (or null) so a concrete rule
/// overrides only the epoch/realm/increment phases it participates in.
public abstract class CommonEvolveRule implements IOlioEvolveRule {

	@Override
	public void startEpoch(OlioContext context, BaseRecord epoch) {
		
	}

	@Override
	public void continueEpoch(OlioContext context, BaseRecord epoch) {
		
	}

	@Override
	public void endEpoch(OlioContext context, BaseRecord epoch) {
		
	}

	

	@Override
	public void beginEvolution(OlioContext context) {
		
	}

	@Override
	public void evaluateRealmIncrement(OlioContext context, BaseRecord realm) {
		
	}

	@Override
	public void endRealmIncrement(OlioContext context, BaseRecord realm) {
		
	}

	@Override
	public void startRealmEvent(OlioContext context, BaseRecord realm) {
		
	}

	@Override
	public void continueRealmEvent(OlioContext context, BaseRecord realm) {
		
	}

	@Override
	public void endRealmEvent(OlioContext context, BaseRecord realm) {
		
	}

	@Override
	public BaseRecord startRealmIncrement(OlioContext context, BaseRecord realm) {
		return null;
	}

	@Override
	public BaseRecord continueRealmIncrement(OlioContext context, BaseRecord realm) {
		return null;
	}

	@Override
	public BaseRecord nextRealmIncrement(OlioContext context, BaseRecord realm) {
		return null;
	}

}
