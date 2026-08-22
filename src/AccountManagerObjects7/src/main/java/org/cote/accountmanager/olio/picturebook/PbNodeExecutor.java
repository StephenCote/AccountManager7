package org.cote.accountmanager.olio.picturebook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
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
			default:
				throw new PictureBookException(400,
					"Single-node execution is not yet implemented for node type " + nodeType
						+ ". Supported types: PORTRAIT");
		}
	}

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
