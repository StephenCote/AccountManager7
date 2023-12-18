package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.util.Strings;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.factory.Factory;
import org.cote.accountmanager.factory.ParticipationFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryField;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBStatementMeta;
import org.cote.accountmanager.io.db.DBWriter;
import org.cote.accountmanager.io.db.StatementUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.objects.generated.PolicyResponseType;

import org.cote.accountmanager.parsers.GenericParser;
import org.cote.accountmanager.parsers.ParseConfiguration;
import org.cote.accountmanager.parsers.ParseMap;
import org.cote.accountmanager.parsers.wordnet.WordNetParser;
import org.cote.accountmanager.parsers.data.DataParseWriter;
import org.cote.accountmanager.parsers.data.WordParser;
import org.cote.accountmanager.parsers.geo.GeoParser;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.GeographyEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.TraitEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.cote.accountmanager.util.AuditUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.CharacterUtil;
import org.cote.accountmanager.olio.EpochUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.PersonalityUtil;
import org.cote.accountmanager.olio.StatisticsUtil;
import org.cote.accountmanager.olio.VeryEnumType;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.WorldUtil;
import org.cote.accountmanager.olio.rules.GenericLocationInitializationRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.junit.Test;

import de.articdive.jnoise.core.api.functions.Interpolation;
import de.articdive.jnoise.core.util.vectors.Vector;
import de.articdive.jnoise.core.util.vectors.Vector2D;
import de.articdive.jnoise.generators.noise_parameters.fade_functions.FadeFunction;
import de.articdive.jnoise.generators.noisegen.opensimplex.SuperSimplexNoiseGenerator;
import de.articdive.jnoise.generators.noisegen.perlin.PerlinNoiseGenerator;
import de.articdive.jnoise.generators.noisegen.worley.WorleyNoiseGenerator;
import de.articdive.jnoise.generators.noisegen.worley.WorleyNoiseResult;
import de.articdive.jnoise.modules.octavation.fractal_functions.FractalFunction;
import de.articdive.jnoise.pipeline.JNoise;
import de.articdive.jnoise.pipeline.JNoiseDetailed;
import de.articdive.jnoise.transformers.domain_warp.DomainWarpTransformer;


public class TestBulkOperation extends BaseTest {

	/*
	 * These unit tests depend on a variety of external data that must be downloaded separately and staged relative to the data path defined in the test resources file.
	 * Data files include: Princeton wordnet dictionary data files, GeoNames file dumps, US baby names, CA surnames, and occuptations.
	 * notes/dataNotes.txt contains the links to these data sources 
	 */
	
	private int bulkLoadSize = 10;

	private boolean resetUniverse = false;
	private boolean resetWorld = true;
	private String worldName = "Demo World";
	private String miniName = "Mini World";
	private String miniSub = "Mini Sub";
	private String subWorldName = "Sub World";
	private String worldPath = "~/Worlds";
	
