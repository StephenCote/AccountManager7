package org.cote.accountmanager.olio.picturebook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.GameUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.util.ByteModelUtil;

/**
 * Single-node executor for the PB2 canvas. Drives one node synchronously, persists a new artifact
 * revision, marks it selected, and propagates staleness downstream.
 * <p>
 * Only PORTRAIT is implemented today. Callers discover unsupported types via a 400
 * {@link PictureBookException} so the API can be extended incrementally without silent no-ops.
 */
public class PbNodeExecutor {

	public static final Logger logger = LogManager.getLogger(PbNodeExecutor.class);

	private PbNodeExecutor() {
		/// static utility
	}

	/**
	 * Execute {@code node} against the SD backend at {@code swarmServer} and persist a new artifact
	 * revision.
	 * <p>
	 * {@code book} and {@code workflow} must be the fully-loaded records from
	 * {@link PbServiceFacade#requireBook} / {@link PbServiceFacade#requireWorkflow} — not the shallow FK
	 * stubs that come back on the node. This is a requirement because {@link PbGraphUtil#markStaleDownstream}
	 * calls {@link PbGraphUtil#listNodes} which conditions on the workflow record and reads its {@code id}
	 * via {@link org.cote.accountmanager.io.StatementUtil}.
	 *
	 * @return DTO map describing the new artifact
	 */
	public static Map<String, Object> executeNode(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, String swarmServer) {
		PbNodeTypeEnumType nodeType = node.getEnum(OlioFieldNames.FIELD_PB_NODE_TYPE);
		if(nodeType == null) {
			nodeType = PbNodeTypeEnumType.UNKNOWN;
		}
		switch(nodeType) {
			case PORTRAIT:
				return executePortrait(user, book, workflow, node, swarmServer);
			case LANDSCAPE:
				return executeLandscape(user, book, workflow, node, swarmServer);
			case SCENE_PROMPT:
			case LANDSCAPE_PROMPT:
				return executePromptNode(user, book, workflow, node, nodeType);
			case COMPOSITE:
				return executeComposite(user, book, workflow, node, swarmServer);
			default:
				throw new PictureBookException(501,
					"Single-node execution is not yet implemented for node type " + nodeType);
		}
	}

	// ─── landscape ───────────────────────────────────────────────────────────────

