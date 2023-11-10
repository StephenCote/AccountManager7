package org.cote.accountmanager.parsers.geo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.QuoteMode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.parsers.GenericParser;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.parsers.data.DataInterceptor;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GeographyEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;

public class GeoParser {
	
	/// Parser for geolocation data from GeoNames Gazetteer - http://download.geonames.org/export/dump/

	public static final Logger logger = LogManager.getLogger(GeoParser.class);
	/// .Builder.create().setDelimiter('\t').setRecordSeparator("\r\n").setQuote('"').setIgnoreSurroundingSpaces(true).build()
	private static CSVFormat defaultFormat = CSVFormat.TDF;
	public static Query getQuery(String geoType, String isoCode, long groupId, long organizationId) {
		Query lq = QueryUtil.getGroupQuery(ModelNames.MODEL_GEO_LOCATION, null, groupId, organizationId);
		if(geoType != null) {
			lq.field("geoType", geoType);
		}
		if(isoCode != null) {
			lq.field("iso", isoCode);
		}
		return lq;
	}
	public static int cleanupLocation(String geoType, String isoCode, long groupId, long organizationId) {
		Query lq = getQuery(geoType, isoCode, groupId, organizationId);
		/*
		QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, groupId);
		lq.field("geoType", geoType);
		if(isoCode != null) {
			lq.field("iso", isoCode);
		}
		lq.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		*/
		int deleted = 0;
		try {
			deleted = IOSystem.getActiveContext().getWriter().delete(lq);
		} catch (WriterException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return deleted;
	}
	public static int countCleanupLocation(BaseRecord user, String groupPath, String geoType, String isoCode, boolean resetCountryInfo) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query lq = getQuery(geoType, isoCode, dir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		
		int count = IOSystem.getActiveContext().getAccessPoint().count(user, lq);
		if(count > 0 && resetCountryInfo) {
			logger.info("Cleaning up " + count + " " + geoType + " records in " + groupPath);
			cleanupLocation(geoType, isoCode, dir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID));
			count = 0;
		}
		return count;
	}

