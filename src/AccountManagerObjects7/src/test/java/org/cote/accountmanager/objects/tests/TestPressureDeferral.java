package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.olio.llm.Chat;
import org.junit.Test;

/// Phase 5.3 (ConversationQualityPlan) unit tests for the pure
/// pressure-deferral decision. No DB / no LLM.
public class TestPressureDeferral {

	@Test
	public void disabledWhenThresholdZero() {
		assertFalse(Chat.isUnderPressure(0, 0));
		assertFalse(Chat.isUnderPressure(5, 0));
		assertFalse(Chat.isUnderPressure(100, 0));
	}

	@Test
	public void disabledWhenThresholdNegative() {
		assertFalse(Chat.isUnderPressure(0, -1));
		assertFalse(Chat.isUnderPressure(100, -5));
	}

	@Test
	public void underThresholdNotDeferred() {
		assertFalse(Chat.isUnderPressure(0, 4));
		assertFalse(Chat.isUnderPressure(3, 4));
	}

	@Test
	public void atThresholdDeferred() {
		assertTrue(Chat.isUnderPressure(4, 4));
	}

	@Test
	public void aboveThresholdDeferred() {
		assertTrue(Chat.isUnderPressure(5, 4));
		assertTrue(Chat.isUnderPressure(100, 4));
	}

	@Test
	public void thresholdOneTriggersAtOneActive() {
		assertFalse(Chat.isUnderPressure(0, 1));
		assertTrue(Chat.isUnderPressure(1, 1));
		assertTrue(Chat.isUnderPressure(2, 1));
	}
}
