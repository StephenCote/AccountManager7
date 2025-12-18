package org.cote.accountmanager.io;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.thread.Threaded;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;

public class BatchQueue extends Threaded {
	public static final Logger logger = LogManager.getLogger(BatchQueue.class);
	private int threadDelay = 5000;
	
	private final Map<String, List<BaseRecord>> createQueue = new ConcurrentHashMap<>();
	private final Map<String, List<BaseRecord>> updateQueue = new ConcurrentHashMap<>();
	
	public BatchQueue(){
		this.setThreadDelay(threadDelay);
	}
	
	public void enqueue(BaseRecord record) {
		Map<String, List<BaseRecord>> map = createQueue;
		if(record == null) {
			logger.warn("Attempted to enqueue a null record");
		}
		if(RecordUtil.isIdentityRecord(record)) {
			map = updateQueue;
		}
		if(!map.containsKey(record.getSchema())) {
			map.put(record.getSchema(), new CopyOnWriteArrayList<>());
		}
		map.get(record.getSchema()).add(record);
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
						FileUtil.emitFile(IOFactory.DEFAULT_FILE_BASE + File.separator + ".queue" + File.separator+ k + File.separator + UUID.randomUUID().toString() + ".json", JSONUtil.exportObject(v, RecordSerializerConfig.getUnfilteredModule()));
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
	
	private void scanQueue() {
		/// TODO: Scan the queue for pending entries
	}
	
	@Override
	public void execute(){
		
		scanQueue();
		
		Map<String, List<BaseRecord>> useMap = new ConcurrentHashMap<>(createQueue);
		createQueue.clear();
		processQueue("create", useMap);
		useMap = new ConcurrentHashMap<>(updateQueue);
		updateQueue.clear();
		processQueue("update", useMap);
	}

}
