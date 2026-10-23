package org.cote.accountmanager.olio.picturebook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;

import org.cote.accountmanager.olio.illustration.CompiledPrompt;
import org.cote.accountmanager.olio.illustration.RenderPlan;
import org.cote.accountmanager.olio.illustration.SdxlCompiler;
import org.cote.accountmanager.schema.type.PbModelTaskEnumType;
import org.junit.Test;

/**
 * Pure tests for {@link PbNodeExecutor#hasSubject(RenderPlan)} (P4-10): the empty-scene 409 is judged on what
 * the plan depicts, not on the compiled BASE string, because the compiler folds the camera, palette, style and
 * photoreal boilerplate into that string. A MIGRATED composition carries a default shot type and viewpoint, so
 * a text-less scene compiled to "medium, eye level" and rendered (found by
 * {@code TestPbRenderPlanExecutor.sceneWithoutTextIsA409BeforeRendering}). No DB, no SD.
 */
public class TestPbRenderPlanSubject {

	private static RenderPlan.Builder decorated() {
		return RenderPlan.builder()
			.purpose(RenderPlan.PURPOSE_SCENE)
			.canvas(new RenderPlan.Canvas(1024, 1024))
			.preset(new RenderPlan.PresetValues("DRAFT", 12, 1, false, "NONE"))
			.style(new RenderPlan.Style("6f1c", "9ab4", "ink wash, muted earth tones", "text, watermark", "dusk, amber rim light", true, Collections.emptyList()))
			.camera(new RenderPlan.Camera("medium, eye level"))
			.intent(new RenderPlan.Intent(null, false))
			.model(new RenderPlan.ModelPick(PbModelTaskEnumType.LOCATION, "OfficialStableDiffusion/sd_xl_base_1.0.safetensors", "*xl*", "DEPLOYMENT_DEFAULT", "OK", false, null));
	}

	@Test
	public void cameraStylePaletteAndPhotorealAloneAreNotASubject() {
		RenderPlan plan = decorated().location(new RenderPlan.Location(null, "   ")).build();
		assertFalse("modifiers only", PbNodeExecutor.hasSubject(plan));
		CompiledPrompt compiled = new SdxlCompiler().compile(plan, RenderPlan.STAGE_BASE, new ArrayList<>());
		assertTrue("the compiled string is NOT blank for a subject-less plan - which is why the gate cannot use it: "
			+ compiled.getPrompt(), compiled.getPrompt() != null && !compiled.getPrompt().isBlank());
		assertFalse(PbNodeExecutor.hasSubject(decorated().location(new RenderPlan.Location(null, "null")).build()));
		assertFalse(PbNodeExecutor.hasSubject(decorated().build()));
	}

	@Test
	public void locationTextIsASubject() {
		assertTrue(PbNodeExecutor.hasSubject(decorated().location(new RenderPlan.Location(null, "a flooded cathedral nave at dusk")).build()));
	}

	@Test
	public void intentIsASubject() {
		assertTrue(PbNodeExecutor.hasSubject(decorated().intent(new RenderPlan.Intent("the harbour master's office at dawn", false)).build()));
		assertFalse("a literal-null override is not", PbNodeExecutor.hasSubject(decorated().intent(new RenderPlan.Intent("n/a", false)).build()));
	}

	@Test
	public void anActorOrElementWithTextIsASubject() {
		assertTrue(PbNodeExecutor.hasSubject(decorated().actor(new RenderPlan.Actor("c01", "Jideon", RenderPlan.ROLE_FOREGROUND, null, false)).build()));
		assertTrue(PbNodeExecutor.hasSubject(decorated().actor(new RenderPlan.Actor("c02", null, RenderPlan.ROLE_BACKGROUND, "a hooded figure", false)).build()));
		assertTrue(PbNodeExecutor.hasSubject(decorated().element(new RenderPlan.Element("lantern", RenderPlan.PRIORITY_HIGH, "a brass lantern on a chain")).build()));
		assertTrue(PbNodeExecutor.hasSubject(decorated().element(new RenderPlan.Element(null, RenderPlan.PRIORITY_LOW, "gulls")).build()));
		assertFalse("rows with neither name nor description are not",
			PbNodeExecutor.hasSubject(decorated().actor(new RenderPlan.Actor("c03", " ", RenderPlan.ROLE_BACKGROUND, "null", false))
				.element(new RenderPlan.Element("", RenderPlan.PRIORITY_MEDIUM, null)).build()));
	}
}
