package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.iso42001.schema.ISO42001Provisioning;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.RoleEnumType;
import org.cote.rest.services.ISO42001Service;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.core.Response;

/**
 * Phase-7 component test for the ISO 42001 REST shim ({@link ISO42001Service}), exercised through a hand-rolled
 * {@link HttpServletRequestMock} (no mocking framework) against the live {@code am7isotestdb} — proving the
 * shim's marshaling/auth/serialization + the RBAC boundary without the HTTP transport (that is the live-Tomcat
 * {@code RestIntegrationTest}). The substantive engine/factory logic is already covered in Track A.
 *
 * <p>Standing rules honored: org {@code /ISO42001}; admin only creates the role users + assigns roles; every
 * asserted call runs as a non-admin role user; a negative RBAC case (isoReader create denied at the boundary)
 * is included.</p>
 *
 * <p>Pointed at the adjacent ISO module's {@code ./am7} file base so the {@code /ISO42001} org keystores/streams
 * created in Phases 1–6 resolve (the org lives in {@code am7isotestdb}; keys are module-local there).</p>
 */
public class TestISO42001Service extends BaseTest {

	private static Properties testProps = null;
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private final ISO42001Service service = new ISO42001Service();

	private OrganizationContext isoOrg;
	private long orgId;
	private BaseRecord adminUser;
	private BaseRecord isoTester;
	private BaseRecord isoReader;
	private long sharedGroupId;

	@Override
	@Before
	public void setup() {
		if (testProps == null) {
			testProps = new Properties();
			try (InputStream fis = ClassLoader.getSystemResourceAsStream("./resource.properties")) {
				testProps.load(fis);
			}
			catch (IOException e) {
				throw new RuntimeException("Failed to load resource.properties", e);
			}
		}
		/// Point at the adjacent ISO module's am7 so the /ISO42001 org keystores (built in Phases 1-6,
		/// module-local to am7isotestdb) resolve — the Agent7 BaseTest adjacent-module pattern.
		IOFactory.DEFAULT_FILE_BASE = "../AccountManagerISO42001/am7";
		OlioModelNames.use();
		ISO42001ModelNames.use();
		organizationPath = "/ISO42001";

		IOProperties props = new IOProperties();
		props.setDataSourceUrl(testProps.getProperty("test.db.url"));
		props.setDataSourceUserName(testProps.getProperty("test.db.user"));
		props.setDataSourcePassword(testProps.getProperty("test.db.password"));
		props.setSchemaCheck(true);
		props.setReset(Boolean.parseBoolean(testProps.getProperty("test.db.reset", "false")));
		resetIO(RecordIO.DATABASE, props);

		ServiceUtil.clearCache();

		isoOrg = getTestOrganization("/ISO42001");
		assertNotNull("ISO org context is null", isoOrg);
		orgId = isoOrg.getOrganizationId();
		adminUser = isoOrg.getAdminUser();
		assertNotNull("ISO org admin is null", adminUser);

		/// Admin-only: provision roles + create the non-admin role users + shared group.
		ISO42001Provisioning.ensureRoles(adminUser, orgId);
		BaseRecord testersRole = ensureRole("ISO42001Testers");
		BaseRecord readersRole = ensureRole("ISO42001Readers");
		isoTester = getCreateUser("isoTester", isoOrg);
		isoReader = getCreateUser("isoReader", isoOrg);
		ensureMember(testersRole, isoTester);
		ensureMember(readersRole, isoReader);

		BaseRecord g = ioContext.getPathUtil().makePath(adminUser, ModelNames.MODEL_GROUP, "~/ISO42001Shared",
			"DATA", orgId);
		assertNotNull("Shared group is null", g);
		sharedGroupId = g.get(FieldNames.FIELD_ID);
	}

	private BaseRecord ensureRole(String name) {
		BaseRecord role = ioContext.getPathUtil().makePath(adminUser, ModelNames.MODEL_ROLE, "/" + name,
			RoleEnumType.USER.toString(), orgId);
		assertNotNull("Role " + name + " is null", role);
		return role;
	}

	private void ensureMember(BaseRecord role, BaseRecord user) {
		if (!ioContext.getMemberUtil().isMember(user, role, null)) {
			boolean ok = ioContext.getMemberUtil().member(adminUser, role, user, null, true);
			assertTrue("Failed to assign " + user.get(FieldNames.FIELD_NAME) + " to role", ok);
		}
	}

	/** A UserPrincipal that {@code ServiceUtil.getPrincipalUser} resolves to the named /ISO42001 user. */
	private HttpServletRequestMock requestAs(String userName) {
		return new HttpServletRequestMock(new UserPrincipal(userName, "/ISO42001"));
	}

	private String configJson(BaseRecord owner) {
		long ownerId = owner.get(FieldNames.FIELD_ID);
		return "{\"schema\":\"" + ISO42001ModelNames.MODEL_TEST_CONFIG + "\","
			+ "\"name\":\"svc-tc-" + UUID.randomUUID() + "\","
			+ "\"groupId\":" + sharedGroupId + ","
			+ "\"organizationId\":" + orgId + ","
			+ "\"ownerId\":" + ownerId + ","
			+ "\"moduleId\":\"BIAS\",\"endpointName\":\"spark-ollama\",\"endpointType\":\"ollama\","
			+ "\"samplesPerGroup\":2,\"tier\":1}";
	}

	@Test
	public void testNoPrincipalReturns401() {
		Response r = service.getConfig("anything", new HttpServletRequestMock());
		assertEquals("Missing principal must be 401", 401, r.getStatus());
	}

	@Test
	public void testListModulesAsTester() throws Exception {
		Response r = service.listModules(requestAs("isoTester"));
		assertEquals(200, r.getStatus());
		String body = r.getEntity().toString();
		assertTrue("modules listing must include BIAS-ATTR-002, got: " + body, body.contains("BIAS-ATTR-002"));
	}

	@Test
	public void testCreateAndGetConfigAsTester() throws Exception {
		HttpServletRequestMock req = requestAs("isoTester");
		Response created = service.createConfig(configJson(isoTester), req);
		assertEquals("isoTester create should succeed (200), got " + created.getStatus(), 200, created.getStatus());

		JsonNode node = MAPPER.readTree(created.getEntity().toString());
		String objectId = node.path(FieldNames.FIELD_OBJECT_ID).asText(null);
		assertNotNull("Created testConfig must carry an objectId", objectId);

		Response got = service.getConfig(objectId, requestAs("isoTester"));
		assertEquals("GET of the created config should be 200", 200, got.getStatus());
		assertTrue("GET body should carry the objectId", got.getEntity().toString().contains(objectId));
	}

	@Test
	public void testReaderCannotCreateConfig() {
		/// Negative RBAC at the boundary: isoReader has no create role on the admin-owned shared group.
		Response r = service.createConfig(configJson(isoReader), requestAs("isoReader"));
		assertEquals("isoReader create MUST be denied (403), got " + r.getStatus(), 403, r.getStatus());
	}
}
