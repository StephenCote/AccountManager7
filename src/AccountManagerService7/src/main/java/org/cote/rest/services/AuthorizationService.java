
package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;


@DeclareRoles({"admin","user"})
@Path("/authorization/{type:[\\.A-Za-z]+}")
public class AuthorizationService {
	
	@Context
	ServletContext context;
	
	@Context
	SecurityContext securityCtx;
	
	private static final Logger logger = LogManager.getLogger(AuthorizationService.class);

	@RolesAllowed({"admin","user"})
	@GET
	@Path("/{objectId:[0-9A-Za-z\\-]+}/member/{fieldName:[0-9A-Za-z\\\\-]+}/{actorType:[\\.A-Za-z]+}/{actorId:[0-9A-Za-z\\-]+}/{enable:(true|false)}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response enableMember(@PathParam("type") String objectType, @PathParam("objectId") String objectId, @PathParam("fieldName") String fieldName, @PathParam("actorType") String actorType, @PathParam("actorId") String actorId, @PathParam("enable") boolean enable, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord object = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, objectType, objectId);
		BaseRecord actor = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, actorType, actorId);
		boolean outBool = false;
		if(fieldName != null && (fieldName.length() == 0 || fieldName.equalsIgnoreCase("null"))) {
			fieldName = null;
		}
		if(object != null && actor != null) {
			outBool = IOSystem.getActiveContext().getAccessPoint().member(user, object, fieldName, actor, null, enable);
		}
		else {
			String objKey = objectType + " " + objectId;
			String actKey = actorType + " " + actorId;
			logger.warn((object == null ? objKey + " was null. ": "") + (actor == null ? actKey + " was null.": ""));
		}
		return Response.status(200).entity(outBool).build();
	}

	@RolesAllowed({"admin","user"})
	@GET @Path("/{objectId:[0-9A-Za-z\\-]+}/{actorType:[\\.A-Za-z]+}/count")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response countMembers(@PathParam("type") String objectType, @PathParam("objectId") String objectId, @PathParam("actorType") String actorType, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord object = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, objectType, objectId);
		logger.info("Counting " + actorType + " members in " + objectType + " " + objectId);
		int count = 0;
		if(object != null){
			count = IOSystem.getActiveContext().getAccessPoint().countMembers(user, object, actorType, null);
		}
		else {
			logger.error("Failed to read object " + objectType + " " + objectId);
		}
		return Response.status(200).entity(count).build();
	}
	
	@RolesAllowed({"admin","user"})
	@GET @Path("/{objectId:[0-9A-Za-z\\-]+}/{actorType:[\\.A-Za-z]+}/{startIndex:[\\d]+}/{count:[\\d]+}")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response members(@PathParam("type") String objectType, @PathParam("objectId") String objectId, @PathParam("actorType") String actorType, @PathParam("startIndex") long startIndex, @PathParam("count") int recordCount, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord object = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, objectType, objectId);
		List<BaseRecord> mems = new ArrayList<>();

		if(object != null){
			mems = IOSystem.getActiveContext().getAccessPoint().listMembers(user, object, actorType, null, startIndex, recordCount);
		}
		return Response.status(200).entity(JSONUtil.exportObject(mems, RecordSerializerConfig.getForeignUnfilteredModule())).build();
	}

	
}
