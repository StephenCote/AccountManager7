package org.cote.accountmanager.objects.tests;


import static org.junit.Assert.assertNotNull;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

/// DEPRECATED with LooseBaseModel in place
///
public class TestValueType {
	
	public static final Logger logger = LogManager.getLogger(TestValueType.class);
	

	@Test
	public void TestValueType() {
		BaseRecord org = null;
		try {
			org = RecordFactory.model(ModelNames.MODEL_ORGANIZATION).newInstance();
			assertNotNull("Org is null", org);

			FieldType name = org.getField(FieldNames.FIELD_NAME);
			assertNotNull("Name field is null", name);

			name.setValue("Test organization");
			
			FieldType id = org.getField("id");
			assertNotNull("Id field is null", id);
			id.setValue(12345L);

		}
		catch(ValueException | FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		String ser = JSONUtil.exportObject(org, RecordSerializerConfig.getUnfilteredModule());
		logger.info(ser);
		
		BaseRecord org2 = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Imported model was null", org2);
		

	}

}
