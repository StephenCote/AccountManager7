package org.cote.accountmanager.io.file;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordDeserializerConfig;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.ComparatorEnumType;
import org.cote.accountmanager.util.CryptoUtil;
import org.cote.accountmanager.util.FileUtil;
import org.cote.accountmanager.util.JSONUtil;

public class FileIndexer {
	public static final Logger logger = LogManager.getLogger(FileIndexer.class);
	
	private boolean allLowerCase = true;
	private boolean trace = false;
	private String traceId = null;

	private boolean useIndexBasedId = true;
	private String indexStoreNameBase = "am7.index";
	private String indexStoreId = null;
	private String model = null;
	private String storageBase = null;
	private String indexStoreName = null;
	
	private String guid = null;
	
	private static boolean splitIndex = false;

	
	private IndexEntry idxEntry = null;
	
	private Map<String, Index> indices = new HashMap<>();
	private List<FieldSchema> indexFields = new ArrayList<>();
	private FileIndexManager fim = null;
	
	
	
	public static boolean isSplitIndex() {
		return splitIndex;
	}
	public static void setSplitIndex(boolean splitIndex) {
		FileIndexer.splitIndex = splitIndex;
	}
	
	public FileIndexer(String model, FileIndexManager fim) {
		this.storageBase = fim.getBase() + "/" + model;
		this.model= model;
		this.fim = fim;
		this.indexStoreName = this.indexStoreNameBase + "." + model + ".json";
		guid = UUID.randomUUID().toString();
		this.indexStoreId = model + FileIndexManager.INDEX_STORE_ID_SUFFIX;
		ModelSchema srec = RecordFactory.getSchema(model);
		srec.getFields().forEach(f -> {
			if(f.isIdentity() || f.isIndex()) {
				indexFields.add(f);
			}
		});
	}

	
	
	public String getGuid() {
		return guid;
	}
	public IndexEntry getIdxEntry() {
		return idxEntry;
	}
	public boolean isTrace() {
		return trace;
	}
	public void setTrace(boolean trace) {
		this.trace = trace;
		logger.info("**** " + model + " index trace " + (trace ? "enabled" : "disabled"));
		this.traceId = UUID.randomUUID().toString();
	}

	public Index findContainerIndex(BaseRecord rec) throws IndexException {
		String path = findContainerIndexFile(rec);
		return getIndexFile(path);
	}
	
	public IndexEntry findIndexEntry(BaseRecord rec) throws IndexException {
		if(!isIndexible(rec)) {
			throw new IndexException("Record is not indexible");
		}

		String objectId = (rec.hasField(FieldNames.FIELD_OBJECT_ID) ? rec.get(FieldNames.FIELD_OBJECT_ID) : null);
		if(objectId != null && objectId.equals(indexStoreId)) {
			if(trace && idxEntry == null) {
				logger.error("Index entry is null");
			}
			return idxEntry;
		}
		return findIndexEntry(getIndex(), rec);
	}
	
	public IndexEntry findIndexEntry(Index idx, BaseRecord rec) throws IndexException {
		if(idx == null) {
			throw new IndexException(IndexException.INDEX_NOT_FOUND);
		}

		IndexEntry outIdx = null;
		for(FieldSchema f : indexFields) {
			if(f.isIdentity() && rec.hasField(f.getName())) {
				outIdx = findIndexEntry(idx, f, rec.get(f.getName()));
				if(outIdx != null) {
					if(trace) {
						logger.info("Found index " + f.getName() + " = " + rec.get(f.getName()));
					}
					break;
				}
			}
		}
		
		return outIdx;

	}
	protected <T> IndexEntry findIndexEntry(String fieldName, T val) throws IndexException {
		return findIndexEntry(getIndex(), fieldName, val);
	}
	protected <T> IndexEntry findIndexEntry(Index idx, String fieldName, T val) throws IndexException {
		List<FieldSchema> schs = indexFields.stream().filter(o -> o.getName().equals(fieldName)).collect(Collectors.toList());
		if(schs.size() != 1) {
			throw new IndexException("Field " + fieldName + " is not indexed");
		}
		return findIndexEntry(getIndex(), schs.get(0), val);
	}
	protected <T> IndexEntry findIndexEntry(Index idx, FieldSchema fieldSchema, T val) throws IndexException {
		IndexEntry entry = null;
		
		FieldEnumType fet = FieldEnumType.valueOf(fieldSchema.getType().toUpperCase());
		String name = fieldSchema.getName();
		
		Optional<IndexEntry> oie = idx.getEntries().stream().filter(o -> {
			return matchIndexEntry(o, fet, name, val);
		}).findFirst();
		if(oie.isPresent()) {
			entry = oie.get();
		}
		return entry;
	}
	
