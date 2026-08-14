package org.cote.accountmanager.olio.picturebook;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.rules.BookWorldInitializationRule;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;

/**
 * PictureBook 2.0 Olio compartment: one {@code Books} universe per organization, one world per book.
 * <p>
 * <b>{@link #getCreateBookContext(BaseRecord, String, String)} is the only create path.</b> Every
 * read path must resolve a book by FK from an already-authorized {@code olio.pb.book} record and go
 * through {@link #assembleBookContext(BaseRecord)}, which is package-private for exactly that reason:
 * there is deliberately no public slug-addressed read entry in phase 1, because resolving a book by
 * name as the acting user is the "read up" that the design forbids. The authorized public entry
 * ({@code AccessPoint.find(user, olio.pb.book)} then {@code book.world} FK then
 * {@code assembleBookContext}) arrives in phase 2, with the {@code olio.pb.book} model.
 * <p>
 * <b>Grants do not recurse - CLOSED in phase 2a by a recursive world-tier pass.</b> Group entitlements
 * are joined on an exact {@code groupId} ({@code effectiveGroupObjectEntitlementTemplate.sql}), so a grant
 * on {@code Gallery} does NOT reach {@code Gallery/Characters} - the shape {@code SDUtil} creates.
 * Measured, not assumed: {@code TestBookWorld} case 9 shows {@code Gallery=true},
 * {@code Gallery/Characters=false} against a passing control. Because grants are
 * Read/Update/Create/Delete together this was a WRITE gap too, not merely a visibility one. The book
 * configuration now sets {@code scanNestedWorldGroups}, so {@code initialize()} grants the book's own role
 * pair recursively beneath every group of the world container - the world tier ONLY; recursing the
 * universe container would reach {@code Worlds} and hand the shared universe role every book in the
 * organization. <b>Remaining bound:</b> the recursive pass covers the tree that exists when it runs, so a
 * write path that creates a new sub-subgroup mid-session must still grant on the group it created; a
 * re-open repairs it.
 * <p>
 * <b>Two role tiers, since phase 2a.</b> The per-book {@code Writer}/{@code Admin} pair is granted on the
 * book's own world groups; a single organization-wide {@link #universeReaderRolePath()} /
 * {@link #universeWriterRolePath()} pair is granted on the {@code Books} universe corpora. Membership of
 * both is what makes a book usable, so {@link #getCreateBookContext(BaseRecord, String, String)} enrols a
 * genuine creator in the book {@code Writer} role AND the universe {@code Reader} role. The split is
 * <b>not retroactive</b> - {@code setEntitlement} only adds - so books created before it keep their
 * per-book roles' universe grants.
 * <p>
 * <b>RULE (learned twice, now written down): a create-or-get that grants entitlement as a side
 * effect must first prove the resource did not already exist, or must authorize the caller against
 * the existing resource.</b> This is the {@code LibraryUtil} trap from {@code architecture.md}, one
 * level up. {@link #getCreateBookContext(BaseRecord, String, String)} reaches
 * {@code WorldUtil.getCreateWorld}, which is a find-or-create: if the slug names somebody else's
 * existing book, the existing world and its existing {@code Writer} role are resolved, and an
 * unconditional "enrol the creator" step would then hand the caller Writer on a book they have no
 * claim to - with the authorization check passing by construction, because the actor used for it is
 * the organization admin. So the create path probes existence with the find-only
 * {@code WorldUtil.findWorld} BEFORE anything creates, and enrols ONLY on genuine creation; an
 * already-existing book must instead authorize the caller (book {@code Writer}/{@code Admin}
 * membership, or the org admin) and throws otherwise.
 */
public class PbOlioContextUtil {
	public static final Logger logger = LogManager.getLogger(PbOlioContextUtil.class);

	/** The single, per-organization universe that holds every book world. */
	public static final String BOOKS_UNIVERSE = "Books";

	public static final String BOOK_ROLE_BASE = "~/Roles/Olio/Books";

