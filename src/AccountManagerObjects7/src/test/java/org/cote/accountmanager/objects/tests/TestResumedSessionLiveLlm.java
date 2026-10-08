package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType;
import org.junit.Test;

/// LLM lane item 6 (2026-10-07): the RESUMED-SESSION emission point and the configureChat WARN
/// branches, exercised against the live DB and the live LLM this JVM resolved (BaseTest.setup ->
/// LlmTestGate; read the `[LLM-GATE] route=... tier=...` line in the log).
///
/// Part A - Chat.configureChat WARN branches (DB only, no LLM call). Each branch is a real
/// construction of a Chat over a chatConfig shaped to hit exactly that branch, with the WARN captured
/// off the live log4j pipeline:
///   a1  chatConfig with NO connection reference      -> "has no connection reference", serverUrl null
///   a2  chatConfig whose connection FK does not exist -> "could not be loaded",          serverUrl null
///   a3  OPENAI_COMPAT connection + upstream UNKNOWN   -> the C1-h discoverability WARN
///   a3' OPENAI_COMPAT connection + upstream OLLAMA    -> control: NO such WARN, upstream resolves OLLAMA
///
/// Part B - the resumed session. ChatUtil.getOpenAIRequest's `vreq != null` branch is the one path
/// that re-applies chat options to a DESERIALIZED session without a Chat instance, and it was the
/// genuinely broken KI-72 emission point (no dialect, no upstream -> no Ollama extensions on
/// resume). This test creates a chatRequest + session the way ChatService does, resumes it, asserts
/// BOTH axes resolved (think + repeat_penalty present when the route's upstream is OLLAMA; absent
/// otherwise), sends one real user turn through Chat.chat() on the live LLM, persists the turn, and
/// resumes AGAIN to prove the stored session grew by exactly one user + one assistant message and
/// that the second resume still carries the extensions.
///
/// Non-admin user throughout (getCreateUser). Nothing here resets or drops anything.
public class TestResumedSessionLiveLlm extends BaseTest {
	public static final Logger logger = LogManager.getLogger(TestResumedSessionLiveLlm.class);

	private static final String TEST_USER = "resumedSessionUser";
	private static final String UNREACHABLE_COMPAT_URL = "http://127.0.0.1:1/v1";
	/// See b1: CPU-only local Ollama contended by other lanes' model swaps.
	private static final int LIVE_TURN_TIMEOUT_SEC = 600;

	/** Captures WARN/ERROR emitted by production code while the body runs (TestPathUtilBehavior idiom). */
	private static final class LogCapture implements AutoCloseable {
		private final List<String> messages = new CopyOnWriteArrayList<>();
		private final List<LoggerConfig> targets = new ArrayList<>();
		private final AbstractAppender appender;
		private final String tag;

		LogCapture(String tag) {
			this.tag = tag;
			LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
			Configuration cfg = ctx.getConfiguration();
			appender = new AbstractAppender(tag, null, null, true, null) {
				@Override
				public void append(LogEvent event) {
					if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
						messages.add(event.getLoggerName() + " | " + event.getMessage().getFormattedMessage());
					}
				}
			};
			appender.start();
			/// Root plus the config that actually serves the Chat logger (they are usually the same
			/// object; when a logger-specific config with additivity=false exists, root alone would
			/// never see the event).
			LoggerConfig root = cfg.getRootLogger();
			LoggerConfig chatCfg = cfg.getLoggerConfig(Chat.class.getName());
			targets.add(root);
			if (chatCfg != null && chatCfg != root) {
				targets.add(chatCfg);
			}
			for (LoggerConfig lc : targets) {
				lc.addAppender(appender, Level.WARN, null);
			}
		}

		List<String> matching(String needle) {
			List<String> out = new ArrayList<>();
			for (String m : messages) {
				if (m.toLowerCase().contains(needle.toLowerCase())) out.add(m);
			}
			return out;
		}

