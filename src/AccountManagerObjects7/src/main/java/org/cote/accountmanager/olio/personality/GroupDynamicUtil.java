package org.cote.accountmanager.olio.personality;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Queue;
import org.cote.accountmanager.olio.AlignmentEnumType;
import org.cote.accountmanager.olio.CharacterRoleEnumType;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.InteractionEnumType;
import org.cote.accountmanager.olio.InteractionUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.OutcomeEnumType;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.ReasonEnumType;
import org.cote.accountmanager.olio.RollEnumType;
import org.cote.accountmanager.olio.RollUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.ThreatEnumType;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.util.ComputeUtil;

public class GroupDynamicUtil {
	public static final Logger logger = LogManager.getLogger(GroupDynamicUtil.class);
	private static final SecureRandom random = new SecureRandom();
	
	private static final int partyMin = 3;
	private static final int partyMax = 10;
	private static final int minPartyAge = 14;
	private static final int maxPartyAge = 40;
	private static final int maxPartyCheck = 5;
	
	/*
	public static List<BaseRecord> randomParty(OlioContext ctx, BaseRecord locationEpoch){
		return randomParty(ctx, locationEpoch, new HashSet<>());
	}

	public static List<BaseRecord> randomParty(OlioContext ctx, BaseRecord locationEpoch, List<BaseRecord> base){
		return randomParty(ctx, locationEpoch, base.stream().map(r -> (long)r.get(FieldNames.FIELD_ID)).collect(Collectors.toSet()));
	}
	*/

	public static List<BaseRecord> randomParty(OlioContext ctx, BaseRecord realm, Set<Long> partSet){
		List<BaseRecord> party = new ArrayList<>();
		List<BaseRecord> lpop = ctx.getRealmPopulation(realm);
		int len = random.nextInt(partyMin, partyMax);
		for(int i = 0; i < len; i++) {
			BaseRecord per = lpop.get(random.nextInt(lpop.size()));
			long id = per.get(FieldNames.FIELD_ID);
			int age = per.get(FieldNames.FIELD_AGE);
			int check = 0;
			while(partSet.contains(id) || age < minPartyAge || age > maxPartyAge) {
				per = lpop.get(random.nextInt(lpop.size()));
				id = per.get(FieldNames.FIELD_ID);
				age = per.get(FieldNames.FIELD_AGE);
				check++;
				if(check > maxPartyCheck) {
					break;
				}
			}
			if(!partSet.contains(id)) {
				partSet.add(id);
				party.add(per);
			}
		}

		return party;
	}
	public static List<BaseRecord> getCreateParty(OlioContext ctx, BaseRecord realm){
		return getCreateParty(ctx, realm, realm.get(FieldNames.FIELD_NAME) + " Party", new HashSet<>());
	}
	
