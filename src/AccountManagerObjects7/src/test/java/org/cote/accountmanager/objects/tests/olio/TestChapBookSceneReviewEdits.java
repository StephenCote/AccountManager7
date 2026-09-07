package org.cote.accountmanager.objects.tests.olio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.junit.Before;
import org.junit.Test;

/**
 * DB-backed integration test for the ChapBook scene-review edits added in this slice:
 * {@link ChapBookUtil#mergeSceneUp(BaseRecord, String)} and
 * {@link ChapBookUtil#deleteSceneAndReindex(BaseRecord, String)}.
 *
 * <p>No LLM and no SD server are required: the ChapBook is created with a {@code null} chatConfig, so
 * {@code createChapBookScene} takes its no-LLM fallback (a stanza-title landscape placeholder) for the
 * {@code sdPrompt}, and neither method under test touches the SD/LLM path. The test therefore runs on
 * the live Postgres DB alone.
 *
 * <p><b>Discipline:</b> the acting user is a fresh non-admin ({@code Factory.getCreateUser}) — never the
 * admin user — and every write is performed by that user through {@code AccessPoint}. Every assertion
 * re-queries the live DB with a fresh, uncached {@code Query} ({@code PbBookUtil.listScenes} /
 * {@code readScene} both set {@code cache:false}) rather than trusting an in-memory reference from the
 * call that mutated the data.
 */
public class TestChapBookSceneReviewEdits extends BaseTest {

	/**
	 * Reuse the same stable org the other ChapBook suites use so the (per-organization, minutes-on-first-use)
	 * Olio Books-universe seed is amortized across runs rather than paid again for a random org.
	 */
	private static final String ORG_PATH = "/Development/ChapBook Tests";

	/**
	 * A real public-domain poem — William Blake, "The Tyger" (first four stanzas), verbatim. Four distinct
	 * four-line stanzas separated by single blank lines, so {@code chunkPoem(text, 4)} yields exactly four
	 * per-stanza chunks → four scenes (each stanza is multi-line, so the single-line "Tika paragraph"
	 * re-chunking heuristic does not fire).
	 */
	private static final String POEM_TEXT =
		"Tyger Tyger, burning bright,\n" +
		"In the forests of the night;\n" +
		"What immortal hand or eye,\n" +
		"Could frame thy fearful symmetry?\n" +
		"\n" +
		"In what distant deeps or skies.\n" +
		"Burnt the fire of thine eyes?\n" +
		"On what wings dare he aspire?\n" +
		"What the hand, dare seize the fire?\n" +
		"\n" +
		"And what shoulder, & what art,\n" +
		"Could twist the sinews of thy heart?\n" +
		"And when thy heart began to beat,\n" +
		"What dread hand? & what dread feet?\n" +
		"\n" +
		"What the hammer? what the chain,\n" +
		"In what furnace was thy brain?\n" +
		"What the anvil? what dread grasp,\n" +
		"Dare its deadly terrors clasp?";

	private BaseRecord testUser;
	private long orgId;

