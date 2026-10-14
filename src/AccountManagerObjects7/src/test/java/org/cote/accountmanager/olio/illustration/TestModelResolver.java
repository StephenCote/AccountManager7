package org.cote.accountmanager.olio.illustration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.cote.accountmanager.olio.illustration.ModelResolver.Candidate;
import org.cote.accountmanager.olio.illustration.ModelResolver.Reason;
import org.cote.accountmanager.olio.illustration.ModelResolver.Request;
import org.cote.accountmanager.olio.illustration.ModelResolver.Resolution;
import org.cote.accountmanager.olio.illustration.ModelResolver.Step;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.cote.accountmanager.schema.type.PbStrategyEnumType;
import org.junit.Before;
import org.junit.Test;

/**
 * Pure tests for the one model policy (P2-2): {@link ModelResolver}, {@link CheckpointNames} and
 * {@link ModelTaskKeys} against a fixed in-memory inventory shaped like a Swarm listing (folder +
 * extension). No DB, no SD server, no BaseTest. The family rules come from the real
 * {@code capabilities.json} on the classpath (flux2 enabled/refs/no masks, sdxl enabled/no refs/masks,
 * kontext disabled), so a change to that resource is caught here too.
 */
public class TestModelResolver {

	static final String SDXL = "SDXL/juggernautXL_ragnarokBy.safetensors";
	static final String FLUX2 = "Flux/flux2Klein_9b.safetensors";
	static final String KONTEXT = "Flux/flux1-kontext-dev.safetensors";
	static final String UNKNOWN_FAMILY = "misc/foo_v1.safetensors";
	static final List<String> INV = Arrays.asList(SDXL, FLUX2, KONTEXT, UNKNOWN_FAMILY);

	@Before
	public void reload() {
		RendererCapabilities.clearCache();
	}

	private static Request.Builder req(PbModelTaskEnumType task) {
		return Request.builder(task);
	}

	private static Map<PbModelTaskEnumType, String> taskModels(PbModelTaskEnumType t, String model) {
		Map<PbModelTaskEnumType, String> m = new EnumMap<>(PbModelTaskEnumType.class);
		m.put(t, model);
		return m;
	}

	// ─────────────────────────────── step order ───────────────────────────────

