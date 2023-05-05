package org.cote.accountmanager.io.file;

import java.util.List;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.util.TypeUtil;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class Index extends LooseRecord {
	
	@JsonIgnore
	private int changeCount = 0;
	@JsonIgnore
	private String path = null;
	
	public Index() {
		try {
			RecordFactory.newInstance(ModelNames.MODEL_INDEX2, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			/// ignore
		}
	}
	
	public Index(BaseRecord rec){
		this.setModel(rec.getModel());
		setFieldList(rec.getFields());
		setFieldMap(rec.getFieldMap());
	}
	
	public int getChangeCount() {
		return changeCount;
	}
	public void resetChangeCount() {
		this.changeCount = 0;
	}
	public void incrementChangeCount() {
		this.changeCount++;
	}
	
	public long nextId() {
		incrementChangeCount();
		long id = get(FieldNames.FIELD_LAST_ID);
		try {
			set(FieldNames.FIELD_LAST_ID, id + 1);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return id + 1;
	}
	


	public long getLastId() {
		return get(FieldNames.FIELD_LAST_ID);
	}

	public void setLastId(long lastId) {
		try {
			set(FieldNames.FIELD_LAST_ID, lastId);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public List<IndexEntry> getEntries() {
		return TypeUtil.convertRecordList(get(FieldNames.FIELD_ENTRIES), IndexEntry.class);
	}
	public void setEntries(List<IndexEntry> entries) {
		try {
			set(FieldNames.FIELD_ENTRIES, entries);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
	}
	
}
