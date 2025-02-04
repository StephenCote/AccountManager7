package org.cote.accountmanager.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.db.AuthorizationSchema;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelAccess;
import org.cote.accountmanager.schema.ModelAccessPolicies;
import org.cote.accountmanager.schema.ModelAccessPolicyBind;
import org.cote.accountmanager.schema.ModelAccessRoles;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.MemberUtil;
import org.cote.accountmanager.util.RecordUtil;

public class AuthorizationUtil {
	
	public static final Logger logger = LogManager.getLogger(AuthorizationUtil.class);
	private final IReader reader;
	private final MemberUtil memberUtil;
	private boolean trace = false;
	

	
	public AuthorizationUtil(IReader reader, IWriter writer, ISearch search) {
		this.reader = reader;
		memberUtil = new MemberUtil(reader, writer, search);
	}
	
	public AuthorizationUtil(IOContext context) {
		this.reader = context.getReader();
		this.memberUtil = context.getMemberUtil();
	}
	
	public boolean isTrace() {
		return trace;
	}

	
	public void createAuthorizationSchema() {
		/// Create functions
		ModelNames.MODELS.forEach(m -> {
			ModelSchema ms = RecordFactory.getSchema(m);
			if(!ms.isAbs() && ms.inherits(ModelNames.MODEL_PARENT)){
				AuthorizationSchema.createPathFunctions(AuthorizationSchema.getPathFunctions(m));
			}
		});
		/// Create views
		AuthorizationSchema.createEffectiveRoleViews();
	}


	public void setTrace(boolean trace) {
		this.trace = trace;
	}

	public void setEntitlement(BaseRecord adminUser, BaseRecord user, BaseRecord obj, String[] permNames, String entType) {
		setEntitlement(adminUser, user, new BaseRecord[] {obj}, permNames, new String[] {entType});
	}
	public void setEntitlement(BaseRecord adminUser, BaseRecord user, BaseRecord[] objs, String[] permNames, String[] entTypes) {
		for(BaseRecord obj : objs) {
			for(String entType : entTypes) {
				for(String p : permNames) {
					BaseRecord rperm1 = IOSystem.getActiveContext().getPathUtil().findPath(adminUser, ModelNames.MODEL_PERMISSION, "/" + p, entType, user.get(FieldNames.FIELD_ORGANIZATION_ID));
					if(rperm1 != null) {
						boolean mem = IOSystem.getActiveContext().getMemberUtil().member(adminUser, obj, user, rperm1, true);
						if(!mem && trace) {
							logger.warn("Failed to set member entitlement: " + p + " " + entType);
						}
					}
					else {
						logger.error("Failed to find perm " + p);
					}
				}
			}
		}

	}
	
	public boolean isModelAdministrator(String model, BaseRecord user) {
		ModelSchema ms = RecordFactory.getSchema(model);
		boolean isAdmin = false;
		List<String> roles = Optional.ofNullable(ms)
				.map(ModelSchema::getAccess)
				.map(ModelAccess::getRoles)
				.map(ModelAccessRoles::getAdmin)
				.orElse(new ArrayList<>())
		;
		OrganizationContext ctx = IOSystem.getActiveContext().getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		for(String s: roles) {
			BaseRecord role = IOSystem.getActiveContext().getPathUtil().findPath(ctx.getAdminUser(), ModelNames.MODEL_ROLE, "/" + s, RoleEnumType.USER.toString(), ctx.getOrganizationId());
			if(role != null && IOSystem.getActiveContext().getMemberUtil().isMember(user, role, null)) {
				isAdmin = true;
				break;
			}
		}
		return isAdmin;
	}


