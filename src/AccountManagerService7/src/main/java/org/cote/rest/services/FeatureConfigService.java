package org.cote.rest.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
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

@DeclareRoles({"admin", "user"})
@Path("/config")
public class FeatureConfigService {
	private static final Logger logger = LogManager.getLogger(FeatureConfigService.class);

	private static final String CONFIG_NAME = ".featureConfig";

	/// Known feature definitions with id, label, description, deps
	private static final List<Map<String, Object>> AVAILABLE_FEATURES = new ArrayList<>();
	private static final Set<String> KNOWN_FEATURE_IDS = new HashSet<>();
	private static final List<String> DEFAULT_FEATURES = Arrays.asList(
		"core", "chat", "cardGame", "games", "testHarness", "iso42001", "biometrics", "schema", "webauthn", "accessRequests", "featureConfig", "pictureBook"
	);

	static {
		addFeature("core", "Core", "Object management, navigation, forms, lists", true, new String[]{});
		addFeature("chat", "LLM Chat", "Chat interface and LLM integration", false, new String[]{"core"});
		addFeature("cardGame", "Card Game", "RPG card game with AI director", false, new String[]{"core", "chat"});
		addFeature("games", "Mini Games", "Tetris, Word Game", false, new String[]{"core"});
		addFeature("testHarness", "Tests", "Automated testing UI", false, new String[]{"core"});
		addFeature("iso42001", "Compliance", "ISO 42001 AI compliance evaluation and bias detection", false, new String[]{"core", "chat"});
		addFeature("biometrics", "Biometrics", "Camera, mood ring, biometric theming", false, new String[]{"core"});
		addFeature("schema", "Schema", "Model schema browser and form editor (admin only)", false, new String[]{"core"});
		addFeature("webauthn", "Passkeys", "WebAuthn/FIDO2 passwordless authentication", false, new String[]{"core"});
		addFeature("accessRequests", "Access Requests", "Self-service access request and approval workflow", false, new String[]{"core"});
		addFeature("featureConfig", "Feature Config", "Server-side feature configuration (admin only)", false, new String[]{"core"});
		addFeature("pictureBook", "Picture Book", "Generate illustrated picture books from story/document objects", false, new String[]{"core"});
	}

	private static void addFeature(String id, String label, String description, boolean required, String[] deps) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("id", id);
		f.put("label", label);
		f.put("description", description);
		f.put("required", required);
		f.put("deps", Arrays.asList(deps));
		AVAILABLE_FEATURES.add(f);
		KNOWN_FEATURE_IDS.add(id);
	}

	/// GET /rest/config/features — return enabled features for current user's organization
	@RolesAllowed({"user"})
	@GET
	@Path("/features")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getFeatureConfig(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401).entity(null).build();
		}

		BaseRecord configRecord = findConfigRecord(user);
		if (configRecord == null) {
			/// Return default full profile
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("features", DEFAULT_FEATURES);
			result.put("profile", "full");
			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		}

		/// Read the description field which stores the JSON config
		String configData = configRecord.get("description");
		if (configData == null || configData.isEmpty()) {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("features", DEFAULT_FEATURES);
			result.put("profile", "full");
			return Response.status(200).entity(JSONUtil.exportObject(result)).build();
		}

		return Response.status(200).entity(configData).build();
	}

	/// PUT /rest/config/features — update enabled features (admin only)
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
			return Response.status(400).entity("{\"error\":\"Empty request body\"}").build();
		}

		/// Parse incoming config
		@SuppressWarnings("unchecked")
		Map<String, Object> incoming = JSONUtil.importObject(json, LinkedHashMap.class);
		if (incoming == null) {
			return Response.status(400).entity("{\"error\":\"Invalid JSON\"}").build();
		}

		/// Validate feature IDs
		Object featuresObj = incoming.get("features");
		if (featuresObj == null || !(featuresObj instanceof List)) {
			return Response.status(400).entity("{\"error\":\"Missing or invalid 'features' array\"}").build();
		}

		@SuppressWarnings("unchecked")
		List<String> featureList = (List<String>) featuresObj;
		List<String> invalid = new ArrayList<>();
		for (String fid : featureList) {
			if (!KNOWN_FEATURE_IDS.contains(fid)) {
				invalid.add(fid);
			}
		}
		if (!invalid.isEmpty()) {
			return Response.status(400).entity("{\"error\":\"Unknown feature IDs: " + String.join(", ", invalid) + "\"}").build();
		}

		/// Ensure 'core' is always included
		if (!featureList.contains("core")) {
			featureList.add(0, "core");
		}

		/// Build config JSON to store
		Map<String, Object> config = new LinkedHashMap<>();
		config.put("features", featureList);
		config.put("profile", incoming.containsKey("profile") ? incoming.get("profile") : "custom");
		String configJson = JSONUtil.exportObject(config);

		/// Find or create config record
		BaseRecord configRecord = findConfigRecord(user);
		if (configRecord != null) {
			/// Update existing record
			try {
				configRecord.set("description", configJson);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
				return Response.status(500).entity("{\"error\":\"Failed to update config record\"}").build();
			}
			BaseRecord updated = IOSystem.getActiveContext().getAccessPoint().update(user, configRecord);
			if (updated == null) {
				return Response.status(500).entity("{\"error\":\"Failed to save config\"}").build();
			}
		} else {
			/// Create new config record
			try {
				String userHomePath = user.get("homeDirectory.path");
				if (userHomePath == null) {
					userHomePath = "~";
				}
				BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, userHomePath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
				if (dir == null) {
					return Response.status(500).entity("{\"error\":\"Failed to find home directory\"}").build();
				}

				ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, userHomePath);
				plist.parameter(FieldNames.FIELD_NAME, CONFIG_NAME);
				BaseRecord newRec = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
				newRec.set("description", configJson);
				newRec.set("contentType", "application/json");
				BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, newRec);
				if (created == null) {
					return Response.status(500).entity("{\"error\":\"Failed to create config record\"}").build();
				}
			} catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
				return Response.status(500).entity("{\"error\":\"Failed to create config record: " + e.getMessage() + "\"}").build();
			}
		}

		return Response.status(200).entity(configJson).build();
	}

	/// GET /rest/config/features/available — return all available feature definitions
	@RolesAllowed({"user"})
	@GET
	@Path("/features/available")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getAvailableFeatures(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401).entity(null).build();
		}
		return Response.status(200).entity(JSONUtil.exportObject(AVAILABLE_FEATURES)).build();
	}

	/// Find the .featureConfig data record in the user's home directory
	private BaseRecord findConfigRecord(BaseRecord user) {
		try {
			String userHomePath = user.get("homeDirectory.path");
			if (userHomePath == null) {
				userHomePath = "~";
			}
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP, userHomePath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
			if (dir == null) {
				return null;
			}
			return IOSystem.getActiveContext().getAccessPoint().findByNameInGroup(user, ModelNames.MODEL_DATA, dir.get(FieldNames.FIELD_OBJECT_ID), CONFIG_NAME);
		} catch (Exception e) {
			logger.error("Error finding feature config record", e);
			return null;
		}
	}
}
