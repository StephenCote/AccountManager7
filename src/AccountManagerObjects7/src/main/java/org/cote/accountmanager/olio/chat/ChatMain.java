package org.cote.accountmanager.olio.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.OllamaMessage;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.llm.OllamaResponse;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;

public class ChatMain {
	public static final Logger logger = LogManager.getLogger(ChatMain.class);
	public static void main(String[] args){
		chatConsole();
	}
	
	public static void chatConsole(){
		BufferedReader is = new BufferedReader(new InputStreamReader(System.in));
		try{
			String prompt = "> ";
			String line = "";
			OllamaRequest req = newRequest("blue-orchid");
			OllamaResponse lastRep = null;
			while (line != null && line.equalsIgnoreCase("quit") == false && line.equalsIgnoreCase("exit") == false && line.equalsIgnoreCase("/bye") == false) {
				System.out.print(prompt);
				line = is.readLine().substring(prompt.length());
				if(line.equalsIgnoreCase("/bye")) {
					break;
				}
				newMessage(req, lastRep, line);
				lastRep = chat(req);
				if(lastRep != null) {
					if(lastRep.getMessage() != null) {
						System.out.println(lastRep.getMessage().getContent());
					}
				}
			}
		    is.close();
		}
		catch(IOException e){
			logger.error(e.getMessage());
		} 
	}
	
	public static OllamaRequest newRequest(String model) {
		OllamaRequest req = new OllamaRequest();
		req.setModel(model);
		req.setStream(false);
		req.setPrompt(llmPrompt);
		return req;
	}
	
	public static OllamaMessage newMessage(OllamaRequest req, OllamaResponse rep, String message) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("user");
		msg.setContent(message);
		if(rep != null) {
			req.getMessages().add(rep.getMessage());
		}
		req.getMessages().add(msg);
		
		return msg;
	}
	
	public static OllamaResponse chat(OllamaRequest req) {
		return ClientUtil.post(OllamaResponse.class, ClientUtil.getResource("http://localhost:11434/api/chat"), req, MediaType.APPLICATION_JSON_TYPE);
	}
	/*
	public static OllamaResponse ollamaChat(OllamaRequest req) {
		logger.info("Test Ollama Chat");
		boolean obool = false;
		OllamaRequest req = new OllamaRequest();
		req.setModel("dolphin-mistral");
		req.setStream(false);
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("user");
		msg.setContent("My name is Silas McGee.  How are you today?");
		req.getMessages().add(msg);
		OllamaResponse rep = ClientUtil.post(OllamaResponse.class, ClientUtil.getResource("http://localhost:11434/api/chat"), req, MediaType.APPLICATION_JSON_TYPE);
		if(rep != null) {
			logger.info(rep.getMessage().getContent());
			req.getMessages().add(rep.getMessage());
			OllamaMessage msg2 = new OllamaMessage();
			msg2.setRole("user");
			msg2.setContent("I shall call you Bubbles.  What would be a good last name for you, Bubbles?");
			req.getMessages().add(msg2);
			rep = ClientUtil.post(OllamaResponse.class, ClientUtil.getResource("http://localhost:11434/api/chat"), req, MediaType.APPLICATION_JSON_TYPE);
			if(rep != null) {
				logger.info(rep.getMessage().getContent());
			}
			else {
				logger.error("Null response");
			}
		}
		else {
			logger.error("Null response");
		}
		return obool;
	}
	*/
	

	private static String llmPrompt = """
You play the role of the mentalist named Sariel.
Start by chatting.
""";
}
