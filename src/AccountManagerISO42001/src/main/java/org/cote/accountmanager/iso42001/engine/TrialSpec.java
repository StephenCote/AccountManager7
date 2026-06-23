package org.cote.accountmanager.iso42001.engine;

import java.util.Objects;

/**
 * One planned bias-test trial: a single LLM call to make, fully determined before any
 * network activity. A list of these (a {@link TrialPlan}) is generated deterministically
 * from the run's random seed, so two runs with the same seed produce the identical,
 * interleaved trial order and demographic/name selection (iso42001-bias.md §5.2/§5.5).
 *
 * <p>Reproducibility is asserted on the plan (these specs), never on the nondeterministic
 * LLM text they elicit.</p>
 *
 * @param trialId sequential id within the plan (0-based, in interleaved execution order)
 * @param group   demographic group key being probed (e.g. {@code "white_male"}, or a
 *                refusal-target label like {@code "asian"})
 * @param token   the value substituted into the module's prompt for this trial — a name
 *                drawn from the name bank, or (for refusal/association tests) a fixed
 *                demographic/stereotype token the module knows how to expand
 * @param tier    1 = system-prompt tier, 2 = conversation-only tier
 */
public final class TrialSpec {

	private final int trialId;
	private final String group;
	private final String token;
	private final int tier;

	public TrialSpec(int trialId, String group, String token, int tier) {
		this.trialId = trialId;
		this.group = group;
		this.token = token;
		this.tier = tier;
	}

	public int getTrialId() { return trialId; }
	public String getGroup() { return group; }
	public String getToken() { return token; }
	public int getTier() { return tier; }

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof TrialSpec)) {
			return false;
		}
		TrialSpec t = (TrialSpec) o;
		return trialId == t.trialId && tier == t.tier
			&& Objects.equals(group, t.group) && Objects.equals(token, t.token);
	}

	@Override
	public int hashCode() {
		return Objects.hash(trialId, group, token, tier);
	}

	@Override
	public String toString() {
		return "#" + trialId + "[t" + tier + " " + group + " '" + token + "']";
	}
}
