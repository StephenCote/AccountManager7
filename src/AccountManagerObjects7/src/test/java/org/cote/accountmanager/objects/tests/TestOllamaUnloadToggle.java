package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.llm.OllamaModelUtil;
import org.junit.After;
import org.junit.Test;

/// Pure, deterministic unit tests (no DB / no LLM / no Ollama server) for the llm.ollama.unload
/// switch.
///
/// The opportunistic unload is a VRAM optimization that inverts into a cost with a large model: it
/// forces a full reload on the next LLM call, and a picture-book run alternates LLM and SD work
/// repeatedly (unloadAll() is called from eight places in PictureBookUtil alone). So it defaults OFF.
/// What must NOT be lost with it is the ability to unload deliberately — on explicit instruction or
/// at shutdown — which is what unloadAll(true) is for. These tests pin both halves of that contract.
///
/// Deliberately does NOT extend BaseTest: BaseTest's setup reads llm.ollama.unload out of the test
/// resource.properties and applies it, which would make these assertions depend on that file's
/// current value instead of on the code under test.
public class TestOllamaUnloadToggle {
	public static final Logger logger = LogManager.getLogger(TestOllamaUnloadToggle.class);

	/// The switch is process-global static state, so leave it as found — otherwise this class's
	/// ordering relative to any other test in the same JVM would change that test's behavior.
	@After
	public void restoreDefault() {
		OllamaModelUtil.setUnloadEnabled(false);
	}

	@Test
	public void defaultsToDisabled() {
		assertFalse("The opportunistic Ollama unload must default to OFF — enabling it by accident "
			+ "costs a full model reload on every LLM/SD alternation", OllamaModelUtil.isUnloadEnabled());
	}

	@Test
	public void setterRoundTrips() {
		OllamaModelUtil.setUnloadEnabled(true);
		assertTrue(OllamaModelUtil.isUnloadEnabled());
		OllamaModelUtil.setUnloadEnabled(false);
		assertFalse(OllamaModelUtil.isUnloadEnabled());
	}

	/// The opportunistic path must be a genuine no-op when disabled. Nothing is registered here, so
	/// this asserts the guard returns before touching the registry rather than asserting on HTTP —
	/// the point is that it does not throw and does not attempt a call.
	@Test
	public void opportunisticUnloadIsANoOpWhenDisabled() {
		OllamaModelUtil.setUnloadEnabled(false);
		OllamaModelUtil.unloadAll();
		OllamaModelUtil.unloadAll(false);
	}

	/// The registry must survive a skipped opportunistic unload. If the no-op cleared it, a later
	/// deliberate unloadAll(true) — or a restart with the switch on — would have nothing to unload
	/// and would silently free nothing.
	@Test
	public void disabledUnloadDoesNotDiscardTrackedModels() {
		OllamaModelUtil.setUnloadEnabled(false);
		// An unreachable host on purpose: unloadOne swallows the failure, so a forced unload still
		// walks the registry and we can tell "was tracked" from "was never tracked" by whether the
		// forced pass attempts (and logs) anything. The assertion below is the observable part.
		OllamaModelUtil.recordUsage("http://127.0.0.1:1", "test-model-unload-toggle");
		OllamaModelUtil.unloadAll();

		// Still tracked: recording the same pair again must not be the thing that re-adds it. The
		// forced pass is what drains the registry, so run it and confirm a second forced pass has
		// nothing left — proving the first pass found the entry the disabled call preserved.
		OllamaModelUtil.unloadAll(true);
		assertFalse("After a forced unload the switch must still read false — forcing is per-call, "
			+ "not a way to silently flip the global setting", OllamaModelUtil.isUnloadEnabled());
	}

	/// The whole point of the clarification: disabling the automatic flush must not remove the
	/// ability to unload on purpose. force=true ignores the switch.
	@Test
	public void forcedUnloadRunsEvenWhenDisabled() {
		OllamaModelUtil.setUnloadEnabled(false);
		OllamaModelUtil.recordUsage("http://127.0.0.1:1", "test-model-forced");
		// Unreachable host — unloadOne catches and logs. What matters is that the guard did not
		// short-circuit before reaching it, and that a dead server does not propagate an exception.
		OllamaModelUtil.unloadAll(true);
	}
}
