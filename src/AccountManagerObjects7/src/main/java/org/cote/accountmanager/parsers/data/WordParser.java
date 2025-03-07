package org.cote.accountmanager.parsers.data;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.ColorUtil;
import org.cote.accountmanager.parsers.GenericParser;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.JSONUtil;

public class WordParser {

	public static final Logger logger = LogManager.getLogger(WordParser.class);

	public static ParseConfiguration newSurnameParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Surnames Parse Configuration");
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap(FieldNames.FIELD_NAME, 0, new CaseInterceptor()));
		map.add(new ParseMap("rank", 1));
		map.add(new ParseMap("count", 2));
		map.add(new ParseMap("proportion", 4));
		map.add(new ParseMap("pctwhite", 5));
		map.add(new ParseMap("pctblack", 6));
		map.add(new ParseMap("pctapi", 7));
		map.add(new ParseMap("pctaian", 8));
		map.add(new ParseMap("pct2prace", 9));
		map.add(new ParseMap("pcthispanic", 10));

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setSchema(ModelNames.MODEL_CENSUS_WORD);
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(false).setQuote(null).setTrim(true).build());
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath);
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		ParseMap filter = new ParseMap(null, 0);
		filter.setMatchValue(FieldNames.FIELD_NAME);
		filter.setExcludeMatch(true);
		cfg.setFilters(new ParseMap[] {filter});
		cfg.setInterceptor(new DataInterceptor());
		return cfg;
	}
	
	public static ParseConfiguration newOccupationsParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Occupations Parse Configuration");
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap(FieldNames.FIELD_NAME, 4));
		map.add(new ParseMap("class", 2));

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setSchema(ModelNames.MODEL_WORD);
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

	public static ParseConfiguration newWebColorParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Web Color Resource Parse Configuration");
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap(FieldNames.FIELD_NAME, 0));
		map.add(new ParseMap("hex", 1, new LowercaseInterceptor()));
		map.add(new ParseMap(null, 2, new DecimalColorInterceptor()));

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setSchema(ModelNames.MODEL_COLOR);
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(true).setQuote('"').setTrim(true).build());
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath);
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		return cfg;
	}
	
	public static ParseConfiguration newColorParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Color Parse Configuration");
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap("code", 0));
		map.add(new ParseMap(FieldNames.FIELD_NAME, 1));
		map.add(new ParseMap("hex", 2));
		map.add(new ParseMap("red", 3));
		map.add(new ParseMap("green", 4));
		map.add(new ParseMap("blue", 5, new HSLInterceptor()));

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setSchema(ModelNames.MODEL_COLOR);
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(true).setQuote('"').setTrim(true).build());
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath);
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		return cfg;
	}
	
	public static ParseConfiguration newPatternParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Pattern Parse Configuration");
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap(FieldNames.FIELD_NAME, 0));
		map.add(new ParseMap(FieldNames.FIELD_BYTE_STORE, 1, new PatternInterceptor()));
		map.add(new ParseMap("description", 2));

		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setSchema(ModelNames.MODEL_DATA);
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter('|').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build());
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath);
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		return cfg;
	}
	
	public static ParseConfiguration newNamesParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New Names Parse Configuration");
		
		List<ParseMap> map = new ArrayList<>();
		map.add(new ParseMap(FieldNames.FIELD_NAME, 0));
		map.add(new ParseMap(FieldNames.FIELD_GENDER, 1));
		map.add(new ParseMap("count", 2));
		
		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setSchema(ModelNames.MODEL_WORD);
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build());
		cfg.setFields(map.toArray(new ParseMap[0]));
		cfg.setFilePath(basePath);
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);
		
		return cfg;
	}

	
	public static int cleanupWords(String model, long groupId, long organizationId) {
		Query lq = QueryUtil.getGroupQuery(model, null, groupId, organizationId);
		int deleted = 0;
		try {
			deleted = IOSystem.getActiveContext().getWriter().delete(lq);
		} catch (WriterException e) {
			logger.error(e);
		}
		return deleted;
	}
	
	public static int countCleanupWords(BaseRecord user, String model, String groupPath, boolean resetCountryInfo) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query lq = QueryUtil.getGroupQuery(model, null, (long)dir.get(FieldNames.FIELD_ID), (long)user.get(FieldNames.FIELD_ORGANIZATION_ID));
		lq.setCache(false);
		int count = IOSystem.getActiveContext().getAccessPoint().count(user, lq);
		if(count > 0 && resetCountryInfo) {
			logger.info("Cleaning up " + count + " records in " + groupPath);
			cleanupWords(model, dir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID));
			count = 0;
		}
		return count;
	}
	
	public static int importFile(ParseConfiguration cfg) {
		
		List<BaseRecord> recs = GenericParser.parseFile(cfg, new DataParseWriter());
		return recs.size();
	}

	public static int loadSurnames(BaseRecord user, String groupPath, String basePath, boolean reset) {
		
		int count = countCleanupWords(user, ModelNames.MODEL_CENSUS_WORD, groupPath, reset);
		if(count == 0) {
			ParseConfiguration cfg = newSurnameParseConfiguration(user, groupPath, basePath, 0);
			count = importFile(cfg);
		}
		else {
			logger.info(count + " records have already been loaded.");
		}
		return count;
	}

	public static int loadColors(BaseRecord user, String groupPath, String basePath, boolean reset) {
		
		int count = countCleanupWords(user, ModelNames.MODEL_COLOR, groupPath, reset);
		if(count == 0) {
			/// Loading from embedded color resource vs. external CSV
			/*
			ParseConfiguration cfg = newColorParseConfiguration(user, groupPath, basePath, 0);
			count = importFile(cfg);
			*/
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			List<BaseRecord> newCol = new ArrayList<>();
			List<BaseRecord> colTemp = ColorUtil.getDefaultColors();
			// logger.info("Loading " + colTemp.size() + " colors");
			try {
				for(BaseRecord tmp : colTemp) {
					BaseRecord nrec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_COLOR, user, tmp, plist);
					newCol.add(nrec);
				}
				count = IOSystem.getActiveContext().getRecordUtil().createRecords(newCol.toArray(new BaseRecord[0]));
			}
			catch(FactoryException e) {
				logger.error(e);
			}
			
		}
		return count;
	}

	public static int loadPatterns(BaseRecord user, String groupPath, String basePath, boolean reset) {
		
		int count = countCleanupWords(user, ModelNames.MODEL_DATA, groupPath, reset);
		if(count == 0) {
			ParseConfiguration cfg = newPatternParseConfiguration(user, groupPath, basePath, 0);
			count = importFile(cfg);
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}
		return count;
	}
	
	public static int loadNames(BaseRecord user, String groupPath, String basePath, boolean reset) {
		
		// logger.info("Load word information into " + groupPath);
		int count = countCleanupWords(user, ModelNames.MODEL_WORD, groupPath, reset);
		if(count == 0) {
			ParseConfiguration cfg = newNamesParseConfiguration(user, groupPath, basePath, 0);
			count = importFile(cfg);
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}
		return count;
	}
	
	public static int loadOccupations(BaseRecord user, String groupPath, String basePath, boolean reset) {
		
		// logger.info("Load word information into " + groupPath);
		int count = countCleanupWords(user, ModelNames.MODEL_WORD, groupPath, reset);
		if(count == 0) {
			ParseConfiguration cfg = newOccupationsParseConfiguration(user, groupPath, basePath, 0);
			count = importFile(cfg);
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}
		return count;
	}
	
	public static int loadTraits(BaseRecord user, String groupPath, String basePath, boolean reset) {
		
		int count = countCleanupWords(user, ModelNames.MODEL_TRAIT, groupPath, reset);
		if(count == 0) {
			count = IOSystem.getActiveContext().getAccessPoint().create(user, getBulkTraits(user, groupPath, basePath).toArray(new BaseRecord[0]));
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}
		return count;
	}
	
	private static List<BaseRecord> getBulkTraits(BaseRecord owner, String groupPath, String basePath){
		String traitsPath = basePath + "/traits.json";
		File f = new File(traitsPath);
		if(!f.exists()) {
			logger.error(traitsPath + " doesn't exist");
			return new ArrayList<>();
		}
		Map<String, String[]> traits = JSONUtil.getMap(traitsPath, String.class,String[].class);
		
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
		
		return traitrecs;

	}
	private static BaseRecord newTrait(BaseRecord owner, String path, String name, TraitEnumType type, AlignmentEnumType alignment) {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path);
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord data = null;
		try {
			data = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_TRAIT, owner, null, plist);
			data.set(FieldNames.FIELD_TYPE, type);
			data.set(FieldNames.FIELD_ALIGNMENT, alignment);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return data;
	}

		
}
