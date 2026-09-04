package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.lang.reflect.Method;

import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ConnectionDialectEnumType;
import org.junit.Test;

/**
 * Phase 3 P3-1: locks down {@code ChatUtil.resolveServiceType(connection, chatConfig)} — the
 * single authoritative resolution of the LLM transport protocol. {@code system.connection.dialect}
 * is the source of truth; the deprecated {@code olio.llm.chatConfig.serviceType} is only the
 * fallback consulted when dialect is UNKNOWN or the connection is absent.
 *
 * <p>{@code resolveServiceType} is package-private {@code static}; it is invoked here via reflection
 * (the same idiom {@link TestChatServiceUrl} uses to reach {@code Chat.processStreamChunk}). This
 * exercises the REAL production method against real {@code system.connection}/{@code olio.llm.chatConfig}
 * {@link BaseRecord}s, plus one end-to-end resolve -&gt; {@code Chat.getServiceUrl()} assertion binding
 * the resolved dialect to the LiteLLM {@code /v1/chat/completions} transport path. No live LLM.</p>
 */
public class TestDialectResolution extends BaseTest {

	private LLMServiceEnumType resolve(BaseRecord connection, BaseRecord chatConfig) {
		try {
			Method m = ChatUtil.class.getDeclaredMethod("resolveServiceType", BaseRecord.class, BaseRecord.class);
			m.setAccessible(true);
			return (LLMServiceEnumType) m.invoke(null, new Object[] { connection, chatConfig });
		} catch (Exception e) {
			throw new RuntimeException("reflective resolveServiceType invocation failed", e);
		}
	}

	private BaseRecord connection(ConnectionDialectEnumType dialect) {
		try {
			BaseRecord c = RecordFactory.model(ModelNames.MODEL_CONNECTION).newInstance();
			if (dialect != null) {
				c.set("dialect", dialect);
			}
			return c;
		} catch (Exception e) {
			throw new RuntimeException("failed to build system.connection", e);
		}
	}

	private BaseRecord chatConfig(LLMServiceEnumType serviceType) {
		try {
			BaseRecord c = RecordFactory.model(OlioModelNames.MODEL_CHAT_CONFIG).newInstance();
			if (serviceType != null) {
				c.set("serviceType", serviceType);
			}
			return c;
		} catch (Exception e) {
			throw new RuntimeException("failed to build olio.llm.chatConfig", e);
		}
	}

	@Test
	public void testDialectWinsWhenNonUnknown() {
		/// A non-UNKNOWN dialect is authoritative and beats a CONFLICTING chatConfig.serviceType.
		assertEquals(LLMServiceEnumType.OLLAMA,
			resolve(connection(ConnectionDialectEnumType.OLLAMA), chatConfig(LLMServiceEnumType.OPENAI)));
		assertEquals(LLMServiceEnumType.OPENAI_COMPAT,
			resolve(connection(ConnectionDialectEnumType.OPENAI_COMPAT), chatConfig(LLMServiceEnumType.OPENAI)));
		assertEquals(LLMServiceEnumType.OPENAI,
			resolve(connection(ConnectionDialectEnumType.OPENAI), chatConfig(LLMServiceEnumType.OLLAMA)));
	}

	@Test
	public void testDialectResolvesWithoutAnyChatConfig() {
		/// dialect alone resolves even when there is NO chatConfig fallback available.
		assertEquals(LLMServiceEnumType.OLLAMA,
			resolve(connection(ConnectionDialectEnumType.OLLAMA), null));
		assertEquals(LLMServiceEnumType.OPENAI_COMPAT,
			resolve(connection(ConnectionDialectEnumType.OPENAI_COMPAT), null));
	}

	@Test
	public void testFallsBackToServiceTypeWhenDialectUnknown() {
		/// dialect UNKNOWN (explicit, and the model default) -> use chatConfig.serviceType.
		assertEquals(LLMServiceEnumType.OLLAMA,
			resolve(connection(ConnectionDialectEnumType.UNKNOWN), chatConfig(LLMServiceEnumType.OLLAMA)));
		/// default connection: dialect field defaults to UNKNOWN -> fallback path.
		assertEquals(LLMServiceEnumType.OPENAI,
			resolve(connection(null), chatConfig(LLMServiceEnumType.OPENAI)));
	}

	@Test
	public void testFallsBackToServiceTypeWhenConnectionAbsent() {
		/// No connection at all -> serviceType. LOCAL has no ConnectionDialectEnumType peer,
		/// so it can ONLY ever be produced through this fallback path.
		assertEquals(LLMServiceEnumType.LOCAL,
			resolve(null, chatConfig(LLMServiceEnumType.LOCAL)));
	}

	@Test
	public void testNullWhenNeitherSourceResolves() {
		/// Documented contract: no connection and no chatConfig -> null.
		assertNull(resolve(null, null));
		/// A UNKNOWN-dialect connection with no chatConfig also collapses to null.
		assertNull(resolve(connection(ConnectionDialectEnumType.UNKNOWN), null));
	}

	@Test
	public void testResolvedDialectDrivesLiteLlmServiceUrl() {
		/// End-to-end P3-1: a resolved OPENAI_COMPAT dialect, threaded into a Chat instance,
		/// yields the LiteLLM /v1/chat/completions transport path (model carried in the body).
		LLMServiceEnumType resolved =
			resolve(connection(ConnectionDialectEnumType.OPENAI_COMPAT), chatConfig(LLMServiceEnumType.OPENAI));
		assertEquals(LLMServiceEnumType.OPENAI_COMPAT, resolved);

		Chat chat = new Chat();
		chat.setServiceType(resolved);
		chat.setServerUrl("http://litellm.example:4000");
		OpenAIRequest req = new OpenAIRequest();
		req.setModel("gpt-4o");
		assertEquals("http://litellm.example:4000/v1/chat/completions", chat.getServiceUrl(req));
	}
}
