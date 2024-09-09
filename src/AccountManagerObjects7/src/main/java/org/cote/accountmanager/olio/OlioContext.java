package org.cote.accountmanager.olio;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;

public class OlioContext {
	public static final Logger logger = LogManager.getLogger(OlioContext.class);
	
	protected OlioContextConfiguration config = null;
	private Overwatch overwatch = null;
	protected BaseRecord world = null;
	protected BaseRecord universe = null;
	private boolean initialized = false;
	/// Each epoch currently defaults to 1 year
	///
	private BaseRecord currentEpoch = null;
	
	private List<BaseRecord> locations = new ArrayList<>();
	private List<BaseRecord> populationGroups = new ArrayList<>();
	/// Each location event defaults to 1 year
	/// All events for a location within that period of time fall under the location event
	///
	private BaseRecord currentEvent = null;
	private BaseRecord currentLocation = null;
	private BaseRecord currentIncrement = null;
	
	private Map<Long, List<BaseRecord>> populationMap = new ConcurrentHashMap<>();
	private Map<Long, Map<String,List<BaseRecord>>> demographicMap = new ConcurrentHashMap<>();
	private Map<String, List<BaseRecord>> queue = new ConcurrentHashMap<>();
	
	private List<BaseRecord> realms = new ArrayList<>();
	
	private ZonedDateTime currentTime = ZonedDateTime.now();
	private ZonedDateTime currentMonth = currentTime;
	private ZonedDateTime currentDay = currentTime;
	private ZonedDateTime currentHour = currentTime;
	
	private String olioUserName = "olioUser";
	private BaseRecord olioUser = null;
	private boolean initConfig = false;
	
	private boolean trace = false;
	private Clock clock = null;
	
	public OlioContext(OlioContextConfiguration cfg) {
		this.config = cfg;
		this.overwatch = new Overwatch(this);
	}
	
	public void overwatchActions() throws OverwatchException {
		overwatch.process();
	}
	public Overwatch getOverwatch() {
		return overwatch;
	}

	public void clearCache() {
		populationMap.clear();
		demographicMap.clear();
		realms.clear();
		if(queue.size() > 0) {
			logger.error("Warning: request to clear pending queue");
		}
		clearQueue();
		CacheUtil.clearCache();
	}
	
	public boolean isTrace() {
		return trace;
	}
	public void setTrace(boolean trace) {
		this.trace = trace;
	}
	public BaseRecord getOlioUser() {
		return olioUser;
	}
	public void clearQueue() {
		queue.clear();
	}
	public void setCurrentMonth(ZonedDateTime m) {
		currentMonth = currentDay = currentHour = currentTime = m;
	}
	public void setCurrentDay(ZonedDateTime m) {
		currentDay = currentHour = currentTime = m;
	}
	public void setCurrentHour(ZonedDateTime m) {
		currentHour = currentTime = m;
	}
	public ZonedDateTime getCurrentTime() {
		return currentTime;
	}
	public void setCurrentTime(ZonedDateTime currentTime) {
		this.currentTime = currentTime;
	}
	public ZonedDateTime getCurrentMonth() {
		return currentMonth;
	}
	public ZonedDateTime getCurrentDay() {
		return currentDay;
	}
	public ZonedDateTime getCurrentHour() {
		return currentHour;
	}
	public BaseRecord getCurrentEpoch() {
		return currentEpoch;
	}

	public void setCurrentEpoch(BaseRecord currentEpoch) {
		this.currentEpoch = currentEpoch;
	}
	
	/*
	public BaseRecord getUser() {
		return config.getUser();
	}
	*/
	
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
	
	private BaseRecord adminRole = null;
	private BaseRecord userRole = null;
	
	public boolean enroleReader(BaseRecord user) {
		return enrole(user, userRole);
	}
	public boolean enroleAdmin(BaseRecord user) {
		return enrole(user, adminRole);
	}
	protected boolean enrole(BaseRecord user, BaseRecord role) {
		boolean enabled = false;
		if(!IOSystem.getActiveContext().getMemberUtil().isMember(user, role,  null)) {
			enabled = IOSystem.getActiveContext().getMemberUtil().member(olioUser, role, user, null, true);
		}
		else {
			enabled = true;
		}
		return enabled;

	}
	
