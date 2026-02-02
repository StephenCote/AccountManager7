package org.cote.sockets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.IChainEventListener;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.JSONUtil;

public class ChainEventHandler implements IChainEventListener {
	private static final Logger logger = LogManager.getLogger(ChainEventHandler.class);

	@Override
	public void onChainEvent(BaseRecord user, BaseRecord chainEvent) {
		String eventType = chainEvent.get("eventType");
		String eventJson = JSONUtil.exportObject(chainEvent);
		logger.info("Chain event: " + eventType + " for user " + user.get("urn"));
		WebSocketService.chirpUser(user, new String[] {
			"chainEvent", eventType, eventJson
		});
	}
}
