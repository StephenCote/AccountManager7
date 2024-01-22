package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.RandomLocationInitializationRule;
import org.cote.accountmanager.parsers.data.WordParser;
import org.cote.accountmanager.parsers.geo.GeoParser;
import org.cote.accountmanager.parsers.wordnet.WordNetParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;

public class WorldUtil {
	public static final Logger logger = LogManager.getLogger(WorldUtil.class);
	
    private static SecureRandom rand = new SecureRandom();
    
    
    protected static boolean rapidDataTest = false;

	

	
	public static BaseRecord getWorld(BaseRecord user, String groupPath, String worldName) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_WORLD, FieldNames.FIELD_GROUP_ID, (long)dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, worldName);
		try {
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	public static BaseRecord getCreateWorld(BaseRecord user, String groupPath, String worldName, String[] features) {
		return getCreateWorld(user, null, groupPath, worldName, features);
	}
	
	public static BaseRecord getCreateWorld(BaseRecord user, BaseRecord basis, String groupPath, String worldName, String[] features) {
		BaseRecord rec = getWorld(user, groupPath, worldName);
		if(rec == null) {
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));

			ParameterList plist = ParameterList.newParameterList("path", groupPath);
			plist.parameter("name", worldName);
			try {
				BaseRecord world = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_WORLD, user, null, plist);
				world.set("features", Arrays.asList(features));
				world.set("basis", basis);
				IOSystem.getActiveContext().getAccessPoint().create(user, world);
				// rec = getWorld(user, groupPath, worldName);
				rec = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_WORLD, (long)dir.get(FieldNames.FIELD_ID), worldName);
			} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		else {
			List<String> feats = rec.get("features");
			if(feats.size() != features.length) {
				try {
					rec.set("features", Arrays.asList(features));
					IOSystem.getActiveContext().getAccessPoint().update(user, rec.copyRecord(new String[] {FieldNames.FIELD_ID, "features"}));
					
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
				}
			}
		}
		
		return rec;
	}
	
	private static int loadOccupations(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord occDir = world.get("occupations");
		IOSystem.getActiveContext().getReader().populate(occDir);

		WordParser.loadOccupations(user, occDir.get(FieldNames.FIELD_PATH), basePath, reset);
		return IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(user, ModelNames.MODEL_WORD, occDir.get(FieldNames.FIELD_PATH)));

	}
	
	private static int loadLocations(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);

		List<String> feats = world.get("features");
		String[] features = feats.toArray(new String[0]);
		BaseRecord locDir = world.get("locations");
		IOSystem.getActiveContext().getReader().populate(locDir);
		if(features.length > 0) {
			GeoParser.loadInfo(user, locDir.get(FieldNames.FIELD_PATH), basePath, features, reset);
		}
		return IOSystem.getActiveContext().getSearch().count(GeoParser.getQuery(null, null, locDir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID)));

	}
	private static int loadDictionary(BaseRecord user, BaseRecord world, String basePath, boolean reset) {

		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord dictDir = world.get("dictionary");
		IOSystem.getActiveContext().getReader().populate(dictDir);
		
		String groupPath = dictDir.get(FieldNames.FIELD_PATH);
		String wnetPath = basePath;
		
		WordNetParser.loadAdverbs(user, groupPath, wnetPath, 0, reset);
		WordNetParser.loadAdjectives(user, groupPath, wnetPath, 0, reset);
		WordNetParser.loadNouns(user, groupPath, wnetPath, 0, reset);
		WordNetParser.loadVerbs(user, groupPath, wnetPath, 0, reset);
		return IOSystem.getActiveContext().getSearch().count(WordNetParser.getQuery(user, null, groupPath));
	}
	private static int loadNames(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord nameDir = world.get("names");
		IOSystem.getActiveContext().getReader().populate(nameDir);
		
		String groupPath = nameDir.get(FieldNames.FIELD_PATH);

		WordParser.loadNames(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(user, ModelNames.MODEL_WORD, groupPath));
	}
	private static int loadColors(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord colDir = world.get("colors");
		IOSystem.getActiveContext().getReader().populate(colDir);
		
		String groupPath = colDir.get(FieldNames.FIELD_PATH);

		WordParser.loadColors(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(user, ModelNames.MODEL_COLOR, groupPath));
	}
	private static int loadPatterns(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord colDir = world.get("patterns");
		IOSystem.getActiveContext().getReader().populate(colDir);
		
		String groupPath = colDir.get(FieldNames.FIELD_PATH);

		WordParser.loadPatterns(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(user, ModelNames.MODEL_DATA, groupPath));
	}
	private static int loadTraits(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord traitsDir = world.get("traits");
		IOSystem.getActiveContext().getReader().populate(traitsDir);
		
		String groupPath = traitsDir.get(FieldNames.FIELD_PATH);

		WordParser.loadTraits(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(user, ModelNames.MODEL_TRAIT, groupPath));
	}
	private static int loadSurnames(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord nameDir = world.get("surnames");
		IOSystem.getActiveContext().getReader().populate(nameDir);
		String groupPath = nameDir.get(FieldNames.FIELD_PATH);
		WordParser.loadSurnames(user, groupPath, basePath, reset);
		return IOSystem.getActiveContext().getSearch().count(OlioUtil.getQuery(user, ModelNames.MODEL_CENSUS_WORD, groupPath));
	}

	public static void loadWorldData(BaseRecord user, BaseRecord world, String basePath, boolean reset) {
		logger.info("Checking world data ...");
		int locs = loadLocations(user, world, basePath + "/location", reset);
		logger.info("Locations: " + locs);
		int dict = loadDictionary(user, world, basePath + "/wn3.1.dict/dict", reset);
		logger.info("Dictionary words: " + dict);
		int occs = loadOccupations(user, world, basePath + "/occupations/noc_2021_version_1.0_-_elements.csv", reset);
		logger.info("Occupations: " + occs);
		int names = loadNames(user, world, basePath + "/names/yob2022.txt", reset);
		logger.info("Names: " + names);
		int surnames = loadSurnames(user, world, basePath + "/surnames/Names_2010Census.csv", reset);
		logger.info("Surnames: " + surnames);
		int traits = loadTraits(user, world, basePath, reset);
		logger.info("Traits: " + traits);
		int colors = loadColors(user, world, basePath + "/colors.csv", reset);
		logger.info("Colors: " + colors);
		int patterns = loadPatterns(user, world, basePath + "/patterns/patterns.csv", reset);
		logger.info("Patterns: " + patterns);
	}
	public static BaseRecord cloneIntoGroup(BaseRecord src, BaseRecord dir) {
		IOSystem.getActiveContext().getReader().populate(src);
		BaseRecord targ = src.copyDeidentifiedRecord();
		try {
			targ.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		}
		catch(ValueException | FieldException | ModelNotFoundException e) {
			logger.error(e);
			targ = null;
		}
		return targ;
	}
	
	protected static BaseRecord generateRegion(OlioContext ctx) {
		
		List<BaseRecord> events = new ArrayList<>(); 
		BaseRecord world = ctx.getWorld();
		BaseRecord parWorld = world.get("basis");
		BaseRecord locDir = world.get("locations");
		BaseRecord eventsDir = world.get("events");
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}
		BaseRecord root = EventUtil.getRootEvent(ctx);
		if(root != null) {
			logger.info("Region is already generated");
			return root;
		}
		
		logger.info("Generate region ...");
		IOSystem.getActiveContext().getReader().populate(parWorld, 2);
		BaseRecord[] locs = new BaseRecord[0];
		for(IOlioContextRule rule : ctx.getConfig().getContextRules()) {
			locs = rule.selectLocations(ctx);
			if(locs != null && locs.length > 0) {
				break;
			}
		}
		if(locs == null || locs.length == 0) {
			locs = (new RandomLocationInitializationRule()).selectLocations(ctx);
		}
		List<BaseRecord> locations = new ArrayList<>();
		for(BaseRecord l : locs) {
			locations.add(cloneIntoGroup(l, locDir));
		}
		if(locations.isEmpty()){
			logger.error("Expected a positive number of locations");
			logger.info(locDir.toFullString());
			return null;
		}
		
		try{
			
			int cloc = IOSystem.getActiveContext().getRecordUtil().updateRecords(locations.toArray(new BaseRecord[0]));
			if(cloc != locations.size()) {
				logger.error("Failed to create locations");
				return null;
			}
			for(BaseRecord loc: locations) {

				String locName = loc.get(FieldNames.FIELD_NAME);
				loc.set(FieldNames.FIELD_DESCRIPTION, null);
				BaseRecord event = null;

				if(root == null) {
					logger.info("Construct region: " + locName);
					ParameterList plist = ParameterList.newParameterList("path", eventsDir.get(FieldNames.FIELD_PATH));
					root = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, ctx.getUser(), null, plist);
					root.set(FieldNames.FIELD_NAME, "Construct Region " + locName);
					root.set(FieldNames.FIELD_LOCATION, loc);
					root.set(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
					root.set("eventStart", ctx.getConfig().getBaseInceptionDate());
					root.set("eventEnd", ctx.getConfig().getBaseInceptionDate());
					if(!IOSystem.getActiveContext().getRecordUtil().updateRecord(root)) {
						logger.error("Failed to create root event");
						return null;
					}
					event = root;
				}
				else {
					logger.info("Construct region: " + locName);
					BaseRecord popEvent = populateRegion(ctx, loc, root, ctx.getConfig().getBasePopulationCount());
					popEvent.set(FieldNames.FIELD_PARENT_ID, root.get(FieldNames.FIELD_ID));
					events.add(popEvent);
					event = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, ctx.getUser(), null, ParameterList.newParameterList("path", eventsDir.get(FieldNames.FIELD_PATH)));
					event.set(FieldNames.FIELD_NAME, "Construct " + locName);
					event.set(FieldNames.FIELD_PARENT_ID, root.get(FieldNames.FIELD_ID));
					
					for(int b = 0; b < 2; b++) {
						List<BaseRecord> traits = event.get((b == 0 ? "entryTraits" : "exitTraits"));
						traits.addAll(Arrays.asList(Decks.getRandomTraits(ctx.getUser(), parWorld, 3)));
					}
					event.set(FieldNames.FIELD_LOCATION, loc);
					event.set(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
					event.set("eventStart", ctx.getConfig().getBaseInceptionDate());
					event.set("eventEnd", ctx.getConfig().getBaseInceptionDate());

					if(!IOSystem.getActiveContext().getRecordUtil().updateRecord(event)) {
						logger.error("Failed to create region event");
						return null;
					}
					events.add(event);
				}
			}

		}
		catch (ValueException | FieldException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		if(!IOSystem.getActiveContext().getRecordUtil().updateRecord(root)) {
			logger.error("Failed to update root event");
			return null;
		}
		return root;
	}

	/// TODO - drop the superfluous parameters
	///
	public static BaseRecord populateRegion(OlioContext ctx, BaseRecord location, BaseRecord rootEvent, int popCount){

		long totalAge = 0L;
		String locName = location.get(FieldNames.FIELD_NAME);
		logger.info("Populating " + locName + " with " + popCount + " people");
		long start = System.currentTimeMillis();
		BaseRecord event = null;
		BaseRecord parWorld = ctx.getWorld().get("basis");
		if(parWorld == null) {
			logger.error("A basis world is required");
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(parWorld, 2);
		try {
			BaseRecord popDir = ctx.getWorld().get("population");
			BaseRecord evtDir = ctx.getWorld().get("events");
			
			ParameterList plist = ParameterList.newParameterList("path", evtDir.get(FieldNames.FIELD_PATH));
			event = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, ctx.getUser(), null, plist);
			event.set(FieldNames.FIELD_LOCATION, location);
			event.set(FieldNames.FIELD_TYPE, EventEnumType.INCEPT);
			event.set(FieldNames.FIELD_NAME, "Populate " + locName);
			event.set(FieldNames.FIELD_PARENT_ID, rootEvent.get(FieldNames.FIELD_ID));
			ZonedDateTime inceptionDate = rootEvent.get("eventStart");
			event.set("eventStart", inceptionDate);
			event.set("eventEnd", rootEvent.get("eventEnd"));

			List<BaseRecord> grps = event.get(FieldNames.FIELD_GROUPS);
			BaseRecord popGrp = OlioUtil.newRegionGroup(ctx.getUser(), popDir, locName + " Population");
			grps.add(popGrp);
			grps.add(OlioUtil.newRegionGroup(ctx.getUser(), popDir, locName + " Cemetary"));
			
			/*
			for(String name : leaderPopulation){
				grps.add(newRegionGroup(user, popDir, locName + " " + name + " Leaders"));				
			}
			*/
			
			IOSystem.getActiveContext().getRecordUtil().updateRecords(grps.toArray(new BaseRecord[0]));
			
			event.set(FieldNames.FIELD_GROUPS, grps);
			List<BaseRecord> actors = event.get("actors");
			if(popCount == 0){
				logger.error("Empty population");
				event.set(FieldNames.FIELD_DESCRIPTION, "Decimated");
			}
			else {
				int totalAbsoluteAlignment = 0;
				ZonedDateTime now = ZonedDateTime.now();

				Decks.shuffleDecks(ctx.getUser(), parWorld);
				if(Decks.maleNamesDeck.length == 0 || Decks.femaleNamesDeck.length == 0 || Decks.surnameNamesDeck.length == 0 || Decks.occupationsDeck.length == 0) {
					logger.error("Empty names");
				}

				logger.info("Creating population of " + popCount);

				for(int i = 0; i < popCount; i++){
					BaseRecord person = CharacterUtil.randomPerson(ctx.getUser(), ctx.getWorld(), null, inceptionDate, Decks.maleNamesDeck, Decks.femaleNamesDeck, Decks.surnameNamesDeck, Decks.occupationsDeck);
					AddressUtil.simpleAddressPerson(ctx, location, person);
					/// AddressUtil.addressPerson(user, world, person, location);
					int alignment = AlignmentEnumType.getAlignmentScore(person);
					long years = Math.abs(now.toInstant().toEpochMilli() - ((ZonedDateTime)person.get("birthDate")).toInstant().toEpochMilli()) / OlioUtil.YEAR;
					person.set("age", (int)years);
					ProfileUtil.rollPersonality(person.get("personality"));
					StatisticsUtil.rollStatistics(person.get("statistics"), (int)years);
					totalAge += years;
					totalAbsoluteAlignment += (alignment + 4);
					
					/*
					List<BaseRecord> appl = person.get("apparel");
					appl.add(ApparelUtil.randomApparel(user, world, person));
					*/
					actors.add(person);
				}
				
				logger.info("Bulk loading population");
				int created = IOSystem.getActiveContext().getRecordUtil().updateRecords(actors.toArray(new BaseRecord[0]));
				if(created != actors.size()) {
					logger.error("Created " + created + " but expected " + actors.size() + " records");
				}
				logger.info("Creating event memberships");
				List<BaseRecord> parts = new ArrayList<>();
				for(BaseRecord rec : actors) {
					parts.add(ParticipationFactory.newParticipation(ctx.getUser(), popGrp, null, rec));
				}
				IOSystem.getActiveContext().getRecordUtil().updateRecords(parts.toArray(new BaseRecord[0]));
				int eventAlignment = (totalAbsoluteAlignment / popCount) - 4;
				if(eventAlignment == 0) {
					eventAlignment++;
				}
				event.set(FieldNames.FIELD_ALIGNMENT, AlignmentEnumType.valueOf(eventAlignment));				
				/*
				if(organizePersonManagement){
					generatePersonOrganization(event.getActors().toArray(new PersonType[0]));
				}
				*/
			}
			logger.info("Update event");
			IOSystem.getActiveContext().getRecordUtil().updateRecord(event);

		} catch (ValueException | FieldException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
		}
		logger.info("Finished populating " + locName + " in " + (System.currentTimeMillis() - start) + "ms");
		return event;
	}

	/*
	public static boolean isDecimated(BaseRecord loc) {
		IOSystem.getActiveContext().getReader().populate(loc);
		boolean dec = false;
		try {
			dec = AttributeUtil.getAttributeValue(loc, "decimated", false);
		} catch (ModelException e) {
			logger.error(e);
		}
		return dec;
	}
	*/

	/*
	public static int cleanupModel(Query q) {
		
	}
	*/
	public static int cleanupWorld(BaseRecord user, BaseRecord world) {
		int totalWrites = 0;
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		IOSystem.getActiveContext().getReader().populate(world, 2);
		long start = System.currentTimeMillis();
		totalWrites += cleanupLocation(user, ModelNames.MODEL_GEO_LOCATION, (long)world.get("locations.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_EVENT, (long)world.get("events.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_CHAR_PERSON, (long)world.get("population.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_GROUP, (long)world.get("population.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_ADDRESS, (long)world.get("addresses.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_CONTACT, (long)world.get("contacts.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD_NET, (long)world.get("dictionary.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD, (long)world.get("words.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_TRAIT, (long)world.get("traits.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD, (long)world.get("names.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_CENSUS_WORD, (long)world.get("surnames.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD, (long)world.get("occupations.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_COLOR, (long)world.get("colors.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_QUALITY, (long)world.get("qualities.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_WEARABLE, (long)world.get("wearables.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_APPAREL, (long)world.get("apparel.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_DATA, (long)world.get("patterns.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_CHAR_STATISTICS, (long)world.get("statistics.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_INSTINCT, (long)world.get("instincts.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_BEHAVIOR, (long)world.get("behaviors.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_CHAR_STATE, (long)world.get("states.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_ACTION, (long)world.get("actions.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_ACTION_RESULT, (long)world.get("actionResults.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_SCHEDULE, (long)world.get("schedules.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_PERSONALITY, (long)world.get("personalities.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_STORE, (long)world.get("stores.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_BUILDER, (long)world.get("builders.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_ITEM, (long)world.get("items.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_ITEM_STATISTICS, (long)world.get("statistics.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_TAG, (long)world.get("tagsGroup.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_ANIMAL, (long)world.get("animals.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_REALM, (long)world.get("realmsGroup.id"), orgId);
		long stop = System.currentTimeMillis();
		logger.info("Cleaned up world in " + (stop - start) + "ms");
		RecordFactory.cleanupOrphans(null);
		
		return totalWrites;
	}
	
	public static int cleanupLocation(BaseRecord user, String model, long groupId, long organizationId) {
		
		if(groupId <= 0L || organizationId <= 0L) {
			logger.error("Invalid group or organization");
			return 0;
		}
		Query lq = QueryUtil.getGroupQuery(model, null, groupId, organizationId);
		int deleted = 0;
		try {
			deleted = IOSystem.getActiveContext().getWriter().delete(lq);
		} catch (WriterException e) {
			logger.error(e);
			e.printStackTrace();
		}
		logger.info("Cleaned up " + deleted + " " + model + " in #" + groupId + " (#" + organizationId + ")");
		return deleted;
	}
	

	public static List<BaseRecord> getPopulation(BaseRecord user, BaseRecord world, BaseRecord location){
		Query q = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON);
		BaseRecord popGrp = getLocationGroup(user, world, location, "Population");
		if(popGrp == null) {
			logger.error("Failed to find population group");
			return new ArrayList<>();
		}
		q.filterParticipation(popGrp, null, ModelNames.MODEL_CHAR_PERSON, null);
		try {
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		q.setCache(false);
		
		return new ArrayList<>(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q)));
	}
	
	public static BaseRecord getLocationGroup(BaseRecord user, BaseRecord world, BaseRecord location, String name) {
		List<BaseRecord> grps = getWorldGroups(user, world);
		BaseRecord ogrp = null;
		IOSystem.getActiveContext().getReader().populate(location, new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_PARENT_ID});
		String locName = location.get(FieldNames.FIELD_NAME) + " " + name;
		Optional<BaseRecord> grp = grps.stream().filter(f -> locName.equals((String)f.get(FieldNames.FIELD_NAME))).findFirst();
		if(grp.isPresent()) {
			ogrp = grp.get();
		}
		return ogrp;
	}
	public static List<BaseRecord> getWorldGroups(BaseRecord user, BaseRecord world){
		IOSystem.getActiveContext().getReader().populate(world, new String[] {"population"});
		return Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, world.get("population.id"))));
	}
	
    
}
