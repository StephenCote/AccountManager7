package org.cote.accountmanager.util;

import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;

public class ThumbnailUtil {
	
	public static final Logger logger = LogManager.getLogger(ThumbnailUtil.class);
	
	public static boolean canCreateThumbnail(BaseRecord record) {
		String contentType = record.get(FieldNames.FIELD_CONTENT_TYPE);
		if(contentType == null || !contentType.startsWith("image/")) {
			logger.warn("Record does not define a content type");
			ErrorUtil.printStackTrace();
			return false;
		}
		if(!record.inherits(ModelNames.MODEL_DIRECTORY) || (long)record.get(FieldNames.FIELD_GROUP_ID) == 0L) {
			logger.warn("Record does not define a group id");
			return false;
		}
		if(!RecordUtil.isIdentityRecord(record)) {
			logger.error("Record is not an identity record (has never been saved yet)");
			return false;
		}
		return true;
	}
	
	public static synchronized BaseRecord getCreateThumbnail(BaseRecord irecord, int width, int height) throws IndexException, ReaderException, FactoryException, IOException, FieldException, ValueException, ModelNotFoundException {
		//OrganizationContext oct = ioContext.getOrganizationContext(record.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if(irecord == null) {
			logger.error("Record is null");
			return null;
		}
		IOContext ctx = IOSystem.getActiveContext();
		BaseRecord record = irecord.copyRecord();
		//ctx.getReader().populate(record, new String[] {FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH});
		if(!canCreateThumbnail(record)) {
			logger.error("Unable to create a thumbnail from content type: " + record.get(FieldNames.FIELD_CONTENT_TYPE));
			return null;
		}
		
		//Query oq = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_ID, record.get(FieldNames.FIELD_OWNER_ID));
		//oq.planMost(false);
		BaseRecord owner = ctx.getRecordUtil().getRecordById(null, ModelNames.MODEL_USER, record.get(FieldNames.FIELD_OWNER_ID));
		if(owner == null) {
			logger.error("Null owner");
			return null;
		}
		
		String groupPath = record.get(FieldNames.FIELD_GROUP_PATH);
		if(groupPath == null) {
			logger.error("Null group path");
			return null;
		}
		
		String thumbPath = groupPath;
		/// Thumb path should use the same path as the record
		///
		//BaseRecord thumbDir = ctx.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, thumbPath, GroupEnumType.DATA.toString(), owner.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord thumbDir = ctx.getAccessPoint().findById(owner, ModelNames.MODEL_GROUP, irecord.get(FieldNames.FIELD_GROUP_ID));
		if (thumbDir == null) {
			logger.error("Unable to find group for thumbnail in group " + irecord.get(FieldNames.FIELD_GROUP_ID));
			//logger.error(irecord.toString());
			ErrorUtil.printStackTrace();
			return null;
		}
		String thumbName = record.get(FieldNames.FIELD_NAME) + " " + width + "x" + height;
		//BaseRecord thumb = ioContext.getSearch().findByPath(owner, thumbPath, thumbName,  owner.get(FieldNames.FIELD_ORGANIZATION_ID));
		// logger.info(owner.toFullString());
		//BaseRecord thumb = ctx.getAccessPoint().findByNameInGroup(owner, ModelNames.MODEL_THUMBNAIL, thumbDir.get(FieldNames.FIELD_OBJECT_ID), thumbName);
		
		
		Query tq = QueryUtil.createQuery(ModelNames.MODEL_THUMBNAIL, FieldNames.FIELD_GROUP_ID, thumbDir.get(FieldNames.FIELD_ID), owner.get(FieldNames.FIELD_ORGANIZATION_ID));
		tq.field(FieldNames.FIELD_NAME, thumbName);
		tq.planMost(true);
		BaseRecord thumb = ctx.getAccessPoint().find(owner, tq);
		if(thumb != null) {
			String cobj = record.get(FieldNames.FIELD_OBJECT_ID);
			String mobj = thumb.get(FieldNames.FIELD_REFERENCE_ID);
			if(cobj != null && cobj.equals(mobj)) {
				return thumb;
			}
			else {
				logger.warn("Orphaned thumbnail detected: Re-writing");
			}
		}

		logger.info("Creating thumbnail " + thumbName + " in " + thumbPath);
		// ctx.getReader().populate(record);
		ctx.getReader().populate(record, new String[] {FieldNames.FIELD_BYTE_STORE});
		byte[] imageBytes = ByteModelUtil.getValue(record);
		if(imageBytes.length == 0 && record.hasField(FieldNames.FIELD_STREAM)) {
			BaseRecord stream = record.get(FieldNames.FIELD_STREAM);
			// logger.info("Loading stream bytes ...");
			if(stream != null) {
				if(!stream.hasField(FieldNames.FIELD_OBJECT_ID)) {
					ctx.getReader().populate(stream);
				}
				StreamSegmentUtil ssu = new StreamSegmentUtil();
				// logger.info("Streaming bytes ... ");
				long size = stream.get(FieldNames.FIELD_SIZE);
				if(size == 0L) {
					logger.warn("Stream size marker is 0");
				}
				imageBytes = ssu.streamToEnd(stream.get(FieldNames.FIELD_OBJECT_ID), 0L, stream.get(FieldNames.FIELD_SIZE));
			}
		}
		if(imageBytes.length == 0) {
			logger.error("Data has no bytes or stream");
			return null;
		}

		byte[] thumbBytes = GraphicsUtil.createThumbnail(imageBytes, width, height);
		if(thumbBytes.length == 0 && imageBytes.length > 0) {
			logger.info("Image was not resized. Using source byte array.");
			thumbBytes = imageBytes;
		}
		if(thumbBytes.length == 0) {
			logger.error("Failed to generate thumbnail from " + imageBytes.length + " bytes");
			return null;
		}
		
		if(thumb == null) {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, thumbPath);
			plist.parameter(FieldNames.FIELD_NAME, thumbName);
			thumb = ctx.getFactory().newInstance(ModelNames.MODEL_THUMBNAIL, owner, null, plist);
		}

		thumb.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		thumb.set(FieldNames.FIELD_BYTE_STORE, thumbBytes);
		thumb.set(FieldNames.FIELD_REFERENCE_ID, record.get(FieldNames.FIELD_OBJECT_ID));
		thumb.set(FieldNames.FIELD_WIDTH, width);
		thumb.set(FieldNames.FIELD_HEIGHT, height);
		thumb = ctx.getAccessPoint().create(owner, thumb);
		
		return thumb;
	}
}
