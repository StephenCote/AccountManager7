package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbComposeUtil;
import org.cote.accountmanager.olio.picturebook.PictureBookException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.cote.accountmanager.schema.type.PbElementTypeEnumType;
import org.cote.accountmanager.schema.type.PermissionEnumType;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;
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
 * <b>Denial status.</b> Both ops first sweep the scene's compositions (its CHAPBOOK_PAGE composition
 * with its scene actors and elements, child→parent, as the user — P4-13 c2) and surface a PBAC refusal
 * of any of those rows as <b>403</b>, before any scene write. On a scene with no compositions the ops
 * still rely on {@code AccessPoint.update}/{@code delete} returning null/false, surfaced as <b>500</b>.
 * This test seeds a page composition on every scene it denies, asserts the operation FAILS with 403
 * and, most importantly, that it is NON-DESTRUCTIVE — scenes, stanza and composition rows all intact.
 * The happy paths assert the folded/removed scene's composition and element rows go with it while a
 * sibling scene's survive.
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

	/** The scene's CHAPBOOK_PAGE composition (created as {@code owner}) with one PROP element row; {composition, element}. */
	private static BaseRecord[] seedPageComposition(BaseRecord owner, BaseRecord book, BaseRecord scene) {
		BaseRecord comp = PbComposeUtil.getCreatePageComposition(owner, book, scene, PbCompositionSourceEnumType.USER);
		assertNotNull("page composition created for scene " + scene.get(FieldNames.FIELD_OBJECT_ID), comp);
		Map<String, Object> in = new HashMap<>();
		in.put(OlioFieldNames.FIELD_PB_ELEMENT_TYPE, PbElementTypeEnumType.PROP.toString());
		in.put(FieldNames.FIELD_DESCRIPTION, "a brass harbor lamp");
		BaseRecord element = PbComposeUtil.createElementRow(owner, comp, in);
		assertNotNull("element row created on the page composition", element);
		BaseRecord compScene = comp.get(OlioFieldNames.FIELD_PB_SCENE);
		assertNotNull("the page composition carries its scene", compScene);
		assertEquals("the page composition points at its scene", oid(scene), oid(compScene));
		return new BaseRecord[] { comp, element };
	}

	/** Raw, unauthorized, uncached row-by-objectId lookup — the ground truth for "is it gone". */
	private static BaseRecord rawRow(String model, String objectId, long orgId) {
		Query q = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	private static String oid(BaseRecord rec) {
		return rec.get(FieldNames.FIELD_OBJECT_ID);
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

		// P4-13 (c2): the folded scene owns a CHAPBOOK_PAGE composition with an element row; the survivor owns
		// one too, which must be untouched by the merge.
		BaseRecord[] foldedPage = seedPageComposition(owner, book, folded);
		BaseRecord[] survivorPage = seedPageComposition(owner, book, absorbing);

		String survivorOid = ChapBookUtil.mergeSceneUp(owner, scene0Oid);
		assertEquals("mergeSceneUp returns the surviving (absorbing) scene objectId", scene0Oid, survivorOid);
		CacheUtil.clearCache();

		assertNull("the folded scene's page composition is deleted with it",
			rawRow(OlioModelNames.MODEL_PB_COMPOSITION, oid(foldedPage[0]), orgId));
		assertNull("the folded scene's element row is deleted with it (child before parent)",
			rawRow(OlioModelNames.MODEL_PB_SCENE_ELEMENT, oid(foldedPage[1]), orgId));
		assertNotNull("the survivor's page composition is untouched",
			rawRow(OlioModelNames.MODEL_PB_COMPOSITION, oid(survivorPage[0]), orgId));
		assertNotNull("the survivor's element row is untouched",
			rawRow(OlioModelNames.MODEL_PB_SCENE_ELEMENT, oid(survivorPage[1]), orgId));

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

		// P4-13 (c2): the removed scene owns a CHAPBOOK_PAGE composition with an element row; a sibling's must survive.
		BaseRecord[] removedPage = seedPageComposition(owner, book, before.get(0));
		BaseRecord[] siblingPage = seedPageComposition(owner, book, before.get(1));

		ChapBookUtil.deleteSceneAndReindex(owner, removedOid);
		CacheUtil.clearCache();

		assertNull("removed scene is deleted", PbBookUtil.readScene(owner, removedOid, orgId));
		assertNull("the removed scene's page composition is deleted with it",
			rawRow(OlioModelNames.MODEL_PB_COMPOSITION, oid(removedPage[0]), orgId));
		assertNull("the removed scene's element row is deleted with it",
			rawRow(OlioModelNames.MODEL_PB_SCENE_ELEMENT, oid(removedPage[1]), orgId));
		assertNotNull("the sibling's page composition survives",
			rawRow(OlioModelNames.MODEL_PB_COMPOSITION, oid(siblingPage[0]), orgId));
		assertNotNull("the sibling's element row survives",
			rawRow(OlioModelNames.MODEL_PB_SCENE_ELEMENT, oid(siblingPage[1]), orgId));

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

		// P4-13 (c2): both scenes carry a page composition, so each denied op is stopped at the composition
		// sweep (403, PBAC) before any scene write — and the sweep itself must not have torn anything down.
		BaseRecord[] page0 = seedPageComposition(owner, book, before.get(0));
		BaseRecord[] page1 = seedPageComposition(owner, book, before.get(1));

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
		// The book row additionally needs Read on its world (same fixture shape as TestPbCompositionRows):
		// without it readBook is DENIED and both ops stop at resolveSceneBook's 404, never reaching the
		// composition sweep — which is exactly what the earlier revision of this test was silently doing.
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
			o.getAdminUser(), denied, new BaseRecord[] { world },
			new String[] { "Read" }, new String[] { PermissionEnumType.DATA.toString() });
		CacheUtil.clearCache();

		// ── POSITIVE CONTROLS: the denied user CAN read scene0 AND the book via the Read grants ────────
		// If either is null the merge/delete calls would take the 404 not-found path, not the sweep's
		// DENY path — so assert it loudly rather than mistaking a 404 for an authorization denial.
		BaseRecord readByDenied = PbBookUtil.readScene(denied, scene0Oid, orgId);
		assertNotNull("Positive control: the read-only user must be able to READ scene0 (the Read grant "
			+ "is real), so merge/delete reach the write-DENY branch and not the 404 path", readByDenied);
		BaseRecord bookByDenied = PbBookUtil.readBook(denied, bookOid, orgId);
		assertNotNull("Positive control: the read-only user must be able to READ the book (group + world "
			+ "Read grants), else resolveSceneBook 404s before the composition sweep", bookByDenied);
		PolicyResponseType canDel = IOSystem.getActiveContext().getAuthorizationUtil().canDelete(denied, denied, page0[0]);
		assertFalse("Positive control: the read-only user must NOT be permitted to delete the page composition",
			canDel != null && canDel.getType() == PolicyResponseEnumType.PERMIT);

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
		for (BaseRecord[] page : new BaseRecord[][] { page0, page1 }) {
			assertNotNull("the page composition " + oid(page[0]) + " survives the denied ops",
				rawRow(OlioModelNames.MODEL_PB_COMPOSITION, oid(page[0]), orgId));
			assertNotNull("the element row " + oid(page[1]) + " survives the denied ops (the sweep is refused "
				+ "at its first row, not after)", rawRow(OlioModelNames.MODEL_PB_SCENE_ELEMENT, oid(page[1]), orgId));
		}

		// P4-13 (c2): with a page composition on the scene, the composition sweep runs first and PBAC's
		// refusal of its rows is surfaced as 403 (DeleteResult.authorized=false), not the 500 a refused
		// scene patch/delete still yields on a scene that has no compositions.
		assertEquals("denied merge is refused at the folded scene's composition sweep with 403", 403, mergeStatus);
		assertEquals("denied delete is refused at the scene's composition sweep with 403", 403, deleteStatus);
		logger.info("Scene-op denial statuses: merge={} delete={}", mergeStatus, deleteStatus);
	}
}
