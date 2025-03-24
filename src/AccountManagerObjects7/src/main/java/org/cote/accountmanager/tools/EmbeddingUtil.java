package org.cote.accountmanager.tools;

import java.net.ConnectException;

import javax.ws.rs.ProcessingException;
import javax.ws.rs.core.MediaType;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.ClientUtil;

public class EmbeddingUtil {
	public static final Logger logger = LogManager.getLogger(EmbeddingUtil.class);
	
	private String serverUrl = null;
	public EmbeddingUtil(String url) {
		this.serverUrl = url;
	}
	public float[] getEmbedding(String content){
		float[] emb = new float[0];
		try {
			EmbeddingResponse resp = ClientUtil.post(EmbeddingResponse.class, ClientUtil.getResource(serverUrl + "/generate_embedding"), null, new EmbeddingRequest(content), MediaType.APPLICATION_JSON_TYPE);
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
	
	/*
	 * 		Status stat = ClientUtil.get(Status.class, ClientUtil.getResource(testProperties.getProperty("test.embedding.server") + "/heartbeat"), null, MediaType.APPLICATION_JSON_TYPE);
		assertNotNull("Status was null");
		
		EmbeddingResponse resp = ClientUtil.post(EmbeddingResponse.class, ClientUtil.getResource(testProperties.getProperty("test.embedding.server") + "/generate_embedding"), null, new EmbeddingRequest(msg), MediaType.APPLICATION_JSON_TYPE);
		assertNotNull("Response was null", resp);
		logger.info(resp.getEmbedding().length);
	 */
	
}
