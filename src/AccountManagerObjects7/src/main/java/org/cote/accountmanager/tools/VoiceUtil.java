package org.cote.accountmanager.tools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.util.ClientUtil;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;

public class VoiceUtil {
	public static final Logger logger = LogManager.getLogger(EmbeddingUtil.class);

	/// Immutable (ttsUrl, sttUrl, authorizationToken) triple.
	///
	/// IOContext holds ONE VoiceUtil process-wide (IOContext.java:51) and these three values are
	/// now mutable at runtime (ServerConfigUtil re-applies the DB-backed deployment config on TTL
	/// refresh, from whatever request thread trips the refresh). They are read by other threads
	/// with no synchronization. Holding them in one immutable holder swapped through a single
	/// volatile reference makes the triple un-tearable: a reader takes one snapshot and can never
	/// see a half-applied config (e.g. a new TTS URL paired with an old token).
	public static final class Endpoint {
		private final String serverTTSUrl;
		private final String serverSTTUrl;
		private final String authorizationToken;
		Endpoint(String serverTTSUrl, String serverSTTUrl, String authorizationToken) {
			this.serverTTSUrl = serverTTSUrl;
			this.serverSTTUrl = serverSTTUrl;
			this.authorizationToken = authorizationToken;
		}
		public String getServerTTSUrl() {
			return serverTTSUrl;
		}
		public String getServerSTTUrl() {
			return serverSTTUrl;
		}
		public String getAuthorizationToken() {
			return authorizationToken;
		}
	}

	private volatile Endpoint endpoint = new Endpoint(null, null, null);
	private volatile LLMServiceEnumType serviceType = LLMServiceEnumType.UNKNOWN;

	public VoiceUtil(LLMServiceEnumType type, String ttsUrl, String sttUrl, String token) {
		this.endpoint = new Endpoint(ttsUrl, sttUrl, token);
		this.serviceType = type;
	}

	/// Single-read accessor for the whole triple. Callers needing more than one value MUST use this.
	public Endpoint getEndpoint() {
		return endpoint;
	}

	public String getServerTTSUrl() {
		return endpoint.getServerTTSUrl();
	}

	public String getServerSTTUrl() {
		return endpoint.getServerSTTUrl();
	}

	public String getAuthorizationToken() {
		return endpoint.getAuthorizationToken();
	}

	/// Atomically swap the whole triple. Preferred over the single-value setters.
	public void setEndpoint(String ttsUrl, String sttUrl, String token) {
		this.endpoint = new Endpoint(ttsUrl, sttUrl, token);
	}

	public void setServerTTSUrl(String serverTTSUrl) {
		Endpoint cur = this.endpoint;
		this.endpoint = new Endpoint(serverTTSUrl, cur.getServerSTTUrl(), cur.getAuthorizationToken());
	}

	public void setServerSTTUrl(String serverSTTUrl) {
		Endpoint cur = this.endpoint;
		this.endpoint = new Endpoint(cur.getServerTTSUrl(), serverSTTUrl, cur.getAuthorizationToken());
	}

	public void setAuthorizationToken(String authorizationToken) {
		Endpoint cur = this.endpoint;
		this.endpoint = new Endpoint(cur.getServerTTSUrl(), cur.getServerSTTUrl(), authorizationToken);
	}

	public void setServiceType(LLMServiceEnumType serviceType) {
		this.serviceType = serviceType;
	}

	public LLMServiceEnumType getServiceType() {
		return serviceType;
	}

	public synchronized VoiceResponse getText(VoiceRequest req){
		return postVoiceRequest(req, this.endpoint.getServerSTTUrl(), "speech-to-text");
	}

	public synchronized VoiceResponse getVoice(VoiceRequest req){
		return postVoiceRequest(req, this.endpoint.getServerTTSUrl(), "synthesize");
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
