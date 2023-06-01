package org.cote.rest.services;

import java.util.List;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.AuthenticationResponseEnumType;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.VerificationEnumType;
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/login")
public class LoginService {
	private static final Logger logger = LogManager.getLogger(LoginService.class);

	
	@POST
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response login(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord cred = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		logger.info("Import: " + json);
		
		boolean loginSuccess = true;
		
		try {
			if(cred == null) {
				logger.error("Null cred");
			}
			else {
				request.getSession();
				String name = cred.get(FieldNames.FIELD_NAME);
				logger.info("Cred Name: " + name);
				request.login(cred.get(FieldNames.FIELD_ORGANIZATION_PATH) + "/" + cred.get(FieldNames.FIELD_NAME), new String((byte[])cred.get(FieldNames.FIELD_CREDENTIAL)));
			}
		} catch (ServletException e) {
			
			logger.error(e);
			loginSuccess = false;
		}

		return Response.status(200).entity(loginSuccess).build();
	}
	

	@RolesAllowed({"user"})
	@GET
	@Path("/jwt/test")
	@Produces(MediaType.APPLICATION_JSON)
	public Response testToken(@Context HttpServletRequest request, @Context HttpServletResponse response) {
		logger.info("Test authenticated request");
		BaseRecord outResp = null;
		try {
			outResp = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_AUTHENTICATION_RESPONSE);
			outResp.set("response", AuthenticationResponseEnumType.AUTHENTICATED);
			BaseRecord user = ServiceUtil.getPrincipalUser(request);
			if(user != null) {
				outResp.set(FieldNames.FIELD_MESSAGE, user.get(FieldNames.FIELD_URN));
			}
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return Response.status(200).entity(JSONUtil.exportObject(outResp, RecordSerializerConfig.getFilteredModule())).build();
	}
	
	@POST
	@Path("/jwt/authenticate")
	@Produces(MediaType.APPLICATION_JSON)
	public Response authenticateForToken(String authnRequestStr, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord authnRequest = JSONUtil.importObject(authnRequestStr, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		BaseRecord outResp = getAuthenticatedToken(authnRequest, request);
		return Response.status(200).entity(JSONUtil.exportObject(outResp, RecordSerializerConfig.getFilteredModule())).build();
	}
	
	@POST
	@Path("/jwt/validate")
	@Produces(MediaType.APPLICATION_JSON)
	public Response validatePostJWT(String authnRequestStr, @Context HttpServletRequest request){
		BaseRecord authnRequest = JSONUtil.importObject(authnRequestStr, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		BaseRecord authnResponse = null;
		try {
			authnResponse = RecordFactory.newInstance(ModelNames.MODEL_AUTHENTICATION_RESPONSE);
			authnResponse.set(FieldNames.FIELD_RESPONSE, AuthenticationResponseEnumType.NOT_AUTHENTICATED);
			String credStr = new String((byte[])authnRequest.get(FieldNames.FIELD_CREDENTIAL));
			String subjectUrn = TokenService.validateTokenToSubject(credStr);
		
			if(subjectUrn != null){
				authnResponse.set(FieldNames.FIELD_RESPONSE, AuthenticationResponseEnumType.AUTHENTICATED);
				authnResponse.set(FieldNames.FIELD_MESSAGE, credStr);
			}
			else {
				logger.error("Failed to extract subject from token");
			}
		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		
		return Response.status(200).entity(authnResponse.toFilteredString()).build();
	}
	
	private BaseRecord getAuthenticatedToken(BaseRecord authnRequest, HttpServletRequest request){
		BaseRecord outResp = null;
		try {
			outResp = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_AUTHENTICATION_RESPONSE);
			outResp.set("response", AuthenticationResponseEnumType.NOT_AUTHENTICATED);
		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		if(outResp == null) {
			logger.error("Response object is null");
			return outResp;
		}

		String outToken = null;		
		
		if(authnRequest != null) {
			CredentialEnumType cet = CredentialEnumType.valueOf(authnRequest.get("credentialType"));
			if(cet == CredentialEnumType.UNKNOWN) {
				cet = CredentialEnumType.HASHED_PASSWORD;
			}
			BaseRecord subject = authnRequest.get(FieldNames.FIELD_SUBJECT);
			String orgPath = subject.get(FieldNames.FIELD_ORGANIZATION_PATH);
			if(orgPath == null) {
				orgPath = "/Public";
			}
			BaseRecord org = IOSystem.getActiveContext().getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, orgPath, null, 0L);
			if(org == null) {
				logger.error("Null organization");
			}
			else if(cet.equals(CredentialEnumType.TOKEN)) {
				logger.error("Handle token");
			}
			else if(cet.equals(CredentialEnumType.HASHED_PASSWORD)){
				
				String credStr = new String((byte[])authnRequest.get(FieldNames.FIELD_CREDENTIAL));
				String name = subject.get(FieldNames.FIELD_NAME);
				if(name == null) {
					name = subject.get(FieldNames.FIELD_URN);
					if(name == null) {
						logger.error("Invalid subject");
					}
				}
				String subType = subject.getModel();
				if(subType == null) {
					subType = ModelNames.MODEL_USER;
				}
				BaseRecord sub = null;
				if(subType.equals(ModelNames.MODEL_USER)) {
					sub = IOSystem.getActiveContext().getRecordUtil().getRecord(null, subType, name, 0L, 0L, org.get(FieldNames.FIELD_ID));
				}
				else {
					sub = IOSystem.getActiveContext().getRecordUtil().getRecordByUrn(null, subType, name);
				}
				if(sub == null) {
					logger.error("Subject " + subType + " " + name + " in " + org.get(FieldNames.FIELD_NAME) + " is null");
				}
				else {
					BaseRecord cred = IOSystem.getActiveContext().getRecordUtil().getRecordByQuery(
			    		IOSystem.getActiveContext().getRecordUtil().getLatestReferenceQuery(sub, ModelNames.MODEL_CREDENTIAL)
				    );
					if(cred == null) {
						logger.error("Credential is null");
					}
					else {
		        		VerificationEnumType vet = VerificationEnumType.UNKNOWN;
		        		try {
							vet = IOSystem.getActiveContext().getFactory().verify(sub, cred, ParameterUtil.newParameterList("password", credStr));
							if(vet == VerificationEnumType.VERIFIED) {
								outToken = TokenService.createJWTToken(sub);
								//outResp.set(FieldNames.FIELD_MESSAGE, outToken);
								List<String> toks = outResp.get("tokens");
								toks.add(outToken);
								outResp.set("response", AuthenticationResponseEnumType.AUTHENTICATED);
								logger.info("Verified credential. Issuing token: " + outToken);
							}
							else {
								logger.error("VET: " + vet);
							}
						} catch (FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException | IndexException e) {
							logger.error(e);
						}
					}
				}
				
			}

		}
		else {
			logger.error("Null request");
		}
		return outResp;
	}
	
}
