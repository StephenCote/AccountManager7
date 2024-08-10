package org.cote.accountmanager.olio.actions;

import java.util.List;

import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.ActionResultEnumType;
import org.cote.accountmanager.schema.type.EventEnumType;

public interface IAction {
	public EventEnumType getEventType();
	
	public long calculateCostMS(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException;
	public boolean executeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException;
	public boolean counterAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException;
	public ActionResultEnumType concludeAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException;
	public void configureAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException;
	public BaseRecord beginAction(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException;
	public List<BaseRecord> definePolicyFactParameters(OlioContext context, BaseRecord actionResult, BaseRecord actor, BaseRecord interactor) throws OlioException;
}
