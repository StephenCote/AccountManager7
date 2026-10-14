package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;

/**
 * Reads and participation writes for {@code olio.pb.style} (PictureBook V3 design §4).
 * <p>
 * Phase 2 P2-1 ships only the schema and this access surface; inheritance and lock resolution
 * (§4.1) arrive in P2-3 and build on {@link #readStyle(BaseRecord, String, long)}. Every style read
 * anywhere in the module goes through {@link #styleRequest()} so the projection is declared once.
 * <p>
 * {@code referenceImages} is a participation-backed list ({@code pb.style.referenceImage}) and is
 * deliberately <b>not</b> projected: a dotted {@code setRequest} entry is not a nested projection
 * (see {@code PictureBookUtil} on {@code profile.portrait}), and the standing PB precedent
 * ({@code PbCastUtil.castRequest()}) reads participation lists on demand through
 * {@code MemberUtil.findMembers}. The write goes through {@code AccessPoint.member} as the
 * requesting user — unlike {@code PbCastUtil.enrollCastMember}, which writes as the olio principal
 * because cast rows live in the olio-owned book world. A style is owned by the user who created it,
 * so the authorized path is the right one here.
 */
public class PbStyleUtil {
	public static final Logger logger = LogManager.getLogger(PbStyleUtil.class);

	private PbStyleUtil() {
		/// static utility
	}

	// ─────────────────────────────── projection ───────────────────────────────

