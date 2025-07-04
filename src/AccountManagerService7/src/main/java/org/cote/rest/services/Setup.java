package org.cote.rest.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.security.CredentialUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/setup")
public class Setup {
	private static final Logger logger = LogManager.getLogger(Setup.class);

	

	@GET
	@Path("/reload")
	@Produces(MediaType.APPLICATION_JSON)
	public Response reset() {
		/*
		IOSystem.close();
		IOSystem.open(RecordIO.FILE, null, null);
		*/
		return Response.status(200).entity(true).build();
		
	}

	
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response checkSetup() {
		OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext("/System", null);
		return Response.status(200).entity((oc != null && oc.isInitialized())).build();
	}
	
	@POST
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response setup(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){

		BaseRecord icred = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		boolean setup = false;

		if(icred == null) {
			logger.error("Null cred");
		}
		else {
			for(String org : OrganizationContext.DEFAULT_ORGANIZATIONS) {
				logger.info("Configuring " + OrganizationEnumType.valueOf(org.substring(1).toUpperCase()) + " " + org);
				OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(org, OrganizationEnumType.valueOf(org.substring(1).toUpperCase()));
				try {
					if(!oc.isInitialized()) {
						oc.createOrganization();
					}
					BaseRecord admin = oc.getAdminUser();
					BaseRecord cred = CredentialUtil.getLatestCredential(admin);
					if(cred == null) {
						String credStr = new String((byte[])icred.get(FieldNames.FIELD_CREDENTIAL));
						ParameterList plist = ParameterUtil.newParameterList("password", credStr);
						plist.parameter(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
						BaseRecord newCred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, admin, null, plist);
						logger.info("New credential created");
						IOSystem.getActiveContext().getRecordUtil().createRecord(newCred);
					}
					else {
						logger.warn("Administrative credential already set");
					}

				} catch (NullPointerException | SystemException | FactoryException e) {
					logger.error(e);
					e.printStackTrace();
				}

			}
			setup = true;

		}


		return Response.status(200).entity(setup).build();
	}
	
}
