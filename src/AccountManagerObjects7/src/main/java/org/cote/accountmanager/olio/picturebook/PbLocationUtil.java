package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.PointOfInterestEnumType;
import org.cote.accountmanager.olio.PointOfInterestUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbProposalStatusEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;

/**
 * PictureBook V3 Phase 3 (design §7, §7.2): resolve a setting named in the text to exactly one
 * {@code olio.pb.location} in the book's Olio world, and keep that location linked to a map-free
 * {@code olio.pointOfInterest} with its own {@code olio.store}.
 * <p>
 * <b>Who writes what.</b> Locations are world sub-records: like every other record under a world's
 * groups they are instantiated and persisted <i>as the olio principal</i> ({@code OlioContext.OLIO_USER_NAME}),
 * the {@link PbBookUtil}/{@link PbSubRecordUtil} pattern, never as the request user and never as the org
 * admin. The request user is the authorization gate: every write here is preceded by a strict
 * {@code canUpdate(user, user, book)} PERMIT check, and the result is re-read as the request user so a
 * caller who may not read the Locations group gets a 403, not a record.
 * <p>
 * <b>POI link rules</b> (§7.2): in a book world (any world under {@code /Olio/Universes/Books/Worlds}) a
 * location gets a fresh POI + store with {@code location} null and {@code east}/{@code north} at the model
 * default (rule 1) - no {@code data.geoLocation} is ever created and this class never writes {@code location},
 * {@code east} or {@code north}. In a game world the location is linked to the one already-placed POI of the
 * same name (rule 2) and nothing else is written; zero or several hits leave {@code poi} null. A dangling or
 * never-linked {@code poi} is repaired by {@link PbHealthUtil} re-running {@link #linkPoi} (rule 5).
 * <p>
 * Nothing here composes, renders or binds a graph node - that is P3-4 / P3-5.
 */
public class PbLocationUtil {

	private static final Logger logger = LogManager.getLogger(PbLocationUtil.class);

	/** Aliases are an unbounded text column; the write path caps them so a runaway clusterer cannot fill it. */
	static final int MAX_ALIASES = 32;
	/** Variants per location; the same bound as aliases - a cap on how far one row can be fanned out, not a product limit. */
	static final int MAX_VARIANTS_PER_LOCATION = 32;
	static final int MAX_ALIAS_LENGTH = 128;
	/** {@code olio.pointOfInterest.name} is maxLength 128 while {@code olio.pb.location.name} is 256. */
	static final int MAX_POI_NAME_LENGTH = 128;
	/** {@code description} has no maxLength in the model; bounded here for the same reason as the aliases. */
	static final int MAX_DESCRIPTION_LENGTH = 4096;

	private static final Pattern COMMERCIAL = Pattern.compile("\\b(tavern|inn|shop|market|bar|store|brothel|stall)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern RUIN = Pattern.compile("\\b(ruin|ruins|wreck|derelict|abandoned)\\b", Pattern.CASE_INSENSITIVE);
	private static final Pattern LANDMARK = Pattern.compile("\\b(hill|hills|river|lake|forest|cliff|cliffs|beach|mountain|mountains|falls|wood|woods|moor|field|fields)\\b", Pattern.CASE_INSENSITIVE);

	private PbLocationUtil() {}

	// ─────────────────────────────── gates ───────────────────────────────

	/** The book's world context, or 409 when the book has no resolvable world. */
	public static BookContext requireBookContext(BaseRecord user, BaseRecord book) {
		BookContext bctx = PbBookUtil.openBookContext(user, book);
		if(bctx == null || bctx.getWorld() == null) {
			throw new PictureBookException(409, "Book " + (book != null ? book.get(OlioFieldNames.FIELD_PB_SLUG) : null)
				+ " has no resolvable world; locations live in the book's world");
		}
		return bctx;
	}

	/// Strict PERMIT, the PbServiceFacade.requireBookUpdate shape - not canHeal's creator fallback: a write into
	/// the world's Locations group as the olio principal must be backed by a real update grant on the book.
	/// The book is re-read by objectId as the user first, so the world written into is the persisted one and
	/// never a world FK carried on a caller-supplied record.
	private static BaseRecord requireBookUpdate(BaseRecord user, BaseRecord book) {
		if(user == null || book == null) {
			throw new PictureBookException(401, "No user");
		}
		Long orgId = book.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord fresh = (orgId != null ? PbBookUtil.readBook(user, oid(book), orgId.longValue()) : null);
		if(fresh == null) {
			throw new PictureBookException(404, "Book not found");
		}
		PolicyResponseType prr = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(user, user, fresh);
		if(prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
			throw new PictureBookException(403, "Not permitted to modify locations of book " + fresh.get(OlioFieldNames.FIELD_PB_SLUG));
		}
		return fresh;
	}

	private static BaseRecord olioUser(long orgId) {
		BaseRecord olioUser = IOSystem.getActiveContext().getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			throw new PictureBookException(500, "No olio principal in organization " + orgId);
		}
		return olioUser;
	}

	// ─────────────────────────────── find ───────────────────────────────

	/**
	 * The one {@code olio.pb.location} in the world's {@code Locations} group whose {@code name} or any
	 * {@code alias} equals {@code name} (trimmed, case-insensitive), or null. Find-only, as {@code actor},
	 * through {@code AccessPoint.list}; an actor who cannot read the group simply gets null.
	 */
	public static BaseRecord findByNameOrAlias(BaseRecord actor, BookContext bctx, String name) {
		if(actor == null || bctx == null || name == null || name.trim().isEmpty()) {
			return null;
		}
		String needle = name.trim();
		for(BaseRecord loc : listLocations(actor, bctx)) {
			if(matches(loc, needle)) {
				return loc;
			}
		}
		return null;
	}

	/** Every {@code olio.pb.location} of the world, projected with {@link PbBookUtil#locationRequest()}, uncached. */
	public static List<BaseRecord> listLocations(BaseRecord actor, BookContext bctx) {
		if(actor == null || bctx == null) {
			return new ArrayList<>();
		}
		BaseRecord grp = bctx.getGroup(FieldNames.FIELD_LOCATIONS);
		if(grp == null) {
			return new ArrayList<>();
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION, FieldNames.FIELD_GROUP_ID, (long) grp.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, bctx.getOrganizationId());
		q.setRequest(PbBookUtil.locationRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(actor, q).getResults();
		if(recs == null) {
			return new ArrayList<>();
		}
		return PbGraphUtil.restoreSchema(new ArrayList<>(Arrays.asList(recs)), OlioModelNames.MODEL_PB_LOCATION);
	}

	static boolean matches(BaseRecord loc, String needle) {
		String n = loc.get(FieldNames.FIELD_NAME);
		if(n != null && n.trim().equalsIgnoreCase(needle)) {
			return true;
		}
		List<String> aliases = loc.get(OlioFieldNames.FIELD_PB_ALIASES);
		if(aliases != null) {
			for(String a : aliases) {
				if(a != null && a.trim().equalsIgnoreCase(needle)) {
					return true;
				}
			}
		}
		return false;
	}

	// ─────────────────────────────── resolve ───────────────────────────────

	/**
	 * Resolve {@code name} to one location in the book's world, creating it (and its POI + store) when no
	 * location carries that name or alias. A hit is returned as it is - no description/kind/alias repair on an
	 * existing record, so a second mention never overwrites what the first one (or the user) established.
	 *
	 * @return the location as read by {@code user} after the write
	 * @throws PictureBookException 400 no name / over-long name or description, 401/403/404 not permitted,
	 *         409 no world, 500 persist failure
	 */
	public static BaseRecord getCreateLocation(BaseRecord user, BaseRecord book, String name, String description,
			PbLocationKindEnumType kind, List<String> aliases) {
		String cleanName = cleanText(name);
		if(cleanName == null || cleanName.isEmpty()) {
			throw new PictureBookException(400, "A location needs a name");
		}
		if(cleanName.length() > 256) {
			throw new PictureBookException(400, "Location name exceeds 256 characters");
		}
		if(description != null && description.trim().length() > MAX_DESCRIPTION_LENGTH) {
			throw new PictureBookException(400, "Location description exceeds " + MAX_DESCRIPTION_LENGTH + " characters");
		}
		BaseRecord freshBook = requireBookUpdate(user, book);
		BookContext bctx = requireBookContext(user, freshBook);
		long orgId = bctx.getOrganizationId();
		BaseRecord olioUser = olioUser(orgId);

		/// Dedupe as the principal that owns the group, so a user-side read restriction cannot produce a duplicate
		BaseRecord existing = findByNameOrAlias(olioUser, bctx, cleanName);
		if(existing != null) {
			return rereadAs(user, existing.get(FieldNames.FIELD_OBJECT_ID), orgId);
		}

		String groupPath = bctx.getGroupPath(FieldNames.FIELD_LOCATIONS);
		if(groupPath == null) {
			throw new PictureBookException(500, "The book world does not expose its Locations group");
		}
		BaseRecord loc;
		try {
			loc = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(olioUser, loc, cleanName, groupPath, orgId);
			/// The model declares name itself (no common.name), so applyNameGroupOwnership leaves it unset
			loc.set(FieldNames.FIELD_NAME, cleanName);
			if(NarrativeUtil.isMeaningful(description)) {
				loc.set(FieldNames.FIELD_DESCRIPTION, description.trim());
			}
			if(kind != null && kind != PbLocationKindEnumType.UNKNOWN) {
				loc.set(OlioFieldNames.FIELD_PB_KIND, kind);
			}
			List<String> al = loc.get(OlioFieldNames.FIELD_PB_ALIASES);
			al.addAll(normalizeAliases(aliases, cleanName, null));
			loc.set(OlioFieldNames.FIELD_PB_WORLD, bctx.getWorld());
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("getCreateLocation: could not assemble olio.pb.location: " + e.getMessage());
			throw new PictureBookException(500, "Failed to assemble the location record");
		}
		boolean ok = IOSystem.getActiveContext().getRecordUtil().createRecord(loc);
		Long id = loc.get(FieldNames.FIELD_ID);
		if(!ok || id == null || id.longValue() <= 0L) {
			logger.error("getCreateLocation: olio.pb.location was not persisted in " + groupPath);
			throw new PictureBookException(500, "Failed to persist the location record");
		}
		linkPoi(olioUser, bctx.getWorld(), loc);
		return rereadAs(user, loc.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	private static BaseRecord rereadAs(BaseRecord user, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.locationRequest());
		q.setCache(false);
		BaseRecord r = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		if(r == null) {
			logger.warn("rereadAs: location " + objectId + " is not readable by " + user.get(FieldNames.FIELD_NAME));
			throw new PictureBookException(403, "Location is not readable");
		}
		return r;
	}

	/** The location as {@code actor} reads it ({@link PbBookUtil#locationRequest()}, uncached), or null when absent or unreadable. */
	public static BaseRecord readLocation(BaseRecord actor, String objectId, long orgId) {
		return readAs(actor, OlioModelNames.MODEL_PB_LOCATION, objectId, orgId, PbBookUtil.locationRequest());
	}

	/** The variant as {@code actor} reads it ({@link PbBookUtil#locationVariantRequest()}, uncached), or null when absent or unreadable. */
	public static BaseRecord readVariant(BaseRecord actor, String objectId, long orgId) {
		return readAs(actor, OlioModelNames.MODEL_PB_LOCATION_VARIANT, objectId, orgId, PbBookUtil.locationVariantRequest());
	}

	/**
	 * The location {@code objectId} read as {@code user}, refused with 404 unless its {@code world} is the
	 * book's world (P3-4; §6.C 170 m i). A location of another world in the organization is "not found"
	 * here, never "exists elsewhere": {@code AccessPoint.update} gates a foreign FK only on READ of the
	 * target, so every route that binds, renders, approves or edits a location through a book must make
	 * the same-world check itself. {@code book} must carry its {@code world} FK ({@link PbBookUtil#bookRequest()}).
	 */
	public static BaseRecord requireLocationOfBook(BaseRecord user, BaseRecord book, String objectId) {
		if(book == null) {
			throw new PictureBookException(400, "A book is required");
		}
		long orgId = PbGraphUtil.orgId(book);
		BaseRecord loc = readLocation(user, objectId, orgId);
		if(loc == null || !sameWorld(book, loc)) {
			throw new PictureBookException(404, "Location not found in this book's world");
		}
		return loc;
	}

	/**
	 * The variant {@code objectId} read as {@code user}, 404 unless its {@code location} is a location of the
	 * book's world; the located parent is returned through {@code out[0]} when {@code out} is non-null.
	 */
	public static BaseRecord requireVariantOfBook(BaseRecord user, BaseRecord book, String objectId, BaseRecord[] out) {
		if(book == null) {
			throw new PictureBookException(400, "A book is required");
		}
		long orgId = PbGraphUtil.orgId(book);
		BaseRecord v = readVariant(user, objectId, orgId);
		BaseRecord locRef = (v != null ? v.get(FieldNames.FIELD_LOCATION) : null);
		BaseRecord loc = (locRef != null ? readLocation(user, oid(locRef), orgId) : null);
		if(v == null || loc == null || !sameWorld(book, loc)) {
			throw new PictureBookException(404, "Location variant not found in this book's world");
		}
		if(out != null && out.length > 0) {
			out[0] = loc;
		}
		return v;
	}

	/** True when {@code loc.world} is {@code book.world} (both by id; a missing FK on either side is false). */
	public static boolean sameWorld(BaseRecord book, BaseRecord loc) {
		long bookWorld = PbHealthUtil.fkId(book, OlioFieldNames.FIELD_PB_WORLD);
		long locWorld = PbHealthUtil.fkId(loc, OlioFieldNames.FIELD_PB_WORLD);
		return bookWorld > 0L && bookWorld == locWorld;
	}

	// ─────────────────────────────── approved render + lock (P3-4; §7, §10) ───────────────────────────────

	/**
	 * The world asset a {@code LOCATION} node renders: the location, and the variant when the node's scope is
	 * {@code locationVariant}. {@link #target()} is the record that carries the node's {@code approvedRender}.
	 * A variant has no {@code locked} field of its own; {@link #isLocked()} is always the parent location's flag.
	 */
	public static final class NodeSubject {
		public final BaseRecord location;
		public final BaseRecord variant;

		NodeSubject(BaseRecord location, BaseRecord variant) {
			this.location = location;
			this.variant = variant;
		}

		public BaseRecord target() {
			return (variant != null ? variant : location);
		}

		public String targetModel() {
			return (variant != null ? OlioModelNames.MODEL_PB_LOCATION_VARIANT : OlioModelNames.MODEL_PB_LOCATION);
		}

		public boolean isLocked() {
			return PbLocationUtil.isLocked(location);
		}

		public String label() {
			return "location " + location.get(FieldNames.FIELD_NAME)
				+ (variant != null ? " / " + variant.get(FieldNames.FIELD_NAME) : "");
		}
	}

	/**
	 * Resolve the location (and variant) a node renders, from {@code scope} / {@code scopeRef}, read as
	 * {@code actor} ({@link PbGraphUtil#nodeRequest()} projects both). Null for any node that is not a
	 * {@code LOCATION} node, so callers can use it as the "is this a location artifact" test.
	 *
	 * @throws PictureBookException 400 when the node carries no scopeRef or an unexpected scope, 404 when
	 *         the record (or a variant's parent) is absent or unreadable
	 */
	public static NodeSubject subjectOf(BaseRecord actor, BaseRecord node) {
		if(node == null || !node.hasField(OlioFieldNames.FIELD_PB_NODE_TYPE)) {
			return null;
		}
		PbNodeTypeEnumType nodeType = node.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE);
		if(nodeType != PbNodeTypeEnumType.LOCATION) {
			return null;
		}
		String handle = node.get(OlioFieldNames.FIELD_PB_HANDLE);
		String scope = node.get(OlioFieldNames.FIELD_PB_SCOPE);
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Location node " + handle + " carries no scopeRef (location or variant objectId)");
		}
		long orgId = PbGraphUtil.orgId(node);
		if(PbPipelineUtil.SCOPE_LOCATION_VARIANT.equals(scope)) {
			BaseRecord variant = readVariant(actor, scopeRef.trim(), orgId);
			BaseRecord locRef = (variant != null ? variant.get(FieldNames.FIELD_LOCATION) : null);
			BaseRecord location = (locRef != null ? readLocation(actor, oid(locRef), orgId) : null);
			if(variant == null || location == null) {
				throw new PictureBookException(404, "Location variant " + scopeRef + " of node " + handle + " was not found");
			}
			return new NodeSubject(location, variant);
		}
		if(PbPipelineUtil.SCOPE_LOCATION.equals(scope)) {
			BaseRecord location = readLocation(actor, scopeRef.trim(), orgId);
			if(location == null) {
				throw new PictureBookException(404, "Location " + scopeRef + " of node " + handle + " was not found");
			}
			return new NodeSubject(location, null);
		}
		throw new PictureBookException(400, "Location node " + handle + " has scope '" + scope + "'; expected "
			+ PbPipelineUtil.SCOPE_LOCATION + " or " + PbPipelineUtil.SCOPE_LOCATION_VARIANT);
	}

