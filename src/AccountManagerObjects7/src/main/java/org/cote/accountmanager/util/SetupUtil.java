package org.cote.accountmanager.util;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.SystemException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.CredentialEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.security.CredentialUtil;

/// First-run deployment setup: initialize the default organizations, set the admin credential,
/// write the deployment media/AI server configuration, and create an initial (non-admin) user.
///
/// This is the SINGLE implementation of the setup latch and the setup sequence. Both the REST
/// endpoint (Service7 org.cote.rest.services.Setup) and the CLI (Console7 AdminAction) delegate
/// here — there is deliberately no second copy of the organization loop.
///
/// Contains NO servlet knowledge, NO token handling (that gate lives in Service7 next to
/// org.cote.jaas.TokenFilter) and NO ISO 42001 knowledge (ISO must never be reachable from
/// Objects7; the ISO role provisioning step stays in Service7).
public class SetupUtil {
	public static final Logger logger = LogManager.getLogger(SetupUtil.class);

	/// data.data marker record written into /System's /Library shared group on completion.
	public static final String SETUP_MARKER_NAME = ".setupState";
	public static final String SETUP_MARKER_VERSION = "1";

	public static final int MIN_PASSWORD_LENGTH = 8;

	/// User name allow-list, 5-64 characters.
	///
	/// THE FIRST FIVE CHARACTERS MUST BE ALPHANUMERIC. That is not arbitrary: system.user declares
	/// the `$minLen5` validation rule on `name` (userModel.json), and despite the name that rule is
	/// NOT a length check — its expression is the regex [A-Za-z0-9]{5} evaluated with
	/// Matcher.find() (validationRules/minLen5Rule.json, ValidationUtil.java:159-197), i.e. it
	/// demands FIVE CONSECUTIVE ALPHANUMERICS somewhere in the value. A separator inside the first
	/// five characters is the common way to violate it.
	///
	/// This pattern is deliberately stricter than the raw rule (it requires the run to be at the
	/// START rather than anywhere) so that the caller gets a precise, actionable message from THIS
	/// validator instead of the opaque and misleading "$minLen5" failure. The constraint is
	/// platform-wide and independently enforced: Factory.getCreateUser runs the same rule and
	/// returns null for a name that violates it, so relaxing this would only move the failure later
	/// and produce a null user.
	///
	/// The rest of the allow-list keeps path-, URN- and shell-hostile characters out of a name that
	/// becomes /home/<name> groups, roles and permissions in Factory.setupUser. \A and \z (not ^/$)
	/// because Java's $ also matches before a trailing line terminator.
	public static final Pattern USER_NAME_PATTERN = Pattern.compile("\\A[A-Za-z0-9]{5}[A-Za-z0-9._-]{0,59}\\z");

	/// The initial user may ONLY be created in these organizations.
	///
	/// /System is HARD-REJECTED. Factory.setupUser (Factory.java:186-219) grants every new user
	/// ROLE_ACCOUNT_USERS and ROLE_REQUESTERS; LibraryUtil.configureDirectoryPermissions
	/// (LibraryUtil.java:100-108) grants AccountUsers Create/Read/Update on every shared library
	/// created with getCreateSharedLibrary(..., true) — which is how ChatLibraryUtil creates
	/// /Library/Connections, /Library/PromptTemplates, /Library/ChatConfigs and
	/// /Library/PromptConfigs (ChatLibraryUtil.java:46-64). A user in /System would therefore be
	/// able to read every global connection record this feature stores (including apiKeys) and to
	/// update the /System prompt templates. That is a full compromise in one request.
	public static final String[] ALLOWED_INITIAL_USER_ORGANIZATIONS = new String[] {
		OrganizationContext.DEVELOPMENT_ORGANIZATION, OrganizationContext.PUBLIC_ORGANIZATION
	};

	/// Users created by organization provisioning. Their existence does NOT mean an operator has
	/// created a real user, so the "create initial user" step ignores them, and none of their names
	/// may be reused.
	public static final String[] SYSTEM_USER_NAMES = new String[] {
		Factory.ADMIN_USER_NAME, Factory.OPS_USER_NAME, Factory.VAULT_USER_NAME,
		Factory.DOCUMENT_CONTROL_USER_NAME, Factory.API_USER_NAME
	};

	/// The only role memberships Factory.setupUser assigns to a newly created user.
	public static final String[] EXPECTED_DEFAULT_ROLES = new String[] {
		AccessSchema.ROLE_ACCOUNT_USERS, AccessSchema.ROLE_REQUESTERS
	};

	/// Process-level latch. Caches ONLY the true (closed) result, and is never reset within a JVM
	/// lifetime — an open result is never cached, because caching "open" would let a transient
	/// failure to read state hold the setup endpoint open for the life of the process.
	private static volatile boolean setupCompleteLatch = false;

	private SetupUtil() {
	}

