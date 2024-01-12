package org.cote.accountmanager.olio.rules;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.PersonalityProfile;
import org.cote.accountmanager.olio.PersonalityUtil;
import org.cote.accountmanager.olio.rules.needs.NeedsUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.JSONUtil;

public class HierarchicalNeedsRule implements IOlioEvolveRule {
	public static final Logger logger = LogManager.getLogger(HierarchicalNeedsRule.class);

	private static final SecureRandom random = new SecureRandom();
	
	/// Use a smaller party selection of the population for tuning events
	///
	private boolean partyPlay = true;
	
	@Override
	public void evaluateIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord increment) {
		// TODO Auto-generated method stub
		logger.info("Evaluate " + locationEpoch.get(FieldNames.FIELD_NAME) + " " + increment.get(FieldNames.FIELD_NAME));
		// logger.info(increment.toFullString());
		BaseRecord realm = context.getRealm(locationEpoch.get("location"));

		List<BaseRecord> party = (partyPlay ? NeedsUtil.getCreateParty(context, locationEpoch) : context.getPopulation(locationEpoch.get("location")));
		NeedsUtil.recommend(context, locationEpoch, increment, party);
		/*
		for(BaseRecord p: party) {
			PersonalityProfile prof = PersonalityUtil.getProfile(context, p);
			//logger.info(JSONUtil.exportObject(prof));
		}
		*/

	}
	
	@Override
	public void startEpoch(OlioContext context, BaseRecord epoch) {
		// TODO Auto-generated method stub
		
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
		// TODO Auto-generated method stub
		
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
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord continueIncrement(OlioContext context, BaseRecord locationEpoch) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void endIncrement(OlioContext context, BaseRecord locationEpoch, BaseRecord currentIncrement) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public BaseRecord nextIncrement(OlioContext context, BaseRecord parentEvent) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void beginEvolution(OlioContext context) {
		// TODO Auto-generated method stub
		
	}
	
}