	private <T> boolean matchIndexEntry(IndexEntry entry, FieldEnumType fet, String name, T val) {
		return matchIndexEntry(entry, fet, name, ComparatorEnumType.EQUALS, val);
	}
	private <T> boolean matchIndexEntry(IndexEntry entry, FieldEnumType fet, String name, ComparatorEnumType comp, T val) {
		boolean match = false;
		
		switch(fet) {
			case LONG:
				if(val == null) {
					logger.warn("Null long value for " + name);
				}
				else {
					long lval =  (long)val;
					long cval = entry.getValue(fet, name, 0L);
					match = (cval == lval);
				}
				break;
			case ENUM:
				if(val != null && val.equals("UNKNOWN")) {
					match = true;
					break;
				}
			case STRING:
				String sval = entry.getValue(fet, name, null);
				if(sval == null && val == null) {
					match = true;
				}
				else if(sval != null) {
					if(comp.equals(ComparatorEnumType.EQUALS)) {
						match = sval.equals((String)val);
					}
					else if(comp.equals(ComparatorEnumType.LIKE)) {
						match = sval.contains((String)val);
					}
					else {
						logger.warn("Unhandled long comparison: " + comp);
					}
				}
				
				break;
			default:
				logger.error("Unhandled value type: " + fet.toString());
				break;
		}
		return match;
	}
	

	
	public IndexEntry[] findIndexEntries(Query query) throws IndexException {
		//List<IndexEntry> entries = new ArrayList<>();
		Index idx = getIndex();
		List<IndexEntry> entries = idx.getEntries().stream().filter(o -> {
			return matchIndex(query, o);
		}).collect(Collectors.toList());
		
		return entries.toArray(new IndexEntry[0]);
	}
	
	private boolean matchIndex(BaseRecord query, IndexEntry idx) {
		if(!query.inherits(ModelNames.MODEL_QUERY)) {
			logger.error("Invalid query model");
			return false;
		}
		// logger.info("Match index by query");
		if(!query.hasField(FieldNames.FIELD_FIELDS)) {
			logger.error("No Fields!");
			return false;
		}
		String modelName = query.get(FieldNames.FIELD_TYPE);
		String itype = idx.get(FieldNames.FIELD_TYPE);
		if(modelName != null && !itype.equals(modelName)) {
			if(itype.equals(ModelNames.MODEL_INDEX_STORE)) {
				return false;
			}
			logger.error("No Query Model for " + idx.getModel() + " != " + itype + "!");
			return false;
		}
		return matchIndexToQuery(query, idx);
	}
	
	private boolean matchIndexToQuery(BaseRecord query, IndexEntry idx) {
		ComparatorEnumType comp = ComparatorEnumType.valueOf(query.get(FieldNames.FIELD_COMPARATOR));
		boolean bOr = comp.equals(ComparatorEnumType.GROUP_OR);
		boolean bAnd = comp.equals(ComparatorEnumType.GROUP_AND);
		if(!bAnd && !bOr) {
			logger.warn("Invalid comparator: " + comp);
			return false;
		}
		List<BaseRecord> fields = query.get(FieldNames.FIELD_FIELDS);
		
		int cnt = 0;
		int size = fields.size();
		for(BaseRecord f : fields) {
			
			if(matchIndexToField(query, f, idx)){
				cnt++;
			}
			if(cnt > 0 && bOr) {
				break;
			}
		}
		if(trace) {
			logger.info("Match " + model + " Index To Query " + (bOr ? "OR" : "AND") + ": " + cnt + "~" + size);
		}
		return ((bOr && cnt > 0) || (bAnd && cnt == size));
	}
	