	/*
	@Test
	public void TestLikelyBrokenParticipations() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		String path = "~/Dooter Peeps - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList("path", path);
		String name = "Person 1";
		plist.parameter("name", name);
		try {
			BaseRecord a1 = ioContext.getFactory().newInstance(ModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a1.set("gender", "male");
			AttributeUtil.addAttribute(a1, "test", true);
			BaseRecord a2 = ioContext.getFactory().newInstance(ModelNames.MODEL_CHAR_PERSON, testUser1, null, plist);
			a2.set(FieldNames.FIELD_NAME, "Person 2");
			a2.set("gender", "female");
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
			
			Query q = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_NAME, "Person 1");
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
			//q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_ATTRIBUTES, "partners", "gender"});
			DBStatementMeta meta = StatementUtil.getSelectTemplate(q);
			Query q2 = QueryUtil.createQuery(ModelNames.MODEL_APPAREL);
			q2.set(FieldNames.FIELD_LIMIT_FIELDS, false);
			DBStatementMeta meta2 = StatementUtil.getSelectTemplate(q2);
			// logger.info("Outside io.db: " + meta.getColumns().stream().collect(Collectors.joining(", ")));
			//logger.info(meta2.getSql());
			/// Access point will force request fields to a finite set if not otherwise defined
			/// When wanting to test foreign recursion of same types, it's necessary to specify the field when using access point, even
			/// when the limit is disabled.  This is intentional since access point is the entry for API calls and conducts policy enforcement
			///
			///
			//BaseRecord rec = ioContext.getAccessPoint().find(testUser1, q);
			BaseRecord rec = ioContext.getSearch().findRecord(q);
			assertNotNull("Record is null", rec);
			List<BaseRecord> parts = rec.get("partners");
			assertTrue("Expected partners to be populated", parts.size() == 1);
			
			BaseRecord attr = AttributeUtil.getAttribute(rec, "test");
			assertNotNull("Expected to find the attribute", attr);
			FieldType ft = attr.getField(FieldNames.FIELD_VALUE);
			logger.info(ft.getName() + " -> " + ft.getValueType().toString());
			boolean attrVal = AttributeUtil.getAttributeValue(rec, "test", false);
			logger.info("Test: " + attrVal);
			
			logger.info(rec.toFullString());
		}
		catch(StackOverflowError | FieldException | ValueException | ModelNotFoundException | FactoryException | ModelException e) {
			logger.error(e);
			e.printStackTrace();
		}

	}
	*/
	
	
	/// Using MGRS-like coding to subdivide the random maps
	///
	@Test
	public void TestGrid() {

		/// World - 60 longitudinal bands (1 - 60) by 20 latitude bands (C to X, not including O)
		/// Grid Zone Designation (GZD) is the intersection 
		/// Next is the 100;000-meter square or 100k ident, which includes squares of 100 x 100 kilometers
		/// The 100K ident is the intersection consisting of a column (A-Z, not including I and O), and a row (A-V, not including I or O)
		/// Eastings: ##### - within a 100K ident on a map with a 1000m grid, the first two numbers come from the label of the grid line west of the position, and the last three digits are the distance in meters from the wester grid line 
		/// 
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		OlioContextConfiguration cfg = new OlioContextConfiguration(
				testUser1,
				testProperties.getProperty("test.datagen.path"),
				worldPath,
				miniName,
				miniSub,
				new String[] {},
				2,
				50,
				false,
				resetUniverse
			);
			/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
			///
			cfg.getContextRules().add(new GridSquareLocationInitializationRule());
			cfg.getEvolutionRules().add(new LocationPlannerRule());
			OlioContext octx = new OlioContext(cfg);

			logger.info("Initialize olio context - Note: This will take a while when first creating a universe");
			octx.initialize();
			assertNotNull("Root location is null", octx.getRootLocation());
			printMapFromAdmin1(octx);

	}
	
