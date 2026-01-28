package org.cote.accountmanager.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.ErrorUtil;

public class Queue {
	public static final Logger logger = LogManager.getLogger(Queue.class);
	
	private static Map<String, List<BaseRecord>> queue = new ConcurrentHashMap<>();
	
	public static void clear() {
		if(queue.size() > 0) {
			logger.error("Warning: request to clear pending queue");
		}
		queue.clear();
	}
	public static Map<String, List<BaseRecord>> getQueue() {
		return queue;
	}
	
	public static void queueAttribute(BaseRecord record) {
		String key = "ADD-ATTRIBUTE-" + record.getSchema();
		if(!queue.containsKey(key)) {
			queue.put(key, new ArrayList<>());
		}
		queue.get(key).add(record);
	}
	
	public static void queue(BaseRecord obj) {
		queueAdd(queue, obj);
	}
	
	public static void queueUpdate(BaseRecord obj, String[] fields) {
		queueUpdate(queue, obj, fields);
	}
	
	public static void processQueue() {
		if(queue.isEmpty()) {
			logger.warn("Queue.processQueue called with empty queue!");
		}
		queue.forEach((k, v) -> {
			logger.info("Queue.processQueue: key=" + k + ", count=" + v.size());
			for(BaseRecord rec : v) {
				logger.info("  -> Updating record id=" + rec.get(FieldNames.FIELD_ID) + ": " + rec.toFullString());
			}
			int result = IOSystem.getActiveContext().getRecordUtil().updateRecords(v.toArray(new BaseRecord[0]));
			logger.info("  -> updateRecords result: " + result + " records updated");
		});
		queue.clear();
	}
	
	public static void processQueue(BaseRecord user) {
		queue.forEach((k, v) -> {
			if(k.startsWith("ADD-")) {
				IOSystem.getActiveContext().getAccessPoint().create(user, v.toArray(new BaseRecord[0]), true);
			}
			else if(k.startsWith("UP-")) {
				IOSystem.getActiveContext().getAccessPoint().update(user, v.toArray(new BaseRecord[0]), true);
			}
		});
		queue.clear();
	}
	
	protected static void queueUpdate(Map<String, List<BaseRecord>> queue, BaseRecord record, String[] fields) {
		List<String> fnlist =new ArrayList<>(Arrays.asList(fields));
		if(fnlist.size() == 0) {
			return;
		}

		fnlist.add(FieldNames.FIELD_ID);
		//fnlist.add(FieldNames.FIELD_OWNER_ID);
		ModelSchema ms = RecordFactory.getSchema(record.getSchema());
		fnlist.sort((f1, f2) -> f1.compareTo(f2));
		Set<String> fieldSet = fnlist.stream().filter(s -> {
			boolean outBool = true;
			FieldSchema fs = ms.getFieldSchema(s);
			/// Leave the identity as it's necessary to actually perform the update
			/// fs.isIdentity() || 
			if(fs == null) {
				logger.warn("Null field schema for: " + s);
				logger.warn(record.toFullString());
				ErrorUtil.printStackTrace();
			}
			if(fs == null || fs.isForeign() && fs.getFieldType() == FieldEnumType.LIST) {
				logger.warn("Skip " + record.getSchema() + "." + s);
				outBool = false;
			}
			return outBool;
		}).collect(Collectors.toSet());
		
		if(fieldSet.size() == 0) {
			logger.error("No valid fields specified to update");
			ErrorUtil.printStackTrace();
			return;
		}
		
		String key = "UP-" + record.getSchema() + "-" + fieldSet.stream().collect(Collectors.joining("-"));
		if(!queue.containsKey(key)) {
			queue.put(key, new ArrayList<>());
		}
		
		queue.get(key).add(record.copyRecord(fieldSet.toArray(new String[0])));
	}
	
	protected static void queueAdd(Map<String, List<BaseRecord>> queue, BaseRecord record) {
		record.getFields().sort((f1, f2) -> f1.getName().compareTo(f2.getName()));
		String pref = "";
		/// Split up the adds because participations can wind up going into different tables and the bulk add
		/// will reject the dataset as being too disimilar
		///
		if(record.getSchema().equals(ModelNames.MODEL_PARTICIPATION)) {
			pref = record.get(FieldNames.FIELD_PARTICIPATION_MODEL) + "-";
		}
		String key = "ADD-" + pref + record.getSchema() + "-" + record.getFields().stream().map(f -> f.getName()).collect(Collectors.joining("-"));
		if(!queue.containsKey(key)) {
			queue.put(key, new ArrayList<>());
		}
		queue.get(key).add(record);
	}
}
