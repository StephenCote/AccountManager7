package org.cote.accountmanager.olio;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.actions.ActionUtil;
import org.cote.accountmanager.olio.actions.Actions;
import org.cote.accountmanager.olio.actions.IAction;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;

public class Overwatch {

	/*
	 *  The intended process flow is as follows:
	 * 	0) Elements monitored by OverWatch:
	 * 		a) Interactions spawned from prior Overwatch
	 *		b) ActionResults that are PENDING or IN_PROGRESS 
	 *			/// Actions immediately spawned from in progress ActionResults
	 *		c) Actions spawned from ActionResults
	 *		d) Group or Proximity Reactions
	 *		e) Schedules that overlap with the current Clock cycle
	 *		f) Influence on events taking place in close proximity and that overlap with the current Clock cycle
	 *	1) Evaluate interactions (created during Overwatch of prior actions)
	 *  2) Evaluate ActionResults
	 *  	a) Check for Roll-Out based on new interactions that may require a higher priority action response
	 *  	b) Commit to available resources (items, stats, etc)
	 *      c) Commit to available time
	 *      d) Execute action
	 *      e) Check for Counter-out based on action and any interaction
	 *      f) Conclude the ActionResult
	 *      	i) Annotate ActionResult state and any effects
	 *  3) Determine Roll-Out / Counter-Out / Interaction
	 *     	a) Roll-Out - This is the result of an overwatched action or interaction aborting the current action such as when the current action is abandoned or deprioritized due to a more urgent action arising
	 *     	b) Counter-Out - This is any counter-action to begin
	 *      	c) Interaction based on the Counter-Out
	 *  4) Recoup
	 *  	a) Evaluate and annotate action outcomes affecting state or inventory
	 *  5) Conclude
	 *  	a) Annotate the action state
	 *  	b) Send Roll/Counter Out actions through Overwatch
	 *  	c) Send repeated or ongoing actions through Overwatc
	 */

	public static final Logger logger = LogManager.getLogger(Overwatch.class);

	public static enum OverwatchEnumType {
		UNKNOWN, EVENT, TIME, PROXIMITY, ACTION, RESPONSE, GROUP, INTERACTION
	};

	// private long minimumProcessTime = 500L;
	/// Some actions may result in many small incremental and/or repeating steps,
	// such as moving between two points.
	// If the average walking speed is 1.2 meters per second.
	/// Maximum process count is 1 hour; each cycle increments by one second
	private int maximumProcessCount = 3600;

	private OlioContext context = null;
	private Map<OverwatchEnumType, List<BaseRecord>> map = new ConcurrentHashMap<>();

	public Overwatch(OlioContext context) {
		this.context = context;

	}

	protected boolean isWatched(OverwatchEnumType type, long id) {
		if (!map.containsKey(type)) {
			return false;
		}
		List<BaseRecord> watched = map.get(type);
		return watched.stream().filter(a -> ((long) a.get(FieldNames.FIELD_ID) == id)).findFirst().isPresent();
	}

	protected void add(OverwatchEnumType type, List<BaseRecord> recs) {
		if (!map.containsKey(type)) {
			map.put(type, new CopyOnWriteArrayList<>());
		}
		map.get(type).addAll(recs);
	}

	public boolean watch(OverwatchEnumType type, BaseRecord[] actionResults) {
		List<BaseRecord> lar = new CopyOnWriteArrayList<>();
		for (BaseRecord ar : actionResults) {
			long id = ar.get(FieldNames.FIELD_ID);
			if (!isWatched(type, id)) {
				lar.add(ar);
			} else {
				logger.info(type.toString() + " #" + id + " is already watched");
			}
		}
		add(type, lar);
		return lar.size() > 0;
	}

	private void prune() {
		map.keySet().forEach(k -> {
			prune(k);
		});
	}

	private void prune(OverwatchEnumType type) {
		if (map.containsKey(type)) {
			List<BaseRecord> frec = getInProcessActions();
			List<BaseRecord> rec = new CopyOnWriteArrayList<>();
			rec.addAll(frec);
			map.put(type, frec);
		}
	}

