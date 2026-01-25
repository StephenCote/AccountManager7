package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.olio.CombatUtil;
import org.cote.accountmanager.olio.CombatUtil.CombatResult;
import org.cote.accountmanager.olio.CombatUtil.DefenseType;
import org.cote.accountmanager.olio.CriticalEnumType;
import org.cote.accountmanager.olio.RollEnumType;
import org.cote.accountmanager.olio.SavingThrowEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

/**
 * Unit tests for CombatUtil combat system.
 * Tests the olio.txt combat rules:
 * - Fight skill calculation
 * - Dodge and Parry mechanics
 * - Armor Damaging System (ADS)
 * - Critical determination
 * - Saving throws
 */
public class TestCombat extends BaseTest {

	/**
	 * Test CriticalEnumType.fromPercentage() mapping
	 */
	@Test
	public void testCriticalDetermination() {
		logger.info("Test Critical Determination");

		// 0-50%: Regular
		assertEquals(CriticalEnumType.REGULAR, CriticalEnumType.fromPercentage(0));
		assertEquals(CriticalEnumType.REGULAR, CriticalEnumType.fromPercentage(25));
		assertEquals(CriticalEnumType.REGULAR, CriticalEnumType.fromPercentage(50));

		// 51-85%: Double
		assertEquals(CriticalEnumType.DOUBLE, CriticalEnumType.fromPercentage(51));
		assertEquals(CriticalEnumType.DOUBLE, CriticalEnumType.fromPercentage(70));
		assertEquals(CriticalEnumType.DOUBLE, CriticalEnumType.fromPercentage(85));

		// 86-95%: Triple
		assertEquals(CriticalEnumType.TRIPLE, CriticalEnumType.fromPercentage(86));
		assertEquals(CriticalEnumType.TRIPLE, CriticalEnumType.fromPercentage(90));
		assertEquals(CriticalEnumType.TRIPLE, CriticalEnumType.fromPercentage(95));

		// 96-100%: Deadly
		assertEquals(CriticalEnumType.DEADLY, CriticalEnumType.fromPercentage(96));
		assertEquals(CriticalEnumType.DEADLY, CriticalEnumType.fromPercentage(100));

		// Test multipliers
		assertEquals(1.0, CriticalEnumType.REGULAR.getMultiplier(), 0.01);
		assertEquals(2.0, CriticalEnumType.DOUBLE.getMultiplier(), 0.01);
		assertEquals(3.0, CriticalEnumType.TRIPLE.getMultiplier(), 0.01);
		assertEquals(10.0, CriticalEnumType.DEADLY.getMultiplier(), 0.01);

		logger.info("Critical Determination tests passed");
	}

	/**
	 * Test fight skill calculation based on stats
	 */
	@Test
	public void testFightSkillCalculation() throws FieldException, ValueException, ModelNotFoundException {
		logger.info("Test Fight Skill Calculation");

		OlioModelNames.use();

		// Create a test character with known stats
		BaseRecord person = createTestPerson("FightTester", 15, 12, 10, 14, 8, 10, 12);

		int fightSkill = CombatUtil.getFightSkill(person);
		// Fight = (strength + agility + speed) / 3 * 5
		// = (15 + 12 + 10) / 3 * 5 = 37 / 3 * 5 = 12.33 * 5 = 61.65 -> 61
		int expected = (int)(((15 + 12 + 10) / 3.0) * 5.0);
		assertEquals("Fight skill should match formula", expected, fightSkill);

		int dodgeSkill = CombatUtil.getDodgeSkill(person);
		// Dodge = (agility + speed) / 2 * 5
		// = (12 + 10) / 2 * 5 = 11 * 5 = 55
		int expectedDodge = (int)(((12 + 10) / 2.0) * 5.0);
		assertEquals("Dodge skill should match formula", expectedDodge, dodgeSkill);

		int parrySkill = CombatUtil.getParrySkill(person);
		assertEquals("Parry skill equals fight skill", fightSkill, parrySkill);

		logger.info("Fight skill: {}, Dodge skill: {}, Parry skill: {}", fightSkill, dodgeSkill, parrySkill);
		logger.info("Fight Skill Calculation tests passed");
	}

