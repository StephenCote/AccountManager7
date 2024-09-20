package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OverwatchException;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.actions.ActionUtil;
import org.cote.accountmanager.olio.actions.Actions;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
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
		

		


		
		


		/*
		OlioTestUtil.outfitAndStage(octx);
		BaseRecord lrec = octx.getLocations().get(0);
		List<BaseRecord> pop = octx.getPopulation(lrec);
		*/
		
		
		logger.info("Imprint Characters");
		BaseRecord per1 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getDukePrint());
		assertNotNull("Person was null", per2);

		/*
		if(debugBreak) {
			logger.info("Debug check");
			return;
		}
		*/
		/*
		MapUtil.printLocationMap(octx, lrec, realm, pop);
		MapUtil.printRealmMap(octx, realm, Arrays.asList(new BaseRecord[] {per1, per2}));
		MapUtil.printAdmin2Map(octx, GeoLocationUtil.getParentLocation(octx, lrec));
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
			

			// 
		}
		catch (NullPointerException | NumberFormatException | StackOverflowError | OlioException | OverwatchException  e) {
			logger.info(e);
			e.printStackTrace();
		}
		Queue.processQueue();
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

	}
	

	

}
