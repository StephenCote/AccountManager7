package org.cote.rest.services;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/// REST surface for KI-17 (Gallery/Group export to ZIP), mirroring PageIndexService's structure and
/// ISO42001Service's "generate -> link -> download" pattern for the read side (resolve FK, populate byte
/// store, Content-Disposition response; 404-with-instructions if not yet generated). Pure transport: every
/// operation goes through the PBAC-gated AccessPoint wrappers (exportGroup/findGroupExport), never the
/// unauthenticated GroupExportUtil directly.
@DeclareRoles({"admin","user"})
@Path("/groupExport")
public class GroupExportService {

	private static final Logger logger = LogManager.getLogger(GroupExportService.class);

	@RolesAllowed({"user"})
	@POST
	@Path("/{type:[A-Za-z\\.]+}/{groupObjectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response build(@PathParam("type") String type, @PathParam("groupObjectId") String groupObjectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord container = null;
		try {
			container = IOSystem.getActiveContext().getAccessPoint().exportGroup(user, groupObjectId, type);
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		if(container == null) {
			return Response.status(404).entity("Export failed or produced nothing (no " + type + " children, or not authorized)").build();
		}
		return Response.status(200).entity(container.toFullString()).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/{groupObjectId:[0-9A-Za-z\\-]+}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response status(@PathParam("type") String type, @PathParam("groupObjectId") String groupObjectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord container = IOSystem.getActiveContext().getAccessPoint().findGroupExport(user, groupObjectId);
		if(container == null) {
			return Response.status(404).entity("No export exists for this group yet; POST /groupExport/" + type + "/" + groupObjectId + " to build one").build();
		}
		return Response.status(200).entity(container.toFullString()).build();
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/{type:[A-Za-z\\.]+}/{groupObjectId:[0-9A-Za-z\\-]+}/download")
	public Response download(@PathParam("type") String type, @PathParam("groupObjectId") String groupObjectId, @Context HttpServletRequest request, @Context HttpServletResponse response){
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		BaseRecord container = IOSystem.getActiveContext().getAccessPoint().findGroupExport(user, groupObjectId);
		if(container == null) {
			return Response.status(404).entity("No export exists for this group yet; POST /groupExport/" + type + "/" + groupObjectId + " to build one").build();
		}
		BaseRecord archiveRef = container.get(FieldNames.FIELD_ARCHIVE);
		if(archiveRef == null) {
			return Response.status(404).entity("Export container has no archive reference").build();
		}
		String archiveObjectId = archiveRef.get(FieldNames.FIELD_OBJECT_ID);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, archiveObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		/// ByteModelUtil.getValue() transparently decompresses/deciphers an inline byte store - but it
		/// decides HOW purely from fields on this SAME record instance (FIELD_COMPRESSION_TYPE,
		/// FIELD_VAULTED, FIELD_ENCIPHERED/FIELD_KEYS for cipher lookup), each read via
		/// `d.get(fieldName, default)` which silently falls back to its default ("NONE"/false) if the
		/// field wasn't in this query's projection. The original field list here omitted
		/// FIELD_COMPRESSION_TYPE, so compressionType always read back as "NONE" and gunzip was never
		/// invoked - a raw field read would then return the STILL-GZIP-COMPRESSED bytes whenever the
		/// persisted archive exceeds ByteModelUtil's 512-byte auto-compress threshold (real bug found
		/// while extending the Objects7-layer OOM fix's test coverage: "zip"'s registered content type,
		/// "multipart/x-zip", doesn't match any of ByteModelUtil.tryCompress()'s exemption prefixes, so
		/// ANY export over 512 bytes - i.e. almost any real-world one - is gzip-wrapped on persist and
		/// would download here as an unreadable file, not a plain .zip, without this fix).
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_STREAM,
			FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_VAULTED, FieldNames.FIELD_ENCIPHERED, FieldNames.FIELD_KEYS });
		BaseRecord archive = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(archive == null) {
			return Response.status(404).entity("Export archive not found or not accessible").build();
		}

		byte[] bytes = null;
		try {
			bytes = ByteModelUtil.getValue(archive);
		}
		catch(Exception e) {
			logger.error(e);
		}
		if(bytes == null || bytes.length == 0) {
			/// Larger archives are stream-backed (StreamUtil.streamToData's size cutoff) rather than
			/// held inline — reconstruct from the stream the same way GroupExportUtil/ExportAction do.
			BaseRecord stream = archive.get(FieldNames.FIELD_STREAM);
			if(stream != null) {
				StreamSegmentUtil ssu = new StreamSegmentUtil();
				bytes = ssu.streamToEnd(stream.get(FieldNames.FIELD_OBJECT_ID), 0, 0);
			}
		}
		if(bytes == null || bytes.length == 0) {
			return Response.status(404).entity("Export archive is empty").build();
		}
		String fileName = archive.get(FieldNames.FIELD_NAME);
		if(fileName == null || fileName.isEmpty()) {
			fileName = "export-" + groupObjectId + ".zip";
		}
		return Response.status(200).entity(bytes).type("application/zip")
			.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"").build();
	}
}