	/// THE LATCH.
	///
	///     isSetupComplete() = markerExists() || adminCredentialExists()
	///
	/// Both terms are DB-resident, and that is the whole point.
	///
	/// There is deliberately NO isInitialized()/organization-initialized term. Such a term is not
	/// merely redundant, it is actively WRONG in both directions:
	///  - It fails CLOSED on a virgin database: IOSystem.open() itself loops
	///    OrganizationContext.DEFAULT_ORGANIZATIONS and calls createOrganization() for any
	///    uninitialized organization (IOSystem.java:182-193), and Factory.makeOrganization creates
	///    the admin user WITHOUT any password credential (Factory.java:113-151, :129). So by the
	///    time the first HTTP request can arrive on a brand-new deployment, all three organizations
	///    report isInitialized() == true with no admin credential anywhere — the latch would close
	///    immediately and strand the operator with no password and no way to set one.
	///  - It fails OPEN in the orphan state: if the /data volume is lost while the database
	///    survives, initializeStores() fails (OrganizationContext.java:103,120-137) and
	///    isInitialized() reports false against a fully populated database.
	///
	/// The orphan state is still covered without it: the auth.credential row lives in Postgres, so
	/// adminCredentialExists() is true and the latch stays closed.
	///
	/// Marker first because it is the cheapest and most direct statement of "an operator completed
	/// setup here"; the credential check is the fallback covering deployments configured before the
	/// marker existed.
	///
	/// FAILS CLOSED: any error evaluating either term returns true (complete). An exception must
	/// never open an unauthenticated provisioning endpoint.
	public static boolean isSetupComplete() {
		if(setupCompleteLatch) {
			return true;
		}
		boolean complete;
		try {
			complete = markerExists() || adminCredentialExists();
		}
		catch(Throwable t) {
			/// Throwable on purpose: an Error here must not open the latch either.
			logger.error("Failed to evaluate the setup latch; failing CLOSED (treating setup as complete)", t);
			complete = true;
		}
		if(complete) {
			setupCompleteLatch = true;
		}
		return complete;
	}

	public static void assertSetupIncomplete() throws SystemException {
		if(isSetupComplete()) {
			throw new SystemException("Setup has already been completed");
		}
	}

	/// True when the .setupState marker record exists in /System's /Library group.
	/// Fails CLOSED (returns true) if the state cannot be read.
	public static boolean markerExists() {
		try {
			return findMarker() != null;
		}
		catch(Exception e) {
			logger.error("Failed to read the setup marker; failing CLOSED", e);
			return true;
		}
	}

	/// True when ANY default organization's admin user already holds a real credential.
	///
	/// "Any", not "all", and not "/System only": setup writes credentials in loop order /System,
	/// /Development, /Public, so a mid-loop failure leaves a partial state — and "any" is the
	/// fail-closed reading of a partial state.
	///
	/// Fails CLOSED (returns true) if the state cannot be read.
	public static boolean adminCredentialExists() {
		for(String org : OrganizationContext.DEFAULT_ORGANIZATIONS) {
			try {
				OrganizationContext octx = organizationContext(org);
				if(octx == null) {
					continue;
				}
				/// getAdminUser() is populated (OrganizationContext.java:92) BEFORE
				/// initializeStores() runs, so this still answers correctly in the orphan state.
				BaseRecord admin = octx.getAdminUser();
				if(admin == null) {
					continue;
				}
				if(isRealCredential(CredentialUtil.getLatestCredential(admin))) {
					return true;
				}
			}
			catch(Exception e) {
				logger.error("Failed to check the admin credential for " + org + "; failing CLOSED", e);
				return true;
			}
		}
		return false;
	}

	/// A credential row only counts as a real credential when it carries a usable type.
	///
	/// CredentialFactory sets CredentialEnumType.UNKNOWN by default and only upgrades it to
	/// HASHED_PASSWORD when both a password and a matching type parameter were supplied
	/// (CredentialFactory.java:48-61) — it throws nothing when they were not. Such a row has a null
	/// credential value and cannot authenticate anyone, so it must NOT satisfy the latch.
	/// It is still never overwritten; see runSetup step 2.
	public static boolean isRealCredential(BaseRecord cred) {
		if(cred == null) {
			return false;
		}
		try {
			CredentialEnumType cet = cred.getEnum(FieldNames.FIELD_TYPE);
			return cet != null && cet != CredentialEnumType.UNKNOWN;
		}
		catch(Exception e) {
			logger.warn("Could not read the credential type; treating it as a real credential: " + e.getMessage());
			return true;
		}
	}

