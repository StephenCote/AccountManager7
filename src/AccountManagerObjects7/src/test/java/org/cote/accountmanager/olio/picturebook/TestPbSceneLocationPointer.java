package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * P3-7 (PictureBook V3 design §24, §26; Implementation §11.4): the one scene-pointing path the Ux uses -
 * {@link PbServiceFacade#setSceneLocation} over {@link PbBookUtil#setSceneLocation} - against the live
 * {@code am7db} as a non-admin owner. A ChapBook page is an {@code olio.pb.scene}; the fixture is
 * {@link TestChapBookPageLocation}'s (two pages from a two-stanza poem, the book's own world) minus every
 * SD / LLM / render / propose call, so each case is a few PBAC writes and fresh re-reads. Nothing is mocked.
 * <ol>
 * <li>{@link #case01PointLocationOnly}: location only - the facade echoes the location, nulls for the
 *     variant, {@code updated:true}; a fresh read carries the location FK and no variant.</li>
 * <li>{@link #case02PointLocationAndVariant}: both ids - both FKs persisted, both names echoed.</li>
 * <li>{@link #case03VariantOnlyResolvesItsParent}: variant id alone - the parent location is resolved and
 *     both FKs are written.</li>
 * <li>{@link #case04ClearAfterPointing}: both ids null after pointing - both FKs cleared, still
 *     {@code updated:true}, nulls echoed.</li>
 * <li>{@link #case05VariantMustBelongToTheLocation}: util 400 for a variant without its location; util 400
 *     "is not a variant of" for a variant of another location of the same book; facade 404 "Location variant
 *     not found under that location" for location A + a variant of B. Nothing is written by a refusal.</li>
 * <li>{@link #case06LocationOfAnotherWorldIs404}: a location (and a variant) of the owner's OTHER ChapBook,
 *     in its own world (precondition: {@code !PbLocationUtil.sameWorld}) - 404 (D4), nothing written.</li>
 * <li>{@link #case07SceneMustBelongToTheBook}: a page of another book - 404 naming the scene; an unknown
 *     scene - 404; a blank scene id - 400.</li>
 * <li>{@link #case08StrangerCannotSeeTheBook}: a second user with no grant - 404 "Book not found" before
 *     the scene or location is consulted.</li>
 * <li>{@link #case09PointingMarksTheSceneNodeStaleEvenWhenPinned}: a LANDSCAPE page node linked as
 *     {@code scene.sceneNode} (the {@code ensureChapBookPageNode} shape) plus a LOCATION node: pointing a
 *     DONE node leaves it STALE ({@code nodeStale:true}, {@code pinned:false}); pinned and re-set DONE, a
 *     re-point to another location is still STALE with {@code pinned:true} and no exception; the LOCATION
 *     node is never touched by scene pointing.</li>
 * <li>{@link #case10NoNodeIsNotStale}: a page with no node - {@code nodeStale:false}, {@code nodeStatus}
 *     null, {@code pinned:false}, FKs still written.</li>
 * <li>{@link #case11BookPageViewCarriesThePointer}: page 0 at a location + variant, page 1 unpointed -
 *     {@link PbServiceFacade#bookPageView} carries the four pointer keys with values on page 0 and PRESENT
 *     with null values on page 1.</li>
 * </ol>
 * The read-only grantee's 403 is proven at the route (TestPictureBookLocationRoutes), not here.
 */
public class TestPbSceneLocationPointer extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";
	private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";
	private static final String POEM_TEXT = "Outside, all is pristine,\n"
		+ "From cobalt skies of charcoal unity\n"
		+ "Descending upon snow canvassed green\n"
		+ "To silver veins of icy sheens,\n"
		+ "Born of spells and sorcery.\n"
		+ "\n"
		+ "Inside hearts and hearths and homes,\n"
		+ "Ochre embers and ebon cinders,\n"
		+ "Faded life stirred by motherly crones,\n"
		+ "Dry damp clothes and warm cold bones\n"
		+ "And illuminate the age-old spellbound tomes.";
	private static final String TAVERN = "A low-beamed tavern by the quay, lanterns in the windows, barrels stacked by the door";
	private static final String CHAPEL = "A chapel of white salt blocks on the headland";

	private OrganizationContext ctx;
	private BaseRecord testUser;

	@Before
	public void setUpSceneLocationPointer() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookTestUser", ctx.getOrganizationId());
		assertNotNull("chapbookTestUser must be created", testUser);
		PbStaleUtil.clear();
	}

	// ───────────────────────────── fixture (TestChapBookPageLocation's idioms, no render) ─────────────────────────────

	private static final class Fixture {
		String bookOid;
		String slug;
		long orgId;
		List<BaseRecord> scenes;
		List<String> sceneOids = new ArrayList<>();
	}

	private static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	private static String oidOf(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	/// The objectId a foreign field points at, or null when the field is unset / cleared.
	private static String fkOid(BaseRecord rec, String field) {
		Object o = (rec.hasField(field) ? rec.get(field) : null);
		return (o instanceof BaseRecord ? oidOf((BaseRecord) o) : null);
	}

	private static long fkId(BaseRecord rec, String field) {
		Object o = (rec.hasField(field) ? rec.get(field) : null);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}

	private static String status(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return (v != null ? String.valueOf(v).toUpperCase() : null);
	}

	private static String upper(Object v) {
		return (v != null ? String.valueOf(v).toUpperCase() : null);
	}

	private BaseRecord createPoem(BaseRecord user, String groupPath, String name, String text) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord poem = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			poem.set("text", text);
			poem.set("title", name);
			return IOSystem.getActiveContext().getAccessPoint().create(user, poem);
		}
		catch (Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}

	/// Two pages from the two-stanza poem at 5 lines a page, in a ChapBook with its own world; no chatConfig,
	/// so no LLM call is made.
	private Fixture createFixture(String tag) {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		Fixture fx = new Fixture();
		fx.orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();
		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookP37-" + tag + "-" + ts, "Poem P3-7 " + tag + " " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(oidOf(poem));
		fx.slug = "cb-p37-" + tag + "-" + ts;
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, fx.slug, "ChapBook P3-7 " + tag + " " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		fx.bookOid = oidOf(book);
		fx.scenes = PbBookUtil.listScenes(testUser, readBook(fx));
		assertEquals("two stanzas at 5 lines a page give two pages", 2, fx.scenes.size());
		for (BaseRecord s : fx.scenes) {
			fx.sceneOids.add(oidOf(s));
		}
		return fx;
	}

	private BaseRecord readBook(Fixture fx) {
		BaseRecord b = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		assertNotNull("readBook " + fx.slug, b);
		return b;
	}

	private BaseRecord readScene(Fixture fx, String sceneOid) {
		BaseRecord s = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertNotNull("page " + sceneOid, s);
		return s;
	}

	private void deleteBook(Fixture fx) {
		if (fx == null || fx.bookOid == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(testUser, fx.bookOid);
			logger.info("cleanup " + fx.slug + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch (Exception e) {
			logger.error("cleanup " + fx.slug + ": " + e.getMessage());
		}
	}

	private BaseRecord newLocation(Fixture fx, String name, String description) {
		BaseRecord loc = PbLocationUtil.getCreateLocation(testUser, readBook(fx), name, description, PbLocationKindEnumType.INTERIOR, List.of("the taproom"));
		assertNotNull("getCreateLocation " + name, loc);
		return loc;
	}

	private BaseRecord newVariant(Fixture fx, String locOid, String name, String timeOfDay, String weather) {
		BaseRecord v = PbLocationUtil.createVariant(testUser, readBook(fx), locOid, name, timeOfDay, weather, null, null, null);
		assertNotNull("createVariant " + name, v);
		assertEquals("the variant hangs off its location", locOid, fkOid(v, FieldNames.FIELD_LOCATION));
		return v;
	}

	private BaseRecord mustReadLocation(Fixture fx, String locOid) {
		BaseRecord loc = PbLocationUtil.readLocation(testUser, locOid, fx.orgId);
		assertNotNull("location " + locOid, loc);
		return loc;
	}

	private BaseRecord mustReadVariant(Fixture fx, String varOid) {
		BaseRecord v = PbLocationUtil.readVariant(testUser, varOid, fx.orgId);
		assertNotNull("variant " + varOid, v);
		return v;
	}

	private BaseRecord workflow(Fixture fx) {
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(testUser, readBook(fx), PbBookUtil.workflowGroupPath(fx.slug));
		assertNotNull("workflow", wf);
		return wf;
	}

	/// A LOCATION node for the location, the TestChapBookPageLocation shape.
	private BaseRecord locationNode(Fixture fx, BaseRecord wf, String locOid) {
		BaseRecord n = PbGraphUtil.addNode(testUser, wf, PbPipelineUtil.locationHandle(locOid), PbNodeTypeEnumType.LOCATION, PbBookUtil.workflowGroupPath(fx.slug), 0);
		assertNotNull("location node " + locOid, n);
		assertTrue("scope patch", PbPipelineUtil.patchNodeScope(testUser, wf, n, PbPipelineUtil.SCOPE_LOCATION, locOid, 0));
		BaseRecord fresh = readNode(fx, oidOf(n));
		assertEquals("the location node is scoped to its location", locOid, fresh.get(OlioFieldNames.FIELD_PB_SCOPE_REF));
		return fresh;
	}

	/// The page's LANDSCAPE node linked as scene.sceneNode - PbServiceFacade.ensureChapBookPageNode's shape
	/// (private, created only at render), replicated here so no render is needed. Reuses a node the import
	/// may already have linked; asserts the link by re-reading the scene before returning.
	private BaseRecord ensurePageNode(Fixture fx, BaseRecord wf, String sceneOid) throws Exception {
		BaseRecord scene = readScene(fx, sceneOid);
		long linked = fkId(scene, OlioFieldNames.FIELD_PB_SCENE_NODE);
		if (linked > 0L) {
			BaseRecord existing = PbGraphUtil.readNodeById(testUser, linked, fx.orgId);
			assertNotNull("the page's linked node #" + linked + " must read back", existing);
			return existing;
		}
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int idx = (sceneIndex != null ? sceneIndex.intValue() : 0);
		String handle = PbPipelineUtil.landscapeHandle(sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(testUser, wf, handle);
		if (node == null) {
			node = PbGraphUtil.addNode(testUser, wf, handle, PbNodeTypeEnumType.LANDSCAPE, PbBookUtil.workflowGroupPath(fx.slug), idx);
			assertNotNull("page node " + handle, node);
			assertTrue("page node scope patch", PbPipelineUtil.patchNodeScope(testUser, wf, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx));
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
		patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, node);
		assertNotNull("scene.sceneNode patch", IOSystem.getActiveContext().getAccessPoint().update(testUser, patch));
		BaseRecord back = readScene(fx, sceneOid);
		assertEquals("the page links its node", idOf(node), fkId(back, OlioFieldNames.FIELD_PB_SCENE_NODE));
		return readNode(fx, oidOf(node));
	}

	private BaseRecord readNode(Fixture fx, String nodeOid) {
		BaseRecord n = PbGraphUtil.readNode(testUser, nodeOid, fx.orgId);
		assertNotNull("node " + nodeOid, n);
		return n;
	}

	private BaseRecord setStatus(Fixture fx, BaseRecord node, PbNodeStatusEnumType to) {
		assertTrue("persistStatus " + to, PbGraphUtil.persistStatus(testUser, node, to));
		BaseRecord fresh = readNode(fx, oidOf(node));
		assertEquals("precondition: node is " + to, to.toString(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return fresh;
	}

	private Map<String, Object> point(Fixture fx, String sceneOid, String locOid, String varOid) {
		return point(testUser, fx, sceneOid, locOid, varOid);
	}

	private Map<String, Object> point(BaseRecord as, Fixture fx, String sceneOid, String locOid, String varOid) {
		Map<String, Object> out = PbServiceFacade.setSceneLocation(as, fx.bookOid, sceneOid, locOid, varOid);
		assertNotNull("setSceneLocation returns a map", out);
		return out;
	}

	/// Fresh re-read (readScene is uncached) of both pointer FKs.
	private BaseRecord assertPointer(Fixture fx, String sceneOid, String expectLoc, String expectVar) {
		BaseRecord back = readScene(fx, sceneOid);
		assertEquals("persisted location pointer", expectLoc, fkOid(back, FieldNames.FIELD_LOCATION));
		assertEquals("persisted variant pointer", expectVar, fkOid(back, OlioFieldNames.FIELD_PB_LOCATION_VARIANT));
		return back;
	}

	private static PictureBookException expect(String what, int expected, String mustContain, Runnable r) {
		try {
			r.run();
		}
		catch (PictureBookException e) {
			assertEquals(what + " status (" + e.getMessage() + ")", expected, e.getStatus());
			if (mustContain != null) {
				assertTrue(what + " message must mention '" + mustContain + "': " + e.getMessage(), e.getMessage().contains(mustContain));
			}
			return e;
		}
		fail(what + " must throw PictureBookException " + expected);
		return null;
	}

	// ───────────────────────────── cases ─────────────────────────────

	@Test
	public void case01PointLocationOnly() throws Exception {
		Fixture fx = createFixture("c01");
		try {
			String sceneOid = fx.sceneOids.get(0);
			BaseRecord loc = newLocation(fx, "The Drowned Anchor", TAVERN);
			assertPointer(fx, sceneOid, null, null);

			Map<String, Object> out = point(fx, sceneOid, oidOf(loc), null);
			assertEquals(Boolean.TRUE, out.get("updated"));
			assertEquals(sceneOid, out.get("sceneObjectId"));
			assertEquals(oidOf(loc), out.get("locationObjectId"));
			assertEquals("The Drowned Anchor", out.get("locationName"));
			assertTrue("the variant keys are present", out.containsKey("variantObjectId") && out.containsKey("variantName"));
			assertNull("no variant named: " + out, out.get("variantObjectId"));
			assertNull("no variant name: " + out, out.get("variantName"));

			BaseRecord back = assertPointer(fx, sceneOid, oidOf(loc), null);
			assertEquals("scene.location.id is the location's id", idOf(loc), fkId(back, FieldNames.FIELD_LOCATION));
			assertNull("scene.locationVariant stays null", back.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT));
		}
		finally {
			deleteBook(fx);
		}
	}

	@Test
	public void case02PointLocationAndVariant() throws Exception {
		Fixture fx = createFixture("c02");
		try {
			String sceneOid = fx.sceneOids.get(0);
			BaseRecord loc = newLocation(fx, "The Drowned Anchor", TAVERN);
			BaseRecord var = newVariant(fx, oidOf(loc), "The Drowned Anchor - dusk, rain", "dusk", "rain");

			Map<String, Object> out = point(fx, sceneOid, oidOf(loc), oidOf(var));
			assertEquals(Boolean.TRUE, out.get("updated"));
			assertEquals(oidOf(loc), out.get("locationObjectId"));
			assertEquals("The Drowned Anchor", out.get("locationName"));
			assertEquals(oidOf(var), out.get("variantObjectId"));
			assertEquals("The Drowned Anchor - dusk, rain", out.get("variantName"));

			BaseRecord back = assertPointer(fx, sceneOid, oidOf(loc), oidOf(var));
			assertEquals(idOf(loc), fkId(back, FieldNames.FIELD_LOCATION));
			assertEquals(idOf(var), fkId(back, OlioFieldNames.FIELD_PB_LOCATION_VARIANT));
		}
		finally {
			deleteBook(fx);
		}
	}

	@Test
	public void case03VariantOnlyResolvesItsParent() throws Exception {
		Fixture fx = createFixture("c03");
		try {
			String sceneOid = fx.sceneOids.get(0);
			BaseRecord loc = newLocation(fx, "The Drowned Anchor", TAVERN);
			BaseRecord var = newVariant(fx, oidOf(loc), "The Drowned Anchor - night", "night", null);

			Map<String, Object> out = point(fx, sceneOid, null, oidOf(var));
			assertEquals(Boolean.TRUE, out.get("updated"));
			assertEquals("the parent is resolved from the variant", oidOf(loc), out.get("locationObjectId"));
			assertEquals("The Drowned Anchor", out.get("locationName"));
			assertEquals(oidOf(var), out.get("variantObjectId"));
			assertEquals("The Drowned Anchor - night", out.get("variantName"));

			BaseRecord back = assertPointer(fx, sceneOid, oidOf(loc), oidOf(var));
			assertEquals(idOf(loc), fkId(back, FieldNames.FIELD_LOCATION));
			assertEquals(idOf(var), fkId(back, OlioFieldNames.FIELD_PB_LOCATION_VARIANT));

			/// a blank location id with the variant is the same as none
			Map<String, Object> blank = point(fx, sceneOid, "   ", oidOf(var));
			assertEquals(oidOf(loc), blank.get("locationObjectId"));
			assertEquals(oidOf(var), blank.get("variantObjectId"));
			assertPointer(fx, sceneOid, oidOf(loc), oidOf(var));
		}
		finally {
			deleteBook(fx);
		}
	}

	@Test
	public void case04ClearAfterPointing() throws Exception {
		Fixture fx = createFixture("c04");
		try {
			String sceneOid = fx.sceneOids.get(0);
			BaseRecord loc = newLocation(fx, "The Drowned Anchor", TAVERN);
			BaseRecord var = newVariant(fx, oidOf(loc), "The Drowned Anchor - dusk", "dusk", null);
			point(fx, sceneOid, oidOf(loc), oidOf(var));
			assertPointer(fx, sceneOid, oidOf(loc), oidOf(var));

			Map<String, Object> out = point(fx, sceneOid, null, null);
			assertEquals(Boolean.TRUE, out.get("updated"));
			assertEquals(sceneOid, out.get("sceneObjectId"));
			assertTrue("the pointer keys are present when cleared", out.containsKey("locationObjectId") && out.containsKey("variantObjectId"));
			assertNull("cleared location: " + out, out.get("locationObjectId"));
			assertNull("cleared location name: " + out, out.get("locationName"));
			assertNull("cleared variant: " + out, out.get("variantObjectId"));
			assertNull("cleared variant name: " + out, out.get("variantName"));

			BaseRecord back = assertPointer(fx, sceneOid, null, null);
			assertNull("scene.location is null after the clear", back.get(FieldNames.FIELD_LOCATION));
			assertNull("scene.locationVariant is null after the clear", back.get(OlioFieldNames.FIELD_PB_LOCATION_VARIANT));

			/// blank ids clear exactly like nulls
			point(fx, sceneOid, oidOf(loc), null);
			assertPointer(fx, sceneOid, oidOf(loc), null);
			Map<String, Object> blank = point(fx, sceneOid, "", " ");
			assertNull(blank.get("locationObjectId"));
			assertPointer(fx, sceneOid, null, null);
		}
		finally {
			deleteBook(fx);
		}
	}

	@Test
	public void case05VariantMustBelongToTheLocation() throws Exception {
		Fixture fx = createFixture("c05");
		try {
			String sceneOid = fx.sceneOids.get(0);
			BaseRecord locA = newLocation(fx, "The Drowned Anchor", TAVERN);
			BaseRecord locB = newLocation(fx, "The Rope Walk", "A long shed where rope is laid");
			assertNotEquals("two distinct locations", oidOf(locA), oidOf(locB));
			BaseRecord varB = newVariant(fx, oidOf(locB), "The Rope Walk - night", "night", null);
			BaseRecord scene = readScene(fx, sceneOid);

			/// util: a variant without its location
			expect("util variant without location", 400, "A location variant cannot be set without its location",
				() -> PbBookUtil.setSceneLocation(testUser, scene, null, mustReadVariant(fx, oidOf(varB))));
			/// util: a variant of ANOTHER location of the same book
			expect("util variant of another location", 400, "is not a variant of",
				() -> PbBookUtil.setSceneLocation(testUser, scene, mustReadLocation(fx, oidOf(locA)), mustReadVariant(fx, oidOf(varB))));
			/// facade: location A named with a variant of B
			expect("facade variant under wrong location", 404, "Location variant not found under that location",
				() -> point(fx, sceneOid, oidOf(locA), oidOf(varB)));
			/// facade: a variant that does not exist
			expect("facade unknown variant", 404, "Location variant not found in this book's world",
				() -> point(fx, sceneOid, oidOf(locA), NIL_UUID));

			/// nothing was written by any refusal
			assertPointer(fx, sceneOid, null, null);

			/// and the matching pair still works after the refusals
			point(fx, sceneOid, oidOf(locB), oidOf(varB));
			assertPointer(fx, sceneOid, oidOf(locB), oidOf(varB));
			/// a refusal on an already-pointed scene leaves the pointer alone
			expect("facade variant under wrong location, pointed scene", 404, "Location variant not found under that location",
				() -> point(fx, sceneOid, oidOf(locA), oidOf(varB)));
			assertPointer(fx, sceneOid, oidOf(locB), oidOf(varB));
		}
		finally {
			deleteBook(fx);
		}
	}

	@Test
	public void case06LocationOfAnotherWorldIs404() throws Exception {
		Fixture fxA = createFixture("c06a");
		Fixture fxB = null;
		try {
			fxB = createFixture("c06b");
			String sceneOid = fxA.sceneOids.get(0);
			BaseRecord locB = newLocation(fxB, "The Salt Chapel", CHAPEL);
			BaseRecord varB = newVariant(fxB, oidOf(locB), "The Salt Chapel - night", "night", null);
			BaseRecord bookA = readBook(fxA);
			long worldA = fkId(bookA, OlioFieldNames.FIELD_PB_WORLD);
			long worldB = fkId(readBook(fxB), OlioFieldNames.FIELD_PB_WORLD);
			assertTrue("both books carry a world", worldA > 0L && worldB > 0L);
			assertNotEquals("each ChapBook has its own world", worldA, worldB);
			assertFalse("precondition: the location is not of book A's world", PbLocationUtil.sameWorld(bookA, mustReadLocation(fxB, oidOf(locB))));
			assertNotNull("the owner can read the other world's location (the writer would let the FK through)",
				PbLocationUtil.readLocation(testUser, oidOf(locB), fxA.orgId));

			expect("location of another world", 404, "Location not found in this book's world",
				() -> point(fxA, sceneOid, oidOf(locB), null));
			expect("variant of another world", 404, "Location variant not found in this book's world",
				() -> point(fxA, sceneOid, null, oidOf(varB)));
			expect("location + variant of another world", 404, "Location variant not found in this book's world",
				() -> point(fxA, sceneOid, oidOf(locB), oidOf(varB)));
			expect("unknown location", 404, "Location not found in this book's world",
				() -> point(fxA, sceneOid, NIL_UUID, null));

			assertPointer(fxA, sceneOid, null, null);
		}
		finally {
			deleteBook(fxA);
			deleteBook(fxB);
		}
	}

	@Test
	public void case07SceneMustBelongToTheBook() throws Exception {
		Fixture fxA = createFixture("c07a");
		Fixture fxB = null;
		try {
			fxB = createFixture("c07b");
			String foreignScene = fxB.sceneOids.get(0);
			BaseRecord locA = newLocation(fxA, "The Drowned Anchor", TAVERN);
			assertNotNull("the owner can read the other book's page", PbBookUtil.readScene(testUser, foreignScene, fxA.orgId));

			expect("scene of another book", 404, "Scene " + foreignScene + " not found in this book",
				() -> point(fxA, foreignScene, oidOf(locA), null));
			expect("unknown scene", 404, "Scene " + NIL_UUID + " not found in this book",
				() -> point(fxA, NIL_UUID, oidOf(locA), null));
			expect("blank scene id", 400, "A scene objectId is required",
				() -> point(fxA, "  ", oidOf(locA), null));
			expect("null scene id", 400, "A scene objectId is required",
				() -> point(fxA, null, oidOf(locA), null));

			/// the other book's page was not touched
			assertPointer(fxB, foreignScene, null, null);
		}
		finally {
			deleteBook(fxA);
			deleteBook(fxB);
		}
	}

	@Test
	public void case08StrangerCannotSeeTheBook() throws Exception {
		Fixture fx = createFixture("c08");
		try {
			BaseRecord stranger = IOSystem.getActiveContext().getFactory()
				.getCreateUser(ctx.getAdminUser(), "chapbookStranger", ctx.getOrganizationId());
			assertNotNull("stranger must be created", stranger);
			assertNotEquals("the stranger is not the owner", idOf(testUser), idOf(stranger));
			String sceneOid = fx.sceneOids.get(0);
			BaseRecord loc = newLocation(fx, "The Drowned Anchor", TAVERN);
			assertNull("precondition: the stranger cannot read the book", PbBookUtil.readBook(stranger, fx.bookOid, fx.orgId));

			expect("stranger point", 404, "Book not found", () -> point(stranger, fx, sceneOid, oidOf(loc), null));
			expect("stranger clear", 404, "Book not found", () -> point(stranger, fx, sceneOid, null, null));
			expect("no principal", 401, "No authenticated principal", () -> point(null, fx, sceneOid, oidOf(loc), null));

			assertPointer(fx, sceneOid, null, null);
		}
		finally {
			deleteBook(fx);
		}
	}

	@Test
	public void case09PointingMarksTheSceneNodeStaleEvenWhenPinned() throws Exception {
		Fixture fx = createFixture("c09");
		try {
			String sceneOid = fx.sceneOids.get(0);
			BaseRecord loc1 = newLocation(fx, "The Drowned Anchor", TAVERN);
			BaseRecord loc2 = newLocation(fx, "The Rope Walk", "A long shed where rope is laid");
			BaseRecord wf = workflow(fx);
			BaseRecord locNode = locationNode(fx, wf, oidOf(loc1));
			String locNodeStatus = status(locNode, OlioFieldNames.FIELD_PB_NODE_STATUS);
			assertEquals("a fresh LOCATION node is PENDING", "PENDING", locNodeStatus);
			BaseRecord node = ensurePageNode(fx, wf, sceneOid);
			assertEquals("the page node is the scene's node", idOf(node), fkId(readScene(fx, sceneOid), OlioFieldNames.FIELD_PB_SCENE_NODE));
			assertNotEquals("the page node is not the location node", idOf(node), idOf(locNode));
			assertFalse("a fresh page node is not pinned", Boolean.TRUE.equals(node.get(OlioFieldNames.FIELD_PB_PINNED)));

			/// precondition: DONE
			node = setStatus(fx, node, PbNodeStatusEnumType.DONE);

			Map<String, Object> out = point(fx, sceneOid, oidOf(loc1), null);
			assertEquals(Boolean.TRUE, out.get("updated"));
			assertEquals(Boolean.TRUE, out.get("nodeStale"));
			assertEquals("STALE", upper(out.get("nodeStatus")));
			assertEquals(Boolean.FALSE, out.get("pinned"));
			BaseRecord after = readNode(fx, oidOf(node));
			assertEquals("the page node is STALE on disk", "STALE", status(after, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertPointer(fx, sceneOid, oidOf(loc1), null);
			assertEquals("the LOCATION node is not touched by scene pointing", locNodeStatus, status(readNode(fx, oidOf(locNode)), OlioFieldNames.FIELD_PB_NODE_STATUS));

			/// pointing an already-STALE node keeps it STALE and still reports nodeStale:true
			Map<String, Object> again = point(fx, sceneOid, oidOf(loc2), null);
			assertEquals(Boolean.TRUE, again.get("nodeStale"));
			assertEquals("STALE", upper(again.get("nodeStatus")));
			assertPointer(fx, sceneOid, oidOf(loc2), null);

			/// precondition: READY, then pinned
			node = setStatus(fx, node, PbNodeStatusEnumType.READY);
			assertTrue("setPinned", PbGraphUtil.setPinned(testUser, node, true));
			node = readNode(fx, oidOf(node));
			assertEquals(Boolean.TRUE, node.get(OlioFieldNames.FIELD_PB_PINNED));
			assertEquals("READY", status(node, OlioFieldNames.FIELD_PB_NODE_STATUS));

			Map<String, Object> pinned = point(fx, sceneOid, oidOf(loc1), null);
			assertEquals(Boolean.TRUE, pinned.get("updated"));
			assertEquals("a pin is not a conflict: still marked", Boolean.TRUE, pinned.get("nodeStale"));
			assertEquals("STALE", upper(pinned.get("nodeStatus")));
			assertEquals("the pin is echoed", Boolean.TRUE, pinned.get("pinned"));
			BaseRecord afterPinned = readNode(fx, oidOf(node));
			assertEquals("STALE", status(afterPinned, OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertEquals("still pinned", Boolean.TRUE, afterPinned.get(OlioFieldNames.FIELD_PB_PINNED));
			assertPointer(fx, sceneOid, oidOf(loc1), null);

			/// clearing is a pointer edit too: DONE -> STALE
			node = setStatus(fx, node, PbNodeStatusEnumType.DONE);
			Map<String, Object> cleared = point(fx, sceneOid, null, null);
			assertEquals(Boolean.TRUE, cleared.get("nodeStale"));
			assertEquals("STALE", upper(cleared.get("nodeStatus")));
			assertEquals(Boolean.TRUE, cleared.get("pinned"));
			assertPointer(fx, sceneOid, null, null);
			assertEquals("STALE", status(readNode(fx, oidOf(node)), OlioFieldNames.FIELD_PB_NODE_STATUS));
			assertEquals("the LOCATION node is still untouched", locNodeStatus, status(readNode(fx, oidOf(locNode)), OlioFieldNames.FIELD_PB_NODE_STATUS));
		}
		finally {
			deleteBook(fx);
		}
	}

	@Test
	public void case10NoNodeIsNotStale() throws Exception {
		Fixture fx = createFixture("c10");
		try {
			String sceneOid = fx.sceneOids.get(1);
			BaseRecord loc = newLocation(fx, "The Drowned Anchor", TAVERN);
			BaseRecord var = newVariant(fx, oidOf(loc), "The Drowned Anchor - dawn", "dawn", null);
			BaseRecord scene = readScene(fx, sceneOid);
			assertEquals("precondition: an unrendered ChapBook page has no node", 0L, fkId(scene, OlioFieldNames.FIELD_PB_SCENE_NODE));

			Map<String, Object> out = point(fx, sceneOid, oidOf(loc), oidOf(var));
			assertEquals(Boolean.TRUE, out.get("updated"));
			assertEquals(Boolean.FALSE, out.get("nodeStale"));
			assertTrue("nodeStatus key is present", out.containsKey("nodeStatus"));
			assertNull("no node, no status: " + out, out.get("nodeStatus"));
			assertEquals(Boolean.FALSE, out.get("pinned"));
			assertEquals(oidOf(loc), out.get("locationObjectId"));
			assertEquals(oidOf(var), out.get("variantObjectId"));

			BaseRecord back = assertPointer(fx, sceneOid, oidOf(loc), oidOf(var));
			assertEquals("still no node", 0L, fkId(back, OlioFieldNames.FIELD_PB_SCENE_NODE));

			/// the util reports the same on a direct call
			assertFalse("util: no node is not stale", PbBookUtil.setSceneLocation(testUser, back, mustReadLocation(fx, oidOf(loc)), null));
			assertPointer(fx, sceneOid, oidOf(loc), null);
		}
		finally {
			deleteBook(fx);
		}
	}

	@Test
	public void case11BookPageViewCarriesThePointer() throws Exception {
		Fixture fx = createFixture("c11");
		try {
			String page0 = fx.sceneOids.get(0);
			String page1 = fx.sceneOids.get(1);
			BaseRecord loc = newLocation(fx, "The Drowned Anchor", TAVERN);
			BaseRecord var = newVariant(fx, oidOf(loc), "The Drowned Anchor - dusk, rain", "dusk", "rain");
			point(fx, page0, oidOf(loc), oidOf(var));
			assertPointer(fx, page0, oidOf(loc), oidOf(var));
			assertPointer(fx, page1, null, null);

			List<Map<String, Object>> pages = PbServiceFacade.bookPageView(testUser, fx.bookOid);
			assertNotNull("bookPageView", pages);
			assertEquals("two pages", 2, pages.size());
			Map<String, Object> p0 = null;
			Map<String, Object> p1 = null;
			for (Map<String, Object> p : pages) {
				if (page0.equals(p.get("objectId"))) {
					p0 = p;
				}
				else if (page1.equals(p.get("objectId"))) {
					p1 = p;
				}
			}
			assertNotNull("page 0 is in the view: " + pages, p0);
			assertNotNull("page 1 is in the view: " + pages, p1);
			assertEquals("ordered by sceneIndex", page0, pages.get(0).get("objectId"));

			assertEquals(oidOf(loc), p0.get("locationObjectId"));
			assertEquals("The Drowned Anchor", p0.get("locationName"));
			assertEquals(oidOf(var), p0.get("locationVariantObjectId"));
			assertEquals("The Drowned Anchor - dusk, rain", p0.get("locationVariantName"));

			for (String key : new String[] { "locationObjectId", "locationName", "locationVariantObjectId", "locationVariantName" }) {
				assertTrue("page 1 carries '" + key + "': " + p1, p1.containsKey(key));
				assertNull("page 1 '" + key + "' is null: " + p1, p1.get(key));
			}

			/// clearing page 0 empties its four keys too
			point(fx, page0, null, null);
			List<Map<String, Object>> after = PbServiceFacade.bookPageView(testUser, fx.bookOid);
			assertEquals(2, after.size());
			Map<String, Object> c0 = after.get(0);
			assertEquals(page0, c0.get("objectId"));
			for (String key : new String[] { "locationObjectId", "locationName", "locationVariantObjectId", "locationVariantName" }) {
				assertTrue("cleared page 0 carries '" + key + "': " + c0, c0.containsKey(key));
				assertNull("cleared page 0 '" + key + "' is null: " + c0, c0.get(key));
			}
		}
		finally {
			deleteBook(fx);
		}
	}
}
