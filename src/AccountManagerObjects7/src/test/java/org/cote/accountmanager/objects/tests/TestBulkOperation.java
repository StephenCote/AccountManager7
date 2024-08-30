package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.DBWriter;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;


public class TestBulkOperation extends BaseTest {


	private int bulkLoadSize = 10;

	@Test
	public void TestLikelyBrokenParticipations() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String path = "~/Dooter Peeps - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList("path", path);
		String name = "Person 1";
		plist.parameter("name", name);
		try {
			BaseRecord a1 = ioContext.getFactory().newInstance(ModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a1.set("gender", "male");
			AttributeUtil.addAttribute(a1, "test", true);
			BaseRecord a2 = ioContext.getFactory().newInstance(ModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a2.set(FieldNames.FIELD_NAME, "Person 2");
			a2.set("gender", "female");
			AttributeUtil.addAttribute(a2, "test", false);

			/// BUG: When adding cross-relationships such as partnerships, the auto-created participation for one half will wind up missing the other half's identifier (in io.db) because of the auto-participation adds are currently coded within the scope of a single record.
			/// To fix this, participations for all records would need to be pulled out separately, have the record identifiers assigned first, and then bulk add the participations
			/// In the previous version, most model level participations were handled like this.
			/// In the current version, the preference is to keep the participation disconnected from the model factory to avoid having to perform bulk read, update, and deletes to determine what changed on every update
			/// In other words, don't auto-create cross-participations except to be able to make an in-scope reference:

			ioContext.getRecordUtil().createRecords(new BaseRecord[] {a1, a2});
			BaseRecord p1 = ParticipationFactory.newParticipation(testUser1, a1, "partners", a2);
			BaseRecord p2 = ParticipationFactory.newParticipation(testUser1, a2, "partners", a1);
			ioContext.getRecordUtil().createRecords(new BaseRecord[] {p1, p2});
			
			Query q = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_NAME, "Person 1");
			q.planMost(true);
			//q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_ATTRIBUTES, "partners", "gender"});
			DBStatementMeta meta = StatementUtil.getSelectTemplate(q);
			Query q2 = QueryUtil.createQuery(ModelNames.MODEL_APPAREL);
			q.planMost(true);
			DBStatementMeta meta2 = StatementUtil.getSelectTemplate(q2);
			// logger.info("Outside io.db: " + meta.getColumns().stream().collect(Collectors.joining(", ")));
			//logger.info(meta2.getSql());
			/// Access point will force request fields to a finite set if not otherwise defined
			/// When wanting to test foreign recursion of same types, it's necessary to specify the field when using access point, even
			/// when the limit is disabled.  This is intentional since access point is the entry for API calls and conducts policy enforcement
			///
			///
			//BaseRecord rec = ioContext.getAccessPoint().find(testUser1, q);
			BaseRecord rec = ioContext.getSearch().findRecord(q);
			assertNotNull("Record is null", rec);
			List<BaseRecord> parts = rec.get("partners");
			assertTrue("Expected partners to be populated", parts.size() == 1);
			
			BaseRecord attr = AttributeUtil.getAttribute(rec, "test");
			assertNotNull("Expected to find the attribute", attr);
			FieldType ft = attr.getField(FieldNames.FIELD_VALUE);
			logger.info(ft.getName() + " -> " + ft.getValueType().toString());
			boolean attrVal = AttributeUtil.getAttributeValue(rec, "test", false);
			logger.info("Test: " + attrVal);
			
			logger.info(rec.toFullString());
		}
		catch(StackOverflowError | FieldException | ValueException | ModelNotFoundException | FactoryException | ModelException e) {
			logger.error(e);
			e.printStackTrace();
		}

	}
	