	/**
	 * The only shape a book slug may take: lowercase alphanumerics, dot, underscore and hyphen, first
	 * character alphanumeric, 1-64 characters.
	 * <p>
	 * This is a security control, not cosmetics. The slug is interpolated into BOTH a role path
	 * ({@link #writerRolePath(String)}) and the world's group container path, and {@code PathUtil}
	 * splits a path on {@code /}. {@code ..} happens to be inert (a path segment is a literal group
	 * name, never a filesystem traversal), but {@code /} is not: a slug of {@code "alpha/inner"} nests
	 * the inner book's container INSIDE book {@code alpha}'s container, and
	 * {@code OlioContext.resolveGrantTargets} enumerates a container's children by {@code parentId} -
	 * so book {@code alpha}'s roles would receive Read/Update/Create/<b>Delete</b> on the inner book's
	 * groups. Validation therefore has to happen before the first {@code makePath}, not after.
	 */
	public static final Pattern BOOK_SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");

	private PbOlioContextUtil() {
		/// static utility
	}

	/**
	 * Reject any slug that is not {@link #BOOK_SLUG_PATTERN}. Called before any path is built.
	 *
	 * @throws OlioException if the slug is null, empty, or contains anything outside the pattern
	 */
	public static void validateBookSlug(String bookSlug) throws OlioException {
		if(bookSlug == null || bookSlug.trim().length() == 0) {
			throw new OlioException("Book slug is null or empty");
		}
		if(!BOOK_SLUG_PATTERN.matcher(bookSlug).matches()) {
			throw new OlioException("Invalid book slug '" + bookSlug + "': a slug must match "
				+ BOOK_SLUG_PATTERN.pattern()
				+ " (lowercase alphanumerics, '.', '_' and '-', starting with an alphanumeric, at most 64 characters)."
				+ " Path separators in particular are refused: they would nest one book's groups inside another's.");
		}
	}

	/** {@code ~/Roles/Olio/Books/{bookSlug}/Writer} - read/create/update/delete within the book world. */
	public static String writerRolePath(String bookSlug) {
		return BOOK_ROLE_BASE + "/" + bookSlug + "/Writer";
	}

	/** {@code ~/Roles/Olio/Books/{bookSlug}/Admin} - full control of the book world. */
	public static String adminRolePath(String bookSlug) {
		return BOOK_ROLE_BASE + "/" + bookSlug + "/Admin";
	}

	/**
	 * {@code ~/Roles/Olio/Books/Reader} - the UNIVERSE tier. One per organization, shared by every book:
	 * <b>Read</b> on the {@code Books} universe's own corpora groups (words, names, colours, apparel
	 * templates) and nothing else.
	 * <p>
	 * <b>Corpora-only is a deliberate deviation</b> from §5.3's "universe membership implies read every
	 * book": book worlds are not granted to this tier at all. It satisfies the actual requirement - the
	 * per-book role alone is useless because apparel templates and colours live in the universe - without
	 * creating a read-every-book role before there is a use case for one. Reversible (add the grants);
	 * the reverse is not, because {@code setEntitlement} only ever adds.
	 * <p>
	 * It cannot collide with a per-book role container: {@code BOOK_SLUG_PATTERN} admits lowercase only,
	 * so no slug can be named {@code Reader} or {@code Writer}.
	 */
	public static String universeReaderRolePath() {
		return BOOK_ROLE_BASE + "/Reader";
	}

	/**
	 * {@code ~/Roles/Olio/Books/Writer} - the universe tier's admin role, holding Create/Update on the
	 * universe's corpora (and Delete on the universe's own, non-shared groups).
	 * <p>
	 * <b>Nothing enrols anybody here automatically.</b> That is the point of the split: before it, a
	 * per-book {@code Admin} role received Create/Update/Delete on the shared corpora simply by being a
	 * book's admin role. See {@link #universeReaderRolePath()}.
	 */
	public static String universeWriterRolePath() {
		return BOOK_ROLE_BASE + "/Writer";
	}

