package org.cote.accountmanager.parsers.wordnet;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
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
import org.cote.accountmanager.parsers.IParseWriter;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.parsers.data.DataParseWriter;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;

public class WordNetParser {
	public static final Logger logger = LogManager.getLogger(WordNetParser.class);
	
	public static final String verbWordType = "v";
	public static final String nounWordType = "n";
	public static final String adverbWordType = "r";
	public static final String adjectiveWordType = "s";
	
	public static ParseConfiguration newWordNetParseConfiguration(BaseRecord user, String groupPath, String basePath, int maxLines) {
		logger.info("New WordNet Parse Configuration");
		
		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setModel(ModelNames.MODEL_WORD_NET);
		cfg.setCsvFormat(CSVFormat.Builder.create().setDelimiter(' ').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build());
		cfg.setFields(new ParseMap[0]);
		cfg.setFilePath(basePath);
		cfg.setGroupPath(groupPath);
		cfg.setMaxCount(maxLines);
		cfg.setOwner(user);

		return cfg;
	}
	
	
	public static Query getQuery(BaseRecord user, String type, String groupPath) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		return getQuery(type, (long)dir.get(FieldNames.FIELD_ID), (long)dir.get(FieldNames.FIELD_ORGANIZATION_ID));
	}
	public static Query getQuery(String type, long groupId, long organizationId) {
		Query lq = QueryUtil.createQuery(ModelNames.MODEL_WORD_NET, FieldNames.FIELD_GROUP_ID, groupId);
		lq.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		if(type != null) {
			lq.field(FieldNames.FIELD_TYPE, type);	
		}
		return lq;
	}
	
	public static int cleanupWords(String type, long groupId, long organizationId) {
		Query lq = getQuery(type, groupId, organizationId);
		int deleted = 0;
		try {
			deleted = IOSystem.getActiveContext().getWriter().delete(lq);
		} catch (WriterException e) {
			logger.error(e);
		}
		return deleted;
	}
	
	public static int countCleanupWords(BaseRecord user, String groupPath, String type, boolean reset) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query lq = getQuery(type, (long)dir.get(FieldNames.FIELD_ID), (long)user.get(FieldNames.FIELD_ORGANIZATION_ID));
		
		int count = IOSystem.getActiveContext().getAccessPoint().count(user, lq);
		if(count > 0 && reset) {
			logger.info("Cleaning up " + count + " records in " + groupPath);
			cleanupWords(type, dir.get(FieldNames.FIELD_ID), user.get(FieldNames.FIELD_ORGANIZATION_ID));
			count = 0;
		}
		return count;
	}
	
	public static int importFile(ParseConfiguration cfg) {
		
		long start = System.currentTimeMillis();
		/// TODO: Revise parser to only use the writer and return the total write count
		List<BaseRecord> recs = parseWNDataFile(cfg, new DataParseWriter());
		long stop = System.currentTimeMillis();
		logger.info("Parsed words in " + (stop - start) + "ms");
		
		return recs.size();
	}
	


	private static int loadWords(BaseRecord user, String groupPath, String basePath, String ext, String type, int maxLines, boolean reset) {
		// logger.info("Load words to " + groupPath);
		int count = countCleanupWords(user, groupPath, type, reset);
		if(count == 0) {
			ParseConfiguration cfg = newWordNetParseConfiguration(user, groupPath, basePath + "/data." + ext, maxLines);
			count = importFile(cfg);
		}
		else {
			// logger.info(count + " records have already been loaded.");
		}
		return count;
	}
	
	public static int loadVerbs(BaseRecord user, String groupPath, String basePath, int maxLines,boolean reset) {
		return loadWords(user, groupPath, basePath, "verb", verbWordType, maxLines, reset);
	}

	
	public static int loadNouns(BaseRecord user, String groupPath, String basePath, int maxLines,boolean reset) {
		return loadWords(user, groupPath, basePath, "noun", nounWordType, maxLines, reset);
	}

	
	public static int loadAdjectives(BaseRecord user, String groupPath, String basePath, int maxLines,boolean reset) {
		return loadWords(user, groupPath, basePath, "adj", adjectiveWordType, maxLines, reset);
	}
	
	
	public static int loadAdverbs(BaseRecord user, String groupPath, String basePath, int maxLines,boolean reset) {
		return loadWords(user, groupPath, basePath, "adv", adverbWordType, maxLines, reset);
	}
	
	public static List<BaseRecord> parseWNDataFile(ParseConfiguration cfg, IParseWriter writer){
		List<BaseRecord> words = new ArrayList<>();

		CSVFormat csvFormat = cfg.getCsvFormat();
		if(csvFormat == null) {
			csvFormat = CSVFormat.Builder.create().setDelimiter(' ').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build();
		}
		CSVParser  csvFileParser = null;
		BufferedReader bir = null;
		CSVRecord lastRecord = null;

		boolean error = false;
		try{
			long start = System.currentTimeMillis();
			int batchSize = 0;
			int totalSize = 0;
			if(writer != null) {
				batchSize = writer.getBatchSize();
			}
			
			bir = new BufferedReader(new InputStreamReader(new FileInputStream(cfg.getFilePath()),StandardCharsets.UTF_8));
			csvFileParser = new CSVParser(bir, csvFormat);

			for(CSVRecord record : csvFileParser){
				lastRecord = record;
				String id = record.get(0);
				if(id.matches("^\\d{8}$")){
					
					// logger.info("Size: " + record.size());
					String synset_offset = id;
					String lexFileName = record.get(1);
					String type = record.get(2);
					
					int count = Integer.parseInt(record.get(3), 16);
					String name = record.get(4).replace('_', ' ');
					
					
					int lexId = Integer.parseInt("0" + record.get(5), 16);
					int offset = 6;
					ParameterList plist = ParameterList.newParameterList("path", cfg.getGroupPath());
					plist.parameter(FieldNames.FIELD_NAME, name);
					BaseRecord word = IOSystem.getActiveContext().getFactory().newInstance(cfg.getModel(), cfg.getOwner(), null, plist);
					List<BaseRecord> alts = word.get("alternatives");
					
					
					for(int i = 1; i < count; i++) {
						String altName = record.get(offset).replace('_', ' ');
						int altLexId = Integer.parseInt(record.get(offset + 1), 16);
						BaseRecord wordAlt = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_WORD_NET_ALT);
						wordAlt.set(FieldNames.FIELD_NAME, altName);
						wordAlt.set("lexId", altLexId);
						alts.add(wordAlt);
						offset += 2;
					}
					
					int ptrCount = Integer.parseInt(record.get(offset));

					boolean startDesc = false;
					boolean startExamp = false;
					boolean breakOut = false;
					StringBuilder desc = new StringBuilder();
					StringBuilder examp = new StringBuilder();


					for(int i = offset + 1; i < record.size(); i++) {
						String tmp = record.get(i);
						if(tmp.startsWith("!")) {
							/// Skip adjective lexical link
							continue;
						}
						if(tmp.startsWith("\\")) {
							BaseRecord wordPointer = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_WORD_NET_POINTER);
							wordPointer.set("offset", record.get(i + 1));
							wordPointer.set("pos", record.get(i + 2));
							wordPointer.set("sourceTarget", record.get(i + 3));
							List<BaseRecord> ptrs = word.get("pointers");
							ptrs.add(wordPointer);
							i += 3;
							continue;
						}
						if(tmp.startsWith("\"")) {
							tmp = tmp.substring(1, tmp.length());
							startExamp = true;
							startDesc = false;
						}
						if(startDesc) {
							if(desc.length() > 0) {
								desc.append(" ");
							}
							if(tmp.endsWith(";")) {
								tmp = tmp.substring(0, tmp.length() - 1);
							}
							desc.append(tmp);
						}
						if(startExamp) {
							if(examp.length() > 0) {
								examp.append(" ");
							}
							if(tmp.endsWith("\"") || tmp.endsWith("\";")) {
								if(tmp.lastIndexOf("\"") > 0) {
									tmp = tmp.substring(0, tmp.lastIndexOf("\""));
								}
								else {
									tmp = "";
								}
								breakOut = true;
							}
							examp.append(tmp);
						}
						if(tmp.startsWith("|")) {
							startDesc = true;
						}
						if(breakOut) {
							break;
						}
						
					}

					word.set("definition", desc.toString().trim());
					word.set("example", examp.toString().trim());
					word.set("offset", synset_offset);
					word.set("count", count);
					word.set("lexId", lexId);
					word.set("pointerCount", ptrCount);
					word.set("lfn", lexFileName);
					word.set(FieldNames.FIELD_TYPE, type);
					words.add(word);
					
					if(batchSize > 0 && words.size() >= batchSize) {
						// logger.info("Batch parse " + words.size() + " in " + (System.currentTimeMillis() - start) + "ms");
						start = System.currentTimeMillis();
						writer.write(cfg, words);
						words.clear();
					}
					if(cfg.getMaxCount() > 0 && words.size() >= cfg.getMaxCount()) {
						break;
					}
					totalSize++;
				}
			}
			if(batchSize > 0 && words.size() > 0) {
				// logger.info("Batch parse " + words.size() + " in " + (System.currentTimeMillis() - start) + "ms");
				writer.write(cfg, words);
			}
		}
		catch(StringIndexOutOfBoundsException | IOException | FactoryException | FieldException | ValueException | ModelNotFoundException  e){
			logger.error(e.getMessage());
			if(lastRecord != null) {
				logger.error(lastRecord.toString());
			}
			error = true;

		}
		if(error) {
			return new ArrayList<>();
		}
		return words;
	}
}
