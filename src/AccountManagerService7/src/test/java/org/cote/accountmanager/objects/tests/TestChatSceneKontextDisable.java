package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.ChatService;
import org.cote.service.util.ServiceUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/**
 * PictureBook V3 Phase 2, P2-8: Kontext Disable (design §37.3) at the chat transport.
 * {@code POST /rest/chat/{objectId}/generateScene} refuses the retired Kontext pipeline at route entry, on
 * BOTH render paths, before the chat session is looked up and before any LLM or SD work:
 * <ul>
 * <li>an explicit {@code compositeMode=kontext} (any case/whitespace) is invalid content - 400 with
 * {@link SceneCompositeUtil#KONTEXT_RETIRED_MESSAGE}, not the generic "unsupported" hint;</li>
 * <li>a body that <i>resolves</i> to Kontext - the legacy {@code useKontext=true} with a blank or null
 * {@code compositeMode} - is refused by state, 409, same message. With the key absent the schema default
 * {@code flux2} is materialised on read and the boolean is never consulted (measured; see the case javadoc);</li>
 * <li>an unknown mode is still the generic 400 whose vocabulary no longer lists kontext;</li>
 * <li>a supported mode (or no mode at all) passes the gates and reaches the session lookup - for a random
 * objectId that is the 404 "Chat request not found", the farthest a direct call can go without a session.</li>
 * </ul>
 * Direct {@link ChatService} call on the H2 fixture; the asserting principal is a plain non-admin user. The
 * route's {@code @Context ServletContext} is not injected in a direct call, so every body carries
 * {@code refinerModel} to keep the init-param defaulting off the path (as {@code TestChatChainStatus} does).
 * Not covered here, stated plainly: the HTTP surface, which the Playwright item-14 block
 * ({@code e2e/chatChainSceneReimageRoutes.spec.js}) pins against the Docker stack as e2etest_shared.
 */
public class TestChatSceneKontextDisable extends BaseTest {

	private static final String USER_NAME = "chatkontextuser";
	private static final String RETIRED_MARK = "replaced by V3";

	private final ChatService service = new ChatService();

