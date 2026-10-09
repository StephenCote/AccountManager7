package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.ClockException;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.MapUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.RollUtil;
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.olio.ThreatUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
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
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class OlioTestUtil {
	public static final Logger logger = LogManager.getLogger(OlioTestUtil.class);
	
	private static boolean resetUniverse = false;
	private static boolean resetWorld = false;
	private static String universeName = "Olio Universe";
	private static String worldName = "Olio World";
	
	public static void setResetUniverse(boolean resetUniverse) {
		OlioTestUtil.resetUniverse = resetUniverse;
	}

	public static void setResetWorld(boolean resetWorld) {
		OlioTestUtil.resetWorld = resetWorld;
	}

	public static OlioContext getContext(OrganizationContext orgCtx, String dataPath) {

		Factory mf = IOSystem.getActiveContext().getFactory();
		BaseRecord testUser1 = mf.getCreateUser(orgCtx.getAdminUser(), "testUser1", orgCtx.getOrganizationId());
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(true);
		AuditUtil.setLogToConsole(false);
		
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			testUser1,
			dataPath,
			universeName,
			worldName,
			new String[] {},
			2,
			50,
			resetWorld,
			resetUniverse
		);
		/// PB2 phase 1: enrolActingUser now defaults to false. The Olio test harness depends on
		/// context construction enrolling testUser1 in the Olio user role, so it opts in explicitly.
		cfg.setEnrolActingUser(true);

		resetWorld = false;
		resetUniverse = false;
	
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
		
		/*
		logger.info("Start/Continue Epoch");
		if(!octx.startOrContinueRealmEpochs()) {
			logger.error("Failed to start realm epochs");
		}
		*/
		AuditUtil.setLogToConsole(true);
		
		return octx;
	}
	
	public static CharacterPrint getLaurelPrint() {
		CharacterPrint cp = new CharacterPrint("Laurel Kelsey Carrera");
		cp.setGender("female");
		cp.setPerson("{firstName: \"Laurel\", middleName: \"Kelsey\", lastName: \"Carrera\", name: \"Laurel Kelsey Carrera\", age: 21, hairColor: {id:181}, hairStyle: \"long and tangled\", eyeColor:{id: 291}, alignment:\"CHAOTICGOOD\",race:[\"E\"],ethnicity:[\"NINE\"],trades:[\"enchantress\"]}");
		cp.setStatistics("{physicalStrength:7,physicalEndurance:12,manualDexterity:15,agility:17,mentalStrength:18,mentalEndurance:16, intelligence:15,wisdom:17,perception:15,creativity:18,spirituality:18,charisma:19}");
		cp.setOutfit("camisole,underwear,thigh-high heeled boots,amulet,jewelry:piercing:7:f:ear");
		cp.setPersonality("{machiavellianism:0.75}");
		return cp;
	}
	
	public static CharacterPrint getDukePrint() {
		CharacterPrint cp = new CharacterPrint("Duke Abraham Washington");
		cp.setPerson("{firstName: \"Duke\", middleName: \"Abraham\", lastName: \"Washington\", name: \"Duke Abraham Washington\", alignment:\"CHAOTICEVIL\",race:[\"E\", \"L\"],trades:[\"serial killer\"]}");
		cp.setStatistics("{physicalStrength:17,agility:17,intelligence:18,perception:19,charisma:12}");
		cp.setPersonality("{psychopathy:0.9,narcissism:0.65}");
		return cp;
	}
	
	public static void lookout(BaseRecord per1, BaseRecord per2) {
		double dist = GeoLocationUtil.getDistanceToState(per1.get(FieldNames.FIELD_STATE), per2.get(FieldNames.FIELD_STATE));
		double mps = AnimalUtil.walkMetersPerSecond(per1);
		double time = (dist / mps) / 60;
		double sprintTime = AnimalUtil.sprintMetersPerSecond(per1);
		double sprintDist = AnimalUtil.sprintMeterLimit(per1);
		double angle = GeoLocationUtil.getAngleBetweenInDegrees(per1.get(FieldNames.FIELD_STATE), per2.get(FieldNames.FIELD_STATE));

		logger.info("Distance Between: " + per1.get(FieldNames.FIELD_FIRST_NAME) + " is " + dist + " meters at " + mps + "mps from " + per2.get(FieldNames.FIELD_FIRST_NAME) + " / Angle " + angle + " " + DirectionEnumType.getDirectionFromDegrees(angle));
		logger.info("Can " + per1.get(FieldNames.FIELD_FIRST_NAME) + " see " + per2.get(FieldNames.FIELD_FIRST_NAME) + "? " + RollUtil.rollPerception(per1, per2).toString());
		logger.info("It would take " + per1.get(FieldNames.FIELD_FIRST_NAME) + " " + time + " minutes to walk there");
		logger.info("It would take " + per1.get(FieldNames.FIELD_FIRST_NAME) + " " + sprintTime + " seconds to sprint " + sprintDist + " meters");
	}

	
	public static void look(OlioContext ctx, BaseRecord realm, List<BaseRecord> pop, BaseRecord increment, BaseRecord per) {
		List<BaseRecord> fpop = StateUtil.observablePopulation(pop, per);
		Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
		try {
			Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = ThreatUtil.getThreatMap(ctx, realm, increment, map);
			String lar  = NarrativeUtil.lookaround(ctx, realm, increment, increment, fpop, per, tmap);
			logger.info(lar);
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}

	
	public static BaseRecord getImprintedCharacter(OlioContext ctx, List<BaseRecord> pop, CharacterPrint print) {
		Optional<BaseRecord> oper = pop.stream().filter(p -> print.getName().equals(p.get(FieldNames.FIELD_NAME))).findFirst();
		if(oper.isPresent()) {
			BaseRecord ooper = oper.get();
			ApparelUtil.outfitAndStage(ctx, null, Arrays.asList(ooper));
			return ooper;
		}
		List<BaseRecord> glist = pop.stream().filter(p -> print.getGender().equals(p.get(FieldNames.FIELD_GENDER))).collect(Collectors.toList());
		if(glist.size() == 0) {
			logger.error("Failed to find a gendered population");
			return null;
		}
		BaseRecord temp = glist.get((new Random()).nextInt(glist.size()));
		if(print.getOutfit() != null) {
			String[] outfit = print.getOutfit().split(",");
			BaseRecord apparel = ApparelUtil.constructApparel(ctx, 0L, temp, outfit);
			apparel.setValue(OlioFieldNames.FIELD_IN_USE, true);
			List<BaseRecord> wearl = apparel.get(OlioFieldNames.FIELD_WEARABLES);
			wearl.forEach(w -> {
				w.setValue(OlioFieldNames.FIELD_IN_USE, true);
			});
			IOSystem.getActiveContext().getRecordUtil().createRecord(apparel);
			BaseRecord store = temp.get(FieldNames.FIELD_STORE);
			List<BaseRecord> appl = store.get(OlioFieldNames.FIELD_APPAREL);
			for(BaseRecord a : appl) {
				IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), store, OlioFieldNames.FIELD_APPAREL, a, null, false);
			}
			appl.clear();
			appl.add(apparel);
			IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), store, OlioFieldNames.FIELD_APPAREL, apparel, null, true);
		}
		else {
			ApparelUtil.outfitAndStage(ctx, null, Arrays.asList(temp));
		}



		if(print.getStatistics() != null) {
			/// Patch the full record because some attributes feed into computed values so the computed values won't correctly reflect the dependent update
			IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(OlioModelNames.MODEL_CHAR_STATISTICS, print.getStatistics()), temp.get(OlioFieldNames.FIELD_STATISTICS), true);
		}
		if(print.getPersonality() != null) {
			/// Patch the full record because some attributes feed into computed values so the computed values won't correctly reflect the dependent update
			IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(ModelNames.MODEL_PERSONALITY, print.getPersonality()), temp.get(FieldNames.FIELD_PERSONALITY), true);
		}
		if(print.getPerson() != null) {
			IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(OlioModelNames.MODEL_CHAR_PERSON, print.getPerson()), temp);
		}
		
		return temp;
	}

	

	
	private static int wanderLength = 300;
	public static void wanderAmok(OlioContext ctx, BaseRecord event, BaseRecord increment, BaseRecord realm, List<BaseRecord> pop, BaseRecord per1) {
		/// Walk in random direction for 20Km (1km == (CellWidth * 10, or FeatureWidth/Height) * CellMultiplier - If the multiplier is 10, then the smallest distance of 1 is 10 meters, or, 100 is 1Km.) 
		assertNotNull("Person location is null", per1.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION));
		
		/// Move person back into a random cell in the realm origin
		BaseRecord org = realm.get(OlioFieldNames.FIELD_ORIGIN);
		List<BaseRecord> ocells = GeoLocationUtil.getCells(ctx, org);
		
		DirectionEnumType dir = DirectionEnumType.UNKNOWN;
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		logger.info(per1.get(FieldNames.FIELD_NAME) + " is wandering amok " + dir.toString().toLowerCase());
		BaseRecord state = per1.get(FieldNames.FIELD_STATE);
		state.setValue(OlioFieldNames.FIELD_CURRENT_LOCATION, ocells.get((new Random()).nextInt(ocells.size())));
		logger.info(per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_EAST) + ", " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_NORTH));
		
		int sx = per1.get(OlioFieldNames.FIELD_STATE_CURRENT_EAST);
		int sy = per1.get(OlioFieldNames.FIELD_STATE_CURRENT_NORTH);
		int rx = per1.get("state.currentLocation.eastings");
		int ry = per1.get("state.currentLocation.northings");
		
		long lid = per1.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION_ID);
		Set<Long> walkBack = new HashSet<>();
		walkBack.add(lid);

		boolean moved = StateUtil.moveByOneMeterInCell(ctx,  per1, dir);
		
		lid = per1.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION_ID);
		walkBack.add(lid);
		if(!moved) {
			logger.warn("Unable to move that way");
		}
		
		long lastLid = lid;
		for(int i = 0; i < wanderLength; i++) {
			moved = StateUtil.moveByOneMeterInCell(ctx, per1, dir);
			if(!moved) {
				logger.warn("Unable to move: " + per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + ", " + state.get("currentLocation.northings") + "; " + state.get(FieldNames.FIELD_CURRENT_EAST) + ", " + state.get(FieldNames.FIELD_CURRENT_NORTH));
			}

			List<BaseRecord> fpop = StateUtil.observablePopulation(pop, per1);
			Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
			Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = ThreatUtil.getThreatMap(ctx, realm, increment, map);
			List<BaseRecord> tinters = ThreatUtil.evaluateThreatMap(ctx, tmap, increment);
			long lid1 = per1.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION_ID);
			if(lid1 != lastLid) {
				assertFalse("Walked back from " + lastLid + " to " + lid1, walkBack.contains(lid1));
				lastLid = lid1;
				walkBack.add(lid1);
			}
		
		}
		
		int sx1 = per1.get(OlioFieldNames.FIELD_STATE_CURRENT_EAST);
		int sy1 = per1.get(OlioFieldNames.FIELD_STATE_CURRENT_NORTH);
		int rx1 = per1.get("state.currentLocation.eastings");
		int ry1 = per1.get("state.currentLocation.northings");
		long lid1 = per1.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION_ID);

		logger.info("Origin: " + sx + ", " + sy + "; #" + lid + ", " + rx + ", " + ry);
		logger.info("Dest: " + sx1 + ", " + sy1 + "; #" + lid1 + ", " + rx1 + ", " + ry1);
		
		StateUtil.queueUpdateLocation(ctx, per1);
		BaseRecord upar = GeoLocationUtil.getParentLocation(ctx, per1.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION));
		AnimalUtil.checkAnimalPopulation(ctx, realm, upar);
		Queue.processQueue();
		logger.info("Print current location - " + per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_EAST) + ", " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_NORTH));
		MapUtil.printPovLocationMap(ctx, realm, per1, 3);
		MapUtil.printLocationMap(ctx, upar, realm, pop);

	}
	
	public static void wanderAimlessly(OlioContext ctx, BaseRecord event, BaseRecord increment, BaseRecord realm, List<BaseRecord> pop, BaseRecord per1) {
		/// Walk northwest for 1Km.
		DirectionEnumType dir = DirectionEnumType.UNKNOWN;
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		logger.info(per1.get(FieldNames.FIELD_NAME) + " is wandering around");
		//List<BaseRecord> fpop = pop.stream().filter(p -> ((long)p.get(FieldNames.FIELD_ID)) != (long)per1.get(FieldNames.FIELD_ID)).collect(Collectors.toList());
		//Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
		
		BaseRecord state = per1.get(FieldNames.FIELD_STATE);
		logger.info(per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_EAST) + ", " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_NORTH));		
		logger.info("Wander " + dir.toString().toLowerCase());
		for(int i = 0; i < 100; i++) {
			boolean moved = StateUtil.moveByOneMeterInCell(ctx, per1, dir);
			if(!moved) {
				logger.warn("Failed to move: " + per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + ", " + state.get("currentLocation.northings") + "; " + state.get(FieldNames.FIELD_CURRENT_EAST) + ", " + state.get(FieldNames.FIELD_CURRENT_NORTH));
			}
		}

		List<BaseRecord> fpop = StateUtil.observablePopulation(pop, per1);
		Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
		Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = ThreatUtil.getThreatMap(ctx, realm, increment, map);
		String lar  = NarrativeUtil.lookaround(ctx, realm, increment, increment, fpop, per1, tmap);
		// logger.info(lar);
		
		dir = DirectionEnumType.UNKNOWN;
		while(dir == DirectionEnumType.UNKNOWN) {
			dir = OlioUtil.randomEnum(DirectionEnumType.class);
		}
		logger.info("Wander " + dir.toString().toLowerCase());
		for(int i = 0; i < 100; i++) {
			boolean moved = StateUtil.moveByOneMeterInCell(ctx, per1, dir);
			if(!moved) {
				logger.warn("Failed to move: " + per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + ", " + state.get("currentLocation.northings") + "; " + state.get(FieldNames.FIELD_CURRENT_EAST) + ", " + state.get(FieldNames.FIELD_CURRENT_NORTH));
			}

		}
		
		fpop = StateUtil.observablePopulation(pop, per1);
		map = ProfileUtil.getProfileMap(ctx, fpop);
		tmap = ThreatUtil.getThreatMap(ctx, realm, increment, map);
		lar  = NarrativeUtil.lookaround(ctx, realm, increment, increment, fpop, per1, tmap);

		logger.info(lar);

		StateUtil.queueUpdateLocation(ctx, state);
		Queue.processQueue();
		logger.info("Print current location - " + per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_EAST) + ", " + per1.get(OlioFieldNames.FIELD_STATE_CURRENT_NORTH));
		MapUtil.printLocationMap(ctx, GeoLocationUtil.getParentLocation(ctx, per1.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION)), realm, pop);

	}
	
	public static BaseRecord getRandmChatConfig(OlioContext octx, BaseRecord user, BaseRecord per1, BaseRecord per2) {
		octx.enroleAdmin(user);
		
		BaseRecord cfg = ChatUtil.getCreateChatConfig(user, "Chat - " + UUID.randomUUID().toString());
		
		try {
			BaseRecord inter = null;
			List<BaseRecord> inters = new ArrayList<>();
			for(int i = 0; i < 10; i++) {
				inter = InteractionUtil.randomInteraction(octx, per1, per2);
				if(inter != null) {
					inters.add(inter);
				}
			}
			IOSystem.getActiveContext().getRecordUtil().createRecords(inters.toArray(new BaseRecord[0]));
			
			cfg.set("event", octx.clock().getIncrement());
			cfg.set("universeName", octx.getUniverse().get(FieldNames.FIELD_NAME));
			cfg.set("worldName", octx.getWorld().get(FieldNames.FIELD_NAME));
			cfg.set("startMode", "system");
			cfg.set("assist", true);
			cfg.set("useNLP", true);
			cfg.set("setting", "random");
			cfg.set("includeScene", false);
			cfg.set("prune", true);
			cfg.set("rating", ESRBEnumType.E);

			cfg.set("model", "fim-local");
			cfg.set("systemCharacter", per2);
			cfg.set("userCharacter", per1);
			cfg.set(OlioFieldNames.FIELD_INTERACTIONS, inters);
			cfg.set(FieldNames.FIELD_TERRAIN, NarrativeUtil.getTerrain(octx, per1));
			NarrativeUtil.describePopulation(octx, cfg);
			// IOSystem.getActiveContext().getPolicyUtil().setTrace(true);
			cfg = IOSystem.getActiveContext().getAccessPoint().update(user, cfg);
			assertNotNull("Config was null", cfg);
			// IOSystem.getActiveContext().getPolicyUtil().setTrace(false);
		}
		catch(StackOverflowError | ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return cfg;
	}
	
	public static void outfitAndStage(OlioContext ctx) {
		List<BaseRecord> locs = ctx.getRealms();
		for(BaseRecord lrec : locs) {
			ApparelUtil.outfitAndStage(ctx, null, ctx.getRealmPopulation(lrec));
			ItemUtil.showerWithMoney(ctx, ctx.getRealmPopulation(lrec));
		}
		Queue.processQueue();
	}
	
	public static BaseRecord getPromptConfig(BaseRecord user, String name) {
		return getPromptConfig(user, name, "olio/llm/prompt.config.json");
	}
	
	public static BaseRecord getObjectPromptConfig(BaseRecord user, String name) {
		return getPromptConfig(user, name, "olio/llm/object.prompt.json");
	}
	
	public static BaseRecord getPromptConfig(BaseRecord user, String name, String resource) {
		BaseRecord opcfg = DocumentUtil.getRecord(user, OlioModelNames.MODEL_PROMPT_CONFIG, name, "~/Chat");
		if (opcfg != null) {
			return opcfg;
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);

		BaseRecord pcfg = null;
		BaseRecord ipcfg = JSONUtil.importObject(ResourceUtil.getInstance().getResource(resource), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());

		try {
			pcfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, user, ipcfg, plist);
			opcfg = IOSystem.getActiveContext().getAccessPoint().create(user, pcfg);
		}
		catch(NullPointerException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return opcfg;
	}
	
	public static BaseRecord getChatConfig(BaseRecord user, LLMServiceEnumType type, String name, Properties testProperties) {
		if(type == LLMServiceEnumType.OLLAMA) {
			return getOllamaOpenAIConfig(user, name, testProperties);
		}
		else if(type == LLMServiceEnumType.OPENAI) {
            return getOpenAIConfig(user, name, testProperties);
        }
        logger.error("Unsupported LLM service type: " + type);
        return null;
	}
	/// Connection info (serverUrl/apiKey/requestTimeout) lives on the system.connection
	/// sub-record now.  Create (idempotent) a connection in ~/Chat and return it so a
	/// chatConfig can reference it via the "connection" FK.
	///
	/// This overload sets NO dialect/upstream, so the row lands at the model default UNKNOWN and any
	/// chatConfig built on it resolves its transport through the deprecated serviceType fallback. Kept
	/// for the callers that depend on exactly that (getUnreachableOllamaConfig, the OpenAI/Azure config).
	public static BaseRecord getCreateConnection(BaseRecord user, String name, String serverUrl, String apiKey, int requestTimeout) {
		return getCreateConnection(user, name, serverUrl, apiKey, null, null, requestTimeout);
	}

	/// ---- LLM route/tier plumbing (LlmTestGate write-back) -------------------------------------------
	///
	/// The keys below are written by LlmTestGate.resolve (called from BaseTest.setup) and describe the
	/// ONE endpoint the tests should talk to this JVM: LiteLLM (OPENAI_COMPAT + master key) when the
	/// proxy is up, else native Ollama. Chat.configureChat re-queries the connection BY FK ID and reads
	/// dialect/upstream from THAT row, so everything here is persisted, never left in memory.
	public static final String PROP_CONNECTION_SERVER = "test.llm.connection.server";
	public static final String PROP_CONNECTION_DIALECT = "test.llm.connection.dialect";
	public static final String PROP_CONNECTION_UPSTREAM = "test.llm.connection.upstream";
	public static final String PROP_CONNECTION_API_KEY = "test.llm.connection.apiKey";
	public static final String PROP_MODEL_ANALYSIS = "test.llm.model.analysis";
	public static final String PROP_MODEL_PB = "test.llm.model.pb";
	public static final String PROP_ROUTE = "test.llm.route";
	public static final String ROUTE_LITELLM = "litellm";
	/// The LiteLLM alias of the Azure gpt-5 deployment (src/litellm/config.yaml model_list).
	public static final String PROP_LITELLM_AZURE_MODEL = "test.llm.litellm.azure.model";
	public static final String DEFAULT_AZURE_ALIAS = "gpt-5.6-terra";

	/// requestTimeout for picture-book / chap-book configs: JOSIEFIED 8B extraction over a long passage
	/// on a busy box legitimately runs past the 120s connection default.
	public static final int PB_REQUEST_TIMEOUT = 300;

	public static boolean isLitellmRoute(Properties props) {
		return ROUTE_LITELLM.equalsIgnoreCase(props.getProperty(PROP_ROUTE));
	}

	public static String azureModel(Properties props) {
		String m = props.getProperty(PROP_LITELLM_AZURE_MODEL);
		return (m != null && !m.isBlank()) ? m.trim() : DEFAULT_AZURE_ALIAS;
	}

	/// Model for analysis-style tests (extraction, tagging, prompts): the gate's write-back, else the
	/// pre-gate key so a caller that never ran BaseTest.setup still gets the configured name.
	public static String analysisModel(Properties props) {
		String m = props.getProperty(PROP_MODEL_ANALYSIS);
		if (m == null || m.isBlank()) m = props.getProperty("test.llm.ollama.model");
		return (m != null && !m.isBlank()) ? m.trim() : null;
	}

	/// Model for picture-book / chap-book tests: the gate's write-back, else test.llm.pb.model, else the
	/// analysis model (a box with only qwen3:8b still runs the pipeline; only the calibrated-quality
	/// assertions skip).
	public static String pbModel(Properties props) {
		String m = props.getProperty(PROP_MODEL_PB);
		if (m == null || m.isBlank()) m = props.getProperty("test.llm.pb.model");
		if (m == null || m.isBlank()) return analysisModel(props);
		return m.trim();
	}

	/// Record-name-safe form of a model name: `goekdenizguelmez/JOSIEFIED-Qwen3:8b` carries a '/', which
	/// must not appear in a data.directory record name (it reads as a path separator to the path
	/// utilities and to anyone eyeballing ~/Chat). Used when a config name embeds the model.
	public static String safeName(String name) {
		return name == null ? null : name.replace('/', '_').replace('\\', '_');
	}

	/// What the connection row for this JVM's route must look like. Built from the gate's write-back;
	/// falls back to a direct-Ollama shape when the gate has not run (no route key).
	public static final class ConnectionTarget {
		public final String serverUrl;
		/// null = "do not touch apiKey" (direct route). Non-null only when route=litellm.
		public final String apiKey;
		public final ConnectionDialectEnumType dialect;
		public final ConnectionUpstreamEnumType upstream;
		public final int requestTimeout;
		public ConnectionTarget(String serverUrl, String apiKey, ConnectionDialectEnumType dialect, ConnectionUpstreamEnumType upstream, int requestTimeout) {
			this.serverUrl = serverUrl;
			this.apiKey = apiKey;
			this.dialect = dialect;
			this.upstream = upstream;
			this.requestTimeout = requestTimeout;
		}
		public static ConnectionTarget fromProperties(Properties props, int requestTimeout) {
			String route = props.getProperty(PROP_ROUTE);
			boolean litellm = "litellm".equalsIgnoreCase(route);
			String server = props.getProperty(PROP_CONNECTION_SERVER);
			if (server == null || server.isBlank()) server = props.getProperty("test.llm.ollama.server");
			ConnectionDialectEnumType dialect = enumOr(ConnectionDialectEnumType.class, props.getProperty(PROP_CONNECTION_DIALECT), ConnectionDialectEnumType.OLLAMA);
			ConnectionUpstreamEnumType upstream = enumOr(ConnectionUpstreamEnumType.class, props.getProperty(PROP_CONNECTION_UPSTREAM), ConnectionUpstreamEnumType.OLLAMA);
			String key = props.getProperty(PROP_CONNECTION_API_KEY);
			/// Direct route: leave apiKey out entirely. A stale key from an earlier LiteLLM run is
			/// harmless to native Ollama and clearing it would mean re-persisting an encrypted field for
			/// nothing.
			String apiKey = (litellm && key != null && !key.isBlank()) ? key.trim() : null;
			return new ConnectionTarget(server != null ? server.trim() : null, apiKey, dialect, upstream, requestTimeout);
		}
		@Override
		public String toString() {
			return "serverUrl=" + serverUrl + " dialect=" + dialect + " upstream=" + upstream + " requestTimeout=" + requestTimeout + " apiKey=" + (apiKey != null ? "<set>" : "<untouched>");
		}
	}

	private static <E extends Enum<E>> E enumOr(Class<E> cls, String value, E fallback) {
		if (value == null || value.isBlank()) return fallback;
		try {
			return Enum.valueOf(cls, value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			logger.warn("Unknown " + cls.getSimpleName() + " '" + value + "' - using " + fallback);
			return fallback;
		}
	}

	/// Create (idempotent) a connection in ~/Chat with an EXPLICIT dialect and upstream. Passing null for
	/// either leaves the model default (UNKNOWN). `upstream` is asserted rather than inferred because
	/// OPENAI_COMPAT must never infer OLLAMA (KI-72) - a LiteLLM-fronted Ollama needs it set to get the
	/// Ollama extension parameters (num_ctx, think, ...). When the row already exists and a dialect was
	/// requested, it is reconciled to the requested shape so a stale row from an earlier run (other
	/// route, other box) cannot silently redirect this run.
	public static BaseRecord getCreateConnection(BaseRecord user, String name, String serverUrl, String apiKey, ConnectionDialectEnumType dialect, ConnectionUpstreamEnumType upstream, int requestTimeout) {
		BaseRecord conn = DocumentUtil.getRecord(user, ModelNames.MODEL_CONNECTION, name, "~/Chat");
		if (conn != null) {
			if (dialect != null) {
				return reconcileConnection(user, conn, new ConnectionTarget(serverUrl, apiKey, dialect, upstream, requestTimeout));
			}
			return conn;
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			BaseRecord c = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
			if (serverUrl != null) {
				c.set("serverUrl", serverUrl);
			}
			if (apiKey != null) {
				c.set("apiKey", apiKey);
			}
			c.set("requestTimeout", requestTimeout);
			if (dialect != null) {
				c.set("dialect", dialect);
			}
			if (upstream != null) {
				c.set(FieldNames.FIELD_UPSTREAM, upstream);
			}
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, c);
			assertNotNull("AccessPoint.create returned null for system.connection '" + name + "'", created);
			return created;
		} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
			logger.error(e);
		}
		return null;
	}

	private static final String[] CONNECTION_READ_FIELDS = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
		"serverUrl", "requestTimeout", "apiKey", "dialect", FieldNames.FIELD_UPSTREAM
	};

	/// Fresh, uncached read of a connection row with the transport fields projected. `create` returns
	/// identity fields only and a planMost read may have come from cache, so anything that compares or
	/// asserts these fields goes through here.
	public static BaseRecord readConnection(BaseRecord user, long connId) {
		Query cq = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, connId);
		cq.setRequest(CONNECTION_READ_FIELDS);
		cq.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, cq);
	}

	/// Bring a persisted connection in line with `target`. Compares serverUrl / dialect / upstream /
	/// requestTimeout (and apiKey only when the target carries one) against a fresh read, and when
	/// anything differs PATCHES exactly those fields:
	///   - explicit-field newInstance, NEVER the bare overload (that would materialise every field at
	///     its default and blank the columns not set - model-api.md);
	///   - `name` is included because the writer validates the patch record itself and
	///     common.nameId requires \S on name;
	///   - apiKey is in the patch ONLY when target.apiKey != null (route=litellm); on the direct route
	///     the field is left out of the patch entirely.
	/// The update result is asserted, never discarded - a null return is the only signal of a
	/// validation failure. Returns the reconciled row re-read from the DB.
	public static BaseRecord reconcileConnection(BaseRecord user, BaseRecord conn, ConnectionTarget target) {
		if (conn == null || target == null) return conn;
		long connId = conn.get(FieldNames.FIELD_ID);
		BaseRecord cur = readConnection(user, connId);
		if (cur == null) {
			logger.warn("Connection id=" + connId + " could not be re-read; leaving as-is");
			return conn;
		}
		String curUrl = cur.get("serverUrl");
		String curDialect = cur.get("dialect");
		String curUpstream = cur.get(FieldNames.FIELD_UPSTREAM);
		Integer curTimeout = cur.get("requestTimeout");
		String curKey = cur.get("apiKey");
		boolean urlDiff = target.serverUrl != null && !target.serverUrl.equals(curUrl);
		boolean dialectDiff = target.dialect != null && !target.dialect.name().equalsIgnoreCase(curDialect == null ? "UNKNOWN" : curDialect);
		boolean upstreamDiff = target.upstream != null && !target.upstream.name().equalsIgnoreCase(curUpstream == null ? "UNKNOWN" : curUpstream);
		boolean timeoutDiff = curTimeout == null || curTimeout.intValue() != target.requestTimeout;
		boolean keyDiff = target.apiKey != null && !target.apiKey.equals(curKey);
		if (!urlDiff && !dialectDiff && !upstreamDiff && !timeoutDiff && !keyDiff) {
			return cur;
		}
		logger.info("Reconciling connection '" + cur.get(FieldNames.FIELD_NAME) + "' id=" + connId
			+ " (url " + curUrl + "->" + target.serverUrl + ", dialect " + curDialect + "->" + target.dialect
			+ ", upstream " + curUpstream + "->" + target.upstream + ", timeout " + curTimeout + "->" + target.requestTimeout
			+ (keyDiff ? ", apiKey changed" : "") + ")");
		try {
			List<String> fields = new ArrayList<>(Arrays.asList(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
				"serverUrl", "dialect", FieldNames.FIELD_UPSTREAM, "requestTimeout"));
			if (target.apiKey != null) {
				fields.add("apiKey");
			}
			BaseRecord patch = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION, fields.toArray(new String[0]));
			patch.set(FieldNames.FIELD_ID, connId);
			patch.set(FieldNames.FIELD_OBJECT_ID, cur.get(FieldNames.FIELD_OBJECT_ID));
			patch.set(FieldNames.FIELD_NAME, cur.get(FieldNames.FIELD_NAME));
			patch.set("serverUrl", target.serverUrl != null ? target.serverUrl : curUrl);
			patch.set("dialect", target.dialect != null ? target.dialect : enumOr(ConnectionDialectEnumType.class, curDialect, ConnectionDialectEnumType.UNKNOWN));
			patch.set(FieldNames.FIELD_UPSTREAM, target.upstream != null ? target.upstream : enumOr(ConnectionUpstreamEnumType.class, curUpstream, ConnectionUpstreamEnumType.UNKNOWN));
			patch.set("requestTimeout", target.requestTimeout);
			if (target.apiKey != null) {
				patch.set("apiKey", target.apiKey);
			}
			BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(user, patch);
			assertNotNull("AccessPoint.update returned null reconciling system.connection id=" + connId + " to " + target
				+ " - check the log for 'Failed to modify record' / validation errors", updated);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			throw new AssertionError("Failed to build connection patch: " + e.getMessage(), e);
		}
		BaseRecord back = readConnection(user, connId);
		assertNotNull("Reconciled connection id=" + connId + " could not be read back", back);
		if (target.serverUrl != null) {
			assertTrue("serverUrl did not persist (" + back.get("serverUrl") + " != " + target.serverUrl + ")", target.serverUrl.equals(back.get("serverUrl")));
		}
		if (target.dialect != null) {
			assertTrue("dialect did not persist", target.dialect.name().equalsIgnoreCase(back.get("dialect")));
		}
		if (target.upstream != null) {
			assertTrue("upstream did not persist", target.upstream.name().equalsIgnoreCase(back.get(FieldNames.FIELD_UPSTREAM)));
		}
		return back;
	}

	/// Set requestTimeout on a chatConfig's connection sub-record (requestTimeout moved off chatConfig).
	public static void setConnectionRequestTimeout(BaseRecord user, BaseRecord chatConfig, int requestTimeout) {
		try {
			BaseRecord conn = chatConfig.get("connection");
			if (conn == null) {
				logger.warn("chatConfig '" + chatConfig.get(FieldNames.FIELD_NAME) + "' has no connection; cannot set requestTimeout");
				return;
			}
			conn.set("requestTimeout", requestTimeout);
			IOSystem.getActiveContext().getAccessPoint().update(user, conn);
		} catch (Exception e) {
			logger.error(e);
		}
	}

	/// Analysis-style chatConfig (extraction, tagging, prompt generation) on this JVM's resolved LLM
	/// route: model = test.llm.model.analysis, connection per LlmTestGate's write-back (LiteLLM +
	/// OPENAI_COMPAT + master key when the proxy is up, else native Ollama), requestTimeout 120.
	/// Idempotent by name; an existing config is RECONCILED (connection row, then model/serviceType)
	/// so a row left by an earlier run on another route/box cannot silently redirect this run.
	public static BaseRecord getOllamaOpenAIConfig(BaseRecord user, String name, Properties testProperties) {
		return getCreateTierChatConfig(user, name, testProperties, analysisModel(testProperties), 120, false);
	}

	/// Picture-book / chap-book chatConfig on this JVM's resolved LLM route: model = test.llm.model.pb
	/// (JOSIEFIED-Qwen3 8B, or its LiteLLM alias), think:false on chatOptions (the qwen3 family emits a
	/// <think> block otherwise, which the extraction parsers do not want), stream:false, temperature
	/// 0.3, requestTimeout PB_REQUEST_TIMEOUT. Same idempotent / reconcile behaviour as
	/// getOllamaOpenAIConfig. Analysis callers keep getOllamaOpenAIConfig; this is for the PB pipeline.
	public static BaseRecord getPbChatConfig(BaseRecord user, String name, Properties testProperties) {
		return getCreateTierChatConfig(user, name, testProperties, pbModel(testProperties), PB_REQUEST_TIMEOUT, true);
	}

	/// Azure gpt-5 chatConfig on its OWN connection row at this JVM's LiteLLM server (OPENAI_COMPAT +
	/// master key per LlmTestGate's write-back) with upstream OPENAI - NOT the OLLAMA upstream the
	/// gate resolves for the local-container alias. The upstream is what gates the Ollama extensions
	/// (KI-72): with upstream OLLAMA, ChatUtil puts top_k/min_p/repeat_penalty/... on the wire and
	/// Azure answers `400 Unknown parameter: 'min_p'` - measured 2026-10-08 through LiteLLM, whose
	/// drop_params:true only drops OpenAI-standard params it recognises and passes unknown extras
	/// straight through. Model = the alias in test.llm.litellm.azure.model (gpt-5.6-terra). Azure is
	/// LiteLLM-only in this test tree - test.llm.openai.* is blank by design - so this is only
	/// meaningful when isLitellmRoute(); on a direct route the config would name a model the native
	/// Ollama does not have. Callers assume() on both that and the LITELLM_LIVE opt-in, because every
	/// chat through it is a paid call. Same idempotent / reconcile behaviour as getOllamaOpenAIConfig.
	public static BaseRecord getAzureChatConfig(BaseRecord user, String name, Properties testProperties) {
		ConnectionTarget routed = ConnectionTarget.fromProperties(testProperties, 120);
		ConnectionTarget azure = new ConnectionTarget(routed.serverUrl, routed.apiKey, routed.dialect,
			ConnectionUpstreamEnumType.OPENAI, routed.requestTimeout);
		return getCreateChatConfig(user, name, azure, azureModel(testProperties), 120, false);
	}

	/// serviceType is the deprecated fallback; keep it tracking the dialect so a connection row that
	/// somehow reads UNKNOWN still resolves to the same transport.
	private static LLMServiceEnumType serviceTypeFor(ConnectionDialectEnumType dialect) {
		if (dialect == null) return LLMServiceEnumType.OLLAMA;
		try {
			return LLMServiceEnumType.valueOf(dialect.name());
		} catch (IllegalArgumentException e) {
			return LLMServiceEnumType.OLLAMA;
		}
	}

	/// NATIVE Ollama chatConfig (dialect OLLAMA, no proxy) at test.llm.ollama.server - the direct URL of
	/// whichever tier the gate resolved - with the real Ollama model name from test.llm.pb.model (the
	/// gate leaves that key as the direct-endpoint input; test.llm.model.pb may be a LiteLLM alias the
	/// native server does not know). For tests that must observe the native server itself (/api/ps,
	/// keep_alive:0 unload) rather than go through the routed connection. Same idempotent / reconcile
	/// behaviour as getOllamaOpenAIConfig.
	public static BaseRecord getNativeOllamaPbConfig(BaseRecord user, String name, Properties testProperties, int requestTimeout) {
		String server = testProperties.getProperty("test.llm.ollama.server");
		String model = testProperties.getProperty("test.llm.pb.model");
		if (model == null || model.isBlank()) model = testProperties.getProperty("test.llm.ollama.model");
		ConnectionTarget target = new ConnectionTarget(server != null ? server.trim() : null, null,
			ConnectionDialectEnumType.OLLAMA, ConnectionUpstreamEnumType.OLLAMA, requestTimeout);
		return getCreateChatConfig(user, name, target, model != null ? model.trim() : null, requestTimeout, true);
	}

	private static BaseRecord getCreateTierChatConfig(BaseRecord user, String name, Properties testProperties, String model, int requestTimeout, boolean pbOptions) {
		return getCreateChatConfig(user, name, ConnectionTarget.fromProperties(testProperties, requestTimeout), model, requestTimeout, pbOptions);
	}

	/// Get-or-create a chatConfig named `name` in ~/Chat on an EXPLICIT connection target and model,
	/// reconciling an existing row (connection, model, serviceType, and the PB options when
	/// `pbOptions`) rather than returning it as found. The routed helpers above all come through here;
	/// it is public for harnesses that choose their own box/model (TestPictureBookCustom).
	public static BaseRecord getCreateChatConfig(BaseRecord user, String name, ConnectionTarget target, String model, int requestTimeout, boolean pbOptions) {
		LLMServiceEnumType serviceType = serviceTypeFor(target.dialect);
		String connName = name + " Connection";
		BaseRecord cfg = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
		if (cfg != null) {
			/// Existing config: reconcile the referenced connection row first (Chat re-queries it by FK id),
			/// then the config's own model/serviceType, then RE-FETCH - the earlier planMost read may be
			/// cached and CacheDBSearch does not invalidate a parent when its nested connection changes.
			BaseRecord conn = cfg.get("connection");
			if (conn == null) {
				conn = getCreateConnection(user, connName, target.serverUrl, target.apiKey, target.dialect, target.upstream, requestTimeout);
			} else {
				conn = reconcileConnection(user, conn, target);
			}
			String curModel = cfg.get("model");
			String curService = cfg.get("serviceType");
			BaseRecord curConn = cfg.get("connection");
			boolean connDiff = curConn == null || conn == null;
			if (!connDiff) {
				long curConnId = curConn.get(FieldNames.FIELD_ID);
				long newConnId = conn.get(FieldNames.FIELD_ID);
				connDiff = curConnId != newConnId;
			}
			boolean modelDiff = model != null && !model.equals(curModel);
			boolean serviceDiff = !serviceType.name().equalsIgnoreCase(curService);
			try {
				/// PB options: a row created earlier as an analysis config under a name now routed here would
				/// otherwise keep stream:true / think:true and hand <think> blocks to the extraction parsers.
				/// chatOptions is an embedded model (no table), so it rides on the parent patch as a whole.
				boolean streamDiff = false;
				boolean optsDiff = false;
				BaseRecord opts = null;
				if (pbOptions) {
					Boolean curStream = cfg.get("stream");
					streamDiff = curStream == null || curStream;
					opts = cfg.get("chatOptions");
					if (opts == null) {
						opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
						optsDiff = true;
					} else {
						Boolean think = opts.get("think");
						Double temp = opts.get("temperature");
						optsDiff = think == null || think || temp == null || Math.abs(temp - 0.3) > 1e-9;
					}
					if (optsDiff) {
						opts.set("think", false);
						opts.set("temperature", 0.3);
					}
				}
				if (modelDiff || serviceDiff || connDiff || streamDiff || optsDiff) {
					logger.info("Reconciling chatConfig '" + name + "' (model " + curModel + "->" + model + ", serviceType " + curService + "->" + serviceType
						+ (connDiff ? ", connection" : "") + (streamDiff ? ", stream->false" : "") + (optsDiff ? ", chatOptions think->false temperature->0.3" : "") + ")");
					List<String> fields = new ArrayList<>(Arrays.asList(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "model", "serviceType"));
					if (connDiff) fields.add("connection");
					if (streamDiff) fields.add("stream");
					if (optsDiff) fields.add("chatOptions");
					BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, fields.toArray(new String[0]));
					patch.set(FieldNames.FIELD_ID, cfg.get(FieldNames.FIELD_ID));
					patch.set(FieldNames.FIELD_OBJECT_ID, cfg.get(FieldNames.FIELD_OBJECT_ID));
					patch.set(FieldNames.FIELD_NAME, cfg.get(FieldNames.FIELD_NAME));
					patch.set("model", model != null ? model : curModel);
					patch.set("serviceType", serviceType);
					if (connDiff) patch.set("connection", conn);
					if (streamDiff) patch.set("stream", false);
					if (optsDiff) patch.set("chatOptions", opts);
					BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(user, patch);
					assertNotNull("AccessPoint.update returned null reconciling chatConfig '" + name + "'", updated);
				}
			} catch (FieldException | ModelNotFoundException | ValueException e) {
				logger.error(e);
				throw new AssertionError("Failed to build chatConfig patch: " + e.getMessage(), e);
			}
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_ID, cfg.get(FieldNames.FIELD_ID));
			q.setCache(false);
			OlioUtil.planMost(q);
			BaseRecord fresh = IOSystem.getActiveContext().getAccessPoint().find(user, q);
			assertNotNull("chatConfig '" + name + "' could not be re-read after reconcile", fresh);
			return fresh;
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord ocfg = null;
		try {
			cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			cfg.set("serviceType", serviceType);
			cfg.set("connection", getCreateConnection(user, connName, target.serverUrl, target.apiKey, target.dialect, target.upstream, requestTimeout));
			cfg.set("model", model);
			if (pbOptions) {
				cfg.set("stream", false);
				BaseRecord opts = cfg.get("chatOptions");
				if (opts == null) {
					opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
					cfg.set("chatOptions", opts);
				}
				opts.set("think", false);
				opts.set("temperature", 0.3);
			}
			ocfg = IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
			assertNotNull("AccessPoint.create returned null for chatConfig '" + name + "'", ocfg);
		} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
			logger.error(e);
			return null;
		}
		/// Create returns identity fields only; hand back the fully-populated record the callers expect.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_ID, ocfg.get(FieldNames.FIELD_ID));
		q.setCache(false);
		OlioUtil.planMost(q);
		BaseRecord fresh = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		return fresh != null ? fresh : ocfg;
	}

	/// Build (idempotent) an OLLAMA chatConfig whose connection points at an UNREACHABLE host so any LLM
	/// call through it genuinely fails at the network layer (connection refused / short-timeout) — used to
	/// exercise the HARD-failure path with a REAL failing network call, never a mock. requestTimeout is kept
	/// short so the failure returns fast. Returns a fully-resolved record (connection FK populated) so a
	/// Chat can read the (unreachable) serverUrl off it.
	public static BaseRecord getUnreachableOllamaConfig(BaseRecord user, String name, String unreachableUrl, String model, int requestTimeout) {
		BaseRecord cfg = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
		if (cfg != null) {
			return OlioUtil.getFullRecord(cfg);
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			cfg.set("serviceType", LLMServiceEnumType.OLLAMA);
			cfg.set("connection", getCreateConnection(user, name + " Connection", unreachableUrl, null, requestTimeout));
			cfg.set("model", model);
			IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
		} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
			logger.error(e);
		}
		BaseRecord created = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
		return created != null ? OlioUtil.getFullRecord(created) : null;
	}

	public static BaseRecord getOpenAIConfig(BaseRecord user, String name, Properties testProperties) {
		BaseRecord ocfg = null;
		BaseRecord cfg = DocumentUtil.getRecord(user, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
		if (cfg != null) {
			/// Return a fully-populated record so the connection FK sub-record is resolved.
			return OlioUtil.getFullRecord(cfg);
		}
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
			cfg.set("serviceType", LLMServiceEnumType.OPENAI);
			cfg.set("apiVersion", testProperties.getProperty("test.llm.openai.version"));
			cfg.set("connection", getCreateConnection(user, name + " Connection", testProperties.getProperty("test.llm.openai.server"), testProperties.getProperty("test.llm.openai.authorizationToken"), 120));
			cfg.set("model", testProperties.getProperty("test.llm.openai.model"));

			ocfg = IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
		} catch (FieldException | ModelNotFoundException | ValueException | FactoryException e) {
			logger.error(e);
		}
		return ocfg;
	}
	
	public static BaseRecord getRandomChatConfig(BaseRecord user, String dataPath) {
		logger.info("Test LLM Chat Config");
	
		OlioContext ctx = OlioContextUtil.getGridContext(user, dataPath, universeName, worldName, false);
		assertNotNull("Context is null", ctx);
		List<BaseRecord> realms = ctx.getRealms();
		assertTrue("Expected at least one realm", realms.size() > 0);
		BaseRecord popGrp = realms.get(0).get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Expected a population group", popGrp);
		List<BaseRecord> pop  = OlioUtil.listGroupPopulation(ctx, popGrp);
		assertTrue("Expected a population", pop.size() > 0);
		
		List<BaseRecord> rlms = ctx.getRealms();
		for(BaseRecord r : rlms) {

			/// Depending on the staging rule, the population may not yet be dressed or have possessions
			///
			ApparelUtil.outfitAndStage(ctx, null, pop);
			ItemUtil.showerWithMoney(ctx, pop);
			Queue.processQueue();
			
		}

		BaseRecord per1 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord per2 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord inter = null;
		for(int i = 0; i < 10; i++) {
			inter = InteractionUtil.randomInteraction(ctx, per1, per2);
			if(inter != null) {
				break;
			}
		}

		BaseRecord levt = null;
		try {
			levt = ctx.realmClock(ctx.getRealms().get(0)).getIncrement();
		} catch (ClockException e) {
			logger.error(e);
			return null;
		}
		
		ParameterList clist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		clist.parameter(FieldNames.FIELD_NAME, "Chat Config - " + UUID.randomUUID().toString());

		BaseRecord cfg = null;
		BaseRecord ocfg = null;
		String setting = NarrativeUtil.getRandomSetting();
		try {
			cfg = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, clist);
			cfg.set("rating", ESRBEnumType.E);
			cfg.set("alignment", levt.get("alignment"));
			cfg.set("systemCharacter", per1);
			cfg.set("userCharacter", per2);
			cfg.set(OlioFieldNames.FIELD_INTERACTIONS, Arrays.asList(new BaseRecord[] {inter}));
			cfg.set("assist", true);
			cfg.set("useNLP", false);
			cfg.set("prune", true);
			cfg.set("setting", null);
			cfg.set("includeScene", true);
			cfg.set("event", ctx.clock().getIncrement());
			cfg.set(FieldNames.FIELD_TERRAIN, NarrativeUtil.getTerrain(ctx, per2));
			cfg.set("systemNarrative", NarrativeUtil.getNarrative(ctx, per1, setting));
			cfg.set("userNarrative", NarrativeUtil.getNarrative(ctx, per2, setting));
			NarrativeUtil.describePopulation(ctx, cfg);
			ocfg = IOSystem.getActiveContext().getAccessPoint().create(user, cfg);
			
		}
		catch(NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull("CFG was null", ocfg);
		
		return ocfg;
	
	}
}

