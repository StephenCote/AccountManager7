package org.cote.rest.services;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.SchemaUtil;
import org.cote.accountmanager.util.JSONUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin","user"})
@Path("/schema")
public class SchemaService {
	private static final Logger logger = LogManager.getLogger(SchemaService.class);

	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSchema(@Context HttpServletRequest request, @Context HttpServletResponse response){
		return Response.status(200).entity(SchemaUtil.getSchemaJSON()).build();
	}

	@RolesAllowed({"admin"})
	@GET
	@Path("/models")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getModelNames(@Context HttpServletRequest request, @Context HttpServletResponse response){
		return Response.status(200).entity(JSONUtil.exportObject(ModelNames.MODELS)).build();
	}

	@RolesAllowed({"admin"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getModelSchema(@PathParam("type") String type, @Context HttpServletRequest request, @Context HttpServletResponse response){
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms == null) {
			return Response.status(404).entity(null).build();
		}
		return Response.status(200).entity(JSONUtil.exportObject(ms)).build();
	}

	@RolesAllowed({"admin"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/fields")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getModelFields(@PathParam("type") String type, @Context HttpServletRequest request, @Context HttpServletResponse response){
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms == null) {
			return Response.status(404).entity(null).build();
		}
		List<FieldSchema> fields = ms.getFields();
		return Response.status(200).entity(JSONUtil.exportObject(fields)).build();
	}

}
