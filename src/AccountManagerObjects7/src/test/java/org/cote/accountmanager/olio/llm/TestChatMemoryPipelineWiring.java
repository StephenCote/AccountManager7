package org.cote.accountmanager.olio.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/// MemoryKeyframeDecouplingPlan §3 — in-process integration test that
/// verifies the trigger / state-machine wiring without making any LLM
/// calls. Lives in the same package as Chat so it can call the
/// package-private `checkKeyframeTrigger` / `checkMemoryExtractionTrigger`
/// directly and assert on the pending-snapshot state.
///
/// Covers the 4 scenarios from the plan's behavior matrix:
///   keyframeEvery=0 + extractMemories=false   → neither pipeline fires
///   keyframeEvery=0 + extractMemories=true    → memory fires, keyframe does not
///   keyframeEvery=N + extractMemories=false   → keyframe fires, memory does not
///   keyframeEvery=N + extractMemories=true    → BOTH fire on their own cadences
public class TestChatMemoryPipelineWiring extends BaseTest {

	private static final String userRole = "user";
	private static final String assistantRole = "assistant";

	private OrganizationContext setupOrg() {
		OrganizationContext ctx = getTestOrganization("/Development/MemPipelineWiring");
		Factory mf = IOSystem.getActiveContext().getFactory();
		BaseRecord testUser = mf.getCreateUser(ctx.getAdminUser(), "memPipeWireUser",
			ctx.getOrganizationId());
		assertTrue("test user", testUser != null);
		return ctx;
	}

	private BaseRecord getTestUser(OrganizationContext ctx) {
		return IOSystem.getActiveContext().getFactory().getCreateUser(
			ctx.getAdminUser(), "memPipeWireUser", ctx.getOrganizationId());
	}

