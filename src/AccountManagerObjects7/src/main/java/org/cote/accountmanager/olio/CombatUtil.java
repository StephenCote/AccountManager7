package org.cote.accountmanager.olio;

import java.security.SecureRandom;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/**
 * Combat utility class implementing the olio combat rules from olio.txt:
 *
 * Fight:
 *   - Roll to hit (fight/weapon skill)
 *   - Roll to dodge (agility + speed) or parry (fight/weapon skill)
 *   - If hit and not dodged/parried, go to Armor Damaging System (ADS)
 *
 * Parry:
 *   - (skill % - attack) = ParryModifier (PM)
 *   - (ParrySkill - PM) = Minimum percent to parry
 *
 * Armor Damaging System (ADS):
 *   - (a) total armor hit points
 *   - (b) armor stress point: number needed to pierce armor
 *   - (c) armor absorption percentage
 *   - (d) effective attack skill = (fight/weapon skill - absorption)
 *   - (e) damage: 5% - (c) = target+armor damaged; (c) to skill = armor only; above skill = miss
 *
 * Critical Determination:
 *   - 0-50%: Regular outcome
 *   - 51-85%: Double outcome
 *   - 86-95%: Triple outcome
 *   - 96-100%: Deadly outcome
 *
 * Saving Throws:
 *   - ((strength + health + willpower) / 3) x 5 = Save percentage
 */
public class CombatUtil {
	public static final Logger logger = LogManager.getLogger(CombatUtil.class);
	private static final SecureRandom rand = new SecureRandom();

	// Combat skill constants
	public static final int DEFAULT_FIGHT_SKILL = 50;   // Base fight skill percentage
	public static final int DEFAULT_WEAPON_SKILL = 40;  // Base weapon skill percentage
	public static final double DODGE_STAT_DIVISOR = 2.0; // (agility + speed) / 2

	// Armor constants
	public static final double ARMOR_CRITICAL_THRESHOLD = 0.05; // 5% threshold for critical armor penetration

	// Damage constants
	public static final int BASE_UNARMED_DAMAGE = 2;
	public static final int BASE_WEAPON_DAMAGE = 5;

	/**
	 * Combat result record containing all combat roll information
	 */
	public static class CombatResult {
		public boolean attackHit = false;
		public boolean defended = false;
		public DefenseType defenseType = DefenseType.NONE;
		public boolean armorPierced = false;
		public int damageToTarget = 0;
		public int damageToArmor = 0;
		public CriticalEnumType criticalLevel = CriticalEnumType.REGULAR;
		public RollEnumType attackRoll = RollEnumType.UNKNOWN;
		public RollEnumType defenseRoll = RollEnumType.UNKNOWN;
		public String description = "";

		@Override
		public String toString() {
			return "CombatResult{hit=" + attackHit + ", defended=" + defended +
				   ", defense=" + defenseType + ", pierced=" + armorPierced +
				   ", dmgTarget=" + damageToTarget + ", dmgArmor=" + damageToArmor +
				   ", critical=" + criticalLevel + "}";
		}
	}

	public enum DefenseType {
		NONE,
		DODGE,
		PARRY,
		BLOCK
	}

	/**
	 * Roll percentage (0-100)
	 */
	public static int rollPercentage() {
		return rand.nextInt(101);
	}

	/**
	 * Get fight skill for a character.
	 * Fight skill is based on: (physicalStrength + agility + speed) / 3 * 5
	 */
	public static int getFightSkill(BaseRecord person) {
		int strength = getStatSafe(person, OlioFieldNames.FIELD_PHYSICAL_STRENGTH);
		int agility = getStatSafe(person, OlioFieldNames.FIELD_AGILITY);
		int speed = getStatSafe(person, OlioFieldNames.FIELD_SPEED);

		if (strength == 0 && agility == 0 && speed == 0) {
			return DEFAULT_FIGHT_SKILL;
		}

		// Average of combat stats * 5 to get percentage
		return (int) (((strength + agility + speed) / 3.0) * 5.0);
	}

