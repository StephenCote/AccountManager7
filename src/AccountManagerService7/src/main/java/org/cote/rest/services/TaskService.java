package org.cote.rest.services;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.SystemTaskUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@DeclareRoles({"admin","user", "apiUser"})
@Path("/task")
public class TaskService {
	
	private static final Logger logger = LogManager.getLogger(TaskService.class);
	
	@Context
	ServletContext context;
	
	@RolesAllowed({"admin","apiUser"})
	@GET
	@Path("/activate")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response activateTasks(@PathParam(FieldNames.FIELD_NAME) String name, @Context HttpServletRequest request){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		List<BaseRecord> pending = SystemTaskUtil.activateTasks();
		String ser = JSONUtil.exportObject(pending, RecordSerializerConfig.getForeignUnfilteredModule());
		return Response.status(200).entity(ser).build();
	}
	
	@RolesAllowed({"admin", "apiUser"})
	@POST
	@Path("/complete")
	@Produces(MediaType.APPLICATION_JSON)
	public Response completeTasks(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){
		List<BaseRecord> tasks = JSONUtil.getList(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		int completed = SystemTaskUtil.completeTasks(tasks);
		return Response.status(200).entity(completed).build();
		
	}

	

}
