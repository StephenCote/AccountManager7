package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
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
import org.cote.accountmanager.olio.OlioContext;
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
 * Objects7-level integration test for the scene-level ChapBook review operations
 * {@link ChapBookUtil#mergeSceneUp(BaseRecord, String)} and
 * {@link ChapBookUtil#deleteSceneAndReindex(BaseRecord, String)}, run against the live DB.
 * <p>
 * The E2E spec ({@code e2e/chapBookMerge.spec.js}) already drives these through the review UI as the
 * owner and checks the persisted result. This test adds the coverage the E2E cannot reach:
 * <ol>
 *   <li><b>The full merge contract at the Objects7 layer</b>, including two fields the E2E does NOT
 *       assert — that the survivor's {@code sdPrompt} and {@code promptLocked} are PRESERVED unchanged
 *       (a hand-edited or LOCKED prompt must survive a merge; only the longer stanza and the resulting
 *       {@code imageStale} flag change). The E2E only checks {@code poemStanza} and {@code imageStale}.</li>
 *   <li><b>Scene-level AUTHORIZATION.</b> A same-org non-owner who can READ a ChapBook (a targeted
 *       Read grant, no Write/Delete) must be DENIED both {@code mergeSceneUp} and
 *       {@code deleteSceneAndReindex}, and the denial must be NON-DESTRUCTIVE (no scene folded, none
 *       deleted, no stanza rewritten, no reindex). A fresh REST user cannot be provisioned into this
 *       read-but-not-write state, so the E2E can never exercise it.</li>
 * </ol>
 * <p>
 * <b>Observed denial status.</b> Unlike {@code deleteChapBook} (which has an explicit
 * {@code AuthorizationUtil.canDelete} pre-check that maps a denial to a hardened HTTP 403), the
 * scene-level ops rely on {@code AccessPoint.update}/{@code delete} returning null/false when denied,
 * which {@code mergeSceneUp}/{@code deleteSceneAndReindex} surface as a {@link PictureBookException}
 * with status <b>500</b>. This test asserts the operation FAILS (a real {@link PictureBookException}
 * is thrown) and, most importantly, that it is NON-DESTRUCTIVE — the security-relevant property. It
 * also records the actual status so a future hardening (500 → 403) is a visible, deliberate change.
 * <p>
 * No LLM and no SD server are contacted: {@code createChapBook} is called with {@code chatConfig=null}
 * (stanza-excerpt fallback prompts, no LLM landscape step) and the test never calls
 * {@code renderChapBook}. It runs against the live DB only.
 */
public class TestChapBookSceneMerge extends BaseTest {

	/** Reuse the already-seeded Books universe org (avoids the multi-minute Olio seed). */
	private static final String ORG_A = "/Development/World Building";

	/** Owner/creator of the ChapBook — a non-admin user; the book row is olio-principal-owned. */
	private static final String OWNER_NAME = "cbSceneMergeOwner";
	/** The ACTOR whose scene merge/delete is denied — a distinct non-admin, read-only user. */
	private static final String DENIED_NAME = "cbSceneMergeDenied";

	/**
	 * Three two-line stanzas separated by blank lines. With {@code maxLinesPerPage=8} and
	 * {@code chunkPoem}'s non-single-line branch, each stanza becomes exactly one scene → 3 scenes,
	 * which is the minimum needed to exercise a merge (fold [1] into [0]) AND still leave >1 survivor.
	 * The first stanza is multi-line, so the " by " header-strip heuristic never fires.
	 */
	private static final String POEM_TEXT =
		"The harbor lamp still burns at three,\n" +
		"A pale coin dropped upon the sea.\n" +
		"\n" +
		"The nets are hung, the boats are still,\n" +
		"And frost has climbed the windowsill.\n" +
		"\n" +
		"A single gull turns on the gale,\n" +
		"Then vanishes beyond the pale.";

	@Before
	public void sceneMergeSetup() {
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

	private String dataPath() {
		return testProperties.getProperty("test.datagen.path");
	}

	/** Create an {@code olio.cb.poem} at the given group path (same pattern as TestChapBookDeleteAuthz). */
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

	/** Create a real 3-scene CHAPBOOK owned by {@code owner} (chatConfig=null → no LLM). */
	private BaseRecord seedBook(BaseRecord owner, String tag) throws Exception {
		String dataPath = dataPath();
		assertNotNull("test.datagen.path must be set", dataPath);
		long ts = System.currentTimeMillis();
		String slug = "cb-scenemerge-" + tag + "-" + ts;
		String title = "ChapBook Scene Merge " + tag + " " + ts;
		BaseRecord poem = createPoem(owner, "~/Data/CbSceneMerge-" + tag + "-" + ts, "Poem " + ts, POEM_TEXT);
		assertNotNull("Poem should be created", poem);
		String poemOid = poem.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem must have objectId", poemOid);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poemOid);
		BaseRecord book = ChapBookUtil.createChapBook(owner, dataPath, slug, title, poemOids, 8, null);
		assertNotNull("createChapBook must return a book record", book);
		logger.info("Seeded CHAPBOOK slug={} objectId={}", slug, book.get(FieldNames.FIELD_OBJECT_ID));
		return book;
	}

	private static Integer sceneIndexOf(BaseRecord scene) {
		return scene.get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
	}

	/** Assert the survivors' sceneIndex values are a gap-free 0..n-1 in list order. */
	private static void assertContiguousIndices(List<BaseRecord> scenes) {
		for (int i = 0; i < scenes.size(); i++) {
			Integer idx = sceneIndexOf(scenes.get(i));
			assertNotNull("scene " + i + " has a sceneIndex", idx);
			assertEquals("scenes must be reindexed to a contiguous 0.." + (scenes.size() - 1)
				+ "; position " + i + " has sceneIndex=" + idx, i, idx.intValue());
		}
	}

	/**
	 * Happy path — merge folds the next stanza into this one, flags the survivor imageStale, PRESERVES the
	 * survivor's sdPrompt and promptLocked unchanged, deletes the folded scene, and reindexes the
	 * survivors 0..n-2.
	 */
	@Test
	public void mergeSceneUp_foldsNext_preservesPrompt_reindexes() throws Exception {
		BaseRecord owner = user(OWNER_NAME);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = seedBook(owner, "merge");

		List<BaseRecord> before = PbBookUtil.listScenes(owner, book);
		assertTrue("need at least 3 scenes to exercise a merge; got " + before.size(), before.size() >= 3);
		final int n = before.size();

		BaseRecord absorbing = before.get(0);
		BaseRecord folded = before.get(1);
		String scene0Oid = absorbing.get(FieldNames.FIELD_OBJECT_ID);
		String scene1Oid = folded.get(FieldNames.FIELD_OBJECT_ID);
		String stanza0 = absorbing.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		String stanza1 = folded.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		assertNotNull("scene0 has stanza text", stanza0);
		assertNotNull("scene1 has stanza text", stanza1);
		// chatConfig was null → createChapBookScene stores a non-blank landscape fallback sdPrompt,
		// so "preserved after merge" is a real, observable assertion (the prompt is non-blank to begin
		// with, and must remain exactly that value after the merge).
		String prePrompt = absorbing.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		Boolean preLocked = absorbing.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
		logger.info("scene0 pre-merge sdPrompt present={} ", (prePrompt != null && !prePrompt.isBlank()));

		String survivorOid = ChapBookUtil.mergeSceneUp(owner, scene0Oid);
		assertEquals("mergeSceneUp returns the surviving (absorbing) scene objectId", scene0Oid, survivorOid);
		CacheUtil.clearCache();

		// Survivor: merged stanza, imageStale=true, sdPrompt PRESERVED, promptLocked PRESERVED.
		BaseRecord survivor = PbBookUtil.readScene(owner, scene0Oid, orgId);
		assertNotNull("survivor scene still exists", survivor);
		assertEquals("survivor stanza is thisStanza + \\n + nextStanza",
			stanza0 + "\n" + stanza1, survivor.get(OlioFieldNames.FIELD_CB_POEM_STANZA));
		assertEquals("merge flags the survivor imageStale=true",
			Boolean.TRUE, survivor.get(OlioFieldNames.FIELD_PB_IMAGE_STALE));
		String postPrompt = survivor.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertEquals("merge must PRESERVE the survivor's sdPrompt unchanged (a hand-edited or LOCKED "
			+ "prompt must survive the merge); got sdPrompt=" + postPrompt, prePrompt, postPrompt);
		Boolean postLocked = survivor.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
		assertEquals("merge must PRESERVE the survivor's promptLocked unchanged; got " + postLocked,
			preLocked, postLocked);

		// Folded scene is gone.
		assertNull("folded scene[1] was deleted", PbBookUtil.readScene(owner, scene1Oid, orgId));

		// Survivors reindexed contiguous 0..n-2, survivor is page 0.
		List<BaseRecord> after = PbBookUtil.listScenes(owner, book);
		assertEquals("one scene fewer after the merge", n - 1, after.size());
		assertContiguousIndices(after);
		assertEquals("the survivor is scene index 0 after the merge",
			scene0Oid, after.get(0).get(FieldNames.FIELD_OBJECT_ID));
	}

	/** Happy path — deleteSceneAndReindex removes a scene and reindexes the survivors 0..n-2. */
	@Test
	public void deleteSceneAndReindex_removes_andReindexes() throws Exception {
		BaseRecord owner = user(OWNER_NAME);
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		BaseRecord book = seedBook(owner, "del");

		List<BaseRecord> before = PbBookUtil.listScenes(owner, book);
		assertTrue("need at least 2 scenes to exercise a delete; got " + before.size(), before.size() >= 2);
		final int n = before.size();
		String removedOid = before.get(0).get(FieldNames.FIELD_OBJECT_ID);

		ChapBookUtil.deleteSceneAndReindex(owner, removedOid);
		CacheUtil.clearCache();

		assertNull("removed scene is deleted", PbBookUtil.readScene(owner, removedOid, orgId));

		List<BaseRecord> after = PbBookUtil.listScenes(owner, book);
		assertEquals("one scene fewer after the delete", n - 1, after.size());
		assertContiguousIndices(after);
	}

	/**
	 * Authorization — a read-only same-org non-owner is DENIED both mergeSceneUp and
	 * deleteSceneAndReindex, and each denial is non-destructive.
	 */
	@Test
	public void sceneMergeAndDelete_deniedForReadOnlyNonOwner_nonDestructive() throws Exception {
		BaseRecord owner = user(OWNER_NAME);
		BaseRecord denied = user(DENIED_NAME);
		OrganizationContext o = org();
		long orgId = ((Number) owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		assertTrue("Both test users must share the organization",
			((Number) denied.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue() == orgId);

		BaseRecord book = seedBook(owner, "authz");
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);

		List<BaseRecord> before = PbBookUtil.listScenes(owner, book);
		assertTrue("need at least 3 scenes; got " + before.size(), before.size() >= 3);
		final int n = before.size();
		String scene0Oid = before.get(0).get(FieldNames.FIELD_OBJECT_ID);
		String stanza0 = before.get(0).get(OlioFieldNames.FIELD_CB_POEM_STANZA);

		// ── Provision a READ-but-not-WRITE grant for the denied user (FIXTURE SETUP as admin) ──────────
		// Grant Read (DATA + GROUP) on the book's OWN group — scenes live in this same group
		// (createChapBookScene uses PbBookUtil.bookGroupPath(slug)) — so the denied user can READ the
		// book and its scenes, reaching the AccessPoint.update/delete DENY branch, but has NO Write/Delete.
		BaseRecord ownerBook = PbBookUtil.readBook(owner, bookOid, orgId);
		assertNotNull("Owner must be able to read the book it created", ownerBook);
		long bookGroupId = ((Number) ownerBook.get(FieldNames.FIELD_GROUP_ID)).longValue();
		Query gq = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_ID, bookGroupId);
		gq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		gq.setCache(false);
		BaseRecord bookGroup = IOSystem.getActiveContext().getSearch().findRecord(gq);
		assertNotNull("Book's own group must resolve for the fixture grant", bookGroup);

		IOSystem.getActiveContext().getAuthorizationUtil().setEntitlement(
			o.getAdminUser(), denied, new BaseRecord[] { bookGroup },
			new String[] { "Read" },
			new String[] { PermissionEnumType.DATA.toString(), PermissionEnumType.GROUP.toString() });
		CacheUtil.clearCache();

		// ── POSITIVE CONTROL: the denied user CAN read scene0 via the Read grant ──────────────────────
		// If this is null the merge/delete calls would take the 404 not-found path, not the write-DENY
		// path — so assert it loudly rather than mistaking a 404 for an authorization denial.
		BaseRecord readByDenied = PbBookUtil.readScene(denied, scene0Oid, orgId);
		assertNotNull("Positive control: the read-only user must be able to READ scene0 (the Read grant "
			+ "is real), so merge/delete reach the write-DENY branch and not the 404 path", readByDenied);

		// ── mergeSceneUp is DENIED and non-destructive ───────────────────────────────────────────────
		int mergeStatus = -1;
		try {
			String r = ChapBookUtil.mergeSceneUp(denied, scene0Oid);
			fail("mergeSceneUp must throw for a read-only non-owner; instead it returned " + r
				+ " (the read-only user was unexpectedly allowed to merge)");
		} catch (PictureBookException e) {
			mergeStatus = e.getStatus();
			logger.info("mergeSceneUp denied as expected: status={} message={}", e.getStatus(), e.getMessage());
			assertTrue("denied mergeSceneUp must fail with an HTTP error status, got " + e.getStatus(),
				e.getStatus() >= 400);
		}

		// ── deleteSceneAndReindex is DENIED and non-destructive ──────────────────────────────────────
		int deleteStatus = -1;
		try {
			ChapBookUtil.deleteSceneAndReindex(denied, scene0Oid);
			fail("deleteSceneAndReindex must throw for a read-only non-owner (the read-only user was "
				+ "unexpectedly allowed to delete a scene)");
		} catch (PictureBookException e) {
			deleteStatus = e.getStatus();
			logger.info("deleteSceneAndReindex denied as expected: status={} message={}", e.getStatus(), e.getMessage());
			assertTrue("denied deleteSceneAndReindex must fail with an HTTP error status, got " + e.getStatus(),
				e.getStatus() >= 400);
		}

		// ── Both denials must be NON-DESTRUCTIVE: re-read as the OWNER (fresh) — nothing changed ───────
		CacheUtil.clearCache();
		List<BaseRecord> after = PbBookUtil.listScenes(owner, book);
		assertEquals("the denied merge+delete must NOT have removed any scene", n, after.size());
		assertContiguousIndices(after);
		BaseRecord survivor0 = PbBookUtil.readScene(owner, scene0Oid, orgId);
		assertNotNull("scene0 must still exist after the denied operations", survivor0);
		assertEquals("scene0's stanza must be UNCHANGED after the denied merge",
			stanza0, survivor0.get(OlioFieldNames.FIELD_CB_POEM_STANZA));

		logger.info("Scene-op denial statuses (observed): merge={} delete={} "
			+ "(scene-level ops surface a denial as PictureBookException(500), not the hardened 403 that "
			+ "deleteChapBook uses; recorded so a future hardening is a deliberate, visible change).",
			mergeStatus, deleteStatus);
	}
}
