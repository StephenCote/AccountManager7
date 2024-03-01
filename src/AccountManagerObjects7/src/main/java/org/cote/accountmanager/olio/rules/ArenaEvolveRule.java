package org.cote.accountmanager.olio.rules;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
import org.cote.accountmanager.olio.StateUtil;
import org.cote.accountmanager.olio.WearLevelEnumType;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.TimeEnumType;

public class ArenaEvolveRule implements IOlioEvolveRule {
	public static final Logger logger = LogManager.getLogger(ArenaEvolveRule.class);

	private static final SecureRandom random = new SecureRandom();

	@Override
	public void startEpoch(OlioContext context, BaseRecord epoch) {
		EventUtil.edgeTimes(epoch);
		
	}

	@Override
	public void continueEpoch(OlioContext context, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endEpoch(OlioContext context, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void startLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch) {
		EventUtil.edgeTimes(epoch);
		
	}

	@Override
	public void continueLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void endLocationEpoch(OlioContext context, BaseRecord location, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
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
		BaseRecord rec = EventUtil.getLastEvent(context.getUser(), context.getWorld(), locationEpoch.get("location"), EventEnumType.PERIOD, TimeEnumType.HOUR, ActionResultEnumType.PENDING, false); 
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
		
		outfitAndStage(context, acells.get(random.nextInt(acells.size())), party1);
		outfitAndStage(context, acells.get(random.nextInt(acells.size())), party2);
		context.processQueue();
	}

	private List<BaseRecord> randomArmor(OlioContext ctx) {
		List<BaseRecord> wears = new ArrayList<>();
		String[] protect = new String[] {"head", "chest", "neck", "upper arm", "forearm", "hand", "foot", "shin", "thigh", "elbow", "knee"};
		double[] protectOdds = new double[] {0.15, 0.25, 0.15, 0.15, 0.15, 0.25, 0.25, 0.15, 0.15, 0.10, 0.10};
		for(int i = 0; i < protect.length; i++) {
			String p = protect[i];
		
			if(random.nextDouble() <= protectOdds[i]) {
				BaseRecord wearRec = OlioUtil.newGroupRecord(ctx.getUser(), ModelNames.MODEL_WEARABLE, ctx.getWorld().get("wearables.path"), null);
				ApparelUtil.embedWearable(ctx, wearRec, ApparelUtil.randomWearable(WearLevelEnumType.OUTER, p, null));
				ApparelUtil.applyEmbeddedFabric(wearRec, ApparelUtil.randomFabric(WearLevelEnumType.OUTER, null));
				ApparelUtil.designWearable(ctx, wearRec);
				wears.add(wearRec);
			}
		}
		return wears;
	}
	private List<BaseRecord> itemTemplates = new ArrayList<>();
	private List<BaseRecord> getItemTemplates(OlioContext ctx){
		if(itemTemplates.size() == 0) {
			itemTemplates = ItemUtil.getTemplateItems(ctx);
		}
		return itemTemplates;
	}
	private String[] weaponFab = new String[] {"iron", "steel", "bronze", "stainless steel", "damascus steel"};
	private List<BaseRecord> randomArms(OlioContext ctx){
		List<BaseRecord> aweaps = new ArrayList<>();
		List<BaseRecord> weapL = getItemTemplates(ctx).stream().filter(r -> "weapon".equals(r.get("category"))).collect(Collectors.toList());
		BaseRecord weapT = weapL.get(random.nextInt(weapL.size()));
		BaseRecord weap = ItemUtil.buildItem(ctx, weapT);
		//ctx.queue(weap);
		ApparelUtil.applyFabric(weap, weaponFab[random.nextInt(weaponFab.length)]);
		aweaps.add(weap);
		
		// logger.info(weap.toFullString());
		if(!OlioUtil.isTagged(weap, "two-handed") && random.nextDouble() <= 0.25) {
			List<BaseRecord> armorL = getItemTemplates(ctx).stream().filter(r -> "armor".equals(r.get("category"))).collect(Collectors.toList());
			BaseRecord armorT = armorL.get(random.nextInt(armorL.size()));
			BaseRecord armor = ItemUtil.buildItem(ctx, armorT);
			ApparelUtil.applyFabric(armor, weaponFab[random.nextInt(weaponFab.length)]);	
			aweaps.add(armor);
			// logger.info(weap.toFullString());
		}
		// ItemUtil.addItemToInventory(ctx, weapT, weap);
		// ctx.queueUpdate(weap, weaponFab);
		/// ItemUtil.getCreateItemTemplate(ctx, animalParty1Name);
		return aweaps;
	}

	private void outfitAndStage(OlioContext ctx, BaseRecord cell, List<BaseRecord> party) {
		for(BaseRecord p: party) {
			BaseRecord sto = p.get("store");
			List<BaseRecord> appl = sto.get("apparel");
			List<BaseRecord> iteml = sto.get("items");
			if(appl.size() == 0) {
				BaseRecord app = ApparelUtil.randomApparel(ctx, p);
				List<BaseRecord> wears = app.get("wearables");
				wears.addAll(randomArmor(ctx));
				// ctx.queue(app);
				IOSystem.getActiveContext().getRecordUtil().createRecord(app);
				appl.add(app);
				ctx.queueUpdate(sto, new String[] {FieldNames.FIELD_ID, "apparel"});
			}
			if(iteml.size() == 0) {
				List<BaseRecord> arms = randomArms(ctx);
				for(BaseRecord a: arms) {
					IOSystem.getActiveContext().getRecordUtil().createRecord(a);
				}
				ctx.queueUpdate(sto, new String[] {FieldNames.FIELD_ID, "items"});
			}

			BaseRecord sta = p.get("state");
			if(sta.get("currentLocation") == null) {
				sta.setValue("currentLocation", cell);
				StateUtil.agitateLocation(ctx, sta);
				ctx.queueUpdate(sta, new String[] {FieldNames.FIELD_ID, "currentLocation", "currentEast", "currentNorth"});
			}
			// logger.info(p.toFullString());
		}
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
