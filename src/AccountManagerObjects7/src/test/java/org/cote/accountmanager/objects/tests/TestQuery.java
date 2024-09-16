package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.policy.PolicyEvaluator;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestQuery extends BaseTest {

	private String testPath = "~/QueryTest";
	private String testDataName = "Query Data 1";
	
	
	@Test
	public void TestQueryModel() {
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());
		logger.info("Test query model");
		try {

			BaseRecord group1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, testPath, "DATA", orgContext.getOrganizationId());

			BaseRecord data1 = this.getCreateData(testUser1, testDataName, "text/plain", "Demo text 1".getBytes(), testPath,  orgContext.getOrganizationId());

			String tmpStr = """
					{
						"type": "data",
						"order": "ascending"
					}
			""";
			BaseRecord query = mf.newTemplateInstance(ModelNames.MODEL_QUERY, testUser1, tmpStr, null);

			BaseRecord queryField = RecordFactory.newInstance(ModelNames.MODEL_QUERY_FIELD);
			queryField.set(FieldNames.FIELD_NAME, FieldNames.FIELD_NAME);
			queryField.setString(FieldNames.FIELD_VALUE, testDataName);
			queryField.set(FieldNames.FIELD_COMPARATOR, "EQUALS");
			List<BaseRecord> queries = query.get(FieldNames.FIELD_FIELDS);
			queries.add(queryField);
			
			String ser = JSONUtil.exportObject(query, RecordSerializerConfig.getUnfilteredModule());
			
			BaseRecord iquery = JSONUtil.importObject(ser,  LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
			assertNotNull("Imported object is null", iquery);
			
			queries = iquery.get(FieldNames.FIELD_FIELDS);
			BaseRecord iqf = queries.get(0);
			logger.info("Imported query: " + iqf.get(FieldNames.FIELD_NAME) + " " + iqf.get(FieldNames.FIELD_COMPARATOR) + " " + iqf.get(FieldNames.FIELD_VALUE));
		}
		catch(Exception e) {
			
		}
	}
	
	@Test
	public void TestParse() {
		
		String tmpStr = """
		{
			FieldNames.FIELD_NAME: "Demo text 1",
			"groupId": 1
		}
		""";

		String tmpStr2 = """
		{
			"id": 101
		}
		""";
		
		BaseRecord query = null;
		try {
			query = ioContext.getFactory().newInstance(ModelNames.MODEL_QUERY, null, null, null, ioContext.getFactory().template(ModelNames.MODEL_DATA, tmpStr), ioContext.getFactory().template(ModelNames.MODEL_DATA, tmpStr2));
		}
		catch(NullPointerException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		assertNotNull("Query is null", query);

	}

	@Test
	public void TestQueryForNameByGroupId() {
		Factory f = ioContext.getFactory();
		
		BaseRecord testQueryUser1 = f.getCreateUser(orgContext.getAdminUser(), "Test Query User 1", orgContext.getOrganizationId());
		BaseRecord testData = this.getCreateData(testQueryUser1, "Test Query Data 1", "text/plain", "This is the query data test".getBytes(), "~/Query Dataset", orgContext.getOrganizationId());
		assertNotNull("Test data is null", testData);
		Query query = QueryUtil.createQuery(ModelNames.MODEL_DATA);
		query.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, "Test Query Data 1");
		query.field(FieldNames.FIELD_GROUP_ID, ComparatorEnumType.EQUALS, testData.get(FieldNames.FIELD_GROUP_ID));
		assertNotNull("Query is null", query);
		
		QueryResult result = null;
		try {
			result = ioContext.getSearch().find(query);
		} catch (ReaderException e) {
			logger.error(e);
		}
		assertNotNull("Result was null", result);
		
	}
	
	@Test
	public void TestQueryById() {
		Factory f = ioContext.getFactory();
		BaseRecord testQueryUser1 = f.getCreateUser(orgContext.getAdminUser(), "Test Query User 1", orgContext.getOrganizationId());
		BaseRecord testData = this.getCreateData(testQueryUser1, "Test Query Data 1", "text/plain", "This is the query data test".getBytes(), "~/Query Dataset", orgContext.getOrganizationId());
		assertNotNull("Test data is null", testData);
		BaseRecord qt = null;
		try {
			logger.info("Invoke read by id");
			qt = ioContext.getReader().read(ModelNames.MODEL_DATA, (long)testData.get(FieldNames.FIELD_ID));
		} catch (NullPointerException | ReaderException e) {
			logger.error(e);
			
		}
		assertNotNull("Result was null", qt);
		
	}
	
	@Test
	public void TestQueryByObjectId() {
		Factory f = ioContext.getFactory();
		BaseRecord testQueryUser1 = f.getCreateUser(orgContext.getAdminUser(), "Test Query User 1", orgContext.getOrganizationId());
		BaseRecord testData = this.getCreateData(testQueryUser1, "Test Query Data 1", "text/plain", "This is the query data test".getBytes(), "~/Query Dataset", orgContext.getOrganizationId());
		assertNotNull("Test data is null", testData);
		BaseRecord qt = null;
		try {
			logger.info("Invoke read by object id");
			qt = ioContext.getReader().read(ModelNames.MODEL_DATA, (String)testData.get(FieldNames.FIELD_OBJECT_ID));
		} catch (NullPointerException | ReaderException e) {
			logger.error(e);
			
		}
		assertNotNull("Result was null", qt);

	}
	
	@Test
	public void TestQueryByUrn() {
		Factory f = ioContext.getFactory();
		BaseRecord testQueryUser1 = f.getCreateUser(orgContext.getAdminUser(), "Test Query User 1", orgContext.getOrganizationId());
		BaseRecord testData = this.getCreateData(testQueryUser1, "Test Query Data 1", "text/plain", "This is the query data test".getBytes(), "~/Query Dataset", orgContext.getOrganizationId());
		assertNotNull("Test data is null", testData);
		BaseRecord qt = null;
		try {
			logger.info("Invoke read by urn");
			qt = ioContext.getReader().readByUrn(ModelNames.MODEL_DATA, (String)testData.get(FieldNames.FIELD_URN));
		} catch (NullPointerException | ReaderException e) {
			logger.error(e);
			
		}
		assertNotNull("Result was null", qt);

	}
	
	
	
	@Test
	public void TestQueryObjectWrapper() {
		logger.warn("*** Deserialization/Reserialization Test Currently Failing");
		// use loose store, and don't follow foreign keys
		logger.info("Test query object wrapper");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());
		try {
			BaseRecord group1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, testPath, "DATA", orgContext.getOrganizationId());
			BaseRecord data1 = this.getCreateData(testUser1, testDataName, "text/plain", "Demo text 1".getBytes(), testPath,  orgContext.getOrganizationId());
			
			Query q = new Query(ModelNames.MODEL_DATA);
			//q.set(FieldNames.FIELD_TYPE, "data");
			q.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, testDataName);
			q.field(FieldNames.FIELD_GROUP_ID, ComparatorEnumType.EQUALS, data1.get(FieldNames.FIELD_GROUP_ID));
			
			String ser = JSONUtil.exportObject(q, RecordSerializerConfig.getUnfilteredModule());
			// logger.info(ser);
			Query q2 = new Query(JSONUtil.importObject(ser,  LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()));
			assertNotNull("Imported query was null", q2);

			
			String ser2 = JSONUtil.exportObject(q, RecordSerializerConfig.getUnfilteredModule());
			assertTrue("Serialized values do not match", ser2.equals(ser));
			
			/// Create a complex query
			///
			
			Query plex = new Query(ModelNames.MODEL_DATA);
			QueryField plexf = plex.field(null, ComparatorEnumType.GROUP_OR, null);
			QueryField plexf1 = plex.field(null, ComparatorEnumType.GROUP_AND, null, plexf);
			QueryField plexf2 = plex.field(null, ComparatorEnumType.GROUP_AND, null, plexf);
			plex.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, data1.get(FieldNames.FIELD_NAME), plexf1);
			plex.field(FieldNames.FIELD_GROUP_ID, ComparatorEnumType.EQUALS, data1.get(FieldNames.FIELD_GROUP_ID), plexf1);
			plex.field(FieldNames.FIELD_OBJECT_ID, ComparatorEnumType.EQUALS, data1.get(FieldNames.FIELD_OBJECT_ID), plexf2);
			
			String ser3 = JSONUtil.exportObject(plex, RecordSerializerConfig.getUnfilteredModule());
			BaseRecord q30 = JSONUtil.importObject(ser3,  LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
			Query q3 = new Query(q30);
			assertNotNull("Imported query was null", q3);
			List<BaseRecord> queries20 = q30.get(FieldNames.FIELD_FIELDS);
			List<BaseRecord> queries2 = q3.get(FieldNames.FIELD_FIELDS);
			logger.info("Query size: " + queries20.size() + "::" + queries2.size());
			String ser4 = JSONUtil.exportObject(q3, RecordSerializerConfig.getUnfilteredModule());
			assertTrue("Expected serial outputs to match", ser3.equals(ser4));
			// logger.info(q3.key());
		
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	@Test
	public void TestQueryInterface() {
		// use loose store, and don't follow foreign keys
		logger.info("Test query interface");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());

		BaseRecord group1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, testPath, "DATA", orgContext.getOrganizationId());
		BaseRecord data1 = this.getCreateData(testUser1, testDataName, "text/plain", "Demo text 1".getBytes(), testPath,  orgContext.getOrganizationId());		
		Query q = new Query(ModelNames.MODEL_DATA);
		q.setRequest(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_SIZE, FieldNames.FIELD_CONTENT_TYPE});
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.EQUALS, testDataName);
		q.field(FieldNames.FIELD_GROUP_ID, ComparatorEnumType.EQUALS, data1.get(FieldNames.FIELD_GROUP_ID));

		QueryResult res = null;
		try {
			res = ioContext.getSearch().find(q);
			assertNotNull("Expected a valid response object", res);
			assertTrue("Expected success", res.getResponse() == OperationResponseEnumType.SUCCEEDED);
			assertTrue("Expected one result", res.getCount() == 1);
			logger.info(JSONUtil.exportObject(res, RecordSerializerConfig.getUnfilteredModule()));
		} catch (Exception e) {
			logger.error(e);
			
		}
	}
	
	@Test
	public void TestSearchList() {
		// use loose store, and don't follow foreign keys
		logger.info("Test search list");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());
		BaseRecord group1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, testPath, "DATA", orgContext.getOrganizationId());
		String testDataPref = "List Query Data";
		for(int i = 0; i < 25; i++) {
			BaseRecord data1 = this.getCreateData(testUser1, testDataPref + " " + (i + 1), "text/plain", ("Demo text " + (i + 1) + " data").getBytes(), testPath, orgContext.getOrganizationId());
		}
		
		Query q = new Query(testUser1, ModelNames.MODEL_DATA);
		q.setRequest(new String[] {FieldNames.FIELD_NAME, FieldNames.FIELD_SIZE, FieldNames.FIELD_CONTENT_TYPE}, 5L, 10);
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, testDataPref);
		q.field(FieldNames.FIELD_GROUP_ID, ComparatorEnumType.EQUALS, group1.get(FieldNames.FIELD_ID));
		logger.info(q.key());
		
		QueryResult res = null;
		try {
			res = ioContext.getSearch().find(q);
			assertNotNull("Expected a valid response object", res);
			assertTrue("Expected success", res.getResponse() == OperationResponseEnumType.SUCCEEDED);
			assertTrue("Expected ten results, not " + res.getCount(), res.getCount() == 10);
		} catch (Exception e) {
			logger.error(e);
			
		}
	}
	
	
	@Test
	public void TestAuthorizedQuery() {

		logger.info("Test query authorization");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(orgContext.getAdminUser(), "testUser1", orgContext.getOrganizationId());
		BaseRecord testUser2 = mf.getCreateUser(orgContext.getAdminUser(), "testUser2", orgContext.getOrganizationId());
		BaseRecord data1 = this.getCreateData(testUser1, testDataName, "text/plain", "Demo text 1".getBytes(), testPath,  orgContext.getOrganizationId());
		BaseRecord group1 = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, testPath, "DATA", orgContext.getOrganizationId());
		BaseRecord readDataPer = ioContext.getPathUtil().findPath(orgContext.getAdminUser(), ModelNames.MODEL_PERMISSION, "/Read", "DATA", orgContext.getOrganizationId());
		
		ioContext.getMemberUtil().member(testUser1, data1, testUser2, readDataPer, false);
		boolean setMember = ioContext.getMemberUtil().member(testUser1, data1, testUser2, readDataPer, true);
		assertTrue("Expected member to be set after first removing any existing instance", setMember);
		PolicyEvaluator pe = ioContext.getPolicyEvaluator();
		PolicyResponseType prr = null;
		PolicyResponseType prr2 = null;
		PolicyType rec = getInferredOwnerPolicyFunction().toConcrete();

		boolean error = false;
		try {
			PolicyRequestType preq = mf.newInstance(ModelNames.MODEL_POLICY_REQUEST, testUser1, null, null, rec, group1).toConcrete(); 
			prr = pe.evaluatePolicyRequest(preq, rec).toConcrete();
			assertTrue("Expected the owner to be permitted", prr.getType() == PolicyResponseEnumType.PERMIT);
			PolicyRequestType preq2 = mf.newInstance(ModelNames.MODEL_POLICY_REQUEST, testUser2, null, null, rec, group1).toConcrete(); 
			prr2 = pe.evaluatePolicyRequest(preq2, rec).toConcrete();
			assertTrue("Expected someone other than the owner to be denied (lacking any other authorization check)", prr2.getType() == PolicyResponseEnumType.DENY);
		} catch (FactoryException | FieldException | ModelNotFoundException | ValueException | ScriptException | IndexException | ReaderException | ModelException e) {
			error = true;
			logger.error(e);
			
		}
		assertFalse("Error encountered", error);
		assertTrue("Expected a permit response", prr.getType() == PolicyResponseEnumType.PERMIT);

	}
	

	
}
