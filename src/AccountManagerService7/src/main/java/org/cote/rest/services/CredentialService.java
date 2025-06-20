package org.cote.rest.services;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/credential")
public class CredentialService {
	public static final Logger logger = LogManager.getLogger(CredentialService.class);

	@RolesAllowed({"user", "admin", "api"})
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
	
	/// Any API User may request a token for an API principle
	/// 
	@RolesAllowed({"api", "admin"})
	@GET
	@Path("/token")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getApiToken(@Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(user.get(FieldNames.FIELD_ORGANIZATION_PATH), null);
		BaseRecord apiUser = oc.getAdminUser();
		String token = getTokenForUser(user, apiUser, request);
		return Response.status(200).entity(token).build();
	}
	
	@RolesAllowed({"api", "admin"})
	@POST
	@Path("/token/{type:[A-Za-z]+}/{objectId:[A-Za-z0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response getApiTokenForUser(@PathParam("objectId") String objectId, @Context HttpServletRequest request){
		/// Both the requester and the API user must be in the API Users role
		/// TODO: The requester MUST be able to AUTHENTICATE to the API user account
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query uq = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_OBJECT_ID, objectId);
		uq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		BaseRecord tuser = IOSystem.getActiveContext().getAccessPoint().find(user, uq);
		String token = null;
		if(tuser != null) {
			token = getTokenForUser(user, tuser, request);
		}
		return Response.status(200).entity(token).build();
	}
	
	private String getTokenForUser(BaseRecord user, BaseRecord apiUser, HttpServletRequest request){
		String outToken = null;
		try {
			outToken = TokenService.createJWTToken(apiUser, user, UUID.randomUUID().toString(), TokenService.TOKEN_EXPIRY_1_WEEK);
		} catch (ReaderException | IndexException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return outToken;
	}

}
