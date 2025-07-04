package org.cote.accountmanager.io;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.RecordUtil;

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
		ModelSchema ms = RecordFactory.getSchema(rec.getSchema());
		if(ms != null && ms.getIo() != null && ms.getIo().getReader() != null) {
			IReader altReader = RecordFactory.getClassInstance(ms.getIo().getReader());
			try {
				rec = altReader.read(rec);
			} catch (ReaderException e) {
				logger.error(e);
			}
		}
		else {
			logger.error("Model schema " + rec.getSchema() + " does not define an alternate reader interface");
		}
		return rec;
	}
	
	public boolean useAlternateIO(BaseRecord rec) {
		ModelSchema ms = RecordFactory.getSchema(rec.getSchema());
		boolean outBool = false;
		if(ms != null && ms.getIo() != null && ms.getIo().getSearch() != null) {
			outBool = true;
		}
		return outBool;
	}
	
	public void repopulate(BaseRecord rec, int foreignDepth) {
		if(rec.inherits(ModelNames.MODEL_POPULATE)) {
			try {
				rec.set(FieldNames.FIELD_POPULATED, false);
				List<String> popFields = rec.get(FieldNames.FIELD_POPULATED_FIELDS);
				popFields.clear();
				populate(rec, new String[0], foreignDepth);
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
		}
	}
	
	public void conditionalPopulate(BaseRecord rec, String[] requestFields) {
		if(rec == null || !RecordUtil.isIdentityRecord(rec)) {
			return;
		}
		if(rec.inherits(ModelNames.MODEL_POPULATE)) {
			List<String> crf = new ArrayList<>();
			for(String f : requestFields) {
				if(
					!rec.hasField(f)
					||
					rec.getField(f).isNullOrEmpty(rec.getSchema())
					||
					(rec.getField(f).getValueType() == FieldEnumType.ENUM && "UNKNOWN".equals(rec.get(f)))
				){
					crf.add(f);
				}
			}
			if(crf.size() > 0) {
				populate(rec, crf.toArray(new String[0]));
			}
		}
	
	}
	/// Note: URN is intentionally excluded from populate to allow for ephemeral instances to pass through without attempting to lookup
	///
	public void populate(BaseRecord rec) {
		populate(rec, 1);
	}
	public void populate(BaseRecord rec, String[] requestFields) {
		populate(rec, requestFields, 1);
	}

	public void populate(BaseRecord rec, int foreignDepth) {
		populate(rec, new String[0], foreignDepth);
	}
	
	/// NOTE: Requesting a field will cause that field to be re-read, which will be an IO hit on large datasets
	/// Use conditionalPopulate to perform an pre-check

	public void populate(BaseRecord rec, String[] requestFields, int foreignDepth) {
		if(rec == null || !RecordUtil.isIdentityRecord(rec)) {
			return;
		}
		if(rec.inherits(ModelNames.MODEL_POPULATE)) {
			List<String> populatedFields = rec.get(FieldNames.FIELD_POPULATED_FIELDS);
			boolean pop = false;
			boolean fullPop = false;
			if(requestFields.length > 0) {
				Set<String> pmap = Set.of(requestFields);
				List<String> filt = populatedFields.stream().filter(o -> pmap.contains(o)).collect(Collectors.toList());
				pop = filt.size() == requestFields.length;
			}
			else {
				pop = rec.get(FieldNames.FIELD_POPULATED);
			}

			if(!pop) {
				ModelSchema ms = RecordFactory.getSchema(rec.getSchema());
				try {
					/// Clear any cache when using FILE IO
					/// It is currently using the record-based cache pattern vs. the query based cache pattern, so it won't open the source for any additional fields
					///
					if(IOSystem.getActiveContext().getIoType() == RecordIO.FILE) {
						CacheUtil.clearCache(rec);
					}
					final BaseRecord frec = IOSystem.getActiveContext().getRecordUtil().findByRecord(null, rec, requestFields);

					if(frec != null) {
						frec.getFields().forEach(f -> {
							FieldSchema fs = ms.getFieldSchema(f.getName());
							try {
								if(
									
									!rec.hasField(f.getName())
									||
									rec.getField(f.getName()).isNullOrEmpty(rec.getSchema())
									||
									(f.getValueType() == FieldEnumType.ENUM && "UNKNOWN".equals(rec.get(f.getName())))
									
								){
									if(fs.getType().intern().toUpperCase().equals(FieldEnumType.FLEX.toString())) {
										rec.setFlex(f.getName(), frec.get(f.getName()));
									}
									else {
										rec.set(f.getName(), frec.get(f.getName()));
									}
									if(!populatedFields.contains(f.getName())) {
										populatedFields.add(f.getName());
									}
								}
								else {
									// logger.warn("Skip populate: " + f.getName());
								}

								// && limit == true
								if((f.getValueType() == FieldEnumType.MODEL || f.getValueType() == FieldEnumType.LIST) && foreignDepth > 0 ) {
									
									
									if(f.getValueType() == FieldEnumType.MODEL) {
										populate(rec.get(f.getName()), foreignDepth - 1);
									}
									else if(f.getValueType() == FieldEnumType.LIST && fs.getBaseType().equals(ModelNames.MODEL_MODEL)) {
										
										List<BaseRecord> frecs = rec.get(f.getName());
										for(BaseRecord f1 : frecs) {
											populate(f1, foreignDepth - 1);
										}
										
									}
								}
								
							} catch (FieldException | ValueException | ModelNotFoundException e) {
								logger.error(e);
								e.printStackTrace();
								
							}
						});
						if(requestFields.length == 0) {
							rec.set(FieldNames.FIELD_POPULATED, true);
						}
					}
					else {
						rec.set(FieldNames.FIELD_POPULATED, true);
					}
					// MemoryReader mread = new MemoryReader();
					// mread.read(rec);

				}
				catch(FieldException | ValueException | ModelNotFoundException  e) {
					logger.error(e);
					
				}
				
			}
			else {
				// logger.warn("Record already populated");
			}

		}
		else {
			logger.debug("Not a populatable record: " + rec.getSchema());
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
		
		ModelSchema lbm = RecordFactory.getSchema(model.getSchema());
		
		//lbm.getFields().forEach(f->{
		/// Use a sorted list of the fields based on the field provider priority
		lbm.getFields().stream().sorted(Comparator.comparingInt(FieldSchema::getPriority)).collect(Collectors.toList()).forEach( f -> {
			if(f.isVirtual() || model.hasField(f.getName())) {
				translateField(operation, this.recordIo, lbm, model, f, model.getField(f.getName()));
			}
		});
		
		translateModel(operation, this.recordIo, lbm, model);
	}

}