	/**
	 * Does the record a LOCATION node's {@code scopeRef} names still exist? Read as the olio principal, which owns
	 * every world sub-record, so the answer does not depend on the caller's entitlements: {@link #subjectOf} 404s
	 * both for a record that is gone (deleted, merged away) and for one {@code actor} may not read, and only the
	 * first has nothing left to protect. False for a non-LOCATION node or a blank scopeRef.
	 */
	public static boolean subjectExists(BaseRecord node) {
		if(node == null || !node.hasField(OlioFieldNames.FIELD_PB_NODE_TYPE) || node.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE) != PbNodeTypeEnumType.LOCATION) {
			return false;
		}
		String scope = node.get(OlioFieldNames.FIELD_PB_SCOPE);
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			return false;
		}
		long orgId = PbGraphUtil.orgId(node);
		String model = (PbPipelineUtil.SCOPE_LOCATION_VARIANT.equals(scope) ? OlioModelNames.MODEL_PB_LOCATION_VARIANT : OlioModelNames.MODEL_PB_LOCATION);
		String[] identity = new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_ORGANIZATION_ID};
		return readAs(olioUser(orgId), model, scopeRef.trim(), orgId, identity) != null;
	}

	/** {@code locked == true} on the location record (absent field or null reads as unlocked). */
	public static boolean isLocked(BaseRecord location) {
		if(location == null || !location.hasField(OlioFieldNames.FIELD_PB_LOCKED)) {
			return false;
		}
		Object v = location.get(OlioFieldNames.FIELD_PB_LOCKED);
		return Boolean.TRUE.equals(v);
	}

	/**
	 * 409 when the location is locked. The lock covers the whole asset (§7 "approve → lock"): description /
	 * kind / alias edits, promoting or clearing {@code approvedRender} on the location or any of its variants,
	 * new renders of either, new variants, and delete. It is enforced here and in {@link PbArtifactUtil}
	 * only - the flag is a plain boolean that generic {@code PATCH /rest/model} never consults (§6.C 174).
	 */
	public static void requireUnlocked(BaseRecord location, String what) {
		if(isLocked(location)) {
			throw new PictureBookException(409, "Location " + location.get(FieldNames.FIELD_NAME) + " is locked; unlock it to " + what);
		}
	}

	/**
	 * Point {@code subject.target().approvedRender} at {@code artifact}, or clear it when {@code artifact} is null,
	 * as the olio principal (the record's owner), then {@link PbStaleUtil#touch} the target so every graph bound
	 * to it is rescanned. Idempotent: no write when the FK already has that value. Clearing relies on the
	 * present-but-null field of a {@link PbGraphUtil#patchOf} patch writing SQL NULL (§6.C 170 f).
	 * <p>
	 * No lock check here: callers decide (approve/reject refuse 409 before any write; delete clears as part
	 * of tearing the book down). {@code user} is the gate the caller already passed; it is only logged.
	 *
	 * @return true when the FK was changed by this call
	 * @throws PictureBookException 500 when the write is refused
	 */
	public static boolean writeApprovedRender(BaseRecord user, NodeSubject subject, BaseRecord artifact) {
		if(subject == null || subject.target() == null) {
			throw new PictureBookException(400, "A location (or variant) is required");
		}
		BaseRecord target = subject.target();
		long current = PbHealthUtil.fkId(target, OlioFieldNames.FIELD_PB_APPROVED_RENDER);
		long wanted = PbHealthUtil.idOf(artifact);
		if(current == wanted) {
			return false;
		}
		long orgId = PbGraphUtil.orgId(target);
		BaseRecord olioUser = olioUser(orgId);
		try {
			BaseRecord patch = PbGraphUtil.patchOf(target, subject.targetModel(), OlioFieldNames.FIELD_PB_APPROVED_RENDER);
			if(artifact != null) {
				patch.set(OlioFieldNames.FIELD_PB_APPROVED_RENDER, artifact);
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(olioUser, patch) == null) {
				throw new PictureBookException(500, "approvedRender of " + subject.label() + " could not be written");
			}
			if(target.hasField(OlioFieldNames.FIELD_PB_APPROVED_RENDER)) {
				target.set(OlioFieldNames.FIELD_PB_APPROVED_RENDER, artifact);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("writeApprovedRender: " + e.getMessage());
			throw new PictureBookException(500, "approvedRender of " + subject.label() + " could not be written");
		}
		logger.info("writeApprovedRender: " + (user != null ? user.get(FieldNames.FIELD_NAME) : "?") + " "
			+ (artifact != null ? "approved " + artifact.get(FieldNames.FIELD_OBJECT_ID) + " for " : "cleared the approved render of ") + subject.label());
		PbStaleUtil.touch(target);
		return true;
	}

	// ─────────────────────────────── edit / lock / variants / delete (P3-4; §26) ───────────────────────────────

	/** Keys {@link #updateLocation} accepts; anything else is a 400 so a typo never becomes a silent no-op. */
	public static final Set<String> EDITABLE_FIELDS = Set.of(FieldNames.FIELD_DESCRIPTION, OlioFieldNames.FIELD_PB_KIND, OlioFieldNames.FIELD_PB_ALIASES);

	/**
	 * Edit a location's {@code description}, {@code kind} and/or {@code aliases} (the keys of
	 * {@link #EDITABLE_FIELDS}; {@code name} is not editable here - the name is the POI's name and the
	 * dedupe key, so a rename is a merge, not an edit). The patch carries only the keys present in
	 * {@code changes}; a present key with a null value clears that field. Written as the olio principal
	 * after the request user's update grant on the book is checked, then the location is touched so every
	 * graph that binds it re-evaluates staleness ({@link PbWatchedFields#PB_LOCATION}).
	 *
	 * @return the location as {@code user} reads it after the write
	 * @throws PictureBookException 400 unknown key / bad kind / over-long description, 401/403/404 not permitted
	 *         or not in this book's world, 409 locked, 500 persist failure
	 */
	public static BaseRecord updateLocation(BaseRecord user, BaseRecord book, String locationObjectId, Map<String, Object> changes) {
		if(changes == null || changes.isEmpty()) {
			throw new PictureBookException(400, "Nothing to change");
		}
		for(String k : changes.keySet()) {
			if(!EDITABLE_FIELDS.contains(k)) {
				throw new PictureBookException(400, "A field in the patch is not editable; editable fields are " + EDITABLE_FIELDS);
			}
		}
		BaseRecord freshBook = requireBookUpdate(user, book);
		BaseRecord loc = requireLocationOfBook(user, freshBook, locationObjectId);
		requireUnlocked(loc, "edit it");
		long orgId = PbGraphUtil.orgId(loc);
		try {
			BaseRecord patch = PbGraphUtil.patchOf(loc, OlioModelNames.MODEL_PB_LOCATION, changes.keySet().toArray(new String[0]));
			if(changes.containsKey(FieldNames.FIELD_DESCRIPTION)) {
				Object d = changes.get(FieldNames.FIELD_DESCRIPTION);
				String desc = (d != null ? String.valueOf(d).trim() : null);
				if(desc != null && desc.length() > MAX_DESCRIPTION_LENGTH) {
					throw new PictureBookException(400, "Location description exceeds " + MAX_DESCRIPTION_LENGTH + " characters");
				}
				if(desc != null && !desc.isEmpty()) {
					patch.set(FieldNames.FIELD_DESCRIPTION, desc);
				}
			}
			if(changes.containsKey(OlioFieldNames.FIELD_PB_KIND)) {
				PbLocationKindEnumType kind = parseKind(changes.get(OlioFieldNames.FIELD_PB_KIND));
				patch.set(OlioFieldNames.FIELD_PB_KIND, kind);
			}
			if(changes.containsKey(OlioFieldNames.FIELD_PB_ALIASES)) {
				Object a = changes.get(OlioFieldNames.FIELD_PB_ALIASES);
				if(a != null && !(a instanceof List)) {
					throw new PictureBookException(400, "aliases must be a list of strings");
				}
				List<String> in = new ArrayList<>();
				if(a != null) {
					for(Object o : (List<?>) a) {
						if(o != null) {
							in.add(String.valueOf(o));
						}
					}
				}
				List<String> al = patch.get(OlioFieldNames.FIELD_PB_ALIASES);
				al.clear();
				al.addAll(normalizeAliases(in, loc.get(FieldNames.FIELD_NAME), null));
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(olioUser(orgId), patch) == null) {
				throw new PictureBookException(500, "Location " + loc.get(FieldNames.FIELD_NAME) + " could not be written");
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("updateLocation: " + e.getMessage());
			throw new PictureBookException(500, "Location " + loc.get(FieldNames.FIELD_NAME) + " could not be written");
		}
		PbStaleUtil.touch(loc);
		return rereadAs(user, locationObjectId, orgId);
	}

	/** Case-insensitive parse of a wire value ("interior", "INTERIOR") to the kind enum; null or empty → UNKNOWN; anything else 400. */
	static PbLocationKindEnumType parseKind(Object value) {
		if(value == null) {
			return PbLocationKindEnumType.UNKNOWN;
		}
		if(value instanceof PbLocationKindEnumType) {
			return (PbLocationKindEnumType) value;
		}
		String s = String.valueOf(value).trim();
		if(s.isEmpty()) {
			return PbLocationKindEnumType.UNKNOWN;
		}
		for(PbLocationKindEnumType k : PbLocationKindEnumType.values()) {
			if(k.name().equalsIgnoreCase(s)) {
				return k;
			}
		}
		throw new PictureBookException(400, "Unknown location kind; one of " + Arrays.toString(PbLocationKindEnumType.values()));
	}

	/**
	 * Set or clear the location's {@code locked} flag (whole-asset lock, see the model description and §6.C 174).
	 * Locking needs the same book update grant as every other write here; it is not itself refused by the
	 * lock, so a locked location can always be unlocked by someone who may update the book.
	 *
	 * @return the location as {@code user} reads it after the write
	 */
	public static BaseRecord setLocked(BaseRecord user, BaseRecord book, String locationObjectId, boolean locked) {
		BaseRecord freshBook = requireBookUpdate(user, book);
		BaseRecord loc = requireLocationOfBook(user, freshBook, locationObjectId);
		long orgId = PbGraphUtil.orgId(loc);
		if(isLocked(loc) == locked) {
			return loc;
		}
		try {
			BaseRecord patch = PbGraphUtil.patchOf(loc, OlioModelNames.MODEL_PB_LOCATION, OlioFieldNames.FIELD_PB_LOCKED);
			patch.set(OlioFieldNames.FIELD_PB_LOCKED, locked);
			if(IOSystem.getActiveContext().getAccessPoint().update(olioUser(orgId), patch) == null) {
				throw new PictureBookException(500, "Location " + loc.get(FieldNames.FIELD_NAME) + " could not be " + (locked ? "locked" : "unlocked"));
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("setLocked: " + e.getMessage());
			throw new PictureBookException(500, "Location " + loc.get(FieldNames.FIELD_NAME) + " could not be " + (locked ? "locked" : "unlocked"));
		}
		logger.info("setLocked: " + user.get(FieldNames.FIELD_NAME) + (locked ? " locked " : " unlocked ") + "location " + loc.get(FieldNames.FIELD_NAME));
		return rereadAs(user, locationObjectId, orgId);
	}

	/**
	 * Create a variant of a location (§7: the same place at another time of day / weather / season / viewpoint).
	 * The variant's unique key is {@code name, groupId, organizationId}, so {@code name} defaults to
	 * {@code <location name> - <deltas>} (§6.C 170 k) when the caller gives none. Every delta is optional; a
	 * variant with no delta at all is refused (400) because the location itself is that variant.
	 *
	 * @return the variant as {@code user} reads it after the write
	 * @throws PictureBookException 400 no delta / over-long values, 401/403/404 not permitted or not in this
	 *         book's world, 409 locked or a variant of that name exists, 500 persist failure
	 */
	public static BaseRecord createVariant(BaseRecord user, BaseRecord book, String locationObjectId, String name,
			String timeOfDay, String weather, String season, String viewpoint, String extra) {
		BaseRecord freshBook = requireBookUpdate(user, book);
		BookContext bctx = requireBookContext(user, freshBook);
		BaseRecord loc = requireLocationOfBook(user, freshBook, locationObjectId);
		requireUnlocked(loc, "add a variant");
		long orgId = bctx.getOrganizationId();
		BaseRecord olioUser = olioUser(orgId);

		String tod = clipDelta(timeOfDay, 128, OlioFieldNames.FIELD_PB_TIME_OF_DAY);
		String wx = clipDelta(weather, 128, OlioFieldNames.FIELD_PB_WEATHER);
		String sn = clipDelta(season, 128, OlioFieldNames.FIELD_PB_SEASON);
		String vp = clipDelta(viewpoint, 256, OlioFieldNames.FIELD_PB_VIEWPOINT);
		String ex = (NarrativeUtil.isMeaningful(extra) ? cleanText(extra) : null);
		if(ex != null && ex.length() > MAX_DESCRIPTION_LENGTH) {
			throw new PictureBookException(400, "Variant extra text exceeds " + MAX_DESCRIPTION_LENGTH + " characters");
		}
		List<String> deltas = new ArrayList<>();
		for(String d : new String[] {tod, wx, sn, vp, ex}) {
			if(d != null) {
				deltas.add(d);
			}
		}
		if(deltas.isEmpty()) {
			throw new PictureBookException(400, "A variant needs at least one of timeOfDay, weather, season, viewpoint or extra");
		}
		String locName = loc.get(FieldNames.FIELD_NAME);
		String cleanName = (NarrativeUtil.isMeaningful(name) ? cleanText(name) : locName + " - " + String.join(", ", deltas));
		if(cleanName.length() > 256) {
			cleanName = cleanName.substring(0, 256).trim();
		}
		List<BaseRecord> existingVariants = variantsOf(loc, orgId);
		for(BaseRecord v : existingVariants) {
			String vn = v.get(FieldNames.FIELD_NAME);
			if(vn != null && vn.equalsIgnoreCase(cleanName)) {
				throw new PictureBookException(409, "Location " + locName + " already has a variant named '" + cleanName + "'");
			}
		}
		if(existingVariants.size() >= MAX_VARIANTS_PER_LOCATION) {
			throw new PictureBookException(409, "Location " + locName + " already has " + existingVariants.size()
				+ " variants; the limit is " + MAX_VARIANTS_PER_LOCATION);
		}
		String groupPath = bctx.getGroupPath(FieldNames.FIELD_LOCATIONS);
		if(groupPath == null) {
			throw new PictureBookException(500, "The book world does not expose its Locations group");
		}
		BaseRecord variant;
		try {
			variant = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION_VARIANT);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(olioUser, variant, cleanName, groupPath, orgId);
			variant.set(FieldNames.FIELD_NAME, cleanName);
			variant.set(FieldNames.FIELD_LOCATION, loc);
			if(tod != null) variant.set(OlioFieldNames.FIELD_PB_TIME_OF_DAY, tod);
			if(wx != null) variant.set(OlioFieldNames.FIELD_PB_WEATHER, wx);
			if(sn != null) variant.set(OlioFieldNames.FIELD_PB_SEASON, sn);
			if(vp != null) variant.set(OlioFieldNames.FIELD_PB_VIEWPOINT, vp);
			if(ex != null) variant.set(OlioFieldNames.FIELD_PB_EXTRA, ex);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("createVariant: could not assemble olio.pb.locationVariant: " + e.getMessage());
			throw new PictureBookException(500, "Failed to assemble the variant record");
		}
		boolean ok = IOSystem.getActiveContext().getRecordUtil().createRecord(variant);
		if(!ok || PbHealthUtil.idOf(variant) <= 0L) {
			logger.error("createVariant: olio.pb.locationVariant was not persisted in " + groupPath);
			throw new PictureBookException(500, "Failed to persist the variant record");
		}
		BaseRecord out = readVariant(user, variant.get(FieldNames.FIELD_OBJECT_ID), orgId);
		if(out == null) {
			throw new PictureBookException(403, "Variant is not readable");
		}
		return out;
	}

	private static String clipDelta(String value, int max, String field) {
		if(!NarrativeUtil.isMeaningful(value)) {
			return null;
		}
		String v = cleanText(value);
		if(v.length() > max) {
			throw new PictureBookException(400, "Variant " + field + " exceeds " + max + " characters");
		}
		return v;
	}

	/** This book's scenes (as {@code user} lists them) whose {@code location} is {@code loc}, in scene order. */
	public static List<BaseRecord> scenesOfBookReferencing(BaseRecord user, BaseRecord book, BaseRecord loc) {
		return scenesOfBookReferencing(user, book, loc, List.of());
	}

	/** As above, also counting scenes whose {@code locationVariant} is one of {@code variants}. */
	static List<BaseRecord> scenesOfBookReferencing(BaseRecord user, BaseRecord book, BaseRecord loc, List<BaseRecord> variants) {
		List<BaseRecord> out = new ArrayList<>();
		long locId = PbHealthUtil.idOf(loc);
		if(locId <= 0L) {
			return out;
		}
		Set<Long> variantIds = new HashSet<>();
		for(BaseRecord v : variants) {
			variantIds.add(Long.valueOf(PbHealthUtil.idOf(v)));
		}
		for(BaseRecord scene : PbBookUtil.listScenes(user, book)) {
			if(PbHealthUtil.fkId(scene, FieldNames.FIELD_LOCATION) == locId
					|| variantIds.contains(Long.valueOf(PbHealthUtil.fkId(scene, OlioFieldNames.FIELD_PB_LOCATION_VARIANT)))) {
				out.add(scene);
			}
		}
		return out;
	}

	/**
	 * Delete a location of the book's world together with its variants, its map-free POI + store (book worlds
	 * only, and only when nothing else references them) and <b>this book's</b> {@code LOCATION} nodes for it
	 * (bindings, artifacts, node). Refused while any scene in the organization still references the location or
	 * one of its variants: un-assign them first. Nodes of other chapters in a shared series world are left alone
	 * (no cross-book writes from a single-book route); their {@code scopeRef} then dangles, which is a health
	 * finding for that chapter, never a failed scene.
	 *
	 * @return the delete steps
	 * @throws PictureBookException 401/403/404 not permitted or not in this book's world, 409 locked or still
	 *         referenced, 500 persist failure
	 */
	public static DeleteResult deleteLocation(BaseRecord user, BaseRecord book, String locationObjectId) {
		BaseRecord freshBook = requireBookUpdate(user, book);
		BookContext bctx = requireBookContext(user, freshBook);
		BaseRecord loc = requireLocationOfBook(user, freshBook, locationObjectId);
		requireUnlocked(loc, "delete it");
		long orgId = bctx.getOrganizationId();
		BaseRecord olioUser = olioUser(orgId);
		String locName = loc.get(FieldNames.FIELD_NAME);

		List<BaseRecord> variants = variantsOf(loc, orgId);
		int referencing = scenesReferencing(loc, orgId).size();
		for(BaseRecord v : variants) {
			referencing += scenesReferencingVariant(v, orgId).size();
		}
		if(referencing > 0) {
			/// The gate is world-wide, the count in the message is this book's only: another chapter's scene count
			/// is not this caller's to read.
			int inBook = scenesOfBookReferencing(user, freshBook, loc, variants).size();
			if(inBook > 0) {
				throw new PictureBookException(409, "Location " + locName + " is still the location of " + inBook
					+ " scene(s); un-assign them before deleting it");
			}
			throw new PictureBookException(409, "Location " + locName + " is still the location of a scene in another chapter; un-assign it there before deleting it");
		}

		DeleteResult res = DeleteResult.ok();
		/// 1. this book's LOCATION nodes for the location and its variants, with their bindings and artifacts
		Set<String> refs = new LinkedHashSet<>();
		refs.add(locationObjectId);
		for(BaseRecord v : variants) {
			refs.add(oid(v));
		}
		deleteLocationNodes(user, freshBook, refs, orgId, res);
		/// 2. variants
		int vn = 0;
		for(BaseRecord v : variants) {
			DeleteResult vr = PictureBookUtil.deleteRecordExplained(olioUser, v);
			if(vr.deleted) {
				vn++;
				PbStaleUtil.touch(v);
			}
			else {
				res.deleted = false;
				res.reason = (res.reason != null ? res.reason + "; " : "") + "variant " + v.get(FieldNames.FIELD_NAME) + ": " + vr.reason;
			}
		}
		res.step("variants", OlioModelNames.MODEL_PB_LOCATION_VARIANT, vn, vn == variants.size(), vn == variants.size() ? null : (variants.size() - vn) + " variant(s) not deleted");
		if(vn != variants.size()) {
			throw new PictureBookException(500, "Location " + locName + " could not be deleted: " + res.reason);
		}
		/// 3. the map-free POI + store (D4 guard inside)
		BaseRecord poiRef = loc.get(OlioFieldNames.FIELD_PB_POI);
		if(poiRef != null && PbHealthUtil.idOf(poiRef) > 0L && isBookWorld(olioUser, bctx.getWorld())) {
			deleteOrphanPoi(olioUser, bctx.getWorld(), loc, poiRef, orgId);
			res.step("poi", OlioModelNames.MODEL_POI, poiExists(poiRef, orgId) ? 0 : 1, true, null);
		}
		/// 4. the location
		DeleteResult lr = PictureBookUtil.deleteRecordExplained(olioUser, loc);
		res.step("location", OlioModelNames.MODEL_PB_LOCATION, lr.deleted ? 1 : 0, lr.deleted, lr.deleted ? null : lr.reason);
		if(!lr.deleted) {
			throw new PictureBookException(500, "Location " + locName + " could not be deleted: " + lr.reason);
		}
		PbStaleUtil.touch(loc);
		logger.info("deleteLocation: " + user.get(FieldNames.FIELD_NAME) + " deleted location " + locName + " (" + variants.size() + " variant(s))");
		return res;
	}

	/**
	 * Remove this book's LOCATION nodes whose {@code scopeRef} is one of {@code refs} (location or variant
	 * objectIds), with their bindings and artifacts, as {@code user}. Only this book's workflow: another
	 * chapter's graph in a shared series world is outside this user's reach and surfaces through health as a
	 * dangling {@code scopeRef}. Recorded on {@code res} as the {@code locationNodes} step.
	 *
	 * @return the number of nodes removed
	 */
	private static int deleteLocationNodes(BaseRecord user, BaseRecord book, Set<String> refs, long orgId, DeleteResult res) {
		BaseRecord workflow = PbGraphUtil.findWorkflow(user, book);
		if(workflow == null) {
			return 0;
		}
		int nodes = 0;
		for(BaseRecord node : PbGraphUtil.listNodes(user, workflow)) {
			if(!node.hasField(OlioFieldNames.FIELD_PB_NODE_TYPE) || node.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE) != PbNodeTypeEnumType.LOCATION) {
				continue;
			}
			String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
			if(scopeRef == null || !refs.contains(scopeRef.trim())) {
				continue;
			}
			PbDeleteUtil.deleteNodeGraph(user, node, orgId, res);
			nodes++;
		}
		res.step("locationNodes", OlioModelNames.MODEL_PB_NODE, nodes, true, null);
		if(nodes > 0) {
			PbStaleUtil.invalidate(PbHealthUtil.idOf(workflow));
		}
		return nodes;
	}

	/** Scenes (any book in the organization) whose {@code locationVariant} is {@code variant}. */
	static List<BaseRecord> scenesReferencingVariant(BaseRecord variant, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_LOCATION_VARIANT, variant);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.sceneRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
		return (recs != null ? PbGraphUtil.restoreSchema(new ArrayList<>(Arrays.asList(recs)), OlioModelNames.MODEL_PB_SCENE) : new ArrayList<>());
	}

	// ─────────────────────────────── POI link ───────────────────────────────

	/**
	 * §7.2 rules 1 and 2. Never throws: a location failure must not fail the scene (or the heal) that asked
	 * for it. Returns true when {@code location.poi} is set on exit.
	 */
	public static boolean linkPoi(BaseRecord olioUser, BaseRecord world, BaseRecord location) {
		if(olioUser == null || world == null || location == null) {
			return false;
		}
		try {
			BaseRecord current = location.get(OlioFieldNames.FIELD_PB_POI);
			if(poiExists(current, PbGraphUtil.orgId(location))) {
				return true;
			}
			String name = location.get(FieldNames.FIELD_NAME);
			if(name == null || name.trim().isEmpty()) {
				logger.warn("linkPoi: location " + location.get(FieldNames.FIELD_OBJECT_ID) + " has no name; nothing to link");
				return false;
			}
			BaseRecord poi;
			if(isBookWorld(olioUser, world)) {
				poi = createMapFreePoi(olioUser, world, location, name.trim());
			}
			else {
				List<BaseRecord> hits = PointOfInterestUtil.findByName(world, name.trim());
				if(hits.size() != 1) {
					logger.info("linkPoi: " + hits.size() + " points of interest named '" + name.trim() + "' in game world "
						+ world.get(FieldNames.FIELD_NAME) + "; leaving location " + location.get(FieldNames.FIELD_OBJECT_ID) + " unlinked");
					return false;
				}
				poi = hits.get(0);
			}
			if(poi == null) {
				return false;
			}
			BaseRecord patch = PbGraphUtil.patchOf(location, OlioModelNames.MODEL_PB_LOCATION, OlioFieldNames.FIELD_PB_POI);
			patch.set(OlioFieldNames.FIELD_PB_POI, poi);
			if(IOSystem.getActiveContext().getAccessPoint().update(olioUser, patch) == null) {
				logger.error("linkPoi: poi patch on location " + location.get(FieldNames.FIELD_OBJECT_ID) + " was not persisted");
				return false;
			}
			location.set(OlioFieldNames.FIELD_PB_POI, poi);
			return true;
		}
		catch(FieldException | ValueException | ModelNotFoundException | PictureBookException e) {
			logger.error("linkPoi: " + e.getMessage());
			return false;
		}
	}

	/// A deleted FK target can read back as an id-only stub (see PbHealthUtil SCENE_NODE_DANGLING), so a link
	/// is "present" only when the row still exists.
	static boolean poiExists(BaseRecord poiRef, long orgId) {
		if(poiRef == null || PbHealthUtil.idOf(poiRef) <= 0L) {
			return false;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_ID, PbHealthUtil.idOf(poiRef));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q) != null;
	}

	/// Rule 1. Store first, then POI: the writer would auto-create a nested identity-less store anyway
	/// (DBWriter.applyAutoCreateList), but writing it explicitly keeps the order deterministic and gives a
	/// checkable id before the POI row exists.
	private static BaseRecord createMapFreePoi(BaseRecord olioUser, BaseRecord world, BaseRecord location, String name)
			throws FieldException, ValueException, ModelNotFoundException {
		String poiName = (name.length() > MAX_POI_NAME_LENGTH ? name.substring(0, MAX_POI_NAME_LENGTH).trim() : name);
		PbLocationKindEnumType kind = (location.hasField(OlioFieldNames.FIELD_PB_KIND) ? location.getEnum(OlioFieldNames.FIELD_PB_KIND) : null);
		BaseRecord poi = PointOfInterestUtil.newPointOfInterest(olioUser, world, poiTypeFor(kind, poiName, location.get(FieldNames.FIELD_DESCRIPTION)), poiName);
		if(poi == null) {
			throw new PictureBookException(500, "Could not instantiate a point of interest for '" + name + "'");
		}
		poi.set(OlioFieldNames.FIELD_CONCEALED, false);
		BaseRecord store = poi.get(FieldNames.FIELD_STORE);
		if(store == null) {
			throw new PictureBookException(500, "Point of interest '" + name + "' carries no store");
		}
		IOContext ioContext = IOSystem.getActiveContext();
		if(!ioContext.getRecordUtil().createRecord(store) || PbHealthUtil.idOf(store) <= 0L) {
			throw new PictureBookException(500, "Failed to persist the store for '" + name + "'");
		}
		if(!ioContext.getRecordUtil().createRecord(poi) || PbHealthUtil.idOf(poi) <= 0L) {
			throw new PictureBookException(500, "Failed to persist the point of interest for '" + name + "'");
		}
		return poi;
	}

	/** §7.2 rule 1: the POI type a location kind maps onto, refined by the words of its name and description. */
	static PointOfInterestEnumType poiTypeFor(PbLocationKindEnumType kind, String name, String description) {
		String text = (name != null ? name : "") + " " + (description != null ? description : "");
		if(kind == null) {
			return PointOfInterestEnumType.UNKNOWN;
		}
		switch(kind) {
			case INTERIOR:
				return COMMERCIAL.matcher(text).find() ? PointOfInterestEnumType.COMMERCIAL : PointOfInterestEnumType.STRUCTURE;
			case EXTERIOR:
			case MIXED:
				if(RUIN.matcher(text).find()) {
					return PointOfInterestEnumType.RUIN;
				}
				if(LANDMARK.matcher(text).find()) {
					return PointOfInterestEnumType.LANDMARK;
				}
				return PointOfInterestEnumType.STRUCTURE;
			default:
				return PointOfInterestEnumType.UNKNOWN;
		}
	}

	/**
	 * A book world is any world whose group is {@code /Olio/Universes/Books/Worlds} - standalone book worlds and
	 * shared series worlds alike. Path only: a game world has a map, but "has a map" is a state, not an identity.
	 */
	public static boolean isBookWorld(BaseRecord olioUser, BaseRecord world) {
		if(olioUser == null || world == null) {
			return false;
		}
		Object gid = world.get(FieldNames.FIELD_GROUP_ID);
		if(!(gid instanceof Number) || ((Number) gid).longValue() <= 0L) {
			return false;
		}
		long orgId = ((Number) olioUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord worldDir = IOSystem.getActiveContext().getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP,
			PbOlioContextUtil.bookWorldPath(), GroupEnumType.DATA.toString(), orgId);
		return worldDir != null && ((Number) gid).longValue() == PbHealthUtil.idOf(worldDir);
	}

	// ─────────────────────────────── merge ───────────────────────────────

	/**
	 * Fold {@code duplicate} into {@code survivor}: the duplicate's name and aliases become aliases of the
	 * survivor, every scene and variant that pointed at the duplicate is re-pointed, this book's LOCATION node
	 * for the duplicate is removed with its candidates (never promoted), the duplicate's map-free POI + store
	 * are removed (book worlds only, and only when nothing else references them), and the duplicate is deleted.
	 * Scenes are re-pointed as {@code user} and the whole merge is refused before any write when {@code user}
	 * may not update one of them.
	 *
	 * @return true when the duplicate is gone
	 * @throws PictureBookException 400 self-merge, 401/403/404 not permitted or not in this book's world,
	 *         409 either side is locked or the survivor's alias list would overflow, 500 persist failure
	 */
	public static boolean mergeLocations(BaseRecord user, BaseRecord book, BaseRecord duplicate, BaseRecord survivor) {
		BaseRecord freshBook = requireBookUpdate(user, book);
		BookContext bctx = requireBookContext(user, freshBook);
		long orgId = bctx.getOrganizationId();
		BaseRecord olioUser = olioUser(orgId);
		IOContext ioContext = IOSystem.getActiveContext();

		BaseRecord dup = readAs(olioUser, OlioModelNames.MODEL_PB_LOCATION, oid(duplicate), orgId, PbBookUtil.locationRequest());
		BaseRecord sur = readAs(olioUser, OlioModelNames.MODEL_PB_LOCATION, oid(survivor), orgId, PbBookUtil.locationRequest());
		long worldId = PbHealthUtil.idOf(bctx.getWorld());
		/// A location of another world in the organization is "not found" here, not "exists elsewhere"
		if(dup == null || sur == null
				|| PbHealthUtil.fkId(dup, OlioFieldNames.FIELD_PB_WORLD) != worldId || PbHealthUtil.fkId(sur, OlioFieldNames.FIELD_PB_WORLD) != worldId) {
			throw new PictureBookException(404, "Location not found in this book's world");
		}
		if(PbHealthUtil.idOf(dup) == PbHealthUtil.idOf(sur)) {
			throw new PictureBookException(400, "A location cannot be merged into itself");
		}
		/// Whole-asset lock (§6.C 174): a merge deletes the duplicate and edits the survivor's aliases, both refused while locked
		requireUnlocked(dup, "merge it into another location");
		requireUnlocked(sur, "merge another location into it");

		/// 0. the folded alias list must fit; a silently dropped duplicate name would let the next mention recreate it
		List<String> merged = new ArrayList<>();
		List<String> surAliases = sur.get(OlioFieldNames.FIELD_PB_ALIASES);
		if(surAliases != null) {
			merged.addAll(surAliases);
		}
		merged.add(dup.get(FieldNames.FIELD_NAME));
		List<String> dupAliases = dup.get(OlioFieldNames.FIELD_PB_ALIASES);
		if(dupAliases != null) {
			merged.addAll(dupAliases);
		}
		List<String> folded = normalizeAliases(merged, sur.get(FieldNames.FIELD_NAME), null, Integer.MAX_VALUE);
		if(folded.size() > MAX_ALIASES) {
			throw new PictureBookException(409, "Merging would give the survivor " + folded.size() + " aliases; the limit is " + MAX_ALIASES);
		}

		/// 1. authorize every scene re-point before writing anything
		List<BaseRecord> scenes = scenesReferencing(dup, orgId);
		for(BaseRecord scene : scenes) {
			PolicyResponseType prr = ioContext.getAuthorizationUtil().canUpdate(user, user, scene);
			if(prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
				logger.warn("mergeLocations: " + user.get(FieldNames.FIELD_NAME) + " may not update scene "
					+ scene.get(FieldNames.FIELD_OBJECT_ID) + "; merge refused before any write");
				return false;
			}
		}
		try {
			/// 2. re-point scenes as the request user
			int staleMarked = 0;
			for(BaseRecord scene : scenes) {
				BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION);
				patch.set(FieldNames.FIELD_LOCATION, sur);
				if(ioContext.getAccessPoint().update(user, patch) == null) {
					throw new PictureBookException(500, "Scene " + scene.get(FieldNames.FIELD_OBJECT_ID) + " could not be re-pointed");
				}
				/// the render on file is of the duplicate; STALE (pinned stays pinned, RUNNING waits for the run to close)
				if(PbBookUtil.markSceneNodeStale(user, scene)) {
					staleMarked++;
				}
			}
			if(!scenes.isEmpty()) {
				logger.info("mergeLocations: " + scenes.size() + " scene(s) re-pointed, " + staleMarked + " page node(s) marked stale");
			}
			/// 3. survivor aliases += duplicate name + aliases (already checked to fit in step 0)
			BaseRecord aliasPatch = PbGraphUtil.patchOf(sur, OlioModelNames.MODEL_PB_LOCATION, OlioFieldNames.FIELD_PB_ALIASES);
			List<String> target = aliasPatch.get(OlioFieldNames.FIELD_PB_ALIASES);
			target.clear();
			target.addAll(folded);
			if(ioContext.getAccessPoint().update(olioUser, aliasPatch) == null) {
				throw new PictureBookException(500, "Survivor aliases could not be written");
			}
			/// 4. re-point the duplicate's variants
			for(BaseRecord v : variantsOf(dup, orgId)) {
				BaseRecord vp = PbGraphUtil.patchOf(v, OlioModelNames.MODEL_PB_LOCATION_VARIANT, FieldNames.FIELD_LOCATION);
				vp.set(FieldNames.FIELD_LOCATION, sur);
				if(ioContext.getAccessPoint().update(olioUser, vp) == null) {
					throw new PictureBookException(500, "Variant " + v.get(FieldNames.FIELD_OBJECT_ID) + " could not be re-pointed");
				}
			}
			/// 4a. this book's LOCATION node for the duplicate itself goes with it, candidates included - the same
			/// clean-up deleteLocation performs; a node scoped to one of the duplicate's variants stays valid because
			/// the variant was re-pointed above, not deleted. The duplicate's candidates are never promoted.
			int nodesRemoved = deleteLocationNodes(user, freshBook, Set.of(oid(dup)), orgId, DeleteResult.ok());
			if(nodesRemoved > 0) {
				logger.info("mergeLocations: removed " + nodesRemoved + " LOCATION node(s) of " + dup.get(FieldNames.FIELD_NAME) + " from this book's workflow");
			}
			/// 4b. graph bindings that reference the duplicate now reference the survivor (§6.C 171 n). refHash is
			/// deliberately left as the duplicate's: the next drift scan then flags the node STALE exactly when the
			/// survivor's watched fields differ from the duplicate's, which is the honest answer. Bindings of a
			/// workflow this user may not update are skipped and counted, never failed.
			repointBindings(user, dup, sur, orgId);
			/// 5. the duplicate's map-free POI + store, when nothing else can be using them
			BaseRecord dupPoi = dup.get(OlioFieldNames.FIELD_PB_POI);
			if(dupPoi != null && PbHealthUtil.idOf(dupPoi) > 0L && isBookWorld(olioUser, bctx.getWorld())) {
				deleteOrphanPoi(olioUser, bctx.getWorld(), dup, dupPoi, orgId);
			}
			/// 6. the duplicate itself
			DeleteResult dr = PictureBookUtil.deleteRecordExplained(olioUser, dup);
			if(!dr.deleted) {
				throw new PictureBookException(500, "Duplicate location could not be deleted: " + dr.reason);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("mergeLocations: " + e.getMessage());
			throw new PictureBookException(500, "The merge could not be written");
		}
		finally {
			PbStaleUtil.touchBook(user, book);
			PbStaleUtil.touch(dup);
		}
		return true;
	}

	/**
	 * Every {@code olio.pb.binding} in the organization whose {@code refModel} is a location and whose
	 * {@code refObjectId} is {@code dup} is re-pointed at {@code sur} as {@code user}. Returns
	 * {@code [updated, skipped]}; skipped bindings (PBAC-denied, typically another chapter's workflow this
	 * user may not update) keep the duplicate's objectId and surface later as a dangling ref binding.
	 */
	static int[] repointBindings(BaseRecord user, BaseRecord dup, BaseRecord sur, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_REF_MODEL, OlioModelNames.MODEL_PB_LOCATION);
		q.field(OlioFieldNames.FIELD_PB_REF_OBJECT_ID, oid(dup));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbGraphUtil.bindingRequest());
		q.setCache(false);
		BaseRecord[] bindings = IOSystem.getActiveContext().getSearch().findRecords(q);
		int updated = 0;
		int skipped = 0;
		if(bindings != null) {
			for(BaseRecord b : bindings) {
				try {
					BaseRecord bp = PbGraphUtil.patchOf(b, OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_REF_OBJECT_ID);
					bp.set(OlioFieldNames.FIELD_PB_REF_OBJECT_ID, oid(sur));
					if(IOSystem.getActiveContext().getAccessPoint().update(user, bp) == null) {
						skipped++;
					}
					else {
						updated++;
					}
				}
				catch(FieldException | ValueException | ModelNotFoundException e) {
					logger.warn("repointBindings: binding " + b.get(FieldNames.FIELD_OBJECT_ID) + ": " + e.getMessage());
					skipped++;
				}
			}
		}
		if(updated > 0 || skipped > 0) {
			logger.warn("mergeLocations: " + updated + " binding(s) re-pointed from " + oid(dup) + " to " + oid(sur)
				+ (skipped > 0 ? "; " + skipped + " not permitted and left dangling" : ""));
		}
		return new int[] {updated, skipped};
	}

	/// D4 guard: only a map-free POI in this world's own POI group that no other location references.
	private static void deleteOrphanPoi(BaseRecord olioUser, BaseRecord world, BaseRecord owner, BaseRecord poiRef, long orgId) {
		BaseRecord poi = readAs(olioUser, OlioModelNames.MODEL_POI, oid(poiRef), orgId, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_LOCATION, FieldNames.FIELD_STORE
		});
		if(poi == null) {
			return;
		}
		Object poiGroup = world.get(OlioFieldNames.FIELD_POINTS_OF_INTEREST_ID);
		long poiGroupId = (poiGroup instanceof Number ? ((Number) poiGroup).longValue() : -1L);
		Object pg = poi.get(FieldNames.FIELD_GROUP_ID);
		long ownGroupId = (pg instanceof Number ? ((Number) pg).longValue() : -1L);
		if(poiGroupId <= 0L || ownGroupId != poiGroupId) {
			logger.info("mergeLocations: POI " + poi.get(FieldNames.FIELD_OBJECT_ID) + " is not in this world's Points of Interest group; left alone");
			return;
		}
		if(poi.get(FieldNames.FIELD_LOCATION) != null) {
			logger.info("mergeLocations: POI " + poi.get(FieldNames.FIELD_OBJECT_ID) + " is placed on the map; left alone");
			return;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION, OlioFieldNames.FIELD_PB_POI, poi);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		BaseRecord[] refs = IOSystem.getActiveContext().getSearch().findRecords(q);
		if(refs != null) {
			for(BaseRecord r : refs) {
				if(PbHealthUtil.idOf(r) != PbHealthUtil.idOf(owner)) {
					logger.info("mergeLocations: POI " + poi.get(FieldNames.FIELD_OBJECT_ID) + " is also referenced by location "
						+ r.get(FieldNames.FIELD_OBJECT_ID) + "; left alone");
					return;
				}
			}
		}
		BaseRecord store = poi.get(FieldNames.FIELD_STORE);
		DeleteResult pr = PictureBookUtil.deleteRecordExplained(olioUser, poi);
		if(!pr.deleted) {
			logger.warn("mergeLocations: POI " + poi.get(FieldNames.FIELD_OBJECT_ID) + " not deleted: " + pr.reason);
			return;
		}
		if(store != null && PbHealthUtil.idOf(store) > 0L) {
			BaseRecord storeRec = readAs(olioUser, OlioModelNames.MODEL_STORE, oid(store), orgId,
				new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID});
			if(storeRec != null) {
				DeleteResult sr = PictureBookUtil.deleteRecordExplained(olioUser, storeRec);
				if(!sr.deleted) {
					logger.warn("mergeLocations: store " + storeRec.get(FieldNames.FIELD_OBJECT_ID) + " not deleted: " + sr.reason);
				}
			}
		}
	}

	/** Scenes (any book in the organization) whose {@code location} is {@code loc}. Record-valued condition, uncached. */
	static List<BaseRecord> scenesReferencing(BaseRecord loc, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION, loc);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.sceneRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
		return (recs != null ? PbGraphUtil.restoreSchema(new ArrayList<>(Arrays.asList(recs)), OlioModelNames.MODEL_PB_SCENE) : new ArrayList<>());
	}

	static List<BaseRecord> variantsOf(BaseRecord loc, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION_VARIANT, FieldNames.FIELD_LOCATION, loc);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.locationVariantRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
		return (recs != null ? PbGraphUtil.restoreSchema(new ArrayList<>(Arrays.asList(recs)), OlioModelNames.MODEL_PB_LOCATION_VARIANT) : new ArrayList<>());
	}

	private static BaseRecord readAs(BaseRecord actor, String model, String objectId, long orgId, String[] request) {
		if(objectId == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(request);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(actor, q);
	}

	private static String oid(BaseRecord rec) {
		return (rec != null ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
	}

	// ─────────────────────────────── whole-book pass (§7.1, §28 step 3) ───────────────────────────────

	/** Prompt template (classpath {@code olio/llm/prompts/}, overridable by a DB {@code olio.llm.promptTemplate} of the same name). */
	public static final String PROMPT_CLUSTER_LOCATIONS = "pictureBook.cluster-locations";
	/** Scenes per LLM call; keeps one user turn inside a small context window on a chaptered manuscript. */
	static final int PASS_CHUNK_SIZE = 40;
	/** Setting / title text per scene line handed to the clusterer. */
	static final int MAX_SETTING_CHARS = 600;
	static final int MAX_TITLE_CHARS = 120;
	static final String PROPOSAL_NAME_PREFIX = "proposal-";
	private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/** What one {@link #proposeLocations} run did. Counts, never timings. */
	public static final class LocationPassResult {
		public String passRun;
		public int scenesConsidered;
		public int scenesSkipped;
		public int chunks;
		public int chunksParsed;
		public int proposed;
		public int updated;
		public int settled;
		public int retired;
		/** Clusters the model split off a named place by room or inside/outside, folded back into it (design §7). */
		public int folded;
		public boolean hardFailure;
		public List<String> warnings = new ArrayList<>();
		public List<BaseRecord> proposals = new ArrayList<>();
	}

	/** What {@link #acceptProposal} did. */
	public static final class ProposalAcceptResult {
		public BaseRecord proposal;
		public BaseRecord location;
		public boolean locationCreated;
		public int scenesRepointed;
		public int scenesSkipped;
		/// Re-pointed scenes whose page node was marked STALE (a re-pointed scene's render is of the old place)
		public int nodesMarkedStale;
		public int aliasesFolded;
		public List<String> warnings = new ArrayList<>();
	}

	/** One clustered place as the pass understood it, before it is written as a proposal row. */
	static final class Cluster {
		String proposedName;
		String description;
		PbLocationKindEnumType kind = PbLocationKindEnumType.UNKNOWN;
		List<String> aliases = new ArrayList<>();
		List<String> sceneObjectIds = new ArrayList<>();
		String sameAs;
		/// A sameAs that names no known location: resolved against the other clusters of the pass by
		/// foldSubPlaces, and only warned about when it names none of them either.
		String sameAsClaim;
	}

	/**
	 * Run the whole-book location pass with a named chat config ({@code null} → the user's {@code generalChat}
	 * resolution inside {@code PictureBookUtil}). See {@link #proposeLocations(BaseRecord, BaseRecord, BaseRecord)}.
	 *
	 * @throws PictureBookException 404 when {@code chatConfigName} names no readable config
	 */
	public static LocationPassResult proposeLocations(BaseRecord user, BaseRecord book, String chatConfigName) {
		BaseRecord cfg = null;
		if(chatConfigName != null && !chatConfigName.trim().isEmpty()) {
			cfg = ChatUtil.resolveConfig(user, OlioModelNames.MODEL_CHAT_CONFIG, chatConfigName.trim(), null);
			if(cfg == null) {
				throw new PictureBookException(404, "Chat config not found");
			}
		}
		return proposeLocations(user, book, cfg);
	}

	/**
	 * The whole-book location pass (design §7.1; migration step 3 of §28): cluster every scene that has a
	 * meaningful {@code setting} by the place it happens in and write one {@code olio.pb.locationProposal} per
	 * place into the book's group, as {@code user}, status PROPOSED. Nothing is resolved here - no
	 * {@code olio.pb.location}, POI or store is created and no scene is touched - until a reviewer
	 * {@link #acceptProposal accepts} an item.
	 * <p>
	 * Idempotent and non-destructive: a proposal whose row already exists is re-written when PROPOSED and left
	 * alone when ACCEPTED or REJECTED; PROPOSED rows that this run did not produce are retired only when every
	 * chunk parsed, so a partial LLM failure never deletes what an earlier complete pass proposed. A hard LLM
	 * failure (no template, no config, transport error) is reported on the result, never thrown, so a migration
	 * can call this after its scenes are in without the import depending on the LLM.
	 *
	 * @param chatConfig an in-memory or persisted {@code olio.llm.chatConfig}, or null for {@code generalChat}
	 * @throws PictureBookException 401/403/404 not permitted, 409 no world
	 */
	public static LocationPassResult proposeLocations(BaseRecord user, BaseRecord book, BaseRecord chatConfig) {
		BaseRecord freshBook = requireBookUpdate(user, book);
		BookContext bctx = requireBookContext(user, freshBook);
		long orgId = bctx.getOrganizationId();
		String slug = freshBook.get(OlioFieldNames.FIELD_PB_SLUG);
		LocationPassResult result = new LocationPassResult();
		result.passRun = UUID.randomUUID().toString();

		/// 1. scenes with something to cluster, keyed by the index the LLM will echo back
		Map<Integer, BaseRecord> byIndex = new LinkedHashMap<>();
		for(BaseRecord scene : PbBookUtil.listScenes(user, freshBook)) {
			Object idx = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			String setting = scene.get(OlioFieldNames.FIELD_PB_SETTING);
			if(!(idx instanceof Number) || !NarrativeUtil.isMeaningful(setting)) {
				result.scenesSkipped++;
				continue;
			}
			byIndex.put(((Number) idx).intValue(), scene);
			result.scenesConsidered++;
		}
		if(byIndex.isEmpty()) {
			result.warnings.add("No scene of " + slug + " has a setting to cluster");
			return result;
		}

		/// 2. what the world already knows, as the caller: a location the user may not read must not reach the
		/// prompt, where it would come back as a sameAs name the user was never entitled to see. A user without
		/// Read on the Locations group simply gets "(none yet)" and the pass proposes fresh places.
		List<BaseRecord> known = listLocations(user, bctx);
		Set<String> knownKeys = new LinkedHashSet<>();
		StringBuilder knownText = new StringBuilder();
		for(BaseRecord loc : known) {
			String n = loc.get(FieldNames.FIELD_NAME);
			if(n == null || n.trim().isEmpty()) {
				continue;
			}
			knownKeys.add(n.trim().toLowerCase(Locale.ROOT));
			knownText.append("- ").append(sanitizeForPrompt(n.trim(), 256)).append('\n');
			List<String> al = loc.get(OlioFieldNames.FIELD_PB_ALIASES);
			if(al != null) {
				for(String a : al) {
					if(a != null && !a.trim().isEmpty()) {
						knownKeys.add(a.trim().toLowerCase(Locale.ROOT));
					}
				}
			}
		}
		if(knownText.length() == 0) {
			knownText.append("(none yet)");
		}

		/// 3. one LLM call per chunk, merged by proposed name across chunks
		List<Integer> indices = new ArrayList<>(byIndex.keySet());
		Map<String, Cluster> clusters = new LinkedHashMap<>();
		int chunkNo = 0;
		for(int from = 0; from < indices.size(); from += PASS_CHUNK_SIZE) {
			chunkNo++;
			result.chunks++;
			List<Integer> part = indices.subList(from, Math.min(indices.size(), from + PASS_CHUNK_SIZE));
			StringBuilder lines = new StringBuilder();
			for(Integer i : part) {
				BaseRecord scene = byIndex.get(i);
				lines.append('#').append(i).append(": ").append(sanitizeForPrompt(scene.get(OlioFieldNames.FIELD_PB_SETTING), MAX_SETTING_CHARS));
				String title = scene.get(OlioFieldNames.FIELD_PB_TITLE);
				if(NarrativeUtil.isMeaningful(title)) {
					lines.append(" - ").append(sanitizeForPrompt(title, MAX_TITLE_CHARS));
				}
				lines.append('\n');
			}
			Map<String, String> vars = new HashMap<>();
			vars.put("knownLocations", knownText.toString().trim());
			vars.put("scenes", lines.toString().trim());
			boolean[] hardOut = new boolean[1];
			boolean[] okOut = new boolean[1];
			String[] lastReply = new String[1];
			/// Same single-shot contract as the short-text scene extraction: one corrective retry when the reply
			/// came back but would not parse as a whole (measured live 2026-10-16: qwen3:8b wrote `"scenes",[3,7,12]`
			/// in three of four elements, and the salvage kept one). The hint rides on `scenes`, the last var the
			/// prompt renders; the emulator skips lines that are not `#n: setting`, so it is unaffected.
			List<Map<String, Object>> items = PictureBookUtil.extractSingleShot(v -> {
				String r = PictureBookUtil.callLlmForChapBook(user, chatConfig, PROMPT_CLUSTER_LOCATIONS, v, hardOut);
				lastReply[0] = r;
				return r;
			}, vars, "scenes", "cluster-locations:" + slug + ":chunk" + chunkNo, result.warnings, null, okOut);
			if(hardOut[0]) {
				result.hardFailure = true;
				result.warnings.add("cluster-locations chunk " + chunkNo + " of " + slug + ": the LLM could not be used (hard failure)");
				logger.error("proposeLocations: hard LLM failure on chunk " + chunkNo + " of " + slug + "; pass stopped");
				break;
			}
			if(okOut[0]) {
				result.chunksParsed++;
			}
			else if(items.isEmpty() && lastReply[0] == null) {
				result.warnings.add("cluster-locations chunk " + chunkNo + " of " + slug + ": the LLM returned nothing usable");
				continue;
			}
			else {
				result.warnings.add("cluster-locations chunk " + chunkNo + " of " + slug + ": response did not parse as a whole array (" + items.size() + " item(s) salvaged)");
			}
			for(Map<String, Object> item : items) {
				mergeCluster(clusters, normalizeCluster(item, byIndex, knownKeys, slug, chunkNo, result.warnings));
			}
		}

		/// 3b. Design §7: a named place and its rooms, yard or doorstep are ONE location; inside/outside is a
		/// variant key (`viewpoint`), never a second record. The prompt says so, and the model still split the
		/// tavern (measured 2026-10-16, qwen3:8b through LiteLLM: "Gilded Tankard" EXTERIOR for scene 3 and
		/// "Gilded Tankard Taproom" INTERIOR for scenes 7 and 12, sameAs null), so the pass folds such splits back
		/// deterministically - by name shape only, model-agnostic - and says so in the warnings.
		result.folded = foldSubPlaces(clusters, slug, result.warnings);

		/// 4. persist, as the request user, in the Book group (where the scenes live)
		String groupPath = PbBookUtil.bookGroupPath(slug);
		Map<String, BaseRecord> existing = new HashMap<>();
		for(BaseRecord p : listProposals(user, freshBook, null)) {
			existing.put(p.get(FieldNames.FIELD_NAME), p);
		}
		Set<String> handled = new LinkedHashSet<>();
		for(Cluster c : clusters.values()) {
			String rowName = proposalName(c.proposedName);
			handled.add(rowName);
			BaseRecord row = existing.get(rowName);
			if(row != null) {
				PbProposalStatusEnumType st = statusOf(row);
				if(st == PbProposalStatusEnumType.ACCEPTED || st == PbProposalStatusEnumType.REJECTED) {
					result.settled++;
					result.proposals.add(row);
					continue;
				}
				BaseRecord patched = writeProposal(user, row, c, result.passRun);
				result.updated++;
				result.proposals.add(patched);
				continue;
			}
			BaseRecord created = createProposal(user, freshBook, groupPath, orgId, rowName, c, result.passRun);
			result.proposed++;
			result.proposals.add(created);
		}

		/// 5. retire what an earlier pass proposed and this complete pass did not (C1); never ACCEPTED/REJECTED.
		/// `existing` holds the pre-pass rows, so their passRun is always the old one - membership in `handled`
		/// is the only reliable "this pass produced it" test.
		if(!result.hardFailure && result.chunks > 0 && result.chunksParsed == result.chunks) {
			for(BaseRecord p : existing.values()) {
				if(statusOf(p) != PbProposalStatusEnumType.PROPOSED || handled.contains(p.get(FieldNames.FIELD_NAME))) {
					continue;
				}
				DeleteResult dr = PictureBookUtil.deleteRecordExplained(user, p);
				if(dr.deleted) {
					result.retired++;
				}
				else {
					result.warnings.add("Stale proposal " + p.get(FieldNames.FIELD_NAME) + " was not retired: " + dr.reason);
				}
			}
		}
		logger.info("proposeLocations(" + slug + "): scenes=" + result.scenesConsidered + " skipped=" + result.scenesSkipped
			+ " chunks=" + result.chunksParsed + "/" + result.chunks + " folded=" + result.folded + " proposed=" + result.proposed + " updated=" + result.updated
			+ " settled=" + result.settled + " retired=" + result.retired + (result.hardFailure ? " HARD-FAILURE" : ""));
		return result;
	}

	/// Braces would trip PictureBookUtil's unsubstituted-placeholder guard after substitution; newlines would
	/// break the one-scene-per-line contract the prompt (and the emulator) parse.
	static String sanitizeForPrompt(String text, int max) {
		if(text == null) {
			return "";
		}
		String t = WHITESPACE.matcher(text.replace('{', '(').replace('}', ')')).replaceAll(" ").trim();
		if(t.length() > max) {
			t = t.substring(0, max).trim();
		}
		return t;
	}

	/** {@code proposal-<slug of the proposed name>}; unique per book group, stable across runs for the same place. */
	static String proposalName(String proposedName) {
		String base = (proposedName != null ? proposedName.trim().toLowerCase(Locale.ROOT) : "");
		String slug = NON_SLUG.matcher(base).replaceAll("-").replaceAll("^-+|-+$", "");
		if(slug.isEmpty()) {
			slug = Integer.toHexString(base.hashCode());
		}
		String name = PROPOSAL_NAME_PREFIX + slug;
		return (name.length() > 256 ? name.substring(0, 256) : name);
	}

	@SuppressWarnings("unchecked")
	private static Cluster normalizeCluster(Map<String, Object> item, Map<Integer, BaseRecord> byIndex, Set<String> knownKeys,
			String slug, int chunkNo, List<String> warnings) {
		if(item == null) {
			return null;
		}
		String name = asText(item.get("name"));
		if(!NarrativeUtil.isMeaningful(name)) {
			warnings.add("cluster-locations chunk " + chunkNo + " of " + slug + ": an item without a name was dropped");
			return null;
		}
		Cluster c = new Cluster();
		c.proposedName = sanitizeForPrompt(name, 256);
		String desc = asText(item.get("description"));
		if(NarrativeUtil.isMeaningful(desc)) {
			c.description = (desc.trim().length() > MAX_DESCRIPTION_LENGTH ? desc.trim().substring(0, MAX_DESCRIPTION_LENGTH).trim() : desc.trim());
		}
		c.kind = kindOf(asText(item.get("kind")));
		Object al = item.get("aliases");
		if(al instanceof List) {
			List<String> raw = new ArrayList<>();
			for(Object o : (List<Object>) al) {
				raw.add(asText(o));
			}
			c.aliases.addAll(normalizeAliases(raw, c.proposedName, null));
		}
		Object sc = item.get("scenes");
		if(sc instanceof List) {
			for(Object o : (List<Object>) sc) {
				Integer idx = asIndex(o);
				BaseRecord scene = (idx != null ? byIndex.get(idx) : null);
				if(scene == null) {
					warnings.add("cluster-locations chunk " + chunkNo + " of " + slug + ": '" + c.proposedName + "' names scene " + o + ", which is not in this chunk");
					continue;
				}
				String soid = scene.get(FieldNames.FIELD_OBJECT_ID);
				if(soid != null && !c.sceneObjectIds.contains(soid)) {
					c.sceneObjectIds.add(soid);
				}
			}
		}
		String same = asText(item.get("sameAs"));
		if(NarrativeUtil.isMeaningful(same)) {
			if(knownKeys.contains(same.trim().toLowerCase(Locale.ROOT))) {
				c.sameAs = sanitizeForPrompt(same, 256);
			}
			else {
				c.sameAsClaim = sanitizeForPrompt(same, 256);
			}
		}
		return c;
	}

	/// Same place proposed from two chunks: union the scenes and aliases, keep the first description and kind.
	private static void mergeCluster(Map<String, Cluster> clusters, Cluster c) {
		if(c == null) {
			return;
		}
		String key = proposalName(c.proposedName);
		Cluster have = clusters.get(key);
		if(have == null) {
			clusters.put(key, c);
			return;
		}
		for(String soid : c.sceneObjectIds) {
			if(!have.sceneObjectIds.contains(soid)) {
				have.sceneObjectIds.add(soid);
			}
		}
		List<String> al = new ArrayList<>(have.aliases);
		al.addAll(c.aliases);
		if(!have.proposedName.equalsIgnoreCase(c.proposedName)) {
			al.add(c.proposedName);
		}
		have.aliases = normalizeAliases(al, have.proposedName, null);
		if(have.description == null) {
			have.description = c.description;
		}
		if(have.kind == PbLocationKindEnumType.UNKNOWN) {
			have.kind = c.kind;
		}
		if(have.sameAs == null) {
			have.sameAs = c.sameAs;
		}
		if(have.sameAsClaim == null) {
			have.sameAsClaim = c.sameAsClaim;
		}
	}

	/// Words that name a PART of a place: "<name> taproom", "<name> courtyard". An inn, a road, a square or a garden
	/// is a place of its own, so they are deliberately absent - "Harbour Road Inn" is never folded into "Harbour Road".
	private static final Set<String> SUB_PLACE_WORDS = new LinkedHashSet<>(Arrays.asList(
		"taproom", "tap room", "bar", "barroom", "common room", "kitchen", "kitchens", "cellar", "cellars", "hall", "great hall",
		"chamber", "chambers", "bedchamber", "room", "rooms", "stair", "stairs", "stairwell", "staircase", "corridor", "hallway",
		"attic", "loft", "roof", "rooftop", "yard", "courtyard", "forecourt", "stable", "stables", "doorway", "door", "doors",
		"entrance", "porch", "threshold", "steps", "interior", "exterior", "inside", "outside", "front", "back", "upstairs",
		"downstairs", "balcony", "antechamber", "parlour", "parlor", "scullery", "pantry", "larder", "undercroft", "crypt",
		"vestibule", "nave", "cloister", "dormitory", "refectory"));
	/// Leading phrases that view a place from inside or outside: "Inside the <name>", "Outside <name>".
	private static final Set<String> SUB_PLACE_PREFIXES = new LinkedHashSet<>(Arrays.asList(
		"inside", "outside", "within", "interior", "exterior", "in", "at", "before", "behind", "in front", "back"));
	/// Trailing qualifiers that are a variant key (timeOfDay / weather / season, design §7), never a second place:
	/// "<name> Night", "<name> at Dawn", "<name> in Winter". Suffix only - a leading one ("Winter Palace", "Night
	/// Market") is more often a proper name, so it is left alone. "spring" and "fall" are absent (water, waterfall).
	private static final Set<String> VARIANT_WORDS = new LinkedHashSet<>(Arrays.asList(
		"night", "nightfall", "nighttime", "day", "daytime", "daybreak", "dawn", "sunrise", "morning", "noon", "midday",
		"afternoon", "evening", "dusk", "sunset", "twilight", "midnight", "moonlight", "moonlit", "candlelit", "lamplit",
		"firelit", "rain", "rainy", "raining", "rainfall", "storm", "stormy", "thunderstorm", "snow", "snowy", "snowfall",
		"fog", "foggy", "mist", "misty", "windy", "sunny", "sunlit", "cloudy", "overcast", "winter", "wintertime", "summer",
		"summertime", "springtime", "autumn"));
	private static final Set<String> FILLER_WORDS = new LinkedHashSet<>(Arrays.asList("the", "a", "an", "of", "its"));
	/// Also dropped from a trailing qualifier: "<name> at night", "<name> in winter", "<name> taproom and cellar".
	private static final Set<String> QUALIFIER_FILLER_WORDS = new LinkedHashSet<>(Arrays.asList(
		"the", "a", "an", "of", "its", "at", "by", "in", "on", "after", "before", "during", "under", "with", "and"));

	/**
	 * Fold every cluster whose name is another cluster's name plus sub-place words ("Gilded Tankard Taproom"), or
	 * variant-key words ("Abbey Scriptorium Night", "Harbour Road at Dawn"), or an inside/outside view of it ("Inside
	 * the Gilded Tankard"), or the same name up to the article, into that cluster: scenes unioned, the folded name and
	 * aliases kept as aliases, descriptions joined, kind MIXED when the two disagree. The shorter name survives.
	 * Repeats until nothing folds. Name shape only - no model, no text of the settings - so it is the same for every LLM.
	 * <p>
	 * Then the model's own word: a cluster named among another cluster's aliases, or whose {@code sameAs} names
	 * another cluster of this pass (by name or alias) rather than a known location, folds into that cluster. Measured
	 * live 2026-10-17 (qwen3:8b through LiteLLM): "Scriptorium" with alias "abbey scriptorium" for scene 2 and
	 * "Abbey Scriptorium" with sameAs "Scriptorium" for scene 4 - the model had already said they are one place, and
	 * a leading qualifier ("abbey", "night" in "Night Market") is by design never folded on shape alone. A claim that
	 * resolves to nothing is reported once, here, after the fold.
	 *
	 * @return how many clusters were folded away
	 */
	static int foldSubPlaces(Map<String, Cluster> clusters, String slug, List<String> warnings) {
		int folded = 0;
		boolean changed = true;
		while(changed && clusters.size() > 1) {
			changed = false;
			List<Cluster> list = new ArrayList<>(clusters.values());
			search:
			for(Cluster base : list) {
				String baseKey = placeKey(base.proposedName);
				for(Cluster part : list) {
					if(part == base) {
						continue;
					}
					String partKey = placeKey(part.proposedName);
					String why = subPlaceOf(baseKey, partKey);
					if(why == null) {
						why = sameSiblingOf(base, baseKey, part, partKey);
					}
					if(why == null) {
						continue;
					}
					if(clusters.remove(proposalName(part.proposedName)) == null) {
						/// Keys are proposalName(proposedName) and proposedName is never mutated, so this cannot
						/// happen; the guard keeps the loop's bound structural rather than invariant-dependent.
						logger.error("cluster-locations " + slug + ": cluster '" + part.proposedName + "' is not keyed by its own name; fold stopped");
						return folded;
					}
					if(base.sameAs != null && part.sameAs != null && !base.sameAs.equals(part.sameAs)) {
						warnings.add("cluster-locations " + slug + ": '" + part.proposedName + "' was marked the same as known location '"
							+ part.sameAs + "' but it folds into '" + base.proposedName + "', which is the same as '" + base.sameAs + "'; review the merge");
					}
					foldInto(base, part);
					warnings.add("cluster-locations " + slug + ": folded '" + part.proposedName + "' into '" + base.proposedName + "' - " + why
						+ " (a place and its parts are one location; inside/outside, time of day, weather and season are variant keys)");
					folded++;
					changed = true;
					break search;
				}
			}
		}
		for(Cluster c : clusters.values()) {
			if(c.sameAsClaim != null) {
				warnings.add("cluster-locations " + slug + ": '" + c.proposedName + "' claims sameAs '" + c.sameAsClaim
					+ "', which is neither a known location nor another place of this pass; ignored");
			}
		}
		return folded;
	}

	/**
	 * Why {@code part} is, by the model's own word, the same place as {@code base}: named among base's aliases, or its
	 * sameAs names base (by name or alias). Null when neither.
	 */
	static String sameSiblingOf(Cluster base, String baseKey, Cluster part, String partKey) {
		if(baseKey.isEmpty() || partKey.isEmpty()) {
			return null;
		}
		Set<String> baseKeys = keysOf(base);
		if(baseKeys.contains(partKey)) {
			return "named among its aliases";
		}
		if(part.sameAsClaim != null && baseKeys.contains(placeKey(part.sameAsClaim))) {
			return "marked the same as it";
		}
		return null;
	}

	/** The place keys a cluster answers to: its name and every alias. */
	private static Set<String> keysOf(Cluster c) {
		Set<String> keys = new LinkedHashSet<>();
		keys.add(placeKey(c.proposedName));
		for(String a : c.aliases) {
			String k = placeKey(a);
			if(!k.isEmpty()) {
				keys.add(k);
			}
		}
		keys.remove("");
		return keys;
	}

	/** Lower-case, alphanumeric words only, leading article dropped: "The Gilded Tankard!" → "gilded tankard". */
	static String placeKey(String name) {
		String t = (name == null ? "" : NON_SLUG.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(" ").trim());
		if(t.startsWith("the ")) {
			t = t.substring(4).trim();
		}
		return t;
	}

	/** Why {@code partKey} is a part or view of {@code baseKey}, or null when it is not. */
	static String subPlaceOf(String baseKey, String partKey) {
		if(baseKey.isEmpty() || partKey.isEmpty()) {
			return null;
		}
		if(partKey.equals(baseKey)) {
			return "the same name";
		}
		if(partKey.startsWith(baseKey + " ")) {
			String why = qualifierOf(stripFiller(partKey.substring(baseKey.length()), QUALIFIER_FILLER_WORDS));
			if(why != null) {
				return why;
			}
		}
		if(partKey.endsWith(" " + baseKey)) {
			String lead = stripFiller(partKey.substring(0, partKey.length() - baseKey.length()), FILLER_WORDS);
			if(SUB_PLACE_PREFIXES.contains(lead)) {
				return "a view " + lead + " it";
			}
		}
		return null;
	}

	/**
	 * What a trailing qualifier makes of the place, or null when it names something else: "taproom" → its taproom;
	 * "night" → its night variant; "taproom night" → its taproom by night; "inn" → null (an inn is a place of its own).
	 */
	static String qualifierOf(String rest) {
		if(rest.isEmpty()) {
			return null;
		}
		if(SUB_PLACE_WORDS.contains(rest)) {
			return "its " + rest;
		}
		List<String> parts = new ArrayList<>();
		List<String> variants = new ArrayList<>();
		for(String w : WHITESPACE.split(rest)) {
			if(SUB_PLACE_WORDS.contains(w)) {
				parts.add(w);
			}
			else if(VARIANT_WORDS.contains(w)) {
				variants.add(w);
			}
			else {
				return null;
			}
		}
		if(parts.isEmpty()) {
			return "its " + String.join(" ", variants) + " variant";
		}
		return "its " + String.join(" ", parts) + (variants.isEmpty() ? "" : " by " + String.join(" ", variants));
	}

	private static String stripFiller(String words, Set<String> filler) {
		List<String> keep = new ArrayList<>();
		for(String w : WHITESPACE.split(words.trim())) {
			if(!w.isEmpty() && !filler.contains(w)) {
				keep.add(w);
			}
		}
		return String.join(" ", keep);
	}

	private static void foldInto(Cluster base, Cluster part) {
		for(String soid : part.sceneObjectIds) {
			if(!base.sceneObjectIds.contains(soid)) {
				base.sceneObjectIds.add(soid);
			}
		}
		List<String> al = new ArrayList<>(base.aliases);
		al.add(part.proposedName);
		al.addAll(part.aliases);
		base.aliases = normalizeAliases(al, base.proposedName, null);
		if(base.description == null) {
			base.description = part.description;
		}
		else if(part.description != null && !base.description.toLowerCase(Locale.ROOT).contains(part.description.toLowerCase(Locale.ROOT))) {
			String d = base.description + "; " + part.description;
			base.description = (d.length() > MAX_DESCRIPTION_LENGTH ? d.substring(0, MAX_DESCRIPTION_LENGTH).trim() : d);
		}
		if(base.kind == PbLocationKindEnumType.UNKNOWN) {
			base.kind = part.kind;
		}
		else if(part.kind != PbLocationKindEnumType.UNKNOWN && part.kind != base.kind) {
			base.kind = PbLocationKindEnumType.MIXED;
		}
		if(base.sameAs == null) {
			base.sameAs = part.sameAs;
		}
		/// A claim the part made about a third cluster travels with it; one either made about the other is now met.
		if(base.sameAsClaim == null) {
			base.sameAsClaim = part.sameAsClaim;
		}
		if(base.sameAsClaim != null && keysOf(base).contains(placeKey(base.sameAsClaim))) {
			base.sameAsClaim = null;
		}
	}

	static PbLocationKindEnumType kindOf(String text) {
		if(text == null) {
			return PbLocationKindEnumType.UNKNOWN;
		}
		String t = text.trim();
		for(PbLocationKindEnumType k : PbLocationKindEnumType.values()) {
			if(k.name().equalsIgnoreCase(t)) {
				return k;
			}
		}
		return PbLocationKindEnumType.UNKNOWN;
	}

	private static String asText(Object o) {
		if(o == null) {
			return null;
		}
		if(o instanceof String) {
			return (String) o;
		}
		if(o instanceof Number || o instanceof Boolean) {
			return String.valueOf(o);
		}
		return null;
	}

	private static Integer asIndex(Object o) {
		if(o instanceof Number) {
			return Integer.valueOf(((Number) o).intValue());
		}
		if(o instanceof String) {
			String s = ((String) o).trim().replaceFirst("^#", "");
			try {
				return Integer.valueOf(Integer.parseInt(s));
			}
			catch(NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	private static BaseRecord createProposal(BaseRecord user, BaseRecord book, String groupPath, long orgId, String rowName,
			Cluster c, String passRun) {
		BaseRecord rec;
		try {
			rec = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION_PROPOSAL);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, rec, rowName, groupPath, orgId);
			rec.set(FieldNames.FIELD_NAME, rowName);
			rec.set(OlioFieldNames.FIELD_PB_BOOK, book);
			applyCluster(rec, c, passRun);
			rec.set(OlioFieldNames.FIELD_PB_PROPOSAL_STATUS, PbProposalStatusEnumType.PROPOSED);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("createProposal: could not assemble olio.pb.locationProposal: " + e.getMessage());
			throw new PictureBookException(500, "Failed to assemble the location proposal");
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, rec);
		if(created == null) {
			throw new PictureBookException(500, "Failed to persist location proposal " + rowName);
		}
		return readProposal(user, created.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	private static BaseRecord writeProposal(BaseRecord user, BaseRecord row, Cluster c, String passRun) {
		BaseRecord patch = PbGraphUtil.patchOf(row, OlioModelNames.MODEL_PB_LOCATION_PROPOSAL,
			OlioFieldNames.FIELD_PB_PROPOSED_NAME, FieldNames.FIELD_DESCRIPTION, OlioFieldNames.FIELD_PB_KIND,
			OlioFieldNames.FIELD_PB_ALIASES, OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS, OlioFieldNames.FIELD_PB_SAME_AS,
			OlioFieldNames.FIELD_PB_PASS_RUN, OlioFieldNames.FIELD_PB_PROPOSAL_STATUS);
		try {
			applyCluster(patch, c, passRun);
			patch.set(OlioFieldNames.FIELD_PB_PROPOSAL_STATUS, PbProposalStatusEnumType.PROPOSED);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("writeProposal: could not assemble the proposal patch: " + e.getMessage());
			throw new PictureBookException(500, "Failed to assemble the proposal patch");
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Location proposal " + row.get(FieldNames.FIELD_NAME) + " could not be re-written");
		}
		return readProposal(user, row.get(FieldNames.FIELD_OBJECT_ID), PbGraphUtil.orgId(row));
	}

	private static void applyCluster(BaseRecord rec, Cluster c, String passRun)
			throws FieldException, ValueException, ModelNotFoundException {
		rec.set(OlioFieldNames.FIELD_PB_PROPOSED_NAME, c.proposedName);
		rec.set(FieldNames.FIELD_DESCRIPTION, c.description);
		rec.set(OlioFieldNames.FIELD_PB_KIND, (c.kind != null ? c.kind : PbLocationKindEnumType.UNKNOWN));
		List<String> al = rec.get(OlioFieldNames.FIELD_PB_ALIASES);
		al.clear();
		al.addAll(c.aliases);
		List<String> ids = rec.get(OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS);
		ids.clear();
		ids.addAll(c.sceneObjectIds);
		rec.set(OlioFieldNames.FIELD_PB_SAME_AS, c.sameAs);
		rec.set(OlioFieldNames.FIELD_PB_PASS_RUN, passRun);
	}

	static PbProposalStatusEnumType statusOf(BaseRecord proposal) {
		if(proposal == null || !proposal.hasField(OlioFieldNames.FIELD_PB_PROPOSAL_STATUS)) {
			return PbProposalStatusEnumType.UNKNOWN;
		}
		Object v = proposal.get(OlioFieldNames.FIELD_PB_PROPOSAL_STATUS);
		if(v instanceof PbProposalStatusEnumType) {
			return (PbProposalStatusEnumType) v;
		}
		if(v instanceof String) {
			for(PbProposalStatusEnumType s : PbProposalStatusEnumType.values()) {
				if(s.name().equalsIgnoreCase((String) v)) {
					return s;
				}
			}
		}
		return PbProposalStatusEnumType.UNKNOWN;
	}

	/** What to project on a proposal read. */
	public static String[] proposalRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_BOOK, OlioFieldNames.FIELD_PB_PROPOSED_NAME, FieldNames.FIELD_DESCRIPTION,
			OlioFieldNames.FIELD_PB_KIND, OlioFieldNames.FIELD_PB_ALIASES, OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS,
			OlioFieldNames.FIELD_PB_SAME_AS, OlioFieldNames.FIELD_PB_PROPOSAL_STATUS, FieldNames.FIELD_LOCATION,
			OlioFieldNames.FIELD_PB_REASON, OlioFieldNames.FIELD_PB_PASS_RUN
		};
	}

	/**
	 * The book's proposals, optionally filtered to one status, as {@code user} through {@code AccessPoint.list}
	 * constrained by the book's group (number) and the book record itself; uncached.
	 */
	public static List<BaseRecord> listProposals(BaseRecord user, BaseRecord book, PbProposalStatusEnumType status) {
		if(user == null || book == null) {
			return new ArrayList<>();
		}
		long orgId = PbGraphUtil.orgId(book);
		Object gid = book.get(FieldNames.FIELD_GROUP_ID);
		if(!(gid instanceof Number) || ((Number) gid).longValue() <= 0L) {
			/// A stub without the group would widen the list to the whole org (AccessPoint.list is not a
			/// per-record boundary); re-read the book as the caller so the group condition is always present.
			BaseRecord fresh = PbBookUtil.readBook(user, book.get(FieldNames.FIELD_OBJECT_ID), orgId);
			gid = fresh != null ? fresh.get(FieldNames.FIELD_GROUP_ID) : null;
			if(!(gid instanceof Number) || ((Number) gid).longValue() <= 0L) {
				return new ArrayList<>();
			}
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION_PROPOSAL, OlioFieldNames.FIELD_PB_BOOK, book);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.field(FieldNames.FIELD_GROUP_ID, ((Number) gid).longValue());
		q.setRequest(proposalRequest());
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		List<BaseRecord> out = new ArrayList<>();
		if(recs == null) {
			return out;
		}
		for(BaseRecord r : PbGraphUtil.restoreSchema(new ArrayList<>(Arrays.asList(recs)), OlioModelNames.MODEL_PB_LOCATION_PROPOSAL)) {
			if(status == null || statusOf(r) == status) {
				out.add(r);
			}
		}
		return out;
	}

	/** One proposal by objectId as {@code user}, or null. */
	public static BaseRecord readProposal(BaseRecord user, String objectId, long orgId) {
		return readAs(user, OlioModelNames.MODEL_PB_LOCATION_PROPOSAL, objectId, orgId, proposalRequest());
	}

	/// The proposal must be readable by the user and belong to this book - a proposal of another book in the
	/// organization is "not found", not "exists elsewhere".
	private static BaseRecord requireProposal(BaseRecord user, BaseRecord freshBook, BaseRecord proposal, long orgId) {
		BaseRecord p = readProposal(user, oid(proposal), orgId);
		if(p == null || PbHealthUtil.fkId(p, OlioFieldNames.FIELD_PB_BOOK) != PbHealthUtil.idOf(freshBook)) {
			throw new PictureBookException(404, "Proposal not found in this book");
		}
		return p;
	}

	/**
	 * Accept one PROPOSED item: resolve it to a location (the {@code sameAs} match when it names a known
	 * location or alias, otherwise {@link #getCreateLocation} by {@code proposedName} - which itself returns the
	 * existing record when the name or an alias is already taken), fold the proposal's aliases into an
	 * unlocked existing location, point every referenced scene of this book that {@code user} may update at
	 * it ({@code overwriteSceneLocation=false} leaves a scene that already has one alone), and mark the proposal
	 * ACCEPTED. Scenes the user may not update, or that are not this book's, are skipped and counted, never
	 * fatal.
	 *
	 * @throws PictureBookException 401/403/404 not permitted or not this book's proposal, 409 not PROPOSED or no
	 *         world, 400/500 from the location write
	 */
	public static ProposalAcceptResult acceptProposal(BaseRecord user, BaseRecord book, BaseRecord proposal, boolean overwriteSceneLocation) {
		BaseRecord freshBook = requireBookUpdate(user, book);
		BookContext bctx = requireBookContext(user, freshBook);
		long orgId = bctx.getOrganizationId();
		BaseRecord olioUser = olioUser(orgId);
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord p = requireProposal(user, freshBook, proposal, orgId);
		if(statusOf(p) != PbProposalStatusEnumType.PROPOSED) {
			throw new PictureBookException(409, "Proposal " + p.get(FieldNames.FIELD_NAME) + " is " + statusOf(p) + ", not PROPOSED");
		}
		ProposalAcceptResult res = new ProposalAcceptResult();
		String proposedName = p.get(OlioFieldNames.FIELD_PB_PROPOSED_NAME);
		String sameAs = p.get(OlioFieldNames.FIELD_PB_SAME_AS);
		List<String> aliases = p.get(OlioFieldNames.FIELD_PB_ALIASES);
		PbLocationKindEnumType kind = kindOf(p.hasField(OlioFieldNames.FIELD_PB_KIND) && p.get(OlioFieldNames.FIELD_PB_KIND) != null
			? String.valueOf((Object) p.get(OlioFieldNames.FIELD_PB_KIND)) : null);
		String reason;
		try {
			/// 1. resolve
			BaseRecord loc = null;
			if(NarrativeUtil.isMeaningful(sameAs)) {
				BaseRecord hit = findByNameOrAlias(olioUser, bctx, sameAs);
				if(hit != null) {
					loc = rereadAs(user, hit.get(FieldNames.FIELD_OBJECT_ID), orgId);
					reason = "sameAs:" + hit.get(FieldNames.FIELD_NAME);
				}
				else {
					res.warnings.add("sameAs '" + sameAs + "' no longer names a location; resolved by proposed name instead");
					reason = "created:" + proposedName;
				}
			}
			else {
				reason = "created:" + proposedName;
			}
			if(loc == null) {
				if(!NarrativeUtil.isMeaningful(proposedName)) {
					throw new PictureBookException(400, "Proposal " + p.get(FieldNames.FIELD_NAME) + " has no usable name");
				}
				BaseRecord before = findByNameOrAlias(olioUser, bctx, proposedName.trim());
				loc = getCreateLocation(user, freshBook, proposedName, p.get(FieldNames.FIELD_DESCRIPTION), kind, aliases);
				res.locationCreated = (before == null);
				if(!res.locationCreated) {
					reason = "existing:" + loc.get(FieldNames.FIELD_NAME);
				}
			}
			res.location = loc;

			/// 2. fold aliases into an existing, unlocked location (a created one already carries them)
			if(!res.locationCreated) {
				res.aliasesFolded = foldAliases(olioUser, loc, proposedName, aliases, res.warnings);
			}

			/// 3. re-point this book's scenes the user may update
			List<String> sceneIds = p.get(OlioFieldNames.FIELD_PB_SCENE_OBJECT_IDS);
			if(sceneIds != null) {
				for(String soid : sceneIds) {
					BaseRecord scene = PbBookUtil.readScene(user, soid, orgId);
					if(scene == null || PbHealthUtil.fkId(scene, OlioFieldNames.FIELD_PB_BOOK) != PbHealthUtil.idOf(freshBook)) {
						res.scenesSkipped++;
						res.warnings.add("Scene " + soid + " is not readable or not in this book; not re-pointed");
						continue;
					}
					long current = PbHealthUtil.fkId(scene, FieldNames.FIELD_LOCATION);
					if(current == PbHealthUtil.idOf(loc)) {
						continue;
					}
					if(current > 0L && !overwriteSceneLocation) {
						res.scenesSkipped++;
						continue;
					}
					PolicyResponseType prr = ioContext.getAuthorizationUtil().canUpdate(user, user, scene);
					if(prr == null || prr.getType() != PolicyResponseEnumType.PERMIT) {
						res.scenesSkipped++;
						res.warnings.add("Not permitted to update scene " + soid + "; not re-pointed");
						continue;
					}
					BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION);
					patch.set(FieldNames.FIELD_LOCATION, loc);
					if(ioContext.getAccessPoint().update(user, patch) == null) {
						res.scenesSkipped++;
						res.warnings.add("Scene " + soid + " could not be re-pointed");
						continue;
					}
					res.scenesRepointed++;
					/// the render on file is of the old place; STALE (pinned stays pinned, RUNNING waits for the run to close)
					if(PbBookUtil.markSceneNodeStale(user, scene)) {
						res.nodesMarkedStale++;
					}
					else if(PbHealthUtil.fkId(scene, OlioFieldNames.FIELD_PB_SCENE_NODE) > 0L) {
						res.warnings.add("Scene " + soid + " was re-pointed but its page node was not marked stale");
					}
				}
			}

			/// 4. the proposal itself
			BaseRecord pp = PbGraphUtil.patchOf(p, OlioModelNames.MODEL_PB_LOCATION_PROPOSAL,
				OlioFieldNames.FIELD_PB_PROPOSAL_STATUS, FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_REASON);
			pp.set(OlioFieldNames.FIELD_PB_PROPOSAL_STATUS, PbProposalStatusEnumType.ACCEPTED);
			pp.set(FieldNames.FIELD_LOCATION, loc);
			pp.set(OlioFieldNames.FIELD_PB_REASON, clip(reason, 1024));
			if(ioContext.getAccessPoint().update(user, pp) == null) {
				throw new PictureBookException(500, "Proposal " + p.get(FieldNames.FIELD_NAME) + " could not be marked ACCEPTED");
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("acceptProposal: " + e.getMessage());
			throw new PictureBookException(500, "The acceptance could not be written");
		}
		finally {
			PbStaleUtil.touchBook(user, freshBook);
		}
		res.proposal = readProposal(user, p.get(FieldNames.FIELD_OBJECT_ID), orgId);
		return res;
	}

	/// C3: an existing location learns the proposal's name and aliases unless it is locked. Overflow past
	/// MAX_ALIASES is dropped with a warning, never a failure - acceptance must not hinge on alias bookkeeping.
	private static int foldAliases(BaseRecord olioUser, BaseRecord loc, String proposedName, List<String> aliases, List<String> warnings)
			throws FieldException, ValueException, ModelNotFoundException {
		Object locked = (loc.hasField(OlioFieldNames.FIELD_PB_LOCKED) ? loc.get(OlioFieldNames.FIELD_PB_LOCKED) : null);
		if(Boolean.TRUE.equals(locked)) {
			warnings.add("Location " + loc.get(FieldNames.FIELD_NAME) + " is locked; its aliases were not extended");
			return 0;
		}
		List<String> incoming = new ArrayList<>();
		if(proposedName != null) {
			incoming.add(proposedName);
		}
		if(aliases != null) {
			incoming.addAll(aliases);
		}
		List<String> have = loc.get(OlioFieldNames.FIELD_PB_ALIASES);
		List<String> current = new ArrayList<>(have != null ? have : new ArrayList<>());
		List<String> add = normalizeAliases(incoming, loc.get(FieldNames.FIELD_NAME), current, Integer.MAX_VALUE);
		if(add.isEmpty()) {
			return 0;
		}
		int room = MAX_ALIASES - current.size();
		if(room <= 0) {
			warnings.add("Location " + loc.get(FieldNames.FIELD_NAME) + " already carries " + MAX_ALIASES + " aliases; none added");
			return 0;
		}
		if(add.size() > room) {
			warnings.add("Location " + loc.get(FieldNames.FIELD_NAME) + ": " + (add.size() - room) + " alias(es) dropped at the limit of " + MAX_ALIASES);
			add = new ArrayList<>(add.subList(0, room));
		}
		BaseRecord patch = PbGraphUtil.patchOf(loc, OlioModelNames.MODEL_PB_LOCATION, OlioFieldNames.FIELD_PB_ALIASES);
		List<String> target = patch.get(OlioFieldNames.FIELD_PB_ALIASES);
		target.clear();
		target.addAll(current);
		target.addAll(add);
		if(IOSystem.getActiveContext().getAccessPoint().update(olioUser, patch) == null) {
			warnings.add("Location " + loc.get(FieldNames.FIELD_NAME) + ": aliases could not be written");
			return 0;
		}
		List<String> onLoc = loc.get(OlioFieldNames.FIELD_PB_ALIASES);
		if(onLoc != null) {
			onLoc.clear();
			onLoc.addAll(target);
		}
		return add.size();
	}

	/**
	 * Reject one PROPOSED item with an optional reviewer note. Nothing else changes.
	 *
	 * @throws PictureBookException 401/403/404 not permitted or not this book's proposal, 409 not PROPOSED
	 */
	public static BaseRecord rejectProposal(BaseRecord user, BaseRecord book, BaseRecord proposal, String reason) {
		BaseRecord freshBook = requireBookUpdate(user, book);
		long orgId = PbGraphUtil.orgId(freshBook);
		BaseRecord p = requireProposal(user, freshBook, proposal, orgId);
		if(statusOf(p) != PbProposalStatusEnumType.PROPOSED) {
			throw new PictureBookException(409, "Proposal " + p.get(FieldNames.FIELD_NAME) + " is " + statusOf(p) + ", not PROPOSED");
		}
		BaseRecord pp = PbGraphUtil.patchOf(p, OlioModelNames.MODEL_PB_LOCATION_PROPOSAL,
			OlioFieldNames.FIELD_PB_PROPOSAL_STATUS, OlioFieldNames.FIELD_PB_REASON);
		try {
			pp.set(OlioFieldNames.FIELD_PB_PROPOSAL_STATUS, PbProposalStatusEnumType.REJECTED);
			pp.set(OlioFieldNames.FIELD_PB_REASON, clip(NarrativeUtil.isMeaningful(reason) ? reason.trim() : "rejected", 1024));
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("rejectProposal: could not assemble the rejection patch: " + e.getMessage());
			throw new PictureBookException(500, "The rejection could not be assembled");
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, pp) == null) {
			throw new PictureBookException(500, "Proposal " + p.get(FieldNames.FIELD_NAME) + " could not be marked REJECTED");
		}
		return readProposal(user, p.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	private static String clip(String s, int max) {
		if(s == null) {
			return null;
		}
		return (s.length() > max ? s.substring(0, max) : s);
	}

	// ─────────────────────────────── aliases ───────────────────────────────

	/**
	 * Trimmed, de-duplicated case-insensitively, never the location's own name, each at most
	 * {@link #MAX_ALIAS_LENGTH} characters, at most {@link #MAX_ALIASES} entries, in first-seen order.
	 */
	static List<String> normalizeAliases(List<String> aliases, String ownName, List<String> alsoExclude) {
		return normalizeAliases(aliases, ownName, alsoExclude, MAX_ALIASES);
	}

	static List<String> normalizeAliases(List<String> aliases, String ownName, List<String> alsoExclude, int max) {
		List<String> out = new ArrayList<>();
		if(aliases == null) {
			return out;
		}
		Set<String> seen = new LinkedHashSet<>();
		if(ownName != null) {
			seen.add(ownName.trim().toLowerCase(Locale.ROOT));
		}
		if(alsoExclude != null) {
			for(String x : alsoExclude) {
				if(x != null) {
					seen.add(x.trim().toLowerCase(Locale.ROOT));
				}
			}
		}
		for(String a : aliases) {
			String t = cleanText(a);
			if(!NarrativeUtil.isMeaningful(t)) {
				continue;
			}
			if(t.length() > MAX_ALIAS_LENGTH) {
				t = t.substring(0, MAX_ALIAS_LENGTH).trim();
			}
			String key = t.toLowerCase(Locale.ROOT);
			if(seen.contains(key)) {
				continue;
			}
			seen.add(key);
			out.add(t);
			if(out.size() >= max) {
				break;
			}
		}
		return out;
	}

	/**
	 * Caller text that becomes a record name or alias, and so a log line and a 4xx body: control characters
	 * (CR, LF, ESC, NUL, ...) become a single space, whitespace runs collapse, ends trimmed. Null stays null.
	 */
	static String cleanText(String s) {
		if(s == null) {
			return null;
		}
		return s.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s{2,}", " ").trim();
	}
}
