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
import org.cote.accountmanager.util.StreamUtil;

/// The specialized reader declared by data.streamSegment's "io" block. A segment is addressed by
/// streamId + startPosition + length and read straight out of the stream's backing file, so the only
/// meaningful entry point is read(BaseRecord) (reached via RecordReader.readAlternate). The IReader
/// methods that assume a record store with identities and foreign fields are explicit "not supported"
/// or documented no-ops - never a silent null.
public class StreamSegmentReader implements IReader {
	public static final Logger logger = LogManager.getLogger(StreamSegmentReader.class);

	StreamSegmentUtil ssUtil = null;

	public StreamSegmentReader() {
		ssUtil = new StreamSegmentUtil();
	}

	/// Nothing is buffered between reads.
	@Override
	public void flush() {

	}

	/// Segments are file-backed; this is not the record-level FILE IO (FileReader), but it is the honest answer.
	@Override
	public RecordIO getRecordIo() {
		return RecordIO.FILE;
	}

	/// data.streamSegment has no foreign fields, so there is nothing to populate at any depth.
	@Override
	public void populate(BaseRecord rec) {

	}

	@Override
	public void populate(BaseRecord rec, int foreignDepth) {

	}

	/// No channel outlives a single read call, so there is nothing to close.
	@Override
	public void close() throws ReaderException {

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
    	
		String path = ssUtil.getFileStreamPath(stream);
		if(ssUtil.isRestrictedPath(path)) {
			throw new ModelException("Path " + path + " is restricted");
		}
		
		StreamUtil.unboxStream(stream, false);
		
        ByteBuffer buffer = null;
 
        try (
        	RandomAccessFile reader = new RandomAccessFile(path, "r");
        	FileChannel fc = reader.getChannel();
        ){
        	
        	long startPosition = segment.get(FieldNames.FIELD_START_POSITION);
        	long length = segment.get(FieldNames.FIELD_LENGTH);
        	long size = fc.size();

        	long maxLen = Math.min(size - startPosition, length);
        	if(length == 0 && maxLen <= 0) {
        		maxLen = size - startPosition;
        	}

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
        }

    }

	/// Inspect is read-without-side-effects: a segment carries no providers to run, so the record is returned
	/// as handed in (the same contract MemoryReader.inspect honours), never null.
	@Override
	public BaseRecord inspect(BaseRecord rec) throws ReaderException {
		if(rec == null) {
			throw new ReaderException("Null segment");
		}
		return rec;
	}

	/// Segments have no objectId, urn or id; they are addressed by streamId + startPosition + length through
	/// read(BaseRecord) or a data.streamSegment Query (StreamSegmentSearch).
	@Override
	public BaseRecord read(String model, String objectId) throws ReaderException {
		throw new ReaderException("Segments are not addressable by objectId; query by streamId, startPosition and length");
	}

	@Override
	public BaseRecord readByUrn(String model, String urn) throws ReaderException {
		throw new ReaderException("Segments are not addressable by urn; query by streamId, startPosition and length");
	}

	@Override
	public BaseRecord read(String model, long id) throws ReaderException {
		throw new ReaderException("Segments are not addressable by id; query by streamId, startPosition and length");
	}

	/// data.streamSegment declares no providers, so there is nothing to translate.
	@Override
	public void translate(RecordOperation operation, BaseRecord rec) {

	}

	/// data.streamSegment has no foreign fields, so every populate variant is a no-op.
	@Override
	public void populate(BaseRecord rec, String[] requestFields) {

	}

	@Override
	public void populate(BaseRecord rec, String[] requestFields, int foreignDepth) {

	}

	@Override
	public void repopulate(BaseRecord rec, int foreignDepth) {

	}

	@Override
	public void conditionalPopulate(BaseRecord rec, String[] requestFields) {

	}

}
