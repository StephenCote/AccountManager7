package org.cote.accountmanager.olio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.LibraryUtil;

public class OlioContext {
	public static final Logger logger = LogManager.getLogger(OlioContext.class);
	
	protected OlioContextConfiguration config = null;
	private Overwatch overwatch = null;
	protected BaseRecord world = null;
	protected BaseRecord universe = null;
	private boolean initialized = false;
	/// Each epoch currently defaults to 1 year
	///
	// private BaseRecord currentEpoch = null;
	
	private List<BaseRecord> locations = new ArrayList<>();
	private List<BaseRecord> populationGroups = new ArrayList<>();
	/// Each location event defaults to 1 year
	/// All events for a location within that period of time fall under the location event
	///
	// private BaseRecord currentEvent = null;
	//private BaseRecord currentLocation = null;
	// private BaseRecord currentIncrement = null;
	
	private Map<Long, List<BaseRecord>> populationMap = new ConcurrentHashMap<>();
	private Map<Long, Map<String,List<BaseRecord>>> demographicMap = new ConcurrentHashMap<>();
	
	private List<BaseRecord> realms = new ArrayList<>();
	/*
	private ZonedDateTime currentTime = ZonedDateTime.now();
	private ZonedDateTime currentMonth = currentTime;
	private ZonedDateTime currentDay = currentTime;
	private ZonedDateTime currentHour = currentTime;
	*/
	/** Name of the internal Olio principal that owns world data and the Olio roles. */
	public static final String OLIO_USER_NAME = "olioUser";

	private String olioUserName = OLIO_USER_NAME;
	private BaseRecord olioUser = null;
	private boolean initConfig = false;
	
	private boolean trace = false;
	private Clock clock = null;
	
	public OlioContext(OlioContextConfiguration cfg) {
		this.config = cfg;
		this.overwatch = new Overwatch(this);
	}
	
	public void overwatchActions() throws OverwatchException {
		overwatch.process();
	}
	public Overwatch getOverwatch() {
		return overwatch;
	}

	public void clearCache() {
		populationMap.clear();
		demographicMap.clear();
		realms.clear();
		Queue.clear();
		CacheUtil.clearCache();
	}
	
	public boolean isTrace() {
		return trace;
	}
	public void setTrace(boolean trace) {
		this.trace = trace;
	}
	public BaseRecord getOlioUser() {
		return olioUser;
	}

	public OlioContextConfiguration getConfig() {
		return config;
	}

	public BaseRecord getWorld() {
		return world;
	}

	public BaseRecord getUniverse() {
		return universe;
	}

	public boolean isInitialized() {
		return initialized;
	}

	/**
	 * Distinct from {@link #isInitialized()} - and the ONLY flag that means "the world authorization
	 * grants were applied".
	 * <p>
	 * {@code initialized = true} is set part-way through {@link #initialize()}, BEFORE the two
	 * {@code configureWorldAuthorization} calls, and the whole body of {@code initialize()} sits
	 * inside a swallow-all {@code catch}. So {@code isInitialized()} returns true even when
	 * authorization threw, and asserting it proves only that initialization reached that statement.
	 * This flag is set only after both grant calls have completed without throwing.
	 * <p>
	 * (The position of {@code initialized = true} is deliberately left alone: moving it is a live
	 * behaviour change for grid/arena and is tracked separately.)
	 */
	public boolean isAuthorizationConfigured() {
		return authorizationConfigured;
	}

	private BaseRecord adminRole = null;
	private BaseRecord userRole = null;
	private boolean authorizationConfigured = false;

	/**
	 * Resolve the user role this context should act on: the per-context role from the configuration
	 * when present, otherwise the org-wide role held in the instance field.
	 * <p>
	 * This exists to close the "role instance-field trap": {@code configureEnvironment} sets
	 * {@link #userRole}/{@link #adminRole} to the ORG-WIDE {@code ~/Roles/Olio User} /
	 * {@code ~/Roles/Olio Admin} unconditionally, before {@code initialize()} ever reaches the
	 * role-parameterised grant calls. Without this indirection every role-less entry point
	 * ({@code enroleReader}, {@code enroleAdmin}, {@code scanNestedGroups}) would silently act on the
	 * org-wide tier even on a context configured with its own roles - granting in the
	 * isolation-losing direction, and doing it quietly.
	 */
	private BaseRecord effectiveUserRole() {
		BaseRecord cfgRole = (config != null ? config.getAuthorizationUserRole() : null);
		return (cfgRole != null ? cfgRole : userRole);
	}

	/** @see #effectiveUserRole() */
	private BaseRecord effectiveAdminRole() {
		BaseRecord cfgRole = (config != null ? config.getAuthorizationAdminRole() : null);
		return (cfgRole != null ? cfgRole : adminRole);
	}

	/**
	 * @deprecated Enrols an ARBITRARY user with NO authorization check on the caller. Use
	 *             {@link #registerUser(BaseRecord, BaseRecord, boolean)}, which authorizes the actor
	 *             and writes an audit record.
	 */
	@Deprecated
	public boolean enroleReader(BaseRecord user) {
		return enrole(user, effectiveUserRole());
	}

	/**
	 * @deprecated Enrols an ARBITRARY user with NO authorization check on the caller. Use
	 *             {@link #registerUser(BaseRecord, BaseRecord, boolean)}, which authorizes the actor
	 *             and writes an audit record.
	 */
	@Deprecated
	public boolean enroleAdmin(BaseRecord user) {
		return enrole(user, effectiveAdminRole());
	}
	protected boolean enrole(BaseRecord user, BaseRecord role) {
		boolean enabled = false;
		if(!IOSystem.getActiveContext().getMemberUtil().isMember(user, role,  null)) {
			enabled = IOSystem.getActiveContext().getMemberUtil().member(olioUser, role, user, null, true);
		}
		else {
			enabled = true;
		}
		return enabled;

	}

