package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.MembershipStatistic;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Test;

public class TestData extends BaseTest {

	@Test
	public void TestMultipleTagQuery() {
		logger.info("Test Query for Data with Multiple Tags");
		OrganizationContext testOrgContext = getTestOrganization("/Development/MultiTagData");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "multiTagUser", testOrgContext.getOrganizationId());

		String tagGroupPath = "~/Tags";
		String dataGroupPath = "~/MultiTaggedData";

		// Create three tags
		String tag1Name = "Tag1-" + UUID.randomUUID().toString();
		String tag2Name = "Tag2-" + UUID.randomUUID().toString();
		String tag3Name = "Tag3-" + UUID.randomUUID().toString();

		// Create three data objects
		String data1Name = "Data1-" + UUID.randomUUID().toString();
		String data2Name = "Data2-" + UUID.randomUUID().toString();
		String data3Name = "Data3-" + UUID.randomUUID().toString();

		try {
			// Create tags
			ParameterList tagPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, tagGroupPath);
			tagPlist.parameter(FieldNames.FIELD_NAME, tag1Name);
			BaseRecord tag1 = ioContext.getFactory().newInstance(ModelNames.MODEL_TAG, testUser, null, tagPlist);
			tag1 = ioContext.getAccessPoint().create(testUser, tag1);
			assertNotNull("Failed to create tag1", tag1);

			tagPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, tagGroupPath);
			tagPlist.parameter(FieldNames.FIELD_NAME, tag2Name);
			BaseRecord tag2 = ioContext.getFactory().newInstance(ModelNames.MODEL_TAG, testUser, null, tagPlist);
			tag2 = ioContext.getAccessPoint().create(testUser, tag2);
			assertNotNull("Failed to create tag2", tag2);

			tagPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, tagGroupPath);
			tagPlist.parameter(FieldNames.FIELD_NAME, tag3Name);
			BaseRecord tag3 = ioContext.getFactory().newInstance(ModelNames.MODEL_TAG, testUser, null, tagPlist);
			tag3 = ioContext.getAccessPoint().create(testUser, tag3);
			assertNotNull("Failed to create tag3", tag3);

			// Create data objects
			ParameterList dataPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, dataGroupPath);
			dataPlist.parameter(FieldNames.FIELD_NAME, data1Name);
			BaseRecord data1 = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, dataPlist);
			data1 = ioContext.getAccessPoint().create(testUser, data1);
			assertNotNull("Failed to create data1", data1);

			dataPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, dataGroupPath);
			dataPlist.parameter(FieldNames.FIELD_NAME, data2Name);
			BaseRecord data2 = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, dataPlist);
			data2 = ioContext.getAccessPoint().create(testUser, data2);
			assertNotNull("Failed to create data2", data2);

			dataPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, dataGroupPath);
			dataPlist.parameter(FieldNames.FIELD_NAME, data3Name);
			BaseRecord data3 = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, dataPlist);
			data3 = ioContext.getAccessPoint().create(testUser, data3);
			assertNotNull("Failed to create data3", data3);

			// Tag data1 with tag1 and tag2
			assertTrue("Failed to tag data1 with tag1", ioContext.getMemberUtil().member(testUser, tag1, data1, null, true));
			assertTrue("Failed to tag data1 with tag2", ioContext.getMemberUtil().member(testUser, tag2, data1, null, true));

			// Tag data2 with tag1 only
			assertTrue("Failed to tag data2 with tag1", ioContext.getMemberUtil().member(testUser, tag1, data2, null, true));

			// Tag data3 with tag1, tag2, and tag3
			assertTrue("Failed to tag data3 with tag1", ioContext.getMemberUtil().member(testUser, tag1, data3, null, true));
			assertTrue("Failed to tag data3 with tag2", ioContext.getMemberUtil().member(testUser, tag2, data3, null, true));
			assertTrue("Failed to tag data3 with tag3", ioContext.getMemberUtil().member(testUser, tag3, data3, null, true));

			// Test 1: Query for data with tag1 only - should return all 3 data objects
			List<BaseRecord> tag1Members = ioContext.getMemberUtil().getMembers(tag1, null, ModelNames.MODEL_DATA);
			logger.info("Data with tag1: " + tag1Members.size());
			assertTrue("Expected 3 data objects with tag1", tag1Members.size() == 3);

			// Test 2: Query for data with tag2 only - should return data1 and data3
			List<BaseRecord> tag2Members = ioContext.getMemberUtil().getMembers(tag2, null, ModelNames.MODEL_DATA);
			logger.info("Data with tag2: " + tag2Members.size());
			assertTrue("Expected 2 data objects with tag2", tag2Members.size() == 2);

			// Test 3: Query for data with tag1 AND tag2 using participation join
			long tag1Id = tag1.get(FieldNames.FIELD_ID);
			long tag2Id = tag2.get(FieldNames.FIELD_ID);
			long tag3Id = tag3.get(FieldNames.FIELD_ID);

			String twoTagIds = tag1Id + "," + tag2Id;
			Query multiTagQuery = QueryUtil.createQuery(ModelNames.MODEL_DATA);
			
			multiTagQuery.setRequest(new String[]{"id"});
			Query partQuery = QueryUtil.createParticipationQuery(null, null, null, null, null);
			partQuery.field(FieldNames.FIELD_PARTICIPATION_ID, ComparatorEnumType.IN, twoTagIds);
			partQuery.field(FieldNames.FIELD_PARTICIPATION_MODEL, ModelNames.MODEL_TAG);
			partQuery.field(FieldNames.FIELD_PARTICIPANT_MODEL, ModelNames.MODEL_DATA);
			partQuery.set(FieldNames.FIELD_JOIN_KEY, FieldNames.FIELD_PARTICIPANT_ID);
			List<BaseRecord> joins = multiTagQuery.get(FieldNames.FIELD_JOINS);
			joins.add(partQuery);
			// any of the tags
			multiTagQuery.set(FieldNames.FIELD_GROUP_CLAUSE, StatementUtil.getAlias(multiTagQuery) + "." + FieldNames.FIELD_ID);
			//multiTagQuery.set(FieldNames.FIELD_HAVING_CLAUSE, "COUNT(DISTINCT " + StatementUtil.getAlias(partQuery) + "." + FieldNames.FIELD_PARTICIPATION_ID + ") = 2");


			logger.info("Multi-tag query SQL:");
			logger.info(StatementUtil.getSelectTemplate(multiTagQuery).getSql());

			QueryResult multiTagResult = ioContext.getAccessPoint().list(testUser, multiTagQuery);
			logger.info("Data with tag1 or tag2: " + multiTagResult.getCount());
			// Returns data matching ANY of the tags (data1, data2, data3)
			//logger.info(multiTagQuery.toSelect());
			
			assertTrue("Expected 3 data objects with tag1 or tag2, but found " + multiTagResult.getCount(), multiTagResult.getCount() == 3);

			// Test 4: Query for data matching ALL of tag1 AND tag2 (using HAVING)
			Query allTagQuery = QueryUtil.createQuery(ModelNames.MODEL_DATA);
			allTagQuery.setRequest(new String[]{FieldNames.FIELD_ID});
			Query partQuery2 = QueryUtil.createParticipationQuery(null, null, null, null, null);
			partQuery2.field(FieldNames.FIELD_PARTICIPATION_ID, ComparatorEnumType.IN, twoTagIds);
			partQuery2.field(FieldNames.FIELD_PARTICIPATION_MODEL, ModelNames.MODEL_TAG);
			partQuery2.field(FieldNames.FIELD_PARTICIPANT_MODEL, ModelNames.MODEL_DATA);
			partQuery2.set(FieldNames.FIELD_JOIN_KEY, FieldNames.FIELD_PARTICIPANT_ID);
			List<BaseRecord> joins2 = allTagQuery.get(FieldNames.FIELD_JOINS);
			joins2.add(partQuery2);

			allTagQuery.set(FieldNames.FIELD_GROUP_CLAUSE, StatementUtil.getAlias(allTagQuery) + "." + FieldNames.FIELD_ID);
			allTagQuery.set(FieldNames.FIELD_HAVING_CLAUSE, "COUNT(DISTINCT " + StatementUtil.getAlias(partQuery2) + "." + FieldNames.FIELD_PARTICIPATION_ID + ") = 2");

			logger.info("All-tags (" + twoTagIds + ") query SQL: " + allTagQuery.key());
			logger.info(StatementUtil.getSelectTemplate(allTagQuery).getSql());
			QueryResult allTagResult = ioContext.getAccessPoint().list(testUser, allTagQuery);
			logger.info("Data with tag1 AND tag2: " + allTagResult.getCount());
			for(BaseRecord dat : allTagResult.getResults()){

				logger.info("#" + (long)dat.get("id"));
			}
			// Only data1 and data3 have BOTH tag1 and tag2
			assertTrue("Expected 2 data objects with both tag1 and tag2, found " + allTagResult.getCount(), allTagResult.getCount() == 2);

			// Test 5: Query for data matching ALL of tag1, tag2, AND tag3
			String threeTagIds = tag1Id + "," + tag2Id + "," + tag3Id;
			Query allThreeQuery = QueryUtil.createQuery(ModelNames.MODEL_DATA);
			allThreeQuery.setRequest(new String[]{FieldNames.FIELD_ID});
			Query partQuery3 = QueryUtil.createParticipationQuery(null, null, null, null, null);
			partQuery3.field(FieldNames.FIELD_PARTICIPATION_ID, ComparatorEnumType.IN, threeTagIds);
			partQuery3.field(FieldNames.FIELD_PARTICIPATION_MODEL, ModelNames.MODEL_TAG);
			partQuery3.field(FieldNames.FIELD_PARTICIPANT_MODEL, ModelNames.MODEL_DATA);
			partQuery3.set(FieldNames.FIELD_JOIN_KEY, FieldNames.FIELD_PARTICIPANT_ID);
			List<BaseRecord> joins3 = allThreeQuery.get(FieldNames.FIELD_JOINS);
			joins3.add(partQuery3);

			allThreeQuery.set(FieldNames.FIELD_GROUP_CLAUSE, StatementUtil.getAlias(allThreeQuery) + "." + FieldNames.FIELD_ID);
			allThreeQuery.set(FieldNames.FIELD_HAVING_CLAUSE, "COUNT(DISTINCT " + StatementUtil.getAlias(partQuery3) + "." + FieldNames.FIELD_PARTICIPATION_ID + ") = 3");

			logger.info("All-three-tags query SQL:");
			logger.info(StatementUtil.getSelectTemplate(allThreeQuery).getSql());
			QueryResult allThreeResult = ioContext.getAccessPoint().list(testUser, allThreeQuery);
			logger.info("Data with tag1 AND tag2 AND tag3: " + allThreeResult.getCount());
			// Only data3 has all 3 tags
			assertTrue("Expected 1 data object with all 3 tags, found " + allThreeResult.getCount(), allThreeResult.getCount() == 1);

		} catch (FactoryException | IndexException | ReaderException | ModelException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	@Test
	public void TestTagQuery() {
		logger.info("Test Tag Query - Inverting query from current version");
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_TAGS});
		try {
			logger.info(StatementUtil.getSelectTemplate(q).getSql());
		} catch (ModelException | FieldException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	@Test
	public void TestTag() {
		logger.info("Tag data");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String tagName = "Tag - " + UUID.randomUUID().toString();
		String tagGroupPath = "~/Tags";
		
		String dataName = "Test Data - " + UUID.randomUUID().toString();
		String groupPath = "~/Tagged Data";
		ParameterList tplist = ParameterList.newParameterList(FieldNames.FIELD_PATH, tagGroupPath);
		tplist.parameter(FieldNames.FIELD_NAME, tagName);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, dataName);

		
		BaseRecord data = null;
		BaseRecord tag = null;
		try {
			tag = ioContext.getFactory().newInstance(ModelNames.MODEL_TAG, testUser1, null, plist);
			tag = ioContext.getAccessPoint().create(testUser1, tag);
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			data = ioContext.getAccessPoint().create(testUser1, data);
			boolean tagged = ioContext.getMemberUtil().member(testUser1, tag, data, null, true);
			assertTrue("Failed to tag", tagged);
			
			List<BaseRecord> datal = ioContext.getMemberUtil().getMembers(tag, null, ModelNames.MODEL_DATA);
			assertTrue("Expected size to be 1", datal.size() == 1);
			
			datal = ioContext.getMemberUtil().getParticipations(data, ModelNames.MODEL_TAG);
			assertTrue("Expected size to be 1", datal.size() == 1);
			
		} catch (FactoryException | IndexException | ReaderException e) {
			logger.error(e);
		}
	}
	
	@Test
	public void TestExportEnum() {
		String schema = SchemaUtil.getSchemaJSON();
		assertNotNull("Schema is null", schema);
	}
	
	@Test
	public void TestImportFlex() {
		/// Note: RecordDeserializer not currently picking up the foreignType property which is used by the database persistence utilities, so the model needs to be specified on the property marked as being of type model
		///
		String authReq = """
			{
				"schema": "auth.authenticationRequest",
				"subjectType": "user",
				"subject":{
					"schema": "system.user",
					"name": "Admin",
					"organizationPath": "/Development"
				}
			}
		""";
		BaseRecord req = JSONUtil.importObject(authReq,  LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		
	}
	
	@Test
	public void TestCreateAttribute() {
		
		
		logger.info("Test Create Attribute On Existing Object");
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Attributes");
		Factory mf = ioContext.getFactory();
		BaseRecord attrUser = mf.getCreateUser(testOrgContext.getAdminUser(), "attrUser", testOrgContext.getOrganizationId());

		assertNotNull("Organization is null", testOrgContext);
		assertNotNull("User is null", attrUser);
		BaseRecord group = ioContext.getPathUtil().makePath(attrUser, ModelNames.MODEL_GROUP, "~/Attribute Test", "DATA", testOrgContext.getOrganizationId());
		BaseRecord data = null;
		String dataName = UUID.randomUUID().toString();
		try {
			data = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
			ioContext.getRecordUtil().applyNameGroupOwnership(attrUser, data, dataName, "~/Attribute Test", testOrgContext.getOrganizationId());
			data.set(FieldNames.FIELD_CONTENT_TYPE,  "text/plain");
			data.set(FieldNames.FIELD_BYTE_STORE, "The data".getBytes());
			ioContext.getAccessPoint().create(attrUser, data);

			BaseRecord dataPatch = ioContext.getAccessPoint().findByNameInGroup(attrUser, ModelNames.MODEL_DATA, (long)group.get(FieldNames.FIELD_ID), data.get(FieldNames.FIELD_NAME));
			assertNotNull("Data was null", dataPatch);
			//logger.info(dataPatch.toFullString());
			
			BaseRecord attr = AttributeUtil.addAttribute(dataPatch, "Demo attribute - " + UUID.randomUUID().toString(), true);
			assertNotNull("New Attribute was null", attr);
			//logger.info(attr.toFullString());
			
			BaseRecord nattr = ioContext.getAccessPoint().create(attrUser, attr);
			assertNotNull("Failed to create new attribute", nattr);
			
			
			/// Try passing the new attribute through the factory layer
			//attr.set(FieldNames.FIELD_NAME, "Demo attribute 2 - " + UUID.randomUUID().toString());
			logger.info(attr.toFullString());
			BaseRecord attr2 = IOSystem.getActiveContext().getFactory().newInstance(attr.getSchema(), attrUser, attr, null);

			assertNotNull("New attribute was null", attr2);
			logger.info(attr2.toFullString());
			
			BaseRecord nattr2 = ioContext.getAccessPoint().create(attrUser, attr2);
			assertNotNull("Failed to create new attribute", nattr2);
			logger.info(nattr2.toFullString());
			
			Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, dataPatch.get(FieldNames.FIELD_OBJECT_ID));
			q.setRequest(new String[] {
				FieldNames.FIELD_ID,
				FieldNames.FIELD_NAME,
				FieldNames.FIELD_ATTRIBUTES,
				FieldNames.FIELD_TAGS
			});
			DBStatementMeta meta = StatementUtil.getSelectTemplate(q);
			logger.info(meta.getSql());
			/// 

			
		} catch (ClassCastException | FieldException | ModelNotFoundException | ValueException | ModelException | FactoryException e1) {
			logger.error(e1);
			e1.printStackTrace();
		}
	}
	

	@Test
	public void TestTagCloudQuery() {
		logger.info("Test Tag Cloud Query - Tags with membership counts sorted by count descending");
		OrganizationContext testOrgContext = getTestOrganization("/Development/TagCloud");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "tagCloudUser", testOrgContext.getOrganizationId());

		String testId = UUID.randomUUID().toString();
		String tagGroupPath = "~/Tags/Cloud-" + testId;
		String dataGroupPath = "~/TagCloudData/" + testId;

		try {
			// Create 3 tags
			ParameterList tagPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, tagGroupPath);
			tagPlist.parameter(FieldNames.FIELD_NAME, "CloudTag1-" + UUID.randomUUID().toString());
			BaseRecord tag1 = ioContext.getAccessPoint().create(testUser, ioContext.getFactory().newInstance(ModelNames.MODEL_TAG, testUser, null, tagPlist));
			assertNotNull("Failed to create tag1", tag1);

			tagPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, tagGroupPath);
			tagPlist.parameter(FieldNames.FIELD_NAME, "CloudTag2-" + UUID.randomUUID().toString());
			BaseRecord tag2 = ioContext.getAccessPoint().create(testUser, ioContext.getFactory().newInstance(ModelNames.MODEL_TAG, testUser, null, tagPlist));
			assertNotNull("Failed to create tag2", tag2);

			tagPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, tagGroupPath);
			tagPlist.parameter(FieldNames.FIELD_NAME, "CloudTag3-" + UUID.randomUUID().toString());
			BaseRecord tag3 = ioContext.getAccessPoint().create(testUser, ioContext.getFactory().newInstance(ModelNames.MODEL_TAG, testUser, null, tagPlist));
			assertNotNull("Failed to create tag3", tag3);

			// Create 5 data objects and assign memberships:
			// tag1: 5 members, tag2: 3 members, tag3: 1 member
			for (int i = 0; i < 5; i++) {
				ParameterList dataPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, dataGroupPath);
				dataPlist.parameter(FieldNames.FIELD_NAME, "CloudData" + i + "-" + UUID.randomUUID().toString());
				BaseRecord data = ioContext.getAccessPoint().create(testUser, ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, dataPlist));
				assertNotNull("Failed to create data" + i, data);

				assertTrue("Failed to tag data" + i + " with tag1", ioContext.getMemberUtil().member(testUser, tag1, data, null, true));
				if (i < 3) {
					assertTrue("Failed to tag data" + i + " with tag2", ioContext.getMemberUtil().member(testUser, tag2, data, null, true));
				}
				if (i < 1) {
					assertTrue("Failed to tag data" + i + " with tag3", ioContext.getMemberUtil().member(testUser, tag3, data, null, true));
				}
			}

			// Authorize a query for the tag model
			Query authQuery = QueryUtil.createQuery(ModelNames.MODEL_TAG);
			authQuery.setRequest(new String[]{FieldNames.FIELD_ID, FieldNames.FIELD_NAME});
			long tagGroupId = tag1.get(FieldNames.FIELD_GROUP_ID);
			authQuery.field(FieldNames.FIELD_GROUP_ID, tagGroupId);

			PolicyResponseType prr = ioContext.getAccessPoint().authorizeQuery(testUser, authQuery);
			assertNotNull("Authorization response was null", prr);
			assertTrue("Query was not authorized: " + prr.getType(), prr.getType() == PolicyResponseEnumType.PERMIT);
			logger.info("Tag cloud query authorized");

			// Use MemberUtil.countMembers to get tag cloud data
			List<MembershipStatistic> cloud = ioContext.getMemberUtil().countMembers(ModelNames.MODEL_TAG, null, tagGroupId, testOrgContext.getOrganizationId());
			logger.info("Tag cloud returned " + cloud.size() + " tags");
			for (MembershipStatistic stat : cloud) {
				logger.info("Tag: " + stat.getName() + " (id=" + stat.getId() + ") members=" + stat.getCount());
			}
			assertTrue("Expected 3 tags in cloud, found " + cloud.size(), cloud.size() == 3);

			// Verify descending order by count
			assertTrue("Expected first tag to have 5 members", cloud.get(0).getCount() == 5L);
			assertTrue("Expected second tag to have 3 members", cloud.get(1).getCount() == 3L);
			assertTrue("Expected third tag to have 1 member", cloud.get(2).getCount() == 1L);

		} catch (FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	@Test
	public void TestDataConstruct() {
		logger.info("Test Data Construct");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String[] sampleData = new String[] {"airship.jpg", "anaconda.jpg", "antikythera.jpg", "railplane.png", "steampunk.png", "sunflower.png"};
		
		try {
			BaseRecord data = getCreateFileData(testUser1, "~/Data/Pictures", "./media/" + sampleData[1]);
			assertNotNull("Data is null", data);
			//logger.info(data.toFullString());
		}
		catch(ValueException | FieldException | ModelNotFoundException | FactoryException | IndexException | ReaderException | IOException | ModelException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	

	
}
