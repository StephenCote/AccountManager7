package org.cote.accountmanager.olio.rules;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.GroupEnumType;

/**
 * Context rule for a PictureBook <b>book world</b>: a world with no map, no locations, no realms and
 * no population generation.
 * <p>
 * It exists because {@code OlioContext.initialize()} aborts when no context rule returns a root event
 * ("Failed to find or create a new region"), and because {@code Clock} wants a root event. So this
 * rule returns a real, minimal root event - a per-book time anchor - and creates the three
 * PictureBook groups ({@code Book}, {@code Workflow}, {@code Artifacts}) under the world's own
 * container. Those three are created here rather than in {@code WorldFactory} so game worlds do not
 * grow three groups they never use.
 * <p>
 * <b>Deliberately absent.</b> This rule must never call
 * {@code GeoLocationUtil.prepareMapGrid/checkK100/prepareK100/prepareCells/newLocation/createLocation/
 * randomLocation/getRegionLocations}, {@code CharacterUtil.populateRegion},
 * {@code RealmUtil.getCreateRealm}, {@code LocationPlannerRule} or
 * {@code RandomLocationInitializationRule}. The whole point of a book world is that none of that
 * happens. {@code pregenerate} being a no-op is precisely the difference from
 * {@code GridSquareLocationInitializationRule}.
 * <p>
 * Must be FIRST in {@code config.getContextRules()} - {@code initialize()} stops at the first rule
 * whose {@code generate()} returns non-null. Pair it with {@code GenericItemDataLoadRule} as the
 * second rule so actions/items/builders/animals still load (the apparel templates PictureBook needs
 * come from {@code BuilderUtil}).
 * <p>
 * <b>Known, accepted, and unresolved: a book world never gets an epoch.</b> The root event this rule
 * returns is a time ANCHOR, not an epoch. {@code EpochUtil.startEpoch} needs a root LOCATION, and a
 * book world deliberately has none, so every book create logs
 * {@code "Failed to find root location"} followed by {@code "Root Epoch is null"} at ERROR (currently
 * ~18 lines per {@code TestBookWorld} run). It is non-fatal - {@code requireRealms=false} keeps the
 * realm messages at INFO and nothing on the book path reads the clock - but the practical consequence
 * is that {@code ctx.clock()} is UNUSABLE for a book world.
 * <p>
 * Phase 2 design question, not a phase-1 bug: either book worlds get a location-free epoch, or the
 * epoch machinery becomes opt-in per world type, or {@code clock()} explicitly rejects book worlds.
 * Do not "fix" this by giving a book world a location - that would reintroduce exactly the map data
 * this rule exists to avoid.
 */
public class BookWorldInitializationRule extends CommonContextRule implements IOlioContextRule {
	public static final Logger logger = LogManager.getLogger(BookWorldInitializationRule.class);

	public static final String GROUP_BOOK = "Book";
	public static final String GROUP_WORKFLOW = "Workflow";
	public static final String GROUP_ARTIFACTS = "Artifacts";

	public BookWorldInitializationRule() {

	}

	/**
	 * No-op, by design. Explicitly does NOT prepare a map grid or a K100 - that is the entire
	 * difference from {@code GridSquareLocationInitializationRule.pregenerate}.
	 */
	@Override
	public void pregenerate(OlioContext context) {
		/// intentionally empty
	}

	@Override
	public BaseRecord generate(OlioContext ctx) {
		BaseRecord world = ctx.getWorld();
		if(world == null) {
			logger.error("World is null");
			return null;
		}

		/// Idempotency guard, same shape as GridSquareLocationInitializationRule
		BaseRecord root = EventUtil.getRootEvent(ctx);
		if(root != null) {
			return root;
		}

		String worldName = world.get(FieldNames.FIELD_NAME);
		long orgId = ctx.getOlioUser().get(FieldNames.FIELD_ORGANIZATION_ID);
		String containerPath = ctx.getConfig().getWorldPath() + "/" + worldName;
		String gtype = GroupEnumType.DATA.toString();

		for(String grp : new String[] {GROUP_BOOK, GROUP_WORKFLOW, GROUP_ARTIFACTS}) {
			BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(ctx.getOlioUser(), ModelNames.MODEL_GROUP, containerPath + "/" + grp, gtype, orgId);
			if(dir == null) {
				logger.error("Failed to create book group " + containerPath + "/" + grp);
				return null;
			}
		}

		BaseRecord eventsDir = world.get(OlioFieldNames.FIELD_EVENTS);
		if(eventsDir == null) {
			logger.error("World events group is null");
			return null;
		}

		try {
			ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_PATH, eventsDir.get(FieldNames.FIELD_PATH));
			root = IOSystem.getActiveContext().getFactory().newInstance(OlioModelNames.MODEL_EVENT, ctx.getOlioUser(), null, plist);
			root.set(FieldNames.FIELD_NAME, "Book " + worldName);
			root.set(FieldNames.FIELD_TYPE, EventEnumType.CONSTRUCT);
			root.set(OlioFieldNames.FIELD_EVENT_START, ctx.getConfig().getBaseInceptionDate());
			root.set(OlioFieldNames.FIELD_EVENT_PROGRESS, ctx.getConfig().getBaseInceptionDate());
			root.set(OlioFieldNames.FIELD_EVENT_END, ctx.getConfig().getBaseInceptionDate());
			/// No location and no realm: a book world has neither.
			if(!IOSystem.getActiveContext().getRecordUtil().updateRecord(root)) {
				logger.error("Failed to create book root event");
				return null;
			}
		}
		catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			return null;
		}

		return root;
	}

	/** No-op. GenericItemDataLoadRule does the corpus/template loading. */
	@Override
	public void postgenerate(OlioContext context) {
		/// intentionally empty
	}

	/** No-op - never called, because a book world has no realms. */
	@Override
	public void generateRegion(OlioContext context, BaseRecord realm) {
		/// intentionally empty
	}

	/**
	 * Empty, never null. {@code GridSquareLocationInitializationRule.generate} iterates every rule's
	 * {@code selectLocations} and treats a null/empty result as "try the next rule", so returning
	 * null here would be indistinguishable from "not implemented".
	 */
	@Override
	public BaseRecord[] selectLocations(OlioContext context) {
		return new BaseRecord[0];
	}
}
