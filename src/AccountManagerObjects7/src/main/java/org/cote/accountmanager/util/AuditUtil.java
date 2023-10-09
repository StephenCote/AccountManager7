package org.cote.accountmanager.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;

public class AuditUtil {
	public static final Logger logger = LogManager.getLogger(AuditUtil.class);
	
	public static String getAuditString(BaseRecord audit) {
		StringBuilder buff = new StringBuilder();
		ActionEnumType act = ActionEnumType.valueOf(audit.get(FieldNames.FIELD_ACTION));
		ResponseEnumType ret = ResponseEnumType.valueOf(audit.get(FieldNames.FIELD_RESPONSE));
		BaseRecord contextUser = audit.get(FieldNames.FIELD_CONTEXT_USER);
		BaseRecord resource = audit.get(FieldNames.FIELD_RESOURCE);
		BaseRecord subject = audit.get(FieldNames.FIELD_SUBJECT);
		String message = audit.get(FieldNames.FIELD_MESSAGE);
		String cname = "[anonymous]";
		String sname = "[anonymous subject]";
		String rname = null;

		if(contextUser != null) {
			cname = contextUser.getModel() + " " + contextUser.get(FieldNames.FIELD_NAME);
		}
		if(subject != null) {
			sname = subject.getModel() + " " + subject.get(FieldNames.FIELD_NAME);
		}
		if(resource != null) {
			rname = resource.getModel() + " ";
			if(resource.hasField(FieldNames.FIELD_NAME)) {
				rname += resource.get(FieldNames.FIELD_NAME);
			}
			else if(resource.hasField(FieldNames.FIELD_URN)) {
				rname += resource.get(FieldNames.FIELD_URN);
			}
			else if(resource.hasField(FieldNames.FIELD_ID)) {
				rname += resource.getModel() + " #" + resource.get(FieldNames.FIELD_ID);
			}
		}
		if(rname == null) {
			String query = audit.get(FieldNames.FIELD_QUERY);
			if(query != null) {
				rname = query;
			}
			else {
				rname = "[unknown resource]";
				// logger.info(audit.toFullString());
			}
		}
		buff.append(ret.toString() + " " + sname + " to " + act.toString() + " " + rname + " (via: " + cname + ")" + (message != null ? " " + message : ""));
		return buff.toString();
	}
	
	public static byte[] getAuditHash(BaseRecord audit) {
		BaseRecord contextUser = audit.get(FieldNames.FIELD_CONTEXT_USER);
		BaseRecord resource = audit.get(FieldNames.FIELD_RESOURCE);
		BaseRecord subject = audit.get(FieldNames.FIELD_SUBJECT);
		ResponseEnumType ret = ResponseEnumType.valueOf(audit.get(FieldNames.FIELD_RESPONSE));
		ActionEnumType act = ActionEnumType.valueOf(audit.get(FieldNames.FIELD_ACTION));
		return (
			(contextUser != null ? contextUser.hash() : "[anonymous]")
			+ "-" + ret.toString() + "-" + act.toString() + "-"
			+ (subject != null ? subject.hash() : "[anonymous]")
			+ "-" + (resource != null ? resource.hash() : "[null]")
		).getBytes();
	}
	
	public static BaseRecord startAudit(BaseRecord contextUser, ActionEnumType action, BaseRecord actor, BaseRecord resource) {
		BaseRecord audit = null;
		try {
			audit = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_AUDIT, contextUser, null, ParameterUtil.newParameterList(FieldNames.FIELD_ACTION, action), actor, resource);
			audit.set(FieldNames.FIELD_ORGANIZATION_PATH, contextUser.get(FieldNames.FIELD_ORGANIZATION_PATH));
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return audit;
	}
	public static void auditResource(BaseRecord audit, BaseRecord resource) {
		try {
			audit.set(FieldNames.FIELD_RESOURCE, resource);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}

	public static void query(BaseRecord audit, String query) {
		try {
			audit.set(FieldNames.FIELD_QUERY, query);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	public static void message(BaseRecord audit, String message) {
		try {
			audit.set(FieldNames.FIELD_MESSAGE, message);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
	public static void closeAudit(BaseRecord audit, PolicyResponseType prr, String msg) {
		ResponseEnumType ret = ResponseEnumType.UNKNOWN;
		if(prr == null) {
			ret = ResponseEnumType.INVALID;
		}
		else if(prr.getType() == PolicyResponseEnumType.PERMIT) {
			ret = ResponseEnumType.PERMIT;
		}
		else {
			ret = ResponseEnumType.DENY;
		}
		try {
			audit.set(FieldNames.FIELD_POLICY, prr);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		closeAudit(audit, ret, msg);
	}
	public static void closeAudit(BaseRecord audit, ResponseEnumType ret, String msg) {
		try {
			audit.set(FieldNames.FIELD_RESPONSE, ret);
			audit.set(FieldNames.FIELD_DESCRIPTION, getAuditString(audit));
			audit.set(FieldNames.FIELD_MESSAGE, msg);
			String orgPath = audit.get(FieldNames.FIELD_ORGANIZATION_PATH);
			if(orgPath != null) {
				OrganizationContext org = IOSystem.getActiveContext().getOrganizationContext(orgPath, null);
				if(org != null && org.isInitialized()) {
					byte[] hash = getAuditHash(audit);
					audit.set(FieldNames.FIELD_HASH, hash);
					byte[] sig = CryptoUtil.sign(org.getKeyStoreBean().getCryptoBean().getPrivateKey(), hash);
					audit.set(FieldNames.FIELD_SIGNATURE, sig);
				}
				else {
					logger.error("Failed to access organization " + audit.get(FieldNames.FIELD_ORGANIZATION_PATH));
					logger.error(audit.toFullString());
				}
			}
			else {
				logger.error("Organization path is null");
				StackTraceElement[] st = new Throwable().getStackTrace();
				for(int i = 0; i < st.length; i++) {
					logger.error(st[i].toString());
				}
			}
			IOSystem.getActiveContext().getRecordUtil().createRecord(audit);
			print(audit);
			
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	public static void print(BaseRecord audit) {
		String auditStr = getAuditString(audit);
		ResponseEnumType ret = ResponseEnumType.valueOf(audit.get(FieldNames.FIELD_RESPONSE));
		if(ret == ResponseEnumType.PERMIT) {
			logger.info("AUDIT " + auditStr);
		}
		else {
			logger.warn("AUDIT " + auditStr);
		}
	}
				
}
