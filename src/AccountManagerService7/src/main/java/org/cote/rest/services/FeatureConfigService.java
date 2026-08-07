package org.cote.rest.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.util.FeatureConfigUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/// PURE TRANSPORT over FeatureConfigUtil (Objects7). No feature catalogue, no default list, and no
/// storage logic lives here: architecture.md - "No business logic in Service7."
///
/// The enabled set is PER-ORGANIZATION (aiDocs/UxFeatureFlagDesign.md D1). The former per-user
/// implementation (a .featureConfig record in the CALLING user's home directory) made an admin's save
/// invisible to everyone else; those orphaned per-user records are deliberately not migrated.
///
/// Wire shapes are unchanged, so the client (AccountManagerUx752/src/core/am7client.js:704-722) needs
/// no change:
///   GET  /rest/config/features            -> {"features":[ids],"profile":"..."}
///   PUT  /rest/config/features            -> {"features":[ids],"profile":"..."}  (admin only)
///   GET  /rest/config/features/available  -> the manifest array, verbatim
@DeclareRoles({"admin", "user"})
@Path("/config")
public class FeatureConfigService {
	private static final Logger logger = LogManager.getLogger(FeatureConfigService.class);

	/// `profile` is a DISPLAY LABEL only (featureConfig.js:159 renders it; nothing branches on it).
	/// It is not persisted: FeatureConfigUtil stores ids, and this layer labels the resolved set as
	/// "full" when it equals the manifest default and "custom" otherwise.
	private static final String PROFILE_FULL = "full";
	private static final String PROFILE_CUSTOM = "custom";

	/// Build an error body through the SERIALIZER, never by concatenation, whenever the message embeds a
	/// value that came off the request. Hand-rolling `"{\"error\":\"...\" + value + "\"}"` emits malformed
	/// JSON the moment the value carries a quote, a backslash or a newline - the client's JSON.parse then
	/// throws and a precise 400 degrades into a generic "Failed to save" with the reason lost. The SHAPE is
	/// unchanged ({"error":"..."}), which TestFeatureConfigService asserts on.
	private static Response errorResponse(int status, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", message);
		return Response.status(status).entity(JSONUtil.exportObject(body)).build();
	}

	private static Response featuresResponse(List<String> features) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("features", features);
		result.put("profile", (FeatureConfigUtil.getDefaultFeatures().equals(features) ? PROFILE_FULL : PROFILE_CUSTOM));
		return Response.status(200).entity(JSONUtil.exportObject(result)).build();
	}

	/// GET /rest/config/features - the enabled features for the caller's organization
	@RolesAllowed({"user"})
	@GET
	@Path("/features")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getFeatureConfig(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401).entity(null).build();
		}
		return featuresResponse(FeatureConfigUtil.getEnabledFeatures(user));
	}

	/// PUT /rest/config/features - set the enabled features for the caller's organization (admin only).
	/// This role annotation is the real write gate.
	@RolesAllowed({"admin"})
	@PUT
	@Path("/features")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response updateFeatureConfig(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401).entity(null).build();
		}

		if (json == null || json.isEmpty()) {
			return errorResponse(400, "Empty request body");
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> incoming = JSONUtil.importObject(json, LinkedHashMap.class);
		if (incoming == null) {
			return errorResponse(400, "Invalid JSON");
		}

		Object featuresObj = incoming.get("features");
		if (!(featuresObj instanceof List)) {
			return errorResponse(400, "Missing or invalid 'features' array");
		}

		List<String> featureList = new ArrayList<>();
		List<String> invalid = new ArrayList<>();
		for (Object o : (List<?>) featuresObj) {
			String fid = (o != null ? o.toString() : null);
			if (!FeatureConfigUtil.isKnownFeature(fid)) {
				invalid.add(String.valueOf(fid));
			}
			else {
				featureList.add(fid);
			}
		}
		if (!invalid.isEmpty()) {
			/// The ids are request-supplied, so the serializer - not string concatenation - has to escape
			/// them. Same message text and same shape as before.
			return errorResponse(400, "Unknown feature IDs: " + String.join(", ", invalid));
		}

		/// 'core' inclusion and the deps closure are applied by FeatureConfigUtil.setEnabledFeatures.
		if (!FeatureConfigUtil.setEnabledFeatures(user, featureList)) {
			/// Deliberately does NOT echo the submitted ids: the failure reason is in the audit log, and the
			/// ids have already been validated against the manifest by this point.
			logger.error("Failed to store the feature configuration");
			return errorResponse(500, "Failed to save config");
		}

		/// Echo what was actually stored (core forced, deps closed), read back through the resolver.
		return featuresResponse(FeatureConfigUtil.getEnabledFeatures(user));
	}

	/// GET /rest/config/features/available - the feature manifest, served verbatim from the Objects7
	/// classpath resource. Single source of truth for id/label/description/required/deps.
	@RolesAllowed({"user"})
	@GET
	@Path("/features/available")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getAvailableFeatures(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401).entity(null).build();
		}
		String manifest = FeatureConfigUtil.getManifestJson();
		if (manifest == null) {
			logger.error("The Ux feature manifest resource could not be read");
			return errorResponse(500, "Feature manifest unavailable");
		}
		return Response.status(200).entity(manifest).build();
	}
}
