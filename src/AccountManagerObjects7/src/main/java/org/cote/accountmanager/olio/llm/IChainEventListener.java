package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.record.BaseRecord;

public interface IChainEventListener {
	void onChainEvent(BaseRecord user, BaseRecord chainEvent);
}
