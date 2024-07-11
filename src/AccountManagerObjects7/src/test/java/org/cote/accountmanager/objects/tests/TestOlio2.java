package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
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
import org.cote.accountmanager.olio.ActionUtil;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.AnimalUtil;
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
import org.cote.accountmanager.olio.rules.GenericStateRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsRule;
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
			resetWorld,
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
			new HierarchicalNeedsRule()
		}));
		
		cfg.getStateRules().addAll(Arrays.asList(new IOlioStateRule[] {
			new GenericStateRule()	
		}));
		
		OlioContext octx = new OlioContext(cfg);

		logger.info("Initialize OlioContext");
		octx.initialize();
		assertTrue("Expected context to be initialized", octx.isInitialized());
		
		BaseRecord[] realms = octx.getRealms();
		assertTrue("Expected realms", realms.length > 0);
		BaseRecord evt = octx.startOrContinueEpoch();
		
		BaseRecord realm = realms[0];
		BaseRecord lrec = realm.get("origin");
		assertNotNull("Location was null", lrec);
		
		BaseRecord levt = octx.startOrContinueLocationEpoch(lrec);
		assertNotNull("Location epoch is null", levt);
		BaseRecord cevt = octx.startOrContinueIncrement();
		octx.evaluateIncrement();
		List<BaseRecord> pop = octx.getPopulation(lrec);

		BaseRecord per1 = getImprintedCharacter(octx, pop, getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = getImprintedCharacter(octx, pop, getDukePrint());
		assertNotNull("Person was null", per2);
		
		look(octx, realm, pop, cevt, per1);
		look(octx, realm, pop, cevt, per2);

		MapUtil.printLocationMap(octx, lrec, realm, pop);
		MapUtil.printRealmMap(octx, realm);
		MapUtil.printAdmin2Map(octx, GeoLocationUtil.getParentLocation(octx, realms[0].get("origin")));
		//BaseRecord upar = GeoLocationUtil.getParentLocation(octx, per1.get("state.currentLocation"));
		MapUtil.printPovLocationMap(octx, realm, per1, 3);
		MapUtil.printPovLocationMap(octx, realm, per2, 3);
		
		
		DirectionEnumType dir = DirectionEnumType.UNKNOWN;
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		StateUtil.move(octx, per1, dir);
		
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
	
	private void look(OlioContext ctx, BaseRecord realm, List<BaseRecord> pop, BaseRecord increment, BaseRecord per) {
		List<BaseRecord> fpop = StateUtil.observablePopulation(pop, per);
		Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
		Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = NeedsUtil.agitate(ctx, realm, increment, map, false);
		String lar  = NarrativeUtil.lookaround(ctx, realm, increment, increment, fpop, per, tmap);
		logger.info(lar);

	}

	
	private BaseRecord getImprintedCharacter(OlioContext ctx, List<BaseRecord> pop, CharacterPrint print) {
		Optional<BaseRecord> oper = pop.stream().filter(p -> print.getName().equals(p.get(FieldNames.FIELD_NAME))).findFirst();
		if(oper.isPresent()) {
			return oper.get();
		}
		List<BaseRecord> glist = pop.stream().filter(p -> print.getGender().equals(p.get("gender"))).collect(Collectors.toList());
		if(glist.size() == 0) {
			logger.error("Failed to find a gendered population");
			return null;
		}
		BaseRecord temp = glist.get((new Random()).nextInt(glist.size()));
		if(print.getOutfit() != null) {
			String[] outfit = print.getOutfit().split(",");
			BaseRecord apparel = ApparelUtil.constructApparel(ctx, 0L, temp, outfit);
			apparel.setValue("inuse", true);
			List<BaseRecord> wearl = apparel.get("wearables");
			wearl.forEach(w -> {
				w.setValue("inuse", true);
			});
			IOSystem.getActiveContext().getRecordUtil().createRecord(apparel);
			BaseRecord store = temp.get("store");
			List<BaseRecord> appl = store.get("apparel");
			for(BaseRecord a : appl) {
				IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), store, "apparel", a, null, false);
			}
			appl.clear();
			appl.add(apparel);
			IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), store, "apparel", apparel, null, true);
		}



		if(print.getStatistics() != null) {
			/// Patch the full record because some attributes feed into computed values so the computed values won't correctly reflect the dependent update
			IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(ModelNames.MODEL_CHAR_STATISTICS, print.getStatistics()), temp.get("statistics"), true);
		}
		if(print.getPersonality() != null) {
			/// Patch the full record because some attributes feed into computed values so the computed values won't correctly reflect the dependent update
			IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(ModelNames.MODEL_PERSONALITY, print.getPersonality()), temp.get("personality"), true);
		}
		if(print.getPerson() != null) {
			IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(ModelNames.MODEL_CHAR_PERSON, print.getPerson()), temp);
		}
		
		return temp;
	}

	private CharacterPrint getLaurelPrint() {
		CharacterPrint cp = new CharacterPrint("Laurel Kelsey Carrera");
		cp.setGender("female");
		cp.setPerson("{firstName: \"Laurel\", middleName: \"Kelsey\", lastName: \"Carrera\", name: \"Laurel Kelsey Carrera\", age: 21, hairColor: {id:181}, hairStyle: \"long and tangled\", eyeColor:{id: 291}, alignment:\"CHAOTICGOOD\",race:[\"E\"],ethnicity:[\"NINE\"],trades:[\"enchantress\"]}");
		cp.setStatistics("{physicalStrength:7,physicalEndurance:12,manualDexterity:15,agility:17,mentalStrength:18,mentalEndurance:16, intelligence:15,wisdom:17,perception:15,creativity:18,spirituality:18,charisma:19}");
		cp.setOutfit("camisole,underwear,thigh-high heeled boots,amulet,jewelry:piercing:7:f:ear");
		cp.setPersonality("{machiavellianism:0.75}");
		return cp;
	}
	
	private CharacterPrint getDukePrint() {
		CharacterPrint cp = new CharacterPrint("Duke Abraham Washington");
		cp.setPerson("{firstName: \"Duke\", middleName: \"Abraham\", lastName: \"Washington\", name: \"Duke Abraham Washington\", alignment:\"CHAOTICEVIL\",race:[\"E\", \"L\"],trades:[\"serial killer\"]}");
		cp.setStatistics("{physicalStrength:17,agility:17,intelligence:18,perception:19,charisma:12}");
		cp.setPersonality("{psychopathy:0.9,narcissism:0.65}");
		return cp;
	}
	
	class CharacterPrint{
		private String name = null;
		private String gender = "male";
		private String outfit = null;
		private String person = null;
		private String statistics = null;
		private String personality = null;
		public CharacterPrint(String name) {
			this.name = name; 
		}
		
		public String getGender() {
			return gender;
		}

		public void setGender(String gender) {
			this.gender = gender;
		}

		public String getOutfit() {
			return outfit;
		}
		public void setOutfit(String outfit) {
			this.outfit = outfit;
		}
		public String getPerson() {
			return person;
		}
		public void setPerson(String person) {
			this.person = person;
		}
		public String getStatistics() {
			return statistics;
		}
		public void setStatistics(String statistics) {
			this.statistics = statistics;
		}
		public String getPersonality() {
			return personality;
		}
		public void setPersonality(String personality) {
			this.personality = personality;
		}
		public String getName() {
			return name;
		}
		
	}
	
	private int wanderLength = 300;
	private void wanderAmok(OlioContext ctx, BaseRecord event, BaseRecord increment, BaseRecord realm, List<BaseRecord> pop, BaseRecord per1) {
		/// Walk in random direction for 20Km (1km == (CellWidth * 10, or FeatureWidth/Height) * CellMultiplier - If the multiplier is 10, then the smallest distance of 1 is 10 meters, or, 100 is 1Km.) 
		assertNotNull("Person location is null", per1.get("state.currentLocation"));
		
		/// Move person back into a random cell in the realm origin
		BaseRecord org = realm.get("origin");
		List<BaseRecord> ocells = GeoLocationUtil.getCells(ctx, org);
		
		DirectionEnumType dir = DirectionEnumType.UNKNOWN;
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		logger.info(per1.get(FieldNames.FIELD_NAME) + " is wandering amok " + dir.toString().toLowerCase());
		BaseRecord state = per1.get("state");
		state.setValue("currentLocation", ocells.get((new Random()).nextInt(ocells.size())));
		logger.info(per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get("state.currentEast") + ", " + per1.get("state.currentNorth"));
		
		int sx = per1.get("state.currentEast");
		int sy = per1.get("state.currentNorth");
		int rx = per1.get("state.currentLocation.eastings");
		int ry = per1.get("state.currentLocation.northings");
		
		long lid = per1.get("state.currentLocation.id");
		Set<Long> walkBack = new HashSet<>();
		walkBack.add(lid);

		boolean moved = StateUtil.move(ctx,  per1, dir);
		
		lid = per1.get("state.currentLocation.id");
		walkBack.add(lid);
		if(!moved) {
			logger.warn("Unable to move that way");
		}
		
		long lastLid = lid;
		for(int i = 0; i < wanderLength; i++) {
			moved = StateUtil.move(ctx, per1, dir);
			if(!moved) {
				logger.warn("Unable to move: " + per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + ", " + state.get("currentLocation.northings") + "; " + state.get("currentEast") + ", " + state.get("currentNorth"));
			}

			List<BaseRecord> fpop = StateUtil.observablePopulation(pop, per1);
			Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
			Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = NeedsUtil.agitate(ctx, realm, increment, map, true);
			List<BaseRecord> tinters = ThreatUtil.evaluateThreatMap(ctx, tmap, increment);
			long lid1 = per1.get("state.currentLocation.id");
			if(lid1 != lastLid) {
				assertFalse("Walked back from " + lastLid + " to " + lid1, walkBack.contains(lid1));
				lastLid = lid1;
				walkBack.add(lid1);
			}
		
		}
		
		int sx1 = per1.get("state.currentEast");
		int sy1 = per1.get("state.currentNorth");
		int rx1 = per1.get("state.currentLocation.eastings");
		int ry1 = per1.get("state.currentLocation.northings");
		long lid1 = per1.get("state.currentLocation.id");

		logger.info("Origin: " + sx + ", " + sy + "; #" + lid + ", " + rx + ", " + ry);
		logger.info("Dest: " + sx1 + ", " + sy1 + "; #" + lid1 + ", " + rx1 + ", " + ry1);
		
		StateUtil.queueUpdate(ctx, per1);
		BaseRecord upar = GeoLocationUtil.getParentLocation(ctx, per1.get("state.currentLocation"));
		AnimalUtil.checkAnimalPopulation(ctx, realm, upar);
		ctx.processQueue();
		logger.info("Print current location - " + per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get("state.currentEast") + ", " + per1.get("state.currentNorth"));
		MapUtil.printPovLocationMap(ctx, realm, per1, 3);
		MapUtil.printLocationMap(ctx, upar, realm, pop);

	}
	
	private void wanderAimlessly(OlioContext ctx, BaseRecord event, BaseRecord increment, BaseRecord realm, List<BaseRecord> pop, BaseRecord per1) {
		/// Walk northwest for 1Km.
		DirectionEnumType dir = DirectionEnumType.UNKNOWN;
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		logger.info(per1.get(FieldNames.FIELD_NAME) + " is wandering around");
		//List<BaseRecord> fpop = pop.stream().filter(p -> ((long)p.get(FieldNames.FIELD_ID)) != (long)per1.get(FieldNames.FIELD_ID)).collect(Collectors.toList());
		//Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
		
		BaseRecord state = per1.get("state");
		logger.info(per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get("state.currentEast") + ", " + per1.get("state.currentNorth"));		
		logger.info("Wander " + dir.toString().toLowerCase());
		for(int i = 0; i < 100; i++) {
			boolean moved = StateUtil.move(ctx, per1, dir);
			if(!moved) {
				logger.warn("Failed to move: " + per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + ", " + state.get("currentLocation.northings") + "; " + state.get("currentEast") + ", " + state.get("currentNorth"));
			}
		}

		List<BaseRecord> fpop = StateUtil.observablePopulation(pop, per1);
		Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
		Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = NeedsUtil.agitate(ctx, realm, increment, map, false);
		String lar  = NarrativeUtil.lookaround(ctx, realm, increment, increment, fpop, per1, tmap);
		// logger.info(lar);
		
		dir = DirectionEnumType.UNKNOWN;
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		logger.info("Wander " + dir.toString().toLowerCase());
		for(int i = 0; i < 100; i++) {
			boolean moved = StateUtil.move(ctx, per1, dir);
			if(!moved) {
				logger.warn("Failed to move: " + per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + ", " + state.get("currentLocation.northings") + "; " + state.get("currentEast") + ", " + state.get("currentNorth"));
			}

		}
		
		fpop = StateUtil.observablePopulation(pop, per1);
		map = ProfileUtil.getProfileMap(ctx, fpop);
		tmap = NeedsUtil.agitate(ctx, realm, increment, map, false);
		lar  = NarrativeUtil.lookaround(ctx, realm, increment, increment, fpop, per1, tmap);

		logger.info(lar);

		StateUtil.queueUpdate(ctx, state);
		ctx.processQueue();
		logger.info("Print current location - " + per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get("state.currentEast") + ", " + per1.get("state.currentNorth"));
		MapUtil.printLocationMap(ctx, GeoLocationUtil.getParentLocation(ctx, per1.get("state.currentLocation")), realm, pop);

	}
	

	
}
