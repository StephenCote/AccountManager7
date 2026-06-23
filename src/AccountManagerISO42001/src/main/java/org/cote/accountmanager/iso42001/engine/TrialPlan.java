package org.cote.accountmanager.iso42001.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import org.cote.accountmanager.iso42001.util.NameBank;

/**
 * A deterministic, seeded, interleaved schedule of {@link TrialSpec}s for one bias-test
 * execution (iso42001-bias.md §5.2 "prompts are generated deterministically from a seed",
 * §5.5 "interleave: run one trial per group in round-robin, shuffled order").
 *
 * <p>Built by {@link #build(BiasModule, NameBank, int, int, long)} from a single
 * {@link Random} seeded with the run's {@code randomSeed}: the module first samples its
 * per-group subjects (names / tokens) from that RNG, then the same RNG drives the
 * round-by-round group shuffling. Both the selection and the interleaving are therefore
 * a pure function of the seed — re-running with the same seed yields an {@link #equals}
 * plan. This is the property the live tests assert (LLM text is nondeterministic; the
 * plan is not).</p>
 */
public final class TrialPlan {

	private final long seed;
	private final String testId;
	private final int tier;
	private final List<String> groups;
	private final int samplesPerGroup;
	private final List<TrialSpec> trials;

	private TrialPlan(long seed, String testId, int tier, List<String> groups,
			int samplesPerGroup, List<TrialSpec> trials) {
		this.seed = seed;
		this.testId = testId;
		this.tier = tier;
		this.groups = Collections.unmodifiableList(new ArrayList<>(groups));
		this.samplesPerGroup = samplesPerGroup;
		this.trials = Collections.unmodifiableList(new ArrayList<>(trials));
	}

	/**
	 * Build the plan. Deterministic for fixed ({@code module} subjects, {@code bank},
	 * {@code perGroup}, {@code tier}, {@code seed}).
	 *
	 * @param module   supplies the comparison groups and the per-group subject tokens
	 * @param bank     the name bank the module samples from
	 * @param perGroup number of trials (samples) per group
	 * @param tier     1 or 2
	 * @param seed     the run's random seed
	 */
	public static TrialPlan build(BiasModule module, NameBank bank, int perGroup, int tier, long seed) {
		Random rng = new Random(seed);
		/// 1) Module samples its subjects from the (seeded) RNG — fully reproducible.
		LinkedHashMap<String, List<String>> subjects = module.subjects(bank, perGroup, rng);
		List<String> groups = new ArrayList<>(subjects.keySet());

		/// 2) Interleave round-robin: for each round, visit the groups in a freshly
		///    shuffled order (driven by the SAME RNG) and emit one trial per group.
		List<TrialSpec> trials = new ArrayList<>();
		int trialId = 0;
		for (int round = 0; round < perGroup; round++) {
			List<String> order = new ArrayList<>(groups);
			Collections.shuffle(order, rng);
			for (String g : order) {
				List<String> tokens = subjects.get(g);
				if (tokens == null || round >= tokens.size()) {
					continue;
				}
				trials.add(new TrialSpec(trialId++, g, tokens.get(round), tier));
			}
		}
		return new TrialPlan(seed, module.testId(), tier, groups, perGroup, trials);
	}

	public long getSeed() { return seed; }
	public String getTestId() { return testId; }
	public int getTier() { return tier; }
	public List<String> getGroups() { return groups; }
	public int getSamplesPerGroup() { return samplesPerGroup; }
	public List<TrialSpec> getTrials() { return trials; }
	public int size() { return trials.size(); }

	/**
	 * A compact, comparable signature of the plan (order-sensitive). Two plans with the
	 * same seed/test/tier produce identical signatures — convenient for a single
	 * assertion in a reproducibility test.
	 */
	public String signature() {
		StringBuilder sb = new StringBuilder();
		sb.append(testId).append('|').append(tier).append('|').append(seed).append('|');
		for (TrialSpec t : trials) {
			sb.append(t.getGroup()).append(':').append(t.getToken()).append(';');
		}
		return sb.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof TrialPlan)) {
			return false;
		}
		TrialPlan p = (TrialPlan) o;
		return seed == p.seed && tier == p.tier
			&& Objects.equals(testId, p.testId)
			&& Objects.equals(groups, p.groups)
			&& Objects.equals(trials, p.trials);
	}

	@Override
	public int hashCode() {
		return Objects.hash(seed, testId, tier, groups, trials);
	}

	@Override
	public String toString() {
		return "TrialPlan{test=" + testId + ", tier=" + tier + ", seed=" + seed
			+ ", groups=" + groups + ", trials=" + trials.size() + "}";
	}
}
