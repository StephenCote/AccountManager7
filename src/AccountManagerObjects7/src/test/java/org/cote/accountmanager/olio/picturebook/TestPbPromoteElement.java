package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.ComposeResult;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil.PromoteResult;
import org.cote.accountmanager.olio.picturebook.PictureBookUtil.DeleteResult;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbElementTypeEnumType;
import org.cote.accountmanager.schema.type.PbImportanceEnumType;
import org.cote.accountmanager.schema.type.PbLocationKindEnumType;
import org.cote.accountmanager.schema.type.PbNodeStatusEnumType;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 4, P4-6: {@link PbComposeUtil#promoteElement} - a scene element becomes an {@code olio.item} held by
 * the scene location's point-of-interest store (design §7.2 rule 4). The pure half ({@code itemText},
 * {@code itemNameOf}, {@code elementText} of an un-promoted element) needs no book. The live half writes throwaway
 * books in {@code /Development/World Building} as {@code testUser1} and proves on the database: the first promote
 * creates a {@code type=prop} item as the olio principal in the world Items group, enrols it in the location's
 * store, links the element to it and marks the scene's DONE node STALE; the second promote of the same element
 * reuses the item and writes no row; an element whose description names an item already in the store links that
 * item instead of creating one; a composition of another book or an element of another composition is 404; a
 * location of another book's world is 404 (the P3-4 same-world rule); a read-only collaborator is 403 with nothing
 * written; a composition with no location is 409 with nothing written; blank ids are 400.
 * <p>
 * {@code composeMigrated} writes no element rows (an LLM-free compose has nothing to stage), so every live case
 * hand-builds one unlinked {@code olio.pb.sceneElement} in the composition's group exactly as
 * {@code PbComposeUtil.createElement} does - that is the row a user promotes from the board.
 */
