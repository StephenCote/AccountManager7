package org.cote.accountmanager.record;

import java.util.MissingFormatArgumentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EffectEnumType;

public class ParticipationFactory {
	public static final Logger logger = LogManager.getLogger(ParticipationFactory.class);
	public static BaseRecord newParticipation(BaseRecord owner, BaseRecord partc, BaseRecord partp, BaseRecord perm) {
		return newParticipation(owner, partc, partp, EffectEnumType.GRANT_PERMISSION, perm);
	}
	public static BaseRecord newParticipation(BaseRecord owner, BaseRecord partc, BaseRecord partp) {
		return newParticipation(owner, partc, partp, EffectEnumType.AGGREGATE, null);
	}
	public static BaseRecord newParticipation(BaseRecord owner, BaseRecord partc, BaseRecord partp, EffectEnumType effect, BaseRecord perm) {
		BaseRecord part1 = null;
		try{
			part1 = RecordFactory.model(ModelNames.MODEL_PARTICIPATION).newInstance();
			part1.set(FieldNames.FIELD_PARTICIPATION_ID, partc.get("id"));
			part1.set(FieldNames.FIELD_PARTICIPATION_MODEL, partc.getModel());
			part1.set(FieldNames.FIELD_PARTICIPANT_ID, partp.get(FieldNames.FIELD_ID));
			part1.set(FieldNames.FIELD_PARTICIPANT_MODEL, partp.getModel());
			part1.set(FieldNames.FIELD_ENABLED,  true);
			part1.set(FieldNames.FIELD_OWNER_ID, owner.get(FieldNames.FIELD_ID));
			part1.set(FieldNames.FIELD_ORGANIZATION_ID, owner.get(FieldNames.FIELD_ORGANIZATION_ID));
			if(effect != null) {
				part1.set(FieldNames.FIELD_EFFECT_TYPE, effect.toString());
			}
			if(perm != null) {
				part1.set(FieldNames.FIELD_PERMISSION_ID, perm.get(FieldNames.FIELD_ID));
			}
		}
		catch (MissingFormatArgumentException | NullPointerException | FieldException | ModelNotFoundException | ValueException e) {
			logger.error(e);
		}
		return part1;
	}
}
