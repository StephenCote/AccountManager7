package org.cote.accountmanager.olio.rules;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.ApparelUtil;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.GeoLocationUtil;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;

public class ArenaEvolveRule extends CommonEvolveRule implements IOlioEvolveRule {
	public static final Logger logger = LogManager.getLogger(ArenaEvolveRule.class);

	private static final SecureRandom random = new SecureRandom();

	@Override
	public void startLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch) {
		EventUtil.edgeTimes(epoch);
		
	}

	@Override
	public BaseRecord startIncrement(OlioContext context, BaseRecord locationEpoch) {
		BaseRecord rec = continueIncrement(context, locationEpoch);
		if(rec != null) {
			logger.warn("Returning current pending increment.");
			return rec;
		}
		return nextIncrement(context, locationEpoch);
	}

	@Override
	public BaseRecord continueIncrement(OlioContext context, BaseRecord locationEpoch) {
		BaseRecord rec = EventUtil.getLastEvent(context.getOlioUser(), context.getWorld(), locationEpoch.get("location"), EventEnumType.PERIOD, TimeEnumType.HOUR, ActionResultEnumType.PENDING, false); 
		if(rec != null) {
			return rec;
		}
		return null;
	}

	@Override
	public void endIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord currentIncrement) {
		BaseRecord rec = currentIncrement;
		if(currentIncrement == null) {
			rec = continueIncrement(context, locationEpoch);
		}
		if(rec == null) {
			logger.warn("Current increment was not found");
			return;
		}
		ActionResultEnumType aet = ActionResultEnumType.valueOf(rec.get(FieldNames.FIELD_STATE));
		if(aet != ActionResultEnumType.PENDING) {
			logger.error("Increment is not in a pending state");
			return;
		}
		try {
			rec.set(FieldNames.FIELD_STATE, ActionResultEnumType.COMPLETE);
			context.queue(rec.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_STATE}));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	private static final String party1Name = "Arena Party 1";
	private static final String party2Name = "Arena Party 2";
	private static final String animalParty1Name = "Animal Party 1";
	private static final String contest1Name = "Contest 1";

	
	@Override
	public void evaluateIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord increment) {
		// TODO Auto-generated method stub
		List<BaseRecord> party1 = GroupDynamicUtil.getCreateParty(context, locationEpoch, party1Name, new ArrayList<>());
		List<BaseRecord> party2 = GroupDynamicUtil.getCreateParty(context, locationEpoch, party2Name, party1);
		
		logger.info("Party: " + party1.size() + " - " + party2.size());
		//logger.info(increment.toString());

		BaseRecord field1 = ArenaInitializationRule.findLocation(context, "Field 1");
		List<BaseRecord> acells = GeoLocationUtil.getCells(context, field1);
		
		ApparelUtil.outfitAndStage(context, acells.get(random.nextInt(acells.size())), party1);
		ApparelUtil.outfitAndStage(context, acells.get(random.nextInt(acells.size())), party2);
		ItemUtil.showerWithMoney(context, party1);
		ItemUtil.showerWithMoney(context, party2);
		context.processQueue();
	}

	@Override
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent) {
		return EventUtil.findNextIncrement(context, parentEvent, TimeEnumType.HOUR);
	}

	@Override
	public void beginEvolution(OlioContext context) {
		// TODO Auto-generated method stub
		IOSystem.getActiveContext().getMemberUtil().deleteMembers(OlioUtil.getCreatePopulationGroup(context, party1Name), null);
		IOSystem.getActiveContext().getMemberUtil().deleteMembers(OlioUtil.getCreatePopulationGroup(context, party2Name), null);
		IOSystem.getActiveContext().getMemberUtil().deleteMembers(OlioUtil.getCreatePopulationGroup(context, animalParty1Name), null);
		
	}


}
