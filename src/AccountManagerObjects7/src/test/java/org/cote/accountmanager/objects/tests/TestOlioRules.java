package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.junit.Test;

public class TestOlioRules extends BaseTest {

	private boolean debugBreak = true;
	
	@Test
	public void TestOlioCanMove() {
		
		logger.info("Test Olio Rules - CanMove");

		Factory mf = ioContext.getFactory();

		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");;
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		
		String dataPath = testProperties.getProperty("test.datagen.path");
		
		// OlioTestUtil.setResetWorld(true);
		// OlioTestUtil.setResetUniverse(true);
		 
		OlioContext octx = null;
		try{
			octx = OlioTestUtil.getContext(testOrgContext, dataPath);
		}
		catch(StackOverflowError | Exception e) {
			e.printStackTrace();
		}
		assertNotNull("Context is null", octx);

		List<BaseRecord> realms = octx.getRealms();
		assertTrue("Expected some realms", realms.size() > 0);
		BaseRecord realm = realms.get(0);
		BaseRecord lrec = realm.get(OlioFieldNames.FIELD_ORIGIN);
		BaseRecord popGroup = realm.get(OlioFieldNames.FIELD_POPULATION);
		assertNotNull("Pop group is null", popGroup);
		List<BaseRecord> pop = octx.getRealmPopulation(realm);
		
		logger.info("Imprint Characters");
		BaseRecord per1 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getDukePrint());
		assertNotNull("Person was null", per2);

		//logger.info(per1.toFullString());
		// logger.info(per2.toFullString());
		
		
		List<BaseRecord> inters = new ArrayList<>();
		BaseRecord inter = null;
		for(int i = 0; i < 10; i++) {
			inter = InteractionUtil.randomInteraction(octx, per2, per1);
			if(inter != null) {
				inters.add(inter);
				//break;
			}
		}

		inter = inters.get((new Random()).nextInt(inters.size()));
		
		logger.info(inter.toForeignFilteredString());

		/*
		String scene = ChatUtil.generateAutoScene(octx, per2, per1, inter, "fim-local", NarrativeUtil.getRandomSetting(), null);
		logger.info("Scene: " + scene);
		*/
		
		
		

		/*

		BaseRecord pact = null;
		try {
			
			pact = ActionUtil.getInAction(per1, "look");
			if(pact != null) {
				pact.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.INCOMPLETE);
				Queue.queueUpdate(pact, new String[] {FieldNames.FIELD_TYPE});
			}
			/// Epochs and increments are currently pinned against the root location of a realm (which made sense at some point and now just seems like it causes more problems than it solves)
			/// Therefore, in order to process an action for an actor, it's necessary to start or continue the epoch and increment for their root location in order for the context to be correct, such as if trying to obtain the realm based on the context location, which would return the last location processed, not the correct location
			///
			///
			
			pact = Actions.beginLook(octx, octx.clock().getIncrement(), per1);
			octx.overwatchActions();
			logger.info(pact.toString());
			
			pact = Actions.beginGather(octx, octx.clock().getIncrement(), per1, "water", 3);
			octx.overwatchActions();
			logger.info(pact.toString());
			
			pact = Actions.beginPeek(octx, octx.clock().getIncrement(), per1, per1);
			octx.overwatchActions();
			logger.info(pact.toString());
			
			pact = Actions.beginUndress(octx, octx.clock().getIncrement(), per1, null, WearLevelEnumType.BASE);
			octx.overwatchActions();
			logger.info(pact.toString());

			pact = Actions.beginDress(octx, octx.clock().getIncrement(), per1, null, WearLevelEnumType.ACCESSORY);
			octx.overwatchActions();
			logger.info(pact.toString());

		}
		catch (NullPointerException | NumberFormatException | StackOverflowError | OlioException | OverwatchException  e) {
			logger.info(e);
			e.printStackTrace();
		}
		Queue.processQueue();
		*/
		
		BaseRecord eper1 = ChatUtil.getFilteredCharacter(per1);
		logger.info(eper1.toFullString());

