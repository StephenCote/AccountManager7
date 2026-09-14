package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;

/// ChapBookUtil.callLandscapePrompt must NOT retry a HARD failure.
///
/// The retry in that loop exists for the SOFT case — a qwen3 think-only reply that strips to
/// nothing, where a fresh generation usually produces real content. A HARD failure is config
/// (missing template, unsubstituted placeholders, no chat config) or infra (timeout, unreachable
/// host, null response). None of those is fixed by re-issuing the identical request, and for the
/// timeout case the retry is actively harmful: the first exchange may still be occupying the model
/// server's generation slot, so attempt 2 queues a second one behind it and doubles the load on an
/// already-saturated server (measured 2026-09-14).
///
/// HOW THE ATTEMPTS ARE COUNTED. callLandscapePrompt has no counter and its LLM bridge is a static
/// package-private method with no seam, so this drives the REAL private method reflectively and
/// counts the PRE-EXISTING production log line that PictureBookUtil.callLlmInternal emits on the
/// hard branch — one per attempt. That is a production statement, not instrumentation added for
/// this test.
///
/// WHY AN EMPTY VARS MAP. It makes the hard failure happen BEFORE the network call:
/// callLlmInternal's UNSUBSTITUTED_PLACEHOLDER guard refuses a template that still contains
/// "{stanzaText}" etc. So the test is deterministic, needs no LLM, and cannot accidentally send a
/// real request. If the template did not resolve at all the other pre-network hard branch
/// ("Prompt template not found") fires instead — also one line per attempt, so both are counted.
/// A count of ZERO fails the test rather than passing silently, which is what would happen if the
/// call had escaped to the network.
///
/// Real DB (prompt-template resolution genuinely runs through ChatUtil.resolveConfig), no LLM,
/// runs as a test user.
public class TestChapBookLandscapeNoRetryOnHardFailure extends BaseTest {

	/// The two PRE-EXISTING pre-network hard-failure statements in
	/// PictureBookUtil.callLlmInternal. Exactly one of them is emitted per attempt.
	private static boolean isHardBranchLine(String msg) {
		if (msg == null) return false;
		return (msg.startsWith("Refusing to call LLM for prompt 'chapBook.landscape-prompt'")
				|| msg.startsWith("Prompt template not found: chapBook.landscape-prompt"));
	}

	private static final class CountingAppender extends AbstractAppender {
		final List<String> messages = Collections.synchronizedList(new ArrayList<String>());

		CountingAppender(String name) {
			super(name, null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
		}

		long hardBranchCount() {
			synchronized (messages) {
				return messages.stream().filter(TestChapBookLandscapeNoRetryOnHardFailure::isHardBranchLine).count();
			}
		}
	}

	/// Invokes the REAL private ChapBookUtil.callLandscapePrompt(user, chatConfig, vars,
	/// hardFailureOut) — the retry loop under test — rather than a reconstruction of it.
	private static String callLandscapePrompt(BaseRecord user, Map<String, String> vars, boolean[] hardOut)
			throws Exception {
		Method m = ChapBookUtil.class.getDeclaredMethod("callLandscapePrompt", BaseRecord.class,
				BaseRecord.class, Map.class, boolean[].class);
		m.setAccessible(true);
		/// chatConfig is non-null so the loop is not short-circuited before the prompt step; the
		/// placeholder guard fires first regardless, so its contents never reach a model.
		BaseRecord chatConfig = null;
		return (String) m.invoke(null, user, chatConfig, vars, hardOut);
	}

	private static <T> T runWithAppenderOn(Class<?> loggerOwner, CountingAppender app, ThrowingSupplier<T> body)
			throws Exception {
		org.apache.logging.log4j.core.Logger target =
			(org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerOwner);
		app.start();
		target.addAppender(app);
		try {
			return body.get();
		} finally {
			target.removeAppender(app);
			app.stop();
		}
	}

	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	/// CONTROL — the counter works and counts ONE line per ONE attempt. Without this, "the loop
	/// logged one line" would not distinguish "one attempt" from "the counter is broken".
	@Test
	public void TestOneDirectCallProducesExactlyOneHardBranchLine() throws Exception {
		BaseRecord u = getCreateUser("cbLandscapeUser");
		assertNotNull("test user", u);
		boolean[] hard = new boolean[1];
		CountingAppender app = new CountingAppender("cbCountControl");

		String result = runWithAppenderOn(PictureBookUtil.class, app,
			() -> PictureBookUtil.callLlmForChapBook(u, null, "chapBook.landscape-prompt",
					new LinkedHashMap<String, String>(), hard));

		logger.info("[CB-NO-RETRY][control] lines=" + app.messages
			+ " hardBranchCount=" + app.hardBranchCount());
		assertNull("a hard failure must return null", result);
		assertTrue("a hard failure must set hardFailureOut", hard[0]);
		assertEquals("one LLM attempt must emit exactly one hard-branch line", 1L, app.hardBranchCount());
	}

	/// THE FIX — the two-attempt loop must stop after the FIRST hard failure.
	/// Before the change this logged the hard-branch line twice (one per attempt).
	@Test
	public void TestHardFailureIsNotRetried() throws Exception {
		BaseRecord u = getCreateUser("cbLandscapeUser");
		assertNotNull("test user", u);
		boolean[] hard = new boolean[1];
		CountingAppender app = new CountingAppender("cbCountLoop");

		String result = runWithAppenderOn(PictureBookUtil.class, app,
			() -> callLandscapePrompt(u, new LinkedHashMap<String, String>(), hard));

		logger.info("[CB-NO-RETRY][loop] lines=" + app.messages
			+ " hardBranchCount=" + app.hardBranchCount());
		assertTrue("the hard branch must have been reached at all — a count of 0 means the call"
			+ " escaped to the network instead of failing pre-network, and this test would be"
			+ " proving nothing", app.hardBranchCount() > 0);
		assertEquals("a HARD failure must NOT be retried: the loop made more than one attempt",
			1L, app.hardBranchCount());

		/// The method's existing contract is unchanged by the early break.
		assertNull("callLandscapePrompt must still return null on a hard failure", result);
		assertTrue("callLandscapePrompt must still report the hard failure to its caller", hard[0]);
	}
}