	/// Always pass the derived organization type.
	///
	/// IOContext caches OrganizationContext instances by path INCLUDING failed ones
	/// (IOContext.java:193-201), and createOrganization() re-initialize()s that cached instance in
	/// place (OrganizationContext.java:203-217) — which is exactly why setup takes effect without a
	/// restart. Passing null here would cache an instance with a null organizationType, and
	/// createOrganization() would then throw "Invalid organization path or type" forever.
	/// Never construct a fresh OrganizationContext.
	private static OrganizationContext organizationContext(String orgPath) {
		IOContext ctx = IOSystem.getActiveContext();
		if(ctx == null) {
			return null;
		}
		OrganizationEnumType otype = OrganizationEnumType.valueOf(orgPath.substring(1).toUpperCase());
		return ctx.getOrganizationContext(orgPath, otype);
	}

	public static OrganizationContext systemOrganizationContext() {
		return organizationContext(OrganizationContext.SYSTEM_ORGANIZATION);
	}

	/// Locate the marker. Throws rather than swallowing so callers can fail closed.
	private static BaseRecord findMarker() {
		IOContext ctx = IOSystem.getActiveContext();
		if(ctx == null) {
			return null;
		}
		OrganizationContext octx = systemOrganizationContext();
		if(octx == null || octx.getOrganizationId() <= 0L || octx.getAdminUser() == null) {
			return null;
		}
		long orgId = octx.getOrganizationId();
		BaseRecord dir = ctx.getPathUtil().findPath(octx.getAdminUser(), ModelNames.MODEL_GROUP,
			LibraryUtil.basePath, GroupEnumType.DATA.toString(), orgId);
		if(dir == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, SETUP_MARKER_NAME);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		return ctx.getSearch().findRecord(q);
	}

	/// ---------------------------------------------------------------------------------------
	/// Validation (shared by the REST and CLI callers so both reject the same inputs)
	/// ---------------------------------------------------------------------------------------

	/// Returns null when the password is acceptable, else a caller-safe message.
	///
	/// Rejecting empty/whitespace-only is a HARD requirement, not hygiene: CredentialFactory only
	/// checks `pwd != null` (CredentialFactory.java:54), so "" produces a real HASHED_PASSWORD row
	/// over the empty string. That would both set the administrator password to empty on every
	/// organization AND make the credential row exist, permanently latching setup closed — a
	/// drive-by request achieving permanent denial-of-provisioning.
	public static String validatePassword(String password) {
		String present = validatePasswordPresent(password);
		if(present != null) {
			return present;
		}
		if(password.length() < MIN_PASSWORD_LENGTH) {
			return "A password must be at least " + MIN_PASSWORD_LENGTH + " characters";
		}
		return null;
	}

	/// PRESENCE ONLY — no length requirement. Returns null when the password is non-null and not
	/// blank, else a caller-safe message.
	///
	/// This is the weaker check used by the DAY-2 Console7 -addUser path, whose pre-existing
	/// contract had no minimum length: enforcing MIN_PASSWORD_LENGTH there would break existing
	/// provisioning scripts. It still closes the hole that matters, because CredentialFactory only
	/// checks `pwd != null` (CredentialFactory.java:54) and would otherwise persist a real
	/// HASHED_PASSWORD over the empty string.
	///
	/// First-run setup paths use validatePassword() instead, which adds the length floor.
	public static String validatePasswordPresent(String password) {
		if(password == null || password.trim().length() == 0) {
			return "A password is required and cannot be empty or whitespace";
		}
		return null;
	}

	/// Returns null when the organization is permitted for the initial user, else a message.
	public static String validateInitialUserOrganization(String orgPath) {
		String p = (orgPath != null ? orgPath.trim() : "");
		for(String allowed : ALLOWED_INITIAL_USER_ORGANIZATIONS) {
			if(allowed.equals(p)) {
				return null;
			}
		}
		return "The initial user may only be created in "
			+ String.join(" or ", ALLOWED_INITIAL_USER_ORGANIZATIONS)
			+ "; " + OrganizationContext.SYSTEM_ORGANIZATION + " is not permitted";
	}

	/// Returns null when the user name is acceptable, else a message.
	/// Applies the character allow-list, the reserved-name list, and the schema validation rules
	/// ($notEmpty, $minLen5) via ValidationUtil against the system.user `name` field.
	public static String validateUserName(String name) {
		String n = (name != null ? name.trim() : "");
		if(n.length() == 0) {
			return "A user name is required";
		}
		if(!USER_NAME_PATTERN.matcher(n).matches()) {
			/// State the REAL constraint. The previous message advertised "letters, digits, '.', '_'
			/// or '-'" without qualification, which was a promise the platform does not keep: the
			/// system.user $minLen5 rule requires five consecutive alphanumerics, so a separator
			/// cannot fall inside the first five characters. 'jane.doe' is the common casualty.
			return "A user name must be 5-64 characters long, must START with five letters or digits"
				+ " (the platform's system.user rule requires five consecutive alphanumerics, so a"
				+ " '.', '_' or '-' cannot appear in the first five characters), and after that may"
				+ " contain letters, digits, '.', '_' or '-'. For example 'jane.doe' is NOT valid but"
				+ " 'janed.oe' or 'janedoe' is.";
		}
		for(String reserved : SYSTEM_USER_NAMES) {
			if(reserved.equalsIgnoreCase(n)) {
				return "'" + n + "' is a reserved system user name";
			}
		}
		try {
			BaseRecord probe = RecordFactory.newInstance(ModelNames.MODEL_USER);
			probe.set(FieldNames.FIELD_NAME, n);
			FieldType nameField = probe.getField(FieldNames.FIELD_NAME);
			/// Targeted at the name field only (rather than RecordValidator.validate on a synthetic
			/// whole record) so an unrelated default field value cannot make user creation
			/// impossible. These are the rules system.user actually declares for `name`.
			for(String ruleName : new String[] { "$notEmpty", "$minLen5" }) {
				BaseRecord rule = ValidationUtil.getRule(ruleName);
				if(rule != null && !ValidationUtil.validateFieldWithRule(probe, nameField, rule)) {
					return "The user name failed the " + ruleName + " validation rule";
				}
			}
		}
		catch(Exception e) {
			logger.error("Failed to validate the user name: " + e.getMessage());
			return "The user name could not be validated";
		}
		return null;
	}

