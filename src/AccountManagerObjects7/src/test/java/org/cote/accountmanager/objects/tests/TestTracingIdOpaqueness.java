package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.olio.llm.TracingIdValidator;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Guardrail 3: {@code TracingIdValidator.isOpaque} - the shape test that decides whether a
 * {@code user} / {@code session_id} value may be forwarded to LiteLLM/Langfuse. Langfuse is a
 * third-party observability store that already holds the full prompt and completion, so the
 * identifier is what makes a trace attributable to a person; a username or email address reaching
 * it is accumulated PII.
 *
 * <p>Three obligations, and all three are load-bearing:</p>
 * <ol>
 *   <li><b>Reject human identifiers AND pseudonymous identifiers.</b> The cases the guardrail
 *       exists for.</li>
 *   <li><b>Accept the ids AM7 actually generates.</b> Including the two LIVE formats the existing
 *       live tests build - {@code am7rt-u-AM7RT-A-<hex8>} (TestLiteLLMRoundTrip) and
 *       {@code am7lp-u-} / {@code am7lp-s-AM7LP-<x>-<hex8>} (TestLiteLLMOllamaProxy). Those tests
 *       assert the value round-trips into Langfuse, so a predicate that rejected them would break
 *       working live tests by silently dropping the correlation id and turning an equality
 *       assertion into a null. If a change to the predicate turns any of these red, the predicate
 *       is too strict - do not relax the test.</li>
 *   <li><b>Pin the deliberate FALSE REJECTS as rejected</b>, so the code and its own stated limits
 *       cannot drift apart.</li>
 * </ol>
 *
 * <p><b>REWRITTEN 2026-09-15 for the tightened predicate.</b> The predicate used to accept any
 * 12..128 char {@code [A-Za-z0-9._:-]} token containing a digit, plus an undashed 32-hex string.
 * Security review rejected that: <b>32 hex characters is the shape of an MD5 of an email address</b>
 * (and 64 of a SHA-256), and a hashed email is pseudonymous PII and the canonical identity join key
 * - trivially re-identified by anyone holding the Langfuse keys plus a staff email list. The rule is
 * now a narrow allowlist: a <b>dashed</b> UUID, or an <b>{@code am7}-prefixed</b> token. The
 * previously-documented false accepts ({@code stephen.cote.1985}) are now rejected, and this file
 * asserts that rather than asserting the old behaviour.</p>
 *
 * <p><b>No real personal data in these fixtures.</b> An earlier version hardcoded the maintainer's
 * actual email address. A PII guardrail's own test fixtures are the worst possible place to commit
 * one, so every case now uses the reserved {@code example.com} domain. The assertions are unchanged
 * - the point was always the shape, never the address.</p>
 *
 * <p>Pure predicate coverage plus one real {@code objectId} read off a record actually created in
 * the database. No live LLM.</p>
 */
public class TestTracingIdOpaqueness extends BaseTest {

	/// Reserved-domain stand-in. RFC 2606 guarantees example.com is not a real address.
	private static final String PII_EMAIL = "someone@example.com";
	private static final String PII_NAME = "Stephen Cote";

	/// (1) The cases Guardrail 3 exists to stop.
	@Test
	public void testRejectsHumanIdentifiers() {
		/// An email address: the `@` is outside the token charset and it carries no am7 prefix.
		assertFalse("an email address must never reach Langfuse", TracingIdValidator.isOpaque(PII_EMAIL));
		/// A display name: whitespace is itself evidence the value is not a generated id.
		assertFalse("a display name must never reach Langfuse", TracingIdValidator.isOpaque(PII_NAME));
		/// Short usernames.
		assertFalse("a short username must never reach Langfuse", TracingIdValidator.isOpaque("scote"));
		assertFalse("a short username must never reach Langfuse", TracingIdValidator.isOpaque("jsmith"));
		/// Dotted names and name pairs, with and without digits: none of them are a dashed UUID and
		/// none carry the am7 prefix.
		assertFalse("a dotted human name must never reach Langfuse",
			TracingIdValidator.isOpaque("stephen.cote"));
		assertFalse("a name pair must never reach Langfuse",
			TracingIdValidator.isOpaque("stephencote"));
	}

