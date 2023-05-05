package org.cote.accountmanager.record;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldType;



public class Catalog {
	public static final Logger logger = LogManager.getLogger(Catalog.class);
	
	private static Map<String, List<FieldType>> models = Collections.synchronizedMap(new HashMap<>());
	/*
	private static Map<String, Class<?>> modelClasses = new HashMap<>();
	*/
	public static void clearCatalog() {
		models.clear();
		RecordFactory.clearModels();
	}
	public static void clearCatalog(String name) {
		models.remove(name);
	}
	public static void register(String name, FieldType[] fields) {
		models.put(name, new ArrayList<>(Arrays.asList(fields)));
	}
	
	public static boolean modelExists(String name) {
		return models.containsKey(name);
	}
	
	public static List<FieldType> getModel(String name){
		return models.get(name);
	}
	
}
