package org.cote.rest.services;

import java.util.Map;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.graalvm.polyglot.PolyglotException;

@DeclareRoles({"admin","user"})
@Path("/script")
public class ScriptService {
	
	private static final Logger logger = LogManager.getLogger(ScriptService.class);
	@RolesAllowed({"admin", "user", "script"})
	@GET
	@Produces(MediaType.TEXT_PLAIN)
	@Path("/template")
	public Response getTemplate(@Context HttpServletRequest request){
		/// UserType user = ServiceUtil.getUserFromSession(request);
		String template = 
"""
/*jslint browser */
/*global console, logger, user*/
/// let AuditEnumType = Java.type("org.cote.accountmanager.objects.types.AuditEnumType");		
/// let GroupEnumType = Java.type("org.cote.accountmanager.objects.types.GroupEnumType");
/// let OperationResponseEnumType = Java.type("org.cote.accountmanager.objects.OperationResponseEnumType");
/// var BaseService =  Java.type("org.cote.accountmanager.service.rest.BaseService");
/// let GroupEnumType = Java.type("org.cote.accountmanager.objects.types.GroupEnumType");
/// let homeDirectory = BaseService.findGroup(user, GroupEnumType.DATA, "~");
/// let dirs = BaseService.listByGroup(AuditEnumType.GROUP, "DATA", homeDirectory.objectId, 0, 10, user);
/// logger.info("Found: " + dirs.length);
""";
		return Response.status(200).entity(template).build();	
		
	}
}
