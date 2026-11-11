package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.olio.llm.ChatSceneRenderUtil;
import org.cote.accountmanager.olio.picturebook.PbRenderPath;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.FeatureConfigService;
import org.cote.service.util.ServiceUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/**
 * PictureBook V3 P2-7: the boot-pinned {@code chat.renderPath} switch as the transport consumes it.
 * {@code ChatService.generateScene} branches on {@link PbRenderPath#isChatV3()} at route entry and the
 * client discovers the value from {@code GET /rest/config/render-path}; this file pins the switch's
 * semantics (normalisation, the v3 default since P10-A 0; chat is the only key since Phase 10 P10-1
 * retired the picturebook / chapbook switches) and the v1 {@code render} marker
 * ({@link ChatSceneRenderUtil#describeV1()}). Same H2 fixture as
 * {@link TestFeatureConfigService}; the asserting principal is a plain non-admin user.
 * <p>
 * Not covered here, stated plainly: the v1 response body's {@code render} node is attached by
 * {@code ChatService.withRender}, a private static helper, and the route itself needs a chat session,
 * two characters and a live LLM + SD; both are exercised by the Playwright item-14-live block
 * ({@code e2e/chatChainSceneReimageRoutes.spec.js}) against the Docker stack.
 */
public class TestChatSceneRenderPath extends BaseTest {

	private static final String READER_NAME = "chatrenderpathreader";

	private final FeatureConfigService service = new FeatureConfigService();

	private BaseRecord reader;
	private String ch0;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		reader = getCreateUser(READER_NAME);
		assertNotNull("Reader user is null", reader);
		ch0 = PbRenderPath.chat();
	}

	@After
	public void restoreSwitch() {
		PbRenderPath.configure(ch0);
	}

	@Test
	public void TestChatV3IsOnlyWhenPinnedV3() {
		PbRenderPath.configure(PbRenderPath.V1);
		assertEquals(PbRenderPath.V1, PbRenderPath.chat());
		assertFalse("chat=v1 must not be v3", PbRenderPath.isChatV3());

		PbRenderPath.configure(PbRenderPath.V3);
		assertEquals(PbRenderPath.V3, PbRenderPath.chat());
		assertTrue("chat=v3 must be v3", PbRenderPath.isChatV3());

		/// case and whitespace are normalised; anything else pins the chat default (v3 since P10-A 0)
		PbRenderPath.configure(" V1 ");
		assertFalse("' V1 ' normalises to v1", PbRenderPath.isChatV3());
		PbRenderPath.configure("v2");
		assertTrue("an unrecognised chat value pins the chat default v3 (a typo must not roll chat back)", PbRenderPath.isChatV3());
		assertEquals(PbRenderPath.CHAT_DEFAULT, PbRenderPath.chat());
		PbRenderPath.configure(PbRenderPath.V1);
		PbRenderPath.configure(null);
		assertEquals("an absent chat value pins the chat default v3", PbRenderPath.V3, PbRenderPath.chat());
		assertEquals("the chat default is v3 (P10-A 0: the inline composite is the v1 rollback)", PbRenderPath.V3, PbRenderPath.CHAT_DEFAULT);
	}

	@Test
	public void TestDescribeV1IsExactlyTheRenderPathMarker() {
		Map<String, Object> v1 = ChatSceneRenderUtil.describeV1();
		assertNotNull(v1);
		assertEquals("describeV1 carries exactly one key: " + v1, 1, v1.size());
		assertEquals(ChatSceneRenderUtil.RENDER_PATH_V1, v1.get("renderPath"));
		assertEquals("v1", v1.get("renderPath"));
		assertEquals("render", ChatSceneRenderUtil.RENDER_KEY);
		/// what the transport embeds under the render key serialises to the one-key object
		assertEquals("{\"renderPath\":\"v1\"}", JSONUtil.exportObject(v1, null, true, false));
	}

	@Test
	public void TestRenderPathEndpointEchoesChatForBothValues() {
		PbRenderPath.configure(PbRenderPath.V1);
		Response r1 = service.getRenderPath(requestAs(reader));
		assertEquals(200, r1.getStatus());
		Map<String, Object> b1 = body(r1);
		assertEquals(PbRenderPath.V1, b1.get("chat"));

		PbRenderPath.configure(PbRenderPath.V3);
		Response r3 = service.getRenderPath(requestAs(reader));
		assertEquals(200, r3.getStatus());
		Map<String, Object> b3 = body(r3);
		assertEquals(PbRenderPath.V3, b3.get("chat"));
		/// P10-1: picturebook and chapbook are V3-only products, not switches - the body carries chat alone
		assertEquals("the body carries exactly the chat key: " + b3.keySet(), 1, b3.size());
		assertFalse("no picturebook key: " + b3.keySet(), b3.containsKey("picturebook"));
		assertFalse("no chapbook key: " + b3.keySet(), b3.containsKey("chapbook"));

		/// no principal -> 401, same as the other config reads
		Response anon = service.getRenderPath(new HttpServletRequestMock());
		assertEquals(401, anon.getStatus());
	}

	private HttpServletRequestMock requestAs(BaseRecord user) {
		return new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), organizationPath));
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
}
