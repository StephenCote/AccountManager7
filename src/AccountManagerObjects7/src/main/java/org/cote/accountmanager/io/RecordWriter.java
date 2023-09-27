package org.cote.accountmanager.io;

import java.io.OutputStream;
import java.util.Comparator;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.record.RecordTranslator;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public abstract class RecordWriter extends RecordTranslator implements IWriter {
	public static final Logger logger = LogManager.getLogger(RecordWriter.class);
	
	public abstract boolean write(BaseRecord rec) throws WriterException;
	public abstract boolean write(BaseRecord rec, OutputStream stream) throws WriterException;
	public abstract void translate(RecordOperation operation, BaseRecord rec);
	public abstract void flush();
	public RecordWriter() {

	}
	
	protected void prepareTranscription(RecordOperation operation, BaseRecord model) {
		
		ModelSchema lbm = RecordFactory.getSchema(model.getModel());
		
		/// If the operation is a write, and the schema defines an identity field and that field is missing on the model, then add it back in
		/// This can happen when setting embedded properties (eg: property.property.property) where the default behavior isn't to automatically add all identity fields
		/// Simply invoking 'get' on the field will automatically populate it
		///
		if(operation == RecordOperation.CREATE) {
			for(FieldSchema fs : lbm.getFields()) {
				if(fs.isIdentity() && !model.hasField(fs.getName())) {
					logger.debug("Auto-inserting missing identity field: " + lbm.getName() + "." + fs.getName());
					model.get(fs.getName());
				}
			}
		}
		model.getFields().stream().sorted(Comparator.comparingInt(f -> lbm.getFieldSchema(f.getName()).getPriority())).collect(Collectors.toList()).forEach( f -> {
		//for(int i = 0; i < model.getFields().size(); i++) {
			//FieldType f = model.getFields().get(i);
			FieldSchema lf = lbm.getFieldSchema(f.getName());
			//logger.info("Translate field: " + recordIo.toString() + " " + operation.toString() + " "  + model.getModel() + " " + lf.getName());
			translateField(operation, this.recordIo, lbm, model, lf, f);
			//RecordValidator.validateField(operation, this.recordIo, lbm, model, lf, f);
		});
		translateModel(operation, this.recordIo, lbm, model);
	}
	

}
