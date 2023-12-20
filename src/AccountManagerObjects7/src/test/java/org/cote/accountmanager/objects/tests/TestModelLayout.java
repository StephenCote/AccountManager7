package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.junit.Test;

public class TestModelLayout extends BaseTest {

	
	@Test
	public void TestDataModelLayoutChange() {
		/// Invoke
		OrganizationContext testOrgContext = getTestOrganization("/Development/ModelLayout");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		boolean error = false;
		BaseRecord newData = null;
		BaseRecord newTag = null;
		String dataName = "Test Data - " + UUID.randomUUID().toString();
		ParameterList plist = ParameterUtil.newParameterList("name", dataName);
		plist.parameter("path", "~/Data");

		String tagName = "Test Tag - " + UUID.randomUUID().toString();
		ParameterList plist2 = ParameterUtil.newParameterList("name", tagName);
		plist2.parameter("path", "~/Tags");
		
		try {
			newData = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			newData.set(FieldNames.FIELD_BYTE_STORE, "Here is some example data".getBytes());
			newData.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			BaseRecord nrec = ioContext.getAccessPoint().create(testUser1, newData);
			assertNotNull("Record is null", nrec);
			
			newTag = ioContext.getFactory().newInstance(ModelNames.MODEL_TAG, testUser1, null, plist2);
			newTag.set(FieldNames.FIELD_TYPE, ModelNames.MODEL_DATA);
			BaseRecord nrec2 = ioContext.getAccessPoint().create(testUser1, newTag);
			assertNotNull("Record is null", nrec2);
			
			assertTrue("Failed to tag data", ioContext.getMemberUtil().member(testUser1, nrec, nrec2, null, true));
			
		}
		catch(ModelNotFoundException | FactoryException | FieldException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		assertFalse("Error encountered", error);
	}
	
	@Test
	public void TestResourceHierarchy() {
		logger.info("Test loading a model that inherits a dependency in a psuedo-package location");

		/// Invoke
		OrganizationContext testOrgContext = getTestOrganization("/Development/ModelLayout");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		/*
		logger.info("Releasing custom schema");
		RecordFactory.releaseCustomSchema("layout");
		// CacheUtil.clearCache();
		
		logger.info("Recreating custom schema");
		*/
		
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("layout", "layout");
		assertNotNull("Schema is null", ms);
		logger.info(ms.getInherits().stream().collect(Collectors.joining(", ")));
		/*
		ModelSchema gs = RecordFactory.getSchema(ModelNames.MODEL_PERSON);
		logger.info(gs.getInherits().stream().collect(Collectors.joining(", ")));
		*/
		//logger.info(ioContext.getDbUtil().generateSchema(ms));
		
		boolean error = false;
		BaseRecord newLayout = null;
		String testName = "Test Layout - " + UUID.randomUUID().toString();
		try {
			newLayout = ioContext.getFactory().newInstance("layout", testUser1, null, null);
			AttributeUtil.addAttribute(newLayout, "Attribute 1", "Example attribute");
			assertNotNull("Object is null", newLayout);
			newLayout.set(FieldNames.FIELD_NAME, testName);
			newLayout.set("genericString", "Example string");
			logger.info(newLayout.toString());
			
			BaseRecord nrec = ioContext.getAccessPoint().create(testUser1, newLayout);
			assertNotNull("Record is null", nrec);
		}
		catch(ModelNotFoundException | FactoryException | FieldException | ValueException | ModelException e) {
			logger.error(e);
			e.printStackTrace();
			error = true;
		}
		assertFalse("Error encountered", error);
		
	}
	
}