	private <T> boolean matchIndexToField(BaseRecord query, BaseRecord field, IndexEntry idx) {
		ComparatorEnumType comp = ComparatorEnumType.valueOf(field.get(FieldNames.FIELD_COMPARATOR));
		if(comp.equals(ComparatorEnumType.GROUP_OR) || comp.equals(ComparatorEnumType.GROUP_AND)) {
			return matchIndexToQuery(field, idx);
		}
		String name = field.get(FieldNames.FIELD_NAME);
		boolean matches = false;
		List<FieldSchema> schs = indexFields.stream().filter(o -> o.getName().equals(name)).collect(Collectors.toList());
		
		/// If matching against an indexed field
		///
		if(schs.size() == 1) {
			FieldSchema fs = schs.get(0);
			if(fs.isVirtual() && fs.getBaseProperty() != null) {
				
			}
			FieldEnumType fet = FieldEnumType.valueOf(fs.getType().toUpperCase());
			matches = matchIndexEntry(idx, fet, name, comp, field.get(FieldNames.FIELD_VALUE));
		}
		/// If matching against an undexed field
		///    - The object will be loaded to perform the match
		else {
			ISearch search = IOSystem.getActiveContext().getSearch();
			if(search != null) {
				Query q = new Query(null, idx.get(FieldNames.FIELD_TYPE));
				q.setRequest(new String[0], 0L, 1);
				q.field(FieldNames.FIELD_ID, ComparatorEnumType.EQUALS, idx.getValue(FieldEnumType.LONG, FieldNames.FIELD_ID, 0L));
				try {
					QueryResult res = search.find(q);
					if(res.getCount() != 1) {
						logger.error("Expected one matching result for #" + idx.getValue(FieldEnumType.LONG, FieldNames.FIELD_ID, 0L));
					}
					else {
						BaseRecord compRec = res.getResults()[0];
						FieldType ft = compRec.getField(name);
						if(ft == null) {
							/// logger.error("Missing field: " + name);
							
						}
						else {
							switch(ft.getValueType()) {
								case ENUM:
									String eval = field.get(FieldNames.FIELD_VALUE);
									if(eval != null && eval.equals("UNKNOWN")) {
										matches = true;
										break;
									}
								case STRING:
									String value = field.get(FieldNames.FIELD_VALUE);
									String compValue = ft.getValue();
									if(value == null && compValue == null) {
										matches = true;
									}
									else if(value != null) {
										if(comp.equals(ComparatorEnumType.EQUALS)) {
											matches = value.equals((String)compValue);
										}
										else if(comp.equals(ComparatorEnumType.LIKE)) {
											matches = value.contains((String)compValue);
										}
									}
									break;
								case LONG:
									long valueL = field.get(FieldNames.FIELD_VALUE);
									long compValueL = ft.getValue();
									matches = (valueL == compValueL);
									break;
								default:
									logger.error("Unhandled type check: " + ft.getValueType().toString());
									break;
							}
						}
					}
					
				} catch (ReaderException e) {
					logger.error(e);
					
				}
			}
			else {
				logger.error("Unhandled filter field: " + name);
			}
		}
		return matches;
	}
	
	public void unloadIndex() {
		synchronized(indices) {
			fim.clearCache(this.model);
		}
	}
	public void clearIndices() {
		indices.clear();
	}
	public void flushIndex() throws IndexException{
		synchronized(indices) {
			Collection<Index> col = indices.values();
			for(Index v : col) {
				if(v.getChangeCount() > 0) {
					String ser = v.toString();
					if(ser == null) {
						throw new IndexException(IndexException.INDEX_SERIALIZATION_ERROR);
					}
					if(fim.isUseFileStore()) {
						try {
							BaseRecord indexRecord = RecordFactory.model(ModelNames.MODEL_INDEX_STORE).newInstance();
							indexRecord.set(FieldNames.FIELD_OBJECT_ID, indexStoreId);
							indexRecord.set(FieldNames.FIELD_ID, 1L);
							indexRecord.set(FieldNames.FIELD_NAME, indexStoreName);
							indexRecord.set(FieldNames.FIELD_INDEX_MODEL, model);
							indexRecord.set(FieldNames.FIELD_BYTE_STORE, ser.getBytes());
							if(trace) {
								logger.info("Flush " + model + " index with " + v.getEntries().size());
							}
							try {
								fim.getStore().update(new BaseRecord[] {indexRecord});
							} catch (IndexException e) {
								
								throw new IndexException(e.getMessage() + " (" + this.model + ")");
							}
						}
						catch(ModelNotFoundException | FieldException | ValueException e) {
							throw new IndexException(e.getMessage());
						}
					}
					else {
						if(!FileUtil.emitFile(v.getPath(), ser)) {
							throw new IndexException(IndexException.INDEX_IO_ERROR);
						}
					}
					v.resetChangeCount();
				}
				else {
					//logger.warn("FLUSH - NO CHANGE COUNT");
				}
			};
		}
	}
	
