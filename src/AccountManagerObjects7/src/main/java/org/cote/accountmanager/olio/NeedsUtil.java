package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.actions.ActionUtil;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;


/*
 * In the Hierarchy of Needs driven rule system, the order of evaluation is:
 * (3 Rs of Needs Evaluation) Recommend, Re/Act, Result
 * Recommend:
 * 	- Use the epoch alignment as a guide for external/random occurrences, and as a sentiment modifier
 *  - Iterate through population, subgroup, or individual
 *  - Calculate initial instinct modifiers, behaviors for the event increment
 *  - Calculate initial state change for the event increment
 *  - Create 2 profiles: current profile (what they are), and projected event outcome profile (what they want - char state vs char + event state)
 *  - For each individual, recommend zero or more actions based on need and 'current' and 'projected' personality
 *     - Produced actions may be chains of dependencies.  E.G.: To eat while having no food would spawn forage, or hunt.  Hunt would spawn the need for a trap or weapon, etc.
 *  - Evaluate the list of actions any peer groups the provided group members belong to
 *     - This may result in immediate actions/results such as attempting to lead or influence choice
 *  - Evaluate the list against any adjacent or established group that may have influence over the peer group or individual
 *     - E.G.: If there's a government, or established leader/leadership group
 *  - Evaluate the list against any authority group that may have or try to exert control over the peer group or individual
 *  - Evaluate the list again individually for personality influencers
 *  - Evaluate the list again individually for social group modifiers
 *  
 *  - Therefore, the order of recommendation is: individual, peer group, any adjacent peer group, leadership group, any authority group, individual, peer group
 *  - The result of the recommendation is a list of pending action chains assigned to zero or more people (zero would mean the action isn't performed, but is kept as a marker).
 *  - The list of actions may now include counter actions from the iterative review
 *  - If an action is determined to be repeatable, attach any schedules off the event
 * Re/Act
 * 	- Actions are started in order.
 *  - For each action, determine if a counter action was included
 *  - For each counter action, evaluate a 'decide' action to determine if it conducted.  If so, that action becomes contested 
 *  - For contested actions, determine the starting order (who goes first), and then evaluate each.
 *  - For any participants involved in a contested action, re-evaluate their assigned actions to determine any impacts
 *  - Evaluate all remaining ActionResult results and modifiers.
 *  - Evaluate all ActionResults for recommended subsequent actions.
 *  - Evaluate any subsequent action than can/should be completed in the increment.  The remaining subsequent actions will be picked up at a later increment
 * Result
 *  - Evaluate the ActionResults for each individual
 *  - Evaluate the results for each peer group for peer group / behavior adjustment
 *  - Evaluate the results for any adjacent peer group
 *  - Evaluate the results for any leadership or authority group
 *  - Reevaluate the results for each individual for personality influencers
 *  - Reevaluate the results for each individual for social group influencers
 *  
 * The 3Rs are evaluated after preparatory consideration
 * 1) Assess Locations
 *    a) The base location (check stores, populate items/animals, etc)
 *    b) The current location (check stores, populate items/animals, etc)
 *    c) The current population (check for personality-driven events/actions)
 *    c) The current peer groups (check for personality-driven events/actions)
 *    c) Rules should apply to the current location
 * 2) Assess Group/Individual
 *    a) What are the current needs, and which is most pressing?
 *    b) What is the current relative situation (expressed as a state and description)
 *    c) Are there any current threats?  People, groups, animals, events (eg: natural)
 *    d) Eval 3Rs
 * 3) Post Process
 *    A) Results from #1 and #2 should be queued as much as possible 
 *    b) Determine significance, if any, of ActionResults
 *    c) Evaluate behavior and personality impacts
 *  
 */

public class NeedsUtil {

    public static final Logger logger = LogManager.getLogger(NeedsUtil.class);
    private static final SecureRandom random = new SecureRandom();

    public static List<BaseRecord> recommend(OlioContext ctx, BaseRecord realm) {
        return new ArrayList<>();
    }

    public static List<BaseRecord> recommend(OlioContext ctx, BaseRecord realm, List<BaseRecord> group) {
        Map<BaseRecord, PersonalityProfile> map = ProfileUtil.getProfileMap(ctx, group);
        logger.info("Calculating recommendation ....");

        BaseRecord increment = null;
        try {
			increment = ctx.clock().realmClock(realm).getIncrement();
		} catch (ClockException e) {
			logger.error(e);
			e.printStackTrace();
		}
        if(increment == null) {
        	return new ArrayList<>();
        }

        List<BaseRecord> tinters = evaluateThreats(ctx, realm, increment, map);
        logger.info("Calculating threat interactions ... " + tinters.size());

        List<BaseRecord> actions = evaluateNeeds(ctx, realm, group, map);
        logger.info("Group actions to delegate: " + actions.size());

        GroupDynamicUtil.delegateActions(ctx, realm, map, actions);

        return new ArrayList<>();
    }

