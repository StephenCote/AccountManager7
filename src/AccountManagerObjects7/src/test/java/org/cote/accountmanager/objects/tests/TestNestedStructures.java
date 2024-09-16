package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.junit.Test;

public class TestNestedStructures extends BaseTest {

	@Test
	public void TestComplexPerson() {
		
		logger.info("Test Complex Person");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Nested Structures");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String path = "~/Dooter Peeps - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path);
		try {
			BaseRecord a1 = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a1.set(FieldNames.FIELD_FIRST_NAME, "Jay");
			a1.set(FieldNames.FIELD_MIDDLE_NAME, "Kippy");
			a1.set(FieldNames.FIELD_LAST_NAME, "Smith");
			a1.set(FieldNames.FIELD_NAME, "Jay Kippy Smith");
			a1.set("instinct", ioContext.getFactory().newInstance(OlioModelNames.MODEL_INSTINCT, testUser1, null, plist));
			a1.set(OlioFieldNames.FIELD_STATISTICS, ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATISTICS, testUser1, null, plist));
			a1.set("personality", ioContext.getFactory().newInstance(ModelNames.MODEL_PERSONALITY, testUser1, null, plist));
			a1.set(FieldNames.FIELD_STATE, ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_STATE, testUser1, null, plist));
			a1.set(FieldNames.FIELD_STORE, ioContext.getFactory().newInstance(OlioModelNames.MODEL_STORE, testUser1, null, plist));
			a1.set("gender", (Math.random() < 0.5 ? "male" : "female"));
			a1.set("age", (new Random()).nextInt(7, 70));
			a1.set("alignment", OlioUtil.getRandomAlignment());
			
			StatisticsUtil.rollStatistics(a1.get(OlioFieldNames.FIELD_STATISTICS), (int)a1.get("age"));
			ProfileUtil.rollPersonality(a1.get("personality"));
			a1.set("race", CharacterUtil.randomRaceType().stream().map(k -> k.toString()).collect(Collectors.toList()));

			CharacterUtil.setStyleByRace(null, a1);
			List<BaseRecord> apps = a1.get("store.apparel");
			BaseRecord app = ApparelUtil.randomApparel(null, a1);
			app.set(FieldNames.FIELD_NAME, "Primary Apparel");
			apps.add(app);
			
			BaseRecord ca1 = IOSystem.getActiveContext().getAccessPoint().create(testUser1, a1);
			assertNotNull("Char Person was null", ca1);
			
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_NAME, a1.get(FieldNames.FIELD_NAME));
			q.planMost(true);
			BaseRecord rec = ioContext.getAccessPoint().find(testUser1, q);
			assertNotNull("Rec is null", rec);
			
			PersonalityProfile pp = ProfileUtil.getProfile(null, rec);
			assertNotNull("Profile is null", pp);

			BaseRecord nar = NarrativeUtil.getNarrative(pp);
			assertNotNull("Narrative is null", nar);
			logger.info(nar.toFullString());

		}
		catch(NullPointerException | ClassCastException | StackOverflowError | FieldException | ValueException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		
	}
	
	@Test
	public void TestPersonSubstruct() {
		logger.info("Test Person Substruct");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Nested Structures");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String path = "~/Dooter Peeps - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path);
		try {
			BaseRecord a1 = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a1.set(FieldNames.FIELD_NAME, "Dooter");
			BaseRecord ca1 = IOSystem.getActiveContext().getAccessPoint().create(testUser1, a1);
			assertNotNull("Char Person was null", ca1);

			BaseRecord pt1 = ioContext.getFactory().newInstance(ModelNames.MODEL_PERSONALITY, testUser1, null, null);
			pt1.set(FieldNames.FIELD_GROUP_PATH, dir.get(FieldNames.FIELD_PATH));
			
			BaseRecord p1 = ioContext.getFactory().newInstance(ModelNames.MODEL_PERSONALITY, testUser1, pt1, null);
			// p1.set(FieldNames.FIELD_GROUP_PATH, dir.get(FieldNames.FIELD_PATH));
			logger.info(p1.toFullString());
			BaseRecord cp1 = IOSystem.getActiveContext().getAccessPoint().create(testUser1, p1);
			assertNotNull("Personality was null", cp1);
			ca1.set("personality", cp1);
			
			BaseRecord up1 = IOSystem.getActiveContext().getAccessPoint().update(testUser1, cp1);
			assertNotNull("Failed to update", up1);
		}
		catch(ClassCastException | StackOverflowError | FieldException | ValueException | ModelNotFoundException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		
	}
	
	@Test
	public void TestPersonConstruct() {
		logger.info("Test Person Construct");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Nested Structures");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		ModelSchema ms = RecordFactory.getSchema(ModelNames.MODEL_WORD_NET_ALT);
		
		String path = "~/Dooter Peeps - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, path);
		String name = "Person 1";
		plist.parameter(FieldNames.FIELD_NAME, name);
		try {
			
			BaseRecord w1 = ioContext.getFactory().newInstance(ModelNames.MODEL_WORD_NET, testUser1, null, plist);
			w1.set(FieldNames.FIELD_NAME, "Dooter");
			List<BaseRecord> alts = w1.get("alternatives");
			BaseRecord wordAlt = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_WORD_NET_ALT);
			wordAlt.set(FieldNames.FIELD_NAME, "Dooter Peeps");
			wordAlt.set("lexId", 1);
			alts.add(wordAlt);

			ioContext.getAccessPoint().create(testUser1, w1);
			
			BaseRecord a1 = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a1.set("gender", "male");
			AttributeUtil.addAttribute(a1, "test", true);
			a1.set(FieldNames.FIELD_CONTACT_INFORMATION, ioContext.getFactory().newInstance(ModelNames.MODEL_CONTACT_INFORMATION, testUser1, null, null));
			BaseRecord a2 = ioContext.getFactory().newInstance(OlioModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a2.set(FieldNames.FIELD_NAME, "Person 2");
			a2.set("gender", "female");
			a2.set(FieldNames.FIELD_CONTACT_INFORMATION, ioContext.getFactory().newInstance(ModelNames.MODEL_CONTACT_INFORMATION, testUser1, null, null));
			AttributeUtil.addAttribute(a2, "test", false);

			/// BUG: When adding cross-relationships such as partnerships, the auto-created participation for one half will wind up missing the other half's identifier (in io.db) because of the auto-participation adds are currently coded within the scope of a single record.
			/// To fix this, participations for all records would need to be pulled out separately, have the record identifiers assigned first, and then bulk add the participations
			/// In the previous version, most model level participations were handled like this.
			/// In the current version, the preference is to keep the participation disconnected from the model factory to avoid having to perform bulk read, update, and deletes to determine what changed on every update
			/// In other words, don't auto-create cross-participations except to be able to make an in-scope reference:

			ioContext.getRecordUtil().createRecords(new BaseRecord[] {a1, a2});
			BaseRecord p1 = ParticipationFactory.newParticipation(testUser1, a1, "partners", a2);
			BaseRecord p2 = ParticipationFactory.newParticipation(testUser1, a2, "partners", a1);
			ioContext.getRecordUtil().createRecords(new BaseRecord[] {p1, p2});

			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_NAME, "Person 1");
			q.planMost(true);
			BaseRecord rec = ioContext.getSearch().findRecord(q);
			assertNotNull("Record is null", rec);
			/// logger.info(rec.toFullString());
			
			/*
			Query q2 = QueryUtil.createQuery(ModelNames.MODEL_CONTACT_INFORMATION, FieldNames.FIELD_ID, rec.get("contactInformation.id"));
			logger.info(Arrays.asList(RecordUtil.getCommonFields(ModelNames.MODEL_CONTACT_INFORMATION)).stream().collect(Collectors.joining(", ")));
			ioContext.getPolicyUtil().setTrace(true);
			BaseRecord crec = ioContext.getAccessPoint().find(testUser1, q2);
			ioContext.getPolicyUtil().setTrace(false);
			assertNotNull("Contact record is null", crec);
			*/

		}
		catch(ClassCastException | StackOverflowError | FieldException | ValueException | ModelNotFoundException | FactoryException | ModelException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
}