	/**
	 * Enrol {@code user} in this context's user or admin role, as an explicitly authorized and
	 * audited operation. This is the supported replacement for {@link #enroleReader(BaseRecord)} /
	 * {@link #enroleAdmin(BaseRecord)}, which take an arbitrary user and check nothing.
	 * <p>
	 * The target role is this context's effective pair - the per-context roles from
	 * {@code OlioContextConfiguration} when set, otherwise the org-wide {@code ~/Roles/Olio *} pair.
	 * <p>
	 * {@code actor} must be a member of the effective admin role, or the organization's admin user.
	 * The membership write itself is performed as the olio user (the owner of the roles).
	 * <p>
	 * <b>Idempotent.</b> Enrolling somebody who is already enrolled is SUCCESS, not failure.
	 * {@code MemberUtil.member(..., enable=true)} returns false when the membership already exists
	 * ("Entry already exists") - that is its contract, not an error - so this probes membership first
	 * and returns true without writing, exactly as {@link #enrole(BaseRecord, BaseRecord)} does. The
	 * probe deliberately comes AFTER the authorization check: an unauthorized actor is refused even
	 * when the target happens to already be a member.
	 *
	 * <p>
	 * <b>The target must belong to this context's organization.</b> Roles are organization-scoped, so
	 * enrolling a foreign-organization principal would write a cross-tenant membership.
	 * <p>
	 * <b>Audit shape.</b> The audit's CONTEXT USER is the actor (who asked) and its SUBJECT is the
	 * enrolled user (who it happened to); the resource is the role. Both must be structured fields:
	 * with the subject set to the actor, "who was enrolled, at whose request" was answerable only by
	 * parsing the free-text message - and on the book create path, where the actor is the org admin,
	 * every enrolment audited identically as "admin ADD admin".
	 *
	 * @param actor the principal requesting the enrolment; authorized, not merely recorded
	 * @param user the principal being enrolled
	 * @param asAdmin true to enrol into the admin role, false for the user role
	 * @return true when the user is enrolled - either because this call wrote the membership or
	 *         because it already existed. False means the write was attempted and failed; never
	 *         discard it
	 * @throws OlioException if the context is not usable, the role cannot be resolved, the target is
	 *         in a different organization, or the actor is not authorized
	 */
	public boolean registerUser(BaseRecord actor, BaseRecord user, boolean asAdmin) throws OlioException {
		if(actor == null) {
			throw new OlioException("Actor is null");
		}
		if(user == null) {
			throw new OlioException("User is null");
		}
		if(olioUser == null) {
			throw new OlioException("Olio User is null");
		}
		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext octx = ioContext.findOrganizationContext(config.getUser());
		if(octx == null) {
			throw new OlioException("Failed to find organization context");
		}
		/// Org-scope the TARGET before anything is written. The roles below belong to this context's
		/// organization; a target from another one would be a cross-tenant membership.
		Long userOrg = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		if(userOrg == null || userOrg.longValue() != octx.getOrganizationId()) {
			throw new OlioException("Cannot register " + user.get(FieldNames.FIELD_NAME)
				+ " (organization " + userOrg + ") in organization " + octx.getOrganizationId());
		}
		BaseRecord admRole = effectiveAdminRole();
		BaseRecord role = (asAdmin ? admRole : effectiveUserRole());
		if(role == null) {
			throw new OlioException("Failed to resolve the " + (asAdmin ? "admin" : "user") + " role");
		}

		/// contextUser = the actor (who asked), subject = the enrolled user (who it happened to),
		/// resource = the role. See the javadoc: both have to be structured fields.
		BaseRecord audit = AuditUtil.startAudit(actor, ActionEnumType.ADD, user, role);
		BaseRecord orgAdmin = octx.getAdminUser();
		boolean authorized = (
			(orgAdmin != null && orgAdmin.get(FieldNames.FIELD_ID) != null && orgAdmin.get(FieldNames.FIELD_ID).equals(actor.get(FieldNames.FIELD_ID)))
			|| (admRole != null && ioContext.getMemberUtil().isMember(actor, admRole, null))
		);
		if(!authorized) {
			AuditUtil.closeAudit(audit, ResponseEnumType.DENY, "Actor is not a member of the Olio administrator role");
			throw new OlioException("Not authorized to register " + user.get(FieldNames.FIELD_NAME));
		}

		/// Already enrolled is success. member(..., true) reports false for an existing entry, so
		/// without this probe every re-open of an already-registered world would report a failure.
		if(ioContext.getMemberUtil().isMember(user, role, null)) {
			AuditUtil.closeAudit(audit, ResponseEnumType.PERMIT, user.get(FieldNames.FIELD_NAME) + " is already registered in " + role.get(FieldNames.FIELD_NAME));
			return true;
		}

		boolean enabled = ioContext.getMemberUtil().member(olioUser, role, user, null, true);
		AuditUtil.closeAudit(audit, (enabled ? ResponseEnumType.PERMIT : ResponseEnumType.INVALID), "Register " + user.get(FieldNames.FIELD_NAME) + " in " + role.get(FieldNames.FIELD_NAME) + " at the request of " + actor.get(FieldNames.FIELD_NAME));
		return enabled;
	}

