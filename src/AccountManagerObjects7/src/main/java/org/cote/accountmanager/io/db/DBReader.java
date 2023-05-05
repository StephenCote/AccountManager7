package org.cote.accountmanager.io.db;

import javax.sql.DataSource;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.JsonReader;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;

public class DBReader extends JsonReader {
	public static final Logger logger = LogManager.getLogger(DBReader.class);
	private DataSource dataSource = null;
	public DBReader(DataSource dsource) {
		super();
		this.recordIo = RecordIO.DATABASE;
		this.dataSource = dsource;
	}

	public DataSource getDataSource() {
		return dataSource;
	}

	public void flush() {

	}

	@Override
	public synchronized BaseRecord read(String model, long id) throws ReaderException {
		return IOSystem.getActiveContext().getSearch().findRecord(QueryUtil.createQuery(model, FieldNames.FIELD_ID, id));
		
	}
	
	@Override
	public synchronized BaseRecord read(String model, String objectId) throws ReaderException {
		return IOSystem.getActiveContext().getSearch().findRecord(QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, objectId));
	}
	
	@Override
	public synchronized BaseRecord readByUrn(String model, String urn) throws ReaderException {
		return IOSystem.getActiveContext().getSearch().findRecord(QueryUtil.createQuery(model, FieldNames.FIELD_URN, urn));
	}
	
}