public class TestPbPromoteElement extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String TEST_USER = "testUser1";
	private static final String SECOND_USER = "testUser2";
	private static final String TAVERN_NAME = "The Gilded Tankard";
	private static final String TAVERN_DESC = "A low-beamed tavern by the quay";
	private static final String CUP_DESC = "a dented pewter cup";

	@FunctionalInterface
	private interface Call {
		void run() throws Exception;
	}

	@Before
	public void setupEach() throws Exception {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		ioContext.getAccessPoint().setPermitBulkContainerApproval(false);
	}

	// ─────────────────────────────── pure: itemText / itemNameOf / elementText ───────────────────────────────

	@Test
	public void itemTextNameOnlyIsLowerCased() {
		assertEquals("cup", PbComposeUtil.itemText("Cup", null, null, null));
		assertEquals("pewter cup", PbComposeUtil.itemText("  Pewter   Cup ", null, new ArrayList<>(), new ArrayList<>()));
		assertEquals("a description equal to the name is not repeated", "pewter cup",
			PbComposeUtil.itemText("Pewter Cup", "pewter cup", null, null));
	}

	@Test
	public void itemTextJoinsColourMaterialsNameAndDescription() {
		assertEquals("grey pewter and tin cup",
			PbComposeUtil.itemText("Cup", null, Arrays.asList("Grey"), Arrays.asList("pewter", "tin")));
		assertEquals("grey pewter and tin cup, dented at the rim",
			PbComposeUtil.itemText("Cup", "Dented at the rim", Arrays.asList("Grey"), Arrays.asList("pewter", "tin")));
		assertEquals("parentheticals are stripped and whitespace collapsed", "grey pewter cup, dented at the rim",
			PbComposeUtil.itemText("Cup (prop)", "Dented  (badly)  at the rim", Arrays.asList("Grey"), Arrays.asList("pewter")));
	}

	@Test
	public void itemTextAddsAccentsForFurtherColours() {
		assertEquals("grey pewter cup with red and gold accents",
			PbComposeUtil.itemText("Cup", null, Arrays.asList("Grey", "Red", "Gold"), Arrays.asList("pewter")));
		assertEquals("a null colour slot is skipped, not rendered", "grey cup with gold accents",
			PbComposeUtil.itemText("Cup", null, Arrays.asList("Grey", null, "Gold"), null));
		assertEquals("the literal 'null' colour is skipped too", "grey cup",
			PbComposeUtil.itemText("Cup", null, Arrays.asList("Grey", "null", " "), null));
	}

	@Test
	public void itemTextNeverYieldsTheWordNull() {
		assertEquals("", PbComposeUtil.itemText("null", "a cup", Arrays.asList("grey"), Arrays.asList("pewter")));
		assertEquals("", PbComposeUtil.itemText("   ", "a cup", null, null));
		assertEquals("", PbComposeUtil.itemText(null, "a cup", null, null));
		String withNullDesc = PbComposeUtil.itemText("Cup", null, null, null);
		assertEquals("cup", withNullDesc);
		assertFalse("a null description never renders as 'null'", withNullDesc.contains("null"));
		String withLiteralNullDesc = PbComposeUtil.itemText("Cup", "null", Arrays.asList("grey"), null);
		assertEquals("grey cup", withLiteralNullDesc);
		assertFalse(withLiteralNullDesc.contains("null"));
	}

	@Test
	public void itemNameOfDropsTheArticleAndLowerCases() {
		assertEquals("dented pewter cup", PbComposeUtil.itemNameOf("A Dented Pewter Cup"));
		assertEquals("oak table", PbComposeUtil.itemNameOf("an  Oak   Table"));
		assertEquals("scarred corner table", PbComposeUtil.itemNameOf("the scarred corner table"));
		assertEquals("loose coins", PbComposeUtil.itemNameOf("some loose coins"));
		assertEquals("only the leading article goes", "cup of the sea", PbComposeUtil.itemNameOf("Cup of the Sea"));
		assertEquals("a parenthetical is stripped", "lantern", PbComposeUtil.itemNameOf("a lantern (unlit)"));
	}

	@Test
	public void itemNameOfIsNullForNothingMeaningfulAndCappedAtSixtyFour() {
		assertNull(PbComposeUtil.itemNameOf("null"));
		assertNull(PbComposeUtil.itemNameOf(null));
		assertNull(PbComposeUtil.itemNameOf("   "));
		assertNull(PbComposeUtil.itemNameOf("n/a"));
		StringBuilder sb = new StringBuilder("a ");
		for(int i = 0; i < 20; i++) {
			sb.append("word ");
		}
		String name = PbComposeUtil.itemNameOf(sb.toString());
		assertNotNull(name);
		assertTrue("capped at " + PbComposeUtil.ITEM_NAME_MAX + " but was " + name.length(), name.length() <= PbComposeUtil.ITEM_NAME_MAX);
		assertFalse("the cap trims trailing whitespace", name.endsWith(" "));
		assertTrue(name.startsWith("word word"));
	}

	@Test
	public void elementTextOfAnUnpromotedElementIsItsDescriptionOrEmpty() throws Exception {
		BaseRecord el = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE_ELEMENT);
		el.set(FieldNames.FIELD_DESCRIPTION, "A scarred  corner table (oak)");
		assertEquals("a scarred corner table", PbComposeUtil.elementText(null, el));
		el.set(FieldNames.FIELD_DESCRIPTION, "null");
		assertEquals("", PbComposeUtil.elementText(null, el));
		el.set(FieldNames.FIELD_DESCRIPTION, null);
		assertEquals("", PbComposeUtil.elementText(null, el));
		assertEquals("", PbComposeUtil.elementText(null, null));
	}

	// ─────────────────────────────── live ───────────────────────────────

	/// First promote: a type=prop item is created as the olio principal in the world Items group, enrolled in the
	/// location's store, linked from the element; the scene's DONE page node is marked STALE (staled=1).
	@Test
	public void firstPromoteCreatesThePropAndStalesTheNode() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "first");
			Stage st = stage(u, bk, 1);
			BaseRecord el = newElement(u, st.comp, CUP_DESC, PbElementTypeEnumType.PROP);
			assertEquals("precondition: the element is not linked", 0L, fkId(readElement(u, oidOf(el), orgId), OlioFieldNames.FIELD_ITEM));

			BaseRecord wf = workflow(u, bk);
			BaseRecord node = ensurePageNode(u, bk, wf, oidOf(st.scene));
			setStatus(u, node, PbNodeStatusEnumType.DONE, orgId);
			int before = countItems(u, st.bctx);
			assertEquals("precondition: the store holds nothing", 0, storeItemIds(st.store).size());

			long t0 = System.currentTimeMillis();
			PromoteResult r = PbComposeUtil.promoteElement(u, bk, oidOf(st.comp), oidOf(el));
			log("first promote " + (System.currentTimeMillis() - t0) + "ms " + describe(r));

			assertTrue("promoted", r.promoted);
			assertTrue("created", r.created);
			assertFalse("not reused", r.reused);
			assertEquals("staled", 1, r.staled);
			assertTrue("no warnings: " + r.warnings, r.warnings.isEmpty());
			assertNotNull("item", r.item);
			assertEquals("item name from the description", "dented pewter cup", r.item.get(FieldNames.FIELD_NAME));
			assertNotNull("elementText", r.elementText);
			assertTrue("elementText carries the item name: " + r.elementText, r.elementText.contains("dented pewter cup"));
			assertFalse("elementText never says null: " + r.elementText, r.elementText.contains("null"));

			/// the row: olio-owned, type=prop, in the world Items group
			BaseRecord item = readItem(u, idOf(r.item), orgId);
			assertNotNull("item re-read by the user", item);
			BaseRecord olio = olioUser(orgId);
			assertEquals("the item is owned by the olio principal", idOf(olio), ((Number) item.get(FieldNames.FIELD_OWNER_ID)).longValue());
			assertNotEquals("and not by the caller", idOf(u), ((Number) item.get(FieldNames.FIELD_OWNER_ID)).longValue());
			assertEquals("item.type", PbComposeUtil.ITEM_TYPE_PROP, item.get(FieldNames.FIELD_TYPE));
			assertEquals("the item sits in the world Items group", idOf(st.bctx.getGroup(OlioFieldNames.FIELD_ITEMS)),
				((Number) item.get(FieldNames.FIELD_GROUP_ID)).longValue());
			assertEquals("exactly one Items row was written", before + 1, countItems(u, st.bctx));

			/// the store membership and the element link, both re-read uncached
			assertTrue("the store holds the item", storeItemIds(st.store).contains(Long.valueOf(idOf(r.item))));
			BaseRecord fresh = readElement(u, oidOf(el), orgId);
			assertEquals("the element links the item", idOf(r.item), fkId(fresh, OlioFieldNames.FIELD_ITEM));
			assertEquals("elementText from the stored link", r.elementText, PbComposeUtil.elementText(u, fresh));

			/// the node
			assertNodeStatus(u, node, PbNodeStatusEnumType.STALE, orgId);
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// Second promote of the same element: idempotent - same item objectId, reused, nothing created, no new row.
	/// This scene has no page node, so both calls honestly report staled=0.
	@Test
	public void secondPromoteReusesTheItemAndWritesNoRow() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "again");
			Stage st = stage(u, bk, 1);
			BaseRecord el = newElement(u, st.comp, "a brass lantern", PbElementTypeEnumType.PROP);
			int before = countItems(u, st.bctx);

			PromoteResult r1 = PbComposeUtil.promoteElement(u, bk, oidOf(st.comp), oidOf(el));
			log("promote #1 " + describe(r1));
			assertTrue(r1.promoted);
			assertTrue(r1.created);
			assertFalse(r1.reused);
			assertEquals("no node on this scene, so nothing is staled", 0, r1.staled);
			assertNotNull(r1.item);
			int afterFirst = countItems(u, st.bctx);
			assertEquals("one row after the first promote", before + 1, afterFirst);

			PromoteResult r2 = PbComposeUtil.promoteElement(u, bk, oidOf(st.comp), oidOf(el));
			log("promote #2 " + describe(r2));
			assertTrue("promoted", r2.promoted);
			assertFalse("nothing created the second time", r2.created);
			assertTrue("reused", r2.reused);
			assertEquals("no node on this scene", 0, r2.staled);
			assertNotNull(r2.item);
			assertEquals("the same item", oidOf(r1.item), oidOf(r2.item));
			assertEquals("elementText is stable", r1.elementText, r2.elementText);
			assertEquals("no second row", afterFirst, countItems(u, st.bctx));
			assertEquals("the element still links the one item", idOf(r1.item), fkId(readElement(u, oidOf(el), orgId), OlioFieldNames.FIELD_ITEM));
			assertEquals("the store holds it once", 1, storeItemIds(st.store).stream().filter(id -> id.longValue() == idOf(r1.item)).count());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// The store already holds "cup" and the description names it: the element links the store's cup; no row is
	/// written and nothing is enrolled.
	@Test
	public void promoteReusesTheStoreItemTheDescriptionNames() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "store");
			Stage st = stage(u, bk, 1);
			BaseRecord cup = newItem(u, st.bctx, "cup", "a plain cup");
			assertTrue("cup enrolled in the store", ioContext.getMemberUtil().member(u, st.store, OlioFieldNames.FIELD_ITEMS, cup, null, true));
			assertTrue("precondition: the store holds the cup", storeItemIds(st.store).contains(Long.valueOf(idOf(cup))));
			BaseRecord el = newElement(u, st.comp, CUP_DESC, PbElementTypeEnumType.PROP);
			int before = countItems(u, st.bctx);

			PromoteResult r = PbComposeUtil.promoteElement(u, bk, oidOf(st.comp), oidOf(el));
			log("store reuse " + describe(r));
			assertTrue("promoted", r.promoted);
			assertTrue("reused", r.reused);
			assertFalse("not created", r.created);
			assertNotNull(r.item);
			assertEquals("the store's cup, not a new prop", oidOf(cup), oidOf(r.item));
			assertEquals("no Items row was written", before, countItems(u, st.bctx));
			assertEquals("the element links the cup", idOf(cup), fkId(readElement(u, oidOf(el), orgId), OlioFieldNames.FIELD_ITEM));
			assertEquals("the cup is in the store exactly once", 1, storeItemIds(st.store).stream().filter(id -> id.longValue() == idOf(cup)).count());
			assertTrue("elementText names the cup: " + r.elementText, r.elementText.contains("cup"));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// A composition of another book is "not found in this book"; an element of another composition is "not found
	/// in this composition" - neither writes anything.
	@Test
	public void compositionOfAnotherBookOrElementOfAnotherCompositionIs404() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bkA = null;
		BaseRecord bkB = null;
		try {
			bkA = newBook(u, "crossa");
			bkB = newBook(u, "crossb");
			Stage a = stage(u, bkA, 1);
			Stage b = stage(u, bkB, 1);
			BaseRecord elA = newElement(u, a.comp, "a chipped jug", PbElementTypeEnumType.PROP);
			BaseRecord elB = newElement(u, b.comp, "a chipped jug", PbElementTypeEnumType.PROP);
			int beforeA = countItems(u, a.bctx);
			int beforeB = countItems(u, b.bctx);

			final BaseRecord bookA = bkA;
			refused("composition of book B against book A", 404, "not found in this book",
				() -> PbComposeUtil.promoteElement(u, bookA, oidOf(b.comp), oidOf(elB)));
			refused("element of composition B against composition A", 404, "not found in this composition",
				() -> PbComposeUtil.promoteElement(u, bookA, oidOf(a.comp), oidOf(elB)));
			refused("unknown composition", 404, "not found in this book",
				() -> PbComposeUtil.promoteElement(u, bookA, UUID.randomUUID().toString(), oidOf(elA)));
			refused("unknown element", 404, "not found in this composition",
				() -> PbComposeUtil.promoteElement(u, bookA, oidOf(a.comp), UUID.randomUUID().toString()));

			assertEquals("nothing written in A", beforeA, countItems(u, a.bctx));
			assertEquals("nothing written in B", beforeB, countItems(u, b.bctx));
			assertEquals("element A still unlinked", 0L, fkId(readElement(u, oidOf(elA), orgId), OlioFieldNames.FIELD_ITEM));
			assertEquals("element B still unlinked", 0L, fkId(readElement(u, oidOf(elB), orgId), OlioFieldNames.FIELD_ITEM));
		}
		finally {
			deleteBook(u, bkA);
			deleteBook(u, bkB);
		}
	}

	/// The P3-4 same-world rule: a scene of book A whose location belongs to book B's world is 404 "Location not
	/// found in this book's world" (before the store is even looked for), nothing written.
	@Test
	public void locationOfAnotherWorldIs404() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bkA = null;
		BaseRecord bkB = null;
		try {
			bkA = newBook(u, "worlda");
			bkB = newBook(u, "worldb");
			BaseRecord locB = PbLocationUtil.getCreateLocation(u, bkB, "The Drowned Anchor", "A quayside inn", PbLocationKindEnumType.INTERIOR, null);
			assertNotNull("location in book B's world", locB);
			BookContext bctxA = bookContext(u, bkA);
			BaseRecord sceneA = addSceneAt(u, bkA, 1, "Arrival", "a rain-dark harbour street", "walking to the inn", "wary", "Scene 1", locB);
			ComposeResult cr = PbComposeUtil.composeMigrated(u, bkA, sceneA, false);
			assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
			BaseRecord compA = PbComposeUtil.readComposition(u, oidOf(cr.composition), orgId);
			assertNotNull(compA);
			assertEquals("the composition carries book B's location", idOf(locB), fkId(compA, FieldNames.FIELD_LOCATION));
			BaseRecord el = newElement(u, compA, "a salt-stained rope", PbElementTypeEnumType.PROP);
			int before = countItems(u, bctxA);

			final BaseRecord bookA = bkA;
			refused("location of another world", 404, "Location not found in this book's world",
				() -> PbComposeUtil.promoteElement(u, bookA, oidOf(compA), oidOf(el)));

			assertEquals("nothing written", before, countItems(u, bctxA));
			assertEquals("element still unlinked", 0L, fkId(readElement(u, oidOf(el), orgId), OlioFieldNames.FIELD_ITEM));
		}
		finally {
			deleteBook(u, bkA);
			deleteBook(u, bkB);
		}
	}

	/// A collaborator with Read only on the book group (and Read on the world record so the book itself reads) can
	/// see the book but not update it: 403 from PbBookUtil.requireBookUpdate, no item row created.
	@Test
	public void readOnlyUserIs403AndWritesNothing() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		OrganizationContext org = getTestOrganization(ORG_A);
		assertNotNull(org);
		BaseRecord reader = ioContext.getFactory().getCreateUser(org.getAdminUser(), SECOND_USER, org.getOrganizationId());
		assertNotNull("reader " + SECOND_USER, reader);
		assertNotEquals("reader is a different user from the owner", idOf(u), idOf(reader));
		BaseRecord bk = null;
		try {
			bk = newBook(u, "reader");
			String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
			Stage st = stage(u, bk, 1);
			BaseRecord el = newElement(u, st.comp, "a wax-sealed letter", PbElementTypeEnumType.PROP);
			int before = countItems(u, st.bctx);

			/// Fixture grants only (the admin of the book's own organization applies them): Read on the book group
			/// and Read on the world record, so readBook succeeds and canUpdate does not.
			BaseRecord olio = olioUser(orgId);
			BaseRecord bookGroup = ioContext.getPathUtil().findPath(olio, ModelNames.MODEL_GROUP, PbBookUtil.bookGroupPath(slug),
				GroupEnumType.DATA.toString(), orgId);
			assertNotNull("book group", bookGroup);
			ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), reader, new BaseRecord[] { bookGroup },
				new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
			BaseRecord world = st.bctx.getWorld();
			assertNotNull("world record", world);
			ioContext.getAuthorizationUtil().setEntitlement(org.getAdminUser(), reader, new BaseRecord[] { world },
				new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString() });

			BaseRecord asReader = PbBookUtil.readBook(reader, oidOf(bk), orgId);
			assertNotNull("precondition: the reader can READ the book (else the refusal would be a 404, not the 403 under test)", asReader);
			PolicyResponseType canUpd = ioContext.getAuthorizationUtil().canUpdate(reader, reader, asReader);
			assertFalse("precondition: the reader must NOT be permitted to update the book",
				canUpd != null && canUpd.getType() == PolicyResponseEnumType.PERMIT);

			final BaseRecord book = bk;
			refused("read-only promote", 403, "Not permitted to promote an element of book " + slug,
				() -> PbComposeUtil.promoteElement(reader, book, oidOf(st.comp), oidOf(el)));

			assertEquals("no item row was written", before, countItems(u, st.bctx));
			assertEquals("the element is still unlinked", 0L, fkId(readElement(u, oidOf(el), orgId), OlioFieldNames.FIELD_ITEM));
			assertEquals("the store is untouched", 0, storeItemIds(st.store).size());
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// A scene with no location composes to a composition with no location: 409 "has no location to hold the
	/// item", nothing written.
	@Test
	public void compositionWithoutLocationIs409AndWritesNothing() throws Exception {
		BaseRecord u = user();
		long orgId = orgOf(u);
		BaseRecord bk = null;
		try {
			bk = newBook(u, "noloc");
			BookContext bctx = bookContext(u, bk);
			BaseRecord scene = addScene(u, bk, 1, "Nowhere", "an empty road at noon", "standing still", "calm", "Scene 1", null, null);
			assertEquals("precondition: the scene has no location", 0L, fkId(scene, FieldNames.FIELD_LOCATION));
			ComposeResult cr = PbComposeUtil.composeMigrated(u, bk, scene, false);
			assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
			BaseRecord comp = PbComposeUtil.readComposition(u, oidOf(cr.composition), orgId);
			assertNotNull(comp);
			assertEquals("precondition: the composition has no location", 0L, fkId(comp, FieldNames.FIELD_LOCATION));
			BaseRecord el = newElement(u, comp, "a milestone", PbElementTypeEnumType.STRUCTURE);
			int before = countItems(u, bctx);

			final BaseRecord book = bk;
			refused("promote without a location", 409, "has no location to hold the item",
				() -> PbComposeUtil.promoteElement(u, book, oidOf(comp), oidOf(el)));

			assertEquals("nothing written", before, countItems(u, bctx));
			assertEquals("element still unlinked", 0L, fkId(readElement(u, oidOf(el), orgId), OlioFieldNames.FIELD_ITEM));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	/// Blank or null ids are 400 after the book authorization and before anything is read.
	@Test
	public void blankIdsAre400() throws Exception {
		BaseRecord u = user();
		BaseRecord bk = null;
		try {
			bk = newBook(u, "blank");
			final BaseRecord book = bk;
			String some = UUID.randomUUID().toString();
			refused("null composition id", 400, "A composition objectId is required", () -> PbComposeUtil.promoteElement(u, book, null, some));
			refused("blank composition id", 400, "A composition objectId is required", () -> PbComposeUtil.promoteElement(u, book, "  ", some));
			refused("null element id", 400, "An element objectId is required", () -> PbComposeUtil.promoteElement(u, book, some, null));
			refused("blank element id", 400, "An element objectId is required", () -> PbComposeUtil.promoteElement(u, book, some, ""));
			refused("no user is 401 before the id checks", 401, "No user", () -> PbComposeUtil.promoteElement(null, book, "", ""));
		}
		finally {
			deleteBook(u, bk);
		}
	}

	// ─────────────────────────────── stage: location + store + scene + composition ───────────────────────────────

	private static final class Stage {
		BookContext bctx;
		BaseRecord location;
		BaseRecord store;
		BaseRecord scene;
		BaseRecord comp;
	}

	/// A tavern location (its map-free POI and store are created with it, the world being a book world), a scene
	/// at it, and the scene's MIGRATED composition re-read uncached so it carries the location.
	private Stage stage(BaseRecord u, BaseRecord bk, int index) throws Exception {
		long orgId = orgOf(u);
		Stage st = new Stage();
		st.bctx = bookContext(u, bk);
		st.location = PbLocationUtil.getCreateLocation(u, bk, TAVERN_NAME, TAVERN_DESC, PbLocationKindEnumType.INTERIOR,
			Arrays.asList("the Tankard", "the taproom"));
		assertNotNull("location", st.location);
		st.store = storeOf(u, st.location, orgId);
		st.scene = addSceneAt(u, bk, index, "Arrival", "a rain-dark harbour street at dusk", "walking toward the tavern door", "wary",
			"Scene " + index + " of the P4-6 promote fixture", st.location);
		ComposeResult cr = PbComposeUtil.composeMigrated(u, bk, st.scene, false);
		assertTrue("MIGRATED composition written: " + cr.failureReason, cr.isWritten());
		st.comp = PbComposeUtil.readComposition(u, oidOf(cr.composition), orgId);
		assertNotNull("composition re-read", st.comp);
		assertEquals("the composition carries the scene's location", idOf(st.location), fkId(st.comp, FieldNames.FIELD_LOCATION));
		assertEquals("the composition belongs to the book", idOf(bk), fkId(st.comp, OlioFieldNames.FIELD_PB_BOOK));
		assertTrue("composeMigrated stages no element rows", PbComposeUtil.listElements(u, st.comp).isEmpty());
		return st;
	}

	/// An unlinked olio.pb.sceneElement in the composition's group, created as the user exactly as
	/// PbComposeUtil.createElement builds one (applyOwnership + the composition's groupId + explicit name).
	private BaseRecord newElement(BaseRecord u, BaseRecord comp, String description, PbElementTypeEnumType type) throws Exception {
		Long gid = comp.get(FieldNames.FIELD_GROUP_ID);
		assertNotNull("composition groupId", gid);
		String name = PbComposeUtil.ELEMENT_NAME_PREFIX + oidOf(comp) + "." + shortId();
		BaseRecord row = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE_ELEMENT);
		ioContext.getRecordUtil().applyOwnership(u, row, orgOf(u));
		row.set(FieldNames.FIELD_GROUP_ID, gid);
		row.set(FieldNames.FIELD_NAME, name);
		row.set(OlioFieldNames.FIELD_PB_COMPOSITION, comp);
		row.set(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, type.toString());
		row.set(FieldNames.FIELD_DESCRIPTION, description);
		row.set(OlioFieldNames.FIELD_PB_IMPORTANCE, PbImportanceEnumType.MEDIUM.toString());
		BaseRecord created = ioContext.getAccessPoint().create(u, row);
		assertNotNull("create element '" + description + "'", created);
		BaseRecord fresh = readElement(u, oidOf(created), orgOf(u));
		assertNotNull("element re-read", fresh);
		assertEquals("the element belongs to the composition", idOf(comp), fkId(fresh, OlioFieldNames.FIELD_PB_COMPOSITION));
		assertEquals(description, fresh.get(FieldNames.FIELD_DESCRIPTION));
		return fresh;
	}

	private BaseRecord readElement(BaseRecord u, String oid, long orgId) {
		BaseRecord el = PbComposeUtil.readElement(u, oid, orgId);
		assertNotNull("element " + oid, el);
		return el;
	}

	/// The item with the fields the assertions need (ownerId, type, groupId), read as the user uncached.
	private BaseRecord readItem(BaseRecord u, long itemId, long orgId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_ITEM, FieldNames.FIELD_ID, itemId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID, FieldNames.FIELD_TYPE, FieldNames.FIELD_DESCRIPTION });
		q.setCache(false);
		return ioContext.getAccessPoint().find(u, q);
	}

	/// Rows of the world Items group, counted as the user with an explicit groupId + organizationId (never unconstrained).
	private int countItems(BaseRecord u, BookContext bctx) {
		BaseRecord itemsGroup = bctx.getGroup(OlioFieldNames.FIELD_ITEMS);
		assertNotNull("Items group", itemsGroup);
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_ITEM, FieldNames.FIELD_GROUP_ID, idOf(itemsGroup));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgOf(u));
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID });
		q.setCache(false);
		return ioContext.getAccessPoint().count(u, q);
	}

	/// The participant ids of store.items (the same participation read PbComposeUtil.storeItems makes).
	private List<Long> storeItemIds(BaseRecord store) throws Exception {
		List<Long> ids = new ArrayList<>();
		List<BaseRecord> parts = ioContext.getMemberUtil().findMembers(store, OlioFieldNames.FIELD_ITEMS, OlioModelNames.MODEL_ITEM, 0L);
		if(parts != null) {
			for(BaseRecord p : parts) {
				Object pid = p.get(FieldNames.FIELD_PARTICIPANT_ID);
				if(pid instanceof Number) {
					ids.add(Long.valueOf(((Number) pid).longValue()));
				}
			}
		}
		return ids;
	}

	private void refused(String what, int status, String contains, Call c) throws Exception {
		try {
			c.run();
		}
		catch(PictureBookException e) {
			log(what + ": " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": status (" + e.getMessage() + ")", status, e.getStatus());
			assertNotNull(what + ": message", e.getMessage());
			assertTrue(what + ": message must contain '" + contains + "' but was: " + e.getMessage(), e.getMessage().contains(contains));
			return;
		}
		fail(what + ": expected PictureBookException " + status + " but nothing was thrown");
	}

	// ─────────────────────────────── graph / nodes (TestPbWardrobe's shape) ───────────────────────────────

	private BaseRecord workflow(BaseRecord u, BaseRecord bk) {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord wf = PbGraphUtil.getCreateWorkflow(u, bk, PbBookUtil.workflowGroupPath(slug));
		assertNotNull("workflow", wf);
		return wf;
	}

	private BaseRecord ensurePageNode(BaseRecord u, BaseRecord bk, BaseRecord wf, String sceneOid) throws Exception {
		long orgId = orgOf(u);
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = readScene(u, sceneOid, orgId);
		long linked = fkId(scene, OlioFieldNames.FIELD_PB_SCENE_NODE);
		if(linked > 0L) {
			BaseRecord existing = PbGraphUtil.readNodeById(u, linked, orgId);
			assertNotNull("linked page node", existing);
			return existing;
		}
		Integer sceneIndex = scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
		int idx = (sceneIndex != null ? sceneIndex.intValue() : 0);
		String handle = PbPipelineUtil.landscapeHandle(sceneOid);
		BaseRecord node = PbPipelineUtil.findNodeByHandle(u, wf, handle);
		if(node == null) {
			node = PbGraphUtil.addNode(u, wf, handle, PbNodeTypeEnumType.LANDSCAPE, PbBookUtil.workflowGroupPath(slug), idx);
			assertNotNull("page node " + handle, node);
			assertTrue("page node scope patch", PbPipelineUtil.patchNodeScope(u, wf, node, PbPipelineUtil.SCOPE_SCENE, sceneOid, idx));
		}
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_NODE);
		patch.set(OlioFieldNames.FIELD_PB_SCENE_NODE, node);
		assertNotNull("scene.sceneNode patch", IOSystem.getActiveContext().getAccessPoint().update(u, patch));
		BaseRecord back = readScene(u, sceneOid, orgId);
		assertEquals("the page links its node", idOf(node), fkId(back, OlioFieldNames.FIELD_PB_SCENE_NODE));
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgId);
		assertNotNull("page node re-read", fresh);
		return fresh;
	}

	private BaseRecord setStatus(BaseRecord u, BaseRecord node, PbNodeStatusEnumType to, long orgId) {
		assertTrue("persistStatus " + to, PbGraphUtil.persistStatus(u, node, to));
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgId);
		assertNotNull("node re-read", fresh);
		assertEquals("precondition: node is " + to, to.toString(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
		return fresh;
	}

	private void assertNodeStatus(BaseRecord u, BaseRecord node, PbNodeStatusEnumType expected, long orgId) {
		BaseRecord fresh = PbGraphUtil.readNode(u, oidOf(node), orgId);
		assertNotNull("node re-read", fresh);
		assertEquals("node " + fresh.get(FieldNames.FIELD_NAME) + " status", expected.toString(), status(fresh, OlioFieldNames.FIELD_PB_NODE_STATUS));
	}

	private static String status(BaseRecord rec, String field) {
		Object v = rec.get(field);
		return (v == null ? null : String.valueOf(v).toUpperCase());
	}

	private BaseRecord readScene(BaseRecord u, String oid, long orgId) {
		BaseRecord s = PbBookUtil.readScene(u, oid, orgId);
		assertNotNull("scene " + oid, s);
		return s;
	}

	// ─────────────────────────────── book / scene / location (TestPbComposeFallback's shape) ───────────────────────────────

	private BookContext bookContext(BaseRecord u, BaseRecord bk) {
		BookContext bctx = PbBookUtil.openBookContext(u, bk);
		assertNotNull("book context", bctx);
		assertNotNull("book world", bctx.getWorld());
		return bctx;
	}

	/// A scene with the fields the compose step reads; nulls are left unset. The scene is re-read as the user.
	private BaseRecord addScene(BaseRecord u, BaseRecord bk, int index, String title, String setting, String action, String mood,
			String description, String sdPrompt, Boolean promptLocked) throws Exception {
		String slug = bk.get(OlioFieldNames.FIELD_PB_SLUG);
		BaseRecord scene = PbBookUtil.createScene(u, bk, index, title, PbBookUtil.bookGroupPath(slug));
		assertNotNull("createScene " + index, scene);
		List<String> fields = new ArrayList<>();
		Map<String, Object> values = new LinkedHashMap<>();
		put(fields, values, OlioFieldNames.FIELD_PB_SETTING, setting);
		put(fields, values, OlioFieldNames.FIELD_PB_ACTION, action);
		put(fields, values, OlioFieldNames.FIELD_PB_MOOD, mood);
		put(fields, values, FieldNames.FIELD_DESCRIPTION, description);
		put(fields, values, OlioFieldNames.FIELD_CB_SD_PROMPT, sdPrompt);
		put(fields, values, OlioFieldNames.FIELD_PB_PROMPT_LOCKED, promptLocked);
		if(!fields.isEmpty()) {
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, fields.toArray(new String[0]));
			for(Map.Entry<String, Object> e : values.entrySet()) {
				patch.set(e.getKey(), e.getValue());
			}
			assertNotNull("patch scene " + index, ioContext.getAccessPoint().update(u, patch));
		}
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("scene " + index + " readable by " + u.get(FieldNames.FIELD_NAME), fresh);
		return fresh;
	}

	private static void put(List<String> fields, Map<String, Object> values, String field, Object value) {
		if(value != null) {
			fields.add(field);
			values.put(field, value);
		}
	}

	/// addScene plus a location FK, re-read as the user and checked to carry it (the item link depends on it).
	private BaseRecord addSceneAt(BaseRecord u, BaseRecord bk, int index, String title, String setting, String action, String mood,
			String description, BaseRecord location) throws Exception {
		BaseRecord scene = addScene(u, bk, index, title, setting, action, mood, description, null, null);
		assertNotNull("location for scene " + index, location);
		BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE, FieldNames.FIELD_LOCATION);
		patch.set(FieldNames.FIELD_LOCATION, location);
		assertNotNull("patch scene " + index + " location", ioContext.getAccessPoint().update(u, patch));
		BaseRecord fresh = PbBookUtil.readScene(u, oidOf(scene), orgOf(u));
		assertNotNull("scene " + index + " readable by " + u.get(FieldNames.FIELD_NAME), fresh);
		assertEquals("scene " + index + " carries its location", idOf(location), fkId(fresh, FieldNames.FIELD_LOCATION));
		return fresh;
	}

	/// The store behind the location's POI, read as the user (promoteElement reads it the same way).
	private BaseRecord storeOf(BaseRecord u, BaseRecord loc, long orgId) {
		BaseRecord fresh = PbLocationUtil.readLocation(u, oidOf(loc), orgId);
		assertNotNull("readLocation", fresh);
		BaseRecord poiRef = fresh.get(OlioFieldNames.FIELD_PB_POI);
		assertNotNull("the location carries a POI", poiRef);
		Query pq = QueryUtil.createQuery(OlioModelNames.MODEL_POI, FieldNames.FIELD_ID, idOf(poiRef));
		pq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_GROUP_ID,
			FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_STORE });
		pq.setCache(false);
		BaseRecord poi = ioContext.getAccessPoint().find(u, pq);
		assertNotNull("the POI is readable by the user", poi);
		BaseRecord store = poi.get(FieldNames.FIELD_STORE);
		assertNotNull("the POI carries a store", store);
		assertTrue("the store has an id", idOf(store) > 0L);
		return store;
	}

	/// An olio.item in the book world's Items group, created as the user (a catalogue-style row, not a prop).
	private BaseRecord newItem(BaseRecord u, BookContext bc, String name, String description) throws Exception {
		String itemsPath = bc.getGroupPath(OlioFieldNames.FIELD_ITEMS);
		assertNotNull("Items group path", itemsPath);
		BaseRecord item = RecordFactory.newInstance(OlioModelNames.MODEL_ITEM);
		ioContext.getRecordUtil().applyNameGroupOwnership(u, item, name, itemsPath, orgOf(u));
		item.set(FieldNames.FIELD_DESCRIPTION, description);
		List<String> materials = item.get(OlioFieldNames.FIELD_MATERIALS);
		materials.add("pewter");
		BaseRecord created = ioContext.getAccessPoint().create(u, item);
		assertNotNull("create item " + name, created);
		item.set(FieldNames.FIELD_ID, created.get(FieldNames.FIELD_ID));
		item.set(FieldNames.FIELD_OBJECT_ID, created.get(FieldNames.FIELD_OBJECT_ID));
		return item;
	}

	private BaseRecord newBook(BaseRecord u, String tag) {
		String slug = "p46" + tag + shortId();
		BaseRecord bk = PbBookUtil.createBook(u, dataPath(), slug, "P4-6 " + tag + " " + slug);
		assertNotNull("createBook " + slug, bk);
		log("book " + slug + " " + oidOf(bk));
		return bk;
	}

	private void deleteBook(BaseRecord u, BaseRecord bk) {
		if(bk == null) {
			return;
		}
		try {
			DeleteResult res = PbDeleteUtil.deleteBookComplete(u, oidOf(bk));
			log("cleanup " + bk.get(FieldNames.FIELD_NAME) + ": deleted=" + res.deleted + " reason=" + res.reason);
		}
		catch(Exception e) {
			logger.error("cleanup " + bk.get(FieldNames.FIELD_NAME) + " failed: " + e.getMessage());
		}
	}

	// ─────────────────────────────── small helpers ───────────────────────────────

	private static String describe(PromoteResult r) {
		return "promoted=" + r.promoted + " created=" + r.created + " reused=" + r.reused + " staled=" + r.staled
			+ " item=" + (r.item != null ? r.item.get(FieldNames.FIELD_NAME) + " " + r.item.get(FieldNames.FIELD_OBJECT_ID) : null)
			+ " text=" + r.elementText + " warnings=" + r.warnings;
	}

	private void log(String msg) {
		logger.info("[p46] " + msg);
	}

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

	private BaseRecord olioUser(long orgId) {
		BaseRecord olio = ioContext.getFactory().findUser(OlioContext.OLIO_USER_NAME, orgId);
		assertNotNull("olio principal in organization " + orgId, olio);
		return olio;
	}

	static long orgOf(BaseRecord u) {
		return ((Number) u.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	static long idOf(BaseRecord rec) {
		return ((Number) rec.get(FieldNames.FIELD_ID)).longValue();
	}

	static String oidOf(BaseRecord rec) {
		assertNotNull("record", rec);
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	static long fkId(BaseRecord rec, String field) {
		Object o = (rec != null && rec.hasField(field) ? rec.get(field) : null);
		return (o instanceof BaseRecord ? idOf((BaseRecord) o) : 0L);
	}
}
