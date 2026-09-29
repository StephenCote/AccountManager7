package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure unit test of {@link LlmTestGate}'s route/tier resolution and write-back. Deliberately does NOT
 * extend {@code BaseTest}: no DB, no IOSystem, no network. Every probe is injected via
 * {@link LlmTestGate#setProbes}, and the per-JVM memo is cleared around each case with
 * {@link LlmTestGate#resetForTest()}.
 *
 * <p>Cases mirror the tiering contract in the Objects7 test {@code resource.properties}:
 * LOCAL (container lists both 8B models) -> AZURE (LiteLLM route + LITELLM_LIVE + healthy alias) ->
 * REMOTE; route is LiteLLM when {@code /health/liveliness} answers, else direct; apiKey is written only
 * on the LiteLLM route; the {@code -Dtest.llm.ollama.server} bypass pins a direct route with no probes.
 */
public class TestLlmTestGate {

	private static final String LITELLM = "http://litellm.test:4000";
	private static final String LOCAL = "http://local.test:11435";
	private static final String REMOTE = "http://remote.test:11434";
	private static final String ANALYSIS = "qwen3:8b";
	private static final String PB = "goekdenizguelmez/JOSIEFIED-Qwen3:8b";
	private static final String AZURE = "gpt-5.6-terra";

	private String savedLiveProp;
	private String savedPinProp;

	@Before
	public void setUp() {
		savedLiveProp = System.getProperty("litellm.live");
		savedPinProp = System.getProperty(LlmTestGate.PROP_OLLAMA_SERVER);
		System.clearProperty("litellm.live");
		System.clearProperty(LlmTestGate.PROP_OLLAMA_SERVER);
		LlmTestGate.resetForTest();
	}

	@After
	public void tearDown() {
		LlmTestGate.resetForTest();
		restore("litellm.live", savedLiveProp);
		restore(LlmTestGate.PROP_OLLAMA_SERVER, savedPinProp);
	}

	private static void restore(String key, String value) {
		if (value == null) System.clearProperty(key);
		else System.setProperty(key, value);
	}

	private static Properties baseProps() {
		Properties p = new Properties();
		p.setProperty(LlmTestGate.PROP_LITELLM_SERVER, LITELLM);
		p.setProperty(LlmTestGate.PROP_OLLAMA_LOCAL_SERVER, LOCAL);
		p.setProperty(LlmTestGate.PROP_OLLAMA_SERVER, REMOTE);
		p.setProperty(LlmTestGate.PROP_OLLAMA_MODEL, ANALYSIS);
		p.setProperty(LlmTestGate.PROP_PB_MODEL, PB);
		p.setProperty(LlmTestGate.PROP_LITELLM_AZURE_MODEL, AZURE);
		return p;
	}

	private static List<String> bothModels() {
		return Arrays.asList("nomic-embed-text:latest", ANALYSIS, PB + ":latest");
	}

	/** The LiteLLM master key the gate will have used (env override or the documented default). */
	private static String expectedMasterKey() {
		String env = System.getenv("LITELLM_MASTER_KEY");
		return (env != null && !env.isBlank()) ? env.trim() : LlmTestGate.DEFAULT_MASTER_KEY;
	}

	/// ---- tier: local ----

	@Test
	public void localTier_viaLitellm_usesContainerAliasesAndMasterKey() {
		Properties p = baseProps();
		List<String> healthProbes = new ArrayList<>();
		LlmTestGate.setProbes(
			url -> url.equals(LITELLM + "/health/liveliness"),
			base -> base.equals(LOCAL) ? bothModels() : null,
			alias -> { healthProbes.add(alias); return true; });

		LlmTestGate.resolve(p);

		assertEquals(LlmTestGate.ROUTE_LITELLM, p.getProperty(LlmTestGate.PROP_ROUTE));
		assertEquals(LlmTestGate.TIER_LOCAL, p.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
		assertEquals("direct Ollama URL must be rewritten to the container", LOCAL, p.getProperty(LlmTestGate.PROP_OLLAMA_SERVER));
		assertEquals(LITELLM, p.getProperty(LlmTestGate.PROP_CONNECTION_SERVER));
		assertEquals("OPENAI_COMPAT", p.getProperty(LlmTestGate.PROP_CONNECTION_DIALECT));
		assertEquals("OLLAMA", p.getProperty(LlmTestGate.PROP_CONNECTION_UPSTREAM));
		assertEquals(expectedMasterKey(), p.getProperty(LlmTestGate.PROP_CONNECTION_API_KEY));
		assertEquals(LlmTestGate.ALIAS_LOCAL_ANALYSIS, p.getProperty(LlmTestGate.PROP_MODEL_ANALYSIS));
		assertEquals(LlmTestGate.ALIAS_LOCAL_PB, p.getProperty(LlmTestGate.PROP_MODEL_PB));
		assertTrue("Azure must not be probed when the local tier is usable", healthProbes.isEmpty());
	}

	@Test
	public void localTier_direct_whenLitellmDown_usesRealNamesAndNoApiKey() {
		Properties p = baseProps();
		LlmTestGate.setProbes(url -> false, base -> base.equals(LOCAL) ? bothModels() : null, alias -> true);

		LlmTestGate.resolve(p);

		assertEquals(LlmTestGate.ROUTE_DIRECT, p.getProperty(LlmTestGate.PROP_ROUTE));
		assertEquals(LlmTestGate.TIER_LOCAL, p.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
		assertEquals(LOCAL, p.getProperty(LlmTestGate.PROP_OLLAMA_SERVER));
		assertEquals(LOCAL, p.getProperty(LlmTestGate.PROP_CONNECTION_SERVER));
		assertEquals("OLLAMA", p.getProperty(LlmTestGate.PROP_CONNECTION_DIALECT));
		assertEquals("OLLAMA", p.getProperty(LlmTestGate.PROP_CONNECTION_UPSTREAM));
		assertEquals("apiKey must be EMPTY on the direct route", "", p.getProperty(LlmTestGate.PROP_CONNECTION_API_KEY));
		assertEquals(ANALYSIS, p.getProperty(LlmTestGate.PROP_MODEL_ANALYSIS));
		assertEquals(PB, p.getProperty(LlmTestGate.PROP_MODEL_PB));
	}

	@Test
	public void localTier_rejected_whenContainerLacksPbModel() {
		Properties p = baseProps();
		LlmTestGate.setProbes(url -> true, base -> Arrays.asList(ANALYSIS, "nomic-embed-text:latest"), alias -> true);

		LlmTestGate.resolve(p);

		assertFalse("container with only the analysis model must not be the local tier",
			LlmTestGate.TIER_LOCAL.equals(p.getProperty(LlmTestGate.PROP_RESOLVED_TIER)));
		assertEquals("remote Ollama must be the resolved direct URL", REMOTE, p.getProperty(LlmTestGate.PROP_OLLAMA_SERVER));
	}

	/// ---- tier: azure ----

	@Test
	public void azureTier_onlyWithLitellmLiveAndHealthyProbe() {
		System.setProperty("litellm.live", "1");
		Properties p = baseProps();
		List<String> healthProbes = new ArrayList<>();
		LlmTestGate.setProbes(url -> true, base -> null, alias -> { healthProbes.add(alias); return AZURE.equals(alias); });

		LlmTestGate.resolve(p);

		assertEquals(Arrays.asList(AZURE), healthProbes);
		assertEquals(LlmTestGate.ROUTE_LITELLM, p.getProperty(LlmTestGate.PROP_ROUTE));
		assertEquals(LlmTestGate.TIER_AZURE, p.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
		assertEquals(LITELLM, p.getProperty(LlmTestGate.PROP_CONNECTION_SERVER));
		assertEquals("OPENAI_COMPAT", p.getProperty(LlmTestGate.PROP_CONNECTION_DIALECT));
		assertEquals(AZURE, p.getProperty(LlmTestGate.PROP_MODEL_ANALYSIS));
		assertEquals(LlmTestGate.ALIAS_REMOTE_PB, p.getProperty(LlmTestGate.PROP_MODEL_PB));
		assertEquals("direct Ollama URL stays on the remote box when Azure serves analysis",
			REMOTE, p.getProperty(LlmTestGate.PROP_OLLAMA_SERVER));
	}

	@Test
	public void azureTier_skipped_whenLitellmLiveNotSet() {
		assumeTrue("LITELLM_LIVE=1 is set in this environment; the negative case cannot be exercised",
			!"1".equals(System.getenv("LITELLM_LIVE")));
		Properties p = baseProps();
		AtomicInteger healthProbes = new AtomicInteger();
		LlmTestGate.setProbes(url -> true, base -> null, alias -> { healthProbes.incrementAndGet(); return true; });

		LlmTestGate.resolve(p);

		assertEquals("no paid Azure probe without the LITELLM_LIVE opt-in", 0, healthProbes.get());
		assertEquals(LlmTestGate.ROUTE_LITELLM, p.getProperty(LlmTestGate.PROP_ROUTE));
		assertEquals(LlmTestGate.TIER_REMOTE, p.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
		assertEquals(LlmTestGate.ALIAS_REMOTE_ANALYSIS, p.getProperty(LlmTestGate.PROP_MODEL_ANALYSIS));
		assertEquals(LlmTestGate.ALIAS_REMOTE_PB, p.getProperty(LlmTestGate.PROP_MODEL_PB));
	}

	@Test
	public void azureTier_skipped_whenProbeUnhealthy() {
		System.setProperty("litellm.live", "1");
		Properties p = baseProps();
		LlmTestGate.setProbes(url -> true, base -> null, alias -> false);

		LlmTestGate.resolve(p);

		assertEquals(LlmTestGate.TIER_REMOTE, p.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
		assertEquals(LlmTestGate.ROUTE_LITELLM, p.getProperty(LlmTestGate.PROP_ROUTE));
	}

	@Test
	public void azureTier_notProbed_onDirectRoute() {
		System.setProperty("litellm.live", "1");
		Properties p = baseProps();
		AtomicInteger healthProbes = new AtomicInteger();
		LlmTestGate.setProbes(url -> false, base -> null, alias -> { healthProbes.incrementAndGet(); return true; });

		LlmTestGate.resolve(p);

		assertEquals("Azure is LiteLLM-only; never probed when liveliness failed", 0, healthProbes.get());
		assertEquals(LlmTestGate.ROUTE_DIRECT, p.getProperty(LlmTestGate.PROP_ROUTE));
		assertEquals(LlmTestGate.TIER_REMOTE, p.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
	}

	/// ---- tier: remote / direct ----

	@Test
	public void remoteTier_direct_whenNothingElseAnswers() {
		Properties p = baseProps();
		LlmTestGate.setProbes(url -> false, base -> null, alias -> false);

		LlmTestGate.resolve(p);

		assertEquals(LlmTestGate.ROUTE_DIRECT, p.getProperty(LlmTestGate.PROP_ROUTE));
		assertEquals(LlmTestGate.TIER_REMOTE, p.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
		assertEquals(REMOTE, p.getProperty(LlmTestGate.PROP_OLLAMA_SERVER));
		assertEquals(REMOTE, p.getProperty(LlmTestGate.PROP_CONNECTION_SERVER));
		assertEquals("OLLAMA", p.getProperty(LlmTestGate.PROP_CONNECTION_DIALECT));
		assertEquals("OLLAMA", p.getProperty(LlmTestGate.PROP_CONNECTION_UPSTREAM));
		assertEquals("", p.getProperty(LlmTestGate.PROP_CONNECTION_API_KEY));
		assertEquals(ANALYSIS, p.getProperty(LlmTestGate.PROP_MODEL_ANALYSIS));
		assertEquals(PB, p.getProperty(LlmTestGate.PROP_MODEL_PB));
	}

	@Test
	public void remoteTier_viaLitellm_usesRemoteAliases() {
		Properties p = baseProps();
		LlmTestGate.setProbes(url -> true, base -> null, alias -> false);

		LlmTestGate.resolve(p);

		assertEquals(LlmTestGate.ROUTE_LITELLM, p.getProperty(LlmTestGate.PROP_ROUTE));
		assertEquals(LlmTestGate.TIER_REMOTE, p.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
		assertEquals(REMOTE, p.getProperty(LlmTestGate.PROP_OLLAMA_SERVER));
		assertEquals(LITELLM, p.getProperty(LlmTestGate.PROP_CONNECTION_SERVER));
		assertEquals(expectedMasterKey(), p.getProperty(LlmTestGate.PROP_CONNECTION_API_KEY));
		assertEquals(LlmTestGate.ALIAS_REMOTE_ANALYSIS, p.getProperty(LlmTestGate.PROP_MODEL_ANALYSIS));
		assertEquals(LlmTestGate.ALIAS_REMOTE_PB, p.getProperty(LlmTestGate.PROP_MODEL_PB));
	}

	/// ---- bypass ----

	@Test
	public void bypass_systemPropertyPinsDirectRouteWithoutProbing() {
		String pinned = "http://pinned.test:11434";
		System.setProperty(LlmTestGate.PROP_OLLAMA_SERVER, pinned);
		Properties p = baseProps();
		AtomicInteger probes = new AtomicInteger();
		LlmTestGate.setProbes(
			url -> { probes.incrementAndGet(); return true; },
			base -> { probes.incrementAndGet(); return bothModels(); },
			alias -> { probes.incrementAndGet(); return true; });

		LlmTestGate.resolve(p);

		assertEquals("bypass must not probe anything", 0, probes.get());
		assertEquals(LlmTestGate.ROUTE_DIRECT, p.getProperty(LlmTestGate.PROP_ROUTE));
		assertEquals(LlmTestGate.TIER_REMOTE, p.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
		assertEquals(pinned, p.getProperty(LlmTestGate.PROP_OLLAMA_SERVER));
		assertEquals(pinned, p.getProperty(LlmTestGate.PROP_CONNECTION_SERVER));
		assertEquals("OLLAMA", p.getProperty(LlmTestGate.PROP_CONNECTION_DIALECT));
		assertEquals("", p.getProperty(LlmTestGate.PROP_CONNECTION_API_KEY));
		assertEquals(ANALYSIS, p.getProperty(LlmTestGate.PROP_MODEL_ANALYSIS));
		assertEquals(PB, p.getProperty(LlmTestGate.PROP_MODEL_PB));
	}

	/// ---- memoization ----

	@Test
	public void resolve_probesOnceAndReappliesToFreshProperties() {
		Properties first = baseProps();
		AtomicInteger liveliness = new AtomicInteger();
		AtomicInteger tags = new AtomicInteger();
		LlmTestGate.setProbes(
			url -> { liveliness.incrementAndGet(); return true; },
			base -> { tags.incrementAndGet(); return bothModels(); },
			alias -> true);

		LlmTestGate.resolve(first);
		assertEquals(1, liveliness.get());
		assertEquals(1, tags.get());
		assertNotNull(LlmTestGate.current());

		/// A second Properties instance (another test class's copy) with a stale/mutated model gets the
		/// same answer, and no probe fires again.
		Properties second = baseProps();
		second.setProperty(LlmTestGate.PROP_OLLAMA_SERVER, "http://somebody-changed-it:1");
		LlmTestGate.resolve(second);
		assertEquals("memoized: liveliness must not be re-probed", 1, liveliness.get());
		assertEquals("memoized: /api/tags must not be re-probed", 1, tags.get());
		assertEquals(LlmTestGate.TIER_LOCAL, second.getProperty(LlmTestGate.PROP_RESOLVED_TIER));
		assertEquals(LOCAL, second.getProperty(LlmTestGate.PROP_OLLAMA_SERVER));
		assertEquals(LlmTestGate.ALIAS_LOCAL_PB, second.getProperty(LlmTestGate.PROP_MODEL_PB));
	}

	@Test
	public void resolve_nullPropertiesIsNoOp() {
		LlmTestGate.resolve(null);
		assertNull(LlmTestGate.current());
	}

	/// ---- helpers ----

	@Test
	public void hasModel_toleratesLatestSuffixOnEitherSide() {
		List<String> names = Arrays.asList("qwen3:8b", "goekdenizguelmez/JOSIEFIED-Qwen3:8b:latest", "nomic-embed-text:latest");
		assertTrue(LlmTestGate.hasModel(names, "qwen3:8b"));
		assertTrue(LlmTestGate.hasModel(names, "qwen3:8b:latest"));
		assertTrue(LlmTestGate.hasModel(names, "goekdenizguelmez/JOSIEFIED-Qwen3:8b"));
		assertTrue(LlmTestGate.hasModel(names, "nomic-embed-text"));
		assertFalse("tag must match exactly - qwen3:4b is not qwen3:8b", LlmTestGate.hasModel(names, "qwen3:4b"));
		assertFalse(LlmTestGate.hasModel(names, "qwen3"));
		assertFalse(LlmTestGate.hasModel(null, "qwen3:8b"));
		assertFalse(LlmTestGate.hasModel(names, null));
	}

	@Test
	public void parseTagNames_readsOllamaApiTagsPayload() throws Exception {
		String json = "{\"models\":[{\"name\":\"qwen3:8b\",\"model\":\"qwen3:8b\",\"size\":1},"
			+ "{\"name\":\"goekdenizguelmez/JOSIEFIED-Qwen3:8b\",\"size\":2},{\"size\":3}]}";
		assertEquals(Arrays.asList("qwen3:8b", "goekdenizguelmez/JOSIEFIED-Qwen3:8b"), LlmTestGate.parseTagNames(json));
		assertTrue(LlmTestGate.parseTagNames("{}").isEmpty());
		assertTrue(LlmTestGate.parseTagNames("").isEmpty());
		assertTrue(LlmTestGate.parseTagNames(null).isEmpty());
	}
}
