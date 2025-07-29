package org.cote.accountmanager.tools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.util.ClientUtil;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;

public class VoiceUtil {
	public static final Logger logger = LogManager.getLogger(EmbeddingUtil.class);
	
	private String serverTTSUrl = null;
	private String serverSTTUrl = null;
	private String authorizationToken = null;
	private LLMServiceEnumType serviceType = LLMServiceEnumType.UNKNOWN;

	public VoiceUtil(LLMServiceEnumType type, String ttsUrl, String sttUrl, String token) {
		this.serverTTSUrl = ttsUrl;
		this.serverSTTUrl = sttUrl;
		this.authorizationToken = token;
		this.serviceType = type;
	}
	
	
	
	public String getServerTTSUrl() {
		return serverTTSUrl;
	}



	public void setServerTTSUrl(String serverTTSUrl) {
		this.serverTTSUrl = serverTTSUrl;
	}



	public String getServerSTTUrl() {
		return serverSTTUrl;
	}



	public void setServerSTTUrl(String serverSTTUrl) {
		this.serverSTTUrl = serverSTTUrl;
	}



	public void setServiceType(LLMServiceEnumType serviceType) {
		this.serviceType = serviceType;
	}



	public LLMServiceEnumType getServiceType() {
		return serviceType;
	}

	public synchronized VoiceResponse getText(VoiceRequest req){
		return postVoiceRequest(req, serverSTTUrl, "speech-to-text");
	}
	
	public synchronized VoiceResponse getVoice(VoiceRequest req){
		return postVoiceRequest(req, serverTTSUrl, "synthesize");
	}
	
	private synchronized VoiceResponse postVoiceRequest(VoiceRequest req, String server, String apiName) {

		VoiceResponse voice = null;
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Voice is not supported");
			return voice;
		}
		logger.info("Posting voice request to " + server + "/" + apiName + "/");
		try {
			voice = ClientUtil.post(VoiceResponse.class, ClientUtil.getResource(server + "/" + apiName + "/"), null, req, MediaType.APPLICATION_JSON_TYPE);
			if(voice != null) {
				voice.setUid(req.getUid());
			}

		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return voice;
	}
	

	
}
