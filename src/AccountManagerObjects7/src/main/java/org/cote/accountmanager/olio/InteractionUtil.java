package org.cote.accountmanager.olio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.ModelNames;

public class InteractionUtil {
	public static final Logger logger = LogManager.getLogger(InteractionUtil.class);

	
	public static BaseRecord newInteraction(OlioContext ctx, BaseRecord event, BaseRecord actor, ThreatEnumType actorThreat, BaseRecord interactor) {
		return newInteraction(ctx, event, actor, AlignmentEnumType.UNKNOWN, actorThreat, interactor, AlignmentEnumType.UNKNOWN, ThreatEnumType.NONE);
	}

	
	public static BaseRecord newInteraction(OlioContext ctx, BaseRecord event, BaseRecord actor, ThreatEnumType actorThreat, BaseRecord interactor, ThreatEnumType interactorThreat) {
		return newInteraction(ctx, event, actor, AlignmentEnumType.UNKNOWN, actorThreat, interactor, AlignmentEnumType.UNKNOWN, interactorThreat);
	}
	
	public static BaseRecord newInteraction(OlioContext ctx, BaseRecord event, BaseRecord actor, AlignmentEnumType actorAlignment, ThreatEnumType actorThreat, BaseRecord interactor, AlignmentEnumType interactorAlignment, ThreatEnumType interactorThreat) {
		BaseRecord inter = null;
	
		ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("interactions.path"));
		try {
			inter = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_INTERACTION, ctx.getUser(), null, plist);
			if(event != null) {
				inter.set("interactionStart", event.get("eventStart"));
				inter.set("interactionEnd", event.get("eventEnd"));
			}
			inter.set("actor", actor);
			inter.set("actorAlignment", actorAlignment);
			inter.set("actorType", actor.getModel());
			inter.set("actorThreat", actorThreat);
			inter.set("interactor", interactor);
			inter.set("interactorAlignment", interactorAlignment);
			inter.set("interactorType", interactor.getModel());
			inter.set("interActorThreat", interactorThreat);
		}
		catch(ModelNotFoundException | FactoryException | FieldException | ValueException e) {
			logger.error(e);
		}
		return inter;
	}
	
}
