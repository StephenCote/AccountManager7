package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.PointOfInterestEnumType;
import org.cote.accountmanager.olio.PointOfInterestUtil;
import org.cote.accountmanager.olio.picturebook.BookContext;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbDeleteUtil;
import org.cote.accountmanager.olio.picturebook.PbHealthUtil;
import org.cote.accountmanager.olio.picturebook.PbLocationUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 3, P3-2: {@link PbLocationUtil} resolves a setting to one {@code olio.pb.location} per book world and
 * links it to a point of interest per design §7.2 rules 1, 2, 3 and 5, against the live {@code am7db}:
 * <ol>
 *  <li>the same setting resolved three times (name, alias, alias) is one location, one map-free
 *      {@code olio.pointOfInterest} and one {@code olio.store}, with no {@code data.geoLocation} ever created; a
 *      stranger in the same organization is refused with 403 and creates nothing;</li>
 *  <li>in a game world ({@code OlioTestUtil}'s "Olio World") a location links the POI already on the map by name
 *      and creates nothing; an unknown name links nothing;</li>
 *  <li>deleting the book removes the location, its variant, the POI and the store;</li>
 *  <li>a POI deleted out from under a location is a {@code LOCATION_POI_MISSING} finding that
 *      {@link PbHealthUtil#healBook} repairs by re-creating it;</li>
 *  <li>merging a duplicate re-points its scene, folds its name and aliases into the survivor and removes the
 *      duplicate with its POI and store, leaving the survivor's POI alone;</li>
 *  <li>merging re-points the duplicate's {@code olio.pb.locationVariant} to the survivor (§6.C 171 k);</li>
 *  <li>a merge whose folded alias list would exceed the cap is refused with 409 before any write - the duplicate,
 *      its scene binding, its POI and the survivor's aliases are all untouched (§6.C 171 j);</li>
 *  <li>a stranger may not merge: 404 from the book re-read, both locations untouched;</li>
 *  <li>in a game world two painted POIs of the same name leave the location unlinked and create nothing
 *      (§6.C 171 g).</li>
 * </ol>
 * Every case uses its own throwaway book (deleted in {@code finally}) in org {@code /Development/World Building}
 * as {@code testUser1}; the olio principal is used only to inspect or damage fixtures, never to assert through.
 */
public class TestPbLocationUtil extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String STRANGER_USER = "p32Stranger";

	private static final String[] POI_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_TYPE,
		FieldNames.FIELD_LOCATION, FieldNames.FIELD_EAST, FieldNames.FIELD_NORTH, FieldNames.FIELD_STORE,
		"concealed", FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID
	};

	@Before
	public void setupEach() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	/// OlioTestUtil.getContext flips bulk approval on for the game-world case; put it back.
	@After
	public void teardownEach() {
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private String dataPath() {
		String p = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", p);
		return p;
	}

	private BaseRecord user() {
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull("Failed to resolve " + ORG_A, org);
		BaseRecord u = ioContext.getFactory().getCreateUser(org.getAdminUser(), TEST_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + TEST_USER, u);
		return u;
	}

	private BaseRecord stranger(long orgId) {
		OrganizationContext org = getTestOrganization(ORG_A);
		BaseRecord s = ioContext.getFactory().getCreateUser(org.getAdminUser(), STRANGER_USER, org.getOrganizationId());
		assertNotNull("Failed to resolve " + STRANGER_USER, s);
		assertEquals("stranger must be in the same organization", orgId, orgOf(s));
		return s;
	}

	private static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private static long fkId(BaseRecord rec, String field) {
		Object o = rec.get(field);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}

	private BaseRecord olioUser(long orgId) {
		BaseRecord o = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal", o);
		return o;
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p32" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P3-2 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, bk.get(FieldNames.FIELD_OBJECT_ID));
			logger.info("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup " + bk.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
		}
	}

	private BookContext bookContext(BaseRecord u, BaseRecord bk) {
		BookContext bc = PbBookUtil.openBookContext(u, bk);
		assertNotNull("The book must carry a world", bc);
		return bc;
	}

	private static long groupIdOf(BookContext bctx, String worldField) {
		BaseRecord g = bctx.getGroup(worldField);
		assertNotNull("world group " + worldField, g);
		return idOf(g);
	}

	private int countInGroup(String model, long groupId, long orgId) {
		Query q = QueryUtil.getGroupQuery(model, null, groupId, orgId);
		q.setCache(false);
		return ioContext.getSearch().count(q);
	}

	private int countGeo(long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		return ioContext.getSearch().count(q);
	}

	/// Unauthorized by-id lookup: null means the row is gone, whoever owns it.
	private BaseRecord rawById(String model, long id, long orgId, String[] request) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_ID, id);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(request != null ? request : new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID});
		q.setCache(false);
		BaseRecord r = ioContext.getSearch().findRecord(q);
		if(r != null && r.getSchema() == null) {
			r.setSchema(model);
		}
		return r;
	}

	private BaseRecord readLocation(BaseRecord actor, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.locationRequest());
		q.setCache(false);
		return ioContext.getAccessPoint().find(actor, q);
	}

	private BaseRecord readPoi(long id, long orgId) {
		return rawById(OlioModelNames.MODEL_POI, id, orgId, POI_REQUEST);
	}

	/// Unauthorized, uncached, record-valued condition (a foreign model field takes the record, not its id).
	private BaseRecord[] variantsPointingAt(BaseRecord loc, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_LOCATION_VARIANT, FieldNames.FIELD_LOCATION, loc);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(PbBookUtil.locationVariantRequest());
		q.setCache(false);
		BaseRecord[] recs = ioContext.getSearch().findRecords(q);
		return (recs != null ? recs : new BaseRecord[0]);
	}

	/// A snapshot of the alias list as read now, so a later re-read can be compared against it.
	private static List<String> aliasesOf(BaseRecord loc) {
		List<String> a = loc.get(OlioFieldNames.FIELD_PB_ALIASES);
		return (a != null ? new ArrayList<>(a) : new ArrayList<>());
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
			p.set(FieldNames.FIELD_NAME, read.get(FieldNames.FIELD_NAME));
		}
		catch(Exception e) {
			fail("patch " + model + ": " + e.getMessage());
		}
		return p;
	}

	private void rawDelete(BaseRecord rec, String what) {
		assertNotNull(what + " to delete", rec);
		assertTrue("raw delete " + what + " #" + idOf(rec), ioContext.getRecordUtil().deleteRecord(rec));
	}

	@SuppressWarnings("unchecked")
	private static Set<String> codes(Map<String, Object> report, String key) {
		Set<String> out = new HashSet<>();
		Object l = report.get(key);
		if(l instanceof List) {
			for(Map<String, Object> f : (List<Map<String, Object>>) l) {
				out.add((String) f.get("code"));
			}
		}
		return out;
	}

	// ─────────────────────────────── cases ───────────────────────────────

	@Test
	public void case01_sameSettingThriceIsOneLocationOnePoiOneStoreAndStrangerIsRefused() {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "res");
		try {
			BookContext bctx = bookContext(u, bk);
			long locGid = groupIdOf(bctx, FieldNames.FIELD_LOCATIONS);
			long poiGid = groupIdOf(bctx, "pointsOfInterest");
			long storeGid = groupIdOf(bctx, "stores");
			assertTrue("the book world is a book world", PbLocationUtil.isBookWorld(olioUser(orgId), bctx.getWorld()));

			int locBefore = countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId);
			int poiBefore = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int storeBefore = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);
			int geoBefore = countGeo(orgId);

			String name = "The Gilded Tankard " + shortId();
			BaseRecord first = PbLocationUtil.getCreateLocation(u, bk, name, "A low-beamed tavern by the quay", PbLocationKindEnumType.INTERIOR,
				Arrays.asList("the tavern", "the Tankard", "the tavern", "   ", null, "null"));
			assertNotNull("first resolve", first);
			String oid = first.get(FieldNames.FIELD_OBJECT_ID);
			assertEquals(name, first.get(FieldNames.FIELD_NAME));
			assertEquals("INTERIOR", String.valueOf((Object) first.get(OlioFieldNames.FIELD_PB_KIND)).toUpperCase());
			List<String> aliases = first.get(OlioFieldNames.FIELD_PB_ALIASES);
			assertNotNull("aliases are read back", aliases);
			assertEquals("aliases de-duplicated case-insensitively and junk dropped: " + aliases, 2, aliases.size());
			assertTrue(aliases.toString(), aliases.contains("the tavern") && aliases.contains("the Tankard"));
			assertEquals("owned by the olio principal", idOf(olioUser(orgId)), ((Number) first.get(FieldNames.FIELD_OWNER_ID)).longValue());
			assertEquals("in the world's Locations group", locGid, ((Number) first.get(FieldNames.FIELD_GROUP_ID)).longValue());
			assertEquals("bound to the book world", idOf(bctx.getWorld()), fkId(first, OlioFieldNames.FIELD_PB_WORLD));

			BaseRecord second = PbLocationUtil.getCreateLocation(u, bk, "the tavern", "some other text", PbLocationKindEnumType.EXTERIOR, null);
			BaseRecord third = PbLocationUtil.getCreateLocation(u, bk, "  THE TANKARD ", null, null, Arrays.asList("ignored"));
			assertEquals("alias resolves to the same location", oid, second.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals("alias resolves to the same location (case/whitespace)", oid, third.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals("a hit is returned unchanged (kind)", "INTERIOR", String.valueOf((Object) third.get(OlioFieldNames.FIELD_PB_KIND)).toUpperCase());
			assertEquals("a hit is returned unchanged (description)", "A low-beamed tavern by the quay", third.get(FieldNames.FIELD_DESCRIPTION));

			assertEquals("+1 location", locBefore + 1, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("+1 point of interest", poiBefore + 1, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("+1 store", storeBefore + 1, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			assertEquals("no data.geoLocation is ever created for a book location", geoBefore, countGeo(orgId));

			long poiId = fkId(first, OlioFieldNames.FIELD_PB_POI);
			assertTrue("location.poi is set", poiId > 0L);
			BaseRecord poi = readPoi(poiId, orgId);
			assertNotNull("POI row", poi);
			assertEquals("POI name = location name", name, poi.get(FieldNames.FIELD_NAME));
			assertEquals("POI type from kind INTERIOR + 'tavern'", "COMMERCIAL", String.valueOf((Object) poi.get(FieldNames.FIELD_TYPE)).toUpperCase());
			assertNull("map-free: no cell", poi.get(FieldNames.FIELD_LOCATION));
			assertEquals("map-free: east -1", -1, ((Number) poi.get(FieldNames.FIELD_EAST)).intValue());
			assertEquals("map-free: north -1", -1, ((Number) poi.get(FieldNames.FIELD_NORTH)).intValue());
			assertEquals("not concealed", Boolean.FALSE, poi.get("concealed"));
			assertEquals("POI in the world's Points of Interest group", poiGid, ((Number) poi.get(FieldNames.FIELD_GROUP_ID)).longValue());
			long storeId = fkId(poi, FieldNames.FIELD_STORE);
			assertTrue("POI has a store", storeId > 0L);
			BaseRecord store = rawById(OlioModelNames.MODEL_STORE, storeId, orgId, new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_GROUP_ID});
			assertNotNull("store row", store);
			assertEquals("store in the world's Stores group", storeGid, ((Number) store.get(FieldNames.FIELD_GROUP_ID)).longValue());

			// A stranger in the same organization may read nothing and create nothing. The util re-reads the book
			// by objectId as the caller before the update gate (the facade's requireBook -> requireBookUpdate order),
			// so a stranger who cannot read the book is refused as 404, never as a 403 that confirms it exists.
			BaseRecord s = stranger(orgId);
			try {
				PbLocationUtil.getCreateLocation(s, bk, "Stranger's Folly " + shortId(), null, null, null);
				fail("a stranger must not create a location in someone else's book");
			}
			catch(PictureBookException e) {
				assertEquals("stranger refused with 404: " + e.getMessage(), 404, e.getStatus());
			}
			assertEquals("stranger created no location", locBefore + 1, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("stranger created no POI", poiBefore + 1, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("stranger created no store", storeBefore + 1, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	@Test
	public void case02_gameWorldLinksThePaintedPoiByNameAndCreatesNothing() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		OlioContext ctx = OlioTestUtil.getContext(org, dataPath());
		assertNotNull("Olio game context", ctx);
		BaseRecord world = ctx.getWorld();
		BaseRecord olio = ctx.getOlioUser();
		assertNotNull("game world", world);
		long orgId = orgOf(olio);
		Object poiGidObj = world.get(OlioFieldNames.FIELD_POINTS_OF_INTEREST_ID);
		assertTrue("game world exposes its Points of Interest group", poiGidObj instanceof Number && ((Number) poiGidObj).longValue() > 0L);
		long poiGid = ((Number) poiGidObj).longValue();
		long storeGid = ((Number) world.get(OlioFieldNames.FIELD_STORES_ID)).longValue();
		String locPath = world.get("locations.path");
		assertNotNull("game world exposes its Locations group path", locPath);
		assertFalse("the Olio test world is not a book world", PbLocationUtil.isBookWorld(olio, world));

		String tag = shortId();
		String poiName = "P32 Quarry " + tag;
		BaseRecord poi = null;
		BaseRecord store = null;
		BaseRecord loc = null;
		BaseRecord loc2 = null;
		try {
			// Plant a placed POI the way the painter does (cell when the world has one), then name it explicitly:
			// olio.pointOfInterest declares name itself, so the factory's applyNameGroupOwnership leaves it unset.
			BaseRecord cell = firstCell(world, orgId);
			logger.info("case02: planting POI '" + poiName + "' " + (cell != null ? "in cell #" + idOf(cell) : "without a cell (world has none)"));
			poi = PointOfInterestUtil.newPointOfInterest(ctx, PointOfInterestEnumType.RESOURCE, cell, poiName, 3, 4);
			assertNotNull("planted POI", poi);
			poi.set(FieldNames.FIELD_NAME, poiName);
			store = poi.get(FieldNames.FIELD_STORE);
			assertNotNull("planted store", store);
			assertTrue("persist store", ioContext.getRecordUtil().createRecord(store));
			assertTrue("persist POI", ioContext.getRecordUtil().createRecord(poi));
			long plantedPoiId = idOf(poi);
			assertEquals("rule 3: findByName sees exactly the planted POI", 1, PointOfInterestUtil.findByName(ctx, poiName).size());

			int poiBefore = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int storeBefore = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);
			int geoBefore = countGeo(orgId);

			loc = plantLocation(olio, world, poiName, locPath, orgId);
			assertTrue("rule 2: link by name", PbLocationUtil.linkPoi(olio, world, loc));
			BaseRecord read = readLocation(olio, loc.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull("planted location readable", read);
			assertEquals("linked to the painted POI, not a new one", plantedPoiId, fkId(read, OlioFieldNames.FIELD_PB_POI));
			BaseRecord poiAfter = readPoi(plantedPoiId, orgId);
			assertNotNull(poiAfter);
			assertEquals("the painted POI keeps its placement (east)", 3, ((Number) poiAfter.get(FieldNames.FIELD_EAST)).intValue());
			assertEquals("the painted POI keeps its placement (north)", 4, ((Number) poiAfter.get(FieldNames.FIELD_NORTH)).intValue());

			loc2 = plantLocation(olio, world, "P32 Nowhere " + tag, locPath, orgId);
			assertFalse("no POI of that name: nothing linked", PbLocationUtil.linkPoi(olio, world, loc2));
			BaseRecord read2 = readLocation(olio, loc2.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertEquals("unlinked location keeps poi null", 0L, fkId(read2, OlioFieldNames.FIELD_PB_POI));

			assertEquals("game world: no POI created", poiBefore, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("game world: no store created", storeBefore, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			assertEquals("game world: no geoLocation created", geoBefore, countGeo(orgId));
		}
		finally {
			if(loc2 != null && loc2.get(FieldNames.FIELD_ID) != null) {
				ioContext.getRecordUtil().deleteRecord(loc2);
			}
			if(loc != null && loc.get(FieldNames.FIELD_ID) != null) {
				ioContext.getRecordUtil().deleteRecord(loc);
			}
			if(poi != null && poi.get(FieldNames.FIELD_ID) != null) {
				ioContext.getRecordUtil().deleteRecord(poi);
			}
			if(store != null && store.get(FieldNames.FIELD_ID) != null) {
				ioContext.getRecordUtil().deleteRecord(store);
			}
		}
	}

	private BaseRecord firstCell(BaseRecord world, long orgId) {
		Object gid = world.get(OlioFieldNames.FIELD_LOCATIONS_ID);
		if(!(gid instanceof Number)) {
			return null;
		}
		Query q = QueryUtil.createQuery(ModelNames.MODEL_GEO_LOCATION, FieldNames.FIELD_GROUP_ID, ((Number) gid).longValue());
		q.field(FieldNames.FIELD_GEOTYPE, "cell");
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME});
		q.setCache(false);
		BaseRecord[] cells = ioContext.getSearch().findRecords(q);
		if(cells == null || cells.length == 0) {
			return null;
		}
		if(cells[0].getSchema() == null) {
			cells[0].setSchema(ModelNames.MODEL_GEO_LOCATION);
		}
		return cells[0];
	}

	/// A location in a world's Locations group as the olio principal - the shape PbLocationUtil writes, without the book gate.
	private BaseRecord plantLocation(BaseRecord olio, BaseRecord world, String name, String groupPath, long orgId) throws Exception {
		BaseRecord loc = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION);
		ioContext.getRecordUtil().applyNameGroupOwnership(olio, loc, name, groupPath, orgId);
		loc.set(FieldNames.FIELD_NAME, name);
		loc.set(OlioFieldNames.FIELD_PB_WORLD, world);
		assertTrue("persist planted location " + name, ioContext.getRecordUtil().createRecord(loc));
		return loc;
	}

	@Test
	public void case03_deletingTheBookRemovesLocationVariantPoiAndStore() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "del");
		boolean deleted = false;
		try {
			BookContext bctx = bookContext(u, bk);
			String locPath = bctx.getGroupPath(FieldNames.FIELD_LOCATIONS);
			BaseRecord loc = PbLocationUtil.getCreateLocation(u, bk, "Harbour Steps " + shortId(), "Weed-slick stone steps down to the water", PbLocationKindEnumType.EXTERIOR, null);
			long locId = idOf(loc);
			long poiId = fkId(loc, OlioFieldNames.FIELD_PB_POI);
			assertTrue("location has a POI", poiId > 0L);
			BaseRecord poi = readPoi(poiId, orgId);
			assertEquals("EXTERIOR without a landmark/ruin word is a STRUCTURE", "STRUCTURE", String.valueOf((Object) poi.get(FieldNames.FIELD_TYPE)).toUpperCase());
			long storeId = fkId(poi, FieldNames.FIELD_STORE);
			assertTrue("POI has a store", storeId > 0L);

			BaseRecord v = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION_VARIANT);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, v, loc.get(FieldNames.FIELD_NAME) + " - dusk", locPath, orgId);
			v.set(FieldNames.FIELD_NAME, loc.get(FieldNames.FIELD_NAME) + " - dusk");
			v.set(FieldNames.FIELD_LOCATION, loc);
			BaseRecord created = ioContext.getAccessPoint().create(u, v);
			assertNotNull("variant created as the user", created);
			long varId = idOf(created);

			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, bk.get(FieldNames.FIELD_OBJECT_ID));
			assertTrue("deleteBookComplete: " + res.reason, res.deleted);
			deleted = true;

			assertNull("location gone", rawById(OlioModelNames.MODEL_PB_LOCATION, locId, orgId, null));
			assertNull("variant gone", rawById(OlioModelNames.MODEL_PB_LOCATION_VARIANT, varId, orgId, null));
			assertNull("POI gone", rawById(OlioModelNames.MODEL_POI, poiId, orgId, null));
			assertNull("store gone", rawById(OlioModelNames.MODEL_STORE, storeId, orgId, null));
		}
		finally {
			if(!deleted) {
				deleteBook(u, bk);
			}
		}
	}

	@Test
	public void case04_danglingPoiIsReportedAndHealedByRecreatingIt() {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "heal");
		try {
			BookContext bctx = bookContext(u, bk);
			long poiGid = groupIdOf(bctx, "pointsOfInterest");
			long storeGid = groupIdOf(bctx, "stores");
			String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);

			BaseRecord loc = PbLocationUtil.getCreateLocation(u, bk, "Old Mill Ruin " + shortId(), "the burnt-out mill above the weir", PbLocationKindEnumType.MIXED, null);
			long poiId = fkId(loc, OlioFieldNames.FIELD_PB_POI);
			assertTrue(poiId > 0L);
			BaseRecord poi = readPoi(poiId, orgId);
			assertEquals("MIXED + 'ruin' is a RUIN", "RUIN", String.valueOf((Object) poi.get(FieldNames.FIELD_TYPE)).toUpperCase());
			long storeId = fkId(poi, FieldNames.FIELD_STORE);

			Map<String, Object> clean = PbHealthUtil.checkBook(u, bookOid);
			assertFalse("a linked location is not a finding: " + codes(clean, "findings"), codes(clean, "findings").contains(PbHealthUtil.LOCATION_POI_MISSING));

			// Damage: the POI and its store disappear out from under the location (rule 5).
			rawDelete(poi, "POI");
			rawDelete(rawById(OlioModelNames.MODEL_STORE, storeId, orgId, null), "store");
			int poiAfterDamage = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int storeAfterDamage = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);
			int geoBefore = countGeo(orgId);
			BaseRecord damaged = readLocation(u, loc.get(FieldNames.FIELD_OBJECT_ID), orgId);
			logger.info("case04: after deleting POI #" + poiId + " the location reads poi=" + damaged.get(OlioFieldNames.FIELD_PB_POI));

			Map<String, Object> report = PbHealthUtil.checkBook(u, bookOid);
			assertTrue("LOCATION_POI_MISSING reported: " + codes(report, "findings"), codes(report, "findings").contains(PbHealthUtil.LOCATION_POI_MISSING));

			Map<String, Object> healed = PbHealthUtil.healBook(u, dataPath(), bookOid, Set.of(PbHealthUtil.LOCATION_POI_MISSING));
			assertTrue("healed: " + codes(healed, "healed") + " skipped: " + codes(healed, "skipped"), codes(healed, "healed").contains(PbHealthUtil.LOCATION_POI_MISSING));
			assertFalse("clean after heal: " + codes(healed, "findings"), codes(healed, "findings").contains(PbHealthUtil.LOCATION_POI_MISSING));

			BaseRecord repaired = readLocation(u, loc.get(FieldNames.FIELD_OBJECT_ID), orgId);
			long newPoiId = fkId(repaired, OlioFieldNames.FIELD_PB_POI);
			assertTrue("re-linked", newPoiId > 0L);
			assertNotEquals("a new POI, not the dead id", poiId, newPoiId);
			BaseRecord newPoi = readPoi(newPoiId, orgId);
			assertNotNull("new POI row exists", newPoi);
			assertEquals("new POI carries the location name", (String) repaired.get(FieldNames.FIELD_NAME), (String) newPoi.get(FieldNames.FIELD_NAME));
			assertNull("still map-free", newPoi.get(FieldNames.FIELD_LOCATION));
			assertTrue("new POI has a store", fkId(newPoi, FieldNames.FIELD_STORE) > 0L);
			assertEquals("+1 POI from the heal", poiAfterDamage + 1, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("+1 store from the heal", storeAfterDamage + 1, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			assertEquals("no geoLocation from the heal", geoBefore, countGeo(orgId));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	@Test
	public void case05_mergeRepointsScenesFoldsAliasesAndRemovesTheDuplicateWithItsPoi() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "mrg");
		try {
			BookContext bctx = bookContext(u, bk);
			long locGid = groupIdOf(bctx, FieldNames.FIELD_LOCATIONS);
			long poiGid = groupIdOf(bctx, "pointsOfInterest");
			long storeGid = groupIdOf(bctx, "stores");
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			String tag = shortId();

			BaseRecord survivor = PbLocationUtil.getCreateLocation(u, bk, "The Lantern Inn " + tag, "an inn", PbLocationKindEnumType.INTERIOR, Arrays.asList("the inn"));
			BaseRecord dup = PbLocationUtil.getCreateLocation(u, bk, "Lantern taproom " + tag, "the taproom of the inn", PbLocationKindEnumType.INTERIOR, Arrays.asList("the taproom", "The Inn"));
			assertNotEquals("two distinct locations", survivor.get(FieldNames.FIELD_OBJECT_ID), dup.get(FieldNames.FIELD_OBJECT_ID));
			long survivorPoi = fkId(survivor, OlioFieldNames.FIELD_PB_POI);
			long dupPoi = fkId(dup, OlioFieldNames.FIELD_PB_POI);
			assertTrue(survivorPoi > 0L && dupPoi > 0L && survivorPoi != dupPoi);
			long dupStore = fkId(readPoi(dupPoi, orgId), FieldNames.FIELD_STORE);
			assertTrue(dupStore > 0L);

			BaseRecord scene = PbBookUtil.createScene(u, bk, 3, "p32 merge scene", PbBookUtil.bookGroupPath(slug));
			assertNotNull("scene", scene);
			BaseRecord sp = patch(OlioModelNames.MODEL_PB_SCENE, scene, FieldNames.FIELD_LOCATION);
			sp.set(FieldNames.FIELD_LOCATION, dup);
			assertNotNull("scene bound to the duplicate", ioContext.getAccessPoint().update(u, sp));
			assertEquals("scene reads the duplicate", idOf(dup), fkId(PbBookUtil.readScene(u, scene.get(FieldNames.FIELD_OBJECT_ID), orgId), FieldNames.FIELD_LOCATION));

			int locBefore = countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId);
			int poiBefore = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int storeBefore = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);

			assertTrue("merge", PbLocationUtil.mergeLocations(u, bk, dup, survivor));

			assertEquals("scene re-pointed to the survivor", idOf(survivor), fkId(PbBookUtil.readScene(u, scene.get(FieldNames.FIELD_OBJECT_ID), orgId), FieldNames.FIELD_LOCATION));
			BaseRecord sur = readLocation(u, survivor.get(FieldNames.FIELD_OBJECT_ID), orgId);
			List<String> aliases = sur.get(OlioFieldNames.FIELD_PB_ALIASES);
			assertNotNull(aliases);
			assertTrue("survivor keeps its own alias: " + aliases, aliases.contains("the inn"));
			assertTrue("duplicate's name becomes an alias: " + aliases, aliases.contains("Lantern taproom " + tag));
			assertTrue("duplicate's alias folded in: " + aliases, aliases.contains("the taproom"));
			assertEquals("'The Inn' is a case-duplicate of 'the inn' and is not added twice: " + aliases, 3, aliases.size());
			assertEquals("survivor's POI untouched", survivorPoi, fkId(sur, OlioFieldNames.FIELD_PB_POI));
			assertNotNull("survivor's POI row still exists", readPoi(survivorPoi, orgId));
			assertNull("duplicate deleted", readLocation(u, dup.get(FieldNames.FIELD_OBJECT_ID), orgId));
			assertNull("duplicate row gone", rawById(OlioModelNames.MODEL_PB_LOCATION, idOf(dup), orgId, null));
			assertNull("duplicate's POI gone", rawById(OlioModelNames.MODEL_POI, dupPoi, orgId, null));
			assertNull("duplicate's store gone", rawById(OlioModelNames.MODEL_STORE, dupStore, orgId, null));
			assertEquals("-1 location", locBefore - 1, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("-1 POI", poiBefore - 1, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("-1 store", storeBefore - 1, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));

			assertEquals("resolving the folded name now hits the survivor", (String) survivor.get(FieldNames.FIELD_OBJECT_ID),
				(String) PbLocationUtil.getCreateLocation(u, bk, "the taproom", null, null, null).get(FieldNames.FIELD_OBJECT_ID));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// §6.C 171 (k): mergeLocations step 4 re-points every olio.pb.locationVariant of the duplicate to the survivor.
	/// The variant is planted as the olio principal in the world's Locations group with the P3-2 name convention
	/// ("<location name> — <label>"), the way the util itself would write one.
	@Test
	public void case06_mergeRepointsTheDuplicatesVariantToTheSurvivor() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "var");
		long varId = 0L;
		try {
			BookContext bctx = bookContext(u, bk);
			String locPath = bctx.getGroupPath(FieldNames.FIELD_LOCATIONS);
			assertNotNull("the book world exposes its Locations group path", locPath);
			String tag = shortId();

			BaseRecord a = PbLocationUtil.getCreateLocation(u, bk, "Mill Pond " + tag, "the pond below the mill", PbLocationKindEnumType.EXTERIOR, null);
			BaseRecord b = PbLocationUtil.getCreateLocation(u, bk, "the millpond " + tag, "same pond, other spelling", PbLocationKindEnumType.EXTERIOR, null);
			assertNotEquals("two distinct locations", a.get(FieldNames.FIELD_OBJECT_ID), b.get(FieldNames.FIELD_OBJECT_ID));
			long aId = idOf(a);
			long bId = idOf(b);

			String varName = b.get(FieldNames.FIELD_NAME) + " — night";
			BaseRecord v = RecordFactory.newInstance(OlioModelNames.MODEL_PB_LOCATION_VARIANT);
			ioContext.getRecordUtil().applyNameGroupOwnership(olioUser(orgId), v, varName, locPath, orgId);
			v.set(FieldNames.FIELD_NAME, varName);
			v.set(FieldNames.FIELD_LOCATION, b);
			v.set("timeOfDay", "night");
			assertTrue("persist variant as the olio principal", ioContext.getRecordUtil().createRecord(v));
			varId = idOf(v);
			assertTrue("variant id", varId > 0L);

			BaseRecord before = rawById(OlioModelNames.MODEL_PB_LOCATION_VARIANT, varId, orgId, PbBookUtil.locationVariantRequest());
			assertNotNull("variant row", before);
			assertEquals("variant points at the duplicate before the merge", bId, fkId(before, FieldNames.FIELD_LOCATION));
			assertEquals("the duplicate has exactly one variant", 1, variantsPointingAt(b, orgId).length);
			assertEquals("the survivor has no variant yet", 0, variantsPointingAt(a, orgId).length);

			assertTrue("merge", PbLocationUtil.mergeLocations(u, bk, b, a));

			BaseRecord after = rawById(OlioModelNames.MODEL_PB_LOCATION_VARIANT, varId, orgId, PbBookUtil.locationVariantRequest());
			assertNotNull("the variant survives the merge", after);
			assertEquals("variant re-pointed to the survivor", aId, fkId(after, FieldNames.FIELD_LOCATION));
			assertEquals("variant keeps its name", varName, after.get(FieldNames.FIELD_NAME));
			assertEquals("variant keeps its delta", "night", after.get("timeOfDay"));
			BaseRecord[] onSurvivor = variantsPointingAt(a, orgId);
			assertEquals("the survivor now carries the variant", 1, onSurvivor.length);
			assertEquals("...and it is the same row", varId, idOf(onSurvivor[0]));
			assertNull("duplicate deleted", readLocation(u, b.get(FieldNames.FIELD_OBJECT_ID), orgId));
			assertNull("duplicate row gone", rawById(OlioModelNames.MODEL_PB_LOCATION, bId, orgId, null));
			BaseRecord sur = readLocation(u, a.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull("survivor readable", sur);
			assertTrue("duplicate's name folded into the survivor: " + aliasesOf(sur), aliasesOf(sur).contains("the millpond " + tag));
		}
		finally {
			deleteBook(u, bk);
			if(varId > 0L) {
				BaseRecord left = rawById(OlioModelNames.MODEL_PB_LOCATION_VARIANT, varId, orgId, null);
				if(left != null) {
					logger.warn("case06: variant #" + varId + " survived the book delete; removing it directly");
					ioContext.getRecordUtil().deleteRecord(left);
				}
			}
		}
	}

	/// §6.C 171 (j): the folded alias list is checked before any write. A survivor already at the 32-alias cap plus a
	/// duplicate that would bring one name and one alias folds to 34, and the merge is refused with 409 leaving the
	/// duplicate, its scene binding, its POI/store and the survivor's alias list exactly as they were.
	@Test
	public void case07_mergeAliasOverflowIs409BeforeAnyWrite() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "ovf");
		try {
			BookContext bctx = bookContext(u, bk);
			long locGid = groupIdOf(bctx, FieldNames.FIELD_LOCATIONS);
			long poiGid = groupIdOf(bctx, "pointsOfInterest");
			long storeGid = groupIdOf(bctx, "stores");
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			String tag = shortId();

			List<String> full = new ArrayList<>();
			for(int i = 0; i < 32; i++) {
				full.add("alias-" + String.format("%02d", i) + "-" + tag);
			}
			BaseRecord survivor = PbLocationUtil.getCreateLocation(u, bk, "Saturated Keep " + tag, "a keep with every name already taken", PbLocationKindEnumType.MIXED, full);
			List<String> surBefore = aliasesOf(survivor);
			assertEquals("the survivor sits exactly at the alias cap: " + surBefore.size(), 32, surBefore.size());

			BaseRecord dup = PbLocationUtil.getCreateLocation(u, bk, "Overflow Keep " + tag, "the same keep", PbLocationKindEnumType.MIXED, Arrays.asList("dup-alias-" + tag));
			assertNotEquals("two distinct locations", survivor.get(FieldNames.FIELD_OBJECT_ID), dup.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals("duplicate carries its one alias", 1, aliasesOf(dup).size());
			long dupId = idOf(dup);
			long dupPoi = fkId(dup, OlioFieldNames.FIELD_PB_POI);
			assertTrue("duplicate has a POI", dupPoi > 0L);
			long dupStore = fkId(readPoi(dupPoi, orgId), FieldNames.FIELD_STORE);
			assertTrue("duplicate's POI has a store", dupStore > 0L);
			long survivorPoi = fkId(survivor, OlioFieldNames.FIELD_PB_POI);

			BaseRecord scene = PbBookUtil.createScene(u, bk, 2, "p32 overflow scene", PbBookUtil.bookGroupPath(slug));
			assertNotNull("scene", scene);
			BaseRecord sp = patch(OlioModelNames.MODEL_PB_SCENE, scene, FieldNames.FIELD_LOCATION);
			sp.set(FieldNames.FIELD_LOCATION, dup);
			assertNotNull("scene bound to the duplicate", ioContext.getAccessPoint().update(u, sp));
			String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
			assertEquals("scene reads the duplicate", dupId, fkId(PbBookUtil.readScene(u, sceneOid, orgId), FieldNames.FIELD_LOCATION));

			int locBefore = countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId);
			int poiBefore = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int storeBefore = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);

			try {
				PbLocationUtil.mergeLocations(u, bk, dup, survivor);
				fail("a merge that would overflow the survivor's alias list must be refused");
			}
			catch(PictureBookException e) {
				assertEquals("refused with 409: " + e.getMessage(), 409, e.getStatus());
				assertTrue("the message carries the folded count (32 + name + 1 alias): " + e.getMessage(), e.getMessage().contains("34 aliases"));
			}

			// Nothing was written: the check runs before step 1.
			assertEquals("scene still bound to the duplicate", dupId, fkId(PbBookUtil.readScene(u, sceneOid, orgId), FieldNames.FIELD_LOCATION));
			BaseRecord dupAfter = readLocation(u, dup.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull("duplicate still readable", dupAfter);
			assertNotNull("duplicate row still there", rawById(OlioModelNames.MODEL_PB_LOCATION, dupId, orgId, null));
			assertEquals("duplicate keeps its alias", Arrays.asList("dup-alias-" + tag), aliasesOf(dupAfter));
			assertEquals("duplicate keeps its POI link", dupPoi, fkId(dupAfter, OlioFieldNames.FIELD_PB_POI));
			assertNotNull("duplicate's POI row still there", readPoi(dupPoi, orgId));
			assertNotNull("duplicate's store row still there", rawById(OlioModelNames.MODEL_STORE, dupStore, orgId, null));
			BaseRecord surAfter = readLocation(u, survivor.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull("survivor still readable", surAfter);
			assertEquals("survivor's aliases unchanged", surBefore, aliasesOf(surAfter));
			assertEquals("survivor's POI unchanged", survivorPoi, fkId(surAfter, OlioFieldNames.FIELD_PB_POI));
			assertEquals("no location removed", locBefore, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("no POI removed", poiBefore, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("no store removed", storeBefore, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// A stranger in the same organization cannot read the book, so requireBookUpdate's re-read misses and the merge
	/// is refused as 404 (never a 403 that confirms the book exists) with nothing written.
	@Test
	public void case08_strangerMayNotMergeAndIsRefused404() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = newBook(u, "smrg");
		try {
			BookContext bctx = bookContext(u, bk);
			long locGid = groupIdOf(bctx, FieldNames.FIELD_LOCATIONS);
			long poiGid = groupIdOf(bctx, "pointsOfInterest");
			long storeGid = groupIdOf(bctx, "stores");
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			String tag = shortId();

			BaseRecord a = PbLocationUtil.getCreateLocation(u, bk, "Chapel Yard " + tag, "the yard before the chapel", PbLocationKindEnumType.EXTERIOR, Arrays.asList("the yard"));
			BaseRecord b = PbLocationUtil.getCreateLocation(u, bk, "the chapel forecourt " + tag, "the same yard", PbLocationKindEnumType.EXTERIOR, Arrays.asList("the forecourt"));
			assertNotEquals("two distinct locations", a.get(FieldNames.FIELD_OBJECT_ID), b.get(FieldNames.FIELD_OBJECT_ID));
			long aId = idOf(a);
			long bId = idOf(b);
			long aPoi = fkId(a, OlioFieldNames.FIELD_PB_POI);
			long bPoi = fkId(b, OlioFieldNames.FIELD_PB_POI);
			assertTrue(aPoi > 0L && bPoi > 0L && aPoi != bPoi);
			List<String> aAliases = aliasesOf(a);
			List<String> bAliases = aliasesOf(b);

			BaseRecord scene = PbBookUtil.createScene(u, bk, 4, "p32 stranger merge scene", PbBookUtil.bookGroupPath(slug));
			assertNotNull("scene", scene);
			BaseRecord sp = patch(OlioModelNames.MODEL_PB_SCENE, scene, FieldNames.FIELD_LOCATION);
			sp.set(FieldNames.FIELD_LOCATION, b);
			assertNotNull("scene bound to B", ioContext.getAccessPoint().update(u, sp));
			String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
			assertEquals("scene reads B", bId, fkId(PbBookUtil.readScene(u, sceneOid, orgId), FieldNames.FIELD_LOCATION));

			int locBefore = countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId);
			int poiBefore = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int storeBefore = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);

			BaseRecord s = stranger(orgId);
			try {
				PbLocationUtil.mergeLocations(s, bk, b, a);
				fail("a stranger must not merge locations in someone else's book");
			}
			catch(PictureBookException e) {
				assertEquals("stranger refused with 404: " + e.getMessage(), 404, e.getStatus());
			}

			assertEquals("scene still bound to B", bId, fkId(PbBookUtil.readScene(u, sceneOid, orgId), FieldNames.FIELD_LOCATION));
			BaseRecord aAfter = readLocation(u, a.get(FieldNames.FIELD_OBJECT_ID), orgId);
			BaseRecord bAfter = readLocation(u, b.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull("A still readable", aAfter);
			assertNotNull("B still readable", bAfter);
			assertNotNull("B row still there", rawById(OlioModelNames.MODEL_PB_LOCATION, bId, orgId, null));
			assertEquals("A's aliases unchanged", aAliases, aliasesOf(aAfter));
			assertEquals("B's aliases unchanged", bAliases, aliasesOf(bAfter));
			assertEquals("A's POI unchanged", aPoi, fkId(aAfter, OlioFieldNames.FIELD_PB_POI));
			assertEquals("B's POI unchanged", bPoi, fkId(bAfter, OlioFieldNames.FIELD_PB_POI));
			assertNotNull("B's POI row still there", readPoi(bPoi, orgId));
			assertEquals("no location removed", locBefore, countInGroup(OlioModelNames.MODEL_PB_LOCATION, locGid, orgId));
			assertEquals("no POI removed", poiBefore, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("no store removed", storeBefore, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			assertEquals("A re-reads as the same row", aId, idOf(aAfter));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// §6.C 171 (g): linkPoi's game-world branch links only on exactly one name hit. Two painted POIs of the same
	/// name leave the location unlinked, and nothing (POI, store, geoLocation) is created.
	@Test
	public void case09_gameWorldTwoPoisOfTheSameNameLeaveTheLocationUnlinked() throws Exception {
		OrganizationContext org = getTestOrganization(ORG_A);
		OlioContext ctx = OlioTestUtil.getContext(org, dataPath());
		assertNotNull("Olio game context", ctx);
		BaseRecord world = ctx.getWorld();
		BaseRecord olio = ctx.getOlioUser();
		assertNotNull("game world", world);
		long orgId = orgOf(olio);
		Object poiGidObj = world.get(OlioFieldNames.FIELD_POINTS_OF_INTEREST_ID);
		assertTrue("game world exposes its Points of Interest group", poiGidObj instanceof Number && ((Number) poiGidObj).longValue() > 0L);
		long poiGid = ((Number) poiGidObj).longValue();
		long storeGid = ((Number) world.get(OlioFieldNames.FIELD_STORES_ID)).longValue();
		String locPath = world.get("locations.path");
		assertNotNull("game world exposes its Locations group path", locPath);
		assertFalse("the Olio test world is not a book world", PbLocationUtil.isBookWorld(olio, world));

		String tag = shortId();
		String poiName = "P32 Twin Well " + tag;
		BaseRecord poi1 = null;
		BaseRecord store1 = null;
		BaseRecord poi2 = null;
		BaseRecord store2 = null;
		BaseRecord loc = null;
		try {
			BaseRecord cell = firstCell(world, orgId);
			logger.info("case09: planting two POIs named '" + poiName + "' " + (cell != null ? "in cell #" + idOf(cell) : "without a cell (world has none)"));
			poi1 = PointOfInterestUtil.newPointOfInterest(ctx, PointOfInterestEnumType.RESOURCE, cell, poiName, 3, 4);
			assertNotNull("first planted POI", poi1);
			poi1.set(FieldNames.FIELD_NAME, poiName);
			store1 = poi1.get(FieldNames.FIELD_STORE);
			assertNotNull("first planted store", store1);
			assertTrue("persist store 1", ioContext.getRecordUtil().createRecord(store1));
			assertTrue("persist POI 1", ioContext.getRecordUtil().createRecord(poi1));

			poi2 = PointOfInterestUtil.newPointOfInterest(ctx, PointOfInterestEnumType.RESOURCE, cell, poiName, 5, 6);
			assertNotNull("second planted POI", poi2);
			poi2.set(FieldNames.FIELD_NAME, poiName);
			store2 = poi2.get(FieldNames.FIELD_STORE);
			assertNotNull("second planted store", store2);
			assertTrue("persist store 2", ioContext.getRecordUtil().createRecord(store2));
			assertTrue("persist POI 2", ioContext.getRecordUtil().createRecord(poi2));
			assertNotEquals("two distinct POI rows", idOf(poi1), idOf(poi2));
			// The setup must exhibit the condition the branch reacts to, through the same lookup linkPoi uses.
			assertEquals("findByName sees both planted POIs", 2, PointOfInterestUtil.findByName(ctx, poiName).size());

			int poiBefore = countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId);
			int storeBefore = countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId);
			int geoBefore = countGeo(orgId);

			loc = plantLocation(olio, world, poiName, locPath, orgId);
			assertFalse("two name hits: nothing linked", PbLocationUtil.linkPoi(olio, world, loc));
			BaseRecord read = readLocation(olio, loc.get(FieldNames.FIELD_OBJECT_ID), orgId);
			assertNotNull("planted location readable", read);
			assertEquals("ambiguous name: location.poi stays null", 0L, fkId(read, OlioFieldNames.FIELD_PB_POI));
			assertNull("ambiguous name: location.poi stays null (raw)", read.get(OlioFieldNames.FIELD_PB_POI));

			assertEquals("game world: no POI created", poiBefore, countInGroup(OlioModelNames.MODEL_POI, poiGid, orgId));
			assertEquals("game world: no store created", storeBefore, countInGroup(OlioModelNames.MODEL_STORE, storeGid, orgId));
			assertEquals("game world: no geoLocation created", geoBefore, countGeo(orgId));
			assertEquals("both painted POIs untouched", 2, PointOfInterestUtil.findByName(ctx, poiName).size());
		}
		finally {
			if(loc != null && loc.get(FieldNames.FIELD_ID) != null) {
				ioContext.getRecordUtil().deleteRecord(loc);
			}
			if(poi2 != null && poi2.get(FieldNames.FIELD_ID) != null) {
				ioContext.getRecordUtil().deleteRecord(poi2);
			}
			if(store2 != null && store2.get(FieldNames.FIELD_ID) != null) {
				ioContext.getRecordUtil().deleteRecord(store2);
			}
			if(poi1 != null && poi1.get(FieldNames.FIELD_ID) != null) {
				ioContext.getRecordUtil().deleteRecord(poi1);
			}
			if(store1 != null && store1.get(FieldNames.FIELD_ID) != null) {
				ioContext.getRecordUtil().deleteRecord(store1);
			}
		}
	}
}
