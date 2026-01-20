package org.cote.rest.services;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.tools.ImageTagResponse;
import org.cote.accountmanager.tools.ImageTagUtil;
import org.cote.accountmanager.tools.TagResult;
import org.cote.accountmanager.tools.VoiceRequest;
import org.cote.accountmanager.tools.VoiceResponse;
import org.cote.accountmanager.util.BinaryUtil;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.ErrorUtil;
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
}
