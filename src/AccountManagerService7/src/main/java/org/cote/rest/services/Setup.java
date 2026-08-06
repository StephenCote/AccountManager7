package org.cote.rest.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ServerConfigUtil;
import org.cote.accountmanager.util.SetupUtil;
import org.cote.rest.config.RestServiceEventListener;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/// First-run deployment setup — TRANSPORT ONLY.
///
/// ============================ WHY THERE IS NO @RolesAllowed HERE ============================
/// architecture.md requires @RolesAllowed on every new endpoint "except pre-auth WebAuthn". This
/// resource is the other pre-auth exception, by necessity: it EXISTS to create the very first
/// credential, so at the moment it must work there is no user, no role, and nothing to authorize
/// against. RolesAllowedDynamicFeature leaves unannotated resources open, web.xml constrains only
/// /Protected/*, and TokenFilter passes unauthenticated requests through — so these methods are
/// reachable anonymously and that is deliberate.
///
/// It is therefore gated by TWO independent mechanisms instead of PBAC:
///   1. A DB-resident latch (SetupUtil.isSetupComplete()) — marker-first OR, so an already
///      configured deployment stays closed even when the /data volume is lost (the "orphan state",
///      where the org keystores are gone but the database is intact and isInitialized() lies).
///      This is the real security boundary.
///   2. A one-shot filesystem token (X-AM7-Setup-Token vs $STORE_PATH/.setup.token, written by
///      docker/entrypoint.sh), constant-time compared. There is deliberately NO hard lockout —
///      see the bad-token fields below for why that would be a remote denial-of-provisioning.
///      This lives HERE, next to org.cote.jaas.TokenFilter, rather than in Objects7 — Objects7
///      must have no servlet or transport knowledge.
///
/// Latched and bad-token both return an IDENTICAL 404 so the endpoint is not an oracle for either
/// state. They are logged distinguishably server-side.
///
/// All business logic (organization creation, credentials, vaults, server config, initial user,
/// marker) lives in org.cote.accountmanager.util.SetupUtil.
@Path("/setup")
public class Setup {
	private static final Logger logger = LogManager.getLogger(Setup.class);

	public static final String SETUP_TOKEN_HEADER = "X-AM7-Setup-Token";
	public static final String SETUP_TOKEN_FILE = ".setup.token";
	/// Sentinel consumed by docker/entrypoint.sh so a configured deployment neither regenerates
	/// nor re-advertises a setup token on subsequent boots.
	public static final String SETUP_DONE_FILE = ".setup.done";
	public static final String SETUP_TOKEN_ENV = "AM7_SETUP_TOKEN";

	/// Bad-token handling: EXPONENTIAL BACKOFF FOR LOGGING ONLY. There is deliberately NO hard
	/// lockout.
	///
	/// A hard "N strikes and the endpoint is dead until restart" rule is a remote, unauthenticated
	/// denial-of-provisioning: anyone who can reach the port burns N bad tokens immediately after
	/// boot and the operator can never complete setup without downtime, repeatably. The token is
	/// 192 bits of entropy (openssl rand -hex 24) — brute force is not the threat model, so the
	/// counter's only real job is log-noise suppression.
	///
	/// Consequences, deliberately:
	///  - A VALID token is ALWAYS accepted immediately, whatever the failure count. There is no
	///    state a remote party can put this endpoint into that blocks the operator.
	///  - Repeated bad tokens escalate a backoff WINDOW that only governs log verbosity.
	///  - Rotating the token file (its content changing) resets the counter, so even the log
	///    suppression is recoverable without a restart.
	private static final int LOUD_FAILURE_LIMIT = 3;
	private static final long BACKOFF_BASE_MS = 1000L;
	private static final long BACKOFF_CAP_MS = 300000L;

	private static final AtomicInteger tokenFailures = new AtomicInteger(0);
	private static volatile long lastTokenFailureAt = 0L;
	/// SHA-256 of the currently observed expected token, used to detect operator rotation.
	private static volatile String observedTokenFingerprint = null;

	@Context
	private ServletContext servletContext = null;

