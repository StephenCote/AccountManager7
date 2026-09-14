package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.SummarizeProgress;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.ExtractCheckpoint;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// The chunk loop's CONTROL FLOW, driven through a scripted LLM instead of a live model.
///
/// Why this file exists: every expensive defect in this feature has lived in these branches, and
/// none of them was reachable by a test. An interrupted run deleted its own checkpoint and reported
/// COMPLETED after 2 of 5 chunks; a stopped run reported extractionComplete:true having extracted
/// zero scenes. Both were found by running the real thing against Docker and reading logs — a slow,
/// flaky way to find a branch bug, and one that only catches what the model happens to do that day.
///
/// PictureBookUtil.ChunkLlm is the seam. Everything else here is production code: the real chunking,
/// the real retry, the real salvage, the real checkpoint writes through AccessPoint, the real
/// resume. Only the model reply is scripted.
///
/// Real DB (checkpoint notes are genuinely written and read back), no LLM, runs as a test user.
public class TestExtractChunkLoop extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestExtractChunkLoop.class);

	/// Comfortably over chunkSize 2000 so the text splits into several chunks. Sentence-ended so
	/// the loop's break-on-period logic behaves like it does on real prose.
	private static String longText(int sentences) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < sentences; i++) {
			sb.append("Sentence number ").append(i)
			  .append(" carries enough words to make the passage realistically long for chunking. ");
		}
		return sb.toString();
	}

	private static String sceneJson(String title) {
		return "{\"additions\":[{\"title\":\"" + title + "\",\"blurb\":\"b\",\"setting\":\"s\","
				+ "\"action\":\"a\",\"mood\":\"m\",\"characters\":[]}]}";
	}

	/// A scripted model: returns the next reply each call, then repeats the last one forever.
	private static final class Script implements PictureBookUtil.ChunkLlm {
		private final List<String> replies;
		final AtomicInteger calls = new AtomicInteger(0);
		private final Runnable beforeEach;

		Script(List<String> replies) { this(replies, null); }

		Script(List<String> replies, Runnable beforeEach) {
			this.replies = replies;
			this.beforeEach = beforeEach;
		}

		@Override
		public String call(Map<String, String> vars, int attempt) {
			if (beforeEach != null) beforeEach.run();
			int i = calls.getAndIncrement();
			if (replies.isEmpty()) return null;
			return replies.get(Math.min(i, replies.size() - 1));
		}
	}

	/// An in-memory scene map, as the chunk loop builds them.
	private static Map<String, Object> scene(String title, String blurb, int sourceChunk) {
		Map<String, Object> s = new LinkedHashMap<>();
		s.put("title", title);
		s.put("blurb", blurb);
		s.put("setting", "a hall");
		s.put("action", "a");
		s.put("mood", "m");
		s.put("characters", new ArrayList<String>());
		s.put("sourceChunk", sourceChunk);
		s.put("sourceText", "PASSAGE-" + sourceChunk);
		return s;
	}

	@SafeVarargs
	private static <T> List<T> listOf(T... items) {
		List<T> l = new ArrayList<>();
		Collections.addAll(l, items);
		return l;
	}

	private static List<String> replies(String... r) {
		List<String> l = new ArrayList<>();
		Collections.addAll(l, r);
		return l;
	}

	private BaseRecord user() {
		BaseRecord u = getCreateUser("pbLoopUser");
		assertNotNull("test user", u);
		return u;
	}

	private List<Map<String, Object>> run(BaseRecord u, String text, SummarizeProgress token,
			List<String> failed, String workObjectId, boolean[] reachedEnd,
			PictureBookUtil.ChunkLlm llm) {
		return PictureBookUtil.extractChunkedInternal(u, null, text, token, failed, workObjectId,
				reachedEnd, llm);
	}

	// ── the happy path, so the rest of the assertions mean something ──────────────

	@Test
	public void TestEveryChunkProcessedReportsReachedEnd() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { false };
		List<String> failed = new ArrayList<>();
		Script s = new Script(replies(sceneJson("One"), sceneJson("Two"), sceneJson("Three"),
				sceneJson("Four"), sceneJson("Five"), sceneJson("Six"), sceneJson("Seven")));

		List<Map<String, Object>> scenes = run(u, longText(60), token, failed, null, reachedEnd, s);

		assertTrue("a run that processed every chunk must report reachedEnd", reachedEnd[0]);
		assertEquals("every chunk was attempted", token.getTotal(), token.getCurrent());
		assertTrue("scenes were extracted", scenes.size() > 0);
		assertTrue("failures must be empty on a clean run", failed.isEmpty());
	}

	// ── cancel ───────────────────────────────────────────────────────────────────

	/// Cancel mid-run stops further model calls at the next chunk boundary, keeps what was
	/// extracted, and must NOT report reachedEnd — the bug that made a stopped run look finished.
	@Test
	public void TestCancelStopsEarlyKeepsScenesAndDoesNotReportReachedEnd() throws Exception {
		BaseRecord u = user();
		final SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { true };
		List<String> failed = new ArrayList<>();

		/// Cancel as soon as the first chunk has been answered.
		Script s = new Script(replies(sceneJson("Kept")), () -> {
			if (token.getCurrent() >= 1) token.cancel();
		});

		List<Map<String, Object>> scenes = run(u, longText(60), token, failed, null, reachedEnd, s);

		assertFalse("a cancelled run must NOT report reachedEnd", reachedEnd[0]);
		assertTrue("scenes extracted before the cancel are kept", scenes.size() > 0);
		assertTrue("the loop stopped early", token.getCurrent() < token.getTotal());
	}

	// ── the unreachable-LLM circuit breaker ──────────────────────────────────────

	/// Two consecutive null replies means the model server is gone. The loop must stop rather than
	/// grinding through the whole document recording bogus failures — and must not report success.
	/// This is the exact situation that produced a `completed` job with ZERO scenes.
	@Test
	public void TestTwoConsecutiveEmptyRepliesTripTheBreaker() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { true };
		List<String> failed = new ArrayList<>();
		Script s = new Script(replies((String) null));

		List<Map<String, Object>> scenes = run(u, longText(80), token, failed, null, reachedEnd, s);

		assertFalse("an unreachable model must not report reachedEnd", reachedEnd[0]);
		assertTrue("it must stop well before the end", token.getCurrent() < token.getTotal());
		assertTrue("no scenes could be extracted", scenes.isEmpty());
		/// The client has to be told WHY, or an empty list is indistinguishable from
		/// "the model found nothing".
		assertFalse("the breaker must record a failure for the client", failed.isEmpty());
		String joined = String.join(" ", failed);
		/// The message must describe WHAT WAS OBSERVED — consecutive immediate failures — and, when
		/// the model gave no reason, say so. It must NOT assert a cause it cannot know: the same
		/// signature is produced by a mistyped model name.
		assertTrue("the failure must state that chunks failed immediately: " + joined,
				joined.contains("failed immediately"));
		assertTrue("and must admit no reason was reported: " + joined,
				joined.contains("gave no reason"));
	}

	/// THE REGRESSION. A model that TIMES OUT is alive, just slow — the run must record the
	/// failed chunks and carry on to the end of the document. Tripping the breaker on timeouts
	/// aborted a real 17-chunk extraction five chunks from the end, where the previous behaviour
	/// was to grind through and return everything it could get. Slow is not dead.
	@Test
	public void TestSlowTimeoutsDoNotTripTheBreaker() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { false };
		List<String> failed = new ArrayList<>();

		/// Chunks 1-2 answer; every later attempt "times out" — returns null only after a delay
		/// past LLM_INFRA_FAILURE_MS. A real 300s timeout is simulated by the shortest delay the
		/// production constant still classifies as slow.
		final AtomicInteger n = new AtomicInteger(0);
		PictureBookUtil.ChunkLlm slow = (vars, attempt) -> {
			if (n.getAndIncrement() < 2) return sceneJson("Early " + n.get());
			try {
				Thread.sleep(PictureBookUtil.LLM_INFRA_FAILURE_MS + 50);
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
			}
			return null;
		};

		/// Each simulated timeout costs a real LLM_INFRA_FAILURE_MS sleep, so this stays modest.
		List<Map<String, Object>> scenes = run(u, longText(60), token, failed, null, reachedEnd, slow);

		assertTrue("a SLOW model must not abort the document — it must reach the end", reachedEnd[0]);
		assertEquals("every chunk must be attempted", token.getTotal(), token.getCurrent());
		assertTrue("the scenes extracted before the slowdown are kept", scenes.size() > 0);
		assertFalse("the timed-out chunks must be reported as failures", failed.isEmpty());
		assertFalse("a timeout must NOT be reported as an unreachable server",
				String.join(" ", failed).contains("unreachable"));
	}

	/// The breaker must still fire for a genuinely DOWN server — that is what it is for, and it
	/// is what stops a Tomcat shutdown burning 40 minutes on calls to nothing. An unreachable
	/// server fails IMMEDIATELY.
	@Test
	public void TestImmediateFailuresStillTripTheBreaker() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { true };
		List<String> failed = new ArrayList<>();
		/// Returns null with no delay at all, as a connection refusal does.
		Script s = new Script(replies((String) null));

		run(u, longText(80), token, failed, null, reachedEnd, s);

		assertFalse("a DOWN server must still stop the run", reachedEnd[0]);
		assertTrue("it must stop well before the end", token.getCurrent() < token.getTotal());
		assertTrue("and record consecutive immediate failures",
				String.join(" ", failed).contains("failed immediately"));
	}

	/// A mistyped model name fails in ~12ms with HTTP 404 "model 'x' not found" — the SAME
	/// instant-failure signature as a dead server. The breaker cannot tell them apart, so it must
	/// report the model's OWN reason rather than asserting the hardware is down. A user who made
	/// a typo was told their server had failed.
	@Test
	public void TestBreakerReportsTheModelsOwnReasonRatherThanGuessing() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { true };
		List<String> failed = new ArrayList<>();

		/// Fail instantly, setting the same thread-local reason Chat sets on a 404.
		PictureBookUtil.ChunkLlm notFound = (vars, attempt) -> {
			Chat.setLastCallErrorForTest("LLM call failed (HTTP 404): model 'qweb3:8b' not found");
			return null;
		};

		run(u, longText(80), token, failed, null, reachedEnd, notFound);

		assertFalse("instant failures still stop the run", reachedEnd[0]);
		String joined = String.join(" ", failed);
		assertTrue("the client must be told the REAL reason: " + joined,
				joined.contains("model 'qweb3:8b' not found"));
		assertFalse("and must NOT be told the server is unreachable when it is a typo",
				joined.contains("unreachable"));
	}

	/// A single empty reply is a blip, not an outage: the run must recover and continue.
	@Test
	public void TestASingleEmptyReplyDoesNotTripTheBreaker() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { false };
		List<String> failed = new ArrayList<>();
		/// Chunk 1 fails both attempts (null, null) then every later chunk answers. Because the
		/// retry consumes a reply, the run sees one bad chunk and then good ones.
		Script s = new Script(replies(null, null, sceneJson("After The Blip")));

		List<Map<String, Object>> scenes = run(u, longText(60), token, failed, null, reachedEnd, s);

		assertTrue("the run must reach the end despite one bad chunk", reachedEnd[0]);
		assertTrue("later chunks still produced scenes", scenes.size() > 0);
	}

	// ── retry + salvage, through the REAL loop ───────────────────────────────────

	/// The corrective retry: a first reply that cannot be parsed at all, then a good one. The loop
	/// must recover on attempt 2 and record NO failure — an earlier version recorded a bogus one.
	@Test
	public void TestUnparseableFirstAttemptRecoversOnRetryWithoutRecordingAFailure() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { false };
		List<String> failed = new ArrayList<>();
		Script s = new Script(replies("this is not json at all", sceneJson("Recovered")));

		List<Map<String, Object>> scenes = run(u, longText(20), token, failed, null, reachedEnd, s);

		assertTrue(reachedEnd[0]);
		assertTrue("the retry must have produced a scene", scenes.size() > 0);
		assertTrue("a recovered chunk must leave no failure record", failed.isEmpty());
	}

	/// TRUNCATION SALVAGE, exercised through the loop rather than only through parseLlmJsonObject.
	/// This is the production failure mode: previousScenes grows every chunk against a fixed
	/// num_ctx, so later replies get cut off mid-object. The repaired fragment must yield real
	/// scenes and must NOT be treated as a failure.
	@Test
	public void TestTruncatedReplyIsSalvagedIntoRealScenes() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { false };
		List<String> failed = new ArrayList<>();

		/// Cut off mid-string inside the second scene's blurb — exactly what a token-limit stop
		/// produces. The first scene is complete and must survive.
		String truncated = "{\"additions\":[{\"title\":\"Survives Truncation\",\"blurb\":\"complete\","
				+ "\"setting\":\"s\",\"action\":\"a\",\"mood\":\"m\",\"characters\":[]},"
				+ "{\"title\":\"Cut Off Here\",\"blurb\":\"the reply stops mid-sen";
		Script s = new Script(replies(truncated));

		List<Map<String, Object>> scenes = run(u, longText(20), token, failed, null, reachedEnd, s);

		assertTrue(reachedEnd[0]);
		assertTrue("salvage must recover the complete scene from a truncated reply",
				scenes.size() > 0);
		boolean found = false;
		for (Map<String, Object> sc : scenes) {
			if ("Survives Truncation".equals(sc.get("title"))) found = true;
		}
		assertTrue("the scene emitted before the cut must survive", found);
		assertTrue("a salvaged chunk is not a failure", failed.isEmpty());
	}

	/// A legitimately empty object means "no new scenes in this passage" — a correct answer. It
	/// must not burn the retry, and must not be recorded as a failure.
	@Test
	public void TestEmptyObjectIsNotAFailureAndDoesNotRetry() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { false };
		List<String> failed = new ArrayList<>();
		Script s = new Script(replies("{}"));

		List<Map<String, Object>> scenes = run(u, longText(20), token, failed, null, reachedEnd, s);

		assertTrue(reachedEnd[0]);
		assertTrue("no scenes, but not an error", scenes.isEmpty());
		assertTrue("an empty object must not be recorded as a failure", failed.isEmpty());
		/// One call per chunk, not two — a retry here used to cost a full generation.
		assertEquals("no retry may be issued for a valid empty reply",
				token.getTotal(), s.calls.get());
	}

	// ── checkpoint + resume, end to end through the loop ─────────────────────────

	/// The whole point of the checkpoint: stop a run, re-drive it, and continue from where it
	/// stopped with the earlier scenes intact — no duplicates, no losses.
	@Test
	public void TestCancelledRunResumesFromItsCheckpointOnReDrive() throws Exception {
		BaseRecord u = user();
		long orgId = u.get(FieldNames.FIELD_ORGANIZATION_ID);
		/// Long enough that cancelling after 2 chunks leaves several unprocessed. With a short
		/// text the cancel lands on the FINAL chunk, the loop finishes naturally, and reachedEnd
		/// is correctly true — right behaviour, but it tests nothing about resume.
		String text = longText(140);
		String docName = "loopWork-" + UUID.randomUUID();
		BaseRecord work = getCreateData(u, docName, "text/plain", text.getBytes(),
				"~/PbLoopTests", orgId);
		assertNotNull(work);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String groupPath = PictureBookUtil.findWorkGroupPath(u, workObjectId);
		assertNotNull("work group path", groupPath);
		PictureBookUtil.clearExtractCheckpoint(u, workObjectId);

		// --- first run: cancel after a few chunks -------------------------------
		final SummarizeProgress t1 = new SummarizeProgress();
		boolean[] end1 = new boolean[] { true };
		List<String> failed1 = new ArrayList<>();
		final AtomicInteger n = new AtomicInteger(0);
		PictureBookUtil.ChunkLlm first = (vars, attempt) -> {
			int i = n.getAndIncrement();
			if (t1.getCurrent() >= 2) t1.cancel();
			return sceneJson("Scene " + i);
		};
		List<Map<String, Object>> firstScenes = run(u, text, t1, failed1, workObjectId, end1, first);

		assertFalse("cancelled run must not report reachedEnd", end1[0]);
		int extractedBefore = firstScenes.size();
		assertTrue("some scenes were extracted before the cancel", extractedBefore > 0);

		ExtractCheckpoint cp = PictureBookUtil.loadExtractCheckpoint(u, groupPath, workObjectId,
				PictureBookUtil.extractTextHash(text), 2000, 200, t1.getTotal());
		assertNotNull("a cancelled run must LEAVE a resumable checkpoint", cp);
		assertEquals("the checkpoint holds the scenes extracted so far",
				extractedBefore, cp.scenes.size());

		// --- re-drive: must resume, not restart ---------------------------------
		SummarizeProgress t2 = new SummarizeProgress();
		boolean[] end2 = new boolean[] { false };
		List<String> failed2 = new ArrayList<>();
		final AtomicInteger m = new AtomicInteger(0);
		PictureBookUtil.ChunkLlm second = (vars, attempt) ->
				sceneJson("Resumed " + m.getAndIncrement());
		List<Map<String, Object>> secondScenes = run(u, text, t2, failed2, workObjectId, end2, second);

		assertTrue("the re-drive must reach the end", end2[0]);
		assertTrue("resume must start past the chunks already done",
				t2.getCurrent() > 0 && m.get() < t2.getTotal());
		assertTrue("the resumed run keeps the earlier scenes and adds more",
				secondScenes.size() > extractedBefore);

		/// The property resume correctness actually rests on: revisions and removals are matched
		/// BY TITLE, so duplicates would silently misdirect a later chunk's revision.
		List<String> titles = new ArrayList<>();
		for (Map<String, Object> sc : secondScenes) titles.add((String) sc.get("title"));
		assertEquals("no duplicate titles after a resume",
				titles.size(), new java.util.HashSet<>(titles).size());

		/// Pre-cancel scenes must come back with their passage rehydrated from sourceChunk,
		/// because createFromScenes reduces per-character detail from it.
		for (Map<String, Object> sc : secondScenes) {
			assertNotNull("every scene carries its source passage after a resume",
					sc.get("sourceText"));
		}

		/// A completed run clears its checkpoint, so the NEXT extraction starts fresh.
		assertNull("a completed run must clear the checkpoint",
				PictureBookUtil.loadProgressNote(u, groupPath, workObjectId));
	}

	/// A run that stops on the FIRST chunk has nothing resumable, and must not leave a checkpoint
	/// that can never be consumed or cleared.
	@Test
	public void TestStoppingOnTheFirstChunkLeavesNoUnresumableCheckpoint() throws Exception {
		BaseRecord u = user();
		long orgId = u.get(FieldNames.FIELD_ORGANIZATION_ID);
		String text = longText(60);
		String docName = "loopWork0-" + UUID.randomUUID();
		BaseRecord work = getCreateData(u, docName, "text/plain", text.getBytes(),
				"~/PbLoopTests", orgId);
		String workObjectId = work.get(FieldNames.FIELD_OBJECT_ID);
		String groupPath = PictureBookUtil.findWorkGroupPath(u, workObjectId);
		PictureBookUtil.clearExtractCheckpoint(u, workObjectId);

		final SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { true };
		List<String> failed = new ArrayList<>();
		/// Cancel before the very first reply is even merged.
		PictureBookUtil.ChunkLlm llm = (vars, attempt) -> {
			token.cancel();
			return null;
		};
		run(u, text, token, failed, workObjectId, reachedEnd, llm);

		assertFalse(reachedEnd[0]);
		assertNull("a checkpoint with nothing processed must not be persisted",
				PictureBookUtil.loadProgressNote(u, groupPath, workObjectId));
	}

	// ── prompt size: the accumulated scene list is what grows ────────────────────

	/// Measured cause of a real failure: by chunk 7 of 17 the request had reached ~15.8KB against
	/// num_ctx 8192 and the model degraded 112s -> 259s -> two 305s timeouts, stopping the run a
	/// third of the way through the document. Only the most recent scenes now carry full detail.
	@Test
	public void TestOlderScenesAreCarriedForwardAsTitleOnly() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			Map<String, Object> sc = scene("Scene " + i, "blurb " + i, i);
			sc.put("diffusionPrompt", "a very long diffusion prompt that must never be sent");
			scenes.add(sc);
		}

		List<Map<String, Object>> sent = PictureBookUtil.scenesForPrompt(scenes);

		assertEquals("every scene stays addressable", 12, sent.size());
		/// The oldest are title-only...
		for (int i = 0; i < 6; i++) {
			assertEquals("Scene " + i, sent.get(i).get("title"));
			assertEquals("older scenes must carry ONLY their title", 1, sent.get(i).size());
		}
		/// ...and the most recent keep the detail the model needs for continuity.
		for (int i = 6; i < 12; i++) {
			assertEquals("Scene " + i, sent.get(i).get("title"));
			assertNotNull("recent scenes keep their blurb", sent.get(i).get("blurb"));
			assertNotNull("recent scenes keep their setting", sent.get(i).get("setting"));
		}
	}

	/// Titles are the match key for revisions and removals, so windowing must not make an older
	/// scene un-addressable — that would silently produce duplicates instead of revisions.
	@Test
	public void TestWindowedOlderScenesRemainRevisable() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		for (int i = 0; i < 12; i++) scenes.add(scene("Scene " + i, "blurb " + i, i));

		List<Map<String, Object>> sent = PictureBookUtil.scenesForPrompt(scenes);
		boolean oldestPresent = false;
		for (Map<String, Object> sc : sent) {
			if ("Scene 0".equals(sc.get("title"))) oldestPresent = true;
		}
		assertTrue("the oldest scene's TITLE must still be visible to the model", oldestPresent);

		/// And a revision keyed on that title still lands on the real (full) scene.
		Map<String, Object> rev = new LinkedHashMap<>();
		rev.put("title", "Scene 0");
		rev.put("blurb", "revised much later");
		Map<String, Object> chunk = new LinkedHashMap<>();
		chunk.put("revisions", listOf(rev));
		PictureBookUtil.mergeChunkResult(scenes, chunk, "p", 12);
		assertEquals("revised much later", scenes.get(0).get("blurb"));
		assertEquals("no duplicate was created", 12, scenes.size());
	}

	/// A short run is unaffected — nothing is windowed until there are more scenes than the window.
	@Test
	public void TestShortSceneListIsNotWindowed() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		for (int i = 0; i < 3; i++) scenes.add(scene("S" + i, "b" + i, i));
		List<Map<String, Object>> sent = PictureBookUtil.scenesForPrompt(scenes);
		assertEquals(3, sent.size());
		for (Map<String, Object> sc : sent) {
			assertNotNull("a short list keeps full detail throughout", sc.get("blurb"));
		}
	}

	/// sourceText must NEVER reach the model, windowed or not — it is the raw passage and would
	/// balloon the prompt by the size of the whole document.
	@Test
	public void TestSourceTextNeverReachesThePrompt() throws Exception {
		List<Map<String, Object>> scenes = new ArrayList<>();
		for (int i = 0; i < 10; i++) scenes.add(scene("S" + i, "b" + i, i));
		for (Map<String, Object> sc : PictureBookUtil.scenesForPrompt(scenes)) {
			assertNull("sourceText must never be sent", sc.get("sourceText"));
			assertNull("sourceChunk is internal bookkeeping", sc.get("sourceChunk"));
		}
	}

	/// Checkpointing is skipped entirely when no work id is supplied — the in-memory callers and
	/// the older tests depend on that.
	@Test
	public void TestNoWorkObjectIdMeansNoCheckpointing() throws Exception {
		BaseRecord u = user();
		SummarizeProgress token = new SummarizeProgress();
		boolean[] reachedEnd = new boolean[] { false };
		List<String> failed = new ArrayList<>();
		Script s = new Script(replies(sceneJson("NoCheckpoint")));

		List<Map<String, Object>> scenes = run(u, longText(20), token, failed, null, reachedEnd, s);
		assertTrue(reachedEnd[0]);
		assertTrue(scenes.size() > 0);
	}
}
