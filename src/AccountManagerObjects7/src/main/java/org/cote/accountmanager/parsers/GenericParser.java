package org.cote.accountmanager.parsers;

import java.io.BufferedReader;
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
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;

public class GenericParser {
	public static final Logger logger = LogManager.getLogger(GenericParser.class);
	
	
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
		List<BaseRecord> objs = new ArrayList<>();
		if(csvFormat == null) {
			csvFormat = CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build();
		}
		CSVParser  csvFileParser = null;
		BufferedReader bir = null;
		boolean error = false;
		CSVRecord lastRecord = null;
		ModelSchema ms = RecordFactory.getSchema(model);
		try{
			bir = new BufferedReader(new InputStreamReader(new FileInputStream(path),StandardCharsets.UTF_8));
			csvFileParser = new CSVParser(bir, csvFormat);
			ParameterList plist = null;
			if(groupPath != null) {
				plist = ParameterList.newParameterList("path", groupPath);
			}

			for(CSVRecord record : csvFileParser){
				lastRecord = record;
				BaseRecord obj = IOSystem.getActiveContext().getFactory().newInstance(model, owner, template, plist);
				String fcell = record.get(0);
				if(fcell == null || fcell.length() == 0 || fcell.startsWith("#")) {
					continue;
				}
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
					if(rval != null) {
						switch(ft.getValueType()) {
							case STRING:
								ft.setValue(rval);
								break;
							case INT:
								ft.setValue(Integer.parseInt(rval));
								break;
							case LONG:
								ft.setValue(Long.parseLong(rval));
								break;
							case BOOLEAN:
								ft.setValue(Boolean.parseBoolean(rval));
								break;
							case LIST:
								if(fs.getBaseType().equals("string")) {
									ft.setValue(Arrays.asList(rval.split(",")));
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
				
				if(maxCount > 0 && objs.size() >= maxCount) {
					break;
				}
			}
		}
		catch(IndexOutOfBoundsException | NumberFormatException | IOException | FactoryException | ValueException  e){
			logger.error(e.getMessage());
			if(lastRecord != null) {
				logger.error(lastRecord.toString());
			}
			e.printStackTrace();
			error = true;

		}
		if(error) {
			return new ArrayList<>();
		}
		return objs;
	}
	/*
	public static List<BaseRecord> parseFile(BaseRecord owner, String model, String[] fields, String groupPath, String path, int maxCount){	
		List<BaseRecord> objs = new ArrayList<>();

		CSVFormat csvFormat = CSVFormat.Builder.create().setDelimiter(',').setAllowMissingColumnNames(true).setQuote(null).setTrim(true).build();
		CSVParser  csvFileParser = null;
		BufferedReader bir = null;
		boolean error = false;
		try{
			bir = new BufferedReader(new InputStreamReader(new FileInputStream(path),StandardCharsets.UTF_8));
			csvFileParser = new CSVParser(bir, csvFormat);
			ParameterList plist = null;
			if(groupPath != null) {
				plist = ParameterList.newParameterList("path", groupPath);
			}

			for(CSVRecord record : csvFileParser){
				BaseRecord obj = IOSystem.getActiveContext().getFactory().newInstance(model, owner, null, plist);
				String fcell = record.get(0);
				if(fcell == null || fcell.length() == 0 || fcell.startsWith("#")) {
					continue;
				}
				for(int i = 0; i < fields.length; i++) {

					if(fields[i] == null) {
						continue;
					}
					FieldType ft = obj.getField(fields[i]);
					String rval = record.get(i);
					if(rval != null) {
						switch(ft.getValueType()) {
							case STRING:
								ft.setValue(rval);
								break;
							case INT:
								ft.setValue(Integer.parseInt(rval));
								break;
							case LONG:
								ft.setValue(Long.parseLong(rval));
								break;
							case BOOLEAN:
								ft.setValue(Boolean.parseBoolean(rval));
								break;

							default:
								logger.warn("Unhandled value type: " + ft.getValueType().toString() + " for " + fields[i]);
								break;
						}
					}
				}
				

				objs.add(obj);
				
				if(maxCount > 0 && objs.size() >= maxCount) {
					break;
				}
			}
		}
		catch(StringIndexOutOfBoundsException | IOException | FactoryException | ValueException  e){
			logger.error(e.getMessage());
			e.printStackTrace();
			error = true;

		}
		if(error) {
			return new ArrayList<>();
		}
		return objs;
	}
	*/
}

