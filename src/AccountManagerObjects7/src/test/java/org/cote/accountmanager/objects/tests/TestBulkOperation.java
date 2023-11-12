package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
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
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.AlignmentEnumType;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GeographyEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.olio.VeryEnumType;
import org.cote.accountmanager.olio.WorldUtil;
import org.junit.Test;

public class TestBulkOperation extends BaseTest {

	/*
	 * These unit tests depend on a variety of external data that must be downloaded separately and staged relative to the data path defined in the test resources file.
	 * Data files include: Princeton wordnet dictionary data files, GeoNames file dumps, US baby names, CA surnames, and occuptations.
	 * notes/dataNotes.txt contains the links to these data sources 
	 */
	
	private int bulkLoadSize = 10;

	private boolean resetCountryInfo = false;
	
	private String worldName = "Demo World";
	private String subWorldName = "Sub World";
	private String worldPath = "~/Worlds";
	
	@Test
	public void TestOlio() {
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Olio");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String olioPath = "~/Olio";
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, olioPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList("path", olioPath);
		BaseRecord irec = null;

		
		try {
			BaseRecord instinct = ioContext.getFactory().newInstance(ModelNames.MODEL_INSTINCT, testUser1, null, plist);
			assertNotNull("Record was null", instinct);
			
			irec = ioContext.getAccessPoint().create(testUser1, instinct);
			
			// world = ioContext.getFactory().newInstance(ModelNames.MODEL_WORLD);
			//logger.info(world.inherits(ModelNames.MODEL_DIRECTORY));
		}
		catch(ClassCastException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull("Instinct is null", irec);
		logger.info(irec.toFullString());
		
		BaseRecord q1 = newQuality(testUser1, olioPath);
		assertNotNull("Quality is null", q1);
		//logger.info(q1.toFullString());

		//String sql = ioContext.getDbUtil().generateSchema(RecordFactory.getSchema(ModelNames.MODEL_WEARABLE));
		//logger.info(sql);
		
		BaseRecord w1 = newWearable(testUser1, "hat", "head", olioPath);
		assertNotNull("Wearable is null", w1);
		
		BaseRecord a1 = newApparel(testUser1, "Beach Outfit", "swim", olioPath, new BaseRecord[] {
			newWearable(testUser1, "bikini top", "chest", olioPath),
			newWearable(testUser1, "bikini bottom", "waist", olioPath),
			newWearable(testUser1, "sandals", "feet", olioPath),
			newWearable(testUser1, "sunglasses", "eyes", olioPath),
			newWearable(testUser1, "sunhat", "head", olioPath)
			// ioContext.getFactory().newInstance(ModelNames.MODEL_WEARABLE, testUser1, ioContext.getFactory().template(ModelNames.MODEL_WEARABLE, "{\"name\": \"earring\"}"), null)
		});

		assertNotNull("A1 is null", a1);
		//BaseRecord ia1 = ioContext.getAccessPoint().findById(testUser1, ModelNames.MODEL_APPAREL, a1.get(FieldNames.FIELD_ID));
		// ioContext.getReader().populate(ia1, 2);
		// logger.info(ia1.toFullString());
		
		String polyStr = ResourceUtil.getResource("./olio/wearable/swimPolyester.json");
		/// "$randomRange[0.15-1]".match(/\$randomRange\[([\d\.]+)\-([\d\.]+)/).length
		Pattern pat = Pattern.compile("\\$randomRange\\[([\\d\\.]+)\\-([\\d\\.]+)");
		Matcher mat = pat.matcher(polyStr);
		Random rand = new Random();
		DecimalFormat df = new DecimalFormat("#.##");
		df.setRoundingMode(RoundingMode.HALF_EVEN);
		if(mat.find()) {
			double min = Double.parseDouble(mat.group(1));
			double max = Double.parseDouble(mat.group(2));
			double res = Double.parseDouble(df.format(rand.nextDouble(max-min) + min));
			VeryEnumType ver = VeryEnumType.valueOf(res);
			// logger.info(min + " to " + max + " = " + res + " - " + ver.toString());
		}
		
		WordParser.loadColors(testUser1, "~/Colors", testProperties.getProperty("test.datagen.path") + "/colors.csv", false);
		
		String appStr = getOlioResource(testUser1, "./olio/apparel/swimPolyester.json");
		logger.info(appStr);
		
		//logger.info(Strings.join(Arrays.asList(RecordUtil.getCommonFields(ModelNames.MODEL_QUALITY)), ','));
		//BaseRecord iw1 = ioContext.getAccessPoint().findById(testUser1, ModelNames.MODEL_WEARABLE, w1.get(FieldNames.FIELD_ID));
		//ioContext.getReader().populate(iw1);
		//logger.info(iw1.toFullString());
		//logger.info(JSONUtil.exportObject(schema));
		
	}
	private Pattern pat = Pattern.compile("\"\\$randomRange\\[([\\d\\.]+)\\-([\\d\\.]+)\\]\"");
	Pattern pat2 = Pattern.compile("\"\\$\\{([A-Za-z]+)\\.([A-Za-z]+)([A-Za-z\\[\\]=,\\s\\d\\.]*)\\}\"");
	public String replaceTokens(final String text) {
		
		Matcher mat = pat.matcher(text);
		Random rand = new Random();
		DecimalFormat df = new DecimalFormat("#.##");
		df.setRoundingMode(RoundingMode.HALF_EVEN);
		//String workText = text;
		int idx = 0;
		StringBuilder rep = new StringBuilder();

		while(mat.find()) {
			double min = Double.parseDouble(mat.group(1));
			double max = Double.parseDouble(mat.group(2));
			double res = Double.parseDouble(df.format(rand.nextDouble(max-min) + min));
			VeryEnumType ver = VeryEnumType.valueOf(res);
			logger.info(min + " to " + max + " = " + res + " - " + ver.toString());
			//workText = mat.replaceAll(Double.toString(res));
		    rep.append(text, idx, mat.start()).append(Double.toString(res));
		    idx = mat.end();

		}
		if (idx < text.length()) {
		    rep.append(text, idx, text.length());
		}
		return rep.toString();
	}
	public String getOlioResource(BaseRecord user, String path) {
		String appStr = ResourceUtil.getResource("./olio/apparel/swimPolyester.json");
		Matcher m = pat2.matcher(appStr);
		int idx = 0;
		StringBuilder rep = new StringBuilder();
		
		while(m.find()) {
			String type = m.group(1);
			String wear = m.group(2);
			String parms = null;
			if(m.groupCount() > 2) {
				parms = m.group(3);
			}
			String wearx = replaceTokens(ResourceUtil.getResource("./olio/" + type + "/" + wear + ".json"));
			logger.info(wearx);
			BaseRecord temp1 = ioContext.getFactory().template("olio." + type, wearx);
			
			if(parms != null) {
				String[] pairs = parms.substring(1, parms.length() - 1).split(",");
				for(String pair : pairs) {
					String[] kv = pair.split("=");
					if(kv.length == 2) {
						FieldType f = temp1.getField(kv[0].trim());
						String val = kv[1].trim();
						try {
							switch(f.getValueType()) {
								case STRING:
									f.setValue(val);
									break;
								case LIST:
									List<String> lst = f.getValue();
									lst.add(val);
									break;
								default:
									logger.error("Unhandled type: " + f.getValueType().toString());
									break;
							}
						}
						catch(ValueException e) {
							logger.error(e);
						}
					}
				}
			}
			applyRandomOlioValues(user, temp1);
			//logger.info(temp1.toFullString());
			//appStr = m.replaceFirst(temp1.toFullString());
		    rep.append(appStr, idx, m.start()).append(temp1.toFullString());
		    idx = m.end();
		}
		if (idx < appStr.length()) {
		    rep.append(appStr, idx, appStr.length());
		}

		return rep.toString();
	}
	public String getRandomOlioValue(BaseRecord user, String fieldName) {
		String outVal = null;
		switch(fieldName) {
			case "color":
				BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, "~/Colors", "DATA", user.get(FieldNames.FIELD_ORGANIZATION_ID));
				outVal = WorldUtil.randomSelectionName(user, QueryUtil.createQuery(ModelNames.MODEL_COLOR, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID)));
				break;
		}
		return outVal;
	}
	public void applyRandomOlioValues(BaseRecord user, BaseRecord record) {
		record.getFields().forEach(f -> {
			try {
				if(f.getValueType() == FieldEnumType.STRING) {
					String val = f.getValue();
					if("$random".equals(val)) {
						f.setValue(getRandomOlioValue(user, f.getName()));
					}
				}
			}
			catch(ValueException e) {
				logger.error(e);
			}
		});
	}
	public BaseRecord outfit(BaseRecord user, String path, String apparelStr) {
		BaseRecord temp1 = ioContext.getFactory().template(ModelNames.MODEL_APPAREL, apparelStr);
		
		return temp1;
	}
	private BaseRecord newApparel(BaseRecord user, String name, String type, String groupPath, BaseRecord[] wearables) {
		BaseRecord temp1 = ioContext.getFactory().template(ModelNames.MODEL_APPAREL, "{\"name\": \"" + name + "\",\"type\":\"" + type + "\"}");
		BaseRecord app = newGroupRecord(user, ModelNames.MODEL_APPAREL, groupPath, temp1);
		logger.info(app.toFullString());
		List<BaseRecord> wables = app.get("wearables");
		wables.addAll(Arrays.asList(wearables));
		return ioContext.getAccessPoint().create(user, app);
	}
	private BaseRecord newWearable(BaseRecord user, String name, String location, String groupPath) {
		BaseRecord temp1 = ioContext.getFactory().template(ModelNames.MODEL_WEARABLE, "{\"name\": \"" + name + "\",\"location\":[\"" + location + "\"]}");
		BaseRecord wear = newGroupRecord(user, ModelNames.MODEL_WEARABLE, groupPath, temp1);
		List<BaseRecord> quals = wear.get("qualities");
		quals.add(newGroupRecord(user, ModelNames.MODEL_QUALITY, groupPath, null));
		return ioContext.getAccessPoint().create(user, wear);
	}
	private BaseRecord newQuality(BaseRecord user, String groupPath) {
		BaseRecord qual = newGroupRecord(user, ModelNames.MODEL_QUALITY, groupPath, null);
		return ioContext.getAccessPoint().create(user, qual);
	}
	private BaseRecord newGroupRecord(BaseRecord user, String model, String groupPath, BaseRecord template) {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dir == null) {
			logger.error("Failed to find or create group " + groupPath);
			return null;
		}
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		BaseRecord irec = null;
		try {
			irec = ioContext.getFactory().newInstance(model, user, template, plist);
		}
		catch(ClassCastException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return irec;
	}

	
	
	/*
	@Test
	public void TestOlio2() {
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord world = WorldUtil.getCreateWorld(testUser1, worldPath, worldName, new String[] {"AS"});
		assertNotNull("World is null", world);
		WorldUtil.populateWorld(testUser1, world, testProperties.getProperty("test.datagen.path"), false);
		
		BaseRecord subWorld = WorldUtil.getCreateWorld(testUser1, world, worldPath, subWorldName, new String[0]);
		// logger.info("Cleanup world: " + WorldUtil.cleanupWorld(testUser1, subWorld));
		// logger.info(subWorld.toFullString());
		//AuditUtil.setLogToConsole(true);
		try {
			BaseRecord event = WorldUtil.generateRegion(testUser1, subWorld, 2, 250);
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}
	
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
