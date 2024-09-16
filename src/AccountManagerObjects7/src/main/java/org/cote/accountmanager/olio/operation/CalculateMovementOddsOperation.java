package org.cote.accountmanager.olio.operation;

import java.security.SecureRandom;
import java.util.List;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.TerrainUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.policy.FactUtil;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;


	public class CalculateMovementOddsOperation extends Operation {
		private SecureRandom random = new SecureRandom();
		
		public CalculateMovementOddsOperation(IReader reader, ISearch search) {
			super(reader, search);
		}
		
		@Override
		public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
			// TODO Auto-generated method stub
			return null;
		}
		
		@Override
		public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord sourceFact, BaseRecord referenceFact) {
			BaseRecord paramAnimal = FactUtil.getParameterValue(sourceFact, "animalActor");
			BaseRecord paramLocation = FactUtil.getParameterValue(sourceFact, "targetLocation");
			if(paramAnimal == null) {
				logger.info("Expected an 'animal' parameter");
				return OperationResponseEnumType.ERROR;
				
			}
			if(paramLocation == null) {
				logger.info("Expected a 'location' parameter");
				return OperationResponseEnumType.ERROR;
			}

			TerrainEnumType tet = paramLocation.getEnum(FieldNames.FIELD_TERRAIN_TYPE);
			double movementOdds = Rules.getMovementOdds(tet);
			if(paramAnimal.getModel().equals(OlioModelNames.MODEL_ANIMAL)) {
				List<String> habitat = paramAnimal.get(OlioFieldNames.FIELD_HABITAT);
				if(habitat.contains(tet.toString().toLowerCase())) {
					movementOdds = Rules.ODDS_HABITAT_MOVEMENT;
				}
				else {
					/// Animal out of native habitat
					///
					if(TerrainUtil.ruleFishOutOfWater(paramAnimal, tet)) {
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
			OperationResponseEnumType ort = OperationResponseEnumType.FAILED;
			double rand = random.nextDouble();
			if(rand <= movementOdds) {
				ort = OperationResponseEnumType.SUCCEEDED;
			}
			PolicyUtil.addResponseMessage(prr, "Calculate movement odds for " + paramAnimal.get(FieldNames.FIELD_NAME) + " to " + tet.toString() + " - " + rand + " <= " + movementOdds);
			return ort;
		}
	}