	/**
	 * Get dodge skill for a character.
	 * Dodge skill is based on: (agility + speed) / 2 * 5
	 */
	public static int getDodgeSkill(BaseRecord person) {
		int agility = getStatSafe(person, OlioFieldNames.FIELD_AGILITY);
		int speed = getStatSafe(person, OlioFieldNames.FIELD_SPEED);

		if (agility == 0 && speed == 0) {
			return 0;
		}

		return (int) (((agility + speed) / DODGE_STAT_DIVISOR) * 5.0);
	}

	/**
	 * Get parry skill for a character.
	 * Parry skill equals fight skill (can parry equal or lesser attacks)
	 */
	public static int getParrySkill(BaseRecord person) {
		return getFightSkill(person);
	}

	/**
	 * Roll to hit - attacker attempts to land an attack
	 * @param attacker The attacking character
	 * @param weaponSkillBonus Additional skill from weapon proficiency
	 * @return RollEnumType indicating success/failure
	 */
	public static RollEnumType rollToHit(BaseRecord attacker, int weaponSkillBonus) {
		int skill = getFightSkill(attacker) + weaponSkillBonus;
		skill = Math.min(skill, 95); // Cap at 95%

		int roll = rollPercentage();

		// Natural 1 (or 0-1 range) is always a miss
		if (roll <= 1) {
			return RollEnumType.CATASTROPHIC_FAILURE;
		}
		// Natural 99-100 is always a hit
		if (roll >= 99) {
			return RollEnumType.NATURAL_SUCCESS;
		}

		if (roll <= skill) {
			return RollEnumType.SUCCESS;
		}
		return RollEnumType.FAILURE;
	}

	/**
	 * Roll to dodge - defender attempts to evade an attack
	 * @param defender The defending character
	 * @param attackSkill The attacker's effective skill (for difficulty modifier)
	 * @return RollEnumType indicating success/failure
	 */
	public static RollEnumType rollDodge(BaseRecord defender, int attackSkill) {
		int dodgeSkill = getDodgeSkill(defender);

		// Apply mobility modifiers
		Boolean immobile = defender.get("state.immobilized");
		Boolean incap = defender.get("state.incapacitated");
		Boolean awake = defender.get("state.awake");

		if (immobile != null && immobile) {
			return RollEnumType.FAILURE; // Cannot dodge while immobile
		}
		if (incap != null && incap) {
			return RollEnumType.FAILURE; // Cannot dodge while incapacitated
		}
		if (awake != null && !awake) {
			dodgeSkill /= 4; // Severely reduced while asleep/unconscious
		}

		// Harder to dodge skilled attacks
		int modifier = Math.max(0, (attackSkill - 50) / 5);
		dodgeSkill = Math.max(5, dodgeSkill - modifier);

		int roll = rollPercentage();

		if (roll <= 1) {
			return RollEnumType.CATASTROPHIC_FAILURE;
		}
		if (roll >= 99) {
			return RollEnumType.NATURAL_SUCCESS;
		}

		if (roll <= dodgeSkill) {
			return RollEnumType.SUCCESS;
		}
		return RollEnumType.FAILURE;
	}

	/**
	 * Roll to parry - defender attempts to block an attack with a parry
	 * Parry formula: (ParrySkill - (skill - attack)) = Minimum percent to parry
	 *
	 * @param defender The defending character
	 * @param attackSkill The attacker's skill level
	 * @param defenderWeaponClass The class of the defender's weapon (can only parry equal or lesser)
	 * @param attackerWeaponClass The class of the attacker's weapon
	 * @return RollEnumType indicating success/failure
	 */
	public static RollEnumType rollParry(BaseRecord defender, int attackSkill,
			int defenderWeaponClass, int attackerWeaponClass) {

		// Cannot parry a higher class weapon
		if (attackerWeaponClass > defenderWeaponClass) {
			logger.debug("Cannot parry: attacker weapon class {} > defender class {}",
					attackerWeaponClass, defenderWeaponClass);
			return RollEnumType.FAILURE;
		}

		int parrySkill = getParrySkill(defender);

		// Apply mobility modifiers
		Boolean immobile = defender.get("state.immobilized");
		Boolean incap = defender.get("state.incapacitated");

		if (immobile != null && immobile) {
			return RollEnumType.FAILURE;
		}
		if (incap != null && incap) {
			return RollEnumType.FAILURE;
		}

		// Parry modifier based on attack skill
		int parryModifier = parrySkill - attackSkill;
		int minToParry = Math.max(5, parrySkill - parryModifier);
		minToParry = Math.min(minToParry, 95);

		int roll = rollPercentage();

		if (roll <= 1) {
			return RollEnumType.CATASTROPHIC_FAILURE;
		}
		if (roll >= 99) {
			return RollEnumType.NATURAL_SUCCESS;
		}

		if (roll <= minToParry) {
			return RollEnumType.SUCCESS;
		}
		return RollEnumType.FAILURE;
	}