	public IndexEntry updateIndexEntry(BaseRecord rec) throws IndexException {
		return updateIndexEntry(getIndex(), rec);
	}
	
	private void applyRecordToEntry(BaseRecord rec, IndexEntry entry) throws FieldException, ModelNotFoundException, ValueException {
		
		ModelSchema srec = RecordFactory.getSchema(rec.getModel());
		List<BaseRecord> values = entry.get(FieldNames.FIELD_VALUES);
		values.clear();
		for(FieldSchema f : srec.getFields()) {

			if(f.isIdentity() || f.isIndex()) {
				if(trace) {
					logger.info("***** Add " + model + " index: " + f.getName() + " / " + f.getType());
				}
				
				if(rec.hasField(f.getName()) && rec.get(f.getName()) != null) {
					FieldEnumType fet = FieldEnumType.valueOf(f.getType().toUpperCase());
					BaseRecord erec = RecordFactory.newInstance(ModelNames.MODEL_INDEX_ENTRY_VALUE2);
					erec.set(FieldNames.FIELD_NAME, f.getName());
					erec.setFlex(FieldNames.FIELD_VALUE, fet, rec.get(f.getName()));
					values.add(erec);
				}
				else {
					logger.warn("Record " + rec.getModel() + " does not define an indexed field: " + f.getName());
					// logger.warn(rec.toString());
				}
			}
		}
		entry.set(FieldNames.FIELD_TYPE, rec.getModel());
	}
	
