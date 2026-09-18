package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil;
import org.junit.Test;

/**
 * The defect: <b>every scene referencing "Darby" rendered her father.</b>
 *
 * <p>MEASURED on am72db 2026-09-18, picture book "BWO 3" (population group 727, Darby=128 female
 * 18, Darby's dad=129 male 45). All 17 scene composites described the first person as
 * "fit, straight-framed 45 year old White man with gray eyes and blond short hair" — the DAD —
 * including the six scenes where Darby is the only character. Veronique and Yolanda in the same book
 * resolved correctly, and scenes listing Darby SECOND put the dad second, so the fault followed the
 * NAME, not the position.
 *
 * <p>Cause: {@code findCharPersonByNameInGroup} looked the name up with
 * {@code ComparatorEnumType.ILIKE}, which reads as "case-insensitive exact" but is not.
 * {@code StatementUtil} silently wraps a LIKE/ILIKE value in {@code %...%} when the value contains
 * no {@code %} of its own (StatementUtil.java:1312-1314), so the emitted predicate was
 * {@code name ILIKE '%Darby%'} — which also matches "Darby's dad" — and {@code AccessPoint.find}
 * returned ONE row from an unordered result. Against that database it returned the dad.
 *
 * <p>Any character whose name is a substring of another character's name in the same group hits
 * this. It is not specific to kinship names: "Ann"/"Anna", "Lee"/"Leena", "Sam"/"Samantha".
 *
 * <p>These tests pin the VERIFICATION RULE the fix rests on: after any DB-side case folding, a
 * candidate is accepted only on a whole-string match. That rule is a pure function, so these need
 * no database. The end-to-end resolution, and the framework's wildcard injection itself, are
 * DB-bound and are not asserted here — see the note at the bottom of this class.
 */
public class TestCharacterNameSubstringCollision {
	public static final Logger logger = LogManager.getLogger(TestCharacterNameSubstringCollision.class);

	/// The rule that has to hold, stated over the data that broke: a substring hit is NOT a match.
	/// firstExactNameMatch is what enforces it after any DB-side ILIKE narrowing; this is the
	/// predicate it applies.
	@Test
	public void aSubstringIsNotAName() {
		assertFalse("'Darby' must not match 'Darby's dad' - this is the whole defect",
			PictureBookUtil.namesMatchAccentInsensitive("Darby", "Darby's dad"));
		assertFalse(PictureBookUtil.namesMatchAccentInsensitive("Darby", "Darby's Dad"));
		assertFalse("The reverse direction too",
			PictureBookUtil.namesMatchAccentInsensitive("Darby's dad", "Darby"));
		// Not a kinship-specific problem.
		assertFalse(PictureBookUtil.namesMatchAccentInsensitive("Ann", "Anna"));
		assertFalse(PictureBookUtil.namesMatchAccentInsensitive("Sam", "Samantha"));
	}

	/// ...while the two things the loose match was introduced FOR must still work: a case
	/// difference ("Jideon") and a diacritic the database does not fold ("Duna"/"Duña").
	@Test
	public void caseAndAccentDifferencesStillMatch() {
		assertTrue("A case difference must still resolve - the reason ILIKE was used at all",
			PictureBookUtil.namesMatchAccentInsensitive("jideon", "Jideon"));
		assertTrue(PictureBookUtil.namesMatchAccentInsensitive("JIDEON DE ROSA", "Jideon de Rosa"));
		assertTrue("A diacritic the DB will not fold must still resolve",
			PictureBookUtil.namesMatchAccentInsensitive("Duna", "Duña"));
		assertTrue("...and whitespace is tolerated",
			PictureBookUtil.namesMatchAccentInsensitive("  Darby  ", "Darby"));
	}

	/// The framework behaviour that caused this is recorded where the workaround lives
	/// (findCharPersonByNameInGroup and ColorUtil.getColorByName), with the measurement that
	/// established it. It is deliberately NOT asserted here: reaching StatementUtil's parameter
	/// binding needs a live IOSystem and a real statement, so a test for it would be a database test
	/// masquerading as a unit test - and what callers actually depend on is the whole-string
	/// verification above, which is pure.
}
