package org.cote.accountmanager.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;


public class SystemTaskUtil {
	private static final Logger logger = LogManager.getLogger(SystemTaskUtil.class);
	
	private static Map<String, List<BaseRecord>> pendingTasks = new ConcurrentHashMap<>();
	private static Map<String, List<BaseRecord>> activeTasks = new ConcurrentHashMap<>();
	private static Map<String, List<BaseRecord>> completedTasks = new ConcurrentHashMap<>();
	private static Map<String, List<BaseRecord>> taskResponses = new ConcurrentHashMap<>();
	
	private static String defaultNode = "any";

	public static void clear() {
		pendingTasks.clear();
		activeTasks.clear();
		completedTasks.clear();
		taskResponses.clear();
	}
	
	public static void dumpTasks() {
		dumpPendingTasks();
		dumpActiveTasks();
		dumpCompletedTasks();
	}
	
	public static void dumpPendingTasks() {
		dumpTasks("pending", pendingTasks);
	}
	
	public static void dumpActiveTasks() {
		dumpTasks("active", activeTasks);
	}

	public static void dumpCompletedTasks() {
		dumpTasks("completed", completedTasks);
	}
	
	private static void dumpTasks(String label, Map<String, List<BaseRecord>> useMap) {
		if(useMap.size() > 0) {
			logger.info("Dumping " + useMap.size() + " " + label + " tasks");
		}
		try {
			useMap.forEach((k, v) -> {
				if(v.size() > 0) {
					FileUtil.emitFile(IOFactory.DEFAULT_FILE_BASE + "/.tasks/" + label + "/" + k + "/" + UUID.randomUUID().toString() + ".json", JSONUtil.exportObject(v, RecordSerializerConfig.getUnfilteredModule()));
				}
			});
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	
	}
	
	public static BaseRecord getResponse(String id) {
		return getResponse(defaultNode, id);
	}

	public static BaseRecord getResponse(String node, String id) {
		BaseRecord resp = null;
		if(taskResponses.containsKey(node)) {
			List<BaseRecord> mat = taskResponses.get(node).stream().filter(r -> id.equals(r.get("id"))).collect(Collectors.toList());
			// logger.info("Found " + mat.size() + " out of " + taskResponses.get(node).size() + " for " + id);
			if(mat.size() > 0) {
				resp = mat.get(0);
			}
		}
		return resp;
	}

	
	public static int completeTasks(BaseRecord taskResponse){
		if(taskResponse == null) {
			logger.error("Null task response");
			return 0;
		}
		return completeTasks(Arrays.asList(new BaseRecord[] {taskResponse}));
	}
	
	private static void filterId(Map<String, List<BaseRecord>> map, String node, String id) {
		if(map.containsKey(node)) {
			List<BaseRecord> tasks = map.get(node);
			List<BaseRecord> filt = tasks.stream().filter(t -> !id.equals((String)t.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
			map.put(node,  filt);
		}
	}

	public static void abandonTask(BaseRecord task){
		abandonTask(defaultNode, task);
	}

	public static void abandonTask(String node, BaseRecord task){
		String id = task.get("id");
		filterId(pendingTasks, node, id);
		filterId(activeTasks, node, id);
		filterId(completedTasks, node, id);
		filterId(taskResponses, node, id);
	}
	
	public static int completeTasks(List<BaseRecord> taskResponses){
		return completeTasks(defaultNode, taskResponses);
	}

	public static int completeTasks(String node, List<BaseRecord> responses){
		int completed = 0;
		List<String> ids = responses.stream().map(r -> (String)r.get(FieldNames.FIELD_ID)).collect(Collectors.toList());
		if(!activeTasks.containsKey(node) || activeTasks.get(node).size() == 0) {
			logger.warn("No active tasks for node " + node);
		}
		else {
			List<BaseRecord> tasks = activeTasks.get(node);
			List<BaseRecord> filt = tasks.stream().filter(t -> !ids.contains((String)t.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
			List<BaseRecord> comp = tasks.stream().filter(t -> ids.contains((String)t.get(FieldNames.FIELD_ID))).collect(Collectors.toList());
			if(comp.size() == 0) {
				logger.warn("Failed to find any matching active tasks.");
			}
			else {
				logger.info("Completing " + comp.size() + " tasks");
			}
			activeTasks.put(node, filt);
			if(!completedTasks.containsKey(node)) {
				completedTasks.put(node, new ArrayList<>());
			}
			completedTasks.get(node).addAll(comp);
			if(!taskResponses.containsKey(node)) {
				taskResponses.put(node, new ArrayList<>());
			}
			taskResponses.get(node).addAll(responses);
			completed = comp.size();
		}
		return completed;
	}
	
	public static boolean pendTask(BaseRecord task) {
		return pendTask(defaultNode, task);
	}

	public static boolean pendTask(String node, BaseRecord task) {
		if(!pendingTasks.containsKey(node)) {
			pendingTasks.put(node, new ArrayList<>());
		}
		String id = task.get("id");
		List<BaseRecord> dup = pendingTasks.get(node).stream().filter(t -> id.equals(t.get("id"))).collect(Collectors.toList());
		if(dup.size() > 0) {
			logger.error("Task id " + id + " is already pended");
			return false;
		}
		pendingTasks.get(node).add(task);
		return true;
	}
	
	public static List<BaseRecord> activateTasks(){
		return activateTasks(defaultNode);
	}

	public static List<BaseRecord> activateTasks(String node){
		List<BaseRecord> tasks = new ArrayList<>();
		if(pendingTasks.containsKey(node)) {
			tasks = pendingTasks.get(node);
			pendingTasks.put(node, new ArrayList<>());
		}
		if(tasks.size() > 0) {
			if(!activeTasks.containsKey(node)) {
				activeTasks.put(node, new ArrayList<>());
			}
			activeTasks.get(node).addAll(tasks);
		}
		return tasks;
	}

}
