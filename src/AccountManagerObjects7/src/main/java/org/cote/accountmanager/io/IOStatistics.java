package org.cote.accountmanager.io;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class IOStatistics {
	public static final Logger logger = LogManager.getLogger(IOStatistics.class);
	
	private boolean enabled = false;
	
	private Map<String, Integer> statistics = new ConcurrentHashMap<>();
	private Map<String, Integer> cacheStatistics = new ConcurrentHashMap<>();
	
	public IOStatistics() {
		
	}

	
	public void addCache(Query query) {
		if(enabled) {
			if(!cacheStatistics.containsKey(query.key())) {
				cacheStatistics.put(query.key(), 1);
			}
			else {
				cacheStatistics.put(query.key(), cacheStatistics.get(query.key()) + 1);
			}
		}
	}
	
	public void add(Query query) {
		if(enabled) {
			if(!statistics.containsKey(query.key())) {
				statistics.put(query.key(), 1);
			}
			else {
				statistics.put(query.key(), statistics.get(query.key()) + 1);
			}
		}
	}
	
	public void print() {
		statistics.forEach((k, v) -> {
			logger.info("IO - " + String.format("%05d", v) + " " + k);
		});
	}
	
	public void cachePrint() {
		cacheStatistics.forEach((k, v) -> {
			logger.info("Cache - " + String.format("%05d", v) + " " + k);
		});
	}

	
	public int cacheSize() {
		return cacheStatistics.size();
	}
	public int size() {
		return statistics.size();
	}
	
	public void clear() {
		statistics.clear();
		cacheStatistics.clear();
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public Map<String, Integer> getCacheStatistics() {
		return cacheStatistics;
	}

	public Map<String, Integer> getStatistics() {
		return statistics;
	}

	
}
