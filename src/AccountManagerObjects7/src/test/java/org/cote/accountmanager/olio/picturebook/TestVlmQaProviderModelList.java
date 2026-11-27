package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.util.ConnectionProbeUtil;
import org.junit.Test;

/// VlmQaProvider.listModels / hasModel after the 2026-11-27 extraction of the parse into
/// ConnectionProbeUtil.parseModelNames (Service Status shares it). Offline on purpose: TestPbVlmQaLive covers
/// the same path through t01 but its @Before needs book 58's cast in the database, so on a database without
/// that fixture it fails before reaching listModels. This drives the real provider through a canned transport.
public class TestVlmQaProviderModelList {

	private static final class Canned implements VlmQaProvider.Transport {
		String body;
		String lastUrl;
		@Override
		public String post(String url, Map<String, String> headers, String json) {
			return null;
		}
		@Override
		public String get(String url, Map<String, String> headers) {
			lastUrl = url;
			return body;
		}
	}

	private static VlmQaProvider provider(LLMServiceEnumType type, Canned t) {
		return new VlmQaProvider(new VlmQaProvider.Route("http://vlm.test:1/", type, null, "m"), t, oid -> null);
	}

	@Test
	public void ollamaTagsAreParsedThroughTheSharedParser() {
		Canned t = new Canned();
		t.body = "{\"models\":[{\"name\":\"vis:latest\"},{\"name\":\"qwen3:8b\"},{\"nope\":1}]}";
		List<String> names = provider(LLMServiceEnumType.OLLAMA, t).listModels();
		assertEquals("http://vlm.test:1/api/tags", t.lastUrl);
		assertEquals(Arrays.asList("vis:latest", "qwen3:8b"), names);
		assertEquals(names, ConnectionProbeUtil.parseModelNames(t.body, true));
		assertTrue(VlmQaProvider.hasModel(names, "vis"));
		assertTrue(VlmQaProvider.hasModel(names, "qwen3:8b:latest"));
		assertFalse(VlmQaProvider.hasModel(names, "qwen3"));
		assertFalse(VlmQaProvider.hasModel(null, "vis"));
	}

	@Test
	public void openAiCompatDataIdsAreParsed() {
		Canned t = new Canned();
		t.body = "{\"object\":\"list\",\"data\":[{\"id\":\"a\"},{\"id\":\"b\"}]}";
		List<String> names = provider(LLMServiceEnumType.OPENAI_COMPAT, t).listModels();
		assertEquals("http://vlm.test:1/v1/models", t.lastUrl);
		assertEquals(Arrays.asList("a", "b"), names);
	}

	@Test
	public void wrongShapeOrNoAnswerIsNull() {
		Canned t = new Canned();
		t.body = null;
		assertNull(provider(LLMServiceEnumType.OLLAMA, t).listModels());
		t.body = "not json";
		assertNull(provider(LLMServiceEnumType.OLLAMA, t).listModels());
		t.body = "{\"models\":\"x\"}";
		assertNull(provider(LLMServiceEnumType.OLLAMA, t).listModels());
		t.body = "{\"data\":[]}";
		assertNull("ollama route reads models[], not data[]", provider(LLMServiceEnumType.OLLAMA, t).listModels());
	}
}
