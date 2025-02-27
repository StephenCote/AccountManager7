package org.cote.accountmanager.io.file;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_DEFAULT)
//  JsonInclude.Include.NON_NULL
public class IndexEntryValue extends LooseRecord {
	
	public IndexEntryValue() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_INDEX_ENTRY2, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			/// ignore
		}
	}
	
	public IndexEntryValue(BaseRecord rec){
		this.setSchema(rec.getSchema());
		setFieldList(rec.getFields());
		setFieldMap(rec.getFieldMap());
	}
	
	public String getName() {
		if(!hasField(FieldNames.FIELD_NAME)) {
			return null;
		}
		return get(FieldNames.FIELD_NAME);
	}
	public <T> T getValue() {
		if(!hasField(FieldNames.FIELD_NAME)) {
			return null;
		}
		return get(FieldNames.FIELD_VALUE);
	}



	
	
}
