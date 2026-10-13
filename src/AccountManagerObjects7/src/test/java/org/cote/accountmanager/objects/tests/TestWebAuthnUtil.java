package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import org.cote.accountmanager.security.WebAuthnUtil;
import org.cote.accountmanager.security.WebAuthnUtil.AssertionResult;
import org.junit.Test;

/// PF-5b (PictureBookV3Implementation §9): pure verification of a WebAuthn assertion with keys
/// generated in the test, no database. Builds the exact byte structures a browser hands over -
/// authenticatorData (rpIdHash || flags || counter) and clientDataJSON - signs
/// authenticatorData || SHA-256(clientDataJSON) with the private key, and checks that only the
/// genuine assertion verifies while each tampered variant fails on its own check.
public class TestWebAuthnUtil {

	private static final String RP_ID = "localhost";
	private static final String ORIGIN = "https://localhost:9443";
	private static final SecureRandom random = new SecureRandom();

	static KeyPair ecKeyPair() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
		g.initialize(new ECGenParameterSpec("secp256r1"));
		return g.generateKeyPair();
	}

	static KeyPair rsaKeyPair() throws Exception {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		return g.generateKeyPair();
	}

	static String newChallenge() {
		byte[] c = new byte[32];
		random.nextBytes(c);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(c);
	}

	static byte[] clientData(String type, String challenge, String origin) {
		return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin + "\",\"crossOrigin\":false}")
			.getBytes(StandardCharsets.UTF_8);
	}

	static byte[] authenticatorData(String rpId, int flags, long counter) {
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

	static byte[] sign(KeyPair kp, byte[] authData, byte[] clientDataJSON) throws Exception {
		String alg = kp.getPublic().getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
		Signature s = Signature.getInstance(alg);
		s.initSign(kp.getPrivate());
		s.update(authData);
		s.update(WebAuthnUtil.sha256(clientDataJSON));
		return s.sign();
	}

	private static AssertionResult verify(KeyPair kp, String challenge, byte[] authData, byte[] cd, byte[] sig, long stored) {
		return WebAuthnUtil.verifyAssertion(kp.getPublic().getEncoded(), RP_ID, ORIGIN, challenge, authData, cd, sig, stored);
	}

	@Test
	public void TestGenuineEs256AssertionVerifies() throws Exception {
		KeyPair kp = ecKeyPair();
		String challenge = newChallenge();
		byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT | WebAuthnUtil.FLAG_USER_VERIFIED, 5L);
		byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);
		AssertionResult r = verify(kp, challenge, authData, cd, sign(kp, authData, cd), 4L);
		assertTrue("genuine ES256 assertion must verify, reason=" + r.reason, r.verified);
		assertNull(r.reason);
		assertEquals(5L, r.counter);
		assertTrue((r.flags & WebAuthnUtil.FLAG_USER_VERIFIED) != 0);
	}

	@Test
	public void TestGenuineRs256AssertionVerifies() throws Exception {
		KeyPair kp = rsaKeyPair();
		String challenge = newChallenge();
		byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 0L);
		byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);
		AssertionResult r = verify(kp, challenge, authData, cd, sign(kp, authData, cd), 0L);
		assertTrue("genuine RS256 assertion must verify, reason=" + r.reason, r.verified);
		assertEquals(0L, r.counter);
	}

	@Test
	public void TestWrongKeyFailsOnSignature() throws Exception {
		KeyPair signer = ecKeyPair();
		KeyPair stored = ecKeyPair();
		String challenge = newChallenge();
		byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 1L);
		byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);
		AssertionResult r = verify(stored, challenge, authData, cd, sign(signer, authData, cd), 0L);
		assertFalse(r.verified);
		assertEquals("signature", r.reason);
	}

	@Test
	public void TestTamperedClientDataFailsOnSignature() throws Exception {
		KeyPair kp = ecKeyPair();
		String challenge = newChallenge();
		byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 1L);
		byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);
		byte[] sig = sign(kp, authData, cd);
		/// flip crossOrigin:false -> crossOrigin:true: type, challenge and origin still pass
		byte[] tampered = new String(cd, StandardCharsets.UTF_8).replace("\"crossOrigin\":false", "\"crossOrigin\":true").getBytes(StandardCharsets.UTF_8);
		AssertionResult r = verify(kp, challenge, authData, tampered, sig, 0L);
		assertFalse(r.verified);
		assertEquals("signature", r.reason);
	}

	@Test
	public void TestTamperedAuthenticatorDataFailsOnSignature() throws Exception {
		KeyPair kp = ecKeyPair();
		String challenge = newChallenge();
		byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 1L);
		byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);
		byte[] sig = sign(kp, authData, cd);
		byte[] bumped = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 2L);
		AssertionResult r = verify(kp, challenge, bumped, cd, sig, 0L);
		assertFalse(r.verified);
		assertEquals("signature", r.reason);
	}

	@Test
	public void TestWrongTypeChallengeOriginRpIdFlagsAndCounter() throws Exception {
		KeyPair kp = ecKeyPair();
		String challenge = newChallenge();
		byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 3L);

		byte[] wrongType = clientData(WebAuthnUtil.TYPE_CREATE, challenge, ORIGIN);
		assertEquals("type", verify(kp, challenge, authData, wrongType, sign(kp, authData, wrongType), 0L).reason);

		byte[] otherChallenge = clientData(WebAuthnUtil.TYPE_GET, newChallenge(), ORIGIN);
		assertEquals("challenge", verify(kp, challenge, authData, otherChallenge, sign(kp, authData, otherChallenge), 0L).reason);

		/// a clientData whose challenge merely CONTAINS the expected value no longer passes
		byte[] containing = clientData(WebAuthnUtil.TYPE_GET, challenge + "AA", ORIGIN);
		assertEquals("challenge", verify(kp, challenge, authData, containing, sign(kp, authData, containing), 0L).reason);

		byte[] otherOrigin = clientData(WebAuthnUtil.TYPE_GET, challenge, "https://evil.example:9443");
		assertEquals("origin", verify(kp, challenge, authData, otherOrigin, sign(kp, authData, otherOrigin), 0L).reason);

		byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);
		byte[] otherRp = authenticatorData("example.com", WebAuthnUtil.FLAG_USER_PRESENT, 3L);
		assertEquals("rpIdHash", verify(kp, challenge, otherRp, cd, sign(kp, otherRp, cd), 0L).reason);

		byte[] noUp = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_VERIFIED, 3L);
		assertEquals("userPresence", verify(kp, challenge, noUp, cd, sign(kp, noUp, cd), 0L).reason);

		/// replayed counter: stored 3, presented 3 and presented 2 both fail; presented 4 passes
		assertEquals("counter", verify(kp, challenge, authData, cd, sign(kp, authData, cd), 3L).reason);
		byte[] lower = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 2L);
		assertEquals("counter", verify(kp, challenge, lower, cd, sign(kp, lower, cd), 3L).reason);
		byte[] higher = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 4L);
		AssertionResult ok = verify(kp, challenge, higher, cd, sign(kp, higher, cd), 3L);
		assertTrue("counter 4 over stored 3 must verify, reason=" + ok.reason, ok.verified);
		assertEquals(4L, ok.counter);

		/// a stored non-zero counter with a presented zero is a clone, not a counterless authenticator
		byte[] zero = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 0L);
		assertEquals("counter", verify(kp, challenge, zero, cd, sign(kp, zero, cd), 1L).reason);

		byte[] truncated = new byte[36];
		assertEquals("authenticatorData", verify(kp, challenge, truncated, cd, new byte[] { 1 }, 0L).reason);
		assertEquals("clientData", verify(kp, challenge, authData, "not json".getBytes(StandardCharsets.UTF_8), new byte[] { 1 }, 0L).reason);
	}

	@Test
	public void TestPublicKeyDecoding() throws Exception {
		PublicKey ec = WebAuthnUtil.decodePublicKey(ecKeyPair().getPublic().getEncoded());
		assertNotNull(ec);
		assertEquals(WebAuthnUtil.ALG_ES256, WebAuthnUtil.algorithmFor(ec));
		PublicKey rsa = WebAuthnUtil.decodePublicKey(rsaKeyPair().getPublic().getEncoded());
		assertNotNull(rsa);
		assertEquals(WebAuthnUtil.ALG_RS256, WebAuthnUtil.algorithmFor(rsa));
		assertNull(WebAuthnUtil.decodePublicKey(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }));
		assertNull(WebAuthnUtil.decodePublicKey(null));

		KeyPair kp = ecKeyPair();
		String challenge = newChallenge();
		byte[] authData = authenticatorData(RP_ID, WebAuthnUtil.FLAG_USER_PRESENT, 1L);
		byte[] cd = clientData(WebAuthnUtil.TYPE_GET, challenge, ORIGIN);
		AssertionResult r = WebAuthnUtil.verifyAssertion(new byte[] { 1, 2, 3 }, RP_ID, ORIGIN, challenge, authData, cd, sign(kp, authData, cd), 0L);
		assertEquals("publicKey", r.reason);
	}

	@Test
	public void TestChallengeComparisonIgnoresPadding() {
		String c = newChallenge();
		assertTrue(WebAuthnUtil.challengeMatches(c, c));
		assertTrue(WebAuthnUtil.challengeMatches(c + "=", c));
		assertFalse(WebAuthnUtil.challengeMatches(c + "AA", c));
		assertFalse(WebAuthnUtil.challengeMatches("", c));
		assertFalse(WebAuthnUtil.challengeMatches(null, c));
		assertFalse(WebAuthnUtil.challengeMatches("***", c));
	}
}
