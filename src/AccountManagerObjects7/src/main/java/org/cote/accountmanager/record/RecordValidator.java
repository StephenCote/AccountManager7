package org.cote.accountmanager.record;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelRule;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.ModelValidation;
import org.cote.accountmanager.util.ValidationUtil;

public class RecordValidator {
	public static final Logger logger = LogManager.getLogger(RecordValidator.class);
	
	public static boolean validate(BaseRecord record) {
		return validate(RecordOperation.CREATE, record);
	}
	public static boolean validate(RecordOperation operation, BaseRecord record) {
		return validate(operation, RecordFactory.getSchema(record.getModel()), record);
	}
	
	public static boolean validate(RecordOperation operation, ModelSchema schema, BaseRecord record) {
		boolean valid = false;
		
		if(IOSystem.getActiveContext() != null && !IOSystem.getActiveContext().isEnforceValidation()) {
			// logger.warn("Validation is not being enforced for model " + record.getModel());
			return true;
		}
		
		if(operation != RecordOperation.CREATE && operation != RecordOperation.UPDATE) {
			return true;
		}
		
		int errors = 0;
		int ruleCount = 0;
		int successCount = 0;
		ModelValidation schemav = schema.getValidation();
		
		for(FieldType f : record.getFields()) {
			FieldSchema fs = schema.getFieldSchema(f.getName());
			switch(f.getValueType()) {
				case ENUM:
				case STRING:
					for(String r : fs.getRules()) {
						BaseRecord rule = ValidationUtil.getRule(r);
						ruleCount++;
						if(rule == null) {
							logger.error("Failed to load rule: " + r);
							errors++;
						}
						else {
							if(ValidationUtil.validateFieldWithRule(record, f, rule)) {
								successCount++;
							}
						}
					}
					if(schemav != null) {
						List<ModelRule> srules = schemav.getRules().stream().filter(o -> {
							return o.getFields().contains(f.getName());

						}).collect(Collectors.toList());
						// logger.info("Scanning model level rules: " + srules.size());
						for(ModelRule mr : srules) {
							for(String r: mr.getRules()) {
								BaseRecord rule = ValidationUtil.getRule(r);
								ruleCount++;
								if(rule == null) {
									logger.error("Failed to load rule: " + r);
									errors++;
								}
								else {
									if(ValidationUtil.validateFieldWithRule(record, f, rule)) {
										successCount++;
									}
								}
							}
						}
					}
					String val = f.getValue();
					if(fs.getMaxLength() > 0 && val != null && val.length() > fs.getMaxLength()) {
						logger.error("Value '" + val + "' exceeds maximum length " + fs.getMaxLength());
						errors++;
					}
					if(fs.getMinLength() > 0 && (val == null || val.length() < fs.getMinLength())){
						logger.error("Value '" + val + "' does not meet the minimum length " + fs.getMinLength());
						errors++;
					}
					break;
				default:
					break;
			}
		}
		logger.debug("Rule Count: " + ruleCount);
		logger.debug("Success Count: " + successCount);
		logger.debug("Error Count: " + errors);
		valid = (errors == 0 && ruleCount == successCount);
		return valid;
		
	}
	
	/*
	
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
	*/
	
	
}
