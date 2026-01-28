package org.cote.accountmanager.olio.actions;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public abstract class CommonAction implements IAction {

	public static final Logger logger = LogManager.getLogger(CommonAction.class);

	@Override
	public long timeRemaining(BaseRecord actionResult, ChronoUnit unit) {
		ZonedDateTime ep = actionResult.get(OlioFieldNames.FIELD_ACTION_PROGRESS);
		ZonedDateTime ee = actionResult.get(OlioFieldNames.FIELD_ACTION_END);
		return ep.until(ee, unit);
	}
	
	protected void edgeEnd(OlioContext ctx, BaseRecord actionResult, int iter) {
		int minSeconds = actionResult.get(OlioFieldNames.FIELD_ACTION_MINIMUM_TIME);
		ActionUtil.edgeSecondsUntilEnd(actionResult, minSeconds * iter);
		Queue.queueUpdate(actionResult, new String[]{OlioFieldNames.FIELD_ACTION_END});
	}
	
	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {

	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.OPERATE;
	}
	
	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		int timeSecs = actionResult.get(OlioFieldNames.FIELD_ACTION_MINIMUM_TIME);
		return (long)(timeSecs * 1000);
	}

	@Override
	public ActionResultEnumType concludeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		return actionResult.getEnum(FieldNames.FIELD_TYPE);
	}

	@Override
	public List<BaseRecord> definePolicyFactParameters(OlioContext context, BaseRecord actionResult, BaseRecord actor,
			BaseRecord interactor) throws OlioException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean counterAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor)
			throws OlioException {
		return false;
	}

	/**
	 * Apply the action's declared effects to the actor's state and statistics.
	 * Called during or after action execution to modify energy, hunger, thirst, fatigue, health,
	 * and instinct values based on the action's configuration and outcome.
	 */
	protected void applyActionEffects(OlioContext context, BaseRecord actionResult, BaseRecord actor, OutcomeEnumType outcome) {
		BaseRecord action = actionResult.get(FieldNames.FIELD_ACTION);
		if (action == null) {
			return;
		}
		IOSystem.getActiveContext().getReader().populate(action);

		BaseRecord state = actor.get(FieldNames.FIELD_STATE);
		if (state == null) {
			return;
		}

		double outcomeMod = getOutcomeModifier(outcome);

		// 1. Apply energy cost from action
		double energyCost = action.get("minimumEnergyCost");
		if (energyCost > 0) {
			double currentEnergy = state.get("energy");
			state.setValue("energy", Math.max(0.0, currentEnergy - energyCost));
		}

		// 2. Apply fatigue from time-consuming actions
		int minTime = action.get("minimumTime");
		if (minTime > 0) {
			double fatigueCost = minTime / 7200.0; // 1 hour of action = 0.5 fatigue
			double currentFatigue = state.get("fatigue");
			state.setValue("fatigue", Math.min(1.0, currentFatigue + fatigueCost));
		}

		// 3. Apply negative state effects (health, energy degradation)
		List<String> negStates = action.get("negativeStates");
		if (negStates != null) {
			double degradation = 0.05 * Math.abs(outcomeMod); // Worse outcomes = more damage
			for (String ns : negStates) {
				if ("health".equals(ns)) {
					double health = state.get("health");
					state.setValue("health", Math.max(0.0, health - degradation));
				} else if ("energy".equals(ns)) {
					double energy = state.get("energy");
					state.setValue("energy", Math.max(0.0, energy - degradation));
				}
			}
		}

		// 4. Apply positive state effects on favorable outcomes
		List<String> posStates = action.get("positiveStates");
		if (posStates != null && outcomeMod > 0) {
			double restoration = 0.05 * outcomeMod;
			for (String ps : posStates) {
				if ("health".equals(ps)) {
					double health = state.get("health");
					state.setValue("health", Math.min(1.0, health + restoration));
				} else if ("energy".equals(ps)) {
					double energy = state.get("energy");
					state.setValue("energy", Math.min(1.0, energy + restoration));
				}
			}
		}

		// 5. Consume items if action has an item category (food reduces hunger, water reduces thirst)
		BaseRecord params = actionResult.get("parameters");
		if (params != null) {
			String itemCategory = params.get("itemCategory");
			if (itemCategory != null) {
				if ("food".equals(itemCategory)) {
					double hunger = state.get("hunger");
					state.setValue("hunger", Math.max(0.0, hunger - 0.3));
				} else if ("water".equals(itemCategory)) {
					double thirst = state.get("thirst");
					state.setValue("thirst", Math.max(0.0, thirst - 0.3));
				}
			}
		}

		// 6. Satisfy instincts - reduce instinct pressure for positive instincts
		List<String> posInstincts = action.get("positiveInstincts");
		if (posInstincts != null && outcomeMod > 0) {
			BaseRecord instinct = actor.get("instinct");
			if (instinct != null) {
				for (String pi : posInstincts) {
					if (instinct.hasField(pi)) {
						double current = instinct.get(pi);
						double reduction = 10 * outcomeMod;
						instinct.setValue(pi, Math.max(-100.0, current - reduction));
					}
				}
			}
		}

		// 7. Aggravate instincts - increase pressure for negative instincts
		List<String> negInstincts = action.get("negativeInstincts");
		if (negInstincts != null) {
			BaseRecord instinct = actor.get("instinct");
			if (instinct != null) {
				for (String ni : negInstincts) {
					if (instinct.hasField(ni)) {
						double current = instinct.get(ni);
						instinct.setValue(ni, Math.min(100.0, current + 5.0));
					}
				}
			}
		}

		// 8. Handle sleep-specific effects
		String actionName = action.get(FieldNames.FIELD_NAME);
		if ("sleep".equals(actionName) && outcomeMod > 0) {
			state.setValue("fatigue", Math.max(0.0, (double)state.get("fatigue") - 0.8));
			state.setValue("energy", Math.min(1.0, (double)state.get("energy") + 0.5));
		}

		Queue.queueUpdate(state, new String[]{"energy", "hunger", "thirst", "fatigue", "health"});
	}

	/**
	 * Convert outcome to a numeric modifier for effect calculations.
	 * VERY_FAVORABLE=1.5, FAVORABLE=1.0, EQUILIBRIUM=0.5, UNFAVORABLE=-0.5, VERY_UNFAVORABLE=-1.0
	 */
	protected double getOutcomeModifier(OutcomeEnumType outcome) {
		if (outcome == null) return 0.5;
		switch (outcome) {
			case VERY_FAVORABLE: return 1.5;
			case FAVORABLE: return 1.0;
			case EQUILIBRIUM: return 0.5;
			case UNFAVORABLE: return -0.5;
			case VERY_UNFAVORABLE: return -1.0;
			default: return 0.5;
		}
	}

	/**
	 * Invert an outcome (actor's favorable = interactor's unfavorable).
	 */
	protected OutcomeEnumType invertOutcome(OutcomeEnumType outcome) {
		if (outcome == null) return OutcomeEnumType.EQUILIBRIUM;
		switch (outcome) {
			case VERY_FAVORABLE: return OutcomeEnumType.VERY_UNFAVORABLE;
			case FAVORABLE: return OutcomeEnumType.UNFAVORABLE;
			case UNFAVORABLE: return OutcomeEnumType.FAVORABLE;
			case VERY_UNFAVORABLE: return OutcomeEnumType.VERY_FAVORABLE;
			default: return OutcomeEnumType.EQUILIBRIUM;
		}
	}

	/**
	 * Calculate a stat modifier from a list of relevant statistics.
	 * Returns the average of the named stats, normalized to a 0-20 range modifier.
	 */
	protected double calculateStatModifier(BaseRecord statistics, List<String> statNames) {
		if (statistics == null || statNames == null || statNames.isEmpty()) {
			return 0.0;
		}
		double total = 0.0;
		int count = 0;
		for (String stat : statNames) {
			if (statistics.hasField(stat)) {
				total += (int) statistics.get(stat);
				count++;
			}
		}
		if (count == 0) return 0.0;
		// Average stat, then center around 10 (average stat) to get a +/- modifier
		return (total / count) - 10.0;
	}

	/**
	 * Resolve two competing scores into an OutcomeEnumType.
	 */
	protected OutcomeEnumType resolveOutcome(double actorScore, double interactorScore) {
		double diff = actorScore - interactorScore;
		if (diff >= 8.0) return OutcomeEnumType.VERY_FAVORABLE;
		if (diff >= 3.0) return OutcomeEnumType.FAVORABLE;
		if (diff >= -3.0) return OutcomeEnumType.EQUILIBRIUM;
		if (diff >= -8.0) return OutcomeEnumType.UNFAVORABLE;
		return OutcomeEnumType.VERY_UNFAVORABLE;
	}
}
