package org.cote.accountmanager.provider;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
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
import org.cote.accountmanager.util.FileUtil;

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
		if(!model.hasField(FieldNames.FIELD_OBJECT_ID)) {
			throw new ModelException("Model " + model.getModel() + " does not include the " + FieldNames.FIELD_OBJECT_ID + " field.");
		}
		if(operation == RecordOperation.CREATE || operation == RecordOperation.UPDATE) {
			logger.info("***** Create/Update with segments");
			StreamEnumType set = StreamEnumType.valueOf(model.get("type"));
			switch(set) {
				case FILE:
					// logger.info("Handle file stream write");
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
		/// String streamSource = stream.get(FieldNames.FIELD_STREAM_SOURCE);
		String streamSource = ssu.getFileStreamPath(stream);
		if(ssu.isRestrictedPath(streamSource)) {
			logger.warn("Will not write to a restricted location");
			return;
		}
		
		StreamSegmentWriter ssw = RecordFactory.getClassInstance(ms.getIo().getWriter());
		if(ssw == null) {
			throw new ModelException("Invalid Model IO Writer: " + ms.getIo().getWriter());
		}

		List<BaseRecord> segments = stream.get(FieldNames.FIELD_SEGMENTS);
		for(BaseRecord segment : segments) {
			ssw.writeSegment(stream, segment);
		}
		try {
			stream.set(FieldNames.FIELD_SIZE, getStreamSize(streamSource));
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			throw new ModelException(e);
		}
	}
	
	private long getStreamSize(String streamSource) {
			StreamSegmentUtil ssu = new StreamSegmentUtil();
			long size = 0L;
			try (
				RandomAccessFile writer = new RandomAccessFile(streamSource, "r");
				FileChannel channel = writer.getChannel()
			){
				size = channel.size();
            }
		    catch (IOException e) {
				logger.error(e);
				e.printStackTrace();
			}
			return size;

	}
	
	/*
	private void writeSegments(BaseRecord stream) throws ModelException {
		List<BaseRecord> segments = stream.get(FieldNames.FIELD_SEGMENTS);
		String path = getFileStreamPath(stream);
		if(isRestrictedPath(path)) {
			throw new ModelException("Path " + path + " is restricted");
		}
		FileUtil.makePath(path.substring(0, path.lastIndexOf("/")));
		long size = 0L;
		try (
			RandomAccessFile writer = new RandomAccessFile(path, "rw");
			FileChannel channel = writer.getChannel()
		){
            FileLock lock = channel.tryLock();

            while (!lock.isValid()) {
                lock = channel.tryLock();
            }
            if(lock.isValid()) {
				for(BaseRecord segment : segments) {
					long start = segment.get(FieldNames.FIELD_START_POSITION);
					if(start == 0L) {
						start = channel.size();
					}
					channel.position(start);
					ByteBuffer buff = ByteBuffer.wrap(segment.get(FieldNames.FIELD_STREAM));
					channel.write(buff);
					size = channel.size();
				}
				lock.release();
            }
	    } catch (IOException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}
	*/

	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model,
			FieldSchema lfield, FieldType field)
			throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		// Nothing to do

	}

}