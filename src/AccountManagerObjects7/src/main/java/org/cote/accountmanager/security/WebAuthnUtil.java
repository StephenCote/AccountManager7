package org.cote.accountmanager.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.JSONUtil;

/// WebAuthn Level 2 §7.2 assertion verification over the raw browser artifacts, independent of any
/// transport or persistence: the caller supplies the stored SPKI public key, rpId, origin, the
/// challenge it issued and the stored signature counter, and gets back a verdict with the reason
/// and the authenticator's new counter. Only ES256 (-7) and RS256 (-257) are accepted, which is
/// what the registration options offer.
public class WebAuthnUtil {

	private static final Logger logger = LogManager.getLogger(WebAuthnUtil.class);

	public static final String TYPE_GET = "webauthn.get";
	public static final String TYPE_CREATE = "webauthn.create";
	public static final int ALG_ES256 = -7;
	public static final int ALG_RS256 = -257;
	public static final int FLAG_USER_PRESENT = 0x01;
	public static final int FLAG_USER_VERIFIED = 0x04;
	public static final int AUTHENTICATOR_DATA_MIN_LENGTH = 37;

	public static final class ClientData {
		public final String type;
		public final String challenge;
		public final String origin;

		private ClientData(String type, String challenge, String origin) {
			this.type = type;
			this.challenge = challenge;
			this.origin = origin;
		}
	}

	public static final class AssertionResult {
		public final boolean verified;
		public final String reason;
		public final long counter;
		public final int flags;

		private AssertionResult(boolean verified, String reason, long counter, int flags) {
			this.verified = verified;
			this.reason = reason;
			this.counter = counter;
			this.flags = flags;
		}

		private static AssertionResult fail(String reason) {
			return new AssertionResult(false, reason, -1L, 0);
		}
	}

	private WebAuthnUtil() {
	}

	/// Returns null when the bytes are not a JSON object; absent members come back as null.
	public static ClientData parseClientData(byte[] clientDataJSON) {
		if (clientDataJSON == null || clientDataJSON.length == 0) {
			return null;
		}
		Map<?, ?> map = null;
		try {
			map = JSONUtil.importObject(new String(clientDataJSON, StandardCharsets.UTF_8), Map.class);
		} catch (Exception e) {
			logger.warn("clientDataJSON did not parse: " + e.getMessage());
		}
		if (map == null) {
			return null;
		}
		return new ClientData(stringMember(map, "type"), stringMember(map, "challenge"), stringMember(map, "origin"));
	}

	private static String stringMember(Map<?, ?> map, String key) {
		Object v = map.get(key);
		return (v instanceof String) ? (String) v : null;
	}

	/// Decodes a SubjectPublicKeyInfo as produced by AuthenticatorAttestationResponse.getPublicKey();
	/// null when it is neither an EC nor an RSA key.
	public static PublicKey decodePublicKey(byte[] spki) {
		if (spki == null || spki.length == 0) {
			return null;
		}
		X509EncodedKeySpec spec = new X509EncodedKeySpec(spki);
		for (String alg : new String[] { "EC", "RSA" }) {
			try {
				return KeyFactory.getInstance(alg).generatePublic(spec);
			} catch (GeneralSecurityException | IllegalArgumentException e) {
				/// try the next key type
			}
		}
		return null;
	}

	/// COSE algorithm identifier for the key, 0 when unsupported.
	public static int algorithmFor(PublicKey key) {
		if (key instanceof ECPublicKey) {
			return ALG_ES256;
		}
		if (key instanceof RSAPublicKey) {
			return ALG_RS256;
		}
		return 0;
	}

	public static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/// Base64url challenge strings are compared by their decoded bytes so padding differences
	/// between the issuer and the browser cannot matter; a value that does not decode never matches.
	public static boolean challengeMatches(String presented, String expected) {
		if (presented == null || expected == null) {
			return false;
		}
		byte[] p = decodeUrl(presented);
		byte[] e = decodeUrl(expected);
		return p != null && e != null && p.length > 0 && MessageDigest.isEqual(p, e);
	}

	private static byte[] decodeUrl(String s) {
		try {
			return Base64.getUrlDecoder().decode(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	public static long counterOf(byte[] authenticatorData) {
		return ((authenticatorData[33] & 0xFFL) << 24)
			| ((authenticatorData[34] & 0xFFL) << 16)
			| ((authenticatorData[35] & 0xFFL) << 8)
			| (authenticatorData[36] & 0xFFL);
	}

	/// Verifies one assertion. Every check is applied in spec order and the first failure is the
	/// reason: authenticatorData, clientData, type, challenge, origin, rpIdHash, userPresence,
	/// counter, publicKey, signature. The counter rule: when the authenticator or the stored
	/// record reports a non-zero counter, the presented value must be strictly greater than the
	/// stored one; two zeros mean the authenticator does not keep a counter.
	public static AssertionResult verifyAssertion(
		byte[] spki,
		String rpId,
		String expectedOrigin,
		String expectedChallenge,
		byte[] authenticatorData,
		byte[] clientDataJSON,
		byte[] signature,
		long storedCounter
	) {
		if (authenticatorData == null || authenticatorData.length < AUTHENTICATOR_DATA_MIN_LENGTH) {
			return AssertionResult.fail("authenticatorData");
		}
		ClientData cd = parseClientData(clientDataJSON);
		if (cd == null) {
			return AssertionResult.fail("clientData");
		}
		if (!TYPE_GET.equals(cd.type)) {
			return AssertionResult.fail("type");
		}
		if (!challengeMatches(cd.challenge, expectedChallenge)) {
			return AssertionResult.fail("challenge");
		}
		if (expectedOrigin == null || !expectedOrigin.equals(cd.origin)) {
			return AssertionResult.fail("origin");
		}
		if (rpId == null || !MessageDigest.isEqual(Arrays.copyOfRange(authenticatorData, 0, 32), sha256(rpId.getBytes(StandardCharsets.UTF_8)))) {
			return AssertionResult.fail("rpIdHash");
		}
		int flags = authenticatorData[32] & 0xFF;
		if ((flags & FLAG_USER_PRESENT) == 0) {
			return AssertionResult.fail("userPresence");
		}
		long counter = counterOf(authenticatorData);
		if ((counter != 0L || storedCounter != 0L) && counter <= storedCounter) {
			return AssertionResult.fail("counter");
		}
		PublicKey key = decodePublicKey(spki);
		if (key == null) {
			return AssertionResult.fail("publicKey");
		}
		String sigAlg = (key instanceof ECPublicKey) ? "SHA256withECDSA" : "SHA256withRSA";
		try {
			Signature sig = Signature.getInstance(sigAlg);
			sig.initVerify(key);
			sig.update(authenticatorData);
			sig.update(sha256(clientDataJSON));
			if (signature == null || signature.length == 0 || !sig.verify(signature)) {
				return AssertionResult.fail("signature");
			}
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			logger.warn("Assertion signature check failed: " + e.getMessage());
			return AssertionResult.fail("signature");
		}
		return new AssertionResult(true, null, counter, flags);
	}
}
