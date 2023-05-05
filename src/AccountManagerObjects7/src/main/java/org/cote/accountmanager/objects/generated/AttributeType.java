/*
	Generated
	Note: Inheritence isn't used here because the schema supports multiple inheritence
*/
package org.cote.accountmanager.objects.generated;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;

public class AttributeType extends LooseRecord {
	public static final Logger logger = LogManager.getLogger(AttributeType.class);
	public AttributeType(){
		try {
			RecordFactory.newInstance("attribute", this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public AttributeType(BaseRecord rec){
		this.setModel(rec.getModel());
		setFieldList(rec.getFields());
		setFieldMap(rec.getFieldMap());
	}
	public <T> T getValue() {
		return get("value");
	}
	public <T> void setValue(T value) {
		try {
			set("value", value);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	public String getName() {
		return get("name");
	}
	public void setName(String name) {
		try {
			set("name", name);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
}
