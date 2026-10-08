package org.cote.accountmanager.objects.tests;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Test-time resolver for the credential-bearing keys in {@code resource.properties} (KI-11).
 *
 * <p>The tracked {@code resource.properties} keeps every {@code *.authorizationToken} key <b>blank</b>.
 * At test setup this gate fills those same keys from an untracked source and writes the value back
 * into the {@code Properties} object every test already reads — the key names never change, so
 * {@code TestAzureChatConnection}, {@code OlioTestUtil}, {@code BaseTest}'s {@code VectorUtil}
 * construction etc. are untouched (the "write back into the existing key" rule in
 * {@code .claude/rules/architecture.md}, same shape as {@link SdTestGate} / {@link LlmTestGate}).</p>
 *
 * <p>Sources, first non-blank wins, per key:</p>
 * <ol>
 *   <li>{@code -D<key>} on the Maven command line (mirrors {@code BaseTest.dbProperty});</li>
 *   <li>environment variable {@code AM7_<KEY>} where {@code <KEY>} is the property key upper-cased with
 *       {@code .} replaced by {@code _} (e.g. {@code AM7_TEST_LLM_OPENAI_AUTHORIZATIONTOKEN}), plus the
 *       Docker-stack alias {@code EMBEDDING_AUTH_TOKEN} for {@code test.embedding.authorizationToken};</li>
 *   <li>a gitignored local properties file: {@code -Dtest.secrets.file=<path>}, else
 *       {@code resource.local.properties} on the test classpath (drop it next to
 *       {@code resource.properties}; ignored by {@code src/.gitignore}), else
 *       {@code ${user.home}/.am7/test-secrets.properties};</li>
 *   <li>the value already in {@code resource.properties} (normally blank).</li>
 * </ol>
 *
 * <p>Values are <b>never logged</b>. The single {@code [SECRET-GATE]} line names the keys that were filled
 * and the source each came from. {@link #resolve(Properties)} is idempotent and cheap, so {@code BaseTest}
 * runs it on every {@code setup()}.</p>
 */
public final class SecretTestGate {

	public static final Logger logger = LogManager.getLogger(SecretTestGate.class);

	/** Property keys this gate manages. Every one is a credential; none is ever printed. */
	public static final List<String> SECRET_KEYS = Collections.unmodifiableList(Arrays.asList(
		"test.llm.openai.authorizationToken",
		"test.embedding.authorizationToken",
		"test.voice.authorizationToken",
		"test.db.password"
	));

	/** Keys that must be blank in the TRACKED properties file (the KI-11 commit guard). */
	public static final List<String> TOKEN_KEYS = Collections.unmodifiableList(Arrays.asList(
		"test.llm.openai.authorizationToken",
		"test.embedding.authorizationToken",
		"test.voice.authorizationToken"
	));

	public static final String ENV_PREFIX = "AM7_";
	public static final String PROP_SECRETS_FILE = "test.secrets.file";
	public static final String LOCAL_RESOURCE = "resource.local.properties";
	public static final String HOME_SECRETS_FILE = ".am7" + File.separator + "test-secrets.properties";

	/** Aliases already used by the Docker stack / .env.example, so one variable serves both. */
	private static final Map<String, String> ENV_ALIASES;
	static {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("test.embedding.authorizationToken", "EMBEDDING_AUTH_TOKEN");
		ENV_ALIASES = Collections.unmodifiableMap(m);
	}

	public static final String SOURCE_SYSTEM = "system";
	public static final String SOURCE_ENV = "env";
	public static final String SOURCE_FILE = "file";
	public static final String SOURCE_PROPERTIES = "properties";

	private static Map<String, String> envOverride = null;
	private static Properties localFileOverride = null;

	private SecretTestGate() {
	}

	/**
	 * Fill every blank/overridable secret key in {@code testProperties} from the untracked sources and
	 * write it back under the same key. Keys with no value anywhere are left exactly as they were.
	 *
	 * @return map of key -> source for the keys that were set by this call (never the values)
	 */
	public static synchronized Map<String, String> resolve(Properties testProperties) {
		Map<String, String> applied = new LinkedHashMap<>();
		if (testProperties == null) {
			return applied;
		}
		Properties local = localFileOverride != null ? localFileOverride : loadLocalFile();
		for (String key : SECRET_KEYS) {
			String sys = trimOrNull(System.getProperty(key));
			if (sys != null) {
				testProperties.setProperty(key, sys);
				applied.put(key, SOURCE_SYSTEM);
				continue;
			}
			String env = fromEnv(key);
			if (env != null) {
				testProperties.setProperty(key, env);
				applied.put(key, SOURCE_ENV);
				continue;
			}
			String file = local != null ? trimOrNull(local.getProperty(key)) : null;
			if (file != null) {
				testProperties.setProperty(key, file);
				applied.put(key, SOURCE_FILE);
			}
		}
		if (!applied.isEmpty()) {
			StringBuilder sb = new StringBuilder("[SECRET-GATE] resolved ");
			boolean first = true;
			for (Map.Entry<String, String> e : applied.entrySet()) {
				if (!first) sb.append(", ");
				sb.append(e.getKey()).append(" <- ").append(e.getValue());
				first = false;
			}
			logger.info(sb.toString());
		}
		return applied;
	}

	/** Where would {@code key} come from right now, without mutating anything? */
	public static String sourceOf(String key, Properties testProperties) {
		if (trimOrNull(System.getProperty(key)) != null) return SOURCE_SYSTEM;
		if (fromEnv(key) != null) return SOURCE_ENV;
		Properties local = localFileOverride != null ? localFileOverride : loadLocalFile();
		if (local != null && trimOrNull(local.getProperty(key)) != null) return SOURCE_FILE;
		if (testProperties != null && trimOrNull(testProperties.getProperty(key)) != null) return SOURCE_PROPERTIES;
		return null;
	}

	/** The environment variable name a property key maps to: {@code AM7_} + upper-case, dots to underscores. */
	public static String envName(String key) {
		return ENV_PREFIX + key.toUpperCase().replace('.', '_');
	}

	/**
	 * Keys in {@link #TOKEN_KEYS} that carry a non-blank value in the given raw properties source — i.e.
	 * the tracked file. Used by the commit guard test; an empty list is the only acceptable result.
	 */
	public static List<String> nonBlankTokenKeys(Properties tracked) {
		List<String> bad = new ArrayList<>();
		if (tracked == null) return bad;
		for (String key : TOKEN_KEYS) {
			if (trimOrNull(tracked.getProperty(key)) != null) {
				bad.add(key);
			}
		}
		return bad;
	}

	/// ---- sources ----

	private static String fromEnv(String key) {
		String v = env(envName(key));
		if (v == null && ENV_ALIASES.containsKey(key)) {
			v = env(ENV_ALIASES.get(key));
		}
		return v;
	}

	private static String env(String name) {
		Map<String, String> src = envOverride != null ? envOverride : System.getenv();
		return trimOrNull(src.get(name));
	}

	private static Properties loadLocalFile() {
		String explicit = trimOrNull(System.getProperty(PROP_SECRETS_FILE));
		if (explicit != null) {
			Properties p = loadFile(new File(explicit));
			if (p == null) {
				logger.warn("[SECRET-GATE] -D" + PROP_SECRETS_FILE + " points at an unreadable file; ignoring");
			}
			return p;
		}
		Properties p = loadResource(LOCAL_RESOURCE);
		if (p != null) return p;
		String home = trimOrNull(System.getProperty("user.home"));
		if (home != null) {
			return loadFile(new File(home, HOME_SECRETS_FILE));
		}
		return null;
	}

	private static Properties loadResource(String name) {
		InputStream is = ClassLoader.getSystemResourceAsStream(name);
		if (is == null) return null;
		try (InputStream in = is) {
			Properties p = new Properties();
			p.load(in);
			return p;
		} catch (IOException e) {
			logger.warn("[SECRET-GATE] failed to read classpath " + name + ": " + e.getClass().getSimpleName());
			return null;
		}
	}

	private static Properties loadFile(File f) {
		if (f == null || !f.isFile() || !f.canRead()) return null;
		try (InputStream in = new FileInputStream(f)) {
			Properties p = new Properties();
			p.load(in);
			return p;
		} catch (IOException e) {
			logger.warn("[SECRET-GATE] failed to read " + f.getName() + ": " + e.getClass().getSimpleName());
			return null;
		}
	}

	private static String trimOrNull(String s) {
		return (s != null && !s.isBlank()) ? s.trim() : null;
	}

	/// ---- test seams (same pattern as LlmTestGate.setProbes/resetForTest) ----

	static synchronized void setSources(Map<String, String> env, Properties localFile) {
		envOverride = env;
		localFileOverride = localFile;
	}

	static synchronized void resetForTest() {
		envOverride = null;
		localFileOverride = null;
	}
}
