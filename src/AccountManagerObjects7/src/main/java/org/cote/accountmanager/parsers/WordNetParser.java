package org.cote.accountmanager.parsers;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class WordNetParser {
	public static final Logger logger = LogManager.getLogger(WordNetParser.class);
	
	public static List<BaseRecord> parseWNDataFile(BaseRecord owner, String groupPath, String path){
		return parseWNDataFile(owner, groupPath, path, 0);
	}
	
	public static List<BaseRecord> parseWNDataFile(BaseRecord owner, String groupPath, String path, int maxCount){
		List<BaseRecord> words = new ArrayList<>();

		CSVFormat csvFormat = CSVFormat.Builder.create().setDelimiter(' ').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build();
		CSVParser  csvFileParser = null;
		BufferedReader bir = null;
		CSVRecord lastRecord = null;

		boolean error = false;
		try{
			bir = new BufferedReader(new InputStreamReader(new FileInputStream(path),StandardCharsets.UTF_8));
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
					ParameterList plist = ParameterList.newParameterList("path", groupPath);
					plist.parameter("name", name);
					BaseRecord word = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_WORD_NET, owner, null, plist);
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
					
					if(maxCount > 0 && words.size() >= maxCount) {
						break;
					}
				}
			}
		}
		catch(StringIndexOutOfBoundsException | IOException | FactoryException | FieldException | ValueException | ModelNotFoundException  e){
			logger.error(e.getMessage());
			e.printStackTrace();
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
