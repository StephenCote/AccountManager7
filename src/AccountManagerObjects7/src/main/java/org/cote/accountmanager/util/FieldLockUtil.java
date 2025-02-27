package org.cote.accountmanager.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;

public class FieldLockUtil {
	public static final Logger logger = LogManager.getLogger(FieldLockUtil.class);
	

	public static boolean clearFieldLocks(BaseRecord user, String modelName, long recordId, String fieldName) {
		boolean cleared = false;
		if(!canLock(modelName, fieldName, true)) {
			return false;
		}

		DBUtil dbUtil = IOSystem.getActiveContext().getDbUtil();
		String sql = "DELETE FROM " + dbUtil.getTableName(ModelNames.MODEL_FIELD_LOCK) + " WHERE referenceModel = ? " + (recordId > 0L ? " AND recordId = ? ": "") + (fieldName != null ? " AND fieldName = ?" : "");
		try (Connection con = dbUtil.getDataSource().getConnection(); PreparedStatement st = con.prepareStatement(sql);){
	    	int col = 1;
	    	st.setString(col++, modelName);
	    	if(recordId > 0L) {
	    		st.setLong(col++, recordId);
	    	}
	    	if(fieldName != null) {
	    		st.setString(col++, fieldName);
	    	}
	    	int update = st.executeUpdate();
	    	if(update > 0) {
	    		cleared = true;
	    	}
		} catch (SQLException  e) {
			logger.error(e);
	    }
	    return cleared;
	}
	
	public static boolean unlockField(BaseRecord user, BaseRecord record, String fieldName) {
		return unlockField(user, record.getSchema(), record.get(FieldNames.FIELD_ID), fieldName);
	}
	public static boolean unlockField(BaseRecord user, String modelName, long recordId, String fieldName) {
		
		if(!canLock(modelName, fieldName)) {
			return false;
		}
		
		BaseRecord lock = getFieldLock(user, modelName, recordId, fieldName);
		if(lock == null) {
			logger.info("Null lock object for " + modelName + " #" + recordId);
			return true;
		}
		
		else {
			/// Work with a copy when updating values
			/// TODO: There are some remaining instances where a cached object is altered, fails the update authorization, and isn't reset/cleared from the cache
			/// The expected outcome should be to wipe the reference from the cache whether the update succeeds or fails
			///
			lock = lock.copyRecord();
			if(lock != null && ((boolean)lock.get(FieldNames.FIELD_ENABLED)) == true) {
				try {
					lock.set(FieldNames.FIELD_ENABLED, false);
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
				}
				lock = IOSystem.getActiveContext().getAccessPoint().update(user, lock);
			}
			else {
				logger.info("Lock bit for " + modelName + " #" + recordId + " " + fieldName + " is already cleared");
			}
		}
		return (lock != null && !((boolean)lock.get(FieldNames.FIELD_ENABLED)));
	}
	
