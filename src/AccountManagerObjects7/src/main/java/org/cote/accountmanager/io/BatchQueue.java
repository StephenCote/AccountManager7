package org.cote.accountmanager.io;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.thread.Threaded;
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
		if(!map.containsKey(record.getModel())) {
			map.put(record.getModel(), new CopyOnWriteArrayList<>());
		}
		map.get(record.getModel()).add(record);
	}

	private void processQueue(String queueType, Map<String, List<BaseRecord>> useMap) {
		useMap.forEach((k, v) -> {
			if(v.size() > 0) {
				logger.info("Processing " + queueType + " " + k + " " + v.size() + " record(s)");
				IOSystem.getActiveContext().getRecordUtil().updateRecords(v.toArray(new BaseRecord[0]));
			}
		});
	}
	
	@Override
	public void execute(){
		Map<String, List<BaseRecord>> useMap = new ConcurrentHashMap<>(createQueue);
		createQueue.clear();
		processQueue("create", useMap);
		useMap = new ConcurrentHashMap<>(updateQueue);
		updateQueue.clear();
		processQueue("update", useMap);
	}

}
