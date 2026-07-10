package org.cote.rest.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.PageIndexUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/// REST surface for the PageIndex capability (AccountManagerObjects7's hierarchical TOC index), mirroring
/// VectorService's structure. Pure transport: every operation goes through the PBAC-gated AccessPoint
/// wrappers (pageIndex/pageIndexRetrieve/pageIndexTree/pageIndexCount/pageIndexDelete) added for this
/// purpose — never the unauthenticated PageIndexUtil methods directly. No business logic here.
///
/// The /node and /node/{objectId}/children endpoints stay bespoke on purpose (NOT the generic
/// /rest/model/data.pageIndexNode/{objectId} route): the generic route would project the pgvector VECTOR
/// embedding field and throw ReaderException, so these endpoints explicitly request
/// PageIndexUtil.safeNodeRequestFields() (never the embedding) via AccessPoint.find/list.
@DeclareRoles({"admin","user"})
@Path("/pageIndex")
public class PageIndexService {

	private static final Logger logger = LogManager.getLogger(PageIndexService.class);

	/// Resolve a single data.pageIndexNode by objectId with the safe (non-embedding) field projection,
	/// authorized via AccessPoint.find (canRead, using the node's groupId shortcut).
	private BaseRecord findSafeNode(BaseRecord user, String objectId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(PageIndexUtil.safeNodeRequestFields());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/build/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response build(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		boolean built = false;
		try {
			built = IOSystem.getActiveContext().getAccessPoint().pageIndex(user, type, objectId);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		return Response.status(200).entity(built).build();
	}

	@RolesAllowed({"user"})
	@POST
	@Path("/retrieve/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}/{count:[0-9]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response retrieve(String query, @PathParam("type") String type, @PathParam("objectId") String objectId, @PathParam("count") int count, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		List<BaseRecord> results = new ArrayList<>();
		try {
			results = IOSystem.getActiveContext().getAccessPoint().pageIndexRetrieve(user, type, objectId, query, count);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		return Response.status(200).entity(JSONUtil.exportObject(results, RecordSerializerConfig.getForeignUnfilteredModuleRecurse())).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/tree/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response tree(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		List<BaseRecord> nodes = new ArrayList<>();
		try {
			nodes = IOSystem.getActiveContext().getAccessPoint().pageIndexTree(user, type, objectId);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		return Response.status(200).entity(JSONUtil.exportObject(nodes, RecordSerializerConfig.getForeignUnfilteredModuleRecurse())).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/node/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response node(@PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord node = findSafeNode(user, objectId);
		if(node == null) {
			return Response.status(404).build();
		}
		return Response.status(200).entity(node.toFullString()).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/node/{objectId:[0-9A-Za-z\\-]+}/children")
	@Produces(MediaType.APPLICATION_JSON)
	public Response nodeChildren(@PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord node = findSafeNode(user, objectId);
		if(node == null) {
			return Response.status(404).build();
		}
		long parentId = node.get(FieldNames.FIELD_ID);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_PAGE_INDEX_NODE, FieldNames.FIELD_PARENT_ID, parentId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(PageIndexUtil.safeNodeRequestFields());
		q.setCache(false);
		QueryResult qr = IOSystem.getActiveContext().getAccessPoint().list(user, q);
		List<BaseRecord> children = new ArrayList<>();
		if(qr != null && qr.getResults() != null) {
			children.addAll(Arrays.asList(qr.getResults()));
		}
		return Response.status(200).entity(JSONUtil.exportObject(children, RecordSerializerConfig.getForeignUnfilteredModuleRecurse())).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/count/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response count(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		int count = 0;
		try {
			count = IOSystem.getActiveContext().getAccessPoint().pageIndexCount(user, type, objectId);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		return Response.status(200).entity(count).build();
	}

	@RolesAllowed({"user"})
	@DELETE
	@Path("/{type:[A-Za-z\\.]+}/{objectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response delete(@PathParam("type") String type, @PathParam("objectId") String objectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		boolean deleted = false;
		try {
			deleted = IOSystem.getActiveContext().getAccessPoint().pageIndexDelete(user, type, objectId);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		return Response.status(200).entity(deleted).build();
	}
}
