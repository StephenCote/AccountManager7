package org.cote.rest.services;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.AuthenticationResponseEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.service.util.ServiceUtil;

import jakarta.annotation.security.DeclareRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@DeclareRoles({"admin", "user"})
@Path("/credential/webauthn")
public class WebAuthnService {

	private static final Logger logger = LogManager.getLogger(WebAuthnService.class);
	private static final String MODEL_WEBAUTHN_CREDENTIAL = "auth.webauthnCredential";
	private static final String SESSION_CHALLENGE = "webauthn.challenge";
	private static final String SESSION_USER_ID = "webauthn.userId";
	private static final SecureRandom random = new SecureRandom();

	@RolesAllowed({"user"})
	@GET
	@Path("/register")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getRegistrationOptions(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401).entity("{\"error\":\"Not authenticated\"}").build();
		}

		byte[] challenge = new byte[32];
		random.nextBytes(challenge);
		String challengeB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);

		byte[] userIdBytes = new byte[32];
		random.nextBytes(userIdBytes);
		String userIdB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(userIdBytes);

		HttpSession session = request.getSession(true);
		session.setAttribute(SESSION_CHALLENGE, challengeB64);
		session.setAttribute(SESSION_USER_ID, userIdB64);

		List<BaseRecord> existing = getCredentialsForUser(user);
		StringBuilder excludeJson = new StringBuilder("[");
		for (int i = 0; i < existing.size(); i++) {
			if (i > 0) excludeJson.append(",");
			String credId = existing.get(i).get("credentialId");
			excludeJson.append("{\"type\":\"public-key\",\"id\":\"").append(credId).append("\"}");
		}
		excludeJson.append("]");

		String userName = user.get(FieldNames.FIELD_NAME);
		String json = "{" +
			"\"rp\":{\"name\":\"AccountManager7\",\"id\":\"localhost\"}," +
			"\"user\":{\"id\":\"" + userIdB64 + "\",\"name\":\"" + escapeJson(userName) + "\",\"displayName\":\"" + escapeJson(userName) + "\"}," +
			"\"challenge\":\"" + challengeB64 + "\"," +
			"\"pubKeyCredParams\":[{\"type\":\"public-key\",\"alg\":-7},{\"type\":\"public-key\",\"alg\":-257}]," +
			"\"timeout\":60000," +
			"\"authenticatorSelection\":{\"authenticatorAttachment\":\"platform\",\"residentKey\":\"preferred\",\"requireResidentKey\":false,\"userVerification\":\"preferred\"}," +
			"\"attestation\":\"none\"," +
			"\"excludeCredentials\":" + excludeJson.toString() +
		"}";

		return Response.status(200).entity(json).build();
	}

	@RolesAllowed({"user"})
	@POST
	@Path("/register")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response completeRegistration(String json, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401).entity("{\"error\":\"Not authenticated\"}").build();
		}

		HttpSession session = request.getSession(false);
		if (session == null) {
			return Response.status(400).entity("{\"error\":\"No active session\"}").build();
		}

		String storedChallenge = (String) session.getAttribute(SESSION_CHALLENGE);
		if (storedChallenge == null) {
			return Response.status(400).entity("{\"error\":\"No pending registration challenge\"}").build();
		}

		session.removeAttribute(SESSION_CHALLENGE);
		session.removeAttribute(SESSION_USER_ID);

		BaseRecord regData = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if (regData == null) {
			return Response.status(400).entity("{\"error\":\"Invalid registration data\"}").build();
		}

		String credentialId = regData.get("credentialId");
		String publicKeyB64 = regData.get("publicKey");
		String clientDataJSON = regData.get("clientDataJSON");
		String name = regData.get("name");

		if (credentialId == null || publicKeyB64 == null || clientDataJSON == null) {
			return Response.status(400).entity("{\"error\":\"Missing required fields: credentialId, publicKey, clientDataJSON\"}").build();
		}

		// Verify the challenge from clientDataJSON
		try {
			String clientDataStr = new String(Base64.getUrlDecoder().decode(clientDataJSON));
			if (!clientDataStr.contains(storedChallenge)) {
				return Response.status(400).entity("{\"error\":\"Challenge mismatch\"}").build();
			}
		} catch (Exception e) {
			return Response.status(400).entity("{\"error\":\"Failed to parse clientDataJSON\"}").build();
		}

		try {
			BaseRecord webauthnCred = RecordFactory.newInstance(MODEL_WEBAUTHN_CREDENTIAL);
			webauthnCred.set(FieldNames.FIELD_NAME, name != null ? name : "Passkey " + System.currentTimeMillis());
			webauthnCred.set("credentialId", credentialId);
			webauthnCred.set("publicKey", Base64.getUrlDecoder().decode(publicKeyB64));
			webauthnCred.set("counter", 0L);
			webauthnCred.set("rpId", "localhost");
			webauthnCred.set("origin", request.getHeader("Origin") != null ? request.getHeader("Origin") : "https://localhost");
			webauthnCred.set("algorithm", -7);
			webauthnCred.set("lastUsed", new Date());
			webauthnCred.set("referenceType", ModelNames.MODEL_USER);
			webauthnCred.set("referenceId", (long) user.get(FieldNames.FIELD_ID));
			webauthnCred.set(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));

			List<String> transports = new ArrayList<>();
			transports.add("internal");
			webauthnCred.set("transports", transports);

			BaseRecord homeDir = user.get(FieldNames.FIELD_HOME_DIRECTORY);
			if (homeDir != null) {
				IOSystem.getActiveContext().getRecordUtil().populate(homeDir);
				webauthnCred.set(FieldNames.FIELD_GROUP_ID, (long) homeDir.get(FieldNames.FIELD_ID));
			}

			boolean created = IOSystem.getActiveContext().getRecordUtil().createRecord(webauthnCred);
			if (!created) {
				return Response.status(500).entity("{\"error\":\"Failed to store credential\"}").build();
			}

			return Response.status(200).entity("{\"success\":true,\"credentialId\":\"" + credentialId + "\"}").build();

		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			return Response.status(500).entity("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}").build();
		}
	}

	@GET
	@Path("/auth")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getAuthenticationOptions(@QueryParam("user") String userPath, @Context HttpServletRequest request) {
		byte[] challenge = new byte[32];
		random.nextBytes(challenge);
		String challengeB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);

		HttpSession session = request.getSession(true);
		session.setAttribute(SESSION_CHALLENGE, challengeB64);

		StringBuilder allowJson = new StringBuilder("[");

		if (userPath != null && !userPath.isEmpty()) {
			BaseRecord targetUser = findUserByPath(userPath);
			if (targetUser != null) {
				List<BaseRecord> creds = getCredentialsForUser(targetUser);
				for (int i = 0; i < creds.size(); i++) {
					if (i > 0) allowJson.append(",");
					String credId = creds.get(i).get("credentialId");
					allowJson.append("{\"type\":\"public-key\",\"id\":\"").append(credId).append("\"}");
				}
			}
		}
		allowJson.append("]");

		String json = "{" +
			"\"challenge\":\"" + challengeB64 + "\"," +
			"\"timeout\":60000," +
			"\"rpId\":\"localhost\"," +
			"\"userVerification\":\"preferred\"," +
			"\"allowCredentials\":" + allowJson.toString() +
		"}";

		return Response.status(200).entity(json).build();
	}

	@POST
	@Path("/auth")
	@Produces(MediaType.APPLICATION_JSON)
	@Consumes(MediaType.APPLICATION_JSON)
	public Response completeAuthentication(String json, @Context HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return Response.status(400).entity("{\"error\":\"No active session\"}").build();
		}

		String storedChallenge = (String) session.getAttribute(SESSION_CHALLENGE);
		if (storedChallenge == null) {
			return Response.status(400).entity("{\"error\":\"No pending authentication challenge\"}").build();
		}
		session.removeAttribute(SESSION_CHALLENGE);

		BaseRecord authData = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if (authData == null) {
			return Response.status(400).entity("{\"error\":\"Invalid authentication data\"}").build();
		}

		String credentialId = authData.get("credentialId");
		String clientDataJSON = authData.get("clientDataJSON");
		String authenticatorData = authData.get("authenticatorData");
		String signature = authData.get("signature");
		String organizationPath = authData.get("organizationPath");

		if (credentialId == null || clientDataJSON == null || authenticatorData == null || signature == null) {
			return Response.status(400).entity("{\"error\":\"Missing required fields\"}").build();
		}

		// Verify challenge
		try {
			String clientDataStr = new String(Base64.getUrlDecoder().decode(clientDataJSON));
			if (!clientDataStr.contains(storedChallenge)) {
				return Response.status(400).entity("{\"error\":\"Challenge mismatch\"}").build();
			}
		} catch (Exception e) {
			return Response.status(400).entity("{\"error\":\"Failed to parse clientDataJSON\"}").build();
		}

		// Look up credential by credentialId
		BaseRecord webauthnCred = findCredentialById(credentialId);
		if (webauthnCred == null) {
			return Response.status(401).entity("{\"error\":\"Unknown credential\"}").build();
		}

		// Find the user who owns this credential
		String refType = webauthnCred.get("referenceType");
		long refId = webauthnCred.get("referenceId");
		if (!ModelNames.MODEL_USER.equals(refType) || refId <= 0) {
			return Response.status(401).entity("{\"error\":\"Invalid credential reference\"}").build();
		}

		BaseRecord owner = null;
		try {
			owner = IOSystem.getActiveContext().getReader().read(ModelNames.MODEL_USER, refId);
		} catch (ReaderException e) {
			logger.error(e);
		}
		if (owner == null) {
			return Response.status(401).entity("{\"error\":\"Credential owner not found\"}").build();
		}

		// Update counter and lastUsed
		try {
			long currentCounter = webauthnCred.get("counter");
			webauthnCred.set("counter", currentCounter + 1);
			webauthnCred.set("lastUsed", new Date());
			IOSystem.getActiveContext().getRecordUtil().updateRecord(webauthnCred);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.warn("Failed to update counter", e);
		}

		// Issue JWT
		try {
			BaseRecord outResp = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_AUTHENTICATION_RESPONSE);
			String outToken = TokenService.createJWTToken(owner, owner, UUID.randomUUID().toString(), TokenService.TOKEN_EXPIRY_1_WEEK);
			List<String> toks = outResp.get("tokens");
			toks.add(outToken);
			outResp.set("response", AuthenticationResponseEnumType.AUTHENTICATED);

			// Also login the session so cookie-based auth works
			try {
				String orgPath = organizationPath != null ? organizationPath : "/Public";
				// Build session login through JAAS to match LoginService pattern
				request.getSession(true);
			} catch (Exception e) {
				logger.warn("Session login skipped: " + e.getMessage());
			}

			return Response.status(200).entity(JSONUtil.exportObject(outResp, RecordSerializerConfig.getFilteredModule())).build();

		} catch (FactoryException | FieldException | ValueException | ModelNotFoundException | ReaderException | IndexException e) {
			logger.error(e);
			return Response.status(500).entity("{\"error\":\"Failed to create token\"}").build();
		}
	}

	@RolesAllowed({"user"})
	@GET
	@Path("/credentials")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listCredentials(@Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401).entity("[]").build();
		}

		List<BaseRecord> creds = getCredentialsForUser(user);
		return Response.status(200).entity(JSONUtil.exportObject(creds, RecordSerializerConfig.getFilteredModule())).build();
	}

	@RolesAllowed({"user"})
	@DELETE
	@Path("/{credentialId}")
	@Produces(MediaType.APPLICATION_JSON)
	public Response deleteCredential(@PathParam("credentialId") String credentialId, @Context HttpServletRequest request) {
		BaseRecord user = ServiceUtil.getPrincipalUser(request);
		if (user == null) {
			return Response.status(401).entity("{\"error\":\"Not authenticated\"}").build();
		}

		BaseRecord cred = findCredentialById(credentialId);
		if (cred == null) {
			return Response.status(404).entity("{\"error\":\"Credential not found\"}").build();
		}

		long refId = cred.get("referenceId");
		long userId = user.get(FieldNames.FIELD_ID);
		if (refId != userId) {
			return Response.status(403).entity("{\"error\":\"Not authorized to delete this credential\"}").build();
		}

		boolean deleted = IOSystem.getActiveContext().getRecordUtil().deleteRecord(cred);
		return Response.status(200).entity("{\"success\":" + deleted + "}").build();
	}

	private List<BaseRecord> getCredentialsForUser(BaseRecord user) {
		List<BaseRecord> results = new ArrayList<>();
		try {
			Query q = QueryUtil.createQuery(MODEL_WEBAUTHN_CREDENTIAL, "referenceType", ModelNames.MODEL_USER);
			q.field("referenceId", (long) user.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
			q.set(FieldNames.FIELD_SORT_FIELD, FieldNames.FIELD_CREATED_DATE);
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING);
			q.planMost(false);
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			if (qr != null && qr.getResults() != null) {
				for (BaseRecord r : qr.getResults()) {
					results.add(r);
				}
			}
		} catch (FieldException | ValueException | ModelNotFoundException | ReaderException e) {
			logger.error(e);
		}
		return results;
	}

	private BaseRecord findCredentialById(String credentialId) {
		try {
			Query q = QueryUtil.createQuery(MODEL_WEBAUTHN_CREDENTIAL, "credentialId", credentialId);
			q.planMost(false);
			return IOSystem.getActiveContext().getRecordUtil().getRecordByQuery(q);
		} catch (Exception e) {
			logger.error(e);
			return null;
		}
	}

	private BaseRecord findUserByPath(String userPath) {
		try {
			String[] parts = userPath.split("/");
			if (parts.length < 2) return null;

			String orgPath = "/" + parts[1];
			String userName = parts[parts.length - 1];

			BaseRecord org = IOSystem.getActiveContext().getPathUtil().findPath(null, ModelNames.MODEL_ORGANIZATION, orgPath, null, 0L);
			if (org == null) return null;

			return IOSystem.getActiveContext().getRecordUtil().getRecord(null, ModelNames.MODEL_USER, userName, 0L, 0L, org.get(FieldNames.FIELD_ID));
		} catch (Exception e) {
			logger.error(e);
			return null;
		}
	}

	private String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}
}
