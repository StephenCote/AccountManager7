package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ContentTypeUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.GraphicsUtil;
import org.cote.accountmanager.util.ThumbnailUtil;
import org.junit.Test;

public class TestData extends BaseTest {

	@Test
	public void TestDataConstruct() {
		logger.info("Test Data Construct");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Data");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 =  mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String[] sampleData = new String[] {"IMG_20220827_184359053.jpg", "IMG_20221230_142909291_HDR.jpg", "shutterstock-1940302522___08094459781.png", "The_George_Bennie_Railplane_System_of_Transport_poster,_1929.png"};
		
		try {
			BaseRecord data = getCreateData(testUser1, "~/Data/Pictures", "c:\\tmp\\xpic\\" + sampleData[0]);
			assertNotNull("Data is null", data);
			logger.info(data.toFullString());
			/*
			BaseRecord data = markAndThumb(testUser1, "~/Data/Streams", "c:\\tmp\\xpic\\" + sampleData[0]);
			assertNotNull("Data is null", data);
			
			logger.info(data.toFullString());
			*/
		}
		/// ValueException | FieldException | ModelNotFoundException | FactoryException | IndexException | Reader
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	private BaseRecord getCreateData(BaseRecord user, String groupPath, String filePath) throws FieldException, ValueException, ModelNotFoundException, FactoryException, IndexException, ReaderException, IOException {
		//BaseRecord data = null;
		String dataName = filePath.replaceAll("\\\\", "/");
		dataName = dataName.substring(dataName.lastIndexOf("/") + 1);

		BaseRecord data = null;
		BaseRecord dir = ioContext.getPathUtil().findPath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dir != null) {
			data = ioContext.getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_DATA, dir.get(FieldNames.FIELD_OBJECT_ID), dataName);
			if(data != null) {
				return data;
			}
		}
		
		byte[] fdata = FileUtil.getFile(filePath);

		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		plist.parameter("name", dataName);

		data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		data.set(FieldNames.FIELD_NAME, dataName);
		data.set(FieldNames.FIELD_CONTENT_TYPE, ContentTypeUtil.getTypeFromExtension(dataName));
		
		if(fdata.length > ByteModelUtil.MAXIMUM_BYTE_LENGTH) {
			BaseRecord stream = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, user, null, plist);
			stream.set(FieldNames.FIELD_CONTENT_TYPE, data.get(FieldNames.FIELD_CONTENT_TYPE));
			stream.set(FieldNames.FIELD_SIZE, (long)fdata.length);
			stream.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			//List<BaseRecord> segs = stream.get(FieldNames.FIELD_SEGMENTS);
			stream = ioContext.getAccessPoint().create(user, stream);
			
			BaseRecord seg = RecordFactory.newInstance(ModelNames.MODEL_STREAM_SEGMENT);
			seg.set(FieldNames.FIELD_STREAM, fdata);
			seg.set(FieldNames.FIELD_STREAM_ID, stream.get(FieldNames.FIELD_OBJECT_ID));
			ioContext.getRecordUtil().createRecord(seg);
			
			data.set(FieldNames.FIELD_STREAM, stream);
		}
		else {
			data.set(FieldNames.FIELD_BYTE_STORE, fdata);
		}
		data = ioContext.getAccessPoint().create(user, data);
		
		if(ThumbnailUtil.canCreateThumbnail(data)) {
			ThumbnailUtil.getCreateThumbnail(data, 50, 50);
		}
		return data;
	}
	
	private BaseRecord markAndThumb(BaseRecord user, String groupPath, String filePath) throws FieldException, ValueException, ModelNotFoundException, FactoryException, IndexException, ReaderException, IOException {
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), orgId);
		logger.info("Path: " + filePath);
		String dataName = filePath.replaceAll("\\\\", "/");
		dataName = dataName.substring(dataName.lastIndexOf("/") + 1);
		BaseRecord stream = ioContext.getRecordUtil().getRecord(user, ModelNames.MODEL_STREAM, dataName, 0, dir.get(FieldNames.FIELD_ID), orgId);
		BaseRecord data = ioContext.getRecordUtil().getRecord(user, ModelNames.MODEL_DATA, dataName, 0, dir.get(FieldNames.FIELD_ID), orgId);
		BaseRecord thumb = null;
		if(stream != null) {
			assertTrue("Failed to delete stream", ioContext.getAccessPoint().delete(user, stream));
			stream = null;
		}
		else {
			logger.info("Stream " + dataName + " not found");
		}
		if(data != null) {
			assertTrue("Failed to delete data", ioContext.getAccessPoint().delete(user, data));
			data = null;
		}
		else {
			logger.info("Data " + dataName + " not found");
		}
		if(stream == null) {
			stream = ioContext.getFactory().newInstance(ModelNames.MODEL_STREAM, user, null, null);
			stream.set(FieldNames.FIELD_NAME, dataName);
			stream.set(FieldNames.FIELD_TYPE, StreamEnumType.FILE);
			stream.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			//stream.set(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			stream.set(FieldNames.FIELD_STREAM_SOURCE, filePath);
			stream.set(FieldNames.FIELD_CONTENT_TYPE, ContentTypeUtil.getTypeFromExtension(dataName));
			stream = ioContext.getAccessPoint().create(user, stream);
		}
		if(data == null && stream != null) {
			data = modelDataOnStream(user, stream);
		}
		if(data != null) {
			thumb = ThumbnailUtil.getCreateThumbnail(data, 50, 50);

		}
		return thumb;

	}
	
	private BaseRecord modelDataOnStream(BaseRecord user, BaseRecord stream) throws FactoryException, FieldException, ValueException, ModelNotFoundException {
		BaseRecord data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, user, null, null);
		data.set(FieldNames.FIELD_NAME, stream.get(FieldNames.FIELD_NAME));
		data.set(FieldNames.FIELD_CONTENT_TYPE, stream.get(FieldNames.FIELD_CONTENT_TYPE));
		data.set(FieldNames.FIELD_GROUP_ID, stream.get(FieldNames.FIELD_GROUP_ID));
		data.set(FieldNames.FIELD_GROUP_PATH, stream.get(FieldNames.FIELD_GROUP_PATH));
		data.set(FieldNames.FIELD_STREAM, stream);
		data.set(FieldNames.FIELD_SIZE, stream.get(FieldNames.FIELD_SIZE));
		//logger.info(data.toFullString());
		
		PolicyResponseType prr = ioContext.getPolicyUtil().evaluateResourcePolicy(user, PolicyUtil.POLICY_SYSTEM_CREATE_OBJECT, user, null, data);
		logger.info((String)prr.get(FieldNames.FIELD_DESCRIPTION));
		
		data = ioContext.getAccessPoint().create(user, data);
		// data.set(FieldNames.FIELD_STREAM, stream);
		/*
		logger.error("**** Dynamic policy currently broken - currently only pulls in direct resource entitlement, and needs to pull in all patterns associated with the rule");
		ioContext.getAccessPoint().update(user, data);
		*/
		return data;
	}
	
}
