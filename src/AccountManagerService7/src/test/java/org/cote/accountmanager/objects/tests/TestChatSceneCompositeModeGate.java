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
 * The composite-mode gate at the chat transport. {@code POST /rest/chat/{objectId}/generateScene} resolves
 * {@code compositeMode} ONCE at route entry, on BOTH render paths, before the chat session is looked up and
 * before any LLM or SD work. Written as P2-8 (Kontext Disable, design §37.3: a dedicated 400/409 "replaced
 * by V3" refusal); rewritten at Phase 10 P10-3 (Kontext Removal, §37.5) when that pipeline and its dedicated
 * refusal were deleted - since then:
 * <ul>
 * <li>{@code compositeMode=kontext} (any case/whitespace) is simply one more unsupported value: the generic
 * 400 with the {@link SceneCompositeUtil#SUPPORTED_MODES} hint, exactly like {@code bogus};</li>
 * <li>the legacy {@code useKontext} boolean is not read at all: {@code true} with a blank or null
 * {@code compositeMode} resolves to classic and passes the gate (previously 409). With the key absent the
 * schema default {@code flux2} is materialised on read (measured; see the case javadoc);</li>
 * <li>a supported mode (or no mode at all) passes the gate and reaches the session lookup - for a random
 * objectId that is the 404 "Chat request not found", the farthest a direct call can go without a session.</li>
 * </ul>
 * Direct {@link ChatService} call on the H2 fixture; the asserting principal is a plain non-admin user. The
 * route's {@code @Context ServletContext} is not injected in a direct call, so every body carries
 * {@code refinerModel} to keep the init-param defaulting off the path (as {@code TestChatChainStatus} does).
 * Not covered here, stated plainly: the HTTP surface, which the Playwright item-14 block
 * ({@code e2e/chatChainSceneReimageRoutes.spec.js}) pins against the Docker stack as e2etest_shared.
 */
public class TestChatSceneCompositeModeGate extends BaseTest {

	private static final String USER_NAME = "chatmodegateuser";
	private static final String UNSUPPORTED_PREFIX = "Unsupported composite mode '";
	private static final String EXPECTED_SUFFIX = "'; expected " + SceneCompositeUtil.SUPPORTED_MODES;

	private final ChatService service = new ChatService();

	private BaseRecord user;
	private String ch0;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		user = getCreateUser(USER_NAME);
		assertNotNull("User is null", user);
		ch0 = PbRenderPath.chat();
	}

	@After
	public void restoreSwitch() {
		PbRenderPath.configure(ch0);
	}

	/**
	 * Phase 10 P10-3: the removed Kontext literal is an unsupported value like any other - the generic 400
	 * with the vocabulary hint, on both render paths, no dedicated message. The echo is trimmed by the route.
	 */
	@Test
	public void TestKontextIsAnUnsupportedModeOnBothPaths() {
		for (String chatPath : new String[] { PbRenderPath.V1, PbRenderPath.V3 }) {
			PbRenderPath.configure(chatPath);
			for (String mode : new String[] { "kontext", " KONTEXT ", "Kontext" }) {
				Response r = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"" + mode + "\",\"refinerModel\":\"x\"}");
				assertEquals("chat=" + chatPath + " mode '" + mode + "': an unsupported value", 400, r.getStatus());
				assertUnsupportedError(r, "chat=" + chatPath + " mode '" + mode + "'", mode.trim());
			}
		}
	}

	/**
	 * Phase 10 P10-3: the legacy {@code useKontext} boolean is not read by {@code resolveMode} any more (its
	 * column goes at P10-4). {@code true} with a blank or null {@code compositeMode} - what a client posts when
	 * it round-trips a pre-{@code compositeMode} saved config whose column is SQL NULL - therefore resolves to
	 * classic and passes the gate on both paths (404 = session lookup reached). Before P10-3 this was the 409
	 * "resolves to the retired mode" refusal.
	 */
	@Test
	public void TestUseKontextWithBlankOrNullModeResolvesClassicAndPassesOnBothPaths() {
		for (String chatPath : new String[] { PbRenderPath.V1, PbRenderPath.V3 }) {
			PbRenderPath.configure(chatPath);
			Response blank = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"\",\"useKontext\":true,\"refinerModel\":\"x\"}");
			assertEquals("chat=" + chatPath + ": blank compositeMode + useKontext=true is classic since P10-3", 404, blank.getStatus());
			assertEquals("Chat request not found", body(blank).get("error"));
			Response nul = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":null,\"useKontext\":true,\"refinerModel\":\"x\"}");
			assertEquals("chat=" + chatPath + ": null compositeMode + useKontext=true is classic since P10-3", 404, nul.getStatus());
			assertEquals("Chat request not found", body(nul).get("error"));
		}
	}

	/**
	 * Measured, not assumed (2026-10-15): {@code useKontext=true} with the {@code compositeMode} KEY ABSENT is not
	 * refused. {@code RecordDeserializer} keeps only the fields the JSON names, but {@code BaseRecord.get} of a
	 * declared-but-absent field materialises it at its schema default ({@code checkField} →
	 * {@code RecordFactory.newFieldInstance}), so {@code resolveMode} reads {@code compositeMode="flux2"} - the
	 * same "schema default wins" rule the Objects7 override path shows. The route proceeds (404 = session lookup
	 * reached). Since P10-3 the boolean is not consulted in any case; this pins the materialised default.
	 */
	@Test
	public void TestUseKontextAloneMaterialisesTheFlux2DefaultAndPasses() {
		Response r = generate("{\"schema\":\"olio.sd.config\",\"useKontext\":true,\"refinerModel\":\"x\"}");
		assertEquals(404, r.getStatus());
		assertEquals("Chat request not found", body(r).get("error"));
	}

	/** §6.C 14 at the transport: a blank mode with no boolean resolves CLASSIC and passes. */
	@Test
	public void TestBlankModeWithoutUseKontextResolvesClassicAndPasses() {
		Response r = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"\",\"refinerModel\":\"x\"}");
		assertEquals("a blank mode with no boolean is classic", 404, r.getStatus());
		assertEquals("Chat request not found", body(r).get("error"));
	}

	@Test
	public void TestUnknownModeIsGeneric400WithoutKontextInTheVocabulary() {
		Response r = generate("{\"schema\":\"olio.sd.config\",\"compositeMode\":\"bogus\",\"refinerModel\":\"x\"}");
		assertEquals(400, r.getStatus());
		assertUnsupportedError(r, "bogus", "bogus");
		assertEquals("the accepted vocabulary does not name kontext", "flux2|classic", SceneCompositeUtil.SUPPORTED_MODES);
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

	/** No mode and no legacy boolean resolves to classic; the route proceeds to the 404 lookup. */
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

	/// The one refusal shape the gate has since P10-3: the trimmed echo in quotes plus the SUPPORTED_MODES hint.
	private void assertUnsupportedError(Response r, String label, String echo) {
		Map<String, Object> b = body(r);
		Object err = b.get("error");
		assertNotNull(label + ": error body", err);
		String e = err.toString();
		assertEquals(label + ": the generic unsupported-mode message", UNSUPPORTED_PREFIX + echo + EXPECTED_SUFFIX, e);
		assertTrue(label + ": no dedicated retirement wording survives: " + e, !e.contains("replaced by V3") && !e.contains("37.3"));
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
