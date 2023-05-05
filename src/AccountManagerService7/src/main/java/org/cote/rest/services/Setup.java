package org.cote.rest.services;

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
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.rest.config.RestServiceConfig;

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
			OrganizationContext oct = IOSystem.getActiveContext().getOrganizationContext("/System", null);
			if(oct != null && !oct.isInitialized()) {
				for(String org : RestServiceConfig.DEFAULT_ORGANIZATIONS) {
					logger.info("Configuring " + OrganizationEnumType.valueOf(org.substring(1).toUpperCase()) + " " + org);
					OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(org, OrganizationEnumType.valueOf(org.substring(1).toUpperCase()));
					if(!oc.isInitialized()) {
						try {
							oc.createOrganization();
							BaseRecord admin = oc.getAdminUser();
							String credStr = new String((byte[])icred.get(FieldNames.FIELD_CREDENTIAL));
							ParameterList plist = ParameterUtil.newParameterList("password", credStr);
							plist.parameter("type", CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
							
							BaseRecord newCred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, admin, null, plist);
							logger.info("New cred");
							logger.info(JSONUtil.exportObject(admin, RecordSerializerConfig.getUnfilteredModule()));
							logger.info(JSONUtil.exportObject(newCred, RecordSerializerConfig.getUnfilteredModule()));
							IOSystem.getActiveContext().getRecordUtil().createRecord(newCred);
						} catch (NullPointerException | SystemException | FactoryException e) {
							logger.error(e);
							e.printStackTrace();
						}
					}
				}
				setup = true;
			}
		}


		return Response.status(200).entity(setup).build();
	}
	
}