	private List<BaseRecord> getInProcessActions() {
		List<BaseRecord> frec = new CopyOnWriteArrayList<>();
		if (map.containsKey(OverwatchEnumType.ACTION)) {
			frec = map.get(OverwatchEnumType.ACTION).stream().filter(r -> {
				boolean f = true;
				ActionResultEnumType atype = r.getEnum(FieldNames.FIELD_TYPE);
				f = (atype == ActionResultEnumType.IN_PROGRESS || atype == ActionResultEnumType.PENDING);
				return f;
			}).collect(Collectors.toList());
		}
		return frec;
	}

	public void updateClock() {
		/*
		 * BaseRecord evt = null; if(context.getCurrentIncrement() != null) { evt =
		 * context.getCurrentIncrement(); } else if(context.getCurrentEvent() != null) {
		 * evt = context.getCurrentEvent(); } else if(context.getCurrentEpoch() != null)
		 * { evt = context.getCurrentEpoch(); } if(evt != null) { this.clock = new
		 * Clock(evt); }
		 */
		// context.clock().addMilliseconds(minimumProcessTime);
	}

	protected void process() throws OverwatchException {
		int count = 0;
		while (count == 0 || getInProcessActions().size() > 0) {

			prune();
			processInteractions();
			// processActionResultResponses();
			/// Process Actions
			processActions();
			processGroup();
			processProximity();
			processTimedSchedules();
			processEvents();
			try {
				synchronizeClocks();
			} catch (ClockException e) {
				throw new OverwatchException(e);
			}
			Queue.processQueue();

			count++;
			if (count > maximumProcessCount) {
				throw new OverwatchException("Exceeded maximum process count");
			}

		}
	}

	protected void synchronizeClocks() throws ClockException {
		// logger.info(context.clock().getCurrent() + " to " +
		// context.clock().getEnd());
		BaseRecord inc = context.clock().getIncrement();
		ZonedDateTime progress = inc.get(OlioFieldNames.FIELD_EVENT_PROGRESS);
		ZonedDateTime end = inc.get(OlioFieldNames.FIELD_EVENT_END);
		long remainingSeconds = progress.until(end, ChronoUnit.SECONDS);
		// long remainingSeconds =
		// context.clock().getCurrent().until(context.clock().getEnd(),
		// ChronoUnit.SECONDS);

		if (remainingSeconds > maximumProcessCount) {
			throw new ClockException("Remaining seconds cannot exceed the increment of one hour");
		}

		inc.setValue(OlioFieldNames.FIELD_EVENT_PROGRESS, progress.plusSeconds(1));
		// 59:59
		if (remainingSeconds <= 1) {
			// if(remainingSeconds >= (maximumProcessCount - 1)){
			logger.warn("Move to next increment with remainingSeconds " + remainingSeconds);
			List<BaseRecord> rlms = context.getRealms();
			for (BaseRecord r : rlms) {

				List<IOlioEvolveRule> rules = context.getConfig().getEvolutionRules();
				rules.forEach(ru -> {
					ru.endRealmIncrement(context, r);
					BaseRecord ni = ru.nextRealmIncrement(context, r);
					if (ni == null) {
						logger.error("Failed to start next increment");
					} else {
						logger.info(ni.toFullString());
						r.setValue(OlioFieldNames.FIELD_CURRENT_INCREMENT, ni);
						Queue.queueUpdate(r, new String[] { OlioFieldNames.FIELD_CURRENT_INCREMENT });
					}

				});
			}
		}

		logger.info("Remaining seconds: " + remainingSeconds);
	}

	protected boolean checkReprocess() {
		boolean outBool = false;
		prune();
		if (getInProcessActions().size() > 0) {
			outBool = true;
		}
		return outBool;
	}

