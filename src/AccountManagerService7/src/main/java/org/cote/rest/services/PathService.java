package org.cote.rest.services;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/path")

public class PathService {
	private static final Logger logger = LogManager.getLogger(PathService.class);
	
	private BaseRecord doMakeFind(String type, String objectType, String path, HttpServletRequest request, boolean make) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		logger.info("Request to find object from: " + type + " " + path + ", and if it doesn't exist then " + (make ? "make it" : "too bad"));
		/// Check for base64 encoded values, prefaced with B64-
		///
		if(path.startsWith("B64-")) {
			path = BinaryUtil.fromBase64Str(path.substring(4,path.length())).replaceAll("%3D", "=");
		}
		if(path.startsWith("~") == false && path.startsWith(".") == false){
			path = "/" + path;
			if(path.contains("..")) {
				path = path.replaceAll("\\.\\.", "/");
			}
			else{
				path = path.replace('.', '/');
			}
			logger.info("Alt path: " + path);
		}
		logger.info("Make find: " + path);
		BaseRecord rec = null;
		if(make) {
			rec = IOSystem.getActiveContext().getAccessPoint().make(user, type, path, objectType.toUpperCase());
		}
		else {
			rec = IOSystem.getActiveContext().getAccessPoint().find(user, type, path, objectType.toUpperCase());
		}
		return rec;
	}
	
	
	@RolesAllowed({"user", "admin"})
	@GET
	@Path("/find/{type:[A-Za-z\\.]+}/{objectType:[A-Za-z]+}/{path:[@\\.~\\/%\\sa-zA-Z_0-9\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response findObject(@PathParam("type") String type, @PathParam("objectType") String objectType, @PathParam("path") String path, @Context HttpServletRequest request){
		BaseRecord rec = doMakeFind(type, objectType, path, request, false);
		return Response.status(200).entity((rec == null ? null : rec.toFullString())).build();
	
	}
	
	@RolesAllowed({"user", "admin"})
	@GET
	@Path("/make/{type:[A-Za-z\\.]+}/{objectType:[A-Za-z]+}/{path:[@\\.~\\/%\\sa-zA-Z_0-9\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response makeFindObject(@PathParam("type") String type, @PathParam("objectType") String objectType, @PathParam("path") String path, @Context HttpServletRequest request){
		BaseRecord rec = doMakeFind(type, objectType, path, request, true);
		return Response.status(200).entity((rec == null ? null : rec.toFullString())).build();
	}

}
