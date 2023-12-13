package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public class OlioContext {
	public static final Logger logger = LogManager.getLogger(OlioContext.class);
	
	protected OlioContextConfiguration config = null;
	protected BaseRecord world = null;
	protected BaseRecord universe = null;
	private boolean initialized = false;
	/// Each epoch currently defaults to 1 year
	///
	private BaseRecord currentEpoch = null;
	private BaseRecord[] locations = new BaseRecord[0];
	private List<BaseRecord> populationGroups = new ArrayList<>();
	/// Each location event defaults to 1 year
	/// All events for a location within that period of time fall under the location event
	///
	private BaseRecord currentEvent = null;
	private BaseRecord currentLocation = null;
	private Map<Long, List<BaseRecord>> populationMap = new ConcurrentHashMap<>();
	private Map<Long, Map<String,List<BaseRecord>>> demographicMap = new ConcurrentHashMap<>();
	private Map<String, List<BaseRecord>> queue = new HashMap<>();
	
	private long currentMonth = 0L;
	private long currentDay = 0L;
	private long currentHour = 0L;
	private long currentTime = 0L;
	
	public OlioContext(OlioContextConfiguration cfg) {
		this.config = cfg;
	}
	public void clearCache() {
		populationMap.clear();
		demographicMap.clear();
		if(queue.size() > 0) {
			logger.error("Warning: request to clear pending queue");
		}
		clearQueue();
	}
	public void clearQueue() {
		queue.clear();
	}
	public void setCurrentMonth(long m) {
		currentMonth = currentDay = currentHour = currentTime = m;
	}
	public void setCurrentDay(long m) {
		currentDay = currentHour = currentTime = m;
	}
	public void setCurrentHour(long m) {
		currentHour = currentTime = m;
	}
	public long getCurrentTime() {
		return currentTime;
	}
	public void setCurrentTime(long currentTime) {
		this.currentTime = currentTime;
	}
	public long getCurrentMonth() {
		return currentMonth;
	}
	public long getCurrentDay() {
		return currentDay;
	}
	public long getCurrentHour() {
		return currentHour;
	}
	public void initialize() {
		logger.info("Initializing Olio Context ...");
		try {
			if(config == null || initialized) {
				return;
			}
			/*
			if(config.getFeatures() == null || config.getFeatures().length == 0) {
				logger.error("Invalid features list");
				return;
			}
			*/
			long start = System.currentTimeMillis();
	
			universe = WorldUtil.getCreateWorld(config.getUser(), config.getWorldPath(), config.getUniverseName(), config.getFeatures());
			if(universe == null) {
				logger.error("Failed to load universe " + config.getUniverseName());
				return;
			}
			IOSystem.getActiveContext().getReader().populate(universe, 2);
			WorldUtil.loadWorldData(config.getUser(), universe, config.getDataPath(), config.isResetUniverse());
			
			world = WorldUtil.getCreateWorld(config.getUser(), universe, config.getWorldPath(), config.getWorldName(), new String[0]);
			if(world == null) {
				logger.error("Failed to load world " + config.getWorldName());
				return;
			}
			if(config.isResetWorld()) {
				WorldUtil.cleanupWorld(config.getUser(), world);
			}
			IOSystem.getActiveContext().getReader().populate(world, 2);
			config.getContextRules().forEach(r -> {
				r.pregenerate(this);
			});
			// BaseRecord rootEvent = WorldUtil.generateRegion(config.getUser(), world, config.getBaseLocationCount(), config.getBasePopulationCount());
			BaseRecord rootEvent = WorldUtil.generateRegion(this);
			if(rootEvent == null) {
				logger.error("Failed to find or create a new region");
				return;
			}
			currentEpoch = EventUtil.getLastEpochEvent(this);
			locations = GeoLocationUtil.getRegionLocations(this);
			populationGroups.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, world.get("population.id")))));
			initialized = true;
			//if(currentEpoch == null) {
				Query eq = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_PARENT_ID, rootEvent.get(FieldNames.FIELD_ID));
				eq.field(FieldNames.FIELD_GROUP_ID, world.get("events.id"));
				eq.field(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
				BaseRecord[] evts = IOSystem.getActiveContext().getSearch().findRecords(eq);
				for(BaseRecord evt : evts) {
					logger.info("Planning " + evt.get(FieldNames.FIELD_NAME));
					for(IOlioEvolveRule rule : config.getEvolutionRules()) {
						rule.generateRegion(this, rootEvent, evt);
					}
				}
			//}
			long stop = System.currentTimeMillis();
			logger.info("... Olio Context Initialized in " + (stop - start) + "ms");
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	public BaseRecord startEpoch() {
		return EpochUtil.startEpoch(this);
	}
	
	public void abandonEpoch() {
		if(currentEpoch != null) {
			ActionResultEnumType aet = ActionResultEnumType.valueOf(currentEpoch.get(FieldNames.FIELD_STATE));
			if(aet != ActionResultEnumType.COMPLETE) {
				IOSystem.getActiveContext().getRecordUtil().deleteRecord(currentEpoch);
				currentEpoch = EventUtil.getLastEpochEvent(this);
			}
		}
	}
	
	public BaseRecord startLocationEvent(BaseRecord location) {
		return EpochUtil.startLocationEvent(this, location);
	}
	
	public void abandonLocationEvent() {
		if(currentEvent != null) {
			ActionResultEnumType aet = ActionResultEnumType.valueOf(currentEvent.get(FieldNames.FIELD_STATE));
			if(aet != ActionResultEnumType.COMPLETE) {
				IOSystem.getActiveContext().getRecordUtil().deleteRecord(currentEvent);
				currentEvent = null;
				currentLocation = null;
			}
		}
	}
	
	public void queue(BaseRecord obj) {
		OlioUtil.queueAdd(queue, obj);
	}
	public void processQueue() {
		queue.forEach((k, v) -> {
			IOSystem.getActiveContext().getRecordUtil().updateRecords(v.toArray(new BaseRecord[0]));
		});
		queue.clear();
	}
	
	public Map<String, List<BaseRecord>> getQueue() {
		return queue;
	}

	public Map<Long, Map<String, List<BaseRecord>>> getDemographicMap() {
		return demographicMap;
	}
	public Map<String, List<BaseRecord>> getDemographicMap(BaseRecord location) {
		return OlioUtil.getDemographicMap(this, location);
	}	
	public Map<Long, List<BaseRecord>> getPopulationMap() {
		return populationMap;
	}

	public BaseRecord[] getLocations() {
		return locations;
	}

	public List<BaseRecord> getPopulationGroups() {
		return populationGroups;
	}
	public List<BaseRecord> getPopulation(BaseRecord location){
		return OlioUtil.getPopulation(this, location);
	}
	public BaseRecord getPopulationGroup(BaseRecord location, String name) {
		return OlioUtil.getPopulationGroup(this, location, name);
	}

	public BaseRecord getCurrentEvent() {
		return currentEvent;
	}

	public void setCurrentEvent(BaseRecord currentEvent) {
		this.currentEvent = currentEvent;
	}

	public BaseRecord getCurrentLocation() {
		return currentLocation;
	}

	public void setCurrentLocation(BaseRecord currentLocation) {
		this.currentLocation = currentLocation;
	}

	public BaseRecord readRandomPerson() {
		if(!initialized) {
			return null;
		}
		Query qp1 = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, world.get("population.id"));
		try {
			qp1.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		return OlioUtil.randomSelection(config.getUser(), qp1);
	}
	
	public boolean validateContext() {
		if(!initialized) {
			logger.error("Context is not initialized");
			return false;
		}
		if(world == null) {
			logger.error("World is null");
			return false;
		}
		IOSystem.getActiveContext().getReader().populate(world, 2);
		if(universe == null) {
			logger.error("A basis world is required");
			return false;
		}
		BaseRecord rootEvt = EventUtil.getRootEvent(this);
		if(rootEvt == null) {
			logger.error("Root event could not be found");
			return false;
		}
		BaseRecord rootLoc = GeoLocationUtil.getRootLocation(this);
		if(rootLoc == null){
			logger.error("Failed to find root location");
			return false;
		}
		
		return true;
	}
	
	public BaseRecord generateEpoch() {
		return generateEpoch(1);
	}
	public BaseRecord generateEpoch(int count) {
		if(!initialized) {
			return null;
		}
		// currentEpoch = EpochUtil.generateEpoch(config.getUser(), world, count);
		currentEpoch = EpochUtil.generateEpoch(this, count);
		return currentEpoch;
	}

	public BaseRecord getCurrentEpoch() {
		return currentEpoch;
	}

	public void setCurrentEpoch(BaseRecord currentEpoch) {
		this.currentEpoch = currentEpoch;
	}

	public BaseRecord getUser() {
		return config.getUser();
	}
	
	public OlioContextConfiguration getConfig() {
		return config;
	}

	public BaseRecord getWorld() {
		return world;
	}

	public BaseRecord getUniverse() {
		return universe;
	}

	public boolean isInitialized() {
		return initialized;
	}
	
	
	
	
}