		BaseRecord iinter = ChatUtil.getFilteredInteraction(inter);
		logger.info(iinter.toFullString());

	}
	

	
	/*
		MapUtil.printLocationMap(octx, lrec, realm, pop);
		MapUtil.printRealmMap(octx, realm, Arrays.asList(new BaseRecord[] {per1, per2}));
		MapUtil.printAdmin2Map(octx, GeoLocationUtil.getParentLocation(octx, lrec));
		*/
	
	/*
	 	
		/*
		String cfgName = "chat.config - " + UUID.randomUUID().toString();
		BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser1, cfgName);
		assertNotNull("CFG is null", cfg);

		IOSystem.getActiveContext().getRecordUtil().createRecords(inters.toArray(new BaseRecord[0]));
		try {
			cfg.set("universeName", octx.getUniverse().get(FieldNames.FIELD_NAME));
			cfg.set("worldName", octx.getWorld().get(FieldNames.FIELD_NAME));
			cfg.set("startMode", "system");
			cfg.set("assist", false);
			cfg.set("useNLP", false);
			cfg.set("nlpCommand", null);
			cfg.set("useJailBreak", false);
			cfg.set("setting", "random");
			cfg.set("includeScene", true);
			cfg.set("prune", false);
			cfg.set("rating", ESRBEnumType.T);
			cfg.set("llmModel", "fim-local");
			cfg.set("systemCharacter", per2);
			cfg.set("userCharacter", per1);
			cfg.set(OlioFieldNames.FIELD_INTERACTIONS, inters);
			for(BaseRecord i : inters) {
				if(i != null) {
					IOSystem.getActiveContext().getMemberUtil().member(testUser1, cfg, OlioFieldNames.FIELD_INTERACTIONS, i, null, true);
				}
			}
			NarrativeUtil.describePopulation(octx, cfg);
			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser1, cfg);
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		
		CacheUtil.clearCache();
		
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, "~/Chat", "DATA", testUser1.get(FieldNames.FIELD_ORGANIZATION_ID));
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAT_CONFIG, FieldNames.FIELD_NAME, cfgName);
		q.field(FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		
		q.planCommon(false);
		q.planField(OlioFieldNames.FIELD_INTERACTIONS);
		q.setValue("debug", true);
		
		BaseRecord cfg2 = IOSystem.getActiveContext().getSearch().findRecord(q);
		//BaseRecord cfg2 = ChatUtil.getCreateChatConfig(testUser1, cfgName);
		inters = cfg2.get(OlioFieldNames.FIELD_INTERACTIONS);

		logger.info("Inters: " + inters.size());
		assertTrue("Expected interactions", inters.size() > 0);

	 */

	/*
	 		BaseRecord mact = null;
		try {
			mact = ActionUtil.getInAction(per1, "walkTo");
			if(mact != null) {
				mact.set(FieldNames.FIELD_TYPE, ActionResultEnumType.INCOMPLETE);
				Queue.queueUpdate(mact, new String[] {FieldNames.FIELD_TYPE});
			}
			mact = Actions.beginMoveTo(octx, octx.getCurrentIncrement(), per1, per2);
			octx.overwatchActions();
		} catch (OlioException | OverwatchException | FieldException | ValueException | ModelNotFoundException e) {
			logger.info(e);
		}
		
		Queue.processQueue();
	 */
	
	/*
	List<BaseRecord> zoo = realm.get(OlioFieldNames.FIELD_ZOO);
	List<BaseRecord> vzoo = GeoLocationUtil.limitToAdjacent(octx, zoo, per1.get(OlioFieldNames.FIELD_STATE_CURRENT_LOCATION));
	logger.info("View Zoo: " + vzoo.size());
	for(BaseRecord a: zoo) {
		double dist = GeoLocationUtil.getDistanceToState(per1.get(FieldNames.FIELD_STATE), a.get(FieldNames.FIELD_STATE));
		logger.info(a.get(FieldNames.FIELD_NAME) + " " + dist + "m");
	}
	*/
	
}
