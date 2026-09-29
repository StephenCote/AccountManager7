package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// EmbeddingUtil OPENAI / OPENAI_COMPAT request-body and auth-header contract.
///
/// The OPENAI_COMPAT dialect exists so the containerized Ollama (`am7-docker-up --ollama`,
/// EMBEDDING_SERVER=http://ollama:11434/v1/embeddings, EMBEDDING_MODEL=nomic-embed-text) can serve
/// embeddings. Ollama's /v1/embeddings REJECTS a body without `model` and does not honor
/// `dimensions` (nomic-embed-text is natively 768, which must equal embedding.dimensions); Azure
/// OPENAI resolves the model from the deployment URL and DOES honor `dimensions`
/// (text-embedding-3-small is 1536 by default and must be asked for 768). Auth differs too:
/// Azure takes `api-key`, OPENAI_COMPAT takes `Authorization: Bearer`.
///
/// The body/header tests are pure — no IOSystem, no DB, no network — and run anywhere. The live
/// test is gated on the local Ollama container (host port 11435) actually having nomic-embed-text
/// pulled; it Assume-skips visibly otherwise rather than pretending.
///
/// This class intentionally does NOT extend BaseTest: it only needs the Olio model namespace
/// registered so RecordFactory can materialize olio.llm.openai.openaiInput / openaiResponse.
public class TestEmbeddingUtilOpenAiInput {
	public static final Logger logger = LogManager.getLogger(TestEmbeddingUtilOpenAiInput.class);

	/// Host-published port of the compose `ollama` service (container-internal http://ollama:11434).
	private static final String LIVE_OLLAMA_BASE = "http://127.0.0.1:11435";
	private static final String LIVE_MODEL = "nomic-embed-text";
	private static final int LIVE_EXPECTED_WIDTH = 768;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@BeforeClass
	public static void registerModels() {
		OlioModelNames.use();
		/// RecordFactory.newInstance(name) requires the loose base model to be pre-loaded
		/// (RecordFactory.java:167). IOSystem.open() does that for every registered model via
		/// ModelNames.loadModels(); with no IOSystem here, prime just the OpenAI wire-model family
		/// (openaiInput for the request; openaiResponse + its nested openaiData/openaiUsage for the
		/// parse — RecordDeserializer needs the nested models loaded too or data[0] comes back null).
		int primed = 0;
		for (String m : ModelNames.MODELS) {
			if (m.startsWith("olio.llm.openai.")) {
				assertNotNull("schema resource failed to load: " + m, RecordFactory.model(m));
				primed++;
			}
		}
		assertTrue("expected the olio.llm.openai.* family to be registered by OlioModelNames.use()", primed > 0);
		assertNotNull(RecordFactory.model(OlioModelNames.MODEL_OPENAI_INPUT));
		assertNotNull(RecordFactory.model(OlioModelNames.MODEL_OPENAI_RESPONSE));
	}

	private static JsonNode body(BaseRecord rec) throws Exception {
		String json = rec.toFullString();
		logger.info("[EMBED-BODY] " + json);
		return MAPPER.readTree(json);
	}

	@Test
	public void testOpenAiCompatBodyCarriesModelAndNoDimensions() throws Exception {
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.OPENAI_COMPAT, "http://127.0.0.1:1/v1/embeddings", "local-token");
		eu.setEmbeddingModel(LIVE_MODEL);
		eu.setEmbeddingDimensions(768);

		BaseRecord inp = eu.buildOpenAiInput("x");
		assertNotNull("buildOpenAiInput returned null", inp);
		assertEquals("x", inp.get("input"));
		assertEquals(LIVE_MODEL, inp.get("model"));

