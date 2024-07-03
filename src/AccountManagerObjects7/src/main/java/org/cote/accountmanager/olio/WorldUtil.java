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
import org.cote.accountmanager.util.LibraryUtil;

public class WorldUtil {
	public static final Logger logger = LogManager.getLogger(WorldUtil.class);
	
    private static SecureRandom rand = new SecureRandom();
    
    /// TODO: Use the OlioContextConfiguration
    protected static boolean useSharedLibrary = true;
    
    
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
				if(useSharedLibrary) {
					world.set("colors", LibraryUtil.getCreateSharedLibrary(user, "Colors", true));
					world.set("occupations", LibraryUtil.getCreateSharedLibrary(user, "Occupations", true));
					world.set("dictionary", LibraryUtil.getCreateSharedLibrary(user, "Dictionary", true));
					world.set("words", LibraryUtil.getCreateSharedLibrary(user, "Words", true));
					world.set("names", LibraryUtil.getCreateSharedLibrary(user, "Names", true));
					world.set("surnames", LibraryUtil.getCreateSharedLibrary(user, "Surnames", true));
					world.set("patterns", LibraryUtil.getCreateSharedLibrary(user, "Patterns", true));
				}
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
	private static boolean fastDataCheck(BaseRecord user, BaseRecord world) {
		IOSystem.getActiveContext().getReader().populate(world);
		BaseRecord locDir = world.get("locations");
		return IOSystem.getActiveContext().getSearch().count(GeoParser.getQuery(null, null, locDir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID))) > 0;
		
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
		else if(reset) {
			GeoParser.countCleanupLocation(user, locDir.get(FieldNames.FIELD_PATH), null, null, reset);
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


	protected static void loadWorldData(OlioContext ctx) {
		BaseRecord user = ctx.getOlioUser();
		BaseRecord world = ctx.getUniverse();
		String basePath = ctx.getConfig().getDataPath();
		boolean reset = ctx.getConfig().isResetUniverse();
		if(!reset && ctx.getConfig().isFastDataCheck() && fastDataCheck(user, world)) {
			return;
		}
		logger.info("Checking world data ...");

		/// data.geoLocation
		int locs = loadLocations(user, world, basePath + "/location", reset);
		/// data.wordNet
		int dict = loadDictionary(user, world, basePath + "/wn3.1.dict/dict", (useSharedLibrary == false && reset));
		/// data.word
		int occs = loadOccupations(user, world, basePath + "/occupations/noc_2021_version_1.0_-_elements.csv", (useSharedLibrary == false && reset));
		/// data.word
		int names = loadNames(user, world, basePath + "/names/yob2022.txt", (useSharedLibrary == false && reset));
		// logger.info("Names: " + names);
		int surnames = loadSurnames(user, world, basePath + "/surnames/Names_2010Census.csv", (useSharedLibrary == false && reset));
		// logger.info("Surnames: " + surnames);
		int traits = loadTraits(user, world, basePath, reset);
		// logger.info("Traits: " + traits);
		int colors = loadColors(user, world, basePath + "/colors.csv", (useSharedLibrary == false && reset));
		// logger.info("Colors: " + colors);
		int patterns = loadPatterns(user, world, basePath + "/patterns/patterns.csv", (useSharedLibrary == false && reset));
		// logger.info("Patterns: " + patterns);
	}





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
		
		if(!useSharedLibrary) {
			totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD_NET, (long)world.get("dictionary.id"), orgId);
			totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD, (long)world.get("words.id"), orgId);
			totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD, (long)world.get("names.id"), orgId);
			totalWrites += cleanupLocation(user, ModelNames.MODEL_CENSUS_WORD, (long)world.get("surnames.id"), orgId);
			totalWrites += cleanupLocation(user, ModelNames.MODEL_WORD, (long)world.get("occupations.id"), orgId);
			totalWrites += cleanupLocation(user, ModelNames.MODEL_COLOR, (long)world.get("colors.id"), orgId);
			totalWrites += cleanupLocation(user, ModelNames.MODEL_DATA, (long)world.get("patterns.id"), orgId);
		}
		
		totalWrites += cleanupLocation(user, ModelNames.MODEL_TRAIT, (long)world.get("traits.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_QUALITY, (long)world.get("qualities.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_WEARABLE, (long)world.get("wearables.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_APPAREL, (long)world.get("apparel.id"), orgId);
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
		totalWrites += cleanupLocation(user, ModelNames.MODEL_INVENTORY_ENTRY, (long)world.get("inventories.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_INTERACTION, (long)world.get("interactions.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_NARRATIVE, (long)world.get("narratives.id"), orgId);
		totalWrites += cleanupLocation(user, ModelNames.MODEL_PROFILE, (long)world.get("profiles.id"), orgId);
		
		RecordFactory.cleanupOrphans(null);
		long stop = System.currentTimeMillis();
		logger.info("Cleaned up world in " + (stop - start) + "ms");

		
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
		}
		// logger.info("Cleaned up " + deleted + " " + model + " in #" + groupId + " (#" + organizationId + ")");
		return deleted;
	}
	
/*
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
		// q.setCache(false);
		
		return new ArrayList<>(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(q)));
	}
*/	
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
