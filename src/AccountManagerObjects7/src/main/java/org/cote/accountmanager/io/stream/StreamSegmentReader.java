package org.cote.accountmanager.io.stream;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.StreamEnumType;

public class StreamSegmentReader implements IReader {
	public static final Logger logger = LogManager.getLogger(StreamSegmentReader.class);
	
	StreamSegmentUtil ssUtil = null;
	
	public StreamSegmentReader() {
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
	public void populate(BaseRecord rec) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void populate(BaseRecord rec, int foreignDepth) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void close() throws ReaderException {
		// TODO Auto-generated method stub
		
	}

	@Override
	public BaseRecord read(BaseRecord rec) throws ReaderException {
		BaseRecord orec = rec.copyRecord();
		try {
			readSegment(orec);
		} catch (ModelException e) {
			throw new ReaderException(e);
		}
		return orec;
	}
	
	public void readSegment(BaseRecord segment) throws ModelException {
		readSegment(ssUtil.getStream(segment), segment);
	}
	
    public void readSegment(BaseRecord stream, BaseRecord segment) throws ModelException {
    	if(stream == null || segment == null) {
    		throw new ModelException("Stream or segment is null");
    	}
		StreamEnumType set = StreamEnumType.valueOf(stream.get(FieldNames.FIELD_TYPE));
		switch(set) {
			case FILE:
				readFileSegment(stream, segment);
				break;
			default:
				throw new ModelException("Unhandled segment type: " + set.toString());
		}
    	
    }
    
    public void readFileSegment(BaseRecord stream, BaseRecord segment) throws ModelException {
    	
    	// logger.info("Read file segment: ");
    	// logger.info(segment.toFullString());
    	
		String path = ssUtil.getFileStreamPath(stream);
		if(ssUtil.isRestrictedPath(path)) {
			throw new ModelException("Path " + path + " is restricted");
		}
	
        ByteBuffer buffer = null;
 
        try (
        	RandomAccessFile reader = new RandomAccessFile(path, "r");
        	FileChannel fc = reader.getChannel();
        ){
        	
        	long startPosition = segment.get(FieldNames.FIELD_START_POSITION);
        	long length = segment.get(FieldNames.FIELD_LENGTH);
        	long size = fc.size();
        	// logger.info("Read " + startPosition + " to " + (startPosition + length));
        	long maxLen = Math.min(size - startPosition, length);
        	if(length == 0 && maxLen <= 0) {
        		maxLen = size - startPosition;
        	}
        	// logger.info("Read " + startPosition + " to " + maxLen);
            fc.position(startPosition);
            buffer = ByteBuffer.allocate((int)maxLen);
 
           while (buffer.hasRemaining()) {
        	   fc.read(buffer);
           }
           
           byte[] ba = buffer.array();
           segment.set(FieldNames.FIELD_READ, true);
           segment.set(FieldNames.FIELD_SIZE, (long)ba.length);
           segment.set(FieldNames.FIELD_STREAM, ba);
 
        } catch (IOException | FieldException | ValueException | ModelNotFoundException e) {
            logger.error(e);

            e.printStackTrace();
        }
    }

	@Override
	public BaseRecord inspect(BaseRecord rec) throws ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord read(String model, String objectId) throws ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord readByUrn(String model, String urn) throws ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public BaseRecord read(String model, long id) throws ReaderException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void translate(RecordOperation operation, BaseRecord rec) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void populate(BaseRecord rec, String[] requestFields) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void populate(BaseRecord rec, String[] requestFields, int foreignDepth) {
		// TODO Auto-generated method stub
		
	}

}
