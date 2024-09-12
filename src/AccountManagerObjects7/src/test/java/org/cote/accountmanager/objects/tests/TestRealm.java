package org.cote.accountmanager.objects.tests;

public class TestRealm extends BaseTest {
	
	private String universeName = "Universe 3";
	private String worldName = "World 3";
	private String worldPath = "~/Worlds";
	
	/*
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
			new HierarchicalNeedsEvolveRule()
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
			Queue.processQueue();
			
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
			try {
				inter = InteractionUtil.randomInteraction(octx, per1, per2);
				if(inter != null) {
					logger.info(NarrativeUtil.describeInteraction(inter));
				}
			}
			catch(Exception e) {
				e.printStackTrace();
			}
		}
		
		// SDUtil sdu = new SDUtil();
		// sdu.generateSDImages(octx, Arrays.asList(per1), "a park in springtime, circa 1975", "professional photograph", "full body", 1, false, false, -1);
		
		BaseRecord popDir = octx.getWorld().get("population");
		Query q = QueryUtil.buildQuery(testUser1, "olio.charPerson", popDir.get("objectId"), null, 0, 10);
		q.field("firstName", per1.get("firstName"));
		BaseRecord[] qr = ioContext.getSearch().findRecords(q);
		if(qr.length > 0) {
			logger.info(qr[0].toFullString());
		}
		else {
			logger.error("Failed to find Pop");
		}

		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
		
	}
	*/
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
			new HierarchicalNeedsEvolveRule()
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
			Queue.processQueue();
			
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
