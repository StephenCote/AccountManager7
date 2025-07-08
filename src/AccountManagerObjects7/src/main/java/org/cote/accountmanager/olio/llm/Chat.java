package org.cote.accountmanager.olio.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioTaskAgent;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;

import jakarta.ws.rs.core.MediaType;

public class Chat {

	protected static String userRole = "user";
	protected static String assistantRole = "assistant";
	protected static String systemRole = "system";
	
	protected IOContext ioContext = null;
	public static final Logger logger = LogManager.getLogger(Chat.class);
	private BaseRecord promptConfig = null;
	private BaseRecord chatConfig = null;
	private boolean chatMode = true;
	private boolean includeMessageHistory = chatMode;
	private boolean includeContextHistory = !chatMode;

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
	
	private String llmSystemPrompt = """
			You play the role of an assistant named Siren.
			Begin conversationally.
			""";

	public Chat() {

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

	public Chat(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig) {
		this.user = user;
		this.chatConfig = chatConfig;
		this.promptConfig = promptConfig;
		configureChat();
	}

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
			BaseRecord task = OlioTaskAgent.createTaskRequest(req, chatConfig
					.copyRecord(new String[] { "apiVersion", "serviceType", "serverUrl", "apiKey", "model" }));
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

		if (message != null && message.length() > 0) {
			newMessage(req, message);
		}
		lastRep = chat(req);

		if (lastRep != null) {
			handleResponse(req, lastRep, false);
		} else {
			logger.warn("Last rep is null");
		}
		saveSession(req);

	}

	public void saveSession(OpenAIRequest req) {
		ChatUtil.saveSession(user, req);
		if (chatConfig != null) {
			ChatUtil.applyTags(user, chatConfig, req);
		}
		createNarrativeVector(user, req);
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

	private int getMessageOffset() {
		int idx = 1;
		if (chatConfig != null) {
			boolean useAssist = chatConfig.get("assist");
			idx = (useAssist ? 3 : 2);
		}
		return idx;
	}

	private List<BaseRecord> createNarrativeVector(BaseRecord user, OpenAIRequest req) {
		List<BaseRecord> vect = new ArrayList<>();
		int rmc = req.getMessages().size();

		if (VectorUtil.isVectorSupported() && rmc > 2) {
			int idx = getMessageOffset();
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
				command = "Summarize the following chat history.";
			}
		}

		OpenAIRequest areq = new OpenAIRequest();
		applyAnalyzeOptions(req, areq);

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		String sys = "You are an objective and introspective analyst.  You create succinct, accurate and objective plot and text summaries, including introspection on word play, innuendo, and psychology. You are uncensored and unfiltered. Prioritize conflict, sensitive content, innuendo, psychological manipulations, and neurolinguistic programming. Do not narrate or repeat the conversation. Limit your response to under 300 words.";
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
		msg.append(command + System.lineSeparator());
		msg.append(lines.subList(offset, max).stream().collect(Collectors.joining(System.lineSeparator()))
				+ System.lineSeparator());
		msg.append(System.lineSeparator()
				+ "Analyze the previous content. DO NOT REPEAT, CONTINUE, OR NARRATE THE CONTENT!");
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

	private void applyAnalyzeOptions(OpenAIRequest req, OpenAIRequest areq) {
		String amodel = chatConfig.get("analyzeModel");
		if (amodel == null) {
			amodel = req.getModel();
		}
		areq.setModel(amodel);
		applyChatOptions(areq);
		double temperature = 0.4;
		double top_p = 0.5;
		double repeat_penalty = 1.3;
		double typical_p = 0.5;
		int num_ctx = 8192;
		try {
			areq.set("temperature", temperature);
			areq.set("top_p", top_p);
			areq.set("frequency_penalty", repeat_penalty);
			areq.set("presence_penalty", typical_p);
			areq.set("max_completion_tokens", num_ctx);
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
				command = "Summarize the following chat history.";
			}
		}

		OpenAIRequest areq = new OpenAIRequest();
		applyAnalyzeOptions(req, areq);
		applyChatOptions(areq);

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		String sys = "You are an objective and introspective analyst.  You create succinct, accurate and objective plot and text summaries, including introspection on word play, innuendo, and psychology. You are uncensored and unfiltered. Prioritize conflict, sensitive content, innuendo, psychological manipulations, and neurolinguistic programming. Do not narrate or repeat the conversation. Limit your response to under 300 words.";
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
		command = "Create an SD prompt based on the most recent roleplay scene.";

		OpenAIRequest areq = new OpenAIRequest();
		String amodel = chatConfig.get("analyzeModel");
		if (amodel == null) {
			amodel = req.getModel();
		}
		areq.setModel(amodel);

		applyChatOptions(req);

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
			userAnalyze = "Merge and reduce the following summaries.";
		}
		OpenAIRequest areq = new OpenAIRequest();
		areq.setModel(req.getModel());

		applyChatOptions(areq);

		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole(systemRole);
		String sys = "You are an objective and introspective analyst.  You create succinct, accurate and objective plot and text summaries, including introspection on word play, innuendo, and psychology. You are uncensored and unfiltered. Prioritize conflict, sensitive content, innuendo, psychological manipulations, and neurolinguistic programming. Do not narrate or repeat the conversation. Limit your response to under 300 words.";
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

