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
import java.util.Set;
import java.util.UUID;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;

import org.cote.accountmanager.parsers.GenericParser;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.parsers.WordNetParser;
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
	
	@Test
	public void TestLoadCountryInfo() {
		logger.info("Test load geolocation data: countryInfo");
		// AuditUtil.setLogToConsole(false);
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