	public static long loadInfo(BaseRecord user, String groupPath, String basePath, String[] isoCodes, boolean resetCountryInfo) {
		long count = 0;
		try {
			int mcount = loadCountryInfo(user, groupPath, basePath, 0, resetCountryInfo);
			if(mcount == 0) {
				logger.error("Failed to load country info");
				return 0;
			}
			count += mcount;
			mcount = loadAdmin1Info(user, groupPath, basePath, 0, resetCountryInfo);
			if(mcount == 0) {
			logger.error("Failed to load admin1 info");
				return 0;
			}
			count += mcount;
			
			mcount = loadAdmin2Info(user, groupPath, basePath, 0, resetCountryInfo);
			if(mcount == 0) {
			logger.error("Failed to load admin2 info");
				return 0;
			}
			count += mcount;
			
			mcount = loadAlternateNamesInfo(user, groupPath, basePath, 0, resetCountryInfo);
			if(mcount == 0) {
			logger.error("Failed to load alternate names info");
				return 0;
			}
			count += mcount;
			
			for(String iso : isoCodes) {
				mcount = loadFeatureInfo(user, groupPath, basePath, iso, 0, false);
				if(mcount == 0) {
					logger.error("Failed to load feature info for " + iso);
					return 0;
				}
				count += mcount;
			}
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		return count;
	}
	
	public static int loadCountryInfo(BaseRecord user, String groupPath, String basePath, int maxLines,boolean resetCountryInfo) {
		// logger.info("Load country information into " + groupPath);
		int count = countCleanupLocation(user, groupPath, "country", null, resetCountryInfo);
		if(count == 0) {
			ParseConfiguration cfg = GeoParser.newCountryParseConfiguration(user, groupPath, basePath, maxLines);
			count = importFile(cfg);
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}
		return count;
	}
	
	
	public static int loadAdmin1Info(BaseRecord user, String groupPath, String basePath, int maxLines, boolean resetCountryInfo) {
		// logger.info("Load admin1 information into " + groupPath);
		int count = countCleanupLocation(user, groupPath, "admin1", null, resetCountryInfo);
		if(count == 0) {
			ParseConfiguration cfg = GeoParser.newAdmin1ParseConfiguration(user, groupPath, basePath, maxLines);
			count = importFile(cfg);
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}

		return count;
	}
	
	public static int loadAdmin2Info(BaseRecord user, String groupPath, String basePath, int maxLines, boolean resetCountryInfo) {
		// logger.info("Load admin2 information into " + groupPath);
		int count = countCleanupLocation(user, groupPath, "admin2", null, resetCountryInfo);
		if(count == 0) {
			ParseConfiguration cfg = GeoParser.newAdmin2ParseConfiguration(user, groupPath, basePath, maxLines);
			count = importFile(cfg);
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}

		return count;
	}
	
	public static int loadFeatureInfo(BaseRecord user, String groupPath, String basePath, String isoCode, int maxLines, boolean resetCountryInfo) {
		// logger.info("Load " + isoCode + " feature information into " + groupPath);
		int count = countCleanupLocation(user, groupPath, "feature", isoCode, resetCountryInfo);
		if(count == 0) {
			ParseConfiguration cfg = GeoParser.newFeatureParseConfiguration(user, groupPath, basePath, isoCode, maxLines);
			count = importFile(cfg);
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}

		return count;
	}
	
	public static int loadAlternateNamesInfo(BaseRecord user, String groupPath, String basePath, int maxLines, boolean resetCountryInfo) {
		// logger.info("Load alternate names information into " + groupPath);
		int count = countCleanupLocation(user, groupPath, "alternateName", null, resetCountryInfo);
		if(count == 0) {
			ParseConfiguration cfg = GeoParser.newAlternateNameParseConfiguration(user, groupPath, basePath, maxLines);
			count = importFile(cfg);
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}

		return count;
	}
	
	public static int importFile(ParseConfiguration cfg) {
		
		long start = System.currentTimeMillis();
		/// TODO: Revise parser to only use the writer and return the total write count
		List<BaseRecord> recs = GenericParser.parseFile(cfg, new GeoParseWriter());
		long stop = System.currentTimeMillis();
		logger.info("Parsed locations from " + cfg.getFilePath() + " in " + (stop - start) + "ms");
		
		return recs.size();
	}
	
	public static ParseConfiguration newCountryParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Country Parse Configuration");
		
		BaseRecord template = null;
		try {
			template = RecordFactory.newInstance(ModelNames.MODEL_GEO_LOCATION);
			template.set("geoType", "country");
			template.set("geographyType", GeographyEnumType.PHYSICAL.toString());
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		
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
		
		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setModel(ModelNames.MODEL_GEO_LOCATION);
		cfg.setCsvFormat(defaultFormat);
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath + "/countryInfo.txt");
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		cfg.setTemplate(template);
		
		return cfg;
	}
	
