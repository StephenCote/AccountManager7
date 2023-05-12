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
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

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
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
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
			seg.set(FieldNames.FIELD_STREAM_ID, stream.get(FieldNames.FIELD_OBJECT_ID));
			segs.add(seg);
		}
		catch(ValueException | FieldException | ModelNotFoundException e) {
			logger.error(e);
		}
		return seg;
	}
	
	@Test
	public void TestAuthorizeStreamSegment() {
		logger.info("Test Stream Authorization");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Policy");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser5 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser5", testOrgContext.getOrganizationId());
		BaseRecord testUser6 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser6", testOrgContext.getOrganizationId());
		String dataName = "Auth stream " + UUID.randomUUID().toString();
		ParameterList plist = ParameterList.newParameterList("path", "~/Data/Streams");
		plist.parameter("name", dataName);
		BaseRecord data = null;
		BaseRecord odata = null;
		StreamSegmentUtil ssu = new StreamSegmentUtil();
		
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, testUser5, null, plist);
			data.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			//newSegment(data, "1) This is some example data".getBytes());
			data = ioContext.getAccessPoint().create(testUser5, data);
			
			BaseRecord seg1 = ssu.newSegment(data.get(FieldNames.FIELD_OBJECT_ID));
			
			PolicyResponseType prr = ioContext.getAuthorizationUtil().canCreate(testUser5, testUser5, data);
			assertTrue("Expected a permit", prr.getType() == PolicyResponseEnumType.PERMIT);
			
			PolicyResponseType prr2 = ioContext.getAuthorizationUtil().canCreate(testUser5, testUser5, seg1);
			//logger.info(prr2.toFullString());
			
			logger.info("Can testUser6 access testUser5's data (no rights)");
			PolicyResponseType prr3 = ioContext.getAuthorizationUtil().canRead(testUser6, testUser6, data);
			logger.info(prr3.toFullString());
			
			logger.info("Can testUser6 access testUser5's data (w/ access token)");
			String token = TokenService.createAuthorizationToken(testUser5, testUser6, data, new String[] {"/Read"}, UUID.randomUUID().toString(), 5);
			PolicyResponseType prr4 = ioContext.getAuthorizationUtil().canRead(testUser6, testUser6, token, data);
			logger.info(prr4.toFullString());
			
			
		} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException | IndexException  e) {
			logger.error(e);
			e.printStackTrace();
		}
		
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
			newSegment(data, "1) This is some example data".getBytes());
			assertNotNull("Data is null", data);
			data = ioContext.getAccessPoint().create(testUser5, data);
			// logger.info(data.toFullString());
			
			BaseRecord idata = ioContext.getAccessPoint().findById(testUser5, ModelNames.MODEL_STREAM, data.get(FieldNames.FIELD_ID));
			assertNotNull("Data is null", idata);
			
			/// Write a direct segment
			BaseRecord seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
			seg.set(FieldNames.FIELD_STREAM, "\n2) This is some more data to add".getBytes());
			seg.set(FieldNames.FIELD_STREAM_ID, idata.get(FieldNames.FIELD_OBJECT_ID));
			boolean created = ioContext.getRecordUtil().createRecord(seg);
			assertTrue("Expected to create the segment", created);
			
			Query q = QueryUtil.createQuery(ModelNames.MODEL_STREAM_SEGMENT, FieldNames.FIELD_STREAM_ID, idata.get(FieldNames.FIELD_OBJECT_ID));
			q.field(FieldNames.FIELD_START_POSITION, 0L);
			q.field(FieldNames.FIELD_LENGTH, 10L);
			
			QueryResult qr = ioContext.getSearch().find(q);
			assertNotNull("Result is null", qr);
			// logger.info(qr.toFullString());
			assertTrue("Expected 1 result", qr.getTotalCount() == 1);
			BaseRecord seg1 = qr.getResults()[0];
			String txt = new String((byte[])seg1.get(FieldNames.FIELD_STREAM));
			logger.info("Streamed back: " + txt);
			
			StreamSegmentUtil ssu = new StreamSegmentUtil();
			
			byte[] allBytes = ssu.streamToEnd(idata.get(FieldNames.FIELD_OBJECT_ID), 0L, 10L);
			logger.info(new String(allBytes));
			
			
			byte[] overRead = ssu.streamToEnd(idata.get(FieldNames.FIELD_OBJECT_ID), 0L, 100L);
			assertTrue("Expected byte length to match", overRead.length == allBytes.length);
			// logger.info(data.toFullString());

		} catch (NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException | IndexException | ReaderException    e) {
			logger.error(e);
			e.printStackTrace();
		}
		
		
	}
	
	
}
