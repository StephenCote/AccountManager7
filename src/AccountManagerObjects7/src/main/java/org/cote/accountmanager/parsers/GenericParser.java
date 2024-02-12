package org.cote.accountmanager.parsers;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public class GenericParser {
	public static final Logger logger = LogManager.getLogger(GenericParser.class);
	
	public static final Pattern numberPattern = Pattern.compile("^[\\d+\\-]+$");
	public static final Pattern floatPattern = Pattern.compile("^[\\d\\.\\-]+$");
	public static List<BaseRecord> parseFile(BaseRecord owner, String model, String[] fields, String groupPath, String path){
		return parseFile(owner, model, fields, groupPath, path, 0);
	}
	
	public static List<BaseRecord> parseFile(BaseRecord owner, String model, String[] fields, String groupPath, String path, int maxCount){
		List<ParseMap> map = new ArrayList<>();
		for(int i = 0; i < fields.length; i++) {
			ParseMap m = new ParseMap();
			m.setColumnIndex(i);
			m.setFieldName(fields[i]);
		}
		return parseFile(owner, model, map.toArray(new ParseMap[0]), groupPath, path, maxCount);
	}
	public static List<BaseRecord> parseFile(BaseRecord owner, String model, ParseMap[] fields, String groupPath, String path, int maxCount){
		return parseFile(owner, model, fields, groupPath, path, maxCount, null, null);
	}
	public static List<BaseRecord> parseFile(BaseRecord owner, String model, ParseMap[] fields, String groupPath, String path, int maxCount, BaseRecord template, CSVFormat csvFormat){
		ParseConfiguration cfg = new ParseConfiguration();
		cfg.setCsvFormat(csvFormat);
		cfg.setOwner(owner);
		cfg.setModel(model);
		cfg.setFields(fields);
		cfg.setGroupPath(groupPath);
		cfg.setFilePath(path);
		cfg.setMaxCount(maxCount);
		cfg.setTemplate(template);
		return parseFile(cfg);
	}
	public static List<BaseRecord> parseFile(ParseConfiguration cfg){
		return parseFile(cfg, null);
	}
	public static List<BaseRecord> parseFile(ParseConfiguration cfg, IParseWriter writer){
		List<BaseRecord> objs = new ArrayList<>();
		CSVFormat csvFormat = cfg.getCsvFormat();
		String model = cfg.getModel();
		ParseMap[] fields = cfg.getFields();
		String groupPath = cfg.getGroupPath();
		BaseRecord owner = cfg.getOwner();
		int maxCount = cfg.getMaxCount();
		
		if(csvFormat == null) {
			csvFormat = CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build();
		}
		CSVParser  csvFileParser = null;
		BufferedReader bir = null;
		boolean error = false;
		CSVRecord lastRecord = null;
		ModelSchema ms = RecordFactory.getSchema(model);
		long start = System.currentTimeMillis();
		
		try{
			bir = new BufferedReader(new InputStreamReader(new FileInputStream(cfg.getFilePath()),StandardCharsets.UTF_8));
			csvFileParser = new CSVParser(bir, csvFormat);
			ParameterList plist = null;
			if(groupPath != null) {
				plist = ParameterList.newParameterList("path", groupPath);
			}
			int batchSize = 0;
			int totalSize = 0;
			if(writer != null) {
				batchSize = writer.getBatchSize();
			}
			for(CSVRecord record : csvFileParser){
				lastRecord = record;
				String fcell = record.get(0);
				if(fcell == null || fcell.length() == 0 || fcell.startsWith("#")) {
					continue;
				}
				if(cfg.getInterceptor() != null && cfg.getInterceptor().filterRow(cfg, record)) {
					continue;
				}
				BaseRecord obj = IOSystem.getActiveContext().getFactory().newInstance(model, owner, cfg.getTemplate(), plist);
				for(int i = 0; i < fields.length; i++) {
					if(fields[i] == null) {
						continue;
					}
					ParseMap field = fields[i];
					
					FieldType ft = obj.getField(field.getFieldName());
					FieldSchema fs = ms.getFieldSchema(ft.getName());
					String rval = null;
					if(field.getColumnIndex() >= 0) {
						rval = record.get(field.getColumnIndex());
					}
					else if(field.getColumnName() != null) {
						rval = record.get(field.getColumnName());
					}
					else {
						rval = record.get(i);
					}
					
					if(cfg.getInterceptor() != null) {
						rval = cfg.getInterceptor().filterField(cfg, record, field, obj, fs, ft, rval);
					}
					if(field.getInterceptor() != null) {
						rval = field.getInterceptor().filterField(cfg, record, field, obj, fs, ft, rval);
					}
					
					if(rval != null) {
						switch(ft.getValueType()) {
							case BLOB:
								/// Let blob be handled by any interceptor
								break;
							case STRING:
								ft.setValue(rval);
								break;
							case INT:
								if(rval.length() > 0 && numberPattern.matcher(rval).find()) {
									ft.setValue(Integer.parseInt(rval));
								}
								break;
							case LONG:
								if(rval.length() > 0 && numberPattern.matcher(rval).find()) {
									ft.setValue(Long.parseLong(rval));
								}
								break;
							case BOOLEAN:
								if(rval.length() > 0) {
									ft.setValue(Boolean.parseBoolean(rval));
								}
								break;
							case DOUBLE:
								if(rval.length() > 0 && floatPattern.matcher(rval).find()) {
									ft.setValue(Double.parseDouble(rval));
								}
								break;
							case LIST:
								if(fs.getBaseType().equals("string")) {
									 if(rval.length() > 0) {
										 ft.setValue(Arrays.asList(rval.split(",")));
									 }
									break;
								}
								else {
									logger.error("Unhandled list base type: " + fs.getBaseType());
								}
								break;

							default:
								logger.warn("Unhandled value type: " + ft.getValueType().toString() + " for " + fields[i]);
								break;
						}
					}
				}
				

				objs.add(obj);
				if(batchSize > 0 && objs.size() >= batchSize) {
					// logger.info("Batch parse " + objs.size() + " in " + (System.currentTimeMillis() - start) + "ms");
					start = System.currentTimeMillis();
					writer.write(cfg, objs);
					objs.clear();
				}
				if(maxCount > 0 && totalSize >= maxCount) {
					break;
				}
				totalSize++;
			}
			if(batchSize > 0 && objs.size() > 0) {
				// logger.info("Batch parse " + objs.size() + " in " + (System.currentTimeMillis() - start) + "ms");
				writer.write(cfg, objs);
			}

		}
		catch(UncheckedIOException e1) {
			logger.error(e1);
			logger.error("Check the parser configuration.  Some datasets that use double apostrophes for quotes can trip up the settings or cause other records to be skipped");
			error = true;
		}
		catch(IndexOutOfBoundsException | NumberFormatException | IOException | FactoryException | ValueException  e){
			logger.error(e.getMessage());
			if(lastRecord != null) {
				logger.error(lastRecord.toString());
			}
			error = true;
		}
		if(error) {
			return new ArrayList<>();
		}
		return objs;
	}
	
}

