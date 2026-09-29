package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.cote.accountmanager.olio.llm.LLMServiceEnumType;
import org.cote.rest.config.RestServiceEventListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@code RestServiceEventListener.warnOnEmbeddingTypeUrlMismatch(type, url)} —
 * the boot-time check that {@code embedding.type} (boot-pinned from web.xml) and the EFFECTIVE
 * {@code embedding.server} URL (DB-backed {@code system.connection}, web.xml only as fallback)
 * agree in shape.
 *
 * <p>Pure static method: no Tomcat, no DB, no HTTP. WARNs are captured with a test appender on the
 * listener's own logger, so the assertions are on what an operator would actually see in the boot
 * log, not on a return value.
 *
 * <p>Motivating case (2026-09-29, am7test): {@code am7-docker-up --ollama} exported
 * {@code EMBEDDING_TYPE=openai_compat}, but a stored {@code embedding} connection still pointed at
 * an Azure deployment URL. OPENAI_COMPAT sends no {@code dimensions}, so Azure text-embedding-3-small
 * returned 1536 against a 768 schema, the startup probe passed, and nothing said why.
 */
public class TestEmbeddingTypeUrlMismatchWarning {

	private static final String AZURE = "https://example.openai.azure.com/openai/deployments/text-embedding-3-small/embeddings?api-version=2023-05-15";
	private static final String OLLAMA = "http://ollama:11434/v1/embeddings";
	private static final String LITELLM = "http://litellm:4000/v1/embeddings";
	private static final String LOCAL = "http://embed:5000";

	private final List<String> warnings = new CopyOnWriteArrayList<>();
	private Logger listenerLogger;
	private Level previousLevel;
	private AbstractAppender appender;

	@Before
	public void attachAppender() {
		listenerLogger = (Logger) LogManager.getLogger(RestServiceEventListener.class);
		appender = new AbstractAppender("embedding-mismatch-capture", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
					warnings.add(event.getMessage().getFormattedMessage());
				}
			}
		};
		appender.start();
		listenerLogger.addAppender(appender);
		/// Make sure WARN is not filtered out by whatever level the test log4j2 config sets.
		previousLevel = listenerLogger.getLevel();
		listenerLogger.setLevel(Level.WARN);
	}

	@After
	public void detachAppender() {
		listenerLogger.removeAppender(appender);
		appender.stop();
		listenerLogger.setLevel(previousLevel);
	}

	private void warn(LLMServiceEnumType type, String url) {
		warnings.clear();
		RestServiceEventListener.warnOnEmbeddingTypeUrlMismatch(type, url);
	}

	private String only() {
		assertEquals("expected exactly ONE boot WARN, got: " + warnings, 1, warnings.size());
		return warnings.get(0);
	}

	// ---- agreeing shapes: silent ----

	@Test
	public void TestAgreeingShapesAreSilent() throws Exception {
		warn(LLMServiceEnumType.OPENAI_COMPAT, OLLAMA);
		assertTrue("openai_compat + /v1/embeddings must be silent: " + warnings, warnings.isEmpty());
		warn(LLMServiceEnumType.OPENAI_COMPAT, LITELLM);
		assertTrue(warnings.isEmpty());
		warn(LLMServiceEnumType.OPENAI, AZURE);
		assertTrue("openai + Azure deployment URL must be silent: " + warnings, warnings.isEmpty());
		warn(LLMServiceEnumType.LOCAL, LOCAL);
		assertTrue("local + host:port must be silent: " + warnings, warnings.isEmpty());
		warn(LLMServiceEnumType.LOCAL, LOCAL + "/");
		assertTrue(warnings.isEmpty());
	}

	@Test
	public void TestNullOrBlankInputsAreSilentAndNeverThrow() throws Exception {
		warn(null, OLLAMA);
		assertTrue(warnings.isEmpty());
		warn(LLMServiceEnumType.OPENAI_COMPAT, null);
		assertTrue(warnings.isEmpty());
		warn(LLMServiceEnumType.OPENAI_COMPAT, "   ");
		assertTrue(warnings.isEmpty());
	}

	// ---- disagreeing shapes: exactly one WARN, naming the problem ----

	@Test
	public void TestLocalTypeWithOpenAiUrlWarns() throws Exception {
		warn(LLMServiceEnumType.LOCAL, OLLAMA);
		String w = only();
		assertTrue(w, w.contains("embedding.type=LOCAL") && w.contains("/generate_embedding"));

		warn(LLMServiceEnumType.LOCAL, AZURE);
		w = only();
		assertTrue(w, w.contains("embedding.type=LOCAL") && w.contains("/generate_embedding"));
	}

	@Test
	public void TestOpenAiShapedTypeWithBareHostWarns() throws Exception {
		warn(LLMServiceEnumType.OPENAI_COMPAT, LOCAL);
		String w = only();
		assertTrue(w, w.contains("embedding.type=OPENAI_COMPAT") && w.contains("has no path") && w.contains("/v1/embeddings"));

		warn(LLMServiceEnumType.OPENAI, LOCAL);
		w = only();
		assertTrue(w, w.contains("embedding.type=OPENAI") && w.contains("has no path") && w.contains("/openai/deployments/"));
	}

	/** The live 2026-09-29 case: --ollama env says openai_compat, stored connection still Azure. */
	@Test
	public void TestOpenAiCompatWithAzureDeploymentUrlWarns() throws Exception {
		warn(LLMServiceEnumType.OPENAI_COMPAT, AZURE);
		String w = only();
		assertTrue(w, w.contains("embedding.type=OPENAI_COMPAT"));
		assertTrue("must say it is an Azure deployment URL: " + w, w.contains("Azure OpenAI deployment URL"));
		assertTrue("must explain the width consequence (no dimensions -> native width): " + w, w.contains("dimensions") && w.contains("1536"));
		assertTrue("must point at the DB-backed override as the likely cause: " + w, w.contains("system.connection"));
	}

	@Test
	public void TestOpenAiWithCompatUrlWarns() throws Exception {
		warn(LLMServiceEnumType.OPENAI, OLLAMA);
		String w = only();
		assertTrue(w, w.contains("embedding.type=OPENAI") && w.contains("/v1/embeddings"));
		assertTrue("must name the api-key vs Bearer consequence: " + w, w.contains("api-key") && w.contains("Bearer"));
		assertTrue("must recommend openai_compat + model: " + w, w.contains("openai_compat") && w.contains("embedding.model"));
	}

	@Test
	public void TestUnparseableUrlWarnsOnceAndDoesNotThrow() throws Exception {
		warn(LLMServiceEnumType.OPENAI_COMPAT, "http://bad host with spaces/v1/embeddings");
		String w = only();
		assertTrue(w, w.contains("not a parseable URL"));
	}
}
