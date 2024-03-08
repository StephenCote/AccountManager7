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
import org.cote.accountmanager.olio.InteractionEnumType;

public class InteractionUtil {
	public static final Logger logger = LogManager.getLogger(InteractionUtil.class);

	
	public static BaseRecord randomInteraction(OlioContext ctx, BaseRecord per1, BaseRecord per2) {
		BaseRecord inter = InteractionUtil.newInteraction(
			ctx,
			OlioUtil.getRandomInteraction(),
			null,
			per1,
			OlioUtil.getRandomAlignment(),
			OlioUtil.getRandomPersonThreat(),
			OlioUtil.getRandomCharacterRole(per1.get("gender")),
			OlioUtil.getRandomReason(), 
			per2,
			OlioUtil.getRandomAlignment(),
			OlioUtil.getRandomPersonThreat(),
			OlioUtil.getRandomCharacterRole(per2.get("gender")),
			OlioUtil.getRandomReason()
		);
		inter.setValue("actorOutcome", OlioUtil.getRandomOutcome());
		inter.setValue("interactorOutcome", OlioUtil.getRandomOutcome());
		return inter;
	}
	
	public static BaseRecord newInteraction(OlioContext ctx, InteractionEnumType type, BaseRecord event, BaseRecord actor, ThreatEnumType actorThreat, BaseRecord interactor) {
		return newInteraction(ctx, type, event, actor, AlignmentEnumType.UNKNOWN, actorThreat, CharacterRoleEnumType.UNKNOWN, ReasonEnumType.UNKNOWN, interactor, AlignmentEnumType.UNKNOWN, ThreatEnumType.NONE, CharacterRoleEnumType.UNKNOWN, ReasonEnumType.UNKNOWN);
	}

	
	public static BaseRecord newInteraction(OlioContext ctx, InteractionEnumType type, BaseRecord event, BaseRecord actor, ThreatEnumType actorThreat, BaseRecord interactor, ThreatEnumType interactorThreat) {
		return newInteraction(ctx, type, event, actor, AlignmentEnumType.UNKNOWN, actorThreat, CharacterRoleEnumType.UNKNOWN, ReasonEnumType.UNKNOWN, interactor, AlignmentEnumType.UNKNOWN, interactorThreat, CharacterRoleEnumType.UNKNOWN, ReasonEnumType.UNKNOWN);
	}
	
	public static BaseRecord newInteraction(
		OlioContext ctx,
		InteractionEnumType type,
		BaseRecord event,
		BaseRecord actor,
		AlignmentEnumType actorAlignment,
		ThreatEnumType actorThreat,
		CharacterRoleEnumType actorRole,
		ReasonEnumType actorReason,
		BaseRecord interactor,
		AlignmentEnumType interactorAlignment,
		ThreatEnumType interactorThreat,
		CharacterRoleEnumType interactorRole,
		ReasonEnumType interactorReason
	) {
		BaseRecord inter = null;
	
		ParameterList plist = ParameterList.newParameterList("path", ctx.getWorld().get("interactions.path"));
		try {
			inter = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_INTERACTION, ctx.getUser(), null, plist);
			if(event != null) {
				inter.set("interactionStart", event.get("eventStart"));
				inter.set("interactionEnd", event.get("eventEnd"));
			}
			inter.set("type", type);
			inter.set("actor", actor);
			inter.set("actorAlignment", actorAlignment);
			inter.set("actorType", actor.getModel());
			inter.set("actorThreat", actorThreat);
			inter.set("actorRole", actorRole);
			inter.set("actorReason", actorReason);
			inter.set("interactor", interactor);
			inter.set("interactorAlignment", interactorAlignment);
			inter.set("interactorType", interactor.getModel());
			inter.set("interactorThreat", interactorThreat);
			inter.set("interactorRole", interactorRole);
			inter.set("interactorReason", interactorReason);

		}
		catch(ModelNotFoundException | FactoryException | FieldException | ValueException e) {
			logger.error(e);
		}
		return inter;
	}
	
}