    private static List<BaseRecord> evaluateThreats(OlioContext ctx, BaseRecord realm, BaseRecord increment, Map<BaseRecord, PersonalityProfile> map) {
        Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> tmap = getAgitatedThreatMap(ctx, realm, increment, map, false);
        List<BaseRecord> tinters = ThreatUtil.evaluateThreatMap(ctx, tmap, increment);
        OlioUtil.batchAddForeignList(ctx, increment, OlioFieldNames.FIELD_INTERACTIONS, tinters);
        return tinters;
    }

    protected static BaseRecord createActionForNeed(OlioContext ctx, PhysiologicalNeedsEnumType need) {
        return createAction(ctx, need, "physiological");
    }

    protected static BaseRecord createActionForNeed(OlioContext ctx, SafetyNeedsEnumType need) {
        return createAction(ctx, need, "safety");
    }

    protected static BaseRecord createActionForNeed(OlioContext ctx, EsteemNeedsEnumType need) {
        return createAction(ctx, need, "esteem");
    }

    protected static BaseRecord createActionForNeed(OlioContext ctx, LoveNeedsEnumType need) {
        return createAction(ctx, need, "love");
    }

    private static BaseRecord createAction(OlioContext ctx, Enum<?> need, String needType) {
        BaseRecord action = null;
        BaseRecord builder = null;
        BaseRecord actionResult = null;

        List<BaseRecord> actions = getActionsForNeed(ctx, need);
        if (!actions.isEmpty()) {
            action = actions.get(random.nextInt(actions.size()));
        }

        if (action != null) {
            ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, ctx.getWorld().get(OlioFieldNames.FIELD_ACTION_RESULTS_PATH));
            try {
                actionResult = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_ACTION_RESULT, ctx.getOlioUser(), null, plist);
                actionResult.set(FieldNames.FIELD_ACTION, action);
                actionResult.set(OlioFieldNames.FIELD_BUILDER, builder);
                actionResult.set(OlioFieldNames.FIELD_NEED_TYPE, needType);
                actionResult.set(OlioFieldNames.FIELD_NEED, need.toString().toLowerCase());
            } catch (FactoryException | FieldException | ValueException | ModelNotFoundException e) {
                logger.error(e);
            }
        }

        return actionResult;
    }

    private static List<BaseRecord> getActionsForNeed(OlioContext ctx, Enum<?> need) {
        List<BaseRecord> actions = new ArrayList<>();
        if (need instanceof PhysiologicalNeedsEnumType) {
            actions = getPhysiologicalActions(ctx, (PhysiologicalNeedsEnumType) need);
        } else if (need instanceof SafetyNeedsEnumType) {
            actions = getSafetyActions(ctx, (SafetyNeedsEnumType) need);
        } else if (need instanceof EsteemNeedsEnumType) {
            actions = getEsteemActions(ctx, (EsteemNeedsEnumType) need);
        } else if (need instanceof LoveNeedsEnumType) {
            actions = getLoveActions(ctx, (LoveNeedsEnumType) need);
        }
        return actions;
    }

    private static List<BaseRecord> getPhysiologicalActions(OlioContext ctx, PhysiologicalNeedsEnumType need) {
        List<BaseRecord> actions = new ArrayList<>();
        if (need == PhysiologicalNeedsEnumType.SHELTER) {
            actions = Arrays.asList(ActionUtil.getActions(ctx)).stream()
                    .filter(b -> ((String) b.get(FieldNames.FIELD_NAME)).equals("build"))
                    .collect(Collectors.toList());
        } else if (need == PhysiologicalNeedsEnumType.FOOD) {
            actions = Arrays.asList(ActionUtil.getActions(ctx)).stream()
                    .filter(b -> ((String) b.get(FieldNames.FIELD_NAME)).equals("gather") || ((String) b.get(FieldNames.FIELD_NAME)).equals("hunt"))
                    .collect(Collectors.toList());
        } else if (need == PhysiologicalNeedsEnumType.WATER) {
            actions = Arrays.asList(ActionUtil.getActions(ctx)).stream()
                    .filter(b -> ((String) b.get(FieldNames.FIELD_NAME)).equals("gather"))
                    .collect(Collectors.toList());
        }
        return actions;
    }

    private static List<BaseRecord> getSafetyActions(OlioContext ctx, SafetyNeedsEnumType need) {
        // Implement similar to getPhysiologicalActions
        return new ArrayList<>();
    }

    private static List<BaseRecord> getEsteemActions(OlioContext ctx, EsteemNeedsEnumType need) {
        // Implement similar to getPhysiologicalActions
        return new ArrayList<>();
    }

    private static List<BaseRecord> getLoveActions(OlioContext ctx, LoveNeedsEnumType need) {
        // Implement similar to getPhysiologicalActions
        return new ArrayList<>();
    }

    protected static List<BaseRecord> evaluateNeeds(OlioContext ctx, BaseRecord realm, List<BaseRecord> group, Map<BaseRecord, PersonalityProfile> map) {
        List<BaseRecord> actions = new ArrayList<>();
        PersonalityGroupProfile pgp = ProfileUtil.getGroupProfile(map);

        actions.addAll(evaluateNeedsForType(ctx, pgp.getPhysiologicalNeedsPriority(), "physiological"));
        actions.addAll(evaluateNeedsForType(ctx, pgp.getSafetyNeedsPriority(), "safety"));
        actions.addAll(evaluateNeedsForType(ctx, pgp.getLoveNeedsPriority(), "love"));
        actions.addAll(evaluateNeedsForType(ctx, pgp.getEsteemNeedsPriority(), "esteem"));

        return actions;
    }

    private static List<BaseRecord> evaluateNeedsForType(OlioContext ctx, List<? extends Enum<?>> needs, String needType) {
        List<BaseRecord> actions = new ArrayList<>();
        for (Enum<?> need : needs) {
            BaseRecord act = createAction(ctx, need, needType);
            if (act != null) {
                actions.add(act);
            }
        }
        return actions;
    }

    public static Map<PersonalityProfile, Map<ThreatEnumType, List<BaseRecord>>> getAgitatedThreatMap(OlioContext ctx, BaseRecord realm, BaseRecord event, Map<BaseRecord, PersonalityProfile> map, boolean roam) {
        agitateLocation(ctx, realm, event, Arrays.asList(map.keySet().toArray(new BaseRecord[0])), true, roam);
        return ThreatUtil.getThreatMap(ctx, realm, event, map);
    }

    protected static void agitateLocation(OlioContext ctx, BaseRecord realm, BaseRecord event, List<BaseRecord> pop, boolean cluster, boolean roam) {
        BaseRecord eloc = event.getP(FieldNames.FIELD_LOCATION);

        if (eloc == null) {
            logger.warn("Increment missing location");
            logger.info(event.toFullString());
            eloc = realm.get(OlioFieldNames.FIELD_ORIGIN);
            event.setValue(FieldNames.FIELD_LOCATION, eloc);
            Queue.queueUpdate(event, new String[]{FieldNames.FIELD_LOCATION});
        }
        List<BaseRecord> acells = GeoLocationUtil.getCells(ctx, eloc);
        BaseRecord rloc = null;

        try {
            for (BaseRecord p : pop) {
                String name = p.get(FieldNames.FIELD_NAME);
                BaseRecord state = p.get(FieldNames.FIELD_STATE);
                Boolean agitated = state.get("agitated");
                if (!roam && agitated != null && agitated) {
                    continue;
                }
                BaseRecord location = state.get(OlioFieldNames.FIELD_CURRENT_LOCATION);
                if (location == null) {
                    if (rloc == null) {
                        rloc = acells.get(random.nextInt(acells.size()));
                    }
                    location = rloc;
                    state.set(OlioFieldNames.FIELD_CURRENT_LOCATION, rloc);
                    if (!cluster) {
                        rloc = null;
                    }
                }
                StateUtil.agitateLocation(ctx, p);
                state.setValue("agitated", true);

                String geoType = location.get(FieldNames.FIELD_GEOTYPE);
                if (geoType.equals(FieldNames.FIELD_FEATURE)) {
                    logger.warn("Feature placement detected: Move " + name);
                } else {
                    List<String> upf = new ArrayList<>();
                    upf.add(OlioFieldNames.FIELD_CURRENT_LOCATION);
                    upf.add(FieldNames.FIELD_CURRENT_EAST);
                    upf.add(FieldNames.FIELD_CURRENT_NORTH);
                    if (!roam) {
                        upf.add("agitated");
                    }
                    Queue.queueUpdate(state, upf.toArray(new String[0]));
                }
            }
            Queue.processQueue();
        } catch (ModelNotFoundException | FieldException | ValueException e) {
            logger.error(e);
        }
    }

    public static boolean isNaked(BaseRecord record) {
        List<BaseRecord> items = record.get(OlioFieldNames.FIELD_STORE_APPAREL);
        return items.size() == 0;
    }

    public static boolean isUnarmed(BaseRecord record) {
        List<BaseRecord> items = record.get(OlioFieldNames.FIELD_STORE_ITEMS);
        List<BaseRecord> weaps = items.stream().filter(i -> "weapon".equals(i.get(OlioFieldNames.FIELD_CATEGORY))).collect(Collectors.toList());
        return weaps.size() == 0;
    }

    public static boolean needsFood(BaseRecord record) {
        List<BaseRecord> items = record.get(OlioFieldNames.FIELD_STORE_ITEMS);
        List<BaseRecord> food = items.stream().filter(i -> "food".equals(i.get(OlioFieldNames.FIELD_CATEGORY))).collect(Collectors.toList());
        return food.size() == 0;
    }

    public static boolean needsWater(BaseRecord record) {
        List<BaseRecord> items = record.get(OlioFieldNames.FIELD_STORE_ITEMS);
        List<BaseRecord> water = items.stream().filter(i -> "water".equals(i.get(OlioFieldNames.FIELD_CATEGORY))).collect(Collectors.toList());
        return water.size() == 0;
    }
}