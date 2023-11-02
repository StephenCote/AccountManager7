package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.util.Strings;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;

import org.cote.accountmanager.parsers.GenericParser;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.parsers.wordnet.WordNetParser;
import org.cote.accountmanager.parsers.data.DataParseWriter;
import org.cote.accountmanager.parsers.data.WordParser;
import org.cote.accountmanager.parsers.geo.GeoParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.AlignmentEnumType;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GeographyEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.WorldUtil;
import org.junit.Test;

public class TestBulkOperation extends BaseTest {
	private int bulkLoadSize = 10;
	
	private BaseRecord newTestData(BaseRecord owner, String path, String name, String textData) {
		ParameterList plist = ParameterList.newParameterList("path", path);
		plist.parameter("name", name);
		BaseRecord data = null;
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, owner, null, plist);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			data.set(FieldNames.FIELD_BYTE_STORE, textData.getBytes());
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return data;
	}
	
	private BaseRecord newTrait(BaseRecord owner, String path, String name, TraitEnumType type, AlignmentEnumType alignment) {
		ParameterList plist = ParameterList.newParameterList("path", path);
		plist.parameter("name", name);
		BaseRecord data = null;
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_TRAIT, owner, null, plist);
			data.set(FieldNames.FIELD_TYPE, type);
			data.set(FieldNames.FIELD_ALIGNMENT, alignment);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return data;
	}
	
	private List<BaseRecord> getBulkTraits(BaseRecord owner, String groupPath){
		String traitsPath = testProperties.getProperty("test.datagen.path") + "/traits.json";
		File f = new File(traitsPath);
		assertTrue("Data file does not exist", f.exists());
		
		Map<String, String[]> traits = JSONUtil.getMap(traitsPath, String.class,String[].class);
		assertTrue("Expected to load some data", traits.size() > 0);
		
		List<BaseRecord> traitrecs = new ArrayList<>();
		Set<String> traitset = new HashSet<>();
		traits.forEach((k, v) -> {
			if(!k.equals("alignment")) {
				final AlignmentEnumType align;
				if(k.equals("positive")) {
					align = AlignmentEnumType.LAWFULGOOD;
				}
				else if(k.equals("negative")) {
					align = AlignmentEnumType.CHAOTICEVIL;
				}
				else {
					align = AlignmentEnumType.NEUTRAL;
				}

				Arrays.asList(v).forEach(s -> {
					if(s != null && !traitset.contains(s)) {
						traitset.add(s);
						traitrecs.add(newTrait(owner, groupPath, s, TraitEnumType.PERSON, align));
					}
					else {
						logger.warn("Invalid or dupe: " + s);
					}
				});
			}
		});
		assertTrue("Expected traits to be queued", traitrecs.size() > 0);
		
		return traitrecs;

	}
	
	/*
	@Test
	public void TestLocationParent() {
		logger.info("Test location parent");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Geolocation");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String groupPath = "~/Bulk/Geo - " + UUID.randomUUID().toString();

		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		String locName = "Parent Loc";
		String chdName = "Child Loc";
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		plist.parameter("name", locName);
		BaseRecord loc = null;
		try {
			loc = ioContext.getFactory().newInstance(ModelNames.MODEL_LOCATION, testUser1, null, plist);
			loc = ioContext.getAccessPoint().create(testUser1, loc);
		} catch (FactoryException e) {
			logger.error(e);
		}
		assertNotNull("Loc is null", loc);

		String[] names = RecordUtil.getCommonFields(ModelNames.MODEL_LOCATION);

		BaseRecord lloc = ioContext.getAccessPoint().findByObjectId(testUser1, ModelNames.MODEL_LOCATION, loc.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("Unable to lookup location", lloc);

	}
	*/
	private int cleanupTrait(long groupId, long organizationId) {
		Query lq = QueryUtil.createQuery(ModelNames.MODEL_TRAIT, FieldNames.FIELD_GROUP_ID, groupId);
		lq.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		int deleted = 0;
		try {
			deleted = ioContext.getWriter().delete(lq);
		} catch (WriterException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return deleted;
	}
	

	private boolean resetCountryInfo = false;
	
	
	private String worldName = "Demo World";
	private String worldPath = "~/Worlds";
	
	private BaseRecord getWorld(BaseRecord user, String[] features) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, worldPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord rec = ioContext.getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_WORLD, (long)dir.get(FieldNames.FIELD_ID), worldName);
		if(rec == null) {
			ParameterList plist = ParameterList.newParameterList("path", worldPath);
			plist.parameter("name", worldName);
			try {
				BaseRecord world = ioContext.getFactory().newInstance(ModelNames.MODEL_WORLD, user, null, plist);
				world.set("features", Arrays.asList(features));
				ioContext.getAccessPoint().create(user, world);
				rec = ioContext.getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_WORLD, (long)dir.get(FieldNames.FIELD_ID), worldName);
			} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}

		}
		
		return rec;
	}
	
	private int loadOccupations(BaseRecord user, BaseRecord world, String basePath) {
		ioContext.getReader().populate(world);
		BaseRecord occDir = world.get("occupations");
		ioContext.getReader().populate(occDir);

		WordParser.loadOccupations(user, occDir.get(FieldNames.FIELD_PATH), basePath, resetCountryInfo);
		return ioContext.getAccessPoint().count(user, WordParser.getQuery(user, ModelNames.MODEL_WORD, occDir.get(FieldNames.FIELD_PATH)));

	}
	
	private int loadLocations(BaseRecord user, BaseRecord world, String basePath) {
		List<String> feats = world.get("features");
		String[] features = feats.toArray(new String[0]);
		ioContext.getReader().populate(world);
		BaseRecord locDir = world.get("locations");
		ioContext.getReader().populate(locDir);

		GeoParser.loadInfo(user, locDir.get(FieldNames.FIELD_PATH), testProperties.getProperty("test.datagen.path") + "/location", features, resetCountryInfo);
		
		return ioContext.getAccessPoint().count(user, GeoParser.getQuery(null, null, locDir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID)));

	}
	private int loadDictionary(BaseRecord user, BaseRecord world, String basePath) {
		AuditUtil.setLogToConsole(false);

		ioContext.getReader().populate(world);
		BaseRecord dictDir = world.get("dictionary");
		ioContext.getReader().populate(dictDir);
		
		String groupPath = dictDir.get(FieldNames.FIELD_PATH);
		String wnetPath = basePath;
		
		WordNetParser.loadAdverbs(user, groupPath, wnetPath, 0, false);
		WordNetParser.loadAdjectives(user, groupPath, wnetPath, 0, false);
		WordNetParser.loadNouns(user, groupPath, wnetPath, 0, false);
		WordNetParser.loadVerbs(user, groupPath, wnetPath, 0, false);
		return ioContext.getAccessPoint().count(user, WordNetParser.getQuery(user, null, groupPath));
	}
	private int loadNames(BaseRecord user, BaseRecord world, String basePath) {
		ioContext.getReader().populate(world);
		BaseRecord nameDir = world.get("names");
		ioContext.getReader().populate(nameDir);
		
		String groupPath = nameDir.get(FieldNames.FIELD_PATH);

		WordParser.loadNames(user, groupPath, basePath, resetCountryInfo);
		return ioContext.getAccessPoint().count(user, WordParser.getQuery(user, ModelNames.MODEL_WORD, groupPath));

	}
	private int loadSurnames(BaseRecord user, BaseRecord world, String basePath) {
		ioContext.getReader().populate(world);
		BaseRecord nameDir = world.get("surnames");
		ioContext.getReader().populate(nameDir);
		String groupPath = nameDir.get(FieldNames.FIELD_PATH);
		WordParser.loadSurnames(user, groupPath, basePath,resetCountryInfo);
		return ioContext.getAccessPoint().count(user, WordParser.getQuery(user, ModelNames.MODEL_CENSUS_WORD, groupPath));
	}
	/*
	@Test
	public void TestAltNames() {
		logger.info("Test load alternate geo names");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Geolocation");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String groupPath = "~/CountryInfo";
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		GeoParser.loadAlternateNamesInfo(testUser1, groupPath, testProperties.getProperty("test.datagen.path") + "/location", 0, false);
		int count = ioContext.getAccessPoint().count(testUser1, GeoParser.getQuery("alternateName", null, dir.get(FieldNames.FIELD_ID), testOrgContext.getOrganizationId()));
		logger.info("Count: " + count);
	}
	*/
	
	@Test
	public void TestWorld() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord world = getWorld(testUser1, new String[] {"US"});
		List<String> features = world.get("features");
		if(!features.contains("US")) {
			features.add("US");
			ioContext.getAccessPoint().update(testUser1, world);
		}
		assertNotNull("World is null", world);
		int dict = loadDictionary(testUser1, world, testProperties.getProperty("test.datagen.path") + "/wn3.1.dict/dict");
		logger.info("Dictionary words: " + dict);
		int locs = loadLocations(testUser1, world, testProperties.getProperty("test.datagen.path") + "/location");
		logger.info("Locations: " + locs);
		int occs = loadOccupations(testUser1, world, testProperties.getProperty("test.datagen.path") + "/occupations/noc_2021_version_1.0_-_elements.csv");
		logger.info("Occupations: " + occs);
		int names = loadNames(testUser1, world, testProperties.getProperty("test.datagen.path") + "/names/yob2022.txt");
		logger.info("Names: " + names);
		int surnames = loadSurnames(testUser1, world, testProperties.getProperty("test.datagen.path") + "/surnames/Names_2010Census.csv");
		logger.info("Surnames: " + surnames);
		BaseRecord dir = world.get("locations");
		ioContext.getReader().populate(dir);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field("geoType", "feature");
		Query q2 = new Query(q.copyRecord());
		int regCount = ioContext.getAccessPoint().count(testUser1, q);
		logger.info(regCount + " from " + dir.get(FieldNames.FIELD_ID));
		long randomIndex = (new Random()).nextLong(regCount);
		logger.info(randomIndex);
		q2.setRequestRange(randomIndex, 1);
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q2);
		assertTrue("No locations", qr.getCount()  >0);
		//logger.info(qr.toFullString());
		AuditUtil.setLogToConsole(false);
		BaseRecord addr1 = WorldUtil.randomAddress(testUser1, qr.getResults()[0], "~/Addresses");
		assertNotNull("addr is null", addr1);
		//logger.info(addr1.toFullString());
		
		for(int i = 0; i < 10; i++) {
			BaseRecord per1 = WorldUtil.randomPerson(testUser1, world);

			assertNotNull("per is null", per1);
			logger.info(per1.toFullString());
		}
	}
	
	/*
	@Test
	public void TestWorldModel() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList("path", worldPath);
		plist.parameter("name", worldName);
		BaseRecord world = null;
		
		try {
			String dataPath = "~/Demo Path";
			//BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, dataPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
			String dataName = "Test Data - " + UUID.randomUUID().toString();
			BaseRecord testData = newTestData(testUser1, "~/Demo Data", dataName, "Example data");
			BaseRecord nrec = ioContext.getAccessPoint().create(testUser1, testData);
			logger.info(nrec.toFullString());
			world = ioContext.getFactory().newInstance(ModelNames.MODEL_WORLD, testUser1, null, plist);
			// world = ioContext.getFactory().newInstance(ModelNames.MODEL_WORLD);
			//logger.info(world.inherits(ModelNames.MODEL_DIRECTORY));
		}
		catch(FactoryException e) {
			logger.error(e);
		}
		assertNotNull("World is null", world);
		logger.info(world.toFullString());
	}
	*/

	/*
	@Test
	public void TestWordNetParse() {
		
		AuditUtil.setLogToConsole(false);
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Parse Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Dictionary";
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		String wnetPath = testProperties.getProperty("test.datagen.path") + "/wn3.1.dict/dict";
		
		WordNetParser.loadAdverbs(testUser1, groupPath, wnetPath, 0, false);
		WordNetParser.loadAdjectives(testUser1, groupPath, wnetPath, 0, false);
		WordNetParser.loadNouns(testUser1, groupPath, wnetPath, 0, false);
		WordNetParser.loadVerbs(testUser1, groupPath, wnetPath, 0, false);
		int count = ioContext.getAccessPoint().count(testUser1, WordNetParser.getQuery(testUser1, null, groupPath));
		logger.info("Dictionary word count: " + count);
	}
	*/
	/*
	@Test
	public void TestWordNetParse() {
		
		AuditUtil.setLogToConsole(false);
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Parse Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Word - " + UUID.randomUUID().toString();

		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);

		String wnetPath = testProperties.getProperty("test.datagen.path") + "/wn3.1.dict/dict";

		try {
			int maxLines = 10000;
			// "adj", "adv", "verb", 
			String[] exts = new String[] {"noun"};
			for(String ext: exts) {
				long start = System.currentTimeMillis();
				logger.info("Processing: " + ext);
				
				
				List<BaseRecord> words = WordNetParser.parseWNDataFile(testUser1, groupPath, wnetPath + "/data." + ext, maxLines, new DataParseWriter());
				long stop = System.currentTimeMillis();
				logger.info("Parsed: " + words.size() + " in " + (stop - start) + "ms");
				
				// assertTrue("Expected records to match max size " + maxLines + " but instead received " + words.size(), ((maxLines == 0 && words.size() > 0 )|| (words.size() == maxLines)));

				start = System.currentTimeMillis();
				ioContext.getAccessPoint().create(testUser1, words.toArray(new BaseRecord[0]), true);
				stop = System.currentTimeMillis();
				logger.info("Imported: " + words.size() + " in " + (stop - start) + "ms");
				
			}
			
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	*/
	
	/*
	@Test
	public void TestLoadCountryInfo() {
		logger.info("Test load geolocation data");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Geolocation");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String groupPath = "~/CountryInfo";

		long records = GeoParser.loadInfo(testUser1, groupPath, testProperties.getProperty("test.datagen.path") + "/location", new String[] {"GB", "IE"}, resetCountryInfo);
		logger.info("Total geolocation records in " + groupPath + ": " + records);
	}
	
	@Test
	public void TestNamesParse() {
		logger.info("Test load names data");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Geolocation");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String groupPath = "~/Words/Names";
		String wnetPath = testProperties.getProperty("test.datagen.path") + "/names/yob2022.txt";
		try {
			WordParser.loadNames(testUser1, groupPath, wnetPath, false);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		int records =ioContext.getAccessPoint().count(testUser1, WordParser.getQuery(testUser1, ModelNames.MODEL_WORD, groupPath));
		logger.info("Total word records in " + groupPath + ": " + records);
	}
	
	@Test
	public void TestSurNamesParse() {
		logger.info("Test load surnames data");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Geolocation");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String groupPath = "~/Words/SurNames";
		String wnetPath = testProperties.getProperty("test.datagen.path") + "/surnames/Names_2010Census.csv";
		try {
			WordParser.loadSurnames(testUser1, groupPath, wnetPath, false);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		int records =ioContext.getAccessPoint().count(testUser1, WordParser.getQuery(testUser1, ModelNames.MODEL_CENSUS_WORD, groupPath));
		logger.info("Total word records in " + groupPath + ": " + records);
	}
	
	@Test
	public void TestOccupationsParse() {
		logger.info("Test load occupation data");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Geolocation");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String groupPath = "~/Words/Occupations";
		String occPath = testProperties.getProperty("test.datagen.path") + "/occupations/noc_2021_version_1.0_-_elements.csv";
		try {
			WordParser.loadOccupations(testUser1, groupPath, occPath, false);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		int records =ioContext.getAccessPoint().count(testUser1, WordParser.getQuery(testUser1, ModelNames.MODEL_WORD, groupPath));
		logger.info("Total word records in " + groupPath + ": " + records);
	}
	*/
	/*
	@Test
	public void TestLoadCountryInfoRandomBulk() {
		logger.info("Test load geolocation data: countryInfo");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Geolocation");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String groupPath = "~/Bulk/Geo - " + UUID.randomUUID().toString();

		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());

		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap("iso", 0));
		map.add(new ParseMap("iso3", 1));
		map.add(new ParseMap("name", 4));
		map.add(new ParseMap("capital", 5));
		map.add(new ParseMap("continent", 8));
		map.add(new ParseMap("currencyCode", 10));
		map.add(new ParseMap("currencyName", 11));
		map.add(new ParseMap("languages", 15));
		map.add(new ParseMap("geonameid", 16));
		map.add(new ParseMap("neighbors", 17));
		
		String locPath = testProperties.getProperty("test.datagen.path") + "/location";
		int maxLines = 0;
		
		CSVFormat csvFormat = CSVFormat.TDF;
		BaseRecord template = null;
		try {
			template = RecordFactory.newInstance(ModelNames.MODEL_GEO_LOCATION);
			template.set("geoType", "country");
			template.set("geographyType", GeographyEnumType.PHYSICAL.toString());
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		long start = System.currentTimeMillis();
		List<BaseRecord> locs = GenericParser.parseFile(testUser1, ModelNames.MODEL_GEO_LOCATION, map.toArray(new ParseMap[0]), groupPath, locPath + "/countryInfo.txt", maxLines, template, csvFormat);
		long stop = System.currentTimeMillis();
		logger.info("Parsed: " + locs.size() + " in " + (stop - start) + "ms");
		assertTrue("Expected records to match max size " + maxLines + " but instead received " + locs.size(), ((maxLines == 0 && locs.size() > 0 )|| (locs.size() == maxLines)));
		start = System.currentTimeMillis();
		ioContext.getAccessPoint().create(testUser1, locs.toArray(new BaseRecord[0]), true);
		stop = System.currentTimeMillis();
		logger.info("Imported: " + locs.size() + " in " + (stop - start) + "ms");
		
		/// Admin 1
		logger.info("Load Admin 1 Codes");

		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field("geoType", "country");
		q.setRequest(new String[] {FieldNames.FIELD_ID, "iso"});
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q);

		logger.info("Retrieved " + qr.getCount() + " country items");
		
		map = new ArrayList<>();
		map.add(new ParseMap("code", 0));
		map.add(new ParseMap("altName", 1));
		map.add(new ParseMap("name", 2));
		map.add(new ParseMap("geonameid", 3));
		
		template = null;
		try {
			template = RecordFactory.newInstance(ModelNames.MODEL_GEO_LOCATION);
			template.set("geoType", "admin1");
			template.set("geographyType", GeographyEnumType.PHYSICAL.toString());
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		
		start = System.currentTimeMillis();
		locs = GenericParser.parseFile(testUser1, ModelNames.MODEL_GEO_LOCATION, map.toArray(new ParseMap[0]), groupPath, locPath + "/admin1CodesASCII.txt", maxLines, template, csvFormat);
		stop = System.currentTimeMillis();
		
		logger.info("Mapping location parent");
		List<BaseRecord> qra = Arrays.asList(qr.getResults());
		for(BaseRecord loc : locs) {
			String code = loc.get("code");
			if(code != null) {
				try {
					String[] pair = code.split("\\.");
					Optional<BaseRecord> orec = qra.stream().filter(p -> pair[0].equals(p.get("iso"))).findFirst();
					if(orec.isPresent()) {
						BaseRecord prec = orec.get();
						loc.set(FieldNames.FIELD_PARENT_ID, prec.get(FieldNames.FIELD_ID));
					}
				} catch (ArrayIndexOutOfBoundsException | FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
					e.printStackTrace();
					break;
				}

			}
		}
		
		logger.info("Parsed: " + locs.size() + " in " + (stop - start) + "ms");
		assertTrue("Expected records to match max size " + maxLines + " but instead received " + locs.size(), ((maxLines == 0 && locs.size() > 0 )|| (locs.size() == maxLines)));
		start = System.currentTimeMillis();
		logger.info(locs.get(0).toFullString());
		try {
			ioContext.getAccessPoint().create(testUser1, locs.toArray(new BaseRecord[0]), true);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		stop = System.currentTimeMillis();
		logger.info("Imported: " + locs.size() + " in " + (stop - start) + "ms");

		/// Admin 2
		logger.info("Load Admin 2 Codes");
		map = new ArrayList<>();
		map.add(new ParseMap("code", 0));
		map.add(new ParseMap("name", 1));
		map.add(new ParseMap("geonameid", 3));
		
		template = null;
		try {
			template = RecordFactory.newInstance(ModelNames.MODEL_GEO_LOCATION);
			template.set("geoType", "admin2");
			template.set("geographyType", GeographyEnumType.PHYSICAL.toString());
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		
		start = System.currentTimeMillis();
		locs = GenericParser.parseFile(testUser1, ModelNames.MODEL_GEO_LOCATION, map.toArray(new ParseMap[0]), groupPath, locPath + "/admin2Codes.txt", maxLines, template, csvFormat);

		stop = System.currentTimeMillis();
		
		logger.info("Parsed: " + locs.size() + " in " + (stop - start) + "ms");
		assertTrue("Expected records to match max size " + maxLines + " but instead received " + locs.size(), ((maxLines == 0 && locs.size() > 0 )|| (locs.size() == maxLines)));
		start = System.currentTimeMillis();
		ioContext.getAccessPoint().create(testUser1, locs.toArray(new BaseRecord[0]), true);
		stop = System.currentTimeMillis();
		logger.info("Imported: " + locs.size() + " in " + (stop - start) + "ms");
	}
	*/

	/*
	
	@Test
	public void TestGenericParse() {
		
		logger.info("Test Generic Parser");
		logger.info("Test depends on external US data for common baby names by year.  See ./notes/dataNotes.txt");
		
		AuditUtil.setLogToConsole(false);
			
		OrganizationContext testOrgContext = getTestOrganization("/Development/Parse Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Names - " + UUID.randomUUID().toString();

		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);

		String wnetPath = testProperties.getProperty("test.datagen.path") + "/names";

		try {
			int maxLines = 5000;
			String[] files = new String[] {"yob2022.txt"};
			for(String file : files) {
				long start = System.currentTimeMillis();
				logger.info("Processing: " + file);
				
				List<BaseRecord> words = GenericParser.parseFile(testUser1, ModelNames.MODEL_WORD, new String[] {"name", "gender", "count"}, groupPath, wnetPath + "/" + file, maxLines);
				long stop = System.currentTimeMillis();
				logger.info("Parsed: " + words.size() + " in " + (stop - start) + "ms");
				
				start = System.currentTimeMillis();
				ioContext.getAccessPoint().create(testUser1, words.toArray(new BaseRecord[0]), true);
				stop = System.currentTimeMillis();
				logger.info("Imported: " + words.size() + " in " + (stop - start) + "ms");
			}
			
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	@Test
	public void TestWordNetParse() {
		
		AuditUtil.setLogToConsole(false);
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Parse Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Word - " + UUID.randomUUID().toString();

		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);

		String wnetPath = testProperties.getProperty("test.datagen.path") + "/wn3.1.dict/dict";

		try {
			int maxLines = 1000;
			String[] exts = new String[] {"adj", "adv", "verb", "noun"};
			for(String ext: exts) {
				long start = System.currentTimeMillis();
				logger.info("Processing: " + ext);
				
				
				List<BaseRecord> words = WordNetParser.parseWNDataFile(testUser1, groupPath, wnetPath + "/data." + ext, maxLines);
				long stop = System.currentTimeMillis();
				logger.info("Parsed: " + words.size() + " in " + (stop - start) + "ms");
				
				assertTrue("Expected records to match max size " + maxLines + " but instead received " + words.size(), ((maxLines == 0 && words.size() > 0 )|| (words.size() == maxLines)));

				start = System.currentTimeMillis();
				ioContext.getAccessPoint().create(testUser1, words.toArray(new BaseRecord[0]), true);
				stop = System.currentTimeMillis();
				logger.info("Imported: " + words.size() + " in " + (stop - start) + "ms");
				
			}
			
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	@Test
	public void TestLoadTraits() {
		logger.info("Test load bulk traits");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Batch Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Trait - " + UUID.randomUUID().toString();

		
		//logger.info("Received: " + traits.size());
		List<BaseRecord> traitrecs = getBulkTraits(testUser1, groupPath);

		logger.info("Single load: " + traitrecs.size() + " traits");
		long start = System.currentTimeMillis();
		for(BaseRecord rec : traitrecs) {
			ioContext.getAccessPoint().create(testUser1, rec);
		}
		long stop = System.currentTimeMillis();
		long diff1 = (stop - start);


		groupPath = "~/Bulk/Trait - " + UUID.randomUUID().toString();
		traitrecs = getBulkTraits(testUser1, groupPath);
		
		logger.info("Bulk load: " + traitrecs.size() + " traits");
		start = System.currentTimeMillis();
		ioContext.getAccessPoint().create(testUser1, traitrecs.toArray(new BaseRecord[0]), true);
		stop = System.currentTimeMillis();
		
		logger.info("Time to insert individually: " + diff1 + "ms");
		logger.info("Time to insert by batch: " + (stop - start) + "ms");

	}

	@Test
	public void TestBulkInsertWithAttributes() {
		logger.info("Test load bulk objects with attributes");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Batch Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Data - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);
		
		String bulkDataNamePrefix = "Bulk Data Test - ";
		List<BaseRecord> bulkLoad = new ArrayList<>();
		
		logger.info("Generating dataset - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, bulkDataNamePrefix + (i+1), "This is the example data");
			try {
				AttributeUtil.addAttribute(data, "Example Attribute 1", "Test String");
				AttributeUtil.addAttribute(data, "Example Attribute 2", 123);
			}
			catch(ValueException | ModelException | FieldException | ModelNotFoundException e) {
				logger.error(e);
			}
			bulkLoad.add(data);
		}
		
		long start = System.currentTimeMillis();
		ioContext.getAccessPoint().create(testUser1, bulkLoad.toArray(new BaseRecord[0]), true);
		long stop = System.currentTimeMillis();
		logger.info("Time to insert by batch: " + (stop - start) + "ms");
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, bulkDataNamePrefix);
		q.setRequest(new String[] {FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ATTRIBUTES});
		
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q);
		assertTrue("Expected to retrieve the batch size", qr.getCount() == bulkLoadSize);
		
		logger.info(qr.getResults()[0].toFullString());
		
	}


	@Test
	public void TestSingleBatchInsertSameType() {
		logger.info("Testing inserting records one at a time versus by batch");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Batch Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Data - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);
		
		String dataNamePrefix = "Data Test - ";
		String bulkDataNamePrefix = "Bulk Data Test - ";
		List<BaseRecord> bulkLoad = new ArrayList<>();
		List<BaseRecord> bulkLoad2 = new ArrayList<>();
		
		logger.info("Generating dataset 1 - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, dataNamePrefix + (i+1), "This is the example data");
			bulkLoad.add(data);
		}

		long start = System.currentTimeMillis();
		for(int i = 0; i < bulkLoad.size(); i++) {
			ioContext.getAccessPoint().create(testUser1, bulkLoad.get(i));
		}
		long stop = System.currentTimeMillis();
		logger.info("Time to insert by individual record: " + (stop - start) + "ms");
		
		logger.info("Generating dataset 2 - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, bulkDataNamePrefix + (i+1), "This is the example data");
			bulkLoad2.add(data);
		}
		
		start = System.currentTimeMillis();

		ioContext.getAccessPoint().create(testUser1, bulkLoad2.toArray(new BaseRecord[0]));
		
		stop = System.currentTimeMillis();
		logger.info("Time to insert by batch: " + (stop - start) + "ms");
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, bulkDataNamePrefix);
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q);
		assertTrue("Expected to retrieve the batch size", qr.getCount() == bulkLoadSize);
		
	}

	
	@Test
	public void TestBatchUpdate() {
		logger.info("Testing updating a batch of records");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Batch Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Data - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);
		
		String bulkDataNamePrefix = "Bulk Data Test - ";
		List<BaseRecord> bulkLoad = new ArrayList<>();
		
		logger.info("Generating dataset - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, bulkDataNamePrefix + (i+1), "This is the example data");
			bulkLoad.add(data);
		}
		
		long start = System.currentTimeMillis();
		ioContext.getAccessPoint().create(testUser1, bulkLoad.toArray(new BaseRecord[0]));
		long stop = System.currentTimeMillis();
		logger.info("Time to insert by batch: " + (stop - start) + "ms");
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, bulkDataNamePrefix);
		q.setRequest(new String[] {FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_CONTENT_TYPE});
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q);
		assertTrue("Expected to retrieve the batch size", qr.getCount() == bulkLoadSize);
		
		BaseRecord[] records = qr.getResults();
		boolean error = false;
		try {
			records[0].set(FieldNames.FIELD_DESCRIPTION, "This is an example description");

			start = System.currentTimeMillis();
			int updated = ioContext.getAccessPoint().update(testUser1, records);
			stop = System.currentTimeMillis();
			logger.info("Time to fail update by batch: " + (stop - start) + "ms");
			
			assertTrue("Expected update to fail because the first record includes a field that the other records do not", updated == 0);
			for(BaseRecord rec: records) {
				rec.set(FieldNames.FIELD_DESCRIPTION, "Patch description: " + UUID.randomUUID().toString());
			}
			
			start = System.currentTimeMillis();
			updated = ioContext.getAccessPoint().update(testUser1, records);
			stop = System.currentTimeMillis();
			logger.info("Time to update by batch: " + (stop - start) + "ms");
			assertTrue("Expected update (" + updated + ") to succeed for all (" + records.length + ") records", updated == records.length);
			
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			error = true;
		}
		assertFalse("Encountered an error", error);
	}
	*/
}
