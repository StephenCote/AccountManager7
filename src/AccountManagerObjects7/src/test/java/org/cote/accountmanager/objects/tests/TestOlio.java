package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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

import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.BuilderUtil;
import org.cote.accountmanager.olio.CharacterRoleEnumType;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.MapUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.NeedsUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.ReasonEnumType;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.olio.ThreatUtil;
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
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EffectEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.json.JSONObject;
import org.junit.Test;

public class TestOlio extends BaseTest {
	
	
	/*
	 * These unit tests depend on a variety of external data that must be downloaded separately and staged relative to the data path defined in the test resources file.
	 * Data files include: Princeton wordnet dictionary data files, GeoNames file dumps, US baby names, CA surnames, and occuptations.
	 * notes/dataNotes.txt contains the links to these data sources 
	 */
	private boolean resetUniverse = false;
	private boolean resetWorld = false;
	private String worldName = "Demo World";
	private String miniName = "Mini World";
	private String arenaName = "Arena World";
	private String miniSub = "Mini Sub";
	private String arenaSub = "Arena Sub";
	private String subWorldName = "Sub World";
	private String worldPath = "~/Worlds";
	
	/// Using MGRS-like coding to subdivide the random maps
	///
	/*
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
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(true);
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
		
		// Increment24HourRule incRule = new Increment24HourRule();
		// incRule.setIncrementType(TimeEnumType.HOUR);
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
			new Increment24HourRule(),
			new HierarchicalNeedsRule()
		}));
		OlioContext octx = new OlioContext(cfg);

		logger.info("Initialize olio context - Note: This will take a while when first creating a universe");
		octx.initialize();
		assertNotNull("Root location is null", octx.getRootLocation());
		
		BaseRecord evt = octx.startOrContinueEpoch();
		assertNotNull("Epoch is null", evt);
		BaseRecord[] locs = octx.getLocations();
		// MapUtil.printMapFromAdmin2(octx);
		for(BaseRecord lrec : locs) {
			BaseRecord levt = octx.startOrContinueLocationEpoch(lrec);
			assertNotNull("Location epoch is null", levt);
			BaseRecord cevt = octx.startOrContinueIncrement();
			octx.evaluateIncrement();
			List<BaseRecord> pop = octx.getPopulation(lrec);
			assertTrue("Expected a population", pop.size() > 0);
			BaseRecord realm = octx.getRealm(lrec);
			assertNotNull("Realm is null", realm);
			wanderAround(octx, levt, cevt, realm, pop, pop.get((new Random()).nextInt(pop.size())));
			// MapUtil.printRealmMap(octx, realm);
		}
		
		MapUtil.printMapFromAdmin2(octx);

	}
	*/
	private void wanderAround(OlioContext ctx, BaseRecord event, BaseRecord increment, BaseRecord realm, List<BaseRecord> pop, BaseRecord per1) {
		/// Walk northwest for 1Km.
		DirectionEnumType dir = OlioUtil.randomEnum(DirectionEnumType.class);
		logger.info("Wander around");
		//List<BaseRecord> fpop = pop.stream().filter(p -> ((long)p.get(FieldNames.FIELD_ID)) != (long)per1.get(FieldNames.FIELD_ID)).collect(Collectors.toList());
		//Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
		
		BaseRecord state = per1.get("state");
		logger.info(per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + " " + state.get("currentLocation.northings") + " " + state.get("currentEast") + ", " + state.get("currentNorth"));		
		logger.info("Wander " + dir.toString().toLowerCase());
		for(int i = 0; i < 100; i++) {
			StateUtil.moveByOneMeterInCell(ctx, per1, dir);
			logger.info(per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + " " + state.get("currentLocation.northings") + " " + state.get("currentEast") + ", " + state.get("currentNorth"));
		}

		List<BaseRecord> fpop = observablePopulation(pop, per1);
		Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
		Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = ThreatUtil.getThreatMap(ctx, realm, increment, map);
		String lar  = NarrativeUtil.lookaround(ctx, realm, increment, increment, fpop, per1, tmap);
		logger.info(lar);
		
		dir = OlioUtil.randomEnum(DirectionEnumType.class);
		logger.info("Wander " + dir.toString().toLowerCase());
		for(int i = 0; i < 100; i++) {
			StateUtil.moveByOneMeterInCell(ctx, per1, dir);
			logger.info(per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + " " + state.get("currentLocation.northings") + " " + state.get("currentEast") + ", " + state.get("currentNorth"));
		}
		
		fpop = observablePopulation(pop, per1);
		map = ProfileUtil.getProfileMap(ctx, fpop);
		tmap = ThreatUtil.getThreatMap(ctx, realm, increment, map);
		lar  = NarrativeUtil.lookaround(ctx, realm, increment, increment, fpop, per1, tmap);

		logger.info(lar);

		ctx.processQueue();

	}

