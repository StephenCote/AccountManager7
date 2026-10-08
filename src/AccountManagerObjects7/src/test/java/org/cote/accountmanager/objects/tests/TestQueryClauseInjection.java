package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.junit.Before;
import org.junit.Test;

/// Regression test for the StatementUtil.getQueryString "TODO: Fix SQL Injection point".
///
/// io.query.groupBy and io.query.having are concatenated raw into the SQL (they are structure, not
/// bindable values) and ModelService.search deserializes the whole io.query from the request body, so
/// a REST caller controls them. The fix validates both against a strict grammar + the model schema and
/// rejects anything else with a FieldException before any SQL is built.
///
/// Acting user is a fresh NON-admin user in its own development org; the org admin is never the subject.
/// The positive case is the exact shape TagService.buildTagQuery produces (match-all tags via
/// participation join + GROUP BY alias.id + HAVING COUNT(DISTINCT part.participationId) = N), so this test
/// also proves the guard does not break the legitimate producer.
public class TestQueryClauseInjection extends BaseTest {

	private BaseRecord testUser;
	private BaseRecord tag;
	private BaseRecord data;
	private long groupId;

	@Override
	@Before
	public void setup() {
		super.setup();
		OrganizationContext testOrgContext = getTestOrganization("/Development/QueryClauseInjection");
		testUser = getCreateUser("clauseInjectionUser", testOrgContext);
		assertNotNull("Test user is null", testUser);

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		try {
			ParameterList tagPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/InjectionTags");
			tagPlist.parameter(FieldNames.FIELD_NAME, "Tag-" + suffix);
			tag = ioContext.getAccessPoint().create(testUser, ioContext.getFactory().newInstance(ModelNames.MODEL_TAG, testUser, null, tagPlist));
			assertNotNull("Failed to create tag", tag);

			ParameterList dataPlist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/InjectionData");
			dataPlist.parameter(FieldNames.FIELD_NAME, "Data-" + suffix);
			BaseRecord created = ioContext.getAccessPoint().create(testUser, ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser, null, dataPlist));
			assertNotNull("Failed to create data", created);
			/// create returns identity fields only; re-read to get groupId
			data = ioContext.getAccessPoint().findByObjectId(testUser, ModelNames.MODEL_DATA, created.get(FieldNames.FIELD_OBJECT_ID));
			assertNotNull("Failed to re-read data", data);
			groupId = data.get(FieldNames.FIELD_GROUP_ID);
			assertTrue("Data has no groupId", groupId > 0L);

			assertTrue("Failed to tag data", ioContext.getMemberUtil().member(testUser, tag, data, null, true));
		} catch (FactoryException e) {
			logger.error(e);
			fail(e.getMessage());
		}
	}

	/// Same shape as TagService.buildTagQuery(type, json, matchAll). Returns {query, participationJoin}.
	private Query[] tagQuery() throws FieldException, ValueException, ModelNotFoundException {
		Query query = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, groupId);
		query.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME});
		query.setCache(false);
		Query partQuery = QueryUtil.createParticipationQuery(null, null, null, null, null);
		partQuery.field(FieldNames.FIELD_PARTICIPATION_ID, ComparatorEnumType.IN, Long.toString((long)tag.get(FieldNames.FIELD_ID)));
		partQuery.field(FieldNames.FIELD_PARTICIPATION_MODEL, ModelNames.MODEL_TAG);
		partQuery.field(FieldNames.FIELD_PARTICIPANT_MODEL, ModelNames.MODEL_DATA);
		partQuery.set(FieldNames.FIELD_JOIN_KEY, FieldNames.FIELD_PARTICIPANT_ID);
		List<BaseRecord> joins = query.get(FieldNames.FIELD_JOINS);
		joins.add(partQuery);
		return new Query[] {query, partQuery};
	}

	private void assertDataStillExists() {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, data.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.setCache(false);
		BaseRecord rec = ioContext.getAccessPoint().find(testUser, q);
		assertNotNull("Data record must still exist after a rejected clause", rec);
	}

	/// The guard must throw from StatementUtil (select, count and delete templates alike) and the PBAC
	/// entry point must return null (DBSearch wraps the FieldException in a ReaderException that
	/// AccessPoint.search swallows) - NOT a FAILED/denied QueryResult, which would mean authorization
	/// rather than the clause guard produced the outcome.
	private void assertRejected(String label, Query query) {
		try {
			StatementUtil.getSelectTemplate(query);
			fail(label + ": getSelectTemplate did not reject the clause");
		} catch (FieldException e) {
			logger.info(label + " -> " + e.getMessage());
			assertTrue("Rejection must name the clause: " + e.getMessage(), e.getMessage().startsWith("Invalid GROUP BY clause") || e.getMessage().startsWith("Invalid HAVING clause"));
		} catch (ModelException e) {
			fail(label + ": unexpected ModelException " + e.getMessage());
		}
		try {
			StatementUtil.getCountTemplate(query);
			fail(label + ": getCountTemplate did not reject the clause");
		} catch (FieldException e) {
			/// expected
		} catch (ModelException e) {
			fail(label + ": unexpected ModelException " + e.getMessage());
		}
		QueryResult qr = ioContext.getAccessPoint().list(testUser, query);
		assertNull(label + ": AccessPoint.list must return null for a rejected clause", qr);
		assertDataStillExists();
	}

	@Test
	public void TestLegitimateTagShapeStillWorks() throws FieldException, ValueException, ModelNotFoundException, ModelException {
		Query[] qs = tagQuery();
		Query query = qs[0];
		Query partQuery = qs[1];
		/// Alias assignment happens in getJoinStatement; the producer calls getAlias() itself (TagService:265-267),
		/// which assigns the same alias up front. Mirror that.
		query.set(FieldNames.FIELD_GROUP_CLAUSE, StatementUtil.getAlias(query) + "." + FieldNames.FIELD_ID);
		query.set(FieldNames.FIELD_HAVING_CLAUSE, "COUNT(DISTINCT " + StatementUtil.getAlias(partQuery) + "." + FieldNames.FIELD_PARTICIPATION_ID + ") = 1");

		String sql = StatementUtil.getSelectTemplate(query).getSql();
		logger.info(sql);
		assertTrue("SQL must carry the GROUP BY", sql.contains(" GROUP BY " + StatementUtil.getAlias(query) + ".id"));
		assertTrue("SQL must carry the HAVING", sql.contains(" HAVING COUNT(DISTINCT " + StatementUtil.getAlias(partQuery) + ".participationId) = 1"));

		QueryResult qr = ioContext.getAccessPoint().list(testUser, query);
		assertNotNull("Legitimate tag query returned null", qr);
		assertEquals("Expected exactly the one tagged data record", 1, qr.getCount());
		/// request projection is {id, name} - compare on id
		long expectedId = data.get(FieldNames.FIELD_ID);
		long actualId = qr.getResults()[0].get(FieldNames.FIELD_ID);
		assertEquals(expectedId, actualId);

		/// Bare field (no alias) resolves against the query model; lower-case aggregate and AND-joined terms are accepted.
		query.set(FieldNames.FIELD_GROUP_CLAUSE, FieldNames.FIELD_ID + ", " + StatementUtil.getAlias(query) + "." + FieldNames.FIELD_NAME);
		query.set(FieldNames.FIELD_HAVING_CLAUSE, "count(distinct " + StatementUtil.getAlias(partQuery) + ".participationId) >= 1 AND COUNT(" + StatementUtil.getAlias(partQuery) + ".participantId) <> 0");
		assertNotNull(StatementUtil.getSelectTemplate(query).getSql());
	}

	@Test
	public void TestHostileGroupByIsRejected() throws FieldException, ValueException, ModelNotFoundException {
		String[] hostile = new String[] {
			"ALIAS.id; DROP TABLE a7_data_data_0_1 --",
			"ALIAS.id) OR 1=1 --",
			"ALIAS.id, (SELECT 1)",
			"ALIAS.id UNION SELECT name FROM a7_system_user_0_1",
			"ALIAS.id/**/,ALIAS.name",
			"\"id\"",
			"ALIAS.groupPath",          /// virtual field - no column
			"ALIAS.noSuchField",        /// not a field at all
			"zzz9.id",                  /// alias that is neither the query alias nor a join alias
			"a7_system_user_0_1.name",  /// another table by name
			""
		};
		for(String h : hostile) {
			Query[] qs = tagQuery();
			Query query = qs[0];
			String clause = h.replace("ALIAS", StatementUtil.getAlias(query));
			query.set(FieldNames.FIELD_GROUP_CLAUSE, clause);
			assertRejected("groupBy=[" + clause + "]", query);
		}
	}

	@Test
	public void TestHostileHavingIsRejected() throws FieldException, ValueException, ModelNotFoundException {
		String[] hostile = new String[] {
			"COUNT(DISTINCT PART.participationId) = 1 OR 1=1",
			"COUNT(DISTINCT PART.participationId) = 1; DELETE FROM a7_data_data_0_1 --",
			"COUNT(DISTINCT PART.participationId) = (SELECT 1)",
			"COUNT(DISTINCT PART.participationId) = 1 UNION SELECT 1",
			"COUNT(DISTINCT PART.participationId) = '1'",
			"COUNT(*) > 0",
			"1=1",
			"COUNT(DISTINCT PART.participationId) = 1 AND",
			"COUNT(DISTINCT PART.noSuchField) = 1",
			"COUNT(DISTINCT zzz9.participationId) = 1",
			"COUNT(DISTINCT ALIAS.groupPath) = 1",
			"MD5(ALIAS.name) = 1",
			""
		};
		for(String h : hostile) {
			Query[] qs = tagQuery();
			Query query = qs[0];
			Query partQuery = qs[1];
			String clause = h.replace("ALIAS", StatementUtil.getAlias(query)).replace("PART", StatementUtil.getAlias(partQuery));
			query.set(FieldNames.FIELD_GROUP_CLAUSE, StatementUtil.getAlias(query) + "." + FieldNames.FIELD_ID);
			query.set(FieldNames.FIELD_HAVING_CLAUSE, clause);
			assertRejected("having=[" + clause + "]", query);
		}
	}

	/// The REST entry point: ModelService.search imports io.query JSON through the filtered deserializer and
	/// wraps it in a Query before AccessPoint.list. Prove a client-supplied groupBy/having survives that import
	/// (i.e. the field really is attacker-controlled) and is then rejected by the same guard.
	@Test
	public void TestClientSuppliedClauseViaJsonIsRejected() {
		String json = "{\"schema\":\"" + ModelNames.MODEL_QUERY + "\",\"type\":\"" + ModelNames.MODEL_DATA + "\","
			+ "\"request\":[\"id\",\"name\"],"
			+ "\"fields\":[{\"name\":\"groupId\",\"comparator\":\"EQUALS\",\"value\":" + groupId + "}],"
			+ "\"groupBy\":\"x.id) OR 1=1 --\","
			+ "\"having\":\"COUNT(x.id) > 0 OR 1=1\"}";
		BaseRecord imp = JSONUtil.importObject(json, LooseRecord.class, RecordDeserializerConfig.getFilteredModule());
		assertNotNull("io.query JSON failed to import", imp);
		assertEquals("x.id) OR 1=1 --", imp.get(FieldNames.FIELD_GROUP_CLAUSE));
		Query query = new Query(imp);
		query.setCache(false);
		assertRejected("json groupBy/having", query);
	}
}
