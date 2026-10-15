package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.AccessSchema;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.accountmanager.util.FeatureConfigUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.FeatureConfigService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// Component test for the feature-config REST shim, exercised through the same hand-rolled
/// HttpServletRequestMock + UserPrincipal pattern as TestISO42001Service - i.e. the real service
/// methods, the real Objects7 resolver, and the real database, without the HTTP transport.
///
/// WHAT THIS REPLACED (recorded so it is not reintroduced). The previous version of this file had four
/// tests, three of which asserted nothing about the product:
///   - TestGetDefaultConfig      declared its own 11-element List literal and asserted things about
///                               that literal. It touched no production symbol, and its "count" guard
///                               had itself drifted (11 asserted, 12 in the service, 13 in the manifest).
///   - TestFeatureValidation     declared a List literal and asserted contains()/!contains() on it.
///   - TestCoreAlwaysIncluded    re-implemented "insert core at 0" in the test body and asserted its
///                               own arithmetic.
///   - TestConfigRecordCRUD      did hit the database, but ran entirely as orgContext.getAdminUser()
///                               (a hard prohibition, .claude/rules/llm-conduct.md rule 3) and targeted
///                               the pre-D1 storage (a record in the caller's HOME directory with the
///                               payload in `description`), which no longer exists. Its
///                               assertNotNull on a re-read `description` proved only that a string
///                               field round-trips, not that any feature configuration worked.
///
/// USERS. Two non-admin users in /Development. The organization admin appears only as a setup actor
/// (creating users and granting a role - both inherently admin acts) and is never an assertion subject.
///
/// SCOPE LIMIT, STATED PLAINLY. Calling the service method in-process does NOT run the container's
/// @RolesAllowed({"admin"}) check on PUT - that is a Jersey/JAAS concern and is verified against the
/// live stack (AccountManagerUx752/e2e/featureConfig.spec.js). What this file does verify is the
/// marshaling, the status codes, the profile labelling, the validation branches, and - separately -
/// that PBAC independently refuses a plain user's write even if the role gate were bypassed.
public class TestFeatureConfigService extends BaseTest {

	private static final String WRITER_NAME = "featurecfgadmin";
	private static final String READER_NAME = "featurecfgreader";

	private final FeatureConfigService service = new FeatureConfigService();

	private BaseRecord writer;
	private BaseRecord reader;
	private long orgId;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		orgId = orgContext.getOrganizationId();

		/// $minLen5 is "contains five CONSECUTIVE alphanumerics" (Matcher.find on [A-Za-z0-9]{5}), and
		/// Factory.getCreateUser returns null for a name that fails it - hence the assertNotNull.
		writer = getCreateUser(WRITER_NAME);
		assertNotNull("Writer user is null", writer);
		reader = getCreateUser(READER_NAME);
		assertNotNull("Reader user is null", reader);

		/// The writer is a member of AccountAdministrators, which is what Service7's
		/// WEB-INF/resource/roleMap.json maps the JAAS role "admin" onto. It is NOT the admin user.
		BaseRecord adminRole = AccessSchema.getSystemRole(AccessSchema.ROLE_ACCOUNT_ADMINISTRATOR,
			RoleEnumType.USER.toString(), orgId);
		assertNotNull("AccountAdministrators role is null", adminRole);
		ioContext.getMemberUtil().member(orgContext.getAdminUser(), adminRole, writer, null, true);

