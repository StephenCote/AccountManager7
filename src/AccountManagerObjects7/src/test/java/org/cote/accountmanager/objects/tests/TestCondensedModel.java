package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.Random;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.security.VaultService;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestCondensedModel extends BaseTest {

	@Test
	public void TestLoadCondensedModel() {
		logger.info("Test condensed model");
		
		/// RecordFactory.releaseCustomSchema("condensed");
		ModelSchema ms = RecordFactory.getCustomSchemaFromResource("condensed", "condensed");
		assertNotNull("Schema is null", ms);
		ModelNames.loadCustomModels();

		OrganizationContext testOrgContext = getTestOrganization("/Development/Condenser");
		
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord group = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Condensed", "DATA", testOrgContext.getOrganizationId());
		String dataName = "Condenser Test " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/Condensed");
		plist.parameter("name", dataName);
		BaseRecord data = null;
		
		try {
			data = ioContext.getFactory().newInstance("condensed", testUser1, null, plist);
			data.set("condensedName", "Condensed Name " + UUID.randomUUID().toString());
			data.set("condensedVal", (new Random()).nextInt());
			ioContext.getAccessPoint().create(testUser1, data);
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException  e) {
			logger.error(e);
		}

		String ser = JSONUtil.exportObject(data, RecordSerializerConfig.getCondensedUnfilteredModule(), false, true);
		logger.info(ser);
		BaseRecord data2 = JSONUtil.importObject(ser, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		assertNotNull("Data is null", data2);
		logger.info(data2.toFullString());
		
		ioContext.getReader().populate(testUser1, 3);
		logger.info(JSONUtil.exportObject(testUser1, RecordSerializerConfig.getCondensedUnfilteredModule(), false, true));
		
	}
}
