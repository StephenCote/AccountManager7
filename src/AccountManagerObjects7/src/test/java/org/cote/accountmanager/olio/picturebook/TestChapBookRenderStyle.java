package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.objects.tests.SdTestGate;
import org.cote.accountmanager.olio.NarrativeUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.FileUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * PictureBook V3 Phase 0, slice C: what the ChapBook per-scene render path actually hands to
 * {@code SDUtil.createImage}.
 * <ul>
 *   <li><b>P0-9 (G-25)</b> - the style clause ({@code SDUtil.applyConfigStyle}) is appended to the
 *       positive prompt and a default negative prompt is supplied when the config carries none, so the
 *       style tier is no longer dead weight on the ChapBook path.</li>
 *   <li><b>P0-10b</b> - the client {@code sdConfig} is a tier BELOW the scene's {@code configOverride}
 *       and ABOVE the book tier, so a per-page override survives a client overlay.</li>
 * </ul>
 * Live against {@code am7db}; nothing is mocked. The capture tests hand the package-private
 * {@code renderChapBookScene(user, sceneOid, SDUtil, ...)} seam an {@link SDUtil} <i>subclass</i> that
 * records the config reaching {@code createImage} and returns no images (so the render reports
 * {@code FAILED} without touching a GPU); the one GPU test captures the same config and then delegates
 * to the real {@code createImage} on the live Swarm node from {@code test.swarm.server}.
 */