	/**
	 * Legacy entry point, retained so grid/arena/agent callers do not change. It resolves the
	 * ORG-WIDE {@code ~/Roles/Olio Admin} / {@code ~/Roles/Olio User} pair, writes them to the
	 * {@link #adminRole}/{@link #userRole} instance fields (as it always has), and then delegates.
	 * The role-parameterised overload deliberately does NOT touch those fields.
	 */
	public void configureWorldAuthorization(BaseRecord cfgWorld, boolean userWrite) throws OlioException {
		if(cfgWorld == null) {
			throw new OlioException("World is null");
		}
		if(olioUser == null) {
			throw new OlioException("Olio User is null");
		}
		OrganizationContext octx = IOSystem.getActiveContext().findOrganizationContext(config.getUser());
		if(octx == null) {
			throw new OlioException("Failed to find organization context");
		}
		IOContext ioContext = IOSystem.getActiveContext();
		adminRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio Admin", RoleEnumType.USER.toString(), octx.getOrganizationId());
		userRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio User", RoleEnumType.USER.toString(), octx.getOrganizationId());
		configureWorldAuthorization(cfgWorld, userRole, adminRole, deriveContainerPath(cfgWorld), userWrite);
	}

	/**
	 * Grant the supplied user/admin roles on the groups belonging to {@code cfgWorld}.
	 * <p>
	 * Unlike the 2-arg form this does NOT write the {@link #adminRole}/{@link #userRole} instance
	 * fields, so a context configured with its own role pair keeps the org-wide pair out of its
	 * grant path entirely.
	 * <p>
	 * Target groups are resolved deterministically - see
	 * {@link #resolveGrantTargets(BaseRecord, String, long)}. The previous {@code parentId}-only
	 * {@code findRecord} container lookup (first-row-wins on an unsorted query) is not used.
	 * <p>
	 * <b>Permissions.</b> Shared {@code /Library/*} groups receive at most {@code Read, Update,
	 * Create} - never {@code Delete} - matching {@code LibraryUtil}'s own grant. The world's own
	 * groups keep {@code Read, Update, Create, Delete} for the admin role and
	 * {@code userWrite ? CRUD : Read} for the user role.
	 * <p>
	 * <b>This narrowing is not retroactive.</b> {@code AuthorizationUtil.setEntitlement} only ADDS
	 * entitlements; it never revokes. Organizations whose shared library groups already carry a
	 * {@code Delete} grant for the Olio roles keep it until a separate revoke utility is written and
	 * run. Only grants issued from here onward are narrowed.
	 *
	 * @param cfgWorld the universe or world record whose groups are being granted on
	 * @param cfgUserRole the role receiving read (or CRUD) access
	 * @param cfgAdminRole the role receiving CRUD access
	 * @param containerPath group path CONTAINING the world's own container group - i.e.
	 *        {@code config.getUniversePath()} for a universe and {@code config.getWorldPath()} for a
	 *        world. The leaf segment is derived from {@code cfgWorld}'s own name, never from a
	 *        separately-passed world name that could disagree with the record
	 * @param userWrite true to give the user role write access to the world's own groups
	 */
	public void configureWorldAuthorization(BaseRecord cfgWorld, BaseRecord cfgUserRole, BaseRecord cfgAdminRole, String containerPath, boolean userWrite) throws OlioException {

		if(cfgWorld == null) {
			throw new OlioException("World is null");
		}
		if(olioUser == null) {
			throw new OlioException("Olio User is null");
		}
		if(cfgUserRole == null || cfgAdminRole == null) {
			throw new OlioException("Both a user role and an admin role are required");
		}
		OrganizationContext octx = IOSystem.getActiveContext().findOrganizationContext(config.getUser());
		if(octx == null) {
			throw new OlioException("Failed to find organization context");
		}
		if(trace) {
			logger.info("CONFIGURE WORLD " + cfgWorld.get(FieldNames.FIELD_NAME));
		}
		IOContext ioContext = IOSystem.getActiveContext();

		GrantTargets targets = resolveGrantTargets(cfgWorld, containerPath, octx);

		String[] rperms = new String[] {"Read"};
		String[] cruperms = new String[] {"Read", "Update", "Create"};
		String[] crudperms = new String[] {"Read", "Update", "Create", "Delete"};
		String[] entTypes = new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()};