		JsonNode json = body(inp);
		assertEquals("x", json.path("input").asText());
		assertEquals("model must be on the wire for OPENAI_COMPAT (Ollama rejects a body without one)",
			LIVE_MODEL, json.path("model").asText());
		assertFalse("dimensions must NOT be on the wire for OPENAI_COMPAT (Ollama ignores/rejects it; nomic-embed-text is natively 768)",
			json.has("dimensions"));
	}

	@Test
	public void testOpenAiBodyCarriesDimensionsAndNoModelWhenUnset() throws Exception {
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.OPENAI, "https://example.invalid/openai/deployments/dep/embeddings?api-version=1", "azure-key");
		eu.setEmbeddingDimensions(768);
		/// No setEmbeddingModel: Azure resolves the model from the deployment URL, so the historical
		/// no-model body must be preserved exactly.

		BaseRecord inp = eu.buildOpenAiInput("x");
		JsonNode json = body(inp);
		assertEquals("x", json.path("input").asText());
		assertTrue("dimensions must be on the wire for OPENAI (Azure honors it; text-embedding-3-small defaults to 1536)",
			json.has("dimensions"));
		assertEquals(768, json.path("dimensions").asInt());
		assertFalse("a null model must be OMITTED from the Azure body, never emitted as \"model\":null", json.has("model"));
	}

	@Test
	public void testOpenAiBodyCarriesModelWhenSet() throws Exception {
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.OPENAI, "https://example.invalid/openai/deployments/dep/embeddings?api-version=1", "azure-key");
		eu.setEmbeddingDimensions(768);
		eu.setEmbeddingModel("text-embedding-3-small");

		JsonNode json = body(eu.buildOpenAiInput("x"));
		assertEquals("text-embedding-3-small", json.path("model").asText());
		assertEquals(768, json.path("dimensions").asInt());
	}

	@Test
	public void testDimensionsOmittedWhenNotPositive() throws Exception {
		/// 0 means "let the model decide" — the pre-existing guard semantics, unchanged.
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.OPENAI, "https://example.invalid/openai/deployments/dep/embeddings", null);
		eu.setEmbeddingDimensions(0);
		JsonNode json = body(eu.buildOpenAiInput("x"));
		assertFalse(json.has("dimensions"));
	}

	@Test
	public void testBlankModelNormalizesToNull() throws Exception {
		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.OPENAI_COMPAT, "http://127.0.0.1:1/v1/embeddings", null);
		eu.setEmbeddingModel("   ");
		assertNull("blank model must normalize to null", eu.getEmbeddingModel());
		assertFalse(body(eu.buildOpenAiInput("x")).has("model"));

		eu.setEmbeddingModel("  nomic-embed-text ");
		assertEquals("model must be trimmed", LIVE_MODEL, eu.getEmbeddingModel());

		eu.setEmbeddingModel(null);
		assertNull(eu.getEmbeddingModel());
	}

	@Test
	public void testAuthHeaderByDialect() {
		EmbeddingUtil compat = new EmbeddingUtil(LLMServiceEnumType.OPENAI_COMPAT, "http://127.0.0.1:1/v1/embeddings", "local-token");
		Map<String,String> h = compat.authHeaders(compat.getEndpoint());
		assertNotNull(h);
		assertEquals("OPENAI_COMPAT must send Bearer auth (LiteLLM requires it; Ollama ignores it)", "Bearer local-token", h.get("Authorization"));
		assertFalse("OPENAI_COMPAT must not send the Azure api-key header", h.containsKey("api-key"));

		EmbeddingUtil azure = new EmbeddingUtil(LLMServiceEnumType.OPENAI, "https://example.invalid/openai/deployments/dep/embeddings", "azure-key");
		Map<String,String> a = azure.authHeaders(azure.getEndpoint());
		assertNotNull(a);
		assertEquals("OPENAI (Azure) must keep sending api-key", "azure-key", a.get("api-key"));
		assertFalse("OPENAI (Azure) must not gain a Bearer header", a.containsKey("Authorization"));

		EmbeddingUtil noTok = new EmbeddingUtil(LLMServiceEnumType.OPENAI_COMPAT, "http://127.0.0.1:1/v1/embeddings", null);
		assertNull("no token -> no auth header at all", noTok.authHeaders(noTok.getEndpoint()));
		EmbeddingUtil blankTok = new EmbeddingUtil(LLMServiceEnumType.OPENAI_COMPAT, "http://127.0.0.1:1/v1/embeddings", "");
		assertNull("blank token -> no auth header at all", blankTok.authHeaders(blankTok.getEndpoint()));
	}

	/// Live: the compose `ollama` container on host port 11435 with nomic-embed-text pulled.
	/// Exercises the real OPENAI_COMPAT path end to end (ClientUtil.postJSON with Bearer header,
	/// openaiResponse parse, width guard) and asserts the natively-768 vector comes back at 768.
	@Test
	public void testLiveOllamaOpenAiCompatEmbeddingIs768() {
		boolean modelPresent = false;
		String tags = null;
		try {
			HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(LIVE_OLLAMA_BASE + "/api/tags"))
				.timeout(Duration.ofSeconds(5)).GET().build();
			HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() == 200) {
				tags = r.body();
				JsonNode models = MAPPER.readTree(tags).path("models");
				for (JsonNode m : models) {
					String name = m.path("name").asText("");
					if (name.equals(LIVE_MODEL) || name.startsWith(LIVE_MODEL + ":")) {
						modelPresent = true;
						break;
					}
				}
			}
		} catch (Exception e) {
			logger.warn("[LIVE-OLLAMA] " + LIVE_OLLAMA_BASE + "/api/tags unreachable: " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		if (!modelPresent) {
			logger.warn("[LIVE-OLLAMA] SKIPPING live embedding test: " + LIVE_MODEL + " not listed at " + LIVE_OLLAMA_BASE
				+ "/api/tags (container down or model not yet pulled). tags=" + tags);
		}
		Assume.assumeTrue("Local Ollama container with " + LIVE_MODEL + " not available at " + LIVE_OLLAMA_BASE, modelPresent);

		EmbeddingUtil eu = new EmbeddingUtil(LLMServiceEnumType.OPENAI_COMPAT, LIVE_OLLAMA_BASE + "/v1/embeddings", null);
		eu.setEmbeddingModel(LIVE_MODEL);
		eu.setEmbeddingDimensions(LIVE_EXPECTED_WIDTH);

		float[] emb = eu.getEmbedding("The quick brown fox jumps over the lazy dog.");
		assertNotNull(emb);
		logger.info("[LIVE-OLLAMA] getEmbedding() returned width " + emb.length);
		assertEquals("nomic-embed-text via /v1/embeddings must return a 768-wide vector", LIVE_EXPECTED_WIDTH, emb.length);
		assertEquals("width guard must record the 768 baseline", LIVE_EXPECTED_WIDTH, eu.getObservedEmbeddingWidth());
	}
}
