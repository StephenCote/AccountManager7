package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.WebAuthnService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;

/// PF-5 (PictureBookV3Implementation §9): the browser posts the WebAuthn registration and assertion
/// results as plain DTOs - the exact objects am7client.js builds (credentialId, publicKey, clientDataJSON,
/// attestationObject, name / credentialId, clientDataJSON, authenticatorData, signature,
/// organizationPath) with NO schema field - so the two POST routes must parse them as DTOs, not as
/// model records. Before the fix both routes returned 400 "Invalid ... data" for every real browser
/// body because the record deserializer needs a schema.
///
/// Exercised through the real WebAuthnService methods and the real (H2) database with Service7's own
/// HttpServletRequestMock + UserPrincipal; the session is a JDK dynamic proxy holding the challenge the
/// GET route would have stored (the routes read and remove exactly that attribute). Nothing in the
/// application is mocked; the stored credential is read back from the database through the service's
/// own list route and removed through its delete route.
public class TestWebAuthnServiceDto extends BaseTest {

	private static final String USER_NAME = "webauthndtouser";
	private static final String MODEL_WEBAUTHN_CREDENTIAL = "auth.webauthnCredential";
	private static final String SESSION_CHALLENGE = "webauthn.challenge";

	private final WebAuthnService service = new WebAuthnService();
	private BaseRecord user;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		user = getCreateUser(USER_NAME);
		assertNotNull("Test user is null", user);
	}

	private static HttpSession sessionWith(Map<String, Object> attributes) {
		Map<String, Object> attrs = new HashMap<>(attributes);
		return (HttpSession) Proxy.newProxyInstance(
			TestWebAuthnServiceDto.class.getClassLoader(),
			new Class<?>[] { HttpSession.class },
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "getAttribute": return attrs.get(String.valueOf(args[0]));
					case "setAttribute": attrs.put(String.valueOf(args[0]), args[1]); return null;
					case "removeAttribute": attrs.remove(String.valueOf(args[0])); return null;
					case "getId": return "test-session";
					default:
						Class<?> rt = method.getReturnType();
						if (rt == boolean.class) return false;
						if (rt == int.class) return 0;
						if (rt == long.class) return 0L;
						return null;
				}
			}
		);
	}

	private HttpServletRequestMock requestAs(BaseRecord u, String challenge) {
		HttpServletRequestMock req = new HttpServletRequestMock(new UserPrincipal((String) u.get(FieldNames.FIELD_NAME), organizationPath));
		Map<String, Object> attrs = new HashMap<>();
		if (challenge != null) {
			attrs.put(SESSION_CHALLENGE, challenge);
		}
		req.setSession(sessionWith(attrs));
		req.setHeader("Origin", "https://localhost:9443");
		return req;
	}

	private static String b64url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String b64url(String text) {
		return b64url(text.getBytes(StandardCharsets.UTF_8));
	}

	/// The browser's clientDataJSON: a JSON document carrying the challenge the server issued.
	private static String clientData(String challenge, String type) {
		return b64url("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"https://localhost:9443\"}");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> body(Response r) {
		assertNotNull("Response is null", r);
		Object entity = r.getEntity();
		assertNotNull("Response entity is null", entity);
		Map<String, Object> m = JSONUtil.importObject(entity.toString(), LinkedHashMap.class);
		assertNotNull("Response body did not parse as JSON: " + entity, m);
		return m;
	}

	private BaseRecord findStored(String credentialId) throws Exception {
		Query q = QueryUtil.createQuery(MODEL_WEBAUTHN_CREDENTIAL, "credentialId", credentialId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "credentialId", "referenceId" });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	/// ------------------------------------------------------------------------------------------

	/// The exact registration body am7client.webauthnRegister posts (no schema) is accepted: the route
	/// answers 200 {success:true, credentialId}, the credential row exists for this user with the posted
	/// name, and the delete route removes it again.
	@Test
	@SuppressWarnings("unchecked")
	public void TestRegistrationAcceptsTheBrowserDto() throws Exception {
		String challenge = b64url(UUID.randomUUID().toString());
		String credentialId = b64url("cred-" + UUID.randomUUID());
		String name = "PF-5 Virtual Key " + UUID.randomUUID().toString().substring(0, 8);
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("credentialId", credentialId);
		dto.put("publicKey", b64url(KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic().getEncoded()));
		dto.put("clientDataJSON", clientData(challenge, "webauthn.create"));
		dto.put("attestationObject", b64url("attestation"));
		dto.put("name", name);
		String json = JSONUtil.exportObject(dto);
		assertTrue("fixture must carry no schema field", !json.contains("schema"));

		Response r = service.completeRegistration(json, requestAs(user, challenge));
		assertEquals("register with the browser DTO must be 200, body=" + r.getEntity(), 200, r.getStatus());
		Map<String, Object> b = body(r);
		assertEquals(Boolean.TRUE, b.get("success"));
		assertEquals(credentialId, b.get("credentialId"));

		BaseRecord stored = findStored(credentialId);
		try {
			assertNotNull("the credential row must exist after registration", stored);
			assertEquals(name, stored.get(FieldNames.FIELD_NAME));
			assertEquals((long) user.get(FieldNames.FIELD_ID), ((Number) stored.get("referenceId")).longValue());

			Response list = service.listCredentials(requestAs(user, null));
			assertEquals(200, list.getStatus());
			List<Map<String, Object>> creds = JSONUtil.importObject(String.valueOf(list.getEntity()), List.class);
			assertNotNull(creds);
			assertTrue("list route must return the registered credential",
				creds.stream().anyMatch(c -> credentialId.equals(c.get("credentialId"))));
		}
		finally {
			Response d = service.deleteCredential(credentialId, requestAs(user, null));
			assertEquals("cleanup delete must be 200, body=" + d.getEntity(), 200, d.getStatus());
			assertEquals(Boolean.TRUE, body(d).get("success"));
		}
		assertEquals("the credential row must be gone after delete", null, findStored(credentialId));
	}

	private String register(String challenge, String credentialId, String name) throws Exception {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("credentialId", credentialId);
		dto.put("publicKey", b64url(KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic().getEncoded()));
		dto.put("clientDataJSON", clientData(challenge, "webauthn.create"));
		dto.put("attestationObject", b64url("attestation"));
		dto.put("name", name);
		return JSONUtil.exportObject(dto);
	}

	@SuppressWarnings("unchecked")
	private List<String> listedCredentialIds() {
		Response list = service.listCredentials(requestAs(user, null));
		assertEquals(200, list.getStatus());
		List<Map<String, Object>> creds = JSONUtil.importObject(String.valueOf(list.getEntity()), List.class);
		assertNotNull(creds);
		return creds.stream().map(c -> String.valueOf(c.get("credentialId"))).toList();
	}

	@SuppressWarnings("unchecked")
	private List<String> allowedCredentialIds() {
		HttpServletRequestMock anonymous = new HttpServletRequestMock();
		anonymous.setSession(sessionWith(new HashMap<>()));
		Response r = service.getAuthenticationOptions(organizationPath + "/" + USER_NAME, anonymous);
		assertEquals(200, r.getStatus());
		List<Map<String, Object>> allow = (List<Map<String, Object>>) body(r).get("allowCredentials");
		assertNotNull("allowCredentials missing from " + r.getEntity(), allow);
		return allow.stream().map(c -> String.valueOf(c.get("id"))).toList();
	}

	/// PF-5c: the list and the allowCredentials of GET /auth are read through CacheDBSearch, which caches
	/// any non-empty result and is never invalidated by a create. With one passkey already listed, a second
	/// registration must still appear in both immediately - before the fix the second key was invisible to
	/// both for the cache lifetime, so it could neither be seen nor used to log in.
	@Test
	public void TestSecondRegistrationIsListedAndAllowedImmediately() throws Exception {
		String first = b64url("first-" + UUID.randomUUID());
		String second = b64url("second-" + UUID.randomUUID());
		try {
			String c1 = b64url(UUID.randomUUID().toString());
			assertEquals(200, service.completeRegistration(register(c1, first, "PF-5c First"), requestAs(user, c1)).getStatus());
			assertTrue("first key must be listed", listedCredentialIds().contains(first));
			assertTrue("first key must be allowed", allowedCredentialIds().contains(first));

			String c2 = b64url(UUID.randomUUID().toString());
			assertEquals(200, service.completeRegistration(register(c2, second, "PF-5c Second"), requestAs(user, c2)).getStatus());
			List<String> listed = listedCredentialIds();
			assertTrue("second key must be listed immediately after registration, got " + listed, listed.contains(second));
			assertTrue("first key must still be listed, got " + listed, listed.contains(first));
			List<String> allowed = allowedCredentialIds();
			assertTrue("second key must be in allowCredentials immediately, got " + allowed, allowed.contains(second));
		}
		finally {
			service.deleteCredential(first, requestAs(user, null));
			service.deleteCredential(second, requestAs(user, null));
		}
		assertEquals(null, findStored(first));
		assertEquals(null, findStored(second));
	}

	/// A parseable DTO that lacks a required field is refused by the field check, not by the parser.
	@Test
	public void TestRegistrationMissingFieldIs400AfterParsing() {
		String challenge = b64url(UUID.randomUUID().toString());
		String json = "{\"publicKey\":\"" + b64url("pk") + "\",\"clientDataJSON\":\"" + clientData(challenge, "webauthn.create") + "\"}";
		Response r = service.completeRegistration(json, requestAs(user, challenge));
		assertEquals(400, r.getStatus());
		String err = String.valueOf(body(r).get("error"));
		assertTrue("must be the missing-field refusal, got: " + err, err.startsWith("Missing required fields"));
	}

	/// A body that is not JSON at all is still "Invalid registration data"; a wrong challenge is refused
	/// after the DTO parsed; and no session is 400 before anything is read.
	@Test
	public void TestRegistrationRefusals() {
		String challenge = b64url(UUID.randomUUID().toString());
		Response bad = service.completeRegistration("not json", requestAs(user, challenge));
		assertEquals(400, bad.getStatus());
		assertEquals("Invalid registration data", String.valueOf(body(bad).get("error")));

		String json = "{\"credentialId\":\"" + b64url("c") + "\",\"publicKey\":\"" + b64url("pk")
			+ "\",\"clientDataJSON\":\"" + clientData(b64url("some-other-challenge"), "webauthn.create") + "\"}";
		Response mismatch = service.completeRegistration(json, requestAs(user, challenge));
		assertEquals(400, mismatch.getStatus());
		assertEquals("Challenge mismatch", String.valueOf(body(mismatch).get("error")));

		HttpServletRequestMock noSession = new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), organizationPath));
		Response ns = service.completeRegistration(json, noSession);
		assertEquals(400, ns.getStatus());
		assertEquals("No active session", String.valueOf(body(ns).get("error")));
	}

	/// The exact assertion body am7client.webauthnAuthenticate posts (no schema) parses: with an unknown
	/// credentialId the route gets past the parse and the challenge check to the credential lookup, which
	/// is the 401 "Unknown credential" - never the 400 "Invalid authentication data" the old parser gave.
	@Test
	public void TestAuthenticationAcceptsTheBrowserDto() {
		String challenge = b64url(UUID.randomUUID().toString());
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("credentialId", b64url("no-such-credential-" + UUID.randomUUID()));
		dto.put("clientDataJSON", clientData(challenge, "webauthn.get"));
		dto.put("authenticatorData", b64url("authenticator-data"));
		dto.put("signature", b64url("signature"));
		dto.put("organizationPath", organizationPath);
		String json = JSONUtil.exportObject(dto);
		assertTrue("fixture must carry no schema field", !json.contains("schema"));

		HttpServletRequestMock anonymous = new HttpServletRequestMock();
		anonymous.setSession(sessionWith(Map.of(SESSION_CHALLENGE, challenge)));
		Response r = service.completeAuthentication(json, anonymous);
		assertEquals("body=" + r.getEntity(), 401, r.getStatus());
		assertEquals("Unknown credential", String.valueOf(body(r).get("error")));

		HttpServletRequestMock again = new HttpServletRequestMock();
		again.setSession(sessionWith(Map.of(SESSION_CHALLENGE, challenge)));
		Response missing = service.completeAuthentication("{\"credentialId\":\"x\"}", again);
		assertEquals(400, missing.getStatus());
		assertEquals("Missing required fields", String.valueOf(body(missing).get("error")));
	}
}
