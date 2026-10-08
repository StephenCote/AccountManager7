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
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
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
		CacheService.clearAuthorizationCache(request);
		CacheService.clearCaches();
		RecordFactory.cleanupOrphans(null);
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		util.vacuum();
		return Response.status(200).entity(true).build();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/vacuum")
	@Produces(MediaType.APPLICATION_JSON)
	public Response vacuum(@Context HttpServletRequest request, @Context HttpServletResponse response){
		CacheService.clearAuthorizationCache(request);
		CacheService.clearCaches();
		DBUtil util = IOSystem.getActiveContext().getDbUtil();
		util.vacuum();
		return Response.status(200).entity(true).build();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z0-9\\.]+}/{objectId:[0-9A-Za-z\\-]+}")
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
	@Path("/{type:[A-Za-z0-9\\.]+}/{objectId:[0-9A-Za-z\\-]+}/full")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getFullModelByObjectId(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query q = QueryUtil.createQuery(type, FieldNames.FIELD_OBJECT_ID, objectId);
		q.planMost(true);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(rec == null) {
			return Response.status(404).entity(null).build();
		}

		return Response.status(200).entity(rec.toFullString()).build();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z0-9\\.]+}/user/{objectType:[A-Za-z]+}")
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
	@Path("/{type:[A-Za-z0-9\\.]+}/{objectId:[0-9A-Za-z\\-]+}")
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
		if(user == null) {
			return Response.status(401).entity(errorBody("Not authenticated")).build();
		}
		BaseRecord imp = JSONUtil.importObject(json,  LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		if(imp == null || imp.getSchema() == null) {
			return Response.status(400).entity(errorBody("Patch body could not be deserialized; the 'schema' field is required")).build();
		}
		ModelSchema ms = RecordFactory.getSchema(imp.getSchema());
		if(ms == null) {
			return Response.status(400).entity(errorBody("Unknown model '" + imp.getSchema() + "'")).build();
		}
		/// A patch is identified by id, objectId or urn; without one AccessPoint.update would treat the
		/// body as a CREATE, which is not what a PATCH caller asked for.
		if(!RecordUtil.isIdentityRecord(imp)) {
			return Response.status(400).entity(errorBody("Patch requires an identity field (id, objectId, or urn)")).build();
		}
		/// Same pre-flight deleteModel performs: a target that cannot be found (or read) is a 404, not a
		/// policy failure on a phantom record.
		Query iq = identityQuery(ms, imp);
		if(iq == null || IOSystem.getActiveContext().getAccessPoint().find(user, iq) == null) {
			return Response.status(404).entity(errorBody("Record not found")).build();
		}

		AuditUtil.clearLastAudit();
		BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(user, imp);
		if(updated == null) {
			/// The reason lives in the audit AccessPoint closed: DENY is an authorization outcome (403);
			/// INVALID is a locked field or a writer/validation rejection of the patch itself (422).
			ResponseEnumType ret = AuditUtil.getLastAuditResponse();
			String msg = AuditUtil.getLastAuditMessage();
			int status = (ret == ResponseEnumType.DENY ? 403 : 422);
			logger.warn("PATCH " + imp.getSchema() + " rejected (" + ret + "): " + msg);
			return Response.status(status).entity(errorBody((msg != null ? msg : "Update was not applied") + " (" + ret + ")")).build();
		}
		if(ms.isVectorize() && VectorUtil.isVectorSupported()) {
			try {
				IOSystem.getActiveContext().getVectorUtil().createVectorStore(imp, ChunkEnumType.WORD, 500);
			} catch (FieldException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		return Response.status(200).entity(true).build();
	}

	private static String errorBody(String message) {
		return "{\"error\":" + JSONUtil.exportObject(message) + "}";
	}

	/// Build the by-identity lookup for a patch body, preferring id, then objectId, then urn, and
	/// projecting only the identity/ownership fields the model actually defines.
	private static Query identityQuery(ModelSchema ms, BaseRecord imp) {
		Query q = null;
		Long id = (imp.hasField(FieldNames.FIELD_ID) ? imp.get(FieldNames.FIELD_ID) : null);
		String objectId = (imp.hasField(FieldNames.FIELD_OBJECT_ID) ? imp.get(FieldNames.FIELD_OBJECT_ID) : null);
		String urn = (imp.hasField(FieldNames.FIELD_URN) ? imp.get(FieldNames.FIELD_URN) : null);
		if(id != null && id.longValue() > 0L) {
			q = QueryUtil.createQuery(ms.getName(), FieldNames.FIELD_ID, id.longValue());
		}
		else if(objectId != null && objectId.length() > 0) {
			q = QueryUtil.createQuery(ms.getName(), FieldNames.FIELD_OBJECT_ID, objectId);
		}
		else if(urn != null && urn.length() > 0) {
			q = QueryUtil.createQuery(ms.getName(), FieldNames.FIELD_URN, urn);
		}
		if(q == null) {
			return null;
		}
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
		for(String pf : pfields) {
			if(ms.hasField(pf)) {
				fields.add(pf);
			}
		}
		q.setRequest(fields.toArray(new String[0]));
		q.setCache(false);
		return q;
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
	@Path("/{type:[A-Za-z0-9\\.]+}/{parentId:[0-9A-Za-z\\-]+}/{name: [\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
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
	@Path("/{type:[A-Za-z0-9\\.]+}/parent/{parentId:[0-9A-Za-z\\-]+}/{name: [\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getGroupedObjectByNameInParent(@PathParam("type") String type, @PathParam("parentId") String parentId,@PathParam(FieldNames.FIELD_NAME) String name,@Context HttpServletRequest request){
		BaseRecord rec = ServiceUtil.generateRecordQueryResponse(type, parentId, name, request);
		return Response.status((rec == null ? 404 : 200)).entity((rec != null ? rec.toFullString() : null)).build();
	}
	
	@RolesAllowed({"user"})
	@GET
	@Path("/stream/{objectId:[0-9A-Za-z\\-]+}/{startIndex:[\\d]+}/{length:[\\d]+}")
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
		enforceContextOrganization(user, query);

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
		enforceContextOrganization(user, query);

		int count = IOSystem.getActiveContext().getAccessPoint().count(user, query);
		return Response.status(200).entity(count).build();
	}

	/**
	 * SECURITY: scope a client-supplied query to the context user's organization when the client did not
	 * provide one. Without this, count/list of org-scoped types that are NOT container-bound (e.g. system.user)
	 * returns/counts records across ALL organizations (cross-org data leak; also makes membership pickers
	 * filter members globally rather than per-org). Only applied when the model actually carries an
	 * organizationId field and the query has not already constrained it.
	 */
	private static void enforceContextOrganization(BaseRecord user, Query query) {
		if(user == null || query == null) {
			return;
		}
		String type = query.getType();
		if(type == null) {
			return;
		}
		ModelSchema ms = RecordFactory.getSchema(type);
		if(ms == null || !ms.hasField(FieldNames.FIELD_ORGANIZATION_ID)) {
			return;
		}
		if(!query.hasQueryField(FieldNames.FIELD_ORGANIZATION_ID)) {
			Object orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
			if(orgId != null) {
				query.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			}
		}
	}
}
