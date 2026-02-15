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
	public void addChatHandler(IChatHandler handler);
	/// Phase 13g: Title event notification for buffer-mode path
	public default void onChatTitle(BaseRecord user, OpenAIRequest request, String title) {}
	/// Phase 13g: Icon event notification
	public default void onChatIcon(BaseRecord user, OpenAIRequest request, String icon) {}
	/// Phase 13g: Autotune event notification (prompt suggestion or options rebalance)
	public default void onAutotuneEvent(BaseRecord user, OpenAIRequest request, String type, String data) {}
	/// Phase 13f: Memory event notification (OI-71, OI-72)
	public default void onMemoryEvent(BaseRecord user, OpenAIRequest request, String type, String data) {}
}
