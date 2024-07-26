package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;
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
		/// List<BaseRecord> zoo = realm.get("zoo");
		long id = loc.get(FieldNames.FIELD_ID);
		List<BaseRecord> zoo = realm.get("zoo");
		List<BaseRecord> localZoo = zoo.stream().filter(a -> {
			BaseRecord ac = a.get("state.currentLocation");
			return ac != null && ((long)ac.get(FieldNames.FIELD_PARENT_ID)) == id;
		}).collect(Collectors.toList());
		
		if(localZoo.size() == 0) {
			logger.info("Painting animals");
			Map<String, List<BaseRecord>> apop = AnimalUtil.paintAnimalPopulation(context, location);
			List<BaseRecord> parts = new ArrayList<>();
			for(String k: apop.keySet()) {
				zoo.addAll(apop.get(k));
				for(BaseRecord ap : apop.get(k)) {
					BaseRecord part = ParticipationFactory.newParticipation(context.getOlioUser(), realm, "zoo", ap);
					if(part != null) {
						parts.add(part);
					}
				}
			}
			IOSystem.getActiveContext().getRecordUtil().createRecords(parts.toArray(new BaseRecord[0]));
			context.processQueue();
			context.clearCache();
		}
	}
	
	private static List<BaseRecord> attachAnimal(OlioContext ctx, BaseRecord location, List<BaseRecord> animals){

		List<BaseRecord> oanims = new ArrayList<>();
		for(BaseRecord anim: animals) {
			//BaseRecord oanim = anim.copyDeidentifiedRecord();
			BaseRecord oanim = anim;
			BaseRecord state = oanim.get("state");
			try {
				oanim.set(FieldNames.FIELD_TYPE, "random");
				state.set("currentLocation", location);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
			ctx.queue(oanim);
			oanims.add(oanim);
		}

		return oanims;
	}
	
	public static Map<String, List<BaseRecord>> paintAnimalPopulation(OlioContext ctx, BaseRecord location) {
		String type = location.get("geoType");
		Map<String, List<BaseRecord>> pap = new ConcurrentHashMap<>();
		if(type.equals("feature") || type.equals("featureless")) {
			List<BaseRecord> cells = GeoLocationUtil.getCells(ctx, location);
			if(cells.size() > 0) {
				for(BaseRecord c: cells) {
					Map<String, List<BaseRecord>> cpap = paintAnimalPopulation(ctx, location, c);
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
		ctx.processQueue();
		return pap;
	}
	public static Map<String, List<BaseRecord>> paintAnimalPopulation(OlioContext ctx, BaseRecord location, BaseRecord cell) {
		Map<String, List<BaseRecord>> cpap = getAnimalPopulation(ctx, cell, 3);
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
	public static Map<String, List<BaseRecord>> getAnimalPopulation(OlioContext ctx, BaseRecord location){
		return getAnimalPopulation(ctx, location, Rules.ANIMAL_GROUP_COUNT);
	}
	public static Map<String, List<BaseRecord>> getAnimalPopulation(OlioContext ctx, BaseRecord location, int maxCount){
		long id = location.get(FieldNames.FIELD_ID);
		if(animalSpread.containsKey(id)) {
			return animalSpread.get(id);
		}
		
		Map<String, List<BaseRecord>> pop = new HashMap<>();
		if(random.nextDouble() <= Rules.ODDS_ANY_ANIMAL_GROUP) {
			TerrainEnumType type = TerrainEnumType.valueOf((String)location.get("terrainType"));
			List<BaseRecord> animp = getAnimalTemplates(ctx).stream().filter(a -> ((List<String>)a.get("habitat")).contains(type.toString().toLowerCase())).collect(Collectors.toList());
			double odds = Rules.getAnimalOdds(type);
			for(BaseRecord a : animp) {
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
							StatisticsUtil.rollStatistics(anim1.get("statistics"), age);
							BaseRecord store = anim1.get("store");
							List<BaseRecord> items = store.get("items");

							for(BaseRecord it : items) {
								it.set("type", null);
							}
							BaseRecord state = anim1.get("state");
							state.setValue("currentLocation", location);
							StateUtil.setInitialLocation(ctx, state);
							//StateUtil.agitateLocation(ctx, state);

							anim1.set(FieldNames.FIELD_TYPE, "random");
							if(random.nextDouble() <= Rules.ANIMAL_CARCASS_ODDS) {
								state.set("alive", false);
							}
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

		Query q = QueryUtil.createQuery(ModelNames.MODEL_ANIMAL, FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("animals.id"));
		q.field(FieldNames.FIELD_TYPE, "template");
		try {
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		animalTemplates.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q)));
		return animalTemplates;
	}
	
	
	protected static BaseRecord newAnimal(OlioContext ctx, String name) {
		BaseRecord oanim = null;
		
		try {
			ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("animals.path"));
			plist.parameter(FieldNames.FIELD_NAME, name);
	
			oanim = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_ANIMAL, ctx.getOlioUser(), null, plist);
			oanim.set("statistics", IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CHAR_STATISTICS, ctx.getOlioUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("statistics.path"))));
			oanim.set("store", IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_STORE, ctx.getOlioUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("stores.path"))));
			oanim.set("instinct", IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_INSTINCT, ctx.getOlioUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("instincts.path"))));
			oanim.set("state", IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CHAR_STATE, ctx.getOlioUser(), null, ParameterList.newParameterList("path", ctx.getWorld().get("states.path"))));
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return oanim;
	}
	
	public static void loadAnimals(OlioContext ctx) {
		int count = IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(ctx.getOlioUser(), ModelNames.MODEL_ANIMAL, ctx.getWorld().get("animals.path")));
		if(count == 0) {
			importAnimals(ctx);
			ctx.processQueue();
		}
	}
	
	protected static BaseRecord[] importAnimals(OlioContext ctx) {
		// logger.info("Import default animal configuration");
		String[] anims = JSONUtil.importObject(ResourceUtil.getResource("olio/animals.json"), String[].class);
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
				
				ctx.queue(oanim);
				oanims.add(oanim);
				if(pairs.length < 5) {
					continue;
				}
				
				BaseRecord store = oanim.get("store");
				List<BaseRecord> items = store.get("items");
				for(String i : pairs[4].split(",")) {
					items.add(ItemUtil.getCreateItemTemplate(ctx, i.trim()));
				}
				/*
				List<BaseRecord> items = new ArrayList<>();
				for(String i : pairs[3].split(",")) {
					items.add(ItemUtil.getItemTemplate(ctx, i.trim()));
				}
				store.set("items", items);
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
			logger.warn("Invalid speed for #" + animal.get("id") + " " + animal.get("name") + ": Using default");
			speed = defaultAnimalSpeed;
		}
		/// Average walking speed is 1.2 meters per second
		// logger.info(animal.get("name") + " speed " + speed + " is " + ((speed/10)*1.2) + "mps");
		return (speed/10) * 1.2;
	}
	
}
