package org.cote.rest.services;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.schema.SchemaUtil;


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
