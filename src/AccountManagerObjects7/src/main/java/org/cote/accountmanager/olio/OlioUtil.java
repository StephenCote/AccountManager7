package org.cote.accountmanager.olio;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.OrderEnumType;
import org.cote.accountmanager.util.ResourceUtil;

public class OlioUtil {
	public static final Logger logger = LogManager.getLogger(OlioUtil.class);
	
	public static String randomSelectionName(BaseRecord user, Query query) {
		String[] names = randomSelectionNames(user, query, 1);
		if(names.length > 0) {
			return names[0];
		}
		return null;
	}
	public static String[] randomSelectionNames(BaseRecord user, Query query, int count) {
		return Arrays.asList(randomSelections(user, query, count)).stream().map(f -> f.get(FieldNames.FIELD_NAME)).collect(Collectors.toList()).toArray(new String[0]);
	}
	public static BaseRecord randomSelection(BaseRecord user, Query query) {
		BaseRecord[] recs = randomSelections(user, query, 1);
		BaseRecord rec = null;
		if(recs.length > 0) {
			rec = recs[0];
		}
		return rec;
	}
	public static BaseRecord[] randomSelections(BaseRecord user, Query query, int count) {
		QueryResult qr = null;
		try {
			query.setRequestRange(0, count);
			query.set(FieldNames.FIELD_CACHE, false);
			query.set(FieldNames.FIELD_SORT_FIELD, "random()");
			query.set(FieldNames.FIELD_ORDER, OrderEnumType.ASCENDING);
			qr = IOSystem.getActiveContext().getSearch().find(query);
		} catch (IndexException | ReaderException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		if(qr != null && qr.getCount() > 0) {
			//logger.info("Random: " + query.get(FieldNames.FIELD_TYPE)+ " " + qr.getCount());
			// logger.info(qr.getResults()[0].toFullString());
			return qr.getResults();
		}
		else {
			// logger.warn("No results: ");
			logger.warn(query.toFullString());
		}
		return new BaseRecord[0];
	}
	public static boolean recordExists(BaseRecord user, String modelName, String name, BaseRecord group) throws IndexException, ReaderException {
		return recordExists(user, modelName, name, (long)group.get(FieldNames.FIELD_ID));
	}
	public static boolean recordExists(BaseRecord user, String modelName, String name, long groupId) throws IndexException, ReaderException {
		Query q = QueryUtil.createQuery(modelName, FieldNames.FIELD_NAME, name);
		q.field(FieldNames.FIELD_GROUP_ID, groupId);
		q.setRequest(new String[] {FieldNames.FIELD_ID});
		return (IOSystem.getActiveContext().getSearch().find(q).getCount() > 0);
	}
    public static <T extends Enum<?>> T randomEnum(Class<T> cls){
        int x = (new Random()).nextInt(cls.getEnumConstants().length);
        return cls.getEnumConstants()[x];
    }
    
	protected static String getRandomOlioValue(BaseRecord user, BaseRecord world, String fieldName) {
		String[] outVal = getRandomOlioValues(user, world, fieldName, 1);
		if(outVal.length > 0) {
			return outVal[0];
		}

		return null;
	} 
	protected static String[] getRandomOlioValues(BaseRecord user, BaseRecord world, String fieldName, int count) {
		String[] outVal = new String[0];
		long groupId = world.get("colors.id");
		if(groupId <= 0L) {
			logger.warn("Invalid group id: " + groupId);
			return outVal;
		}
		switch(fieldName) {
			case "color":
				outVal = OlioUtil.randomSelectionNames(user, QueryUtil.createQuery(ModelNames.MODEL_COLOR, FieldNames.FIELD_GROUP_ID, groupId), count);
				break;
			default:
				logger.warn("Unhandled type: " + fieldName);
				break;
		}
		return outVal;
	}
	
	protected static void applyRandomOlioValues(BaseRecord user, BaseRecord world, BaseRecord record) {
		record.getFields().forEach(f -> {
			try {
				if(f.getValueType() == FieldEnumType.STRING) {
					String val = f.getValue();
					if("$random".equals(val)) {
						f.setValue(getRandomOlioValue(user, world, f.getName()));
					}
				}
			}
			catch(ValueException e) {
				logger.error(e);
			}
		});
	}
	

	public static BaseRecord newQuality(BaseRecord user, String groupPath) {
		BaseRecord qual = newGroupRecord(user, ModelNames.MODEL_QUALITY, groupPath, null);
		return IOSystem.getActiveContext().getAccessPoint().create(user, qual);
	}
	public static BaseRecord newGroupRecord(BaseRecord user, String model, String groupPath, BaseRecord template) {
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().makePath(user, ModelNames.MODEL_GROUP, groupPath, GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dir == null) {
			logger.error("Failed to find or create group " + groupPath);
			return null;
		}
		ParameterList plist = ParameterList.newParameterList("path", groupPath);
		BaseRecord irec = null;
		try {
			irec = IOSystem.getActiveContext().getFactory().newInstance(model, user, template, plist);
		}
		catch(ClassCastException | FactoryException e) {
			logger.error(e);
			e.printStackTrace();
		}
		return irec;
	}
	
}
