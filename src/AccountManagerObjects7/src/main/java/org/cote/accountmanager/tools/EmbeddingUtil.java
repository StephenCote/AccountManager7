package org.cote.accountmanager.tools;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.LLMConnectionManager;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;

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
	
	public LLMServiceEnumType getServiceType() {
		return serviceType;
	}

	/// Phase 5.3 (ConversationQualityPlan): track this sync HTTP call with
	/// LLMConnectionManager so it shows up in getActiveLLMCallCount() and
	/// pressure-based deferral can see it. Caller passes a `label` like
	/// "embed:keywords" for diagnostics. Returns the ToolResponse, or null
	/// on transport error.
	private ToolResponse trackedToolPost(String label, String path, ToolRequest body) {
		String id = LLMConnectionManager.registerSyncCall(label);
		try {
			return ClientUtil.post(ToolResponse.class,
				ClientUtil.getResource(serverUrl + path), null, body,
				MediaType.APPLICATION_JSON_TYPE);
		} catch (ProcessingException e) {
			logger.error(e);
			return null;
		} finally {
			LLMConnectionManager.unregisterSyncCall(id);
		}
	}

	public String[] getKeywords(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Keywords not supported");
			return new String[0];
		}
		ToolResponse resp = trackedToolPost("embed:keywords", "/extract_keywords", new ToolRequest(content));
		return resp != null ? resp.getKeywords() : new String[0];
	}

	public String[] getTopics(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Topics not supported");
			return new String[0];
		}
		ToolResponse resp = trackedToolPost("embed:topics", "/topic_modeling", new ToolRequest(content));
		return resp != null ? resp.getTopics() : new String[0];
	}

	public String[] getNames(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Names not supported");
			return new String[0];
		}
		ToolResponse resp = trackedToolPost("embed:names", "/named_entity_recognition", new ToolRequest(content));
		return resp != null ? resp.getEntities() : new String[0];
	}

	public String[] getTags(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Tags not supported");
			return new String[0];
		}
		ToolResponse resp = trackedToolPost("embed:tags", "/generate_tags", new ToolRequest(content));
		return resp != null ? resp.getTags() : new String[0];
	}

	public String getSentiment(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Sentiment not supported");
			return null;
		}
		ToolResponse resp = trackedToolPost("embed:sentiment", "/analyze_sentiment", new ToolRequest(content));
		return resp != null ? resp.getSentiment() : null;
	}

	public String getSummary(String content){
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Summary not supported");
			return null;
		}
		ToolResponse resp = trackedToolPost("embed:summary", "/generate_summary", new ToolRequest(content));
		return resp != null ? resp.getSummary() : null;
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