	/// Build a minimal chatConfig with the specified cadence settings.
	/// Persists it via the standard ChatUtil path so configureChat can
	/// re-read it normally.
	private BaseRecord makeConfig(BaseRecord testUser, int keyframeEvery,
			boolean extractMemories, int memoryExtractionEvery) {
		String uniqueName = "MemWire-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser, uniqueName);
		try {
			cfg.set("assist", false);
			cfg.set("prune", true);
			cfg.set("messageTrim", 20);
			cfg.set("keyframeEvery", keyframeEvery);
			cfg.set("extractMemories", extractMemories);
			cfg.set("memoryExtractionEvery", memoryExtractionEvery);
			cfg.set("memoryBudget", 0);  // disable retrieval — we're only testing triggers
			cfg.set("lastKeyframeAt", 0);
			cfg.set("lastMemoryExtractionAt", 0);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, cfg);
		} catch (Exception e) {
			throw new RuntimeException("Failed to configure test chatConfig: " + e.getMessage(), e);
		}
		return cfg;
	}

	/// Build a fake OpenAIRequest with `msgCount` alternating user/assistant
	/// messages — just enough structure for the trigger logic to count.
	private OpenAIRequest makeRequest(int msgCount) {
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("test-model");
		List<OpenAIMessage> msgs = new ArrayList<>();
		for (int i = 0; i < msgCount; i++) {
			OpenAIMessage m = new OpenAIMessage();
			m.setRole(i % 2 == 0 ? userRole : assistantRole);
			m.setContent("msg-" + i + " content");
			msgs.add(m);
		}
		req.setMessages(msgs);
		return req;
	}

	private Chat newChat(BaseRecord testUser, BaseRecord cfg) {
		Chat chat = new Chat(testUser, cfg, null);
		/// Chat normally enters chatMode in continueChat; set it manually so
		/// the trigger gates (which check chatMode) don't short-circuit.
		chat.setChatMode(true);
		return chat;
	}

	// ── Scenario 1: keyframeEvery=0, extractMemories=false ─────────────

	@Test
	public void neitherPipelineTriggersWhenBothDisabled() {
		OrganizationContext ctx = setupOrg();
		BaseRecord testUser = getTestUser(ctx);
		BaseRecord cfg = makeConfig(testUser, 0, false, 5);
		Chat chat = newChat(testUser, cfg);

		assertEquals(0, chat.getKeyFrameEvery());
		assertEquals(5, chat.getMemoryExtractionEvery());

		/// Build a request big enough to trigger any cadence.
		OpenAIRequest req = makeRequest(20);
		chat.checkKeyframeTrigger(req);
		chat.checkMemoryExtractionTrigger(req);

		assertFalse("keyframe must NOT fire when keyframeEvery=0", chat.hasPendingKeyframeSnapshot());
		assertFalse("memory must NOT fire when extractMemories=false", chat.hasPendingMemorySnapshot());
	}

	// ── Scenario 2: keyframeEvery=0, extractMemories=true ──────────────

	@Test
	public void memoryFiresIndependentOfKeyframe() {
		OrganizationContext ctx = setupOrg();
		BaseRecord testUser = getTestUser(ctx);
		BaseRecord cfg = makeConfig(testUser, 0, true, 5);
		Chat chat = newChat(testUser, cfg);

		assertEquals("keyframe disabled", 0, chat.getKeyFrameEvery());
		assertEquals("memory every 5", 5, chat.getMemoryExtractionEvery());

		OpenAIRequest req = makeRequest(10);  // 10 - introOverhead(1) = 9 >= 5
		chat.checkKeyframeTrigger(req);
		chat.checkMemoryExtractionTrigger(req);

		assertFalse("keyframe still disabled", chat.hasPendingKeyframeSnapshot());
		assertTrue("memory MUST fire when extractMemories=true and threshold reached",
			chat.hasPendingMemorySnapshot());
	}

	// ── Scenario 3: keyframeEvery=N, extractMemories=false ─────────────

	@Test
	public void keyframeFiresWithoutMemoryWhenExtractDisabled() {
		OrganizationContext ctx = setupOrg();
		BaseRecord testUser = getTestUser(ctx);
		BaseRecord cfg = makeConfig(testUser, 5, false, 5);
		Chat chat = newChat(testUser, cfg);

		assertEquals(5, chat.getKeyFrameEvery());

		OpenAIRequest req = makeRequest(10);
		chat.checkKeyframeTrigger(req);
		chat.checkMemoryExtractionTrigger(req);

		assertTrue("keyframe MUST fire when keyframeEvery=5 and threshold reached",
			chat.hasPendingKeyframeSnapshot());
		assertFalse("memory must NOT fire when extractMemories=false even with keyframes",
			chat.hasPendingMemorySnapshot());
	}

	// ── Scenario 4: both enabled, independent cadences ─────────────────

	@Test
	public void bothFireWhenBothEnabledAndThresholdsMet() {
		OrganizationContext ctx = setupOrg();
		BaseRecord testUser = getTestUser(ctx);
		BaseRecord cfg = makeConfig(testUser, 5, true, 3);
		Chat chat = newChat(testUser, cfg);

		assertEquals(5, chat.getKeyFrameEvery());
		assertEquals(3, chat.getMemoryExtractionEvery());

		OpenAIRequest req = makeRequest(8);  // 8 - intro(1) = 7. Both thresholds met.
		chat.checkKeyframeTrigger(req);
		chat.checkMemoryExtractionTrigger(req);

		assertTrue("keyframe must fire on its own cadence", chat.hasPendingKeyframeSnapshot());
		assertTrue("memory must fire on its own cadence", chat.hasPendingMemorySnapshot());
	}

	// ── No keyframeEvery auto-upgrade for extractMemories=true ─────────

	@Test
	public void extractMemoriesTrueDoesNotForceKeyframeEvery() {
		/// Pre-MemoryKeyframeDecouplingPlan: configureChat would silently
		/// bump keyframeEvery to 5 when extractMemories=true and keyframeEvery=0.
		/// Post-plan: keyframeEvery stays at whatever the user set.
		OrganizationContext ctx = setupOrg();
		BaseRecord testUser = getTestUser(ctx);
		BaseRecord cfg = makeConfig(testUser, 0, true, 7);
		Chat chat = newChat(testUser, cfg);

		assertEquals("keyframeEvery must NOT be force-upgraded",
			0, chat.getKeyFrameEvery());
		assertEquals("memoryExtractionEvery preserved as-is",
			7, chat.getMemoryExtractionEvery());
	}

	// ── Memory trigger below threshold doesn't fire ────────────────────

	@Test
	public void memoryDoesNotFireBelowThreshold() {
		OrganizationContext ctx = setupOrg();
		BaseRecord testUser = getTestUser(ctx);
		BaseRecord cfg = makeConfig(testUser, 0, true, 5);
		Chat chat = newChat(testUser, cfg);

		/// 4 messages, intro=1, sinceLast=3, every=5 → no fire.
		OpenAIRequest req = makeRequest(4);
		chat.checkMemoryExtractionTrigger(req);
		assertFalse(chat.hasPendingMemorySnapshot());
	}

	// ── Cadences run on independent counters ───────────────────────────

	@Test
	public void cadencesAreIndependent() {
		OrganizationContext ctx = setupOrg();
		BaseRecord testUser = getTestUser(ctx);
		/// keyframeEvery=3, memoryExtractionEvery=5: at msg=7, sinceLast=6 →
		/// keyframe fires (6 >= 3), memory fires (6 >= 5). At msg=4, sinceLast=3 →
		/// keyframe fires (3 >= 3), memory does NOT fire (3 < 5).
		BaseRecord cfg = makeConfig(testUser, 3, true, 5);
		Chat chatA = newChat(testUser, cfg);

		OpenAIRequest req4 = makeRequest(4);
		chatA.checkKeyframeTrigger(req4);
		chatA.checkMemoryExtractionTrigger(req4);
		assertTrue("keyframe fires at msg=4 with every=3", chatA.hasPendingKeyframeSnapshot());
		assertFalse("memory does NOT fire at msg=4 with every=5", chatA.hasPendingMemorySnapshot());

		/// Fresh chat instance (each request is its own Chat), config reread.
		Chat chatB = newChat(testUser, ChatUtil.getCreateChatConfig(testUser,
			(String) cfg.get(FieldNames.FIELD_NAME)));
		OpenAIRequest req7 = makeRequest(7);
		chatB.checkKeyframeTrigger(req7);
		chatB.checkMemoryExtractionTrigger(req7);
		assertTrue("keyframe still fires at msg=7", chatB.hasPendingKeyframeSnapshot());
		assertTrue("memory fires at msg=7 with every=5", chatB.hasPendingMemorySnapshot());
	}
}
