package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.objects.tests.BaseTest;
import org.cote.accountmanager.olio.OlioContextUtil;
import org.cote.accountmanager.olio.illustration.QualityPresets;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.PbNodeTypeEnumType;
import org.cote.accountmanager.schema.type.PbQualityPresetEnumType;
import org.junit.Before;
import org.junit.Test;

/// 2026-11-16 (Stephen): a PictureBook scene render requires the book's effective style (book -> series -> library,
/// PbStyleResolver.resolve) to carry a description. A style-less book used to render anyway under the hardcoded
/// STANDARD preset fallback (2 variants + VLM QA per scene; book CyberH on the live stack). Through PbServiceFacade as
/// normal (non-admin) users on the live test DB:
///   case01 - a style-less book is 409 STYLE_REQUIRED_MESSAGE from every gated entry point (render-all, scene render,
///            render-plan, regenerate-background) and the refusal writes nothing (no workflow).
///   case02 - a stranger on the same style-less book is still the book's 404 from every entry point, never the 409.
///   case03 - after the owner gives the book a described style through the production style PUT path, the same calls
///            pass the predicate. No SD server is passed, so the proof is the distinct downstream refusal each path
///            gives next - the planner's / executor's 500 "SD server not configured" - and nothing reaches SwarmUI.
///            The effective preset of a style that names no preset is the resource default (DRAFT), not STANDARD.
///   case04 - requestRegenerate is gated only for a scene render node: a RENDER_PLAN node of a style-less book is the
///            409, a PORTRAIT node of the same book is not (it reaches the executor's own 500).
///   case05 - a ChapBook is exempt (its look comes from the effective non-flux sd.config, which a style description
///            would override): a style-less CHAPBOOK is not refused with the style 409 by requestSceneRender (forced and
///            not) or requestRenderAll - any other outcome is past the gate. The ChapBook fixture gets no style.
///   case06 - compose Quick-start (an SD render per composed scene) is the same 409 on a style-less book and the 404
///            for a stranger; a plain compose is not gated; once described, Quick-start passes to its own 500.
public class TestPbRenderStyleGate extends BaseTest {

	private static final String OWNER = "pbStyleGateOwner";
	private static final String STRANGER = "pbStyleGateStranger";
	private static final long LOCAL_TIMEOUT_MS = 5L * 60L * 1000L;
	private static final String NO_SD = "SD server not configured";

	@Before
	public void styleGateSetup() {
		OlioModelNames.use();
		OlioContextUtil.clearCache();
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		PbStaleUtil.clear();
	}

	private static final class Fixture {
		BaseRecord owner;
		long orgId;
		BaseRecord book;
		String slug;
		String bookOid;
		List<String> sceneOids = new ArrayList<>();
	}

	private interface Call {
		void run() throws Exception;
	}

