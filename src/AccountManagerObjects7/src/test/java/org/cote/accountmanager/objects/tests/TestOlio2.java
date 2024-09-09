package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.awt.Image;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.AssessmentEnumType;
import org.cote.accountmanager.olio.BuilderUtil;
import org.cote.accountmanager.olio.CharacterRoleEnumType;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.LoveNeedsEnumType;
import org.cote.accountmanager.olio.MapUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.NeedsUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.ReasonEnumType;
import org.cote.accountmanager.olio.RollUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.olio.ThreatUtil;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.actions.ActionUtil;
import org.cote.accountmanager.olio.actions.Actions;
import org.cote.accountmanager.olio.llm.OllamaExchange;
import org.cote.accountmanager.olio.llm.OllamaMessage;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.llm.OllamaResponse;
import org.cote.accountmanager.olio.llm.OllamaUtil;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.personality.MBTIUtil;
import org.cote.accountmanager.personality.SloanUtil;
import org.cote.accountmanager.olio.rules.ArenaEvolveRule;
import org.cote.accountmanager.olio.rules.ArenaInitializationRule;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GenericLocationInitializationRule;
import org.cote.accountmanager.olio.rules.GenericStateRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.IOlioStateRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.parsers.wordnet.WordNetParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EffectEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.GraphicsUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.json.JSONObject;
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
		BaseRecord evt = octx.startOrContinueEpoch();
		
		BaseRecord realm = realms.get(0);
		BaseRecord lrec = realm.get("origin");
		assertNotNull("Location was null", lrec);
		
		logger.info("Start/Continue Location Epoch");
		BaseRecord levt = octx.startOrContinueLocationEpoch(lrec);
		assertNotNull("Location epoch is null", levt);
		
		logger.info("Start/Continue Increment");
		BaseRecord cevt = octx.startOrContinueIncrement();
		try {
			octx.evaluateIncrement();
		}
		catch(Exception e) {
			e.printStackTrace();
			return;
		}
		// octx.clearCache();
		List<BaseRecord> pop = octx.getPopulation(lrec);

		logger.info("Imprint Characters");
		BaseRecord per1 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getDukePrint());
		assertNotNull("Person was null", per2);

		/*
		logger.info("Look around");
		look(octx, realm, pop, cevt, per1);
		look(octx, realm, pop, cevt, per2);
		*/
		
		logger.info(per1.get("state.id") + " " + per1.get("state.currentEast") + ", " + per1.get("state.currentNorth"));
		MapUtil.printLocationMap(octx, lrec, realm, pop);
		MapUtil.printRealmMap(octx, realm, Arrays.asList(new BaseRecord[] {per1, per2}));
		MapUtil.printAdmin2Map(octx, GeoLocationUtil.getParentLocation(octx, realms.get(0).get("origin")));
		//BaseRecord upar = GeoLocationUtil.getParentLocation(octx, per1.get("state.currentLocation"));
		// MapUtil.printPovLocationMap(octx, realm, per1, 3);
		// MapUtil.printPovLocationMap(octx, realm, per2, 3);
		
		
		DirectionEnumType dir = DirectionEnumType.UNKNOWN;
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		logger.info("Move " + dir.toString().toLowerCase());
		StateUtil.moveByOneMeterInCell(octx, per1, dir);
		StateUtil.queueUpdateLocation(octx, per1);
		octx.processQueue();
		
		OlioTestUtil.lookout(per1, per2);
		OlioTestUtil.lookout(per2, per1);
		
		ZonedDateTime ep = cevt.get("eventProgress");
		ZonedDateTime ee = cevt.get("eventEnd");
		long remMin = ep.until(ee, ChronoUnit.MINUTES);
		logger.info("Minutes remaining: " + remMin);
		
		BaseRecord mact = null;
		try{
			mact = ActionUtil.getInAction(per1, "walkTo");
			if(mact != null) {
				mact.set("type", ActionResultEnumType.INCOMPLETE);
				octx.queueUpdate(mact, new String[] {"type"});
			}
			mact = Actions.beginMoveTo(octx, cevt, per1, per2);
			octx.overwatchActions();
			/*
			assertNotNull("Move action was null", mact);
			Actions.executeAction(octx, mact);
			*/
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull("Move action was null", mact);
		//logger.info(mact.toFullString());
		
		//logger.info(cevt.toFullString());
		
		//MapUtil.printLocationMap(octx, upar, realm, pop);
		/*
		String filtName = "Jori Tyce Hoggan";
		for(BaseRecord realm : realms) {
			BaseRecord lrec = realm.get("origin");
			assertNotNull("Location was null", lrec);
			
			BaseRecord levt = octx.startOrContinueLocationEpoch(lrec);
			assertNotNull("Location epoch is null", levt);
			BaseRecord cevt = octx.startOrContinueIncrement();
			octx.evaluateIncrement();
			List<BaseRecord> pop = octx.getPopulation(lrec);
			BaseRecord per = null;
			if(filtName != null) {
				Optional<BaseRecord> oper = pop.stream().filter(c -> filtName.equals(c.get("name"))).findFirst();
				if(oper.isPresent()) {
					per = oper.get();
				}
			}
			else {
				per = pop.get((new Random()).nextInt(pop.size()));
			}
			assertTrue("Expected a population", pop.size() > 0);
			/ *
			if(per != null) {
				try {
					// wanderAimlessly(octx, levt, cevt, realm, pop, pop.get((new Random()).nextInt(pop.size())));
					wanderAmok(octx, levt, cevt, realm, pop, per);
				}
				catch(Exception e) {
					e.printStackTrace();
				}
				//MapUtil.printLocationMap(octx, lrec, realm, pop);
				MapUtil.printRealmMap(octx, realm);
			}
			* /
			
		}
		*/
		//MapUtil.printMapFromAdmin2(octx);
		
		//assertNotNull("Root location is null", octx.getRootLocation());
	}
	

	


	

	
}