	public PolicyResponseType canCreate(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_CREATE_OBJECT, ActionEnumType.ADD, actor, null, resource);
	}
	public PolicyResponseType canDelete(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_DELETE_OBJECT, ActionEnumType.DELETE, actor, null, resource);
	}
	public PolicyResponseType canExecute(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_EXECUTE_OBJECT, ActionEnumType.EXECUTE, actor, null, resource);
	}
	public PolicyResponseType canRead(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canRead(contextUser, actor, null, resource);
	}
	public PolicyResponseType canRead(BaseRecord contextUser, BaseRecord actor, String token, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_READ_OBJECT, ActionEnumType.READ, actor, token, resource);
	}
	public PolicyResponseType canUpdate(BaseRecord contextUser, BaseRecord actor, BaseRecord resource) {
		return canDo(contextUser, PolicyUtil.POLICY_SYSTEM_UPDATE_OBJECT, ActionEnumType.MODIFY, actor, null, resource);
	}
	
	protected PolicyResponseType canDo(BaseRecord contextUser, String policyName, ActionEnumType action, BaseRecord actor, String token, BaseRecord inRec) {
		
		final BaseRecord resource = inRec.copyRecord();
		
		OrganizationContext org = IOSystem.getActiveContext().getOrganizationContext(contextUser.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		if(org == null) {
			logger.error("Failed to load organization context: " + contextUser.get(FieldNames.FIELD_ORGANIZATION_PATH));
			return null;
		}
		
		ModelSchema ms = RecordFactory.getSchema(resource.getModel());
		ModelAccessPolicyBind bind = Optional.ofNullable(ms)
				.map(ModelSchema::getAccess)
				.map(ModelAccess::getPolicies)
				.map(ModelAccessPolicies::getBind)
				.orElse(null)
		;
		
		reader.conditionalPopulate(resource, RecordUtil.getPossibleFields(resource.getModel(), new String[] {FieldNames.FIELD_URN, FieldNames.FIELD_NAME, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_TYPE, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_PARENT_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ID, FieldNames.FIELD_REFERENCE_ID, FieldNames.FIELD_REFERENCE_TYPE}));
		
		if(bind != null) {
			PolicyResponseType oprr = new PolicyResponseType();
			FieldSchema fs = ms.getFieldSchema(bind.getObjectId());
			String objId = null;
			long lobjId = 0L;
			if(fs.getType().equals("long")) {
				lobjId = resource.get(bind.getObjectId());
			}
			else {
				objId = resource.get(bind.getObjectId());
			}
			String model = bind.getModel();
			if(bind.getObjectModel() != null && resource.get(bind.getObjectModel()) != null) {
				model = resource.get(bind.getObjectModel());
			}
			BaseRecord refObj = null;
			if(model != null && objId != null) {
				refObj = IOSystem.getActiveContext().getAccessPoint().findByObjectId(contextUser, model, objId);
			}
			if(model != null && lobjId > 0L) {
				refObj = IOSystem.getActiveContext().getAccessPoint().findById(contextUser, model, lobjId);
			}
			if(refObj != null) {
				return canDo(contextUser, policyName, action, actor, token, refObj);
			}
			else {
				logger.warn("Orphan binding for " + model + " " + objId);
			}
		}
		
		PolicyResponseType prr = IOSystem.getActiveContext().getPolicyUtil().evaluateResourcePolicy(contextUser, policyName, actor, token, resource);
		
		return prr;
	}
	
	public boolean checkEntitlement(BaseRecord actor, BaseRecord permission, BaseRecord object) {
		
		if(trace) {
			logger.info("Check entitlement: " + permission.get(FieldNames.FIELD_NAME) + " " + permission.get(FieldNames.FIELD_TYPE) + " for " + object.getModel());
		}

		boolean outBool = false;
		List<BaseRecord> parts = new ArrayList<>();
		try {
			parts = memberUtil.findMembers(object, null, null, 0, permission.get(FieldNames.FIELD_ID));
		} catch (IndexException | ReaderException e) {
			logger.error(e);
			
		}
		if(trace) {
			logger.info("Find members with permission: " + parts.size());
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
						outBool = memberUtil.isMember(actor, role, null, true);
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
