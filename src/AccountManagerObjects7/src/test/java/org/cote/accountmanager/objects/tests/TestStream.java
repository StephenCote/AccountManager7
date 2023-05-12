package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.SystemPermissionEnumType;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

import io.jsonwebtoken.Claims;

public class TestStream extends BaseTest {

	private BaseRecord newSegment(BaseRecord stream, byte[] data) {
		List<BaseRecord> segs = stream.get(FieldNames.FIELD_SEGMENTS);
		BaseRecord seg = null;
		try {
			seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
			seg.set(FieldNames.FIELD_STREAM, data);
			segs.add(seg);
		}
		catch(ValueException | FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		return seg;
	}

	@Test
	public void TestCreateStream() {
		logger.info("Test Streaming");

		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		String dataName = "Demo stream " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/Data/Streams");
		plist.parameter("name", dataName);
		BaseRecord data = null;
		BaseRecord odata = null;
		
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, testUser5, null, plist);
			data.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			newSegment(data, "This is some example data".getBytes());
			assertNotNull("Data is null", data);
			data = ioContext.getAccessPoint().create(testUser5, data);
			logger.info(data.toFullString());
			
			BaseRecord idata = ioContext.getAccessPoint().findById(testUser5, ModelNames.MODEL_STREAM, data.get(FieldNames.FIELD_ID));
			assertNotNull("Data is null", idata);
			
			// logger.info(data.toFullString());

		} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException    e) {
			logger.error(e);
			e.printStackTrace();
		}
		
		
	}
	
	
}
