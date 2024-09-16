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
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.DirectionEnumType;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.MapUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.RollUtil;
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.olio.ThreatUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
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
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AuditUtil;

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
		double dist = GeoLocationUtil.getDistanceToState(per1.get("state"), per2.get("state"));
		double mps = AnimalUtil.walkMetersPerSecond(per1);
		double time = (dist / mps) / 60;
		double sprintTime = AnimalUtil.sprintMetersPerSecond(per1);
		double sprintDist = AnimalUtil.sprintMeterLimit(per1);
		double angle = GeoLocationUtil.getAngleBetweenInDegrees(per1.get("state"), per2.get("state"));

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
			BaseRecord store = temp.get(FieldNames.FIELD_STORE);
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
			IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(OlioModelNames.MODEL_CHAR_STATISTICS, print.getStatistics()), temp.get("statistics"), true);
		}
		if(print.getPersonality() != null) {
			/// Patch the full record because some attributes feed into computed values so the computed values won't correctly reflect the dependent update
			IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(ModelNames.MODEL_PERSONALITY, print.getPersonality()), temp.get("personality"), true);
		}
		if(print.getPerson() != null) {
			IOSystem.getActiveContext().getRecordUtil().patch(RecordFactory.importRecord(OlioModelNames.MODEL_CHAR_PERSON, print.getPerson()), temp);
		}
		
		return temp;
	}

	

	
	private static int wanderLength = 300;
	public static void wanderAmok(OlioContext ctx, BaseRecord event, BaseRecord increment, BaseRecord realm, List<BaseRecord> pop, BaseRecord per1) {
		/// Walk in random direction for 20Km (1km == (CellWidth * 10, or FeatureWidth/Height) * CellMultiplier - If the multiplier is 10, then the smallest distance of 1 is 10 meters, or, 100 is 1Km.) 
		assertNotNull("Person location is null", per1.get("state.currentLocation"));
		
		/// Move person back into a random cell in the realm origin
		BaseRecord org = realm.get(OlioFieldNames.FIELD_ORIGIN);
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

		boolean moved = StateUtil.moveByOneMeterInCell(ctx,  per1, dir);
		
		lid = per1.get("state.currentLocation.id");
		walkBack.add(lid);
		if(!moved) {
			logger.warn("Unable to move that way");
		}
		
		long lastLid = lid;
		for(int i = 0; i < wanderLength; i++) {
			moved = StateUtil.moveByOneMeterInCell(ctx, per1, dir);
			if(!moved) {
				logger.warn("Unable to move: " + per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + ", " + state.get("currentLocation.northings") + "; " + state.get("currentEast") + ", " + state.get("currentNorth"));
			}

			List<BaseRecord> fpop = StateUtil.observablePopulation(pop, per1);
			Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, fpop);
			Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = ThreatUtil.getThreatMap(ctx, realm, increment, map);
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
		
		StateUtil.queueUpdateLocation(ctx, per1);
		BaseRecord upar = GeoLocationUtil.getParentLocation(ctx, per1.get("state.currentLocation"));
		AnimalUtil.checkAnimalPopulation(ctx, realm, upar);
		Queue.processQueue();
		logger.info("Print current location - " + per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get("state.currentEast") + ", " + per1.get("state.currentNorth"));
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
		
		BaseRecord state = per1.get("state");
		logger.info(per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get("state.currentEast") + ", " + per1.get("state.currentNorth"));		
		logger.info("Wander " + dir.toString().toLowerCase());
		for(int i = 0; i < 100; i++) {
			boolean moved = StateUtil.moveByOneMeterInCell(ctx, per1, dir);
			if(!moved) {
				logger.warn("Failed to move: " + per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + ", " + state.get("currentLocation.northings") + "; " + state.get("currentEast") + ", " + state.get("currentNorth"));
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
				logger.warn("Failed to move: " + per1.get(FieldNames.FIELD_NAME) + " " + state.get("currentLocation.eastings") + ", " + state.get("currentLocation.northings") + "; " + state.get("currentEast") + ", " + state.get("currentNorth"));
			}

		}
		
		fpop = StateUtil.observablePopulation(pop, per1);
		map = ProfileUtil.getProfileMap(ctx, fpop);
		tmap = ThreatUtil.getThreatMap(ctx, realm, increment, map);
		lar  = NarrativeUtil.lookaround(ctx, realm, increment, increment, fpop, per1, tmap);

		logger.info(lar);

		StateUtil.queueUpdateLocation(ctx, state);
		Queue.processQueue();
		logger.info("Print current location - " + per1.get(FieldNames.FIELD_NAME) + " " + per1.get("state.currentLocation.eastings") + ", " + per1.get("state.currentLocation.northings") + "; " + per1.get("state.currentEast") + ", " + per1.get("state.currentNorth"));
		MapUtil.printLocationMap(ctx, GeoLocationUtil.getParentLocation(ctx, per1.get("state.currentLocation")), realm, pop);

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

			cfg.set("llmModel", "fim-local");
			cfg.set("systemCharacter", per2);
			cfg.set("userCharacter", per1);
			cfg.set("interactions", inters);
			cfg.set("terrain", NarrativeUtil.getTerrain(octx, per1));
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
}

