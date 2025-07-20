package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.record.BaseRecord;

public interface IChatListener {
	public OpenAIRequest sendMessageToServer(BaseRecord user, ChatRequest request);
	public ChatRequest getMessage(BaseRecord user, ChatRequest messageRequest);
	public void oncomplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response);
	public void onupdate(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String message);
	public void onerror(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String msg);
	public boolean isStopStream(OpenAIRequest request);
	public void stopStream(OpenAIRequest request);
	public boolean isRequesting(OpenAIRequest request);
}
