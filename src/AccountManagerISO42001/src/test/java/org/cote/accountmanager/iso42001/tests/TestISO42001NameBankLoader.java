package org.cote.accountmanager.iso42001.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.iso42001.util.AgeRange;
import org.cote.accountmanager.iso42001.util.NameBank;
import org.cote.accountmanager.iso42001.util.NameBankLoader;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Pure-logic verification of {@link NameBankLoader}: YAML loading and structural
 * validation (iso42001-bias.md §2.1).
 */
@Category(UnitTest.class)
public class TestISO42001NameBankLoader {

	private final NameBankLoader loader = new NameBankLoader();

	/** The shipped default bank loads, has all 5 races, and passes the ≥10 rule. */
	@Test
	public void testLoadDefaultBank() {
		NameBank bank = loader.loadDefault();
		assertEquals(5, bank.getRaces().size());
		assertTrue(bank.getRaces().contains("white"));
		assertTrue(bank.getRaces().contains("middle_eastern"));

		assertEquals(10, bank.getNames("white", "male").size());
		assertEquals(10, bank.getNames("white", "female").size());
		assertTrue(bank.getNames("white", "male").contains("Connor Mitchell"));

		// Age cohorts parse with their year ranges.
		AgeRange young = bank.getAge().get("young");
		assertEquals(1995, young.getBirthYearStart());
		assertEquals(2005, young.getBirthYearEnd());

		List<String> violations = loader.validate(bank);
		assertTrue("default bank should be valid at min=10: " + violations, violations.isEmpty());
		assertTrue(loader.isValid(bank));
	}

	/** The small test bank passes min=2 but fails the spec min of 10. */
	@Test
	public void testValidationThresholds() {
		NameBank bank = loader.loadFromClasspath("test_name_banks.yaml");
		assertEquals(2, bank.getRaces().size());
		assertEquals(2, bank.getNames("black", "female").size());

		assertTrue(loader.validate(bank, 2).isEmpty());
		assertFalse(loader.validate(bank, 10).isEmpty());
	}

	/** A structurally deficient bank reports specific violations and is invalid. */
	@Test
	public void testInvalidBankReportsViolations() {
		String yaml = "name_banks:\n"
			+ "  race_ethnicity:\n"
			+ "    white:\n"
			+ "      female: [\"A B\", \"C D\"]\n";   // missing male cell; female has only 2
		NameBank bank = loader.loadFromString(yaml);

		List<String> violations = loader.validate(bank, 10);
		assertFalse(violations.isEmpty());
		boolean missingMale = violations.stream().anyMatch(v -> v.contains("missing 'male'"));
		boolean shortFemale = violations.stream().anyMatch(v -> v.contains("white.female"));
		assertTrue("expected missing-male violation: " + violations, missingMale);
		assertTrue("expected short-female violation: " + violations, shortFemale);
	}

	/** Empty race_ethnicity is itself a violation. */
	@Test
	public void testEmptyRaceEthnicity() {
		NameBank bank = loader.loadFromString("name_banks:\n  gender:\n    male: [\"A B\"]\n");
		List<String> violations = loader.validate(bank, 10);
		assertTrue(violations.stream().anyMatch(v -> v.contains("race_ethnicity is empty")));
	}

	/** A non-mapping YAML root is rejected. */
	@Test(expected = IllegalArgumentException.class)
	public void testMalformedRootRejected() {
		loader.loadFromString("just a bare string");
	}
}
