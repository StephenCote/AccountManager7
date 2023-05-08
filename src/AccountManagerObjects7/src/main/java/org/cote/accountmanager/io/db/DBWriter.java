package org.cote.accountmanager.io.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.MemoryWriter;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.FieldUtil;
import org.cote.accountmanager.util.RecordUtil;

public class DBWriter extends MemoryWriter {
	public static final Logger logger = LogManager.getLogger(DBWriter.class);

	private DataSource dataSource = null;
	public DBWriter(DataSource dsource) {
		super();
		this.recordIo = RecordIO.DATABASE;
		this.dataSource = dsource;
	}
	

	@Override
	public void flush() {

	}
	



	public synchronized boolean delete(BaseRecord model) throws WriterException {
		//RecordOperation op = RecordOperation.DELETE;
		boolean success = false;
		CacheUtil.clearCache(model);
		DBStatementMeta meta = StatementUtil.getDeleteTemplate(model);
		if(meta == null) {
			return false;
		}
	    try (Connection con = dataSource.getConnection()){
	    	PreparedStatement st = con.prepareStatement(meta.getSql());
	    	StatementUtil.applyPreparedStatement(model, meta, st);
	    	int update = st.executeUpdate();
	    	if(update > 0) {
	    		logger.info("Deleted: " + update);
	    		success = true;
	    	}
			st.close();
		} catch (SQLException | DatabaseException e) {
			logger.error(e);
	    }
		

		return success;
	}
	
	public synchronized boolean write(BaseRecord model) throws WriterException {
		RecordOperation op = RecordOperation.CREATE;
		ModelSchema schema = RecordFactory.getSchema(model.getModel());
		String objectId = (model.hasField(FieldNames.FIELD_OBJECT_ID) ? model.get(FieldNames.FIELD_OBJECT_ID) : null);
		long id = (model.hasField(FieldNames.FIELD_ID) ? model.get(FieldNames.FIELD_ID) : 0L);
		if(id > 0L || objectId != null) {
			op = RecordOperation.UPDATE;
			CacheUtil.clearCache(model);
		}
		/*
		logger.info(op.toString() + " " + model.getModel());
		logger.info(model.toString());
		model.getFields().forEach(f -> {
			logger.info(f.getName());
		});
		*/
		// RecordUtil.sortFields(model);
		super.write(model);
		if(!RecordUtil.isIdentityRecord(model) && (op != RecordOperation.CREATE || !RecordUtil.isIdentityModel(schema))) { 
			logger.error("*** " + op.toString() + " " + model.getModel());
			logger.error("**** " + model.get(FieldNames.FIELD_ID));
			
			logger.error("**** " + RecordUtil.isIdentityRecord(model));
			logger.error("**** " + RecordUtil.isIdentityModel(schema));
			logger.error(model.toString());
			throw new WriterException("Model " + model.getModel() + " does not define an identity field");
		}
		
		for(FieldType f : model.getFields()) {
			FieldSchema fs = schema.getFieldSchema(f.getName());
			if(fs.isForeign() && f.getValueType() == FieldEnumType.MODEL) {
				BaseRecord bf = model.get(f.getName());
				if(bf != null && !RecordUtil.isIdentityRecord(bf)) {
					logger.warn("Attempt to auto-write " + model.getModel() + "." + f.getName() + "? " +  RecordUtil.isIdentityRecord(bf));
				}
			}
			
		}
		
		boolean success = false;
		if(op == RecordOperation.CREATE) {
			long rid = 0L;
			try{
				rid = IOSystem.getActiveContext().getDbUtil().getNextId(model.getModel());
			}
			catch(DatabaseException e) {
				logger.error(e);
			}
			// logger.info("Obtain next id: " + rid);
			if(rid == 0L) {
				throw new WriterException("Failed to obtain next identifier");
			}
			DBStatementMeta meta = StatementUtil.getInsertTemplate(model);
			
			// logger.info(meta.getSql());
		    try (Connection con = dataSource.getConnection()){
				model.set(FieldNames.FIELD_ID, rid);
		    	PreparedStatement st = con.prepareStatement(meta.getSql());
		    	StatementUtil.applyPreparedStatement(model, meta, st);
		    	int update = st.executeUpdate();
		    	// logger.info("Update: " + update);
		    	if(update > 0) {
		    		
		    		List<FieldType> refList = model.getFields().stream().filter(o -> {
		    			FieldSchema fs = schema.getFieldSchema(o.getName());
		    			return fs.isReferenced();

		    		}).collect(Collectors.toList());
		    		
		    		if(refList.size() > 0) {
		    			// logger.info("Write referenced list");
		    			for(FieldType f : refList) {
		    				if(f.getValueType() == FieldEnumType.LIST) {
		    					FieldSchema fs = schema.getFieldSchema(f.getName());
		    					
		    					if(fs.getBaseType().equals(ModelNames.MODEL_MODEL)) {
		    						List<BaseRecord> vals = model.get(f.getName());
		    						for(BaseRecord erec : vals) {
		    							if(!erec.inherits(ModelNames.MODEL_REFERENCE)) {
		    								logger.error("Model " + erec.getModel() + " does not inherit from the reference model");
		    								continue;
		    							}
		    							if(FieldUtil.isNullOrEmpty(erec.getField(FieldNames.FIELD_REFERENCE_ID))) {
		    								erec.set(FieldNames.FIELD_REFERENCE_ID, rid);
		    								erec.set(FieldNames.FIELD_REFERENCE_TYPE, model.getModel());
		    								if(erec.inherits(ModelNames.MODEL_ORGANIZATION_EXT)) {
		    									erec.set(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
		    								}
		    								if(!IOSystem.getActiveContext().getRecordUtil().createRecord(erec)) {
		    									logger.error("Failed to write: " + erec.toString());
		    								}
		    							}
		    							
		    						}
		    					}
		    					else {
		    						logger.error("**** Handle Referenced Type That Is Not a Model");
		    					}
		    					
		    					
		    				}
		    				else {
		    					logger.error("**** Handle Referenced Type " + f.getValueType().toString());
		    				}
		    			}
		    		}
		    		
		    		success = true;
		    	}
				st.close();
			} catch (SQLException | DatabaseException | FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
		    }
		}
		else if(op == RecordOperation.UPDATE) {
			DBStatementMeta meta = StatementUtil.getUpdateTemplate(model);
			// logger.info(meta.getSql());
		    try (Connection con = dataSource.getConnection()){
		    	PreparedStatement st = con.prepareStatement(meta.getSql());
		    	StatementUtil.applyPreparedStatement(model, meta, st);
		    	int update = st.executeUpdate();
		    	if(update > 0) {
		    		success = true;
		    	}
				st.close();
			} catch (SQLException | DatabaseException e) {
				logger.error(e);
		    }
		}
		
		
		
		return success;
	}
	


}