	@Before
	public void setUpReviewEdits() {
		// BaseTest.setup() is @Before too and runs first; OlioModelNames.use() is called there.
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		orgId = ctx.getOrganizationId();
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "cbReviewEditsUser", orgId);
		assertNotNull("cbReviewEditsUser must be created", testUser);
		assertFalse("Actor must not be the admin user", "admin".equals(testUser.get(FieldNames.FIELD_NAME)));
	}

	/**
	 * Full lifecycle in one test (the ChapBook fixture is expensive to build — world + universe + grants —
	 * so both operations under test share it):
	 * <ol>
	 *   <li>Create a ChapBook of 4 scenes from one 4-stanza poem (no LLM).</li>
	 *   <li>{@code mergeSceneUp} the scene at index 1 → it absorbs the scene at index 2; assert the merged
	 *       stanza, {@code imageStale=true}, cleared {@code sdPrompt}, the absorbed scene gone, and gap-free
	 *       {@code sceneIndex} 0..n-1 on the 3 survivors.</li>
	 *   <li>{@code deleteSceneAndReindex} the first survivor → assert it is gone and the remaining 2 scenes
	 *       are re-indexed 0..n-1.</li>
	 * </ol>
	 */
	@Test
	public void testMergeSceneUpAndDeleteReindex() {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set for ChapBook world creation", dataPath);

		long ts = System.currentTimeMillis();
		String slug = "cb-review-" + ts;
		String title = "ChapBook Review Edits " + ts;
		String poemPath = "~/Data/ChapBookReview-" + ts;

		// ── 1. Create the poem and the ChapBook (null chatConfig = no LLM) ──────────────────
		BaseRecord poem = createPoem(testUser, poemPath, "The Tyger " + ts, POEM_TEXT);
		assertNotNull("Poem should be created", poem);
		String poemOid = poem.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem must have an objectId", poemOid);

		List<String> poemOids = new ArrayList<>();
		poemOids.add(poemOid);

		// maxLinesPerPage = 4 → one scene per 4-line stanza → 4 scenes.
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, slug, title, poemOids, 4, null);
		assertNotNull("createChapBook must return a book record", book);

		List<BaseRecord> scenes0 = PbBookUtil.listScenes(testUser, book);
		assertTrue("Need >= 3 scenes for the merge/delete test (got " + scenes0.size() + ")", scenes0.size() >= 3);
		assertContiguousIndices("after create", scenes0);

		// Capture pre-merge identity + stanza text for the scenes at index 1 (absorbing) and 2 (absorbed).
		String s1Oid = scenes0.get(1).get(FieldNames.FIELD_OBJECT_ID);
		String s2Oid = scenes0.get(2).get(FieldNames.FIELD_OBJECT_ID);
		String s1Stanza = scenes0.get(1).get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		String s2Stanza = scenes0.get(2).get(OlioFieldNames.FIELD_CB_POEM_STANZA);
		assertNotNull("Absorbing scene must carry a poemStanza", s1Stanza);
		assertNotNull("Absorbed scene must carry a poemStanza", s2Stanza);
		// No-LLM create stores a fallback sdPrompt; confirm it is present so "cleared after merge" is meaningful.
		String s1PromptBefore = scenes0.get(1).get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertTrue("Absorbing scene should have a create-time fallback sdPrompt before merge",
			s1PromptBefore != null && !s1PromptBefore.isBlank());
		int nBeforeMerge = scenes0.size();

		// ── 2. mergeSceneUp: scene at index 1 absorbs scene at index 2 ──────────────────────
		String survivingOid = ChapBookUtil.mergeSceneUp(testUser, s1Oid);
		assertEquals("mergeSceneUp must return the absorbing scene's objectId", s1Oid, survivingOid);

		List<BaseRecord> afterMerge = PbBookUtil.listScenes(testUser, book);
		assertEquals("Merge must remove exactly one scene", nBeforeMerge - 1, afterMerge.size());

		// The absorbed scene is gone.
		for (BaseRecord s : afterMerge) {
			assertNotEquals("Absorbed scene must be deleted", s2Oid, s.get(FieldNames.FIELD_OBJECT_ID));
		}

		// The survivor carries the merged stanza + the merge flags, read back fresh (uncached).
		BaseRecord merged = PbBookUtil.readScene(testUser, s1Oid, orgId);
		assertNotNull("Surviving scene must still be readable", merged);
		assertEquals("Merged stanza must be old-N + \"\\n\" + old-next",
			s1Stanza + "\n" + s2Stanza, merged.get(OlioFieldNames.FIELD_CB_POEM_STANZA));
		assertEquals("imageStale must be true after a merge",
			Boolean.TRUE, merged.get(OlioFieldNames.FIELD_PB_IMAGE_STALE));
		String mergedPrompt = merged.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertTrue("sdPrompt must be cleared after merge (was: " + mergedPrompt + ")",
			mergedPrompt == null || mergedPrompt.isBlank());
		Object mergedLocked = merged.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
		assertTrue("promptLocked must be false/unset after merge (was: " + mergedLocked + ")",
			mergedLocked == null || Boolean.FALSE.equals(mergedLocked));

		// Survivors are re-indexed to a clean, gap-free 0..n-1 (the old index-3 scene moved to 2).
		assertContiguousIndices("after merge-up", afterMerge);

		// ── 3. deleteSceneAndReindex: remove the first survivor ─────────────────────────────
		String deleteOid = afterMerge.get(0).get(FieldNames.FIELD_OBJECT_ID);
		int nBeforeDelete = afterMerge.size();

		ChapBookUtil.deleteSceneAndReindex(testUser, deleteOid);

		List<BaseRecord> afterDelete = PbBookUtil.listScenes(testUser, book);
		assertEquals("Delete must remove exactly one scene", nBeforeDelete - 1, afterDelete.size());
		for (BaseRecord s : afterDelete) {
			assertNotEquals("Deleted scene must be gone", deleteOid, s.get(FieldNames.FIELD_OBJECT_ID));
		}
		assertContiguousIndices("after delete", afterDelete);

		logger.info("TestChapBookSceneReviewEdits: create={} scenes -> merge={} -> delete={} scenes; indices clean throughout",
			nBeforeMerge, afterMerge.size(), afterDelete.size());
	}

	/** Assert the scenes (already ascending by sceneIndex from {@code listScenes}) are numbered 0..n-1 with no gaps. */
	private void assertContiguousIndices(String phase, List<BaseRecord> scenes) {
		for (int i = 0; i < scenes.size(); i++) {
			Integer idx = scenes.get(i).get(OlioFieldNames.FIELD_PB_SCENE_INDEX);
			assertNotNull("[" + phase + "] scene at position " + i + " must have a sceneIndex", idx);
			assertEquals("[" + phase + "] sceneIndex must be gap-free 0..n-1", i, idx.intValue());
		}
	}

	/**
	 * Create an {@code olio.cb.poem} at the given group path with the given name/text, as the acting user
	 * through {@code AccessPoint.create}. Mirrors the helper in {@code TestChapBook}.
	 */
	private BaseRecord createPoem(BaseRecord user, String groupPath, String name, String text) {
		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, groupPath);
			plist.parameter(FieldNames.FIELD_NAME, name);
			BaseRecord poem = IOSystem.getActiveContext().getFactory()
				.newInstance(OlioModelNames.MODEL_CB_POEM, user, null, plist);
			poem.set("text", text);
			poem.set("title", name);
			return IOSystem.getActiveContext().getAccessPoint().create(user, poem);
		}
		catch (Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}
}
