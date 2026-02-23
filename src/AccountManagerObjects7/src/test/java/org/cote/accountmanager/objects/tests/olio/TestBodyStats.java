package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.BodyShapeEnumType;
import org.cote.accountmanager.olio.BodyStatsProvider;
import org.cote.accountmanager.olio.BodyTypeEnumType;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.EthnicityEnumType;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.HighEnumType;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Test;

public class TestBodyStats extends BaseTest {

	/// ── heightToInches conversion tests ────────────────────────────

	@Test
	public void TestHeightToInches() {
		logger.info("Test heightToInches conversion");

		assertEquals("5'0\" should be 60 inches", 60.0, BodyStatsProvider.heightToInches(5.00), 0.01);
		assertEquals("5'10\" should be 70 inches", 70.0, BodyStatsProvider.heightToInches(5.10), 0.01);
		assertEquals("6'0\" should be 72 inches", 72.0, BodyStatsProvider.heightToInches(6.00), 0.01);
		assertEquals("6'2\" should be 74 inches", 74.0, BodyStatsProvider.heightToInches(6.02), 0.01);
		assertEquals("4'6\" should be 54 inches", 54.0, BodyStatsProvider.heightToInches(4.06), 0.01);
		assertEquals("3'0\" should be 36 inches", 36.0, BodyStatsProvider.heightToInches(3.00), 0.01);
	}

	/// ── BMI descriptor tests ───────────────────────────────────────

	@Test
	public void TestBmiDescriptor() {
		logger.info("Test BMI descriptors");

		assertEquals("BMI 15 should be emaciated", "emaciated", BodyStatsProvider.getBmiDescriptor(15.0));
		assertEquals("BMI 17 should be emaciated", "emaciated", BodyStatsProvider.getBmiDescriptor(17.0));
		assertEquals("BMI 18 should be lean", "lean", BodyStatsProvider.getBmiDescriptor(18.0));
		assertEquals("BMI 20 should be lean", "lean", BodyStatsProvider.getBmiDescriptor(20.0));
		assertEquals("BMI 22 should be fit", "fit", BodyStatsProvider.getBmiDescriptor(22.0));
		assertEquals("BMI 24 should be fit", "fit", BodyStatsProvider.getBmiDescriptor(24.0));
		assertEquals("BMI 27 should be solid", "solid", BodyStatsProvider.getBmiDescriptor(27.0));
		assertEquals("BMI 32 should be heavy", "heavy", BodyStatsProvider.getBmiDescriptor(32.0));
		assertEquals("BMI 40 should be massive", "massive", BodyStatsProvider.getBmiDescriptor(40.0));

		/// Boundary tests
		assertEquals("BMI 17.5 should be lean (boundary)", "lean", BodyStatsProvider.getBmiDescriptor(17.5));
		assertEquals("BMI 21.0 should be fit (boundary)", "fit", BodyStatsProvider.getBmiDescriptor(21.0));
		assertEquals("BMI 25.0 should be solid (boundary)", "solid", BodyStatsProvider.getBmiDescriptor(25.0));
		assertEquals("BMI 30.0 should be heavy (boundary)", "heavy", BodyStatsProvider.getBmiDescriptor(30.0));
		assertEquals("BMI 35.0 should be massive (boundary)", "massive", BodyStatsProvider.getBmiDescriptor(35.0));
	}

	/// ── Beauty adjustment tests ────────────────────────────────────

	@Test
	public void TestBeautyAdjustment() {
		logger.info("Test beauty adjustments by body shape and gender");

		/// Male adjustments
		assertEquals("V_TAPER male +3", 3, BodyStatsProvider.getBeautyAdjustment("V_TAPER", "male"));
		assertEquals("INVERTED_TRIANGLE male +3", 3, BodyStatsProvider.getBeautyAdjustment("INVERTED_TRIANGLE", "male"));
		assertEquals("HOURGLASS male 0", 0, BodyStatsProvider.getBeautyAdjustment("HOURGLASS", "male"));
		assertEquals("RECTANGLE male 0", 0, BodyStatsProvider.getBeautyAdjustment("RECTANGLE", "male"));
		assertEquals("PEAR male -3", -3, BodyStatsProvider.getBeautyAdjustment("PEAR", "male"));
		assertEquals("ROUND male -4", -4, BodyStatsProvider.getBeautyAdjustment("ROUND", "male"));

		/// Female adjustments
		assertEquals("HOURGLASS female +3", 3, BodyStatsProvider.getBeautyAdjustment("HOURGLASS", "female"));
		assertEquals("V_TAPER female 0", 0, BodyStatsProvider.getBeautyAdjustment("V_TAPER", "female"));
		assertEquals("INVERTED_TRIANGLE female 0", 0, BodyStatsProvider.getBeautyAdjustment("INVERTED_TRIANGLE", "female"));
		assertEquals("RECTANGLE female 0", 0, BodyStatsProvider.getBeautyAdjustment("RECTANGLE", "female"));
		assertEquals("PEAR female +1", 1, BodyStatsProvider.getBeautyAdjustment("PEAR", "female"));
		assertEquals("ROUND female -4", -4, BodyStatsProvider.getBeautyAdjustment("ROUND", "female"));

		/// Null/invalid input
		assertEquals("Null shape returns 0", 0, BodyStatsProvider.getBeautyAdjustment(null, "male"));
		assertEquals("Null gender returns 0", 0, BodyStatsProvider.getBeautyAdjustment("ROUND", null));
		assertEquals("Invalid shape returns 0", 0, BodyStatsProvider.getBeautyAdjustment("INVALID", "male"));
	}

