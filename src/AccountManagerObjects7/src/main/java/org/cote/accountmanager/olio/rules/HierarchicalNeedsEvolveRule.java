package org.cote.accountmanager.olio.rules;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.NeedsUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OverwatchException;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class HierarchicalNeedsEvolveRule extends CommonEvolveRule implements IOlioEvolveRule {
	public static final Logger logger = LogManager.getLogger(HierarchicalNeedsEvolveRule.class);

	private static final SecureRandom random = new SecureRandom();

	/// Hunger accumulation per increment (1 hour). ~50 hours to starve from sated.
	private static final double HUNGER_PER_INCREMENT = 0.02;
	/// Thirst accumulation per increment. ~25 hours to dehydrate from hydrated.
	private static final double THIRST_PER_INCREMENT = 0.04;
	/// Fatigue accumulation per increment while awake. ~16 hours awake to exhaustion.
	private static final double FATIGUE_PER_INCREMENT = 0.06;
	/// Health degradation when a critical need is maxed out
	private static final double HEALTH_DEGRADATION = 0.02;

	/// Use a smaller party selection of the population for tuning events
	///
	private boolean partyPlay = true;


	@Override
	public void evaluateRealmIncrement(OlioContext context, BaseRecord realm) {

		/// populate any animal life as needed
		AnimalUtil.checkAnimalPopulation(context, realm, realm.get(OlioFieldNames.FIELD_ORIGIN));

		/// Accumulate needs pressure for all non-player-controlled population each increment
		List<BaseRecord> allPop = context.getRealmPopulation(realm);
		accumulateNeeds(allPop);

		/// Party Play will pick a small band to work with, versus the total population
		/// This becomes the primaryGroup of the 'realm' for this location
		List<BaseRecord> party = (partyPlay ? GroupDynamicUtil.getCreateParty(context, realm) : allPop);

		/// Filter out player-controlled characters from automatic evolution
		party = party.stream().filter(p -> {
			BaseRecord state = p.get(FieldNames.FIELD_STATE);
			if (state == null) return true;
			Boolean pc = state.get("playerControlled");
			return pc == null || !pc;
		}).collect(Collectors.toList());

		if (!party.isEmpty()) {
			NeedsUtil.recommend(context, realm, party);

			try {
				context.overwatchActions();
			} catch (OverwatchException e) {
				logger.error(e);
			}
		}
	}

	/**
	 * Accumulate hunger, thirst, and fatigue for all alive, non-player-controlled characters.
	 * When critical needs max out (hunger=1 or thirst=1), health begins to degrade.
	 */
	private void accumulateNeeds(List<BaseRecord> population) {
		for (BaseRecord person : population) {
			BaseRecord state = person.get(FieldNames.FIELD_STATE);
			if (state == null) continue;

			Boolean alive = state.get(OlioFieldNames.FIELD_ALIVE);
			if (alive == null || !alive) continue;

			Boolean playerControlled = state.get("playerControlled");
			if (playerControlled != null && playerControlled) continue;

			double hunger = state.get("hunger") != null ? (double) state.get("hunger") : 0.0;
			double thirst = state.get("thirst") != null ? (double) state.get("thirst") : 0.0;
			double fatigue = state.get("fatigue") != null ? (double) state.get("fatigue") : 0.0;
			double health = state.get("health") != null ? (double) state.get("health") : 1.0;

			// Accumulate hunger and thirst
			hunger = Math.min(1.0, hunger + HUNGER_PER_INCREMENT);
			thirst = Math.min(1.0, thirst + THIRST_PER_INCREMENT);

			// Accumulate fatigue only while awake
			Boolean awake = state.get("awake");
			if (awake == null || awake) {
				fatigue = Math.min(1.0, fatigue + FATIGUE_PER_INCREMENT);
			}

			// Degrade health when critical needs are maxed
			if (hunger >= 1.0 || thirst >= 1.0) {
				health = Math.max(0.0, health - HEALTH_DEGRADATION);
			}

			state.setValue("hunger", hunger);
			state.setValue("thirst", thirst);
			state.setValue("fatigue", fatigue);
			state.setValue("health", health);

			Queue.queueUpdate(state, new String[]{"hunger", "thirst", "fatigue", "health"});
		}
		Queue.processQueue();
	}

}