	/**
	 * Calculate damage using the Armor Damaging System (ADS)
	 *
	 * @param attackSkill The attacker's effective skill
	 * @param baseDamage Base damage of the attack/weapon
	 * @param armorHitPoints Total armor hit points
	 * @param armorStressPoint Points needed to pierce armor
	 * @param armorAbsorption Armor absorption percentage (0-100)
	 * @return int array: [damageToTarget, damageToArmor, armorPierced (1 or 0)]
	 */
	public static int[] calculateArmorDamage(int attackSkill, int baseDamage,
			int armorHitPoints, int armorStressPoint, int armorAbsorption) {

		int damageToTarget = 0;
		int damageToArmor = 0;
		boolean pierced = false;

		// Effective attack skill after armor absorption
		int effectiveSkill = attackSkill - armorAbsorption;
		if (effectiveSkill < 5) effectiveSkill = 5;

		// Roll for damage placement
		int roll = rollPercentage();

		// Critical threshold (5%) - both target and armor take damage
		int criticalThreshold = (int)(armorAbsorption * ARMOR_CRITICAL_THRESHOLD);
		if (criticalThreshold < 5) criticalThreshold = 5;

		if (roll <= criticalThreshold) {
			// Critical hit - pierces armor, damages both target and armor
			pierced = true;
			damageToTarget = baseDamage;
			damageToArmor = baseDamage / 2;
			logger.debug("ADS: Critical hit! Roll {} <= {}", roll, criticalThreshold);
		} else if (roll <= armorAbsorption) {
			// Hits armor only
			damageToArmor = baseDamage;
			logger.debug("ADS: Armor absorbed. Roll {} <= absorption {}", roll, armorAbsorption);
		} else if (roll <= effectiveSkill) {
			// Effective hit - reduced damage through armor
			damageToTarget = baseDamage / 2;
			damageToArmor = baseDamage / 4;
			logger.debug("ADS: Partial penetration. Roll {} <= effective skill {}", roll, effectiveSkill);
		} else {
			// Miss due to armor
			logger.debug("ADS: Armor deflected. Roll {} > effective skill {}", roll, effectiveSkill);
		}

		// Check if armor stress point exceeded
		if (damageToArmor >= armorStressPoint) {
			pierced = true;
		}

		return new int[] { damageToTarget, damageToArmor, pierced ? 1 : 0 };
	}

	/**
	 * Roll for critical determination.
	 * Based on olio.txt rules:
	 *   0-50%: Regular outcome
	 *   51-85%: Double outcome
	 *   86-95%: Triple outcome
	 *   96-100%: Deadly outcome
	 */
	public static CriticalEnumType rollCritical() {
		int roll = rollPercentage();
		return CriticalEnumType.fromPercentage(roll);
	}

