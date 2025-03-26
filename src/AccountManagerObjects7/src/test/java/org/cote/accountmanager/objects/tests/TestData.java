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
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ContentTypeUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ThumbnailUtil;
import org.junit.Test;

public class TestData extends BaseTest {

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
	public void TestDataConstruct() {
		logger.info("Test Data Construct");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String[] sampleData = new String[] {"airship.jpg", "anaconda.jpg", "antikythera.jpg", "railplane.png", "steampunk.png", "sunflower.png"};
		
		try {
			BaseRecord data = getCreateData(testUser1, "~/Data/Pictures", "./media/" + sampleData[1]);
			assertNotNull("Data is null", data);
			//logger.info(data.toFullString());
		}
		catch(ValueException | FieldException | ModelNotFoundException | FactoryException | IndexException | ReaderException | IOException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

	private BaseRecord getCreateData(BaseRecord user, String groupPath, String filePath) throws FieldException, ValueException, ModelNotFoundException, FactoryException, IndexException, ReaderException, IOException {
		//BaseRecord data = null;
		String dataName = filePath.replaceAll("\\\\", "/");
		dataName = dataName.substring(dataName.lastIndexOf("/") + 1);
		// dataName = "Float " + UUID.randomUUID().toString() + "-" + dataName;
		BaseRecord data = null;
		BaseRecord dir = ioContext.getPathUtil().findPath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dir != null) {
			data = ioContext.getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_DATA, dir.get(FieldNames.FIELD_OBJECT_ID), dataName);
			if(data != null) {
				return data;
			}
		}
		
		byte[] fdata = FileUtil.getFile(filePath);

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
		plist.parameter(FieldNames.FIELD_NAME, dataName);

		data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		data.set(FieldNames.FIELD_NAME, dataName);
		data.set(FieldNames.FIELD_CONTENT_TYPE, ContentTypeUtil.getTypeFromExtension(dataName));
		if(fdata.length == 0) {
			throw new ValueException("Invalid file: " + filePath);
		}
		if(fdata.length > ByteModelUtil.MAXIMUM_BYTE_LENGTH) {
			/// NOTE: When creating a stream, and creating segments separately, and then attaching that stream to another object
			/// The size re-calculation done in the segment writer will affect the persistence layer, not the instance of the stream object
			/// If the size is needed in the current context, such as below where a data object is stored as a stream, and then a thumbnail created, then:
			///		a) set the size on the stream, if known
			///		b) or, if all segments are present, write the segments with the stream
			///		c) or, re-read the stream after writing the segment
			///	Both (a) and (c) are done below
			BaseRecord stream = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, user, null, plist);
			stream.set(FieldNames.FIELD_CONTENT_TYPE, data.get(FieldNames.FIELD_CONTENT_TYPE));
			stream.set(FieldNames.FIELD_SIZE, (long)fdata.length);
			stream.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			//List<BaseRecord> segs = stream.get(FieldNames.FIELD_SEGMENTS);
			logger.info("Invoke create on stream");
			stream = ioContext.getAccessPoint().create(user, stream);
			
			//logger.info(stream.toFullString());
			
			BaseRecord seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
			seg.set(FieldNames.FIELD_STREAM, fdata);
			seg.set(FieldNames.FIELD_STREAM_ID, stream.get(FieldNames.FIELD_OBJECT_ID));
			logger.info("Invoke create on segment");
			ioContext.getRecordUtil().createRecord(seg);
			
			stream = ioContext.getAccessPoint().findByObjectId(user, ModelNames.MODEL_STREAM, stream.get(FieldNames.FIELD_OBJECT_ID));
			data.set(FieldNames.FIELD_STREAM, stream);
			data.set(FieldNames.FIELD_SIZE, stream.get(FieldNames.FIELD_SIZE));
		}
		else {
			data.set(FieldNames.FIELD_BYTE_STORE, fdata);
		}
		logger.info("Invoke create on data");
		data = ioContext.getAccessPoint().create(user, data);
		
		if(ThumbnailUtil.canCreateThumbnail(data)) {
			BaseRecord trec = ThumbnailUtil.getCreateThumbnail(data, 50, 50);
			assertNotNull("Thumbnail record is null", trec);
			assertTrue("Failed to emit file", FileUtil.emitFile("./thumb.png", (byte[])trec.get(FieldNames.FIELD_BYTE_STORE)));
		}
		else {
			logger.error("Cannot create thumbnail from data");
		}
		return data;
	}

	
}
