package org.cote.accountmanager.olio.llm;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.NarrativeUtil;
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

public class Chat {
	
	protected IOContext ioContext = null;
	// protected OrganizationContext orgContext = null;
	// protected String organizationPath = "/Development";
	
	public static final Logger logger = LogManager.getLogger(Chat.class);
	//private String ollamaServer = "http://localhost:11434";
	
	private BaseRecord promptConfig = null;
	private BaseRecord chatConfig = null;
	private boolean chatMode = true;
	private boolean includeMessageHistory = chatMode;
	private boolean includeContextHistory = !chatMode;

	// private static int contextSize = 4096;
	private static int contextSize = 8192;
	private int pruneLength = contextSize / 2;
	//private int tokenBuffer = 756;
	private String sessionName = null;
	private String saveName = "chat.json";
	//private String analyzeName = "analyze.json";
	private BaseRecord user = null;
	private int pruneSkip = 1;
	private boolean formatOutput = false;
	private boolean includeScene = false;
	private boolean forceJailbreak = false;
	private int remind = 5;
	private int messageTrim = 10;
	private int keyFrameEvery = 10;
	
	private LLMServiceEnumType serviceType = LLMServiceEnumType.OPENAI;
	
	private String model = null;
	private String serverUrl = null;
	private String apiVersion = null;
	private String authorizationToken = null;
	
	/// Depending on the system where the library is running, System.lineSeparator() may include a carriage return. Depending where the LLM is running, it may be desired to strip the carriage return off.
	//private boolean stripCarriageReturn = true;

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
	
