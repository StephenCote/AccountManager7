package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
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
import org.cote.accountmanager.security.TokenService;
import org.cote.accountmanager.security.WebAuthnUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.WebAuthnService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.core.Response;

/// PF-5b (PictureBookV3Implementation §9): the assertion route must verify the authenticator's
/// signature with the public key stored at registration before it issues a token. Before the fix
/// completeAuthentication only checked that the challenge string appeared somewhere in clientDataJSON
/// and then issued a JWT for whoever owned the named credential - any caller who knew a credentialId
/// (the registration options list them) could log in as its owner. Exercised through the real
/// WebAuthnService methods and the real (H2) database: a key pair generated here is registered via
/// completeRegistration exactly as the browser would post it, and completeAuthentication is then
/// driven with assertions signed by that key (accepted) and with forged or replayed ones (refused,
/// counter untouched). The session proxy holds the challenge the GET route would have stored.
public class TestWebAuthnServiceAssertion extends BaseTest {

	private static final String USER_NAME = "webauthnassertuser";
	private static final String MODEL_WEBAUTHN_CREDENTIAL = "auth.webauthnCredential";
	private static final String SESSION_CHALLENGE = "webauthn.challenge";
	private static final String RP_ID = "localhost";
	private static final String ORIGIN = "https://localhost:9443";
	private static final SecureRandom random = new SecureRandom();

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

	/// ---- harness (same shape as TestWebAuthnServiceDto) ----------------------------------------