	// ─────────────────────────────── cases ───────────────────────────────

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case01_styleLessBookIs409FromEveryGatedEntryPointAndWritesNothing() throws Exception {
		Fixture f = fixture("sg1", 2);
		try {
			assertStyleLess(f);
			String s0 = f.sceneOids.get(0);
			String api = SDAPIEnumType.SWARM.name();
			expectStyle409(() -> PbServiceFacade.requestRenderAll(f.owner, f.bookOid, api, null), "render all");
			expectStyle409(() -> PbServiceFacade.requestSceneRender(f.owner, f.bookOid, s0, api, null, false), "scene render");
			expectStyle409(() -> PbServiceFacade.requestSceneRender(f.owner, f.bookOid, s0, api, null, true), "scene render forced");
			expectStyle409(() -> PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, s0, api, null), "render plan");
			expectStyle409(() -> PbServiceFacade.requestRegenerateBackground(f.owner, f.bookOid, s0, api, null, null), "regenerate background");
			assertNull("a style refusal writes nothing: no workflow", PbGraphUtil.findWorkflow(f.owner, f.book));
		}
		finally {
			cleanup(f);
		}
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case02_strangerOnAStyleLessBookIsStill404() throws Exception {
		Fixture f = fixture("sg2", 1);
		try {
			assertStyleLess(f);
			BaseRecord stranger = getCreateUser(STRANGER);
			assertNotNull("stranger", stranger);
			assertFalse("precondition: a different user", f.owner.get(FieldNames.FIELD_ID).equals(stranger.get(FieldNames.FIELD_ID)));
			String s0 = f.sceneOids.get(0);
			String api = SDAPIEnumType.SWARM.name();
			expect404(() -> PbServiceFacade.requestRenderAll(stranger, f.bookOid, api, null), "render all");
			expect404(() -> PbServiceFacade.requestSceneRender(stranger, f.bookOid, s0, api, null, false), "scene render");
			expect404(() -> PbServiceFacade.requestRenderPlanScene(stranger, f.bookOid, s0, api, null), "render plan");
			expect404(() -> PbServiceFacade.requestRegenerateBackground(stranger, f.bookOid, s0, api, null, null), "regenerate background");
			assertNull("the stranger's refusals write nothing", PbGraphUtil.findWorkflow(f.owner, f.book));
		}
		finally {
			cleanup(f);
		}
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case03_describedStylePassesThePredicate() throws Exception {
		Fixture f = fixture("sg3", 2);
		try {
			assertStyleLess(f);
			String s0 = f.sceneOids.get(0);
			String s1 = f.sceneOids.get(1);
			String api = SDAPIEnumType.SWARM.name();
			expectStyle409(() -> PbServiceFacade.requestSceneRender(f.owner, f.bookOid, s0, api, null, false), "scene render before the style");

			Map<String, Object> dto = PbRenderStyleFixture.describe(f.owner, f.bookOid);
			assertNotNull("style PUT", dto);
			assertTrue("the book now has a renderable style", PbServiceFacade.hasRenderableStyle(f.owner, readBook(f)));
			@SuppressWarnings("unchecked")
			Map<String, Object> effective = (Map<String, Object>) dto.get("effective");
			assertNotNull("effective block on the style DTO: " + dto, effective);
			@SuppressWarnings("unchecked")
			Map<String, Object> fields = (Map<String, Object>) effective.get("fields");
			assertEquals("the UI's styleHasDescription source", PbRenderStyleFixture.DESCRIPTION, fields.get(FieldNames.FIELD_DESCRIPTION));
			/// A style that names no preset falls back to the resource's defaultPreset, not the old hardcoded STANDARD.
			assertEquals("unset preset is the resource default", QualityPresets.defaultPreset().toString(), effective.get("qualityPreset"));
			assertEquals("...which ships as DRAFT", PbQualityPresetEnumType.DRAFT.toString(), effective.get("qualityPreset"));
			assertEquals("the plan's effective preset agrees", PbQualityPresetEnumType.DRAFT,
				PbRenderPlanUtil.effectivePreset(f.owner, readBook(f), null).getName());

			/// Past the predicate: each path's next refusal, with no SD server, is its own 500.
			expectNoSd(() -> PbServiceFacade.requestSceneRender(f.owner, f.bookOid, s0, api, null, false), "scene render");
			expectNoSd(() -> PbServiceFacade.requestRenderAll(f.owner, f.bookOid, api, null), "render all");
			expectNoSd(() -> PbServiceFacade.requestRenderPlanScene(f.owner, f.bookOid, s1, api, null), "render plan");
			/// regenerate-background on a never-rendered scene stops at its own 409 "render it first" - past the style.
			try {
				PbServiceFacade.requestRegenerateBackground(f.owner, f.bookOid, s0, api, null, null);
				fail("a never-rendered scene has no background to regenerate");
			}
			catch(PictureBookException e) {
				logger.info("style gate case03 regenerate background: " + e.getStatus() + " " + e.getMessage());
				assertEquals(409, e.getStatus());
				assertEquals("Scene has no selected render to regenerate the background of; render it first", e.getMessage());
			}
		}
		finally {
			cleanup(f);
		}
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case04_regenerateIsGatedOnlyForSceneRenderNodes() throws Exception {
		Fixture f = fixture("sg4", 1);
		try {
			assertStyleLess(f);
			String wfPath = PbBookUtil.workflowGroupPath(f.slug);
			BaseRecord wf = PbGraphUtil.getCreateWorkflow(f.owner, f.book, wfPath);
			assertNotNull("workflow", wf);
			BaseRecord plan = PbGraphUtil.addNode(f.owner, wf, PbPipelineUtil.renderPlanHandle(f.sceneOids.get(0)), PbNodeTypeEnumType.RENDER_PLAN, wfPath, 0);
			assertNotNull("render-plan node", plan);
			BaseRecord portrait = PbGraphUtil.addNode(f.owner, wf, "sg4-portrait-" + shortId(), PbNodeTypeEnumType.PORTRAIT, wfPath, 1);
			assertNotNull("portrait node", portrait);
			String api = SDAPIEnumType.SWARM.name();
			expectStyle409(() -> PbServiceFacade.requestRegenerate(f.owner, f.bookOid, plan.get(FieldNames.FIELD_OBJECT_ID), api, null), "regenerate RENDER_PLAN");
			expectNoSd(() -> PbServiceFacade.requestRegenerate(f.owner, f.bookOid, portrait.get(FieldNames.FIELD_OBJECT_ID), api, null), "regenerate PORTRAIT");
		}
		finally {
			cleanup(f);
		}
	}

	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case05_chapBookIsExempt() throws Exception {
		Fixture f = fixture("sg5", 1);
		try {
			assertStyleLess(f);
			BaseRecord patch = PbGraphUtil.patchOf(f.book, OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_BOOK_TYPE);
			patch.set(OlioFieldNames.FIELD_PB_BOOK_TYPE, "CHAPBOOK");
			assertNotNull("book type patch", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
			Object type = readBook(f).get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
			assertEquals("precondition: the book is a CHAPBOOK", "CHAPBOOK", String.valueOf(type).toUpperCase());
			assertFalse("precondition: the ChapBook has no renderable style", PbServiceFacade.hasRenderableStyle(f.owner, readBook(f)));
			/// Past the gate: any outcome other than the style 409. With no SD server both stop at the planner's 500.
			expectNotStyle409(() -> PbServiceFacade.requestSceneRender(f.owner, f.bookOid, f.sceneOids.get(0), SDAPIEnumType.SWARM.name(), null, true),
				"ChapBook scene render");
			expectNotStyle409(() -> PbServiceFacade.requestSceneRender(f.owner, f.bookOid, f.sceneOids.get(0), SDAPIEnumType.SWARM.name(), null, false),
				"ChapBook scene render un-forced");
			expectNotStyle409(() -> PbServiceFacade.requestRenderAll(f.owner, f.bookOid, SDAPIEnumType.SWARM.name(), null),
				"ChapBook render all");
		}
		finally {
			cleanup(f);
		}
	}

	/// Compose Quick-start renders every composed scene on the SD server (PbComposeJobUtil -> executePlanScene, below
	/// the scene-render gates), so requestCompose carries the same predicate when quickStart is set; a plain compose
	/// renders nothing and is not gated. Nothing here queues a job: the plain compose names an unknown chat config (its
	/// 404 comes before the queue), and the styled Quick-start passes no SD server (its 500 comes before the queue).
	@Test(timeout = LOCAL_TIMEOUT_MS)
	public void case06_composeQuickStartIsGatedPlainComposeIsNot() throws Exception {
		Fixture f = fixture("sg6", 1);
		try {
			assertStyleLess(f);
			String api = SDAPIEnumType.SWARM.name();
			expectStyle409(() -> PbServiceFacade.requestCompose(f.owner, f.bookOid, true, false, null, api, null), "compose quick start");

			BaseRecord stranger = getCreateUser(STRANGER);
			assertNotNull("stranger", stranger);
			expect404(() -> PbServiceFacade.requestCompose(stranger, f.bookOid, true, false, null, api, null), "compose quick start");

			String missing = "pb-style-gate-no-such-config-" + shortId();
			try {
				PbServiceFacade.requestCompose(f.owner, f.bookOid, false, false, missing, api, null);
				fail("plain compose with an unknown chat config must stop at the config 404");
			}
			catch(PictureBookException e) {
				logger.info("style gate plain compose: " + e.getStatus() + " " + e.getMessage());
				assertEquals("plain compose is not style-gated: " + e.getMessage(), 404, e.getStatus());
				assertEquals("Chat config not found: " + missing, e.getMessage());
			}
			assertNull("no compose refusal writes a workflow", PbGraphUtil.findWorkflow(f.owner, f.book));

			assertNotNull("style PUT", PbRenderStyleFixture.describe(f.owner, f.bookOid));
			try {
				PbServiceFacade.requestCompose(f.owner, f.bookOid, true, false, null, api, null);
				fail("a styled Quick-start with no SD server must stop at its own 500");
			}
			catch(PictureBookException e) {
				logger.info("style gate passed compose quick start: " + e.getStatus() + " " + e.getMessage());
				assertEquals("past the style predicate: " + e.getMessage(), 500, e.getStatus());
				assertTrue(e.getMessage(), e.getMessage().startsWith(NO_SD));
			}
		}
		finally {
			cleanup(f);
		}
	}

	// ─────────────────────────────── fixture ───────────────────────────────

	/// A V3 book (real PbBookUtil.createBook, so it has its world and groups) with {@code sceneCount} scenes carrying
	/// setting / action / mood, owned by {@code OWNER}. No style of its own, no workflow.
	private Fixture fixture(String prefix, int sceneCount) throws Exception {
		Fixture f = new Fixture();
		f.owner = getCreateUser(OWNER);
		assertNotNull("owner", f.owner);
		f.orgId = ((Number) f.owner.get(FieldNames.FIELD_ORGANIZATION_ID)).longValue();
		String dataPath = testProperties.getProperty("test.datagen.path");
		assertNotNull("test.datagen.path must be configured", dataPath);
		f.slug = prefix + "-" + shortId();
		f.book = PbBookUtil.createBook(f.owner, dataPath, f.slug, "Style gate " + f.slug);
		assertNotNull("createBook", f.book);
		f.bookOid = f.book.get(FieldNames.FIELD_OBJECT_ID);
		for(int i = 0; i < sceneCount; i++) {
			BaseRecord scene = PbBookUtil.createScene(f.owner, f.book, i, "Scene " + i, PbBookUtil.bookGroupPath(f.slug));
			assertNotNull("scene " + i, scene);
			BaseRecord patch = PbGraphUtil.patchOf(scene, OlioModelNames.MODEL_PB_SCENE,
				OlioFieldNames.FIELD_PB_SETTING, OlioFieldNames.FIELD_PB_ACTION, OlioFieldNames.FIELD_PB_MOOD);
			patch.set(OlioFieldNames.FIELD_PB_SETTING, "a lighthouse on a basalt headland at dusk");
			patch.set(OlioFieldNames.FIELD_PB_ACTION, "gulls wheel over the breakwater");
			patch.set(OlioFieldNames.FIELD_PB_MOOD, "windswept and expectant");
			assertNotNull("scene text patch must persist", IOSystem.getActiveContext().getAccessPoint().update(f.owner, patch));
			f.sceneOids.add(scene.get(FieldNames.FIELD_OBJECT_ID));
		}
		return f;
	}

	/// The condition under test must actually hold: the book's EFFECTIVE style (which includes the organization's
	/// LIBRARY style) has no description. A library style with one would make every book renderable.
	private void assertStyleLess(Fixture f) {
		BaseRecord bk = readBook(f);
		PbStyleResolver.EffectiveStyle es = PbStyleResolver.resolve(f.owner, bk);
		logger.info("style gate precondition for " + f.slug + ": " + es + " description=" + es.value(FieldNames.FIELD_DESCRIPTION));
		assertFalse("precondition: the book's effective style has no description (chain " + es + ")",
			PbServiceFacade.hasRenderableStyle(f.owner, bk));
	}

	private BaseRecord readBook(Fixture f) {
		BaseRecord bk = PbBookUtil.readBook(f.owner, f.bookOid, f.orgId);
		assertNotNull("readBook " + f.slug, bk);
		return bk;
	}

	private void cleanup(Fixture f) {
		if(f == null || f.book == null) return;
		try {
			PbDeleteUtil.deleteBookComplete(f.owner, f.bookOid);
		}
		catch(Exception e) {
			logger.warn("Cleanup of " + f.slug + ": " + e.getMessage());
		}
	}

	private static String shortId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}

	private void expectStyle409(Call c, String what) throws Exception {
		try {
			c.run();
			fail(what + ": a style-less book must be refused");
		}
		catch(PictureBookException e) {
			logger.info("style gate " + what + ": " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": " + e.getMessage(), 409, e.getStatus());
			assertEquals(what, PbServiceFacade.STYLE_REQUIRED_MESSAGE, e.getMessage());
		}
	}

	private void expect404(Call c, String what) throws Exception {
		try {
			c.run();
			fail(what + ": a stranger must be refused");
		}
		catch(PictureBookException e) {
			logger.info("style gate stranger " + what + ": " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": " + e.getMessage(), 404, e.getStatus());
		}
	}

	/// The call got past the style predicate: it may succeed or be refused by anything downstream, but never with the
	/// style 409. Logged so the run shows which outcome it was.
	private void expectNotStyle409(Call c, String what) throws Exception {
		try {
			c.run();
			logger.info("style gate exempt " + what + ": accepted");
		}
		catch(PictureBookException e) {
			logger.info("style gate exempt " + what + ": " + e.getStatus() + " " + e.getMessage());
			assertFalse(what + " must not be the style 409: " + e.getStatus() + " " + e.getMessage(),
				e.getStatus() == 409 && PbServiceFacade.STYLE_REQUIRED_MESSAGE.equals(e.getMessage()));
		}
	}

	private void expectNoSd(Call c, String what) throws Exception {
		try {
			c.run();
			fail(what + ": with no SD server the call must stop at its own 500");
		}
		catch(PictureBookException e) {
			logger.info("style gate passed " + what + ": " + e.getStatus() + " " + e.getMessage());
			assertEquals(what + ": " + e.getMessage(), 500, e.getStatus());
			assertEquals(what, NO_SD, e.getMessage());
		}
	}
}
