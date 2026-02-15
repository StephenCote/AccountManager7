package org.cote.accountmanager.olio.llm.policy;

import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.llm.PromptResourceUtil;
import org.cote.accountmanager.olio.llm.policy.ResponsePolicyEvaluator.PolicyViolation;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

/// Phase 9: Analyzes policy violations and generates autotuned prompt suggestions.
/// Uses a separate LLM call (analyzeModel) to analyze the violation and suggest prompt rewrites.
/// Autotuned prompts are saved as new records (never overwrite original).
public class ChatAutotuner {

	public static final Logger logger = LogManager.getLogger(ChatAutotuner.class);

	/// Analyze a policy violation and generate an autotuned prompt.
	/// @param user The user who owns the prompt
	/// @param chatConfig The chatConfig with LLM connection details
	/// @param promptConfig The original promptConfig that produced the violation
	/// @param violations The policy violations detected
	/// @return AutotuneResult with the autotuned prompt name and analysis, or null on failure
	public AutotuneResult autotune(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig, List<PolicyViolation> violations) {
		if (user == null || chatConfig == null || promptConfig == null || violations == null || violations.isEmpty()) {
			logger.warn("ChatAutotuner: Missing required parameters");
			return null;
		}

		try {
			/// Resolve the full chatConfig from DB to ensure persisted field values are available
			BaseRecord resolvedConfig = OlioUtil.getFullRecord(chatConfig);
			if (resolvedConfig == null) {
				resolvedConfig = chatConfig;
			}

			String analysisPrompt = buildAnalysisPrompt(promptConfig, resolvedConfig, violations);

			/// Use analyzeModel for efficiency, fall back to main model
			String model = resolvedConfig.get("analyzeModel");
			if (model == null || model.isEmpty()) {
				model = resolvedConfig.get("model");
			}
			String analysisResponse = callAnalysisLLM(user, resolvedConfig, model, analysisPrompt);
			if (analysisResponse == null || analysisResponse.trim().isEmpty()) {
				logger.warn("ChatAutotuner: Analysis LLM returned empty response");
				return new AutotuneResult(null, "Analysis LLM returned empty response", violations);
			}

			/// Generate the autotuned prompt name
			String baseName = promptConfig.get(FieldNames.FIELD_NAME);
			int count = countExistingAutotuned(user, baseName) + 1;
			String autotunedName = baseName + " - autotuned - " + count;

			return new AutotuneResult(autotunedName, analysisResponse, violations);

		} catch (Exception e) {
			logger.error("ChatAutotuner: Error during autotuning: " + e.getMessage());
			return null;
		}
	}

	/// Count existing autotuned variants of a prompt.
	public int countExistingAutotuned(BaseRecord user, String baseName) {
		try {
			Query query = QueryUtil.createQuery(OlioModelNames.MODEL_PROMPT_CONFIG);
			query.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, baseName + " - autotuned - %");
			query.field(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
			query.setContextUser(user);
			return IOSystem.getActiveContext().getSearch().count(query);
		} catch (Exception e) {
			logger.warn("ChatAutotuner: Error counting autotuned prompts: " + e.getMessage());
			return 0;
		}
	}

	private static final String RESOURCE = "autotune";

