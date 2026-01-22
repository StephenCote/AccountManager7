package org.cote.rest.services;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.tools.ImageTagResponse;
import org.cote.accountmanager.tools.ImageTagUtil;
import org.cote.accountmanager.tools.TagResult;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ThumbnailUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@DeclareRoles({"admin","user"})
@Path("/tag")
public class TagService {

	@Context
	ServletContext context;
	
	@Context
	SecurityContext securityCtx;
	
	private static final Logger logger = LogManager.getLogger(TagService.class);

	private BaseRecord getFullData(BaseRecord user, String model, String objectId, boolean bytes) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.planMost(false);
		if(bytes) {
			q.getRequest().add(FieldNames.FIELD_BYTE_STORE);
		}
		/*
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_OBJECT_ID,
				FieldNames.FIELD_URN, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_GROUP_ID,
				FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_STREAM,
				FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_MODIFIED_DATE });
		*/
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}
	
	private ImageTagResponse getTagResponse(BaseRecord user, String objectId) {
		ImageTagResponse imageTagResponse = null;
		BaseRecord data = getFullData(user, "data.data", objectId, true);
		if (data != null && data.get(FieldNames.FIELD_CONTENT_TYPE) != null && ((String) data.get(FieldNames.FIELD_CONTENT_TYPE)).startsWith("image/")) {
			try {
				BaseRecord thumb = ThumbnailUtil.getCreateThumbnail(data,  768, 768);
				if (thumb == null) {
					logger.error("Thumbnail generation failed for: " + objectId);
					return null;
				}
				String tid = (String) thumb.get(FieldNames.FIELD_OBJECT_ID);
				thumb = getFullData(user, "data.thumbnail", tid, true);
				if (thumb == null) {
					logger.error("Thumbnail retrieval failed for: " + tid);
					return null;
				}

				String base64 = BinaryUtil.toBase64Str(ByteModelUtil.getValue(thumb));
				
				ImageTagUtil itu = new ImageTagUtil(context.getInitParameter("tag.server"));
				imageTagResponse = itu.tagImageBase64(base64);
				if (imageTagResponse != null) {
					// logger.info(JSONUtil.exportObject(imageTagResponse), referenceId, request, user, imageTagResponse, data, thumb, tid, base64, itu);
				}
				else {
					logger.error("Image tagging failed for: " + objectId);
				}
			} catch (IOException | InterruptedException | ValueException | FieldException | IndexException | ReaderException | FactoryException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
		else {
			logger.error("Data not found or not an image: " + objectId);
		}
		return imageTagResponse;
	}
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/{referenceId:[\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response getImageTags(String json, @PathParam("referenceId") String referenceId, @Context HttpServletRequest request){
		logger.info("Get tags for " + referenceId);
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		ImageTagResponse imageTagResponse = getTagResponse(user, referenceId);
		return  Response.status((imageTagResponse != null ? 200 : 404)).entity(imageTagResponse != null ? JSONUtil.exportObject(imageTagResponse) : null).build();
	}
	
	@RolesAllowed({"admin","user"})
	@GET
	@Path("/apply/{referenceId:[\\(\\)@%\\sa-zA-Z_0-9\\-\\.]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response applyImageTags(String json, @PathParam("referenceId") String referenceId, @Context HttpServletRequest request){
		logger.info("Apply tags to " + referenceId);
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		ImageTagResponse imageTagResponse = getTagResponse(user, referenceId);
		if(imageTagResponse != null) {
			BaseRecord data = getFullData(user, "data.data", referenceId, false);
			if(data.get(FieldNames.FIELD_DESCRIPTION) == null) {
				String captionStr = null;
				if (imageTagResponse.getCaptions() != null && imageTagResponse.getCaptions().size() >= 2) {
					captionStr = imageTagResponse.getCaptions().get(imageTagResponse.getCaptions().size() - 2);
				}
				if (captionStr != null) {
					data.setValue(FieldNames.FIELD_DESCRIPTION, captionStr);
				}
				Queue.queueUpdate(data, new String[] { FieldNames.FIELD_DESCRIPTION });
				Queue.processQueue(user);
			}
			for(TagResult tag : imageTagResponse.getTags()) {
				//BaseRecord tag = DocumentUtil.getCreateTag(user, tagName.getTag());
				//logger.info("Applying tag: " + tag.getTag());
				DocumentUtil.applyTag(user, tag.getTag(), "data.data", data, true);
			}
		}
		return  Response.status((imageTagResponse != null ? 200 : 404)).entity(imageTagResponse != null ? JSONUtil.exportObject(imageTagResponse) : null).build();
	}

	/**
	 * Search for objects matching ALL of the provided tags.
	 */
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/search/{type:[A-Za-z\\.]+}")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response searchByTags(@PathParam("type") String type, String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query query = buildTagQuery(type, json, true);
		if (query == null) {
			return Response.status(400).entity(null).build();
		}
		QueryResult result = IOSystem.getActiveContext().getAccessPoint().list(user, query);
		return Response.status(200).entity(result != null ? result.toFullString() : null).build();
	}

	/**
	 * Count objects matching ALL of the provided tags.
	 */
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/search/{type:[A-Za-z\\.]+}/count")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response countByTags(@PathParam("type") String type, String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query query = buildTagQuery(type, json, true);
		if (query == null) {
			return Response.status(400).entity(null).build();
		}
		int count = IOSystem.getActiveContext().getAccessPoint().count(user, query);
		return Response.status(200).entity(count).build();
	}

	/**
	 * Search for objects matching ANY of the provided tags.
	 */
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/search/{type:[A-Za-z\\.]+}/any")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response searchByAnyTags(@PathParam("type") String type, String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query query = buildTagQuery(type, json, false);
		if (query == null) {
			return Response.status(400).entity(null).build();
		}
		QueryResult result = IOSystem.getActiveContext().getAccessPoint().list(user, query);
		return Response.status(200).entity(result != null ? result.toFullString() : null).build();
	}

	/**
	 * Count objects matching ANY of the provided tags.
	 */
	@RolesAllowed({"admin","user"})
	@POST
	@Path("/search/{type:[A-Za-z\\.]+}/any/count")
	@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
	public Response countByAnyTags(@PathParam("type") String type, String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		Query query = buildTagQuery(type, json, false);
		if (query == null) {
			return Response.status(400).entity(null).build();
		}
		int count = IOSystem.getActiveContext().getAccessPoint().count(user, query);
		return Response.status(200).entity(count).build();
	}

	private Query buildTagQuery(String type, String json, boolean matchAll) {
		BaseRecord[] tags = JSONUtil.importObject(json, LooseRecord[].class, RecordDeserializerConfig.getFilteredModule());
		if (tags == null || tags.length == 0) {
			return null;
		}

		List<String> tagIds = new ArrayList<>();
		for (BaseRecord tag : tags) {
			Long tagId = tag.get(FieldNames.FIELD_ID);
			if (tagId != null && tagId > 0) {
				tagIds.add(tagId.toString());
			}
		}
		if (tagIds.isEmpty()) {
			return null;
		}

		Query query = QueryUtil.createQuery(type);
		Query partQuery = QueryUtil.createParticipationQuery(null, null, null, null, null);
		partQuery.field(FieldNames.FIELD_PARTICIPATION_ID, ComparatorEnumType.IN, String.join(",", tagIds));
		partQuery.field(FieldNames.FIELD_PARTICIPATION_MODEL, ModelNames.MODEL_TAG);
		partQuery.field(FieldNames.FIELD_PARTICIPANT_MODEL, type);
		try {
			partQuery.set(FieldNames.FIELD_JOIN_KEY, FieldNames.FIELD_PARTICIPANT_ID);
			List<BaseRecord> joins = query.get(FieldNames.FIELD_JOINS);
			joins.add(partQuery);
			query.set(FieldNames.FIELD_GROUP_CLAUSE, StatementUtil.getAlias(query) + "." + FieldNames.FIELD_ID);
			if (matchAll) {
				query.set(FieldNames.FIELD_HAVING_CLAUSE, "COUNT(DISTINCT " + StatementUtil.getAlias(partQuery) + "." + FieldNames.FIELD_PARTICIPATION_ID + ") = " + tagIds.size());
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			return null;
		}
		return query;
	}
}
