package org.cote.accountmanager.olio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;

public class SituationUtil {
	public static final Logger logger = LogManager.getLogger(SituationUtil.class);
	
	public static Assessment assess(OlioContext ctx, BaseRecord location) {
		Assessment amt = new Assessment(location);
		return amt;
	}
}
