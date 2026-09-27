package org.cote.accountmanager.olio.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.net.ssl.SSLSession;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.JSONUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * In-process LLM emulator selected by {@code system.connection.dialect = EMULATOR}.
 *
 * <p>Purpose: let a Ux-driven Playwright run (and JUnit) exercise the PictureBook pipeline — chapter
 * detection, chunked extraction, retry/circuit-breaker, checkpointing — end to end WITHOUT a live model
 * server. {@link Chat#chatInternal} calls {@link #respond} in place of
 * {@code ClientUtil.postToRecordAndStream} and consumes the result through the exact same
 * OpenAI-compatible SSE parser, so nothing downstream knows the difference.
 *
 * <p>Replay order for a request: exact-key fixture ({@code <root>/<set>/<sha256>.json}) → synthesizer
 * (a small, deterministic generator that emits the JSON shapes the real PictureBook parsers accept) →
 * miss. A miss in strict mode is an emulated HTTP 500; otherwise the synthesizer always answers.
 *
 * <p>Configuration is deployment-global and boot-pinned: {@link #configure} is called once from
 * {@code RestServiceEventListener} with the {@code llm.emulator.fixtureRoot} / {@code llm.emulator.recordDir}
 * context params (both empty → inert). It is stored as ONE immutable holder in a single {@code volatile}
 * field so a reader can never observe a torn (root, recordDir) pair. Console7 never configures it, so
 * an EMULATOR connection dispatched from Console7 fails fast with a clear last-call error rather than
 * touching the filesystem.
 *
 * <p>Filesystem safety: the user-record {@code serverUrl} is never used as a path. Only the set name
 * is taken from it, and only after it matches {@code ^[A-Za-z0-9_-]{1,64}$}; the resolved directory
 * must then still start with the configured root. Only files named {@code <64 hex>.json} under the set
 * directory are ever read. The recorder applies the same rules to the model-derived directory name and
 * never writes auth tokens or headers; it is refused outright (recording disabled, replay kept) when its
 * directory equals, sits under, or contains the fixture root, and it skips any exchange larger than
 * {@link #MAX_RECORD_CHARS}.
 */
public final class LlmEmulator {

	private static final Logger logger = LogManager.getLogger(LlmEmulator.class);

	public static final String SCHEME = "emulator://";
	public static final String MANIFEST_FILE = "manifest.json";
	/// Set (directory) names and recorder directory names must match this exactly.
	static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
	private static final Pattern FIXTURE_FILE = Pattern.compile("^[0-9a-f]{64}\\.json$");
	/// Split synthesized/replayed content into SSE deltas of at most this many chars, so the consumer's
	/// accumulate path sees a realistic multi-chunk stream rather than one giant delta.
	private static final int SSE_CHUNK_CHARS = 240;
	/// Recorder cap: an exchange whose request-message content plus response content exceeds this many
	/// chars is skipped (WARN, not counted as recorded). A hostile or runaway upstream must not be able
	/// to fill the disk one fixture at a time.
	public static final int MAX_RECORD_CHARS = 2_000_000;

	public static final String KIND_EXTRACT_CHUNK = "extract-chunk";
	public static final String KIND_EXTRACT_SCENES = "extract-scenes";
	public static final String KIND_EXTRACT_SCENE_LIST = "extract-scene-list";
	public static final String KIND_EXTRACT_SCENE = "extract-scene";
	public static final String KIND_EXTRACT_CHARACTER_LIST = "extract-character-list";
	public static final String KIND_EXTRACT_CHARACTER = "extract-character";
	public static final String KIND_REDUCE_CHARACTER = "reduce-character";
	public static final String KIND_GUESS_APPAREL = "guess-apparel";
	public static final String KIND_SCENE_BLURB = "scene-blurb";
	public static final String KIND_SD_PROMPT = "sd-prompt";
	public static final String KIND_CHAT = "chat";

	/**
	 * Default prompt-kind detectors, evaluated in this order against the LAST user message and then the
	 * system prompt. Anchored on distinctive phrases of the {@code pictureBook.*} prompt templates.
	 * A set's {@code manifest.json} may override any pattern via {@code "kinds": {"<kind>": "<regex>"}}.
	 */
	private static final LinkedHashMap<String, Pattern> DEFAULT_KINDS = new LinkedHashMap<>();
	static {
		DEFAULT_KINDS.put(KIND_EXTRACT_CHUNK, Pattern.compile("TEXT SEGMENT:\\s*\\n", Pattern.CASE_INSENSITIVE));
		DEFAULT_KINDS.put(KIND_REDUCE_CHARACTER, Pattern.compile("Combine the passages below", Pattern.CASE_INSENSITIVE));
		DEFAULT_KINDS.put(KIND_EXTRACT_CHARACTER, Pattern.compile("physical and costume details", Pattern.CASE_INSENSITIVE));
		DEFAULT_KINDS.put(KIND_EXTRACT_CHARACTER_LIST, Pattern.compile("Extract all named characters", Pattern.CASE_INSENSITIVE));
		DEFAULT_KINDS.put(KIND_EXTRACT_SCENE_LIST, Pattern.compile("Extract all key scenes", Pattern.CASE_INSENSITIVE));
		DEFAULT_KINDS.put(KIND_EXTRACT_SCENE, Pattern.compile("Extract the primary scene", Pattern.CASE_INSENSITIVE));
		DEFAULT_KINDS.put(KIND_EXTRACT_SCENES, Pattern.compile("most visually notable scenes", Pattern.CASE_INSENSITIVE));
		DEFAULT_KINDS.put(KIND_GUESS_APPAREL, Pattern.compile("Catalog \\(choose ONLY", Pattern.CASE_INSENSITIVE));
		DEFAULT_KINDS.put(KIND_SCENE_BLURB, Pattern.compile("picture book caption", Pattern.CASE_INSENSITIVE));
		DEFAULT_KINDS.put(KIND_SD_PROMPT, Pattern.compile("Create a Stable Diffusion", Pattern.CASE_INSENSITIVE));
	}

	/// Small fixed race set used ONLY when the reduce-character prompt does not carry its own option list.
	private static final String[] FALLBACK_RACES = { "Unknown" };

	/// Capitalized-word runs that are almost never character names in narrative prose.
	private static final Set<String> NAME_STOPWORDS = new HashSet<>(Arrays.asList(
		"The", "A", "An", "And", "But", "Or", "Chapter", "I", "He", "She", "It", "They", "We", "You",
		"When", "Then", "Now", "In", "On", "At", "This", "That", "These", "Those", "There", "Here",
		"His", "Her", "Their", "Our", "My", "Your", "Its", "If", "So", "As", "For", "From", "With",
		"Not", "No", "Yes", "Oh", "Well", "What", "Where", "Why", "How", "Who", "Which", "While",
		"After", "Before", "Once", "Still", "Yet", "Even", "Just", "Only", "Every", "Some", "Any",
		"All", "One", "Two", "Three", "First", "Last", "Next", "Later", "Soon", "Suddenly", "Meanwhile",
		"Perhaps", "Maybe", "Because", "Though", "Although", "Until", "Since", "Also", "Again", "Never",
		"Always", "Text", "Segment", "Story", "Scene", "Previously", "Established", "Characters",
		"Naming", "Return", "Extract", "Given", "Combine", "Passages"));

	private static final Pattern CAPITALIZED_RUN = Pattern.compile("\\b([A-Z][a-z]{1,20}(?:\\s+[A-Z][a-z]{1,20}){0,2})\\b");

	// ------------------------------------------------------------------------------------------------
	// Configuration — one immutable holder, one volatile field.
	// ------------------------------------------------------------------------------------------------

	private static final class Config {
		/// Absolute, normalized fixture root, or null when the emulator is not configured.
		final Path fixtureRoot;
		/// Absolute, normalized recorder directory, or null when recording is off.
		final Path recordDir;
		/// Deployment-wide strict flag (miss → error). A set manifest may also turn strict on.
		final boolean strict;

		Config(Path fixtureRoot, Path recordDir, boolean strict) {
			this.fixtureRoot = fixtureRoot;
			this.recordDir = recordDir;
			this.strict = strict;
		}
	}

	private static volatile Config CONFIG = new Config(null, null, false);

	private static final AtomicLong HIT = new AtomicLong();
	private static final AtomicLong MISS = new AtomicLong();
	private static final AtomicLong SYNTH = new AtomicLong();
	private static final AtomicLong FAULT = new AtomicLong();
	private static final AtomicLong RECORDED = new AtomicLong();
	/// Per-(set, fault index) match counters driving manifest {@code faults[].occurrence}.
	private static final ConcurrentHashMap<String, AtomicLong> FAULT_COUNTERS = new ConcurrentHashMap<>();

	private LlmEmulator() {
	}

	/**
	 * Configure once at boot. Blank/null for both → inert ({@link #isConfigured()} false). The recorder
	 * may be enabled without a fixture root (recording real upstream traffic on a normal deployment),
	 * and the fixture root without a recorder (the test stack). Re-invocation replaces the whole holder.
	 */
	public static void configure(String fixtureRoot, String recordDir) {
		configure(fixtureRoot, recordDir, false);
	}

	public static void configure(String fixtureRoot, String recordDir, boolean strict) {
		Path root = null;
		Path rec = null;
		if (fixtureRoot != null && !fixtureRoot.isBlank()) {
			try {
				root = Paths.get(fixtureRoot.trim()).toAbsolutePath().normalize();
			} catch (Exception e) {
				logger.error("LLM emulator: invalid fixture root '" + fixtureRoot + "': " + e.getMessage());
				root = null;
			}
		}
		if (recordDir != null && !recordDir.isBlank()) {
			try {
				rec = Paths.get(recordDir.trim()).toAbsolutePath().normalize();
			} catch (Exception e) {
				logger.error("LLM emulator: invalid record dir '" + recordDir + "': " + e.getMessage());
				rec = null;
			}
		}
		if (root != null && rec != null && (rec.equals(root) || rec.startsWith(root) || root.startsWith(rec))) {
			/// Refuse a recorder that writes inside, above, or at the fixture tree. With the two
			/// overlapping, a user-chosen chatConfig.model equal to an existing set name (e.g.
			/// "harlots-eight") would write THAT user's upstream responses into a shared fixture set and
			/// replay them to every other tenant. Replay stays on; recording is disabled. Both paths are
			/// deployer-configured boot values, so naming them here leaks nothing.
			logger.error("LLM emulator: recordDir " + rec + " overlaps fixtureRoot " + root
				+ " (equal, nested, or containing) — RECORDER DISABLED; fixture replay remains enabled. Configure disjoint directories.");
			rec = null;
		}
		CONFIG = new Config(root, rec, strict);
		if (root != null) {
			logger.info("LLM emulator configured: fixtureRoot=" + root + " strict=" + strict
				+ (Files.isDirectory(root) ? "" : " (WARNING: directory does not exist yet)"));
		}
		if (rec != null) {
			/// WARN once, deliberately loud: every successful buffer-mode LLM exchange on this deployment
			/// will be written to disk (prompts and completions — never tokens or headers).
			logger.warn("LLM emulator RECORDER is ON: every successful buffer-mode LLM exchange will be written under "
				+ rec + " (request messages + response content; no auth tokens, no headers)");
		}
	}

	/** True when a fixture root is configured — the precondition for dispatching an EMULATOR connection. */
	public static boolean isConfigured() {
		return CONFIG.fixtureRoot != null;
	}

	/** The recorder directory, or null when recording is off. */
	public static Path recordDir() {
		return CONFIG.recordDir;
	}

	/** The configured fixture root, or null. Exposed for diagnostics/tests only. */
	public static Path fixtureRoot() {
		return CONFIG.fixtureRoot;
	}

	/** Immutable snapshot of the counters. */
	public static Map<String, Long> stats() {
		Map<String, Long> m = new LinkedHashMap<>();
		m.put("hit", HIT.get());
		m.put("miss", MISS.get());
		m.put("synth", SYNTH.get());
		m.put("fault", FAULT.get());
		m.put("recorded", RECORDED.get());
		return Collections.unmodifiableMap(m);
	}

	/**
	 * Zero every counter, INCLUDING the per-set fault occurrence counters. Exists because the Tomcat JVM
	 * is long-lived: a manifest fault with {@code occurrence: 3} fires exactly once per process unless
	 * something resets it, so a Playwright run that wants to exercise the retry path must be able to
	 * reset before it starts (the stats endpoint's {@code ?reset=true}).
	 */
	public static void resetCounters() {
		HIT.set(0);
		MISS.set(0);
		SYNTH.set(0);
		FAULT.set(0);
		RECORDED.set(0);
		FAULT_COUNTERS.clear();
	}

	// ------------------------------------------------------------------------------------------------
	// Request key
	// ------------------------------------------------------------------------------------------------

	/**
	 * Stable key for a wire request: SHA-256 hex over the canonical form of {@code model} and every
	 * {@code messages[i].role}/{@code messages[i].content}, with fixed separators, UTF-8. Nothing else
	 * (no options, no stream flag, no temperature) participates, so a request built for an OPENAI_COMPAT
	 * connection and the same request built for an EMULATOR connection produce the same key — which is
	 * what lets a recording made against the real server replay under the emulator.
	 */
	public static String requestKey(OpenAIRequest req) {
		StringBuilder sb = new StringBuilder();
		sb.append(req != null && req.getModel() != null ? req.getModel() : "").append('\u0000');
		if (req != null && req.getMessages() != null) {
			for (OpenAIMessage m : req.getMessages()) {
				if (m == null) continue;
				sb.append(m.getRole() != null ? m.getRole() : "").append('\u0001');
				sb.append(m.getContent() != null ? m.getContent() : "").append('\u0002');
			}
		}
		return sha256Hex(sb.toString());
	}

	static String sha256Hex(String s) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(d.length * 2);
			for (byte b : d) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (Exception e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Set / path resolution
	// ------------------------------------------------------------------------------------------------

	/**
	 * Extract and validate the set name from {@code emulator://<set>}. Returns null for anything that is
	 * not exactly the scheme followed by a safe name — so {@code emulator://../x}, {@code emulator://a/b}
	 * and an empty set are all rejected before any path arithmetic happens.
	 */
	public static String parseSetName(String serverUrl) {
		if (serverUrl == null) return null;
		String u = serverUrl.trim();
		if (!u.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) return null;
		String set = u.substring(SCHEME.length());
		/// Tolerate a single trailing slash ("emulator://set/") since URL fields often get one.
		if (set.endsWith("/")) set = set.substring(0, set.length() - 1);
		return SAFE_NAME.matcher(set).matches() ? set : null;
	}

	/**
	 * Resolve {@code root/name} and refuse anything that escapes the root. {@code name} must already
	 * have passed {@link #SAFE_NAME}; the normalize+startsWith check is defence in depth, not the
	 * primary gate.
	 */
	static Path resolveUnder(Path root, String name) {
		if (root == null || name == null || !SAFE_NAME.matcher(name).matches()) return null;
		Path p = root.resolve(name).normalize();
		if (!p.startsWith(root)) return null;
		return p;
	}

	/** Model name → safe recorder directory name (anything outside [A-Za-z0-9_-] becomes '_'). */
	static String sanitizeName(String model) {
		String base = (model == null || model.isBlank()) ? "unknown-model" : model.trim();
		StringBuilder sb = new StringBuilder();
		for (char c : base.toCharArray()) {
			sb.append((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
		}
		String s = sb.toString();
		if (s.length() > 64) s = s.substring(0, 64);
		return s.isEmpty() ? "unknown-model" : s;
	}

	// ------------------------------------------------------------------------------------------------
	// Manifest
	// ------------------------------------------------------------------------------------------------

	static final class Fault {
		final String kind;
		final String keyPrefix;
		final long occurrence;
		final String mode;

		Fault(String kind, String keyPrefix, long occurrence, String mode) {
			this.kind = kind;
			this.keyPrefix = keyPrefix;
			this.occurrence = occurrence;
			this.mode = mode;
		}

		boolean matches(String k, String key) {
			if (kind != null && !kind.isBlank()) {
				if (!kind.equals(k)) return false;
			}
			if (keyPrefix != null && !keyPrefix.isBlank()) {
				if (key == null || !key.startsWith(keyPrefix)) return false;
			}
			return (kind != null && !kind.isBlank()) || (keyPrefix != null && !keyPrefix.isBlank());
		}
	}

	static final class Manifest {
		final boolean strict;
		final LinkedHashMap<String, Pattern> kinds;
		final List<Fault> faults;

		Manifest(boolean strict, LinkedHashMap<String, Pattern> kinds, List<Fault> faults) {
			this.strict = strict;
			this.kinds = kinds;
			this.faults = faults;
		}

		static Manifest defaults() {
			return new Manifest(false, DEFAULT_KINDS, Collections.emptyList());
		}
	}

	@SuppressWarnings("unchecked")
	static Manifest loadManifest(Path setDir) {
		if (setDir == null) return Manifest.defaults();
		Path mf = setDir.resolve(MANIFEST_FILE);
		if (!Files.isRegularFile(mf)) return Manifest.defaults();
		try {
			Map<String, Object> m = JSONUtil.getMap(Files.readAllBytes(mf), String.class, Object.class);
			if (m == null) return Manifest.defaults();
			boolean strict = Boolean.TRUE.equals(m.get("strict"));
			LinkedHashMap<String, Pattern> kinds = new LinkedHashMap<>(DEFAULT_KINDS);
			Object ko = m.get("kinds");
			if (ko instanceof Map) {
				for (Map.Entry<String, Object> e : ((Map<String, Object>) ko).entrySet()) {
					if (e.getValue() instanceof String) {
						try {
							kinds.put(e.getKey(), Pattern.compile((String) e.getValue(), Pattern.CASE_INSENSITIVE));
						} catch (Exception pe) {
							logger.warn("LLM emulator manifest " + mf + ": bad regex for kind '" + e.getKey() + "': " + pe.getMessage());
						}
					}
				}
			}
			List<Fault> faults = new ArrayList<>();
			Object fo = m.get("faults");
			if (fo instanceof List) {
				for (Object o : (List<Object>) fo) {
					if (!(o instanceof Map)) continue;
					Map<String, Object> f = (Map<String, Object>) o;
					String kind = f.get("kind") instanceof String ? (String) f.get("kind") : null;
					String keyPrefix = f.get("keyPrefix") instanceof String ? (String) f.get("keyPrefix") : null;
					long occ = f.get("occurrence") instanceof Number ? ((Number) f.get("occurrence")).longValue() : 1L;
					String mode = f.get("mode") instanceof String ? ((String) f.get("mode")).trim().toLowerCase() : "http500";
					faults.add(new Fault(kind, keyPrefix, Math.max(1L, occ), mode));
				}
			}
			return new Manifest(strict, kinds, faults);
		} catch (Exception e) {
			logger.warn("LLM emulator: could not read manifest " + mf + ": " + e.getMessage());
			return Manifest.defaults();
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Kind detection
	// ------------------------------------------------------------------------------------------------

	/** Detect the prompt kind using the default detectors. */
	public static String detectKind(OpenAIRequest req) {
		return detectKind(req, DEFAULT_KINDS);
	}

	static String detectKind(OpenAIRequest req, Map<String, Pattern> kinds) {
		String lastUser = lastMessageContent(req, "user");
		String system = lastMessageContent(req, "system");
		for (Map.Entry<String, Pattern> e : kinds.entrySet()) {
			Pattern p = e.getValue();
			if (p == null) continue;
			if (lastUser != null && p.matcher(lastUser).find()) return e.getKey();
		}
		for (Map.Entry<String, Pattern> e : kinds.entrySet()) {
			Pattern p = e.getValue();
			if (p == null) continue;
			if (system != null && p.matcher(system).find()) return e.getKey();
		}
		return KIND_CHAT;
	}

	static String lastMessageContent(OpenAIRequest req, String role) {
		if (req == null || req.getMessages() == null) return null;
		List<OpenAIMessage> msgs = req.getMessages();
		for (int i = msgs.size() - 1; i >= 0; i--) {
			OpenAIMessage m = msgs.get(i);
			if (m != null && role.equalsIgnoreCase(m.getRole())) return m.getContent();
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------
	// respond()
	// ------------------------------------------------------------------------------------------------

	/**
	 * Answer a wire request in place of the network. Never throws: every failure shape is expressed as
	 * a completed/failed future the {@link Chat} consumer already knows how to handle.
	 *
	 * @param serverUrl      the connection's {@code emulator://<set>} pseudo-URL (never used as a path)
	 * @param wireReq        the pruned request actually being "sent" — the key is computed on this
	 * @param serializedReq  the serialized body (unused except for diagnostics; kept so the seam has the
	 *                       same information the real transport gets)
	 */
	public static CompletableFuture<HttpResponse<Stream<String>>> respond(String serverUrl, OpenAIRequest wireReq, String serializedReq) {
		Config cfg = CONFIG;
		if (cfg.fixtureRoot == null) {
			return CompletableFuture.completedFuture(errorResponse(503, "LLM emulator not configured on this deployment"));
		}
		String set = parseSetName(serverUrl);
		if (set == null) {
			MISS.incrementAndGet();
			return CompletableFuture.completedFuture(errorResponse(400,
				"LLM emulator: invalid emulator set in serverUrl (expected emulator://<set>, set matching " + SAFE_NAME.pattern() + ")"));
		}
		Path setDir = resolveUnder(cfg.fixtureRoot, set);
		if (setDir == null) {
			MISS.incrementAndGet();
			return CompletableFuture.completedFuture(errorResponse(400, "LLM emulator: set '" + set + "' resolves outside the fixture root"));
		}
		Manifest manifest = loadManifest(setDir);
		String key = requestKey(wireReq);
		String kind = detectKind(wireReq, manifest.kinds);

		/// Faults first: a fault is a statement about the Nth call of a kind, regardless of whether a
		/// fixture exists for it.
		Fault fault = matchFault(set, manifest, kind, key);
		if (fault != null) {
			FAULT.incrementAndGet();
			logger.warn("LLM emulator FAULT fired: set=" + set + " kind=" + kind + " mode=" + fault.mode + " occurrence=" + fault.occurrence);
			switch (fault.mode) {
				case "unreachable":
					return CompletableFuture.failedFuture(new java.net.ConnectException("emulated: connection refused (" + serverUrl + ")"));
				case "empty":
					return CompletableFuture.completedFuture(sseResponse("[]"));
				case "http500":
				default:
					return CompletableFuture.completedFuture(errorResponse(500, "emulated failure"));
			}
		}

		/// 1. Exact-key fixture.
		String content = readFixtureContent(setDir, key);
		if (content != null) {
			HIT.incrementAndGet();
			logger.info("LLM emulator HIT: set=" + set + " kind=" + kind + " key=" + key.substring(0, 12));
			return CompletableFuture.completedFuture(sseResponse(content));
		}

		/// 2. Strict → miss is an error.
		if (cfg.strict || manifest.strict) {
			MISS.incrementAndGet();
			logger.warn("LLM emulator MISS (strict): set=" + set + " kind=" + kind + " key=" + key);
			return CompletableFuture.completedFuture(errorResponse(500,
				"LLM emulator fixture miss (strict): set=" + set + " kind=" + kind + " key=" + key));
		}

		/// 3. Synthesize.
		String synth = synthesize(kind, wireReq, key);
		SYNTH.incrementAndGet();
		logger.info("LLM emulator SYNTH: set=" + set + " kind=" + kind + " key=" + key.substring(0, 12) + " chars=" + synth.length());
		return CompletableFuture.completedFuture(sseResponse(synth));
	}

	private static Fault matchFault(String set, Manifest manifest, String kind, String key) {
		if (manifest.faults == null || manifest.faults.isEmpty()) return null;
		for (int i = 0; i < manifest.faults.size(); i++) {
			Fault f = manifest.faults.get(i);
			if (!f.matches(kind, key)) continue;
			AtomicLong c = FAULT_COUNTERS.computeIfAbsent(set + "|" + i, k -> new AtomicLong());
			long n = c.incrementAndGet();
			if (n == f.occurrence) return f;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static String readFixtureContent(Path setDir, String key) {
		String fname = key + ".json";
		if (!FIXTURE_FILE.matcher(fname).matches()) return null;
		Path f = setDir.resolve(fname).normalize();
		if (!f.startsWith(setDir) || !Files.isRegularFile(f)) return null;
		try {
			Map<String, Object> m = JSONUtil.getMap(Files.readAllBytes(f), String.class, Object.class);
			if (m == null) return null;
			Object resp = m.get("response");
			if (resp instanceof Map) {
				Object c = ((Map<String, Object>) resp).get("content");
				if (c instanceof String) return (String) c;
			}
			/// Tolerate a flat {"content": "..."} fixture written by hand.
			Object c = m.get("content");
			return c instanceof String ? (String) c : null;
		} catch (Exception e) {
			logger.warn("LLM emulator: unreadable fixture " + f + ": " + e.getMessage());
			return null;
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Recorder
	// ------------------------------------------------------------------------------------------------

	/**
	 * Persist one successful real exchange as a replayable fixture under
	 * {@code <recordDir>/<sanitized model>/<sha256>.json}. Writes ONLY the model, the request messages
	 * (role/content) and the raw assistant content — never the auth token, never headers, never the
	 * server URL. Any failure is a WARN; it can never affect the call that produced the exchange.
	 *
	 * @return true when a file was written
	 */
	public static boolean record(OpenAIRequest wireReq, String rawContent) {
		Path rec = CONFIG.recordDir;
		if (rec == null || wireReq == null || rawContent == null) return false;
		try {
			/// Size gate BEFORE any filesystem work, so a skipped exchange creates neither file nor directory.
			long total = exchangeChars(wireReq, rawContent);
			if (total > MAX_RECORD_CHARS) {
				logger.warn("LLM emulator recorder: skipping exchange of " + total + " chars (cap " + MAX_RECORD_CHARS
					+ "); model=" + sanitizeName(wireReq.getModel()) + " kind=" + detectKind(wireReq));
				return false;
			}
			String key = requestKey(wireReq);
			String kind = detectKind(wireReq);
			String dirName = sanitizeName(wireReq.getModel());
			Path dir = resolveUnder(rec, dirName);
			if (dir == null) {
				logger.warn("LLM emulator recorder: refusing directory name '" + dirName + "'");
				return false;
			}
			Files.createDirectories(dir);
			Path out = dir.resolve(key + ".json").normalize();
			if (!out.startsWith(dir)) return false;

			Map<String, Object> request = new LinkedHashMap<>();
			request.put("model", wireReq.getModel());
			List<Map<String, String>> msgs = new ArrayList<>();
			if (wireReq.getMessages() != null) {
				for (OpenAIMessage m : wireReq.getMessages()) {
					if (m == null) continue;
					Map<String, String> mm = new LinkedHashMap<>();
					mm.put("role", m.getRole());
					mm.put("content", m.getContent());
					msgs.add(mm);
				}
			}
			request.put("messages", msgs);
			Map<String, Object> response = new LinkedHashMap<>();
			response.put("content", rawContent);

			Map<String, Object> fixture = new LinkedHashMap<>();
			fixture.put("key", key);
			fixture.put("kind", kind);
			fixture.put("model", wireReq.getModel());
			fixture.put("request", request);
			fixture.put("response", response);
			fixture.put("recordedAt", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

			String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(fixture);
			Files.write(out, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			RECORDED.incrementAndGet();
			logger.info("LLM emulator recorded " + kind + " -> " + out);
			return true;
		} catch (Exception e) {
			logger.warn("LLM emulator recorder failed (call unaffected): " + e.getClass().getSimpleName() + ": " + e.getMessage());
			return false;
		}
	}

	/** Chars the recorder would persist: every request message's content plus the response content. */
	static long exchangeChars(OpenAIRequest req, String rawContent) {
		long total = rawContent != null ? rawContent.length() : 0L;
		if (req != null && req.getMessages() != null) {
			for (OpenAIMessage m : req.getMessages()) {
				if (m != null && m.getContent() != null) total += m.getContent().length();
			}
		}
		return total;
	}

	// ------------------------------------------------------------------------------------------------
	// Synthesizer
	// ------------------------------------------------------------------------------------------------

	/**
	 * Deterministic stand-in content for a request of the given kind. Everything here emits the exact
	 * JSON shapes the PictureBook parsers ({@code parseLlmJsonObject}/{@code parseLlmJsonArray}) accept;
	 * plain-text kinds emit short plausible strings.
	 */
	public static String synthesize(String kind, OpenAIRequest req, String key) {
		String user = Optional.ofNullable(lastMessageContent(req, "user")).orElse("");
		String k = kind == null ? KIND_CHAT : kind;
		try {
			switch (k) {
				case KIND_EXTRACT_CHUNK: {
					String chunk = sectionAfter(user, "TEXT SEGMENT:");
					List<Map<String, Object>> scenes = buildScenes(chunk, 3, false);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("additions", scenes);
					out.put("revisions", new ArrayList<>());
					out.put("removals", new ArrayList<>());
					return toJson(out);
				}
				case KIND_EXTRACT_SCENES: {
					String story = sectionAfter(user, "STORY:");
					int count = 3;
					Matcher cm = Pattern.compile("identify the (\\d+) most").matcher(user);
					if (cm.find()) {
						try { count = Math.max(2, Math.min(3, Integer.parseInt(cm.group(1)))); } catch (NumberFormatException ignore) { /* default */ }
					}
					return toJson(buildScenes(story, count, true));
				}
				case KIND_EXTRACT_SCENE_LIST: {
					String story = sectionAfter(user, "STORY:");
					List<Map<String, Object>> scenes = buildScenes(story, 3, false);
					List<Map<String, Object>> out = new ArrayList<>();
					for (Map<String, Object> s : scenes) {
						Map<String, Object> o = new LinkedHashMap<>();
						o.put("title", s.get("title"));
						o.put("setting", s.get("setting"));
						o.put("action", s.get("action"));
						o.put("mood", s.get("mood"));
						List<String> names = new ArrayList<>();
						Object chars = s.get("characters");
						if (chars instanceof List) {
							for (Object c : (List<?>) chars) {
								if (c instanceof Map) names.add(String.valueOf(((Map<?, ?>) c).get("name")));
							}
						}
						o.put("characters", names);
						out.add(o);
					}
					return toJson(out);
				}
				case KIND_EXTRACT_SCENE: {
					String story = sectionAfter(user, "STORY:");
					String firstLine = firstNonBlankLine(story, 60);
					Map<String, Object> o = new LinkedHashMap<>();
					o.put("setting", "The place where \"" + firstLine + "\" unfolds, drawn in plain daylight.");
					o.put("action", "The characters move through the events of the passage.");
					o.put("mood", "quiet, attentive, natural light");
					return toJson(o);
				}
				case KIND_EXTRACT_CHARACTER_LIST: {
					String story = sectionAfter(user, "STORY:");
					List<String> names = extractNames(story, 6);
					if (names.isEmpty()) names.add("Narrator");
					List<Map<String, Object>> out = new ArrayList<>();
					for (String n : names) {
						Map<String, Object> o = new LinkedHashMap<>();
						o.put("name", n);
						out.add(o);
					}
					return toJson(out);
				}
				case KIND_REDUCE_CHARACTER:
				case KIND_EXTRACT_CHARACTER: {
					String name = firstGroup(user, "for the character named '([^']+)'", null);
					if (name == null) name = firstGroup(user, "character detail for '([^']+)'", "Unknown");
					boolean reduce = KIND_REDUCE_CHARACTER.equals(k);
					int h = Math.abs(key != null ? key.hashCode() : name.hashCode());
					Map<String, Object> o = new LinkedHashMap<>();
					o.put("name", name);
					o.put("gender", (h % 2 == 0) ? "female" : "male");
					o.put("age_approx", String.valueOf(22 + (h % 30)));
					if (reduce) {
						o.put("race", pickRace(user, h));
						o.put("race_evidence", "");
					}
					o.put("ethnicity", "");
					if (reduce) o.put("ethnicity_evidence", "");
					o.put("skills", Arrays.asList("observation", "conversation"));
					Map<String, Object> phys = new LinkedHashMap<>();
					phys.put("height", (h % 3 == 0) ? "tall" : "average");
					phys.put("build", (h % 2 == 0) ? "slender" : "sturdy");
					phys.put("hair", new String[] { "dark brown, shoulder-length", "black, cropped short", "auburn, braided", "grey, swept back" }[h % 4]);
					phys.put("eyes", new String[] { "brown", "green", "hazel", "grey" }[(h / 4) % 4]);
					phys.put("skin", new String[] { "olive", "pale", "deep brown", "tan" }[(h / 16) % 4]);
					phys.put("distinguishing", "a small scar above the left eyebrow");
					o.put("physical", phys);
					o.put("clothing_style", (h % 2 == 0) ? "practical, well-worn" : "neat and formal");
					o.put("outfit_notes", (h % 2 == 0) ? "a dark wool coat over a plain shirt, sturdy boots" : "a pressed jacket, white shirt, polished shoes");
					o.put("personality_hint", (h % 3 == 0) ? "watchful and dry-humored" : "earnest and quick to speak");
					if (reduce) {
						o.put("description", name + " is a " + phys.get("build") + " " + o.get("gender") + " with "
							+ phys.get("hair") + " hair and " + phys.get("eyes") + " eyes, wearing " + o.get("outfit_notes") + ".");
					}
					return toJson(o);
				}
				case KIND_GUESS_APPAREL: {
					String catalog = firstGroup(user, "exact item names\\):\\s*([^\\n]+)", "");
					List<String> items = new ArrayList<>();
					for (String c : catalog.split(",")) {
						String t = c.trim();
						if (!t.isEmpty()) items.add(t);
						if (items.size() >= 3) break;
					}
					if (items.isEmpty()) items.add("shirt");
					Map<String, Object> o = new LinkedHashMap<>();
					o.put("items", items);
					o.put("description", "A simple, coherent outfit built from " + String.join(", ", items) + ".");
					return toJson(o);
				}
				case KIND_SCENE_BLURB: {
					String title = firstGroup(user, "TITLE:\\s*([^\\n]+)", "the scene");
					return "In " + title.trim() + ", the light settles and the characters pause mid-motion. "
						+ "Every detail of the moment is caught in place, waiting for what comes next.";
				}
				case KIND_SD_PROMPT: {
					String setting = firstGroup(user, "SETTING:\\s*([^\\n]+)", "a quiet room");
					return "masterpiece, best quality, " + setting.trim() + ", soft natural lighting, detailed environment, cinematic composition";
				}
				default:
					return "Emulated response.";
			}
		} catch (Exception e) {
			logger.warn("LLM emulator synthesizer failed for kind " + k + ": " + e.getMessage());
			return "{}";
		}
	}

	/** The text after the first line that starts with {@code marker}, with a trailing /no_think removed. */
	static String sectionAfter(String user, String marker) {
		if (user == null) return "";
		int idx = user.indexOf(marker);
		if (idx < 0) return user;
		String rest = user.substring(idx + marker.length());
		if (rest.startsWith("\r\n")) rest = rest.substring(2);
		else if (rest.startsWith("\n")) rest = rest.substring(1);
		rest = rest.replaceAll("(?s)\\s*/no_think\\s*$", "");
		/// Prompts that place a closing instruction after the text ("Return only the JSON array.") — drop it.
		rest = rest.replaceAll("(?s)\\n\\s*Return only the JSON (array|object)\\.?\\s*$", "");
		return rest;
	}

	static String firstNonBlankLine(String text, int maxChars) {
		if (text == null) return "";
		for (String line : text.split("\\r?\\n")) {
			String t = line.trim();
			if (!t.isEmpty()) {
				return t.length() > maxChars ? t.substring(0, maxChars).trim() : t;
			}
		}
		return "";
	}

	static String firstGroup(String text, String regex, String dflt) {
		if (text == null) return dflt;
		Matcher m = Pattern.compile(regex).matcher(text);
		return m.find() ? m.group(1) : dflt;
	}

	static String pickRace(String user, int h) {
		/// The reduce prompt carries its own option list ("... or \"Unknown\" if the passages do not
		/// literally name it: A, B, C"). Choose from it so the consumer's enum mapping always resolves.
		String opts = firstGroup(user, "For \"race\"[^\\n]*?:\\s*([^\\n]+)", null);
		List<String> races = new ArrayList<>();
		if (opts != null) {
			for (String o : opts.split(",")) {
				String t = o.trim();
				if (!t.isEmpty()) races.add(t);
			}
		}
		if (races.isEmpty()) races.addAll(Arrays.asList(FALLBACK_RACES));
		return races.get(h % races.size());
	}

	/** Capitalized-word runs from the text, sentence-initial stopwords excluded, deduped, capped. */
	static List<String> extractNames(String text, int cap) {
		LinkedHashSet<String> names = new LinkedHashSet<>();
		if (text == null) return new ArrayList<>();
		Matcher m = CAPITALIZED_RUN.matcher(text);
		while (m.find() && names.size() < cap) {
			String run = m.group(1).replaceAll("\\s+", " ");
			String[] words = run.split(" ");
			/// Trim leading/trailing stopwords ("The Darby" -> "Darby"; "Darby When" -> "Darby").
			int s = 0, e = words.length;
			while (s < e && NAME_STOPWORDS.contains(words[s])) s++;
			while (e > s && NAME_STOPWORDS.contains(words[e - 1])) e--;
			if (s >= e) continue;
			String name = String.join(" ", Arrays.copyOfRange(words, s, e));
			if (name.length() < 3) continue;
			names.add(name);
		}
		return new ArrayList<>(names);
	}

	/**
	 * Build 2–{@code max} scenes over the text. The FIRST scene's title begins with the first non-blank
	 * line of the text (truncated to 60 chars) — the property the chapter-parity Playwright test keys on
	 * to prove the right chapter text reached the extraction prompt.
	 */
	static List<Map<String, Object>> buildScenes(String text, int max, boolean withIndex) {
		String t = text == null ? "" : text;
		int n = Math.max(2, Math.min(3, max));
		if (t.trim().length() < 200) n = 2;
		List<String> names = extractNames(t, 4);
		if (names.isEmpty()) names.add("Narrator");
		String firstLine = firstNonBlankLine(t, 60);
		if (firstLine.isEmpty()) firstLine = "Untitled passage";

		List<Map<String, Object>> scenes = new ArrayList<>();
		int len = t.length();
		for (int i = 0; i < n; i++) {
			int from = (int) ((long) len * i / n);
			int to = (int) ((long) len * (i + 1) / n);
			String slice = t.substring(Math.min(from, len), Math.min(to, len));
			String anchor = i == 0 ? firstLine : firstNonBlankLine(slice, 40);
			if (anchor.isEmpty()) anchor = "Passage " + (i + 1);
			String title = i == 0 ? firstLine : anchor + " (" + (i + 1) + ")";
			Map<String, Object> s = new LinkedHashMap<>();
			if (withIndex) s.put("index", i);
			s.put("title", title);
			s.put("blurb", "The moment around \"" + anchor + "\". "
				+ names.get(0) + (names.size() > 1 ? " and " + names.get(1) : "") + " are present as the passage turns.");
			s.put("setting", (i % 2 == 0) ? "an interior room with a single window, late afternoon" : "an open street under a grey sky");
			s.put("action", names.get(0) + (names.size() > 1 ? " speaks with " + names.get(1 % names.size()) : " pauses and looks up"));
			s.put("mood", (i % 2 == 0) ? "still, warm light, expectant" : "restless, cool light, uncertain");
			List<Map<String, Object>> chars = new ArrayList<>();
			for (int c = 0; c < Math.min(names.size(), 2 + (i % 2)); c++) {
				Map<String, Object> ch = new LinkedHashMap<>();
				ch.put("name", names.get(c));
				ch.put("role", c == 0 ? "focus of the scene" : "present");
				chars.add(ch);
			}
			s.put("characters", chars);
			s.put("diffusionPrompt", "masterpiece, best quality, " + names.get(0) + " in " + s.get("setting") + ", " + s.get("mood"));
			scenes.add(s);
		}
		return scenes;
	}

	private static String toJson(Object o) {
		try {
			return new ObjectMapper().writeValueAsString(o);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------------------------------------------
	// Fake HttpResponse<Stream<String>>
	// ------------------------------------------------------------------------------------------------

	/** OpenAI-compatible SSE stream carrying {@code content}, split into deltas, then finish + [DONE]. */
	static HttpResponse<Stream<String>> sseResponse(String content) {
		List<String> lines = new ArrayList<>();
		String c = content == null ? "" : content;
		int pos = 0;
		if (c.isEmpty()) {
			lines.add("data: " + chunkJson("", true));
		}
		boolean first = true;
		while (pos < c.length()) {
			int end = Math.min(c.length(), pos + SSE_CHUNK_CHARS);
			/// Don't split a surrogate pair.
			if (end < c.length() && Character.isHighSurrogate(c.charAt(end - 1))) end--;
			lines.add("data: " + chunkJson(c.substring(pos, end), first));
			first = false;
			pos = end;
		}
		lines.add("data: {\"id\":\"emu\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
		lines.add("data: [DONE]");
		return new EmulatedResponse(200, Map.of("content-type", List.of("text/event-stream")), lines.stream());
	}

	private static String chunkJson(String piece, boolean withRole) {
		return "{\"id\":\"emu\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{"
			+ (withRole ? "\"role\":\"assistant\"," : "")
			+ "\"content\":" + jsonString(piece) + "}}]}";
	}

	/** Non-200 body in the {@code {"error":{"message":...}}} shape {@code ChatUtil.extractLLMError} reads. */
	static HttpResponse<Stream<String>> errorResponse(int status, String message) {
		String body = "{\"error\":{\"message\":" + jsonString(message) + ",\"type\":\"emulated\"}}";
		return new EmulatedResponse(status, Map.of("content-type", List.of("application/json")), Stream.of(body));
	}

	static String jsonString(String s) {
		try {
			return new ObjectMapper().writeValueAsString(s == null ? "" : s);
		} catch (Exception e) {
			return "\"\"";
		}
	}

	/**
	 * Minimal {@link HttpResponse} the {@link Chat} consumer can use. Only {@code statusCode()},
	 * {@code headers()} and {@code body()} are meaningful; the rest return null/defaults.
	 */
	static final class EmulatedResponse implements HttpResponse<Stream<String>> {
		private final int status;
		private final HttpHeaders headers;
		private final Stream<String> body;

		EmulatedResponse(int status, Map<String, List<String>> headers, Stream<String> body) {
			this.status = status;
			this.headers = HttpHeaders.of(headers, (a, b) -> true);
			this.body = body;
		}

		@Override public int statusCode() { return status; }
		@Override public HttpRequest request() { return null; }
		@Override public Optional<HttpResponse<Stream<String>>> previousResponse() { return Optional.empty(); }
		@Override public HttpHeaders headers() { return headers; }
		@Override public Stream<String> body() { return body; }
		@Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
		@Override public URI uri() { return URI.create("emulator://local"); }
		@Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
	}

	/// Kept package-visible so a test can assert the SSE framing without going through Chat.
	static List<String> sseLines(String content) {
		List<String> out = new ArrayList<>();
		sseResponse(content).body().forEach(out::add);
		return out;
	}
}
