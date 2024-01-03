package org.cote.accountmanager.olio.rules.needs;

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
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class NeedsUtil {

	public static final Logger logger = LogManager.getLogger(NeedsUtil.class);
	private static final SecureRandom random = new SecureRandom();
	private static final int partyMin = 3;
	private static final int partyMax = 10;

	public static List<BaseRecord> getCreateParty(OlioContext ctx, BaseRecord locationEpoch){
		List<BaseRecord> party = new ArrayList<>();
		BaseRecord loc = locationEpoch.get("location");
		IOSystem.getActiveContext().getReader().populate(loc, new String[] { FieldNames.FIELD_NAME });
		String partyName = loc.get(FieldNames.FIELD_NAME) + " Party";
		BaseRecord grp = null;
		try {
			grp = OlioUtil.getCreatePopulationGroup(ctx, partyName);
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
					while(partSet.contains(id) || age < 14 || age > 40) {
						per = lpop.get(random.nextInt(lpop.size()));
						id = per.get(FieldNames.FIELD_ID);
						age = per.get("age");
						check++;
						if(check > 3) {
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
	
}
