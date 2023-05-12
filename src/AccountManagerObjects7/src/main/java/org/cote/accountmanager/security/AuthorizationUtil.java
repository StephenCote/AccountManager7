package org.cote.accountmanager.security;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.util.MemberUtil;
import org.cote.accountmanager.util.RecordUtil;

public class AuthorizationUtil {
	
	public static final Logger logger = LogManager.getLogger(AuthorizationUtil.class);
	private final IReader reader;
	private final IWriter writer;
	private final ISearch search;
	private final RecordUtil recordUtil;
	private final MemberUtil memberUtil;
	private boolean trace = false;

	public AuthorizationUtil(IReader reader, IWriter writer, ISearch search) {
		this.reader = reader;
		this.writer = writer;
		this.search = search;
		recordUtil = new RecordUtil(reader, writer, search);
		memberUtil = new MemberUtil(reader, writer, search);
	}
	
	
	
	public boolean isTrace() {
		return trace;
	}



	public void setTrace(boolean trace) {
		this.trace = trace;
	}



	public PolicyResponseType canCreate(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_CREATE_OBJECT, ActionEnumType.ADD, actor, resource);
	}
	public PolicyResponseType canDelete(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_DELETE_OBJECT, ActionEnumType.DELETE, actor, resource);
	}
	public PolicyResponseType canExecute(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_EXECUTE_OBJECT, ActionEnumType.EXECUTE, actor, resource);
	}
	public PolicyResponseType canRead(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_READ_OBJECT, ActionEnumType.READ, actor, resource);
	}
	public PolicyResponseType canUpdate(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_UPDATE_OBJECT, ActionEnumType.MODIFY, actor, resource);
	}
	
	protected PolicyResponseType canDo(BaseRecord contextUser, String policyName, ActionEnumType action, BaseRecord actor, BaseRecord resource) {
		OrganizationContext org = IOSystem.getActiveContext().getOrganizationContext(contextUser.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		reader.populate(resource);
		if(org == null) {
			logger.error("Failed to load organization context: " + contextUser.get(FieldNames.FIELD_ORGANIZATION_PATH));
			return null;
		}

		// BaseRecord audit = AuditUtil.startAudit(contextUser, action, actor, resource);
		PolicyResponseType prr = IOSystem.getActiveContext().getPolicyUtil().evaluateResourcePolicy(contextUser, policyName, actor, resource);
		// AuditUtil.closeAudit(audit, prr);
		
		return prr;
		/*
		boolean success = (prr.getType() == PolicyResponseEnumType.PERMIT);
		
		return success;
		*/
	}
	
	public boolean checkEntitlement(BaseRecord actor, BaseRecord permission, BaseRecord object) {
		
		if(trace) {
			logger.info("Check entitlement: " + permission.get(FieldNames.FIELD_NAME) + " " + permission.get(FieldNames.FIELD_TYPE) + " for " + object.getModel() + " " + object.get(FieldNames.FIELD_NAME));
		}

		boolean outBool = false;
		List<BaseRecord> parts = new ArrayList<>();
		try {
			parts = memberUtil.findMembers(object, null, 0, permission.get(FieldNames.FIELD_ID));
		} catch (IndexException | ReaderException e) {
			logger.error(e);
			
		}
		if(trace) {
			logger.info("Parts: " + parts.size());
		}
		for(BaseRecord part : parts) {
			
			long partId = 0L;
			String type = null;

			if(part.inherits(ModelNames.MODEL_PARTICIPATION_ENTRY)) {
				partId = part.get(FieldNames.FIELD_PART_ID);
				type = part.get(FieldNames.FIELD_TYPE);
			}
			else {
				partId = part.get(FieldNames.FIELD_PARTICIPANT_ID);
				type = part.get(FieldNames.FIELD_PARTICIPANT_MODEL);
			}
			/// Direct assignment, allow for generic use of 'DATA' permission type to apply to all model types
			///
			if(actor.getModel().equals(type) || type.equals(PermissionEnumType.DATA.toString())) {
				long id = actor.get(FieldNames.FIELD_ID);
				if(id == partId) {
					outBool = true;
					break;
				}
			}
			if(ModelNames.MODEL_ROLE.equals(type)) {
				try {
					BaseRecord role = reader.read(type, partId);
					if(role != null) {
						outBool = memberUtil.isMember(actor, role, true);
					}
				} catch (ReaderException e) {
					logger.error(e);
					
				}
			}
			if(outBool) {
				break;
			}
		}
		
		if(!outBool && actor.inherits(ModelNames.MODEL_PERSON)) {
			///reader.populate(actor);
			List<BaseRecord> accounts = actor.get(FieldNames.FIELD_ACCOUNTS);
			for(BaseRecord acct : accounts) {
				outBool = checkEntitlement(acct, permission, object);
				if(outBool) {
					break;
				}
			}
		}
		
		// logger.info("Parts with entitlements: " + parts.size());
		
		return outBool;
	}
	
}
