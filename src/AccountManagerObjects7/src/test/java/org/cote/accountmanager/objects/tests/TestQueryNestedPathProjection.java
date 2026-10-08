package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryPlan;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Test;

/**
 * Core REST audit item 3-1 (2026-10-07): a request projection containing a nested path such as
 * {@code connection.serverUrl} is documented in model-api.md but was never honoured.
 * {@code StatementUtil.getSelectTemplate} resolved every request entry against the top-level schema
 * and threw {@code FieldException: Field 'connection.serverUrl' was not found on model
 * olio.llm.chatConfig}; {@code DBSearch} swallowed it and the REST layer answered 200 with an empty
 * body. Reproduced live as the shared (non-admin) user before the fix.
 *
 * <p>{@code Query.expandRequestPaths()} now turns {@code a.b[.c]} into the root field {@code a} plus a
 * sub-plan carrying the nested model's common fields and {@code b}, which
 * {@code StatementUtil.getInnerSelectTemplate} already projects through {@code getPlan(fieldName)}.
 * Runs as a NON-admin user; every read that checks a projection sets {@code cache(false)}.</p>
 */
public class TestQueryNestedPathProjection extends BaseTest {

	private static final String SERVER_URL = "https://nested-path.example.test:11434";

	private BaseRecord createConnection(BaseRecord user, String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord c = ioContext.getFactory().newInstance(ModelNames.MODEL_CONNECTION, user, null, plist);
		c.set("serverUrl", SERVER_URL);
		c.set("requestTimeout", 77);
		BaseRecord created = ioContext.getAccessPoint().create(user, c);
		assertNotNull("connection CREATE returned null", created);
		return created;
	}

