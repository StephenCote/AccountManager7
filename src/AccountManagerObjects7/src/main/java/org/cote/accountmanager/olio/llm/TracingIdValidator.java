package org.cote.accountmanager.olio.llm;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Guardrail 3 enforcement: the tracing identifiers AM7 forwards to LiteLLM/Langfuse
 * ({@code user} -&gt; trace userId, {@code session_id} -&gt; trace sessionId) MUST be opaque
 * correlation keys - an objectId/URN or a generated nonce - and NEVER a username, an email address,
 * or any other human-identifying string. Langfuse is a third-party observability store; a human
 * identifier that reaches it is accumulated PII, and the trace bodies it already holds are the full
 * prompt and completion (Guardrail 4), so the identifier is what makes a trace attributable.
 *
 * <p>Both fields are persisted on {@code olio.llm.openai.openaiRequest} and
 * {@code ChatService.chatHistory} rebuilds the request from the DB-loaded session record, so
 * <b>any client that can write those fields through the generic {@code /rest/model} routes controls
 * what reaches Langfuse.</b> Compliance had been left to every caller having read the design doc;
 * this class moves it to the emission points.
 *
 * <h2>Behaviour: DROP + WARN. Never reject, never throw.</h2>
 * A non-opaque value is silently omitted from the wire and a warning is logged. It is deliberately
 * NOT an error:
 * <ul>
 *   <li>A tracing field must not be able to fail a chat. Tracing is observability, not function.</li>
 *   <li>A reject path would hand exactly the client described above a way to break every chat by
 *       writing one bad string into a session record.</li>
 * </ul>
 * The warning logs the field name, the value's <b>length</b>, and a truncated <b>keyed</b> HMAC
 * prefix (see {@link #fingerprint(String)}) - never the value itself, or the PII merely moves from
 * the trace store into the application log, where it is if anything more widely readable.
 *
 * <h2>Shape test: a narrow allowlist, not a denylist</h2>
 * A value is opaque when it is either
 * <ol>
 *   <li>a <b>dashed</b> UUID ({@code 8-4-4-4-12} hex) - which is what every AM7 {@code objectId}
 *       is; or</li>
 *   <li>an <b>{@code am7}-family prefixed token</b>: {@code ^am7[a-z]{0,4}-[A-Za-z0-9._:-]{8,120}$}.</li>
 * </ol>
 * A denylist ("reject anything that looks like an email") would be the wrong shape of test: the set
 * of human identifiers is open-ended, while the set of correlation keys AM7 actually generates is
 * small and regular. Both live formats satisfy rule 2 ({@code am7rt-u-AM7RT-A-<hex8>},
 * {@code am7lp-u-/am7lp-s-AM7LP-B-<hex8>}) and the ISO engine's {@code session_id} - an
 * {@code iso42001.testRun.objectId} - satisfies rule 1.
 *
 * <h2>Why this is narrower than "a long token with a digit in it"</h2>
 * An earlier version accepted any 12..128 char {@code [A-Za-z0-9._:-]} token containing a digit,
 * plus an undashed 32-hex string. Security review rejected that, correctly:
 * <ul>
 *   <li><b>A 32-hex string is the shape of an MD5 of an email address</b>, and a 64-hex string the
 *       shape of a SHA-256. A hashed email is <b>pseudonymous PII and the canonical identity join
 *       key</b> - trivially re-identified by anyone holding the Langfuse keys plus a staff email
 *       list. Accepting it unconditionally was the worst single case, because it looks maximally
 *       opaque and is not.</li>
 *   <li>{@code _} and {@code -} are both in the charset, so an unpadded <b>base64url blob</b>
 *       passed - an arbitrary payload, possibly an encrypted or encoded identifier.</li>
 *   <li>A bare {@code >=12}-digit numeric string passed - i.e. card- and account-number shaped.</li>
 *   <li>{@code stephen.cote.1985} passed, which was documented as a known false accept. Under the
 *       current rules it is rejected.</li>
 * </ul>
 *
 * <h2>Known limits - stated, not papered over</h2>
 * <b>This is still a shape test, not a semantic one.</b> Anything an operator chooses to put after
 * an {@code am7} prefix is accepted, so {@code am7-someone@example.com} would fail only because
 * {@code @} is outside the charset, while {@code am7x-stephen.cote} is accepted. The rule makes PII
 * hard to send <i>accidentally</i>; it cannot make it impossible. <b>Callers still must not put a
 * human identifier in these fields.</b>
 *
 * <p><b>Deliberate FALSE REJECTS - if your id is being dropped, this is why.</b> A correlation id
 * that is neither a dashed UUID nor {@code am7}-prefixed is dropped, including: an undashed UUID,
 * a bare hex digest, an all-alphabetic nonce, and any bespoke prefix. <b>Use a dashed UUID (an
 * {@code objectId}) or prefix your nonce with {@code am7}</b> - which is what every id AM7
 * generates already does.
 */
public class TracingIdValidator {
	public static final Logger logger = LogManager.getLogger(TracingIdValidator.class);

	/** Shortest accepted suffix after the {@code am7} prefix. Below this, a value is a name. */
	public static final int MIN_SUFFIX_LENGTH = 8;
	/** Longest accepted suffix. Above this, a value is prose or an injected payload. */
	public static final int MAX_SUFFIX_LENGTH = 120;

	/// A DASHED UUID only. The undashed 32-hex form is deliberately NOT accepted - see the class
	/// javadoc: it is indistinguishable from an MD5 of an email address.
	private static final Pattern UUID_DASHED = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
	/// The am7-family correlation-id shape. The mandatory lowercase `am7` prefix plus a separator is
	/// what makes this an allowlist of AM7-generated ids rather than a generic "looks random" test.
	private static final Pattern AM7_TOKEN = Pattern.compile(
		"^am7[a-z]{0,4}-[A-Za-z0-9._:-]{" + MIN_SUFFIX_LENGTH + "," + MAX_SUFFIX_LENGTH + "}$");

	/// Per-PROCESS random HMAC key for fingerprint(). Generated once at class init and never
	/// persisted or logged.
	///
	/// WHY AN HMAC AND NOT A BARE DIGEST: fingerprint() runs ONLY on the reject path, i.e. only on
	/// the low-entropy human identifiers this class exists to catch. A truncated plain SHA-256 of
	/// "an email address from this organisation" is recoverable from a few thousand candidates in
	/// milliseconds - a dictionary lookup, not a brute force - so a bare digest in the log would
	/// have re-created in the application log exactly the PII exposure the guardrail removes from
	/// the trace store. Keying it with a per-process secret preserves the only property the log
	/// actually needs (two occurrences of the same bad value correlate WITHIN one run) and destroys
	/// the dictionary attack. The trade-off is intended: fingerprints are NOT comparable across
	/// restarts or across JVMs.
	private static final byte[] FINGERPRINT_KEY = newFingerprintKey();

	private static byte[] newFingerprintKey() {
		byte[] k = new byte[32];
		new java.security.SecureRandom().nextBytes(k);
		return k;
	}

	private TracingIdValidator() {
		// static utility
	}

	/**
	 * True when the value is a dashed UUID or an {@code am7}-prefixed token, and may therefore be
	 * forwarded to Langfuse. Everything else - null, blank, over-long, wrong-charset, bare hex
	 * digests, undashed UUIDs, base64url blobs, numeric strings, and any human identifier - is
	 * false. See the class javadoc for why the rule is this narrow.
	 */
	public static boolean isOpaque(String value) {
		if(value == null || value.isBlank()) {
			return false;
		}
		/// Tested RAW, deliberately not trimmed: surrounding whitespace is itself evidence the value
		/// is not a generated id, and both patterns are anchored so padding is rejected. Trimming
		/// here would silently "repair" a value the emission point would then forward in its
		/// original padded form.
		final String v = value;
		return UUID_DASHED.matcher(v).matches() || AM7_TOKEN.matcher(v).matches();
	}

	/**
	 * Convenience for the emission points: returns the value when it is opaque, otherwise null
	 * after logging the DROP + WARN line. {@code fieldName} is the request field being dropped
	 * ("user" / "session_id") and appears in the log; the value never does.
	 */
	public static String opaqueOrNull(String fieldName, String value) {
		if(value == null || value.isBlank()) {
			return null;
		}
		if(isOpaque(value)) {
			return value;
		}
		logger.warn("Dropping non-opaque tracing value for '" + fieldName + "' (Guardrail 3): it is not an"
			+ " objectId/URN or a generated nonce, so it must not reach Langfuse. length=" + value.length()
			+ " sha256=" + fingerprint(value) + " (the value itself is deliberately not logged - logging it"
			+ " would move the PII into the application log). Use an opaque correlation id; see"
			+ " TracingIdValidator for the accepted shapes.");
		return null;
	}

	/**
	 * A truncated <b>HMAC-SHA256</b> of the value under a per-process random key: a correlation
	 * handle for the log, so two occurrences of the same rejected value can be tied together
	 * <b>within one process lifetime</b> without the log ever carrying the value.
	 *
	 * <p><b>It is a correlation handle, not a privacy control in its own right</b> - the privacy
	 * control is that the value itself is never logged. The per-process key is what makes the handle
	 * safe to write down: an unkeyed truncated digest of a human identifier is recoverable by
	 * dictionary lookup (see {@code FINGERPRINT_KEY}), so a bare SHA-256 prefix would have moved the
	 * PII into the application log rather than protecting it. An earlier version of this javadoc
	 * claimed the digest was "never reversible in practice for prose"; that was true of prose and
	 * false of the only inputs this method actually receives, which are rejected identifiers.
	 *
	 * <p>Consequences to expect: fingerprints are <b>not</b> comparable across restarts or across
	 * JVMs, and are not an identifier for anything. Do not persist one or use it as a key.
	 */
	public static String fingerprint(String value) {
		if(value == null) {
			return "null";
		}
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(FINGERPRINT_KEY, "HmacSHA256"));
			byte[] dig = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for(int i = 0; i < 6 && i < dig.length; i++) {
				sb.append(String.format("%02x", dig[i]));
			}
			return sb.toString();
		}
		catch(Exception e) {
			return "unavailable";
		}
	}
}
