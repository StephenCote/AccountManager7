package org.cote.accountmanager.olio.llm;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.ws.rs.core.MediaType;

import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.llm.OllamaMessage;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.llm.OllamaResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;

public class Chat {
	
	protected IOContext ioContext = null;
	// protected OrganizationContext orgContext = null;
	// protected String organizationPath = "/Development";

	
	public static final Logger logger = LogManager.getLogger(Chat.class);
	private boolean chatMode = true;
	private boolean includeMessageHistory = chatMode;
	private boolean includeContextHistory = !chatMode;
	private boolean enablePrune = false;
	private int tokenLength = 2048;
	//private String model = "llama2-uncensored:7b-chat-q8_0";
	private String model = "dolphin-mistral";
	//private String model = "blue-orchid";
	private String saveName = "chat.save";
	
	private BaseRecord user = null;
	private int pruneSkip = 1;
	
	private String llmSystemPrompt = """
You play the role of an assistant named Siren.
Begin conversationally.
""";
	
	public Chat(BaseRecord user) {
		this.user = user;
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

	public int getTokenLength() {
		return tokenLength;
	}

	public void setTokenLength(int tokenLength) {
		this.tokenLength = tokenLength;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public String getLlmSystemPrompt() {
		return llmSystemPrompt;
	}

	public void setLlmSystemPrompt(String llmSystemPrompt) {
		this.llmSystemPrompt = llmSystemPrompt;
	}

	public void chatConsole(){
		chatConsole(newRequest(model));
	}
	public void chatConsole(OllamaRequest req){
		BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
		try{
			String prompt = "> ";
			String line = "";
			OllamaResponse lastRep = null;
			while (line != null && line.equalsIgnoreCase("/quit") == false && line.equalsIgnoreCase("/exit") == false && line.equalsIgnoreCase("/bye") == false) {
				if(lastRep == null && req.getMessages().size() > 0) {
					logger.info("Initializing ...");
					lastRep = chat(req);
					if(lastRep != null) {
						handleResponse(req, lastRep);
					}
				}
				System.out.print(prompt);
				line = is.readLine();
				if(line == null || line.equalsIgnoreCase("/bye")) {
					break;
				}
				if(line.equals("/new")) {
					req = newRequest(model);
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
					newMessage(req, line);
				}
				else {
					req.setPrompt(line);
				}
				
				/// System.out.println(JSONUtil.exportObject(req));
				lastRep = chat(req);
				if(lastRep != null) {
					handleResponse(req, lastRep);
				}
			}
		    is.close();
		}
		catch(IOException e){
			logger.error(e.getMessage());
		} 
	}
	
	private void handleResponse(OllamaRequest req, OllamaResponse rep) {
		if(includeContextHistory) {
			req.setContext(rep.getContext());
		}

		/// System.out.println(JSONUtil.exportObject(lastRep));
		if(rep.getMessage() != null) {
			if(includeMessageHistory) {
				req.getMessages().add(rep.getMessage());
			}
			String cont = rep.getMessage().getContent();
			if(cont != null) {
				cont = cont.trim().replaceAll("^assistant[:]*\s*", "");
				System.out.println(cont);
			}
		}
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
		if((!force && !enablePrune) || !chatMode) {
			return;
		}
		if(req.getMessages().size() <= pruneSkip) {
			return;
		}
		OllamaMessage ctxMsg = req.getMessages().get(0);

		int curLength = ctxMsg.getContent().split("\\W+").length;
		int marker = -1;

		/// Skip the first message used to set any context prompt
		///
		for(int i = pruneSkip; i < req.getMessages().size(); i++) {
			curLength += req.getMessages().get(i).getContent().split("\\W+").length;
			if(curLength >= tokenLength) {
				System.out.println("Prune prior to " + i + " of " + req.getMessages().size() + " / Token Size = " + curLength);
				marker = i - 1;
				break;
			}
		}
		if(force) {
			System.out.println("Found " + curLength + " tokens");
		}
		if(marker > -1) {
			List<OllamaMessage> msgs = Arrays.asList(Arrays.copyOfRange(req.getMessages().toArray(new OllamaMessage[0]), marker, req.getMessages().size()));
			req.getMessages().clear();
			req.getMessages().add(ctxMsg);
			req.getMessages().addAll(msgs);
		}
	}
	
	public OllamaMessage newMessage(OllamaRequest req, String message) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("user");
		msg.setContent(message);

		req.getMessages().add(msg);
		prune(req, false);
		return msg;
	}
	
	public OllamaResponse chat(OllamaRequest req) {
		return ClientUtil.post(OllamaResponse.class, ClientUtil.getResource("http://localhost:11434/api/" + (chatMode ? "chat" : "generate")), req, MediaType.APPLICATION_JSON_TYPE);
	}



}
