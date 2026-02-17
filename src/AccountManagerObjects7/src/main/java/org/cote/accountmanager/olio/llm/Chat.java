package org.cote.accountmanager.olio.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioTaskAgent;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.MemoryUtil;
import org.cote.accountmanager.mcp.McpContextBuilder;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.cote.accountmanager.schema.type.MemoryTypeEnumType;
import org.cote.accountmanager.olio.llm.policy.ChatAutotuner;
import org.cote.accountmanager.olio.llm.policy.ChatAutotuner.AutotuneResult;
import org.cote.accountmanager.olio.llm.policy.InteractionEvaluator;
import org.cote.accountmanager.olio.llm.policy.ResponseComplianceEvaluator;
import org.cote.accountmanager.olio.llm.policy.ResponsePolicyEvaluator;
import org.cote.accountmanager.olio.llm.policy.ResponsePolicyEvaluator.PolicyEvaluationResult;
import org.cote.accountmanager.olio.llm.policy.ResponsePolicyEvaluator.PolicyViolation;

public class Chat {

	public static final String userRole = "user";
	public static final String assistantRole = "assistant";
	public static final String systemRole = "system";
	private static final String CHAT_OPS_RESOURCE = "chatOperations";
	
	protected IOContext ioContext = null;
	public static final Logger logger = LogManager.getLogger(Chat.class);
	private BaseRecord promptConfig = null;
	private BaseRecord chatConfig = null;
	private boolean chatMode = true;
	private boolean includeMessageHistory = chatMode;
	private boolean includeContextHistory = !chatMode;
	private boolean persistSession = true;

	private static int contextSize = 8192;

	/// Which role to use for the keyframe content
	/// NOTE: When the Keyframe is added as an assistant response, the assistant
	/// will start copying that format
	private static String keyframeRole = userRole;

	private String saveName = "chat.json";
	private BaseRecord user = null;
	private int pruneSkip = 1;
	private boolean formatOutput = false;
	private boolean includeScene = false;
	private boolean forceJailbreak = false;
	private boolean streamMode = false;
	private int remind = 6;
	private int messageTrim = 20;
	private int keyFrameEvery = 20;
	private LLMServiceEnumType serviceType = LLMServiceEnumType.OPENAI;
	private String model = null;
	private String serverUrl = null;
	private String apiVersion = null;
	private String authorizationToken = null;
	private boolean deferRemote = false;
	private boolean enableKeyFrame = true;
	private IChatListener listener = null;
	private int requestTimeout = 120;
	/// Phase 14: Separate timeout for internal analyze/memory extraction LLM calls.
	/// Default 120s. Legacy configs without the field keep this default.
	private int analyzeTimeout = 120;
	/// Thread-local override so background analyze/extract threads don't mutate the shared requestTimeout field.
	private ThreadLocal<Integer> analyzeTimeoutOverride = new ThreadLocal<>();
	/// Guard against duplicate async keyframe generation when messages arrive faster than analysis completes.
	private volatile boolean asyncKeyframeInProgress = false;
	/// Deferred keyframe: snapshot saved by pruneCount, launched after main response completes.
	private volatile List<OpenAIMessage> pendingKeyframeSnapshot = null;
	private int responseCount = 0;

	/// Mid-stream policy evaluation tracking
	private static final int MID_STREAM_CHECK_INTERVAL = 200;
	private int lastMidStreamCheckLength = 0;
	private PolicyEvaluationResult midStreamViolation = null;

	private String llmSystemPrompt = """
			You play the role of an assistant named Siren.
			Begin conversationally.
			""";

	public Chat() {

	}
	
	public Chat(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig) {
		this.user = user;
		this.chatConfig = chatConfig;
		this.promptConfig = promptConfig;
		configureChat();
	}



	public boolean isPersistSession() {
		return persistSession;
	}

	public void setPersistSession(boolean persistSession) {
		this.persistSession = persistSession;
	}

	public BaseRecord getPromptConfig() {
		return promptConfig;
	}

	public BaseRecord getChatConfig() {
		return chatConfig;
	}

	public IChatListener getListener() {
		return listener;
	}



	public void setListener(IChatListener listener) {
		this.listener = listener;
	}



	public boolean isEnableKeyFrame() {
		return enableKeyFrame;
	}



	public void setEnableKeyFrame(boolean enableKeyFrame) {
		this.enableKeyFrame = enableKeyFrame;
	}



	public static String getKeyframeRole() {
		return keyframeRole;
	}

	public static void setKeyframeRole(String keyframeRole) {
		Chat.keyframeRole = keyframeRole;
	}

	public boolean isDeferRemote() {
		return deferRemote;
	}

	public void setDeferRemote(boolean deferRemote) {
		this.deferRemote = deferRemote;
	}

	public int getRequestTimeout() {
		return requestTimeout;
	}

	public void setRequestTimeout(int requestTimeout) {
		this.requestTimeout = requestTimeout;
	}

	public int getAnalyzeTimeout() {
		return analyzeTimeout;
	}

	/// Minimum keyframeEvery value when extractMemories is enabled.
	/// Prevents excessive analyze() LLM calls which are expensive.
	public static final int MIN_KEYFRAME_EVERY_WITH_EXTRACT = 5;

	private void configureChat() {
		if (chatConfig != null) {
			setServerUrl(chatConfig.get("serverUrl"));
			setApiVersion(chatConfig.get("apiVersion"));
			setAuthorizationToken(chatConfig.get("apiKey"));
			setModel(chatConfig.get("model"));
			setServiceType(chatConfig.getEnum("serviceType"));
			remind = chatConfig.get("remindEvery");
			keyFrameEvery = chatConfig.get("keyframeEvery");
			messageTrim = chatConfig.get("messageTrim");
			requestTimeout = chatConfig.get("requestTimeout");
			try { streamMode = chatConfig.get("stream"); } catch (Exception e) { /* field may not be set */ }
			/// Phase 14: Configure analyzeTimeout — separate from requestTimeout for background LLM calls.
			/// Legacy configs without the field will have cfgAnalyzeTimeout=0; keep field default (120s).
			try {
				int cfgAnalyzeTimeout = chatConfig.get("analyzeTimeout");
				if (cfgAnalyzeTimeout > 0) {
					analyzeTimeout = cfgAnalyzeTimeout;
				}
			} catch (Exception e) {
				// keep field default
			}

			/// OI-5/OI-83: Enforce minimum keyframeEvery when extractMemories is enabled.
			/// If keyframeEvery=0 but extractMemories=true, memories can never be created
			/// because the keyframe→memory pipeline requires keyframes to fire.
			boolean extractMemories = (boolean) chatConfig.get("extractMemories");
			if (extractMemories && keyFrameEvery <= 0) {
				logger.warn("extractMemories=true but keyframeEvery=0 — setting keyframeEvery to " + MIN_KEYFRAME_EVERY_WITH_EXTRACT + " to enable memory creation");
				keyFrameEvery = MIN_KEYFRAME_EVERY_WITH_EXTRACT;
			}
			else if (extractMemories && keyFrameEvery < MIN_KEYFRAME_EVERY_WITH_EXTRACT) {
				logger.warn("keyframeEvery=" + keyFrameEvery + " too low with extractMemories=true, raising to " + MIN_KEYFRAME_EVERY_WITH_EXTRACT);
				keyFrameEvery = MIN_KEYFRAME_EVERY_WITH_EXTRACT;
			}
		}
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getServerUrl() {
		return serverUrl;
	}

	public void setServerUrl(String serverUrl) {
		this.serverUrl = serverUrl;
	}

	public String getApiVersion() {
		return apiVersion;
	}

	public void setApiVersion(String apiVersion) {
		this.apiVersion = apiVersion;
	}

	public String getAuthorizationToken() {
		return authorizationToken;
	}

	public void setAuthorizationToken(String authorizationToken) {
		this.authorizationToken = authorizationToken;
	}

	public LLMServiceEnumType getServiceType() {
		return serviceType;
	}

	public void setServiceType(LLMServiceEnumType serviceType) {
		this.serviceType = serviceType;
	}

	public boolean isFormatOutput() {
		return formatOutput;
	}

	public void setFormatOutput(boolean formatOutput) {
		this.formatOutput = formatOutput;
	}

	public boolean isIncludeScene() {
		return includeScene;
	}

	public void setIncludeScene(boolean includeScene) {
		this.includeScene = includeScene;
	}

	public int getPruneSkip() {
		return pruneSkip;
	}

	public void setPruneSkip(int pruneSkip) {
		this.pruneSkip = pruneSkip;
	}

	private void setMode(boolean chat) {
		chatMode = chat;
		includeMessageHistory = chatMode;
		includeContextHistory = !chatMode;
	}

	public boolean isChatMode() {
		return chatMode;
	}

	public void setChatMode(boolean chatMode) {
		this.chatMode = chatMode;
	}

	public boolean isIncludeMessageHistory() {
		return includeMessageHistory;
	}

	public void setIncludeMessageHistory(boolean includeMessageHistory) {
		this.includeMessageHistory = includeMessageHistory;
	}

	public boolean isIncludeContextHistory() {
		return includeContextHistory;
	}

	public void setIncludeContextHistory(boolean includeContextHistory) {
		this.includeContextHistory = includeContextHistory;
	}

	public String getLlmSystemPrompt() {
		return llmSystemPrompt;
	}

	public void setLlmSystemPrompt(String llmSystemPrompt) {
		this.llmSystemPrompt = llmSystemPrompt;
	}

	public void chatConsole() {
		chatConsole(newRequest(chatConfig.get("model")));
	}

	public OpenAIResponse checkRemote(OpenAIRequest req, String message, boolean conversational) {
		OpenAIResponse oresp = null;
		if (deferRemote) {
			if (message != null && message.length() > 0) {
				newMessage(req, message);
			}
			/// Copy config for remote task, excluding apiKey (encrypted field loses vault metadata on copy).
			/// Re-set apiKey from the already-decrypted in-memory source.
			BaseRecord remoteCfg = chatConfig.copyRecord(new String[] { "apiVersion", "serviceType", "serverUrl", "model", "chatOptions" });
			try { remoteCfg.set("apiKey", authorizationToken); } catch (Exception e) { logger.warn("Could not set apiKey on remote config"); }
			BaseRecord task = OlioTaskAgent.createTaskRequest(req, remoteCfg);
			BaseRecord rtask = OlioTaskAgent.executeTask(task);
			if (rtask != null) {
				BaseRecord resp = rtask.get("taskModel");
				if (resp != null) {
					oresp = new OpenAIResponse(resp);
					if (conversational) {
						handleResponse(req, oresp, false);
						saveSession(req);
					}
				} else {
					logger.error("Task response was null");
				}
			}
		}
		return oresp;
	}
	
	public void continueChat(OpenAIRequest req, String message) {
		if (deferRemote) {
			checkRemote(req, message, true);
			return;
		}

		OpenAIResponse lastRep = null;
		LineAction act = checkAction(req, message);
		if (act == LineAction.BREAK || act == LineAction.CONTINUE || act == LineAction.SAVE_AND_CONTINUE) {
			if (act == LineAction.SAVE_AND_CONTINUE) {
				ChatUtil.saveSession(user, req);
				if (chatConfig != null) {
					ChatUtil.applyTags(user, chatConfig, req);
				}
				createNarrativeVector(user, req);
			}
			logger.info("Continue...");
			return;
		}

		/// Inject fresh memory context before user message (dynamic per-turn)
		if (chatConfig != null) {
			BaseRecord sysChar = chatConfig.get("systemCharacter");
			BaseRecord usrChar = chatConfig.get("userCharacter");
			String memCtx = retrieveRelevantMemories(sysChar, usrChar);
			if (memCtx != null && !memCtx.isEmpty()) {
				OpenAIMessage memMsg = new OpenAIMessage();
				memMsg.setRole(systemRole);
				memMsg.setContent(memCtx);
				req.addMessage(memMsg);
			}
		}

		if (message != null && message.length() > 0) {
			newMessage(req, message);
		}
		boolean stream = req.get("stream");
		
		if(!stream) {
			lastRep = chat(req);
			if (lastRep != null) {
				handleResponse(req, lastRep, false);
			} else {
				logger.warn("Last rep is null");
			}
			/// Phase 9: Post-response policy evaluation (buffer mode)
			PolicyEvaluationResult policyResult = evaluateResponsePolicy(req, lastRep);
			saveSession(req);

			/// Phase 13g: Auto-tune after policy violation (buffer mode)
			if (policyResult != null && !policyResult.isPermitted()) {
				handleAutotuning(req, policyResult);
			}

			/// Flush deferred keyframe AFTER main response completes (buffer mode).
			/// This prevents the keyframe LLM call from competing with the main response.
			flushPendingKeyframe(req);

			/// Phase 13g: Auto-generate title and icon after first real exchange (buffer mode)
			boolean autoTitle = chatConfig != null && (boolean) chatConfig.get("autoTitle");
			int offset = getMessageOffset(req);
			List<OpenAIMessage> allMsgs = req.getMessages();
			int userMsgCount = 0;
			for (int i = offset; i < allMsgs.size(); i++) {
				if (userRole.equals(allMsgs.get(i).getRole())) userMsgCount++;
			}
			logger.info("Auto-title check: autoTitle=" + autoTitle + " userMsgCount=" + userMsgCount);
			if (autoTitle && userMsgCount == 1) {
				String[] titleAndIcon = generateChatTitleAndIcon(req);
				String title = titleAndIcon[0];
				String icon = titleAndIcon[1];
				logger.info("Title generation result: title=" + title + " icon=" + icon);
				String oid = req.get(FieldNames.FIELD_OBJECT_ID);
				if (oid != null) {
					try {
						Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_REQUEST, FieldNames.FIELD_OBJECT_ID, oid);
						cq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
						BaseRecord chatReqRec = IOSystem.getActiveContext().getAccessPoint().find(user, cq);
						if (chatReqRec != null) {
							if (title != null) {
								setChatTitle(chatReqRec, title);
							}
							if (icon != null) {
								setChatIcon(chatReqRec, icon);
							}
						}
					} catch (Exception e) {
						logger.warn("Buffer mode title/icon persist failed: " + e.getMessage());
					}
				}
				/// Notify listener for real-time client update
				if (listener != null) {
					if (title != null) listener.onChatTitle(user, req, title);
					if (icon != null) listener.onChatIcon(user, req, icon);
				}
			}
		}
		else {

			logger.info("Defer to async processing");
			chat(req);
			
		}
	}

