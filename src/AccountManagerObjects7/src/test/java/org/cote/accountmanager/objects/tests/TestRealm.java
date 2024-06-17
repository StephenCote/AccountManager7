package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.RaceEnumType;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ESRBEnumType;
import org.cote.accountmanager.olio.llm.OllamaRequest;
import org.cote.accountmanager.olio.llm.PromptConfiguration;
import org.cote.accountmanager.olio.llm.PromptRaceConfiguration;
import org.cote.accountmanager.olio.llm.PromptUtil;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.LibraryUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.junit.Test;

public class TestRealm extends BaseTest {
	
	private String universeName = "Universe 3";
	private String worldName = "World 3";
	private String worldPath = "~/Worlds";
	
	@Test
	public void TestRealm() {
		logger.info("Test Realm");
		AuditUtil.setLogToConsole(false);
	
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		ioContext.getAccessPoint().setPermitBulkContainerApproval(true);
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			testUser1,
			testProperties.getProperty("test.datagen.path"),
			worldPath,
			universeName,
			worldName,
			new String[] {},
			2,
			50,
			false,
			false
		);
	
		/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
		///
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new GridSquareLocationInitializationRule(),
			new LocationPlannerRule(),
			new GenericItemDataLoadRule()
		}));
		
		// Increment24HourRule incRule = new Increment24HourRule();
		// incRule.setIncrementType(TimeEnumType.HOUR);
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
			new Increment24HourRule(),
			new HierarchicalNeedsRule()
		}));

		OlioContext octx = new OlioContext(cfg);
		octx.initialize();
		assertNotNull("Root location is null", octx.getRootLocation());
		
		BaseRecord evt = octx.startOrContinueEpoch();
		BaseRecord levt = null;
		BaseRecord cevt = null;
		assertNotNull("Epoch is null", evt);
		BaseRecord[] locs = octx.getLocations();
		for(BaseRecord lrec : locs) {
			
			/// Depending on the staging rule, the population may not yet be dressed or have possessions
			///
			ApparelUtil.outfitAndStage(octx, null, octx.getPopulation(lrec));
			ItemUtil.showerWithMoney(octx, octx.getPopulation(lrec));
			octx.processQueue();
			
			levt = octx.startOrContinueLocationEpoch(lrec);
			assertNotNull("Location epoch is null", levt);
			cevt = octx.startOrContinueIncrement();
			octx.evaluateIncrement();
		}

		BaseRecord[] realms = octx.getRealms();
		assertTrue("Expected at least one realm", realms.length > 0);
		BaseRecord popGrp = realms[0].get("population");
		assertNotNull("Expected a population group", popGrp);

		List<BaseRecord> pop  = OlioUtil.listGroupPopulation(octx, popGrp);
		assertTrue("Expected a population", pop.size() > 0);

		BaseRecord per1 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord per2 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord inter = null;
		for(int i = 0; i < 10; i++) {
			inter = InteractionUtil.randomInteraction(octx, per1, per2);
			if(inter != null) {
				logger.info(NarrativeUtil.describeInteraction(inter));
			}
		}

		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		
	}
	
	/*
	@Test
	public void TestRealm() {
		logger.info("Test Realm");
		AuditUtil.setLogToConsole(false);
		
		OrganizationContext testOrgContext = getTestOrganization("/Development/Realm");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		BaseRecord testUser2 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser2", testOrgContext.getOrganizationId());
		
		BaseRecord dir = LibraryUtil.getCreateSharedLibrary(testUser1, "authZTest", true);
		boolean canRead = IOSystem.getActiveContext().getAuthorizationUtil().canRead(testUser1, testUser1, dir).getType() == PolicyResponseEnumType.PERMIT;
		boolean canUp = IOSystem.getActiveContext().getAuthorizationUtil().canUpdate(testUser1, testUser1, dir).getType() == PolicyResponseEnumType.PERMIT;
		assertTrue("Expected to be able to read", canRead);
		assertTrue("Expected to be able to update", canUp);
		

		BaseRecord cdir = LibraryUtil.getCreateSharedLibrary(testUser1, "colors", true);
		//BaseRecord[] recs = OlioUtil.randomSelections(testUser1, QueryUtil.createQuery(ModelNames.MODEL_COLOR, FieldNames.FIELD_GROUP_ID, cdir.get(FieldNames.FIELD_ID)), 30);
		
		//assertTrue("Expected some records", recs.length > 0);
		ioContext.getAccessPoint().setPermitBulkContainerApproval(true);
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			testUser1,
			testProperties.getProperty("test.datagen.path"),
			worldPath,
			universeName,
			worldName,
			new String[] {},
			2,
			50,
			false,
			false
		);
	
		/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
		///
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new GridSquareLocationInitializationRule(),
			new LocationPlannerRule(),
			new GenericItemDataLoadRule()
		}));
		
		// Increment24HourRule incRule = new Increment24HourRule();
		// incRule.setIncrementType(TimeEnumType.HOUR);
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
			new Increment24HourRule(),
			new HierarchicalNeedsRule()
		}));

		OlioContext octx = new OlioContext(cfg);
		octx.initialize();
		assertNotNull("Root location is null", octx.getRootLocation());
		
		BaseRecord evt = octx.startOrContinueEpoch();
		assertNotNull("Epoch is null", evt);
		BaseRecord[] locs = octx.getLocations();
		for(BaseRecord lrec : locs) {
			
			/// Depending on the staging rule, the population may not yet be dressed or have possessions
			///
			ApparelUtil.outfitAndStage(octx, null, octx.getPopulation(lrec));
			ItemUtil.showerWithMoney(octx, octx.getPopulation(lrec));
			octx.processQueue();
			
			BaseRecord levt = octx.startOrContinueLocationEpoch(lrec);
			assertNotNull("Location epoch is null", levt);
			BaseRecord cevt = octx.startOrContinueIncrement();
			octx.evaluateIncrement();
		}

		BaseRecord[] realms = octx.getRealms();
		assertTrue("Expected at least one realm", realms.length > 0);
		BaseRecord popGrp = realms[0].get("population");
		assertNotNull("Expected a population group", popGrp);

		List<BaseRecord> pop  = OlioUtil.listGroupPopulation(octx, popGrp);
		assertTrue("Expected a population", pop.size() > 0);

		BaseRecord per1 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord per2 = pop.get((new Random()).nextInt(pop.size()));
		BaseRecord inter = null;
		for(int i = 0; i < 10; i++) {
			inter = InteractionUtil.randomInteraction(octx, per1, per2);
			if(inter != null) {
				break;
			}
		}
		logger.info(NarrativeUtil.describe(octx, per1));
		logger.info(NarrativeUtil.describe(octx, per2));
		logger.info(NarrativeUtil.describeInteraction(inter));
		
		AuditUtil.setLogToConsole(true);
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);

	}
	*/
}
