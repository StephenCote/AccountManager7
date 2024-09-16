package org.cote.accountmanager.olio.actions;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.ItemUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.ActionResultEnumType;

public class Consume extends CommonAction implements IAction {
	public static final Logger logger = LogManager.getLogger(Consume.class);


	@Override
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		BaseRecord params = actionResult.get(FieldNames.FIELD_PARAMETERS);
		if(params == null) {
			throw new OlioException("Missing required parameters");
		}
		
		String itemName = params.get("itemName");
		if(itemName == null) {
			throw new OlioException("Item name required");
		}
		
		BaseRecord itemTempl = ItemUtil.getItemTemplate(context, itemName);
		if(itemTempl == null) {
			throw new OlioException("Item name refers to an invalid template");
		}
		
		int quantity = params.get(OlioFieldNames.FIELD_QUANTITY);
		if(quantity <= 0) {
			quantity = 1;
			params.setValue(OlioFieldNames.FIELD_QUANTITY, 1);
		}
		
		int minSeconds = actionResult.get(OlioFieldNames.FIELD_ACTION_MINIMUM_TIME);
		ActionUtil.edgeSecondsUntilEnd(actionResult, minSeconds);
		Queue.queueUpdate(actionResult, new String[]{OlioFieldNames.FIELD_ACTION_END});

		return actionResult;
	}
	
	@Override
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException {
		
		BaseRecord params = actionResult.get(FieldNames.FIELD_PARAMETERS);
		String itemName = params.get("itemName");
		String itemModel = params.get("itemModel");
		int quantity = params.get(OlioFieldNames.FIELD_QUANTITY);
		boolean transferred = false;
		
		/// does actor have item in inventory
		BaseRecord item = ItemUtil.findStoredItemByName(actor, itemName);
		ActionResultEnumType aet = ActionResultEnumType.FAILED;
		
		if(item != null) {
			/// Try to withdraw quantity item(s) from their inventory
			boolean withdraw = ItemUtil.withdrawItemFromInventory(context, actor, item, quantity);
			if(withdraw) {
				aet = ActionResultEnumType.SUCCEEDED;
			}
			else {
				logger.warn("Unable to withdraw item from inventory");
			}
		}
		else {
			logger.warn("Actor does not have item");
		}
		
		actionResult.setValue(FieldNames.FIELD_TYPE, aet);
		return transferred;
	}
	
	
}