	public static ParseConfiguration newAlternateNameParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Alternate Name Parse Configuration");
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap("altgeonameid", 1));
		map.add(new ParseMap("geonameid", 0));
		map.add(new ParseMap("altType", 2));
		map.add(new ParseMap("name", 3));
		
	
		BaseRecord template = null;
		try {
			template = RecordFactory.newInstance(ModelNames.MODEL_GEO_LOCATION);
			template.set("geoType", "alternateName");
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	
		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setModel(ModelNames.MODEL_GEO_LOCATION);
		cfg.setCsvFormat(defaultFormat);
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath + "/alternateNamesV2.txt");
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		cfg.setTemplate(template);

		ParseMap filter = new ParseMap(null, 2);
		filter.setMatchValue("post");
		cfg.setFilters(new ParseMap[] {filter});
		cfg.setInterceptor(new DataInterceptor());
		
		return cfg;
	}
	
	public static ParseConfiguration newFeatureParseConfiguration(BaseRecord user, String groupPath, String basePath, String isoCode, int maxLines) {
		logger.info("New Region Parse Configuration: " + isoCode);
		List<ParseMap> map = new ArrayList<>();
		
		map.add(new ParseMap("altName", 1));
		map.add(new ParseMap("name", 2));
		map.add(new ParseMap("geonameid", 0));
		map.add(new ParseMap("latitude", 4));
		map.add(new ParseMap("longitude", 5));
		map.add(new ParseMap("feature", 6, new ParseMap(null, 7)));
		map.add(new ParseMap("code", 8, new ParseMap(null, 10)));

		BaseRecord template = null;
		try {
			template = RecordFactory.newInstance(ModelNames.MODEL_GEO_LOCATION);
			template.set("geoType", "feature");
			template.set("iso", isoCode);
			template.set("geographyType", GeographyEnumType.PHYSICAL.toString());
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));

		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.setRequest(new String[] {FieldNames.FIELD_ID, "code"});
		q.field("iso", isoCode.toUpperCase());
		QueryField or = q.field(null, ComparatorEnumType.GROUP_OR, null);
		QueryField or1 = q.field(null, ComparatorEnumType.GROUP_AND, null, or);
		q.field("geoType", ComparatorEnumType.EQUALS, "admin1", or1);
		QueryField or2 = q.field(null, ComparatorEnumType.GROUP_AND, null, or);
		q.field("geoType", ComparatorEnumType.EQUALS, "admin2", or2);
		
		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setModel(ModelNames.MODEL_GEO_LOCATION);
		cfg.setCsvFormat(defaultFormat);
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath + "/" + isoCode.toUpperCase() + ".txt");
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		cfg.setParentQuery(q);
		cfg.setTemplate(template);
		cfg.setInterceptor(new RegionInterceptor());
		cfg.setMapField("code");
		cfg.setParentMapField("code");
		return cfg;
	}

	public static ParseConfiguration newAdmin1ParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Admin 1 Parse Configuration");
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap("code", 0));
		map.add(new ParseMap("iso", 0, new CodeInterceptor()));
		map.add(new ParseMap("altName", 1));
		map.add(new ParseMap("name", 2));
		map.add(new ParseMap("geonameid", 3));
	
		BaseRecord template = null;
		try {
			template = RecordFactory.newInstance(ModelNames.MODEL_GEO_LOCATION);
			template.set("geoType", "admin1");
			template.set("geographyType", GeographyEnumType.PHYSICAL.toString());
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field("geoType", "country");
		q.setRequest(new String[] {FieldNames.FIELD_ID, "iso"});

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setModel(ModelNames.MODEL_GEO_LOCATION);
		cfg.setCsvFormat(defaultFormat);
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath + "/admin1CodesASCII.txt");
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		cfg.setParentQuery(q);
		cfg.setTemplate(template);
		cfg.setMapField("code");
		cfg.setParentMapField("iso");

		return cfg;
	}

	public static ParseConfiguration newAdmin2ParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Admin 2 Parse Configuration");
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap("code", 0));
		map.add(new ParseMap("iso", 0, new CodeInterceptor()));
		map.add(new ParseMap("altName", 1));
		map.add(new ParseMap("name", 2));
		map.add(new ParseMap("geonameid", 3));
	
		BaseRecord template = null;
		try {
			template = RecordFactory.newInstance(ModelNames.MODEL_GEO_LOCATION);
			template.set("geoType", "admin2");
			template.set("geographyType", GeographyEnumType.PHYSICAL.toString());
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field("geoType", "country");
		q.setRequest(new String[] {FieldNames.FIELD_ID, "iso"});

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setModel(ModelNames.MODEL_GEO_LOCATION);
		cfg.setCsvFormat(defaultFormat);
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath + "/admin2Codes.txt");
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		cfg.setParentQuery(q);
		cfg.setTemplate(template);
		cfg.setMapField("code");
		cfg.setParentMapField("code");

		return cfg;
	}


}
