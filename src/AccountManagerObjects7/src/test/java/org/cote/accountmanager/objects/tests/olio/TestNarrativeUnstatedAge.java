package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.ParameterUtil;
import org.junit.Test;

/// IssueLog-2026-09-22 Issue 11 / item 9: a picture-book character whose manuscript never stated an age
/// reads age 0 ("age" is a plain int column), and the two describers that feed the IMAGE prompts turned
/// that into "0 year old boy child" and "((zero:1.5) (0yo:1.5))" - an adult drawn as a newborn.
///
/// Exercised against a REAL persisted olio.charPerson read back through the normal full-record path and
/// the real ProfileUtil, not a hand-built profile. No LLM, no SD. The Olio simulation's own sentence
/// (NarrativeUtil.describe / getGenderLabel) is deliberately untouched - a sim newborn IS 0 years old -
/// and the stated-age outputs must be byte-for-byte what they were.
public class TestNarrativeUnstatedAge extends BaseTest {

	private BaseRecord persistCharacter(String gender, int age) throws Exception {
		OlioModelNames.use();
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord person = mf.newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null,
			ParameterUtil.newParameterList(FieldNames.FIELD_PATH, "~/Characters"));
		person.set(FieldNames.FIELD_NAME, "Unstated Age - " + UUID.randomUUID().toString());
		person.set(FieldNames.FIELD_FIRST_NAME, "Simon");
		person.set(FieldNames.FIELD_GENDER, gender);
		person.set(FieldNames.FIELD_AGE, age);
		assertTrue("charPerson create failed", ioContext.getRecordUtil().createRecord(person));
		BaseRecord full = OlioUtil.getFullRecord(person);
		assertNotNull("charPerson should read back fully populated", full);
		assertEquals("age round-trips as stored", age, (int) full.get(FieldNames.FIELD_AGE));
		return full;
	}

	@Test
	public void TestUnstatedAgeIsDescribedAsAnAdultNotANewborn() throws Exception {
		BaseRecord full = persistCharacter("male", 0);
		PersonalityProfile pp = ProfileUtil.getProfile(null, full);
		assertNotNull("profile should resolve for a persisted character", pp);
		assertEquals("profile carries the stored (unstated) age", 0, pp.getAge());

		String physical = NarrativeUtil.describePhysical(pp);
		String sd = NarrativeUtil.getSDMinPrompt(pp);
		logger.info("age 0 describePhysical: " + physical);
		logger.info("age 0 getSDMinPrompt: " + sd);

		assertFalse("no '0 year old' in the physical description: " + physical, physical.contains("0 year old"));
		assertFalse("no 'year old' at all for an unstated age: " + physical, physical.contains("year old"));
		assertFalse("not a child: " + physical, physical.contains("child"));
		assertFalse("not a teenager: " + physical, physical.contains("teenaged"));
		assertTrue("described as a man: " + physical, physical.contains("man"));

		assertFalse("no weighted zero-age token: " + sd, sd.contains("(zero:1.5)"));
		assertFalse("no weighted 0yo token: " + sd, sd.contains("(0yo:1.5)"));
		assertFalse("no 'yo:1.5' token of any value: " + sd, sd.contains("yo:1.5)"));
		assertTrue("the gender token is the adult noun: " + sd, sd.contains("(man)"));
		assertFalse("not drawn as a child: " + sd, sd.contains("child"));

		/// The same character once the age IS known reads exactly as before the change - the age tokens are
		/// back and the gender noun follows the age bands as it always did.
		full.set(FieldNames.FIELD_AGE, 30);
		PersonalityProfile pp30 = ProfileUtil.updateProfile(null, full);
		assertEquals(30, pp30.getAge());
		String physical30 = NarrativeUtil.describePhysical(pp30);
		String sd30 = NarrativeUtil.getSDMinPrompt(pp30);
		logger.info("age 30 describePhysical: " + physical30);
		logger.info("age 30 getSDMinPrompt: " + sd30);
		assertTrue("stated age is spoken: " + physical30, physical30.contains("30 year old"));
		/// The number-word token is the legacy text verbatim, including the trailing space the private
		/// getNumberName has always emitted ("thirty :1.5") - this test guards that the fix changed nothing here.
		assertTrue("stated age keeps its weighted tokens: " + sd30, sd30.contains("((thirty :1.5) (30yo:1.5) (man))"));

		/// A real child is still a child: the fix is scoped to the UNSTATED value, not to ages below the
		/// child threshold.
		int childAge = Math.max(1, Rules.MAXIMUM_CHILD_AGE - 1);
		full.set(FieldNames.FIELD_AGE, childAge);
		PersonalityProfile ppChild = ProfileUtil.updateProfile(null, full);
		String physicalChild = NarrativeUtil.describePhysical(ppChild);
		assertTrue("a stated child age still reads as a child: " + physicalChild,
			physicalChild.contains(childAge + " year old") && physicalChild.contains("boy child"));
	}

	@Test
	public void TestUnstatedAgeFemaleReadsAsWoman() throws Exception {
		BaseRecord full = persistCharacter("female", 0);
		PersonalityProfile pp = ProfileUtil.getProfile(null, full);
		assertNotNull(pp);
		String physical = NarrativeUtil.describePhysical(pp);
		String sd = NarrativeUtil.getSDMinPrompt(pp);
		assertFalse(physical, physical.contains("year old"));
		assertFalse(physical, physical.contains("girl"));
		assertTrue(physical, physical.contains("woman"));
		assertTrue(sd, sd.contains("(woman)"));
		assertFalse(sd, sd.contains("yo:1.5)"));
	}

	/// Pure: the label helpers behind both describers, and the invariant that the SIM label is untouched.
	@Test
	public void TestDescribedGenderLabelOnlyDivergesForUnstatedAge() {
		assertTrue(NarrativeUtil.isAgeUnstated(0));
		assertTrue(NarrativeUtil.isAgeUnstated(-1));
		assertFalse(NarrativeUtil.isAgeUnstated(1));
		assertEquals("man", NarrativeUtil.getDescribedGenderLabel("male", 0));
		assertEquals("woman", NarrativeUtil.getDescribedGenderLabel("female", 0));
		assertEquals("boy child", NarrativeUtil.getGenderLabel("male", 0));
		assertEquals("girl child", NarrativeUtil.getGenderLabel("female", 0));
		for(int age = 1; age <= 90; age++) {
			assertEquals("stated ages agree at " + age, NarrativeUtil.getGenderLabel("male", age), NarrativeUtil.getDescribedGenderLabel("male", age));
			assertEquals("stated ages agree at " + age, NarrativeUtil.getGenderLabel("female", age), NarrativeUtil.getDescribedGenderLabel("female", age));
		}
	}
}
