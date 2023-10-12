package org.cote.accountmanager.io;

import java.io.OutputStream;

import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;

public interface IWriter {
	public void flush();
	public RecordIO getRecordIo();
	public void close() throws WriterException;
	public int write(BaseRecord[] recs) throws WriterException;
	public boolean write(BaseRecord rec) throws WriterException;
	public boolean write(BaseRecord rec, OutputStream stream) throws WriterException;
	public boolean delete(BaseRecord rec) throws WriterException;
	public boolean delete(BaseRecord rec, OutputStream stream) throws WriterException;
	public void translate(RecordOperation operation, BaseRecord rec);
}
