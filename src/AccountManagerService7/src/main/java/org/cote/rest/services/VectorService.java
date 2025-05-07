package org.cote.rest.services;

import java.util.ArrayList;
import java.util.List;

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
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;
import org.cote.service.util.ServiceUtil;

@DeclareRoles({"admin","user"})
@Path("/vector")
public class VectorService {

	private static final Logger logger = LogManager.getLogger(VectorService.class);
	@Context
	ServletContext context;
	
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
	@Path("/summarize/{chunkType:[A-Za-z\\.]+}/{chunkSize:[\\d]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response summarize(String json, @PathParam("chunkType") ChunkEnumType chunkType, @PathParam("chunkSize") int chunkSize, @Context HttpServletRequest request){
		ChatRequest chatReq = ChatRequest.importRecord(json);
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord chatConfig = OlioUtil.getFullRecord(chatReq.getChatConfig());
		BaseRecord promptConfig = OlioUtil.getFullRecord(chatReq.getPromptConfig());
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
				BaseRecord sumD = ChatUtil.createSummary(user, chatConfig, promptConfig, frec, chunkType, chunkSize, true, Boolean.parseBoolean(context.getInitParameter("task.defer.remote")));
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
	/*
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
	*/
}
