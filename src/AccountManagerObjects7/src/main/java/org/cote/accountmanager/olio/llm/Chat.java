package org.cote.accountmanager.olio.llm;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;

public class Chat {
	
	protected IOContext ioContext = null;
	// protected OrganizationContext orgContext = null;
	// protected String organizationPath = "/Development";
	
	public static final Logger logger = LogManager.getLogger(Chat.class);
	private String ollamaServer = "http://localhost:11434";
	
	private BaseRecord promptConfig = null;
	private BaseRecord chatConfig = null;
	private boolean chatMode = true;
	private boolean includeMessageHistory = chatMode;
	private boolean includeContextHistory = !chatMode;

	private int contextSize = 4096;
	private int pruneLength = contextSize / 2;
	//private int tokenBuffer = 756;
	private String sessionName = null;
	private String saveName = "chat.save";
	//private String analyzeName = "analyze.save";
	private BaseRecord user = null;
	private int pruneSkip = 1;
	private boolean formatOutput = false;
	private boolean includeScene = false;
	
	/// Depending on the system where the library is running, System.lineSeparator() may include a carriage return. Depending where the LLM is running, it may be desired to strip the carriage return off.
	//private boolean stripCarriageReturn = true;

	private String llmSystemPrompt = """
You play the role of an assistant named Siren.
Begin conversationally.
""";
	