	/// ---------------------------------------------------------------------------------------

	/// What the caller asks for. Every field is optional except the admin password on a genuine
	/// first run; each runSetup step keeps its own idempotence guard.
	public static class SetupRequest {
		private String adminPassword = null;
		private Map<String, String> servers = new LinkedHashMap<>();
		private String initialUserName = null;
		private String initialUserPassword = null;
		private String initialUserOrganization = OrganizationContext.PUBLIC_ORGANIZATION;

		public String getAdminPassword() {
			return adminPassword;
		}
		public void setAdminPassword(String adminPassword) {
			this.adminPassword = adminPassword;
		}
		public Map<String, String> getServers() {
			return servers;
		}
		public String getInitialUserName() {
			return initialUserName;
		}
		public void setInitialUserName(String initialUserName) {
			this.initialUserName = initialUserName;
		}
		public String getInitialUserPassword() {
			return initialUserPassword;
		}
		public void setInitialUserPassword(String initialUserPassword) {
			this.initialUserPassword = initialUserPassword;
		}
		public String getInitialUserOrganization() {
			return initialUserOrganization;
		}
		public void setInitialUserOrganization(String initialUserOrganization) {
			this.initialUserOrganization = initialUserOrganization;
		}
	}

	public static class SetupResult {
		private boolean ok = false;
		private String initialUser = null;
		private List<String> warnings = new ArrayList<>();
		/// True when a credential had to be created for at least one organization and could not be
		/// (bad or rejected password). Distinguishes "nothing needed doing" from "something needed
		/// doing and failed", so a repair re-run on a healthy deployment still reports success.
		private boolean credentialRequiredButNotSet = false;

		public boolean isCredentialRequiredButNotSet() {
			return credentialRequiredButNotSet;
		}
		public void setCredentialRequiredButNotSet(boolean credentialRequiredButNotSet) {
			this.credentialRequiredButNotSet = credentialRequiredButNotSet;
		}
		public boolean isOk() {
			return ok;
		}
		public void setOk(boolean ok) {
			this.ok = ok;
		}
		public String getInitialUser() {
			return initialUser;
		}
		public void setInitialUser(String initialUser) {
			this.initialUser = initialUser;
		}
		public List<String> getWarnings() {
			return warnings;
		}
	}

