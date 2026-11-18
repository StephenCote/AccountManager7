package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.olio.NarrativeUtil;
import org.junit.Test;

/**
 * Pure-JUnit coverage of {@link NarrativeUtil#getSDActionSettingClause}, the action + setting clause of the
 * character SD prompt ({@code NarrativeUtil.getSDPrompt}, used by both the Swarm and A1111 txt2img builders).
 * No database, no server, no {@code BaseTest}.
 * <p>
 * The setting belongs INSIDE the outer emphasis group with the action: {@code ((sitting in) a spaceship bridge)}.
 * Before 2026-11-18 the group closed before the setting — {@code ((sitting in))  a spaceship bridge} — leaving the
 * setting unweighted outside it.
 */
public class TestSdActionSettingClause {

	@Test
	public void settingIsInsideTheGroupWithTheAction() {
		assertEquals(" He is ((sitting in) Futuristic spaceship bridge)",
			NarrativeUtil.getSDActionSettingClause("He", "", "sitting in", "Futuristic spaceship bridge"));
	}

	@Test
	public void tradeLeadsTheGroup() {
		// getSDPrompt passes the trade with a leading space (" smith"); the clause must not double it.
		assertEquals(" She is (a smith (hammering in) a forge)",
			NarrativeUtil.getSDActionSettingClause("She", " smith", "hammering in", "a forge"));
	}

	@Test
	public void parenthesesBalanceAndNoDoubleSpaces() {
		String c = NarrativeUtil.getSDActionSettingClause("He", " farmer", "resting in", "  an expanse of grass  ");
		assertEquals(" He is (a farmer (resting in) an expanse of grass)", c);
		assertFalse("no double space", c.contains("  "));
		long open = c.chars().filter(ch -> ch == '(').count();
		long close = c.chars().filter(ch -> ch == ')').count();
		assertEquals(open, close);
		assertTrue("old form must be gone", !c.contains("))"));
	}

	@Test
	public void blankPlaceKeepsAWellFormedGroup() {
		assertEquals(" He is ((standing))", NarrativeUtil.getSDActionSettingClause("He", null, "standing", null));
	}
}
