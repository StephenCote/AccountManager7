package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordValidator;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.ValidationUtil;
import org.junit.Before;
import org.junit.Test;

public class TestValidationRules {
	public static final Logger logger = LogManager.getLogger(TestValidationRules.class);
	
	
	@Before
	public void setup() {
		ModelNames.loadModels();
	}
		
	
	@Test
	public void TestFieldValidation() {

		BaseRecord data = null;
		BaseRecord user = null;
		try {
			data = RecordFactory.newInstance(ModelNames.MODEL_DATA);
			data.set(FieldNames.FIELD_NAME, "   This   ");
			boolean valid = RecordValidator.validate(data);
			assertTrue("Expected data to be valid", valid);
			assertTrue("Expected name value to be trimmed", "This".equals(data.get(FieldNames.FIELD_NAME)));
			
			user = RecordFactory.newInstance(ModelNames.MODEL_USER);
			user.set(FieldNames.FIELD_NAME, "   This   ");

			valid = RecordValidator.validate(user);
			assertFalse("Expected user to be invalid", valid);
			assertTrue("Expected name value to be trimmed", "This".equals(user.get(FieldNames.FIELD_NAME)));
			
			user.set(FieldNames.FIELD_NAME, "   This2  ");
			valid = RecordValidator.validate(user);
			assertTrue("Expected user to be valid", valid);
			assertTrue("Expected name value to be trimmed", "This2".equals(user.get(FieldNames.FIELD_NAME)));

			// boolean valid = ValidationUtil.validateFieldWithRule(data, data.getField(FieldNames.FIELD_NAME), rule);
			// logger.info("Valid: " + valid);
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
	}
	
}