	/// ── Body shape descriptor tests ────────────────────────────────

	@Test
	public void TestBodyShapeDescriptor() {
		logger.info("Test body shape descriptors");

		/// Male descriptors
		assertEquals("V_TAPER male", "broad-shouldered with a narrow waist", BodyStatsProvider.getBodyShapeDescriptor("V_TAPER", "male"));
		assertEquals("HOURGLASS male", "well-proportioned", BodyStatsProvider.getBodyShapeDescriptor("HOURGLASS", "male"));
		assertEquals("RECTANGLE any", "straight-framed", BodyStatsProvider.getBodyShapeDescriptor("RECTANGLE", "male"));
		assertEquals("ROUND any", "round and stout", BodyStatsProvider.getBodyShapeDescriptor("ROUND", "male"));
		assertEquals("INVERTED_TRIANGLE male", "powerfully built with broad shoulders", BodyStatsProvider.getBodyShapeDescriptor("INVERTED_TRIANGLE", "male"));
		assertEquals("PEAR male", "bottom-heavy", BodyStatsProvider.getBodyShapeDescriptor("PEAR", "male"));

		/// Female descriptors
		assertEquals("V_TAPER female", "athletic with wide shoulders", BodyStatsProvider.getBodyShapeDescriptor("V_TAPER", "female"));
		assertEquals("HOURGLASS female", "curvaceous with a defined waist", BodyStatsProvider.getBodyShapeDescriptor("HOURGLASS", "female"));
		assertEquals("RECTANGLE female", "straight-framed", BodyStatsProvider.getBodyShapeDescriptor("RECTANGLE", "female"));
		assertEquals("ROUND female", "round and stout", BodyStatsProvider.getBodyShapeDescriptor("ROUND", "female"));
		assertEquals("INVERTED_TRIANGLE female", "athletically top-heavy", BodyStatsProvider.getBodyShapeDescriptor("INVERTED_TRIANGLE", "female"));
		assertEquals("PEAR female", "full-hipped", BodyStatsProvider.getBodyShapeDescriptor("PEAR", "female"));

		/// Null/invalid input
		assertEquals("Null shape", "average", BodyStatsProvider.getBodyShapeDescriptor(null, "male"));
		assertEquals("Invalid shape", "average", BodyStatsProvider.getBodyShapeDescriptor("NONEXISTENT", "male"));
	}

	/// ── computeBmi tests (requires model context) ──────────────────