	/**
	 * Roll a saving throw.
	 * Formula: ((strength + health + willpower) / 3) x 5 = Save percentage
	 *
	 * @param person The character making the save
	 * @param saveType Type of saving throw
	 * @param difficultyModifier Additional difficulty modifier
	 * @return RollEnumType indicating success/failure
	 */
	public static RollEnumType rollSavingThrow(BaseRecord person, SavingThrowEnumType saveType, int difficultyModifier) {
		int strength = getStatSafe(person, OlioFieldNames.FIELD_PHYSICAL_STRENGTH);
		int health = getStatSafe(person, OlioFieldNames.FIELD_HEALTH);
		int willpower = getStatSafe(person, OlioFieldNames.FIELD_WILLPOWER);

		// Base save percentage
		int savePercent = (int) (((strength + health + willpower) / 3.0) * 5.0);

		// Apply type-specific modifiers
		switch (saveType) {
			case DEATH:
				// No additional modifier for death saves
				break;
			case SICKNESS:
			case POISON:
				// Endurance helps with sickness/poison
				int endurance = getStatSafe(person, OlioFieldNames.FIELD_PHYSICAL_ENDURANCE);
				savePercent += endurance;
				break;
			case MAGIC:
				// Mental strength helps against magic
				int mentalStrength = getStatSafe(person, OlioFieldNames.FIELD_MENTAL_STRENGTH);
				savePercent += mentalStrength / 2;
				break;
			case FEAR:
				// Wisdom and willpower help against fear
				int wisdom = getStatSafe(person, OlioFieldNames.FIELD_WISDOM);
				savePercent += (wisdom + willpower) / 4;
				break;
			case STUN:
				// Physical endurance helps against stun
				int physEnd = getStatSafe(person, OlioFieldNames.FIELD_PHYSICAL_ENDURANCE);
				savePercent += physEnd / 2;
				break;
		}

		// Apply difficulty modifier
		savePercent -= difficultyModifier;
		savePercent = Math.max(5, Math.min(95, savePercent));

		int roll = rollPercentage();

		if (roll <= 1) {
			return RollEnumType.CATASTROPHIC_FAILURE;
		}
		if (roll >= 99) {
			return RollEnumType.NATURAL_SUCCESS;
		}

		if (roll <= savePercent) {
			return RollEnumType.SUCCESS;
		}
		return RollEnumType.FAILURE;
	}

