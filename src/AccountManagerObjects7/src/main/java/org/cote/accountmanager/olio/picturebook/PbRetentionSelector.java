package org.cote.accountmanager.olio.picturebook;

import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;

/**
 * PictureBook V3 §23 retention policy, as a pure function over one node's artifacts.
 * <p>
 * No I/O, no clock of its own, no {@code BaseRecord}: {@link PbRetentionUtil} reads the artifacts of a node,
 * projects each into a {@link Candidate}, hands the list here with the policy parameters and the current
 * time, and acts on the {@link Decision}s. That split is what makes the policy testable offline
 * ({@code TestPbRetentionSelector}) and what keeps the dry run and the apply on exactly the same item set.
 * <p>
 * The rules, in the order they are applied to each artifact:
 * <ol>
 * <li><b>Kept forever:</b> APPROVED, or currently selected. Never archived, bytes never deleted - whatever
 * their attempt's age, and whatever their status says ({@code archive()} refuses these with 409 for the same
 * reason).</li>
 * <li><b>Stage intermediates</b> ({@code role == stage}) are kept only while a kept final references them,
 * directly or through another stage ({@code referenceArtifactObjectIds} in the sanitized generator request,
 * which the executor fills with the stage the pass painted over). An unreferenced intermediate is archived
 * like any other old attempt; the attempts rule below does not apply to them (an intermediate is only ever
 * as valuable as the final it fed).</li>
 * <li><b>Last K attempts</b> per {@code (role)} are kept whole. An attempt is the artifacts sharing an
 * {@code attempt} value (the run objectId); an artifact with no attempt (Test-mode render, pre-Phase-6 row)
 * is its own attempt. Attempts are ordered by their highest revision, newest first.</li>
 * <li><b>Older GENERATED and REJECTED</b> artifacts are archived. IN_REVIEW is left alone: somebody is
 * looking at it, and a sweep must not pull a candidate out from under a reviewer.</li>
 * <li><b>ARCHIVED</b> artifacts lose their bytes {@code D} days after the archival stamp, when they still
 * have any. The stamp is trusted only when the status is ARCHIVED and is not epoch-0
 * ({@link PbArtifactUtil#archivedAtOf}); an undated archival is reported and left alone rather than
 * guessed at.</li>
 * </ol>
 * Everything else is {@link Action#KEEP} with a reason, so a dry run explains every row.
 */
public final class PbRetentionSelector {

	/** Attempts kept whole per (node, role), newest first. Overridable per request, never a book setting (§6.C 212). */
	public static final int DEFAULT_KEEP_ATTEMPTS = 3;

	/** Days after the archival stamp before an archived artifact's bytes are deleted. */
	public static final int DEFAULT_DELETE_BYTES_AFTER_DAYS = 30;

	/** Upper bounds a request may ask for; anything beyond is a 400 at the util, not a silent clamp. */
	public static final int MAX_KEEP_ATTEMPTS = 1000;
	public static final int MAX_DELETE_BYTES_AFTER_DAYS = 3650;

	public enum Action {
		/** Nothing changes; {@code reason} says why it is retained. */
		KEEP,
		/** GENERATED/REJECTED, older than the last K attempts (or an unreferenced stage): mark ARCHIVED. */
		ARCHIVE,
		/** ARCHIVED for D days or more and still holding bytes: delete the {@code data.data} and clear the FK. */
		DELETE_BYTES,
		/** ARCHIVED but not yet due, already byte-less, or undated: nothing to do this sweep. */
		NONE
	}

	/** One artifact, as much of it as the policy needs. Immutable. */
	public static final class Candidate {
		private final String objectId;
		private final String name;
		private final String role;
		private final String attempt;
		private final int variantIndex;
		private final int revision;
		private final PbReviewStatusEnumType reviewStatus;
		private final boolean selected;
		private final ZonedDateTime archivedAt;
		private final boolean hasBytes;
		private final List<String> referenceIds;

