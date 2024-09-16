package org.cote.accountmanager.olio.rules;

import java.security.SecureRandom;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.AnimalUtil;
import org.cote.accountmanager.olio.Clock;
import org.cote.accountmanager.olio.NeedsUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.OverwatchException;
import org.cote.accountmanager.olio.personality.GroupDynamicUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;

public class HierarchicalNeedsEvolveRule extends CommonEvolveRule implements IOlioEvolveRule {
	public static final Logger logger = LogManager.getLogger(HierarchicalNeedsEvolveRule.class);

	private static final SecureRandom random = new SecureRandom();
	
	/// Use a smaller party selection of the population for tuning events
	///
	private boolean partyPlay = true;
	

	@Override
	public void evaluateRealmIncrement(OlioContext context, BaseRecord realm) {

		// logger.info("Evaluate " + locationEpoch.get(FieldNames.FIELD_NAME) + " " + increment.get(FieldNames.FIELD_NAME));
		
		/// populate any animal life as needed

		AnimalUtil.checkAnimalPopulation(context, realm, realm.get(OlioFieldNames.FIELD_ORIGIN));
		
		/// Party Play will pick a small band of work with, versus the total population
		/// This becomes the primaryGroup of the 'realm' for this location
		/// NOTE/TODO: The realm/location relationship is currently a bit disconnected in the way they are initially set up
		/// This was supposed to allow for flexibility, but depending on the rule chain, can quickly become mandatory
		///
		//Clock rclock = context.clock().realmClock(realm);
		List<BaseRecord> party = (partyPlay ? GroupDynamicUtil.getCreateParty(context, realm) : context.getRealmPopulation(realm));

		NeedsUtil.recommend(context, realm, party);

		try {
			context.overwatchActions();
		} catch (OverwatchException e) {
			logger.error(e);
		}
	}
	
}
