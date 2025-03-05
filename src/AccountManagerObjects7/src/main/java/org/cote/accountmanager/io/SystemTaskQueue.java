package org.cote.accountmanager.io;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.OlioTaskAgent;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.thread.Threaded;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.SystemTaskUtil;

public class SystemTaskQueue extends Threaded {
	public static final Logger logger = LogManager.getLogger(SystemTaskQueue.class);
	private int threadDelay = 5000;
	private String serverUrl = null;
	private String authorizationToken = null;
	private boolean remotePoll = false;
	private boolean localPoll = false;
	private int failureCount = 0;
	private int maxFailureCount = 10;
	private int maxFailureDelay = 30000;
	
	private final Map<String, List<BaseRecord>> createQueue = new ConcurrentHashMap<>();
	private final Map<String, List<BaseRecord>> updateQueue = new ConcurrentHashMap<>();
	
	public SystemTaskQueue(){
		this.setThreadDelay(threadDelay);
	}


	
	public String getServerUrl() {
		return serverUrl;
	}



	public void setServerUrl(String serverUrl) {
		this.serverUrl = serverUrl;
	}



	public String getAuthorizationToken() {
		return authorizationToken;
	}

	public void setAuthorizationToken(String authorizationToken) {
		this.authorizationToken = authorizationToken;
	}

	public boolean isLocalPoll() {
		return localPoll;
	}

	public void setLocalPoll(boolean localPoll) {
		this.localPoll = localPoll;
	}

	public boolean isRemotePoll() {
		return remotePoll;
	}

	public void setRemotePoll(boolean remotePoll) {
		this.remotePoll = remotePoll;
	}



	@Override
	public void execute(){
		

		if(failureCount == 0 && getThreadDelay() == maxFailureDelay) {
			setThreadDelay(threadDelay);
			logger.warn("Existing failure delay");
		}
		List<BaseRecord> tasks = new ArrayList<>();
		/// Get the tasks from the remote server
		if(remotePoll) {
			// logger.info("Polling remote server:" + serverUrl);
			if(serverUrl == null) {
				logger.warn("Null server");
			}
			
			Builder bld = ClientUtil.getRequestBuilder(ClientUtil.getResource(serverUrl)).accept(MediaType.APPLICATION_JSON);
			if(authorizationToken != null) {
				bld.header("Authorization", "Bearer " + new String(authorizationToken));
			}
			Response response = bld.get();

			String json = null;
			if(response != null) {
				if(response.getStatus() == 200){
					json = response.readEntity(String.class);
					tasks = JSONUtil.getList(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
				}
				else {
					logger.warn("Received response: " + response.getStatus());
					logger.warn(response.readEntity(String.class));
					failureCount++;
				}
			}
			else {
				logger.warn("Null response");
			}
		}
		else if(localPoll){
			// logger.info("Processing local queue");
			tasks = SystemTaskUtil.activateTasks();
		}
		if(tasks.size() > 0) {
			logger.info("Processing " + tasks.size() + " tasks");
			for(BaseRecord task : tasks) {
				if(OlioModelNames.MODEL_OPENAI_REQUEST.equals(task.get("taskModelType"))) {
					BaseRecord resp = OlioTaskAgent.evaluateTaskResponse(task);
					SystemTaskUtil.completeTasks(resp);
				}
			}
		}
		if(failureCount >= maxFailureCount) {
			logger.warn("Entering failure delay");
			setThreadDelay(maxFailureDelay);
			failureCount = 0;
		}
	}

}
