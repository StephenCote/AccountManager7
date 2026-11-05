package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Objects7-level integration test for {@link ChapBookUtil#splitScene(BaseRecord, String)} (P10-C 4, ledger
 * §6.C 266), the server-side counterpart of {@code mergeSceneUp}, run against the live DB.
 * <p>
 * Covers: (1) the split contract — the scene keeps the first {@code ceil(lines/2)} lines and is flagged
 * {@code imageStale} with its {@code sdPrompt}/{@code promptLocked} PRESERVED; a NEW scene directly after it
 * holds the rest with the title suffixed {@code " (cont.)"}, the mood copied and a landscape fallback prompt;
 * the book is reindexed {@code 0..n}; (2) a stanza with fewer than two non-blank lines is refused with 400
 * and nothing changes; (3) a same-org read-only non-owner is refused and the refusal is NON-DESTRUCTIVE — the
 * create comes first, so a denied split neither truncates the page nor leaves a stray scene behind.
 * <p>
 * No LLM and no SD server are contacted ({@code createChapBook} with {@code chatConfig=null}; the split never
 * calls the LLM). Same fixture shape as {@link TestChapBookSceneMerge}.
 */
public class TestChapBookSceneSplit extends BaseTest {

	private static final String ORG_A = "/Development/World Building";
	private static final String OWNER_NAME = "cbSceneSplitOwner";
	private static final String DENIED_NAME = "cbSceneSplitDenied";

	/** Two stanzas: a four-line one (splits 2+2) and a two-line one (splits 1+1); maxLinesPerPage=8 → 2 scenes. */
	private static final String POEM_TEXT =
		"The harbor lamp still burns at three,\n" +
		"A pale coin dropped upon the sea.\n" +
		"The nets are hung, the boats are still,\n" +
		"And frost has climbed the windowsill.\n" +
		"\n" +
		"A single gull turns on the gale,\n" +
		"Then vanishes beyond the pale.";

	@Before
	public void sceneSplitSetup() {
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
	}

	private OrganizationContext org() {
		return getTestOrganization(ORG_A);
	}

