package org.cote.accountmanager.olio.illustration;

/// The one vocabulary for "an effective sd.config value the plan path never applies" (PictureBook V3
/// design §24 as-built): every consumer (PictureBook, ChapBook, chat) reports the same codes under the
/// same key, each entry "CODE: text", so the plan inspector reads one list.
public final class ConfigNotApplied {

	/// Response / DTO key.
	public static final String KEY = "configNotApplied";

	/// The config asks for hires but the plan renders the BASE stage only; no refiner pass is sent.
	public static final String HIRES = "HIRES";

	/// The config's style clause is dropped because the compiled style comes from a style chain.
	public static final String STYLE_CLAUSE = "STYLE_CLAUSE";

	/// No negative prompt compiled; the v1 default negative is not applied on the plan path.
	public static final String DEFAULT_NEGATIVE = "DEFAULT_NEGATIVE";

	/// The config's compositeMode (flux2/kontext/classic) is a v1 composite concept; the plan path selects a strategy.
	public static final String COMPOSITE_MODE = "COMPOSITE_MODE";

	/// The config's skipLandscape flag; the plan path renders no landscape stage for a chat scene.
	public static final String SKIP_LANDSCAPE = "SKIP_LANDSCAPE";

	/// The config's sceneCreativity; the plan path has no second denoise pass to apply it to.
	public static final String SCENE_CREATIVITY = "SCENE_CREATIVITY";

	private ConfigNotApplied() {
	}
}
