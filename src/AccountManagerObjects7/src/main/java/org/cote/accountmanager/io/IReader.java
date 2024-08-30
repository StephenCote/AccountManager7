package org.cote.accountmanager.io;

import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;

public interface IReader {
	public void flush();
	public RecordIO getRecordIo();
	public void conditionalPopulate(BaseRecord rec, String[] requestFields);
	public void populate(BaseRecord rec);
	public void populate(BaseRecord rec, int foreignDepth);
	public void populate(BaseRecord rec, String[] requestFields);
	public void populate(BaseRecord rec, String[] requestFields, int foreignDepth);
	public void repopulate(BaseRecord rec, int foreignDepth);
	public void close() throws ReaderException;
	public BaseRecord read(BaseRecord rec) throws ReaderException;
	public BaseRecord inspect(BaseRecord rec) throws ReaderException;
	public BaseRecord read(String model, String objectId) throws ReaderException;
	public BaseRecord readByUrn(String model, String urn) throws ReaderException;
	public BaseRecord read(String model, long id) throws ReaderException;
	public void translate(RecordOperation operation, BaseRecord rec);
}