		FeatureConfigUtil.invalidate(orgId);
	}

	private HttpServletRequestMock requestAs(BaseRecord user) {
		return new HttpServletRequestMock(new UserPrincipal((String)user.get(FieldNames.FIELD_NAME), organizationPath));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> body(Response r) {
		assertNotNull("Response is null", r);
		Object entity = r.getEntity();
		assertNotNull("Response entity is null", entity);
		Map<String, Object> m = JSONUtil.importObject(entity.toString(), LinkedHashMap.class);
		assertNotNull("Response body did not parse as JSON: " + entity, m);
		return m;
	}

	@SuppressWarnings("unchecked")
	private List<String> featuresOf(Response r) {
		Object f = body(r).get("features");
		assertTrue("Response has no 'features' array", f instanceof List);
		List<String> out = new ArrayList<>();
		for(Object o : (List<Object>)f) {
			out.add(String.valueOf(o));
		}
		return out;
	}

	private String putJson(List<String> ids) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("features", ids);
		return JSONUtil.exportObject(m);
	}

	/// Remove the org's stored record so the "no record" branch can be tested. Deleted as the
	/// admin-role writer, who owns it.
	private void clearStoredConfig() {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(writer, ModelNames.MODEL_GROUP,
			FeatureConfigUtil.LIBRARY_PATH_CONFIGURATION, GroupEnumType.DATA.toString(), orgId);
		if(dir != null) {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, FeatureConfigUtil.CONFIG_NAME);
			q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(writer, q);
			if(rec != null) {
				assertTrue("Failed to delete the stored feature configuration",
					IOSystem.getActiveContext().getAccessPoint().delete(writer, rec));
			}
		}
		FeatureConfigUtil.invalidate(orgId);
	}

	/// ------------------------------------------------------------------------------------------

	@Test
	public void TestNoPrincipalReturns401() {
		assertEquals("GET /features with no principal must be 401", 401,
			service.getFeatureConfig(new HttpServletRequestMock()).getStatus());
		assertEquals("GET /features/available with no principal must be 401", 401,
			service.getAvailableFeatures(new HttpServletRequestMock()).getStatus());
		assertEquals("PUT /features with no principal must be 401", 401,
			service.updateFeatureConfig(putJson(Arrays.asList("core")), new HttpServletRequestMock()).getStatus());
	}

	/// Replaces the old list-literal TestGetDefaultConfig: with no stored record, GET must return the
	/// manifest-derived default set and label it "full" - asserted against the production accessor,
	/// never against a literal declared here.
	@Test
	public void TestGetDefaultConfig() {
		/// Make sure /Library/Configuration and its grants exist, then remove the record itself.
		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("core")));
		clearStoredConfig();

		Response r = service.getFeatureConfig(requestAs(reader));
		assertEquals("GET /features should be 200 for an authenticated non-admin", 200, r.getStatus());
		assertEquals("With no stored record the service must return the manifest default profile",
			FeatureConfigUtil.getDefaultFeatures(), featuresOf(r));
		assertEquals("The default set must be labelled 'full'", "full", body(r).get("profile"));
		assertTrue("'media' must be in the default set - it is the id that rotted out of the old server list",
			featuresOf(r).contains("media"));
	}

	/// Replaces the old admin-user, home-directory, `description`-payload TestConfigRecordCRUD.
	/// This is the D1 assertion at the transport layer: one user's PUT is visible to a DIFFERENT user's GET.
	@Test
	public void TestPutIsVisibleToAnotherUsersGet() {
		List<String> reduced = Arrays.asList("core", "media", "chat");
		List<String> expected = FeatureConfigUtil.resolveFeatures(reduced);
		assertFalse("The reduced set must differ from the default profile or this test is vacuous",
			expected.equals(FeatureConfigUtil.getDefaultFeatures()));

		Response put = service.updateFeatureConfig(putJson(reduced), requestAs(writer));
		assertEquals("PUT /features should be 200, body: " + put.getEntity(), 200, put.getStatus());
		assertEquals("PUT must echo what was actually stored", expected, featuresOf(put));
		assertEquals("A reduced set must be labelled 'custom'", "custom", body(put).get("profile"));

		/// The OTHER user - who never wrote anything - must see exactly that set.
		Response get = service.getFeatureConfig(requestAs(reader));
		assertEquals(200, get.getStatus());
		assertEquals("The second user's GET did not return the organization's saved set",
			expected, featuresOf(get));
		assertEquals("custom", body(get).get("profile"));

		/// Round trip back to the full set, and the label flips back.
		Response full = service.updateFeatureConfig(putJson(FeatureConfigUtil.getManifestIds()), requestAs(writer));
		assertEquals(200, full.getStatus());
		assertEquals(FeatureConfigUtil.getDefaultFeatures(), featuresOf(full));
		assertEquals("full", body(full).get("profile"));
		assertEquals("The second user must see the restored full set",
			FeatureConfigUtil.getDefaultFeatures(), featuresOf(service.getFeatureConfig(requestAs(reader))));
	}

	/// Replaces the old list-literal TestFeatureValidation: exercises the real 400 branches and proves a
	/// rejected PUT does not mutate what is stored.
	@Test
	public void TestFeatureValidation() {
		List<String> good = Arrays.asList("core", "games");
		List<String> expected = FeatureConfigUtil.resolveFeatures(good);
		assertEquals(200, service.updateFeatureConfig(putJson(good), requestAs(writer)).getStatus());
		assertEquals(expected, featuresOf(service.getFeatureConfig(requestAs(reader))));

		Response unknown = service.updateFeatureConfig(putJson(Arrays.asList("core", "bogusFeature")), requestAs(writer));
		assertEquals("An unknown feature id must be rejected with 400", 400, unknown.getStatus());
		assertTrue("The 400 body should name the offending id, got: " + unknown.getEntity(),
			unknown.getEntity().toString().contains("bogusFeature"));

		assertEquals("An empty body must be rejected with 400", 400,
			service.updateFeatureConfig("", requestAs(writer)).getStatus());
		assertEquals("A null body must be rejected with 400", 400,
			service.updateFeatureConfig(null, requestAs(writer)).getStatus());
		assertEquals("Unparsable JSON must be rejected with 400", 400,
			service.updateFeatureConfig("{not json", requestAs(writer)).getStatus());
		assertEquals("A body with no 'features' array must be rejected with 400", 400,
			service.updateFeatureConfig("{\"profile\":\"minimal\"}", requestAs(writer)).getStatus());
		assertEquals("A 'features' value that is not an array must be rejected with 400", 400,
			service.updateFeatureConfig("{\"features\":\"core\"}", requestAs(writer)).getStatus());

		/// None of the rejected calls may have changed the stored configuration.
		FeatureConfigUtil.invalidate(orgId);
		assertEquals("A rejected PUT must not mutate the stored configuration",
			expected, featuresOf(service.getFeatureConfig(requestAs(reader))));
	}

	/// The 400 body must be SERIALIZED, not concatenated. Hand-building
	/// `"{\"error\":\"Unknown feature IDs: " + join(ids) + "\"}"` emits malformed JSON the moment an id
	/// carries a quote, a backslash or a newline: the client's JSON.parse then throws and a precise 400
	/// degrades into a generic "Failed to save" with the reason lost. body() parses the entity, so it fails
	/// outright on an unparsable body - which is exactly what the old concatenation produced here.
	@Test
	public void TestUnknownIdErrorBodyIsValidJson() {
		String nasty = "bo\"gus\\Feature\nid";
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("features", Arrays.asList("core", nasty));

		Response r = service.updateFeatureConfig(JSONUtil.exportObject(m), requestAs(writer));
		assertEquals("An unknown feature id must be rejected with 400, body: " + r.getEntity(), 400, r.getStatus());

		/// The load-bearing assertion: the body PARSES, and keeps the documented {"error":"..."} shape.
		Map<String, Object> parsed = body(r);
		assertNotNull("The 400 body must carry an 'error' key: " + r.getEntity(), parsed.get("error"));
		assertTrue("The message text must be unchanged, got: " + parsed.get("error"),
			String.valueOf(parsed.get("error")).startsWith("Unknown feature IDs: "));
		assertTrue("The offending id must survive escaping intact, got: " + parsed.get("error"),
			String.valueOf(parsed.get("error")).contains(nasty));
	}

	/// Replaces the old TestCoreAlwaysIncluded, which asserted its own in-test arithmetic.
	/// Here the id list genuinely omits core and the service/resolver must put it back - on the PUT
	/// echo, in what is persisted, and on a different user's GET.
	@Test
	public void TestCoreAlwaysIncluded() {
		List<String> noCore = Arrays.asList("games");
		assertFalse("The input must not contain core", noCore.contains("core"));

		Response put = service.updateFeatureConfig(putJson(noCore), requestAs(writer));
		assertEquals("PUT should be 200, body: " + put.getEntity(), 200, put.getStatus());
		assertTrue("core must be force-included in the PUT echo", featuresOf(put).contains("core"));
		assertEquals("core is first in manifest order", "core", featuresOf(put).get(0));

		assertTrue("core must be force-included for another user's GET",
			featuresOf(service.getFeatureConfig(requestAs(reader))).contains("core"));

		/// deps closure, same call path: cardGame pulls in chat.
		Response deps = service.updateFeatureConfig(putJson(Arrays.asList("cardGame")), requestAs(writer));
		assertEquals(200, deps.getStatus());
		assertEquals("cardGame must close to core, chat, cardGame in manifest order",
			Arrays.asList("core", "chat", "cardGame"), featuresOf(deps));
	}

	/// GET /features/available must serve the Objects7 manifest resource verbatim - it is the single
	/// source of truth the client mirrors in src/features.manifest.json.
	@Test
	public void TestAvailableFeaturesServesManifestVerbatim() {
		Response r = service.getAvailableFeatures(requestAs(reader));
		assertEquals(200, r.getStatus());
		String served = r.getEntity().toString();
		assertEquals("GET /features/available must be the manifest resource, byte for byte",
			FeatureConfigUtil.getManifestJson(), served);

		List<Map<String, Object>> entries = JSONUtil.getList(served, LinkedHashMap.class, null);
		assertNotNull("The served manifest did not parse as an array", entries);
		assertEquals("The served manifest must carry every manifest id",
			FeatureConfigUtil.getManifestIds().size(), entries.size());
		List<String> ids = new ArrayList<>();
		for(Map<String, Object> e : entries) {
			ids.add(String.valueOf(e.get("id")));
			assertNotNull("Manifest entry has no label: " + e, e.get("label"));
			assertTrue("Manifest entry has no deps array: " + e, e.get("deps") instanceof List);
		}
		assertEquals("The served id set/order must match getManifestIds()",
			FeatureConfigUtil.getManifestIds(), ids);
		assertTrue("'media' must be served", ids.contains("media"));
	}

	/// Defense in depth. The container's @RolesAllowed({"admin"}) is the primary write gate and is not
	/// evaluated by an in-process call, so this asserts the SECOND gate: even reaching the service
	/// method, a plain AccountUsers member's write is refused by PBAC and nothing is stored.
	@Test
	public void TestPlainUserPutIsRefusedByPbac() {
		List<String> stored = Arrays.asList("core", "games");
		List<String> expected = FeatureConfigUtil.resolveFeatures(stored);
		assertEquals(200, service.updateFeatureConfig(putJson(stored), requestAs(writer)).getStatus());

		Response r = service.updateFeatureConfig(putJson(Arrays.asList("core", "chat", "cardGame")), requestAs(reader));
		assertEquals("A plain non-admin's PUT must fail (500 from the refused write), got " + r.getStatus()
			+ " body: " + r.getEntity(), 500, r.getStatus());

		FeatureConfigUtil.invalidate(orgId);
		assertEquals("A refused PUT must not mutate the stored configuration",
			expected, featuresOf(service.getFeatureConfig(requestAs(reader))));
	}

	/// The pre-D1 storage location, exercised for real: a record in the caller's own home directory is
	/// not what the resolver reads, so it cannot affect anyone - which is the bug D1 fixed.
	@Test
	public void TestLegacyHomeDirectoryRecordIsIgnored() throws Exception {
		assertTrue("Setup write failed", FeatureConfigUtil.setEnabledFeatures(writer, Arrays.asList("core")));
		clearStoredConfig();

		String homePath = writer.get("homeDirectory.path");
		assertNotNull("Writer has no home directory path", homePath);
		BaseRecord homeDir = IOSystem.getActiveContext().getPathUtil().findPath(writer, ModelNames.MODEL_GROUP,
			homePath, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("Writer home directory not found: " + homePath, homeDir);

		Map<String, Object> cfg = new LinkedHashMap<>();
		cfg.put("features", Arrays.asList("core", "chat"));
		BaseRecord legacy = newData(writer, FeatureConfigUtil.CONFIG_NAME, FeatureConfigUtil.CONTENT_TYPE,
			JSONUtil.exportObject(cfg).getBytes("UTF-8"), homePath, orgId);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(writer, legacy);
		assertNotNull("Failed to create the legacy per-user record", created);
		FeatureConfigUtil.invalidate(orgId);

		try {
			assertEquals("A per-user home-directory record must be ignored - GET must fall back to the default profile",
				FeatureConfigUtil.getDefaultFeatures(), featuresOf(service.getFeatureConfig(requestAs(reader))));
			assertEquals("...and must be ignored for its own author too",
				FeatureConfigUtil.getDefaultFeatures(), featuresOf(service.getFeatureConfig(requestAs(writer))));
		}
		finally {
			IOSystem.getActiveContext().getAccessPoint().delete(writer, created);
		}
	}

	/// P1-8: the read-only render-path route is principal-gated like its siblings.
	@Test
	public void TestRenderPathRequiresPrincipal() {
		assertEquals("GET /render-path with no principal must be 401", 401,
			service.getRenderPath(new HttpServletRequestMock()).getStatus());
	}

	/// P1-8: GET /render-path reports the process-global switch for every product, as the resolver holds
	/// it - a plain (non-admin) user may read it, and the body follows the resolver when it changes.
	/// The switch is restored in finally because it is process-global and other classes in this JVM
	/// (TestPbRenderPathRefusal) depend on it. P2-7 adds the chat value (default v1).
	@Test
	public void TestRenderPathReportsBothProducts() {
		String pb0 = PbRenderPath.pictureBook();
		String cb0 = PbRenderPath.chapBook();
		String ch0 = PbRenderPath.chat();
		try {
			Response r = service.getRenderPath(requestAs(reader));
			assertEquals(200, r.getStatus());
			Map<String, Object> b = body(r);
			assertEquals("picturebook must echo the resolver", pb0, b.get("picturebook"));
			assertEquals("chapbook must echo the resolver", cb0, b.get("chapbook"));
			assertTrue("picturebook must be v1 or v3", PbRenderPath.V1.equals(b.get("picturebook")) || PbRenderPath.V3.equals(b.get("picturebook")));
			assertTrue("chapbook must be v1 or v3", PbRenderPath.V1.equals(b.get("chapbook")) || PbRenderPath.V3.equals(b.get("chapbook")));
			assertEquals("chat must echo the resolver", ch0, b.get("chat"));
			assertTrue("chat must be v1 or v3", PbRenderPath.V1.equals(b.get("chat")) || PbRenderPath.V3.equals(b.get("chat")));

			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V1);
			Map<String, Object> b2 = body(service.getRenderPath(requestAs(reader)));
			assertEquals(PbRenderPath.V3, b2.get("picturebook"));
			assertEquals(PbRenderPath.V1, b2.get("chapbook"));
			assertEquals("the two-value configure must leave chat untouched", ch0, b2.get("chat"));

			/// P2-7: the chat value is its own product; absent / unrecognised pin ITS default, v1.
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3, PbRenderPath.V3);
			assertEquals(PbRenderPath.V3, body(service.getRenderPath(requestAs(reader))).get("chat"));
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3, "bogus");
			assertEquals("unrecognised chat must read back as its default v1", PbRenderPath.V1, body(service.getRenderPath(requestAs(reader))).get("chat"));
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3, PbRenderPath.V3);
			PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3, null);
			assertEquals("absent chat must read back as its default v1", PbRenderPath.V1, body(service.getRenderPath(requestAs(reader))).get("chat"));

			/// P2-6 (§28.1 Stage 2): absent / unrecognised pin the PRODUCT default - v1 for PictureBook, v3 for ChapBook.
			PbRenderPath.configure(null, "bogus");
			Map<String, Object> b3 = body(service.getRenderPath(requestAs(reader)));
			assertEquals("absent picturebook must read back as its default v1", PbRenderPath.V1, b3.get("picturebook"));
			assertEquals("unrecognised chapbook must read back as its default v3", PbRenderPath.V3, b3.get("chapbook"));
			PbRenderPath.configure("bogus", null);
			Map<String, Object> b4 = body(service.getRenderPath(requestAs(reader)));
			assertEquals("unrecognised picturebook must read back as its default v1", PbRenderPath.V1, b4.get("picturebook"));
			assertEquals("absent chapbook must read back as its default v3", PbRenderPath.V3, b4.get("chapbook"));
		}
		finally {
			PbRenderPath.configure(pb0, cb0, ch0);
		}
	}

	/// The record really is where D1 says it is: /Library/Configuration/.featureConfig, not a home dir.
	@Test
	public void TestStorageLocationIsOrgLibraryConfiguration() {
		assertEquals(200, service.updateFeatureConfig(putJson(Arrays.asList("core", "schema")), requestAs(writer)).getStatus());

		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(reader, ModelNames.MODEL_GROUP,
			FeatureConfigUtil.LIBRARY_PATH_CONFIGURATION, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("/Library/Configuration does not exist after a PUT", dir);

		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, FeatureConfigUtil.CONFIG_NAME);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		/// The READER resolves it - proving the AccountUsers read grant, not just the writer's ownership.
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(reader, q);
		assertNotNull("The non-admin reader cannot see /Library/Configuration/.featureConfig", rec);
		assertEquals("The stored record must carry contentType application/json",
			FeatureConfigUtil.CONTENT_TYPE, rec.get(FieldNames.FIELD_CONTENT_TYPE));

		/// And it is NOT in the writer's home directory.
		String homePath = writer.get("homeDirectory.path");
		BaseRecord homeDir = IOSystem.getActiveContext().getPathUtil().findPath(writer, ModelNames.MODEL_GROUP,
			homePath, GroupEnumType.DATA.toString(), orgId);
		if(homeDir != null) {
			Query hq = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_NAME, FeatureConfigUtil.CONFIG_NAME);
			hq.field(FieldNames.FIELD_GROUP_ID, homeDir.get(FieldNames.FIELD_ID));
			hq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			assertNull("A PUT must not write a .featureConfig into the caller's home directory (the pre-D1 bug)",
				IOSystem.getActiveContext().getAccessPoint().find(writer, hq));
		}
	}
}