		/// use org admin to set entitlement to address use/references to shared libraries
		for(BaseRecord group : targets.shared) {
			ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), cfgUserRole, new BaseRecord[] {group}, (userWrite ? cruperms : rperms), entTypes);
			ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), cfgAdminRole, new BaseRecord[] {group}, cruperms, entTypes);
		}
		for(BaseRecord group : targets.own) {
			ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), cfgUserRole, new BaseRecord[] {group}, (userWrite ? crudperms : rperms), entTypes);
			ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), cfgAdminRole, new BaseRecord[] {group}, crudperms, entTypes);
		}
	}

	/**
	 * The complete, deterministically-enumerated set of groups
	 * {@link #configureWorldAuthorization(BaseRecord, BaseRecord, BaseRecord, String, boolean)} grants
	 * on - shared and own together. Exposed so a caller can VERIFY grants across the whole set rather
	 * than probing one sampled group (a single probe cannot detect one missing grant among ~36).
	 *
	 * @param containerPath as per the 5-arg {@code configureWorldAuthorization}
	 */
	public List<BaseRecord> getAuthorizationGroups(BaseRecord cfgWorld, String containerPath) throws OlioException {
		if(cfgWorld == null) {
			throw new OlioException("World is null");
		}
		if(olioUser == null) {
			throw new OlioException("Olio User is null");
		}
		OrganizationContext octx = IOSystem.getActiveContext().findOrganizationContext(config.getUser());
		if(octx == null) {
			throw new OlioException("Failed to find organization context");
		}
		GrantTargets targets = resolveGrantTargets(cfgWorld, containerPath, octx);
		List<BaseRecord> all = new ArrayList<>(targets.shared);
		all.addAll(targets.own);
		return all;
	}

	/** Grant-target partition: shared {@code /Library/*} groups vs the world's own groups. */
	private static class GrantTargets {
		private final List<BaseRecord> shared = new ArrayList<>();
		private final List<BaseRecord> own = new ArrayList<>();
	}

	/**
	 * Deterministically enumerate the groups a world's roles must be granted on.
	 * <p>
	 * Two sources, de-duplicated by group id:
	 * <ol>
	 * <li>the world record's own foreign {@code auth.group} fields, partitioned into shared
	 * ({@code /Library/*} corpora) and own - note this collects the NON-shared fields too, which an
	 * earlier implementation discarded;</li>
	 * <li>the world's container group, resolved BY NAME via {@code pathUtil.findPath}, and that
	 * container's children by {@code parentId}. The container itself is not a target, matching the
	 * previous behaviour.</li>
	 * </ol>
	 * A null foreign group field throws, on every path. {@code WorldFactory.implement()} creates all
	 * of a world's groups unconditionally, so a null there is a genuine anomaly and must abort
	 * loudly rather than be skipped with a warning.
	 * <p>
	 * <b>The shared/own test fails CLOSED, and the {@code shared} attribute alone is not enough to
	 * decide it.</b> {@code own} receives {@code Delete}; {@code shared} never does. But
	 * {@code AttributeUtil.getAttributeValue} reads only the in-memory {@code attributes} list - there
	 * is no read-through - and {@code LibraryUtil.getCreateSharedGroup} stamps that attribute on its
	 * CREATE branch only ({@code LibraryUtil.java:39-45}: a {@code findPath} hit returns at {@code :40}
	 * before {@code :45}). So a {@code /Library/*} group that predates the attribute, or that simply
	 * was not populated deeply enough on this read, reports "not shared" - and an attribute-only test
	 * would hand the Olio roles {@code Delete} over an ORG-WIDE shared corpus. The membership test is
	 * therefore "the attribute says shared <b>OR</b> the group is a child of {@code /Library}", with
	 * the {@code /Library} children enumerated by {@code parentId} rather than inferred from a virtual
	 * {@code path} field that may not be computed on a foreign-field read.
	 */
	private GrantTargets resolveGrantTargets(BaseRecord cfgWorld, String containerPath, OrganizationContext octx) throws OlioException {
		IOContext ioContext = IOSystem.getActiveContext();
		long organizationId = octx.getOrganizationId();
		GrantTargets targets = new GrantTargets();
		List<Long> seen = new ArrayList<>();
		Set<Long> libraryGroupIds = resolveSharedLibraryGroupIds(octx);
		List<String> sharedDesc = new ArrayList<>();

		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_WORLD);
		for(FieldSchema fs : ms.getFields()) {
			if(fs.getBaseModel() != null && fs.getBaseModel().equals(ModelNames.MODEL_GROUP) && fs.isForeign()) {
				BaseRecord group = cfgWorld.get(fs.getName());
				if(group == null) {
					throw new OlioException("Group " + fs.getName() + " is null");
				}
				try {
					long gid = group.get(FieldNames.FIELD_ID);
					if(seen.contains(gid)) {
						continue;
					}
					seen.add(gid);
					boolean attrShared = ((boolean)AttributeUtil.getAttributeValue(group, "shared", false) == true);
					boolean libShared = libraryGroupIds.contains(gid);
					if(attrShared || libShared) {
						targets.shared.add(group);
						sharedDesc.add(fs.getName() + "(" + (attrShared ? "attr" : "") + (attrShared && libShared ? "+" : "") + (libShared ? "lib" : "") + ")");
					}
					else {
						targets.own.add(group);
					}
				}
				catch(ModelException e) {
					throw new OlioException(e.getMessage());
				}
			}
		}

		if(containerPath == null) {
			throw new OlioException("Container path is null");
		}
		String worldContainerPath = containerPath + "/" + (String)cfgWorld.get(FieldNames.FIELD_NAME);
		BaseRecord pdir = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, worldContainerPath, GroupEnumType.DATA.toString(), organizationId);
		if(pdir == null) {
			throw new OlioException("Failed to find parent group " + worldContainerPath);
		}
		Query ppq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, pdir.get(FieldNames.FIELD_ID), organizationId);
		for(BaseRecord group : ioContext.getSearch().findRecords(ppq)) {
			long gid = group.get(FieldNames.FIELD_ID);
			if(seen.contains(gid)) {
				continue;
			}
			seen.add(gid);
			targets.own.add(group);
		}

		logger.info("Grant targets for " + cfgWorld.get(FieldNames.FIELD_NAME) + ": " + targets.shared.size()
			+ " shared " + sharedDesc + ", " + targets.own.size() + " own (own receives Delete, shared does not)");

		/// Fail loudly rather than silently: a world configured to use the shared libraries but whose
		/// shared partition is EMPTY means every library corpus was classified as the world's own and
		/// is about to receive Delete.
		if(config != null && config.isUseSharedLibraries() && targets.shared.isEmpty()) {
			logger.error("SHARED LIBRARY CLASSIFICATION FAILED for world " + cfgWorld.get(FieldNames.FIELD_NAME)
				+ " in organization " + organizationId + ": useSharedLibraries is true but NO group classified as shared."
				+ " Every /Library corpus is about to be granted Delete. Resolved " + libraryGroupIds.size()
				+ " child group(s) of " + LibraryUtil.basePath + ".");
		}

		return targets;
	}

	/**
	 * Ids of the immediate children of {@code /Library} - the org-wide shared corpora.
	 * <p>
	 * Resolved as the organization admin (the owner {@code LibraryUtil} creates them with) and by
	 * {@code parentId}, so the answer does not depend on the virtual {@code path} field being computed
	 * or on the {@code shared} attribute being present in memory. An organization with no
	 * {@code /Library} yields an empty set, which is correct: there is nothing shared to protect.
	 */
	private Set<Long> resolveSharedLibraryGroupIds(OrganizationContext octx) {
		Set<Long> ids = new HashSet<>();
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord libDir = ioContext.getPathUtil().findPath(octx.getAdminUser(), ModelNames.MODEL_GROUP, LibraryUtil.basePath, GroupEnumType.DATA.toString(), octx.getOrganizationId());
		if(libDir == null) {
			return ids;
		}
		Query lq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, libDir.get(FieldNames.FIELD_ID), octx.getOrganizationId());
		for(BaseRecord group : ioContext.getSearch().findRecords(lq)) {
			ids.add((long)group.get(FieldNames.FIELD_ID));
		}
		return ids;
	}

	/**
	 * Container path for the legacy 2-arg {@code configureWorldAuthorization}: the universe record
	 * sits under {@code config.getUniversePath()} and the world record under
	 * {@code config.getWorldPath()}, and in both cases the container's leaf is the record's own name.
	 */
	private String deriveContainerPath(BaseRecord cfgWorld) {
		String name = cfgWorld.get(FieldNames.FIELD_NAME);
		boolean isUniverse = (universe != null && cfgWorld == universe)
			|| (config.getUniverseName() != null && config.getUniverseName().equals(name));
		return (isUniverse ? config.getUniversePath() : config.getWorldPath());
	}

	public void scanNestedGroups(BaseRecord cfgWorld, String fieldName, boolean userWrite) {
		BaseRecord dir = cfgWorld.get(fieldName);
		scanNestedGroups(dir, userWrite);
	}
	/**
	 * Recursively grant this context's EFFECTIVE role pair on {@code dir} and its descendants. Group
	 * entitlements do not inherit down the tree, so the recursion is required, not belt-and-braces.
	 * <p>
	 * The roles come from {@link #effectiveUserRole()}/{@link #effectiveAdminRole()}, so a context
	 * carrying its own role pair does not grant the org-wide Olio roles here.
	 */
	public void scanNestedGroups(BaseRecord dir, boolean userWrite) {


		//logger.info("Configure group " + dir.get(FieldNames.FIELD_NAME));
		String[] rperms = new String[] {"Read"};
		String[] crudperms = new String[] {"Read", "Update", "Create", "Delete"};
		IOContext ioContext = IOSystem.getActiveContext();
		ioContext.getAuthorizationUtil().setEntitlement(olioUser, effectiveUserRole(), new BaseRecord[] {dir}, (userWrite ? crudperms : rperms), new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
		ioContext.getAuthorizationUtil().setEntitlement(olioUser, effectiveAdminRole(), new BaseRecord[] {dir}, crudperms, new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});

		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, dir.get(FieldNames.FIELD_ID), dir.get(FieldNames.FIELD_ORGANIZATION_ID));

		BaseRecord[] dirs = ioContext.getSearch().findRecords(pq);

		// logger.info("Scan group " + dir.get(FieldNames.FIELD_NAME) + " (#" + dir.get(FieldNames.FIELD_ID) + " in #" +  dir.get(FieldNames.FIELD_ORGANIZATION_ID) + ") with " + dirs.length + " children");
		for(BaseRecord group : dirs) {
			scanNestedGroups(group, userWrite);
		}
	}

	public void configureEnvironment() throws OlioException {
		if(config == null) {
			throw new OlioException("Configuration is null");
		}
		if(config.getUser() == null) {
			throw new OlioException("Configuration user is null");
		}

		OrganizationContext octx = IOSystem.getActiveContext().findOrganizationContext(config.getUser());
		if(octx == null) {
			throw new OlioException("Failed to find organization context");
		}
		
		IOContext ioContext = IOSystem.getActiveContext();
		olioUser = ioContext.getFactory().getCreateUser(octx.getAdminUser(), olioUserName, octx.getOrganizationId());
		if(olioUser == null) {
			throw new OlioException("Failed to find olio user");
		}
		adminRole = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio Admin", RoleEnumType.USER.toString(), octx.getOrganizationId());
		if(adminRole != null) {
			/// KI-35. The Olio roles already exist, so the one-time provisioning below is done — but
			/// the ACTING USER's enrolment is NOT one-time, and used to sit below this return.
			///
			/// Consequence: `member(olioUser, userRole, config.getUser(), ...)` ran only in the run
			/// that first created the Olio Admin role, i.e. for exactly one user, ever, per
			/// organization. Every later acting user got no membership in ~/Roles/Olio User and
			/// therefore none of the world-group grants that role carries. ApparelUtil deliberately
			/// creates apparel/wearables/qualities as the OLIO user (so colours resolve from the
			/// shared colour library), while the character and store belong to the acting user — so
			/// for any user who did not happen to initialise the world, dress-up/down was a write to
			/// another owner's record with no grant behind it. PBAC refused it, `inuse` stayed true
			/// forever ("always worn"), and describeOutfit kept reporting the full outfit.
			///
			/// userRole must also be resolved here: the early return used to leave it null on every
			/// subsequent init, and only configureWorldAuthorization happened to reassign it later.
			///
			/// enrole() is idempotent (it checks isMember first), so this is safe to run every time.
			///
			/// PB2 phase 1: the userRole RESOLUTION below stays unconditional (KI-35 depends on it),
			/// but the ENROLMENT is gated on config.isEnrolActingUser(), which defaults to false.
			/// Constructing a context must not be a way to grant yourself access.
			if(userRole == null) {
				userRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio User", RoleEnumType.USER.toString(), octx.getOrganizationId());
			}
			if(config.isEnrolActingUser() && userRole != null && !enrole(config.getUser(), userRole)) {
				logger.warn("Failed to enrol " + config.getUser().get(FieldNames.FIELD_NAME)
					+ " in the Olio User role — writes to Olio-owned apparel/wearables will be denied");
			}
			return;
		}

		initConfig = true;
		adminRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio Admin", RoleEnumType.USER.toString(), octx.getOrganizationId());
		userRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, "~/Roles/Olio User", RoleEnumType.USER.toString(), octx.getOrganizationId());
		ioContext.getMemberUtil().member(olioUser, adminRole, olioUser, null, true);

		/// PB2 phase 1: first-run enrolment of the acting user is gated the same way as the
		/// every-run branch above. See OlioContextConfiguration.isEnrolActingUser().
		if(config.isEnrolActingUser()) {
			ioContext.getMemberUtil().member(olioUser, userRole, config.getUser(), null, true);
		}

		BaseRecord rootDir = ioContext.getPathUtil().makePath(octx.getAdminUser(), ModelNames.MODEL_GROUP, config.getBasePath(), GroupEnumType.DATA.toString(), octx.getOrganizationId());
		if(rootDir == null) {
			throw new OlioException("Root directory is null");
		}
		
		ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), olioUser, new BaseRecord[] {rootDir}, new String[] {"Read", "Update", "Create"}, new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
		
		BaseRecord uDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, config.getUniversePath(), GroupEnumType.DATA.toString(), octx.getOrganizationId());
		if(uDir == null) {
			throw new OlioException("Universe directory is null");
		}
		BaseRecord wDir = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, config.getWorldPath(), GroupEnumType.DATA.toString(), octx.getOrganizationId());
		if(wDir == null) {
			throw new OlioException("World directory is null");
		}

		/// This block runs ONCE per organization - on whichever context happens to be the first. The
		/// roles it grants here are the ORG-WIDE `~/Roles/Olio *` pair (the local fields, deliberately
		/// not effectiveUserRole()/effectiveAdminRole()), so what it grants on is visible to every
		/// Olio user in the organization.
		///
		/// rootDir (/Olio) and uDir (/Olio/Universes) are the deliberate org-wide root reference and
		/// are granted unconditionally, exactly as before.
		///
		/// wDir is NOT. For grid/arena it is the generic per-universe Worlds container and stays in the
		/// grant, so that behaviour is byte-for-byte unchanged. But a context carrying its OWN role
		/// pair is a compartment (a PictureBook book), and there wDir is
		/// /Olio/Universes/Books/Worlds - the group holding EVERY book's olio.world record. If the
		/// first Olio context in an organization happened to be a book, granting the org-wide Olio User
		/// role Read there would expose every book slug and every book's group FKs to the whole org.
		boolean compartmentalized = (config.getAuthorizationUserRole() != null || config.getAuthorizationAdminRole() != null);
		BaseRecord[] entryDirs = (compartmentalized
			? new BaseRecord[] {rootDir, uDir}
			: new BaseRecord[] {rootDir, uDir, wDir});
		if(compartmentalized) {
			logger.info("First-run Olio bootstrap on a compartmentalized context: withholding the org-wide Read grant on "
				+ config.getWorldPath() + " (the compartment's own roles are granted separately)");
		}
		ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), userRole, entryDirs, new String[] {"Read"}, new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
		ioContext.getAuthorizationUtil().setEntitlement(octx.getAdminUser(), adminRole, entryDirs, new String[] {"Read"}, new String[] {PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString()});
	}
	
	public void initialize() {
		if(trace) {
			logger.info("Initializing Olio Context ...");
		}
		try {
			configureEnvironment();
			if(initialized) {
				logger.warn("Context is already initialized");
				return;
			}
			long start = System.currentTimeMillis();
			if(trace) {
				logger.info("Get/Create Universe ...");
			}
			universe = WorldUtil.getCreateWorld(olioUser, config.getUniversePath(), config.getUniverseName(), config.getFeatures());
			if(universe == null) {
				throw new OlioException("Failed to load universe " + config.getUniverseName());
			}
			IOSystem.getActiveContext().getReader().populate(universe, 2);
			
			if(trace) {
				logger.info("Check/Load World Data ...");
			}
			WorldUtil.loadWorldData(this);
			if(trace) {
				logger.info("Get/Create World ...");
			}
			world = WorldUtil.getCreateWorld(olioUser, universe, config.getWorldPath(), config.getWorldName(), new String[0]);
			if(world == null) {
				throw new OlioException("Failed to load world " + config.getWorldName());
			}
			if(config.isResetWorld()) {
				if(trace) {
					logger.info("Reset World ...");
				}
				WorldUtil.cleanupWorld(olioUser, world);
			}
			IOSystem.getActiveContext().getReader().populate(world, 2);
			if(trace) {
				logger.info("Pregenerate ...");
			}
			config.getContextRules().forEach(r -> {
				r.pregenerate(this);
			});
			if(trace) {
				logger.info("Get/Create Regions ...");
			}
			BaseRecord rootEvent = null;
			for(IOlioContextRule r : config.getContextRules()){
				BaseRecord evt = r.generate(this);
				if(evt != null) {
					rootEvent = evt;
					break;
				}
			};

			if(rootEvent == null) {
				throw new OlioException("Failed to find or create a new region");
			}
			if(trace) {
				logger.info("Postgenerate ...");
			}
			config.getContextRules().forEach(r -> {
				r.postgenerate(this);
			});
			if(trace) {
				logger.info("Get/Create Epoch ...");
			}

			clock = new Clock(EventUtil.getLastEpochEvent(this), EventUtil.getRootEvent(this));
			
			locations = getRealms().stream().map(r -> (BaseRecord)r.get(OlioFieldNames.FIELD_ORIGIN)).collect(Collectors.toList());


			populationGroups.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, world.get(OlioFieldNames.FIELD_POPULATION_ID)))));

			initialized = true;
			
			if(trace) {
				logger.info("Generate Regions ...");
			}
			for(BaseRecord realm : getRealms()) {
				for(IOlioContextRule rule : config.getContextRules()) {
					rule.generateRegion(this, realm);
				}
			}
			
			if(!startOrContinueRealmEvents()) {
				if(config.isRequireRealms()) {
					logger.error("Failed to start realms");
				}
				else {
					logger.info("Realms were not started (requireRealms is false)");
				}
			}

			BaseRecord cfgUserRole = config.getAuthorizationUserRole();
			BaseRecord cfgAdminRole = config.getAuthorizationAdminRole();
			if(cfgUserRole != null && cfgAdminRole != null) {
				configureWorldAuthorization(universe, cfgUserRole, cfgAdminRole, config.getUniversePath(), false);
				configureWorldAuthorization(world, cfgUserRole, cfgAdminRole, config.getWorldPath(), true);
			}
			else {
				configureWorldAuthorization(universe, false);
				configureWorldAuthorization(world, true);
			}
			/// Set ONLY after both grant calls returned. `initialized` above is not evidence that
			/// authorization ran - see isAuthorizationConfigured().
			authorizationConfigured = true;


			if(trace) {
				long stop = System.currentTimeMillis();
				logger.info("... Olio Context Initialized in " + (stop - start) + "ms");
			}
			
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	
	public BaseRecord getRealmConstructEvent(BaseRecord realm) {
		long eid = realm.get(FieldNames.FIELD_ID);
		Query eq = QueryUtil.createQuery(OlioModelNames.MODEL_EVENT, OlioFieldNames.FIELD_REALM, eid);
		eq.field(FieldNames.FIELD_GROUP_ID, world.get(OlioFieldNames.FIELD_EVENTS_ID));
		eq.field(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
		eq.getRequest().addAll(Arrays.asList(new String[] {FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_EVENT_START, OlioFieldNames.FIELD_EVENT_PROGRESS, OlioFieldNames.FIELD_EVENT_END}));
		return IOSystem.getActiveContext().getSearch().findRecord(eq);
	}

	/*
	public BaseRecord[] getChildEvents() {
		if(currentEpoch != null) {
			return getChildEvents(currentEpoch);
		}
		return new BaseRecord[0];
	}
	
	public BaseRecord[] getChildEvents(BaseRecord event) {
		return EventUtil.getChildEvents(world, event, EventEnumType.UNKNOWN);
	}
	*/
	private boolean startOrContinueRealmEvents() throws ClockException {
		BaseRecord ep = startOrContinueEpoch();
		int errors = 0;
		if(ep != null) {
			List<BaseRecord> rlms = getRealms();
			if(rlms.size() == 0) {
				/// A realm-free world (a PictureBook book world) is a valid configuration, not a fault.
				if(config.isRequireRealms()) {
					logger.error("No realms detected");
					errors++;
				}
				else {
					logger.info("No realms detected (requireRealms is false)");
				}
			}
			for(BaseRecord r: rlms) {
				r.setValue(OlioFieldNames.FIELD_CURRENT_EPOCH, ep);
				Queue.queueUpdate(r, new String[] {OlioFieldNames.FIELD_CURRENT_EPOCH});
				BaseRecord revt = startOrContinueRealmEvent(r);
				if(revt == null) {
					logger.error("Failed to start or continue realm epoch");
					errors++;
					continue;
				}
				clock.realmClock(r).setEvent(revt);
				BaseRecord ievt = startOrContinueRealmIncrement(r);
				if(ievt == null) {
					logger.error("Failed to start or continue realm increment");
					errors++;
					continue;
				}
				clock.realmClock(r).setIncrement(ievt);
				evaluateIncrement(r);
			}
		}
		else {
			logger.error("Root Epoch is null");
			errors++;
		}
		Queue.processQueue();
		return (errors == 0);
	}

	private BaseRecord startOrContinueEpoch() {
		BaseRecord e = null;
		try {
			if(clock.getEpoch() != null) {
				ActionResultEnumType aet = ActionResultEnumType.valueOf(clock.getEpoch().get(FieldNames.FIELD_STATE));
				if(aet == ActionResultEnumType.PENDING) {
					for(IOlioEvolveRule r : config.getEvolutionRules()) {
						r.continueEpoch(this, clock.getEpoch());
					}
					e = clock.getEpoch();
				}
			}
			else {
				logger.info("Start an epoch");
				e = startEpoch();
			}
		}
		catch(Exception er) {
			logger.error(er);
			er.printStackTrace();
		}
		
		return e;
	}
	
	public Clock realmClock(BaseRecord realm) throws ClockException {
		return clock.realmClock(realm);
	}
	
	public Clock clock() {
		return clock;
	}
	
	public BaseRecord startEpoch() {
		return EpochUtil.startEpoch(this);
	}
	
	public void abandonEpoch() {
		BaseRecord currentEpoch = clock.getEpoch();
		if(currentEpoch != null) {
			ActionResultEnumType aet = ActionResultEnumType.valueOf(currentEpoch.get(FieldNames.FIELD_STATE));
			if(aet != ActionResultEnumType.COMPLETE) {
				IOSystem.getActiveContext().getRecordUtil().deleteRecord(currentEpoch);
				currentEpoch = EventUtil.getLastEpochEvent(this);
			}
		}
	}

	public List<BaseRecord> getRealms() {
		if(realms.size() > 0) {
			return realms;
		}
		
		Query rq = QueryUtil.createQuery(OlioModelNames.MODEL_REALM, FieldNames.FIELD_GROUP_ID, world.get(OlioFieldNames.FIELD_REALMS_GROUP_ID));
		OlioUtil.planMost(rq);
		//logger.info(rq.toSelect());
		realms.addAll(Arrays.asList(IOSystem.getActiveContext().getSearch().findRecords(rq)));
		
		if(realms.size() == 0) {
			logger.info("Creating realms ...");
			List<BaseRecord> locs = GeoLocationUtil.getRegionLocations(this);
			for(BaseRecord loc: locs) {
				realms.add(getRealm(loc));
			}
		}

		return realms;
	}

	public BaseRecord getRealm(BaseRecord location) {
		long id = location.get(FieldNames.FIELD_ID);
		Optional<BaseRecord> rlm = realms.stream().filter(r -> id == (long)r.get("origin.id")).findFirst();
		BaseRecord realm = null;
		if(!rlm.isPresent()) {
			realm = RealmUtil.getCreateRealm(this, location);
			if(realm == null) {
				logger.error("Realm is null");
				return null;
			}
		}
		else {
			realm = rlm.get();
		}
		updateRealm(realm);
		return realm;
	}
	
	private void updateRealm(BaseRecord realm) {

		BaseRecord org = realm.get(OlioFieldNames.FIELD_ORIGIN);
		if(org == null) {
			logger.error("Origin is missing");
			logger.error(realm.toFullString());
			return;
		}
		try {
			if(clock != null) {
				realm.set(OlioFieldNames.FIELD_CURRENT_EPOCH, clock.getEpoch());
			}
			Queue.queue(realm.copyRecord(new String[] {FieldNames.FIELD_ID, OlioFieldNames.FIELD_CURRENT_EPOCH, OlioFieldNames.FIELD_CURRENT_EVENT, OlioFieldNames.FIELD_CURRENT_INCREMENT}));
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
	}

	private BaseRecord startOrContinueRealmEvent(BaseRecord realm) {
		if(clock.getEpoch() == null) {
			logger.error("Current epoch is null");
			return null;
		}
		
		BaseRecord cevt = realm.get(OlioFieldNames.FIELD_CURRENT_EVENT);
		if(cevt != null) {
			ActionResultEnumType aet = ActionResultEnumType.valueOf(cevt.get(FieldNames.FIELD_STATE));
			if(aet == ActionResultEnumType.PENDING) {
				for(IOlioEvolveRule r : config.getEvolutionRules()) {
					r.continueRealmEvent(this, realm);
				}
				return cevt;
			}
			else {
				logger.warn("Current realm epoch is not in a pending state");
				logger.warn(cevt.toFullString());
			}
		}
		return startRealmEvent(realm);

	}
	
	public BaseRecord startRealmEvent(BaseRecord realm) {
		return EpochUtil.startRealmEvent(this, realm);
	}
	
	public void endRealmEpoch(BaseRecord realm) {
		EpochUtil.endRealmEvent(this, realm);
	}

	public void endEpoch() {
		EpochUtil.endEpoch(this);
	}
	
	public void evaluateIncrement(BaseRecord realm) {
		BaseRecord evt = realm.get(OlioFieldNames.FIELD_CURRENT_EVENT);
		BaseRecord ievt = realm.get(OlioFieldNames.FIELD_CURRENT_INCREMENT);
		if(evt == null) {
			logger.error("Invalid current event");
			return;
		}
		if(ievt == null) {
			logger.error("Invalid current increment");
			return;
		}
		for(IOlioEvolveRule r : config.getEvolutionRules()) {
			r.evaluateRealmIncrement(this, realm);
		}		
	}
	
	public BaseRecord startOrContinueRealmIncrement(BaseRecord realm) {
		return startOrContinueIncrement(realm);
	}
	

	public BaseRecord startOrContinueIncrement(BaseRecord realm) {
		if(clock.getEpoch() == null) {
			logger.error("Invalid location epoch");
		}
		BaseRecord inc = null;
		for(IOlioEvolveRule r : config.getEvolutionRules()) {
			inc = r.continueRealmIncrement(this, realm);
			if(inc != null) {
				break;
			}
		}
		if(inc != null) {
			return inc;
		}
		return startIncrement(realm);
	}
	
	public BaseRecord startIncrement(BaseRecord realm) {
		if(clock.getEpoch() == null) {
			logger.error("Invalid epoch");
			return null;
		}
		return EpochUtil.startRealmIncrement(this, realm);
	}

	
	public BaseRecord endIncrement(BaseRecord realm) throws ClockException {
		Clock rclock = clock.realmClock(realm);
		if(rclock.getIncrement() == null) {
			logger.error("Invalid increment");
			return null;
		}

		return EpochUtil.endRealmIncrement(this, realm);

	}

	public BaseRecord continueIncrement(BaseRecord realm) throws ClockException {
		Clock rclock = clock.realmClock(realm);
		if(rclock.getIncrement() == null) {
			logger.error("Invalid increment");
			return null;
		}
		return EpochUtil.continueRealmIncrement(this, realm);
	}

	public Map<Long, Map<String, List<BaseRecord>>> getDemographicMap() {
		return demographicMap;
	}
	public Map<String, List<BaseRecord>> getDemographicMap(BaseRecord location) {
		return OlioUtil.getDemographicMap(this, location);
	}	
	public Map<Long, List<BaseRecord>> getPopulationMap() {
		return populationMap;
	}

	public List<BaseRecord> getLocations() {
		return locations;
	}

	public List<BaseRecord> getPopulationGroups() {
		return populationGroups;
	}
	
	public List<BaseRecord> getRealmPopulation(BaseRecord realm){
		return OlioUtil.getRealmPopulation(this, realm);
	}
	
	public boolean validateContext() {
		if(!initialized) {
			logger.error("Context is not initialized");
			return false;
		}
		if(world == null) {
			logger.error("World is null");
			return false;
		}
		IOSystem.getActiveContext().getReader().populate(world, 2);
		if(universe == null) {
			logger.error("A basis world is required");
			return false;
		}
		BaseRecord rootEvt = EventUtil.getRootEvent(this);
		if(rootEvt == null) {
			logger.error("Root event could not be found");
			return false;
		}
		BaseRecord rootLoc = GeoLocationUtil.getRootLocation(this);
		if(rootLoc == null){
			logger.error("Failed to find root location");
			return false;
		}
		
		return true;
	}
	
	public BaseRecord getRootLocation() {
		 return GeoLocationUtil.getRootLocation(this);
	}
	
}
