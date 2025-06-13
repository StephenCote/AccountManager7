package org.cote.rest.services;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;

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
