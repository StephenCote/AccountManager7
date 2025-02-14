
package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;


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
	@Path("/{objectId:[0-9A-Za-z\\-]+}/member/{actorType:[\\.A-Za-z]+}/{actorId:[0-9A-Za-z\\-]+}/{enable:(true|false)}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response enableMember(@PathParam("type") String objectType, @PathParam("objectId") String objectId, @PathParam("actorType") String actorType, @PathParam("actorId") String actorId, @PathParam("enable") boolean enable, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord object = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, objectType, objectId);
		BaseRecord actor = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, actorType, actorId);
		boolean outBool = false;
		if(object != null && actor != null) {
			outBool = IOSystem.getActiveContext().getAccessPoint().member(user, object, actor, null, enable);
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