public class TestChapBookRenderStyle extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";
	private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47 };
	/// A render budget, not a threshold: timings are recorded, never asserted.
	private static final long RENDER_TIMEOUT_MS = 20L * 60L * 1000L;

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

	private BaseRecord testUser;

	@Before
	public void setUpChapBookStyle() {
		/// These cases assert the v1 SD block (client sdConfig overlay, synchronous image patch), which
		/// v3 replaces with a graph job that drops the overlay. BaseTest re-pins v3 on every @Before,
		/// so this cannot leak. v3 is covered by TestChapBookV3Render.
		PbRenderPath.configure(PbRenderPath.V3, PbRenderPath.V1);
		OrganizationContext ctx = getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookTestUser", ctx.getOrganizationId());
		assertNotNull("chapbookTestUser must be created", testUser);
	}

	// ─────────────────────────────── P0-9 ───────────────────────────────

	/**
	 * With no negative prompt anywhere in the tiers, the config reaching {@code createImage} carries
	 * the genuine stored prompt PLUS the style clause as its {@code description}, and
	 * {@code NarrativeUtil.getDefaultNegativePrompt()} as its {@code negativePrompt}. The stored scene
	 * prompt is not mutated (the clause is applied at render time, not persisted).
	 */
	@Test
	public void styleClauseAndDefaultNegativeReachCreateImage() throws Exception {
		Fixture fx = createFixture("style");
		String sceneOid = fx.sceneOids.get(0);
		patchScenePrompt(fx.scenes.get(0), GENUINE_PROMPT);

		CapturingSdu sdu = new CapturingSdu(swarmServer(), false);
		ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(
			testUser, sceneOid, sdu, null, null, null);
		assertNotNull("The seam must return a result", result);
		assertEquals("An empty createImage result is reported as FAILED (nothing rendered)",
			ChapBookUtil.SceneRenderStatus.FAILED, result.status);
		assertFalse("A genuine stored prompt rendered with chatConfig == null must not raise llmUnavailable",
			result.llmUnavailable);

		BaseRecord cfg = sdu.single();
		String description = cfg.get("description");
		assertNotNull("createImage must receive a description", description);
		assertTrue("The description must start with the genuine stored prompt, got: " + description,
			description.startsWith(GENUINE_PROMPT));
		String clause = SDUtil.getSDConfigPrompt(cfg);
		assertNotNull("The config must yield a style clause", clause);
		assertFalse("The style clause must not be blank", clause.isBlank());
		assertTrue("The description must carry the style clause '" + clause + "', got: " + description,
			description.contains(clause));
		assertFalse("A completed style clause must not contain the literal '(null)': " + clause, clause.contains("(null)"));
		assertEquals("applyConfigStyle must be idempotent on the description it produced",
			description, SDUtil.applyConfigStyle(description, cfg));
		assertEquals("The description must be exactly applyConfigStyle(prompt, cfg)",
			SDUtil.applyConfigStyle(GENUINE_PROMPT, cfg), description);

		String negative = cfg.get("negativePrompt");
		assertEquals("With no negative prompt in any tier, the default negative prompt must be supplied",
			NarrativeUtil.getDefaultNegativePrompt(), negative);

		BaseRecord stored = PbBookUtil.readScene(testUser, sceneOid, fx.orgId);
		assertEquals("The stored scene prompt must not be mutated by the render-time style clause",
			GENUINE_PROMPT, stored.get(OlioFieldNames.FIELD_CB_SD_PROMPT));
		logger.info("P0-9 description=" + description);
		logger.info("P0-9 negativePrompt=" + negative);
	}

	/**
	 * An explicitly configured negative prompt (here on the client overlay) is used verbatim; the
	 * default is only a fallback for a blank one.
	 */
	@Test
	public void explicitNegativePromptIsPreserved() throws Exception {
		Fixture fx = createFixture("neg");
		String sceneOid = fx.sceneOids.get(0);
		patchScenePrompt(fx.scenes.get(0), GENUINE_PROMPT);

		String myNegative = "text, watermark, lowres, p0-9-explicit-negative";
		BaseRecord overlay = sparse(new String[] { "negativePrompt" });
		overlay.setValue("negativePrompt", myNegative);

		CapturingSdu sdu = new CapturingSdu(swarmServer(), false);
		ChapBookUtil.renderChapBookScene(testUser, sceneOid, sdu, null, overlay, null);
		BaseRecord cfg = sdu.single();
		assertEquals("An explicit negative prompt must reach createImage unchanged", myNegative, cfg.get("negativePrompt"));
		assertTrue("The style clause must still be applied with an explicit negative prompt",
			((String) cfg.get("description")).contains(SDUtil.getSDConfigPrompt(cfg)));
	}

	// ─────────────────────────────── P0-10b ───────────────────────────────

	/**
	 * Tier order as rendered: scene {@code configOverride} &gt; client overlay &gt; book sdConfig &gt;
	 * schema defaults.
	 * <ul>
	 *   <li>scene 0 carries {@code configOverride steps=17}; the client overlay says {@code steps=23, cfg=4};
	 *       the book says {@code steps=31} - createImage must see {@code steps=17} (page override survives
	 *       the overlay) and {@code cfg=4} (overlay-only field wins over the book/default tiers).</li>
	 *   <li>scene 1 has no override; the same overlay yields {@code steps=23} (overlay beats book).</li>
	 *   <li>scene 1 with no overlay yields {@code steps=31} (the book tier is visible at all).</li>
	 * </ul>
	 */
	@Test
	public void pageOverrideSurvivesClientOverlay() throws Exception {
		Fixture fx = createFixture("tiers");
		assertTrue("Two scenes are needed, got " + fx.sceneOids.size(), fx.sceneOids.size() >= 2);
		patchScenePrompt(fx.scenes.get(0), GENUINE_PROMPT);
		patchScenePrompt(fx.scenes.get(1), GENUINE_PROMPT + ", second stanza");

		BaseRecord bookSparse = sparse(new String[] { "steps" });
		bookSparse.setValue("steps", 31);
		BaseRecord stored = ChapBookUtil.setBookSdConfig(testUser, fx.bookOid, bookSparse);
		assertNotNull("The book sdConfig must be stored", stored);
		assertEquals("The book tier must hold steps=31", Integer.valueOf(31), stored.get("steps"));

		assertTrue("The scene configOverride must be persisted", PbBookUtil.setSceneConfigOverride(
			testUser, fx.sceneOids.get(0), "{\"schema\":\"" + OlioModelNames.MODEL_SD_CONFIG + "\",\"steps\":17}"));

		BaseRecord overlay = sparse(new String[] { "steps", "cfg" });
		overlay.setValue("steps", 23);
		overlay.setValue("cfg", 4);

		CapturingSdu sdu = new CapturingSdu(swarmServer(), false);
		ChapBookUtil.renderChapBookScene(testUser, fx.sceneOids.get(0), sdu, null, overlay, null);
		BaseRecord withOverride = sdu.single();
		assertEquals("The scene configOverride must beat the client overlay", Integer.valueOf(17), withOverride.get("steps"));
		assertEquals("An overlay-only field must beat the book and schema tiers", Integer.valueOf(4), withOverride.get("cfg"));

		sdu = new CapturingSdu(swarmServer(), false);
		ChapBookUtil.renderChapBookScene(testUser, fx.sceneOids.get(1), sdu, null, overlay, null);
		BaseRecord overlayOnly = sdu.single();
		assertEquals("Without a scene override the client overlay must beat the book tier", Integer.valueOf(23), overlayOnly.get("steps"));

		sdu = new CapturingSdu(swarmServer(), false);
		ChapBookUtil.renderChapBookScene(testUser, fx.sceneOids.get(1), sdu, null, null, null);
		BaseRecord bookOnly = sdu.single();
		assertEquals("With neither override nor overlay the book tier must be visible", Integer.valueOf(31), bookOnly.get("steps"));
		assertEquals("The schema default cfg must be visible when no tier sets it", Integer.valueOf(7), bookOnly.get("cfg"));

		BaseRecord bookCfg = ChapBookUtil.getBookSdConfig(testUser, fx.bookOid);
		assertNotNull("The book sdConfig must still be readable", bookCfg);
		assertEquals("Rendering with an overlay must not mutate the stored book sdConfig", Integer.valueOf(31), bookCfg.get("steps"));
	}

	// ─────────────────────────────── live render ───────────────────────────────

	/**
	 * One real render on the live Swarm node through the same seam, capturing the config AND
	 * delegating to the real {@code createImage}: the styled prompt and default negative prompt are the
	 * ones that actually produced the PNG written to {@code target/chapbook-v3-p0-9-style.png}.
	 */
	@Test(timeout = RENDER_TIMEOUT_MS)
	public void liveRenderUsesStyledPromptAndDefaultNegative() throws Exception {
		String swarmServer = swarmServer();
		BaseRecord renderConfig = SdTestGate.requireRenderConfig(testProperties, swarmServer, "ChapBook V3 P0-9 style render");

		Fixture fx = createFixture("live");
		String sceneOid = fx.sceneOids.get(0);
		patchScenePrompt(fx.scenes.get(0), GENUINE_PROMPT);

		CapturingSdu sdu = new CapturingSdu(swarmServer, true);
		long t0 = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(
			testUser, sceneOid, sdu, null, renderConfig, null);
		long durationMs = System.currentTimeMillis() - t0;
		logger.info("P0-9 live render durationMs=" + durationMs);

		assertNotNull("The live render must return a result", result);
		assertEquals("The live render must RENDER (status=" + result.status + ")",
			ChapBookUtil.SceneRenderStatus.RENDERED, result.status);
		assertNotNull("A RENDERED result must carry an imageObjectId", result.imageObjectId);

		BaseRecord cfg = sdu.single();
		String description = cfg.get("description");
		assertTrue("The live prompt must start with the genuine stored prompt, got: " + description,
			description.startsWith(GENUINE_PROMPT));
		assertTrue("The live prompt must carry the style clause, got: " + description,
			description.contains(SDUtil.getSDConfigPrompt(cfg)));
		assertEquals("The live render must use the default negative prompt",
			NarrativeUtil.getDefaultNegativePrompt(), cfg.get("negativePrompt"));
		String expectedModel = renderConfig.get(OlioFieldNames.FIELD_SD_MODEL);
		String liveModel = cfg.get(OlioFieldNames.FIELD_SD_MODEL);
		assertEquals("The live render must use the installed checkpoint", expectedModel, liveModel);

		BaseRecord data = freshReadData(result.imageObjectId, fx.orgId);
		assertNotNull("The rendered image record must be readable", data);
		byte[] bytes = ByteModelUtil.getValue(data);
		assertNotNull("The rendered image must have bytes", bytes);
		assertTrue("The rendered image must be larger than a PNG header (was " + bytes.length + ")", bytes.length > 1024);
		for (int i = 0; i < PNG_MAGIC.length; i++) {
			assertEquals("PNG magic byte " + i, PNG_MAGIC[i], bytes[i]);
		}
		File out = new File("target/chapbook-v3-p0-9-style.png");
		FileUtil.emitFile(out.getPath(), bytes);
		assertTrue("The PNG must have been written to " + out.getAbsolutePath(), out.exists() && out.length() == bytes.length);
		logger.info("P0-9 PNG written: " + out.getAbsolutePath() + " (" + bytes.length + " bytes)");
		logger.info("P0-9 live description=" + description);
	}

	// ─────────────────────────────── helpers ───────────────────────────────

	/** Records every config reaching {@code createImage}; renders for real only when {@code delegate}. */
	private static final class CapturingSdu extends SDUtil {
		final List<BaseRecord> captured = new ArrayList<>();
		private final boolean delegate;

		CapturingSdu(String server, boolean delegate) {
			super(SDAPIEnumType.SWARM, server);
			this.delegate = delegate;
		}

		@Override
		public List<BaseRecord> createImage(BaseRecord user, String groupPath, BaseRecord sdConfig, String name, int batch, boolean hires, int seed) {
			captured.add(sdConfig);
			if (delegate) {
				return super.createImage(user, groupPath, sdConfig, name, batch, hires, seed);
			}
			return new ArrayList<>();
		}

		BaseRecord single() {
			assertEquals("createImage must be called exactly once", 1, captured.size());
			return captured.get(0);
		}
	}

	private static final class Fixture {
		String bookOid;
		long orgId;
		List<BaseRecord> scenes;
		List<String> sceneOids = new ArrayList<>();
	}

	/** A fresh two-scene ChapBook created WITHOUT a chatConfig (scenes carry the fallback prompt shape). */
	private Fixture createFixture(String tag) {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);
		Fixture fx = new Fixture();
		fx.orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		long ts = System.currentTimeMillis();

		BaseRecord poem = createPoem(testUser, "~/Data/ChapBookStyle-" + tag + "-" + ts, "Poem Style " + tag + " " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem.get(FieldNames.FIELD_OBJECT_ID));

		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, "cb-style-" + tag + "-" + ts,
			"ChapBook Style " + tag + " " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		fx.bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", fx.bookOid);
		BaseRecord bookCheck = reloadBook(fx.bookOid, fx.orgId);
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
		assertTrue("The patched prompt must read back as genuine",
			ChapBookUtil.isGenuineStoredPrompt(prompt));
	}

	private BaseRecord reloadBook(String bookObjectId, long orgId) {
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

	private BaseRecord freshReadData(String objectId, long orgId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, objectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_ATTRIBUTES,
			FieldNames.FIELD_BYTE_STORE, FieldNames.FIELD_DATA_HASH, FieldNames.FIELD_SIZE,
			FieldNames.FIELD_COMPRESSION_TYPE, FieldNames.FIELD_CONTENT_TYPE, FieldNames.FIELD_ENCIPHERED,
			FieldNames.FIELD_VAULTED, FieldNames.FIELD_KEY_ID, FieldNames.FIELD_VAULT_ID });
		q.setCache(false);
		return IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
	}
}
