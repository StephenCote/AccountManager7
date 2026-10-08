package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link SecretTestGate} (KI-11): the tracked {@code resource.properties} must carry no credential, and
 * the gate must fill the SAME keys from untracked sources without renaming them. Pure unit test: no DB,
 * no network; env and local-file sources are injected via {@code SecretTestGate.setSources}.
 *
 * <p>No assertion here compares against a real secret and nothing prints a resolved value — the
 * synthetic values below are the only ones that ever appear in output.</p>
 */
public class TestSecretTestGate {

	private static final String K_LLM = "test.llm.openai.authorizationToken";
	private static final String K_EMB = "test.embedding.authorizationToken";
	private static final String K_VOICE = "test.voice.authorizationToken";
	private static final String K_DB = "test.db.password";

	private final Map<String, String> savedSys = new HashMap<>();

	@Before
	public void setUp() {
		for (String k : SecretTestGate.SECRET_KEYS) {
			savedSys.put(k, System.getProperty(k));
			System.clearProperty(k);
		}
		savedSys.put(SecretTestGate.PROP_SECRETS_FILE, System.getProperty(SecretTestGate.PROP_SECRETS_FILE));
		System.clearProperty(SecretTestGate.PROP_SECRETS_FILE);
		SecretTestGate.resetForTest();
	}

	@After
	public void tearDown() {
		SecretTestGate.resetForTest();
		for (Map.Entry<String, String> e : savedSys.entrySet()) {
			if (e.getValue() == null) System.clearProperty(e.getKey());
			else System.setProperty(e.getKey(), e.getValue());
		}
	}

	/** The KI-11 commit guard: the tracked resource.properties on the test classpath has blank tokens. */
	@Test
	public void TestTrackedPropertiesCarryNoToken() throws Exception {
		Properties tracked = new Properties();
		try (InputStream is = ClassLoader.getSystemResourceAsStream("./resource.properties")) {
			assertNotNull("resource.properties must be on the test classpath", is);
			tracked.load(is);
		}
		for (String k : SecretTestGate.TOKEN_KEYS) {
			assertTrue("tracked resource.properties must declare " + k + " (blank)", tracked.containsKey(k));
		}
		assertEquals("resource.properties must not carry a credential; move it to resource.local.properties or an AM7_* env var",
			Collections.emptyList(), SecretTestGate.nonBlankTokenKeys(tracked));
	}

	@Test
	public void TestEnvFillsBlankKeysUnderTheSameName() {
		Properties p = blankProps();
		Map<String, String> env = new HashMap<>();
		env.put(SecretTestGate.envName(K_LLM), "unit-llm-token");
		env.put(SecretTestGate.envName(K_VOICE), "  unit-voice-token  ");
		SecretTestGate.setSources(env, new Properties());

		Map<String, String> applied = SecretTestGate.resolve(p);

		assertEquals("AM7_TEST_LLM_OPENAI_AUTHORIZATIONTOKEN", SecretTestGate.envName(K_LLM));
		assertEquals("unit-llm-token", p.getProperty(K_LLM));
		assertEquals("trimmed", "unit-voice-token", p.getProperty(K_VOICE));
		assertEquals("untouched when no source has it", "", p.getProperty(K_EMB));
		assertEquals("tracked db password kept when no override", "tracked-db", p.getProperty(K_DB));
		assertEquals(SecretTestGate.SOURCE_ENV, applied.get(K_LLM));
		assertEquals(SecretTestGate.SOURCE_ENV, applied.get(K_VOICE));
		assertFalse(applied.containsKey(K_EMB));
		assertFalse(applied.containsKey(K_DB));
		/// No key was renamed or added
		assertEquals(4, p.size());
	}

	@Test
	public void TestDockerAliasServesEmbeddingToken() {
		Properties p = blankProps();
		SecretTestGate.setSources(Collections.singletonMap("EMBEDDING_AUTH_TOKEN", "unit-alias-token"), new Properties());
		Map<String, String> applied = SecretTestGate.resolve(p);
		assertEquals("unit-alias-token", p.getProperty(K_EMB));
		assertEquals(SecretTestGate.SOURCE_ENV, applied.get(K_EMB));
	}

	@Test
	public void TestLocalFileFillsWhatEnvDoesNot() {
		Properties p = blankProps();
		Properties local = new Properties();
		local.setProperty(K_LLM, "file-llm-token");
		local.setProperty(K_EMB, "file-emb-token");
		local.setProperty(K_DB, "file-db");
		Map<String, String> env = Collections.singletonMap(SecretTestGate.envName(K_LLM), "env-llm-token");
		SecretTestGate.setSources(env, local);

		Map<String, String> applied = SecretTestGate.resolve(p);

		assertEquals("env wins over file", "env-llm-token", p.getProperty(K_LLM));
		assertEquals("file-emb-token", p.getProperty(K_EMB));
		assertEquals("file overrides the tracked value", "file-db", p.getProperty(K_DB));
		assertEquals(SecretTestGate.SOURCE_ENV, applied.get(K_LLM));
		assertEquals(SecretTestGate.SOURCE_FILE, applied.get(K_EMB));
		assertEquals(SecretTestGate.SOURCE_FILE, applied.get(K_DB));
		assertNull(applied.get(K_VOICE));
	}

	@Test
	public void TestSystemPropertyWinsOverEverything() {
		Properties p = blankProps();
		System.setProperty(K_EMB, "sys-emb-token");
		Properties local = new Properties();
		local.setProperty(K_EMB, "file-emb-token");
		SecretTestGate.setSources(Collections.singletonMap(SecretTestGate.envName(K_EMB), "env-emb-token"), local);

		Map<String, String> applied = SecretTestGate.resolve(p);

		assertEquals("sys-emb-token", p.getProperty(K_EMB));
		assertEquals(SecretTestGate.SOURCE_SYSTEM, applied.get(K_EMB));
		assertEquals(SecretTestGate.SOURCE_SYSTEM, SecretTestGate.sourceOf(K_EMB, p));
	}

	@Test
	public void TestResolveIsIdempotentAndQuietWhenNothingToDo() {
		Properties p = blankProps();
		SecretTestGate.setSources(new HashMap<>(), new Properties());
		Map<String, String> first = SecretTestGate.resolve(p);
		Map<String, String> second = SecretTestGate.resolve(p);
		assertTrue(first.isEmpty());
		assertTrue(second.isEmpty());
		assertEquals("", p.getProperty(K_LLM));
		assertEquals("tracked-db", p.getProperty(K_DB));
		assertEquals(SecretTestGate.SOURCE_PROPERTIES, SecretTestGate.sourceOf(K_DB, p));
		assertNull(SecretTestGate.sourceOf(K_LLM, p));
		assertTrue(SecretTestGate.resolve(null).isEmpty());
	}

	private static Properties blankProps() {
		Properties p = new Properties();
		p.setProperty(K_LLM, "");
		p.setProperty(K_EMB, "");
		p.setProperty(K_VOICE, "");
		p.setProperty(K_DB, "tracked-db");
		return p;
	}
}
