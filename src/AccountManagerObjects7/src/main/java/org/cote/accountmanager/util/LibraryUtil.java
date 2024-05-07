package org.cote.accountmanager.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;

public class LibraryUtil {
	public static final Logger logger = LogManager.getLogger(LibraryUtil.class);
	private static String[] readOnly = new String[] {AccessSchema.SYSTEM_PERMISSION_READ};
	private static String[] readCreateUpdate = new String[] {AccessSchema.SYSTEM_PERMISSION_CREATE, AccessSchema.SYSTEM_PERMISSION_READ, AccessSchema.SYSTEM_PERMISSION_UPDATE};
	public static BaseRecord getCreateSharedLibrary(BaseRecord user, String name, boolean enableCRU) {
		return getCreateSharedGroup(user, "/lib/" + name, (enableCRU ? readCreateUpdate : readOnly), new String[] {PermissionEnumType.GROUP.toString(), PermissionEnumType.DATA.toString()});
	}
	
	public static BaseRecord getCreateSharedGroup(BaseRecord user, String path, String[] permissions, String[] types) {
		IOContext ctx = IOSystem.getActiveContext();
		OrganizationContext octx = ctx.getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if(octx == null) {
			logger.error("Failed to find organization context");
			return null;
		}
		BaseRecord dir = ctx.getPathUtil().findPath(octx.getAdminUser(), ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), octx.getOrganizationId());
		if(dir != null) {
			return dir;
		}
		dir = ctx.getPathUtil().makePath(octx.getAdminUser(), ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), octx.getOrganizationId());
		BaseRecord usersRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_USERS, RoleEnumType.USER.toString(), octx.getOrganizationId());
		for(String perm : permissions) {
			for(String type : types) {
				BaseRecord perm1 = ctx.getPathUtil().findPath(octx.getAdminUser(), ModelNames.MODEL_PERMISSION, "/" + perm, type, octx.getOrganizationId());
				ctx.getMemberUtil().member(octx.getAdminUser(), dir, usersRole, perm1, true);
			}
		}
		return dir;
	}
}