	/// (2) PSEUDONYMOUS identifiers - the security-review cases, and the most important ones in this
	/// file because every one of them LOOKS opaque.
	///
	/// A hex digest is the canonical identity join key: hash the email, and anyone with the Langfuse
	/// keys and a staff email list re-identifies the trace with a dictionary of a few thousand
	/// candidates. Accepting it would have been worse than accepting the raw address, because it
	/// would have looked compliant.
	@Test
	public void testRejectsPseudonymousAndEncodedIdentifiers() {
		/// MD5 of an email address - 32 hex, exactly the shape the old UUID_PLAIN branch accepted.
		assertEquals("fixture check: an MD5 hex digest is 32 chars", 32, "9e107d9d372bb6826bd81d3542a419d6".length());
		assertFalse("a 32-char hex digest must be rejected: it is the shape of an MD5 of an email"
			+ " address, i.e. pseudonymous PII and the canonical identity join key",
			TracingIdValidator.isOpaque("9e107d9d372bb6826bd81d3542a419d6"));

		/// SHA-256 hex - 64 chars, which passed the old token rule (in-charset, has digits).
		String sha256Hex = "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592";
		assertEquals("fixture check: a SHA-256 hex digest is 64 chars", 64, sha256Hex.length());
		assertFalse("a 64-char hex digest must be rejected for the same reason as the MD5 case",
			TracingIdValidator.isOpaque(sha256Hex));

		/// An unpadded base64url blob: `-` and `_` are both in the token charset, so this used to
		/// pass. It is an arbitrary payload - possibly an encoded or encrypted identifier.
		assertFalse("an unpadded base64url blob must be rejected",
			TracingIdValidator.isOpaque("eyJ1c2VyIjoic2NvdGUifQ"));
		assertFalse("a base64url blob using both - and _ must be rejected",
			TracingIdValidator.isOpaque("aGVsbG8-d29ybGQ_YWJjMTIz"));

		/// A bare long numeric string: card- and account-number shaped.
		assertFalse("a 16-digit numeric string must be rejected - it is card/account shaped",
			TracingIdValidator.isOpaque("4111111111111111"));
		assertFalse("a 12-digit numeric string must be rejected",
			TracingIdValidator.isOpaque("123456789012"));
	}

	/// (3) Absent, blank, over-long and non-ASCII values.
	@Test
	public void testRejectsMalformedAndOversizedValues() {
		assertFalse("null must be rejected, not NPE", TracingIdValidator.isOpaque(null));
		assertFalse("empty must be rejected", TracingIdValidator.isOpaque(""));
		assertFalse("blank must be rejected", TracingIdValidator.isOpaque("   "));

		/// 200 characters of prose. Prose in a tracing field is either a mistake or an injected
		/// payload.
		StringBuilder prose = new StringBuilder();
		while (prose.length() < 200) {
			prose.append("the quick brown fox jumps over the lazy dog 1234567890 ");
		}
		String prose200 = prose.substring(0, 200);
		assertEquals(200, prose200.length());
		assertFalse("200 characters of prose must be rejected", TracingIdValidator.isOpaque(prose200));

		/// Over-long but otherwise PERFECTLY shaped: a real am7 prefix and an in-charset suffix, so
		/// the ONLY thing rejecting it is the suffix length cap. Without the am7 prefix this case
		/// would pass for the wrong reason.
		StringBuilder longToken = new StringBuilder("am7xx-");
		while (longToken.length() <= TracingIdValidator.MAX_SUFFIX_LENGTH + "am7xx-".length()) {
			longToken.append("b2c3");
		}
		assertTrue("fixture check: the suffix must exceed MAX_SUFFIX_LENGTH",
			longToken.length() - "am7xx-".length() > TracingIdValidator.MAX_SUFFIX_LENGTH);
		assertFalse("an am7 token whose suffix exceeds MAX_SUFFIX_LENGTH must be rejected",
			TracingIdValidator.isOpaque(longToken.toString()));

		/// Too SHORT a suffix, again with a real prefix so only the floor can reject it.
		assertTrue("fixture check: this suffix is below MIN_SUFFIX_LENGTH",
			"abc".length() < TracingIdValidator.MIN_SUFFIX_LENGTH);
		assertFalse("an am7 token whose suffix is below MIN_SUFFIX_LENGTH must be rejected",
			TracingIdValidator.isOpaque("am7xx-abc"));

		/// Non-ASCII: outside the token charset.
		assertFalse("non-ASCII must be rejected", TracingIdValidator.isOpaque("Stéphane-Côté-1985"));
		assertFalse("non-ASCII must be rejected", TracingIdValidator.isOpaque("张伟-20260915-abc"));
		assertFalse("non-ASCII must be rejected even behind an am7 prefix",
			TracingIdValidator.isOpaque("am7xx-Stéphane-Côté"));

		/// Padding: both patterns are anchored, so a value that would otherwise pass is rejected
		/// when padded. The emission point forwards the ORIGINAL string, so a trimmed-and-accepted
		/// value would put the padded form on the wire.
		assertFalse("a padded but otherwise valid am7 token must be rejected",
			TracingIdValidator.isOpaque("  am7rt-u-AM7RT-A-364a2874  "));
		assertFalse("a padded but otherwise valid UUID must be rejected",
			TracingIdValidator.isOpaque(" " + UUID.randomUUID().toString() + " "));
	}

