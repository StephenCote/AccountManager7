package org.cote.accountmanager.provider;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public interface IProvider {

	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model)  throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException;
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field)  throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException;
}
