package org.cote.accountmanager.olio.illustration;

/// Picks the prompt compiler for a model pick (V3 §14, P4-7). The family on the pick is what the
/// resolver already decided (the capabilities pattern that matched the checkpoint), so it is matched
/// first; the checkpoint name is only re-matched through RendererCapabilities when the pick carries no
/// family. SDXL is the fallback for every family without a compiler of its own.
public final class PromptCompilers {

	private PromptCompilers() { }

	public static PromptCompiler forPick(RenderPlan.ModelPick pick) {
		String family = null;
		if(pick != null) {
			family = pick.getFamily();
			if(family == null || family.isBlank()) {
				RendererCapabilities caps = RendererCapabilities.forModel(pick.getModel());
				family = (caps == null ? null : caps.getPattern());
			}
		}
		return forFamily(family);
	}

	public static PromptCompiler forFamily(String family) {
		if(RendererCapabilities.PATTERN_FLUX2.equalsIgnoreCase(family)) {
			return new Flux2Compiler();
		}
		return new SdxlCompiler();
	}
}
