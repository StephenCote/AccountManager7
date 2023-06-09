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
import org.cote.accountmanager.exceptions.ReaderException;
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
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
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

		return Response.status(200).entity(rec.toFullString()).build();
	}
	
	@GET
	@Path("/{type:[A-Za-z]+}/user/{objectType:[A-Za-z]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getUserModelRoot(@PathParam("type") String type, @PathParam("objectType") String objectType, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord rec = IOSystem.getActiveContext().getPathUtil().findPath(user, type, "~/", objectType, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(rec == null) {
			return Response.status(404).entity(null).build();
		}

		return Response.status(200).entity(rec.toFullString()).build();
	}
	
	@DELETE
	@Path("/{type:[A-Za-z]+}/{objectId:[0-9A-Za-z\\\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteModel(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query q = QueryUtil.createQuery(type, FieldNames.FIELD_OBJECT_ID, objectId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN});
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		//BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());

		if(rec == null) {
			logger.error("Failed to find: " + type + " " + objectId);
			return Response.status(404).entity(null).build();
		}

		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(user, rec);
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
		logger.info(json);
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
			ops = oop.copyRecord(outFields.toArray(new String[0])).toFullString();
		}
		return Response.status(200).entity(ops).build();
	}
	
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z]+}/{parentId:[0-9A-Za-z\\-]+}/{name: [\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getObjectByNameInParent(@PathParam("type") String type, @PathParam("parentId") String parentId,@PathParam("name") String name,@Context HttpServletRequest request){
		BaseRecord rec = ServiceUtil.generateRecordQueryResponse(type, parentId, name, request);
		return Response.status((rec == null ? 404 : 200)).entity((rec != null ? rec.toFullString() : null)).build();
	}
	


	
	/// Specifically to allow for the variation where a factory is clustered by both group and parent
	/// To retrieve an object using a parent id vs. the group id
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z]+}/parent/{parentId:[0-9A-Za-z\\-]+}/{name: [\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getGroupedObjectByNameInParent(@PathParam("type") String type, @PathParam("parentId") String parentId,@PathParam("name") String name,@Context HttpServletRequest request){
		BaseRecord rec = ServiceUtil.generateRecordQueryResponse(type, parentId, name, request);
		return Response.status((rec == null ? 404 : 200)).entity((rec != null ? rec.toFullString() : null)).build();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/stream/{objectId:[0-9A-Za-z\\-]+}/{startIndex:[\\\\d]+}/{length:[\\\\d]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getStreamSegment(@PathParam("objectId") String objectId,@PathParam("name") String name, @PathParam("startIndex") long startIndex, @PathParam("length") int length, @Context HttpServletRequest request){
		BaseRecord rseg = null;
		try{
			rseg = IOSystem.getActiveContext().getReader().read(new StreamSegmentUtil().newSegment(objectId, startIndex, length));
		}
		catch(ReaderException e) {
			logger.error(e);
		}
		return Response.status((rseg == null ? 404 : 200)).entity((rseg != null ? rseg.toFullString() : null)).build();
	}
	
	/*
	@POST
	@Path("/list")
	@Produces(MediaType.APPLICATION_JSON)
	public Response search(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){
		logger.warn("**** MOVE TO LIST");
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(imp == null) {
			return Response.status(404).entity(null).build();
		}
		Query query = new Query(imp);
		
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, query);
		String ops = null;
		if(qr != null) {
			ops = qr.toFullString();
		}
		return Response.status(200).entity(ops).build();
	}
	*/
}
