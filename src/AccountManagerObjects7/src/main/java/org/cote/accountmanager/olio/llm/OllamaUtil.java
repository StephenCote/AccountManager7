package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.ClientUtil;

public class OllamaUtil {
	public static final Logger logger = LogManager.getLogger(OllamaUtil.class);
	
	private String model = "dolphin-mistral";
	public OllamaUtil() {
		
	}
	public OllamaUtil(String model) {
		this.model = model;
	}
	private void addNewMessage(OllamaRequest req, String content) {
		OllamaMessage msg = newMessage(content);
		req.getMessages().add(msg);
	}
	private OllamaMessage newMessage(String content) {
		OllamaMessage msg = new OllamaMessage();
		msg.setRole("user");
		msg.setContent(content);
		return msg;
	}
	private OllamaRequest newRequest() {
		return newRequest(new ArrayList<>());
	}
	private OllamaRequest newRequest(OllamaExchange ex) {
		List<OllamaMessage> msgs = new ArrayList<>(ex.getRequest().getMessages());
		if(ex.getResponse() != null && ex.getResponse().getMessage() != null) {
			msgs.add(ex.getResponse().getMessage());
		}
		return newRequest(msgs);
	}
	private OllamaRequest newRequest(List<OllamaMessage> msgs) {
		OllamaRequest req = new OllamaRequest();
		req.setModel(model);
		req.setStream(false);
		req.setMessages(msgs);
		return req;
	}
	
	public OllamaExchange chat(String msg) {
		return chat(msg, null);
	}
	
	public OllamaExchange chat(String msg, OllamaExchange iex) {
		OllamaExchange ex = new OllamaExchange();
		OllamaRequest req = newRequest();
		if(iex != null) {
			req = newRequest(iex);
		}
		
		ex.setRequest(req);
		addNewMessage(req, msg);
		OllamaResponse rep = ClientUtil.post(OllamaResponse.class, ClientUtil.getResource("http://localhost:11434/api/chat"), req, MediaType.APPLICATION_JSON_TYPE);
		if(rep != null) {
			ex.setResponse(rep);
		}
		else {
			logger.error("Null response");
		}
		return ex;
	}
}