	/// (4) The ids AM7 actually generates must all be accepted.
	@Test
	public void testAcceptsDashedUuids() {
		String dashed = UUID.randomUUID().toString();
		assertEquals("fixture check: a dashed UUID is 36 chars", 36, dashed.length());
		assertTrue("a dashed UUID must be accepted", TracingIdValidator.isOpaque(dashed));
		assertTrue("an upper-case UUID must be accepted", TracingIdValidator.isOpaque(dashed.toUpperCase()));
	}

	/// (4b) THE DELIBERATE FALSE REJECTS, asserted AS REJECTED so the predicate and its javadoc
	/// cannot drift apart. Each of these USED to be accepted; each is now dropped on purpose.
	///
	/// If you arrived here because a correlation id of yours is being dropped: use a dashed UUID (an
	/// objectId) or give your nonce an `am7` prefix. That is what every id AM7 generates does.
	@Test
	public void testDeliberateFalseRejectsAreRejected() {
		String plain = UUID.randomUUID().toString().replace("-", "");
		assertEquals("fixture check: an undashed UUID is 32 chars", 32, plain.length());
		assertFalse("an UNDASHED UUID is now rejected - it is indistinguishable from an MD5 digest,"
			+ " which is the whole reason the 32-hex branch was removed",
			TracingIdValidator.isOpaque(plain));

		assertFalse("`stephen.cote.1985` was a DOCUMENTED FALSE ACCEPT under the old digit rule and"
			+ " is now rejected. If this turns red, the predicate was loosened back and"
			+ " TracingIdValidator's javadoc must change with it.",
			TracingIdValidator.isOpaque("stephen.cote.1985"));
		assertFalse("same class: a name with a year appended", TracingIdValidator.isOpaque("stephencote1985"));

		assertFalse("an all-alphabetic nonce with no am7 prefix is rejected",
			TracingIdValidator.isOpaque("abcdefghijkl"));
		assertFalse("a bespoke (non-am7) prefix is rejected",
			TracingIdValidator.isOpaque("trace-user-42"));
		assertFalse("an UPPERCASE AM7 prefix is rejected - the prefix test is deliberately"
			+ " lowercase-only so it matches exactly what AM7 generates",
			TracingIdValidator.isOpaque("AM7RT-u-AM7RT-A-364a2874"));
		assertFalse("`am7` with no separator is rejected", TracingIdValidator.isOpaque("am7364a2874abc"));
	}

	/// (5) A REAL objectId, read off a record actually written to the database by a NON-admin test
	/// user - not a fabricated string that merely looks like one. objectId is the canonical opaque
	/// correlation key the design doc tells callers to use, so it must pass.
	@Test
	public void testAcceptsARealObjectId() {
		BaseRecord user = getCreateUser("tracingIdUser");
		assertNotNull("test user is null", user);
		String objectId = user.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("the test user carries no objectId", objectId);
		logger.info("[GUARDRAIL3] real objectId under test = " + objectId);
		assertTrue("a REAL AM7 objectId must be accepted - it is the canonical opaque correlation"
			+ " key the design tells callers to use", TracingIdValidator.isOpaque(objectId));
	}

