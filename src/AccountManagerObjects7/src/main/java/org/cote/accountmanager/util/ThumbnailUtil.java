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
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;

public class ThumbnailUtil {
	
	public static final Logger logger = LogManager.getLogger(ThumbnailUtil.class);
	
	public static boolean canCreateThumbnail(BaseRecord record) {
		String contentType = record.get(FieldNames.FIELD_CONTENT_TYPE);
		if(contentType == null || !contentType.startsWith("image/")) {
			return false;
		}
		return true;
	}
	public static BaseRecord getCreateThumbnail(BaseRecord record, int width, int height) throws IndexException, ReaderException, FactoryException, IOException, FieldException, ValueException, ModelNotFoundException {
		//OrganizationContext oct = ioContext.getOrganizationContext(record.get(FieldNames.FIELD_ORGANIZATION_PATH), null);

		IOContext ctx = IOSystem.getActiveContext();
		ctx.getReader().populate(record);
		
		if(!canCreateThumbnail(record)) {
			logger.error("Unable to create a thumbnail from content type: " + record.get(FieldNames.FIELD_CONTENT_TYPE));
			return null;
		}
		
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
		
		String thumbPath = groupPath; // + "/.thumbnail";
		BaseRecord thumbDir = ctx.getPathUtil().makePath(owner, ModelNames.MODEL_GROUP, thumbPath, GroupEnumType.DATA.toString(), owner.get(FieldNames.FIELD_ORGANIZATION_ID));
		String thumbName = record.get(FieldNames.FIELD_NAME) + " " + width + "x" + height;
		//BaseRecord thumb = ioContext.getSearch().findByPath(owner, thumbPath, thumbName,  owner.get(FieldNames.FIELD_ORGANIZATION_ID));

		BaseRecord thumb = ctx.getAccessPoint().findByNameInGroup(owner, ModelNames.MODEL_THUMBNAIL, thumbDir.get(FieldNames.FIELD_OBJECT_ID), thumbName);
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

		
		byte[] imageBytes = record.get(FieldNames.FIELD_BYTE_STORE);
		if(imageBytes.length == 0 && record.hasField(FieldNames.FIELD_STREAM)) {
			BaseRecord stream = record.get(FieldNames.FIELD_STREAM);
			if(stream != null) {
				if(!stream.hasField(FieldNames.FIELD_OBJECT_ID)) {
					ctx.getReader().populate(stream);
				}
				StreamSegmentUtil ssu = new StreamSegmentUtil();
				imageBytes = ssu.streamToEnd(stream.get(FieldNames.FIELD_OBJECT_ID), 0L, stream.get(FieldNames.FIELD_SIZE));
			}
		}
		if(imageBytes.length == 0) {
			logger.error("Data has no bytes or stream");
			return null;
		}

		byte[] thumbBytes = GraphicsUtil.createThumbnail(imageBytes, width, height);
		if(thumbBytes.length == 0) {
			logger.error("Failed to generate thumbnail");
			return null;
		}
		
		if(thumb == null) {
			ParameterList plist = ParameterList.newParameterList("path", thumbPath);
			plist.parameter("name", thumbName);
			thumb = ctx.getFactory().newInstance(ModelNames.MODEL_THUMBNAIL, owner, null, plist);
		}

		thumb.set(FieldNames.FIELD_CONTENT_TYPE, record.get(FieldNames.FIELD_CONTENT_TYPE));
		thumb.set(FieldNames.FIELD_BYTE_STORE, thumbBytes);
		thumb.set(FieldNames.FIELD_REFERENCE_ID, record.get(FieldNames.FIELD_OBJECT_ID));
		thumb.set(FieldNames.FIELD_WIDTH, width);
		thumb.set(FieldNames.FIELD_HEIGHT, height);
		thumb = ctx.getAccessPoint().create(owner, thumb);
		
		return thumb;
	}
}