	/**
	 * Test saving throw calculation
	 */
	@Test
	public void testSavingThrowCalculation() throws FieldException, ValueException, ModelNotFoundException {
		logger.info("Test Saving Throw Calculation");

		OlioModelNames.use();

		// Create a test character with known stats
		// Saving throw base = ((strength + health + willpower) / 3) * 5
		BaseRecord person = createTestPerson("SaveTester", 12, 10, 10, 15, 14, 12, 10);

		// Run multiple saving throws to verify the system works
		int successes = 0;
		int iterations = 1000;

		for (int i = 0; i < iterations; i++) {
			RollEnumType result = CombatUtil.rollSavingThrow(person, SavingThrowEnumType.DEATH, 0);
			if (result == RollEnumType.SUCCESS || result == RollEnumType.NATURAL_SUCCESS) {
				successes++;
			}
		}

		// Save percentage should be around ((12+15+12)/3)*5 = (39/3)*5 = 13*5 = 65%
		// But bounded to 5-95, so expect ~65% success rate
		double successRate = (double) successes / iterations;
		logger.info("Saving throw success rate: {}% ({}/{})",
				String.format("%.1f", successRate * 100), successes, iterations);

		// Should be roughly 60-70% (allowing for variance)
		assertTrue("Success rate should be reasonable (40-80%)",
				successRate >= 0.40 && successRate <= 0.80);

		logger.info("Saving Throw tests passed");
	}

	/**
	 * Test Armor Damaging System (ADS)
	 */
	@Test
	public void testArmorDamageSystem() {
		logger.info("Test Armor Damaging System");

		// Test with moderate armor
		int attackSkill = 60;
		int baseDamage = 10;
		int armorHP = 50;
		int armorStress = 8;
		int armorAbsorption = 40;

		// Run multiple iterations to see damage distribution
		int totalTargetDamage = 0;
		int totalArmorDamage = 0;
		int piercedCount = 0;
		int iterations = 1000;

		for (int i = 0; i < iterations; i++) {
			int[] result = CombatUtil.calculateArmorDamage(attackSkill, baseDamage,
					armorHP, armorStress, armorAbsorption);
			totalTargetDamage += result[0];
			totalArmorDamage += result[1];
			if (result[2] == 1) piercedCount++;
		}

		double avgTargetDamage = (double) totalTargetDamage / iterations;
		double avgArmorDamage = (double) totalArmorDamage / iterations;
		double pierceRate = (double) piercedCount / iterations;

		logger.info("ADS Results over {} iterations:", iterations);
		logger.info("  Avg target damage: {}", String.format("%.2f", avgTargetDamage));
		logger.info("  Avg armor damage: {}", String.format("%.2f", avgArmorDamage));
		logger.info("  Pierce rate: {}%", String.format("%.1f", pierceRate * 100));

		// Verify armor provides meaningful protection
		assertTrue("Avg target damage should be less than base damage", avgTargetDamage < baseDamage);
		assertTrue("Some armor damage should occur", avgArmorDamage > 0);

		logger.info("Armor Damaging System tests passed");
	}

	/**
	 * Test parry weapon class restriction
	 */
	@Test
	public void testParryWeaponClass() throws FieldException, ValueException, ModelNotFoundException {
		logger.info("Test Parry Weapon Class Restriction");

		OlioModelNames.use();

		BaseRecord defender = createTestPerson("Defender", 15, 15, 12, 12, 10, 10, 10);

		// Cannot parry a higher class weapon
		RollEnumType result = CombatUtil.rollParry(defender, 50, 2, 4);
		assertEquals("Cannot parry higher class weapon", RollEnumType.FAILURE, result);

		// Can attempt to parry equal or lower class
		int parrySuccesses = 0;
		int iterations = 100;
		for (int i = 0; i < iterations; i++) {
			result = CombatUtil.rollParry(defender, 50, 4, 2);
			if (result == RollEnumType.SUCCESS || result == RollEnumType.NATURAL_SUCCESS) {
				parrySuccesses++;
			}
		}
		assertTrue("Some parries should succeed when weapon class allows", parrySuccesses > 0);

		logger.info("Parry Weapon Class tests passed");
	}

