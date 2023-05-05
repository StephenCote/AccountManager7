package org.cote.accountmanager.io;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.util.JSONUtil;

public class JsonReader extends MemoryReader {
	public static final Logger logger = LogManager.getLogger(JsonReader.class);
	
	
	public JsonReader() {
		super();
	}
	
	@Override
	public synchronized BaseRecord read(String model, String contents) throws ReaderException {
		BaseRecord rec = JSONUtil.importObject(contents, LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
		if(rec == null) {
			logger.error("Failed to deserialize");
			return null;
		}
		return super.read(rec);
	}

}