	/// Steps 1-3 of the setup sequence, shared by runSetup() and repairProvisioning():
	///   1. create each default organization when it is not initialized
	///   2. set the admin credential wherever NO credential row exists (never overwrite)
	///   3. initialize each organization vault
	///
	/// Every step keeps its own idempotence guard, so this is safely re-runnable. That property is
	/// the whole point of repairProvisioning(): this is the loop the pre-existing Console7
	/// -setup used, and operators relied on re-running it to repair partial provisioning.
	///
	/// adminPassword may be null: organizations are still created and vaults still initialized, and
	/// a missing credential is reported as a warning rather than being invented.
	private static void provisionDefaultOrganizations(String adminPassword, SetupResult res) {
		IOContext ctx = IOSystem.getActiveContext();
		for(String org : OrganizationContext.DEFAULT_ORGANIZATIONS) {
			OrganizationEnumType otype = OrganizationEnumType.valueOf(org.substring(1).toUpperCase());
			logger.info("Configuring " + otype + " " + org);
			OrganizationContext octx = ctx.getOrganizationContext(org, otype);
			if(octx == null) {
				res.getWarnings().add("Failed to obtain an organization context for " + org);
				continue;
			}

			/// Step 1 — idempotence guard: only create when not initialized.
			try {
				if(!octx.isInitialized()) {
					octx.createOrganization();
				}
			}
			catch(Exception e) {
				res.getWarnings().add("Failed to create organization " + org + ": " + e.getMessage());
				continue;
			}

			/// Step 2 — idempotence guard: only set a credential where NO row exists. Never
			/// overwrite, not even an unusable UNKNOWN-type row.
			BaseRecord admin = octx.getAdminUser();
			if(admin == null) {
				res.getWarnings().add("No administration user for " + org);
				continue;
			}
			BaseRecord existingCred = CredentialUtil.getLatestCredential(admin);
			if(existingCred == null) {
				if(adminPassword == null) {
					res.getWarnings().add("No administrative credential set for " + org + " (no password supplied)");
				}
				else {
					/// Validate LAZILY, only where a credential actually has to be created. An
					/// already-provisioned deployment never needs the password, so a re-run must not
					/// fail on it — that is what keeps -setup a clean no-op.
					String pwErr = validatePassword(adminPassword);
					if(pwErr != null) {
						logger.error("Cannot set the administrative credential for " + org + ": " + pwErr);
						res.getWarnings().add("Cannot set the administrative credential for " + org + ": " + pwErr);
						res.setCredentialRequiredButNotSet(true);
					}
					else if(!createBootstrapCredential(admin, adminPassword)) {
						res.getWarnings().add("Failed to set the administrative credential for " + org);
						res.setCredentialRequiredButNotSet(true);
					}
				}
			}
			else if(!isRealCredential(existingCred)) {
				logger.error("**** A credential row already exists for the " + org
					+ " administrator but its type is UNKNOWN, so it cannot authenticate anyone."
					+ " It is NOT being overwritten. This requires operator intervention: remove the"
					+ " unusable auth.credential row for the " + org + " admin user, then re-run setup.");
				res.getWarnings().add("The " + org + " administrator has an unusable (UNKNOWN type) credential row."
					+ " It was NOT overwritten — operator intervention is required.");
			}
			else {
				logger.warn("Administrative credential already set for " + org);
			}

			/// Step 3 — the vault must exist before any encrypted apiKey is written.
			try {
				if(octx.getVault() == null) {
					res.getWarnings().add("Failed to initialize the vault for " + org);
				}
			}
			catch(Exception e) {
				res.getWarnings().add("Failed to initialize the vault for " + org + ": " + e.getMessage());
			}
		}
	}

	/// IDEMPOTENT PROVISIONING REPAIR — the Console7 -setup entry point.
	///
	/// DELIBERATELY NOT LATCH-GATED, unlike runSetup(). The latch exists to stop an UNAUTHENTICATED
	/// REMOTE caller from re-running provisioning against a configured deployment; a CLI operator
	/// already holds the database credentials and filesystem access that the latch is protecting,
	/// which is the same reasoning by which the CLI needs no setup token.
	///
	/// This restores the pre-existing Console7 behavior: the old -setup looped the default
	/// organizations, created any that were missing and set any absent admin credential, and was
	/// safely re-runnable. It was THE tool for repairing partial provisioning, and running it on an
	/// already-configured deployment was a clean no-op. Routing -setup through the latch-gated
	/// runSetup() removed the repair capability and made a previously-valid invocation emit an
	/// ERROR, which breaks provisioning scripts and log-scraping CI.
	///
	/// It still CANNOT do the damaging things:
	///   - it never overwrites an existing credential (step 2's guard),
	///   - it never writes the marker unless an administrator credential actually exists,
	///   - it does not touch server configuration or create users (runSetup's steps 4 and 5).
	public static SetupResult repairProvisioning(String adminPassword) throws SystemException {
		SetupResult res = new SetupResult();
		IOContext ctx = IOSystem.getActiveContext();
		if(ctx == null) {
			throw new SystemException("IO context is not available");
		}

		provisionDefaultOrganizations(adminPassword, res);

		/// Same irreversible-marker guard as runSetup: never close the latch unless an administrator
		/// can actually log in.
		if(!adminCredentialExists()) {
			logger.error("**** REFUSING to write the " + SETUP_MARKER_NAME + " marker: no default"
				+ " organization has a usable administrator credential.");
			res.getWarnings().add("The " + SETUP_MARKER_NAME + " marker was NOT written because no"
				+ " administrator credential exists. Provisioning is INCOMPLETE — re-run with a valid"
				+ " administrator password (minimum " + MIN_PASSWORD_LENGTH + " characters).");
			res.setOk(false);
			return res;
		}
		if(writeMarker() == null) {
			res.getWarnings().add("Failed to write the " + SETUP_MARKER_NAME + " marker");
		}
		res.setOk(!res.isCredentialRequiredButNotSet());
		isSetupComplete();
		return res;
	}