	private void printMapFromAdmin1(OlioContext ctx) {
		/// Find the admin1 location of the first location and map that
		///
		BaseRecord[] locs = ctx.getLocations();
		BaseRecord loc = locs[0];
		ioContext.getReader().populate(loc);
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_ID, loc.get(FieldNames.FIELD_PARENT_ID));
		try {
			pq.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		printAdmin1Map(ctx, ioContext.getSearch().findRecord(pq));
		printLocationMaps(ctx);
		
	}
	private void printLocationMaps(OlioContext ctx) {
		for(BaseRecord rec : ctx.getLocations()) {
			printLocationMap(ctx, rec);
		}
	}
	private void printLocationMap(OlioContext ctx, BaseRecord location) {
		logger.info("Printing location " + location.get(FieldNames.FIELD_NAME));
		logger.info("NOTE: This currently expects a GridSquare layout");
		GridSquareLocationInitializationRule rule = new GridSquareLocationInitializationRule();
		ioContext.getReader().populate(location);
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_ID));
		pq.field(FieldNames.FIELD_GROUP_ID, ctx.getWorld().get("locations.id"));
		
		try {
			pq.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		// logger.info(location.toFullString());
		// logger.info(pq.toFullString());
		
		/// Note: finding based only on parentId will span groups
		/// 
		BaseRecord[] cells = ioContext.getSearch().findRecords(pq);
		logger.info("Cell count: " + cells.length);
		int cellWidth = rule.getMapCellWidthM() * 25;
		int cellHeight = rule.getMapCellWidthM() * 25;

		BufferedImage image = new BufferedImage(cellWidth, cellHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		for(BaseRecord cell : cells) {
			String ctype = cell.get("geoType");
			int east = cell.get("eastings");
			int north = cell.get("northings");
			int x = east * 25;
			int y = north * 25;

			logger.info(x + ", " + y);
			/*
			if(ctype.equals("cell")) {
				g2d.setColor(Color.RED);
			}
			else {
				g2d.setColor(Color.WHITE);
			}
			*/
			g2d.setColor(Color.WHITE);
			g2d.fillRect(x, y, 25, 25);
			g2d.setColor(Color.DARK_GRAY);
			g2d.drawRect(x, y, 25, 25);
		}
		g2d.dispose();
		File outputfile = new File("./map - location - " + location.get(FieldNames.FIELD_NAME) + ".png");
		try {
			ImageIO.write(image, "png", outputfile);
		} catch (IOException e) {
			logger.error(e);
		}
		
		// logger.info("PLocs = " + plocs.length);
		// logger.info(location.toFullString());
	}
	
	private void printAdmin1Map(OlioContext ctx, BaseRecord location) {
		logger.info("Printing admin1 location " + location.get(FieldNames.FIELD_NAME));
		logger.info("NOTE: This currently expects a GridSquare layout");
		GridSquareLocationInitializationRule rule = new GridSquareLocationInitializationRule();
		ioContext.getReader().populate(location);
		List<BaseRecord> locs = new ArrayList<>(Arrays.asList(ctx.getLocations()));
		/// This will look for the locations only in the universe, not the world
		/// These are the templates, and the context locations will be substituted for these
		///
		Query pq = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_PARENT_ID, location.get(FieldNames.FIELD_ID));
		pq.field(FieldNames.FIELD_GROUP_ID, location.get(FieldNames.FIELD_GROUP_ID));
		try {
			pq.set(FieldNames.FIELD_LIMIT_FIELDS, false);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		/// Note: finding based only on parentId will span groups
		/// 
		BaseRecord[] plocs = ioContext.getSearch().findRecords(pq);

		int cellWidth = rule.getMapCellWidthM();
		int cellHeight = rule.getMapCellWidthM();

		BufferedImage image = new BufferedImage(rule.getMapWidth1km() * cellWidth, rule.getMapHeight1km() * cellHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2d = image.createGraphics();
		for(BaseRecord uloc : plocs) {
			BaseRecord loc = uloc;
			Optional<BaseRecord> oloc = locs.stream().filter(l -> l.get(FieldNames.FIELD_NAME).equals(uloc.get(FieldNames.FIELD_NAME))).findFirst();
			boolean blot = false;
			if(oloc.isPresent()) {
				loc = oloc.get();
				ioContext.getReader().populate(loc);
				blot = true;
			}
			int east = loc.get("eastings");
			int north = loc.get("northings");
			int x = east * cellWidth;
			int y = north * cellHeight;

			g2d.setColor(Color.DARK_GRAY);
			g2d.drawRect(x, y, cellWidth, cellHeight);
			String type = loc.get("geoType");
			if(blot) {
				g2d.setColor(Color.RED);
			}
			else if(type != null && type.equals("feature")) {
				g2d.setColor(Color.GREEN);
			}
			else {
				g2d.setColor(Color.WHITE);
			}
			g2d.fillRect(x, y, cellWidth, cellHeight);

		}
		g2d.dispose();
		File outputfile = new File("./map - admin1 - " + location.get(FieldNames.FIELD_NAME) + ".png");
		try {
			ImageIO.write(image, "png", outputfile);
		} catch (IOException e) {
			logger.error(e);
		}
		
		// logger.info("PLocs = " + plocs.length);
		// logger.info(location.toFullString());
	}
	
	@Test
	public void TestOlio4() {
		if(true) {
			return;
		}
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		/// Note: Worlds are not currently keyed to a parent, and should be
		
		/// To use only custom locations, send in zero features.  It will be necessary to define a couple locations in a parent location in order to generate the populations
		///
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			testUser1,
			testProperties.getProperty("test.datagen.path"),
			worldPath,
			miniName,
			miniSub,
			new String[] {},
			2,
			50,
			false,
			resetUniverse
		);
		/// Location requirements: Location Count + 2 - you need the 'country', the 'parent', and then the count of locations, where the 'parent' is random
		///
		cfg.getContextRules().add(new GenericLocationInitializationRule("Root Sub", new String[] {"Sub 1", "Sub 2", "Sub 3", "Sub 4", "Sub 5"}));
		cfg.getEvolutionRules().add(new LocationPlannerRule());
		OlioContext octx = new OlioContext(cfg);
		//// Using full country load
		////
		/*
		OlioContext octx = new OlioContext(
			new OlioContextConfiguration(
				testUser1,
				testProperties.getProperty("test.datagen.path"),
				worldPath,
				worldName,
				subWorldName,
				new String[] {"AS", "GB", "IE", "US"},
				2,
				250,
				false,
				resetUniverse
			)
		);
		*/
		logger.info("Initialize olio context - Note: This will take a while when first creating a universe");
		octx.initialize();
		assertTrue("Expected olio context to be initialized", octx.isInitialized());
		if(octx.getCurrentEpoch() == null) {
			octx.generateEpoch();
		}
		
		logger.info("Test start a new epoch");
		BaseRecord test = octx.startEpoch();
		assertNotNull("Epoch is null", test);
		BaseRecord[] locs2 = octx.getLocations();
		BaseRecord testE = octx.startLocationEvent(locs2[0]);
		assertNotNull("Location event is null", testE);
		
		octx.abandonLocationEvent();
		octx.abandonEpoch();
		/*
		logger.info("Test start a new epoch while another epoch is open");
		BaseRecord test2 = EpochUtil.startEpoch(octx);
		assertNull("Epoch should be null", test2);

		logger.info("Cleanup the open epoch");
		octx.abandonEpoch();
		*/
		
		// BaseRecord per = octx.readRandomPerson();
		// assertNotNull("Person is null", per);
		//logger.info(per.toFullString());
		/*
		Query q = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_ID, per.get(FieldNames.FIELD_ID));
		try {
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);
			DBStatementMeta meta = StatementUtil.getSelectTemplate(q);
			// logger.info(meta.getSql());
		} catch (FieldException | ValueException | ModelNotFoundException | ModelException e) {
			logger.error(e);
		}
		*/
		BaseRecord[] locs = GeoLocationUtil.getRegionLocations(testUser1, octx.getWorld());
		assertTrue("Expected two or more locations", locs.length > 0);
		assertNotNull("Location is null", locs[0]);
		// float dist = GeoLocationUtil.calculateDistance(locs[0], locs[1]);
		// logger.info("Distance between " + locs[0].get(FieldNames.FIELD_NAME) + " and " + locs[1].get(FieldNames.FIELD_NAME) + " is " + dist);
		BaseRecord per = null;
		try {
			List<BaseRecord> lpop = octx.getPopulation(locs[0]);
			//FileUtil.emitFile("./tmp.txt", JSONUtil.exportObject(lpop, RecordSerializerConfig.getForeignUnfilteredModule()));
			per = lpop.get((new Random()).nextInt(lpop.size()));
		}
		catch(Exception e) {
			e.printStackTrace();
		}
		assertNotNull("Person is null", per);
		PersonalityProfile prof = PersonalityUtil.analyzePersonality(octx, per);
		logger.info(JSONUtil.exportObject(prof));
		
		for(BaseRecord e : prof.getEvents()) {
			logger.info((String)e.get(FieldNames.FIELD_NAME));
		}
		
		octx.clearCache();
		
	}
	/*
	@Test
	public void TestOlio2() {
		logger.info("Test Olio World Data Loading");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		// 
		BaseRecord world = WorldUtil.getCreateWorld(testUser1, worldPath, worldName, new String[] {"AS", "GB", "IE", "US"});
		assertNotNull("World is null", world);
		WorldUtil.loadWorldData(testUser1, world, testProperties.getProperty("test.datagen.path"), false);
		
		BaseRecord subWorld = WorldUtil.getCreateWorld(testUser1, world, worldPath, subWorldName, new String[0]);
		// logger.info("Cleanup world: " + WorldUtil.cleanupWorld(testUser1, subWorld));
		BaseRecord epoch = null;
		try {

			WorldUtil.generateRegion(testUser1, subWorld, 2, 250);
			epoch = EpochUtil.generateEpoch(testUser1, subWorld, 1);

		}
		catch(Exception e) {
			e.printStackTrace();
		}
		assertNotNull("Expected epoch to be created", epoch);
	}
	*/
	
	/*
	
	assertNotNull("Event is null", event);

	Query qp1 = QueryUtil.createQuery(ModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_GROUP_ID, subWorld.get("population.id"));
	qp1.set(FieldNames.FIELD_LIMIT_FIELDS, false);
	BaseRecord person = OlioUtil.randomSelection(testUser1, qp1);
	assertNotNull("Person is null", person);
	logger.info(person.get(FieldNames.FIELD_NAME) + " is " + CharacterUtil.getCurrentAge(testUser1, subWorld, person) + " years old");
	List<BaseRecord> apps = person.get("apparel");
	if(apps.size() > 0) {
		BaseRecord app = apps.get(0);
		((List<BaseRecord>)app.get("wearables")).forEach(r -> {
			logger.info(r.get("level") + " " + r.get("color") + " " + r.get("fabric") + " " + r.get("pattern.name") + " " + r.get("name"));
		});
	}
	*/
	/*
	BaseRecord[] locs = GeoLocationUtil.getRegionLocations(testUser1, subWorld);
	assertTrue("Expected one or more locations", locs.length > 0);
	long start = System.currentTimeMillis();
	List<BaseRecord> pop = WorldUtil.getPopulation(testUser1, subWorld, locs[0]);
	long stop = System.currentTimeMillis();
	
	assertTrue("Expected a population", pop.size() > 0);
	logger.info("Time to select population: " + (stop - start) + "ms");
	Map<String,List<BaseRecord>> map = WorldUtil.getDemographicMap(testUser1, subWorld, locs[0]);
	map.forEach((k, v) -> {
		logger.info(k + " -- " + v.size());
	});
	map.get("Coupled").forEach(p -> {
		long pid = p.get(FieldNames.FIELD_ID);
		Optional<BaseRecord> popt = map.get("Coupled").stream().filter(f -> ((long)f.get(FieldNames.FIELD_ID) == pid)).findFirst();
		if(popt.isEmpty()) {
			logger.error("Uncoupled warning: " + p.get(FieldNames.FIELD_NAME));
		}
	});
	*/
	/*
	@Test
	public void TestDeepSingleModelQuery() {
		OrganizationContext testOrgContext = getTestOrganization("/Development/World Building");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		List<BaseRecord> aal = new ArrayList<>();
		String path = "~/Demo - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		ParameterList plist = ParameterList.newParameterList("path", path);
		String name = "Dooter - " + UUID.randomUUID().toString();
		plist.parameter("name", name);
		try {
			BaseRecord a1 = ioContext.getFactory().newInstance(ModelNames.MODEL_APPAREL, testUser1, null, plist);
			aal.add(a1);
			List<BaseRecord> wl = a1.get("wearables");
			BaseRecord w1 = ioContext.getFactory().newInstance(ModelNames.MODEL_WEARABLE, testUser1, null, plist);
			wl.add(w1);
			List<BaseRecord> ql = w1.get("qualities");
			BaseRecord q1 = ioContext.getFactory().newInstance(ModelNames.MODEL_QUALITY, testUser1, null, plist);
			ql.add(q1);
			BaseRecord d1 = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, testUser1, null, plist);
			w1.set("pattern", d1);
			d1.set("dataBytesStore", "This is some example text".getBytes());
			ioContext.getAccessPoint().create(testUser1, a1);

			
			Query q = QueryUtil.createQuery(ModelNames.MODEL_APPAREL, FieldNames.FIELD_ID, a1.get(FieldNames.FIELD_ID));
			q.set(FieldNames.FIELD_LIMIT_FIELDS, false);

			// DBStatementMeta meta = StatementUtil.getSelectTemplate(q);
			//logger.info(meta.getSql());
			BaseRecord a2 = ioContext.getSearch().findRecord(q);
			assertNotNull("It's null", a2);

			((DBWriter)ioContext.getWriter()).setDeleteForeignReferences(true);
			ioContext.getAccessPoint().delete(testUser1, a1);
			ioContext.getAccessPoint().delete(testUser1, w1);
			ioContext.getAccessPoint().delete(testUser1, q1);
			ioContext.getAccessPoint().delete(testUser1, d1);
			((DBWriter)ioContext.getWriter()).setDeleteForeignReferences(false);
		}
		catch(ModelNotFoundException | FactoryException | FieldException | ValueException e) {
			logger.error(e);
		}
		

	}
	
	@Test
	public void TestLocationParent() {
		logger.info("Test location parent");
		OrganizationContext testOrgContext = getTestOrganization("/Development/Geolocation");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());

		String groupPath = "~/Bulk/Geo - " + UUID.randomUUID().toString();

		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		String locName = "Parent Loc";
		String chdName = "Child Loc";
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		plist.parameter("name", locName);
		BaseRecord loc = null;
		try {
			loc = ioContext.getFactory().newInstance(ModelNames.MODEL_LOCATION, testUser1, null, plist);
			loc = ioContext.getAccessPoint().create(testUser1, loc);
		} catch (FactoryException e) {
			logger.error(e);
		}
		assertNotNull("Loc is null", loc);

		String[] names = RecordUtil.getCommonFields(ModelNames.MODEL_LOCATION);

		BaseRecord lloc = ioContext.getAccessPoint().findByObjectId(testUser1, ModelNames.MODEL_LOCATION, loc.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("Unable to lookup location", lloc);

	}


	 private BaseRecord newTestData(BaseRecord owner, String path, String name, String textData) {
		ParameterList plist = ParameterList.newParameterList("path", path);
		plist.parameter("name", name);
		BaseRecord data = null;
		try {
			data = ioContext.getFactory().newInstance(ModelNames.MODEL_DATA, owner, null, plist);
			data.set(FieldNames.FIELD_CONTENT_TYPE, "text/plain");
			data.set(FieldNames.FIELD_BYTE_STORE, textData.getBytes());
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return data;
	}

	@Test
	public void TestSingleBatchInsertSameType() {
		logger.info("Testing inserting records one at a time versus by batch");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Batch Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Data - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);
		
		String dataNamePrefix = "Data Test - ";
		String bulkDataNamePrefix = "Bulk Data Test - ";
		List<BaseRecord> bulkLoad = new ArrayList<>();
		List<BaseRecord> bulkLoad2 = new ArrayList<>();
		
		logger.info("Generating dataset 1 - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, dataNamePrefix + (i+1), "This is the example data");
			bulkLoad.add(data);
		}

		long start = System.currentTimeMillis();
		for(int i = 0; i < bulkLoad.size(); i++) {
			ioContext.getAccessPoint().create(testUser1, bulkLoad.get(i));
		}
		long stop = System.currentTimeMillis();
		logger.info("Time to insert by individual record: " + (stop - start) + "ms");
		
		logger.info("Generating dataset 2 - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, bulkDataNamePrefix + (i+1), "This is the example data");
			bulkLoad2.add(data);
		}
		
		start = System.currentTimeMillis();

		ioContext.getAccessPoint().create(testUser1, bulkLoad2.toArray(new BaseRecord[0]));
		
		stop = System.currentTimeMillis();
		logger.info("Time to insert by batch: " + (stop - start) + "ms");
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, bulkDataNamePrefix);
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q);
		assertTrue("Expected to retrieve the batch size", qr.getCount() == bulkLoadSize);
		
	}

	
	@Test
	public void TestBatchUpdate() {
		logger.info("Testing updating a batch of records");
		AuditUtil.setLogToConsole(false);
		OrganizationContext testOrgContext = getTestOrganization("/Development/Batch Testing");
		Factory mf = ioContext.getFactory();
		BaseRecord testUser1 = mf.getCreateUser(testOrgContext.getAdminUser(), "testUser1", testOrgContext.getOrganizationId());
		
		String groupPath = "~/Bulk/Data - " + UUID.randomUUID().toString();
		BaseRecord dir = ioContext.getPathUtil().makePath(testUser1, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), testOrgContext.getOrganizationId());
		assertNotNull("Directory is null", dir);
		
		String bulkDataNamePrefix = "Bulk Data Test - ";
		List<BaseRecord> bulkLoad = new ArrayList<>();
		
		logger.info("Generating dataset - size = " + bulkLoadSize);
		for(int i = 0; i < bulkLoadSize; i++) {
			BaseRecord data = newTestData(testUser1, groupPath, bulkDataNamePrefix + (i+1), "This is the example data");
			bulkLoad.add(data);
		}
		
		long start = System.currentTimeMillis();
		ioContext.getAccessPoint().create(testUser1, bulkLoad.toArray(new BaseRecord[0]));
		long stop = System.currentTimeMillis();
		logger.info("Time to insert by batch: " + (stop - start) + "ms");
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_NAME, ComparatorEnumType.LIKE, bulkDataNamePrefix);
		q.setRequest(new String[] {FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_CONTENT_TYPE});
		QueryResult qr = ioContext.getAccessPoint().list(testUser1, q);
		assertTrue("Expected to retrieve the batch size", qr.getCount() == bulkLoadSize);
		
		BaseRecord[] records = qr.getResults();
		boolean error = false;
		try {
			records[0].set(FieldNames.FIELD_DESCRIPTION, "This is an example description");

			start = System.currentTimeMillis();
			int updated = ioContext.getAccessPoint().update(testUser1, records);
			stop = System.currentTimeMillis();
			logger.info("Time to fail update by batch: " + (stop - start) + "ms");
			
			assertTrue("Expected update to fail because the first record includes a field that the other records do not", updated == 0);
			for(BaseRecord rec: records) {
				rec.set(FieldNames.FIELD_DESCRIPTION, "Patch description: " + UUID.randomUUID().toString());
			}
			
			start = System.currentTimeMillis();
			updated = ioContext.getAccessPoint().update(testUser1, records);
			stop = System.currentTimeMillis();
			logger.info("Time to update by batch: " + (stop - start) + "ms");
			assertTrue("Expected update (" + updated + ") to succeed for all (" + records.length + ") records", updated == records.length);
			
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			error = true;
		}
		assertFalse("Encountered an error", error);
	}
	*/

}
