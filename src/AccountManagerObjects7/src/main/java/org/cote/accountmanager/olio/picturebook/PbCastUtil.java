package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;

/**
 * The {@code olio.pb.castGroup} tier of the series/chapters model (N1, Q6).
 * <p>
 * One shared series world holds a <b>canonical baseline cast</b> — one {@code charPerson} per
 * character, the shared identity and default look — grouped by a <b>series-scoped</b> cast group,
 * plus <b>per-chapter shadow copies</b> derived from that baseline, each grouped by a
 * <b>book-scoped</b> cast group that tags the chapter it belongs to. Both tiers of cast group live in
 * the SAME group — the series world's {@code Book} group — so the distinguishing key is the cast
 * group's <b>name</b> (and its {@code series}/{@code book} FK), never its {@code groupId}. That is why
 * {@link #baselineCastGroupName(String)} and {@link #shadowCastGroupName(String)} are deliberately
 * distinct: the model's {@code (name, groupId, organizationId)} constraint would otherwise collide
 * across the two tiers in the one shared world.
 * <p>
 * <b>Ownership follows the rest of the book world: the olio principal.</b> Cast group rows and the
 * {@code members} participations are written through {@code RecordUtil.createRecord} /
 * {@code MemberUtil.member} as the olio principal, exactly as {@code PbBookUtil.writeBookRow} writes
 * the book row and {@code PbSubRecordUtil} writes world-group sub-records. The series role pair does
 * hold CRUD on the series world's {@code Book} group (the world-tier grant pass with
 * {@code scanNestedWorldGroups}), so an authorized create as the acting user would also succeed — but
 * uniform olio-principal ownership avoids any dependence on grant timing and keeps every world-group
 * record owned the same way. Every READ, by contrast, goes through {@code AccessPoint} as the acting
 * user (with an explicit {@code groupId} condition so PBAC gets its group-only shortcut), which also
 * proves the acting user can actually see the cast — a cast group the series creator cannot read is a
 * failed create, not a successful one.
 */
public class PbCastUtil {
	public static final Logger logger = LogManager.getLogger(PbCastUtil.class);

	private PbCastUtil() {
		/// static utility
	}

	// ─────────────────────────────── names ───────────────────────────────

	/**
	 * The series-scoped BASELINE cast group's name. Includes the series slug so it is unique within the
	 * shared {@code Book} group and cannot collide with a per-chapter shadow cast group's name.
	 */
	public static String baselineCastGroupName(String seriesSlug) {
		return "Baseline Cast " + seriesSlug;
	}

	/**
	 * A per-chapter SHADOW cast group's name. Includes the chapter book slug (unique per organization),
	 * so two chapters never share a shadow cast group and the {@code (name, groupId, organizationId)}
	 * constraint is safe even though every shadow group lives in the one shared {@code Book} group.
	 */
	public static String shadowCastGroupName(String bookSlug) {
		return "Chapter Cast " + bookSlug;
	}

	// ─────────────────────────────── create-or-get ───────────────────────────────

	/**
	 * Get-or-create the series-scoped baseline cast group in {@code castGroupPath} (the series world's
	 * {@code Book} group). The {@code series} FK is set and {@code book} is left null.
	 *
	 * @param user          the acting user, used only to re-read the group through {@code AccessPoint}
	 * @param series        the {@code olio.pb.series} row the baseline belongs to
	 * @param seriesSlug    the series world's slug (the world's {@code name}); drives the group's name
	 * @param castGroupPath the series world's {@code Book} group path
	 * @param organizationId the organization
	 * @return the baseline cast group, re-read as {@code user}
	 */
	public static BaseRecord getCreateBaselineCastGroup(BaseRecord user, BaseRecord series, String seriesSlug,
			String castGroupPath, long organizationId) {
		if(series == null) {
			throw new PictureBookException(400, "A baseline cast group needs a series");
		}
		return getCreateCastGroup(user, baselineCastGroupName(seriesSlug), castGroupPath, organizationId,
			OlioFieldNames.FIELD_PB_SERIES, series);
	}

	/**
	 * Get-or-create the per-chapter shadow cast group in {@code castGroupPath} (the series world's
	 * {@code Book} group). The {@code book} FK is set and {@code series} is left null.
	 *
	 * @param user          the acting user, used only to re-read the group through {@code AccessPoint}
	 * @param book          the chapter {@code olio.pb.book} row this shadow cast tags
	 * @param bookSlug      the chapter book's slug; drives the group's name
	 * @param castGroupPath the series world's {@code Book} group path
	 * @param organizationId the organization
	 * @return the shadow cast group, re-read as {@code user}
	 */
	public static BaseRecord getCreateShadowCastGroup(BaseRecord user, BaseRecord book, String bookSlug,
			String castGroupPath, long organizationId) {
		if(book == null) {
			throw new PictureBookException(400, "A shadow cast group needs a chapter book");
		}
		return getCreateCastGroup(user, shadowCastGroupName(bookSlug), castGroupPath, organizationId,
			OlioFieldNames.FIELD_PB_BOOK, book);
	}

