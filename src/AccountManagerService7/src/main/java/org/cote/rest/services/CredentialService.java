package org.cote.rest.services;

import java.nio.charset.StandardCharsets;

import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.UriInfo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.service.util.ServiceUtil;

@Path("/credential")
public class CredentialService {
	public static final Logger logger = LogManager.getLogger(CredentialService.class);

	@RolesAllowed({"user", "admin"})
	@POST
	@Path("/{type:[A-Za-z]+}/{objectId:[A-Za-z0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public boolean newPrimaryCredential(@PathParam("type") String objectType, @PathParam("objectId") String objectId, String authReqJson,@Context HttpServletRequest request){

		BaseRecord authReq = JSONUtil.importObject(authReqJson,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord audit = AuditUtil.startAudit(user, ActionEnumType.MODIFY, null, null);
		BaseRecord owner = null;
		boolean outBool = false;

		BaseRecord newCred = null;
		try{
			BaseRecord targetObject = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, objectType, objectId);
			if(targetObject == null) {
				AuditUtil.closeAudit(audit, ResponseEnumType.INVALID, "Target object does not exist or is not accessible");
				return outBool;
			}

			/*
			 * To create a new user credential:
			 *    The authenticated user must be an account administrator
			 *    Or the current credential must be supplied
			 */
			//boolean accountAdmin = org.cote.accountmanager.data.services.AuthorizationService.is
			if(ModelNames.MODEL_USER.equals(objectType)) {
				BaseRecord cred = CredentialUtil.getLatestCredential(targetObject);
				
				ParameterList plist = ParameterUtil.newParameterList(FieldNames.FIELD_TYPE, authReq.get(FieldNames.FIELD_CREDENTIAL_TYPE));
				plist.parameter("password", "password");
				newCred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, targetObject, null, plist);
				boolean verify = false;
				if(cred == null) {
					logger.info("Create new credential");
					verify = true;
				}
				else {
					logger.info("Replace active credential");
					if(IOSystem.getActiveContext().getAuthorizationUtil().isModelAdministrator(objectType, user)) {
						logger.info("User is a model administrator");
						verify = true;
					}
					else {
						VerificationEnumType vet = IOSystem.getActiveContext().getFactory().verify(targetObject, cred, ParameterUtil.newParameterList("password", new String((byte[])cred.get(FieldNames.FIELD_CHECK_CREDENTIAL))));
						if(vet == VerificationEnumType.VERIFIED) {
							verify = true;
						}
						else {
							logger.error("Failed to verify current credential");
						}
					}
					
				}
				if(verify) {
					if(cred != null) {
						cred.set(FieldNames.FIELD_PRIMARY_KEY, false);
						IOSystem.getActiveContext().getRecordUtil().updateRecord(cred);
					}
					outBool = IOSystem.getActiveContext().getRecordUtil().createRecord(newCred);
				}
			}
			else {
				logger.warn("**** TODO: Handle model " + objectType);
			}
		}
		catch(NullPointerException | FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return outBool;

	}

}
