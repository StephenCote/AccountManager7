package org.cote.accountmanager.provider;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.io.stream.StreamSegmentWriter;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.util.RecordUtil;

public class StreamProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(StreamProvider.class);
	
	public StreamProvider() {

	}
	
	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model)
			throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		
		if(!model.inherits(ModelNames.MODEL_STREAM)) {
			throw new ModelException("Model does not inherit from " + ModelNames.MODEL_STREAM);
		}
		if(!model.hasField(FieldNames.FIELD_TYPE)) {
			return;
		}
		if(model.getModel().equals(ModelNames.MODEL_STREAM) && (!model.hasField(FieldNames.FIELD_SEGMENTS) || ((List<BaseRecord>)model.get(FieldNames.FIELD_SEGMENTS)).size() == 0)) {
			return;
		}
		//if(!model.hasField(FieldNames.FIELD_OBJECT_ID)) {
		if(!RecordUtil.isIdentityRecord(model)) {
			if(operation == RecordOperation.CREATE) {
				throw new ModelException("Model " + model.getModel() + " does not include the " + FieldNames.FIELD_OBJECT_ID + " field.");
			}
			else {
				
				logger.warn("Skip segment write for model " + model.getModel() + " because it does not include the " + FieldNames.FIELD_OBJECT_ID + " field.");
				return;
			}
		}
		if(operation == RecordOperation.CREATE || operation == RecordOperation.UPDATE) {
			StreamEnumType set = StreamEnumType.valueOf(model.get(FieldNames.FIELD_TYPE));
			switch(set) {
				case FILE:
					writeSegments(model);
					break;
				default:
					logger.error("UNHANDLED STREAM TYPE: " + set.toString());
					break;
			}
			// logger.info(model.toFullString());
		}
		else if(operation == RecordOperation.READ) {
			/*
			logger.info("***** Read segment");
			StackTraceElement[] st = new Throwable().getStackTrace();
			for(int i = 0; i < st.length; i++) {
				logger.error(st[i].toString());
			}
			*/
		}
		else if(operation == RecordOperation.INSPECT) {
			// logger.info("Skip inspect segment");
		}

	}
	

	
	private void writeSegments(BaseRecord stream) throws ModelException {
		ModelSchema ms = RecordFactory.getSchema(ModelNames.MODEL_STREAM_SEGMENT);
		if(ms.getIo() == null) {
			throw new ModelException("Model " + ms.getName() + " does not define a specialized IO");
		}
		
		StreamSegmentUtil ssu = new StreamSegmentUtil();
		String streamSource = ssu.getFileStreamPath(stream);
		if(ssu.isRestrictedPath(streamSource)) {
			logger.warn("Will not write to a restricted location: " + streamSource);
			return;
		}
		
		StreamSegmentWriter ssw = RecordFactory.getClassInstance(ms.getIo().getWriter());
		if(ssw == null) {
			throw new ModelException("Invalid Model IO Writer: " + ms.getIo().getWriter());
		}

		List<BaseRecord> segments = stream.get(FieldNames.FIELD_SEGMENTS);
		if(segments.size() > 0) {
			try {
				stream.set(FieldNames.FIELD_SEGMENTS, new ArrayList<BaseRecord>());
			} catch (FieldException | ValueException | ModelNotFoundException e) {
				logger.error(e);
			}
			for(BaseRecord segment : segments) {
				ssw.writeSegment(stream, segment);
			}
			logger.info("Calculating stream size");
			ssu.updateStreamSize(stream);
		}
	}

	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model,
			FieldSchema lfield, FieldType field)
			throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		// Nothing to do

	}

}
