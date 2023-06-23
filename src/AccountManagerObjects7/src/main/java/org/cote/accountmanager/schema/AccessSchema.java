package org.cote.accountmanager.schema;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;

public class AccessSchema {
	public static final String ROLE_MODEL_ADMINISTRATOR = "ModelAdministrators";
	public static final String ROLE_MODEL_READERS = "ModelReaders";
	public static final String ROLE_SYSTEM_ADMINISTRATOR = "SystemAdministrators";
	public static final String ROLE_DATA_ADMINISTRATOR = "DataAdministrators";
	public static final String ROLE_DATA_READERS = "DataReaders";
	public static final String ROLE_ACCOUNT_ADMINISTRATOR = "AccountAdministrators";
	public static final String ROLE_ACCOUNT_DEVELOPERS = "AccountDevelopers";
	public static final String ROLE_ACCOUNT_USERS_READERS = "AccountUsersReaders";	
	public static final String ROLE_ACCOUNT_USERS = "AccountUsers";
	public static final String ROLE_API_USERS = "ApiUsers";
	public static final String ROLE_ARTICLE_AUTHORS = "ArticleAuthors";
	public static final String ROLE_PERMISSION_READERS = "PermissionReaders";
	public static final String ROLE_ROLE_READERS = "RoleReaders";
	public static final String ROLE_ROLE_ADMINISTRATORS = "RoleAdministrators";
	public static final String ROLE_PERMISSION_ADMINISTRATORS = "PermissionAdministrators";
	public static final String ROLE_GROUP_READERS = "GroupReaders";
	public static final String ROLE_SCRIPT_EXECUTORS = "ScriptExecutors";
	public static final String ROLE_APPROVERS = "Approvers";
	public static final String ROLE_REQUESTERS = "Requesters";
	public static final String ROLE_REQUEST_READERS = "RequestReaders";
	public static final String ROLE_REQUEST_ADMINISTRATORS = "RequestAdministrators";
	
	public static final String[] SYSTEM_ROLE_NAMES = new String[]{
		ROLE_SYSTEM_ADMINISTRATOR, ROLE_DATA_ADMINISTRATOR, ROLE_DATA_READERS,ROLE_ARTICLE_AUTHORS,
		ROLE_ACCOUNT_ADMINISTRATOR, ROLE_ACCOUNT_DEVELOPERS, ROLE_ACCOUNT_USERS, ROLE_ACCOUNT_USERS_READERS, ROLE_API_USERS,ROLE_PERMISSION_ADMINISTRATORS,
		ROLE_PERMISSION_READERS, ROLE_ROLE_READERS, ROLE_ROLE_ADMINISTRATORS, ROLE_GROUP_READERS, ROLE_MODEL_READERS, ROLE_MODEL_ADMINISTRATOR, ROLE_SCRIPT_EXECUTORS,
		ROLE_APPROVERS, ROLE_REQUESTERS, ROLE_REQUEST_READERS, ROLE_REQUEST_ADMINISTRATORS
	};
	
	public static final String[] ROLE_TYPES = new String[] {
			"Account", "User", "Person"
		};

	public static final String[] SYSTEM_PERMISSION_NAMES = new String[] {
		"Create", "Read", "Update", "Delete", "Execute"
	};
	public static final String[] SYSTEM_PERMISSION_TYPES = new String[] {
		"Data", "Group", "Role", "Permission", "Account", "User", "Person", "Object", "Application"
	};

	public static BaseRecord getSystemRole(String name, String type, long organizationId) {
		return IOSystem.getActiveContext().getPathUtil().findPath(null, ModelNames.MODEL_ROLE, "/" + name, type, organizationId);
	}
	
	public static BaseRecord getSystemPermission(String name, String type, long organizationId) {
		return IOSystem.getActiveContext().getPathUtil().findPath(null, ModelNames.MODEL_PERMISSION, "/" + name, type, organizationId);
	}
	
	public static BaseRecord userRole(BaseRecord user) {
		return IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_ROLE, "~/", "USER", user.get(FieldNames.FIELD_ORGANIZATION_ID));
	}
	public static BaseRecord userPermission(BaseRecord user) {
		return IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_PERMISSION, "~/", "USER", user.get(FieldNames.FIELD_ORGANIZATION_ID));
	}
	
}
