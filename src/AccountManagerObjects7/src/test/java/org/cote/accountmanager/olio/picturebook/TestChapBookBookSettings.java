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
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.SdConfigUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 0, slice C, P0-10a (G-26 for ChapBook): the book-level SD config a ChapBook
 * stores and renders from.
 * <p>
 * Persistence shape is PB1's - exactly one {@code olio.sd.config} row named
 * {@link PbBookUtil#BOOK_SD_CONFIG_NAME} in the book group, referenced from {@code olio.pb.book.sdConfig}.
 * Everything here runs against the live {@code am7db}; nothing is mocked. The one render in this class
 * goes through the package-private {@code renderChapBookScene(user, sceneOid, SDUtil, ...)} seam with a
 * capturing {@link SDUtil} subclass that returns no images, so it never touches a GPU.
 */
public class TestChapBookBookSettings extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";

	private static final String POEM_TEXT =
		"Outside, all is pristine,\n" +
		"From cobalt skies of charcoal unity\n" +
		"Descending upon snow canvassed green\n" +
		"To silver veins of icy sheens,\n" +
		"Born of spells and sorcery.\n" +
		"\n" +
		"Inside hearts and hearths and homes,\n" +
		"Ochre embers and ebon cinders,\n" +
		"Faded life stirred by motherly crones,\n" +
		"Dry damp clothes and warm cold bones\n" +
		"And illuminate the age-old spellbound tomes.";

	private BaseRecord testUser;
	private long orgId;

	@Before
	public void setUpChapBookSettings() {
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookTestUser", ctx.getOrganizationId());
		assertNotNull("chapbookTestUser must be created", testUser);
		orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
	}

	/**
	 * A fresh ChapBook has no book config; a sparse set creates the single {@code book-sdConfig} row in
	 * the book group, links it, and reads back; a second sparse set patches the SAME row leaving omitted
	 * fields untouched; per-render fields are never stored; the client's own identity/location fields
	 * cannot redirect the write.
	 */
	@Test
	public void setBookSdConfigRoundTrip() throws Exception {
		Fixture fx = createFixture("rt");
		assertNull("A fresh ChapBook must have no book sdConfig", ChapBookUtil.getBookSdConfig(testUser, fx.bookOid));
		assertEquals("No book-sdConfig row may pre-exist in a fresh book group", 0, countBookConfigRows(fx.bookGroupId));

		/// Identity + location fields on the sparse record point somewhere else entirely; the write
		/// must land in the book group under the canonical name regardless.
		String foreignOid = UUID.randomUUID().toString();
		BaseRecord sparse = sparse(new String[] { FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_NAME,
			"steps", "cfg", "style", "artStyle", "description", "imageName", FieldNames.FIELD_TAGS });
		sparse.setValue(FieldNames.FIELD_OBJECT_ID, foreignOid);
		sparse.setValue(FieldNames.FIELD_GROUP_ID, fx.bookGroupId + 1000000L);
		sparse.setValue(FieldNames.FIELD_NAME, "not-the-book-config");
		sparse.setValue("steps", 33);
		sparse.setValue("cfg", 5);
		sparse.setValue("style", "art");
		sparse.setValue("artStyle", "watercolor");
		sparse.setValue("description", "per-render prompt that must not be stored");
		sparse.setValue("imageName", "per-render-output.png");
		/// A real, readable tag the user owns rides along on the inherited participation list. The
		/// book-settings write is an SD-field contract; it must not attach tags (or controls/attributes)
		/// to the stored row as a side effect.
		BaseRecord smuggledTag = createTag("cb-settings-smuggled-" + UUID.randomUUID());
		List<BaseRecord> tagList = sparse.get(FieldNames.FIELD_TAGS);
		tagList.add(smuggledTag);

		BaseRecord stored = ChapBookUtil.setBookSdConfig(testUser, fx.bookOid, sparse);
		assertNotNull("setBookSdConfig must return the stored config", stored);
		String storedOid = stored.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("The stored config must carry an objectId", storedOid);
		assertNotEquals("A client-supplied objectId must not redirect the write", foreignOid, storedOid);
		assertEquals("The stored row must be named " + PbBookUtil.BOOK_SD_CONFIG_NAME,
			PbBookUtil.BOOK_SD_CONFIG_NAME, stored.get(FieldNames.FIELD_NAME));
		assertEquals("The stored row must live in the book group", Long.valueOf(fx.bookGroupId), stored.get(FieldNames.FIELD_GROUP_ID));
		assertEquals("steps must round-trip", Integer.valueOf(33), stored.get("steps"));
		assertEquals("cfg must round-trip", Integer.valueOf(5), stored.get("cfg"));
		assertEquals("style must round-trip", "art", stored.get("style"));
		assertEquals("artStyle must round-trip", "watercolor", stored.get("artStyle"));
		String storedDesc = stored.get("description");
		assertTrue("description is per-render and must not be stored, got: " + storedDesc,
			storedDesc == null || storedDesc.isBlank());
		String storedImage = stored.get("imageName");
		assertTrue("imageName is per-render and must not be stored, got: " + storedImage,
			storedImage == null || storedImage.isBlank());
		assertEquals("Exactly one book-sdConfig row must exist in the book group", 1, countBookConfigRows(fx.bookGroupId));
		/// The config is the participation owner and the tag the participant: ask for the config's members.
		List<BaseRecord> tagMembers = IOSystem.getActiveContext().getMemberUtil().getMembers(stored, FieldNames.FIELD_TAGS, ModelNames.MODEL_TAG);
		assertTrue("A tag carried on the sparse settings body must not be attached to the stored config, got "
			+ tagMembers.size() + " member(s)", tagMembers.isEmpty());

		BaseRecord viaGet = ChapBookUtil.getBookSdConfig(testUser, fx.bookOid);
		assertNotNull("getBookSdConfig must find the linked row", viaGet);
		assertEquals("getBookSdConfig must return the same row", storedOid, viaGet.get(FieldNames.FIELD_OBJECT_ID));
		Long storedId = stored.get(FieldNames.FIELD_ID);
		assertEquals("The book FK must point at the stored row", storedId, bookSdConfigFkId(fx.bookOid));

		/// Second set: only steps - cfg/style must survive untouched, the row must be the same one.
		BaseRecord again = sparse(new String[] { "steps" });
		again.setValue("steps", 44);
		BaseRecord stored2 = ChapBookUtil.setBookSdConfig(testUser, fx.bookOid, again);
		assertEquals("A second set must patch the same row", storedOid, stored2.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("steps must be updated", Integer.valueOf(44), stored2.get("steps"));
		assertEquals("cfg must be untouched by a sparse set that omits it", Integer.valueOf(5), stored2.get("cfg"));
		assertEquals("style must be untouched by a sparse set that omits it", "art", stored2.get("style"));
		assertEquals("Still exactly one book-sdConfig row", 1, countBookConfigRows(fx.bookGroupId));
	}

	/**
	 * An unlinked {@code book-sdConfig} row already in the book group (PB1 wrote it before the FK patch
	 * landed, or the FK patch failed) is adopted and linked rather than duplicated.
	 */
	@Test
	public void adoptsExistingUnlinkedRow() throws Exception {
		Fixture fx = createFixture("adopt");
		BaseRecord pre = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		pre.set(FieldNames.FIELD_NAME, PbBookUtil.BOOK_SD_CONFIG_NAME);
		pre.set(FieldNames.FIELD_GROUP_ID, fx.bookGroupId);
		pre.set(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pre.set("steps", 12);
		SDUtil.fillStyleDefaults(pre);
		BaseRecord preStored = SdConfigUtil.createOrUpdateConfig(testUser, pre);
		assertNotNull("The pre-existing unlinked row must be created", preStored);
		String preOid = preStored.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull(preOid);
		assertNull("The book must not yet be linked to the row", ChapBookUtil.getBookSdConfig(testUser, fx.bookOid));
		assertEquals(1, countBookConfigRows(fx.bookGroupId));

		BaseRecord sparse = sparse(new String[] { "cfg" });
		sparse.setValue("cfg", 9);
		BaseRecord stored = ChapBookUtil.setBookSdConfig(testUser, fx.bookOid, sparse);
		assertEquals("The unlinked row must be adopted, not duplicated", preOid, stored.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("The adopted row's existing steps must survive", Integer.valueOf(12), stored.get("steps"));
		assertEquals("The sparse cfg must be applied to the adopted row", Integer.valueOf(9), stored.get("cfg"));
		assertEquals("Still exactly one book-sdConfig row after adoption", 1, countBookConfigRows(fx.bookGroupId));
		Long adoptedId = stored.get(FieldNames.FIELD_ID);
		assertEquals("The book FK must now point at the adopted row", adoptedId, bookSdConfigFkId(fx.bookOid));
		BaseRecord viaGet = ChapBookUtil.getBookSdConfig(testUser, fx.bookOid);
		assertNotNull(viaGet);
		assertEquals(preOid, viaGet.get(FieldNames.FIELD_OBJECT_ID));
	}

	/**
	 * Rendering with a client overlay reads the book tier but never writes it: after a render whose
	 * overlay says {@code steps=23}, the stored book config still says {@code steps=31}, and the render
	 * itself saw 23.
	 */
	@Test
	public void renderDoesNotMutateStoredBookConfig() throws Exception {
		Fixture fx = createFixture("render");
		BaseRecord bookSparse = sparse(new String[] { "steps", "negativePrompt" });
		bookSparse.setValue("steps", 31);
		bookSparse.setValue("negativePrompt", "book-level negative p0-10a");
		BaseRecord stored = ChapBookUtil.setBookSdConfig(testUser, fx.bookOid, bookSparse);
		String storedOid = stored.get(FieldNames.FIELD_OBJECT_ID);

		patchScenePrompt(fx.scenes.get(0), "a lantern-lit cottage in deep snow, warm window glow, winter night");
		BaseRecord overlay = sparse(new String[] { "steps", "description" });
		overlay.setValue("steps", 23);
		overlay.setValue("description", "overlay description that must never be stored");

		List<BaseRecord> captured = new ArrayList<>();
		SDUtil sdu = new SDUtil(SDAPIEnumType.SWARM, swarmServer()) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name, int batch, boolean hires, int seed) {
				captured.add(sdConfig);
				return new ArrayList<>();
			}
		};
		ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(
			testUser, fx.sceneOids.get(0), sdu, null, overlay, null);
		assertEquals(ChapBookUtil.SceneRenderStatus.FAILED, result.status);
		assertEquals("createImage must be called exactly once", 1, captured.size());
		assertEquals("The render must see the overlay's steps", Integer.valueOf(23), captured.get(0).get("steps"));
		assertEquals("The render must see the book's negative prompt (no overlay value)",
			"book-level negative p0-10a", captured.get(0).get("negativePrompt"));

		BaseRecord after = ChapBookUtil.getBookSdConfig(testUser, fx.bookOid);
		assertNotNull(after);
		assertEquals("The render must not relink the book to another row", storedOid, after.get(FieldNames.FIELD_OBJECT_ID));
		assertEquals("The stored book steps must be untouched by a render overlay", Integer.valueOf(31), after.get("steps"));
		String afterDesc = after.get("description");
		assertTrue("The stored book description must be untouched by a render overlay, got: " + afterDesc,
			afterDesc == null || afterDesc.isBlank());
		assertEquals("Still exactly one book-sdConfig row after a render", 1, countBookConfigRows(fx.bookGroupId));
	}

	/** The ChapBook wrappers refuse a non-CHAPBOOK book with 403, before any write. */
	@Test
	public void nonChapBookIs403() throws Exception {
		Fixture fx = createFixture("type");
		BaseRecord bookCheck = reloadBook(fx.bookOid);
		BaseRecord typePatch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_PB_BOOK_TYPE });
		typePatch.set(FieldNames.FIELD_ID, bookCheck.get(FieldNames.FIELD_ID));
		typePatch.set(FieldNames.FIELD_OBJECT_ID, fx.bookOid);
		typePatch.set(FieldNames.FIELD_NAME, bookCheck.get(FieldNames.FIELD_NAME));
		typePatch.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, "STORY");
		assertNotNull("bookType patch must succeed", IOSystem.getActiveContext().getAccessPoint().update(testUser, typePatch));

		BaseRecord sparse = sparse(new String[] { "steps" });
		sparse.setValue("steps", 5);
		try {
			ChapBookUtil.setBookSdConfig(testUser, fx.bookOid, sparse);
			fail("setBookSdConfig on a STORY book must throw 403");
		}
		catch (PictureBookException e) {
			assertEquals("setBookSdConfig on a non-CHAPBOOK must be a 403", 403, e.getStatus());
		}
		try {
			ChapBookUtil.getBookSdConfig(testUser, fx.bookOid);
			fail("getBookSdConfig on a STORY book must throw 403");
		}
		catch (PictureBookException e) {
			assertEquals("getBookSdConfig on a non-CHAPBOOK must be a 403", 403, e.getStatus());
		}
		assertEquals("A refused set must not have written a row", 0, countBookConfigRows(fx.bookGroupId));

		try {
			ChapBookUtil.setBookSdConfig(testUser, "no-such-book-" + UUID.randomUUID(), sparse);
			fail("An unknown book must throw 404");
		}
		catch (PictureBookException e) {
			assertEquals(404, e.getStatus());
		}
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	private static final class Fixture {
		String bookOid;
		long bookGroupId;
		List<BaseRecord> scenes;
		List<String> sceneOids = new ArrayList<>();
	}

	private Fixture createFixture(String tag) {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		Fixture fx = new Fixture();
		long ts = System.currentTimeMillis();

		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookSettings-" + tag + "-" + ts, "Poem Settings " + tag + " " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-settings-" + tag + "-" + ts,
			"ChapBook Settings " + tag + " " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		fx.bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", fx.bookOid);
		BaseRecord bookCheck = reloadBook(fx.bookOid);
		Long gid = bookCheck.get(FieldNames.FIELD_GROUP_ID);
		assertNotNull("Book must carry a groupId", gid);
		fx.bookGroupId = gid.longValue();
		fx.scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created", fx.scenes.isEmpty());
		for (BaseRecord s : fx.scenes) {
			fx.sceneOids.add(s.get(FieldNames.FIELD_OBJECT_ID));
		}
		return fx;
	}

	private BaseRecord sparse(String[] fields) throws Exception {
		return RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG, fields);
	}

	private int countBookConfigRows(long groupId) {
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_SD_CONFIG, FieldNames.FIELD_NAME, PbBookUtil.BOOK_SD_CONFIG_NAME);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().count(testUser, q);
	}

	/** The numeric id the book's {@code sdConfig} FK points at, read fresh with the config projection. */
	private Long bookSdConfigFkId(String bookOid) {
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookOid);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(PbConfigUtil.requestFields());
		bq.setCache(false);
		BaseRecord b = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be readable with the config projection", b);
		BaseRecord fk = b.get(OlioFieldNames.FIELD_PB_SD_CONFIG);
		assertNotNull("The book sdConfig FK must be set", fk);
		return fk.get(FieldNames.FIELD_ID);
	}

	private String swarmServer() {
		String s = testProperties.getProperty(SdTestGate.PROP_SWARM_SERVER);
		assertNotNull(SdTestGate.PROP_SWARM_SERVER + " must be set", s);
		return s;
	}

	private void patchScenePrompt(BaseRecord scene, String prompt) throws Exception {
		BaseRecord patch = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE, new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME, OlioFieldNames.FIELD_CB_SD_PROMPT });
		patch.set(FieldNames.FIELD_ID, scene.get(FieldNames.FIELD_ID));
		patch.set(FieldNames.FIELD_OBJECT_ID, scene.get(FieldNames.FIELD_OBJECT_ID));
		patch.set(FieldNames.FIELD_NAME, scene.get(FieldNames.FIELD_NAME));
		patch.set(OlioFieldNames.FIELD_CB_SD_PROMPT, prompt);
		assertNotNull("Scene prompt patch must succeed", IOSystem.getActiveContext().getAccessPoint().update(testUser, patch));
	}

	private BaseRecord reloadBook(String bookObjectId) {
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_OWNER_ID, OlioFieldNames.FIELD_PB_BOOK_TYPE });
		bq.setCache(false);
		BaseRecord bookCheck = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be findable by objectId", bookCheck);
		return bookCheck;
	}

	private BaseRecord createTag(String name) throws Exception {
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, "~/Tags/ChapBookSettings");
		plist.parameter(FieldNames.FIELD_NAME, name);
		BaseRecord tag = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_TAG, testUser, null, plist);
		tag = IOSystem.getActiveContext().getAccessPoint().create(testUser, tag);
		assertNotNull("Tag must be created", tag);
		return tag;
	}

	private BaseRecord createPoem(BaseRecord user, String groupPath, String name, String text) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord poem = IOSystem.getActiveContext().getFactory()
				.newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			poem.set("text", text);
			poem.set("title", name);
			return IOSystem.getActiveContext().getAccessPoint().create(user, poem);
		} catch (Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}
}
