package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.record.BaseRecord;

public interface IChatListener {
	public void sendMessageToClient(BaseRecord user, OpenAIRequest request, String message);
	public void sendMessageToServer(BaseRecord user, ChatRequest request);
	public ChatRequest getMessage(BaseRecord user, ChatRequest messageRequest);
	public void oncomplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response);
	public boolean isStopStream(OpenAIRequest request);
}