	public IndexEntry updateIndexEntry(Index idx, BaseRecord rec) throws IndexException {
		IndexEntry outEntry = null;
		synchronized(indices) {
			if(!rec.hasField(FieldNames.FIELD_ID) && !rec.hasField(FieldNames.FIELD_OBJECT_ID)) {
				throw new IndexException(String.format(IndexException.NOT_INDEXABLE, rec.getModel(), "name, id, or objectId"));
			}
			IndexEntry entry = findIndexEntry(idx, rec);
			if(entry == null) {
				throw new IndexException(IndexException.INDEX_ENTRY_NOT_FOUND);
			}
			try {
				applyRecordToEntry(rec, entry);
			} catch (FieldException | ModelNotFoundException | ValueException e) {
				logger.error(e);
			}
			idx.incrementChangeCount();
			outEntry = entry;
		}

		return outEntry;
	}
	private IndexEntry newIndexEntry(BaseRecord rec) throws IndexException {
		if(!isIndexible(rec)) {
			throw new IndexException(String.format(IndexException.INDEX_IDENTITY_NOT_FOUND, "id or objectId"));
		}
		
		IndexEntry entry = new IndexEntry();
		try {
			applyRecordToEntry(rec, entry);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		return entry;
	}
	
	private void removeIndexEntry(Index idx, IndexEntry entry, final FieldType idxField) throws IndexException {
		int size1 = idx.getEntries().size();
		List<BaseRecord> ents = idx.get(FieldNames.FIELD_ENTRIES);
		boolean remd = ents.removeIf(e -> {
			boolean match = matchIndexEntry(new IndexEntry(e), idxField.getValueType(), idxField.getName(), ComparatorEnumType.EQUALS, idxField.getValue());
			return match;
		});
		if(!remd) {
			throw new IndexException("**** FAILED TO REMOVE INDEX ENTRY");
		}
		if(size1 == idx.getEntries().size()) {
			throw new IndexException("**** FAILED TO REMOVE INDEX ENTRY BY " + idxField.getName() + ": " + size1 + " <> " + idx.getEntries().size());
		}
		
	}
	public boolean removeIndexEntry(BaseRecord rec) throws IndexException {
		if(!rec.hasField(FieldNames.FIELD_ID) && !rec.hasField(FieldNames.FIELD_OBJECT_ID)) {
			throw new IndexException(String.format(IndexException.NOT_INDEXABLE, rec.getModel()));
		}
		boolean outBool = false;

		Index idx = getIndex();
		if(idx == null) {
			throw new IndexException(IndexException.INDEX_NOT_FOUND);
		}
		IndexEntry entry = findIndexEntry(idx, rec);
		if(entry == null) {
			throw new IndexException(String.format(IndexException.INDEX_ENTRY_NOT_FOUND));
		}
		
		
		FieldType idxField = null;
		for(FieldSchema sch : indexFields) {
			if(sch.isIdentity() && rec.hasField(sch.getName())) {
				idxField = rec.getField(sch.getName());
				break;
			}
		}

		if(idxField == null) {
			throw new IndexException("Index field not found");
		}
		removeIndexEntry(idx, entry, idxField);
		idx.incrementChangeCount();
		outBool = true;
		
		return outBool;
	}
	public boolean isIndexible(BaseRecord rec) {
		if(!model.equals(rec.getModel()) && !rec.getModel().equals(ModelNames.MODEL_INDEX_STORE)){
			logger.error("Record " + rec.getModel() + " cannot be indexed by the " + model + " indexer");
			return false;
		}
		boolean hasIdentity = false;
		for(FieldSchema f : indexFields) {
			if(f.isIdentity() && rec.hasField(f.getName())) {
				hasIdentity = true;
				break;
			}
		};
		if(!hasIdentity) {
			logger.error("Record " + rec.getModel() + " does not define an identity field");
			logger.error(rec.toString());
		}
		return hasIdentity;
	}
	public IndexEntry addIndexEntry(BaseRecord rec) throws IndexException {
		IndexEntry outEntry = null;
		//logger.info(model + " add index entry (" + trace + ")");
		if(!isIndexible(rec)) {
			throw new IndexException(String.format(IndexException.NOT_INDEXABLE, rec.getModel()));
		}
		if(trace) {
			logger.info("Add Index Entry For Record - " + rec.toString());
		}
		synchronized(indices) {
			Index idx = getIndex();
			if(idx == null) {
				throw new IndexException(IndexException.INDEX_NOT_FOUND);
			}
			IndexEntry eidx = findIndexEntry(idx, rec);
			if(eidx != null) {
				logger.error("Index exists for " + this.model);
				if(trace) {
					logger.error(eidx.toString());
				}
				throw new IndexException(String.format(IndexException.INDEX_EXISTS, rec.getModel()));
			}
			
			IndexEntry entry = newIndexEntry(rec);


			List<BaseRecord> entries = idx.get(FieldNames.FIELD_ENTRIES);
			entries.add(entry);
			idx.incrementChangeCount();
			outEntry = entry;
			if(trace) {
				logger.info("Add Index Entry (#" + entries.size() + ") - " + entry.toString());
			}
			
		}
		return outEntry;
	}
	public Index getIndex() throws IndexException {
		String path = storageBase + "/" + indexStoreName;
		if(fim.isUseFileStore()) path = indexStoreId;
		return getIndexFile(path);
	}
	private Index getIndexFile(String path) throws IndexException {
		String hash = CryptoUtil.getDigestAsString(path);
		if(!indices.containsKey(hash)) {
			Index idx = null;
			if(fim.isUseFileStore()) {
				byte[] data = fim.getStore().get(indexStoreId);
				if(data == null || data.length == 0) {
					idx = new Index();
					try {
						BaseRecord indexRecord = RecordFactory.model(ModelNames.MODEL_INDEX_STORE).newInstance();
						indexRecord.set(FieldNames.FIELD_OBJECT_ID, indexStoreId);
						indexRecord.set(FieldNames.FIELD_ID, 1L);
						indexRecord.set(FieldNames.FIELD_NAME, indexStoreName);
						indexRecord.set(FieldNames.FIELD_INDEX_MODEL, model);
						indexRecord.set(FieldNames.FIELD_BYTE_STORE, JSONUtil.exportObject(idx).getBytes());
						IndexEntry entry = idxEntry = newIndexEntry(indexRecord);
						if(trace) {
							logger.warn("*** Idx Entry New " + this.model + ": " + (idxEntry != null));
						}
						
						List<IndexEntry> ents = idx.get(FieldNames.FIELD_ENTRIES);
						ents.add(idxEntry);
						idx.incrementChangeCount();

						if(trace) {
							logger.info("Creating " + model + " index with " + ents.size());
						}
						try {
							fim.getStore().add(new BaseRecord[] {indexRecord});
						} catch (IOException e) {
							logger.error(e.getMessage());
							throw new IndexException(e.getMessage());
						}
					}
					catch(ModelNotFoundException | FieldException | ValueException e) {
						throw new IndexException(e.getMessage());
					}
				}
				else {
					BaseRecord irec = JSONUtil.importObject(new String(data), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule());
					byte[] edata = irec.get(FieldNames.FIELD_BYTE_STORE);
					idx = JSONUtil.importObject(new String(edata), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete(Index.class);
					idx.getEntries().forEach(i -> {
						if(i.getObjectId() != null && i.getObjectId().equals(indexStoreId)) idxEntry = i;
					});
					if(trace) {
						logger.warn("*** Idx Entry Load " + this.model + " (" + idx.getEntries().size() + ") : " + (idxEntry != null));
						logger.warn(new String(edata));
					}

				}
			}
			else {
				File f = new File(path);
				
				if(!f.exists()) {
					idx = new Index();
					// logger.info("Staging initial index: " + path);
					FileUtil.emitFile(path, idx.toString());
				}
				else {
					idx = JSONUtil.importObject(FileUtil.getFileAsString(f), LooseRecord.class, RecordDeserializerConfig.getUnfilteredModule()).toConcrete(Index.class);
				}
			}
			idx.setPath(path);
			indices.put(hash, idx);
			loadHierarchy(idx);
			
		}
		return indices.get(hash);
	}
	
	public String findContainerIndexFile(BaseRecord rec) {
		String path = getFilePath(rec);
		File f = new File(path);
		File p = f.getParentFile();
		return (p.getPath() + "/" + indexStoreName).replace('\\', '/');
	}
	public String getFilePath(BaseRecord rec) {
		StringBuilder buff = new StringBuilder();
		
		buff.append(storageBase);
		buff.append("/" + rec.getModel());
		if(rec.hasField(FieldNames.FIELD_NAME)) {
			buff.append("/" + (String)rec.get(FieldNames.FIELD_NAME) + ".json");
		}
		String path = null;
		if(buff.length() > 0) path = buff.toString();
		if(allLowerCase && path != null) path = path.toLowerCase();
		return path;
	}
	
	public synchronized long nextId() throws IndexException {
		return nextId(getIndex());
	}
	public synchronized long nextId(Index idx) {
		long id = 100L;
		if(useIndexBasedId) {
			id = idx.nextId();
		}
		else {
			String path = storageBase + "/identity.json";
			String ids = FileUtil.getFileAsString(path);
			if(ids != null && ids.length() > 0) {
				id = Long.parseLong(ids);
			}
			id++;
			FileUtil.emitFile(path, Long.toString(id));
		}
		return id;
	}
	
	public synchronized void loadHierarchy(Index idx) throws IndexException {
		loadHierarchy(idx, null);
	}
	public synchronized void loadHierarchy(Index idx, String model) throws IndexException {

		for(IndexEntry e : idx.getEntries()) {
			String itype = e.get(FieldNames.FIELD_TYPE);
			if(model != null || !itype.equals(model)) {
				continue;
			}
			e.setParent(null);
			e.setContainer(null);
			e.getChildren().clear();
			e.getContains().clear();
		}
		for(IndexEntry e : idx.getEntries()) {
			String itype = e.get(FieldNames.FIELD_TYPE);
			if(model != null || !itype.equals(model)) {
				continue;
			}
			if(e.getParentId() > 0L) {
				IndexEntry p = findIndexEntry(idx, FieldNames.FIELD_ID, e.getParentId());
				if(p != null) {
					e.setParent(p);
					p.getChildren().add(e);
				}
			}
			if(e.getGroupId() > 0L) {
				IndexEntry g = findIndexEntry(idx, FieldNames.FIELD_ID, e.getGroupId());
				if(g != null) {
					g.getContains().add(e);
				}
			}
		}

	}
	
	
}
