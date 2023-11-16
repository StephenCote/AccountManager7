package org.cote.accountmanager.parsers;

import java.util.List;

import org.apache.commons.csv.CSVRecord;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldSchema;

public interface IParseInterceptor {
	public boolean filterRow(ParseConfiguration cfg, CSVRecord record);
	public String filterField(ParseConfiguration cfg, CSVRecord record, ParseMap map, BaseRecord rec, FieldSchema fieldSchema, FieldType fieldType, String baseVal);
	public void filterParent(ParseConfiguration cfg, List<BaseRecord> parents, BaseRecord rec);
}
