package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.RoleEnumType;

/**
 * Book membership and the two chapter mechanisms (§3.5). <b>Every method here is an explicit, authorized
 * WRITE. None is reachable from a read path.</b>
 * <p>
 * <b>Membership is two-tier, and both tiers are mandatory.</b> Since the phase-2a split the per-book
 * {@code Writer}/{@code Admin} roles hold <i>nothing</i> on the {@code Books} universe, so a user given
 * only the book role cannot read the apparel templates, colours and word lists the pipeline needs. This is
 * exactly the bound Appendix D carried forward from case14: opening an existing book enrols nobody, so the
 * sharing flow has to do both tiers itself. {@link #shareBook} does, and refuses to do half of it.
 * <p>
 * <b>Add-by-writer-only, with no request/approval trail</b> (ratification 16 / Q18). The
 * {@code access.accessRequest} backend exists but has no UI, so book sharing is a direct grant by someone
 * who already holds the book. Every enrolment is audited by {@code OlioContext.register}, which is where
 * the authorization check and the org-scope check live - this class does not re-implement either.
 * <p>
 * <b>Chapters copy, they do not reference</b> (§3.5). Chapter 2 must be able to age or redress a character
 * without mutating chapter 1, and {@code deleteGroupRecursive}'s own comment
 * ({@code PictureBookUtil.java:4243-4245}) relies on a character's sub-records being created fresh per
 * character - so sharing them across chapters would make a chapter-1 delete destroy chapter-2 data.
 * Copying uses the canonical {@code OlioUtil.cloneIntoGroup}, and narratives go through
 * {@code NarrativeUtil.getCreateNarrative}, not a hand-rolled writer.
 */
public class PbSharingUtil {
	public static final Logger logger = LogManager.getLogger(PbSharingUtil.class);

	/** Binding role recording that a record was promoted up to the universe from a book world. */
	public static final String ROLE_PROMOTED_FROM = "promotedFrom";

	/** Binding role recording that a record was copied in from the previous chapter's world. */
	public static final String ROLE_CHAPTER_SOURCE = "chapterSource";

	private PbSharingUtil() {
		/// static utility
	}

	// ─────────────────────────────── membership ───────────────────────────────

	/**
	 * Enrol {@code target} in a book, in <b>both</b> tiers: the book's {@code Writer} (or {@code Admin})
	 * role and the organization-wide universe {@code Reader} role.
	 * <p>
	 * The authorization check is {@code OlioContext.register}'s, not a re-implementation: {@code actor}
	 * must be the organization admin or a member of the book's {@code Admin} role for the book tier, and
	 * of the universe {@code Writer} role (or org admin) for the universe tier. The context is built by
	 * {@code getCreateBookContext}, which for an existing book takes its "authorize the caller against the
	 * existing book" branch and enrols nobody - so this method is the only thing doing the enrolling, and
	 * it is doing it deliberately.
	 * <p>
	 * <b>Both-or-fail.</b> A target left in the book role without the universe role holds a book they
	 * cannot generate anything in, and grants are add-only so nothing would clean it up. If the second
	 * enrolment fails this throws, saying which half landed.
	 *
	 * @param asAdmin enrol into the book {@code Admin} role rather than {@code Writer}
	 * @return true when both tiers are in place
	 */
	public static boolean shareBook(BaseRecord actor, BaseRecord target, String dataPath, String bookSlug, boolean asAdmin) {
		if(actor == null || target == null) {
			throw new PictureBookException(400, "Sharing a book needs an actor and a target user");
		}
		OlioContext ctx = null;
		try {
			PbOlioContextUtil.validateBookSlug(bookSlug);
			ctx = PbOlioContextUtil.getCreateBookContext(actor, dataPath, bookSlug);
		}
		catch(OlioException e) {
			/// getCreateBookContext refuses an actor who is not entitled to an existing book, which is
			/// precisely the check that stops an outsider sharing somebody else's book with themselves.
			throw new PictureBookException(403, "Cannot share book '" + bookSlug + "': " + e.getMessage(), e);
		}

		boolean bookTier = false;
		try {
			bookTier = ctx.registerUser(actor, target, asAdmin);
		}
		catch(OlioException e) {
			throw new PictureBookException(403, "Not authorized to enrol " + target.get(FieldNames.FIELD_NAME)
				+ " in book '" + bookSlug + "': " + e.getMessage(), e);
		}
		if(!bookTier) {
			throw new PictureBookException(500, "Failed to enrol " + target.get(FieldNames.FIELD_NAME)
				+ " in the book tier of '" + bookSlug + "'");
		}

		boolean universeTier = false;
		try {
			universeTier = ctx.registerUniverseUser(actor, target, false);
		}
		catch(OlioException e) {
			throw new PictureBookException(403, "Enrolled " + target.get(FieldNames.FIELD_NAME)
				+ " in the book tier of '" + bookSlug + "' but NOT the universe tier: " + e.getMessage()
				+ ". They hold the book and cannot read the corpora it needs.", e);
		}
		if(!universeTier) {
			throw new PictureBookException(500, "Enrolled " + target.get(FieldNames.FIELD_NAME)
				+ " in the book tier of '" + bookSlug + "' but NOT the universe tier."
				+ " They hold the book and cannot read the corpora it needs.");
		}
		logger.info("Shared book '" + bookSlug + "' with " + target.get(FieldNames.FIELD_NAME)
			+ " (" + (asAdmin ? "admin" : "writer") + " + universe reader) at the request of "
			+ actor.get(FieldNames.FIELD_NAME));
		return true;
	}

