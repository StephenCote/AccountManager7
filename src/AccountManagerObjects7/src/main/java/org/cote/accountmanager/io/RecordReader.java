package org.cote.accountmanager.io;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.record.RecordTranslator;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;

public abstract class RecordReader extends RecordTranslator implements IReader {
	public static final Logger logger = LogManager.getLogger(RecordReader.class);
	public abstract BaseRecord read(BaseRecord rec) throws ReaderException;
	public abstract BaseRecord read(String model, String objectId) throws ReaderException;
	public abstract BaseRecord read(String model, long id) throws ReaderException;
	public abstract void translate(RecordOperation operation, BaseRecord rec);
    private static final String[] FK_FIELDS = new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_PARENT_ID, FieldNames.FIELD_PATH};
    
	/// Null or empty, return everything
    private String[] filterFields = null;
    /// For fields marked as foreign, specify the fields to return
    /// Not specifying this will lead to any subordinate foreign fields being included, which can quickly make for a large object
    /// TODO - Need to add recursion checking to prevent repetition
    ///
	private String[] filterForeignFields = FK_FIELDS;
	
	public RecordReader() {

	}
	
	public BaseRecord readAlternate(BaseRecord rec) {
		ModelSchema ms = RecordFactory.getSchema(rec.getModel());
		if(ms != null && ms.getIo() != null && ms.getIo().getReader() != null) {
			IReader altReader = RecordFactory.getClassInstance(ms.getIo().getReader());
			try {
				rec = altReader.read(rec);
			} catch (ReaderException e) {
				logger.error(e);
			}
		}
		else {
			logger.error("Model schema " + rec.getModel() + " does not define an alternate reader interface");
		}
		return rec;
	}
	
	public boolean useAlternateIO(BaseRecord rec) {
		ModelSchema ms = RecordFactory.getSchema(rec.getModel());
		boolean outBool = false;
		if(ms != null && ms.getIo() != null && ms.getIo().getSearch() != null) {
			outBool = true;
		}
		return outBool;
	}
	
	/// Note: URN is intentionally excluded from populate to allow for ephemeral instances to pass through without attempting to lookup
	///
	public void populate(BaseRecord rec) {
		populate(rec, 1);
	}
	public void populate(BaseRecord rec, int foreignDepth) {
		if(rec == null) {
			return;
		}
		if(rec.inherits(ModelNames.MODEL_POPULATE)) {
			boolean pop = false;
			if(rec.hasField(FieldNames.FIELD_POPULATED)) {
				pop = rec.get(FieldNames.FIELD_POPULATED);
			}
			if(!pop) {
				try {
					/// Clear any cache when using FILE IO
					/// It is currently using the record-based cache pattern vs. the query based cache pattern, so it won't open the source for any additional fields
					///
					if(IOSystem.getActiveContext().getIoType() == RecordIO.FILE) {
						CacheUtil.clearCache(rec);
					}
					final BaseRecord frec;
					if(rec.hasField(FieldNames.FIELD_ID)) {
						long id = rec.get(FieldNames.FIELD_ID);
						if(id > 0L) {
							frec = this.read(rec.getModel(), (long)rec.get(FieldNames.FIELD_ID));
						}
						else {
							frec = null;
						}
					}
					else if(rec.hasField(FieldNames.FIELD_OBJECT_ID)) {
						String objectId = rec.get(FieldNames.FIELD_OBJECT_ID);
						if(objectId != null) {
							frec = this.read(rec.getModel(), objectId);
						}
						else {
							frec = null;
						}
					}
					else {
						frec = null;
					}
					if(frec != null) {
						//logger.info("Populate: " + frec.getModel() + " " + frec.getFields().size());
						frec.getFields().forEach(f -> {
							try {
								///  && !frec.getField(f.getName()).isNullOrEmpty()
								if(
									
									!rec.hasField(f.getName())
									||
									rec.getField(f.getName()).isNullOrEmpty(rec.getModel())
									||
									(f.getValueType() == FieldEnumType.ENUM && "UNKNOWN".equals(rec.get(f.getName())))
									
								){
									rec.set(f.getName(), frec.get(f.getName()));
								}
								else {
									// logger.warn("Skip populate: " + f.getName());
								}
								if(rec.getField(f.getName()).getValueType() == FieldEnumType.MODEL && foreignDepth > 0) {
									populate(rec.get(f.getName()), foreignDepth - 1);
								}
							} catch (FieldException | ValueException | ModelNotFoundException e) {
								logger.error(e);
								
							}
						});
						rec.set(FieldNames.FIELD_POPULATED, true);
					}
					else {
						rec.set(FieldNames.FIELD_POPULATED, true);
					}
					MemoryReader mread = new MemoryReader();
					mread.read(rec);

				}
				catch(ReaderException | FieldException | ValueException | ModelNotFoundException e) {
					logger.error(e);
					
				}
				
			}
			else {
				// logger.warn("Record already populated");
			}

		}
		else {
			logger.warn("Not a populatable record: " + rec.getModel());
		}
	}
	
	public String[] getFilterFields() {
		return filterFields;
	}
	public String[] getFilterForeignFields() {
		return filterForeignFields;
	}
	public void setFilterFields(String[] filterFields) {
		this.filterFields = filterFields;
	}
	public void setFilterForeignFields(String[] filterForeignFields) {
		this.filterForeignFields = filterForeignFields;
	}
	public void prepareTranslation(RecordOperation operation, BaseRecord model) {
		
		ModelSchema lbm = RecordFactory.getSchema(model.getModel());
		
		lbm.getFields().forEach(f->{
			translateField(operation, this.recordIo, lbm, model, f, model.getField(f.getName()));
		});
		
		translateModel(operation, this.recordIo, lbm, model);
	}

}
