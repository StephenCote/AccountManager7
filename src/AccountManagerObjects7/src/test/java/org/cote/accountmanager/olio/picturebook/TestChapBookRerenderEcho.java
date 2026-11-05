package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbCompositionPurposeEnumType;
import org.cote.accountmanager.schema.type.PbCompositionSourceEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * V3 Phase 10, P10-C 1: a per-page re-render whose {@code promptOverride} merely ECHOES the page's stored
 * prompt is not a human edit. Before this fix the review card sent its textarea back on every re-render and
 * {@code renderChapBookScene} took the echo for an edit: it was persisted as a USER-sourced, locked page
 * composition - including the stanza-free {@code "landscape, <title>, …"} placeholder written when the LLM
 * failed at create time - after which the lock protected the placeholder from recovery for good, with no
 * signal (a permanently random landscape). Now {@link ChapBookUtil#isStoredScenePrompt} drops the echo and
 * the stored prompt goes through the normal pre-step.
 * <p>
 * Live {@code am7db}, {@code chapbookTestUser} in {@code /Development/ChapBook Tests}, a real ChapBook from
 * Stephen's poem created WITHOUT a chatConfig (so every page starts on the placeholder). ChapBooks are flipped
 * to v1 for the render calls ({@code PbRenderPath.configure(V3, V1)}, the {@code TestChapBookPageComposition}
 * precedent) with a capturing {@link SDUtil} that returns no image, so the exact positive prompt that reaches
 * the renderer is asserted with no live SD; the decision under test lives in the pre-step both paths share.
 * The one chatConfig used points at an unreachable host, so a recovery that should NOT run would show up as
 * {@code llmUnavailable=true}. No live LLM is touched.
 */
public class TestChapBookRerenderEcho extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";
	/// A budget, never a threshold: fixture creation and four renders get ample room and fail only on error.
	private static final long LONG_TIMEOUT_MS = 20L * 60L * 1000L;
	private static final String DEAD_SERVER = "http://127.0.0.1:1";

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

	private static final String GENUINE_PROMPT =
		"a serene alpine lake at dawn, wide natural vista, soft diffuse light, snow on distant peaks";
	private static final String EDIT_PROMPT =
		"a warm cottage hearth at dusk, ochre embers, wide natural vista through a frosted window";

	private OrganizationContext orgCtx;
	private BaseRecord testUser;

	@Before
	public void setUpRerenderEcho() {
		OlioModelNames.use();
		orgCtx = getTestOrganization(ORG_PATH);
		assertNotNull("organization " + ORG_PATH, orgCtx);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(orgCtx.getAdminUser(), "chapbookTestUser", orgCtx.getOrganizationId());
		assertNotNull("chapbookTestUser must be created", testUser);
	}

	/**
	 * The reproduction (C2 of P10-C 1). Page 0 carries the create-time placeholder, unlocked, with no page
	 * composition. Re-rendering it with the placeholder echoed back as the override and no chatConfig (what
	 * the review card used to send) must NOT lock the placeholder in: no composition is created, the scene
	 * stays unlocked with the same text, and because nothing could recover a real prompt the render is the
	 * degrade path - the placeholder reaches the renderer and the result carries {@code llmUnavailable=true},
	 * the honest signal the card turns into a warning. Before the fix the same call wrote a USER-locked
	 * composition and reported nothing.
	 */
	@Test(timeout = LONG_TIMEOUT_MS)
	public void echoOfThePlaceholderIsNotAnEditAndDegradesHonestly() throws Exception {
		Fixture fx = createFixture("echo-fallback");
		String sceneOid = fx.sceneOids.get(0);
		BaseRecord scene0 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		String stored = scene0.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertNotNull("precondition: the page carries the create-time placeholder", stored);
		assertFalse("precondition: the placeholder is the fallback shape: " + stored, ChapBookUtil.isGenuineStoredPrompt(stored));
		assertNotEquals("precondition: unlocked", Boolean.TRUE, scene0.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertNull("precondition: no page composition", findComposition(fx, scene0));
		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		assertTrue("the echo is recognised against the scene mirror", ChapBookUtil.isStoredScenePrompt(testUser, book, scene0, "  " + stored + " "));
		assertFalse("a blank is never an echo", ChapBookUtil.isStoredScenePrompt(testUser, book, scene0, "   "));
		assertFalse("a different text is not an echo", ChapBookUtil.isStoredScenePrompt(testUser, book, scene0, EDIT_PROMPT));

		List<BaseRecord> captured = new ArrayList<>();
		ChapBookUtil.SceneRenderResult result = renderOnV1(sceneOid, capturing(captured), null, stored);
		logger.info("[p10c1] placeholder echo status=" + result.status + " llmUnavailable=" + result.llmUnavailable
			+ " llmDegraded=" + result.llmDegraded + " captured=" + captured.size());
		assertEquals("the renderer was called once (degrade-render on the stored placeholder)", 1, captured.size());
		assertTrue("the positive prompt is the stored placeholder: " + captured.get(0).get("description"),
			String.valueOf((Object) captured.get(0).get("description")).startsWith(stored));
		assertTrue("the degrade path reports llmUnavailable (no chatConfig, so nothing could recover a prompt)", result.llmUnavailable);
		/// llmDegraded is "the scene STILL rendered, on the stored prompt" (SceneRenderResult): the capturing
		/// renderer returns no image, so the result is FAILED and by contract carries llmDegraded=false. The
		/// degrade path itself is proven above: the placeholder reached the renderer with no chatConfig.
		assertEquals("the capture returns no image, so FAILED - never RENDERED on a silent lock-in",
			ChapBookUtil.SceneRenderStatus.FAILED, result.status);
		assertFalse("a FAILED result never claims a degraded render", result.llmDegraded);

		BaseRecord after = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("the scene text is unchanged", stored, after.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertNotEquals("the echo did NOT lock the page", Boolean.TRUE, after.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertNull("the echo did NOT create a page composition", findComposition(fx, after));
	}

	/**
	 * An echo of a GENUINE, unlocked stored prompt (what the card sends for a page whose create-time LLM
	 * prompt was never edited): rendered verbatim through the prefer-stored branch, recovery never runs
	 * (the unreachable chatConfig would otherwise report {@code llmUnavailable=true}), and the provenance
	 * is left alone - no USER-locked composition is written for a prompt the LLM wrote.
	 */
	@Test(timeout = LONG_TIMEOUT_MS)
	public void echoOfAGenuineUnlockedPromptRendersItWithoutLocking() throws Exception {
		Fixture fx = createFixture("echo-genuine");
		String sceneOid = fx.sceneOids.get(0);
		BaseRecord scene0 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		/// Give the page a genuine prompt the way the LLM would have left it: scene text only, unlocked, no composition.
		BaseRecord patch = PbGraphUtil.patchOf(scene0, OlioModelNames.MODEL_PB_SCENE,
			OlioFieldNames.FIELD_CB_SD_PROMPT, OlioFieldNames.FIELD_PB_PROMPT_LOCKED);
		patch.set(OlioFieldNames.FIELD_CB_SD_PROMPT, GENUINE_PROMPT);
		patch.set(OlioFieldNames.FIELD_PB_PROMPT_LOCKED, Boolean.FALSE);
		assertNotNull("genuine-prompt patch", IOSystem.getActiveContext().getAccessPoint().update(testUser, patch));
		BaseRecord scene1 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals(GENUINE_PROMPT, scene1.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertTrue(ChapBookUtil.isGenuineStoredPrompt(GENUINE_PROMPT));
		assertNull("precondition: no page composition", findComposition(fx, scene1));

		BaseRecord deadLlm = OlioTestUtil.getUnreachableOllamaConfig(testUser, "chapbookP10C1DeadLlm", DEAD_SERVER, "qwen3:8b", 5);
		assertNotNull("the unreachable chatConfig must be built", deadLlm);
		List<BaseRecord> captured = new ArrayList<>();
		ChapBookUtil.SceneRenderResult result = renderOnV1(sceneOid, capturing(captured), deadLlm, GENUINE_PROMPT);
		logger.info("[p10c1] genuine echo status=" + result.status + " llmUnavailable=" + result.llmUnavailable
			+ " llmDegraded=" + result.llmDegraded + " captured=" + captured.size());
		assertEquals("rendered once", 1, captured.size());
		assertTrue("the positive prompt is the genuine stored prompt, verbatim: " + captured.get(0).get("description"),
			String.valueOf((Object) captured.get(0).get("description")).startsWith(GENUINE_PROMPT));
		assertFalse("recovery never ran (a genuine stored prompt is preferred)", result.llmUnavailable);
		assertFalse("nothing was degraded", result.llmDegraded);

		BaseRecord after = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals(GENUINE_PROMPT, after.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertNotEquals("the echo did NOT lock the page", Boolean.TRUE, after.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertNull("the echo did NOT create a page composition", findComposition(fx, after));
	}

	/**
	 * An echo of a prompt the user DID save earlier (a USER-locked page composition) is still honoured
	 * verbatim - the lock is the authority - but it is not re-written: the composition's revision and source
	 * do not move. Before the fix every re-render bumped the revision.
	 */
	@Test(timeout = LONG_TIMEOUT_MS)
	public void echoOfALockedCompositionPromptIsHonouredWithoutARewrite() throws Exception {
		Fixture fx = createFixture("echo-locked");
		String sceneOid = fx.sceneOids.get(0);
		assertTrue(ChapBookUtil.setSceneLandscapePrompt(testUser, sceneOid, EDIT_PROMPT));
		BaseRecord scene1 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		BaseRecord comp1 = findComposition(fx, scene1);
		assertNotNull("precondition: the save created the page composition", comp1);
		String compOid = comp1.get(FieldNames.FIELD_OBJECT_ID);
		comp1 = PbComposeUtil.readComposition(testUser, compOid, fx.orgId);
		int rev1 = intOf(comp1.get(OlioFieldNames.FIELD_PB_REVISION));
		assertEquals(PbCompositionSourceEnumType.USER.name(), status(comp1, OlioFieldNames.FIELD_PB_SOURCE));
		assertEquals(Boolean.TRUE, comp1.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		assertTrue("the echo is recognised against the composition too", ChapBookUtil.isStoredScenePrompt(testUser, book, scene1, EDIT_PROMPT));

		List<BaseRecord> captured = new ArrayList<>();
		ChapBookUtil.SceneRenderResult result = renderOnV1(sceneOid, capturing(captured), null, EDIT_PROMPT);
		logger.info("[p10c1] locked echo status=" + result.status + " llmUnavailable=" + result.llmUnavailable
			+ " llmDegraded=" + result.llmDegraded + " captured=" + captured.size());
		assertEquals("rendered once", 1, captured.size());
		assertTrue("the positive prompt is the locked prompt, verbatim: " + captured.get(0).get("description"),
			String.valueOf((Object) captured.get(0).get("description")).startsWith(EDIT_PROMPT));
		assertFalse("a locked prompt is never degraded", result.llmDegraded);

		BaseRecord comp2 = PbComposeUtil.readComposition(testUser, compOid, fx.orgId);
		assertEquals("the composition was not re-written: same revision", rev1, intOf(comp2.get(OlioFieldNames.FIELD_PB_REVISION)));
		assertEquals("still USER", PbCompositionSourceEnumType.USER.name(), status(comp2, OlioFieldNames.FIELD_PB_SOURCE));
		assertEquals("still locked", Boolean.TRUE, comp2.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertEquals(EDIT_PROMPT, comp2.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
	}

	/**
	 * The contrast: an override that DIFFERS from the stored text is a real edit and keeps the existing
	 * contract - persisted first as a USER-sourced, locked page composition, mirrored onto the scene, and
	 * rendered verbatim. The echo rule is an equality test, so an edit shaped like the placeholder
	 * ({@code "landscape, …"}) is honoured as an edit too.
	 */
	@Test(timeout = LONG_TIMEOUT_MS)
	public void aDifferentPromptIsStillAnEditAndLocks() throws Exception {
		Fixture fx = createFixture("edit");
		String sceneOid = fx.sceneOids.get(0);
		BaseRecord scene0 = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		String stored = scene0.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertNotNull(stored);
		String edit = "landscape, " + EDIT_PROMPT;
		assertNotEquals(stored.trim(), edit);
		assertFalse("the edit is deliberately placeholder-shaped", ChapBookUtil.isGenuineStoredPrompt(edit));
		assertNull("precondition: no page composition", findComposition(fx, scene0));

		List<BaseRecord> captured = new ArrayList<>();
		ChapBookUtil.SceneRenderResult result = renderOnV1(sceneOid, capturing(captured), null, edit);
		logger.info("[p10c1] edit status=" + result.status + " llmDegraded=" + result.llmDegraded + " captured=" + captured.size());
		assertEquals("rendered once", 1, captured.size());
		assertTrue("the positive prompt is the edit, verbatim: " + captured.get(0).get("description"),
			String.valueOf((Object) captured.get(0).get("description")).startsWith(edit));
		assertFalse("an explicit edit is never degraded", result.llmDegraded);

		BaseRecord after = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("the edit is the scene text", edit, after.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		assertEquals("the edit locked the page", Boolean.TRUE, after.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		BaseRecord comp = findComposition(fx, after);
		assertNotNull("the edit created the page composition", comp);
		comp = PbComposeUtil.readComposition(testUser, comp.get(FieldNames.FIELD_OBJECT_ID), fx.orgId);
		assertEquals(edit, comp.get(OlioFieldNames.FIELD_PB_PROMPT_OVERRIDE));
		assertEquals(Boolean.TRUE, comp.get(OlioFieldNames.FIELD_PB_PROMPT_LOCKED));
		assertEquals(PbCompositionSourceEnumType.USER.name(), status(comp, OlioFieldNames.FIELD_PB_SOURCE));
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	/** A capturing renderer: records the SD config it is handed and returns no image. */
	private static SDUtil capturing(List<BaseRecord> captured) {
		return new SDUtil(SDAPIEnumType.SWARM, DEAD_SERVER) {
			@Override
			public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name, int batch, boolean hires, int seed) {
				captured.add(sdConfig);
				return new ArrayList<>();
			}
		};
	}

	/** One per-scene render with ChapBooks pinned to v1 for the call (restored afterwards). */
	private ChapBookUtil.SceneRenderResult renderOnV1(String sceneOid, SDUtil sdu, BaseRecord chatConfig, String promptOverride) {
		String savedPb = PbRenderPath.pictureBook();
		String savedCb = PbRenderPath.chapBook();
		try {
			PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V1);
			return ChapBookUtil.renderChapBookScene(testUser, sceneOid, sdu, chatConfig, null, promptOverride);
		}
		finally {
			PbRenderPath.configure(savedPb, savedCb);
		}
	}

	private BaseRecord findComposition(Fixture fx, BaseRecord scene) {
		BaseRecord book = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		long groupId = ((Number) book.get(FieldNames.FIELD_GROUP_ID)).longValue();
		return PbComposeUtil.findSceneComposition(testUser, scene, groupId, fx.orgId, PbCompositionPurposeEnumType.CHAPBOOK_PAGE);
	}

	private static final class Fixture {
		String bookOid;
		long orgId;
		List<String> sceneOids = new ArrayList<>();
	}

	/** A fresh ChapBook from Stephen's poem, created WITHOUT a chatConfig (pages carry the placeholder prompt). */
	private Fixture createFixture(String tag) {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		Fixture fx = new Fixture();
		fx.orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();
		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookP10C1-" + tag + "-" + ts, "Poem P10C1 " + tag + " " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-p10c1-" + tag + "-" + ts,
			"ChapBook P10C1 " + tag + " " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		fx.bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord bookCheck = PbBookUtil.readBook(testUser, fx.bookOid, fx.orgId);
		assertNotNull("Book must be readable", bookCheck);
		assertTrue("the fixture is a ChapBook", PbNodeExecutor.isChapBook(bookCheck));
		for(BaseRecord s : PbBookUtil.listScenes(testUser, bookCheck)) {
			fx.sceneOids.add(s.get(FieldNames.FIELD_OBJECT_ID));
		}
		assertTrue("At least one page must be created", fx.sceneOids.size() >= 1);
		logger.info("[p10c1] fixture " + tag + " book=" + fx.bookOid + " pages=" + fx.sceneOids.size());
		return fx;
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
		catch(Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}

	private static String status(BaseRecord rec, String field) {
		Object v = (rec != null ? rec.get(field) : null);
		return (v != null ? String.valueOf(v).toUpperCase() : null);
	}

	private static int intOf(Object o) {
		assertTrue("a number but was " + o, o instanceof Number);
		return ((Number) o).intValue();
	}
}
