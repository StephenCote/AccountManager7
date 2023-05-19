package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;

import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.schema.type.ValidationEnumType;
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

		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		plist.parameter("name", dataName);

		data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
		data.set(FieldNames.FIELD_NAME, dataName);
		data.set(FieldNames.FIELD_CONTENT_TYPE, ContentTypeUtil.getTypeFromExtension(dataName));
		
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
			ThumbnailUtil.getCreateThumbnail(data, 50, 50);
		}
		return data;
	}
	
}