	/// Run the FULL first-run setup sequence. Order is load-bearing:
	///   1. create each default organization when it is not initialized
	///   2. set the admin credential wherever none exists (never overwrite)
	///   3. initialize each organization vault
	///   4. write the six deployment server connection records
	///   5. create the initial user
	///   6. write the .setupState marker
	///
	/// Step 3 must precede step 4: on a genuine first run the boot-time vault initialization never
	/// ran (RestServiceEventListener stops at the first uninitialized organization). Writing a
	/// system.connection apiKey before the vault exists fails inside EncryptFieldProvider, and that
	/// failure path logs the record — with the apiKey still in PLAINTEXT at CREATE time. Keeping
	/// this ordering is what prevents a plaintext key from reaching the log.
	public static SetupResult runSetup(SetupRequest req) throws SystemException {
		assertSetupIncomplete();
		SetupResult res = new SetupResult();
		if(req == null) {
			throw new SystemException("Setup request is null");
		}
		IOContext ctx = IOSystem.getActiveContext();
		if(ctx == null) {
			throw new SystemException("IO context is not available");
		}
		/// Defense in depth: the transport layer validates and returns 400, but never trust that a
		/// caller did.
		if(req.getAdminPassword() != null) {
			String pwErr = validatePassword(req.getAdminPassword());
			if(pwErr != null) {
				throw new SystemException("Invalid administrator password: " + pwErr);
			}
		}

		provisionDefaultOrganizations(req.getAdminPassword(), res);

		/// Step 4 — the six URLs are /System-global deployment configuration.
		OrganizationContext sysOctx = systemOrganizationContext();
		BaseRecord sysAdmin = (sysOctx != null ? sysOctx.getAdminUser() : null);
		if(sysAdmin == null) {
			res.getWarnings().add("Cannot write server configuration: no /System administration user");
		}
		else {
			for(String name : ServerConfigUtil.SERVER_NAMES) {
				String url = req.getServers().get(name);
				/// Omitted keys are left unchanged.
				if(url == null || url.trim().length() == 0) {
					continue;
				}
				if(!ServerConfigUtil.putConnection(sysAdmin, name, url.trim(), null)) {
					res.getWarnings().add("Failed to write the server configuration for '" + name + "'");
				}
			}
		}

		/// Step 5 — initial user.
		if(req.getInitialUserName() != null && req.getInitialUserName().trim().length() > 0) {
			createInitialUserStep(req, res);
		}

		/// Step 6 — marker LAST, and only if an administrator can actually log in.
		///
		/// The marker is IRREVERSIBLE: writing it closes the latch permanently, and the setup
		/// endpoint is the only way to set the first admin password. runSetup previously wrote it
		/// unconditionally, so a caller that passed a null adminPassword (which step 2 only WARNS
		/// about) would produce: no credential written, marker written, latch closed forever, no
		/// admin password and no way to set one. Neither shipped caller can reach that today
		/// (Setup.java 400s on a missing credential and AdminAction validates first), but SetupUtil
		/// is public Objects7 API and the damage is unrecoverable, so the guard belongs here.
		///
		/// adminCredentialExists() is the exact post-condition that matters: at least one default
		/// organization's administrator holds a usable (non-UNKNOWN) credential.
		if(!adminCredentialExists()) {
			logger.error("**** REFUSING to write the " + SETUP_MARKER_NAME + " marker: no default"
				+ " organization has a usable administrator credential. Writing it would close the"
				+ " setup latch permanently with no way to set an administrator password."
				+ " Re-run setup with a valid administrator password.");
			res.getWarnings().add("The " + SETUP_MARKER_NAME + " marker was NOT written because no"
				+ " administrator credential exists. Setup is INCOMPLETE — re-run it with a valid"
				+ " administrator password.");
			res.setOk(false);
			return res;
		}
		if(writeMarker() == null) {
			res.getWarnings().add("Failed to write the " + SETUP_MARKER_NAME + " marker");
		}

		res.setOk(true);
		/// Re-evaluate so the process latch closes immediately.
		isSetupComplete();
		return res;
	}

