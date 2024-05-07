package org.cote.accountmanager.parsers.data;

import java.util.List;

import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.ColorUtil;
import org.cote.accountmanager.parsers.IParseInterceptor;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldSchema;

public class HSLInterceptor implements IParseInterceptor {
	public static final Logger logger = LogManager.getLogger(HSLInterceptor.class);
	
	@Override
	public boolean filterRow(ParseConfiguration cfg, CSVRecord record) {
		return false;
	}

	@Override
	public String filterField(ParseConfiguration cfg, CSVRecord record, ParseMap map, BaseRecord rec, FieldSchema fieldSchema, FieldType fieldType, String baseVal) {
		String outVal = baseVal;
		if(outVal != null && outVal.length() > 1) {
			int red = rec.get("red");
			int green = rec.get("green");
			int blue = Integer.parseInt(outVal);

			double[] hsl = ColorUtil.getHSL(red, green, blue);
			rec.setValue("hue", hsl[0]);
			rec.setValue("saturation", hsl[1]);
			rec.setValue("lightness", hsl[2]);
		}
		return outVal;
	}

	@Override
	public void filterParent(ParseConfiguration cfg, List<BaseRecord> parents, BaseRecord rec) {

	}

}
