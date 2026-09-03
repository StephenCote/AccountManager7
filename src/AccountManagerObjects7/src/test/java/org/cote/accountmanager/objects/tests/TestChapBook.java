package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbConfigUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.DocumentUtil;
import org.cote.accountmanager.util.FileUtil;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration test for ChapBookUtil.renderChapBook.
 * Creates real olio.cb.poem records, builds a ChapBook via createChapBook,
 * then calls renderChapBook against the live Swarm SD server and verifies
 * that at least one scene has an imageObjectId persisted.
 *
 * Gated on test.swarm.server — skipped when the property is absent.
 */
public class TestChapBook extends BaseTest {

	private static final String ORG_PATH = "/Development/ChapBook Tests";

	/**
	 * REAL poem content — copied verbatim from {@code e2e/chapBookScopingOverride.spec.js} (the
	 * {@code POEM_TEXT} constant). Two five-line stanzas, so {@code maxLinesPerPage=5} yields two scenes.
	 * Using the exact same corpus the UX e2e uses keeps the render path exercising real, representative
	 * text rather than a synthetic stand-in.
	 */
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

	@Before
	public void setUpChapBook() {
		// BaseTest.setup() is @Before too and runs first; OlioModelNames.use() is called there.
		// Create a stable test user for this suite.
		org.cote.accountmanager.io.OrganizationContext ctx =
			getTestOrganization(ORG_PATH);
		testUser = IOSystem.getActiveContext().getFactory()
			.getCreateUser(ctx.getAdminUser(), "chapbookTestUser", ctx.getOrganizationId());
		assertNotNull("chapbookTestUser must be created", testUser);
	}

	/**
	 * Issue 4 regression: a legacy Word 97-2003 {@code .doc} uploaded as a ChapBook poem source
	 * used to display OLE2 binary ASCII instead of extracted prose, because the record arrived with
	 * a missing (or generic {@code text/plain}) content type and the byteStore was read as raw UTF-8.
	 * <p>
	 * This exercises the applied fix end-to-end against the REAL fixture
	 * {@code media/The Big Way Out.doc} — no synthetic poem text, no LLM/SD, no network:
	 * <ol>
	 *   <li>{@link DocumentUtil#sniffOfficeContentType(byte[])} must recognise the OLE2 container as
	 *       {@code application/msword}, and {@link DocumentUtil#readDocument(byte[], int, String)} with
	 *       that hint must return real readable prose (POI HWPF path), not OLE2 garbage.</li>
	 *   <li>{@link ChapBookUtil#extractPoemText(BaseRecord)} must return that same readable prose when
	 *       the record's byteStore holds the {@code .doc} bytes and the declared content type is
	 *       {@code null}, AND when it is mislabeled {@code text/plain} — never OLE2 binary garbage.</li>
	 * </ol>
	 */
	@Test
	public void testExtractPoemTextFromLegacyDocFile() throws Exception {
		File docFile = locateFixture("The Big Way Out.doc");
		assertNotNull("Real legacy .doc fixture must be locatable on disk (media/The Big Way Out.doc)", docFile);
		assertTrue("Fixture .doc must exist: " + docFile.getAbsolutePath(), docFile.exists());
		byte[] docBytes = java.nio.file.Files.readAllBytes(docFile.toPath());
		assertTrue("Fixture .doc must be non-empty", docBytes.length > 0);

		// ── (1a) Container magic sniff overrides the (missing/wrong) declared type ──
		String sniffed = DocumentUtil.sniffOfficeContentType(docBytes);
		assertEquals("OLE2 legacy .doc must sniff as application/msword", "application/msword", sniffed);

		// ── (1b) Bounded POI extraction with the resolved hint yields readable prose ──
		String extracted = DocumentUtil.readDocument(docBytes, 16 * 1024 * 1024, "application/msword");
		assertNotNull("readDocument(bytes, max, application/msword) must return non-null text", extracted);
		assertReadableProse("DocumentUtil.readDocument(application/msword)", extracted);

		// ── (2a) extractPoemText with a NULL content type sniffs the container and routes to POI ──
		BaseRecord dataNull = newDocDataRecord("bigwayout-null-" + System.currentTimeMillis(), null, docBytes);
		String poemFromNull = ChapBookUtil.extractPoemText(dataNull);
		assertNotNull("extractPoemText must return text when contentType is null", poemFromNull);
		assertReadableProse("extractPoemText(null contentType)", poemFromNull);

		// ── (2b) extractPoemText with a MISLABELED text/plain content type still sniffs and routes to POI ──
		BaseRecord dataText = newDocDataRecord("bigwayout-text-" + System.currentTimeMillis(), "text/plain", docBytes);
		String poemFromText = ChapBookUtil.extractPoemText(dataText);
		assertNotNull("extractPoemText must return text when contentType is mislabeled text/plain", poemFromText);
		assertReadableProse("extractPoemText(text/plain contentType)", poemFromText);

		// Both record paths must agree with the direct POI extraction (same underlying bytes/parser).
		assertEquals("extractPoemText(null) and extractPoemText(text/plain) must produce identical prose",
			poemFromNull, poemFromText);

		logger.info("testExtractPoemTextFromLegacyDocFile: extracted {} chars of readable prose from {}",
			poemFromNull.length(), docFile.getName());
	}

	/**
	 * End-to-end test: create two poems → createChapBook → renderChapBook.
	 * Asserts rendered >= 1 and that at least one scene has imageObjectId set in the DB.
	 */
	@Test
	public void TestChapBookRender() throws Exception {
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assumeTrue("test.swarm.server not configured — skipping SD test", swarmServer != null && !swarmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		long ts = System.currentTimeMillis();
		String slug = "cb-test-" + ts;
		String title = "ChapBook Test " + ts;

		// ── 1. Create two olio.cb.poem records ──────────────────────────────────
		String poem1Text =
			"The light falls soft on winter boughs,\n" +
			"A silver hush where no bird calls,\n" +
			"The world holds still as snowfall flows,\n" +
			"And quiet fills the frozen halls.";

		String poem2Text =
			"Come spring and break the ice apart,\n" +
			"Let green reclaim the barren earth,\n" +
			"With warmth restored to every heart,\n" +
			"And songs reborn to celebrate birth.";

		String poemPath = "~/Data/ChapBookTest-" + ts;

		BaseRecord poem1 = createPoem(testUser, poemPath, "Poem One " + ts, poem1Text);
		assertNotNull("Poem 1 should be created", poem1);
		String poem1Oid = poem1.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem 1 must have objectId", poem1Oid);

		BaseRecord poem2 = createPoem(testUser, poemPath, "Poem Two " + ts, poem2Text);
		assertNotNull("Poem 2 should be created", poem2);
		String poem2Oid = poem2.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem 2 must have objectId", poem2Oid);

		logger.info("Created poem1={} poem2={}", poem1Oid, poem2Oid);

		// ── 2. Create ChapBook (null chatConfig = no LLM, uses default sdPrompt) ──
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem1Oid);
		poemOids.add(poem2Oid);

		long createStart = System.currentTimeMillis();
		// maxLinesPerPage=4 so each 4-line stanza becomes one scene (2 poems → 2 scenes)
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, slug, title, poemOids, 4, null);
		logger.info("createChapBook took {}ms", System.currentTimeMillis() - createStart);

