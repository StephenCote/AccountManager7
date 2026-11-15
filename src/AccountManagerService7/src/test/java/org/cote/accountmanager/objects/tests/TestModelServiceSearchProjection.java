package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.ModelService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// KI-83 (§24 I-9, 2026-11-14): POST /rest/model/search with a `request` projection the model cannot
/// honour - an unknown top-level field, or a dotted path whose segments do not all resolve to a
/// plannable foreign model field - used to reach StatementUtil.getSelectTemplate as a FieldException,
/// which DBSearch wraps in a ReaderException and AccessPoint.search swallows into a null result, so the
/// route answered 200 with an EMPTY body: indistinguishable from "no such record". This file proves the
/// route (and /search/count) now answer 400 with a message naming the field and the model, and that a
/// VALID nested path ("connection.serverUrl" on olio.llm.chatConfig, the audit's exact call) still
/// answers 200 with the nested value projected - Query.expandRequestPaths (2026-10-08) made that work
/// at the Objects7 layer (TestQueryNestedPathProjection); this is the transport contract on top of it.
///
/// Same in-process pattern as TestModelServicePatch: real service method, real AccessPoint/PBAC, the
/// real (H2) database, HttpServletRequestMock + UserPrincipal. The actor is a non-admin user.
public class TestModelServiceSearchProjection extends BaseTest {

	private static final String SERVER_URL = "https://ki83-nested-path.example.test:11434";

	private final ModelService service = new ModelService();

