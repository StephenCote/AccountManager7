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

	@Test
	public void TestOlioCanMove() {
		
		logger.info("Test Olio Rules - CanMove");
		

		
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String dataPath = testProperties.getProperty("test.datagen.path");
		
		// OlioTestUtil.setResetWorld(true);
		// OlioTestUtil.setResetUniverse(true);
		OlioContext octx = OlioTestUtil.getContext(testOrgContext, dataPath);
		
		BaseRecord lrec = octx.getLocations()[0];
		List<BaseRecord> pop = octx.getPopulation(lrec);
		
		logger.info("Imprint Characters");
		BaseRecord per1 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getLaurelPrint());
		assertNotNull("Person was null", per1);
		BaseRecord per2 = OlioTestUtil.getImprintedCharacter(octx, pop, OlioTestUtil.getDukePrint());
		assertNotNull("Person was null", per2);
		
		octx.enroleAdmin(testUser1);
		
		BaseRecord cfg = ChatUtil.getCreateChatConfig(testUser1, "Chat - " + UUID.randomUUID().toString());
		
		try {
			BaseRecord inter = null;
			List<BaseRecord> inters = new ArrayList<>();
			for(int i = 0; i < 10; i++) {
				inter = InteractionUtil.randomInteraction(octx, per1, per2);
				if(inter != null) {
					inters.add(inter);
				}
			}
			IOSystem.getActiveContext().getRecordUtil().createRecords(inters.toArray(new BaseRecord[0]));
			
			cfg.set("event", octx.getCurrentIncrement());
			cfg.set("universeName", octx.getUniverse().get("name"));
			cfg.set("worldName", octx.getWorld().get("name"));
			cfg.set("startMode", "system");
			cfg.set("assist", true);
			cfg.set("useNLP", true);
			cfg.set("setting", "random");
			cfg.set("includeScene", false);
			cfg.set("prune", true);
			cfg.set("rating", ESRBEnumType.RC);

			cfg.set("llmModel", "fim-local");
			cfg.set("systemCharacter", per2);
			cfg.set("userCharacter", per1);
			cfg.set("interactions", inters);
			cfg.set("terrain", NarrativeUtil.getTerrain(octx, per1));
			NarrativeUtil.describePopulation(octx, cfg);
			// IOSystem.getActiveContext().getPolicyUtil().setTrace(true);
			cfg = IOSystem.getActiveContext().getAccessPoint().update(testUser1, cfg);
			assertNotNull("Config was null", cfg);
			// IOSystem.getActiveContext().getPolicyUtil().setTrace(false);
		}
		catch(StackOverflowError | ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
			e.printStackTrace();
		}
		
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
