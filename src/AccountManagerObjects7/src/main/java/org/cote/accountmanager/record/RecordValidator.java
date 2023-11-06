package org.cote.accountmanager.record;

import java.util.ArrayList;
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
		/*
		ModelSchema schema = RecordFactory.getSchema(record.getModel());
		int valid = 0;
		for(String impl : schema.getImplements()) {
			logger.info("Validating " + impl);
			if(validate(operation, RecordFactory.getSchema(impl), record)) {
				valid++;
			}
		}
		return (valid == schema.getImplements().size());
		*/
	}
	
	public static List<ModelValidation> getValidations(ModelSchema schema, BaseRecord record) {
		List<ModelValidation> valids = new ArrayList<>();
		for(String impl : schema.getImplements()) {
			ModelSchema ischema = RecordFactory.getSchema(impl);
			if(ischema.getValidation() != null) {
				valids.add(ischema.getValidation());
			}
		}
		return valids;
	}
	
	private static boolean validate(RecordOperation operation, ModelSchema schema, BaseRecord record) {
		boolean valid = false;
		
		if(IOSystem.getActiveContext() != null && !IOSystem.getActiveContext().isEnforceValidation()) {
			// logger.warn("Validation is not being enforced for model " + record.getModel());
			return true;
		}
		
		if(operation != RecordOperation.CREATE && operation != RecordOperation.UPDATE) {
			return true;
		}
		
		/// logger.info("**** Validating " + record.getModel());
		
		int errors = 0;
		int ruleCount = 0;
		int successCount = 0;
		//ModelValidation schemav = schema.getValidation();
		List<ModelValidation> schemavs = getValidations(schema, record);
		for(FieldType f : record.getFields()) {
			FieldSchema fs = schema.getFieldSchema(f.getName());
			switch(f.getValueType()) {
				case ENUM:
				case STRING:

					String val = f.getValue();
					if(fs.getMaxLength() > 0 && val != null && val.length() > fs.getMaxLength()) {
						logger.error(fs.getName() + " value '" + val + "' exceeds maximum length " + fs.getMaxLength());
						errors++;
						break;
					}
					if(fs.getMinLength() > 0 && (val == null || val.length() < fs.getMinLength())){
						logger.error(fs.getName() + " value '" + val + "' does not meet the minimum length " + fs.getMinLength());
						errors++;
						break;
					}
					break;
				case LONG:
					long lval = f.getValue();
					if(fs.isValidateRange() && (lval < fs.getMinValue() || lval > fs.getMaxValue())) {
						logger.error(fs.getName() + " value " + lval + " is outside value range " + fs.getMinValue() + " - " + fs.getMaxValue());
						errors++;
						break;
					}
					break;
				case INT:
					int ival = f.getValue();
					if(fs.isValidateRange() && (ival < fs.getMinValue() || ival > fs.getMaxValue())) {
						logger.error(fs.getName() + " value " + ival + " is outside value range " + fs.getMinValue() + " - " + fs.getMaxValue());
						errors++;
						break;
					}
					break;

				case DOUBLE:
					long dval = f.getValue();
					if(fs.isValidateRange() && (dval < fs.getMinValue() || dval > fs.getMaxValue())) {
						logger.error(fs.getName() + " value " + dval + " is outside value range " + fs.getMinValue() + " - " + fs.getMaxValue());
						errors++;
						break;
					}

					break;
				default:

					break;
			}
			if(errors == 0) {
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
				if(schemavs.size() > 0) {
					List<ModelRule> srules = schemavs.stream().flatMap(r -> r.getRules().stream()).filter(o -> {
						return o.getFields().contains(f.getName());
	
					}).collect(Collectors.toList());
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