	/// Phase 9: Evaluate the LLM response against the chatConfig's policy.
	/// Uses the existing PolicyEvaluator pipeline via ResponsePolicyEvaluator.
	/// Also runs LLM-based compliance check when complianceCheck is enabled.
	/// Returns the evaluation result (or null if no policy configured).
	public PolicyEvaluationResult evaluateResponsePolicy(OpenAIRequest req, OpenAIResponse resp) {
		if (chatConfig == null || user == null) {
			logger.warn("evaluateResponsePolicy: chatConfig=" + (chatConfig != null) + " user=" + (user != null) + " — skipping");
			return null;
		}

		String responseContent = null;
		if (resp != null) {
			BaseRecord msg = resp.get("message");
			if (msg != null) {
				responseContent = msg.get("content");
			}
			if (responseContent == null) {
				List<BaseRecord> choices = resp.get("choices");
				if (choices != null && !choices.isEmpty()) {
					BaseRecord choice = choices.get(0);
					BaseRecord cmsg = choice.get("message");
					if (cmsg != null) {
						responseContent = cmsg.get("content");
					}
				}
			}
		}

		responseCount++;

		/// Heuristic policy evaluation (fast)
		PolicyEvaluationResult result = null;
		BaseRecord policyRef = chatConfig.get("policy");
		String policyTemplate = chatConfig.hasField("policyTemplate") ? chatConfig.get("policyTemplate") : null;
		boolean hasPolicyConfig = policyRef != null || (policyTemplate != null && !policyTemplate.isEmpty());
		logger.info("evaluateResponsePolicy: responseCount=" + responseCount
			+ " policyRef=" + (policyRef != null ? "loaded" : "NULL")
			+ " policyTemplate=" + (policyTemplate != null ? policyTemplate : "NULL")
			+ " responseLen=" + (responseContent != null ? responseContent.length() : 0)
			+ " listener=" + (listener != null));
		if (hasPolicyConfig) {
			if (listener != null) {
				listener.onEvalProgress(user, req, "policy", "Evaluating response policy: timeout, recursive loop, wrong character, refusal");
			}
			String reqOid = req != null ? req.get(FieldNames.FIELD_OBJECT_ID) : null;
			ResponsePolicyEvaluator rpe = new ResponsePolicyEvaluator();
			result = rpe.evaluate(user, responseContent, chatConfig, promptConfig, reqOid);
			if (listener != null) {
				listener.onEvalProgress(user, req, "policyDone", result != null && !result.isPermitted() ? result.getViolationSummary() : "passed");
			}
			if (result != null && !result.isPermitted()) {
				logger.warn("Policy violation detected: " + result.getViolationSummary());
			}
		}

		/// LLM-based compliance evaluation (async, runs every Nth response)
		boolean complianceEnabled = (boolean) chatConfig.get("complianceCheck");
		int complianceEvery = chatConfig.get("complianceCheckEvery");
		if (complianceEnabled && complianceEvery > 0 && responseCount % complianceEvery == 0 && responseContent != null) {
			if (listener != null) {
				listener.onEvalProgress(user, req, "compliance", "Running compliance check: character identity, gendered voice, profile adherence, age adherence, equal treatment, personality consistency");
			}
			final String content = responseContent;
			final PolicyEvaluationResult heuristicResult = result;
			CompletableFuture.runAsync(() -> {
				try {
					ResponseComplianceEvaluator rce = new ResponseComplianceEvaluator();
					List<PolicyViolation> complianceViolations = rce.evaluate(user, content, chatConfig);
					if (!complianceViolations.isEmpty()) {
						logger.warn("Compliance violations: " + complianceViolations.size());
						/// Merge into heuristic result if available, or create new result
						if (heuristicResult != null) {
							complianceViolations.forEach(v -> heuristicResult.addViolation(v.getRuleType(), v.getDetails()));
						}
						/// Notify listener of compliance violations
						if (listener != null) {
							StringBuilder summary = new StringBuilder();
							for (PolicyViolation v : complianceViolations) {
								if (summary.length() > 0) summary.append("; ");
								summary.append(v.getRuleType()).append(": ").append(v.getDetails());
							}
							listener.onAutotuneEvent(user, req, "complianceViolation", summary.toString());
						}
					}
					if (listener != null) {
						listener.onEvalProgress(user, req, "complianceDone", complianceViolations.isEmpty() ? "passed" : complianceViolations.size() + " violation(s)");
					}
				} catch (Exception e) {
					logger.warn("Compliance evaluation failed: " + e.getMessage());
					if (listener != null) {
						listener.onEvalProgress(user, req, "complianceDone", "error");
					}
				}
			});
		}

		return result;
	}

	/// Mid-stream policy evaluation — runs heuristic-only policy checks on partial response content.
	/// Called periodically from accumulateChunk() during streaming. Does NOT run LLM-based compliance.
	/// Returns the evaluation result if a violation is found, null if content passes or no policy configured.
	private PolicyEvaluationResult evaluateMidStreamPolicy(OpenAIRequest req, OpenAIResponse resp) {
		if (chatConfig == null || user == null) {
			return null;
		}
		BaseRecord policyRef = chatConfig.get("policy");
		String policyTmpl = chatConfig.hasField("policyTemplate") ? chatConfig.get("policyTemplate") : null;
		if (policyRef == null && (policyTmpl == null || policyTmpl.isEmpty())) {
			return null;
		}

		String responseContent = null;
		BaseRecord msg = resp.get("message");
		if (msg != null) {
			responseContent = msg.get("content");
		}
		if (responseContent == null || responseContent.isEmpty()) {
			return null;
		}

		String reqOid = req != null ? req.get(FieldNames.FIELD_OBJECT_ID) : null;
		ResponsePolicyEvaluator rpe = new ResponsePolicyEvaluator();
		PolicyEvaluationResult result = rpe.evaluate(user, responseContent, chatConfig, promptConfig, reqOid);
		return (result != null && !result.isPermitted()) ? result : null;
	}

	/// Accessor for mid-stream violation result — used by oncomplete to report pre-detected violations.
	public PolicyEvaluationResult getMidStreamViolation() {
		return midStreamViolation;
	}

	/// Phase 13g: Handle auto-tuning after a policy violation.
	/// Runs prompt analysis (autoTunePrompts) and/or options rebalancing (autoTuneChatOptions).
	/// Prompt analysis saves a NEW promptConfig variant — never overwrites the original.
	/// Options rebalancing updates the chatConfig chatOptions in place.
	public void handleAutotuning(OpenAIRequest req, PolicyEvaluationResult policyResult) {
		if (chatConfig == null || policyResult == null || policyResult.isPermitted()) {
			return;
		}
		List<PolicyViolation> violations = policyResult.getViolations();
		if (violations == null || violations.isEmpty()) {
			return;
		}

		boolean tunePrompts = (boolean) chatConfig.get("autoTunePrompts");
		boolean tuneOptions = (boolean) chatConfig.get("autoTuneChatOptions");

		if (tunePrompts && promptConfig != null) {
			CompletableFuture.runAsync(() -> {
				try {
					ChatAutotuner autotuner = new ChatAutotuner();
					AutotuneResult atResult = autotuner.autotune(user, chatConfig, promptConfig, violations);
					if (atResult.isSuccess()) {
						logger.info("Autotune prompt analysis complete: " + atResult.getAutotunedPromptName());
						if (listener != null) {
							listener.onAutotuneEvent(user, req, "promptSuggestion", atResult.getAnalysisResponse());
						}
					}
				} catch (Exception e) {
					logger.warn("Autotune prompt analysis failed: " + e.getMessage());
				}
			});
		}

		if (tuneOptions) {
			CompletableFuture.runAsync(() -> {
				try {
					rebalanceChatOptions(req, violations);
				} catch (Exception e) {
					logger.warn("Options rebalance failed: " + e.getMessage());
				}
			});
		}
	}

	/// Phase 13g: Rebalance chatOptions based on violation types.
	/// Applies adjustments directly to the chatConfig and persists.
	private void rebalanceChatOptions(OpenAIRequest req, List<PolicyViolation> violations) {
		BaseRecord opts = chatConfig.get("chatOptions");
		if (opts == null) {
			logger.warn("No chatOptions found on chatConfig for rebalancing");
			return;
		}

		boolean changed = false;
		StringBuilder adjustments = new StringBuilder();

		for (PolicyViolation v : violations) {
			String type = v.getRuleType();
			if (type == null) continue;

			if (type.contains("REFUSAL") || type.contains("refusal")) {
				double temp = opts.get("temperature");
				double topP = opts.get("top_p");
				if (temp > 0.3) {
					double newTemp = Math.round((temp - 0.1) * 100.0) / 100.0;
					opts.setValue("temperature", newTemp);
					adjustments.append("temperature: ").append(temp).append(" -> ").append(newTemp).append("; ");
					changed = true;
				}
				if (topP > 0.7) {
					double newTopP = Math.round((topP - 0.05) * 100.0) / 100.0;
					opts.setValue("top_p", newTopP);
					adjustments.append("top_p: ").append(topP).append(" -> ").append(newTopP).append("; ");
					changed = true;
				}
			}
			else if (type.contains("RECURSIVE") || type.contains("recursive") || type.contains("LOOP") || type.contains("loop")) {
				double freqPen = opts.get("frequency_penalty");
				double presPen = opts.get("presence_penalty");
				if (freqPen < 1.5) {
					double newFreq = Math.round((freqPen + 0.2) * 100.0) / 100.0;
					opts.setValue("frequency_penalty", newFreq);
					adjustments.append("frequency_penalty: ").append(freqPen).append(" -> ").append(newFreq).append("; ");
					changed = true;
				}
				if (presPen < 1.0) {
					double newPres = Math.round((presPen + 0.1) * 100.0) / 100.0;
					opts.setValue("presence_penalty", newPres);
					adjustments.append("presence_penalty: ").append(presPen).append(" -> ").append(newPres).append("; ");
					changed = true;
				}
			}
		}

		if (changed) {
			try {
				IOSystem.getActiveContext().getAccessPoint().update(user, chatConfig);
				logger.info("Chat options rebalanced: " + adjustments.toString());
				if (listener != null) {
					listener.onAutotuneEvent(user, req, "optionsRebalance", adjustments.toString());
				}
			} catch (Exception e) {
				logger.warn("Failed to persist rebalanced chatOptions: " + e.getMessage());
			}
		}
	}

	public void saveSession(OpenAIRequest req) {
		if(!persistSession) {
			return;
		}
		ChatUtil.saveSession(user, req);
		if (chatConfig != null) {
			ChatUtil.applyTags(user, chatConfig, req);
		}
		createNarrativeVector(user, req);
	}

	/// Phase 13g: Auto-generate a concise chat title and Material Symbols icon from the first exchange.
	/// Returns a String[] with [0]=title, [1]=icon. Either may be null on failure.
	public String[] generateChatTitleAndIcon(OpenAIRequest req) {
		int offset = getMessageOffset(req);
		List<OpenAIMessage> msgs = req.getMessages();
		if (msgs.size() < offset + 2) return new String[] { null, null };

		String userMsg = msgs.get(offset).getContent();
		String assistMsg = msgs.get(offset + 1).getContent();
		if (userMsg == null || assistMsg == null) return new String[] { null, null };

		if (userMsg.length() > 200) userMsg = userMsg.substring(0, 200) + "...";
		if (assistMsg.length() > 200) assistMsg = assistMsg.substring(0, 200) + "...";

		OpenAIRequest titleReq = new OpenAIRequest();
		titleReq.setModel(req.getModel());
		titleReq.setStream(false);

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		String titleSys = PromptResourceUtil.getLines(CHAT_OPS_RESOURCE, "titleSystem");
		sysMsg.setContent(titleSys != null ? titleSys : "Given a conversation, generate a short chat title (5-8 words max) and a Google Material Symbols icon name. Return exactly two lines.");
		titleReq.getMessages().add(sysMsg);

		OpenAIMessage userPrompt = new OpenAIMessage();
		userPrompt.setRole(userRole);
		String titleFmt = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "titleUserFormat");
		if (titleFmt != null) {
			titleFmt = PromptResourceUtil.replaceToken(titleFmt, "userMsg", userMsg);
			titleFmt = PromptResourceUtil.replaceToken(titleFmt, "assistMsg", assistMsg);
			userPrompt.setContent(titleFmt);
		} else {
			userPrompt.setContent("User said: " + userMsg + "\nAssistant replied: " + assistMsg);
		}
		titleReq.getMessages().add(userPrompt);

