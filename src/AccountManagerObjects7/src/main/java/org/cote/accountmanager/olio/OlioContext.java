package org.cote.accountmanager.olio;

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
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
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

public class OlioContext {
	public static final Logger logger = LogManager.getLogger(OlioContext.class);
	
	protected OlioContextConfiguration config = null;
	private Overwatch overwatch = null;
	protected BaseRecord world = null;
	protected BaseRecord universe = null;
	private boolean initialized = false;
	/// Each epoch currently defaults to 1 year
	///
	// private BaseRecord currentEpoch = null;
	
	private List<BaseRecord> locations = new ArrayList<>();
	private List<BaseRecord> populationGroups = new ArrayList<>();
	/// Each location event defaults to 1 year
	/// All events for a location within that period of time fall under the location event
	///
	// private BaseRecord currentEvent = null;
	//private BaseRecord currentLocation = null;
	// private BaseRecord currentIncrement = null;
	
	private Map<Long, List<BaseRecord>> populationMap = new ConcurrentHashMap<>();
	private Map<Long, Map<String,List<BaseRecord>>> demographicMap = new ConcurrentHashMap<>();
	
	private List<BaseRecord> realms = new ArrayList<>();
	/*
	private ZonedDateTime currentTime = ZonedDateTime.now();
	private ZonedDateTime currentMonth = currentTime;
	private ZonedDateTime currentDay = currentTime;
	private ZonedDateTime currentHour = currentTime;
	*/
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
		Queue.clear();
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

