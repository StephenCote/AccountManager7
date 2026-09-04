package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;

import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.Rules;
import org.junit.Test;

/**
 * Issue-5 regression guard (no LLM, no DB, no SD — pure function).
 *
 * <p>PictureBook character generation persisted {@code gender} in UPPERCASE ("MALE"/"FEMALE"), but the
 * entire Olio narrative/pronoun/body-stat ecosystem compares gender with the case-SENSITIVE test
 * {@code "male".equals(gender)}. An uppercase "MALE" therefore silently fell through to the female
 * branch, so a male PictureBook character was described as a "woman"/"girl" in the narrative — the exact
 * symptom Stephen reported ("it reads 'male' but 'male' isn't set, so it defaults to 'female'").
 * {@code PictureBookUtil.normalizeGender()} and its baseline fallback now clamp to the
 * ecosystem-canonical LOWERCASE "male"/"female".
 *
 * <p>This test does NOT exercise the extract()/createCharPerson() pipeline (that end-to-end proof is
 * {@code TestPictureBookUtilE2E}, gated behind PICTUREBOOK_E2E + live LLM). What it locks down here is
 * the case-sensitivity CONTRACT of the consumer {@link NarrativeUtil#getGenderLabel(String, int)} that
 * makes lowercase storage mandatory — including an explicit UPPERCASE negative control that reproduces
 * the Issue-5 mislabel, so the fix cannot silently regress back to uppercase storage without this test
 * going red.
 */
public class TestNarrativeGenderLabel {

	private static final int ADULT_AGE = Rules.MINIMUM_ADULT_AGE + 10; // 26 — unambiguously adult (> MINIMUM_ADULT_AGE)
	private static final int CHILD_AGE = Rules.MAXIMUM_CHILD_AGE - 2;  // 8  — unambiguously a child (< MAXIMUM_CHILD_AGE)
	private static final int TEEN_AGE = Rules.MINIMUM_ADULT_AGE;       // 16 — teen boundary (<= MINIMUM_ADULT_AGE)

	@Test
	public void lowercaseMaleRendersAsMan() {
		assertEquals("Canonical lowercase 'male' (the stored form after the Issue-5 fix) must render an "
			+ "adult male label", "man", NarrativeUtil.getGenderLabel("male", ADULT_AGE));
	}

	@Test
	public void lowercaseFemaleRendersAsWoman() {
		assertEquals("Canonical lowercase 'female' must render an adult female label",
			"woman", NarrativeUtil.getGenderLabel("female", ADULT_AGE));
	}

	@Test
	public void uppercaseMaleFallsThroughToFemale_theIssue5Defect() {
		// NEGATIVE CONTROL — this IS the bug. getGenderLabel() does gender.equals("male"), so "MALE"
		// != "male" and a male character is mislabeled "woman". Asserting the defect is real and
		// case-driven is what proves the fix must store lowercase, not uppercase; if someone reverts
		// normalizeGender() to uppercase, TestPictureBookUtilE2E's stored value would again be "MALE"
		// and every narrative label for males would flip to female — this control documents why.
		assertEquals("Uppercase 'MALE' is NOT recognized by the case-sensitive consumer and mislabels a "
			+ "male as 'woman' — the exact Issue-5 defect the lowercase fix removes",
			"woman", NarrativeUtil.getGenderLabel("MALE", ADULT_AGE));
	}

	@Test
	public void lowercaseMaleChildAndTeen() {
		assertEquals("boy child", NarrativeUtil.getGenderLabel("male", CHILD_AGE));
		assertEquals("teenaged boy", NarrativeUtil.getGenderLabel("male", TEEN_AGE));
	}

	@Test
	public void lowercaseFemaleChildAndTeen() {
		assertEquals("girl child", NarrativeUtil.getGenderLabel("female", CHILD_AGE));
		assertEquals("teenaged girl", NarrativeUtil.getGenderLabel("female", TEEN_AGE));
	}
}