	/**
	 * Is {@code user} a member of the book's {@code Writer} or {@code Admin} role?
	 * <p>
	 * A find-only read. It answers a membership question and must never be mistaken for the
	 * authorization decision on a record - that is {@code AccessPoint}'s, per group entitlement.
	 */
	public static boolean isBookMember(BaseRecord user, String bookSlug) {
		if(user == null || bookSlug == null) {
			return false;
		}
		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext octx = ioContext.findOrganizationContext(user);
		if(octx == null) {
			return false;
		}
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, octx.getOrganizationId());
		if(olioUser == null) {
			return false;
		}
		for(String rolePath : new String[] {
				PbOlioContextUtil.writerRolePath(bookSlug), PbOlioContextUtil.adminRolePath(bookSlug)}) {
			BaseRecord role = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE, rolePath,
				RoleEnumType.USER.toString(), octx.getOrganizationId());
			if(role != null && ioContext.getMemberUtil().isMember(user, role, null)) {
				return true;
			}
		}
		return false;
	}

	// ─────────────────────────────── promote up to the universe ───────────────────────────────

	/**
	 * Copy a book-world record up into the shared {@code Books} universe, leaving the original intact
	 * (§3.5 "share up to universe").
	 * <p>
	 * Requires membership of the universe {@code Writer} role - the tier that actually holds
	 * Create/Update on the universe's corpora. Nothing auto-enrols anybody there, which is what makes the
	 * check meaningful rather than decorative.
	 * <p>
	 * Uses the existing {@code OlioUtil.cloneIntoGroup} (already the pattern at
	 * {@code GridSquareLocationInitializationRule.java:87}), then records the lineage as a binding with
	 * {@code role="promotedFrom"} on the promoting node, so the workflow view can show where a shared
	 * asset came from.
	 *
	 * @param targetGroup the universe group to promote into
	 * @param lineageNode the node to hang the {@code promotedFrom} binding on, or null to skip the lineage
	 * @return the promoted copy, read back as {@code user}
	 */
	public static BaseRecord promoteToUniverse(BaseRecord user, BaseRecord record, BaseRecord targetGroup,
			BaseRecord workflow, BaseRecord lineageNode, String bindingGroupPath) {
		if(user == null || record == null || targetGroup == null) {
			throw new PictureBookException(400, "Promoting needs a user, a record and a target universe group");
		}
		requireUniverseWriter(user);

		BaseRecord clone = OlioUtil.cloneIntoGroup(record, targetGroup);
		if(clone == null) {
			throw new PictureBookException(500, "Failed to clone " + record.getSchema() + " "
				+ record.get(FieldNames.FIELD_OBJECT_ID) + " into " + targetGroup.get(FieldNames.FIELD_NAME));
		}
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, clone);
		if(created == null) {
			throw new PictureBookException(500, "Failed to create the promoted copy of " + record.getSchema()
				+ " in " + targetGroup.get(FieldNames.FIELD_NAME));
		}
		if(lineageNode != null && workflow != null && bindingGroupPath != null) {
			recordLineage(user, workflow, lineageNode, ROLE_PROMOTED_FROM, record, bindingGroupPath);
		}
		return created;
	}

	// ─────────────────────────────── copy to the next chapter ───────────────────────────────

	/**
	 * Copy records from one book's world into another's (§3.5 "copy to adjacent world").
	 * <p>
	 * <b>Requires Writer on BOTH books</b> - a one-sided check would let a writer of chapter 2 pull
	 * records out of a chapter 1 they have no claim to.
	 * <p>
	 * <b>Copy, not reference, and that is the point.</b> Chapter 2 must be able to age or redress a
	 * character without mutating chapter 1. It is also a correctness requirement, not a preference:
	 * {@code deleteGroupRecursive} relies on a character's foreign sub-records having been created fresh
	 * per character ({@code PictureBookUtil.java:4243-4245}), so a shared instance would make deleting
	 * chapter 1 destroy chapter 2's data.
	 * <p>
	 * <b>Stated limit of this implementation:</b> {@code cloneIntoGroup} uses
	 * {@code copyDeidentifiedRecord}, which recurses into nested {@code model} and {@code model}-list
	 * fields but leaves each copy in the SOURCE group unless it is itself re-grouped. Callers copying a
	 * character therefore pass the sub-records they want re-homed explicitly, per model; this method does
	 * not silently pretend to have re-homed a graph it did not walk. Phase 3 is where the per-model
	 * sub-record routing lands, next to the pipeline that knows which groups those are.
	 *
	 * @param records the source records to copy
	 * @param targetGroup the destination group in the target book's world
	 * @return the created copies, in input order; a failed copy aborts with the count that succeeded
	 */
	public static List<BaseRecord> copyToChapter(BaseRecord user, String fromSlug, String toSlug,
			List<BaseRecord> records, BaseRecord targetGroup, BaseRecord targetWorkflow, BaseRecord lineageNode,
			String bindingGroupPath) {
		if(user == null || records == null || targetGroup == null) {
			throw new PictureBookException(400, "Copying to a chapter needs a user, records and a target group");
		}
		if(!isBookMember(user, fromSlug)) {
			throw new PictureBookException(403, "Not a member of the source book '" + fromSlug + "'");
		}
		if(!isBookMember(user, toSlug)) {
			throw new PictureBookException(403, "Not a member of the target book '" + toSlug + "'");
		}

		List<BaseRecord> out = new ArrayList<>();
		for(BaseRecord src : records) {
			BaseRecord clone = OlioUtil.cloneIntoGroup(src, targetGroup);
			if(clone == null) {
				throw new PictureBookException(500, "Copied " + out.size() + " record(s), then failed to clone "
					+ src.getSchema() + " " + src.get(FieldNames.FIELD_OBJECT_ID)
					+ " - the copy is partially applied and was NOT rolled back");
			}
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, clone);
			if(created == null) {
				throw new PictureBookException(500, "Copied " + out.size() + " record(s), then failed to create a copy of "
					+ src.getSchema() + " " + src.get(FieldNames.FIELD_OBJECT_ID)
					+ " - the copy is partially applied and was NOT rolled back");
			}
			out.add(created);
			if(lineageNode != null && targetWorkflow != null && bindingGroupPath != null) {
				recordLineage(user, targetWorkflow, lineageNode, ROLE_CHAPTER_SOURCE, src, bindingGroupPath);
			}
		}
		return out;
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	/**
	 * Record where a copied record came from, as a binding on the consuming node. The binding carries the
	 * ORIGINAL's model and objectId, so the lineage survives even if the copy is later edited.
	 */
	private static BaseRecord recordLineage(BaseRecord user, BaseRecord workflow, BaseRecord node, String role,
			BaseRecord source, String bindingGroupPath) {
		int ordinal = PbGraphUtil.listBindings(user, node).size();
		return PbGraphUtil.addRecordBinding(user, workflow, node, role, ordinal, source.getSchema(),
			source.get(FieldNames.FIELD_OBJECT_ID), bindingGroupPath);
	}

	/**
	 * Assert membership of the universe {@code Writer} role, the tier holding Create/Update on the
	 * universe corpora. Nothing enrols anybody there automatically, by design.
	 */
	private static void requireUniverseWriter(BaseRecord user) {
		IOContext ioContext = IOSystem.getActiveContext();
		OrganizationContext octx = ioContext.findOrganizationContext(user);
		if(octx == null) {
			throw new PictureBookException(500, "Failed to find an organization context");
		}
		BaseRecord orgAdmin = octx.getAdminUser();
		if(orgAdmin != null && orgAdmin.get(FieldNames.FIELD_ID) != null
			&& orgAdmin.get(FieldNames.FIELD_ID).equals(user.get(FieldNames.FIELD_ID))) {
			return;
		}
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, octx.getOrganizationId());
		if(olioUser == null) {
			throw new PictureBookException(403, "No olio principal in this organization, so no universe role exists");
		}
		BaseRecord role = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_ROLE,
			PbOlioContextUtil.universeWriterRolePath(), RoleEnumType.USER.toString(), octx.getOrganizationId());
		if(role == null || !ioContext.getMemberUtil().isMember(user, role, null)) {
			throw new PictureBookException(403, "Promoting to the shared universe requires membership of "
				+ PbOlioContextUtil.universeWriterRolePath());
		}
	}
}
