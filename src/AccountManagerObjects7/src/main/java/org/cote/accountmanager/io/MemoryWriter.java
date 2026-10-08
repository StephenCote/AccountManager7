package org.cote.accountmanager.io;

import java.io.OutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.record.RecordValidator;
import org.cote.accountmanager.util.RecordUtil;

public class MemoryWriter extends RecordWriter {
	public static final Logger logger = LogManager.getLogger(MemoryWriter.class);
	
	public MemoryWriter() {
		super();
		this.recordIo = RecordIO.MEMORY;
	}

	/// Base implementation for every writer that does not override the batch form (FileWriter, JsonWriter):
	/// each record is written in turn. Until 2026-10-07 this wrote recs[0] on every iteration, so a batch of
	/// N distinct records translated the first one N times and never touched the rest (TestMemoryIO).
	@Override
	public int write(BaseRecord[] recs) throws WriterException {
		int writeCount = 0;
		for(int i = 0; i < recs.length; i++) {
			if(write(recs[i])) {
				writeCount++;
			}
		}
		return writeCount;
	}
	
	@Override
	public boolean write(BaseRecord rec) throws WriterException {
		RecordOperation op = RecordOperation.CREATE;
		if(RecordUtil.isIdentityRecord(rec)) {
			op = RecordOperation.UPDATE;
		}
		if((recordIo == RecordIO.FILE || recordIo == RecordIO.DATABASE) && !RecordValidator.validate(op, rec)) {
			if(rec != null) {
				logger.error(rec.toFullString());
			}
			throw new WriterException("Record failed validation in IO " + this.recordIo);
		}
		prepareTranscription(op, rec);

		return true;
	}

	@Override
	public boolean write(BaseRecord rec, OutputStream stream) throws WriterException {
		throw new WriterException(WriterException.NOT_IMPLEMENTED);
	}
	
	@Override
	public void translate(RecordOperation operation, BaseRecord rec) {
		this.prepareTranscription(operation, rec);
	}
	
	@Override
	public void flush() {
		
	}

	@Override
	public boolean delete(BaseRecord rec) throws WriterException {
		RecordOperation op = RecordOperation.DELETE;
		prepareTranscription(op, rec);
		return true;
	}

	@Override
	public boolean delete(BaseRecord rec, OutputStream stream) throws WriterException {
		throw new WriterException(WriterException.NOT_IMPLEMENTED);
	}

	/// The memory writer only runs record translation; it holds no store or stream, so there is nothing to close.
	/// DBWriter and FileWriter inherit this: they open and close their connection/statement per call.
	@Override
	public void close() throws WriterException {

	}

	@Override
	public int delete(Query query) throws WriterException {
		throw new WriterException(WriterException.NOT_IMPLEMENTED);
	}


}
