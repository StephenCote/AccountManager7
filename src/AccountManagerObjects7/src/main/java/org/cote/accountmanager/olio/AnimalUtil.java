package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;
import org.cote.accountmanager.util.ErrorUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class AnimalUtil {
	public static final Logger logger = LogManager.getLogger(AnimalUtil.class);
	
	private static List<BaseRecord> animalTemplates = new ArrayList<>();
	private static final SecureRandom random = new SecureRandom();
	private static int defaultAnimalSpeed = 10;
	
	private static final Map<Long, Map<String, List<BaseRecord>>> animalSpread = new ConcurrentHashMap<>();
	
	/// Attach an animal population to the specified location.  This should be used for random/surprise encounters, or called from the adjacent paint method
	/// Note: For 'feature' locations with 'cell' children, the animals need to be spread/painted across the cells
	///
	
	public static void checkAnimalPopulation(OlioContext context, BaseRecord realm, BaseRecord loc) {
		BaseRecord location = OlioUtil.getFullRecord(loc);
		/// List<BaseRecord> zoo = realm.get(OlioFieldNames.FIELD_ZOO);
		long id = loc.get(FieldNames.FIELD_ID);
		List<BaseRecord> zoo = realm.get(OlioFieldNames.FIELD_ZOO);
		List<BaseRecord> localZoo = zoo.stream().filter(a -> {
			BaseRecord ac = a.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION);
			return ac != null && ((long)ac.get(FieldNames.FIELD_PARENT_ID)) == id;
		}).collect(Collectors.toList());
		
		if(localZoo.size() == 0) {
			logger.info("Painting animals");
			Map<String, List<BaseRecord>> apop = AnimalUtil.paintAnimalPopulation(context, location);
			List<BaseRecord> parts = new ArrayList<>();
			for(String k: apop.keySet()) {
				zoo.addAll(apop.get(k));
				for(BaseRecord ap : apop.get(k)) {
					BaseRecord part = ParticipationFactory.newParticipation(context.getOlioUser(), realm, OlioFieldNames.FIELD_ZOO, ap);
					if(part != null) {
						parts.add(part);
					}
				}
			}
			IOSystem.getActiveContext().getRecordUtil().createRecords(parts.toArray(new BaseRecord[0]));
			Queue.processQueue();
			// context.clearCache();
		}
	}
	
	private static List<BaseRecord> attachAnimal(OlioContext ctx, BaseRecord location, List<BaseRecord> animals){

		List<BaseRecord> oanims = new ArrayList<>();
		for(BaseRecord anim: animals) {
			BaseRecord oanim = anim;
			BaseRecord state = oanim.get(FieldNames.FIELD_STATE);
			try {
				oanim.set(FieldNames.FIELD_TYPE, "random");
				state.set(OlioFieldNames.FIELD_CURRENT_LOCATION, location);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
			Queue.queue(oanim);
			oanims.add(oanim);
		}

		return oanims;
	}
	
	public static Map<String, List<BaseRecord>> paintAnimalPopulation(OlioContext ctx, BaseRecord location) {
		String type = location.get(FieldNames.FIELD_GEOTYPE);
		Map<String, List<BaseRecord>> pap = new ConcurrentHashMap<>();
		if(type.equals(FieldNames.FIELD_FEATURE) || type.equals("featureless")) {
			List<BaseRecord> cells = GeoLocationUtil.getCells(ctx, location);
			if(cells.size() > 0) {
				for(BaseRecord c: cells) {
					Map<String, List<BaseRecord>> cpap = createAnimalPopulation(ctx, c);
					for(String k: cpap.keySet()) {
						if(!pap.containsKey(k)) {
							pap.put(k, new ArrayList<>());
						}
						pap.get(k).addAll(cpap.get(k));
					}
				}
			}
			else {
				logger.error("**** No cells for location: " + location.get(FieldNames.FIELD_NAME));
			}
		}
		else {
			logger.warn("**** Location is not a 'feature' location");
		}
		Queue.processQueue();
		return pap;
	}
	
	protected static Map<String, List<BaseRecord>> createAnimalPopulation(OlioContext ctx, BaseRecord cell) {
		Map<String, List<BaseRecord>> cpap = newAnimalPopulation(ctx, cell);
		List<BaseRecord> apap = new ArrayList<>();
		for(String k: cpap.keySet()) {
			apap.addAll(cpap.get(k));
		}
		attachAnimal(ctx, cell, apap);
		return cpap;
	}
	
	/// Return a random assortment of deidentified and unpersisted animal templates for the terrain type
	/// Note: The location isn't attached here and is only used for the id and type, because in a grid map setup, the animals should be attached to the child cells, but they need to be spread around and not all clumped together
	///
	/// Note: For 'feature' terrain, child cells may have other terraintypes that would be naturally less likely for the animal to occur - eg: don't drop the horse in the middle of a lake
	protected static Map<String, List<BaseRecord>> newAnimalPopulation(OlioContext ctx, BaseRecord location){
		return newAnimalPopulation(ctx, location, Rules.ANIMAL_GROUP_COUNT, Rules.ANIMAL_MAX_GROUP_COUNT);
	}
	protected static Map<String, List<BaseRecord>> newAnimalPopulation(OlioContext ctx, BaseRecord location, int maxCount, int maxGroupCount){
		long id = location.get(FieldNames.FIELD_ID);
		if(animalSpread.containsKey(id)) {
			return animalSpread.get(id);
		}
		
		Map<String, List<BaseRecord>> pop = new HashMap<>();
		if(random.nextDouble() <= Rules.ODDS_ANY_ANIMAL_GROUP) {
			TerrainEnumType type = TerrainEnumType.valueOf((String)location.get("terrainType"));
			List<BaseRecord> animp = getAnimalTemplates(ctx).stream().filter(a -> ((List<String>)a.get("habitat")).contains(type.toString().toLowerCase())).collect(Collectors.toList());
			Collections.shuffle(animp);
			double odds = Rules.getAnimalOdds(type);
			int total = 0;
			for(BaseRecord a : animp) {
				if(total >= Rules.ANIMAL_MAX_GROUP_COUNT) {
					break;
				}
				if(random.nextDouble() <= odds) {
					int count = random.nextInt(1, maxCount);
					// logger.info("Add a " + a.get("groupName") + " of " + a.get(FieldNames.FIELD_NAME) + " with " + count);
					List<BaseRecord> grp = new ArrayList<>();
					for(int i = 0; i < count; i++) {
						BaseRecord anim1 = a.copyDeidentifiedRecord();
						try {
							anim1.set("store.items", a.get("store.items"));
							ItemUtil.convertItemsToInventory(ctx, anim1);
							
							int age = random.nextInt(1,20);
							anim1.setValue("age", age);
							StatisticsUtil.rollStatistics(anim1.get(OlioFieldNames.FIELD_STATISTICS), age);
							BaseRecord store = anim1.get(FieldNames.FIELD_STORE);
							List<BaseRecord> items = store.get(OlioFieldNames.FIELD_ITEMS);

							for(BaseRecord it : items) {
								it.set("type", null);
							}
							BaseRecord state = anim1.get(FieldNames.FIELD_STATE);
							if(total == 0 && (long)anim1.get("state.groupId") == 0L) {
								logger.info((long)anim1.get("state.groupId"));
								logger.info(state.toFullString());
								ErrorUtil.printStackTrace();
							}
							state.setValue(OlioFieldNames.FIELD_CURRENT_LOCATION, location);
							StateUtil.setInitialLocation(ctx, state);
							//StateUtil.agitateLocation(ctx, state);

							anim1.set(FieldNames.FIELD_TYPE, "random");
							state.set("alive", random.nextDouble() > Rules.ANIMAL_CARCASS_ODDS);
							total++;
							
						} catch (FieldException | ValueException | ModelNotFoundException e) {
							logger.error(e);
						}
						grp.add(anim1);
					}
					pop.put(a.get("groupName"), grp);
				}
				else {
					// logger.info("Skip adding " + a.get("groupName") + " of " + a.get(FieldNames.FIELD_NAME));
				}
			}
			//logger.info("Found " + animp.size() + " templates for " + type.toString());
		}
		if(pop.keySet().size() == 0) {
			// logger.info("No animals around " + location.get(FieldNames.FIELD_NAME));
		}
		animalSpread.put(id, pop);
		return pop;
	}

	protected static List<BaseRecord> getAnimalTemplates(OlioContext ctx){
		if(animalTemplates.size() > 0) {
			return animalTemplates;
		}

		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_ANIMAL, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("animals.id"));
		q.field(FieldNames.FIELD_TYPE, "template");
		OlioUtil.planMost(q);

		animalTemplates.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q)));
		return animalTemplates;
	}
	
	
	protected static BaseRecord newAnimal(OlioContext ctx, String name) {
		BaseRecord oanim = null;
		
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("animals.path"));
			plist.parameter(FieldNames.FIELD_NAME, name);
	
			oanim = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_ANIMAL, ctx.getOlioUser(), null, plist);
			oanim.set(OlioFieldNames.FIELD_STATISTICS, IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATISTICS, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("statistics.path"))));
			oanim.set(FieldNames.FIELD_STORE, IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_STORE, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get(OlioFieldNames.FIELD_STORES_PATH))));
			oanim.set("instinct", IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_INSTINCT, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("instincts.path"))));
			oanim.set(FieldNames.FIELD_STATE, IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATE, ctx.getOlioUser(), null, ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get("states.path"))));
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return oanim;
	}
	
	public static void loadAnimals(OlioContext ctx) {
		int count = IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(ctx.getOlioUser(), OlioModelNames.MODEL_ANIMAL, ctx.getWorld().get("animals.path")));
		if(count == 0) {
			importAnimals(ctx);
			Queue.processQueue();
		}
	}
	
	protected static BaseRecord[] importAnimals(OlioContext ctx) {
		// logger.info("Import default animal configuration");
		String[] anims = JSONUtil.importObject(ResourceUtil.getInstance().getResource("olio/animals.json"), String[].class);
		List<BaseRecord> oanims = new ArrayList<>();
		try {
			
			for(String anim : anims) {
				String[] pairs = anim.split(":");
				if(pairs.length < 4) {
					logger.warn("Expected at least four pairs: " + anim);
					continue;
				}

				BaseRecord oanim = newAnimal(ctx, pairs[0]);
				oanim.set(FieldNames.FIELD_TYPE, "template");
				oanims.add(oanim);
				oanim.set("groupName", pairs[1]);
				
				char[] instKeys = pairs[2].toCharArray();
				if(instKeys.length >= 3) {
					BaseRecord inst = oanim.get("instinct");
					double fight = 0;
					double protect = 0;
					double flight = 0;
					double herd = 0;
					double coop = 0;
					double stealth = 0;
					if(instKeys[0] == 'a') fight += 50;
					else if(instKeys[0] == 'f') flight += 25;
					else if(instKeys[0] == 'p') protect += 50;
					else {
						logger.warn("Unhandled 1: " + instKeys[0] + " from " + pairs[0] + " " + pairs[2]);
					}
					if(instKeys[1] == 's') herd -= 25;
					else if(instKeys[1] == 'h') {
						herd += 50;
					}
					else {
						logger.warn("Unhandled 2: " + instKeys[1] + " from " + pairs[0] + " " + pairs[2]);
					}
					if(instKeys[2] == 's') stealth += 25;
					else if(instKeys[2] == 'c') coop += 50;
					else if(instKeys[2] == 'f') fight += 25;
					else {
						logger.warn("Unhandled 3: " + instKeys[2] + " from " + pairs[0] + " " + pairs[2]);
					}
					inst.set("fight", fight);
					inst.set("flight", flight);
					inst.set("protect", protect);
					inst.set("cooperate", coop);
					inst.set("hide", stealth);
					
				}
				
				List<String> habitat = oanim.get("habitat");
				for(String h : pairs[3].split(",")) {
					habitat.add(h.trim());
				}
				
				Queue.queue(oanim);
				oanims.add(oanim);
				if(pairs.length < 5) {
					continue;
				}
				
				BaseRecord store = oanim.get(FieldNames.FIELD_STORE);
				List<BaseRecord> items = store.get(OlioFieldNames.FIELD_ITEMS);
				for(String i : pairs[4].split(",")) {
					items.add(ItemUtil.getCreateItemTemplate(ctx, i.trim()));
				}
				/*
				List<BaseRecord> items = new ArrayList<>();
				for(String i : pairs[3].split(",")) {
					items.add(ItemUtil.getItemTemplate(ctx, i.trim()));
				}
				store.set(OlioFieldNames.FIELD_ITEMS, items);
				*/
				/*)
				List<BaseRecord> tags = oanim.get("tags");
				List<BaseRecord> itags = new ArrayList<>();
				for(BaseRecord t: tags) {
					itags.add(OlioUtil.getCreateTag(ctx, t.get(FieldNames.FIELD_NAME), act.getModel()));
				}
				oanim.set("tags", itags);
				*/

			}
			
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}

		return oanims.toArray(new BaseRecord[0]);
	}
	
	public static double sprintMeterLimit(BaseRecord animal) {
		int speed = animal.get("statistics.athleticism");
		return Math.abs(((double)speed - 10)/10) * 800.0;
	}
	
	public static double sprintMetersPerSecond(BaseRecord animal) {
		int speed = animal.get("statistics.speed");
		return Math.abs(((double)speed - 10)/10) * 10.2;
	}
	
	public static double walkMetersPerSecond(BaseRecord animal) {
		double speed = (double)(int)animal.get("statistics.speed");
		if(speed <= 0) {
			logger.warn("Invalid speed for #" + animal.get(FieldNames.FIELD_ID) + " " + animal.get(FieldNames.FIELD_NAME) + ": Using default");
			speed = defaultAnimalSpeed;
		}
		/// Average walking speed is 1.2 meters per second
		// logger.info(animal.get(FieldNames.FIELD_NAME) + " speed " + speed + " is " + ((speed/10)*1.2) + "mps");
		return (speed/10) * 1.2;
	}
	
}
