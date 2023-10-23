package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestFlexValueType extends BaseTest {

	
	@Test
	public void TestFlexDeserialization() {
		BaseRecord model = null;
		try {
			model = RecordFactory.model("flex").newInstance();
			model.setBoolean("flexBool", true);
			model.setString("flexString", "Example string");
			model.setByteArray("flexByte", "Example byte array".getBytes());
			model.setDateTime("flexDate", new Date());
		} catch (FieldException | ModelNotFoundException | ValueException | ModelException e) {
			logger.error(e);
		}
		assertNotNull("Model is null", model);
		String ser = JSONUtil.exportObject(model, RecordSerializerConfig.getUnfilteredModule());
		BaseRecord impMod = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		
		assertNotNull("Import is null", impMod);
		
		/// Now, test converting the flex model - because the individual fields may not be known, it's presently up to the implementer to use any other field to determine the desired format
		/// Conversion should only be necessary for Dates and Blobs
		boolean error = false;
		try {
			impMod.convertField("flexByte", FieldEnumType.BLOB);
			impMod.convertField("flexDate", FieldEnumType.TIMESTAMP);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			error = true;
			logger.error(e);
		}
		
		assertFalse("Didn't expect an error", error);
		
		assertTrue("Expected blob type", impMod.getField("flexByte").getValueType().equals(FieldEnumType.BLOB));
		assertTrue("Expected date type", impMod.getField("flexDate").getValueType().equals(FieldEnumType.TIMESTAMP));
	}
	
	
	@Test
	public void TestFlexValue() {
		BaseRecord model = null;
		try {
			model = RecordFactory.model(ModelNames.MODEL_VALIDATION_RULE).newInstance();
			assertNotNull("Model was null", model);
			
			logger.info(JSONUtil.exportObject(model, RecordSerializerConfig.getUnfilteredModule()));
			
		} catch (Exception e) {
			logger.error(e);
		}
		assertNotNull("Model was null", model);
		
		boolean error = false;
		
		/*
		/// This specific condition is currently invalid
		///
		try {
			/// Setting any kind of value to a flex field will throw an error
			model.set("replacementValue", true);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			error = true;
		}
		assertTrue("Expected an error to be thrown", error);
		*/
		error = false;
		try {
			/// Set a specific data type to overwrite the field
			model.setBoolean("replacementValue", false);
		} catch (Exception e) {
			logger.error(e);
			error = true;
		}
		assertFalse("Did not expect an error to be thrown", error);
		String ser = JSONUtil.exportObject(model, RecordSerializerConfig.getUnfilteredModule());
		logger.info(ser);
		
		BaseRecord impMod = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		
		/// Field's whose value is a FieldType is used to hold a value-driven type
		/// The abstract field can be overridden with the specific setType(Class<?> value) methods on the BaseModel, which will overwrite the abstract field definition
		///
		
		
	}
	
	
}
