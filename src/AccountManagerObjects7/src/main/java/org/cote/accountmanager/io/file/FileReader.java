package org.cote.accountmanager.io.file;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.StoreException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.JsonReader;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;

public class FileReader extends JsonReader {
	public static final Logger logger = LogManager.getLogger(FileReader.class);
	private boolean useFileStore = false;
	private FileStore store = null;
	private boolean useStoreIndex = false;
	private boolean followForeignKeys = false;
	
	public FileReader(String base) {
		super();
		this.storageBase = base;
		this.recordIo = RecordIO.FILE;
		
	}
	
	public void setFollowForeignKeys(boolean followForeignKeys) {
		this.followForeignKeys = followForeignKeys;
	}

	public boolean isUseFileStore() {
		return useFileStore;
	}
	public void setStore(FileStore store) throws StoreException {
		this.store = store;
		if(store != null) {
			if(!store.isInitialized()) {
				throw new StoreException(StoreException.STORE_NOT_INITIALIZED);
			}
		}
		setUseFileStore(store != null);
	}
	private void setUseFileStore(boolean useFileStore) {
		this.useFileStore = useFileStore;
		this.useStoreIndex = useFileStore;
	}
	
	public void flush() {
		IOSystem.getActiveContext().getIndexManager().flush();
	}

	public FileStore getStore() {
		return store;
	}

	public boolean isUseStoreIndex() {
		return useStoreIndex;
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
	
	protected synchronized BaseRecord read(IndexEntry entry) throws ReaderException {
		
		String contents = null;
		if(useFileStore) {
			contents = new String(store.get(store.getStoreName(entry)));
		}
		else {
			String path = storageBase + "/" + FilePathUtil.getFilePath(entry);
			contents = FileUtil.getFileAsString(path);
			if(contents == null || contents.length() == 0) {
				logger.error("Failed to load " + path);
				return null;
			}
		}

		BaseRecord rec = JSONUtil.importObject(contents, LooseRecord.class, RecordDeserializerConfig.getForeignModule((followForeignKeys ? null : this.getFilterForeignFields())));
		if(rec == null) {
			logger.error("Failed to deserialize");
			return null;
		}
		return super.read(rec);

	}
	
}
