package org.cote.accountmanager.parsers.data;

import java.util.List;

import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.parsers.IParseInterceptor;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldSchema;

public class LowercaseInterceptor implements IParseInterceptor {
	public static final Logger logger = LogManager.getLogger(LowercaseInterceptor.class);
	
	@Override
	public boolean filterRow(ParseConfiguration cfg, CSVRecord record) {
		return false;
	}

	@Override
	public String filterField(ParseConfiguration cfg, CSVRecord record, ParseMap map, BaseRecord rec, FieldSchema fieldSchema, FieldType fieldType, String baseVal) {
		String outVal = baseVal;
		if(outVal != null && outVal.length() > 1) {
			outVal = outVal.toLowerCase();
		}
		return outVal;
	}

	@Override
	public void filterParent(ParseConfiguration cfg, List<BaseRecord> parents, BaseRecord rec) {

	}

}
