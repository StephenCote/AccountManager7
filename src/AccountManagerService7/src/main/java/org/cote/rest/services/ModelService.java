package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
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
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin","user"})
@Path("/model")
public class ModelService {

	private static final Logger logger = LogManager.getLogger(ModelService.class);

	@RolesAllowed({"user"})
	@GET
	@Path("/cleanup")
	@Produces(MediaType.APPLICATION_JSON)
	public Response cleanupOrphans(@Context HttpServletRequest request, @Context HttpServletResponse response){

		RecordFactory.cleanupOrphans(null);
		return Response.status(200).entity(true).build();
	}
	
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getModelByObjectId(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, type, objectId);

		if(rec == null) {
			return Response.status(404).entity(null).build();
		}

		return Response.status(200).entity(rec.toFullString()).build();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/user/{objectType:[A-Za-z]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getUserModelRoot(@PathParam("type") String type, @PathParam("objectType") String objectType, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord rec = IOSystem.getActiveContext().getPathUtil().findPath(user, type, "~/", objectType, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(rec == null) {
			return Response.status(404).entity(null).build();
		}

		return Response.status(200).entity(rec.toFullString()).build();
	}
	
	@RolesAllowed({"user"})
	@DELETE
	@Path("/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteModel(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query q = QueryUtil.createQuery(type, FieldNames.FIELD_OBJECT_ID, objectId);
		ModelSchema ms = RecordFactory.getSchema(type);
		String[] pfields = new String[] {
				FieldNames.FIELD_ID,
				FieldNames.FIELD_OWNER_ID,
				FieldNames.FIELD_PARENT_ID,
				FieldNames.FIELD_GROUP_ID,
				FieldNames.FIELD_OBJECT_ID,
				FieldNames.FIELD_URN,
				FieldNames.FIELD_ORGANIZATION_ID
		};
		List<String> fields = new ArrayList<>();
		for(String pf: pfields) {
			if(ms.hasField(pf)) {
				fields.add(pf);
			}
		}
		q.setRequest(fields.toArray(new String[0]));
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);

		if(rec == null) {
			logger.error("Failed to find: " + type + " " + objectId);
			return Response.status(404).entity(null).build();
		}

		boolean deleted = IOSystem.getActiveContext().getAccessPoint().delete(user, rec);
		if(ms.isVectorize() && deleted && VectorUtil.isVectorSupported()) {
			IOSystem.getActiveContext().getVectorUtil().deleteVectorStore(rec);
		}
		return Response.status(200).entity(deleted).build();
	}
	
	@RolesAllowed({"user"})
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
		ModelSchema ms = RecordFactory.getSchema(imp.getSchema());

		if(IOSystem.getActiveContext().getAccessPoint().update(user, imp) != null) {
			patched = true;
			if(ms.isVectorize() && VectorUtil.isVectorSupported()) {
				try {
					IOSystem.getActiveContext().getVectorUtil().createVectorStore(imp, ChunkEnumType.WORD, 500);
				} catch (FieldException e) {
					logger.error(e);
					e.printStackTrace();
				}
			}

		}
		return Response.status(200).entity(patched).build();
	}
	
	@RolesAllowed({"user"})
	@POST
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response createModel(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(imp == null) {
			return Response.status(404).entity(null).build();
		}

		ModelSchema schema = RecordFactory.getSchema(imp.getSchema());
		BaseRecord op = null;
		BaseRecord oop = null;
		List<String> outFields = new ArrayList<>();

		try {
			op = IOSystem.getActiveContext().getFactory().newInstance(imp.getSchema(), user, imp, null);
			
			oop = IOSystem.getActiveContext().getAccessPoint().create(user, op);
			if(oop == null) {
				logger.error("Failed to create record");
				op = null;
			}
			else {
				for(FieldType f : oop.getFields()) {
					FieldSchema fs = schema.getFieldSchema(f.getName());
					if(fs.isIdentity() || fs.getName().equals(FieldNames.FIELD_GROUP_ID) || fs.getName().equals(FieldNames.FIELD_PARENT_ID)) {
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
			ModelSchema ms = RecordFactory.getSchema(oop.getSchema());
			if(ms.isVectorize() && VectorUtil.isVectorSupported()) {
				try {
					IOSystem.getActiveContext().getVectorUtil().createVectorStore(oop, ChunkEnumType.WORD, 500);
				} catch (FieldException e) {
					logger.error(e);
					e.printStackTrace();
				}
			}

		}
		return Response.status(200).entity(ops).build();
	}
	
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/{parentId:[0-9A-Za-z\\-]+}/{name: [\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getObjectByNameInParent(@PathParam("type") String type, @PathParam("parentId") String parentId,@PathParam(FieldNames.FIELD_NAME) String name,@Context HttpServletRequest request){
		BaseRecord rec = ServiceUtil.generateRecordQueryResponse(type, parentId, name, request);
		return Response.status((rec == null ? 404 : 200)).entity((rec != null ? rec.toFullString() : null)).build();
	}
	


	
	/// Specifically to allow for the variation where a factory is clustered by both group and parent
	/// To retrieve an object using a parent id vs. the group id
	///
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/parent/{parentId:[0-9A-Za-z\\-]+}/{name: [\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getGroupedObjectByNameInParent(@PathParam("type") String type, @PathParam("parentId") String parentId,@PathParam(FieldNames.FIELD_NAME) String name,@Context HttpServletRequest request){
		BaseRecord rec = ServiceUtil.generateRecordQueryResponse(type, parentId, name, request);
		return Response.status((rec == null ? 404 : 200)).entity((rec != null ? rec.toFullString() : null)).build();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/stream/{objectId:[0-9A-Za-z\\-]+}/{startIndex:[\\\\d]+}/{length:[\\\\d]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getStreamSegment(@PathParam("objectId") String objectId,@PathParam(FieldNames.FIELD_NAME) String name, @PathParam("startIndex") long startIndex, @PathParam("length") int length, @Context HttpServletRequest request){
		BaseRecord rseg = null;
		try{
			rseg = IOSystem.getActiveContext().getReader().read(new StreamSegmentUtil().newSegment(objectId, startIndex, length));
		}
		catch(ReaderException e) {
			logger.error(e);
		}
		return Response.status((rseg == null ? 404 : 200)).entity((rseg != null ? rseg.toFullString() : null)).build();
	}
	
	@RolesAllowed({"user"})
	@POST
	@Path("/search")
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
			ops = qr.toFullString();
		}
		return Response.status(200).entity(ops).build();
	}
	
	@RolesAllowed({"user"})
	@POST
	@Path("/search/count")
	@Produces(MediaType.APPLICATION_JSON)
	public Response searchCount(String json, @Context HttpServletRequest request, @Context HttpServletResponse response){

		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(imp == null) {
			return Response.status(404).entity(null).build();
		}
		Query query = new Query(imp);
		
		int count = IOSystem.getActiveContext().getAccessPoint().count(user, query);
		return Response.status(200).entity(count).build();
	}
}
