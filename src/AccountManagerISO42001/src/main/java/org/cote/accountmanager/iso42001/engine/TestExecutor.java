package org.cote.accountmanager.iso42001.engine;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.record.BaseRecord;

/**
 * Base for ISO 42001 test executors: runs prompts against the live AM7 chat infrastructure
 * (design §1.2 / §5.4) with an <b>isolated session per trial</b> and captures every
 * request/response <b>verbatim</b> into a raw log (design §2.3 {@code rawLogRef}).
 *
 * <p>A run targets one endpoint (the {@code olio.llm.chatConfig} handed in). Running a "mix
 * of models" is multiple runs/configs — cross-model aggregation is Phase 5, not built here.</p>
 *
 * <p>Isolation: each Tier-1 trial builds a fresh {@link OpenAIRequest}; each Tier-2 trial
 * builds a fresh request and walks its turns, with no carry-over between trials. The
 * keyframe/memory/prune machinery on the chat layer is neutralized in {@link #neutralize}
 * so a trial is exactly the prompt(s) under test — no background LLM calls, no session
 * persistence.</p>
 */
public abstract class TestExecutor {

	private static final Logger logger = LogManager.getLogger(TestExecutor.class);

	protected static final String SYSTEM_ROLE = "system";

	protected final BaseRecord user;
	protected final BaseRecord chatConfig;
	protected final String model;

	/** Verbatim request/response capture, one entry per LLM call. */
	protected final List<Map<String, Object>> rawLog = new ArrayList<>();

	/** Count of trials whose LLM call actually returned content (endpoint reachable). */
	protected int reachableCalls = 0;
	/** Count of LLM calls attempted. */
	protected int attemptedCalls = 0;

	protected TestExecutor(BaseRecord user, BaseRecord chatConfig) {
		this.user = user;
		this.chatConfig = chatConfig;
		this.model = chatConfig != null ? chatConfig.get("model") : null;
		neutralize(chatConfig);
	}

	public List<Map<String, Object>> getRawLog() {
		return rawLog;
	}

	public int getReachableCalls() {
		return reachableCalls;
	}

	public int getAttemptedCalls() {
		return attemptedCalls;
	}

	public String getModel() {
		return model;
	}

	/**
	 * Disable the chat layer's background machinery (keyframes, memory extraction, pruning,
	 * interaction extraction) in-memory so each trial is a single clean call. Best-effort:
	 * fields absent on a given config are ignored.
	 */
	protected void neutralize(BaseRecord cfg) {
		if (cfg == null) {
			return;
		}
		setQuiet(cfg, "keyframeEvery", 0);
		setQuiet(cfg, "memoryExtractionEvery", 0);
		setQuiet(cfg, "interactionEvery", 0);
		setQuiet(cfg, "remindEvery", 0);
		setQuiet(cfg, "qualityEvaluatorEvery", 0);
		setQuiet(cfg, "extractMemories", false);
		setQuiet(cfg, "extractInteractions", false);
		setQuiet(cfg, "prune", false);
		setQuiet(cfg, "assist", false);
		setQuiet(cfg, "stream", false);
	}

	private void setQuiet(BaseRecord rec, String field, Object value) {
		try {
			rec.set(field, value);
		} catch (Exception e) {
			/// field not present on this config — fine.
		}
	}

	/**
	 * Tier-1: one isolated call with a system prompt + a single user message.
	 * Returns the assistant content, or {@code null} if the endpoint failed.
	 */
	protected String runTier1(String systemPrompt, String userMessage, TrialSpec spec) {
		Chat chat = newChat(systemPrompt);
		OpenAIRequest req = chat.newRequest(model);
		req.setStream(false);
		addMessage(req, Chat.userRole, userMessage);

		List<Map<String, String>> reqLog = snapshotRequest(req);
		String content = invoke(chat, req);
		log(spec, 1, 0, reqLog, content);
		return content;
	}

	/**
	 * Tier-2: a fresh, conversation-only session (no system prompt). Walks the user turns,
	 * appending each assistant reply, and returns the FINAL assistant content (the scored
	 * response). Returns {@code null} if any turn's call failed.
	 */
	protected String runTier2(List<String> userTurns, TrialSpec spec) {
		Chat chat = newChat(null);
		OpenAIRequest req = chat.newRequest(model);
		req.setStream(false);
		String last = null;
		for (int i = 0; i < userTurns.size(); i++) {
			addMessage(req, Chat.userRole, userTurns.get(i));
			List<Map<String, String>> reqLog = snapshotRequest(req);
			String content = invoke(chat, req);
			log(spec, 2, i, reqLog, content);
			if (content == null) {
				return null;
			}
			/// Append the assistant reply so the next turn sees the conversation.
			addMessage(req, Chat.assistantRole, content);
			last = content;
		}
		return last;
	}

	private Chat newChat(String systemPrompt) {
		Chat chat = new Chat(user, chatConfig, null);
		chat.setPersistSession(false);
		chat.setEnableKeyFrame(false);
		/// Always set the system prompt explicitly: a module that defines none (null) must
		/// send NO system message, not the chat layer's default roleplay prompt. newRequest
		/// only emits a system message when llmSystemPrompt is non-null.
		chat.setLlmSystemPrompt(systemPrompt);
		return chat;
	}

	private String invoke(Chat chat, OpenAIRequest req) {
		attemptedCalls++;
		try {
			OpenAIResponse resp = chat.chat(req);
			if (resp != null && resp.getMessage() != null) {
				String content = resp.getMessage().getContent();
				if (content != null) {
					reachableCalls++;
					return content;
				}
			}
		} catch (Exception e) {
			logger.warn("LLM call failed: " + e.getMessage());
		}
		return null;
	}

	private void addMessage(OpenAIRequest req, String role, String content) {
		OpenAIMessage msg = new OpenAIMessage();
		msg.setRole(role);
		msg.setContent(content);
		req.addMessage(msg);
	}

	/** Snapshot the request messages (role+content) for verbatim logging. */
	private List<Map<String, String>> snapshotRequest(OpenAIRequest req) {
		List<Map<String, String>> out = new ArrayList<>();
		List<OpenAIMessage> msgs = req.getMessages();
		if (msgs != null) {
			for (OpenAIMessage m : msgs) {
				Map<String, String> e = new LinkedHashMap<>();
				e.put("role", m.getRole());
				e.put("content", m.getContent());
				out.add(e);
			}
		}
		return out;
	}

	private void log(TrialSpec spec, int tier, int turnIndex,
			List<Map<String, String>> request, String responseContent) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("trialId", spec.getTrialId());
		entry.put("group", spec.getGroup());
		entry.put("token", spec.getToken());
		entry.put("tier", tier);
		entry.put("turnIndex", turnIndex);
		entry.put("model", model);
		entry.put("timestamp", Instant.now().toString());
		entry.put("request", request);
		entry.put("responseContent", responseContent);
		entry.put("reachable", responseContent != null);
		rawLog.add(entry);
	}
}
