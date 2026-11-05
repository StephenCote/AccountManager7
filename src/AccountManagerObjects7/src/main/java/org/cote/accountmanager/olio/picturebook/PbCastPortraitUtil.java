package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;

/// Design §5 / §28 step 2: a book character gets one PORTRAIT node ({@link PbPipelineUtil#portraitHandle}), and a
/// character that already has an {@code identity.profile.portrait} has that image imported as revision 1 of the
/// node's {@code portrait} chain - selected, APPROVED, node {@code DONE_UNVERIFIED} ("inherited, not produced by
/// this graph"). The artifact points at the profile's own {@code data.data}; no bytes are copied.
///
/// Before this existed, only the PB1 pipeline ever created a {@code portrait@…} node, so a V3 book that never went
/// through PB1 planned every actor "rendered from description (no approved portrait)" - TEXT_ONLY on the SDXL
/// location model - even when every character had a portrait (measured 2026-11-05 on book blodjade-doc, workflow
/// 197: two HERO/SUPPORTING actors with profile portraits, no PORTRAIT nodes, both planned text-only).
///
/// WRITE PATH ONLY. It is called from the RENDER_PLAN / composition-plan executors (an authorized render as the
/// request user), never from the plan-preview read ({@code PbServiceFacade.planScene}). Every write is the request
/// user's, through {@code AccessPoint}. Idempotent: a node whose chain already holds any portrait revision is left
/// alone (a human's approval, rejection or a rendered portrait always wins over the import), and a character
/// without a readable profile portrait gets no node at all - so nothing upstream is left for a run to render
/// unasked. Never throws: a failure is logged and returned as a message, and the plan then degrades exactly as it
/// did before.
public final class PbCastPortraitUtil {

	public static final Logger logger = LogManager.getLogger(PbCastPortraitUtil.class);

	/// PB1's portrait node ordinal (PictureBookUtil's reuse branch), kept so both paths order the canvas alike.
	static final int PORTRAIT_ORDINAL = 20;

	private PbCastPortraitUtil() { }

	/// What one call did, per character objectId.
	public static final class Result {
		private final List<String> imported = new ArrayList<>();
		private final List<String> existing = new ArrayList<>();
		private final List<String> noPortrait = new ArrayList<>();
		private final List<String> failures = new ArrayList<>();

		/** Characters whose profile portrait was imported as an approved revision 1 by this call. */
		public List<String> getImported() { return imported; }
		/** Characters whose PORTRAIT node already carried a portrait chain; left untouched. */
		public List<String> getExisting() { return existing; }
		/** Characters with no readable profile portrait; no node was created. */
		public List<String> getNoPortrait() { return noPortrait; }
		/** Human-readable failures; each one leaves that character planned exactly as before. */
		public List<String> getFailures() { return failures; }
	}

	/// Import the profile portraits of every character row on {@code composition}. Null composition / workflow: no-op.
	public static Result importCastPortraits(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord composition) {
		Result r = new Result();
		if(user == null || book == null || workflow == null || composition == null) {
			return r;
		}
		List<BaseRecord> rows;
		try {
			rows = PbComposeUtil.listActors(user, composition);
		}
		catch(Exception e) {
			r.failures.add("actor rows of composition " + composition.get(FieldNames.FIELD_OBJECT_ID) + " could not be read: " + e.getMessage());
			logger.warn(r.failures.get(r.failures.size() - 1));
			return r;
		}
		if(rows == null || rows.isEmpty()) {
			return r;
		}
		long orgId = PbGraphUtil.orgId(book);
		Set<Long> seen = new LinkedHashSet<>();
		for(BaseRecord row : rows) {
			long charId = PbHealthUtil.fkId(row, OlioFieldNames.FIELD_PB_CHARACTER);
			if(charId <= 0L || !seen.add(Long.valueOf(charId))) {
				continue;
			}
			importOne(user, book, workflow, charId, orgId, r);
		}
		return r;
	}

