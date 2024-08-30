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
		
		OlioTestUtil.setResetWorld(true);
		//OlioTestUtil.setResetUniverse(true);
		 
		OlioContext octx = null;
		try{
			octx = OlioTestUtil.getContext(testOrgContext, dataPath);
		}
		catch(StackOverflowError | Exception e) {
			e.printStackTrace();
		}
		/*
		if(debugBreak) {
			logger.info("Debug check");
			return;
		}
		*/
		assertNotNull("Context is null", octx);



		
		OlioTestUtil.outfitAndStage(octx);
		BaseRecord lrec = octx.getLocations()[0];
		List<BaseRecord> pop = octx.getPopulation(lrec);
		
		logger.info("Imprint Characters");
		BaseRecord per1 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getDukePrint());
		assertNotNull("Person was null", per2);

		BaseRecord pact = null;
		try {
			
			pact = ActionUtil.getInAction(per1, "look");
			if(pact != null) {
				pact.setValue("type", ActionResultEnumType.INCOMPLETE);
				octx.queueUpdate(pact, new String[] {"type"});
			}
			
			pact = Actions.beginLook(octx, octx.getCurrentIncrement(), per1);
			octx.overwatchActions();
			logger.info(pact.toString());
			
			pact = Actions.beginGather(octx, octx.getCurrentIncrement(), per1, "water", 3);
			octx.overwatchActions();
			// logger.info(pact.toString());
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

			// NumberFormatException | StackOverflowError | OlioException | Overwatch
		} catch (Exception  e) {
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