	@Test
	public void TestComputeBmi() {
		logger.info("Test computeBmi with statistics records");

		try {
			BaseRecord stats = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
			assertNotNull("Statistics record should not be null", stats);

			/// Average stats (10 across the board) should give base BMI of 22.0
			stats.set(OlioFieldNames.FIELD_PHYSICAL_STRENGTH, 10);
			stats.set(OlioFieldNames.FIELD_AGILITY, 10);
			stats.set(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE, 10);
			stats.set(OlioFieldNames.FIELD_MENTAL_STRENGTH, 10);
			stats.set(OlioFieldNames.FIELD_MENTAL_ENDURANCE, 10);
			stats.set(OlioFieldNames.FIELD_CHARISMA, 10);
			stats.set(OlioFieldNames.FIELD_SPEED, 10);
			new MemoryReader().inspect(stats);

			double bmi = BodyStatsProvider.computeBmi(stats);
			logger.info("Average stats BMI: " + bmi);
			assertEquals("Average stats should produce BMI near 22.0", 22.0, bmi, 1.5);

			/// High strength, low agility -> higher BMI (heavier/more muscular)
			stats.set(OlioFieldNames.FIELD_PHYSICAL_STRENGTH, 18);
			stats.set(OlioFieldNames.FIELD_AGILITY, 6);
			new MemoryReader().inspect(stats);

			double highBmi = BodyStatsProvider.computeBmi(stats);
			logger.info("High STR / Low AGI BMI: " + highBmi);
			assertTrue("High STR / Low AGI should produce higher BMI than average", highBmi > bmi);

			/// Low strength, high agility -> lower BMI (leaner)
			stats.set(OlioFieldNames.FIELD_PHYSICAL_STRENGTH, 6);
			stats.set(OlioFieldNames.FIELD_AGILITY, 18);
			new MemoryReader().inspect(stats);

			double lowBmi = BodyStatsProvider.computeBmi(stats);
			logger.info("Low STR / High AGI BMI: " + lowBmi);
			assertTrue("Low STR / High AGI should produce lower BMI than average", lowBmi < bmi);

			/// BMI should be clamped to valid range
			stats.set(OlioFieldNames.FIELD_PHYSICAL_STRENGTH, 1);
			stats.set(OlioFieldNames.FIELD_AGILITY, 20);
			new MemoryReader().inspect(stats);

			double minBmi = BodyStatsProvider.computeBmi(stats);
			assertTrue("BMI should be >= 15.0", minBmi >= 15.0);
			assertTrue("BMI should be <= 45.0", minBmi <= 45.0);

		} catch (FieldException | ModelNotFoundException | ValueException | ReaderException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	/// ── rollHeight tests ───────────────────────────────────────────

	@Test
	public void TestRollHeight() {
		logger.info("Test rollHeight produces valid heights");

		try {
			/// Test male adult height
			BaseRecord stats = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
			StatisticsUtil.rollHeight(stats, Arrays.asList("E"), "male", 30);
			double height = stats.get(OlioFieldNames.FIELD_HEIGHT);
			logger.info("Male adult height: " + height);
			assertTrue("Male height should be > 0", height > 0);
			int feet = (int) height;
			assertTrue("Male adult feet should be between 3 and 8", feet >= 3 && feet <= 8);

			/// Test female adult height
			BaseRecord stats2 = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
			StatisticsUtil.rollHeight(stats2, Arrays.asList("E"), "female", 25);
			double height2 = stats2.get(OlioFieldNames.FIELD_HEIGHT);
			logger.info("Female adult height: " + height2);
			assertTrue("Female height should be > 0", height2 > 0);

			/// Test child height (should be shorter)
			BaseRecord stats3 = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
			StatisticsUtil.rollHeight(stats3, Arrays.asList("E"), "male", 8);
			double height3 = stats3.get(OlioFieldNames.FIELD_HEIGHT);
			logger.info("Child (age 8) height: " + height3);
			assertTrue("Child height should be > 0", height3 > 0);

			/// Test different races produce heights
			String[] races = {"A", "B", "C", "D", "E", "X", "Y", "Z"};
			for (String race : races) {
				BaseRecord raceStats = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
				StatisticsUtil.rollHeight(raceStats, Arrays.asList(race), "male", 25);
				double raceHeight = raceStats.get(OlioFieldNames.FIELD_HEIGHT);
				logger.info("Race " + race + " male height: " + raceHeight);
				assertTrue("Race " + race + " should produce valid height", raceHeight > 0);
			}

			/// Dwarf (Y) should generally be shorter than Elf (X) over many rolls
			double dwarfTotal = 0;
			double elfTotal = 0;
			int sampleSize = 50;
			for (int i = 0; i < sampleSize; i++) {
				BaseRecord dStats = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
				StatisticsUtil.rollHeight(dStats, Arrays.asList("Y"), "male", 25);
				dwarfTotal += BodyStatsProvider.heightToInches((double) dStats.get(OlioFieldNames.FIELD_HEIGHT));

				BaseRecord eStats = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
				StatisticsUtil.rollHeight(eStats, Arrays.asList("X"), "male", 25);
				elfTotal += BodyStatsProvider.heightToInches((double) eStats.get(OlioFieldNames.FIELD_HEIGHT));
			}
			double avgDwarf = dwarfTotal / sampleSize;
			double avgElf = elfTotal / sampleSize;
			logger.info("Average dwarf height: " + avgDwarf + " inches, elf: " + avgElf + " inches");
			assertTrue("Dwarves should be shorter than elves on average", avgDwarf < avgElf);

			/// Height format validation: inches portion should be 0-11
			for (int i = 0; i < 20; i++) {
				BaseRecord fmtStats = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
				StatisticsUtil.rollHeight(fmtStats, Arrays.asList("E"), "male", 25);
				double h = fmtStats.get(OlioFieldNames.FIELD_HEIGHT);
				int hFeet = (int) h;
				int hInches = (int) Math.round((h - hFeet) * 100);
				assertTrue("Inches portion should be 0-11 but was " + hInches + " (height=" + h + ")", hInches >= 0 && hInches < 12);
			}

		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	/// ── Narrative body description tests ────────────────────────────

	@Test
	public void TestNarrativeDescribeBodyShape() {
		logger.info("Test NarrativeUtil.describeBodyShape");

		try {
			BaseRecord person = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
			assertNotNull("Person record should not be null", person);

			/// No body shape set -> empty string
			String desc = NarrativeUtil.describeBodyShape(person);
			assertEquals("No body shape should return empty string", "", desc);

			/// Set body shape and gender, verify descriptor is returned
			person.set("bodyShape", "V_TAPER");
			person.set(FieldNames.FIELD_GENDER, "male");
			desc = NarrativeUtil.describeBodyShape(person);
			assertNotNull("Description should not be null", desc);
			assertTrue("Description should not be empty", desc.length() > 0);
			assertEquals("V_TAPER male descriptor", "broad-shouldered with a narrow waist", desc);
			logger.info("V_TAPER male: " + desc);

			/// Female hourglass
			person.set("bodyShape", "HOURGLASS");
			person.set(FieldNames.FIELD_GENDER, "female");
			desc = NarrativeUtil.describeBodyShape(person);
			assertEquals("HOURGLASS female descriptor", "curvaceous with a defined waist", desc);
			logger.info("HOURGLASS female: " + desc);

			/// Null person -> empty string
			desc = NarrativeUtil.describeBodyShape(null);
			assertEquals("Null person should return empty string", "", desc);

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	@Test
	public void TestNarrativeDescribeBuild() {
		logger.info("Test NarrativeUtil.describeBuild");

		try {
			BaseRecord person = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
			assertNotNull("Person record should not be null", person);

			/// Default BMI (22.0) should return "fit"
			String desc = NarrativeUtil.describeBuild(person);
			assertNotNull("Build description should not be null", desc);
			assertEquals("Default BMI should be 'fit'", "fit", desc);
			logger.info("Default BMI build: " + desc);

			/// Set low BMI
			person.set("bmi", 16.0);
			desc = NarrativeUtil.describeBuild(person);
			assertEquals("BMI 16 should be 'emaciated'", "emaciated", desc);

			/// Set high BMI
			person.set("bmi", 33.0);
			desc = NarrativeUtil.describeBuild(person);
			assertEquals("BMI 33 should be 'heavy'", "heavy", desc);

			/// Null person -> empty string
			desc = NarrativeUtil.describeBuild(null);
			assertEquals("Null person should return empty string", "", desc);

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	@Test
	public void TestNarrativeGetLooksPrettyUgly() {
		logger.info("Test NarrativeUtil.getLooksPrettyUgly uses beauty");

		/// Create a PersonalityProfile and verify beauty-based description
		PersonalityProfile prof = new PersonalityProfile();

		/// Test each beauty level
		prof.setBeauty(HighEnumType.DIMINISHED);
		assertEquals("DIMINISHED beauty -> hideous", "hideous", NarrativeUtil.getLooksPrettyUgly(prof));

		prof.setBeauty(HighEnumType.MODEST);
		assertEquals("MODEST beauty -> homely", "homely", NarrativeUtil.getLooksPrettyUgly(prof));

		prof.setBeauty(HighEnumType.FAIR);
		assertEquals("FAIR beauty -> bland", "bland", NarrativeUtil.getLooksPrettyUgly(prof));

		prof.setBeauty(HighEnumType.ELEVATED);
		assertEquals("ELEVATED beauty -> comely", "comely", NarrativeUtil.getLooksPrettyUgly(prof));

		prof.setBeauty(HighEnumType.STRONG);
		assertEquals("STRONG beauty -> pretty", "pretty", NarrativeUtil.getLooksPrettyUgly(prof));

		prof.setBeauty(HighEnumType.EXTENSIVE);
		assertEquals("EXTENSIVE beauty -> beautiful", "beautiful", NarrativeUtil.getLooksPrettyUgly(prof));

		prof.setBeauty(HighEnumType.HERO);
		assertEquals("HERO beauty -> gorgeous", "gorgeous", NarrativeUtil.getLooksPrettyUgly(prof));
	}

	/// ── Narrative physical description integration ──────────────────

	@Test
	public void TestNarrativeDescribePhysical() {
		logger.info("Test NarrativeUtil.describePhysical includes body info");

		try {
			/// describePhysical calls describeBodyShape and describeBuild on the person record.
			/// These methods access person.get("bodyShape"), person.get("bmi"), and person.get("gender").
			/// We test this via a charPerson record with those fields set.
			BaseRecord person = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
			person.set(FieldNames.FIELD_GENDER, "male");
			person.set(FieldNames.FIELD_NAME, "Test Person");
			person.set(FieldNames.FIELD_FIRST_NAME, "Test");
			person.set("age", 25);
			person.set("bodyShape", "V_TAPER");
			person.set("bmi", 24.0);

			/// Verify body shape and build descriptions work correctly from the record
			String bodyDesc = NarrativeUtil.describeBodyShape(person);
			String buildDesc = NarrativeUtil.describeBuild(person);
			logger.info("Body shape: " + bodyDesc + ", Build: " + buildDesc);

			assertTrue("Should contain body shape descriptor", bodyDesc.contains("broad-shouldered"));
			assertEquals("Build should be 'fit'", "fit", buildDesc);

			/// Test with female + hourglass
			person.set(FieldNames.FIELD_GENDER, "female");
			person.set("bodyShape", "HOURGLASS");
			person.set("bmi", 28.0);

			bodyDesc = NarrativeUtil.describeBodyShape(person);
			buildDesc = NarrativeUtil.describeBuild(person);
			logger.info("Female body shape: " + bodyDesc + ", Build: " + buildDesc);

			assertTrue("Should contain hourglass descriptor", bodyDesc.contains("curvaceous"));
			assertEquals("Build should be 'solid'", "solid", buildDesc);

		} catch (FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	/// ── Statistics description integration ──────────────────────────

	@Test
	public void TestNarrativeDescribeStatistics() {
		logger.info("Test NarrativeUtil.describeStatistics includes body info");

		PersonalityProfile pp = new PersonalityProfile();
		pp.setGender("female");
		pp.setAge(28);

		try {
			BaseRecord person = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
			person.set(FieldNames.FIELD_GENDER, "female");
			person.set("bodyShape", "HOURGLASS");
			person.set("bmi", 22.5);

			BaseRecord stats = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
			stats.set(OlioFieldNames.FIELD_PHYSICAL_STRENGTH, 12);
			stats.set(OlioFieldNames.FIELD_AGILITY, 14);
			stats.set(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE, 12);
			stats.set(OlioFieldNames.FIELD_SPEED, 12);
			stats.set(OlioFieldNames.FIELD_MENTAL_STRENGTH, 12);
			stats.set(OlioFieldNames.FIELD_MENTAL_ENDURANCE, 12);
			stats.set(OlioFieldNames.FIELD_INTELLIGENCE, 14);
			stats.set(OlioFieldNames.FIELD_WISDOM, 12);
			stats.set(OlioFieldNames.FIELD_CHARISMA, 14);
			stats.set(OlioFieldNames.FIELD_CREATIVITY, 12);
			stats.set(OlioFieldNames.FIELD_SPIRITUALITY, 10);
			stats.set(OlioFieldNames.FIELD_LUCK, 12);
			stats.set(OlioFieldNames.FIELD_PERCEPTION, 12);
			stats.set(OlioFieldNames.FIELD_MANUAL_DEXTERITY, 12);
			new MemoryReader().inspect(stats);
			person.set(OlioFieldNames.FIELD_STATISTICS, stats);

			pp.setRecord(person);

			/// Set HighEnumType values for stats description
			pp.setPhysicalStrength(HighEnumType.ELEVATED);
			pp.setPhysicalEndurance(HighEnumType.ELEVATED);
			pp.setIntelligence(HighEnumType.ELEVATED);
			pp.setMagic(HighEnumType.FAIR);
			pp.setLuck(HighEnumType.FAIR);
			pp.setBeauty(HighEnumType.STRONG);
			pp.setWisdom(HighEnumType.FAIR);

			String desc = NarrativeUtil.describeStatistics(pp);
			assertNotNull("Statistics description should not be null", desc);
			assertTrue("Statistics description should not be empty", desc.length() > 0);
			logger.info("Statistics description: " + desc);

			/// Should contain body info
			assertTrue("Should contain build descriptor", desc.contains("fit"));
			assertTrue("Should contain body shape", desc.contains("curvaceous"));

		} catch (FieldException | ModelNotFoundException | ValueException | ReaderException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	/// ── Full provider chain integration tests ──────────────────────

	@Test
	public void TestProviderChainOnFactoryCharacter() {
		logger.info("Test full provider chain: factory charPerson -> roll stats -> roll height -> inspect -> verify derived fields");

		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		try {
			/// Create a charPerson through the factory (properly initializes all nested records)
			BaseRecord person = mf.newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, null);
			assertNotNull("Factory charPerson should not be null", person);

			String name = "BodyStats Test - " + UUID.randomUUID().toString();
			person.set(FieldNames.FIELD_FIRST_NAME, "BodyStats");
			person.set(FieldNames.FIELD_LAST_NAME, "Test");
			person.set(FieldNames.FIELD_NAME, name);
			person.set(FieldNames.FIELD_GENDER, "male");
			person.set("age", 30);
			person.set("alignment", OlioUtil.getRandomAlignment());
			person.set(OlioFieldNames.FIELD_RACE, CharacterUtil.randomRaceType().stream().map(k -> k.toString()).collect(Collectors.toList()));
			person.set("ethnicity", Arrays.asList(new String[] { EthnicityEnumType.ZERO.toString() }));

			/// Get the nested statistics record created by the factory
			BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
			assertNotNull("Factory should create nested statistics record", stats);

			/// Roll statistics (sets base stat values)
			StatisticsUtil.rollStatistics(stats, 30);

			/// Roll height (sets height on statistics)
			List<String> races = person.get(OlioFieldNames.FIELD_RACE);
			String gender = person.get(FieldNames.FIELD_GENDER);
			StatisticsUtil.rollHeight(stats, races, gender, 30);

			/// Verify height was set
			double height = stats.get(OlioFieldNames.FIELD_HEIGHT);
			logger.info("Rolled height: " + height);
			assertTrue("Height should be > 0 after rollHeight", height > 0);

			/// Verify base stats were set
			int str = stats.get(OlioFieldNames.FIELD_PHYSICAL_STRENGTH);
			int agi = stats.get(OlioFieldNames.FIELD_AGILITY);
			int end = stats.get(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE);
			logger.info("Base stats - STR: " + str + ", AGI: " + agi + ", END: " + end);
			assertTrue("Strength should be > 0 after rollStatistics", str > 0);
			assertTrue("Agility should be > 0 after rollStatistics", agi > 0);

			/// Inspect stats to fire virtual field providers (ComputeProvider for maximumHealth, beauty, etc.)
			new MemoryReader().inspect(stats);

			/// Verify maximumHealth was computed (AVG of physicalStrength, physicalEndurance, mentalStrength, mentalEndurance, charisma)
			int maxHealth = stats.get("maximumHealth");
			logger.info("Computed maximumHealth: " + maxHealth);
			assertTrue("maximumHealth should be > 0 after inspect", maxHealth > 0);

			/// Now test weight derivation via BodyStatsProvider
			/// weight is a virtual field on statistics with provider=BodyStatsProvider
			double weight = stats.get(OlioFieldNames.FIELD_WEIGHT);
			logger.info("Derived weight: " + weight + " lbs");
			assertTrue("Weight should be > 0 after provider fires", weight > 0);
			assertTrue("Weight should be reasonable (50-400 lbs)", weight >= 50 && weight <= 400);

			/// Verify BMI computation
			double bmi = BodyStatsProvider.computeBmi(stats);
			logger.info("Computed BMI: " + bmi);
			assertTrue("BMI should be in valid range", bmi >= 15.0 && bmi <= 45.0);

			/// Verify weight formula: weight = (bmi * heightInches^2) / 703
			double heightInches = BodyStatsProvider.heightToInches(height);
			double expectedWeight = (bmi * heightInches * heightInches) / 703.0;
			logger.info("Expected weight from formula: " + expectedWeight + ", actual: " + weight);
			assertEquals("Weight should match formula", expectedWeight, weight, 1.0);

			/// Inspect charPerson to fire its virtual field providers (bmi, bodyType, bodyShape via BodyStatsProvider)
			new MemoryReader().inspect(person);

			/// Now test charPerson-level virtual fields (bmi, bodyType, bodyShape)
			double personBmi = person.get(OlioFieldNames.FIELD_BMI);
			String bodyType = person.get(OlioFieldNames.FIELD_BODY_TYPE);
			String bodyShape = person.get(OlioFieldNames.FIELD_BODY_SHAPE);
			logger.info("Person BMI: " + personBmi + ", bodyType: " + bodyType + ", bodyShape: " + bodyShape);

			assertTrue("Person BMI should be > 0", personBmi > 0);
			assertNotNull("Body type should not be null", bodyType);
			assertNotNull("Body shape should not be null", bodyShape);

			/// Verify body type is a valid enum value
			BodyTypeEnumType btEnum = BodyTypeEnumType.valueOf(bodyType);
			assertNotNull("Body type should be a valid enum", btEnum);

			/// Verify body shape is a valid enum value
			BodyShapeEnumType bsEnum = BodyShapeEnumType.valueOf(bodyShape);
			assertNotNull("Body shape should be a valid enum", bsEnum);

			/// Verify beauty adjustment returns a value for the derived shape
			int beautyAdj = BodyStatsProvider.getBeautyAdjustment(bodyShape, gender);
			logger.info("Beauty adjustment for " + bodyShape + " " + gender + ": " + beautyAdj);

			/// Verify narrative descriptors work with the derived values
			String bmiDesc = BodyStatsProvider.getBmiDescriptor(personBmi);
			String shapeDesc = BodyStatsProvider.getBodyShapeDescriptor(bodyShape, gender);
			logger.info("BMI descriptor: " + bmiDesc + ", Shape descriptor: " + shapeDesc);
			assertNotNull("BMI descriptor should not be null", bmiDesc);
			assertNotNull("Shape descriptor should not be null", shapeDesc);
			assertTrue("BMI descriptor should not be empty", bmiDesc.length() > 0);
			assertTrue("Shape descriptor should not be empty", shapeDesc.length() > 0);

		} catch (FactoryException | FieldException | ModelNotFoundException | ValueException | ReaderException e) {
			logger.error(e);
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	@Test
	public void TestProviderChainMultipleCharacters() {
		logger.info("Test provider chain across multiple characters with different stat distributions");

		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		try {
			/// Create a strong, slow male (should lean MESOMORPH — strength drives meso archetype)
			BaseRecord strongGuy = mf.newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, null);
			strongGuy.set(FieldNames.FIELD_NAME, "Strong Guy - " + UUID.randomUUID().toString());
			strongGuy.set(FieldNames.FIELD_GENDER, "male");
			strongGuy.set("age", 35);

			BaseRecord sgStats = strongGuy.get(OlioFieldNames.FIELD_STATISTICS);
			sgStats.set(OlioFieldNames.FIELD_PHYSICAL_STRENGTH, 18);
			sgStats.set(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE, 16);
			sgStats.set(OlioFieldNames.FIELD_AGILITY, 6);
			sgStats.set(OlioFieldNames.FIELD_SPEED, 5);
			sgStats.set(OlioFieldNames.FIELD_MENTAL_STRENGTH, 10);
			sgStats.set(OlioFieldNames.FIELD_MENTAL_ENDURANCE, 10);
			sgStats.set(OlioFieldNames.FIELD_CHARISMA, 10);
			sgStats.set(OlioFieldNames.FIELD_INTELLIGENCE, 10);
			sgStats.set(OlioFieldNames.FIELD_WISDOM, 10);
			sgStats.set(OlioFieldNames.FIELD_CREATIVITY, 8);
			sgStats.set(OlioFieldNames.FIELD_SPIRITUALITY, 8);
			sgStats.set(OlioFieldNames.FIELD_LUCK, 10);
			sgStats.set(OlioFieldNames.FIELD_PERCEPTION, 8);
			sgStats.set(OlioFieldNames.FIELD_MANUAL_DEXTERITY, 8);
			sgStats.set(OlioFieldNames.FIELD_HEIGHT, 6.00);
			new MemoryReader().inspect(sgStats);
			new MemoryReader().inspect(strongGuy);

			String sgBodyType = strongGuy.get(OlioFieldNames.FIELD_BODY_TYPE);
			double sgBmi = strongGuy.get(OlioFieldNames.FIELD_BMI);
			double sgWeight = sgStats.get(OlioFieldNames.FIELD_WEIGHT);
			logger.info("Strong guy: bodyType=" + sgBodyType + ", BMI=" + sgBmi + ", weight=" + sgWeight);
			assertEquals("High STR male should be MESOMORPH", "MESOMORPH", sgBodyType);
			assertTrue("High STR BMI should be above average (>22)", sgBmi > 22);

			/// Create an agile, light female (should lean ECTOMORPH / RECTANGLE)
			BaseRecord nimbleGal = mf.newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, null);
			nimbleGal.set(FieldNames.FIELD_NAME, "Nimble Gal - " + UUID.randomUUID().toString());
			nimbleGal.set(FieldNames.FIELD_GENDER, "female");
			nimbleGal.set("age", 25);

			BaseRecord ngStats = nimbleGal.get(OlioFieldNames.FIELD_STATISTICS);
			ngStats.set(OlioFieldNames.FIELD_PHYSICAL_STRENGTH, 6);
			ngStats.set(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE, 8);
			ngStats.set(OlioFieldNames.FIELD_AGILITY, 18);
			ngStats.set(OlioFieldNames.FIELD_SPEED, 17);
			ngStats.set(OlioFieldNames.FIELD_MENTAL_STRENGTH, 10);
			ngStats.set(OlioFieldNames.FIELD_MENTAL_ENDURANCE, 10);
			ngStats.set(OlioFieldNames.FIELD_CHARISMA, 12);
			ngStats.set(OlioFieldNames.FIELD_INTELLIGENCE, 14);
			ngStats.set(OlioFieldNames.FIELD_WISDOM, 10);
			ngStats.set(OlioFieldNames.FIELD_CREATIVITY, 12);
			ngStats.set(OlioFieldNames.FIELD_SPIRITUALITY, 10);
			ngStats.set(OlioFieldNames.FIELD_LUCK, 10);
			ngStats.set(OlioFieldNames.FIELD_PERCEPTION, 14);
			ngStats.set(OlioFieldNames.FIELD_MANUAL_DEXTERITY, 14);
			ngStats.set(OlioFieldNames.FIELD_HEIGHT, 5.04);
			new MemoryReader().inspect(ngStats);
			new MemoryReader().inspect(nimbleGal);

			String ngBodyType = nimbleGal.get(OlioFieldNames.FIELD_BODY_TYPE);
			double ngBmi = nimbleGal.get(OlioFieldNames.FIELD_BMI);
			double ngWeight = ngStats.get(OlioFieldNames.FIELD_WEIGHT);
			logger.info("Nimble gal: bodyType=" + ngBodyType + ", BMI=" + ngBmi + ", weight=" + ngWeight);
			assertEquals("High AGI / Low STR should be ECTOMORPH", "ECTOMORPH", ngBodyType);
			assertTrue("High AGI BMI should be below average (<22)", ngBmi < 22);

			/// Verify nimble gal weighs less than strong guy
			assertTrue("Ectomorph female should weigh less than mesomorph male", ngWeight < sgWeight);

			/// Create a balanced character (should be MESOMORPH)
			BaseRecord balanced = mf.newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, null);
			balanced.set(FieldNames.FIELD_NAME, "Balanced Char - " + UUID.randomUUID().toString());
			balanced.set(FieldNames.FIELD_GENDER, "male");
			balanced.set("age", 28);

			BaseRecord bStats = balanced.get(OlioFieldNames.FIELD_STATISTICS);
			bStats.set(OlioFieldNames.FIELD_PHYSICAL_STRENGTH, 12);
			bStats.set(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE, 12);
			bStats.set(OlioFieldNames.FIELD_AGILITY, 12);
			bStats.set(OlioFieldNames.FIELD_SPEED, 12);
			bStats.set(OlioFieldNames.FIELD_MENTAL_STRENGTH, 12);
			bStats.set(OlioFieldNames.FIELD_MENTAL_ENDURANCE, 12);
			bStats.set(OlioFieldNames.FIELD_CHARISMA, 12);
			bStats.set(OlioFieldNames.FIELD_INTELLIGENCE, 12);
			bStats.set(OlioFieldNames.FIELD_WISDOM, 12);
			bStats.set(OlioFieldNames.FIELD_CREATIVITY, 12);
			bStats.set(OlioFieldNames.FIELD_SPIRITUALITY, 12);
			bStats.set(OlioFieldNames.FIELD_LUCK, 12);
			bStats.set(OlioFieldNames.FIELD_PERCEPTION, 12);
			bStats.set(OlioFieldNames.FIELD_MANUAL_DEXTERITY, 12);
			bStats.set(OlioFieldNames.FIELD_HEIGHT, 5.10);
			new MemoryReader().inspect(bStats);
			new MemoryReader().inspect(balanced);

			String bBodyType = balanced.get(OlioFieldNames.FIELD_BODY_TYPE);
			logger.info("Balanced char: bodyType=" + bBodyType);
			assertEquals("Balanced stats should be MESOMORPH", "MESOMORPH", bBodyType);

		} catch (FactoryException | FieldException | ModelNotFoundException | ValueException | ReaderException e) {
			logger.error(e);
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	@Test
	public void TestFullRollCharacterWithBodyStats() {
		logger.info("Test full character roll pipeline with body stats (mirrors TestOlio2.TestRollCharacter)");

		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		try {
			BaseRecord person = mf.newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, null);
			String name = "FullRoll Test - " + UUID.randomUUID().toString();
			person.set(FieldNames.FIELD_FIRST_NAME, "FullRoll");
			person.set(FieldNames.FIELD_LAST_NAME, "Test");
			person.set(FieldNames.FIELD_NAME, name);
			String gender = (Math.random() <= 0.5 ? "male" : "female");
			person.set(FieldNames.FIELD_GENDER, gender);
			int age = new Random().nextInt(18, 60);
			person.set("age", age);
			person.set("alignment", OlioUtil.getRandomAlignment());

			/// Roll race
			List<String> races = CharacterUtil.randomRaceType().stream().map(k -> k.toString()).collect(Collectors.toList());
			person.set(OlioFieldNames.FIELD_RACE, races);
			person.set("ethnicity", Arrays.asList(new String[] { EthnicityEnumType.ZERO.toString() }));

			/// Roll statistics
			BaseRecord stats = person.get(OlioFieldNames.FIELD_STATISTICS);
			assertNotNull("Statistics should exist on factory charPerson", stats);
			StatisticsUtil.rollStatistics(stats, age);

			/// Roll height (new functionality under test)
			StatisticsUtil.rollHeight(stats, races, gender, age);

			/// Roll personality
			ProfileUtil.rollPersonality(person.get(FieldNames.FIELD_PERSONALITY));

			/// Set style by race
			CharacterUtil.setStyleByRace(null, person);

			/// Verify all base stats were rolled
			int str = stats.get(OlioFieldNames.FIELD_PHYSICAL_STRENGTH);
			int agi = stats.get(OlioFieldNames.FIELD_AGILITY);
			int end = stats.get(OlioFieldNames.FIELD_PHYSICAL_ENDURANCE);
			int spd = stats.get(OlioFieldNames.FIELD_SPEED);
			logger.info("Rolled stats - STR: " + str + ", AGI: " + agi + ", END: " + end + ", SPD: " + spd);
			assertTrue("All base stats should be > 0", str > 0 && agi > 0 && end > 0 && spd > 0);

			/// Verify height was rolled
			double height = stats.get(OlioFieldNames.FIELD_HEIGHT);
			logger.info("Rolled height: " + height + " for race " + races + " " + gender + " age " + age);
			assertTrue("Height should be > 0", height > 0);

			/// Inspect stats to trigger virtual field computation (maximumHealth, beauty, weight)
			new MemoryReader().inspect(stats);

			/// Verify derived weight
			double weight = stats.get(OlioFieldNames.FIELD_WEIGHT);
			logger.info("Derived weight: " + weight + " lbs");
			assertTrue("Weight should be > 0", weight > 0);

			/// Inspect charPerson to trigger its virtual field providers (bmi, bodyType, bodyShape)
			new MemoryReader().inspect(person);

			/// Verify charPerson-level derived fields
			double bmi = person.get(OlioFieldNames.FIELD_BMI);
			String bodyType = person.get(OlioFieldNames.FIELD_BODY_TYPE);
			String bodyShape = person.get(OlioFieldNames.FIELD_BODY_SHAPE);
			logger.info("Derived body stats - BMI: " + bmi + ", bodyType: " + bodyType + ", bodyShape: " + bodyShape);
			assertTrue("BMI should be > 0", bmi > 0);
			assertNotNull("bodyType should not be null", bodyType);
			assertNotNull("bodyShape should not be null", bodyShape);

			/// Verify all enums are valid
			assertNotNull("bodyType should be valid enum", BodyTypeEnumType.valueOf(bodyType));
			assertNotNull("bodyShape should be valid enum", BodyShapeEnumType.valueOf(bodyShape));

			/// Verify narrative descriptors work end-to-end
			String buildDesc = NarrativeUtil.describeBuild(person);
			String shapeDesc = NarrativeUtil.describeBodyShape(person);
			logger.info("Narrative - build: " + buildDesc + ", shape: " + shapeDesc);
			assertTrue("Build descriptor should not be empty", buildDesc.length() > 0);
			assertTrue("Shape descriptor should not be empty", shapeDesc.length() > 0);

			/// Summary log
			int feet = (int) height;
			int inches = (int) Math.round((height - feet) * 100);
			logger.info("Complete character: " + name + " (" + gender + ", age " + age + ", " +
				feet + "'" + inches + "\", " + Math.round(weight) + " lbs, " +
				bodyType + ", " + bodyShape + ", BMI " + String.format("%.1f", bmi) + " [" + buildDesc + ", " + shapeDesc + "])");

		} catch (FactoryException | FieldException | ModelNotFoundException | ValueException | ReaderException e) {
			logger.error(e);
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	/// ── Enum validation tests ──────────────────────────────────────

	@Test
	public void TestBodyTypeEnum() {
		logger.info("Test BodyTypeEnumType values");

		BodyTypeEnumType[] types = BodyTypeEnumType.values();
		assertEquals("Should have 3 body types", 3, types.length);
		assertNotNull("ECTOMORPH exists", BodyTypeEnumType.valueOf("ECTOMORPH"));
		assertNotNull("MESOMORPH exists", BodyTypeEnumType.valueOf("MESOMORPH"));
		assertNotNull("ENDOMORPH exists", BodyTypeEnumType.valueOf("ENDOMORPH"));
	}

	@Test
	public void TestBodyShapeEnum() {
		logger.info("Test BodyShapeEnumType values");

		BodyShapeEnumType[] shapes = BodyShapeEnumType.values();
		assertEquals("Should have 6 body shapes", 6, shapes.length);
		assertNotNull("V_TAPER exists", BodyShapeEnumType.valueOf("V_TAPER"));
		assertNotNull("HOURGLASS exists", BodyShapeEnumType.valueOf("HOURGLASS"));
		assertNotNull("RECTANGLE exists", BodyShapeEnumType.valueOf("RECTANGLE"));
		assertNotNull("ROUND exists", BodyShapeEnumType.valueOf("ROUND"));
		assertNotNull("INVERTED_TRIANGLE exists", BodyShapeEnumType.valueOf("INVERTED_TRIANGLE"));
		assertNotNull("PEAR exists", BodyShapeEnumType.valueOf("PEAR"));
	}
}
