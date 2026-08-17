package org.cote.accountmanager.olio.picturebook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
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
		"initImage", "promptImages", "sessionId"
	));

	/** Where the replaced reference objectIds are recorded instead. */
	public static final String REFERENCE_ARTIFACTS_KEY = "referenceArtifactObjectIds";

	private PbArtifactUtil() {
		/// static utility
	}

	// ─────────────────────────────── persist ───────────────────────────────

	/**
	 * Persist one artifact for {@code (node, role)}, at the next revision, and select it.
	 * <p>
	 * Ordering, and it matters:
	 * <ol>
	 * <li>resolve the next revision from the persisted chain (never a cached count);</li>
	 * <li>create the artifact with a revision-bearing derived name, {@code supersedes} pointing at the
	 * previously selected revision, and {@code selected = false};</li>
	 * <li>{@link #setSelected} it, which clears the sibling flags and re-reads to prove it.</li>
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
		if(node == null) {
			throw new PictureBookException(400, "An artifact must name the node that produced it");
		}
		if(role == null || role.trim().length() == 0) {
			throw new PictureBookException(400, "An artifact must carry a role - the version chain is keyed on (node, role, revision)");
		}
		long orgId = PbGraphUtil.orgId(node);
		int revision = nextRevision(user, node, role);
		BaseRecord previous = findSelected(user, node, role);
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
		setSelected(user, persisted);
		return readArtifact(user, created.get(FieldNames.FIELD_OBJECT_ID), orgId);
	}

	/**
	 * Record the bytes' measurements on an existing artifact. Separate from
	 * {@link #persistArtifact} because the bytes may be written by a different step, and because a patch
	 * is the safe way to touch a record whose foreign references point at groupless models.
	 */
	public static boolean recordImageMetrics(BaseRecord user, BaseRecord artifact, byte[] bytes, String mimeType,
			int width, int height, Long seed) {
		if(artifact == null) {
			return false;
		}
		BaseRecord patch = PbGraphUtil.patchOf(artifact, OlioModelNames.MODEL_PB_ARTIFACT,
			OlioFieldNames.FIELD_PB_BYTE_LENGTH, OlioFieldNames.FIELD_PB_CONTENT_HASH,
			OlioFieldNames.FIELD_PB_MIME_TYPE, OlioFieldNames.FIELD_PB_IMAGE_WIDTH,
			OlioFieldNames.FIELD_PB_IMAGE_HEIGHT, OlioFieldNames.FIELD_PB_SEED);
		try {
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
				for(String k : SANITIZE_KEYS) {
					if(present.equalsIgnoreCase(k)) {
						if(m.remove(present) != null) {
							removed++;
						}
						break;
					}
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
			OlioFieldNames.FIELD_PB_BACKEND_GRAPH
		};
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
