package org.cote.accountmanager.util;

import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;

public class ApplicationUtil {
	private static final Logger logger = LogManager.getLogger(ApplicationUtil.class);
	
	public static BaseRecord getUserPerson(BaseRecord user) {
		
		BaseRecord person = null;
		if(user != null){
			OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(oc.getAdminUser(), ModelNames.MODEL_GROUP, "/Persons", GroupEnumType.DATA.toString(), oc.getOrganizationId());
			person = IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_PERSON, dir.get(FieldNames.FIELD_OBJECT_ID), user.get(FieldNames.FIELD_NAME));
		}
		else {
			logger.error("Context user or user were null");
		}
		return person;

	}
	public static BaseRecord getApplicationProfile(BaseRecord user) {
		BaseRecord app = null;
		try {
			app = RecordFactory.newInstance(ModelNames.MODEL_APPLICATION_PROFILE);
			long organizationId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
			logger.info("Application profile for " + user.get(FieldNames.FIELD_URN) + " in org " + organizationId);
			Query rquery = QueryUtil.createQuery(ModelNames.MODEL_ROLE, FieldNames.FIELD_PARENT_ID, 0L, organizationId);
			rquery.field(FieldNames.FIELD_NAME, ComparatorEnumType.NOT_EQUALS, AccessSchema.ROLE_HOME);
			BaseRecord[] roles = IOSystem.getActiveContext().getSearch().findRecords(rquery);
			Query pquery = QueryUtil.createQuery(ModelNames.MODEL_PERMISSION, FieldNames.FIELD_PARENT_ID, 0L, organizationId);
			pquery.field(FieldNames.FIELD_NAME, ComparatorEnumType.NOT_EQUALS, AccessSchema.ROLE_HOME);
			BaseRecord[] permissions = IOSystem.getActiveContext().getSearch().findRecords(pquery);
			List<BaseRecord> aroles = app.get(FieldNames.FIELD_SYSTEM_ROLES);
			List<BaseRecord> uroles = app.get(FieldNames.FIELD_USER_ROLES);
			List<BaseRecord> aperms = app.get(FieldNames.FIELD_SYSTEM_PERMISSIONS);
			aroles.addAll(Arrays.asList(roles));
			aperms.addAll(Arrays.asList(permissions));

			app.set(FieldNames.FIELD_USER, user);
			app.set(FieldNames.FIELD_PERSON, getUserPerson(user));
			List<BaseRecord> usrRoles = IOSystem.getActiveContext().getMemberUtil().getParticipations(user, ModelNames.MODEL_ROLE);
			uroles.addAll(usrRoles);
			app.set(FieldNames.FIELD_ORGANIZATION_PATH, user.get(FieldNames.FIELD_ORGANIZATION_PATH));
		} catch (FieldException | ValueException | ModelNotFoundException | IndexException | ReaderException e1) {
			logger.error(e1);
		}
		return app;

	}
	
}
