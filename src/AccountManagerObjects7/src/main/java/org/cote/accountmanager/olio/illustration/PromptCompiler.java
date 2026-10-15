package org.cote.accountmanager.olio.illustration;

import java.util.List;

/// A per-family prompt compiler (PictureBook V3 design §14): turns the plan into the exact prompt
/// pair a stage sends. Pure and non-throwing on a valid plan - a prompt that does not fit is
/// truncated and recorded as a PROMPT_TRUNCATED degradation in {@code out}, never an exception.
public interface PromptCompiler {

	/// The family glob this compiler targets (see RendererCapabilities.PATTERN_*).
	String family();

	/// Compile one stage. Degradations the compilation produced are appended to {@code out}
	/// (which may be null when the caller does not want them).
	CompiledPrompt compile(RenderPlan plan, String stage, List<RenderPlan.Degradation> out);
}
