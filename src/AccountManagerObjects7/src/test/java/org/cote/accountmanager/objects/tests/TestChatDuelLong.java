package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatRequest;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.IChatHandler;
import org.cote.accountmanager.olio.llm.IChatListener;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIMessage;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.MemoryUtil;
import org.junit.Before;
import org.junit.Test;

/// Long-conversation chat duel diagnostic test.
///
/// Drives two characters in a back-and-forth using two chatConfigs with
/// swapped systemCharacter/userCharacter, mirroring the Console7 -duel
/// behavior but in a unit-test harness. Goals:
///
///   1. Reproduce the "LLM returns zero tokens after N turns" runaway
///      reported in long sessions. Per-turn metrics are emitted so the
///      exact failure point is visible in the log.
///   2. Exercise the memory extraction pipeline with a realistic
///      conversation length (20 turns per character = 40 exchanges per
///      conversation), then verify memories were created.
///   3. Exercise gossip / cross-character memory retrieval.
///
/// Model wiring (per user direction):
///   - Chat model:    way-local      (localhost ollama)
///   - Analyze model: qwen3:8b       (used for memory extraction etc.)
///
/// Per-turn diagnostic columns (look for [DUEL] in the log):
///   turn, conv, speaker, msgCount, wireBytes, elapsedMs, respChars, status
public class TestChatDuelLong extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;
	private OlioContext octx;

	/// Turns per character per conversation. 20 means 40 LLM round trips
	/// per conversation (20 system-side, 20 user-side), 80 total across
	/// the two conversations. Overridable via -Dduel.turns=N.
	private static final int TURNS_PER_CHARACTER =
			Integer.parseInt(System.getProperty("duel.turns", "20"));

	/// One pair = two chat configs with swapped system/user characters.
	private static final int NUM_PAIRS = 1;

	/// Per-turn LLM call timeout. Long because we are explicitly hunting
	/// for the "zero token" runaway — we want to OBSERVE the hang, not
	/// hide it with a tight cap.
	private static final int STREAM_TIMEOUT_SECONDS = 600;

	/// Chat model. Default qwen3:8b for fast iteration. Override with
	/// -Dduel.chatModel=way-local:latest to drive the larger/slower model.
	/// way-local is the production model; qwen3:8b is 3x+ faster end-to-end.
	private static final String CHAT_MODEL =
			System.getProperty("duel.chatModel", "qwen3:8b");

	/// Analyze model for memory extraction / keyframe / etc.
	private static final String ANALYZE_MODEL =
			System.getProperty("duel.analyzeModel", "qwen3:8b");

	/// Hardcoded to localhost — test.llm.ollama.server in resource.properties
	/// points at a reverse-proxy port that doesn't have these models loaded.
	private static final String OLLAMA_URL = "http://localhost:11434";

	/// ── Chat-options tuning knobs (overridable via -D) ────────────────
	/// Keep the prune→memory→retrieve loop ACTIVE — these are NOT skip
	/// switches. They just keep per-turn cost manageable so the system
	/// can exercise the full loop in test wall time.
	///
	/// max_tokens: cap on response length. Smaller = faster turn. Default
	///   512 keeps responses snappy without truncating mid-thought.
	private static final int OPT_MAX_TOKENS =
			Integer.parseInt(System.getProperty("duel.maxTokens", "512"));

	/// num_ctx: Ollama context window. Production uses 131072 for
	/// way-local. For test with messageTrim=20 and short responses, 16384
	/// is plenty and dramatically cuts prompt-eval time on each turn.
	private static final int OPT_NUM_CTX =
			Integer.parseInt(System.getProperty("duel.numCtx", "16384"));

	/// messageTrim: how many recent messages are kept in the wire request.
	/// prune=true marks older as pruned, they're dropped from the wire
	/// payload but kept in memory for extraction. 20 (default) means about
	/// 10 turns of recent context survive, the rest go through the
	/// memory/keyframe pipeline. Lower = stresses the prune-and-retrieve
	/// loop harder. Higher = fewer prunes.
	private static final int OPT_MESSAGE_TRIM =
			Integer.parseInt(System.getProperty("duel.messageTrim", "20"));

	/// keyframeEvery: trigger a keyframe (which feeds memory extraction)
	/// every Nth message. Should be ≤ messageTrim so keyframes fire before
	/// content is pruned and lost from the wire context.
	private static final int OPT_KEYFRAME_EVERY =
			Integer.parseInt(System.getProperty("duel.keyframeEvery", "5"));

	/// memoryExtractionEvery: extract memories every Nth keyframe.
	/// 1 = extract on every keyframe (maximum exercise of the pipeline).
	private static final int OPT_MEMORY_EXTRACTION_EVERY =
			Integer.parseInt(System.getProperty("duel.memoryExtractionEvery", "1"));

	/// memoryBudget: tokens of retrieved memories injected per turn. 0
	/// disables retrieval entirely. 2000 gives the LLM real context from
	/// pruned/historical content so the system can recall facts that
	/// would otherwise be lost when messageTrim drops them off the wire.
	private static final int OPT_MEMORY_BUDGET =
			Integer.parseInt(System.getProperty("duel.memoryBudget", "2000"));

	/// Upper bound on the drain wait. The drain polls until memory counts
	/// stabilize, so it usually returns earlier. With qwen3:8b each
	/// extraction takes 5-10s and the test produces ~12 per conv, so
	/// 300s is comfortable headroom without being painful when extraction
	/// is fast.
	private static final int ASYNC_DRAIN_SECONDS =
			Integer.parseInt(System.getProperty("duel.asyncDrainSeconds", "300"));

	@Before
	public void setupDuel() {
		testOrgContext = getTestOrganization("/Development/ChatDuelLong");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "chatDuelLongUser",
				testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);

		String dataPath = testProperties.getProperty("test.datagen.path");
		/// OlioTestUtil.getContext is the well-trodden path that working tests
		/// (TestGameUtil, TestKontext) use to get a context with a populated
		/// realm. OlioContextUtil.getGridContext alone creates the map
		/// structure but on a fresh DB the realm population is empty —
		/// getRealmPopulation comes back with 0 entries.
		octx = OlioTestUtil.getContext(testOrgContext, dataPath);
		assertNotNull("Olio context should not be null", octx);
	}

	/// 1 hour budget — 80 LLM round trips at way-local speeds could take a while.
	@Test(timeout = 3600000)
	public void testLongChatDuel() {
		logger.warn("[DUEL] LIVE LLM test: requires Ollama at localhost with " + CHAT_MODEL + " and " + ANALYZE_MODEL);

		try {
			List<BaseRecord> realms = octx.getRealms();
			assertTrue("Expected at least one realm", realms.size() > 0);
			BaseRecord realm = realms.get(0);
			List<BaseRecord> pop = octx.getRealmPopulation(realm);
			assertTrue("Population should have at least 2 people", pop.size() >= 2);

			List<BaseRecord> shuffled = new ArrayList<>(pop);
			Collections.shuffle(shuffled);
			List<BaseRecord> picked = shuffled.subList(0, 2);

			BaseRecord sysChar = picked.get(0);
			BaseRecord usrChar = picked.get(1);
			String sysName = sysChar.get(FieldNames.FIELD_FIRST_NAME);
			String usrName = usrChar.get(FieldNames.FIELD_FIRST_NAME);
			String sysOid = sysChar.get(FieldNames.FIELD_OBJECT_ID);
			String usrOid = usrChar.get(FieldNames.FIELD_OBJECT_ID);

			logger.info("[DUEL] Picked characters:");
			logger.info("[DUEL]   sys: " + sysName + " (oid=" + sysOid + ")");
			logger.info("[DUEL]   usr: " + usrName + " (oid=" + usrOid + ")");

			BaseRecord promptConfig = loadPromptConfig(testUser);
			assertNotNull("Prompt config should not be null", promptConfig);

			String setting = NarrativeUtil.getRandomSetting();
			logger.info("[DUEL] Setting: " + setting);

			Map<String, List<String>> systemConvs = new HashMap<>();
			Map<String, List<String>> userConvs = new HashMap<>();
			systemConvs.put(sysOid, new ArrayList<>());
			systemConvs.put(usrOid, new ArrayList<>());
			userConvs.put(sysOid, new ArrayList<>());
			userConvs.put(usrOid, new ArrayList<>());

			for (int pairIdx = 0; pairIdx < NUM_PAIRS; pairIdx++) {
				BaseRecord cfgA = createDuelChatConfig(
						"DuelLong " + pairIdx + " A " + UUID.randomUUID().toString().substring(0, 6),
						sysChar, usrChar, setting, "system");
				assertNotNull("ChatConfig A should not be null", cfgA);

				BaseRecord cfgB = createDuelChatConfig(
						"DuelLong " + pairIdx + " B " + UUID.randomUUID().toString().substring(0, 6),
						usrChar, sysChar, setting, "user");
				assertNotNull("ChatConfig B should not be null", cfgB);

				/// Server-side memory extraction tags each memory with the
				/// chatConfig.objectId as `conversationId`
				/// (Chat.java:2652). So `getConversationMemories` must be
				/// queried by THESE ids, not test-side fabricated strings.
				String convIdA = cfgA.get(FieldNames.FIELD_OBJECT_ID);
				String convIdB = cfgB.get(FieldNames.FIELD_OBJECT_ID);

				systemConvs.get(sysOid).add(convIdA);
				userConvs.get(usrOid).add(convIdA);
				systemConvs.get(usrOid).add(convIdB);
				userConvs.get(sysOid).add(convIdB);

				Chat chatA = new Chat(testUser, cfgA, promptConfig);
				Chat chatB = new Chat(testUser, cfgB, promptConfig);
				/// PersistSession=false: the test owns the in-memory req. We
				/// don't want background save threads racing with our metrics.
				chatA.setPersistSession(false);
				chatB.setPersistSession(false);

				DuelStreamListener listenerA = new DuelStreamListener("A");
				DuelStreamListener listenerB = new DuelStreamListener("B");
				chatA.setListener(listenerA);
				chatB.setListener(listenerB);

				OpenAIRequest reqA = chatA.getChatPrompt();
				OpenAIRequest reqB = chatB.getChatPrompt();
				assertNotNull("Request A should not be null", reqA);
				assertNotNull("Request B should not be null", reqB);
				reqA.setStream(true);
				reqB.setStream(true);

				String messageForA = "";
				String messageForB = null;

				logger.info("[DUEL] ========================================");
				logger.info("[DUEL] Pair " + (pairIdx + 1) + " starting: "
						+ sysName + " (A=system) vs " + usrName + " (A=user)");
				logger.info("[DUEL] Running " + TURNS_PER_CHARACTER
						+ " turns per character (" + (TURNS_PER_CHARACTER * 2) + " LLM calls per conv)");
				logger.info("[DUEL] CSV header: turn,conv,speaker,msgCount,wireBytes,elapsedMs,respChars,status");
				logger.info("[DUEL] ========================================");

				int turnsCompletedA = 0;
				int turnsCompletedB = 0;
				int timeoutsA = 0;
				int timeoutsB = 0;
				int zeroResponsesA = 0;
				int zeroResponsesB = 0;

				for (int turn = 0; turn < TURNS_PER_CHARACTER; turn++) {
					/// ── A's turn (sysName speaking) ────────────────────
					TurnResult ra = runOneTurn(chatA, reqA, listenerA, messageForA,
							turn + 1, "A", sysName);
					if (ra.completed) {
						turnsCompletedA++;
						messageForB = ra.responseText;
					} else {
						timeoutsA++;
						/// On timeout or error, give B something to react
						/// to so the conversation can recover — otherwise B
						/// would also stall waiting on an empty input. Use
						/// the prior B-input as a fallback.
						messageForB = (messageForB != null ? messageForB : "[continue]");
					}
					if (ra.responseText == null || ra.responseText.isEmpty()) {
						zeroResponsesA++;
					}

					/// ── B's turn (usrName speaking) ────────────────────
					TurnResult rb = runOneTurn(chatB, reqB, listenerB, messageForB,
							turn + 1, "B", usrName);
					if (rb.completed) {
						turnsCompletedB++;
						messageForA = rb.responseText;
					} else {
						timeoutsB++;
						messageForA = (messageForA != null ? messageForA : "[continue]");
					}
					if (rb.responseText == null || rb.responseText.isEmpty()) {
						zeroResponsesB++;
					}
				}

				logger.info("[DUEL] ========================================");
				logger.info("[DUEL] Pair " + (pairIdx + 1) + " complete");
				logger.info("[DUEL]   A: " + turnsCompletedA + "/" + TURNS_PER_CHARACTER
						+ " completed, " + timeoutsA + " timeouts, " + zeroResponsesA + " zero responses");
				logger.info("[DUEL]   B: " + turnsCompletedB + "/" + TURNS_PER_CHARACTER
						+ " completed, " + timeoutsB + " timeouts, " + zeroResponsesB + " zero responses");
				logger.info("[DUEL]   Final msgCounts: A=" + reqA.getMessages().size()
						+ " B=" + reqB.getMessages().size());

				/// Dump the full conversation objects for content inspection.
				/// reqA.toFullString() is the entire OpenAIRequest including
				/// every message (system, user, assistant) and every chat
				/// option that was set on the wire — what you'd actually
				/// send to Ollama. Useful for verifying template substitution,
				/// MCP block injection (reminders/keyframes/memories), prune
				/// markers, and response content.
				logger.info("[DUEL] === Full conversation reqA (" + convIdA + ") ===");
				logger.info(reqA.toFullString());
				logger.info("[DUEL] === Full conversation reqB (" + convIdB + ") ===");
				logger.info(reqB.toFullString());

				/// Also dump each message's role + content individually so
				/// `grep "[MSG]"` gives a clean per-message readout without
				/// the wire fluff.
				logger.info("[DUEL] === Per-message readout reqA (" + convIdA + ") ===");
				dumpMessages(reqA, sysName, usrName);
				logger.info("[DUEL] === Per-message readout reqB (" + convIdB + ") ===");
				dumpMessages(reqB, usrName, sysName);

				/// Async keyframe/interaction/memory-extraction pipelines were
				/// spawned via flushPendingKeyframe in runOneTurn. Drain by
				/// polling memory counts every 5s until they stabilize for
				/// 3 consecutive checks (15s of no growth), or until
				/// ASYNC_DRAIN_SECONDS elapses. This catches slow extraction
				/// (qwen3:8b takes ~5-10s per extraction × ~12 per conv =
				/// often >60s of tail work). No test-side memory creation —
				/// if numbers are low, the chatConfig is the problem.
				drainAsyncMemories(convIdA, convIdB);
				logger.info("[DUEL] ========================================");
			}

			/// ── Memory + gossip verification ──────────────────────
			logger.info("[DUEL] === Memory verification for " + sysName + " ===");
			verifyMemories(systemConvs.get(sysOid), "sysOf " + sysName);
			verifyMemories(userConvs.get(sysOid), "usrOf " + sysName);

			logger.info("[DUEL] === Memory verification for " + usrName + " ===");
			verifyMemories(systemConvs.get(usrOid), "sysOf " + usrName);
			verifyMemories(userConvs.get(usrOid), "usrOf " + usrName);

			/// Cross-character search exercises the gossip path.
			long sysPersonId = sysChar.get(FieldNames.FIELD_ID);
			long usrPersonId = usrChar.get(FieldNames.FIELD_ID);
			runGossipProbe(sysPersonId, usrPersonId, sysName, usrName);
			runGossipProbe(usrPersonId, sysPersonId, usrName, sysName);

			/// Raw memory dump — every memory in the test user's memory
			/// group, regardless of conversationId. Useful when verifyMemories
			/// returns lower than expected counts: confirms whether the
			/// memories were saved at all and what their conversationIds /
			/// memoryTypes actually are.
			dumpAllMemories();

			logger.info("[DUEL] === TestChatDuelLong PASSED ===");
		} catch (Exception e) {
			logger.error("[DUEL] Test failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Result of a single LLM call.
	private static class TurnResult {
		boolean completed;
		String responseText;
		long elapsedMs;
		long wireBytes;
		int msgCount;
	}

	/// Run one turn against the chat, emit a [DUEL] CSV row, return result.
	private TurnResult runOneTurn(Chat chat, OpenAIRequest req,
			DuelStreamListener listener, String inboundMessage,
			int turn, String conv, String speaker) throws InterruptedException {

		TurnResult tr = new TurnResult();
		listener.reset();

		long beforeBytes = wireSize(req);
		int beforeMsgCount = req.getMessages().size();

		long t0 = System.currentTimeMillis();
		String status;
		try {
			chat.continueChat(req, inboundMessage);
			boolean completed = listener.awaitCompletion(STREAM_TIMEOUT_SECONDS);
			tr.elapsedMs = System.currentTimeMillis() - t0;

			if (!completed) {
				status = "TIMEOUT";
				tr.completed = false;
				tr.responseText = "";
			} else if (listener.hasError()) {
				status = "ERROR(" + listener.getErrorMsg() + ")";
				tr.completed = false;
				tr.responseText = "";
			} else {
				OpenAIResponse resp = listener.getResponse();
				if (resp == null) {
					status = "NULL_RESPONSE";
					tr.completed = false;
					tr.responseText = "";
				} else {
					try {
						chat.handleResponse(req, resp, false);
					} catch (Exception he) {
						logger.warn("[DUEL] handleResponse failed: " + he.getMessage());
					}

					/// IMPORTANT: production ChatListener.oncomplete is what
					/// triggers flushPendingKeyframe and flushPendingInteraction
					/// after each turn. Our DuelStreamListener is a minimal
					/// CountDownLatch wrapper and doesn't flush. Without these
					/// calls, the keyframe pipeline (and the memory-extraction
					/// pipeline that consumes keyframes) never runs, so the
					/// prune+memory-recall loop only exercises prune. The
					/// flushes spawn CompletableFutures that run extraction
					/// async — sleep briefly to give them a chance to land
					/// before the next turn fires retrieveRelevantMemories.
					try { chat.flushPendingKeyframe(req); }
					catch (Exception fe) { logger.warn("[DUEL] flushPendingKeyframe failed: " + fe.getMessage()); }
					try { chat.flushPendingInteraction(req); }
					catch (Exception fe) { logger.warn("[DUEL] flushPendingInteraction failed: " + fe.getMessage()); }

					List<OpenAIMessage> msgs = req.getMessages();
					String last = msgs.isEmpty() ? null
							: msgs.get(msgs.size() - 1).getContent();
					if (last == null || last.isEmpty()) {
						status = "ZERO_TOKENS";
						tr.completed = false;
						tr.responseText = "";
					} else {
						status = "OK";
						tr.completed = true;
						tr.responseText = last;
					}
				}
			}
		} catch (RuntimeException re) {
			tr.elapsedMs = System.currentTimeMillis() - t0;
			status = "EXCEPTION(" + re.getClass().getSimpleName() + ":" + re.getMessage() + ")";
			tr.completed = false;
			tr.responseText = "";
		}

		tr.wireBytes = beforeBytes;
		tr.msgCount = beforeMsgCount;

		String preview = tr.responseText == null ? ""
				: truncate(tr.responseText.replace('\n', ' '), 80);
		logger.info(String.format("[DUEL] %3d,%s,%-12s,%4d,%8d,%7d,%6d,%-14s | %s",
				turn, conv, speaker, beforeMsgCount, beforeBytes,
				tr.elapsedMs,
				tr.responseText == null ? 0 : tr.responseText.length(),
				status, preview));
		return tr;
	}

	/// Serialize the request to JSON and return the byte length. This is
	/// what would actually go on the wire to Ollama.
	private long wireSize(OpenAIRequest req) {
		try {
			List<String> ignore = new ArrayList<>(ChatUtil.IGNORE_FIELDS);
			OpenAIRequest pruned = ChatUtil.getPrunedRequest(req, ignore);
			String ser = JSONUtil.exportObject(pruned,
					RecordSerializerConfig.getHiddenForeignUnfilteredModule());
			return ser == null ? 0 : ser.length();
		} catch (Exception e) {
			return -1;
		}
	}

	private void verifyMemories(List<String> convIds, String label) {
		int total = 0;
		for (String cid : convIds) {
			/// MemoryUtil.getConversationMemories now bypasses CacheDBSearch
			/// (cache disabled in the query itself), so this returns the
			/// authoritative live count.
			int n = MemoryUtil.getConversationMemories(testUser, cid).size();
			total += n;
			logger.info("[DUEL]   " + label + " conv " + cid + ": " + n + " memories");
		}
		logger.info("[DUEL]   " + label + " TOTAL: " + total + " memories across "
				+ convIds.size() + " conv(s)");
	}

	/// Direct query bypassing MemoryUtil.getConversationMemories, with an
	/// explicit setRequestRange. Used as a control to see whether the
	/// filtered query is being truncated when no range is set.
	private int countMemoriesByConvDirect(String conversationId) {
		try {
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(
					testUser, ModelNames.MODEL_GROUP, "~/Memories",
					org.cote.accountmanager.schema.type.GroupEnumType.DATA.toString(),
					testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			if (dir == null) return -1;
			Query q = QueryUtil.createQuery(org.cote.accountmanager.schema.ModelNames.MODEL_MEMORY,
					FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.field("conversationId", conversationId);
			q.planMost(true);
			q.setRequestRange(0L, 500);  // explicit pagination range
			BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
			return recs == null ? 0 : recs.length;
		} catch (Exception e) {
			logger.warn("[DUEL] countMemoriesByConvDirect failed: " + e.getMessage());
			return -2;
		}
	}

	/// Poll memory counts for the given conversation IDs every 5s, stopping
	/// when no growth is observed for 3 consecutive checks (15s stable) or
	/// when ASYNC_DRAIN_SECONDS elapses. Returns the final per-conv counts.
	private void drainAsyncMemories(String convIdA, String convIdB) {
		final long pollIntervalMs = 5000L;
		final int stableChecksRequired = 3;
		long deadline = System.currentTimeMillis() + (ASYNC_DRAIN_SECONDS * 1000L);
		int lastA = -1, lastB = -1;
		int stableA = 0, stableB = 0;
		int checkNum = 0;
		while (System.currentTimeMillis() < deadline) {
			/// MemoryUtil.getConversationMemories now sets cache=false so
			/// these reads are always fresh. Direct query kept alongside as
			/// a control to detect future cache regressions.
			int a = MemoryUtil.getConversationMemories(testUser, convIdA).size();
			int b = MemoryUtil.getConversationMemories(testUser, convIdB).size();
			int aDirect = countMemoriesByConvDirect(convIdA);
			int bDirect = countMemoriesByConvDirect(convIdB);
			checkNum++;
			logger.info(String.format("[DUEL]   drain check #%d: A=%d(direct=%d) B=%d(direct=%d) (stable A=%d/%d, B=%d/%d)",
					checkNum, a, aDirect, b, bDirect, stableA, stableChecksRequired, stableB, stableChecksRequired));
			if (a == lastA) stableA++; else { stableA = 0; lastA = a; }
			if (b == lastB) stableB++; else { stableB = 0; lastB = b; }
			if (stableA >= stableChecksRequired && stableB >= stableChecksRequired && (a > 0 || b > 0)) {
				logger.info("[DUEL]   drain stable — A=" + a + " B=" + b);
				return;
			}
			try { Thread.sleep(pollIntervalMs); }
			catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
		}
		logger.warn("[DUEL]   drain timed out after " + ASYNC_DRAIN_SECONDS + "s — A=" + lastA + " B=" + lastB);
	}

	/// List every memory in the test user's memory group. Useful when
	/// getConversationMemories(cfgObjId) returns a smaller count than the
	/// audit log suggests — confirms whether memories were saved with the
	/// expected conversationId tag or with something else.
	private void dumpAllMemories() {
		try {
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(
					testUser, ModelNames.MODEL_GROUP, "~/Memories",
					org.cote.accountmanager.schema.type.GroupEnumType.DATA.toString(),
					testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
			if (dir == null) {
				logger.warn("[DUEL] dumpAllMemories: no memory group");
				return;
			}
			Query q = QueryUtil.createQuery(org.cote.accountmanager.schema.ModelNames.MODEL_MEMORY,
					FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.planMost(true);
			q.setRequestRange(0L, 500);
			BaseRecord[] recs = IOSystem.getActiveContext().getSearch().findRecords(q);
			int n = recs == null ? 0 : recs.length;
			logger.info("[DUEL] === Raw memory dump: " + n + " memories in group "
					+ dir.get(FieldNames.FIELD_ID) + " (path=~/Memories) ===");
			if (recs != null) {
				for (int i = 0; i < recs.length; i++) {
					BaseRecord m = recs[i];
					String convId = null;
					String type = null;
					String summary = null;
					try { convId = m.get("conversationId"); } catch (Exception ignore) {}
					try {
						Object t = m.get("memoryType");
						type = t == null ? null : t.toString();
					} catch (Exception ignore) {}
					try { summary = m.get("summary"); } catch (Exception ignore) {}
					logger.info(String.format("[MEM] %3d type=%-12s conv=%s summary=%s",
							i, type, convId, truncate(summary, 80)));
				}
			}
		} catch (Exception e) {
			logger.warn("[DUEL] dumpAllMemories failed: " + e.getMessage(), e);
		}
	}

	private void runGossipProbe(long personId, long excludePartnerId,
			String label, String excludeLabel) {
		String[] probes = new String[] {
				"how did they feel about each other",
				"what did they talk about",
				"what happened between them"
		};
		for (String q : probes) {
			List<BaseRecord> hits = MemoryUtil.searchCrossCharacterMemories(
					testUser, personId, excludePartnerId, q, 10, 0.5);
			logger.info("[DUEL] gossip(" + label + " excl=" + excludeLabel
					+ ", q=\"" + q + "\") -> " + (hits == null ? 0 : hits.size())
					+ " memories");
		}
	}

	/// Minimal prompt config — kept short so the test focuses on
	/// reproducing the runaway, not on prompt-template branches.
	/// The config name embeds a version suffix so when the template body
	/// changes here we don't accidentally reuse a stale cached config
	/// from a prior run (DocumentUtil.getRecord finds by name).
	private static final String PROMPT_CONFIG_NAME = "Chat Duel Long Prompt v3";

	private BaseRecord loadPromptConfig(BaseRecord user) {
		BaseRecord opcfg = DocumentUtil.getRecord(user, OlioModelNames.MODEL_PROMPT_CONFIG,
				PROMPT_CONFIG_NAME, "~/Chat");
		if (opcfg != null) return opcfg;

		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, PROMPT_CONFIG_NAME);
		try {
			BaseRecord pcfg = IOSystem.getActiveContext().getFactory()
					.newInstance(OlioModelNames.MODEL_PROMPT_CONFIG, user, null, plist);
			List<String> sys = new ArrayList<>();
			/// Template syntax is ${system.firstName} / ${user.firstName} —
			/// the prompt-template engine resolves these via PromptUtil and
			/// chatConfig.systemCharacter / userCharacter. `{{...}}` is NOT
			/// substituted and the literal text leaks into the LLM, causing
			/// it to echo "{{userCharacter.firstName}}" in its responses.
			sys.add("You are ${system.firstName}, a ${system.age}-year-old ${system.gender}.");
			sys.add("Respond in character to ${user.firstName} in 1-3 short sentences. Stay grounded in the current setting.");
			pcfg.set("system", sys);
			opcfg = IOSystem.getActiveContext().getAccessPoint().create(user, pcfg);
		} catch (Exception e) {
			logger.error("Error creating prompt config: " + e.getMessage(), e);
		}
		return opcfg;
	}

	private BaseRecord createDuelChatConfig(String name, BaseRecord sysChar, BaseRecord usrChar,
			String setting, String startMode) {

		BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser, name);
		try {
			cfg.set("systemCharacter", sysChar);
			cfg.set("userCharacter", usrChar);
			cfg.set("startMode", startMode);
			cfg.set("setting", setting);
			cfg.set("assist", false);
			cfg.set("useNLP", false);
			cfg.set("includeScene", false);
			cfg.set("prune", true);
			cfg.set("messageTrim", OPT_MESSAGE_TRIM);
			cfg.set("rating", ESRBEnumType.E);
			cfg.set("extractMemories", true);
			cfg.set("keyframeEvery", OPT_KEYFRAME_EVERY);
			cfg.set("memoryExtractionEvery", OPT_MEMORY_EXTRACTION_EVERY);
			/// memoryBudget controls how many tokens of retrieved memories
			/// can be injected on each turn. 0 disables retrieval entirely
			/// — which means pruned content is simply lost from context.
			/// We WANT retrieval to fire so the prune/extract/recall loop
			/// is exercised, not just the prune half.
			cfg.set("memoryBudget", OPT_MEMORY_BUDGET);
			cfg.set("requestTimeout", STREAM_TIMEOUT_SECONDS);

			String terrain = NarrativeUtil.getTerrain(octx, usrChar);
			if (terrain != null) {
				cfg.set(FieldNames.FIELD_TERRAIN, terrain);
			}

			/// Per user direction: chat=way-local, analyze=qwen3:8b
			cfg.setValue("serviceType", LLMServiceEnumType.OLLAMA);
			cfg.setValue("serverUrl", OLLAMA_URL);
			cfg.setValue("model", CHAT_MODEL);
			cfg.setValue("analyzeModel", ANALYZE_MODEL);
			cfg.setValue("stream", true);

			BaseRecord opts = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_OPTIONS);
			opts.set("temperature", 0.8);
			opts.set("top_p", 0.95);
			opts.set("repeat_penalty", 1.1);
			opts.set("num_ctx", OPT_NUM_CTX);
			opts.set("max_tokens", OPT_MAX_TOKENS);
			/// Explicit: way-local rejects `think` even when false. Schema
			/// default was changed to false but we set it here too so the
			/// test is hermetic against schema regressions.
			opts.set("think", false);
			cfg.set("chatOptions", opts);

			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
			if (cfg != null) {
				BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(
						testUser, ModelNames.MODEL_GROUP, "~/Chat", "DATA",
						testUser.get(FieldNames.FIELD_ORGANIZATION_ID));
				Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG,
						FieldNames.FIELD_NAME, name);
				q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
				OlioUtil.planMost(q);
				OlioUtil.limitSubplanFields(q.plan(), OlioModelNames.MODEL_CHAT_CONFIG, "event");
				cfg = IOSystem.getActiveContext().getSearch().findRecord(q);
			}
		} catch (StackOverflowError | Exception e) {
			logger.error("Error creating chat config: " + e.getMessage(), e);
			return null;
		}
		return cfg;
	}

	private static String truncate(String s, int maxLen) {
		if (s == null) return "null";
		return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
	}

	/// Emit each message in the request on its own [MSG] line so the
	/// reader can scan content without parsing the whole toFullString
	/// blob. Speaker labels come from the chatConfig role mapping passed
	/// in by the caller (assistant=system-char, user=user-char).
	private void dumpMessages(OpenAIRequest req, String assistantName, String userName) {
		List<OpenAIMessage> msgs = req.getMessages();
		for (int i = 0; i < msgs.size(); i++) {
			OpenAIMessage m = msgs.get(i);
			String role = m.getRole();
			String content = m.getContent();
			boolean pruned = false;
			try { pruned = m.isPruned(); } catch (Exception ignore) { /* legacy */ }
			String speaker;
			if ("system".equals(role)) speaker = "SYSTEM";
			else if ("assistant".equals(role)) speaker = assistantName;
			else if ("user".equals(role)) speaker = userName;
			else speaker = role;
			logger.info(String.format("[MSG] %3d %-10s %s%s",
					i, speaker, (pruned ? "[PRUNED] " : ""),
					content == null ? "<null>" : content.replace('\n', ' ')));
		}
	}

	/// CountDownLatch-based listener mirroring TestMemoryDuel.
	private class DuelStreamListener implements IChatListener {
		private final String tag;
		private volatile CountDownLatch latch;
		private final AtomicReference<OpenAIResponse> responseRef = new AtomicReference<>();
		private final AtomicBoolean errorFlag = new AtomicBoolean(false);
		private final AtomicReference<String> errorMsg = new AtomicReference<>();

		DuelStreamListener(String tag) {
			this.tag = tag;
			this.latch = new CountDownLatch(1);
		}

		void reset() {
			latch = new CountDownLatch(1);
			responseRef.set(null);
			errorFlag.set(false);
			errorMsg.set(null);
		}

		boolean awaitCompletion(int timeoutSeconds) throws InterruptedException {
			return latch.await(timeoutSeconds, TimeUnit.SECONDS);
		}

		OpenAIResponse getResponse() { return responseRef.get(); }
		boolean hasError() { return errorFlag.get(); }
		String getErrorMsg() { return errorMsg.get(); }

		@Override
		public void oncomplete(BaseRecord user, OpenAIRequest request, OpenAIResponse response) {
			responseRef.set(response);
			latch.countDown();
		}

		@Override
		public void onupdate(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String message) {
			/// no-op — response accumulates in Chat.chat()
		}

		@Override
		public void onerror(BaseRecord user, OpenAIRequest request, OpenAIResponse response, String msg) {
			logger.warn("[DUEL] stream " + tag + " error: " + msg);
			errorFlag.set(true);
			errorMsg.set(msg);
			latch.countDown();
		}

		@Override public boolean isStopStream(OpenAIRequest request) { return false; }
		@Override public void stopStream(OpenAIRequest request) { /* no-op */ }
		@Override public boolean isRequesting(OpenAIRequest request) { return latch.getCount() > 0; }
		@Override public OpenAIRequest sendMessageToServer(BaseRecord user, ChatRequest request) { return null; }
		@Override public ChatRequest getMessage(BaseRecord user, ChatRequest messageRequest) { return null; }
		@Override public void addChatHandler(IChatHandler handler) { /* no-op */ }
	}
}
