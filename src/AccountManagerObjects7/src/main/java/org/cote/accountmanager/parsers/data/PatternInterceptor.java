package org.cote.accountmanager.parsers.data;

import java.io.File;
import java.util.List;

import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.parsers.IParseInterceptor;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ContentTypeUtil;
import org.cote.accountmanager.util.StreamUtil;

public class PatternInterceptor implements IParseInterceptor {
	public static final Logger logger = LogManager.getLogger(PatternInterceptor.class);
	
	@Override
	public boolean filterRow(ParseConfiguration cfg, CSVRecord record) {
		return false;
	}

	@Override
	public String filterField(ParseConfiguration cfg, CSVRecord record, ParseMap map, BaseRecord rec, FieldSchema fieldSchema, FieldType fieldType, String baseVal) {
		String outVal = null;;
		if(baseVal != null && baseVal.length() > 1) {
			String path = cfg.getFilePath().substring(0, cfg.getFilePath().lastIndexOf('/') + 1) + baseVal;
			File f = new File(path);
			if(f.exists()) {
				try {
					byte[] bytes = StreamUtil.fileHandleToBytes(f);
					//fieldType.setValue(bytes);
					rec.set(FieldNames.FIELD_CONTENT_TYPE, ContentTypeUtil.getTypeFromExtension(path));
					ByteModelUtil.setValue(rec, fieldType.getName(), bytes);
				} catch (ValueException | FieldException | ModelNotFoundException e) {
					logger.error(e);
				}
			}
		}
		return outVal;
	}

	@Override
	public void filterParent(ParseConfiguration cfg, List<BaseRecord> parents, BaseRecord rec) {

	}

}
