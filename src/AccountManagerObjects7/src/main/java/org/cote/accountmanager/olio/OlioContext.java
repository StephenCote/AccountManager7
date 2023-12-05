package org.cote.accountmanager.olio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class OlioContext {
	public static final Logger logger = LogManager.getLogger(OlioContext.class);
	
	protected OlioContextConfiguration config = null;
	protected BaseRecord world = null;
	protected BaseRecord universe = null;
	private boolean initialized = false;
	private BaseRecord currentEpoch = null;
	public OlioContext(OlioContextConfiguration cfg) {
		this.config = cfg;
	}
	
	public void initialize() {
		logger.info("Initializing Olio Context ...");
		try {
			if(config == null || initialized) {
				return;
			}
			if(config.getFeatures() == null || config.getFeatures().length == 0) {
				logger.error("Invalid features list");
				return;
			}
			long start = System.currentTimeMillis();
	
			universe = WorldUtil.getCreateWorld(config.getUser(), config.getWorldPath(), config.getUniverseName(), config.getFeatures());
			if(universe == null) {
				logger.error("Failed to load universe " + config.getUniverseName());
				return;
			}
			WorldUtil.loadWorldData(config.getUser(), universe, config.getDataPath(), config.isResetUniverse());
			
			world = WorldUtil.getCreateWorld(config.getUser(), universe, config.getWorldPath(), config.getWorldName(), new String[0]);
			if(world == null) {
				logger.error("Failed to load world " + config.getWorldName());
				return;
			}
			if(config.isResetWorld()) {
				WorldUtil.cleanupWorld(config.getUser(), world);
			}
	
			BaseRecord rootEvent = WorldUtil.generateRegion(config.getUser(), world, config.getBaseLocationCount(), config.getBasePopulationCount());
			if(rootEvent == null) {
				logger.error("Failed to find or create a new region");
				return;
			}
			currentEpoch = EventUtil.getLastEpochEvent(config.getUser(), world);
			
			initialized = true;
			long stop = System.currentTimeMillis();
			logger.info("... Olio Context Initialized in " + (stop - start) + "ms");
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
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
	
	public BaseRecord generateEpoch() {
		return generateEpoch(1);
	}
	public BaseRecord generateEpoch(int count) {
		if(!initialized) {
			return null;
		}
		currentEpoch = EpochUtil.generateEpoch(config.getUser(), world, count);
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