	/**
	 * Perform a full combat exchange between attacker and defender.
	 *
	 * @param attacker The attacking character
	 * @param defender The defending character
	 * @param attackerWeaponDamage Base damage of attacker's weapon
	 * @param attackerWeaponSkillBonus Skill bonus from weapon proficiency
	 * @param attackerWeaponClass Weapon class (for parry checks)
	 * @param defenderArmorHP Defender's armor hit points
	 * @param defenderArmorStress Defender's armor stress threshold
	 * @param defenderArmorAbsorption Defender's armor absorption percentage
	 * @param defenderWeaponClass Defender's weapon class (for parry)
	 * @param preferParry Whether defender prefers parry over dodge
	 * @return CombatResult with full combat outcome
	 */
	public static CombatResult resolveCombat(
			BaseRecord attacker, BaseRecord defender,
			int attackerWeaponDamage, int attackerWeaponSkillBonus, int attackerWeaponClass,
			int defenderArmorHP, int defenderArmorStress, int defenderArmorAbsorption,
			int defenderWeaponClass, boolean preferParry) {

		CombatResult result = new CombatResult();
		StringBuilder desc = new StringBuilder();

		String attackerName = attacker.get(FieldNames.FIELD_NAME);
		String defenderName = defender.get(FieldNames.FIELD_NAME);
		if (attackerName == null) attackerName = "Attacker";
		if (defenderName == null) defenderName = "Defender";

		// Step 1: Roll to hit
		int attackSkill = getFightSkill(attacker) + attackerWeaponSkillBonus;
		result.attackRoll = rollToHit(attacker, attackerWeaponSkillBonus);

		if (result.attackRoll == RollEnumType.CATASTROPHIC_FAILURE) {
			result.attackHit = false;
			result.description = attackerName + " critically fumbles the attack!";
			return result;
		}

		if (result.attackRoll == RollEnumType.FAILURE) {
			result.attackHit = false;
			result.description = attackerName + " misses " + defenderName + ".";
			return result;
		}

		// Attack hits - defender can attempt to dodge or parry
		desc.append(attackerName).append(" attacks ").append(defenderName).append(". ");

		// Step 2: Defense attempt
		if (preferParry && defenderWeaponClass > 0) {
			// Try parry first
			result.defenseRoll = rollParry(defender, attackSkill, defenderWeaponClass, attackerWeaponClass);
			result.defenseType = DefenseType.PARRY;

			if (result.defenseRoll == RollEnumType.SUCCESS || result.defenseRoll == RollEnumType.NATURAL_SUCCESS) {
				result.defended = true;
				result.attackHit = false;
				result.description = desc.toString() + defenderName + " parries the attack!";
				return result;
			}
		} else {
			// Try dodge
			result.defenseRoll = rollDodge(defender, attackSkill);
			result.defenseType = DefenseType.DODGE;

			if (result.defenseRoll == RollEnumType.SUCCESS || result.defenseRoll == RollEnumType.NATURAL_SUCCESS) {
				result.defended = true;
				result.attackHit = false;
				result.description = desc.toString() + defenderName + " dodges the attack!";
				return result;
			}
		}

		// Step 3: Attack lands - apply ADS if armored
		result.attackHit = true;

		// Roll for critical
		if (result.attackRoll == RollEnumType.NATURAL_SUCCESS) {
			result.criticalLevel = rollCritical();
		}

		int baseDamage = attackerWeaponDamage > 0 ? attackerWeaponDamage : BASE_UNARMED_DAMAGE;
		baseDamage = (int)(baseDamage * result.criticalLevel.getMultiplier());

		if (defenderArmorHP > 0) {
			// Apply armor damage system
			int[] adsDamage = calculateArmorDamage(attackSkill, baseDamage,
					defenderArmorHP, defenderArmorStress, defenderArmorAbsorption);
			result.damageToTarget = adsDamage[0];
			result.damageToArmor = adsDamage[1];
			result.armorPierced = adsDamage[2] == 1;

			if (result.armorPierced) {
				desc.append("The attack pierces armor! ");
			} else if (result.damageToTarget == 0) {
				desc.append("Armor absorbs the blow. ");
			}
		} else {
			// No armor - full damage
			result.damageToTarget = baseDamage;
		}

		// Build final description
		if (result.criticalLevel != CriticalEnumType.REGULAR) {
			desc.append(result.criticalLevel.name()).append(" critical! ");
		}
		if (result.damageToTarget > 0) {
			desc.append(defenderName).append(" takes ").append(result.damageToTarget).append(" damage");
			if (result.damageToArmor > 0) {
				desc.append(" (armor: ").append(result.damageToArmor).append(")");
			}
			desc.append(".");
		} else {
			desc.append("No damage dealt.");
		}

		result.description = desc.toString();
		return result;
	}

	/**
	 * Safely get an integer statistic value from a record
	 */
	private static int getStatSafe(BaseRecord person, String statName) {
		try {
			Object val = person.get("statistics." + statName);
			if (val instanceof Integer) {
				return (Integer) val;
			} else if (val instanceof Number) {
				return ((Number) val).intValue();
			}
		} catch (Exception e) {
			logger.debug("Could not get stat {}: {}", statName, e.getMessage());
		}
		return 0;
	}

	/**
	 * Apply damage to a character's health state
	 */
	public static void applyDamage(BaseRecord person, int damage) {
		if (damage <= 0) return;

		try {
			Double currentHealth = person.get("state.health");
			if (currentHealth == null) currentHealth = 1.0;

			// Calculate health reduction (damage as percentage of max)
			int maxHealth = getStatSafe(person, OlioFieldNames.FIELD_HEALTH);
			if (maxHealth <= 0) maxHealth = 10;

			double healthLoss = (double) damage / (maxHealth * 5.0);
			double newHealth = Math.max(0.0, currentHealth - healthLoss);

			person.set("state.health", newHealth);

			// Check for incapacitation
			if (newHealth <= 0.1) {
				person.set("state.incapacitated", true);
			}
			if (newHealth <= 0.0) {
				person.set("state.alive", false);
			}

			logger.info("Applied {} damage to {}. Health: {} -> {}",
					damage, person.get(FieldNames.FIELD_NAME), currentHealth, newHealth);
		} catch (Exception e) {
			logger.error("Failed to apply damage: {}", e.getMessage());
		}
	}
}
