package org.cote.accountmanager.olio;

import java.util.Arrays;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.rules.ArenaEvolveRule;
import org.cote.accountmanager.olio.rules.ArenaInitializationRule;
import org.cote.accountmanager.olio.rules.GenericItemDataLoadRule;
import org.cote.accountmanager.olio.rules.GridSquareLocationInitializationRule;
import org.cote.accountmanager.olio.rules.HierarchicalNeedsRule;
import org.cote.accountmanager.olio.rules.IOlioContextRule;
import org.cote.accountmanager.olio.rules.IOlioEvolveRule;
import org.cote.accountmanager.olio.rules.Increment24HourRule;
import org.cote.accountmanager.olio.rules.LocationPlannerRule;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.AuditUtil;

public class OlioContextUtil {
	public static final Logger logger = LogManager.getLogger(OlioContextUtil.class);
	private static HashMap<String, OlioContext> contextMap = new HashMap<>();
	public static OlioContext getOlioContext(BaseRecord user, String dataPath) {
		String key = user.get(FieldNames.FIELD_NAME);
		if(contextMap.containsKey(key)) {
			return contextMap.get(key);
		}
		OlioContext octx = OlioContextUtil.getGridContext(user, dataPath, "My Grid Universe", "My Grid World", false);
		if(octx != null) {
			contextMap.put(key, octx);
		}
		return octx;
	}
	public static OlioContext getGridContext(BaseRecord user, String dataPath, String universeName, String worldName, boolean resetWorld) {
		AuditUtil.setLogToConsole(false);
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(true);

		OlioContextConfiguration cfg = new OlioContextConfiguration(
				user,
				dataPath,
				"~/Worlds",
				universeName,
				worldName,
				new String[] {},
				1,
				50,
				resetWorld,
				false
			);
		
			/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
			///
			cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
				new GridSquareLocationInitializationRule(),
				new LocationPlannerRule(),
				new GenericItemDataLoadRule()
			}));
			
			// Increment24HourRule incRule = new Increment24HourRule();
			// incRule.setIncrementType(TimeEnumType.HOUR);
			cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
				new Increment24HourRule(),
				new HierarchicalNeedsRule()
			}));
			OlioContext octx = new OlioContext(cfg);

			logger.info("Initialize olio context - Grid");
			octx.initialize();
			
			AuditUtil.setLogToConsole(true);
			IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
			
			return octx;
	}
	public static OlioContext getArenaContext(BaseRecord user, String dataPath, String universeName, String worldName, boolean resetWorld) {
		/// Currently using the 'Arena' setup with minimal locations and small, outfitted squads
		///
		AuditUtil.setLogToConsole(false);
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(true);
		OlioContextConfiguration cfg = new OlioContextConfiguration(
			user,
			dataPath,
			"~/Worlds",
			universeName,
			worldName,
			new String[] {},
			1,
			50,
			resetWorld,
			false
		);
	
		/// Generate a grid square structure to use with a map that can evolve during evolutionary cycles
		///
		cfg.getContextRules().addAll(Arrays.asList(new IOlioContextRule[] {
			new ArenaInitializationRule(),
			new GenericItemDataLoadRule()
		}));
		cfg.getEvolutionRules().addAll(Arrays.asList(new IOlioEvolveRule[] {
				new ArenaEvolveRule()
			}));
		OlioContext octx = new OlioContext(cfg);

		logger.info("Initialize olio context - Arena");
		octx.initialize();
		
		AuditUtil.setLogToConsole(true);
		IOSystem.getActiveContext().getAccessPoint().setPermitBulkContainerApproval(false);
		
		return octx;
	}
}
