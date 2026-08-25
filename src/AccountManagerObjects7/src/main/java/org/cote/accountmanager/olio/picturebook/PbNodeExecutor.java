package org.cote.accountmanager.olio.picturebook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.GameUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.Flux2Defaults;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.olio.sd.swarm.SWTxt2Img;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.util.ByteModelUtil;

/**
 * Single-node executor for the PB2 canvas. Drives one node synchronously, persists a new artifact
 * revision, marks it selected, and propagates staleness downstream.
 * <p>
 * PORTRAIT, LANDSCAPE, SCENE_PROMPT, LANDSCAPE_PROMPT, REFERENCE_STRIP, and COMPOSITE are
 * implemented. Callers discover unsupported types via a 501 {@link PictureBookException} so the
 * API can be extended incrementally without silent no-ops.
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
			case REFERENCE_STRIP:
				return executeReferenceStrip(user, book, workflow, node);
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
		BaseRecord scene = findSceneByNodeRef(user, book, node, orgId, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_CB_SD_PROMPT, OlioFieldNames.FIELD_PB_MOOD
		});
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
		BaseRecord scene = findSceneByNodeRef(user, book, node, orgId, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_CB_SD_PROMPT, FieldNames.FIELD_DESCRIPTION
		});
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

	// ─── reference strip ─────────────────────────────────────────────────────────

	/**
	 * Stitch the source portrait and landscape artifacts side-by-side into an IMAGE_STRIP for use
	 * as a Kontext/FLUX.2 reference image. Does NOT require a swarm server — pure Java 2D work.
	 */
	private static Map<String, Object> executeReferenceStrip(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node) {
		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// Gather portrait and landscape bytes from bound source nodes (cache:false via listBindings)
		List<BaseRecord> bindings = PbGraphUtil.listBindings(user, node);
		byte[] portrait0Bytes = null;
		byte[] portrait1Bytes = null;
		byte[] landscapeBytes = null;

		for(BaseRecord binding : bindings) {
			String role = binding.get(OlioFieldNames.FIELD_PB_ROLE);
			if(role == null) continue;
			BaseRecord srcStub = binding.get(OlioFieldNames.FIELD_PB_SOURCE_NODE);
			if(srcStub == null) continue;
			String srcOid = srcStub.get(FieldNames.FIELD_OBJECT_ID);
			if(srcOid == null) continue;
			BaseRecord srcNode = PbGraphUtil.readNode(user, srcOid, orgId);
			if(srcNode == null) continue;

			String imgRole = PbPipelineUtil.ROLE_LANDSCAPE.equals(role)
				? PbPipelineUtil.ROLE_LANDSCAPE : PbPipelineUtil.ROLE_PORTRAIT;
			BaseRecord sel = PbArtifactUtil.findSelected(user, srcNode, imgRole);
			if(sel == null) continue;
			byte[] artifactBytes = readArtifactBytes(user, sel);
			if(artifactBytes == null || artifactBytes.length == 0) continue;

			if(PbPipelineUtil.ROLE_PORTRAIT_0.equals(role)) {
				portrait0Bytes = artifactBytes;
			} else if(PbPipelineUtil.ROLE_PORTRAIT_1.equals(role)) {
				portrait1Bytes = artifactBytes;
			} else if(PbPipelineUtil.ROLE_LANDSCAPE.equals(role)) {
				landscapeBytes = artifactBytes;
			}
		}

		if(portrait0Bytes == null && portrait1Bytes == null && landscapeBytes == null) {
			throw new PictureBookException(400, "Reference-strip node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " has no resolved source artifacts — execute portrait and landscape nodes first");
		}

		int panelSize = Flux2Defaults.referenceSize();
		byte[] stripBytes = SDUtil.stitchSceneImages(portrait0Bytes, portrait1Bytes, landscapeBytes, panelSize);
		if(stripBytes == null || stripBytes.length == 0) {
			throw new PictureBookException(500, "stitchSceneImages returned empty bytes for reference strip");
		}

		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		String artifactPath = PbBookUtil.artifactGroupPath(slug);
		String stripName = "ref_strip_" + node.get(OlioFieldNames.FIELD_PB_HANDLE) + "_" + System.currentTimeMillis();

		// Create and persist a data.data record for the stitched bytes (pattern from PbPipelineUtil.persistBytes)
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, artifactPath);
		plist.parameter(FieldNames.FIELD_NAME, stripName);
		BaseRecord dataRecord;
		try {
			dataRecord = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_DATA, user, null, plist);
			ByteModelUtil.setValue(dataRecord, stripBytes);
			dataRecord.set(FieldNames.FIELD_CONTENT_TYPE, "image/png");
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble reference strip data record: " + e.getMessage());
		}
		BaseRecord createdData = IOSystem.getActiveContext().getAccessPoint().create(user, dataRecord);
		if(createdData == null) {
			throw new PictureBookException(500, "Failed to create reference strip data record in " + artifactPath);
		}

		BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_REFERENCE_STRIP,
			PbArtifactTypeEnumType.IMAGE_STRIP, artifactPath, createdData, null, null, null);

		List<BaseRecord> marked = PbGraphUtil.markStaleDownstream(user, workflow, node);
		PbGraphUtil.persistStatus(user, node, PbNodeStatusEnumType.DONE_UNVERIFIED);

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("nodeObjectId", node.get(FieldNames.FIELD_OBJECT_ID));
		out.put("handle", node.get(OlioFieldNames.FIELD_PB_HANDLE));
		out.put("nodeStatus", "DONE_UNVERIFIED");
		out.put("artifactObjectId", artifact.get(FieldNames.FIELD_OBJECT_ID));
		out.put("artifactRevision", artifact.get(OlioFieldNames.FIELD_PB_REVISION));
		out.put("byteLength", Long.valueOf(stripBytes.length));
		out.put("downstreamMarked", Integer.valueOf(marked.size()));
		List<String> downstreamHandles = new ArrayList<>();
		for(BaseRecord d : marked) {
			downstreamHandles.add((String) d.get(OlioFieldNames.FIELD_PB_HANDLE));
		}
		out.put("downstreamHandles", downstreamHandles);
		return out;
	}

	// ─── composite ───────────────────────────────────────────────────────────────

	private static Map<String, Object> executeComposite(BaseRecord user, BaseRecord book,
			BaseRecord workflow, BaseRecord node, String swarmServer) {
		// 503 guard MUST be first — before loading bindings, before any AccessPoint call
		if(swarmServer == null || swarmServer.trim().isEmpty()) {
			throw new PictureBookException(503, "No SD server URL available — configure it via ServerConfigUtil.SERVER_SD");
		}

		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			throw new PictureBookException(400, "Composite node "
				+ node.get(OlioFieldNames.FIELD_PB_HANDLE) + " carries no scopeRef (scene objectId)");
		}

		long orgId = ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// Load scene for action/setting/mood/prompt
		BaseRecord scene = findSceneByNodeRef(user, book, node, orgId, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_CB_SD_PROMPT, OlioFieldNames.FIELD_PB_MOOD,
			OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, FieldNames.FIELD_DESCRIPTION
		});
		if(scene == null) {
			throw new PictureBookException(404, "Scene " + scopeRef + " not found");
		}
		String action = scene.get(OlioFieldNames.FIELD_PB_ACTION);
		String setting = scene.get(OlioFieldNames.FIELD_PB_SETTING);
		String mood = scene.get(OlioFieldNames.FIELD_PB_MOOD);
		String scenePrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		if(scenePrompt == null || scenePrompt.isBlank()) {
			scenePrompt = scene.get(FieldNames.FIELD_DESCRIPTION);
		}
		if(scenePrompt == null) scenePrompt = "";

		// Gather portrait bytes + descriptions and landscape bytes from bound source nodes
		List<BaseRecord> bindings = PbGraphUtil.listBindings(user, node);
		byte[] leftBytes = null;     // PORTRAIT_0
		byte[] centerBytes = null;   // PORTRAIT_1 (mapped to buildSceneRequest's rightBytes param)
		byte[] landscapeBytes = null;
		String leftDesc = "";
		String rightDesc = "";

		for(BaseRecord binding : bindings) {
			String bRole = binding.get(OlioFieldNames.FIELD_PB_ROLE);
			if(bRole == null) continue;
			BaseRecord srcStub = binding.get(OlioFieldNames.FIELD_PB_SOURCE_NODE);
			if(srcStub == null) continue;
			String srcOid = srcStub.get(FieldNames.FIELD_OBJECT_ID);
			if(srcOid == null) continue;
			BaseRecord srcNode = PbGraphUtil.readNode(user, srcOid, orgId);
			if(srcNode == null) continue;

			boolean isPort0 = PbPipelineUtil.ROLE_PORTRAIT_0.equals(bRole);
			boolean isPort1 = PbPipelineUtil.ROLE_PORTRAIT_1.equals(bRole);
			boolean isLand  = PbPipelineUtil.ROLE_LANDSCAPE.equals(bRole);
			if(!isPort0 && !isPort1 && !isLand) continue;

			String imgRole = isLand ? PbPipelineUtil.ROLE_LANDSCAPE : PbPipelineUtil.ROLE_PORTRAIT;
			BaseRecord sel = PbArtifactUtil.findSelected(user, srcNode, imgRole);
			if(sel == null) continue;
			byte[] artifactBytes = readArtifactBytes(user, sel);
			if(artifactBytes == null || artifactBytes.length == 0) continue;

			if(isPort0) {
				leftBytes = artifactBytes;
				String pt = srcNode.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT);
				if(pt != null && !pt.isBlank()) leftDesc = pt;
			} else if(isPort1) {
				centerBytes = artifactBytes;
				String pt = srcNode.get(OlioFieldNames.FIELD_PB_PROMPT_TEXT);
				if(pt != null && !pt.isBlank()) rightDesc = pt;
			} else {
				landscapeBytes = artifactBytes;
			}
		}

		BaseRecord effectiveConfig = PbConfigUtil.resolveEffectiveConfig(book, node, true);
		String mode = SceneCompositeUtil.resolveMode(effectiveConfig, false);
		double creativity = SceneCompositeUtil.defaultCreativity(mode);
		String negPrompt = NarrativeUtil.getDefaultNegativePrompt();

		// centerBytes maps to buildSceneRequest's "rightBytes" parameter (second portrait)
		SWTxt2Img sceneRequest = SceneCompositeUtil.buildSceneRequest(mode,
			leftDesc, rightDesc, action, setting, mood, scenePrompt, negPrompt,
			leftBytes, centerBytes, landscapeBytes, creativity, effectiveConfig);
		if(sceneRequest == null) {
			throw new PictureBookException(500,
				"Could not build a composite scene request for scene " + scopeRef + " mode=" + mode);
		}

		String slug = book.get(OlioFieldNames.FIELD_PB_SLUG);
		String artifactPath = PbBookUtil.artifactGroupPath(slug);
		String sceneName = "composite_" + scopeRef + "_" + System.currentTimeMillis();

		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer);
		List<BaseRecord> images = sdu.createSceneImage(user, artifactPath, sceneName, sceneRequest, null, null);
		if(images == null || images.isEmpty()) {
			throw new PictureBookException(500, "SD backend returned no images for composite of scene " + scopeRef);
		}
		BaseRecord image = images.get(0);
		byte[] bytes;
		try {
			bytes = ByteModelUtil.getValue(image);
		}
		catch(FieldException | ValueException e) {
			throw new PictureBookException(500, "Failed to read composite bytes for scene " + scopeRef + ": " + e.getMessage());
		}
		if(bytes == null || bytes.length == 0) {
			throw new PictureBookException(500, "Composite image for scene " + scopeRef + " decoded to empty bytes");
		}

		BaseRecord artifact = PbArtifactUtil.persistArtifact(user, node, PbPipelineUtil.ROLE_COMPOSITE,
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
		List<String> downstreamHandles = new ArrayList<>();
		for(BaseRecord d : marked) {
			downstreamHandles.add((String) d.get(OlioFieldNames.FIELD_PB_HANDLE));
		}
		out.put("downstreamHandles", downstreamHandles);
		return out;
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

	// ─── scene lookup helper ─────────────────────────────────────────────────────

	/**
	 * Resolve the {@code olio.pb.scene} record for a node whose {@code scopeRef} may be either:
	 * <ol>
	 *   <li>the {@code olio.pb.scene} objectId (new pipeline nodes created by {@code getCreateSceneRow}), or</li>
	 *   <li>the PB1 {@code data.note} objectId stored by {@code getCreateNode} when the node was first
	 *       written (legacy nodes from early PB2 seeding runs).</li>
	 * </ol>
	 * The direct lookup is attempted first. If it fails, the method falls back to listing all scenes for
	 * the book and matching by {@code sceneIndex} from the node record. This keeps callers honest about
	 * what objectId they are dealing with without requiring a re-seed.
	 *
	 * @param requestFields fields to include in the projection (passed to both the direct and fallback queries)
	 * @return the matching {@code olio.pb.scene}, or {@code null} if none can be found
	 */
	private static BaseRecord findSceneByNodeRef(BaseRecord user, BaseRecord book, BaseRecord node,
			long orgId, String[] requestFields) {
		String scopeRef = node.get(OlioFieldNames.FIELD_PB_SCOPE_REF);
		if(scopeRef == null || scopeRef.trim().isEmpty()) {
			return null;
		}

		// Primary: direct objectId lookup
		Query sq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_OBJECT_ID, scopeRef);
		sq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		sq.setRequest(requestFields);
		sq.setCache(false);
		BaseRecord scene = IOSystem.getActiveContext().getAccessPoint().find(user, sq);
		if(scene != null) {
			return scene;
		}

		// Fallback: scopeRef is a PB1 note objectId — match by book + sceneIndex
		Integer sceneIdx = node.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		if(sceneIdx == null || book == null) {
			logger.warn("findSceneByNodeRef: direct lookup of {} failed and no sceneIndex/book to fall back on", scopeRef);
			return null;
		}
		logger.debug("findSceneByNodeRef: direct lookup of {} failed; trying sceneIndex={} fallback", scopeRef, sceneIdx);
		Query fallback = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_BOOK, book);
		fallback.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		fallback.field(OlioFieldNames.FIELD_PB_SCENE_INDEX, sceneIdx);
		fallback.setRequest(requestFields);
		fallback.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, fallback);
	}

	// ─── artifact bytes helper ────────────────────────────────────────────────────

	/**
	 * Read the raw bytes stored in the {@code data.data} record linked by {@code artifact.data}.
	 * Uses {@link ByteModelUtil#getValue} to handle compression/encryption transparently.
	 * Returns {@code null} (logged) if the artifact carries no data FK, or if the data record
	 * cannot be found or decoded.
	 */
	private static byte[] readArtifactBytes(BaseRecord user, BaseRecord artifact) {
		if(artifact == null) return null;
		BaseRecord dataStub = artifact.get(OlioFieldNames.FIELD_PB_DATA);
		if(dataStub == null) return null;
		String dataOid = dataStub.get(FieldNames.FIELD_OBJECT_ID);
		if(dataOid == null) return null;
		long orgId = PbGraphUtil.orgId(artifact);
		Query dq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, dataOid);
		dq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		dq.planMost(false);
		dq.setCache(false);
		BaseRecord dataRecord = IOSystem.getActiveContext().getAccessPoint().find(user, dq);
		if(dataRecord == null) {
			logger.warn("readArtifactBytes: data record {} not found", dataOid);
			return null;
		}
		try {
			return ByteModelUtil.getValue(dataRecord);
		}
		catch(FieldException | ValueException e) {
			logger.warn("readArtifactBytes: failed to decode bytes from data record {}: {}", dataOid, e.getMessage());
			return null;
		}
	}
}
