package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.cote.accountmanager.data.security.UserPrincipal;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.thread.AsyncJob;
import org.cote.accountmanager.thread.AsyncJobRegistry;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.rest.services.ChatService;
import org.cote.service.util.ServiceUtil;
import org.junit.Before;
import org.junit.Test;

import jakarta.ws.rs.core.Response;

/// GET /chat/chain/status/{planId} contract. The registry is shared with PictureBook/ChapBook jobs
/// and the route embeds a chain job's result raw as JSON, so only `chat.chain` jobs may answer 200;
/// another kind, another principal's job and an unknown id are all 404 `status:"unknown"`.
public class TestChatChainStatus extends BaseTest {

	private final ChatService service = new ChatService();
	private BaseRecord owner;
	private BaseRecord other;

	@Override
	@Before
	public void setup() {
		super.setup();
		ServiceUtil.clearCache();
		owner = getCreateUser("chainStatusOwner");
		other = getCreateUser("chainStatusOther");
		assertNotNull(owner);
		assertNotNull(other);
	}

	private HttpServletRequestMock requestAs(BaseRecord user) {
		return new HttpServletRequestMock(new UserPrincipal((String) user.get(FieldNames.FIELD_NAME), organizationPath));
	}

	private AsyncJob submitAndFinish(BaseRecord user, String kind, String result) throws Exception {
		AsyncJob job = AsyncJobRegistry.submit(user, kind, "key-" + UUID.randomUUID(), j -> result);
		assertNotNull("job must be submitted for a real principal", job);
		long deadline = System.currentTimeMillis() + 10000L;
		while (!job.isTerminal() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50L);
		}
		assertTrue("job must reach a terminal state", job.isTerminal());
		return job;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> bodyOf(Response r) {
		assertNotNull(r.getEntity());
		Map<String, Object> m = JSONUtil.importObject(r.getEntity().toString(), LinkedHashMap.class);
		assertNotNull("body must be valid JSON: " + r.getEntity(), m);
		return m;
	}

	@Test
	public void TestOwnChainJobIs200WithEmbeddedResult() throws Exception {
		AsyncJob job = submitAndFinish(owner, "chat.chain", "{\"ok\":true,\"steps\":2}");
		Response r = service.chainStatus(job.getJobId(), requestAs(owner));
		assertEquals("own chain job: " + r.getEntity(), 200, r.getStatus());
		Map<String, Object> m = bodyOf(r);
		assertEquals(job.getJobId(), m.get("jobId"));
		assertEquals("chat.chain", m.get("kind"));
		assertEquals("completed", m.get("status"));
		assertEquals(Boolean.TRUE, m.get("terminal"));
		Object result = m.get("result");
		assertTrue("result must be embedded as an object: " + r.getEntity(), result instanceof Map);
		assertEquals(Boolean.TRUE, ((Map<String, Object>) result).get("ok"));
	}

	/// A PictureBook/ChapBook job's result is not the chain JSON; the chain route must not embed it.
	@Test
	public void TestOwnNonChainJobIs404Unknown() throws Exception {
		AsyncJob job = submitAndFinish(owner, "pb.extractScenes", "not json at all");
		Response r = service.chainStatus(job.getJobId(), requestAs(owner));
		assertEquals("non-chain job: " + r.getEntity(), 404, r.getStatus());
		assertEquals("unknown", bodyOf(r).get("status"));
	}

	@Test
	public void TestAnotherPrincipalsChainJobIs404Unknown() throws Exception {
		AsyncJob job = submitAndFinish(owner, "chat.chain", "{\"ok\":true}");
		Response r = service.chainStatus(job.getJobId(), requestAs(other));
		assertEquals("foreign chain job: " + r.getEntity(), 404, r.getStatus());
		assertEquals("unknown", bodyOf(r).get("status"));
	}

	@Test
	public void TestUnknownIdIs404Unknown() {
		Response r = service.chainStatus(UUID.randomUUID().toString(), requestAs(owner));
		assertEquals(404, r.getStatus());
		assertEquals("unknown", bodyOf(r).get("status"));
	}

	/// POST /chat/chain?async=true past the principal's active-job cap must be refused with 429 and
	/// must not fall back to running the chain inline — that fallback is for "no usable principal",
	/// and taking it here would hand the caller exactly the unbounded work the cap refuses. The held
	/// jobs are plain registry submissions (no LLM); the chain body never reaches runChain.
	@Test
	public void TestChainPastTheActiveJobCapIs429() throws Exception {
		final CountDownLatch release = new CountDownLatch(1);
		AsyncJob[] held = new AsyncJob[AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL];
		try {
			for (int i = 0; i < held.length; i++) {
				held[i] = AsyncJobRegistry.submit(owner, "test.hold", "hold-" + i, j -> {
					release.await(10000L, TimeUnit.MILLISECONDS);
					return "{}";
				});
				assertNotNull(held[i]);
			}
			assertEquals(AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL, AsyncJobRegistry.activeCount(owner));

			Response r = service.chain("{\"planQuery\":\"cap probe\"}", true, requestAs(owner));
			assertEquals("over-cap async chain: " + r.getEntity(), 429, r.getStatus());
			Object err = bodyOf(r).get("error");
			assertTrue("body must say why: " + r.getEntity(), err instanceof String && ((String) err).contains("Too many active async jobs"));
			assertEquals("the refused chain must not have been registered",
				AsyncJobRegistry.MAX_ACTIVE_JOBS_PER_PRINCIPAL, AsyncJobRegistry.activeCount(owner));
			for (AsyncJob j : AsyncJobRegistry.list(owner)) {
				assertTrue("no chain job may exist for the refused request: " + j.getKind(), !"chat.chain".equals(j.getKind()) || j.isTerminal());
			}
		} finally {
			release.countDown();
		}
		long deadline = System.currentTimeMillis() + 10000L;
		while (AsyncJobRegistry.activeCount(owner) > 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(50L);
		}
		assertEquals("held jobs must drain so the cap lifts again", 0, AsyncJobRegistry.activeCount(owner));
	}
}
