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

public class DataInterceptor implements IParseInterceptor {
	public static final Logger logger = LogManager.getLogger(DataInterceptor.class);
	
	@Override
	public boolean filterRow(ParseConfiguration cfg, CSVRecord record) {
		boolean filter = false;
		if(cfg.getFilters() != null) {
			filter = true;
			for(ParseMap map : cfg.getFilters()) {
				String mval = map.getMatchValue();
				if(mval == null) {
					mval = "";
				}
				boolean match = record.get(map.getColumnIndex()).equals(mval);
				if(!map.isExcludeMatch()) {
					match = !match;
				}
				filter = match;
				// logger.info(mval + " == " + record.get(map.getColumnIndex()) + " != " + map.isExcludeMatch() + " = " + filter);
				if(!filter) {
					break;
				}
				//if(match) {
				//	filter = false;
				//	break;
				//}
			}
		}
		return filter;
	}

	@Override
	public String filterField(ParseConfiguration cfg, CSVRecord record, ParseMap map, FieldSchema fieldSchema, FieldType fieldType, String baseVal) {

		return baseVal;
	}

	@Override
	public void filterParent(ParseConfiguration cfg, List<BaseRecord> parents, BaseRecord rec) {

	}

}
