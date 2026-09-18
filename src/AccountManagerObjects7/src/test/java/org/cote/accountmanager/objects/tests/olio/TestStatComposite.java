package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.util.ParameterUtil;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.HighEnumType;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.junit.Test;

/// The composite 'beauty' statistic and the narrative labels keyed to it, exercised through the real
/// provider chain (MemoryReader.inspect -> StatCompositeProvider -> ComputeUtil.getSpreadAverage).
///
/// Before the SAVG change, 'beauty' averaged five terms of which three were themselves averages, so it
/// drew on eleven base statistics and retained roughly a fifth of their spread. Measured over 20k rolls
/// it never left 3-14 of its declared 0-20 range, 63.7% of characters were "bland", and "pretty",
/// "beautiful" and "gorgeous" were unreachable at any stat allocation.
public class TestStatComposite extends BaseTest {

	private static final int POP = 3000;
	private static final String[] LABELS = new String[] {"hideous", "homely", "bland", "comely", "pretty", "beautiful", "gorgeous"};

	private BaseRecord newStats() throws FieldException, ModelNotFoundException {
		OlioModelNames.use();
		BaseRecord stats = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_STATISTICS);
		assertNotNull("Statistics record should not be null", stats);
		return stats;
	}

	private String labelOf(BaseRecord stats) {
		int beauty = stats.get("beauty");
		PersonalityProfile prof = new PersonalityProfile();
		prof.setBeauty(HighEnumType.valueOf((beauty * 5) / 100.0));
		return NarrativeUtil.getLooksPrettyUgly(prof);
	}

	/// A composite declared on the same 0-20 scale as its inputs has to be able to use that scale.
	@Test
	public void TestBeautyUsesItsDeclaredRange() {
		try {
			int min = 21;
			int max = -1;
			long sum = 0;
			Map<String, Integer> labels = new LinkedHashMap<>();

			for(int i = 0; i < POP; i++) {
				BaseRecord stats = newStats();
				StatisticsUtil.rollStatistics(stats, 30);
				new MemoryReader().inspect(stats);
				int beauty = stats.get("beauty");
				assertTrue("beauty " + beauty + " outside declared 0-20 range", beauty >= 0 && beauty <= 20);
				sum += beauty;
				if(beauty < min) min = beauty;
				if(beauty > max) max = beauty;
				labels.merge(labelOf(stats), 1, Integer::sum);
			}

			double mean = (double)sum / POP;
			StringBuilder buff = new StringBuilder("\nbeauty over " + POP + " rolled adults: mean "
				+ String.format("%.2f", mean) + ", min " + min + ", max " + max + "\n");
			for(String l : LABELS) {
				int v = labels.getOrDefault(l, 0);
				buff.append(String.format("  %-10s %5d %5.1f%%%n", l, v, (100.0 * v) / POP));
			}
			logger.info(buff.toString());

			/// Every label must actually occur in a rolled population, not merely be reachable in theory.
			for(String l : LABELS) {
				assertTrue("No rolled character was '" + l + "' in " + POP + " rolls", labels.getOrDefault(l, 0) > 0);
			}
			/// ...and none may swallow the population the way 'bland' did at 63.7%.
			for(String l : LABELS) {
				double pct = (100.0 * labels.getOrDefault(l, 0)) / POP;
				assertTrue("'" + l + "' covers " + String.format("%.1f%%", pct) + " of the population", pct < 40.0);
			}
			/// The range has to be exercised at both ends.
			assertTrue("beauty never reached the low end of its range (min " + min + ")", min <= 3);
			assertTrue("beauty never reached the high end of its range (max " + max + ")", max >= 15);
			/// A composite over a fixed point budget should sit near the mean base statistic, not below it.
			double expected = (double)Rules.INITIAL_STATISTICS_ALLOTMENT / StatisticsUtil.getBaseStatisticNames().length;
			assertEquals("mean beauty should track the mean base statistic", expected, mean, 1.5);
		}
		catch(FieldException | ModelNotFoundException | ReaderException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	/// The spread transform must be the identity when a character's appearance statistics match their
	/// overall level. That is what centring on the character's own mean base statistic buys, and it is
	/// the property a fixed centre breaks: centred on the scale midpoint, a character with every base
	/// statistic at 5 came out at 0.
	@Test
	public void TestBeautyIsSelfConsistentOnTheDiagonal() {
		try {
			for(int v : new int[] {2, 5, 8, 10, 14, 18, 20}) {
				BaseRecord stats = newStats();
				for(String f : StatisticsUtil.getBaseStatisticNames()) {
					stats.set(f, v);
				}
				new MemoryReader().inspect(stats);
				assertEquals("all base statistics at " + v + " should give beauty " + v, v, (int)stats.get("beauty"));
				assertEquals("all base statistics at " + v + " should give charm " + v, v, (int)stats.get("charm"));
				assertEquals("all base statistics at " + v + " should give wit " + v, v, (int)stats.get("wit"));
			}
		}
		catch(FieldException | ModelNotFoundException | ValueException | ReaderException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	/// Children roll half the point budget (Rules.INITIAL_STATISTICS_ALLOTMENT_CHILD) and a lower stat
	/// ceiling, so their mean base statistic is about 4.6 rather than 9.3. Centring the spread transform
	/// on a constant tuned for adults amplified that offset into a uniform verdict: measured, 100% of
	/// children came out 'hideous'.
	@Test
	public void TestChildrenAreNotUniformlyHideous() {
		try {
			Map<String, Integer> labels = new LinkedHashMap<>();
			int n = 600;
			for(int i = 0; i < n; i++) {
				BaseRecord stats = newStats();
				StatisticsUtil.rollStatistics(stats, 8);
				new MemoryReader().inspect(stats);
				labels.merge(labelOf(stats), 1, Integer::sum);
			}
			logger.info("beauty labels over " + n + " rolled children: " + labels);
			assertTrue("Children produced only one beauty label: " + labels, labels.size() > 1);
			for(Map.Entry<String, Integer> e : labels.entrySet()) {
				double pct = (100.0 * e.getValue()) / n;
				assertTrue("'" + e.getKey() + "' covers " + String.format("%.1f%%", pct) + " of children", pct < 80.0);
			}
		}
		catch(FieldException | ModelNotFoundException | ReaderException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	/// The narrative wording for the composite lives on olio.narrative.beautyDescription - not as a
	/// second field named 'beauty' on olio.charPerson. Two things have to hold: narrative generation
	/// populates it, and it round-trips through the database (it is a plain string column, which the
	/// boot DDL patch adds to the existing table - no migration).
	@Test
	public void TestNarrativeCarriesTheBeautyWording() {
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		try {
			BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

			/// ProfileUtil.getProfile refuses an unsaved record (it keys its cache on id), so the
			/// character has to be persisted before a profile - and therefore a narrative - exists.
			BaseRecord person = mf.newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null,
				ParameterUtil.newParameterList(FieldNames.FIELD_PATH, "~/Characters"));
			person.set(FieldNames.FIELD_NAME, "Beauty Narrative - " + UUID.randomUUID().toString());
			person.set(FieldNames.FIELD_FIRST_NAME, "Wording");
			person.set(FieldNames.FIELD_GENDER, "female");
			person.set("age", 28);

			BaseRecord stats = person.get("statistics");
			assertNotNull("charPerson should carry a statistics record", stats);
			for(String f : StatisticsUtil.getBaseStatisticNames()) {
				stats.set(f, 18);
			}
			new MemoryReader().inspect(stats);
			assertEquals("flat 18s should give beauty 18", 18, (int)stats.get("beauty"));

			assertTrue("charPerson create failed", ioContext.getRecordUtil().createRecord(person));
			BaseRecord full = OlioUtil.getFullRecord(person);
			assertNotNull("charPerson should read back fully populated", full);

			PersonalityProfile prof = ProfileUtil.getProfile(null, full);
			assertNotNull("profile should resolve for a persisted character", prof);
			String expected = NarrativeUtil.getLooksPrettyUgly(prof);
			logger.info("profile beauty " + prof.getBeauty() + " -> " + expected);

			BaseRecord nar = NarrativeUtil.getNarrative(prof);
			assertNotNull("narrative should be generated", nar);
			assertEquals("narrative should carry the beauty wording", expected, (String)nar.get("beautyDescription"));
			logger.info("narrative.beautyDescription = " + (String)nar.get("beautyDescription"));
		}
		catch(Exception e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	/// beautyDescription is a persisted column now, not a virtual field, so it has to survive a
	/// write/read cycle. This is the part that would break if the boot DDL patch had not added it.
	@Test
	public void TestBeautyWordingPersists() {
		Factory mf = ioContext.getFactory();
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		try {
			BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
			BaseRecord stored = RecordFactory.newInstance(OlioModelNames.MODEL_NARRATIVE);
			stored.set(FieldNames.FIELD_NAME, "beauty-wording-" + UUID.randomUUID().toString());
			stored.set("beautyDescription", "gorgeous");
			stored.set(FieldNames.FIELD_GROUP_ID, getTestGroupId(testUser1, "~/Narratives"));
			assertTrue("narrative create failed", ioContext.getRecordUtil().createRecord(stored));

			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_NARRATIVE, FieldNames.FIELD_OBJECT_ID, stored.get(FieldNames.FIELD_OBJECT_ID));
			q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, "beautyDescription"});
			q.setCache(false);
			BaseRecord read = ioContext.getSearch().findRecord(q);
			assertNotNull("narrative should read back", read);
			assertEquals("beautyDescription should persist", "gorgeous", (String)read.get("beautyDescription"));
		}
		catch(Exception e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}

	private long getTestGroupId(BaseRecord user, String path) throws Exception {
		BaseRecord dir = ioContext.getPathUtil().makePath(user, ModelNames.MODEL_GROUP, path,
			GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		assertNotNull("could not make " + path, dir);
		return (long)dir.get(FieldNames.FIELD_ID);
	}

	/// The provider chain only runs in dependency order because the composite fields declare increasing
	/// priorities: willpower(1) and maximumHealth(10) before physicalAppearance/mentalHealth/wit(15),
	/// before charm(20), before beauty(25). RecordReader.prepareTranslation sorts on that priority; a
	/// field computed before its inputs would silently read their instantiation defaults.
	@Test
	public void TestCompositeChainRespectsPriority() {
		try {
			BaseRecord stats = newStats();
			stats.set("intelligence", 20);
			stats.set("creativity", 20);
			stats.set("charisma", 20);
			for(String f : new String[] {"physicalStrength", "physicalEndurance", "manualDexterity", "agility",
					"speed", "mentalStrength", "mentalEndurance", "wisdom", "spirituality", "luck", "perception"}) {
				stats.set(f, 10);
			}
			new MemoryReader().inspect(stats);

			int wit = stats.get("wit");
			int charm = stats.get("charm");
			assertEquals("wit should be maxed by intelligence/creativity at 20", 20, wit);
			/// charm reads wit, so a charm computed before wit would land far lower
			assertTrue("charm (" + charm + ") should reflect an already-computed wit of " + wit, charm >= 18);
			logger.info("priority chain: wit " + wit + " -> charm " + charm + " -> beauty " + (int)stats.get("beauty"));
		}
		catch(FieldException | ModelNotFoundException | ValueException | ReaderException e) {
			logger.error(e);
			throw new RuntimeException(e);
		}
	}
}
