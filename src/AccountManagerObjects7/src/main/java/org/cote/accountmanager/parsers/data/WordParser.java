package org.cote.accountmanager.parsers.data;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.parsers.GenericParser;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.parsers.geo.GeoParseWriter;
import org.cote.accountmanager.parsers.geo.GeoParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GeographyEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;

public class WordParser {

	public static final Logger logger = LogManager.getLogger(WordParser.class);

	public static ParseConfiguration newSurnameParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Surnames Parse Configuration");
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap("name", 0, new CaseInterceptor()));

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setModel(ModelNames.MODEL_WORD);
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(false).setQuote(null).setTrim(true).build());
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath);
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		ParseMap filter = new ParseMap(null, 0);
		filter.setMatchValue("name");
		cfg.setFilters(new ParseMap[] {filter});
		cfg.setInterceptor(new DataInterceptor());
		return cfg;
	}
	
	public static ParseConfiguration newOccupationsParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Occupations Parse Configuration");
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap("name", 4));
		map.add(new ParseMap("class", 2));

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setModel(ModelNames.MODEL_WORD);
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build());
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath);
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		ParseMap filter = new ParseMap(null, 3);
		filter.setMatchValue("All examples");
		cfg.setFilters(new ParseMap[] {filter});
		cfg.setInterceptor(new DataInterceptor());
		return cfg;
	}
	
	public static ParseConfiguration newNamesParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Names Parse Configuration");
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap("name", 0));
		map.add(new ParseMap("gender", 1));
		map.add(new ParseMap("count", 2));
		
		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setModel(ModelNames.MODEL_WORD);
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build());
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath);
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		
		return cfg;
	}
	
	public static Query getQuery(BaseRecord user, String groupPath) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return getQuery((long)dir.get(FieldNames.FIELD_ID), (long)dir.get(FieldNames.FIELD_ORGANIZATION_ID));
	}
	public static Query getQuery(long groupId, long organizationId) {
		Query lq = QueryUtil.createQuery(ModelNames.MODEL_WORD, FieldNames.FIELD_GROUP_ID, groupId);
		lq.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		return lq;
	}
	
	public static int cleanupWords(long groupId, long organizationId) {
		Query lq = getQuery(groupId, organizationId);
		int deleted = 0;
		try {
			deleted = IOSystem.getActiveContext().getWriter().delete(lq);
		} catch (WriterException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return deleted;
	}
	
	public static int countCleanupWords(BaseRecord user, String groupPath, boolean resetCountryInfo) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query lq = getQuery((long)dir.get(FieldNames.FIELD_ID), (long)user.get(FieldNames.FIELD_ORGANIZATION_ID));
		
		int count = IOSystem.getActiveContext().getAccessPoint().count(user, lq);
		if(count > 0 && resetCountryInfo) {
			logger.info("Cleaning up " + count + " records in " + groupPath);
			cleanupWords(dir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID));
			count = 0;
		}
		return count;
	}
	
	public static int importFile(ParseConfiguration cfg) {
		
		long start = System.currentTimeMillis();
		List<BaseRecord> recs = GenericParser.parseFile(cfg, new DataParseWriter());
		long stop = System.currentTimeMillis();
		logger.info("Parsed " + recs.size() + " from " + cfg.getFilePath() + " in " + (stop - start) + "ms");
		
		return recs.size();
	}

	public static int loadSurnames(BaseRecord user, String groupPath, String basePath, boolean reset) {
		
		logger.info("Load word information into " + groupPath);
		int count = countCleanupWords(user, groupPath, reset);
		if(count == 0) {
			ParseConfiguration cfg = newSurnameParseConfiguration(user, groupPath, basePath, 0);
			count = importFile(cfg);
		}
		else {
			logger.info(count + " records have already been loaded.");
		}
		return count;
	}
	
	public static int loadNames(BaseRecord user, String groupPath, String basePath, boolean reset) {
		
		logger.info("Load word information into " + groupPath);
		int count = countCleanupWords(user, groupPath, reset);
		if(count == 0) {
			ParseConfiguration cfg = newNamesParseConfiguration(user, groupPath, basePath, 0);
			count = importFile(cfg);
		}
		else {
			logger.info(count + " records have already been loaded.");
		}
		return count;
	}
	
	public static int loadOccupations(BaseRecord user, String groupPath, String basePath, boolean reset) {
		
		logger.info("Load word information into " + groupPath);
		int count = countCleanupWords(user, groupPath, reset);
		if(count == 0) {
			ParseConfiguration cfg = newOccupationsParseConfiguration(user, groupPath, basePath, 0);
			count = importFile(cfg);
		}
		else {
			logger.info(count + " records have already been loaded.");
		}
		return count;
	}
		
}
