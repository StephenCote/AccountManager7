package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.olio.llm.policy.ResponsePolicyEvaluator.PolicyEvaluationResult;
import org.cote.accountmanager.record.BaseRecord;

public interface IChatHandler {
	public void onChatComplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response);
	public void onChatUpdate(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String message);
	public void onChatError(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String msg);
	public void onChatStart(BaseRecord user, ChatRequest chatRequest, OpenAIRequest request);
	/// Phase 9: Called when a post-response policy violation is detected
	public default void onPolicyViolation(BaseRecord user, OpenAIRequest request, OpenAIResponse response, PolicyEvaluationResult result) {}
	/// Phase 13: Called when a chat title is auto-generated after first exchange
	public default void onChatTitle(BaseRecord user, OpenAIRequest request, String title) {}
	/// Phase 13g: Called when a chat icon is auto-generated after first exchange
	public default void onChatIcon(BaseRecord user, OpenAIRequest request, String icon) {}
	/// Phase 13g: Called when autotune suggests prompt changes or rebalances options
	public default void onAutotuneEvent(BaseRecord user, OpenAIRequest request, String type, String data) {}
	/// Phase 13f: Called when a memory-related event occurs (keyframe, extracted, recalled)
	public default void onMemoryEvent(BaseRecord user, OpenAIRequest request, String type, String data) {}
	/// Called when mid-chat interaction evaluation produces a status update
	public default void onInteractionEvent(BaseRecord user, OpenAIRequest request, String data) {}
	/// Called when an evaluation phase starts or completes, so UX can show progress
	public default void onEvalProgress(BaseRecord user, OpenAIRequest request, String phase, String detail) {}
}
