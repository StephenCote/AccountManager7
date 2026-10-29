package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cote.accountmanager.olio.picturebook.PbRetentionSelector.Action;
import org.cote.accountmanager.olio.picturebook.PbRetentionSelector.Candidate;
import org.cote.accountmanager.olio.picturebook.PbRetentionSelector.Decision;
import org.cote.accountmanager.schema.type.PbReviewStatusEnumType;
import org.junit.Test;

/// V3 §23 retention policy (Phase 6, P6-3a). Pure function over one node's artifacts: no DB, no LLM.
/// PbRetentionUtil (P6-3b) is the only caller; the dry run and the apply both go through select(), so
/// every rule proven here is the rule the sweep applies.
public class TestPbRetentionSelector {

	private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 29, 12, 0, 0, 0, ZoneOffset.UTC);
	private static final String FINAL = "final";
	private static final String STAGE = PbPipelineUtil.ROLE_STAGE;

	/// ---- builders -------------------------------------------------------------------------------

	private static Candidate c(String oid, String role, String attempt, int variant, int revision,
			PbReviewStatusEnumType status, boolean selected, ZonedDateTime archivedAt, boolean hasBytes, String... refs) {
		return new Candidate(oid, oid + "-name", role, attempt, variant, revision, status, selected, archivedAt, hasBytes,
			Arrays.asList(refs));
	}

	private static Candidate generated(String oid, String attempt, int variant, int revision) {
		return c(oid, FINAL, attempt, variant, revision, PbReviewStatusEnumType.GENERATED, false, null, true);
	}

	private static Decision only(List<Decision> decisions, String oid) {
		for(Decision d : decisions) {
			if(oid.equals(d.getCandidate().getObjectId())) {
				return d;
			}
		}
		fail("No decision for " + oid);
		return null;
	}

	private static void assertAction(List<Decision> decisions, String oid, Action expected) {
		Decision d = only(decisions, oid);
		assertEquals(oid + ": " + d.getReason(), expected, d.getAction());
	}

	/// ---- rule 1: kept forever ------------------------------------------------------------------

	/// APPROVED and selected artifacts are never archived, however old their attempt is.
	@Test
	public void TestApprovedAndSelectedAreKeptWhateverTheirAge() {
		List<Candidate> in = new ArrayList<>();
		in.add(c("a0", FINAL, "run0", 0, 1, PbReviewStatusEnumType.APPROVED, false, null, true));
		in.add(c("a1", FINAL, "run1", 0, 2, PbReviewStatusEnumType.GENERATED, true, null, true));
		in.add(generated("a2", "run2", 0, 3));
		in.add(generated("a3", "run3", 0, 4));
		in.add(generated("a4", "run4", 0, 5));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		assertAction(out, "a0", Action.KEEP);
		assertEquals("approved", only(out, "a0").getReason());
		assertAction(out, "a1", Action.KEEP);
		assertEquals("selected", only(out, "a1").getReason());
		/// K=1: only run4 survives on age
		assertAction(out, "a2", Action.ARCHIVE);
		assertAction(out, "a3", Action.ARCHIVE);
		assertAction(out, "a4", Action.KEEP);
	}

	/// A REJECTED artifact that is somehow still the selection (fallback had nothing else) is kept too;
	/// the sweep must not pull the strip's current image.
	@Test
	public void TestSelectedRejectedIsStillKept() {
		List<Candidate> in = new ArrayList<>();
		in.add(c("r0", FINAL, "run0", 0, 1, PbReviewStatusEnumType.REJECTED, true, null, true));
		in.add(generated("g1", "run1", 0, 2));
		in.add(generated("g2", "run2", 0, 3));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		assertAction(out, "r0", Action.KEEP);
		assertAction(out, "g1", Action.ARCHIVE);
		assertAction(out, "g2", Action.KEEP);
	}

	/// ---- rule 3: last K attempts ---------------------------------------------------------------

	/// Attempts are counted by the shared attempt id, not per artifact: three variants of one run are
	/// one attempt, and all of its variants are kept together.
	@Test
	public void TestVariantsOfOneAttemptCountOnceAndAreKeptTogether() {
		List<Candidate> in = new ArrayList<>();
		/// attempt A: 3 variants, revisions 1..3
		in.add(generated("A0", "runA", 0, 1));
		in.add(generated("A1", "runA", 1, 2));
		in.add(generated("A2", "runA", 2, 3));
		/// attempt B: 2 variants
		in.add(generated("B0", "runB", 0, 4));
		in.add(generated("B1", "runB", 1, 5));
		/// attempt C: 1 variant
		in.add(generated("C0", "runC", 0, 6));
		List<Decision> out = PbRetentionSelector.select(in, 2, 30, NOW);
		assertAction(out, "A0", Action.ARCHIVE);
		assertAction(out, "A1", Action.ARCHIVE);
		assertAction(out, "A2", Action.ARCHIVE);
		assertAction(out, "B0", Action.KEEP);
		assertAction(out, "B1", Action.KEEP);
		assertAction(out, "C0", Action.KEEP);
	}

	/// Pre-Phase-6 rows and Test-mode renders have no attempt; each is its own attempt so K still means
	/// "the last K renders" for a legacy chain.
	@Test
	public void TestNullAttemptIsItsOwnAttempt() {
		List<Candidate> in = new ArrayList<>();
		in.add(generated("n1", null, 0, 1));
		in.add(generated("n2", null, 0, 2));
		in.add(generated("n3", "", 0, 3));
		in.add(generated("n4", null, 0, 4));
		List<Decision> out = PbRetentionSelector.select(in, 2, 30, NOW);
		assertAction(out, "n1", Action.ARCHIVE);
		assertAction(out, "n2", Action.ARCHIVE);
		assertAction(out, "n3", Action.KEEP);
		assertAction(out, "n4", Action.KEEP);
	}

	/// Attempt age is the attempt's highest revision, so an attempt whose variant 0 is old but whose last
	/// variant is newer than another whole attempt ranks by its newest member.
	@Test
	public void TestAttemptAgeIsItsNewestRevision() {
		List<Candidate> in = new ArrayList<>();
		in.add(generated("X0", "runX", 0, 1));
		in.add(generated("Y0", "runY", 0, 2));
		in.add(generated("Y1", "runY", 1, 3));
		in.add(generated("X1", "runX", 1, 4));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		/// runX's newest is 4 > runY's 3, so X is the kept attempt
		assertAction(out, "X0", Action.KEEP);
		assertAction(out, "X1", Action.KEEP);
		assertAction(out, "Y0", Action.ARCHIVE);
		assertAction(out, "Y1", Action.ARCHIVE);
	}

	/// K counts per role: a node's portrait chain and its scene chain do not compete for the same K.
	@Test
	public void TestKeepCountIsPerRole() {
		List<Candidate> in = new ArrayList<>();
		in.add(generated("s1", "runS1", 0, 1));
		in.add(generated("s2", "runS2", 0, 2));
		in.add(c("p1", "portrait", "runP1", 0, 1, PbReviewStatusEnumType.GENERATED, false, null, true));
		in.add(c("p2", "portrait", "runP2", 0, 2, PbReviewStatusEnumType.GENERATED, false, null, true));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		assertAction(out, "s1", Action.ARCHIVE);
		assertAction(out, "s2", Action.KEEP);
		assertAction(out, "p1", Action.ARCHIVE);
		assertAction(out, "p2", Action.KEEP);
	}

	/// ---- rule 4: what is archived --------------------------------------------------------------

	/// Outside the kept attempts GENERATED and REJECTED are archived; IN_REVIEW is left for the reviewer.
	@Test
	public void TestOnlyGeneratedAndRejectedAreArchived() {
		List<Candidate> in = new ArrayList<>();
		in.add(c("g", FINAL, "run1", 0, 1, PbReviewStatusEnumType.GENERATED, false, null, true));
		in.add(c("r", FINAL, "run2", 0, 2, PbReviewStatusEnumType.REJECTED, false, null, true));
		in.add(c("i", FINAL, "run3", 0, 3, PbReviewStatusEnumType.IN_REVIEW, false, null, true));
		in.add(generated("k", "run4", 0, 4));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		assertAction(out, "g", Action.ARCHIVE);
		assertAction(out, "r", Action.ARCHIVE);
		assertAction(out, "i", Action.KEEP);
		assertEquals("in review", only(out, "i").getReason());
		assertAction(out, "k", Action.KEEP);
	}

	/// UNKNOWN/null status folds to GENERATED (an unread enum column), so a legacy row is archivable.
	@Test
	public void TestNullStatusFoldsToGenerated() {
		Candidate legacy = new Candidate("L", "L", FINAL, null, 0, 1, null, false, null, true, null);
		assertEquals(PbReviewStatusEnumType.GENERATED, legacy.getReviewStatus());
		Candidate unknown = new Candidate("U", "U", FINAL, null, 0, 2, PbReviewStatusEnumType.UNKNOWN, false, null, true, null);
		assertEquals(PbReviewStatusEnumType.GENERATED, unknown.getReviewStatus());
		List<Decision> out = PbRetentionSelector.select(Arrays.asList(legacy, unknown, generated("k", null, 0, 3)), 1, 30, NOW);
		assertAction(out, "L", Action.ARCHIVE);
		assertAction(out, "U", Action.ARCHIVE);
		assertAction(out, "k", Action.KEEP);
	}

	/// ---- rule 5: bytes of archived artifacts ---------------------------------------------------

	@Test
	public void TestArchivedBytesAreDeletedOnlyWhenDue() {
		ZonedDateTime old = NOW.minusDays(31);
		ZonedDateTime exactlyDue = NOW.minusDays(30);
		ZonedDateTime recent = NOW.minusDays(29);
		List<Candidate> in = new ArrayList<>();
		in.add(c("due", FINAL, "run1", 0, 1, PbReviewStatusEnumType.ARCHIVED, false, old, true));
		in.add(c("edge", FINAL, "run2", 0, 2, PbReviewStatusEnumType.ARCHIVED, false, exactlyDue, true));
		in.add(c("notDue", FINAL, "run3", 0, 3, PbReviewStatusEnumType.ARCHIVED, false, recent, true));
		in.add(c("gone", FINAL, "run4", 0, 4, PbReviewStatusEnumType.ARCHIVED, false, old, false));
		in.add(c("undated", FINAL, "run5", 0, 5, PbReviewStatusEnumType.ARCHIVED, false, null, true));
		in.add(generated("k", "run6", 0, 6));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		assertAction(out, "due", Action.DELETE_BYTES);
		assertEquals(old.plusDays(30), only(out, "due").getBytesDueAt());
		assertAction(out, "edge", Action.DELETE_BYTES);
		assertAction(out, "notDue", Action.NONE);
		assertEquals(recent.plusDays(30), only(out, "notDue").getBytesDueAt());
		assertAction(out, "gone", Action.NONE);
		assertNull(only(out, "gone").getBytesDueAt());
		assertAction(out, "undated", Action.NONE);
		assertTrue(only(out, "undated").getReason().contains("archivedAt"));
		assertAction(out, "k", Action.KEEP);
	}

	/// An ARCHIVED row is judged by the bytes rule even when it would fall inside the last K attempts -
	/// archival is a one-way door in P6 (no un-archive), so it is never "kept" back into the strip.
	@Test
	public void TestArchivedInsideKeptWindowStillFollowsBytesRule() {
		List<Candidate> in = new ArrayList<>();
		in.add(c("arch", FINAL, "run1", 0, 1, PbReviewStatusEnumType.ARCHIVED, false, NOW.minusDays(40), true));
		List<Decision> out = PbRetentionSelector.select(in, 3, 30, NOW);
		assertAction(out, "arch", Action.DELETE_BYTES);
	}

	/// D = 0 means bytes go on the first sweep after archival.
	@Test
	public void TestZeroDaysDeletesImmediately() {
		List<Candidate> in = Collections.singletonList(
			c("arch", FINAL, "run1", 0, 1, PbReviewStatusEnumType.ARCHIVED, false, NOW.minusSeconds(1), true));
		List<Decision> out = PbRetentionSelector.select(in, 1, 0, NOW);
		assertAction(out, "arch", Action.DELETE_BYTES);
	}

	/// ---- rule 2: stage intermediates -----------------------------------------------------------

	/// A stage is kept while a kept final references it, directly or through another stage; the stage
	/// chain of an archived final is archived with it. Stages never consume the attempt budget.
	@Test
	public void TestStagesFollowTheFinalThatReferencesThem() {
		List<Candidate> in = new ArrayList<>();
		/// old attempt: stage s1a -> stage s1b -> final f1 (f1 refs s1b, s1b refs s1a)
		in.add(c("s1a", STAGE, "run1", 0, 1, PbReviewStatusEnumType.GENERATED, false, null, true));
		in.add(c("s1b", STAGE, "run1", 0, 2, PbReviewStatusEnumType.GENERATED, false, null, true, "s1a"));
		in.add(c("f1", FINAL, "run1", 0, 3, PbReviewStatusEnumType.GENERATED, false, null, true, "s1b"));
		/// new attempt: stage s2 -> final f2
		in.add(c("s2", STAGE, "run2", 0, 4, PbReviewStatusEnumType.GENERATED, false, null, true));
		in.add(c("f2", FINAL, "run2", 0, 5, PbReviewStatusEnumType.GENERATED, true, null, true, "s2"));
		/// an orphan stage nobody references
		in.add(c("sx", STAGE, "run2", 0, 6, PbReviewStatusEnumType.GENERATED, false, null, true));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		assertAction(out, "f1", Action.ARCHIVE);
		assertAction(out, "s1a", Action.ARCHIVE);
		assertAction(out, "s1b", Action.ARCHIVE);
		assertAction(out, "f2", Action.KEEP);
		assertAction(out, "s2", Action.KEEP);
		assertEquals("stage referenced by a kept final", only(out, "s2").getReason());
		assertAction(out, "sx", Action.ARCHIVE);
	}

	/// An APPROVED final outside the K window keeps its stages too - rule 1 is a KEEP, and references
	/// from any KEEP count.
	@Test
	public void TestApprovedFinalKeepsItsStagesOutsideTheWindow() {
		List<Candidate> in = new ArrayList<>();
		in.add(c("s0", STAGE, "run0", 0, 1, PbReviewStatusEnumType.GENERATED, false, null, true));
		in.add(c("f0", FINAL, "run0", 0, 2, PbReviewStatusEnumType.APPROVED, false, null, true, "s0"));
		in.add(generated("f1", "run1", 0, 3));
		in.add(generated("f2", "run2", 0, 4));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		assertAction(out, "f0", Action.KEEP);
		assertAction(out, "s0", Action.KEEP);
		assertAction(out, "f1", Action.ARCHIVE);
		assertAction(out, "f2", Action.KEEP);
	}

	/// A reference to an artifact outside the node (or already deleted) must not break the walk, and a
	/// reference cycle between stages must terminate.
	@Test
	public void TestDanglingAndCyclicReferencesTerminate() {
		List<Candidate> in = new ArrayList<>();
		in.add(c("sa", STAGE, "run1", 0, 1, PbReviewStatusEnumType.GENERATED, false, null, true, "sb", "missing"));
		in.add(c("sb", STAGE, "run1", 0, 2, PbReviewStatusEnumType.GENERATED, false, null, true, "sa"));
		in.add(c("f", FINAL, "run1", 0, 3, PbReviewStatusEnumType.GENERATED, false, null, true, "sa", "elsewhere"));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		assertAction(out, "f", Action.KEEP);
		assertAction(out, "sa", Action.KEEP);
		assertAction(out, "sb", Action.KEEP);
		assertEquals(3, out.size());
	}

	/// An archived stage follows the bytes rule like any archived artifact; a selected stage (not a
	/// state the executor produces, but the model permits it) is kept.
	@Test
	public void TestArchivedAndSelectedStages() {
		List<Candidate> in = new ArrayList<>();
		in.add(c("sArch", STAGE, "run1", 0, 1, PbReviewStatusEnumType.ARCHIVED, false, NOW.minusDays(31), true));
		in.add(c("sSel", STAGE, "run1", 0, 2, PbReviewStatusEnumType.GENERATED, true, null, true));
		in.add(c("sRev", STAGE, "run1", 0, 3, PbReviewStatusEnumType.IN_REVIEW, false, null, true));
		List<Decision> out = PbRetentionSelector.select(in, 1, 30, NOW);
		assertAction(out, "sArch", Action.DELETE_BYTES);
		assertAction(out, "sSel", Action.KEEP);
		assertAction(out, "sRev", Action.KEEP);
	}

	/// ---- shape ---------------------------------------------------------------------------------

	/// Output is one decision per input, in input order, regardless of role interleaving.
	@Test
	public void TestOutputIsInputOrder() {
		List<Candidate> in = new ArrayList<>();
		in.add(generated("z", "runZ", 0, 9));
		in.add(c("s", STAGE, "runA", 0, 1, PbReviewStatusEnumType.GENERATED, false, null, true));
		in.add(generated("a", "runA", 0, 2));
		in.add(c("m", FINAL, "runM", 0, 5, PbReviewStatusEnumType.APPROVED, false, null, true));
		List<Decision> out = PbRetentionSelector.select(in, 2, 30, NOW);
		assertEquals(4, out.size());
		assertEquals("z", out.get(0).getCandidate().getObjectId());
		assertEquals("s", out.get(1).getCandidate().getObjectId());
		assertEquals("a", out.get(2).getCandidate().getObjectId());
		assertEquals("m", out.get(3).getCandidate().getObjectId());
		for(Decision d : out) {
			assertNotNull(d.getAction());
			assertNotNull(d.getReason());
		}
	}

	@Test
	public void TestEmptyInputIsEmptyOutput() {
		assertTrue(PbRetentionSelector.select(Collections.emptyList(), 3, 30, NOW).isEmpty());
		assertTrue(PbRetentionSelector.select(null, 3, 30, NOW).isEmpty());
	}

	/// The defaults are the §23 numbers; the util exposes them as the route's defaults.
	@Test
	public void TestDefaults() {
		assertEquals(3, PbRetentionSelector.DEFAULT_KEEP_ATTEMPTS);
		assertEquals(30, PbRetentionSelector.DEFAULT_DELETE_BYTES_AFTER_DAYS);
	}

	/// Parameter bounds are rejected, not clamped - the util turns these into a 400.
	@Test
	public void TestParameterBoundsAreRejected() {
		List<Candidate> in = Collections.singletonList(generated("x", "run", 0, 1));
		expectIllegal(() -> PbRetentionSelector.select(in, 0, 30, NOW));
		expectIllegal(() -> PbRetentionSelector.select(in, PbRetentionSelector.MAX_KEEP_ATTEMPTS + 1, 30, NOW));
		expectIllegal(() -> PbRetentionSelector.select(in, 3, -1, NOW));
		expectIllegal(() -> PbRetentionSelector.select(in, 3, PbRetentionSelector.MAX_DELETE_BYTES_AFTER_DAYS + 1, NOW));
		expectIllegal(() -> PbRetentionSelector.select(in, 3, 30, null));
		expectIllegal(() -> new Candidate(null, "n", FINAL, null, 0, 1, PbReviewStatusEnumType.GENERATED, false, null, true, null));
	}

	private static void expectIllegal(Runnable r) {
		try {
			r.run();
			fail("Expected IllegalArgumentException");
		}
		catch(IllegalArgumentException e) {
			/// expected
		}
	}
}
