package org.cote.accountmanager.factory;

import java.util.MissingFormatArgumentException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EffectEnumType;
import org.cote.accountmanager.util.ErrorUtil;

public class ParticipationFactory {
	public static final Logger logger = LogManager.getLogger(ParticipationFactory.class);

	public static String getParticipantModel(BaseRecord model, String fieldName, BaseRecord actor) {
		String partModel = null;
		if(actor != null) {
			partModel = actor.getSchema();
		}
		if(model != null && fieldName != null) {
			partModel = getParticipantModel(model.getSchema(), fieldName, partModel);
		}
		return partModel;
	}
	public static String getParticipantModel(String model, String fieldName, String defaultModel) {
		String partModel = defaultModel;
		// logger.info("Get part model: " + model + "." + fieldName + " <> " + defaultModel);
		if(fieldName != null) {
			FieldSchema fs = RecordFactory.getSchema(model).getFieldSchema(fieldName);
			if(fs == null) {
				logger.error("Null field schema for " + model + "." + fieldName);
				return null;
			}
			if(fs.getParticipantModel() != null) {
				partModel = fs.getParticipantModel();
			}
		}
		return partModel;
	}
	public static BaseRecord newParticipation(BaseRecord owner, BaseRecord partc, String fieldName, BaseRecord partp, BaseRecord perm) {
		return newParticipation(owner, partc, fieldName, partp, EffectEnumType.GRANT_PERMISSION, perm);
	}
	
	public static BaseRecord newParticipation(BaseRecord owner, BaseRecord partc, String fieldName, BaseRecord partp) {
		return newParticipation(owner, partc, fieldName, partp, EffectEnumType.AGGREGATE, null);
	}
	
	public static BaseRecord newParticipation(BaseRecord owner, BaseRecord partc, String fieldName, BaseRecord partp, EffectEnumType effect, BaseRecord perm) {
		BaseRecord part1 = null;
		String partModel = getParticipantModel(partc.getSchema(), fieldName, partp.getSchema());

		long ppid = partc.get(FieldNames.FIELD_ID);
		long paid = partp.get(FieldNames.FIELD_ID);

		if(ppid == 0L || paid == 0L) {
			logger.error("Participation or participant identifiers are not specified");
			ErrorUtil.printStackTrace();
			return null;
		}
		
		/// Allow a user to be assigned participation to itself for purposes of assigning individual rights to the user object
		///
		else if(ppid == paid && partc.getSchema().equals(partp.getSchema()) && !partc.getSchema().equals(ModelNames.MODEL_USER)) {
			logger.error("Participation and partipant identifiers should not match: " + partc.getSchema() + "/" + partp.getSchema() + " " + ppid + "/" + paid);
			return null;
		}
		
		try{
			part1 = RecordFactory.model(ModelNames.MODEL_PARTICIPATION).newInstance();
			part1.set(FieldNames.FIELD_PARTICIPATION_ID, partc.get(FieldNames.FIELD_ID));
			part1.set(FieldNames.FIELD_PARTICIPATION_MODEL, partc.getSchema());
			part1.set(FieldNames.FIELD_PARTICIPANT_ID, partp.get(FieldNames.FIELD_ID));
			part1.set(FieldNames.FIELD_PARTICIPANT_MODEL, partModel);
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
