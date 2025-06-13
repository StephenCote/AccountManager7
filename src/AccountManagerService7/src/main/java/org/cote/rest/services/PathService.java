package org.cote.rest.services;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;

@DeclareRoles({"admin","user"})
@Path("/path")

public class PathService {
	private static final Logger logger = LogManager.getLogger(PathService.class);
	
	private BaseRecord doMakeFind(String type, String objectType, String path, HttpServletRequest request, boolean make) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		/// Check for base64 encoded values, prefaced with B64-
		///
		if(path.startsWith("B64-")) {
			path = BinaryUtil.fromBase64Str(path.substring(4,path.length())).replaceAll("%3D", "=");
		}
		if(path.startsWith("~") == false && path.startsWith("/") == false){
			path = "/" + path;
			if(path.contains("..")) {
				path = path.replaceAll("\\.\\.", "/");
			}
			else{
				path = path.replace('.', '/');
			}
			logger.info("Alt path: " + path);
		}

		logger.info("Request to find object from: " + type + " " + objectType + " " + path + ", and if it doesn't exist then " + (make ? "make it" : "too bad"));
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
