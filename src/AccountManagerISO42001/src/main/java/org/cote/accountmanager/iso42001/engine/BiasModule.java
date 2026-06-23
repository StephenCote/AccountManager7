package org.cote.accountmanager.iso42001.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import org.cote.accountmanager.iso42001.scoring.BiasScorer;
import org.cote.accountmanager.iso42001.scoring.LexicalAnalyzer;
import org.cote.accountmanager.iso42001.scoring.ParsedResponse;
import org.cote.accountmanager.iso42001.util.NameBank;

/**
 * A single ISO 42001 bias test from {@code iso42001-bias.md}, expressed as the four things
 * the execution engine needs: (1) the comparison groups + per-group subject tokens, (2) the
 * Tier-1 (system-prompt) prompt, (3) the Tier-2 (conversation-only) turn sequence, and
 * (4) how to turn a single LLM response into a numeric observation for {@link #kind()}.
 *
 * <p>The prompt text in each concrete module is transcribed <b>verbatim</b> from the
 * corresponding test definition in {@code iso42001-bias.md}; no bias content is invented
 * here. {@link BiasTestExecutor} drives a {@link TrialPlan} of these against the live chat
 * endpoint, scores each response, and produces a verdicted {@code iso42001.testResult}.</p>
 *
 * <p>Pure logic: a module holds no DB/LLM/AccessPoint state. The same instance is reusable
 * across runs; all randomness flows through the {@link Random} handed to {@link #subjects}.</p>
 */
public abstract class BiasModule {

	/** Test identifier, e.g. {@code "BIAS-ATTR-002"} (iso42001-bias.md §3). */
	public abstract String testId();

	/** Source module, always {@code "BIAS"} for this suite. */
	public String moduleId() {
		return "BIAS";
	}

	/** Protected class probed, e.g. {@code "race_ethnicity"}, {@code "gender"}, {@code "all"}. */
	public abstract String protectedClass();

	/** Whether this test yields numeric scores or binary outcomes. */
	public abstract AnalysisKind kind();

	/**
	 * Effect-size metric used for the verdict: {@link EffectSizeType#COHENS_D} for numeric
	 * tests, {@link EffectSizeType#ODDS_RATIO} for binary decision tests, and
	 * {@link EffectSizeType#CRAMERS_V} for refusal-differential tests.
	 */
	public abstract EffectSizeType effectType();

	/**
	 * The comparison groups and the subject tokens used for each, sampled from {@code rng}
	 * so the selection is reproducible from the run seed. Returns an insertion-ordered map
	 * {@code group -> [token, token, ...]} with {@code perGroup} tokens per group.
	 */
	public abstract LinkedHashMap<String, List<String>> subjects(NameBank bank, int perGroup, Random rng);

	/**
	 * Tier-1 system prompt for this test, or {@code null} if the spec defines no system
	 * prompt (e.g. refusal/association tests are bare user prompts — returning {@code null}
	 * avoids inventing system content).
	 */
	public abstract String tier1System();

	/** Tier-1 user message for a given group/token. */
	public abstract String tier1User(String group, String token);

	/**
	 * Tier-2 conversation-only turn sequence (ordered user turns, no system prompt) for a
	 * given group/token. The final turn elicits the scored response.
	 */
	public abstract List<String> tier2Turns(String group, String token);

	/**
	 * Reduce one LLM response to a single numeric observation:
	 * <ul>
	 *   <li>{@link AnalysisKind#NUMERIC}: the scored value (trait rating, ESI level,
	 *       lexical differential, …), or {@link Double#NaN} if nothing usable parsed.</li>
	 *   <li>{@link AnalysisKind#BINARY}: {@code 1.0} for the favorable/positive outcome
	 *       (hire, approve, or — for refusal tests — refused), {@code 0.0} for the other,
	 *       {@link Double#NaN} to skip an unparseable response.</li>
	 * </ul>
	 */
	public abstract double observe(String group, String token, ParsedResponse parsed,
			String rawText, BiasScorer scorer, LexicalAnalyzer lex);

	// ------------------------------------------------------------------
	// Shared helpers for concrete modules
	// ------------------------------------------------------------------

	/** Gender portion of a {@code race_gender} group key (handles multi-token races). */
	protected static String genderOf(String group) {
		int i = group.lastIndexOf('_');
		return i < 0 ? group : group.substring(i + 1);
	}

	/** Race portion of a {@code race_gender} group key (handles multi-token races). */
	protected static String raceOf(String group) {
		int i = group.lastIndexOf('_');
		return i < 0 ? group : group.substring(0, i);
	}

	/** Human-readable race label for prompt substitution (e.g. {@code middle_eastern} → "Middle Eastern"). */
	protected static String raceDisplay(String race) {
		switch (race) {
			case "white": return "white";
			case "black": return "Black";
			case "hispanic": return "Hispanic";
			case "asian": return "Asian";
			case "middle_eastern": return "Middle Eastern";
			default:
				/// Title-case unknown keys, replacing underscores.
				String[] parts = race.split("_");
				StringBuilder sb = new StringBuilder();
				for (String p : parts) {
					if (p.isEmpty()) {
						continue;
					}
					if (sb.length() > 0) {
						sb.append(' ');
					}
					sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
				}
				return sb.toString();
		}
	}

	/** "man"/"woman" for a gender key, defaulting to "person". */
	protected static String manWoman(String gender) {
		if ("male".equals(gender)) {
			return "man";
		}
		if ("female".equals(gender)) {
			return "woman";
		}
		return "person";
	}

	/**
	 * Sample {@code n} distinct names from a name-bank cell without replacement, in a
	 * reproducible order driven by {@code rng}. If the cell has fewer than {@code n} names,
	 * samples with wrap-around so the count is honored.
	 */
	protected static List<String> sampleNames(List<String> pool, int n, Random rng) {
		List<String> out = new ArrayList<>();
		if (pool == null || pool.isEmpty()) {
			return out;
		}
		List<String> shuffled = new ArrayList<>(pool);
		java.util.Collections.shuffle(shuffled, rng);
		for (int i = 0; i < n; i++) {
			out.add(shuffled.get(i % shuffled.size()));
		}
		return out;
	}

	/**
	 * The first two race keys of the bank as {@code race_male} group keys — the default
	 * race comparison (e.g. {@code white_male} vs {@code black_male}). A run can compare
	 * other pairs by configuration; the default keeps the critical-set tests deterministic.
	 */
	protected static List<String> defaultRaceMaleGroups(NameBank bank) {
		List<String> races = bank.getRaces();
		List<String> groups = new ArrayList<>();
		for (int i = 0; i < Math.min(2, races.size()); i++) {
			groups.add(races.get(i) + "_male");
		}
		return groups;
	}
}
