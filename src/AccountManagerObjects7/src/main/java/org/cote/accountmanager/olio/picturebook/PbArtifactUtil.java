package org.cote.accountmanager.olio.picturebook;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.cote.accountmanager.util.JSONUtil;

/**
 * Persist, version and select {@code olio.pb.artifact} records - the provenance wrapper around one
 * output of one node.
 * <p>
 * <b>Bytes stay in {@code data.data}</b> in the world's {@code Gallery} (the existing uniform shape - no
 * new blob model). The artifact carries the provenance: which node produced it, in which role, at which
 * revision, from which config, with which sanitized request.
 * <p>
 * <b>The derived name MUST include the revision.</b> Three facts combine into a hard requirement:
 * {@code applyNameGroupOwnership} does not set {@code name} on these models (it gates on
 * {@code common.name}); a null {@code name} defeats the unique
 * {@code (name, groupId, organizationId)} index because PostgreSQL treats NULLs as distinct; and that
 * index is ratification 8's urn-collision guard, since {@code UrnProvider} composes the urn from
 * {@code name} and {@code common.urn} carries no uniqueness constraint of its own. So two revisions of
 * the same {@code (node, role)} with revision-free names would produce two identical urns and the second
 * create would be rejected - or, with a null name, silently accepted and collide.
 * <p>
 * <b>"One selected artifact per {@code (node, role)}" is not expressible as a unique index.</b> A boolean
 * is never NULL, so a unique index over {@code selected} would forbid a second <i>superseded</i> row -
 * the normal case. The version chain is constrained on
 * {@code (producedByNode, role, revision, organizationId)} instead, and single-selected is enforced in
 * {@link #setSelected} <b>with a post-write re-read</b>, because an index cannot do it.
 */
public class PbArtifactUtil {
	public static final Logger logger = LogManager.getLogger(PbArtifactUtil.class);

	/**
	 * Keys stripped from a backend request before it is persisted as {@code generatorRequest}.
	 * <p>
	 * Two live defects are fixed by construction here:
	 * <ul>
	 * <li>{@code initImage} / {@code promptImages} carry <b>inlined base64</b> - neither
	 * {@code SWTxt2Img.initImage} nor {@code promptImages} has a {@code @JsonIgnore}, so every FLUX.2 or
	 * classic composite persisted multi-megabyte base64 into an attribute. They are replaced with the
	 * <b>artifact objectIds</b> of the references, which is also what makes provenance readable;</li>
	 * <li>{@code session_id} is the Swarm session, persisted with every image today for no reason and
	 * with some risk.</li>
	 * </ul>
	 */
	public static final List<String> SANITIZE_KEYS = Collections.unmodifiableList(Arrays.asList(
		/// The SERIALIZED names, which is what a persisted request actually contains.
		/// MEASURED DEFECT, fixed 2026-08-17: this list originally held only the Java field names
		/// ("initImage", "promptImages"). SWTxt2Img annotates them @JsonProperty("initimage") /
		/// ("promptimages") because that is SwarmUI's wire contract, so the camelCase spellings NEVER
		/// appear in the JSON - sanitization stripped nothing, a 1.6 MB base64 payload was persisted, and
		/// isSanitized() reported a FALSE CLEAN because it counts removals and found none. Caught by
		/// TestPictureBookWorkflow's level-1 assertion on a real FLUX.2 request. Both spellings are kept
		/// and matching is case-insensitive (see stripKeys), so neither a field rename nor a wire-name
		/// change can silently reopen it.
		"initimage", "promptimages", "session_id", "sessionid",
		"initImage", "promptImages", "sessionId",
		/// A raw-graph request (V3 §38) carries the graph body under this key; the graph is its own
		/// artifact (renderGraph), so the request record never duplicates it.
		"comfyworkflowraw"
	));

	/**
	 * A raw-graph request's image inputs are keyed {@code comfyrawworkflowinputimage<name>} - one key per
	 * reference, init image or mask, each a data URI. Any key with this prefix is stripped (V3 §38.3).
	 */
	public static final String SANITIZE_IMAGE_KEY_PREFIX = "comfyrawworkflowinputimage";

	/** Where the replaced reference objectIds are recorded instead. */
	public static final String REFERENCE_ARTIFACTS_KEY = "referenceArtifactObjectIds";

	private PbArtifactUtil() {
		/// static utility
	}

	// ─────────────────────────────── persist ───────────────────────────────

	/**
	 * Persist one artifact for {@code (node, role)}, at the next revision, and select it unless the current
	 * selection is APPROVED (V3 §10 rule 1).
	 * <p>
	 * Ordering, and it matters:
	 * <ol>
	 * <li>resolve the next revision from the persisted chain (never a cached count);</li>
	 * <li>create the artifact with a revision-bearing derived name, {@code supersedes} pointing at the
	 * previously selected revision, {@code selected = false} and {@code reviewStatus = GENERATED};</li>
	 * <li>{@link #setSelected} it, which clears the sibling flags and re-reads to prove it - <b>unless</b>
	 * the previously selected revision is APPROVED, in which case the newcomer stays unselected and the
	 * approved one keeps showing (rules 1 and 3).</li>
	 * </ol>
	 * Creating it already-selected would leave two selected rows for the window between the two writes,
	 * and on a failure would leave two permanently.
	 *
	 * @param groupPath the world's {@code Artifacts} group path
	 * @param data the {@code data.data} holding the bytes, or null for a text/prompt artifact
	 * @param artifactText the inline payload for the byte-free types (TEXT, PROMPT, JSON), or null
	 */
	public static BaseRecord persistArtifact(BaseRecord user, BaseRecord node, String role,
			PbArtifactTypeEnumType artifactType, String groupPath, BaseRecord data, String artifactText,
			BaseRecord sdConfigSnapshot, String generatorRequest) {
		return persistArtifact(user, node, role, artifactType, groupPath, data, artifactText, sdConfigSnapshot,
			generatorRequest, null, 0, null, true);
	}

