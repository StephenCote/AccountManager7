package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.BookContext;
import org.cote.accountmanager.olio.picturebook.PbArtifactUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbWatchedFields;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.PbArtifactTypeEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 3, P3-1: {@code olio.pb.location} and {@code olio.pb.locationVariant} round trip through
 * {@code AccessPoint} against the live {@code am7db} as a non-admin user, the two new nullable FKs on
 * {@code olio.pb.scene} resolve, and the watched set (now {@code watched/v4} since P4-1 added the §8 models;
 * the location entries are unchanged) notices the edits it is declared to notice.
 * <p>
 * Same fixture as {@link TestPbStyleSchema}: org {@code /Development/World Building}, user {@code testUser1},
 * book {@code pb2c-graph}. Locations live in the book world's {@code Locations} group, resolved by FK through
 * {@link PbBookUtil#openBookContext} - never by path - and are created as the test user, whom
 * {@code OlioContext.scanNestedWorldGroups} grants CRUD on every world child group. A PBAC denial there is a
 * finding about that grant, not a cue to fall back to the olio principal.
 */
public class TestPbLocationSchema extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";
	/// A home group for the same-named artifact in case 2: the unique key is (name, groupId, organizationId),
	/// so the collision can only be built across two groups - exactly the series-chapter shape it stands in for.
	private static final String SCRATCH_PATH = "~/PbLocationRefs";

	private static final String[] LOCATION_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_NAME,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
		FieldNames.FIELD_DESCRIPTION, OlioFieldNames.FIELD_PB_KIND, OlioFieldNames.FIELD_PB_WORLD,
		OlioFieldNames.FIELD_PB_ALIASES, OlioFieldNames.FIELD_PB_POI, OlioFieldNames.FIELD_PB_APPROVED_RENDER,
		OlioFieldNames.FIELD_PB_LOCKED
	};

	private static final String[] VARIANT_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_NAME,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
		FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_TIME_OF_DAY, OlioFieldNames.FIELD_PB_WEATHER,
		OlioFieldNames.FIELD_PB_SEASON, OlioFieldNames.FIELD_PB_VIEWPOINT, OlioFieldNames.FIELD_PB_EXTRA,
		OlioFieldNames.FIELD_PB_APPROVED_RENDER
	};

	@Before
	public void schemaSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private long orgOf(BaseRecord u) {
		return (long) u.get(FieldNames.FIELD_ORGANIZATION_ID);
	}

	private BaseRecord book(BaseRecord u) {
		BaseRecord existing = PbBookUtil.findBookBySlug(u, SLUG, orgOf(u));
		if(existing != null) {
			return existing;
		}
		BaseRecord created = PbBookUtil.createBook(u, dataPath(), SLUG, "Phase 2c graph fixture");
		assertNotNull("Failed to create the fixture book", created);
		return created;
	}

	private BookContext bookContext(BaseRecord u, BaseRecord bk) {
		BookContext bc = PbBookUtil.openBookContext(u, bk);
		assertNotNull("The fixture book must carry a world", bc);
		return bc;
	}

	/// The world's Locations group path, by FK. WorldFactory builds it on every world (standalone or series),
	/// so there is no new group to create and BookWorldInitializationRule is untouched.
	private String locationsPath(BaseRecord u, BaseRecord bk) {
		String path = bookContext(u, bk).getGroupPath(FieldNames.FIELD_LOCATIONS);
		assertNotNull("The book world must expose its Locations group", path);
		return path;
	}

	/// The model does not inherit common.name, so applyNameGroupOwnership does not set the name
	/// (TestPbModelSchema documents this) - set it here.
	private BaseRecord newLocation(BaseRecord u, String name, String groupPath, BaseRecord world) {
		BaseRecord loc = null;
		try {
			loc = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, loc, name, groupPath, orgOf(u));
			loc.set(FieldNames.FIELD_NAME, name);
			if(world != null) {
				loc.set(OlioFieldNames.FIELD_PB_WORLD, world);
			}
		}
		catch(Exception e) {
			fail("Failed to assemble olio.pb.location '" + name + "': " + e.getMessage());
		}
		return loc;
	}

	private BaseRecord newVariant(BaseRecord u, String name, String groupPath, BaseRecord location) {
		BaseRecord v = null;
		try {
			v = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION_VARIANT);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, v, name, groupPath, orgOf(u));
			v.set(FieldNames.FIELD_NAME, name);
			v.set(FieldNames.FIELD_LOCATION, location);
		}
		catch(Exception e) {
			fail("Failed to assemble olio.pb.locationVariant '" + name + "': " + e.getMessage());
		}
		return v;
	}

	private BaseRecord create(BaseRecord u, BaseRecord rec) {
		BaseRecord created = ioContext.getAccessPoint().create(u, rec);
		assertNotNull("AccessPoint.create must accept " + rec.getSchema() + " '" + rec.get(FieldNames.FIELD_NAME)
			+ "' in group " + rec.get(FieldNames.FIELD_GROUP_ID) + " as " + TEST_USER, created);
		assertNotNull("create returns identity", created.get(FieldNames.FIELD_OBJECT_ID));
		return created;
	}

	private BaseRecord readFresh(BaseRecord u, String model, String objectId, String[] request) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(request);
		q.setCache(false);
		return ioContext.getAccessPoint().find(u, q);
	}

	private BaseRecord readLocation(BaseRecord u, String objectId) {
		BaseRecord r = readFresh(u, OlioModelNames.MODEL_PB_LOCATION, objectId, LOCATION_REQUEST);
		assertNotNull("olio.pb.location " + objectId + " must be readable by its owner", r);
		return r;
	}

	private BaseRecord readVariant(BaseRecord u, String objectId) {
		BaseRecord r = readFresh(u, OlioModelNames.MODEL_PB_LOCATION_VARIANT, objectId, VARIANT_REQUEST);
		assertNotNull("olio.pb.locationVariant " + objectId + " must be readable by its owner", r);
		return r;
	}

	/// A projected read in exactly the shape computeRefHash uses, so canonicalRef can be inspected.
	private BaseRecord readWatched(BaseRecord u, String model, String objectId) {
		BaseRecord r = readFresh(u, model, objectId, PbWatchedFields.projection(model));
		assertNotNull("watched projection of " + model + " " + objectId, r);
		return r;
	}

	private BaseRecord patch(String model, BaseRecord read, String... changed) {
		String[] fields = new String[3 + changed.length];
		fields[0] = FieldNames.FIELD_ID;
		fields[1] = FieldNames.FIELD_OBJECT_ID;
		fields[2] = FieldNames.FIELD_NAME;
		System.arraycopy(changed, 0, fields, 3, changed.length);
		BaseRecord p = null;
		try {
			p = RecordFactory.newInstance(model, fields);
			p.set(FieldNames.FIELD_ID, read.get(FieldNames.FIELD_ID));
			p.set(FieldNames.FIELD_OBJECT_ID, read.get(FieldNames.FIELD_OBJECT_ID));
			/// validated, so it must travel with the value the record already has
			p.set(FieldNames.FIELD_NAME, read.get(FieldNames.FIELD_NAME));
		}
		catch(Exception e) {
			fail("Failed to assemble a " + model + " patch: " + e.getMessage());
		}
		return p;
	}

	private void update(BaseRecord u, BaseRecord p) {
		assertNotNull("AccessPoint.update must accept the " + p.getSchema() + " patch", ioContext.getAccessPoint().update(u, p));
	}

	private void deleteQuietly(BaseRecord u, BaseRecord rec) {
		if(rec != null) {
			ioContext.getAccessPoint().delete(u, rec);
		}
	}

	// ───────── 1: location + variant + scene FKs round trip create → read → patch → delete as the book writer ─────────

	@Test
	public void case01_locationVariantAndSceneFksRoundTrip() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		long orgId = orgOf(u);
		BookContext bc = bookContext(u, bk);
		String groupPath = locationsPath(u, bk);
		String tag = shortId();

		BaseRecord locRead = null;
		BaseRecord varRead = null;
		BaseRecord scene = null;
		try {
			BaseRecord loc = newLocation(u, "p31-tavern-" + tag, groupPath, bc.getWorld());
			loc.set(FieldNames.FIELD_DESCRIPTION, "A low-beamed tavern, smoke-stained, one long table");
			loc.set(OlioFieldNames.FIELD_PB_KIND, PbLocationKindEnumType.INTERIOR.toString());
			List<String> aliases = loc.get(OlioFieldNames.FIELD_PB_ALIASES);
			aliases.add("the tavern");
			aliases.add("the inn");
			String locOid = create(u, loc).get(FieldNames.FIELD_OBJECT_ID);

			locRead = readLocation(u, locOid);
			assertEquals("p31-tavern-" + tag, locRead.get(FieldNames.FIELD_NAME));
			String urn = locRead.get(FieldNames.FIELD_URN);
			assertTrue("UrnProvider must compose a urn, got '" + urn + "'", urn != null && !urn.isBlank());
			assertEquals("A low-beamed tavern, smoke-stained, one long table", locRead.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals(PbLocationKindEnumType.INTERIOR, locRead.getEnum(OlioFieldNames.FIELD_PB_KIND));
			List<String> aliasesRead = locRead.get(OlioFieldNames.FIELD_PB_ALIASES);
			assertNotNull("aliases persists as a text column and reads back as a list", aliasesRead);
			assertEquals(Arrays.asList("the tavern", "the inn"), aliasesRead);
			BaseRecord worldRef = locRead.get(OlioFieldNames.FIELD_PB_WORLD);
			assertNotNull("The world FK must resolve", worldRef);
			assertEquals((Long) bc.getWorld().get(FieldNames.FIELD_ID), (Long) worldRef.get(FieldNames.FIELD_ID));
			assertEquals("locked is a boolean column: DDL default false, never a JSON default", Boolean.FALSE,
				locRead.get(OlioFieldNames.FIELD_PB_LOCKED));
			assertNull("No POI is linked until P3-2 resolves one", locRead.get(OlioFieldNames.FIELD_PB_POI));
			assertNull("No approved render until P3-4 approves one", locRead.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			long locationsGroupId = bc.getGroup(FieldNames.FIELD_LOCATIONS).get(FieldNames.FIELD_ID);
			assertEquals("Created in the world's Locations group", Long.valueOf(locationsGroupId), locRead.get(FieldNames.FIELD_GROUP_ID));

			BaseRecord var = newVariant(u, "p31-tavern-night-" + tag, groupPath, locRead);
			var.set(OlioFieldNames.FIELD_PB_TIME_OF_DAY, "night");
			var.set(OlioFieldNames.FIELD_PB_WEATHER, "rain");
			var.set(OlioFieldNames.FIELD_PB_SEASON, "late autumn");
			var.set(OlioFieldNames.FIELD_PB_VIEWPOINT, "from the doorway");
			var.set(OlioFieldNames.FIELD_PB_EXTRA, "candles guttering, floor wet");
			String varOid = create(u, var).get(FieldNames.FIELD_OBJECT_ID);

			varRead = readVariant(u, varOid);
			assertEquals("p31-tavern-night-" + tag, varRead.get(FieldNames.FIELD_NAME));
			assertEquals("night", varRead.get(OlioFieldNames.FIELD_PB_TIME_OF_DAY));
			assertEquals("rain", varRead.get(OlioFieldNames.FIELD_PB_WEATHER));
			assertEquals("late autumn", varRead.get(OlioFieldNames.FIELD_PB_SEASON));
			assertEquals("from the doorway", varRead.get(OlioFieldNames.FIELD_PB_VIEWPOINT));
			assertEquals("candles guttering, floor wet", varRead.get(OlioFieldNames.FIELD_PB_EXTRA));
			BaseRecord varLoc = varRead.get(FieldNames.FIELD_LOCATION);
			assertNotNull("variant.location must resolve", varLoc);
			assertEquals(locOid, varLoc.get(FieldNames.FIELD_OBJECT_ID));
			assertNull(varRead.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));

			/// PATCH the location: identity + name + the two changed fields; a locked flip and a kind change.
			BaseRecord lp = patch(OlioModelNames.MODEL_PB_LOCATION, locRead, OlioFieldNames.FIELD_PB_KIND, OlioFieldNames.FIELD_PB_LOCKED);
			lp.set(OlioFieldNames.FIELD_PB_KIND, PbLocationKindEnumType.MIXED.toString());
			lp.set(OlioFieldNames.FIELD_PB_LOCKED, Boolean.TRUE);
			update(u, lp);
			BaseRecord locAfter = readLocation(u, locOid);
			assertEquals(PbLocationKindEnumType.MIXED, locAfter.getEnum(OlioFieldNames.FIELD_PB_KIND));
			assertEquals(Boolean.TRUE, locAfter.get(OlioFieldNames.FIELD_PB_LOCKED));
			assertEquals("Unpatched fields are untouched", "A low-beamed tavern, smoke-stained, one long table", locAfter.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals(Arrays.asList("the tavern", "the inn"), locAfter.get(OlioFieldNames.FIELD_PB_ALIASES));

			/// The scene seam: both FKs nullable, settable by patch, projected by sceneRequest()/readScene.
			int sceneIndex = 9000 + new Random().nextInt(900);
			scene = PbBookUtil.createScene(u, bk, sceneIndex, "p31 scene " + tag, PbBookUtil.bookGroupPath(SLUG));
			assertNotNull(scene);
			assertTrue("sceneRequest() must project location", scene.hasField(FieldNames.FIELD_LOCATION));
			assertTrue("sceneRequest() must project locationVariant", scene.hasField(OlioFieldNames.FIELD_PB_LOCATION_VARIANT));
			assertNull("A new scene has no location", scene.get(FieldNames.FIELD_LOCATION));
			assertNull("A new scene has no variant", scene.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT));

			BaseRecord sp = patch(OlioModelNames.MODEL_PB_SCENE, scene, FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
			sp.set(FieldNames.FIELD_LOCATION, locAfter);
			sp.set(OlioFieldNames.FIELD_PB_LOCATION_VARIANT, varRead);
			update(u, sp);

			BaseRecord sceneAfter = PbBookUtil.readScene(u, scene.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull(sceneAfter);
			BaseRecord sl = sceneAfter.get(FieldNames.FIELD_LOCATION);
			BaseRecord sv = sceneAfter.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
			assertNotNull("scene.location must resolve after the patch", sl);
			assertNotNull("scene.locationVariant must resolve after the patch", sv);
			assertEquals(locOid, sl.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(varOid, sv.get(FieldNames.FIELD_OBJECT_ID));

			/// LIST by the unique key (name, groupId, organizationId) - the shape findByNameOrAlias will use.
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION, FieldNames.FIELD_NAME, "p31-tavern-" + tag);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.field(FieldNames.FIELD_GROUP_ID, locationsGroupId);
			q.setRequest(LOCATION_REQUEST);
			q.setCache(false);
			BaseRecord[] listed = ioContext.getAccessPoint().list(u, q).getResults();
			assertNotNull(listed);
			assertEquals("Exactly one location carries that name in that group", 1, listed.length);
			assertEquals(locOid, listed[0].get(FieldNames.FIELD_OBJECT_ID));

			/// Clear the scene FKs before the location goes, as a UI unlink would.
			BaseRecord unlink = patch(OlioModelNames.MODEL_PB_SCENE, sceneAfter, FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT);
			unlink.set(FieldNames.FIELD_LOCATION, null);
			unlink.set(OlioFieldNames.FIELD_PB_LOCATION_VARIANT, null);
			update(u, unlink);
			BaseRecord unlinked = PbBookUtil.readScene(u, scene.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNull("The scene's location FK is nullable and can be cleared", unlinked.get(FieldNames.FIELD_LOCATION));
			assertNull("...and so is the variant FK", unlinked.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT));

			assertTrue("The owner can delete the variant", ioContext.getAccessPoint().delete(u, varRead));
			varRead = null;
			assertTrue("The owner can delete the location", ioContext.getAccessPoint().delete(u, locAfter));
			locRead = null;
			assertNull("Deleted locations are gone", readFresh(u, OlioModelNames.MODEL_PB_LOCATION, locOid, LOCATION_REQUEST));
			assertNull("Deleted variants are gone", readFresh(u, OlioModelNames.MODEL_PB_LOCATION_VARIANT, varOid, VARIANT_REQUEST));
		}
		finally {
			deleteQuietly(u, scene);
			deleteQuietly(u, varRead);
			deleteQuietly(u, locRead);
		}
	}

	// ───────── 2: the watched set notices kind, description and approvedRender edits; ignores aliases; renders artifacts by objectId ─────────

	@Test
	public void case02_watchedSetDetectsTheEditsItDeclares() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		long orgId = orgOf(u);
		BookContext bc = bookContext(u, bk);
		String groupPath = locationsPath(u, bk);
		String tag = shortId();

		assertEquals("watched/v4", PbWatchedFields.WATCHED_SET_VERSION);
		assertTrue(PbWatchedFields.watchedModels().contains(OlioModelNames.MODEL_PB_LOCATION));
		assertTrue(PbWatchedFields.watchedModels().contains(OlioModelNames.MODEL_PB_LOCATION_VARIANT));
		assertEquals(Arrays.asList(FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION, OlioFieldNames.FIELD_PB_KIND,
			OlioFieldNames.FIELD_PB_APPROVED_RENDER), PbWatchedFields.watchedFields(OlioModelNames.MODEL_PB_LOCATION));
		assertEquals(Arrays.asList(OlioFieldNames.FIELD_PB_TIME_OF_DAY, OlioFieldNames.FIELD_PB_WEATHER,
			OlioFieldNames.FIELD_PB_SEASON, OlioFieldNames.FIELD_PB_VIEWPOINT, OlioFieldNames.FIELD_PB_EXTRA,
			OlioFieldNames.FIELD_PB_APPROVED_RENDER), PbWatchedFields.watchedFields(OlioModelNames.MODEL_PB_LOCATION_VARIANT));
		for(String model : new String[] { OlioModelNames.MODEL_PB_LOCATION, OlioModelNames.MODEL_PB_LOCATION_VARIANT }) {
			ModelSchema ms = RecordFactory.getSchema(model);
			List<String> proj = Arrays.asList(PbWatchedFields.projection(model));
			for(String f : PbWatchedFields.watchedFields(model)) {
				assertNotNull("Watched field " + model + "." + f + " must exist on the model", ms.getFieldSchema(f));
				assertTrue("projection(" + model + ") must carry " + f, proj.contains(f));
			}
		}

		BaseRecord locRead = null;
		BaseRecord varRead = null;
		BaseRecord artifactA = null;
		BaseRecord artifactB = null;
		BaseRecord n = null;
		try {
			BaseRecord loc = newLocation(u, "p31-watch-" + tag, groupPath, bc.getWorld());
			loc.set(FieldNames.FIELD_DESCRIPTION, "a lighthouse on a spit of black rock");
			loc.set(OlioFieldNames.FIELD_PB_KIND, PbLocationKindEnumType.EXTERIOR.toString());
			String locOid = create(u, loc).get(FieldNames.FIELD_OBJECT_ID);
			locRead = readLocation(u, locOid);

			String h0 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION, locOid);
			assertNotNull("A watched model hashes", h0);
			assertEquals("Deterministic", h0, PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION, locOid));

			/// aliases is deliberately unwatched: a synonym does not change what the location looks like
			BaseRecord ap = patch(OlioModelNames.MODEL_PB_LOCATION, locRead, OlioFieldNames.FIELD_PB_ALIASES);
			List<String> al = ap.get(OlioFieldNames.FIELD_PB_ALIASES);
			al.add("the light");
			update(u, ap);
			assertEquals(Arrays.asList("the light"), readLocation(u, locOid).get(OlioFieldNames.FIELD_PB_ALIASES));
			assertEquals("An aliases edit must not change the refHash", h0,
				PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION, locOid));

			BaseRecord kp = patch(OlioModelNames.MODEL_PB_LOCATION, locRead, OlioFieldNames.FIELD_PB_KIND);
			kp.set(OlioFieldNames.FIELD_PB_KIND, PbLocationKindEnumType.MIXED.toString());
			update(u, kp);
			String h1 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION, locOid);
			assertNotEquals("A kind edit must change the refHash", h0, h1);

			BaseRecord dp = patch(OlioModelNames.MODEL_PB_LOCATION, locRead, FieldNames.FIELD_DESCRIPTION);
			dp.set(FieldNames.FIELD_DESCRIPTION, "a lighthouse on a spit of black rock, lamp lit");
			update(u, dp);
			String h2 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION, locOid);
			assertNotEquals("A description edit must change the refHash", h1, h2);

			/// approvedRender: rendered by objectId, not name, because artifact names repeat across the
			/// per-chapter Artifacts groups of a series. Build the collision: artifact A from the real chain,
			/// artifact B with the SAME name in another group.
			BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(SLUG));
			assertNotNull(wf);
			n = PbGraphUtil.addNode(u, wf, "p31-loc-" + tag, PbNodeTypeEnumType.LOCATION, PbBookUtil.workflowGroupPath(SLUG), 0);
			assertNotNull("A LOCATION node must be creatable", n);
			artifactA = PbArtifactUtil.persistArtifact(u, n, "location0", PbArtifactTypeEnumType.TEXT,
				PbBookUtil.artifactGroupPath(SLUG), null, "p3-1 location render stand-in", null, null);
			assertNotNull(artifactA);
			artifactA = PbArtifactUtil.readArtifact(u, artifactA.get(FieldNames.FIELD_OBJECT_ID), orgId);
			String aName = artifactA.get(FieldNames.FIELD_NAME);
			String aOid = artifactA.get(FieldNames.FIELD_OBJECT_ID);

			BaseRecord b = RecordFactory.newInstance(OlioModelNames.MODEL_PB_ARTIFACT);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, b, aName, SCRATCH_PATH, orgId);
			b.set(FieldNames.FIELD_NAME, aName);
			b.set(OlioFieldNames.FIELD_PB_ARTIFACT_TYPE, PbArtifactTypeEnumType.TEXT.toString());
			b.set(OlioFieldNames.FIELD_PB_ROLE, "location0");
			b.set(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(1));
			artifactB = create(u, b);
			String bOid = artifactB.get(FieldNames.FIELD_OBJECT_ID);
			artifactB = PbArtifactUtil.readArtifact(u, bOid, orgId);
			assertNotNull(artifactB);
			assertEquals("Same name in two groups", aName, artifactB.get(FieldNames.FIELD_NAME));
			assertNotEquals(aOid, bOid);

			BaseRecord rpA = patch(OlioModelNames.MODEL_PB_LOCATION, locRead, OlioFieldNames.FIELD_PB_APPROVED_RENDER);
			rpA.set(OlioFieldNames.FIELD_PB_APPROVED_RENDER, artifactA);
			update(u, rpA);
			String canonA = PbWatchedFields.canonicalRef(readWatched(u, OlioModelNames.MODEL_PB_LOCATION, locOid), OlioModelNames.MODEL_PB_LOCATION);
			assertTrue("canonicalRef renders approvedRender by objectId: " + canonA,
				canonA.contains(OlioFieldNames.FIELD_PB_APPROVED_RENDER + "=" + aOid));
			assertFalse("...and never by the (non-unique) name: " + canonA,
				canonA.contains(OlioFieldNames.FIELD_PB_APPROVED_RENDER + "=" + aName));
			String h3 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION, locOid);
			assertNotEquals("Setting approvedRender must change the refHash", h2, h3);

			BaseRecord rpB = patch(OlioModelNames.MODEL_PB_LOCATION, locRead, OlioFieldNames.FIELD_PB_APPROVED_RENDER);
			rpB.set(OlioFieldNames.FIELD_PB_APPROVED_RENDER, artifactB);
			update(u, rpB);
			String h4 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION, locOid);
			assertNotEquals("Swapping to a same-named artifact in another group must change the refHash", h3, h4);

			/// the variant's own set
			BaseRecord var = newVariant(u, "p31-watch-dusk-" + tag, groupPath, locRead);
			var.set(OlioFieldNames.FIELD_PB_TIME_OF_DAY, "dusk");
			var.set(OlioFieldNames.FIELD_PB_WEATHER, "clear");
			String varOid = create(u, var).get(FieldNames.FIELD_OBJECT_ID);
			varRead = readVariant(u, varOid);
			String v0 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION_VARIANT, varOid);
			assertNotNull(v0);
			BaseRecord wp = patch(OlioModelNames.MODEL_PB_LOCATION_VARIANT, varRead, OlioFieldNames.FIELD_PB_WEATHER);
			wp.set(OlioFieldNames.FIELD_PB_WEATHER, "fog");
			update(u, wp);
			String v1 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION_VARIANT, varOid);
			assertNotEquals("A weather edit must change the variant refHash", v0, v1);
			BaseRecord vr = patch(OlioModelNames.MODEL_PB_LOCATION_VARIANT, varRead, OlioFieldNames.FIELD_PB_APPROVED_RENDER);
			vr.set(OlioFieldNames.FIELD_PB_APPROVED_RENDER, artifactA);
			update(u, vr);
			String v2 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_LOCATION_VARIANT, varOid);
			assertNotEquals("Setting the variant approvedRender must change its refHash", v1, v2);
			String canonV = PbWatchedFields.canonicalRef(readWatched(u, OlioModelNames.MODEL_PB_LOCATION_VARIANT, varOid), OlioModelNames.MODEL_PB_LOCATION_VARIANT);
			assertTrue(canonV, canonV.contains(OlioFieldNames.FIELD_PB_APPROVED_RENDER + "=" + aOid));

			/// the two artifacts are node/scratch rows; delete them with the fixture they belong to
			assertTrue(ioContext.getAccessPoint().delete(u, varRead));
			varRead = null;
			assertTrue(ioContext.getAccessPoint().delete(u, readLocation(u, locOid)));
			locRead = null;
			assertTrue(ioContext.getAccessPoint().delete(u, artifactB));
			artifactB = null;
			assertTrue(ioContext.getAccessPoint().delete(u, artifactA));
			artifactA = null;
			assertTrue(ioContext.getAccessPoint().delete(u, n));
			n = null;
		}
		finally {
			/// a failed assertion must not leak a LOCATION node + artifact into the shared pb2c-graph workflow
			deleteQuietly(u, varRead);
			deleteQuietly(u, locRead);
			deleteQuietly(u, artifactB);
			deleteQuietly(u, artifactA);
			deleteQuietly(u, n);
		}
	}

	// ───────── 3: sparse rows read UNKNOWN (no JSON defaults), and the enums carry the Phase 3 shape ─────────

	@Test
	public void case03_sparseLocationReadsUnknownKindAndEnumsHaveThePhase3Shape() throws Exception {
		assertEquals(Arrays.asList(PbLocationKindEnumType.UNKNOWN, PbLocationKindEnumType.INTERIOR,
			PbLocationKindEnumType.EXTERIOR, PbLocationKindEnumType.MIXED, PbLocationKindEnumType.ABSTRACT),
			Arrays.asList(PbLocationKindEnumType.values()));
		assertEquals(PbLocationKindEnumType.INTERIOR, PbLocationKindEnumType.fromValue("INTERIOR"));
		List<PbNodeTypeEnumType> nodeTypes = Arrays.asList(PbNodeTypeEnumType.values());
		assertTrue("LOCATION is a node type", nodeTypes.contains(PbNodeTypeEnumType.LOCATION));
		assertTrue("LANDSCAPE stays", nodeTypes.contains(PbNodeTypeEnumType.LANDSCAPE));

		BaseRecord u = user();
		BaseRecord bk = book(u);
		String groupPath = locationsPath(u, bk);
		BaseRecord created = create(u, newLocation(u, "p31-sparse-" + shortId(), groupPath, null));
		BaseRecord r = readLocation(u, created.get(FieldNames.FIELD_OBJECT_ID));
		try {
			assertEquals("kind is UNKNOWN until set", PbLocationKindEnumType.UNKNOWN, r.getEnum(OlioFieldNames.FIELD_PB_KIND));
			assertNull("world is nullable", r.get(OlioFieldNames.FIELD_PB_WORLD));
			assertNull(r.get(OlioFieldNames.FIELD_PB_POI));
			assertNull(r.get(OlioFieldNames.FIELD_PB_APPROVED_RENDER));
			assertEquals(Boolean.FALSE, r.get(OlioFieldNames.FIELD_PB_LOCKED));
			List<String> aliases = r.get(OlioFieldNames.FIELD_PB_ALIASES);
			assertTrue("aliases reads empty, never null-token garbage", aliases == null || aliases.isEmpty());
		}
		finally {
			assertTrue(ioContext.getAccessPoint().delete(u, r));
		}
	}
}
