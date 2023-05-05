package org.cote.accountmanager.io;

import java.io.IOException;
import java.io.OutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.util.JSONUtil;

public class JsonWriter extends MemoryWriter {
	public static final Logger logger = LogManager.getLogger(JsonWriter.class);
	
	public JsonWriter() {
		super();
		this.recordIo = RecordIO.MEMORY;
	}
	
	@Override
	public synchronized boolean write(BaseRecord rec, OutputStream stream) throws WriterException {
		super.write(rec); 
		try {
			stream.write(JSONUtil.exportObject(rec, RecordSerializerConfig.getFilteredModule()).getBytes());
			stream.flush();
		} catch (IOException e) {
			throw new WriterException(e.getMessage());
		}
		return true;
	}
}
