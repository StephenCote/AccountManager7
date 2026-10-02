package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.ExtractCheckpoint;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// Extraction checkpoint (incremental persistence + resume) tests.
///
/// Real DB: every case writes and reads an actual `.pbExtractProgress.<workObjectId>` data.note
/// through AccessPoint, as a test user (never admin). No LLM — the checkpoint contract is
/// independent of what the model returns, which is what makes it cheap to assert exhaustively.
///
/// Lives in the production package because the checkpoint helpers are package-private; same
/// convention as TestLlmJsonSalvage and BookContextTestAccess here.
///
/// Motivating failure (measured 2026-09-13): chunked extraction accumulated every scene in memory
/// and wrote nothing until the final chunk. A 17-chunk run reached chunk 11, nginx returned 504 at
/// exactly 900s, and ~27 minutes of LLM output was discarded with nothing server-side logged. The
/// async job layer keeps a finished result alive for a TTL, which covers a lost CONNECTION; these
/// checkpoints cover a lost PROCESS.
public class TestExtractCheckpoint extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestExtractCheckpoint.class);

	private static final int CHUNK_SIZE = 2000;
	private static final int OVERLAP = 200;

	private BaseRecord testUser;
	private String groupPath;

	/// A distinct group per test run keeps checkpoints from colliding across runs, since the note
	/// name is keyed on the work objectId and these are synthetic ids.
	private void prepare() {
		testUser = getCreateUser("pbCkptUser");
		assertNotNull("test user", testUser);
		groupPath = "~/PbCheckpointTests";
	}

	private static Map<String, Object> scene(String title, String blurb, int sourceChunk) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("title", title);
		s.put("blurb", blurb);
		s.put("setting", "a hall");
		s.put("characters", new ArrayList<String>());
		s.put("sourceChunk", sourceChunk);
		/// The transient ~2000-char passage carrier. Must never reach the persisted note.
		s.put("sourceText", "PASSAGE-" + sourceChunk + "-" + repeat("x", 300));
		return s;
	}

	private static String repeat(String s, int n) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) sb.append(s);
		return sb.toString();
	}

	private ExtractCheckpoint newCheckpoint(String textHash, int processed, int total,
			List<Map<String, Object>> scenes) {
		ExtractCheckpoint cp = new ExtractCheckpoint();
		cp.textHash = textHash;
		cp.chunkSize = CHUNK_SIZE;
		cp.overlap = OVERLAP;
		cp.totalChunks = total;
		cp.chunksProcessed = processed;
		cp.scenes = scenes;
		return cp;
	}

	// ── the core contract: partial work survives and resumes at the right chunk ────

	/// The defect, directly: work completed before a crash must come back, and the run must
	/// restart at the first UNprocessed chunk rather than at zero.
	@Test
	public void TestCheckpointRoundTripsScenesAndResumeIndex() throws Exception {
		prepare();
		String work = "ckpt-roundtrip-" + UUID.randomUUID();
		String hash = PictureBookUtil.extractTextHash("the source document text");

		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("The Arrival", "She reaches the gate.", 0));
		scenes.add(scene("The Hall", "Lamps gutter.", 1));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work,
				newCheckpoint(hash, 2, 17, scenes));

		ExtractCheckpoint back = PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work,
				hash, CHUNK_SIZE, OVERLAP, 17);
		assertNotNull("checkpoint should load", back);
		assertEquals("resume index", 2, back.chunksProcessed);
		assertEquals("scene count", 2, back.scenes.size());
		assertEquals("The Arrival", back.scenes.get(0).get("title"));
		assertEquals("The Hall", back.scenes.get(1).get("title"));
		assertEquals("Lamps gutter.", back.scenes.get(1).get("blurb"));
		assertEquals("a hall", back.scenes.get(1).get("setting"));
	}

	/// sourceText is a ~2000-char passage per scene and is deliberately NOT persisted — the chunk
	/// index stands in for it and rehydrates from the identical chunk list. If it leaked into the
	/// note, the checkpoint would grow by the size of the whole document as scenes accumulated.
	@Test
	public void TestSourceTextIsStrippedButChunkIndexSurvives() throws Exception {
		prepare();
		String work = "ckpt-strip-" + UUID.randomUUID();
		String hash = PictureBookUtil.extractTextHash("strip me");

		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Only Scene", "b", 3));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work,
				newCheckpoint(hash, 4, 9, scenes));

		BaseRecord note = PictureBookUtil.loadProgressNote(testUser, groupPath, work);
		assertNotNull("progress note", note);
		String json = note.get("text");
		assertNotNull("note text", json);
		assertFalse("sourceText must not be persisted", json.contains("sourceText"));
		assertFalse("the passage itself must not be persisted", json.contains("PASSAGE-3"));
		assertTrue("the chunk index must be persisted", json.contains("sourceChunk"));

		ExtractCheckpoint back = PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work,
				hash, CHUNK_SIZE, OVERLAP, 9);
		assertNotNull(back);
		assertEquals("chunk index round-trips", 3,
				PictureBookUtil.intOf(back.scenes.get(0).get("sourceChunk")));
		assertNull("sourceText is absent until rehydrated", back.scenes.get(0).get("sourceText"));

		/// The in-memory caller mutates the scene map on the returned list, so verify the shape
		/// resume relies on: the persisted list is mutable and accepts the rehydrated passage.
		back.scenes.get(0).put("sourceText", "REHYDRATED");
		assertEquals("REHYDRATED", back.scenes.get(0).get("sourceText"));
	}

	// ── the validity guard: resuming against the wrong document would corrupt output ───

	/// Chunk index n only denotes a passage relative to a specific text. If the document changed,
	/// resuming would splice scenes from the old text into a run over the new one — so the
	/// checkpoint must be refused, not adapted.
	@Test
	public void TestCheckpointRejectedWhenSourceTextChanged() throws Exception {
		prepare();
		String work = "ckpt-hash-" + UUID.randomUUID();
		String oldHash = PictureBookUtil.extractTextHash("the original document");
		String newHash = PictureBookUtil.extractTextHash("the document, revised");
		assertFalse("hashes must differ", oldHash.equals(newHash));

		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Stale", "from the old text", 0));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work,
				newCheckpoint(oldHash, 3, 10, scenes));

		assertNotNull("still valid for the original text",
				PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work, oldHash,
						CHUNK_SIZE, OVERLAP, 10));
		assertNull("must be refused for changed text",
				PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work, newHash,
						CHUNK_SIZE, OVERLAP, 10));
	}

	/// Same reasoning for the chunking parameters: identical text chunked differently yields
	/// different passages per index.
	@Test
	public void TestCheckpointRejectedWhenChunkingChanged() throws Exception {
		prepare();
		String work = "ckpt-chunking-" + UUID.randomUUID();
		String hash = PictureBookUtil.extractTextHash("same text throughout");
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("A", "a", 0));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work,
				newCheckpoint(hash, 2, 12, scenes));

		assertNull("different chunk size", PictureBookUtil.loadExtractCheckpoint(
				testUser, groupPath, work, hash, 1500, OVERLAP, 12));
		assertNull("different overlap", PictureBookUtil.loadExtractCheckpoint(
				testUser, groupPath, work, hash, CHUNK_SIZE, 100, 12));
		assertNull("different total chunk count", PictureBookUtil.loadExtractCheckpoint(
				testUser, groupPath, work, hash, CHUNK_SIZE, OVERLAP, 13));
		assertNotNull("unchanged chunking still resumes", PictureBookUtil.loadExtractCheckpoint(
				testUser, groupPath, work, hash, CHUNK_SIZE, OVERLAP, 12));
	}

	/// A processed count past the end of the run means the record is inconsistent with the text,
	/// and a zero/negative one has nothing to resume. Both must start over rather than index
	/// out of the chunk list.
	@Test
	public void TestCheckpointRejectedWhenProcessedCountIsOutOfRange() throws Exception {
		prepare();
		String hash = PictureBookUtil.extractTextHash("range check");

		String tooHigh = "ckpt-high-" + UUID.randomUUID();
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, tooHigh,
				newCheckpoint(hash, 11, 8, new ArrayList<Map<String, Object>>()));
		assertNull("processed > total must be refused", PictureBookUtil.loadExtractCheckpoint(
				testUser, groupPath, tooHigh, hash, CHUNK_SIZE, OVERLAP, 8));

		String zero = "ckpt-zero-" + UUID.randomUUID();
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, zero,
				newCheckpoint(hash, 0, 8, new ArrayList<Map<String, Object>>()));
		assertNull("nothing processed means nothing to resume",
				PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, zero, hash,
						CHUNK_SIZE, OVERLAP, 8));
	}

	/// An absent checkpoint is the ordinary first-run case and must read as "start fresh", not
	/// as an error.
	@Test
	public void TestMissingCheckpointReadsAsStartFresh() throws Exception {
		prepare();
		assertNull(PictureBookUtil.loadExtractCheckpoint(testUser, groupPath,
				"ckpt-absent-" + UUID.randomUUID(),
				PictureBookUtil.extractTextHash("x"), CHUNK_SIZE, OVERLAP, 5));
	}

	// ── the plan's named resume risk: revisions are matched BY TITLE ───────────────

	/// extractChunkedInternal applies a chunk's `revisions` by matching `title` against the
	/// accumulated scene list, and its `removals` the same way. So a resumed run's correctness
	/// depends entirely on titles surviving the round-trip byte-exact, in order, with no
	/// duplicates — otherwise a later chunk's revision silently lands on the wrong scene or on
	/// none at all. This asserts that property directly, including titles with the punctuation
	/// and non-ASCII characters an LLM actually emits.
	@Test
	public void TestTitlesRoundTripExactlySoRevisionMatchingStillResolves() throws Exception {
		prepare();
		String work = "ckpt-titles-" + UUID.randomUUID();
		String hash = PictureBookUtil.extractTextHash("title fidelity");

		String[] titles = {
			"The Arrival",
			"A Quarrel, Interrupted",
			"\"Nothing\" She Said",
			"Café at Dawn — Later",
			"Scene 5: the 2nd Descent"
		};
		List<Map<String, Object>> scenes = new ArrayList<>();
		for (int i = 0; i < titles.length; i++) {
			Map<String, Object> s = scene(titles[i], "blurb " + i, i);
			s.put("index", i);
			scenes.add(s);
		}
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work,
				newCheckpoint(hash, titles.length, 17, scenes));

		ExtractCheckpoint back = PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work,
				hash, CHUNK_SIZE, OVERLAP, 17);
		assertNotNull(back);
		assertEquals("no scenes dropped or duplicated", titles.length, back.scenes.size());
		for (int i = 0; i < titles.length; i++) {
			assertEquals("title " + i + " must round-trip byte-exact",
					titles[i], back.scenes.get(i).get("title"));
			assertEquals("order must be preserved", i,
					PictureBookUtil.intOf(back.scenes.get(i).get("index")));
		}

		/// Now run the REAL merge against the resumed list — PictureBookUtil.mergeChunkResult, the
		/// same code the chunk loop calls. An earlier version of this test re-implemented the
		/// matching inline, which proved only that JSON round-trips titles and would have stayed
		/// green through a genuine bug in the merge.
		String revTitle = "Café at Dawn — Later";
		Map<String, Object> rev = new LinkedHashMap<>();
		rev.put("title", revTitle);
		rev.put("blurb", "revised after resume");
		Map<String, Object> chunkResult = new LinkedHashMap<>();
		chunkResult.put("revisions", listOf(rev));

		PictureBookUtil.mergeChunkResult(back.scenes, chunkResult, "the next passage", 9);

		assertEquals("no scenes added or dropped by a revision", titles.length, back.scenes.size());
		assertEquals("a revision keyed on a PRE-CRASH title must land on that scene",
				"revised after resume", back.scenes.get(3).get("blurb"));
		assertEquals("the matched scene keeps its title", revTitle, back.scenes.get(3).get("title"));
		assertEquals("other scenes are untouched", "blurb 0", back.scenes.get(0).get("blurb"));

		/// And a removal must resolve against the same pre-crash titles.
		Map<String, Object> removalChunk = new LinkedHashMap<>();
		removalChunk.put("removals", listOf("\"Nothing\" She Said"));
		int before = back.scenes.size();
		PictureBookUtil.mergeChunkResult(back.scenes, removalChunk, "another passage", 10);
		assertEquals("removal by pre-crash title must resolve", before - 1, back.scenes.size());
		for (Map<String, Object> s : back.scenes) {
			assertFalse("the removed scene must be gone",
					"\"Nothing\" She Said".equals(s.get("title")));
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> List<T> listOf(T... items) {
		List<T> l = new ArrayList<>();
		for (T i : items) l.add(i);
		return l;
	}

	// ── the merge itself (PictureBookUtil.mergeChunkResult) ───────────────────────

	/// Additions get their index, their transient sourceText, and the sourceChunk the checkpoint
	/// persists in its place.
	@Test
	public void TestMergeAdditionsAreIndexedAndCarryTheirChunk() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Existing", "e", 0));

		Map<String, Object> add = new LinkedHashMap<>();
		add.put("title", "Newly Found");
		add.put("blurb", "n");
		Map<String, Object> chunk = new LinkedHashMap<>();
		chunk.put("additions", listOf(add));

		PictureBookUtil.mergeChunkResult(scenes, chunk, "PASSAGE-7", 7);

		assertEquals(2, scenes.size());
		Map<String, Object> added = scenes.get(1);
		assertEquals("Newly Found", added.get("title"));
		assertEquals("index is its position in the running list", 1,
				PictureBookUtil.intOf(added.get("index")));
		assertEquals("the chunk index must be recorded for checkpoint/resume", 7,
				PictureBookUtil.intOf(added.get("sourceChunk")));
		assertEquals("PASSAGE-7", added.get("sourceText"));
		assertEquals(Boolean.FALSE, added.get("userEdited"));
	}

	/// A revision must never rewrite the title — it is the match key, so changing it would orphan
	/// the scene from every later chunk's revisions and removals.
	@Test
	public void TestMergeRevisionNeverRewritesTheMatchKey() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Stable Title", "original", 0));

		Map<String, Object> rev = new LinkedHashMap<>();
		rev.put("title", "Stable Title");
		rev.put("blurb", "updated");
		Map<String, Object> chunk = new LinkedHashMap<>();
		chunk.put("revisions", listOf(rev));

		PictureBookUtil.mergeChunkResult(scenes, chunk, "p", 1);

		assertEquals("Stable Title", scenes.get(0).get("title"));
		assertEquals("updated", scenes.get(0).get("blurb"));
	}

	/// A null field in a revision must not clobber a good stored value. Models routinely emit null
	/// for fields they did not reconsider.
	@Test
	public void TestMergeRevisionIgnoresNullFields() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Keep", "good blurb", 0));

		Map<String, Object> rev = new LinkedHashMap<>();
		rev.put("title", "Keep");
		rev.put("blurb", null);
		rev.put("mood", "tense");
		Map<String, Object> chunk = new LinkedHashMap<>();
		chunk.put("revisions", listOf(rev));

		PictureBookUtil.mergeChunkResult(scenes, chunk, "p", 1);

		assertEquals("a null must not overwrite a real value", "good blurb", scenes.get(0).get("blurb"));
		assertEquals("tense", scenes.get(0).get("mood"));
	}

	/// A revision for a title that is not present is a no-op, not an insert — otherwise a
	/// mis-titled revision would silently create a duplicate half-populated scene.
	@Test
	public void TestMergeRevisionForAnUnknownTitleIsANoOp() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Only", "o", 0));

		Map<String, Object> rev = new LinkedHashMap<>();
		rev.put("title", "Does Not Exist");
		rev.put("blurb", "ignored");
		Map<String, Object> chunk = new LinkedHashMap<>();
		chunk.put("revisions", listOf(rev));

		PictureBookUtil.mergeChunkResult(scenes, chunk, "p", 1);

		assertEquals(1, scenes.size());
		assertEquals("o", scenes.get(0).get("blurb"));
	}

	/// Matching is exact, not fuzzy: a revision must not land on a near-miss title.
	@Test
	public void TestMergeTitleMatchIsExact() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("The Arrival", "a", 0));

		Map<String, Object> rev = new LinkedHashMap<>();
		rev.put("title", "the arrival");
		rev.put("blurb", "should not apply");
		Map<String, Object> chunk = new LinkedHashMap<>();
		chunk.put("revisions", listOf(rev));

		PictureBookUtil.mergeChunkResult(scenes, chunk, "p", 1);
		assertEquals("case must not match", "a", scenes.get(0).get("blurb"));
	}

	/// A malformed chunk (missing keys, wrong types, a null entry) must leave the list untouched
	/// rather than throwing — the loop treats an unusable chunk as skippable, not fatal.
	@Test
	public void TestMergeToleratesMalformedChunkResults() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Untouched", "u", 0));

		PictureBookUtil.mergeChunkResult(scenes, new LinkedHashMap<String, Object>(), "p", 1);
		assertEquals(1, scenes.size());

		Map<String, Object> wrongTypes = new LinkedHashMap<>();
		wrongTypes.put("additions", "not a list");
		wrongTypes.put("revisions", 42);
		wrongTypes.put("removals", new LinkedHashMap<String, Object>());
		PictureBookUtil.mergeChunkResult(scenes, wrongTypes, "p", 1);
		assertEquals("wrongly-typed members must be ignored", 1, scenes.size());
		assertEquals("u", scenes.get(0).get("blurb"));

		Map<String, Object> nullEntries = new LinkedHashMap<>();
		nullEntries.put("additions", listOf((Map<String, Object>) null));
		nullEntries.put("revisions", listOf((Map<String, Object>) null));
		PictureBookUtil.mergeChunkResult(scenes, nullEntries, "p", 1);
		assertEquals("null entries must be skipped, not added", 1, scenes.size());

		PictureBookUtil.mergeChunkResult(scenes, null, "p", 1);
		assertEquals(1, scenes.size());
	}

	/// The documented order is additions, then revisions, then removals — within ONE chunk a scene
	/// can be added, revised and removed again, and the end state must reflect all three.
	@Test
	public void TestMergeAppliesAdditionsThenRevisionsThenRemovals() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();

		Map<String, Object> add1 = new LinkedHashMap<>();
		add1.put("title", "Kept");
		add1.put("blurb", "first");
		Map<String, Object> add2 = new LinkedHashMap<>();
		add2.put("title", "Doomed");
		add2.put("blurb", "first");
		Map<String, Object> rev = new LinkedHashMap<>();
		rev.put("title", "Kept");
		rev.put("blurb", "revised in the same chunk that added it");

		Map<String, Object> chunk = new LinkedHashMap<>();
		chunk.put("additions", listOf(add1, add2));
		chunk.put("revisions", listOf(rev));
		chunk.put("removals", listOf("Doomed"));

		PictureBookUtil.mergeChunkResult(scenes, chunk, "p", 2);

		assertEquals(1, scenes.size());
		assertEquals("Kept", scenes.get(0).get("title"));
		assertEquals("revised in the same chunk that added it", scenes.get(0).get("blurb"));
	}

	// ── overwrite + clear lifecycle ───────────────────────────────────────────────

	/// Successive checkpoints in one run must overwrite, not accumulate notes — otherwise the
	/// resume read is ambiguous and the group fills with scratch records.
	@Test
	public void TestSuccessiveCheckpointsOverwriteInPlace() throws Exception {
		prepare();
		String work = "ckpt-overwrite-" + UUID.randomUUID();
		String hash = PictureBookUtil.extractTextHash("overwrite me");

		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("First", "one", 0));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work,
				newCheckpoint(hash, 2, 17, scenes));
		BaseRecord firstNote = PictureBookUtil.loadProgressNote(testUser, groupPath, work);
		assertNotNull(firstNote);

		scenes.add(scene("Second", "two", 2));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work,
				newCheckpoint(hash, 4, 17, scenes));

		ExtractCheckpoint back = PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work,
				hash, CHUNK_SIZE, OVERLAP, 17);
		assertNotNull(back);
		assertEquals("later checkpoint wins", 4, back.chunksProcessed);
		assertEquals(2, back.scenes.size());
		assertEquals("same note reused, not a second one",
				(Object) firstNote.get(FieldNames.FIELD_OBJECT_ID),
				(Object) PictureBookUtil.loadProgressNote(testUser, groupPath, work)
						.get(FieldNames.FIELD_OBJECT_ID));
	}

	/// A completed run must leave no checkpoint behind, or the NEXT extraction of the same
	/// document would resume instead of re-extracting. clearExtractCheckpoint resolves the group
	/// from the work record itself, so this needs a real data.data work to find.
	@Test
	public void TestClearCheckpointRemovesItForACompletedRun() throws Exception {
		prepare();
		long orgId = testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		String docName = "ckptWork-" + UUID.randomUUID();
		BaseRecord work = getCreateData(testUser, docName, "text/plain",
				"some source text".getBytes(), groupPath, orgId);
		assertNotNull("work record", work);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull(workObjectId);

		/// The work's own group is where its checkpoint lives; confirm the resolution the
		/// production path uses rather than assuming it.
		String resolved = PictureBookUtil.findWorkGroupPath(testUser, workObjectId);
		assertNotNull("work group path must resolve", resolved);

		String hash = PictureBookUtil.extractTextHash("some source text");
		List<Map<String, Object>> scenes = new ArrayList<>();
		scenes.add(scene("Done", "d", 0));
		PictureBookUtil.saveExtractCheckpoint(testUser, resolved, workObjectId,
				newCheckpoint(hash, 3, 3, scenes));
		assertNotNull("checkpoint written", PictureBookUtil.loadExtractCheckpoint(
				testUser, resolved, workObjectId, hash, CHUNK_SIZE, OVERLAP, 3));

		PictureBookUtil.clearExtractCheckpoint(testUser, workObjectId);

		assertNull("checkpoint must be gone after completion",
				PictureBookUtil.loadProgressNote(testUser, resolved, workObjectId));
		assertNull("and must read as start-fresh", PictureBookUtil.loadExtractCheckpoint(
				testUser, resolved, workObjectId, hash, CHUNK_SIZE, OVERLAP, 3));
	}

	/// Checkpoints are keyed per work document, so two documents extracting concurrently must not
	/// read each other's progress — the note name carries the work objectId for this reason.
	@Test
	public void TestCheckpointsAreIsolatedPerWorkDocument() throws Exception {
		prepare();
		String hash = PictureBookUtil.extractTextHash("shared hash is irrelevant");
		String workA = "ckpt-iso-a-" + UUID.randomUUID();
		String workB = "ckpt-iso-b-" + UUID.randomUUID();

		List<Map<String, Object>> aScenes = new ArrayList<>();
		aScenes.add(scene("A-only", "a", 0));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, workA,
				newCheckpoint(hash, 2, 17, aScenes));

		List<Map<String, Object>> bScenes = new ArrayList<>();
		bScenes.add(scene("B-only", "b", 0));
		bScenes.add(scene("B-second", "b2", 1));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, workB,
				newCheckpoint(hash, 7, 17, bScenes));

		ExtractCheckpoint a = PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, workA,
				hash, CHUNK_SIZE, OVERLAP, 17);
		ExtractCheckpoint b = PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, workB,
				hash, CHUNK_SIZE, OVERLAP, 17);
		assertNotNull(a);
		assertNotNull(b);
		assertEquals(2, a.chunksProcessed);
		assertEquals(7, b.chunksProcessed);
		assertEquals(1, a.scenes.size());
		assertEquals(2, b.scenes.size());
		assertEquals("A-only", a.scenes.get(0).get("title"));
		assertEquals("B-only", b.scenes.get(0).get("title"));
	}

	// ── Q8: two ranges of ONE manuscript get independent checkpoints ───────────────

	/// The motivating N2/Q8 defect: a series chapter is a RANGE of one shared manuscript, so two
	/// chapters reuse ONE workObjectId. Whole-document text gives an identical textHash and identical
	/// chunking gives an identical guard, so before the fix chapter 2's saveExtractCheckpoint wrote
	/// the SAME note name as chapter 1 and clobbered it — resume protection was silently lost for
	/// every chapter but the last one written.
	///
	/// This proves the fix at the level the bug lived: the two ranges resolve to DISTINCT notes,
	/// neither touches the legacy whole-document note, each loads back its own scenes/resume index,
	/// and clearing one leaves the other intact.
	@Test
	public void TestTwoRangesOfOneDocumentGetIndependentCheckpoints() throws Exception {
		prepare();
		String work = "ckpt-tworanges-" + UUID.randomUUID();
		/// SAME document text for both ranges — this is exactly the case the old guard could not
		/// distinguish, because textHash + chunking are identical across chapters of one manuscript.
		String hash = PictureBookUtil.extractTextHash("the whole shared manuscript");

		ExtractCheckpoint a = newCheckpoint(hash, 2, 17,
				listOf(scene("Chapter One Opens", "a gate", 0)));
		a.startOffset = 0;
		a.endOffset = 1000;
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work, a);

		ExtractCheckpoint b = newCheckpoint(hash, 5, 17,
				listOf(scene("Chapter Two Opens", "a hall", 0),
						scene("Chapter Two Turns", "a storm", 1)));
		b.startOffset = 1000;
		b.endOffset = 2500;
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work, b);

		/// Distinct notes, not one overwritten in place.
		BaseRecord noteA = PictureBookUtil.loadProgressNote(testUser, groupPath, work, 0, 1000);
		BaseRecord noteB = PictureBookUtil.loadProgressNote(testUser, groupPath, work, 1000, 2500);
		assertNotNull("range A note", noteA);
		assertNotNull("range B note", noteB);
		assertFalse("the two ranges must not share one note",
				noteA.get(FieldNames.FIELD_OBJECT_ID).equals(noteB.get(FieldNames.FIELD_OBJECT_ID)));

		/// The legacy whole-document note must be untouched — a ranged save must never write it.
		assertNull("no whole-document note should exist for a ranged run",
				PictureBookUtil.loadProgressNote(testUser, groupPath, work));

		/// Each range loads back ITS OWN state — the crux: no cross-contamination.
		ExtractCheckpoint backA = PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work,
				0, 1000, hash, CHUNK_SIZE, OVERLAP, 17);
		ExtractCheckpoint backB = PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work,
				1000, 2500, hash, CHUNK_SIZE, OVERLAP, 17);
		assertNotNull(backA);
		assertNotNull(backB);
		assertEquals("range A resume index", 2, backA.chunksProcessed);
		assertEquals("range B resume index", 5, backB.chunksProcessed);
		assertEquals("range A scene count", 1, backA.scenes.size());
		assertEquals("range B scene count", 2, backB.scenes.size());
		assertEquals("Chapter One Opens", backA.scenes.get(0).get("title"));
		assertEquals("Chapter Two Opens", backB.scenes.get(0).get("title"));
		assertEquals("range A offsets round-trip", (Integer) 0, backA.startOffset);
		assertEquals((Integer) 1000, backA.endOffset);
		assertEquals("range B offsets round-trip", (Integer) 1000, backB.startOffset);
		assertEquals((Integer) 2500, backB.endOffset);

		/// The whole-document (null-range) load must NOT find either ranged note.
		assertNull("null-range load must not resolve a ranged note",
				PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work, hash,
						CHUNK_SIZE, OVERLAP, 17));

		/// Clearing one range leaves the other intact — chapter-scoped completion, not a blanket wipe.
		PictureBookUtil.clearExtractCheckpointAt(testUser, groupPath, work, 0, 1000);
		assertNull("cleared range A is gone",
				PictureBookUtil.loadProgressNote(testUser, groupPath, work, 0, 1000));
		assertNotNull("range B survives A's clear",
				PictureBookUtil.loadProgressNote(testUser, groupPath, work, 1000, 2500));
	}

	/// §8 REQUIRED #1: orphan cleanup was an EQUALS on the bare name, which — once chapters write
	/// range-suffixed notes — would leave every range note orphaned forever. It is now a name-prefix
	/// (LIKE) match, so a single cleanup for a gone work removes the whole-document note AND every
	/// range note for that work, and nothing belonging to another work.
	@Test
	public void TestOrphanCleanupPrefixDeletesEveryRangeNote() throws Exception {
		prepare();
		String work = "ckpt-orphan-" + UUID.randomUUID();
		String other = "ckpt-orphan-other-" + UUID.randomUUID();
		String hash = PictureBookUtil.extractTextHash("orphan manuscript");

		/// Two range notes plus the bare whole-document note, all for the same work.
		ExtractCheckpoint whole = newCheckpoint(hash, 1, 4, listOf(scene("Whole", "w", 0)));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work, whole);
		ExtractCheckpoint r1 = newCheckpoint(hash, 1, 4, listOf(scene("R1", "r1", 0)));
		r1.startOffset = 0; r1.endOffset = 500;
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work, r1);
		ExtractCheckpoint r2 = newCheckpoint(hash, 1, 4, listOf(scene("R2", "r2", 0)));
		r2.startOffset = 500; r2.endOffset = 900;
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work, r2);

		/// A different work's note must NOT be swept up by the prefix.
		ExtractCheckpoint keep = newCheckpoint(hash, 1, 4, listOf(scene("Keep", "k", 0)));
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, other, keep);

		int deleted = PictureBookUtil.deleteOrphanedExtractCheckpoints(testUser, work);
		assertEquals("bare + two ranges all deleted by one prefix cleanup", 3, deleted);

		assertNull("whole-document note gone",
				PictureBookUtil.loadProgressNote(testUser, groupPath, work));
		assertNull("range note 1 gone",
				PictureBookUtil.loadProgressNote(testUser, groupPath, work, 0, 500));
		assertNull("range note 2 gone",
				PictureBookUtil.loadProgressNote(testUser, groupPath, work, 500, 900));
		assertNotNull("another work's checkpoint must be untouched",
				PictureBookUtil.loadProgressNote(testUser, groupPath, other));
	}

	// ── discovery + discard: the surface for a checkpoint with no book behind it ────

	/// Until createFromScenes runs, an interrupted single-document extraction leaves ONLY its
	/// checkpoint note — no book, nothing the list could show, and nothing a user could delete short
	/// of starting another LLM run with ?fresh=true. describeExtractCheckpoints is the read side:
	/// it must report progress, scene and failed-passage counts WITHOUT the scene payload, and
	/// discardExtractCheckpoint must remove the note without any extraction.
	@Test
	public void TestDescribeAndDiscardCheckpointForARealWork() throws Exception {
		prepare();
		long orgId = testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		String docName = "ckptDescribe-" + UUID.randomUUID();
		BaseRecord work = getCreateData(testUser, docName, "text/plain",
				"the interrupted manuscript".getBytes(), groupPath, orgId);
		assertNotNull(work);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String resolved = PictureBookUtil.findWorkGroupPath(testUser, workObjectId);
		assertNotNull(resolved);

		assertTrue("no checkpoint yet means an empty description, not an error",
				PictureBookUtil.describeExtractCheckpoints(testUser, workObjectId).isEmpty());

		String hash = PictureBookUtil.extractTextHash("the interrupted manuscript");
		ExtractCheckpoint cp = newCheckpoint(hash, 5, 17,
				listOf(scene("One", "a", 0), scene("Two", "b", 2), scene("Three", "c", 4)));
		cp.failedExtractions = new ArrayList<>();
		/// One retryable passage failure and one breaker entry (stoppedEarly), which is NOT retryable.
		cp.failedExtractions.add("{\"context\":\"extract-scenes-chunk:3/17\",\"kind\":\"unparseable\"}");
		cp.failedExtractions.add("{\"context\":\"extract-scenes-chunk:6/17\",\"stoppedEarly\":true}");
		PictureBookUtil.saveExtractCheckpoint(testUser, resolved, workObjectId, cp);

		List<Map<String, Object>> rows = PictureBookUtil.describeExtractCheckpoints(testUser, workObjectId);
		assertEquals("exactly the one whole-document checkpoint", 1, rows.size());
		Map<String, Object> row = rows.get(0);
		assertEquals(workObjectId, row.get("workObjectId"));
		assertEquals(docName, row.get("workName"));
		assertEquals(Boolean.FALSE, row.get("workMissing"));
		assertNull("whole-document row has no range", row.get("startOffset"));
		assertNull(row.get("endOffset"));
		assertEquals(5, row.get("chunksProcessed"));
		assertEquals(17, row.get("totalChunks"));
		assertEquals(3, row.get("sceneCount"));
		assertEquals("only the retryable failure counts", 1, row.get("failedCount"));
		assertEquals(Boolean.FALSE, row.get("complete"));
		assertNotNull(row.get("noteObjectId"));
		assertFalse("updatedAt must be populated", String.valueOf(row.get("updatedAt")).isEmpty());
		assertFalse("the scene payload must not be in a list row", row.containsKey("scenes"));

		int deleted = PictureBookUtil.discardExtractCheckpoint(testUser, workObjectId, null, null);
		assertEquals(1, deleted);
		assertNull("note gone", PictureBookUtil.loadProgressNote(testUser, resolved, workObjectId));
		assertTrue(PictureBookUtil.describeExtractCheckpoints(testUser, workObjectId).isEmpty());
		assertEquals("a second discard is a no-op, not an error", 0,
				PictureBookUtil.discardExtractCheckpoint(testUser, workObjectId, null, null));
	}

	/// A run that reached the end with retryable failures keeps its checkpoint with
	/// chunksProcessed == totalChunks; the description must say so (complete=true, failedCount>0)
	/// because the wizard offers "Retry failed passages" rather than "Resume" for it.
	@Test
	public void TestDescribeReportsCompleteWithFailuresAndPerRangeDiscard() throws Exception {
		prepare();
		long orgId = testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord work = getCreateData(testUser, "ckptRanges-" + UUID.randomUUID(), "text/plain",
				"a shared manuscript".getBytes(), groupPath, orgId);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String resolved = PictureBookUtil.findWorkGroupPath(testUser, workObjectId);
		String hash = PictureBookUtil.extractTextHash("a shared manuscript");

		ExtractCheckpoint done = newCheckpoint(hash, 4, 4, listOf(scene("Done", "d", 0)));
		done.startOffset = 0; done.endOffset = 1000;
		done.failedExtractions = new ArrayList<>();
		done.failedExtractions.add("{\"context\":\"extract-scenes-chunk:2/4\",\"kind\":\"refusal\"}");
		PictureBookUtil.saveExtractCheckpoint(testUser, resolved, workObjectId, done);

		ExtractCheckpoint partial = newCheckpoint(hash, 1, 4, listOf(scene("Partial", "p", 0)));
		partial.startOffset = 1000; partial.endOffset = 2000;
		PictureBookUtil.saveExtractCheckpoint(testUser, resolved, workObjectId, partial);

		List<Map<String, Object>> rows = PictureBookUtil.describeExtractCheckpoints(testUser, workObjectId);
		assertEquals(2, rows.size());
		Map<String, Object> doneRow = null, partialRow = null;
		for (Map<String, Object> r : rows) {
			if (Integer.valueOf(0).equals(r.get("startOffset"))) doneRow = r; else partialRow = r;
		}
		assertNotNull(doneRow);
		assertNotNull(partialRow);
		assertEquals("range parsed from the note name", 1000, doneRow.get("endOffset"));
		assertEquals(Boolean.TRUE, doneRow.get("complete"));
		assertEquals(1, doneRow.get("failedCount"));
		assertEquals(1000, partialRow.get("startOffset"));
		assertEquals(2000, partialRow.get("endOffset"));
		assertEquals(Boolean.FALSE, partialRow.get("complete"));

		/// Discarding one range must leave the sibling range alone.
		assertEquals(1, PictureBookUtil.discardExtractCheckpoint(testUser, workObjectId, 0, 1000));
		rows = PictureBookUtil.describeExtractCheckpoints(testUser, workObjectId);
		assertEquals(1, rows.size());
		assertEquals(1000, rows.get(0).get("startOffset"));
		/// And a null-range discard does NOT touch a ranged note — it is keyed exactly like clear.
		assertEquals(0, PictureBookUtil.discardExtractCheckpoint(testUser, workObjectId, null, null));
		assertNotNull(PictureBookUtil.loadProgressNote(testUser, resolved, workObjectId, 1000, 2000));
	}

	/// The user-wide list must return the caller's checkpoints and ONLY the caller's: AccessPoint.list
	/// authorizes the query shape, not each row, so an org-wide prefix search without the owner
	/// condition would surface another user's unfinished work. It must also include a checkpoint
	/// whose work document is gone (workMissing), since the list is the one place an orphan can be
	/// discarded from.
	@Test
	public void TestListIsOwnerScopedAndIncludesOrphans() throws Exception {
		prepare();
		long orgId = testUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		BaseRecord otherUser = getCreateUser("pbCkptOther");
		assertNotNull(otherUser);
		String hash = PictureBookUtil.extractTextHash("owner scoping");

		BaseRecord mine = getCreateData(testUser, "ckptMine-" + UUID.randomUUID(), "text/plain",
				"owner scoping".getBytes(), groupPath, orgId);
		String mineId = mine.get(FieldNames.FIELD_OBJECT_ID);
		PictureBookUtil.saveExtractCheckpoint(testUser, PictureBookUtil.findWorkGroupPath(testUser, mineId),
				mineId, newCheckpoint(hash, 2, 9, listOf(scene("Mine", "m", 0))));

		/// A checkpoint for a work id that resolves to no document at all.
		String orphanId = UUID.randomUUID().toString();
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, orphanId,
				newCheckpoint(hash, 1, 3, listOf(scene("Orphan", "o", 0))));

		/// Another user's checkpoint in the same organisation.
		String theirsId = "ckpt-theirs-" + UUID.randomUUID();
		PictureBookUtil.saveExtractCheckpoint(otherUser, "~/PbCheckpointTestsOther", theirsId,
				newCheckpoint(hash, 1, 3, listOf(scene("Theirs", "t", 0))));

		List<Map<String, Object>> rows = PictureBookUtil.listExtractCheckpoints(testUser);
		Map<String, Object> mineRow = null, orphanRow = null;
		for (Map<String, Object> r : rows) {
			assertFalse("another user's checkpoint must not be listed", theirsId.equals(r.get("workObjectId")));
			if (mineId.equals(r.get("workObjectId"))) mineRow = r;
			if (orphanId.equals(r.get("workObjectId"))) orphanRow = r;
		}
		assertNotNull("own checkpoint listed", mineRow);
		assertEquals(mine.get(FieldNames.FIELD_NAME), mineRow.get("workName"));
		assertEquals(Boolean.FALSE, mineRow.get("workMissing"));
		assertEquals(2, mineRow.get("chunksProcessed"));
		assertNotNull("orphan listed so it can be discarded", orphanRow);
		assertEquals(Boolean.TRUE, orphanRow.get("workMissing"));
		assertNull(orphanRow.get("workName"));

		/// The other user sees theirs and not mine.
		List<Map<String, Object>> theirRows = PictureBookUtil.listExtractCheckpoints(otherUser);
		boolean sawTheirs = false;
		for (Map<String, Object> r : theirRows) {
			assertFalse(mineId.equals(r.get("workObjectId")));
			if (theirsId.equals(r.get("workObjectId"))) sawTheirs = true;
		}
		assertTrue(sawTheirs);

		/// Discarding the orphan goes through the gone-work sweep and must remove it.
		assertEquals(1, PictureBookUtil.discardExtractCheckpoint(testUser, orphanId, null, null));
		for (Map<String, Object> r : PictureBookUtil.listExtractCheckpoints(testUser)) {
			assertFalse("orphan gone after discard", orphanId.equals(r.get("workObjectId")));
		}

		PictureBookUtil.discardExtractCheckpoint(testUser, mineId, null, null);
		PictureBookUtil.deleteOrphanedExtractCheckpoints(otherUser, theirsId);
	}

	/// failedExtractions accumulated before a crash must come back with the resume, or a resumed
	/// run would report a clean extraction while some chunks had in fact failed to parse.
	@Test
	public void TestFailedExtractionsSurviveTheCheckpoint() throws Exception {
		prepare();
		String work = "ckpt-failed-" + UUID.randomUUID();
		String hash = PictureBookUtil.extractTextHash("failures carry forward");

		ExtractCheckpoint cp = newCheckpoint(hash, 3, 17, new ArrayList<Map<String, Object>>());
		cp.failedExtractions = new ArrayList<>();
		cp.failedExtractions.add("extract-scenes-chunk:2/17 unparseable");
		PictureBookUtil.saveExtractCheckpoint(testUser, groupPath, work, cp);

		ExtractCheckpoint back = PictureBookUtil.loadExtractCheckpoint(testUser, groupPath, work,
				hash, CHUNK_SIZE, OVERLAP, 17);
		assertNotNull(back);
		assertEquals(1, back.failedExtractions.size());
		assertEquals("extract-scenes-chunk:2/17 unparseable", back.failedExtractions.get(0));
	}
}