	@Test
	public void overrideInstalledWins() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.override("juggernautXL_ragnarokBy")
			.taskModels(taskModels(PbModelTaskEnumType.PORTRAIT, "flux2Klein_9b"))
			.styleModel("flux2Klein_9b")
			.deploymentModel("flux2Klein_9b")
			.build(), INV);
		assertTrue(r.toString(), r.isResolved());
		assertEquals(Step.OVERRIDE, r.getStep());
		assertEquals(SDXL, r.getModel());
		assertEquals(Reason.OK, r.getReason());
		assertFalse(r.isDegraded());
		assertNull(r.getDegradationCode());
		assertNull(r.getDegradationText());
		assertEquals(1, r.getCandidates().size());
		assertEquals(RendererCapabilities.PATTERN_SDXL, r.getFamily());
	}

	@Test
	public void overrideNotInstalledSubstitutesStyleTask() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.override("missing_model_xl")
			.taskModels(taskModels(PbModelTaskEnumType.PORTRAIT, "juggernautXL_ragnarokBy"))
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(Step.STYLE_TASK, r.getStep());
		assertEquals(SDXL, r.getModel());
		assertTrue(r.isDegraded());
		assertEquals(ModelResolver.DEGRADATION_MODEL_SUBSTITUTED, r.getDegradationCode());
		assertEquals(2, r.getCandidates().size());
		Candidate first = r.getCandidates().get(0);
		assertEquals(Step.OVERRIDE, first.getStep());
		assertEquals(Reason.NOT_INSTALLED, first.getReason());
		assertEquals("missing_model_xl", first.getRequested());
		assertNull(first.getListedName());
		assertNull(first.getCapabilities());
		assertEquals(Reason.OK, r.getCandidates().get(1).getReason());
		String text = r.getDegradationText();
		assertNotNull(text);
		assertTrue(text, text.contains("missing_model_xl"));
		assertTrue(text, text.contains("not installed"));
		assertTrue(text, text.contains(SDXL));
		assertTrue(text, text.contains(Step.STYLE_TASK.name()));
		assertEquals(text, ModelResolver.degradationText(r));
	}

	@Test
	public void styleTaskModelWins() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.taskModels(taskModels(PbModelTaskEnumType.PORTRAIT, "juggernautXL_ragnarokBy"))
			.styleModel("flux2Klein_9b")
			.deploymentModel("flux2Klein_9b")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(Step.STYLE_TASK, r.getStep());
		assertEquals(SDXL, r.getModel());
		assertFalse(r.isDegraded());
	}

	@Test
	public void styleTaskModelForAnotherTaskIsIgnored() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.LOCATION)
			.taskModels(taskModels(PbModelTaskEnumType.PORTRAIT, "flux2Klein_9b"))
			.styleModel("juggernautXL_ragnarokBy")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(Step.STYLE_DEFAULT, r.getStep());
		assertEquals(SDXL, r.getModel());
		assertEquals(1, r.getCandidates().size());
	}

	@Test
	public void styleModelUsedForPortrait() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.styleModel("juggernautXL_ragnarokBy")
			.styleFlux2Model("flux2Klein_9b")
			.deploymentModel("flux2Klein_9b")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(Step.STYLE_DEFAULT, r.getStep());
		assertEquals(SDXL, r.getModel());
	}

	@Test
	public void styleFlux2UsedForSceneDirect() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.SCENE_DIRECT)
			.styleModel("juggernautXL_ragnarokBy")
			.styleFlux2Model("flux2Klein_9b")
			.deploymentModel("juggernautXL_ragnarokBy")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(Step.STYLE_DEFAULT, r.getStep());
		assertEquals(FLUX2, r.getModel());
		assertEquals(RendererCapabilities.PATTERN_FLUX2, r.getFamily());
	}

	@Test
	public void deploymentDefaultIsLast() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.deploymentModel("juggernautXL_ragnarokBy")
			.deploymentFlux2Model("flux2Klein_9b")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(Step.DEPLOYMENT_DEFAULT, r.getStep());
		assertEquals(SDXL, r.getModel());
		assertFalse("empty earlier steps are not substitutions", r.isDegraded());
		assertEquals(1, r.getCandidates().size());
	}

	@Test
	public void deploymentFlux2ForSceneDirect() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.SCENE_DIRECT)
			.deploymentModel("juggernautXL_ragnarokBy")
			.deploymentFlux2Model("flux2Klein_9b")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(Step.DEPLOYMENT_DEFAULT, r.getStep());
		assertEquals(FLUX2, r.getModel());
	}

	@Test
	public void resolvedModelIsTheListedNameNotTheRequestedSpelling() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.SCENE_DIRECT)
			.override("flux2Klein_9b")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(FLUX2, r.getModel());
		assertEquals("flux2Klein_9b", r.getCandidates().get(0).getRequested());
		assertEquals(FLUX2, r.getCandidates().get(0).getListedName());
	}

	// ─────────────────────────────── failures ───────────────────────────────

	@Test
	public void nothingInstalledIsUnresolvedNotThrown() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.override("a")
			.taskModels(taskModels(PbModelTaskEnumType.PORTRAIT, "b"))
			.styleModel("c")
			.deploymentModel("d")
			.build(), Collections.emptyList());
		assertFalse(r.isResolved());
		assertNull(r.getModel());
		assertNull(r.getStep());
		assertNull(r.getFamily());
		assertEquals(Reason.NO_CANDIDATE, r.getReason());
		assertFalse(r.isDegraded());
		assertNull(r.getInventoryError());
		assertEquals(4, r.getCandidates().size());
		for(Candidate c : r.getCandidates()) {
			assertEquals(c.toString(), Reason.NOT_INSTALLED, c.getReason());
		}
		assertEquals(Step.OVERRIDE, r.getCandidates().get(0).getStep());
		assertEquals(Step.DEPLOYMENT_DEFAULT, r.getCandidates().get(3).getStep());
		assertNotNull(r.getDegradationText());
		assertTrue(r.getDegradationText(), r.getDegradationText().contains("PORTRAIT"));
		assertNull(ModelResolver.degradationText(r));
	}

	@Test
	public void nothingRequestedIsUnresolvedWithNoCandidates() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT).build(), INV);
		assertFalse(r.isResolved());
		assertEquals(Reason.NO_CANDIDATE, r.getReason());
		assertTrue(r.getCandidates().isEmpty());
	}

	@Test
	public void blankStepValuesAddNoCandidate() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.override("  ")
			.styleModel("")
			.deploymentModel(" juggernautXL_ragnarokBy ")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(1, r.getCandidates().size());
		assertEquals(Step.DEPLOYMENT_DEFAULT, r.getStep());
		assertFalse(r.isDegraded());
	}

	@Test
	public void unknownTaskIsUnresolved() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.UNKNOWN).override("juggernautXL_ragnarokBy").build(), INV);
		assertFalse(r.isResolved());
		assertEquals(Reason.NO_CANDIDATE, r.getReason());
		assertTrue(r.getCandidates().isEmpty());
		Resolution n = ModelResolver.resolve(null, INV);
		assertFalse(n.isResolved());
		assertEquals(PbModelTaskEnumType.UNKNOWN, n.getTask());
		Resolution nt = ModelResolver.resolve(Request.builder(null).override("juggernautXL_ragnarokBy").build(), INV);
		assertFalse(nt.isResolved());
	}

	@Test
	public void unavailableInventoryIsNotNoCandidate() {
		ModelInventory inv = ModelInventory.unavailable("http://sd.example:7801", "boom");
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.override("juggernautXL_ragnarokBy")
			.deploymentModel("juggernautXL_ragnarokBy")
			.build(), inv);
		assertFalse(r.isResolved());
		assertEquals(Reason.INVENTORY_UNAVAILABLE, r.getReason());
		assertEquals("boom", r.getInventoryError());
		assertEquals(inv.fetchedAt(), r.getInventoryFetchedAt());
		assertTrue("no candidate may be judged against a list that was never read", r.getCandidates().isEmpty());
		assertNull(r.getModel());
		assertTrue(r.getDegradationText().contains("boom"));
		ModelInventory blank = ModelInventory.unavailable(null, "  ");
		assertNotNull(blank.error());
		assertTrue(blank.installedModels().isEmpty());
	}

	@Test
	public void emptyInventoryThatWasReadIsARealAnswer() {
		ModelInventory inv = ModelInventory.of(null);
		assertNull(inv.error());
		assertTrue(inv.installedModels().isEmpty());
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT).override("x").build(), inv);
		assertEquals(Reason.NO_CANDIDATE, r.getReason());
		assertEquals(1, r.getCandidates().size());
		assertEquals(Reason.NOT_INSTALLED, r.getCandidates().get(0).getReason());
	}

	// ─────────────────────────────── family rules ───────────────────────────────

	@Test
	public void kontextOverrideRefusedFamilyDisabled() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.override("flux1-kontext-dev")
			.deploymentModel("juggernautXL_ragnarokBy")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(Step.DEPLOYMENT_DEFAULT, r.getStep());
		assertEquals(SDXL, r.getModel());
		assertTrue(r.isDegraded());
		Candidate k = r.getCandidates().get(0);
		assertEquals(Reason.FAMILY_DISABLED, k.getReason());
		assertEquals(KONTEXT, k.getListedName());
		assertEquals(RendererCapabilities.PATTERN_KONTEXT, k.getFamily());
		assertTrue(r.getDegradationText(), r.getDegradationText().contains("disabled"));
	}

	@Test
	public void kontextNeverChosenFromAnyStep() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT)
			.override("flux1-kontext-dev")
			.taskModels(taskModels(PbModelTaskEnumType.PORTRAIT, KONTEXT))
			.styleModel("Flux/flux1-kontext-dev")
			.deploymentModel("flux1-kontext-dev.safetensors")
			.build(), INV);
		assertFalse(r.isResolved());
		assertEquals(Reason.NO_CANDIDATE, r.getReason());
		assertEquals(4, r.getCandidates().size());
		for(Candidate c : r.getCandidates()) {
			assertEquals(c.toString(), Reason.FAMILY_DISABLED, c.getReason());
			assertEquals(KONTEXT, c.getListedName());
		}
	}

	@Test
	public void unknownFamilyRefused() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT).override("foo_v1").build(), INV);
		assertFalse(r.isResolved());
		Candidate c = r.getCandidates().get(0);
		assertEquals(Reason.FAMILY_UNKNOWN, c.getReason());
		assertEquals(UNKNOWN_FAMILY, c.getListedName());
		assertNull(c.getCapabilities());
		assertNull(c.getFamily());
		assertTrue(r.getDegradationText(), r.getDegradationText().contains("no known family"));
	}

	@Test
	public void sdxlRefusedForSceneDirectWithReferences() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.SCENE_DIRECT)
			.referencesRequested(true)
			.override("juggernautXL_ragnarokBy")
			.deploymentFlux2Model("flux2Klein_9b")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(FLUX2, r.getModel());
		assertEquals(Step.DEPLOYMENT_DEFAULT, r.getStep());
		assertEquals(Reason.FAMILY_UNSUPPORTED_FOR_TASK, r.getCandidates().get(0).getReason());
		assertTrue(r.getDegradationText(), r.getDegradationText().contains("does not support"));
	}

	@Test
	public void sdxlAllowedForSceneDirectWithoutReferences() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.SCENE_DIRECT)
			.referencesRequested(false)
			.override("juggernautXL_ragnarokBy")
			.build(), INV);
		assertTrue(r.isResolved());
		assertEquals(SDXL, r.getModel());
		assertFalse(r.isDegraded());
	}

	@Test
	public void flux2RefusedForInpaint() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.INPAINT).override("flux2Klein_9b").build(), INV);
		assertFalse(r.isResolved());
		assertEquals(Reason.FAMILY_UNSUPPORTED_FOR_TASK, r.getCandidates().get(0).getReason());
	}

	@Test
	public void sdxlAllowedForInpaint() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.INPAINT).override("juggernautXL_ragnarokBy").build(), INV);
		assertTrue(r.isResolved());
		assertEquals(SDXL, r.getModel());
	}

	@Test
	public void flux2AllowedForHarmonize() {
		Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.HARMONIZE).override("flux2Klein_9b").build(), INV);
		assertTrue(r.isResolved());
		assertEquals(FLUX2, r.getModel());
	}

	@Test
	public void supportsTaskTable() {
		RendererCapabilities sdxl = RendererCapabilities.forModel(SDXL);
		RendererCapabilities flux2 = RendererCapabilities.forModel(FLUX2);
		RendererCapabilities kontext = RendererCapabilities.forModel(KONTEXT);
		assertNotNull(sdxl);
		assertNotNull(flux2);
		assertNotNull(kontext);
		assertEquals(Reason.FAMILY_UNKNOWN, ModelResolver.supportsTask(null, PbModelTaskEnumType.PORTRAIT, false));
		assertEquals(Reason.FAMILY_DISABLED, ModelResolver.supportsTask(kontext, PbModelTaskEnumType.PORTRAIT, false));
		assertEquals(Reason.FAMILY_DISABLED, ModelResolver.supportsTask(kontext, PbModelTaskEnumType.SCENE_DIRECT, true));
		for(PbModelTaskEnumType t : new PbModelTaskEnumType[] {PbModelTaskEnumType.PORTRAIT, PbModelTaskEnumType.LOCATION, PbModelTaskEnumType.UPSCALE}) {
			assertEquals(t.name(), Reason.OK, ModelResolver.supportsTask(sdxl, t, false));
			assertEquals(t.name(), Reason.OK, ModelResolver.supportsTask(flux2, t, false));
		}
		assertEquals(Reason.OK, ModelResolver.supportsTask(flux2, PbModelTaskEnumType.SCENE_DIRECT, true));
		assertEquals(Reason.FAMILY_UNSUPPORTED_FOR_TASK, ModelResolver.supportsTask(sdxl, PbModelTaskEnumType.SCENE_DIRECT, true));
		assertEquals(Reason.OK, ModelResolver.supportsTask(sdxl, PbModelTaskEnumType.SCENE_DIRECT, false));
		assertEquals(Reason.OK, ModelResolver.supportsTask(sdxl, PbModelTaskEnumType.INPAINT, false));
		assertEquals(Reason.FAMILY_UNSUPPORTED_FOR_TASK, ModelResolver.supportsTask(flux2, PbModelTaskEnumType.INPAINT, false));
		assertEquals(Reason.OK, ModelResolver.supportsTask(sdxl, PbModelTaskEnumType.HARMONIZE, false));
		assertEquals(Reason.OK, ModelResolver.supportsTask(flux2, PbModelTaskEnumType.HARMONIZE, false));
		assertEquals(Reason.FAMILY_UNSUPPORTED_FOR_TASK, ModelResolver.supportsTask(sdxl, PbModelTaskEnumType.UNKNOWN, false));
		assertEquals(Reason.FAMILY_UNSUPPORTED_FOR_TASK, ModelResolver.supportsTask(sdxl, null, false));
	}

	// ─────────────────────────────── checkpoint names ───────────────────────────────

	@Test
	public void matchesFolderAndExtension() {
		assertEquals(SDXL, CheckpointNames.matchInstalled(INV, "juggernautXL_ragnarokBy"));
		assertEquals(SDXL, CheckpointNames.matchInstalled(INV, "juggernautXL_ragnarokBy.safetensors"));
		assertEquals(SDXL, CheckpointNames.matchInstalled(INV, "SDXL/juggernautXL_ragnarokBy"));
		assertEquals(SDXL, CheckpointNames.matchInstalled(INV, SDXL));
		assertEquals(SDXL, CheckpointNames.matchInstalled(INV, "  juggernautXL_ragnarokBy  "));
		assertNull(CheckpointNames.matchInstalled(INV, "juggernautXL"));
		assertNull(CheckpointNames.matchInstalled(INV, "other/juggernautXL_ragnarokBy"));
		assertNull(CheckpointNames.matchInstalled(INV, null));
		assertNull(CheckpointNames.matchInstalled(INV, " "));
		assertNull(CheckpointNames.matchInstalled(null, "juggernautXL_ragnarokBy"));
		assertNull(CheckpointNames.matchInstalled(Collections.emptyList(), "juggernautXL_ragnarokBy"));
	}

	@Test
	public void matchIsCaseInsensitive() {
		assertEquals(SDXL, CheckpointNames.matchInstalled(INV, "JUGGERNAUTXL_RAGNAROKBY"));
		assertEquals(SDXL, CheckpointNames.matchInstalled(INV, "sdxl/juggernautxl_ragnarokby.SAFETENSORS"));
		assertEquals(FLUX2, CheckpointNames.matchInstalled(INV, "FLUX2KLEIN_9B"));
	}

	@Test
	public void firstListedMatchWins() {
		List<String> dup = Arrays.asList("a/dup.safetensors", "b/dup.safetensors", "c/other.safetensors");
		assertEquals("a/dup.safetensors", CheckpointNames.matchInstalled(dup, "dup"));
		assertEquals("b/dup.safetensors", CheckpointNames.matchInstalled(dup, "b/dup"));
		assertEquals("b/dup.safetensors", CheckpointNames.matchInstalled(dup, "b/dup.safetensors"));
	}

	@Test
	public void stripExtensionAndBasename() {
		assertEquals("a/b", CheckpointNames.stripExtension("a/b.safetensors"));
		assertEquals("a/b", CheckpointNames.stripExtension("a/b.SafeTensors"));
		assertEquals("a/b.ckpt", CheckpointNames.stripExtension("a/b.ckpt"));
		assertEquals(".safetensors", CheckpointNames.stripExtension(".safetensors"));
		assertNull(CheckpointNames.stripExtension(null));
		assertEquals("b", CheckpointNames.basename("a/b"));
		assertEquals("b", CheckpointNames.basename("b"));
		assertEquals("a/b/", CheckpointNames.basename("a/b/"));
		assertNull(CheckpointNames.basename(null));
	}

	// ─────────────────────────────── strategy / task keys ───────────────────────────────

	@Test
	public void strategyForModeTable() {
		assertEquals(PbStrategyEnumType.DIRECT_REFERENCE, ModelResolver.strategyForMode("flux2"));
		assertEquals(PbStrategyEnumType.DIRECT_REFERENCE, ModelResolver.strategyForMode(" Flux2 "));
		assertEquals(PbStrategyEnumType.CLASSIC_COMPOSITE, ModelResolver.strategyForMode("classic"));
		assertEquals(PbStrategyEnumType.UNKNOWN, ModelResolver.strategyForMode("kontext"));
		assertEquals(PbStrategyEnumType.UNKNOWN, ModelResolver.strategyForMode(null));
		assertEquals(PbStrategyEnumType.UNKNOWN, ModelResolver.strategyForMode(""));
		assertEquals(PbStrategyEnumType.UNKNOWN, ModelResolver.strategyForMode("sequential"));
	}

	@Test
	public void modeLiteralsMirrorSceneCompositeUtil() {
		assertEquals(SceneCompositeUtil.MODE_FLUX2, ModelResolver.MODE_FLUX2);
		assertEquals(SceneCompositeUtil.MODE_KONTEXT, ModelResolver.MODE_KONTEXT);
		assertEquals(SceneCompositeUtil.MODE_CLASSIC, ModelResolver.MODE_CLASSIC);
	}

	@Test
	public void taskKeyRoundTripAllSix() {
		int n = 0;
		for(PbModelTaskEnumType t : PbModelTaskEnumType.values()) {
			if(t == PbModelTaskEnumType.UNKNOWN) {
				continue;
			}
			String k = ModelTaskKeys.key(t);
			assertNotNull(t.name(), k);
			assertEquals(t.name(), t, ModelTaskKeys.task(k));
			assertTrue(k, Character.isLowerCase(k.charAt(0)));
			assertFalse(k, k.contains("_"));
			n++;
		}
		assertEquals(6, n);
		assertEquals("sceneDirect", ModelTaskKeys.key(PbModelTaskEnumType.SCENE_DIRECT));
		assertEquals("portrait", ModelTaskKeys.key(PbModelTaskEnumType.PORTRAIT));
		assertNull(ModelTaskKeys.key(PbModelTaskEnumType.UNKNOWN));
		assertNull(ModelTaskKeys.key(null));
	}

	@Test
	public void taskAcceptsEnumNameCaseInsensitively() {
		assertEquals(PbModelTaskEnumType.SCENE_DIRECT, ModelTaskKeys.task("SCENE_DIRECT"));
		assertEquals(PbModelTaskEnumType.SCENE_DIRECT, ModelTaskKeys.task("scene_direct"));
		assertEquals(PbModelTaskEnumType.SCENE_DIRECT, ModelTaskKeys.task("SceneDirect"));
		assertEquals(PbModelTaskEnumType.PORTRAIT, ModelTaskKeys.task("Portrait"));
		assertEquals(PbModelTaskEnumType.PORTRAIT, ModelTaskKeys.task(" portrait "));
	}

	@Test
	public void unknownKeyIsUnknown() {
		assertEquals(PbModelTaskEnumType.UNKNOWN, ModelTaskKeys.task("bogus"));
		assertEquals(PbModelTaskEnumType.UNKNOWN, ModelTaskKeys.task("unknown"));
		assertEquals(PbModelTaskEnumType.UNKNOWN, ModelTaskKeys.task(null));
		assertEquals(PbModelTaskEnumType.UNKNOWN, ModelTaskKeys.task("  "));
	}

	@Test
	public void parseTaskModelsDropsNullStringAndBlank() {
		Map<PbModelTaskEnumType, String> m = ModelTaskKeys.parseTaskModels(
			"{\"portrait\":\"null\",\"location\":\" \",\"sceneDirect\":\"flux2Klein_9b\",\"upscale\":\"n/a\"}");
		assertEquals(m.toString(), 1, m.size());
		assertEquals("flux2Klein_9b", m.get(PbModelTaskEnumType.SCENE_DIRECT));
	}

	@Test
	public void parseTaskModelsMalformedIsEmpty() {
		assertTrue(ModelTaskKeys.parseTaskModels("not json").isEmpty());
		assertTrue(ModelTaskKeys.parseTaskModels("{\"portrait\":").isEmpty());
		assertTrue(ModelTaskKeys.parseTaskModels("[\"portrait\"]").isEmpty());
		assertTrue(ModelTaskKeys.parseTaskModels("{}").isEmpty());
	}

	@Test
	public void parseTaskModelsUnknownKeyIgnored() {
		Map<PbModelTaskEnumType, String> m = ModelTaskKeys.parseTaskModels("{\"bogus\":\"x\",\"portrait\":\"juggernautXL_ragnarokBy\"}");
		assertEquals(1, m.size());
		assertEquals("juggernautXL_ragnarokBy", m.get(PbModelTaskEnumType.PORTRAIT));
	}

	@Test
	public void parseTaskModelsNullIsEmpty() {
		assertTrue(ModelTaskKeys.parseTaskModels(null).isEmpty());
		assertTrue(ModelTaskKeys.parseTaskModels("").isEmpty());
		assertTrue(ModelTaskKeys.parseTaskModels("   ").isEmpty());
	}

	@Test
	public void parseTaskModelsAcceptsEnumSpellingAndNonStringsAreDropped() {
		Map<PbModelTaskEnumType, String> m = ModelTaskKeys.parseTaskModels("{\"SCENE_DIRECT\":\" flux2Klein_9b \",\"inpaint\":42,\"harmonize\":null}");
		assertEquals(1, m.size());
		assertEquals("flux2Klein_9b", m.get(PbModelTaskEnumType.SCENE_DIRECT));
	}

	@Test
	public void parsedTaskModelsFeedTheResolver() {
		Map<PbModelTaskEnumType, String> m = ModelTaskKeys.parseTaskModels("{\"sceneDirect\":\"flux2Klein_9b\",\"portrait\":\"juggernautXL_ragnarokBy\"}");
		Resolution scene = ModelResolver.resolve(req(PbModelTaskEnumType.SCENE_DIRECT).taskModels(m).build(), INV);
		Resolution portrait = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT).taskModels(m).build(), INV);
		assertEquals(FLUX2, scene.getModel());
		assertEquals(SDXL, portrait.getModel());
		assertSame(Step.STYLE_TASK, scene.getStep());
		assertSame(Step.STYLE_TASK, portrait.getStep());
	}

	// ─────────────────────────────── SwarmModelInventory.forServer guards (no server contacted) ───────────────────────────────

	/**
	 * Gap (e): a non-SWARM API type or a blank server is an unavailable inventory with a reason, never a
	 * throw and never a network call (nothing here listens on 127.0.0.1:1, so a contact attempt would
	 * surface as a different error string). Resolving over it is INVENTORY_UNAVAILABLE, not NO_CANDIDATE.
	 * The reason names the supported API only - neither the configured type nor the server URL rides on
	 * the error text, which the facade puts on the wire for user-role callers (security review).
	 */
	@Test
	public void forServerNonSwarmOrBlankServerIsUnavailableNotThrown() {
		String server = "http://127.0.0.1:1";
		for(org.cote.accountmanager.olio.sd.SDAPIEnumType t : new org.cote.accountmanager.olio.sd.SDAPIEnumType[] {
			org.cote.accountmanager.olio.sd.SDAPIEnumType.AUTO1111,
			org.cote.accountmanager.olio.sd.SDAPIEnumType.COMFY,
			org.cote.accountmanager.olio.sd.SDAPIEnumType.UNKNOWN,
			null
		}) {
			ModelInventory inv = SwarmModelInventory.forServer(t, server);
			assertNotNull("forServer(" + t + ") must return an inventory, not throw", inv);
			assertNotNull("forServer(" + t + ") must carry an error", inv.error());
			assertTrue("forServer(" + t + ") error must name the supported API: " + inv.error(),
				inv.error().contains("only available for SWARM"));
			assertFalse("forServer(" + t + ") error must not echo the configured type: " + inv.error(),
				inv.error().contains(String.valueOf(t)));
			assertFalse("forServer(" + t + ") error must not embed the server URL: " + inv.error(),
				inv.error().contains(server));
			assertTrue("forServer(" + t + ") lists nothing", inv.installedModels().isEmpty());
			assertEquals(server, inv.server());
			Resolution r = ModelResolver.resolve(req(PbModelTaskEnumType.PORTRAIT).deploymentModel("juggernautXL_ragnarokBy").build(), inv);
			assertSame(Reason.INVENTORY_UNAVAILABLE, r.getReason());
			assertFalse(r.isResolved());
			assertEquals(inv.error(), r.getInventoryError());
			assertTrue("no candidate is ever evaluated against an unavailable list", r.getCandidates().isEmpty());
		}
		for(String blank : new String[] {null, "", "   "}) {
			ModelInventory inv = SwarmModelInventory.forServer(org.cote.accountmanager.olio.sd.SDAPIEnumType.SWARM, blank);
			assertEquals("SD server is not configured", inv.error());
			assertTrue(inv.installedModels().isEmpty());
		}
	}

	@Test
	public void requestAndResolutionAreImmutable() {
		Map<PbModelTaskEnumType, String> m = taskModels(PbModelTaskEnumType.PORTRAIT, "juggernautXL_ragnarokBy");
		Request r = req(PbModelTaskEnumType.PORTRAIT).taskModels(m).build();
		m.put(PbModelTaskEnumType.LOCATION, "x");
		assertEquals("the request copies the map at build time", 1, r.getTaskModels().size());
		try {
			r.getTaskModels().put(PbModelTaskEnumType.UPSCALE, "y");
			assertTrue("taskModels must be unmodifiable", false);
		}
		catch(UnsupportedOperationException expected) {
			// ok
		}
		Resolution res = ModelResolver.resolve(r, INV);
		try {
			res.getCandidates().clear();
			assertTrue("candidates must be unmodifiable", false);
		}
		catch(UnsupportedOperationException expected) {
			// ok
		}
	}
}