	/// (6) THE TWO LIVE FORMATS. These are built by TestLiteLLMRoundTrip (am7rt-u-...) and
	/// TestLiteLLMOllamaProxy (am7lp-u-... / am7lp-s-...), both of which then assert the value
	/// arrives in Langfuse as trace.userId / trace.sessionId. Constructed here EXACTLY the way those
	/// tests construct them, from a real random UUID prefix, so this is the same shape and not a
	/// hand-copied sample.
	@Test
	public void testAcceptsTheLiveGeneratedTracingIdFormats() {
		String hex8 = UUID.randomUUID().toString().substring(0, 8);

		/// TestLiteLLMRoundTrip: nonce = "AM7RT-A-" + hex8; userId = "am7rt-u-" + nonce
		String rtUser = "am7rt-u-AM7RT-A-" + hex8;
		/// TestLiteLLMOllamaProxy: nonce = "AM7LP-B-" + hex8; userId/sessionId = "am7lp-u-"/"am7lp-s-" + nonce
		String lpUser = "am7lp-u-AM7LP-B-" + hex8;
		String lpSession = "am7lp-s-AM7LP-B-" + hex8;
		String lpUserE = "am7lp-u-AM7LP-E-" + hex8;
		String rtSession = "am7rt-s-AM7RT-C-" + hex8;

		for (String v : new String[] { rtUser, lpUser, lpSession, lpUserE, rtSession }) {
			logger.info("[GUARDRAIL3] live format under test = " + v + " (len=" + v.length() + ")");
			assertTrue("THE PREDICATE IS TOO STRICT: '" + v + "' is a format the LIVE LiteLLM/Langfuse"
				+ " tests generate and assert round-trips into Langfuse. Rejecting it makes"
				+ " Chat.buildTracingHeaders drop the correlation id, so those tests fail on a null"
				+ " trace.userId/sessionId. Fix the predicate, not this test.",
				TracingIdValidator.isOpaque(v));
		}
	}

	/// (7) opaqueOrNull is the emission-point convenience: DROP + WARN, never reject, never throw.
	/// A tracing field must not be able to fail a chat - tracing is observability, not function.
	@Test
	public void testOpaqueOrNullDropsRatherThanThrows() {
		String good = UUID.randomUUID().toString();
		assertEquals("an opaque value must pass through unchanged", good,
			TracingIdValidator.opaqueOrNull("user", good));

		assertNull("a non-opaque value must be DROPPED (null), not thrown",
			TracingIdValidator.opaqueOrNull("user", PII_EMAIL));
		assertNull("a display name must be dropped", TracingIdValidator.opaqueOrNull("session_id", PII_NAME));
		assertNull("a hex digest must be dropped", TracingIdValidator.opaqueOrNull("user",
			"9e107d9d372bb6826bd81d3542a419d6"));
		assertNull("null must return null without throwing", TracingIdValidator.opaqueOrNull("user", null));
		assertNull("blank must return null without throwing", TracingIdValidator.opaqueOrNull("user", "  "));
	}

	/// (8) The DROP + WARN line must never carry the value itself, or the PII merely moves from the
	/// trace store into the application log, where it is if anything more widely readable.
	///
	/// The fingerprint is a KEYED HMAC under a per-process random key, not a bare SHA-256. It runs
	/// only on the reject path, i.e. only on low-entropy human identifiers, where a truncated
	/// unkeyed digest is recoverable by dictionary lookup rather than brute force. Hence: stable
	/// WITHIN a process (so two occurrences of one bad value correlate), and deliberately NOT
	/// comparable across processes - which is why this test never asserts a literal digest value.
	@Test
	public void testFingerprintDoesNotLeakTheValue() {
		String fp = TracingIdValidator.fingerprint(PII_EMAIL);
		assertNotNull(fp);
		assertFalse("the fingerprint must not BE the value", fp.equals(PII_EMAIL));
		assertFalse("the fingerprint must not CONTAIN the value", fp.contains(PII_EMAIL));
		assertFalse("the fingerprint must not contain the local part of the address", fp.contains("someone"));
		assertEquals("the fingerprint is a deliberately short truncated HMAC (6 bytes = 12 hex)",
			12, fp.length());
		assertTrue("the fingerprint must be lowercase hex", fp.matches("^[0-9a-f]{12}$"));
		assertEquals("the same value must fingerprint identically WITHIN one process, so two"
			+ " occurrences of one bad value can be correlated in the log", fp,
			TracingIdValidator.fingerprint(PII_EMAIL));
		assertFalse("different values must fingerprint differently",
			fp.equals(TracingIdValidator.fingerprint(PII_NAME)));
		assertEquals("null must fingerprint to the literal 'null', not throw",
			"null", TracingIdValidator.fingerprint(null));
	}
}