	/**
	 * Test full combat resolution
	 */
	@Test
	public void testCombatResolution() throws FieldException, ValueException, ModelNotFoundException {
		logger.info("Test Full Combat Resolution");

		OlioModelNames.use();

		BaseRecord attacker = createTestPerson("Attacker", 16, 14, 12, 10, 8, 10, 10);
		BaseRecord defender = createTestPerson("Defender", 12, 14, 14, 14, 10, 12, 8);

		int attackerWeaponDamage = 8;
		int attackerSkillBonus = 10;
		int attackerWeaponClass = 3;
		int defenderArmorHP = 40;
		int defenderArmorStress = 6;
		int defenderArmorAbsorption = 30;
		int defenderWeaponClass = 3;

		// Run multiple combat exchanges
		int hitCount = 0;
		int dodgeCount = 0;
		int parryCount = 0;
		int damageDealt = 0;
		int iterations = 1000;

		for (int i = 0; i < iterations; i++) {
			CombatResult result = CombatUtil.resolveCombat(
					attacker, defender,
					attackerWeaponDamage, attackerSkillBonus, attackerWeaponClass,
					defenderArmorHP, defenderArmorStress, defenderArmorAbsorption,
					defenderWeaponClass, false // prefer dodge over parry
			);

			if (result.attackHit) {
				hitCount++;
				damageDealt += result.damageToTarget;
			}
			if (result.defended) {
				if (result.defenseType == DefenseType.DODGE) {
					dodgeCount++;
				} else if (result.defenseType == DefenseType.PARRY) {
					parryCount++;
				}
			}
		}

		double hitRate = (double) hitCount / iterations;
		double dodgeRate = (double) dodgeCount / iterations;
		double avgDamage = hitCount > 0 ? (double) damageDealt / hitCount : 0;

		logger.info("Combat Results over {} iterations:", iterations);
		logger.info("  Hit rate: {}%", String.format("%.1f", hitRate * 100));
		logger.info("  Dodge rate: {}%", String.format("%.1f", dodgeRate * 100));
		logger.info("  Parry rate: {}%", String.format("%.1f", (double) parryCount / iterations * 100));
		logger.info("  Avg damage when hit: {}", String.format("%.2f", avgDamage));

		// Verify reasonable combat results
		assertTrue("Some attacks should hit", hitCount > 0);
		assertTrue("Some attacks should be dodged", dodgeCount > 0);
		// With preferParry=false, we shouldn't see many parries
		assertTrue("Dodge preferred over parry", dodgeCount > parryCount);

		logger.info("Combat Resolution tests passed");
	}

	/**
	 * Test combat with parry preference
	 */
	@Test
	public void testCombatWithParry() throws FieldException, ValueException, ModelNotFoundException {
		logger.info("Test Combat With Parry Preference");

		OlioModelNames.use();

		BaseRecord attacker = createTestPerson("Attacker", 14, 12, 12, 10, 8, 10, 10);
		BaseRecord defender = createTestPerson("Defender", 16, 16, 14, 14, 10, 12, 8);

		int parryCount = 0;
		int dodgeCount = 0;
		int iterations = 500;

		for (int i = 0; i < iterations; i++) {
			CombatResult result = CombatUtil.resolveCombat(
					attacker, defender,
					6, 5, 3,  // weapon stats
					30, 5, 25,  // armor stats
					4, true  // prefer parry, defender has higher weapon class
			);

			if (result.defended) {
				if (result.defenseType == DefenseType.PARRY) {
					parryCount++;
				} else if (result.defenseType == DefenseType.DODGE) {
					dodgeCount++;
				}
			}
		}

		logger.info("With parry preference: Parries={}, Dodges={}", parryCount, dodgeCount);
		// With parry preferred and defender having adequate weapon class
		assertTrue("Parries should occur when preferred", parryCount > 0);

		logger.info("Combat With Parry tests passed");
	}

	/**
	 * Test damage application
	 */
	@Test
	public void testDamageApplication() throws FieldException, ValueException, ModelNotFoundException {
		logger.info("Test Damage Application");

		OlioModelNames.use();

		BaseRecord person = createTestPerson("DamageTester", 10, 10, 10, 15, 10, 10, 10);
		// Set initial health state
		person.set("state.health", 1.0);
		person.set("state.alive", true);
		person.set("state.incapacitated", false);

		// Apply some damage
		CombatUtil.applyDamage(person, 5);
		Double health = person.get("state.health");
		assertTrue("Health should decrease after damage", health < 1.0);
		logger.info("Health after 5 damage: {}", String.format("%.3f", health));

		// Apply more damage
		CombatUtil.applyDamage(person, 20);
		health = person.get("state.health");
		logger.info("Health after additional 20 damage: {}", String.format("%.3f", health));

		// Apply lethal damage
		CombatUtil.applyDamage(person, 100);
		health = person.get("state.health");
		Boolean alive = person.get("state.alive");
		Boolean incap = person.get("state.incapacitated");

		logger.info("After lethal damage - Health: {}, Alive: {}, Incapacitated: {}",
				String.format("%.3f", health), alive, incap);

		assertTrue("Character should be incapacitated or dead after massive damage",
				(incap != null && incap) || (alive != null && !alive));

		logger.info("Damage Application tests passed");
	}