	private BaseRecord createChatConfig(BaseRecord user, String name, BaseRecord conn) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord cfg = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		cfg.set("connection", conn);
		cfg.set("model", "nested-path-model");
		BaseRecord created = ioContext.getAccessPoint().create(user, cfg);
		assertNotNull("chatConfig CREATE returned null", created);
		return created;
	}

	/// The exact shape ModelService.search builds: a Query copied from an imported io.query record
	/// whose request carries a dotted path.
	private Query restShapedQuery(BaseRecord user, String cfgObjectId, String... request) {
		Query raw = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, cfgObjectId);
		raw.field(FieldNames.FIELD_ORGANIZATION_ID, (long) user.get(FieldNames.FIELD_ORGANIZATION_ID));
		raw.getRequest().clear();
		raw.getRequest().addAll(Arrays.asList(request));
		raw.setCache(false);
		Query q = new Query(raw);
		q.setContextUser(user);
		return q;
	}

	@Test
	public void testExpandRequestPathsRewritesRequestAndPlan() throws Exception {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, "x");
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_NAME, "connection.serverUrl", "connection.requestTimeout" });

		assertTrue("root field must replace the dotted entries", q.getRequest().contains("connection"));
		assertFalse("dotted entry must not survive in the top-level request", q.getRequest().contains("connection.serverUrl"));
		assertEquals("duplicate roots must collapse", 3, q.getRequest().size());

		QueryPlan sub = q.getPlan("connection");
		assertNotNull("sub-plan for connection must exist", sub);
		assertEquals(ModelNames.MODEL_CONNECTION, sub.getModelName());
		assertTrue(sub.getPlanFields().contains("serverUrl"));
		assertTrue(sub.getPlanFields().contains("requestTimeout"));
		assertTrue("nested common fields keep the record identifiable", sub.getPlanFields().contains(FieldNames.FIELD_ID));
		assertTrue(sub.getPlanFields().contains(FieldNames.FIELD_OBJECT_ID));

		/// Idempotent: a second pass is a no-op.
		assertFalse(q.expandRequestPaths());

		/// The select template must now build without FieldException.
		String sql = q.toSelect();
		assertNotNull("select template must build", sql);
		assertTrue("nested column must be materialized", sql.toLowerCase().contains("serverurl"));
	}

	@Test
	public void testInvalidPathIsLeftForRejection() throws Exception {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, "x");
		q.setRequest(new String[] { FieldNames.FIELD_ID, "connection.noSuchField", "model.anything" });
		/// Neither path resolves: 'noSuchField' is not on system.connection and 'model' is a string.
		assertTrue(q.getRequest().contains("connection.noSuchField"));
		assertTrue(q.getRequest().contains("model.anything"));
		assertNull("no plan is created for an invalid path", q.getPlan("connection"));
		/// toSelect logs and returns null because getSelectTemplate still throws the field-not-found error.
		assertNull("invalid path must still be rejected, not silently projected", q.toSelect());
	}

	@Test
	public void testCacheKeyDistinguishesNestedPlans() throws Exception {
		Query plain = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, "x");
		plain.setRequest(new String[] { FieldNames.FIELD_ID, "connection" });
		Query nested = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, "x");
		nested.setRequest(new String[] { FieldNames.FIELD_ID, "connection.serverUrl" });
		Query nested2 = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_OBJECT_ID, "x");
		nested2.setRequest(new String[] { FieldNames.FIELD_ID, "connection.requestTimeout" });

		assertEquals("top-level request lists are identical", plain.getRequest(), nested.getRequest());
		assertNotEquals("a nested plan must not share the bare request's cache key", plain.key(), nested.key());
		assertNotEquals("different nested projections must not share a cache key", nested.key(), nested2.key());
	}

	@Test
	public void testNestedPathProjectionReturnsNestedValue() throws Exception {
		BaseRecord user = getCreateUser("nestedPathUser1");
		String suffix = UUID.randomUUID().toString();
		BaseRecord conn = createConnection(user, "np-conn-" + suffix);
		BaseRecord cfg = createChatConfig(user, "np-cfg-" + suffix, conn);
		String cfgOid = cfg.get(FieldNames.FIELD_OBJECT_ID);

		/// Control: bare 'connection' projects only the nested common fields (serverUrl absent).
		Query control = restShapedQuery(user, cfgOid, FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "connection");
		QueryResult cqr = ioContext.getAccessPoint().list(user, control);
		assertNotNull("control list returned null", cqr);
		assertEquals(1, cqr.getResults().length);
		BaseRecord cconn = cqr.getResults()[0].get("connection");
		assertNotNull("control must still return the nested record", cconn);
		assertFalse("control must not carry serverUrl", cconn.hasField("serverUrl") && cconn.get("serverUrl") != null);

		/// The failing call from the audit: request a nested path.
		Query q = restShapedQuery(user, cfgOid, FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "connection.serverUrl");
		QueryResult qr = ioContext.getAccessPoint().list(user, q);
		assertNotNull("nested-path list returned null (FieldException in StatementUtil?)", qr);
		assertEquals(1, qr.getResults().length);
		BaseRecord rec = qr.getResults()[0];
		assertEquals(cfgOid, rec.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord nconn = rec.get("connection");
		assertNotNull("nested connection must be populated", nconn);
		assertEquals("nested serverUrl must be projected", SERVER_URL, nconn.get("serverUrl"));
		assertEquals("nested identity must be present", (long) conn.get(FieldNames.FIELD_ID), (long) nconn.get(FieldNames.FIELD_ID));
		/// requestTimeout was not requested and is not a common field, so it must not be projected.
		Integer rt = nconn.hasField("requestTimeout") ? nconn.get("requestTimeout") : null;
		assertTrue("unrequested nested field must not be projected", rt == null || rt.intValue() == 0);

		/// find() path (AccessPoint.find authorizes and canRead-checks the record) with two leaves.
		Query fq = restShapedQuery(user, cfgOid, FieldNames.FIELD_ID, FieldNames.FIELD_NAME, "connection.serverUrl", "connection.requestTimeout");
		BaseRecord found = ioContext.getAccessPoint().find(user, fq);
		assertNotNull("find with nested paths returned null", found);
		BaseRecord fconn = found.get("connection");
		assertNotNull(fconn);
		assertEquals(SERVER_URL, fconn.get("serverUrl"));
		assertEquals(77, (int) fconn.get("requestTimeout"));
	}
}