		/**
		 * @param attempt the run objectId, or null when the artifact belongs to no attempt
		 * @param reviewStatus already folded through {@link PbArtifactUtil#reviewStatusOf} (never null/UNKNOWN)
		 * @param archivedAt trusted stamp or null (already guarded by {@link PbArtifactUtil#archivedAtOf})
		 * @param hasBytes true while the artifact still points at a {@code data.data}
		 * @param referenceIds objectIds of the artifacts this one was rendered from (stages it painted over)
		 */
		public Candidate(String objectId, String name, String role, String attempt, int variantIndex, int revision,
				PbReviewStatusEnumType reviewStatus, boolean selected, ZonedDateTime archivedAt, boolean hasBytes,
				List<String> referenceIds) {
			if(objectId == null || objectId.isBlank()) {
				throw new IllegalArgumentException("A retention candidate needs an objectId");
			}
			this.objectId = objectId;
			this.name = name;
			this.role = (role != null ? role : "");
			this.attempt = (attempt != null && !attempt.isBlank() ? attempt : null);
			this.variantIndex = Math.max(0, variantIndex);
			this.revision = revision;
			this.reviewStatus = (reviewStatus != null && reviewStatus != PbReviewStatusEnumType.UNKNOWN
				? reviewStatus : PbReviewStatusEnumType.GENERATED);
			this.selected = selected;
			this.archivedAt = archivedAt;
			this.hasBytes = hasBytes;
			this.referenceIds = (referenceIds != null ? List.copyOf(referenceIds) : Collections.emptyList());
		}

		public String getObjectId() { return objectId; }
		public String getName() { return name; }
		public String getRole() { return role; }
		public String getAttempt() { return attempt; }
		public int getVariantIndex() { return variantIndex; }
		public int getRevision() { return revision; }
		public PbReviewStatusEnumType getReviewStatus() { return reviewStatus; }
		public boolean isSelected() { return selected; }
		public ZonedDateTime getArchivedAt() { return archivedAt; }
		public boolean hasBytes() { return hasBytes; }
		public List<String> getReferenceIds() { return referenceIds; }

		boolean isStage() {
			return PbPipelineUtil.ROLE_STAGE.equals(role);
		}

		boolean keptForever() {
			return selected || reviewStatus == PbReviewStatusEnumType.APPROVED;
		}

		/** The attempt this artifact counts under: its run, or itself when it has none. */
		String attemptKey() {
			return (attempt != null ? attempt : "artifact:" + objectId);
		}
	}

	/** The policy's verdict on one candidate. */
	public static final class Decision {
		private final Candidate candidate;
		private final Action action;
		private final String reason;
		private final ZonedDateTime bytesDueAt;

		Decision(Candidate candidate, Action action, String reason, ZonedDateTime bytesDueAt) {
			this.candidate = candidate;
			this.action = action;
			this.reason = reason;
			this.bytesDueAt = bytesDueAt;
		}

		public Candidate getCandidate() { return candidate; }
		public Action getAction() { return action; }
		public String getReason() { return reason; }
		/** For an ARCHIVED artifact that still has bytes: when they become deletable. Null otherwise. */
		public ZonedDateTime getBytesDueAt() { return bytesDueAt; }
	}

	private PbRetentionSelector() {
		/// pure static policy
	}

