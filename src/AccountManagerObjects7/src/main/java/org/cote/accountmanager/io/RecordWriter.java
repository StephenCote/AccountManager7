package org.cote.accountmanager.io;

import java.io.OutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.record.RecordTranslator;
import org.cote.accountmanager.record.RecordValidator;
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
		for(int i = 0; i < model.getFields().size(); i++) {
			FieldType f = model.getFields().get(i);
			FieldSchema lf = lbm.getFieldSchema(f.getName());
			//logger.info("Translate field: " + recordIo.toString() + " " + operation.toString() + " "  + model.getModel() + " " + lf.getName());
			translateField(operation, this.recordIo, lbm, model, lf, f);
			RecordValidator.validateField(operation, this.recordIo, lbm, model, lf, f);
		}
		translateModel(operation, this.recordIo, lbm, model);
	}
	

}
