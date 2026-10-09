package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobLimitException;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.junit.Test;

/// Ownership and lifecycle tests for AsyncJobRegistry. No LLM, and no DB records are read or
/// written — but it extends BaseTest because constructing even a bare system.user needs the model
/// schema registry, which RecordFactory populates through IOSystem. The "users" here are
/// in-memory records carrying only an objectId, which is all the registry keys on.
///
/// The ownership assertions here are the security-relevant ones. They mirror
/// TestPictureBookSceneAuthz's coverage of PictureBookCancelRegistry, which exists because an
/// earlier version of that registry was keyed on a client-supplied id with the principal thrown
/// away, letting any authenticated user cancel anyone else's in-flight extraction.
public class TestAsyncJobRegistry extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestAsyncJobRegistry.class);

	private static final long WAIT_MS = 10000;

	private BaseRecord user(String objectId) throws Exception {
		BaseRecord u = RecordFactory.newInstance(ModelNames.MODEL_USER,
			new String[] { FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME });
		u.set(FieldNames.FIELD_OBJECT_ID, objectId);
		u.set(FieldNames.FIELD_NAME, "u-" + objectId);
		return u;
	}

	private static void awaitTerminal(AsyncJob job) throws Exception {
		long deadline = System.currentTimeMillis() + WAIT_MS;
		while (!job.isTerminal() && System.currentTimeMillis() < deadline) {
			Thread.sleep(25);
		}
		assertTrue("job did not reach a terminal status within " + WAIT_MS + "ms", job.isTerminal());
	}

	// ── the core fix: a finished result survives the caller disconnecting ──────

	/// The whole reason this registry exists. The submitting request is gone by the time the job
	/// finishes, and the result must still be collectable — that is precisely what the nginx 504
	/// destroyed when the work ran inline in the request.
	@Test
	public void TestResultIsRetainedForCollectionAfterCompletion() throws Exception {
		BaseRecord u = user("owner-retain-1");
		AsyncJob job = AsyncJobRegistry.submit(u, "test.retain", "work-1",
			j -> "{\"scenes\":[{\"title\":\"One\"}]}");
		assertNotNull(job);
		awaitTerminal(job);
		assertEquals(AsyncJob.Status.COMPLETED, job.getStatus());

		/// Re-fetch by id, as a poll from a completely different request would.
		AsyncJob fetched = AsyncJobRegistry.get(u, job.getJobId());
		assertNotNull("a finished job must still be retrievable", fetched);
		assertEquals("the finished payload must be retained verbatim",
			"{\"scenes\":[{\"title\":\"One\"}]}", fetched.getResult());
	}

	@Test
	public void TestFailedJobReportsItsError() throws Exception {
		BaseRecord u = user("owner-fail-1");
		AsyncJob job = AsyncJobRegistry.submit(u, "test.fail", "work-2", j -> {
			throw new IllegalStateException("deliberate test failure");
		});
		awaitTerminal(job);
		assertEquals(AsyncJob.Status.FAILED, job.getStatus());
		assertEquals("deliberate test failure", job.getError());
		assertNull(job.getResult());
	}

	/// An Error (not an Exception) thrown by the work must still terminate the job. The pool's
	/// FutureTask swallows anything that escapes runJob, so without this the job sits RUNNING for
	/// ever and a client polls it until its own budget runs out — seen live with a
	/// pb.retryFailedChunks job whose work had logged its final line minutes earlier.
	@Test
	public void TestErrorThrownByWorkStillTerminatesTheJob() throws Exception {
		BaseRecord u = user("owner-error-1");
		AsyncJob job = AsyncJobRegistry.submit(u, "test.error", "work-2b", j -> {
			throw new NoSuchFieldError("deliberate linkage error");
		});
		assertNotNull(job);
		awaitTerminal(job);
		assertEquals(AsyncJob.Status.FAILED, job.getStatus());
		assertEquals("deliberate linkage error", job.getError());
		assertNull(job.getResult());
		assertNotNull("a failed job must still be collectable by its owner",
			AsyncJobRegistry.get(u, job.getJobId()));
	}

	// ── ownership: a non-owner must not be able to see or touch a job ──────────

	@Test
	public void TestAnotherUserCannotReadTheJob() throws Exception {
		BaseRecord owner = user("owner-A");
		BaseRecord other = user("other-B");
		AsyncJob job = AsyncJobRegistry.submit(owner, "test.owned", "work-3", j -> "{}");
		awaitTerminal(job);

		assertNotNull("owner can read it", AsyncJobRegistry.get(owner, job.getJobId()));
		assertNull("a non-owner must get nothing back, indistinguishable from an unknown id",
			AsyncJobRegistry.get(other, job.getJobId()));
	}

	@Test
	public void TestAnotherUserCannotCancelTheJob() throws Exception {
		BaseRecord owner = user("owner-C");
		BaseRecord other = user("other-D");
		final CountDownLatch release = new CountDownLatch(1);
		AsyncJob job = AsyncJobRegistry.submit(owner, "test.cancelauthz", "work-4", j -> {
			release.await(WAIT_MS, TimeUnit.MILLISECONDS);
			return "{}";
		});
		assertNotNull(job);

		assertFalse("a non-owner cancel must fail", AsyncJobRegistry.cancel(other, job.getJobId()));
		assertFalse("and must not have cancelled the owner's token",
			job.getProgress().isCancelled());
		assertTrue("the owner can still cancel it", AsyncJobRegistry.cancel(owner, job.getJobId()));
		assertTrue(job.getProgress().isCancelled());
		release.countDown();
		awaitTerminal(job);
	}

	/// Unknown id, another user's id, and an already-cancelled job must all answer the same way,
	/// so the response cannot be used to probe what other users have running.
	@Test
	public void TestCancelFailuresAreIndistinguishable() throws Exception {
		BaseRecord u = user("owner-E");
		assertFalse("unknown id", AsyncJobRegistry.cancel(u, "no-such-job-id"));
		assertFalse("null id", AsyncJobRegistry.cancel(u, null));
		assertFalse("null user", AsyncJobRegistry.cancel(null, "anything"));

		AsyncJob job = AsyncJobRegistry.submit(u, "test.doublecancel", "work-5", j -> "{}");
		awaitTerminal(job);
		assertFalse("an already-finished job reports false, like an unknown id",
			AsyncJobRegistry.cancel(u, job.getJobId()));
	}

	@Test
	public void TestListReturnsOnlyTheCallersJobs() throws Exception {
		BaseRecord a = user("owner-list-A");
		BaseRecord b = user("owner-list-B");
		AsyncJob ja1 = AsyncJobRegistry.submit(a, "test.list", "w1", j -> "{}");
		AsyncJob ja2 = AsyncJobRegistry.submit(a, "test.list", "w2", j -> "{}");
		AsyncJob jb1 = AsyncJobRegistry.submit(b, "test.list", "w3", j -> "{}");
		awaitTerminal(ja1);
		awaitTerminal(ja2);
		awaitTerminal(jb1);

		List<AsyncJob> aJobs = AsyncJobRegistry.list(a);
		for (AsyncJob j : aJobs) {
			assertEquals("list must never leak another principal's job",
				"owner-list-A", j.getOwnerObjectId());
		}
		assertTrue("both of A's jobs must be listed", aJobs.size() >= 2);
		assertTrue(AsyncJobRegistry.list(null).isEmpty());
	}

	// ── cancellation semantics ────────────────────────────────────────────────

	/// Cancellation is cooperative: the work observes the token and returns what it completed.
	/// That partial output must be kept — discarding it is the very loss this design removes.
	@Test
	public void TestCancelledJobStillRetainsPartialWork() throws Exception {
		BaseRecord u = user("owner-partial");
		final CountDownLatch started = new CountDownLatch(1);
		AsyncJob job = AsyncJobRegistry.submit(u, "test.partial", "work-6", j -> {
			started.countDown();
			/// Emulate a chunk loop: stop at the cancel checkpoint, return work so far.
			StringBuilder sb = new StringBuilder("[");
			for (int i = 0; i < 100; i++) {
				if (j.getProgress().isCancelled()) {
					break;
				}
				if (i > 0) {
					sb.append(",");
				}
				sb.append(i);
				Thread.sleep(10);
			}
			return sb.append("]").toString();
		});
		assertTrue(started.await(WAIT_MS, TimeUnit.MILLISECONDS));
		Thread.sleep(60);
		assertTrue(AsyncJobRegistry.cancel(u, job.getJobId()));
		awaitTerminal(job);

		assertEquals(AsyncJob.Status.CANCELLED, job.getStatus());
		assertNotNull("partial work must be retained on cancel, not discarded", job.getResult());
		assertTrue("partial result should hold the elements completed before the cancel",
			job.getResult().startsWith("[0"));
	}

	/// A cancel can arrive while the job is still QUEUED behind the concurrency cap. It must not
	/// then run the work the user already asked to stop.
	@Test
	public void TestCancelBeforeStartPreventsTheWorkRunning() throws Exception {
		BaseRecord u = user("owner-prestart");
		final CountDownLatch blockers = new CountDownLatch(1);
		/// Saturate the pool so the next submission cannot start.
		AsyncJob[] hogs = new AsyncJob[AsyncJobRegistry.MAX_CONCURRENT_JOBS];
		for (int i = 0; i < hogs.length; i++) {
			hogs[i] = AsyncJobRegistry.submit(u, "test.hog", "hog-" + i, j -> {
				blockers.await(WAIT_MS, TimeUnit.MILLISECONDS);
				return "{}";
			});
		}
		final boolean[] ran = new boolean[1];
		AsyncJob queued = AsyncJobRegistry.submit(u, "test.queued", "work-7", j -> {
			ran[0] = true;
			return "{}";
		});
		assertEquals("must still be queued behind the cap", AsyncJob.Status.QUEUED, queued.getStatus());
		assertTrue(AsyncJobRegistry.cancel(u, queued.getJobId()));

		blockers.countDown();
		for (AsyncJob h : hogs) {
			awaitTerminal(h);
		}
		awaitTerminal(queued);
		assertEquals(AsyncJob.Status.CANCELLED, queued.getStatus());
		assertFalse("work cancelled before it started must never run", ran[0]);
	}

	// ── per-principal cap ─────────────────────────────────────────────────────

	/// Live jobs are never swept and MAX_RETAINED_JOBS only counts finished work, so before the cap
	/// one principal could queue an unbounded number of jobs behind the two-wide pool — each holding
	/// its closure and registry entry for as long as it took the pool to drain them. The cap refuses
	/// the submission outright (an exception, not the null that means "run synchronously"), applies
	/// per principal so one user cannot starve another, and counts only queued/running jobs so a
	/// principal whose work finished can submit again.
	@Test
	public void TestPrincipalCannotExceedTheActiveJobCap() throws Exception {
		BaseRecord hog = user("owner-cap-hog");
		BaseRecord other = user("owner-cap-other");
		final CountDownLatch release = new CountDownLatch(1);
		AsyncJob[] held = new AsyncJob[AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL];
		for (int i = 0; i < held.length; i++) {
			held[i] = AsyncJobRegistry.submit(hog, "test.cap", "cap-" + i, j -> {
				release.await(WAIT_MS, TimeUnit.MILLISECONDS);
				return "{}";
			});
			assertNotNull("submission " + i + " is within the cap and must be accepted", held[i]);
		}
		assertEquals(AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL, AsyncJobRegistry.activeCount(hog));

		final boolean[] ran = new boolean[1];
		try {
			AsyncJobRegistry.submit(hog, "test.cap", "cap-over", j -> {
				ran[0] = true;
				return "{}";
			});
			release.countDown();
			throw new AssertionError("the submission over the cap must be refused");
		} catch (AsyncJobLimitException e) {
			assertEquals(AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL, e.getActive());
			assertEquals(AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL, e.getLimit());
		}
		assertEquals("a refused submission must not have been registered",
			AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL, AsyncJobRegistry.activeCount(hog));

		/// The cap is per principal: the hog's backlog must not block anyone else.
		AsyncJob othersJob = AsyncJobRegistry.submit(other, "test.cap", "other-1", j -> "{}");
		assertNotNull("another principal must still be able to submit", othersJob);

		release.countDown();
		for (AsyncJob h : held) {
			awaitTerminal(h);
		}
		awaitTerminal(othersJob);
		assertFalse("the refused work must never have run", ran[0]);
		assertEquals("finished jobs do not count toward the cap", 0, AsyncJobRegistry.activeCount(hog));

		AsyncJob again = AsyncJobRegistry.submit(hog, "test.cap", "cap-after", j -> "{}");
		assertNotNull("once its jobs have finished the principal can submit again", again);
		awaitTerminal(again);
		assertEquals(AsyncJob.Status.COMPLETED, again.getStatus());
	}

	// ── keying ────────────────────────────────────────────────────────────────

	/// A jobId is server-generated per submission, so one user running two phases at once gets two
	/// independent jobs. The older key-on-workObjectId registry silently clobbered one with the
	/// other.
	@Test
	public void TestConcurrentJobsOnTheSameKeyDoNotCollide() throws Exception {
		BaseRecord u = user("owner-collide");
		AsyncJob j1 = AsyncJobRegistry.submit(u, "test.phaseOne", "same-key", j -> "{\"a\":1}");
		AsyncJob j2 = AsyncJobRegistry.submit(u, "test.phaseTwo", "same-key", j -> "{\"b\":2}");
		assertNotNull(j1);
		assertNotNull(j2);
		assertFalse("two jobs on one key must have distinct ids", j1.getJobId().equals(j2.getJobId()));
		awaitTerminal(j1);
		awaitTerminal(j2);
		assertEquals("{\"a\":1}", AsyncJobRegistry.get(u, j1.getJobId()).getResult());
		assertEquals("{\"b\":2}", AsyncJobRegistry.get(u, j2.getJobId()).getResult());
	}

	/// A principal with no objectId must not share a key space with a real one; submit reports
	/// failure so the caller can fall back to running synchronously rather than dropping the work.
	@Test
	public void TestSubmitWithoutUsablePrincipalReturnsNull() throws Exception {
		assertNull(AsyncJobRegistry.submit(null, "test.nouser", "k", j -> "{}"));
		BaseRecord noOid = RecordFactory.newInstance(ModelNames.MODEL_USER,
			new String[] { FieldNames.FIELD_NAME });
		noOid.set(FieldNames.FIELD_NAME, "no-objectid");
		assertNull(AsyncJobRegistry.submit(noOid, "test.nooid", "k", j -> "{}"));
	}
}
