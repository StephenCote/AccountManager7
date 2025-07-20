package org.cote.accountmanager.objects.tests.olio;

import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatResponse;
import org.cote.accountmanager.olio.llm.IChatListener;
import org.cote.accountmanager.olio.llm.ChatListener;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.record.BaseRecord;

public class MockWebSocket extends ChatListener implements IChatListener {
	private BaseRecord user = null;
	public MockWebSocket(BaseRecord user) {
		super();
		this.user = user;
	}
	
	public OpenAIRequest sendMessageToServer(ChatRequest req) {
		return this.sendMessageToServer(user, req);
	}
}
