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
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryPlan;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.BookContext;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbDeleteUtil;
import org.cote.accountmanager.olio.picturebook.PbWatchedFields;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.PbActorRoleEnumType;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbElementTypeEnumType;
import org.cote.accountmanager.schema.type.PbFacingEnumType;
import org.cote.accountmanager.schema.type.PbImportanceEnumType;
import org.cote.accountmanager.schema.type.PbReferencePolicyEnumType;
import org.cote.accountmanager.schema.type.PbShotTypeEnumType;
import org.cote.accountmanager.schema.type.PbViewpointEnumType;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-1: the three §8 models - {@code olio.pb.composition}, {@code olio.pb.sceneActor},
 * {@code olio.pb.sceneElement} - round trip through {@code AccessPoint} against the live {@code am7db}
 * as a non-admin user; every enum reads back as its UPPERCASE constant and a NULL column reads
 * {@code UNKNOWN}; the FKs (book, scene, location, locationVariant, focalActor, composition, character,
 * apparel, item) are {@code bigint} columns; each model fits under the 49-field nested cap and a projected
 * read follows {@code scene.*} / {@code focalActor.*} / {@code composition.*} request paths (a bare
 * {@code planMost(true)} is not a supported read shape for these models - see case 6); the
 * {@code watched/v4} set notices the edits it declares; and
 * {@link PbDeleteUtil#deleteBookComplete} removes the three models with the book.
 * <p>
 * Same fixture as {@link TestPbLocationSchema}: org {@code /Development/World Building}, user
 * {@code testUser1}, book {@code pb2c-graph}. Compositions, actors and elements live in the book's
 * {@code Book} group ({@link PbBookUtil#bookGroupPath}); the item fixture lives in the book world's
 * {@code Items} group, resolved by FK through {@link PbBookUtil#openBookContext}. Everything is created as
 * the test user; a PBAC denial is a finding, not a cue to fall back to the olio principal.
 */
public class TestPbV4Schema extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SLUG = "pb2c-graph";

	private static final String[] COMPOSITION_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_NAME,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
		OlioFieldNames.FIELD_PB_BOOK, OlioFieldNames.FIELD_PB_PURPOSE, OlioFieldNames.FIELD_PB_SCENE,
		FieldNames.FIELD_LOCATION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT, OlioFieldNames.FIELD_PB_SETTING_TEXT,
		OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD, OlioFieldNames.FIELD_PB_SHOT_TYPE,
		OlioFieldNames.FIELD_PB_VIEWPOINT, OlioFieldNames.FIELD_PB_FOCAL_ACTOR, OlioFieldNames.FIELD_PB_CAMERA_TEXT,
		OlioFieldNames.FIELD_PB_PROPS, OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE,
		OlioFieldNames.FIELD_PB_PROMPT_LOCKED, OlioFieldNames.FIELD_PB_SOURCE, OlioFieldNames.FIELD_PB_USER_EDITED,
		OlioFieldNames.FIELD_PB_REVISION
	};

	private static final String[] ACTOR_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_NAME,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
		OlioFieldNames.FIELD_PB_COMPOSITION, OlioFieldNames.FIELD_PB_CHARACTER, OlioFieldNames.FIELD_PB_ROLE,
		OlioFieldNames.FIELD_PB_PRIORITY, OlioFieldNames.FIELD_APPAREL, OlioFieldNames.FIELD_PB_POSE,
		FieldNames.FIELD_EXPRESSION, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_FACING,
		OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y, OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H,
		OlioFieldNames.FIELD_PB_DEPTH, FieldNames.FIELD_DESCRIPTION, FieldNames.FIELD_COUNT,
		OlioFieldNames.FIELD_PB_REFERENCE_POLICY
	};

	private static final String[] ELEMENT_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_URN, FieldNames.FIELD_NAME,
		FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID,
		OlioFieldNames.FIELD_PB_COMPOSITION, OlioFieldNames.FIELD_PB_ELEMENT_TYPE, FieldNames.FIELD_DESCRIPTION,
		OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_Y, OlioFieldNames.FIELD_PB_W, OlioFieldNames.FIELD_PB_H,
		OlioFieldNames.FIELD_PB_DEPTH, OlioFieldNames.FIELD_PB_IMPORTANCE, OlioFieldNames.FIELD_ITEM
	};

	private static final String[] ITEM_REQUEST = new String[] {
		FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
		FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_DESCRIPTION, "materials"
	};

	private ExecutorService bounded = null;

	@Before
	public void schemaSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		bounded = Executors.newSingleThreadExecutor();
	}

	@After
	public void schemaTeardown() {
		if(bounded != null) {
			bounded.shutdownNow();
		}
	}

	// ─────────────────────────────── fixture ───────────────────────────────

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

	private long bookGroupId(BookContext bc) {
		BaseRecord g = bc.getGroup(OlioFieldNames.PB_GROUP_BOOK);
		assertNotNull("The book context must expose the Book group", g);
		return (long) g.get(FieldNames.FIELD_ID);
	}

	/// None of the three models inherits common.name, so applyNameGroupOwnership does not set the name
	/// (TestPbModelSchema documents this) - set it here.
	private BaseRecord newPbRecord(BaseRecord u, String model, String name, String groupPath) {
		BaseRecord rec = null;
		try {
			rec = RecordFactory.newInstance(model);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, rec, name, groupPath, orgOf(u));
			rec.set(FieldNames.FIELD_NAME, name);
		}
		catch(Exception e) {
			fail("Failed to assemble " + model + " '" + name + "': " + e.getMessage());
		}
		return rec;
	}

	private BaseRecord newComposition(BaseRecord u, BaseRecord bk, String name, String groupPath, PbCompositionPurposeEnumType purpose) {
		BaseRecord c = newPbRecord(u, OlioModelNames.MODEL_PB_COMPOSITION, name, groupPath);
		try {
			c.set(OlioFieldNames.FIELD_PB_BOOK, bk);
			c.set(OlioFieldNames.FIELD_PB_PURPOSE, purpose.toString());
		}
		catch(Exception e) {
			fail("Failed to set composition fields: " + e.getMessage());
		}
		return c;
	}

	private BaseRecord newActor(BaseRecord u, BaseRecord composition, String name, String groupPath, PbActorRoleEnumType role) {
		BaseRecord a = newPbRecord(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, name, groupPath);
		try {
			a.set(OlioFieldNames.FIELD_PB_COMPOSITION, composition);
			a.set(OlioFieldNames.FIELD_PB_ROLE, role.toString());
		}
		catch(Exception e) {
			fail("Failed to set actor fields: " + e.getMessage());
		}
		return a;
	}

	private BaseRecord newElement(BaseRecord u, BaseRecord composition, String name, String groupPath, PbElementTypeEnumType type) {
		BaseRecord el = newPbRecord(u, OlioModelNames.MODEL_PB_SCENE_ELEMENT, name, groupPath);
		try {
			el.set(OlioFieldNames.FIELD_PB_COMPOSITION, composition);
			el.set(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, type.toString());
		}
		catch(Exception e) {
			fail("Failed to set element fields: " + e.getMessage());
		}
		return el;
	}

	/// olio.item inherits common.name, so applyNameGroupOwnership sets the name; a bare create in the
	/// world's Items group as the test user. Not ItemUtil.newItem - the template path is not under test.
	private BaseRecord newItem(BaseRecord u, BookContext bc, String name, String description, String material) {
		BaseRecord item = null;
		try {
			String itemsPath = bc.getGroupPath(OlioFieldNames.FIELD_ITEMS);
			assertNotNull("The book world must expose its Items group", itemsPath);
			item = RecordFactory.newInstance(OlioModelNames.MODEL_ITEM);
			ioContext.getRecordUtil().applyNameGroupOwnership(u, item, name, itemsPath, orgOf(u));
			item.set(FieldNames.FIELD_DESCRIPTION, description);
			List<String> materials = item.get("materials");
			materials.add(material);
		}
		catch(Exception e) {
			fail("Failed to assemble olio.item '" + name + "': " + e.getMessage());
		}
		return item;
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

	private BaseRecord readComposition(BaseRecord u, String objectId) {
		BaseRecord r = readFresh(u, OlioModelNames.MODEL_PB_COMPOSITION, objectId, COMPOSITION_REQUEST);
		assertNotNull("olio.pb.composition " + objectId + " must be readable by its owner", r);
		return r;
	}

	private BaseRecord readActor(BaseRecord u, String objectId) {
		BaseRecord r = readFresh(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, objectId, ACTOR_REQUEST);
		assertNotNull("olio.pb.sceneActor " + objectId + " must be readable by its owner", r);
		return r;
	}

	private BaseRecord readElement(BaseRecord u, String objectId) {
		BaseRecord r = readFresh(u, OlioModelNames.MODEL_PB_SCENE_ELEMENT, objectId, ELEMENT_REQUEST);
		assertNotNull("olio.pb.sceneElement " + objectId + " must be readable by its owner", r);
		return r;
	}

	private BaseRecord readItem(BaseRecord u, String objectId) {
		BaseRecord r = readFresh(u, OlioModelNames.MODEL_ITEM, objectId, ITEM_REQUEST);
		assertNotNull("olio.item " + objectId + " must be readable by its owner", r);
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

	/** Run {@code c} on another thread with a hard time bound, so a non-terminating plan cannot hang the suite. */
	private <T> T withinSeconds(int seconds, String what, Callable<T> c) {
		Future<T> f = bounded.submit(c);
		try {
			return f.get(seconds, TimeUnit.SECONDS);
		}
		catch(TimeoutException e) {
			f.cancel(true);
			fail(what + " did not complete within " + seconds + "s - it does not terminate");
		}
		catch(Exception e) {
			f.cancel(true);
			fail(what + " failed: " + e.getCause() + " / " + e.getMessage());
		}
		return null;
	}

	/**
	 * A targeted projection read of one record by objectId, with nested {@code field.subField} request paths
	 * for the foreign records the caller actually needs. This is the read shape for the PB graph
	 * ({@link PbBookUtil#findBookBySlug}, the scene readers): a bare {@code planMost(true)} on a composition
	 * walks book -> world -> sdConfig and round the composition <-> focalActor cycle and is not a supported
	 * shape for these models (same ruling as olio.pb.scene).
	 */
	private BaseRecord projectedRead(BaseRecord u, String model, String objectId, long orgId, String[] request) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(request);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(u, q);
	}

	private static String[] withPaths(String[] base, String... paths) {
		String[] out = Arrays.copyOf(base, base.length + paths.length);
		System.arraycopy(paths, 0, out, base.length, paths.length);
		return out;
	}

	private void assertBigintFk(String model, String field, String expectedBaseModel) {
		FieldSchema fs = RecordFactory.getSchema(model).getFieldSchema(field);
		assertNotNull(model + "." + field + " must exist", fs);
		assertTrue(model + "." + field + " must be foreign", fs.isForeign());
		assertEquals(model + "." + field + " must reference " + expectedBaseModel, expectedBaseModel, fs.getBaseModel());
		assertEquals(model + "." + field + " must emit a bigint column", "bigint",
			ioContext.getDbUtil().getDataType(fs, fs.getFieldType()));
	}

	// ───────── 1: registered, and every §8 FK is a bigint column ─────────

	@Test
	public void case01_registeredAndFksAreBigint() {
		for(String m : new String[] { OlioModelNames.MODEL_PB_COMPOSITION, OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioModelNames.MODEL_PB_SCENE_ELEMENT }) {
			assertTrue(m + " must be registered in ModelNames.MODELS", ModelNames.MODELS.contains(m));
			ModelSchema ms = RecordFactory.getSchema(m);
			assertNotNull(m + " must load", ms);
			assertEquals("PB2 convention: compositions, actors and elements live in the book's Book group",
				OlioFieldNames.PB_GROUP_BOOK, ms.getGroup());
		}
		assertBigintFk(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_BOOK, OlioModelNames.MODEL_PB_BOOK);
		assertBigintFk(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_SCENE, OlioModelNames.MODEL_PB_SCENE);
		assertBigintFk(OlioModelNames.MODEL_PB_COMPOSITION, FieldNames.FIELD_LOCATION, OlioModelNames.MODEL_PB_LOCATION);
		assertBigintFk(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_LOCATION_VARIANT, OlioModelNames.MODEL_PB_LOCATION_VARIANT);
		assertBigintFk(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_FOCAL_ACTOR, OlioModelNames.MODEL_PB_SCENE_ACTOR);
		assertBigintFk(OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioFieldNames.FIELD_PB_COMPOSITION, OlioModelNames.MODEL_PB_COMPOSITION);
		assertBigintFk(OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioFieldNames.FIELD_PB_CHARACTER, OlioModelNames.MODEL_CHAR_PERSON);
		assertBigintFk(OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioFieldNames.FIELD_APPAREL, OlioModelNames.MODEL_APPAREL);
		assertBigintFk(OlioModelNames.MODEL_PB_SCENE_ELEMENT, OlioFieldNames.FIELD_PB_COMPOSITION, OlioModelNames.MODEL_PB_COMPOSITION);
		assertBigintFk(OlioModelNames.MODEL_PB_SCENE_ELEMENT, OlioFieldNames.FIELD_ITEM, OlioModelNames.MODEL_ITEM);
	}

	// ───────── 2: composition CRUD in the Book group; enums UPPERCASE; a SCENE composition carries its scene ─────────

	@Test
	public void case02_compositionRoundTrip() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		long orgId = orgOf(u);
		BookContext bc = bookContext(u, bk);
		String groupPath = PbBookUtil.bookGroupPath(SLUG);
		String tag = shortId();

		BaseRecord coverRead = null;
		BaseRecord sceneCompRead = null;
		BaseRecord scene = null;
		try {
			BaseRecord cover = newComposition(u, bk, "p41-cover-" + tag, groupPath, PbCompositionPurposeEnumType.COVER);
			cover.set(OlioFieldNames.FIELD_PB_SETTING_TEXT, "a lighthouse on black rock at dusk");
			cover.set(OlioFieldNames.FIELD_PB_ACTION, "the keeper climbs the outer stair");
			cover.set(OlioFieldNames.FIELD_PB_MOOD, "foreboding");
			cover.set(OlioFieldNames.FIELD_PB_SHOT_TYPE, PbShotTypeEnumType.WIDE.toString());
			cover.set(OlioFieldNames.FIELD_PB_VIEWPOINT, PbViewpointEnumType.LOW.toString());
			cover.set(OlioFieldNames.FIELD_PB_CAMERA_TEXT, "low angle from the rocks, lamp just lit");
			cover.set(OlioFieldNames.FIELD_PB_PROPS, "brass lantern, coiled rope");
			cover.set(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE, "{\"steps\":30}");
			cover.set(OlioFieldNames.FIELD_PB_SOURCE, PbCompositionSourceEnumType.USER.toString());
			cover.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
			cover.set(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(1));
			String coverOid = create(u, cover).get(FieldNames.FIELD_OBJECT_ID);

			coverRead = readComposition(u, coverOid);
			assertEquals("p41-cover-" + tag, coverRead.get(FieldNames.FIELD_NAME));
			String urn = coverRead.get(FieldNames.FIELD_URN);
			assertTrue("UrnProvider must compose a urn, got '" + urn + "'", urn != null && !urn.isBlank());
			assertEquals("Created in the book's Book group", Long.valueOf(bookGroupId(bc)), coverRead.get(FieldNames.FIELD_GROUP_ID));
			BaseRecord bookRef = coverRead.get(OlioFieldNames.FIELD_PB_BOOK);
			assertNotNull("composition.book must resolve", bookRef);
			assertEquals((String) bk.get(FieldNames.FIELD_OBJECT_ID), bookRef.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(PbCompositionPurposeEnumType.COVER, coverRead.getEnum(OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals(PbShotTypeEnumType.WIDE, coverRead.getEnum(OlioFieldNames.FIELD_PB_SHOT_TYPE));
			assertEquals(PbViewpointEnumType.LOW, coverRead.getEnum(OlioFieldNames.FIELD_PB_VIEWPOINT));
			assertEquals(PbCompositionSourceEnumType.USER, coverRead.getEnum(OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals("a lighthouse on black rock at dusk", coverRead.get(OlioFieldNames.FIELD_PB_SETTING_TEXT));
			assertEquals("the keeper climbs the outer stair", coverRead.get(OlioFieldNames.FIELD_PB_ACTION));
			assertEquals("foreboding", coverRead.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("low angle from the rocks, lamp just lit", coverRead.get(OlioFieldNames.FIELD_PB_CAMERA_TEXT));
			assertEquals("brass lantern, coiled rope", coverRead.get(OlioFieldNames.FIELD_PB_PROPS));
			assertEquals("{\"steps\":30}", coverRead.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
			assertEquals(Boolean.TRUE, coverRead.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals(Boolean.FALSE, coverRead.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(Integer.valueOf(1), coverRead.get(OlioFieldNames.FIELD_PB_REVISION));
			assertNull("A COVER composition has no scene", coverRead.get(OlioFieldNames.FIELD_PB_SCENE));
			assertNull("No location until the composer sets one", coverRead.get(FieldNames.FIELD_LOCATION));
			assertNull(coverRead.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT));
			assertNull("No focal actor yet", coverRead.get(OlioFieldNames.FIELD_PB_FOCAL_ACTOR));
			assertNull(coverRead.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));

			/// PATCH: identity + name + the changed fields only.
			BaseRecord mp = patch(OlioModelNames.MODEL_PB_COMPOSITION, coverRead, OlioFieldNames.FIELD_PB_MOOD,
				OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, OlioFieldNames.FIELD_PB_PROMPT_LOCKED, OlioFieldNames.FIELD_PB_REVISION);
			mp.set(OlioFieldNames.FIELD_PB_MOOD, "serene");
			mp.set(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, "a calm lighthouse at dusk, painterly");
			mp.set(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.TRUE);
			mp.set(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(2));
			update(u, mp);
			BaseRecord coverAfter = readComposition(u, coverOid);
			assertEquals("serene", coverAfter.get(OlioFieldNames.FIELD_PB_MOOD));
			assertEquals("a calm lighthouse at dusk, painterly", coverAfter.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
			assertEquals(Boolean.TRUE, coverAfter.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(Integer.valueOf(2), coverAfter.get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("Unpatched fields are untouched", "the keeper climbs the outer stair", coverAfter.get(OlioFieldNames.FIELD_PB_ACTION));
			assertEquals(PbShotTypeEnumType.WIDE, coverAfter.getEnum(OlioFieldNames.FIELD_PB_SHOT_TYPE));

			/// A SCENE composition carries its scene FK.
			int sceneIndex = 9000 + new Random().nextInt(900);
			scene = PbBookUtil.createScene(u, bk, sceneIndex, "p41 scene " + tag, groupPath);
			assertNotNull(scene);
			BaseRecord sc = newComposition(u, bk, "p41-scene-" + tag, groupPath, PbCompositionPurposeEnumType.SCENE);
			sc.set(OlioFieldNames.FIELD_PB_SCENE, scene);
			sc.set(OlioFieldNames.FIELD_PB_SOURCE, PbCompositionSourceEnumType.LLM.toString());
			String scOid = create(u, sc).get(FieldNames.FIELD_OBJECT_ID);
			sceneCompRead = readComposition(u, scOid);
			assertEquals(PbCompositionPurposeEnumType.SCENE, sceneCompRead.getEnum(OlioFieldNames.FIELD_PB_PURPOSE));
			BaseRecord sceneRef = sceneCompRead.get(OlioFieldNames.FIELD_PB_SCENE);
			assertNotNull("composition.scene must resolve", sceneRef);
			assertEquals((String) scene.get(FieldNames.FIELD_OBJECT_ID), sceneRef.get(FieldNames.FIELD_OBJECT_ID));

			/// LIST by (book, purpose) - the hint the composer's per-book lookups will use.
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_COMPOSITION, OlioFieldNames.FIELD_PB_BOOK, bk);
			q.field(OlioFieldNames.FIELD_PB_PURPOSE, PbCompositionPurposeEnumType.SCENE.toString());
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			q.field(FieldNames.FIELD_GROUP_ID, bookGroupId(bc));
			q.setRequest(COMPOSITION_REQUEST);
			q.setCache(false);
			BaseRecord[] listed = ioContext.getAccessPoint().list(u, q).getResults();
			assertNotNull(listed);
			boolean found = false;
			for(BaseRecord r : listed) {
				if(scOid.equals(r.get(FieldNames.FIELD_OBJECT_ID))) {
					found = true;
				}
				assertNotEquals("A (book, SCENE) list must not return the COVER composition", coverOid, r.get(FieldNames.FIELD_OBJECT_ID));
			}
			assertTrue("The SCENE composition is listed by (book, purpose)", found);

			assertTrue("The owner can delete the scene composition", ioContext.getAccessPoint().delete(u, sceneCompRead));
			sceneCompRead = null;
			assertTrue("The owner can delete the cover composition", ioContext.getAccessPoint().delete(u, coverAfter));
			coverRead = null;
			assertNull("Deleted compositions are gone", readFresh(u, OlioModelNames.MODEL_PB_COMPOSITION, coverOid, COMPOSITION_REQUEST));
			assertNull(readFresh(u, OlioModelNames.MODEL_PB_COMPOSITION, scOid, COMPOSITION_REQUEST));
		}
		finally {
			deleteQuietly(u, sceneCompRead);
			deleteQuietly(u, coverRead);
			deleteQuietly(u, scene);
		}
	}

	// ───────── 3: no field carries a schema default - NULL enums read UNKNOWN, numbers 0, booleans false ─────────

	@Test
	public void case03_unsetColumnsReadDdlDefaults() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		String groupPath = PbBookUtil.bookGroupPath(SLUG);
		String tag = shortId();

		BaseRecord compRead = null;
		BaseRecord actorRead = null;
		BaseRecord elRead = null;
		try {
			BaseRecord comp = newPbRecord(u, OlioModelNames.MODEL_PB_COMPOSITION, "p41-bare-" + tag, groupPath);
			comp.set(OlioFieldNames.FIELD_PB_BOOK, bk);
			compRead = readComposition(u, create(u, comp).get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(PbCompositionPurposeEnumType.UNKNOWN, compRead.getEnum(OlioFieldNames.FIELD_PB_PURPOSE));
			assertEquals(PbShotTypeEnumType.UNKNOWN, compRead.getEnum(OlioFieldNames.FIELD_PB_SHOT_TYPE));
			assertEquals(PbViewpointEnumType.UNKNOWN, compRead.getEnum(OlioFieldNames.FIELD_PB_VIEWPOINT));
			assertEquals(PbCompositionSourceEnumType.UNKNOWN, compRead.getEnum(OlioFieldNames.FIELD_PB_SOURCE));
			assertEquals(Boolean.FALSE, compRead.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
			assertEquals(Boolean.FALSE, compRead.get(OlioFieldNames.FIELD_PB_USER_EDITED));
			assertEquals(Integer.valueOf(0), compRead.get(OlioFieldNames.FIELD_PB_REVISION));

			BaseRecord actor = newPbRecord(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, "p41-bare-actor-" + tag, groupPath);
			actor.set(OlioFieldNames.FIELD_PB_COMPOSITION, compRead);
			actorRead = readActor(u, create(u, actor).get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(PbActorRoleEnumType.UNKNOWN, actorRead.getEnum(OlioFieldNames.FIELD_PB_ROLE));
			assertEquals(PbFacingEnumType.UNKNOWN, actorRead.getEnum(OlioFieldNames.FIELD_PB_FACING));
			assertEquals(PbReferencePolicyEnumType.UNKNOWN, actorRead.getEnum(OlioFieldNames.FIELD_PB_REFERENCE_POLICY));
			assertEquals(Integer.valueOf(0), actorRead.get(OlioFieldNames.FIELD_PB_PRIORITY));
			assertEquals(Integer.valueOf(0), actorRead.get(OlioFieldNames.FIELD_PB_DEPTH));
			assertEquals(Integer.valueOf(0), actorRead.get(FieldNames.FIELD_COUNT));
			assertEquals(Double.valueOf(0.0), actorRead.get(OlioFieldNames.FIELD_PB_X));
			assertEquals(Double.valueOf(0.0), actorRead.get(OlioFieldNames.FIELD_PB_H));
			assertNull(actorRead.get(OlioFieldNames.FIELD_PB_CHARACTER));
			assertNull(actorRead.get(OlioFieldNames.FIELD_APPAREL));

			BaseRecord el = newPbRecord(u, OlioModelNames.MODEL_PB_SCENE_ELEMENT, "p41-bare-el-" + tag, groupPath);
			el.set(OlioFieldNames.FIELD_PB_COMPOSITION, compRead);
			elRead = readElement(u, create(u, el).get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(PbElementTypeEnumType.UNKNOWN, elRead.getEnum(OlioFieldNames.FIELD_PB_ELEMENT_TYPE));
			assertEquals(PbImportanceEnumType.UNKNOWN, elRead.getEnum(OlioFieldNames.FIELD_PB_IMPORTANCE));
			assertEquals(Integer.valueOf(0), elRead.get(OlioFieldNames.FIELD_PB_DEPTH));
			assertEquals(Double.valueOf(0.0), elRead.get(OlioFieldNames.FIELD_PB_W));
			assertNull(elRead.get(OlioFieldNames.FIELD_ITEM));
		}
		finally {
			deleteQuietly(u, elRead);
			deleteQuietly(u, actorRead);
			deleteQuietly(u, compRead);
		}
	}

	// ───────── 4: actor CRUD, then the composition <-> focalActor cycle reads both ways ─────────

	@Test
	public void case04_actorRoundTripAndFocalActorCycle() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		String groupPath = PbBookUtil.bookGroupPath(SLUG);
		String tag = shortId();

		BaseRecord compRead = null;
		BaseRecord heroRead = null;
		BaseRecord extraRead = null;
		try {
			compRead = readComposition(u, create(u, newComposition(u, bk, "p41-actors-" + tag, groupPath,
				PbCompositionPurposeEnumType.CHAPTER_OPENER)).get(FieldNames.FIELD_OBJECT_ID));

			BaseRecord hero = newActor(u, compRead, "p41-hero-" + tag, groupPath, PbActorRoleEnumType.HERO);
			hero.set(OlioFieldNames.FIELD_PB_PRIORITY, Integer.valueOf(10));
			hero.set(OlioFieldNames.FIELD_PB_POSE, "standing, one hand on the rail");
			hero.set(FieldNames.FIELD_EXPRESSION, "wary");
			hero.set(OlioFieldNames.FIELD_PB_ACTION, "looks out to sea");
			hero.set(OlioFieldNames.FIELD_PB_FACING, PbFacingEnumType.TOWARD.toString());
			hero.set(OlioFieldNames.FIELD_PB_X, Double.valueOf(0.35));
			hero.set(OlioFieldNames.FIELD_PB_Y, Double.valueOf(0.2));
			hero.set(OlioFieldNames.FIELD_PB_W, Double.valueOf(0.3));
			hero.set(OlioFieldNames.FIELD_PB_H, Double.valueOf(0.75));
			hero.set(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(0));
			hero.set(FieldNames.FIELD_DESCRIPTION, "the lighthouse keeper");
			hero.set(FieldNames.FIELD_COUNT, Integer.valueOf(1));
			hero.set(OlioFieldNames.FIELD_PB_REFERENCE_POLICY, PbReferencePolicyEnumType.AUTO.toString());
			String heroOid = create(u, hero).get(FieldNames.FIELD_OBJECT_ID);

			heroRead = readActor(u, heroOid);
			assertEquals("p41-hero-" + tag, heroRead.get(FieldNames.FIELD_NAME));
			BaseRecord compRef = heroRead.get(OlioFieldNames.FIELD_PB_COMPOSITION);
			assertNotNull("actor.composition must resolve", compRef);
			assertEquals((String) compRead.get(FieldNames.FIELD_OBJECT_ID), compRef.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(PbActorRoleEnumType.HERO, heroRead.getEnum(OlioFieldNames.FIELD_PB_ROLE));
			assertEquals(PbFacingEnumType.TOWARD, heroRead.getEnum(OlioFieldNames.FIELD_PB_FACING));
			assertEquals(PbReferencePolicyEnumType.AUTO, heroRead.getEnum(OlioFieldNames.FIELD_PB_REFERENCE_POLICY));
			assertEquals(Integer.valueOf(10), heroRead.get(OlioFieldNames.FIELD_PB_PRIORITY));
			assertEquals("standing, one hand on the rail", heroRead.get(OlioFieldNames.FIELD_PB_POSE));
			assertEquals("wary", heroRead.get(FieldNames.FIELD_EXPRESSION));
			assertEquals("looks out to sea", heroRead.get(OlioFieldNames.FIELD_PB_ACTION));
			assertEquals(Double.valueOf(0.35), heroRead.get(OlioFieldNames.FIELD_PB_X));
			assertEquals(Double.valueOf(0.2), heroRead.get(OlioFieldNames.FIELD_PB_Y));
			assertEquals(Double.valueOf(0.3), heroRead.get(OlioFieldNames.FIELD_PB_W));
			assertEquals(Double.valueOf(0.75), heroRead.get(OlioFieldNames.FIELD_PB_H));
			assertEquals(Integer.valueOf(0), heroRead.get(OlioFieldNames.FIELD_PB_DEPTH));
			assertEquals("the lighthouse keeper", heroRead.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals(Integer.valueOf(1), heroRead.get(FieldNames.FIELD_COUNT));
			assertNull("character is nullable - an unresolved actor has none", heroRead.get(OlioFieldNames.FIELD_PB_CHARACTER));
			assertNull("apparel is nullable", heroRead.get(OlioFieldNames.FIELD_APPAREL));

			/// A CROWD actor with a count and no box edits.
			BaseRecord extra = newActor(u, compRead, "p41-crowd-" + tag, groupPath, PbActorRoleEnumType.CROWD);
			extra.set(FieldNames.FIELD_COUNT, Integer.valueOf(12));
			extra.set(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(3));
			extra.set(OlioFieldNames.FIELD_PB_REFERENCE_POLICY, PbReferencePolicyEnumType.TEXT_ONLY.toString());
			extraRead = readActor(u, create(u, extra).get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(PbActorRoleEnumType.CROWD, extraRead.getEnum(OlioFieldNames.FIELD_PB_ROLE));
			assertEquals(Integer.valueOf(12), extraRead.get(FieldNames.FIELD_COUNT));
			assertEquals(PbReferencePolicyEnumType.TEXT_ONLY, extraRead.getEnum(OlioFieldNames.FIELD_PB_REFERENCE_POLICY));

			/// PATCH the hero: facing + box.
			BaseRecord hp = patch(OlioModelNames.MODEL_PB_SCENE_ACTOR, heroRead, OlioFieldNames.FIELD_PB_FACING, OlioFieldNames.FIELD_PB_X);
			hp.set(OlioFieldNames.FIELD_PB_FACING, PbFacingEnumType.PROFILE.toString());
			hp.set(OlioFieldNames.FIELD_PB_X, Double.valueOf(0.5));
			update(u, hp);
			BaseRecord heroAfter = readActor(u, heroOid);
			assertEquals(PbFacingEnumType.PROFILE, heroAfter.getEnum(OlioFieldNames.FIELD_PB_FACING));
			assertEquals(Double.valueOf(0.5), heroAfter.get(OlioFieldNames.FIELD_PB_X));
			assertEquals("Unpatched fields are untouched", Double.valueOf(0.75), heroAfter.get(OlioFieldNames.FIELD_PB_H));
			assertEquals(Integer.valueOf(10), heroAfter.get(OlioFieldNames.FIELD_PB_PRIORITY));

			/// The cycle: composition.focalActor -> actor whose composition -> this composition.
			BaseRecord fp = patch(OlioModelNames.MODEL_PB_COMPOSITION, compRead, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			fp.set(OlioFieldNames.FIELD_PB_FOCAL_ACTOR, heroAfter);
			update(u, fp);
			BaseRecord compAfter = readComposition(u, compRead.get(FieldNames.FIELD_OBJECT_ID));
			BaseRecord focal = compAfter.get(OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			assertNotNull("composition.focalActor must resolve", focal);
			assertEquals(heroOid, focal.get(FieldNames.FIELD_OBJECT_ID));
			BaseRecord back = readActor(u, heroOid).get(OlioFieldNames.FIELD_PB_COMPOSITION);
			assertEquals("...and the actor still points back at the composition", (String) compRead.get(FieldNames.FIELD_OBJECT_ID), back.get(FieldNames.FIELD_OBJECT_ID));

			/// LIST actors by composition - the composer's per-composition lookup (hint "composition").
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioFieldNames.FIELD_PB_COMPOSITION, compRead);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
			q.setRequest(ACTOR_REQUEST);
			q.setCache(false);
			BaseRecord[] actors = ioContext.getAccessPoint().list(u, q).getResults();
			assertNotNull(actors);
			assertEquals("Exactly the two actors of this composition", 2, actors.length);

			/// Clear the focal actor before the actor goes, as a UI would.
			BaseRecord unlink = patch(OlioModelNames.MODEL_PB_COMPOSITION, compAfter, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			unlink.set(OlioFieldNames.FIELD_PB_FOCAL_ACTOR, null);
			update(u, unlink);
			assertNull("focalActor is nullable and can be cleared", readComposition(u, compRead.get(FieldNames.FIELD_OBJECT_ID)).get(OlioFieldNames.FIELD_PB_FOCAL_ACTOR));

			assertTrue("The owner can delete the hero", ioContext.getAccessPoint().delete(u, heroAfter));
			heroRead = null;
			assertNull("Deleted actors are gone", readFresh(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, heroOid, ACTOR_REQUEST));
		}
		finally {
			deleteQuietly(u, extraRead);
			deleteQuietly(u, heroRead);
			deleteQuietly(u, compRead);
		}
	}

	// ───────── 5: element CRUD with and without the olio.item FK ─────────

	@Test
	public void case05_elementRoundTripWithItemFk() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = book(u);
		BookContext bc = bookContext(u, bk);
		String groupPath = PbBookUtil.bookGroupPath(SLUG);
		String tag = shortId();

		BaseRecord compRead = null;
		BaseRecord itemRead = null;
		BaseRecord lanternRead = null;
		BaseRecord seaRead = null;
		try {
			compRead = readComposition(u, create(u, newComposition(u, bk, "p41-elements-" + tag, groupPath,
				PbCompositionPurposeEnumType.SCENE)).get(FieldNames.FIELD_OBJECT_ID));

			BaseRecord item = newItem(u, bc, "p41-lantern-" + tag, "a brass storm lantern, glass cracked", "brass");
			String itemOid = create(u, item).get(FieldNames.FIELD_OBJECT_ID);
			itemRead = readItem(u, itemOid);
			assertEquals("p41-lantern-" + tag, itemRead.get(FieldNames.FIELD_NAME));
			assertEquals("a brass storm lantern, glass cracked", itemRead.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals(Arrays.asList("brass"), itemRead.get("materials"));
			long itemsGroupId = bc.getGroup(OlioFieldNames.FIELD_ITEMS).get(FieldNames.FIELD_ID);
			assertEquals("Created in the world's Items group", Long.valueOf(itemsGroupId), itemRead.get(FieldNames.FIELD_GROUP_ID));

			BaseRecord lantern = newElement(u, compRead, "p41-el-lantern-" + tag, groupPath, PbElementTypeEnumType.PROP);
			lantern.set(FieldNames.FIELD_DESCRIPTION, "the lantern on the rail");
			lantern.set(OlioFieldNames.FIELD_PB_X, Double.valueOf(0.6));
			lantern.set(OlioFieldNames.FIELD_PB_Y, Double.valueOf(0.55));
			lantern.set(OlioFieldNames.FIELD_PB_W, Double.valueOf(0.08));
			lantern.set(OlioFieldNames.FIELD_PB_H, Double.valueOf(0.12));
			lantern.set(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(1));
			lantern.set(OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.HIGH.toString());
			lantern.set(OlioFieldNames.FIELD_ITEM, itemRead);
			String lanternOid = create(u, lantern).get(FieldNames.FIELD_OBJECT_ID);

			lanternRead = readElement(u, lanternOid);
			assertEquals("p41-el-lantern-" + tag, lanternRead.get(FieldNames.FIELD_NAME));
			assertEquals("Created in the book's Book group", Long.valueOf(bookGroupId(bc)), lanternRead.get(FieldNames.FIELD_GROUP_ID));
			BaseRecord compRef = lanternRead.get(OlioFieldNames.FIELD_PB_COMPOSITION);
			assertNotNull("element.composition must resolve", compRef);
			assertEquals((String) compRead.get(FieldNames.FIELD_OBJECT_ID), compRef.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(PbElementTypeEnumType.PROP, lanternRead.getEnum(OlioFieldNames.FIELD_PB_ELEMENT_TYPE));
			assertEquals(PbImportanceEnumType.HIGH, lanternRead.getEnum(OlioFieldNames.FIELD_PB_IMPORTANCE));
			assertEquals("the lantern on the rail", lanternRead.get(FieldNames.FIELD_DESCRIPTION));
			assertEquals(Double.valueOf(0.6), lanternRead.get(OlioFieldNames.FIELD_PB_X));
			assertEquals(Double.valueOf(0.55), lanternRead.get(OlioFieldNames.FIELD_PB_Y));
			assertEquals(Double.valueOf(0.08), lanternRead.get(OlioFieldNames.FIELD_PB_W));
			assertEquals(Double.valueOf(0.12), lanternRead.get(OlioFieldNames.FIELD_PB_H));
			assertEquals(Integer.valueOf(1), lanternRead.get(OlioFieldNames.FIELD_PB_DEPTH));
			BaseRecord itemRef = lanternRead.get(OlioFieldNames.FIELD_ITEM);
			assertNotNull("element.item must resolve to the olio.item", itemRef);
			assertEquals(itemOid, itemRef.get(FieldNames.FIELD_OBJECT_ID));

			/// A NATURE element with no item - the common case.
			BaseRecord sea = newElement(u, compRead, "p41-el-sea-" + tag, groupPath, PbElementTypeEnumType.NATURE);
			sea.set(FieldNames.FIELD_DESCRIPTION, "grey sea, white caps");
			sea.set(OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.LOW.toString());
			sea.set(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(9));
			seaRead = readElement(u, create(u, sea).get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(PbElementTypeEnumType.NATURE, seaRead.getEnum(OlioFieldNames.FIELD_PB_ELEMENT_TYPE));
			assertEquals(PbImportanceEnumType.LOW, seaRead.getEnum(OlioFieldNames.FIELD_PB_IMPORTANCE));
			assertNull("item is nullable", seaRead.get(OlioFieldNames.FIELD_ITEM));

			/// PATCH: importance + unlink the item; then relink.
			BaseRecord ep = patch(OlioModelNames.MODEL_PB_SCENE_ELEMENT, lanternRead, OlioFieldNames.FIELD_PB_IMPORTANCE, OlioFieldNames.FIELD_ITEM);
			ep.set(OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.MEDIUM.toString());
			ep.set(OlioFieldNames.FIELD_ITEM, null);
			update(u, ep);
			BaseRecord lanternAfter = readElement(u, lanternOid);
			assertEquals(PbImportanceEnumType.MEDIUM, lanternAfter.getEnum(OlioFieldNames.FIELD_PB_IMPORTANCE));
			assertNull("The item FK can be cleared", lanternAfter.get(OlioFieldNames.FIELD_ITEM));
			assertEquals("Unpatched fields are untouched", Double.valueOf(0.6), lanternAfter.get(OlioFieldNames.FIELD_PB_X));

			BaseRecord ep2 = patch(OlioModelNames.MODEL_PB_SCENE_ELEMENT, lanternAfter, OlioFieldNames.FIELD_ITEM);
			ep2.set(OlioFieldNames.FIELD_ITEM, itemRead);
			update(u, ep2);
			assertEquals(itemOid, ((BaseRecord) readElement(u, lanternOid).get(OlioFieldNames.FIELD_ITEM)).get(FieldNames.FIELD_OBJECT_ID));

			/// LIST elements by composition (hint "composition").
			Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_SCENE_ELEMENT, OlioFieldNames.FIELD_PB_COMPOSITION, compRead);
			q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
			q.setRequest(ELEMENT_REQUEST);
			q.setCache(false);
			BaseRecord[] els = ioContext.getAccessPoint().list(u, q).getResults();
			assertNotNull(els);
			assertEquals("Exactly the two elements of this composition", 2, els.length);

			assertTrue("The owner can delete the lantern element", ioContext.getAccessPoint().delete(u, lanternAfter));
			lanternRead = null;
			assertNull("Deleted elements are gone", readFresh(u, OlioModelNames.MODEL_PB_SCENE_ELEMENT, lanternOid, ELEMENT_REQUEST));
			assertNotNull("Deleting an element never touches the item it referenced", readItem(u, itemOid));
		}
		finally {
			deleteQuietly(u, seaRead);
			deleteQuietly(u, lanternRead);
			deleteQuietly(u, itemRead);
			deleteQuietly(u, compRead);
		}
	}

	// ───────── 6: each model fits under the 49-field nested cap, and a projected read follows scene + focalActor ─────────

	/**
	 * Since 2026-10-07 any nested model over {@link QueryPlan#MAX_NESTED_PLAN_FIELDS} is reduced to its
	 * common fields rather than blowing PostgreSQL's 100-argument limit; the static count asserts each of
	 * the three models fits as a nested child, so a parent that projects it gets its full field set. The
	 * live half reads a composition with {@code scene.*} and {@code focalActor.*} request paths, and the
	 * actor back through {@code composition.*}, which is the read shape for these models. A bare
	 * {@code planMost(true)} is not: it walks composition -> book -> world -> sdConfig and round the
	 * composition <-> focalActor cycle until the statement collapses (measured 2026-10-18, the first run of
	 * this case), exactly as it does on olio.pb.scene via sceneNode -> workflow.
	 */
	@Test
	public void case06_projectedReadUnderNestedCap() throws Exception {
		for(String[] pair : new String[][] {
			{ OlioModelNames.MODEL_PB_SCENE_ACTOR, OlioModelNames.MODEL_PB_COMPOSITION },
			{ OlioModelNames.MODEL_PB_SCENE_ELEMENT, OlioModelNames.MODEL_PB_COMPOSITION },
			{ OlioModelNames.MODEL_PB_COMPOSITION, OlioModelNames.MODEL_PB_SCENE_ACTOR },
			{ OlioModelNames.MODEL_PB_COMPOSITION, OlioModelNames.MODEL_PB_SCENE }
		}) {
			ModelSchema ms = RecordFactory.getSchema(pair[0]);
			int n = QueryPlan.countMaterializedFields(ms, RecordUtil.getMostRequestFields(pair[0]), pair[1]);
			assertTrue(pair[0] + " nested under " + pair[1] + " materializes " + n + " fields; the cap is "
				+ QueryPlan.MAX_NESTED_PLAN_FIELDS, n <= QueryPlan.MAX_NESTED_PLAN_FIELDS);
		}

		BaseRecord u = user();
		BaseRecord bk = book(u);
		long orgId = orgOf(u);
		String groupPath = PbBookUtil.bookGroupPath(SLUG);
		String tag = shortId();

		BaseRecord compRead = null;
		BaseRecord actorRead = null;
		BaseRecord scene = null;
		try {
			int sceneIndex = 9000 + new Random().nextInt(900);
			scene = PbBookUtil.createScene(u, bk, sceneIndex, "p41 full scene " + tag, groupPath);
			assertNotNull(scene);
			BaseRecord comp = newComposition(u, bk, "p41-full-" + tag, groupPath, PbCompositionPurposeEnumType.SCENE);
			comp.set(OlioFieldNames.FIELD_PB_SCENE, scene);
			comp.set(OlioFieldNames.FIELD_PB_SHOT_TYPE, PbShotTypeEnumType.MEDIUM.toString());
			String compOid = create(u, comp).get(FieldNames.FIELD_OBJECT_ID);
			compRead = readComposition(u, compOid);
			actorRead = readActor(u, create(u, newActor(u, compRead, "p41-full-hero-" + tag, groupPath, PbActorRoleEnumType.HERO)).get(FieldNames.FIELD_OBJECT_ID));
			final String actorOid = actorRead.get(FieldNames.FIELD_OBJECT_ID);
			final String sceneOid = scene.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord fp = patch(OlioModelNames.MODEL_PB_COMPOSITION, compRead, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			fp.set(OlioFieldNames.FIELD_PB_FOCAL_ACTOR, actorRead);
			update(u, fp);

			final String[] compRequest = withPaths(COMPOSITION_REQUEST,
				OlioFieldNames.FIELD_PB_SCENE + "." + FieldNames.FIELD_NAME,
				OlioFieldNames.FIELD_PB_SCENE + "." + OlioFieldNames.FIELD_PB_SCENE_INDEX,
				OlioFieldNames.FIELD_PB_FOCAL_ACTOR + "." + FieldNames.FIELD_NAME,
				OlioFieldNames.FIELD_PB_FOCAL_ACTOR + "." + OlioFieldNames.FIELD_PB_ROLE);
			final BaseRecord[] full = new BaseRecord[1];
			long start = System.currentTimeMillis();
			withinSeconds(120, "projected read of olio.pb.composition with scene.* + focalActor.* paths", () -> {
				full[0] = projectedRead(u, OlioModelNames.MODEL_PB_COMPOSITION, compOid, orgId, compRequest);
				return Boolean.TRUE;
			});
			logger.info("projected read of olio.pb.composition returned in " + (System.currentTimeMillis() - start) + " ms");
			assertNotNull("The projected read must return the composition", full[0]);
			assertEquals(compOid, full[0].get(FieldNames.FIELD_OBJECT_ID));
			assertEquals(PbShotTypeEnumType.MEDIUM, full[0].getEnum(OlioFieldNames.FIELD_PB_SHOT_TYPE));
			BaseRecord fullScene = full[0].get(OlioFieldNames.FIELD_PB_SCENE);
			assertNotNull("The nested scene is populated", fullScene);
			assertEquals(sceneOid, fullScene.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals("scene.sceneIndex followed the request path", sceneIndex, (int) fullScene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX));
			BaseRecord fullFocal = full[0].get(OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			assertNotNull("The nested focal actor is populated", fullFocal);
			assertEquals(actorOid, fullFocal.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals("focalActor.name followed the request path", "p41-full-hero-" + tag, fullFocal.get(FieldNames.FIELD_NAME));
			assertEquals("focalActor.role followed the request path", PbActorRoleEnumType.HERO, fullFocal.getEnum(OlioFieldNames.FIELD_PB_ROLE));

			final String[] actorRequest = withPaths(ACTOR_REQUEST,
				OlioFieldNames.FIELD_PB_COMPOSITION + "." + FieldNames.FIELD_NAME,
				OlioFieldNames.FIELD_PB_COMPOSITION + "." + OlioFieldNames.FIELD_PB_PURPOSE);
			final BaseRecord[] fullActor = new BaseRecord[1];
			withinSeconds(120, "projected read of olio.pb.sceneActor with composition.* paths while composition.focalActor points back", () -> {
				fullActor[0] = projectedRead(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, actorOid, orgId, actorRequest);
				return Boolean.TRUE;
			});
			assertNotNull("The projected read of the actor must return", fullActor[0]);
			BaseRecord backComp = fullActor[0].get(OlioFieldNames.FIELD_PB_COMPOSITION);
			assertNotNull(backComp);
			assertEquals(compOid, backComp.get(FieldNames.FIELD_OBJECT_ID));
			assertEquals("composition.name followed the request path", "p41-full-" + tag, backComp.get(FieldNames.FIELD_NAME));
			assertEquals("composition.purpose followed the request path", PbCompositionPurposeEnumType.SCENE, backComp.getEnum(OlioFieldNames.FIELD_PB_PURPOSE));

			BaseRecord unlink = patch(OlioModelNames.MODEL_PB_COMPOSITION, compRead, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			unlink.set(OlioFieldNames.FIELD_PB_FOCAL_ACTOR, null);
			update(u, unlink);
		}
		finally {
			deleteQuietly(u, actorRead);
			deleteQuietly(u, compRead);
			deleteQuietly(u, scene);
		}
	}

	// ───────── 7: watched/v4 notices actor, element and item edits; ignores composition bookkeeping; FKs render by name ─────────

	@Test
	public void case07_watchedSetDetectsTheEditsItDeclares() throws Exception {
		assertEquals("watched/v4", PbWatchedFields.WATCHED_SET_VERSION);
		for(String model : new String[] { OlioModelNames.MODEL_PB_COMPOSITION, OlioModelNames.MODEL_PB_SCENE_ACTOR,
			OlioModelNames.MODEL_PB_SCENE_ELEMENT, OlioModelNames.MODEL_ITEM }) {
			assertTrue(model + " must be watched", PbWatchedFields.watchedModels().contains(model));
			ModelSchema ms = RecordFactory.getSchema(model);
			List<String> proj = Arrays.asList(PbWatchedFields.projection(model));
			for(String f : PbWatchedFields.watchedFields(model)) {
				assertNotNull("Watched field " + model + "." + f + " must exist on the model", ms.getFieldSchema(f));
				assertTrue("projection(" + model + ") must carry " + f, proj.contains(f));
			}
		}
		List<String> compWatched = PbWatchedFields.watchedFields(OlioModelNames.MODEL_PB_COMPOSITION);
		assertTrue(compWatched.contains(OlioFieldNames.FIELD_PB_FOCAL_ACTOR));
		assertTrue(compWatched.contains(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertFalse("revision is bookkeeping, not appearance", compWatched.contains(OlioFieldNames.FIELD_PB_REVISION));
		assertFalse("source is bookkeeping, not appearance", compWatched.contains(OlioFieldNames.FIELD_PB_SOURCE));
		assertFalse("userEdited is bookkeeping, not appearance", compWatched.contains(OlioFieldNames.FIELD_PB_USER_EDITED));
		assertFalse("the book FK never changes", compWatched.contains(OlioFieldNames.FIELD_PB_BOOK));
		assertFalse("the actor's composition FK never changes", PbWatchedFields.watchedFields(OlioModelNames.MODEL_PB_SCENE_ACTOR).contains(OlioFieldNames.FIELD_PB_COMPOSITION));
		assertEquals(Arrays.asList(FieldNames.FIELD_NAME, FieldNames.FIELD_DESCRIPTION, "color", "complementColor", "accentColor", "materials"),
			PbWatchedFields.watchedFields(OlioModelNames.MODEL_ITEM));

		BaseRecord u = user();
		BaseRecord bk = book(u);
		BookContext bc = bookContext(u, bk);
		String groupPath = PbBookUtil.bookGroupPath(SLUG);
		String tag = shortId();

		BaseRecord compRead = null;
		BaseRecord actorRead = null;
		BaseRecord elRead = null;
		BaseRecord itemRead = null;
		try {
			compRead = readComposition(u, create(u, newComposition(u, bk, "p41-watch-" + tag, groupPath,
				PbCompositionPurposeEnumType.SCENE)).get(FieldNames.FIELD_OBJECT_ID));
			String compOid = compRead.get(FieldNames.FIELD_OBJECT_ID);
			String c0 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_COMPOSITION, compOid);
			assertNotNull("A watched model hashes", c0);
			assertEquals("Deterministic", c0, PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_COMPOSITION, compOid));

			/// bookkeeping edits do not change the hash
			BaseRecord bp = patch(OlioModelNames.MODEL_PB_COMPOSITION, compRead, OlioFieldNames.FIELD_PB_REVISION, OlioFieldNames.FIELD_PB_SOURCE, OlioFieldNames.FIELD_PB_USER_EDITED);
			bp.set(OlioFieldNames.FIELD_PB_REVISION, Integer.valueOf(7));
			bp.set(OlioFieldNames.FIELD_PB_SOURCE, PbCompositionSourceEnumType.MIGRATED.toString());
			bp.set(OlioFieldNames.FIELD_PB_USER_EDITED, Boolean.TRUE);
			update(u, bp);
			assertEquals(Integer.valueOf(7), readComposition(u, compOid).get(OlioFieldNames.FIELD_PB_REVISION));
			assertEquals("revision/source/userEdited edits must not change the refHash", c0,
				PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_COMPOSITION, compOid));

			BaseRecord sp = patch(OlioModelNames.MODEL_PB_COMPOSITION, compRead, OlioFieldNames.FIELD_PB_SHOT_TYPE);
			sp.set(OlioFieldNames.FIELD_PB_SHOT_TYPE, PbShotTypeEnumType.CLOSE_UP.toString());
			update(u, sp);
			String c1 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_COMPOSITION, compOid);
			assertNotEquals("A shotType edit must change the refHash", c0, c1);

			BaseRecord pp = patch(OlioModelNames.MODEL_PB_COMPOSITION, compRead, OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE);
			pp.set(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE, "override text");
			update(u, pp);
			String c2 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_COMPOSITION, compOid);
			assertNotEquals("A promptOverride edit must change the refHash", c1, c2);

			/// focalActor: rendered by name in canonicalRef (not an artifact), and setting it changes the hash
			actorRead = readActor(u, create(u, newActor(u, compRead, "p41-watch-hero-" + tag, groupPath, PbActorRoleEnumType.HERO)).get(FieldNames.FIELD_OBJECT_ID));
			String actorOid = actorRead.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord fp = patch(OlioModelNames.MODEL_PB_COMPOSITION, compRead, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			fp.set(OlioFieldNames.FIELD_PB_FOCAL_ACTOR, actorRead);
			update(u, fp);
			String canon = PbWatchedFields.canonicalRef(readWatched(u, OlioModelNames.MODEL_PB_COMPOSITION, compOid), OlioModelNames.MODEL_PB_COMPOSITION);
			assertTrue("canonicalRef starts with the version: " + canon, canon.startsWith(PbWatchedFields.WATCHED_SET_VERSION));
			assertTrue("canonicalRef renders focalActor by name: " + canon,
				canon.contains(OlioFieldNames.FIELD_PB_FOCAL_ACTOR + "=p41-watch-hero-" + tag));
			String c3 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_COMPOSITION, compOid);
			assertNotEquals("Setting focalActor must change the refHash", c2, c3);

			/// the actor's own set: facing changes it, the composition FK is not watched at all
			String a0 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, actorOid);
			assertNotNull(a0);
			BaseRecord ap = patch(OlioModelNames.MODEL_PB_SCENE_ACTOR, actorRead, OlioFieldNames.FIELD_PB_FACING);
			ap.set(OlioFieldNames.FIELD_PB_FACING, PbFacingEnumType.AWAY.toString());
			update(u, ap);
			String a1 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, actorOid);
			assertNotEquals("A facing edit must change the actor refHash", a0, a1);
			BaseRecord xp = patch(OlioModelNames.MODEL_PB_SCENE_ACTOR, actorRead, OlioFieldNames.FIELD_PB_X, OlioFieldNames.FIELD_PB_DEPTH);
			xp.set(OlioFieldNames.FIELD_PB_X, Double.valueOf(0.42));
			xp.set(OlioFieldNames.FIELD_PB_DEPTH, Integer.valueOf(2));
			update(u, xp);
			String a2 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, actorOid);
			assertNotEquals("A box/depth edit must change the actor refHash", a1, a2);
			String actorCanon = PbWatchedFields.canonicalRef(readWatched(u, OlioModelNames.MODEL_PB_SCENE_ACTOR, actorOid), OlioModelNames.MODEL_PB_SCENE_ACTOR);
			assertFalse("The actor's composition FK is not part of its canonical form: " + actorCanon,
				actorCanon.contains(OlioFieldNames.FIELD_PB_COMPOSITION + "="));

			/// the element's set: importance changes it; its item renders by name and an item edit changes the item's hash
			itemRead = readItem(u, create(u, newItem(u, bc, "p41-watch-item-" + tag, "a rusted anchor", "iron")).get(FieldNames.FIELD_OBJECT_ID));
			String itemOid = itemRead.get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord el = newElement(u, compRead, "p41-watch-el-" + tag, groupPath, PbElementTypeEnumType.PROP);
			el.set(OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.LOW.toString());
			el.set(OlioFieldNames.FIELD_ITEM, itemRead);
			elRead = readElement(u, create(u, el).get(FieldNames.FIELD_OBJECT_ID));
			String elOid = elRead.get(FieldNames.FIELD_OBJECT_ID);
			String e0 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_SCENE_ELEMENT, elOid);
			assertNotNull(e0);
			String elCanon = PbWatchedFields.canonicalRef(readWatched(u, OlioModelNames.MODEL_PB_SCENE_ELEMENT, elOid), OlioModelNames.MODEL_PB_SCENE_ELEMENT);
			assertTrue("canonicalRef renders item by name: " + elCanon, elCanon.contains(OlioFieldNames.FIELD_ITEM + "=p41-watch-item-" + tag));
			BaseRecord ip = patch(OlioModelNames.MODEL_PB_SCENE_ELEMENT, elRead, OlioFieldNames.FIELD_PB_IMPORTANCE);
			ip.set(OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.HIGH.toString());
			update(u, ip);
			String e1 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_PB_SCENE_ELEMENT, elOid);
			assertNotEquals("An importance edit must change the element refHash", e0, e1);

			String i0 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_ITEM, itemOid);
			assertNotNull("olio.item is watched", i0);
			BaseRecord idp = patch(OlioModelNames.MODEL_ITEM, itemRead, FieldNames.FIELD_DESCRIPTION);
			idp.set(FieldNames.FIELD_DESCRIPTION, "a rusted anchor, chain still attached");
			update(u, idp);
			String i1 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_ITEM, itemOid);
			assertNotEquals("An item description edit must change the item refHash", i0, i1);
			BaseRecord imp = patch(OlioModelNames.MODEL_ITEM, itemRead, "materials");
			List<String> mats = imp.get("materials");
			mats.add("iron");
			mats.add("rope");
			update(u, imp);
			assertEquals(Arrays.asList("iron", "rope"), readItem(u, itemOid).get("materials"));
			String i2 = PbWatchedFields.computeRefHash(u, OlioModelNames.MODEL_ITEM, itemOid);
			assertNotEquals("An item materials edit must change the item refHash", i1, i2);

			BaseRecord unlink = patch(OlioModelNames.MODEL_PB_COMPOSITION, compRead, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			unlink.set(OlioFieldNames.FIELD_PB_FOCAL_ACTOR, null);
			update(u, unlink);
		}
		finally {
			deleteQuietly(u, elRead);
			deleteQuietly(u, itemRead);
			deleteQuietly(u, actorRead);
			deleteQuietly(u, compRead);
		}
	}

	// ───────── 8: deleteBookComplete removes compositions, actors and elements with the book ─────────

	@Test
	public void case08_deleteBookCompleteRemovesTheThreeModels() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		String slug = "p41del" + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-1 delete fixture " + slug);
		assertNotNull("Failed to create the throwaway book", bk);
		String bookOid = bk.get(FieldNames.FIELD_OBJECT_ID);
		String groupPath = PbBookUtil.bookGroupPath(slug);
		try {
			BaseRecord compRead = readComposition(u, create(u, newComposition(u, bk, "p41-del-comp", groupPath,
				PbCompositionPurposeEnumType.SCENE)).get(FieldNames.FIELD_OBJECT_ID));
			String compOid = compRead.get(FieldNames.FIELD_OBJECT_ID);
			String actorOid = create(u, newActor(u, compRead, "p41-del-actor", groupPath, PbActorRoleEnumType.SUPPORTING)).get(FieldNames.FIELD_OBJECT_ID);
			String elOid = create(u, newElement(u, compRead, "p41-del-el", groupPath, PbElementTypeEnumType.FURNITURE)).get(FieldNames.FIELD_OBJECT_ID);
			BaseRecord fp = patch(OlioModelNames.MODEL_PB_COMPOSITION, compRead, OlioFieldNames.FIELD_PB_FOCAL_ACTOR);
			fp.set(OlioFieldNames.FIELD_PB_FOCAL_ACTOR, readActor(u, actorOid));
			update(u, fp);

			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, bookOid);
			assertNotNull(res);
			assertTrue("deleteBookComplete must succeed: " + res.reason + " steps=" + res.steps, res.deleted);
			assertStep(res, "sceneElementsByComposition", OlioModelNames.MODEL_PB_SCENE_ELEMENT, 1);
			assertStep(res, "sceneActorsByComposition", OlioModelNames.MODEL_PB_SCENE_ACTOR, 1);
			assertStep(res, "compositionsByBook", OlioModelNames.MODEL_PB_COMPOSITION, 1);

			/// raw reads: inspection only, so a PBAC-shaped miss cannot masquerade as a delete
			assertEquals("The composition row is gone", 0, rawCount(OlioModelNames.MODEL_PB_COMPOSITION, compOid, orgId));
			assertEquals("The actor row is gone", 0, rawCount(OlioModelNames.MODEL_PB_SCENE_ACTOR, actorOid, orgId));
			assertEquals("The element row is gone", 0, rawCount(OlioModelNames.MODEL_PB_SCENE_ELEMENT, elOid, orgId));
			assertNull("The book is gone", PbBookUtil.findBookBySlug(u, slug, orgId));
			bk = null;
		}
		finally {
			if(bk != null) {
				PbDeleteUtil.deleteBookComplete(u, bookOid);
			}
		}
	}

	private void assertStep(DeleteResult res, String step, String model, int count) {
		for(Map<String, Object> s : res.steps) {
			if(step.equals(s.get("step"))) {
				assertEquals("step " + step + " model", model, s.get("model"));
				assertEquals("step " + step + " count", Integer.valueOf(count), s.get("count"));
				assertEquals("step " + step + " ok (" + s.get("reason") + ")", Boolean.TRUE, s.get("ok"));
				return;
			}
		}
		fail("deleteBookComplete emitted no '" + step + "' step; steps=" + res.steps);
	}

	private int rawCount(String model, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID });
		q.setCache(false);
		BaseRecord[] rows = ioContext.getSearch().findRecords(q);
		return rows == null ? 0 : rows.length;
	}

}