	/**
	 * Process a single action to completion without the full Overwatch loop.
	 * Used for player-driven actions in the card game where each action is resolved individually.
	 * Attaches resulting interactions to the current realm increment.
	 */
	public ActionResultEnumType processOne(BaseRecord actionResult) throws OverwatchException {
		try {
			String actionName = actionResult.get(OlioFieldNames.FIELD_ACTION_NAME2);
			if (actionName == null) {
				actionName = actionResult.get(OlioFieldNames.FIELD_ACTION_NAME);
			}
			IAction action = Actions.getActionProvider(context, actionName);
			if (action == null) {
				throw new OverwatchException("Invalid action: " + actionName);
			}

			BaseRecord actor = ActionUtil.getActionActor(actionResult);
			BaseRecord iactor = ActionUtil.getActionInteractor(actionResult);

			// Execute the action
			Actions.executeAction(context, actionResult, actor, iactor);

			// Conclude the action
			ActionResultEnumType result = Actions.concludeAction(context, actionResult, actor, iactor);

			// Attach interactions to the current increment event
			BaseRecord increment = context.clock().getIncrement();
			if (increment != null) {
				List<BaseRecord> interactions = actionResult.get(OlioFieldNames.FIELD_INTERACTIONS);
				if (interactions != null && !interactions.isEmpty()) {
					OlioUtil.batchAddForeignList(context, increment,
							OlioFieldNames.FIELD_INTERACTIONS, interactions);
				}
			}

			Queue.processQueue();
			return result;
		} catch (OlioException e) {
			logger.error(e);
			throw new OverwatchException(e);
		}
	}

	protected void processInteractions() {

	}

	protected void processActionResultResponses() {

	}

	protected void processActions() {
		if (map.containsKey(OverwatchEnumType.ACTION)) {
			List<BaseRecord> actionResults = map.get(OverwatchEnumType.ACTION);
			// logger.info("Processing actions " + actionResults.size() + " actions ...");
			try {
				// for(BaseRecord actionResult : actionResults) {
				for (int i = 0; i < actionResults.size(); i++) {
					BaseRecord actionResult = actionResults.get(i);
					processAction(actionResult);
				}
			} catch (OverwatchException e) {
				logger.error(e);
			}
		}
	}

	protected void processAction(BaseRecord actionResult) throws OverwatchException {
		// logger.info("Processing action " +
		// actionResult.get(OlioFieldNames.FIELD_ACTION_NAME));
		if (testForRollOut(actionResult)) {
			return;
		}
		try {
			IAction action = Actions.getActionProvider(context,
					(String) actionResult.get(OlioFieldNames.FIELD_ACTION_NAME2));
			if (action == null) {
				throw new OverwatchException("Invalid action");
			}

			BaseRecord actor = ActionUtil.getActionActor(actionResult);
			BaseRecord iactor = ActionUtil.getActionInteractor(actionResult);

			/// The action implementation will set the action progress; Overwatch will
			/// adjust any external clocks/events
			// long timeCost = action.calculateCostMS(context, actionResult, actor, iactor);
			// logger.info("Time Cost: " + timeCost + "ms");

			ZonedDateTime progress = actionResult.get(OlioFieldNames.FIELD_ACTION_PROGRESS);
			ZonedDateTime end = actionResult.get(OlioFieldNames.FIELD_ACTION_END);
			long remainingSeconds = progress.until(end, ChronoUnit.SECONDS);

			logger.info(actionResult.get(OlioFieldNames.FIELD_ACTION_NAME2) + " ("
					+ actionResult.get(FieldNames.FIELD_TYPE) + ") time remaining: "
					+ action.timeRemaining(actionResult, ChronoUnit.SECONDS) + " seconds");
			// logger.info("Overwatch progress remaining - " +
			// remainingActionProgress(actionResult) + "ms");
			if (!Actions.executeAction(context, actionResult)) {
				// logger.warn("Follow-up failed action: " +
				// (String)actionResult.get(OlioFieldNames.FIELD_ACTION_NAME));
			}

			Actions.concludeAction(context, actionResult, actor, iactor);

		} catch (OlioException e) {
			logger.error(e);
			throw new OverwatchException(e);
		}

	}

	public long remainingActionProgress(BaseRecord actionResult) {
		ZonedDateTime ep = actionResult.get(OlioFieldNames.FIELD_ACTION_PROGRESS);
		ZonedDateTime ee = context.clock().getCurrent();
		return ep.until(ee, ChronoUnit.MILLIS);
	}

	protected boolean testForRollOut(BaseRecord actionResult) {
		/// Check to see if there is another action for or involving this actor, and
		/// whether it should take priority over this action
		///
		return false;
	}

	protected void processGroup() {

	}

	protected void processProximity() {

	}

	protected void processTimedSchedules() {

	}

	protected void processEvents() {

	}
}