	/**
	 * What to project on a style read: every column-backed §4 field. {@code sdConfig} and
	 * {@code parent} come back as id references (the referenced record's common fields), never as a
	 * nested plan — resolve {@code parent} with a second {@link #readStyle(BaseRecord, String, long)}.
	 * {@code referenceImages} is excluded; see {@link #listReferenceImages(BaseRecord, BaseRecord)}.
	 */
	public static String[] styleRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			FieldNames.FIELD_DESCRIPTION,
			OlioFieldNames.FIELD_PB_EXCLUSIONS,
			OlioFieldNames.FIELD_PB_SD_CONFIG,
			OlioFieldNames.FIELD_PB_TASK_MODELS,
			OlioFieldNames.FIELD_PB_PALETTE,
			OlioFieldNames.FIELD_PB_PAGE_FORMAT,
			OlioFieldNames.FIELD_PB_CUSTOM_WIDTH,
			OlioFieldNames.FIELD_PB_CUSTOM_HEIGHT,
			OlioFieldNames.FIELD_PB_QUALITY_PRESET,
			OlioFieldNames.FIELD_PB_PHOTOREAL,
			OlioFieldNames.FIELD_PB_LOCKED,
			OlioFieldNames.FIELD_PB_SCOPE,
			OlioFieldNames.FIELD_PB_PARENT
		};
	}

	// ─────────────────────────────── reads ───────────────────────────────

	/**
	 * Authorized read of one style by objectId, projected with {@link #styleRequest()} and uncached.
	 * Returns null when the style does not exist or the user may not read it.
	 */
	public static BaseRecord readStyle(BaseRecord user, String objectId, long organizationId) {
		if(user == null || objectId == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_STYLE, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(styleRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * {@link #readStyle(BaseRecord, String, long)} from a foreign reference as it comes back on a parent
	 * record ({@code book.style}, {@code style.parent}, {@code series.style}): by {@code objectId} when the
	 * reference carries one, else by {@code id}. Null for a null/empty reference, an absent style, or one
	 * the user may not read.
	 */
	public static BaseRecord readStyle(BaseRecord user, BaseRecord ref, long organizationId) {
		if(user == null || ref == null) {
			return null;
		}
		String oid = (ref.hasField(FieldNames.FIELD_OBJECT_ID) ? ref.get(FieldNames.FIELD_OBJECT_ID) : null);
		if(oid != null && !oid.isBlank()) {
			return readStyle(user, oid, organizationId);
		}
		long id = (ref.hasField(FieldNames.FIELD_ID) && ref.get(FieldNames.FIELD_ID) != null ? ((Number) ref.get(FieldNames.FIELD_ID)).longValue() : 0L);
		if(id <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_STYLE, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(styleRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * The style's {@code sdConfig} read <b>fully</b> ({@code planMost(false)}) as the acting user — the
	 * record whose values become the config tier of {@code PbConfigUtil.resolveEffectiveConfig} /
	 * {@code resolveDeclaredConfig} when a style chain carries one (V3 §4.1; §6.C 156 b). Null when the
	 * style names no config (no style default), or when it names one the user may not read — {@code find}
	 * authorizes the style, not the config it points at, so the resolver treats that as a truncation at
	 * {@code olio.sd.config}, never as "no style default".
	 * <p>
	 * A <b>deleted</b> config is not distinguishable here: the style's {@code sdConfig} projection is a
	 * scalar subselect over the config table ({@code StatementUtil.getInnerSelectTemplate}), so once the
	 * row is gone the reference comes back as id 0 even though the FK column still holds the old id
	 * ({@code DBWriter.deleteForeignReferences} is off). {@link #sdConfigId} is then 0 and the style
	 * reads as "no config" — the same dangling-reference semantics as every other foreign field. Measured
	 * 2026-10-13 ({@code TestPbStyleResolver.case14}, §6.C 160).
	 */
	public static BaseRecord readSdConfig(BaseRecord user, BaseRecord style) {
		long id = sdConfigId(style);
		if(user == null || id <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_ID, id);
		if(style.hasField(FieldNames.FIELD_ORGANIZATION_ID)) {
			q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) style.get(FieldNames.FIELD_ORGANIZATION_ID));
		}
		q.planMost(false);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * Find-only read of the style named {@code name} in group {@code groupId}, as the acting user — the
	 * {@code (name, groupId, organizationId)} constraint of {@code styleModel.json} makes the match unique.
	 * Null when there is none or the user may not read it. Never creates.
	 */
	public static BaseRecord findStyleByName(BaseRecord user, String name, long groupId, long organizationId) {
		if(user == null || name == null || name.isBlank() || groupId <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_STYLE, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(styleRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/** The numeric id of the style's {@code sdConfig} reference, or 0 when it has none (or it was deleted - see {@link #readSdConfig}). */
	public static long sdConfigId(BaseRecord style) {
		if(style == null || !style.hasField(OlioFieldNames.FIELD_PB_SD_CONFIG)) {
			return 0L;
		}
		BaseRecord ref = style.get(OlioFieldNames.FIELD_PB_SD_CONFIG);
		if(ref == null || !ref.hasField(FieldNames.FIELD_ID) || ref.get(FieldNames.FIELD_ID) == null) {
			return 0L;
		}
		return ((Number) ref.get(FieldNames.FIELD_ID)).longValue();
	}

	/**
	 * The style's {@code sdConfig} read as the acting user, projected to its two checkpoint fields
	 * ({@code model}, {@code flux2Model}) — what {@code ModelResolver}'s STYLE_DEFAULT step consumes.
	 * Null when the style names no config or the user may not read it; the caller treats either as
	 * "no style default", never as an error.
	 */
	public static BaseRecord readSdConfigModels(BaseRecord user, BaseRecord style) {
		if(user == null || style == null || !style.hasField(OlioFieldNames.FIELD_PB_SD_CONFIG)) {
			return null;
		}
		BaseRecord ref = style.get(OlioFieldNames.FIELD_PB_SD_CONFIG);
		if(ref == null || !ref.hasField(FieldNames.FIELD_ID)) {
			return null;
		}
		long id = ref.get(FieldNames.FIELD_ID);
		if(id <= 0L) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_ID, id);
		if(style.hasField(FieldNames.FIELD_ORGANIZATION_ID)) {
			q.field(FieldNames.FIELD_ORGANIZATION_ID, (long) style.get(FieldNames.FIELD_ORGANIZATION_ID));
		}
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, OlioFieldNames.FIELD_SD_MODEL, OlioFieldNames.FIELD_SD_FLUX2_MODEL});
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * The {@code data.data} records enrolled as this style's reference images, resolved as the acting
	 * user. Projects identity, name, group and content type only — never the bytes; callers that need
	 * the image read it by id through {@code ByteModelUtil}. Returns empty (never null) when the style
	 * has no reference images or the read fails.
	 */
	public static List<BaseRecord> listReferenceImages(BaseRecord user, BaseRecord style) {
		if(user == null || style == null) {
			return Collections.emptyList();
		}
		try {
			/// findMembers, not getMembers: the field declares a participantModel override and getMembers
			/// filters on the raw model (see PbCastUtil.listCastMembers for the full account).
			List<BaseRecord> parts = IOSystem.getActiveContext().getMemberUtil().findMembers(style,
				OlioFieldNames.FIELD_PB_REFERENCE_IMAGES, ModelNames.MODEL_DATA, 0L);
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
			/// AccessPoint.list authorizes the query shape, not each record. The ids come only from a
			/// style the caller already read, and each one was enrolled through AccessPoint.member,
			/// which required canRead on the image as the enrolling user. If reference images are ever
			/// shared across an entitlement boundary, switch to per-id AccessPoint.find.
			long organizationId = ((Number) style.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
			Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA);
			q.field(FieldNames.FIELD_ID, ComparatorEnumType.IN, String.join(",", ids));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
			q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
				FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_CONTENT_TYPE });
			q.setCache(false);
			BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
			return (recs != null ? new ArrayList<>(Arrays.asList(recs)) : Collections.emptyList());
		}
		catch(Exception e) {
			logger.warn("Failed to list reference images of " + style.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
			return Collections.emptyList();
		}
	}

	// ─────────────────────────────── writes ───────────────────────────────

	/**
	 * Enrol {@code image} (a {@code data.data}) as a reference image of {@code style}, as the
	 * requesting user through {@code AccessPoint.member} — PBAC requires read on the image and update
	 * on the style. Idempotent: returns true when the image is enrolled or already was.
	 */
	public static boolean addReferenceImage(BaseRecord user, BaseRecord style, BaseRecord image) {
		if(user == null || style == null || image == null) {
			return false;
		}
		if(IOSystem.getActiveContext().getMemberUtil().isMember(image, style, OlioFieldNames.FIELD_PB_REFERENCE_IMAGES)) {
			return true;
		}
		return IOSystem.getActiveContext().getAccessPoint().member(user, style, OlioFieldNames.FIELD_PB_REFERENCE_IMAGES, image, null, true);
	}

	/**
	 * Remove {@code image} from {@code style}'s reference images, as the requesting user. Returns
	 * true only when a participation was actually removed.
	 */
	public static boolean removeReferenceImage(BaseRecord user, BaseRecord style, BaseRecord image) {
		if(user == null || style == null || image == null) {
			return false;
		}
		return IOSystem.getActiveContext().getAccessPoint().member(user, style, OlioFieldNames.FIELD_PB_REFERENCE_IMAGES, image, null, false);
	}
}
