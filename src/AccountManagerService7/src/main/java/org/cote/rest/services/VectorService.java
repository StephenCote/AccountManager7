package org.cote.rest.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.security.DeclareRoles;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
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
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.cote.accountmanager.tools.ToolResponse;
import org.cote.accountmanager.util.ByteModelUtil;
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
	
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/summarize")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response summarize(String json, @Context HttpServletRequest request){
		logger.info(json);
		ChatRequest chatReq = ChatRequest.importRecord(json);
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chatConfig = ChatUtil.getConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatReq.getChatConfig(), null);
		boolean summarized = false;
		List<String> dataRef = chatReq.get(FieldNames.FIELD_DATA);
		if(dataRef.size() > 0) {
			String dataR = dataRef.get(0);
			BaseRecord recRef = RecordFactory.importRecord(dataR);
			String objId = recRef.get(FieldNames.FIELD_OBJECT_ID);
			Query rq = QueryUtil.createQuery(recRef.getSchema(), FieldNames.FIELD_OBJECT_ID, objId);
			rq.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			rq.planMost(true);
			BaseRecord frec = IOSystem.getActiveContext().getAccessPoint().find(user, rq);
			if(frec != null) {
				BaseRecord sumD = ChatUtil.createSummary(user, chatConfig, frec, true);
				summarized = (sumD != null);
			}
		}
		return Response.status(200).entity(summarized).build();
	}
	
	@RolesAllowed({"user"})
	@POST
	@Path("/reference/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}/{count:[0-9]+}/{dist:[0-9\\.]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response vectorReference(String statement, @PathParam("type") String type, @PathParam("objectId") String objectId, @PathParam("count") int count, @PathParam("dist") double dist, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		List<BaseRecord> vects = new ArrayList<>();
		BaseRecord rec = null;
		if(type.equals("null")) type = null;
		if(objectId.equals("null")) objectId = null;
		if(type != null && objectId != null) {
			rec = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, type, objectId);
			if(rec == null) {
				return Response.status(404).build();
			}
		}
		//String[] tables = new String[0];
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();
		if(type != null && type.equals(OlioModelNames.MODEL_CHAR_PERSON)) {
			vects.addAll(vu.find(rec, ModelNames.MODEL_DATA, new BaseRecord[0], new String[] {OlioModelNames.MODEL_VECTOR_CHAT_HISTORY}, statement, count, dist));
		}
		vects.addAll(vu.find(rec, type, statement, count, dist));
		List<BaseRecord> ovects = vu.sortAndLimit(vects, count);
		logger.info("Found " + ovects.size() + " chunks");
		return Response.status(200).entity(JSONUtil.exportObject(ovects, RecordSerializerConfig.getForeignUnfilteredModuleRecurse())).build();
	}
	
	@RolesAllowed({"user"})
	@POST
	@Path("/meta")
	@Produces(MediaType.APPLICATION_JSON)
	public Response metaData(String statement, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		return Response.status(200).entity(JSONUtil.exportObject(IOSystem.getActiveContext().getVectorUtil().getEmbedUtil().getMeta(statement))).build();
	}
	
	@GET
	@Path("/meta/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}/{count:[0-9]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response metaReference(@PathParam("type") String type, @PathParam("objectId") String objectId, @PathParam("count") int count, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().findByObjectId(user, type, objectId);
		if(rec == null) {
			return Response.status(404).build();
		}
		List<ToolResponse> trs = new ArrayList<>();
		
		
		VectorUtil vu = IOSystem.getActiveContext().getVectorUtil();

		String path = rec.get(FieldNames.FIELD_GROUP_PATH);
		if(rec.getSchema().equals(ModelNames.MODEL_DATA) && path != null && path.contains("/Chat")) {
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_VECTOR_CHAT_HISTORY, FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.setRequestRange(0, count);
			q.field(FieldNames.FIELD_VECTOR_REFERENCE, rec);
			q.field(FieldNames.FIELD_VECTOR_REFERENCE_TYPE, rec.getSchema());
			q.planMost(false, Arrays.asList(new String[] {FieldNames.FIELD_EMBEDDING}));
			BaseRecord[] chunks = IOSystem.getActiveContext().getSearch().findRecords(q);

			for(BaseRecord vchunk : chunks) {
				BaseRecord chunk = RecordFactory.importRecord(ModelNames.MODEL_VECTOR_CHUNK, vchunk.get("content"));
				ToolResponse tr = vu.getEmbedUtil().getMeta(chunk.get("content"));
				trs.add(tr);
			}
		}
		

		if(type != null && type.equals(OlioModelNames.MODEL_CHAR_PERSON)) {
//			vects.addAll(vu.find(null, ModelNames.MODEL_DATA, new String[] {OlioModelNames.MODEL_VECTOR_CHAT_HISTORY}, statement, count, dist));
		}

		return Response.status(200).entity(JSONUtil.exportObject(trs)).build();
	}
	
	/*
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

		if(IOSystem.getActiveContext().getAccessPoint().update(user, imp) != null) {
			patched = true;
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
		
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, query);
		String ops = null;
		if(qr != null) {
			ops = qr.toFullString();
		}
		return Response.status(200).entity(ops).build();
	}
	
	@RolesAllowed({"user"})
	@POST
	@Path("/count")
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
	*/
}