		String title = null;
		String icon = null;
		try {
			OpenAIResponse resp = chat(titleReq);
			if (resp != null) {
				String content = null;
				BaseRecord msg = resp.get("message");
				if (msg != null) content = msg.get("content");
				if (content == null) {
					List<BaseRecord> choices = resp.get("choices");
					if (choices != null && !choices.isEmpty()) {
						BaseRecord cmsg = choices.get(0).get("message");
						if (cmsg != null) content = cmsg.get("content");
					}
				}
				if (content != null) {
					content = content.trim();
					String[] lines = content.split("\\r?\\n");
					if (lines.length >= 1) {
						title = lines[0].trim().replaceAll("^\"|\"$", "");
						if (title.length() > 60) title = title.substring(0, 60);
					}
					if (lines.length >= 2) {
						icon = lines[1].trim().toLowerCase().replaceAll("[^a-z0-9_]", "");
						if (icon.isEmpty()) icon = null;
					}
				}
			}
		} catch (Exception e) {
			logger.warn("Title/icon generation failed: " + e.getMessage());
		}
		return new String[] { title, icon };
	}

	/// Phase 13: Backward-compatible wrapper — returns only the title.
	public String generateChatTitle(OpenAIRequest req) {
		return generateChatTitleAndIcon(req)[0];
	}

	/// Phase 13: Set the chatTitle attribute on a chatRequest record and persist.
	public void setChatTitle(BaseRecord chatRequest, String title) {
		try {
			AttributeUtil.addAttribute(chatRequest, "chatTitle", title);
			IOSystem.getActiveContext().getAccessPoint().update(user, chatRequest);
			logger.info("Chat title set: " + title);
		} catch (Exception e) {
			logger.warn("Failed to set chatTitle attribute: " + e.getMessage());
		}
	}

	/// Phase 13g: Set the chatIcon attribute on a chatRequest record and persist.
	public void setChatIcon(BaseRecord chatRequest, String icon) {
		try {
			AttributeUtil.addAttribute(chatRequest, "chatIcon", icon);
			IOSystem.getActiveContext().getAccessPoint().update(user, chatRequest);
			logger.info("Chat icon set: " + icon);
		} catch (Exception e) {
			logger.warn("Failed to set chatIcon attribute: " + e.getMessage());
		}
	}

	private String getNarrativeForVector(OpenAIMessage msg) {

		if (chatConfig == null) {
			return msg.getContent();
		}

		BaseRecord vchar = chatConfig.get("systemCharacter");
		if (userRole.equals(msg.getRole())) {
			vchar = chatConfig.get("userCharacter");
		}
		String ujobDesc = "";
		if (vchar != null) {
			List<String> utrades = vchar.get(OlioFieldNames.FIELD_TRADES);
			if (utrades.size() > 0) {
				ujobDesc = " " + utrades.get(0).toLowerCase();
			}
		}
		return ("* " + (vchar == null ? msg.getRole()
				: vchar.get(FieldNames.FIELD_FIRST_NAME) + " (" + vchar.get(FieldNames.FIELD_AGE) + " year-old "
						+ NarrativeUtil.getRaceDescription(vchar.get(OlioFieldNames.FIELD_RACE)) + " "
						+ vchar.get(FieldNames.FIELD_GENDER) + ujobDesc + ")")
				+ " *: " + msg.getContent());

	}

	public int getMessageOffset() {
		int idx = 1;
		if (chatConfig != null) {
			boolean useAssist = chatConfig.get("assist");
			idx = (useAssist ? 3 : 2);
		}
		return idx;
	}

	/// Overload that checks the actual session messages to detect when the
	/// user-consent template resolved to empty (e.g. rating "E" with assist=true).
	/// In that case the session has only system + assistant-template = 2 base messages.
	public int getMessageOffset(OpenAIRequest req) {
		int idx = getMessageOffset();
		if (idx == 3 && req != null && req.getMessages().size() > 1
				&& assistantRole.equals(req.getMessages().get(1).getRole())) {
			idx = 2;
		}
		return idx;
	}

	private List<BaseRecord> createNarrativeVector(BaseRecord user, OpenAIRequest req) {
		List<BaseRecord> vect = new ArrayList<>();
		int rmc = req.getMessages().size();

		if (VectorUtil.isVectorSupported() && rmc > 2) {
			int idx = getMessageOffset(req);
			List<String> buff = new ArrayList<>();
			for (int i = idx; i < rmc; i++) {
				buff.add(getNarrativeForVector(req.getMessages().get(i)));
			}
			String cnt = buff.stream().collect(Collectors.joining(System.lineSeparator()));
			try {

				int del = IOSystem.getActiveContext().getVectorUtil().deleteVectorStore(req,
						OlioModelNames.MODEL_VECTOR_CHAT_HISTORY);
				if (del > 0) {
					logger.info("Cleaned up previous store with " + del + " chunks");
				}
				ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_VECTOR_REFERENCE, req);
				plist.parameter(FieldNames.FIELD_CHUNK, ChunkEnumType.WORD);
				plist.parameter(FieldNames.FIELD_CHUNK_COUNT, 1000);
				plist.parameter("chatConfig", chatConfig);
				plist.parameter("content", cnt);
				plist.parameter("promptConfig", promptConfig);
				plist.parameter("systemCharacter", chatConfig.get("systemCharacter"));
				plist.parameter("userCharacter", chatConfig.get("userCharacter"));
				BaseRecord vlist = IOSystem.getActiveContext().getFactory()
						.newInstance(OlioModelNames.MODEL_VECTOR_CHAT_HISTORY_LIST, user, null, plist);
				vect = vlist.get(FieldNames.FIELD_VECTORS);

				if (vect.size() > 0) {
					IOSystem.getActiveContext().getWriter().write(vect.toArray(new BaseRecord[0]));
				}
			} catch (WriterException | FactoryException e) {
				logger.error(e);
			}
		}
		return vect;
	}

	public OpenAIRequest getAnalyzePrompt(OpenAIRequest req, String command, int offset, int count, boolean full) {
		List<String> lines = ChatUtil.getFormattedChatHistory(req, chatConfig, pruneSkip, full);
		if (count == 0) {
			count = lines.size();
		}
		int max = Math.min(offset + count, lines.size());
		if (lines.size() == 0 || offset >= lines.size()) {
			return null;
		}

		String assistantAnalyze = PromptUtil.getAssistantAnalyzeTemplate(promptConfig, chatConfig);
		String systemAnalyze = PromptUtil.getSystemAnalyzeTemplate(promptConfig, chatConfig);
		String userAnalyze = PromptUtil.getUserAnalyzeTemplate(promptConfig, chatConfig);
		if (command == null || command.length() == 0) {
			if (userAnalyze != null && userAnalyze.length() > 0) {
				command = userAnalyze;
			} else {
				String defaultCmd = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "analyzeUserDefault");
				command = defaultCmd != null ? defaultCmd : "Summarize the following chat history.";
			}
		}

		OpenAIRequest areq = new OpenAIRequest();
		applyAnalyzeOptions(req, areq);

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		String sys = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "analyzeSystem");
		if (sys == null) {
			sys = "You are an objective and introspective analyst. You create succinct, accurate and objective plot and text summaries.";
		}
		if (systemAnalyze != null && systemAnalyze.length() > 0) {
			sys = systemAnalyze;
		}

		sysMsg.setContent(sys);
		areq.addMessage(sysMsg);

		if (assistantAnalyze != null && assistantAnalyze.length() > 0) {
			OpenAIMessage aaMsg = new OpenAIMessage();
			aaMsg.setRole(assistantRole);
			aaMsg.setContent(assistantAnalyze);
			areq.addMessage(aaMsg);
		}

		StringBuilder msg = new StringBuilder();

		String instrHeader = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "analyzeInstructionHeader");
		String instrBody = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "analyzeInstructionBody");
		String instrFooter = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "analyzeInstructionFooter");
		msg.append(instrHeader != null ? instrHeader : "--- ANALYSIS INSTRUCTIONS ---").append(System.lineSeparator());
		msg.append(instrBody != null ? instrBody : "Use the following content to create a response for the request: ").append(command);

		msg.append(lines.subList(offset, max).stream().collect(Collectors.joining(System.lineSeparator()))
				+ System.lineSeparator());
		msg.append(instrFooter != null ? instrFooter : "--- END CONTENT FOR ANALYSIS ---").append(System.lineSeparator());
		msg.append(command);
		String cont = msg.toString();
		boolean useJB = chatConfig.get("useJailBreak");
		if (useJB || forceJailbreak) {
			String jbt = PromptUtil.getJailBreakTemplate(promptConfig);
			if (jbt != null && jbt.length() > 0) {
				cont = embeddedMessage.matcher(jbt).replaceAll(msg.toString());
			}
		}

		OpenAIMessage anMsg = new OpenAIMessage();
		anMsg.setRole(userRole);
		anMsg.setContent(cont);
		areq.addMessage(anMsg);
		// logger.info(areq.toFullString());
		return areq;
	}

	/// Phase 12: OI-30 — Analyze operations intentionally use conservative settings for
	/// deterministic output (summaries, keyframes, narration). These override chatOptions values.
	public static final double ANALYZE_TEMPERATURE = 0.4;
	public static final double ANALYZE_TOP_P = 0.5;
	public static final double ANALYZE_FREQUENCY_PENALTY = 0.0;
	public static final double ANALYZE_PRESENCE_PENALTY = 0.0;
	public static final int ANALYZE_NUM_CTX = 8192;

	private void applyAnalyzeOptions(OpenAIRequest req, OpenAIRequest areq) {
		String amodel = chatConfig.get("analyzeModel");
		if (amodel == null) {
			amodel = req.getModel();
		}
		areq.setModel(amodel);
		applyChatOptions(areq);
		double temperature = ANALYZE_TEMPERATURE;
		double top_p = ANALYZE_TOP_P;
		double frequency_penalty = ANALYZE_FREQUENCY_PENALTY;
		double presence_penalty = ANALYZE_PRESENCE_PENALTY;
		int num_ctx = ANALYZE_NUM_CTX;
		try {
			areq.set("temperature", temperature);
			areq.set("top_p", top_p);
			areq.set("frequency_penalty", frequency_penalty);
			areq.set("presence_penalty", presence_penalty);
			String tokField = ChatUtil.getMaxTokenField(chatConfig);
			if(tokField != null && tokField.length() > 0) {
				areq.set(tokField, num_ctx);
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

	}

	private static Pattern embeddedMessage = Pattern.compile("\\$\\{embmsg\\}");

	public OpenAIRequest getNarratePrompt(OpenAIRequest req, String command, int offset, int count, boolean full) {
		List<String> lines = ChatUtil.getFormattedChatHistory(req, chatConfig, pruneSkip, full);
		if (count == 0) {
			count = lines.size();
		}
		int max = Math.min(offset + count, lines.size());
		if (lines.size() == 0 || offset >= lines.size()) {
			logger.info("There is no chat history to to narrate");
			return null;
		}

		String assistantNarrate = PromptUtil.getAssistantNarrateTemplate(promptConfig, chatConfig);
		String systemNarrate = PromptUtil.getSystemNarrateTemplate(promptConfig, chatConfig);
		String userNarrate = PromptUtil.getUserNarrateTemplate(promptConfig, chatConfig);
		if (command == null || command.length() == 0) {
			if (userNarrate != null && userNarrate.length() > 0) {
				command = userNarrate;
			} else {
				String defaultCmd = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "narrateUserDefault");
				command = defaultCmd != null ? defaultCmd : "Summarize the following chat history.";
			}
		}

		OpenAIRequest areq = new OpenAIRequest();
		applyAnalyzeOptions(req, areq);
		/// Phase 12: Removed redundant applyChatOptions(areq) — applyAnalyzeOptions already calls it (OI-31/OI-34)

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		String sys = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "analyzeSystem");
		if (sys == null) {
			sys = "You are an objective and introspective analyst. You create succinct, accurate and objective plot and text summaries.";
		}
		if (systemNarrate != null && systemNarrate.length() > 0) {
			sys = systemNarrate;
		}
		sysMsg.setContent(sys);
		areq.addMessage(sysMsg);

		if (assistantNarrate != null) {
			OpenAIMessage aaMsg = new OpenAIMessage();
			aaMsg.setRole(assistantRole);
			aaMsg.setContent(assistantNarrate);
			areq.addMessage(aaMsg);
		}

		StringBuilder msg = new StringBuilder();
		msg.append(command + System.lineSeparator());
		msg.append(lines.subList(offset, max).stream().collect(Collectors.joining(System.lineSeparator()))
				+ System.lineSeparator());

		String cont = msg.toString();
		boolean useJB = chatConfig.get("useJailBreak");
		if (useJB || forceJailbreak) {
			String jbt = PromptUtil.getJailBreakTemplate(promptConfig);
			if (jbt != null && jbt.length() > 0) {
				cont = embeddedMessage.matcher(jbt).replaceAll(cont);
			}
		}

		OpenAIMessage anMsg = new OpenAIMessage();
		anMsg.setRole(userRole);
		anMsg.setContent(cont);
		areq.addMessage(anMsg);
		return areq;
	}

	public OpenAIRequest getSDPrompt(OpenAIRequest req, String command, boolean full) {
		List<String> lines = ChatUtil.getFormattedChatHistory(req, chatConfig, pruneSkip, full);

		String systemSD = PromptUtil.getSystemSDTemplate(promptConfig, chatConfig);
		String defaultSD = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "sdCommandDefault");
		command = defaultSD != null ? defaultSD : "Create an SD prompt based on the most recent roleplay scene.";

		OpenAIRequest areq = new OpenAIRequest();
		String amodel = chatConfig.get("analyzeModel");
		if (amodel == null) {
			amodel = req.getModel();
		}
		areq.setModel(amodel);

		applyChatOptions(areq);

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		sysMsg.setContent(systemSD);
		areq.addMessage(sysMsg);

		StringBuilder msg = new StringBuilder();
		msg.append(command + System.lineSeparator());
		msg.append(lines.stream().collect(Collectors.joining(System.lineSeparator())));

		OpenAIMessage anMsg = new OpenAIMessage();
		anMsg.setRole(userRole);
		anMsg.setContent(msg.toString());
		areq.addMessage(anMsg);

		return areq;
	}

	public String SDPrompt(OpenAIRequest req, String command, boolean full) {
		List<String> resp = new ArrayList<>();
		OpenAIRequest oreq = getSDPrompt(req, command, full);

		logger.info("Creating SD Prompt ... ");
		OpenAIResponse oresp = chat(oreq);
		if (oresp == null || oresp.getMessage() == null) {
			logger.error("Unexpected response");
			if (oresp != null) {
				logger.error(oresp.toFullString());
			}
			return null;
		}
		return oresp.getMessage().getContent();
	}

	/// Generate a contextual scene image prompt from the current chat.
	/// Returns a ScenePromptResult with the composite SD prompt, negative prompt,
	/// and portrait bytes for IP-Adapter face references. Returns null on failure.
	public ScenePromptResult generateScenePrompt(OpenAIRequest req) {
		if (chatConfig == null) {
			logger.warn("generateScenePrompt: chatConfig is null");
			return null;
		}

		BaseRecord systemChar = chatConfig.get("systemCharacter");
		BaseRecord userChar = chatConfig.get("userCharacter");
		if (systemChar == null || userChar == null) {
			logger.warn("generateScenePrompt: systemCharacter or userCharacter is null");
			return null;
		}

		/// Step 1: Use LLM to generate a scene description from recent chat
		String sceneDesc = generateSceneDescription(req);
		if (sceneDesc == null || sceneDesc.isBlank()) {
			sceneDesc = "two people facing each other";
		}
		logger.info("Scene description: " + sceneDesc);

		/// Step 2: Get SD-optimized character descriptions via PersonalityProfile
		PersonalityProfile sysPP = ProfileUtil.getProfile(null, systemChar);
		PersonalityProfile usrPP = ProfileUtil.getProfile(null, userChar);
		String sysDesc = sysPP != null ? NarrativeUtil.getSDMinPrompt(sysPP) : systemChar.get("firstName");
		String usrDesc = usrPP != null ? NarrativeUtil.getSDMinPrompt(usrPP) : userChar.get("firstName");

		/// Step 3: Get setting/terrain from chatConfig
		String setting = null;
		try { setting = chatConfig.get("setting"); } catch (Exception e) { /* ignore */ }
		String terrain = null;
		try { terrain = chatConfig.get("terrain"); } catch (Exception e) { /* ignore */ }
		String settingDesc = "";
		if (setting != null && !setting.isEmpty() && !"random".equalsIgnoreCase(setting)) {
			settingDesc = setting;
		} else if (terrain != null && !terrain.isEmpty()) {
			settingDesc = terrain;
		}

		/// Step 4: Assemble composite prompt with IP-Adapter image tags
		StringBuilder prompt = new StringBuilder();
		prompt.append("8k highly detailed ((highest quality)) ((ultra realistic)) ");
		prompt.append(sceneDesc);
		prompt.append(", ").append(sysDesc).append(" on the left");
		// Add IP-Adapter tag for system character if portrait available
		byte[] sysPortraitBytes = getPortraitBytes(systemChar);
		if (sysPortraitBytes != null) {
			prompt.append(" <image:sysPortrait>");
		}
		prompt.append(", ").append(usrDesc).append(" on the right");
		byte[] usrPortraitBytes = getPortraitBytes(userChar);
		if (usrPortraitBytes != null) {
			prompt.append(" <image:userPortrait>");
		}
		if (!settingDesc.isEmpty()) {
			prompt.append(", ").append(settingDesc);
		}

		/// Step 5: Build negative prompt
		String negPrompt = "text, watermark, signature, blurry, bad anatomy, extra limbs, deformed, disfigured, duplicate, error";
		String sysNeg = NarrativeUtil.getSDNegativePrompt(systemChar);
		if (sysNeg != null && !sysNeg.isEmpty()) negPrompt = sysNeg;

		ScenePromptResult result = new ScenePromptResult();
		result.prompt = prompt.toString();
		result.negativePrompt = negPrompt;
		result.sysPortraitBytes = sysPortraitBytes;
		result.usrPortraitBytes = usrPortraitBytes;
		result.label = systemChar.get("firstName") + " and " + userChar.get("firstName");
		logger.info("Scene prompt assembled: " + result.prompt.substring(0, Math.min(200, result.prompt.length())) + "...");
		return result;
	}

	/// LLM call to generate a concise scene description from recent chat history.
	private String generateSceneDescription(OpenAIRequest req) {
		List<String> lines = ChatUtil.getFormattedChatHistory(req, chatConfig, pruneSkip, false);
		if (lines.isEmpty()) return null;

		String sys = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "sceneSystem");
		if (sys == null) sys = "You generate concise Stable Diffusion scene descriptions. Output ONLY the description. Limit to 50 words.";
		String cmd = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "sceneUser");
		if (cmd == null) cmd = "Describe the current scene for an image based on the most recent exchange.";

		OpenAIRequest sceneReq = new OpenAIRequest();
		String amodel = chatConfig.get("analyzeModel");
		if (amodel == null || amodel.isEmpty()) amodel = chatConfig.get("model");
		sceneReq.setModel(amodel);
		sceneReq.setStream(false);
		applyAnalyzeOptions(req, sceneReq);
		try { sceneReq.set("temperature", 0.4); } catch (Exception e) { /* ignore */ }
		String tokField = ChatUtil.getMaxTokenField(chatConfig);
		try { sceneReq.set(tokField, 256); } catch (Exception e) { /* ignore */ }

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		sysMsg.setContent(sys);
		sceneReq.addMessage(sysMsg);

		OpenAIMessage usrMsg = new OpenAIMessage();
		usrMsg.setRole(userRole);
		usrMsg.setContent(cmd + System.lineSeparator() + lines.stream().collect(Collectors.joining(System.lineSeparator())));
		sceneReq.addMessage(usrMsg);

		analyzeTimeoutOverride.set(analyzeTimeout);
		try {
			OpenAIResponse resp = chat(sceneReq);
			if (resp != null && resp.getMessage() != null) {
				return resp.getMessage().getContent();
			}
		} catch (Exception e) {
			logger.warn("Scene description generation failed: " + e.getMessage());
		} finally {
			analyzeTimeoutOverride.remove();
		}
		return null;
	}

	/// Load portrait image bytes from a character's profile.portrait field.
	private byte[] getPortraitBytes(BaseRecord character) {
		try {
			BaseRecord profile = character.get("profile");
			if (profile == null) return null;
			BaseRecord portrait = profile.get("portrait");
			if (portrait == null) return null;
			byte[] bytes = portrait.get(FieldNames.FIELD_BYTE_STORE);
			if (bytes != null && bytes.length > 0) return bytes;
		} catch (Exception e) {
			logger.debug("Could not load portrait for " + character.get("firstName") + ": " + e.getMessage());
		}
		return null;
	}

	/// Result container for scene prompt generation.
	public static class ScenePromptResult {
		public String prompt;
		public String negativePrompt;
		public byte[] sysPortraitBytes;
		public byte[] usrPortraitBytes;
		public String label;
	}

	public String analyze(OpenAIRequest req, String command, boolean narrate, boolean reduce, boolean full) {
		List<String> resp = new ArrayList<>();
		int offset = 0;
		int count = (reduce ? 20 : 0);
		OpenAIRequest oreq = null;
		if (narrate) {
			oreq = getNarratePrompt(req, command, offset, count, full);
		} else {
			oreq = getAnalyzePrompt(req, command, offset, count, full);
		}
		String lbl = "Analyzing";
		if (narrate) {
			lbl = "Narrating";
		} else if (reduce) {
			lbl = "Reducing";
		}
		while (oreq != null) {
			logger.info(lbl + " ... " + offset);
			OpenAIResponse oresp = null;
			if (deferRemote) {
				oresp = checkRemote(oreq, null, false);
			} else {
				oresp = chat(oreq);
			}
			if (oresp == null || oresp.getMessage() == null) {
				logger.error("Unexpected response");
				if (oresp != null) {
					logger.error(oresp.toFullString());
				}

				break;
			}
			resp.add(oresp.getMessage().getContent());
			if (!reduce || count == 0) {
				break;
			}
			offset += count;
			oreq = getAnalyzePrompt(req, command, offset, count, full);
		}
		if (reduce) {
			return reduce(req, resp);
		}
		return resp.stream().collect(Collectors.joining(System.lineSeparator()));
	}

	public OpenAIRequest getReducePrompt(OpenAIRequest req, String text) {
		String systemAnalyze = PromptUtil.getSystemAnalyzeTemplate(promptConfig, chatConfig);
		String assistantAnalyze = PromptUtil.getAssistantAnalyzeTemplate(promptConfig, chatConfig);
		String userAnalyze = PromptUtil.getUserReduceTemplate(promptConfig, chatConfig);
		if (userAnalyze == null) {
			String defaultCmd = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "reduceUserDefault");
			userAnalyze = defaultCmd != null ? defaultCmd : "Merge and reduce the following summaries.";
		}
		OpenAIRequest areq = new OpenAIRequest();
		areq.setModel(req.getModel());

		applyChatOptions(areq);

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		String sys = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "analyzeSystem");
		if (sys == null) {
			sys = "You are an objective and introspective analyst. You create succinct, accurate and objective plot and text summaries.";
		}
		if (systemAnalyze != null && systemAnalyze.length() > 0) {
			sys = systemAnalyze;
		}
		sysMsg.setContent(sys);
		areq.addMessage(sysMsg);

		if (assistantAnalyze != null) {
			OpenAIMessage aaMsg = new OpenAIMessage();
			aaMsg.setRole(assistantRole);
			aaMsg.setContent(assistantAnalyze);
			areq.addMessage(aaMsg);
		}

		StringBuilder msg = new StringBuilder();
		msg.append(userAnalyze + System.lineSeparator());
		msg.append(text);

		OpenAIMessage anMsg = new OpenAIMessage();
		anMsg.setRole(userRole);
		anMsg.setContent(msg.toString());
		areq.addMessage(anMsg);

		return areq;
	}

	public String reduce(OpenAIRequest req, List<String> summaries) {

		int size = summaries.size();
		if (size == 0) {
			logger.warn("No summaries to reduce");
			return null;
		}
		int count = 1;
		if (size > 1) {
			count = Math.max(2, size / 5);
		}
		List<String> rsum = new ArrayList<>();

		if (size > 1) {
			for (int i = 0; i < size; i += count) {
				logger.info("Reducing ... " + i + " of " + summaries.size());
				String sumBlock = summaries.subList(i, Math.min(size, i + count)).stream()
						.map(s -> "(Analysis Segment)" + System.lineSeparator() + s)
						.collect(Collectors.joining(System.lineSeparator()));
				OpenAIRequest rreq = getReducePrompt(req, sumBlock);
				OpenAIResponse oresp = chat(rreq);
				if (oresp == null || oresp.getMessage() == null) {
					logger.warn("Invalid response");
					break;
				}
				rsum.add(oresp.getMessage().getContent());
			}
		} else {
			rsum.add(summaries.get(0));
		}
		String summary = null;
		if (rsum.size() > 1) {
			logger.info("Summarizing ... " + rsum.size());
			String sumBlock = rsum.stream().collect(Collectors.joining(System.lineSeparator()));
			OpenAIResponse oresp = chat(getReducePrompt(req, sumBlock));
			if (oresp == null || oresp.getMessage() == null) {
				logger.warn("Invalid response");
			} else {
				summary = oresp.getMessage().getContent();
			}
		} else {
			summary = rsum.stream().collect(Collectors.joining(System.lineSeparator()));
		}
		return summary;
	}

	enum LineAction {
		UNKNOWN, CONTINUE, SAVE_AND_CONTINUE, BREAK
	}

	private LineAction checkAction(OpenAIRequest req, String line) {
		LineAction oact = LineAction.UNKNOWN;
		if (line == null || line.equalsIgnoreCase("/bye")) {
			return LineAction.BREAK;
		}

		else if (line.startsWith("/jailbreak")) {
			forceJailbreak = !forceJailbreak;
			logger.info("Jailbreak " + (forceJailbreak ? "enabled" : "disabled"));
			oact = LineAction.CONTINUE;
		} else if (line.startsWith("/analyzeAll")) {
			logger.info(analyze(req, line.substring(11).trim(), false, false, true));
			oact = LineAction.CONTINUE;
		} else if (line.startsWith("/analyze")) {
			logger.info(analyze(req, line.substring(8).trim(), false, false, false));
			oact = LineAction.CONTINUE;
		} else if (line.startsWith("/reduceAll")) {
			logger.info(analyze(req, line.substring(10).trim(), false, true, true));
			oact = LineAction.CONTINUE;
		} else if (line.startsWith("/reduce")) {
			logger.info(analyze(req, line.substring(7).trim(), false, true, false));
			oact = LineAction.CONTINUE;
		} else if (line.startsWith("/narrateAll")) {
			logger.info(analyze(req, line.substring(11).trim(), true, false, true));
			oact = LineAction.CONTINUE;
		} else if (line.startsWith("/narrate")) {
			logger.info(analyze(req, line.substring(8).trim(), true, false, false));
			oact = LineAction.CONTINUE;
		} else if (line.startsWith("/sdprompt")) {
			logger.error("Feature currently disabled");
			oact = LineAction.CONTINUE;
		} else if (line.equals("/look")) {

			String char1 = NarrativeUtil.describe(null, chatConfig.get("systemCharacter"));
			String char2 = NarrativeUtil.describe(null, chatConfig.get("userCharacter"));
			logger.info("Character 1: " + char1);
			logger.info("Character 2: " + char2);
			if (req != null && req.getMessages().size() > 3) {
				OpenAIResponse oresp = chat(getNarratePrompt(req,
						"Write a brief narrative description of the following two characters. Include all physical, behavioral, and personality details."
								+ System.lineSeparator() + char1 + System.lineSeparator() + char2,
						0, 0, false));
				if (oresp != null && oresp.getMessage() != null) {
					logger.info(oresp.getMessage().getContent());
				}
			}
			oact = LineAction.CONTINUE;
		} else if (line.equals("/story")) {
			String snar = chatConfig.get("systemNarrative.sceneDescription");
			String unar = chatConfig.get("userNarrative.sceneDescription");
			if (snar != null && unar != null) {
				logger.info(snar);
				logger.info(unar);
			}
			oact = LineAction.CONTINUE;
		} else if (line.equals("/next")) {
			BaseRecord nextEp = PromptUtil.moveToNextEpisode(chatConfig);
			if (req.getMessages().size() == 0) {
				logger.error("No system message to replace!");
			} else if (nextEp != null) {
				if (req.getMessages().size() > 4) {
					logger.info("Summarizing current episode...");
					OpenAIResponse oresp = chat(getNarratePrompt(req,
							"Write a brief narrative description of the following content with respect to the described episode. Include all physical, behavioral, and personality details.",
							0, 0, false));
					String summary = null;
					if (oresp != null && oresp.getMessage() != null) {
						summary = oresp.getMessage().getContent();
						logger.info("Summary: " + summary);
					}
					nextEp.setValue("summary", summary);
				}
				IOSystem.getActiveContext().getAccessPoint().update(user, chatConfig.copyRecord(new String[] {
						FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_GROUP_ID, "episodes" }));
				String newSys = PromptUtil.getSystemChatPromptTemplate(promptConfig, chatConfig);
				req.getMessages().get(0).setContent(newSys);
				logger.info("Begin episode #" + nextEp.get("number") + " " + nextEp.get("theme"));
			} else {
				logger.warn("No further episodes");
			}
			oact = LineAction.SAVE_AND_CONTINUE;

		} else if (line.equals("/prune")) {
			pruneCount(req, messageTrim);
			oact = LineAction.CONTINUE;
		} else if (line.equals("/prompt")) {
			if (line.length() > 7) {
				String newPrompt = line.substring(8).trim();
				req.getMessages().get(0).setContent(newPrompt);
				logger.info("New prompt: " + newPrompt);
			} else {
				logger.info("Current prompt: " + req.getMessages().get(0).getContent());
			}
			oact = LineAction.CONTINUE;
		}

		return oact;
	}

	public void chatConsole(OpenAIRequest req) {
		BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
		try {
			AuditUtil.setLogToConsole(false);
			String prompt = "> ";
			String line = "";
			OpenAIResponse lastRep = null;
			while (line != null && line.equalsIgnoreCase("/quit") == false && line.equalsIgnoreCase("/exit") == false
					&& line.equalsIgnoreCase("/bye") == false) {
				if (lastRep == null && req.getMessages().size() > 0) {
					logger.info("Initializing ...");
					String iscene = chatConfig.get("userNarrative.interactionDescription");
					String cscene = chatConfig.get("scene");
					if (cscene == null) {
						cscene = iscene;
					}
					if (cscene != null) {
						logger.info(cscene);
					} else if (!"random".equals(chatConfig.get("setting"))) {
						logger.info((String) chatConfig.get("setting"));
					}
					BaseRecord nextEp = PromptUtil.getNextEpisode(chatConfig);
					if (nextEp != null) {
						logger.info("Episode #" + nextEp.get("number") + " " + nextEp.get("theme"));
					}
					lastRep = chat(req);
					if (lastRep != null) {
						handleResponse(req, lastRep, true);
					} else {
						logger.warn("Null response");
					}
				}
				System.out.print(prompt);
				line = is.readLine();

				LineAction act = checkAction(req, line);
				if (act == LineAction.BREAK) {
					break;
				}
				if (act == LineAction.CONTINUE || act == LineAction.SAVE_AND_CONTINUE) {
					continue;
				}

				if (line.equals("/new")) {
					req = newRequest(chatConfig.get("model"));
					continue;
				}

				if (line.equals("/truncate")) {
					lastRep = null;
					boolean useAssist = chatConfig.get("assist");
					req.setMessages(req.getMessages().subList(0, useAssist ? 3 : 2));
					continue;
				}

				if (line.equals("/save")) {
					System.out.println("Saving ...");
					FileUtil.emitFile("./" + saveName, JSONUtil.exportObject(req));
					continue;
				}
				if (line.equals("/load")) {
					System.out.println("Loading ...");
					String sav = FileUtil.getFileAsString("./" + saveName);
					if (sav != null && sav.length() > 0) {
						req = OpenAIRequest.importRecord(sav);
					}
					continue;
				}

				newMessage(req, line);

				lastRep = chat(req);
				if (lastRep != null) {
					handleResponse(req, lastRep, true);
				}

				ChatUtil.saveSession(user, req);
				if (chatConfig != null) {
					ChatUtil.applyTags(user, chatConfig, req);
				}
				createNarrativeVector(user, req);

			}
			AuditUtil.setLogToConsole(true);
			is.close();
		} catch (IOException e) {
			logger.error(e.getMessage());
		}
	}

	/// Keyframe-specific max tokens — much lower than generic analyze (8192).
	/// Keyframes only need ~150 words / ~300 tokens.
	public static final int KEYFRAME_MAX_TOKENS = 1024;

	/// Build a focused keyframe analysis request with condensed prompts and low token cap.
	private OpenAIRequest buildKeyframeRequest(OpenAIRequest snapshotReq) {
		List<String> lines = ChatUtil.getFormattedChatHistory(snapshotReq, chatConfig, pruneSkip, false);
		if (lines.isEmpty()) return null;

		String sys = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "keyframeSystem");
		if (sys == null) sys = "You produce brief, factual conversation summaries. Limit to 150 words.";
		String cmd = PromptResourceUtil.getString(CHAT_OPS_RESOURCE, "keyframeUser");
		if (cmd == null) cmd = "Summarize this conversation segment: who, what happened, key emotional beats, unresolved tensions.";

		OpenAIRequest kfReq = new OpenAIRequest();
		applyAnalyzeOptions(snapshotReq, kfReq);

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		sysMsg.setContent(sys);
		kfReq.addMessage(sysMsg);

		StringBuilder body = new StringBuilder();
		body.append(cmd).append(System.lineSeparator());
		body.append(lines.stream().collect(Collectors.joining(System.lineSeparator())));
		OpenAIMessage userMsg = new OpenAIMessage();
		userMsg.setRole(userRole);
		userMsg.setContent(body.toString());
		kfReq.addMessage(userMsg);

		/// Override max tokens to keyframe cap
		try {
			String tokField = ChatUtil.getMaxTokenField(chatConfig);
			if (tokField != null && !tokField.isEmpty()) {
				kfReq.set(tokField, KEYFRAME_MAX_TOKENS);
			}
		} catch (Exception e) {
			logger.warn("Failed to set keyframe max tokens", e);
		}

		return kfReq;
	}

	/// Phase 14: Async keyframe pipeline (OI-86, OI-87).
	/// Runs on a background thread. Uses the snapshot for analysis to avoid
	/// concurrent modification, then synchronizes on the original request
	/// to inject the keyframe message.
	private void addKeyFrameAsync(OpenAIRequest req, List<OpenAIMessage> snapshot) {
		ESRBEnumType rating = chatConfig.getEnum("rating");

		BaseRecord systemChar = chatConfig.get("systemCharacter");
		BaseRecord userChar = chatConfig.get("userCharacter");

		String lab = systemChar.get("firstName") + " and " + userChar.get("firstName");

		/// Step 1: Build a snapshot request for analysis (avoids concurrent modification)
		OpenAIRequest snapshotReq = new OpenAIRequest();
		snapshotReq.setModel(req.getModel());
		snapshotReq.setMessages(new ArrayList<>(snapshot));
		applyChatOptions(snapshotReq);

		/// Step 1a: Build focused keyframe request and call with analyzeTimeout
		OpenAIRequest kfReq = buildKeyframeRequest(snapshotReq);
		String analysisText = null;
		if (kfReq != null) {
			analyzeTimeoutOverride.set(analyzeTimeout);
			try {
				OpenAIResponse kfResp = chat(kfReq);
				if (kfResp != null && kfResp.getMessage() != null) {
					analysisText = kfResp.getMessage().getContent();
				}
			} catch (Exception e) {
				logger.warn("Keyframe analysis failed: " + e.getMessage());
			} finally {
				analyzeTimeoutOverride.remove();
			}
		}

		boolean analysisOk = analysisText != null && !analysisText.isBlank();
		if (!analysisOk) {
			logger.warn("Keyframe analysis returned empty — will skip memory extraction");
		}

		/// Step 2: Build MCP keyframe and inject into original request (synchronized)
		String cfgObjId = chatConfig.get(FieldNames.FIELD_OBJECT_ID);
		OpenAIMessage msg = new OpenAIMessage();
		msg.setRole(keyframeRole);
		McpContextBuilder builder = new McpContextBuilder();
		builder.addKeyframe(
			"am7://keyframe/" + (cfgObjId != null ? cfgObjId : "default"),
			Map.of(
				"summary", "Summary of " + lab + " with " + rating.toString() + "/" + ESRBEnumType.getESRBMPA(rating) + "-rated content",
				"analysis", analysisText != null ? analysisText : "",
				"rating", rating.toString(),
				"ratingMpa", ESRBEnumType.getESRBMPA(rating),
				"characters", lab
			)
		);
		msg.setContent(builder.build());

		synchronized (req) {
			/// OI-14: MCP-only keyframe detection (old text format deprecated)
			/// Filter out previous keyframes, keeping the last 2
			List<OpenAIMessage> keyframes = req.getMessages().stream()
					.filter(m -> isMcpKeyframe(m.getContent()))
					.collect(Collectors.toList());
			List<OpenAIMessage> nonKeyframes = req.getMessages().stream()
					.filter(m -> !isMcpKeyframe(m.getContent()))
					.collect(Collectors.toList());

			if (keyframes.size() > 1) {
				OpenAIMessage lastExisting = keyframes.get(keyframes.size() - 1);
				nonKeyframes.add(lastExisting);
			} else if (keyframes.size() == 1) {
				nonKeyframes.add(keyframes.get(0));
			}
			nonKeyframes.add(msg);
			req.setMessages(nonKeyframes);
		}

		/// Step 3: Persist keyframe analysis as a durable memory
		if (analysisOk) {
			persistKeyframeAsMemory(req, analysisText, lab, cfgObjId, systemChar, userChar);
		}

		/// Phase 13f: Emit keyframe event (OI-72)
		if (listener != null) {
			listener.onMemoryEvent(user, req, "keyframe", lab);
		}

		/// Step 4: Extract discrete memories via dedicated prompt (Phase 14b)
		/// Skip if keyframe analysis failed — avoid double timeout
		if (analysisOk) {
			extractMemoriesIfEnabled(req, snapshotReq, cfgObjId, systemChar, userChar);
		}

		/// Step 5: Interaction evaluation — runs inline since we're already async
		if (listener != null) {
			listener.onEvalProgress(user, req, "interaction", "Evaluating interaction: type, outcome, relationship direction");
			try {
				InteractionEvaluator ie = new InteractionEvaluator();
				String result = ie.evaluate(user, chatConfig, req.getMessages());
				if (result != null && !result.isEmpty()) {
					listener.onInteractionEvent(user, req, result);
				}
				listener.onEvalProgress(user, req, "interactionDone", result != null ? "complete" : "no result");
			} catch (Exception e) {
				logger.warn("Interaction evaluation failed: " + e.getMessage());
				listener.onEvalProgress(user, req, "interactionDone", "error");
			}
		}
	}

	/// Phase 14b: Extract discrete memories from the conversation segment
	/// using a dedicated memory extraction prompt. Called from the async keyframe pipeline.
	private void extractMemoriesIfEnabled(OpenAIRequest req, OpenAIRequest snapshotReq,
			String cfgObjId, BaseRecord systemChar, BaseRecord userChar) {

		boolean extractMemories = false;
		try {
			extractMemories = chatConfig.get("extractMemories");
		} catch (Exception e) {
			// field may not be set
		}
		if (!extractMemories) {
			return;
		}

		if (listener != null) {
			listener.onEvalProgress(user, req, "memoryExtract", "Extracting memories...");
		}

		try {
			List<BaseRecord> memories = extractMemoriesFromSegment(snapshotReq, cfgObjId, systemChar, userChar);

			if (listener != null) {
				String countMsg = memories.size() + " memories extracted";
				listener.onMemoryEvent(user, req, "extracted", countMsg);
				listener.onEvalProgress(user, req, "memoryExtractDone", countMsg);
			}
		} catch (Exception e) {
			logger.warn("Memory extraction failed: " + e.getMessage());
			if (listener != null) {
				listener.onEvalProgress(user, req, "memoryExtractDone", "error");
			}
		}
	}

	/// Phase 14b: Dedicated memory extraction — uses a structured prompt to extract
	/// categorized engrams (FACT, RELATIONSHIP, EMOTION, DECISION, DISCOVERY) from
	/// the conversation segment, instead of relying on the generic analyze() output.
	private List<BaseRecord> extractMemoriesFromSegment(OpenAIRequest snapshotReq,
			String conversationId, BaseRecord systemChar, BaseRecord userChar) {

		/// Build the conversation segment text from the snapshot
		String segment = ChatUtil.getFormattedChatHistory(snapshotReq, chatConfig, pruneSkip, false)
			.stream().collect(Collectors.joining(System.lineSeparator()));

		if (segment == null || segment.trim().isEmpty()) {
			return java.util.Collections.emptyList();
		}

		/// Load the extraction prompt from resources
		String promptName = null;
		try {
			promptName = chatConfig.get("memoryExtractionPrompt");
		} catch (Exception e) {
			// field may not be set
		}
		if (promptName == null || promptName.isEmpty()) {
			promptName = "memoryExtraction";
		}

		String systemPrompt = PromptResourceUtil.getLines(promptName, "system");
		if (systemPrompt == null) {
			logger.warn("Memory extraction prompt not found: " + promptName);
			return java.util.Collections.emptyList();
		}

		/// Replace template tokens in the prompt
		String sysCharName = systemChar.get("firstName");
		String usrCharName = userChar.get("firstName");
		String setting = "unknown";
		try {
			setting = chatConfig.get("setting");
			if (setting == null) setting = "unknown";
		} catch (Exception e) {
			// ignore
		}

		systemPrompt = PromptResourceUtil.replaceToken(systemPrompt, "systemCharName", sysCharName);
		systemPrompt = PromptResourceUtil.replaceToken(systemPrompt, "userCharName", usrCharName);
		systemPrompt = PromptResourceUtil.replaceToken(systemPrompt, "setting", setting);

		/// Use analyzeModel if configured, otherwise main model
		String amodel = chatConfig.get("analyzeModel");
		if (amodel == null || amodel.isEmpty()) {
			amodel = chatConfig.get("model");
		}

		/// Build extraction request
		OpenAIRequest extractReq = new OpenAIRequest();
		extractReq.setModel(amodel);
		applyChatOptions(extractReq);
		/// Lower temperature for factual extraction
		try { extractReq.set("temperature", 0.3); } catch (Exception e) { /* ignore */ }

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		sysMsg.setContent(systemPrompt);
		extractReq.addMessage(sysMsg);

		OpenAIMessage usrMsg = new OpenAIMessage();
		usrMsg.setRole(userRole);
		usrMsg.setContent(segment);
		extractReq.addMessage(usrMsg);

		/// Use analyzeTimeout for the extraction call via thread-local override (thread-safe)
		analyzeTimeoutOverride.set(analyzeTimeout);
		OpenAIResponse resp;
		try {
			resp = chat(extractReq);
		} finally {
			analyzeTimeoutOverride.remove();
		}

		if (resp == null || resp.getMessage() == null) {
			logger.warn("No response from memory extraction LLM call");
			return java.util.Collections.emptyList();
		}

		long sysId = systemChar.get(FieldNames.FIELD_ID);
		long usrId = userChar.get(FieldNames.FIELD_ID);
		String personModel = null;
		if (systemChar.getSchema() != null) {
			personModel = systemChar.getSchema();
		}
		String sourceUri = "am7://keyframe/" + (conversationId != null ? conversationId : "default");

		return MemoryUtil.extractMemoriesFromResponse(
			user, resp.getMessage().getContent(), sourceUri, conversationId,
			sysId, usrId, personModel
		);
	}

	/// Phase 3: Persist the keyframe analysis text as a durable OUTCOME memory
	/// tagged with both character IDs in canonical order for cross-conversation retrieval.
	private void persistKeyframeAsMemory(OpenAIRequest req, String analysisText, String characterLabel,
			String cfgObjId, BaseRecord systemChar, BaseRecord userChar) {

		if (analysisText == null || analysisText.trim().isEmpty()) {
			return;
		}

		boolean extractMemories = false;
		try {
			extractMemories = chatConfig.get("extractMemories");
		} catch (Exception e) {
			// field may not be set
		}
		if (!extractMemories) {
			return;
		}

		/// Check memoryExtractionEvery — controls how often keyframes produce memories
		/// 0 = every keyframe, N = every Nth keyframe
		int extractionEvery = 0;
		try {
			extractionEvery = chatConfig.get("memoryExtractionEvery");
		} catch (Exception e) {
			// field may not be set, default to 0 (every keyframe)
		}

		if (extractionEvery > 0 && cfgObjId != null) {
			/// Count existing keyframe memories for this conversation to determine if we should extract
			List<BaseRecord> existingMemories = MemoryUtil.getConversationMemories(user, cfgObjId);
			int keyframeMemCount = (int) existingMemories.stream()
				.filter(m -> {
					Object mt = m.get("memoryType");
					return mt != null && MemoryTypeEnumType.OUTCOME.toString().equals(mt.toString());
				})
				.count();
			if (keyframeMemCount % extractionEvery != 0) {
				logger.info("Skipping keyframe memory extraction (extraction every " + extractionEvery + ", keyframe memory count=" + keyframeMemCount + ")");
				return;
			}
		}

		try {
			long sysId = systemChar.get(FieldNames.FIELD_ID);
			long usrId = userChar.get(FieldNames.FIELD_ID);

			/// Truncate analysis to a summary (first 200 chars, ending at sentence boundary)
			String summary = truncateToSentence(analysisText, 200);

			String sourceUri = "am7://keyframe/" + (cfgObjId != null ? cfgObjId : "default");
			String conversationId = cfgObjId;

			/// OI-1: Populate personModel from the character schema
			String personModel = null;
			if (systemChar.getSchema() != null) {
				personModel = systemChar.getSchema();
			}

			BaseRecord memory = MemoryUtil.createMemory(
				user,
				analysisText,
				summary,
				MemoryTypeEnumType.OUTCOME,
				7,  // Keyframe summaries are moderately important
				sourceUri,
				conversationId,
				sysId,
				usrId,
				personModel
			);

			if (memory != null) {
				logger.info("Persisted keyframe as memory for " + characterLabel);
				/// Phase 13f: Emit extracted event (OI-71)
				if (listener != null) {
					listener.onMemoryEvent(user, req, "extracted", summary);
				}
			} else {
				logger.warn("Failed to persist keyframe as memory for " + characterLabel);
			}
		} catch (Exception e) {
			logger.warn("Error persisting keyframe as memory: " + e.getMessage());
		}
	}

	/// Truncate text to maxLen characters, ending at a sentence boundary if possible.
	private String truncateToSentence(String text, int maxLen) {
		if (text == null || text.length() <= maxLen) {
			return text;
		}
		String truncated = text.substring(0, maxLen);
		int lastPeriod = truncated.lastIndexOf('.');
		int lastExcl = truncated.lastIndexOf('!');
		int lastQ = truncated.lastIndexOf('?');
		int lastSentEnd = Math.max(lastPeriod, Math.max(lastExcl, lastQ));
		if (lastSentEnd > maxLen / 2) {
			return truncated.substring(0, lastSentEnd + 1);
		}
		return truncated + "...";
	}

	public void handleResponse(OpenAIRequest req, OpenAIResponse rep, boolean emitResponse) {
		List<BaseRecord> msgs = new ArrayList<>();
		BaseRecord msg = rep.get("message");
		if (msg == null) {
			List<BaseRecord> choices = rep.get("choices");
			for (BaseRecord c : choices) {
				BaseRecord m = c.get("message");
				msgs.add(m);
			}
		} else {
			msgs.add(msg);
		}
		for (BaseRecord m : msgs) {
			if (includeMessageHistory) {
				req.addMessage(new OpenAIMessage(m));
			}
			String cont = m.get("content");
			if (emitResponse && cont != null) {
				System.out.println(formatOutput(cont));
			}
		}

	}

	private String formatOutput(String input) {
		if (!formatOutput) {
			return input;
		}
		String output = input.replace('’', '\'');
		return output;
	}

	public OpenAIRequest newRequest(String model) {
		OpenAIRequest req = new OpenAIRequest();
		req.setModel(model);
		req.setStream(streamMode);
		applyChatOptions(req);
		if (llmSystemPrompt != null) {
			OpenAIMessage msg = new OpenAIMessage();
			msg.setRole(systemRole);
			msg.setContent(llmSystemPrompt.trim());
			List<OpenAIMessage> msgs = req.get("messages");
			msgs.add(msg);
		}

		return req;
	}

	private void pruneCount(OpenAIRequest req, int messageCount) {
		boolean enablePrune = chatConfig.get("prune");
		if (messageCount <= 0 || !enablePrune || !chatMode) {
			return;
		}

		/// Target count = system + pruneSkip
		int idx = getMessageOffset(req);
		int len = req.getMessages().size() - messageCount;
		List<OpenAIMessage> kfs = new ArrayList<>();
		for (int i = idx; i < len; i++) {
			OpenAIMessage msg = req.getMessages().get(i);
			msg.setPruned(true);
			/// OI-14: MCP-only keyframe detection (old text format deprecated)
			if (msg.getContent() != null && isMcpKeyframe(msg.getContent())) {
				kfs.add(msg);
			}
		}

		/// Phase 3: Don't prune the last 2 keyframes for better continuity
		if (kfs.size() > 0) {
			kfs.get(kfs.size() - 1).setPruned(false);
			if (kfs.size() > 1) {
				kfs.get(kfs.size() - 2).setPruned(false);
			}
		}
		boolean useAssist = chatConfig.get("assist");
		if (!useAssist || keyFrameEvery <= 0) {
			return;
		}
		int qual = countBackToMcp(req, "/keyframe/");
		logger.info("Keyframe check: keyFrameEvery=" + keyFrameEvery
			+ " msgSize=" + req.getMessages().size() + " pruneSkip=" + pruneSkip
			+ " threshold=" + (pruneSkip + keyFrameEvery) + " sinceLastKF=" + qual);
		if (req.getMessages().size() > (pruneSkip + keyFrameEvery) && qual >= keyFrameEvery) {
			/// Guard: skip if a previous async keyframe is still in progress
			if (asyncKeyframeInProgress) {
				logger.info("Skipping keyframe — async keyframe already in progress");
				return;
			}
			/// Defer keyframe launch: save snapshot now, launch AFTER main response completes.
			/// This prevents the keyframe LLM call from competing with the main response
			/// for LLM resources (critical for single-slot LLMs like Ollama).
			logger.info("(Deferring keyframe — will launch after main response)");
			pendingKeyframeSnapshot = new ArrayList<>(req.getMessages());
		}

	}

	/// Flush a deferred keyframe: launch the async pipeline now that the main response is complete.
	/// Called from continueChat (buffer mode) and ChatListener.oncomplete (streaming mode).
	public void flushPendingKeyframe(OpenAIRequest req) {
		List<OpenAIMessage> snapshot = pendingKeyframeSnapshot;
		pendingKeyframeSnapshot = null;
		if (snapshot == null) {
			return;
		}
		if (asyncKeyframeInProgress) {
			logger.info("Skipping deferred keyframe — async keyframe already in progress");
			return;
		}
		logger.info("(Launching deferred keyframe — main response complete)");
		asyncKeyframeInProgress = true;

		if (listener != null) {
			listener.onEvalProgress(user, req, "keyframe", "Generating keyframe summary...");
		}

		CompletableFuture.runAsync(() -> {
			try {
				addKeyFrameAsync(req, snapshot);
			} catch (Exception e) {
				logger.warn("Async keyframe generation failed: " + e.getMessage());
			} finally {
				asyncKeyframeInProgress = false;
			}
			if (listener != null) {
				listener.onEvalProgress(user, req, "keyframeDone", "Keyframe complete");
			}
		});
	}

	/// OI-14: Unified MCP-only keyframe/reminder detection methods.
	/// Old `(KeyFrame:` and `(Reminder:` text formats are deprecated.

	private static boolean isMcpKeyframe(String content) {
		return content != null && content.contains("<mcp:context") && content.contains("/keyframe/");
	}

	private static boolean isMcpReminder(String content) {
		return content != null && content.contains("<mcp:context") && content.contains("/reminder/");
	}

	/// Count messages backwards from the end to the last MCP block matching the given URI fragment.
	/// Replaces the old countBackTo() which checked both old text and MCP formats.
	private int countBackToMcp(OpenAIRequest req, String uriFragment) {
		int idx = getMessageOffset(req);
		int eidx = req.getMessages().size() - 1;
		int qual = 0;
		for (int i = eidx; i >= idx; i--) {
			OpenAIMessage imsg = req.getMessages().get(i);
			if(systemRole.equals(imsg.getRole())) {
				continue;
			}
			if(imsg.getContent() == null) continue;
			if(imsg.getContent().contains("<mcp:context") && imsg.getContent().contains(uriFragment)) {
				break;
			}
			qual++;
		}
		return qual;
	}
	
	public OpenAIMessage newMessage(OpenAIRequest req, String message) {
		return newMessage(req, message, userRole);
	}

	public OpenAIMessage newMessage(OpenAIRequest req, String message, String role) {
		OpenAIMessage msg = new OpenAIMessage();
		msg.setRole(role);
		StringBuilder msgBuff = new StringBuilder();
		msgBuff.append(message);
		if (chatConfig != null && role.equals(userRole)) {
			ESRBEnumType rating = chatConfig.getEnum("rating");
			boolean useAssist = chatConfig.get("assist");
			int qual = countBackToMcp(req, "/reminder/");

			if (useAssist && promptConfig != null && qual >= remind && remind > 0) {
				if (req.getMessages().size() > 0) {
					OpenAIMessage amsg = req.getMessages().get(req.getMessages().size() - 1);
					if (amsg.getRole().equals(assistantRole)) {
						OpenAIMessage anmsg = new OpenAIMessage();
						List<String> arem = promptConfig.get("assistantReminder");
						String rem = arem.stream().collect(Collectors.joining(System.lineSeparator()));
						if (chatConfig != null) {
							rem = PromptUtil.getChatPromptTemplate(promptConfig, chatConfig, rem, false);
						}
						if(rem.length() > 0) {
							anmsg.setRole(assistantRole);
							anmsg.setContent(rem);
							req.addMessage(anmsg);
						}
					}
				}
				
				/// Inject the user reminder as MCP block
				List<String> urem = promptConfig.get("userReminder");
				String rem = urem.stream().collect(Collectors.joining(System.lineSeparator()));
				if (chatConfig != null) {
					rem = PromptUtil.getChatPromptTemplate(promptConfig, chatConfig, rem, false);
				}

				if (rem.length() > 0) {
					String cfgObjId = chatConfig.get(FieldNames.FIELD_OBJECT_ID);
					McpContextBuilder remBuilder = new McpContextBuilder();
					remBuilder.addReminder(
						"am7://reminder/" + (cfgObjId != null ? cfgObjId : "default"),
						List.of(Map.of("key", "user-reminder", "value", rem))
					);
					msgBuff.append(System.lineSeparator() + remBuilder.build());
				}
			}
		}
		msg.setContent(msgBuff.toString());

		if (chatConfig != null) {
			pruneCount(req, messageTrim);
		}

		req.addMessage(msg);

		return msg;
	}

	/// Phase 7: Always-stream from LLM. The stream flag controls whether chunks
	/// are forwarded to the client listener or buffered internally.
	/// When stream=false, this method blocks until the response is complete and returns it.
	/// When stream=true, chunks are forwarded via the listener and the method returns null (async).
	public OpenAIResponse chat(OpenAIRequest req) {
		if (req == null) {
			return null;
		}

		/// Reset mid-stream policy tracking for this request
		lastMidStreamCheckLength = 0;
		midStreamViolation = null;

		List<String> ignoreFields = new ArrayList<>(ChatUtil.IGNORE_FIELDS);
		String tokField = ChatUtil.getMaxTokenField(chatConfig);
		ignoreFields.addAll(Arrays.asList(new String[] {"num_ctx", "max_tokens", "max_completion_tokens"}).stream().filter(f -> !f.equals(tokField)).collect(Collectors.toList()));

		String penField = ChatUtil.getPresencePenaltyField(chatConfig);
		ignoreFields.addAll(Arrays.asList(new String[] {"presence_penalty"}).stream().filter(f -> !f.equals(penField)).collect(Collectors.toList()));

		boolean forwardToClient = (boolean) req.get("stream");

		/// Always set stream=true on the wire request so the LLM always streams
		OpenAIRequest wireReq = ChatUtil.getPrunedRequest(req, ignoreFields);
		wireReq.setStream(true);
		String ser = JSONUtil.exportObject(wireReq, RecordSerializerConfig.getHiddenForeignUnfilteredModule());

		OpenAIResponse aresp = new OpenAIResponse();
		CountDownLatch latch = forwardToClient ? null : new CountDownLatch(1);
		final OpenAIResponse[] bufferResult = new OpenAIResponse[] { null };
		final String[] bufferError = new String[] { null };

		CompletableFuture<HttpResponse<Stream<String>>> streamFuture = ClientUtil.postToRecordAndStream(getServiceUrl(req), authorizationToken, ser);

		/// Apply effective timeout: thread-local override (background analyze/extract) or requestTimeout (normal calls)
		final int effectiveTimeout = analyzeTimeoutOverride.get() != null ? analyzeTimeoutOverride.get() : requestTimeout;
		if (effectiveTimeout > 0) {
			streamFuture = streamFuture.orTimeout(effectiveTimeout, TimeUnit.SECONDS);
		}

		/// Phase 9: Register stream future with listener for failover cancellation
		if (forwardToClient && listener instanceof ChatListener) {
			String oid = req.get(FieldNames.FIELD_OBJECT_ID);
			if (oid != null) {
				((ChatListener) listener).registerStreamFuture(oid, streamFuture);
			}
		}

		streamFuture.thenAccept(response -> {
			if (response == null || response.body() == null) {
				logger.warn("Null response from streaming chat");
				return;
			}
			/// Phase 12: OI-27 — Register HTTP response for server-side abort on cancel
			if (forwardToClient && listener instanceof ChatListener) {
				String oid2 = req.get(FieldNames.FIELD_OBJECT_ID);
				if (oid2 != null) {
					((ChatListener) listener).registerHttpResponse(oid2, response);
				}
			}
			boolean hasListener = listener != null;
			response.body()
				.takeWhile(line -> !hasListener || !listener.isStopStream(req))
				.forEach(line -> {
					processStreamChunk(line, req, aresp, forwardToClient);
				});
		}).whenComplete((result, error) -> {
			if (error != null) {
				String errMsg;
				if (error instanceof TimeoutException || (error.getCause() != null && error.getCause() instanceof TimeoutException)) {
					errMsg = "Request timed out after " + effectiveTimeout + " seconds";
				} else {
					errMsg = "Error during streaming chat response: " + error.getMessage();
				}
				if (forwardToClient && listener != null) {
					listener.onerror(user, req, aresp, errMsg);
				} else {
					bufferError[0] = errMsg;
					logger.error(errMsg);
				}
			} else {
				if (forwardToClient && listener != null) {
					listener.oncomplete(user, req, aresp);
				} else {
					bufferResult[0] = aresp;
				}
			}
			if (latch != null) {
				latch.countDown();
			}
		});

		if (!forwardToClient) {
			/// Buffer mode: block until streaming completes
			try {
				int waitSeconds = effectiveTimeout > 0 ? effectiveTimeout + 5 : 300;
				if (!latch.await(waitSeconds, TimeUnit.SECONDS)) {
					logger.error("Buffer mode timed out waiting for stream completion");
					return null;
				}
			} catch (InterruptedException e) {
				logger.error("Buffer mode interrupted", e);
				Thread.currentThread().interrupt();
				return null;
			}
			if (bufferError[0] != null) {
				logger.error("Stream error in buffer mode: " + bufferError[0]);
				if (listener != null) {
					listener.onerror(user, req, aresp, bufferError[0]);
				}
				return null;
			}
			return bufferResult[0];
		}

		/// Streaming mode: return null, caller uses listener callbacks
		return null;
	}

	/// Phase 7: Shared stream chunk processing — eliminates duplicated parsing logic
	/// between Ollama (message-based) and OpenAI (choices/delta-based) response formats.
	private void processStreamChunk(String line, OpenAIRequest req, OpenAIResponse aresp, boolean forwardToClient) {
		if (line == null || line.isEmpty()) {
			return;
		}

		String json = line;
		if (serviceType == LLMServiceEnumType.OPENAI && line.startsWith("data: ")) {
			json = line.substring(6);
		}
		if ("[DONE]".equals(json)) {
			return;
		}

		BaseRecord asyncResp = RecordFactory.importRecord(OlioModelNames.MODEL_OPENAI_RESPONSE, json);
		if (asyncResp == null) {
			if (forwardToClient && listener != null) {
				listener.onerror(user, req, aresp, "Failed to import response object from: " + json);
			}
			return;
		}
		if (asyncResp.get("error") != null) {
			if (forwardToClient && listener != null) {
				listener.onerror(user, req, aresp, "Received an error: " + asyncResp.get("error"));
			}
			return;
		}

		List<BaseRecord> choices = asyncResp.get("choices");
		if (choices.isEmpty()) {
			/// Ollama format: message at top level
			BaseRecord deltaMessage = asyncResp.get("message");
			if (deltaMessage == null) {
				logger.info("Received empty message ... treat as completed");
				return;
			}
			String contentChunk = deltaMessage.get("content");
			boolean done = asyncResp.get("done");
			if (done) {
				return;
			}
			if (contentChunk != null && contentChunk.length() > 0) {
				accumulateChunk(aresp, deltaMessage, contentChunk, forwardToClient, req);
			}
		} else {
			/// OpenAI format: choices array with delta objects
			for (BaseRecord choice : choices) {
				BaseRecord delta = choice.get("delta");
				if (delta != null && delta.hasField("content")) {
					String contentChunk = delta.get("content");
					if (contentChunk != null) {
						accumulateChunk(aresp, delta, contentChunk, forwardToClient, req);
					}
				}
			}
		}
	}

	/// Accumulate a content chunk into the response and optionally forward to listener.
	/// Includes periodic mid-stream policy evaluation to detect violations early and stop generation.
	private void accumulateChunk(OpenAIResponse aresp, BaseRecord delta, String contentChunk, boolean forwardToClient, OpenAIRequest req) {
		BaseRecord amsg = aresp.get("message");
		if (amsg == null) {
			if (delta.get("role") == null) {
				delta.setValue("role", "assistant");
			}
			aresp.setValue("message", delta);
		} else {
			amsg.setValue("content", (String) amsg.get("content") + contentChunk);
		}
		if (forwardToClient && listener != null) {
			listener.onupdate(user, req, aresp, contentChunk);
		}

		/// Mid-stream policy evaluation: check accumulated content at intervals
		/// Only runs in streaming mode (forwardToClient) and only if no violation already found
		if (forwardToClient && midStreamViolation == null) {
			BaseRecord msg = aresp.get("message");
			if (msg != null) {
				String accumulated = msg.get("content");
				int len = accumulated != null ? accumulated.length() : 0;
				if (len - lastMidStreamCheckLength >= MID_STREAM_CHECK_INTERVAL) {
					lastMidStreamCheckLength = len;
					PolicyEvaluationResult result = evaluateMidStreamPolicy(req, aresp);
					if (result != null) {
						midStreamViolation = result;
						logger.warn("Mid-stream policy violation at " + len + " chars: " + result.getViolationSummary());
						if (listener != null) {
							listener.onEvalProgress(user, req, "midStreamViolation", result.getViolationSummary());
							listener.stopStream(req);
						}
					}
				}
			}
		}
	}

	public String getServiceUrl(OpenAIRequest req) {

		String url = null;
		if (serviceType == LLMServiceEnumType.OLLAMA) {
			url = serverUrl + "/api/" + (chatMode ? "chat" : "generate");
		} else if (serviceType == LLMServiceEnumType.OPENAI) {
			url = serverUrl + "/openai/deployments/" + req.getModel() + "/chat/completions"
					+ (apiVersion != null ? "?api-version=" + apiVersion : "");
		}
		return url;
	}

	/// Phase 14c: Enhanced memory reconstitution with budget-allocated type-prioritized
	/// retrieval, cross-chat layered queries, and freshness decay (OI-90).
	///
	/// Budget allocation:
	///   40% RELATIONSHIP — most important for character consistency
	///   25% FACT — concrete details that prevent contradiction
	///   20% DECISION/DISCOVERY — plot-relevant choices
	///   15% EMOTION — emotional continuity
	///
	/// Query layers:
	///   Layer 1 (50%): Pair-specific (this exact character pair)
	///   Layer 2 (30%): Character-specific (either character with anyone)
	///   Layer 3 (20%): Semantic (topic-relevant from any source)
	private String retrieveRelevantMemories(BaseRecord systemChar, BaseRecord userChar) {
		if (chatConfig == null || systemChar == null || userChar == null) {
			logger.info("retrieveRelevantMemories: skipping — chatConfig=" + (chatConfig != null) + " sys=" + (systemChar != null) + " usr=" + (userChar != null));
			return "";
		}
		int memoryBudget = chatConfig.get("memoryBudget");
		if (memoryBudget <= 0) {
			logger.info("retrieveRelevantMemories: memoryBudget=" + memoryBudget + " (disabled) — chatConfig.objectId=" + chatConfig.get(FieldNames.FIELD_OBJECT_ID) + " extractMemories=" + chatConfig.get("extractMemories"));
			return "";
		}

		try {
			long sysId = systemChar.get(FieldNames.FIELD_ID);
			long usrId = userChar.get(FieldNames.FIELD_ID);
			logger.info("retrieveRelevantMemories: budget=" + memoryBudget + " sysId=" + sysId + " usrId=" + usrId);

			int maxPerLayer = Math.max(3, memoryBudget / 50);

			/// Layer 1: Pair-specific memories (50% of budget)
			List<BaseRecord> pairMemories = MemoryUtil.searchMemoriesByPersonPair(user, sysId, usrId, maxPerLayer);
			logger.info("retrieveRelevantMemories: pairMemories=" + pairMemories.size());

			/// Layer 2: Character-specific memories (30% of budget)
			List<BaseRecord> charMemories = MemoryUtil.searchMemoriesByPerson(user, sysId, maxPerLayer / 2);
			/// Add user character memories too, deduplicating against pair results
			List<BaseRecord> userCharMemories = MemoryUtil.searchMemoriesByPerson(user, usrId, maxPerLayer / 2);
			for (BaseRecord ucm : userCharMemories) {
				long ucmId = ucm.get(FieldNames.FIELD_ID);
				boolean dup = charMemories.stream().anyMatch(m -> (long) m.get(FieldNames.FIELD_ID) == ucmId);
				if (!dup) {
					charMemories.add(ucm);
				}
			}
			/// Remove any that are already in pair results
			java.util.Set<Long> pairIds = new java.util.HashSet<>();
			for (BaseRecord pm : pairMemories) {
				pairIds.add((long) pm.get(FieldNames.FIELD_ID));
			}
			charMemories.removeIf(m -> pairIds.contains((long) m.get(FieldNames.FIELD_ID)));

			/// Combine all memories with freshness decay applied
			List<MemoryWithScore> scored = new ArrayList<>();
			String cfgObjId = chatConfig.get(FieldNames.FIELD_OBJECT_ID);
			for (BaseRecord mem : pairMemories) {
				scored.add(new MemoryWithScore(mem, applyFreshnessDecay(mem, cfgObjId), 1));
			}
			for (BaseRecord mem : charMemories) {
				scored.add(new MemoryWithScore(mem, applyFreshnessDecay(mem, cfgObjId), 2));
			}

			/// Sort by effective importance descending
			scored.sort((a, b) -> Double.compare(b.effectiveImportance, a.effectiveImportance));

			/// Budget-allocated type-prioritized injection
			java.util.Map<String, Double> typeBudgetRatios = new java.util.LinkedHashMap<>();
			typeBudgetRatios.put("RELATIONSHIP", 0.40);
			typeBudgetRatios.put("FACT", 0.25);
			typeBudgetRatios.put("DECISION", 0.10);
			typeBudgetRatios.put("DISCOVERY", 0.10);
			typeBudgetRatios.put("EMOTION", 0.15);

			McpContextBuilder ctxBuilder = new McpContextBuilder();
			List<String> relationshipSummaries = new ArrayList<>();
			List<String> factSummaries = new ArrayList<>();
			List<String> decisionSummaries = new ArrayList<>();
			List<String> emotionSummaries = new ArrayList<>();
			String lastSessionText = "";
			int totalIncluded = 0;
			int tokensUsed = 0;

			long id1 = Math.min(sysId, usrId);
			long id2 = Math.max(sysId, usrId);

			logger.info("Memory budget allocation: total=" + memoryBudget + " tokens, scored=" + scored.size() + " memories");

			/// First pass: allocate by type budget
			for (java.util.Map.Entry<String, Double> entry : typeBudgetRatios.entrySet()) {
				String targetType = entry.getKey();
				int typeBudget = (int)(memoryBudget * entry.getValue());
				int typeTokensUsed = 0;

				for (MemoryWithScore ms : scored) {
					if (ms.included) continue;
					String memType = getMemoryType(ms.memory);
					if (!targetType.equals(memType)) continue;

					String content = ms.memory.get("content");
					if (content == null) continue;

					int memTokens = estimateTokens(content);
					if (typeTokensUsed + memTokens > typeBudget || tokensUsed + memTokens > memoryBudget) {
						logger.info("Memory skipped: type=" + memType + " memTokens=" + memTokens + " typeBudget=" + typeBudget + " totalBudget=" + memoryBudget);
						continue;
					}

					ms.included = true;
					typeTokensUsed += memTokens;
					tokensUsed += memTokens;
					totalIncluded++;

					addMemoryToContext(ctxBuilder, ms.memory, id1, id2);
					categorizeMemory(ms.memory, memType, relationshipSummaries, factSummaries,
						decisionSummaries, emotionSummaries);
				}
			}

			/// Second pass: fill remaining budget with any unclaimed memories by importance
			for (MemoryWithScore ms : scored) {
				if (ms.included) continue;
				String content = ms.memory.get("content");
				if (content == null) continue;
				int memTokens = estimateTokens(content);
				if (tokensUsed + memTokens > memoryBudget) continue;

				ms.included = true;
				tokensUsed += memTokens;
				totalIncluded++;

				String memType = getMemoryType(ms.memory);
				addMemoryToContext(ctxBuilder, ms.memory, id1, id2);
				categorizeMemory(ms.memory, memType, relationshipSummaries, factSummaries,
					decisionSummaries, emotionSummaries);

				if ("OUTCOME".equals(memType) || "EVENT".equals(memType)) {
					String summary = ms.memory.get("summary");
					String displayText = summary != null && !summary.isEmpty() ? summary : content;
					lastSessionText = displayText;
				}
			}

			// Set categorized memory thread-locals for PromptUtil template variables
			PromptUtil.setMemoryRelationship(String.join("; ", relationshipSummaries));
			PromptUtil.setMemoryFacts(String.join("; ", factSummaries));
			PromptUtil.setMemoryDecisions(String.join("; ", decisionSummaries));
			PromptUtil.setMemoryEmotions(String.join("; ", emotionSummaries));
			PromptUtil.setMemoryLastSession(lastSessionText);
			PromptUtil.setMemoryCount(totalIncluded);

			/// Phase 13f: Emit recalled event (OI-71)
			logger.info("Recalled " + totalIncluded + " memories (" + tokensUsed + " tokens) for " + id1 + "/" + id2);
			if (listener != null) {
				listener.onMemoryEvent(user, null, "recalled", String.valueOf(totalIncluded));
			}

			return ctxBuilder.build();
		} catch (Exception e) {
			logger.warn("Error retrieving memories: " + e.getMessage());
			return "";
		}
	}

	/// Helper: get memoryType string from a memory record, defaulting to "NOTE".
	private static String getMemoryType(BaseRecord memory) {
		Object mt = memory.get("memoryType");
		return mt != null ? mt.toString() : "NOTE";
	}

	/// Helper: add a memory record to the MCP context builder.
	private static void addMemoryToContext(McpContextBuilder ctxBuilder, BaseRecord memory, long id1, long id2) {
		String content = memory.get("content");
		String summary = memory.get("summary");
		String memoryType = getMemoryType(memory);
		String objId = memory.hasField(FieldNames.FIELD_OBJECT_ID) ? memory.get(FieldNames.FIELD_OBJECT_ID) : null;
		String uri = "am7://memory/" + id1 + "/" + id2 + (objId != null ? "/" + objId : "");
		ctxBuilder.addResource(
			uri,
			"urn:am7:narrative:memory",
			Map.of(
				"content", content != null ? content : "",
				"summary", summary != null ? summary : "",
				"memoryType", memoryType,
				"importance", memory.get("importance")
			),
			true
		);
	}

	/// Helper: categorize a memory into the appropriate summary list for template variables.
	private static void categorizeMemory(BaseRecord memory, String memoryType,
			List<String> relationship, List<String> facts, List<String> decisions, List<String> emotions) {
		String summary = memory.get("summary");
		String content = memory.get("content");
		String displayText = summary != null && !summary.isEmpty() ? summary : (content != null ? content : "");
		if (displayText.isEmpty()) return;

		switch (memoryType) {
			case "RELATIONSHIP":
				relationship.add(displayText);
				break;
			case "FACT":
			case "NOTE":
			case "INSIGHT":
				facts.add(displayText);
				break;
			case "DECISION":
				decisions.add(displayText);
				break;
			case "DISCOVERY":
				decisions.add(displayText);
				break;
			case "EMOTION":
				emotions.add(displayText);
				break;
			default:
				facts.add(displayText);
				break;
		}
	}

	/// Phase 14c: Apply freshness decay to memory importance at query time.
	/// Memories are not modified — decay is applied only for ranking during reconstitution.
	/// - Recent (same conversation): Full importance
	/// - Moderate (< 7 days): importance × 0.7
	/// - Old (> 30 days): importance × 0.4
	/// - Pinned (importance = 10): Never decay
	private double applyFreshnessDecay(BaseRecord memory, String currentConversationId) {
		int importance = memory.get("importance");
		if (importance >= 10) {
			return importance; // Pinned — never decay
		}

		String memConvId = memory.get("conversationId");
		if (memConvId != null && memConvId.equals(currentConversationId)) {
			return importance; // Same conversation — full weight
		}

		/// Use createdDate to approximate age
		try {
			java.util.Date created = memory.get(FieldNames.FIELD_CREATED_DATE);
			if (created != null) {
				long ageMs = System.currentTimeMillis() - created.getTime();
				long ageDays = ageMs / (1000L * 60 * 60 * 24);
				if (ageDays > 30) {
					return importance * 0.4; // Old
				} else if (ageDays > 7) {
					return importance * 0.7; // Moderate
				}
			}
		} catch (Exception e) {
			// Fall through to default
		}

		return importance;
	}

	/// Phase 14c: Rough token estimate — ~4 chars per token for English text.
	private static int estimateTokens(String text) {
		if (text == null) return 0;
		return Math.max(1, text.length() / 4);
	}

	/// Phase 14c: Internal scoring wrapper for memory budget allocation.
	private static class MemoryWithScore {
		final BaseRecord memory;
		final double effectiveImportance;
		final int layer; // 1=pair, 2=character, 3=semantic
		boolean included = false;

		MemoryWithScore(BaseRecord memory, double effectiveImportance, int layer) {
			this.memory = memory;
			this.effectiveImportance = effectiveImportance;
			this.layer = layer;
		}
	}

	public OpenAIRequest getChatPrompt() {
		String model = null;
		if (chatConfig != null) {
			model = chatConfig.get("model");
		}
		return getChatPrompt(model);
	}

	/// Re-inject memory thread-locals before each template processing call.
	/// buildMemoryReplacements() consumes (removes) thread-locals on first use,
	/// so they must be re-set before each PromptUtil/PromptTemplateComposer call.
	private void injectMemoryThreadLocals(String memoryCtx) {
		if (memoryCtx != null && !memoryCtx.isEmpty()) {
			PromptUtil.setMemoryContext(memoryCtx);
		}
	}

	public OpenAIRequest getChatPrompt(String model) {

		OpenAIRequest req = newRequest(model);
		BaseRecord systemChar = null;
		BaseRecord userChar = null;
		String assist = null;
		String userTemp = null;
		String sysTemp = null;
		boolean useAssist = false;
		/// Memory context string saved locally — thread-locals are consumed on first use,
		/// so we must re-inject before each template processing call.
		String memoryCtx = null;
		if (chatConfig != null) {
			useAssist = chatConfig.get("assist");
			systemChar = chatConfig.get("systemCharacter");
			userChar = chatConfig.get("userCharacter");

			// Phase 2: Retrieve memory context before template processing
			memoryCtx = retrieveRelevantMemories(systemChar, userChar);
			logger.info("getChatPrompt: memoryCtx length=" + (memoryCtx != null ? memoryCtx.length() : "null"));
		}
		/// Check for structured prompt template on chatConfig
		BaseRecord promptTemplate = (chatConfig != null) ? chatConfig.get("promptTemplate") : null;
		if (promptTemplate != null) {
			IOSystem.getActiveContext().getReader().populate(promptTemplate);
		}

		if (promptTemplate != null && promptConfig != null) {
			/// Use structured template format via PromptTemplateComposer
			BaseRecord effectiveChatConfig = (systemChar != null && userChar != null) ? chatConfig : null;
			/// System template first — inject memory thread-locals before each call
			injectMemoryThreadLocals(memoryCtx);
			sysTemp = PromptTemplateComposer.composeSystem(promptTemplate, promptConfig, effectiveChatConfig);
			if (useAssist) {
				injectMemoryThreadLocals(memoryCtx);
				assist = PromptTemplateComposer.composeAssistant(promptTemplate, promptConfig, effectiveChatConfig);
				injectMemoryThreadLocals(memoryCtx);
				userTemp = PromptTemplateComposer.composeUser(promptTemplate, promptConfig, effectiveChatConfig);
			}
		} else if (promptConfig != null) {
			if (systemChar != null && userChar != null) {
				/// System template FIRST so ${memory.context} resolves before thread-local is consumed
				injectMemoryThreadLocals(memoryCtx);
				sysTemp = PromptUtil.getSystemChatPromptTemplate(promptConfig, chatConfig);
				if (useAssist) {
					injectMemoryThreadLocals(memoryCtx);
					assist = PromptUtil.getAssistChatPromptTemplate(promptConfig, chatConfig);
					injectMemoryThreadLocals(memoryCtx);
					userTemp = PromptUtil.getUserChatPromptTemplate(promptConfig, chatConfig);
				}

			} else {
				injectMemoryThreadLocals(memoryCtx);
				sysTemp = PromptUtil.getSystemChatPromptTemplate(promptConfig, null);
				injectMemoryThreadLocals(memoryCtx);
				assist = PromptUtil.getAssistChatPromptTemplate(promptConfig, null);
				injectMemoryThreadLocals(memoryCtx);
				userTemp = PromptUtil.getUserChatPromptTemplate(promptConfig, null);

			}
		}

		setLlmSystemPrompt(sysTemp);
		req = newRequest(model);
		setPruneSkip(2);
		if (userTemp != null && userTemp.length() > 0) {
			newMessage(req, userTemp);
		}
		if (assist != null && assist.length() > 0) {
			setPruneSkip(3);
			newMessage(req, assist, assistantRole);
		}

		return req;
	}

	public void applyChatOptions(OpenAIRequest req) {
		ChatUtil.applyChatOptions(req, chatConfig);
	}

}
