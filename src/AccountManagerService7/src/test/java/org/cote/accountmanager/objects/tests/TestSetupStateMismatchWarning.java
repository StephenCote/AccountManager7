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
import org.cote.rest.config.RestServiceEventListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@code RestServiceEventListener.warnOnSetupStateMismatch(storeSetupDone, dbSetupComplete, storePath)} —
 * the boot-time check that the store's {@code .setup.done} and the database's setup latch agree.
 *
 * <p>Pure static method: no Tomcat, no DB, no HTTP. ERRORs are captured with a test appender on the
 * listener's own logger, so the assertions are on what an operator would actually see in the boot log.
 *
 * <p>Motivating case (2026-10-23, am7test): the am7-pg data volume went missing, compose created an
 * empty one, and the stack came up with {@code .setup.done} present (so no setup token) while
 * {@code /rest/setup/state} said {@code initialized:false} — the UI routed to /setup and nothing in the
 * log said why.
 */
public class TestSetupStateMismatchWarning {

	private static final String STORE = "/data/am7/store";

	private final List<String> errors = new CopyOnWriteArrayList<>();
	private Logger listenerLogger;
	private Level previousLevel;
	private AbstractAppender appender;

	@Before
	public void attachAppender() {
		listenerLogger = (Logger) LogManager.getLogger(RestServiceEventListener.class);
		appender = new AbstractAppender("setup-mismatch-capture", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
					errors.add(event.getMessage().getFormattedMessage());
				}
			}
		};
		appender.start();
		listenerLogger.addAppender(appender);
		previousLevel = listenerLogger.getLevel();
		listenerLogger.setLevel(Level.WARN);
	}

	@After
	public void detachAppender() {
		listenerLogger.removeAppender(appender);
		appender.stop();
		listenerLogger.setLevel(previousLevel);
	}

	private void check(boolean storeSetupDone, boolean dbSetupComplete) {
		errors.clear();
		RestServiceEventListener.warnOnSetupStateMismatch(storeSetupDone, dbSetupComplete, STORE);
	}

	@Test
	public void TestAgreeingStatesAreSilent() throws Exception {
		check(true, true);
		assertTrue("configured store + configured DB must be silent: " + errors, errors.isEmpty());
		check(false, false);
		assertTrue("first run (neither) must be silent: " + errors, errors.isEmpty());
	}

	/** Bare Tomcat and pre-setup-page stores never get .setup.done; a configured DB without it is normal. */
	@Test
	public void TestConfiguredDbWithoutSentinelIsSilent() throws Exception {
		check(false, true);
		assertTrue("latch closed + no .setup.done must be silent: " + errors, errors.isEmpty());
	}

	/** The live 2026-10-23 case: store says configured, DB is empty. */
	@Test
	public void TestSentinelWithUnconfiguredDbErrors() throws Exception {
		check(true, false);
		assertEquals("expected exactly ONE boot ERROR, got: " + errors, 1, errors.size());
		String e = errors.get(0);
		assertTrue("must name the sentinel path: " + e, e.contains(STORE + "/.setup.done"));
		assertTrue("must say the DB has no marker/credential: " + e, e.contains("no setup marker") && e.contains("administrator credential"));
		assertTrue("must name the likely cause: " + e, e.contains("replaced or re-initialized"));
		assertTrue("must explain why /setup cannot be finished: " + e, e.contains("no token"));
		assertTrue("must point at the matched-pair reset: " + e, e.contains("BOTH the database and the store"));
	}
}