	private void configureChat() {
		if(chatConfig != null) {
			setServerUrl(chatConfig.get("serverUrl"));
			setApiVersion(chatConfig.get("apiVersion"));
			setAuthorizationToken(chatConfig.get("apiKey"));
			setModel(chatConfig.get("model"));
			setServiceType(chatConfig.getEnum("serviceType"));
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

	public void setSessionName(String sessionName) {
		this.sessionName = (sessionName != null ? ChatUtil.getSessionName(user, chatConfig, promptConfig, sessionName) : null);
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

	public void chatConsole(){
		chatConsole(newRequest(chatConfig.get("model")));
	}
	
	public void continueChat(OpenAIRequest req, String message){
		OpenAIResponse lastRep = null;	
		
		LineAction act = checkAction(req, message);
		if(act == LineAction.BREAK || act == LineAction.CONTINUE || act == LineAction.SAVE_AND_CONTINUE) {
			if(act == LineAction.SAVE_AND_CONTINUE && sessionName != null) {
				ChatUtil.saveSession(user, req, sessionName);
				createNarrativeVector(user, req, sessionName);
			}
			logger.info("Continue...");
			return;
		}

		if(message != null && message.length() > 0) {
			newMessage(req, message);
		}
		lastRep = chat(req);
		
		if(lastRep != null) {
			handleResponse(req, lastRep, false);
		}
		else {
			logger.warn("Last rep is null");
		}
		if(sessionName != null) {
			ChatUtil.saveSession(user, req, sessionName);
			createNarrativeVector(user, req, sessionName);
		}
	}
	
	private String getNarrativeForVector(OpenAIMessage msg) {

		if(chatConfig == null) {
			return msg.getContent();
		}
		
		BaseRecord vchar = chatConfig.get("systemCharacter");
		if("user".equals(msg.getRole())){
			vchar = chatConfig.get("userCharacter");
		}
		String ujobDesc = "";
		List<String> utrades = vchar.get(OlioFieldNames.FIELD_TRADES);
		if(utrades.size() > 0) {
			ujobDesc =" " + utrades.get(0).toLowerCase();
		}
		return (
			"* " + vchar.get(FieldNames.FIELD_FIRST_NAME) + " (" + vchar.get(FieldNames.FIELD_AGE) + " year-old "
				+ NarrativeUtil.getRaceDescription(vchar.get(OlioFieldNames.FIELD_RACE)) + " "
				+ vchar.get(FieldNames.FIELD_GENDER) + ujobDesc + ")*: "
			//+ System.lineSeparator()
			+ msg.getContent()
		);
	

	}
	private List<BaseRecord> createNarrativeVector(BaseRecord user, OpenAIRequest req, String sessionName) {
		List<BaseRecord> vect = new ArrayList<>();
		int rmc = req.getMessages().size();
		
		if(sessionName != null && VectorUtil.isVectorSupported() && rmc > 2) {
			
			String cnt = getNarrativeForVector(req.getMessages().get(rmc - 2)) + System.lineSeparator() + getNarrativeForVector(req.getMessages().get(rmc - 1));
			try {
				
				BaseRecord dat = ChatUtil.getSessionData(user, sessionName);
				ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_VECTOR_REFERENCE, dat);
				plist.parameter(FieldNames.FIELD_CHUNK, ChunkEnumType.WORD);
				plist.parameter(FieldNames.FIELD_CHUNK_COUNT, 1000);
				plist.parameter("chatConfig", chatConfig);
				plist.parameter("content", cnt);
				plist.parameter("promptConfig", promptConfig);
				plist.parameter("systemCharacter", chatConfig.get("systemCharacter"));
				plist.parameter("userCharacter", chatConfig.get("userCharacter"));
				BaseRecord vlist = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_VECTOR_CHAT_HISTORY_LIST, user, null, plist);
				vect = vlist.get(FieldNames.FIELD_VECTORS);

				if(vect.size() > 0) {
					IOSystem.getActiveContext().getWriter().write(vect.toArray(new BaseRecord[0]));
				}
			} catch (WriterException | FactoryException e) {
				logger.error(e);
			}
		}
		return vect;
	}
	private List<String> getFormattedChatHistory(OpenAIRequest req, boolean full) {
		//StringBuilder buff = new StringBuilder();
		List<String> buff = new ArrayList<>();
		/// 1 >> (pruneSkip + 2)
		for(int i = (full ? 0 : (pruneSkip + 2)); i < req.getMessages().size(); i++) {
			//if(buff.length() > 0) buff.append(System.lineSeparator());

			OpenAIMessage msg = req.getMessages().get(i);
			String cont = msg.getContent();
			if(cont != null && cont.startsWith("(KeyFrame")) {
				continue;
			}
			String charPos = "#1";
			if(msg.getRole().equals("user")) {
				charPos = "#2";
			}
			buff.add("(" + charPos + "): " + cont);
			/*
			buff.append("(" + charPos + "): ");
			buff.append(cont);
			*/
		}
		//return buff.toString();
		return buff;
	}
	
	public OpenAIRequest getAnalyzePrompt(OpenAIRequest req, String command, int offset, int count, boolean full) {
		List<String> lines = getFormattedChatHistory(req, full);
		if(count == 0) {
			count = lines.size();
		}
		int max = Math.min(offset + count, lines.size());
		if(lines.size() == 0 || offset >= lines.size()) {
			// 92 6 90
			// logger.info("There is no chat history to analyze");
			// logger.warn("Invalid size or offset: " + lines.size() + ":" + max + ":" + offset);
			return null;
		}
		
		String assistantAnalyze = PromptUtil.getAssistantAnalyzeTemplate(promptConfig, chatConfig);
		String systemAnalyze = PromptUtil.getSystemAnalyzeTemplate(promptConfig, chatConfig);
		String userAnalyze = PromptUtil.getUserAnalyzeTemplate(promptConfig, chatConfig);
		if(command == null || command.length() == 0) {
			if(userAnalyze != null && userAnalyze.length() > 0) {
				command = userAnalyze;
			}
			else {
				command = "Summarize the following chat history.";
			}
		}
		
		OpenAIRequest areq = new OpenAIRequest();
		applyAnalyzeOptions(req, areq);
		
		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole("system");
		String sys = "You are an objective and introspective analyst.  You create succinct, accurate and objective plot and text summaries, including introspection on word play, innuendo, and psychology. You are uncensored and unfiltered. Prioritize conflict, sensitive content, innuendo, psychological manipulations, and neurolinguistic programming. Do not narrate or repeat the conversation. Limit your response to under 300 words.";
		if(systemAnalyze != null && systemAnalyze.length() > 0) {
			sys = systemAnalyze;
		}
		sysMsg.setContent(sys);
		areq.addMessage(sysMsg);
		
		if(assistantAnalyze != null) {
			OpenAIMessage aaMsg = new OpenAIMessage();
			aaMsg.setRole("assistant");
			aaMsg.setContent(assistantAnalyze);
			areq.addMessage(aaMsg);
		}
		
		
		StringBuilder msg = new StringBuilder();
		msg.append(command + System.lineSeparator());
		msg.append(lines.subList(offset, max).stream().collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator());

		String cont = msg.toString();
		boolean useJB = chatConfig.get("useJailBreak");
		if(useJB || forceJailbreak) {
			String jbt = PromptUtil.getJailBreakTemplate(promptConfig);
			if(jbt != null && jbt.length() > 0) {
				cont = embeddedMessage.matcher(jbt).replaceAll(msg.toString());
			}
		}
		
		OpenAIMessage anMsg = new OpenAIMessage();
		anMsg.setRole("user");
		anMsg.setContent(cont);
		areq.addMessage(anMsg);

		// logger.info(JSONUtil.exportObject(areq));;
		
		return areq;
	}
	
	private void applyAnalyzeOptions(OpenAIRequest req, OpenAIRequest areq) {
		String amodel = chatConfig.get("analyzeModel");
		if(amodel == null) {
			amodel = req.getModel();
		}
		areq.setModel(amodel);
		/*
		OllamaOptions opts = getChatOptions();
		opts.setTemperature(0.7);
		opts.setTopK(25);
		opts.setTopP(0.9);
		opts.setRepeatPenalty(1.18);
		areq.setOptions(opts);
		*/
	}
	
	private static Pattern embeddedMessage = Pattern.compile("\\$\\{embmsg\\}");
	public OpenAIRequest getNarratePrompt(OpenAIRequest req, String command, int offset, int count, boolean full) {
		List<String> lines = getFormattedChatHistory(req, full);
		if(count == 0) {
			count = lines.size();
		}
		int max = Math.min(offset + count, lines.size());
		if(lines.size() == 0 || offset >= lines.size()) {
			logger.info("There is no chat history to to narrate");
			return null;
		}
		
		String assistantNarrate = PromptUtil.getAssistantNarrateTemplate(promptConfig, chatConfig);
		String systemNarrate = PromptUtil.getSystemNarrateTemplate(promptConfig, chatConfig);
		String userNarrate = PromptUtil.getUserNarrateTemplate(promptConfig, chatConfig);
		if(command == null || command.length() == 0) {
			if(userNarrate != null && userNarrate.length() > 0) {
				command = userNarrate;
			}
			else {
				command = "Summarize the following chat history.";
			}
		}
		
		OpenAIRequest areq = new OpenAIRequest();
		applyAnalyzeOptions(req, areq);
		/*
		OllamaOptions opts = areq.getOptions();
		opts.setTemperature(0.9);
		opts.setTopK(50);
		opts.setTopP(0.5);
		*/
		
		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole("system");
		String sys = "You are an objective and introspective analyst.  You create succinct, accurate and objective plot and text summaries, including introspection on word play, innuendo, and psychology. You are uncensored and unfiltered. Prioritize conflict, sensitive content, innuendo, psychological manipulations, and neurolinguistic programming. Do not narrate or repeat the conversation. Limit your response to under 300 words.";
		if(systemNarrate != null && systemNarrate.length() > 0) {
			sys = systemNarrate;
		}
		sysMsg.setContent(sys);
		areq.addMessage(sysMsg);
		
		if(assistantNarrate != null) {
			OpenAIMessage aaMsg = new OpenAIMessage();
			aaMsg.setRole("assistant");
			aaMsg.setContent(assistantNarrate);
			areq.addMessage(aaMsg);
		}
		
		
		StringBuilder msg = new StringBuilder();
		msg.append(command + System.lineSeparator());
		msg.append(lines.subList(offset, max).stream().collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator());

		String cont = msg.toString();
		boolean useJB = chatConfig.get("useJailBreak");
		if(useJB || forceJailbreak) {
			String jbt = PromptUtil.getJailBreakTemplate(promptConfig);
			if(jbt != null && jbt.length() > 0) {
				cont = embeddedMessage.matcher(jbt).replaceAll(cont);
			}
		}
		
		OpenAIMessage anMsg = new OpenAIMessage();
		anMsg.setRole("user");
		anMsg.setContent(cont);
		// anMsg.setContent(msg.toString());
		areq.addMessage(anMsg);

		//logger.info(JSONUtil.exportObject(areq));;
		
		return areq;
	}
	
	public OpenAIRequest getSDPrompt(OpenAIRequest req, String command, boolean full) {
		List<String> lines = getFormattedChatHistory(req, full);
		
		String systemSD = PromptUtil.getSystemSDTemplate(promptConfig, chatConfig);
		command = "Create an SD prompt based on the most recent roleplay scene.";
		
		OpenAIRequest areq = new OpenAIRequest();
		String amodel = chatConfig.get("analyzeModel");
		if(amodel == null) {
			amodel = req.getModel();
		}
		areq.setModel(amodel);
		/*
		OllamaOptions opts = getChatOptions();
		opts.setTemperature(0.9);
		opts.setTopK(75);
		opts.setTopP(0.9);
		opts.setRepeatPenalty(1.18);
		areq.setOptions(opts);
		*/
		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole("system");
		sysMsg.setContent(systemSD);
		areq.addMessage(sysMsg);
		
		StringBuilder msg = new StringBuilder();
		msg.append(command + System.lineSeparator());
		msg.append(lines.stream().collect(Collectors.joining(System.lineSeparator())));

		OpenAIMessage anMsg = new OpenAIMessage();
		anMsg.setRole("user");
		anMsg.setContent(msg.toString());
		areq.addMessage(anMsg);

		// logger.info(JSONUtil.exportObject(areq));;
		
		return areq;
	}
	
	public String SDPrompt(OpenAIRequest req, String command, boolean full) {
		List<String> resp = new ArrayList<>();
		OpenAIRequest oreq = getSDPrompt(req, command, full);

		logger.info("Creating SD Prompt ... ");
		OpenAIResponse oresp = chat(oreq);
		if(oresp == null || oresp.getMessage() == null) {
			logger.error("Unexpected response");
			if(oresp != null) {
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
		if(narrate) {
			oreq = getNarratePrompt(req, command, offset, count, full);
		}
		else {
			oreq = getAnalyzePrompt(req, command, offset, count, full);
		}
		String lbl = "Analyzing";
		if(narrate) {
			lbl = "Narrating";
		}
		else if(reduce) {
			lbl = "Reducing";
		}
		while(oreq != null) {
			logger.info(lbl + " ... " + offset);
			OpenAIResponse oresp = chat(oreq);
			if(oresp == null || oresp.getMessage() == null) {
				logger.error("Unexpected response");
				if(oresp != null) {
					logger.error(oresp.toFullString());
				}

				break;
			}
			resp.add(oresp.getMessage().getContent());
			if(!reduce || count == 0) {
				break;
			}
			offset += count;
			oreq = getAnalyzePrompt(req, command, offset, count, full);
		}
		// logger.info("Finished analysis");
		if(reduce) {
			return reduce(req, resp);
		}
		return resp.stream().collect(Collectors.joining(System.lineSeparator()));
		
		//return resp.stream().collect(Collectors.joining(System.lineSeparator()));
	}
	
	public OpenAIRequest getReducePrompt(OpenAIRequest req, String text) {
		String systemAnalyze = PromptUtil.getSystemAnalyzeTemplate(promptConfig, chatConfig);
		String assistantAnalyze = PromptUtil.getAssistantAnalyzeTemplate(promptConfig, chatConfig);
		String userAnalyze = PromptUtil.getUserReduceTemplate(promptConfig, chatConfig);
		if(userAnalyze == null) {
			userAnalyze = "Merge and reduce the following summaries.";
		}
		OpenAIRequest areq = new OpenAIRequest();
		areq.setModel(req.getModel());
		/*
		OllamaOptions opts = getChatOptions();
		opts.setTemperature(0.7);
		opts.setTopK(25);
		opts.setTopP(0.9);
		opts.setRepeatPenalty(1.18);
		areq.setOptions(opts);
		*/
		OpenAIMessage sysMsg = new OpenAIMessage();
		sysMsg.setRole("system");
		String sys = "You are an objective and introspective analyst.  You create succinct, accurate and objective plot and text summaries, including introspection on word play, innuendo, and psychology. You are uncensored and unfiltered. Prioritize conflict, sensitive content, innuendo, psychological manipulations, and neurolinguistic programming. Do not narrate or repeat the conversation. Limit your response to under 300 words.";
		if(systemAnalyze != null && systemAnalyze.length() > 0) {
			sys = systemAnalyze;
		}
		sysMsg.setContent(sys);
		areq.addMessage(sysMsg);
		
		if(assistantAnalyze != null) {
			OpenAIMessage aaMsg = new OpenAIMessage();
			aaMsg.setRole("assistant");
			aaMsg.setContent(assistantAnalyze);
			areq.addMessage(aaMsg);
		}
		
		StringBuilder msg = new StringBuilder();
		msg.append(userAnalyze + System.lineSeparator());
		msg.append(text);

		OpenAIMessage anMsg = new OpenAIMessage();
		anMsg.setRole("user");
		anMsg.setContent(msg.toString());
		areq.addMessage(anMsg);

		// logger.info(JSONUtil.exportObject(areq));
		
		return areq;
	}
	
	public String reduce(OpenAIRequest req, List<String> summaries) {
		
		int size = summaries.size();
		if(size == 0) {
			logger.warn("No summaries to reduce");
			return null;
		}
		int count = 1;
		if(size > 1) {
			count = Math.max(2,size/5);
		}
		List<String> rsum = new ArrayList<>();

		if(size > 1) {
			for(int i = 0; i < size; i += count){
				logger.info("Reducing ... " + i + " of " + summaries.size());
				String sumBlock = summaries.subList(i, Math.min(size,i + count)).stream().map(s -> "(Analysis Segment)" + System.lineSeparator() + s).collect(Collectors.joining(System.lineSeparator()));
				OpenAIRequest rreq = getReducePrompt(req, sumBlock);
				// logger.info(JSONUtil.exportObject(rreq));
				OpenAIResponse oresp = chat(rreq);
				if(oresp == null || oresp.getMessage() == null) {
					logger.warn("Invalid response");
					break;
				}
				// logger.info(oresp.getMessage().getContent());
				rsum.add(oresp.getMessage().getContent());
			}
		}
		else {
			rsum.add(summaries.get(0));
		}
		String summary = null;
		if(rsum.size() > 1 ) {
			logger.info("Summarizing ... " + rsum.size());
			String sumBlock = rsum.stream().collect(Collectors.joining(System.lineSeparator()));
			OpenAIResponse oresp = chat(getReducePrompt(req, sumBlock));
			if(oresp == null || oresp.getMessage() == null) {
				logger.warn("Invalid response");
			}
			else {
				// logger.info(oresp.getMessage().getContent());
				summary = oresp.getMessage().getContent();
			}
		}
		else {
			summary = rsum.stream().collect(Collectors.joining(System.lineSeparator()));
		}
		return summary;
	}

	enum LineAction{
		UNKNOWN,
		CONTINUE,
		SAVE_AND_CONTINUE,
		BREAK
	}
	
	private LineAction checkAction(OpenAIRequest req, String line) {
		LineAction oact = LineAction.UNKNOWN;
		if(line == null || line.equalsIgnoreCase("/bye")) {
			return LineAction.BREAK;
		}
		
		else if(line.startsWith("/jailbreak")) {
			forceJailbreak = !forceJailbreak;
			logger.info("Jailbreak " + (forceJailbreak ? "enabled" : "disabled"));
			oact = LineAction.CONTINUE;
		}
		else if(line.startsWith("/analyzeAll")) {
			logger.info(analyze(req, line.substring(11).trim(), false, false, true));
			oact = LineAction.CONTINUE;
		}
		else if(line.startsWith("/analyze")) {
			logger.info(analyze(req, line.substring(8).trim(), false, false, false));
			oact = LineAction.CONTINUE;
		}
		else if(line.startsWith("/reduceAll")) {
			logger.info(analyze(req, line.substring(10).trim(), false, true, true));
			oact = LineAction.CONTINUE;
		}
		else if(line.startsWith("/reduce")) {
			logger.info(analyze(req, line.substring(7).trim(), false, true, false));
			oact = LineAction.CONTINUE;
		}
		else if(line.startsWith("/narrateAll")) {
			logger.info(analyze(req, line.substring(11).trim(), true, false, true));
			oact = LineAction.CONTINUE;
		}
		else if(line.startsWith("/narrate")) {
			logger.info(analyze(req, line.substring(8).trim(), true, false, false));
			oact = LineAction.CONTINUE;
		}
		else if(line.startsWith("/sdprompt")) {
			logger.error("Feature currently disabled");
			// logger.info(SDPrompt(req, line.substring(9).trim(), false));
			oact = LineAction.CONTINUE;
		}
		else if(line.equals("/look")) {
			
			String char1 = NarrativeUtil.describe(null, chatConfig.get("systemCharacter"));
			String char2 = NarrativeUtil.describe(null, chatConfig.get("userCharacter"));
			logger.info("Character 1: " + char1);
			logger.info("Character 2: " + char2);
			if(req != null && req.getMessages().size() > 3) {
				OpenAIResponse oresp = chat(getNarratePrompt(req, "Write a brief narrative description of the following two characters. Include all physical, behavioral, and personality details." + System.lineSeparator() + char1 + System.lineSeparator() + char2, 0, 0, false));
				if(oresp != null && oresp.getMessage() != null) {
					logger.info(oresp.getMessage().getContent());
				}
			}
			oact = LineAction.CONTINUE;
		}
		else if(line.equals("/story")) {
			String snar = chatConfig.get("systemNarrative.sceneDescription");
			String unar = chatConfig.get("userNarrative.sceneDescription");
			if(snar != null && unar != null) {
				logger.info(snar);
				logger.info(unar);
			}
			oact = LineAction.CONTINUE;
		}
		else if(line.equals("/next")) {
			BaseRecord nextEp = PromptUtil.moveToNextEpisode(chatConfig);
			if(req.getMessages().size() == 0) {
				logger.error("No system message to replace!");
			}
			else if(nextEp != null) {
				if(req.getMessages().size() > 4) {
					logger.info("Summarizing current episode...");
					OpenAIResponse oresp = chat(getNarratePrompt(req, "Write a brief narrative description of the following content with respect to the described episode. Include all physical, behavioral, and personality details.", 0, 0, false));
					String summary = null;
					if(oresp != null && oresp.getMessage() != null) {
						summary = oresp.getMessage().getContent();
						logger.info("Summary: " + summary);
					}
					nextEp.setValue("summary", summary);
				}
				else {
					// logger.info("Skipping summary");
				}
				IOSystem.getActiveContext().getAccessPoint().update(user, chatConfig.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_GROUP_ID, "episodes"}));
				String newSys = PromptUtil.getSystemChatPromptTemplate(promptConfig, chatConfig);
				req.getMessages().get(0).setContent(newSys);
				logger.info("Begin episode #" + nextEp.get("number") + " " + nextEp.get("theme"));
			}
			else {
				logger.warn("No further episodes");
			}
			oact = LineAction.SAVE_AND_CONTINUE;

		}
		else if(line.equals("/prune")) {
			prune(req, true);
			oact = LineAction.CONTINUE;
		}
		else if(line.equals("/prompt")) {
			if (line.length() > 7) {
				String newPrompt = line.substring(8).trim();
				req.getMessages().get(0).setContent(newPrompt);
				logger.info("New prompt: " + newPrompt);
			} else {
				logger.info("Current prompt: " + req.getMessages().get(0).getContent());
			}
			// if(chatMode && req.getMessages().size() > 0) {
				//req.getMessages().get(0).setContent(llmSystemPrompt.trim());
			/*
			}
			
			if(!chatMode) {
				req.setSystem(llmSystemPrompt.trim());
			}
			*/
			oact = LineAction.CONTINUE;
		}

		return oact;
	}
	
	public void chatConsole(OpenAIRequest req){
		BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
		try{
			AuditUtil.setLogToConsole(false);
			String prompt = "> ";
			String line = "";
			OpenAIResponse lastRep = null;
			while (line != null && line.equalsIgnoreCase("/quit") == false && line.equalsIgnoreCase("/exit") == false && line.equalsIgnoreCase("/bye") == false) {
				if(lastRep == null && req.getMessages().size() > 0) {
					logger.info("Initializing ...");
					String iscene = chatConfig.get("userNarrative.interactionDescription");
					String cscene = chatConfig.get("scene");
					if(cscene == null) {
						cscene = iscene;
					}
					if(cscene != null) {
						logger.info(cscene);
					}
					else if(!"random".equals(chatConfig.get("setting"))) {
						logger.info((String)chatConfig.get("setting"));
					}
					BaseRecord nextEp = PromptUtil.getNextEpisode(chatConfig);
					if(nextEp != null) {
						logger.info("Episode #" + nextEp.get("number") + " " + nextEp.get("theme"));
					}
					lastRep = chat(req);
					if(lastRep != null) {
						handleResponse(req, lastRep, true);
					}
					else {
						logger.warn("Null response");
					}
				}
				System.out.print(prompt);
				line = is.readLine();
				
				LineAction act = checkAction(req, line);
				if(act == LineAction.BREAK) {
					break;
				}
				if(act == LineAction.CONTINUE || act == LineAction.SAVE_AND_CONTINUE) {
					continue;
				}
				
				if(line.equals("/new")) {
					req = newRequest(chatConfig.get("model"));
					continue;
				}

				if(line.equals("/truncate")) {
					lastRep = null;
					boolean useAssist = chatConfig.get("assist");
					req.setMessages(req.getMessages().subList(0, useAssist ? 3 : 2));
					continue;
				}

				if(line.equals("/save")) {
					System.out.println("Saving ...");
					FileUtil.emitFile("./" + saveName, JSONUtil.exportObject(req));
					continue;
				}
				if(line.equals("/load")) {
					System.out.println("Loading ...");
					String sav = FileUtil.getFileAsString("./" + saveName);
					if(sav != null && sav.length() > 0) {
						req = OpenAIRequest.importRecord(sav);
					}
					continue;
				}
				// System.out.println("'" + line + "'");
				
				//if(chatMode) {
					newMessage(req, line);
				/*
				}
				else {
					req.setPrompt(line);
				}
				*/
				/// System.out.println(JSONUtil.exportObject(req));
				lastRep = chat(req);
				if(lastRep != null) {
					handleResponse(req, lastRep, true);
				}
				if(sessionName != null) {
					ChatUtil.saveSession(user, req, sessionName);
					createNarrativeVector(user, req, sessionName);
					
				}
			}
			AuditUtil.setLogToConsole(true);
		    is.close();
		}
		catch(IOException e){
			logger.error(e.getMessage());
		} 
	}
	
	private void addKeyFrame(OpenAIRequest req) {
		OpenAIMessage msg = new OpenAIMessage();
		msg.setRole("assistant");
		ESRBEnumType rating = chatConfig.getEnum("rating");

		BaseRecord systemChar = chatConfig.get("systemCharacter");
		BaseRecord userChar = chatConfig.get("userCharacter");

		String lab = systemChar.get("firstName") + " and " + userChar.get("firstName");
		msg.setContent("(KeyFrame: (Summary of " + lab + " in a" + rating.toString() + "/" + ESRBEnumType.getESRBMPA(rating) + "-rated roleplay) " + analyze(req, null, false, false, false) + ")");
		List<OpenAIMessage> msgs = req.getMessages().stream().filter(m -> m.getContent() != null && !m.getContent().startsWith("(KeyFrame")).collect(Collectors.toList());
		msgs.add(msg);
		req.setMessages(msgs);
	}

	private void handleResponse(OpenAIRequest req, OpenAIResponse rep, boolean emitResponse) {
		List<BaseRecord> msgs = new ArrayList<>();
		BaseRecord msg = rep.get("message");
		if(msg == null) {
			List<BaseRecord> choices = rep.get("choices");
			for(BaseRecord c : choices) {
				BaseRecord m = c.get("message");
				msgs.add(m);
			}
		}
		else {
			msgs.add(msg);
		}
		for(BaseRecord m : msgs) {
			if(includeMessageHistory) {
				req.addMessage(new OpenAIMessage(m));
			}
			String cont = m.get("content");
			if(emitResponse && cont != null) {
				System.out.println(formatOutput(cont));
			}
		}
		/*
		}
		else if(serviceType == LLMServiceEnumType.OLLAMA) {
			if(rep.getMessage() != null) {
				if(includeMessageHistory) {
					req.addMessage(rep.getMessage());
				}
				String cont = rep.getMessage().getContent();
				if(emitResponse && cont != null) {
					cont = cont.trim().replaceAll("^assistant[:]*\s*", "");
					int idx = cont.indexOf("@@@");
					if(idx > -1) {
						cont = cont.substring(idx);
					}
					System.out.println(formatOutput(cont));
				}
			}
		}
		*/
	}

	private String formatOutput(String input) {
		if(!formatOutput) {
			return input;
		}
		String output = input.replace('’', '\'');
		return output;
	}
	
	public OpenAIRequest newRequest(String model) {
		OpenAIRequest req = new OpenAIRequest();
		req.setModel(model);
		req.setStream(false);
		//if(chatMode) {
			if(llmSystemPrompt != null) {
				OpenAIMessage msg = new OpenAIMessage();
				msg.setRole("system");
				msg.setContent(llmSystemPrompt.trim());
				List<OpenAIMessage> msgs = req.get("messages");
				msgs.add(msg);
			}
		/*
		}
		else {
			req.setSystem(llmSystemPrompt.trim());
		}
		*/
		return req;
	}
	
	private void pruneCount(OpenAIRequest req, int messageCount) {
		boolean enablePrune = chatConfig.get("prune");
		if(messageCount <= 0 || !enablePrune || !chatMode) {
			return;
		}
		
		/// Target count = system + pruneSkip
		///
		int idx = pruneSkip + 1;
		int len = req.getMessages().size() - messageCount;
		int kfc = 0;
		for(int i = idx; i < len; i++) {
			OpenAIMessage msg = req.getMessages().get(i);
			msg.setPruned(true);
			if(msg.getContent() != null && msg.getContent().startsWith("(KeyFrame:")) {
				kfc++;
			}
		}
		
		if(req.getMessages().size() > (pruneSkip + keyFrameEvery) && (req.getMessages().size() - idx - kfc) % keyFrameEvery == 0) {
			logger.info("(Adding key frame)");
			addKeyFrame(req);
		}
		

	}
	
	private void prune(OpenAIRequest req, boolean force) {
		boolean enablePrune = chatConfig.get("prune");
		if((!force && !enablePrune) || !chatMode) {
			return;
		}
		if(req.getMessages().size() <= pruneSkip) {
			return;
		}
		OpenAIMessage ctxMsg = req.getMessages().get(0);
		/// Including the system prompt
		///
		
		int curLength = 0;
		//ctxMsg.getContent().split("\\W+").length;
		//logger.info("Sys prompt length: " + curLength);
		int marker = -1;
		//int markerAhead = -1;
		/// Skip the first message used to set any context prompt
		///
		/// pruneSkip
		for(int i = 0; i < req.getMessages().size(); i++) {
			OpenAIMessage msg = req.getMessages().get(i);
			if(msg.isPruned()) continue;
			if(msg.getContent() != null) {
				curLength += msg.getContent().split("\\W+").length;
			}
			// logger.info("Tokens: " + curLength);
			if(i < pruneSkip) {
				continue;
			}
			else if(i == pruneSkip) {
				// logger.info("Front load: " + curLength);
			}
			if(marker == -1 && curLength >= pruneLength) {
				// System.out.println("(Prune from " + pruneSkip + " prior to " + i + " of " + req.getMessages().size() + " / Token Size = " + curLength + ")");
				marker = i - 1;
				//break;
			}
			/*
			if(markerAhead == -1 && curLength >= (tokenLength + tokenBuffer)) {
				// System.out.println("(Prune buffer from " + pruneSkip + " prior to " + i + " of " + req.getMessages().size() + " / Token Size = " + curLength + ")");
				markerAhead = i - 1;
				//break;
			}
			*/

		}
		
		logger.info("Found " + curLength + " tokens. " + (marker > -1 ? "Will":"Won't") + " prune.");
		/*
		if(force) {
			System.out.println("Found " + curLength + " tokens. " + (marker > -1 ? "Will":"Won't") + " prune.");
		}
		*/
		if(marker > -1 && curLength >= contextSize) {
			
			int im = marker; //Math.max(marker, markerAhead);
			for(int i = pruneSkip; i <= im; i++) {
				req.getMessages().get(i).setPruned(true);
			}
			logger.info("(Adding key frame)");
			addKeyFrame(req);
			/*
			List<OpenAIMessage> msgs = Arrays.asList(Arrays.copyOfRange(req.getMessages().toArray(new OpenAIMessage[0]), marker, req.getMessages().size()));
			req.getMessages().clear();
			req.addMessage(ctxMsg);
			req.addMessageAll(msgs);
			*/
		}
	}

	public OpenAIMessage newMessage(OpenAIRequest req, String message) {
		return newMessage(req, message, "user");
	}
	public OpenAIMessage newMessage(OpenAIRequest req, String message, String role) {
		OpenAIMessage msg = new OpenAIMessage();
		msg.setRole(role);
		StringBuilder msgBuff = new StringBuilder();
		msgBuff.append(message);
		if(chatConfig != null && role.equals("user")) {
			ESRBEnumType rating = chatConfig.getEnum("rating"); 
			boolean useAssist = chatConfig.get("assist");
			int mark = remind + (useAssist ? 1 : 0);
			// logger.info(useAssist + " / " + remind + " / " + mark + " / " + req.getMessages().size() + " / " + rating.toString());
			// && (rating == ESRBEnumType.AO || rating == ESRBEnumType.RC)
			if(promptConfig != null && (req.getMessages().size() % remind) == 0 ) {
				
				/// Inject the assistant warning into the last message
				if(req.getMessages().size() > 0)
				{
					OpenAIMessage amsg = req.getMessages().get(req.getMessages().size() - 1);
					if(amsg.getRole().equals("assistant")) {
						List<String> arem = promptConfig.get("assistantReminder");
						String rem = arem.stream().collect(Collectors.joining(System.lineSeparator()));
						if(chatConfig != null) {
							rem = PromptUtil.getChatPromptTemplate(promptConfig, chatConfig, rem, false);
						}
						amsg.setContent(amsg.getContent() + System.lineSeparator() + rem);
						
					}
				}
				/// Inject the user reminder
				
				List<String> urem = promptConfig.get("userReminder");
				String rem = urem.stream().collect(Collectors.joining(System.lineSeparator()));
				if(chatConfig != null) {
					rem = PromptUtil.getChatPromptTemplate(promptConfig, chatConfig, rem, false);
				}
				// logger.info("reminding ...");
				if(rem.length() > 0) {
					msgBuff.append(System.lineSeparator() + rem);
				}
				else {
					logger.warn("Reminder template is empty.");
				}
			}
			else {
				// logger.info("Don't remind because " + (chatConfig == null) + "/" + (promptConfig == null) + "/" + (req.getMessages().size() % remind));
			}
		}
		msg.setContent(msgBuff.toString());

		if(chatConfig != null) {
			pruneCount(req, messageTrim);
		}

		req.addMessage(msg);

		return msg;
	}
	
	public OpenAIRequest getPrunedRequest(OpenAIRequest inReq) {
		if(promptConfig == null) {
			return inReq;
		}
		OpenAIRequest outReq = new OpenAIRequest();
		// outReq.setContext(inReq.getContext());
		outReq.setModel(inReq.getModel());
		// outReq.setOptions(inReq.getOptions());
		// outReq.setPrompt(inReq.getPrompt());
		// outReq.setSystem(inReq.getSystem());
		String jbt = PromptUtil.getJailBreakTemplate(promptConfig);
		boolean useJB = (forceJailbreak || chatConfig != null && (boolean)chatConfig.get("useJailBreak"));

		outReq.addMessage(inReq.getMessages().stream().filter(m -> (m.isPruned()==false))
		.collect(Collectors.toList()));
		
		return outReq;
	}

	public OpenAIResponse chat(OpenAIRequest req) {
		if(req == null) {
			return null;
		}
		String ser = JSONUtil.exportObject(getPrunedRequest(req), RecordSerializerConfig.getHiddenForeignUnfilteredModule());
		OpenAIResponse orec = null;
		BaseRecord rec = ClientUtil.postToRecord(OlioModelNames.MODEL_OPENAI_RESPONSE, ClientUtil.getResource(getServiceUrl(req)), authorizationToken, ser, MediaType.APPLICATION_JSON_TYPE);
		if(rec != null) {
			orec = new OpenAIResponse(rec);
		}
		else {
			logger.warn("Null response");
		}
		return orec;
	}
	
	public String getServiceUrl(OpenAIRequest req) {
		
		String url = null;
		if(serviceType == LLMServiceEnumType.OLLAMA) {
			url = serverUrl + "/api/" + (chatMode ? "chat" : "generate");
		}
		else if (serviceType == LLMServiceEnumType.OPENAI) {
			url = serverUrl + "/openai/deployments/" + req.getModel() + "/chat/completions" + (apiVersion != null ? "?api-version=" + apiVersion : "");
		}
		return url;
	}
	
	


	
	public OpenAIRequest getChatPrompt() {
		String model = null;
		if(chatConfig != null) {
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
		if(chatConfig != null) {
			useAssist = chatConfig.get("assist");
			systemChar = chatConfig.get("systemCharacter");
			userChar = chatConfig.get("userCharacter");
		}
		if(promptConfig != null) {
			if(systemChar != null && userChar != null) {
				
				if(useAssist) {
					assist = PromptUtil.getAssistChatPromptTemplate(promptConfig, chatConfig);
				}
				sysTemp = PromptUtil.getSystemChatPromptTemplate(promptConfig, chatConfig);
				userTemp = PromptUtil.getUserChatPromptTemplate(promptConfig, chatConfig);
				//setAnnotation(getAnnotateChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt));
				// setAssist(getAssistChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt));
			}
			else {
				sysTemp = PromptUtil.getSystemChatPromptTemplate(promptConfig, null);
				assist = PromptUtil.getAssistChatPromptTemplate(promptConfig, null);
				userTemp = PromptUtil.getUserChatPromptTemplate(promptConfig, null);
				
			}
		}
		
		setLlmSystemPrompt(sysTemp);
		req = newRequest(model);
		setPruneSkip(2);
		newMessage(req, userTemp);
		if(assist != null && assist.length() > 0) {
			setPruneSkip(3);
			newMessage(req, assist, "assistant");
		}
		
		// req.setOptions(getChatOptions(chatConfig));

		return req;
	}
	/*
	public static OllamaOptions getChatOptions() {
		return getChatOptions(null);
	}
	
	public static OllamaOptions getChatOptions(BaseRecord cfg) {
		OllamaOptions opts = new OllamaOptions();
		BaseRecord cfgOpts = null;
		if(cfg != null) {
			cfgOpts = cfg.get("chatOptions");
		}
		if(cfgOpts != null) {
			opts.setTopK(cfgOpts.get("top_k"));
			opts.setTopP(cfgOpts.get("top_p"));
			opts.setMinP(cfgOpts.get("min_p"));
			opts.setTypicalP(cfgOpts.get("typical_p"));
			opts.setRepeatLastN(cfgOpts.get("repeat_last_n"));
			opts.setTemperature(cfgOpts.get("temperature"));
			opts.setRepeatPenalty(cfgOpts.get("repeat_penalty"));
			opts.setNumCtx(cfgOpts.get("num_ctx"));
			opts.setNumGpu(cfgOpts.get("num_gpu"));
		}
		else {
			opts.setNumGpu(32);
			opts.setNumCtx(contextSize);
			opts.setRepeatPenalty(1.3);
			opts.setRepeatLastN(512);
			opts.setTemperature(0.9);
			opts.setTopP(0.5);
			opts.setTopK(50);
		}
		return opts;
	}
	*/
}
