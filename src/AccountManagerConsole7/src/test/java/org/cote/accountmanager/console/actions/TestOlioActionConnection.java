package org.cote.accountmanager.console.actions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Properties;
import java.util.UUID;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOProperties;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.llm.ChatLibraryUtil;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.OrganizationEnumType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/// Exercises OlioAction.resolveConnection against the live test database: the console's
/// -chatConfig path must attach a /Library/Connections system.connection (serverUrl/apiKey live
/// there, not on olio.llm.chatConfig) and an explicit -serverUrl/-apiKey must land on that record.
public class TestOlioActionConnection {
	private static final Logger logger = LogManager.getLogger(TestOlioActionConnection.class);
	/// Sub-org, as the Objects7 connection tests do: /Development itself has no .vault group in am7db,
	/// so the encrypted apiKey cannot round-trip there.
	private static final String ORG_PATH = "/Development/Console";
	private static final String TEST_USER = "console_test_user";

	private IOContext ioContext = null;
	private OrganizationContext orgContext = null;
	private BaseRecord user = null;

	@Before
	public void setup() throws Exception {
		Properties props = new Properties();
		try (InputStream is = ClassLoader.getSystemResourceAsStream("resource.properties")) {
			props.load(is);
		}
		OlioModelNames.use();
		IOFactory.DEFAULT_FILE_BASE = props.getProperty("app.basePath");
		IOProperties ioProps = new IOProperties();
		ioProps.setDataSourceUrl(props.getProperty("test.db.url"));
		ioProps.setDataSourceUserName(props.getProperty("test.db.user"));
		ioProps.setDataSourcePassword(props.getProperty("test.db.password"));
		ioProps.setSchemaCheck(false);
		ioProps.setReset(false);
		ioContext = IOSystem.open(RecordIO.DATABASE, ioProps);
		assertNotNull("IOContext", ioContext);
		orgContext = ioContext.getOrganizationContext(ORG_PATH, OrganizationEnumType.DEVELOPMENT);
		assertNotNull("Organization context", orgContext);
		if(!orgContext.isInitialized()) {
			orgContext.createOrganization();
		}
		user = ioContext.getFactory().getCreateUser(orgContext.getAdminUser(), TEST_USER, orgContext.getOrganizationId());
		assertNotNull("Test user", user);
	}

	@After
	public void teardown() {
		IOSystem.close();
	}

	private CommandLine parse(String... args) throws Exception {
		Options options = new Options();
		options.addOption("chatConfig", true, "");
		new OlioAction().addOptions(options);
		return new DefaultParser().parse(options, args);
	}

	private BaseRecord readConnection(long id) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_CONNECTION, FieldNames.FIELD_ID, id);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID, "serverUrl", "apiKey", "dialect"});
		q.setCache(false);
		return ioContext.getAccessPoint().find(user, q);
	}

	@Test
	public void testCreateUpdateAndAttachConnection() throws Exception {
		String connName = "console-test-" + UUID.randomUUID().toString().substring(0, 8);
		String url1 = "http://127.0.0.1:11435";
		String url2 = "http://127.0.0.1:4000/v1";

		/// 1. Absent connection is created in /Library/Connections with the requested serverUrl.
		OlioAction action = new OlioAction();
		BaseRecord conn = action.resolveConnection(parse("-connection", connName, "-serverUrl", url1), user);
		assertNotNull("Connection should be created", conn);
		long connId = conn.get(FieldNames.FIELD_ID);
		BaseRecord lib = ChatLibraryUtil.getCreateConnectionLibrary(user);
		assertNotNull("Connection library", lib);

		BaseRecord stored = readConnection(connId);
		assertNotNull("Created connection should be readable by the test user", stored);
		assertEquals(connName, stored.get(FieldNames.FIELD_NAME));
		assertEquals("Created in the shared connection library", (long)lib.get(FieldNames.FIELD_ID), (long)stored.get(FieldNames.FIELD_GROUP_ID));
		assertEquals(url1, stored.get("serverUrl"));

		/// 2. Existing connection is reused (same id) and -serverUrl/-apiKey/-serviceType update it.
		BaseRecord again = action.resolveConnection(parse("-connection", connName, "-serverUrl", url2, "-apiKey", "sk-console-test", "-serviceType", "OPENAI_COMPAT"), user);
		assertNotNull(again);
		assertEquals("Should resolve the same record", connId, (long)again.get(FieldNames.FIELD_ID));
		stored = readConnection(connId);
		assertEquals(url2, stored.get("serverUrl"));
		String storedKey = stored.get("apiKey");
		assertEquals("apiKey should decrypt to the value passed on the command line", "sk-console-test", storedKey);
		Object dialect = stored.get("dialect");
		assertEquals("OPENAI_COMPAT", String.valueOf(dialect).toUpperCase());

		/// 3. No -serverUrl/-apiKey leaves the record untouched.
		action.resolveConnection(parse("-connection", connName), user);
		stored = readConnection(connId);
		assertEquals(url2, stored.get("serverUrl"));

		/// 4. The connection attaches to a chat config as the FK the Chat runtime resolves.
		String cfgName = "console-test-cfg-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord cfg = ChatUtil.getCreateChatConfig(user, cfgName);
		assertNotNull("Chat config", cfg);
		assertNull("chatConfig no longer carries serverUrl", cfg.getField("serverUrl"));
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_CHAT_CONFIG, new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "connection"});
		patch.set(FieldNames.FIELD_ID, cfg.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, cfg.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, cfgName);
		patch.set("connection", again);
		assertNotNull("Chat config update", ioContext.getAccessPoint().update(user, patch));

		Query cq = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_ID, cfg.get(FieldNames.FIELD_ID));
		cq.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, "connection"});
		cq.planMost(false);
		cq.setCache(false);
		BaseRecord reread = ioContext.getAccessPoint().find(user, cq);
		assertNotNull(reread);
		BaseRecord attached = reread.get("connection");
		assertNotNull("chatConfig.connection should be populated", attached);
		assertEquals(connId, (long)attached.get(FieldNames.FIELD_ID));

		/// Cleanup what this test created.
		assertTrue(ioContext.getAccessPoint().delete(user, reread));
		assertTrue(ioContext.getAccessPoint().delete(user, stored));
		logger.info("Connection " + connName + " created, updated, attached and removed");
	}
}
