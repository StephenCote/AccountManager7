package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.EthnicityEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.MapUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.actions.ActionUtil;
import org.cote.accountmanager.olio.actions.Actions;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GenericStateRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioStateRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

public class TestOlio2 extends BaseTest {
	
	
	/*
	 * These unit tests depend on a variety of external data that must be downloaded separately and staged relative to the data path defined in the test resources file.
	 * Data files include: Princeton wordnet dictionary data files, GeoNames file dumps, US baby names, CA surnames, and occuptations.
	 * notes/dataNotes.txt contains the links to these data sources 
	 */
	private boolean resetUniverse = false;
	private boolean resetWorld = false;
	private String universeName = "Olio Universe";
	private String worldName = "Olio World";

	@Test
	public void TestRollCharacter() {
		logger.info("Test Olio Roll Character");

		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");;
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		
		String dataPath = testProperties.getProperty("test.datagen.path");
		
		OlioContext octx = null;
		try{
			octx = OlioTestUtil.getContext(testOrgContext, dataPath);
		}
		catch(StackOverflowError | Exception e) {
			e.printStackTrace();
		}
		assertNotNull("Context is null", octx);
		List<BaseRecord> realms = octx.getRealms();
		assertTrue("Expected some realms", realms.size() > 0);
		BaseRecord realm = realms.get(0);
		BaseRecord popGroup = realm.get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Pop group is null", popGroup);
		List<BaseRecord> pop = octx.getRealmPopulation(realm);
		
		logger.info("Imprint Characters");
		BaseRecord per1 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getDukePrint());
		assertNotNull("Person was null", per2);
		
		logger.info("Roll new character");
		BaseRecord a1 = null;
		String name = "Jay Kippy Smith - " + UUID.randomUUID().toString();
		try {
			a1 = mf.newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, null);

			a1.set(FieldNames.FIELD_FIRST_NAME, "Jay");
			a1.set(FieldNames.FIELD_MIDDLE_NAME, "Kippy");
			a1.set(FieldNames.FIELD_LAST_NAME, "Smith");
			a1.set(FieldNames.FIELD_NAME, name);
	
			a1.set(FieldNames.FIELD_GENDER, (Math.random() <= 0.5 ? "male" : "female"));
			a1.set("age", (new Random()).nextInt(7, 70));
			a1.set("alignment", OlioUtil.getRandomAlignment());
			
			StatisticsUtil.rollStatistics(a1.get(OlioFieldNames.FIELD_STATISTICS), (int)a1.get("age"));
			ProfileUtil.rollPersonality(a1.get(FieldNames.FIELD_PERSONALITY));
			a1.set(OlioFieldNames.FIELD_RACE, CharacterUtil.randomRaceType().stream().map(k -> k.toString()).collect(Collectors.toList()));
			a1.set("ethnicity", Arrays.asList(new String[] {EthnicityEnumType.ZERO.toString()}));
			CharacterUtil.setStyleByRace(null, a1);
			
			BaseRecord app = ApparelUtil.randomApparel(null, a1);
			List<BaseRecord> apps = a1.get("store.apparel");
			app.set(FieldNames.FIELD_NAME, "Primary Apparel");
			apps.add(app);
			//logger.info(((BaseRecord)a1.get("store")).toFullString());
			logger.info(app.toFullString());
			//RecordUtil.buildNestedGroupRecord(testUser1, a1.get("store"), null, OlioFieldNames.FIELD_APPAREL, null); 
			
			List<BaseRecord> wears = app.get(OlioFieldNames.FIELD_WEARABLES);
			//logger.info(wears.get(wears.size() - 1).toFullString());
			//logger.info(app.toFullString());
			 
			// logger.info(a1.toFullString());
		}
		catch(NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException  e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	/*
	/// Using MGRS-like coding to subdivide the random maps
	///
	
	@Test
	public void TestGridBROKEN() {

		/// World - 60 longitudinal bands (1 - 60) by 20 latitude bands (C to X, not including O)
		/// Grid Zone Designation (GZD) is the intersection 
		/// Next is the 100;000-meter square or 100k ident, which includes squares of 100 x 100 kilometers
		/// The 100K ident is the intersection consisting of a column (A-Z, not including I and O), and a row (A-V, not including I or O)
		/// Eastings: ##### - within a 100K ident on a map with a 1000m grid, the first two numbers come from the label of the grid line west of the position, and the last three digits are the distance in meters from the wester grid line 
		/// 
		// AuditUtil.setLogToConsole(false);

		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(true);
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			testUser1,
			testProperties.getProperty("test.datagen.path"),
			universeName,
			worldName,
			new String[] {},
			2,
			50,
			true,
			resetUniverse
		);
	
		/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
		///
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new GridSquareLocationInitializationRule(),
			new LocationPlannerRule(),
			new GenericItemDataLoadRule()
		}));
		
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
			new Increment24HourRule(),
			new HierarchicalNeedsEvolveRule()
		}));
		
		cfg.getStateRules().addAll(Arrays.asList(new IOlioStateRule[] {
			new GenericStateRule()	
		}));
		
		OlioContext octx = new OlioContext(cfg);

		logger.info("Initialize OlioContext");
		octx.initialize();
		assertTrue("Expected context to be initialized", octx.isInitialized());
		
		logger.info("Get realms");
		List<BaseRecord> realms = octx.getRealms();
		assertTrue("Expected realms", realms.size() > 0);
		logger.info("Start/Continue Epoch");
		BaseRecord evt = null;
		///octx.startOrContinueEpoch();
		
		BaseRecord realm = realms.get(0);
		BaseRecord lrec = realm.get(OlioFieldNames.FIELD_ORIGIN);
		assertNotNull("Location was null", lrec);
		
		logger.info("Start/Continue Location Epoch");

		List<BaseRecord> pop = octx.getRealmPopulation(realm);

		logger.info("Imprint Characters");
		BaseRecord per1 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getDukePrint());
		assertNotNull("Person was null", per2);

		logger.info(per1.get("state.id") + " " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_EAST) + ", " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_NORTH));
		MapUtil.printLocationMap(octx, lrec, realm, pop);
		MapUtil.printRealmMap(octx, realm, Arrays.asList(new BaseRecord[] {per1, per2}));
		MapUtil.printAdmin2Map(octx, GeoLocationUtil.getParentLocation(octx, realms.get(0).get(OlioFieldNames.FIELD_ORIGIN)));
	
		
		DirectionEnumType dir = DirectionEnumType.UNKNOWN;
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		logger.info("Move " + dir.toString().toLowerCase());
		StateUtil.moveByOneMeterInCell(octx, per1, dir);
		StateUtil.queueUpdateLocation(octx, per1);
		Queue.processQueue();
		
		OlioTestUtil.lookout(per1, per2);
		OlioTestUtil.lookout(per2, per1);
		
		BaseRecord mact = null;
		try{
			mact = ActionUtil.getInAction(per1, "walkTo");
			if(mact != null) {
				mact.set(FieldNames.FIELD_TYPE, ActionResultEnumType.INCOMPLETE);
				Queue.queueUpdate(mact, new String[] {FieldNames.FIELD_TYPE});
			}

			mact = Actions.beginMoveTo(octx, null, per1, per2);
			octx.overwatchActions();

		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull("Move action was null", mact);
	}
	*/
	
}
