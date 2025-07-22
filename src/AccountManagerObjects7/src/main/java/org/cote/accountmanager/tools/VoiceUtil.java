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
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;

public class VoiceUtil {
	public static final Logger logger = LogManager.getLogger(EmbeddingUtil.class);
	
	private String serverUrl = null;
	private String authorizationToken = null;
	private LLMServiceEnumType serviceType = LLMServiceEnumType.UNKNOWN;

	public VoiceUtil(LLMServiceEnumType type, String url, String token) {
		this.serverUrl = url;
		this.authorizationToken = token;
		this.serviceType = type;
	}
	
	public LLMServiceEnumType getServiceType() {
		return serviceType;
	}

	public synchronized VoiceResponse getText(VoiceRequest req){
		VoiceResponse voice = null;
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Voice is not supported");
			return voice;
		}

		try {
			voice = ClientUtil.post(VoiceResponse.class, ClientUtil.getResource(serverUrl + "/speech-to-text/"), null, req, MediaType.APPLICATION_JSON_TYPE);
		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return voice;
	}
	
	public synchronized VoiceResponse getVoice(VoiceRequest req){
		VoiceResponse voice = null;
		if(serviceType != LLMServiceEnumType.LOCAL) {
			logger.error("Voice is not supported");
			return voice;
		}

		try {
			voice = ClientUtil.post(VoiceResponse.class, ClientUtil.getResource(serverUrl + "/synthesize/"), null, req, MediaType.APPLICATION_JSON_TYPE);

		}
		catch(ProcessingException e) {
			logger.error(e);
		}
		return voice;
	}
	


	
}
