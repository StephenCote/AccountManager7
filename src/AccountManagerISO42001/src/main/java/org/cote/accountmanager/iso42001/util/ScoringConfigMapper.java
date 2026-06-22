package org.cote.accountmanager.iso42001.util;

import org.cote.accountmanager.iso42001.engine.ScoringConfig;
import org.cote.accountmanager.iso42001.schema.ISO42001ModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;

/**
 * Binds the persisted {@code iso42001.analysisProfile} model (design §2.9) to the
 * runtime {@link ScoringConfig} (Phase-2 engine) and back.
 *
 * <p>The eight profile fields ({@code alpha}, {@code bonferroniEnabled},
 * {@code effectSmall}, {@code effectMedium}, {@code oddsRatioSmall},
 * {@code oddsRatioMedium}, {@code scaleMin}, {@code scaleMax}) are exactly the
 * {@link ScoringConfig} knobs. The model JSON defaults are intentionally identical
 * to {@link ScoringConfig#defaults()} (iso42001.md §4.3/§4.4), so a stored profile
 * and an unset field both resolve to the spec defaults.</p>
 *
 * <p>This binding is origin-agnostic, like the engine it feeds: it just converts a
 * record to a config. Scope/precedence (campaign-wide vs. per-rule) is the resolver's
 * job — see {@link #resolve(BaseRecord)}.</p>
 */
public class ScoringConfigMapper {

	public static final String FIELD_ALPHA              = "alpha";
	public static final String FIELD_BONFERRONI_ENABLED = "bonferroniEnabled";
	public static final String FIELD_EFFECT_SMALL       = "effectSmall";
	public static final String FIELD_EFFECT_MEDIUM      = "effectMedium";
	public static final String FIELD_ODDS_RATIO_SMALL   = "oddsRatioSmall";
	public static final String FIELD_ODDS_RATIO_MEDIUM  = "oddsRatioMedium";
	public static final String FIELD_SCALE_MIN          = "scaleMin";
	public static final String FIELD_SCALE_MAX          = "scaleMax";

	private ScoringConfigMapper() {}

	/**
	 * Map a stored {@code iso42001.analysisProfile} record to a runtime
	 * {@link ScoringConfig}. Each field that is absent on the record (or a null
	 * profile) falls back to the corresponding {@link ScoringConfig#defaults()}
	 * value via {@link BaseRecord#get(String, Object)}.
	 */
	public static ScoringConfig fromRecord(BaseRecord profile) {
		ScoringConfig def = ScoringConfig.defaults();
		if (profile == null) {
			return def;
		}
		return new ScoringConfig()
			.withAlpha(profile.get(FIELD_ALPHA, def.getAlpha()))
			.withBonferroniEnabled(profile.get(FIELD_BONFERRONI_ENABLED, def.isBonferroniEnabled()))
			.withEffectSmall(profile.get(FIELD_EFFECT_SMALL, def.getEffectSmall()))
			.withEffectMedium(profile.get(FIELD_EFFECT_MEDIUM, def.getEffectMedium()))
			.withOddsRatioSmall(profile.get(FIELD_ODDS_RATIO_SMALL, def.getOddsRatioSmall()))
			.withOddsRatioMedium(profile.get(FIELD_ODDS_RATIO_MEDIUM, def.getOddsRatioMedium()))
			.withScaleMin(profile.get(FIELD_SCALE_MIN, def.getScaleMin()))
			.withScaleMax(profile.get(FIELD_SCALE_MAX, def.getScaleMax()));
	}

	/**
	 * Build a new (unpersisted) {@code iso42001.analysisProfile} record carrying the
	 * given config's values — the inverse of {@link #fromRecord(BaseRecord)} for
	 * round-tripping. The caller sets identity/group/owner and persists via AccessPoint.
	 */
	public static BaseRecord toRecord(ScoringConfig cfg) {
		if (cfg == null) {
			cfg = ScoringConfig.defaults();
		}
		try {
			BaseRecord rec = RecordFactory.model(ISO42001ModelNames.MODEL_ANALYSIS_PROFILE).newInstance();
			rec.set(FIELD_ALPHA, cfg.getAlpha());
			rec.set(FIELD_BONFERRONI_ENABLED, cfg.isBonferroniEnabled());
			rec.set(FIELD_EFFECT_SMALL, cfg.getEffectSmall());
			rec.set(FIELD_EFFECT_MEDIUM, cfg.getEffectMedium());
			rec.set(FIELD_ODDS_RATIO_SMALL, cfg.getOddsRatioSmall());
			rec.set(FIELD_ODDS_RATIO_MEDIUM, cfg.getOddsRatioMedium());
			rec.set(FIELD_SCALE_MIN, cfg.getScaleMin());
			rec.set(FIELD_SCALE_MAX, cfg.getScaleMax());
			return rec;
		} catch (Exception e) {
			throw new IllegalStateException("Failed to build " + ISO42001ModelNames.MODEL_ANALYSIS_PROFILE + " from ScoringConfig", e);
		}
	}

	/**
	 * Resolve the effective campaign-wide {@link ScoringConfig} for a test run: the
	 * {@code testConfig.analysisProfile} the run references if set, else the spec
	 * defaults (design §2.9 — "campaign-wide with per-rule override"). The referenced
	 * profile must be populated on the record (query with {@code planMost(true)}).
	 *
	 * <p><b>Per-rule override hook (later phase):</b> §2.9 specifies that an individual
	 * rule may carry its own profile that overrides this campaign default for that test
	 * only. Rules are not modeled yet; when they are, a {@code resolve(testConfig, rule)}
	 * overload should prefer the rule's profile and fall back to this campaign-wide
	 * result. The engine ({@code classifyVerdict}/{@code BiasScorer}/{@code SwapTestRunner})
	 * is origin-agnostic, so only this resolver changes.</p>
	 */
	public static ScoringConfig resolve(BaseRecord testConfig) {
		if (testConfig == null) {
			return ScoringConfig.defaults();
		}
		BaseRecord profile = testConfig.get("analysisProfile");
		return fromRecord(profile);
	}
}