	/// Legacy: GET /rest/setup/ returns a bare boolean for whether /System is initialized.
	/// Documented in aiDocs/DockerComposeDesign.md; kept working verbatim.
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Response checkSetup() {
		OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext("/System", null);
		return Response.status(200).entity((oc != null && oc.isInitialized())).build();
	}

	/// GET /rest/setup/state — the {"initialized":boolean} answer is open, because the UI has to be
	/// able to route an unauthenticated visitor to the setup page.
	///
	/// The `servers` block is NOT open. It carries the boot init-param values for the six media/AI
	/// servers (internal hostnames and service ports), which is free internal reconnaissance for an
	/// anonymous caller. It is therefore returned ONLY when a valid X-AM7-Setup-Token accompanies
	/// the request — the operator has the token, so prefill still works.
	///
	/// An absent/invalid token simply OMITS `servers`. It does not 404 (this is the routing probe
	/// and must stay answerable) and it does NOT count toward the bad-token counter, because this is
	/// a read that legitimately happens without a token on every unauthenticated page load.
	///
	/// This endpoint NEVER returns apiKeys, credentials, or the setup token itself.
	@GET
	@Path("/state")
	@Produces(MediaType.APPLICATION_JSON)
	public Response state(@HeaderParam(SETUP_TOKEN_HEADER) String setupToken) {
		boolean initialized = SetupUtil.isSetupComplete();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("initialized", initialized);
		if(!initialized && verifySetupToken(setupToken)) {
			Map<String, String> servers = new LinkedHashMap<>();
			for(String name : ServerConfigUtil.SERVER_NAMES) {
				String param = ServerConfigUtil.getInitParameterName(name);
				String value = (servletContext != null && param != null ? servletContext.getInitParameter(param) : null);
				servers.put(name, value);
			}
			out.put("servers", servers);
		}
		return Response.status(200).entity(JSONUtil.exportObject(out)).build();
	}

