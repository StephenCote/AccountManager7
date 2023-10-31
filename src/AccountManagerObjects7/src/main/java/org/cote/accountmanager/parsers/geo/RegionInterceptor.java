package org.cote.accountmanager.parsers.geo;

import java.util.List;
import java.util.Optional;

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

public class RegionInterceptor implements IParseInterceptor {
	public static final Logger logger = LogManager.getLogger(RegionInterceptor.class);
	
	@Override
	public boolean filterRow(ParseConfiguration cfg, CSVRecord record) {
		String code = record.get(6) + "." + record.get(7);
		return (!code.equals("P.PPL"));
	}

	@Override
	public String filterField(ParseConfiguration cfg, CSVRecord record, ParseMap map, FieldSchema fieldSchema, FieldType fieldType, String baseVal) {
		String outVal = baseVal;
		if(map.getLink() != null) {
			outVal = baseVal + "." + record.get(map.getLink().getColumnIndex());
		}
		return outVal;
	}

	@Override
	public void filterParent(ParseConfiguration cfg, List<BaseRecord> parents, BaseRecord rec) {
		// TODO Auto-generated method stub
		String mapF = rec.get(cfg.getMapField());
		try {

			Optional<BaseRecord> orec = parents.stream().filter(p -> mapF.equals(p.get(cfg.getParentMapField()))).findFirst();
			if(orec.isPresent()) {
				BaseRecord prec = orec.get();
				rec.set(FieldNames.FIELD_PARENT_ID, prec.get(FieldNames.FIELD_ID));
			}
		} catch (ArrayIndexOutOfBoundsException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
		
	}

}
