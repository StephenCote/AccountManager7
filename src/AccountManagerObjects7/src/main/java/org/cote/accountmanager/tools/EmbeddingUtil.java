package org.cote.accountmanager.tools;

import java.net.ConnectException;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.util.ClientUtil;

public class EmbeddingUtil {
	public static final Logger logger = LogManager.getLogger(EmbeddingUtil.class);
	
	private String serverUrl = null;
	public EmbeddingUtil(String url) {
		this.serverUrl = url;
	}
	
	public String[] getKeywords(String content){
		String[] tags = new String[0];
		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/extract_keywords"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				tags = resp.getKeywords();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return tags;
	}

	public String[] getTopics(String content){
		String[] tags = new String[0];
		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/topic_modeling"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				tags = resp.getTopics();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return tags;
	}
	
	public String[] getNames(String content){
		String[] tags = new String[0];
		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/named_entity_recognition"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				tags = resp.getEntities();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return tags;
	}
	
	public String[] getTags(String content){
		String[] tags = new String[0];
		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/generate_tags"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				tags = resp.getTags();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return tags;
	}
	
	public String getSentiment(String content){
		String sent = null;
		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/analyze_sentiment"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				sent = resp.getSentiment();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return sent;
	}
	
	public String getSummary(String content){
		String summary = null;
		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/generate_summary"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				summary = resp.getSummary();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		
		return summary;
	}
	
	public float[] getEmbedding(String content){
		float[] emb = new float[0];
		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/generate_embedding"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				emb = resp.getEmbedding();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		
		return emb;

	}
	public boolean heartbeat() {
		boolean outBool = false;
		try {
			Status stat = ClientUtil.get(Status.class, ClientUtil.getResource(serverUrl + "/heartbeat"), null, MediaType.APPLICATION_JSON_TYPE);
			if(stat != null) {
				outBool = stat.isStatus();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return outBool;

	}

	public ToolResponse getMeta(String statement) {
		ToolResponse tr = new ToolResponse();
		tr.setKeywords(getKeywords(statement));
		tr.setEntities(getNames(statement));
		tr.setSentiment(getSentiment(statement));
		tr.setSummary(getSummary(statement));
		tr.setTags(getTags(statement));
		tr.setTopics(getTopics(statement));
		return tr;
	}
	
}
