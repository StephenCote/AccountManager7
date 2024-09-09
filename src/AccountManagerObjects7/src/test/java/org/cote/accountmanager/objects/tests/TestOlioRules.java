package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.objects.generated.FactType;
import org.cote.accountmanager.objects.generated.PolicyDefinitionType;
import org.cote.accountmanager.objects.generated.PolicyRequestType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.MapUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OlioPolicyUtil;
import org.cote.accountmanager.olio.OverwatchException;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.actions.ActionUtil;
import org.cote.accountmanager.olio.actions.Actions;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.policy.FactUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;
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


		OlioTestUtil.outfitAndStage(octx);
		BaseRecord lrec = octx.getLocations().get(0);
		List<BaseRecord> pop = octx.getPopulation(lrec);
		
		/*
		if(debugBreak) {
			logger.info("Debug check");
			return;
		}
		*/
		
		logger.info("Imprint Characters");
		BaseRecord per1 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getDukePrint());
		assertNotNull("Person was null", per2);

		BaseRecord realm = octx.getRealm(lrec);
		
		
		MapUtil.printLocationMap(octx, lrec, realm, pop);
		MapUtil.printRealmMap(octx, realm, Arrays.asList(new BaseRecord[] {per1, per2}));
		MapUtil.printAdmin2Map(octx, GeoLocationUtil.getParentLocation(octx, lrec));
		
		
		/*
		List<BaseRecord> zoo = realm.get("zoo");
		List<BaseRecord> vzoo = GeoLocationUtil.limitToAdjacent(octx, zoo, per1.get("state.currentLocation"));
		logger.info("View Zoo: " + vzoo.size());
		for(BaseRecord a: zoo) {
			double dist = GeoLocationUtil.getDistanceToState(per1.get("state"), a.get("state"));
			logger.info(a.get("name") + " " + dist + "m");
		}
		*/
		BaseRecord pact = null;
		try {
			
			pact = ActionUtil.getInAction(per1, "look");
			if(pact != null) {
				pact.setValue("type", ActionResultEnumType.INCOMPLETE);
				octx.queueUpdate(pact, new String[] {"type"});
			}
			/// Epochs and increments are currently pinned against the root location of a realm (which made sense at some point and now just seems like it causes more problems than it solves)
			/// Therefore, in order to process an action for an actor, it's necessary to start or continue the epoch and increment for their root location in order for the context to be correct, such as if trying to obtain the realm based on the context location, which would return the last location processed, not the correct location
			///
			///
			octx.startOrContinueLocationEpoch(lrec);
			octx.startOrContinueIncrement();
			
			pact = Actions.beginLook(octx, octx.getCurrentIncrement(), per1);
			octx.overwatchActions();
			logger.info(pact.toString());
			
			pact = Actions.beginGather(octx, octx.getCurrentIncrement(), per1, "water", 3);
			octx.overwatchActions();
			logger.info(pact.toString());
			/*
			pact = Actions.beginPeek(octx, octx.getCurrentIncrement(), per1, per1);
			octx.overwatchActions();
			logger.info(pact.toString());
			
			pact = Actions.beginUndress(octx, octx.getCurrentIncrement(), per1, null, WearLevelEnumType.BASE);
			octx.overwatchActions();
			logger.info(pact.toString());

			pact = Actions.beginDress(octx, octx.getCurrentIncrement(), per1, null, WearLevelEnumType.ACCESSORY);
			octx.overwatchActions();
			logger.info(pact.toString());
			*/

			// 
		}
		catch (NullPointerException | NumberFormatException | StackOverflowError | OlioException | OverwatchException  e) {
			logger.info(e);
			e.printStackTrace();
		}
		octx.processQueue();
		/*
		BaseRecord mact = null;
		try {
			mact = ActionUtil.getInAction(per1, "walkTo");
			if(mact != null) {
				mact.set("type", ActionResultEnumType.INCOMPLETE);
				octx.queueUpdate(mact, new String[] {"type"});
			}
			mact = Actions.beginMoveTo(octx, octx.getCurrentIncrement(), per1, per2);
			octx.overwatchActions();
		} catch (OlioException | OverwatchException | FieldException | ValueException | ModelNotFoundException e) {
			logger.info(e);
		}
		
		octx.processQueue();
		*/

	}
	

	

}