	/**
	 * Test roll percentage distribution
	 */
	@Test
	public void testRollDistribution() {
		logger.info("Test Roll Distribution");

		int[] buckets = new int[10]; // 0-9, 10-19, ..., 90-100
		int iterations = 10000;

		for (int i = 0; i < iterations; i++) {
			int roll = CombatUtil.rollPercentage();
			assertTrue("Roll should be 0-100", roll >= 0 && roll <= 100);
			int bucket = Math.min(roll / 10, 9);
			buckets[bucket]++;
		}

		// Check for reasonable distribution (each bucket should be ~10% +/- 3%)
		for (int i = 0; i < buckets.length; i++) {
			double pct = (double) buckets[i] / iterations;
			assertTrue("Bucket " + i + " should be roughly 10%: " + String.format("%.1f%%", pct * 100),
					pct >= 0.07 && pct <= 0.13);
		}

		logger.info("Roll Distribution tests passed");
	}

	/**
	 * Test critical roll distribution
	 */
	@Test
	public void testCriticalDistribution() {
		logger.info("Test Critical Roll Distribution");

		int regular = 0, doubleCrit = 0, triple = 0, deadly = 0;
		int iterations = 10000;

		for (int i = 0; i < iterations; i++) {
			CriticalEnumType crit = CombatUtil.rollCritical();
			switch (crit) {
				case REGULAR: regular++; break;
				case DOUBLE: doubleCrit++; break;
				case TRIPLE: triple++; break;
				case DEADLY: deadly++; break;
			}
		}

		double regularPct = (double) regular / iterations * 100;
		double doublePct = (double) doubleCrit / iterations * 100;
		double triplePct = (double) triple / iterations * 100;
		double deadlyPct = (double) deadly / iterations * 100;

		logger.info("Critical distribution over {} rolls:", iterations);
		logger.info("  Regular (0-50): {}% (expected ~51%)", String.format("%.1f", regularPct));
		logger.info("  Double (51-85): {}% (expected ~35%)", String.format("%.1f", doublePct));
		logger.info("  Triple (86-95): {}% (expected ~10%)", String.format("%.1f", triplePct));
		logger.info("  Deadly (96-100): {}% (expected ~5%)", String.format("%.1f", deadlyPct));

		// Verify roughly correct distribution (allowing 5% variance)
		assertTrue("Regular should be ~51%", regularPct >= 45 && regularPct <= 57);
		assertTrue("Double should be ~35%", doublePct >= 28 && doublePct <= 42);
		assertTrue("Triple should be ~10%", triplePct >= 5 && triplePct <= 15);
		assertTrue("Deadly should be ~5%", deadlyPct >= 1 && deadlyPct <= 9);

		logger.info("Critical Distribution tests passed");
	}

	/**
	 * Helper method to create a test person with specific stats
	 */
	private BaseRecord createTestPerson(String name, int strength, int agility, int speed,
			int health, int willpower, int endurance, int mentalStrength)
			throws FieldException, ValueException, ModelNotFoundException {

		BaseRecord person = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
		person.set(FieldNames.FIELD_NAME, name);

		// Create statistics record
		BaseRecord stats = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
		stats.set(OlioFieldNames.FIELD_PHYSICAL_STRENGTH, strength);
		stats.set(OlioFieldNames.FIELD_AGILITY, agility);
		stats.set(OlioFieldNames.FIELD_SPEED, speed);
		stats.set(OlioFieldNames.FIELD_HEALTH, health);
		stats.set(OlioFieldNames.FIELD_WILLPOWER, willpower);
		stats.set(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE, endurance);
		stats.set(OlioFieldNames.FIELD_MENTAL_STRENGTH, mentalStrength);
		stats.set(OlioFieldNames.FIELD_WISDOM, 10);

		person.set(OlioFieldNames.FIELD_STATISTICS, stats);

		// Create state record
		BaseRecord state = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATE);
		state.set("health", 1.0);
		state.set("alive", true);
		state.set("awake", true);
		state.set("immobilized", false);
		state.set("incapacitated", false);

		person.set(OlioFieldNames.FIELD_STATE, state);

		return person;
	}
}