	/// Build the analysis prompt for the autotuning LLM call.
	private String buildAnalysisPrompt(BaseRecord promptConfig, BaseRecord chatConfig, List<PolicyViolation> violations) {
		StringBuilder sb = new StringBuilder();

		/// Header from resource
		String header = PromptResourceUtil.getString(RESOURCE, "header");
		sb.append(header != null ? header : "You are a prompt engineering expert. A chat prompt produced a policy violation.");
		sb.append(System.lineSeparator()).append(System.lineSeparator());

		/// Violation details (dynamic, using prefixes from resource)
		String violPrefix = PromptResourceUtil.getString(RESOURCE, "violationPrefix");
		String violDetailPrefix = PromptResourceUtil.getString(RESOURCE, "violationDetailPrefix");
		for (PolicyViolation v : violations) {
			sb.append(violPrefix != null ? violPrefix : "VIOLATION TYPE: ").append(v.getRuleType()).append(System.lineSeparator());
			sb.append(violDetailPrefix != null ? violDetailPrefix : "VIOLATION DETAILS: ").append(v.getDetails()).append(System.lineSeparator());
		}
		sb.append(System.lineSeparator());

		/// System prompt preview
		List<String> system = promptConfig.get("system");
		if (system != null && !system.isEmpty()) {
			String previewHeader = PromptResourceUtil.getString(RESOURCE, "promptPreviewHeader");
			sb.append(previewHeader != null ? previewHeader : "CURRENT SYSTEM PROMPT (first 3 lines of system[]):").append(System.lineSeparator());
			int maxLines = Math.min(3, system.size());
			for (int i = 0; i < maxLines; i++) {
				sb.append(system.get(i)).append(System.lineSeparator());
			}
			sb.append(System.lineSeparator());
		}

		/// Chat config summary (dynamic values, header from resource)
		String configHeader = PromptResourceUtil.getString(RESOURCE, "configHeader");
		sb.append(configHeader != null ? configHeader : "CURRENT CHAT CONFIGURATION:").append(System.lineSeparator());
		sb.append("- Rating: ").append((Object) chatConfig.get("rating")).append(System.lineSeparator());
		sb.append("- Model: ").append((String) chatConfig.get("model")).append(System.lineSeparator());
		sb.append("- Service: ").append((Object) chatConfig.get("serviceType")).append(System.lineSeparator());
		List<BaseRecord> episodes = chatConfig.get("episodes");
		sb.append("- Has episodes: ").append(episodes != null && !episodes.isEmpty()).append(System.lineSeparator());
		sb.append("- Has NLP: ").append((boolean) chatConfig.get("useNLP")).append(System.lineSeparator());
		sb.append(System.lineSeparator());

		/// Failure request + response format from resource
		String failureReq = PromptResourceUtil.getLines(RESOURCE, "failureRequest");
		if (failureReq != null) {
			sb.append(failureReq).append(System.lineSeparator());
		}
		sb.append(System.lineSeparator());
		String respFormat = PromptResourceUtil.getLines(RESOURCE, "responseFormat");
		if (respFormat != null) {
			sb.append(respFormat).append(System.lineSeparator());
		}

		return sb.toString();
	}

	/// Make an LLM call for analysis using the chat infrastructure.
	private String callAnalysisLLM(BaseRecord user, BaseRecord chatConfig, String model, String analysisPrompt) {
		try {
			Chat chat = new Chat(user, chatConfig, null);
			chat.setPersistSession(false);
			OpenAIRequest areq = new OpenAIRequest();
			areq.setModel(model);
			areq.setStream(false);

			/// Apply conservative options for analysis
			try {
				areq.set("temperature", 0.4);
				areq.set("top_p", 0.5);
				String tokField = ChatUtil.getMaxTokenField(chatConfig);
				if (tokField != null && !tokField.isEmpty()) {
					areq.set(tokField, 4096);
				}
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.warn("ChatAutotuner: Error setting analysis options: " + e.getMessage());
			}

			OpenAIMessage sysMsg = new OpenAIMessage();
			sysMsg.setRole("system");
			String sysContent = PromptResourceUtil.getString(RESOURCE, "system");
			sysMsg.setContent(sysContent != null ? sysContent : "You are a prompt engineering expert. Analyze prompt failures and suggest corrections. Respond only in JSON format.");
			areq.addMessage(sysMsg);

			OpenAIMessage userMsg = new OpenAIMessage();
			userMsg.setRole("user");
			userMsg.setContent(analysisPrompt);
			areq.addMessage(userMsg);

			OpenAIResponse resp = chat.chat(areq);
			if (resp == null) {
				logger.warn("ChatAutotuner: chat.chat() returned null");
				return null;
			}

			/// Extract response content
			BaseRecord msg = resp.get("message");
			if (msg != null) {
				return msg.get("content");
			}
			List<BaseRecord> choices = resp.get("choices");
			if (choices != null && !choices.isEmpty()) {
				BaseRecord choice = choices.get(0);
				BaseRecord message = choice.get("message");
				if (message != null) {
					return message.get("content");
				}
			}
			logger.warn("ChatAutotuner: No content found in LLM response");
			return null;
		} catch (Exception e) {
			logger.error("ChatAutotuner: LLM call failed: " + e.getMessage());
			return null;
		}
	}

	/// Result container for autotuning.
	public static class AutotuneResult {
		private String autotunedPromptName;
		private String analysisResponse;
		private List<PolicyViolation> violations;

		public AutotuneResult(String autotunedPromptName, String analysisResponse, List<PolicyViolation> violations) {
			this.autotunedPromptName = autotunedPromptName;
			this.analysisResponse = analysisResponse;
			this.violations = violations;
		}

		public String getAutotunedPromptName() { return autotunedPromptName; }
		public String getAnalysisResponse() { return analysisResponse; }
		public List<PolicyViolation> getViolations() { return violations; }

		public boolean isSuccess() { return autotunedPromptName != null; }
	}
}
