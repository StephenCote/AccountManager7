package org.cote.accountmanager.io.file;

import java.io.File;
import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.StoreException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.MemoryWriter;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;

public class FileWriter extends MemoryWriter {
	public static final Logger logger = LogManager.getLogger(FileWriter.class);
	private boolean useFileStore = false;
	private boolean useStoreIndex = false;
	private FileStore store = null;

	private boolean useForeignKey = true;
	public FileWriter(String base) {
		super();
		storageBase = base;
		this.recordIo = RecordIO.FILE;
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
	
	@Override
	public void flush() {
		IOSystem.getActiveContext().getIndexManager().flush();
	}
	
	public FileStore getStore() {
		return store;
	}

	public boolean isUseStoreIndex() {
		return useStoreIndex;
	}
	/*
	public FileIndexer getIndexer() {
		if(indexer == null) {
			indexer = FileIndexer.getInstance("all", storageBase);
		}
		return indexer;
	}
	*/
	protected void prepareWrite(IndexEntry entry, String path, String oldPath) {
		if(oldPath != null && !oldPath.equals(path)) {
			logger.warn("Clean up old save: " + oldPath + " --> " + path);
			/*
			StackTraceElement[] st = new Throwable().getStackTrace();
			for(int i = 0; i < st.length; i++) {
				logger.error(st[i].toString());
			}
			*/
			File fd = new File(oldPath);
			if(!fd.delete()) {
				logger.error("Failed to delete old file: " + oldPath);
			}
		}
	}
	
	protected void prepareDelete(IndexEntry entry, String path) {
		
	}

	public synchronized boolean delete(BaseRecord model) throws WriterException {
		//RecordOperation op = RecordOperation.DELETE;
		boolean outBool = false;
		
		String objectId = (model.hasField(FieldNames.FIELD_OBJECT_ID) ? model.get(FieldNames.FIELD_OBJECT_ID) : null);
		long id = (model.hasField(FieldNames.FIELD_ID) ? model.get(FieldNames.FIELD_ID) : 0L);

		if(objectId == null && id <= 0L) {
			throw new WriterException(String.format(IndexException.NOT_INDEXABLE, model.getModel() + " model to delete", "id or objectId"));
		}
	
		try {
			FileIndexer fix = IOSystem.getActiveContext().getIndexManager().getInstance(model.getModel());
			//IndexEntry2[] idxs = fix.findIndexEntriesDEPRECATE(id, 0L, 0L, 0L, objectId, null, null, null);
			Query query = QueryUtil.createQuery(model.getModel(), FieldNames.FIELD_ID, id);
			query.field(FieldNames.FIELD_OBJECT_ID, objectId);
			query.setComparator(ComparatorEnumType.GROUP_OR);
			IndexEntry[] idxs = fix.findIndexEntries(query);
			if(idxs.length == 0) {
				throw new WriterException(String.format(IndexException.INDEX_NOT_FOUND));
			}
			if(idxs.length > 1) {
				throw new WriterException(String.format(IndexException.INDEX_COLLISION));
			}
			
			String path = FilePathUtil.getFilePath(idxs[0]);
			prepareDelete(idxs[0], path);
			super.delete(model);

			if(fix.removeIndexEntry(model)) {
				if(!useFileStore) {
					File f = new File(storageBase + "/" + path);
					if(!f.exists()) {
						throw new WriterException(String.format(WriterException.FILE_DOESNT_EXIST, path));
					}
					outBool = f.delete();
				}
				else {
					outBool = store.remove(store.getStoreName(idxs[0]));
				}
			}
			else {
				logger.error("Failed to delete index entry");
			}
		} catch (IndexException e) {
			
			throw new WriterException(e.getMessage());
		}
		return outBool;
	}
	
	private BaseRecord createMerge(BaseRecord model) {
		BaseRecord outModel = null;
		try {
			if(model.hasField(FieldNames.FIELD_OBJECT_ID)) {
				String oid = model.get(FieldNames.FIELD_OBJECT_ID);
				outModel = IOSystem.getActiveContext().getReader().read(model.getModel(), oid);
			}
			else if(model.hasField(FieldNames.FIELD_ID)) {
				long id = model.get(FieldNames.FIELD_ID);
				outModel = IOSystem.getActiveContext().getReader().read(model.getModel(), id);
			}
			if(outModel == null) {
				logger.error("Failed to load update model");
			}
			else {
				if(outModel.equals(model)) {
					logger.warn("CACHE CONFLICT WARNING");
					outModel = model.copyRecord();
				}
				ModelSchema schema = RecordFactory.getSchema(model.getModel());
				//model.getFields().forEach(f -> {
				for(FieldType f : model.getFields()) {
					FieldSchema fs = schema.getFieldSchema(f.getName());
					if(
						!fs.isEphemeral()
						&&
						!fs.isIdentity()
						&&
						!fs.isReadOnly()
						&&
						!fs.isVirtual()
					) {
						//logger.info(f.getName() + " = " + model.hasField(f.getName()));
						//Object v = model.getField(f.getName()).getValue();
						outModel.set(f.getName(), model.getField(f.getName()).getValue());
					}
					else {
						// logger.info("Skip merging " + f.getName());
					}
						
				};
			}
		}
		catch(Exception e) {
			logger.error(e);
			
		}
		return outModel;
	}

	public synchronized boolean write(BaseRecord model) throws WriterException {
		RecordOperation op = RecordOperation.CREATE;
		String objectId = (model.hasField(FieldNames.FIELD_OBJECT_ID) ? model.get(FieldNames.FIELD_OBJECT_ID) : null);
		long id = (model.hasField(FieldNames.FIELD_ID) ? model.get(FieldNames.FIELD_ID) : 0L);
		BaseRecord mergeModel = null;
		if(id > 0L || objectId != null) {
			op = RecordOperation.UPDATE;
			mergeModel = createMerge(model);
			
		}
		super.write(model);
		//prepareTranscription(op, model);
		
		FileIndexer idx = IOSystem.getActiveContext().getIndexManager().getInstance(model.getModel());
		try {
			String path = null;
			String oldPath = null;
			IndexEntry entry = null;
			IndexEntry oldEntry = null;
			if(op.equals(RecordOperation.CREATE)) {
				entry = idx.addIndexEntry(model);

			}
			else {
				Query query = QueryUtil.createQuery(model.getModel(), FieldNames.FIELD_ID, id);
				query.field(FieldNames.FIELD_OBJECT_ID, objectId);
				query.setComparator(ComparatorEnumType.GROUP_OR);
				
				//IndexEntry2 ent = idx.findIndexEntry(FieldNames.FIELD_OBJECT_ID, objectId);
				//IndexEntry2[] entries =  idx.findIndexEntriesDEPRECATE(id, 0L, 0L, 0L, objectId, null, null, null);
				IndexEntry[] entries = idx.findIndexEntries(query);
				if(entries.length > 0) {
					if(entries[0].getIndexType().equals(ModelNames.MODEL_INDEX_STORE)) {
						logger.info(entries[0].toString());
						logger.info(model.toString());
						throw new WriterException("Cannot write directly against the index store via objectId " + objectId);
					}
					oldEntry = entries[0];
					oldPath = FilePathUtil.getFilePath(oldEntry);
				}
				
				entry = idx.updateIndexEntry(model);
			}
			
			if(entry == null) {
				throw new WriterException(String.format(IndexException.INDEX_VALUE_NOT_FOUND, "New or updated index for " + objectId + "/" + id + " was not found"));
			}

			path = FilePathUtil.getFilePath(entry);
			prepareWrite(entry, path, oldPath);
			
			String contents = JSONUtil.exportObject( (mergeModel != null ? mergeModel : model), RecordSerializerConfig.getForeignFilteredModule());
			if(!useFileStore) {
				//String path = storageBase + "/" + model.get(FieldNames.FIELD_OBJECT_ID) + ".json";
				return FileUtil.emitFile(storageBase + "/" + path, contents);
			}
			else {
				if(op == RecordOperation.UPDATE) store.update(new BaseRecord[] {model});
				else store.add(new BaseRecord[] {model});
				return true;
			}
		}
		catch(IndexException | IOException e) {
			
			throw new WriterException(e.getMessage());
		}
	}
}
