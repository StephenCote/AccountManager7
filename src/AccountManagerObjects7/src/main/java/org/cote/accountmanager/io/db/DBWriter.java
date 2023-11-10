package org.cote.accountmanager.io.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.DatabaseException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.MemoryWriter;
import org.cote.accountmanager.io.Query;
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
	

	@Override
	public synchronized int delete(Query query) throws WriterException {
		
		int deleted = 0;
		CacheUtil.clearCache(query);
	    try (Connection con = dataSource.getConnection()){
			DBStatementMeta meta = StatementUtil.getDeleteTemplate(query);
			if(meta == null) {
				return deleted;
			}

	    	PreparedStatement statement = con.prepareStatement(meta.getSql());
			StatementUtil.setStatementParameters(query, statement);
	    	deleted = statement.executeUpdate();

	    	statement.close();
		} catch (SQLException | DatabaseException | ModelException | FieldException e) {
			logger.error(e);
	    }
		

		return deleted;
	}

	@Override
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
	    		success = true;
	    	}
			st.close();
		} catch (SQLException | DatabaseException e) {
			logger.error(e);
			e.printStackTrace();
	    }
		

		return success;
	}
	
	
	private int maxBatchSize = 2000;
	
	public synchronized int write(BaseRecord[] models) throws WriterException {
		int writeCount = 0;
		int batch = 0;
		if(models.length == 0) {
			return 0;
		}
		
		if(!IOSystem.getActiveContext().getRecordUtil().isSimilar(models)) {
			logger.error("Model list must be consistent and with the same model name");
		}
		
		RecordOperation op = RecordOperation.CREATE;
		BaseRecord firstModel = models[0];
		ModelSchema schema = RecordFactory.getSchema(firstModel.getModel());
		String objectId = (firstModel.hasField(FieldNames.FIELD_OBJECT_ID) ? firstModel.get(FieldNames.FIELD_OBJECT_ID) : null);
		long id = (firstModel.hasField(FieldNames.FIELD_ID) ? firstModel.get(FieldNames.FIELD_ID) : 0L);
		if(id > 0L || objectId != null) {
			op = RecordOperation.UPDATE;
		}

		Map<String, List<BaseRecord>> autoCreate = new HashMap<>();
		if(autoCreate.size() > 0) {
			logger.error("TODO: Re-implement auto creation of foreign references");
		}
		
		for(BaseRecord rec : models) {
			if(op == RecordOperation.UPDATE) {
				CacheUtil.clearCache(rec);
			}
			super.write(rec);
		}
		
		if(!RecordUtil.isIdentityRecord(firstModel) && (op != RecordOperation.CREATE || !RecordUtil.isIdentityModel(schema))) { 
			throw new WriterException("Model " + firstModel.getModel() + " does not define an identity field");
		}
		DBUtil dbUtil = IOSystem.getActiveContext().getDbUtil();

		/*
		if(models[0].getModel().equals(ModelNames.MODEL_EVENT)) {
			logger.warn("***** Events: " + models.length);
		}
		*/
		DBStatementMeta meta = null;
	    try (Connection con = dataSource.getConnection()){
	    	List<Long> ids = new ArrayList<>();
	    	if(op == RecordOperation.CREATE) {
				ids = dbUtil.getNextIds(firstModel.getModel(), models.length);
				if(ids.size() != models.length) {
					throw new WriterException("Failed to obtain next identifier");
				}
		    }
			boolean lastCommit = con.getAutoCommit();
			con.setAutoCommit(false);
	    	
	    	meta = null;
	    	if(op == RecordOperation.CREATE) {
	    		meta = StatementUtil.getInsertTemplate(firstModel);
	    	}
	    	else {
	    		meta = StatementUtil.getUpdateTemplate(firstModel);
	    	}
	    	
	    	for(int i = 0; i < models.length; i++) {
	    		BaseRecord model = models[i];
	    		if(op == RecordOperation.CREATE) {
	    			applyAutoCreateList(model, op, autoCreate);
	    		}
	    	}
	    	processAutoCreate(autoCreate);
	    	
	    	PreparedStatement st = con.prepareStatement(meta.getSql());
	    	for(int i = 0; i < models.length; i++) {
	    		BaseRecord model = models[i];
	    		
	    		if(op == RecordOperation.CREATE) {
	    			long rid = ids.get(i);
	    			model.set(FieldNames.FIELD_ID, rid);
	    			// applyAutoCreateList(model, op, autoCreate);
	    			updateAutoCreateReference(op, schema, model, autoCreate);
	    		}
	    		
		    	StatementUtil.applyPreparedStatement(model, meta, st);
				st.addBatch();
				batch++;
				if(batch >= maxBatchSize || (batch > 0 && i == (models.length - 1))){
					processAutoCreate(autoCreate);
					st.executeBatch();
					st.clearBatch();
					writeCount += batch;
					// logger.info("Batch write: " + batch + "/" + writeCount +" of " + models.length);
					batch=0;
				}
	    	}
			st.close();
			con.commit();
			con.setAutoCommit(lastCommit);

		}
	    catch (SQLException | DatabaseException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			if(meta != null) {
				logger.error(meta.getSql());
			}
	    }
	    
		if(models[0].getModel().equals(ModelNames.MODEL_EVENT)) {
			logger.warn("***** " + op.toString() + " Events: " + models.length + " -> " + writeCount);

		}
		

		return writeCount;
	}
	
	private void updateAutoCreateReference(RecordOperation op, ModelSchema schema, BaseRecord model, Map<String, List<BaseRecord>> autoCreate) throws FieldException, ValueException, ModelNotFoundException {
		List<BaseRecord> parts = StatementUtil.getForeignParticipations(model);
		if(!autoCreate.containsKey(ModelNames.MODEL_PARTICIPATION)) {
			autoCreate.put(ModelNames.MODEL_PARTICIPATION, new ArrayList<>());
		}
		autoCreate.get(ModelNames.MODEL_PARTICIPATION).addAll(parts);
		List<FieldType> refList = model.getFields().stream().filter(o -> {
			FieldSchema fs = schema.getFieldSchema(o.getName());
			return (fs.isReferenced());

		}).collect(Collectors.toList());
		long rid = model.get(FieldNames.FIELD_ID);
		
		if(refList.size() > 0) {
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
							if(FieldUtil.isNullOrEmpty(erec.getModel(), erec.getField(FieldNames.FIELD_REFERENCE_ID))) {
								erec.set(FieldNames.FIELD_REFERENCE_ID, rid);
								erec.set(FieldNames.FIELD_REFERENCE_TYPE, model.getModel());
								if(erec.inherits(ModelNames.MODEL_ORGANIZATION_EXT)) {
									erec.set(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
								}
								if(!autoCreate.containsKey(erec.getModel())) {
									autoCreate.put(erec.getModel(), new ArrayList<>());
								}
								autoCreate.get(erec.getModel()).add(erec);
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
	}
	
	private void processAutoCreate(Map<String, List<BaseRecord>> autoCreate) {
	    autoCreate.forEach((k ,v) -> {
	    	if(v.size() > 0) {
	    		int created = IOSystem.getActiveContext().getRecordUtil().createRecords(v.toArray(new BaseRecord[0]));
	    		if(created != v.size()) {
	    			logger.error("Failed to auto create: " + k + " " + v.size() + ". Only created: " + created);
	    		}
	    	}
	    });
	    autoCreate.clear();
	}
	
	protected Map<String, List<BaseRecord>> getAutoCreateList(BaseRecord model, RecordOperation op){
		//List<BaseRecord> autoCreate = new ArrayList<>();
		Map<String, List<BaseRecord>> autoCreate = new HashMap<>();
		applyAutoCreateList(model, op ,autoCreate);
		return autoCreate;
	}
	protected void applyAutoCreateList(BaseRecord model, RecordOperation op, Map<String, List<BaseRecord>> autoCreate){
		ModelSchema schema = RecordFactory.getSchema(model.getModel());
		if(schema.isAutoCreateForeignReference()) {
			for(FieldType f : model.getFields()) {
				FieldSchema fs = schema.getFieldSchema(f.getName());
				List<BaseRecord> bfs = new ArrayList<>();
				if(fs.isForeign()) {
					if(f.getValueType() == FieldEnumType.MODEL) {
						bfs.add(model.get(f.getName()));
					}
					else if(f.getValueType() == FieldEnumType.LIST) {
						bfs = model.get(f.getName());
					}
					for(BaseRecord bf : bfs) {
						if(bf != null && !RecordUtil.isIdentityRecord(bf)) {
							/// logger.error("**** TODO: REMOVE THIS: Attempt to auto-write " + model.getModel() + "." + f.getName() + "? " +  RecordUtil.isIdentityRecord(bf));
							if(op == RecordOperation.CREATE) {
								// logger.info("*** Auto-creating foreign child: " + model.getModel() + "." + f.getName());
								try {
									bf.set(FieldNames.FIELD_OWNER_ID, model.get(FieldNames.FIELD_OWNER_ID));
									bf.set(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
									if(!autoCreate.containsKey(bf.getModel())) {
										autoCreate.put(bf.getModel(), new ArrayList<>());
									}
									autoCreate.get(bf.getModel()).add(bf);
								}
								catch(ValueException | FieldException | ModelNotFoundException e) {
									logger.error(e);
								}
							}
							else {
								logger.error("Will not update " + model.getModel() + " foreign child " + f.getName() + " without an identity reference");
							}
						}
					}
				}
				
			}
		}
	}
	
	public synchronized boolean write(BaseRecord model) throws WriterException {
		return (write(new BaseRecord[] {model}) == 1);
	}
	


}