	@Test
	public void TestDeepSingleModelQuery() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		List<BaseRecord> aal = new ArrayList<>();
		String path = "~/Demo - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList("path", path);
		String name = "Dooter - " + UUID.randomUUID().toString();
		plist.parameter("name", name);
		try {
			BaseRecord a1 = ioContext.getFactory().newInstance(ModelNames.MODEL_APPAREL, testUser1, null, plist);
			aal.add(a1);
			List<BaseRecord> wl = a1.get("wearables");
			BaseRecord w1 = ioContext.getFactory().newInstance(ModelNames.MODEL_WEARABLE, testUser1, null, plist);
			wl.add(w1);
			List<BaseRecord> ql = w1.get("qualities");
			BaseRecord q1 = ioContext.getFactory().newInstance(ModelNames.MODEL_QUALITY, testUser1, null, plist);
			ql.add(q1);
			BaseRecord d1 = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			w1.set("pattern", d1);
			d1.set("dataBytesStore", "This is some example text".getBytes());
			ioContext.getAccessPoint().create(testUser1, a1);

			
			Query q = QueryUtil.createQuery(ModelNames.MODEL_APPAREL, FieldNames.FIELD_ID, a1.get(FieldNames.FIELD_ID));
			q.planMost(true);

			// DBStatementMeta meta = StatementUtil.getSelectTemplate(q);
			//logger.info(meta.getSql());
			BaseRecord a2 = ioContext.getSearch().findRecord(q);
			assertNotNull("It's null", a2);

			((DBWriter)ioContext.getWriter()).setDeleteForeignReferences(true);
			ioContext.getAccessPoint().delete(testUser1, a1);
			ioContext.getAccessPoint().delete(testUser1, w1);
			ioContext.getAccessPoint().delete(testUser1, q1);
			ioContext.getAccessPoint().delete(testUser1, d1);
			((DBWriter)ioContext.getWriter()).setDeleteForeignReferences(false);
		}
		catch(ModelNotFoundException | FactoryException | FieldException | ValueException e) {
			logger.error(e);
		}
		

	}
	
	@Test
	public void TestLocationParent() {
		logger.info("Test location parent");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Geolocation");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String groupPath = "~/Bulk/Geo - " + UUID.randomUUID().toString();

		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		String locName = "Parent Loc";
		String chdName = "Child Loc";
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		plist.parameter("name", locName);
		BaseRecord loc = null;
		try {
			loc = ioContext.getFactory().newInstance(ModelNames.MODEL_LOCATION, testUser1, null, plist);
			loc = ioContext.getAccessPoint().create(testUser1, loc);
		} catch (FactoryException e) {
			logger.error(e);
		}
		assertNotNull("Loc is null", loc);

		String[] names = RecordUtil.getCommonFields(ModelNames.MODEL_LOCATION);

		BaseRecord lloc = ioContext.getAccessPoint().findByObjectId(testUser1, ModelNames.MODEL_LOCATION, loc.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("Unable to lookup location", lloc);

	}


	 private BaseRecord newTestData(BaseRecord owner, String path, String name, String textData) {
		ParameterList plist = ParameterList.newParameterList("path", path);
		plist.parameter("name", name);
		BaseRecord data = null;
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, owner, null, plist);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			data.set(FieldNames.FIELD_BYTE_STORE, textData.getBytes());
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return data;
	}

	@Test
	public void TestSingleBatchInsertSameType() {
		logger.info("Testing inserting records one at a time versus by batch");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Batch Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Data - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);
		
		String dataNamePrefix = "Data Test - ";
		String bulkDataNamePrefix = "Bulk Data Test - ";
		List<BaseRecord> bulkLoad = new ArrayList<>();
		List<BaseRecord> bulkLoad2 = new ArrayList<>();
		
		logger.info("Generating dataset 1 - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, dataNamePrefix + (i+1), "This is the example data");
			bulkLoad.add(data);
		}

		long start = System.currentTimeMillis();
		for(int i = 0; i < bulkLoad.size(); i++) {
			ioContext.getAccessPoint().create(testUser1, bulkLoad.get(i));
		}
		long stop = System.currentTimeMillis();
		logger.info("Time to insert by individual record: " + (stop - start) + "ms");
		
		logger.info("Generating dataset 2 - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, bulkDataNamePrefix + (i+1), "This is the example data");
			bulkLoad2.add(data);
		}
		
		start = System.currentTimeMillis();

		ioContext.getAccessPoint().create(testUser1, bulkLoad2.toArray(new BaseRecord[0]));
		
		stop = System.currentTimeMillis();
		logger.info("Time to insert by batch: " + (stop - start) + "ms");
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, bulkDataNamePrefix);
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q);
		assertTrue("Expected to retrieve the batch size", qr.getCount() == bulkLoadSize);
		
	}

	
	@Test
	public void TestBatchUpdate() {
		logger.info("Testing updating a batch of records");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Batch Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Data - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);
		
		String bulkDataNamePrefix = "Bulk Data Test - ";
		List<BaseRecord> bulkLoad = new ArrayList<>();
		
		logger.info("Generating dataset - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, bulkDataNamePrefix + (i+1), "This is the example data");
			bulkLoad.add(data);
		}
		
		long start = System.currentTimeMillis();
		ioContext.getAccessPoint().create(testUser1, bulkLoad.toArray(new BaseRecord[0]));
		long stop = System.currentTimeMillis();
		logger.info("Time to insert by batch: " + (stop - start) + "ms");
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, bulkDataNamePrefix);
		q.setRequest(new String[] {FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_CONTENT_TYPE});
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q);
		assertTrue("Expected to retrieve the batch size", qr.getCount() == bulkLoadSize);
		
		BaseRecord[] records = qr.getResults();
		boolean error = false;
		try {
			records[0].set(FieldNames.FIELD_DESCRIPTION, "This is an example description");

			start = System.currentTimeMillis();
			int updated = ioContext.getAccessPoint().update(testUser1, records);
			stop = System.currentTimeMillis();
			logger.info("Time to fail update by batch: " + (stop - start) + "ms");
			
			assertTrue("Expected update to fail because the first record includes a field that the other records do not", updated == 0);
			for(BaseRecord rec: records) {
				rec.set(FieldNames.FIELD_DESCRIPTION, "Patch description: " + UUID.randomUUID().toString());
			}
			
			start = System.currentTimeMillis();
			updated = ioContext.getAccessPoint().update(testUser1, records);
			stop = System.currentTimeMillis();
			logger.info("Time to update by batch: " + (stop - start) + "ms");
			assertTrue("Expected update (" + updated + ") to succeed for all (" + records.length + ") records", updated == records.length);
			
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			error = true;
		}
		assertFalse("Encountered an error", error);
	}
	

}
