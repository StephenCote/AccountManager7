package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.RecordUtil;

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

	// ─────────────────────────────── seed a chapter's shadow cast ───────────────────────────────

	/**
	 * Seed a chapter's <b>shadow</b> cast from series baseline records, <b>within the one shared series
	 * world</b> (§3 N1 / §8 lower-priority). This is the Q6 redirect of {@link #copyToChapter}: instead of
	 * copying into a fresh per-chapter world, it copies baseline records into the series world's own group
	 * for that model (e.g. {@code population} for {@code charPerson}) and <b>enrols each clone into the
	 * chapter's book-scoped {@code shadowCastGroup}</b>. Cloning into the group alone does not tag the copy
	 * by chapter - two chapters share the one series world's Population group, so without the cast-group
	 * enrolment their shadows would be indistinguishable. The enrolment is the chapter tag.
	 * <p>
	 * <b>No per-book membership check, and that is deliberate, not an omission.</b> {@link #copyToChapter}
	 * checks {@code isBookMember} on both slugs because per-chapter worlds carried per-book role pairs. A
	 * series chapter has neither: it references the shared world and is gated by the SERIES role pair, which
	 * {@code PbBookUtil.createBook}'s {@code isEntitledToSeries} check already enforced before this runs. A
	 * second membership check here would be against roles that do not exist.
	 * <p>
	 * <b>Copy, still not reference.</b> The shadow is a fresh instance derived from the baseline, so a
	 * chapter can redress or age its own shadow without mutating the baseline or another chapter's shadow -
	 * the re-render guarantee of Q6. {@code cloneIntoGroup}'s stated sub-record limitation (see
	 * {@link #copyToChapter}) is unchanged: callers pass the records they want re-homed.
	 *
	 * @param baselineRecords the series baseline records to seed shadows from (typically {@code charPerson})
	 * @param targetGroup     the series world's own group for these records (e.g. {@code population})
	 * @param shadowCastGroup the chapter's book-scoped cast group, from
	 *                        {@link PbCastUtil#getCreateShadowCastGroup}
	 * @return the created shadow clones, in input order; a failed clone/create/enrol aborts with the count
	 *         that succeeded, and the partial seed is NOT rolled back
	 */
	public static List<BaseRecord> copyToChapterShadow(BaseRecord user, List<BaseRecord> baselineRecords,
			BaseRecord targetGroup, BaseRecord shadowCastGroup, BaseRecord targetWorkflow, BaseRecord lineageNode,
			String bindingGroupPath) {
		if(user == null || baselineRecords == null || targetGroup == null || shadowCastGroup == null) {
			throw new PictureBookException(400,
				"Seeding a chapter shadow needs a user, baseline records, a target group and a shadow cast group");
		}

		long targetGroupId = ((Number) targetGroup.get(FieldNames.FIELD_ID)).longValue();

		List<BaseRecord> out = new ArrayList<>();
		for(BaseRecord baseline : baselineRecords) {
			/// Deep-populate the baseline before cloning. The sources handed in were read with a minimal
			/// projection (createChapter's findByObjectId), so their render-state sub-records - narrative,
			/// profile.portrait, store.apparel - are not present; cloneIntoGroup would then copy an empty
			/// graph and the shadow would carry no render state. getFullRecord applies OlioUtil.planMost, the
			/// canonical deep read for Olio objects. It is an internal re-read of a record already authorized
			/// as this user in createChapter, so the unauthorized search is the established Olio pattern here.
			BaseRecord full = OlioUtil.getFullRecord(baseline);
			BaseRecord toClone = (full != null ? full : baseline);
			BaseRecord clone = OlioUtil.cloneIntoGroup(toClone, targetGroup);
			if(clone == null) {
				throw new PictureBookException(500, "Seeded " + out.size() + " shadow(s), then failed to clone "
					+ baseline.getSchema() + " " + baseline.get(FieldNames.FIELD_OBJECT_ID)
					+ " - the shadow seed is partially applied and was NOT rolled back");
			}
			/// HIGH gotcha: cloneIntoGroup deidentifies EVERY nested record, including the shared universe
			/// library records a wearable only REFERENCES (its colours, pattern and traits — olio.item's
			/// foreign data.color/data.data/data.trait fields). A deidentified copy has no id, so the shadow
			/// create tries to auto-INSERT it; for data.color that hits the (name, groupId, organizationId)
			/// unique constraint against the pre-seeded library row, the batch aborts, and the wearable's
			/// colour FK is left null — which then renders as the literal "null" in the SD prompt. These are
			/// SHARED library rows, not per-chapter render state, so restore them to the source's real records
			/// (with their ids) BEFORE re-homing/create: the wearable then references the library row by id
			/// instead of re-inserting a duplicate. This is the deliberate exception to "chapters copy, they
			/// do not reference": the owned graph is copied, the universe library it points at is referenced.
			restoreSharedItemRefs(toClone, clone, 0);
			/// HIGH gotcha: cloneIntoGroup re-homes only the TOP-LEVEL groupId (OlioUtil.java:596). Every
			/// nested sub-record copyDeidentifiedRecord produced is a FRESH copy (all identity fields stripped,
			/// BaseRecord.java:104-126) that still carries the SOURCE group's groupId. Left there, a chapter's
			/// shadow narrative/portrait/apparel would live in the series baseline Population group - so
			/// overwriting a shadow, or deleting the chapter, would reach into baseline data. Re-home the whole
			/// copied graph into the shadow group before create; the create then cascades the nested records
			/// (the named participation/reverse-reference exception) into that one group. Restored shared
			/// library refs are identity records and are skipped by rehome — they are referenced, not moved.
			rehomeSubRecords(clone, targetGroupId, 0);
			BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, clone);
			if(created == null) {
				throw new PictureBookException(500, "Seeded " + out.size()
					+ " shadow(s), then failed to create a shadow copy of " + baseline.getSchema() + " "
					+ baseline.get(FieldNames.FIELD_OBJECT_ID)
					+ " - the shadow seed is partially applied and was NOT rolled back");
			}
			if(!PbCastUtil.enrollCastMember(shadowCastGroup, created)) {
				throw new PictureBookException(500, "Created a shadow copy of " + baseline.getSchema() + " "
					+ baseline.get(FieldNames.FIELD_OBJECT_ID) + " but failed to enrol it into the chapter's shadow cast '"
					+ shadowCastGroup.get(FieldNames.FIELD_NAME)
					+ "' - the shadow is untagged and would be indistinguishable from other chapters' shadows");
			}
			out.add(created);
			if(lineageNode != null && targetWorkflow != null && bindingGroupPath != null) {
				recordLineage(user, targetWorkflow, lineageNode, ROLE_CHAPTER_SOURCE, baseline, bindingGroupPath);
			}
		}
		return out;
	}

	// ─────────────────────────────── merge baseline updates into shadows ───────────────────────────────

	/**
	 * Merge series baseline updates into existing chapter shadows (Q6 sync op "merge"): PULL the baseline's
	 * scalar (column-backed) attributes into each shadow while KEEPING the shadow's own chapter-specific
	 * overrides - its foreign/nested render state (store.apparel, state/pose, narrative, profile.portrait,
	 * statistics, instinct, traits, colours) and referenced attributes. This is the counterpart to
	 * {@link #copyToChapterShadow}: copy/recopy reseed a shadow wholesale from the baseline, merge only
	 * overlays the shared attributes and leaves the chapter's edits standing.
	 * <p>
	 * <b>The shadow keeps its identity, including its name.</b> Scene-to-character links in the PictureBook
	 * flow resolve BY NAME at render time ({@code resolveSceneCharacter} -> {@code findSceneCharacterGroups},
	 * shadow-group-first), so the patch is built with {@link PbGraphUtil#patchOf} against the SHADOW (identity
	 * and name taken from the shadow) and only its scalar values are overwritten with the baseline's. A merge
	 * therefore never re-points a scene link and never renames a shadow.
	 * <p>
	 * <b>Only NON-NULL baseline scalars are pulled, and only those fields are materialised on the patch.</b>
	 * The field-name {@code newInstance} overload materialises exactly the named fields and the writer
	 * persists every field present on the patch record (see {@link PbGraphUtil#patchOf}), so materialising a
	 * field the baseline left null would blank the shadow's own value. The set of fields to patch is therefore
	 * computed per shadow from the baseline's actually-present scalars - a baseline that never set
	 * {@code hairStyle} leaves the shadow's {@code hairStyle} alone. (MY JUDGMENT: pull = overwrite-from-non-null;
	 * a baseline null is treated as "no opinion", not "clear the shadow".)
	 * <p>
	 * <b>Matched by case-insensitive trimmed name</b>, the same key the render path resolves on. A shadow with
	 * no baseline counterpart (a character added only to this chapter) is left untouched. The baseline is
	 * deep-read with {@code OlioUtil.getFullRecord} so its scalar columns are populated - the cast-member list
	 * projects only identity fields - which is an internal re-read of a record already authorized as this user
	 * via the cast list. Writes as the acting {@code user} through {@code AccessPoint.update} and asserts the
	 * result, so a silent write failure cannot pass for success; a failure aborts with the count that merged
	 * and the partial merge is NOT rolled back.
	 *
	 * @param shadowMembers   the chapter's shadow {@code charPerson}s (from {@code listCastMembers} on the shadow cast)
	 * @param baselineMembers the series baseline {@code charPerson}s (from {@code listCastMembers} on the baseline cast)
	 * @return the number of shadows merged (a baseline match found and at least one scalar pulled)
	 */
	public static int mergeChapterShadows(BaseRecord user, List<BaseRecord> shadowMembers,
			List<BaseRecord> baselineMembers) {
		if(user == null || shadowMembers == null || baselineMembers == null) {
			throw new PictureBookException(400,
				"Merging chapter shadows needs a user, shadow members and baseline members");
		}
		/// Index baselines by case-insensitive trimmed name - the same key resolveSceneCharacter resolves on.
		Map<String, BaseRecord> baselineByName = new HashMap<>();
		for(BaseRecord b : baselineMembers) {
			String nm = b.get(FieldNames.FIELD_NAME);
			if(nm != null && nm.trim().length() > 0) {
				baselineByName.put(nm.trim().toLowerCase(), b);
			}
		}

		List<String> scalarFields = scalarPullFields(OlioModelNames.MODEL_CHAR_PERSON);
		int merged = 0;
		for(BaseRecord shadow : shadowMembers) {
			String snm = shadow.get(FieldNames.FIELD_NAME);
			if(snm == null || snm.trim().length() == 0) {
				continue;
			}
			BaseRecord baseline = baselineByName.get(snm.trim().toLowerCase());
			if(baseline == null) {
				/// A character present only in this chapter's shadow cast has no baseline to pull from.
				continue;
			}
			/// Deep-read the baseline so its scalar columns are populated - listCastMembers projects only
			/// id/objectId/name/groupId/organizationId. getFullRecord is the canonical Olio deep read.
			BaseRecord full = OlioUtil.getFullRecord(baseline);
			BaseRecord src = (full != null ? full : baseline);

			/// Only pull scalars the baseline actually holds - materialising a field the baseline left null
			/// would blank the shadow's value when the writer persists the patch.
			List<String> pull = new ArrayList<>();
			for(String fn : scalarFields) {
				if(src.get(fn) != null) {
					pull.add(fn);
				}
			}
			if(pull.isEmpty()) {
				continue;
			}
			BaseRecord patch = PbGraphUtil.patchOf(shadow, OlioModelNames.MODEL_CHAR_PERSON,
				pull.toArray(new String[0]));
			try {
				for(String fn : pull) {
					patch.set(fn, src.get(fn));
				}
			}
			catch(FieldException | ValueException | ModelNotFoundException e) {
				throw new PictureBookException(500, "Merged " + merged
					+ " shadow(s), then failed to assemble a merge patch for '" + snm + "': " + e.getMessage());
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				throw new PictureBookException(500, "Merged " + merged + " shadow(s), then failed to update shadow '"
					+ snm + "' - the merge is partially applied and was NOT rolled back");
			}
			merged++;
		}
		return merged;
	}

	/**
	 * The scalar (column-backed) {@code charPerson} fields safe to PULL from a baseline into a shadow on
	 * merge: non-identity, non-foreign, non-virtual, non-ephemeral, non-referenced, scalar-typed, and
	 * excluding the fields that pin a record's identity or placement ({@code name}, {@code groupId},
	 * {@code organizationId}, {@code ownerId}, {@code groupPath}). The KEEP set - every foreign/nested/
	 * participation render-state field and referenced attribute - is exactly the complement, so a merge
	 * pulls shared attributes without disturbing the chapter's own apparel/state/pose overrides. Schema
	 * driven so a scalar attribute added to the model is pulled without an edit here.
	 */
	private static List<String> scalarPullFields(String model) {
		List<String> out = new ArrayList<>();
		ModelSchema ms = RecordFactory.getSchema(model);
		if(ms == null) {
			return out;
		}
		Set<String> exclude = new HashSet<>(Arrays.asList(
			FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_GROUP_PATH));
		for(FieldSchema fs : ms.getFields()) {
			String fn = fs.getName();
			if(fn == null || exclude.contains(fn)) {
				continue;
			}
			if(fs.isIdentity() || fs.isForeign() || fs.isVirtual() || fs.isEphemeral() || fs.isReferenced()) {
				continue;
			}
			if(isScalarPullType(fs.getFieldType())) {
				out.add(fn);
			}
		}
		return out;
	}

	/** True for the field types a merge pulls by value - primitives, strings, enums and timestamps. MODEL,
	 *  LIST and BLOB are excluded: those carry the chapter's own render-state overrides (KEEP), not shared
	 *  scalar attributes. */
	private static boolean isScalarPullType(FieldEnumType t) {
		if(t == null) {
			return false;
		}
		switch(t) {
			case STRING:
			case ENUM:
			case INT:
			case LONG:
			case DOUBLE:
			case BOOLEAN:
			case ZONETIME:
			case TIMESTAMP:
				return true;
			default:
				return false;
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	/** The deepest sub-record graph re-homing will descend, mirroring the effective depth of a fully
	 *  planned charPerson graph and bounding any accidental cycle in populated data. */
	private static final int REHOME_MAX_DEPTH = 12;

	/**
	 * The shared universe-library models an {@code olio.item} (a wearable) only REFERENCES, never owns:
	 * its colours ({@code data.color}), its pattern ({@code data.data}) and its perks/features
	 * ({@code data.trait}). These rows are seeded once into the Books universe library and are shared across
	 * every character and chapter, so a chapter shadow must point back at the existing rows by id rather than
	 * clone them. {@code olio.itemStatistics} is deliberately absent: it is per-item render state the shadow
	 * OWNS and therefore copies. Kept as a set (not per-field names) so it is driven by each field's
	 * {@code baseModel}, and a new shared-library foreign field on {@code olio.item} is covered without an edit.
	 */
	private static final Set<String> SHARED_LIBRARY_ITEM_MODELS = new HashSet<>(Arrays.asList(
		ModelNames.MODEL_COLOR, ModelNames.MODEL_DATA, ModelNames.MODEL_TRAIT));

	/**
	 * Restore, on a freshly-cloned graph, the shared universe-library references that
	 * {@code OlioUtil.cloneIntoGroup} -> {@code BaseRecord.copyDeidentifiedRecord} stripped of their
	 * identity.
	 * <p>
	 * <b>Why this exists.</b> {@code copyDeidentifiedRecord} (BaseRecord.java:104-126) deep-copies EVERY
	 * nested {@code MODEL}/list-of-{@code MODEL} field and strips all identity fields from each copy - it
	 * cannot tell an OWNED sub-record (a character's narrative, portrait, statistics) from a mere REFERENCE
	 * to a shared library row (a wearable's colour). For owned records that is exactly right - the shadow
	 * needs its own copy. For the shared library rows it is a defect: a colour copy with no id is auto-INSERTed
	 * on create ({@code DBWriter.applyAutoCreateList} creates a foreign child only when
	 * {@code !RecordUtil.isIdentityRecord}), and the insert collides with the pre-seeded library row on
	 * {@code data.color}'s {@code (name, groupId, organizationId)} unique constraint. The batch aborts, the
	 * wearable's colour FK is left null, and {@code NarrativeUtil.describeWearable} then emits the literal
	 * "null" into the SD prompt.
	 * <p>
	 * <b>What it does.</b> Walks {@code source} (the identity-bearing baseline read via
	 * {@code OlioUtil.getFullRecord}) and {@code clone} (its deidentified copy) in lockstep. For any record
	 * that inherits {@code olio.item}, every foreign field whose {@code baseModel} is a
	 * {@link #SHARED_LIBRARY_ITEM_MODELS shared library model} is re-pointed at the SOURCE's original
	 * record(s) - which still carry ids - so the create references the existing library row instead of
	 * re-inserting a duplicate. All OTHER foreign fields (owned sub-records, and the container fields that
	 * hold the wearables such as {@code store}/{@code store.apparel}) are recursed into so the wearables are
	 * reached; nothing owned is re-pointed. Best-effort per field: a restore that throws is logged and
	 * skipped, and the collision it fails to prevent then surfaces loudly as a create failure rather than
	 * passing silently.
	 */
	private static void restoreSharedItemRefs(BaseRecord source, BaseRecord clone, int depth) {
		if(source == null || clone == null || depth >= REHOME_MAX_DEPTH) {
			return;
		}
		ModelSchema ms = RecordFactory.getSchema(clone.getSchema());
		if(ms == null) {
			return;
		}
		boolean isItem = clone.inherits(OlioModelNames.MODEL_ITEM);
		for(FieldType f : clone.getFields()) {
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if(fs == null || !fs.isForeign()) {
				continue;
			}
			boolean shared = isItem && SHARED_LIBRARY_ITEM_MODELS.contains(fs.getBaseModel());
			if(f.getValueType() == FieldEnumType.MODEL) {
				Object sv = source.get(f.getName());
				if(shared) {
					/// Re-point the clone's stripped copy at the source's identity-bearing library record.
					if(sv instanceof BaseRecord && RecordUtil.isIdentityRecord((BaseRecord) sv)) {
						setRef(clone, f.getName(), sv);
					}
				}
				else if(sv instanceof BaseRecord && f.getValue() instanceof BaseRecord) {
					/// Owned sub-record (or a container like store/profile) - recurse to reach the wearables.
					restoreSharedItemRefs((BaseRecord) sv, (BaseRecord) f.getValue(), depth + 1);
				}
			}
			else if(f.getValueType() == FieldEnumType.LIST && ModelNames.MODEL_MODEL.equals(fs.getBaseType())) {
				Object sv = source.get(f.getName());
				if(shared) {
					/// Re-point the whole list (perks/features) at the source's identity-bearing traits, but
					/// ONLY when every source element carries an id - symmetric with the MODEL branch's
					/// isIdentityRecord gate. A source trait lacking an id would otherwise be re-pointed and
					/// then treated as an owned copy by rehomeSubRecords (its identity-skip guard wouldn't
					/// skip it), which would try to create it. If any element lacks an id, leave the clone's
					/// deidentified copies so the anomaly surfaces loudly on create rather than silently here.
					if(sv instanceof List && !((List<?>) sv).isEmpty()) {
						boolean allIdentity = true;
						for(Object o : (List<?>) sv) {
							if(!(o instanceof BaseRecord) || !RecordUtil.isIdentityRecord((BaseRecord) o)) {
								allIdentity = false;
								break;
							}
						}
						if(allIdentity) {
							setRef(clone, f.getName(), sv);
						}
						else {
							logger.warn("Not restoring shared library list reference " + clone.getSchema() + "."
								+ f.getName() + " - one or more source elements lack an identity");
						}
					}
				}
				else if(sv instanceof List && f.getValue() instanceof List) {
					/// Owned list (e.g. store.apparel) - recurse into each element in lockstep by index.
					List<?> sl = (List<?>) sv;
					List<?> cl = (List<?>) f.getValue();
					int n = Math.min(sl.size(), cl.size());
					for(int i = 0; i < n; i++) {
						if(sl.get(i) instanceof BaseRecord && cl.get(i) instanceof BaseRecord) {
							restoreSharedItemRefs((BaseRecord) sl.get(i), (BaseRecord) cl.get(i), depth + 1);
						}
					}
				}
			}
		}
	}

	/** Set a foreign reference on {@code rec}, best-effort; a failed restore is logged, not fatal - the
	 *  collision it would have prevented then surfaces loudly as a create failure. */
	private static void setRef(BaseRecord rec, String field, Object value) {
		try {
			rec.set(field, value);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.warn("Could not restore shared library reference " + rec.getSchema() + "." + field
				+ ": " + e.getMessage());
		}
	}

	/**
	 * Re-home every nested sub-record of a freshly-cloned graph into {@code groupId}, recursively.
	 * <p>
	 * Walks the same fields {@code copyDeidentifiedRecord} recursed into - populated foreign {@code MODEL}
	 * fields and foreign lists of {@code MODEL} - and sets {@code groupId} on any sub-record whose schema
	 * declares one ({@code common.groupExt}: {@code narrative}, {@code store}+{@code store.apparel},
	 * {@code profile}+{@code profile.portrait}+{@code album}, and any statistics/instinct the character
	 * owns). Only {@code groupId} is set: it is the persisted column, while {@code groupPath} is a virtual
	 * {@code PathProvider} field recomputed from the group on read, so setting it would be cosmetic.
	 * <p>
	 * <b>Safe against shared-template contamination.</b> {@code copyDeidentifiedRecord} makes a fresh copy
	 * of every populated nested record (all identity fields stripped), so nothing here mutates a shared
	 * universe record - it only stamps the group onto the new owned copies about to be created.
	 */
	private static void rehomeSubRecords(BaseRecord rec, long groupId, int depth) {
		if(rec == null || depth >= REHOME_MAX_DEPTH) {
			return;
		}
		ModelSchema ms = RecordFactory.getSchema(rec.getSchema());
		if(ms == null) {
			return;
		}
		for(FieldType f : rec.getFields()) {
			FieldSchema fs = ms.getFieldSchema(f.getName());
			if(fs == null || !fs.isForeign()) {
				continue;
			}
			if(f.getValueType() == FieldEnumType.MODEL) {
				Object v = f.getValue();
				if(v instanceof BaseRecord) {
					BaseRecord child = (BaseRecord) v;
					/// A restored shared-library reference (restoreSharedItemRefs) is an identity record - the
					/// existing library row, not an owned copy about to be created. Do not re-home or descend
					/// into it: it belongs to the shared universe library, not this chapter's group.
					if(RecordUtil.isIdentityRecord(child)) {
						continue;
					}
					applyGroupId(child, groupId);
					rehomeSubRecords(child, groupId, depth + 1);
				}
			}
			else if(f.getValueType() == FieldEnumType.LIST && ModelNames.MODEL_MODEL.equals(fs.getBaseType())) {
				Object v = f.getValue();
				if(v instanceof List) {
					for(Object o : (List<?>) v) {
						if(o instanceof BaseRecord) {
							BaseRecord child = (BaseRecord) o;
							/// Skip restored shared-library references (identity records): perks/features point
							/// back at existing library traits, which must not be re-homed into this group.
							if(RecordUtil.isIdentityRecord(child)) {
								continue;
							}
							applyGroupId(child, groupId);
							rehomeSubRecords(child, groupId, depth + 1);
						}
					}
				}
			}
		}
	}

	/** Set {@code groupId} on {@code rec} when its schema declares one; best-effort and never fatal. */
	private static void applyGroupId(BaseRecord rec, long groupId) {
		ModelSchema ms = RecordFactory.getSchema(rec.getSchema());
		if(ms == null || ms.getFieldSchema(FieldNames.FIELD_GROUP_ID) == null) {
			return;
		}
		try {
			rec.set(FieldNames.FIELD_GROUP_ID, groupId);
		}
		catch(Exception e) {
			logger.warn("Could not re-home " + rec.getSchema() + " into group " + groupId + ": " + e.getMessage());
		}
	}

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
