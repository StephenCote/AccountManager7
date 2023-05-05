package org.cote.accountmanager.record;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ModelValidationException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public class RecordValidator {
	public static final Logger logger = LogManager.getLogger(RecordValidator.class);
	
	public static void validateField(RecordOperation operation, RecordIO io, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) {
		
		// logger.info("Validating " + field.getName());
		
	}
	
	private static boolean canDo(RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) {
		boolean outBool = false;
		switch(operation) {
			case CREATE:
				outBool = canCreate(operation, lmodel, model, lfield, field);
				break;
			default:
				logger.error("Unhandled operation: " + operation.toString());
				break;
		}
		return outBool;
	}

	private static void validate(RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelValidationException {
		if(!canDo(operation, lmodel, model, lfield, field)) {
			throw new ModelValidationException(String.format(ModelValidationException.VALIDATION_ERROR, model.getModel(), field.getName()));
		}
	}

	
	private static boolean canCreate(RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) {
		boolean outBool = false;
		if(!lfield.isReadOnly() || operation.equals(RecordOperation.CREATE)) {
			outBool = true;
		}
		return outBool;
	}
	
	private static void validateCreate(RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelValidationException {
		if(!canCreate(operation, lmodel, model, lfield, field)) {
			throw new ModelValidationException(String.format(ModelValidationException.FIELD_READ_ONLY, model.getModel(), field.getName()));
		}
	}
	
	
	
}
