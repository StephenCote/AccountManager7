package org.cote.rest.services;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.schema.SchemaUtil;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;


@Path("/schema")
public class SchemaService {
	private static final Logger logger = LogManager.getLogger(SchemaService.class);
	
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSchema(@Context HttpServletRequest request, @Context HttpServletResponse response){
		return Response.status(200).entity(SchemaUtil.getSchemaJSON()).build();
	}
	
}
