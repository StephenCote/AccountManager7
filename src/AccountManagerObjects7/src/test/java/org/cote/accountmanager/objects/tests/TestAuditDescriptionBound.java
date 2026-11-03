package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.record.RecordValidator;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ActionEnumType;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.junit.Test;

/// 239 f (P10-A 6, 2026-11-02): a query-keyed audit's description carried the whole query key, and a
/// wide by-name projection (olio.llm.chatConfig planMost, as ChatUtil.getConfig issues it) runs to tens
/// of KB against description's maxLength of 2048 (common.description2K, varchar(2048) in the DB).
/// RecordValidator rejected the record on the BatchQueue thread and, because DBWriter.write validates
/// the whole batch before inserting any of it, every audit queued in the same window was lost with it.
/// AuditUtil.closeAudit now cuts description and message to the schema's maxLength (AuditUtil.bounded).
/// The by-name miss is issued as a plain test user through AccessPoint — the PBAC decision is not the
/// subject here and nothing about it changes; the test only proves the decision now reaches the journal.
public class TestAuditDescriptionBound extends BaseTest {

	private static final int DESCRIPTION_MAX = 2048;

	private BaseRecord findAuditByQueryContaining(String marker) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_AUDIT);
		q.field(FieldNames.FIELD_QUERY, ComparatorEnumType.LIKE, "%" + marker + "%");
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_MESSAGE, FieldNames.FIELD_QUERY, FieldNames.FIELD_RESPONSE });
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	private BaseRecord findAuditByMessage(String marker) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_AUDIT, FieldNames.FIELD_MESSAGE, marker);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_MESSAGE, FieldNames.FIELD_RESPONSE });
		q.setCache(false);
		return ioContext.getSearch().findRecord(q);
	}

	@Test
	public void boundedCutsToTheSchemaMaxLengthAndLeavesTheRestAlone() {
		ModelSchema schema = RecordFactory.getSchema(ModelNames.MODEL_AUDIT);
		assertNotNull(schema);
		assertEquals("precondition: description is common.description2K", DESCRIPTION_MAX, schema.getFieldSchema(FieldNames.FIELD_DESCRIPTION).getMaxLength());
		assertEquals("precondition: query carries no maxLength", 0, schema.getFieldSchema(FieldNames.FIELD_QUERY).getMaxLength());

		assertNull("null stays null", AuditUtil.bounded(schema, FieldNames.FIELD_DESCRIPTION, null));
		String shortValue = "INVALID system.user someone to READ a short key";
		assertSame("a value within the limit is returned as is", shortValue, AuditUtil.bounded(schema, FieldNames.FIELD_DESCRIPTION, shortValue));
		String exact = "x".repeat(DESCRIPTION_MAX);
		assertSame("a value at exactly the limit is returned as is", exact, AuditUtil.bounded(schema, FieldNames.FIELD_DESCRIPTION, exact));

		String longValue = "PERMIT system.user someone to READ " + "field,".repeat(12000);
		assertTrue("precondition: well over the limit", longValue.length() > 10 * DESCRIPTION_MAX);
		String cut = AuditUtil.bounded(schema, FieldNames.FIELD_DESCRIPTION, longValue);
		assertEquals("cut to exactly the limit", DESCRIPTION_MAX, cut.length());
		String marker = " ...[" + longValue.length() + " chars]";
		assertTrue("ends in the marker giving the original length: " + cut.substring(cut.length() - 40), cut.endsWith(marker));
		assertEquals("the prefix is the original text", longValue.substring(0, DESCRIPTION_MAX - marker.length()), cut.substring(0, DESCRIPTION_MAX - marker.length()));
		assertSame("a field without a maxLength is never cut", longValue, AuditUtil.bounded(schema, FieldNames.FIELD_QUERY, longValue));
		assertSame("a null schema is tolerated", longValue, AuditUtil.bounded(null, FieldNames.FIELD_DESCRIPTION, longValue));
		assertSame("an unknown field is tolerated", longValue, AuditUtil.bounded(schema, "noSuchField", longValue));
	}

	@Test
	public void anOverlongDescriptionIsWhatTheValidatorRejects() throws Exception {
		BaseRecord user = getCreateUser("p10a6AuditUser");
		assertNotNull(user);
		BaseRecord audit = AuditUtil.startAudit(user, ActionEnumType.READ, user, null);
		assertNotNull(audit);
		audit.set(FieldNames.FIELD_RESPONSE, ResponseEnumType.INVALID);
		audit.set(FieldNames.FIELD_MESSAGE, "No results");
		String longKey = "k-" + "field,".repeat(12000);
		audit.set(FieldNames.FIELD_QUERY, longKey);
		audit.set(FieldNames.FIELD_DESCRIPTION, "INVALID " + longKey);
		assertFalse("the rule 239 f hit: an overlong description fails validation", RecordValidator.validate(RecordOperation.CREATE, audit));
		ModelSchema schema = RecordFactory.getSchema(ModelNames.MODEL_AUDIT);
		audit.set(FieldNames.FIELD_DESCRIPTION, AuditUtil.bounded(schema, FieldNames.FIELD_DESCRIPTION, "INVALID " + longKey));
		assertTrue("the bounded description validates; query is unbounded and untouched", RecordValidator.validate(RecordOperation.CREATE, audit));
		assertEquals(longKey, audit.get(FieldNames.FIELD_QUERY));
	}

	@Test
	public void aLongByNameMissJournalsAndTakesNoNeighbourWithIt() throws Exception {
		OlioModelNames.use();
		BaseRecord user = getCreateUser("p10a6AuditUser");
		assertNotNull(user);
		String name = "p10a6-miss-" + UUID.randomUUID().toString();
		assertNull("precondition: no audit carries the marker yet", findAuditByQueryContaining(name));

		/// The by-name miss ChatUtil.getConfig issues: organizationId + ownerId + name, planMost, no groupId.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.field(FieldNames.FIELD_OWNER_ID, user.get(FieldNames.FIELD_ID));
		q.planMost(true);
		assertTrue("precondition: the key is wider than the description column (" + q.key().length() + ")", q.key().length() > DESCRIPTION_MAX);
		BaseRecord missed = ioContext.getAccessPoint().find(user, q);
		assertNull("a miss", missed);
		BaseRecord lastAudit = AuditUtil.getLastAudit();
		assertNotNull("the miss closed an audit on this thread", lastAudit);
		String response = lastAudit.get(FieldNames.FIELD_RESPONSE);
		String description = lastAudit.get(FieldNames.FIELD_DESCRIPTION);
		assertEquals("the queued description is already within the column", DESCRIPTION_MAX, description.length());
		assertTrue("the queued description names the response: " + description.substring(0, 40), description.startsWith(response + " "));

		/// A short PERMIT queued right behind it, in the same BatchQueue window when the service thread
		/// does not flush in between: before the fix it was lost with the long one (DBWriter.write validates
		/// the whole batch before inserting any of it).
		String neighbour = "p10a6-neighbour-" + UUID.randomUUID().toString();
		BaseRecord permit = AuditUtil.startAudit(user, ActionEnumType.READ, user, user);
		assertNotNull(permit);
		AuditUtil.closeAudit(permit, ResponseEnumType.PERMIT, neighbour);

		/// One synchronous flush, as TestBatchQueueSpill does.
		ioContext.getQueue().execute();

		BaseRecord journaled = findAuditByQueryContaining(name);
		assertNotNull("the long by-name miss is journaled", journaled);
		String stored = journaled.get(FieldNames.FIELD_DESCRIPTION);
		assertEquals("stored at exactly the column width", DESCRIPTION_MAX, stored.length());
		assertTrue("stored description ends in the marker: " + stored.substring(stored.length() - 40), stored.matches("(?s).* \\.\\.\\.\\[\\d+ chars\\]$"));
		assertTrue("stored description names the response", stored.startsWith(response + " "));
		String storedQuery = journaled.get(FieldNames.FIELD_QUERY);
		assertNotNull(storedQuery);
		assertTrue("the full key is kept in query (" + storedQuery.length() + ")", storedQuery.length() > DESCRIPTION_MAX && storedQuery.contains(name));
		assertEquals("No results", journaled.get(FieldNames.FIELD_MESSAGE));

		BaseRecord neighbourRow = findAuditByMessage(neighbour);
		assertNotNull("the short PERMIT queued behind the long miss is journaled too", neighbourRow);
		assertEquals(ResponseEnumType.PERMIT.toString(), ((String) neighbourRow.get(FieldNames.FIELD_RESPONSE)).toUpperCase());
	}
}
