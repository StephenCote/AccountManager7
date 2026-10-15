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
 * semantics (three-value configure, two-value configure leaving chat alone, the v1 default) and the
 * v1 {@code render} marker ({@link ChatSceneRenderUtil#describeV1()}). Same H2 fixture as
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
	private String pb0;
	private String cb0;
	private String ch0;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		reader = getCreateUser(READER_NAME);
		assertNotNull("Reader user is null", reader);
		pb0 = PbRenderPath.pictureBook();
		cb0 = PbRenderPath.chapBook();
		ch0 = PbRenderPath.chat();
	}

	@After
	public void restoreSwitch() {
		PbRenderPath.configure(pb0, cb0, ch0);
	}

	@Test
	public void TestChatV3IsOnlyWhenPinnedV3() {
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1, PbRenderPath.V1);
		assertEquals(PbRenderPath.V1, PbRenderPath.chat());
		assertFalse("chat=v1 must not be v3", PbRenderPath.isChatV3());

		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1, PbRenderPath.V3);
		assertEquals(PbRenderPath.V3, PbRenderPath.chat());
		assertTrue("chat=v3 must be v3", PbRenderPath.isChatV3());

		/// case and whitespace are normalised; anything else pins the chat default (v1)
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1, " V3 ");
		assertTrue("' V3 ' normalises to v3", PbRenderPath.isChatV3());
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1, "v2");
		assertFalse("an unrecognised chat value pins the chat default v1", PbRenderPath.isChatV3());
		assertEquals(PbRenderPath.CHAT_DEFAULT, PbRenderPath.chat());
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1, PbRenderPath.V3);
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1, null);
		assertEquals("an absent chat value pins the chat default v1", PbRenderPath.V1, PbRenderPath.chat());
		assertEquals("the chat default is v1 (the inline composite stays live)", PbRenderPath.V1, PbRenderPath.CHAT_DEFAULT);
	}

	@Test
	public void TestTwoValueConfigureLeavesChatUnchanged() {
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1, PbRenderPath.V3);
		assertTrue(PbRenderPath.isChatV3());

		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V1);
		assertEquals(PbRenderPath.V3, PbRenderPath.pictureBook());
		assertEquals(PbRenderPath.V1, PbRenderPath.chapBook());
		assertEquals("the two-value configure must not move chat", PbRenderPath.V3, PbRenderPath.chat());
		assertTrue(PbRenderPath.isChatV3());

		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V1, PbRenderPath.V1);
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V3);
		assertEquals("the two-value configure must not move chat off v1 either", PbRenderPath.V1, PbRenderPath.chat());
		assertFalse(PbRenderPath.isChatV3());
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
		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3, PbRenderPath.V1);
		Response r1 = service.getRenderPath(requestAs(reader));
		assertEquals(200, r1.getStatus());
		Map<String, Object> b1 = body(r1);
		assertEquals(PbRenderPath.V1, b1.get("chat"));
		assertEquals(PbRenderPath.V1, b1.get("picturebook"));
		assertEquals(PbRenderPath.V3, b1.get("chapbook"));

		PbRenderPath.configure(PbRenderPath.V1, PbRenderPath.V3, PbRenderPath.V3);
		Response r3 = service.getRenderPath(requestAs(reader));
		assertEquals(200, r3.getStatus());
		Map<String, Object> b3 = body(r3);
		assertEquals(PbRenderPath.V3, b3.get("chat"));
		assertEquals("picturebook is unaffected by the chat value", PbRenderPath.V1, b3.get("picturebook"));
		assertEquals("chapbook is unaffected by the chat value", PbRenderPath.V3, b3.get("chapbook"));
		assertEquals("the body carries exactly the three product keys: " + b3.keySet(), 3, b3.size());

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