	/**
	 * Decide every candidate of one node. Output order is input order.
	 *
	 * @param candidates every artifact of the node, all roles (stages included - they are judged by reference)
	 * @param keepAttempts K, {@code 1..MAX_KEEP_ATTEMPTS}
	 * @param deleteBytesAfterDays D, {@code 0..MAX_DELETE_BYTES_AFTER_DAYS}; 0 means "as soon as archived"
	 * @param now the sweep's clock
	 */
	public static List<Decision> select(List<Candidate> candidates, int keepAttempts, int deleteBytesAfterDays, ZonedDateTime now) {
		if(keepAttempts < 1 || keepAttempts > MAX_KEEP_ATTEMPTS) {
			throw new IllegalArgumentException("keepAttempts must be 1.." + MAX_KEEP_ATTEMPTS + "; got " + keepAttempts);
		}
		if(deleteBytesAfterDays < 0 || deleteBytesAfterDays > MAX_DELETE_BYTES_AFTER_DAYS) {
			throw new IllegalArgumentException("deleteBytesAfterDays must be 0.." + MAX_DELETE_BYTES_AFTER_DAYS + "; got " + deleteBytesAfterDays);
		}
		if(now == null) {
			throw new IllegalArgumentException("now is required");
		}
		if(candidates == null || candidates.isEmpty()) {
			return Collections.emptyList();
		}

		/// Pass 1 - which attempts survive per role, over the non-stage artifacts.
		Map<String, Map<String, Integer>> attemptsByRole = new LinkedHashMap<>();
		for(Candidate c : candidates) {
			if(c.isStage()) {
				continue;
			}
			Map<String, Integer> attempts = attemptsByRole.computeIfAbsent(c.getRole(), k -> new LinkedHashMap<>());
			attempts.merge(c.attemptKey(), c.getRevision(), Math::max);
		}
		Map<String, Set<String>> keptAttemptsByRole = new LinkedHashMap<>();
		for(Map.Entry<String, Map<String, Integer>> e : attemptsByRole.entrySet()) {
			List<Map.Entry<String, Integer>> ordered = new ArrayList<>(e.getValue().entrySet());
			/// newest first: highest revision in the attempt; ties (impossible within one chain) by key for determinism
			ordered.sort((a, b) -> {
				int byRev = Integer.compare(b.getValue(), a.getValue());
				return (byRev != 0 ? byRev : a.getKey().compareTo(b.getKey()));
			});
			Set<String> kept = new HashSet<>();
			for(int i = 0; i < ordered.size() && i < keepAttempts; i++) {
				kept.add(ordered.get(i).getKey());
			}
			keptAttemptsByRole.put(e.getKey(), kept);
		}

		/// Pass 2 - decide the non-stage artifacts; collect what the kept ones reference.
		Map<String, Candidate> byId = new LinkedHashMap<>();
		for(Candidate c : candidates) {
			byId.put(c.getObjectId(), c);
		}
		Map<String, Decision> decided = new LinkedHashMap<>();
		Set<String> referencedByKept = new HashSet<>();
		for(Candidate c : candidates) {
			if(c.isStage()) {
				continue;
			}
			Decision d = decideFinal(c, keptAttemptsByRole.get(c.getRole()), deleteBytesAfterDays, now);
			decided.put(c.getObjectId(), d);
			if(d.getAction() == Action.KEEP) {
				referencedByKept.addAll(c.getReferenceIds());
			}
		}
		/// ...transitively through stages (a final references the stage it painted over, which references its own).
		Set<String> reachable = new HashSet<>();
		Deque<String> work = new ArrayDeque<>(referencedByKept);
		while(!work.isEmpty()) {
			String id = work.pop();
			if(!reachable.add(id)) {
				continue;
			}
			Candidate ref = byId.get(id);
			if(ref != null) {
				for(String next : ref.getReferenceIds()) {
					if(!reachable.contains(next)) {
						work.push(next);
					}
				}
			}
		}

		/// Pass 3 - stages.
		for(Candidate c : candidates) {
			if(!c.isStage()) {
				continue;
			}
			decided.put(c.getObjectId(), decideStage(c, reachable.contains(c.getObjectId()), deleteBytesAfterDays, now));
		}

		List<Decision> out = new ArrayList<>(candidates.size());
		for(Candidate c : candidates) {
			out.add(decided.get(c.getObjectId()));
		}
		return out;
	}

	private static Decision decideFinal(Candidate c, Set<String> keptAttempts, int days, ZonedDateTime now) {
		if(c.keptForever()) {
			return new Decision(c, Action.KEEP, (c.getReviewStatus() == PbReviewStatusEnumType.APPROVED ? "approved" : "selected"), null);
		}
		if(c.getReviewStatus() == PbReviewStatusEnumType.ARCHIVED) {
			return decideArchived(c, days, now);
		}
		if(keptAttempts != null && keptAttempts.contains(c.attemptKey())) {
			return new Decision(c, Action.KEEP, "within the last " + keptAttempts.size() + " attempt(s)", null);
		}
		if(c.getReviewStatus() == PbReviewStatusEnumType.IN_REVIEW) {
			return new Decision(c, Action.KEEP, "in review", null);
		}
		return new Decision(c, Action.ARCHIVE, c.getReviewStatus().name().toLowerCase() + ", older than the kept attempts", null);
	}

	private static Decision decideStage(Candidate c, boolean referenced, int days, ZonedDateTime now) {
		if(c.keptForever()) {
			return new Decision(c, Action.KEEP, (c.getReviewStatus() == PbReviewStatusEnumType.APPROVED ? "approved" : "selected"), null);
		}
		if(c.getReviewStatus() == PbReviewStatusEnumType.ARCHIVED) {
			return decideArchived(c, days, now);
		}
		if(referenced) {
			return new Decision(c, Action.KEEP, "stage referenced by a kept final", null);
		}
		if(c.getReviewStatus() == PbReviewStatusEnumType.IN_REVIEW) {
			return new Decision(c, Action.KEEP, "in review", null);
		}
		return new Decision(c, Action.ARCHIVE, "stage not referenced by any kept final", null);
	}

	private static Decision decideArchived(Candidate c, int days, ZonedDateTime now) {
		if(!c.hasBytes()) {
			return new Decision(c, Action.NONE, "archived, bytes already deleted", null);
		}
		if(c.getArchivedAt() == null) {
			return new Decision(c, Action.NONE, "archived without a usable archivedAt stamp; bytes left alone", null);
		}
		ZonedDateTime due = c.getArchivedAt().plusDays(days);
		if(!due.isAfter(now)) {
			return new Decision(c, Action.DELETE_BYTES, "archived " + days + "+ day(s) ago", due);
		}
		return new Decision(c, Action.NONE, "archived, bytes due " + due, due);
	}
}
