package org.cote.rest.services;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatResponse;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.SystemTaskUtil;
import org.cote.service.util.ServiceUtil;

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
