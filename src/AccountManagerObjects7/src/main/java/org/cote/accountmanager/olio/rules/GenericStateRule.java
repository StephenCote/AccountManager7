package org.cote.accountmanager.olio.rules;

import java.security.SecureRandom;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.olio.OlioContext;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.TerrainUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.TerrainEnumType;

public class GenericStateRule  implements IOlioStateRule {
	public static final Logger logger = LogManager.getLogger(GenericStateRule.class);

	private SecureRandom random = new SecureRandom();
	
	@Override
	public boolean canMove(OlioContext context, BaseRecord animal, BaseRecord location) {
		BaseRecord state = animal.get("state");
		if(state == null) {
			logger.warn("Null state");
			return false;
		}
		if((boolean)state.get("immobilized") || (boolean)state.get("incapacitated")) {
			logger.warn("Incapable of moving");
			return false;
		}
		TerrainEnumType tet = location.getEnum("terrainType");
		double movementOdds = Rules.getMovementOdds(tet);
		if(animal.getModel().equals(ModelNames.MODEL_ANIMAL)) {
			List<String> habitat = animal.get("habitat");
			if(habitat.contains(tet.toString().toLowerCase())) {
				movementOdds = Rules.ODDS_HABITAT_MOVEMENT;
			}
			else {
				/// Animal out of native habitat
				///
				if(TerrainUtil.ruleFishOutOfWater(animal, tet)) {
					// logger.warn(animal.get(FieldNames.FIELD_NAME) + " would be way out of it's habitat in " + tet.toString().toLowerCase() + " and would likely die");
					movementOdds = 0.001;
				}
				else {
					movementOdds = Math.min(movementOdds, Rules.ODDS_NONHABITAT_MOVEMENT);
				}
			}
		}
		/// Simple default rule - if odds of moving are greater than 50% then it's an automatic success
		/// TODO: Apply movement modifiers (eg: floatation or skill for water, skill for mountain, etc)
		/*
		if(movementOdds > 0.50) {
			return true;
		}
		*/
		double rand = random.nextDouble();
		if(rand <= movementOdds) {
			return true;
		}
		/// figure out consequences of failing to move
		// logger.warn(animal.get(FieldNames.FIELD_NAME) + " failed (" + rand + " > " + movementOdds + ") to move over a patch of " + tet.toString().toLowerCase());
		
		return false;
	}
	

}
