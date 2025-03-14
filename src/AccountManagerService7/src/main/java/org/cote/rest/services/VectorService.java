package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/vector")
public class VectorService {

	private static final Logger logger = LogManager.getLogger(VectorService.class);
	
	@RolesAllowed({"user"})
	@GET
	@Path("/vectorize/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}/{chunkType:[A-Za-z\\.]+}/{chunkSize:[\\d]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response vectorize(@PathParam("type") String type, @PathParam("objectId") String objectId, @PathParam("chunkType") ChunkEnumType chunkType, @PathParam("chunkSize") int chunkSize, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		boolean vectorized = false;
		try{
			vectorized = IOSystem.getActiveContext().getAccessPoint().vectorize(user, type, objectId, chunkType, chunkSize);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		return Response.status(200).entity(vectorized).build();
	}
	
}