	/**
	 * {@link #persistArtifact(BaseRecord, BaseRecord, String, PbArtifactTypeEnumType, String, BaseRecord, String, BaseRecord, String)}
	 * for one variant of a multi-variant attempt (V3 §23).
	 * <p>
	 * Every variant of one render shares {@code attempt} (the run's objectId) and carries its own
	 * {@code variantIndex} (0..N-1). Two things differ from the single-variant path, and both exist because
	 * the N variants are persisted one after another into the same {@code (node, role)} chain:
	 * <ul>
	 * <li><b>{@code supersedes} is the caller's pre-batch selection</b>, resolved once before the first
	 * variant was persisted and passed in for every one of them. Resolving it per variant would make
	 * variant 1 "supersede" variant 0 - a sibling of the same attempt, not the revision the attempt
	 * replaces. {@code resolveSupersedes=true} is the single-variant behaviour (look it up here);
	 * {@code false} trusts {@code supersedes} as given, null included.</li>
	 * <li><b>Rule 1 auto-selection applies to variant 0 only.</b> Variants 1..N-1 arrive unselected
	 * whatever the chain's state, so an attempt never selects more than one of its own variants and the
	 * book keeps showing variant 0 (or the APPROVED selection, when there is one) until a human picks.</li>
	 * </ul>
	 *
	 * @param supersedes the revision this attempt replaces (the chain's selection before the attempt), or null
	 * @param variantIndex 0..N-1; only 0 is ever auto-selected
	 * @param attempt the run objectId shared by the attempt's variants, or null when there is no run
	 * @param resolveSupersedes true to ignore {@code supersedes} and look the selection up (single-variant path)
	 */
	public static BaseRecord persistArtifact(BaseRecord user, BaseRecord node, String role,
			PbArtifactTypeEnumType artifactType, String groupPath, BaseRecord data, String artifactText,
			BaseRecord sdConfigSnapshot, String generatorRequest, BaseRecord supersedes, int variantIndex,
			String attempt, boolean resolveSupersedes) {
		if(node == null) {
			throw new PictureBookException(400, "An artifact must name the node that produced it");
		}
		if(role == null || role.trim().length() == 0) {
			throw new PictureBookException(400, "An artifact must carry a role - the version chain is keyed on (node, role, revision)");
		}
		if(variantIndex < 0) {
			throw new PictureBookException(400, "variantIndex must be 0 or more; got " + variantIndex);
		}
		long orgId = PbGraphUtil.orgId(node);
		int revision = nextRevision(user, node, role);
		BaseRecord previous = (resolveSupersedes ? findSelected(user, node, role) : supersedes);
		String name = artifactName(node, role, revision);

		BaseRecord artifact = null;
		try {
			artifact = RecordFactory.newInstance(OlioModelNames.MODEL_PB_ARTIFACT);
			IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, artifact, name, groupPath, orgId);
			/// Explicitly: applyNameGroupOwnership does NOT set it on this model.
			artifact.set(FieldNames.FIELD_NAME, name);
			artifact.set(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE, node);
			artifact.set(OlioFieldNames.FIELD_PB_ROLE, role);
			artifact.set(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(revision));
			artifact.set(OlioFieldNames.FIELD_PB_ARTIFACT_TYPE,
				(artifactType != null ? artifactType : PbArtifactTypeEnumType.UNKNOWN).toString());
			artifact.set(OlioFieldNames.FIELD_PB_SELECTED, Boolean.FALSE);
			/// Written here, not as a schema default: PB2 convention rule 3 forbids field defaults
			/// on the PB models (TestPbModelSchema.TestPbModelsDeclareNoFieldDefaults).
			artifact.set(OlioFieldNames.FIELD_PB_REVIEW_STATUS, PbReviewStatusEnumType.GENERATED.toString());
			artifact.set(OlioFieldNames.FIELD_PB_VARIANT_INDEX, Integer.valueOf(variantIndex));
			if(attempt != null && attempt.trim().length() > 0) {
				artifact.set(OlioFieldNames.FIELD_PB_ATTEMPT, attempt);
			}
			if(previous != null) {
				artifact.set(OlioFieldNames.FIELD_PB_SUPERSEDES, previous);
			}
			if(data != null) {
				artifact.set(OlioFieldNames.FIELD_PB_DATA, data);
			}
			if(artifactText != null) {
				artifact.set(OlioFieldNames.FIELD_PB_ARTIFACT_TEXT, artifactText);
				artifact.set(OlioFieldNames.FIELD_PB_CONTENT_HASH, PbConfigUtil.sha256Hex(artifactText));
			}
			if(sdConfigSnapshot != null) {
				/// A snapshot must FREEZE. Serialized, never a shared foreign row, or a later edit to the
				/// config would silently rewrite history for every past artifact (plan §6c.3.3).
				artifact.set(OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT, sdConfigSnapshot);
			}
			if(generatorRequest != null) {
				artifact.set(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST, generatorRequest);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			logger.error("Failed to assemble an artifact: " + e.getMessage(), e);
			throw new PictureBookException(500, "Failed to assemble artifact " + name);
		}

		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, artifact);
		if(created == null) {
			/// Do NOT state a single cause here. This message previously claimed the unique
			/// (producedByNode, role, revision) index had rejected a duplicate, which sent a real
			/// investigation down the wrong path on 2026-08-17: the actual cause was a PBAC denial on a
			/// cross-compartment `data` foreign reference, and no artifact row existed at all. AccessPoint
			/// .create returns null for denial AND for constraint rejection, so name both.
			throw new PictureBookException(500, "Failed to create artifact " + name
				+ " - AccessPoint.create returned null. Either PBAC denied it (check the audit log; a"
				+ " foreign reference to a record OUTSIDE this book's compartment is the usual cause) or the"
				+ " unique (producedByNode, role, revision, organizationId) index rejected a duplicate"
				+ " revision. Revision resolved to " + revision + ".");
		}
		BaseRecord persisted = readArtifact(user, created.get(FieldNames.FIELD_OBJECT_ID), orgId);
		/// V3 §10 rule 1: a new attempt never displaces an APPROVED selection. The newcomer arrives
		/// GENERATED and unselected; downstream readers (findSelected) keep consuming the approved one. When
		/// nothing is approved the newest attempt is auto-selected, so the book always has something to show.
		/// §23: only variant 0 of an attempt competes for the selection; its siblings stay unselected.
		if(variantIndex > 0) {
			logger.info("Artifact " + name + " recorded unselected: variant " + variantIndex + " of attempt " + attempt);
		}
		else if(previous == null || reviewStatusOf(previous) != PbReviewStatusEnumType.APPROVED) {
			setSelected(user, persisted);
		}
		else {
			logger.info("Artifact " + name + " recorded unselected: the current selection "
				+ previous.get(FieldNames.FIELD_NAME) + " is APPROVED");
		}
		return readArtifact(user, created.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	/**
	 * Record the bytes' measurements on an existing artifact. Separate from
	 * {@link #persistArtifact} because the bytes may be written by a different step, and because a patch
	 * is the safe way to touch a record whose foreign references point at groupless models.
	 */
	public static boolean recordImageMetrics(BaseRecord user, BaseRecord artifact, byte[] bytes, String mimeType,
			int width, int height, Long seed) {
		return recordImageMetrics(user, artifact, bytes, mimeType, width, height, seed, null, 0L);
	}

	/**
	 * {@link #recordImageMetrics(BaseRecord, BaseRecord, byte[], String, int, int, Long)} plus the backend
	 * that produced the bytes and how long it took (V3 §21). {@code durationMs} is recorded for display and
	 * never asserted; 0 means unrecorded and is not written. The two fields are materialised on the patch
	 * only when supplied, so the seven-argument form leaves them untouched.
	 */
	public static boolean recordImageMetrics(BaseRecord user, BaseRecord artifact, byte[] bytes, String mimeType,
			int width, int height, Long seed, SDAPIEnumType backend, long durationMs) {
		return recordImageMetrics(user, artifact, bytes, mimeType, width, height, seed, backend, durationMs, null);
	}

	/**
	 * {@link #recordImageMetrics(BaseRecord, BaseRecord, byte[], String, int, int, Long, SDAPIEnumType, long)}
	 * plus the raw-graph provenance a V3 §38 render produces ({@code backendGraph}: stage, graphHash,
	 * saveNodeId, swarmVersion - never the graph body, which is the sibling renderGraph artifact).
	 * Materialised on the patch only when non-null.
	 */
	public static boolean recordImageMetrics(BaseRecord user, BaseRecord artifact, byte[] bytes, String mimeType,
			int width, int height, Long seed, SDAPIEnumType backend, long durationMs, String backendGraph) {
		if(artifact == null) {
			return false;
		}
		List<String> fields = new ArrayList<>(Arrays.asList(
			OlioFieldNames.FIELD_PB_BYTE_LENGTH, OlioFieldNames.FIELD_PB_CONTENT_HASH,
			OlioFieldNames.FIELD_PB_MIME_TYPE, OlioFieldNames.FIELD_PB_IMAGE_WIDTH,
			OlioFieldNames.FIELD_PB_IMAGE_HEIGHT, OlioFieldNames.FIELD_PB_SEED));
		if(backend != null) {
			fields.add(OlioFieldNames.FIELD_PB_BACKEND);
		}
		if(durationMs > 0L) {
			fields.add(OlioFieldNames.FIELD_PB_DURATION_MS);
		}
		if(backendGraph != null) {
			fields.add(OlioFieldNames.FIELD_PB_BACKEND_GRAPH);
		}
		BaseRecord patch = PbGraphUtil.patchOf(artifact, OlioModelNames.MODEL_PB_ARTIFACT, fields.toArray(new String[0]));
		try {
			if(backend != null) {
				patch.set(OlioFieldNames.FIELD_PB_BACKEND, backend.toString());
			}
			if(durationMs > 0L) {
				patch.set(OlioFieldNames.FIELD_PB_DURATION_MS, Long.valueOf(durationMs));
			}
			if(backendGraph != null) {
				patch.set(OlioFieldNames.FIELD_PB_BACKEND_GRAPH, backendGraph);
			}
			if(bytes != null) {
				patch.set(OlioFieldNames.FIELD_PB_BYTE_LENGTH, Long.valueOf(bytes.length));
				patch.set(OlioFieldNames.FIELD_PB_CONTENT_HASH, sha256Bytes(bytes));
			}
			if(mimeType != null) {
				patch.set(OlioFieldNames.FIELD_PB_MIME_TYPE, mimeType);
			}
			if(width > 0) {
				patch.set(OlioFieldNames.FIELD_PB_IMAGE_WIDTH, Integer.valueOf(width));
			}
			if(height > 0) {
				patch.set(OlioFieldNames.FIELD_PB_IMAGE_HEIGHT, Integer.valueOf(height));
			}
			if(seed != null) {
				patch.set(OlioFieldNames.FIELD_PB_SEED, seed);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble an artifact metrics patch: " + e.getMessage());
		}
		/// Never discard the result: it is the only signal a persistent failure produces.
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			logger.error("Failed to record image metrics on artifact " + artifact.get(FieldNames.FIELD_NAME));
			return false;
		}
		return true;
	}

	// ─────────────────────────────── selection ───────────────────────────────

	/**
	 * Make {@code artifact} the single selected revision of its {@code (node, role)}.
	 * <p>
	 * Clears {@code selected} on every sibling, sets it here, then <b>re-reads the whole chain and asserts
	 * exactly one selected row</b>. The re-read is not defensive padding: a unique index cannot express
	 * this invariant at all (a boolean is never NULL, so a unique index over {@code selected} would forbid
	 * a second superseded row), so the post-write check is the <i>only</i> enforcement that exists.
	 *
	 * @throws PictureBookException 500 when the chain does not end up with exactly one selected revision
	 */
	public static BaseRecord setSelected(BaseRecord user, BaseRecord artifact) {
		if(artifact == null) {
			throw new PictureBookException(400, "Cannot select a null artifact");
		}
		BaseRecord node = artifact.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		String role = artifact.get(OlioFieldNames.FIELD_PB_ROLE);
		if(node == null || role == null) {
			throw new PictureBookException(400, "Artifact " + artifact.get(FieldNames.FIELD_NAME)
				+ " has no producedByNode/role, so its (node, role) chain cannot be resolved."
				+ " Read it with PbArtifactUtil.artifactRequest() projected.");
		}
		long orgId = PbGraphUtil.orgId(artifact);
		Long keepId = artifact.get(FieldNames.FIELD_ID);

		for(BaseRecord sibling : listChain(user, node, role)) {
			Boolean sel = sibling.get(OlioFieldNames.FIELD_PB_SELECTED);
			boolean shouldSelect = keepId.equals(sibling.get(FieldNames.FIELD_ID));
			if(sel != null && sel.booleanValue() == shouldSelect) {
				continue;
			}
			BaseRecord patch = PbGraphUtil.patchOf(sibling, OlioModelNames.MODEL_PB_ARTIFACT,
				OlioFieldNames.FIELD_PB_SELECTED);
			try {
				patch.set(OlioFieldNames.FIELD_PB_SELECTED, Boolean.valueOf(shouldSelect));
			}
			catch(FieldException | ValueException | ModelNotFoundException e) {
				throw new PictureBookException(500, "Failed to assemble a selection patch: " + e.getMessage());
			}
			if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
				throw new PictureBookException(500, "Failed to update 'selected' on artifact "
					+ sibling.get(FieldNames.FIELD_NAME));
			}
		}

		/// POST-WRITE RE-READ. Uncached, or the just-written rows would be invisible.
		List<BaseRecord> selected = new ArrayList<>();
		for(BaseRecord a : listChain(user, node, role)) {
			Boolean sel = a.get(OlioFieldNames.FIELD_PB_SELECTED);
			if(sel != null && sel.booleanValue()) {
				selected.add(a);
			}
		}
		if(selected.size() != 1) {
			throw new PictureBookException(500, "Expected exactly one selected artifact for ("
				+ node.get(FieldNames.FIELD_ID) + ", " + role + ") after setSelected, found " + selected.size()
				+ " - this invariant is not expressible as a unique index, so this re-read is its only enforcement");
		}
		if(!keepId.equals(selected.get(0).get(FieldNames.FIELD_ID))) {
			throw new PictureBookException(500, "setSelected left a different artifact selected: expected #"
				+ keepId + ", found #" + selected.get(0).get(FieldNames.FIELD_ID));
		}
		return readArtifact(user, artifact.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	// ─────────────────────────────── review (V3 §10) ───────────────────────────────

	/** The {@code approvedBy} value recorded when the book's {@code autoApprove} switch approved (rule 5). */
	public static final String APPROVED_BY_AUTO = OlioFieldNames.PB_APPROVED_BY_AUTO;

	/**
	 * "Use this image" (rule 2): make {@code artifact} the selected revision of its {@code (node, role)}.
	 * <p>
	 * Writes {@code selected} only - never {@code reviewStatus}, never the node's {@code pinned}. A chain whose
	 * current selection is APPROVED refuses with 409: a selection click reads as "show this one" and must not
	 * silently discard a human approval and its pin. Approve the other variant, or reject the approved one.
	 */
	public static BaseRecord select(BaseRecord user, BaseRecord artifact) {
		BaseRecord node = chainNode(artifact);
		String role = artifact.get(OlioFieldNames.FIELD_PB_ROLE);
		BaseRecord current = findSelected(user, node, role);
		if(current != null && !sameRecord(current, artifact) && reviewStatusOf(current) == PbReviewStatusEnumType.APPROVED) {
			throw new PictureBookException(409, "The selected artifact " + current.get(FieldNames.FIELD_NAME)
				+ " is APPROVED and is not displaced by a selection. Approve " + artifact.get(FieldNames.FIELD_NAME)
				+ " instead, or reject the approved one.");
		}
		return setSelected(user, artifact);
	}

	/**
	 * Approve (rule 2): {@code artifact} ends selected, APPROVED and with its node pinned. Approving a variant
	 * that is not the current selection selects it first (approve implies select) and demotes any other
	 * APPROVED sibling to GENERATED, so there is one APPROVED artifact per {@code (node, role)}.
	 * <p>
	 * Order: demote siblings, select, pin, stamp. A pinned-but-unapproved node is a legal user state while
	 * APPROVED-but-unpinned is not, so the stamp comes last and a failure part-way is repaired by repeating.
	 * Every step guards on its own terminal state rather than on "already APPROVED", so re-approving an
	 * artifact whose node was unpinned re-pins it, and a human approval over {@link #APPROVED_BY_AUTO}
	 * re-stamps {@code approvedBy}. Protecting a human approval from the auto-approver is the caller's guard
	 * (rule 5), not this method's.
	 *
	 * <p>
	 * <b>Location artifacts (V3 §7, P3-4).</b> When the producing node is a {@code LOCATION} node, the approval is
	 * refused with 409 before any write while the location is locked, and after the stamp the approved
	 * {@code landscape} image is promoted to {@code approvedRender} of the location (or variant) the node renders -
	 * the world asset every scene bound to that record consumes - and the record is touched so every graph bound
	 * to it is rescanned for staleness. The other artifact of a LOCATION node (the {@code renderPlan} JSON) is
	 * approvable like any other but promotes nothing.
	 *
	 * @param approvedBy the approving user's objectId, or {@link #APPROVED_BY_AUTO}
	 */
	public static BaseRecord approve(BaseRecord user, BaseRecord artifact, String approvedBy) {
		if(approvedBy == null || approvedBy.trim().length() == 0) {
			throw new PictureBookException(400, "approvedBy is required: the approving user's objectId or " + APPROVED_BY_AUTO);
		}
		BaseRecord node = chainNode(artifact);
		String role = artifact.get(OlioFieldNames.FIELD_PB_ROLE);
		long orgId = PbGraphUtil.orgId(artifact);

		BaseRecord fullNode = PbGraphUtil.readNode(user, node.get(FieldNames.FIELD_OBJECT_ID), orgId);
		if(fullNode == null) {
			throw new PictureBookException(500, "The producing node of " + artifact.get(FieldNames.FIELD_NAME) + " could not be re-read to pin it");
		}
		PbLocationUtil.NodeSubject subject = PbLocationUtil.subjectOf(user, fullNode);
		if(subject != null) {
			PbLocationUtil.requireUnlocked(subject.location, "approve a render of it");
		}

		for(BaseRecord sibling : listChain(user, node, role)) {
			if(!sameRecord(sibling, artifact) && reviewStatusOf(sibling) == PbReviewStatusEnumType.APPROVED) {
				writeReview(user, sibling, PbReviewStatusEnumType.GENERATED, null, null);
			}
		}
		Boolean sel = artifact.get(OlioFieldNames.FIELD_PB_SELECTED);
		if(sel == null || !sel.booleanValue()) {
			setSelected(user, artifact);
		}
		Boolean pinned = fullNode.get(OlioFieldNames.FIELD_PB_PINNED);
		if(pinned == null || !pinned.booleanValue()) {
			if(!PbGraphUtil.setPinned(user, fullNode, true)) {
				throw new PictureBookException(500, "Failed to pin node " + fullNode.get(OlioFieldNames.FIELD_PB_HANDLE)
					+ " for the approval of " + artifact.get(FieldNames.FIELD_NAME));
			}
		}
		if(reviewStatusOf(artifact) != PbReviewStatusEnumType.APPROVED
				|| !approvedBy.equals(artifact.get(OlioFieldNames.FIELD_PB_APPROVED_BY))) {
			writeReview(user, artifact, PbReviewStatusEnumType.APPROVED, approvedBy, ZonedDateTime.now());
		}
		if(subject != null && promotesToApprovedRender(artifact)) {
			PbLocationUtil.writeApprovedRender(user, subject, artifact);
		}
		return readArtifact(user, artifact.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	/** The one artifact of a LOCATION node that is the location's render: role {@code landscape}, an IMAGE. */
	private static boolean promotesToApprovedRender(BaseRecord artifact) {
		if(artifact == null || !PbPipelineUtil.ROLE_LANDSCAPE.equals(artifact.get(OlioFieldNames.FIELD_PB_ROLE))) {
			return false;
		}
		if(!artifact.hasField(OlioFieldNames.FIELD_PB_ARTIFACT_TYPE)) {
			return false;
		}
		PbArtifactTypeEnumType type = artifact.getEnum(OlioFieldNames.FIELD_PB_ARTIFACT_TYPE);
		return type == PbArtifactTypeEnumType.IMAGE;
	}

	/**
	 * A LOCATION node whose {@code scopeRef} no longer resolves (the location was deleted or merged away) has
	 * no record to protect or to clear, so a rejection treats it as having no subject rather than refusing.
	 * Approve keeps the 404: a render cannot be promoted onto a location that is not there. A record that
	 * exists but that {@code user} may not read also keeps the 404 ({@link PbLocationUtil#subjectExists}):
	 * its lock still protects it, and a caller who cannot see the lock may not clear what it guards.
	 */
	private static PbLocationUtil.NodeSubject subjectOrNullWhenDangling(BaseRecord user, BaseRecord fullNode, BaseRecord artifact) {
		try {
			return PbLocationUtil.subjectOf(user, fullNode);
		}
		catch(PictureBookException e) {
			if(e.getStatus() != 404 || PbLocationUtil.subjectExists(fullNode)) {
				throw e;
			}
			logger.warn("Rejecting " + artifact.get(FieldNames.FIELD_NAME) + " of node " + fullNode.get(OlioFieldNames.FIELD_PB_HANDLE)
				+ " whose location is gone: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Auto-approve (rule 5): the book's {@code autoApprove} switch is on and an attempt has just finished, so
	 * {@code artifact} (the new revision) is approved via {@link #approve} with {@link #APPROVED_BY_AUTO} -
	 * selected, APPROVED, node pinned - unless one of the guards holds. The guards, in order:
	 * <ol>
	 * <li><b>degraded</b>: the executor reported a degradation that vetoes (P4-7). Each entry is read as
	 * {@code "<CODE>: <detail>"}; an entry whose code is a known {@link RenderPlan.Degradation#CODES} code outside
	 * {@link #AUTO_APPROVE_VETO_CODES} (an actor planned from text, a substituted model, a QA warning) is a
	 * reported-and-accepted shortfall and does not veto. Every other entry does: a veto code, an unknown code,
	 * or a free-form entry such as the executor's {@code "upscale: ..."} pseudo-entry. A render that did not
	 * consume what the graph bound is not a result to pin.</li>
	 * <li><b>existing-approved</b>: another revision of the same {@code (node, role)} is APPROVED. The auto-approver
	 * never replaces an approval, human or auto; {@link #approve} would demote it, so this is checked first.</li>
	 * </ol>
	 * <b>Never throws.</b> A guard, or any {@link PictureBookException} from {@link #approve} (a PBAC-denied
	 * review patch surfaces there as a 500), is returned as the skip reason: the attempt completed and the
	 * artifact exists; auto-approval is a convenience layered on top of it, and a caller reports the reason on
	 * its DTO rather than failing a finished run.
	 * <p>
	 * The existing-approved guard is a check-then-act over the chain: a human approving a sibling between the
	 * {@link #listChain} read and the {@link #approve} write is demoted by it. Acceptable for a synchronous run
	 * (the window is one query); the async job layer (P1-6) inherits it unchanged and it is recorded as such.
	 *
	 * @param degradations the executor's {@code degradations} list for this attempt; null reads as empty
	 * @return null when the artifact was approved, else the skip reason ({@code "degraded: ..."} naming only the
	 *         vetoing entries, {@code "existing-approved: <name>"}, {@code "failed: <message>"})
	 */
	public static String autoApprove(BaseRecord user, BaseRecord artifact, List<String> degradations) {
		if(artifact == null) {
			return "failed: no artifact";
		}
		List<String> vetoing = vetoingDegradations(degradations);
		if(!vetoing.isEmpty()) {
			return "degraded: " + String.join("; ", vetoing);
		}
		try {
			BaseRecord node = chainNode(artifact);
			String role = artifact.get(OlioFieldNames.FIELD_PB_ROLE);
			for(BaseRecord sibling : listChain(user, node, role)) {
				if(!sameRecord(sibling, artifact) && reviewStatusOf(sibling) == PbReviewStatusEnumType.APPROVED) {
					return "existing-approved: " + sibling.get(FieldNames.FIELD_NAME);
				}
			}
			approve(user, artifact, APPROVED_BY_AUTO);
			return null;
		}
		catch(PictureBookException e) {
			logger.error("Auto-approve of " + artifact.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
			return "failed: " + e.getMessage();
		}
	}

	/**
	 * The degradation codes that veto auto-approval (P4-7): a character that could not be read or rendered, a
	 * setting rendered without its approved render, a prompt the compiler had to cut, and a composition written
	 * out of order, and an attempt that produced fewer finals than its preset planned (§23, P6-2).
	 * {@code ACTOR_TEXT_ONLY}, {@code MODEL_SUBSTITUTED}, {@code QA_WARN} and {@code VARIANT_SEED_UNKNOWN} are
	 * reported on the plan but accepted.
	 */
	public static final Set<String> AUTO_APPROVE_VETO_CODES = Set.of(
		RenderPlan.Degradation.ACTOR_UNRESOLVED,
		RenderPlan.Degradation.ACTOR_FAILED,
		RenderPlan.Degradation.LOCATION_TEXT_ONLY,
		RenderPlan.Degradation.PROMPT_TRUNCATED,
		RenderPlan.Degradation.COMPOSED_OUT_OF_ORDER,
		RenderPlan.Degradation.VARIANT_FAILED);

	/// The entries of {@code degradations} that veto auto-approval, in order; empty when none (or null) do.
	static List<String> vetoingDegradations(List<String> degradations) {
		if(degradations == null || degradations.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> vetoing = new ArrayList<>();
		for(String entry : degradations) {
			if(entry == null) {
				continue;
			}
			int colon = entry.indexOf(':');
			String code = (colon > 0 ? entry.substring(0, colon).trim() : entry.trim());
			if(RenderPlan.Degradation.isKnownCode(code) && !AUTO_APPROVE_VETO_CODES.contains(code)) {
				continue;
			}
			vetoing.add(entry);
		}
		return vetoing;
	}

	/**
	 * Reject (rule 4): the artifact is marked REJECTED (and kept - cleanup is §23's job), and
	 * {@code approvedBy} is cleared. When it was the selection, the newest sibling that is neither REJECTED
	 * nor ARCHIVED is selected in its place so the book still has something to show; when there is no such
	 * sibling the selection stays where it is (exactly one selected per chain). The node's {@code pinned}
	 * flag is <b>not</b> touched: a rejection is a verdict on one variant, not a regeneration request, and
	 * only an explicit unpin releases a pin.
	 * <p>
	 * <b>Location artifacts (P3-4).</b> Rejecting the image that is currently the {@code approvedRender} of the
	 * location (or variant) its LOCATION node renders clears that FK (and touches the record) after the REJECTED
	 * stamp; while the location is locked that rejection is refused with 409 before any write. Rejecting any
	 * other candidate of a locked location is allowed - it changes nothing the lock protects.
	 */
	public static BaseRecord reject(BaseRecord user, BaseRecord artifact) {
		BaseRecord node = chainNode(artifact);
		String role = artifact.get(OlioFieldNames.FIELD_PB_ROLE);
		long orgId = PbGraphUtil.orgId(artifact);

		PbLocationUtil.NodeSubject subject = null;
		boolean clearsApprovedRender = false;
		if(promotesToApprovedRender(artifact)) {
			BaseRecord fullNode = PbGraphUtil.readNode(user, node.get(FieldNames.FIELD_OBJECT_ID), orgId);
			subject = (fullNode != null ? subjectOrNullWhenDangling(user, fullNode, artifact) : null);
			if(subject != null) {
				clearsApprovedRender = PbHealthUtil.fkId(subject.target(), OlioFieldNames.FIELD_PB_APPROVED_RENDER) == PbHealthUtil.idOf(artifact)
					&& PbHealthUtil.idOf(artifact) > 0L;
				if(clearsApprovedRender) {
					PbLocationUtil.requireUnlocked(subject.location, "reject its approved render");
				}
			}
		}

		Boolean sel = artifact.get(OlioFieldNames.FIELD_PB_SELECTED);
		if(sel != null && sel.booleanValue()) {
			BaseRecord fallback = null;
			for(BaseRecord sibling : listChain(user, node, role)) {
				if(sameRecord(sibling, artifact)) {
					continue;
				}
				PbReviewStatusEnumType rs = reviewStatusOf(sibling);
				if(rs == PbReviewStatusEnumType.REJECTED || rs == PbReviewStatusEnumType.ARCHIVED) {
					continue;
				}
				/// listChain is ascending by revision, so the last survivor is the newest.
				fallback = sibling;
			}
			if(fallback != null) {
				setSelected(user, fallback);
			}
			else {
				logger.info("Rejected artifact " + artifact.get(FieldNames.FIELD_NAME)
					+ " stays selected: its chain has no other GENERATED/IN_REVIEW/APPROVED revision");
			}
		}
		if(reviewStatusOf(artifact) != PbReviewStatusEnumType.REJECTED
				|| artifact.get(OlioFieldNames.FIELD_PB_APPROVED_BY) != null) {
			writeReview(user, artifact, PbReviewStatusEnumType.REJECTED, null, null);
		}
		if(clearsApprovedRender) {
			PbLocationUtil.writeApprovedRender(user, subject, null);
		}
		return readArtifact(user, artifact.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	/**
	 * Archive (§23 retention): mark the artifact ARCHIVED and stamp {@code archivedAt}. The row and its bytes
	 * stay; {@link PbRetentionUtil} deletes the bytes later, once the stamp is old enough.
	 * <p>
	 * <b>Refused with 409 while the artifact is the chain's selection or is APPROVED</b> - archiving never
	 * changes what a book shows, and an approval is a human (or auto) verdict that only {@link #reject} undoes.
	 * The guard reads the artifact as handed in, so a sweep that selected its candidates a moment ago and is
	 * now applying them is refused per item when a reviewer selected or approved one in between; the caller
	 * records the 409 and moves on. {@code approvedBy} is cleared with the status (an archived artifact carries
	 * no approval); {@code selected} and the node's {@code pinned} flag are not touched - a non-selected
	 * artifact has nothing to demote. Idempotent: an already-ARCHIVED artifact keeps its original stamp.
	 */
	public static BaseRecord archive(BaseRecord user, BaseRecord artifact) {
		chainNode(artifact);
		long orgId = PbGraphUtil.orgId(artifact);
		Boolean sel = artifact.get(OlioFieldNames.FIELD_PB_SELECTED);
		if(sel != null && sel.booleanValue()) {
			throw new PictureBookException(409, "Artifact " + artifact.get(FieldNames.FIELD_NAME)
				+ " is the selected revision of its chain and cannot be archived; select or approve another revision first");
		}
		PbReviewStatusEnumType rs = reviewStatusOf(artifact);
		if(rs == PbReviewStatusEnumType.APPROVED) {
			throw new PictureBookException(409, "Artifact " + artifact.get(FieldNames.FIELD_NAME)
				+ " is APPROVED and cannot be archived; reject it first");
		}
		if(rs != PbReviewStatusEnumType.ARCHIVED) {
			writeArchived(user, artifact, ZonedDateTime.now());
		}
		return readArtifact(user, artifact.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	/**
	 * The ARCHIVED stamp: {@code reviewStatus}, a cleared {@code approvedBy}, and {@code archivedAt}. The stamp
	 * is always set here (never left materialised at epoch-0 - see {@link #writeReview} and
	 * {@link #archivedAtOf}, which reports an epoch-0 stamp as null precisely so that a slip here cannot read as
	 * "archived in 1970" and have the bytes deleted on the next sweep).
	 */
	private static void writeArchived(BaseRecord user, BaseRecord artifact, ZonedDateTime archivedAt) {
		BaseRecord patch = PbGraphUtil.patchOf(artifact, OlioModelNames.MODEL_PB_ARTIFACT,
			OlioFieldNames.FIELD_PB_REVIEW_STATUS, OlioFieldNames.FIELD_PB_APPROVED_BY, OlioFieldNames.FIELD_PB_ARCHIVED_AT);
		try {
			patch.set(OlioFieldNames.FIELD_PB_REVIEW_STATUS, PbReviewStatusEnumType.ARCHIVED.toString());
			patch.set(OlioFieldNames.FIELD_PB_APPROVED_BY, null);
			patch.set(OlioFieldNames.FIELD_PB_ARCHIVED_AT, archivedAt);
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble an archive patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to mark artifact " + artifact.get(FieldNames.FIELD_NAME)
				+ " ARCHIVED - AccessPoint.update returned null (PBAC denial or validation failure; check the audit log)");
		}
	}

	/**
	 * Patch the review fields. {@code approvedAt} is listed on the patch <b>only</b> when a stamp is supplied:
	 * {@code patchOf} materialises a ZONETIME at epoch-0 (not null), so listing it on a demote or reject
	 * would silently write 1970-01-01. A demoted or rejected artifact therefore keeps its old
	 * {@code approvedAt}, which is harmless - approval is keyed on {@code reviewStatus}, and the facade emits
	 * {@code approvedAt} only when the status is APPROVED. {@code reviewStatus} is always set because the
	 * patch materialises it at the model default.
	 */
	private static void writeReview(BaseRecord user, BaseRecord artifact, PbReviewStatusEnumType status,
			String approvedBy, ZonedDateTime approvedAt) {
		List<String> fields = new ArrayList<>(Arrays.asList(OlioFieldNames.FIELD_PB_REVIEW_STATUS,
			OlioFieldNames.FIELD_PB_APPROVED_BY));
		if(approvedAt != null) {
			fields.add(OlioFieldNames.FIELD_PB_APPROVED_AT);
		}
		BaseRecord patch = PbGraphUtil.patchOf(artifact, OlioModelNames.MODEL_PB_ARTIFACT, fields.toArray(new String[0]));
		try {
			patch.set(OlioFieldNames.FIELD_PB_REVIEW_STATUS, status.toString());
			patch.set(OlioFieldNames.FIELD_PB_APPROVED_BY, approvedBy);
			if(approvedAt != null) {
				patch.set(OlioFieldNames.FIELD_PB_APPROVED_AT, approvedAt);
			}
		}
		catch(FieldException | ValueException | ModelNotFoundException e) {
			throw new PictureBookException(500, "Failed to assemble a review patch: " + e.getMessage());
		}
		if(IOSystem.getActiveContext().getAccessPoint().update(user, patch) == null) {
			throw new PictureBookException(500, "Failed to mark artifact " + artifact.get(FieldNames.FIELD_NAME)
				+ " " + status + " - AccessPoint.update returned null (PBAC denial or validation failure; check the audit log)");
		}
	}

	/** The producing node of an artifact read with {@link #artifactRequest()}, or a 400 naming the projection. */
	private static BaseRecord chainNode(BaseRecord artifact) {
		if(artifact == null) {
			throw new PictureBookException(400, "A review action needs an artifact");
		}
		BaseRecord node = artifact.get(OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		String role = artifact.get(OlioFieldNames.FIELD_PB_ROLE);
		if(node == null || role == null || node.get(FieldNames.FIELD_OBJECT_ID) == null) {
			throw new PictureBookException(400, "Artifact " + artifact.get(FieldNames.FIELD_NAME)
				+ " has no producedByNode/role, so its (node, role) chain cannot be resolved."
				+ " Read it with PbArtifactUtil.artifactRequest() projected.");
		}
		return node;
	}

	private static boolean sameRecord(BaseRecord a, BaseRecord b) {
		Long ia = a.get(FieldNames.FIELD_ID);
		Long ib = b.get(FieldNames.FIELD_ID);
		return ia != null && ia.equals(ib);
	}

	// ─────────────────────────────── sanitize ───────────────────────────────

	/**
	 * Strip the base64 payloads and the Swarm session id out of a backend request JSON, replacing the
	 * references with the artifact objectIds that produced them.
	 * <p>
	 * <b>Structural, not textual.</b> The request is parsed to a map and the keys are removed, so a
	 * regex cannot miss an escaped or re-ordered payload. Keys are matched at every nesting level, since
	 * the Swarm request shape nests.
	 *
	 * @param referenceArtifactObjectIds the objectIds of the artifacts that supplied the references, in
	 *        request order; recorded under {@link #REFERENCE_ARTIFACTS_KEY}
	 * @return the sanitized JSON, or null when {@code requestJson} was null/unparseable (never the
	 *         unsanitized original - a failure to sanitize must not silently persist base64)
	 */
	@SuppressWarnings("unchecked")
	public static String sanitizeGeneratorRequest(String requestJson, List<String> referenceArtifactObjectIds) {
		if(requestJson == null || requestJson.trim().length() == 0) {
			return null;
		}
		Map<String, Object> parsed = null;
		try {
			parsed = JSONUtil.getMap(requestJson.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		}
		catch(Exception e) {
			logger.error("Failed to parse a generator request for sanitization: " + e.getMessage());
		}
		if(parsed == null) {
			/// Returning the original would persist the base64 this method exists to remove.
			logger.error("A generator request could not be parsed and is therefore NOT persisted -"
				+ " persisting it unsanitized would store multi-megabyte base64 and the Swarm session id");
			return null;
		}
		int removed = stripKeys(parsed);
		Map<String, Object> out = new LinkedHashMap<>(parsed);
		out.put(REFERENCE_ARTIFACTS_KEY,
			(referenceArtifactObjectIds != null ? referenceArtifactObjectIds : Collections.emptyList()));
		if(removed > 0) {
			logger.debug("Sanitized " + removed + " inlined payload/session key(s) out of a generator request");
		}
		return JSONUtil.exportObject(out);
	}

	@SuppressWarnings("unchecked")
	private static int stripKeys(Object node) {
		int removed = 0;
		if(node instanceof Map) {
			Map<String, Object> m = (Map<String, Object>) node;
			/// Case-INSENSITIVE. A key list written against one spelling of the same field is exactly how
			/// the false-clean defect above happened, and the cost of being robust here is one lowercase
			/// comparison per key.
			for(String present : new ArrayList<>(m.keySet())) {
				boolean strip = present.toLowerCase().startsWith(SANITIZE_IMAGE_KEY_PREFIX);
				if(!strip) {
					for(String k : SANITIZE_KEYS) {
						if(present.equalsIgnoreCase(k)) {
							strip = true;
							break;
						}
					}
				}
				if(strip && m.remove(present) != null) {
					removed++;
				}
			}
			for(Object v : new ArrayList<>(m.values())) {
				removed += stripKeys(v);
			}
		}
		else if(node instanceof List) {
			for(Object v : (List<Object>) node) {
				removed += stripKeys(v);
			}
		}
		return removed;
	}

	/**
	 * Does {@code generatorRequest} still carry anything {@link #SANITIZE_KEYS} names?
	 * <p>
	 * Structural, like {@link #sanitizeGeneratorRequest}: it parses and counts, so an escaped or
	 * re-ordered payload cannot slip past it the way it could past a substring check. An unparseable
	 * string answers <b>false</b> - unknown is not clean.
	 */
	public static boolean isSanitized(String generatorRequest) {
		if(generatorRequest == null) {
			return true;
		}
		Map<String, Object> parsed = null;
		try {
			parsed = JSONUtil.getMap(generatorRequest.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		}
		catch(Exception e) {
			logger.warn("Unparseable generatorRequest: " + e.getMessage());
		}
		if(parsed == null) {
			return false;
		}
		return stripKeys(parsed) == 0;
	}

	// ─────────────────────────────── reads ───────────────────────────────

	/** The next revision for {@code (node, role)}: highest persisted revision + 1, or 1. */
	public static int nextRevision(BaseRecord user, BaseRecord node, String role) {
		int max = 0;
		for(BaseRecord a : listChain(user, node, role)) {
			Integer r = a.get(OlioFieldNames.FIELD_PB_REVISION);
			if(r != null && r.intValue() > max) {
				max = r.intValue();
			}
		}
		return max + 1;
	}

	/** The whole version chain for {@code (node, role)}, ascending by revision. Uncached. */
	public static List<BaseRecord> listChain(BaseRecord user, BaseRecord node, String role) {
		if(user == null || node == null || role == null) {
			return Collections.emptyList();
		}
		/// A condition on a FOREIGN MODEL field takes the RECORD, never its id: Query.field routes the
		/// value through FieldUtil.setFlex, which calls setModel() for a MODEL-typed field, so a Long is
		/// rejected and the condition silently becomes "producedByNode = null" - it matches nothing and logs
		/// nothing at the call site. StatementUtil casts the value to BaseRecord and reads its id
		/// (StatementUtil.java:1367). Measured on am7db 2026-08-15.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_ARTIFACT,
			OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE, node);
		q.field(OlioFieldNames.FIELD_PB_ROLE, ComparatorEnumType.EQUALS, role);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, PbGraphUtil.orgId(node));
		q.setRequest(artifactRequest());
		q.setValue(FieldNames.FIELD_SORT_FIELD, OlioFieldNames.FIELD_PB_REVISION);
		q.setValue(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING.toString());
		/// Uncached: setSelected re-reads immediately after writing, and every revision decision must see
		/// the rows just created.
		q.setCache(false);
		BaseRecord[] recs = IOSystem.getActiveContext().getAccessPoint().list(user, q).getResults();
		return (recs != null ? PbGraphUtil.restoreSchema(Arrays.asList(recs), OlioModelNames.MODEL_PB_ARTIFACT)
			: Collections.emptyList());
	}

	/** The selected revision for {@code (node, role)}, or null. */
	public static BaseRecord findSelected(BaseRecord user, BaseRecord node, String role) {
		for(BaseRecord a : listChain(user, node, role)) {
			Boolean sel = a.get(OlioFieldNames.FIELD_PB_SELECTED);
			if(sel != null && sel.booleanValue()) {
				return a;
			}
		}
		return null;
	}

	public static BaseRecord readArtifact(BaseRecord user, String objectId, long organizationId) {
		if(objectId == null) {
			return null;
		}
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_ARTIFACT, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.setRequest(artifactRequest());
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(user, q);
	}

	/**
	 * {@code Artifact <nodeHandle> <role> r<revision>}.
	 * <p>
	 * <b>The revision is in the name and must stay there</b> - see the class javadoc. Without it the
	 * second revision of a {@code (node, role)} collides on the unique
	 * {@code (name, groupId, organizationId)} index, which is the urn-collision guard.
	 */
	public static String artifactName(BaseRecord node, String role, int revision) {
		Object handle = (node.hasField(OlioFieldNames.FIELD_PB_HANDLE) ? node.get(OlioFieldNames.FIELD_PB_HANDLE) : null);
		return "Artifact " + (handle != null ? handle : node.get(FieldNames.FIELD_OBJECT_ID))
			+ " " + role + " r" + revision;
	}

	public static String[] artifactRequest() {
		return new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_URN,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
			OlioFieldNames.FIELD_PB_ARTIFACT_TYPE, OlioFieldNames.FIELD_PB_DATA,
			OlioFieldNames.FIELD_PB_ARTIFACT_TEXT, OlioFieldNames.FIELD_PB_REF_MODEL,
			OlioFieldNames.FIELD_PB_REF_OBJECT_ID, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE,
			OlioFieldNames.FIELD_PB_ROLE, OlioFieldNames.FIELD_PB_REVISION, OlioFieldNames.FIELD_PB_SUPERSEDES,
			OlioFieldNames.FIELD_PB_SELECTED, OlioFieldNames.FIELD_PB_SEED,
			OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT, OlioFieldNames.FIELD_PB_GENERATOR_REQUEST,
			OlioFieldNames.FIELD_PB_CONTENT_HASH, OlioFieldNames.FIELD_PB_MIME_TYPE,
			OlioFieldNames.FIELD_PB_IMAGE_WIDTH, OlioFieldNames.FIELD_PB_IMAGE_HEIGHT,
			OlioFieldNames.FIELD_PB_BYTE_LENGTH, OlioFieldNames.FIELD_PB_BACKEND,
			OlioFieldNames.FIELD_PB_BACKEND_GRAPH,
			OlioFieldNames.FIELD_PB_REVIEW_STATUS, OlioFieldNames.FIELD_PB_APPROVED_BY,
			OlioFieldNames.FIELD_PB_APPROVED_AT, OlioFieldNames.FIELD_PB_DURATION_MS,
			OlioFieldNames.FIELD_PB_ATTEMPT, OlioFieldNames.FIELD_PB_VARIANT_INDEX,
			OlioFieldNames.FIELD_PB_ARCHIVED_AT
		};
	}

	/** The attempt (run objectId) an artifact belongs to, or null for a Test-mode / pre-Phase-6 row. */
	public static String attemptOf(BaseRecord artifact) {
		if(artifact == null || !artifact.hasField(OlioFieldNames.FIELD_PB_ATTEMPT)) {
			return null;
		}
		String a = artifact.get(OlioFieldNames.FIELD_PB_ATTEMPT);
		return (a != null && a.trim().length() > 0 ? a : null);
	}

	/** The variant's 0..N-1 position in its attempt; 0 when absent (single-variant or pre-Phase-6 row). */
	public static int variantIndexOf(BaseRecord artifact) {
		if(artifact == null || !artifact.hasField(OlioFieldNames.FIELD_PB_VARIANT_INDEX)) {
			return 0;
		}
		Integer i = artifact.get(OlioFieldNames.FIELD_PB_VARIANT_INDEX);
		return (i != null && i.intValue() > 0 ? i.intValue() : 0);
	}

	/**
	 * The retention stamp, or null unless the artifact is ARCHIVED. The column is never NULL (DDL default
	 * now(), epoch-0 on a bare newInstance), so the value is trusted only behind the status - the same guard
	 * {@code artifactSummary} applies to {@code approvedAt}. An epoch-0 stamp on an ARCHIVED row (a patch
	 * that materialised the field without setting it) is reported as null too, so nothing computes
	 * "archived 56 years ago" from it.
	 */
	public static ZonedDateTime archivedAtOf(BaseRecord artifact) {
		if(artifact == null || reviewStatusOf(artifact) != PbReviewStatusEnumType.ARCHIVED
				|| !artifact.hasField(OlioFieldNames.FIELD_PB_ARCHIVED_AT)) {
			return null;
		}
		ZonedDateTime at = artifact.get(OlioFieldNames.FIELD_PB_ARCHIVED_AT);
		return (at != null && at.toInstant().toEpochMilli() > 0L ? at : null);
	}

	/**
	 * The review state of an artifact, with the null-means-GENERATED rule applied: the enum column carries no
	 * DDL default, so every row written before V3 Phase 1 reads SQL NULL, which EnumValueType surfaces as
	 * UNKNOWN. Absent, null and UNKNOWN all read as GENERATED.
	 */
	public static PbReviewStatusEnumType reviewStatusOf(BaseRecord artifact) {
		if(artifact == null || !artifact.hasField(OlioFieldNames.FIELD_PB_REVIEW_STATUS)) {
			return PbReviewStatusEnumType.GENERATED;
		}
		PbReviewStatusEnumType rs = artifact.getEnum(OlioFieldNames.FIELD_PB_REVIEW_STATUS);
		return (rs == null || rs == PbReviewStatusEnumType.UNKNOWN ? PbReviewStatusEnumType.GENERATED : rs);
	}

	/**
	 * The objectIds of the artifacts this one was rendered from, read back out of the sanitized
	 * {@code generatorRequest} ({@link #REFERENCE_ARTIFACTS_KEY}, written by {@link #sanitizeGeneratorRequest}).
	 * Empty for an artifact with no request, an unparseable one, or a request without the key.
	 */
	public static List<String> referenceArtifactIdsOf(BaseRecord artifact) {
		if(artifact == null || !artifact.hasField(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST)) {
			return Collections.emptyList();
		}
		String json = artifact.get(OlioFieldNames.FIELD_PB_GENERATOR_REQUEST);
		if(json == null || json.trim().length() == 0) {
			return Collections.emptyList();
		}
		Map<String, Object> parsed = null;
		try {
			parsed = JSONUtil.getMap(json.getBytes(StandardCharsets.UTF_8), String.class, Object.class);
		}
		catch(Exception e) {
			logger.warn("Unparseable generatorRequest on " + artifact.get(FieldNames.FIELD_NAME) + ": " + e.getMessage());
		}
		Object refs = (parsed != null ? parsed.get(REFERENCE_ARTIFACTS_KEY) : null);
		if(!(refs instanceof List)) {
			return Collections.emptyList();
		}
		List<String> out = new ArrayList<>();
		for(Object o : (List<?>) refs) {
			if(o != null && o.toString().trim().length() > 0) {
				out.add(o.toString());
			}
		}
		return out;
	}

	/** Deserialize a persisted {@code sdConfigSnapshot} string. Only needed when it arrives as text. */
	public static BaseRecord parseSnapshot(String json) {
		if(json == null || json.trim().length() == 0) {
			return null;
		}
		return JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
	}

	private static String sha256Bytes(byte[] bytes) {
		/// Hash the bytes themselves, not a String view of them - a byte[] round-tripped through a String
		/// is corrupted for any non-text payload.
		java.security.MessageDigest md = null;
		try {
			md = java.security.MessageDigest.getInstance(PbConfigUtil.HASH_ALGORITHM);
		}
		catch(java.security.NoSuchAlgorithmException e) {
			throw new PictureBookException(500, PbConfigUtil.HASH_ALGORITHM + " is unavailable: " + e.getMessage());
		}
		byte[] digest = md.digest(bytes);
		StringBuilder sb = new StringBuilder(digest.length * 2);
		for(byte b : digest) {
			sb.append(Character.forDigit((b >> 4) & 0xF, 16));
			sb.append(Character.forDigit(b & 0xF, 16));
		}
		return sb.toString();
	}
}