	/// POST /rest/setup/
	///
	/// Wire contract (all fields except `credential` optional):
	/// {
	///   "credential": "<base64 admin password>",
	///   "initialUser": { "name": "...", "credential": "<base64>", "organization": "/Public" },
	///   "servers": { "sd": "...", "face": "...", "tag": "...",
	///                "voice.tts": "...", "voice.stt": "...", "embedding": "..." }
	/// }
	/// Omitted `servers` keys are left unchanged. The LEGACY body — a record-shaped
	/// {"schema":"auth.credential","credential":"<base64>","type":"hashed_password"} with nothing
	/// else — still bootstraps, because only `credential` is read from it.
	///
	/// 200 -> {"ok":true,"initialUser":"name"|null,"warnings":[...]}
	/// 404 -> latched / bad token / locked out (indistinguishable by design)
	/// 400 -> malformed body
	@POST
	@Path("/")
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response setup(String json, @HeaderParam(SETUP_TOKEN_HEADER) String setupToken,
			@Context HttpServletRequest request, @Context HttpServletResponse response) {

		/// --- Gate 1: DB latch. Identical 404, logged distinguishably. ---
		if(SetupUtil.isSetupComplete()) {
			logger.warn("Setup rejected: setup is already complete (latched)");
			return notFound();
		}

		/// --- Gate 2: token. Identical 404, logged distinguishably. ---
		if(!verifySetupToken(setupToken)) {
			noteTokenFailure();
			return notFound();
		}

		if(json == null || json.trim().length() == 0) {
			return Response.status(400).entity("{\"ok\":false,\"error\":\"Empty request body\"}").build();
		}

		/// Parsed as a plain map, NOT via RecordDeserializer: the current body has no `schema`
		/// field (the deserializer requires one), while the legacy record-shaped body parses fine
		/// as a map and still yields `credential`.
		@SuppressWarnings("unchecked")
		Map<String, Object> body = JSONUtil.importObject(json, LinkedHashMap.class);
		if(body == null) {
			return Response.status(400).entity("{\"ok\":false,\"error\":\"Invalid JSON\"}").build();
		}

		SetupUtil.SetupRequest req = new SetupUtil.SetupRequest();
		String adminPassword = decodeCredential(body.get("credential"));
		if(adminPassword == null) {
			/// Never log the value, only that it was unusable.
			return Response.status(400).entity("{\"ok\":false,\"error\":\"Missing or invalid base64 credential\"}").build();
		}
		/// Reject empty/whitespace-only and too-short passwords BEFORE anything reaches
		/// CredentialFactory, which only checks `pwd != null` and would happily persist a real
		/// HASHED_PASSWORD over the empty string — setting the admin password to empty on all three
		/// organizations AND permanently latching setup closed. Fail loudly with a 400.
		String adminPwError = SetupUtil.validatePassword(adminPassword);
		if(adminPwError != null) {
			logger.error("Setup rejected: invalid administrator password (" + adminPwError + ")");
			return Response.status(400).entity(JSONUtil.exportObject(error(adminPwError))).build();
		}
		req.setAdminPassword(adminPassword);

		Object iu = body.get("initialUser");
		if(iu instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> iuMap = (Map<String, Object>) iu;
			Object name = iuMap.get("name");
			if(name != null && name.toString().trim().length() > 0) {
				String userName = name.toString().trim();
				String nameError = SetupUtil.validateUserName(userName);
				if(nameError != null) {
					logger.error("Setup rejected: invalid initial user name (" + nameError + ")");
					return Response.status(400).entity(JSONUtil.exportObject(error(nameError))).build();
				}
				req.setInitialUserName(userName);
				String userPassword = decodeCredential(iuMap.get("credential"));
				if(userPassword == null) {
					return Response.status(400).entity("{\"ok\":false,\"error\":\"Missing or invalid base64 initialUser credential\"}").build();
				}
				String userPwError = SetupUtil.validatePassword(userPassword);
				if(userPwError != null) {
					logger.error("Setup rejected: invalid initial user password (" + userPwError + ")");
					return Response.status(400).entity(JSONUtil.exportObject(error(userPwError))).build();
				}
				req.setInitialUserPassword(userPassword);
				Object org = iuMap.get("organization");
				if(org != null && org.toString().trim().length() > 0) {
					req.setInitialUserOrganization(org.toString().trim());
				}
				/// HARD REJECT /System (and anything else off the allow-list) at the edge as well as
				/// in SetupUtil: a user in /System inherits AccountUsers CRU on every shared library,
				/// which includes the /Library/Connections records holding the global API keys.
				String orgError = SetupUtil.validateInitialUserOrganization(req.getInitialUserOrganization());
				if(orgError != null) {
					logger.error("Setup rejected: invalid initial user organization '"
						+ req.getInitialUserOrganization() + "' (" + orgError + ")");
					return Response.status(400).entity(JSONUtil.exportObject(error(orgError))).build();
				}
			}
		}

		Object srv = body.get("servers");
		if(srv instanceof Map) {
			@SuppressWarnings("unchecked")
			Map<String, Object> srvMap = (Map<String, Object>) srv;
			for(String name : ServerConfigUtil.SERVER_NAMES) {
				Object v = srvMap.get(name);
				if(v != null && v.toString().trim().length() > 0) {
					req.getServers().put(name, v.toString().trim());
				}
			}
		}

		SetupUtil.SetupResult result;
		try {
			result = SetupUtil.runSetup(req);
		}
		catch(Exception e) {
			/// A latch race (another request completed setup first) also lands here.
			logger.error("Setup failed: " + e.getMessage());
			if(SetupUtil.isSetupComplete()) {
				return notFound();
			}
			return Response.status(500).entity("{\"ok\":false,\"error\":\"Setup failed\"}").build();
		}

		/// Post-setup provisioning (vault + ISO 42001 roles) uses the SAME method the boot listener
		/// uses, so the freshly created organizations are usable without a restart. The ISO call
		/// stays in Service7 — ISO must never be reachable from Objects7.
		try {
			RestServiceEventListener.provisionDefaultOrganizations();
		}
		catch(Exception e) {
			logger.error("Post-setup organization provisioning failed", e);
			result.getWarnings().add("Post-setup organization provisioning failed: " + e.getMessage());
		}

		/// Retire the one-shot token and drop the sentinel entrypoint.sh looks for.
		retireSetupToken(result);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", true);
		out.put("initialUser", result.getInitialUser());
		out.put("warnings", result.getWarnings());
		return Response.status(200).entity(JSONUtil.exportObject(out)).build();
	}

