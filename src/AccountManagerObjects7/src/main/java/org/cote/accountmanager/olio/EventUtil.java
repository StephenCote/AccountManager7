package org.cote.accountmanager.olio;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.EventEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;

public class EventUtil {
	public static final Logger logger = LogManager.getLogger(EventUtil.class);
	
	public static BaseRecord addEvent(
			BaseRecord user, BaseRecord world, BaseRecord parentEvent, EventEnumType type, String name, long time,
			BaseRecord[] actors, BaseRecord[] participants, BaseRecord[] influencers,
			Map<String, List<BaseRecord>> queue
		) {
			BaseRecord evt = null;
			ParameterList elist = ParameterList.newParameterList("path", world.get("events.path"));
			try {
				evt = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_EVENT, user, null, elist);
				/// TODO: Need a way to bulk-add hierarchies
				/// The previous version used a complex method of identifier assignment and rewrite with negative values
				evt.set(FieldNames.FIELD_NAME, name);
				evt.set(FieldNames.FIELD_LOCATION, parentEvent.get(FieldNames.FIELD_LOCATION));
				if(actors != null && actors.length > 0) {
					List<BaseRecord> acts = evt.get("actors");
					acts.addAll(Arrays.asList(actors));
				}
				if(participants != null && participants.length > 0) {
					List<BaseRecord> parts = evt.get("participants");
					parts.addAll(Arrays.asList(participants));
				}
				if(influencers != null && influencers.length > 0) {
					List<BaseRecord> inf = evt.get("influencers");
					inf.addAll(Arrays.asList(influencers));
				}
				evt.set(FieldNames.FIELD_TYPE, type);
				evt.set(FieldNames.FIELD_PARENT_ID, parentEvent.get(FieldNames.FIELD_ID));
				evt.set("eventStart", new Date(time));
				evt.set("eventEnd", new Date(time));
				OlioUtil.queueAdd(queue, evt);
			}
			catch(FactoryException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
			return evt;
		}
	
	public static BaseRecord getRootEvent(BaseRecord user, BaseRecord world) {
		IOSystem.getActiveContext().getReader().populate(world, 2);
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, (long)world.get("events.id"));
		q.field(FieldNames.FIELD_PARENT_ID, 0L);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}
	
	public static BaseRecord[] getBaseRegionEvents(BaseRecord user, BaseRecord world) {
		BaseRecord root = getRootEvent(user, world);
		BaseRecord[] evts = new BaseRecord[0];
		if(root != null) {
			IOSystem.getActiveContext().getReader().populate(world, 2);
			Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, (long)world.get("events.id"));
			q.field(FieldNames.FIELD_PARENT_ID, root.get(FieldNames.FIELD_ID));
			q.field(FieldNames.FIELD_TYPE, EventEnumType.INCEPT);
			evts = IOSystem.getActiveContext().getSearch().findRecords(q);
		}
		return evts;
	}
	public static BaseRecord getLastEvent(BaseRecord user, BaseRecord world, BaseRecord location) {
		BaseRecord lastEpoch = getLastEpochEvent(user, world);
		if(lastEpoch == null) {
			return null;
		}
		
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, world.get("events.id"));
		q.field(FieldNames.FIELD_PARENT_ID, lastEpoch.get(FieldNames.FIELD_ID));
		q.field(FieldNames.FIELD_LOCATION, location.copyRecord(new String[] {FieldNames.FIELD_ID}));
		BaseRecord lastEvt = null;
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, "eventStart");
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING);
			q.setRequestRange(0L, 1);
			lastEvt = IOSystem.getActiveContext().getSearch().findRecord(q);
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return lastEvt;
	}
	public static BaseRecord getLastEpochEvent(BaseRecord user, BaseRecord world) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_EVENT, FieldNames.FIELD_GROUP_ID, world.get("events.id"));
		q.field("epoch", true);
		BaseRecord epoch = null;
		try {
			q.set(FieldNames.FIELD_SORT_FIELD, "eventStart");
			q.set(FieldNames.FIELD_ORDER, OrderEnumType.DESCENDING);
			q.setRequestRange(0L, 1);
			epoch = IOSystem.getActiveContext().getSearch().findRecord(q);
		}
		catch(ModelNotFoundException | FieldException | ValueException e) {
			logger.error(e);
		}
		return epoch;
	}
}