	public static List<BaseRecord> getCreateParty(OlioContext ctx, BaseRecord realm, String partyName, List<BaseRecord> base){
		return getCreateParty(ctx, realm, partyName, base.stream().map(r -> (long)r.get(FieldNames.FIELD_ID)).collect(Collectors.toSet()));
	}
	public static List<BaseRecord> getCreateParty(OlioContext ctx, BaseRecord realm, String partyName, Set<Long> partySet){
		List<BaseRecord> party = new ArrayList<>();

		BaseRecord grp = null;
		try {
			grp = OlioUtil.getCreatePopulationGroup(ctx, partyName);
			if(realm.get("principalGroup") == null) {
				realm.set("principalGroup", grp);
				Queue.queueUpdate(realm, new String[] {"principalGroup"});
			}
			party = OlioUtil.listGroupPopulation(ctx, grp);
			if(party.size() == 0) {
				List<BaseRecord> lpop = randomParty(ctx, realm, partySet);
				for(BaseRecord per : lpop) {
					if(!IOSystem.getActiveContext().getMemberUtil().member(ctx.getOlioUser(), grp, per, null, true)) {
						logger.error("Failed to add member");
					}
				}
				party = OlioUtil.listGroupPopulation(ctx, grp);
			}
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return party;
	}
	
	
	public static void delegateActions(OlioContext ctx, BaseRecord realm, Map<BaseRecord, PersonalityProfile> map, List<BaseRecord> actions) {

		PersonalityProfile leader = identifyLeader(ctx, realm, map);
	}
	
	public static OutcomeEnumType rulePersonalityConflict(BaseRecord interaction, PersonalityProfile prof1, PersonalityProfile prof2) {
		ReasonEnumType ret = interaction.getEnum(OlioFieldNames.FIELD_ACTOR_REASON);
		OutcomeEnumType oet = OutcomeEnumType.EQUILIBRIUM;
		switch(ret) {
			case ATTRACTIVE_NARCISSISM:
			case MACHIAVELLIANISM:
			case NARCISSISM:
			case SADISM:
			case PSYCHOPATHY:
				oet = DarkTetradUtil.ruleDarkTetrad(interaction, prof1, prof2);
				break;
			case MATURITY:
				oet = ruleMaturity(interaction, prof1, prof2);
				break;
			default:
				logger.warn("Unhandled reason: " + ret.toString());
				break;
		}
		return oet;
	}
	
	public static RollEnumType rollAgeMaturity(BaseRecord rec) {
		
		int age = rec.get(FieldNames.FIELD_AGE);
		int off = 0;
		if(age < Rules.MINIMUM_ADULT_AGE) off = Rules.MINIMUM_ADULT_AGE - age;
		else if(age >= Rules.SENIOR_AGE) off = age - Rules.SENIOR_AGE;
		if(off == 0) {
			return RollEnumType.SUCCESS;
		}
		int rollStat = ComputeUtil.getMaximumInt(rec.get(OlioFieldNames.FIELD_STATISTICS), new String[] {OlioFieldNames.FIELD_WISDOM, OlioFieldNames.FIELD_SPIRITUALITY}) - off;
		if(rollStat <= 0) {
			logger.info(rec.get(FieldNames.FIELD_FIRST_NAME) + " is not only too young, but not wise or spiritual enough to even be considered.  Who picked this person to begin with?  Sheesh.");
			return RollEnumType.CATASTROPHIC_FAILURE;
		}
		return RollUtil.rollStat20(rollStat);
	}
	
	public static OutcomeEnumType ruleMaturity(BaseRecord interaction, PersonalityProfile actor, PersonalityProfile interactor) {
		OutcomeEnumType actorOutcome = OutcomeEnumType.EQUILIBRIUM;
		OutcomeEnumType interactorOutcome = OutcomeEnumType.EQUILIBRIUM;
		
		boolean goFirst = false;
		/// If the person claiming maturity as an issue is also outside the mature  band, then roll on another skill to determine if they don't inadvertently wind up going first
		///
		if(actor.getAge() < Rules.MINIMUM_ADULT_AGE || actor.getAge() >= Rules.SENIOR_AGE) {
			RollEnumType retf = RollUtil.rollStat20(ComputeUtil.getMaximumInt(actor.getRecord().get(OlioFieldNames.FIELD_STATISTICS), new String[] {OlioFieldNames.FIELD_WISDOM, OlioFieldNames.FIELD_SPIRITUALITY}));
			goFirst = (retf == RollEnumType.FAILURE || retf == RollEnumType.CATASTROPHIC_FAILURE);
		}
		
		RollEnumType ret = rollAgeMaturity(actor.getRecord());
		RollEnumType iret = rollAgeMaturity(interactor.getRecord());
		if(goFirst) {
			actorOutcome = RollUtil.rollToOutcome(ret);
		}
		if(!goFirst || (actorOutcome == OutcomeEnumType.FAVORABLE || actorOutcome == OutcomeEnumType.VERY_FAVORABLE)) {
			interactorOutcome = RollUtil.rollToOutcome(iret);
		}
		else {
			interactorOutcome = OutcomeEnumType.FAVORABLE;
		}
		if(actorOutcome == OutcomeEnumType.EQUILIBRIUM) {
			if(interactorOutcome == OutcomeEnumType.FAVORABLE || interactorOutcome == OutcomeEnumType.VERY_FAVORABLE) {
				actorOutcome = OutcomeEnumType.UNFAVORABLE;
			}
			else {
				actorOutcome = OutcomeEnumType.FAVORABLE;
			}

		}
		try {
			if(interaction != null) {
				interaction.set("actorOutcome", actorOutcome);
				interaction.set("interactorOutcome", interactorOutcome);
			}
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return actorOutcome;
	}
	
	public static PersonalityProfile identifyLeader(OlioContext ctx, BaseRecord realm, Map<BaseRecord, PersonalityProfile> map) {
		List<PersonalityProfile> lgrp = new ArrayList<>();
		PersonalityProfile leader = null;
		if(map.keySet().size() > 1) {
			List<PersonalityProfile> natCommand = PersonalityUtil.filterCommanders(new ArrayList<>(map.values()));
			/// Find any 'directors'
			List<PersonalityProfile> natDir = PersonalityUtil.filterDirectors(new ArrayList<>(map.values()));
			if(natCommand.size() > 0) {
				logger.info(natCommand.size() + " people want to take charge");
				lgrp = natCommand;

			}
			else if(natDir.size() > 0) {
				logger.info(natDir.size() + " people step up to try to take lead");
				lgrp = natDir;
			}
			else {
				logger.info("Nobody stepped up to take charge");
				lgrp = new ArrayList<>(map.values());
			}

			leader = processLeadership(ctx, realm, map, lgrp);
			if(leader == null) {
				logger.error("Failed to identify a leader");
			}
			else {
				logger.info(leader.getName() + " emerged as the leader");
			}
		}
		return leader;
	}
	
	protected static PersonalityProfile processLeadership(OlioContext ctx, BaseRecord realm, Map<BaseRecord, PersonalityProfile> map, List<PersonalityProfile> lgrp) {
		PersonalityProfile outLead = PersonalityUtil.identifyLeaderPersonality(lgrp);
		if(lgrp.size() > 1) {
			List<BaseRecord> contesting = contestLeadership(ctx, realm, lgrp, outLead);
			if(contesting.size() > 0) {
				// logger.info(contesting.size() + " people are contesting leadership");
				for(BaseRecord c: contesting) {
					if(!map.containsKey(c.get(OlioFieldNames.FIELD_ACTOR)) || !map.containsKey(c.get(OlioFieldNames.FIELD_INTERACTOR))) {
						logger.warn("Not sure who is contesting!"); 
					}
					else {
						PersonalityProfile pp = map.get(c.get(OlioFieldNames.FIELD_ACTOR));
						PersonalityProfile pp2 = map.get(c.get(OlioFieldNames.FIELD_INTERACTOR));
						// logger.info(pp.getName() + " is contesting " + outLead.getName() + "'s leadership over " + c.get(OlioFieldNames.FIELD_ACTOR_REASON));
						OutcomeEnumType oet = rulePersonalityConflict(c, pp, pp2);
						if(oet == OutcomeEnumType.FAVORABLE || oet == OutcomeEnumType.VERY_FAVORABLE) {
							logger.info(pp.getName() + " has successfully contested " + outLead.getName() + "'s leadership over " + c.get(OlioFieldNames.FIELD_ACTOR_REASON));
							/// Remove the presumed leader from the possible group, and re-process leadership
							///
							long currId = outLead.getId();
							outLead = processLeadership(ctx, realm, map, lgrp.stream().filter(p -> p.getId() != currId).collect(Collectors.toList()));
							break;
						}
						else {
							logger.info(pp.getName() + " failed to contest " + outLead.getName() + "'s leadership over " + c.get(OlioFieldNames.FIELD_ACTOR_REASON));
						}
					}
				}
			}
		}
		return outLead;
	}
	
	public static BaseRecord evaluateContest(OlioContext ctx, PersonalityProfile challenger, PersonalityProfile defender) {
		BaseRecord evt = EventUtil.newEvent(ctx, ctx.clock().getIncrement(), EventEnumType.DESTABILIZE, challenger.getRecord().get(FieldNames.FIELD_FIRST_NAME) + " challenges " + defender.getRecord().get(FieldNames.FIELD_FIRST_NAME), ctx.clock().getIncrement().get("startTime"), new BaseRecord[] {challenger.getRecord(), defender.getRecord()}, null, null, true);
		return evt;
	}
	
	protected static BaseRecord newIntraGroupInteraction(OlioContext ctx, InteractionEnumType type, BaseRecord event, PersonalityProfile actor, ThreatEnumType actorThreat, ReasonEnumType actorReason, PersonalityProfile interactor, ThreatEnumType interactorThreat, ReasonEnumType interactorReason) {
		AlignmentEnumType eventAlign = AlignmentEnumType.NEUTRAL;
		AlignmentEnumType actorAlign = actor.getAlignment();
		AlignmentEnumType interactorAlign = interactor.getAlignment();

		if(event != null) {
			eventAlign = AlignmentEnumType.valueOf(event.get(FieldNames.FIELD_ALIGNMENT));
			actorAlign = AlignmentEnumType.margin(eventAlign, actor.getAlignment());
			interactorAlign = AlignmentEnumType.margin(eventAlign, interactor.getAlignment());
		}
		return InteractionUtil.newInteraction(ctx, type, event, actor.getRecord(), actorAlign, actorThreat, CharacterRoleEnumType.INDETERMINATE, actorReason, interactor.getRecord(), interactorAlign, interactorThreat, CharacterRoleEnumType.INDETERMINATE, interactorReason);
	}
	
	/// Given some leader, identify if the current group will accept them
	///
	public static List<BaseRecord> contestLeadership(OlioContext ctx, BaseRecord realm, List<PersonalityProfile> map, PersonalityProfile leader) {
		// Set<PersonalityProfile> contest = new HashSet<>();
		
		/// TODO: Differentiate between new leadership and existing leadership
		BaseRecord increment = null;
		if(realm != null) {
			realm.get(OlioFieldNames.FIELD_CURRENT_INCREMENT);
		}
		List<BaseRecord> interactions = new ArrayList<>();
		List<PersonalityProfile> primeAge = map.stream().filter(pp -> pp.getId() != leader.getId() && pp.getAge() >= Rules.MINIMUM_ADULT_AGE && pp.getAge() <= Rules.SENIOR_AGE).collect(Collectors.toList());
		List<PersonalityProfile> primeDipAge = primeAge.stream().filter(pp -> pp.getMbti().getGroup().equals("diplomat")).collect(Collectors.toList());
		if(
			primeDipAge.size() > 0
			&&
			(leader.getAge() < Rules.MINIMUM_ADULT_AGE || leader.getAge() >= Rules.SENIOR_AGE)
		) {
			logger.warn("One or more people are worried the leader is too young or too old");
			for(PersonalityProfile p: primeDipAge) {
				interactions.add(
					newIntraGroupInteraction(ctx, InteractionEnumType.COMPETE, increment, p, ThreatEnumType.IDEOLOGICAL_THREAT, ReasonEnumType.MATURITY, leader, ThreatEnumType.IDEOLOGICAL_TARGET, (leader.getAge() < Rules.MINIMUM_ADULT_AGE ? ReasonEnumType.IMMATURITY : ReasonEnumType.SENILITY))
				);
			}
			// contest.addAll(primeDipAge);
		}
		
		else if(primeAge.size() > 0 && leader.getAge() <= Rules.MAXIMUM_CHILD_AGE) {
			logger.warn("Do " + primeAge.size() + " adults really want a child as their leader?");
			for(PersonalityProfile p: primeDipAge) {
				interactions.add(
					newIntraGroupInteraction(ctx, InteractionEnumType.COMPETE, increment, p, ThreatEnumType.SOCIAL_THREAT, ReasonEnumType.MATURITY, leader, ThreatEnumType.SOCIAL_TARGET, ReasonEnumType.IMMATURITY)
				);
			}
		}
			
		List<PersonalityProfile> prettyNarcissists = PersonalityUtil.filterBetterLookingPrettyNarcissists(map, leader); 
		if(prettyNarcissists.size() > 0) {
			// contest.addAll(prettyNarcissists);
			for(PersonalityProfile p: prettyNarcissists) {
				interactions.add(
					newIntraGroupInteraction(ctx, InteractionEnumType.COMPETE, increment, p, ThreatEnumType.PSYCHOLOGICAL_THREAT, ReasonEnumType.ATTRACTIVE_NARCISSISM, leader, ThreatEnumType.PSYCHOLOGICAL_TARGET, ReasonEnumType.LESS_ATTRACTIVE)
				);
			}

		}

		List<PersonalityProfile> machiavellian = DarkTetradUtil.filterMachiavellianism(map, leader);
		if(machiavellian.size() > 0) {
			for(PersonalityProfile p: machiavellian) {
				interactions.add(
					newIntraGroupInteraction(ctx, InteractionEnumType.COMPETE, increment, p, ThreatEnumType.PSYCHOLOGICAL_THREAT, ReasonEnumType.MACHIAVELLIANISM, leader, ThreatEnumType.PSYCHOLOGICAL_TARGET, ReasonEnumType.UNKNOWN)
				);
			}

		}
		List<PersonalityProfile> psychopath = DarkTetradUtil.filterPsychopath(map, leader);
		if(psychopath.size() > 0 && !leader.isPsychopath()) {
			for(PersonalityProfile p: psychopath) {
				interactions.add(
					newIntraGroupInteraction(ctx, InteractionEnumType.CONFLICT, increment, p, ThreatEnumType.PSYCHOLOGICAL_THREAT, ReasonEnumType.PSYCHOPATHY, leader, ThreatEnumType.PSYCHOLOGICAL_TARGET, ReasonEnumType.SANITY)
				);
			}
		}
		return interactions;
		// return new ArrayList<>(contest);
	}
}
