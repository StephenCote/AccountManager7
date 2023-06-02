package org.cote.accountmanager.io.stream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.util.FileUtil;

public class StreamSegmentWriter implements IWriter {

	public static final Logger logger = LogManager.getLogger(StreamSegmentWriter.class);
	
	StreamSegmentUtil ssUtil = null;
	
	public StreamSegmentWriter() {
		ssUtil = new StreamSegmentUtil();
	}
	
	@Override
	public void flush() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public RecordIO getRecordIo() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void close() throws WriterException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean write(BaseRecord rec) throws WriterException {
		// TODO Auto-generated method stub
		boolean outBool = false;
		try {
			writeSegment(rec);
			outBool = true;
		}
		catch(ModelException e) {
			logger.error(e);
		}
		return outBool;
	}

	@Override
	public boolean write(BaseRecord rec, OutputStream stream) throws WriterException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean delete(BaseRecord rec) throws WriterException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean delete(BaseRecord rec, OutputStream stream) throws WriterException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void translate(RecordOperation operation, BaseRecord rec) {
		// TODO Auto-generated method stub
		
	}
	
	/*
	public void writeSegments(BaseRecord stream) throws ModelException {
		logger.warn("REFACTOR THIS: DON'T WRITE SEGMENTS BEFORE THE STREAM OBJECT IS CREATED - MOVE TO A POST WRITE OPERATION");
		List<BaseRecord> segments = stream.get(FieldNames.FIELD_SEGMENTS);
		for(BaseRecord segment : segments) {
			writeSegment(stream, segment);
		}
	}
	*/
	public void writeSegment(BaseRecord segment) throws ModelException {
		BaseRecord stream = ssUtil.getStream(segment);
		writeSegment(stream, segment);
		ssUtil.updateStreamSize(stream);
		

		BaseRecord streamU = stream.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_SIZE});
		try {
			IOSystem.getActiveContext().getWriter().write(streamU);
		} catch (WriterException e) {
			logger.error(e);
		}
		
	}
	
	public void writeSegment(BaseRecord stream, BaseRecord segment) throws ModelException {
		StreamEnumType set = StreamEnumType.valueOf(stream.get(FieldNames.FIELD_TYPE));
		switch(set) {
			case FILE:
				writeFileSegment(stream, segment);
				break;
			default:
				throw new ModelException("Unhandled segment type: " + set.toString());
		}
	}
	
	private void writeFileSegment(BaseRecord stream, BaseRecord segment) throws ModelException {
		String path = ssUtil.getFileStreamPath(stream);
		if(ssUtil.isRestrictedPath(path)) {
			throw new ModelException("Path " + path + " is restricted");
		}
		FileUtil.makePath(path.substring(0, path.lastIndexOf("/")));
		// long size = 0L;
		try (
			RandomAccessFile writer = new RandomAccessFile(path, "rw");
			FileChannel channel = writer.getChannel()
		){
            FileLock lock = channel.tryLock();

            while (!lock.isValid()) {
                lock = channel.tryLock();
            }
            if(lock.isValid()) {
				long start = segment.get(FieldNames.FIELD_START_POSITION);
				if(start == 0L) {
					start = channel.size();
				}
				byte[] bytes = segment.get(FieldNames.FIELD_STREAM);
				logger.info("Writing " + stream.get(FieldNames.FIELD_NAME) + " bytes " + bytes.length + " at " + start);
				channel.position(start);
				ByteBuffer buff = ByteBuffer.wrap(bytes);
				channel.write(buff);
				// size = channel.size();

				lock.release();
            }
	    } catch (IOException e) {
			logger.error(e);
			e.printStackTrace();
		}
	}

}