	/// Import for one composition of a scene, resolving the scene's composition the way the planner does.
	public static Result importSceneCastPortraits(BaseRecord user, BaseRecord book, BaseRecord workflow, BaseRecord scene,
			PbCompositionPurposeEnumType purpose) {
		if(book == null || scene == null) {
			return new Result();
		}
		Object gv = (book.hasField(FieldNames.FIELD_GROUP_ID) ? book.get(FieldNames.FIELD_GROUP_ID) : null);
		long groupId = (gv instanceof Number ? ((Number) gv).longValue() : 0L);
		if(groupId <= 0L) {
			return new Result();
		}
		BaseRecord composition = null;
		try {
			composition = PbComposeUtil.findSceneComposition(user, scene, groupId, PbGraphUtil.orgId(book), purpose);
		}
		catch(Exception e) {
			Result r = new Result();
			r.failures.add("composition of scene " + scene.get(FieldNames.FIELD_OBJECT_ID) + " could not be read: " + e.getMessage());
			logger.warn(r.failures.get(0));
			return r;
		}
		return importCastPortraits(user, book, workflow, composition);
	}

	private static void importOne(BaseRecord user, BaseRecord book, BaseRecord workflow, long charId, long orgId, Result r) {
		String cpOid = null;
		try {
			BaseRecord cp = readCharacter(user, charId, orgId);
			if(cp == null) {
				/// Unreadable characters are the planner's ACTOR_UNRESOLVED; nothing to import as this user.
				return;
			}
			cpOid = cp.get(FieldNames.FIELD_OBJECT_ID);
			String handle = PbPipelineUtil.portraitHandle(cpOid);
			BaseRecord node = PbPipelineUtil.findNodeByHandle(user, workflow, handle);
			if(node != null && !PbArtifactUtil.listChain(user, node, PbPipelineUtil.ROLE_PORTRAIT).isEmpty()) {
				r.existing.add(cpOid);
				return;
			}
			BaseRecord data = profilePortraitData(user, cp, orgId);
			if(data == null) {
				r.noPortrait.add(cpOid);
				return;
			}
			if(node == null) {
				String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
				node = PbGraphUtil.addNode(user, workflow, handle, PbNodeTypeEnumType.PORTRAIT, PbBookUtil.workflowGroupPath(slug), PORTRAIT_ORDINAL);
				if(node == null) {
					throw new PictureBookException(500, "Failed to create the portrait node " + handle);
				}
				if(!PbPipelineUtil.patchNodeScope(user, workflow, node, PbPipelineUtil.SCOPE_CHARACTER, cpOid, PORTRAIT_ORDINAL)) {
					throw new PictureBookException(500, "Failed to record the character scope on node " + handle);
				}
			}
			String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
			BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_PORTRAIT,
				PbArtifactTypeEnumType.IMAGE, PbBookUtil.artifactGroupPath(slug), data, null, null, null);
			if(artifact == null) {
				throw new PictureBookException(500, "Failed to record the imported portrait on " + handle);
			}
			PbArtifactUtil.approve(user, artifact, (String) user.get(FieldNames.FIELD_OBJECT_ID));
			if(!PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.DONE_UNVERIFIED)) {
				logger.warn("Imported portrait on " + handle + " but its node status could not be set DONE_UNVERIFIED");
			}
			logger.info("Imported the profile portrait of " + cp.get(FieldNames.FIELD_NAME) + " (" + data.get(FieldNames.FIELD_OBJECT_ID)
				+ ") as the approved revision 1 of " + handle);
			r.imported.add(cpOid);
		}
		catch(Exception e) {
			String msg = "portrait of character " + (cpOid != null ? cpOid : "#" + charId) + " not imported: " + e.getMessage();
			logger.warn(msg);
			r.failures.add(msg);
		}
	}

	private static BaseRecord readCharacter(BaseRecord user, long id, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_PROFILE });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/// The profile's portrait {@code data.data} as {@code user} reads it (identity only, never the bytes), or null when
	/// the character has no profile, the profile no portrait, or the user may not read it.
	static BaseRecord profilePortraitData(BaseRecord user, BaseRecord cp, long orgId) {
		long profileId = PbHealthUtil.fkId(cp, FieldNames.FIELD_PROFILE);
		if(profileId <= 0L) {
			return null;
		}
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_PROFILE, FieldNames.FIELD_ID, profileId);
		pq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "portrait" });
		pq.setCache(false);
		BaseRecord profile = IOSystem.getActiveContext().getAccessPoint().find(user, pq);
		long dataId = (profile != null ? PbHealthUtil.fkId(profile, "portrait") : 0L);
		if(dataId <= 0L) {
			return null;
		}
		Query dq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_ID, dataId);
		dq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		dq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_CONTENT_TYPE });
		dq.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, dq);
	}
}