	private static void createInitialUserStep(SetupRequest req, SetupResult res) {
		String name = req.getInitialUserName().trim();
		String orgPath = (req.getInitialUserOrganization() != null ? req.getInitialUserOrganization().trim() : "");

		/// HARD REJECT anything outside the allow-list — /System above all.
		String orgErr = validateInitialUserOrganization(orgPath);
		if(orgErr != null) {
			logger.error("Rejected initial user '" + name + "' for organization '" + orgPath + "': " + orgErr);
			res.getWarnings().add("Initial user not created: " + orgErr);
			return;
		}
		String nameErr = validateUserName(name);
		if(nameErr != null) {
			logger.error("Rejected initial user name: " + nameErr);
			res.getWarnings().add("Initial user not created: " + nameErr);
			return;
		}
		String pwErr = validatePassword(req.getInitialUserPassword());
		if(pwErr != null) {
			logger.error("Rejected the initial user password: " + pwErr);
			res.getWarnings().add("Initial user not created: " + pwErr);
			return;
		}

		OrganizationContext userOctx;
		try {
			userOctx = organizationContext(orgPath);
		}
		catch(Exception e) {
			res.getWarnings().add("Unknown organization for the initial user: " + orgPath);
			return;
		}
		if(userOctx == null || userOctx.getAdminUser() == null) {
			res.getWarnings().add("Cannot create the initial user: no administration user for " + orgPath);
			return;
		}
		if(nonSystemUserExists(userOctx.getOrganizationId())) {
			/// Idempotence guard: a non-system user already exists, so this is not a first run for
			/// this organization. Do not touch it.
			res.getWarnings().add("Skipped creating the initial user: " + orgPath + " already has a non-system user");
			return;
		}
		BaseRecord created = createInitialUser(userOctx.getAdminUser(), name, req.getInitialUserPassword(),
			userOctx.getOrganizationId());
		if(created == null) {
			res.getWarnings().add("Failed to create the initial user " + name);
			return;
		}
		res.setInitialUser(name);

		/// Post-condition: the created user must carry exactly the Factory.setupUser defaults and
		/// none of the administrative roles. Reported, and logged as an audit line.
		List<String> granted = listSystemRoleMemberships(created, userOctx.getOrganizationId());
		logger.info("SETUP AUDIT: created initial user name='" + name + "' organization='" + orgPath
			+ "' organizationId=" + userOctx.getOrganizationId() + " systemRoles=" + granted);
		List<String> expected = Arrays.asList(EXPECTED_DEFAULT_ROLES);
		for(String role : granted) {
			if(!expected.contains(role)) {
				logger.error("**** Initial user '" + name + "' in " + orgPath + " unexpectedly holds system role '"
					+ role + "'. Expected exactly " + expected + ".");
				res.getWarnings().add("Initial user '" + name + "' unexpectedly holds system role '" + role + "'");
			}
		}
		for(String role : expected) {
			if(!granted.contains(role)) {
				res.getWarnings().add("Initial user '" + name + "' is missing the expected default role '" + role + "'");
			}
		}
	}

	/// Enumerate which of the AccessSchema system USER roles the actor is a member of.
	public static List<String> listSystemRoleMemberships(BaseRecord actor, long organizationId) {
		List<String> out = new ArrayList<>();
		if(actor == null) {
			return out;
		}
		for(String roleName : AccessSchema.SYSTEM_ROLE_NAMES) {
			try {
				BaseRecord role = AccessSchema.getSystemRole(roleName, RoleEnumType.USER.toString(), organizationId);
				if(role == null) {
					continue;
				}
				if(IOSystem.getActiveContext().getMemberUtil().isMember(actor, role, null)) {
					out.add(roleName);
				}
			}
			catch(Exception e) {
				logger.warn("Failed to check role membership for '" + roleName + "': " + e.getMessage());
			}
		}
		return out;
	}

