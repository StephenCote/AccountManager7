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
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.EventUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.EventEnumType;

public class GroupDynamicUtil {
	public static final Logger logger = LogManager.getLogger(GroupDynamicUtil.class);
	private static final SecureRandom random = new SecureRandom();
	
	private static final int partyMin = 3;
	private static final int partyMax = 10;
	private static final int minPartyAge = 14;
	private static final int maxPartyAge = 40;
	private static final int maxPartyCheck = 5;
	
	public static List<BaseRecord> getCreateParty(OlioContext ctx, BaseRecord locationEpoch){
		List<BaseRecord> party = new ArrayList<>();
		BaseRecord loc = locationEpoch.get("location");
		IOSystem.getActiveContext().getReader().populate(loc, new String[] { FieldNames.FIELD_NAME });
		String partyName = loc.get(FieldNames.FIELD_NAME) + " Party";
		BaseRecord grp = null;
		try {
			BaseRecord realm = ctx.getRealm(locationEpoch.get("location"));
			grp = OlioUtil.getCreatePopulationGroup(ctx, partyName);
			if(realm.get("principalGroup") == null) {
				realm.set("principalGroup", grp);
				IOSystem.getActiveContext().getRecordUtil().updateRecord(realm.copyRecord(new String[] {FieldNames.FIELD_ID, "principalGroup", FieldNames.FIELD_ORGANIZATION_ID}));
			}
			party = OlioUtil.listGroupPopulation(ctx, grp);
			if(party.size() == 0) {
				List<BaseRecord> lpop = ctx.getPopulation(loc);
				int len = random.nextInt(partyMin, partyMax);
				Set<Long> partSet = new HashSet<>();
				logger.info("Creating a party of " + len + " from " + lpop.size());
				for(int i = 0; i < len; i++) {
					BaseRecord per = lpop.get(random.nextInt(lpop.size()));
					long id = per.get(FieldNames.FIELD_ID);
					int age = per.get("age");
					int check = 0;
					while(partSet.contains(id) || age < minPartyAge || age > maxPartyAge) {
						per = lpop.get(random.nextInt(lpop.size()));
						id = per.get(FieldNames.FIELD_ID);
						age = per.get("age");
						check++;
						if(check > maxPartyCheck) {
							break;
						}
					}
					if(!partSet.contains(id)) {
						partSet.add(id);
						if(!IOSystem.getActiveContext().getMemberUtil().member(ctx.getUser(), grp, per, null, true)) {
							logger.error("Failed to add member");
						}
					}
				}
				party = OlioUtil.listGroupPopulation(ctx, grp);
			}
			logger.info(partyName + " size = " + party.size());
			
		} catch (FieldException | ValueException | ModelNotFoundException | ReaderException e) {
			logger.error(e);
		}
		return party;
	}
	
	public static void delegateActions(OlioContext ctx, Map<BaseRecord, PersonalityProfile> map, List<BaseRecord> actions) {
		/// Given a set of actions
		/// Find any leaders, and figure out any contests
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
			leader = PersonalityUtil.identifyLeader(lgrp);
			if(lgrp.size() > 1) {
				List<PersonalityProfile> contesting = GroupDynamicUtil.contestLeadership(lgrp, leader);
				if(contesting.size() > 0) {
					logger.info(contesting.size() + " people are contesting leadership");
					for(PersonalityProfile c: contesting) {
						
					}
				}
			}
		}
	}
	
	public static BaseRecord evaluateContest(OlioContext ctx, PersonalityProfile challenger, PersonalityProfile defender) {
		BaseRecord evt = EventUtil.newEvent(ctx, ctx.getCurrentIncrement(), EventEnumType.DESTABILIZE, challenger.getRecord().get("firstName") + " challenges " + defender.getRecord().get("firstName"), ctx.getCurrentIncrement().get("startTime"), new BaseRecord[] {challenger.getRecord(), defender.getRecord()}, null, null, true);
		return evt;
	}
	
	/// Given some leader, identify if the current group will accept them
	///
	public static List<PersonalityProfile> contestLeadership(List<PersonalityProfile> map, PersonalityProfile leader) {
		Set<PersonalityProfile> contest = new HashSet<>();
		
		List<PersonalityProfile> primeAge = map.stream().filter(pp -> pp.getId() != leader.getId() && pp.getAge() >= Rules.MINIMUM_ADULT_AGE && pp.getAge() <= Rules.SENIOR_AGE).collect(Collectors.toList());
		List<PersonalityProfile> primeDipAge = primeAge.stream().filter(pp -> pp.getMbti().getGroup().equals("diplomat")).collect(Collectors.toList());
		if(
			primeDipAge.size() > 0
			&&
			(leader.getAge() < Rules.MINIMUM_ADULT_AGE || leader.getAge() >= Rules.SENIOR_AGE)
		) {
			logger.warn("One or more people are worried the leader is too young or too old");
			contest.addAll(primeDipAge);
		}
		
		if(primeAge.size() > 0 && leader.getAge() <= Rules.MAXIMUM_CHILD_AGE) {
			logger.warn("Do " + primeAge.size() + " adults really want a child as their leader?");
		}
			
		List<PersonalityProfile> prettyNarcissists = PersonalityUtil.filterBetterLookingPrettyNarcissists(map, leader); 
		if(prettyNarcissists.size() > 0) {
			logger.warn("Uh-oh, it looks like " + prettyNarcissists.size() + " narcissists prettier than the leader might be contesting that");
			contest.addAll(prettyNarcissists);
		}
		else {
			logger.info("No prettier narcissists around");
		}
		List<PersonalityProfile> machiavellian = DarkTriadUtil.filterMachiavellianism(map, leader);
		if(machiavellian.size() > 0) {
			logger.warn("Uh-oh, it looks like some machiavellian types have taken notice");
			contest.addAll(machiavellian);
		}
		else {
			logger.info("No machiavellian types around to take notice");
		}
		List<PersonalityProfile> psychopath = DarkTriadUtil.filterPsychopath(map, leader);
		if(psychopath.size() > 0 && !leader.isPsychopath()) {
			logger.warn("Uh-oh, it looks like some psychopaths have taken notice");
		}
		else {
			logger.info("No psychopaths around to take notice");
		}

		return new ArrayList<>(contest);
	}
}
