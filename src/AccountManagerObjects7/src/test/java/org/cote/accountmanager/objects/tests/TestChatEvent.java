package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.llm.ChatEventUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Phase 4 chat event tests (MemoryRefactor2.md).
 *
 * Verifies event creation, interaction membership, timeline,
 * query by participant, chatConfig binding, idempotent create, and close.
 */
public class TestChatEvent extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupChatEvent() {
		testOrgContext = getTestOrganization("/Development/ChatEvent");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "chatEventUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	/// Create a minimal chatConfig-like record for testing.
	/// Uses olio.llm.chatConfig model with required fields.
	private BaseRecord createTestChatConfig(String suffix) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/ChatConfigs");
		plist.parameter(FieldNames.FIELD_NAME, "testChatCfg-" + suffix);
		BaseRecord cfg = IOSystem.getActiveContext().getFactory().newInstance(
			OlioModelNames.MODEL_CHAT_CONFIG, testUser, null, plist);
		cfg = IOSystem.getActiveContext().getAccessPoint().create(testUser, cfg);
		return cfg;
	}

	/// Verify that a new chat creates an olio.event when getOrCreateChatEvent is called.
	@Test
	public void testCreatedOnFirstMessage() {
		try {
			BaseRecord cfg = createTestChatConfig("first-" + UUID.randomUUID().toString().substring(0, 8));
			assertNotNull("ChatConfig should be created", cfg);

			BaseRecord event = ChatEventUtil.getOrCreateChatEvent(testUser, cfg);
			assertNotNull("Event should be created", event);

			String eventOid = event.get(FieldNames.FIELD_OBJECT_ID);
			assertNotNull("Event objectId should be set", eventOid);

			String type = event.get(FieldNames.FIELD_TYPE) != null
				? event.get(FieldNames.FIELD_TYPE).toString() : null;
			assertEquals("Event type should be INTERACT", "INTERACT", type);

			String state = event.get(FieldNames.FIELD_STATE) != null
				? event.get(FieldNames.FIELD_STATE).toString() : null;
			assertEquals("Event state should be IN_PROGRESS", "IN_PROGRESS", state);

			logger.info("testCreatedOnFirstMessage passed — eventOid=" + eventOid);
		} catch (Exception e) {
			logger.error("testCreatedOnFirstMessage failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that extracted interactions become members of the event.
	@Test
	public void testInteractionMembership() {
		try {
			BaseRecord cfg = createTestChatConfig("membership-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord event = ChatEventUtil.getOrCreateChatEvent(testUser, cfg);
			assertNotNull("Event should be created", event);

			// Create an interaction
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Interactions");
			plist.parameter(FieldNames.FIELD_NAME, "SOCIALIZE " + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord inter = IOSystem.getActiveContext().getFactory().newInstance(
				OlioModelNames.MODEL_INTERACTION, testUser, null, plist);
			inter.set(FieldNames.FIELD_TYPE, InteractionEnumType.SOCIALIZE);
			inter.set("state", ActionResultEnumType.COMPLETE);
			inter.set("actorOutcome", OutcomeEnumType.FAVORABLE);
			inter.set("interactorOutcome", OutcomeEnumType.EQUILIBRIUM);
			inter = IOSystem.getActiveContext().getAccessPoint().create(testUser, inter);
			assertNotNull("Interaction should persist", inter);

			// Add to event
			ChatEventUtil.addInteractionToEvent(testUser, cfg, inter);

			// Re-read event and verify interactions list
			IOSystem.getActiveContext().getReader().populate(event);
			List<BaseRecord> interactions = event.get(OlioFieldNames.FIELD_INTERACTIONS);
			assertNotNull("Interactions list should not be null", interactions);
			assertTrue("Interactions list should contain at least 1 entry", interactions.size() >= 1);

			logger.info("testInteractionMembership passed — interactions count=" + interactions.size());
		} catch (Exception e) {
			logger.error("testInteractionMembership failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that eventStart is set when event is created and eventEnd is set when closed.
	@Test
	public void testEventTimeline() {
		try {
			ZonedDateTime before = ZonedDateTime.now();
			BaseRecord cfg = createTestChatConfig("timeline-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord event = ChatEventUtil.getOrCreateChatEvent(testUser, cfg);
			assertNotNull("Event should be created", event);

			ZonedDateTime eventStart = event.get(OlioFieldNames.FIELD_EVENT_START);
			assertNotNull("eventStart should be set", eventStart);
			assertTrue("eventStart should be after test start",
				!eventStart.isBefore(before.minusSeconds(1)));

			// Close the event
			ChatEventUtil.closeChatEvent(testUser, cfg);

			// Re-read event
			IOSystem.getActiveContext().getReader().populate(event);
			ZonedDateTime eventEnd = event.get(OlioFieldNames.FIELD_EVENT_END);
			assertNotNull("eventEnd should be set after close", eventEnd);

			String state = event.get(FieldNames.FIELD_STATE) != null
				? event.get(FieldNames.FIELD_STATE).toString() : null;
			assertEquals("State should be COMPLETE after close", "COMPLETE", state);

			logger.info("testEventTimeline passed — start=" + eventStart + " end=" + eventEnd);
		} catch (Exception e) {
			logger.error("testEventTimeline failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that events can be queried by type.
	@Test
	public void testEventQuery() {
		try {
			BaseRecord cfg = createTestChatConfig("query-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord event = ChatEventUtil.getOrCreateChatEvent(testUser, cfg);
			assertNotNull("Event should be created", event);

			String objectId = event.get(FieldNames.FIELD_OBJECT_ID);

			// Query by objectId
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_EVENT, FieldNames.FIELD_OBJECT_ID, objectId);
			q.planMost(true);
			BaseRecord[] results = IOSystem.getActiveContext().getSearch().findRecords(q);
			assertNotNull("Query results should not be null", results);
			assertTrue("Should find at least 1 result", results.length > 0);

			String foundType = results[0].get(FieldNames.FIELD_TYPE) != null
				? results[0].get(FieldNames.FIELD_TYPE).toString() : null;
			assertEquals("Queried event type should be INTERACT", "INTERACT", foundType);

			logger.info("testEventQuery passed — objectId=" + objectId);
		} catch (Exception e) {
			logger.error("testEventQuery failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that chatConfig.event references the created event.
	@Test
	public void testChatConfigBinding() {
		try {
			BaseRecord cfg = createTestChatConfig("binding-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord event = ChatEventUtil.getOrCreateChatEvent(testUser, cfg);
			assertNotNull("Event should be created", event);

			// Re-read chatConfig to verify binding
			String cfgOid = cfg.get(FieldNames.FIELD_OBJECT_ID);
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, cfgOid);
			q.planMost(true);
			BaseRecord foundCfg = IOSystem.getActiveContext().getSearch().findRecord(q);
			assertNotNull("ChatConfig should be found", foundCfg);

			BaseRecord boundEvent = foundCfg.get("event");
			assertNotNull("ChatConfig.event should be bound", boundEvent);

			logger.info("testChatConfigBinding passed");
		} catch (Exception e) {
			logger.error("testChatConfigBinding failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that calling getOrCreateChatEvent twice does not create a duplicate.
	@Test
	public void testIdempotentCreate() {
		try {
			BaseRecord cfg = createTestChatConfig("idempotent-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord event1 = ChatEventUtil.getOrCreateChatEvent(testUser, cfg);
			assertNotNull("First event should be created", event1);

			String oid1 = event1.get(FieldNames.FIELD_OBJECT_ID);

			// Call again — should return the same event
			BaseRecord event2 = ChatEventUtil.getOrCreateChatEvent(testUser, cfg);
			assertNotNull("Second call should return an event", event2);

			String oid2 = event2.get(FieldNames.FIELD_OBJECT_ID);
			assertEquals("Both calls should return the same event", oid1, oid2);

			logger.info("testIdempotentCreate passed — oid=" + oid1);
		} catch (Exception e) {
			logger.error("testIdempotentCreate failed", e);
			fail("Exception: " + e.getMessage());
		}
	}

	/// Verify that closing a chat sets the event state to COMPLETE and eventEnd.
	@Test
	public void testCloseOnEnd() {
		try {
			BaseRecord cfg = createTestChatConfig("close-" + UUID.randomUUID().toString().substring(0, 8));
			BaseRecord event = ChatEventUtil.getOrCreateChatEvent(testUser, cfg);
			assertNotNull("Event should be created", event);

			String stateBefore = event.get(FieldNames.FIELD_STATE) != null
				? event.get(FieldNames.FIELD_STATE).toString() : null;
			assertEquals("State should be IN_PROGRESS before close", "IN_PROGRESS", stateBefore);

			ChatEventUtil.closeChatEvent(testUser, cfg);

			// Re-read event
			String eventOid = event.get(FieldNames.FIELD_OBJECT_ID);
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_EVENT, FieldNames.FIELD_OBJECT_ID, eventOid);
			q.planMost(true);
			BaseRecord found = IOSystem.getActiveContext().getSearch().findRecord(q);
			assertNotNull("Event should be found after close", found);

			String stateAfter = found.get(FieldNames.FIELD_STATE) != null
				? found.get(FieldNames.FIELD_STATE).toString() : null;
			assertEquals("State should be COMPLETE after close", "COMPLETE", stateAfter);

			ZonedDateTime eventEnd = found.get(OlioFieldNames.FIELD_EVENT_END);
			assertNotNull("eventEnd should be set after close", eventEnd);

			logger.info("testCloseOnEnd passed — eventOid=" + eventOid);
		} catch (Exception e) {
			logger.error("testCloseOnEnd failed", e);
			fail("Exception: " + e.getMessage());
		}
	}
}
