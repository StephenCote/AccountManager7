package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.ActionUtil;
import org.cote.accountmanager.olio.BuilderUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.MapUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.PersonalityUtil;
import org.cote.accountmanager.olio.rules.GenericEvolveRule;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GenericLocationInitializationRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Test;

public class TestOlio extends BaseTest {
	
	
	/*
	 * These unit tests depend on a variety of external data that must be downloaded separately and staged relative to the data path defined in the test resources file.
	 * Data files include: Princeton wordnet dictionary data files, GeoNames file dumps, US baby names, CA surnames, and occuptations.
	 * notes/dataNotes.txt contains the links to these data sources 
	 */
	private boolean resetUniverse = false;
	private boolean resetWorld = true;
	private String worldName = "Demo World";
	private String miniName = "Mini World";
	private String miniSub = "Mini Sub";
	private String subWorldName = "Sub World";
	private String worldPath = "~/Worlds";
	
	/// Using MGRS-like coding to subdivide the random maps
	///
	@Test
	public void TestGrid() {

		/// World - 60 longitudinal bands (1 - 60) by 20 latitude bands (C to X, not including O)
		/// Grid Zone Designation (GZD) is the intersection 
		/// Next is the 100;000-meter square or 100k ident, which includes squares of 100 x 100 kilometers
		/// The 100K ident is the intersection consisting of a column (A-Z, not including I and O), and a row (A-V, not including I or O)
		/// Eastings: ##### - within a 100K ident on a map with a 1000m grid, the first two numbers come from the label of the grid line west of the position, and the last three digits are the distance in meters from the wester grid line 
		/// 
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		OlioContextConfiguration cfg = new OlioContextConfiguration(
				testUser1,
				testProperties.getProperty("test.datagen.path"),
				worldPath,
				miniName,
				miniSub,
				new String[] {},
				2,
				50,
				false,
				resetUniverse
			);
		
			/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
			///
			cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
				new GridSquareLocationInitializationRule(),
				new LocationPlannerRule(),
				new GenericItemDataLoadRule()
			}));
			
			cfg.getEvolutionRules().add(new GenericEvolveRule());
			OlioContext octx = new OlioContext(cfg);

			logger.info("Initialize olio context - Note: This will take a while when first creating a universe");

			octx.initialize();
			assertNotNull("Root location is null", octx.getRootLocation());
			
			
			/*
			if(octx.getCurrentEpoch() == null) {
				octx.startEpoch();
			}
			
			logger.info(octx.getCurrentEpoch() == null);
			*/
			BaseRecord evt = octx.startOrContinueEpoch();
			assertNotNull("Epoch is null", evt);
			BaseRecord[] locs = octx.getLocations();
			BaseRecord levt = octx.startOrContinueLocationEpoch(locs[0]);
			assertNotNull("Location epoch is null", levt);
			
			ZonedDateTime start = levt.get("eventStart");
			ZonedDateTime prog = levt.get("eventProgress");
			ZonedDateTime end = levt.get("eventEnd");
			
			long tdiff = end.toInstant().toEpochMilli() - start.toInstant().toEpochMilli();
			long pdiff = end.toInstant().toEpochMilli() - prog.toInstant().toEpochMilli();
			
			//long diffInHours = TimeUnit.MILLISECONDS.toHours(diff);
			long totalDays = TimeUnit.MILLISECONDS.toDays(tdiff);
			long progDays = TimeUnit.MILLISECONDS.toDays(pdiff);
			logger.info("Total days: " + totalDays);
			logger.info("Remaining days: " + progDays);
			
			//logger.info("Computing maps");

			//MapUtil.printMapFromAdmin2(octx);

			/*
			ItemUtil.loadItems(octx);
			BaseRecord[] items = ItemUtil.getItems(octx);
			assertTrue("Expected some item templates", items.length > 0);
			BuilderUtil.loadBuilders(octx);
			BaseRecord[] builds = BuilderUtil.getBuilders(octx);
			assertTrue("Expected some builds", builds.length > 0);
			logger.info(builds[0].toFullString());
			 */

	}
	


	/*
	@Test
	public void TestOlio4() {

		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		/// Note: Worlds are not currently keyed to a parent, and should be
		
		/// To use only custom locations, send in zero features.  It will be necessary to define a couple locations in a parent location in order to generate the populations
		///
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			testUser1,
			testProperties.getProperty("test.datagen.path"),
			worldPath,
			miniName,
			miniSub,
			new String[] {},
			2,
			50,
			false,
			resetUniverse
		);
		/// Location requirements: Location Count + 2 - you need the 'country', the 'parent', and then the count of locations, where the 'parent' is random
		///
		cfg.getContextRules().add(new GenericLocationInitializationRule("Root Sub", new String[] {"Sub 1", "Sub 2", "Sub 3", "Sub 4", "Sub 5"}));
		cfg.getEvolutionRules().add(new LocationPlannerRule());
		OlioContext octx = new OlioContext(cfg);
		//// Using full country load
		////
		/ *
		OlioContext octx = new OlioContext(
			new OlioContextConfiguration(
				testUser1,
				testProperties.getProperty("test.datagen.path"),
				worldPath,
				worldName,
				subWorldName,
				new String[] {"AS", "GB", "IE", "US"},
				2,
				250,
				false,
				resetUniverse
			)
		);
		* /
		logger.info("Initialize olio context - Note: This will take a while when first creating a universe");
		octx.initialize();
		assertTrue("Expected olio context to be initialized", octx.isInitialized());
		if(octx.getCurrentEpoch() == null) {
			octx.generateEpoch();
		}
		
		logger.info("Test start a new epoch");
		BaseRecord test = octx.startEpoch();
		assertNotNull("Epoch is null", test);
		BaseRecord[] locs2 = octx.getLocations();
		BaseRecord testE = octx.startLocationEvent(locs2[0]);
		assertNotNull("Location event is null", testE);
		
		octx.abandonLocationEvent();
		octx.abandonEpoch();
		/ *
		logger.info("Test start a new epoch while another epoch is open");
		BaseRecord test2 = EpochUtil.startEpoch(octx);
		assertNull("Epoch should be null", test2);

		logger.info("Cleanup the open epoch");
		octx.abandonEpoch();
		* /
		
		// BaseRecord per = octx.readRandomPerson();
		// assertNotNull("Person is null", per);
		//logger.info(per.toFullString());
		/ *
		Query q = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_ID, per.get(FieldNames.FIELD_ID));
		try {
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
			DBStatementMeta meta = StatementUtil.getSelectTemplate(q);
			// logger.info(meta.getSql());
		} catch (FieldException | ValueException | ModelNotFoundException | ModelException e) {
			logger.error(e);
		}
		* /
		BaseRecord[] locs = GeoLocationUtil.getRegionLocations(testUser1, octx.getWorld());
		assertTrue("Expected two or more locations", locs.length > 0);
		assertNotNull("Location is null", locs[0]);
		// float dist = GeoLocationUtil.calculateDistance(locs[0], locs[1]);
		// logger.info("Distance between " + locs[0].get(FieldNames.FIELD_NAME) + " and " + locs[1].get(FieldNames.FIELD_NAME) + " is " + dist);
		BaseRecord per = null;
		try {
			List<BaseRecord> lpop = octx.getPopulation(locs[0]);
			//FileUtil.emitFile("./tmp.txt", JSONUtil.exportObject(lpop, RecordSerializerConfig.getForeignUnfilteredModule()));
			per = lpop.get((new Random()).nextInt(lpop.size()));
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		assertNotNull("Person is null", per);
		PersonalityProfile prof = PersonalityUtil.analyzePersonality(octx, per);
		logger.info(JSONUtil.exportObject(prof));
		
		for(BaseRecord e : prof.getEvents()) {
			logger.info((String)e.get(FieldNames.FIELD_NAME));
		}
		
		octx.clearCache();
		
	}
	*/
	/*
	@Test
	public void TestOlio2() {
		logger.info("Test Olio World Data Loading");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		// 
		BaseRecord world = WorldUtil.getCreateWorld(testUser1, worldPath, worldName, new String[] {"AS", "GB", "IE", "US"});
		assertNotNull("World is null", world);
		WorldUtil.loadWorldData(testUser1, world, testProperties.getProperty("test.datagen.path"), false);
		
		BaseRecord subWorld = WorldUtil.getCreateWorld(testUser1, world, worldPath, subWorldName, new String[0]);
		// logger.info("Cleanup world: " + WorldUtil.cleanupWorld(testUser1, subWorld));
		BaseRecord epoch = null;
		try {

			WorldUtil.generateRegion(testUser1, subWorld, 2, 250);
			epoch = EpochUtil.generateEpoch(testUser1, subWorld, 1);

		}
		catch(Exception e) {
			e.printStackTrace();
		}
		assertNotNull("Expected epoch to be created", epoch);
	}
	*/
	
	/*
	
	assertNotNull("Event is null", event);

	Query qp1 = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, subWorld.get("population.id"));
	qp1.set(FieldNames.FIELD_LIMIT_FIELDS, false);
	BaseRecord person = OlioUtil.randomSelection(testUser1, qp1);
	assertNotNull("Person is null", person);
	logger.info(person.get(FieldNames.FIELD_NAME) + " is " + CharacterUtil.getCurrentAge(testUser1, subWorld, person) + " years old");
	List<BaseRecord> apps = person.get("apparel");
	if(apps.size() > 0) {
		BaseRecord app = apps.get(0);
		((List<BaseRecord>)app.get("wearables")).forEach(r -> {
			logger.info(r.get("level") + " " + r.get("color") + " " + r.get("fabric") + " " + r.get("pattern.name") + " " + r.get("name"));
		});
	}
	*/
	/*
	BaseRecord[] locs = GeoLocationUtil.getRegionLocations(testUser1, subWorld);
	assertTrue("Expected one or more locations", locs.length > 0);
	long start = System.currentTimeMillis();
	List<BaseRecord> pop = WorldUtil.getPopulation(testUser1, subWorld, locs[0]);
	long stop = System.currentTimeMillis();
	
	assertTrue("Expected a population", pop.size() > 0);
	logger.info("Time to select population: " + (stop - start) + "ms");
	Map<String,List<BaseRecord>> map = WorldUtil.getDemographicMap(testUser1, subWorld, locs[0]);
	map.forEach((k, v) -> {
		logger.info(k + " -- " + v.size());
	});
	map.get("Coupled").forEach(p -> {
		long pid = p.get(FieldNames.FIELD_ID);
		Optional<BaseRecord> popt = map.get("Coupled").stream().filter(f -> ((long)f.get(FieldNames.FIELD_ID) == pid)).findFirst();
		if(popt.isEmpty()) {
			logger.error("Uncoupled warning: " + p.get(FieldNames.FIELD_NAME));
		}
	});
	*/
}