	private BaseRecord user;
	private String pb0;
	private String cb0;
	private String ch0;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		user = getCreateUser(USER_NAME);
		assertNotNull("User is null", user);
		pb0 = PbRenderPath.pictureBook();
		cb0 = PbRenderPath.chapBook();
		ch0 = PbRenderPath.chat();
	}

	@After
	public void restoreSwitch() {
		PbRenderPath.configure(pb0, cb0, ch0);
	}

	@Test
	public void TestExplicitKontextIs400WithRetiredMessageOnBothPaths() {
		for (String chatPath : new String[] { PbRenderPath.V1, PbRenderPath.V3 }) {
			PbRenderPath.configure(pb0, cb0, chatPath);
			Response r = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"kontext\",\"refinerModel\":\"x\"}");
			assertEquals("chat=" + chatPath + ": explicit kontext is invalid content", 400, r.getStatus());
			assertRetiredError(r, "chat=" + chatPath);
		}
	}

	@Test
	public void TestExplicitKontextIsCaseAndWhitespaceInsensitive() {
		Response r = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\" KONTEXT \",\"refinerModel\":\"x\"}");
		assertEquals(400, r.getStatus());
		assertRetiredError(r, "' KONTEXT '");
	}

	/**
	 * A body that only RESOLVES to kontext - a blank or null {@code compositeMode} with the legacy
	 * {@code useKontext=true} (what a client posts when it round-trips a pre-{@code compositeMode} saved config,
	 * whose column is SQL NULL) - passes the explicit-mode gate and is refused by state, 409, on both paths.
	 */
	@Test
	public void TestLegacyUseKontextWithBlankOrNullModeIs409OnBothPaths() {
		for (String chatPath : new String[] { PbRenderPath.V1, PbRenderPath.V3 }) {
			PbRenderPath.configure(pb0, cb0, chatPath);
			Response blank = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"\",\"useKontext\":true,\"refinerModel\":\"x\"}");
			assertEquals("chat=" + chatPath + ": blank compositeMode + useKontext=true resolves to the retired mode", 409, blank.getStatus());
			assertRetiredError(blank, "chat=" + chatPath + " blank");
			Response nul = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":null,\"useKontext\":true,\"refinerModel\":\"x\"}");
			assertEquals("chat=" + chatPath + ": null compositeMode + useKontext=true resolves to the retired mode", 409, nul.getStatus());
			assertRetiredError(nul, "chat=" + chatPath + " null");
		}
	}

	/**
	 * Measured, not assumed (2026-10-15): {@code useKontext=true} with the {@code compositeMode} KEY ABSENT is not
	 * refused. {@code RecordDeserializer} keeps only the fields the JSON names, but {@code BaseRecord.get} of a
	 * declared-but-absent field materialises it at its schema default ({@code checkField} →
	 * {@code RecordFactory.newFieldInstance}), so {@code resolveMode} reads {@code compositeMode="flux2"} and never
	 * consults the boolean - the same "schema default wins" rule the Objects7 override path shows. The route
	 * therefore proceeds (404 = session lookup reached) and Kontext is unreachable by the boolean alone.
	 */
	@Test
	public void TestUseKontextAloneMaterialisesTheFlux2DefaultAndPasses() {
		Response r = generate("{\"schema\":\"olio.sd.config\",\"useKontext\":true,\"refinerModel\":\"x\"}");
		assertEquals(404, r.getStatus());
		assertEquals("Chat request not found", body(r).get("error"));
	}

	/** §6.C 14 at the transport: a blank mode with no boolean resolves CLASSIC (chat passes {@code false}), never kontext. */
	@Test
	public void TestBlankModeWithoutUseKontextResolvesClassicAndPasses() {
		Response r = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"\",\"refinerModel\":\"x\"}");
		assertEquals("a blank mode with no boolean is classic, not the retired mode", 404, r.getStatus());
		assertEquals("Chat request not found", body(r).get("error"));
	}

	@Test
	public void TestUnknownModeIsGeneric400WithoutKontextInTheVocabulary() {
		Response r = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"bogus\",\"refinerModel\":\"x\"}");
		assertEquals(400, r.getStatus());
		Map<String, Object> b = body(r);
		assertEquals("Unsupported composite mode 'bogus'; expected flux2|classic", b.get("error"));
		assertEquals("the accepted vocabulary no longer names kontext", "flux2|classic", SceneCompositeUtil.SUPPORTED_MODES);
	}

	/**
	 * The unknown mode is echoed back (and logged) only after control characters are replaced and the
	 * length is capped at 32: a request body cannot forge a log line or reflect an unbounded value.
	 */
	@Test
	public void TestUnknownModeEchoIsSanitisedAndCapped() {
		String mode = "a\\nb" + "x".repeat(40);
		Response r = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"" + mode + "\",\"refinerModel\":\"x\"}");
		assertEquals(400, r.getStatus());
		String expectedEcho = "a?b" + "x".repeat(29) + "...";
		assertEquals(35, expectedEcho.length());
		assertEquals("Unsupported composite mode '" + expectedEcho + "'; expected flux2|classic", body(r).get("error"));
	}

	/** A supported mode passes both gates; for a random session id the route proceeds to the 404 session lookup. */
	@Test
	public void TestSupportedModesReachTheSessionLookup() {
		for (String mode : new String[] { "flux2", "classic", " FLUX2 " }) {
			Response r = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"" + mode + "\",\"refinerModel\":\"x\"}");
			assertEquals("compositeMode '" + mode + "' must not be refused by the mode gates", 404, r.getStatus());
			assertEquals("Chat request not found", body(r).get("error"));
		}
	}

	/** No mode and no legacy boolean resolves to classic (chat passes {@code false}); the route proceeds to the 404 lookup. */
	@Test
	public void TestAbsentModeResolvesToClassicAndReachesTheSessionLookup() {
		Response r = generate("{\"schema\":\"olio.sd.config\",\"refinerModel\":\"x\"}");
		assertEquals(404, r.getStatus());
		assertEquals("Chat request not found", body(r).get("error"));
		/// explicit useKontext=false is the classic path too
		Response r2 = generate("{\"schema\":\"olio.sd.config\",\"useKontext\":false,\"refinerModel\":\"x\"}");
		assertEquals(404, r2.getStatus());
	}

	private Response generate(String json) {
		Response r = service.generateScene(json, UUID.randomUUID().toString(), requestAs(user));
		assertNotNull("Response is null", r);
		return r;
	}

	private void assertRetiredError(Response r, String label) {
		Map<String, Object> b = body(r);
		Object err = b.get("error");
		assertNotNull(label + ": error body", err);
		String e = err.toString();
		assertTrue(label + ": the error starts with the shared retirement message: " + e,
			e.startsWith(SceneCompositeUtil.KONTEXT_RETIRED_MESSAGE));
		assertTrue(label + ": the error says it was replaced by V3: " + e, e.contains(RETIRED_MARK));
		assertTrue(label + ": the error cites design section 37.3: " + e, e.contains("37.3"));
	}

	private HttpServletRequestMock requestAs(BaseRecord u) {
		return new HttpServletRequestMock(new UserPrincipal((String) u.get(FieldNames.FIELD_NAME), organizationPath));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> body(Response r) {
		Object entity = r.getEntity();
		assertNotNull("Response entity is null", entity);
		Map<String, Object> m = JSONUtil.importObject(entity.toString(), LinkedHashMap.class);
		assertNotNull("Response body did not parse as JSON: " + entity, m);
		return m;
	}
}