	/*
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
	
	*/
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
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_WORLD);
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
	
	public void scanNestedGroups(BaseRecord cfgWorld, String fieldName, boolean userWrite) {
		BaseRecord dir = cfgWorld.get(fieldName);
		scanNestedGroups(dir, userWrite);
	}
	public void scanNestedGroups(BaseRecord dir, boolean userWrite) {

		
		logger.info("Configure group " + dir.get(FieldNames.FIELD_NAME));
		String[] rperms = new String[] {"Read"};
		String[] crudperms = new String[] {"Read", "Update", "Create", "Delete"};
		IOContext ioContext = IOSystem.getActiveContext();
		ioContext.getAuthorizationUtil().setEntitlement(olioUser, userRole, new BaseRecord[] {dir}, (userWrite ? crudperms : rperms), new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
		ioContext.getAuthorizationUtil().setEntitlement(olioUser, adminRole, new BaseRecord[] {dir}, crudperms, new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});

		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, dir.get(FieldNames.FIELD_ID), dir.get(FieldNames.FIELD_ORGANIZATION_ID));
		
		BaseRecord[] dirs = ioContext.getSearch().findRecords(pq);
		logger.info("Scan group " + dir.get(FieldNames.FIELD_NAME));
		for(BaseRecord group : dirs) {
			scanNestedGroups(group, userWrite);
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

			clock = new Clock(EventUtil.getLastEpochEvent(this), EventUtil.getRootEvent(this));
			
			locations = getRealms().stream().map(r -> (BaseRecord)r.get(OlioFieldNames.FIELD_ORIGIN)).collect(Collectors.toList());


			populationGroups.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, world.get(OlioFieldNames.FIELD_POPULATION_ID)))));

			initialized = true;
			
			if(trace) {
				logger.info("Generate Regions ...");
			}
			for(BaseRecord realm : getRealms()) {
				for(IOlioContextRule rule : config.getContextRules()) {
					rule.generateRegion(this, realm);
				}
			}
			
			if(!startOrContinueRealmEvents()) {
				logger.error("Failed to start realms");
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
	
	public BaseRecord getRealmConstructEvent(BaseRecord realm) {
		long eid = realm.get(FieldNames.FIELD_ID);
		Query eq = QueryUtil.createQuery(OlioModelNames.MODEL_EVENT, OlioFieldNames.FIELD_REALM, eid);
		eq.field(FieldNames.FIELD_GROUP_ID, world.get(OlioFieldNames.FIELD_EVENTS_ID));
		eq.field(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
		eq.getRequest().addAll(Arrays.asList(new String[] {FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_EVENT_START, OlioFieldNames.FIELD_EVENT_PROGRESS, OlioFieldNames.FIELD_EVENT_END}));
		return IOSystem.getActiveContext().getSearch().findRecord(eq);
	}

	/*
	public BaseRecord[] getChildEvents() {
		if(currentEpoch != null) {
			return getChildEvents(currentEpoch);
		}
		return new BaseRecord[0];
	}
	
	public BaseRecord[] getChildEvents(BaseRecord event) {
		return EventUtil.getChildEvents(world, event, EventEnumType.UNKNOWN);
	}
	*/
	private boolean startOrContinueRealmEvents() {
		BaseRecord ep = startOrContinueEpoch();
		int errors = 0;
		if(ep != null) {
			List<BaseRecord> rlms = getRealms();
			if(rlms.size() == 0) {
				logger.error("No realms detected");
				errors++;
			}
			for(BaseRecord r: rlms) {
				r.setValue(OlioFieldNames.FIELD_CURRENT_EPOCH, ep);
				Queue.queueUpdate(r, new String[] {OlioFieldNames.FIELD_CURRENT_EPOCH});
				BaseRecord revt = startOrContinueRealmEvent(r);
				if(revt == null) {
					logger.error("Failed to start or continue realm epoch");
					errors++;
					continue;
				}
				clock.realmClock(r).setEvent(revt);
				BaseRecord ievt = startOrContinueRealmIncrement(r);
				if(ievt == null) {
					logger.error("Failed to start or continue realm increment");
					errors++;
					continue;
				}
				clock.realmClock(r).setIncrement(ievt);
				evaluateIncrement(r);
			}
		}
		else {
			logger.error("Root Epoch is null");
			errors++;
		}
		Queue.processQueue();
		return (errors == 0);
	}

	private BaseRecord startOrContinueEpoch() {
		BaseRecord e = null;
		try {
			if(clock.getEpoch() != null) {
				ActionResultEnumType aet = ActionResultEnumType.valueOf(clock.getEpoch().get(FieldNames.FIELD_STATE));
				if(aet == ActionResultEnumType.PENDING) {
					for(IOlioEvolveRule r : config.getEvolutionRules()) {
						r.continueEpoch(this, clock.getEpoch());
					}
					e = clock.getEpoch();
				}
			}
			else {
				logger.info("Start an epoch");
				e = startEpoch();
			}
		}
		catch(Exception er) {
			logger.error(er);
			er.printStackTrace();
		}
		
		return e;
	}
	
	public Clock realmClock(BaseRecord realm) {
		return clock.realmClock(realm);
	}
	
	public Clock clock() {
		return clock;
	}
	
	public BaseRecord startEpoch() {
		return EpochUtil.startEpoch(this);
	}
	
	public void abandonEpoch() {
		BaseRecord currentEpoch = clock.getEpoch();
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
		
		Query rq = QueryUtil.createQuery(OlioModelNames.MODEL_REALM, FieldNames.FIELD_GROUP_ID, world.get(OlioFieldNames.FIELD_REALMS_GROUP_ID));
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

		BaseRecord org = realm.get(OlioFieldNames.FIELD_ORIGIN);
		if(org == null) {
			logger.error("Origin is missing");
			logger.error(realm.toFullString());
			return;
		}
		try {
			if(clock != null) {
				realm.set(OlioFieldNames.FIELD_CURRENT_EPOCH, clock.getEpoch());
			}
			Queue.queue(realm.copyRecord(new String[] {FieldNames.FIELD_ID, OlioFieldNames.FIELD_CURRENT_EPOCH, OlioFieldNames.FIELD_CURRENT_EVENT, OlioFieldNames.FIELD_CURRENT_INCREMENT}));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}

	private BaseRecord startOrContinueRealmEvent(BaseRecord realm) {
		if(clock.getEpoch() == null) {
			logger.error("Current epoch is null");
			return null;
		}
		
		BaseRecord cevt = realm.get(OlioFieldNames.FIELD_CURRENT_EVENT);
		if(cevt != null) {
			ActionResultEnumType aet = ActionResultEnumType.valueOf(cevt.get(FieldNames.FIELD_STATE));
			if(aet == ActionResultEnumType.PENDING) {
				for(IOlioEvolveRule r : config.getEvolutionRules()) {
					r.continueRealmEvent(this, realm);
				}
				return cevt;
			}
			else {
				logger.warn("Current realm epoch is not in a pending state");
				logger.warn(cevt.toFullString());
			}
		}
		return startRealmEvent(realm);

	}
	
	public BaseRecord startRealmEvent(BaseRecord realm) {
		return EpochUtil.startRealmEvent(this, realm);
	}
	
	public void endRealmEpoch(BaseRecord realm) {
		EpochUtil.endRealmEvent(this, realm);
	}

	public void endEpoch() {
		EpochUtil.endEpoch(this);
	}
	
	public void evaluateIncrement(BaseRecord realm) {
		BaseRecord evt = realm.get(OlioFieldNames.FIELD_CURRENT_EVENT);
		BaseRecord ievt = realm.get(OlioFieldNames.FIELD_CURRENT_INCREMENT);
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
		return startOrContinueIncrement(realm);
	}
	

	public BaseRecord startOrContinueIncrement(BaseRecord realm) {
		if(clock.getEpoch() == null) {
			logger.error("Invalid location epoch");
		}
		BaseRecord inc = null;
		for(IOlioEvolveRule r : config.getEvolutionRules()) {
			inc = r.continueRealmIncrement(this, realm);
			if(inc != null) {
				break;
			}
		}
		if(inc != null) {
			return inc;
		}
		return startIncrement(realm);
	}
	
	public BaseRecord startIncrement(BaseRecord realm) {
		if(clock.getEpoch() == null) {
			logger.error("Invalid epoch");
			return null;
		}
		return EpochUtil.startRealmIncrement(this, realm);
	}

	
	public BaseRecord endIncrement(BaseRecord realm) {
		Clock rclock = clock.realmClock(realm);
		if(rclock.getIncrement() == null) {
			logger.error("Invalid increment");
			return null;
		}

		return EpochUtil.endRealmIncrement(this, realm);

	}

	public BaseRecord continueIncrement(BaseRecord realm) {
		Clock rclock = clock.realmClock(realm);
		if(rclock.getIncrement() == null) {
			logger.error("Invalid increment");
			return null;
		}
		return EpochUtil.continueRealmIncrement(this, realm);
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
