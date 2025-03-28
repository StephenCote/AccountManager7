package org.cote.accountmanager.tools;

import java.net.ConnectException;
import java.util.List;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.MediaType;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ClientUtil;

public class EmbeddingUtil {
	public static final Logger logger = LogManager.getLogger(EmbeddingUtil.class);
	
	private String serverUrl = null;
	private String authorizationToken = null;
	private LLMServiceEnumType serviceType = LLMServiceEnumType.UNKNOWN;

	public EmbeddingUtil(LLMServiceEnumType type, String url, String token) {
		this.serverUrl = url;
		this.authorizationToken = token;
		this.serviceType = type;
	}
	
	public String[] getKeywords(String content){
		String[] words = new String[0];
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Keywords not supported");
			return words;
		}
		
		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/extract_keywords"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				words = resp.getKeywords();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return words;
	}

	public String[] getTopics(String content){
		String[] tops = new String[0];
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Topics not supported");
			return tops;
		}

		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/topic_modeling"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				tops = resp.getTopics();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return tops;
	}
	
	public String[] getNames(String content){
		String[] names = new String[0];
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Names not supported");
			return names;
		}

		try {
			ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/named_entity_recognition"), null, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
			if(resp != null) {
				names = resp.getEntities();
			}
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return names;
	}
	
	public String[] getTags(String content){
		String[] tags = new String[0];
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Tags not supported");
			return tags;
		}
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
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Sentiment not supported");
			return sent;
		}
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
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Summary not supported");
			return summary;
		}
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
			if(serviceType == LLMServiceEnumType.LOCAL) {
				ToolResponse resp = ClientUtil.post(ToolResponse.class, ClientUtil.getResource(serverUrl + "/generate_embedding"), authorizationToken, new ToolRequest(content), MediaType.APPLICATION_JSON_TYPE);
				if(resp != null) {
					emb = resp.getEmbedding();
				}
			}
			else if(serviceType == LLMServiceEnumType.OPENAI) {
				BaseRecord inp = RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_INPUT);
				inp.set("input", content);
				String respStr = ClientUtil.post(String.class, ClientUtil.getResource(serverUrl), authorizationToken, inp.toFullString(), MediaType.APPLICATION_JSON_TYPE);
				if(respStr != null) {
					BaseRecord resp = RecordFactory.importRecord(OlioModelNames.MODEL_OPENAI_RESPONSE, respStr);
					if(resp != null) {
						List<BaseRecord> data = resp.get(FieldNames.FIELD_DATA);
						if(data != null && data.size() > 0) {
							// logger.info(data.get(0).toFullString());
							List<Float> embs = data.get(0).get("embedding");
	
							if(embs != null) {
								// emb = ArrayUtils.toPrimitive(embs.toArray(new Float[0]), 0.0F);
								int len = embs.size();
								emb = new float[len];
								logger.info("Len: " + len);
								for(int i = 0; i < len; i++) {
									Object obj = embs.get(i);
						            if (obj instanceof Double) {
						            	// logger.warn("Stupid Azure - " + obj);
						                emb[i] = ((Double) obj).floatValue();
						            } else if (obj instanceof Float) {
						                emb[i] = (Float) obj;
						            } else {
						                throw new IllegalArgumentException("List contains non-float and non-double elements - " + obj);
						            }
								}
							}
							else {
								logger.error("Float list was null");
							}
						}
					}
					else {
						logger.error("Failed to deserialize: " + resp);
					}
				}
				else {
					logger.error("Response was null");
				}

			}
			else {
				logger.error("Unhandled service type: " + serviceType.toString());
			}
		}
		catch(ProcessingException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		return emb;

	}
	public boolean heartbeat() {
		boolean outBool = false;
		if(serviceType != LLMServiceEnumType.LOCAL) {
			return true;
		}
		
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