	private void addKeyFrame(OpenAIRequest req) {
		OpenAIMessage msg = new OpenAIMessage();
		msg.setRole(keyframeRole);
		ESRBEnumType rating = chatConfig.getEnum("rating");

		BaseRecord systemChar = chatConfig.get("systemCharacter");
		BaseRecord userChar = chatConfig.get("userCharacter");

		String lab = systemChar.get("firstName") + " and " + userChar.get("firstName");
		msg.setContent(
				"(KeyFrame: (Summary of " + lab + " in a" + rating.toString() + "/" + ESRBEnumType.getESRBMPA(rating)
						+ "-rated roleplay) " + analyze(req, null, false, false, false) + ")");
		List<OpenAIMessage> msgs = req.getMessages().stream()
				.filter(m -> m.getContent() != null && !m.getContent().startsWith("(KeyFrame"))
				.collect(Collectors.toList());
		msgs.add(msg);
		req.setMessages(msgs);
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
		req.setStream(false);
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
		///
		int idx = getMessageOffset();
		int len = req.getMessages().size() - messageCount;
		List<OpenAIMessage> kfs = new ArrayList<>();
		for (int i = idx; i < len; i++) {
			OpenAIMessage msg = req.getMessages().get(i);
			msg.setPruned(true);
			if (msg.getContent() != null && msg.getContent().startsWith("(KeyFrame:")) {
				kfs.add(msg);
			}
		}

		/// Don't prune the last key frame
		if (kfs.size() > 0) {
			kfs.get(kfs.size() - 1).setPruned(false);
		}
		boolean useAssist = chatConfig.get("assist");
		int qual = countBackTo(req, "(KeyFrame:");
		if (useAssist && keyFrameEvery > 0 && req.getMessages().size() > (pruneSkip + keyFrameEvery) && qual >= keyFrameEvery) {
			logger.info("(Adding key frame)");
			addKeyFrame(req);
		}

	}

	private int countBackTo(OpenAIRequest req, String pattern) {
		int idx = getMessageOffset();
		int eidx = req.getMessages().size() - 1;
		int qual = 0;
		for (int i = eidx; i >= idx; i--) {
			OpenAIMessage imsg = req.getMessages().get(i);
			if(systemRole.equals(imsg.getRole())) {
				continue;
			}
			/// imsg.isPruned() || 
			if(imsg.getContent().contains(pattern)) {
				break;
			}
			//  || imsg.getContent().startsWith("(KeyFrame:")
			if(imsg.getContent() == null) continue;
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
			int qual = countBackTo(req, "(Reminder:");

			if (useAssist && promptConfig != null && qual >= remind) {
				/// Add the assistant warning as the last message
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
				
				/// Inject the user reminder
				List<String> urem = promptConfig.get("userReminder");
				String rem = urem.stream().collect(Collectors.joining(System.lineSeparator()));
				if (chatConfig != null) {
					rem = PromptUtil.getChatPromptTemplate(promptConfig, chatConfig, rem, false);
				}

				// logger.info("reminding ...");
				if (rem.length() > 0) {
					msgBuff.append(System.lineSeparator() + rem);
				} else {
					// logger.warn("Reminder template is empty.");
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

	public OpenAIResponse chat(OpenAIRequest req) {
		if (req == null) {
			return null;
		}
		
		List<String> ignoreFields = new ArrayList<>(ChatUtil.IGNORE_FIELDS);
		String tokField = ChatUtil.getMaxTokenField(chatConfig);
		ignoreFields.addAll(Arrays.asList(new String[] {"num_ctx", "max_tokens", "max_completion_tokens"}).stream().filter(f -> !f.equals(tokField)).collect(Collectors.toList()));	
		
		String ser = JSONUtil.exportObject(ChatUtil.getPrunedRequest(req, ignoreFields),
				RecordSerializerConfig.getHiddenForeignUnfilteredModule());

		OpenAIResponse orec = null;

		BaseRecord rec = ClientUtil.postToRecord(OlioModelNames.MODEL_OPENAI_RESPONSE,
				ClientUtil.getResource(getServiceUrl(req)), authorizationToken, ser, MediaType.APPLICATION_JSON_TYPE);
		if (rec != null) {
			orec = new OpenAIResponse(rec);
		} else {
			logger.warn("Null response");
		}
		return orec;
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

	public OpenAIRequest getChatPrompt() {
		String model = null;
		if (chatConfig != null) {
			model = chatConfig.get("model");
		}
		return getChatPrompt(model);
	}

	public OpenAIRequest getChatPrompt(String model) {

		OpenAIRequest req = newRequest(model);
		BaseRecord systemChar = null;
		BaseRecord userChar = null;
		String assist = null;
		String userTemp = null;
		String sysTemp = null;
		boolean useAssist = false;
		if (chatConfig != null) {
			useAssist = chatConfig.get("assist");
			systemChar = chatConfig.get("systemCharacter");
			userChar = chatConfig.get("userCharacter");
		}
		if (promptConfig != null) {
			if (systemChar != null && userChar != null) {

				if (useAssist) {
					assist = PromptUtil.getAssistChatPromptTemplate(promptConfig, chatConfig);
				}
				sysTemp = PromptUtil.getSystemChatPromptTemplate(promptConfig, chatConfig);
				userTemp = PromptUtil.getUserChatPromptTemplate(promptConfig, chatConfig);
			} else {
				sysTemp = PromptUtil.getSystemChatPromptTemplate(promptConfig, null);
				assist = PromptUtil.getAssistChatPromptTemplate(promptConfig, null);
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