	private List<BaseRecord> observablePopulation(List<BaseRecord> pop, BaseRecord pov){
		return pop.stream().filter(p -> {
				double dist = GeoLocationUtil.getDistance(pov.get("state"), p.get("state"));
				int max = Rules.MAXIMUM_OBSERVATION_DISTANCE * Rules.MAP_EXTERIOR_CELL_WIDTH * Rules.MAP_EXTERIOR_CELL_MULTIPLIER;
				// logger.info("Distance: " + dist + "::" + max);
				boolean filt =(((long)p.get(FieldNames.FIELD_ID)) != (long)pov.get(FieldNames.FIELD_ID)
				&&
				dist <= max
				);
				return filt;
			}
		).collect(Collectors.toList());
	}

	private BaseRecord meetAndGreet(OlioContext ctx, BaseRecord per1, BaseRecord per2) {
		

		
		BaseRecord interaction = InteractionUtil.randomInteraction(ctx, per1, per2);
		if(interaction == null) {
			logger.warn("Random interaction was null");
			return null;
		}
		IOSystem.getActiveContext().getRecordUtil().createRecord(interaction);
		
		BaseRecord interaction2 = ioContext.getSearch().findRecord(QueryUtil.createQuery(ModelNames.MODEL_INTERACTION, FieldNames.FIELD_ID, interaction.get(FieldNames.FIELD_ID)));
		assertNotNull("Interaction is null", interaction2);
		// logger.info(interaction2.toFullString());
		// PersonalityProfile prof1 = ProfileUtil.analyzePersonality(ctx, per1);
		// PersonalityProfile prof2 = ProfileUtil.analyzePersonality(ctx, per2);
		CompatibilityEnumType mbtiCompat = MBTIUtil.getCompatibility(per1.get("personality.mbtiKey"), per2.get("personality.mbtiKey"));
		StringBuilder buff = new StringBuilder();
		
		buff.append("Write a narrative description about the following two characters, and the interaction that takes place between them.");
		buff.append(" " + NarrativeUtil.describe(ctx, per1));
		buff.append(" " + NarrativeUtil.describe(ctx, per2));
		
		buff.append(" " + NarrativeUtil.describeInteraction(interaction));
		logger.info(buff.toString());
		/*
		OllamaUtil ou = new OllamaUtil();
		OllamaExchange ex= ou.chat(buff.toString());
		if(ex.getResponse() != null) {
			logger.info(ex.getResponse().getMessage().getContent());
		}
		*/
		return interaction;
	}
	/*
	@Test
	public void TestArena1() {
		logger.info("Test Olio - Arena");

		AuditUtil.setLogToConsole(false);

		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(true);
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			testUser1,
			testProperties.getProperty("test.datagen.path"),
			worldPath,
			arenaName,
			arenaSub,
			new String[] {},
			1,
			50,
			true,
			resetUniverse
		);
	
		/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
		///
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new ArenaInitializationRule(),
			new GenericItemDataLoadRule()
		}));
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
				new ArenaEvolveRule()
			}));
		OlioContext octx = new OlioContext(cfg);

		logger.info("Initialize olio context - Arena");
		octx.initialize();
		assertNotNull("Root location is null", octx.getRootLocation());
		logger.info("Arena prepared");
		
		// logger.info(octx.getUniverse().toFullString());
		
		BaseRecord evt = octx.startOrContinueEpoch();
		BaseRecord[] locs = octx.getLocations();
		try {
			for(BaseRecord lrec : locs) {
				logger.info("Loc: " + lrec.get(FieldNames.FIELD_NAME));
				BaseRecord levt = octx.startOrContinueLocationEpoch(lrec);
				BaseRecord cevt = octx.startOrContinueIncrement();
				octx.evaluateIncrement();
				BaseRecord realm = octx.getRealm(lrec);

				BaseRecord popGrp1 = OlioUtil.getCreatePopulationGroup(octx, "Arena Party 1");
				assertNotNull("Population group is null", popGrp1);
				List<BaseRecord> party1  = OlioUtil.listGroupPopulation(octx, popGrp1);
				List<BaseRecord> party2  = OlioUtil.listGroupPopulation(octx, OlioUtil.getCreatePopulationGroup(octx, "Arena Party 2"));

				BaseRecord per1 = party1.get((new Random()).nextInt(party1.size()));
				BaseRecord per2 = party2.get((new Random()).nextInt(party2.size()));
				
				meetAndGreet(octx, per1, per2);
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}

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
	
}
