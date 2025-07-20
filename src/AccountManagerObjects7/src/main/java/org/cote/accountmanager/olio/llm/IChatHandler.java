package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.record.BaseRecord;

public interface IChatHandler {
	public void onChatComplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response);
	public void onChatUpdate(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String message);
	public void onChatError(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String msg);
	public void onChatStart(BaseRecord user, ChatRequest chatRequest, OpenAIRequest request);
}
