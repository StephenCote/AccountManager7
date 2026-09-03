package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.iso42001.engine.modules.HireModule;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.DocumentUtil;
import org.junit.Assume;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A4 — live-Azure {@code tier=0} ("both tiers") round-trip for the BIAS-HIRE module, run
 * end-to-end as the non-admin {@code isoTester}.
 *
 * <p>Points the run at the live Azure "Terra" endpoint (serviceType=OPENAI Azure deployment
 * scheme, model {@code gpt-5.6-terra}, apiVersion {@code 2025-04-01-preview}) and asserts, via
 * {@link BiasModuleTestBase#runAndAssertBothTiers}, that a {@code tier=0} config produces TWO
 * embedded testResults — one for Tier 1 and one for Tier 2 — each verdicted and persisted, with
 * the raw request/response capture carrying the verbatim prompt.</p>
 *
 * <p><b>Secret handling.</b> The dedicated ISO test DB ({@code am7isotestdb}) does not contain the
 * org-3 Azure records, so the endpoint is provisioned in the ISO org from the git-ignored files
 * under {@code volatile/connections/}. The apiKey is read at runtime, stored via the normal
 * vault-encrypted {@code system.connection.apiKey} path, and is NEVER printed, logged, or copied
 * into any tracked file. If the volatile files are absent the test SKIPS (JUnit Assume) rather
 * than failing.</p>
 */
@Category(LiveTest.class)
public class TestISO42001BiasAzureBothTiers extends BiasModuleTestBase {

	private static final String CONN_REL = "volatile/connections/GPT 5.6 Terra Connection.txt";
	private static final String CHAT_REL = "volatile/connections/GPT 5.6 Terra Chat Config.txt";

	private BaseRecord azureConfigCache;

	/**
	 * Override the base's ollama config with the live Azure "Terra" config, provisioned in the ISO
	 * org from the volatile files. If those files are not present, SKIP the whole test cleanly.
	 */
	@Override
	protected BaseRecord chatConfig() {
		if (azureConfigCache != null) {
			return azureConfigCache;
		}
		File connFile = findVolatileFile(CONN_REL);
		File chatFile = findVolatileFile(CHAT_REL);
		Assume.assumeTrue(
			"Azure 'Terra' volatile connection files not present (" + CONN_REL + " / " + CHAT_REL
				+ "); skipping live-Azure both-tiers round-trip.",
			connFile != null && chatFile != null);

		String serverUrl;
		String apiKey;
		int requestTimeout;
		String model;
		String apiVersion;
		try {
			ObjectMapper om = new ObjectMapper();
			JsonNode conn = om.readTree(new String(Files.readAllBytes(connFile.toPath()), StandardCharsets.UTF_8));
			JsonNode chat = om.readTree(new String(Files.readAllBytes(chatFile.toPath()), StandardCharsets.UTF_8));
			serverUrl = text(conn, "serverUrl");
			apiKey = text(conn, "apiKey");
			requestTimeout = conn.path("requestTimeout").asInt(300);
			model = text(chat, "model");
			apiVersion = text(chat, "apiVersion");
		} catch (Exception e) {
			/// Do NOT include exception detail that could echo file contents.
			throw new RuntimeException("Failed to read/parse Azure Terra volatile connection files");
		}
		/// Guard on the non-secret fields; never assert on / print the key.
		Assume.assumeTrue("Azure Terra connection file missing serverUrl/model/apiVersion",
			serverUrl != null && !serverUrl.isBlank()
				&& model != null && !model.isBlank()
				&& apiVersion != null && !apiVersion.isBlank());
		Assume.assumeTrue("Azure Terra connection file missing apiKey", apiKey != null && !apiKey.isBlank());

		/// system.connection with the vault-encrypted apiKey (vault auto-creates for the ISO org).
		BaseRecord connection = OlioTestUtil.getCreateConnection(
			isoTester, "ISO42001 Azure Terra Connection", serverUrl, apiKey, requestTimeout);
		assertNotNull("Azure system.connection provisioning returned null", connection);

		azureConfigCache = getCreateAzureChatConfig(
			"ISO42001 Azure Terra Chat", connection, model, apiVersion);
		assertNotNull("Azure chatConfig provisioning returned null", azureConfigCache);
		return azureConfigCache;
	}

	/** Idempotent OPENAI (Azure) chatConfig in ~/Chat owned by isoTester, referencing the connection. */
	private BaseRecord getCreateAzureChatConfig(String name, BaseRecord connection, String model, String apiVersion) {
		BaseRecord existing = DocumentUtil.getRecord(isoTester, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
		if (existing != null) {
			return OlioUtil.getFullRecord(existing);
		}
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Chat");
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord cfg = IOSystem.getActiveContext().getFactory()
				.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, isoTester, null, plist);
			cfg.set("serviceType", LLMServiceEnumType.OPENAI);
			cfg.set("apiVersion", apiVersion);
			cfg.set("model", model);
			cfg.set("connection", connection);
			IOSystem.getActiveContext().getAccessPoint().create(isoTester, cfg);
		} catch (Exception e) {
			throw new RuntimeException("Failed to create Azure chatConfig", e);
		}
		BaseRecord created = DocumentUtil.getRecord(isoTester, OlioModelNames.MODEL_CHAT_CONFIG, name, "~/Chat");
		return created != null ? OlioUtil.getFullRecord(created) : null;
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return (v == null || v.isNull()) ? null : v.asText();
	}

	/** Walk up from the working dir to locate a git-ignored volatile file at the repo root. */
	private static File findVolatileFile(String rel) {
		File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
		for (int i = 0; i < 8 && dir != null; i++) {
			File cand = new File(dir, rel);
			if (cand.isFile()) {
				return cand;
			}
			dir = dir.getParentFile();
		}
		return null;
	}

	/**
	 * tier=0 config against the live Azure endpoint: assert BOTH tiers execute and are scored
	 * (2 embedded results, tiers 1 and 2), with the verbatim prompt captured in the raw log.
	 */
	@Test
	public void testAzureBiasBothTiers() {
		/// Trigger provisioning (and any skip) before building the config.
		BaseRecord cfg = chatConfig();
		assertNotNull("Azure chatConfig is null", cfg);

		BaseRecord tc = createTestConfig(PER_GROUP, 0 /* both tiers */, SEED, null);
		runAndAssertBothTiers(new HireModule(), tc, SEED, "Senior Software Engineer");
	}
}
