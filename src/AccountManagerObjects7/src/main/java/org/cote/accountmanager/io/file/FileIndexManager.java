package org.cote.accountmanager.io.file;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.schema.ModelNames;

public class FileIndexManager {
	public static final Logger logger = LogManager.getLogger(FileIndexManager.class);
	
	private String base = null;
	private Map<String, FileIndexer> indexers = new HashMap<>();
	private IReader reader = null;
	private ISearch search = null;
	private boolean useFileStore = false;
	private FileStore fileStore = null;
	private String storageBase = null;
	//private String indexStoreName = null;
	public static final String INDEX_STORE_ID_SUFFIX = "-INDEX-FIM-AM7-000-000";

	


	public FileIndexManager(String base) {
		this.base = base;
		for(String s : ModelNames.MODELS) {
			getInstance(s);
		}
	}
	
	public String getBase() {
		return base;
	}
	

	public FileIndexer getInstance(String model) {
		if(!indexers.containsKey(model)) {
			if(base == null) {
				logger.error("FileIndex2 instance base not defined");
				return null;
			}
			indexers.put(model, new FileIndexer(model, this));
		}
		return indexers.get(model);
	}
	
	public void setInstance(String model, FileIndexer idx) {
		indexers.put(model, idx);
	}
	
	public void clearCache(String model) {
		synchronized(indexers) {
			if(indexers.containsKey(model)) {
				indexers.get(model).clearIndices();
				indexers.remove(model);
			}
		}
	}

	public void flush() {
		indexers.forEach((s, f) ->{
			try {
				f.flushIndex();
			} catch (IndexException e) {
				logger.error(e);
			}
		});
	}
	
	public void clearCache() {
		indexers.forEach((s, f) ->{
			f.clearIndices();
		});
		indexers.clear();
	}
	/*
	public void setSearch(IReader reader, ISearch search) {
		this.reader = reader;
		this.search = search;
	}
	*/

	public void setSearch(IReader reader, ISearch search) {
		this.reader = reader;
		this.search = search;
	}
	/*
	public String getIndexStoreName() {
		return indexStoreName;
	}
	public void setIndexStoreName(String indexStoreName) {
		this.indexStoreName = indexStoreName;
	}
	*/
	public FileStore getStore() {
		return fileStore;
	}

	public void setStore(FileStore fileStore) {
		this.fileStore = fileStore;
		useFileStore = (fileStore != null);
		/*
		if(useFileStore) {
			indexers.forEach((s, f) ->{
				try {
					f.getIndex();
				} catch (IndexException e) {
					logger.error(e);
				}
			});
		}
		*/
	}

	public boolean isUseFileStore() {
		return useFileStore;
	}

	public void setUseFileStore(boolean useFileStore) {
		this.useFileStore = useFileStore;
	}
}
