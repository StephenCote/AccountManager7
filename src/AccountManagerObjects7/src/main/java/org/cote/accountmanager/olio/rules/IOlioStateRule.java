package org.cote.accountmanager.olio.rules;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.record.BaseRecord;

public interface IOlioStateRule {
	public boolean canMove(OlioContext context, BaseRecord animal, BaseRecord location);
}
