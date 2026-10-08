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
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.thread.Threaded;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;

/// Background writer for records that must be persisted but whose caller does not need to wait for
/// the write - audit records (AuditUtil.closeAudit) are the main client. Records are batched per
/// schema and flushed through RecordUtil.updateRecords every threadDelay ms and once more on
/// IOSystem.close (Threaded.requestStop runs execute() synchronously before stopping the thread).
///
/// If a flush finds no active context the batch is spilled to disk instead of being dropped:
/// DEFAULT_FILE_BASE/.queue/<schema>/<uuid>.json, one unfiltered-serialized list per schema.
/// scanQueue() re-ingests those files on the next flush that does have a context, so an audit that
/// was queued during shutdown is written on the next start rather than lost.
public class BatchQueue extends Threaded {
	public static final Logger logger = LogManager.getLogger(BatchQueue.class);
	private int threadDelay = 5000;

	private static final String QUEUE_DIR = ".queue";
	private static final String QUEUE_SUFFIX = ".json";
	private static final String CLAIM_SUFFIX = ".claim";
	private static final String FAILED_SUFFIX = ".failed";

	private final Map<String, List<BaseRecord>> createQueue = new ConcurrentHashMap<>();
	private final Map<String, List<BaseRecord>> updateQueue = new ConcurrentHashMap<>();

	public BatchQueue(){
		this.setThreadDelay(threadDelay);
	}

	public static String getQueuePath() {
		return IOFactory.DEFAULT_FILE_BASE + File.separator + QUEUE_DIR;
	}

	public void enqueue(BaseRecord record) {
		Map<String, List<BaseRecord>> map = createQueue;
		if(record == null) {
			logger.warn("Attempted to enqueue a null record");
			return;
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
						FileUtil.emitFile(getQueuePath() + File.separator + k + File.separator + UUID.randomUUID().toString() + QUEUE_SUFFIX, JSONUtil.exportObject(v, RecordSerializerConfig.getUnfilteredModule()));
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

	/// Re-ingest the batches processQueue spilled while no context was active. Each file is claimed
	/// by renaming it before it is read - a rename is a single filesystem operation, so the service
	/// thread and a direct execute() cannot both ingest the same file. Claimed records go back through
	/// enqueue(), which re-sorts identity vs non-identity records, and are written by the processQueue
	/// pass that follows in the same execute(). A file that cannot be parsed is kept under a .failed
	/// suffix for inspection instead of being retried every cycle. Returns the number of records restored.
	protected int scanQueue() {
		if(IOSystem.getActiveContext() == null) {
			return 0;
		}
		File[] schemaDirs = new File(getQueuePath()).listFiles(File::isDirectory);
		if(schemaDirs == null) {
			return 0;
		}
		int restored = 0;
		for(File dir : schemaDirs) {
			File[] files = dir.listFiles((d, n) -> n.endsWith(QUEUE_SUFFIX));
			if(files == null) {
				continue;
			}
			for(File f : files) {
				File claim = new File(f.getPath() + "." + UUID.randomUUID().toString() + CLAIM_SUFFIX);
				if(!f.renameTo(claim)) {
					/// Already claimed by the other scanner, or removed underneath us.
					continue;
				}
				List<BaseRecord> recs = null;
				try {
					recs = JSONUtil.getList(FileUtil.getFileAsString(claim), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
				}
				catch(Exception e) {
					logger.error(e);
				}
				if(recs == null) {
					logger.error("Failed to load cached " + dir.getName() + " queue " + f.getName() + "; retaining it as " + FAILED_SUFFIX);
					if(!claim.renameTo(new File(f.getPath() + FAILED_SUFFIX))) {
						logger.error("Failed to retain " + claim.getPath());
					}
					continue;
				}
				int count = 0;
				for(BaseRecord rec : recs) {
					if(rec != null) {
						enqueue(rec);
						count++;
					}
				}
				restored += count;
				logger.info("Restored " + count + " cached " + dir.getName() + " entrie(s) from " + f.getName());
				if(!claim.delete()) {
					logger.error("Failed to delete " + claim.getPath());
				}
			}
		}
		return restored;
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