		@Override
		public void close() {
			for (LoggerConfig lc : targets) {
				lc.removeAppender(tag);
			}
			appender.stop();
		}
	}

	/// chatConfig IN MEMORY: Chat.configureChat reads only its fields and its connection FK id.
	private BaseRecord inMemoryChatConfig(BaseRecord user, String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory()
			.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		assertNotNull("chatConfig factory newInstance returned null", cfg);
		cfg.set("model", "qwen3:8b");
		cfg.set("stream", false);
		return cfg;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Part A — configureChat WARN branches
	// ─────────────────────────────────────────────────────────────────────────

	@Test
	public void a1_noConnectionReference_warnsAndLeavesServerUrlUnset() throws Exception {
		BaseRecord user = getCreateUser(TEST_USER);
		assertNotNull("test user is null", user);
		BaseRecord cfg = inMemoryChatConfig(user, "Resumed Warn NoConn");
		cfg.set("connection", null);

		try (LogCapture cap = new LogCapture("a1-" + UUID.randomUUID())) {
			Chat chat = new Chat(user, cfg, null);
			List<String> hits = cap.matching("has no connection reference");
			logger.info("[ITEM6][A1] captured=" + hits);
			assertEquals("configureChat must WARN exactly once for a chatConfig with no connection", 1, hits.size());
			assertTrue("the WARN must name the chatConfig", hits.get(0).contains("Resumed Warn NoConn"));
			assertNull("serverUrl must stay unset (no inline fallback)", chat.getServerUrl());
			assertNull("apiKey must stay unset", chat.getAuthorizationToken());
			assertTrue("no 'could not be loaded' WARN on this branch", cap.matching("could not be loaded").isEmpty());
		}
	}

	@Test
	public void a2_danglingConnectionId_warnsCouldNotBeLoaded() throws Exception {
		BaseRecord user = getCreateUser(TEST_USER);
		assertNotNull("test user is null", user);
		BaseRecord cfg = inMemoryChatConfig(user, "Resumed Warn Dangling");
		/// A connection FK whose row cannot exist: explicit-field newInstance so nothing else is set.
		BaseRecord bogus = RecordFactory.newInstance(ModelNames.MODEL_CONNECTION, new String[] { FieldNames.FIELD_ID });
		long bogusId = Long.MAX_VALUE / 4L;
		bogus.set(FieldNames.FIELD_ID, bogusId);
		cfg.set("connection", bogus);

		try (LogCapture cap = new LogCapture("a2-" + UUID.randomUUID())) {
			Chat chat = new Chat(user, cfg, null);
			List<String> hits = cap.matching("could not be loaded");
			logger.info("[ITEM6][A2] captured=" + hits);
			assertEquals("configureChat must WARN exactly once for an unloadable connection", 1, hits.size());
			assertTrue("the WARN must carry the FK id", hits.get(0).contains("id=" + bogusId));
			assertNull("serverUrl must stay unset", chat.getServerUrl());
			assertTrue("no 'has no connection reference' WARN on this branch", cap.matching("has no connection reference").isEmpty());
			/// With no connection the resolver falls back to the deprecated serviceType and the upstream
			/// is floored from it - never OLLAMA from an OPENAI-shaped default.
			assertNotNull(chat.getServiceType());
		}
	}

	@Test
	public void a3_openAiCompatUpstreamUnknown_warnsDiscoverability_controlIsSilent() throws Exception {
		BaseRecord user = getCreateUser(TEST_USER);
		assertNotNull("test user is null", user);

		/// Idempotent by name (reconciled on re-run) so this does not grow a row per run.
		BaseRecord unknownConn = OlioTestUtil.getCreateConnection(user, "Resumed Warn Compat Unknown", UNREACHABLE_COMPAT_URL, null,
			ConnectionDialectEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.UNKNOWN, 5);
		assertNotNull("OPENAI_COMPAT+UNKNOWN connection was not created", unknownConn);
		BaseRecord ollamaConn = OlioTestUtil.getCreateConnection(user, "Resumed Warn Compat Ollama", UNREACHABLE_COMPAT_URL, null,
			ConnectionDialectEnumType.OPENAI_COMPAT, ConnectionUpstreamEnumType.OLLAMA, 5);
		assertNotNull("OPENAI_COMPAT+OLLAMA connection was not created", ollamaConn);

		BaseRecord cfgUnknown = inMemoryChatConfig(user, "Resumed Warn Compat Unknown Cfg");
		cfgUnknown.set("connection", unknownConn);
		try (LogCapture cap = new LogCapture("a3u-" + UUID.randomUUID())) {
			Chat chat = new Chat(user, cfgUnknown, null);
			List<String> hits = cap.matching("system.connection.upstream unset");
			logger.info("[ITEM6][A3-unknown] captured=" + hits);
			assertEquals("dialect must resolve from the connection", LLMServiceEnumType.OPENAI_COMPAT, chat.getServiceType());
			assertEquals("OPENAI_COMPAT must NEVER infer an OLLAMA upstream", ConnectionUpstreamEnumType.UNKNOWN, chat.getUpstream());
			assertEquals("the C1-h discoverability WARN must fire exactly once per Chat construction", 1, hits.size());
			assertTrue("the WARN must say the extensions are suppressed", hits.get(0).contains("SUPPRESSED"));
			assertEquals("serverUrl must have been loaded off the connection", UNREACHABLE_COMPAT_URL, chat.getServerUrl());
		}

		BaseRecord cfgOllama = inMemoryChatConfig(user, "Resumed Warn Compat Ollama Cfg");
		cfgOllama.set("connection", ollamaConn);
		try (LogCapture cap = new LogCapture("a3o-" + UUID.randomUUID())) {
			Chat chat = new Chat(user, cfgOllama, null);
			List<String> hits = cap.matching("system.connection.upstream unset");
			logger.info("[ITEM6][A3-ollama] captured=" + hits);
			assertEquals(LLMServiceEnumType.OPENAI_COMPAT, chat.getServiceType());
			assertEquals("an asserted upstream must be honoured", ConnectionUpstreamEnumType.OLLAMA, chat.getUpstream());
			assertTrue("control: the discoverability WARN must NOT fire when upstream is asserted", hits.isEmpty());
			assertTrue(cap.matching("has no connection reference").isEmpty());
			assertTrue(cap.matching("could not be loaded").isEmpty());
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// Part B — resumed session, live LLM
	// ─────────────────────────────────────────────────────────────────────────

	private static String content(OpenAIResponse resp) {
		if (resp == null || resp.getMessage() == null) {
			return null;
		}
		return resp.getMessage().get(FieldNames.FIELD_CONTENT);
	}

	private static void assertAxesOnResumedRequest(String tag, OpenAIRequest req, boolean ollamaUpstream, String expectModel) {
		assertNotNull(tag + ": resumed request is null", req);
		assertEquals(tag + ": resumed request must carry the chatConfig model", expectModel, req.getModel());
		/// A session read back from the DB materialises every column, so presence alone proves nothing
		/// here: the schema default for repeat_penalty on olio.llm.openai.openaiRequest is 0.0 and only
		/// applyOllamaUpstreamOptions ever writes a positive value (chatOptions value, else 1.1).
		double repeatPenalty = req.hasField("repeat_penalty") ? req.get("repeat_penalty") : 0.0;
		if (ollamaUpstream) {
			assertTrue(tag + ": `think` must be explicitly present on a resumed Ollama-upstream request", req.hasField("think"));
			assertTrue(tag + ": repeat_penalty must be explicitly emitted (>0) on a resumed Ollama-upstream request, got " + repeatPenalty,
				repeatPenalty > 0.0);
			int numCtx = req.hasField("num_ctx") ? req.get("num_ctx") : 0;
			assertTrue(tag + ": num_ctx must be emitted on a resumed Ollama-upstream request", numCtx > 0);
		} else {
			assertEquals(tag + ": repeat_penalty must NOT be emitted on a non-Ollama upstream", 0.0, repeatPenalty, 1e-9);
		}
	}

	@Test
	public void b1_resumedSession_reappliesBothAxes_roundTripsAndPersists() throws Exception {
		BaseRecord user = getCreateUser(TEST_USER);
		assertNotNull("test user is null", user);

		String route = testProperties.getProperty(OlioTestUtil.PROP_ROUTE);
		String upstreamProp = testProperties.getProperty(OlioTestUtil.PROP_CONNECTION_UPSTREAM);
		String dialectProp = testProperties.getProperty(OlioTestUtil.PROP_CONNECTION_DIALECT);
		boolean ollamaUpstream = "OLLAMA".equalsIgnoreCase(upstreamProp);
		String expectModel = OlioTestUtil.analysisModel(testProperties);
		assertNotNull("no analysis model resolved - LlmTestGate did not run", expectModel);
		logger.info("[ITEM6][B1] route=" + route + " dialect=" + dialectProp + " upstream=" + upstreamProp + " model=" + expectModel);

		BaseRecord cfg = OlioTestUtil.getOllamaOpenAIConfig(user, "Resumed Session Cfg", testProperties);
		assertNotNull("chatConfig could not be created on the resolved route", cfg);
		/// The analysis config's connection carries requestTimeout 120. The local tier is a CPU-only
		/// Ollama with ONE runner slot that other test lanes keep swapping onto JOSIEFIED; measured
		/// 2026-10-07 a single short completion queued behind that thrash took 3m39s end to end
		/// (ollama GIN log: `POST /api/chat ... 3m39s`) and the 120s latch expired with content=null.
		/// Widen ONLY this connection's timeout - same target the config was built with, longer wait.
		/// Chat.configureChat re-reads the connection by id, so the patch is what the turn sees.
		BaseRecord conn = cfg.get("connection");
		assertNotNull("analysis chatConfig carries no connection", conn);
		BaseRecord widened = OlioTestUtil.reconcileConnection(user, conn,
			OlioTestUtil.ConnectionTarget.fromProperties(testProperties, LIVE_TURN_TIMEOUT_SEC));
		assertNotNull("connection timeout could not be widened", widened);
		int widenedTimeout = widened.get("requestTimeout");
		assertEquals("requestTimeout did not persist", LIVE_TURN_TIMEOUT_SEC, widenedTimeout);
		BaseRecord pcfg = OlioTestUtil.getPromptConfig(user, "Resumed Session Prompt");
		assertNotNull("promptConfig could not be created", pcfg);

		/// (1) Create the chatRequest + session exactly as ChatService does (new-session path).
		String reqName = "Resumed Session " + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord created = ChatUtil.getCreateChatRequest(user, reqName, cfg, pcfg);
		assertNotNull("chatRequest was not created", created);
		BaseRecord found = ChatUtil.getCreateChatRequest(user, reqName, cfg, pcfg);
		assertNotNull("chatRequest could not be re-read by name", found);
		assertNotNull("re-read chatRequest carries no session FK", found.get("session"));

		/// (2) RESUME: the vreq != null branch of ChatUtil.getOpenAIRequest.
		ChatRequest creq = new ChatRequest(found);
		OpenAIRequest req = ChatUtil.getOpenAIRequest(user, creq);
		assertAxesOnResumedRequest("first resume", req, ollamaUpstream, expectModel);
		int before = req.getMessages().size();
		assertTrue("a freshly created session must already carry its system prompt", before >= 1);
		logger.info("[ITEM6][B1] first resume: messages=" + before + " think=" + (req.hasField("think") ? req.get("think") : "<absent>")
			+ " repeat_penalty=" + (req.hasField("repeat_penalty") ? req.get("repeat_penalty") : "<absent>")
			+ " num_ctx=" + (req.hasField("num_ctx") ? req.get("num_ctx") : "<absent>"));

		/// (3) One real turn on the live LLM through the resumed request.
		Chat chat = ChatUtil.getChat(user, creq, false);
		assertNotNull("ChatUtil.getChat returned null for the resumed request", chat);
		assertNotNull("Chat has no serverUrl - the connection did not load", chat.getServerUrl());
		req.setStream(false);
		chat.newMessage(req, "Reply with the single word PONG.", Chat.userRole);
		long t0 = System.currentTimeMillis();
		OpenAIResponse resp = chat.chat(req);
		long ms = System.currentTimeMillis() - t0;
		String completion = content(resp);
		logger.info("[ITEM6][B1] live turn " + ms + "ms completion=\"" + (completion == null ? "null" : completion.trim()) + "\"");
		assertNotNull("Chat.chat returned null on the resumed session", resp);
		assertTrue("completion content was empty on the resumed session", completion != null && !completion.trim().isEmpty());

		/// Persist the turn the way continueChat does (append assistant message, save session).
		chat.handleResponse(req, resp, false);
		chat.saveSession(req);
		int afterInMemory = req.getMessages().size();
		assertEquals("in-memory session must have grown by user + assistant", before + 2, afterInMemory);

		/// (4) RESUME AGAIN from the DB: the stored session must carry the new turn and the
		///     extensions must be re-applied on this second deserialization too.
		OpenAIRequest again = ChatUtil.getOpenAIRequest(user, new ChatRequest(found));
		assertAxesOnResumedRequest("second resume", again, ollamaUpstream, expectModel);
		List<OpenAIMessage> msgs = again.getMessages();
		assertEquals("persisted session must have grown by exactly user + assistant", before + 2, msgs.size());
		OpenAIMessage last = msgs.get(msgs.size() - 1);
		OpenAIMessage prev = msgs.get(msgs.size() - 2);
		assertEquals("second-to-last persisted message must be the user turn", Chat.userRole, prev.getRole());
		assertTrue("user turn content was not persisted", prev.getContent() != null && prev.getContent().contains("PONG"));
		assertEquals("last persisted message must be the assistant turn", "assistant", last.getRole());
		assertTrue("assistant turn content was not persisted", last.getContent() != null && !last.getContent().trim().isEmpty());

		/// Independent fresh read of the session row (cache bypassed) to rule out an in-memory echo.
		BaseRecord fresh = OlioUtil.getFullRecord(found.get("session"), false);
		assertNotNull("session row could not be re-read", fresh);
		List<BaseRecord> freshMsgs = fresh.get("messages");
		assertEquals("DB session message count", before + 2, freshMsgs.size());
		logger.info("[ITEM6][B1] PASS route=" + route + " upstream=" + upstreamProp + " messages " + before + " -> " + msgs.size());
	}
}
