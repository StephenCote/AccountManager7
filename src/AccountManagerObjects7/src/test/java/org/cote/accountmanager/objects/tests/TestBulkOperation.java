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
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
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
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GeographyEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.EpochUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.VeryEnumType;
import org.cote.accountmanager.olio.WearLevelEnumType;
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
	
	/*
	@Test
	public void TestOlio3() {
		String[] outfit = ApparelUtil.randomOutfit(WearLevelEnumType.BASE, WearLevelEnumType.ACCESSORY, "male", .35);
		logger.info(String.join(", ", outfit));
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

	}
	*/
	@Test
	public void TestDeepSingleModelQuery() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		List<BaseRecord> aal = new ArrayList<>();
		String path = "~/Demo - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList("path", path);
		String name = "Dooter - " + UUID.randomUUID().toString();
		plist.parameter("name", name);
		try {
			BaseRecord a1 = ioContext.getFactory().newInstance(ModelNames.MODEL_APPAREL, testUser1, null, plist);
			aal.add(a1);
			List<BaseRecord> wl = a1.get("wearables");
			BaseRecord w1 = ioContext.getFactory().newInstance(ModelNames.MODEL_WEARABLE, testUser1, null, plist);
			wl.add(w1);
			List<BaseRecord> ql = w1.get("qualities");
			BaseRecord q1 = ioContext.getFactory().newInstance(ModelNames.MODEL_QUALITY, testUser1, null, plist);
			ql.add(q1);
			BaseRecord d1 = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			w1.set("pattern", d1);
			d1.set("dataBytesStore", "This is some example text".getBytes());
			ioContext.getAccessPoint().create(testUser1, a1);
			//logger.info(a1.toFullString());
			
			Query q = QueryUtil.createQuery(ModelNames.MODEL_APPAREL, FieldNames.FIELD_ID, a1.get(FieldNames.FIELD_ID));
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
			DBStatementMeta meta = StatementUtil.getSelectTemplate(q);
			// logger.info(meta.getSql());
			BaseRecord a2 = ioContext.getSearch().findRecord(q);
			assertNotNull("It's null", a2);
			logger.info(a2.toFullString());
		}
		catch(ModelNotFoundException | FactoryException | FieldException | ValueException | ModelException e) {
			logger.error(e);
		}
		

	}
	@Test
	public void TestOlio2() {

		AuditUtil.setLogToConsole(false);

		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord world = WorldUtil.getCreateWorld(testUser1, worldPath, worldName, new String[] {"AS"});
		assertNotNull("World is null", world);
		WorldUtil.loadWorldData(testUser1, world, testProperties.getProperty("test.datagen.path"), false);
		
		BaseRecord subWorld = WorldUtil.getCreateWorld(testUser1, world, worldPath, subWorldName, new String[0]);
		// logger.info("Cleanup world: " + WorldUtil.cleanupWorld(testUser1, subWorld));

		try {

			WorldUtil.generateRegion(testUser1, subWorld, 2, 250);
			BaseRecord event = EpochUtil.generateEpoch(testUser1, subWorld, 1);
			assertNotNull("Event is null", event);

			Query qp1 = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, subWorld.get("population.id"));
			qp1.set(FieldNames.FIELD_LIMIT_FIELDS, false);
			BaseRecord person = OlioUtil.randomSelection(testUser1, qp1);
			assertNotNull("Person is null", person);
			logger.info("Current age: " + CharacterUtil.getCurrentAge(testUser1, subWorld, person));
			ioContext.getReader().populate(person.get("statistics"));
			logger.info(person.toFullString());

		}
		catch(Exception e) {
			e.printStackTrace();
		}

	}

	
	/*
	((List<BaseRecord>)app.get("wearables")).forEach(r -> {
		logger.info(r.get("level") + " " + r.get("color") + " " + r.get("fabric") + " " + r.get("pattern.name") + " " + r.get("name"));
	});
	*/
	
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
