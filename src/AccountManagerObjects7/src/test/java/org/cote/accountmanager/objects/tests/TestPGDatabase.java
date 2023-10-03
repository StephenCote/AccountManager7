package org.cote.accountmanager.objects.tests;

import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.AttributeUtil;
import org.junit.Test;

public class TestPGDatabase extends BaseTest {


	

	@Test
	public void TestPGQuery() {
		logger.info("Test PG Query Construct");
		
		logger.info("Have table: " + ioContext.getDbUtil().haveTable(ModelNames.MODEL_ORGANIZATION));

		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		BaseRecord data = null;

		try {
			String dataName = "Data Test - " + UUID.randomUUID().toString();

			BaseRecord temp = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, null);
			temp.set(FieldNames.FIELD_GROUP_PATH, "~/Data");
			temp.set(FieldNames.FIELD_NAME, dataName);
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, temp, null);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			
			AttributeUtil.addAttribute(data, "Demo Attr 1", true);
			AttributeUtil.addAttribute(data, "Demo Attr 2", 1.1);
			
			data = ioContext.getAccessPoint().create(testUser1, data);
			
			BaseRecord idata = ioContext.getAccessPoint().findByObjectId(testUser1, ModelNames.MODEL_DATA, data.get(FieldNames.FIELD_OBJECT_ID));
			
			logger.info(idata.toFullString());
		}
		catch(ValueException | FieldException | ModelNotFoundException | FactoryException | ModelException e) {
			logger.error(e);
		}
	
	
	
	}
	
}
