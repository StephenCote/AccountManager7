package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.security.DeclareRoles;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.POST;
import javax.ws.rs.GET;
import javax.ws.rs.PATCH;
import javax.ws.rs.DELETE;
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
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.generated.PolicyType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/model")
public class ModelService {
	private static final Logger logger = LogManager.getLogger(ModelService.class);

	@GET
	@Path("/{type:[A-Za-z]+}/{objectId:[0-9A-Za-z\\\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getModelByObjectId(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, type, objectId);

		if(rec == null) {
			return Response.status(404).entity(null).build();
		}

		return Response.status(200).entity(rec.toFilteredString()).build();
	}
	
	@DELETE
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteModel(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());

		if(imp == null) {
			return Response.status(404).entity(null).build();
		}

		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(user, imp);
		return Response.status(200).entity(deleted).build();
	}
	
	@PATCH
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response patchModel(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){
		
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());

		boolean patched = false;
		if(imp == null) {
			return Response.status(404).entity(null).build();
		}

		if(IOSystem.getActiveContext().getAccessPoint().update(user, imp) != null) {
			patched = true;
		}
		return Response.status(200).entity(patched).build();
	}
	
	@POST
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response createModel(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){
		
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(imp == null) {
			return Response.status(404).entity(null).build();
		}
		ModelSchema schema = RecordFactory.getSchema(imp.getModel());
		BaseRecord op = null;
		BaseRecord oop = null;
		List<String> outFields = new ArrayList<>();

		try {
			op = IOSystem.getActiveContext().getFactory().newInstance(imp.getModel(), user, imp, null);
			oop = IOSystem.getActiveContext().getAccessPoint().create(user, op);
			if(oop == null) {
				logger.error("Failed to create record");
				op = null;
			}
			else {
				for(FieldType f : oop.getFields()) {
					FieldSchema fs = schema.getFieldSchema(f.getName());
					if(fs.isIdentity()) {
						outFields.add(f.getName());
					}
				}
			}
		}
		catch (FactoryException  e) {
			logger.error(e);
		}

		String ops = null;
		if(oop != null) {
			ops = oop.copyRecord(outFields.toArray(new String[0])).toFilteredString();
		}
		return Response.status(200).entity(ops).build();
	}
	
	@POST
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	public Response search(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){
		
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(imp == null) {
			return Response.status(404).entity(null).build();
		}
		Query query = new Query(imp);
		
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, query);
		String ops = null;
		if(qr != null) {
			ops = qr.toFilteredString();
		}
		return Response.status(200).entity(ops).build();
	}
}
