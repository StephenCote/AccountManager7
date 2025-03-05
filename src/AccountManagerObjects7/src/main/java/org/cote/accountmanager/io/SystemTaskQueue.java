package org.cote.accountmanager.io;

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
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.thread.Threaded;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;

public class SystemTaskQueue extends Threaded {
	public static final Logger logger = LogManager.getLogger(SystemTaskQueue.class);
	private int threadDelay = 1000;
	private String serverUrl = null;
	private String authorizationToken = null;
	private boolean remotePoll = true;
	private int failureCount = 0;
	private int maxFailureCount = 10;
	private int maxFailureDelay = 10000;
	
	private final Map<String, List<BaseRecord>> createQueue = new ConcurrentHashMap<>();
	private final Map<String, List<BaseRecord>> updateQueue = new ConcurrentHashMap<>();
	
	public SystemTaskQueue(){
		this.setThreadDelay(threadDelay);
	}

	private void processQueue(String queueType, Map<String, List<BaseRecord>> useMap) {
		if(IOSystem.getActiveContext() == null && useMap.size() > 0) {
			logger.warn("Context is not active.  Queued items will be cached.");
		}
		try {
			useMap.forEach((k, v) -> {
				if(v.size() > 0) {
					if(IOSystem.getActiveContext() == null) {
						logger.error("Context is not active.  Caching " + v.size() + " " + k + " entrie(s)");
						FileUtil.emitFile(IOFactory.DEFAULT_FILE_BASE + "/.queue/" + k + "/" + UUID.randomUUID().toString() + ".json", JSONUtil.exportObject(v, RecordSerializerConfig.getUnfilteredModule()));
					}
					else {
						IOSystem.getActiveContext().getRecordUtil().updateRecords(v.toArray(new BaseRecord[0]));
					}
					
					
				}
			});
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		
	}

	
	@Override
	public void execute(){
		
		if(serverUrl == null) {
			logger.warn("Null server");
		}
		if(failureCount == 0 && getThreadDelay() == maxFailureDelay) {
			setThreadDelay(threadDelay);
			logger.warn("Existing failure delay");
		}
		/// Get the task from the remote server
		if(remotePoll) {
			Builder bld = ClientUtil.getRequestBuilder(ClientUtil.getResource(serverUrl)).accept(MediaType.APPLICATION_JSON);
			if(authorizationToken != null) {
				bld.header("Authorization", "Bearer " + new String(authorizationToken));
			}
			Response response = bld.get();

			String json = null;
			if(response != null) {
				if(response.getStatus() == 200){
					json = response.readEntity(String.class);
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
			/// String json = ClientUtil.get(String.class, ClientUtil.getResource(serverUrl), authorizationToken, MediaType.APPLICATION_JSON_TYPE);
		}
		if(failureCount >= maxFailureCount) {
			logger.warn("Entering failure delay");
			setThreadDelay(maxFailureDelay);
			failureCount = 0;
		}
	}

}
