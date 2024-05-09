package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.personality.CompatibilityEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class InteractionUtil {
	public static final Logger logger = LogManager.getLogger(InteractionUtil.class);
	private static final SecureRandom rand = new SecureRandom();
	private static List<BaseRecord> interactionTemplates = new ArrayList<>();

	public static List<BaseRecord> getInteractionTemplates(){
		if(interactionTemplates.size() == 0) {
			interactionTemplates = JSONUtil.getList(ResourceUtil.getResource("olio/interactions.json"), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		}
		return interactionTemplates;
	}
	public static ThreatEnumType getThreatForInteraction(InteractionEnumType inter) {
		ThreatEnumType tet = ThreatEnumType.NONE;
		if(inter == InteractionEnumType.COERCE) {
			tet = ThreatEnumType.PSYCHOLOGICAL_THREAT;
		}
		else if(inter == InteractionEnumType.COMBAT || inter == InteractionEnumType.CONFLICT || inter == InteractionEnumType.THREATEN) {
			tet = ThreatEnumType.PHYSICAL_THREAT;
		}
		else if(inter == InteractionEnumType.CRITICIZE) {
			tet = ThreatEnumType.VERBAL_THREAT;
		}
		else if(inter == InteractionEnumType.PEER_PRESSURE) {
			tet = ThreatEnumType.SOCIAL_THREAT;
		}
		else if(inter == InteractionEnumType.OPPOSE) {
			tet = ThreatEnumType.IDEOLOGICAL_THREAT;
		}

		return tet;
	}
	/*
	public static ReasonEnumType guessReason(PersonalityProfile prof1, AlignmentEnumType align, InteractionEnumType interType, CharacterRoleEnumType role, PersonalityProfile prof2) {
		ReasonEnumType ret = ReasonEnumType.UNKNOWN;
		ProfileComparison pcomp = new ProfileComparison(prof1, prof2);
		CompatibilityEnumType cet = pcomp.getCompatibility();
		boolean ageIssue = pcomp.doesAgeCrossBoundary();
		VeryEnumType isMach = prof1.getMachiavellian();
		VeryEnumType isPsych = prof1.getPsychopath();
		VeryEnumType isNarc = prof1.getNarcissist();
		HighEnumType prettier = pcomp.getCharismaMargin();

		int alignVal = AlignmentEnumType.getValue(align);
		/// Given the current forces affecting alignment, are they still leaning towards the good
		///
		boolean leaningGood = AlignmentEnumType.compare(align, AlignmentEnumType.NEUTRAL, ComparatorEnumType.GREATER_THAN);
		int iqual = InteractionEnumType.getCompare(interType);
		int rqual = CharacterRoleEnumType.getCompare(role);
		
		/// Negative forces abound for a bad action
		List<ReasonEnumType> possibleReasons = new ArrayList<>();
		StringBuilder claim = new StringBuilder();
		
		/// Doing a bad thing
		if(iqual < 0) {
			
		}
		
		if(alignVal < 0 && iqual < 0) {
			/// by a person being bad
			claim.append("Negative forces contribute to a bad action ");
			if(rqual < 0) {
				claim.append("by a person trying to be bad");
				possibleReasons.addAll(ReasonEnumType.getNegativeReasons());
				//ret = ReasonEnumType.getNegativeReasons().get(rand.nextInt(ReasonEnumType.getNegativeReasons().size()));
			}
			else if(rqual == 0) {
				claim.append("by a person being indifferent");
				possibleReasons.addAll(ReasonEnumType.getNeutralReasons());
			}
			else {
				claim.append("by a person trying to be good");
				possibleReasons.addAll(ReasonEnumType.getPositiveReasons());
			}
			
		}
		/// Positive or neutral forces lead to a bad action by a positive or negative role
		if(iqual < 0) {
			claim.append("Despite positive forces, a bad action is taken");
			if(rqual > 0) {
				claim.append("by a person trying to be good");
			}
			else if(rqual == 0) {
				claim.append("by a person being indifferent");
			}

		}
		if(possibleReasons.size() > 0) {
			ret = possibleReasons.get(rand.nextInt(possibleReasons.size()));
		}

		
		logger.info(claim.toString() + " - " + cet.toString() + " " + align.toString() + " " + interType.toString() + " " + role.toString() + " " + ret.toString());
		
		
		return ret;
	}
	*/
	
	public static ReasonEnumType guessReasonXXX(OlioContext ctx, PersonalityProfile prof1, AlignmentEnumType align, InteractionEnumType interType, CharacterRoleEnumType role, PersonalityProfile prof2) {
		ReasonEnumType ret = ReasonEnumType.UNKNOWN;
		ProfileComparison pcomp = new ProfileComparison(ctx, prof1, prof2);
		CompatibilityEnumType cet = pcomp.getCompatibility();
		boolean ageIssue = pcomp.doesAgeCrossBoundary();
		VeryEnumType isMach = prof1.getMachiavellian();
		VeryEnumType isPsych = prof1.getPsychopath();
		VeryEnumType isNarc = prof1.getNarcissist();
		HighEnumType prettier = pcomp.getCharismaMargin();
		
		int alignVal = AlignmentEnumType.getValue(align);
		/// Given the current forces affecting alignment, are they still leaning towards the good
		///
		boolean leaningGood = AlignmentEnumType.compare(align, AlignmentEnumType.NEUTRAL, ComparatorEnumType.GREATER_THAN);
		int iqual = InteractionEnumType.getCompare(interType);
		int rqual = CharacterRoleEnumType.getCompare(role);
		
		/// Negative forces abound for a bad action
		List<ReasonEnumType> possibleReasons = new ArrayList<>();
		StringBuilder claim = new StringBuilder();
		
		/// Doing a bad thing
		if(iqual < 0) {
			
		}
		
		if(alignVal < 0 && iqual < 0) {
			/// by a person being bad
			claim.append("Negative forces contribute to a bad action ");
			if(rqual < 0) {
				claim.append("by a person trying to be bad");
				possibleReasons.addAll(ReasonEnumType.getNegativeReasons());
				//ret = ReasonEnumType.getNegativeReasons().get(rand.nextInt(ReasonEnumType.getNegativeReasons().size()));
			}
			else if(rqual == 0) {
				claim.append("by a person being indifferent");
				possibleReasons.addAll(ReasonEnumType.getNeutralReasons());
			}
			else {
				claim.append("by a person trying to be good");
				possibleReasons.addAll(ReasonEnumType.getPositiveReasons());
			}
			
		}
		/// Positive or neutral forces lead to a bad action by a positive or negative role
		if(iqual < 0) {
			claim.append("Despite positive forces, a bad action is taken");
			if(rqual > 0) {
				claim.append("by a person trying to be good");
			}
			else if(rqual == 0) {
				claim.append("by a person being indifferent");
			}

		}
		if(possibleReasons.size() > 0) {
			ret = possibleReasons.get(rand.nextInt(possibleReasons.size()));
		}

		
		logger.info(claim.toString() + " - " + cet.toString() + " " + align.toString() + " " + interType.toString() + " " + role.toString() + " " + ret.toString());
		
		
		return ret;
	}
	
	public static List<InteractionEnumType> getInteractionsByAlignment(AlignmentEnumType align) {
		List<InteractionEnumType> inters = new ArrayList<>();
		if(AlignmentEnumType.compare(align, AlignmentEnumType.CHAOTICNEUTRAL, ComparatorEnumType.LESS_THAN)) {
			inters = InteractionEnumType.getNegativeInteractions();
		}
		else if(AlignmentEnumType.compare(align, AlignmentEnumType.CHAOTICGOOD, ComparatorEnumType.LESS_THAN)) {
			inters = InteractionEnumType.getNeutralInteractions();
		}
		else inters = InteractionEnumType.getPositiveInteractions();
		return inters;
	}
	
	public static List<ReasonEnumType> getReasonsByAlignment(AlignmentEnumType align) {
		List<ReasonEnumType> reas = new ArrayList<>();
		if(AlignmentEnumType.compare(align, AlignmentEnumType.CHAOTICNEUTRAL, ComparatorEnumType.LESS_THAN)) {
			reas = ReasonEnumType.getNegativeReasons();
		}
		else if(AlignmentEnumType.compare(align, AlignmentEnumType.CHAOTICGOOD, ComparatorEnumType.LESS_THAN)) {
			reas = ReasonEnumType.getNeutralReasons();
		}
		else reas = ReasonEnumType.getPositiveReasons();
		return reas;
	}

	public static CharacterRoleEnumType getCharacterRoleByAlignment(AlignmentEnumType align, String gender) {
		CharacterRoleEnumType actorRole = CharacterRoleEnumType.UNKNOWN;
		List<CharacterRoleEnumType> actorRoles = getCharacterRolesByAlignment(align,gender);
		if(actorRoles.size() > 0) {
			actorRole = actorRoles.get(rand.nextInt(actorRoles.size()));
		}
		return actorRole;
	}
	public static List<CharacterRoleEnumType> getCharacterRolesByAlignment(AlignmentEnumType align, String gender) {
		List<CharacterRoleEnumType> reas = new ArrayList<>();
		if(AlignmentEnumType.compare(align, AlignmentEnumType.CHAOTICNEUTRAL, ComparatorEnumType.LESS_THAN)) {
			reas.addAll(CharacterRoleEnumType.getNegativeRoles());
		}
		else if(AlignmentEnumType.compare(align, AlignmentEnumType.CHAOTICGOOD, ComparatorEnumType.LESS_THAN)) {
			reas.addAll(CharacterRoleEnumType.getNeutralRoles());
		}
		else reas.addAll(CharacterRoleEnumType.getPositiveRoles());
		if(gender.equals("male")) {
			reas.remove(CharacterRoleEnumType.TEMPTRESS);
		}
		reas.remove(CharacterRoleEnumType.UNKNOWN);
		return reas;
	}
	
	protected static List<BaseRecord> filterInteractionTemplates(AlignmentEnumType actorAlignment, ReasonEnumType actorReason, AlignmentEnumType interactorAlignment, ReasonEnumType interactorReason){
		String alignStr = actorAlignment.toString().toLowerCase();
		String interalignStr = interactorAlignment.toString().toLowerCase();
		String reasStr = actorReason.toString().toLowerCase();
		String interreasStr = interactorReason.toString().toLowerCase();
		return getInteractionTemplates().stream().filter(i -> {
			List<String> aligns = i.get("actorAlignmentSuggestion");
			List<String> interaligns = i.get("interactorAlignmentSuggestion");
			List<String> reas = i.get("actorReasonSuggestion");
			List<String> interreas = i.get("interactorReasonSuggestion");

			return (
				(actorAlignment == AlignmentEnumType.UNKNOWN || aligns.contains(alignStr))
				&&
				(interactorAlignment == AlignmentEnumType.UNKNOWN || interaligns.contains(interalignStr))
				&&
				(actorReason == ReasonEnumType.UNKNOWN || reas.contains(reasStr))
				&&
				(interactorReason == ReasonEnumType.UNKNOWN || interreas.contains(interreasStr))

			);
		}).collect(Collectors.toList());
	}
	
	/// Given two random people, guess a reason for them to interact based on their profiles
	/// It's possible there's no good reason, captured as type.NONE
	///
	public static ReasonToDo guessReasonToInteract(OlioContext ctx, PersonalityProfile prof1, AlignmentEnumType contextAlign, PersonalityProfile prof2) {
		if(prof1.getId() == prof2.getId()) {
			logger.error("Profiles are the same");
			return null;
		}
		ReasonToDo rtd = new ReasonToDo();

		ProfileComparison pcomp = new ProfileComparison(ctx, prof1, prof2);
		CompatibilityEnumType rcet = pcomp.getRomanticCompatibility();

		AlignmentEnumType actorAlign = AlignmentEnumType.margin(contextAlign, prof1.getAlignment());
		AlignmentEnumType interactorAlign = AlignmentEnumType.margin(contextAlign, prof2.getAlignment());
		
		boolean leaningGood = AlignmentEnumType.compare(actorAlign, AlignmentEnumType.NEUTRAL, ComparatorEnumType.GREATER_THAN);
		List<ReasonEnumType> reasons = getReasonsByAlignment(actorAlign);
		List<ReasonEnumType> interactorReasons = getReasonsByAlignment(interactorAlign);
		List<BaseRecord> inters = filterInteractionTemplates(actorAlign, ReasonEnumType.UNKNOWN, interactorAlign, ReasonEnumType.UNKNOWN);
		if(inters.size() == 0) {
			logger.error("Failed to find a reasonable interaction match");
			return null;
		}
		BaseRecord inter = inters.get(rand.nextInt(inters.size()));
		logger.info("Interaction: " + inter.get("type"));
		
		List<String> baseActorReasons = inter.get("actorReasonSuggestion");
		List<ReasonEnumType> actorReasons = new ArrayList<>();
		
		String ainst = inter.get("actorInstinct");
		for(String s: baseActorReasons) {
			ReasonEnumType are = ReasonEnumType.valueOf(s.toUpperCase());
			if(reasons.contains(are)) {
				if(
					(
						are != ReasonEnumType.INTIMACY
						&& are != ReasonEnumType.ATTRACTION 
						&& are != ReasonEnumType.SENSUALITY
						&& (are != ReasonEnumType.INSTINCT || "mate".equals(ainst))
					)
					|| CompatibilityEnumType.compare(rcet, CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)
				){
					actorReasons.add(are);
				}
				else {
					// logger.info("Skip reason: " + are.toString());
				}
			}
		}
		ReasonEnumType actorReason = inter.getEnum("actorReason");
		if(actorReasons.size() == 0 && actorReason != ReasonEnumType.UNKNOWN) {
			logger.warn("Failed to identify any possible reasons");
			return null;
		}
		if(actorReason == ReasonEnumType.UNKNOWN) {
			actorReason = actorReasons.get(rand.nextInt(actorReasons.size()));
		}
		
		
		List<String> baseInteractorReasons = inter.get("interactorReasonSuggestion");
		List<ReasonEnumType> iactorReasons = new ArrayList<>();
		
		String iinst = inter.get("interactorInstinct");
		for(String s: baseInteractorReasons) {
			ReasonEnumType are = ReasonEnumType.valueOf(s.toUpperCase());
			if(interactorReasons.contains(are)) {
				if(
					(
						are != ReasonEnumType.INTIMACY
						&& are != ReasonEnumType.ATTRACTION 
						&& are != ReasonEnumType.SENSUALITY
						&& (are != ReasonEnumType.INSTINCT || "mate".equals(iinst))
					)
					|| CompatibilityEnumType.compare(rcet, CompatibilityEnumType.NOT_IDEAL, ComparatorEnumType.GREATER_THAN_OR_EQUALS)
				){
					iactorReasons.add(are);
				}
			}
		}
		
		ReasonEnumType interactorReason = inter.getEnum("interactorReason");
		if(iactorReasons.size() > 0) {
			interactorReason = iactorReasons.get(rand.nextInt(iactorReasons.size()));
		}
		
		
		/// Figure out what kind of threat, if any, the actor may represent 
		ThreatEnumType actorThreat = ThreatEnumType.NONE;
		if(!leaningGood) {
			logger.info(prof1.getName() + " is not leaning to the good, and therefore may represent a threat");
			List<ThreatEnumType> tets = new ArrayList<>();
			List<String> baseActorThreats = inter.get("actorThreatSuggestion");
			for(String s: baseActorThreats) {
				ThreatEnumType tet = ThreatEnumType.valueOf(s.toUpperCase());
				if(
					tet != ThreatEnumType.ANIMAL_THREAT
					&&
					(!prof1.isMachiavellian() || tet == ThreatEnumType.POLITICAL_THREAT)
					&&
					((!prof1.isPsychopath() && !prof1.isMachiavellian()) || tet == ThreatEnumType.PSYCHOLOGICAL_THREAT)
					&&
					(!HighEnumType.compare(prof1.getPhysicalStrength(), HighEnumType.DIMINISHED, ComparatorEnumType.LESS_THAN_OR_EQUALS) || tet == ThreatEnumType.PHYSICAL_THREAT)
				) {
					tets.add(tet);
				}
			}
			if(tets.size() > 0) {
				actorThreat = tets.get(rand.nextInt(tets.size()));
			}
		}
		
		rtd.setReason(actorReason);
		rtd.setInteraction(InteractionEnumType.valueOf(((String)inter.get("type")).toUpperCase()));
		rtd.setThreat(actorThreat);
		rtd.setRole(getCharacterRoleByAlignment(actorAlign, prof1.getGender()));
		rtd.setAlignment(actorAlign);
		
		rtd.setInteractorAlignment(interactorAlign);
		rtd.setInteractorRole(getCharacterRoleByAlignment(interactorAlign, prof2.getGender()));
		rtd.setInteractorReason(interactorReason);
		rtd.setInteractorThreat(ThreatEnumType.getTarget(actorThreat));
		
		/// logger.info(pcomp.compare());
		
		return rtd;
	}
	
	
	public static BaseRecord randomInteraction(OlioContext ctx, BaseRecord per1, BaseRecord per2) {

		PersonalityProfile prof1 = ProfileUtil.getProfile(ctx, per1);
		PersonalityProfile prof2 = ProfileUtil.getProfile(ctx, per2);
		if(ProfileUtil.sameProfile(prof1, prof2)) {
			logger.warn("Same profile");
			return null;
		}
		
		AlignmentEnumType interAlign = OlioUtil.getRandomAlignment();
		// AlignmentEnumType actorAlign = AlignmentEnumType.margin(interAlign, per1.getEnum("alignment"));
		// AlignmentEnumType interactorAlign = AlignmentEnumType.margin(interAlign, per2.getEnum("alignment"));
		// InteractionEnumType interType = OlioUtil.getRandomInteraction();
		// CharacterRoleEnumType actorRole = OlioUtil.getRandomCharacterRole(per1.get("gender"));
		//ReasonEnumType actorReason = guessReason(prof1, actorAlign, interType, actorRole, prof2);

		ReasonToDo rtd = guessReasonToInteract(ctx, prof1, interAlign, prof2);
		if(rtd == null) {
			logger.warn("Could not find a reason to interact");
			return null;
		}
		// ThreatEnumType threat = getThreatForInteraction(interType);
		// ThreatEnumType athreat = ThreatEnumType.getTarget(rtd.getThreat());
		BaseRecord inter = InteractionUtil.newInteraction(
			ctx,
			rtd.getInteraction(),
			null,
			per1,
			rtd.getAlignment(),
			rtd.getThreat(),
			rtd.getRole(),
			rtd.getReason(), 
			per2,
			rtd.getInteractorAlignment(),
			rtd.getInteractorThreat(),
			rtd.getInteractorRole(),
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

class ReasonToDo{
	private InteractionEnumType interaction = InteractionEnumType.NONE;
	private AlignmentEnumType alignment = AlignmentEnumType.NEUTRAL;
	private ReasonEnumType reason = ReasonEnumType.NONE;
	private CharacterRoleEnumType role = CharacterRoleEnumType.UNKNOWN;
	private ThreatEnumType threat = ThreatEnumType.NONE;

	private AlignmentEnumType interactorAlignment = AlignmentEnumType.NEUTRAL;
	private ReasonEnumType interactorReason = ReasonEnumType.NONE;
	private CharacterRoleEnumType interactorRole = CharacterRoleEnumType.UNKNOWN;
	private ThreatEnumType interactorThreat = ThreatEnumType.NONE;

	
	public ReasonToDo() {
		
	}
	
	public AlignmentEnumType getInteractorAlignment() {
		return interactorAlignment;
	}

	public void setInteractorAlignment(AlignmentEnumType interactorAlignment) {
		this.interactorAlignment = interactorAlignment;
	}

	public ReasonEnumType getInteractorReason() {
		return interactorReason;
	}

	public void setInteractorReason(ReasonEnumType interactorReason) {
		this.interactorReason = interactorReason;
	}

	public CharacterRoleEnumType getInteractorRole() {
		return interactorRole;
	}

	public void setInteractorRole(CharacterRoleEnumType interactorRole) {
		this.interactorRole = interactorRole;
	}

	public ThreatEnumType getInteractorThreat() {
		return interactorThreat;
	}

	public void setInteractorThreat(ThreatEnumType interactorThreat) {
		this.interactorThreat = interactorThreat;
	}

	public AlignmentEnumType getAlignment() {
		return alignment;
	}

	public void setAlignment(AlignmentEnumType alignment) {
		this.alignment = alignment;
	}

	public ThreatEnumType getThreat() {
		return threat;
	}

	public void setThreat(ThreatEnumType threat) {
		this.threat = threat;
	}

	public CharacterRoleEnumType getRole() {
		return role;
	}

	public void setRole(CharacterRoleEnumType role) {
		this.role = role;
	}

	public InteractionEnumType getInteraction() {
		return interaction;
	}
	public void setInteraction(InteractionEnumType interaction) {
		this.interaction = interaction;
	}
	public ReasonEnumType getReason() {
		return reason;
	}
	public void setReason(ReasonEnumType reason) {
		this.reason = reason;
	}
	
}
