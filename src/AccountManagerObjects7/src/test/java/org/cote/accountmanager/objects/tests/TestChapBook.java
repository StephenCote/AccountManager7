package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.objects.tests.olio.OlioTestUtil;
import org.cote.accountmanager.olio.picturebook.ChapBookUtil;
import org.cote.accountmanager.olio.picturebook.PbBookUtil;
import org.cote.accountmanager.olio.picturebook.PbGraphUtil;
import org.cote.accountmanager.olio.picturebook.PbServiceFacade;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
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

	// ── helpers ──────────────────────────────────────────────────────────────

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
}