	/**
	 * The shared create-or-get body for both tiers. Resolves the olio principal and the target group,
	 * returns an existing cast group by name+groupId when present, otherwise writes a new one as the
	 * olio principal and re-reads it as {@code user}.
	 */
	private static BaseRecord getCreateCastGroup(BaseRecord user, String name, String castGroupPath,
			long organizationId, String scopeField, BaseRecord scopeRecord) {
		if(user == null || name == null || castGroupPath == null) {
			throw new PictureBookException(400, "user, name and castGroupPath are required");
		}
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, organizationId);
		if(olioUser == null) {
			throw new PictureBookException(500, "No olio principal in organization " + organizationId);
		}
		BaseRecord group = ioContext.getPathUtil().makePath(olioUser, ModelNames.MODEL_GROUP, castGroupPath,
			GroupEnumType.DATA.toString(), organizationId);
		if(group == null) {
			throw new PictureBookException(500, "Failed to resolve the cast group container " + castGroupPath);
		}
		long groupId = ((Number) group.get(FieldNames.FIELD_ID)).longValue();

		BaseRecord existing = findCastGroupInGroup(user, name, groupId, organizationId);
		if(existing != null) {
			return existing;
		}

		BaseRecord cg = null;
		try {
			cg = RecordFactory.newInstance(OlioModelNames.MODEL_PB_CAST_GROUP);
			ioContext.getRecordUtil().applyNameGroupOwnership(olioUser, cg, name, castGroupPath, organizationId);
			cg.set(FieldNames.FIELD_NAME, name);
			cg.set(scopeField, scopeRecord);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble a cast group: " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble cast group '" + name + "'");
		}
		if(!ioContext.getRecordUtil().createRecord(cg)) {
			/// The unique (name, groupId, organizationId) index is the serialization point: a racer that
			/// created the same cast group between the find above and here lands here. Re-read it rather
			/// than fail — the caller wanted get-or-create, and the other writer produced exactly that.
			BaseRecord raced = findCastGroupInGroup(user, name, groupId, organizationId);
			if(raced != null) {
				return raced;
			}
			throw new PictureBookException(500, "Failed to create cast group '" + name + "'");
		}
		BaseRecord readBack = findCastGroupInGroup(user, name, groupId, organizationId);
		if(readBack == null) {
			throw new PictureBookException(500, "Cast group '" + name + "' was created but is not readable by "
				+ user.get(FieldNames.FIELD_NAME) + " - the series world grants did not reach " + castGroupPath);
		}
		return readBack;
	}

	// ─────────────────────────────── read ───────────────────────────────

	/**
	 * The cast group named {@code name} in the group at {@code castGroupPath}, read as {@code user}
	 * through {@code AccessPoint}, or null when absent. Resolves the {@code groupId} find-only as the
	 * olio principal so the authorized query can carry the group-only PBAC shortcut.
	 */
	public static BaseRecord findCastGroup(BaseRecord user, String name, String castGroupPath, long organizationId) {
		if(user == null || name == null || castGroupPath == null) {
			return null;
		}
		IOContext ioContext = IOSystem.getActiveContext();
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, organizationId);
		if(olioUser == null) {
			return null;
		}
		BaseRecord group = ioContext.getPathUtil().findPath(olioUser, ModelNames.MODEL_GROUP, castGroupPath,
			GroupEnumType.DATA.toString(), organizationId);
		if(group == null) {
			return null;
		}
		return findCastGroupInGroup(user, name, ((Number) group.get(FieldNames.FIELD_ID)).longValue(), organizationId);
	}

	/**
	 * The cast group named {@code name} with the given {@code groupId}, read as {@code user}. Uncached,
	 * with an explicit {@code groupId} + {@code organizationId} condition (the group-only PBAC shortcut,
	 * and {@code organizationId} is a number, not a string).
	 */
	private static BaseRecord findCastGroupInGroup(BaseRecord user, String name, long groupId, long organizationId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_CAST_GROUP, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(castRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * The {@code charPerson} members of {@code castGroup}, via the {@code pb.castGroup.member}
	 * participation table. Returns an empty list on any error rather than throwing — a roster read must
	 * never abort a chapter operation.
	 */
	public static List<BaseRecord> listCastMembers(BaseRecord user, BaseRecord castGroup) {
		if(castGroup == null) {
			return Collections.emptyList();
		}
		try {
			/// findMembers, NOT getMembers. The members field declares a participantModel OVERRIDE
			/// ("pb.castGroup.member"), and ParticipationFactory stamps that override onto every row
			/// (ParticipationFactory.java:79). getMembers -> getDbMembers filters participantModel on the
			/// RAW "olio.charPerson" it is handed (MemberUtil.java:233), which never equals the stored
			/// override, so it matches zero rows and returns EMPTY - the roster silently came back empty.
			/// findMembers resolves the override via getParticipantModel and filters on it correctly, but
			/// it returns the raw system.participation rows (participationModel.json declares no
			/// "participant" foreign field, so it cannot itself resolve them to charPerson). Each row's
			/// participantId IS the charPerson id (ParticipationFactory.java:78), so resolve them here -
			/// callers read member.get(FIELD_NAME).
			List<BaseRecord> parts = IOSystem.getActiveContext().getMemberUtil().findMembers(castGroup,
				OlioFieldNames.FIELD_PB_MEMBERS, OlioModelNames.MODEL_CHAR_PERSON, 0L);
			if(parts == null || parts.isEmpty()) {
				return Collections.emptyList();
			}
			List<String> ids = new ArrayList<>();
			for(BaseRecord part : parts) {
				Object pid = part.get(FieldNames.FIELD_PARTICIPANT_ID);
				if(pid instanceof Number) {
					ids.add(Long.toString(((Number) pid).longValue()));
				}
			}
			if(ids.isEmpty()) {
				return Collections.emptyList();
			}
			/// Resolve participantIds to charPerson through AccessPoint as the acting user. NOTE:
			/// AccessPoint.list authorizes only the QUERY SHAPE - it does NOT apply per-record canRead
			/// (see .claude/rules/model-api.md), so this is NOT a per-record read filter and must not be
			/// relied upon as one. Per-record safety here rests on WHERE the members live: the ids come
			/// only from a cast group the caller already read, baseline members live in the series
			/// Population group and shadow members in the chapter shadow group, and the caller's series
			/// roles grant read on both - so every id in this set is already caller-entitled. No groupId
			/// condition because the set can span those two groups; the ids are the scope. If this roster
			/// is ever surfaced across an entitlement boundary, switch to per-id AccessPoint.find or
			/// constrain by an entitled groupId - do not lean on list() to filter.
			long organizationId = ((Number) castGroup.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON);
			q.field(FieldNames.FIELD_ID, ComparatorEnumType.IN, String.join(",", ids));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
				FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID });
			q.setCache(false);
			BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
			return (recs != null ? new ArrayList<>(Arrays.asList(recs)) : Collections.emptyList());
		}
		catch(Exception e) {
			logger.warn("Failed to list cast members of " + castGroup.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			return Collections.emptyList();
		}
	}

	/**
	 * The distinct, meaningful character names in the series' baseline cast — the cross-chapter roster
	 * that seeds the extraction prompt so a character named in chapter 1 is not re-invented as "the
	 * girl" in chapter 2 (N2 item 4).
	 * <p>
	 * Reads the baseline cast group and collects its members' names, filtered by
	 * {@link NarrativeUtil#isMeaningful(String)} so an LLM-extracted literal {@code "null"}/{@code "n/a"}
	 * never enters the roster. Order-preserving and de-duplicated. Returns empty (never null) when the
	 * series has no baseline cast yet.
	 *
	 * @param seriesSlug    the series world's slug
	 * @param castGroupPath the series world's {@code Book} group path
	 */
	public static List<String> seriesCastNames(BaseRecord user, String seriesSlug, String castGroupPath,
			long organizationId) {
		BaseRecord baseline = findCastGroup(user, baselineCastGroupName(seriesSlug), castGroupPath, organizationId);
		if(baseline == null) {
			return Collections.emptyList();
		}
		Set<String> names = new LinkedHashSet<>();
		for(BaseRecord member : listCastMembers(user, baseline)) {
			String nm = member.get(FieldNames.FIELD_NAME);
			if(NarrativeUtil.isMeaningful(nm)) {
				names.add(nm.trim());
			}
		}
		return new ArrayList<>(names);
	}

	// ─────────────────────────────── enrollment ───────────────────────────────

	/**
	 * Enrol {@code charPerson} as a member of {@code castGroup} via the {@code pb.castGroup.member}
	 * participation table. Idempotent (see {@code MemberUtil.member}); written as the olio principal so
	 * the participation is owned uniformly with the rest of the book world. Returns true when the member
	 * is enrolled (or already was), false on failure.
	 */
	public static boolean enrollCastMember(BaseRecord castGroup, BaseRecord charPerson) {
		if(castGroup == null || charPerson == null) {
			return false;
		}
		IOContext ioContext = IOSystem.getActiveContext();
		long orgId = ((Number) castGroup.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord olioUser = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		if(olioUser == null) {
			logger.error("No olio principal in organization " + orgId + "; cannot enrol cast member");
			return false;
		}
		if(ioContext.getMemberUtil().isMember(charPerson, castGroup, OlioFieldNames.FIELD_PB_MEMBERS)) {
			return true;
		}
		return ioContext.getMemberUtil().member(olioUser, castGroup, OlioFieldNames.FIELD_PB_MEMBERS, charPerson, null, true);
	}

	// ─────────────────────────────── projection ───────────────────────────────

	/**
	 * What to project on a cast group read. The {@code members} list is deliberately NOT projected —
	 * members are read on demand via {@link #listCastMembers(BaseRecord, BaseRecord)} through the
	 * participation table, not eagerly through a foreign-list plan.
	 */
	public static String[] castRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			FieldNames.FIELD_DESCRIPTION,
			OlioFieldNames.FIELD_PB_BOOK, OlioFieldNames.FIELD_PB_SERIES
		};
	}
}