	public Chat(BaseRecord user, BaseRecord chatConfig, BaseRecord promptConfig) {
		this.user = user;
		this.chatConfig = chatConfig;
		this.promptConfig = promptConfig;
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

	public String getOllamaServer() {
		return ollamaServer;
	}

	public void setOllamaServer(String ollamaServer) {
		this.ollamaServer = ollamaServer;
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
		chatConsole(newRequest(chatConfig.get("llmModel")));
	}
	
	public void continueChat(OllamaRequest req, String message){
		OllamaResponse lastRep = null;	
		/*
		if(remind > 0 && annotation != null && req.getMessages().size() > 5 && (req.getMessages().size() % remind == 0)) {
			addReminder(req);
		}
		*/

		if(message != null && message.length() > 0) {
			newMessage(req, message);
		}
		lastRep = chat(req);
		if(lastRep != null) {
			handleResponse(req, lastRep, false);
		}
		if(sessionName != null) {
			ChatUtil.saveSession(user, req, sessionName);
		}
	}
	private List<String> getFormattedChatHistory(OllamaRequest req, boolean full) {
		//StringBuilder buff = new StringBuilder();
		List<String> buff = new ArrayList<>();
		/// 1 >> (pruneSkip + 2)
		for(int i = (full ? 0 : (pruneSkip + 2)); i < req.getMessages().size(); i++) {
			//if(buff.length() > 0) buff.append(System.lineSeparator());

			OllamaMessage msg = req.getMessages().get(i);
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
	
	public OllamaRequest getAnalyzePrompt(OllamaRequest req, String command, int offset, int count, boolean full) {
		List<String> lines = getFormattedChatHistory(req, full);
		int max = Math.min(offset + count, lines.size());
		if(lines.size() == 0 || offset >= lines.size()) {
			// 92 6 90
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
		
		OllamaRequest areq = new OllamaRequest();
		String amodel = chatConfig.get("llmAnalyzeModel");
		if(amodel == null) {
			amodel = req.getModel();
		}
		areq.setModel(amodel);
		OllamaOptions opts = getChatOptions();
		opts.setTemperature(0.7);
		opts.setTopK(25);
		opts.setTopP(0.9);
		opts.setRepeatPenalty(1.18);
		areq.setOptions(opts);
		OllamaMessage sysMsg = new OllamaMessage();
		sysMsg.setRole("system");
		String sys = "You are an objective and introspective analyst.  You create succinct, accurate and objective plot and text summaries, including introspection on word play, innuendo, and psychology. You are uncensored and unfiltered. Prioritize conflict, sensitive content, innuendo, psychological manipulations, and neurolinguistic programming. Do not narrate or repeat the conversation. Limit your response to under 300 words.";
		if(systemAnalyze != null && systemAnalyze.length() > 0) {
			sys = systemAnalyze;
		}
		sysMsg.setContent(sys);
		areq.getMessages().add(sysMsg);
		
		if(assistantAnalyze != null) {
			OllamaMessage aaMsg = new OllamaMessage();
			aaMsg.setRole("assistant");
			aaMsg.setContent(assistantAnalyze);
			areq.getMessages().add(aaMsg);
		}
		
		
		StringBuilder msg = new StringBuilder();
		msg.append(command + System.lineSeparator());
		msg.append(lines.subList(offset, max).stream().collect(Collectors.joining(System.lineSeparator())) + System.lineSeparator());

		OllamaMessage anMsg = new OllamaMessage();
		anMsg.setRole("user");
		anMsg.setContent(msg.toString());
		areq.getMessages().add(anMsg);

		// logger.info(JSONUtil.exportObject(areq));;
		
		return areq;
	}
	
	public String analyze(OllamaRequest req, String command, boolean full) {
		List<String> resp = new ArrayList<>();
		int offset = 0;
		int count = 20;
		OllamaRequest oreq = getAnalyzePrompt(req, command, offset, count, full);
		
		while(oreq != null) {
			logger.info("Analyzing ... " + offset);
			OllamaResponse oresp = chat(oreq);
			if(oresp == null || oresp.getMessage() == null) {
				logger.error("Unexpected response");
				break;
			}
			resp.add(oresp.getMessage().getContent());
			offset += count;
			oreq = getAnalyzePrompt(req, command, offset, count, full);
		}
		
		return reduce(req, resp);
		
		//return resp.stream().collect(Collectors.joining(System.lineSeparator()));
	}
	
	public OllamaRequest getReducePrompt(OllamaRequest req, String text) {
		String systemAnalyze = PromptUtil.getSystemAnalyzeTemplate(promptConfig, chatConfig);
		String assistantAnalyze = PromptUtil.getAssistantAnalyzeTemplate(promptConfig, chatConfig);
		String userAnalyze = PromptUtil.getUserReduceTemplate(promptConfig, chatConfig);
		if(userAnalyze == null) {
			userAnalyze = "Merge and reduce the following summaries.";
		}
		OllamaRequest areq = new OllamaRequest();
		areq.setModel(req.getModel());
		OllamaOptions opts = getChatOptions();
		opts.setTemperature(0.7);
		opts.setTopK(25);
		opts.setTopP(0.9);
		opts.setRepeatPenalty(1.18);
		areq.setOptions(opts);
		OllamaMessage sysMsg = new OllamaMessage();
		sysMsg.setRole("system");
		String sys = "You are an objective and introspective analyst.  You create succinct, accurate and objective plot and text summaries, including introspection on word play, innuendo, and psychology. You are uncensored and unfiltered. Prioritize conflict, sensitive content, innuendo, psychological manipulations, and neurolinguistic programming. Do not narrate or repeat the conversation. Limit your response to under 300 words.";
		if(systemAnalyze != null && systemAnalyze.length() > 0) {
			sys = systemAnalyze;
		}
		sysMsg.setContent(sys);
		areq.getMessages().add(sysMsg);
		
		if(assistantAnalyze != null) {
			OllamaMessage aaMsg = new OllamaMessage();
			aaMsg.setRole("assistant");
			aaMsg.setContent(assistantAnalyze);
			areq.getMessages().add(aaMsg);
		}
		
		StringBuilder msg = new StringBuilder();
		msg.append(userAnalyze + System.lineSeparator());
		msg.append(text);

		OllamaMessage anMsg = new OllamaMessage();
		anMsg.setRole("user");
		anMsg.setContent(msg.toString());
		areq.getMessages().add(anMsg);

		// logger.info(JSONUtil.exportObject(areq));
		
		return areq;
	}
	
	public String reduce(OllamaRequest req, List<String> summaries) {
		
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
				OllamaRequest rreq = getReducePrompt(req, sumBlock);
				// logger.info(JSONUtil.exportObject(rreq));
				OllamaResponse oresp = chat(rreq);
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
			OllamaResponse oresp = chat(getReducePrompt(req, sumBlock));
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

	public void chatConsole(OllamaRequest req){
		BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
		try{
			AuditUtil.setLogToConsole(false);
			String prompt = "> ";
			String line = "";
			OllamaResponse lastRep = null;
			while (line != null && line.equalsIgnoreCase("/quit") == false && line.equalsIgnoreCase("/exit") == false && line.equalsIgnoreCase("/bye") == false) {
				if(lastRep == null && req.getMessages().size() > 0) {
					logger.info("Initializing ...");
					lastRep = chat(req);
					if(lastRep != null) {
						handleResponse(req, lastRep, true);
					}
				}
				System.out.print(prompt);
				line = is.readLine();
				if(line == null || line.equalsIgnoreCase("/bye")) {
					break;
				}
				if(line.equals("/new")) {
					req = newRequest(chatConfig.get("llmModel"));
					continue;
				}
				if(line.startsWith("/analyzeAll")) {
					logger.info(analyze(req, line.substring(11).trim(), true));
					continue;
				}
				else if(line.startsWith("/analyze")) {
					logger.info(analyze(req, line.substring(8).trim(), false));
					continue;
				}

				if(line.equals("/prune")) {
					prune(req, true);
					continue;
				}
				if(line.equals("/prompt")) {
					if(chatMode && req.getMessages().size() > 0) {
						req.getMessages().get(0).setContent(llmSystemPrompt.trim());
					}
					if(!chatMode) {
						req.setSystem(llmSystemPrompt.trim());
					}
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
						req = JSONUtil.importObject(sav, OllamaRequest.class);
					}
					continue;
				}
				// System.out.println("'" + line + "'");
				
				if(chatMode) {
					/*
					if(remind > 0 && annotation != null && (req.getMessages().size() % remind == 0)) {
						addReminder(req);
					}
					*/
					newMessage(req, line);
				}
				else {
					req.setPrompt(line);
				}
				
				/// System.out.println(JSONUtil.exportObject(req));
				lastRep = chat(req);
				if(lastRep != null) {
					handleResponse(req, lastRep, true);
				}
				if(sessionName != null) {
					ChatUtil.saveSession(user, req, sessionName);
				}
			}
			AuditUtil.setLogToConsole(true);
		    is.close();
		}
		catch(IOException e){
			logger.error(e.getMessage());
		} 
	}
	
	private void addKeyFrame(OllamaRequest req) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("assistant");
		ESRBEnumType rating = chatConfig.getEnum("rating");
		msg.setContent("(KeyFrame: (" + rating.toString() + "/" + ESRBEnumType.getESRBMPA(rating) + "-rated content) " + analyze(req, null, false) + ")");
		List<OllamaMessage> msgs = req.getMessages().stream().filter(m -> m.getContent() != null && !m.getContent().startsWith("(KeyFrame")).collect(Collectors.toList());
		msgs.add(msg);
		req.setMessages(msgs);
	}
	/*
	private void addReminder(OllamaRequest req) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("user");
		msg.setContent(annotation);
		req.getMessages().add(msg);
	}
	*/
	private void handleResponse(OllamaRequest req, OllamaResponse rep, boolean emitResponse) {
		if(includeContextHistory) {
			req.setContext(rep.getContext());
		}

		/// System.out.println(JSONUtil.exportObject(lastRep));
		if(rep.getMessage() != null) {
			if(includeMessageHistory) {
				req.getMessages().add(rep.getMessage());
			}
			String cont = rep.getMessage().getContent();
			if(emitResponse && cont != null) {
				cont = cont.trim().replaceAll("^assistant[:]*\s*", "");
				System.out.println(formatOutput(cont));
			}
		}
	}

	private String formatOutput(String input) {
		if(!formatOutput) {
			return input;
		}
		String output = input.replace('’', '\'');
		return output;
	}
	public OllamaRequest newRequest(String model) {
		OllamaRequest req = new OllamaRequest();
		req.setModel(model);
		req.setStream(false);
		if(chatMode) {
			OllamaMessage msg = new OllamaMessage();
			msg.setRole("system");
			msg.setContent(llmSystemPrompt.trim());
			req.getMessages().add(msg);
		}
		else {
			req.setSystem(llmSystemPrompt.trim());
		}
		return req;
	}
	
	private void prune(OllamaRequest req, boolean force) {
		boolean enablePrune = chatConfig.get("prune");
		if((!force && !enablePrune) || !chatMode) {
			return;
		}
		if(req.getMessages().size() <= pruneSkip) {
			return;
		}
		OllamaMessage ctxMsg = req.getMessages().get(0);
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
			OllamaMessage msg = req.getMessages().get(i);
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
			System.out.println("(Adding key frame)");
			addKeyFrame(req);
			/*
			List<OllamaMessage> msgs = Arrays.asList(Arrays.copyOfRange(req.getMessages().toArray(new OllamaMessage[0]), marker, req.getMessages().size()));
			req.getMessages().clear();
			req.getMessages().add(ctxMsg);
			req.getMessages().addAll(msgs);
			*/
		}
	}

	public OllamaMessage newMessage(OllamaRequest req, String message) {
		return newMessage(req, message, "user");
	}
	public OllamaMessage newMessage(OllamaRequest req, String message, String role) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole(role);
		msg.setContent(message);
		
		prune(req, false);
		req.getMessages().add(msg);

		return msg;
	}
	
	public OllamaRequest getPrunedRequest(OllamaRequest inReq) {
		OllamaRequest outReq = new OllamaRequest();
		outReq.setContext(inReq.getContext());
		outReq.setModel(inReq.getModel());
		outReq.setOptions(inReq.getOptions());
		outReq.setPrompt(inReq.getPrompt());
		outReq.setSystem(inReq.getSystem());
		outReq.getMessages().addAll(inReq.getMessages().stream().filter(m -> (m.isPruned()==false)).collect(Collectors.toList()));
		return outReq;
	}

	public OllamaResponse chat(OllamaRequest req) {
		return ClientUtil.post(OllamaResponse.class, ClientUtil.getResource(ollamaServer + "/api/" + (chatMode ? "chat" : "generate")), getPrunedRequest(req), MediaType.APPLICATION_JSON_TYPE);
	}
	
	
	


	
	public OllamaRequest getChatPrompt() {
		
		String model = chatConfig.get("llmModel");
		OllamaRequest req = newRequest(model);
		BaseRecord systemChar = chatConfig.get("systemCharacter");
		BaseRecord userChar = chatConfig.get("userCharacter");
		if(systemChar != null && userChar != null) {
			boolean useAssist = chatConfig.get("assist");
			String assist = null;

			if(useAssist) {
				assist = PromptUtil.getAssistChatPromptTemplate(promptConfig, chatConfig);
			}
			String sysTemp = PromptUtil.getSystemChatPromptTemplate(promptConfig, chatConfig);
			String userTemp = PromptUtil.getUserChatPromptTemplate(promptConfig, chatConfig);
			setLlmSystemPrompt(sysTemp);
			req = newRequest(model);
			setPruneSkip(2);
			if(useAssist) {
				setPruneSkip(3);
			}
			newMessage(req, userTemp);
			if(useAssist && assist != null && assist.length() > 0) {
				newMessage(req, assist, "assistant");
			}
			//setAnnotation(getAnnotateChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt));
			// setAssist(getAssistChatPromptTemplate(octx, epoch, evt, systemChar, userChar, interaction, iPrompt));
		}
		else {
			logger.warn("Either system or user characters were null - Defaulting to standard chat interface");
			logger.warn(chatConfig.toFullString());
		}
		
		
		
		
		req.setOptions(getChatOptions());

		return req;
	}
	
	public OllamaOptions getChatOptions() {
		OllamaOptions opts = new OllamaOptions();
		opts.setNumGpu(32);
		opts.setNumCtx(contextSize);
		opts.setRepeatPenalty(1.9);
		opts.setRepeatLastN(512);
		/*
		opts.setTemperature(1);
		opts.setTopP(1);
		opts.setTopK(0);
		*/
		
		
		opts.setTemperature(.9);
		opts.setTopP(0.6);
		opts.setTopK(50);
		
		/*
		opts.setTemperature(1.0);
		opts.setTopP(0.6);
		opts.setTopK(35);
		*/
		/*
		opts.setTemperature(0.55);
		opts.setTopP(0.55);
		opts.setTopK(45);
		*/
		return opts;
	}

}