	private BaseRecord user(String name) {
		OrganizationContext o = org();
		BaseRecord u = ioContext.getFactory().getCreateUser(o.getAdminUser(), name, o.getOrganizationId());
		assertNotNull("Failed to resolve test user " + name, u);
		return u;
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

	private BaseRecord seedBook(BaseRecord owner, String tag) throws Exception {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		long ts = System.currentTimeMillis();
		String slug = "cb-scenesplit-" + tag + "-" + ts;
		BaseRecord poem = createPoem(owner, "~/Data/CbSceneSplit-" + tag + "-" + ts, "Poem " + ts, POEM_TEXT);
		assertNotNull("Poem should be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord book = ChapBookUtil.createChapBook(owner, dataPath, slug, "ChapBook Scene Split " + tag + " " + ts,
			poemOids, 8, null);
		assertNotNull("createChapBook must return a book record", book);
		return book;
	}

	private static void assertContiguousIndices(List<BaseRecord> scenes) {
		for (int i = 0; i < scenes.size(); i++) {
			Integer idx = scenes.get(i).get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			assertNotNull("scene " + i + " has a sceneIndex", idx);
			assertEquals("scenes must be reindexed to a contiguous 0.." + (scenes.size() - 1)
				+ "; position " + i + " has sceneIndex=" + idx, i, idx.intValue());
		}
	}

	private static String oid(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
	}

	@Test
	public void splitStanzaAtMidpoint_halves() {
		assertNull("null stanza", ChapBookUtil.splitStanzaAtMidpoint(null));
		assertNull("one line cannot split", ChapBookUtil.splitStanzaAtMidpoint("only one line"));
		assertNull("one line plus a blank cannot split", ChapBookUtil.splitStanzaAtMidpoint("only one line\n   "));
		String[] two = ChapBookUtil.splitStanzaAtMidpoint("a\nb");
		assertEquals("a", two[0]);
		assertEquals("b", two[1]);
		String[] three = ChapBookUtil.splitStanzaAtMidpoint("a\r\nb\r\nc");
		assertEquals("ceil(3/2)=2 lines stay on the first page", "a\nb", three[0]);
		assertEquals("c", three[1]);
		String[] four = ChapBookUtil.splitStanzaAtMidpoint("a\nb\nc\nd");
		assertEquals("a\nb", four[0]);
		assertEquals("c\nd", four[1]);
	}

	/** Happy path: the four-line page 0 splits 2+2; the new page is seated at 1 and the old page 1 moves to 2. */
	@Test
	public void splitScene_createsNextPage_truncatesOriginal_reindexes() throws Exception {
		BaseRecord owner = user(OWNER_NAME);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = seedBook(owner, "split");

		List<BaseRecord> before = PbBookUtil.listScenes(owner, book);
		assertEquals("the two stanzas seed two scenes", 2, before.size());
		final int n = before.size();
		BaseRecord target = before.get(0);
		String targetOid = oid(target);
		String followerOid = oid(before.get(1));
		String stanza = target.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		assertNotNull("scene0 has stanza text", stanza);
		String[] lines = stanza.split("\n");
		assertEquals("the first stanza is the four-line one", 4, lines.length);
		String prePrompt = target.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		Boolean preLocked = target.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
		String preTitle = target.get(OlioFieldNames.FIELD_PB_TITLE);
		String preMood = target.get(OlioFieldNames.FIELD_PB_MOOD);
		assertTrue("the fixture scene carries a non-blank create-time prompt to preserve",
			prePrompt != null && !prePrompt.isBlank());

		String newOid = ChapBookUtil.splitScene(owner, targetOid);
		assertNotNull("splitScene returns the NEW scene's objectId", newOid);
		assertTrue("the new scene is a different record", !newOid.equals(targetOid));
		CacheUtil.clearCache();

		BaseRecord original = PbBookUtil.readScene(owner, targetOid, orgId);
		assertNotNull("the split scene still exists", original);
		assertEquals("the original keeps the first two lines", lines[0] + "\n" + lines[1],
			original.get(OlioFieldNames.FIELD_CB_POEM_STANZA));
		assertEquals("the shorter text makes the rendered image stale", Boolean.TRUE,
			original.get(OlioFieldNames.FIELD_PB_IMAGE_STALE));
		assertEquals("split PRESERVES the original's sdPrompt", prePrompt, original.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("split PRESERVES the original's promptLocked", preLocked, original.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertEquals("split leaves the original's title alone", preTitle, original.get(OlioFieldNames.FIELD_PB_TITLE));

		BaseRecord created = PbBookUtil.readScene(owner, newOid, orgId);
		assertNotNull("the new scene is readable by its creator", created);
		assertEquals("the new scene holds the last two lines", lines[2] + "\n" + lines[3],
			created.get(OlioFieldNames.FIELD_CB_POEM_STANZA));
		assertEquals("the new scene's title is the original's suffixed (cont.)",
			(preTitle != null && !preTitle.isBlank() ? preTitle + " (cont.)" : null),
			created.get(OlioFieldNames.FIELD_PB_TITLE));
		assertEquals("the mood is copied", preMood, created.get(OlioFieldNames.FIELD_PB_MOOD));
		String newPrompt = created.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertTrue("the new scene carries the no-LLM landscape fallback prompt (the review flags it needs-prompt); got "
			+ newPrompt, newPrompt != null && newPrompt.startsWith("landscape, "));
		BaseRecord createdBook = created.get(OlioFieldNames.FIELD_PB_BOOK);
		assertNotNull("the new scene points at the book", createdBook);
		// Typed locals: a bare generic get() handed to assertEquals binds the Object[] overload (objects7-reference.md).
		Long bookGroupId = book.get(FieldNames.FIELD_GROUP_ID);
		Long createdGroupId = created.get(FieldNames.FIELD_GROUP_ID);
		assertEquals("the new scene is in the book's group", bookGroupId, createdGroupId);

		List<BaseRecord> after = PbBookUtil.listScenes(owner, book);
		assertEquals("one scene more after the split", n + 1, after.size());
		assertContiguousIndices(after);
		assertEquals("the original stays at 0", targetOid, oid(after.get(0)));
		assertEquals("the new scene is seated directly after it", newOid, oid(after.get(1)));
		assertEquals("the old follower moves down one", followerOid, oid(after.get(2)));
	}

	/** A page with a single line has nothing to split: 400, nothing written. */
	@Test
	public void splitScene_refusesSingleLine_nonDestructive() throws Exception {
		BaseRecord owner = user(OWNER_NAME);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = seedBook(owner, "short");
		List<BaseRecord> before = PbBookUtil.listScenes(owner, book);
		assertEquals(2, before.size());
		BaseRecord two = before.get(1);
		String twoOid = oid(two);
		// Split the two-line page once (1+1), then the one-line half must refuse.
		String halfOid = ChapBookUtil.splitScene(owner, twoOid);
		CacheUtil.clearCache();
		List<BaseRecord> mid = PbBookUtil.listScenes(owner, book);
		assertEquals(3, mid.size());
		assertContiguousIndices(mid);
		try {
			ChapBookUtil.splitScene(owner, halfOid);
			fail("a one-line stanza must not split");
		} catch (PictureBookException e) {
			assertEquals("too little text is a 400", 400, e.getStatus());
		}
		CacheUtil.clearCache();
		List<BaseRecord> after = PbBookUtil.listScenes(owner, book);
		assertEquals("the refused split added nothing", 3, after.size());
		assertContiguousIndices(after);
		BaseRecord half = PbBookUtil.readScene(owner, halfOid, orgId);
		assertEquals("the one-line page is untouched", "Then vanishes beyond the pale.",
			half.get(OlioFieldNames.FIELD_CB_POEM_STANZA));
	}

	/** A same-org read-only non-owner is refused, and the refusal leaves the book exactly as it was. */
	@Test
	public void splitScene_deniedForReadOnlyNonOwner_nonDestructive() throws Exception {
		BaseRecord owner = user(OWNER_NAME);
		BaseRecord denied = user(DENIED_NAME);
		OrganizationContext o = org();
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = seedBook(owner, "authz");
		String bookOid = oid(book);
		List<BaseRecord> before = PbBookUtil.listScenes(owner, book);
		assertEquals(2, before.size());
		String scene0Oid = oid(before.get(0));
		String stanza0 = before.get(0).get(OlioFieldNames.FIELD_CB_POEM_STANZA);

		// Read (DATA + GROUP) on the book's own group and Read on its world — the same fixture as
		// TestChapBookSceneMerge — so the denied user reaches the write, not resolveSceneBook's 404.
		BaseRecord ownerBook = PbBookUtil.readBook(owner, bookOid, orgId);
		assertNotNull(ownerBook);
		long bookGroupId = ((Number) ownerBook.get(FieldNames.FIELD_GROUP_ID)).longValue();
		Query gq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_ID, bookGroupId);
		gq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		gq.setCache(false);
		BaseRecord bookGroup = IOSystem.getActiveContext().getSearch().findRecord(gq);
		assertNotNull("Book's own group must resolve for the fixture grant", bookGroup);
		IOSystem.getActiveContext().getAuthorizationUtil().setEntitlement(
			o.getAdminUser(), denied, new BaseRecord[] { bookGroup }, new String[] { "Read" },
			new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
		BaseRecord worldFk = ownerBook.get(OlioFieldNames.FIELD_PB_WORLD);
		assertNotNull("the book carries its world FK", worldFk);
		long worldId = ((Number) worldFk.get(FieldNames.FIELD_ID)).longValue();
		Query wq = QueryUtil.createQuery(OlioModelNames.MODEL_WORLD, FieldNames.FIELD_ID, worldId);
		wq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		wq.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_URN, FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID });
		wq.setCache(false);
		BaseRecord world = IOSystem.getActiveContext().getSearch().findRecord(wq);
		assertNotNull("the book's world must resolve for the fixture grant", world);
		IOSystem.getActiveContext().getAuthorizationUtil().setEntitlement(
			o.getAdminUser(), denied, new BaseRecord[] { world }, new String[] { "Read" },
			new String[] { PermissionEnumType.DATA.toString() });
		CacheUtil.clearCache();

		// Positive controls: the read-only user can READ the scene and the book, so the split reaches a write.
		assertNotNull("Positive control: the read-only user must be able to READ scene0",
			PbBookUtil.readScene(denied, scene0Oid, orgId));
		assertNotNull("Positive control: the read-only user must be able to READ the book",
			PbBookUtil.readBook(denied, bookOid, orgId));

		int status = -1;
		try {
			String r = ChapBookUtil.splitScene(denied, scene0Oid);
			fail("splitScene must throw for a read-only non-owner; instead it returned " + r);
		} catch (PictureBookException e) {
			status = e.getStatus();
			logger.info("splitScene denied as expected: status={} message={}", e.getStatus(), e.getMessage());
		}
		assertTrue("a denied split fails with an HTTP error status, got " + status, status >= 400);

		CacheUtil.clearCache();
		List<BaseRecord> after = PbBookUtil.listScenes(owner, book);
		assertEquals("the denied split must NOT have added a scene", 2, after.size());
		assertContiguousIndices(after);
		BaseRecord scene0 = PbBookUtil.readScene(owner, scene0Oid, orgId);
		assertNotNull(scene0);
		assertEquals("scene0's stanza must be UNCHANGED after the denied split (the create comes first)",
			stanza0, scene0.get(OlioFieldNames.FIELD_CB_POEM_STANZA));
		assertFalse("scene0 is not flagged stale by a refused split",
			Boolean.TRUE.equals(scene0.get(OlioFieldNames.FIELD_PB_IMAGE_STALE)));
	}
}
