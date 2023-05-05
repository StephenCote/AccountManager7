package org.cote.accountmanager.io;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;

public class MemoryReader extends RecordReader {
	public static final Logger logger = LogManager.getLogger(MemoryReader.class);
	
	
	public MemoryReader() {
		super();
		this.recordIo = RecordIO.MEMORY;
	}
	
	public synchronized BaseRecord read(BaseRecord rec) throws ReaderException {
		prepareTranslation(RecordOperation.READ, rec);
		return rec;
	}

	@Override
	public BaseRecord read(String model, String objectId) throws ReaderException {
		throw new ReaderException(ReaderException.NOT_IMPLEMENTED);
	}
	
	@Override
	public BaseRecord read(String model, long id) throws ReaderException {
		throw new ReaderException(ReaderException.NOT_IMPLEMENTED);
	}

	@Override
	public void translate(RecordOperation operation, BaseRecord rec) {
		prepareTranslation(operation, rec);
	}
	
	@Override
	public void flush() {
		
	}

	@Override
	public BaseRecord readByUrn(String model, String urn) throws ReaderException {
		throw new ReaderException(ReaderException.NOT_IMPLEMENTED);
	}

	@Override
	public void close() throws ReaderException {
		// TODO Auto-generated method stub
		
	}

}
