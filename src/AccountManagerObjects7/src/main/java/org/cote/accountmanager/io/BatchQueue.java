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
	private int threadDelay = 1000;
	private boolean stopRequested=false;
	private Thread svcThread = null;
	
	private final Map<String, List<BaseRecord>> createQueue = new ConcurrentHashMap<>();
	private final Map<String, List<BaseRecord>> updateQueue = new ConcurrentHashMap<>();
	
	public BatchQueue(){
		logger.info("Starting batch queue");
		svcThread = new Thread(this);
		svcThread.setPriority(Thread.MIN_PRIORITY);
		svcThread.start();
	}
	
	public void enqueue(BaseRecord record) {
		Map<String, List<BaseRecord>> map = createQueue;
		if(RecordUtil.isIdentityRecord(record)) {
			map = updateQueue;
		}
		if(!map.containsKey(record.getModel())) {
			map.put(record.getModel(), new CopyOnWriteArrayList<>());
		}
		map.get(record.getModel()).add(record);
	}

	public int getThreadDelay() {
		return threadDelay;
	}

	public void setThreadDelay(int threadDelay) {
		this.threadDelay = threadDelay;
	}

	public void requestStop(){
		logger.info("Stopping queue");
		stopRequested=true;
		svcThread.interrupt();
		try{
			execute();
		}
		catch(Exception e){
			logger.error(e);
		}
		
	}
	private void processQueue(Map<String, List<BaseRecord>> map) {
		Map<String, List<BaseRecord>> useMap = new ConcurrentHashMap<>(map);
		map.clear();
		useMap.forEach((k, v) -> {
			if(v.size() > 0) {
				logger.info("Processing " + k + " " + v.size() + " record(s)");
				IOSystem.getActiveContext().getRecordUtil().updateRecords(v.toArray(new BaseRecord[0]));
			}
		});
	}
	public void execute(){
		processQueue(createQueue);
		processQueue(updateQueue);
	}
	
	@Override
	public void run(){
		while (!stopRequested){
			try{
				Thread.sleep(threadDelay);
			}
			catch (InterruptedException ex){
				/* ... */
			}
			try{
				execute();
			}
			catch(Exception e){
				logger.error(e);
			}
		}
	}
}
