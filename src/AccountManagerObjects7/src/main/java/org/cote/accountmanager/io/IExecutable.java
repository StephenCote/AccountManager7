package org.cote.accountmanager.io;

import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;

public interface IExecutable {
	public boolean execute(BaseRecord rule, BaseRecord record, FieldType field);
}