	/**
	 * Build the configuration for a book world.
	 * <p>
	 * A book world has no map, no locations, no realms and no population generation:
	 * {@code features} is empty (so {@code loadLocations} skips {@code GeoParser.loadInfo} while every
	 * other corpus still loads), {@code requireRealms} is false (so the three "no realms" ERROR lines
	 * become INFO), and the rule list is exactly
	 * {@code [BookWorldInitializationRule, GenericItemDataLoadRule]} with no evolution or state rules.
	 * <p>
	 * {@code enrolActingUser} is false: creating or opening a book must never enrol anybody. The
	 * creator is enrolled explicitly and audited by
	 * {@link #getCreateBookContext(BaseRecord, String, String)}.
	 * <p>
	 * <b>This creates four roles</b> via {@code makePath} - the two per-book roles ({@code Writer},
	 * {@code Admin}) and the two organization-wide universe-tier roles
	 * ({@link #universeReaderRolePath()}, {@link #universeWriterRolePath()}) -
	 * because a role must exist before {@code setEntitlement} can grant on it -
	 * {@code AuthorizationUtil.setEntitlement} silently skips a null permission and only logs
	 * membership failures under trace, so a missing role produces no grants and no error. It therefore
	 * belongs on the create path only.
	 *
	 * <p>
	 * The slug is validated FIRST, before any {@code makePath}: see {@link #BOOK_SLUG_PATTERN}.
	 * <p>
	 * <b>Package-private, because it creates.</b> It is a step of
	 * {@link #getCreateBookContext(BaseRecord, String, String)} - the class javadoc's "only create
	 * path" - and it runs two {@code makePath} calls with no authorization check of its own. Public
	 * would make it a second, unguarded create entry. Nothing outside this package calls it; the one
	 * test that does reaches it through the test-only shim
	 * {@code org.cote.accountmanager.olio.picturebook.BookContextTestAccess}, exactly as
	 * {@link #assembleBookContext(BaseRecord)} is reached, so no production modifier is widened for a
	 * test.
	 *
	 * @throws OlioException if the slug is malformed, or the organization, the olio principal, or
	 *         either role cannot be resolved
	 */
	static OlioContextConfiguration newBookConfiguration(BaseRecord user, String dataPath, String bookSlug) throws OlioException {
		if(user == null) {
			throw new OlioException("User is null");
		}
		validateBookSlug(bookSlug);
		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext octx = ioContext.findOrganizationContext(user);
		if(octx == null) {
			throw new OlioException("Failed to find organization context");
		}
		long orgId = octx.getOrganizationId();

		BaseRecord olioUser = ioContext.getFactory().getCreateUser(octx.getAdminUser(), OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			throw new OlioException("Failed to find or create the olio user");
		}

		BaseRecord writerRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, writerRolePath(bookSlug), RoleEnumType.USER.toString(), orgId);
		if(writerRole == null) {
			throw new OlioException("Failed to create book role " + writerRolePath(bookSlug));
		}
		BaseRecord adminRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, adminRolePath(bookSlug), RoleEnumType.USER.toString(), orgId);
		if(adminRole == null) {
			throw new OlioException("Failed to create book role " + adminRolePath(bookSlug));
		}

		/// The universe tier - one pair per organization, shared by every book. Same reason as the two
		/// roles above: setEntitlement silently skips a role it cannot resolve, so the role has to exist
		/// before initialize() runs its universe grant pass.
		BaseRecord universeReaderRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, universeReaderRolePath(), RoleEnumType.USER.toString(), orgId);
		if(universeReaderRole == null) {
			throw new OlioException("Failed to create universe role " + universeReaderRolePath());
		}
		BaseRecord universeWriterRole = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_ROLE, universeWriterRolePath(), RoleEnumType.USER.toString(), orgId);
		if(universeWriterRole == null) {
			throw new OlioException("Failed to create universe role " + universeWriterRolePath());
		}

		OlioContextConfiguration cfg = new OlioContextConfiguration(
			user,
			dataPath,
			BOOKS_UNIVERSE,
			bookSlug,
			new String[0],
			0,
			0,
			false,
			false
		);
		cfg.setBasePath("/Olio");
		cfg.setRequireRealms(false);
		cfg.setEnrolActingUser(false);
		cfg.setAuthorizationUserRole(writerRole);
		cfg.setAuthorizationAdminRole(adminRole);
		cfg.setUniverseAuthorizationUserRole(universeReaderRole);
		cfg.setUniverseAuthorizationAdminRole(universeWriterRole);
		/// Grants stop at the world's own groups otherwise - entitlements are joined on an exact groupId.
		/// See OlioContext.scanNestedWorldGroups().
		cfg.setScanNestedWorldGroups(true);
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new BookWorldInitializationRule(),
			new GenericItemDataLoadRule()
		}));
		return cfg;
	}

	/**
	 * Create (or resolve) the book world for {@code bookSlug} and return its initialized context.
	 * <p>
	 * This is the ONLY path that may create a book world. Ordering matters and is:
	 * <ol>
	 * <li>resolve/create the two per-book roles ({@code newBookConfiguration}) - BEFORE initialize,
	 * because grants against a non-existent role fail silently;</li>
	 * <li>initialize through the shared context cache;</li>
	 * <li>verify: {@code isAuthorizationConfigured()} (NOT {@code isInitialized()}, which is set
	 * before the grant calls and inside a swallow-all catch), the creator's membership in the book
	 * Writer role, and the presence of a Read grant for that role on EVERY enumerated group of BOTH
	 * tiers - the book's own world groups and the shared {@code Books} universe groups - not one
	 * sampled probe, which cannot detect a single missing grant among ~80.</li>
	 * </ol>
	 * A verification failure throws rather than returning a half-built context - and, because
	 * {@code getCachedContext} publishes the context to the SHARED cache before any of the post-checks
	 * run, a failure must also evict it. Otherwise the next caller is handed, from cache, precisely the
	 * unverified context this method refused to return.
	 * <p>
	 * <b>Create-vs-open is decided BEFORE anything creates</b>, with the find-only
	 * {@code WorldUtil.findWorld}, because {@code initialize()} reaches the find-or-create
	 * {@code WorldUtil.getCreateWorld} and cannot afterwards tell the two apart. See the class
	 * javadoc for the rule. Consequences:
	 * <ul>
	 * <li>the world did NOT exist - genuine creation; the creator is enrolled in the book Writer role
	 * and that enrolment is audited;</li>
	 * <li>the world DID exist - nothing is enrolled, and the caller must already be entitled to that
	 * book (a member of its {@code Writer} or {@code Admin} role, or the organization admin). An
	 * unentitled caller gets an {@code OlioException}, not a context.</li>
	 * </ul>
	 * <b>Known bound (not fixed here):</b> the probe and the create are not one atomic operation, so
	 * two callers racing on the same brand-new slug can both observe "absent" and both be enrolled as
	 * creators of the one world that actually gets made. That window needs a serialization point
	 * (a uniqueness constraint surfaced as a create failure, or a per-slug lock) rather than a wider
	 * check, and it is a race for a slug nobody owns yet - not the "claim somebody else's existing
	 * book" defect this ordering closes.
	 */
	public static OlioContext getCreateBookContext(BaseRecord user, String dataPath, String bookSlug) throws OlioException {
		if(user == null) {
			throw new OlioException("User is null");
		}
		/// Before ANY path is built - see BOOK_SLUG_PATTERN.
		validateBookSlug(bookSlug);

		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext octx = ioContext.findOrganizationContext(user);
		if(octx == null) {
			throw new OlioException("Failed to find organization context");
		}

		/// Existence probe FIRST, find-only, before newBookConfiguration (which creates roles) and
		/// before initialize() (which reaches the find-or-create getCreateWorld).
		boolean preExisting = (findBookWorld(octx, bookSlug) != null);
		if(preExisting && !isEntitledToBook(user, octx, bookSlug)) {
			throw new OlioException("Book '" + bookSlug + "' already exists and "
				+ user.get(FieldNames.FIELD_NAME) + " is not entitled to it."
				+ " Opening an existing book requires membership of " + writerRolePath(bookSlug)
				+ " or " + adminRolePath(bookSlug) + ".");
		}

		OlioContextConfiguration cfg = newBookConfiguration(user, dataPath, bookSlug);

		boolean verified = false;
		try {
			OlioContext ctx = OlioContextUtil.getCachedContext(user, BOOKS_UNIVERSE, bookSlug, () -> {
				OlioContext bctx = new OlioContext(cfg);
				bctx.initialize();
				return bctx;
			});
			if(ctx == null) {
				throw new OlioException("Failed to construct a book context for " + bookSlug);
			}
			if(!ctx.isAuthorizationConfigured()) {
				throw new OlioException("Book context " + bookSlug + " did not complete world authorization");
			}
			if(ctx.getWorld() == null || ctx.getUniverse() == null) {
				throw new OlioException("Book context " + bookSlug + " has no world or universe");
			}

			BaseRecord writerRole = cfg.getAuthorizationUserRole();
			BaseRecord universeReaderRole = cfg.getUniverseAuthorizationUserRole();
			if(!preExisting) {
				/// GENUINE CREATION ONLY. The creator is enrolled explicitly and audited. registerUser
				/// authorizes the actor, and the organization admin is authorized by definition - which
				/// is precisely why this must never run on the open-an-existing-book path: there the
				/// check would pass by construction and grant Writer on somebody else's book.
				if(!ctx.registerUser(octx.getAdminUser(), user, false)) {
					throw new OlioException("Failed to enrol " + user.get(FieldNames.FIELD_NAME) + " in " + writerRolePath(bookSlug));
				}
				if(!ioContext.getMemberUtil().isMember(user, writerRole, null)) {
					throw new OlioException("Creator is not a member of " + writerRolePath(bookSlug));
				}
				/// BOTH tiers, and this one is not optional. Since the two-tier split the per-book roles
				/// hold nothing on the universe, so a creator enrolled in the book Writer role alone
				/// cannot read the corpora - apparel templates, colours, word lists - that generating
				/// anything at all requires. The universe Reader role is Read on shared corpora only.
				if(!ctx.registerUniverseUser(octx.getAdminUser(), user, false)) {
					throw new OlioException("Failed to enrol " + user.get(FieldNames.FIELD_NAME) + " in " + universeReaderRolePath());
				}
				if(!ioContext.getMemberUtil().isMember(user, universeReaderRole, null)) {
					throw new OlioException("Creator is not a member of " + universeReaderRolePath());
				}
			}

			verifyGrants(ctx, writerRole, universeReaderRole, octx);
			verified = true;
			return ctx;
		}
		finally {
			/// Leave nothing behind on ANY non-local exit. The context was published to the shared
			/// cache by getCachedContext before the checks above ran, so a failure that did not evict
			/// would let the next caller retrieve the very context this call rejected.
			///
			/// The evict is itself wrapped: it dereferences the user's organizationId, and a throw from
			/// a finally block REPLACES the exception that is already propagating - turning a precise
			/// OlioException into an unrelated NPE at the call site.
			if(!verified) {
				try {
					evictBookContext(user, bookSlug);
				}
				catch(Exception e) {
					logger.error("Failed to evict the unverified book context " + bookSlug + ": " + e.getMessage(), e);
				}
			}
		}
	}

	/**
	 * Find-only: the {@code olio.world} for {@code bookSlug}, or null when it does not exist.
	 * <p>
	 * Creates nothing, on any branch. The olio principal is resolved with {@code findUser}, not
	 * {@code getCreateUser}: an organization with no olio principal cannot have a book world, so the
	 * correct answer there is "absent", not "make one".
	 */
	private static BaseRecord findBookWorld(OrganizationContext octx, String bookSlug) {
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, octx.getOrganizationId());
		if(olioUser == null) {
			return null;
		}
		return WorldUtil.findWorld(olioUser, bookWorldPath(), bookSlug);
	}

	/** {@code /Olio/Universes/Books/Worlds} - the group that holds every book world record. */
	static String bookWorldPath() {
		return new OlioContextConfiguration().getUniversePath() + "/" + BOOKS_UNIVERSE + "/Worlds";
	}

	/**
	 * Is {@code user} already entitled to the EXISTING book {@code bookSlug}?
	 * <p>
	 * True for the organization admin, or for a member of the book's own {@code Writer} or
	 * {@code Admin} role. Both roles are resolved find-only. Note this is deliberately NOT
	 * {@code registerUser}'s check: that one authorizes an ACTOR to enrol somebody, using the org
	 * admin as actor on the create path, and so cannot be reused to decide whether the CALLER may open
	 * a book that already exists.
	 */
	private static boolean isEntitledToBook(BaseRecord user, OrganizationContext octx, String bookSlug) {
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord orgAdmin = octx.getAdminUser();
		if(orgAdmin != null && orgAdmin.get(FieldNames.FIELD_ID) != null
			&& orgAdmin.get(FieldNames.FIELD_ID).equals(user.get(FieldNames.FIELD_ID))) {
			return true;
		}
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, octx.getOrganizationId());
		if(olioUser == null) {
			return false;
		}
		for(String rolePath : new String[] {writerRolePath(bookSlug), adminRolePath(bookSlug)}) {
			BaseRecord role = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE, rolePath, RoleEnumType.USER.toString(), octx.getOrganizationId());
			if(role != null && ioContext.getMemberUtil().isMember(user, role, null)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Assert a Read entitlement on every group the world authorization enumerated - <b>on BOTH tiers, and
	 * against the role that owns each tier</b>. Throws on the first gap, naming the tier and the group.
	 * <p>
	 * <b>Two passes, because {@code initialize()} makes two grant calls, and two ROLES, because of the
	 * two-tier split.</b> {@code OlioContext.initialize} runs {@code configureWorldAuthorization} once for
	 * the universe ({@code config.getUniversePath()}, {@code userWrite=false}) and once for the world
	 * ({@code config.getWorldPath()}, {@code userWrite=true}). Verifying only the world tier would leave
	 * the universe tier - the corpora every book reads from - checked by nothing at runtime, and this
	 * method exists precisely because {@code isInitialized()} is true even when authorization threw.
	 * <p>
	 * <b>The universe tier must be checked against the universe role, not the book role.</b> Since the
	 * split the book {@code Writer} role holds nothing on the universe, so checking it there would fail
	 * every newly created book. Books created BEFORE the split keep their universe grants
	 * ({@code setEntitlement} only adds), so this is not a check that could have been kept either way -
	 * it distinguishes a correct system from a broken one only when addressed to the right role.
	 * <p>
	 * <b>Read, never CRUD.</b> The universe pass runs with {@code userWrite=false}, so the universe
	 * {@code Reader} role legitimately holds only Read there. Read is the one permission BOTH passes
	 * grant, so it is the only one assertable across the whole set; asserting Update/Create/Delete on the
	 * universe tier would fail a correct system.
	 *
	 * @param worldRole the book's own role, checked against the world's groups
	 * @param universeRole the shared universe-tier role, checked against the universe's groups
	 */
	private static void verifyGrants(OlioContext ctx, BaseRecord worldRole, BaseRecord universeRole, OrganizationContext octx) throws OlioException {
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord readPerm = ioContext.getPathUtil().findPath(octx.getAdminUser(), ModelNames.MODEL_PERMISSION, "/Read", PermissionEnumType.DATA.toString(), octx.getOrganizationId());
		if(readPerm == null) {
			throw new OlioException("Failed to resolve the Read permission");
		}
		verifyReadGrants(ioContext, worldRole, readPerm, ctx.getAuthorizationGroups(ctx.getWorld(), ctx.getConfig().getWorldPath()), "world");
		verifyReadGrants(ioContext, universeRole, readPerm, ctx.getAuthorizationGroups(ctx.getUniverse(), ctx.getConfig().getUniversePath()), "universe");
	}

	/**
	 * One tier of {@link #verifyGrants(OlioContext, BaseRecord, OrganizationContext)}. The failure mode
	 * is identical on either tier: throw naming the offending group rather than return a
	 * half-verified context.
	 */
	private static void verifyReadGrants(IOContext ioContext, BaseRecord role, BaseRecord readPerm, List<BaseRecord> groups, String tier) throws OlioException {
		for(BaseRecord grp : groups) {
			if(!ioContext.getAuthorizationUtil().checkEntitlement(role, readPerm, grp)) {
				throw new OlioException("Missing Read grant for " + role.get(FieldNames.FIELD_NAME) + " on " + tier + " group " + grp.get(FieldNames.FIELD_NAME) + " (#" + grp.get(FieldNames.FIELD_ID) + ")");
			}
		}
	}

	/**
	 * Assemble a read-only {@link BookContext} from an ALREADY-RESOLVED, ALREADY-AUTHORIZED
	 * {@code olio.world}.
	 * <p>
	 * Package-private on purpose - see the class javadoc. It must never call
	 * {@code OlioContext.initialize()}: that begins with {@code configureEnvironment}, which
	 * {@code getCreateUser}s the olio principal and (when enabled) enrols the acting user, i.e. it
	 * would make a read mutate authorization. This assembles from existing records only, with no rule
	 * pipeline, no clock, no realms and no {@code configureWorldAuthorization}.
	 *
	 * @return null at the first missing piece (olio principal, universe, or the world's own record)
	 */
	static BookContext assembleBookContext(BaseRecord world) {
		if(world == null) {
			return null;
		}
		IOContext ioContext = IOSystem.getActiveContext();
		long orgId = world.get(FieldNames.FIELD_ORGANIZATION_ID);

		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			logger.warn("No olio user in organization " + orgId);
			return null;
		}

		OlioContextConfiguration paths = new OlioContextConfiguration();
		String universePath = paths.getUniversePath();
		BaseRecord universe = WorldUtil.findWorld(olioUser, universePath, BOOKS_UNIVERSE);
		if(universe == null) {
			logger.warn("No " + BOOKS_UNIVERSE + " universe in organization " + orgId);
			return null;
		}
		String worldName = world.get(FieldNames.FIELD_NAME);
		String worldPath = universePath + "/" + BOOKS_UNIVERSE + "/Worlds";
		BaseRecord fullWorld = WorldUtil.findWorld(olioUser, worldPath, worldName);
		if(fullWorld == null) {
			logger.warn("No book world " + worldName + " under " + worldPath);
			return null;
		}
		ioContext.getReader().populate(universe, 2);
		ioContext.getReader().populate(fullWorld, 2);

		Map<String, BaseRecord> extra = new HashMap<>();
		String containerPath = worldPath + "/" + worldName;
		for(String grp : new String[] {BookWorldInitializationRule.GROUP_BOOK, BookWorldInitializationRule.GROUP_WORKFLOW, BookWorldInitializationRule.GROUP_ARTIFACTS}) {
			BaseRecord dir = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, containerPath + "/" + grp, GroupEnumType.DATA.toString(), orgId);
			if(dir != null) {
				extra.put(grp.toLowerCase(), dir);
			}
		}

		/// The olio principal stays HERE: it is an unauthorized find and BookContext neither takes nor
		/// holds it.
		return new BookContext(universe, fullWorld, extra);
	}

	/**
	 * Drop the cached context for one user's view of one book. Note the cache is per
	 * {@code (organizationId, user, universe, world)}: use
	 * {@code OlioContextUtil.evictByWorld(organizationId, worldObjectId)} to drop every user's view
	 * after a book reset or delete.
	 * <p>
	 * A user with no organization id is a no-op, not an NPE: this is called from a {@code finally}
	 * block on the create path, where a throw would replace the exception already propagating.
	 */
	public static void evictBookContext(BaseRecord user, String bookSlug) {
		if(user == null || bookSlug == null) {
			return;
		}
		Long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		String userName = user.get(FieldNames.FIELD_NAME);
		if(orgId == null || userName == null) {
			logger.warn("Cannot evict a book context for a user with no organization id or name");
			return;
		}
		OlioContextUtil.evict(orgId.longValue(), userName, BOOKS_UNIVERSE, bookSlug);
	}
}