	private static HttpSession sessionWith(Map<String, Object> attributes) {
		Map<String, Object> attrs = new HashMap<>(attributes);
		return (HttpSession) Proxy.newProxyInstance(
			TestWebAuthnServiceAssertion.class.getClassLoader(),
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

	private HttpServletRequestMock registrationRequest(String challenge) {
		HttpServletRequestMock req = new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), organizationPath));
		Map<String, Object> attrs = new HashMap<>();
		attrs.put(SESSION_CHALLENGE, challenge);
		req.setSession(sessionWith(attrs));
		req.setHeader("Origin", ORIGIN);
		return req;
	}

	private HttpServletRequestMock anonymousRequest(String challenge) {
		HttpServletRequestMock req = new HttpServletRequestMock();
		Map<String, Object> attrs = new HashMap<>();
		attrs.put(SESSION_CHALLENGE, challenge);
		req.setSession(sessionWith(attrs));
		req.setHeader("Origin", ORIGIN);
		return req;
	}

	private HttpServletRequestMock userRequest() {
		HttpServletRequestMock req = new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), organizationPath));
		req.setSession(sessionWith(new HashMap<>()));
		return req;
	}

	private static String b64url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String b64url(String text) {
		return b64url(text.getBytes(StandardCharsets.UTF_8));
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
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "credentialId", "referenceId", "counter", "publicKey", "algorithm", "origin", "rpId" });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	/// ---- WebAuthn byte structures, built the way an authenticator builds them ------------------

	private static KeyPair ecKeyPair() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
		g.initialize(new ECGenParameterSpec("secp256r1"));
		return g.generateKeyPair();
	}

	private static KeyPair rsaKeyPair() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		return g.generateKeyPair();
	}

	private static String newChallenge() {
		byte[] c = new byte[32];
		random.nextBytes(c);
		return b64url(c);
	}

	private static byte[] clientData(String type, String challenge, String origin) {
		return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}")
			.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] authenticatorData(String rpId, int flags, long counter) {
		byte[] hash = WebAuthnUtil.sha256(rpId.getBytes(StandardCharsets.UTF_8));
		byte[] out = new byte[37];
		System.arraycopy(hash, 0, out, 0, 32);
		out[32] = (byte) flags;
		out[33] = (byte) (counter >>> 24);
		out[34] = (byte) (counter >>> 16);
		out[35] = (byte) (counter >>> 8);
		out[36] = (byte) counter;
		return out;
	}

	private static byte[] sign(KeyPair kp, byte[] authData, byte[] clientDataJSON) throws Exception {
		String alg = kp.getPublic().getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
		Signature s = Signature.getInstance(alg);
		s.initSign(kp.getPrivate());
		s.update(authData);
		s.update(WebAuthnUtil.sha256(clientDataJSON));
		return s.sign();
	}

	/// The exact body am7client.webauthnAuthenticate posts.
	private String assertionBody(String credentialId, byte[] authData, byte[] cd, byte[] sig) {
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("credentialId", credentialId);
		dto.put("clientDataJSON", b64url(cd));
		dto.put("authenticatorData", b64url(authData));
		dto.put("signature", b64url(sig));
		dto.put("organizationPath", organizationPath);
		return JSONUtil.exportObject(dto);
	}

	/// Registers the key pair through the real registration route, as the browser posts it.
	private String register(KeyPair kp, String name) throws Exception {
		String challenge = newChallenge();
		String credentialId = b64url("cred-" + UUID.randomUUID());
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("credentialId", credentialId);
		dto.put("publicKey", b64url(kp.getPublic().getEncoded()));
		dto.put("clientDataJSON", b64url(clientData(WebAuthnUtil.TYPE_CREATE, challenge, ORIGIN)));
		dto.put("attestationObject", b64url("attestation"));
		dto.put("name", name);
		Response r = service.completeRegistration(JSONUtil.exportObject(dto), registrationRequest(challenge));
		assertEquals("registration must be 200, body=" + r.getEntity(), 200, r.getStatus());
		assertEquals(Boolean.TRUE, body(r).get("success"));
		return credentialId;
	}

	private Response authenticate(String challenge, String credentialId, byte[] authData, byte[] cd, byte[] sig, HttpServletRequestMock req) {
		return service.completeAuthentication(assertionBody(credentialId, authData, cd, sig), req);
	}

	private Response authenticate(String challenge, String credentialId, byte[] authData, byte[] cd, byte[] sig) {
		return authenticate(challenge, credentialId, authData, cd, sig, anonymousRequest(challenge));
	}

	private long storedCounter(String credentialId) throws Exception {
		BaseRecord stored = findStored(credentialId);
		assertNotNull("credential row must exist", stored);
		return ((Number) stored.get("counter")).longValue();
	}

	private void cleanup(String credentialId) throws Exception {
		Response d = service.deleteCredential(credentialId, userRequest());
		assertEquals("cleanup delete must be 200, body=" + d.getEntity(), 200, d.getStatus());
		assertNull("the credential row must be gone after delete", findStored(credentialId));
	}

	private static void assertRefused(String what, Response r) {
		assertEquals(what + " must be 401, body=" + r.getEntity(), 401, r.getStatus());
	}

	/// ---------------------------------------------------------------------------------------------

	/// Registration stores the SPKI bytes and the key's real COSE algorithm; a genuine ES256 assertion
	/// over the session challenge is accepted, the response carries a JWT whose subject is the owner,
	/// the authenticator's counter is persisted, and the route logs the session in through JAAS with
	/// the "${jwt}" username and that token as the password.
	@Test
	@SuppressWarnings("unchecked")
	public void TestGenuineEs256AssertionAuthenticates() throws Exception {
		KeyPair kp = ecKeyPair();
		String credentialId = register(kp, "PF-5b ES256 " + UUID.randomUUID().toString().substring(0, 8));
		try {
			BaseRecord stored = findStored(credentialId);
			assertNotNull(stored);
			assertEquals(WebAuthnUtil.ALG_ES256, ((Number) stored.get("algorithm")).intValue());
			assertEquals(ORIGIN, stored.get("origin"));
			assertEquals(RP_ID, stored.get("rpId"));
			assertEquals(0L, ((Number) stored.get("counter")).longValue());
			byte[] storedKey = stored.get("publicKey");
			assertNotNull("publicKey blob must be stored", storedKey);
			assertTrue("stored SPKI must equal the posted key", java.util.Arrays.equals(kp.getPublic().getEncoded(), storedKey));

			String challenge = newChallenge();
			byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT | WebAuthnUtil.FLAG_USER_VERIFIED, 7L);
			byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);
			HttpServletRequestMock req = anonymousRequest(challenge);
			Response r = authenticate(challenge, credentialId, authData, cd, sign(kp, authData, cd), req);
			assertEquals("genuine assertion must be 200, body=" + r.getEntity(), 200, r.getStatus());
			Map<String, Object> b = body(r);
			assertEquals("authenticated", String.valueOf(b.get("response")).toLowerCase());
			List<String> tokens = (List<String>) b.get("tokens");
			assertNotNull("tokens must be present", tokens);
			assertEquals(1, tokens.size());
			assertEquals("JWT subject must be the credential owner", user.get(FieldNames.FIELD_NAME), TokenService.validateTokenToSubject(tokens.get(0)));
			assertEquals(7L, storedCounter(credentialId));

			assertEquals("the route must log the session in through JAAS as ${jwt}", organizationPath + "/${jwt}", req.getLoginUsername());
			assertEquals("the JAAS password must be the issued token", tokens.get(0), req.getLoginPassword());
		}
		finally {
			cleanup(credentialId);
		}
	}

	/// RS256 keys (COSE -257, offered in the registration options) verify with SHA256withRSA.
	@Test
	public void TestGenuineRs256AssertionAuthenticates() throws Exception {
		KeyPair kp = rsaKeyPair();
		String credentialId = register(kp, "PF-5b RS256 " + UUID.randomUUID().toString().substring(0, 8));
		try {
			assertEquals(WebAuthnUtil.ALG_RS256, ((Number) findStored(credentialId).get("algorithm")).intValue());
			String challenge = newChallenge();
			byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 1L);
			byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);
			Response r = authenticate(challenge, credentialId, authData, cd, sign(kp, authData, cd));
			assertEquals("genuine RS256 assertion must be 200, body=" + r.getEntity(), 200, r.getStatus());
			assertEquals(1L, storedCounter(credentialId));
		}
		finally {
			cleanup(credentialId);
		}
	}

	/// The pre-fix hole: an assertion that names a real credential and echoes the challenge but is
	/// signed by a different key is refused and the stored counter is not advanced. Same for a
	/// clientDataJSON altered after signing, a wrong type, a wrong origin and a wrong rpId.
	@Test
	public void TestForgedAssertionsAreRefusedAndLeaveTheCounterAlone() throws Exception {
		KeyPair kp = ecKeyPair();
		KeyPair attacker = ecKeyPair();
		String credentialId = register(kp, "PF-5b Forged " + UUID.randomUUID().toString().substring(0, 8));
		try {
			String challenge = newChallenge();
			byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT | WebAuthnUtil.FLAG_USER_VERIFIED, 3L);
			byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);

			assertRefused("wrong key", authenticate(challenge, credentialId, authData, cd, sign(attacker, authData, cd)));
			assertEquals(0L, storedCounter(credentialId));

			byte[] sig = sign(kp, authData, cd);
			byte[] tamperedCd = new String(cd, StandardCharsets.UTF_8).replace("\"crossOrigin\":false", "\"crossOrigin\":true").getBytes(StandardCharsets.UTF_8);
			assertRefused("tampered clientDataJSON", authenticate(challenge, credentialId, authData, tamperedCd, sig));

			byte[] tamperedAuth = authData.clone();
			tamperedAuth[36] = (byte) 9;
			assertRefused("tampered authenticatorData", authenticate(challenge, credentialId, tamperedAuth, cd, sig));

			byte[] createCd = clientData(WebAuthnUtil.TYPE_CREATE, challenge, ORIGIN);
			assertRefused("type webauthn.create", authenticate(challenge, credentialId, authData, createCd, sign(kp, authData, createCd)));

			byte[] otherOriginCd = clientData(WebAuthnUtil.TYPE_GET, challenge, "https://evil.example:9443");
			assertRefused("wrong origin", authenticate(challenge, credentialId, authData, otherOriginCd, sign(kp, authData, otherOriginCd)));

			byte[] otherRpAuth = authenticatorData("evil.example", WebAuthnUtil.FLAG_USER_PRESENT, 3L);
			assertRefused("wrong rpId", authenticate(challenge, credentialId, otherRpAuth, cd, sign(kp, otherRpAuth, cd)));

			byte[] noPresence = authenticatorData(RP_ID, 0, 3L);
			assertRefused("user not present", authenticate(challenge, credentialId, noPresence, cd, sign(kp, noPresence, cd)));

			byte[] otherChallengeCd = clientData(WebAuthnUtil.TYPE_GET, newChallenge(), ORIGIN);
			assertRefused("challenge mismatch", authenticate(challenge, credentialId, authData, otherChallengeCd, sign(kp, authData, otherChallengeCd)));

			assertRefused("garbage signature", authenticate(challenge, credentialId, authData, cd, "not-a-signature".getBytes(StandardCharsets.UTF_8)));

			assertEquals("no refused assertion may advance the counter", 0L, storedCounter(credentialId));

			Response ok = authenticate(challenge, credentialId, authData, cd, sig);
			assertEquals("the genuine assertion with the same inputs must still be accepted, body=" + ok.getEntity(), 200, ok.getStatus());
			assertEquals(3L, storedCounter(credentialId));
		}
		finally {
			cleanup(credentialId);
		}
	}

	/// A captured assertion replayed with the same or a lower counter is refused once the stored
	/// counter has advanced; a higher counter is accepted.
	@Test
	public void TestReplayedCounterIsRefused() throws Exception {
		KeyPair kp = ecKeyPair();
		String credentialId = register(kp, "PF-5b Replay " + UUID.randomUUID().toString().substring(0, 8));
		try {
			String c1 = newChallenge();
			byte[] a5 = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 5L);
			byte[] cd1 = clientData(WebAuthnUtil.TYPE_GET, c1, ORIGIN);
			assertEquals(200, authenticate(c1, credentialId, a5, cd1, sign(kp, a5, cd1)).getStatus());
			assertEquals(5L, storedCounter(credentialId));

			String c2 = newChallenge();
			byte[] cd2 = clientData(WebAuthnUtil.TYPE_GET, c2, ORIGIN);
			assertRefused("same counter", authenticate(c2, credentialId, a5, cd2, sign(kp, a5, cd2)));
			byte[] a4 = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 4L);
			assertRefused("lower counter", authenticate(c2, credentialId, a4, cd2, sign(kp, a4, cd2)));
			assertEquals(5L, storedCounter(credentialId));

			byte[] a6 = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 6L);
			assertEquals(200, authenticate(c2, credentialId, a6, cd2, sign(kp, a6, cd2)).getStatus());
			assertEquals(6L, storedCounter(credentialId));
		}
		finally {
			cleanup(credentialId);
		}
	}

	/// Registration refuses a public key that is not an EC or RSA SubjectPublicKeyInfo, and a
	/// clientDataJSON whose type is not webauthn.create.
	@Test
	public void TestRegistrationRefusesBadKeyAndWrongType() throws Exception {
		String challenge = newChallenge();
		Map<String, Object> dto = new LinkedHashMap<>();
		dto.put("credentialId", b64url("cred-" + UUID.randomUUID()));
		dto.put("publicKey", b64url(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
		dto.put("clientDataJSON", b64url(clientData(WebAuthnUtil.TYPE_CREATE, challenge, ORIGIN)));
		dto.put("attestationObject", b64url("attestation"));
		dto.put("name", "PF-5b bad key");
		Response bad = service.completeRegistration(JSONUtil.exportObject(dto), registrationRequest(challenge));
		assertEquals("body=" + bad.getEntity(), 400, bad.getStatus());
		assertEquals("Unsupported public key", String.valueOf(body(bad).get("error")));
		assertNull(findStored((String) dto.get("credentialId")));

		String challenge2 = newChallenge();
		dto.put("publicKey", b64url(ecKeyPair().getPublic().getEncoded()));
		dto.put("clientDataJSON", b64url(clientData(WebAuthnUtil.TYPE_GET, challenge2, ORIGIN)));
		Response wrongType = service.completeRegistration(JSONUtil.exportObject(dto), registrationRequest(challenge2));
		assertEquals("body=" + wrongType.getEntity(), 400, wrongType.getStatus());
		assertEquals("Unexpected clientData type", String.valueOf(body(wrongType).get("error")));
		assertNull(findStored((String) dto.get("credentialId")));
	}
}
