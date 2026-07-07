package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.cote.accountmanager.util.ClientUtil;
import org.junit.Test;

/// Real network smoke test: proves AM7 can talk to Azure OpenAI chat/completions
/// using the test.llm.openai.* settings in resource.properties, in isolation and
/// BEFORE the larger PageIndex integration test.
///
/// This exercises the EXACT transport the production Chat class uses:
///   - the Azure URL is built with the same formula as Chat.getServiceUrl():
///       serverUrl + "/openai/deployments/" + model + "/chat/completions?api-version=" + version
///   - the request is sent via ClientUtil.postToRecordAndStream(url, token, body),
///     which is the exact method Chat.chatInternal() (Chat.java line 3930) calls.
///
/// ClientUtil.postToRecordAndStream sends the credential as "Authorization: Bearer <token>"
/// (ClientUtil.java lines 228-230). Azure OpenAI API keys must be sent as the "api-key"
/// header instead; Bearer is only valid for Entra ID / AAD OAuth tokens. So this test
/// also runs a diagnostic "api-key" probe against the same URL to determine, precisely,
/// whether a failure is a bad key, a wrong URL (404), or a Bearer-vs-api-key auth mismatch
/// (401 on Bearer but 200 on api-key).
///
/// No admin user, no record writes; BaseTest.setup() is used only to load
/// resource.properties.
public class TestAzureChatConnection extends BaseTest {

	private static final Pattern CONTENT = Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

	private String buildAzureChatUrl(String server, String model, String version) {
		/// Mirrors Chat.getServiceUrl() for the OPENAI branch.
		return server + "/openai/deployments/" + model + "/chat/completions"
			+ (version != null ? "?api-version=" + version : "");
	}

	private String extractContent(String rawBody) {
		StringBuilder sb = new StringBuilder();
		Matcher m = CONTENT.matcher(rawBody);
		while (m.find()) {
			sb.append(m.group(1));
		}
		return sb.toString();
	}

	@Test
	public void TestAzureChat() {
		String server = testProperties.getProperty("test.llm.openai.server");
		String model = testProperties.getProperty("test.llm.openai.model");
		String version = testProperties.getProperty("test.llm.openai.version");
		String token = testProperties.getProperty("test.llm.openai.authorizationToken");

		assertNotNull("test.llm.openai.server missing", server);
		assertNotNull("test.llm.openai.model missing", model);
		assertNotNull("test.llm.openai.authorizationToken missing", token);

		String url = buildAzureChatUrl(server, model, version);
		logger.info("[AZURE-CHAT] url=" + url + " token=present(len=" + token.length() + ")");

		String bearerBody = "{\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word: pong\"}],\"stream\":true}";

		/// ---- PRIMARY / REAL PATH: exactly what Chat.chatInternal() does (Bearer) ----
		int bearerStatus = -1;
		String bearerCompletion = "";
		String bearerError = null;
		try {
			CompletableFuture<HttpResponse<Stream<String>>> future =
				ClientUtil.postToRecordAndStream(url, token, bearerBody);
			HttpResponse<Stream<String>> resp = future.get(60, TimeUnit.SECONDS);
			bearerStatus = resp.statusCode();
			StringBuilder body = new StringBuilder();
			Iterator<String> it = resp.body().iterator();
			while (it.hasNext()) {
				String line = it.next();
				body.append(line).append("\n");
				if (line.startsWith("data: ")) {
					String data = line.substring(6).trim();
					if (!"[DONE]".equals(data)) {
						bearerCompletion += extractContent(data);
					}
				}
			}
			if (bearerStatus == 200 && bearerCompletion.isEmpty()) {
				/// Non-SSE body (e.g. error JSON, or a non-streamed response): scrape content.
				bearerCompletion = extractContent(body.toString());
			}
			logger.info("[AZURE-CHAT][bearer] HTTP " + bearerStatus
				+ " completion=\"" + bearerCompletion + "\"");
			if (bearerStatus != 200) {
				logger.warn("[AZURE-CHAT][bearer] non-200 body: " + body.toString());
			}
		} catch (Exception e) {
			bearerError = e.getClass().getName() + ": " + e.getMessage();
			logger.error("[AZURE-CHAT][bearer] exception: " + bearerError, e);
		}

		/// ---- DIAGNOSTIC PROBE: same URL, "api-key" header (Azure's expected scheme) ----
		/// Not the production path — run only to pinpoint a Bearer-vs-api-key mismatch.
		int apiKeyStatus = -1;
		String apiKeyCompletion = "";
		String apiKeyError = null;
		String apiKeyBody = "{\"messages\":[{\"role\":\"user\",\"content\":\"Reply with the single word: pong\"}]}";
		try {
			HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
			HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("Content-Type", "application/json")
				.header("api-key", token)
				.POST(HttpRequest.BodyPublishers.ofString(apiKeyBody))
				.timeout(Duration.ofSeconds(60))
				.build();
			HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
			apiKeyStatus = r.statusCode();
			apiKeyCompletion = extractContent(r.body());
			logger.info("[AZURE-CHAT][api-key] HTTP " + apiKeyStatus
				+ " completion=\"" + apiKeyCompletion + "\"");
			if (apiKeyStatus != 200) {
				logger.warn("[AZURE-CHAT][api-key] non-200 body: " + r.body());
			}
		} catch (Exception e) {
			apiKeyError = e.getClass().getName() + ": " + e.getMessage();
			logger.error("[AZURE-CHAT][api-key] exception: " + apiKeyError, e);
		}

		/// Diagnostic summary so the failure reason is unambiguous in the console.
		logger.info("[AZURE-CHAT][SUMMARY] production-path(Bearer via ClientUtil.postToRecordAndStream)="
			+ (bearerError != null ? "EXC " + bearerError : "HTTP " + bearerStatus)
			+ " | diagnostic(api-key)="
			+ (apiKeyError != null ? "EXC " + apiKeyError : "HTTP " + apiKeyStatus));

		if (bearerStatus != 200 && apiKeyStatus == 200) {
			logger.error("[AZURE-CHAT][FINDING] The production Chat transport FAILS against Azure but "
				+ "the api-key probe SUCCEEDS. Root cause: ClientUtil.postToRecordAndStream() "
				+ "(ClientUtil.java ~lines 228-230) sends 'Authorization: Bearer <token>', but Azure "
				+ "OpenAI requires the 'api-key: <token>' header for API keys. Chat.chatInternal() "
				+ "(Chat.java line 3930) uses postToRecordAndStream, and Chat.getServiceUrl() "
				+ "(Chat.java line 4236) builds the Azure deployment URL — so the URL is correct but "
				+ "the auth header is wrong for Azure. Fix needed in ClientUtil.postToRecordAndStream "
				+ "(add api-key support) or in the Chat call site.");
		}

		/// The assertion is on the REAL production path only. Do not pass on the
		/// diagnostic probe — that would hide a genuine AM7 Azure-auth defect.
		assertTrue("Production chat transport (Bearer via ClientUtil.postToRecordAndStream) did not "
			+ "return HTTP 200 from Azure. Got: "
			+ (bearerError != null ? "exception " + bearerError : "HTTP " + bearerStatus)
			+ ". See [AZURE-CHAT][SUMMARY]/[FINDING] logs above for the exact cause.",
			bearerStatus == 200);
		assertTrue("Azure chat returned HTTP 200 but no completion text was parsed from the stream.",
			bearerCompletion != null && !bearerCompletion.trim().isEmpty());

		logger.info("[AZURE-CHAT] PASS — Azure returned: \"" + bearerCompletion.trim() + "\"");
	}
}
