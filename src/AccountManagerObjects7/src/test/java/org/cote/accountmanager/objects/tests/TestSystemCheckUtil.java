package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil.ConnectionTarget;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.cote.accountmanager.schema.type.ConnectionUpstreamEnumType;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.cote.accountmanager.util.AdminMaintenanceException;
import org.cote.accountmanager.util.AdminMaintenanceUtil;
import org.cote.accountmanager.util.ConnectionProbeUtil;
import org.cote.accountmanager.util.SystemCheckUtil;
import org.junit.Assume;
import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/// SystemCheckUtil / ConnectionProbeUtil - the Objects7 half of Service Status (aiDocs/ServiceStatusDesign.md
/// §9 JUnit list). Live: am7db, the local Ollama container (:11435), LiteLLM (:4000), the embedding server the
/// test VectorUtil points at, and the LLM route LlmTestGate resolved (its [LLM-GATE] line is in the log).
///
/// Actors (never the built-in admin as a subject - llm-conduct rule 3):
///   ssAdminUser    AccountAdministrators of /Development/ServiceStatus - a NON-/System administrator
///   ssSysOrgAdmin  AccountAdministrators of /System, credential-less - the /System administrator (vectors, URLs)
///   ssPlainOne/ssPlainTwo  plain users of /Development/ServiceStatus (canRead filter, refusal)
///   ssParAdmin     AccountAdministrators of /Development/ServiceStatusPar, owning exactly 8 black-hole connections
///
/// Unreachable addresses: 127.0.0.1:9 (discard port, closed -> refused at once) and 192.0.2.1 (TEST-NET-1,
/// RFC 5737 - never routed, so a connect hangs until the 5 s connect timeout).
public class TestSystemCheckUtil extends BaseTest {

	private static final String ORG = "/Development/ServiceStatus";
	private static final String ORG_PAR = "/Development/ServiceStatusPar";
	private static final String ADMIN = "ssAdminUser";
	private static final String SYS_ORG_ADMIN = "ssSysOrgAdmin";
	private static final String PLAIN_A = "ssPlainOne";
	private static final String PLAIN_B = "ssPlainTwo";
	private static final String PAR_ADMIN = "ssParAdmin";

	private static final String OLLAMA_URL = "http://127.0.0.1:11435";
	private static final String LITELLM_URL = "http://127.0.0.1:4000";
	private static final String CLOSED_URL = "http://127.0.0.1:9";
	private static final String BLACKHOLE_URL = "http://192.0.2.1:11434";
	private static final String SENTINEL_KEY = "sk-sentinel-svcstatus-7f3a9c";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	// ─────────────────────────────── fixture ───────────────────────────────

	private OrganizationContext org(String path) {
		OrganizationContext o = getTestOrganization(path);
		assertNotNull("Failed to resolve " + path, o);
		assertTrue(path + " is not initialized", o.isInitialized());
		return o;
	}

	private BaseRecord withRole(OrganizationContext o, String userName, String roleName) {
		BaseRecord u = getCreateUser(userName, o);
		assertNotNull("Failed to resolve " + userName + " - check the $minLen5 rule", u);
		BaseRecord role = AccessSchema.getSystemRole(roleName, RoleEnumType.USER.toString(), o.getOrganizationId());
		assertNotNull(roleName + " role is null", role);
		/// The grant is inherently an admin act, so the org admin is the setup actor only; idempotent.
		ioContext.getMemberUtil().member(o.getAdminUser(), role, u, null, true);
		assertTrue(userName + " must be a " + roleName + " member", ioContext.getMemberUtil().isMember(u, role, null));
		return u;
	}

