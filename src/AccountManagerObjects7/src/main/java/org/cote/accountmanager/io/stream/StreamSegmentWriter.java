package org.cote.accountmanager.io.stream;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOFactory;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.model.field.CryptoBean;
import org.cote.accountmanager.model.field.VaultBean;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.StreamEnumType;
import org.cote.accountmanager.security.VaultService;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.StreamUtil;

import com.fasterxml.jackson.core.io.IOContext;

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
	public int write(BaseRecord[] recs) throws WriterException {
		throw new WriterException("Bulk segment write operations are not supported");
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
	
	public long writeSegment(BaseRecord segment) throws ModelException {
		BaseRecord stream = ssUtil.getStream(segment);
		long size = writeSegment(stream, segment);
		//ssUtil.updateStreamSize(stream);
		stream.setValue(FieldNames.FIELD_SIZE, size);
		BaseRecord streamU = stream.copyRecord(new String[] {FieldNames.FIELD_ID, FieldNames.FIELD_SIZE});
		try {
			IOSystem.getActiveContext().getWriter().write(streamU);
		} catch (WriterException e) {
			logger.error(e);
		}
		return size;
		
	}
	
	public long writeSegment(BaseRecord stream, BaseRecord segment) throws ModelException {
		StreamEnumType set = StreamEnumType.valueOf(stream.get(FieldNames.FIELD_TYPE));
		long totalSize = 0;
		switch(set) {
			case FILE:
				totalSize = writeFileSegment(stream, segment);
				break;
			default:
				throw new ModelException("Unhandled segment type: " + set.toString());
		}
		return totalSize;
	}

	
	
	private long writeFileSegment(BaseRecord stream, BaseRecord segment) throws ModelException {
		String path = ssUtil.getFileStreamPath(stream);
		if(ssUtil.isRestrictedPath(path)) {
			throw new ModelException("Path " + path + " is restricted");
		}
		FileUtil.makePath(path.substring(0, path.lastIndexOf("/")));
		long totalSize = 0L;
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
				channel.position(start);
				ByteBuffer buff = ByteBuffer.wrap(bytes);
				channel.write(buff);
				totalSize = bytes.length + start;
				lock.release();
				logger.info("Writing " + path + " bytes " + bytes.length + " at " + start + " total size " + totalSize);
            }
	    } catch (IOException e) {
			logger.error(e);
			e.printStackTrace();
		}

        return totalSize;
	}

	@Override
	public int delete(Query query) throws WriterException {
		throw new WriterException("Bulk delete operations based on a query are not supported");
	}

}