	private BaseRecord owner;
	private BaseRecord conn;
	private BaseRecord cfg;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		owner = getCreateUser("mssOwner");
		assertNotNull(owner);
		try {
			String suffix = UUID.randomUUID().toString();
			conn = createConnection(owner, "ki83-conn-" + suffix);
			cfg = createChatConfig(owner, "ki83-cfg-" + suffix, conn);
		}
		catch(Exception e) {
			throw new RuntimeException(e);
		}
	}

	private HttpServletRequestMock requestAs(BaseRecord user) {
		return new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), organizationPath));
	}

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

	private BaseRecord createChatConfig(BaseRecord user, String name, BaseRecord connection) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord c = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAT_CONFIG, user, null, plist);
		c.set("connection", connection);
		c.set("model", "ki83-model");
		BaseRecord created = ioContext.getAccessPoint().create(user, c);
		assertNotNull("chatConfig CREATE returned null", created);
		return created;
	}

	/// The exact wire shape the Ux752 client sends: an io.query with a `request` projection and one
	/// objectId condition, uncached, scoped to the actor's organization.
	private String queryBody(String... request) {
		Map<String, Object> q = new LinkedHashMap<>();
		q.put("schema", ModelNames.MODEL_QUERY);
		q.put(FieldNames.FIELD_TYPE, OlioModelNames.MODEL_CHAT_CONFIG);
		q.put(FieldNames.FIELD_REQUEST, new ArrayList<>(Arrays.asList(request)));
		List<Map<String, Object>> fields = new ArrayList<>();
		Map<String, Object> f1 = new LinkedHashMap<>();
		f1.put(FieldNames.FIELD_NAME, FieldNames.FIELD_OBJECT_ID);
		f1.put("comparator", "EQUALS");
		f1.put("value", cfg.get(FieldNames.FIELD_OBJECT_ID));
		fields.add(f1);
		Map<String, Object> f2 = new LinkedHashMap<>();
		f2.put(FieldNames.FIELD_NAME, FieldNames.FIELD_ORGANIZATION_ID);
		f2.put("comparator", "EQUALS");
		f2.put("value", (long) owner.get(FieldNames.FIELD_ORGANIZATION_ID));
		fields.add(f2);
		q.put("fields", fields);
		q.put("cache", false);
		return JSONUtil.exportObject(q);
	}

	private Response search(String body) {
		return service.search(body, requestAs(owner), new HttpServletResponseMock());
	}

	private Response count(String body) {
		return service.searchCount(body, requestAs(owner), new HttpServletResponseMock());
	}

	@SuppressWarnings("unchecked")
	private String errorOf(Response r) {
		assertNotNull("Response entity is null", r.getEntity());
		Map<String, Object> m = JSONUtil.importObject(r.getEntity().toString(), LinkedHashMap.class);
		assertNotNull("Error body did not parse as JSON: " + r.getEntity(), m);
		Object err = m.get("error");
		assertNotNull("Error body must carry 'error': " + r.getEntity(), err);
		return err.toString();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> resultsOf(Response r) {
		assertNotNull("search answered with an EMPTY body (the KI-83 symptom)", r.getEntity());
		Map<String, Object> m = JSONUtil.importObject(r.getEntity().toString(), LinkedHashMap.class);
		assertNotNull("search body did not parse as JSON: " + r.getEntity(), m);
		Object results = m.get("results");
		assertNotNull("search body must carry 'results': " + r.getEntity(), results);
		return (List<Map<String, Object>>) results;
	}

	/// ------------------------------------------------------------------------------------------

	@Test
	@SuppressWarnings("unchecked")
	public void TestValidNestedPathIs200WithNestedValue() {
		Response r = search(queryBody(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "connection.serverUrl"));
		assertEquals("valid nested path must be 200, body: " + r.getEntity(), 200, r.getStatus());
		List<Map<String, Object>> results = resultsOf(r);
		assertEquals("exactly the one chatConfig", 1, results.size());
		Map<String, Object> rec = results.get(0);
		assertEquals(cfg.get(FieldNames.FIELD_OBJECT_ID), rec.get(FieldNames.FIELD_OBJECT_ID));
		Object nested = rec.get("connection");
		assertNotNull("nested connection must be projected: " + r.getEntity(), nested);
		assertTrue("nested connection must be an object, got " + nested, nested instanceof Map);
		Map<String, Object> nconn = (Map<String, Object>) nested;
		assertEquals("nested serverUrl must be projected", SERVER_URL, nconn.get("serverUrl"));
		assertEquals("nested identity must be present", ((Number) conn.get(FieldNames.FIELD_ID)).longValue(), ((Number) nconn.get(FieldNames.FIELD_ID)).longValue());
	}

	@Test
	public void TestControlWithoutProjectionIs200() {
		/// No `request` at all: the model's default query fields. Pins that the new pre-flight does not
		/// touch the common case.
		Response r = search(queryBody());
		assertEquals("default projection must be 200, body: " + r.getEntity(), 200, r.getStatus());
		List<Map<String, Object>> results = resultsOf(r);
		assertEquals(1, results.size());
		assertEquals(cfg.get(FieldNames.FIELD_OBJECT_ID), results.get(0).get(FieldNames.FIELD_OBJECT_ID));
	}

	@Test
	public void TestInvalidNestedLeafIs400() {
		Response r = search(queryBody(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "connection.noSuchField"));
		assertEquals("unknown nested leaf must be 400, body: " + r.getEntity(), 400, r.getStatus());
		String err = errorOf(r);
		assertTrue("error must name the offending path: " + err, err.contains("connection.noSuchField"));
		assertTrue("error must name the model: " + err, err.contains(OlioModelNames.MODEL_CHAT_CONFIG));
	}

	@Test
	public void TestNonModelSegmentIs400() {
		/// `model` is a string field on chatConfig: a path through it cannot be planned.
		Response r = search(queryBody(FieldNames.FIELD_ID, "model.anything"));
		assertEquals("path through a non-model field must be 400, body: " + r.getEntity(), 400, r.getStatus());
		String err = errorOf(r);
		assertTrue("error must name the offending path: " + err, err.contains("model.anything"));
	}

	@Test
	public void TestUnknownTopLevelFieldIs400() {
		Response r = search(queryBody(FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "noSuchField"));
		assertEquals("unknown top-level field must be 400, body: " + r.getEntity(), 400, r.getStatus());
		String err = errorOf(r);
		assertTrue("error must name the offending field: " + err, err.contains("noSuchField"));
		assertTrue("error must name the model: " + err, err.contains(OlioModelNames.MODEL_CHAT_CONFIG));
	}

	@Test
	public void TestCountWithInvalidPathIs400AndValidPathCounts() {
		Response bad = count(queryBody(FieldNames.FIELD_ID, "connection.noSuchField"));
		assertEquals("count with an unknown nested leaf must be 400, body: " + bad.getEntity(), 400, bad.getStatus());
		assertTrue(errorOf(bad).contains("connection.noSuchField"));

		Response ok = count(queryBody(FieldNames.FIELD_ID, "connection.serverUrl"));
		assertEquals("count with a valid nested path must be 200, body: " + ok.getEntity(), 200, ok.getStatus());
		assertNotNull(ok.getEntity());
		assertEquals("exactly the one chatConfig", 1, ((Number) ok.getEntity()).intValue());
	}
}
