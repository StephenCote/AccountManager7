package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbOlioContextUtil;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.AttributeUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Issue 4: PictureBook (PB2) character images were persisted under the DEFAULT grid world's gallery
 * ({@code /Olio/Universes/My Grid Universe/Worlds/My Grid World/Gallery}) instead of the book's own
 * universe/world gallery.
 *
 * <p><b>Root cause (traced, not guessed).</b> The reimage REST flow
 * ({@code OlioService.reimageWithConfig}) resolves the DEFAULT grid {@code OlioContext} (My Grid World)
 * whenever the request carries no {@code universeObjectId}/{@code worldObjectId} (or they fail to
 * resolve and it degrades). It then calls {@code SDUtil.generateSDImages(octx, ...)}, and
 * {@code SDUtil.resolveCharacterImagePath} used {@code octx.getWorld().gallery.path} — i.e. WHATEVER
 * world the passed context pointed at, which in that flow is My Grid World, not the book's world.
 *
 * <p><b>Fix under test (Stephen's 2nd option — an attribute pointing at the alt save location, read in
 * SDUtil).</b> {@code PictureBookUtil.createFromScenes} now stamps {@code SDUtil.ATTR_IMAGE_GALLERY_PATH}
 * on each PB2 charPerson with the BOOK world's gallery path (captured where the book's OlioContext is in
 * hand). {@code SDUtil.resolveCharacterImagePath} prefers that attribute over the passed context's world
 * gallery. Because that gallery tree is olio-owned and RUCD-granted to the olio role
 * ({@code getCreateBookContext} sets {@code scanNestedWorldGroups(true)}), the olio user — the principal
 * the images are written as — CAN makePath/create there, so this does not reintroduce the KI-34/KI-61
 * "Not authorized to create auth.group" regression.
 *
 * <p><b>Why a second book context stands in for "My Grid World".</b> The mechanism the bug turns on is
 * "the passed context points at the WRONG world." Passing a DIFFERENT book's context (book B) while
 * resolving book A's character exercises exactly that override, and additionally asserts the resolved
 * path contains neither book B's world nor the literal default world name
 * ({@link OlioContextUtil#DEFAULT_WORLD_NAME} = "My Grid World"). This avoids triggering the multi-minute
 * default-grid seed in an org that only has the Books universe seeded, while proving the same behavior.
 *
 * <p>Uses NO LLM and NO SD: it creates bare charPerson records, stamps/reads the attribute, and resolves
 * the save path. It exercises the exact production load shape (the reimage flow loads the charPerson with
 * {@code planMost(true)}, which deliberately EXCLUDES the referenced {@code attributes} field —
 * {@code RecordUtil.getMostRequestFields}), so the attribute is proven to be recovered via SDUtil's
 * targeted re-read, not merely read from a hand-populated in-memory list. Runs against live Postgres,
 * non-admin actor, no schema reset. Reuses the already-seeded Books universe org.
 */
public class TestPictureBookImageGalleryPath extends BaseTest {

	/** Reuse the already-seeded Books universe org (avoids the multi-minute universe seed). */
	private static final String ORG_A = "/Development/World Building";

	@Before
	public void resetSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private OrganizationContext org() {
		return getTestOrganization(ORG_A);
	}

	private long orgId(BaseRecord user) {
		return ((Number) user.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	/** A non-admin, ordinary user. */
	private BaseRecord user(String name) {
		OrganizationContext o = org();
		BaseRecord u = ioContext.getFactory().getCreateUser(o.getAdminUser(), name, o.getOrganizationId());
		assertNotNull("Failed to resolve non-admin test user " + name, u);
		return u;
	}

	/**
	 * Resolve a book world's gallery group path, re-querying with a fresh uncached projection if the
	 * in-memory world was loaded shallow (same fallback shape the production write path uses).
	 */
	private String resolveGalleryPath(OlioContext ctx, long orgId) {
		BaseRecord world = ctx.getWorld();
		assertNotNull("Book world must exist (getCreateBookContext built it)", world);
		String gp = world.get("gallery.path");
		if (gp == null || gp.isBlank()) {
			String worldObjId = world.get(FieldNames.FIELD_OBJECT_ID);
			Query wq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_OBJECT_ID, worldObjId);
			wq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			wq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "gallery.path" });
			wq.setCache(false);
			BaseRecord full = IOSystem.getActiveContext().getSearch().findRecord(wq);
			assertNotNull("World must re-resolve for gallery.path projection", full);
			gp = full.get("gallery.path");
		}
		assertNotNull("World must expose a gallery.path", gp);
		assertFalse("gallery.path must not be blank", gp.isBlank());
		return gp;
	}

	/** Resolve a book world's population group path (where PB2 characters actually live). */
	private String resolvePopulationPath(OlioContext ctx, long orgId) {
		BaseRecord world = ctx.getWorld();
		assertNotNull("Book world must exist", world);
		String pp = world.get("population.path");
		if (pp == null || pp.isBlank()) {
			String worldObjId = world.get(FieldNames.FIELD_OBJECT_ID);
			Query wq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_OBJECT_ID, worldObjId);
			wq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
			wq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, "population.path" });
			wq.setCache(false);
			BaseRecord full = IOSystem.getActiveContext().getSearch().findRecord(wq);
			assertNotNull("World must re-resolve for population.path projection", full);
			pp = full.get("population.path");
		}
		assertNotNull("World must expose a population.path", pp);
		assertFalse("population.path must not be blank", pp.isBlank());
		return pp;
	}

	/** Create a bare charPerson in the given group path, as the acting (non-admin) user. */
	private BaseRecord createCharacter(BaseRecord user, String name, String groupPath, long orgId) throws Exception {
		BaseRecord cp = RecordFactory.newInstance(OlioModelNames.MODEL_CHAR_PERSON);
		IOSystem.getActiveContext().getRecordUtil().applyNameGroupOwnership(user, cp, name, groupPath, orgId);
		BaseRecord created = IOSystem.getActiveContext().getAccessPoint().create(user, cp);
		assertNotNull("charPerson must be created in " + groupPath, created);
		return created;
	}

	/**
	 * Stamp the image-gallery attribute the same way the production write path does — the direct-persist
	 * pattern for referenced attribute storage, NOT a parent PATCH.
	 */
	private void stampGalleryAttribute(BaseRecord charPerson, String galleryPath) throws Exception {
		IOSystem.getActiveContext().getRecordUtil().createRecord(
			AttributeUtil.addAttribute(charPerson, SDUtil.ATTR_IMAGE_GALLERY_PATH, galleryPath));
	}

	/** Re-load a charPerson exactly as the reimage flow does: planMost(true), which excludes attributes. */
	private BaseRecord loadCharacterPlanMost(BaseRecord user, String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.planMost(true);
		q.setCache(false);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		assertNotNull("charPerson must reload via planMost", rec);
		return rec;
	}

	/** Re-load a charPerson WITH its attributes projected (the create-time in-memory read shape). */
	private BaseRecord loadCharacterWithAttributes(BaseRecord user, String objectId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_CHAR_PERSON, FieldNames.FIELD_OBJECT_ID, objectId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, FieldNames.FIELD_ATTRIBUTES });
		q.setCache(false);
		BaseRecord rec = IOSystem.getActiveContext().getAccessPoint().find(user, q);
		assertNotNull("charPerson must reload with attributes", rec);
		return rec;
	}

	@Test
	public void pb2_characterImages_saveUnderBookWorldGallery_notWrongWorld() throws Exception {
		logger.info("Issue 4 gate START: character images must resolve to the BOOK world gallery even when "
			+ "resolveCharacterImagePath is handed the WRONG OlioContext (the production reimage bug)");

		long ts = System.currentTimeMillis();
		BaseRecord user = user("issue4Img" + ts);
		long orgId = orgId(user);
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		// ── Two PB2 books → two distinct universe/world galleries. No LLM/SD involved in book creation. ──
		String slugA = "issue4-imgpath-a-" + ts;
		String slugB = "issue4-imgpath-b-" + ts;
		BaseRecord bookA = PbBookUtil.createBook(user, dataPath, slugA, "Issue4 Image Path A " + ts);
		BaseRecord bookB = PbBookUtil.createBook(user, dataPath, slugB, "Issue4 Image Path B " + ts);
		assertNotNull("Book A must be created", bookA);
		assertNotNull("Book B must be created", bookB);

		OlioContext ctxA = PbOlioContextUtil.getCreateBookContext(user, dataPath, slugA);
		OlioContext ctxB = PbOlioContextUtil.getCreateBookContext(user, dataPath, slugB);
		assertNotNull("Book A context", ctxA);
		assertNotNull("Book B context", ctxB);

		String galleryA = resolveGalleryPath(ctxA, orgId);
		String galleryB = resolveGalleryPath(ctxB, orgId);
		String populationA = resolvePopulationPath(ctxA, orgId);
		String populationB = resolvePopulationPath(ctxB, orgId);
		logger.info("galleryA={} galleryB={}", galleryA, galleryB);
		assertNotEquals("The two books must have distinct galleries", galleryA, galleryB);
		assertFalse("Sanity: book A gallery must not be the default grid world",
			galleryA.contains(OlioContextUtil.DEFAULT_WORLD_NAME));

		// ── charA (with attribute) and a same-named charB in a DIFFERENT book (with its own attribute). ──
		String sharedName = "Kestrel Vane";
		BaseRecord charA = createCharacter(user, sharedName, populationA, orgId);
		stampGalleryAttribute(charA, galleryA);
		String charAObjId = charA.get(FieldNames.FIELD_OBJECT_ID);

		BaseRecord charB = createCharacter(user, sharedName, populationB, orgId);
		stampGalleryAttribute(charB, galleryB);
		String charBObjId = charB.get(FieldNames.FIELD_OBJECT_ID);

		// A legacy (non-PB / un-stamped) character to prove the fallback path is unchanged.
		BaseRecord legacy = createCharacter(user, "Rook Ashford", populationA, orgId);
		String legacyObjId = legacy.get(FieldNames.FIELD_OBJECT_ID);

		// ── CORE: resolve charA's image path while handing SDUtil the WRONG context (book B). The stamped
		//    attribute must win — the path must be under book A's gallery, and neither book B's world nor
		//    the default "My Grid World". This is the production reimage scenario reproduced. charA is
		//    loaded via planMost(true), which does NOT project attributes, so this proves SDUtil recovers
		//    the attribute via its targeted re-read (not from a hand-populated list). ──────────────────
		BaseRecord charAPlanMost = loadCharacterPlanMost(user, charAObjId);
		String resolvedA = SDUtil.resolveCharacterImagePath(ctxB, charAPlanMost);
		logger.info("resolvedA (via WRONG ctxB) = {}", resolvedA);
		assertNotNull("resolvedA must not be null", resolvedA);
		assertTrue("Character image path must be under book A's world gallery, got: " + resolvedA,
			resolvedA.startsWith(galleryA + "/Characters/"));
		assertFalse("Character image path must NOT be under the passed (wrong) book B world gallery: " + resolvedA,
			resolvedA.startsWith(galleryB + "/Characters/"));
		assertFalse("Character image path must NOT be under the default '" + OlioContextUtil.DEFAULT_WORLD_NAME
			+ "' gallery: " + resolvedA, resolvedA.contains(OlioContextUtil.DEFAULT_WORLD_NAME));

		// ── KI-34/KI-61 authorization: the olio user (the principal images are written as) must be able to
		//    makePath/create the resolved group without "Not authorized to create auth.group". ──────────
		BaseRecord olioUser = ctxB.getOlioUser();
		assertNotNull("olio user must resolve", olioUser);
		BaseRecord madeGroup = IOSystem.getActiveContext().getPathUtil()
			.makePath(olioUser, ModelNames.MODEL_GROUP, resolvedA, GroupEnumType.DATA.toString(), orgId);
		assertNotNull("KI-34: olio principal MUST be authorized to makePath the resolved book-world gallery "
			+ "path (" + resolvedA + ") — a null here is the reintroduced 'Not authorized' regression", madeGroup);

		// ── In-memory create-time path: when attributes ARE projected, the same attribute is honored. ──
		BaseRecord charAWithAttrs = loadCharacterWithAttributes(user, charAObjId);
		String resolvedAInMem = SDUtil.resolveCharacterImagePath(ctxB, charAWithAttrs);
		logger.info("resolvedA (in-memory attributes) = {}", resolvedAInMem);
		assertTrue("In-memory attribute read must also resolve under book A's gallery, got: " + resolvedAInMem,
			resolvedAInMem.startsWith(galleryA + "/Characters/"));

		// ── KI-34 collision-avoidance: same NAME, different books ⇒ different storage roots. ────────────
		BaseRecord charBPlanMost = loadCharacterPlanMost(user, charBObjId);
		String resolvedB = SDUtil.resolveCharacterImagePath(ctxA, charBPlanMost);
		logger.info("resolvedB (via ctxA) = {}", resolvedB);
		assertTrue("charB (same name, book B) must resolve under book B's gallery, got: " + resolvedB,
			resolvedB.startsWith(galleryB + "/Characters/"));
		assertNotEquals("KI-34: two same-named characters in different books must NOT share an image path",
			resolvedA, resolvedB);

		// ── Regression guard: a character WITHOUT the attribute falls back to the PASSED context's world
		//    gallery exactly as before (behavior unchanged for legacy/non-book characters). ─────────────
		BaseRecord legacyPlanMost = loadCharacterPlanMost(user, legacyObjId);
		String resolvedLegacy = SDUtil.resolveCharacterImagePath(ctxB, legacyPlanMost);
		logger.info("resolvedLegacy (no attribute, via ctxB) = {}", resolvedLegacy);
		assertTrue("Un-stamped character must fall back to the passed context's world gallery (unchanged), got: "
			+ resolvedLegacy, resolvedLegacy.startsWith(galleryB + "/Characters/"));

		logger.info("Issue 4 gate PASS: resolvedA={} olioMakePath=OK resolvedB={} legacyFallback={}",
			resolvedA, resolvedB, resolvedLegacy);
	}
}
