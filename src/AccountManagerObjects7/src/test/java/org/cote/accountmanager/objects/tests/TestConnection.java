package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/**
 * Connection refactor tests (ConnectionRefactorPlan Part 1.6).
 *
 * Connection info (serverUrl / apiKey / requestTimeout) was lifted out of chatConfig
 * into its own olio.llm.connection model, referenced from chatConfig via the
 * "connection" FK.  These tests are the explicit guard against:
 *  - the connection sub-record not being populated when chatConfig is reloaded
 *  - the encrypted apiKey failing to decrypt off the connection sub-record
 */
@SuppressWarnings("deprecation")
public class TestConnection extends BaseTest {

	private OrganizationContext testOrgContext;
	private BaseRecord testUser;

	@Before
	public void setupConnection() {
		testOrgContext = getTestOrganization("/Development/Connection");
		Factory mf = ioContext.getFactory();
		testUser = mf.getCreateUser(testOrgContext.getAdminUser(), "connectionUser", testOrgContext.getOrganizationId());
		assertNotNull("Test user should not be null", testUser);
	}

	/// Create a (keyless — like a local Ollama endpoint) connection, link it to a chatConfig,
	/// reload the chatConfig via the production query path (getCreateChatConfig), and assert
	/// the connection sub-record is populated (serverUrl + requestTimeout) — the core guard
	/// against the FK coming back as a bare id instead of a populated sub-record.
	@Test
	public void testConnectionPopulated() {
		String testServer = "http://192.168.1.42:11434";

		BaseRecord conn = OlioTestUtil.getCreateConnection(testUser, "Conn-" + UUID.randomUUID().toString().substring(0, 6), testServer, null, 90);
		assertNotNull("Connection should not be null", conn);

		String cfgName = "ConnCfg-" + UUID.randomUUID().toString().substring(0, 6);
		BaseRecord chatConfig = ChatUtil.getCreateChatConfig(testUser, cfgName);
		assertNotNull("ChatConfig should not be null", chatConfig);

		try {
			chatConfig.set("connection", conn);
			chatConfig = IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
			assertNotNull("Updated chatConfig should not be null", chatConfig);
		} catch (Exception e) {
			throw new RuntimeException("Failed to link connection: " + e.getMessage(), e);
		}

		/// Reload via the production path that Chat uses.
		BaseRecord reloaded = ChatUtil.getCreateChatConfig(testUser, cfgName);
		assertNotNull("Reloaded chatConfig should not be null", reloaded);

		BaseRecord reloadedConn = reloaded.get("connection");
		assertNotNull("Reloaded chatConfig.connection should be populated (not just an FK id)", reloadedConn);
		assertEquals("connection.serverUrl should round-trip", testServer, reloadedConn.get("serverUrl"));
		int reqTimeout = reloadedConn.get("requestTimeout");
		assertEquals("connection.requestTimeout should round-trip", 90, reqTimeout);
	}

	/// A keyed connection linked to a chatConfig: reloading the chatConfig leaves the
	/// connection sub-record's apiKey encrypted (vault metadata intentionally not projected
	/// to avoid recursion).  Chat.configureChat reloads the connection directly to decrypt —
	/// this asserts that exact path yields the original key.
	@Test
	public void testKeyedConnectionDecryptsViaDirectReload() {
		String testServer = "https://api.openai.com";
		String testKey = "sk-test-" + UUID.randomUUID().toString().substring(0, 12);

		BaseRecord conn = OlioTestUtil.getCreateConnection(testUser, "KeyedConn-" + UUID.randomUUID().toString().substring(0, 6), testServer, testKey, 120);
		assertNotNull("Connection should not be null", conn);

		String cfgName = "KeyedCfg-" + UUID.randomUUID().toString().substring(0, 6);
		BaseRecord chatConfig = ChatUtil.getCreateChatConfig(testUser, cfgName);
		assertNotNull("ChatConfig should not be null", chatConfig);
		try {
			chatConfig.set("connection", conn);
			IOSystem.getActiveContext().getAccessPoint().update(testUser, chatConfig);
		} catch (Exception e) {
			throw new RuntimeException("Failed to link connection: " + e.getMessage(), e);
		}

		BaseRecord reloaded = ChatUtil.getCreateChatConfig(testUser, cfgName);
		BaseRecord subConn = reloaded.get("connection");
		assertNotNull("connection sub-record should be populated", subConn);

		/// Mirror Chat.configureChat: reload the connection directly to decrypt apiKey.
		BaseRecord fullConn = OlioUtil.getFullRecord(subConn);
		assertNotNull("Direct connection reload should not be null", fullConn);
		assertEquals("connection.apiKey should decrypt to original via direct reload", testKey, fullConn.get("apiKey"));
	}

	/// apiKey survives copyRecord + re-set on the connection sub-record (the pattern
	/// Chat.checkRemote / ChatUtil.getChat use for remote/copied configs).
	@Test
	public void testConnectionApiKeyCopyRecord() {
		String testKey = "sk-test-" + UUID.randomUUID().toString().substring(0, 12);
		BaseRecord conn = OlioTestUtil.getCreateConnection(testUser, "ConnCopy-" + UUID.randomUUID().toString().substring(0, 6), "http://192.168.1.42:11434", testKey, 120);
		assertNotNull("Connection should not be null", conn);

		BaseRecord readBack = OlioUtil.getFullRecord(conn);
		assertNotNull("Read-back connection should not be null", readBack);
		String decryptedKey = readBack.get("apiKey");
		assertEquals("Decrypted apiKey should match original", testKey, decryptedKey);

		try {
			BaseRecord copy = readBack.copyRecord();
			assertNotNull("Copy should not be null", copy);
			/// Vault metadata is lost on copy; re-set apiKey from the decrypted source.
			copy.set("apiKey", (String) readBack.get("apiKey"));
			String copyKey = copy.get("apiKey");
			assertEquals("ApiKey on copy (after re-set from source) should match", testKey, copyKey);
		} catch (Exception e) {
			throw new RuntimeException("copyRecord apiKey pattern failed: " + e.getMessage(), e);
		}
	}
}