	public static boolean lockField(BaseRecord user, BaseRecord record, String fieldName) {
		return lockField(user, record.getSchema(), record.get(FieldNames.FIELD_ID), fieldName);
	}
	public static boolean lockField(BaseRecord user, String modelName, long recordId, String fieldName) {

		if(!canLock(modelName, fieldName)) {
			return false;
		}

		BaseRecord lock = getFieldLock(user, modelName, recordId, fieldName);
		if(lock == null) {
			lock = newFieldLock(user, modelName, recordId, fieldName);
			lock = IOSystem.getActiveContext().getAccessPoint().create(user, lock);
		}
		else {
			if(lock != null && ((boolean)lock.get(FieldNames.FIELD_ENABLED)) == false) {
				try {
					lock.set(FieldNames.FIELD_ENABLED, true);
				} catch (FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
				}
				IOSystem.getActiveContext().getAccessPoint().update(user, lock);
			}
		}
		return (lock != null && ((boolean)lock.get(FieldNames.FIELD_ENABLED)));
	}
	public static boolean isFieldLocked(BaseRecord user, BaseRecord record, String fieldName) {
		return isFieldLocked(user, record.getSchema(), record.get(FieldNames.FIELD_ID), fieldName);
	}
	public static boolean isFieldLocked(BaseRecord user, String modelName, long recordId, String fieldName) {
		BaseRecord lock = getFieldLock(user, modelName, recordId, fieldName);
		return (lock != null && ((boolean)lock.get(FieldNames.FIELD_ENABLED)));
	}
	public static List<String> getFieldLocks(BaseRecord user, BaseRecord record) {
		return getFieldLocks(user, record.getSchema(), record.get(FieldNames.FIELD_ID));
	}
	public static List<String> getFieldLocks(BaseRecord user, String modelName, long recordId) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_FIELD_LOCK, FieldNames.FIELD_REFERENCE_TYPE, modelName);
		q.field(FieldNames.FIELD_REFERENCE_ID, recordId);
		q.field(FieldNames.FIELD_ENABLED, true);
		q.field(FieldNames.FIELD_ORGANIZATION_ID, user.get(FieldNames.FIELD_ORGANIZATION_ID));
		List<String> locks = new ArrayList<>();
		try {
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			if(qr != null) {
				locks = Arrays.asList(qr.getResults()).stream().map(r -> (String)r.get(FieldNames.FIELD_FIELD_NAME)).collect(Collectors.toList());
			}
		} catch (ReaderException e) {
			logger.error(e);
		}
		return locks;
	}
	
	protected static BaseRecord getFieldLock(BaseRecord user, String modelName, long recordId, String fieldName) {
		Query q = QueryUtil.createQuery(ModelNames.MODEL_FIELD_LOCK, FieldNames.FIELD_REFERENCE_TYPE, modelName);
		q.field(FieldNames.FIELD_REFERENCE_ID, recordId);
		q.field(FieldNames.FIELD_FIELD_NAME, fieldName);
		return IOSystem.getActiveContext().getSearch().findRecord(q);
	}

	protected static BaseRecord newFieldLock(BaseRecord user, BaseRecord record, String fieldName) {
		if(!record.hasField(FieldNames.FIELD_ID) || ((long)record.get(FieldNames.FIELD_ID)) <= 0L) {
			logger.error("Record does not define an identity");
			return null;
		}
		return newFieldLock(user, record.getSchema(), record.get(FieldNames.FIELD_ID), fieldName);
	}
	
	
	public static boolean canLock(String modelName, String fieldName) {
		return canLock(modelName, fieldName, false);
	}
	
	public static boolean canLock(String modelName, String fieldName, boolean allowNullField) {
		
		if(modelName == null || modelName.equals(ModelNames.MODEL_FIELD_LOCK)) {
			logger.error("Invalid model name: " + modelName);
			return false;
		}	
		ModelSchema ms = RecordFactory.getSchema(modelName);
		if(ms == null) {
			logger.error("Invalid model schema for " + modelName);
			return false;
		}
		if(fieldName == null && allowNullField) {
			return true;
		}
		FieldSchema fs = ms.getFieldSchema(fieldName);
		if(fs == null) {
			logger.error("Invalid field schema for " + modelName + "." + fieldName);
			return false;
		}
		if(fs.isIdentity() || fs.isVirtual() || fs.isPriv() || fs.isEphemeral() || fs.isReadOnly() || fs.isSequence()) {
			logger.error("Field " + modelName + "." + fieldName + " cannot be locked");
			return false;
		}
		return true;
	}
	
	protected static BaseRecord newFieldLock(BaseRecord user, String modelName, long recordId, String fieldName) {
		BaseRecord lock = null;
		
		if(!canLock(modelName, fieldName)) {
			return null;
		}
		
		ParameterList plist = ParameterList.newParameterList(FieldNames.FIELD_FIELD_NAME, fieldName);
		plist.parameter(FieldNames.FIELD_REFERENCE_ID, recordId);
		plist.parameter(FieldNames.FIELD_REFERENCE_TYPE, modelName);
		
		try {
			lock = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_FIELD_LOCK, user, null, plist);
		} catch (FactoryException e) {
			logger.error(e);
		}

		return lock;
	}
}
