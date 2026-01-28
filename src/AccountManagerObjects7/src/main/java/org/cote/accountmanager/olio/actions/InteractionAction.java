package org.cote.accountmanager.olio.actions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ProfileUtil;
import org.cote.accountmanager.olio.RollUtil;
import org.cote.accountmanager.olio.VeryEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

/**
 * Generic IAction implementation that handles all interaction types (COMBAT, BARTER, SOCIALIZE, etc.).
 * Maps interaction types to component action sequences and resolves outcomes using the full
 * personality/stat/instinct system.
 *
 * Each interaction type decomposes into one or more atomic actions (fight, defend, transfer, etc.)
 * whose stat weights, personality modifiers, and instinct modifiers drive outcome resolution.
 */
public class InteractionAction extends CommonAction {

	public static final Logger logger = LogManager.getLogger(InteractionAction.class);

	/**
	 * Maps interaction type names to their component action sequences.
	 * The first action in the array is the primary action whose stats drive the outcome roll.
	 * Time cost is summed across all component actions.
	 */
	private static final Map<String, String[]> INTERACTION_ACTIONS = new HashMap<>();
	static {
		// Combat/Physical interactions
		INTERACTION_ACTIONS.put("COMBAT", new String[]{"fight", "defend"});
		INTERACTION_ACTIONS.put("CONFLICT", new String[]{"fight", "defend"});
		INTERACTION_ACTIONS.put("DEFEND", new String[]{"defend"});
		INTERACTION_ACTIONS.put("COMPETE", new String[]{"fight"});
		INTERACTION_ACTIONS.put("OPPOSE", new String[]{"fight", "defend"});

		// Social/Diplomatic interactions
		INTERACTION_ACTIONS.put("SOCIALIZE", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("BEFRIEND", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("ALLY", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("COOPERATE", new String[]{"gather"});
		INTERACTION_ACTIONS.put("ACCOMMODATE", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("HELP", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("MENTOR", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("ENTERTAIN", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("RECREATE", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("EXPRESS_GRATITUDE", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("CORRESPOND", new String[]{"socialize"});

		// Commerce/Exchange interactions
		INTERACTION_ACTIONS.put("BARTER", new String[]{"transfer"});
		INTERACTION_ACTIONS.put("COMMERCE", new String[]{"transfer"});
		INTERACTION_ACTIONS.put("EXCHANGE", new String[]{"transfer"});
		INTERACTION_ACTIONS.put("NEGOTIATE", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("DEBATE", new String[]{"socialize"});

		// Coercion/Threat interactions
		INTERACTION_ACTIONS.put("COERCE", new String[]{"wait"});
		INTERACTION_ACTIONS.put("THREATEN", new String[]{"wait"});
		INTERACTION_ACTIONS.put("PEER_PRESSURE", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("CRITICIZE", new String[]{"wait"});
		INTERACTION_ACTIONS.put("SHUN", new String[]{"wait"});

		// Romantic/Intimate interactions
		INTERACTION_ACTIONS.put("ROMANCE", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("INTIMATE", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("DATE", new String[]{"socialize"});
		INTERACTION_ACTIONS.put("BREAK_UP", new String[]{"wait"});

		// Investigation/Exploration
		INTERACTION_ACTIONS.put("INVESTIGATE", new String[]{"look", "scout"});

		// Escape
		INTERACTION_ACTIONS.put("EXPRESS_INDIFFERENCE", new String[]{"wait"});
	}

	/**
	 * Stat overrides per interaction type. When an interaction type uses different stats
	 * than its component action's positiveStatistics, this map provides the override.
	 */
	private static final Map<String, String[]> INTERACTION_STATS = new HashMap<>();
	static {
		INTERACTION_STATS.put("COERCE", new String[]{"mentalStrength", "charisma"});
		INTERACTION_STATS.put("THREATEN", new String[]{"mentalStrength", "physicalStrength"});
		INTERACTION_STATS.put("NEGOTIATE", new String[]{"intelligence", "charisma"});
		INTERACTION_STATS.put("DEBATE", new String[]{"intelligence", "wisdom", "charisma"});
		INTERACTION_STATS.put("MENTOR", new String[]{"wisdom", "intelligence"});
		INTERACTION_STATS.put("ROMANCE", new String[]{"charisma", "creativity"});
		INTERACTION_STATS.put("INTIMATE", new String[]{"charisma", "creativity"});
		INTERACTION_STATS.put("ENTERTAIN", new String[]{"charisma", "creativity"});
		INTERACTION_STATS.put("BEFRIEND", new String[]{"charisma", "wisdom"});
		INTERACTION_STATS.put("INVESTIGATE", new String[]{"intelligence", "perception", "wisdom"});
		INTERACTION_STATS.put("HELP", new String[]{"charisma", "wisdom"});
		INTERACTION_STATS.put("BARTER", new String[]{"intelligence", "charisma"});
		INTERACTION_STATS.put("PEER_PRESSURE", new String[]{"charisma", "mentalStrength"});
		INTERACTION_STATS.put("CRITICIZE", new String[]{"intelligence", "mentalStrength"});
	}

	@Override
	public EventEnumType getEventType() {
		return EventEnumType.OPERATE;
	}

	@Override
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		// Set the action's time bounds based on component actions
		long costMs = calculateCostMS(context, actionResult, actor, interactor);
		int costSeconds = (int)(costMs / 1000);
		actionResult.setValue(OlioFieldNames.FIELD_ACTION_MINIMUM_TIME, costSeconds);
	}

	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.IN_PROGRESS);
		edgeEnd(context, actionResult, 1);
		return actionResult;
	}

	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		if (interactor == null) {
			logger.warn("InteractionAction requires an interactor");
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
			return false;
		}

		// 1. Get the interaction type from the actionResult's interactions list
		List<BaseRecord> interactions = actionResult.get(OlioFieldNames.FIELD_INTERACTIONS);
		if (interactions == null || interactions.isEmpty()) {
			logger.warn("No interaction record on actionResult");
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.FAILED);
			return false;
		}

		BaseRecord interaction = interactions.get(0);
		IOSystem.getActiveContext().getReader().populate(interaction);
		InteractionEnumType iType = interaction.getEnum(FieldNames.FIELD_TYPE);
		String typeName = iType != null ? iType.toString() : "UNKNOWN";

		// 2. Get the primary component action and its stat weights
		String[] components = INTERACTION_ACTIONS.getOrDefault(typeName, new String[]{"wait"});
		BaseRecord primaryAction = ActionUtil.getAction(context, components[0]);
		if (primaryAction == null) {
			logger.warn("Cannot find primary action: " + components[0]);
			primaryAction = ActionUtil.getAction(context, "wait");
		}

		// 3. Determine which stats to use (interaction override or primary action's stats)
		List<String> statNames;
		if (INTERACTION_STATS.containsKey(typeName)) {
			statNames = List.of(INTERACTION_STATS.get(typeName));
		} else {
			statNames = primaryAction.get("positiveStatistics");
			if (statNames == null || statNames.isEmpty()) {
				statNames = List.of("charisma", "intelligence");
			}
		}

		// 4. Calculate stat modifiers for actor and interactor
		BaseRecord actorStats = actor.get(OlioFieldNames.FIELD_STATISTICS);
		BaseRecord interactorStats = interactor.get(OlioFieldNames.FIELD_STATISTICS);
		double actorStatMod = calculateStatModifier(actorStats, statNames);
		double interactorStatMod = calculateStatModifier(interactorStats, statNames);

		// 5. Apply personality modifiers
		double actorPersonalityMod = calculatePersonalityModifier(context, actor, typeName);
		double interactorPersonalityMod = calculatePersonalityModifier(context, interactor, typeName);

		// 6. Apply instinct modifiers
		double actorInstinctMod = calculateInstinctModifier(actor, primaryAction);
		double interactorInstinctMod = calculateInstinctModifier(interactor, primaryAction);

		// 7. Roll and resolve outcome
		double actorRoll = RollUtil.roll20Dbl();
		double interactorRoll = RollUtil.roll20Dbl();

		double actorScore = actorRoll + actorStatMod + actorPersonalityMod + actorInstinctMod;
		double interactorScore = interactorRoll + interactorStatMod + interactorPersonalityMod + interactorInstinctMod;

		logger.info("InteractionAction [" + typeName + "] actor=" + String.format("%.1f", actorScore)
				+ " (roll=" + String.format("%.1f", actorRoll)
				+ " stat=" + String.format("%.1f", actorStatMod)
				+ " pers=" + String.format("%.1f", actorPersonalityMod)
				+ " inst=" + String.format("%.1f", actorInstinctMod) + ")"
				+ " vs interactor=" + String.format("%.1f", interactorScore));

		OutcomeEnumType actorOutcome = resolveOutcome(actorScore, interactorScore);
		OutcomeEnumType interactorOutcome = invertOutcome(actorOutcome);

		// 8. Set outcomes on actionResult and interaction
		actionResult.setValue("outcome", actorOutcome);
		interaction.setValue("actorOutcome", actorOutcome);
		interaction.setValue("interactorOutcome", interactorOutcome);
		interaction.setValue("state", ActionResultEnumType.SUCCEEDED);
		Queue.queueUpdate(interaction, new String[]{"actorOutcome", "interactorOutcome", "state"});

		// 9. Apply effects to both actor and interactor
		applyActionEffects(context, actionResult, actor, actorOutcome);
		applyActionEffects(context, actionResult, interactor, interactorOutcome);

		// 10. Advance action progress by time cost
		long costMs = calculateCostMS(context, actionResult, actor, interactor);
		ActionUtil.addProgressMS(actionResult, costMs);

		actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.SUCCEEDED);
		return true;
	}

	@Override
	public ActionResultEnumType concludeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		ActionResultEnumType currentType = actionResult.getEnum(FieldNames.FIELD_TYPE);
		if (currentType == ActionResultEnumType.IN_PROGRESS) {
			actionResult.setValue(FieldNames.FIELD_TYPE, ActionResultEnumType.SUCCEEDED);
		}
		return actionResult.getEnum(FieldNames.FIELD_TYPE);
	}

	@Override
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		// Get interaction type to look up component actions
		List<BaseRecord> interactions = actionResult.get(OlioFieldNames.FIELD_INTERACTIONS);
		String typeName = "UNKNOWN";
		if (interactions != null && !interactions.isEmpty()) {
			InteractionEnumType iType = interactions.get(0).getEnum(FieldNames.FIELD_TYPE);
			if (iType != null) {
				typeName = iType.toString();
			}
		}

		// Sum minimumTime from all component actions
		String[] components = INTERACTION_ACTIONS.getOrDefault(typeName, new String[]{"wait"});
		long totalMs = 0;
		for (String comp : components) {
			BaseRecord act = ActionUtil.getAction(context, comp);
			if (act != null) {
				int minTime = act.get("minimumTime");
				totalMs += (long) minTime * 1000L;
			}
		}
		return totalMs > 0 ? totalMs : 60000L; // Default 1 minute
	}

	/**
	 * Calculate personality modifier for a given interaction type.
	 * Uses Big Five traits and Dark Tetrad to adjust the roll.
	 */
	private double calculatePersonalityModifier(OlioContext context, BaseRecord person, String interactionType) {
		PersonalityProfile prof = ProfileUtil.getProfile(context, person);
		if (prof == null) {
			return 0.0;
		}

		double mod = 0.0;

		// Extraversion benefits social interactions
		if (isSocialInteraction(interactionType)) {
			mod += veryToModifier(prof.getExtraverted()) * 2.0;
			mod += veryToModifier(prof.getAgreeable());
		}

		// Agreeableness benefits cooperative interactions
		if (isCooperativeInteraction(interactionType)) {
			mod += veryToModifier(prof.getAgreeable()) * 2.0;
			mod += veryToModifier(prof.getConscientious());
		}

		// Openness benefits creative/romantic interactions
		if (isCreativeInteraction(interactionType)) {
			mod += veryToModifier(prof.getOpen()) * 2.0;
		}

		// Machiavellian benefits coercive interactions
		if (isCoerciveInteraction(interactionType)) {
			mod += veryToModifier(prof.getMachiavellian()) * 2.0;
			mod += veryToModifier(prof.getNarcissist());
		}

		// Neuroticism penalizes threatening situations
		if (isThreatInteraction(interactionType)) {
			mod -= veryToModifier(prof.getNeurotic());
		}

		// Conscientiousness benefits structured interactions
		if (isStructuredInteraction(interactionType)) {
			mod += veryToModifier(prof.getConscientious()) * 1.5;
		}

		return mod;
	}

	/**
	 * Calculate instinct modifier based on the action's positive/negative instincts
	 * and the character's current instinct values.
	 */
	private double calculateInstinctModifier(BaseRecord person, BaseRecord action) {
		BaseRecord instinct = person.get("instinct");
		if (instinct == null || action == null) {
			return 0.0;
		}

		double mod = 0.0;

		// Positive instincts: high values give bonuses (instinct aligned with action)
		List<String> posInstincts = action.get("positiveInstincts");
		if (posInstincts != null) {
			for (String pi : posInstincts) {
				if (instinct.hasField(pi)) {
					double val = instinct.get(pi);
					mod += val / 50.0; // Range -100 to 100 → -2.0 to 2.0
				}
			}
		}

		// Negative instincts: high values give penalties (instinct opposed to action)
		List<String> negInstincts = action.get("negativeInstincts");
		if (negInstincts != null) {
			for (String ni : negInstincts) {
				if (instinct.hasField(ni)) {
					double val = instinct.get(ni);
					mod -= val / 50.0;
				}
			}
		}

		return mod;
	}

	/**
	 * Convert VeryEnumType to a numeric modifier (-0.9 to 1.0 range).
	 */
	private double veryToModifier(VeryEnumType val) {
		if (val == null) return 0.0;
		switch (val) {
			case DISREGARDED: return -0.9;
			case NEVER: return 0.0;
			case UNLIKELY: return 0.1;
			case SLIGHTLY: return 0.2;
			case NOT_USUALLY: return 0.3;
			case LESS_FREQUENTLY: return 0.4;
			case SOMEWHAT: return 0.5;
			case FREQUENTLY: return 0.6;
			case USUALLY: return 0.7;
			case MOSTLY: return 0.8;
			case VERY: return 0.9;
			case ALWAYS: return 1.0;
			case GUARANTEED: return 1.1;
			default: return 0.0;
		}
	}

	// Interaction type classification helpers

	private boolean isSocialInteraction(String type) {
		return "SOCIALIZE".equals(type) || "BEFRIEND".equals(type) || "ALLY".equals(type)
				|| "ENTERTAIN".equals(type) || "RECREATE".equals(type) || "ACCOMMODATE".equals(type)
				|| "CORRESPOND".equals(type) || "EXPRESS_GRATITUDE".equals(type) || "HELP".equals(type);
	}

	private boolean isCooperativeInteraction(String type) {
		return "COOPERATE".equals(type) || "HELP".equals(type) || "ALLY".equals(type)
				|| "ACCOMMODATE".equals(type);
	}

	private boolean isCreativeInteraction(String type) {
		return "ROMANCE".equals(type) || "INTIMATE".equals(type) || "DATE".equals(type)
				|| "ENTERTAIN".equals(type);
	}

	private boolean isCoerciveInteraction(String type) {
		return "COERCE".equals(type) || "THREATEN".equals(type) || "PEER_PRESSURE".equals(type)
				|| "CRITICIZE".equals(type) || "SHUN".equals(type);
	}

	private boolean isThreatInteraction(String type) {
		return "COMBAT".equals(type) || "CONFLICT".equals(type) || "THREATEN".equals(type)
				|| "OPPOSE".equals(type);
	}

	private boolean isStructuredInteraction(String type) {
		return "NEGOTIATE".equals(type) || "DEBATE".equals(type) || "BARTER".equals(type)
				|| "COMMERCE".equals(type) || "MENTOR".equals(type) || "INVESTIGATE".equals(type);
	}
}
