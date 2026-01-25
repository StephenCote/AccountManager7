package org.cote.accountmanager.olio;

/**
 * Types of saving throws based on olio.txt rules.
 * Formula: ((strength + health + willpower) / 3) x 5 = Save percentage
 */
public enum SavingThrowEnumType {
	DEATH,
	SICKNESS,
	POISON,
	MAGIC,
	FEAR,
	STUN
}