	/// True when the organization already has at least one user that provisioning did not create.
	public static boolean nonSystemUserExists(long organizationId) {
		try {
			List<String> systemNames = Arrays.asList(SYSTEM_USER_NAMES);
			Query q = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_ORGANIZATION_ID, organizationId);
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_ORGANIZATION_ID });
			/// Only ever called pre-latch, when the user count is the provisioning set plus at most
			/// a handful. A small window avoids paging an established deployment.
			q.setRequestRange(0L, SYSTEM_USER_NAMES.length + 8);
			BaseRecord[] users = IOSystem.getActiveContext().getSearch().findRecords(q);
			if(users == null) {
				return false;
			}
			for(BaseRecord u : users) {
				String name = u.get(FieldNames.FIELD_NAME);
				if(name != null && !systemNames.contains(name)) {
					return true;
				}
			}
			return false;
		}
		catch(Exception e) {
			logger.warn("Failed to check for existing users in organization " + organizationId + ": " + e.getMessage());
			/// Fail safe: assume a user exists rather than risk touching an established organization.
			return true;
		}
	}

	/// Create a user + its initial credential. Idempotent by name: when the name already exists the
	/// existing user is returned and its credential is NOT touched.
	///
	/// Mirrors the Console7 -addUser flow (AdminAction.java:88-105) but is the BOOTSTRAP variant:
	/// this runs before the caller has an authenticated session and before role membership exists,
	/// so the credential write goes through getRecordUtil().createRecord() rather than AccessPoint.
	///
	/// Callers are expected to have run validateUserName / validatePassword /
	/// validateInitialUserOrganization first; the guards here are a backstop, not the policy.
	public static BaseRecord createInitialUser(BaseRecord adminUser, String name, String password, long organizationId) {
		if(adminUser == null) {
			logger.error("An administration user is required");
			return null;
		}
		String nameErr = validateUserName(name);
		if(nameErr != null) {
			logger.error("Refusing to create a user: " + nameErr);
			return null;
		}
		String pwErr = validatePassword(password);
		if(pwErr != null) {
			logger.error("Refusing to create a user without a usable password: " + pwErr);
			return null;
		}
		String n = name.trim();
		IOContext ctx = IOSystem.getActiveContext();
		try {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_USER, FieldNames.FIELD_ORGANIZATION_ID, organizationId);
			q.field(FieldNames.FIELD_NAME, n);
			BaseRecord existing = ctx.getSearch().findRecord(q);
			if(existing != null) {
				logger.warn("User " + n + " already exists; leaving its credential untouched");
				return existing;
			}
			BaseRecord user = ctx.getFactory().getCreateUser(adminUser, n, organizationId);
			if(user == null) {
				logger.error("Failed to create user " + n);
				return null;
			}
			logger.info("Created " + n);
			if(!createBootstrapCredential(user, password)) {
				logger.error("Failed to set the credential for " + n);
			}
			return user;
		}
		catch(Exception e) {
			logger.error("Failed to create user " + n + ": " + e.getMessage());
			return null;
		}
	}

	/// BOOTSTRAP credential write.
	///
	/// INTENTIONAL: uses getRecordUtil().createRecord() rather than AccessPoint. This runs
	/// pre-authentication and, for the admin user, before role membership/entitlement wiring exists
	/// for the organization being created, so a PBAC-checked write has nothing to authorize against.
	/// This is the same write the legacy Setup/AdminAction bootstrap path performed. It is
	/// deliberately NOT shared with the day-2 -addUser path.
	public static boolean createBootstrapCredential(BaseRecord owner, String password) {
		String pwErr = validatePassword(password);
		if(pwErr != null) {
			logger.error("Refusing to create a credential: " + pwErr);
			return false;
		}
		try {
			ParameterList plist = ParameterUtil.newParameterList("password", password);
			plist.parameter(FieldNames.FIELD_TYPE, CredentialEnumType.HASHED_PASSWORD.toString().toLowerCase());
			BaseRecord cred = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_CREDENTIAL, owner, null, plist);
			if(cred == null) {
				return false;
			}
			/// CredentialFactory silently leaves the row at UNKNOWN when the type/password pair did
			/// not take (CredentialFactory.java:48-61). Refuse to persist such a row.
			if(!isRealCredential(cred)) {
				logger.error("Refusing to persist a credential that was not built as a hashed password"
					+ " (type is UNKNOWN) for " + owner.get(FieldNames.FIELD_NAME));
				return false;
			}
			boolean created = IOSystem.getActiveContext().getRecordUtil().createRecord(cred);
			if(created) {
				/// Never log credential material — name the subject only.
				logger.info("New credential created for " + owner.get(FieldNames.FIELD_NAME));
			}
			return created;
		}
		catch(Exception e) {
			logger.error("Failed to create a credential: " + e.getMessage());
			return false;
		}
	}

	/// Write the .setupState marker into /System's /Library group.
	///
	/// Goes through AccessPoint (precedent: ChatLibraryUtil.java:255). By this point the
	/// organization's roles and permissions exist, so PBAC has something to authorize against.
	/// Technique: a data.data record whose description carries the JSON.
	///
	/// This comment previously cited FeatureConfigService's .featureConfig marker as the precedent.
	/// That reference is stale: .featureConfig moved to FeatureConfigUtil, became org-scoped under
	/// /Library/Configuration, and now carries its payload in dataBytesStore rather than description
	/// (description is capped at 512 chars, and the default projection omits both fields — see
	/// FeatureConfigUtil). The marker here is deliberately unchanged; it is a short, fixed-size
	/// string, so description remains adequate for it.
	public static BaseRecord writeMarker() {
		try {
			IOContext ctx = IOSystem.getActiveContext();
			OrganizationContext octx = systemOrganizationContext();
			if(octx == null || octx.getAdminUser() == null) {
				logger.error("Cannot write the setup marker: no /System administration user");
				return null;
			}
			BaseRecord existing = findMarker();
			if(existing != null) {
				return existing;
			}
			BaseRecord admin = octx.getAdminUser();
			long orgId = octx.getOrganizationId();
			BaseRecord dir = ctx.getPathUtil().makePath(admin, ModelNames.MODEL_GROUP, LibraryUtil.basePath,
				GroupEnumType.DATA.toString(), orgId);
			if(dir == null) {
				logger.error("Cannot write the setup marker: failed to resolve " + LibraryUtil.basePath);
				return null;
			}
			Map<String, Object> state = new LinkedHashMap<>();
			state.put("completedDate", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
			state.put("version", SETUP_MARKER_VERSION);

			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, LibraryUtil.basePath);
			plist.parameter(FieldNames.FIELD_NAME, SETUP_MARKER_NAME);
			BaseRecord rec = ctx.getFactory().newInstance(ModelNames.MODEL_DATA, admin, null, plist);
			rec.set(FieldNames.FIELD_DESCRIPTION, JSONUtil.exportObject(state));
			rec.set(FieldNames.FIELD_CONTENT_TYPE, "application/json");
			return ctx.getAccessPoint().create(admin, rec);
		}
		catch(Exception e) {
			logger.error("Failed to write the setup marker: " + e.getMessage());
			return null;
		}
	}
}
