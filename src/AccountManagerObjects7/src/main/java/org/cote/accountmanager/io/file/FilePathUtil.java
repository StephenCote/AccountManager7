package org.cote.accountmanager.io.file;

import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.PathUtil;

public class FilePathUtil extends PathUtil {
	public FilePathUtil(FileReader reader) {
		super(reader, new FileSearch(reader));
	}
	public FilePathUtil(FileReader reader, FileWriter writer, FileSearch search) {
		super(reader, writer, search);
	}
	
	public static String getFilePath(IndexEntry entry) {
		
		long groupId = entry.getValue(FieldEnumType.LONG, FieldNames.FIELD_GROUP_ID, 0L);
		long parentId = entry.getValue(FieldEnumType.LONG, FieldNames.FIELD_PARENT_ID, 0L);
		long organizationId = entry.getValue(FieldEnumType.LONG, FieldNames.FIELD_ORGANIZATION_ID, 0L);
		long id = entry.getValue(FieldEnumType.LONG, FieldNames.FIELD_ID, 0L);
		String name = entry.getValue(FieldNames.FIELD_NAME, null);
		String oid = entry.getValue(FieldNames.FIELD_OBJECT_ID, null);
		String type = entry.getValue(FieldNames.FIELD_TYPE, null);
		String mtype = entry.get(FieldNames.FIELD_TYPE);

		String gop = Long.toString(groupId) + "." + Long.toString(parentId);
		
		return mtype + "/" + organizationId + "/" + gop + "/" + (name != null ? name : (oid != null ? oid : id)) + (type != null ? " " + type : "") + ".json";
		//return organizationId + "/" + mtype + "/" + gop + "/" + (name != null ? name : (oid != null ? oid : id)) + (type != null ? " " + type : "") + ".json";

		
		//String gop = Long.toString(entry.getGroupId()) + "." + Long.toString(entry.getParentId());
		//return entry.getOrganizationId() + "/" + entry.getModel() + "/" + gop + "/" + (entry.getName() != null ? entry.getName() : (entry.getObjectId() != null ? entry.getObjectId() : entry.getId())) + (entry.getType() != null ? " " + entry.getType() : "") + ".json";
	}
	
}
