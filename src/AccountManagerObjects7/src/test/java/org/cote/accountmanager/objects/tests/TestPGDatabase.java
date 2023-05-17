package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.dbcp.cpdsadapter.DriverAdapterCPDS;
import org.apache.commons.dbcp.datasources.SharedPoolDataSource;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.SqlTypeUtil;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.FieldUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;

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

	
	
	/*
	try (Connection con = ioContext.getDbUtil().getDataSource().getConnection()){
		Statement stat = con.createStatement();
		ResultSet rset = stat.executeQuery("SELECT * FROM A7_participation ORDER BY id ASC");
		while(rset.next()) {
			String ctype = rset.getString("participationModel");
			long cid = rset.getLong("participationId");
			String ptype = rset.getString("participantModel");
			long pid = rset.getLong("participantId");
			String etype = rset.getString("effectType");
			long eid = rset.getLong("permissionId");
			
			logger.info(ctype + " " + cid + " " + ptype + " " + pid + " " + etype + " " + eid);


		}
	}
	catch(SQLException e) {
		logger.error(e);
	}
	*/
	
}