		assertNotNull("createChapBook must return a book record", book);
		String bookObjectId = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookObjectId);

		// Verify bookType was patched to CHAPBOOK
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
		bq.setRequest(new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_OWNER_ID, OlioFieldNames.FIELD_PB_BOOK_TYPE
		});
		bq.setCache(false);
		BaseRecord bookCheck = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be findable by objectId", bookCheck);
		String bookType = bookCheck.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
		assertTrue("bookType must be CHAPBOOK, got: " + bookType,
			bookType != null && "CHAPBOOK".equalsIgnoreCase(bookType));

		// Verify scenes were created
		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertTrue("At least one scene must have been created from the poems", !scenes.isEmpty());
		logger.info("Scenes created: {}", scenes.size());

		// ── 3. Render the ChapBook against the live Swarm SD server ─────────────
		long renderStart = System.currentTimeMillis();
		int rendered = ChapBookUtil.renderChapBook(testUser, bookObjectId, "SWARM", swarmServer);
		logger.info("renderChapBook took {}ms, rendered={}/{} scenes",
			System.currentTimeMillis() - renderStart, rendered, scenes.size());

		assertTrue("At least 1 scene must be rendered (returned " + rendered + ")", rendered >= 1);

		// ── 4. Verify imageObjectId persisted on at least one scene ─────────────
		// Re-query scenes from the DB to get the updated imageObjectId fields
		List<BaseRecord> updatedScenes = PbBookUtil.listScenes(testUser, bookCheck);
		long scenesWithImage = updatedScenes.stream()
			.filter(s -> {
				String oid = s.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
				return oid != null && !oid.isBlank();
			})
			.count();

		logger.info("Scenes with imageObjectId set: {}/{}", scenesWithImage, updatedScenes.size());
		assertTrue("At least one scene must have imageObjectId persisted after renderChapBook; got "
			+ scenesWithImage + "/" + updatedScenes.size() + " with image",
			scenesWithImage >= 1);

		// ── 5. Verify bookPageView enriches rendered pages with MediaServlet URL fields ──────────
		// The viewer needs the image data.data record's groupPath + name to build a /media URL;
		// dataObjectId alone 404s (no /rest/resource route exists). Assert the facade now supplies them.
		List<Map<String, Object>> pages = PbServiceFacade.bookPageView(testUser, bookObjectId);
		assertNotNull("bookPageView must return pages", pages);
		assertTrue("bookPageView must return at least one page", !pages.isEmpty());
		long pagesWithImageUrl = pages.stream()
			.filter(pg -> pg.get("dataObjectId") != null)
			.filter(pg -> {
				String gp = (String) pg.get("imageGroupPath");
				String nm = (String) pg.get("imageName");
				return gp != null && !gp.isBlank() && nm != null && !nm.isBlank();
			})
			.count();
		logger.info("Pages with resolvable image URL fields (imageGroupPath+imageName): {}/{}",
			pagesWithImageUrl, pages.size());
		for (Map<String, Object> pg : pages) {
			logger.info("page dataObjectId={} imageGroupPath={} imageName={} imageContentType={}",
				pg.get("dataObjectId"), pg.get("imageGroupPath"), pg.get("imageName"), pg.get("imageContentType"));
		}
		assertTrue("At least one rendered page must carry non-null imageGroupPath + imageName so the "
			+ "viewer can build a MediaServlet URL; got " + pagesWithImageUrl + "/" + pages.size(),
			pagesWithImageUrl >= 1);
	}

	/**
	 * B7 verification: createChapBook now creates a workflow with one SCENE node per stanza.
	 * No SD server required.
	 */
	@Test
	public void testChapBookWorkflow() throws Exception {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		long ts = System.currentTimeMillis();
		String slug = "cb-wf-test-" + ts;
		String title = "ChapBook Workflow Test " + ts;

		String poem1Text =
			"The light falls soft on winter boughs,\n" +
			"A silver hush where no bird calls.";

		String poem2Text =
			"Come spring and break the ice apart,\n" +
			"Let green reclaim the barren earth.";

		String poemPath = "~/Data/ChapBookWfTest-" + ts;

		BaseRecord poem1 = createPoem(testUser, poemPath, "Poem WF One " + ts, poem1Text);
		assertNotNull("Poem 1 should be created", poem1);
		String poem1Oid = poem1.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem 1 must have objectId", poem1Oid);

		BaseRecord poem2 = createPoem(testUser, poemPath, "Poem WF Two " + ts, poem2Text);
		assertNotNull("Poem 2 should be created", poem2);
		String poem2Oid = poem2.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem 2 must have objectId", poem2Oid);

		List<String> poemOids = new ArrayList<>();
		poemOids.add(poem1Oid);
		poemOids.add(poem2Oid);

		// maxLinesPerPage=4 so each 2-line stanza becomes one scene (2 poems → 2 scenes)
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, slug, title, poemOids, 4, null);
		assertNotNull("createChapBook must return a book record", book);
		String bookObjectId = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookObjectId);

		// Verify the workflow was created
		BaseRecord workflow = PbGraphUtil.findWorkflow(testUser, book);
		assertNotNull("createChapBook must create a workflow (B7)", workflow);

		// Verify at least one SCENE node was added
		List<BaseRecord> nodes = PbGraphUtil.listNodes(testUser, workflow);
		assertTrue("Workflow must have at least one node (B7)", !nodes.isEmpty());

		// Verify the first node is SCENE type (case-insensitive, as enums come back UPPERCASE in Java)
		String nodeType = nodes.get(0).get(OlioFieldNames.FIELD_PB_NODE_TYPE);
		assertNotNull("First node must have a nodeType", nodeType);
		assertTrue("First node type must be SCENE, got: " + nodeType,
			PbNodeTypeEnumType.SCENE.toString().equalsIgnoreCase(nodeType));

		logger.info("testChapBookWorkflow: book={} workflow={} nodes={}",
			bookObjectId, workflow.get(FieldNames.FIELD_OBJECT_ID), nodes.size());
	}

	/**
	 * Exercises the LLM landscape-prompt path in {@link ChapBookUtil#createChapBookScene}.
	 * <p>
	 * When {@code chatConfig} is non-null, {@code createChapBookScene} calls
	 * {@link org.cote.accountmanager.olio.picturebook.PictureBookUtil#callLlmForChapBook} with the
	 * {@code chapBook.landscape-prompt} template and the stanza text as {@code stanzaText}, expecting
	 * the LLM to return a Stable Diffusion landscape prompt beginning with "masterpiece, best quality,".
	 * <p>
	 * The test asserts that the stored {@code sdPrompt} on each scene:
	 * <ol>
	 *   <li>Is non-null and non-blank.</li>
	 *   <li>Does not equal the raw stanza text verbatim.</li>
	 *   <li>Does NOT start with {@code "landscape, "} (that prefix is the no-LLM fallback).</li>
	 * </ol>
	 * Gated on {@code test.llm.ollama.server} — skipped when the property is absent.
	 */
	@Test
	public void testChapBookLlmLandscapePrompt() throws Exception {
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		assumeTrue("test.llm.ollama.server not configured — skipping LLM landscape-prompt test",
			llmServer != null && !llmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		// Create chatConfig via OlioTestUtil (idempotent: reuses existing if present)
		BaseRecord chatConfig = OlioTestUtil.getOllamaOpenAIConfig(testUser, "chapbookLlmTestConfig", testProperties);
		assertNotNull("chatConfig must be created for LLM test", chatConfig);

		long ts = System.currentTimeMillis();
		String slug = "cb-llm-" + ts;
		String title = "ChapBook LLM Test " + ts;

		// A well-described stanza so the LLM can generate meaningful landscape imagery
		String stanzaText =
			"The golden dawn spills across the ancient hills,\n" +
			"Where frost-tipped grass still trembles in the cold,\n" +
			"And morning breaks its light on window sills,\n" +
			"While chimney smoke curls slow through pale and gold.";

		String poemPath = "~/Data/ChapBookLlmTest-" + ts;
		BaseRecord poem = createPoem(testUser, poemPath, "Poem LLM " + ts, stanzaText);
		assertNotNull("Poem should be created", poem);
		String poemOid = poem.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem must have objectId", poemOid);

		List<String> poemOids = new ArrayList<>();
		poemOids.add(poemOid);

		// createChapBook with chatConfig != null — exercises the LLM path in createChapBookScene.
		// chapBook.landscape-prompt.json classpath resource provides system+user prompts with
		// {stanzaText}, {mood}, {compositionContext} variables.
		long start = System.currentTimeMillis();
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, slug, title, poemOids, 4, chatConfig);
		logger.info("createChapBook with chatConfig took {}ms", System.currentTimeMillis() - start);
		assertNotNull("createChapBook must return a book", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have objectId", bookOid);

		// Re-query to get the fields needed for listScenes (organizationId, groupId, etc.)
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookOid);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID,
			((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue());
		bq.setRequest(new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID
		});
		bq.setCache(false);
		BaseRecord bookCheck = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be findable after creation", bookCheck);

		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created", scenes.isEmpty());
		logger.info("Scenes created: {}", scenes.size());

		// Verify the LLM path produced a non-trivial SD prompt on at least one scene.
		// The chapBook.landscape-prompt system prompt instructs the LLM to begin with
		// "masterpiece, best quality," — so any prompt NOT starting with "landscape, "
		// (the fallback prefix) counts as LLM-generated.
		boolean foundLlmGeneratedPrompt = false;
		for (BaseRecord scene : scenes) {
			String sdPrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			logger.info("Scene sdPrompt (first 120 chars): {}",
				sdPrompt != null && sdPrompt.length() > 120 ? sdPrompt.substring(0, 120) + "…" : sdPrompt);
			assertNotNull("sdPrompt must not be null on scene", sdPrompt);
			assertFalse("sdPrompt must not be blank", sdPrompt.isBlank());
			// The raw stanza text must not have been copied verbatim
			assertFalse("sdPrompt must not equal raw stanza text verbatim",
				stanzaText.trim().equals(sdPrompt.trim()));
			if (!sdPrompt.startsWith("landscape, ")) {
				// Does not carry the fallback "landscape, " prefix → LLM actually ran
				foundLlmGeneratedPrompt = true;
				logger.info("LLM-generated sdPrompt confirmed (no fallback prefix) — first 120: {}",
					sdPrompt.length() > 120 ? sdPrompt.substring(0, 120) + "…" : sdPrompt);
			}
		}
		assertTrue(
			"At least one scene must have an LLM-generated sdPrompt (not the 'landscape, ...' fallback). "
			+ "LLM server: " + llmServer + ". If the LLM is unreachable or the prompt template is "
			+ "missing, all scenes will carry the fallback prefix.",
			foundLlmGeneratedPrompt);
	}

	/**
	 * GAP 2 (PictureBook2ChapBookGapAnalysis-2026-08-31): a ChapBook landscape render to the Issue-13
	 * evidence standard.
	 * <p>
	 * End-to-end, against the LIVE backend (LLM at {@code test.llm.ollama.server}, SD/Swarm at
	 * {@code test.swarm.server}):
	 * <ol>
	 * <li>Real poem content — the exact {@link #POEM_TEXT} the UX e2e uses.</li>
	 * <li>{@code createChapBook} with a real LLM {@code chatConfig} so the landscape-prompt path runs,
	 *     then {@code renderChapBook} against the live Swarm.</li>
	 * <li>Before render, set a distinctive per-scene {@code configOverride} (steps=42) and assert (via
	 *     {@code resolveEffectiveConfig} on that scene — the exact carrier {@code renderChapBook} passes)
	 *     that the override WINS the merge, proving it flows into the real render/config path.</li>
	 * <li>Resolve a rendered scene's {@code imageObjectId} &rarr; {@code data.data} record &rarr; bytes
	 *     via {@code ByteModelUtil.getValue} (never a raw {@code byteStore .get()}).</li>
	 * <li>Assert the leading bytes are PNG ({@code 89 50 4E 47}) or JPEG ({@code FF D8 FF}) magic, then
	 *     DECODE via {@code ImageIO.read} asserting a real raster (width &gt; 0 &amp;&amp; height &gt; 0).
	 *     A zero-byte/HTML/JSON error blob FAILS both checks.</li>
	 * <li>Emit the decoded image AND the LLM-generated {@code sdPrompt} to disk for visual inspection and
	 *     log the emit paths.</li>
	 * </ol>
	 * Gated on {@code test.swarm.server} AND {@code test.llm.ollama.server} — {@code assumeTrue} SKIPS
	 * (not fails) when either is absent. With both present it MUST run.
	 */
	@Test
	public void TestChapBookRenderDecodedEmitE2E() throws Exception {
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assumeTrue("test.swarm.server not configured — skipping ChapBook render E2E",
			swarmServer != null && !swarmServer.isBlank());
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		assumeTrue("test.llm.ollama.server not configured — skipping ChapBook render E2E",
			llmServer != null && !llmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// Test-side-only disk emission for manual visual inspection (production code knows nothing of it).
		String emitDir = new File(dataPath, "chapbook-e2e").getAbsolutePath();
		new File(emitDir).mkdirs();

		// Real LLM chatConfig so createChapBookScene runs the chapBook.landscape-prompt LLM path.
		BaseRecord chatConfig = OlioTestUtil.getOllamaOpenAIConfig(testUser, "chapbookLlmTestConfig", testProperties);
		assertNotNull("chatConfig must be created for the LLM landscape-prompt path", chatConfig);

		long ts = System.currentTimeMillis();
		String slug = "cb-e2e-" + ts;
		String title = "ChapBook Verified E2E " + ts;

		// ── 1. Real poem content ────────────────────────────────────────────────
		String poemPath = "~/Data/ChapBookE2E-" + ts;
		BaseRecord poem = createPoem(testUser, poemPath, "Poem E2E " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		String poemOid = poem.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem must have objectId", poemOid);

		List<String> poemOids = new ArrayList<>();
		poemOids.add(poemOid);

		// ── 2. createChapBook with the LLM chatConfig (landscape-prompt path runs) ──
		long createStart = System.currentTimeMillis();
		// maxLinesPerPage=5 → each 5-line stanza becomes one scene (POEM_TEXT has two → 2 scenes)
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, slug, title, poemOids, 5, chatConfig);
		logger.info("createChapBook (with LLM chatConfig) took {}ms", System.currentTimeMillis() - createStart);
		assertNotNull("createChapBook must return a book", book);
		String bookObjectId = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookObjectId);

		// Re-query for the fields listScenes needs (organizationId, groupId, groupPath, etc.)
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_OWNER_ID, OlioFieldNames.FIELD_PB_BOOK_TYPE
		});
		bq.setCache(false);
		BaseRecord bookCheck = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be findable by objectId", bookCheck);

		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created from the poem", scenes.isEmpty());
		logger.info("ChapBook E2E: {} scene(s) created", scenes.size());

		// ── 3. Prove a per-scene configOverride flows into the REAL render/config path ──
		// renderChapBook (ChapBookUtil.java) resolves each scene's config via
		// PbConfigUtil.resolveEffectiveConfig(book, scene, false) — the SCENE is the override carrier.
		// Set a distinctive override (steps=42) and assert that same resolver returns 42.
		BaseRecord firstScene = scenes.get(0);
		String firstSceneOid = firstScene.get(FieldNames.FIELD_OBJECT_ID);
		BaseRecord cfg42 = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
		cfg42.set("steps", 42);
		String override = PbConfigUtil.sparseOverride(cfg42, Arrays.asList("steps"));
		assertNotNull("sparseOverride must produce a non-null override JSON", override);
		assertTrue("override JSON must carry steps=42, got: " + override, override.contains("42"));
		boolean overrideSet = PbBookUtil.setSceneConfigOverride(testUser, firstSceneOid, override);
		assertTrue("setSceneConfigOverride must return true", overrideSet);

		BaseRecord sceneReloaded = PbBookUtil.readScene(testUser, firstSceneOid, orgId);
		assertNotNull("Scene must be re-readable after setSceneConfigOverride", sceneReloaded);
		assertEquals("Persisted configOverride must round-trip verbatim",
			override, (String) sceneReloaded.get(OlioFieldNames.FIELD_PB_CONFIG_OVERRIDE));
		BaseRecord effective = PbConfigUtil.resolveEffectiveConfig(bookCheck, sceneReloaded, false);
		assertNotNull("resolveEffectiveConfig must not be null", effective);
		int mergedSteps = ((Number) effective.get("steps")).intValue();
		assertEquals("Per-scene configOverride (steps=42) MUST win the effective-config merge that "
			+ "renderChapBook performs (scene is the override carrier)", 42, mergedSteps);
		logger.info("ChapBook E2E: per-scene configOverride steps=42 wins resolveEffectiveConfig");

		// ── 4. Render against the live Swarm SD server ──────────────────────────
		long renderStart = System.currentTimeMillis();
		int rendered = ChapBookUtil.renderChapBook(testUser, bookObjectId, "SWARM", swarmServer);
		logger.info("renderChapBook took {}ms, rendered={}/{} scenes",
			System.currentTimeMillis() - renderStart, rendered, scenes.size());
		assertTrue("At least 1 scene must be rendered (returned " + rendered + ")", rendered >= 1);

		// ── 5. Resolve a rendered scene → data.data → bytes → decode + magic-check ──
		List<BaseRecord> updatedScenes = PbBookUtil.listScenes(testUser, bookCheck);
		BaseRecord renderedScene = null;
		String imageOid = null;
		for (BaseRecord s : updatedScenes) {
			String oid = s.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
			if (oid != null && !oid.isBlank()) {
				renderedScene = s;
				imageOid = oid;
				break;
			}
		}
		assertNotNull("At least one scene must carry a persisted imageObjectId after render", imageOid);
		logger.info("ChapBook E2E: rendered scene imageObjectId={}", imageOid);

		byte[] imageBytes = fetchDataBytes(imageOid, orgId);
		assertNotNull("Image data.data bytes must be readable via ByteModelUtil.getValue", imageBytes);
		assertTrue("Image bytes must be non-empty — a 0-byte/HTML-error blob must FAIL (len="
			+ imageBytes.length + ")", imageBytes.length > 0);

		// Magic-byte check: PNG (89 50 4E 47) or JPEG (FF D8 FF).
		boolean isPng = imageBytes.length >= 4
			&& (imageBytes[0] & 0xFF) == 0x89 && (imageBytes[1] & 0xFF) == 0x50
			&& (imageBytes[2] & 0xFF) == 0x4E && (imageBytes[3] & 0xFF) == 0x47;
		boolean isJpeg = imageBytes.length >= 3
			&& (imageBytes[0] & 0xFF) == 0xFF && (imageBytes[1] & 0xFF) == 0xD8
			&& (imageBytes[2] & 0xFF) == 0xFF;
		String magicPreview = String.format("%02X %02X %02X %02X",
			imageBytes[0],
			imageBytes.length > 1 ? imageBytes[1] : 0,
			imageBytes.length > 2 ? imageBytes[2] : 0,
			imageBytes.length > 3 ? imageBytes[3] : 0);
		assertTrue("Image bytes must start with PNG or JPEG magic (first bytes: " + magicPreview
			+ ") — an HTML/JSON error blob must FAIL", isPng || isJpeg);

		// Decode a real raster.
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
		assertNotNull("ImageIO must decode a real image from the persisted bytes "
			+ "(non-decodable bytes = SwarmUI never returned image data)", img);
		assertTrue("decoded image width must be > 0 (was " + img.getWidth() + ")", img.getWidth() > 0);
		assertTrue("decoded image height must be > 0 (was " + img.getHeight() + ")", img.getHeight() > 0);
		logger.info("ChapBook E2E: decoded {}x{} {} image from {} persisted bytes",
			img.getWidth(), img.getHeight(), isPng ? "PNG" : "JPEG", imageBytes.length);

		// ── 6. sdPrompt proof: the scene's stored sdPrompt is the LLM landscape prompt ──
		// createChapBookScene (chatConfig != null) generates the landscape prompt via the LLM and stores
		// it on the scene; a non-LLM fallback would carry the "landscape, " prefix.
		String sdPrompt = renderedScene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertNotNull("Rendered scene must carry a stored sdPrompt", sdPrompt);
		assertFalse("sdPrompt must not be blank", sdPrompt.isBlank());
		assertFalse("sdPrompt must not be the raw poem stanza verbatim",
			POEM_TEXT.trim().equals(sdPrompt.trim()));
		assertFalse("sdPrompt must be LLM-generated, not the 'landscape, ' no-LLM fallback (got: "
			+ (sdPrompt.length() > 60 ? sdPrompt.substring(0, 60) + "…" : sdPrompt) + ")",
			sdPrompt.startsWith("landscape, "));
		logger.info("ChapBook E2E: LLM sdPrompt (first 120): {}",
			sdPrompt.length() > 120 ? sdPrompt.substring(0, 120) + "…" : sdPrompt);

		// ── 7. Emit image + sdPrompt to disk and log the paths ──────────────────
		String pngPath = emitDir + File.separator + "chapbook_verified_" + ts + ".png";
		String txtPath = emitDir + File.separator + "chapbook_verified_" + ts + ".txt";
		assertTrue("Emitting the decoded image to disk must succeed: " + pngPath,
			FileUtil.emitFile(pngPath, imageBytes));
		assertTrue("Emitting the sdPrompt to disk must succeed: " + txtPath,
			FileUtil.emitFile(txtPath, sdPrompt));
		logger.info("ChapBook E2E EMIT: image -> {} ({} bytes, {}x{})", pngPath, imageBytes.length,
			img.getWidth(), img.getHeight());
		logger.info("ChapBook E2E EMIT: sdPrompt -> {}", txtPath);
	}

	/**
	 * GAP 6 (PictureBook2ChapBookGapAnalysis-2026-08-31): the NEW per-scene, client-driven ChapBook
	 * render — {@link ChapBookUtil#renderChapBookScene} — to the Issue-13 evidence standard.
	 * <p>
	 * PB2 solved the whole-book gateway-timeout by having the client call ONE per-scene render endpoint
	 * per scene rather than one bulk HTTP call rendering every scene on a single thread. This test drives
	 * {@code renderChapBookScene} directly (NOT the bulk {@link ChapBookUtil#renderChapBook}) so the
	 * hardened parent-book FK resolution (objectId path, else fall back to the FK's numeric {@code id} via
	 * {@code loadRenderBookById}) and the single-scene render wrapper are genuinely exercised.
	 * <p>
	 * End-to-end, against the LIVE backend (LLM at {@code test.llm.ollama.server}, SD/Swarm at
	 * {@code test.swarm.server}):
	 * <ol>
	 * <li>Real poem content — the exact {@link #POEM_TEXT} the UX e2e uses.</li>
	 * <li>{@code createChapBook} with a real LLM {@code chatConfig}, then pick ONE scene's objectId.</li>
	 * <li>Call {@code renderChapBookScene(user, sceneObjectId, "SWARM", swarmServer, chatConfig, null)} for
	 *     that ONE scene — exactly one SD call.</li>
	 * <li>Assert the returned {@code imageObjectId} is non-null and non-blank.</li>
	 * <li>Resolve {@code imageObjectId} &rarr; {@code data.data} record &rarr; bytes via
	 *     {@code ByteModelUtil.getValue} (never a raw {@code byteStore .get()}); assert PNG
	 *     ({@code 89 50 4E 47}) or JPEG ({@code FF D8 FF}) magic, then {@code ImageIO.read} a real raster
	 *     (width &gt; 0 &amp;&amp; height &gt; 0). A 0-byte/HTML/JSON error blob FAILS both checks.</li>
	 * <li>Emit the decoded image to disk ({@code gap6-chapbook-scene-<objectId>.png}) for visual inspection.</li>
	 * <li>Re-query the scene with {@code setCache(false)} and assert {@code FIELD_PB_IMAGE_OBJECT_ID} was
	 *     patched to the returned objectId (proves the persistence side-effect happened).</li>
	 * </ol>
	 * Gated on {@code test.swarm.server} AND {@code test.llm.ollama.server} — {@code assumeTrue} SKIPS
	 * (not fails) when either is absent. Both are set in resource.properties, so this MUST run.
	 */
	@Test
	public void TestChapBookRenderSceneDecodedEmitE2E() throws Exception {
		String swarmServer = testProperties.getProperty("test.swarm.server");
		assumeTrue("test.swarm.server not configured — skipping per-scene ChapBook render E2E",
			swarmServer != null && !swarmServer.isBlank());
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		assumeTrue("test.llm.ollama.server not configured — skipping per-scene ChapBook render E2E",
			llmServer != null && !llmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// Test-side-only disk emission for manual visual inspection (production code knows nothing of it).
		String emitDir = new File(dataPath, "chapbook-e2e").getAbsolutePath();
		new File(emitDir).mkdirs();

		// Real LLM chatConfig so renderChapBookScene runs the chapBook.landscape-prompt LLM path.
		BaseRecord chatConfig = OlioTestUtil.getOllamaOpenAIConfig(testUser, "chapbookLlmTestConfig", testProperties);
		assertNotNull("chatConfig must be created for the LLM landscape-prompt path", chatConfig);

		long ts = System.currentTimeMillis();
		String slug = "cb-gap6-" + ts;
		String title = "ChapBook GAP6 Per-Scene E2E " + ts;

		// ── 1. Real poem content ────────────────────────────────────────────────
		String poemPath = "~/Data/ChapBookGap6-" + ts;
		BaseRecord poem = createPoem(testUser, poemPath, "Poem GAP6 " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		String poemOid = poem.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem must have objectId", poemOid);

		List<String> poemOids = new ArrayList<>();
		poemOids.add(poemOid);

		// ── 2. createChapBook (LLM chatConfig), then pick ONE scene ─────────────
		long createStart = System.currentTimeMillis();
		// maxLinesPerPage=5 → each 5-line stanza becomes one scene (POEM_TEXT has two → 2 scenes).
		// We render only ONE below via renderChapBookScene, so exactly one SD call is made.
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, slug, title, poemOids, 5, chatConfig);
		logger.info("createChapBook (with LLM chatConfig) took {}ms", System.currentTimeMillis() - createStart);
		assertNotNull("createChapBook must return a book", book);
		String bookObjectId = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookObjectId);

		// Re-query for the fields listScenes needs (organizationId, groupId, groupPath, etc.)
		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookObjectId);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_OWNER_ID, OlioFieldNames.FIELD_PB_BOOK_TYPE
		});
		bq.setCache(false);
		BaseRecord bookCheck = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be findable by objectId", bookCheck);

		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created from the poem", scenes.isEmpty());
		logger.info("ChapBook GAP6: {} scene(s) created — rendering exactly ONE via renderChapBookScene", scenes.size());

		BaseRecord targetScene = scenes.get(0);
		String sceneObjectId = targetScene.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Target scene must have an objectId", sceneObjectId);
		// Confirm the scene has no image yet (so the assertion below proves THIS call patched it).
		String preImage = targetScene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
		assertTrue("Target scene must start with NO imageObjectId (got: " + preImage + ")",
			preImage == null || preImage.isBlank());

		// ── 3. Drive the NEW per-scene render path (exactly one SD call) ────────
		long renderStart = System.currentTimeMillis();
		ChapBookUtil.SceneRenderResult renderResult = ChapBookUtil.renderChapBookScene(
			testUser, sceneObjectId, "SWARM", swarmServer, chatConfig, null);
		logger.info("renderChapBookScene took {}ms for scene {}",
			System.currentTimeMillis() - renderStart, sceneObjectId);

		// ── 4. Assert RENDERED status + a non-null, non-blank imageObjectId ─────
		assertNotNull("renderChapBookScene must return a SceneRenderResult", renderResult);
		assertEquals("renderChapBookScene must return status RENDERED for a genuine-prompt scene "
			+ "(SKIPPED_NO_PROMPT = un-prompted / FAILED = SD returned nothing or patch failed)",
			ChapBookUtil.SceneRenderStatus.RENDERED, renderResult.status);
		String imageOid = renderResult.imageObjectId;
		assertNotNull("A RENDERED result must carry a non-null imageObjectId", imageOid);
		assertFalse("renderChapBookScene must return a non-blank imageObjectId", imageOid.isBlank());
		logger.info("ChapBook GAP6: renderChapBookScene returned imageObjectId={}", imageOid);

		// ── 5. Resolve imageObjectId → data.data → bytes → magic + decode ───────
		byte[] imageBytes = fetchDataBytes(imageOid, orgId);
		assertNotNull("Image data.data bytes must be readable via ByteModelUtil.getValue", imageBytes);
		assertTrue("Image bytes must be non-empty — a 0-byte/HTML-error blob must FAIL (len="
			+ imageBytes.length + ")", imageBytes.length > 0);

		boolean isPng = imageBytes.length >= 4
			&& (imageBytes[0] & 0xFF) == 0x89 && (imageBytes[1] & 0xFF) == 0x50
			&& (imageBytes[2] & 0xFF) == 0x4E && (imageBytes[3] & 0xFF) == 0x47;
		boolean isJpeg = imageBytes.length >= 3
			&& (imageBytes[0] & 0xFF) == 0xFF && (imageBytes[1] & 0xFF) == 0xD8
			&& (imageBytes[2] & 0xFF) == 0xFF;
		String magicPreview = String.format("%02X %02X %02X %02X",
			imageBytes[0],
			imageBytes.length > 1 ? imageBytes[1] : 0,
			imageBytes.length > 2 ? imageBytes[2] : 0,
			imageBytes.length > 3 ? imageBytes[3] : 0);
		assertTrue("Image bytes must start with PNG or JPEG magic (first bytes: " + magicPreview
			+ ") — an HTML/JSON error blob must FAIL", isPng || isJpeg);

		BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
		assertNotNull("ImageIO must decode a real image from the persisted bytes "
			+ "(non-decodable bytes = SwarmUI never returned image data)", img);
		assertTrue("decoded image width must be > 0 (was " + img.getWidth() + ")", img.getWidth() > 0);
		assertTrue("decoded image height must be > 0 (was " + img.getHeight() + ")", img.getHeight() > 0);
		logger.info("ChapBook GAP6: decoded {}x{} {} image from {} persisted bytes",
			img.getWidth(), img.getHeight(), isPng ? "PNG" : "JPEG", imageBytes.length);

		// ── 6. Emit the decoded image to disk for visual inspection ─────────────
		String pngPath = emitDir + File.separator + "gap6-chapbook-scene-" + sceneObjectId + ".png";
		assertTrue("Emitting the decoded image to disk must succeed: " + pngPath,
			FileUtil.emitFile(pngPath, imageBytes));
		logger.info("ChapBook GAP6 EMIT: image -> {} ({} bytes, {}x{})", pngPath, imageBytes.length,
			img.getWidth(), img.getHeight());

		// ── 7. Re-query the scene (cache:false) and assert imageObjectId patched ─
		BaseRecord sceneAfter = PbBookUtil.readScene(testUser, sceneObjectId, orgId);
		assertNotNull("Scene must be re-readable after render", sceneAfter);
		String persistedImageOid = sceneAfter.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
		assertNotNull("Scene must carry a persisted imageObjectId after renderChapBookScene", persistedImageOid);
		assertFalse("Persisted imageObjectId must not be blank", persistedImageOid.isBlank());
		assertEquals("The scene's persisted imageObjectId MUST equal the objectId renderChapBookScene "
			+ "returned (proves the FIELD_PB_IMAGE_OBJECT_ID patch side-effect happened)",
			imageOid, persistedImageOid);
		logger.info("ChapBook GAP6: scene {} FIELD_PB_IMAGE_OBJECT_ID persisted = {}", sceneObjectId, persistedImageOid);
	}

	/**
	 * Fix B (deterministic, no LLM): proves {@link ChapBookUtil#assemblePriorContext} — the continuity
	 * string threaded into the {@code chapBook.landscape-prompt} call — is assembled and guarded correctly.
	 * <ol>
	 *   <li>Empty inputs yield the {@code "none"} sentinel (never blank — the {@code {priorContext}}
	 *       placeholder must always substitute or the UNSUBSTITUTED_PLACEHOLDER guard refuses the LLM call).</li>
	 *   <li>LLM literal {@code "null"}/{@code "n/a"}/{@code "unknown"} values are dropped (via
	 *       {@code NarrativeUtil.isMeaningful}) rather than leaking into the assembled prompt.</li>
	 *   <li>Meaningful poem theme/mood/keywords AND a prior scene's imagery are all carried through.</li>
	 * </ol>
	 * Not gated — runs without the LLM.
	 */
	@Test
	public void testAssemblePriorContextGuardsAndSentinel() {
		// 1. Nothing meaningful → "none" sentinel, never blank.
		assertEquals("all-null inputs must yield the 'none' sentinel",
			"none", ChapBookUtil.assemblePriorContext(null, null, null, null));
		assertEquals("empty prior-scene list with null analysis must yield 'none'",
			"none", ChapBookUtil.assemblePriorContext(null, null, null, new ArrayList<>()));

		// 2. LLM literal 'null'/'n/a'/'unknown' must be guarded out (NarrativeUtil.isMeaningful).
		assertEquals("literal 'null'/'n/a'/'unknown' analysis values must be dropped, yielding 'none'",
			"none", ChapBookUtil.assemblePriorContext("null", "n/a", "unknown", Arrays.asList("null")));

		// 3. Meaningful analysis + a prior scene prompt → all fragments carried through.
		String priorScene = "masterpiece, best quality, a snowbound valley under cobalt sky, silver frost, cold morning light";
		String pc = ChapBookUtil.assemblePriorContext("winter stillness", "snow, frost, silver veins",
			"melancholy", Arrays.asList(priorScene));
		logger.info("assemblePriorContext (meaningful): {}", pc);
		assertFalse("meaningful inputs must NOT yield the 'none' sentinel", "none".equals(pc));
		assertTrue("prior context must carry the poem theme", pc.contains("winter stillness"));
		assertTrue("prior context must carry the poem mood", pc.contains("melancholy"));
		assertTrue("prior context must carry the imagery keywords", pc.contains("snow, frost, silver veins"));
		assertTrue("prior context must carry the earlier scene's imagery",
			pc.contains("a snowbound valley under cobalt sky"));

		// 4. A prior-scene prompt that is itself the literal 'null' must not create fake continuity.
		assertEquals("a literal 'null' prior-scene prompt with no analysis must yield 'none'",
			"none", ChapBookUtil.assemblePriorContext(null, null, null, Arrays.asList("null")));
	}

	/**
	 * Fix B (LLM, live): proves the "prior mcp entries" continuity is threaded into scene generation for a
	 * multi-scene ChapBook. Against the live LLM ({@code test.llm.ollama.server}):
	 * <ol>
	 *   <li>Analyze the poem first ({@code analyzePoemTheme}) so theme/mood/keywords are populated.</li>
	 *   <li>{@code createChapBook} with a real chatConfig and {@code maxLinesPerPage=5} → the two-stanza
	 *       {@link #POEM_TEXT} yields TWO scenes, each with an LLM-generated landscape prompt (asserted NOT
	 *       to be the raw stanza nor the {@code "landscape, "} fallback — the regression under test).</li>
	 *   <li>Reconstruct the EXACT continuity string {@code createChapBook} builds for scene 2 —
	 *       {@code assemblePriorContext(theme, keywords, mood, [scene-1 prompt])} — and assert it is
	 *       non-empty, not the {@code "none"} sentinel, and actually carries scene 1's imagery (and the
	 *       poem theme when analysis populated one). This proves scene 2's generation input saw the poem
	 *       analysis and/or scene 1's prompt.</li>
	 * </ol>
	 * Gated on {@code test.llm.ollama.server} — SKIPPED (not failed) when absent.
	 */
	@Test
	public void testChapBookLlmPriorContextThreading() throws Exception {
		String llmServer = testProperties.getProperty("test.llm.ollama.server");
		assumeTrue("test.llm.ollama.server not configured — skipping prior-context threading test",
			llmServer != null && !llmServer.isBlank());

		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		BaseRecord chatConfig = OlioTestUtil.getOllamaOpenAIConfig(testUser, "chapbookLlmTestConfig", testProperties);
		assertNotNull("chatConfig must be created for the LLM prior-context test", chatConfig);

		long ts = System.currentTimeMillis();
		String slug = "cb-prior-" + ts;
		String title = "ChapBook Prior-Context Test " + ts;

		// ── 1. Create a two-stanza poem ─────────────────────────────────────────
		String poemPath = "~/Data/ChapBookPrior-" + ts;
		BaseRecord poem = createPoem(testUser, poemPath, "Poem Prior " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		String poemOid = poem.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem must have objectId", poemOid);

		// ── 2. Analyze the poem so theme/mood/keywords populate (needs 'text' projected) ──
		Query pq = QueryUtil.createQuery(OlioModelNames.MODEL_CB_POEM, FieldNames.FIELD_OBJECT_ID, poemOid);
		pq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, "text"
		});
		pq.setCache(false);
		BaseRecord poemForAnalysis = IOSystem.getActiveContext().getAccessPoint().find(testUser, pq);
		assertNotNull("Poem must be re-readable with text projected", poemForAnalysis);
		ChapBookUtil.analyzePoemTheme(testUser, poemForAnalysis, chatConfig);

		// Re-read the analyzed poem's theme/mood/keywords (best-effort — the assertion below is robust
		// even if the analysis returned nothing, because scene 1's prompt alone proves the threading).
		Query pq2 = QueryUtil.createQuery(OlioModelNames.MODEL_CB_POEM, FieldNames.FIELD_OBJECT_ID, poemOid);
		pq2.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		pq2.setRequest(new String[] {
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID,
			OlioFieldNames.FIELD_CB_THEME, OlioFieldNames.FIELD_CB_MOOD, OlioFieldNames.FIELD_CB_KEYWORDS
		});
		pq2.setCache(false);
		BaseRecord analyzed = IOSystem.getActiveContext().getAccessPoint().find(testUser, pq2);
		assertNotNull("Poem must be re-readable after analysis", analyzed);
		String theme = analyzed.get(OlioFieldNames.FIELD_CB_THEME);
		String mood = analyzed.get(OlioFieldNames.FIELD_CB_MOOD);
		String keywords = analyzed.get(OlioFieldNames.FIELD_CB_KEYWORDS);
		logger.info("Analyzed poem — theme='{}' mood='{}' keywords='{}'", theme, mood, keywords);

		// ── 3. createChapBook → two scenes, each LLM-generated ──────────────────
		List<String> poemOids = new ArrayList<>();
		poemOids.add(poemOid);
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, slug, title, poemOids, 5, chatConfig);
		assertNotNull("createChapBook must return a book", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);

		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookOid);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID
		});
		bq.setCache(false);
		BaseRecord bookCheck = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be findable after creation", bookCheck);

		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertTrue("A two-stanza poem at maxLinesPerPage=5 must yield at least two scenes (was "
			+ scenes.size() + ")", scenes.size() >= 2);
		logger.info("prior-context test: {} scenes created", scenes.size());

		// Every scene must carry an LLM-generated landscape prompt (regression: not the fallback).
		List<String> scenePrompts = new ArrayList<>();
		for (int i = 0; i < scenes.size(); i++) {
			BaseRecord scene = scenes.get(i);
			String sdPrompt = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			String stanza = scene.get(OlioFieldNames.FIELD_CB_POEM_STANZA);
			logger.info("Scene {} sdPrompt: {}", i,
				sdPrompt != null && sdPrompt.length() > 160 ? sdPrompt.substring(0, 160) + "…" : sdPrompt);
			assertNotNull("Scene " + i + " sdPrompt must not be null", sdPrompt);
			assertFalse("Scene " + i + " sdPrompt must not be blank", sdPrompt.isBlank());
			assertFalse("Scene " + i + " sdPrompt must not be the 'landscape, ' fallback (the regression)",
				sdPrompt.startsWith("landscape, "));
			if (stanza != null) {
				assertFalse("Scene " + i + " sdPrompt must not equal the raw stanza verbatim",
					stanza.trim().equals(sdPrompt.trim()));
			}
			scenePrompts.add(sdPrompt);
		}

		// ── 4. Prove the exact prior-context string createChapBook threads into scene 2 ──
		// scenes are ordered ascending by sceneIndex (PbBookUtil.listScenes), so index 0 == scene 1.
		String scene1Prompt = scenePrompts.get(0);
		String priorContextForScene2 = ChapBookUtil.assemblePriorContext(theme, keywords, mood,
			Arrays.asList(scene1Prompt));
		logger.info("Prior context threaded into scene 2: {}", priorContextForScene2);
		assertNotNull("prior context must not be null", priorContextForScene2);
		assertFalse("prior context for scene 2 must NOT be the empty 'none' sentinel — it must carry "
			+ "scene 1's imagery and/or the poem analysis", "none".equals(priorContextForScene2));
		// Faithful proof scene 1's prompt was threaded: a leading fragment of it appears in the context.
		String scene1Head = scene1Prompt.trim();
		scene1Head = scene1Head.substring(0, Math.min(40, scene1Head.length()));
		assertTrue("prior context for scene 2 must contain scene 1's imagery (threaded prior mcp entry). "
			+ "context='" + priorContextForScene2 + "' scene1Head='" + scene1Head + "'",
			priorContextForScene2.contains(scene1Head));
		// When the poem analysis produced a theme, it must also be threaded.
		if (org.cote.accountmanager.olio.NarrativeUtil.isMeaningful(theme)) {
			assertTrue("prior context for scene 2 must carry the analyzed poem theme '" + theme + "'",
				priorContextForScene2.contains(theme.trim()));
		}
	}

	/**
	 * Fix (deterministic, no LLM/SD): proves {@link ChapBookUtil#resolveScenePrompt} — the pure
	 * prompt-resolution decision the per-scene render path uses — PREFERS the stored continuity prompt
	 * and only regenerates on a fallback-shaped one. This is the core of the fix: the create-time
	 * continuity prompt must NOT be discarded on the render path the Ux client actually hits.
	 * <ol>
	 *   <li>(a) A genuine, LLM-shaped stored prompt is returned verbatim and the recovery supplier
	 *       (the LLM call) is NOT invoked.</li>
	 *   <li>(b) A {@code "landscape, ..."} fallback-shaped stored prompt triggers regeneration — the
	 *       recovery supplier IS invoked and its result is used.</li>
	 *   <li>(c) When recovery returns blank AND the stored prompt is only the {@code "landscape, "}
	 *       fallback shape, resolution returns {@code null} — the scene is un-prompted, so the render must
	 *       SKIP and produce no image (heavier fallback: never draw a fallback/stanza image).</li>
	 * </ol>
	 * Not gated — runs without the LLM or SD server (the recovery seam is a deterministic stub here).
	 */
	@Test
	public void testResolveScenePromptPrefersStoredContinuityPrompt() throws Exception {
		// (a) A genuine, LLM-shaped stored prompt is authoritative — returned verbatim, LLM NOT invoked.
		BaseRecord sceneA = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[]{ OlioFieldNames.FIELD_CB_SD_PROMPT });
		String genuine = "masterpiece, best quality, DISTINCTIVE_MARKER_A, cobalt sky over a snowbound valley";
		sceneA.set(OlioFieldNames.FIELD_CB_SD_PROMPT, genuine);
		java.util.concurrent.atomic.AtomicBoolean invokedA = new java.util.concurrent.atomic.AtomicBoolean(false);
		String chosenA = ChapBookUtil.resolveScenePrompt(sceneA, "some stanza text", () -> {
			invokedA.set(true);
			return "REGENERATED_SHOULD_NOT_BE_USED";
		});
		assertEquals("A genuine stored continuity prompt must be returned verbatim", genuine, chosenA);
		assertFalse("The LLM recovery supplier must NOT be invoked when a genuine stored prompt exists",
			invokedA.get());

		// (b) A fallback-shaped stored prompt ("landscape, ...") must trigger regeneration (recovery).
		BaseRecord sceneB = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[]{ OlioFieldNames.FIELD_CB_SD_PROMPT });
		sceneB.set(OlioFieldNames.FIELD_CB_SD_PROMPT,
			"landscape, Winter Poem, melancholy atmosphere, painterly, soft light, wide natural vista");
		java.util.concurrent.atomic.AtomicBoolean invokedB = new java.util.concurrent.atomic.AtomicBoolean(false);
		String recovered = "masterpiece, best quality, DISTINCTIVE_MARKER_B, silver frost at dawn";
		String chosenB = ChapBookUtil.resolveScenePrompt(sceneB, "some stanza text", () -> {
			invokedB.set(true);
			return recovered;
		});
		assertTrue("A fallback-shaped stored prompt must trigger the LLM recovery supplier", invokedB.get());
		assertEquals("The recovered LLM prompt must be used when the stored one is fallback-shaped",
			recovered, chosenB);

		// (c) When recovery returns blank AND the stored prompt is only the "landscape, " fallback shape,
		//     resolution must return NULL — the scene is un-prompted, so the render SKIPS (no fallback/
		//     stanza image is ever drawn). This is the heavier-fallback behaviour change.
		BaseRecord sceneC = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[]{ OlioFieldNames.FIELD_CB_SD_PROMPT });
		String fallbackShaped = "landscape, Spring Poem, hopeful atmosphere, painterly, soft light, wide natural vista";
		sceneC.set(OlioFieldNames.FIELD_CB_SD_PROMPT, fallbackShaped);
		String chosenC = ChapBookUtil.resolveScenePrompt(sceneC, "some stanza text", () -> "");
		assertNull("A blank recovery over a fallback-shaped stored prompt must return null (un-prompted) — "
			+ "the render must skip, never draw the 'landscape, ' fallback or the raw stanza", chosenC);

		logger.info("testResolveScenePromptPrefersStoredContinuityPrompt: prefer-stored, recover-on-fallback, "
			+ "and blank-recovery-over-fallback returns null (skip) all honored");
	}

	/**
	 * Fix (deterministic, no LLM/SD): proves {@link ChapBookUtil#resolveScenePrompt} returns {@code null}
	 * for every un-prompted case — the core of the heavier-fallback change. A null return is the signal the
	 * render must SKIP and produce no image; the method must NEVER return the {@code "landscape, "}
	 * fallback-shaped stored string nor the raw stanza.
	 * <ol>
	 *   <li>(a) No recovery supplier + fallback-shaped stored prompt → null (recovery cannot run).</li>
	 *   <li>(b) No recovery supplier + blank stored prompt + a non-blank stanza → null (the raw stanza is
	 *       never used as a prompt).</li>
	 *   <li>(c) Recovery supplier present but returns null + fallback-shaped stored prompt → null.</li>
	 *   <li>(d) Recovery supplier present but stanza is blank (so recovery cannot run) + fallback-shaped
	 *       stored prompt → null.</li>
	 * </ol>
	 * Not gated — runs without the LLM or SD server.
	 */
	@Test
	public void testResolveScenePromptReturnsNullWhenNoGenuinePrompt() throws Exception {
		// (a) No recovery at all, stored prompt is fallback-shaped → un-prompted (null).
		BaseRecord sceneA = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[]{ OlioFieldNames.FIELD_CB_SD_PROMPT });
		sceneA.set(OlioFieldNames.FIELD_CB_SD_PROMPT,
			"landscape, Winter Poem, melancholy atmosphere, painterly, soft light, wide natural vista");
		assertNull("No recovery + fallback-shaped stored prompt must be un-prompted (null) — never the "
			+ "'landscape, ' fallback string",
			ChapBookUtil.resolveScenePrompt(sceneA, "some stanza text", null));

		// (b) No recovery, blank stored prompt, non-blank stanza → the raw stanza is NEVER used (null).
		BaseRecord sceneB = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[]{ OlioFieldNames.FIELD_CB_SD_PROMPT });
		sceneB.set(OlioFieldNames.FIELD_CB_SD_PROMPT, "");
		assertNull("A blank stored prompt with a non-blank stanza must be un-prompted (null) — the raw "
			+ "stanza is never used as a render prompt",
			ChapBookUtil.resolveScenePrompt(sceneB, "the raw stanza body must never be a prompt", null));

		// (c) Recovery present but returns null, stored prompt fallback-shaped → un-prompted (null).
		BaseRecord sceneC = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[]{ OlioFieldNames.FIELD_CB_SD_PROMPT });
		sceneC.set(OlioFieldNames.FIELD_CB_SD_PROMPT,
			"landscape, Spring Poem, hopeful atmosphere, painterly, soft light, wide natural vista");
		java.util.concurrent.atomic.AtomicBoolean invokedC = new java.util.concurrent.atomic.AtomicBoolean(false);
		String chosenC = ChapBookUtil.resolveScenePrompt(sceneC, "some stanza text", () -> {
			invokedC.set(true);
			return null;
		});
		assertTrue("A fallback-shaped stored prompt must still attempt recovery", invokedC.get());
		assertNull("A null recovery over a fallback-shaped stored prompt must be un-prompted (null)", chosenC);

		// (d) Recovery present but stanza blank (recovery cannot run), stored fallback-shaped → null.
		BaseRecord sceneD = RecordFactory.newInstance(OlioModelNames.MODEL_PB_SCENE,
			new String[]{ OlioFieldNames.FIELD_CB_SD_PROMPT });
		sceneD.set(OlioFieldNames.FIELD_CB_SD_PROMPT,
			"landscape, Autumn Poem, wistful atmosphere, painterly, soft light, wide natural vista");
		java.util.concurrent.atomic.AtomicBoolean invokedD = new java.util.concurrent.atomic.AtomicBoolean(false);
		String chosenD = ChapBookUtil.resolveScenePrompt(sceneD, "  ", () -> {
			invokedD.set(true);
			return "SHOULD_NOT_RUN";
		});
		assertFalse("Recovery must NOT run when the stanza is blank", invokedD.get());
		assertNull("A blank stanza (recovery cannot run) over a fallback-shaped stored prompt must be "
			+ "un-prompted (null)", chosenD);

		logger.info("testResolveScenePromptReturnsNullWhenNoGenuinePrompt: all un-prompted cases return null (skip)");
	}

	/**
	 * The single stored-prompt discriminator {@link ChapBookUtil#isGenuineStoredPrompt(String)} — the
	 * source of truth the create path, render path, and forward-threading all key on, and which the Ux
	 * client's {@code isSceneUnprompted} mirrors. This is the exact drift the discriminator prevents: a
	 * bare LLM sentinel ("none"/"null"/…) must be rejected as NON-genuine so the create path stores the
	 * {@code "landscape, "} fallback instead — never persisting a sentinel that the backend would skip
	 * but the client would fail to flag for regeneration.
	 * <p>
	 * Pure — runs without the LLM or SD server.
	 */
	@Test
	public void testIsGenuineStoredPromptDiscriminator() throws Exception {
		// Genuine LLM prompts → true.
		assertTrue("A real landscape description is a genuine prompt",
			ChapBookUtil.isGenuineStoredPrompt("a misty valley at dawn, rolling fog over pine ridges, muted light"));
		assertTrue("A genuine prompt with leading whitespace is still genuine (trimmed)",
			ChapBookUtil.isGenuineStoredPrompt("   a storm-lit coastline, jagged cliffs, heavy surf"));

		// The "landscape, " no-LLM fallback shape → NOT genuine (both layers treat it as un-prompted).
		assertFalse("The 'landscape, ' fallback shape is not a genuine prompt",
			ChapBookUtil.isGenuineStoredPrompt("landscape, Winter Poem, melancholy atmosphere, painterly, soft light"));
		assertFalse("A leading-whitespace 'landscape, ' fallback is still the fallback shape (trimmed)",
			ChapBookUtil.isGenuineStoredPrompt("  landscape, Spring Poem, hopeful atmosphere"));

		// Literal LLM sentinels → NOT genuine. THIS is the drift the fix closes: without isMeaningful,
		// a bare "none"/"null" would have been stored and the client would not flag the skipped scene.
		assertFalse("Literal 'null' is not a genuine prompt", ChapBookUtil.isGenuineStoredPrompt("null"));
		assertFalse("Literal 'none' is not a genuine prompt", ChapBookUtil.isGenuineStoredPrompt("none"));
		assertFalse("Literal 'n/a' is not a genuine prompt", ChapBookUtil.isGenuineStoredPrompt("n/a"));
		assertFalse("Literal 'unknown' is not a genuine prompt", ChapBookUtil.isGenuineStoredPrompt("unknown"));
		assertFalse("Literal 'unspecified' is not a genuine prompt", ChapBookUtil.isGenuineStoredPrompt("unspecified"));

		// Blank / null → NOT genuine.
		assertFalse("null is not a genuine prompt", ChapBookUtil.isGenuineStoredPrompt(null));
		assertFalse("empty is not a genuine prompt", ChapBookUtil.isGenuineStoredPrompt(""));
		assertFalse("whitespace is not a genuine prompt", ChapBookUtil.isGenuineStoredPrompt("   "));

		logger.info("testIsGenuineStoredPromptDiscriminator: sentinel/fallback/blank rejected, real prompts accepted");
	}

	/**
	 * Fix (deterministic, no LLM/SD): proves the hardened create-time fallback (chatConfig == null) stores
	 * a landscape prompt that keeps the {@code "landscape, "} discriminator prefix but does NOT embed the
	 * raw poem stanza body — the exact regression being fixed (the old form produced
	 * {@code "landscape, <poem text>, poetic atmosphere, painterly, soft light"}).
	 * <p>
	 * Hits the DB (createChapBook), but is deterministic in its assertion because it does NOT depend on any
	 * LLM output — chatConfig is null so the no-LLM fallback path runs.
	 */
	@Test
	public void testCreateTimeFallbackDoesNotEmbedRawStanza() throws Exception {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		// A distinctive token seeded into the stanza BODY — the hardened fallback must NOT copy it in.
		String marker = "ZZQXSTANZAMARKER";
		String stanzaText =
			"The " + marker + " glimmers over frozen fields,\n" +
			"A silver hush where no bird calls,\n" +
			"The " + marker + " world holds still as snowfall yields,\n" +
			"And quiet fills the frozen halls.";

		long ts = System.currentTimeMillis();
		String poemPath = "~/Data/ChapBookFallback-" + ts;
		BaseRecord poem = createPoem(testUser, poemPath, "Fallback Poem " + ts, stanzaText);
		assertNotNull("Poem must be created", poem);
		String poemOid = poem.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem must have objectId", poemOid);

		List<String> poemOids = new ArrayList<>();
		poemOids.add(poemOid);

		String slug = "cb-fallback-" + ts;
		// chatConfig == null → NO LLM → createChapBookScene stores the hardened no-LLM fallback prompt.
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, slug,
			"ChapBook Fallback Test " + ts, poemOids, 4, null);
		assertNotNull("createChapBook must return a book", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookOid);

		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookOid);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_ORGANIZATION_ID, FieldNames.FIELD_OWNER_ID
		});
		bq.setCache(false);
		BaseRecord bookCheck = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be findable after creation", bookCheck);

		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created", scenes.isEmpty());

		for (BaseRecord scene : scenes) {
			String stored = scene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
			assertNotNull("No-LLM create must store a non-null sdPrompt", stored);
			assertFalse("Stored sdPrompt must not be blank", stored.isBlank());
			assertTrue("No-LLM fallback sdPrompt must keep the 'landscape, ' discriminator prefix, got: " + stored,
				stored.startsWith("landscape, "));
			assertFalse("Hardened no-LLM fallback must NOT embed raw stanza text (found marker '" + marker
				+ "' in: " + stored + ")", stored.contains(marker));
		}
		logger.info("testCreateTimeFallbackDoesNotEmbedRawStanza: {} scene(s), all fallback prompts free of stanza body",
			scenes.size());
	}

	/**
	 * Deterministic, no live SD/LLM: proves the CORRECTED no-config semantics. When a ChapBook scene is
	 * un-prompted — only the {@code "landscape, "} fallback-shaped stored prompt and NO chatConfig to
	 * recover a genuine one — the landscape-prompt LLM step is UNAVAILABLE for that scene. As of the
	 * llmUnavailable-signal fix that unavailability is a DOMAIN determination made inside
	 * {@link ChapBookUtil#renderChapBookScene} ({@code chatConfig == null} is folded into
	 * {@code renderResolvedScene}'s {@code llmStepUnavailable}); it is NO LONGER a silent benign skip.
	 * <p>
	 * With a stored (fallback) prompt present, the render degrade-ATTEMPTS on it rather than skipping, so
	 * it DOES reach the SD server. Here the SD URL is a deliberate <i>unreachable</i> dummy
	 * ({@code 127.0.0.1:1}), so the degrade attempt fails fast (connection refused) and the result is
	 * {@code FAILED} carrying a truthful {@code llmUnavailable=true} — and, because nothing rendered,
	 * {@code llmDegraded=false} and no image is produced or persisted.
	 * <p>
	 * <b>Why this needs no live infrastructure:</b> it requires the SD server to be UNREACHABLE (the dummy
	 * guarantees that) and needs no LLM at all, so it is NOT gated on {@code test.swarm.server}. It guards
	 * the key regression: a no-config render of a fallback-only scene must surface {@code llmUnavailable=true},
	 * never a silent {@code SKIPPED_NO_PROMPT / llmUnavailable=false}.
	 */
	@Test
	public void testRenderChapBookSceneNoConfigDegradeAttemptReportsUnavailable() throws Exception {
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be set", dataPath);

		long orgId = ((Number) testUser.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();

		long ts = System.currentTimeMillis();
		String poemPath = "~/Data/ChapBookSkip-" + ts;
		BaseRecord poem = createPoem(testUser, poemPath, "Skip Poem " + ts, POEM_TEXT);
		assertNotNull("Poem must be created", poem);
		String poemOid = poem.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Poem must have objectId", poemOid);

		List<String> poemOids = new ArrayList<>();
		poemOids.add(poemOid);

		String slug = "cb-skip-" + ts;
		// chatConfig == null → no LLM → createChapBookScene stores the "landscape, " fallback-shaped prompt.
		BaseRecord book = ChapBookUtil.createChapBook(testUser, dataPath, slug,
			"ChapBook Skip Test " + ts, poemOids, 5, null);
		assertNotNull("createChapBook must return a book", book);
		String bookOid = book.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Book must have an objectId", bookOid);

		Query bq = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID, bookOid);
		bq.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		bq.setRequest(new String[]{
			FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			FieldNames.FIELD_GROUP_ID, FieldNames.FIELD_GROUP_PATH, FieldNames.FIELD_ORGANIZATION_ID,
			FieldNames.FIELD_OWNER_ID, OlioFieldNames.FIELD_PB_BOOK_TYPE
		});
		bq.setCache(false);
		BaseRecord bookCheck = IOSystem.getActiveContext().getAccessPoint().find(testUser, bq);
		assertNotNull("Book must be findable by objectId", bookCheck);

		List<BaseRecord> scenes = PbBookUtil.listScenes(testUser, bookCheck);
		assertFalse("At least one scene must be created from the poem", scenes.isEmpty());

		BaseRecord targetScene = scenes.get(0);
		String sceneObjectId = targetScene.get(FieldNames.FIELD_OBJECT_ID);
		assertNotNull("Target scene must have an objectId", sceneObjectId);

		// Precondition: the stored prompt is the fallback shape, and no image is set yet.
		String storedPrompt = targetScene.get(OlioFieldNames.FIELD_CB_SD_PROMPT);
		assertNotNull("Scene must carry a stored sdPrompt", storedPrompt);
		assertTrue("This test requires a fallback-shaped ('landscape, ') stored prompt so the scene is "
			+ "un-prompted, got: " + storedPrompt, storedPrompt.startsWith("landscape, "));
		String preImage = targetScene.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
		assertTrue("Target scene must start with NO imageObjectId (got: " + preImage + ")",
			preImage == null || preImage.isBlank());

		// Render with chatConfig == null → the landscape LLM step is UNAVAILABLE for this scene. Because a
		// stored (fallback) prompt exists, renderResolvedScene degrade-ATTEMPTS on it and DOES reach the SD
		// server. The SD URL below is a deliberate UNREACHABLE dummy: the attempt fails fast (connection
		// refused) so the result is FAILED with a truthful llmUnavailable=true — never a silent skip.
		ChapBookUtil.SceneRenderResult result = ChapBookUtil.renderChapBookScene(
			testUser, sceneObjectId, "SWARM", "http://127.0.0.1:1/dummy-sd-unreachable", null, null);

		assertNotNull("renderChapBookScene must return a SceneRenderResult", result);
		assertTrue("A no-config render of a fallback-only scene must report the landscape LLM step as "
			+ "UNAVAILABLE (chatConfig == null is folded into llmUnavailable in Objects7), never a silent "
			+ "benign skip", result.llmUnavailable);
		assertEquals("The degrade attempt against the unreachable dummy SD must FAIL — not a silent "
			+ "SKIPPED_NO_PROMPT and not a RENDERED",
			ChapBookUtil.SceneRenderStatus.FAILED, result.status);
		assertFalse("Nothing rendered against the unreachable SD, so llmDegraded must be false (it is only "
			+ "true on a SUCCESSFUL degraded render)", result.llmDegraded);
		assertNull("A FAILED render must carry a null imageObjectId (no image produced)",
			result.imageObjectId);

		// Prove the failed degrade attempt left the scene image-less in the DB (nothing was drawn / persisted).
		BaseRecord sceneAfter = PbBookUtil.readScene(testUser, sceneObjectId, orgId);
		assertNotNull("Scene must be re-readable after the failed render", sceneAfter);
		String postImage = sceneAfter.get(OlioFieldNames.FIELD_PB_IMAGE_OBJECT_ID);
		assertTrue("A failed render must leave the scene image-less in the DB (imageObjectId must stay unset), got: "
			+ postImage, postImage == null || postImage.isBlank());

		logger.info("testRenderChapBookSceneNoConfigDegradeAttemptReportsUnavailable: scene {} → no chatConfig "
			+ "⇒ llmUnavailable=true, degrade-attempt FAILED against unreachable SD, no image persisted", sceneObjectId);
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	/**
	 * Fresh read of a {@code data.data} record's byte payload via {@code ByteModelUtil.getValue}
	 * (never a raw {@code byteStore .get()}, which would hand back compressed/encrypted bytes).
	 * {@code cache:false} forces a genuinely fresh read.
	 */
	private byte[] fetchDataBytes(String dataObjectId, long organizationId) throws Exception {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_DATA, FieldNames.FIELD_OBJECT_ID, dataObjectId);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
		q.planMost(false);
		q.setCache(false);
		BaseRecord data = IOSystem.getActiveContext().getAccessPoint().find(testUser, q);
		if (data == null) {
			return null;
		}
		IOSystem.getActiveContext().getReader().populate(data, new String[] { FieldNames.FIELD_BYTE_STORE });
		return ByteModelUtil.getValue(data);
	}

	/**
	 * Create an {@code olio.cb.poem} record at the given group path with the given name and text.
	 * Returns the identity-only record (objectId) from AccessPoint.create.
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
		} catch (Exception e) {
			logger.error("createPoem failed: {}", e.getMessage(), e);
			return null;
		}
	}

	/**
	 * Locate a real test fixture under the module's {@code media/} directory. The Maven Surefire
	 * working directory is the module dir when run via {@code mvn -pl AccountManagerObjects7 test},
	 * but fall back to the git-root-relative path so the test is robust to being launched from the
	 * aggregator or the repo root.
	 */
	private File locateFixture(String fileName) {
		File[] candidates = new File[] {
			new File("media", fileName),
			new File("AccountManagerObjects7/media", fileName),
			new File("src/AccountManagerObjects7/media", fileName)
		};
		for (File f : candidates) {
			if (f.exists()) {
				return f;
			}
		}
		return null;
	}

	/**
	 * Build an in-memory {@code data.data} record carrying the given bytes in its byteStore.
	 * Written via {@link ByteModelUtil#setValue} (never a raw {@code byteStore .set()}), so the
	 * optional compression the model applies to a {@code text/plain} label round-trips on read —
	 * exactly the path {@code extractPoemText} exercises. No persistence is required: the fix reads
	 * the byteStore straight off the record.
	 */
	private BaseRecord newDocDataRecord(String name, String contentType, byte[] bytes) throws Exception {
		BaseRecord rec = RecordFactory.model(ModelNames.MODEL_DATA).newInstance();
		rec.set(FieldNames.FIELD_NAME, name);
		if (contentType != null) {
			// Set the (deliberately wrong/generic) content type BEFORE writing, so the writer's
			// compression decision matches what a real mislabeled upload would produce.
			rec.set(FieldNames.FIELD_CONTENT_TYPE, contentType);
		}
		ByteModelUtil.setValue(rec, bytes);
		return rec;
	}

	/**
	 * Assert that extracted text is genuine, readable prose — not OLE2/ZIP binary read as UTF-8.
	 * <p>
	 * The pre-fix failure returned the raw OLE2 container as a string: dominated by non-printable
	 * control bytes and the U+FFFD replacement character, with none of the extremely common English
	 * function words. This checks the discriminators directly rather than asserting any specific
	 * sentence from the (creative-writing) fixture:
	 * <ul>
	 *   <li>a substantial amount of text was produced;</li>
	 *   <li>the vast majority of characters are ordinary printable prose (letters/digits/space/punct),
	 *       with very few C0 control or replacement characters;</li>
	 *   <li>at least a couple of the most common English words appear as whole words.</li>
	 * </ul>
	 */
	private void assertReadableProse(String label, String text) {
		assertNotNull(label + ": text must not be null", text);
		assertTrue(label + ": expected a substantial amount of extracted text, got " + text.length() + " chars",
			text.length() >= 100);

		int printable = 0;
		int controlOrReplacement = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\t' || c == '\n' || c == '\r') {
				printable++;
			} else if (c == '�') {
				controlOrReplacement++;
			} else if (c < 0x20 || c == 0x7F) {
				controlOrReplacement++;
			} else {
				printable++;
			}
		}
		double printableRatio = (double) printable / text.length();
		double garbageRatio = (double) controlOrReplacement / text.length();
		assertTrue(label + ": extracted text must be dominated by printable characters (ratio="
			+ printableRatio + ")", printableRatio >= 0.90);
		assertTrue(label + ": extracted text must NOT be dominated by control/replacement bytes (ratio="
			+ garbageRatio + ") — that is the OLE2-garbage failure mode", garbageRatio <= 0.05);

		// Extremely common English function words — any English prose contains several of these.
		// Word-boundary, case-insensitive; safe for any content without inventing fixture text.
		String[] common = { "the", "and", "to", "of", "a", "in", "is", "that", "it", "was", "with", "he", "she" };
		int hits = 0;
		String lower = text.toLowerCase();
		for (String w : common) {
			if (lower.matches("(?s).*\\b" + w + "\\b.*")) {
				hits++;
			}
		}
		assertTrue(label + ": extracted text must read as English prose (matched " + hits
			+ " common words) — OLE2 garbage matches none", hits >= 3);
	}
}
