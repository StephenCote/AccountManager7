package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.tools.EmbeddingUtil;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/// Real network smoke test: proves AM7 can obtain an embedding from Azure OpenAI
/// using the test.embedding.* settings in resource.properties, in isolation and
/// BEFORE the larger PageIndex integration test.
///
/// This is intentionally a live call to Azure. It uses EmbeddingUtil exactly the
/// way BaseTest wires the VectorUtil (LLMServiceEnumType.OPENAI + the full Azure
/// URL that already carries the deployment + api-version + the api key). No admin
/// user, no record writes; BaseTest.setup() is used only to load resource.properties
/// and register the Olio models (MODEL_OPENAI_INPUT / MODEL_OPENAI_RESPONSE) that
/// EmbeddingUtil.getEmbedding() deserializes into.
///
/// URL construction note (per investigation): the OPENAI branch of
/// EmbeddingUtil.getEmbedding() (EmbeddingUtil.java line 123) POSTs to
/// ClientUtil.getResource(serverUrl) — i.e. serverUrl VERBATIM, with NO path or
/// api-version appended. That is correct for embeddings because test.embedding.server
/// is already a complete URL (deployment path + ?api-version=...). So the URL is NOT
/// munged. A diagnostic raw probe below confirms Azure answers 200 with a valid
/// embedding at that exact URL, isolating any AM7 failure to response handling rather
/// than URL/auth.
public class TestAzureEmbeddingConnection extends BaseTest {

	@Test
	public void TestAzureEmbedding() {
		String type = testProperties.getProperty("test.embedding.type");
		String server = testProperties.getProperty("test.embedding.server");
		String token = testProperties.getProperty("test.embedding.authorizationToken");

		logger.info("[AZURE-EMBED] type=" + type + " server=" + server
			+ " token=" + (token != null ? "present(len=" + token.length() + ")" : "null"));

		assertNotNull("test.embedding.type missing", type);
		assertNotNull("test.embedding.server missing", server);

		/// EXACT final URL EmbeddingUtil POSTs to = serverUrl verbatim (no append).
		logger.info("[AZURE-EMBED] EXACT POST URL (EmbeddingUtil -> ClientUtil.getResource(serverUrl)) = "
			+ server + "  | auth header = api-key (ClientUtil.post line 135)");

		/// ---- DIAGNOSTIC RAW PROBE: same URL, api-key header, capture status + dimension ----
		/// Proves whether URL + key are valid independent of AM7 response parsing.
		int rawStatus = -1;
		int rawDim = -1;
		String rawErr = null;
		try {
			HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
			HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(server))
				.header("Content-Type", "application/json")
				.header("api-key", token)
				.POST(HttpRequest.BodyPublishers.ofString(
					"{\"input\":\"The quick brown fox jumps over the lazy dog.\"}"))
				.timeout(Duration.ofSeconds(60))
				.build();
			HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
			rawStatus = r.statusCode();
			if (rawStatus == 200) {
				JsonNode root = new ObjectMapper().readTree(r.body());
				JsonNode arr = root.path("data").path(0).path("embedding");
				if (arr.isArray()) rawDim = arr.size();
			} else {
				logger.warn("[AZURE-EMBED][raw] non-200 body: " + r.body());
			}
			logger.info("[AZURE-EMBED][raw probe] HTTP " + rawStatus + " embedding dimension = " + rawDim);
		} catch (Exception e) {
			rawErr = e.getClass().getName() + ": " + e.getMessage();
			logger.error("[AZURE-EMBED][raw] exception: " + rawErr, e);
		}

		/// ---- PRIMARY / REAL PATH: EmbeddingUtil.getEmbedding() (the code PageIndex uses) ----
		EmbeddingUtil eu = new EmbeddingUtil(
			LLMServiceEnumType.valueOf(type.toUpperCase()), server, token);
		float[] emb = eu.getEmbedding("The quick brown fox jumps over the lazy dog.");

		assertNotNull("Embedding array was null", emb);
		logger.info("[AZURE-EMBED] EmbeddingUtil.getEmbedding() returned dimension = " + emb.length);

		if (emb.length == 0 && rawStatus == 200 && rawDim > 0) {
			logger.error("[AZURE-EMBED][FINDING] URL and api-key are CORRECT — the raw probe got HTTP 200 "
				+ "with a " + rawDim + "-dim embedding from the exact same URL. But EmbeddingUtil.getEmbedding() "
				+ "returned 0 floats. Root cause: EmbeddingUtil.getEmbedding() OPENAI branch (EmbeddingUtil.java "
				+ "line 123) calls ClientUtil.post(String.class, ...). ClientUtil.post (ClientUtil.java line 143) "
				+ "does JSONUtil.importObject(json, String.class), and JSONUtil.importObject (JSONUtil.java line 96) "
				+ "calls mapper.readValue(json, String.class) — which THROWS on a JSON-object body "
				+ "('Cannot deserialize value of type java.lang.String from Object value'), so post() returns null "
				+ "and getEmbedding() logs 'Response was null'. This is a RESPONSE-DESERIALIZATION bug, NOT a URL "
				+ "or auth problem. Fix: EmbeddingUtil must read the raw JSON string (e.g. ClientUtil.postJSON, "
				+ "which does readEntity(String.class) directly) instead of ClientUtil.post(String.class,...).");
		}

		assertTrue("EmbeddingUtil.getEmbedding() returned an empty embedding (length 0). "
			+ "Raw probe result: HTTP " + rawStatus + ", dimension " + rawDim + ". "
			+ "See [AZURE-EMBED][FINDING] log above for the exact defect location.", emb.length > 0);

		/// Storage-mismatch check for PageIndex/vectors: common.vectorExt.embedding is
		/// maxLength=768, but text-embedding-3-small returns 1536 by default. Surface it.
		int vectorExtMax = 768;
		if (emb.length != vectorExtMax) {
			logger.warn("[AZURE-EMBED] DIMENSION MISMATCH: Azure returned " + emb.length
				+ " floats but common.vectorExt.embedding is maxLength=" + vectorExtMax
				+ ". Storing this vector as-is will overflow/truncate. PageIndex/vector storage "
				+ "must either request a reduced dimension from Azure (dimensions=" + vectorExtMax
				+ ") or the model maxLength must be widened.");
		} else {
			logger.info("[AZURE-EMBED] dimension matches common.vectorExt.embedding maxLength ("
				+ vectorExtMax + ")");
		}
	}
}