	public void configureWorld(BaseRecord cfgWorld, boolean userWrite) throws OlioException {
		if(initConfig) {
			return;
		}
		if(cfgWorld == null) {
			throw new OlioException("World is null");
		}
		if(olioUser == null) {
			throw new OlioException("Olio User is null");
		}
		OrganizationContext octx = IOSystem.getActiveContext().findOrganizationContext(config.getUser());
		if(octx == null) {
			throw new OlioException("Failed to find organization context");
		}
		
		IOContext ioContext = IOSystem.getActiveContext();

		adminRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio Admin", RoleEnumType.USER.toString(), octx.getOrganizationId());
		userRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio User", RoleEnumType.USER.toString(), octx.getOrganizationId());
		ModelSchema ms = RecordFactory.getSchema(ModelNames.MODEL_WORLD);
		for(FieldSchema fs : ms.getFields()) {
			if(fs.getBaseModel() != null && fs.getBaseModel().equals(ModelNames.MODEL_GROUP) && fs.isForeign()) {
				BaseRecord group = cfgWorld.get(fs.getName());
				if(group == null) {
					throw new OlioException("Group " + fs.getName() + " is null");
				}
				String[] rperms = new String[] {"Read"};
				String[] crudperms = new String[] {"Read", "Update", "Create", "Delete"};
				ioContext.getAuthorizationUtil().setEntitlement(olioUser, userRole, new BaseRecord[] {group}, (userWrite ? crudperms : rperms), new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
				ioContext.getAuthorizationUtil().setEntitlement(olioUser, adminRole, new BaseRecord[] {group}, crudperms, new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
			}
		}

	}
	public void configureEnvironment() throws OlioException {
		if(config == null) {
			throw new OlioException("Configuration is null");
		}
		if(config.getUser() == null) {
			throw new OlioException("Configuration user is null");
		}

		OrganizationContext octx = IOSystem.getActiveContext().findOrganizationContext(config.getUser());
		if(octx == null) {
			throw new OlioException("Failed to find organization context");
		}
		
		IOContext ioContext = IOSystem.getActiveContext();
		olioUser = ioContext.getFactory().getCreateUser(octx.getAdminUser(), olioUserName, octx.getOrganizationId());
		if(olioUser == null) {
			throw new OlioException("Failed to find olio user");
		}
		adminRole = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio Admin", RoleEnumType.USER.toString(), octx.getOrganizationId());
		if(adminRole != null) {
			return;
		}
		
		initConfig = true;
		adminRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio Admin", RoleEnumType.USER.toString(), octx.getOrganizationId());
		userRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio User", RoleEnumType.USER.toString(), octx.getOrganizationId());
		ioContext.getMemberUtil().member(olioUser, adminRole, olioUser, null, true);
		ioContext.getMemberUtil().member(olioUser, userRole, config.getUser(), null, true);
		
		BaseRecord rootDir = ioContext.getPathUtil().makePath(octx.getAdminUser(), ModelNames.MODEL_GROUP, config.getBasePath(), GroupEnumType.DATA.toString(), octx.getOrganizationId());
		if(rootDir == null) {
			throw new OlioException("Root directory is null");
		}
		
		
		ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), olioUser, new BaseRecord[] {rootDir}, new String[] {"Read", "Update", "Create"}, new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
		
		BaseRecord uDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, config.getUniversePath(), GroupEnumType.DATA.toString(), octx.getOrganizationId());
		if(uDir == null) {
			throw new OlioException("Universe directory is null");
		}
		BaseRecord wDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, config.getWorldPath(), GroupEnumType.DATA.toString(), octx.getOrganizationId());
		if(wDir == null) {
			throw new OlioException("World directory is null");
		}
		ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), userRole, new BaseRecord[] {rootDir, uDir, wDir}, new String[] {"Read"}, new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
	}
	
	public void initialize() {
		if(trace) {
			logger.info("Initializing Olio Context ...");
		}
		try {
			configureEnvironment();
			if(initialized) {
				logger.warn("Context is already initialized");
				return;
			}
			long start = System.currentTimeMillis();
			if(trace) {
				logger.info("Get/Create Universe ...");
			}
			universe = WorldUtil.getCreateWorld(olioUser, config.getUniversePath(), config.getUniverseName(), config.getFeatures());
			if(universe == null) {
				throw new OlioException("Failed to load universe " + config.getUniverseName());
			}
			IOSystem.getActiveContext().getReader().populate(universe, 2);
			configureWorld(universe, false);
			if(trace) {
				logger.info("Check/Load World Data ...");
			}
			WorldUtil.loadWorldData(this);
			if(trace) {
				logger.info("Get/Create World ...");
			}
			world = WorldUtil.getCreateWorld(olioUser, universe, config.getWorldPath(), config.getWorldName(), new String[0]);
			if(world == null) {
				throw new OlioException("Failed to load world " + config.getWorldName());
			}
			configureWorld(world, true);
			if(config.isResetWorld()) {
				if(trace) {
					logger.info("Reset World ...");
				}
				WorldUtil.cleanupWorld(olioUser, world);
			}
			IOSystem.getActiveContext().getReader().populate(world, 2);
			if(trace) {
				logger.info("Pregenerate ...");
			}
			config.getContextRules().forEach(r -> {
				r.pregenerate(this);
			});
			if(trace) {
				logger.info("Get/Create Regions ...");
			}
			BaseRecord rootEvent = null;
			for(IOlioContextRule r : config.getContextRules()){
				BaseRecord evt = r.generate(this);
				if(evt != null) {
					rootEvent = evt;
					break;
				}
			};

			if(rootEvent == null) {
				throw new OlioException("Failed to find or create a new region");
			}
			if(trace) {
				logger.info("Postgenerate ...");
			}
			config.getContextRules().forEach(r -> {
				r.postgenerate(this);
			});
			if(trace) {
				logger.info("Get/Create Epoch ...");
			}
			currentEpoch = EventUtil.getLastEpochEvent(this);
			
			locations = getRealms().stream().map(r -> (BaseRecord)r.get("origin")).collect(Collectors.toList());
			
			//locations = GeoLocationUtil.getRegionLocations(this);

			populationGroups.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, world.get("population.id")))));

			initialized = true;
			
			Query eq = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_PARENT_ID, rootEvent.get(FieldNames.FIELD_ID));
			eq.field(FieldNames.FIELD_GROUP_ID, world.get("events.id"));
			eq.field(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
			eq.getRequest().addAll(Arrays.asList(new String[] {"location", "eventStart", "eventProgress", "eventEnd"}));
			BaseRecord[] evts = IOSystem.getActiveContext().getSearch().findRecords(eq);
			if(trace) {
				logger.info("Generate Regions ...");
			}
			for(BaseRecord evt : evts) {
				for(IOlioContextRule rule : config.getContextRules()) {
					rule.generateRegion(this, rootEvent, evt);
				}
			}
			
			if(trace) {
				long stop = System.currentTimeMillis();
				logger.info("... Olio Context Initialized in " + (stop - start) + "ms");
			}
			
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	public void queue(BaseRecord obj) {
		OlioUtil.queueAdd(queue, obj);
	}
	public void queueUpdate(BaseRecord obj, String[] fields) {
		OlioUtil.queueUpdate(queue, obj, fields);
	}
	public void processQueue() {
		queue.forEach((k, v) -> {
			IOSystem.getActiveContext().getRecordUtil().updateRecords(v.toArray(new BaseRecord[0]));
		});
		queue.clear();
	}
	
	public BaseRecord[] getChildEvents() {
		if(currentEpoch != null) {
			return getChildEvents(currentEpoch);
		}
		return new BaseRecord[0];
	}
	
	public BaseRecord[] getChildEvents(BaseRecord event) {
		return EventUtil.getChildEvents(world, event, EventEnumType.UNKNOWN);
	}
	
	public boolean startOrContinueRealmEpochs() {
		BaseRecord ep = startOrContinueEpoch();
		int errors = 0;
		if(ep != null) {
			List<BaseRecord> rlms = getRealms();
			if(rlms.size() == 0) {
				logger.error("No realms detected");
				errors++;
			}
			for(BaseRecord r: rlms) {
				r.setValue("currentEpoch", ep);
				queueUpdate(r, new String[] {"currentEpoch"});
				BaseRecord revt = startOrContinueRealmEpoch(r);
				if(revt == null) {
					logger.error("Failed to start or continue realm epoch");
					errors++;
					continue;
				}
				BaseRecord ievt = startOrContinueRealmIncrement(r);
				if(ievt == null) {
					logger.error("Failed to start or continue realm increment");
					errors++;
					continue;
				}
				evaluateIncrement(r);
			}
		}
		else {
			logger.error("Root Epoch is null");
			errors++;
		}
		processQueue();
		return (errors == 0);
	}

	public BaseRecord startOrContinueEpoch() {
		BaseRecord e = null;
		try {
			if(currentEpoch != null) {
				ActionResultEnumType aet = ActionResultEnumType.valueOf(currentEpoch.get(FieldNames.FIELD_STATE));
				if(aet == ActionResultEnumType.PENDING) {
					for(IOlioEvolveRule r : config.getEvolutionRules()) {
						r.continueEpoch(this, currentEpoch);
					}
					return currentEpoch;
				}
			}
			e = startEpoch();
		}
		catch(Exception er) {
			logger.error(er);
			er.printStackTrace();
		}
		return e;
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

	public List<BaseRecord> getRealms() {
		if(realms.size() > 0) {
			return realms;
		}
		
		Query rq = QueryUtil.createQuery(ModelNames.MODEL_REALM, FieldNames.FIELD_GROUP_ID, world.get("realmsGroup.id"));
		OlioUtil.planMost(rq);
		//logger.info(rq.toSelect());
		realms.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(rq)));
		
		if(realms.size() == 0) {
			logger.info("Creating realms ...");
			List<BaseRecord> locs = GeoLocationUtil.getRegionLocations(this);
			for(BaseRecord loc: locs) {
				realms.add(getRealm(loc));
			}
		}
		else {
			BaseRecord tr = realms.get(0);
			BaseRecord ci = tr.get("currentIncrement");
			//if(ci != null) {
				//logger.info("Debug realm:");
				//logger.info(tr.toFullString());
			//}
		}
		return realms;
	}

	public BaseRecord getRealm(BaseRecord location) {
		long id = location.get(FieldNames.FIELD_ID);
		Optional<BaseRecord> rlm = realms.stream().filter(r -> id == (long)r.get("origin.id")).findFirst();
		BaseRecord realm = null;
		if(!rlm.isPresent()) {
			realm = RealmUtil.getCreateRealm(this, location);
			if(realm == null) {
				logger.error("Realm is null");
				return null;
			}
		}
		else {
			realm = rlm.get();
		}
		updateRealm(realm);
		return realm;
	}
	
	private void updateRealm(BaseRecord realm) {
		if(currentLocation == null) {
			return;
		}
		
		IOSystem.getActiveContext().getReader().populate(realm, new String[] {"origin", "currentEpoch", "currentEvent", "currentIncrement"});
		BaseRecord org = realm.get("origin");
		if(org == null) {
			logger.error("Origin is missing");
			logger.error(realm.toFullString());
			return;
		}
		long rloc = realm.get("origin.id");
		long currId = currentLocation.get(FieldNames.FIELD_ID);
		if(rloc > 0L && rloc == currId) {
			try {
				realm.set("currentEpoch", currentEpoch);
				realm.set("currentEvent", currentEvent);
				realm.set("currentIncrement", currentIncrement);
				queue(realm.copyRecord(new String[] {FieldNames.FIELD_ID, "currentEpoch", "currentEvent", "currentIncrement"}));
			}
			catch(ModelNotFoundException | FieldException | ValueException e) {
				logger.error(e);
			}
		}
	}

	public BaseRecord startOrContinueRealmEpoch(BaseRecord realm) {
		if(currentEpoch == null) {
			logger.error("Current epoch is null");
			return null;
		}
		
		BaseRecord cevt = realm.get("currentEvent");
		
		if(cevt != null) {
			ActionResultEnumType aet = ActionResultEnumType.valueOf(cevt.get(FieldNames.FIELD_STATE));
			if(aet == ActionResultEnumType.PENDING) {
				for(IOlioEvolveRule r : config.getEvolutionRules()) {
					r.continueRealmEpoch(this, realm, currentEpoch);
				}
				return currentEpoch;
			}
			else {
				logger.warn("Current realm epoch is not in a pending state");
				logger.warn(cevt.toFullString());
			}
		}
		return startRealmEpoch(realm);

	}
	
	public BaseRecord startOrContinueLocationEpoch(BaseRecord location) {
		if(currentEpoch == null) {
			logger.error("Current epoch is null");
			return null;
		}
		BaseRecord[] childEvts = EventUtil.getChildEvents(world, currentEpoch, null, location, null, TimeEnumType.UNKNOWN, EventEnumType.UNKNOWN);
		if(childEvts.length > 0) {
			if(childEvts.length > 1) {
				logger.warn("Expected only 1 location epoch and found " + childEvts.length);
			}
			ActionResultEnumType aet = ActionResultEnumType.valueOf(childEvts[0].get(FieldNames.FIELD_STATE));
			if(aet == ActionResultEnumType.PENDING) {
				for(IOlioEvolveRule r : config.getEvolutionRules()) {
					r.continueLocationEpoch(this, location, currentEpoch);
				}
				currentEvent = childEvts[0];
				currentLocation = location;
				return currentEpoch;
			}
			else {
				logger.warn("Current location epoch is not in a pending state");
				logger.warn(childEvts[0].toFullString());
			}
		}
		return startLocationEpoch(location);

	}
	
	public BaseRecord startLocationEpoch(BaseRecord location) {
		return EpochUtil.startLocationEpoch(this, location);
	}
	public void endLocationEpoch(BaseRecord location) {
		EpochUtil.endLocationEpoch(this, location);
	}

	public BaseRecord startRealmEpoch(BaseRecord realm) {
		return EpochUtil.startRealmEpoch(this, realm);
	}
	public void endRealmEpoch(BaseRecord realm) {
		EpochUtil.endRealmEpoch(this, realm);
	}

	
	public void endEpoch() {
		EpochUtil.endEpoch(this);
	}
	public void evaluateIncrement() {
		if(currentIncrement == null) {
			logger.error("Invalid increment");
			return;
		}
		for(IOlioEvolveRule r : config.getEvolutionRules()) {
			r.evaluateIncrement(this, currentEvent, currentIncrement);
		}
	}
	
	public void evaluateIncrement(BaseRecord realm) {
		BaseRecord evt = realm.get("currentEvent");
		BaseRecord ievt = realm.get("currentIncrement");
		if(evt == null) {
			logger.error("Invalid current event");
			return;
		}
		if(ievt == null) {
			logger.error("Invalid current increment");
			return;
		}
		for(IOlioEvolveRule r : config.getEvolutionRules()) {
			r.evaluateRealmIncrement(this, realm);
		}		
	}
	
	public BaseRecord startOrContinueRealmIncrement(BaseRecord realm) {
		BaseRecord evt = startOrContinueIncrement(realm.get("currentEvent"));
		realm.setValue("currentIncrement", evt);
		queueUpdate(realm, new String[] {"currentIncrement"});
		return evt;
	}
	
	public BaseRecord startOrContinueIncrement() {
		return startOrContinueIncrement(currentEvent);
	}
	public BaseRecord startOrContinueIncrement(BaseRecord locationEpoch) {
		if(locationEpoch == null) {
			logger.error("Invalid location epoch");
		}
		BaseRecord inc = null;
		for(IOlioEvolveRule r : config.getEvolutionRules()) {
			inc = r.continueIncrement(this, locationEpoch);
			if(inc != null) {
				break;
			}
		}
		if(inc != null) {
			currentIncrement = inc;
			return inc;
		}
		return startIncrement(locationEpoch);
	}
	
	public BaseRecord startIncrement() {
		return startIncrement(currentEvent);
	}
	public BaseRecord startIncrement(BaseRecord locationEpoch) {
		if(locationEpoch == null) {
			logger.error("Invalid location epoch");
			return null;
		}
		BaseRecord inc = EpochUtil.startIncrement(this, locationEpoch);
		currentIncrement = inc;
		return inc;
	}
	public BaseRecord endIncrement() {
		return endIncrement(currentEvent);
	}
	public BaseRecord endIncrement(BaseRecord locationEpoch) {
		if(locationEpoch == null) {
			logger.error("Invalid location epoch");
			return null;
		}
		BaseRecord inc = EpochUtil.endIncrement(this, locationEpoch);
		return inc;
	}
	public BaseRecord continueIncrement() {
		return continueIncrement(currentEvent);
	}
	public BaseRecord continueIncrement(BaseRecord locationEpoch) {
		if(locationEpoch == null) {
			logger.error("Invalid location epoch");
			return null;
		}
		BaseRecord inc = EpochUtil.continueIncrement(this, locationEpoch);
		return inc;
	}
	
	public void abandonLocationEpoch() {
		if(currentEvent != null) {
			ActionResultEnumType aet = ActionResultEnumType.valueOf(currentEvent.get(FieldNames.FIELD_STATE));
			if(aet != ActionResultEnumType.COMPLETE) {
				IOSystem.getActiveContext().getRecordUtil().deleteRecord(currentEvent);
				currentEvent = null;
				currentLocation = null;
			}
		}
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

	public List<BaseRecord> getLocations() {
		return locations;
	}

	public List<BaseRecord> getPopulationGroups() {
		return populationGroups;
	}
	
	public List<BaseRecord> getRealmPopulation(BaseRecord realm){
		return OlioUtil.getRealmPopulation(this, realm);
	}
	
	/// TODO: Deprecate this
	public List<BaseRecord> getPopulation(BaseRecord location){
		return OlioUtil.getPopulation(this, location);
	}
	public BaseRecord getPopulationGroup(BaseRecord location, String name) {
		return OlioUtil.getPopulationGroup(this, location, name);
	}

	public BaseRecord getCurrentIncrement() {
		return currentIncrement;
	}
	public void setCurrentIncrement(BaseRecord currentIncrement) {
		this.currentIncrement = currentIncrement;
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
	public BaseRecord getRootLocation() {
		 return GeoLocationUtil.getRootLocation(this);
	}
	
}