	private static Map<String, Object> executeLandscape(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, String swarmServer) {
		if(swarmServer == null || swarmServer.trim().isEmpty()) {
			throw new PictureBookException(503, "No SD server URL available — configure it via ServerConfigUtil.SERVER_SD");
		}
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Landscape node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " carries no scopeRef (scene objectId)");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_OBJECT_ID, scopeRef);
		sq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		sq.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_CB_SD_PROMPT, OlioFieldNames.FIELD_PB_MOOD
		});
		sq.setCache(false);
		BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
		if(scene == null) {
			throw new PictureBookException(404, "Scene " + scopeRef + " not found");
		}

		String sdPrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		if(sdPrompt == null || sdPrompt.trim().isEmpty()) {
			String mood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
			sdPrompt = "landscape, scenic, painterly, soft light" + (mood != null ? ", " + mood + " atmosphere" : "");
			logger.warn("executeLandscape: scene {} has no sdPrompt — using generic fallback", scopeRef);
		}

		BaseRecord effectiveConfig = PbConfigUtil.resolveEffectiveConfig(book, node, false);
		try {
			effectiveConfig.set("description", sdPrompt);
			effectiveConfig.set("hires", false);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to set description on effective landscape config: " + e.getMessage());
		}

		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		String artifactPath = PbBookUtil.artifactGroupPath(slug);
		String landscapeName = "landscape_" + System.currentTimeMillis();

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
		List<BaseRecord> images = sdu.createImage(user, artifactPath, effectiveConfig, landscapeName, 1, false, -1);
		if(images == null || images.isEmpty()) {
			throw new PictureBookException(500, "SD backend returned no images for landscape of scene " + scopeRef);
		}
		BaseRecord image = images.get(0);
		byte[] bytes;
		try {
			bytes = ByteModelUtil.getValue(image);
		}
		catch(FieldException | ValueException e) {
			throw new PictureBookException(500, "Failed to read landscape bytes for scene " + scopeRef + ": " + e.getMessage());
		}
		if(bytes == null || bytes.length == 0) {
			throw new PictureBookException(500, "Landscape image for scene " + scopeRef + " decoded to empty bytes");
		}

		BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_LANDSCAPE,
			PbArtifactTypeEnumType.IMAGE, artifactPath, image, null, effectiveConfig, null);

		List<BaseRecord> marked = PbGraphUtil.markStaleDownstream(user, workflow, node);
		PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		out.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		out.put("nodeStatus", "DONE_UNVERIFIED");
		out.put("artifactObjectId", artifact.get(FieldNames.FIELD_OBJECT_ID));
		out.put("artifactRevision", artifact.get(OlioFieldNames.FIELD_PB_REVISION));
		out.put("byteLength", Long.valueOf(bytes.length));
		out.put("downstreamMarked", Integer.valueOf(marked.size()));
		List<String> downstreamHandles = new java.util.ArrayList<>();
		for(BaseRecord d : marked) {
			downstreamHandles.add((String) d.get(OlioFieldNames.FIELD_PB_HANDLE));
		}
		out.put("downstreamHandles", downstreamHandles);
		return out;
	}

	// ─── prompt nodes (SCENE_PROMPT / LANDSCAPE_PROMPT) ──────────────────────────

	private static Map<String, Object> executePromptNode(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, PbNodeTypeEnumType nodeType) {
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Prompt node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " carries no scopeRef (scene objectId)");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_OBJECT_ID, scopeRef);
		sq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		sq.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_CB_SD_PROMPT, "description"
		});
		sq.setCache(false);
		BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
		if(scene == null) {
			throw new PictureBookException(404, "Scene " + scopeRef + " not found");
		}

		String role = (nodeType == PbNodeTypeEnumType.LANDSCAPE_PROMPT)
			? PbPipelineUtil.ROLE_LANDSCAPE_PROMPT
			: PbPipelineUtil.ROLE_SCENE_PROMPT;

		String promptText = (nodeType == PbNodeTypeEnumType.LANDSCAPE_PROMPT)
			? scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT)
			: scene.get(FieldNames.FIELD_DESCRIPTION);
		if(promptText == null) promptText = "";

		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		String artifactPath = PbBookUtil.artifactGroupPath(slug);

		BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, role,
			PbArtifactTypeEnumType.TEXT, artifactPath, null, promptText, null, null);

		List<BaseRecord> marked = PbGraphUtil.markStaleDownstream(user, workflow, node);
		PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		out.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		out.put("nodeStatus", "DONE_UNVERIFIED");
		out.put("artifactObjectId", artifact.get(FieldNames.FIELD_OBJECT_ID));
		out.put("artifactRevision", artifact.get(OlioFieldNames.FIELD_PB_REVISION));
		out.put("downstreamMarked", Integer.valueOf(marked.size()));
		List<String> downstreamHandles = new java.util.ArrayList<>();
		for(BaseRecord d : marked) {
			downstreamHandles.add((String) d.get(OlioFieldNames.FIELD_PB_HANDLE));
		}
		out.put("downstreamHandles", downstreamHandles);
		return out;
	}

	// ─── composite ───────────────────────────────────────────────────────────────

	private static Map<String, Object> executeComposite(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, String swarmServer) {
		throw new PictureBookException(501,
			"COMPOSITE requires img2img pipeline — not yet implemented");
	}

	// ─── portrait ────────────────────────────────────────────────────────────────

	private static Map<String, Object> executePortrait(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, String swarmServer) {
		if(swarmServer == null || swarmServer.trim().isEmpty()) {
			throw new PictureBookException(503, "No SD server URL available — configure it via ServerConfigUtil.SERVER_SD");
		}

		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Portrait node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " carries no scopeRef (charPerson objectId)");
		}

		BaseRecord charPerson = GameUtil.findCharacter(scopeRef);
		if(charPerson == null) {
			throw new PictureBookException(404, "Character " + scopeRef + " not found");
		}

		PersonalityProfile pp = ProfileUtil.getProfile(null, charPerson);
		if(pp == null) {
			throw new PictureBookException(500, "Failed to build personality profile for character " + scopeRef);
		}

		BaseRecord nar = NarrativeUtil.getNarrative(pp);
		if(nar == null) {
			throw new PictureBookException(500, "Failed to build narrative for character " + scopeRef);
		}
		String sdPrompt = nar.get("sdPrompt");
		if(sdPrompt == null || sdPrompt.trim().isEmpty()) {
			throw new PictureBookException(500, "Narrative sdPrompt is empty for character " + scopeRef);
		}

		BaseRecord effectiveConfig = PbConfigUtil.resolveEffectiveConfig(book, node, false);
		String description = PictureBookUtil.buildPortraitDescription(sdPrompt, effectiveConfig);
		try {
			effectiveConfig.set("description", description);
			effectiveConfig.set("hires", false);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to set description on effective config: " + e.getMessage());
		}

		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		String artifactPath = PbBookUtil.artifactGroupPath(slug);
		String charName = charPerson.get(FieldNames.FIELD_NAME);
		if(charName == null) {
			charName = PbPipelineUtil.shortRef(scopeRef);
		}
		String portName = "portrait_" + charName.replace(" ", "_") + "_" + System.currentTimeMillis();

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
		List<BaseRecord> portImages = sdu.createImage(user, artifactPath, effectiveConfig, portName, 1, false, -1);
		if(portImages == null || portImages.isEmpty()) {
			throw new PictureBookException(500, "SD backend returned no images for portrait of " + charName);
		}
		BaseRecord portImage = portImages.get(0);
		byte[] portBytes;
		try {
			portBytes = ByteModelUtil.getValue(portImage);
		}
		catch(FieldException | ValueException e) {
			throw new PictureBookException(500, "Failed to read portrait bytes for " + charName + ": " + e.getMessage());
		}
		if(portBytes == null || portBytes.length == 0) {
			throw new PictureBookException(500, "Portrait image for " + charName + " decoded to empty bytes");
		}

		BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_PORTRAIT,
			PbArtifactTypeEnumType.IMAGE, artifactPath, portImage, null, effectiveConfig, null);

		List<BaseRecord> marked = PbGraphUtil.markStaleDownstream(user, workflow, node);
		PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		out.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		out.put("nodeStatus", "DONE_UNVERIFIED");
		out.put("artifactObjectId", artifact.get(FieldNames.FIELD_OBJECT_ID));
		out.put("artifactRevision", artifact.get(OlioFieldNames.FIELD_PB_REVISION));
		out.put("byteLength", Long.valueOf(portBytes.length));
		out.put("downstreamMarked", Integer.valueOf(marked.size()));
		List<String> downstreamHandles = new java.util.ArrayList<>();
		for(BaseRecord d : marked) {
			downstreamHandles.add((String) d.get(OlioFieldNames.FIELD_PB_HANDLE));
		}
		out.put("downstreamHandles", downstreamHandles);
		return out;
	}
}