	/// Identical response for latched and bad-token — no oracle.
	private static Response notFound() {
		return Response.status(404).entity("{\"ok\":false}").build();
	}

	private static Map<String, Object> error(String message) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("ok", false);
		out.put("error", message);
		return out;
	}

	/// Exponential backoff over the bad-token counter. Governs LOG VERBOSITY only — see the field
	/// comments. Never blocks a valid token, never disables the endpoint.
	private static long backoffMs(int failures) {
		if(failures <= LOUD_FAILURE_LIMIT) {
			return 0L;
		}
		long ms = BACKOFF_BASE_MS;
		for(int i = LOUD_FAILURE_LIMIT; i < failures && ms < BACKOFF_CAP_MS; i++) {
			ms = ms * 2;
		}
		return Math.min(ms, BACKOFF_CAP_MS);
	}

	private static void noteTokenFailure() {
		int failures = tokenFailures.incrementAndGet();
		long window = backoffMs(failures);
		long now = System.currentTimeMillis();
		boolean suppress = (window > 0L && (now - lastTokenFailureAt) < window);
		lastTokenFailureAt = now;
		if(failures <= LOUD_FAILURE_LIMIT) {
			logger.error("Setup rejected: BAD SETUP TOKEN (attempt " + failures + ")");
		}
		else if(!suppress) {
			logger.error("Setup rejected: BAD SETUP TOKEN (attempt " + failures + "). Further bad-token"
				+ " attempts inside a " + window + "ms window are logged at DEBUG. A VALID token is"
				+ " still accepted immediately — the endpoint is not locked. Rotating "
				+ SETUP_TOKEN_FILE + " resets this counter.");
		}
		else {
			logger.debug("Setup rejected: BAD SETUP TOKEN (attempt " + failures + ", suppressed)");
		}
	}

	/// Reset the bad-token counter when the operator rotates the token file, so even the log
	/// suppression is recoverable without a container restart.
	private static void noteObservedToken(String expected) {
		String fingerprint = sha256Hex(expected);
		if(fingerprint == null) {
			return;
		}
		if(!fingerprint.equals(observedTokenFingerprint)) {
			if(observedTokenFingerprint != null) {
				logger.info("Setup token rotated; resetting the bad-token counter");
			}
			observedTokenFingerprint = fingerprint;
			tokenFailures.set(0);
			lastTokenFailureAt = 0L;
		}
	}

	private static String sha256Hex(String value) {
		if(value == null) {
			return null;
		}
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder();
			for(byte b : d) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		}
		catch(NoSuchAlgorithmException e) {
			return null;
		}
	}

	/// base64 -> UTF-8 string. Returns null when absent or not valid base64.
	private static String decodeCredential(Object value) {
		if(value == null) {
			return null;
		}
		String s = value.toString().trim();
		if(s.length() == 0) {
			return null;
		}
		try {
			byte[] raw = Base64.getDecoder().decode(s);
			if(raw.length == 0) {
				return null;
			}
			return new String(raw, StandardCharsets.UTF_8);
		}
		catch(IllegalArgumentException e) {
			logger.error("Setup credential was not valid base64");
			return null;
		}
	}

	/// Constant-time token comparison.
	///
	/// Both sides are SHA-256 digested before MessageDigest.isEqual so the comparison is
	/// independent of length as well as content — a raw isEqual on the plaintext leaks the
	/// expected length through an early length check.
	private boolean verifySetupToken(String presented) {
		/// NO TOKEN PRESENTED — return quietly, BEFORE touching the token file or logging anything.
		///
		/// GET /state calls this on every unauthenticated page load (the router probes it to decide
		/// between the setup page and sign-in), and the absence of a header on a READ is not an
		/// error. Checking the file first logged
		/// "Setup rejected: no setup token is configured ..." at ERROR on every one of those probes.
		/// A genuinely misconfigured token file is still reported at ERROR below, on a request that
		/// actually presented a token — i.e. on a mutation attempt.
		String given = (presented != null ? presented.trim() : "");
		if(given.length() == 0) {
			return false;
		}
		String expected = readExpectedToken();
		if(expected == null || expected.length() == 0) {
			logger.error("Setup rejected: a token was presented but no setup token is configured ("
				+ SETUP_TOKEN_FILE + " is missing and " + SETUP_TOKEN_ENV + " is unset)");
			return false;
		}
		noteObservedToken(expected);
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] a = md.digest(expected.getBytes(StandardCharsets.UTF_8));
			md.reset();
			byte[] b = md.digest(given.getBytes(StandardCharsets.UTF_8));
			return MessageDigest.isEqual(a, b);
		}
		catch(NoSuchAlgorithmException e) {
			logger.error("SHA-256 is unavailable; cannot verify the setup token");
			return false;
		}
	}

	/// Two INDEPENDENT sources, environment first:
	///   1. the AM7_SETUP_TOKEN environment VALUE, when set;
	///   2. otherwise the token FILE under store.path.
	///
	/// These are not two views of one thing. docker/entrypoint.sh only ever populates the FILE — it
	/// reads AM7_SETUP_TOKEN_FILE (a PATH) and never reads AM7_SETUP_TOKEN (a VALUE) — so under
	/// Docker the file is the whole story. The environment override is retained because it is the
	/// documented bootstrap path for BARE-TOMCAT operators, who have no entrypoint script and
	/// therefore no other way to obtain a token: nothing outside entrypoint.sh creates that file.
	///
	/// The file contents are TRIMMED: the token is written with a trailing newline
	/// (`openssl rand -hex 24 > file`), so an untrimmed compare would fail every single time.
	private String readExpectedToken() {
		String env = System.getenv(SETUP_TOKEN_ENV);
		if(env != null && env.trim().length() > 0) {
			return env.trim();
		}
		java.nio.file.Path p = setupFilePath(SETUP_TOKEN_FILE);
		if(p == null) {
			return null;
		}
		try {
			if(!Files.exists(p)) {
				return null;
			}
			return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
		}
		catch(IOException e) {
			logger.error("Failed to read " + SETUP_TOKEN_FILE + ": " + e.getMessage());
			return null;
		}
	}

	/// Both files live directly under store.path. IOFactory.DEFAULT_FILE_BASE is assigned from the
	/// store.path init-param at boot (RestServiceEventListener); the init-param is the fallback for
	/// contexts where it has not been assigned yet.
	private java.nio.file.Path setupFilePath(String fileName) {
		String base = IOFactory.DEFAULT_FILE_BASE;
		if((base == null || base.trim().length() == 0) && servletContext != null) {
			base = servletContext.getInitParameter("store.path");
		}
		if(base == null || base.trim().length() == 0) {
			logger.error("Cannot resolve the store path; " + fileName + " is unreachable");
			return null;
		}
		return Paths.get(base.trim(), fileName);
	}

	/// On successful completion: delete .setup.token (it is one-shot) and create .setup.done
	/// (the sentinel docker/entrypoint.sh checks so it stops minting and advertising tokens on
	/// later boots of an already-configured store).
	private void retireSetupToken(SetupUtil.SetupResult result) {
		java.nio.file.Path token = setupFilePath(SETUP_TOKEN_FILE);
		if(token != null) {
			try {
				if(Files.deleteIfExists(token)) {
					logger.info("Removed the one-shot setup token");
				}
			}
			catch(IOException e) {
				logger.error("Failed to remove " + SETUP_TOKEN_FILE + ": " + e.getMessage());
				result.getWarnings().add("Failed to remove the one-shot setup token; delete " + SETUP_TOKEN_FILE + " manually");
			}
		}
		java.nio.file.Path done = setupFilePath(SETUP_DONE_FILE);
		if(done != null) {
			try {
				Files.write(done, (ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n")
					.getBytes(StandardCharsets.UTF_8));
				logger.info("Wrote the setup completion sentinel " + SETUP_DONE_FILE);
			}
			catch(IOException e) {
				logger.error("Failed to write " + SETUP_DONE_FILE + ": " + e.getMessage());
				result.getWarnings().add("Failed to write " + SETUP_DONE_FILE + "; the container will re-advertise a setup token on the next boot");
			}
		}
	}
}