	private BaseRecord admin() {
		OrganizationContext o = org(ORG);
		BaseRecord u = withRole(o, ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
		assertTrue(AdminMaintenanceUtil.isAdministrator(u, o.getOrganizationId()));
		assertFalse("the org admin-role user must NOT be a /System administrator",
			AdminMaintenanceUtil.isSystemOrganizationAdministrator(u, o.getOrganizationId()));
		return u;
	}

	private BaseRecord systemOrgAdmin() {
		OrganizationContext sys = ioContext.getOrganizationContext(OrganizationContext.SYSTEM_ORGANIZATION, OrganizationEnumType.SYSTEM);
		assertNotNull(sys);
		assertTrue(sys.isInitialized());
		BaseRecord u = withRole(sys, SYS_ORG_ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
		assertTrue(AdminMaintenanceUtil.isSystemOrganizationAdministrator(u, sys.getOrganizationId()));
		return u;
	}

	private BaseRecord plain(String name) {
		OrganizationContext o = org(ORG);
		BaseRecord u = getCreateUser(name, o);
		assertNotNull("Failed to resolve " + name, u);
		assertFalse(name + " must not be an administrator", AdminMaintenanceUtil.isAdministrator(u, o.getOrganizationId()));
		return u;
	}

	private BaseRecord conn(BaseRecord user, String name, String url, String key, ConnectionDialectEnumType dialect) {
		BaseRecord c = OlioTestUtil.getCreateConnection(user, name, url, key, dialect, ConnectionUpstreamEnumType.UNKNOWN, 120);
		assertNotNull("connection " + name, c);
		String oid = c.get(FieldNames.FIELD_OBJECT_ID);
		if(oid == null) {
			c = OlioTestUtil.readConnection(user, (long) c.get(FieldNames.FIELD_ID));
		}
		assertNotNull(c.get(FieldNames.FIELD_OBJECT_ID));
		return c;
	}

	private static Map<String, String> defaults() {
		Map<String, String> d = new LinkedHashMap<>();
		/// sd points at a closed port (optional -> warn); everything else is unconfigured (skip).
		d.put("sd.server", CLOSED_URL);
		return d;
	}

	private static String json(Object o) throws Exception {
		return MAPPER.writeValueAsString(o);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> check(Map<String, Object> report, String name) {
		for(Map<String, Object> c : (List<Map<String, Object>>) report.get("checks")) {
			if(name.equals(c.get("name"))) {
				return c;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> row(Map<String, Object> report, String objectId) {
		for(Map<String, Object> c : (List<Map<String, Object>>) report.get("connections")) {
			if(objectId.equals(c.get("objectId"))) {
				return c;
			}
		}
		return null;
	}

	private static String litellmKey() {
		String k = testProperties.getProperty(LlmTestGate.PROP_CONNECTION_API_KEY);
		if(k == null || k.isBlank()) {
			k = System.getenv("LITELLM_MASTER_KEY");
		}
		return (k != null && !k.isBlank()) ? k.trim() : LlmTestGate.DEFAULT_MASTER_KEY;
	}

	private long rowCount(String model) throws Exception {
		if(!ioContext.getDbUtil().haveTable(model)) {
			return -1L;
		}
		try(Connection con = ioContext.getDbUtil().getDataSource().getConnection(); Statement st = con.createStatement();
			ResultSet rs = st.executeQuery("select count(*) from " + ioContext.getDbUtil().getTableName(model))) {
			return rs.next() ? rs.getLong(1) : -1L;
		}
	}

	private Map<String, Long> counts() throws Exception {
		Map<String, Long> m = new LinkedHashMap<>();
		for(String model : new String[] { ModelNames.MODEL_CONNECTION, OlioModelNames.MODEL_CHAT_CONFIG, OlioModelNames.MODEL_CHAT_REQUEST,
			OlioModelNames.MODEL_OPENAI_REQUEST, ModelNames.MODEL_MEMORY, ModelNames.MODEL_DATA }) {
			m.put(model, rowCount(model));
		}
		return m;
	}

	/// The raw database row as JSON text - byte-identical comparison of everything persisted.
	private String rawRow(String model, long id) throws Exception {
		try(Connection con = ioContext.getDbUtil().getDataSource().getConnection();
			PreparedStatement st = con.prepareStatement("select row_to_json(t)::text from " + ioContext.getDbUtil().getTableName(model) + " t where id = ?")) {
			st.setLong(1, id);
			try(ResultSet rs = st.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private static void expectStatus(int status, Runnable r) {
		try {
			r.run();
			fail("Expected AdminMaintenanceException " + status);
		}
		catch(AdminMaintenanceException e) {
			assertEquals(e.getMessage(), status, e.getStatus());
		}
	}

	// ─────────────────────────────── deployment ───────────────────────────────

	@Test
	public void TestDeploymentReportAndRedaction() throws Exception {
		BaseRecord a = admin();
		Map<String, Object> rep = SystemCheckUtil.report(a, defaults(), true, false);
		logger.info("[syscheck] non-/System admin report: " + json(rep));

		Map<String, Object> db = check(rep, "db");
		assertNotNull(db);
		assertEquals("db must be ok: " + db, "ok", db.get("status"));

		Map<String, Object> sd = check(rep, "sd");
		assertEquals("sd on a closed port is optional -> warn: " + sd, "warn", sd.get("status"));
		assertEquals("unreachable", sd.get("reason"));
		assertEquals("default", sd.get("source"));
		assertEquals(Boolean.TRUE, sd.get("urlRedacted"));
		assertNull("a non-/System admin never sees a URL", ((Map<?, ?>) sd.get("target")).get("url"));
		assertEquals("skip", check(rep, "face").get("status"));
		assertEquals("not_configured", check(rep, "face").get("reason"));

		Map<String, Object> emb = check(rep, "embedding");
		assertNotNull(emb);
		assertEquals("live embedding probe: " + emb, "ok", emb.get("status"));
		assertEquals("live", emb.get("source"));
		assertEquals("ok", check(rep, "embedding.config").get("status"));
		assertNull("N1: no vectors row for a non-/System administrator", check(rep, "vectors"));

		/// Every row in the design's set except vectors.
		for(String n : SystemCheckUtil.DEPLOYMENT_CHECKS) {
			if(!"vectors".equals(n)) {
				assertNotNull("missing deployment row " + n, check(rep, n));
			}
		}
		String all = json(rep);
		assertFalse("no '://' anywhere in a non-/System admin report: " + all, all.contains("://"));
		assertEquals("degraded", rep.get("status"));
		assertNotNull(rep.get("propagation"));

		BaseRecord sys = systemOrgAdmin();
		Map<String, Object> srep = SystemCheckUtil.report(sys, defaults(), false, false);
		Map<String, Object> vec = check(srep, "vectors");
		assertNotNull("a /System administrator gets the vectors row", vec);
		assertEquals("ok", vec.get("status"));
		assertTrue("vectors counts derived from the registered vectorExt models: " + vec, vec.get("counts") instanceof Map && !((Map<?, ?>) vec.get("counts")).isEmpty());
		logger.info("[syscheck] vectors row: " + json(vec));
		Map<String, Object> ssd = check(srep, "sd");
		assertEquals(CLOSED_URL, ((Map<?, ?>) ssd.get("target")).get("url"));
		assertEquals(Boolean.FALSE, ssd.get("urlRedacted"));
	}

	@Test
	public void TestSummaryIsNamesOnlyAndOpenToPlainUsers() throws Exception {
		BaseRecord p = plain(PLAIN_A);
		Map<String, Object> s = SystemCheckUtil.summary(p, defaults());
		String js = json(s);
		logger.info("[syscheck] summary: " + js);
		assertEquals(3, s.size());
		assertTrue(s.containsKey("status") && s.containsKey("degraded") && s.containsKey("checkedAt"));
		for(Object n : (List<?>) s.get("degraded")) {
			assertTrue("summary names come only from the fixed set: " + n, SystemCheckUtil.SUMMARY_NAMES.contains(n));
		}
		assertTrue("sd (closed port) is degraded: " + js, ((List<?>) s.get("degraded")).contains("sd"));
		assertFalse(js.contains("://"));
		assertFalse(js.contains(SENTINEL_KEY));
		expectStatus(401, () -> SystemCheckUtil.summary(null, defaults()));
	}

	@Test
	public void TestPlainUserIsRefused() {
		BaseRecord p = plain(PLAIN_A);
		expectStatus(403, () -> SystemCheckUtil.report(p, defaults(), false, false));
		expectStatus(403, () -> SystemCheckUtil.report(p, defaults(), true, true));
		expectStatus(403, () -> SystemCheckUtil.connections(p));
		expectStatus(403, () -> SystemCheckUtil.testConnection(p, UUID.randomUUID().toString(), false));
		expectStatus(403, () -> SystemCheckUtil.testServer(p, defaults(), "db", false));
		expectStatus(401, () -> SystemCheckUtil.report(null, defaults(), false, false));
	}

	@Test
	public void TestServerWhitelistAndNoDeepForm() {
		BaseRecord a = admin();
		expectStatus(400, () -> SystemCheckUtil.testServer(a, defaults(), "http://evil", false));
		expectStatus(400, () -> SystemCheckUtil.testServer(a, defaults(), "llm", false));
		expectStatus(403, () -> SystemCheckUtil.testServer(a, defaults(), "vectors", false));
		for(String n : SystemCheckUtil.DEPLOYMENT_CHECKS) {
			if("vectors".equals(n)) {
				continue;
			}
			Map<String, Object> c = SystemCheckUtil.testServer(a, defaults(), n, false);
			assertEquals(n, c.get("name"));
			assertNotNull(n + " status", c.get("status"));
		}
		Map<String, Object> sd = SystemCheckUtil.testServer(a, defaults(), "sd", true);
		assertTrue("deep on a check with no deep form says so: " + sd.get("detail"), String.valueOf(sd.get("detail")).contains("no deep form"));
		assertEquals("probe", sd.get("depth"));
		Map<String, Object> db = SystemCheckUtil.testServer(a, defaults(), "db", false);
		assertEquals("ok", db.get("status"));
		Map<String, Object> vec = SystemCheckUtil.testServer(systemOrgAdmin(), defaults(), "vectors", false);
		assertEquals("ok", vec.get("status"));
	}

	// ─────────────────────────────── connections ───────────────────────────────

	@Test
	public void TestConnectionProbes() throws Exception {
		BaseRecord a = admin();
		BaseRecord ollama = conn(a, "ss-ollama-local", OLLAMA_URL, null, ConnectionDialectEnumType.OLLAMA);
		BaseRecord closed = conn(a, "ss-closed-port", CLOSED_URL, null, ConnectionDialectEnumType.OLLAMA);
		BaseRecord hole = conn(a, "ss-black-hole", BLACKHOLE_URL, null, ConnectionDialectEnumType.OLLAMA);
		BaseRecord emu = conn(a, "ss-emulator", "emulator://default", null, ConnectionDialectEnumType.EMULATOR);

		Map<String, Object> r = SystemCheckUtil.testConnection(a, ollama.get(FieldNames.FIELD_OBJECT_ID), false);
		logger.info("[syscheck] ollama: " + json(r));
		assertEquals("OLLAMA /api/tags: " + r, "ok", r.get("status"));
		assertTrue("models listed: " + r, ((Number) r.get("modelCount")).intValue() > 0);
		assertEquals("ollama", r.get("dialect"));

		long t0 = System.currentTimeMillis();
		r = SystemCheckUtil.testConnection(a, closed.get(FieldNames.FIELD_OBJECT_ID), false);
		logger.info("[syscheck] closed (" + (System.currentTimeMillis() - t0) + " ms): " + json(r));
		assertEquals("fail", r.get("status"));
		assertEquals("unreachable", r.get("reason"));

		t0 = System.currentTimeMillis();
		r = SystemCheckUtil.testConnection(a, hole.get(FieldNames.FIELD_OBJECT_ID), false);
		long holeMs = System.currentTimeMillis() - t0;
		logger.info("[syscheck] black hole (" + holeMs + " ms): " + json(r));
		assertEquals("fail", r.get("status"));
		assertEquals("timeout", r.get("reason"));
		assertTrue("black-hole probe must time out in under 7 s, took " + holeMs, holeMs < 7000L);

		r = SystemCheckUtil.testConnection(a, emu.get(FieldNames.FIELD_OBJECT_ID), false);
		assertEquals("skip", r.get("status"));
		assertEquals("emulator", r.get("reason"));

		/// A referencing chatConfig whose model is not installed -> warn model_missing.
		BaseRecord cfg = OlioTestUtil.getCreateChatConfig(a, "ss-missing-model",
			new ConnectionTarget(OLLAMA_URL, null, ConnectionDialectEnumType.OLLAMA, ConnectionUpstreamEnumType.OLLAMA, 120), "no-such-model:0b", 120, false);
		BaseRecord mconn = OlioTestUtil.readConnection(a, (long) ((BaseRecord) cfg.get("connection")).get(FieldNames.FIELD_ID));
		r = SystemCheckUtil.testConnection(a, mconn.get(FieldNames.FIELD_OBJECT_ID), false);
		logger.info("[syscheck] model missing: " + json(r));
		assertEquals("warn", r.get("status"));
		assertEquals("model_missing", r.get("reason"));
		assertTrue("referencedBy names the chatConfig: " + r, ((List<?>) r.get("referencedBy")).contains("ss-missing-model"));

		expectStatus(404, () -> SystemCheckUtil.testConnection(a, UUID.randomUUID().toString(), false));
	}

	@Test
	public void TestLiteLlmOkAndWrongKeyIsAuth() throws Exception {
		ConnectionProbeUtil.Probe live = ConnectionProbeUtil.get(LITELLM_URL + "/health/liveliness", null);
		Assume.assumeTrue("SKIPPED (not a pass): LiteLLM is not answering at " + LITELLM_URL, live.getHttpStatus() == 200);
		BaseRecord a = admin();
		BaseRecord good = conn(a, "ss-litellm", LITELLM_URL, litellmKey(), ConnectionDialectEnumType.OPENAI_COMPAT);
		BaseRecord bad = conn(a, "ss-litellm-badkey", LITELLM_URL, SENTINEL_KEY, ConnectionDialectEnumType.OPENAI_COMPAT);

		Map<String, Object> r = SystemCheckUtil.testConnection(a, good.get(FieldNames.FIELD_OBJECT_ID), false);
		logger.info("[syscheck] litellm: " + json(r));
		assertEquals("LiteLLM /v1/models with the key: " + r, "ok", r.get("status"));
		assertEquals(Boolean.TRUE, r.get("hasApiKey"));

		r = SystemCheckUtil.testConnection(a, bad.get(FieldNames.FIELD_OBJECT_ID), false);
		logger.info("[syscheck] litellm bad key: " + json(r));
		assertEquals("fail", r.get("status"));
		assertEquals("auth", r.get("reason"));
		assertEquals(Boolean.TRUE, r.get("hasApiKey"));
		String js = json(r);
		assertFalse("the apiKey never reaches a map", js.contains(SENTINEL_KEY));
		assertFalse("no URL for a non-/System admin", js.contains("://"));

		/// The full report served to this non-/System admin carries neither the sentinel nor any URL.
		Map<String, Object> rep = SystemCheckUtil.report(a, defaults(), true, false);
		String all = json(rep);
		assertNotNull("the sentinel connection is listed", row(rep, bad.get(FieldNames.FIELD_OBJECT_ID)));
		assertFalse("sentinel apiKey absent from the report", all.contains(SENTINEL_KEY));
		assertFalse("no '://' in the report: " + all, all.contains("://"));
		assertNull("N1", check(rep, "vectors"));
		String conns = json(SystemCheckUtil.connections(a));
		assertFalse(conns.contains(SENTINEL_KEY));
		assertFalse(conns.contains("://"));
		String sum = json(SystemCheckUtil.summary(a, defaults()));
		assertFalse(sum.contains(SENTINEL_KEY));
		assertFalse(sum.contains("://"));
	}

	@Test
	public void TestTwoUserCanReadFilter() throws Exception {
		BaseRecord ua = plain(PLAIN_A);
		BaseRecord ub = plain(PLAIN_B);
		BaseRecord mine = conn(ua, "ss-plain-one-conn", OLLAMA_URL, null, ConnectionDialectEnumType.OLLAMA);
		BaseRecord theirs = conn(ub, "ss-plain-two-private", OLLAMA_URL, null, ConnectionDialectEnumType.OLLAMA);
		long theirId = theirs.get(FieldNames.FIELD_ID);

		/// The setup must be capable of showing the bug: the raw AccessPoint.list (query-shape authorization
		/// only) must return B's record to A. If it did not, the filter assertion below would prove nothing.
		Query q = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, ua.get(FieldNames.FIELD_ORGANIZATION_ID));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID });
		q.setCache(false);
		q.setRequestRange(0L, 1000);
		QueryResult qr = ioContext.getAccessPoint().list(ua, q);
		boolean rawHasTheirs = false;
		if(qr != null && qr.getResults() != null) {
			for(BaseRecord r : qr.getResults()) {
				if((long) r.get(FieldNames.FIELD_ID) == theirId) {
					rawHasTheirs = true;
				}
			}
		}
		logger.info("[syscheck] raw AccessPoint.list returned B's private connection to A: " + rawHasTheirs);

		List<BaseRecord> readable = SystemCheckUtil.listReadable(ua, ModelNames.MODEL_CONNECTION, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_ORGANIZATION_ID });
		List<Long> ids = new ArrayList<>();
		for(BaseRecord r : readable) {
			ids.add(r.get(FieldNames.FIELD_ID));
		}
		assertTrue("A's own connection is readable", ids.contains((long) mine.get(FieldNames.FIELD_ID)));
		assertFalse("B's private connection must be absent from A's list", ids.contains(theirId));
		assertTrue("precondition: the raw list must have returned B's record, or this test cannot show the defect", rawHasTheirs);
	}

	@Test
	public void TestEightParallelBlackHolesUnderEightSeconds() throws Exception {
		OrganizationContext o = org(ORG_PAR);
		BaseRecord pa = withRole(o, PAR_ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
		List<String> oids = new ArrayList<>();
		for(int i = 1; i <= 8; i++) {
			BaseRecord c = conn(pa, "par-black-hole-" + i, "http://192.0.2.1:" + (11000 + i), null, ConnectionDialectEnumType.OLLAMA);
			oids.add(c.get(FieldNames.FIELD_OBJECT_ID));
		}
		/// Warm the shared deployment section so the timed call measures only the connection fan-out.
		SystemCheckUtil.summary(pa, defaults());
		SystemCheckUtil.invalidateUser((long) pa.get(FieldNames.FIELD_ID));
		long t0 = System.currentTimeMillis();
		Map<String, Object> rep = SystemCheckUtil.report(pa, defaults(), false, false);
		long ms = System.currentTimeMillis() - t0;
		logger.info("[syscheck] 8 parallel black holes: " + ms + " ms");
		int timedOut = 0;
		for(String oid : oids) {
			Map<String, Object> r = row(rep, oid);
			assertNotNull("row for " + oid, r);
			if("timeout".equals(r.get("reason"))) {
				timedOut++;
			}
			assertEquals("fail", r.get("status"));
		}
		assertEquals("all 8 time out", 8, timedOut);
		assertTrue("8 parallel black-hole probes must finish under 8 s, took " + ms, ms < 8000L);
	}

	// ─────────────────────────────── deep ───────────────────────────────

	@Test
	public void TestDeepEmbedding() throws Exception {
		BaseRecord a = admin();
		EmbeddingUtil live = ioContext.getVectorUtil().getEmbedUtil();
		Assume.assumeTrue("SKIPPED (not a pass): no live embedding", live != null);
		EmbeddingUtil.Endpoint ep = live.getEndpoint();
		String model = live.getEmbeddingModel();
		int dims = live.getEmbeddingDimensions();
		int observed = live.getObservedEmbeddingWidth();
		LLMServiceEnumType type = live.getServiceType();

		Map<String, Object> r = SystemCheckUtil.deepEmbedding(a, live, ep.getServerUrl(), ep.getAuthorizationToken());
		logger.info("[syscheck] deep embedding (" + type + "): " + json(r));
		assertEquals("deep embedding: " + r, "ok", r.get("status"));
		assertEquals(768, ((Number) r.get("width")).intValue());
		assertEquals(Boolean.FALSE, r.get("bounded"));
		assertEquals("deep", r.get("depth"));

		/// Production path (URL/token resolved from ServerConfigUtil, live as the fallback).
		Map<String, Object> s = SystemCheckUtil.testServer(a, defaults(), "embedding", true);
		assertEquals("ok", s.get("status"));
		assertFalse(json(s).contains("://"));

		/// No setter on the live instance: the endpoint holder is the very same object, model/dims/width
		/// baseline unchanged.
		assertSame("setEndpoint/setServerUrl/setAuthorizationToken must never be called on the live instance", ep, live.getEndpoint());
		assertEquals(model, live.getEmbeddingModel());
		assertEquals(dims, live.getEmbeddingDimensions());
		assertEquals(observed, live.getObservedEmbeddingWidth());
		assertSame(type, live.getServiceType());

		/// Mismatched type: an OpenAI-shaped client against the LOCAL host:port service.
		EmbeddingUtil wrong = new EmbeddingUtil(type == LLMServiceEnumType.LOCAL ? LLMServiceEnumType.OPENAI_COMPAT : LLMServiceEnumType.LOCAL, ep.getServerUrl(), null);
		wrong.setEmbeddingDimensions(dims);
		Map<String, Object> w = SystemCheckUtil.deepEmbedding(a, wrong, ep.getServerUrl(), null);
		logger.info("[syscheck] deep embedding, mismatched type: " + json(w));
		assertEquals("fail", w.get("status"));
	}

	@Test
	public void TestDeepChatWritesNothingAndConcurrentDeepIs409() throws Exception {
		BaseRecord a = admin();
		String model = OlioTestUtil.analysisModel(testProperties);
		Assume.assumeTrue("SKIPPED (not a pass): no analysis model", model != null);
		logger.info("[syscheck] LLM route=" + testProperties.getProperty(LlmTestGate.PROP_ROUTE) + " tier=" + testProperties.getProperty(LlmTestGate.PROP_RESOLVED_TIER) + " model=" + model);
		BaseRecord cfg = OlioTestUtil.getCreateChatConfig(a, "ss-deep-chat", ConnectionTarget.fromProperties(testProperties, 120), model, 120, false);
		assertNotNull(cfg);
		long cfgId = cfg.get(FieldNames.FIELD_ID);

		/// Non-zero counters, and lastMemoryExtractionAt larger than any deep-chat message count: exactly the
		/// state in which Chat's stale-marker reset (Chat.java checkMemoryExtractionTrigger) would write 0.
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			"lastKeyframeAt", "lastMemoryExtractionAt", "lastInteractionAt", "extractMemories", "memoryExtractionEvery" });
		patch.set(FieldNames.FIELD_ID, cfgId);
		patch.set(FieldNames.FIELD_OBJECT_ID, cfg.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, "ss-deep-chat");
		patch.set("lastKeyframeAt", 3);
		patch.set("lastMemoryExtractionAt", 99);
		patch.set("lastInteractionAt", 4);
		patch.set("extractMemories", true);
		patch.set("memoryExtractionEvery", 2);
		assertNotNull("counter patch", ioContext.getAccessPoint().update(a, patch));
		String before = rawRow(OlioModelNames.MODEL_CHAT_CONFIG, cfgId);
		assertNotNull(before);
		assertTrue(before, before.contains("99"));

		BaseRecord llmConn = OlioTestUtil.readConnection(a, (long) ((BaseRecord) cfg.get("connection")).get(FieldNames.FIELD_ID));
		String llmOid = llmConn.get(FieldNames.FIELD_OBJECT_ID);

		/// Black-hole connection with a referencing config, so its deep chat holds the executor for seconds.
		BaseRecord holeCfg = OlioTestUtil.getCreateChatConfig(a, "ss-deep-hole",
			new ConnectionTarget(BLACKHOLE_URL, null, ConnectionDialectEnumType.OLLAMA, ConnectionUpstreamEnumType.OLLAMA, 10), "qwen3:8b", 10, false);
		BaseRecord holeConn = OlioTestUtil.readConnection(a, (long) ((BaseRecord) holeCfg.get("connection")).get(FieldNames.FIELD_ID));

		Map<String, Long> countsBefore = counts();

		Map<String, Object> r = SystemCheckUtil.testConnection(a, llmOid, true);
		logger.info("[syscheck] deep chat: " + json(r));
		assertEquals("deep chat: " + r, "ok", r.get("status"));
		assertEquals("deep", r.get("depth"));
		String js = json(r);
		assertFalse(js.contains("://"));

		/// Fresh, uncached re-read: byte-identical row.
		String after = rawRow(OlioModelNames.MODEL_CHAT_CONFIG, cfgId);
		assertEquals("B1: the referencing chatConfig must be byte-for-byte unchanged after a deep chat", before, after);
		Query fq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_ID, cfgId);
		fq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID, "lastKeyframeAt", "lastMemoryExtractionAt", "lastInteractionAt" });
		fq.setCache(false);
		BaseRecord fresh = ioContext.getAccessPoint().find(a, fq);
		assertEquals(99, (int) fresh.get("lastMemoryExtractionAt"));
		assertEquals(3, (int) fresh.get("lastKeyframeAt"));
		assertEquals(4, (int) fresh.get("lastInteractionAt"));

		/// Concurrent deep: the first holds the deep-chat executor; the second is refused 409.
		AtomicReference<Object> first = new AtomicReference<>();
		Thread t = new Thread(() -> {
			try {
				first.set(SystemCheckUtil.testConnection(a, holeConn.get(FieldNames.FIELD_OBJECT_ID), true));
			}
			catch(Throwable e) {
				first.set(e);
			}
		});
		t.start();
		long wait = System.currentTimeMillis() + 10000L;
		while(!SystemCheckUtil.isDeepChatBusy() && System.currentTimeMillis() < wait) {
			Thread.sleep(20L);
		}
		assertTrue("the first deep chat must be running", SystemCheckUtil.isDeepChatBusy());
		expectStatus(409, () -> SystemCheckUtil.testConnection(a, llmOid, true));
		expectStatus(409, () -> SystemCheckUtil.report(a, defaults(), true, true));
		t.join(60000L);
		logger.info("[syscheck] first (black-hole) deep chat: " + (first.get() instanceof Map ? json(first.get()) : String.valueOf(first.get())));
		assertTrue("first deep chat returned a result", first.get() instanceof Map);
		assertEquals("fail", ((Map<?, ?>) first.get()).get("status"));
		long until = System.currentTimeMillis() + 40000L;
		while(SystemCheckUtil.isDeepChatBusy() && System.currentTimeMillis() < until) {
			Thread.sleep(100L);
		}
		assertFalse("the deep-chat flag is released when the worker ends", SystemCheckUtil.isDeepChatBusy());

		/// A full deep run in the parallel org (8 black holes -> fast probes fail -> no deep chats there;
		/// the deep embedding still runs), then the record counts.
		OrganizationContext po = org(ORG_PAR);
		BaseRecord pa = withRole(po, PAR_ADMIN, AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR);
		Map<String, Object> full = SystemCheckUtil.report(pa, defaults(), true, true);
		Map<String, Object> demb = check(full, "embedding");
		logger.info("[syscheck] full deep run embedding row: " + json(demb));
		assertEquals("deep", demb.get("depth"));
		assertEquals(Boolean.FALSE, demb.get("bounded"));

		Map<String, Long> countsAfter = counts();
		assertEquals("diagnostics persist nothing (record counts unchanged)", countsBefore, countsAfter);
		assertEquals(before, rawRow(OlioModelNames.MODEL_CHAT_CONFIG, cfgId));
	}
}
