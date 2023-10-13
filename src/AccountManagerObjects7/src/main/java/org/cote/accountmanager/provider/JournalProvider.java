package org.cote.accountmanager.provider;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.RecordUtil;

public class JournalProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(JournalProvider.class);
	
	
	/*
	 * Note: Identity fields are currently being included
	 */
	private static boolean INCLUDE_IDENTITY_FIELDS = true;
	
	private static final String[] protectedFields = new String[] {
			FieldNames.FIELD_JOURNALED,
			FieldNames.FIELD_JOURNAL_HASH,
			FieldNames.FIELD_JOURNAL_VERSION,
			FieldNames.FIELD_JOURNAL_ENTRIES
	}; 

	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		/// Nothing to do
	}
	
	public void provide(BaseRecord contextUser, RecordOperation operation,  ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException {

		if(!model.inherits(ModelNames.MODEL_JOURNAL_EXT)) {
			throw new ModelException(String.format(ModelException.INHERITENCE_EXCEPTION, model.getModel(), ModelNames.MODEL_JOURNAL_EXT));
		}
		
		if(!RecordOperation.CREATE.equals(operation) && !RecordOperation.UPDATE.equals(operation) && !RecordOperation.DELETE.equals(operation)) {
			return;
		}
	
		logger.info("Calculate journal entry for " + operation.toString() + " " + model.get(FieldNames.FIELD_NAME));
		try {
			if(RecordOperation.CREATE.equals(operation)) {
				logger.info("Prepare journal create");
				createJournalObject(model);
			}
			else if(RecordOperation.UPDATE.equals(operation)) {
				logger.info("Prepare journal update");
				updateJournalObject(model);;
			}
		}
		catch(Exception e) {
			logger.error(e);
		}
	}
	
	private void updateJournalObject(BaseRecord model) {
		BaseRecord jour1 = null;
		if(!model.hasField(FieldNames.FIELD_OBJECT_ID) && !model.hasField(FieldNames.FIELD_ID)) {
			logger.error("Model does not include an identity field");
			return;
		}
		if(!model.inherits(ModelNames.MODEL_JOURNAL_EXT)) {
			logger.error("Model does not use the journal interface");
			return;
		}
		if(model.hasField(FieldNames.FIELD_JOURNAL)) {
			jour1 = model.get(FieldNames.FIELD_JOURNAL);
		}
		if(jour1 == null) {
			jour1 = IOSystem.getActiveContext().getRecordUtil().findChildRecord(null, model, FieldNames.FIELD_JOURNAL);
		}
		if(jour1 != null && !jour1.hasField(FieldNames.FIELD_JOURNAL_ENTRIES)) {
			logger.warn("Loading complete journal model");
			CacheUtil.clearCache(jour1);
			jour1 = IOSystem.getActiveContext().getRecordUtil().findByRecord(null, jour1, new String[0]);
		}
		if(jour1 == null || !jour1.hasField(FieldNames.FIELD_JOURNAL_ENTRIES)) {
			logger.error("Unable to restore journal");
			if(jour1 != null) {
				logger.error(jour1.toString());
			}
			return;
		}
		
		Map<String, FieldType> baseLine = getBaseLine(jour1);
		ModelSchema lbm = RecordFactory.getSchema(model.getModel());
		List<FieldType> patchFields = new ArrayList<>();
		
		for(FieldType f : model.getFields()) {
			FieldSchema lbf = lbm.getFieldSchema(f.getName());
			
			if(INCLUDE_IDENTITY_FIELDS && lbf.isIdentity()) {
				continue;
			}
			if(lbf.isEphemeral() || lbf.isVirtual() || f.getName().equals(FieldNames.FIELD_JOURNAL)) {
				continue;
			}
			if(baseLine.containsKey(f.getName()) && baseLine.get(f.getName()).isEquals(f)) {
				continue;
			}
			if(f.isDefault(model.getModel())) {
				continue;
			}
			
			/// If the baseline value null and the incoming value is the default, then leave it out of the journal
			///
			if(f.getValueType() == FieldEnumType.ENUM) {
				String baseLineVal = null;
				if(baseLine.get(f.getName()) != null) {
					baseLineVal = baseLine.get(f.getName()).getValue();
				}
				String lineVal = f.getValue();
				if(baseLineVal == null && lineVal != null && lineVal.equals("UNKNOWN")) {
					continue;
				}
			}
			patchFields.add(f);
		}
		if(patchFields.size() > 0) {
			
			try {
				BaseRecord patchModel = RecordFactory.newInstance(model.getModel());
				patchModel.setFields(patchFields);
				journalFields(jour1, patchModel);
				logger.info("Updating journal");
				IOSystem.getActiveContext().getRecordUtil().updateRecord(jour1);
			} catch (FieldException | ModelNotFoundException e) {
				logger.error(e);
				
			}
			
		}
		else {
			logger.error("No fields identified needing to be patched");
		}
	}
	
	private Map<String, FieldType> getBaseLine(BaseRecord journal){
		List<BaseRecord> entries = journal.get(FieldNames.FIELD_JOURNAL_ENTRIES);
		Map<String, FieldType> baseLine = new HashMap<>();
		for(int i = entries.size() - 1; i >= 0; i--) {
			BaseRecord e = entries.get(i);
			BaseRecord emod = e.get(FieldNames.FIELD_JOURNAL_ENTRY_MODIFIED);
			emod.getFields().forEach(f -> {
				if(!baseLine.containsKey(f.getName())) {
					baseLine.put(f.getName(), f);
				}
			});
		}
		
		return baseLine;
	}
	
	private void createJournalObject(BaseRecord model)  {
		try {
			BaseRecord jour1 = RecordFactory.newInstance(ModelNames.MODEL_JOURNAL);
			BaseRecord copyMod = model.copyRecord();
			copyMod.set(FieldNames.FIELD_JOURNAL, null);
			
			jour1.set(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
			jour1.set(FieldNames.FIELD_OWNER_ID, model.get(FieldNames.FIELD_OWNER_ID));

			jour1.set(FieldNames.FIELD_JOURNALED, true);
			jour1.set(FieldNames.FIELD_JOURNAL_VERSION, 1.0);
			model.set(FieldNames.FIELD_JOURNAL, jour1);
			
			journalFields(jour1, copyMod);

			if(!IOSystem.getActiveContext().getRecordUtil().createRecord(jour1)) {
				logger.error("Failed to create journal object");
			}
		}
		catch(NullPointerException | FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
			
			
		}
	}
	
	private void journalFields(BaseRecord journal, BaseRecord model) throws FieldException, ModelNotFoundException {
		List<BaseRecord> entries = journal.get(FieldNames.FIELD_JOURNAL_ENTRIES);
		BaseRecord entry = RecordFactory.newInstance(ModelNames.MODEL_JOURNAL_ENTRY);
		List<String> fieldNames = entry.get(FieldNames.FIELD_FIELDS);
		
		RecordUtil.sortFields(model);
		ModelSchema lbm = RecordFactory.getSchema(model.getModel());
		//logger.info(model.toFullString());
		for(FieldType f : model.getFields()) {
			FieldSchema lbf = lbm.getFieldSchema(f.getName());
			if(lbf.isEphemeral() || lbf.isVirtual() || f.getName().equals(FieldNames.FIELD_JOURNAL)) {
				continue;
			}
			/*
			if(FieldUtil.isNullOrEmpty(model.getModel(), f)) {
				continue;
			}
			*/
			fieldNames.add(f.getName());
				
		}
		try {
			entry.set(FieldNames.FIELD_JOURNAL_ENTRY_DATE, new Date());
			entry.set(FieldNames.FIELD_JOURNAL_ENTRY_MODIFIED, model);
			entry.set(FieldNames.FIELD_HASH, model.hash(fieldNames.toArray(new String[0])).getBytes());
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		entries.add(entry);
	}

	
}
